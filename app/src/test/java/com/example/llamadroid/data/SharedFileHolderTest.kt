package com.example.llamadroid.data

import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedFileHolderTest {

    @After
    fun tearDown() {
        SharedFileHolder.clear()
    }

    @Test
    fun `consumePendingFile returns current value and clears it`() {
        val uri = mockk<android.net.Uri>()

        SharedFileHolder.setPendingFile(uri, "application/pdf", "pdf")

        val consumed = SharedFileHolder.consumePendingFile()

        requireNotNull(consumed)
        assertEquals(uri, consumed.uri)
        assertEquals("application/pdf", consumed.mimeType)
        assertEquals("pdf", consumed.targetScreen)
        assertNull(SharedFileHolder.pendingFile.value)
        assertNull(SharedFileHolder.consumePendingFile())
    }

    @Test
    fun `target-aware consume leaves a file intended for another screen`() {
        val uri = mockk<android.net.Uri>()
        SharedFileHolder.setPendingFile(uri, "application/pdf", SharedFileTarget.PDF_SUMMARY)

        assertNull(SharedFileHolder.consumeFor(SharedFileTarget.PDF_TOOLBOX))
        assertEquals(uri, SharedFileHolder.consumeFor(SharedFileTarget.PDF_SUMMARY)?.uri)
    }

    @Test
    fun `target-aware consume accepts legacy image target aliases`() {
        val uri = mockk<android.net.Uri>()
        SharedFileHolder.setPendingFile(
            uri,
            "image/png",
            SharedFileTarget.IMAGE_GENERATION,
            SharedFileHolder.Target.IMAGE_GEN_UPSCALE
        )

        assertEquals(uri, SharedFileHolder.consumeFor(SharedFileTarget.IMAGE_GENERATION)?.uri)
    }

    @Test
    fun `sharing the same uri twice creates distinct requests`() {
        val uri = mockk<android.net.Uri>()
        val first = SharedFileHolder.setPendingFile(
            uri,
            "application/pdf",
            SharedFileTarget.PDF_TOOLBOX
        )
        val second = SharedFileHolder.setPendingFile(
            uri,
            "application/pdf",
            SharedFileTarget.PDF_TOOLBOX
        )

        assertNotEquals(first.id, second.id)
        assertEquals(second.id, SharedFileHolder.consumeFor(SharedFileTarget.PDF_TOOLBOX)?.id)
    }
}
