package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.ui.ai.llama.MarkdownText
import com.example.llamadroid.ui.ai.llama.chat.LlamaMessageItem
import com.example.llamadroid.ui.ai.llama.markdownParseCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class ChatCardsRenderingTest {
    @get:Rule
    val rule = createComposeRule()

    private val choicesReply = "Pick one.\n```card\n{\"type\":\"choices\",\"question\":\"Which colour?\"," +
        "\"options\":[\"Red\",\"Blue\"]}\n```\n"

    private fun message(content: String) = LlamaMessageEntity(id = 9L, chatId = 5L, role = "assistant", content = content)

    @Test
    fun tapOptionThenSendSubmitsAnswerInRealBubble() {
        val sent = mutableListOf<Triple<Long, Int, String>>()
        val cards = ChatCardsUi(emptyMap(), submitEnabled = true) { id, idx, text, _ -> sent += Triple(id, idx, text) }
        rule.setContent {
            MaterialTheme {
                LlamaMessageItem(
                    message = message(choicesReply),
                    onRegenerate = {}, onEdit = {}, onRetry = {}, retryEnabled = true,
                    onRetryTranscription = {}, onDiscardFailedMessage = {}, onDelete = {},
                    cards = cards,
                )
            }
        }
        rule.onNodeWithText("Which colour?").assertIsDisplayed()
        rule.onNodeWithText("Blue").performClick()
        rule.onNodeWithText("Send").assertIsEnabled().performClick()
        assertEquals(listOf(Triple(9L, 0, "Which colour?: Blue")), sent)
    }

    private val twoQuestionsReply = "Two questions.\n" +
        "```card\n{\"type\":\"choices\",\"question\":\"Which colour?\",\"options\":[\"Red\",\"Blue\"]}\n```\n" +
        "```card\n{\"type\":\"choices\",\"question\":\"Which size?\",\"options\":[\"Small\",\"Large\"]}\n```\n"

    @Test
    fun twoChoicesCardsSendOneCombinedMessage() {
        val single = mutableListOf<String>()
        val batches = mutableListOf<Triple<Long, List<ChatCardBatchAnswer>, String>>()
        val cards = ChatCardsUi(
            emptyMap(), submitEnabled = true,
            onSubmitBatch = { id, answers, text -> batches += Triple(id, answers, text) }
        ) { _, _, text, _ -> single += text }
        rule.setContent {
            MaterialTheme {
                LlamaMessageItem(
                    message = message(twoQuestionsReply),
                    onRegenerate = {}, onEdit = {}, onRetry = {}, retryEnabled = true,
                    onRetryTranscription = {}, onDiscardFailedMessage = {}, onDelete = {},
                    cards = cards,
                )
            }
        }
        // Individual Send buttons are hidden; the single "Send answers" button waits for every question.
        rule.onAllNodesWithText("Send").assertCountEquals(0)
        rule.onNodeWithText("Send answers").assertIsNotEnabled()
        rule.onNodeWithText("Red").performClick()
        rule.onNodeWithText("Send answers").assertIsNotEnabled()
        rule.onNodeWithText("Large").performClick()
        rule.onNodeWithText("Send answers").assertIsEnabled().performClick()
        assertEquals(1, batches.size)
        assertEquals(9L, batches[0].first)
        assertEquals(listOf(0, 1), batches[0].second.map { it.cardIndex })
        assertEquals("Which colour?: Red\n\nWhich size?: Large", batches[0].third)
        assertTrue(single.isEmpty())
    }

    @Test
    fun markdownAndBatchAgreeOnCardFencesIncludingJsonFences() {
        val c1 = "{\"type\":\"choices\",\"question\":\"First?\",\"options\":[\"a\",\"b\"]}"
        val c2 = "{\"type\":\"form\",\"title\":\"Second\",\"fields\":[{\"id\":\"d\",\"label\":\"Date\",\"kind\":\"date\"}]}"
        val content = "```card\n$c1\n```\n```json\n{\"type\":\"object\"}\n```\n```json\n$c2\n```\n```python\nprint(1)\n```\n"
        val seen = mutableListOf<Pair<Int, ChatCard>>()
        rule.setContent {
            MaterialTheme { MarkdownText(text = content, textColor = Color.White, cardContent = { i, card -> seen += i to card }) }
        }
        rule.waitForIdle()
        assertEquals(listOf(0, 1), seen.map { it.first }.distinct())
        assertTrue(seen.first { it.first == 0 }.second is ChatCard.Choices)
        assertTrue(seen.first { it.first == 1 }.second is ChatCard.Form)
        assertEquals(listOf(c1, c2), cardFenceCodes(content))
        assertEquals(listOf(0, 1), batchMembers(content, emptyMap()))
    }

    @Test
    fun batchMembersListsOnlyUnansweredInteractiveCards() {
        val content = "a\n```card\n{\"type\":\"choices\",\"question\":\"Q1\",\"options\":[\"A\",\"B\"]}\n```\n" +
            "```card\n{\"type\":\"callout\",\"tone\":\"info\",\"text\":\"hi\"}\n```\n" +
            "```card\n{\"type\":\"choices\",\"question\":\"Q2\",\"options\":[\"A\",\"B\"]}\n```\n"
        assertEquals(listOf(0, 2), batchMembers(content, emptyMap()))
        assertEquals(listOf(2), batchMembers(content, mapOf(0 to "{}")))
    }

    @Test
    fun inputCardsJoinTheBatch() {
        val content = "```card\n{\"type\":\"input\",\"question\":\"Name?\"}\n```\n" +
            "```card\n{\"type\":\"choices\",\"question\":\"Q\",\"options\":[\"A\",\"B\"]}\n```\n"
        assertEquals(listOf(0, 1), batchMembers(content, emptyMap()))
        assertEquals(listOf(1), batchMembers(content, mapOf(0 to "{}")))
    }

    @Test
    fun cardsOffKeepsFenceAsCodeBlock() {
        rule.setContent {
            MaterialTheme {
                LlamaMessageItem(
                    message = message(choicesReply),
                    onRegenerate = {}, onEdit = {}, onRetry = {}, retryEnabled = true,
                    onRetryTranscription = {}, onDiscardFailedMessage = {}, onDelete = {},
                )
            }
        }
        rule.onNodeWithText("Send").let { runCatching { it.assertIsDisplayed() }.let { r -> assertTrue(r.isFailure) } }
    }

    @Test
    fun answeredCardIsReadOnly() {
        val state = com.example.llamadroid.chat.cards.ChatCardState.encodeChoices(listOf("Red"), null)
        val cards = ChatCardsUi(mapOf(9L to mapOf(0 to state)), submitEnabled = true) { _, _, _, _ -> }
        rule.setContent {
            MaterialTheme {
                LlamaMessageItem(
                    message = message(choicesReply),
                    onRegenerate = {}, onEdit = {}, onRetry = {}, retryEnabled = true,
                    onRetryTranscription = {}, onDiscardFailedMessage = {}, onDelete = {},
                    cards = cards,
                )
            }
        }
        rule.onNodeWithText("Answered").assertIsDisplayed()
    }

    @Test
    fun recompositionDoesNotReparseMarkdown() {
        val tick = mutableStateOf(0)
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth()) {
                    // The colour changes every tick, so MarkdownText itself recomposes with identical text.
                    MarkdownText(
                        text = "Hello **world**\n\n```kotlin\nval a = 1\n```",
                        textColor = Color(red = (tick.value % 2).toFloat(), green = 0f, blue = 0f)
                    )
                }
            }
        }
        rule.waitForIdle()
        val before = markdownParseCount.get()
        repeat(3) { tick.value++; rule.waitForIdle() }
        assertEquals(before, markdownParseCount.get())
    }
}
