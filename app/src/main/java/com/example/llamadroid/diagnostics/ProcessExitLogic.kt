package com.example.llamadroid.diagnostics

/** Metadata-only view of one `ApplicationExitInfo`. Never carries content. */
data class ProcessExitRecord(
    val processName: String,
    val reason: Int,
    val status: Int,
    val description: String,
    val timestamp: Long,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
)

/** Plain-language buckets for Android exit reasons. */
enum class ProcessExitCategory {
    LOW_MEMORY,
    EXCESSIVE_RESOURCE_USAGE,
    USER_REQUESTED,
    SIGNALED,
    ANR,
    CRASH,
    CRASH_NATIVE,
    DEPENDENCY_DIED,
    FREEZER,
    FGS_TIMEOUT,
    PERMISSION_CHANGE,
    OTHER,
}

/** Pure mapping and bookkeeping helpers (unit tested). */
object ProcessExitLogic {
    const val MAX_STORED = 20
    const val MAX_DESCRIPTION = 160

    // Values of android.app.ApplicationExitInfo.REASON_*; duplicated so the logic stays JVM-testable.
    const val REASON_EXIT_SELF = 1
    const val REASON_SIGNALED = 2
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_PERMISSION_CHANGE = 8
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    const val REASON_USER_REQUESTED = 10
    const val REASON_USER_STOPPED = 11
    const val REASON_DEPENDENCY_DIED = 12
    const val REASON_FREEZER = 14

    fun reasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        REASON_EXIT_SELF -> "EXIT_SELF"
        REASON_SIGNALED -> "SIGNALED"
        REASON_LOW_MEMORY -> "LOW_MEMORY"
        REASON_CRASH -> "CRASH"
        REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        REASON_ANR -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        REASON_USER_REQUESTED -> "USER_REQUESTED"
        REASON_USER_STOPPED -> "USER_STOPPED"
        REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        REASON_FREEZER -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    fun isForegroundServiceTimeout(description: String): Boolean {
        val d = description.lowercase()
        return d.contains("timeout") && (d.contains("foreground") || d.contains("fgs")) ||
            d.contains("did not stop")
    }

    fun categorize(reason: Int, description: String): ProcessExitCategory = when {
        isForegroundServiceTimeout(description) -> ProcessExitCategory.FGS_TIMEOUT
        reason == REASON_LOW_MEMORY -> ProcessExitCategory.LOW_MEMORY
        reason == REASON_EXCESSIVE_RESOURCE_USAGE -> ProcessExitCategory.EXCESSIVE_RESOURCE_USAGE
        reason == REASON_USER_REQUESTED || reason == REASON_USER_STOPPED -> ProcessExitCategory.USER_REQUESTED
        reason == REASON_SIGNALED -> ProcessExitCategory.SIGNALED
        reason == REASON_ANR -> ProcessExitCategory.ANR
        reason == REASON_CRASH -> ProcessExitCategory.CRASH
        reason == REASON_CRASH_NATIVE -> ProcessExitCategory.CRASH_NATIVE
        reason == REASON_DEPENDENCY_DIED -> ProcessExitCategory.DEPENDENCY_DIED
        reason == REASON_FREEZER -> ProcessExitCategory.FREEZER
        reason == REASON_PERMISSION_CHANGE -> ProcessExitCategory.PERMISSION_CHANGE
        else -> ProcessExitCategory.OTHER
    }

    /** Normal self-exits are not "stops" worth recording. */
    fun isInteresting(record: ProcessExitRecord): Boolean = record.reason != REASON_EXIT_SELF

    fun boundDescription(text: String?): String =
        (text ?: "").replace('\n', ' ').trim().take(MAX_DESCRIPTION)

    /** Records newer than [lastSeen], newest first, de-duplicated by process and timestamp. */
    fun newSince(candidates: List<ProcessExitRecord>, lastSeen: Long): List<ProcessExitRecord> =
        candidates
            .filter { it.timestamp > lastSeen && isInteresting(it) }
            .distinctBy { it.processName to it.timestamp }
            .sortedByDescending { it.timestamp }

    /** Newest-first merge capped at [max]; the same process and timestamp is kept once. */
    fun merge(stored: List<ProcessExitRecord>, added: List<ProcessExitRecord>, max: Int = MAX_STORED): List<ProcessExitRecord> =
        (added + stored)
            .distinctBy { it.processName to it.timestamp }
            .sortedByDescending { it.timestamp }
            .take(max)

    fun newestTimestamp(records: List<ProcessExitRecord>, fallback: Long): Long =
        maxOf(fallback, records.maxOfOrNull { it.timestamp } ?: fallback)
}
