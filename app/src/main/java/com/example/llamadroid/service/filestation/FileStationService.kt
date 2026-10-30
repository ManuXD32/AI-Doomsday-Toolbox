package com.example.llamadroid.service.filestation

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.R
import com.example.llamadroid.service.AiServerAuth
import com.example.llamadroid.service.AiServerNetwork
import com.example.llamadroid.service.ForegroundStart
import com.example.llamadroid.service.ForegroundServiceTypes
import com.example.llamadroid.service.ForegroundTimeoutSupport
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.service.filestation.api.ArchiveApi
import com.example.llamadroid.service.filestation.api.ToolsApi
import com.example.llamadroid.service.filestation.http.AuthConfigProvider
import com.example.llamadroid.service.filestation.http.FileStationDeps
import com.example.llamadroid.service.filestation.http.FileStationHttpServer
import com.example.llamadroid.service.filestation.http.FsLogger
import com.example.llamadroid.service.filestation.http.JobState
import com.example.llamadroid.service.filestation.http.PasswordCredentials
import com.example.llamadroid.service.filestation.http.PasswordVerifier
import com.example.llamadroid.service.filestation.jobs.JobManager
import com.example.llamadroid.service.filestation.tools.ToolBackendsFactory
import com.example.llamadroid.service.filestation.volume.TrashStore
import com.example.llamadroid.service.filestation.volume.VolumeRegistry
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service hosting the File Station web server (specialUse FGS). Started and stopped via
 * [FileStationRuntime]; state is published through its StateFlows.
 */
class FileStationService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycleLock = Any()

    private var server: FileStationHttpServer? = null
    private var jobs: JobManager? = null
    private var registry: VolumeRegistry? = null
    private var trash: TrashStore? = null
    private var lanVisible = true
    private var taskId: Int? = null
    private var locksHeld = false
    private var startJob: Job? = null
    private var watcherJob: Job? = null
    private var settingsJob: Job? = null
    private var lastSecret: String? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FileStationRuntime.ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ensureForeground()) return START_NOT_STICKY
        if (server == null && startJob?.isActive != true) {
            startJob = scope.launch { startServer() }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ForegroundTimeoutSupport.handle(this, fgsType)
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureForeground(): Boolean {
        if (foregroundStarted) return true
        val title = getString(R.string.fs_notification_title)
        val (id, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.FILE_STATION, title,
        )
        val result = ForegroundStart.start(
            this,
            id,
            notification,
            ForegroundServiceTypes.SPECIAL_USE
        )
        if (result is ForegroundStart.Result.Failed) {
            val message = ForegroundStart.failureMessage(this, result.reason, title)
            FileStationRuntime.setLastError(message)
            FileStationRuntime.reset()
            UnifiedNotificationManager.failTask(id, message)
            stopSelf()
            return false
        }
        taskId = id
        foregroundStarted = true
        return true
    }

    private suspend fun startServer() {
        val store = FileStationSettingsStore(this)
        val settings = store.settings.value
        FileStationRuntime.setLastError(null)
        val built = FileStationVolumes.build(this, settings.volumes)
        if (built.volumes.isEmpty()) {
            fail(
                if (settings.volumes.isEmpty()) getString(R.string.fs_error_no_root)
                else getString(R.string.fs_error_volume_lost, built.lostLabels.joinToString()),
            )
            return
        }
        val host = AiServerNetwork.bindHost(settings.lanVisible)
        if (!AiServerNetwork.canBind(host, settings.port)) {
            fail(getString(R.string.fs_error_port_in_use, settings.port))
            return
        }
        val reg = VolumeRegistry(built.volumes, settings.trashRetentionDays, settings.showHidden)
        val trashStore = TrashStore(reg)
        val jobManager = JobManager(File(cacheDir, "file_station_jobs"))
        val deps = FileStationDeps(
            volumes = reg,
            auth = object : AuthConfigProvider {
                override fun credentials(): PasswordCredentials? = store.credentials()
                override fun sessionSecret(): ByteArray = store.sessionSecret().toByteArray(Charsets.UTF_8)
                override val lanVisible: Boolean = settings.lanVisible
            },
            assets = AndroidAssetSource(assets),
            jobs = jobManager,
            tools = ToolBackendsFactory.create(this),
            logger = FsLogger { level, tag, message -> DebugLog.log("[$tag/$level] $message") },
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            cacheDir = cacheDir,
        )
        val http = FileStationHttpServer(
            hostname = host,
            port = settings.port,
            deps = deps,
            verifier = PasswordVerifier { password, c -> AiServerAuth.verifyPassword(password, c.salt, c.hash) },
            extraRoutes = listOf(ArchiveApi(deps), ToolsApi(deps)),
            registry = reg,
            trash = trashStore,
        )
        try {
            http.startServer()
        } catch (e: Exception) {
            jobManager.shutdown()
            fail(getString(R.string.fs_notification_error, e.message ?: e.javaClass.simpleName))
            return
        }
        synchronized(lifecycleLock) {
            server = http; jobs = jobManager; registry = reg; trash = trashStore
            lanVisible = settings.lanVisible
            lastSecret = store.sessionSecret()
        }
        acquireLocks()
        val urls = AiServerNetwork.urlsFor(settings.port, settings.lanVisible)
        FileStationRuntime.setUrls(urls)
        FileStationRuntime.setUnprotected(settings.unprotected)
        FileStationRuntime.setRunning(true)
        if (built.lostLabels.isNotEmpty()) {
            FileStationRuntime.setLastError(getString(R.string.fs_error_volume_lost, built.lostLabels.joinToString()))
        }
        updateNotification(urls.firstOrNull()?.second, settings.unprotected)
        watcherJob = scope.launch { watch(jobManager, trashStore) }
        settingsJob = scope.launch {
            FileStationRuntime.settingsChanged.collect { applySettings() }
        }
    }

    /** Swaps volumes, trash retention, hidden flag and session state live; port and LAN need a restart. */
    private suspend fun applySettings() {
        val store = FileStationSettingsStore(this)
        val settings = store.settings.value
        val reg = registry ?: return
        val built = FileStationVolumes.build(this, settings.volumes)
        reg.setVolumes(built.volumes)
        reg.trashRetentionDays = settings.trashRetentionDays
        reg.showHidden = settings.showHidden
        val secret = store.sessionSecret()
        if (secret != lastSecret) {
            lastSecret = secret
            server?.gate?.clearSessions()
        }
        FileStationRuntime.setLastError(
            if (built.lostLabels.isEmpty()) null
            else getString(R.string.fs_error_volume_lost, built.lostLabels.joinToString()),
        )
        val unprotected = lanVisible && !settings.hasPassword
        FileStationRuntime.setUnprotected(unprotected)
        updateNotification(FileStationRuntime.urls.value.firstOrNull()?.second, unprotected)
    }

    /** Publishes the active job count and purges the trash on start and every six hours. */
    private suspend fun watch(jobManager: JobManager, trashStore: TrashStore) {
        var nextPurge = 0L
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            if (now >= nextPurge) {
                runCatching { trashStore.purgeAll() }
                nextPurge = now + TRASH_PURGE_INTERVAL_MS
            }
            val active = jobManager.list().count { it.state == JobState.QUEUED || it.state == JobState.RUNNING }
            FileStationRuntime.setActiveJobs(active)
            delay(1_000)
        }
    }

    private suspend fun updateNotification(url: String?, unprotected: Boolean) {
        val id = taskId ?: return
        val text = when {
            url == null -> getString(R.string.fs_notification_title)
            unprotected -> getString(R.string.fs_notification_unprotected, url)
            else -> getString(R.string.fs_notification_running, url)
        }
        withContext(Dispatchers.Main) { UnifiedNotificationManager.updateProgress(id, 1f, text) }
    }

    private fun acquireLocks() {
        if (locksHeld) return
        locksHeld = true
        WakeLockManager.acquire(this, LOCK_TAG)
        WakeLockManager.acquireWifiLock(this, LOCK_TAG)
    }

    private fun fail(message: String) {
        FileStationRuntime.setLastError(message)
        FileStationRuntime.reset()
        shutdown()
        stopSelf()
    }

    /** Idempotent teardown shared by stop, failure and destroy. */
    private fun shutdown() {
        val http: FileStationHttpServer?
        val jobManager: JobManager?
        synchronized(lifecycleLock) {
            http = server; jobManager = jobs
            server = null; jobs = null; registry = null; trash = null
        }
        watcherJob?.cancel(); watcherJob = null
        settingsJob?.cancel(); settingsJob = null
        startJob?.cancel(); startJob = null
        runCatching { http?.stop() }
        runCatching { jobManager?.shutdown() }
        if (locksHeld) {
            locksHeld = false
            WakeLockManager.release(LOCK_TAG)
            WakeLockManager.releaseWifiLock(LOCK_TAG)
        }
        taskId?.let { UnifiedNotificationManager.dismissTask(it) }
        taskId = null
        if (foregroundStarted) {
            foregroundStarted = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
        FileStationRuntime.reset()
    }

    private companion object {
        const val LOCK_TAG = "FileStationService"
        const val TRASH_PURGE_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
