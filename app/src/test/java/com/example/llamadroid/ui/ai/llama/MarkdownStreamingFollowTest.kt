package com.example.llamadroid.ui.ai.llama

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A streaming code block keeps its scroll state, follows its bottom, and stops after the user drags up. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class MarkdownStreamingFollowTest {
    @get:Rule
    val rule = createComposeRule()

    private val hasVScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun markdown(lines: Int) =
        "Reply\n\n```html\n" + (1..lines).joinToString("\n") { "<p>line $it</p>" }

    private fun range() = rule.onAllNodes(hasVScroll).fetchSemanticsNodes().first()
        .config[SemanticsProperties.VerticalScrollAxisRange]

    @Test
    fun followsBottomWhileStreamingThenStopsAfterUserDrag() {
        val text = mutableStateOf(markdown(40))
        rule.setContent {
            Box(Modifier.width(360.dp)) {
                MarkdownText(text = text.value, textColor = Color.Black, streaming = true)
            }
        }
        rule.waitForIdle()
        assertTrue("block must overflow its 320dp body", range().maxValue() > 0f)
        assertEquals(range().maxValue(), range().value(), 2f)

        text.value = markdown(80)
        rule.waitForIdle()
        assertEquals("grown block must follow the bottom", range().maxValue(), range().value(), 2f)

        // drag the content down (scroll up)
        rule.onAllNodes(hasVScroll)[0].performTouchInput { swipeDown() }
        rule.waitForIdle()
        val afterDrag = range().value()
        assertTrue("drag must leave the bottom", afterDrag < range().maxValue() - 2f)

        text.value = markdown(140)
        rule.waitForIdle()
        assertEquals("no longer follows after a user drag", afterDrag, range().value(), 2f)
    }
}
