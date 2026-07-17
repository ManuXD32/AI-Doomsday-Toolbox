package com.example.llamadroid.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSummaryBackendEditorTest {
    @Test
    fun visibleLiteRtModelIdKeepsSavedSelectionWhenAvailable() {
        assertEquals(42L, visibleLiteRtModelId(currentId = 42L, availableIds = listOf(7L, 42L)))
    }

    @Test
    fun visibleLiteRtModelIdFallsBackToFirstInstalledModel() {
        assertEquals(7L, visibleLiteRtModelId(currentId = null, availableIds = listOf(7L, 42L)))
        assertEquals(7L, visibleLiteRtModelId(currentId = 999L, availableIds = listOf(7L, 42L)))
    }

    @Test
    fun visibleLiteRtModelIdReturnsNullWhenNoModelsAreInstalled() {
        assertNull(visibleLiteRtModelId(currentId = null, availableIds = emptyList()))
    }

    @Test
    fun shouldCommitVisibleLiteRtModelIdOnlyWhenTheVisibleSelectionDiffers() {
        assertTrue(shouldCommitVisibleLiteRtModelId(currentId = null, visibleId = 7L))
        assertTrue(shouldCommitVisibleLiteRtModelId(currentId = 999L, visibleId = 7L))
        assertFalse(shouldCommitVisibleLiteRtModelId(currentId = 7L, visibleId = 7L))
        assertFalse(shouldCommitVisibleLiteRtModelId(currentId = null, visibleId = null))
    }
}
