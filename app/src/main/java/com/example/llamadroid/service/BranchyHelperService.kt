package com.example.llamadroid.service

import android.app.ActivityManager
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
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyNative
import com.example.llamadroid.branchy.cluster.BranchyAdvert
import com.example.llamadroid.branchy.cluster.BranchyBatteryGuard
import com.example.llamadroid.branchy.cluster.BranchyClusterToken
import com.example.llamadroid.branchy.cluster.BranchyHelperSettings
import com.example.llamadroid.branchy.cluster.BranchyWorkerEvent
import com.example.llamadroid.branchy.cluster.BranchyWorkerEventParser
import com.example.llamadroid.branchy.cluster.BranchyHelperState
import com.example.llamadroid.branchy.cluster.BranchyInferenceHelperRunner
import com.example.llamadroid.branchy.cluster.BranchyProcessSignals
import com.example.llamadroid.branchy.cluster.BranchySliceNames
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.DeviceAddresses
import com.example.llamadroid.util.HelperHeartbeat
import com.example.llamadroid.util.HelperHeartbeatServer
import com.example.llamadroid.util.HelperSpecs
import com.example.llamadroid.util.NsdHelper
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that lends this phone to a Branchy training cluster: runs `branchy worker`, advertises it
 * over NSD (`_adt-branchy._tcp.`), serves the heartbeat on port + 1 and shows a notification with Stop.
 * With the inference role it also runs `llama-branchy-helper` on port + 3 for the latest `*.slice.gguf` the worker
 * receives (or one the user picked), and a battery guard suspends both processes when the battery is low.
 * The cluster token reaches the native process only through a 0600 file.
 */
class BranchyHelperService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var runJob: Job? = null
    private var foregroundStarted = false
    private var heartbeat: HelperHeartbeatServer? = null
    private var nsd: NsdHelper? = null
    private var tokenFile: File? = null
    private var guardJob: Job? = null
    private val runner = BranchyInferenceHelperRunner(scope) { code ->
        log(getString(R.string.branchy_cluster_log_infer_stopped, code ?: -1))
    }

    @Volatile private var pendingSlice: File? = null

    @Volatile private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.branchy_cluster_notification_channel), NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start (also from the notification action on a dead service) must call startForeground first.
        if (!ensureForeground()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_START -> startWorker()
            ACTION_STOP -> {
                if (runJob?.isActive == true) {
                    // Also cancels a benchmark that is still running before the worker process exists.
                    // Cancel first: the reader's IOException after destroy() must be read as a stop, not a failure.
                    runJob?.cancel()
                    process?.let { BranchyProcessSignals.signal(it, suspend = false) }
                    process?.destroy()
                } else {
                    shutdown()
                }
            }
            ACTION_USE_SLICE -> useSlice(intent.getStringExtra(EXTRA_SLICE_PATH))
            else -> if (runJob?.isActive != true) shutdown()
        }
        return START_NOT_STICKY
    }

    /** A slice picked by the user was copied into the worker's files folder; start serving it. */
    private fun useSlice(path: String?) {
        val filesDir = BranchySliceNames.filesDir(this)
        val file = path?.let(::File)?.takeIf { it.isFile && it.parentFile?.canonicalPath == filesDir.canonicalPath }
        if (file == null) {
            if (runJob?.isActive != true) shutdown()
            return
        }
        pendingSlice = file
        when {
            runJob?.isActive != true -> startWorker()
            tokenFile != null -> scope.launch { startInference(file) }
            // Otherwise the worker is still starting and picks the pending slice up itself.
        }
    }

    private fun startWorker() {
        if (runJob?.isActive == true) return
        val port = BranchyHelperSettings.port(this)
        val threads = BranchyHelperSettings.threads(this)
        val name = BranchyHelperSettings.deviceName(this)
        runJob = scope.launch {
            try {
                runWorker(port, threads, name)
            } catch (_: CancellationException) {
                // Stop requested before the worker started.
            } catch (e: Exception) {
                // A stop (job cancelled) destroys the process under the stdout reader; that IOException is expected.
                if (isActive) log(getString(R.string.branchy_cluster_log_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                cleanup()
                shutdown()
            }
        }
    }

    private suspend fun runWorker(port: Int, threads: Int, name: String) {
        if (BranchyClusterToken.get(this) == null) {
            log(getString(R.string.branchy_cluster_log_no_token))
            return
        }
        val exe = BinaryRepository(this).getTieredBinary("branchy_trainer")
        if (exe == null) {
            log(getString(R.string.branchy_cluster_log_no_binary))
            return
        }
        var gflops = BranchyHelperSettings.cachedGflops(this, threads)
        if (gflops == null) {
            log(getString(R.string.branchy_cluster_log_benchmarking))
            gflops = runCatching { BranchyNative(applicationContext).bench(threads) }.getOrNull()
            if (gflops != null) {
                BranchyHelperSettings.cacheGflops(this, threads, gflops)
                log(getString(R.string.branchy_cluster_log_benchmark_done, gflops))
            }
        }
        currentCoroutineContext().ensureActive()

        val root = File(filesDir, "branchy/worker").apply { mkdirs() }
        val token = BranchyClusterToken.writeTokenFile(this)
        tokenFile = token
        val portFile = File(cacheDir, "branchy_worker.port").apply { delete() }
        val argv = listOf(
            exe.absolutePath, "worker",
            "--port", port.toString(), "--bind", "0.0.0.0",
            "--root", root.absolutePath,
            "--token-file", token.absolutePath,
            "--name", name,
            "--port-file", portFile.absolutePath,
            "--threads", threads.toString()
        )
        log(getString(R.string.branchy_cluster_log_starting, port, threads))
        DebugLog.log("[BranchyHelper] ${exe.name} worker --port $port --threads $threads")
        WakeLockManager.acquire(applicationContext, TAG)
        WakeLockManager.acquireWifiLock(applicationContext, TAG)

        val proc = ProcessBuilder(argv).redirectErrorStream(true).directory(filesDir).start()
        process = proc
        BranchyHelperState.setRunning(true, port)
        updateNotification()

        val hb = HelperHeartbeatServer(
            HelperHeartbeat.heartbeatPort(port), BranchyAdvert.HEARTBEAT_BANNER, "0.0.0.0",
            "branchy-helper-heartbeat", "BranchyHelperHeartbeat"
        ) { heartbeatSpecs(name, threads, gflops).toJsonLine() }
        if (hb.start()) heartbeat = hb else log(getString(R.string.branchy_cluster_log_heartbeat_busy, HelperHeartbeat.heartbeatPort(port)))
        registerNsd(port, name, gflops)
        startBatteryGuard()
        (pendingSlice ?: BranchySliceNames.latest(this))?.let { startInference(it) }

        val filesDir = BranchySliceNames.filesDir(this)
        try {
            proc.inputStream.bufferedReader().forEachLine { line ->
                val text = line.trim()
                if (text.isNotEmpty()) log(text.take(MAX_LINE_CHARS))
                handleWorkerEvent(text)
                BranchySliceNames.receivedSlicePath(text, filesDir)?.let { slice ->
                    log(getString(R.string.branchy_cluster_log_slice_received, slice.name))
                    startInference(slice)
                }
            }
            log(getString(R.string.branchy_cluster_log_stopped, proc.waitFor()))
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }

    /** Fresh specs for each heartbeat probe: RAM changes while a run is going. */
    private fun heartbeatSpecs(name: String, threads: Int, gflops: Double?): HelperSpecs {
        val memory = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val liveThreads = BranchyHelperState.workerThreads.value ?: threads
        return HelperSpecs(
            name = name,
            node = BranchyAdvert.SERVICE_NAME_PREFIX + "-" + name.filter { it.isLetterOrDigit() },
            ramFreeMb = (memory.availMem / (1024 * 1024)).toInt(),
            ramTotalMb = (memory.totalMem / (1024 * 1024)).toInt(),
            cores = Runtime.getRuntime().availableProcessors(),
            threads = liveThreads,
            gflops = BranchyHelperSettings.cachedGflops(this, liveThreads) ?: gflops,
            version = BuildConfig.VERSION_NAME
        )
    }

    private fun registerNsd(port: Int, name: String, gflops: Double?) {
        val addresses = DeviceAddresses.current(applicationContext)
        val memory = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val role = BranchyHelperSettings.role(this)
        val helper = NsdHelper(applicationContext, BranchyAdvert.SERVICE_TYPE, BranchyAdvert.SERVICE_NAME_PREFIX)
        helper.registerService(
            port,
            name,
            BranchyAdvert.buildTxt(
                role = role,
                name = name,
                ramMb = (memory.availMem / (1024 * 1024)).toInt(),
                gflops = gflops,
                appVersion = BuildConfig.VERSION_NAME,
                port = port,
                vpnIp = DeviceAddresses.vpnIp(addresses),
                inferPort = BranchyHelperSettings.inferPort(this)
            )
        )
        nsd = helper
    }

    /** Starts `llama-branchy-helper` on [slice], replacing a running one. Needs the inference role and a token file. */
    private fun startInference(slice: File) {
        pendingSlice = null
        BranchyHelperState.setSlice(BranchySliceNames.info(slice))
        if (!BranchyHelperSettings.role(this).canInfer) {
            log(getString(R.string.branchy_cluster_log_infer_disabled))
            return
        }
        val exe = BinaryRepository(this).getTieredBinary("llama-branchy-helper")
        if (exe == null) {
            log(getString(R.string.branchy_cluster_log_no_helper_binary))
            return
        }
        val token = tokenFile ?: return
        val port = BranchyHelperSettings.inferPort(this)
        log(getString(R.string.branchy_cluster_log_infer_starting, slice.name, port))
        runner.start(exe, slice, port, token, BranchyHelperSettings.threads(this), BranchyHelperSettings.ctx(this))
        if (BranchyHelperState.lowBatteryPaused.value) runner.signal(suspend = true)
    }

    private fun startBatteryGuard() {
        guardJob?.cancel()
        guardJob = scope.launch {
            var paused = false
            while (isActive) {
                val (percent, pluggedIn) = readBattery()
                val min = BranchyHelperSettings.minBatteryPercent(this@BranchyHelperService)
                val want = BranchyBatteryGuard.shouldPause(
                    BranchyHelperSettings.batteryGuard(this@BranchyHelperService), percent, pluggedIn, min, paused
                )
                if (want != paused) {
                    paused = want
                    process?.let { BranchyProcessSignals.signal(it, suspend = paused) }
                    runner.signal(suspend = paused)
                    BranchyHelperState.setLowBatteryPaused(paused)
                    log(
                        if (paused) getString(R.string.branchy_cluster_log_battery_paused, percent)
                        else getString(R.string.branchy_cluster_log_battery_resumed, percent)
                    )
                }
                delay(GUARD_INTERVAL_MS)
            }
        }
    }

    /** Battery percent (-1 when unknown) and whether a charger is connected. */
    private fun readBattery(): Pair<Int, Boolean> {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1 to false
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return percent to (status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }

    /** Maps worker state events to the helper UI state; the thread count is persisted for the next start. */
    private fun handleWorkerEvent(line: String) {
        when (val event = BranchyWorkerEventParser.parse(line) ?: return) {
            BranchyWorkerEvent.WaitingMaster, BranchyWorkerEvent.SessionEnded, BranchyWorkerEvent.Session ->
                BranchyHelperState.setWaitingMaster(
                    BranchyWorkerEventParser.waitingAfter(BranchyHelperState.waitingMaster.value, event)
                )
            is BranchyWorkerEvent.Progress -> BranchyHelperState.setTraining(event.progress)
            is BranchyWorkerEvent.Threads -> {
                BranchyHelperState.setWorkerThreads(event.threads)
                BranchyHelperSettings.save(
                    this, BranchyHelperSettings.deviceName(this), BranchyHelperSettings.port(this), event.threads
                )
            }
        }
    }

    private fun cleanup() {
        guardJob?.cancel()
        guardJob = null
        runner.stop()
        process?.let { BranchyProcessSignals.signal(it, suspend = false) }
        process?.let { if (it.isAlive) it.destroyForcibly() }
        process = null
        heartbeat?.close()
        heartbeat = null
        nsd?.unregisterService()
        nsd = null
        tokenFile?.delete()
        tokenFile = null
        BranchyHelperState.setRunning(false, null)
        WakeLockManager.release(TAG)
        WakeLockManager.releaseWifiLock(TAG)
    }

    private fun shutdown() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun log(message: String) {
        BranchyHelperState.appendLog(message)
        DebugLog.log("[BranchyHelper] $message")
    }

    private fun ensureForeground(): Boolean {
        if (foregroundStarted) return true
        val result = ForegroundStart.start(this, NOTIFICATION_ID, buildNotification(), ForegroundServiceTypes.DATA_SYNC)
        if (result is ForegroundStart.Result.Failed) {
            log(ForegroundStart.failureMessage(this, result, getString(R.string.branchy_cluster_notification_title)))
            stopSelf()
            return false
        }
        foregroundStarted = true
        return true
    }

    private fun updateNotification() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // Notification permission not granted; the helper itself is unaffected.
        }
    }

    private fun buildNotification(): Notification {
        val port = BranchyHelperState.port.value
        val text = if (port != null) getString(R.string.branchy_cluster_notification_running, port)
        else getString(R.string.branchy_cluster_notification_starting)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getForegroundService(
            this, 1, Intent(this, BranchyHelperService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(getString(R.string.branchy_cluster_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.branchy_cluster_action_stop), stop)
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ForegroundTimeoutSupport.handle(this, fgsType)
    }

    override fun onDestroy() {
        process?.let { BranchyProcessSignals.signal(it, suspend = false) }
        process?.destroy()
        scope.cancel()
        cleanup()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.llamadroid.branchy.HELPER_START"
        const val ACTION_STOP = "com.example.llamadroid.branchy.HELPER_STOP"
        const val ACTION_USE_SLICE = "com.example.llamadroid.branchy.HELPER_USE_SLICE"
        const val EXTRA_SLICE_PATH = "slice_path"

        private const val TAG = "BranchyHelperService"
        private const val CHANNEL_ID = "branchy_helper"
        private const val NOTIFICATION_ID = 7422
        private const val MAX_LINE_CHARS = 300
        private const val GUARD_INTERVAL_MS = 15_000L

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)

        /** Starts serving [slicePath] (a file inside the worker's files folder), starting the worker if needed. */
        fun useSlice(context: Context, slicePath: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BranchyHelperService::class.java).apply {
                    action = ACTION_USE_SLICE
                    putExtra(EXTRA_SLICE_PATH, slicePath)
                }
            )
        }

        private fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BranchyHelperService::class.java).apply { this.action = action }
            )
        }
    }
}
