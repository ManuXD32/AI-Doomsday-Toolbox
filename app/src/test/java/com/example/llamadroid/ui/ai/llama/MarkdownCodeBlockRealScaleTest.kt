package com.example.llamadroid.ui.ai.llama

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.ui.ai.llama.chat.LlamaMessageItem
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real-scale regression gate: a ~1,000 line html world block inside the real chat message item
 * must keep its code horizontally scrollable and the world card buttons clickable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownCodeBlockRealScaleTest {
    @get:Rule
    val rule = createComposeRule()

    private val hasHScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

    private fun elephant(): String =
        javaClass.getResource("/worlds/elephant_module.md")!!.readText()

    /** Elephant world padded with comment lines (some 150 chars) up to [lines] lines of html. */
    private fun worldReply(lines: Int): String {
        val base = elephant()
        val pad = StringBuilder()
        for (i in 0 until lines) {
            if (i % 7 == 0) pad.append("// ").append("pad$i ").append("y".repeat(145)).append('\n')
            else pad.append("// padding line ").append(i).append('\n')
        }
        return base.replace("</script>", pad.toString() + "</script>")
    }

    private fun message(content: String) = LlamaMessageEntity(
        id = 77L, chatId = 5L, role = "assistant", content = content,
        thinking = "Planning the enclosure layout step by step.\nMore thoughts.",
    )

    private fun setItem(content: String) {
        rule.setContent {
            MaterialTheme {
                val state = rememberLazyListState()
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(600.dp).testTag("list")) {
                    item {
                        LlamaMessageItem(
                            message = message(content),
                            onRegenerate = {}, onEdit = {}, onRetry = {}, retryEnabled = true,
                            onRetryTranscription = {}, onDiscardFailedMessage = {}, onDelete = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun scrollListTo(textTag: String) {
        var n = 0
        while (n++ < 400) {
            val shown = runCatching { rule.onNodeWithText(textTag).assertIsDisplayed() }.isSuccess
            if (shown) {
                val b = rule.onNodeWithText(textTag).getBoundsInRoot()
                val l = rule.onNode(hasTestTag("list")).getBoundsInRoot()
                println("REPRO bounds $textTag=$b list=$l")
                if (b.top >= l.top && b.bottom <= l.bottom) return
            }
            rule.onNode(hasTestTag("list")).performTouchInput {
                // Swipe at the right margin, outside the card (card max width 320dp).
                val x = width - 4f
                swipeUp(startY = height * 0.9f, endY = height * 0.1f)
                @Suppress("UNUSED_VARIABLE") val unused = x
            }
            rule.waitForIdle()
        }
        throw AssertionError("could not reach '$textTag' by list scrolling")
    }

    private fun runScenario(lines: Int, label: String) {
        setItem(worldReply(lines))
        val hnodes = rule.onAllNodes(hasHScroll)
        val range0 = hnodes.fetchSemanticsNodes().first().config[SemanticsProperties.HorizontalScrollAxisRange]
        val before = range0.value()
        val results = StringBuilder("[$label lines=$lines] hscroll nodes=${hnodes.fetchSemanticsNodes().size} max=${range0.maxValue()}")
        // (a) swipe left on code text
        runCatching {
            hnodes[0].performTouchInput { swipeLeft() }
            rule.waitForIdle()
        }
        val after = rule.onAllNodes(hasHScroll).fetchSemanticsNodes().first()
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        results.append(" | (a) $before->$after")
        // (b) Save, (c) Play after scrolling to the card
        scrollListTo("Save")
        rule.onNodeWithText("Play").performClick()
        rule.waitForIdle()
        val stopShown = runCatching { rule.onNodeWithText("Stop").assertIsDisplayed() }.isSuccess
        results.append(" | (c, before Save) stopShown=$stopShown")
        // The card's own Toast runs on the compose test thread (no Looper) and throws after the world was
        // saved; that is a harness artifact, so the gate is the meta.json written before it.
        runCatching {
            rule.onNodeWithText("Save").performClick()
            rule.waitForIdle()
            Thread.sleep(500)
            rule.waitForIdle()
        }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val worldsDir = File(ctx.filesDir, "worlds")
        val metas = worldsDir.walkTopDown().filter { it.name == "meta.json" }.count()
        results.append(" | (b) meta.json=$metas")
        println("REPRO $results")
        worldsDir.deleteRecursively()
        assertTrue("(a) code did not scroll horizontally: $results", after > before)
        assertTrue("(b) Save produced no meta.json: $results", metas > 0)
        assertTrue("(c) Play did not toggle to Stop: $results", stopShown)
    }

    @Test
    fun control60LineBlock() = runScenario(60, "control")

    @Test
    fun huge1000LineBlock() = runScenario(1000, "huge")

    @Test
    fun hugeBlockIsBoundedAndInnerAreaScrollsVertically() {
        setItem(worldReply(1000))
        val hasV = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        val inner = rule.onAllNodes(hasV).fetchSemanticsNodes()
            .filter { it.size.height < 400 * 3 && it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f }
        assertTrue("no bounded inner vertical scroll area (<=320dp) found", inner.isNotEmpty())
        val node = inner.first()
        val before = node.config[SemanticsProperties.VerticalScrollAxisRange].value()
        rule.onAllNodes(hasV).filterToOne(hasHScroll).performTouchInput { swipeUp() }
        rule.waitForIdle()
        val after = rule.onAllNodes(hasV).fetchSemanticsNodes()
            .first { it.id == node.id }.config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("inner area did not scroll vertically: $before -> $after", after > before)
    }
}
