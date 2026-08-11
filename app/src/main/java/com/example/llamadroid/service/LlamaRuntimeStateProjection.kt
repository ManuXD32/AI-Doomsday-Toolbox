package com.example.llamadroid.service

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-way, metadata-only projection from the dedicated llama runtime process to
 * the main process. Keeping the projection separate preserves legacy static
 * StateFlow consumers without allowing main-process updates to rebroadcast.
 */
internal object LlamaRuntimeStateProjection {
    private const val ACTION_EVENT = "com.example.llamadroid.action.LLAMA_RUNTIME_EVENT"
    private const val PREFS = "llama_runtime_projection"
    private const val KEY_GENERATION = "generation"
    private const val KEY_KIND = "kind"
    private const val KEY_VALUE = "value"
    private const val KEY_STATUS = "status"
    private const val KEY_LOGS = "logs"
    private const val EXTRA_GENERATION = "generation"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_VALUE = "value"
    private const val EXTRA_STATUS = "status"
    private const val MAX_LOG_CHARS = 512
    private const val MAX_LOG_BATCH_CHARS = 4_096
    private const val MAX_LOGS = 128

    private const val KIND_STATE = "state"
    private const val KIND_LOG = "log"
    private const val KIND_LOG_BATCH = "log_batch"
    private const val KIND_CLEAR_LOGS = "clear_logs"
    private const val KIND_HEARTBEAT = "heartbeat"
    private const val KIND_STARTUP_FAILURE = "startup_failure"
    private const val KIND_CLEAR_STARTUP_FAILURE = "clear_startup_failure"
    private const val HEARTBEAT_TIMEOUT_MS = 35_000L
    private const val WATCHDOG_INTERVAL_MS = 10_000L

    @Volatile private var registered = false
    @Volatile private var lastHeartbeatAtMs = 0L
    private val reducer = LlamaRuntimeProjectionReducer()
    private val pendingLogs = ConcurrentLinkedQueue<Pair<Long, String>>()
    private val logFlushScheduled = AtomicBoolean(false)
    // Keep Android's main looper lookup out of object initialization so JVM unit tests can use
    // the pure reducer/decoding helpers without requiring a mocked Android Looper.
    private val logHandler by lazy { Handler(Looper.getMainLooper()) }
    private val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "llama-log-persistence").apply { isDaemon = true }
    }
    private val logFlushRunnable = Runnable { flushPendingLogs() }

    fun beginRuntimeGeneration(context: Context): Long {
        val generation = System.currentTimeMillis()
        persistState(context, generation, "starting", "")
        publish(context, generation, KIND_STATE, "starting", "")
        return generation
    }

    fun publishState(context: Context, generation: Long, state: ServerState) {
        val (value, status) = when (state) {
            ServerState.Stopped -> "stopped" to ""
            ServerState.Starting -> "starting" to ""
            is ServerState.Loading -> "loading:${state.progress}" to state.status
            is ServerState.Running -> "running:${state.port}" to ""
            is ServerState.Error -> "error" to state.message.take(MAX_LOG_CHARS)
        }
        persistState(context, generation, value, status)
        publish(context, generation, KIND_STATE, value, status)
    }

    fun publishLog(context: Context, generation: Long, message: String) {
        runtimeContextForProjection = context.applicationContext
        pendingLogs.add(generation to sanitize(message))
        scheduleLogFlush()
    }

    /** A service-side bounded buffer has already applied rate limiting and coalescing. */
    fun publishLogBatch(context: Context, generation: Long, messages: List<String>) {
        if (messages.isEmpty()) return
        runtimeContextForProjection = context.applicationContext
        publish(
            context,
            generation,
            KIND_LOG_BATCH,
            messages.joinToString("\n") { sanitize(it) },
            ""
        )
    }

    fun publishClearLogs(context: Context, generation: Long) {
        pendingLogs.clear()
        logHandler.removeCallbacks(logFlushRunnable)
        logFlushScheduled.set(false)
        publish(context, generation, KIND_CLEAR_LOGS, "", "")
    }

    fun publishHeartbeat(context: Context, generation: Long) {
        publish(context, generation, KIND_HEARTBEAT, "", "")
    }

    fun publishStartupFailure(context: Context, generation: Long, timestampMs: Long?) {
        publish(context, generation, if (timestampMs == null) KIND_CLEAR_STARTUP_FAILURE else KIND_STARTUP_FAILURE, timestampMs?.toString().orEmpty(), "")
    }

    fun registerMainProcess(context: Context, state: MutableStateFlow<ServerState>, logs: MutableStateFlow<List<com.example.llamadroid.util.LogEntry>>) {
        if (!isMainProcess(context) || registered) return
        restore(context, state, logs)
        lastHeartbeatAtMs = System.currentTimeMillis()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != ACTION_EVENT) return
                applyEvent(
                    context = receiverContext,
                    generation = intent.getLongExtra(EXTRA_GENERATION, 0L),
                    kind = intent.getStringExtra(EXTRA_KIND).orEmpty(),
                    value = intent.getStringExtra(EXTRA_VALUE).orEmpty(),
                    status = intent.getStringExtra(EXTRA_STATUS).orEmpty(),
                    state = state,
                    logs = logs
                )
            }
        }
        val filter = IntentFilter(ACTION_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        registered = true
        scheduleWatchdog(context.applicationContext, state)
    }

    internal fun applyEvent(
        context: Context,
        generation: Long,
        kind: String,
        value: String,
        status: String,
        state: MutableStateFlow<ServerState>,
        logs: MutableStateFlow<List<com.example.llamadroid.util.LogEntry>>
    ) {
        if (!reducer.accept(generation)) return
        when (kind) {
            KIND_STATE -> state.value = decodeState(value, status)
            KIND_LOG, KIND_LOG_BATCH -> {
                val entries = value.lineSequence()
                    .filter { it.isNotBlank() }
                    .map { com.example.llamadroid.util.LogEntry(System.currentTimeMillis(), sanitize(it)) }
                    .toList()
                logs.value = (logs.value + entries).takeLast(MAX_LOGS)
            }
            KIND_CLEAR_LOGS -> logs.value = emptyList()
            KIND_HEARTBEAT -> lastHeartbeatAtMs = System.currentTimeMillis()
            KIND_STARTUP_FAILURE -> LlamaService.applyProjectedStartupFailure(value.toLongOrNull())
            KIND_CLEAR_STARTUP_FAILURE -> LlamaService.applyProjectedStartupFailure(null)
            else -> return
        }
        if (kind == KIND_LOG || kind == KIND_LOG_BATCH || kind == KIND_CLEAR_LOGS) persistLogs(context, logs.value)
    }

    private fun scheduleLogFlush() {
        if (logFlushScheduled.compareAndSet(false, true)) {
            logHandler.postDelayed(logFlushRunnable, 100L)
        }
    }

    private fun flushPendingLogs() {
        logFlushScheduled.set(false)
        val first = pendingLogs.poll() ?: return
        val batch = StringBuilder(first.second)
        val generation = first.first
        var count = 1
        while (count < 32) {
            val next = pendingLogs.peek() ?: break
            if (next.first != generation || batch.length + next.second.length + 1 > MAX_LOG_BATCH_CHARS) break
            pendingLogs.poll()
            batch.append('\n').append(next.second)
            count++
        }
        val context = runtimeContextForProjection ?: return
        publish(context, generation, KIND_LOG_BATCH, batch.toString(), "")
        if (pendingLogs.isNotEmpty()) scheduleLogFlush()
    }

    @Volatile
    private var runtimeContextForProjection: Context? = null

    private fun publish(context: Context, generation: Long, kind: String, value: String, status: String) {
        context.sendBroadcast(Intent(ACTION_EVENT).apply {
            `package` = context.packageName
            putExtra(EXTRA_GENERATION, generation)
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_VALUE, value.take(if (kind == KIND_LOG_BATCH) MAX_LOG_BATCH_CHARS else MAX_LOG_CHARS))
            putExtra(EXTRA_STATUS, status.take(MAX_LOG_CHARS))
        })
    }

    internal fun restore(context: Context, state: MutableStateFlow<ServerState>, logs: MutableStateFlow<List<com.example.llamadroid.util.LogEntry>>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        reducer.restore(prefs.getLong(KEY_GENERATION, 0L))
        if (reducer.acceptedGeneration <= 0L) return
        if (prefs.getString(KEY_KIND, "") == KIND_STATE) {
            state.value = decodeState(prefs.getString(KEY_VALUE, "").orEmpty(), prefs.getString(KEY_STATUS, "").orEmpty())
        }
        logs.value = prefs.getString(KEY_LOGS, "").orEmpty().lineSequence().filter { it.isNotBlank() }
            .map { com.example.llamadroid.util.LogEntry(System.currentTimeMillis(), it) }.toList().takeLast(MAX_LOGS)
    }

    private fun persistLogs(context: Context, logs: List<com.example.llamadroid.util.LogEntry>) {
        val value = logs.takeLast(MAX_LOGS).joinToString("\n") { sanitize(it.message) }
        persistenceExecutor.execute {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LOGS, value).apply()
        }
    }

    private fun persistState(context: Context, generation: Long, value: String, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_GENERATION, generation).putString(KEY_KIND, KIND_STATE)
            .putString(KEY_VALUE, value.take(MAX_LOG_CHARS)).putString(KEY_STATUS, status.take(MAX_LOG_CHARS)).commit()
    }

    internal fun decodeState(value: String, status: String): ServerState = when {
        value == "stopped" -> ServerState.Stopped
        value == "starting" -> ServerState.Starting
        value.startsWith("loading:") -> ServerState.Loading(value.substringAfter(':').toFloatOrNull() ?: -1f, status)
        value.startsWith("running:") -> ServerState.Running(value.substringAfter(':').toIntOrNull() ?: 0)
        value == "error" -> ServerState.Error(status.ifBlank { "Runtime interrupted" })
        else -> ServerState.Error("Runtime state interrupted")
    }

    internal fun sanitize(value: String): String = value.replace('\n', ' ').replace(Regex("/[^ ]+"), "<path>").take(MAX_LOG_CHARS)

    internal fun runtimeHeartbeatTimedOut(state: ServerState, nowMs: Long, lastHeartbeatMs: Long): Boolean {
        val runtimeActive = state is ServerState.Starting || state is ServerState.Loading || state is ServerState.Running
        return runtimeActive && nowMs - lastHeartbeatMs > HEARTBEAT_TIMEOUT_MS
    }

    private fun scheduleWatchdog(context: Context, state: MutableStateFlow<ServerState>) {
        val handler = Handler(Looper.getMainLooper())
        val watchdog = object : Runnable {
            override fun run() {
                val current = state.value
                if (runtimeHeartbeatTimedOut(current, System.currentTimeMillis(), lastHeartbeatAtMs)) {
                    state.value = ServerState.Error(context.getString(com.example.llamadroid.R.string.llama_runtime_interrupted))
                    GenerationDiagnosticsStore.recordBreadcrumb(
                        source = "llama_runtime_projection",
                        event = "runtime_heartbeat_timeout",
                        details = "generation=${reducer.acceptedGeneration} previous=${current.javaClass.simpleName}"
                    )
                }
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun isMainProcess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        return runCatching { Application.getProcessName() == context.applicationInfo.processName }.getOrDefault(true)
    }

    internal fun resetForTests(context: Context) {
        pendingLogs.clear()
        logHandler.removeCallbacks(logFlushRunnable)
        logFlushScheduled.set(false)
        reducer.reset()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}

internal class LlamaRuntimeProjectionReducer {
    var acceptedGeneration: Long = 0L
        private set

    fun accept(generation: Long): Boolean {
        if (generation <= 0L || generation < acceptedGeneration) return false
        acceptedGeneration = generation
        return true
    }

    fun restore(generation: Long) {
        acceptedGeneration = generation.coerceAtLeast(0L)
    }

    fun reset() {
        acceptedGeneration = 0L
    }
}
