package com.example.llamadroid.audio

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AudioAdapterCapabilityTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `pocket descriptor disables controls fixed by the model pack`() {
        val registry = AudioAdapterRegistry.forContext(context)
        val descriptor = AudioModelDescriptor(
            id = "pocket-es",
            family = AudioModelFamilies.POCKET_TTS,
            modelPath = "/models/pocket.gguf",
            companionPath = "/models/pocket-mmproj.gguf",
            language = "ES"
        )

        val info = requireNotNull(registry.describe(descriptor))
        assertFalse(info.supportsLanguageSelection)
        assertTrue(info.referenceAudioRequired)
        assertFalse(info.supportsTemperature)
        assertFalse(info.supportsTopP)
        assertFalse(info.supportsTopK)
        assertEquals(listOf("es"), info.supportedLanguages)
        assertEquals("pocket_language_fixed", info.parameterDefinitions.first { it.key == "language" }.unsupportedReason)
        assertEquals(
            "pocket_sampling_pack_defined",
            info.parameterDefinitions.first { it.key == "temperature" }.unsupportedReason
        )
        assertTrue(info.parameterDefinitions.first { it.key == "seed" }.supported)
    }

    @Test
    fun `supertonic exposes pipeline and export bounds`() {
        val registry = AudioAdapterRegistry.forContext(context)
        val info = requireNotNull(
            registry.describe(
                AudioModelDescriptor(
                    id = "supertonic",
                    family = AudioModelFamilies.SUPERTONIC,
                    modelPath = "/models/supertonic"
                )
            )
        )
        assertTrue(info.supportsOutputSampleRate)
        assertEquals(0.5, info.parameterDefinitions.first { it.key == "speed" }.minimum!!, 0.0)
        assertEquals(2.0, info.parameterDefinitions.first { it.key == "speed" }.maximum!!, 0.0)
        assertEquals(24_000.0, info.parameterDefinitions.first { it.key == "outputSampleRate" }.defaultValue!!.toDouble(), 0.0)
    }
}
