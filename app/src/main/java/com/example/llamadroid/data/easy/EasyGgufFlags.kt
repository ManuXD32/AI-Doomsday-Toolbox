package com.example.llamadroid.data.easy

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/**
 * Minimal GGUF header reader for boolean metadata keys. [com.example.llamadroid.util.GGUFParser]
 * only extracts model-size fields, so Easy mode reads `clip.has_audio_encoder` here.
 */
object EasyGgufFlags {
    const val KEY_HAS_AUDIO_ENCODER = "clip.has_audio_encoder"
    private const val MAGIC = 0x46554747 // "GGUF" little-endian
    private const val MAX_KV = 4096L
    private const val MAX_STRING = 1L shl 20

    /** True only when the file is a readable GGUF that stores [KEY_HAS_AUDIO_ENCODER] = true. */
    fun hasAudioEncoder(mmproj: File): Boolean =
        mmproj.isFile && runCatching { mmproj.inputStream().buffered().use(::hasAudioEncoder) }.getOrDefault(false)

    fun hasAudioEncoder(input: InputStream): Boolean = readBool(input, KEY_HAS_AUDIO_ENCODER) == true

    /** Value of a boolean KV [key], or null when absent, not a bool, or the stream is malformed. */
    fun readBool(input: InputStream, key: String): Boolean? = try {
        val data = DataInputStream(input)
        if (le32(data) != MAGIC) null else {
            le32(data) // version
            le64(data) // tensor count
            val kvCount = le64(data).coerceAtMost(MAX_KV)
            var found: Boolean? = null
            var i = 0L
            while (i++ < kvCount) {
                val name = readString(data)
                val type = le32(data)
                if (name == key) {
                    found = if (type == TYPE_BOOL) data.readUnsignedByte() != 0 else null
                    break
                }
                skip(data, type)
            }
            found
        }
    } catch (_: EOFException) { null } catch (_: java.io.IOException) { null } catch (_: IllegalStateException) { null }

    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9

    private fun le32(d: DataInputStream): Int = Integer.reverseBytes(d.readInt())
    private fun le64(d: DataInputStream): Long = java.lang.Long.reverseBytes(d.readLong())

    private fun readString(d: DataInputStream): String {
        val len = le64(d)
        check(len in 0..MAX_STRING)
        val bytes = ByteArray(len.toInt())
        d.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipBytes(d: DataInputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = d.skip(left)
            if (n <= 0) { d.readUnsignedByte(); left-- } else left -= n
        }
    }

    private fun fixedSize(type: Int): Int = when (type) {
        0, 1, 7 -> 1
        2, 3 -> 2
        4, 5, 6 -> 4
        10, 11, 12 -> 8
        else -> error("unknown GGUF type $type")
    }

    private fun skip(d: DataInputStream, type: Int) {
        when (type) {
            TYPE_STRING -> skipBytes(d, le64(d).also { check(it >= 0) })
            TYPE_ARRAY -> {
                val elem = le32(d)
                val count = le64(d)
                check(count >= 0)
                if (elem == TYPE_STRING || elem == TYPE_ARRAY) { var j = 0L; while (j++ < count) skip(d, elem) }
                else skipBytes(d, count * fixedSize(elem))
            }
            else -> skipBytes(d, fixedSize(type).toLong())
        }
    }
}
