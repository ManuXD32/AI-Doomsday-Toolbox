package com.example.llamadroid.service

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.security.MessageDigest

data class GenerationBreadcrumb(
    val timestamp: Long,
    val source: String,
    val sessionId: String?,
    val mode: String?,
    val event: String,
    val phase: String?,
    val details: String?,
    val wakeLockHeld: Boolean?,
    val notificationActive: Boolean?,
    val batteryExempt: Boolean?,
    val interactive: Boolean?,
    val powerSaveMode: Boolean?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("source", source)
        put("sessionId", sessionId)
        put("mode", mode)
        put("event", event)
        put("phase", phase)
        put("details", details)
        put("wakeLockHeld", wakeLockHeld)
        put("notificationActive", notificationActive)
        put("batteryExempt", batteryExempt)
        put("interactive", interactive)
        put("powerSaveMode", powerSaveMode)
    }

    companion object {
        fun fromJson(json: JSONObject): GenerationBreadcrumb =
            GenerationBreadcrumb(
                timestamp = json.optLong("timestamp", 0L),
                source = json.optString("source"),
                sessionId = json.optString("sessionId").ifBlank { null },
                mode = json.optString("mode").ifBlank { null },
                event = json.optString("event"),
                phase = json.optString("phase").ifBlank { null },
                details = json.optString("details").ifBlank { null },
                wakeLockHeld = json.optBooleanOrNull("wakeLockHeld"),
                notificationActive = json.optBooleanOrNull("notificationActive"),
                batteryExempt = json.optBooleanOrNull("batteryExempt"),
                interactive = json.optBooleanOrNull("interactive"),
                powerSaveMode = json.optBooleanOrNull("powerSaveMode")
            )
    }
}

data class GenerationExitSnapshot(
    val timestamp: Long,
    val reasonCode: Int,
    val reasonLabel: String,
    val status: Int,
    val importance: Int,
    val processName: String?,
    val pid: Int?,
    val processPssKb: Long?,
    val processRssKb: Long?,
    val description: String?,
    val traceSnippet: String?,
    val traceFormat: String?,
    val traceByteCount: Int?,
    val traceSha256: String?,
    val traceTruncated: Boolean = false,
    /** Bounded tombstone metadata; the raw trace remains a separate export-only file. */
    val tombstoneSummary: String? = null,
    val hadActiveGeneration: Boolean,
    val sessionSummary: String?,
    val activeSessionDetails: List<String>,
    val correlationSummary: String?,
    val relatedProcessSummary: String?,
    val agentJournalSummary: String?,
    val deviceSummary: String?,
    val memorySummary: String?,
    val appVersion: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("reasonCode", reasonCode)
        put("reasonLabel", reasonLabel)
        put("status", status)
        put("importance", importance)
        put("processName", processName)
        put("pid", pid)
        put("processPssKb", processPssKb)
        put("processRssKb", processRssKb)
        put("description", description)
        put("traceSnippet", traceSnippet)
        put("traceFormat", traceFormat)
        put("traceByteCount", traceByteCount)
        put("traceSha256", traceSha256)
        put("traceTruncated", traceTruncated)
        put("tombstoneSummary", tombstoneSummary)
        put("hadActiveGeneration", hadActiveGeneration)
        put("sessionSummary", sessionSummary)
        put("activeSessionDetails", JSONArray(activeSessionDetails))
        put("correlationSummary", correlationSummary)
        put("relatedProcessSummary", relatedProcessSummary)
        put("agentJournalSummary", agentJournalSummary)
        put("deviceSummary", deviceSummary)
        put("memorySummary", memorySummary)
        put("appVersion", appVersion)
    }

    companion object {
        fun fromJson(json: JSONObject): GenerationExitSnapshot =
            GenerationExitSnapshot(
                timestamp = json.optLong("timestamp", 0L),
                reasonCode = json.optInt("reasonCode", 0),
                reasonLabel = json.optString("reasonLabel"),
                status = json.optInt("status", 0),
                importance = json.optInt("importance", 0),
                processName = json.optString("processName").ifBlank { null },
                pid = json.optInt("pid", -1).takeIf { it >= 0 },
                processPssKb = json.optLong("processPssKb", -1L).takeIf { it >= 0L },
                processRssKb = json.optLong("processRssKb", -1L).takeIf { it >= 0L },
                description = json.optString("description").ifBlank { null },
                traceSnippet = json.optString("traceSnippet").ifBlank { null },
                traceFormat = json.optString("traceFormat").ifBlank { null },
                traceByteCount = json.optInt("traceByteCount", -1).takeIf { it >= 0 },
                traceSha256 = json.optString("traceSha256").ifBlank { null },
                traceTruncated = json.optBoolean("traceTruncated", false),
                tombstoneSummary = json.optString("tombstoneSummary").ifBlank { null },
                hadActiveGeneration = json.optBoolean("hadActiveGeneration", false),
                sessionSummary = json.optString("sessionSummary").ifBlank { null },
                activeSessionDetails = json.optJSONArray("activeSessionDetails")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }.orEmpty(),
                correlationSummary = json.optString("correlationSummary").ifBlank { null },
                relatedProcessSummary = json.optString("relatedProcessSummary").ifBlank { null },
                agentJournalSummary = json.optString("agentJournalSummary").ifBlank { null },
                deviceSummary = json.optString("deviceSummary").ifBlank { null },
                memorySummary = json.optString("memorySummary").ifBlank { null },
                appVersion = json.optString("appVersion").ifBlank { null }
            )
    }
}

/** Metadata for the raw trace file. The trace body intentionally stays out of JSON diagnostics. */
internal data class ExitTraceCapture(
    val summary: String,
    val format: String,
    val byteCount: Int,
    val sha256: String,
    val truncated: Boolean
)

/**
 * Streams an ApplicationExitInfo trace directly to a temporary app-private file. A single extra
 * byte detects a safety-cap truncation without retaining a second full copy in the UI process.
 */
internal fun captureExitTraceForDiagnostics(
    input: InputStream,
    destination: File,
    maxBytes: Int = 8 * 1024 * 1024
): ExitTraceCapture? {
    require(maxBytes > 0) { "Trace cap must be positive" }
    destination.parentFile?.mkdirs()
    val temporary = File(destination.parentFile, "${destination.name}.${UUID.randomUUID()}.tmp")
    val digest = MessageDigest.getInstance("SHA-256")
    val preview = java.io.ByteArrayOutputStream(minOf(maxBytes, TRACE_PREVIEW_LIMIT))
    var captured = 0
    try {
        FileOutputStream(temporary).use { output ->
            val buffer = ByteArray(8 * 1024)
            while (captured < maxBytes) {
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - captured))
                if (read <= 0) break
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                if (preview.size() < TRACE_PREVIEW_LIMIT) {
                    preview.write(buffer, 0, minOf(read, TRACE_PREVIEW_LIMIT - preview.size()))
                }
                captured += read
            }
            output.fd.sync()
        }
        if (captured == 0) {
            temporary.delete()
            return null
        }
        val truncated = input.read() != -1
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        val binary = preview.toByteArray().isLikelyBinaryExitTrace()
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        return ExitTraceCapture(
            summary = "${if (binary) "binary" else "text"} trace " +
                "($captured captured bytes${if (truncated) ", truncated" else ""}, sha256=$sha256)",
            format = if (binary) "binary" else "text",
            byteCount = captured,
            sha256 = sha256,
            truncated = truncated
        )
    } catch (error: Throwable) {
        temporary.delete()
        throw error
    }
}

private const val TRACE_PREVIEW_LIMIT = 65_536
private const val TRACE_SNIPPET_LIMIT = 8 * 1024

private fun ByteArray.isLikelyBinaryExitTrace(): Boolean {
    if (any { it.toInt() == 0 }) return true
    val sample = take(512)
    if (sample.isEmpty()) return false
    val controlCount = sample.count { byte ->
        val value = byte.toInt() and 0xff
        value < 0x09 || value in 0x0E..0x1F || value == 0x7F
    }
    return controlCount > sample.size / 50
}

object GenerationDiagnosticsStore {
    private const val PREFS_NAME = "generation_diagnostics"
    private const val KEY_ACTIVE_SESSIONS = "active_sessions_json"
    private const val KEY_LAST_PROCESSED_EXIT_TIMESTAMP = "last_processed_exit_timestamp"
    private const val KEY_PENDING_RELAUNCH_WARNING = "pending_relaunch_warning"
    private const val DIAGNOSTICS_DIR = "generation_diagnostics"
    private const val BREADCRUMBS_FILE = "breadcrumbs.jsonl"
    private const val EXIT_SNAPSHOT_FILE = "last_exit_snapshot.json"
    private const val EXIT_TRACE_FILE = "last_exit_trace.bin"
    private const val MAX_RECENT_BREADCRUMBS = 80
    private const val MAX_STORED_BREADCRUMBS = 200
    private const val TRACE_CAPTURE_LIMIT = 8 * 1024 * 1024

    private val recentBreadcrumbsState = MutableStateFlow<List<GenerationBreadcrumb>>(emptyList())
    val recentBreadcrumbs: StateFlow<List<GenerationBreadcrumb>> = recentBreadcrumbsState

    private val latestExitSnapshotState = MutableStateFlow<GenerationExitSnapshot?>(null)
    val latestExitSnapshot: StateFlow<GenerationExitSnapshot?> = latestExitSnapshotState

    private val lock = Any()
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = context.applicationContext
                prefs = appContext!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            recentBreadcrumbsState.value = loadBreadcrumbsLocked()
            latestExitSnapshotState.value = readExitSnapshotLocked()
        }
        captureLatestExitReasonIfNeeded()
    }

    fun startSession(
        source: String,
        mode: String,
        details: String?,
        phase: String?,
        wakeLockHeld: Boolean?,
        notificationActive: Boolean?,
        batteryExempt: Boolean?,
        interactive: Boolean?,
        powerSaveMode: Boolean?
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            ensureInitializedLocked()
            val sessions = loadActiveSessionsLocked().toMutableList()
            sessions.removeAll { it.id == sessionId }
            sessions.add(
                ActiveGenerationSession(
                    id = sessionId,
                    source = source,
                    mode = mode,
                    details = details,
                    startedAt = now,
                    lastUpdatedAt = now,
                    lastPhase = phase
                )
            )
            saveActiveSessionsLocked(sessions)
            appendBreadcrumbLocked(
                GenerationBreadcrumb(
                    timestamp = now,
                    source = source,
                    sessionId = sessionId,
                    mode = mode,
                    event = "session_started",
                    phase = phase,
                    details = details,
                    wakeLockHeld = wakeLockHeld,
                    notificationActive = notificationActive,
                    batteryExempt = batteryExempt,
                    interactive = interactive,
                    powerSaveMode = powerSaveMode
                )
            )
        }
        return sessionId
    }

    fun finishSession(
        sessionId: String?,
        source: String,
        mode: String?,
        outcome: String,
        details: String?,
        wakeLockHeld: Boolean?,
        notificationActive: Boolean?,
        batteryExempt: Boolean?,
        interactive: Boolean?,
        powerSaveMode: Boolean?
    ) {
        if (sessionId == null) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            ensureInitializedLocked()
            val sessions = loadActiveSessionsLocked().toMutableList()
            val existingSession = sessions.firstOrNull { it.id == sessionId }
            appendBreadcrumbLocked(
                GenerationBreadcrumb(
                    timestamp = now,
                    source = source,
                    sessionId = sessionId,
                    mode = mode ?: existingSession?.mode,
                    event = "session_finished:$outcome",
                    phase = existingSession?.lastPhase,
                    details = details ?: existingSession?.details,
                    wakeLockHeld = wakeLockHeld,
                    notificationActive = notificationActive,
                    batteryExempt = batteryExempt,
                    interactive = interactive,
                    powerSaveMode = powerSaveMode
                )
            )
            sessions.removeAll { it.id == sessionId }
            saveActiveSessionsLocked(sessions)
        }
    }

    fun recordBreadcrumb(
        source: String,
        sessionId: String? = null,
        mode: String? = null,
        event: String,
        phase: String? = null,
        details: String? = null,
        wakeLockHeld: Boolean? = null,
        notificationActive: Boolean? = null,
        batteryExempt: Boolean? = null,
        interactive: Boolean? = null,
        powerSaveMode: Boolean? = null
    ) {
        synchronized(lock) {
            ensureInitializedLocked()
            val sessions = loadActiveSessionsLocked().toMutableList()
            if (sessionId != null) {
                val updatedSessions = sessions.map { session ->
                    if (session.id == sessionId) {
                        session.copy(
                            lastUpdatedAt = System.currentTimeMillis(),
                            lastPhase = phase ?: session.lastPhase,
                            details = details ?: session.details
                        )
                    } else {
                        session
                    }
                }
                saveActiveSessionsLocked(updatedSessions)
            }
            appendBreadcrumbLocked(
                GenerationBreadcrumb(
                    timestamp = System.currentTimeMillis(),
                    source = source,
                    sessionId = sessionId,
                    mode = mode,
                    event = event,
                    phase = phase,
                    details = details,
                    wakeLockHeld = wakeLockHeld,
                    notificationActive = notificationActive,
                    batteryExempt = batteryExempt,
                    interactive = interactive,
                    powerSaveMode = powerSaveMode
                )
            )
        }
    }

    fun captureLatestExitReasonIfNeeded() {
        if (!isApplicationExitInfoAvailable()) return
        val context = appContext ?: return
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exitReasons = runCatching {
            ApplicationExitInfoApi30.readRecords(context, activityManager, maxCount = 30)
        }.getOrNull().orEmpty()
        val latestExit = choosePrimaryExitForDiagnostics(exitReasons, context.packageName) ?: return
        val relatedProcessSummary = buildRelatedProcessSummary(exitReasons, latestExit, context.packageName)

        synchronized(lock) {
            ensureInitializedLocked()
            val preferences = prefs ?: return
            val lastProcessed = preferences.getLong(KEY_LAST_PROCESSED_EXIT_TIMESTAMP, 0L)
            if (latestExit.timestamp <= lastProcessed) return

            val activeSessions = loadActiveSessionsLocked()
            val traceCapture = readTraceCapture(latestExit, exitTraceFileLocked())
            if (traceCapture == null) exitTraceFileLocked().delete()
            val tombstoneSummary = if (traceCapture?.format == "binary") {
                summarizeAndroidTombstoneFile(exitTraceFileLocked())?.compactText()
            } else {
                null
            }
            val correlatedBreadcrumbs = loadBreadcrumbsLocked(limit = MAX_STORED_BREADCRUMBS)
                .filter { it.timestamp in (latestExit.timestamp - 120_000L)..latestExit.timestamp }
                .takeLast(20)
            val agentJournalBreadcrumbs = correlatedBreadcrumbs
                .filter { it.source == "agent_journal" || it.source == "agent_stream" || it.source == "agent_tool_runtime" }
                .takeLast(12)
            val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val sessionDetails = activeSessions.map { session ->
                buildString {
                    append("${session.source}:${session.mode}")
                    append(" started=${session.startedAt}")
                    append(" updated=${session.lastUpdatedAt}")
                    if (!session.lastPhase.isNullOrBlank()) append(" phase=${session.lastPhase}")
                    if (!session.details.isNullOrBlank()) append(" details=${session.details}")
                }
            }
            val snapshot = GenerationExitSnapshot(
                timestamp = latestExit.timestamp,
                reasonCode = latestExit.reason,
                reasonLabel = latestExit.reasonLabel,
                status = latestExit.status,
                importance = latestExit.importance,
                processName = latestExit.processName?.takeIf { it.isNotBlank() },
                pid = latestExit.pid,
                processPssKb = latestExit.pss.takeIf { it > 0L },
                processRssKb = latestExit.rss.takeIf { it > 0L },
                description = latestExit.description?.takeIf { it.isNotBlank() },
                traceSnippet = traceCapture?.summary,
                traceFormat = traceCapture?.format,
                traceByteCount = traceCapture?.byteCount,
                traceSha256 = traceCapture?.sha256,
                traceTruncated = traceCapture?.truncated ?: false,
                tombstoneSummary = tombstoneSummary,
                hadActiveGeneration = activeSessions.isNotEmpty(),
                sessionSummary = activeSessions
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" | ") { session ->
                        buildString {
                            append("${session.source}:${session.mode}")
                            if (!session.lastPhase.isNullOrBlank()) append(" phase=${session.lastPhase}")
                            if (!session.details.isNullOrBlank()) append(" ${session.details}")
                        }
                    },
                activeSessionDetails = sessionDetails,
                correlationSummary = correlatedBreadcrumbs
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" | ") { breadcrumb ->
                        buildString {
                            append("${breadcrumb.source}:${breadcrumb.event}")
                            breadcrumb.phase?.let { append("@$it") }
                        }
                    },
                relatedProcessSummary = relatedProcessSummary,
                agentJournalSummary = agentJournalBreadcrumbs
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" | ") { breadcrumb ->
                        buildString {
                            append("${breadcrumb.source}:${breadcrumb.event}")
                            breadcrumb.mode?.let { append("[$it]") }
                            breadcrumb.phase?.let { append("@$it") }
                            breadcrumb.details?.takeIf { it.isNotBlank() }?.let { append(" ").append(it.take(180)) }
                        }
                    },
                deviceSummary = "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} " +
                    "(SDK ${Build.VERSION.SDK_INT}); ABI=${Build.SUPPORTED_ABIS.joinToString()}",
                memorySummary = "relaunchAvailable=${memoryInfo.availMem} total=${memoryInfo.totalMem} " +
                    "lowMemory=${memoryInfo.lowMemory} threshold=${memoryInfo.threshold} " +
                    "memoryClassMb=${activityManager.memoryClass} largeMemoryClassMb=${activityManager.largeMemoryClass}",
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            )

            writeExitSnapshotLocked(snapshot)
            latestExitSnapshotState.value = snapshot
            preferences.edit()
                .putLong(KEY_LAST_PROCESSED_EXIT_TIMESTAMP, latestExit.timestamp)
                .putBoolean(KEY_PENDING_RELAUNCH_WARNING, snapshot.hadActiveGeneration)
                .apply()
            if (snapshot.hadActiveGeneration) {
                DebugLog.log(
                    "[GEN-DIAG] Previous app session ended during active generation: " +
                        "${snapshot.reasonLabel} (${snapshot.description ?: "no description"})"
                )
            }
            saveActiveSessionsLocked(emptyList())
        }
    }

    fun describeRecentProcessExit(
        processNameSuffix: String,
        sinceTimestamp: Long
    ): String? {
        if (!isApplicationExitInfoAvailable()) return null
        val context = appContext ?: return null
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exitInfo = runCatching {
            ApplicationExitInfoApi30.readRecords(context, activityManager, maxCount = 30)
                .filter { info ->
                    info.timestamp >= sinceTimestamp &&
                        info.processName.orEmpty().endsWith(processNameSuffix)
                }
                .maxByOrNull { it.timestamp }
        }.getOrNull() ?: return null

        val traceLine = readTraceSnippet(exitInfo)
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.take(700)
        return buildString {
            append(exitInfo.processName)
            append(": ")
            append(exitInfo.reasonLabel)
            append(" status=")
            append(exitInfo.status)
            exitInfo.description?.takeIf { it.isNotBlank() }?.let { description ->
                append(" description=")
                append(description.take(700))
            }
            traceLine?.let {
                append(" trace=")
                append(it)
            }
        }
    }

    fun consumePendingRelaunchWarning(): GenerationExitSnapshot? {
        synchronized(lock) {
            ensureInitializedLocked()
            val preferences = prefs ?: return null
            if (!preferences.getBoolean(KEY_PENDING_RELAUNCH_WARNING, false)) return null
            preferences.edit().putBoolean(KEY_PENDING_RELAUNCH_WARNING, false).apply()
            return latestExitSnapshotState.value
        }
    }

    fun clearPersistedDiagnostics() {
        synchronized(lock) {
            ensureInitializedLocked()
            breadcrumbsFileLocked().delete()
            exitSnapshotFileLocked().delete()
            exitTraceFileLocked().delete()
            latestExitSnapshotState.value = null
            recentBreadcrumbsState.value = emptyList()
            prefs?.edit()
                ?.putBoolean(KEY_PENDING_RELAUNCH_WARNING, false)
                ?.apply()
        }
    }

    fun loadAllStoredBreadcrumbs(): List<GenerationBreadcrumb> {
        synchronized(lock) {
            ensureInitializedLocked()
            return loadBreadcrumbsLocked(limit = MAX_STORED_BREADCRUMBS)
        }
    }

    fun latestTraceFileForExport(): File? {
        synchronized(lock) {
            ensureInitializedLocked()
            return exitTraceFileLocked().takeIf { it.isFile && it.length() > 0L }
        }
    }

    fun activeSessionSummaryForBreadcrumb(maxSessions: Int = 4): String {
        synchronized(lock) {
            ensureInitializedLocked()
            return loadActiveSessionsLocked()
                .takeLast(maxSessions)
                .joinToString(";") { session ->
                    buildString {
                        append("${session.source}:${session.mode}")
                        session.lastPhase?.takeIf { it.isNotBlank() }?.let { append("@$it") }
                        session.details?.takeIf { it.isNotBlank() }?.let { append(" ").append(it.take(120)) }
                    }
                }
                .ifBlank { "none" }
        }
    }

    private fun appendBreadcrumbLocked(entry: GenerationBreadcrumb) {
        val file = breadcrumbsFileLocked()
        file.parentFile?.mkdirs()
        file.appendText(entry.toJson().toString() + "\n")
        trimBreadcrumbFileLocked(file)
            recentBreadcrumbsState.value = loadBreadcrumbsLocked()
    }

    private fun trimBreadcrumbFileLocked(file: File) {
        val lines = file.takeIf { it.exists() }?.readLines().orEmpty()
        if (lines.size > MAX_STORED_BREADCRUMBS) {
            file.writeText(lines.takeLast(MAX_STORED_BREADCRUMBS).joinToString("\n") + "\n")
        }
    }

    private fun loadBreadcrumbsLocked(limit: Int = MAX_RECENT_BREADCRUMBS): List<GenerationBreadcrumb> {
        val file = breadcrumbsFileLocked()
        if (!file.exists()) return emptyList()
        return file.readLines()
            .mapNotNull { line ->
                runCatching { GenerationBreadcrumb.fromJson(JSONObject(line)) }.getOrNull()
            }
            .takeLast(limit)
    }

    private fun readExitSnapshotLocked(): GenerationExitSnapshot? {
        val file = exitSnapshotFileLocked()
        if (!file.exists()) return null
        return runCatching {
            GenerationExitSnapshot.fromJson(JSONObject(file.readText()))
        }.getOrNull()
    }

    private fun writeExitSnapshotLocked(snapshot: GenerationExitSnapshot) {
        val file = exitSnapshotFileLocked()
        file.parentFile?.mkdirs()
        file.writeText(snapshot.toJson().toString(2))
    }

    private fun loadActiveSessionsLocked(): List<ActiveGenerationSession> {
        val raw = prefs?.getString(KEY_ACTIVE_SESSIONS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val jsonArray = JSONArray(raw)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    add(ActiveGenerationSession.fromJson(item))
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun saveActiveSessionsLocked(sessions: List<ActiveGenerationSession>) {
        val jsonArray = JSONArray()
        sessions.forEach { jsonArray.put(it.toJson()) }
        prefs?.edit()?.putString(KEY_ACTIVE_SESSIONS, jsonArray.toString())?.apply()
    }

    private fun readTraceSnippet(exitInfo: ApplicationExitRecord): String? = runCatching {
        exitInfo.openTrace()?.use { input ->
            val bytes = ByteArray(TRACE_SNIPPET_LIMIT)
            val read = input.read(bytes)
            if (read > 0) bytes.copyOf(read).toString(Charsets.UTF_8).trim() else null
        }
    }.getOrNull()

    private fun readTraceCapture(exitInfo: ApplicationExitRecord, destination: File): ExitTraceCapture? =
        runCatching {
            exitInfo.openTrace()?.use { input ->
                captureExitTraceForDiagnostics(input, destination, TRACE_CAPTURE_LIMIT)
            }
        }.getOrNull()

    private fun choosePrimaryExitForDiagnostics(
        exits: List<ApplicationExitRecord>,
        packageName: String
    ): ApplicationExitRecord? {
        val sorted = exits.sortedByDescending { it.timestamp }
        val mainProcessExit = sorted.firstOrNull { info ->
            info.processName == packageName && !info.isBenignIsolatedProcessExit()
        }
        if (mainProcessExit != null) return mainProcessExit

        val nonBenignNonSandboxExit = sorted.firstOrNull { info ->
            !info.isBenignIsolatedProcessExit() && !info.isWebViewSandboxProcess(packageName)
        }
        if (nonBenignNonSandboxExit != null) return nonBenignNonSandboxExit

        return sorted.firstOrNull()
    }

    private fun buildRelatedProcessSummary(
        exits: List<ApplicationExitRecord>,
        primary: ApplicationExitRecord,
        packageName: String
    ): String? {
        return exits
            .asSequence()
            .filter { it.timestamp != primary.timestamp || it.pid != primary.pid || it.processName != primary.processName }
            .sortedByDescending { it.timestamp }
            .take(5)
            .map { info ->
                buildString {
                    append(info.processName.orEmpty().ifBlank { "unknown" })
                    append(" ")
                    append(info.reasonLabel)
                    append(" status=").append(info.status)
                    if (info.isWebViewSandboxProcess(packageName)) append(" secondary=webview_sandbox")
                    if (info.isBenignIsolatedProcessExit()) append(" benign=isolated_not_needed")
                    info.description?.takeIf { it.isNotBlank() }?.let {
                        append(" description=").append(it.take(160))
                    }
                }
            }
            .joinToString(" | ")
            .ifBlank { null }
    }

    private fun ApplicationExitRecord.isWebViewSandboxProcess(packageName: String): Boolean {
        val process = processName.orEmpty()
        return process.startsWith("$packageName:") &&
            (process.contains("webview", ignoreCase = true) ||
                process.contains("sandbox", ignoreCase = true) ||
                process.contains("isolated", ignoreCase = true))
    }

    private fun ApplicationExitRecord.isBenignIsolatedProcessExit(): Boolean {
        val descriptionText = description.orEmpty()
        return isOtherReason &&
            descriptionText.contains("isolated not needed", ignoreCase = true)
    }

    private fun breadcrumbsFileLocked(): File = File(diagnosticsDirLocked(), BREADCRUMBS_FILE)

    private fun exitSnapshotFileLocked(): File = File(diagnosticsDirLocked(), EXIT_SNAPSHOT_FILE)

    private fun exitTraceFileLocked(): File = File(diagnosticsDirLocked(), EXIT_TRACE_FILE)

    private fun diagnosticsDirLocked(): File = File(checkNotNull(appContext).filesDir, DIAGNOSTICS_DIR)

    private fun ensureInitializedLocked() {
        checkNotNull(appContext) { "GenerationDiagnosticsStore.init(context) must be called first" }
        checkNotNull(prefs) { "GenerationDiagnosticsStore.init(context) must be called first" }
    }

    private data class ActiveGenerationSession(
        val id: String,
        val source: String,
        val mode: String,
        val details: String?,
        val startedAt: Long,
        val lastUpdatedAt: Long,
        val lastPhase: String?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("source", source)
            put("mode", mode)
            put("details", details)
            put("startedAt", startedAt)
            put("lastUpdatedAt", lastUpdatedAt)
            put("lastPhase", lastPhase)
        }

        companion object {
            fun fromJson(json: JSONObject): ActiveGenerationSession =
                ActiveGenerationSession(
                    id = json.optString("id"),
                    source = json.optString("source"),
                    mode = json.optString("mode"),
                    details = json.optString("details").ifBlank { null },
                    startedAt = json.optLong("startedAt", 0L),
                    lastUpdatedAt = json.optLong("lastUpdatedAt", 0L),
                    lastPhase = json.optString("lastPhase").ifBlank { null }
                )
        }
    }
}

private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    return if (has(key) && !isNull(key)) optBoolean(key) else null
}
