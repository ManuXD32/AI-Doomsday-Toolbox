package com.example.llamadroid.ui.ai.llama

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.data.api.LlamaApi
import com.example.llamadroid.data.api.OllamaApi
import com.example.llamadroid.data.api.OllamaModel
import com.example.llamadroid.data.api.OllamaShowRequest
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.buildLlamaServerBaseUrl
import com.example.llamadroid.data.model.deriveOllamaCapabilityState
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.util.DebugLog
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class LlamaServerViewModel(
    private val repository: LlamaRepository
) : ViewModel() {
    private val ollamaJson = Json { ignoreUnknownKeys = true }

    private val _servers = MutableStateFlow<List<LlamaServerEntity>>(emptyList())
    val servers = _servers.asStateFlow()

    init {
        viewModelScope.launch {
            repository.allServers.collectLatest {
                _servers.value = it
            }
        }
    }

    fun saveServer(server: LlamaServerEntity) {
        viewModelScope.launch {
            val savedServer = if (server.id == 0L) {
                val id = repository.saveServer(server)
                server.copy(id = id)
            } else {
                repository.updateServer(server)
                server
            }
            refreshServerMetadata(savedServer)
        }
    }

    fun updateServer(server: LlamaServerEntity) {
        saveServer(server)
    }

    fun deleteServer(server: LlamaServerEntity) {
        viewModelScope.launch {
            repository.deleteServer(server)
        }
    }

    // Since we don't have a "global" selected server state in DB yet (only lastUsed),
    // we might just navigate to chat from here. 
    // For now, updating usage timestamp is enough.
    fun selectServer(server: LlamaServerEntity) {
        viewModelScope.launch {
            repository.updateServerUsage(server.id)
        }
    }

    fun fetchModelName(server: LlamaServerEntity) {
        refreshServerMetadata(server)
    }

    fun refreshServerMetadata(server: LlamaServerEntity) {
        viewModelScope.launch {
            when {
                server.isLiteRtEngine() -> refreshLiteRtMetadata(server)
                server.isOllamaEngine() -> refreshOllamaMetadata(server)
                server.isLlamaSwapEngine() -> refreshLlamaSwapMetadata(server)
                else -> refreshLlamaServerMetadata(server)
            }
        }
    }

    fun loadOllamaModels(
        host: String,
        port: Int,
        onResult: (Result<List<OllamaModel>>) -> Unit
    ) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    fetchOllamaModels(buildLlamaServerBaseUrl(host, port))
                        .sortedBy { it.name.lowercase() }
                }
            }
            onResult(result)
        }
    }

    fun loadOllamaCapabilities(
        host: String,
        port: Int,
        modelName: String,
        onResult: (Result<Pair<Boolean, Boolean>>) -> Unit
    ) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val info = fetchOllamaModelInfo(
                        baseUrl = buildLlamaServerBaseUrl(host, port),
                        modelName = modelName
                    )
                    val capabilities = deriveOllamaCapabilityState(info.capabilities)
                    capabilities.supportsVision to capabilities.supportsAudio
                }
            }
            onResult(result)
        }
    }

    fun loadLlamaSwapModels(
        host: String,
        port: Int,
        onResult: (Result<List<String>>) -> Unit
    ) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    fetchOpenAiModelIds(buildLlamaServerBaseUrl(host, port))
                        .sortedBy { it.lowercase() }
                }
            }
            onResult(result)
        }
    }

    private suspend fun refreshLlamaServerMetadata(server: LlamaServerEntity) {
        try {
            val modelName = withContext(Dispatchers.IO) {
                fetchLlamaServerModelName(server)
            }
            repository.updateServerModelName(server.id, modelName)
            if (modelName != null) {
                DebugLog.log("Fetched llama-server model name for ${server.name}: $modelName")
            }
        } catch (e: Exception) {
            DebugLog.log("Error fetching llama-server model name: ${e.message}")
            repository.updateServerModelName(server.id, null)
        }
    }

    private suspend fun refreshLiteRtMetadata(server: LlamaServerEntity) {
        repository.updateServerModelMetadata(
            id = server.id,
            modelName = server.modelName?.ifBlank { server.name } ?: server.name,
            supportsVision = server.supportsVision,
            supportsAudio = server.supportsAudio
        )
        DebugLog.log("LiteRT server metadata uses local model '${server.modelName ?: server.name}'")
    }

    private suspend fun refreshOllamaMetadata(server: LlamaServerEntity) {
        val modelName = server.modelName?.trim().orEmpty()
        if (modelName.isBlank()) {
            repository.updateServerModelMetadata(
                id = server.id,
                modelName = null,
                supportsVision = false,
                supportsAudio = false
            )
            return
        }

        try {
            val info = withContext(Dispatchers.IO) {
                fetchOllamaModelInfo(server.baseUrl(), modelName)
            }
            val capabilities = deriveOllamaCapabilityState(info.capabilities)
            repository.updateServerModelMetadata(
                id = server.id,
                modelName = modelName,
                supportsVision = capabilities.supportsVision,
                supportsAudio = capabilities.supportsAudio
            )
            DebugLog.log(
                "Fetched Ollama metadata for ${server.name}: $modelName vision=${capabilities.supportsVision} audio=${capabilities.supportsAudio}"
            )
        } catch (e: Exception) {
            DebugLog.log("Error fetching Ollama metadata: ${e.message}")
            repository.updateServerModelMetadata(
                id = server.id,
                modelName = modelName,
                supportsVision = false,
                supportsAudio = false
            )
        }
    }

    private suspend fun refreshLlamaSwapMetadata(server: LlamaServerEntity) {
        try {
            val models = withContext(Dispatchers.IO) {
                fetchOpenAiModelIds(server.baseUrl())
            }
            val currentModel = server.modelName?.trim().orEmpty()
            val selectedModel = when {
                currentModel.isNotBlank() && models.contains(currentModel) -> currentModel
                currentModel.isNotBlank() -> currentModel
                else -> models.firstOrNull()
            }
            repository.updateServerModelName(server.id, selectedModel)
            DebugLog.log("Fetched llama-swap models for ${server.name}: ${models.size}")
        } catch (e: Exception) {
            DebugLog.log("Error fetching llama-swap models: ${e.message}")
        }
    }

    private fun buildLlamaApi(baseUrl: String): LlamaApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LlamaApi::class.java)
    }

    private fun buildOllamaApi(baseUrl: String): OllamaApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ollamaJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OllamaApi::class.java)
    }

    private fun fetchLlamaServerModelName(server: LlamaServerEntity): String? {
        val response = buildLlamaApi(server.baseUrl()).getModels().execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code()}")
        }
        return response.body()?.data?.firstOrNull()?.id
    }

    private fun fetchOpenAiModelIds(baseUrl: String): List<String> {
        val response = buildLlamaApi(baseUrl).getModels().execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code()}")
        }
        return response.body()?.data.orEmpty()
            .mapNotNull { it.id.takeIf { id -> id.isNotBlank() } }
    }

    private suspend fun fetchOllamaModels(baseUrl: String): List<OllamaModel> =
        buildOllamaApi(baseUrl).getTags().models

    private suspend fun fetchOllamaModelInfo(baseUrl: String, modelName: String) =
        buildOllamaApi(baseUrl).showModel(OllamaShowRequest(name = modelName))
}

class LlamaServerViewModelFactory(private val repository: LlamaRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LlamaServerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LlamaServerViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
