package com.example.llamadroid.service.ram

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Reads the few GGUF metadata keys that decide the KV-cache size. Unlike `GGUFParser` it does not
 * stop after 100 keys and understands per-layer arrays (`head_count_kv`). Returns null for any
 * unreadable or non-GGUF input so callers fall back to the size heuristic.
 */
object GgufModelShapeReader {
    private const val MAX_KV_ENTRIES = 200_000L

    fun read(file: File): LlamaModelShape? {
        if (!file.isFile || !file.canRead()) return null
        return runCatching {
            FileInputStream(file).use { read(BufferedInputStream(it, 64 * 1024)) }
        }.getOrNull()
    }

    fun read(input: InputStream): LlamaModelShape? {
        val stream = DataInputStream(input)
        val magic = ByteArray(4).also { stream.readFully(it) }
        if (String(magic, Charsets.US_ASCII) != "GGUF") return null
        stream.skipFully(4) // version
        stream.skipFully(8) // tensor count
        val kvCount = stream.readLongLe()
        if (kvCount !in 0..MAX_KV_ENTRIES) return null

        var layers = 0
        var embedding = 0
        var heads = 0.0
        var kvHeads = 0.0
        var keyLength = 0
        var valueLength = 0
        var window = 0
        var pattern = 0
        var arch = ""
        try {
            for (i in 0 until kvCount) {
                val key = stream.readStringLe()
                val type = stream.readIntLe()
                when {
                    key == "general.architecture" && type == TYPE_STRING -> arch = stream.readStringLe()
                    key.endsWith(".block_count") -> layers = stream.readNumber(type).toInt()
                    key.endsWith(".embedding_length") -> embedding = stream.readNumber(type).toInt()
                    key.endsWith(".attention.head_count_kv") -> kvHeads = stream.readNumber(type)
                    key.endsWith(".attention.head_count") -> heads = stream.readNumber(type)
                    key.endsWith(".attention.key_length") -> keyLength = stream.readNumber(type).toInt()
                    key.endsWith(".attention.value_length") -> valueLength = stream.readNumber(type).toInt()
                    key.endsWith(".attention.sliding_window") -> window = stream.readNumber(type).toInt()
                    key.endsWith(".attention.sliding_window_pattern") -> pattern = stream.readNumber(type).toInt()
                    else -> stream.skipValue(type)
                }
                // Tokenizer data is huge and always follows the architecture keys.
                if (key == "tokenizer.ggml.tokens" && layers > 0) break
            }
        } catch (_: EOFException) {
            // Use what was read; a truncated tail only matters if the needed keys are missing.
        }
        if (layers <= 0 || heads <= 0.0) return null
        if (kvHeads <= 0.0) kvHeads = heads
        val headDim = if (embedding > 0) (embedding / heads).toInt() else 0
        val k = if (keyLength > 0) keyLength else headDim
        val v = if (valueLength > 0) valueLength else k
        if (k <= 0) return null
        return LlamaModelShape(
            layers = layers,
            kvHeads = kvHeads,
            headDimK = k,
            headDimV = v,
            slidingWindow = window.coerceAtLeast(0),
            swaLayerFraction = if (window > 0) swaFraction(arch, pattern) else 0.0
        )
    }

    /** Share of sliding-window layers: explicit pattern wins, else a per-family default. */
    internal fun swaFraction(arch: String, pattern: Int): Double {
        if (pattern > 1) return (pattern - 1).toDouble() / pattern
        val a = arch.lowercase()
        return when {
            a.startsWith("gemma3n") -> 0.8
            a.startsWith("gemma3") -> 5.0 / 6.0
            a.startsWith("llama4") -> 0.75
            else -> 0.5
        }
    }

    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9

    private fun DataInputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun DataInputStream.readIntLe(): Int = Integer.reverseBytes(readInt())

    private fun DataInputStream.readLongLe(): Long = java.lang.Long.reverseBytes(readLong())

    private fun DataInputStream.readStringLe(): String {
        val length = readLongLe()
        if (length < 0 || length > 1_048_576L) throw EOFException()
        val bytes = ByteArray(length.toInt())
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun fixedSize(type: Int): Int = when (type) {
        0, 1, 7 -> 1
        2, 3 -> 2
        4, 5, 6 -> 4
        10, 11, 12 -> 8
        else -> throw EOFException()
    }

    private fun DataInputStream.readScalar(type: Int): Double = when (type) {
        0 -> readUnsignedByte().toDouble()
        1 -> readByte().toDouble()
        2 -> java.lang.Short.reverseBytes(readShort()).toInt().and(0xFFFF).toDouble()
        3 -> java.lang.Short.reverseBytes(readShort()).toDouble()
        4 -> (readIntLe().toLong() and 0xFFFFFFFFL).toDouble()
        5 -> readIntLe().toDouble()
        6 -> java.lang.Float.intBitsToFloat(readIntLe()).toDouble()
        7 -> readUnsignedByte().toDouble()
        10, 11 -> readLongLe().toDouble()
        12 -> java.lang.Double.longBitsToDouble(readLongLe())
        else -> throw EOFException()
    }

    /** A scalar, or the average of the positive entries of a numeric array. */
    private fun DataInputStream.readNumber(type: Int): Double {
        if (type != TYPE_ARRAY) return readScalar(type)
        val elementType = readIntLe()
        val count = readLongLe()
        if (count < 0 || count > 1_000_000L || elementType == TYPE_STRING || elementType == TYPE_ARRAY) {
            skipArrayBody(elementType, count)
            return 0.0
        }
        var sum = 0.0
        var counted = 0
        for (i in 0 until count) {
            val value = readScalar(elementType)
            if (value > 0.0) {
                sum += value
                counted++
            }
        }
        // Average over all layers so layers without KV (value 0) lower the mean.
        return if (count > 0 && counted > 0) sum / count else 0.0
    }

    private fun DataInputStream.skipValue(type: Int) {
        when (type) {
            TYPE_STRING -> skipFully(readLongLe())
            TYPE_ARRAY -> {
                val elementType = readIntLe()
                skipArrayBody(elementType, readLongLe())
            }
            else -> skipFully(fixedSize(type).toLong())
        }
    }

    private fun DataInputStream.skipArrayBody(elementType: Int, count: Long) {
        if (count < 0) throw EOFException()
        when (elementType) {
            TYPE_STRING -> for (i in 0 until count) skipFully(readLongLe())
            TYPE_ARRAY -> for (i in 0 until count) skipValue(TYPE_ARRAY)
            else -> skipFully(fixedSize(elementType).toLong() * count)
        }
    }
}
