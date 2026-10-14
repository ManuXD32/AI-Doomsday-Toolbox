package com.example.llamadroid.service.delegation

import org.json.JSONObject

/** Pure model-facing text for harness model-provider failures. English only. */
internal object DelegationProviderFailure {
    const val ERROR_CODE = "harness_model_error"
    private const val CONTEXT = "CONTEXT_WINDOW_EXCEEDED"

    /** Extra guidance appended to the harness_wait result after the first provider failure. */
    fun firstFailureHint(code: String, cause: String? = null): String = buildString {
        append("\nThe harness agent's model request failed with ").append(code).append(". ")
        cause?.takeIf { it.isNotBlank() }?.let { append("Cause: ").append(it.take(300)).append(' ') }
        append("Do not resend the same prompt more than once; if the same failure repeats the task will stop. ")
        if (code == CONTEXT) {
            append("The harness model's context window is too small for the harness agent's prompt; ")
            append("a shorter prompt will not fix it. Report this with finish_task(failed) or message_main if it happens again.")
        } else {
            append("Change your approach or call finish_task(failed) if the harness cannot run.")
        }
    }

    /** Plain-words diagnosis (also the progress note and the wake notice), at most 400 characters. */
    fun needsDirectionReason(code: String, contextTokens: Int?, cause: String? = null): String {
        val text = if (code == CONTEXT) {
            val n = contextTokens?.takeIf { it > 0 }?.let { "($it tokens per slot) " }.orEmpty()
            "The harness agent's model request failed twice with the context window exceeded: the harness model's context window " +
                n + "is too small for the harness agent. Use fewer parallel slots, turn on unified KV cache for that server, " +
                "or pick a server with a larger context."
        } else {
            "The harness agent's model request failed twice with $code. " + (cause?.takeIf { it.isNotBlank() }
                ?.let { "Cause: ${it.take(200)} " }
                ?: "The harness model server may be down, overloaded or rejecting requests; ") +
                "check the server selected for the harness agent."
        }
        return text.take(400)
    }

    /** Result text for a wait that ended because the harness follow stream was lost. */
    const val FOLLOW_LOST_HINT =
        "\nThe follow stream to the harness was lost (connection problem), not a model failure. " +
            "Call harness_wait again once; do not resend prompts."

    /** `contextTokens` from the task's `harnessModelJson`, when known. */
    fun contextTokensOf(harnessModelJson: String?): Int? {
        if (harnessModelJson.isNullOrBlank()) return null
        return runCatching { JSONObject(harnessModelJson).optInt("contextTokens", 0) }.getOrDefault(0).takeIf { it > 0 }
    }
}
