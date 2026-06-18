package com.example.llamadroid.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

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
    val description: String?,
    val traceSnippet: String?,
    val hadActiveGeneration: Boolean,
    val sessionSummary: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("reasonCode", reasonCode)
        put("reasonLabel", reasonLabel)
        put("status", status)
        put("importance", importance)
        put("description", description)
        put("traceSnippet", traceSnippet)
        put("hadActiveGeneration", hadActiveGeneration)
        put("sessionSummary", sessionSummary)
    }

    companion object {
        fun fromJson(json: JSONObject): GenerationExitSnapshot =
            GenerationExitSnapshot(
                timestamp = json.optLong("timestamp", 0L),
                reasonCode = json.optInt("reasonCode", 0),
                reasonLabel = json.optString("reasonLabel"),
                status = json.optInt("status", 0),
                importance = json.optInt("importance", 0),
                description = json.optString("description").ifBlank { null },
                traceSnippet = json.optString("traceSnippet").ifBlank { null },
                hadActiveGeneration = json.optBoolean("hadActiveGeneration", false),
                sessionSummary = json.optString("sessionSummary").ifBlank { null }
            )
    }
}

object GenerationDiagnosticsStore {
    private const val PREFS_NAME = "generation_diagnostics"
    private const val KEY_ACTIVE_SESSIONS = "active_sessions_json"
    private const val KEY_LAST_PROCESSED_EXIT_TIMESTAMP = "last_processed_exit_timestamp"
    private const val KEY_PENDING_RELAUNCH_WARNING = "pending_relaunch_warning"
    private const val DIAGNOSTICS_DIR = "generation_diagnostics"
    private const val BREADCRUMBS_FILE = "breadcrumbs.jsonl"
    private const val EXIT_SNAPSHOT_FILE = "last_exit_snapshot.json"
    private const val MAX_RECENT_BREADCRUMBS = 80
    private const val MAX_STORED_BREADCRUMBS = 200
    private const val TRACE_SNIPPET_LIMIT = 4096

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val context = appContext ?: return
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val latestExit = runCatching {
            activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, 20)
                .maxByOrNull { it.timestamp }
        }.getOrNull() ?: return

        synchronized(lock) {
            ensureInitializedLocked()
            val preferences = prefs ?: return
            val lastProcessed = preferences.getLong(KEY_LAST_PROCESSED_EXIT_TIMESTAMP, 0L)
            if (latestExit.timestamp <= lastProcessed) return

            val activeSessions = loadActiveSessionsLocked()
            val snapshot = GenerationExitSnapshot(
                timestamp = latestExit.timestamp,
                reasonCode = latestExit.reason,
                reasonLabel = exitReasonLabel(latestExit.reason),
                status = latestExit.status,
                importance = latestExit.importance,
                description = latestExit.description?.takeIf { it.isNotBlank() },
                traceSnippet = readTraceSnippet(latestExit),
                hadActiveGeneration = activeSessions.isNotEmpty(),
                sessionSummary = activeSessions
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" | ") { session ->
                        buildString {
                            append("${session.source}:${session.mode}")
                            if (!session.lastPhase.isNullOrBlank()) append(" phase=${session.lastPhase}")
                            if (!session.details.isNullOrBlank()) append(" ${session.details}")
                        }
                    }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val context = appContext ?: return null
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exitInfo = runCatching {
            activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, 30)
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
            append(exitReasonLabel(exitInfo.reason))
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

    private fun readTraceSnippet(exitInfo: ApplicationExitInfo): String? {
        return runCatching {
            exitInfo.traceInputStream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (output.size() < TRACE_SNIPPET_LIMIT) {
                    val toRead = minOf(buffer.size, TRACE_SNIPPET_LIMIT - output.size())
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
                    .takeIf { it.isNotEmpty() }
                    ?.toReadableExitTraceSnippet()
            }
        }.getOrNull()
    }

    private fun ByteArray.toReadableExitTraceSnippet(): String {
        if (isLikelyBinaryExitTrace()) return "binary trace (${size} bytes)"
        return toString(Charsets.UTF_8).trim()
            .takeIf { it.isNotBlank() }
            ?.toReadableExitTraceSnippet()
            ?: "binary trace (${size} bytes)"
    }

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

    private fun String.toReadableExitTraceSnippet(): String {
        val line = lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isReadableExitTraceLine() }
        return line?.take(TRACE_SNIPPET_LIMIT)
            ?: "binary trace (${length} chars)"
    }

    private fun String.isReadableExitTraceLine(): Boolean {
        if (isBlank()) return false
        if (none { it.isLetterOrDigit() }) return false
        val controlCount = count { it.code < 32 && it != '\t' }
        if (controlCount > 0) return false
        val readableCount = count { it == '\t' || it.code in 32..126 }
        return readableCount >= (length * 0.9).toInt()
    }

    private fun exitReasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "Crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Exited normally"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
        ApplicationExitInfo.REASON_OTHER -> "Other"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission change"
        ApplicationExitInfo.REASON_SIGNALED -> "Signaled"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "User requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "User stopped"
        else -> "Reason $reason"
    }

    private fun breadcrumbsFileLocked(): File = File(diagnosticsDirLocked(), BREADCRUMBS_FILE)

    private fun exitSnapshotFileLocked(): File = File(diagnosticsDirLocked(), EXIT_SNAPSHOT_FILE)

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
