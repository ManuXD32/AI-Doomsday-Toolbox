package com.example.llamadroid.knowledge.legacy

import com.example.llamadroid.knowledge.DocumentExtractionException
import java.io.File
import java.nio.charset.Charset

/** Text extraction for Word 97-2003 `.doc` files (FIB, Clx piece table, 8-bit and UTF-16 pieces). */
object LegacyDocReader {
    private const val MAX_CHARS = 20_000_000
    private const val MAX_PIECES = 200_000
    private val CP1252: Charset = Charset.forName("windows-1252")

    private fun u16(b: ByteArray, o: Int): Int {
        if (o < 0 || o + 2 > b.size) throw DocumentExtractionException("Corrupt .doc: header out of range.")
        return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)

    fun extract(file: File, sink: (CharSequence) -> Unit) {
        val cfb = CompoundFile.open(file)
        val word = cfb.readStream("WordDocument") ?: throw DocumentExtractionException("Not a Word document (no WordDocument stream).")
        if (u16(word, 0) != 0xA5EC) throw DocumentExtractionException("Unsupported .doc version (Word 97 or newer required).")
        val nFib = u16(word, 2)
        if (nFib < 0xC1) throw DocumentExtractionException("Unsupported .doc version (Word 97 or newer required).")
        val flags = u16(word, 0x0A)
        if (flags and 0x0100 != 0) throw DocumentExtractionException("The document is encrypted or password protected.")
        val csw = u16(word, 32)
        val lwOff = 32 + 2 + csw * 2
        val cslw = u16(word, lwOff)
        val fcOff = lwOff + 2 + cslw * 4
        val ccpText = u32(word, lwOff + 2 + 3 * 4)
        if (ccpText <= 0 || ccpText > MAX_CHARS) {
            if (ccpText <= 0) return
            throw DocumentExtractionException("The document text is too large.")
        }
        val cbRgFcLcb = u16(word, fcOff)
        val clxPair = fcOff + 2 + 33 * 8
        var fcClx = 0L
        var lcbClx = 0L
        if (cbRgFcLcb > 34) {
            fcClx = u32(word, clxPair)
            lcbClx = u32(word, clxPair + 4)
        }
        val raw = StringBuilder()
        val pieces = if (lcbClx > 0) {
            val table = cfb.readStream(if (flags and 0x0200 != 0) "1Table" else "0Table")
            if (table != null && fcClx + lcbClx <= table.size) readPieces(table, fcClx.toInt(), lcbClx.toInt(), ccpText.toInt()) else null
        } else {
            null
        }
        if (pieces != null) {
            for (p in pieces) {
                if (p.cpEnd <= p.cpStart) continue
                val n = (p.cpEnd - p.cpStart).toInt()
                if (p.compressed) {
                    val start = p.fc / 2
                    if (start + n > word.size) throw DocumentExtractionException("Corrupt .doc: text piece beyond the stream.")
                    raw.append(String(word, start.toInt(), n, CP1252))
                } else {
                    if (p.fc + n * 2L > word.size) throw DocumentExtractionException("Corrupt .doc: text piece beyond the stream.")
                    raw.append(String(word, p.fc.toInt(), n * 2, Charsets.UTF_16LE))
                }
            }
        } else {
            // No piece table: contiguous 8-bit text from fcMin.
            val fcMin = u32(word, 0x18)
            val n = ccpText.toInt()
            if (fcMin + n > word.size) throw DocumentExtractionException("Corrupt .doc: text range beyond the stream.")
            raw.append(String(word, fcMin.toInt(), n, CP1252))
        }
        sink(clean(raw))
    }

    private class Piece(val cpStart: Long, val cpEnd: Long, val compressed: Boolean, val fc: Long)

    private fun readPieces(table: ByteArray, fcClx: Int, lcbClx: Int, ccpText: Int): List<Piece>? {
        var pos = fcClx
        val end = fcClx + lcbClx
        while (pos < end) {
            when (table[pos].toInt() and 0xFF) {
                0x01 -> pos += 3 + u16(table, pos + 1)
                0x02 -> {
                    val lcb = u32(table, pos + 1).toInt()
                    val plc = pos + 5
                    if (lcb < 4 || plc + lcb > end) return null
                    val n = (lcb - 4) / 12
                    if (n <= 0 || n > MAX_PIECES) return null
                    val pcdBase = plc + (n + 1) * 4
                    val list = ArrayList<Piece>(n)
                    for (i in 0 until n) {
                        val cpStart = u32(table, plc + i * 4)
                        val cpEnd = minOf(u32(table, plc + (i + 1) * 4), ccpText.toLong())
                        val fcRaw = u32(table, pcdBase + i * 8 + 2)
                        val compressed = fcRaw and 0x40000000L != 0L
                        val fc = fcRaw and 0x3FFFFFFFL
                        list += Piece(cpStart, cpEnd, compressed, fc)
                    }
                    return list
                }
                else -> return null
            }
        }
        return null
    }

    /** Drops field codes and control characters; table cell marks become tabs and row ends become newlines. */
    internal fun clean(raw: CharSequence): String {
        val sb = StringBuilder(raw.length)
        var fieldDepth = 0
        var inInstruction = false
        val instrStack = ArrayList<Boolean>()
        for (c in raw) {
            when (c) {
                '\u0013' -> { instrStack += inInstruction; inInstruction = true; fieldDepth++ }
                '\u0014' -> if (fieldDepth > 0) inInstruction = false
                '\u0015' -> if (fieldDepth > 0) { fieldDepth--; inInstruction = instrStack.removeAt(instrStack.size - 1) }
                else -> if (!inInstruction) when (c) {
                    '\r', '\u000B', '\u000C' -> sb.append('\n')
                    '\u0007' -> if (sb.isNotEmpty() && sb[sb.length - 1] == '\t') sb.setCharAt(sb.length - 1, '\n') else sb.append('\t')
                    '\t', '\n' -> sb.append(c)
                    ' ' -> sb.append('\n')
                    else -> if (c >= ' ' && c != '￾' && c != '￿') sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
