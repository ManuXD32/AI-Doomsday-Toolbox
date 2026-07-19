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
        assertArgValue(args, "--spec-draft-threads", "4")
        assertArgValue(args, "--spec-draft-threads-batch", "4")
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
    fun `accelerator ggml backend path resolves to a file only`() {
        val controller = ProcessController()
        val tempDir = createTempDir(prefix = "ggml-backend-test")
        val libDir = File(tempDir, "linked").apply { mkdirs() }
        val nativeDir = File(tempDir, "native").apply { mkdirs() }
        val backend = File(nativeDir, "libggml-opencl.so").apply { writeText("test") }

        val resolved = controller.resolveGgmlBackendPathForAccelerator(nativeDir, libDir)

        assertEquals(backend.absolutePath, resolved)
    }

    @Test
    fun `accelerator ggml backend path ignores directories`() {
        val controller = ProcessController()
        val tempDir = createTempDir(prefix = "ggml-backend-test")
        val libDir = File(tempDir, "linked").apply { mkdirs() }
        val nativeDir = File(tempDir, "native").apply { mkdirs() }
        File(nativeDir, "libggml-opencl.so").mkdirs()

        val resolved = controller.resolveGgmlBackendPathForAccelerator(nativeDir, libDir)

        assertEquals(null, resolved)
    }

    @Test
    fun `generated command can keep KV cache on CPU`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/main.gguf",
                kvCacheEnabled = true,
                kvOffloadMode = LlamaKvOffloadMode.CPU.value
            )
        )

        assertTrue(args.contains("--no-kv-offload"))
        assertFalse(args.contains("--kv-offload"))
    }

    @Test
    fun `generated command can explicitly enable KV offload`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/main.gguf",
                kvCacheEnabled = true,
                kvOffloadMode = LlamaKvOffloadMode.ACCELERATOR.value
            )
        )

        assertTrue(args.contains("--kv-offload"))
        assertFalse(args.contains("--no-kv-offload"))
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
        assertArgValue(args, "--spec-draft-threads", "4")
        assertArgValue(args, "--spec-draft-threads-batch", "4")
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
        assertFalse(args.contains("--spec-draft-threads"))
        assertFalse(args.contains("--spec-draft-threads-batch"))
        assertFalse(args.contains("--draft-p-min"))
    }

    @Test
    fun `MTP command can force CPU draft placement`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/mtp.gguf",
                speculativeMode = LlamaSpeculativeMode.DRAFT_MTP,
                draftDeviceMode = LlamaDraftDeviceMode.CPU.value
            )
        )

        assertArgValue(args, "--device-draft", "none")
        assertArgValue(args, "--gpu-layers-draft", "0")
    }

    @Test
    fun `MTP command can force OpenCL draft placement`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/mtp.gguf",
                speculativeMode = LlamaSpeculativeMode.DRAFT_MTP,
                draftDeviceMode = LlamaDraftDeviceMode.ACCELERATOR.value
            )
        )

        assertArgValue(args, "--device-draft", "GPUOpenCL")
        assertArgValue(args, "--gpu-layers-draft", "all")
    }

    @Test
    fun `custom draft placement flags suppress generated draft placement`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/mtp.gguf",
                speculativeMode = LlamaSpeculativeMode.DRAFT_MTP,
                draftDeviceMode = LlamaDraftDeviceMode.CPU.value,
                customFlags = "--device-draft GPUOpenCL --gpu-layers-draft all"
            )
        )

        assertEquals(1, args.count { it == "--device-draft" })
        assertArgValue(args, "--device-draft", "GPUOpenCL")
        assertArgValue(args, "--gpu-layers-draft", "all")
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
                draftThreads = 6,
                draftThreadsBatch = 5,
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
        assertArgValue(args, "--spec-draft-threads", "6")
        assertArgValue(args, "--spec-draft-threads-batch", "5")
    }

    @Test
    fun `generated command uses DFlash speculative decoding flags`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/main.gguf",
                draftModelPath = "/models/dflash-draft.gguf",
                speculativeMode = LlamaSpeculativeMode.DRAFT_DFLASH,
                draftMax = 15,
                draftThreads = 3,
                draftThreadsBatch = 2,
                temperature = 0.7f
            )
        )

        assertArgValue(args, "--spec-type", "draft-dflash")
        assertArgValue(args, "-md", "/models/dflash-draft.gguf")
        assertArgValue(args, "--spec-draft-n-max", "15")
        assertArgValue(args, "--spec-draft-threads", "3")
        assertArgValue(args, "--spec-draft-threads-batch", "2")
        assertArgValue(args, "--temp", "0.7")
        assertFalse(args.contains("--spec-draft-model"))
        assertFalse(args.contains("--spec-draft-n-min"))
        assertFalse(args.contains("--spec-draft-p-min"))
    }

    @Test
    fun `DFlash omits speculative args without draft model`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/main.gguf",
                speculativeMode = LlamaSpeculativeMode.DRAFT_DFLASH
            )
        )

        assertFalse(args.contains("--spec-type"))
        assertFalse(args.contains("-md"))
        assertFalse(args.contains("--spec-draft-threads"))
        assertFalse(args.contains("--spec-draft-threads-batch"))
    }

    @Test
    fun `n-gram mod command uses no draft model args`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/main.gguf",
                draftModelPath = "/models/ignored-draft.gguf",
                speculativeMode = LlamaSpeculativeMode.NGRAM_MOD,
                ngramModNMatch = 24,
                ngramModNMin = 48,
                ngramModNMax = 64
            )
        )

        assertArgValue(args, "--spec-type", "ngram-mod")
        assertArgValue(args, "--spec-ngram-mod-n-min", "48")
        assertArgValue(args, "--spec-ngram-mod-n-max", "64")
        assertArgValue(args, "--spec-ngram-mod-n-match", "24")
        assertFalse(args.contains("-md"))
        assertFalse(args.contains("--spec-draft-model"))
        assertFalse(args.contains("--spec-draft-threads"))
        assertFalse(args.contains("--spec-draft-threads-batch"))
    }

    @Test
    fun `n-gram simple command uses no draft model args`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/main.gguf",
                draftModelPath = "/models/ignored-draft.gguf",
                speculativeMode = LlamaSpeculativeMode.NGRAM_SIMPLE,
                ngramSimpleSizeN = 12,
                ngramSimpleSizeM = 48,
                ngramSimpleMinHits = 1
            )
        )

        assertArgValue(args, "--spec-type", "ngram-simple")
        assertArgValue(args, "--spec-ngram-simple-size-n", "12")
        assertArgValue(args, "--spec-ngram-simple-size-m", "48")
        assertArgValue(args, "--spec-ngram-simple-min-hits", "1")
        assertFalse(args.contains("-md"))
        assertFalse(args.contains("--spec-draft-model"))
        assertFalse(args.contains("--spec-draft-threads"))
        assertFalse(args.contains("--spec-draft-threads-batch"))
    }

    @Test
    fun `n-gram map-k command uses no draft model args`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/main.gguf",
                draftModelPath = "/models/ignored-draft.gguf",
                speculativeMode = LlamaSpeculativeMode.NGRAM_MAP_K,
                ngramMapKSizeN = 8,
                ngramMapKSizeM = 32,
                ngramMapKMinHits = 2
            )
        )

        assertArgValue(args, "--spec-type", "ngram-map-k")
        assertArgValue(args, "--spec-ngram-map-k-size-n", "8")
        assertArgValue(args, "--spec-ngram-map-k-size-m", "32")
        assertArgValue(args, "--spec-ngram-map-k-min-hits", "2")
        assertFalse(args.contains("-md"))
        assertFalse(args.contains("--spec-draft-model"))
        assertFalse(args.contains("--spec-draft-threads"))
        assertFalse(args.contains("--spec-draft-threads-batch"))
    }

    @Test
    fun `n-gram map-k4v command uses no draft model args`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/main.gguf",
                speculativeMode = LlamaSpeculativeMode.NGRAM_MAP_K4V,
                ngramMapK4VSizeN = 6,
                ngramMapK4VSizeM = 16,
                ngramMapK4VMinHits = 3
            )
        )

        assertArgValue(args, "--spec-type", "ngram-map-k4v")
        assertArgValue(args, "--spec-ngram-map-k4v-size-n", "6")
        assertArgValue(args, "--spec-ngram-map-k4v-size-m", "16")
        assertArgValue(args, "--spec-ngram-map-k4v-min-hits", "3")
        assertFalse(args.contains("-md"))
        assertFalse(args.contains("--spec-draft-model"))
        assertFalse(args.contains("--spec-draft-threads"))
        assertFalse(args.contains("--spec-draft-threads-batch"))
    }

    @Test
    fun `n-gram cache command uses no draft model args`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/main.gguf",
                draftModelPath = "/models/ignored-draft.gguf",
                speculativeMode = LlamaSpeculativeMode.NGRAM_CACHE
            )
        )

        assertArgValue(args, "--spec-type", "ngram-cache")
        assertFalse(args.contains("-md"))
        assertFalse(args.contains("--spec-draft-model"))
        assertFalse(args.contains("--spec-draft-threads"))
        assertFalse(args.contains("--spec-draft-threads-batch"))
    }

    @Test
    fun `n-gram command template placeholder uses selected speculative args`() {
        val controller = ProcessController()

        val args = controller.renderCommandTemplate(
            template = "{speculative_args}",
            binaryPath = "/bin/llama-server",
            config = LlamaConfig(
                modelPath = "/models/main.gguf",
                speculativeMode = LlamaSpeculativeMode.NGRAM_MOD
            )
        )

        assertArgValue(args, "--spec-type", "ngram-mod")
        assertArgValue(args, "--spec-ngram-mod-n-min", "48")
        assertArgValue(args, "--spec-ngram-mod-n-max", "64")
        assertArgValue(args, "--spec-ngram-mod-n-match", "24")
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
        assertFalse(args.contains("--spec-draft-threads"))
        assertFalse(args.contains("--spec-draft-threads-batch"))
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
                draftModelPath = "/models/mtp-draft.gguf",
                draftThreads = 8,
                draftThreadsBatch = 7,
                mtpDraftMax = 5,
                mtpDraftMin = 2,
                mtpDraftPMin = 0.10f
            )
        )

        assertArgValue(args, "--spec-type", "draft-mtp")
        assertArgValue(args, "--spec-draft-n-max", "5")
        assertArgValue(args, "--spec-draft-n-min", "2")
        assertArgValue(args, "--spec-draft-p-min", "0.10")
        assertArgValue(args, "--spec-draft-model", "/models/mtp-draft.gguf")
        assertArgValue(args, "--spec-draft-threads", "8")
        assertArgValue(args, "--spec-draft-threads-batch", "7")
    }

    @Test
    fun `native tools are appended after custom flags in generated command`() {
        val controller = ProcessController()

        val args = controller.getCommand(
            "/bin/llama-server",
            LlamaConfig(
                modelPath = "/models/main.gguf",
                customFlags = "--jinja",
                nativeToolsEnabled = true
            )
        )

        val jinjaIndex = args.indexOf("--jinja")
        val toolsIndex = args.indexOf("--tools")
        assertTrue(jinjaIndex >= 0)
        assertTrue(toolsIndex > jinjaIndex)
        assertEquals("all", args[toolsIndex + 1])
    }

    @Test
    fun `native tools template appends unless template handles native tools`() {
        val controller = ProcessController()
        val config = LlamaConfig(modelPath = "/models/main.gguf", nativeToolsEnabled = true)

        val appended = controller.renderCommandTemplate(
            template = "{binary} -m {model} --jinja",
            binaryPath = "/bin/llama-server",
            config = config
        )
        assertArgValue(appended, "--tools", "all")

        val explicit = controller.renderCommandTemplate(
            template = "{binary} -m {model} --tools search",
            binaryPath = "/bin/llama-server",
            config = config
        )
        assertArgValue(explicit, "--tools", "search")

        val placeholder = controller.renderCommandTemplate(
            template = "{binary} -m {model} {native_tools_args}",
            binaryPath = "/bin/llama-server",
            config = config
        )
        assertArgValue(placeholder, "--tools", "all")
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

    @Test
    fun `DFlash binary capability check detects stable speculative markers`() {
        val controller = ProcessController()
        val supportedByFlag = File.createTempFile("llama-server-dflash-flag", ".so")
        val supportedByModel = File.createTempFile("llama-server-dflash-model", ".so")
        val unsupported = File.createTempFile("llama-server-no-dflash", ".so")
        try {
            supportedByFlag.writeText("common_speculative_impl_draft_dflash draft-dflash")
            supportedByModel.writeText("llama_model_dflash dflash.block_size")
            unsupported.writeText("common_speculative_impl_draft")

            assertTrue(controller.binarySupportsDflashSpeculative(supportedByFlag))
            assertTrue(controller.binarySupportsDflashSpeculative(supportedByModel))
            assertFalse(controller.binarySupportsDflashSpeculative(unsupported))
        } finally {
            supportedByFlag.delete()
            supportedByModel.delete()
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
