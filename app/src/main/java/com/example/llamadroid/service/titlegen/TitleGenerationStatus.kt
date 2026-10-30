package com.example.llamadroid.service.titlegen

/** Observable state of automatic title generation, shown in the settings card as plain text. */
sealed interface TitleGenerationStatus {
    data object Idle : TitleGenerationStatus
    data object Disabled : TitleGenerationStatus
    data object NoModel : TitleGenerationStatus
    data class WaitingForRam(val neededBytes: Long, val availableBytes: Long) : TitleGenerationStatus
    data class Generating(val chatTitle: String?) : TitleGenerationStatus
    data class Error(val message: String, val at: Long) : TitleGenerationStatus
}
