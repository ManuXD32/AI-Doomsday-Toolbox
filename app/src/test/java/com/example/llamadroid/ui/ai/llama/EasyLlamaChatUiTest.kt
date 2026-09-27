package com.example.llamadroid.ui.ai.llama

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.ManagedLlamaServerException
import com.example.llamadroid.service.NativeChatToolConfig
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyLlamaChatUiTest {
    private val temporaryFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        temporaryFiles.forEach(File::delete)
    }

    @Test
    fun pickerContractAcceptsOnlyDownloadedRegularChatGgufFiles() {
        val gguf = File.createTempFile("easy-chat-", ".gguf").also(temporaryFiles::add)
        val valid = model(gguf, type = ModelType.LLM, downloaded = true)
        val vision = model(gguf, type = ModelType.VISION, downloaded = true)
        val projector = model(gguf, type = ModelType.VISION_PROJECTOR, downloaded = true)
        val notDownloaded = model(gguf, type = ModelType.LLM, downloaded = false)

        assertTrue(isEasyLlamaModel(valid))
        assertTrue(isEasyLlamaModel(vision))
        assertFalse(isEasyLlamaModel(projector))
        assertFalse(isEasyLlamaModel(notDownloaded))
    }

    @Test
    fun pickerContractRejectsNonGgufAndDirectories() {
        val text = File.createTempFile("easy-chat-", ".bin").also(temporaryFiles::add)
        val directory = Files.createTempDirectory("easy-chat-").toFile().also(temporaryFiles::add)

        assertFalse(isEasyLlamaModel(model(text, ModelType.LLM, downloaded = true)))
        assertFalse(isEasyLlamaModel(model(directory, ModelType.LLM, downloaded = true)))
    }

    @Test
    fun unknownBackendFailureUsesLocalizedFallbackInsteadOfRawCode() {
        val backendFailure = IllegalStateException("MANAGED_START_FAILED: native detail")
        assertEquals(
            "The server could not start.",
            localizedEasyLlamaFailure(backendFailure, "The server could not start.")
        )
        assertEquals(
            "Localized recovery copy",
            localizedEasyLlamaFailure(
                ManagedLlamaServerException("MANAGED_START_FAILED", "Localized recovery copy"),
                "Fallback"
            )
        )
    }

    @Test
    fun managedNativeNewChatDisablesToolsWithoutChangingManualDefaults() {
        val managed = JSONObject(requireNotNull(newChatApiParamsForServer(
            LlamaServerEntity(
                name = "Managed",
                host = "127.0.0.1",
                port = 49152,
                managedServerCardId = 12L,
                defaultApiParams = JSONObject().put(NativeChatToolConfig.KEY_TOOLS_ENABLED, true).toString()
            )
        )))
        assertFalse(managed.getBoolean(NativeChatToolConfig.KEY_TOOLS_ENABLED))
        assertFalse(managed.getBoolean(NativeChatToolConfig.KEY_DATETIME_ENABLED))
        assertFalse(managed.getBoolean(NativeChatToolConfig.KEY_CALCULATOR_ENABLED))

        val manualDefaults = JSONObject().put(NativeChatToolConfig.KEY_TOOLS_ENABLED, true).toString()
        assertEquals(
            manualDefaults,
            newChatApiParamsForServer(
                LlamaServerEntity(
                    name = "Manual",
                    host = "127.0.0.1",
                    port = 49153,
                    defaultApiParams = manualDefaults
                )
            )
        )
    }

    private fun model(file: File, type: ModelType, downloaded: Boolean): ModelEntity = ModelEntity(
        filename = file.name,
        path = file.path,
        sizeBytes = file.length(),
        type = type,
        repoId = "test/repo",
        isDownloaded = downloaded
    )
}
