package com.example.llamadroid.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.data.api.HfModelDto
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.FileInfo
import com.example.llamadroid.data.model.RepoFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.example.llamadroid.util.DebugLog
import java.io.File

class ModelManagerViewModel(
    private val repository: ModelRepository
) : ViewModel() {

    // Installed llama.cpp model-family rows shown by this manager. MTP/draft,
    // LoRA, embeddings, and legacy vision/projector rows stay actionable here;
    // Quadtrix and Whisper assets have their own managers.
    val installedModels: StateFlow<List<ModelEntity>> = repository.getModelManagerModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    // Download Progress
    val downloadProgress = repository.downloadProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Search
    private val _searchResults = MutableStateFlow<List<HfModelDto>>(emptyList())
    val searchResults = _searchResults.asStateFlow()
    
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()
    private var searchRequestId = 0

    // For file selection dialog
    private val _selectedRepoId = MutableStateFlow<String?>(null)
    val selectedRepoId = _selectedRepoId.asStateFlow()
    
    private val _availableFiles = MutableStateFlow<List<FileInfo>>(emptyList())
    val availableFiles = _availableFiles.asStateFlow()
    
    // Vision support - mmproj files
    private val _visionFiles = MutableStateFlow<List<FileInfo>>(emptyList())
    val visionFiles = _visionFiles.asStateFlow()
    
    private val _hasVisionSupport = MutableStateFlow(false)
    val hasVisionSupport = _hasVisionSupport.asStateFlow()
    
    // Cache for vision support detection in search results
    private val _repoVisionCache = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val repoVisionCache = _repoVisionCache.asStateFlow()
    
    // Track if user should be prompted to download vision projector
    private val _showVisionPrompt = MutableStateFlow(false)
    val showVisionPrompt = _showVisionPrompt.asStateFlow()
    
    private val _pendingVisionDownload = MutableStateFlow<Pair<String, FileInfo>?>(null)
    val pendingVisionDownload = _pendingVisionDownload.asStateFlow()

    fun search(query: String, type: ModelType) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            clearSearchResults()
            return
        }
        val requestId = ++searchRequestId
        viewModelScope.launch {
            _isSearching.value = true
            val filter = when {
                type == ModelType.EMBEDDING -> "bert"
                type.name.startsWith("SD_") -> "safetensors"
                type == ModelType.WHISPER -> "gguf" // Whisper CPP uses GGUF-like structure on HF
                else -> "gguf" // Default for LLM
            }
            // Pass filter to repo for LLM/GGUF/SD filtering
            val results = repository.searchModels(normalizedQuery, filter)
            if (requestId != searchRequestId) return@launch
            _searchResults.value = results
            _isSearching.value = false
            
            // Asynchronously check each repo for vision support
            results.forEach { model ->
                // Only check if not already cached
                if (!_repoVisionCache.value.containsKey(model.id)) {
                    viewModelScope.launch {
                        val hasVision = checkRepoForVision(model.id)
                        _repoVisionCache.value = _repoVisionCache.value + (model.id to hasVision)
                    }
                }
            }
        }
    }

    fun clearSearchResults() {
        searchRequestId += 1
        _searchResults.value = emptyList()
        _isSearching.value = false
    }
    
    private suspend fun checkRepoForVision(repoId: String): Boolean {
        return try {
            val repoFiles = repository.getFilesWithVisionSupport(repoId)
            repoFiles.hasVisionSupport
        } catch (e: Exception) {
            false
        }
    }
    
    fun selectRepoForDownload(repoId: String) {
        viewModelScope.launch {
            _selectedRepoId.value = repoId
            DebugLog.log("Fetching files with vision support for: $repoId")
            val repoFiles = repository.getFilesWithVisionSupport(repoId)
            DebugLog.log("Found ${repoFiles.modelFiles.size} model files, ${repoFiles.visionFiles.size} vision files")
            _availableFiles.value = repoFiles.modelFiles
            _visionFiles.value = repoFiles.visionFiles
            _hasVisionSupport.value = repoFiles.hasVisionSupport
        }
    }
    
    fun clearSelection() {
        _selectedRepoId.value = null
        _availableFiles.value = emptyList()
        _visionFiles.value = emptyList()
        _hasVisionSupport.value = false
        _showVisionPrompt.value = false
        _pendingVisionDownload.value = null
    }
    
    /**
     * Close the file selection dialog without resetting vision state.
     * This allows the vision prompt to appear after a download completes.
     */
    fun closeFileSelectionDialog() {
        _selectedRepoId.value = null
        _availableFiles.value = emptyList()
        // Keep vision state so prompt can show after download
    }

    fun downloadModel(repoId: String, filename: String, type: ModelType) {
        viewModelScope.launch {
            try {
                DebugLog.log("Starting download: $repoId/$filename")
                repository.downloadModel(repoId, filename, type, _hasVisionSupport.value)
                DebugLog.log("Download complete: $filename")
                
                // After main model download, check if we should prompt for vision projector
                if (type == ModelType.LLM && _hasVisionSupport.value && _visionFiles.value.isNotEmpty()) {
                    val visionFile = _visionFiles.value.first()
                    _pendingVisionDownload.value = Pair(repoId, visionFile)
                    _showVisionPrompt.value = true
                }
            } catch (e: Exception) {
                DebugLog.log("Download FAILED: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun downloadVisionProjector() {
        viewModelScope.launch {
            // Try pending download first (from post-download prompt), otherwise use visionFiles (from checkbox)
            val download = _pendingVisionDownload.value 
                ?: _selectedRepoId.value?.let { repoId -> 
                    _visionFiles.value.firstOrNull()?.let { file -> Pair(repoId, file) }
                }
            
            download?.let { (repoId, fileInfo) ->
                try {
                    DebugLog.log("Starting vision projector download: $repoId/${fileInfo.filename}")
                    repository.downloadModel(repoId, fileInfo.filename, ModelType.VISION_PROJECTOR)
                    DebugLog.log("Vision projector download complete: ${fileInfo.filename}")
                } catch (e: Exception) {
                    DebugLog.log("Vision projector download FAILED: ${e.message}")
                }
            }
            dismissVisionPrompt()
        }
    }
    
    fun dismissVisionPrompt() {
        _showVisionPrompt.value = false
        _pendingVisionDownload.value = null
    }

    fun deleteModel(model: ModelEntity) {
        viewModelScope.launch {
            repository.deleteModel(model)
        }
    }

    fun updateVisionSupport(model: ModelEntity, enabled: Boolean) {
        viewModelScope.launch {
            repository.updateVisionSupport(model.filename, enabled)
        }
    }
    
    /**
     * Import a local model file with user-specified type and badges
     */
    fun importLocalModel(
        path: String,
        filename: String,
        modelType: ModelType,
        hasVision: Boolean = false,
        hasEmbedding: Boolean = false,
        sdCapabilities: String? = null,
        layerCount: Int = 0  // Number of layers from GGUF parsing
    ) {
        viewModelScope.launch {
            try {
                val modelEntity = buildLocalModelEntity(
                    path = path,
                    filename = filename,
                    modelType = modelType,
                    hasVision = hasVision,
                    hasEmbedding = hasEmbedding,
                    sdCapabilities = sdCapabilities,
                    layerCount = layerCount
                )
                
                repository.insertModel(modelEntity)
                DebugLog.log("Imported local model: $filename as ${modelEntity.type.name} (vision=$hasVision, layers=$layerCount)")
            } catch (e: Exception) {
                DebugLog.log("Failed to import model: ${e.message}")
            }
        }
    }
}

/**
 * Builds the database row for a file copied into the model manager's local-import location.
 *
 * This is kept separate from the coroutine-backed import entry point so the persisted contract
 * can be tested without constructing a repository or ViewModel. Other model managers intentionally
 * keep their own import semantics and do not use this helper.
 */
internal fun buildLocalModelEntity(
    path: String,
    filename: String,
    modelType: ModelType,
    hasVision: Boolean = false,
    hasEmbedding: Boolean = false,
    sdCapabilities: String? = null,
    layerCount: Int = 0
): ModelEntity {
    val file = File(path)
    val sizeBytes = if (file.exists()) file.length() else 0L

    // Determine effective type based on badges.
    val effectiveType = if (modelType == ModelType.LLM && hasEmbedding) {
        ModelType.EMBEDDING
    } else {
        modelType
    }

    return ModelEntity(
        repoId = "local-import",
        filename = filename,
        path = path,
        sizeBytes = sizeBytes,
        type = effectiveType,
        // A model manager import has already copied the payload into its final path.
        isDownloaded = true,
        isVision = effectiveType == ModelType.LLM && hasVision,
        sdCapabilities = sdCapabilities,
        layerCount = layerCount
    )
}

/**
 * Route-owned factory for the model manager. The screen must obtain this ViewModel from the
 * NavBackStackEntry's ViewModelStore so its repository collectors and search jobs are cancelled
 * when the route is removed instead of leaking a manually remembered instance.
 */
class ModelManagerViewModelFactory(
    private val repository: ModelRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ModelManagerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ModelManagerViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
