package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

        assertArgValue(args, "--model-draft", "/models/draft.gguf")
        assertArgValue(args, "--spec-draft-n-max", "16")
        assertArgValue(args, "--spec-draft-n-min", "0")
        assertArgValue(args, "--draft-p-min", "0.75")
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

        assertArgValue(args, "--model-draft", "/models/draft.gguf")
        assertArgValue(args, "--spec-draft-n-max", "16")
        assertArgValue(args, "--spec-draft-n-min", "0")
        assertArgValue(args, "--draft-p-min", "0.75")
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
                draftModelPath = "/models/draft.gguf",
                mtpDecodingEnabled = true,
                mtpDraftMax = 7
            )
        )

        assertArgValue(args, "--spec-type", "draft-mtp")
        assertArgValue(args, "--spec-draft-n-max", "7")
        assertArgValue(args, "--parallel", "1")
        assertFalse(args.contains("--model-draft"))
        assertFalse(args.contains("--spec-draft-n-min"))
        assertFalse(args.contains("--draft-p-min"))
    }

    @Test
    fun `MTP command template placeholder uses MTP speculative decoding flags`() {
        val controller = ProcessController()

        val args = controller.renderCommandTemplate(
            template = "{speculative_args}",
            binaryPath = "/bin/llama-server",
            config = LlamaConfig(
                modelPath = "/models/mtp.gguf",
                mtpDecodingEnabled = true
            )
        )

        assertArgValue(args, "--spec-type", "draft-mtp")
        assertArgValue(args, "--spec-draft-n-max", "3")
        assertFalse(args.contains("--model-draft"))
    }

    private fun speculativeConfig() = LlamaConfig(
        modelPath = "/models/main.gguf",
        draftModelPath = "/models/draft.gguf",
        draftMax = 16,
        draftMin = 0,
        draftPMin = 0.75f
    )

    private fun assertArgValue(args: List<String>, flag: String, expected: String) {
        val index = args.indexOf(flag)
        assertTrue("$flag missing from ${args.joinToString(" ")}", index >= 0)
        assertTrue("$flag has no value in ${args.joinToString(" ")}", index + 1 < args.size)
        assertEquals(expected, args[index + 1])
    }
}
