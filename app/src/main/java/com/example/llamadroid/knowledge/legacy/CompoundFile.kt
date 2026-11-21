package com.example.llamadroid.knowledge.legacy

import com.example.llamadroid.knowledge.DocumentExtractionException
import java.io.File

/**
 * Bounded reader for OLE2 / Compound File Binary containers (legacy .doc and .xls). The whole file is read into memory
 * (capped by [MAX_FILE_BYTES]); FAT, DIFAT and mini-FAT chains are loop-guarded and every sector index is range checked.
 */
class CompoundFile private constructor(private val data: ByteArray) {
    companion object {
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        const val MAX_STREAM_BYTES = 64L * 1024 * 1024
        private const val ENDOFCHAIN = -2
        private const val MAX_DIR_ENTRIES = 100_000
        private val SIGNATURE = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        )

        fun isCompoundFile(file: File): Boolean = try {
            file.inputStream().use { input ->
                val head = ByteArray(8)
                var off = 0
                while (off < 8) {
                    val n = input.read(head, off, 8 - off)
                    if (n < 0) break
                    off += n
                }
                off == 8 && head.contentEquals(SIGNATURE)
            }
        } catch (_: Exception) {
            false
        }

        fun open(file: File): CompoundFile {
            if (file.length() > MAX_FILE_BYTES) throw DocumentExtractionException("Legacy document is too large.")
            val bytes = file.readBytes()
            return CompoundFile(bytes).also { it.parse() }
        }
    }

    private class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    private var sectorShift = 9
    private var sectorSize = 512
    private var miniSectorSize = 64
    private var miniCutoff = 4096L
    private var fat = IntArray(0)
    private var miniFat = IntArray(0)
    private var entries: List<Entry> = emptyList()
    private var miniStream = ByteArray(0)

    private fun u16(o: Int) = (data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8)
    private fun i32(o: Int) = u16(o) or (u16(o + 2) shl 16)
    private fun u32(o: Int) = i32(o).toLong() and 0xFFFFFFFFL

    private fun bad(msg: String): Nothing = throw DocumentExtractionException("Corrupt legacy document: $msg")

    private fun sectorOffset(id: Int): Int {
        if (id < 0) bad("negative sector")
        val off = (id.toLong() + 1) shl sectorShift
        if (off + sectorSize > data.size) bad("sector $id beyond end of file")
        return off.toInt()
    }

    private fun parse() {
        if (data.size < 512) bad("file too short")
        for (i in SIGNATURE.indices) if (data[i] != SIGNATURE[i]) bad("not a compound file")
        sectorShift = u16(0x1E)
        if (sectorShift != 9 && sectorShift != 12) bad("unsupported sector size")
        sectorSize = 1 shl sectorShift
        val miniShift = u16(0x20)
        if (miniShift != 6) bad("unsupported mini sector size")
        miniSectorSize = 1 shl miniShift
        miniCutoff = u32(0x38)
        val numFat = u32(0x2C)
        val firstDir = i32(0x30)
        val firstMiniFat = i32(0x3C)
        val numMiniFat = u32(0x40)
        val firstDifat = i32(0x44)
        val numDifat = u32(0x48)
        val maxSectors = (data.size / sectorSize).toLong() + 1
        if (numFat > maxSectors || numDifat > maxSectors || numMiniFat > maxSectors) bad("sector counts exceed file size")

        // FAT sector list: 109 header slots, then the DIFAT chain.
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) {
            val id = i32(0x4C + i * 4)
            if (id >= 0 && fatSectors.size < numFat) fatSectors += id
        }
        var difat = firstDifat
        var difatSeen = 0L
        val perDifat = sectorSize / 4 - 1
        while (difat >= 0 && fatSectors.size < numFat) {
            if (++difatSeen > numDifat) bad("DIFAT chain too long or looping")
            val base = sectorOffset(difat)
            for (i in 0 until perDifat) {
                val id = i32(base + i * 4)
                if (id >= 0 && fatSectors.size < numFat) fatSectors += id
            }
            difat = i32(base + perDifat * 4)
        }
        if (fatSectors.isEmpty()) bad("no FAT sectors")
        val perFat = sectorSize / 4
        fat = IntArray(fatSectors.size * perFat)
        fatSectors.forEachIndexed { n, sid ->
            val base = sectorOffset(sid)
            for (i in 0 until perFat) fat[n * perFat + i] = i32(base + i * 4)
        }

        // Directory.
        val dir = readChain(firstDir, Long.MAX_VALUE)
        val count = dir.size / 128
        if (count > MAX_DIR_ENTRIES) bad("too many directory entries")
        val list = ArrayList<Entry>(count)
        for (i in 0 until count) {
            val o = i * 128
            val type = dir[o + 0x42].toInt() and 0xFF
            if (type == 0) continue
            val nameLen = ((dir[o + 0x40].toInt() and 0xFF) or ((dir[o + 0x41].toInt() and 0xFF) shl 8)).coerceIn(0, 64)
            val name = String(dir, o, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            val start = rd32(dir, o + 0x74)
            val lo = rd32(dir, o + 0x78).toLong() and 0xFFFFFFFFL
            val hi = rd32(dir, o + 0x7C).toLong() and 0xFFFFFFFFL
            val size = if (hi != 0L) Long.MAX_VALUE else lo
            list += Entry(name, type, start, size)
        }
        entries = list
        val root = list.firstOrNull { it.type == 5 } ?: bad("no root entry")

        // Mini FAT and mini stream.
        if (firstMiniFat >= 0 && numMiniFat > 0) {
            val raw = readChain(firstMiniFat, numMiniFat * sectorSize)
            miniFat = IntArray(raw.size / 4) { rd32(raw, it * 4) }
        }
        if (root.start >= 0 && root.size > 0) {
            if (root.size > MAX_STREAM_BYTES) bad("mini stream too large")
            miniStream = readChain(root.start, root.size)
        }
    }

    private fun rd32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** Follows a FAT chain; throws on loops, out-of-range ids or streams past [limit] bytes. */
    private fun readChain(start: Int, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var id = start
        var steps = 0
        while (id != ENDOFCHAIN) {
            if (id < 0 || id >= fat.size) bad("chain leaves the FAT")
            if (++steps > fat.size) bad("looping sector chain")
            if (out.size().toLong() + sectorSize > MAX_STREAM_BYTES + sectorSize) bad("stream too large")
            out.write(data, sectorOffset(id), sectorSize)
            if (out.size() >= limit && limit != Long.MAX_VALUE) break
            id = fat[id]
        }
        val bytes = out.toByteArray()
        return if (limit != Long.MAX_VALUE && bytes.size > limit) bytes.copyOf(limit.toInt()) else bytes
    }

    private fun readMiniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var id = start
        var steps = 0
        while (id != ENDOFCHAIN && out.size() < size) {
            if (id < 0 || id >= miniFat.size) bad("mini chain leaves the mini FAT")
            if (++steps > miniFat.size) bad("looping mini sector chain")
            val off = id.toLong() * miniSectorSize
            if (off + miniSectorSize > miniStream.size) bad("mini sector beyond mini stream")
            out.write(miniStream, off.toInt(), miniSectorSize)
            id = miniFat[id]
        }
        return out.toByteArray()
    }

    fun hasStream(name: String): Boolean = entries.any { it.type == 2 && it.name.equals(name, ignoreCase = true) }

    /** Reads the first stream called [name]; null when missing. Throws when the chain is shorter than the declared size. */
    fun readStream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
        if (e.size > MAX_STREAM_BYTES) throw DocumentExtractionException("Stream $name is too large.")
        if (e.size == 0L) return ByteArray(0)
        val bytes = if (e.size < miniCutoff) readMiniChain(e.start, e.size) else readChain(e.start, e.size)
        if (bytes.size < e.size) bad("stream $name is truncated")
        return if (bytes.size.toLong() == e.size) bytes else bytes.copyOf(e.size.toInt())
    }
}
