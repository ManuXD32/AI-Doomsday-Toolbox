package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdNativeFailureClassifierTest {
    @Test
    fun `issue 21 output classifies as missing vae`() {
        val report = SdNativeFailureClassifier.classify(
            exitCode = 1,
            recentOutput = listOf(
                "[ERROR] VAE tensor 'first_stage_model.encoder.conv_in.weight' not in model metadata",
                "[ERROR] model metadata validation failed",
                "[INFO] new_sd_ctx_t failed"
            )
        )

        assertEquals(SdFailureCategory.MISSING_VAE, report.category)
    }

    @Test
    fun `exit 134 is native abort even when metadata text is present`() {
        val report = SdNativeFailureClassifier.classify(
            exitCode = 134,
            recentOutput = listOf("model metadata validation failed")
        )

        assertEquals(SdFailureCategory.NATIVE_ABORT, report.category)
        assertEquals(6, report.signal)
    }

    @Test
    fun `oom and unsupported cli have distinct categories`() {
        assertEquals(
            SdFailureCategory.OUT_OF_MEMORY,
            SdNativeFailureClassifier.classify(1, listOf("ggml: failed to allocate 4096 MB")).category
        )
        assertEquals(
            SdFailureCategory.UNSUPPORTED_BINARY_FLAG,
            SdNativeFailureClassifier.classify(1, listOf("unknown argument: --future-flag")).category
        )
    }

    @Test
    fun `generic tensor mismatch is distinct from corrupt metadata`() {
        assertEquals(
            SdFailureCategory.TENSOR_MISMATCH,
            SdNativeFailureClassifier.classify(1, listOf("tensor shape mismatch at output_blocks.0")).category
        )
    }

    @Test
    fun `missing expected diffusion tensor is a model architecture mismatch`() {
        val report = SdNativeFailureClassifier.classify(
            exitCode = 1,
            recentOutput = listOf(
                "[ERROR] Diffusion model tensor 'transformer_blocks.0.attn.to_q.weight' not in model metadata"
            )
        )

        assertEquals(SdFailureCategory.MODEL_LAYOUT_MISMATCH, report.category)
    }

    @Test
    fun `accelerator failures require accelerator evidence`() {
        val generic = SdNativeFailureClassifier.classify(
            exitCode = 1,
            recentOutput = listOf("new_sd_ctx_t failed"),
            acceleratorBinary = true
        )
        val vulkan = SdNativeFailureClassifier.classify(
            exitCode = 1,
            recentOutput = listOf("Vulkan error: device lost"),
            acceleratorBinary = true
        )

        assertEquals(SdFailureCategory.UNKNOWN_NATIVE_FAILURE, generic.category)
        assertEquals(SdFailureCategory.ACCELERATOR_FAILURE, vulkan.category)
    }

    @Test
    fun `bounded buffer redacts paths and enforces limits`() {
        val buffer = SdNativeOutputBuffer(maxLines = 2, maxChars = 100)
        buffer.add("one /storage/emulated/0/Android/data/app/model.gguf")
        buffer.add("two")
        buffer.add("three")

        val output = buffer.snapshot()
        assertEquals(2, output.size)
        assertFalse(output.joinToString().contains("/storage/"))
        assertTrue(output.last().contains("three"))
    }
}
