package com.example.llamadroid.service

import java.time.Instant
import java.time.OffsetDateTime

object AgentSleepWakeTime {
    const val MIN_DELAY_SECONDS = 10L
    const val MAX_DELAY_SECONDS = 7L * 24L * 60L * 60L

    fun resolveEpochMillis(arguments: Map<String, String>, nowEpochMs: Long): Long {
        val timestamp = arguments["wake_at"]?.trim().orEmpty()
        val delayText = arguments["delay_seconds"]?.trim().orEmpty()
        require((timestamp.isBlank()) xor (delayText.isBlank())) {
            "Provide exactly one of wake_at or delay_seconds."
        }
        val wakeAt = if (delayText.isNotBlank()) {
            val delay = delayText.toLongOrNull()
                ?: throw IllegalArgumentException("delay_seconds must be a whole number.")
            require(delay in MIN_DELAY_SECONDS..MAX_DELAY_SECONDS) {
                "delay_seconds must be between $MIN_DELAY_SECONDS and $MAX_DELAY_SECONDS."
            }
            nowEpochMs + delay * 1_000L
        } else {
            runCatching { Instant.parse(timestamp).toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(timestamp).toInstant().toEpochMilli() }
                .getOrElse { throw IllegalArgumentException("wake_at must be an ISO-8601 timestamp with a timezone.") }
        }
        val delayMs = wakeAt - nowEpochMs
        require(delayMs in (MIN_DELAY_SECONDS * 1_000L)..(MAX_DELAY_SECONDS * 1_000L)) {
            "The wake time must be between 10 seconds and 7 days from now."
        }
        return wakeAt
    }
}
