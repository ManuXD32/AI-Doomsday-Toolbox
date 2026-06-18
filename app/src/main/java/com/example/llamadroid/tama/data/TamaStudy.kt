package com.example.llamadroid.tama.data

import kotlinx.serialization.Serializable

const val TAMA_STUDY_EDUCATION_PER_HOUR = 5f
const val TAMA_STUDY_MAX_REWARD_MS = 8L * 60L * 60L * 1000L

@Serializable
enum class TamaStudyMode {
    NORMAL,
    POMODORO
}

@Serializable
enum class TamaStudyStatus {
    ACTIVE,
    COMPLETED,
    STOPPED
}

@Serializable
enum class TamaStudyPhase {
    FOCUS,
    SHORT_REST,
    LONG_REST
}

data class TamaPomodoroSettings(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val rounds: Int = 4
) {
    fun normalized(): TamaPomodoroSettings = copy(
        focusMinutes = focusMinutes.coerceIn(1, 240),
        shortBreakMinutes = shortBreakMinutes.coerceIn(1, 120),
        longBreakMinutes = longBreakMinutes.coerceIn(1, 180),
        rounds = rounds.coerceIn(1, 12)
    )
}

fun GrowthStage.canStudy(): Boolean = when (this) {
    GrowthStage.EGG,
    GrowthStage.CHILD,
    GrowthStage.TEEN,
    GrowthStage.ADULT,
    GrowthStage.SENIOR -> true
    GrowthStage.BABY -> false
}
