package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

class WhisperCliSupportTest {
    private fun request(
        purpose: WhisperInvocationPurpose = WhisperInvocationPurpose.BATCH_TRANSCRIPTION,
        vad: WhisperVadConfig = WhisperVadConfig(),
        formats: Set<WhisperOutputFormat> = setOf(
            WhisperOutputFormat.SRT,
            WhisperOutputFormat.TXT
        )
    ) = WhisperInvocationRequest(
        binaryPath = "/native/libwhisper-cli_i8mm.so",
        modelPath = "/models/ggml-base.bin",
        audioPath = "/tmp/input audio.wav",
        language = "auto",
        threads = 5,
        translate = false,
        outputFormats = formats,
        outputBasePath = "/tmp/output base",
        purpose = purpose,
        vad = vad
    )

    @Test
    fun `disabled VAD preserves the ordinary CPU command`() {
        val args = buildWhisperInvocationArgs(request())

        assertTrue(args.contains("--no-gpu"))
        assertTrue(args.contains("-otxt"))
        assertTrue(args.contains("-osrt"))
        assertFalse(args.contains("--vad"))
        assertEquals("/tmp/input audio.wav", args[args.indexOf("-f") + 1])
        assertEquals("/tmp/output base", args[args.indexOf("-of") + 1])
    }

    @Test
    fun `empty output selection safely falls back to text`() {
        val args = buildWhisperInvocationArgs(request(formats = emptySet()))

        assertTrue(args.contains("-otxt"))
        assertFalse(args.contains("-osrt"))
    }

    @Test
    fun `batch VAD emits every configured flag once`() {
        val args = buildWhisperInvocationArgs(
            request(
                vad = WhisperVadConfig(
                    enabled = true,
                    modelPath = "/models/vad model.bin",
                    threshold = 0.55f,
                    minSpeechDurationMs = 300,
                    minSilenceDurationMs = 450,
                    maxSpeechDurationSeconds = 90f,
                    speechPaddingMs = 40,
                    samplesOverlap = 0.2f
                )
            ),
            WhisperBinaryCapabilities.ALLOW_ALL
        )

        assertEquals(1, args.count { it == "--vad" })
        assertEquals("/models/vad model.bin", args[args.indexOf("--vad-model") + 1])
        assertEquals("0.55", args[args.indexOf("--vad-threshold") + 1])
        assertEquals("300", args[args.indexOf("--vad-min-speech-duration-ms") + 1])
        assertEquals("450", args[args.indexOf("--vad-min-silence-duration-ms") + 1])
        assertEquals("90", args[args.indexOf("--vad-max-speech-duration-s") + 1])
        assertEquals("40", args[args.indexOf("--vad-speech-pad-ms") + 1])
        assertEquals("0.2", args[args.indexOf("--vad-samples-overlap") + 1])
    }

    @Test
    fun `batch style purposes allow native VAD`() {
        val enabledVad = WhisperVadConfig(enabled = true, modelPath = "/models/vad.bin")
        listOf(
            WhisperInvocationPurpose.BATCH_TRANSCRIPTION,
            WhisperInvocationPurpose.VIDEO_SUMMARY,
            WhisperInvocationPurpose.MEDIA_WORKFLOW,
            WhisperInvocationPurpose.AUDIO_ATTACHMENT
        ).forEach { purpose ->
            val args = buildWhisperInvocationArgs(
                request(purpose, enabledVad),
                WhisperBinaryCapabilities.ALLOW_ALL
            )
            assertTrue("$purpose should allow VAD", args.contains("--vad"))
        }
    }

    @Test
    fun `interactive purposes suppress native VAD`() {
        val enabledVad = WhisperVadConfig(enabled = true, modelPath = "/models/vad.bin")
        listOf(
            WhisperInvocationPurpose.CALL_TRANSCRIPTION,
            WhisperInvocationPurpose.LIVE_TRANSLATOR,
            WhisperInvocationPurpose.LANGUAGE_SAMPLE
        ).forEach { purpose ->
            val args = buildWhisperInvocationArgs(request(purpose, enabledVad))
            assertFalse("$purpose should suppress VAD", args.contains("--vad"))
        }
    }

    @Test
    fun `enabled VAD without a model path fails closed`() {
        try {
            buildWhisperInvocationArgs(
                request(vad = WhisperVadConfig(enabled = true)),
                WhisperBinaryCapabilities.ALLOW_ALL
            )
            fail("Expected unavailable VAD model failure")
        } catch (_: WhisperVadUnavailableException) {
        }
    }

    @Test
    fun `missing binary VAD capability fails closed`() {
        try {
            buildWhisperInvocationArgs(
                request(
                    vad = WhisperVadConfig(enabled = true, modelPath = "/models/vad.bin")
                ),
                WhisperBinaryCapabilities(setOf("--vad", "--vad-model"))
            )
            fail("Expected unsupported flag failure")
        } catch (expected: WhisperUnsupportedFlagsException) {
            assertTrue(expected.flags.contains("--vad-threshold"))
            assertTrue(expected.flags.contains("--vad-samples-overlap"))
        }
    }

    @Test
    fun `capability parser recognizes short and long flags`() {
        val capabilities = parseWhisperBinaryCapabilities(
            """
            -m FILE
            --vad
            --vad-model FILE
            --vad-threshold N
            """.trimIndent()
        )

        assertTrue(capabilities.supports("-m"))
        assertTrue(capabilities.supports("--vad"))
        assertTrue(capabilities.supports("--vad-model"))
        assertTrue(capabilities.supports("--vad-threshold"))
    }

    @Test
    fun `CLI numbers remain locale independent`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val args = buildWhisperInvocationArgs(
                request(
                    vad = WhisperVadConfig(
                        enabled = true,
                        modelPath = "/models/vad.bin",
                        threshold = 0.5f,
                        samplesOverlap = 0.1f
                    )
                ),
                WhisperBinaryCapabilities.ALLOW_ALL
            )
            assertEquals("0.5", args[args.indexOf("--vad-threshold") + 1])
            assertEquals("0.1", args[args.indexOf("--vad-samples-overlap") + 1])
        } finally {
            Locale.setDefault(original)
        }
    }
}
