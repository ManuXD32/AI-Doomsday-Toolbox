package com.example.llamadroid.worlds

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Toggling and re-hosting WorldWebView must tear each WebView down exactly once without throwing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class WorldWebViewLifecycleTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val seen = LinkedHashSet<WebView>()

    private fun collect(view: View) {
        if (view is WebView) seen.add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
    }

    private fun snapshot() = rule.runOnUiThread { collect(rule.activity.window.decorView) }

    @Test
    fun toggleThreeTimesDestroysEachInstanceOnce() {
        var visible by mutableStateOf(false)
        rule.setContent {
            if (visible) WorldWebView(pageHtml = "<html><body>hi</body></html>", modifier = Modifier.fillMaxSize())
        }
        repeat(3) {
            rule.runOnUiThread { visible = true }
            rule.waitForIdle()
            snapshot()
            rule.runOnUiThread { visible = false }
            rule.waitForIdle()
        }
        assertEquals(3, seen.size)
        seen.forEach { assertTrue(shadowOf(it).wasDestroyCalled()) }
    }

    @Test
    fun hostSwapInlineToFullscreenDoesNotThrow() {
        var fullscreen by mutableStateOf(false)
        var visible by mutableStateOf(true)
        rule.setContent {
            if (visible) {
                if (fullscreen) {
                    Box(Modifier.fillMaxSize()) { WorldWebView(pageHtml = "<html></html>", modifier = Modifier.fillMaxSize()) }
                } else {
                    Box { WorldWebView(pageHtml = "<html></html>", modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        rule.waitForIdle()
        snapshot()
        rule.runOnUiThread { fullscreen = true }
        rule.waitForIdle()
        snapshot()
        rule.runOnUiThread { visible = false }
        rule.waitForIdle()
        assertTrue(seen.isNotEmpty())
        seen.forEach { assertTrue(shadowOf(it).wasDestroyCalled()) }
    }
}
