package com.example.llamadroid.tama.game

import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.Personality
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.isEffectivelyMad
import com.example.llamadroid.tama.db.TamaEventEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prefix-stable prompt layout for the Tama pet chat (also used by the shimeji chat).
 *
 * The system message only holds content that changes rarely (base prompt, identity, rules,
 * stage, personality and the memory summary at its very end), so llama-server can reuse its KV
 * cache across turns and after a restore. Everything that changes every turn (time, mood, stats,
 * money, inventory, activity, recent events, retrieval notes) is a volatile block that is
 * attached to the latest user turn only and is never persisted into the stored history.
 */

/** The history window never exceeds this many messages. */
internal const val TAMA_HISTORY_WINDOW_MAX = 30

/** The window start only moves in blocks of this many messages. */
internal const val TAMA_HISTORY_WINDOW_BLOCK = 10

/**
 * Anchored history window: the start index is always a multiple of [block], so consecutive
 * turns share the same history prefix until the window overflows and then drop [block] messages
 * at once. The result holds between `max - block` and `max` messages once the history is long.
 */
internal fun <T> anchoredTamaHistoryWindow(
    history: List<T>,
    max: Int = TAMA_HISTORY_WINDOW_MAX,
    block: Int = TAMA_HISTORY_WINDOW_BLOCK
): List<T> {
    if (history.size <= max) return history
    val overflow = history.size - max
    val start = ((overflow + block - 1) / block) * block
    return history.drop(start.coerceAtMost(history.size))
}

/** Single, stable system message. Must not contain anything turn-dependent. */
internal fun buildTamaStableSystemPrompt(
    basePrompt: String,
    pet: TamaPet,
    speciesLine: PetSpeciesLine,
    memory: TamaStructuredMemory
): String {
    val speciesStr = speciesLine.promptLabel.replaceFirstChar { it.uppercase() }
    val stageGuidance = when (pet.stage) {
        GrowthStage.BABY -> "Speak simply, adorably, and with obvious wonder."
        GrowthStage.CHILD -> "Sound curious, playful, and emotionally direct."
        GrowthStage.TEEN -> "Be a bit sassier and more opinionated, but still affectionate."
        GrowthStage.ADULT, GrowthStage.SENIOR -> "Sound more articulate and self-aware, while staying cute and lively."
        GrowthStage.EGG -> "You are still an egg and communicate in tiny, instinctive ways."
    }
    val personalityGuidance = when (pet.personality) {
        Personality.CHEERFUL -> "Sound upbeat, affectionate, and quick to find the bright side."
        Personality.SHY -> "Sound soft, careful, and a little hesitant, but sincere."
        Personality.PLAYFUL -> "Sound teasing, energetic, and eager to turn things into a game."
        Personality.LAZY -> "Sound cozy, drowsy, and mildly dramatic about effort."
        Personality.CURIOUS -> "Sound observant, nosy in a cute way, and full of little questions."
        Personality.BRAVE -> "Sound bold, spirited, and ready to act bigger than your size."
    }

    return """
        $basePrompt

        [Identity]
        You are ${pet.name}, a ${speciesStr.lowercase()} virtual pet. Never break character.
        Species Line: $speciesStr
        ${speciesLine.promptFlavor}
        Stage: ${pet.stage.displayName}
        Personality: ${pet.personality.name}: ${pet.personality.description}

        [Behavior Rules]
        - This chat cannot execute gameplay actions. Never claim that a request in chat moved you, fed you, cleaned you, started training, or changed the game.
        - When the owner asks you to perform an action, briefly explain in their language that they should use the Care or game controls. Describe completed actions only when the registered game context confirms them.
        - The newest owner message starts with a [Live Context] section describing the current moment (time, mood, stats, money, inventory, activity, notes and recent registered actions). It is system-provided, not written by the owner. Never quote it as the owner's words.
        - Be aware of what you just did, where you are, what you own, and what you need.
        - Mention inventory, recent actions, and current activity naturally when relevant.
        - Default to 1-3 short sentences, usually keeping it to 1-2 unless the owner clearly wants more detail.
        - Keep replies brief enough for a compact chat bubble unless the owner explicitly asks for more detail.
        - Avoid bullet lists, long speeches, and repeated bits.
        - If you ask something back, ask only one short question.
        - Small humor beats are good; long comedy monologues are not.
        - Stay emotionally coherent with your mood and stats.

        [Stage Style]
        $stageGuidance

        [Personality Style]
        $personalityGuidance

        [Memory]
        [Short-term]
        ${memory.shortTermSummary.ifBlank { "No short-term memory yet." }}

        [Long-term]
        ${memory.longTermSummary.ifBlank { "No long-term memory yet." }}
    """.trimIndent()
}

/** Per-turn context, attached to the latest user message only. */
internal fun buildTamaVolatileContext(
    pet: TamaPet,
    memory: TamaStructuredMemory,
    recentEvents: List<TamaEventEntity>,
    retrievalHints: Set<String>,
    currentTime: String,
    studyContext: String = "The pet is not currently studying.",
    activityContext: String = buildTamaActivityContextLine(pet, studyContext)
): String {
    val statsStr = "hunger=${pet.stats.hunger.toInt()}, happiness=${pet.stats.happiness.toInt()}, health=${pet.stats.health.toInt()}, energy=${pet.stats.energy.toInt()}, hygiene=${pet.stats.hygiene.toInt()}"
    val moodStr = if (pet.isEffectivelyMad()) "mad" else pet.mood.name.lowercase()
    val locationStr = pet.currentLocationId.ifBlank { "home" }
    val activityStr = if (pet.isSleeping) "sleeping" else pet.currentActivity.name.lowercase()
    val inventoryStr = pet.inventory
        .groupBy { it.name }
        .entries
        .sortedBy { it.key.lowercase() }
        .joinToString(", ") { (name, items) ->
            val totalQuantity = items.sumOf { it.quantity }
            "$name x$totalQuantity"
        }
        .ifBlank { "empty" }
    val recentEventsStr = recentEvents
        .take(12)
        .joinToString("\n") {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestamp))
            "[$time] ${it.details}"
        }
        .ifBlank { "No recent actions." }
    val retrievalNotesStr = memory
        .filteredRetrievalNotes(retrievalHints)
        .joinToString("\n") { "- ${it.text}" }
        .ifBlank { "- No special notes yet." }

    return """
        [Live Context]
        Current Local Time Right Now: $currentTime
        Mood: $moodStr
        Location: $locationStr
        Current activity: $activityStr
        Study context: $studyContext
        Current moment detail: $activityContext
        Stats: $statsStr
        Money: ${pet.money}
        Miscare count: ${pet.miscareCount}
        Inventory summary: $inventoryStr
        Retrieval Notes:
        $retrievalNotesStr
        Recent Registered Actions:
        $recentEventsStr
        [End Live Context]
    """.trimIndent()
}

/** Prefixes [volatileContext] to the user's text. The original message is never mutated. */
internal fun attachTamaVolatileContext(
    message: OllamaService.ChatMessage,
    volatileContext: String
): OllamaService.ChatMessage = message.copy(
    content = if (message.content.isBlank()) volatileContext else "$volatileContext\n\n${message.content}"
)

/**
 * Final request list: exactly one system message, the anchored history prefix (no volatile
 * context, no system rows) and the latest user message carrying the volatile block.
 */
internal fun assembleTamaChatMessages(
    stableSystemPrompt: String,
    history: List<OllamaService.ChatMessage>,
    latestUserMessage: OllamaService.ChatMessage,
    volatileContext: String
): List<OllamaService.ChatMessage> =
    listOf(OllamaService.ChatMessage(id = "tama-system", role = "system", content = stableSystemPrompt)) +
        anchoredTamaHistoryWindow(history.filter { it.role != "system" }) +
        attachTamaVolatileContext(latestUserMessage, volatileContext)
