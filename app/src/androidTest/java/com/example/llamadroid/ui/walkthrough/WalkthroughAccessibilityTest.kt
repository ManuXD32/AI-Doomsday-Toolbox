package com.example.llamadroid.ui.walkthrough

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.ContextThemeWrapper
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalFullyDrawnReporterOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.R
import com.example.llamadroid.ui.LlamaApp
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Verifies that the real walkthrough exposes usable native accessibility controls. */
class WalkthroughAccessibilityTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var savedSettings: Map<String, Any?>
    private var originalAccessibilityFlags: Int? = null

    @Before
    fun preparePreferences() {
        settingsPrefs = rule.activity.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        savedSettings = capture(settingsPrefs, PREFERENCE_KEYS)
        check(
            settingsPrefs.edit()
                .putBoolean(HAS_COMPLETED_WELCOME, true)
                .remove(AUTOMATIC_ELIGIBLE)
                .remove(CORE_PROGRESS)
                .remove(CORE_COMPLETED)
                .commit()
        )
        // Compose accepts ACTION_ACCESSIBILITY_FOCUS only with touch exploration enabled.
        // UiAutomation supports it, but does not request it by default.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val serviceInfo = automation.serviceInfo
        originalAccessibilityFlags = serviceInfo.flags
        serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        automation.serviceInfo = serviceInfo
        val accessibility = rule.activity.getSystemService(AccessibilityManager::class.java)
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) { accessibility.isTouchExplorationEnabled }
    }

    @After
    fun restorePreferences() {
        try {
            restore(settingsPrefs, savedSettings)
        } finally {
            originalAccessibilityFlags?.let { flags ->
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val serviceInfo = automation.serviceInfo
                serviceInfo.flags = flags
                automation.serviceInfo = serviceInfo
            }
        }
    }

    @Test
    fun guideAndCoachControlsAreReachableThroughNativeAccessibilityTree() {
        rule.setContent {
            StandardEnglish411 {
                LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                    LlamaApp()
                }
            }
        }
        rule.waitForIdle()

        awaitViewId("studio_route_dashboard")
        val tourEntry = awaitViewId("soft_studio_tour")
        assertEquals(englishString(R.string.tour_title), nodeDescription(tourEntry))
        assertClickableAndReachable(tourEntry, "App tour")
        clickNative(tourEntry)
        awaitViewId("tour_guide")

        val back = awaitDescription(englishString(R.string.action_back))
        assertEquals(englishString(R.string.action_back), nodeDescription(back))
        assertClickableAndReachable(back, "Guide Back")
        focusNative(back, "Guide Back")
        clickNative(back)
        awaitGone("tour_guide")
        awaitViewId("studio_route_dashboard")

        // Re-enter the real Guide and start the real core chapter using a native click action.
        clickNative(awaitViewId("soft_studio_tour"))
        awaitViewId("tour_guide")
        val startCore = awaitViewId("tour_start_core")
        assertClickableAndReachable(startCore, "Start tour")
        clickNative(startCore)

        awaitViewId("tour_coach")
        val coachTree = nativeTree()
        val closeIndex = coachTree.indexOfFirst { it.viewIdResourceName.testIdSuffix() == "tour_close" }
        val nextIndex = coachTree.indexOfFirst { it.viewIdResourceName.testIdSuffix() == "tour_next" }
        assertTrue("Coach Close must precede Next in accessibility traversal", closeIndex >= 0)
        assertTrue("Coach Next must be reachable after Close", nextIndex > closeIndex)

        val close = awaitViewId("tour_close")
        assertEquals(englishString(R.string.tour_close), nodeDescription(close))
        assertClickableAndReachable(close, "Close walkthrough")
        focusNative(close, "Close walkthrough")

        val next = awaitViewId("tour_next")
        // The first core step is already on Home, so the action is labelled Next. If a host
        // renders the target a frame later, Skip step is still the explicit accessible action.
        val nextLabel = nodeText(next)
        assertTrue(
            "Coach action should expose Next or Skip step, got $nextLabel",
            nextLabel == englishString(R.string.tour_next) ||
                nextLabel == englishString(R.string.tour_skip_step)
        )
        assertClickableAndReachable(next, "Next walkthrough step")
        focusNative(next, "Next walkthrough step")
        clickNative(next)
        awaitViewId("tour_step_tools")

        // X remains a native action after advancing, and closes the coach without an injected
        // semantic click. This also exercises the minimum target size on the live control.
        val finalClose = awaitViewId("tour_close")
        assertEquals(englishString(R.string.tour_close), nodeDescription(finalClose))
        assertClickableAndReachable(finalClose, "Close walkthrough after Next")
        clickNative(finalClose)
        awaitGone("tour_coach")
        awaitViewId("studio_route_dashboard")
    }

    private fun clickNative(node: AccessibilityNodeInfo) {
        assertTrue("Native node is not clickable: ${node.debugLabel()}", node.isClickable)
        assertTrue(
            "Accessibility click failed for ${node.debugLabel()}",
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        )
        rule.waitForIdle()
    }

    private fun focusNative(node: AccessibilityNodeInfo, label: String) {
        assertTrue(
            "Accessibility focus failed for $label",
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        )
        val id = node.viewIdResourceName.testIdSuffix()
        val description = nodeDescription(node)
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            (id?.let(::findByViewId) ?: description?.let(::findByDescription))?.isAccessibilityFocused == true
        }
    }

    private fun assertClickableAndReachable(node: AccessibilityNodeInfo, label: String) {
        assertTrue("$label is not visible to accessibility", node.isVisibleToUser)
        assertTrue("$label is not clickable", node.isClickable)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val minTargetPx = (MIN_TARGET_DP * rule.activity.resources.displayMetrics.density).roundToInt()
        assertTrue(
            "$label target is too small: ${bounds.width()}x${bounds.height()} px; expected at least ${minTargetPx}px",
            bounds.width() >= minTargetPx && bounds.height() >= minTargetPx
        )
    }

    private fun awaitViewId(id: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            result = findByViewId(id)
            result != null
        }
        return requireNotNull(result) { "Accessibility node '$id' was not found" }
    }

    private fun awaitDescription(description: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            result = findByDescription(description)
            result != null
        }
        return requireNotNull(result) {
            "Accessibility node with content description '$description' was not found"
        }
    }

    private fun awaitGone(id: String) {
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) { findByViewId(id) == null }
    }

    private fun findByViewId(id: String?): AccessibilityNodeInfo? =
        nativeTree().firstOrNull { node ->
            node.isVisibleToUser && node.viewIdResourceName.testIdSuffix() == id
        }

    private fun findByDescription(description: String): AccessibilityNodeInfo? =
        nativeTree().firstOrNull { node ->
            node.isVisibleToUser && node.isClickable && nodeDescription(node) == description
        }

    private fun nativeTree(): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return emptyList()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            nodes += node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
            }
        }
        visit(root)
        return nodes
    }

    private fun nodeDescription(node: AccessibilityNodeInfo): String? {
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> nodeDescription(child)?.let { return it } }
        }
        return null
    }

    private fun nodeText(node: AccessibilityNodeInfo): String? {
        node.text?.toString()?.let { return it }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                nodeText(child)?.let { return it }
            }
        }
        return null
    }

    private fun String?.testIdSuffix(): String? = this?.substringAfterLast("/")

    private fun AccessibilityNodeInfo?.debugLabel(): String =
        this?.viewIdResourceName ?: this?.contentDescription?.toString() ?: "unknown"

    private fun englishString(resId: Int): String {
        val configuration = Configuration(rule.activity.resources.configuration).apply {
            setLocale(Locale.US)
        }
        return rule.activity.createConfigurationContext(configuration).getString(resId)
    }

    @Composable
    private fun StandardEnglish411(content: @Composable () -> Unit) {
        val baseDensity = LocalDensity.current
        val physicalDensity = baseDensity.density
        val configuration = Configuration(LocalConfiguration.current).apply {
            screenWidthDp = VIEWPORT_WIDTH_DP
            screenHeightDp = VIEWPORT_HEIGHT_DP
            fontScale = 1f
            setLocale(Locale.US)
        }
        val localizedContext = ContextThemeWrapper(LocalContext.current, 0).apply { applyOverrideConfiguration(configuration) }
        CompositionLocalProvider(
            LocalActivity provides rule.activity,
            LocalFullyDrawnReporterOwner provides rule.activity,
            LocalOnBackPressedDispatcherOwner provides rule.activity,
            LocalActivityResultRegistryOwner provides rule.activity,
            LocalConfiguration provides configuration,
            LocalContext provides localizedContext,
            LocalResources provides localizedContext.resources,
            LocalWindowInfo provides fixtureWindow(
                VIEWPORT_WIDTH_DP,
                VIEWPORT_HEIGHT_DP,
                physicalDensity
            ),
            LocalDensity provides Density(physicalDensity, 1f)
        ) {
            content()
        }
    }

    private fun capture(prefs: SharedPreferences, keys: List<String>): Map<String, Any?> {
        val values = prefs.all
        return keys.associateWith { key -> if (prefs.contains(key)) values[key] else MissingPreference }
    }

    private fun restore(prefs: SharedPreferences, values: Map<String, Any?>) {
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            when (value) {
                null, MissingPreference -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported SharedPreferences value for $key: ${value::class}")
            }
        }
        check(editor.commit()) { "Could not restore accessibility test preferences" }
    }

    private fun fixtureWindow(widthDp: Int, heightDp: Int, density: Float) = object : WindowInfo {
        override val isWindowFocused = true
        override val containerSize = IntSize(
            (widthDp * density).roundToInt(),
            (heightDp * density).roundToInt()
        )
    }

    private object MissingPreference

    private companion object {
        const val SETTINGS_PREFS = "llamadroid_settings"
        const val HAS_COMPLETED_WELCOME = "has_completed_welcome"
        const val AUTOMATIC_ELIGIBLE = "walkthrough_automatic_eligible"
        const val CORE_PROGRESS = "walkthrough_progress:core"
        const val CORE_COMPLETED = "walkthrough_completed:core"
        const val VIEWPORT_WIDTH_DP = 411
        const val VIEWPORT_HEIGHT_DP = 800
        const val MIN_TARGET_DP = 48
        const val WAIT_TIMEOUT_MS = 5_000L
        val PREFERENCE_KEYS = listOf(
            HAS_COMPLETED_WELCOME,
            AUTOMATIC_ELIGIBLE,
            CORE_PROGRESS,
            CORE_COMPLETED
        )
    }
}
