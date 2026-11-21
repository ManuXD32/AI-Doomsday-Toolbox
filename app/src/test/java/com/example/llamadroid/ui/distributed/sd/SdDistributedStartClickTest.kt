package com.example.llamadroid.ui.distributed.sd

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SdDistributedRunMonitor
import com.example.llamadroid.service.SdRunKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression gate for "tapping Start does nothing": hosts the real [SdMasterScreen] (pixel theme,
 * 360 dp) and clicks the real footer button through pointer input, so anything covering it fails.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class SdDistributedStartClickTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var harness: SdMasterScreenHarness

    @Before
    fun setUp() {
        harness = SdMasterScreenHarness(rule, tmp)
        harness.setUp()
    }

    @After
    fun tearDown() = harness.tearDown()

    private fun openRunStep() {
        harness.awaitLoaded()
        rule.onNodeWithTag("sd_master_step_RUN").performClick()
        rule.waitUntil(5_000) { rule.onNodeWithTag("sd_master_start").isDisplayedSafely() }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.isDisplayedSafely(): Boolean =
        runCatching { assertExists(); true }.getOrDefault(false)

    @Test
    fun imagesWithoutModelExplainsAndJumpsToTheModelStep() {
        harness.host(SdMasterMode.IMAGES)
        openRunStep()
        val message = harness.string(R.string.sd_dist_error_select_image_model)

        // Start is never disabled: the tap itself explains what is missing.
        rule.onNodeWithTag("sd_master_start").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { harness.hasText(message) }

        rule.onNodeWithTag("sd_master_start_message").assertExists()
        assertTrue(harness.hasText(harness.string(R.string.sd_r8_w07_workflow_title)))
        assertTrue("A blocked Start must not start a service", harness.launcher.images.isEmpty())
        assertEquals(null, SdDistributedRunMonitor.session.value)
    }

    @Test
    fun imagesWithModelAndHelperReachesTheLaunchSeam() {
        harness.seedImageSetup(withHelper = true)
        harness.host(SdMasterMode.IMAGES)
        harness.awaitLoaded()
        // Model and an enabled Helper: the flow opens on the Run step.
        rule.waitUntil(5_000) { harness.hasText(harness.string(R.string.sd_r8_w08_prompt_title)) }

        rule.onNodeWithTag("sd_master_start").assertIsEnabled().performClick()
        rule.waitUntil(10_000) { harness.launcher.images.isNotEmpty() }

        val config = harness.launcher.images.single()
        assertEquals(SDMode.TXT2IMG, config.mode)
        assertEquals("a cat", config.prompt)
        assertTrue(config.distributedRuntime.enabled)
        val session = SdDistributedRunMonitor.session.value
        assertNotNull("Run monitor session was not begun", session)
        assertEquals(SdRunKind.IMAGE, session!!.kind)
        assertEquals("TXT2IMG", session.mode)
    }

    @Test
    fun imagesWithModelOnlyRunsOnThisPhone() {
        harness.seedImageSetup(withHelper = false)
        harness.host(SdMasterMode.IMAGES)
        openRunStep()

        rule.onNodeWithTag("sd_master_start").performClick()
        rule.waitUntil(10_000) { harness.launcher.images.isNotEmpty() }

        assertEquals("a cat", harness.launcher.images.single().prompt)
    }

    @Test
    fun startFooterStaysTappableAfterScrollingTheLongRunStep() {
        harness.seedImageSetup(withHelper = true)
        harness.host(SdMasterMode.IMAGES)
        harness.awaitLoaded()
        rule.waitUntil(5_000) { harness.hasText(harness.string(R.string.sd_r8_w08_prompt_title)) }

        // Scroll to the last card of the step; the footer is outside the list and must still take the tap.
        rule.onNode(hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText(harness.string(R.string.sd_r8_w08_links_title)))
        rule.waitForIdle()
        rule.onNodeWithTag("sd_master_start").assertIsEnabled().performClick()
        rule.waitUntil(10_000) { harness.launcher.images.isNotEmpty() }

        assertEquals(1, harness.launcher.images.size)
    }

    /** Robolectric has no SD binary, so a video run is refused with the binary message and nothing starts. */
    @Test
    fun videoWithoutBinaryShowsTheBinaryMessageAndStartsNothing() {
        runBlocking(Dispatchers.IO) {
            AppDatabase.getDatabase(harness.app).sdDistributedDao()
                .upsertMasterSettings(SdDistributedMasterSettingsEntity(id = 1, videoPrompt = "a cat running"))
        }
        harness.host(SdMasterMode.VIDEO)
        openRunStep()

        rule.onNodeWithTag("sd_master_start").performClick()
        val message = harness.string(R.string.video_controls_binary_unavailable_selection)
        rule.waitUntil(10_000) { harness.hasText(message) }

        assertTrue(harness.launcher.videos.isEmpty())
        assertEquals(null, SdDistributedRunMonitor.session.value)
    }
}
