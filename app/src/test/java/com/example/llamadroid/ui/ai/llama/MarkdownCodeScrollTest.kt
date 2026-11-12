package com.example.llamadroid.ui.ai.llama

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Reproduces "```html code block is not horizontally scrollable" for model replies. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class MarkdownCodeScrollTest {
    @get:Rule
    val rule = createComposeRule()

    private val longLine = "x".repeat(400)
    private val hasHScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

    private fun elephant(): String =
        javaClass.getResource("/worlds/elephant_module.md")!!.readText()

    private fun scrollValueAfterSwipe(markdown: String, selectable: Boolean): Pair<Float, Float> {
        rule.setContent {
            Box(Modifier.width(360.dp)) {
                if (selectable) {
                    SelectionContainer { MarkdownText(text = markdown, textColor = Color.Black, modifier = Modifier.fillMaxWidth()) }
                } else {
                    MarkdownText(text = markdown, textColor = Color.Black, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        rule.waitForIdle()
        val nodes = rule.onAllNodes(hasHScroll)
        val first = nodes.fetchSemanticsNodes().first()
        val range = first.config[SemanticsProperties.HorizontalScrollAxisRange]
        val before = range.value()
        nodes[0].performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val after = rule.onAllNodes(hasHScroll).fetchSemanticsNodes().first()
            .config[SemanticsProperties.HorizontalScrollAxisRange].value()
        println("scroll before=$before after=$after max=${range.maxValue()}")
        return before to after
    }

    @Test
    fun kotlinBlockScrolls() {
        val (b, a) = scrollValueAfterSwipe("Reply\n\n```kotlin\nval s = \"$longLine\"\n```\n", selectable = false)
        assertTrue("kotlin control did not scroll: $b -> $a", a > b)
    }

    @Test
    fun htmlWorldBlockScrolls() {
        val md = elephant() + "\n\n```html\n<p>$longLine</p>\n```\n"
        val (b, a) = scrollValueAfterSwipe(md, selectable = false)
        assertTrue("html block did not scroll: $b -> $a", a > b)
    }

    @Test
    fun htmlWorldBlockScrollsInsideSelectionContainer() {
        val md = elephant()
        val (b, a) = scrollValueAfterSwipe(md, selectable = true)
        assertTrue("html block in SelectionContainer did not scroll: $b -> $a", a > b)
    }
}
