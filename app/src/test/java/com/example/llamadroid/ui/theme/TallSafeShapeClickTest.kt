package com.example.llamadroid.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A button at the bottom of a very tall clipped card must stay tappable. Compose hit-tests a clipped layer with a
 * generic (path) outline through a path intersection that loses precision at large coordinates, so a tall card
 * with [PixelCornerShape] used to swallow taps near its bottom (the distributed Media "Start work" button).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class TallSafeShapeClickTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun clicksOnBottomButton(shape: Shape, contentHeightDp: Int): Int {
        var clicks = 0
        rule.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Card(shape = shape, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Spacer(Modifier.height(contentHeightDp.dp))
                        Button(onClick = { clicks++ }, modifier = Modifier.testTag("bottom")) { Text("Go") }
                    }
                }
            }
        }
        rule.onNodeWithTag("bottom").performScrollTo().performClick()
        rule.waitForIdle()
        return clicks
    }

    private val bubble = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)

    @Test
    fun bubbleShape4000pxIsTappable() = assertEquals(1, clicksOnBottomButton(TallSafeShape(bubble), 2000))

    @Test
    fun bubbleShape10000pxIsTappable() = assertEquals(1, clicksOnBottomButton(TallSafeShape(bubble), 5000))

    @Test
    fun bubbleShape18000pxIsTappable() = assertEquals(1, clicksOnBottomButton(TallSafeShape(bubble), 9000))

    @Test
    fun outlineIsRectangleAboveTheLimit() {
        val density = Density(2f)
        val shape = TallSafeShape(bubble)
        assertTrue(shape.createOutline(Size(720f, 100f), LayoutDirection.Ltr, density) !is Outline.Rectangle)
        assertTrue(shape.createOutline(Size(720f, MAX_PATH_OUTLINE_PX + 1f), LayoutDirection.Ltr, density) is Outline.Rectangle)
    }
}

