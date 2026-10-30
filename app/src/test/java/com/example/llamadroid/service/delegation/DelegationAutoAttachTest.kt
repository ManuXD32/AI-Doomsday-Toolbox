package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationAutoAttachTest {
    private fun row(id: Long) = LlamaMessageAttachmentEntity(
        id = id, messageId = 7, chatId = 1, path = "/x/$id", displayName = "n$id", mimeType = "application/zip", sizeBytes = 1
    )

    @Test
    fun selectsAttachmentsThenMessageImage() {
        assertEquals(listOf("f5", "f6", "img7"), DelegationAutoAttach.refsFor(7, "/img.png", listOf(row(5), row(6))))
    }

    @Test
    fun emptyWhenNothingAttached() {
        assertTrue(DelegationAutoAttach.refsFor(7, null, emptyList()).isEmpty())
        assertTrue(DelegationAutoAttach.refsFor(7, " ", emptyList()).isEmpty())
    }

    @Test
    fun selectionIsBounded() {
        val rows = (1L..60L).map { row(it) }
        assertEquals(DelegationToolArgs.MAX_ATTACHMENTS, DelegationAutoAttach.refsFor(7, "/i.png", rows).size)
    }

    @Test
    fun messageMentionsAttached() {
        assertTrue(DelegationAutoAttach.mentionsAttached("Please use the attached file"))
        assertTrue(DelegationAutoAttach.mentionsAttached("revisa el archivo adjunto"))
        assertFalse(DelegationAutoAttach.mentionsAttached("make the button blue"))
    }
}
