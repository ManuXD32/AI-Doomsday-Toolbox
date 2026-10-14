package com.example.llamadroid.harness

import android.content.Context
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Metadata about one failed harness model request. Never contains prompt, response, tool
 * output or file text: only codes, counts, timings and a bounded llama-server error body.
 */
@Serializable
data class HarnessModelFailure(
    val timestampMs: Long,
    /** Stable bridge code, for example `LLAMA_SERVER_REJECTED` or `MODEL_SERVER_UNSTABLE`. */
    val code: String,
    val exceptionClass: String,
    /** Sanitized llama-server error body, at most 200 characters. */
    val body: String? = null,
    /** Wire id of the model, for example `llama:12`; null when unknown. */
    val model: String = "",
    val cardId: Long? = null,
    /** True when the managed server process was alive after the failure; null when not probed. */
    val serverAlive: Boolean? = null,
    /** True when `/health` answered after the failure; null when not probed. */
    val serverHealthy: Boolean? = null,
    /** True when the managed server pid/start ticks changed or its state is ERROR/STOPPED. */
    val serverRestarted: Boolean = false,
    /** Managed session state name after the failure (for example `ERROR`), when known. */
    val serverStatus: String? = null,
    val promptTokens: Int? = null,
    val promptTokensProcessed: Int? = null,
    /** Streamed text/tool deltas received before the failure (about one per generated token). */
    val generatedTokens: Int = 0,
    val elapsedMs: Long = 0,
    val freeRamBytes: Long? = null,
    val lowMemory: Boolean = false,
    val contextTokens: Int? = null,
)

/** What went wrong in user terms; drives both the localized UI line and the English model text. */
enum class HarnessModelFailureKind {
    SERVER_UNSTABLE, CONTEXT_TOO_LARGE, SERVER_DIED, SERVER_REJECTED, CONNECTION_LOST,
    ADAPTER_STREAM, LITERT_CRASH, OTHER
}

/** Pure explanation helpers; no Android access so they can be unit tested. */
object HarnessModelFailureExplain {
    /** RAM below this at failure time is reported as likely out-of-memory. */
    const val LOW_RAM_BYTES = 1_000L * 1024 * 1024

    fun kind(record: HarnessModelFailure): HarnessModelFailureKind {
        val serverGone = record.serverRestarted || record.serverAlive == false
        return when {
            record.code == HarnessProviderErrorCodes.MODEL_SERVER_UNSTABLE -> HarnessModelFailureKind.SERVER_UNSTABLE
            record.code == HarnessProviderErrorCodes.CONTEXT_WINDOW_EXCEEDED ||
                record.code == "LITERT_PROMPT_OVER_LIMIT" -> HarnessModelFailureKind.CONTEXT_TOO_LARGE
            record.code == "LITERT_WORKER_CRASHED" -> HarnessModelFailureKind.LITERT_CRASH
            serverGone && record.code != HarnessProviderErrorCodes.LLAMA_SERVER_REJECTED ->
                HarnessModelFailureKind.SERVER_DIED
            record.code == HarnessProviderErrorCodes.LLAMA_SERVER_REJECTED -> HarnessModelFailureKind.SERVER_REJECTED
            record.code == HarnessProviderErrorCodes.LLAMA_SERVER_UNREACHABLE ||
                record.code == HarnessProviderErrorCodes.LLAMA_SERVER_CONNECTION_LOST ||
                record.code == HarnessProviderErrorCodes.PROVIDER_INTERRUPTED && record.serverHealthy == false ->
                HarnessModelFailureKind.CONNECTION_LOST
            record.code == HarnessProviderErrorCodes.ADAPTER_STREAM_ERROR -> HarnessModelFailureKind.ADAPTER_STREAM
            else -> HarnessModelFailureKind.OTHER
        }
    }

    /** True when a death of the model server looks like memory pressure. */
    fun likelyOutOfMemory(record: HarnessModelFailure): Boolean {
        val free = record.freeRamBytes
        return record.lowMemory || (free != null && free < LOW_RAM_BYTES)
    }

    fun formatGb(bytes: Long?): String =
        if (bytes == null) "?" else String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))

    /** English one-line cause for model-facing text (subagent results); the UI uses string resources. */
    fun english(record: HarnessModelFailure): String = when (kind(record)) {
        HarnessModelFailureKind.SERVER_UNSTABLE ->
            "The model server stopped repeatedly during harness requests, so new requests are paused for a while. " +
                "Use a smaller context or model, or close other apps, then try again."
        HarnessModelFailureKind.CONTEXT_TOO_LARGE -> "The prompt is larger than the model's context."
        HarnessModelFailureKind.SERVER_DIED ->
            "The model server stopped during generation" +
                (if (likelyOutOfMemory(record)) " - likely out of memory (free RAM ${formatGb(record.freeRamBytes)})" else "") + "."
        HarnessModelFailureKind.SERVER_REJECTED ->
            "llama-server rejected the request" + (record.body?.let { ": $it" } ?: "") + "."
        HarnessModelFailureKind.CONNECTION_LOST -> "The connection to the model server was lost."
        HarnessModelFailureKind.ADAPTER_STREAM -> "The app could not read the model's response stream."
        HarnessModelFailureKind.LITERT_CRASH -> "The on-device LiteRT model worker crashed."
        HarnessModelFailureKind.OTHER -> "The model request failed (${record.code})."
    }

    /** Bounded multi-line text for the clipboard. Metadata only. */
    fun diagnosticsText(records: List<HarnessModelFailure>): String = buildString {
        append("DeepSeek Harness model failures (metadata only, newest first)\n")
        records.sortedByDescending { it.timestampMs }.forEach { r ->
            append("- ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(r.timestampMs))).append(" code=").append(r.code)
                .append(" class=").append(r.exceptionClass)
                .append(" model=").append(r.model.ifBlank { "?" })
                .append(" elapsedMs=").append(r.elapsedMs)
                .append(" promptTokens=").append(r.promptTokens ?: "?")
                .append(" promptProcessed=").append(r.promptTokensProcessed ?: "?")
                .append(" generated=").append(r.generatedTokens)
                .append(" ctx=").append(r.contextTokens ?: "?")
                .append(" serverAlive=").append(r.serverAlive ?: "?")
                .append(" healthy=").append(r.serverHealthy ?: "?")
                .append(" restarted=").append(r.serverRestarted)
                .append(" serverStatus=").append(r.serverStatus ?: "?")
                .append(" freeRam=").append(formatGb(r.freeRamBytes))
                .append(" lowMemory=").append(r.lowMemory)
            r.body?.let { append(" body=\"").append(it).append('"') }
            append('\n').append("  -> ").append(english(r)).append('\n')
        }
    }
}

/**
 * Last [MAX_RECORDS] model failures of one harness runtime: in-memory with a [StateFlow] and
 * file-backed under the runtime's `agent_harness` data directory. Metadata only.
 */
class HarnessModelFailureLog private constructor(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow<List<HarnessModelFailure>>(emptyList())
    private var loaded = false

    /** Newest first. Starts loading from disk lazily on first access. */
    val records: StateFlow<List<HarnessModelFailure>> get() { load(); return readOnly }
    private val readOnly: StateFlow<List<HarnessModelFailure>> = mutable.asStateFlow()

    /** Optional sink the runtime wires to its journal; invoked after every record. */
    @Volatile var journalSink: ((HarnessModelFailure) -> Unit)? = null

    fun latest(): HarnessModelFailure? { load(); return mutable.value.firstOrNull() }

    @Synchronized
    fun record(failure: HarnessModelFailure) {
        loadLocked()
        mutable.value = (listOf(failure) + mutable.value).take(MAX_RECORDS)
        persistLocked()
        runCatching { journalSink?.invoke(failure) }
    }

    @Synchronized
    fun clear() {
        loadLocked()
        mutable.value = emptyList()
        persistLocked()
    }

    fun diagnosticsText(): String = HarnessModelFailureExplain.diagnosticsText(run { load(); mutable.value })

    @Synchronized private fun load() = loadLocked()

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val stored = file?.takeIf { it.isFile }?.let { target ->
            runCatching { json.decodeFromString<List<HarnessModelFailure>>(target.readText()) }.getOrNull()
        }.orEmpty()
        mutable.value = stored.sortedByDescending { it.timestampMs }.take(MAX_RECORDS)
    }

    private fun persistLocked() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(json.encodeToString(mutable.value))
            if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
        }
    }

    companion object {
        const val MAX_RECORDS = 20
        private val instances = ConcurrentHashMap<String, HarnessModelFailureLog>()

        /** The log of [runtimeId]; shared by the model adapter, the viewer and the subagent tools. */
        fun get(context: Context, runtimeId: String): HarnessModelFailureLog =
            instances.computeIfAbsent(runtimeId) {
                val file = runCatching {
                    HarnessRuntimeScope.dataFile(
                        HarnessRuntimeScope.context(context.applicationContext, runtimeId),
                        "agent_harness/model-failures.json",
                    )
                }.getOrNull()
                HarnessModelFailureLog(file)
            }

        /** In-memory only log for tests. */
        internal fun inMemory(): HarnessModelFailureLog = HarnessModelFailureLog(null)
    }
}

/**
 * Stops a tight restart loop when a managed llama-server keeps dying during harness requests:
 * two deaths of one card within [WINDOW_MS] block further requests until
 * [RESET_MS] after the latest death. A successful request clears the card's history.
 */
class HarnessServerStabilityRule(private val clock: () -> Long = System::currentTimeMillis) {
    private val deaths = mutableMapOf<Long, MutableList<Long>>()

    @Synchronized fun recordDeath(cardId: Long) {
        val list = deaths.getOrPut(cardId) { mutableListOf() }
        list.add(clock())
        while (list.size > MAX_TRACKED) list.removeAt(0)
    }

    @Synchronized fun recordSuccess(cardId: Long) { deaths.remove(cardId) }

    /**
     * True when the next request for [cardId] must fail fast: two deaths fell within
     * [WINDOW_MS] of each other and the latest one is less than [RESET_MS] old.
     */
    @Synchronized fun isUnstable(cardId: Long): Boolean {
        val list = deaths[cardId] ?: return false
        if (clock() - list.last() > RESET_MS) { deaths.remove(cardId); return false }
        return list.zipWithNext().any { (earlier, later) -> later - earlier <= WINDOW_MS }
    }

    companion object {
        const val MAX_TRACKED = 10
        const val WINDOW_MS = 5 * 60_000L
        const val RESET_MS = 10 * 60_000L
    }
}

/** Process-wide stability rule used by every [HarnessLocalModels] instance. */
object HarnessModelServerStability {
    val rule = HarnessServerStabilityRule()
}
