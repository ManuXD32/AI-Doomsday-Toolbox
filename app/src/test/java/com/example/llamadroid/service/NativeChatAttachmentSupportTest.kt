package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class NativeChatAttachmentSupportTest {
    @get:Rule val tmp = TemporaryFolder()
    private val support = NativeChatAttachmentSupport

    @Test fun parsesFileImageAndZipEntryIds() {
        assertEquals(ChatFileRef.Attachment(12), support.parseId("f12"))
        assertEquals(ChatFileRef.Attachment(12), support.parseId(" F12 "))
        assertEquals(ChatFileRef.MessageImage(55), support.parseId("img55"))
        assertEquals(ChatFileRef.Attachment(12, "src/a b.txt"), support.parseId("f12:src/a b.txt"))
    }

    @Test fun rejectsInvalidIdsAndTraversal() {
        assertNull(support.parseId(null))
        assertNull(support.parseId(""))
        assertNull(support.parseId("12"))
        assertNull(support.parseId("f"))
        assertNull(support.parseId("f12abc"))
        assertNull(support.parseId("img55:x"))
        assertNull(support.parseId("f12:../secret"))
        assertNull(support.parseId("f12:a/../../b"))
        assertNull(support.parseId("f12:/etc/passwd"))
        assertNull(support.parseId("f12:a\\b"))
        assertNull(support.parseId("f12:"))
    }

    @Test fun confinesPathsToTheChatDirectory() {
        val dir = tmp.newFolder("chat1")
        val inside = File(dir, "a.txt").apply { writeText("x") }
        val sibling = tmp.newFolder("chat10")
        val outside = File(sibling, "b.txt").apply { writeText("x") }
        assertTrue(support.isInside(dir, inside))
        assertFalse(support.isInside(dir, outside))
        assertFalse(support.isInside(dir, File(dir, "../chat10/b.txt")))
    }

    @Test fun acceptsFilesUnderEitherRoot() {
        val workspace = tmp.newFolder("ws")
        val legacy = tmp.newFolder("legacy")
        val other = tmp.newFolder("other")
        val a = File(workspace, "attachments").apply { mkdirs() }.let { File(it, "a.txt").apply { writeText("x") } }
        val b = File(legacy, "b.txt").apply { writeText("x") }
        val c = File(other, "c.txt").apply { writeText("x") }
        assertTrue(support.isInsideAny(listOf(workspace, legacy), a))
        assertTrue(support.isInsideAny(listOf(workspace, legacy), b))
        assertFalse(support.isInsideAny(listOf(workspace, legacy), c))
    }

    @Test fun pagesTextWithFooter() {
        val text = "a".repeat(15_000)
        val first = support.page(text, null, null)
        assertTrue(first.endsWith("(chars 0–6000 of 15000; continue with offset=6000)"))
        val big = support.page(text, 0, 50_000)
        assertTrue(big.contains("(chars 0–12000 of 15000; continue with offset=12000)"))
        val last = support.page(text, 12_000, 6000)
        assertTrue(last.endsWith("(chars 12000–15000 of 15000; end of file)"))
        assertTrue(support.page(text, 20_000, null).contains("past the end"))
        assertEquals("(empty file)", support.page("", 0, null))
        assertTrue(support.page("abc", 0, null, truncatedSource = true).contains("at least 3"))
    }

    @Test fun sniffsBinaryContent() {
        assertFalse(support.looksBinary("hello\nworld\ttab ñ é 日本語".toByteArray()))
        assertTrue(support.looksBinary(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01)))
        assertTrue(support.looksBinary(ByteArray(100) { 1 }))
        assertFalse(support.looksBinary(ByteArray(0)))
        assertTrue(support.isTextLike("notes.md", "application/octet-stream"))
        assertTrue(support.isTextLike("x", "application/json"))
        assertFalse(support.isTextLike("a.bin", "application/octet-stream"))
    }

    @Test fun zipListingIsBoundedAndSkipsDirectories() {
        val file = tmp.newFile("a.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("dir/")); zip.closeEntry()
            repeat(250) { i ->
                zip.putNextEntry(ZipEntry("dir/f$i.txt")); zip.write("hi".toByteArray()); zip.closeEntry()
            }
        }
        val listing = ZipFile(file).use { support.zipListing(it, "a.zip") }
        assertTrue(listing.contains("250 file(s)"))
        assertTrue(listing.contains("dir/f199.txt (2 B)"))
        assertFalse(listing.contains("dir/f200.txt"))
        assertTrue(listing.contains("50 more entries not listed"))
    }

    @Test fun readCappedReportsTruncation() {
        val file = tmp.newFile("big.txt").apply { writeBytes(ByteArray(100) { 'a'.code.toByte() }) }
        val (bytes, cut) = support.readCapped(file, 40)
        assertEquals(40, bytes.size)
        assertTrue(cut)
        val (all, cut2) = support.readCapped(file, 100)
        assertEquals(100, all.size)
        assertFalse(cut2)
    }
}
