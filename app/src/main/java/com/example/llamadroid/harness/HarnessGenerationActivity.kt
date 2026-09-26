package com.example.llamadroid.harness

/**
 * Metadata-only status for one active Harness model request. Prompt/message
 * content is deliberately absent so a UI can render progress without keeping
 * a second transcript or diagnostic copy of the request.
 */
data class HarnessGenerationActivity(
    val requestId: String,
    val sessionId: String?,
    val attemptId: String,
    val model: String,
    val phase: String,
    val known: Boolean = false,
    val total: Int? = null,
    val cached: Int? = null,
    val processed: Int? = null,
    val timeMs: Long? = null,
    /** Wall-clock start time for display and correlation with native logs. */
    val startedAtMs: Long,
    /** Monotonic start time for elapsed-duration rendering. */
    val startedAtElapsedMs: Long,
    val updatedAtMs: Long,
) {
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (processed ?: 0).toFloat().div(it).coerceIn(0f, 1f) }
}
