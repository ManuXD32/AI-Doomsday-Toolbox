package com.example.llamadroid.service

import com.example.llamadroid.data.LlamaOcrPromptPreset
import com.example.llamadroid.data.LlamaOcrSettingsSnapshot
import com.example.llamadroid.data.model.LlamaServerSessionIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaServerLauncherTest {
    @Test
    fun `owned launcher action allowlist includes only local service lifecycle commands`() {
        assertTrue(LlamaServerLauncher.isOwnedServiceAction(LlamaService.ACTION_START))
        assertTrue(LlamaServerLauncher.isOwnedServiceAction(LlamaService.ACTION_RECONFIGURE))
        assertTrue(LlamaServerLauncher.isOwnedServiceAction(LlamaService.ACTION_SWITCH_MODEL))
        assertTrue(LlamaServerLauncher.isOwnedServiceAction(LlamaService.ACTION_STOP))
        assertTrue(LlamaServerLauncher.isOwnedServiceAction(LlamaService.ACTION_RECOVER))
        assertFalse(LlamaServerLauncher.isOwnedServiceAction(LlamaService.ACTION_PREVIEW_COMMAND))
        assertFalse(LlamaServerLauncher.isOwnedServiceAction(null))
        assertFalse(LlamaServerLauncher.isOwnedServiceAction("OTHER_SERVICE"))
    }

    @Test
    fun `OCR flags remove duplicated preset values and dedicated flash attention`() {
        val flags = LlamaServerLauncher.composeLlamaOcrFlags(
            recommendedFlags = "--chat-template deepseek-ocr --flash-attn off",
            customFlags = "--chat-template deepseek-ocr --flash-attn off"
        )

        assertEquals("--chat-template deepseek-ocr", flags)
        assertFalse(flags.contains("--flash-attn"))
    }

    @Test
    fun `OCR flags remove speculative MTP and native tools settings`() {
        val flags = LlamaServerLauncher.composeLlamaOcrFlags(
            recommendedFlags = "--chat-template deepseek-ocr --flash-attn off",
            customFlags = "--spec-type draft-mtp --spec-draft-n-max 4 --tools all --parallel 1"
        )

        assertEquals("--chat-template deepseek-ocr --parallel 1", flags)
        assertFalse(flags.contains("draft-mtp"))
        assertFalse(flags.contains("--spec-type"))
        assertFalse(flags.contains("--spec-draft"))
        assertFalse(flags.contains("--tools"))
    }

    @Test
    fun `OCR flags remove equals-style speculative settings`() {
        val flags = LlamaServerLauncher.composeLlamaOcrFlags(
            recommendedFlags = "--chat-template deepseek-ocr",
            customFlags = "--spec-type=draft-mtp --device-draft GPUOpenCL --gpu-layers-draft all --no-warmup"
        )

        assertEquals("--chat-template deepseek-ocr --no-warmup", flags)
        assertFalse(flags.contains("draft-mtp"))
        assertFalse(flags.contains("GPUOpenCL"))
        assertFalse(flags.contains("--gpu-layers-draft"))
    }

    @Test
    fun `OCR profile maps the complete isolated launch configuration`() {
        val profile = LlamaServerLauncher.buildLlamaOcrLaunchProfile(
            LlamaOcrSettingsSnapshot(
                modelPath = " /models/Unlimited-OCR-Q4.gguf ",
                mmprojPath = " /models/mmproj-Unlimited-OCR-F16.gguf ",
                promptPreset = LlamaOcrPromptPreset.UNLIMITED_OCR,
                customPrompt = null,
                contextSize = 8_192,
                maxTokens = 2_600,
                port = 8_087,
                flashAttention = false,
                cacheRam = 2_048,
                parallel = 1,
                customFlags = "--spec-type draft-mtp --no-warmup",
                commandTemplate = " {binary} --model {model} ",
                temporarilyReplaceRunningServer = true
            )
        )

        assertEquals("/models/Unlimited-OCR-Q4.gguf", profile.modelPath)
        assertEquals("/models/mmproj-Unlimited-OCR-F16.gguf", profile.mmprojPath)
        assertEquals("127.0.0.1", profile.host)
        assertEquals(8_087, profile.serverPort)
        assertEquals(8_192, profile.contextSize)
        assertEquals(1, profile.parallel)
        assertEquals(2_048, profile.cacheRam)
        assertEquals(0f, profile.temperature)
        assertTrue(profile.visionEnabled)
        assertFalse(profile.speculativeEnabled)
        assertFalse(profile.nativeToolsEnabled)
        assertNull(profile.draftModelPath)
        assertFalse(profile.customFlags.orEmpty().contains("draft-mtp"))
        assertTrue(profile.customFlags.orEmpty().contains("deepseek-ocr"))
        assertEquals("{binary} --model {model}", profile.commandTemplate)
    }

    @Test
    fun `OCR stop is permanently bound to the exact reserved session id`() {
        assertEquals(LlamaServerSessionIds.OCR, LlamaServerLauncher.ocrReservedSessionId())
        assertTrue(LlamaServerSessionIds.isReserved(LlamaServerLauncher.ocrReservedSessionId()))
        assertFalse(LlamaServerLauncher.ocrReservedSessionId().startsWith("card:"))
    }
}
