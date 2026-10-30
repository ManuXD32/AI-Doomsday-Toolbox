package com.example.llamadroid.service.chat

/**
 * Texts of the messages the app injects into a native-chat tool turn. Each one is recorded as a
 * [NativeChatStepType.NOTE] step, so the live loop and every replay send it at the same position with
 * the same role and text. Constants only; deterministic.
 */
object NativeChatTurnNotes {
    /** An app message for the model: [role] "system" (normalised by the serializer) unless stated. */
    data class Note(val text: String, val role: String? = null, val imagePath: String? = null)

    const val SKIPPED_DUPLICATE = "Skipped: this exact call already ran in this turn; its result is above. Use it to answer."

    const val BLANK_ANSWER_RETRY =
        "The app already returned the requested tool results. Send the final visible answer now using those results. Do not call tools."

    const val ROUND_LIMIT =
        "The native chat tool round limit has been reached. Do not call more tools. Write a short progress note for the user: what is done, what is still missing, and that they can press Continue to keep going. Use only the tool results already provided. If the tool results include source_citations or KB citation links, cite sourced claims with those exact Markdown links."

    const val LITERT_FINALIZE =
        "The app has returned the requested tool result above. If it is enough to answer, send the final visible answer now instead of repeating the same tool call. Call another tool only if the provided result is clearly insufficient."

    const val DEEP_RESEARCH_DONE =
        "Deep Research has finished. Do not call deep_research again for the same request. Send a visible chat response now. Briefly say the research KB is ready and mention the imported source count from the tool result. If the user asked for factual conclusions, use kb_search or kb_read_source on the selected KB first and cite exact KB links."

    fun duplicateCall(toolName: String) = Note(
        "The tool $toolName was already called with the same arguments and its result is above. Do not repeat that call. Use the existing result to answer now."
    )

    fun mutatingLimit(toolName: String) = Note(
        "The app already completed $toolName. Do not call it again in this turn. Send the final visible answer now using the completed result."
    )
}
