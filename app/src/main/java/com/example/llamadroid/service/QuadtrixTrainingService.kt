package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.QUADTRIX_LORA_REPO_PREFIX
import com.example.llamadroid.data.db.QuadtrixMetricEntity
import com.example.llamadroid.data.db.QuadtrixProfileEntity
import com.example.llamadroid.data.db.QuadtrixRunEntity
import com.example.llamadroid.quadtrix.QuadtrixCommandBuilder
import com.example.llamadroid.quadtrix.QuadtrixLogParser
import com.example.llamadroid.quadtrix.QuadtrixPaths
import com.example.llamadroid.quadtrix.QuadtrixWorkspaceManager
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.text.Charsets

data class QuadtrixRuntimeState(
    val status: String = "stopped",
    val profileName: String = "",
    val runId: Long? = null,
    val activeMode: String = "",
    val processAlive: Boolean = false,
    val startedAtMillis: Long? = null,
    val logs: String = "",
    val latestMetric: QuadtrixMetricEntity? = null,
    val tokenization: QuadtrixLogParser.TokenizationProgress? = null,
    val workerProgress: QuadtrixLogParser.WorkerProgress? = null,
    val webUrl: String? = null,
    val chatOutput: String = "",
    val chatLogs: String = "",
    val system: QuadtrixSystemSnapshot = QuadtrixSystemSnapshot(),
    val error: String? = null
)

data class QuadtrixSystemSnapshot(
    val totalRamMb: Long? = null,
    val freeRamMb: Long? = null,
    val ramUsageMb: Long? = null,
    val trainerRamMb: Long? = null,
    val cpuAvgTempC: Double? = null,
    val highestTempC: Double? = null,
    val batteryTempC: Double? = null,
    val batteryPercent: Int? = null
)

class QuadtrixTrainingService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var process: Process? = null
    private var mode: String = ""
    private var notificationTaskId: Int? = null
    private var telemetryJob: Job? = null
    private val registeredWorkspaceAdapters = mutableSetOf<String>()
    private var progressServerJob: Job? = null
    private var progressServerSocket: ServerSocket? = null
    private var activeProfileId: Long? = null
    private var activeWorkersFile: File? = null
    private var stopRequested = AtomicBoolean(false)
    private val notificationLogLines = ArrayDeque<String>()
    private var activeWorkerHost: String = ""
    private var activeWorkerPort: Int = 0
    private var activeThreads: Int = 0
    private var activeStartedAtMillis: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRAINING -> startProfile(intent.getLongExtra(EXTRA_PROFILE_ID, 0L), workerOnly = false)
            ACTION_START_WORKER -> startProfile(intent.getLongExtra(EXTRA_PROFILE_ID, 0L), workerOnly = true)
            ACTION_START_WEBUI -> startWebUi(intent.getLongExtra(EXTRA_PROFILE_ID, 0L))
            ACTION_STOP -> stopActiveProcess()
            ACTION_TOKENIZE_ONLY -> startProfile(intent.getLongExtra(EXTRA_PROFILE_ID, 0L), workerOnly = false, tokenizeOnly = true)
            ACTION_CONVERT_GGUF -> convertToGguf(
                profileId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L),
                checkpointPath = intent.getStringExtra(EXTRA_MODEL_PATH).orEmpty()
            )
            ACTION_CHAT -> runChat(
                profileId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L),
                modelPath = intent.getStringExtra(EXTRA_MODEL_PATH).orEmpty(),
                prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty(),
                tokens = intent.getIntExtra(EXTRA_TOKENS, 120)
            )
        }
        return START_NOT_STICKY
    }

    private fun startProfile(profileId: Long, workerOnly: Boolean, tokenizeOnly: Boolean = false) {
        val initialStatus = if (workerOnly) "worker_running" else if (tokenizeOnly) "tokenizing" else "running"
        startForegroundTask(if (workerOnly) "Quadtrix Worker" else if (tokenizeOnly) "Quadtrix Token Cache" else "Quadtrix Training")
        stopRequested.set(false)
        mode = if (workerOnly) "worker" else if (tokenizeOnly) "tokenize" else "training"
        _state.value = _state.value.copy(
            status = initialStatus,
            activeMode = mode,
            startedAtMillis = activeStartedAtMillis,
            error = null
        )
        scope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val profile = db.quadtrixProfileDao().getProfile(profileId)
            if (profile == null) {
                publishError("Quadtrix profile not found.")
                stopSelf()
                return@launch
            }
            val executable = BinaryRepository(applicationContext).getTieredBinary("quadtrix_trainer")
            if (executable == null) {
                publishError("Quadtrix trainer binary was not found.")
                stopSelf()
                return@launch
            }
            if (!workerOnly && profile.datasetPath.isBlank()) {
                publishError("Dataset path is required.")
                stopSelf()
                return@launch
            }

            val root = quadtrixRoot()
            val spec = QuadtrixCommandBuilder.trainingArgs(executable, root, profile, workerOnly, tokenizeOnly)
            activeProfileId = profile.id
            activeWorkersFile = spec.workersFile
            activeWorkerHost = profile.workerHost
            activeWorkerPort = profile.workerPort
            activeThreads = profile.threads
            if (profile.streamProgress) startProgressServer(profile)
            val runId = db.quadtrixRunDao().insertRun(
                QuadtrixRunEntity(
                    profileId = profile.id,
                    profileName = profile.name,
                    status = if (workerOnly) "worker_running" else if (tokenizeOnly) "tokenizing" else "running",
                    processMode = if (workerOnly) "worker" else if (tokenizeOnly) "tokenize" else "training",
                    logFilePath = logFile(profile.name).absolutePath,
                    modelOutputDir = spec.modelDir.absolutePath,
                    maxIter = profile.maxIters
                )
            )
            startTelemetry()
            appendLog("[APP] ${if (workerOnly) "Starting worker" else if (tokenizeOnly) "Preparing token cache" else "Starting training"} / Iniciando\n")
            runProcess(
                profile = profile,
                runId = runId,
                command = spec.args,
                workingDir = root,
                modelFile = spec.modelPath,
                ggufFile = spec.ggufPath,
                registerModelOnFinish = !workerOnly
            )
        }
    }

    private fun startWebUi(profileId: Long) {
        startForegroundTask("Quadtrix WebUI")
        stopRequested.set(false)
        scope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val profile = db.quadtrixProfileDao().getProfile(profileId)
            if (profile == null) {
                publishError("Quadtrix profile not found.")
                stopSelf()
                return@launch
            }
            val executable = BinaryRepository(applicationContext).getTieredBinary("quadtrix_trainer")
            if (executable == null) {
                publishError("Quadtrix trainer binary was not found.")
                stopSelf()
                return@launch
            }
            val root = quadtrixRoot()
            mode = "webui"
            startTelemetry()
            val url = "http://${profile.webHost}:${profile.webPort}"
            _state.value = _state.value.copy(status = "webui_running", activeMode = mode, startedAtMillis = activeStartedAtMillis, profileName = profile.name, webUrl = url, error = null)
            appendLog("[APP] Starting WebUI / Iniciando WebUI: $url\n")
            runProcess(
                profile = profile,
                runId = null,
                command = QuadtrixCommandBuilder.webArgs(executable, profile),
                workingDir = root,
                modelFile = null,
                ggufFile = null,
                registerModelOnFinish = false
            )
        }
    }

    private fun runChat(profileId: Long, modelPath: String, prompt: String, tokens: Int) {
        startForegroundTask("Quadtrix Generation")
        stopRequested.set(false)
        scope.launch {
            var proc: Process? = null
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val profile = db.quadtrixProfileDao().getProfile(profileId)
                val executable = BinaryRepository(applicationContext).getTieredBinary("quadtrix_trainer")
                if (profile == null || executable == null || modelPath.isBlank() || prompt.isBlank()) {
                    publishError("Profile, model, and prompt are required.")
                    return@launch
                }
                mode = "chat"
                _state.value = _state.value.copy(status = "chat_running", activeMode = mode, startedAtMillis = activeStartedAtMillis, chatOutput = "", chatLogs = "", error = null)
                updateQuadtrixNotification()
                val pb = ProcessBuilder(QuadtrixCommandBuilder.chatArgs(executable, profile, modelPath, prompt, tokens))
                    .directory(quadtrixRoot())
                    .redirectErrorStream(true)
                proc = pb.start()
                process = proc
                _state.value = _state.value.copy(status = "chat_running", processAlive = true)
                updateQuadtrixNotification()
                proc.outputStream.bufferedWriter().use { writer ->
                    writer.write(prompt)
                    writer.newLine()
                    writer.write("exit")
                    writer.newLine()
                }
                val output = try {
                    proc.inputStream.bufferedReader().readText()
                } catch (e: IOException) {
                    if (stopRequested.get()) "" else throw e
                }
                val exit = proc.waitFor()
                val marker = "Quadtrix>"
                val generated = output.substringAfter(marker, output).substringBefore("\u001B[1;32mYou>", "").trim()
                if (stopRequested.get()) notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
                else if (exit == 0) notificationTaskId?.let { UnifiedNotificationManager.completeTask(it, "Quadtrix generation finished") }
                else notificationTaskId?.let { UnifiedNotificationManager.failTask(it, "Quadtrix generation exited with $exit") }
                _state.value = _state.value.copy(
                    status = if (stopRequested.get()) "stopped" else if (exit == 0) "stopped" else "error",
                    processAlive = false,
                    chatOutput = generated.ifBlank { output.takeLast(4000) },
                    chatLogs = output.takeLast(8000),
                    error = if (exit == 0) null else "Exit code $exit"
                )
            } finally {
                proc?.takeIf { it.isAlive }?.destroyForcibly()
                cleanupProcess()
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun convertToGguf(profileId: Long, checkpointPath: String) {
        startForegroundTask("Quadtrix GGUF Conversion")
        stopRequested.set(false)
        scope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val profile = db.quadtrixProfileDao().getProfile(profileId)
            val executable = BinaryRepository(applicationContext).getTieredBinary("quadtrix_trainer")
            if (profile == null || executable == null || checkpointPath.isBlank()) {
                publishError("Profile and checkpoint are required.")
                cleanupProcess()
                stopSelf()
                return@launch
            }
            val (args, ggufFile) = QuadtrixCommandBuilder.convertToGgufArgs(executable, quadtrixRoot(), profile, checkpointPath)
            mode = "convert"
            _state.value = _state.value.copy(status = "converting_gguf", activeMode = mode, startedAtMillis = activeStartedAtMillis, profileName = profile.name, error = null)
            runProcess(
                profile = profile,
                runId = null,
                command = args,
                workingDir = quadtrixRoot(),
                modelFile = null,
                ggufFile = ggufFile,
                registerModelOnFinish = true
            )
        }
    }

    private suspend fun runProcess(
        profile: QuadtrixProfileEntity,
        runId: Long?,
        command: List<String>,
        workingDir: File,
        modelFile: File?,
        ggufFile: File?,
        registerModelOnFinish: Boolean
    ) {
        val db = AppDatabase.getDatabase(applicationContext)
        val logFile = logFile(profile.name)
        logFile.parentFile?.mkdirs()
        DebugLog.log("[Quadtrix] ${command.joinToString(" ")}")
        val pb = ProcessBuilder(command).directory(workingDir).redirectErrorStream(true)
        val proc = pb.start()
        process = proc
        _state.value = _state.value.copy(
            status = when (mode) {
                "worker" -> "worker_running"
                "webui" -> "webui_running"
                "tokenize" -> "tokenizing"
                "convert" -> "converting_gguf"
                "chat" -> "chat_running"
                else -> "running"
            },
            activeMode = mode,
            processAlive = true,
            startedAtMillis = activeStartedAtMillis,
            profileName = profile.name,
            runId = runId,
            error = null
        )
        updateQuadtrixNotification()
        var readerError: Throwable? = null
        try {
            withContext(Dispatchers.IO) {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val withNewline = "$line\n"
                        logFile.appendText(withNewline)
                        appendLog(withNewline)
                        rememberNotificationLogLine(line)
                        QuadtrixLogParser.parseTokenization(line)?.let { tokenization ->
                            updateTokenization(tokenization)
                        }
                        QuadtrixLogParser.parseWorkerProgress(line)?.let { worker ->
                            updateWorkerProgress(worker)
                        }
                        val metric = QuadtrixLogParser.parseMetric(line, profile.name, runId)
                        if (metric != null) {
                            db.quadtrixMetricDao().insertMetric(metric)
                            updateMetric(runId, metric)
                        }
                        QuadtrixLogParser.parseGgufPath(line)?.let { path ->
                            registerGgufModel(db, profile, File(path))
                        }
                        if (QuadtrixLogParser.isDone(line)) {
                            completeProgress(runId)
                        }
                        updateQuadtrixNotification()
                    }
                }
            }
        } catch (e: InterruptedIOException) {
            if (!stopRequested.get() && proc.isAlive) readerError = e
        } catch (e: IOException) {
            if (!stopRequested.get() && proc.isAlive) readerError = e
        }
        val exit = runCatching { proc.waitFor() }.getOrDefault(-1)
        val stopped = stopRequested.get()
        val finalStatus = when {
            stopped -> "stopped"
            readerError != null -> "error"
            exit == 0 -> "done"
            else -> "error"
        }
        if (exit == 0) completeProgress(runId)
        runId?.let { id ->
            db.quadtrixRunDao().getRun(id)?.let { run ->
                db.quadtrixRunDao().updateRun(
                    run.copy(
                        status = finalStatus,
                        finishedAt = System.currentTimeMillis(),
                        errorMessage = when {
                            stopped || exit == 0 -> null
                            readerError != null -> readerError?.message
                            else -> "Exit code $exit"
                        }
                    )
                )
            }
        }
        if (registerModelOnFinish) {
            if (modelFile != null && modelFile.exists()) registerQuadtrixModel(db, profile, modelFile)
            if (ggufFile != null && ggufFile.exists()) registerGgufModel(db, profile, ggufFile)
        }
        notificationTaskId?.let {
            if (stopped) UnifiedNotificationManager.dismissTask(it)
            else if (exit == 0) UnifiedNotificationManager.completeTask(it, "Quadtrix finished")
            else UnifiedNotificationManager.failTask(it, "Quadtrix exited with $exit")
        }
        cleanupProcess()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        _state.value = _state.value.copy(
            status = finalStatus,
            processAlive = false,
            error = when {
                stopped || exit == 0 -> null
                readerError != null -> readerError?.message
                else -> "Exit code $exit"
            }
        )
        stopSelf()
    }

    private suspend fun completeProgress(runId: Long?) {
        val metric = _state.value.latestMetric ?: return
        if (metric.maxIter <= 0) return
        val completed = metric.copy(iter = metric.maxIter, etaSeconds = 0)
        runId?.let { AppDatabase.getDatabase(applicationContext).quadtrixMetricDao().insertMetric(completed) }
        updateMetric(runId, completed)
    }

    private suspend fun updateMetric(runId: Long?, metric: QuadtrixMetricEntity) {
        runId?.let { id ->
            val db = AppDatabase.getDatabase(applicationContext)
            db.quadtrixRunDao().getRun(id)?.let { run ->
                db.quadtrixRunDao().updateRun(
                    run.copy(
                        latestEtaSeconds = metric.etaSeconds,
                        latestIter = metric.iter,
                        maxIter = metric.maxIter,
                        latestBatchLoss = metric.batchLoss,
                        latestTrainLoss = metric.trainLoss,
                        latestValLoss = metric.valLoss,
                        latestGradNorm = metric.gradNorm
                    )
                )
            }
        }
        _state.value = _state.value.copy(latestMetric = metric, status = if (_state.value.status == "tokenizing") "running" else _state.value.status)
        updateQuadtrixNotification()
    }

    private fun updateTokenization(tokenization: QuadtrixLogParser.TokenizationProgress) {
        _state.value = _state.value.copy(status = if (_state.value.status == "running") "tokenizing" else _state.value.status, tokenization = tokenization)
        updateQuadtrixNotification()
    }

    private fun updateWorkerProgress(worker: QuadtrixLogParser.WorkerProgress) {
        _state.value = _state.value.copy(
            status = if (mode == "worker") "worker_running" else _state.value.status,
            workerProgress = worker
        )
        updateQuadtrixNotification()
    }

    private fun rememberNotificationLogLine(line: String) {
        val compact = line.trim().replace(Regex("\\s+"), " ").take(180)
        if (compact.isBlank()) return
        notificationLogLines.addLast(compact)
        while (notificationLogLines.size > 5) notificationLogLines.removeFirst()
    }

    private fun updateQuadtrixNotification() {
        val taskId = notificationTaskId ?: return
        val state = _state.value
        val metric = state.latestMetric
        val tokenization = state.tokenization
        val worker = state.workerProgress
        val elapsedSeconds = activeStartedAtMillis
            .takeIf { it > 0L }
            ?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0L) / 1000L) }
            ?: 0L
        val elapsed = QuadtrixLogParser.formatDuration(elapsedSeconds)
        val details = notificationLogLines.toList()
        val lossText = metric?.batchLoss?.let { "%.4f".format(Locale.US, it) }
            ?: metric?.trainLoss?.let { "%.4f".format(Locale.US, it) }
            ?: worker?.loss?.let { "%.4f".format(Locale.US, it) }
            ?: "-"
        val progress = when (mode) {
            "tokenize" -> tokenization?.let { (it.percent / 100.0).toFloat().coerceIn(0f, 1f) } ?: -1f
            "training" -> metric?.let { if (it.maxIter > 0) it.iter.toFloat() / it.maxIter.toFloat() else -1f } ?: -1f
            else -> -1f
        }
        val text = when (mode) {
            "worker" -> if (worker != null) {
                getString(
                    R.string.quadtrix_notification_worker_progress,
                    activeWorkerHost.ifBlank { "0.0.0.0" },
                    activeWorkerPort,
                    activeThreads,
                    worker.iter,
                    worker.microSteps?.toString() ?: "-",
                    lossText,
                    elapsed
                )
            } else {
                getString(
                    R.string.quadtrix_notification_worker_waiting,
                    activeWorkerHost.ifBlank { "0.0.0.0" },
                    activeWorkerPort,
                    activeThreads,
                    elapsed
                )
            }
            "tokenize" -> getString(
                R.string.quadtrix_notification_tokenizing,
                tokenization?.stage ?: "-",
                tokenization?.percent?.toInt() ?: 0,
                tokenization?.tokens?.toString() ?: "-",
                elapsed,
                tokenization?.etaSeconds?.let { QuadtrixLogParser.formatDuration(it) } ?: "-"
            )
            "webui" -> getString(R.string.quadtrix_notification_webui, state.webUrl ?: elapsed)
            "convert" -> getString(R.string.quadtrix_notification_converting, elapsed)
            "chat" -> getString(R.string.quadtrix_notification_chat, elapsed)
            else -> if (metric != null) {
                val percent = if (metric.maxIter > 0) ((metric.iter * 100f) / metric.maxIter).toInt() else 0
                getString(
                    R.string.quadtrix_notification_training,
                    metric.iter,
                    metric.maxIter,
                    percent,
                    lossText,
                    metric.elapsedSeconds?.let { QuadtrixLogParser.formatDuration(it) } ?: elapsed,
                    metric.etaSeconds?.let { QuadtrixLogParser.formatDuration(it) } ?: "-"
                )
            } else {
                getString(R.string.quadtrix_notification_running, elapsed)
            }
        }
        UnifiedNotificationManager.updateProgressWithDetails(taskId, progress.coerceIn(-1f, 1f), text, details)
    }

    private fun stopActiveProcess() {
        scope.launch {
            stopRequested.set(true)
            val line = "[APP] Stop requested / Detencion solicitada"
            appendLog("$line\n")
            rememberNotificationLogLine(line)
            _state.value = _state.value.copy(status = "stopping", processAlive = process?.isAlive == true)
            updateQuadtrixNotification()
            process?.destroy()
            delay(2500)
            process?.takeIf { it.isAlive }?.destroyForcibly()
            if (process == null) {
                notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
                cleanupProcess()
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                _state.value = _state.value.copy(status = "stopped")
                stopSelf()
            }
        }
    }

    private fun startForegroundTask(title: String) {
        if (notificationTaskId != null) return
        activeStartedAtMillis = System.currentTimeMillis()
        notificationLogLines.clear()
        val (id, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.QUADTRIX,
            title
        )
        notificationTaskId = id
        startForeground(id, notification)
        UnifiedNotificationManager.updateProgressWithDetails(id, -1f, title, emptyList())
        WakeLockManager.acquire(applicationContext, "QuadtrixTrainingService")
        WakeLockManager.acquireWifiLock(applicationContext, "QuadtrixTrainingService")
    }

    private fun startTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            while (true) {
                val alive = process?.isAlive == true
                val correctedStatus = when {
                    alive && mode == "worker" -> "worker_running"
                    alive && mode == "webui" -> "webui_running"
                    alive && mode == "tokenize" -> "tokenizing"
                    alive && mode == "training" -> "running"
                    alive && mode == "convert" -> "converting_gguf"
                    alive && mode == "chat" -> "chat_running"
                    else -> _state.value.status
                }
                _state.value = _state.value.copy(status = correctedStatus, processAlive = alive, system = readSystemSnapshot())
                registerWorkspaceLoraAdapters()
                if (alive) updateQuadtrixNotification()
                delay(5000)
            }
        }
    }

    private fun readSystemSnapshot(): QuadtrixSystemSnapshot {
        val runtime = Runtime.getRuntime()
        val totalMb = runtime.maxMemory() / (1024 * 1024)
        val freeMb = runtime.freeMemory() / (1024 * 1024)
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 } ?: 100
        val batteryPct = level?.let { (it * 100) / scale }
        val batteryTemp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10.0 }
        val temps = readThermalTemps()
        return QuadtrixSystemSnapshot(
            totalRamMb = totalMb,
            freeRamMb = freeMb,
            ramUsageMb = usedMb,
            trainerRamMb = null,
            cpuAvgTempC = temps.takeIf { it.isNotEmpty() }?.average(),
            highestTempC = temps.maxOrNull(),
            batteryTempC = batteryTemp,
            batteryPercent = batteryPct
        )
    }

    private fun readThermalTemps(): List<Double> {
        val root = File("/sys/class/thermal")
        return root.listFiles { file -> file.name.startsWith("thermal_zone") }
            ?.mapNotNull { zone ->
                val type = File(zone, "type").readTextOrNull()?.lowercase(Locale.US).orEmpty()
                if (type.contains("battery")) return@mapNotNull null
                val raw = File(zone, "temp").readTextOrNull()?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                if (raw > 1000) raw / 1000.0 else raw
            }
            ?.filter { it in -20.0..130.0 }
            .orEmpty()
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    private fun appendLog(line: String) {
        _state.value = _state.value.copy(logs = (_state.value.logs + line).takeLast(512_000))
    }

    private suspend fun registerQuadtrixModel(db: AppDatabase, profile: QuadtrixProfileEntity, file: File) {
        db.modelDao().insertModel(
            ModelEntity(
                filename = file.name,
                path = file.absolutePath,
                sizeBytes = file.length(),
                type = ModelType.QUADTRIX,
                repoId = "quadtrix/${profile.name}",
                isDownloaded = false
            )
        )
    }

    private suspend fun registerGgufModel(db: AppDatabase, profile: QuadtrixProfileEntity, file: File) {
        if (!profile.showGgufInModels || !file.exists() || file.extension.lowercase(Locale.US) != "gguf") return
        db.modelDao().insertModel(
            ModelEntity(
                filename = file.name,
                path = file.absolutePath,
                sizeBytes = file.length(),
                type = ModelType.LLM,
                repoId = "quadtrix/${profile.name}",
                isDownloaded = false
            )
        )
    }

    private suspend fun registerWorkspaceLoraAdapters() = withContext(Dispatchers.IO) {
        val modelsRoot = File(quadtrixRoot(), QuadtrixPaths.MODELS)
        if (!modelsRoot.isDirectory) return@withContext
        val db = AppDatabase.getDatabase(applicationContext)
        modelsRoot.walkTopDown()
            .maxDepth(3)
            .filter { file ->
                file.isFile &&
                    file.extension.equals("gguf", ignoreCase = true) &&
                    file.absolutePath !in registeredWorkspaceAdapters
            }
            .take(256)
            .forEach { file ->
                if (!isLoraAdapterGguf(file)) return@forEach
                val existing = db.modelDao().getModelByFilename(file.name)
                if (existing == null || existing.path == file.absolutePath) {
                    db.modelDao().insertModel(
                        ModelEntity(
                            filename = file.name,
                            path = file.absolutePath,
                            sizeBytes = file.length(),
                            type = ModelType.LORA,
                            repoId = "$QUADTRIX_LORA_REPO_PREFIX${file.parentFile?.name.orEmpty()}",
                            isDownloaded = false
                        )
                    )
                    appendLog("[APP] Registered LoRA adapter / Adaptador LoRA registrado: ${file.name}\n")
                } else {
                    appendLog("[APP] LoRA adapter filename already exists / El nombre del adaptador LoRA ya existe: ${file.name}\n")
                }
                registeredWorkspaceAdapters += file.absolutePath
            }
    }

    private fun isLoraAdapterGguf(file: File): Boolean = runCatching {
        val probeSize = minOf(file.length(), 2L * 1024L * 1024L).toInt()
        if (probeSize < 24) return@runCatching false
        val bytes = ByteArray(probeSize)
        file.inputStream().buffered().use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read <= 0) break
                offset += read
            }
            if (offset < 4 ||
                bytes[0] != 'G'.code.toByte() ||
                bytes[1] != 'G'.code.toByte() ||
                bytes[2] != 'U'.code.toByte() ||
                bytes[3] != 'F'.code.toByte()
            ) return@runCatching false
            val header = bytes.copyOf(offset).toString(Charsets.ISO_8859_1)
            header.contains("adapter.type") && header.contains("lora")
        }
    }.getOrDefault(false)

    private fun startProgressServer(profile: QuadtrixProfileEntity) {
        progressServerJob?.cancel()
        progressServerJob = scope.launch {
            runCatching {
                val bindHost = if (profile.streamLanEnabled) profile.streamHost.ifBlank { "0.0.0.0" } else "127.0.0.1"
                ServerSocket().use { server ->
                    server.bind(InetSocketAddress(InetAddress.getByName(bindHost), profile.streamPort))
                    progressServerSocket = server
                    appendLog("[APP] Stream progress API on $bindHost:${profile.streamPort} / API de progreso en $bindHost:${profile.streamPort}\n")
                    while (true) {
                        val socket = withContext(Dispatchers.IO) { server.accept() }
                        launch { handleProgressClient(socket) }
                    }
                }
            }.onFailure {
                appendLog("[APP] Stream progress API stopped: ${it.message}\n")
            }
        }
    }

    private suspend fun handleProgressClient(socket: Socket) {
        socket.use { client ->
            val reader = client.getInputStream().bufferedReader()
            val request = reader.readLine().orEmpty()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val key = line.substringBefore(':').trim().lowercase(Locale.US)
                val value = line.substringAfter(':', "").trim()
                if (key.isNotBlank()) headers[key] = value
            }
            val parts = request.split(" ")
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()
            val authorized = isProgressAuthorized(client, headers)
            val body = when {
                !authorized -> jsonObject("error" to "Unauthorized")
                path.startsWith("/state") -> stateJson()
                path.startsWith("/metrics") -> metricsJson()
                path.startsWith("/logs") -> jsonObject("logs" to _state.value.logs)
                path.startsWith("/diagnostics") -> jsonObject("diagnostics" to _state.value.logs, "state" to stateJson())
                path.startsWith("/workers") && method == "GET" -> workersJson()
                path.startsWith("/stop") && method == "POST" -> {
                    stopActiveProcess()
                    jsonObject("ok" to "true")
                }
                path.startsWith("/resume") && method == "POST" -> {
                    val id = activeProfileId
                    if (id != null && process == null) startProfile(id, workerOnly = false)
                    jsonObject("ok" to (id != null).toString())
                }
                path.startsWith("/workers/add") && method == "POST" -> {
                    val endpoint = queryParam(path, "endpoint")
                    if (endpoint.isNotBlank()) updateWorker(endpoint, add = true)
                    jsonObject("ok" to "true")
                }
                path.startsWith("/workers/remove") && method == "POST" -> {
                    val endpoint = queryParam(path, "endpoint")
                    if (endpoint.isNotBlank()) updateWorker(endpoint, add = false)
                    jsonObject("ok" to "true")
                }
                else -> jsonObject("error" to "Unknown endpoint")
            }
            val bytes = body.toByteArray()
            client.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()
            )
            client.getOutputStream().write(bytes)
        }
    }

    private fun isProgressAuthorized(client: Socket, headers: Map<String, String>): Boolean {
        val token = activeProfileId?.let { id ->
            runCatching {
                kotlinx.coroutines.runBlocking {
                    AppDatabase.getDatabase(applicationContext).quadtrixProfileDao().getProfile(id)?.workerToken
                }
            }.getOrNull()
        }.orEmpty()
        if (token.isBlank()) return true
        val address = client.inetAddress
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return true
        val supplied = headers["x-adt-token"].orEmpty().ifBlank { queryParam(headers["x-request-path"].orEmpty(), "token") }
        return supplied == token
    }

    private fun stateJson(): String {
        val state = _state.value
        val metric = state.latestMetric
        return jsonObject(
            "status" to state.status,
            "profileName" to state.profileName,
            "activeMode" to state.activeMode,
            "processAlive" to state.processAlive.toString(),
            "iter" to (metric?.iter?.toString() ?: "0"),
            "maxIter" to (metric?.maxIter?.toString() ?: "0"),
            "batchLoss" to (metric?.batchLoss?.toString() ?: ""),
            "trainLoss" to (metric?.trainLoss?.toString() ?: ""),
            "valLoss" to (metric?.valLoss?.toString() ?: ""),
            "gradNorm" to (metric?.gradNorm?.toString() ?: ""),
            "elapsedSeconds" to (metric?.elapsedSeconds?.toString() ?: ""),
            "etaSeconds" to (metric?.etaSeconds?.toString() ?: ""),
            "cpuAvgTempC" to (state.system.cpuAvgTempC?.toString() ?: ""),
            "highestTempC" to (state.system.highestTempC?.toString() ?: ""),
            "batteryTempC" to (state.system.batteryTempC?.toString() ?: ""),
            "batteryPercent" to (state.system.batteryPercent?.toString() ?: ""),
            "tokenStage" to (state.tokenization?.stage ?: ""),
            "tokenPercent" to (state.tokenization?.percent?.toString() ?: ""),
            "tokenEtaSeconds" to (state.tokenization?.etaSeconds?.toString() ?: ""),
            "tokenDoneChars" to (state.tokenization?.doneChars?.toString() ?: ""),
            "tokenTotalChars" to (state.tokenization?.totalChars?.toString() ?: ""),
            "tokenCount" to (state.tokenization?.tokens?.toString() ?: ""),
            "workerIter" to (state.workerProgress?.iter?.toString() ?: ""),
            "workerMicroSteps" to (state.workerProgress?.microSteps?.toString() ?: ""),
            "workerLoss" to (state.workerProgress?.loss?.toString() ?: "")
        )
    }

    private suspend fun metricsJson(): String {
        val profileName = _state.value.profileName
        val metrics = if (profileName.isBlank()) emptyList() else AppDatabase.getDatabase(applicationContext)
            .quadtrixMetricDao()
            .getMetricsForProfile(profileName)
            .takeLast(2000)
        val body = metrics.joinToString(prefix = "[", postfix = "]") { metric ->
            jsonObject(
                "iter" to metric.iter.toString(),
                "maxIter" to metric.maxIter.toString(),
                "batchLoss" to (metric.batchLoss?.toString() ?: ""),
                "trainLoss" to (metric.trainLoss?.toString() ?: ""),
                "valLoss" to (metric.valLoss?.toString() ?: ""),
                "gradNorm" to (metric.gradNorm?.toString() ?: ""),
                "elapsedSeconds" to (metric.elapsedSeconds?.toString() ?: ""),
                "etaSeconds" to (metric.etaSeconds?.toString() ?: "")
            )
        }
        return "{\"metrics\":$body}"
    }

    private fun workersJson(): String {
        val workers = activeWorkersFile
            ?.takeIf { it.exists() }
            ?.readLines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
            .joinToString(prefix = "[", postfix = "]") { "\"${it.jsonEscape()}\"" }
        return "{\"workers\":$workers}"
    }

    private fun updateWorker(endpoint: String, add: Boolean) {
        val file = activeWorkersFile ?: return
        file.parentFile?.mkdirs()
        val current = if (file.exists()) file.readLines().map { it.trim() }.filter { it.isNotBlank() }.toMutableList() else mutableListOf()
        val normalized = "1 ${endpoint.trim().removePrefix("1 ").removePrefix("0 ").trim()}"
        val hostOnly = normalized.removePrefix("1 ")
        current.removeAll { it.removePrefix("1 ").removePrefix("0 ").trim() == hostOnly }
        if (add) current += normalized
        file.writeText(current.joinToString("\n"))
        appendLog("[APP] Worker ${if (add) "added" else "removed"}: $hostOnly\n")
    }

    private fun queryParam(path: String, name: String): String {
        val query = path.substringAfter('?', "")
        return query.split('&').firstNotNullOfOrNull { pair ->
            val key = pair.substringBefore('=')
            if (key == name) URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") else null
        }.orEmpty()
    }

    private fun jsonObject(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${key.jsonEscape()}\":\"${value.jsonEscape()}\""
        }

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun publishError(message: String) {
        DebugLog.log("[Quadtrix] $message")
        _state.value = _state.value.copy(status = "error", error = message)
    }

    private fun cleanupProcess() {
        process = null
        telemetryJob?.cancel()
        telemetryJob = null
        progressServerJob?.cancel()
        progressServerJob = null
        runCatching { progressServerSocket?.close() }
        progressServerSocket = null
        activeProfileId = null
        activeWorkersFile = null
        WakeLockManager.release("QuadtrixTrainingService")
        WakeLockManager.releaseWifiLock("QuadtrixTrainingService")
        notificationTaskId = null
    }

    override fun onDestroy() {
        cleanupProcess()
        super.onDestroy()
    }

    private fun quadtrixRoot(): File =
        QuadtrixWorkspaceManager.rootForRuntime(
            applicationContext,
            SettingsRepository(applicationContext).quadtrixWorkspacePath.value
        )

    private fun logFile(profileName: String): File =
        File(quadtrixRoot(), "${QuadtrixPaths.LOGS}/${QuadtrixPaths.safeName(profileName)}.log")

    companion object {
        private const val ACTION_START_TRAINING = "quadtrix_start_training"
        private const val ACTION_START_WORKER = "quadtrix_start_worker"
        private const val ACTION_START_WEBUI = "quadtrix_start_webui"
        private const val ACTION_TOKENIZE_ONLY = "quadtrix_tokenize_only"
        const val ACTION_STOP = "quadtrix_stop"
        private const val ACTION_CHAT = "quadtrix_chat"
        private const val ACTION_CONVERT_GGUF = "quadtrix_convert_gguf"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_MODEL_PATH = "model_path"
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_TOKENS = "tokens"

        private val _state = MutableStateFlow(QuadtrixRuntimeState())
        val state = _state.asStateFlow()

        fun startTraining(context: Context, profileId: Long) {
            ContextCompat.startForegroundService(context, Intent(context, QuadtrixTrainingService::class.java).apply {
                action = ACTION_START_TRAINING
                putExtra(EXTRA_PROFILE_ID, profileId)
            })
        }

        fun startWorker(context: Context, profileId: Long) {
            ContextCompat.startForegroundService(context, Intent(context, QuadtrixTrainingService::class.java).apply {
                action = ACTION_START_WORKER
                putExtra(EXTRA_PROFILE_ID, profileId)
            })
        }

        fun prepareTokenCache(context: Context, profileId: Long) {
            ContextCompat.startForegroundService(context, Intent(context, QuadtrixTrainingService::class.java).apply {
                action = ACTION_TOKENIZE_ONLY
                putExtra(EXTRA_PROFILE_ID, profileId)
            })
        }

        fun startWebUi(context: Context, profileId: Long) {
            ContextCompat.startForegroundService(context, Intent(context, QuadtrixTrainingService::class.java).apply {
                action = ACTION_START_WEBUI
                putExtra(EXTRA_PROFILE_ID, profileId)
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, QuadtrixTrainingService::class.java).apply {
                action = ACTION_STOP
            })
        }

        fun chat(context: Context, profileId: Long, modelPath: String, prompt: String, tokens: Int) {
            ContextCompat.startForegroundService(context, Intent(context, QuadtrixTrainingService::class.java).apply {
                action = ACTION_CHAT
                putExtra(EXTRA_PROFILE_ID, profileId)
                putExtra(EXTRA_MODEL_PATH, modelPath)
                putExtra(EXTRA_PROMPT, prompt)
                putExtra(EXTRA_TOKENS, tokens)
            })
        }

        fun convertToGguf(context: Context, profileId: Long, checkpointPath: String) {
            ContextCompat.startForegroundService(context, Intent(context, QuadtrixTrainingService::class.java).apply {
                action = ACTION_CONVERT_GGUF
                putExtra(EXTRA_PROFILE_ID, profileId)
                putExtra(EXTRA_MODEL_PATH, checkpointPath)
            })
        }

        fun clearLogs() {
            _state.value = _state.value.copy(logs = "", chatLogs = "")
        }
    }
}
