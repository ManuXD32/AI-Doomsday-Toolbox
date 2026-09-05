package com.example.llamadroid.service

import com.example.llamadroid.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaServerLaunchProfileTest {
    @Test
    fun `saved profile round trips its isolated llama configuration`() {
        val profile = LlamaServerLaunchProfile(
            modelPath = "/models/coder.gguf",
            mmprojPath = "/models/vision.mmproj",
            visionEnabled = true,
            loraPath = "/models/style.lora",
            host = "0.0.0.0",
            serverPort = 18080,
            contextSize = 32768,
            threads = 8,
            batchSize = 2048,
            physicalBatchSize = 1024,
            threadsBatch = 3,
            kvCacheEnabled = true,
            kvCacheTypeK = "q8_0",
            kvCacheTypeV = "q4_0",
            kvCacheReuse = 128,
            kvOffloadMode = "disabled",
            noMmap = true,
            parallel = 2,
            cacheRam = 4096,
            contextCheckpoints = 8,
            checkpointMinStep = 256,
            cachePrompt = true,
            cacheIdleSlots = false,
            kvUnifiedMode = "enabled",
            swaFull = true,
            sleepIdleSeconds = 99,
            customFlags = "--no-warmup",
            commandTemplate = "{binary} {model}",
            flashAttention = true,
            openClCpuTargetGpuDraft = true,
            nativeBinarySelection = SettingsRepository.NATIVE_BINARY_CPU_DOTPROD,
            nativeToolsEnabled = true,
            speculativeEnabled = true,
            speculativeMode = LlamaSpeculativeMode.DRAFT_MTP.flagValue,
            draftModelPath = "/models/draft.gguf",
            draftMax = 6,
            draftMin = 2,
            draftPMin = 0.4f,
            draftThreads = 3,
            draftThreadsBatch = 2,
            mtpDraftMax = 7,
            mtpDraftMin = 1,
            mtpDraftPMin = 0.2f,
            mtpUseDraftModel = true,
            draftDeviceMode = "cpu",
            ngramModNMatch = 16,
            ngramModNMin = 20,
            ngramModNMax = 30,
            ngramSimpleSizeN = 9,
            ngramSimpleSizeM = 10,
            ngramSimpleMinHits = 2,
            ngramMapKSizeN = 11,
            ngramMapKSizeM = 12,
            ngramMapKMinHits = 3,
            ngramMapK4VSizeN = 13,
            ngramMapK4VSizeM = 14,
            ngramMapK4VMinHits = 4
        )

        val restored = requireNotNull(LlamaServerLaunchProfile.decode(LlamaServerLaunchProfile.encode(profile)))
        val controller = ProcessController()

        assertEquals(
            profile.copy(
                schemaVersion = LlamaServerLaunchProfile.SCHEMA_VERSION,
                loadMode = LlamaLoadMode.NONE.value,
                loras = listOf(LlamaLoraSpec("/models/style.lora"))
            ),
            restored
        )
        assertEquals(
            controller.getCommand("/bin/llama-server", profile.toLlamaConfig()),
            controller.getCommand("/bin/llama-server", restored.toLlamaConfig())
        )
        assertTrue(restored.toLlamaConfig().openClCpuTargetGpuDraft)
        assertTrue(restored.toLlamaConfig().nativeToolsEnabled)
        assertEquals(3, restored.threadsBatch)
        assertEquals(3, restored.toLlamaConfig().threadsBatch)
        assertTrue(restored.hasModel())
        assertEquals("coder.gguf · 32768 ctx · 8 threads", restored.summary())
    }

    @Test
    fun `invalid and blank saved profiles fail closed`() {
        assertNull(LlamaServerLaunchProfile.decode(null))
        assertNull(LlamaServerLaunchProfile.decode("  "))
        assertNull(LlamaServerLaunchProfile.decode("not json"))
        assertFalse(LlamaServerLaunchProfile(modelPath = "").hasModel())
    }

    @Test
    fun `profiles written before binary selection preserve global fallback`() {
        val restored = requireNotNull(
            LlamaServerLaunchProfile.decode(
                """{"schemaVersion":2,"modelPath":"/models/legacy.gguf"}"""
            )
        )

        assertEquals(LlamaServerLaunchProfile.SCHEMA_VERSION, restored.schemaVersion)
        assertNull(restored.nativeBinarySelection)
    }

    @Test
    fun `profiles written before thread batch setting default to omitted flag`() {
        val restored = requireNotNull(
            LlamaServerLaunchProfile.decode(
                """{"schemaVersion":4,"modelPath":"/models/legacy.gguf","physicalBatchSize":1024}"""
            )
        )

        assertNull(restored.threadsBatch)
        assertFalse(ProcessController().getCommand("/bin/llama-server", restored.toLlamaConfig())
            .contains("--threads-batch"))
    }

    @Test
    fun `MTP separate model toggle changes only the generated draft model arguments`() {
        val withoutSeparateModel = LlamaServerLaunchProfile(
            modelPath = "/models/main.gguf",
            speculativeEnabled = true,
            speculativeMode = LlamaSpeculativeMode.DRAFT_MTP.flagValue,
            draftModelPath = "/models/optional-draft.gguf",
            mtpUseDraftModel = false
        )
        val withSeparateModel = withoutSeparateModel.copy(mtpUseDraftModel = true)
        val controller = ProcessController()

        val withoutArgs = controller.getCommand("/bin/llama-server", withoutSeparateModel.toLlamaConfig())
        val withArgs = controller.getCommand("/bin/llama-server", withSeparateModel.toLlamaConfig())

        assertFalse("--spec-draft-model" in withoutArgs)
        assertTrue("--spec-draft-model" in withArgs)
    }
}
