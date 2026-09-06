package com.example.llamadroid.ui.walkthrough

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.R
import com.example.llamadroid.data.WalkthroughPreferences
import com.example.llamadroid.ui.LlamaApp
import com.example.llamadroid.ui.navigation.ExternalRouteResolution
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.util.Locale
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Connected regression for the video guide's real tab and profile actions.
 *
 * This is opt-in because it mounts the production LlamaApp and its repositories. Run it under
 * Android user 10 with `isolated_feature_qa=true` and the package's instrumentation APK. The
 * test only opens/dismisses guidance and clicks the Create/Gallery tabs plus the harmless custom
 * profile chip; it never starts generation, downloads a model, imports a file, or submits work.
 */
class VideoFeatureGuideConnectedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var savedSettings: Map<String, Any?>

    private val pendingNavigationRoute = mutableStateOf<ExternalRouteResolution>(
        ExternalRouteResolution.NoRoute
    )
    private val hostRequest = mutableStateOf(HostRequest(0L))
    private var contentInstalled = false
    private var requestSequence = 0L

    @Before
    fun prepareIsolatedPreferences() {
        // Keep this guard as the first operation: no app prefs, database, or files are touched
        // when a normal user-0 connected invocation is accidentally selected.
        requireIsolatedInvocation()
        settingsPrefs = rule.activity.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        savedSettings = capture(settingsPrefs, SETTINGS_KEYS)
        check(
            settingsPrefs.edit()
                .putBoolean(HAS_COMPLETED_WELCOME, true)
                .putBoolean(AUTOMATIC_ELIGIBLE, false)
                .remove(progressKey(SESSION_ID))
                .remove(completedKey(SESSION_ID))
                .commit()
        ) { "Could not prepare isolated video-guide preferences" }
    }

    @After
    fun restoreIsolatedPreferences() {
        if (::settingsPrefs.isInitialized && ::savedSettings.isInitialized) {
            restore(settingsPrefs, savedSettings)
        }
    }

    @Test
    fun galleryGuideStartAndActionsKeepTheUnderlyingTabHonest() {
        ensureHost()
        navigateToVideo()

        val galleryLabel = localized(R.string.video_gen_tab_gallery)
        val createLabel = localized(R.string.video_gen_tab_generate)
        val profileLabel = localized(R.string.video_runtime_custom_profile)

        selectTab(galleryLabel)
        assertSelected(galleryLabel)

        // The chooser is a modal over the live editor. Its close action must leave Gallery
        // selected, so opening help cannot silently reset the tool's current tab.
        openFeatureChooser()
        rule.onNodeWithTag("feature_guide_close").performClick()
        awaitGone("feature_guide_chooser")
        assertVideoRoute()
        assertSelected(galleryLabel)

        openFeatureChooser()
        startQuickstart()
        check(awaitTag("tour_step_video.quickstart.orient")) { "Required video guide step did not appear: " + "tour_step_video.quickstart.orient" }
        assertVideoRoute()
        assertSelected(galleryLabel)

        // Coach X persists the first step and must also preserve the underlying Gallery tab.
        rule.onNodeWithTag("tour_close").performClick()
        awaitGone("tour_coach")
        assertVideoRoute()
        assertSelected(galleryLabel)
        check(
            WalkthroughPreferences(settingsPrefs).progress(SESSION_ID) ==
                "video.quickstart.orient"
        ) { "Coach close did not persist the current video-guide step" }

        // Resume from the same route. Starting a guide from Gallery must not auto-switch to
        // Create; the next transition is caused by the real Create tab click below.
        openFeatureChooser()
        startQuickstart(resume = true)
        check(awaitTag("tour_step_video.quickstart.orient")) { "Required video guide step did not appear: " + "tour_step_video.quickstart.orient" }
        assertVideoRoute()
        assertSelected(galleryLabel)

        selectTab(createLabel)
        check(awaitTag("tour_step_video.quickstart.read")) { "Required video guide step did not appear: " + "tour_step_video.quickstart.read" }
        assertVideoRoute()
        assertSelected(createLabel)

        // Binary availability owns this control. Missing native binaries must leave a usable
        // Skip path; when available, observe the real profile event without starting inference.
        val profileChip = rule.onNodeWithText(profileLabel)
        if (profileChip.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)) {
            profileChip.assertIsNotEnabled()
            rule.onNodeWithTag("tour_next").performClick()
        } else {
            profileChip.performClick()
        }
        check(awaitTag("tour_step_video.quickstart.recover")) { "Required video guide step did not appear: " + "tour_step_video.quickstart.recover" }
        assertVideoRoute()
        assertSelected(createLabel)

        selectTab(galleryLabel)
        awaitGone("tour_coach")
        assertSelected(galleryLabel)
        check(WalkthroughPreferences(settingsPrefs).isCompleted(SESSION_ID)) {
            "Gallery event did not complete the video guide"
        }
    }

    private fun requireIsolatedInvocation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Set isolated_feature_qa=true to run against the real LlamaApp host",
            args.getString(ARG_ISOLATED) == "true"
        )
        assumeTrue(
            "Run this test only in secondary Android user 10",
            args.getString(ARG_SECONDARY_USER) == SECONDARY_USER_ID
        )
        assumeTrue(
            "The connected test must run under Android user 10, not only receive the argument",
            android.os.Process.myUid() / USER_ID_RANGE == SECONDARY_USER_ID.toInt()
        )
    }

    /** The production host is installed once; navigation is delivered as a mutable request. */
    private fun ensureHost() {
        if (contentInstalled) return
        contentInstalled = true
        rule.setContent {
            val request = hostRequest.value
            key(request.sequence) {
                LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                    LlamaApp(
                        pendingNavigationRoute = pendingNavigationRoute.value,
                        onNavigationHandled = {
                            pendingNavigationRoute.value = ExternalRouteResolution.NoRoute
                        },
                        allowDailySupportPrompt = false,
                        allowAutomaticWalkthrough = false
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun navigateToVideo() {
        requestSequence += 1
        rule.runOnUiThread {
            pendingNavigationRoute.value = ExternalRouteResolution.Navigate(VIDEO_ROUTE)
            hostRequest.value = HostRequest(requestSequence)
        }
        rule.waitForIdle()
        check(awaitTag("studio_route_$VIDEO_ROUTE")) {
            "The real NavHost did not expose $VIDEO_ROUTE"
        }
    }

    private fun openFeatureChooser() {
        check(awaitTag("feature_guide_open")) {
            "VideoGenScreen did not expose its FeatureGuideAction"
        }
        rule.onNodeWithTag("feature_guide_open").performClick()
        check(awaitTag("feature_guide_chooser")) {
            "FeatureGuideAction did not open the real chooser"
        }
    }

    private fun startQuickstart(resume: Boolean = false) {
        val cardTag = "feature_recipe_video.quickstart"
        check(awaitTag(cardTag)) { "Video quickstart card was not rendered" }
        val label = localized(if (resume) R.string.tour_resume else R.string.tour_start)
        rule.onNode(cardTagStartMatcher(cardTag, label), useUnmergedTree = true)
            .performClick()
        check(awaitTag("tour_coach")) { "Video guide did not open its coach" }
    }

    private fun selectTab(label: String) {
        rule.onNodeWithText(label).performClick()
        rule.waitForIdle()
    }

    private fun assertVideoRoute() {
        check(awaitTag("studio_route_$VIDEO_ROUTE")) {
            "Video guide action unexpectedly left $VIDEO_ROUTE"
        }
    }

    private fun assertSelected(label: String) {
        rule.onNodeWithText(label).assertIsSelected()
    }

    private fun cardTagStartMatcher(cardTag: String, label: String) =
        hasClickAction() and
            hasAnyAncestor(hasTestTag(cardTag)) and
            hasAnyDescendant(hasText(label))

    private fun awaitTag(tag: String, timeoutMillis: Long = WAIT_TIMEOUT_MS): Boolean =
        try {
            rule.waitUntil(timeoutMillis = timeoutMillis) {
                rule.onAllNodesWithTag(tag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            true
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            false
        }

    private fun awaitGone(tag: String) {
        check(
            try {
                rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
                    rule.onAllNodesWithTag(tag, useUnmergedTree = true)
                        .fetchSemanticsNodes().isEmpty()
                }
                true
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                false
            }
        ) { "Timed out waiting for $tag to disappear" }
    }

    private fun localized(resId: Int): String {
        val configuration = Configuration(rule.activity.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        return rule.activity.createConfigurationContext(configuration).getString(resId)
    }

    private fun capture(prefs: SharedPreferences, keys: List<String>): Map<String, Any?> {
        val values = prefs.all
        return keys.associateWith { key -> if (prefs.contains(key)) values[key] else MissingPreference }
    }

    private fun restore(prefs: SharedPreferences, values: Map<String, Any?>) {
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            when (value) {
                MissingPreference, null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported SharedPreferences value for $key: ${value::class}")
            }
        }
        check(editor.commit()) { "Could not restore isolated video-guide preferences" }
    }

    private data class HostRequest(val sequence: Long)

    private object MissingPreference

    private companion object {
        const val SETTINGS_PREFS = "llamadroid_settings"
        const val HAS_COMPLETED_WELCOME = "has_completed_welcome"
        const val AUTOMATIC_ELIGIBLE = "walkthrough_automatic_eligible"
        const val ARG_ISOLATED = "isolated_feature_qa"
        const val ARG_SECONDARY_USER = "secondary_android_user_id"
        const val SECONDARY_USER_ID = "10"
        const val USER_ID_RANGE = 100_000
        const val VIDEO_ROUTE = "video_gen"
        const val SESSION_ID = "feature:video.quickstart"
        const val WAIT_TIMEOUT_MS = 8_000L
        val SETTINGS_KEYS = listOf(
            HAS_COMPLETED_WELCOME,
            AUTOMATIC_ELIGIBLE,
            progressKey(SESSION_ID),
            completedKey(SESSION_ID)
        )

        fun progressKey(chapterId: String) = "walkthrough_progress:$chapterId"
        fun completedKey(chapterId: String) = "walkthrough_completed:$chapterId"
    }
}
