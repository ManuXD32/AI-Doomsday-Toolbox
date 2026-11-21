package com.example.llamadroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BatterySnapshot
import com.example.llamadroid.branchy.BranchyClusterRun
import com.example.llamadroid.branchy.BranchyCheckpointRequest
import com.example.llamadroid.branchy.BranchyCkptStage
import com.example.llamadroid.branchy.BranchyControlFile
import com.example.llamadroid.branchy.BranchyRebalance
import com.example.llamadroid.branchy.BranchyDataMarker
import com.example.llamadroid.branchy.BranchyEpochs
import com.example.llamadroid.branchy.BranchyHelperWatch
import com.example.llamadroid.branchy.BranchyStopPolicy
import com.example.llamadroid.branchy.BranchyCommandBuilder
import com.example.llamadroid.branchy.BranchyEvent
import com.example.llamadroid.branchy.BranchyEventParser
import com.example.llamadroid.branchy.BranchyFormat
import com.example.llamadroid.branchy.BranchyGuards
import com.example.llamadroid.branchy.BranchyLiveDevice
import com.example.llamadroid.branchy.BranchyNative
import com.example.llamadroid.branchy.BranchyPauseReason
import com.example.llamadroid.branchy.BranchyPlan
import com.example.llamadroid.branchy.BranchyPhase
import com.example.llamadroid.branchy.BranchyRunConfig
import com.example.llamadroid.branchy.BranchyRunJson
import com.example.llamadroid.branchy.BranchyRunRepository
import com.example.llamadroid.branchy.BranchyRunStatus
import com.example.llamadroid.branchy.BranchyTrainingState
import com.example.llamadroid.branchy.ClusterJsonResult
import com.example.llamadroid.branchy.GuardAction
import com.example.llamadroid.branchy.RunSource
import com.example.llamadroid.branchy.RunStage
import com.example.llamadroid.branchy.TokenizerInput
import com.example.llamadroid.branchy.cluster.BranchyClusterRepository
import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import com.example.llamadroid.branchy.cluster.BranchyClusterToken
import com.example.llamadroid.branchy.cluster.BranchyHelperProbe
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.training.DatasetHelpers
import com.example.llamadroid.training.DatasetRepository
import com.example.llamadroid.training.DatasetStatus
import com.example.llamadroid.training.TrainingDatasetEntity
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Foreground service that runs the Branchy pipeline of one run at a time: tokenizer-train, prepare per
 * dataset, run.json, train, then the next stage with `--init-from` the previous final checkpoint.
 * Pause sends SIGTERM (the native side writes a checkpoint and exits). Stop is a pause that also removes
 * the notification: runs are never discarded, the user resumes them from the run list.
 */
class BranchyTrainingService : Service() {
    private enum class StopMode { NONE, PAUSE, STOP }

    private sealed class Outcome {
        data object Done : Outcome()
        data object Paused : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /** Thrown inside the pipeline once pause/stop was requested; never an error. */
    private class PausedException : Exception()

    private class NativeResult(
        val exit: Int,
        val error: String?,
        val sawPaused: Boolean,
        val done: BranchyEvent.Done?,
        val paused: BranchyEvent.Paused? = null
    )

    /** Files and names of a multi-device launch: cluster.json, the 0600 token file and device display names. */
    private class ClusterLaunch(val jsonFile: File, val tokenFile: File, val names: Map<String, String>)

    private class TrainTracker(var tokensPerStep: Long = 0L, var totalSteps: Long = 0L) {
        var clusterNames: Map<String, String> = emptyMap()
        var lastLoss: Double? = null
        var lastVal: Double? = null
        var lastStep: Long = 0L
        var lastDbAt: Long = 0L
        var lastNotifyAt: Long = 0L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repo: BranchyRunRepository
    private var runJob: Job? = null
    private var guardJob: Job? = null

    @Volatile private var process: Process? = null
    @Volatile private var stopMode = StopMode.NONE
    @Volatile private var stopReason = BranchyPauseReason.USER
    @Volatile private var activeRunId: Long? = null
    @Volatile private var runName: String = ""
    @Volatile private var pausedByGuard = false
    @Volatile private var finishing = false
    @Volatile private var guardConfig: BranchyRunConfig? = null
    @Volatile private var controlWriter: BranchyControlFile.Writer? = null
    private var watchJob: Job? = null
    private val helperProbe by lazy { BranchyHelperProbe(applicationContext) }
    private var foregroundStarted = false
    private var wakeHeld = false
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = BranchyRunRepository(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start (also from a notification action on a dead service) must call startForeground first.
        if (!ensureForeground()) return START_NOT_STICKY
        val runId = intent?.getLongExtra(EXTRA_RUN_ID, 0L) ?: 0L
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> startRun(runId)
            ACTION_PAUSE -> {
                if (runJob?.isActive == true) requestStop(StopMode.PAUSE, BranchyPauseReason.USER) else finishIfIdle()
            }
            ACTION_STOP -> {
                if (runJob?.isActive == true) {
                    requestStop(StopMode.STOP, BranchyPauseReason.USER)
                } else {
                    guardJob?.cancel()
                    pausedByGuard = false
                    removeNotificationAndStop()
                }
            }
            ACTION_SKIP_HELPER -> {
                val helperId = intent.getStringExtra(EXTRA_HELPER_ID)
                if (runJob?.isActive == true && !helperId.isNullOrBlank()) skipHelper(helperId) else finishIfIdle()
            }
            ACTION_CONTROL -> {
                val command = intent.getStringExtra(EXTRA_COMMAND)
                val helperId = intent.getStringExtra(EXTRA_HELPER_ID)
                if (runJob?.isActive == true && !command.isNullOrBlank()) sendControl(command, helperId) else finishIfIdle()
            }
            else -> finishIfIdle()
        }
        return START_NOT_STICKY
    }

    // region run lifecycle

    private fun startRun(runId: Long) {
        if (runId <= 0L) {
            finishIfIdle()
            return
        }
        if (runJob?.isActive == true) {
            if (activeRunId != runId) DebugLog.log("[Branchy] Ignored start of run $runId: run $activeRunId is active")
            return
        }
        activeRunId = runId
        stopMode = StopMode.NONE
        stopReason = BranchyPauseReason.USER
        pausedByGuard = false
        finishing = false
        val previous = BranchyTrainingState.state.value
        if (previous.runId != runId) BranchyTrainingState.reset()
        BranchyTrainingState.update {
            it.copy(runId = runId, phase = BranchyPhase.PREPARE, message = null, pauseReason = null)
        }
        acquireWake()
        notifyNow()
        runJob = scope.launch {
            val outcome = try {
                runPipeline(runId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: PausedException) {
                Outcome.Paused
            } catch (e: Exception) {
                if (BranchyStopPolicy.endsPaused(stopMode != StopMode.NONE)) {
                    // After a user stop any failure (for example the stdout IOException after destroy()) is a pause.
                    Outcome.Paused
                } else {
                    DebugLog.log("[Branchy] Run $runId failed: ${e.message}")
                    Outcome.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
                }
            }
            finishRun(runId, outcome)
        }
    }

    private suspend fun finishRun(runId: Long, outcome: Outcome) {
        finishing = true
        process = null
        watchJob?.cancel()
        watchJob = null
        releaseWake()
        // Nothing is connected or in progress once the run ended, paused or failed.
        BranchyTrainingState.update { BranchyTrainingState.endRun(it) }
        when (outcome) {
            Outcome.Done -> {
                guardJob?.cancel()
                repo.setStatus(runId, BranchyRunStatus.DONE)
                BranchyTrainingState.update { it.copy(phase = BranchyPhase.DONE, message = null) }
                detachAndStop()
            }
            Outcome.Paused -> {
                val reason = if (stopMode == StopMode.NONE) BranchyPauseReason.USER else stopReason
                repo.setStatus(runId, BranchyRunStatus.PAUSED)
                BranchyTrainingState.update { it.copy(phase = BranchyPhase.PAUSED, pauseReason = reason) }
                val waitForGuard = stopMode == StopMode.PAUSE && reason != BranchyPauseReason.USER &&
                    guardConfig?.autoResume == true
                when {
                    stopMode == StopMode.STOP -> {
                        guardJob?.cancel()
                        withContext(Dispatchers.Main) { removeNotificationAndStop() }
                    }
                    waitForGuard -> {
                        pausedByGuard = true
                        notifyNow()
                    }
                    else -> {
                        guardJob?.cancel()
                        detachAndStop()
                    }
                }
            }
            is Outcome.Failed -> {
                guardJob?.cancel()
                repo.setStatus(runId, BranchyRunStatus.FAILED, outcome.message)
                BranchyTrainingState.update { it.copy(phase = BranchyPhase.ERROR, message = outcome.message) }
                detachAndStop()
            }
        }
    }

    private suspend fun detachAndStop() = withContext(Dispatchers.Main) {
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
        foregroundStarted = false
        notifyNow()
        stopSelf()
    }

    private fun removeNotificationAndStop() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foregroundStarted = false
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        stopSelf()
    }

    private fun finishIfIdle() {
        if (runJob?.isActive == true || pausedByGuard) return
        removeNotificationAndStop()
    }

    private fun requestStop(mode: StopMode, reason: BranchyPauseReason) {
        if (runJob?.isActive != true) return
        if (stopMode == StopMode.STOP) return
        stopReason = reason
        stopMode = mode
        BranchyTrainingState.update { it.copy(phase = BranchyPhase.PAUSING, pauseReason = reason) }
        notifyNow()
        process?.destroy()
        scope.launch {
            delay(PAUSE_GRACE_MS)
            process?.takeIf { it.isAlive }?.destroyForcibly()
        }
    }

    private fun checkNotStopped() {
        if (stopMode != StopMode.NONE) throw PausedException()
    }

    // endregion

    // region pipeline

    private suspend fun runPipeline(runId: Long): Outcome {
        val entity = repo.get(runId) ?: throw IllegalStateException(getString(R.string.branchy_error_run_missing))
        if (entity.status == BranchyRunStatus.DONE.value) return Outcome.Done
        var config = BranchyRunConfig.decode(entity.configJson)
            ?: throw IllegalStateException(getString(R.string.branchy_error_config_invalid))
        val exe = BinaryRepository(applicationContext).getTieredBinary("branchy_trainer")?.absolutePath
            ?: throw IllegalStateException(getString(R.string.branchy_error_binary_missing))
        runName = entity.name
        val runDir = File(entity.runDir).apply { mkdirs() }
        guardConfig = config
        startGuards(runId)

        var plan = config.plan
        if (plan == null) {
            setPhase(BranchyPhase.PREPARE, null)
            plan = BranchyNative(applicationContext)
                .plan(config.planInput.copy(allowStorageOffload = config.allowStorageOffload))
            config = config.copy(plan = plan)
            repo.saveConfig(runId, config)
        }
        if (!plan.fits) {
            throw IllegalStateException(plan.reason ?: getString(R.string.branchy_error_plan_does_not_fit))
        }
        checkNotStopped()

        val stages = config.effectiveStages()
        val datasetRepo = DatasetRepository(applicationContext)
        val datasets = resolveDatasets(datasetRepo, stages)
        BranchyTrainingState.update { it.copy(stageCount = stages.size) }

        for (k in config.currentStage until stages.size) {
            checkNotStopped()
            if (config.currentStage != k) {
                config = config.copy(currentStage = k)
                repo.saveConfig(runId, config)
            }
            repo.setStage(runId, k)
            BranchyTrainingState.update {
                it.copy(stage = k, lossPoints = emptyList(), valPoints = emptyList(), lastStep = null, micro = null)
            }
            config = runStage(runId, exe, runDir, config, stages, k, datasetRepo, datasets)
        }
        return Outcome.Done
    }

    /** Loads every dataset of every stage and requires it to be ready (refreshing in-flight downloads). */
    private suspend fun resolveDatasets(
        datasetRepo: DatasetRepository,
        stages: List<RunStage>
    ): Map<Long, TrainingDatasetEntity> {
        val result = linkedMapOf<Long, TrainingDatasetEntity>()
        stages.flatMap { it.mix }.map { it.datasetId }.distinct().forEach { id ->
            val row = datasetRepo.refreshStatus(id)
                ?: throw IllegalStateException(getString(R.string.branchy_error_dataset_missing, id.toString()))
            if (row.status != DatasetStatus.READY.value) {
                throw IllegalStateException(getString(R.string.branchy_error_dataset_not_ready, row.name))
            }
            result[id] = row
        }
        return result
    }

    private suspend fun runStage(
        runId: Long,
        exe: String,
        runDir: File,
        config: BranchyRunConfig,
        stages: List<RunStage>,
        k: Int,
        datasetRepo: DatasetRepository,
        datasets: Map<Long, TrainingDatasetEntity>
    ): BranchyRunConfig {
        val stage = stages[k]
        val mix = stage.mix.filter { it.weight > 0.0 }
        if (mix.isEmpty()) throw IllegalStateException(getString(R.string.branchy_error_no_data))
        val plan = config.plan ?: throw IllegalStateException(getString(R.string.branchy_error_plan_does_not_fit))
        val dataDir = File(runDir, "data").apply { mkdirs() }
        val tokenizer = File(runDir, "tokenizer.json")
        // Warm the VPN paths to the helpers while the phone tokenizes and prepares data (result is ignored).
        BranchyHelperWatch.warm(scope, helperProbe, selectedHelpers(config))

        // 1. Tokenizer, trained once on the datasets of all stages and shared by every stage.
        if (!tokenizer.isFile) {
            repo.setStatus(runId, BranchyRunStatus.PREPARING)
            setPhase(BranchyPhase.TOKENIZER, null)
            val inputs = stages.flatMap { it.mix }.map { it.datasetId }.distinct().flatMap { id ->
                val ds = datasets.getValue(id)
                val cols = DatasetHelpers.columnsArg(DatasetHelpers.parseColumnMap(ds.columnMapJson))
                datasetRepo.dataFiles(id).map { TokenizerInput(it.absolutePath, ds.format, cols) }
            }
            if (inputs.isEmpty()) throw IllegalStateException(getString(R.string.branchy_error_no_data))
            val tmp = File(runDir, "tokenizer.tmp.json").also { it.delete() }
            val res = runNative(
                BranchyCommandBuilder.tokenizerTrain(
                    exe, tmp.absolutePath, config.vocab, inputs, TOKENIZER_MAX_BYTES
                ),
                runDir, File(runDir, "stderr.log")
            ) { ev -> if (ev is BranchyEvent.Progress) onProgress(ev, null) }
            if (stopMode != StopMode.NONE) {
                tmp.delete()
                throw PausedException()
            }
            if (res.exit != 0 || !tmp.isFile) {
                tmp.delete()
                throw IllegalStateException(failureText(res, File(runDir, "stderr.log")))
            }
            if (!tmp.renameTo(tokenizer)) throw IllegalStateException(getString(R.string.branchy_error_native, -1))
        }

        // 2. Prepare each dataset once (a `.done` marker next to the `.btok` proves a clean finish).
        repo.setStatus(runId, BranchyRunStatus.PREPARING)
        val sources = mutableListOf<RunSource>()
        var trainTokens = 0L
        var tokensKnown = true
        val prefix = if (k == 0) "" else "../"
        for (entry in mix) {
            val ds = datasets.getValue(entry.datasetId)
            val files = datasetRepo.dataFiles(ds.id)
            if (files.isEmpty()) throw IllegalStateException(getString(R.string.branchy_error_dataset_not_ready, ds.name))
            val totalBytes = files.sumOf { it.length() }.coerceAtLeast(1L).toDouble()
            files.forEachIndexed { i, file ->
                checkNotStopped()
                val outName = if (files.size == 1) ds.id.toString() else "${ds.id}-$i"
                val out = File(dataDir, "$outName.btok")
                val marker = File(dataDir, "$outName.done")
                if (!(out.isFile && marker.isFile)) {
                    setPhase(BranchyPhase.PREPARE, ds.name)
                    cleanPrepared(dataDir, outName)
                    // rowCount holds the user's cap for every source (curated, HF and file; see DatasetLibraryViewModel.applyCap).
                    val maxRows = ds.rowCount
                    var preparedTokens = 0L
                    val res = runNative(
                        BranchyCommandBuilder.prepare(
                            exe, tokenizer.absolutePath, file.absolutePath, ds.format,
                            DatasetHelpers.columnsArg(DatasetHelpers.parseColumnMap(ds.columnMapJson)),
                            out.absolutePath, maxRows
                        ),
                        runDir, File(runDir, "stderr.log")
                    ) { ev ->
                        if (ev is BranchyEvent.Progress) onProgress(ev, ds.name)
                        if (ev is BranchyEvent.Prepared) preparedTokens = ev.trainTokens
                    }
                    if (stopMode != StopMode.NONE) {
                        cleanPrepared(dataDir, outName)
                        throw PausedException()
                    }
                    if (res.exit != 0 || !out.isFile) {
                        cleanPrepared(dataDir, outName)
                        throw IllegalStateException(failureText(res, File(runDir, "stderr.log")))
                    }
                    marker.writeText(BranchyDataMarker.text(preparedTokens))
                }
                val sourceTokens = BranchyDataMarker.trainTokens(runCatching { marker.readText() }.getOrNull())
                if (sourceTokens > 0L) trainTokens += sourceTokens else tokensKnown = false
                sources += RunSource("${prefix}data/$outName.btok", entry.weight * file.length() / totalBytes)
            }
        }
        checkNotStopped()

        // 3. run.json for this stage (stage 0 lives in the run dir, later stages in stage<k>/ beside shared data).
        val stageDir = stageDir(runDir, k).apply { mkdirs() }
        val stageSteps = if (config.stages.isNotEmpty() && stage.tokensTarget > 0.0) {
            BranchyRunJson.stepsForTokens(stage.tokensTarget, plan.train.microbatch, plan.train.gradAccum, plan.layout.context)
        } else plan.train.steps
        val tokensPerStep = plan.train.microbatch.toLong() * plan.train.gradAccum * plan.layout.context
        // On resume the steps already written to run.json stay; a new stage is capped at `epochs` passes over its data.
        val steps = (if (latestCheckpoint(stageDir) != null) {
            BranchyEpochs.writtenSteps(runCatching { File(stageDir, "run.json").readText() }.getOrNull())
        } else null) ?: BranchyEpochs.steps(stageSteps, config.epochs, if (tokensKnown) trainTokens else 0L, tokensPerStep)
        val root = BranchyRunJson.build(
            name = if (k == 0) runName else "$runName-stage${k + 1}",
            seed = config.seed, threads = config.threads, plan = plan,
            weightMode = config.planInput.weightMode, tokenizerPath = "${prefix}tokenizer.json",
            sources = sources, steps = steps, lrScale = stage.lrScale, overrides = config.overrides
        )
        val json = BranchyRunJson.toJsonString(root)
        val tmpJson = File(stageDir, "run.json.tmp")
        tmpJson.writeText(json)
        if (!tmpJson.renameTo(File(stageDir, "run.json"))) File(stageDir, "run.json").writeText(json)
        val updated = config.copy(runJson = json)
        repo.saveConfig(runId, updated)

        // 4. Train: resume from this stage's own checkpoint, else init from the previous stage's final one.
        val resume = latestCheckpoint(stageDir) != null
        val initFrom = if (!resume && k > 0) latestCheckpoint(stageDir(runDir, k - 1))?.absolutePath else null
        repo.setStatus(runId, BranchyRunStatus.TRAINING)
        setPhase(BranchyPhase.TRAIN, null)
        val tracker = TrainTracker()
        val stderr = File(stageDir, "stderr.log")
        val cluster = prepareCluster(config, plan, stageDir)
        tracker.clusterNames = cluster?.names.orEmpty()
        val argv = if (cluster == null) {
            // Single-device runs get a control file too, so "save checkpoint now" reaches the trainer.
            val control = File(stageDir, CONTROL_FILE_NAME)
            controlWriter = BranchyControlFile.Writer(control)
            BranchyCommandBuilder.train(exe, stageDir.absolutePath, resume, initFrom, config.threads, control.absolutePath)
        } else {
            BranchyCommandBuilder.trainCluster(
                exe, stageDir.absolutePath, cluster.jsonFile.absolutePath, cluster.tokenFile.absolutePath,
                resume, initFrom, config.threads
            )
        }
        watchJob?.cancel()
        watchJob = if (cluster != null) BranchyHelperWatch.watch(scope, helperProbe, selectedHelpers(config)) else null
        var res = try {
            runNative(argv, stageDir, stderr) { ev -> onTrainEvent(runId, ev, tracker) }
        } finally {
            watchJob?.cancel()
            watchJob = null
        }
        var finalConfig = updated
        // BP rebalance: the native side saved everything and exited with `paused{rebalance, cluster}`; relaunch at once.
        var restarts = 0
        while (cluster != null && restarts < MAX_REBALANCE_RESTARTS) {
            val path = BranchyRebalance.restartCluster(res.paused, stopMode != StopMode.NONE) { File(it).isFile }
                ?: break
            restarts++
            val placement = adoptRebalancedCluster(File(path), cluster.jsonFile)
            if (placement.isNotEmpty()) {
                finalConfig = finalConfig.copy(plan = finalConfig.plan?.let { BranchyRebalance.applyPlacement(it, placement) })
                repo.saveConfig(runId, finalConfig)
            }
            saveTrainProgress(runId, tracker)
            BranchyTrainingState.update { st ->
                st.copy(
                    phase = BranchyPhase.TRAIN, rebalancing = true, bpProposal = null,
                    devices = st.devices.map { d ->
                        d.copy(offline = false, branches = placement[d.id] ?: d.branches)
                    }
                )
            }
            notifyNow()
            controlWriter = BranchyControlFile.Writer(File(stageDir, CONTROL_FILE_NAME))
            val again = BranchyCommandBuilder.trainCluster(
                exe, stageDir.absolutePath, cluster.jsonFile.absolutePath, cluster.tokenFile.absolutePath,
                true, null, config.threads
            )
            watchJob = BranchyHelperWatch.watch(scope, helperProbe, selectedHelpers(config))
            res = try {
                runNative(again, stageDir, stderr) { ev -> onTrainEvent(runId, ev, tracker) }
            } finally {
                watchJob?.cancel()
                watchJob = null
            }
        }
        val done = res.done
        when {
            done != null && res.exit == 0 -> {
                tracker.lastStep = done.step
                saveTrainProgress(runId, tracker)
            }
            stopMode != StopMode.NONE || res.sawPaused -> {
                saveTrainProgress(runId, tracker)
                throw PausedException()
            }
            else -> throw IllegalStateException(failureText(res, stderr))
        }
        return finalConfig
    }

    /**
     * Copies the patched cluster file the native side wrote over the stage's `cluster.json` (so later resumes use the
     * new placement) and returns its branches per device id (empty when it could not be read).
     */
    private fun adoptRebalancedCluster(source: File, target: File): Map<String, List<Int>> {
        val text = runCatching { source.readText() }.getOrNull() ?: return emptyMap()
        runCatching { target.writeText(text) }
            .onFailure { DebugLog.log("[Branchy] Could not adopt ${source.name}: ${it.message}") }
        return BranchyRebalance.placementOf(text)
    }

    /**
     * Writes `cluster.json` (next to run.json) and the token file for a multi-device plan and seeds the live
     * device rows. Returns null for a single-device run, also when the plan kept every branch on this phone.
     * Planned helpers that are no longer in the device list fail the run with a readable message.
     */
    private suspend fun prepareCluster(config: BranchyRunConfig, plan: BranchyPlan, stageDir: File): ClusterLaunch? {
        val selection = config.cluster?.takeIf { !it.isEmpty }
        if (selection == null) return writeCluster(ClusterJsonResult.NotNeeded, emptyMap(), stageDir)
        val selected = BranchyClusterRepository(applicationContext).getDevices().filter { it.id in selection.deviceIds }
        // Each helper's preferred address (VPN first) falls back to its other address when only that one answers.
        val helpers = com.example.llamadroid.branchy.cluster.BranchyEndpointPreflight.preflight(applicationContext, selected)
        val control = File(stageDir, CONTROL_FILE_NAME)
        // The writer deletes a stale control.json; its sequence continues from control.seq of this stage dir.
        controlWriter = BranchyControlFile.Writer(control)
        val result = BranchyClusterRun.clusterJson(runName, plan, helpers, control.absolutePath,
            com.example.llamadroid.branchy.BranchyRunEdit.savedHelperThreads(config), config.planInput.devices)
        if (result is ClusterJsonResult.MissingEndpoints) {
            throw IllegalStateException(getString(R.string.branchy_main_error_missing_endpoints, result.ids.joinToString(", ")))
        }
        return writeCluster(result, helpers.associate { it.id to it.name }, stageDir)
    }

    private fun writeCluster(result: ClusterJsonResult, helperNames: Map<String, String>, stageDir: File): ClusterLaunch? {
        val ready = result as? ClusterJsonResult.Ready
        if (ready == null) {
            BranchyTrainingState.update {
                it.copy(clusterMode = null, devices = emptyList(), syncRound = 0L, syncBytes = 0L, syncWorkers = 0)
            }
            return null
        }
        val file = File(stageDir, "cluster.json")
        file.writeText(ready.json)
        val tokenFile = BranchyClusterToken.writeTokenFile(applicationContext)
        BranchyTrainingState.update {
            it.copy(
                clusterMode = ready.mode.value, syncRound = 0L, syncBytes = 0L, syncWorkers = 0, dpSetup = null,
                devices = ready.devices.map { d -> BranchyLiveDevice(d.id, helperNames[d.id] ?: d.id, d.branches) }
            )
        }
        return ClusterLaunch(file, tokenFile, helperNames)
    }

    private suspend fun selectedHelpers(config: BranchyRunConfig): List<BranchyClusterDeviceEntity> {
        val ids = config.cluster?.takeIf { !it.isEmpty }?.deviceIds ?: return emptyList()
        return runCatching { BranchyClusterRepository(applicationContext).getDevices().filter { it.id in ids } }
            .getOrDefault(emptyList())
    }

    /** "Continue without it" (data parallel): the coordinator polls control.json and drops [helperId] for the rest of the run. */
    private fun skipHelper(helperId: String) = sendControl(CMD_SKIP, helperId)

    /** Writes one command (see `CMD_*`) to the control file of the running stage; single-device runs get a control file too (`train --control-file`). */
    private fun sendControl(command: String, helperId: String?) {
        val writer = controlWriter ?: return
        val id = helperId?.takeIf { it.isNotBlank() }
        if (command == CMD_RETRY && id != null) BranchyTrainingState.update { BranchyTrainingState.requestRetry(it, id) }
        if (command == CMD_CHECKPOINT) {
            if (BranchyTrainingState.state.value.checkpointRequest?.pending == true) return
            BranchyTrainingState.update { BranchyTrainingState.requestCheckpoint(it) }
        }
        scope.launch {
            runCatching {
                when (command) {
                    CMD_SKIP -> id?.let { writer.skip(it) }
                    CMD_REST -> id?.let { writer.rest(it) }
                    CMD_UNREST -> id?.let { writer.unrest(it) }
                    CMD_RETRY -> id?.let { writer.retry(it) }
                    CMD_FORGET -> id?.let { writer.forget(it) }
                    CMD_CHECKPOINT -> writer.checkpointNow()
                    CMD_REBALANCE -> writer.rebalance()
                    else -> Unit
                }
            }.onFailure {
                DebugLog.log("[Branchy] Could not write $CONTROL_FILE_NAME: ${it.message}")
                if (command == CMD_CHECKPOINT) {
                    BranchyTrainingState.update { st ->
                        st.copy(checkpointRequest = BranchyCheckpointRequest(BranchyCkptStage.FAILED, System.currentTimeMillis(), reason = "write_failed"))
                    }
                    scheduleClearFinishedCheckpoint()
                }
            }
        }
    }

    /** Hides the "Checkpoint saved" / failure row of a manual request after [CKPT_RESULT_MS]. */
    private fun scheduleClearFinishedCheckpoint() {
        val req = BranchyTrainingState.state.value.checkpointRequest?.takeIf { !it.pending } ?: return
        scope.launch {
            delay(CKPT_RESULT_MS)
            BranchyTrainingState.update { BranchyTrainingState.clearFinishedCheckpoint(it, req.atMs) }
        }
    }

    private fun stageDir(runDir: File, stage: Int): File = if (stage == 0) runDir else File(runDir, "stage${stage + 1}")

    /** The checkpoint named by `checkpoints/latest`, or null. */
    private fun latestCheckpoint(stageDir: File): File? {
        val dir = File(stageDir, "checkpoints")
        val name = runCatching { File(dir, "latest").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return File(dir, name.substringAfterLast('/')).takeIf { it.isFile }
    }

    private fun cleanPrepared(dataDir: File, outName: String) {
        File(dataDir, "$outName.btok").delete()
        File(dataDir, "$outName.valid.btok").delete()
        File(dataDir, "$outName.done").delete()
    }

    private fun failureText(res: NativeResult, stderr: File): String {
        res.error?.takeIf { it.isNotBlank() }?.let { return it }
        val tail = runCatching { stderr.readLines().takeLast(4).joinToString("\n") }.getOrDefault("")
        val base = getString(R.string.branchy_error_native, res.exit)
        return if (tail.isBlank()) base else "$base\n$tail"
    }

    /** Starts [argv], streams its stdout events to [onEvent] and returns how it ended. Blocks on a worker thread. */
    private suspend fun runNative(
        argv: List<String>,
        workDir: File,
        stderrFile: File,
        onEvent: (BranchyEvent) -> Unit
    ): NativeResult = withContext(Dispatchers.IO) {
        checkNotStopped()
        stderrFile.parentFile?.mkdirs()
        DebugLog.log("[Branchy] ${argv.joinToString(" ")}")
        val proc = ProcessBuilder(argv).directory(workDir)
            .redirectError(ProcessBuilder.Redirect.appendTo(stderrFile)).start()
        process = proc
        // A pause that arrived between the check above and the assignment still has to reach the process.
        if (stopMode != StopMode.NONE) proc.destroy()
        var sawPaused = false
        var done: BranchyEvent.Done? = null
        var paused: BranchyEvent.Paused? = null
        var error: String? = null
        try {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    val ev = BranchyEventParser.parse(line) ?: return@forEachLine
                    when (ev) {
                        is BranchyEvent.Paused -> { sawPaused = true; paused = ev }
                        is BranchyEvent.Done -> done = ev
                        is BranchyEvent.Error -> error = ev.message
                        else -> Unit
                    }
                    onEvent(ev)
                }
            } catch (e: IOException) {
                // destroy() after a user Stop/Pause closes the pipe under the reader: expected, the run pauses.
                if (!BranchyStopPolicy.isExpectedReadFailure(e, stopMode != StopMode.NONE)) throw e
            }
            val exit = proc.waitFor()
            NativeResult(exit, error, sawPaused, done, paused)
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
            process = null
        }
    }

    // endregion

    // region events and state

    private fun setPhase(phase: BranchyPhase, label: String?) {
        BranchyTrainingState.update {
            it.copy(phase = phase, progressLabel = label, progressDone = 0L, progressTotal = 0L, message = null)
        }
        notifyNow()
    }

    private fun onProgress(ev: BranchyEvent.Progress, label: String?) {
        BranchyTrainingState.update {
            it.copy(progressDone = ev.done, progressTotal = ev.total, progressLabel = label ?: ev.phase)
        }
        notifyThrottled()
    }

    private fun onTrainEvent(runId: Long, ev: BranchyEvent, t: TrainTracker) {
        when (ev) {
            is BranchyEvent.Cluster, is BranchyEvent.WorkerLost, is BranchyEvent.WorkerJoined, is BranchyEvent.Rejoin,
            is BranchyEvent.Sync, is BranchyEvent.DpStart, is BranchyEvent.DpSetup,
            is BranchyEvent.ClusterWaiting, is BranchyEvent.ClusterResumed,
            is BranchyEvent.Micro, is BranchyEvent.DpRound, is BranchyEvent.DpAdvice, is BranchyEvent.BpAdvice,
            is BranchyEvent.Endpoint, is BranchyEvent.ControlAck, is BranchyEvent.CheckpointBegin,
            is BranchyEvent.Consolidate -> {
                if (ev is BranchyEvent.ControlAck) controlWriter?.onAck(ev.seq)
                BranchyTrainingState.update { BranchyTrainingState.applyClusterEvent(it, ev, t.clusterNames) }
                if (ev is BranchyEvent.ControlAck) scheduleClearFinishedCheckpoint()
                if (ev is BranchyEvent.Sync && t.lastLoss == null && ev.loss != null && ev.loss.isFinite()) t.lastLoss = ev.loss
                if (ev is BranchyEvent.DpSetup || ev is BranchyEvent.Micro) notifyThrottled()
                if (ev is BranchyEvent.WorkerLost || ev is BranchyEvent.WorkerJoined ||
                    ev is BranchyEvent.ClusterWaiting || ev is BranchyEvent.ClusterResumed
                ) notifyNow()
            }
            is BranchyEvent.Start -> {
                t.totalSteps = ev.steps
                t.tokensPerStep = ev.tokensPerStep
                t.lastStep = ev.resumedFrom ?: 0L
                BranchyTrainingState.update {
                    it.copy(
                        totalSteps = ev.steps, tokensPerStep = ev.tokensPerStep, params = ev.params,
                        progressDone = t.lastStep, progressTotal = ev.steps, micro = null, rebalancing = false,
                        checkpointRequest = null
                    )
                }
                saveTrainProgress(runId, t)
                notifyNow()
            }
            is BranchyEvent.Step -> {
                t.lastStep = ev.step
                if (ev.loss.isFinite()) t.lastLoss = ev.loss
                BranchyTrainingState.update {
                    BranchyTrainingState.applyClusterEvent(it, ev, t.clusterNames).copy(
                        lastStep = ev,
                        lossPoints = if (ev.loss.isFinite()) {
                            BranchyTrainingState.appendPoint(it.lossPoints, ev.step.toInt() to ev.loss.toFloat())
                        } else it.lossPoints,
                        progressDone = ev.step, progressTotal = t.totalSteps
                    )
                }
                val now = System.currentTimeMillis()
                if (now - t.lastDbAt >= DB_THROTTLE_MS) {
                    t.lastDbAt = now
                    saveTrainProgress(runId, t)
                }
                notifyThrottled()
            }
            is BranchyEvent.Eval -> {
                if (ev.valLoss.isFinite()) t.lastVal = ev.valLoss
                BranchyTrainingState.update {
                    it.copy(
                        valPoints = if (ev.valLoss.isFinite()) {
                            BranchyTrainingState.appendPoint(it.valPoints, ev.step.toInt() to ev.valLoss.toFloat())
                        } else it.valPoints
                    )
                }
                saveTrainProgress(runId, t)
            }
            is BranchyEvent.Sample -> BranchyTrainingState.update {
                it.copy(samples = BranchyTrainingState.appendSample(it.samples, ev.text))
            }
            is BranchyEvent.Checkpoint -> {
                t.lastStep = ev.step
                BranchyTrainingState.update { BranchyTrainingState.applyClusterEvent(it, ev, t.clusterNames) }
                saveTrainProgress(runId, t)
                scheduleClearFinishedCheckpoint()
            }
            is BranchyEvent.Paused -> {
                t.lastStep = ev.step
                BranchyTrainingState.update { BranchyTrainingState.applyClusterEvent(it, ev, t.clusterNames) }
                saveTrainProgress(runId, t)
            }
            is BranchyEvent.Done -> BranchyTrainingState.update { BranchyTrainingState.applyClusterEvent(it, ev, t.clusterNames) }
            is BranchyEvent.Error -> BranchyTrainingState.update {
                BranchyTrainingState.applyClusterEvent(it, ev, t.clusterNames).copy(message = ev.message)
            }
            else -> Unit
        }
    }

    private fun saveTrainProgress(runId: Long, t: TrainTracker) {
        runBlocking {
            repo.saveProgress(runId, t.lastStep, t.totalSteps, t.lastLoss, t.lastVal, t.lastStep * t.tokensPerStep)
        }
    }

    // endregion

    // region guards

    private fun startGuards(runId: Long) {
        guardJob?.cancel()
        guardJob = scope.launch {
            while (isActive) {
                val cfg = guardConfig
                val snap = readBattery()
                val busy = runJob?.isActive == true
                if (cfg != null && snap != null && !(busy && stopMode != StopMode.NONE)) {
                    when (val action = BranchyGuards.decide(cfg.guards, snap, busy, pausedByGuard, cfg.autoResume)) {
                        is GuardAction.Pause -> {
                            pausedByGuard = true
                            requestStop(StopMode.PAUSE, action.reason)
                        }
                        GuardAction.Resume -> {
                            pausedByGuard = false
                            startRun(runId)
                        }
                        GuardAction.None -> Unit
                    }
                }
                delay(GUARD_POLL_MS)
            }
        }
    }

    private fun readBattery(): BatterySnapshot? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return BatterySnapshot(
            levelPct = level * 100 / scale,
            pluggedIn = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            tempC = if (temp == Int.MIN_VALUE) 0.0 else temp / 10.0
        )
    }

    // endregion

    // region notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.branchy_notification_channel_name), NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun ensureForeground(): Boolean {
        if (foregroundStarted) return true
        val result = ForegroundStart.start(this, NOTIFICATION_ID, buildNotification(), ForegroundServiceTypes.DATA_SYNC)
        if (result is ForegroundStart.Result.Failed) {
            val message = ForegroundStart.failureMessage(this, result, getString(R.string.branchy_notification_title_generic))
            BranchyTrainingState.update { it.copy(phase = BranchyPhase.ERROR, message = message) }
            stopSelf()
            return false
        }
        foregroundStarted = true
        return true
    }

    private fun notifyThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < NOTIFY_THROTTLE_MS) return
        notifyNow()
    }

    private fun notifyNow() {
        lastNotifyAt = System.currentTimeMillis()
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // Notification permission not granted; the run itself is unaffected.
        }
    }

    private fun pendingAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, BranchyTrainingService::class.java).apply {
            this.action = action
            putExtra(EXTRA_RUN_ID, activeRunId ?: BranchyTrainingState.state.value.runId ?: 0L)
        }
        return PendingIntent.getForegroundService(
            this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun reasonText(reason: BranchyPauseReason?): String = getString(
        when (reason) {
            BranchyPauseReason.BATTERY_LOW -> R.string.branchy_pause_reason_battery_low
            BranchyPauseReason.UNPLUGGED -> R.string.branchy_pause_reason_unplugged
            BranchyPauseReason.BATTERY_HOT -> R.string.branchy_pause_reason_hot
            BranchyPauseReason.RESTORED -> R.string.branchy_pause_reason_restored
            else -> R.string.branchy_pause_reason_user
        }
    )

    private fun buildNotification(): Notification {
        val s = BranchyTrainingState.state.value
        val stageLabel = if (s.stageCount > 1) "${s.stage + 1}/${s.stageCount}" else ""
        var progress = -1f
        val text = when (s.phase) {
            BranchyPhase.TOKENIZER -> getString(R.string.branchy_notification_tokenizer)
            BranchyPhase.PREPARE -> {
                if (s.progressTotal > 0L) progress = s.progressDone.toFloat() / s.progressTotal
                getString(
                    R.string.branchy_notification_prepare, s.progressLabel ?: "",
                    (progress.coerceAtLeast(0f) * 100).toInt()
                )
            }
            BranchyPhase.TRAIN -> {
                val step = s.lastStep
                if (s.rebalancing) getString(R.string.branchy_r6_rebalancing)
                else if (step != null && s.totalSteps > 0L) {
                    progress = step.step.toFloat() / s.totalSteps
                    getString(
                        R.string.branchy_notification_training,
                        stageLabel.ifEmpty { "1/1" }, step.step.toString(), s.totalSteps.toString(),
                        BranchyFormat.loss(step.loss), BranchyFormat.duration(step.etaS)
                    )
                } else getString(R.string.branchy_notification_training_start)
            }
            BranchyPhase.WAITING_NETWORK -> getString(
                if (s.waiting?.phase == "join") R.string.branchy_r5_notification_waiting_join
                else R.string.branchy_r4_notification_waiting
            )
            BranchyPhase.PAUSING -> getString(R.string.branchy_notification_pausing)
            BranchyPhase.PAUSED -> getString(R.string.branchy_notification_paused, reasonText(s.pauseReason))
            BranchyPhase.DONE -> getString(R.string.branchy_notification_done)
            BranchyPhase.ERROR -> getString(R.string.branchy_notification_failed, s.message ?: "")
            BranchyPhase.IDLE -> getString(R.string.branchy_notification_tokenizer)
        }
        val active = s.isActive || s.phase == BranchyPhase.IDLE
        val title = if (runName.isBlank()) getString(R.string.branchy_notification_title_generic)
        else getString(R.string.branchy_notification_title, runName)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .setAutoCancel(s.phase == BranchyPhase.DONE || s.phase == BranchyPhase.ERROR)
        if (active) {
            if (progress < 0f) builder.setProgress(0, 0, true)
            else builder.setProgress(100, (progress.coerceIn(0f, 1f) * 100).toInt(), false)
        }
        when (s.phase) {
            BranchyPhase.PAUSED -> {
                builder.addAction(
                    android.R.drawable.ic_media_play, getString(R.string.branchy_action_resume),
                    pendingAction(ACTION_RESUME, 2)
                )
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.branchy_action_stop),
                    pendingAction(ACTION_STOP, 3)
                )
            }
            BranchyPhase.DONE, BranchyPhase.ERROR -> Unit
            else -> {
                builder.addAction(
                    android.R.drawable.ic_media_pause, getString(R.string.branchy_action_pause),
                    pendingAction(ACTION_PAUSE, 1)
                )
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.branchy_action_stop),
                    pendingAction(ACTION_STOP, 3)
                )
            }
        }
        return builder.build()
    }

    // endregion

    private fun acquireWake() {
        if (wakeHeld) return
        wakeHeld = true
        WakeLockManager.acquire(applicationContext, WAKE_TAG)
    }

    private fun releaseWake() {
        if (!wakeHeld) return
        wakeHeld = false
        WakeLockManager.release(WAKE_TAG)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ForegroundTimeoutSupport.handle(this, fgsType)
    }

    override fun onDestroy() {
        guardJob?.cancel()
        watchJob?.cancel()
        val runId = activeRunId
        val wasRunning = runJob?.isActive == true && !finishing
        // SIGTERM lets the native trainer write a checkpoint even though this service is going away.
        process?.destroy()
        scope.cancel()
        releaseWake()
        if (wasRunning && runId != null) {
            BranchyTrainingState.update {
                BranchyTrainingState.endRun(it).copy(phase = BranchyPhase.PAUSED, pauseReason = BranchyPauseReason.USER)
            }
            val repository = repo
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch(NonCancellable) {
                repository.setStatus(runId, BranchyRunStatus.PAUSED)
            }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.llamadroid.branchy.START"
        const val ACTION_PAUSE = "com.example.llamadroid.branchy.PAUSE"
        const val ACTION_RESUME = "com.example.llamadroid.branchy.RESUME"
        const val ACTION_STOP = "com.example.llamadroid.branchy.STOP"
        const val ACTION_SKIP_HELPER = "com.example.llamadroid.branchy.SKIP_HELPER"
        const val ACTION_CONTROL = "com.example.llamadroid.branchy.CONTROL"
        const val EXTRA_COMMAND = "command"
        const val CMD_SKIP = "skip"
        const val CMD_REST = "rest"
        const val CMD_UNREST = "unrest"
        const val CMD_RETRY = "retry"
        const val CMD_FORGET = "forget"
        const val CMD_CHECKPOINT = "checkpoint"
        private const val CKPT_RESULT_MS = 8_000L
        const val CMD_REBALANCE = "rebalance"
        private const val MAX_REBALANCE_RESTARTS = 8
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_HELPER_ID = "helper_id"
        const val CONTROL_FILE_NAME = "control.json"

        private const val CHANNEL_ID = "branchy_training"
        private const val NOTIFICATION_ID = 7421
        private const val WAKE_TAG = "BranchyTrainingService"
        private const val GUARD_POLL_MS = 30_000L
        private const val PAUSE_GRACE_MS = 120_000L
        private const val DB_THROTTLE_MS = 2_000L
        private const val NOTIFY_THROTTLE_MS = 1_000L
        private const val TOKENIZER_MAX_BYTES = 256L * 1024 * 1024

        private fun send(context: Context, action: String, runId: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BranchyTrainingService::class.java).apply {
                    this.action = action
                    putExtra(EXTRA_RUN_ID, runId)
                }
            )
        }

        fun start(ctx: Context, runId: Long) = send(ctx, ACTION_START, runId)
        fun pause(ctx: Context, runId: Long) = send(ctx, ACTION_PAUSE, runId)
        fun resume(ctx: Context, runId: Long) = send(ctx, ACTION_RESUME, runId)
        fun stop(ctx: Context, runId: Long) = send(ctx, ACTION_STOP, runId)

        private fun control(ctx: Context, runId: Long, command: String, helperId: String?) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, BranchyTrainingService::class.java).apply {
                    action = ACTION_CONTROL
                    putExtra(EXTRA_RUN_ID, runId)
                    putExtra(EXTRA_COMMAND, command)
                    if (helperId != null) putExtra(EXTRA_HELPER_ID, helperId)
                }
            )
        }

        /** Data parallel: rest a helper (keeps it connected without work). */
        fun rest(ctx: Context, runId: Long, helperId: String) = control(ctx, runId, CMD_REST, helperId)
        fun unrest(ctx: Context, runId: Long, helperId: String) = control(ctx, runId, CMD_UNREST, helperId)

        /** Asks the native side to retry connecting to a lost helper now. */
        fun retryHelper(ctx: Context, runId: Long, helperId: String) = control(ctx, runId, CMD_RETRY, helperId)

        /** Drops a lost helper from the run for good (DP only; BP ignores it). */
        fun forgetHelper(ctx: Context, runId: Long, helperId: String) = control(ctx, runId, CMD_FORGET, helperId)

        /** Writes a checkpoint at the next step/round boundary. */
        fun checkpointNow(ctx: Context, runId: Long) = control(ctx, runId, CMD_CHECKPOINT, null)

        /** Branch parallel: applies the suggested placement at the next step boundary (the app restarts the stage itself). */
        fun rebalance(ctx: Context, runId: Long) = control(ctx, runId, CMD_REBALANCE, null)

        /** Data-parallel "Continue without it": drops [helperId] from the running cluster (no effect otherwise). */
        fun skipHelper(ctx: Context, runId: Long, helperId: String) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, BranchyTrainingService::class.java).apply {
                    action = ACTION_SKIP_HELPER
                    putExtra(EXTRA_RUN_ID, runId)
                    putExtra(EXTRA_HELPER_ID, helperId)
                }
            )
        }
    }
}
