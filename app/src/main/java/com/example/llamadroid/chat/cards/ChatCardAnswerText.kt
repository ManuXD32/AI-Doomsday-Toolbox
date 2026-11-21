package com.example.llamadroid.chat.cards

/** Builds the plain-text user message sent when an interactive card is answered. No localized boilerplate. */
object ChatCardAnswerText {
    fun choices(card: ChatCard.Choices, selected: List<String>, other: String?): String {
        val parts = selected.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        other?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        return "${card.question.trim()}: ${parts.joinToString(", ")}"
    }

    /** "Question: answer"; blank when the answer is empty. */
    fun input(card: ChatCard.Input, answer: String): String {
        val text = answer.trim()
        return if (text.isEmpty()) "" else "${card.question.trim()}: $text"
    }

    fun form(card: ChatCard.Form, values: Map<String, String>): String =
        card.fields.mapNotNull { field ->
            val value = values[field.id]?.trim().orEmpty()
            if (value.isEmpty()) null else "${field.label.trim()}: $value"
        }.joinToString("\n")
}
