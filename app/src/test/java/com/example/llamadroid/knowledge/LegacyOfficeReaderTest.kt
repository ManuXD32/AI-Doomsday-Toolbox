package com.example.llamadroid.knowledge

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyOfficeReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixtureBytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("legacy/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private fun fixture(name: String): File = tmp.newFile(name).also { it.writeBytes(fixtureBytes(name)) }

    private fun extract(f: File, maxChars: Int = DocumentTextExtractor.DEFAULT_MAX_CHARS) =
        DocumentTextExtractor.extract(f, f.name, null, maxChars)

    private fun assertRejected(f: File) {
        try {
            extract(f)
            fail("expected DocumentExtractionException")
        } catch (_: DocumentExtractionException) {
        }
    }

    @Test
    fun supportsLegacyTypes() {
        assertTrue(DocumentTextExtractor.supports("a.doc", null))
        assertTrue(DocumentTextExtractor.supports("a.XLS", null))
        assertTrue(DocumentTextExtractor.supports("blob", "application/msword"))
        assertTrue(DocumentTextExtractor.supports("blob", "application/vnd.ms-excel"))
        assertTrue("application/msword" in DocumentTextExtractor.pickerMimeTypes)
        assertTrue("application/vnd.ms-excel" in DocumentTextExtractor.pickerMimeTypes)
    }

    @Test
    fun docTextFromPieceTable() {
        val doc = extract(fixture("Bug28627.doc"))
        assertNotNull(doc)
        assertEquals("doc", doc!!.format)
        assertEquals(
            "This document is going to have things deleted from it\n\n" +
                "This paragraph will be found on {delete me} and removed\n\n" +
                "This paragraph will have its bold text removed\n\nThis one will be fine",
            doc.text
        )
    }

    @Test
    fun docKeepsPunctuationAndTabs() {
        val text = extract(fixture("HeaderFooterUnicode.doc"))!!.text
        assertTrue(text.contains("EUR - €"))
        assertTrue(text.contains("\tMolière\n"))
        assertTrue(text.contains("That’s it for page one"))
        assertTrue(text.endsWith("The end."))
        assertFalse(text.any { it < ' ' && it != '\n' && it != '\t' })
    }

    @Test
    fun xlsSheetNamesAndCells() {
        val doc = extract(fixture("SampleSS.xls"))!!
        assertEquals("xls", doc.format)
        assertEquals(
            "## First Sheet\nTest spreadsheet\n2nd row\t2nd row 2nd column\nThis one is red\n\n" +
                "## Sheet Number 2\nStart of 2nd sheet\nSheet 2 row 2\nI'm in bold blue, on a yellow background\n" +
                "cb=1\tcb=10\tcb=2\tcb=sum\n1\t10\t2\t13\n\n## Sheet3",
            doc.text
        )
    }

    @Test
    fun xlsNumbersKeepColumnPositions() {
        val text = extract(fixture("SimpleMultiCell.xls"))!!.text
        assertEquals("## Sheet1\n1\n2\t3\n4\t5\t6\n7\t8\t9\t10\n11\t12\t13\t14\t15\n\n## Sheet2\n\n## Sheet3", text)
    }

    @Test
    fun encryptedWorkbookIsRefused() {
        try {
            extract(fixture("xor-encryption-abc.xls"))
            fail("expected DocumentExtractionException")
        } catch (e: DocumentExtractionException) {
            assertTrue(e.message!!.contains("encrypted"))
        }
    }

    @Test
    fun outputCapTruncates() {
        val doc = extract(fixture("Bug28627.doc"), maxChars = 20)!!
        assertTrue(doc.truncated)
        assertEquals(20, doc.text.length)
    }

    @Test
    fun nonCompoundAndTruncatedFilesAreRejected() {
        val junk = tmp.newFile("junk.doc").also { it.writeBytes(ByteArray(2048) { 7 }) }
        assertRejected(junk)
        val short = tmp.newFile("short.xls").also { it.writeBytes(fixtureBytes("SampleSS.xls").copyOf(700)) }
        assertRejected(short)
        val empty = tmp.newFile("empty.doc")
        assertRejected(empty)
    }

    @Test
    fun loopingDirectoryChainIsRejected() {
        val bytes = fixtureBytes("Bug28627.doc")
        fun i32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
            ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
        val dirSector = i32(0x30)
        val fatSector = i32(0x4C)
        val entryOffset = (fatSector + 1) * 512 + dirSector * 4
        // Point the directory sector at itself.
        for (k in 0 until 4) bytes[entryOffset + k] = ((dirSector shr (8 * k)) and 0xFF).toByte()
        val f = tmp.newFile("loop.doc").also { it.writeBytes(bytes) }
        assertRejected(f)
    }

    @Test
    fun sectorIndexBeyondFileIsRejected() {
        val bytes = fixtureBytes("SampleSS.xls")
        // First directory sector id far outside the file.
        bytes[0x30] = 0xFF.toByte(); bytes[0x31] = 0xFF.toByte(); bytes[0x32] = 0x00; bytes[0x33] = 0x00
        assertRejected(tmp.newFile("far.xls").also { it.writeBytes(bytes) })
    }

    @Test
    fun oversizedFileIsRefusedWithoutReading() {
        val f = tmp.newFile("huge.doc")
        RandomAccessFile(f, "rw").use { it.setLength(65L * 1024 * 1024) }
        assertRejected(f)
    }
}
