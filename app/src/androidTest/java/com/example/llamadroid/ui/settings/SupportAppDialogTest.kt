package com.example.llamadroid.ui.settings

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.compose.ui.platform.ComposeView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.llamadroid.R
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SupportAppDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun englishLargeTextKeepsBothSupportLinksAndDismissReachable() = verify("en")
    @Test fun spanishLargeTextKeepsBothSupportLinksAndDismissReachable() = verify("es")

    private fun captureDialog(language: String, stage: String) {
        rule.waitForIdle()
        android.os.SystemClock.sleep(300) // Allow the platform dialog window transition to finish.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val directory = File(rule.activity.getExternalFilesDir(null), "soft-studio-support-qa").apply { mkdirs() }
            File(directory, "$language-$stage-font2.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun verify(language: String) {
        val configuration = Configuration(rule.activity.resources.configuration).apply {
            fontScale = 2f
            setLocale(Locale.forLanguageTag(language))
        }
        // Dialog creates a separate Android window from the host view's Context. Configure
        // that real Context so both language and font scale reach the dialog content.
        val context = ContextThemeWrapper(rule.activity, rule.activity.theme).apply {
            applyOverrideConfiguration(configuration)
        }
        val links = mutableListOf<String>()
        var dismissals = 0
        rule.runOnUiThread {
            rule.activity.setContentView(ComposeView(context).apply {
                setContent {
                    LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                        SupportAppDialog(onDismiss = { dismissals++ }, onSupportLink = { links.add(it) })
                    }
                }
            })
        }
        val notNow = context.getString(R.string.support_not_now)
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithText(notNow).assertIsDisplayed() }.isSuccess
        }
        captureDialog(language, "initial")
        rule.onNodeWithText(context.getString(R.string.about_kofi)).performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText(context.getString(R.string.about_paypal)).performScrollTo().assertIsDisplayed().performClick()
        captureDialog(language, "links")
        rule.onNodeWithText(notNow).assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(listOf(SupportLinks.KOFI, SupportLinks.PAYPAL), links)
            assertEquals(1, dismissals)
        }
    }
}
