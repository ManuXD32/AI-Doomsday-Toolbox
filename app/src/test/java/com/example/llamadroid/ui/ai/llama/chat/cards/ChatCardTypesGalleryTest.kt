package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.llamadroid.chat.cards.ChatCardType
import com.example.llamadroid.chat.cards.ChatCardsAwareness
import com.example.llamadroid.chat.cards.ChatCardsDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class ChatCardTypesGalleryTest {
    @get:Rule
    val rule = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @After
    fun reset() = ChatCardsDefaults.setDisabledTypes(context, emptySet())

    @Test
    fun disabledSetRoundTripsAndDefaultsToNone() {
        ChatCardsDefaults.setDisabledTypes(context, emptySet())
        assertEquals(emptySet<ChatCardType>(), ChatCardsDefaults.disabledTypes(context))
        ChatCardsDefaults.setDisabledTypes(context, setOf(ChatCardType.MAP, ChatCardType.CHART))
        assertEquals(setOf(ChatCardType.MAP, ChatCardType.CHART), ChatCardsDefaults.disabledTypes(context))
        assertEquals(ChatCardType.entries.toSet() - setOf(ChatCardType.MAP, ChatCardType.CHART), ChatCardsDefaults.currentEnabledTypes())
        assertFalse(ChatCardsAwareness.current().contains("- map:"))
        assertTrue(ChatCardsAwareness.current().contains("- weather:"))
    }

    @Test
    fun galleryShowsSwitchesThatWriteTheStoredSet() {
        ChatCardsDefaults.setDisabledTypes(context, emptySet())
        rule.setContent { MaterialTheme { ChatCardTypesGallery(onClose = {}) } }
        rule.onNodeWithTag(chatCardTypeSwitchTag(ChatCardType.CHOICES)).assertIsOn().performClick()
        rule.onNodeWithTag(chatCardTypeSwitchTag(ChatCardType.CHOICES)).assertIsOff()
        assertEquals(setOf(ChatCardType.CHOICES), ChatCardsDefaults.disabledTypes(context))
        // Charts are far down the list: the gallery must scroll to them.
        rule.onNodeWithTag(CHAT_CARD_TYPES_LIST_TAG).performScrollToNode(hasTestTag(chatCardTypeSwitchTag(ChatCardType.CHART)))
        rule.onNodeWithTag(chatCardTypeSwitchTag(ChatCardType.CHART)).assertIsOn().performClick()
        assertEquals(setOf(ChatCardType.CHOICES, ChatCardType.CHART), ChatCardsDefaults.disabledTypes(context))
        rule.onNodeWithTag(chatCardTypeSwitchTag(ChatCardType.CHART)).performClick().assertIsOn()
        assertEquals(setOf(ChatCardType.CHOICES), ChatCardsDefaults.disabledTypes(context))
    }
}
