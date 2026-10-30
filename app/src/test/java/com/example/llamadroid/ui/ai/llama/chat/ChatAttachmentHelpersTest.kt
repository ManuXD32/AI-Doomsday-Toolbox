package com.example.llamadroid.ui.ai.llama.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAttachmentHelpersTest {
    @Test fun sizeFormatting() {
        assertEquals("0 B", formatAttachmentSize(0))
        assertEquals("1023 B", formatAttachmentSize(1023))
        assertEquals("1.0 KB", formatAttachmentSize(1024))
        assertEquals("1.5 MB", formatAttachmentSize(1_572_864))
        assertEquals("2.0 GB", formatAttachmentSize(2L * 1024 * 1024 * 1024))
        assertEquals("0 B", formatAttachmentSize(-5))
    }

    @Test fun kinds() {
        assertEquals(AttachmentKind.IMAGE, attachmentKindForMime("image/png", "a.png"))
        assertEquals(AttachmentKind.PDF, attachmentKindForMime("application/octet-stream", "r.PDF"))
        assertEquals(AttachmentKind.ARCHIVE, attachmentKindForMime("application/zip", "a.zip"))
        assertEquals(AttachmentKind.ARCHIVE, attachmentKindForMime("", "x.tar"))
        assertEquals(AttachmentKind.TEXT, attachmentKindForMime("text/plain", "n"))
        assertEquals(AttachmentKind.TEXT, attachmentKindForMime("", "a.json"))
        assertEquals(AttachmentKind.OTHER, attachmentKindForMime("application/octet-stream", "a.bin"))
    }

    @Test fun tokenCounts() {
        assertEquals("950", formatTokenCount(950))
        assertEquals("1.0k", formatTokenCount(1000))
        assertEquals("12.4k", formatTokenCount(12_400))
        assertEquals("1.2M", formatTokenCount(1_200_000))
        assertEquals("0", formatTokenCount(-3))
        assertEquals("1.0M", formatTokenCount(999_999))
    }
}
