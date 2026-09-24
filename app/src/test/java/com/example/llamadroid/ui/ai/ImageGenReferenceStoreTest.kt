package com.example.llamadroid.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class ImageGenReferenceStoreTest {
    @Test
    fun `reported size above cap is rejected before opening content`() {
        assertThrows(ImageGenReferenceTooLargeException::class.java) {
            requireImageGenReferenceSizeWithinLimit(sizeBytes = 6L, maxBytes = 5L)
        }
    }

    @Test
    fun `streaming copy rejects unknown oversized payload and deletes partial file`() {
        val target = File.createTempFile("imagegen-reference-test", ".bin")
        try {
            target.writeBytes(byteArrayOf(99, 99))
            assertThrows(ImageGenReferenceTooLargeException::class.java) {
                copyImageGenReferenceWithinLimit(
                    input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6)),
                    target = target,
                    maxBytes = 5L
                )
            }

            assertFalse(target.exists())
        } finally {
            target.delete()
        }
    }

    @Test
    fun `streaming copy accepts payload exactly at cap`() {
        val target = File.createTempFile("imagegen-reference-test", ".bin")
        try {
            val copied = copyImageGenReferenceWithinLimit(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                target = target,
                maxBytes = 5L
            )

            assertEquals(5L, copied)
            assertEquals(5L, target.length())
        } finally {
            target.delete()
        }
    }

    @Test
    fun `removing Qwen source promotes the first readable reference in order`() {
        val selection = removePrimaryImageReference(
            primaryPath = "source.png",
            additionalPaths = listOf("missing.png", "second.png", "third.png"),
            promoteNext = true,
            isReadable = { it != "missing.png" }
        )

        assertEquals("second.png", selection.primaryPath)
        assertEquals(listOf("third.png"), selection.additionalPaths)
    }

    @Test
    fun `removing a source in a standard image mode clears the selection`() {
        val selection = removePrimaryImageReference(
            primaryPath = "source.png",
            additionalPaths = listOf("other.png"),
            promoteNext = false,
            isReadable = { true }
        )

        assertNull(selection.primaryPath)
        assertEquals(listOf("other.png"), selection.additionalPaths)
    }

    @Test
    fun `selection generation rejects work that completes after source removal`() {
        val captured = ImageGenSourceSelectionGeneration()
        val current = captured.next()

        assertTrue(current.accepts(current))
        assertFalse(current.accepts(captured))
    }

    @Test
    fun `cleanup never deletes a path promoted to Qwen primary`() {
        assertFalse(shouldDeleteImageGenRemovedPrimary("reference.png", "reference.png"))
        assertTrue(shouldDeleteImageGenRemovedPrimary("source.png", "reference.png"))
    }
}
