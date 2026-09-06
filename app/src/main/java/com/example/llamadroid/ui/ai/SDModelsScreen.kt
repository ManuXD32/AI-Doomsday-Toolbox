package com.example.llamadroid.ui.ai

import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.ui.components.AppScrollableTabRow
import com.example.llamadroid.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.data.api.HfModelDto
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_VID_GEN
import com.example.llamadroid.data.db.buildSdCapabilities
import com.example.llamadroid.data.db.hasSdCapability
import com.example.llamadroid.data.db.parseSdCapabilities
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.FileInfo
import com.example.llamadroid.data.model.ModelLibraryManager
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.isStableDiffusionArtifact
import com.example.llamadroid.ui.components.DownloadTaskSection
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.sd.SdModelFamily
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdArtifactInspection
import com.example.llamadroid.sd.SdArtifactInspector
import com.example.llamadroid.sd.SdArtifactRole
import com.example.llamadroid.sd.SdInspectionConfidence
import com.example.llamadroid.sd.buildSdCompatProfiles
import com.example.llamadroid.sd.defaultCapabilitiesForFamily
import com.example.llamadroid.sd.defaultCompatProfilesFor
import com.example.llamadroid.sd.inferSdFamily
import com.example.llamadroid.sd.matchesSdFamily
import com.example.llamadroid.sd.parseSdCompatProfiles
import com.example.llamadroid.sd.sdFamilyEnum
import com.example.llamadroid.sd.sdArtifactInspection
import com.example.llamadroid.sd.withSdArtifactInspection
import com.example.llamadroid.data.model.SdArtifactValidationException
import com.example.llamadroid.service.smokeCheckSdADetailerDetector
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Recommended SD model search queries
 */
data class SDSearchSuggestion(
    val name: String,
    val query: String,
    val capabilities: List<SDCapability>
)

data class PendingSdDiscoverDownload(
    val repoId: String,
    val fileInfo: FileInfo,
    val suggestedType: SDModelSelectionType
)

enum class SDCapability(
    val token: String,
    @StringRes val labelRes: Int,
    val color: Long
) {
    TXT2IMG(SD_CAPABILITY_TXT2IMG, R.string.sd_models_badge_txt2img, 0xFF4CAF50),
    IMG2IMG(SD_CAPABILITY_IMG2IMG, R.string.sd_models_badge_img2img, 0xFF2196F3),
    UPSCALE("upscale", R.string.sd_models_badge_upscale, 0xFFFF9800),
    FLUX("flux", R.string.sd_models_badge_flux, 0xFF9C27B0),
    VID_GEN(SD_CAPABILITY_VID_GEN, R.string.sd_models_badge_vid_gen, 0xFFE53935)
}

enum class SDModelSelectionType(
    val storedType: ModelType,
    @StringRes val labelRes: Int
) {
    CHECKPOINT(ModelType.SD_CHECKPOINT, R.string.sd_type_checkpoint),
    DIFFUSION(ModelType.SD_DIFFUSION, R.string.sd_type_diffusion),
    VIDEO_GEN(ModelType.SD_DIFFUSION, R.string.sd_type_video_gen),
    CLIP_L(ModelType.SD_CLIP_L, R.string.sd_type_clip_l),
    CLIP_G(ModelType.SD_CLIP_G, R.string.sd_type_clip_g),
    T5XXL(ModelType.SD_T5XXL, R.string.sd_type_t5xxl),
    TAE(ModelType.SD_TAE, R.string.sd_type_tae),
    VAE(ModelType.SD_VAE, R.string.sd_type_vae),
    CONTROLNET(ModelType.SD_CONTROLNET, R.string.sd_type_controlnet),
    LORA(ModelType.SD_LORA, R.string.sd_type_lora),
    TEXTUAL_INVERSION(
        ModelType.SD_TEXTUAL_INVERSION,
        R.string.sd_type_textual_inversion
    ),
    PHOTOMAKER(ModelType.SD_PHOTOMAKER, R.string.sd_type_photomaker),
    CLIP_VISION(ModelType.SD_CLIP_VISION, R.string.sd_type_clip_vision),
    IP_ADAPTER(ModelType.SD_IP_ADAPTER, R.string.sd_type_ip_adapter),
    ADETAILER(ModelType.SD_ADETAILER, R.string.sd_type_adetailer),
    IMAGE_LLM(ModelType.LLM, R.string.sd_type_image_llm),
    IMAGE_LLM_VISION(ModelType.VISION_PROJECTOR, R.string.sd_type_image_llm_vision),
    UPSCALER(ModelType.SD_UPSCALER, R.string.sd_type_upscaler)
}

private val SD_MANAGER_SELECTION_TYPES = SDModelSelectionType.entries
    .filterNot {
        // Image LLMs and projectors are managed by ModelManagerScreen, never by
        // the Stable Diffusion installed/import card.
        it == SDModelSelectionType.IMAGE_LLM || it == SDModelSelectionType.IMAGE_LLM_VISION
    }

private enum class SdInspectionRowStatus {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED
}

private data class SdInspectionRowState(
    val status: SdInspectionRowStatus = SdInspectionRowStatus.IDLE,
    val detail: String? = null
)

private class SdAdetailerImportException : Exception()

/**
 * SDModelsScreen - Manage Stable Diffusion models with HuggingFace search
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SDModelsScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { com.example.llamadroid.data.SettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    val repository = remember { ModelRepository(context, db.modelDao()) }
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // Installed SD models - Classic types
    val sdCheckpoints by db.modelDao().getModelsByType(ModelType.SD_CHECKPOINT)
        .collectAsState(initial = emptyList())
    val sdUpscalers by db.modelDao().getModelsByType(ModelType.SD_UPSCALER)
        .collectAsState(initial = emptyList())
    
    // Standalone diffusion and side-component types
    val sdDiffusionModels by db.modelDao().getModelsByType(ModelType.SD_DIFFUSION)
        .collectAsState(initial = emptyList())
    val sdClipLModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_L)
        .collectAsState(initial = emptyList())
    val sdClipGModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_G)
        .collectAsState(initial = emptyList())
    val sdT5xxlModels by db.modelDao().getModelsByType(ModelType.SD_T5XXL)
        .collectAsState(initial = emptyList())
    val sdTaeModels by db.modelDao().getModelsByType(ModelType.SD_TAE)
        .collectAsState(initial = emptyList())
    val sdVaeModels by db.modelDao().getModelsByType(ModelType.SD_VAE)
        .collectAsState(initial = emptyList())
    val sdControlNetModels by db.modelDao().getModelsByType(ModelType.SD_CONTROLNET)
        .collectAsState(initial = emptyList())
    val sdLoraModels by db.modelDao().getModelsByType(ModelType.SD_LORA)
        .collectAsState(initial = emptyList())
    val sdPhotoMakerModels by db.modelDao().getModelsByType(ModelType.SD_PHOTOMAKER)
        .collectAsState(initial = emptyList())
    val sdClipVisionModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_VISION)
        .collectAsState(initial = emptyList())
    val sdIpAdapterModels by db.modelDao().getModelsByType(ModelType.SD_IP_ADAPTER)
        .collectAsState(initial = emptyList())
    val sdAdetailerModels by db.modelDao().getModelsByType(ModelType.SD_ADETAILER)
        .collectAsState(initial = emptyList())
    // Total installed count
    val totalInstalledCount = sdCheckpoints.size + sdUpscalers.size +
        sdDiffusionModels.size + sdClipLModels.size + sdClipGModels.size +
        sdT5xxlModels.size + sdTaeModels.size + sdVaeModels.size +
        sdControlNetModels.size + sdLoraModels.size + sdPhotoMakerModels.size +
        sdClipVisionModels.size + sdIpAdapterModels.size +
        sdAdetailerModels.size
    
    // Download progress
    val downloadProgress by DownloadProgressHolder.progress.collectAsState()
    
    // Active downloads count
    val activeDownloads = downloadProgress.filter { it.value > 0f && it.value < 1f }
    
    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<HfModelDto>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchRequestId by remember { mutableIntStateOf(0) }

    val updateSearchQuery: (String) -> Unit = { value ->
        if (value != searchQuery) {
            searchRequestId += 1
            searchQuery = value
            searchResults = emptyList()
            isSearching = false
        }
    }
    
    // File selection state
    var selectedRepoId by remember { mutableStateOf<String?>(null) }
    var availableFiles by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var pendingDiscoverDownload by remember { mutableStateOf<PendingSdDiscoverDownload?>(null) }
    var selectedDiscoverType by remember { mutableStateOf(SDModelSelectionType.CHECKPOINT) }
    var discoverSdFamily by remember { mutableStateOf<SdModelFamily?>(null) }
    var discoverSdVariant by remember { mutableStateOf("") }
    var discoverCompatProfiles by remember { mutableStateOf("") }
    
    // FLUX component reminder dialog
    val showFluxReminderPending by settingsRepo.showFluxReminderPending.collectAsState()
    
    // Selected tab: 0 = Installed, 1 = Downloading, 2 = Discover
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // Search suggestions organized by model type and RAM requirements
    val suggestions = remember {
        listOf(
            // === Classic SD Models ===
            SDSearchSuggestion(
                "SD 1.5 GGUF (4GB+)",
                "stable-diffusion gguf q4",
                listOf(SDCapability.TXT2IMG, SDCapability.IMG2IMG)
            ),
            SDSearchSuggestion(
                "SDXL GGUF (8GB+)",
                "sdxl gguf q4",
                listOf(SDCapability.TXT2IMG, SDCapability.IMG2IMG)
            ),
            
            SDSearchSuggestion(
                resources.getString(R.string.sd_models_ip_adapter_search_sd15),
                "h94/IP-Adapter",
                listOf(SDCapability.TXT2IMG, SDCapability.IMG2IMG)
            ),
            SDSearchSuggestion(
                resources.getString(R.string.sd_models_ip_adapter_search_sdxl),
                "h94/IP-Adapter SDXL",
                listOf(SDCapability.TXT2IMG, SDCapability.IMG2IMG)
            ),
            SDSearchSuggestion(
                resources.getString(R.string.sd_models_clip_vision_search),
                "CLIP-Vision safetensors",
                listOf(SDCapability.TXT2IMG, SDCapability.IMG2IMG)
            ),

            // === Standalone Diffusion Models ===
            SDSearchSuggestion(
                "FLUX Schnell Q4 (8GB+)",
                "city96/FLUX.1-schnell-gguf",
                listOf(SDCapability.FLUX, SDCapability.TXT2IMG)
            ),
            SDSearchSuggestion(
                "FLUX Dev Q4 (12GB+)",
                "city96/FLUX.1-dev-gguf",
                listOf(SDCapability.FLUX, SDCapability.TXT2IMG)
            ),
            SDSearchSuggestion(
                "FLUX Lite (4GB+)",
                "flux gguf q2 q3",
                listOf(SDCapability.FLUX, SDCapability.TXT2IMG)
            ),
            
            // === FLUX Text Encoders ===
            SDSearchSuggestion(
                "T5-XXL Encoder (GGUF)",
                "city96/t5-v1_1-xxl-encoder-gguf",
                listOf(SDCapability.FLUX)
            ),
            SDSearchSuggestion(
                "CLIP-L Encoder (GGUF)",
                "zer0int/CLIP-GmP-ViT-L-14",
                listOf(SDCapability.FLUX)
            ),
            
            // === VAE Models ===
            SDSearchSuggestion(
                "FLUX VAE (GGUF)",
                "city96/FLUX.1-dev-gguf",
                listOf(SDCapability.FLUX)
            ),
            SDSearchSuggestion(
                "SDXL VAE",
                "sdxl-vae-fp16-fix",
                listOf(SDCapability.TXT2IMG, SDCapability.IMG2IMG)
            ),
            
            // === ControlNet ===
            SDSearchSuggestion(
                "ControlNet GGUF",
                "controlnet gguf",
                listOf(SDCapability.IMG2IMG)
            ),
            
            // === LoRA ===
            SDSearchSuggestion(
                "LoRA Models",
                "lora safetensors",
                listOf(SDCapability.TXT2IMG, SDCapability.IMG2IMG)
            ),
            
            // === Upscalers ===
            SDSearchSuggestion(
                "ESRGAN 4x Upscaler",
                "esrgan 4x",
                listOf(SDCapability.UPSCALE)
            ),
            SDSearchSuggestion(
                "RealESRGAN Anime",
                "realesrgan anime",
                listOf(SDCapability.UPSCALE)
            )
        )
    }
    
    // Search function. Accept the requested text explicitly so tapping a suggestion does not
    // race Compose state propagation and accidentally submit the previous field value.
    val launchSearch: (String) -> Unit = { rawQuery ->
        val requestedQuery = rawQuery.trim()
        if (requestedQuery.isNotBlank()) {
            val requestId = searchRequestId + 1
            searchRequestId = requestId
            isSearching = true
            keyboardController?.hide()
            scope.launch {
                val results = try {
                    repository.searchModels(requestedQuery)
                } catch (e: Exception) {
                    emptyList()
                }
                if (requestId == searchRequestId && requestedQuery == searchQuery.trim()) {
                    searchResults = results
                    isSearching = false
                }
            }
        }
    }
    val doSearch: () -> Unit = { launchSearch(searchQuery) }
    
    // Load files for a repo
    val loadRepoFiles: (String) -> Unit = { repoId ->
        selectedRepoId = repoId
        isLoadingFiles = true
        scope.launch {
            try {
                val files = repository.getGgufFilesWithSize(repoId)
                availableFiles = files
            } catch (e: Exception) {
                availableFiles = emptyList()
            }
            isLoadingFiles = false
        }
    }

    val startDiscoverDownload: (String, FileInfo, SDModelSelectionType) -> Unit =
        { repoId, fileInfo, selectionType ->
            val type = selectionType.storedType
            repository.startDownloadAsync(
                repoId = repoId,
                filename = fileInfo.filename,
                type = type,
                sdCapabilities = buildCapabilitiesForSelection(selectionType, sdFamily = discoverSdFamily),
                sdFamily = if (isImageMainSelection(selectionType)) discoverSdFamily?.storedValue else null,
                sdVariant = discoverSdVariant.ifBlank { null },
                sdCompatProfiles = if (requiresCompatProfiles(selectionType)) discoverCompatProfiles.ifBlank { null } else null
            )

            if (
                type == ModelType.SD_DIFFUSION ||
                type == ModelType.SD_CLIP_L ||
                type == ModelType.SD_CLIP_G ||
                type == ModelType.SD_T5XXL ||
                type == ModelType.SD_TAE ||
                type == ModelType.SD_VAE
            ) {
                settingsRepo.setShowFluxReminderPending(true)
            }

            selectedRepoId = null
            availableFiles = emptyList()
            pendingDiscoverDownload = null
            selectedDiscoverType = SDModelSelectionType.CHECKPOINT
            discoverSdFamily = null
            discoverSdVariant = ""
            discoverCompatProfiles = ""
        }
    
    // File selection dialog
    if (selectedRepoId != null && availableFiles.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { 
                selectedRepoId = null
                availableFiles = emptyList()
            },
            title = { 
                Column {
                    Text(
                        stringResource(R.string.sd_models_select_file),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        selectedRepoId ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(availableFiles) { fileInfo ->
                        Card(
                            onClick = {
                                // Determine model type based on filename and repo patterns
                                val repoLower = selectedRepoId?.lowercase() ?: ""
                                val fileLower = fileInfo.filename.lowercase()
                                
                                val selectionType = inferSelectionType(
                                    repoId = repoLower,
                                    filename = fileLower
                                )

                                pendingDiscoverDownload = PendingSdDiscoverDownload(
                                    repoId = selectedRepoId!!,
                                    fileInfo = fileInfo,
                                    suggestedType = selectionType
                                )
                                selectedDiscoverType = selectionType
                                val metadata = defaultSdMetadataForSelection(
                                    selectionType = selectionType,
                                    repoId = selectedRepoId!!,
                                    filename = fileInfo.filename
                                )
                                discoverSdFamily = metadata.family
                                discoverSdVariant = metadata.variant.orEmpty()
                                discoverCompatProfiles = metadata.compatProfiles.orEmpty()
                                selectedRepoId = null
                                availableFiles = emptyList()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        fileInfo.filename,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        fileInfo.formattedSize(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.sd_models_tab_downloading),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { 
                    selectedRepoId = null
                    availableFiles = emptyList()
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (pendingDiscoverDownload != null) {
        LaunchedEffect(selectedDiscoverType, pendingDiscoverDownload?.fileInfo?.filename) {
            pendingDiscoverDownload?.let { pending ->
                val metadata = defaultSdMetadataForSelection(
                    selectionType = selectedDiscoverType,
                    repoId = pending.repoId,
                    filename = pending.fileInfo.filename
                )
                if (isImageMainSelection(selectedDiscoverType)) {
                    discoverSdFamily = metadata.family
                    discoverSdVariant = metadata.variant.orEmpty()
                } else {
                    discoverCompatProfiles = metadata.compatProfiles.orEmpty()
                }
            }
        }
        AlertDialog(
            onDismissRequest = {
                pendingDiscoverDownload = null
                selectedDiscoverType = SDModelSelectionType.CHECKPOINT
                discoverSdFamily = null
                discoverSdVariant = ""
                discoverCompatProfiles = ""
            },
            title = { Text(stringResource(R.string.sd_models_download_confirm_title)) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    item {
                        Text(
                            stringResource(
                                R.string.sd_models_download_confirm_desc,
                                pendingDiscoverDownload!!.fileInfo.filename
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    items(SD_MANAGER_SELECTION_TYPES) { type ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedDiscoverType == type,
                                    onClick = { selectedDiscoverType = type }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedDiscoverType == type,
                                onClick = { selectedDiscoverType = type }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(type.labelRes),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        SDMetadataEditor(
                            selectionType = selectedDiscoverType,
                            sdFamily = discoverSdFamily,
                            onSdFamilyChange = { discoverSdFamily = it },
                            sdVariant = discoverSdVariant,
                            onSdVariantChange = { discoverSdVariant = it },
                            compatProfiles = discoverCompatProfiles,
                            onCompatProfilesChange = { discoverCompatProfiles = it }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDiscoverDownload?.let { pending ->
                            startDiscoverDownload(
                                pending.repoId,
                                pending.fileInfo,
                                selectedDiscoverType
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_download))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingDiscoverDownload = null
                        selectedDiscoverType = SDModelSelectionType.CHECKPOINT
                        discoverSdFamily = null
                        discoverSdVariant = ""
                        discoverCompatProfiles = ""
                    }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
    
    // Loading dialog
    if (isLoadingFiles) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.sd_models_loading_files)) },
            text = { CircularProgressIndicator() },
            confirmButton = {}
        )
    }
    
    AppPageBackground {
        Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.sd_models_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // Tab Row
        AppScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.sd_models_tab_installed, totalInstalledCount)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.sd_models_tab_downloading))
                        if (activeDownloads.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Badge { Text("${activeDownloads.size}") }
                        }
                    }
                }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text(stringResource(R.string.sd_models_tab_discover)) }
            )
        }
        
        when (selectedTab) {
            0 -> InstalledSDModelsTab(
                checkpoints = sdCheckpoints,
                upscalers = sdUpscalers,
                diffusionModels = sdDiffusionModels,
                clipLModels = sdClipLModels,
                clipGModels = sdClipGModels,
                t5xxlModels = sdT5xxlModels,
                taeModels = sdTaeModels,
                vaeModels = sdVaeModels,
                controlNetModels = sdControlNetModels,
                loraModels = sdLoraModels,
                photoMakerModels = sdPhotoMakerModels,
                clipVisionModels = sdClipVisionModels,
                ipAdapterModels = sdIpAdapterModels,
                adetailerModels = sdAdetailerModels,
                onDelete = { model ->
                    scope.launch {
                        repository.deleteModel(model)
                    }
                },
                repository = repository,
                settingsRepo = settingsRepo,
                onOpenOnnxModels = { navController.navigate(Screen.OnnxModels.route) }
            )
            1 -> DownloadingTab(
                downloadProgress = downloadProgress,
                onCancel = { filename ->
                    com.example.llamadroid.util.Downloader.cancelDownload(filename)
                    DownloadProgressHolder.removeProgress(filename)
                }
            )
            2 -> DiscoverTab(
                searchQuery = searchQuery,
                onQueryChange = updateSearchQuery,
                onSearch = doSearch,
                isSearching = isSearching,
                searchResults = searchResults,
                suggestions = suggestions,
                onSuggestionClick = { query ->
                    updateSearchQuery(query)
                    launchSearch(query)
                },
                onRepoClick = loadRepoFiles
            )
        }
        }
    }
}

@Composable
private fun InstalledSDModelsTab(
    checkpoints: List<ModelEntity>,
    upscalers: List<ModelEntity>,
    diffusionModels: List<ModelEntity>,
    clipLModels: List<ModelEntity>,
    clipGModels: List<ModelEntity>,
    t5xxlModels: List<ModelEntity>,
    taeModels: List<ModelEntity>,
    vaeModels: List<ModelEntity>,
    controlNetModels: List<ModelEntity>,
    loraModels: List<ModelEntity>,
    photoMakerModels: List<ModelEntity>,
    clipVisionModels: List<ModelEntity>,
    ipAdapterModels: List<ModelEntity>,
    adetailerModels: List<ModelEntity>,
    onDelete: (ModelEntity) -> Unit,
    repository: ModelRepository,
    settingsRepo: com.example.llamadroid.data.SettingsRepository,
    onOpenOnnxModels: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    // The installed rows are emitted from separate Room flows, so keep one
    // deterministic list for bulk inspection.  Inspection only reads bounded
    // headers/descriptors and runs on Dispatchers.IO; it never starts the
    // native runtime or asks sdcpp to load model weights.
    val allInstalledModels = listOf(
        checkpoints,
        upscalers,
        diffusionModels,
        clipLModels,
        clipGModels,
        t5xxlModels,
        taeModels,
        vaeModels,
        controlNetModels,
        loraModels,
        photoMakerModels,
        clipVisionModels,
        ipAdapterModels,
        adetailerModels
    ).flatten()
        .filter { it.type.isStableDiffusionArtifact() }
        .distinctBy { it.path }
    val inspectionStates = remember { mutableStateMapOf<String, SdInspectionRowState>() }
    var isInspectingAll by remember { mutableStateOf(false) }
    var inspectedCount by remember { mutableIntStateOf(0) }
    var inspectionFailureCount by remember { mutableIntStateOf(0) }
    var inspectionSummaryVisible by remember { mutableStateOf(false) }

    suspend fun inspectInstalledModel(model: ModelEntity): Result<ModelEntity> =
        withContext(Dispatchers.IO) {
            runCatching { repository.ensureSdArtifactInspection(model) }
        }

    val inspectOne: (ModelEntity) -> Unit = { model ->
        val currentState = inspectionStates[model.path]
        if (!isInspectingAll && currentState?.status != SdInspectionRowStatus.RUNNING) {
            inspectionSummaryVisible = false
            inspectionStates[model.path] = SdInspectionRowState(SdInspectionRowStatus.RUNNING)
            scope.launch {
                val result = inspectInstalledModel(model)
                result.fold(
                    onSuccess = {
                        inspectionStates[model.path] =
                            SdInspectionRowState(SdInspectionRowStatus.SUCCEEDED)
                    },
                    onFailure = { error ->
                        inspectionStates[model.path] = SdInspectionRowState(
                            status = SdInspectionRowStatus.FAILED,
                            detail = error.message?.replace(Regex("\\s+"), " ")?.trim()?.take(180)
                        )
                    }
                )
            }
        }
    }

    val inspectAll: () -> Unit = {
        if (!isInspectingAll && allInstalledModels.isNotEmpty()) {
            inspectionSummaryVisible = false
            isInspectingAll = true
            inspectedCount = 0
            inspectionFailureCount = 0
            scope.launch {
                allInstalledModels.forEachIndexed { index, model ->
                    inspectionStates[model.path] =
                        SdInspectionRowState(SdInspectionRowStatus.RUNNING)
                    val result = inspectInstalledModel(model)
                    result.fold(
                        onSuccess = {
                            inspectionStates[model.path] =
                                SdInspectionRowState(SdInspectionRowStatus.SUCCEEDED)
                        },
                        onFailure = { error ->
                            inspectionFailureCount++
                            inspectionStates[model.path] = SdInspectionRowState(
                                status = SdInspectionRowStatus.FAILED,
                                detail = error.message?.replace(Regex("\\s+"), " ")?.trim()?.take(180)
                            )
                        }
                    )
                    inspectedCount = index + 1
                }
                isInspectingAll = false
                inspectionSummaryVisible = true
            }
        }
    }
    
    // Import state - FILE FIRST approach (FAB launches picker, then show dialog)
    var showImportDialog by remember { mutableStateOf(false) }
    var selectedImportType by remember { mutableStateOf(SDModelSelectionType.CHECKPOINT) }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingFilename by remember { mutableStateOf("") }
    var supportsTxt2Img by remember { mutableStateOf(true) }
    var supportsImg2Img by remember { mutableStateOf(true) }
    var importSdFamily by remember { mutableStateOf<SdModelFamily?>(null) }
    var importSdVariant by remember { mutableStateOf("") }
    var importCompatProfiles by remember { mutableStateOf("") }
    var pendingInspection by remember { mutableStateOf<SdArtifactInspection?>(null) }
    var pendingInspectionError by remember { mutableStateOf<String?>(null) }
    var isInspectingPending by remember { mutableStateOf(false) }
    var inspectionSelectionType by remember { mutableStateOf<SDModelSelectionType?>(null) }

    // Import progress tracking
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var importingFilename by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    
    // Export state
    var pendingExportModel by remember { mutableStateOf<ModelEntity?>(null) }
    var pendingDeleteModel by remember { mutableStateOf<ModelEntity?>(null) }
    var editingModel by remember { mutableStateOf<ModelEntity?>(null) }
    var editedFilename by remember { mutableStateOf("") }
    var selectedEditType by remember { mutableStateOf(SDModelSelectionType.CHECKPOINT) }
    var editSupportsTxt2Img by remember { mutableStateOf(true) }
    var editSupportsImg2Img by remember { mutableStateOf(true) }
    var editSdFamily by remember { mutableStateOf<SdModelFamily?>(null) }
    var editSdVariant by remember { mutableStateOf("") }
    var editCompatProfiles by remember { mutableStateOf("") }

    LaunchedEffect(editingModel) {
        editingModel?.let { model ->
            editSdFamily = model.sdFamilyEnum()
            editSdVariant = model.sdVariant.orEmpty()
            editCompatProfiles = initialCompatProfilesForModel(model)
        }
    }

    // Changing a model's role to an IP-Adapter should initialize its side
    // compatibility from the edited filename/repository, not reuse a broad
    // profile set from the previously selected role.
    LaunchedEffect(editingModel, selectedEditType) {
        val model = editingModel ?: return@LaunchedEffect
        if (selectedEditType == SDModelSelectionType.IP_ADAPTER) {
            editCompatProfiles = if (model.type == ModelType.SD_IP_ADAPTER) {
                initialCompatProfilesForModel(model)
            } else {
                initialCompatProfilesForSelection(
                    selectionType = selectedEditType,
                    repoId = model.repoId,
                    filename = editedFilename.ifBlank { model.filename }
                ).orEmpty()
            }
        }
    }
    
    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { treeUri ->
            pendingExportModel?.let { model ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val sourceFile = File(model.path)
                        if (!sourceFile.exists()) {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, resources.getString(R.string.sd_models_error_source_not_found), android.widget.Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                        
                        val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                        val targetFile = documentFile?.createFile("*/*", model.filename)
                        
                        if (targetFile != null) {
                            context.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                                sourceFile.inputStream().use { input ->
                                    input.copyTo(output)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, resources.getString(R.string.sd_models_export_success, model.filename), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, resources.getString(R.string.sd_models_export_failed, e.message), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        pendingExportModel = null
                    }
                }
            }
        }
    }
    
    // Export model function
    val exportModel: (ModelEntity) -> Unit = { model ->
        pendingExportModel = model
        exportPicker.launch(null)
    }

    // Start a bounded, payload-free SAF probe immediately after selection so
    // detected evidence can correct the filename's guessed role before the
    // repository applies role validation. This never launches sdcpp or copies
    // model tensor payloads.
    val inspectSelectedUri: (android.net.Uri, String, SDModelSelectionType) -> Unit =
        { uri, filename, initialType ->
            pendingInspection = null
            pendingInspectionError = null
            isInspectingPending = true
            inspectionSelectionType = initialType
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    SdArtifactInspector().inspect(
                        contentResolver = context.contentResolver,
                        uri = uri,
                        displayName = filename,
                        temporaryDirectory = context.cacheDir
                    )
                }
                withContext(Dispatchers.Main) {
                    if (pendingUri != uri) return@withContext
                    if (!selectedImportType.storedType.isStableDiffusionArtifact()) {
                        isInspectingPending = false
                        pendingInspection = null
                        pendingInspectionError = null
                        inspectionSelectionType = null
                        return@withContext
                    }
                    isInspectingPending = false
                    result.fold(
                        onSuccess = { inspection ->
                            pendingInspection = inspection
                            pendingInspectionError = null
                            if (inspectionSelectionType == selectedImportType) {
                                detectedSelectionType(inspection.detectedRole)?.let { detectedType ->
                                    selectedImportType = detectedType
                                }
                            }
                            inspection.detectedFamily?.let { detectedFamily ->
                                importSdFamily = detectedFamily
                            }
                        },
                        onFailure = { error ->
                            pendingInspection = null
                            pendingInspectionError = error.message
                        }
                    )
                }
            }
        }

    val selectImportType: (SDModelSelectionType) -> Unit = { type ->
        if (selectedImportType != type) {
            selectedImportType = type
            pendingUri?.let { uri ->
                if (type.storedType.isStableDiffusionArtifact()) {
                    inspectSelectedUri(uri, pendingFilename, type)
                } else {
                    pendingInspection = null
                    pendingInspectionError = null
                    isInspectingPending = false
                    inspectionSelectionType = null
                }
            }
        }
    }
    
    // File picker - FAB triggers this directly
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                importError = null
                pendingUri = it
                // Get filename
                val cursor = context.contentResolver.query(it, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            pendingFilename = c.getString(nameIndex)
                            if (pendingFilename.endsWith(".onnx", ignoreCase = true) || pendingFilename.endsWith(".ort", ignoreCase = true)) {
                                android.widget.Toast.makeText(
                                    context,
                                    resources.getString(R.string.sd_models_unsupported_import),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                onOpenOnnxModels()
                                pendingUri = null
                                pendingFilename = ""
                                return@use
                            }
                            val inferredSelectionType = inferSelectionType(
                                repoId = "",
                                filename = pendingFilename.lowercase()
                            )
                            selectedImportType = inferredSelectionType
                            val metadata = defaultSdMetadataForSelection(
                                selectionType = inferredSelectionType,
                                repoId = "local-import",
                                filename = pendingFilename
                            )
                            importSdFamily = metadata.family
                            importSdVariant = metadata.variant.orEmpty()
                            importCompatProfiles = metadata.compatProfiles.orEmpty()
                        }
                    }
                }
                // Show import dialog after file is selected
                showImportDialog = pendingUri != null
                if (pendingUri != null) {
                    if (selectedImportType.storedType.isStableDiffusionArtifact()) {
                        inspectSelectedUri(it, pendingFilename, selectedImportType)
                    } else {
                        // Upscalers, textual inversions, and ADetailer
                        // detectors use their own safe validators. Do not run
                        // the SafeTensors/GGUF probe against .pth/.pt/.onnx.
                        pendingInspection = null
                        pendingInspectionError = null
                        isInspectingPending = false
                        inspectionSelectionType = null
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    // Combined import dialog (type selection + capabilities)
    if (showImportDialog && pendingUri != null) {
        val selectionNeedsInspection = selectedImportType.storedType.isStableDiffusionArtifact()
        val selectionInspectionBlock = pendingInspection
            ?.takeIf { selectionNeedsInspection }
            ?.let { inspection ->
                inspectionBlockForSelection(inspection, selectedImportType, importSdFamily)
            }
        LaunchedEffect(selectedImportType, pendingFilename) {
            val metadata = defaultSdMetadataForSelection(
                selectionType = selectedImportType,
                repoId = "local-import",
                filename = pendingFilename
            )
            if (isImageMainSelection(selectedImportType)) {
                importSdFamily = metadata.family
                importSdVariant = metadata.variant.orEmpty()
            } else {
                importCompatProfiles = metadata.compatProfiles.orEmpty()
            }
            if (selectedImportType == SDModelSelectionType.DIFFUSION || selectedImportType == SDModelSelectionType.CHECKPOINT) {
                val defaultCaps = buildCapabilitiesForSelection(
                    selectionType = selectedImportType,
                    sdFamily = metadata.family
                )?.parseSdCapabilities().orEmpty()
                supportsTxt2Img = defaultCaps.isEmpty() || SD_CAPABILITY_TXT2IMG in defaultCaps
                supportsImg2Img = SD_CAPABILITY_IMG2IMG in defaultCaps
            }
        }
        LaunchedEffect(pendingInspection) {
            pendingInspection?.detectedFamily?.let { detectedFamily ->
                importSdFamily = detectedFamily
            }
        }
        AlertDialog(
            onDismissRequest = { 
                showImportDialog = false
                pendingUri = null
                pendingInspection = null
                pendingInspectionError = null
                isInspectingPending = false
                inspectionSelectionType = null
            },
            title = { Text(stringResource(R.string.sd_models_import_title)) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    item {
                        if (isInspectingPending) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.sd_models_inspection_selection_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        stringResource(R.string.sd_models_inspection_selection_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        } else if (selectionInspectionBlock != null) {
                            Text(
                                stringResource(R.string.sd_models_inspection_selection_blocked),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        } else if (selectionNeedsInspection && pendingInspectionError != null) {
                            Text(
                                stringResource(R.string.sd_models_inspection_selection_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        } else if (selectionNeedsInspection) {
                            pendingInspection?.let { inspection ->
                                SdSelectedArtifactSummary(inspection)
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        // Editable filename
                        Text(stringResource(R.string.sd_models_filename_label), style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = pendingFilename,
                            onValueChange = { pendingFilename = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.sd_models_filename_hint)) }
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Model type selection
                        Text(stringResource(R.string.sd_models_type_label), style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    items(SD_MANAGER_SELECTION_TYPES) { type ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedImportType == type,
                                    onClick = { selectImportType(type) }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedImportType == type,
                                onClick = { selectImportType(type) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(type.labelRes),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    // SD capabilities (only shown for checkpoint type)
                    if (selectedImportType == SDModelSelectionType.CHECKPOINT || selectedImportType == SDModelSelectionType.DIFFUSION) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.sd_models_capabilities_label), style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = supportsTxt2Img,
                                    onCheckedChange = { supportsTxt2Img = it }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.sd_models_cap_txt2img))
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = supportsImg2Img,
                                    onCheckedChange = { supportsImg2Img = it }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.sd_models_cap_img2img))
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        SDMetadataEditor(
                            selectionType = selectedImportType,
                            sdFamily = importSdFamily,
                            onSdFamilyChange = { importSdFamily = it },
                            sdVariant = importSdVariant,
                            onSdVariantChange = { importSdVariant = it },
                            compatProfiles = importCompatProfiles,
                            onCompatProfilesChange = { importCompatProfiles = it }
                        )
                        if (isImageMainSelection(selectedImportType) && importSdFamily == null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.sd_models_manual_configuration_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.models_import_delete_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showImportDialog = false
                    val uri = pendingUri!!
                    val filename = pendingFilename
                    val type = selectedImportType.storedType
                    
                    val caps = buildCapabilitiesForSelection(
                        selectionType = selectedImportType,
                        supportsTxt2Img = supportsTxt2Img,
                        supportsImg2Img = supportsImg2Img,
                        sdFamily = importSdFamily
                    )
                    
                    // Show progress dialog
                    isImporting = true
                    importProgress = 0f
                    importingFilename = filename
                    
                    scope.launch(Dispatchers.IO) {
                        val result = importSDModel(
                            context = context,
                            repository = repository,
                            uri = uri,
                            filename = filename,
                            type = type,
                            capabilities = caps,
                            sdFamily = if (isImageMainSelection(selectedImportType)) importSdFamily?.storedValue else null,
                            sdVariant = importSdVariant.ifBlank { null },
                            sdCompatProfiles = if (requiresCompatProfiles(selectedImportType)) {
                                importCompatProfiles.ifBlank { null }
                            } else {
                                null
                            },
                        ) { progress ->
                            importProgress = progress
                        }
                        withContext(Dispatchers.Main) {
                            isImporting = false
                            result.onFailure { error ->
                                val message = when (error) {
                                    is SdArtifactValidationException ->
                                        resources.getString(R.string.sd_models_import_validation_failed)
                                    is SdAdetailerImportException ->
                                        resources.getString(R.string.imagegen_adetailer_error_incompatible_detector)
                                    else -> resources.getString(
                                        R.string.sd_models_import_failed,
                                        error.message ?: resources.getString(R.string.error_generic)
                                    )
                                }
                                importError = message
                                android.widget.Toast.makeText(
                                    context,
                                    message,
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            // Show the component reminder for a successfully
                            // imported standalone artifact.
                            if (result.isSuccess && (
                                type == ModelType.SD_DIFFUSION ||
                                    type == ModelType.SD_CLIP_L ||
                                    type == ModelType.SD_CLIP_G ||
                                    type == ModelType.SD_T5XXL ||
                                    type == ModelType.SD_VAE
                                )) {
                                settingsRepo.setShowFluxReminderPending(true)
                            }
                        }
                    }
                    
                    // Reset
                    pendingUri = null
                    pendingFilename = ""
                    pendingInspection = null
                    pendingInspectionError = null
                    isInspectingPending = false
                    inspectionSelectionType = null
                    selectedImportType = SDModelSelectionType.CHECKPOINT
                    supportsTxt2Img = true
                    supportsImg2Img = true
                    importSdFamily = null
                    importSdVariant = ""
                    importCompatProfiles = ""
                }, enabled = !isInspectingPending && selectionInspectionBlock == null) {
                    Text(stringResource(R.string.action_import))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    pendingUri = null
                    pendingFilename = ""
                    pendingInspection = null
                    pendingInspectionError = null
                    isInspectingPending = false
                    inspectionSelectionType = null
                    selectedImportType = SDModelSelectionType.CHECKPOINT
                    supportsTxt2Img = true
                    supportsImg2Img = true
                    importSdFamily = null
                    importSdVariant = ""
                    importCompatProfiles = ""
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Import Progress Dialog
    if (isImporting) {
        AlertDialog(
            onDismissRequest = { /* Can't dismiss during import */ },
            title = { Text(stringResource(R.string.sd_models_importing_title)) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        importingFilename,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { importProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${(importProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.sd_models_please_wait),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { /* No confirm button */ }
        )
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(stringResource(R.string.sd_models_import_error_title)) },
            text = {
                Text(
                    message,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { importError = null }) {
                    Text(stringResource(R.string.action_dismiss))
                }
            }
        )
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        val hasAnyModels = checkpoints.isNotEmpty() || upscalers.isNotEmpty() ||
            diffusionModels.isNotEmpty() || clipLModels.isNotEmpty() || clipGModels.isNotEmpty() ||
            t5xxlModels.isNotEmpty() || taeModels.isNotEmpty() || vaeModels.isNotEmpty() ||
            controlNetModels.isNotEmpty() || loraModels.isNotEmpty() || photoMakerModels.isNotEmpty() ||
            clipVisionModels.isNotEmpty() || ipAdapterModels.isNotEmpty() ||
            adetailerModels.isNotEmpty()
        
        if (!hasAnyModels) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Create,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.sd_models_no_sd_models),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.sd_models_import_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Leave a safe tail below the import FAB so the final delete
                // action remains reachable on short phones.
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "sd-artifact-inspection-control") {
                    SdInspectionControl(
                        totalModels = allInstalledModels.size,
                        inspectedModels = inspectedCount,
                        failedModels = inspectionFailureCount,
                        isInspecting = isInspectingAll,
                        showSummary = inspectionSummaryVisible,
                        onInspectAll = inspectAll
                    )
                }

                // SD/SDXL Checkpoints
                if (checkpoints.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.sd_models_checkpoints_label, checkpoints.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(checkpoints) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                            }
                        )
                    }
                }
                
                // Standalone Diffusion Models
                if (diffusionModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_standalone_diffusion_label, diffusionModels.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(diffusionModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                            }
                        )
                    }
                }
                
                // CLIP-L Encoders
                if (clipLModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_clip_l_label, clipLModels.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(clipLModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                            }
                        )
                    }
                }
                
                // T5-XXL Encoders
                if (t5xxlModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_t5xxl_label, t5xxlModels.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(t5xxlModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                            }
                        )
                    }
                }

                if (clipGModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_clip_g_label, clipGModels.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(clipGModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                                editSdFamily = model.sdFamilyEnum()
                                editSdVariant = model.sdVariant.orEmpty()
                                editCompatProfiles = initialCompatProfilesForModel(model)
                            }
                        )
                    }
                }

                if (taeModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_tae_label, taeModels.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(taeModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                                editSdFamily = model.sdFamilyEnum()
                                editSdVariant = model.sdVariant.orEmpty()
                                editCompatProfiles = model.sdCompatProfiles.orEmpty()
                            }
                        )
                    }
                }
                
                // VAE Models
                if (vaeModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_vae_label, vaeModels.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(vaeModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                            }
                        )
                    }
                }
                
                // ControlNet Models
                if (controlNetModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_controlnet_label, controlNetModels.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(controlNetModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                            }
                        )
                    }
                }
                
                // LoRA Models
                if (loraModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_lora_label, loraModels.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(loraModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                            }
                        )
                    }
                }

                if (photoMakerModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_photomaker_label, photoMakerModels.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(photoMakerModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                                editSdFamily = model.sdFamilyEnum()
                                editSdVariant = model.sdVariant.orEmpty()
                                editCompatProfiles = model.sdCompatProfiles.orEmpty()
                            }
                        )
                    }
                }

                if (clipVisionModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_clip_vision_label, clipVisionModels.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(clipVisionModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                                editSdFamily = model.sdFamilyEnum()
                                editSdVariant = model.sdVariant.orEmpty()
                                editCompatProfiles = model.sdCompatProfiles.orEmpty()
                            }
                        )
                    }
                }

                if (ipAdapterModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_ip_adapter_label, ipAdapterModels.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(ipAdapterModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                                editSdFamily = model.sdFamilyEnum()
                                editSdVariant = model.sdVariant.orEmpty()
                                editCompatProfiles = initialCompatProfilesForModel(model)
                            }
                        )
                    }
                }

                if (adetailerModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_adetailer_label, adetailerModels.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(adetailerModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                                editCompatProfiles = model.sdCompatProfiles.orEmpty()
                            }
                        )
                    }
                }
                
                // Upscalers
                if (upscalers.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sd_models_upscalers_label, upscalers.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(upscalers) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = installedCapabilitiesFor(model),
                            onDelete = { pendingDeleteModel = model },
                            onExport = { exportModel(model) },
                            onInspect = { inspectOne(model) },
                            inspectionState = inspectionStates[model.path]
                                ?: SdInspectionRowState(),
                            onEdit = {
                                editingModel = model
                                editedFilename = model.filename
                                selectedEditType = selectionTypeForModel(model)
                                editSupportsTxt2Img = checkpointSupportsTxt2Img(model)
                                editSupportsImg2Img = checkpointSupportsImg2Img(model)
                            }
                        )
                    }
                }

            }
        }

        if (editingModel != null) {
            AlertDialog(
                onDismissRequest = { editingModel = null },
                title = { Text(stringResource(R.string.sd_models_edit_title)) },
                text = {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        item {
                            Text(stringResource(R.string.sd_models_filename_label), style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = editedFilename,
                                onValueChange = { editedFilename = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.sd_models_filename_hint)) }
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.sd_models_type_label), style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        items(SD_MANAGER_SELECTION_TYPES) { type ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selectedEditType == type,
                                        onClick = { selectedEditType = type }
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedEditType == type,
                                    onClick = { selectedEditType = type }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(type.labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (selectedEditType == SDModelSelectionType.CHECKPOINT || selectedEditType == SDModelSelectionType.DIFFUSION) {
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(stringResource(R.string.sd_models_capabilities_label), style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = editSupportsTxt2Img,
                                        onCheckedChange = { editSupportsTxt2Img = it }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.sd_models_cap_txt2img))
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = editSupportsImg2Img,
                                        onCheckedChange = { editSupportsImg2Img = it }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.sd_models_cap_img2img))
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            SDMetadataEditor(
                                selectionType = selectedEditType,
                                sdFamily = editSdFamily,
                                onSdFamilyChange = { editSdFamily = it },
                                sdVariant = editSdVariant,
                                onSdVariantChange = { editSdVariant = it },
                                compatProfiles = editCompatProfiles,
                                onCompatProfilesChange = { editCompatProfiles = it }
                            )
                            if (isImageMainSelection(selectedEditType) &&
                                editingModel?.sdInspectionConfidence != SdInspectionConfidence.HIGH.storedValue
                            ) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.sd_models_manual_configuration_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val model = editingModel ?: return@TextButton
                            val updatedType = selectedEditType.storedType
                            val updatedCapabilities = buildCapabilitiesForSelection(
                                selectionType = selectedEditType,
                                supportsTxt2Img = editSupportsTxt2Img,
                                supportsImg2Img = editSupportsImg2Img,
                                sdFamily = editSdFamily
                            )

                            scope.launch {
                                val result = repository.updateModel(
                                    original = model,
                                    newFilename = editedFilename,
                                    newType = updatedType,
                                    sdCapabilities = updatedCapabilities,
                                    sdFamily = if (isImageMainSelection(selectedEditType)) editSdFamily?.storedValue else null,
                                    sdVariant = editSdVariant.ifBlank { null },
                                    sdCompatProfiles = if (requiresCompatProfiles(selectedEditType)) {
                                        editCompatProfiles.ifBlank { null }
                                    } else {
                                        null
                                    },
                                )

                                result.onSuccess { updated ->
                                    android.widget.Toast.makeText(
                                        context,
                                        resources.getString(R.string.sd_models_update_success, updated.filename),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()

                                    if (
                                        updated.type == ModelType.SD_DIFFUSION ||
                                        updated.type == ModelType.SD_CLIP_L ||
                                        updated.type == ModelType.SD_CLIP_G ||
                                        updated.type == ModelType.SD_T5XXL ||
                                        updated.type == ModelType.SD_VAE
                                    ) {
                                        settingsRepo.setShowFluxReminderPending(true)
                                    }
                                }.onFailure { error ->
                                    android.widget.Toast.makeText(
                                        context,
                                        resources.getString(
                                            R.string.sd_models_update_failed,
                                            error.message ?: resources.getString(R.string.error_generic)
                                        ),
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }

                            editingModel = null
                        }
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingModel = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }

        pendingDeleteModel?.let { model ->
            AlertDialog(
                onDismissRequest = { pendingDeleteModel = null },
                title = { Text(stringResource(R.string.models_delete)) },
                text = { Text(stringResource(R.string.models_delete_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDeleteModel = null
                            onDelete(model)
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteModel = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
        
        // FAB for import - launches file picker directly
        FloatingActionButton(
            onClick = { filePicker.launch(arrayOf("*/*")) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sd_models_import_title))
        }
    }
}

// Helper function to import SD model with progress
private suspend fun importSDModel(
    context: android.content.Context,
    repository: ModelRepository,
    uri: android.net.Uri,
    filename: String,
    type: ModelType,
    capabilities: String?,
    sdFamily: String? = null,
    sdVariant: String? = null,
    sdCompatProfiles: String? = null,
    onProgress: (Float) -> Unit = {}
) : Result<ModelEntity> {
    var tempFile: File? = null
    var targetFile: File? = null
    var previousFile: File? = null
    var replacementStarted = false
    var databaseCommitted = false
    return try {
        val requestedFilename = filename.ifBlank { "imported_sd_model.safetensors" }
        val targetFilename = ModelLibraryManager.canonicalFilename(requestedFilename)
        val fileSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            it.length
        } ?: 0L
        val runtimeDir = repository.getModelDir(type).apply { mkdirs() }
        targetFile = File(runtimeDir, targetFilename)
        tempFile = File(runtimeDir, "$targetFilename.importing")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile!!.outputStream().use { output ->
                if (fileSize > 0) {
                    val buffer = ByteArray(8192)
                    var bytesCopied = 0L
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        onProgress((bytesCopied.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f))
                        bytes = input.read(buffer)
                    }
                } else {
                    input.copyTo(output)
                }
            }
        } ?: error("Unable to open selected SD model")
        // Inspect the completed temporary file before the final rename.  This
        // is the trust boundary for local imports: malformed payloads and
        // high-confidence role/family contradictions never become model rows.
        onProgress(0.98f)
        val inspection: SdArtifactInspection? = when (type) {
            ModelType.SD_ADETAILER -> {
                if (!smokeCheckSdADetailerDetector(tempFile!!)) {
                    throw SdAdetailerImportException()
                }
                null
            }
            // These established SD assets are not SafeTensors/GGUF pipeline
            // artifacts. Keep .pth/.pt opaque and leave validation to their
            // consuming runtime instead of attempting to parse or unpickle.
            ModelType.SD_UPSCALER,
            ModelType.SD_TEXTUAL_INVERSION -> null
            else -> repository.inspectSdArtifact(
                file = tempFile!!,
                configuredType = type,
                configuredFamily = sdFamily,
                force = true
            )
        }
        val finalTargetFile = targetFile ?: error("Unable to prepare import destination")
        val finalPath = finalTargetFile.absolutePath
        val existing = AppDatabase.getDatabase(context).modelDao().getModelByFilename(targetFilename)
        val modelEntity = ModelEntity(
            filename = targetFilename,
            path = finalPath,
            sizeBytes = tempFile!!.length(),
            type = type,
            repoId = "custom-import/$targetFilename",
            isDownloaded = false,
            sdCapabilities = capabilities,
            sdFamily = sdFamily,
            sdVariant = sdVariant,
            sdCompatProfiles = sdCompatProfiles
        ).let { entity ->
            inspection?.let { entity.withSdArtifactInspection(it) } ?: entity
        }

        // Keep an existing file recoverable if the database write fails.  The
        // temporary source remains in place until the replacement is complete.
        if (finalTargetFile.exists()) {
            previousFile = File(runtimeDir, ".$targetFilename.previous-${System.currentTimeMillis()}")
            check(finalTargetFile.renameTo(previousFile!!)) { "Unable to stage the existing model file" }
        }
        replacementStarted = true
        replaceImportedSdFile(tempFile!!, finalTargetFile)
        repository.insertModel(modelEntity)
        databaseCommitted = true
        if (existing != null && existing.path != finalPath) {
            runCatching { repository.deleteModelArtifacts(existing) }
                .onFailure { error ->
                    com.example.llamadroid.util.DebugLog.log(
                        "[SD-IMPORT] Existing artifact cleanup deferred: ${error.message}"
                    )
                }
        }
        previousFile?.delete()
        onProgress(1f)
        com.example.llamadroid.util.DebugLog.log("[SD-IMPORT] Imported: $targetFilename as ${type.name}")
        Result.success(modelEntity)
    } catch (e: Exception) {
        // Do not delete the temporary artifact.  A failed inspection/import is
        // recoverable and can be retried without selecting the source again.
        if (replacementStarted && !databaseCommitted) {
            previousFile?.takeIf { it.exists() }?.let { backup ->
                targetFile?.let { target ->
                    if (target.exists()) {
                        val failedCopy = File(
                            target.parentFile,
                            "${target.name}.failed-${System.currentTimeMillis()}"
                        )
                        target.renameTo(failedCopy)
                    }
                    backup.renameTo(target)
                }
            }
        }
        com.example.llamadroid.util.DebugLog.log("[SD-IMPORT] Failed: ${e.message}")
        Result.failure(e)
    }
}

private fun replaceImportedSdFile(tempFile: File, targetFile: File) {
    if (targetFile.exists()) {
        targetFile.delete()
    }
    if (!tempFile.renameTo(targetFile)) {
        tempFile.inputStream().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempFile.delete()
    }
}

private data class SdMetadataDraft(
    val family: SdModelFamily? = null,
    val variant: String? = null,
    val compatProfiles: String? = null
)

private fun isImageMainSelection(selectionType: SDModelSelectionType): Boolean =
    selectionType == SDModelSelectionType.CHECKPOINT || selectionType == SDModelSelectionType.DIFFUSION

private fun requiresCompatProfiles(selectionType: SDModelSelectionType): Boolean = when (selectionType) {
    SDModelSelectionType.CLIP_L,
    SDModelSelectionType.CLIP_G,
    SDModelSelectionType.T5XXL,
    SDModelSelectionType.TAE,
    SDModelSelectionType.VAE,
    SDModelSelectionType.CONTROLNET,
    SDModelSelectionType.LORA,
    SDModelSelectionType.PHOTOMAKER,
    SDModelSelectionType.CLIP_VISION,
    SDModelSelectionType.IP_ADAPTER,
    SDModelSelectionType.ADETAILER,
    SDModelSelectionType.IMAGE_LLM,
    SDModelSelectionType.IMAGE_LLM_VISION,
    SDModelSelectionType.UPSCALER -> true
    else -> false
}

private fun defaultSdMetadataForSelection(
    selectionType: SDModelSelectionType,
    repoId: String,
    filename: String
): SdMetadataDraft {
    val normalizedFilename = filename.trim()
    return when {
        isImageMainSelection(selectionType) -> {
            val inferred = inferSdFamily(selectionType.storedType, repoId, normalizedFilename)
            SdMetadataDraft(
                family = inferred.first,
                variant = inferred.second
            )
        }
        requiresCompatProfiles(selectionType) -> SdMetadataDraft(
            compatProfiles = initialCompatProfilesForSelection(selectionType, repoId, filename)
        )
        else -> SdMetadataDraft()
    }
}

/**
 * Seed side-component compatibility from the artifact hint when one is
 * available. IP-Adapter files are commonly named for SDXL or SD 1.x; using
 * the old broad `checkpoint:sd1,checkpoint:sdxl` default for an inferred SDXL
 * adapter makes the row look valid for the wrong base model. Keep the broad
 * defaults only when no family can be inferred.
 */
private fun initialCompatProfilesForSelection(
    selectionType: SDModelSelectionType,
    repoId: String,
    filename: String
): String? {
    if (selectionType != SDModelSelectionType.IP_ADAPTER) {
        return buildSdCompatProfiles(
            *defaultCompatProfilesFor(selectionType.storedType).toTypedArray()
        )
    }
    val inferred = inferSdFamily(selectionType.storedType, repoId, filename)
    val family = inferred.first
    if (family == null) {
        return buildSdCompatProfiles(
            *defaultCompatProfilesFor(selectionType.storedType).toTypedArray()
        )
    }
    val variant = inferred.second?.trim()?.ifBlank { null }
    return buildSdCompatProfiles(
        variant?.let { "${family.storedValue}:$it" } ?: family.storedValue
    )
}

/**
 * Preserve explicit component choices while collapsing legacy broad defaults
 * to a known inferred profile. A genuinely conflicting manual choice remains
 * visible so the row can be marked invalid instead of being silently changed.
 */
private fun initialCompatProfilesForModel(model: ModelEntity): String {
    if (model.type != ModelType.SD_IP_ADAPTER) return model.sdCompatProfiles.orEmpty()
    val inferred = inferSdFamily(model.type, model.repoId, model.filename)
    val family = inferred.first ?: return model.sdCompatProfiles.orEmpty()
    val variant = inferred.second?.trim()?.ifBlank { null }
    val inferredToken = variant?.let { "${family.storedValue}:$it" } ?: family.storedValue
    val explicit = model.sdCompatProfiles.parseSdCompatProfiles()
    if (explicit.isEmpty()) return inferredToken

    val legacyDefaults = defaultCompatProfilesFor(model.type) + family.storedValue
    return when {
        explicit.all { it in legacyDefaults } -> inferredToken
        inferredToken in explicit -> inferredToken
        else -> model.sdCompatProfiles.orEmpty()
    }
}

private fun buildCapabilitiesForSelection(
    selectionType: SDModelSelectionType,
    supportsTxt2Img: Boolean = true,
    supportsImg2Img: Boolean = true,
    sdFamily: SdModelFamily? = null
): String? = when (selectionType) {
    SDModelSelectionType.CHECKPOINT -> buildSdCapabilities(
        if (supportsTxt2Img) SD_CAPABILITY_TXT2IMG else null,
        if (supportsImg2Img) SD_CAPABILITY_IMG2IMG else null
    )
    SDModelSelectionType.DIFFUSION -> buildSdCapabilities(
        if (supportsTxt2Img) SD_CAPABILITY_TXT2IMG else null,
        if (supportsImg2Img) SD_CAPABILITY_IMG2IMG else null
    ) ?: defaultCapabilitiesForFamily(sdFamily, selectionType.storedType)
    SDModelSelectionType.VIDEO_GEN -> buildSdCapabilities(SD_CAPABILITY_VID_GEN)
    else -> null
}

private fun selectionTypeForModel(model: ModelEntity): SDModelSelectionType = when (model.type) {
    ModelType.SD_CHECKPOINT -> SDModelSelectionType.CHECKPOINT
    ModelType.SD_DIFFUSION -> {
        if (model.hasSdCapability(SD_CAPABILITY_VID_GEN)) {
            SDModelSelectionType.VIDEO_GEN
        } else {
            SDModelSelectionType.DIFFUSION
        }
    }
    ModelType.SD_CLIP_L -> SDModelSelectionType.CLIP_L
    ModelType.SD_CLIP_G -> SDModelSelectionType.CLIP_G
    ModelType.SD_T5XXL -> SDModelSelectionType.T5XXL
    ModelType.SD_TAE -> SDModelSelectionType.TAE
    ModelType.SD_VAE -> SDModelSelectionType.VAE
    ModelType.SD_CONTROLNET -> SDModelSelectionType.CONTROLNET
    ModelType.SD_LORA -> SDModelSelectionType.LORA
    ModelType.SD_TEXTUAL_INVERSION ->
        SDModelSelectionType.TEXTUAL_INVERSION
    ModelType.SD_PHOTOMAKER -> SDModelSelectionType.PHOTOMAKER
    ModelType.SD_CLIP_VISION -> SDModelSelectionType.CLIP_VISION
    ModelType.SD_IP_ADAPTER -> SDModelSelectionType.IP_ADAPTER
    ModelType.SD_ADETAILER -> SDModelSelectionType.ADETAILER
    ModelType.LLM -> SDModelSelectionType.IMAGE_LLM
    ModelType.VISION_PROJECTOR -> SDModelSelectionType.IMAGE_LLM_VISION
    ModelType.SD_UPSCALER -> SDModelSelectionType.UPSCALER
    else -> SDModelSelectionType.CHECKPOINT
}

private fun checkpointSupportsTxt2Img(model: ModelEntity): Boolean =
    (model.type == ModelType.SD_CHECKPOINT || model.type == ModelType.SD_DIFFUSION) &&
        (model.sdCapabilities.isNullOrBlank() || model.hasSdCapability(SD_CAPABILITY_TXT2IMG))

private fun checkpointSupportsImg2Img(model: ModelEntity): Boolean =
    (model.type == ModelType.SD_CHECKPOINT || model.type == ModelType.SD_DIFFUSION) &&
        (model.sdCapabilities.isNullOrBlank() || model.hasSdCapability(SD_CAPABILITY_IMG2IMG))

private fun inferSelectionType(repoId: String, filename: String): SDModelSelectionType = when {
    filename.contains("yolov8", ignoreCase = true) ||
        filename.contains("adetailer", ignoreCase = true) ||
        repoId.contains("adetailer", ignoreCase = true) ||
        repoId.contains("yolo", ignoreCase = true) ->
        SDModelSelectionType.ADETAILER
    isVideoGenHint(repoId) || isVideoGenHint(filename) ->
        SDModelSelectionType.VIDEO_GEN
    filename.contains("mmproj") || repoId.contains("mmproj") ||
        filename.contains("vision", ignoreCase = true) && repoId.contains("qwen", ignoreCase = true) ->
        SDModelSelectionType.IMAGE_LLM_VISION
    filename.contains("clip_vision", ignoreCase = true) ||
        filename.contains("clip-vision", ignoreCase = true) ||
        filename.contains("image_encoder", ignoreCase = true) ||
        filename.contains("image-encoder", ignoreCase = true) ||
        filename.contains("clip-vit", ignoreCase = true) ||
        repoId.contains("clip_vision", ignoreCase = true) ||
        repoId.contains("clip-vision", ignoreCase = true) ->
        SDModelSelectionType.CLIP_VISION
    filename.contains("ip-adapter", ignoreCase = true) ||
        filename.contains("ip_adapter", ignoreCase = true) ||
        filename.contains("ipadapter", ignoreCase = true) ||
        repoId.contains("ip-adapter", ignoreCase = true) ||
        repoId.contains("ip_adapter", ignoreCase = true) ->
        SDModelSelectionType.IP_ADAPTER
    filename.contains("upscale") || filename.contains("esrgan") ||
        repoId.contains("esrgan") || repoId.contains("upscale") ->
        SDModelSelectionType.UPSCALER
    filename.contains("photomaker") || repoId.contains("photomaker") ->
        SDModelSelectionType.PHOTOMAKER
    filename.contains("taesd") || filename.contains("tae") || repoId.contains("taesd") ->
        SDModelSelectionType.TAE
    filename.contains("clip_g") || filename.contains("clip-g") || repoId.contains("clip-g") ->
        SDModelSelectionType.CLIP_G
    // Start the bounded inspection with the generic standalone role for
    // architecture names that are not FLUX. A full SD3 checkpoint is
    // corrected back to CHECKPOINT when its header proves that layout.
    filename.contains("sd3") || filename.contains("stable-diffusion-3") ||
        repoId.contains("sd3") || repoId.contains("stable-diffusion-3") ||
        filename.contains("chroma") || repoId.contains("chroma") ||
        filename.contains("qwen-image") || repoId.contains("qwen-image") ||
        filename.contains("z-image") || repoId.contains("z-image") ||
        filename.contains("ovis") || repoId.contains("ovis") ||
        filename.contains("anima") || repoId.contains("anima") ->
        SDModelSelectionType.DIFFUSION
    (repoId.contains("flux") && (filename.endsWith(".gguf") || filename.contains("diffusion"))) ||
        repoId.contains("qwen-image") || repoId.contains("qwen image") ||
        repoId.contains("chroma") || repoId.contains("z-image") || repoId.contains("ovis") ||
        repoId.contains("anima") ||
        (filename.contains("flux") && !filename.contains("vae") && !filename.contains("clip") && !filename.contains("t5")) ->
        SDModelSelectionType.DIFFUSION
    repoId.contains("qwen2.5-vl") || filename.contains("qwen2.5-vl") ||
        repoId.contains("image-llm") || filename.contains("image-llm") ->
        SDModelSelectionType.IMAGE_LLM
    filename.contains("t5") || repoId.contains("t5-v1") || repoId.contains("t5xxl") ->
        SDModelSelectionType.T5XXL
    filename.contains("clip") || repoId.contains("clip-vit") ||
        (repoId.contains("clip") && filename.endsWith(".gguf")) ->
        SDModelSelectionType.CLIP_L
    filename.contains("vae") || repoId.contains("vae") ->
        SDModelSelectionType.VAE
    filename.contains("controlnet") || repoId.contains("controlnet") ->
        SDModelSelectionType.CONTROLNET
    filename.contains("lora") || repoId.contains("lora") ->
        SDModelSelectionType.LORA
    else -> SDModelSelectionType.CHECKPOINT
}

private fun isVideoGenHint(value: String): Boolean {
    val hints = listOf(
        "t2v",
        "i2v",
        "txt2vid",
        "img2vid",
        "video",
        "vidgen",
        "self-forcing",
        "self_forcing"
    )
    return hints.any { hint -> value.contains(hint) }
}

private fun installedCapabilitiesFor(model: ModelEntity): List<SDCapability> = when (model.type) {
    ModelType.SD_CHECKPOINT -> {
        val capabilities = mutableListOf<SDCapability>()
        val showTxt2Img = model.sdCapabilities.isNullOrBlank() || model.hasSdCapability(SD_CAPABILITY_TXT2IMG)
        val showImg2Img = model.sdCapabilities.isNullOrBlank() || model.hasSdCapability(SD_CAPABILITY_IMG2IMG)
        if (showTxt2Img) capabilities += SDCapability.TXT2IMG
        if (showImg2Img) capabilities += SDCapability.IMG2IMG
        capabilities
    }
    ModelType.SD_DIFFUSION -> buildList {
        if (model.hasSdCapability(SD_CAPABILITY_VID_GEN)) {
            add(SDCapability.VID_GEN)
        } else {
            val fallback = defaultCapabilitiesForFamily(model.sdFamilyEnum(), model.type).parseSdCapabilities()
            val capabilities = if (model.sdCapabilities.isNullOrBlank()) fallback else model.sdCapabilities.parseSdCapabilities()
            if (SD_CAPABILITY_TXT2IMG in capabilities) add(SDCapability.TXT2IMG)
            if (SD_CAPABILITY_IMG2IMG in capabilities) add(SDCapability.IMG2IMG)
        }
    }
    ModelType.SD_UPSCALER -> listOf(SDCapability.UPSCALE)
    else -> emptyList()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SDMetadataEditor(
    selectionType: SDModelSelectionType,
    sdFamily: SdModelFamily?,
    onSdFamilyChange: (SdModelFamily?) -> Unit,
    sdVariant: String,
    onSdVariantChange: (String) -> Unit,
    compatProfiles: String,
    onCompatProfilesChange: (String) -> Unit
) {
    if (isImageMainSelection(selectionType)) {
        Text(stringResource(R.string.sd_models_family_label), style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        var familyExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = familyExpanded,
            onExpandedChange = { familyExpanded = !familyExpanded }
        ) {
            OutlinedTextField(
                value = sdFamily?.let { stringResource(sdFamilyLabelRes(it)) }.orEmpty(),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                label = { Text(stringResource(R.string.sd_models_family_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = familyExpanded) }
            )
            ExposedDropdownMenu(
                expanded = familyExpanded,
                onDismissRequest = { familyExpanded = false }
            ) {
                selectableFamiliesFor(selectionType).forEach { family ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(sdFamilyLabelRes(family)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {
                            onSdFamilyChange(family)
                            familyExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = sdVariant,
            onValueChange = onSdVariantChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.sd_models_variant_label)) },
            placeholder = { Text(stringResource(R.string.sd_models_variant_hint)) },
            singleLine = true
        )
    } else if (requiresCompatProfiles(selectionType)) {
        Text(stringResource(R.string.sd_models_compat_profiles_label), style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.sd_models_compat_profiles_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        val selectedProfiles = compatProfiles.parseSdCompatProfiles()
        val suggestedProfiles = remember(selectionType) { compatProfileSuggestionsFor(selectionType) }

        if (selectedProfiles.isEmpty()) {
            Text(
                stringResource(R.string.sd_models_compat_profiles_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                selectedProfiles.toList().sorted().forEach { profile ->
                    InputChip(
                        selected = true,
                        onClick = {
                            onCompatProfilesChange(
                                buildSdCompatProfiles(
                                    *(selectedProfiles - profile).toList().sorted().toTypedArray()
                                ).orEmpty()
                            )
                        },
                        label = {
                            Text(
                                profile,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_remove),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestedProfiles.forEach { profile ->
                val selected = profile in selectedProfiles
                FilterChip(
                    selected = selected,
                    onClick = {
                        val nextProfiles = if (selected) {
                            selectedProfiles - profile
                        } else {
                            selectedProfiles + profile
                        }
                        onCompatProfilesChange(
                            buildSdCompatProfiles(*nextProfiles.toList().sorted().toTypedArray()).orEmpty()
                        )
                    },
                    label = {
                        Text(
                            profile,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

private fun selectableFamiliesFor(selectionType: SDModelSelectionType): List<SdModelFamily> = when (selectionType) {
    // Architecture and packaging are independent: a family may be distributed
    // as a full checkpoint or a standalone diffusion artifact. Keep both main
    // roles derived from the complete enum and let inspection/pipeline
    // resolution decide whether the selected file's actual layout is valid.
    SDModelSelectionType.CHECKPOINT,
    SDModelSelectionType.DIFFUSION -> SdModelFamily.entries
    else -> emptyList()
}

@StringRes
private fun sdFamilyLabelRes(family: SdModelFamily): Int = when (family) {
    SdModelFamily.CHECKPOINT -> R.string.sd_models_family_checkpoint
    SdModelFamily.SD3 -> R.string.sd_models_family_sd3
    SdModelFamily.FLUX_1 -> R.string.sd_models_family_flux1
    SdModelFamily.FLUX_KONTEXT -> R.string.sd_models_family_flux_kontext
    SdModelFamily.FLUX_2 -> R.string.sd_models_family_flux2
    SdModelFamily.CHROMA -> R.string.sd_models_family_chroma
    SdModelFamily.CHROMA_RADIANCE -> R.string.sd_models_family_chroma_radiance
    SdModelFamily.QWEN_IMAGE -> R.string.sd_models_family_qwen_image
    SdModelFamily.QWEN_IMAGE_EDIT -> R.string.sd_models_family_qwen_image_edit
    SdModelFamily.Z_IMAGE -> R.string.sd_models_family_z_image
    SdModelFamily.OVIS_IMAGE -> R.string.sd_models_family_ovis_image
    SdModelFamily.ANIMA -> R.string.sd_models_family_anima
}

private fun compatProfileSuggestionsFor(selectionType: SDModelSelectionType): List<String> {
    if (selectionType == SDModelSelectionType.UPSCALER) {
        return listOf(SdComponentRole.UPSCALER.compatToken)
    }
    return buildList {
        addAll(
            listOf(
                SdModelFamily.CHECKPOINT.storedValue,
                "${SdModelFamily.CHECKPOINT.storedValue}:sd1",
                "${SdModelFamily.CHECKPOINT.storedValue}:sd2",
                "${SdModelFamily.CHECKPOINT.storedValue}:sdxl",
                SdModelFamily.SD3.storedValue,
                SdModelFamily.FLUX_1.storedValue,
                "${SdModelFamily.FLUX_1.storedValue}:schnell",
                "${SdModelFamily.FLUX_1.storedValue}:dev",
                SdModelFamily.FLUX_KONTEXT.storedValue,
                "${SdModelFamily.FLUX_KONTEXT.storedValue}:dev",
                SdModelFamily.FLUX_2.storedValue,
                "${SdModelFamily.FLUX_2.storedValue}:dev",
                "${SdModelFamily.FLUX_2.storedValue}:base",
                "${SdModelFamily.FLUX_2.storedValue}:klein_4b",
                "${SdModelFamily.FLUX_2.storedValue}:klein_base_4b",
                "${SdModelFamily.FLUX_2.storedValue}:klein_9b",
                "${SdModelFamily.FLUX_2.storedValue}:klein_base_9b",
                SdModelFamily.CHROMA.storedValue,
                SdModelFamily.CHROMA_RADIANCE.storedValue,
                SdModelFamily.QWEN_IMAGE.storedValue,
                SdModelFamily.QWEN_IMAGE_EDIT.storedValue,
                "${SdModelFamily.QWEN_IMAGE_EDIT.storedValue}:2509",
                "${SdModelFamily.QWEN_IMAGE_EDIT.storedValue}:2511",
                SdModelFamily.Z_IMAGE.storedValue,
                "${SdModelFamily.Z_IMAGE.storedValue}:turbo",
                "${SdModelFamily.Z_IMAGE.storedValue}:base",
                SdModelFamily.OVIS_IMAGE.storedValue,
                SdModelFamily.ANIMA.storedValue
            )
        )
        addAll(defaultCompatProfilesFor(selectionType.storedType))
    }.distinct().sorted()
}

@Composable
private fun SdInspectionControl(
    totalModels: Int,
    inspectedModels: Int,
    failedModels: Int,
    isInspecting: Boolean,
    showSummary: Boolean,
    onInspectAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                stringResource(R.string.sd_models_detection_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.sd_models_detection_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onInspectAll,
                enabled = totalModels > 0 && !isInspecting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isInspecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(
                            R.string.sd_models_detection_progress,
                            inspectedModels,
                            totalModels
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_models_detect_all))
                }
            }
            if (isInspecting) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = {
                        if (totalModels == 0) 0f else inspectedModels.toFloat() / totalModels
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (showSummary) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.sd_models_detection_summary,
                        inspectedModels,
                        failedModels
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failedModels == 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun hasStaleSdCompatibilityMetadata(model: ModelEntity): Boolean {
    val inferred = inferSdFamily(model.type, model.repoId, model.filename)
    val inferredFamily = inferred.first ?: return false
    val configured = model.sdCompatProfiles.parseSdCompatProfiles()
    if (configured.isEmpty()) return false
    return !model.matchesSdFamily(inferredFamily, inferred.second)
}

@Composable
private fun SdSelectedArtifactSummary(inspection: SdArtifactInspection) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(8.dp)
            )
            .padding(8.dp)
    ) {
        Text(
            stringResource(R.string.sd_models_detected_format, inspection.format.storedValue),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            stringResource(
                R.string.sd_models_detected_family,
                inspection.detectedFamily?.let { stringResource(sdFamilyLabelRes(it)) }
                    ?: stringResource(R.string.sd_models_unknown_value)
            ),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            stringResource(
                R.string.sd_models_detected_role,
                inspection.detectedRole?.storedValue
                    ?: stringResource(R.string.sd_models_unknown_value)
            ),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            stringResource(
                R.string.sd_models_detected_layout,
                inspection.artifactLayout.storedValue
            ),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (inspection.warnings.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.sd_models_inspection_warnings,
                    inspection.warnings.take(2).joinToString(" • ")
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun detectedSelectionType(role: SdArtifactRole?): SDModelSelectionType? = when (role) {
    SdArtifactRole.FULL_MODEL,
    SdArtifactRole.MAIN_MODEL -> SDModelSelectionType.CHECKPOINT
    SdArtifactRole.STANDALONE_DIFFUSION -> SDModelSelectionType.DIFFUSION
    SdArtifactRole.VAE -> SDModelSelectionType.VAE
    SdArtifactRole.TAE -> SDModelSelectionType.TAE
    SdArtifactRole.CLIP_L -> SDModelSelectionType.CLIP_L
    SdArtifactRole.CLIP_G -> SDModelSelectionType.CLIP_G
    SdArtifactRole.T5XXL -> SDModelSelectionType.T5XXL
    // Preserve the dedicated image-LLM/projector route when the artifact is
    // genuinely classified as one of those roles; encoder roles above remain
    // Stable Diffusion component selections.
    SdArtifactRole.LLM -> SDModelSelectionType.IMAGE_LLM
    SdArtifactRole.LLM_VISION -> SDModelSelectionType.IMAGE_LLM_VISION
    SdArtifactRole.LORA -> SDModelSelectionType.LORA
    SdArtifactRole.CONTROLNET -> SDModelSelectionType.CONTROLNET
    SdArtifactRole.UNKNOWN,
    null -> null
}

private fun expectedRoleForSelection(selectionType: SDModelSelectionType): SdArtifactRole? = when (selectionType) {
    SDModelSelectionType.CHECKPOINT -> SdArtifactRole.FULL_MODEL
    SDModelSelectionType.DIFFUSION,
    SDModelSelectionType.VIDEO_GEN -> SdArtifactRole.STANDALONE_DIFFUSION
    SDModelSelectionType.VAE -> SdArtifactRole.VAE
    SDModelSelectionType.TAE -> SdArtifactRole.TAE
    SDModelSelectionType.CLIP_L -> SdArtifactRole.CLIP_L
    SDModelSelectionType.CLIP_G -> SdArtifactRole.CLIP_G
    SDModelSelectionType.T5XXL -> SdArtifactRole.T5XXL
    SDModelSelectionType.IMAGE_LLM -> SdArtifactRole.LLM
    SDModelSelectionType.IMAGE_LLM_VISION -> SdArtifactRole.LLM_VISION
    SDModelSelectionType.LORA -> SdArtifactRole.LORA
    SDModelSelectionType.CONTROLNET -> SdArtifactRole.CONTROLNET
    else -> null
}

private fun inspectionBlockForSelection(
    inspection: SdArtifactInspection,
    selectionType: SDModelSelectionType,
    configuredFamily: SdModelFamily?
): String? {
    if (!inspection.isInspected || !inspection.isStructurallyUsable) return "invalid"
    val expectedRole = expectedRoleForSelection(selectionType)
    val detectedRole = inspection.detectedRole
    if (inspection.confidence == SdInspectionConfidence.HIGH &&
        expectedRole != null && detectedRole != null &&
        when (expectedRole) {
            SdArtifactRole.FULL_MODEL -> detectedRole != SdArtifactRole.FULL_MODEL &&
                detectedRole != SdArtifactRole.MAIN_MODEL
            else -> detectedRole != expectedRole
        }
    ) {
        return "role"
    }
    if (inspection.confidence == SdInspectionConfidence.HIGH &&
        configuredFamily != null && inspection.detectedFamily != null &&
        configuredFamily != inspection.detectedFamily
    ) {
        return "family"
    }
    return null
}

@Composable
private fun InstalledModelCard(
    model: ModelEntity,
    capabilities: List<SDCapability>,
    onDelete: () -> Unit,
    onExport: () -> Unit = {},
    onEdit: () -> Unit = {},
    onInspect: () -> Unit = {},
    inspectionState: SdInspectionRowState = SdInspectionRowState()
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.filename,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        FormatUtils.formatFileSize(model.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    model.sdFamily?.takeIf { it.isNotBlank() }?.let { family ->
                        Text(
                            text = family + model.sdVariant?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    model.sdCompatProfiles?.takeIf { it.isNotBlank() }?.let { compat ->
                        Text(
                            text = compat,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (hasStaleSdCompatibilityMetadata(model)) {
                        Text(
                            stringResource(R.string.sd_models_compatibility_invalid),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.action_edit),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                // Export button
                IconButton(onClick = onExport) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.action_share),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                // Delete button
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            // Capability badges
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                capabilities.forEach { cap ->
                    CapabilityBadge(cap)
                }
            }
            SdInspectionEvidence(
                model = model,
                state = inspectionState,
                onInspect = onInspect
            )
        }
    }
}

/** Compact, bounded structural evidence shown without exposing model payloads. */
@Composable
private fun SdInspectionEvidence(
    model: ModelEntity,
    state: SdInspectionRowState,
    onInspect: () -> Unit
) {
    if (!model.type.isStableDiffusionArtifact()) return
    val inspection = remember(
        model.sdInspectionJson,
        model.sdInspectionVersion,
        model.sdDetectedFamily,
        model.sdDetectedRole,
        model.sdArtifactLayout,
        model.sdInspectionConfidence
    ) { model.sdArtifactInspection() }
    var detailsExpanded by rememberSaveable(model.path) { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(8.dp)
            )
            .padding(8.dp)
    ) {
        if (inspection == null || !inspection.isInspected) {
            Text(
                stringResource(R.string.sd_models_inspection_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            // Keep the actionable classification visible in the card while
            // putting the less frequently needed structural facts behind a
            // full-width disclosure. Every line is bounded for narrow phones.
            Text(
                stringResource(R.string.sd_models_detected_format, inspection.format.storedValue),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(
                    R.string.sd_models_detected_family,
                    inspection.detectedFamily?.let { stringResource(sdFamilyLabelRes(it)) }
                        ?: stringResource(R.string.sd_models_unknown_value)
                ),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(
                    R.string.sd_models_detected_role,
                    inspection.detectedRole?.storedValue
                        ?: stringResource(R.string.sd_models_unknown_value)
                ),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (inspection.warnings.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.sd_models_inspection_warnings,
                        inspection.warnings.take(3).joinToString(" • ")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!inspection.isStructurallyUsable) {
                Text(
                    stringResource(R.string.sd_models_inspection_structurally_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(
                onClick = { detailsExpanded = !detailsExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (detailsExpanded) {
                        Icons.Default.ExpandLess
                    } else {
                        Icons.Default.ExpandMore
                    },
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(
                        if (detailsExpanded) {
                            R.string.sd_models_inspection_hide_details
                        } else {
                            R.string.sd_models_inspection_show_details
                        }
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (detailsExpanded) {
                Text(
                    stringResource(
                        R.string.sd_models_detected_layout,
                        inspection.artifactLayout.storedValue
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(
                        R.string.sd_models_detected_components,
                        inspectionComponentSummary(inspection)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(
                        R.string.sd_models_inspection_confidence,
                        inspection.confidence.storedValue
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (inspection.confidence == SdInspectionConfidence.HIGH) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (state.status == SdInspectionRowStatus.FAILED) {
            Text(
                stringResource(
                    R.string.sd_models_inspection_failed_short,
                    state.detail ?: stringResource(R.string.error_generic)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (state.status == SdInspectionRowStatus.SUCCEEDED) {
            Text(
                stringResource(R.string.sd_models_inspection_updated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(
            onClick = onInspect,
            enabled = state.status != SdInspectionRowStatus.RUNNING,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.status == SdInspectionRowStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.sd_models_inspecting_artifact))
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(
                        if (inspection?.isInspected == true) {
                            R.string.sd_models_inspection_retry
                        } else {
                            R.string.sd_models_detect
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun inspectionComponentSummary(inspection: SdArtifactInspection): String = buildList {
    if (inspection.containsDiffusion) add(stringResource(R.string.sd_models_component_diffusion))
    if (inspection.containsVae) add("VAE")
    if (inspection.containsClipL) add("CLIP-L")
    if (inspection.containsClipG) add("CLIP-G")
    if (inspection.containsT5xxl) add("T5-XXL")
    if (inspection.containsLlm) add("LLM")
}.joinToString(", ").ifBlank { stringResource(R.string.sd_models_component_none) }

@Composable
private fun DownloadingTab(
    downloadProgress: Map<String, Float>,
    onCancel: (String) -> Unit
) {
    val sdProgressPrefixes = setOf(
        "sd_checkpoint|",
        "sd_upscaler|",
        "sd_diffusion|",
        "sd_clip_l|",
        "sd_clip_g|",
        "sd_t5xxl|",
        "sd_tae|",
        "sd_vae|",
        "sd_lora|",
        "sd_controlnet|",
        "sd_photomaker|",
        "sd_clip_vision|",
        "sd_ip_adapter|",
        "sd_adetailer|"
    )
    val activeDownloads = downloadProgress.filter { (key, value) ->
        sdProgressPrefixes.any { key.startsWith(it) } &&
            (value == DownloadProgressHolder.INDETERMINATE || value in 0f..0.999f)
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            DownloadTaskSection(
                modelTypes = listOf(
                    ModelType.SD_CHECKPOINT,
                    ModelType.SD_UPSCALER,
                    ModelType.SD_DIFFUSION,
                    ModelType.SD_CLIP_L,
                    ModelType.SD_CLIP_G,
                    ModelType.SD_T5XXL,
                    ModelType.SD_TAE,
                    ModelType.SD_VAE,
                    ModelType.SD_LORA,
                    ModelType.SD_TEXTUAL_INVERSION,
                    ModelType.SD_CONTROLNET,
                    ModelType.SD_PHOTOMAKER,
                    ModelType.SD_CLIP_VISION,
                    ModelType.SD_IP_ADAPTER,
                    ModelType.SD_ADETAILER
                )
            )
        }

        if (activeDownloads.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.sd_models_no_active_downloads),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(activeDownloads.toList()) { (key, progress) ->
                val filename = DownloadProgressHolder.getFilename(key) ?: key
                DownloadingCard(
                    filename = filename,
                    progress = progress,
                    onCancel = { onCancel(filename) }
                )
            }
        }
    }
}

@Composable
private fun DownloadingCard(
    filename: String,
    progress: Float,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        filename,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
    }
}

@Composable
private fun DiscoverTab(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    searchResults: List<HfModelDto>,
    suggestions: List<SDSearchSuggestion>,
    onSuggestionClick: (String) -> Unit,
    onRepoClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.sd_models_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, stringResource(R.string.action_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = onSearch,
            modifier = Modifier.fillMaxWidth(),
            enabled = searchQuery.isNotBlank() && !isSearching
        ) {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Search, null)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isSearching) stringResource(R.string.sd_models_searching) else stringResource(R.string.action_search))
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (com.example.llamadroid.ui.components.isCuratedCatalogBrowseMode(searchQuery)) {
                item {
                    // Complete SD/workflow bundles come first; individual
                    // detector downloads remain the focused follow-up section.
                    SdCuratedBundlesSection()
                }
                item(key = "phase_c_adetailer_curated_bundles") {
                    com.example.llamadroid.ui.components.CuratedModelBundleSection(
                        title = stringResource(R.string.phase_c_adetailer_bundles_title),
                        description = stringResource(R.string.adetailer_bundles_desc),
                        bundles = com.example.llamadroid.data.model.AdetailerCuratedBundleCatalog.bundles
                    )
                }
            }

            // Show search results
            if (searchResults.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.sd_models_search_results, searchResults.size),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                
                items(searchResults) { result ->
                    SearchResultCard(
                        result = result,
                        onClick = { onRepoClick(result.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: SDSearchSuggestion,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                suggestion.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.sd_models_search_label, suggestion.query),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestion.capabilities.forEach { cap ->
                    CapabilityBadge(cap)
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: HfModelDto,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.id,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "${result.likes}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "${result.downloads}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.sd_models_view_files),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun CapabilityBadge(capability: SDCapability) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = androidx.compose.ui.graphics.Color(capability.color).copy(alpha = 0.15f)
    ) {
        Text(
            stringResource(capability.labelRes),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color(capability.color)
        )
    }
}
