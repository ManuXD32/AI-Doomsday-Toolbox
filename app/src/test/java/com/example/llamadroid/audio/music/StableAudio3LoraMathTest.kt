package com.example.llamadroid.audio.music

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class StableAudio3LoraMathTest {
    @Test fun cancellationInterruptsMatrixWork() {
        val layer = StableAudio3LoraLayer(mapOf(
            "lora_A" to StableAudio3LoraTensor(intArrayOf(1, 2), floatArrayOf(1f, 0f)),
            "lora_B" to StableAudio3LoraTensor(intArrayOf(2, 1), floatArrayOf(1f, 2f))))
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            StableAudio3LoraMath.validateAndMergeDelta("test", FloatArray(4), 2, 2, layer, "lora", 1f,
                checkpoint = { throw kotlinx.coroutines.CancellationException() })
        }
    }

    @Test fun doraMagnitudeNormalizesTheAdaptedBase() {
        val layer = StableAudio3LoraLayer(mapOf(
            "lora_A" to StableAudio3LoraTensor(intArrayOf(1, 2), floatArrayOf(1f, 0f)),
            "lora_B" to StableAudio3LoraTensor(intArrayOf(2, 1), floatArrayOf(1f, 2f)),
            "magnitude" to StableAudio3LoraTensor(intArrayOf(2), floatArrayOf(2f, 3f))))
        val delta = StableAudio3LoraMath.validateAndMergeDelta("test", floatArrayOf(1f, 0f, 0f, 1f), 2, 2, layer, "dora-rows", 1f)
        assertArrayEquals(floatArrayOf(1f, 0f, 6f / kotlin.math.sqrt(5f), 3f / kotlin.math.sqrt(5f) - 1f), delta, 0.00001f)
    }

    @Test
    fun loraDeltaUsesBTimesAAndAdapterScaling() {
        val layer = StableAudio3LoraLayer(
            mapOf(
                "lora_A" to StableAudio3LoraTensor(intArrayOf(1, 2), floatArrayOf(1f, 1f)),
                "lora_B" to StableAudio3LoraTensor(intArrayOf(2, 1), floatArrayOf(1f, 2f))
            )
        )

        val delta = StableAudio3LoraMath.validateAndMergeDelta(
            layerName = "layer",
            base = floatArrayOf(1f, 2f, 3f, 4f),
            rows = 2,
            columns = 2,
            layer = layer,
            adapterType = "lora",
            scaling = 0.5f
        )

        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 1f, 1f), delta, 0.00001f)
    }

    @Test
    fun mismatchedAdapterShapeIsRejectedBeforeMultiplication() {
        val layer = StableAudio3LoraLayer(
            mapOf(
                "lora_A" to StableAudio3LoraTensor(intArrayOf(2, 3), FloatArray(6)),
                "lora_B" to StableAudio3LoraTensor(intArrayOf(2, 1), FloatArray(2))
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            StableAudio3LoraMath.validateAndMergeDelta(
                layerName = "layer",
                base = FloatArray(4),
                rows = 2,
                columns = 2,
                layer = layer,
                adapterType = "lora",
                scaling = 1f
            )
        }
    }

    @Test
    fun quantizedPrecisionCannotBeMerged() {
        assertFalse(StableAudio3DitPrecision.W8A32.supportsLora)
        assertFalse(StableAudio3DitPrecision.W8A8_DYNAMIC.supportsLora)
        assertTrue(StableAudio3DitPrecision.FP32.supportsLora)
        assertTrue(StableAudio3DitPrecision.W16A32.supportsLora)
    }
}
