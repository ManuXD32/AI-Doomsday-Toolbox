package com.example.llamadroid.ui.distributed.sd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdMasterRunActionsTest {
    @Test
    fun issuesJumpToTheStepThatFixesThem() {
        val images = SdMasterMode.IMAGES
        val video = SdMasterMode.VIDEO
        assertEquals(SdMasterStep.MODEL, sdMasterIssueStep(SdDistributedLaunchIssue.SelectImageModel, images))
        assertEquals(SdMasterStep.MODEL, sdMasterIssueStep(SdDistributedLaunchIssue.SelectVideoModel, video))
        assertEquals(SdMasterStep.MODEL, sdMasterIssueStep(SdDistributedLaunchIssue.MissingImageComponents(emptyList()), images))
        assertEquals(SdMasterStep.RUN, sdMasterIssueStep(SdDistributedLaunchIssue.PromptRequired, images))
        assertEquals(SdMasterStep.RUN, sdMasterIssueStep(SdDistributedLaunchIssue.InvalidNumber(0), video))
    }

    @Test
    fun inputImageLivesOnRunForImagesAndOnModelForVideo() {
        assertEquals(SdMasterStep.RUN, sdMasterIssueStep(SdDistributedLaunchIssue.InputImageRequired, SdMasterMode.IMAGES))
        assertEquals(SdMasterStep.MODEL, sdMasterIssueStep(SdDistributedLaunchIssue.InputImageRequired, SdMasterMode.VIDEO))
    }

    @Test
    fun silenceHintAppearsAfterFiveMinutesOnly() {
        assertNull(sdRunSilenceMinutes(nowMs = 4 * 60_000L + 59_000L, lastOutputAtMs = 0L))
        assertEquals(5, sdRunSilenceMinutes(nowMs = 5 * 60_000L, lastOutputAtMs = 0L))
        assertEquals(7, sdRunSilenceMinutes(nowMs = 8 * 60_000L + 30_000L, lastOutputAtMs = 60_000L + 30_000L))
        assertNull(sdRunSilenceMinutes(nowMs = 100L, lastOutputAtMs = 200L))
    }
}
