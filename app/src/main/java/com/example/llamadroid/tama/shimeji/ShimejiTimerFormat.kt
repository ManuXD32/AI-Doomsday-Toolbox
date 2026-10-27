package com.example.llamadroid.tama.shimeji

import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.TamaStudyPhase
import java.util.Locale

enum class ShimejiTimerKind { SLEEP, STUDY, POMODORO, WORK, RELAX, TRAINING }

/** Active study session summary: [phaseEndsAt] is null for normal (open ended) sessions. */
data class ShimejiStudyInfo(
    val pomodoro: Boolean,
    val phase: TamaStudyPhase,
    val startedAt: Long,
    val phaseEndsAt: Long?
)

/** What the timer badge / notification shows for the pet right now. */
data class ShimejiTimerState(
    val kind: ShimejiTimerKind,
    val startedAt: Long,
    val phaseEndsAt: Long? = null,
    val phase: TamaStudyPhase? = null,
    val energyPercent: Int = 0
)

/** Already localized words used by [ShimejiTimerFormat.badgeText]. */
data class ShimejiTimerLabels(
    val focus: String,
    val shortRest: String,
    val longRest: String,
    val study: String,
    val work: String,
    val relax: String,
    val training: String
)

/** Pure formatting and mapping helpers for the shimeji timer badge (no Android dependencies). */
object ShimejiTimerFormat {
    const val SLEEP_MARK = "Zz"

    /** mm:ss, or h:mm:ss from one hour on. Negative values clamp to zero. */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    /** Maps the pet's persisted state to a timer, or null when nothing is running. Sleep wins over activities. */
    fun timerState(
        isSleeping: Boolean,
        sleepStartTime: Long?,
        activity: ActivityType,
        activityStartTime: Long?,
        study: ShimejiStudyInfo?,
        energy: Float,
        now: Long
    ): ShimejiTimerState? {
        if (isSleeping) {
            return ShimejiTimerState(
                ShimejiTimerKind.SLEEP,
                sleepStartTime ?: now,
                energyPercent = energy.coerceIn(0f, 100f).toInt()
            )
        }
        return when (activity) {
            ActivityType.NONE -> null
            ActivityType.STUDYING -> if (study != null) {
                ShimejiTimerState(
                    if (study.pomodoro) ShimejiTimerKind.POMODORO else ShimejiTimerKind.STUDY,
                    study.startedAt,
                    phaseEndsAt = study.phaseEndsAt,
                    phase = study.phase
                )
            } else {
                ShimejiTimerState(ShimejiTimerKind.STUDY, activityStartTime ?: now)
            }
            ActivityType.WORKING -> ShimejiTimerState(ShimejiTimerKind.WORK, activityStartTime ?: now)
            ActivityType.TRAINING -> ShimejiTimerState(ShimejiTimerKind.TRAINING, activityStartTime ?: now)
            ActivityType.RELAXING -> ShimejiTimerState(ShimejiTimerKind.RELAX, activityStartTime ?: now)
        }
    }

    /** Badge text, for example "Focus 12:34", "Study 05:10" or "Zz 1:02:03 63%". */
    fun badgeText(state: ShimejiTimerState, now: Long, labels: ShimejiTimerLabels): String = when (state.kind) {
        ShimejiTimerKind.SLEEP ->
            "$SLEEP_MARK ${clock(now - state.startedAt)} ${state.energyPercent}%"
        ShimejiTimerKind.POMODORO -> {
            val label = when (state.phase) {
                TamaStudyPhase.SHORT_REST -> labels.shortRest
                TamaStudyPhase.LONG_REST -> labels.longRest
                else -> labels.focus
            }
            val end = state.phaseEndsAt
            "$label ${clock(if (end != null) end - now else now - state.startedAt)}"
        }
        ShimejiTimerKind.STUDY -> "${labels.study} ${clock(now - state.startedAt)}"
        ShimejiTimerKind.WORK -> "${labels.work} ${clock(now - state.startedAt)}"
        ShimejiTimerKind.RELAX -> "${labels.relax} ${clock(now - state.startedAt)}"
        ShimejiTimerKind.TRAINING -> "${labels.training} ${clock(now - state.startedAt)}"
    }

    /** True when the pet should stay seated instead of wandering (activity running, not asleep). */
    fun isOccupied(isSleeping: Boolean, activity: ActivityType): Boolean =
        !isSleeping && activity != ActivityType.NONE
}
