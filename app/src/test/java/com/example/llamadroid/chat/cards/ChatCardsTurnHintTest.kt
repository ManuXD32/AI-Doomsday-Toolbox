package com.example.llamadroid.chat.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatCardsTurnHintTest {
    private val all = ChatCardType.entries.toSet()
    private fun hint(text: String, types: Set<ChatCardType> = all, on: Boolean = true) = ChatCardsTurnHint.forTurn(on, types, text)

    @Test fun firesOnMiniAppRequests() {
        listOf(
            "Make me a calculator app using the card tools",
            "I need a tip calculator for our dinner bill",
            "Make a converter from kilometers to miles",
            "I want a 5 minute countdown timer here",
            "Make a little dice roller I can tap",
            "Hazme una calculadora de propinas",
            "Quiero un temporizador de 3 minutos",
            "Make flashcards to learn 8 Spanish verbs",
            "Make me a HIIT workout timer",
            "Hazme una calculadora de matrices",
        ).forEach { assertEquals(it, ChatCardsTurnHint.TEXT, hint(it)) }
    }

    @Test fun staysQuietOtherwise() {
        listOf(
            "Explain how a pocket calculator works inside",
            "Build an Android app in Kotlin with a login screen",
            "Give me a tip for studying",
            "Give me a counter-argument",
            "What is the capital of Australia?",
            // A quiz stays a choices card (asked one question at a time); no omni push.
            "Make me a quiz about the planets",
        ).forEach { assertNull(it, hint(it)) }
        assertNull(hint("Make me a calculator", all - ChatCardType.OMNI))
        assertNull(hint("Make me a calculator", on = false))
    }
}
