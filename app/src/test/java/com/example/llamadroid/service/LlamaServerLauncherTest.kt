package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
