package com.example.llamadroid.data.easy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyGgufFlagsTest {
    private class Gguf {
        val out = ByteArrayOutputStream()
        var count = 0
        private fun w(b: ByteArray) = out.write(b)
        private fun u32(v: Int) = w(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        private fun u64(v: Long) = w(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        private fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); w(b) }
        fun string(key: String, v: String) { str(key); u32(8); str(v); count++ }
        fun uint32(key: String, v: Int) { str(key); u32(4); u32(v); count++ }
        fun bool(key: String, v: Boolean) { str(key); u32(7); out.write(if (v) 1 else 0); count++ }
        fun stringArray(key: String, vararg v: String) {
            str(key); u32(9); u32(8); u64(v.size.toLong()); v.forEach(::str); count++
        }
        fun u16Array(key: String, n: Int) { str(key); u32(9); u32(2); u64(n.toLong()); w(ByteArray(n * 2)); count++ }
        fun build(): ByteArray {
            val head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46554747).putInt(3).putLong(0).putLong(count.toLong()).array()
            return head + out.toByteArray()
        }
    }

    private fun has(bytes: ByteArray) = EasyGgufFlags.hasAudioEncoder(ByteArrayInputStream(bytes))

    @Test fun trueFlagAfterOtherKeysAndArrays() {
        val g = Gguf()
        g.string("general.architecture", "clip")
        g.stringArray("tokenizer.tokens", "a", "bb")
        g.u16Array("clip.vision.image_mean", 5)
        g.uint32("clip.vision.block_count", 12)
        g.bool(EasyGgufFlags.KEY_HAS_AUDIO_ENCODER, true)
        assertTrue(has(g.build()))
    }

    @Test fun falseFlagMissingFlagAndWrongType() {
        assertFalse(has(Gguf().apply { bool(EasyGgufFlags.KEY_HAS_AUDIO_ENCODER, false) }.build()))
        assertFalse(has(Gguf().apply { string("general.architecture", "clip") }.build()))
        assertNull(EasyGgufFlags.readBool(
            ByteArrayInputStream(Gguf().apply { uint32(EasyGgufFlags.KEY_HAS_AUDIO_ENCODER, 1) }.build()),
            EasyGgufFlags.KEY_HAS_AUDIO_ENCODER))
    }

    @Test fun badMagicAndTruncatedAreFalse() {
        assertFalse(has(ByteArray(40)))
        val ok = Gguf().apply { bool(EasyGgufFlags.KEY_HAS_AUDIO_ENCODER, true) }.build()
        assertFalse(has(ok.copyOf(ok.size - 3)))
        assertFalse(has(ByteArray(0)))
    }
}
