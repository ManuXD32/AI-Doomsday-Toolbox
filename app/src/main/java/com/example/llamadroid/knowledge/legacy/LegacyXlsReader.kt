package com.example.llamadroid.knowledge.legacy

import com.example.llamadroid.knowledge.DocumentExtractionException
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.nio.charset.Charset
import java.util.TreeMap

/** Text extraction for Excel 97-2003 `.xls` (BIFF8): one `## Sheet` block per worksheet with tab-separated rows. */
object LegacyXlsReader {
    private const val MAX_CELLS = 2_000_000
    private const val MAX_SHEETS = 1_000
    private const val MAX_SST = 4_000_000
    private const val MAX_COLS = 16_384
    private val CP1252: Charset = Charset.forName("windows-1252")

    private class Rec(val id: Int, val off: Int, val len: Int)

    private class Sheet(val name: String, val pos: Long)

    fun extract(file: File, sink: (CharSequence) -> Unit) {
        val cfb = CompoundFile.open(file)
        val wb = cfb.readStream("Workbook") ?: cfb.readStream("Book")
            ?: throw DocumentExtractionException("Not an Excel workbook (no Workbook stream).")
        val recs = scanRecords(wb)
        val sheets = ArrayList<Sheet>()
        var sst: List<String> = emptyList()
        var firstBof = true
        var i = 0
        // Workbook globals.
        while (i < recs.size) {
            val r = recs[i]
            when (r.id) {
                0x0809 -> {
                    if (!firstBof) break
                    firstBof = false
                    if (r.len >= 4 && u16(wb, r.off + 2) != 0x0005) {
                        throw DocumentExtractionException("Unsupported .xls version (Excel 97 or newer required).")
                    }
                    if (r.len >= 2 && u16(wb, r.off) < 0x0600) {
                        throw DocumentExtractionException("Unsupported .xls version (Excel 97 or newer required).")
                    }
                }
                0x002F -> throw DocumentExtractionException("The workbook is encrypted or password protected.")
                0x0085 -> if (r.len >= 8 && sheets.size < MAX_SHEETS) {
                    val type = wb[r.off + 5].toInt() and 0xFF
                    val name = readShortString(wb, r.off + 6, r.off + r.len)
                    if (type == 0) sheets += Sheet(name, u32(wb, r.off)) else sheets += Sheet("\u0000", u32(wb, r.off))
                }
                0x00FC -> {
                    val chunks = ArrayList<Pair<Int, Int>>()
                    chunks += r.off to r.len
                    var j = i + 1
                    while (j < recs.size && recs[j].id == 0x003C) { chunks += recs[j].off to recs[j].len; j++ }
                    sst = readSst(wb, chunks)
                    i = j - 1
                }
                0x000A -> break
            }
            i++
        }
        if (sheets.isEmpty()) throw DocumentExtractionException("Not an Excel workbook (no worksheets).")
        val worksheets = sheets.filter { it.name != "\u0000" }
        var next = 0
        var cells = 0
        var k = i
        while (k < recs.size) {
            val r = recs[k]
            if (r.id != 0x0809 || r.len < 4) { k++; continue }
            val type = u16(wb, r.off + 2)
            // Find the end of this substream (matching EOF, tracking nested BOFs).
            var depth = 1
            var e = k + 1
            while (e < recs.size && depth > 0) {
                if (recs[e].id == 0x0809) depth++ else if (recs[e].id == 0x000A) depth--
                e++
            }
            if (type == 0x0010) {
                val bofPos = (r.off - 4).toLong()
                val sheet = sheets.firstOrNull { it.pos == bofPos && it.name != "\u0000" } ?: worksheets.getOrNull(next)
                next++
                val rows = TreeMap<Int, TreeMap<Int, String>>()
                cells = readCells(wb, recs, k + 1, e, sst, rows, cells)
                sink("## ${sheet?.name ?: "Sheet$next"}\n")
                for ((_, cols) in rows) {
                    val line = StringBuilder()
                    var last = -1
                    for ((col, text) in cols) {
                        if (text.isEmpty()) continue
                        repeat(if (last >= 0) col - last else col) { line.append('\t') }
                        line.append(text)
                        last = col
                    }
                    if (line.isNotEmpty()) sink(line.append('\n'))
                }
                sink("\n")
            }
            k = e
        }
    }

    private fun scanRecords(b: ByteArray): List<Rec> {
        val list = ArrayList<Rec>()
        var p = 0
        while (p + 4 <= b.size) {
            val id = u16(b, p)
            val len = u16(b, p + 2)
            if (p + 4 + len > b.size) break
            list += Rec(id, p + 4, len)
            if (list.size > 20_000_000) throw DocumentExtractionException("Corrupt .xls: too many records.")
            p += 4 + len
        }
        return list
    }

    private fun readCells(
        wb: ByteArray, recs: List<Rec>, from: Int, to: Int, sst: List<String>,
        rows: TreeMap<Int, TreeMap<Int, String>>, startCount: Int
    ): Int {
        var count = startCount
        var pendingFormula: Pair<Int, Int>? = null
        fun put(row: Int, col: Int, text: String) {
            if (col >= MAX_COLS) return
            if (++count > MAX_CELLS) throw DocumentExtractionException("The worksheet has too many cells.")
            rows.getOrPut(row) { TreeMap() }[col] = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        }
        for (n in from until to) {
            val r = recs[n]
            val o = r.off
            when (r.id) {
                0x00FD -> if (r.len >= 10) {
                    put(u16(wb, o), u16(wb, o + 2), sst.getOrNull(u32(wb, o + 6).toInt()).orEmpty())
                }
                0x0204 -> if (r.len >= 8) {
                    val cch = u16(wb, o + 6)
                    put(u16(wb, o), u16(wb, o + 2), readChars(wb, o + 8, o + r.len, cch))
                }
                0x0203 -> if (r.len >= 14) put(u16(wb, o), u16(wb, o + 2), fmt(double(wb, o + 6)))
                0x027E -> if (r.len >= 10) put(u16(wb, o), u16(wb, o + 2), fmt(rk(u32(wb, o + 6))))
                0x00BD -> if (r.len >= 6) {
                    val row = u16(wb, o)
                    val first = u16(wb, o + 2)
                    val last = u16(wb, o + r.len - 2)
                    var q = o + 4
                    var col = first
                    while (col <= last && q + 6 <= o + r.len - 2) {
                        put(row, col, fmt(rk(u32(wb, q + 2))))
                        q += 6
                        col++
                    }
                }
                0x0205 -> if (r.len >= 8) {
                    if ((wb[o + 7].toInt() and 0xFF) == 0) put(u16(wb, o), u16(wb, o + 2), if (wb[o + 6].toInt() != 0) "TRUE" else "FALSE")
                }
                0x0006 -> if (r.len >= 14) {
                    val row = u16(wb, o)
                    val col = u16(wb, o + 2)
                    if (u16(wb, o + 12) == 0xFFFF) {
                        when (wb[o + 6].toInt() and 0xFF) {
                            0 -> pendingFormula = row to col
                            1 -> put(row, col, if (wb[o + 8].toInt() != 0) "TRUE" else "FALSE")
                        }
                    } else {
                        put(row, col, fmt(double(wb, o + 6)))
                    }
                }
                0x0207 -> {
                    val pf = pendingFormula
                    if (pf != null && r.len >= 3) {
                        put(pf.first, pf.second, readChars(wb, o + 3, o + r.len, u16(wb, o), u8(wb, o + 2)))
                    }
                    pendingFormula = null
                }
            }
        }
        return count
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int): Int {
        if (o < 0 || o + 2 > b.size) throw DocumentExtractionException("Corrupt .xls: record out of range.")
        return u8(b, o) or (u8(b, o + 1) shl 8)
    }
    private fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    private fun double(b: ByteArray, o: Int): Double = java.lang.Double.longBitsToDouble(u32(b, o) or (u32(b, o + 4) shl 32))

    private fun rk(v: Long): Double {
        var d = if (v and 2L != 0L) {
            (v.toInt() shr 2).toDouble()
        } else {
            java.lang.Double.longBitsToDouble((v and 0xFFFFFFFCL) shl 32)
        }
        if (v and 1L != 0L) d /= 100.0
        return d
    }

    internal fun fmt(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return ""
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return BigDecimal(d).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }

    /** ShortXLUnicodeString: cch(1), flags(1), chars. */
    private fun readShortString(b: ByteArray, off: Int, end: Int): String {
        if (off + 2 > end) return ""
        return readChars(b, off + 2, end, u8(b, off), u8(b, off + 1))
    }

    /** [cch] characters at [off]; with [flags] given, bit 0 selects UTF-16. For LABEL (flags absent) a flags byte leads the data. */
    private fun readChars(b: ByteArray, off: Int, end: Int, cch: Int, flags: Int = -1): String {
        var p = off
        val f = if (flags >= 0) flags else { if (p >= end) return ""; u8(b, p++) }
        val wide = f and 1 != 0
        val bytes = if (wide) cch * 2 else cch
        val n = minOf(bytes, (end - p).coerceAtLeast(0))
        return if (wide) String(b, p, n and 1.inv(), Charsets.UTF_16LE) else String(b, p, n, CP1252)
    }

    /** Cursor over the SST record and its CONTINUE chunks; a string split across chunks re-states its width flag. */
    private class SstCursor(val b: ByteArray, val chunks: List<Pair<Int, Int>>) {
        var ci = 0
        var pos = chunks[0].first
        var end = chunks[0].first + chunks[0].second
        fun atEnd(): Boolean {
            while (pos >= end && ci + 1 < chunks.size) { ci++; pos = chunks[ci].first; end = pos + chunks[ci].second }
            return pos >= end
        }
        fun u8(): Int { if (atEnd()) throw DocumentExtractionException("Corrupt .xls: string table truncated."); return b[pos++].toInt() and 0xFF }
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                if (atEnd()) throw DocumentExtractionException("Corrupt .xls: string table truncated.")
                val step = minOf(left, (end - pos).toLong()).toInt()
                pos += step; left -= step
            }
        }
    }

    private fun readSst(b: ByteArray, chunks: List<Pair<Int, Int>>): List<String> {
        if (chunks[0].second < 8) return emptyList()
        val c = SstCursor(b, chunks)
        c.u32()
        val unique = c.u32()
        if (unique > MAX_SST) throw DocumentExtractionException("The shared string table is too large.")
        val out = ArrayList<String>(unique.toInt().coerceAtMost(65_536))
        var seen = 0L
        while (seen++ < unique) {
            if (c.atEnd()) break
            val cch = c.u16()
            val flags = c.u8()
            val rich = if (flags and 0x08 != 0) c.u16() else 0
            val ext = if (flags and 0x04 != 0) c.u32() else 0L
            var wide = flags and 1 != 0
            val sb = StringBuilder(cch.coerceAtMost(4096))
            var remaining = cch
            while (remaining > 0) {
                if (c.pos >= c.end) {
                    if (c.atEnd()) throw DocumentExtractionException("Corrupt .xls: string table truncated.")
                    wide = c.u8() and 1 != 0 // new chunk repeats the width flag
                }
                val avail = c.end - c.pos
                val take = minOf(remaining, if (wide) avail / 2 else avail)
                if (take <= 0) throw DocumentExtractionException("Corrupt .xls: string table malformed.")
                if (wide) sb.append(String(b, c.pos, take * 2, Charsets.UTF_16LE)) else sb.append(String(b, c.pos, take, CP1252))
                c.pos += if (wide) take * 2 else take
                remaining -= take
            }
            c.skip(rich * 4L + ext)
            out += sb.toString()
        }
        return out
    }
}
