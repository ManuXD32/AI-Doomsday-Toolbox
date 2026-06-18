package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProcessControllerTest {

    @Test
    fun `unexpected exit resolves to error state`() {
        val controller = ProcessController()

        val state = controller.resolveExitState(42, "exited")

        assertEquals(ServerState.Error("exited"), state)
    }

    @Test
    fun `intentional stop resolves to stopped state`() {
        val controller = ProcessController()
        controller.stop()

        val state = controller.resolveExitState(1, "ignored")

        assertTrue(state is ServerState.Stopped)
    }

    @Test
    fun `generated command uses current speculative decoding flags`() {
        val controller = ProcessController()

        val args = controller.getCommand("/bin/llama-server", speculativeConfig())

        assertArgValue(args, "--spec-type", "draft-simple")
        assertArgValue(args, "--spec-draft-model", "/models/draft.gguf")
        assertArgValue(args, "--spec-draft-n-max", "3")
        assertArgValue(args, "--spec-draft-n-min", "0")
        assertArgValue(args, "--spec-draft-p-min", "0.00")
        assertFalse(args.contains("--model-draft"))
        assertFalse(args.contains("--draft-p-min"))
        assertFalse(args.contains("--draft-max"))
        assertFalse(args.contains("--draft-min"))
    }

    @Test
    fun `generated embedding command can set physical batch size`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/embed.gguf",
                isEmbedding = true,
                batchSize = 1024,
                physicalBatchSize = 1024,
                parallel = 1,
                cacheRam = 0
            )
        )

        assertArgValue(args, "-b", "1024")
        assertArgValue(args, "--ubatch-size", "1024")
        assertArgValue(args, "--parallel", "1")
        assertArgValue(args, "--cache-ram", "0")
    }

    @Test
    fun `speculative command template placeholder uses current speculative decoding flags`() {
        val controller = ProcessController()

        val args = controller.renderCommandTemplate(
            template = "{speculative_args}",
            binaryPath = "/bin/llama-server",
            config = speculativeConfig()
        )

        assertArgValue(args, "--spec-type", "draft-simple")
        assertArgValue(args, "--spec-draft-model", "/models/draft.gguf")
        assertArgValue(args, "--spec-draft-n-max", "3")
        assertArgValue(args, "--spec-draft-n-min", "0")
        assertArgValue(args, "--spec-draft-p-min", "0.00")
        assertFalse(args.contains("--model-draft"))
        assertFalse(args.contains("--draft-p-min"))
        assertFalse(args.contains("--draft-max"))
        assertFalse(args.contains("--draft-min"))
    }

    @Test
    fun `generated command uses MTP speculative decoding flags`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/mtp.gguf",
                speculativeMode = LlamaSpeculativeMode.DRAFT_MTP,
                mtpDraftMax = 7,
                mtpDraftMin = 2,
                mtpDraftPMin = 0.25f
            )
        )

        assertArgValue(args, "--spec-type", "draft-mtp")
        assertArgValue(args, "--spec-draft-n-max", "7")
        assertArgValue(args, "--spec-draft-n-min", "2")
        assertArgValue(args, "--spec-draft-p-min", "0.25")
        assertArgValue(args, "--parallel", "1")
        assertFalse(args.contains("--spec-draft-model"))
        assertFalse(args.contains("--draft-p-min"))
    }

    @Test
    fun `generated command can use separate draft model in MTP mode`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/mtp.gguf",
                draftModelPath = "/models/mtp-draft.gguf",
                speculativeMode = LlamaSpeculativeMode.DRAFT_MTP,
                mtpDraftMax = 5,
                mtpDraftMin = 1,
                mtpDraftPMin = 0.15f
            )
        )

        assertArgValue(args, "--spec-type", "draft-mtp")
        assertArgValue(args, "--spec-draft-model", "/models/mtp-draft.gguf")
        assertArgValue(args, "--spec-draft-n-max", "5")
        assertArgValue(args, "--spec-draft-n-min", "1")
        assertArgValue(args, "--spec-draft-p-min", "0.15")
    }

    @Test
    fun `MTP command template placeholder uses MTP speculative decoding flags`() {
        val controller = ProcessController()

        val args = controller.renderCommandTemplate(
            template = "{speculative_args}",
            binaryPath = "/bin/llama-server",
            config = LlamaConfig(
                modelPath = "/models/mtp.gguf",
                speculativeMode = LlamaSpeculativeMode.DRAFT_MTP,
                mtpDraftMin = 1
            )
        )

        assertArgValue(args, "--spec-type", "draft-mtp")
        assertArgValue(args, "--spec-draft-n-max", "3")
        assertArgValue(args, "--spec-draft-n-min", "1")
        assertArgValue(args, "--spec-draft-p-min", "0.00")
        assertFalse(args.contains("--spec-draft-model"))
    }

    @Test
    fun `MTP command template placeholder can render only MTP args`() {
        val controller = ProcessController()

        val args = controller.renderCommandTemplate(
            template = "{mtp_args}",
            binaryPath = "/bin/llama-server",
            config = LlamaConfig(
                modelPath = "/models/mtp.gguf",
                speculativeMode = LlamaSpeculativeMode.DRAFT_MTP,
                mtpDraftMax = 5,
                mtpDraftMin = 2,
                mtpDraftPMin = 0.10f
            )
        )

        assertArgValue(args, "--spec-type", "draft-mtp")
        assertArgValue(args, "--spec-draft-n-max", "5")
        assertArgValue(args, "--spec-draft-n-min", "2")
        assertArgValue(args, "--spec-draft-p-min", "0.10")
        assertFalse(args.contains("--spec-draft-model"))
    }

    @Test
    fun `MTP binary capability check detects advertised draft-mtp marker`() {
        val controller = ProcessController()
        val supported = File.createTempFile("llama-server-mtp", ".so")
        val unsupported = File.createTempFile("llama-server-no-mtp", ".so")
        try {
            supported.writeText("common_speculative_impl_draft_mtp draft-mtp")
            unsupported.writeText("common_speculative_impl_draft")

            assertTrue(controller.binarySupportsMtpSpeculative(supported))
            assertFalse(controller.binarySupportsMtpSpeculative(unsupported))
        } finally {
            supported.delete()
            unsupported.delete()
        }
    }

    private fun speculativeConfig() = LlamaConfig(
        modelPath = "/models/main.gguf",
        speculativeMode = LlamaSpeculativeMode.DRAFT_SIMPLE,
        draftModelPath = "/models/draft.gguf"
    )

    private fun assertArgValue(args: List<String>, flag: String, expected: String) {
        val index = args.indexOf(flag)
        assertTrue("$flag missing from ${args.joinToString(" ")}", index >= 0)
        assertTrue("$flag has no value in ${args.joinToString(" ")}", index + 1 < args.size)
        assertEquals(expected, args[index + 1])
    }
}
