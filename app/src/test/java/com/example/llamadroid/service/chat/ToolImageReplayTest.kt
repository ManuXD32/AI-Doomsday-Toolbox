package com.example.llamadroid.service.chat

import com.example.llamadroid.service.NativeChatWorkspaceSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolImageReplayTest {
    private val steps = NativeChatSteps(
        steps = listOf(
            NativeChatStep(NativeChatStepType.NOTE, 0, "Image a.png (requested with chat_file_read)", role = "user", imagePath = "/x/a.png")
        )
    )

    @Test fun replayKeepsPictureWhenServerSeesImages() {
        val note = NativeChatStepHistory.expand("done", steps).first()
        assertEquals("/x/a.png", note.imagePath)
    }

    @Test fun replayDropsPictureWhenServerCannotSeeImages() {
        val note = NativeChatStepHistory.expand("done", steps, serverSeesImages = false).first()
        assertNull(note.imagePath)
        assertNull(note.images)
        assertTrue(note.content.endsWith(ToolImageResults.IMAGE_OMITTED))
    }

    @Test fun resultShapes() {
        assertTrue(ToolImageResults.shownContent("a.png").startsWith("Image a.png is shown to you below as a picture."))
        assertTrue(ToolImageResults.shownContent("a.png").endsWith(ToolImageResults.OWN_VISION_NOTE))
        assertEquals("Image a.png (requested with read_workspace_file)", ToolImageResults.noteText("a.png", "read_workspace_file"))
        assertTrue(ToolImageResults.describedContent("a.png", "cat").contains(ToolImageResults.MISTAKES_NOTE))
        assertTrue(ToolImageResults.describedContent("a.png", "cat").endsWith("\ncat"))
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun magicDetection() {
        assertEquals("image/png", NativeChatWorkspaceSupport.imageMimeFromMagic(bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 13, 10)))
        assertEquals("image/jpeg", NativeChatWorkspaceSupport.imageMimeFromMagic(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/gif", NativeChatWorkspaceSupport.imageMimeFromMagic("GIF89a".toByteArray()))
        assertEquals("image/webp", NativeChatWorkspaceSupport.imageMimeFromMagic("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("image/heic", NativeChatWorkspaceSupport.imageMimeFromMagic(bytes(0, 0, 0, 24) + "ftypheic".toByteArray()))
        assertNull(NativeChatWorkspaceSupport.imageMimeFromMagic("BM text file".toByteArray()))
        assertNull(NativeChatWorkspaceSupport.imageMimeFromMagic("hello world".toByteArray()))
        assertNull(NativeChatWorkspaceSupport.imageMimeFromMagic(ByteArray(0)))
    }
}
