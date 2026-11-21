package com.example.llamadroid.service.search

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.llamadroid.service.AgentLocalWorkspaceSupport
import com.example.llamadroid.service.AgentProotCommandExecutor
import com.example.llamadroid.service.AgentProotCommandRequest
import com.example.llamadroid.service.AgentProotCommandStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Experimental in-app SearXNG (Debian PRoot, loopback only). Installed on demand, started when a search needs it,
 * stopped after [SearxngLocalConfig.IDLE_STOP_MS] without use. Not verified on arm64 PRoot yet (see docs).
 */
internal object SearxngLocalInstance {
    private const val TAG = "SearxngLocal"
    private const val PREFS = "searxng_local"
    private const val KEY_SECRET = "secret"
    private const val ASSET_SCRIPT = "searxng_local/searxng_local.sh"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opLock = Mutex()
    private val _state = MutableStateFlow(SearxngLocalState.NOT_INSTALLED)
    private val _error = MutableStateFlow<String?>(null)
    val state: StateFlow<SearxngLocalState> = _state.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var lastUsedMs = 0L
    @Volatile private var serveRunId: String? = null
    private var monitor: Job? = null
    private val executor by lazy { AgentProotCommandExecutor(requireNotNull(appContext)) }
    private val http = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()

    /** Wires the [WebSearchBackends.localInstanceBaseUrl] hook; safe to call repeatedly (app start and settings). */
    fun register(context: Context) {
        appContext = context.applicationContext
        syncFromDisk()
        WebSearchBackends.localInstanceBaseUrl = ::baseUrlForSearch
    }

    private fun home(): File = File(
        AgentLocalWorkspaceSupport.rootForProject(requireNotNull(appContext), SearxngLocalConfig.PROJECT_FOLDER),
        "searxng"
    )
    private fun marker() = File(home(), ".installed")
    private fun port() = SearxngLocalConfig.DEFAULT_PORT

    private fun dispatch(event: SearxngLocalEvent) {
        _state.value = SearxngLocalStateMachine.next(_state.value, event)
    }

    private fun syncFromDisk() {
        dispatch(if (marker().isFile) SearxngLocalEvent.MARKER_PRESENT else SearxngLocalEvent.MARKER_MISSING)
    }

    /** Hook for the search backend: touches the idle timer; starts a stopped instance in the background. */
    private fun baseUrlForSearch(): String? {
        if (appContext == null) return null
        lastUsedMs = System.currentTimeMillis()
        return when (_state.value) {
            SearxngLocalState.RUNNING -> SearxngLocalConfig.baseUrl(port())
            SearxngLocalState.STOPPED -> { start(); null } // first search after idle falls back to DuckDuckGo
            else -> null
        }
    }

    private fun secret(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SECRET, null)?.takeIf { it.length == 64 }?.let { return it }
        return SearxngLocalConfig.newSecret().also { prefs.edit { putString(KEY_SECRET, it) } }
    }

    fun install() {
        val ctx = appContext ?: return
        scope.launch {
            opLock.withLock {
                if (_state.value != SearxngLocalState.NOT_INSTALLED && _state.value != SearxngLocalState.FAILED) return@withLock
                _error.value = null
                dispatch(SearxngLocalEvent.INSTALL_REQUESTED)
                try {
                    val dir = home().apply { mkdirs() }
                    ctx.assets.open(ASSET_SCRIPT).use { input ->
                        File(dir, "searxng_local.sh").outputStream().use { input.copyTo(it) }
                    }
                    File(dir, "logs/install.log").takeIf { it.exists() }?.delete()
                    val run = executor.execute(request("install", secret(ctx), SearxngLocalConfig.INSTALL_TIMEOUT_MS))
                    var result = run
                    while (result.status == AgentProotCommandStatus.STARTING || result.status == AgentProotCommandStatus.RUNNING) {
                        delay(2_000)
                        result = executor.status(run.runId) ?: break
                    }
                    if (result.status == AgentProotCommandStatus.SUCCEEDED && marker().isFile) {
                        dispatch(SearxngLocalEvent.INSTALL_OK)
                    } else {
                        _error.value = result.error ?: "exit ${result.exitCode ?: "?"}"
                        dispatch(SearxngLocalEvent.INSTALL_FAILED)
                    }
                } catch (e: CancellationException) {
                    dispatch(SearxngLocalEvent.INSTALL_FAILED)
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "install failed", e)
                    _error.value = e.message?.take(300) ?: e::class.java.simpleName
                    dispatch(SearxngLocalEvent.INSTALL_FAILED)
                }
            }
        }
    }

    private fun request(action: String, secret: String?, timeoutMs: Long) = AgentProotCommandRequest(
        runId = "searxng-$action-${UUID.randomUUID().toString().take(8)}",
        environmentId = SearxngLocalConfig.ENVIRONMENT_ID,
        projectFolder = SearxngLocalConfig.PROJECT_FOLDER,
        command = SearxngLocalConfig.command(action, port(), secret),
        timeoutMs = timeoutMs,
        background = action == "serve"
    )

    fun start() {
        val ctx = appContext ?: return
        scope.launch {
            opLock.withLock {
                if (_state.value != SearxngLocalState.STOPPED) return@withLock
                _error.value = null
                lastUsedMs = System.currentTimeMillis()
                dispatch(SearxngLocalEvent.START_REQUESTED)
                val run = try {
                    executor.execute(request("serve", secret(ctx), SearxngLocalConfig.SERVE_TIMEOUT_MS))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _error.value = e.message?.take(300) ?: e::class.java.simpleName
                    dispatch(SearxngLocalEvent.START_FAILED)
                    return@withLock
                }
                serveRunId = run.runId
                val deadline = System.currentTimeMillis() + SearxngLocalConfig.START_TIMEOUT_MS
                var healthy = false
                while (System.currentTimeMillis() < deadline && !isTerminal(run.runId)) {
                    if (isHealthy()) { healthy = true; break }
                    delay(1_000)
                }
                if (healthy) {
                    dispatch(SearxngLocalEvent.HEALTHY)
                    startMonitor(run.runId)
                } else {
                    _error.value = executor.status(run.runId)?.error ?: "SearXNG did not answer on 127.0.0.1:${port()}"
                    executor.cancel(run.runId)
                    serveRunId = null
                    dispatch(SearxngLocalEvent.START_FAILED)
                }
            }
        }
    }

    private fun isTerminal(runId: String): Boolean {
        val s = executor.status(runId)?.status ?: return true
        return s != AgentProotCommandStatus.STARTING && s != AgentProotCommandStatus.RUNNING
    }

    private fun isHealthy(): Boolean = try {
        http.newCall(Request.Builder().url(SearxngLocalConfig.healthUrl(port())).build()).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    private fun startMonitor(runId: String) {
        monitor?.cancel()
        monitor = scope.launch {
            while (isActive) {
                delay(15_000)
                if (isTerminal(runId)) {
                    serveRunId = null
                    dispatch(SearxngLocalEvent.PROCESS_EXITED)
                    return@launch
                }
                if (SearxngLocalConfig.isIdle(System.currentTimeMillis(), lastUsedMs)) {
                    stopNow()
                    return@launch
                }
            }
        }
    }

    private suspend fun stopNow() {
        monitor?.cancel()
        serveRunId?.let { executor.cancel(it) }
        serveRunId = null
        dispatch(SearxngLocalEvent.STOP_REQUESTED)
    }

    fun stop() {
        if (appContext == null) return
        scope.launch { stopNow() }
    }

    /** Removes the install (src, venv, settings, logs). The private rootfs copy is left for other uses. */
    fun uninstall() {
        if (appContext == null) return
        scope.launch {
            stopNow()
            opLock.withLock {
                if (_state.value == SearxngLocalState.INSTALLING) return@withLock
                withContext(Dispatchers.IO) { home().deleteRecursively() }
                _error.value = null
                dispatch(SearxngLocalEvent.UNINSTALLED)
            }
        }
    }

    /** Tail of the install or serve log, whichever was written last; empty when there is none. */
    /** Appends one diagnostic line (never the query text) to serve.log so the log viewer shows each Test. */
    fun appendLogLine(line: String) {
        if (appContext == null) return
        runCatching {
            val logs = File(home(), "logs").also { it.mkdirs() }
            File(logs, "serve.log").appendText(line.take(500) + "\n")
        }
    }

    fun readLog(): String {
        if (appContext == null) return ""
        val logs = File(home(), "logs")
        val file = listOf("install.log", "serve.log").map { File(logs, it) }
            .filter { it.isFile }.maxByOrNull { it.lastModified() } ?: return ""
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val take = minOf(len, SearxngLocalConfig.LOG_TAIL_BYTES.toLong())
                raf.seek(len - take)
                val bytes = ByteArray(take.toInt())
                raf.readFully(bytes)
                SearxngLocalConfig.tail(String(bytes, Charsets.UTF_8))
            }
        }.getOrDefault("")
    }
}
