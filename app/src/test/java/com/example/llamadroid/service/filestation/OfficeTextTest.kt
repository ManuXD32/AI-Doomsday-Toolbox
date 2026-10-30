package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.tools.OfficeText
import com.example.llamadroid.service.filestation.volume.FsException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OfficeTextTest {
    private fun zip(name: String, xml: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use {
            it.putNextEntry(ZipEntry(name))
            it.write(xml.toByteArray())
            it.closeEntry()
        }
        return bos.toByteArray()
    }

    private val docx = """<?xml version="1.0" encoding="UTF-8"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Title &amp; more</w:t></w:r></w:p>
<w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>world</w:t></w:r><w:r><w:br/><w:t>next</w:t></w:r></w:p>
<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>one</w:t></w:r></w:p>
<w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>two</w:t></w:r></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:p><w:r><w:t>A2</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B2</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
<w:p><w:r><w:t>end</w:t></w:r></w:p>
</w:body></w:document>"""

    private val odt = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0">
<office:automatic-styles><text:p>ignored style text</text:p></office:automatic-styles>
<office:body><office:text>
<text:h text:outline-level="2">Heading</text:h>
<text:p>Some<text:s text:c="2"/>text<text:line-break/>more</text:p>
<text:list><text:list-item><text:p>item1</text:p></text:list-item><text:list-item><text:p>item2</text:p></text:list-item></text:list>
<table:table><table:table-row><table:table-cell><text:p>c1</text:p></table:table-cell><table:table-cell><text:p>c2</text:p></table:table-cell></table:table-row></table:table>
<text:p>tail</text:p>
</office:text></office:body></office:document-content>"""

    @Test fun `docx text keeps headings lists and tables`() {
        val text = OfficeText.toText(OfficeText.Kind.DOCX, ByteArrayInputStream(zip("word/document.xml", docx)))
        assertTrue(text, text.startsWith("Title & more\n\nHello world\nnext\n\n- one\n  - two\n\nA1\tB1\nA2\tB2\n\nend"))
    }

    @Test fun `docx html has structure and escapes`() {
        val html = OfficeText.toHtml(OfficeText.Kind.DOCX, ByteArrayInputStream(zip("word/document.xml", docx)))
        assertTrue(html.contains("<h1>Title &amp; more</h1>"))
        assertTrue(html.contains("<p>Hello world<br>next</p>"))
        assertTrue(html.contains("<ul>\n<li>one</li>\n<li>two</li>\n</ul>"))
        assertTrue(html.contains("<td>A2</td><td>B2</td>"))
    }

    @Test fun `odt text ignores styles and handles spaces lists tables`() {
        val text = OfficeText.toText(OfficeText.Kind.ODT, ByteArrayInputStream(zip("content.xml", odt)))
        assertFalse(text.contains("ignored"))
        assertEquals("Heading\n\nSome  text\nmore\n\n- item1\n- item2\n\nc1\tc2\n\ntail\n", text)
        val html = OfficeText.toHtml(OfficeText.Kind.ODT, ByteArrayInputStream(zip("content.xml", odt)))
        assertTrue(html.contains("<h2>Heading</h2>"))
        assertTrue(html.contains("<li>item2</li>"))
    }

    @Test fun `doctype and entities are rejected`() {
        val evil = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>"""
        try {
            OfficeText.toText(OfficeText.Kind.DOCX, ByteArrayInputStream(zip("word/document.xml", evil)))
            fail("expected rejection")
        } catch (e: FsException) {
            assertTrue(e.message!!.contains("DOCTYPE"))
        }
    }

    @Test fun `missing entry and oversize entry fail cleanly`() {
        try {
            OfficeText.toText(OfficeText.Kind.DOCX, ByteArrayInputStream(zip("other.xml", "<a/>")))
            fail()
        } catch (e: FsException) { assertTrue(e.message!!.contains("missing")) }
        try {
            OfficeText.toText(OfficeText.Kind.DOCX, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            fail()
        } catch (e: FsException) { /* not a zip */ }
    }

    @Test fun `extension mapping`() {
        assertEquals(OfficeText.Kind.DOCX, OfficeText.kindForExtension("DOCX"))
        assertEquals(OfficeText.Kind.ODT, OfficeText.kindForExtension(".odt"))
        assertEquals(null, OfficeText.kindForExtension("doc"))
    }
}
