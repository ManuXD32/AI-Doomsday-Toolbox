package com.example.llamadroid.harness

import com.example.llamadroid.harness.runtime.HarnessRuntimeLogEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/** A runtime exists before its first conversation. Keep its metadata independently. */
@Serializable
data class HarnessRuntimeDiagnostic(
    val id: Long,
    val timestampMs: Long,
    val event: String,
    val phase: String? = null,
    val generation: String? = null,
    val state: String? = null,
    val durationMs: Long? = null,
    val errorCode: String? = null,
    val exitCode: Int? = null,
    val transport: String? = null,
    val operation: String? = null,
    val outcome: String? = null,
    val httpStatus: Int? = null,
    val closeCode: Int? = null,
    val attempt: Int? = null,
    val activeStreams: Int? = null,
    val coalescedCount: Int? = null,
    val webBoot: Map<String, Int> = emptyMap(),
)

/** Rotating app-private metadata journal; frequent reads cannot evict lifecycle evidence. */
class HarnessRuntimeJournal(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableEntries = MutableStateFlow<List<HarnessRuntimeDiagnostic>>(emptyList())
    val entries: StateFlow<List<HarnessRuntimeDiagnostic>> = mutableEntries.asStateFlow()
    private val directory = File(file.parentFile, file.name + ".segments")
    private val segmentCounts = mutableMapOf<String, Int>()
    private val successes = mutableMapOf<String, Pair<Long, Int>>()
    private var loaded = false
    private var nextId = 1L

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        val saved = mutableListOf<HarnessRuntimeDiagnostic>()
        var unreadable = false
        segmentFiles().forEach { segment ->
            try {
                require(segment.length() <= SEGMENT_BYTES)
                segment.useLines { lines -> lines.take(SEGMENT_ENTRIES).forEach { line ->
                    try { sanitize(json.decodeFromString<HarnessRuntimeDiagnostic>(line))?.let(saved::add) }
                    catch (_: Exception) { unreadable = true }
                } }
            } catch (_: Exception) { unreadable = true }
        }
        // Migrate the previous 200-row JSON once, without trusting old unsanitized fields.
        if (saved.isEmpty() && file.exists()) {
            try {
                require(file.length() <= 128 * 1024)
                saved += json.decodeFromString<List<HarnessRuntimeDiagnostic>>(file.readText()).mapNotNull(::sanitize)
                saved.forEach { persist(it) }
            } catch (_: Exception) { unreadable = true }
        }
        mutableEntries.value = saved.distinctBy { it.id }.sortedBy { it.id }.takeLast(MAX_ENTRIES)
        nextId = (mutableEntries.value.maxOfOrNull { it.id } ?: 0L) + 1L
        if (unreadable) append(HarnessRuntimeDiagnostic(
            nextId++, clock(), "journal_unreadable", errorCode = "HARNESS_DIAGNOSTICS_UNREADABLE"
        ))
        // These are obsolete journal files only; successful segment migration preserves their evidence.
        if (segmentFiles().isNotEmpty() && file.exists()) file.delete()
    }

    /** Only this journal's metadata is erased. Runtime, conversations and project files are untouched. */
    @Synchronized
    fun clear() {
        segmentFiles().forEach { check(it.delete()) { "HARNESS_DIAGNOSTICS_WRITE_FAILED" } }
        if (file.exists()) check(file.delete()) { "HARNESS_DIAGNOSTICS_WRITE_FAILED" }
        mutableEntries.value = emptyList()
        successes.clear()
        segmentCounts.clear()
        nextId = 1L
        loaded = true
    }

    /** Called on the runtime's IO scope, before conversation-specific fan-out. */
    @Synchronized
    fun record(event: HarnessRuntimeLogEvent) {
        load()
        append(HarnessRuntimeDiagnostic(
            id = nextId++, timestampMs = clock(), event = event.event,
            phase = event.phase,
            generation = event.generation, state = event.state?.name,
            durationMs = event.durationMs, errorCode = event.errorCode, exitCode = event.exitCode,
        ))
    }

    @Synchronized
    fun recordConnection(
        event: String, transport: String? = null, operation: String? = null,
        outcome: String? = null, durationMs: Long? = null, errorCode: String? = null,
        httpStatus: Int? = null, closeCode: Int? = null, attempt: Int? = null,
        activeStreams: Int? = null,
        webBoot: Map<String, Int> = emptyMap(),
    ) {
        load()
        append(HarnessRuntimeDiagnostic(
            id = nextId++, timestampMs = clock(), event = event,
            transport = transport, operation = operation, outcome = outcome,
            durationMs = durationMs, errorCode = errorCode, httpStatus = httpStatus,
            closeCode = closeCode, attempt = attempt, activeStreams = activeStreams,
            webBoot = webBoot,
        ))
    }

    private fun append(raw: HarnessRuntimeDiagnostic) {
        var entry = sanitize(raw) ?: return
        if (isRoutine(entry)) {
            val key = "${entry.event}/${entry.operation}/${entry.outcome}"
            val previous = successes[key]
            if (previous != null && entry.timestampMs - previous.first in 0 until COALESCE_MS) {
                successes[key] = previous.first to (previous.second + 1).coerceAtMost(1_000_000)
                return
            }
            entry = entry.copy(coalescedCount = previous?.second?.takeIf { it > 0 })
            successes[key] = entry.timestampMs to 0
        }
        try {
            val removedIds = persist(entry)
            mutableEntries.value = (mutableEntries.value.filterNot { it.id in removedIds } + entry).takeLast(MAX_ENTRIES)
        } catch (_: Exception) {
            mutableEntries.value = (mutableEntries.value + entry + HarnessRuntimeDiagnostic(
                nextId++, clock(), "journal_write_failed", errorCode = "HARNESS_DIAGNOSTICS_WRITE_FAILED"
            )).takeLast(MAX_ENTRIES)
        }
    }

    private fun segmentFiles(prefix: String? = null): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && SEGMENT_NAME.matches(it.name) && (prefix == null || it.name.startsWith(prefix)) }
        .sortedBy { it.name.substringAfter('-').substringBefore('.').toLongOrNull() ?: 0L }

    private fun persist(entry: HarnessRuntimeDiagnostic): Set<Long> {
        check(directory.isDirectory || directory.mkdirs())
        val prefix = if (isRoutine(entry)) "routine-" else "priority-"
        val limit = if (isRoutine(entry)) 8 else 2
        val bytes = (json.encodeToString(entry) + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= SEGMENT_BYTES)
        var segments = segmentFiles(prefix)
        val previous = segments.lastOrNull()
        val current = if (previous == null || previous.length() + bytes.size > SEGMENT_BYTES ||
            segmentCounts.getOrPut(previous.name) { previous.useLines { it.take(SEGMENT_ENTRIES).count() } } >= SEGMENT_ENTRIES) {
            File(directory, "$prefix${entry.id}.jsonl").also { segments = segments + it }
        } else previous
        val removedIds = mutableSetOf<Long>()
        // Retention removes only the oldest segment in its lane; noisy RPC reads have their own quota.
        segments.dropLast(limit).forEach { expired ->
            expired.useLines { lines -> lines.forEach { line ->
                runCatching { json.decodeFromString<HarnessRuntimeDiagnostic>(line).id }.getOrNull()?.let(removedIds::add)
            } }
            check(expired.delete())
            segmentCounts.remove(expired.name)
        }
        FileOutputStream(current, true).use { output ->
            output.write(bytes)
            if (!isRoutine(entry)) output.fd.sync()
        }
        segmentCounts[current.name] = (segmentCounts[current.name] ?: 0) + 1
        return removedIds
    }

    private fun isRoutine(entry: HarnessRuntimeDiagnostic): Boolean = entry.errorCode == null &&
        ((entry.event == "rpc" && entry.outcome == "success" && entry.operation in READ_OPERATIONS) ||
            entry.event == "native_recovered")

    private fun sanitize(entry: HarnessRuntimeDiagnostic): HarnessRuntimeDiagnostic? {
        if (entry.event !in EVENTS || entry.id !in 1..MAX_ID || entry.timestampMs < 0) return null
        return entry.copy(
            phase = entry.phase?.takeIf { it in PHASES },
            generation = entry.generation?.takeIf { it.matches(UUID_PATTERN) },
            state = entry.state?.takeIf { it in STATES },
            durationMs = entry.durationMs?.takeIf { it in 0..86_400_000L },
            errorCode = entry.errorCode?.let(::harnessDiagnosticErrorClass),
            exitCode = entry.exitCode?.takeIf { it in -255..255 },
            transport = entry.transport?.takeIf { it in setOf("http", "websocket", "webview", "native") },
            operation = entry.operation?.let(::harnessDiagnosticOperation),
            outcome = entry.outcome?.takeIf { it in OUTCOMES },
            httpStatus = entry.httpStatus?.takeIf { it in 100..599 },
            closeCode = entry.closeCode?.takeIf { it in 1000..4999 },
            attempt = entry.attempt?.takeIf { it in 0..1000 },
            activeStreams = entry.activeStreams?.takeIf { it in 0..128 },
            coalescedCount = entry.coalescedCount?.takeIf { it in 1..1_000_000 },
            webBoot = entry.webBoot.filter { (key, value) -> key in WEB_BOOT_COUNTS && value in 0..100_000 },
        )
    }

    companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_BYTES = 10 * 1024 * 1024
        private const val SEGMENT_ENTRIES = 1000
        private const val SEGMENT_BYTES = 1024 * 1024
        private const val COALESCE_MS = 30_000L
        private val WEB_BOOT_COUNTS = setOf("entries", "batches", "pending", "scripts", "assets", "stylesheets", "importsFailed", "mounted", "overlay", "failedOverlay", "resourceError")
        private val SEGMENT_NAME = Regex("(?:priority|routine)-[0-9]+\\.jsonl")
        private val READ_OPERATIONS = setOf("session/list", "session/modelCatalog", "settings/describe",
            "llm/listConfigurableProviders", "credentials/describe", "agentPresets/list", "skills/list",
            "messageFeedback/list", "commands/list", "subagents/list")
        private const val MAX_ID = Long.MAX_VALUE / 2
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
        private val STATES = setOf("STOPPED", "STARTING", "RUNNING", "STOP_REQUESTED", "FORCE_STOPPING", "FAILED", "INTERRUPTED")
        internal val EVENTS = setOf(
            "app_start_requested", "app_start_failed", "app_operation_failed", "start_requested", "phase",
            "environment_preparing", "environment_ready", "payload_preparing", "payload_ready",
            "bridge_preparing", "process_launching", "process_launched", "readiness_waiting",
            "startup_output", "process_exited", "started", "start_failed", "recovered",
            "stopped", "stop_timed_out", "force_stopped", "force_stop_incomplete",
            "stop_hook_timed_out", "stop_hook_failed", "journal_unreadable", "journal_write_failed",
            "rpc", "websocket", "webview", "native_failure", "native_recovered",
        )
        internal val PHASES = setOf(
            "environment_preparing", "environment_ready", "payload_preparing", "payload_ready",
            "bridge_preparing", "bridge_ready", "process_launching", "process_launched",
            "process_identity_ready", "readiness_waiting", "readiness_ready",
        )
        private val OUTCOMES = setOf("success", "failure", "opening", "open", "closed", "retrying",
            "cancelled", "ready", "waiting", "started", "completed", "interrupted", "recovered")
    }
}
