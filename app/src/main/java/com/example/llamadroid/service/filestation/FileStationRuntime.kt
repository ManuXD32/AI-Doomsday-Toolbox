package com.example.llamadroid.service.filestation

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide File Station state. The foreground service updates it; the UI observes it and sends
 * start/stop intents. The service is addressed by class name so this file has no compile-time
 * dependency on it.
 */
object FileStationRuntime {
    const val SERVICE_CLASS = "com.example.llamadroid.service.filestation.FileStationService"
    const val ACTION_STOP = "com.example.llamadroid.filestation.STOP"

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** (interface label, url) pairs, empty when stopped. */
    private val _urls = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val urls: StateFlow<List<Pair<String, String>>> = _urls.asStateFlow()

    private val _activeJobs = MutableStateFlow(0)
    val activeJobs: StateFlow<Int> = _activeJobs.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** True while serving on the LAN without a password. */
    private val _unprotected = MutableStateFlow(false)
    val unprotected: StateFlow<Boolean> = _unprotected.asStateFlow()

    private val _settingsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when volumes, password, trash or hidden settings changed; the service re-reads the store. */
    val settingsChanged: SharedFlow<Unit> = _settingsChanged.asSharedFlow()

    fun setRunning(value: Boolean) { _running.value = value }
    fun setUrls(value: List<Pair<String, String>>) { _urls.value = value }
    fun setActiveJobs(value: Int) { _activeJobs.value = value }
    fun setLastError(value: String?) { _lastError.value = value }
    fun setUnprotected(value: Boolean) { _unprotected.value = value }
    fun notifySettingsChanged() { _settingsChanged.tryEmit(Unit) }

    /** Resets everything a stopped service should not leave behind. */
    fun reset() {
        _running.value = false; _urls.value = emptyList(); _activeJobs.value = 0; _unprotected.value = false
    }

    fun startIntent(context: Context): Intent = Intent().setClassName(context, SERVICE_CLASS)

    fun stopIntent(context: Context): Intent = startIntent(context).setAction(ACTION_STOP)

    fun start(context: Context) {
        context.startForegroundService(startIntent(context))
    }

    fun stop(context: Context) {
        runCatching { context.startService(stopIntent(context)) }
    }
}
