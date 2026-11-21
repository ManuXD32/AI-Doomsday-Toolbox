package com.example.llamadroid.knowledge

import com.example.llamadroid.knowledge.legacy.LegacyDocReader
import com.example.llamadroid.knowledge.legacy.LegacyXlsReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** Text pulled out of an office document. [truncated] is true when the output cap was reached. */
data class ExtractedDocument(val text: String, val truncated: Boolean, val format: String)

/** Raised for unreadable or hostile documents (zip bomb, too many entries, malformed XML, doctype/entities). */
class DocumentExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Dependency-free text extraction for legacy DOC/XLS (see `legacy/`), DOCX, ODT, EPUB, XLSX, ODS and CSV/TSV (PDF stays on the PDFBox path).
 * Zip archives are bounded (entry count, per-entry and total uncompressed bytes) and XML is parsed with
 * DOCTYPE/entity expansion refused.
 */
object DocumentTextExtractor {
    const val MAX_ZIP_ENTRIES = 5_000
    const val MAX_ENTRY_BYTES = 40L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 160L * 1024 * 1024
    const val MAX_PLAIN_BYTES = 16L * 1024 * 1024
    const val DEFAULT_MAX_CHARS = 2_000_000

    private val extensions = setOf("docx", "odt", "epub", "xlsx", "ods", "csv", "tsv", "doc", "xls")
    private val mimeToFormat = mapOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/epub+zip" to "epub",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
        "text/csv" to "csv",
        "text/tab-separated-values" to "tsv",
        "application/msword" to "doc",
        "application/vnd.ms-excel" to "xls"
    )

    /** Mime types the document pickers should offer for these formats. */
    val pickerMimeTypes: List<String> = mimeToFormat.keys.toList()

    fun formatOf(name: String, mime: String?): String? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in extensions) return ext
        return mime?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()?.let { mimeToFormat[it] }
    }

    fun supports(name: String, mime: String?): Boolean = formatOf(name, mime) != null

    /** Returns null when the type is unsupported; throws [DocumentExtractionException] for bad/hostile files. */
    fun extract(file: File, name: String, mime: String?, maxChars: Int = DEFAULT_MAX_CHARS): ExtractedDocument? {
        val format = formatOf(name, mime) ?: return null
        val out = Out(maxChars)
        try {
            when (format) {
                "csv", "tsv" -> extractPlain(file, out)
                "doc" -> LegacyDocReader.extract(file) { out.append(it) }
                "xls" -> LegacyXlsReader.extract(file) { out.append(it) }
                else -> ZipFile(file).use { zip ->
                    val budget = Budget(zip)
                    when (format) {
                        "docx" -> extractDocx(zip, budget, out)
                        "odt" -> extractOdf(zip, budget, out, spreadsheet = false)
                        "ods" -> extractOdf(zip, budget, out, spreadsheet = true)
                        "epub" -> extractEpub(zip, budget, out)
                        else -> extractXlsx(zip, budget, out)
                    }
                }
            }
        } catch (e: OutputFull) {
            // Cap reached: keep what we have.
        } catch (e: DocumentExtractionException) {
            throw e
        } catch (e: Exception) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is OutputFull }) {
                return ExtractedDocument(out.toString().trim(), true, format)
            }
            throw DocumentExtractionException("Cannot read $format document: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return ExtractedDocument(out.toString().trim(), out.full, format)
    }

    // ---- bounded output / zip budget ----

    private class OutputFull : SAXException("output cap")

    private class Out(private val max: Int) {
        private val sb = StringBuilder()
        var full = false
            private set

        fun append(s: CharSequence) {
            val room = max - sb.length
            if (s.length >= room) {
                sb.append(s, 0, room.coerceAtLeast(0))
                full = true
                throw OutputFull()
            }
            sb.append(s)
        }

        fun append(c: Char) = append(c.toString())

        fun markFull() {
            full = true
        }
        override fun toString(): String = sb.toString()
    }

    private class Budget(zip: ZipFile) {
        var total = 0L

        init {
            var count = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                entries.nextElement()
                if (++count > MAX_ZIP_ENTRIES) {
                    throw DocumentExtractionException("Archive has too many entries (more than $MAX_ZIP_ENTRIES).")
                }
            }
        }

        fun read(zip: ZipFile, entryName: String): ByteArray? {
            val entry = zip.getEntry(entryName) ?: return null
            if (entry.isDirectory) return null
            if (entry.size > MAX_ENTRY_BYTES) throw DocumentExtractionException("Entry $entryName is too large.")
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var size = 0L
            zip.getInputStream(entry).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    total += n
                    if (size > MAX_ENTRY_BYTES) throw DocumentExtractionException("Entry $entryName expands too much.")
                    if (total > MAX_TOTAL_BYTES) throw DocumentExtractionException("Archive expands too much.")
                    out.write(buffer, 0, n)
                }
            }
            return out.toByteArray()
        }
    }

    // ---- XML ----

    private fun parseXml(bytes: ByteArray, handler: DefaultHandler) {
        // Cheap, parser-independent guard: no DOCTYPE or ENTITY declarations at all (XXE / billion laughs).
        val head = String(bytes, Charsets.ISO_8859_1)
        if (head.contains("<!DOCTYPE", ignoreCase = true) || head.contains("<!ENTITY", ignoreCase = true)) {
            throw DocumentExtractionException("XML with DOCTYPE or entity declarations is refused.")
        }
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val parser = factory.newSAXParser()
        val reader = parser.xmlReader
        reader.contentHandler = handler
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(java.io.StringReader("")) }
        reader.parse(InputSource(ByteArrayInputStream(bytes) as InputStream))
    }

    private fun local(qName: String): String = qName.substringAfter(':')

    private fun attr(a: Attributes, name: String): String? {
        for (i in 0 until a.length) if (local(a.getQName(i)) == name) return a.getValue(i)
        return null
    }

    // ---- CSV / TSV ----

    private fun extractPlain(file: File, out: Out) {
        val buf = ByteArray(minOf(file.length(), MAX_PLAIN_BYTES).toInt())
        file.inputStream().use { input ->
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
        }
        var text = String(buf, Charsets.UTF_8)
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        out.append(text)
        if (file.length() > MAX_PLAIN_BYTES) out.markFull()
    }

    // ---- DOCX ----

    private fun extractDocx(zip: ZipFile, budget: Budget, out: Out) {
        val bytes = budget.read(zip, "word/document.xml")
            ?: throw DocumentExtractionException("Not a DOCX file (word/document.xml missing).")
        parseXml(bytes, object : DefaultHandler() {
            var inText = false
            override fun startElement(uri: String?, l: String?, q: String, a: Attributes) {
                when (local(q)) {
                    "t" -> inText = true
                    "tab" -> out.append('\t')
                    "br", "cr" -> out.append('\n')
                }
            }

            override fun endElement(uri: String?, l: String?, q: String) {
                when (local(q)) {
                    "t" -> inText = false
                    "p" -> out.append('\n')
                    "tc" -> out.append('\t')
                    "tr" -> out.append('\n')
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) out.append(String(ch, start, length))
            }
        })
    }

    // ---- ODT / ODS ----

    private fun extractOdf(zip: ZipFile, budget: Budget, out: Out, spreadsheet: Boolean) {
        val bytes = budget.read(zip, "content.xml")
            ?: throw DocumentExtractionException("Not an OpenDocument file (content.xml missing).")
        parseXml(bytes, object : DefaultHandler() {
            var cellDepth = 0
            var pDepth = 0
            val row = StringBuilder()

            private fun emit(s: CharSequence) {
                if (spreadsheet && cellDepth > 0) row.append(s) else out.append(s)
            }

            override fun startElement(uri: String?, l: String?, q: String, a: Attributes) {
                when (q) {
                    "table:table" -> if (spreadsheet) out.append("## ${attr(a, "name").orEmpty()}\n")
                    "table:table-row" -> row.setLength(0)
                    "table:table-cell", "table:covered-table-cell" -> if (spreadsheet) cellDepth++
                    "text:p", "text:h" -> pDepth++
                    "text:tab" -> emit("\t")
                    "text:line-break" -> emit(if (cellDepth > 0) " " else "\n")
                    "text:s" -> emit(" ".repeat((attr(a, "c")?.toIntOrNull() ?: 1).coerceIn(1, 200)))
                }
            }

            override fun endElement(uri: String?, l: String?, q: String) {
                when (q) {
                    "text:p", "text:h" -> {
                        pDepth = (pDepth - 1).coerceAtLeast(0)
                        if (cellDepth > 0) row.append(' ') else if (!spreadsheet) out.append('\n')
                    }
                    "table:table-cell", "table:covered-table-cell" -> if (spreadsheet) {
                        cellDepth--
                        while (row.isNotEmpty() && row.last() == ' ') row.setLength(row.length - 1)
                        row.append('\t')
                    }
                    "table:table-row" -> if (spreadsheet) {
                        val line = row.toString().trimEnd('\t', ' ')
                        if (line.isNotEmpty()) out.append(line + "\n")
                        row.setLength(0)
                    } else out.append('\n')
                    "table:table" -> if (spreadsheet) out.append('\n')
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (pDepth > 0 && (!spreadsheet || cellDepth > 0)) emit(String(ch, start, length))
            }
        })
    }

    // ---- EPUB ----

    private fun extractEpub(zip: ZipFile, budget: Budget, out: Out) {
        val container = budget.read(zip, "META-INF/container.xml")
        var opfPath: String? = null
        if (container != null) {
            parseXml(container, object : DefaultHandler() {
                override fun startElement(uri: String?, l: String?, q: String, a: Attributes) {
                    if (local(q) == "rootfile" && opfPath == null) opfPath = attr(a, "full-path")
                }
            })
        }
        val ordered = ArrayList<String>()
        val opfFile = opfPath
        if (opfFile != null) {
            val opfBytes = budget.read(zip, opfFile)
            if (opfBytes != null) {
                val manifest = HashMap<String, String>()
                val spine = ArrayList<String>()
                parseXml(opfBytes, object : DefaultHandler() {
                    override fun startElement(uri: String?, l: String?, q: String, a: Attributes) {
                        when (local(q)) {
                            "item" -> {
                                val id = attr(a, "id")
                                val href = attr(a, "href")
                                if (id != null && href != null) manifest[id] = href
                            }
                            "itemref" -> attr(a, "idref")?.let { spine += it }
                        }
                    }
                })
                val base = opfFile.substringBeforeLast('/', "")
                for (id in spine) {
                    val href = manifest[id] ?: continue
                    val clean = java.net.URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
                    ordered += normalizePath(if (base.isEmpty()) clean else "$base/$clean")
                }
            }
        }
        if (ordered.isEmpty()) {
            val names = ArrayList<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val n = entries.nextElement().name
                val ext = n.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext == "xhtml" || ext == "html" || ext == "htm") names += n
            }
            ordered += names.sorted()
        }
        for (name in ordered) {
            val bytes = budget.read(zip, name) ?: continue
            out.append(htmlToText(String(bytes, Charsets.UTF_8)))
            out.append("\n\n")
        }
    }

    private fun normalizePath(path: String): String {
        val parts = ArrayList<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += p
        }
        return parts.joinToString("/")
    }

    private val scriptStyle = Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val blockEnd = Regex("</?(p|div|br|h[1-6]|li|tr|section|blockquote|pre|table|ul|ol)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("<[^>]*>")

    internal fun htmlToText(html: String): String {
        var s = scriptStyle.replace(html, " ")
        s = blockEnd.replace(s, "\n")
        s = anyTag.replace(s, "")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#160;", " ").replace("&amp;", "&")
        return s.lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    // ---- XLSX ----

    private fun extractXlsx(zip: ZipFile, budget: Budget, out: Out) {
        val shared = ArrayList<String>()
        budget.read(zip, "xl/sharedStrings.xml")?.let { bytes ->
            parseXml(bytes, object : DefaultHandler() {
                var sb: StringBuilder? = null
                var inT = false
                var phonetic = 0
                override fun startElement(uri: String?, l: String?, q: String, a: Attributes) {
                    when (local(q)) {
                        "si" -> sb = StringBuilder()
                        "rPh" -> phonetic++
                        "t" -> inT = true
                    }
                }

                override fun endElement(uri: String?, l: String?, q: String) {
                    when (local(q)) {
                        "si" -> { shared += sb?.toString().orEmpty(); sb = null }
                        "rPh" -> phonetic--
                        "t" -> inT = false
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inT && phonetic == 0) sb?.append(ch, start, length)
                }
            })
        }
        // Sheet name -> path, in workbook order.
        val rels = HashMap<String, String>()
        budget.read(zip, "xl/_rels/workbook.xml.rels")?.let { bytes ->
            parseXml(bytes, object : DefaultHandler() {
                override fun startElement(uri: String?, l: String?, q: String, a: Attributes) {
                    if (local(q) == "Relationship") {
                        val id = attr(a, "Id")
                        val target = attr(a, "Target")
                        if (id != null && target != null) {
                            rels[id] = if (target.startsWith("/")) target.trimStart('/') else normalizePath("xl/$target")
                        }
                    }
                }
            })
        }
        val sheets = ArrayList<Pair<String, String>>()
        budget.read(zip, "xl/workbook.xml")?.let { bytes ->
            parseXml(bytes, object : DefaultHandler() {
                override fun startElement(uri: String?, l: String?, q: String, a: Attributes) {
                    if (local(q) == "sheet") {
                        val rid = attr(a, "id")
                        val path = rid?.let { rels[it] }
                        if (path != null) sheets += (attr(a, "name") ?: "Sheet${sheets.size + 1}") to path
                    }
                }
            })
        }
        if (sheets.isEmpty()) {
            val names = ArrayList<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val n = entries.nextElement().name
                if (n.startsWith("xl/worksheets/") && n.endsWith(".xml")) names += n
            }
            names.sorted().forEachIndexed { i, n -> sheets += "Sheet${i + 1}" to n }
        }
        if (sheets.isEmpty()) throw DocumentExtractionException("Not an XLSX file (no worksheets).")
        for ((sheetName, path) in sheets) {
            val bytes = budget.read(zip, path) ?: continue
            out.append("## $sheetName\n")
            parseXml(bytes, XlsxSheetHandler(shared, out))
            out.append('\n')
        }
    }

    private class XlsxSheetHandler(private val shared: List<String>, private val out: Out) : DefaultHandler() {
        var cellType: String? = null
        var col = 0
        var nextCol = 0
        var lastCol = -1
        var inV = false
        var inInlineT = false
        val value = StringBuilder()
        val line = StringBuilder()

        override fun startElement(uri: String?, l: String?, q: String, a: Attributes) {
            when (local(q)) {
                "row" -> { line.setLength(0); lastCol = -1; nextCol = 0 }
                "c" -> {
                    cellType = attr(a, "t")
                    col = attr(a, "r")?.let { columnIndex(it) } ?: nextCol
                    nextCol = col + 1
                    value.setLength(0)
                }
                "v" -> inV = true
                "t" -> inInlineT = cellType == "inlineStr"
            }
        }

        override fun endElement(uri: String?, l: String?, q: String) {
            when (local(q)) {
                "v" -> inV = false
                "t" -> inInlineT = false
                "c" -> {
                    var text = value.toString()
                    when (cellType) {
                        "s" -> text = text.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        "b" -> text = if (text.trim() == "1") "TRUE" else "FALSE"
                    }
                    text = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                    if (text.isNotEmpty()) {
                        val tabs = if (lastCol >= 0) col - lastCol else col
                        repeat(tabs.coerceIn(0, 500)) { line.append('\t') }
                        line.append(text)
                        lastCol = col
                    }
                }
                "row" -> {
                    if (line.isNotEmpty()) out.append(line.toString() + "\n")
                    line.setLength(0)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inV || inInlineT) value.append(ch, start, length)
        }

        private fun columnIndex(ref: String): Int {
            var n = 0
            for (c in ref) {
                if (c in 'A'..'Z') n = n * 26 + (c - 'A' + 1) else if (c in 'a'..'z') n = n * 26 + (c - 'a' + 1) else break
            }
            return (n - 1).coerceAtLeast(0)
        }
    }
}
