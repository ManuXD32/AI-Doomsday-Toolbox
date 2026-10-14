package com.example.llamadroid.service.delegation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatDelegationHintsTest {
    private fun auto(text: String) = NativeChatDelegationHints.looksLikeCodingWork(text, DelegationMode.AUTO)
    private fun prefer(text: String) = NativeChatDelegationHints.looksLikeCodingWork(text, DelegationMode.PREFER)

    @Test
    fun englishCodingRequestsMatchInAuto() {
        assertTrue(auto("Write a Python script that renames my files"))
        assertTrue(auto("Create a website for my bakery"))
        assertTrue(auto("Please fix the bug in the login screen"))
        assertTrue(auto("main.py keeps crashing on start"))
        assertTrue(auto("Can you debug this for me?"))
        assertTrue(auto("build me a snake game"))
    }

    @Test
    fun spanishCodingRequestsMatchInAuto() {
        assertTrue(auto("Crea una aplicación para llevar mis gastos"))
        assertTrue(auto("Necesito programar un bot de Telegram"))
        assertTrue(auto("Depura este error por favor"))
        assertTrue(auto("Arregla el bug del formulario"))
        assertTrue(auto("Escribe el código de un servidor sencillo"))
        assertTrue(auto("Convierte el archivo datos.csv a JSON"))
    }

    @Test
    fun smallTalkDoesNotMatch() {
        assertFalse(auto("What is the capital of France?"))
        assertFalse(auto("Hola, ¿cómo estás hoy?"))
        assertFalse(auto("Tell me a joke about cats"))
        assertFalse(auto("Explica qué es la fotosíntesis"))
        assertFalse(auto("I want to make an apple pie"))
    }

    @Test
    fun shortMessagesNeverMatch() {
        assertFalse(auto("debug"))
        assertFalse(prefer("code?"))
        assertNull(NativeChatDelegationHints.hintFor("ok thanks", DelegationMode.PREFER))
    }

    @Test
    fun preferModeIsBroader() {
        val text = "I really like this app a lot"
        assertFalse(auto(text))
        assertTrue(prefer(text))
        assertFalse(auto("Explica qué es un proyecto"))
        assertTrue(prefer("Explica qué es un proyecto"))
    }

    @Test
    fun onlyWhenAskedNeverHints() {
        assertFalse(NativeChatDelegationHints.looksLikeCodingWork("Write a Python script", DelegationMode.ONLY_WHEN_ASKED))
        assertNull(NativeChatDelegationHints.hintFor("Crea una aplicación completa", DelegationMode.ONLY_WHEN_ASKED))
    }

    @Test
    fun hintTextDependsOnMode() {
        assertEquals(NativeChatDelegationHints.HINT_AUTO, NativeChatDelegationHints.hintFor("Write a Python script", DelegationMode.AUTO))
        assertEquals(NativeChatDelegationHints.HINT_PREFER, NativeChatDelegationHints.hintFor("Write a Python script", DelegationMode.PREFER))
        assertNotNull(NativeChatDelegationHints.hintFor("Build an app for me", DelegationMode.AUTO))
    }

    @Test
    fun configRoundTripKeepsMode() {
        val config = NativeChatDelegationConfig(enabled = true, mode = DelegationMode.PREFER)
        assertEquals(DelegationMode.PREFER, NativeChatDelegationConfig.fromParams(config.toParamMap()).mode)
        val ask = config.copy(mode = DelegationMode.ONLY_WHEN_ASKED)
        assertEquals(DelegationMode.ONLY_WHEN_ASKED, NativeChatDelegationConfig.fromParams(ask.toParamMap()).mode)
    }

    @Test
    fun oldOrUnknownJsonDefaultsToAuto() {
        assertEquals(DelegationMode.AUTO, NativeChatDelegationConfig.fromParams(mapOf("tool_deepseek_enabled" to true)).mode)
        assertEquals(
            DelegationMode.AUTO,
            NativeChatDelegationConfig.fromParams(mapOf(NativeChatDelegationConfig.KEY_MODE to "garbage")).mode
        )
    }
}
