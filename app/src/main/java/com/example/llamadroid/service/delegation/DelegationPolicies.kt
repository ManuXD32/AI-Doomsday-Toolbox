package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.NativeChatDelegationWakeKind

/** Alarm limits and the exact-vs-inexact decision for `deepseek_schedule_check`. */
object DelegationAlarmPolicy {
    const val MIN_MINUTES = 2
    const val MAX_MINUTES = 1440
    const val MAX_ALARMS_PER_TASK = 96

    sealed class Decision {
        data object Cancel : Decision()
        data object Refused : Decision()
        data class Schedule(val minutes: Int, val fireAt: Long, val exact: Boolean) : Decision()
    }

    fun clampMinutes(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    fun fireAt(now: Long, minutes: Int): Long = now + clampMinutes(minutes) * 60_000L

    fun decide(minutes: Int, alarmCount: Int, canExact: Boolean, now: Long): Decision = when {
        minutes <= 0 -> Decision.Cancel
        alarmCount >= MAX_ALARMS_PER_TASK -> Decision.Refused
        else -> clampMinutes(minutes).let { Decision.Schedule(it, fireAt(now, it), canExact) }
    }

    /** Stable PendingIntent request code for a task's single alarm. */
    fun requestCode(taskId: Long): Int = 0x5D5000 xor (taskId.hashCode() and 0x000FFFFF)
}

/** Guards for [DelegationWakeDispatcher]: pacing, caps, coalescing and kind priority. */
object DelegationWakePolicy {
    const val MIN_INTERVAL_MS = 60_000L
    const val MAX_WAKES_PER_TASK = 60
    const val MAX_QUEUE_DEPTH = 8
    const val MAX_NOTICE_CHARS = 2_000
    private const val SEPARATOR = "\n\n"

    /** Milliseconds a chat must still wait before its next wake may be delivered (0 = ready). */
    fun waitMs(lastDeliveredAt: Long?, now: Long): Long {
        if (lastDeliveredAt == null) return 0L
        return (lastDeliveredAt + MIN_INTERVAL_MS - now).coerceIn(0L, MIN_INTERVAL_MS)
    }

    fun wakeCapReached(wakeCount: Int): Boolean = wakeCount >= MAX_WAKES_PER_TASK

    fun queueTooDeep(depth: Int): Boolean = depth > MAX_QUEUE_DEPTH

    /** Distinct notices joined into ONE row, bounded to [MAX_NOTICE_CHARS]. */
    fun coalesce(notices: List<String>): String {
        val joined = notices.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(SEPARATOR)
        if (joined.length <= MAX_NOTICE_CHARS) return joined
        return joined.take(MAX_NOTICE_CHARS - 1).trimEnd() + "…"
    }

    private fun priority(kind: String?): Int = when (kind) {
        NativeChatDelegationWakeKind.TASK_FINISHED -> 4
        NativeChatDelegationWakeKind.NEEDS_DIRECTION -> 3
        NativeChatDelegationWakeKind.SUBAGENT_QUESTION -> 2
        NativeChatDelegationWakeKind.SUBAGENT_REPLY -> 2
        NativeChatDelegationWakeKind.CHECK_ALARM -> 1
        else -> 0
    }

    /** The kind kept on the task row when [incoming] arrives while [existing] is still pending. */
    fun mergeKind(existing: String?, incoming: String): String =
        if (priority(existing) > priority(incoming)) existing!! else incoming

    /** Whether a notification linking to the chat accompanies this wake. */
    fun notifiesUser(kind: String): Boolean = kind == NativeChatDelegationWakeKind.TASK_FINISHED ||
        kind == NativeChatDelegationWakeKind.NEEDS_DIRECTION ||
        kind == NativeChatDelegationWakeKind.SUBAGENT_QUESTION
}

/** Model-facing notice text builders (English only). */
object DelegationNotices {
    const val TAG_CHECK = "[DeepSeek scheduled check]"
    const val TAG_FINISHED = "[DeepSeek task finished]"

    fun scheduledCheck(title: String, note: String?, cadenceMinutes: Int?): String = buildString {
        append(TAG_CHECK).append(" It is time to check on the DeepSeek task \"").append(title.trim().take(DelegationToolArgs.MAX_TITLE_CHARS)).append('"')
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { append(", note: ").append(it.take(DelegationToolArgs.MAX_NOTE_CHARS)) }
        append(". Call deepseek_task_status to get an update. If the task is still running afterwards, call deepseek_schedule_check again to set the next reminder")
        cadenceMinutes?.takeIf { it > 0 }?.let { append(" (last cadence: ").append(it).append(" minutes)") }
        append('.')
    }

    fun fallbackFor(kind: String): String = when (kind) {
        NativeChatDelegationWakeKind.TASK_FINISHED -> "$TAG_FINISHED Call deepseek_task_status to read the result."
        NativeChatDelegationWakeKind.NEEDS_DIRECTION -> "[DeepSeek task needs direction] Call deepseek_task_status, then decide how to continue."
        NativeChatDelegationWakeKind.SUBAGENT_QUESTION -> "[DeepSeek subagent question] The subagent asked a question. Call deepseek_task_status to read it."
        NativeChatDelegationWakeKind.SUBAGENT_REPLY -> "[DeepSeek subagent reply] The subagent replied. Call deepseek_task_status to read it."
        else -> "$TAG_CHECK Call deepseek_task_status to get an update."
    }

    fun startedText(): String =
        "DeepSeek task started. It runs in the background; you may keep talking to the user meanwhile. " +
            "Use deepseek_task_status to check progress, deepseek_task_message to talk to the subagent, and " +
            "deepseek_schedule_check to set a reminder (suggest 10-30 minutes). Never invent results; wait for the finished notice."
}
