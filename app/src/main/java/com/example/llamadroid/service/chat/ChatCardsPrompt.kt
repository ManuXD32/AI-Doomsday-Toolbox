package com.example.llamadroid.service.chat

import com.example.llamadroid.chat.cards.ChatCardsAwareness
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.service.NativeChatToolConfig

/**
 * Prompt wiring for interactive chat cards.
 *
 * KV-cache rule: the awareness text only changes when the user edits the global card-type switches, so it is always the FIRST block of the system
 * message, followed by the chat's own system prompt. Every turn of a chat (and every chat with the
 * same toggle) therefore shares the same token prefix, and toggling the flag changes the prefix once.
 * One merged system message is used because many chat templates reject several system messages.
 * Tool chats get the same text from `NativeChatBasePrefix.stableText`, so this helper is only for
 * the plain (no native tools) paths.
 */
object ChatCardsPrompt {
    /** The system prompt with the cards awareness text in front, or [systemPrompt] unchanged when off. */
    fun systemPrompt(systemPrompt: String?, config: NativeChatToolConfig): String? {
        if (!config.cardsEnabled) return systemPrompt
        val own = systemPrompt?.trim().orEmpty()
        return ChatCardsAwareness.current().let { text -> if (own.isEmpty()) text else text + "\n\n" + own }
    }

    /** Copy of [chat] whose system prompt carries the cards text; [chat] itself when cards are off. */
    fun forPlainTurn(chat: LlamaChatEntity, config: NativeChatToolConfig): LlamaChatEntity =
        if (!config.cardsEnabled) chat else chat.copy(systemPrompt = systemPrompt(chat.systemPrompt, config))
}
