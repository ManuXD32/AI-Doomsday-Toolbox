package com.example.llamadroid.screenshots.harness

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.view.PixelCopy
import java.time.Duration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.example.llamadroid.ui.LlamaApp
import com.example.llamadroid.ui.theme.AppThemeFromSettings
import com.example.llamadroid.ui.navigation.ExternalRouteResolution
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Locale

/** A tap performed after the first settle (opens a sheet, switches a tab...), then settled again. */
sealed interface ShotTap {
    data class Text(val text: String, val substring: Boolean = true) : ShotTap
    data class Tag(val tag: String) : ShotTap
    data class Description(val text: String) : ShotTap
}

/**
 * One screenshot. [route] is a real navigation route ("dashboard", "llama_chat/1/1"). [seed] writes
 * fixtures into the real singletons (it runs after [FakeEnvironment.install], before the activity
 * launches, with a blocking context). [ready] returns true once the screen shows its content.
 */
data class ShotSpec(
    val key: String,
    val route: String,
    val profile: ShotProfile,
    val locale: ShotLocale,
    val seed: (Application) -> Unit = {},
    val ready: (ComposeTestRule) -> Boolean = { true },
    val taps: List<ShotTap> = emptyList(),
    val substitute: Boolean = false,
    /**
     * Texts of controls to paint over with the background colour in the captured image (the control's
     * bounds are filled with the colour sampled at the right edge of its row). Store shots only.
     */
    val blankTexts: List<String> = emptyList(),
    /**
     * When set, this content is composed in the shot box instead of `LlamaApp` (dialog/popup bodies
     * rendered inline, see `DialogShotHost`); [route] is then only a label for the manifest.
     */
    val content: (@Composable () -> Unit)? = null
)

class ShotResult(
    val spec: ShotSpec,
    val bitmap: Bitmap,
    val captureMethod: String,
    val settled: Boolean,
    val millis: Long,
    /** Decor-view draw of the same frame, for sharpness comparison; null unless requested. */
    val decorBitmap: Bitmap?
)

/**
 * Launches the real `LlamaApp` in a bare `ComponentActivity`, settles a manual clock and captures the
 * `adt_shot_root` box. MainActivity is deliberately not used (permission prompts, binary deploy).
 * The caller owns the compose rule (`createEmptyComposeRule()`) and a [FakeEnvironment].
 */
class ShotHarness(private val rule: ComposeTestRule, private val environment: FakeEnvironment) {
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var previousDefaultLocale: Locale? = null

    fun shoot(spec: ShotSpec, alsoDecor: Boolean = false): ShotResult {
        val started = System.nanoTime()
        val app = RuntimeEnvironment.getApplication()
        // Qualifiers (and locale) must be applied before the activity is created.
        RuntimeEnvironment.setQualifiers(spec.profile.qualifiersFor(spec.locale))
        val resourceLocale = app.resources.configuration.locales[0]
        check(resourceLocale.language == spec.locale.tag) {
            "Resource locale is $resourceLocale but ${spec.locale.tag} was requested"
        }
        // Number/date formatting inside the app follows the JVM default locale.
        if (previousDefaultLocale == null) previousDefaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(spec.locale.tag))
        environment.install(app)
        spec.seed(app)
        val launched = ActivityScenario.launch(ComponentActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            activity.setContent {
                AppThemeFromSettings {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        val density = LocalDensity.current
                        Box(
                            modifier = Modifier
                                .size(with(density) { spec.profile.widthPx.toDp() }, with(density) { spec.profile.heightPx.toDp() })
                                .testTag(ROOT_TAG)
                        ) {
                            val inline = spec.content
                            if (inline != null) {
                                inline()
                            } else {
                                LlamaApp(
                                    pendingNavigationRoute = ExternalRouteResolution.Navigate(spec.route),
                                    allowDailySupportPrompt = false,
                                    allowAutomaticWalkthrough = false
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        var settled = settle(spec.ready)
        for (tap in spec.taps) {
            try {
                tap.matcher().let { rule.onAllNodes(it, useUnmergedTree = true).onFirst().performClick() }
            } catch (error: AssertionError) {
                println("SHOT tap $tap failed; visible texts: ${visibleTexts(rule)}")
                throw error
            }
            settled = settle(spec.ready) && settled
        }
        // Let the last compose frame reach the window before reading pixels.
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)) }
        val decor = if (alsoDecor) captureDecor(spec.profile) else null
        val bitmap = capturePixelCopy(spec.profile)
        blankControls(bitmap, spec.blankTexts)
        return ShotResult(
            spec, bitmap, "pixelCopy", settled, (System.nanoTime() - started) / 1_000_000, decor
        )
    }

    fun close() {
        runCatching { scenario?.close() }
        scenario = null
        environment.uninstall()
        previousDefaultLocale?.let { Locale.setDefault(it) }
        previousDefaultLocale = null
    }

    /** Advances the manual clock until [ready] holds (after a minimum) or the wall deadline passes. */
    fun settle(ready: (ComposeTestRule) -> Boolean, minSteps: Int = 6, maxWallMs: Long = 8_000): Boolean {
        val start = System.nanoTime()
        var steps = 0
        while (true) {
            rule.mainClock.advanceTimeBy(250)
            // idleFor also advances the Robolectric clock so Choreographer frames (draws) run.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            Thread.sleep(40)
            steps++
            if (steps >= minSteps && ready(rule)) return true
            if ((System.nanoTime() - start) / 1_000_000 > maxWallMs) return false
        }
    }

    /**
     * Reads the root box straight from the window with PixelCopy (Robolectric draws the real hardware
     * pipeline in NATIVE graphics mode). The compose-test `captureToImage` is not used: its forceRedraw
     * posts to the view handler and waits on the test thread, which never runs under a paused looper
     * (verified: ComposeTimeoutException after 2000 ms with autoAdvance on or off).
     */
    private fun capturePixelCopy(profile: ShotProfile): Bitmap {
        var out: Bitmap? = null
        var status = Int.MIN_VALUE
        scenario?.onActivity { activity ->
            val bitmap = Bitmap.createBitmap(profile.widthPx, profile.heightPx, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                activity.window, Rect(0, 0, profile.widthPx, profile.heightPx), bitmap,
                { status = it }, Handler(Looper.getMainLooper())
            )
            shadowOf(Looper.getMainLooper()).idle()
            out = bitmap
        }
        check(status == PixelCopy.SUCCESS) { "PixelCopy failed with status $status" }
        return checkNotNull(out)
    }

    /** Fills the window bounds of the first node showing each text with the row's right-edge pixel colour. */
    private fun blankControls(bitmap: Bitmap, texts: List<String>) {
        texts.forEach { text ->
            val node = runCatching {
                rule.onAllNodes(hasText(text, ignoreCase = false)).fetchSemanticsNodes().firstOrNull()
            }.getOrNull() ?: run { println("SHOT blank '$text': node not found"); return@forEach }
            val bounds = node.boundsInWindow
            val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
            val right = bounds.right.toInt().coerceIn(left + 1, bitmap.width)
            val top = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
            val bottom = bounds.bottom.toInt().coerceIn(top + 1, bitmap.height)
            val color = bitmap.getPixel(bitmap.width - 4, ((top + bottom) / 2).coerceIn(0, bitmap.height - 1))
            val canvas = Canvas(bitmap)
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), android.graphics.Paint().apply { this.color = color })
            println("SHOT blanked '$text' at $left,$top-$right,$bottom")
        }
    }

    private fun captureDecor(profile: ShotProfile): Bitmap? {
        var out: Bitmap? = null
        scenario?.onActivity { activity ->
            val decor = activity.window.decorView
            val full = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(full))
            out = Bitmap.createBitmap(
                full, 0, 0, minOf(profile.widthPx, full.width), minOf(profile.heightPx, full.height)
            )
        }
        return out
    }

    private fun ShotTap.matcher(): SemanticsMatcher = when (this) {
        is ShotTap.Text -> hasText(text, substring = substring, ignoreCase = true)
        is ShotTap.Tag -> hasTestTag(tag)
        is ShotTap.Description -> hasContentDescription(text, substring = true)
    }

    /** Texts currently in the unmerged semantics tree (diagnostic output for a tap that found nothing). */
    private fun visibleTexts(rule: ComposeTestRule): List<String> =
        rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .distinct().take(80)

    companion object {
        const val ROOT_TAG = "adt_shot_root"

        fun outRoot(): File = File(ShotRunOptions.outDir).let { if (it.isAbsolute) it else File(ShotRunOptions.repoRoot, it.path) }

        /** True when any node shows [text] (case-sensitive substring) in the merged semantics tree. */
        fun textVisible(rule: ComposeTestRule, text: String): Boolean =
            rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
}
