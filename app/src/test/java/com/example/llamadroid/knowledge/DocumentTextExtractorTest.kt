package com.example.llamadroid.knowledge

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentTextExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(name: String, entries: List<Pair<String, String>>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, c) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry()
            }
        }
        return f
    }

    @Test
    fun supportsByExtensionAndMime() {
        assertTrue(DocumentTextExtractor.supports("a.DOCX", null))
        assertTrue(DocumentTextExtractor.supports("blob", "application/epub+zip"))
        assertFalse(DocumentTextExtractor.supports("a.pdf", "application/pdf"))
        assertFalse(DocumentTextExtractor.supports("a.txt", "text/plain"))
        assertNull(DocumentTextExtractor.extract(tmp.newFile("x.bin"), "x.bin", null))
    }

    @Test
    fun docxParagraphsAndTables() {
        val xml = """<w:document xmlns:w="w"><w:body><w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t> world</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"""
        val doc = DocumentTextExtractor.extract(zip("a.docx", listOf("word/document.xml" to xml)), "a.docx", null)!!
        assertTrue(doc.text.startsWith("Hello world"))
        assertTrue(doc.text.contains("A\n\tB"))
        assertTrue(doc.text.contains("B"))
        assertEquals("docx", doc.format)
    }

    @Test
    fun odtParagraphs() {
        val xml = """<office:document-content xmlns:office="o" xmlns:text="t"><office:body><office:text>
            <text:h>Title</text:h><text:p>One<text:s text:c="2"/>two</text:p></office:text></office:body></office:document-content>"""
        val doc = DocumentTextExtractor.extract(zip("a.odt", listOf("content.xml" to xml)), "a.odt", null)!!
        assertEquals("Title\nOne  two", doc.text)
    }

    @Test
    fun odsRowsPerSheet() {
        val xml = """<office:document-content xmlns:office="o" xmlns:table="t" xmlns:text="x"><office:body><office:spreadsheet>
            <table:table table:name="Data"><table:table-row><table:table-cell><text:p>a</text:p></table:table-cell><table:table-cell><text:p>b</text:p></table:table-cell></table:table-row>
            <table:table-row><table:table-cell><text:p>1</text:p></table:table-cell><table:table-cell><text:p>2</text:p></table:table-cell></table:table-row></table:table>
            </office:spreadsheet></office:body></office:document-content>"""
        val doc = DocumentTextExtractor.extract(zip("a.ods", listOf("content.xml" to xml)), "a.ods", null)!!
        assertEquals("## Data\na\tb\n1\t2", doc.text)
    }

    @Test
    fun epubFollowsSpineOrder() {
        val container = """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""
        val opf = """<package><manifest><item id="c1" href="ch1.xhtml"/><item id="c2" href="ch2.xhtml"/></manifest>
            <spine><itemref idref="c2"/><itemref idref="c1"/></spine></package>"""
        val f = zip("a.epub", listOf(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf,
            "OEBPS/ch1.xhtml" to "<html><body><p>First &amp; best</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><style>p{}</style><h1>Second</h1></body></html>"
        ))
        val doc = DocumentTextExtractor.extract(f, "a.epub", null)!!
        assertTrue(doc.text.indexOf("Second") in 0 until doc.text.indexOf("First & best"))
    }

    @Test
    fun xlsxSharedStringsAndGaps() {
        val shared = """<sst><si><t>Name</t></si><si><t>Qty</t></si><si><t>Pen</t></si></sst>"""
        val workbook = """<workbook xmlns:r="r"><sheets><sheet name="Stock" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        val rels = """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>"""
        val sheet = """<worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
            <row r="2"><c r="A2" t="s"><v>2</v></c><c r="C2"><v>5</v></c></row></sheetData></worksheet>"""
        val f = zip("a.xlsx", listOf(
            "xl/sharedStrings.xml" to shared, "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to rels, "xl/worksheets/sheet1.xml" to sheet
        ))
        val doc = DocumentTextExtractor.extract(f, "a.xlsx", null)!!
        assertEquals("## Stock\nName\t\tQty\nPen\t\t5", doc.text)
    }

    @Test
    fun csvKeepsRowsAndStripsBom() {
        val f = tmp.newFile("a.csv").apply { writeText("﻿a,b\n1,2\n") }
        assertEquals("a,b\n1,2", DocumentTextExtractor.extract(f, "a.csv", null)!!.text)
    }

    @Test
    fun outputCapTruncates() {
        val f = tmp.newFile("big.csv").apply { writeText("x".repeat(500)) }
        val doc = DocumentTextExtractor.extract(f, "big.csv", null, maxChars = 100)!!
        assertTrue(doc.truncated)
        assertEquals(100, doc.text.length)
    }

    @Test
    fun entityDeclarationsAreRefused() {
        val xml = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]><w:document xmlns:w="w"><w:p><w:t>&x;</w:t></w:p></w:document>"""
        expectRefusal { DocumentTextExtractor.extract(zip("x.docx", listOf("word/document.xml" to xml)), "x.docx", null) }
    }

    @Test
    fun tooManyEntriesRefused() {
        val entries = (0..DocumentTextExtractor.MAX_ZIP_ENTRIES + 1).map { "f$it.txt" to "" } + ("word/document.xml" to "<a/>")
        expectRefusal { DocumentTextExtractor.extract(zip("m.docx", entries), "m.docx", null) }
    }

    @Test
    fun oversizedEntryRefused() {
        val big = "<w:document xmlns:w=\"w\">" + "<w:p/>".repeat(8_000_000) + "</w:document>" // ~48 MB expanded, tiny compressed
        expectRefusal { DocumentTextExtractor.extract(zip("b.docx", listOf("word/document.xml" to big)), "b.docx", null) }
    }

    @Test
    fun notAZipIsAnExtractionError() {
        val f = tmp.newFile("bad.docx").apply { writeText("plain text") }
        expectRefusal { DocumentTextExtractor.extract(f, "bad.docx", null) }
        assertNotNull(DocumentTextExtractor.pickerMimeTypes.firstOrNull())
    }

    private fun expectRefusal(block: () -> Unit) {
        try {
            block(); fail("expected DocumentExtractionException")
        } catch (_: DocumentExtractionException) {
        }
    }
}
