package com.example.llamadroid.audio.music

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StableAudio3TfliteWeightsTest {
    private fun fixture(precision: String, block: (File) -> Unit) {
        val file = File.createTempFile("stable-lora-$precision", ".tflite")
        try {
            javaClass.classLoader!!.getResourceAsStream("audio/music/lora-fc-$precision.tflite")!!
                .use { input -> file.outputStream().use { input.copyTo(it) } }
            block(file)
        } finally { file.delete() }
    }

    @Test fun `schema defaults and shared vtables locate exact FP32 buffer`() = fixture("fp32") { file ->
        val weight = StableAudio3TfliteWeights.discover(file).single()
        assertEquals(StableAudio3TfliteWeightType.FP32, weight.type)
        assertArrayEquals(intArrayOf(2, 3), weight.shape)
        assertEquals(24, weight.byteSize)
        val buffer = ByteBuffer.wrap(file.readBytes(), weight.offset.toInt(), weight.byteSize).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1f, buffer.float, 0f)
        assertEquals(2f, buffer.float, 0f)
    }

    @Test fun `dequantize indirection resolves original FP16 constants`() = fixture("fp16") { file ->
        val weight = StableAudio3TfliteWeights.discover(file).single()
        assertEquals(StableAudio3TfliteWeightType.FP16, weight.type)
        assertEquals(12, weight.byteSize)
    }

    @Test fun `quantized weights fail before a model copy can be patched`() = fixture("int8") { file ->
        assertTrue(runCatching { StableAudio3TfliteWeights.discover(file) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `FP16 rounding propagates carry and uses ties to even`() {
        assertEquals(0x4000, StableAudio3LoraMerge.floatToHalf(1.9998f))
        assertEquals(0x3c00, StableAudio3LoraMerge.floatToHalf(1.00048828125f))
        assertEquals(0x3c02, StableAudio3LoraMerge.floatToHalf(1.00146484375f))
        assertEquals(0x8000, StableAudio3LoraMerge.floatToHalf(-0.0f))
        assertEquals(1, StableAudio3LoraMerge.floatToHalf(5.9604645e-8f))
        assertTrue(runCatching { StableAudio3LoraMerge.floatToHalf(65520f) }.isFailure)
    }
}
