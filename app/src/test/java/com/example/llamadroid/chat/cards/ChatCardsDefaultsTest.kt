package com.example.llamadroid.chat.cards

import org.robolectric.RuntimeEnvironment
import com.example.llamadroid.service.NativeChatToolConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatCardsDefaultsTest {
    private fun cards(params: String?) = NativeChatToolConfig.fromApiParams(params).cardsEnabled

    @Test
    fun defaultOffLeavesNewChatParamsUntouched() {
        assertNull(ChatCardsDefaults.applyToNewChatParams(null, false))
        assertEquals("{\"tools\":true}", ChatCardsDefaults.applyToNewChatParams("{\"tools\":true}", false))
        assertFalse(cards(ChatCardsDefaults.applyToNewChatParams(null, false)))
    }

    @Test
    fun defaultOnEnablesCardsForNewChats() {
        assertTrue(cards(ChatCardsDefaults.applyToNewChatParams(null, true)))
        val merged = ChatCardsDefaults.applyToNewChatParams("{\"tools\":true}", true)
        assertTrue(cards(merged))
        assertTrue(JSONObject(requireNotNull(merged)).getBoolean("tools"))
    }

    @Test
    fun globalDefaultWinsOverSerializedServerDefaultFalse() {
        val off = "{\"chat_cards_enabled\":false}"
        assertTrue(cards(ChatCardsDefaults.applyToNewChatParams(off, true)))
        val on = "{\"chat_cards_enabled\":true}"
        assertEquals(on, ChatCardsDefaults.applyToNewChatParams(on, true))
        assertEquals(off, ChatCardsDefaults.applyToNewChatParams(off, false))
    }

    @Test
    fun omniIsOffByDefaultAndRespectsAnExplicitChoice() {
        // Nothing stored yet.
        assertEquals(setOf(ChatCardType.OMNI), ChatCardsDefaults.resolveDisabled(null, null))
        // A set saved before omni existed (no known types stored) must not turn omni on.
        assertEquals(setOf(ChatCardType.MAP, ChatCardType.OMNI), ChatCardsDefaults.resolveDisabled(setOf("map"), null))
        // After the user saved a set with every type known, omni on means it is simply not disabled.
        val known = ChatCardType.entries.map { it.key }.toSet()
        assertEquals(emptySet<ChatCardType>(), ChatCardsDefaults.resolveDisabled(emptySet(), known))
        assertEquals(setOf(ChatCardType.OMNI), ChatCardsDefaults.resolveDisabled(setOf("omni", "bogus"), known))
    }

    @Test
    fun disabledTypesDefaultsToOmniOffThenStoresTheChoice() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(setOf(ChatCardType.OMNI), ChatCardsDefaults.disabledTypes(context))
        ChatCardsDefaults.setDisabledTypes(context, emptySet())
        assertEquals(emptySet<ChatCardType>(), ChatCardsDefaults.disabledTypes(context))
        ChatCardsDefaults.setDisabledTypes(context, setOf(ChatCardType.OMNI, ChatCardType.MAP))
        assertEquals(setOf(ChatCardType.OMNI, ChatCardType.MAP), ChatCardsDefaults.disabledTypes(context))
    }

    @Test
    fun preferenceRoundTrip() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(ChatCardsDefaults.isDefaultOn(context))
        ChatCardsDefaults.setDefaultOn(context, true)
        assertTrue(ChatCardsDefaults.isDefaultOn(context))
        assertTrue(cards(ChatCardsDefaults.applyToNewChatParams(context, null)))
        ChatCardsDefaults.setDefaultOn(context, false)
        assertFalse(ChatCardsDefaults.isDefaultOn(context))
    }
}
