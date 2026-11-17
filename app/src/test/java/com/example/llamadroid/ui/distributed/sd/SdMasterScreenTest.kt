package com.example.llamadroid.ui.distributed.sd

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.llamadroid.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The three-step shell: header navigation, Next / Back and the first-incomplete-step rule. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class SdMasterScreenTest {
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

    private fun waitForText(id: Int) {
        val text = harness.string(id)
        rule.waitUntil(5_000) { harness.hasText(text) }
    }

    @Test
    fun headerNavigatesBetweenTheThreeSteps() {
        harness.host(SdMasterMode.IMAGES)
        harness.awaitLoaded()
        // Nothing configured yet: the flow opens on the Model step.
        waitForText(R.string.sd_r8_w07_workflow_title)

        rule.onNodeWithTag("sd_master_step_DEVICES").performClick()
        waitForText(R.string.sd_r8_w07_split_title)

        rule.onNodeWithTag("sd_master_step_RUN").performClick()
        waitForText(R.string.sd_r8_w08_prompt_title)
        assertTrue(harness.hasText(harness.string(R.string.sd_dist_start_work)))

        rule.onNodeWithTag("sd_master_step_MODEL").performClick()
        waitForText(R.string.sd_r8_w07_workflow_title)
    }

    @Test
    fun nextAndBackMoveOneStep() {
        harness.host(SdMasterMode.IMAGES)
        harness.awaitLoaded()
        waitForText(R.string.sd_r8_w07_workflow_title)

        rule.onNodeWithTag("sd_master_next").performClick()
        waitForText(R.string.sd_r8_w07_split_title)
        rule.onNodeWithTag("sd_master_next").performClick()
        waitForText(R.string.sd_r8_w08_prompt_title)
        // Step 3 swaps Next for Start.
        assertTrue(harness.hasText(harness.string(R.string.sd_dist_start_work)))

        rule.onNodeWithTag("sd_master_back").performClick()
        waitForText(R.string.sd_r8_w07_split_title)
        rule.onNodeWithTag("sd_master_back").performClick()
        waitForText(R.string.sd_r8_w07_workflow_title)
    }

    @Test
    fun modelAndHelperOpenOnTheRunStep() {
        harness.seedImageSetup(withHelper = true)
        harness.host(SdMasterMode.IMAGES)
        harness.awaitLoaded()
        waitForText(R.string.sd_r8_w08_prompt_title)
        assertEquals(false, harness.hasText(harness.string(R.string.sd_r8_w07_workflow_title)))
    }

    @Test
    fun modelWithoutHelperOpensOnTheDevicesStep() {
        harness.seedImageSetup(withHelper = false)
        harness.host(SdMasterMode.IMAGES)
        harness.awaitLoaded()
        waitForText(R.string.sd_r8_w07_split_title)
    }
}
