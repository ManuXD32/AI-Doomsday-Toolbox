package com.example.llamadroid.service

/** Durations emitted by stable-diffusion.cpp's structured stage log messages. */
data class SdStageTimings(
    val conditioningMs: Long? = null,
    val samplingMs: Long? = null,
    val decodingMs: Long? = null
) {
    fun withLine(line: String): SdStageTimings {
        val seconds = DURATION.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return this
        val millis = (seconds * 1_000.0).toLong().coerceAtLeast(0L)
        val normalized = line.lowercase()
        return when {
            "condition" in normalized || "learned_condition" in normalized -> copy(conditioningMs = millis)
            "sample" in normalized -> copy(samplingMs = (samplingMs ?: 0L) + millis)
            "decode" in normalized || "vae" in normalized && "completed" in normalized -> copy(decodingMs = millis)
            else -> this
        }
    }

    companion object {
        private val DURATION = Regex("""(?:taking|in)\\s+([0-9]+(?:\\.[0-9]+)?)\\s*s(?:ec(?:onds?)?)?""", RegexOption.IGNORE_CASE)
    }
}
