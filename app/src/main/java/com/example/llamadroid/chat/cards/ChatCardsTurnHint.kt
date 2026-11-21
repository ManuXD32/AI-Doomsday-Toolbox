package com.example.llamadroid.chat.cards

/**
 * One-turn reminder for mini-app requests: small models otherwise answer "make me a calculator" with a tool call
 * (often generate_image) instead of an omni card. Measured on gemma-4-E4B (1.00015): card rate 9/18 -> 12/18 and
 * tool calls 4 -> 0. Only fires when the message asks to make/give something app-like, so explanations stay plain.
 */
object ChatCardsTurnHint {
    const val TEXT = "[App note: interactive cards are on. A calculator, converter, grapher, flashcards, tracker, timer, checklist, " +
        "dice or other small app the user asks for is ONE omni card written in your reply, never a tool call, a note or an image.]"

    private val ASK = Regex(
        "\\b(make|build|create|give|need|want|show|set up|hazme|haz|crea|créame|dame|necesito|quiero|pon|monta)\\b",
        RegexOption.IGNORE_CASE
    )
    private val THING = Regex(
        // No bare "app"/"tip": "build an Android app" or "give me a tip" must stay normal answers.
        "\\b(calculator|converter|timer|countdown|stopwatch|checklist|check list|to-?do list|dice|die roller|counter(?!-)|" +
            "score ?keeper|bill splitter|mini-?app|calculadora|conversor|convertidor|temporizador|" +
            "cuenta atrás|cronómetro|cronometro|lista de tareas|dados|contador|marcador|" +
            "flash ?cards?|tracker|scoreboard|interval timer|hiit|pomodoro|grapher|plotter|function explorer|matrix|matrices|" +
            "tarjetas de estudio|tarjetas didácticas|graficadora?|matriz)\\b",
        RegexOption.IGNORE_CASE
    )

    fun forTurn(cardsEnabled: Boolean, enabledTypes: Set<ChatCardType>, userText: String): String? {
        if (!cardsEnabled || ChatCardType.OMNI !in enabledTypes) return null
        val text = userText.take(2000)
        return TEXT.takeIf { ASK.containsMatchIn(text) && THING.containsMatchIn(text) }
    }
}
