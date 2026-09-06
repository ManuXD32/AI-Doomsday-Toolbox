package com.example.llamadroid.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.activity.ComponentActivity
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DailySupportPromptTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var settingsPrefs: SharedPreferences
    private var hadSavedSupportDay = false
    private var savedSupportDay = Long.MIN_VALUE
    private var hadSavedAutomaticEligibility = false
    private var savedAutomaticEligibility = false
    private lateinit var settings: SettingsRepository

    @Before
    fun preparePreferences() {
        settingsPrefs = rule.activity.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        hadSavedSupportDay = settingsPrefs.contains(SUPPORT_LAST_SHOWN)
        savedSupportDay = settingsPrefs.getLong(SUPPORT_LAST_SHOWN, Long.MIN_VALUE)
        hadSavedAutomaticEligibility = settingsPrefs.contains(AUTOMATIC_ELIGIBLE)
        savedAutomaticEligibility = settingsPrefs.getBoolean(AUTOMATIC_ELIGIBLE, false)
        settingsPrefs.edit()
            .putLong(SUPPORT_LAST_SHOWN, LocalDate.now().minusDays(1).toEpochDay())
            .commit()
        settings = SettingsRepository(rule.activity)
    }

    @After
    fun restorePreferences() {
        val editor = settingsPrefs.edit()
        if (hadSavedSupportDay) editor.putLong(SUPPORT_LAST_SHOWN, savedSupportDay)
        else editor.remove(SUPPORT_LAST_SHOWN)
        if (hadSavedAutomaticEligibility) {
            editor.putBoolean(AUTOMATIC_ELIGIBLE, savedAutomaticEligibility)
        } else {
            editor.remove(AUTOMATIC_ELIGIBLE)
        }
        check(editor.commit()) { "Could not restore support prompt preferences" }
    }

    @Test
    fun eligiblePromptClaimsTodayAndDismissalClosesIt() {
        val eligible = mutableStateOf(true)
        val launchId = mutableStateOf(0)
        val (title, notNow) = setPromptContent(eligible, launchId)

        waitForText(title)
        rule.onNodeWithText(notNow).performClick()
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            rule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(LocalDate.now().toEpochDay(), settingsPrefs.getLong(SUPPORT_LAST_SHOWN, Long.MIN_VALUE))
    }

    @Test
    fun ineligibleLaunchClearsVisibleDialogBeforeSameDayNormalLaunch() {
        val eligible = mutableStateOf(true)
        val launchId = mutableStateOf(0)
        val (title, _) = setPromptContent(eligible, launchId)

        waitForText(title)
        rule.runOnIdle { eligible.value = false }
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            rule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }

        // The next normal launch has a new identity but the same durable day claim.
        rule.runOnIdle {
            launchId.value += 1
            eligible.value = true
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty())
    }

    private fun setPromptContent(
        eligible: MutableState<Boolean>,
        launchId: MutableState<Int>
    ): Pair<String, String> {
        val expectedLabels = mutableStateOf<Pair<String, String>?>(null)
        rule.setContent {
            val resources = LocalResources.current
            SideEffect {
                expectedLabels.value =
                    resources.getString(R.string.support_daily_title) to
                        resources.getString(R.string.support_not_now)
            }
            LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                DailySupportPrompt(settings = settings, eligible = eligible.value, launchId = launchId.value)
            }
        }
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) { expectedLabels.value != null }
        return checkNotNull(expectedLabels.value)
    }

    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            runCatching { rule.onNodeWithText(text).assertIsDisplayed() }.isSuccess
        }
    }

    private companion object {
        const val SETTINGS_PREFS = "llamadroid_settings"
        const val SUPPORT_LAST_SHOWN = "support_last_shown_epoch_day"
        const val AUTOMATIC_ELIGIBLE = "walkthrough_automatic_eligible"
        const val WAIT_TIMEOUT_MS = 5_000L
    }
}
