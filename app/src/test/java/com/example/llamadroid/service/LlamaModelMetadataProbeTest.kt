package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LlamaModelMetadataProbeTest {
    @Test
    fun parsesCurrentAndLegacyLayerLogShapes() {
        assertEquals(48, LlamaModelMetadataProbe.parseTransformerBlocks("llm_load_print_meta: n_layer = 48"))
        assertEquals(32, LlamaModelMetadataProbe.parseTransformerBlocks("model: n_layer: 32"))
        assertEquals(
            34,
            LlamaModelMetadataProbe.parseTransformerBlocks(
                "llama_model_loader: - kv  12: gemma3.block_count u32 = 34"
            )
        )
        assertEquals(
            62,
            LlamaModelMetadataProbe.parseTransformerBlocks(
                "llama_model_loader: - kv  10: qwen2.block_count = 62"
            )
        )
        assertEquals(
            30,
            LlamaModelMetadataProbe.parseTransformerBlocks(
                "llama_model_loader: - kv 14: gemma4.block_count u32 = 30"
            )
        )
        assertEquals(
            24,
            LlamaModelMetadataProbe.parseTransformerBlocks("model config: num_hidden_layers: 24")
        )
    }

    @Test
    fun parsesTypedArchitectureMetadata() {
        assertEquals(
            "gemma3",
            LlamaModelMetadataProbe.parseArchitecture(
                "llama_model_loader: - kv   0: general.architecture str = gemma3"
            )
        )
    }

    @Test
    fun manualFallbackAddsTheOffloadableOutputLayerAndValidatesBounds() {
        val result = LlamaModelMetadataProbe.manualResult(30, "/models/gemma4.gguf")

        assertEquals(30, result.transformerBlocks)
        assertEquals(31, result.offloadableLayers)
        assertEquals("manual", result.binaryPath)
        assertThrows(IllegalArgumentException::class.java) {
            LlamaModelMetadataProbe.manualResult(0, "/models/invalid.gguf")
        }
    }

    @Test
    fun probeCommandIsVerbosePrivateAndNeverContactsRpcWorkers() {
        val command = LlamaModelMetadataProbe.buildProbeCommand(
            File("/packaged/libllama_server.so"),
            File("/models/model.gguf"),
            43210
        )

        assertEquals("/packaged/libllama_server.so", command.first())
        assertEquals(true, "--verbose" in command)
        assertEquals(listOf("--device", "none"), command.windowed(2).first { it.first() == "--device" })
        assertEquals(listOf("--host", "127.0.0.1"), command.windowed(2).first { it.first() == "--host" })
        assertEquals(false, "--rpc" in command)
    }

    @Test
    fun probeCommandFollowsLongOnlyHelpAliases() {
        val command = LlamaModelMetadataProbe.buildProbeCommand(
            binary = File("/packaged/llama-server"),
            model = File("/models/model.gguf"),
            port = 43210,
            capabilities = LlamaBinaryCapabilities(
                supportedFlags = setOf(
                    "--model",
                    "--ctx-size",
                    "--threads",
                    "--batch-size",
                    "--device",
                    "--n-gpu-layers"
                )
            )
        )

        assertEquals("--model", command[1])
        assertEquals("--ctx-size", command[3])
        assertEquals("--threads", command[5])
        assertEquals("--batch-size", command[7])
        assertEquals("--device", command[10])
        assertEquals("--n-gpu-layers", command[12])
    }

    @Test
    fun llamaCapabilityParserRecognizesMultiLetterShortAliases() {
        val capabilities = parseLlamaBinaryCapabilities(
            """
            -m, --model FILE
            -c, --ctx-size N
            -ngl, --n-gpu-layers N
            -dev, --device DEVICE
            """.trimIndent()
        )

        assertTrue(capabilities.supports("-ngl"))
        assertTrue(capabilities.supports("-dev"))
        assertEquals("-ngl", capabilities.preferredFlag("--n-gpu-layers", "-ngl"))
    }

    @Test
    fun ignoresProgressAndTensorLoadingLines() {
        assertNull(LlamaModelMetadataProbe.parseTransformerBlocks("load_tensors: loading model tensors, this can take a while"))
        assertNull(LlamaModelMetadataProbe.parseTransformerBlocks("n_layer = unknown"))
    }

    @Test
    fun fingerprintChangesWithModelOrBinaryIdentity() {
        val model = File.createTempFile("probe-model", ".gguf")
        val binary = File.createTempFile("probe-binary", ".so")
        try {
            model.writeText("one")
            binary.writeText("binary")
            val first = LlamaModelMetadataProbe.fingerprint(model, binary)
            model.appendText("two")
            val modelChanged = LlamaModelMetadataProbe.fingerprint(model, binary)
            binary.appendText("changed")
            val binaryChanged = LlamaModelMetadataProbe.fingerprint(model, binary)
            org.junit.Assert.assertNotEquals(first, modelChanged)
            org.junit.Assert.assertNotEquals(modelChanged, binaryChanged)
        } finally {
            model.delete()
            binary.delete()
        }
    }
}
