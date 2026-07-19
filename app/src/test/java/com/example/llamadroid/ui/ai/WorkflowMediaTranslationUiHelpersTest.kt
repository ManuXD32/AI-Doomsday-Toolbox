package com.example.llamadroid.ui.ai

import com.example.llamadroid.service.MEDIA_TRANSLATION_WORKFLOW_KIND_MEDIA
import com.example.llamadroid.service.MEDIA_TRANSLATION_WORKFLOW_KIND_SUBTITLE
import com.example.llamadroid.service.MediaTranslationWorkflowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowMediaTranslationUiHelpersTest {
    @Test
    fun workflowIndexFollowsRuntimeKind() {
        assertEquals(4, workflowIndexForMediaTranslationKind(MEDIA_TRANSLATION_WORKFLOW_KIND_MEDIA))
        assertEquals(5, workflowIndexForMediaTranslationKind(MEDIA_TRANSLATION_WORKFLOW_KIND_SUBTITLE))
        assertNull(workflowIndexForMediaTranslationKind(null))
    }

    @Test
    fun mediaTranslationStateForWorkflowHidesOtherWorkflowState() {
        val subtitleState = MediaTranslationWorkflowState(
            workflowKind = MEDIA_TRANSLATION_WORKFLOW_KIND_SUBTITLE,
            isRunning = true,
            status = "Subtitle work"
        )

        val hiddenFromMedia = mediaTranslationStateForWorkflow(subtitleState, MEDIA_TRANSLATION_WORKFLOW_KIND_MEDIA)
        val visibleForSubtitle = mediaTranslationStateForWorkflow(subtitleState, MEDIA_TRANSLATION_WORKFLOW_KIND_SUBTITLE)

        assertFalse(hiddenFromMedia.isRunning)
        assertEquals("", hiddenFromMedia.status)
        assertTrue(visibleForSubtitle.isRunning)
        assertEquals("Subtitle work", visibleForSubtitle.status)
    }

    @Test
    fun galleryRecognizesLegacyAndSourceNamedSubtitleFiles() {
        assertTrue(workflowGalleryIsOriginalSubtitleFileName("original.srt"))
        assertTrue(workflowGalleryIsOriginalSubtitleFileName("original_My_Video_123.srt"))
        assertTrue(workflowGalleryIsTranslatedSubtitleFileName("translated.srt"))
        assertTrue(workflowGalleryIsTranslatedSubtitleFileName("translated_My_Video_123.srt"))
        assertFalse(workflowGalleryIsOriginalSubtitleFileName("translated_My_Video_123.srt"))
        assertFalse(workflowGalleryIsTranslatedSubtitleFileName("original_My_Video_123.srt"))
    }
}
