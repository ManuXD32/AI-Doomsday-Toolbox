package com.example.llamadroid.service

import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaClientServiceLocalServerTest {
    @Test
    fun loopbackHostDetection_acceptsLocalLlamaServerHosts() {
        assertTrue(isNativeChatLoopbackHost("localhost"))
        assertTrue(isNativeChatLoopbackHost("127.0.0.1"))
        assertTrue(isNativeChatLoopbackHost("http://127.0.0.1"))
        assertTrue(isNativeChatLoopbackHost("::1"))
        assertTrue(isNativeChatLoopbackHost("[::1]"))
    }

    @Test
    fun loopbackHostDetection_rejectsRemoteHosts() {
        assertFalse(isNativeChatLoopbackHost("192.168.1.20"))
        assertFalse(isNativeChatLoopbackHost("example.com"))
    }

    @Test
    fun nativeChatLocalHostForServer_preservesIpFamily() {
        assertEquals("127.0.0.1", nativeChatLocalHostForServer("localhost"))
        assertEquals("127.0.0.1", nativeChatLocalHostForServer("http://127.0.0.1"))
        assertEquals("::1", nativeChatLocalHostForServer("[::1]"))
    }

    @Test
    fun localLlamaServerAutoStartPolicy_onlyAllowsStoppedLoopbackLlamaServer() {
        val localLlamaServer = LlamaServerEntity(
            name = "Local llama-server",
            host = "127.0.0.1",
            port = 8080,
            engine = LlamaServerEntity.ENGINE_LLAMA_SERVER
        )
        val localLiteRt = localLlamaServer.copy(engine = LlamaServerEntity.ENGINE_LITERT_LM)
        val remoteLlamaServer = localLlamaServer.copy(host = "192.168.1.44")

        assertTrue(shouldAutoStartLocalLlamaServer(localLlamaServer, ServerState.Stopped))
        assertFalse(shouldAutoStartLocalLlamaServer(localLiteRt, ServerState.Stopped))
        assertFalse(shouldAutoStartLocalLlamaServer(remoteLlamaServer, ServerState.Stopped))
        assertFalse(shouldAutoStartLocalLlamaServer(localLlamaServer, ServerState.Error("failed")))
        assertFalse(shouldAutoStartLocalLlamaServer(localLlamaServer, ServerState.Running(8080)))
    }

    @Test
    fun recentLocalLlamaServerFailure_expiresAndCanBeCleared() {
        LlamaService.clearRecentStartupFailure()

        LlamaService.recordRecentStartupFailure(nowMs = 1_000L)

        assertTrue(LlamaService.hasRecentStartupFailure(nowMs = 2_000L))
        assertFalse(LlamaService.hasRecentStartupFailure(nowMs = 10 * 60 * 1000L))

        LlamaService.recordRecentStartupFailure(nowMs = 5_000L)
        LlamaService.clearRecentStartupFailure()

        assertFalse(LlamaService.hasRecentStartupFailure(nowMs = 6_000L))
    }

    @Test
    fun localLlamaServerAutoStart_attachesMmprojOnlyForImageVisionTurns() {
        val visionServer = LlamaServerEntity(
            name = "Vision llama-server",
            host = "127.0.0.1",
            port = 8080,
            engine = LlamaServerEntity.ENGINE_LLAMA_SERVER,
            supportsVision = true
        )
        val textServer = visionServer.copy(supportsVision = false)

        assertTrue(
            shouldAttachMmprojForLocalAutoStart(
                server = visionServer,
                imagePath = "/tmp/image.png",
                visionEnabled = true,
                selectedMmprojPath = "/tmp/projector.gguf"
            )
        )
        assertFalse(
            shouldAttachMmprojForLocalAutoStart(
                server = visionServer,
                imagePath = null,
                visionEnabled = true,
                selectedMmprojPath = "/tmp/projector.gguf"
            )
        )
        assertFalse(
            shouldAttachMmprojForLocalAutoStart(
                server = textServer,
                imagePath = "/tmp/image.png",
                visionEnabled = true,
                selectedMmprojPath = "/tmp/projector.gguf"
            )
        )
        assertFalse(
            shouldAttachMmprojForLocalAutoStart(
                server = visionServer,
                imagePath = "/tmp/image.png",
                visionEnabled = true,
                selectedMmprojPath = null
            )
        )
    }

    @Test
    fun liteRtAutoGpuRetries_areConservativeForNonGemmaCatalogModels() {
        val gemma = LiteRtModelEntity(
            displayName = "Gemma 4 E2B",
            path = "/tmp/gemma-4-E2B-it.litertlm",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            filename = "gemma-4-E2B-it.litertlm",
            backendPreference = LITERT_BACKEND_AUTO,
            supportsGpu = true
        )
        val vibethinker = LiteRtModelEntity(
            displayName = "vibethinker 3b litertlm",
            path = "/tmp/vibethinker.litertlm",
            repoId = "manojpanda/vibethinker-3b-litertlm",
            filename = "model.litertlm",
            backendPreference = LITERT_BACKEND_AUTO,
            supportsGpu = true
        )
        val forcedGpu = vibethinker.copy(backendPreference = LITERT_BACKEND_GPU)

        assertEquals(2, liteRtAutoGpuWorkerMaxAttempts(gemma))
        assertEquals(1, liteRtAutoGpuWorkerMaxAttempts(vibethinker))
        assertEquals(1, liteRtAutoGpuWorkerMaxAttempts(forcedGpu))
    }
}
