package com.example.llamadroid.service.ram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufModelShapeReaderTest {
    private class Gguf {
        private val out = ByteArrayOutputStream()
        private var count = 0L
        private val body = ByteArrayOutputStream()

        private fun le(size: Int, fill: (ByteBuffer) -> Unit): ByteArray =
            ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).also(fill).array()

        private fun str(s: String) {
            val bytes = s.toByteArray()
            body.write(le(8) { it.putLong(bytes.size.toLong()) })
            body.write(bytes)
        }

        fun string(key: String, value: String) = apply {
            str(key); body.write(le(4) { it.putInt(8) }); str(value); count++
        }

        fun u32(key: String, value: Int) = apply {
            str(key); body.write(le(4) { it.putInt(4) }); body.write(le(4) { it.putInt(value) }); count++
        }

        fun i32Array(key: String, values: List<Int>) = apply {
            str(key); body.write(le(4) { it.putInt(9) })
            body.write(le(4) { it.putInt(5) }); body.write(le(8) { it.putLong(values.size.toLong()) })
            values.forEach { v -> body.write(le(4) { it.putInt(v) }) }
            count++
        }

        fun stringArray(key: String, values: List<String>) = apply {
            str(key); body.write(le(4) { it.putInt(9) })
            body.write(le(4) { it.putInt(8) }); body.write(le(8) { it.putLong(values.size.toLong()) })
            values.forEach { str(it) }
            count++
        }

        fun bytes(): ByteArray {
            out.write("GGUF".toByteArray())
            out.write(le(4) { it.putInt(3) })
            out.write(le(8) { it.putLong(0) })
            out.write(le(8) { it.putLong(count) })
            out.write(body.toByteArray())
            return out.toByteArray()
        }
    }

    @Test fun readsGemmaStyleShapeAndSkipsOtherKeys() {
        val data = Gguf()
            .string("general.architecture", "gemma3")
            .stringArray("general.tags", listOf("a", "bb"))
            .u32("gemma3.block_count", 34)
            .u32("gemma3.embedding_length", 2560)
            .u32("gemma3.attention.head_count", 8)
            .u32("gemma3.attention.head_count_kv", 4)
            .u32("gemma3.attention.key_length", 256)
            .u32("gemma3.attention.value_length", 256)
            .u32("gemma3.attention.sliding_window", 1024)
            .bytes()
        val shape = GgufModelShapeReader.read(ByteArrayInputStream(data))
        assertNotNull(shape)
        shape!!
        assertEquals(34, shape.layers)
        assertEquals(4.0, shape.kvHeads, 0.0)
        assertEquals(256, shape.headDimK)
        assertEquals(256, shape.headDimV)
        assertEquals(1024, shape.slidingWindow)
        assertEquals(5.0 / 6.0, shape.swaLayerFraction, 1e-9)
    }

    @Test fun derivesHeadDimAndAveragesPerLayerKvHeads() {
        val data = Gguf()
            .string("general.architecture", "hybrid")
            .u32("hybrid.block_count", 4)
            .u32("hybrid.embedding_length", 4096)
            .u32("hybrid.attention.head_count", 32)
            .i32Array("hybrid.attention.head_count_kv", listOf(8, 0, 8, 0))
            .bytes()
        val shape = GgufModelShapeReader.read(ByteArrayInputStream(data))!!
        assertEquals(128, shape.headDimK)
        assertEquals(128, shape.headDimV)
        assertEquals(4.0, shape.kvHeads, 1e-9)
        assertEquals(0, shape.slidingWindow)
        assertEquals(0.0, shape.swaLayerFraction, 0.0)
    }

    @Test fun explicitSlidingWindowPatternWins() {
        val data = Gguf()
            .string("general.architecture", "custom")
            .u32("custom.block_count", 8)
            .u32("custom.embedding_length", 1024)
            .u32("custom.attention.head_count", 8)
            .u32("custom.attention.sliding_window", 256)
            .u32("custom.attention.sliding_window_pattern", 4)
            .bytes()
        val shape = GgufModelShapeReader.read(ByteArrayInputStream(data))!!
        assertEquals(0.75, shape.swaLayerFraction, 1e-9)
        // No head_count_kv: falls back to the attention head count.
        assertEquals(8.0, shape.kvHeads, 0.0)
    }

    @Test fun rejectsNonGgufAndIncompleteInput() {
        assertNull(GgufModelShapeReader.read(ByteArrayInputStream("NOPE-not-gguf".toByteArray())))
        val incomplete = Gguf().string("general.architecture", "llama").bytes()
        assertNull(GgufModelShapeReader.read(ByteArrayInputStream(incomplete)))
    }
}
