package com.example.llamadroid.ui.distributed

import android.app.ActivityManager
import android.content.Context

import android.content.Intent
import android.widget.Toast
import android.net.nsd.NsdServiceInfo
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle as collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SavedCommandEntity
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.data.model.SavedWorker
import com.example.llamadroid.service.DistributedService
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.WorkerInfo
import com.example.llamadroid.service.DistributedLlamaArguments
import com.example.llamadroid.service.DistributedLlamaLaunchProfile
import com.example.llamadroid.service.DistributedLlamaLaunchResolver
import com.example.llamadroid.service.DistributedLlamaResolveRequest
import com.example.llamadroid.service.DistributedWorkerLaunchSpec
import com.example.llamadroid.service.DistributedSpeculativePlacement
import com.example.llamadroid.service.DistributedDeviceKind
import com.example.llamadroid.service.DistributedDeviceRef
import com.example.llamadroid.service.MainModelPlacement
import com.example.llamadroid.service.MainModelPlacementMode
import com.example.llamadroid.service.DraftModelPlacement
import com.example.llamadroid.service.DraftModelPlacementMode
import com.example.llamadroid.service.MmprojPlacement
import com.example.llamadroid.service.DistributedKvPlacement
import com.example.llamadroid.service.DistributedKvPlacementMode
import com.example.llamadroid.service.DistributedLayerAllocation
import com.example.llamadroid.service.LlamaModelMetadataProbe
import com.example.llamadroid.service.LlamaModelProbeTimeoutException
import com.example.llamadroid.service.LlamaModelProbeNoLayersException
import com.example.llamadroid.service.LlamaModelProbeResult
import com.example.llamadroid.service.stableKey
import com.example.llamadroid.service.ResolvedDistributedLaunch
import com.example.llamadroid.service.DistributedLaunchResolution
import com.example.llamadroid.service.DistributedMasterLlamaService
import com.example.llamadroid.service.DistributedMasterRuntimeState
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.service.LlamaSpeculativeMode
import com.example.llamadroid.service.ProcessController
import com.example.llamadroid.service.effectiveSpeculativeDraftPath
import com.example.llamadroid.service.speculativeDraftModelsFor
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.util.GGUFParser
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.*
import com.example.llamadroid.service.RemoteMasterServer

private const val MASTER_CPU_PLACEMENT_ID = -1L

private sealed interface ModelProbeUiState {
    data object Missing : ModelProbeUiState
    data object Checking : ModelProbeUiState
    data class Ready(val result: LlamaModelProbeResult, val parserLayers: Int?) : ModelProbeUiState
    data class Manual(val result: LlamaModelProbeResult) : ModelProbeUiState
    data class Failed(val message: String, val parserLayers: Int?) : ModelProbeUiState
}

/**
 * Master mode screen - select model, manage workers, start distributed inference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterModeScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { AppDatabase.getDatabase(context) }
    val settingsRepo = remember { com.example.llamadroid.data.SettingsRepository(context) }
    val nativeBinarySelection by settingsRepo.llmNativeBinarySelection.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    
    // Collect saved workers from database
    val savedWorkers by db.savedWorkerDao().getAllWorkers().collectAsState(initial = emptyList())
    val enabledWorkers = remember(savedWorkers) { savedWorkers.filter { it.isEnabled } }
    
    // Collect only the state rendered by this route; worker status is projected elsewhere.
    val masterRamMB by DistributedService.masterRamMB.collectAsState()
    LaunchedEffect(Unit) { DistributedMasterRuntimeState.attach(context.applicationContext) }
    val distributedRuntimeState by DistributedMasterRuntimeState.state.collectAsState()
    val isServerRunning = distributedRuntimeState !is ServerState.Stopped &&
        distributedRuntimeState !is ServerState.Error
    val lastCommand by DistributedService.lastCommand.collectAsState()
    val customCommand by DistributedService.customCommand.collectAsState()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    LaunchedEffect(Unit) { DistributedService.refreshLastCommand() }
    
    
    // Sync enabled saved workers to DistributedService (diff-based to avoid disrupting active workers)
    LaunchedEffect(savedWorkers) {
        val enabledWorkers = savedWorkers.filter { it.isEnabled }
        val currentWorkers = DistributedService.workers.value
        
        // Build set of desired workers from DB
        val desiredKeys = enabledWorkers.map { "${it.ip}:${it.port}" }.toSet()
        val currentKeys = currentWorkers.map { "${it.ip}:${it.port}" }.toSet()
        
        // Remove workers no longer in DB
        currentKeys.minus(desiredKeys).forEach { key ->
            val parts = key.split(":")
            if (parts.size == 2) {
                DistributedService.removeWorker(parts[0], parts[1].toIntOrNull() ?: 50052)
            }
        }
        
        // Add or update workers from DB
        enabledWorkers.forEach { saved ->
            DistributedService.addWorkerManually(
                saved.ip, 
                saved.port, 
                saved.deviceName, 
                saved.ramMB,
                saved.assignedProportion
            )
        }
    }
    
    // Get ALL models from database and filter properly
    val allModels by db.modelDao().getAllModels().collectAsState(initial = emptyList())
    
    // Filter to models that can be used for chat inference. Embeddings are not
    // generative targets and must not appear in the main or draft selectors.
    // Check if file actually exists (for imported models) OR isDownloaded flag is true
    val availableModels by produceState(initialValue = emptyList<ModelEntity>(), allModels) {
        value = withContext(Dispatchers.IO) {
            allModels.filter { model -> model.isDownloaded || java.io.File(model.path).exists() }
        }
    }
    val llmModels = remember(availableModels) { availableModels.filter { model ->
        model.type == ModelType.LLM || model.type == ModelType.VISION
    } }
    val mmprojModels = remember(availableModels) { availableModels.filter { model ->
        model.type == ModelType.MMPROJ || model.type == ModelType.VISION_PROJECTOR
    } }
    
    val selectedModel = DistributedService.masterSelectedModel.collectAsState().value
    var showModelPicker by remember { mutableStateOf(false) }
    var showAddWorkerDialog by remember { mutableStateOf(false) }
    var showEditWorkerDialog by remember { mutableStateOf(false) }
    var workerToEdit by remember { mutableStateOf<SavedWorker?>(null) }
    var isStarting by remember { mutableStateOf(false) }
    var isPreviewing by remember { mutableStateOf(false) }
    
    // Custom Commands
    val savedCommands by db.savedCommandDao()
        .getCommandsByScope(SavedCommandScopes.MASTER)
        .collectAsState(initial = emptyList())
    var showSaveCommandDialog by remember { mutableStateOf(false) }
    var showLoadCommandDialog by remember { mutableStateOf(false) }
    var commandNameToSave by remember { mutableStateOf("") }
    
    // Model configuration settings
    val contextSize by DistributedService.masterContextSize.collectAsState()
    val serverPort by DistributedService.masterServerPort.collectAsState()
    var serverPortText by rememberSaveable(serverPort) { mutableStateOf(serverPort.toString()) }
    val contextSizeText by DistributedService.masterContextSizeText.collectAsState()
    val temperature by DistributedService.masterTemperature.collectAsState()
    val threads by DistributedService.masterThreads.collectAsState()
    val batchSize by DistributedService.masterBatchSize.collectAsState()
    val batchSizeText by DistributedService.masterBatchSizeText.collectAsState()
    
    // KV Cache settings
    val kvCacheEnabled by DistributedService.masterKvCacheEnabled.collectAsState()
    val kvCacheTypeK by DistributedService.masterKvCacheTypeK.collectAsState()
    val kvCacheTypeV by DistributedService.masterKvCacheTypeV.collectAsState()
    val kvCacheReuse by DistributedService.masterKvCacheReuse.collectAsState()
    
    // Advanced fields
    val masterParallelText by DistributedService.masterParallelText.collectAsState()
    val masterCacheRamText by DistributedService.masterCacheRamText.collectAsState()
    val masterCustomFlags by DistributedService.masterCustomFlags.collectAsState()
    val masterCommandTemplate by DistributedService.masterCommandTemplate.collectAsState()
    val masterParallel by DistributedService.masterParallel.collectAsState()
    val masterCacheRam by DistributedService.masterCacheRam.collectAsState()
    val masterFlashAttention by DistributedService.masterFlashAttention.collectAsState()
    var masterShowAdvancedSettings by remember { mutableStateOf(false) }

    // Speculative decoding settings
    val speculativeEnabled by DistributedService.masterSpeculativeEnabled.collectAsState()
    val storedDraftModelPath by DistributedService.masterDraftModelPath.collectAsState()
    var showDraftModelPicker by remember { mutableStateOf(false) }
    val draftMax by DistributedService.masterDraftMax.collectAsState()
    val draftMaxText by DistributedService.masterDraftMaxText.collectAsState()
    val draftMin by DistributedService.masterDraftMin.collectAsState()
    val draftMinText by DistributedService.masterDraftMinText.collectAsState()
    val draftPMin by DistributedService.masterDraftPMin.collectAsState()
    val draftPMinText by DistributedService.masterDraftPMinText.collectAsState()
    val draftThreads by DistributedService.masterDraftThreads.collectAsState()
    val draftThreadsText by DistributedService.masterDraftThreadsText.collectAsState()
    val draftThreadsBatch by DistributedService.masterDraftThreadsBatch.collectAsState()
    val draftThreadsBatchText by DistributedService.masterDraftThreadsBatchText.collectAsState()
    val speculativeMode by DistributedService.masterSpeculativeMode.collectAsState()
    val draftModels = remember(availableModels, speculativeMode) {
        speculativeDraftModelsFor(availableModels, speculativeMode)
    }
    val effectiveDraftModelPath = remember(storedDraftModelPath, draftModels) {
        effectiveSpeculativeDraftPath(storedDraftModelPath, draftModels)
    }
    val draftModel = remember(effectiveDraftModelPath, draftModels) {
        draftModels.firstOrNull { it.path == effectiveDraftModelPath }
    }
    val fitEnabled by DistributedService.masterFitEnabled.collectAsState()
    val fitTargetMiB by DistributedService.masterFitTargetMiB.collectAsState()
    val nglArgument by DistributedService.masterNglArgument.collectAsState()
    val speculativePlacement by DistributedService.masterSpeculativePlacement.collectAsState()
    val speculativeWorkerIndex by DistributedService.masterSpeculativeWorkerIndex.collectAsState()
    val draftGpuLayers by DistributedService.masterDraftGpuLayers.collectAsState()
    val draftDeviceMode by DistributedService.masterDraftDeviceMode.collectAsState()
    val mtpUseDraftModel by DistributedService.masterMtpUseDraftModel.collectAsState()
    val mtpDraftMax by DistributedService.masterMtpDraftMax.collectAsState()
    val mtpDraftMin by DistributedService.masterMtpDraftMin.collectAsState()
    val mtpDraftPMin by DistributedService.masterMtpDraftPMin.collectAsState()
    val ngramModNMatch by DistributedService.masterNgramModNMatch.collectAsState()
    val ngramModNMin by DistributedService.masterNgramModNMin.collectAsState()
    val ngramModNMax by DistributedService.masterNgramModNMax.collectAsState()
    val ngramSimpleSizeN by DistributedService.masterNgramSimpleSizeN.collectAsState()
    val ngramSimpleSizeM by DistributedService.masterNgramSimpleSizeM.collectAsState()
    val ngramSimpleMinHits by DistributedService.masterNgramSimpleMinHits.collectAsState()
    val ngramMapKSizeN by DistributedService.masterNgramMapKSizeN.collectAsState()
    val ngramMapKSizeM by DistributedService.masterNgramMapKSizeM.collectAsState()
    val ngramMapKMinHits by DistributedService.masterNgramMapKMinHits.collectAsState()
    val ngramMapK4VSizeN by DistributedService.masterNgramMapK4VSizeN.collectAsState()
    val ngramMapK4VSizeM by DistributedService.masterNgramMapK4VSizeM.collectAsState()
    val ngramMapK4VMinHits by DistributedService.masterNgramMapK4VMinHits.collectAsState()
    var fitCardExpanded by rememberSaveable { mutableStateOf(true) }
    var placementCardExpanded by rememberSaveable { mutableStateOf(true) }
    var ramCardExpanded by rememberSaveable { mutableStateOf(true) }
    var modelSettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var remoteMasterExpanded by rememberSaveable { mutableStateOf(false) }
    var speculativeModeMenuExpanded by remember { mutableStateOf(false) }
    var placementMenuExpanded by remember { mutableStateOf(false) }
    var workerMenuExpanded by remember { mutableStateOf(false) }
    var draftDeviceMenuExpanded by remember { mutableStateOf(false) }
    var mainPlacementModeName by rememberSaveable { mutableStateOf(MainModelPlacementMode.DISTRIBUTED.name) }
    val mainPlacementMode = MainModelPlacementMode.valueOf(mainPlacementModeName)
    var mainResidentWorkerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var includeMasterInMain by rememberSaveable { mutableStateOf(true) }
    var selectedMainWorkerIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    var kvPlacementModeName by rememberSaveable {
        mutableStateOf(DistributedKvPlacementMode.DISTRIBUTED_WITH_LAYERS.name)
    }
    var kvHostKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDraftWorkerIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    var selectedMmprojPath by rememberSaveable { mutableStateOf<String?>(null) }
    var mmprojPlacementName by rememberSaveable { mutableStateOf(MmprojPlacement.MASTER_ACCELERATOR.name) }
    var showMmprojPicker by remember { mutableStateOf(false) }
    var modelProbeAttempt by rememberSaveable { mutableIntStateOf(0) }
    var showManualLayerInput by rememberSaveable(selectedModel?.path) { mutableStateOf(false) }
    var manualLayerText by rememberSaveable(selectedModel?.path) { mutableStateOf("") }
    var manualTransformerBlocks by rememberSaveable(selectedModel?.path) { mutableStateOf<Int?>(null) }

    LaunchedEffect(enabledWorkers) {
        val enabledIds = enabledWorkers.map { it.id }.toSet()
        selectedMainWorkerIds = if (selectedMainWorkerIds.isEmpty()) {
            enabledWorkers.map { it.id }
        } else {
            selectedMainWorkerIds.filter { it in enabledIds }
        }
    }

    val modelProbeState by produceState<ModelProbeUiState>(
        initialValue = ModelProbeUiState.Missing,
        selectedModel?.path,
        nativeBinarySelection,
        speculativeEnabled,
        speculativeMode,
        fitEnabled,
        modelProbeAttempt,
        manualTransformerBlocks
    ) {
        val model = selectedModel
        if (model == null) {
            value = ModelProbeUiState.Missing
            return@produceState
        }
        manualTransformerBlocks?.let { blocks ->
            value = ModelProbeUiState.Manual(LlamaModelMetadataProbe.manualResult(blocks, model.path))
            return@produceState
        }
        value = ModelProbeUiState.Checking
        val parserLayers = withContext(Dispatchers.IO) { GGUFParser.parse(model.path)?.layerCount }
        value = try {
            val binary = DistributedLaunchResolution.selectBinary(
                context.applicationContext,
                speculativeMode.takeIf { speculativeEnabled },
                fitEnabled
            )
            ModelProbeUiState.Ready(
                LlamaModelMetadataProbe.probe(context.applicationContext, model.path, binary),
                parserLayers
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = when (error) {
                is LlamaModelProbeTimeoutException -> resources.getString(R.string.dist_probe_timeout)
                is LlamaModelProbeNoLayersException -> resources.getString(R.string.dist_probe_no_layers)
                else -> error.message ?: resources.getString(R.string.dist_probe_failed)
            }
            ModelProbeUiState.Failed(message, parserLayers)
        }
    }

    val effectiveLayerResult = when (val state = modelProbeState) {
        is ModelProbeUiState.Ready -> state.result
        is ModelProbeUiState.Manual -> state.result
        else -> null
    }

    LaunchedEffect(storedDraftModelPath, draftModels) {
        // Wait for at least one candidate before synchronizing persisted state;
        // the database-backed list starts empty while it is loading. Once the
        // list is available, an incompatible path is cleared rather than being
        // allowed to reach distributed launch fallback state.
        if (draftModels.isNotEmpty()) {
            DistributedService.setMasterDraftModel(draftModel)
        }
    }
    
    
    // Network visibility - when enabled, uses 0.0.0.0 to allow external connections
    var enableNetworkAccess by remember { mutableStateOf(true) }
    
    // Reset custom command when any parameter changes
    var commandInvalidationArmed by remember { mutableStateOf(false) }
    LaunchedEffect(
        selectedModel, threads, batchSize, contextSize, temperature, 
        speculativeEnabled, draftModel, draftMax, draftMin, draftPMin, draftThreads, draftThreadsBatch,
        mtpUseDraftModel, mtpDraftMax, mtpDraftMin, mtpDraftPMin,
        ngramModNMatch, ngramModNMin, ngramModNMax, ngramSimpleSizeN, ngramSimpleSizeM,
        ngramSimpleMinHits, ngramMapKSizeN, ngramMapKSizeM, ngramMapKMinHits,
        ngramMapK4VSizeN, ngramMapK4VSizeM, ngramMapK4VMinHits,
        draftDeviceMode, speculativeMode, speculativePlacement, speculativeWorkerIndex, draftGpuLayers,
        enableNetworkAccess, masterRamMB, savedWorkers, fitEnabled, fitTargetMiB, nglArgument,
        kvCacheEnabled, kvCacheTypeK, kvCacheTypeV, kvCacheReuse,
        masterParallel, masterCacheRam, masterCustomFlags, masterCommandTemplate,
        masterFlashAttention, serverPort, mainPlacementModeName, mainResidentWorkerId,
        selectedDraftWorkerIds, selectedMmprojPath, mmprojPlacementName,
        includeMasterInMain, selectedMainWorkerIds, kvPlacementModeName, kvHostKey,
        manualTransformerBlocks
    ) {
        if (commandInvalidationArmed) {
            DistributedService.setCustomCommand(null)
            DistributedService.clearLastCommand()
        } else {
            commandInvalidationArmed = true
        }
    }
    
    // Get device memory info
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)
    val totalRamMB = (memInfo.totalMem / (1024 * 1024)).toInt()
    val availableRamMB = (memInfo.availMem / (1024 * 1024)).toInt()
    
    var masterRamSlider by remember { mutableFloatStateOf(masterRamMB.toFloat().coerceIn(0f, totalRamMB.toFloat())) }
    var threadsText by remember { mutableStateOf(threads.toString()) }
    
    // Command editing state
    var isEditingCommand by remember { mutableStateOf(false) }
    var editedCommand by remember { mutableStateOf("") }
    var masterRamText by remember { mutableStateOf(masterRamMB.toString()) }  // For text input
    
    // Discovered Workers Section (NSD)
    val discoveredServices: List<NsdServiceInfo> by DistributedService.discoveredServices.collectAsState()
    
    // Manage Discovery Lifecycle
    DisposableEffect(Unit) {
        DistributedService.startDiscovery(context)
        onDispose {
            DistributedService.stopDiscovery()
            DistributedService.setCustomCommand(null)
            DistributedService.clearLastCommand()
        }
    }
    
    // Filter out already saved workers
    val newDiscoveredWorkers: List<NsdServiceInfo> = discoveredServices.filter { service ->
        val alreadySaved = savedWorkers.any { 
            it.ip == service.host.hostAddress && it.port == service.port
        }
        !alreadySaved
    }

    val enabledWorkerSlots = enabledWorkers.map { "${it.ip}:${it.port}" }
    val deviceSlots = DistributedService.distributedDeviceSlots(enabledWorkerSlots)
    val fitValidationError = if (fitEnabled && deviceSlots.isNotEmpty()) {
        runCatching { DistributedLlamaArguments.normalizeFitTarget(fitTargetMiB, deviceSlots.size) }
            .exceptionOrNull()?.message
    } else null

    suspend fun resolveCurrentDistributedProfile(): DistributedLlamaLaunchProfile = withContext(Dispatchers.IO) {
        val model = requireNotNull(selectedModel) { resources.getString(R.string.dist_error_no_model) }
        val workerSpecs = enabledWorkers.map {
            DistributedWorkerLaunchSpec(
                address = "${it.ip}:${it.port}",
                ramMiB = it.ramMB,
                assignedProportion = it.assignedProportion,
                workerId = it.id
            )
        }
        require(workerSpecs.isNotEmpty()) { resources.getString(R.string.dist_error_no_workers) }
        val probe = effectiveLayerResult
            ?: error(resources.getString(R.string.dist_probe_required))
        val selectedDraftPath = draftModel?.path
        val effectiveDraftPath = when {
            !speculativeEnabled -> null
            speculativeMode == LlamaSpeculativeMode.DRAFT_MTP && !mtpUseDraftModel -> null
            else -> selectedDraftPath
        }
        val requiresSelectedDraft = speculativeMode.requiresDraftModel ||
            (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP && mtpUseDraftModel)
        if (speculativeEnabled && requiresSelectedDraft) {
            require(!effectiveDraftPath.isNullOrBlank()) {
                resources.getString(R.string.dist_speculative_missing_required_draft)
            }
        }
        val localState = LlamaService.state.value
        val localOwnedPort = (localState as? ServerState.Running)?.port
            ?: settingsRepo.serverPort.value.takeUnless {
                localState is ServerState.Stopped || localState is ServerState.Error
            }
        require(localOwnedPort != serverPort) {
            resources.getString(R.string.dist_port_conflicts_local, serverPort)
        }
        val selectedMainWorkers = when (mainPlacementMode) {
            MainModelPlacementMode.RESIDENT -> if (mainResidentWorkerId == MASTER_CPU_PLACEMENT_ID) emptyList() else listOfNotNull(
                enabledWorkers.firstOrNull { it.id == mainResidentWorkerId } ?: enabledWorkers.firstOrNull()
            )
            MainModelPlacementMode.DISTRIBUTED -> enabledWorkers.filter { it.id in selectedMainWorkerIds }
        }
        val mainDevices = when (mainPlacementMode) {
            MainModelPlacementMode.RESIDENT -> if (mainResidentWorkerId == MASTER_CPU_PLACEMENT_ID) {
                listOf(DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU))
            } else selectedMainWorkers.map {
                DistributedDeviceRef(DistributedDeviceKind.WORKER, it.id, "${it.ip}:${it.port}")
            }
            MainModelPlacementMode.DISTRIBUTED -> buildList {
                if (includeMasterInMain) add(DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU))
                selectedMainWorkers.forEach {
                    add(DistributedDeviceRef(DistributedDeviceKind.WORKER, it.id, "${it.ip}:${it.port}"))
                }
            }
        }
        val mainPlacement = MainModelPlacement(
            mode = mainPlacementMode,
            devices = mainDevices,
            shares = selectedMainWorkers.associate {
                it.id.toString() to (it.assignedProportion ?: it.ramMB.toFloat())
            },
            ramContributionsMiB = buildMap {
                if (includeMasterInMain || mainResidentWorkerId == MASTER_CPU_PLACEMENT_ID) {
                    put(DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU).stableKey(), masterRamSlider.toInt())
                }
                selectedMainWorkers.forEach { worker ->
                    put(
                        DistributedDeviceRef(
                            DistributedDeviceKind.WORKER,
                            worker.id,
                            "${worker.ip}:${worker.port}"
                        ).stableKey(),
                        worker.ramMB
                    )
                }
            }
        )
        val kvMode = DistributedKvPlacementMode.valueOf(kvPlacementModeName)
        val kvDevice = when {
            kvMode != DistributedKvPlacementMode.ACCELERATOR_DEVICE -> null
            kvHostKey == "master_accelerator" -> DistributedDeviceRef(DistributedDeviceKind.MASTER_ACCELERATOR)
            else -> enabledWorkers.firstOrNull { "worker:${it.id}" == kvHostKey }?.let {
                DistributedDeviceRef(DistributedDeviceKind.WORKER, it.id, "${it.ip}:${it.port}")
            }
        }
        val draftRefs = when {
            speculativePlacement == DistributedSpeculativePlacement.LOCAL ||
                speculativePlacement == DistributedSpeculativePlacement.MASTER_DEDICATED -> listOf(
                DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU)
            )
            selectedDraftWorkerIds.isNotEmpty() -> enabledWorkers.filter { it.id in selectedDraftWorkerIds }.map {
                DistributedDeviceRef(DistributedDeviceKind.WORKER, it.id, "${it.ip}:${it.port}")
            }
            else -> listOfNotNull(enabledWorkers.getOrNull(speculativeWorkerIndex ?: 0)).map {
                DistributedDeviceRef(DistributedDeviceKind.WORKER, it.id, "${it.ip}:${it.port}")
            }
        }
        DistributedLlamaLaunchResolver.resolve(
            DistributedLlamaResolveRequest(
                modelPath = model.path,
                modelSizeMiB = (java.io.File(model.path).length() / (1024L * 1024L)).toInt(),
                modelLayers = probe.offloadableLayers,
                transformerBlocks = probe.transformerBlocks,
                workers = workerSpecs,
                masterTargetRamMiB = masterRamSlider.toInt(),
                host = if (enableNetworkAccess) "0.0.0.0" else "127.0.0.1",
                port = serverPort,
                threads = threads,
                batchSize = batchSize,
                contextSize = contextSize,
                temperature = temperature,
                kvCacheEnabled = kvCacheEnabled,
                kvCacheTypeK = kvCacheTypeK,
                kvCacheTypeV = kvCacheTypeV,
                kvCacheReuse = kvCacheReuse,
                fitEnabled = fitEnabled,
                fitTargetMiB = fitTargetMiB,
                nGpuLayersArgument = nglArgument.trim().takeIf(String::isNotEmpty),
                speculativeMode = speculativeMode.takeIf { speculativeEnabled },
                draftModelPath = effectiveDraftPath,
                draftModelSizeMiB = effectiveDraftPath?.let {
                    (java.io.File(it).length() / (1024L * 1024L)).toInt()
                } ?: 0,
                draftMax = draftMax,
                draftMin = draftMin,
                draftPMin = draftPMin,
                draftThreads = draftThreads,
                draftThreadsBatch = draftThreadsBatch,
                draftDeviceMode = draftDeviceMode,
                draftGpuLayers = draftGpuLayers,
                mtpDraftMax = mtpDraftMax,
                mtpDraftMin = mtpDraftMin,
                mtpDraftPMin = mtpDraftPMin,
                ngramModNMatch = ngramModNMatch,
                ngramModNMin = ngramModNMin,
                ngramModNMax = ngramModNMax,
                ngramSimpleSizeN = ngramSimpleSizeN,
                ngramSimpleSizeM = ngramSimpleSizeM,
                ngramSimpleMinHits = ngramSimpleMinHits,
                ngramMapKSizeN = ngramMapKSizeN,
                ngramMapKSizeM = ngramMapKSizeM,
                ngramMapKMinHits = ngramMapKMinHits,
                ngramMapK4VSizeN = ngramMapK4VSizeN,
                ngramMapK4VSizeM = ngramMapK4VSizeM,
                ngramMapK4VMinHits = ngramMapK4VMinHits,
                speculativePlacement = speculativePlacement,
                speculativeWorkerIndex = speculativeWorkerIndex,
                parallel = masterParallel,
                cacheRam = masterCacheRam,
                customFlags = masterCustomFlags.takeIf(String::isNotBlank),
                commandTemplate = masterCommandTemplate.takeIf(String::isNotBlank),
                customCommand = DistributedService.customCommand.value,
                flashAttention = masterFlashAttention,
                mainPlacement = mainPlacement,
                draftPlacement = DraftModelPlacement(
                    mode = if (draftRefs.size > 1) DraftModelPlacementMode.DISTRIBUTED else DraftModelPlacementMode.RESIDENT,
                    devices = draftRefs
                ),
                mmprojPath = selectedMmprojPath,
                mmprojPlacement = MmprojPlacement.valueOf(mmprojPlacementName),
                kvPlacement = DistributedKvPlacement(kvMode, kvDevice)
            )
        )
    }

    suspend fun resolveCurrentDistributedLaunch(): ResolvedDistributedLaunch = withContext(Dispatchers.IO) {
        DistributedLaunchResolution.resolve(context.applicationContext, resolveCurrentDistributedProfile())
    }

    suspend fun buildCurrentDistributedCommand(): String = withContext(Dispatchers.IO) {
        val launch = resolveCurrentDistributedLaunch()
        ProcessController().buildCommandString(launch.argv)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dist_master_mode)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.kiwix_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Model Selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.dist_step_model_endpoint),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        OutlinedCard(
                            onClick = { showModelPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedModel?.filename ?: stringResource(R.string.dist_tap_to_select_model),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = if (selectedModel != null) {
                                            formatFileSize(selectedModel!!.sizeBytes)
                                        } else {
                                            stringResource(R.string.dist_models_available, llmModels.size)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = stringResource(R.string.dist_select_model)
                                )
                            }
                        }
                        when (val probeState = modelProbeState) {
                            ModelProbeUiState.Checking -> Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(
                                        stringResource(R.string.dist_probe_checking),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    stringResource(R.string.dist_probe_bounded_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = { showManualLayerInput = true },
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                                ) {
                                    Text(stringResource(R.string.dist_probe_enter_manually))
                                }
                            }
                            is ModelProbeUiState.Ready -> {
                                Text(
                                    stringResource(
                                        R.string.dist_probe_ready,
                                        probeState.result.transformerBlocks,
                                        probeState.result.offloadableLayers
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (probeState.parserLayers != null &&
                                    probeState.parserLayers != probeState.result.offloadableLayers
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.dist_probe_mismatch,
                                            probeState.parserLayers,
                                            probeState.result.offloadableLayers
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            is ModelProbeUiState.Manual -> Text(
                                stringResource(
                                    R.string.dist_probe_manual_active,
                                    probeState.result.transformerBlocks,
                                    probeState.result.offloadableLayers
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            is ModelProbeUiState.Failed -> Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    stringResource(R.string.dist_probe_error, probeState.message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(
                                    onClick = { modelProbeAttempt++ },
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.dist_probe_retry))
                                }
                                TextButton(
                                    onClick = { showManualLayerInput = true },
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.dist_probe_enter_manually))
                                }
                            }
                            ModelProbeUiState.Missing -> Unit
                        }
                        if (showManualLayerInput || manualTransformerBlocks != null) {
                            val parsedManualBlocks = manualLayerText.toIntOrNull()
                            val manualValueValid = parsedManualBlocks != null && parsedManualBlocks in 1..2048
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = manualLayerText,
                                    onValueChange = { input ->
                                        manualLayerText = input.filter(Char::isDigit).take(4)
                                        if (manualTransformerBlocks != null) {
                                            manualLayerText.toIntOrNull()
                                                ?.takeIf { it in 1..2048 }
                                                ?.let { manualTransformerBlocks = it }
                                        }
                                    },
                                    label = { Text(stringResource(R.string.dist_probe_manual_label)) },
                                    supportingText = {
                                        Text(
                                            if (manualTransformerBlocks != null && !manualValueValid) {
                                                stringResource(
                                                    R.string.dist_probe_manual_last_valid,
                                                    manualTransformerBlocks ?: 0
                                                )
                                            } else {
                                                stringResource(R.string.dist_probe_manual_helper)
                                            }
                                        )
                                    },
                                    isError = manualLayerText.isNotBlank() && !manualValueValid,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (manualTransformerBlocks == null) {
                                    Button(
                                        onClick = { manualTransformerBlocks = parsedManualBlocks },
                                        enabled = manualValueValid,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.dist_probe_use_manual))
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            manualTransformerBlocks = null
                                            manualLayerText = ""
                                            showManualLayerInput = false
                                            modelProbeAttempt++
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.dist_probe_use_binary_again))
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = serverPortText,
                            onValueChange = { text ->
                                serverPortText = text.filter(Char::isDigit).take(5)
                                serverPortText.toIntOrNull()?.takeIf { it in 1..65535 }
                                    ?.let(DistributedService::setMasterServerPort)
                            },
                            label = { Text(stringResource(R.string.dist_master_server_port)) },
                            supportingText = { Text(stringResource(R.string.dist_master_server_port_hint)) },
                            isError = serverPortText.toIntOrNull()?.let { it in 1..65535 } != true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isServerRunning
                        )
                    }
                }
            }

            item(key = "step_workers") {
                DistributedPlacementStepCard(
                    title = stringResource(R.string.dist_step_workers),
                    description = stringResource(R.string.dist_step_workers_desc)
                ) {
                    if (enabledWorkers.isEmpty()) {
                        Text(stringResource(R.string.dist_error_no_workers), color = MaterialTheme.colorScheme.error)
                    } else {
                        enabledWorkers.forEach { worker ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(worker.deviceName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${worker.ramMB} MB", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            item(key = "step_main_placement") {
                DistributedPlacementStepCard(
                    title = stringResource(R.string.dist_step_main_placement),
                    description = stringResource(R.string.dist_step_main_placement_desc)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mainPlacementMode == MainModelPlacementMode.DISTRIBUTED,
                            onClick = { mainPlacementModeName = MainModelPlacementMode.DISTRIBUTED.name },
                            label = { Text(stringResource(R.string.dist_placement_distributed)) }
                        )
                        FilterChip(
                            selected = mainPlacementMode == MainModelPlacementMode.RESIDENT,
                            onClick = { mainPlacementModeName = MainModelPlacementMode.RESIDENT.name },
                            label = { Text(stringResource(R.string.dist_placement_resident)) }
                        )
                    }
                    if (mainPlacementMode == MainModelPlacementMode.RESIDENT) {
                        Text(stringResource(R.string.dist_choose_resident_device), style = MaterialTheme.typography.labelLarge)
                        FilterChip(
                            selected = mainResidentWorkerId == MASTER_CPU_PLACEMENT_ID,
                            onClick = { mainResidentWorkerId = MASTER_CPU_PLACEMENT_ID },
                            label = { Text(stringResource(R.string.dist_master_cpu)) }
                        )
                        enabledWorkers.forEach { worker ->
                            FilterChip(
                                selected = mainResidentWorkerId == worker.id ||
                                    (mainResidentWorkerId == null && worker == enabledWorkers.firstOrNull()),
                                onClick = { mainResidentWorkerId = worker.id },
                                label = { Text(worker.deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = includeMasterInMain,
                                onCheckedChange = { includeMasterInMain = it }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.dist_include_master_main))
                                Text(
                                    stringResource(R.string.dist_include_master_main_desc, masterRamSlider.toInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        enabledWorkers.forEach { worker ->
                            FilterChip(
                                selected = worker.id in selectedMainWorkerIds,
                                onClick = {
                                    selectedMainWorkerIds = if (worker.id in selectedMainWorkerIds) {
                                        selectedMainWorkerIds - worker.id
                                    } else {
                                        selectedMainWorkerIds + worker.id
                                    }
                                },
                                label = {
                                    Text(
                                        "${worker.deviceName} · ${worker.ramMB} MB",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                    DistributedAllocationStrip(
                        workers = enabledWorkers,
                        residentWorkerId = mainResidentWorkerId.takeIf { mainPlacementMode == MainModelPlacementMode.RESIDENT },
                        includeMaster = includeMasterInMain && mainPlacementMode == MainModelPlacementMode.DISTRIBUTED ||
                            mainPlacementMode == MainModelPlacementMode.RESIDENT && mainResidentWorkerId == MASTER_CPU_PLACEMENT_ID,
                        masterRamMiB = masterRamSlider.toInt(),
                        selectedWorkerIds = if (mainPlacementMode == MainModelPlacementMode.DISTRIBUTED) {
                            selectedMainWorkerIds.toSet()
                        } else null,
                        offloadableLayers = effectiveLayerResult
                            ?.offloadableLayers
                            ?.takeUnless {
                                kvPlacementModeName == DistributedKvPlacementMode.ACCELERATOR_DEVICE.name
                            }
                    )
                }
            }
            
            // Remote administration is intentionally lazy: this legacy panel owns many
            // network/UI flows and should not affect scrolling unless the user opens it.
            item(key = "remote_master_control") {
                DistributedCollapsibleCard(
                    title = stringResource(R.string.dist_remote_master),
                    description = stringResource(R.string.dist_remote_server_desc),
                    expanded = remoteMasterExpanded,
                    onExpandedChange = { remoteMasterExpanded = it }
                ) {
                    RemoteMasterCard(modifier = Modifier.fillMaxWidth())
                }
            }
            
            // Master RAM Configuration
            item {
                DistributedCollapsibleCard(
                    title = stringResource(R.string.dist_your_ram_contribution),
                    description = stringResource(R.string.dist_memory_card_desc),
                    expanded = ramCardExpanded,
                    onExpandedChange = { ramCardExpanded = it }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.dist_your_ram_contribution),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        // RAM with text input (synced with slider)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${masterRamSlider.toInt()} ${stringResource(R.string.agent_unit_mb)}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (masterRamSlider.toInt() == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = masterRamText,
                                onValueChange = { newValue ->
                                    masterRamText = newValue
                                    newValue.toIntOrNull()?.let { ram ->
                                        val clamped = ram.coerceIn(0, totalRamMB)
                                        masterRamSlider = clamped.toFloat()
                                        DistributedService.setMasterRam(clamped)
                                    }
                                },
                                label = { Text(stringResource(R.string.agent_unit_mb)) },
                                singleLine = true,
                                modifier = Modifier.width(100.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = !isServerRunning
                            )
                        }
                        
                        // Show warning if RAM exceeds currently available
                        if (masterRamSlider > availableRamMB) {
                            Text(
                                text = stringResource(R.string.dist_ram_exceeds_available, availableRamMB),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Slider(
                            value = masterRamSlider,
                            onValueChange = { 
                                masterRamSlider = it
                                masterRamText = it.toInt().toString()
                                DistributedService.setMasterRam(it.toInt())
                            },
                            valueRange = 0f..totalRamMB.toFloat().coerceAtLeast(1f),
                            steps = (totalRamMB / 512).coerceAtLeast(1),
                            enabled = !isServerRunning
                        )
                        
                        // Offload All button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = masterRamSlider.toInt() == 0,
                                onClick = {
                                    if (masterRamSlider.toInt() == 0) {
                                        // Toggle back to a reasonable default
                                        val defaultRam = (totalRamMB / 2).coerceAtLeast(1024)
                                        masterRamSlider = defaultRam.toFloat()
                                        masterRamText = defaultRam.toString()
                                        DistributedService.setMasterRam(defaultRam)
                                    } else {
                                        masterRamSlider = 0f
                                        masterRamText = "0"
                                        DistributedService.setMasterRam(0)
                                    }
                                },
                                label = { Text(stringResource(R.string.dist_offload_all)) },
                                leadingIcon = if (masterRamSlider.toInt() == 0) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else null,
                                enabled = !isServerRunning
                            )
                        }
                        
                        if (masterRamSlider.toInt() == 0) {
                            Text(
                                text = stringResource(R.string.dist_offload_all_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.dist_device_total_ram, totalRamMB),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.dist_device_free_ram, availableRamMB),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                DistributedCollapsibleCard(
                    title = stringResource(R.string.dist_fit_title),
                    description = stringResource(R.string.dist_fit_desc),
                    expanded = fitCardExpanded,
                    onExpandedChange = { fitCardExpanded = it }
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.dist_fit_enable), modifier = Modifier.weight(1f))
                        Switch(
                            checked = fitEnabled,
                            onCheckedChange = { DistributedService.setMasterFitEnabled(it) },
                            enabled = !isServerRunning
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (deviceSlots.isEmpty()) {
                        OutlinedTextField(
                            value = fitTargetMiB,
                            onValueChange = { DistributedService.setMasterFitTargetMiB(it.filter { ch -> ch.isDigit() || ch == ',' }) },
                            label = { Text(stringResource(R.string.dist_fit_target)) },
                            placeholder = { Text(stringResource(R.string.dist_fit_target_hint)) },
                            supportingText = { Text(stringResource(R.string.dist_fit_target_hint)) },
                            isError = fitValidationError != null,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    } else {
                        val rawTargets = fitTargetMiB.split(',').map(String::trim)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            deviceSlots.forEachIndexed { index, slot ->
                                val displayedTarget = rawTargets.getOrNull(index)
                                    ?: rawTargets.singleOrNull()
                                    ?: ""
                                OutlinedTextField(
                                    value = displayedTarget,
                                    onValueChange = { newValue ->
                                        val sanitized = newValue.filter(Char::isDigit)
                                        val values = if (rawTargets.size == deviceSlots.size) {
                                            rawTargets.toMutableList()
                                        } else {
                                            MutableList(deviceSlots.size) { rawTargets.singleOrNull().orEmpty() }
                                        }
                                        values[index] = sanitized
                                        DistributedService.setMasterFitTargetMiB(values.joinToString(","))
                                    },
                                    label = { Text("${stringResource(R.string.dist_fit_target)} · $slot") },
                                    supportingText = { Text(stringResource(R.string.dist_fit_target_hint)) },
                                    isError = fitValidationError != null,
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = nglArgument,
                        onValueChange = { DistributedService.setMasterNglArgument(it) },
                        label = { Text(stringResource(R.string.dist_ngl_argument)) },
                        supportingText = { Text(stringResource(R.string.dist_ngl_argument_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isServerRunning
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.dist_fit_device_order,
                            deviceSlots.ifEmpty { listOf("RPC0…") }.joinToString(", ")
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    fitValidationError?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            item {
                DistributedCollapsibleCard(
                    title = stringResource(R.string.dist_step_speculative),
                    description = stringResource(R.string.dist_speculative_desc),
                    expanded = placementCardExpanded,
                    onExpandedChange = { placementCardExpanded = it }
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.dist_speculative_enable), fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(R.string.dist_speculative_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = speculativeEnabled,
                            onCheckedChange = DistributedService::setMasterSpeculativeEnabled,
                            enabled = !isServerRunning
                        )
                    }
                    if (speculativeEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val modeLabel = when (speculativeMode) {
                        LlamaSpeculativeMode.DRAFT_MTP -> stringResource(R.string.dist_speculative_mode_mtp)
                        LlamaSpeculativeMode.DRAFT_DFLASH -> stringResource(R.string.dist_speculative_mode_dflash)
                        LlamaSpeculativeMode.DRAFT_DSPARK -> stringResource(R.string.dist_speculative_mode_dspark)
                        LlamaSpeculativeMode.DRAFT_SIMPLE -> stringResource(R.string.dist_speculative_mode_simple)
                        LlamaSpeculativeMode.NGRAM_MOD -> stringResource(R.string.dist_speculative_mode_ngram_mod)
                        LlamaSpeculativeMode.NGRAM_SIMPLE -> stringResource(R.string.dist_speculative_mode_ngram_simple)
                        LlamaSpeculativeMode.NGRAM_MAP_K -> stringResource(R.string.dist_speculative_mode_ngram_map_k)
                        LlamaSpeculativeMode.NGRAM_MAP_K4V -> stringResource(R.string.dist_speculative_mode_ngram_map_k4v)
                        LlamaSpeculativeMode.NGRAM_CACHE -> stringResource(R.string.dist_speculative_mode_ngram_cache)
                    }
                    ExposedDropdownMenuBox(
                        expanded = speculativeModeMenuExpanded,
                        onExpandedChange = { speculativeModeMenuExpanded = !speculativeModeMenuExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = modeLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.dist_speculative_mode_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(speculativeModeMenuExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = speculativeModeMenuExpanded,
                            onDismissRequest = { speculativeModeMenuExpanded = false }
                        ) {
                            listOf(
                                LlamaSpeculativeMode.DRAFT_SIMPLE,
                                LlamaSpeculativeMode.DRAFT_MTP,
                                LlamaSpeculativeMode.DRAFT_DFLASH,
                                LlamaSpeculativeMode.DRAFT_DSPARK,
                                LlamaSpeculativeMode.NGRAM_MOD,
                                LlamaSpeculativeMode.NGRAM_SIMPLE,
                                LlamaSpeculativeMode.NGRAM_MAP_K,
                                LlamaSpeculativeMode.NGRAM_MAP_K4V,
                                LlamaSpeculativeMode.NGRAM_CACHE
                            ).forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (mode) {
                                                LlamaSpeculativeMode.DRAFT_MTP -> stringResource(R.string.dist_speculative_mode_mtp)
                                                LlamaSpeculativeMode.DRAFT_DFLASH -> stringResource(R.string.dist_speculative_mode_dflash)
                                                LlamaSpeculativeMode.DRAFT_DSPARK -> stringResource(R.string.dist_speculative_mode_dspark)
                                                LlamaSpeculativeMode.DRAFT_SIMPLE -> stringResource(R.string.dist_speculative_mode_simple)
                                                LlamaSpeculativeMode.NGRAM_MOD -> stringResource(R.string.dist_speculative_mode_ngram_mod)
                                                LlamaSpeculativeMode.NGRAM_SIMPLE -> stringResource(R.string.dist_speculative_mode_ngram_simple)
                                                LlamaSpeculativeMode.NGRAM_MAP_K -> stringResource(R.string.dist_speculative_mode_ngram_map_k)
                                                LlamaSpeculativeMode.NGRAM_MAP_K4V -> stringResource(R.string.dist_speculative_mode_ngram_map_k4v)
                                                LlamaSpeculativeMode.NGRAM_CACHE -> stringResource(R.string.dist_speculative_mode_ngram_cache)
                                            }
                                        )
                                    },
                                    onClick = {
                                        DistributedService.setMasterSpeculativeMode(mode)
                                        speculativeModeMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    ExposedDropdownMenuBox(
                        expanded = draftDeviceMenuExpanded,
                        onExpandedChange = { draftDeviceMenuExpanded = !draftDeviceMenuExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val draftDeviceLabel = when (draftDeviceMode) {
                            SettingsRepository.LLAMA_DRAFT_DEVICE_ACCELERATOR -> stringResource(R.string.llm_draft_device_accelerator)
                            SettingsRepository.LLAMA_DRAFT_DEVICE_CPU -> stringResource(R.string.llm_draft_device_cpu)
                            else -> stringResource(R.string.llm_backend_mode_auto)
                        }
                        OutlinedTextField(
                            value = draftDeviceLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.llm_draft_device_mode)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(draftDeviceMenuExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            enabled = speculativePlacement == DistributedSpeculativePlacement.LOCAL ||
                                speculativePlacement == DistributedSpeculativePlacement.MASTER_DEDICATED,
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = draftDeviceMenuExpanded &&
                                (speculativePlacement == DistributedSpeculativePlacement.LOCAL ||
                                    speculativePlacement == DistributedSpeculativePlacement.MASTER_DEDICATED),
                            onDismissRequest = { draftDeviceMenuExpanded = false }
                        ) {
                            listOf(
                                SettingsRepository.LLAMA_DRAFT_DEVICE_AUTO to stringResource(R.string.llm_backend_mode_auto),
                                SettingsRepository.LLAMA_DRAFT_DEVICE_ACCELERATOR to stringResource(R.string.llm_draft_device_accelerator),
                                SettingsRepository.LLAMA_DRAFT_DEVICE_CPU to stringResource(R.string.llm_draft_device_cpu)
                            ).forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        DistributedService.setMasterDraftDeviceMode(value)
                                        draftDeviceMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        text = if (speculativePlacement == DistributedSpeculativePlacement.LOCAL ||
                            speculativePlacement == DistributedSpeculativePlacement.MASTER_DEDICATED
                        ) {
                            stringResource(R.string.llm_draft_device_hint)
                        } else {
                            stringResource(R.string.dist_speculative_worker_device_hint)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.dist_speculative_placement), fontWeight = FontWeight.Medium)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            DistributedSpeculativePlacement.LOCAL to stringResource(R.string.dist_speculative_placement_local),
                            DistributedSpeculativePlacement.MASTER_DEDICATED to stringResource(R.string.dist_speculative_placement_master_dedicated),
                            DistributedSpeculativePlacement.WORKER_SHARED to stringResource(R.string.dist_speculative_placement_shared),
                            DistributedSpeculativePlacement.WORKER_DEDICATED to stringResource(R.string.dist_speculative_placement_dedicated)
                        ).filter { (placement, _) ->
                            placement != DistributedSpeculativePlacement.MASTER_DEDICATED ||
                                speculativeMode == LlamaSpeculativeMode.DRAFT_MTP
                        }.forEach { (placement, label) ->
                            FilterChip(
                                selected = speculativePlacement == placement,
                                onClick = {
                                    DistributedService.setMasterSpeculativePlacement(placement)
                                    if (placement == DistributedSpeculativePlacement.LOCAL) {
                                        DistributedService.setMasterSpeculativeWorkerIndex(null)
                                    }
                                },
                                label = { Text(label) },
                                enabled = placement == DistributedSpeculativePlacement.LOCAL ||
                                    placement == DistributedSpeculativePlacement.MASTER_DEDICATED ||
                                    deviceSlots.isNotEmpty()
                            )
                        }
                    }
                    if (speculativePlacement != DistributedSpeculativePlacement.LOCAL &&
                        speculativePlacement != DistributedSpeculativePlacement.MASTER_DEDICATED
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (speculativePlacement == DistributedSpeculativePlacement.WORKER_SHARED)
                                stringResource(R.string.dist_draft_choose_workers)
                            else stringResource(R.string.dist_choose_resident_device),
                            style = MaterialTheme.typography.labelLarge
                        )
                        enabledWorkers.forEachIndexed { index, worker ->
                            val selected = worker.id in selectedDraftWorkerIds ||
                                (selectedDraftWorkerIds.isEmpty() && speculativeWorkerIndex == index)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedDraftWorkerIds = if (speculativePlacement == DistributedSpeculativePlacement.WORKER_DEDICATED) {
                                        listOf(worker.id)
                                    } else if (selected) {
                                        selectedDraftWorkerIds - worker.id
                                    } else {
                                        selectedDraftWorkerIds + worker.id
                                    }
                                    DistributedService.setMasterSpeculativeWorkerIndex(
                                        enabledWorkers.indexOfFirst { it.id == selectedDraftWorkerIds.firstOrNull() }
                                            .takeIf { it >= 0 }
                                    )
                                },
                                label = { Text(worker.deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = draftGpuLayers,
                            onValueChange = { DistributedService.setMasterDraftGpuLayers(it) },
                            label = { Text(stringResource(R.string.dist_speculative_gpu_layers)) },
                            supportingText = { Text(stringResource(R.string.dist_speculative_gpu_layers_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (speculativeMode.requiresDraftModel ||
                        (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP && mtpUseDraftModel)
                    ) {
                        Text(stringResource(R.string.dist_speculative_draft_model), fontWeight = FontWeight.Medium)
                        OutlinedButton(
                            onClick = { showDraftModelPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                draftModel?.filename ?: stringResource(R.string.dist_speculative_select_draft),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (draftModel != null) {
                            TextButton(onClick = { DistributedService.setMasterDraftModel(null) }) {
                                Text(stringResource(R.string.dist_speculative_clear_draft))
                            }
                        }
                    }
                    if (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.general_mtp_use_draft_model_title))
                                Text(
                                    stringResource(R.string.general_mtp_use_draft_model_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = mtpUseDraftModel,
                                onCheckedChange = DistributedService::setMasterMtpUseDraftModel
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (speculativeMode) {
                            LlamaSpeculativeMode.DRAFT_SIMPLE,
                            LlamaSpeculativeMode.DRAFT_DFLASH,
                            LlamaSpeculativeMode.DRAFT_DSPARK -> {
                                DraftIntTextField(
                                    value = draftMax,
                                    onValueChange = DistributedService::setMasterDraftMax,
                                    valueRange = 1..64,
                                    label = { Text(stringResource(if (speculativeMode == LlamaSpeculativeMode.DRAFT_SIMPLE) R.string.dist_speculative_draft_max else R.string.dist_speculative_block_size)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (speculativeMode == LlamaSpeculativeMode.DRAFT_SIMPLE) {
                                    DraftIntTextField(value = draftMin, onValueChange = DistributedService::setMasterDraftMin, valueRange = 0..16, label = { Text(stringResource(R.string.dist_speculative_draft_min)) }, modifier = Modifier.fillMaxWidth())
                                    DraftFloatTextField(value = draftPMin, onValueChange = DistributedService::setMasterDraftPMin, valueRange = 0f..1f, label = { Text(stringResource(R.string.dist_speculative_draft_p_min)) }, modifier = Modifier.fillMaxWidth())
                                }
                                DraftIntTextField(value = draftThreads, onValueChange = DistributedService::setMasterDraftThreads, valueRange = 1..16, label = { Text(stringResource(R.string.dist_speculative_draft_threads)) }, modifier = Modifier.fillMaxWidth())
                                DraftIntTextField(value = draftThreadsBatch, onValueChange = DistributedService::setMasterDraftThreadsBatch, valueRange = 1..16, label = { Text(stringResource(R.string.dist_speculative_draft_threads_batch)) }, modifier = Modifier.fillMaxWidth())
                            }
                            LlamaSpeculativeMode.DRAFT_MTP -> {
                                DraftIntTextField(value = mtpDraftMax, onValueChange = DistributedService::setMasterMtpDraftMax, valueRange = 1..16, label = { Text(stringResource(R.string.dist_speculative_mtp_block_size)) }, modifier = Modifier.fillMaxWidth())
                                DraftIntTextField(value = mtpDraftMin, onValueChange = DistributedService::setMasterMtpDraftMin, valueRange = 0..64, label = { Text(stringResource(R.string.dist_speculative_draft_min)) }, modifier = Modifier.fillMaxWidth())
                                DraftFloatTextField(value = mtpDraftPMin, onValueChange = DistributedService::setMasterMtpDraftPMin, valueRange = 0f..1f, label = { Text(stringResource(R.string.dist_speculative_draft_p_min)) }, modifier = Modifier.fillMaxWidth())
                                if (mtpUseDraftModel) {
                                    DraftIntTextField(value = draftThreads, onValueChange = DistributedService::setMasterDraftThreads, valueRange = 1..16, label = { Text(stringResource(R.string.dist_speculative_draft_threads)) }, modifier = Modifier.fillMaxWidth())
                                    DraftIntTextField(value = draftThreadsBatch, onValueChange = DistributedService::setMasterDraftThreadsBatch, valueRange = 1..16, label = { Text(stringResource(R.string.dist_speculative_draft_threads_batch)) }, modifier = Modifier.fillMaxWidth())
                                }
                            }
                            LlamaSpeculativeMode.NGRAM_MOD -> {
                                DraftIntTextField(value = ngramModNMatch, onValueChange = DistributedService::setMasterNgramModNMatch, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_mod_match)) }, modifier = Modifier.fillMaxWidth())
                                DraftIntTextField(value = ngramModNMin, onValueChange = DistributedService::setMasterNgramModNMin, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_mod_min)) }, modifier = Modifier.fillMaxWidth())
                                DraftIntTextField(value = ngramModNMax, onValueChange = DistributedService::setMasterNgramModNMax, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_mod_max)) }, modifier = Modifier.fillMaxWidth())
                            }
                            LlamaSpeculativeMode.NGRAM_SIMPLE -> {
                                DraftIntTextField(value = ngramSimpleSizeN, onValueChange = DistributedService::setMasterNgramSimpleSizeN, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_simple_size_n)) }, modifier = Modifier.fillMaxWidth())
                                DraftIntTextField(value = ngramSimpleSizeM, onValueChange = DistributedService::setMasterNgramSimpleSizeM, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_simple_size_m)) }, modifier = Modifier.fillMaxWidth())
                                DraftIntTextField(value = ngramSimpleMinHits, onValueChange = DistributedService::setMasterNgramSimpleMinHits, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_simple_min_hits)) }, modifier = Modifier.fillMaxWidth())
                            }
                            LlamaSpeculativeMode.NGRAM_MAP_K -> {
                                DraftIntTextField(value = ngramMapKSizeN, onValueChange = DistributedService::setMasterNgramMapKSizeN, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_map_k_size_n)) }, modifier = Modifier.fillMaxWidth())
                                DraftIntTextField(value = ngramMapKSizeM, onValueChange = DistributedService::setMasterNgramMapKSizeM, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_map_k_size_m)) }, modifier = Modifier.fillMaxWidth())
                                DraftIntTextField(value = ngramMapKMinHits, onValueChange = DistributedService::setMasterNgramMapKMinHits, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_map_k_min_hits)) }, modifier = Modifier.fillMaxWidth())
                            }
                            LlamaSpeculativeMode.NGRAM_MAP_K4V -> {
                                DraftIntTextField(value = ngramMapK4VSizeN, onValueChange = DistributedService::setMasterNgramMapK4VSizeN, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_map_k4v_size_n)) }, modifier = Modifier.fillMaxWidth())
                                DraftIntTextField(value = ngramMapK4VSizeM, onValueChange = DistributedService::setMasterNgramMapK4VSizeM, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_map_k4v_size_m)) }, modifier = Modifier.fillMaxWidth())
                                DraftIntTextField(value = ngramMapK4VMinHits, onValueChange = DistributedService::setMasterNgramMapK4VMinHits, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_map_k4v_min_hits)) }, modifier = Modifier.fillMaxWidth())
                            }
                            LlamaSpeculativeMode.NGRAM_CACHE -> Text(
                                stringResource(R.string.dist_speculative_ngram_cache_params_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    }
                }
            }

            item(key = "step_mmproj") {
                DistributedPlacementStepCard(
                    title = stringResource(R.string.dist_step_mmproj),
                    description = stringResource(R.string.dist_step_mmproj_desc)
                ) {
                    OutlinedButton(onClick = { showMmprojPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            selectedMmprojPath?.substringAfterLast('/')
                                ?: stringResource(R.string.llm_vision_not_selected),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (selectedMmprojPath != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = mmprojPlacementName == MmprojPlacement.MASTER_ACCELERATOR.name,
                                onClick = { mmprojPlacementName = MmprojPlacement.MASTER_ACCELERATOR.name },
                                label = { Text(stringResource(R.string.llm_draft_device_accelerator)) }
                            )
                            FilterChip(
                                selected = mmprojPlacementName == MmprojPlacement.MASTER_CPU.name,
                                onClick = { mmprojPlacementName = MmprojPlacement.MASTER_CPU.name },
                                label = { Text(stringResource(R.string.llm_draft_device_cpu)) }
                            )
                        }
                        Text(
                            stringResource(R.string.dist_mmproj_rpc_unsupported),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Model Settings
            item {
                DistributedCollapsibleCard(
                    title = stringResource(R.string.dist_step_runtime),
                    description = stringResource(R.string.dist_model_settings_desc),
                    expanded = modelSettingsExpanded,
                    onExpandedChange = { modelSettingsExpanded = it }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.dist_model_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Context Size with text input
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                             Text(stringResource(R.string.dist_context_size), style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = contextSizeText,
                                onValueChange = { text ->
                                    DistributedService.setMasterContextSizeText(text)
                                    text.toIntOrNull()?.let { value ->
                                        if (value in 512..131072) {
                                            DistributedService.setMasterContextSize(value)
                                        }
                                    }
                                },
                                modifier = Modifier.width(120.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                 suffix = { Text(stringResource(R.string.dist_tokens), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        Slider(
                            value = contextSize.toFloat(),
                            onValueChange = { 
                                DistributedService.setMasterContextSize(it.toInt())
                                DistributedService.setMasterContextSizeText(it.toInt().toString())
                            },
                            valueRange = 512f..16384f,
                            steps = 14
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Temperature
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                             Text(stringResource(R.string.dist_temperature), style = MaterialTheme.typography.bodyMedium)
                            Text("%.1f".format(temperature), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = temperature,
                            onValueChange = { DistributedService.setMasterTemperature(it) },
                            valueRange = 0f..2f,
                            steps = 19
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Threads
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                             Text(stringResource(R.string.dist_threads), style = MaterialTheme.typography.bodyMedium)
                            Text("$threads", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = threads.toFloat(),
                            onValueChange = { DistributedService.setMasterThreads(it.toInt()) },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Batch Size
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.dist_batch_size), style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = batchSizeText,
                                onValueChange = { newText ->
                                    DistributedService.setMasterBatchSizeText(newText)
                                    newText.toIntOrNull()?.let { v -> if (v in 1..4096) DistributedService.setMasterBatchSize(v) }
                                },
                                modifier = Modifier.width(90.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Slider(
                            value = batchSize.toFloat(),
                            onValueChange = { 
                                DistributedService.setMasterBatchSize(it.toInt())
                                DistributedService.setMasterBatchSizeText(it.toInt().toString())
                            },
                            valueRange = 32f..4096f,
                            steps = 0
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Network Visibility
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                 Text(stringResource(R.string.dist_network_visibility), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                     text = if (enableNetworkAccess) stringResource(R.string.dist_host_public) else stringResource(R.string.dist_host_local),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enableNetworkAccess,
                                onCheckedChange = { enableNetworkAccess = it }
                            )
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        
                        // KV Cache Settings
                        Text(
                            text = stringResource(R.string.dist_kv_cache_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                 Text(stringResource(R.string.dist_enable_kv_cache), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                     text = stringResource(R.string.dist_kv_cache_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = kvCacheEnabled,
                                onCheckedChange = { DistributedService.setMasterKvCacheEnabled(it) }
                            )
                        }

                        AnimatedVisibility(visible = kvCacheEnabled) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    stringResource(R.string.dist_kv_placement_title),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                DistributedKvChoice(
                                    selected = kvPlacementModeName ==
                                        DistributedKvPlacementMode.DISTRIBUTED_WITH_LAYERS.name,
                                    onClick = {
                                        kvPlacementModeName =
                                            DistributedKvPlacementMode.DISTRIBUTED_WITH_LAYERS.name
                                    },
                                    title = stringResource(R.string.dist_kv_placement_distributed),
                                    description = stringResource(R.string.dist_kv_placement_distributed_desc)
                                )
                                DistributedKvChoice(
                                    selected = kvPlacementModeName == DistributedKvPlacementMode.MASTER_CPU.name,
                                    onClick = {
                                        kvPlacementModeName = DistributedKvPlacementMode.MASTER_CPU.name
                                    },
                                    title = stringResource(R.string.dist_kv_placement_cpu),
                                    description = stringResource(R.string.dist_kv_placement_cpu_desc)
                                )
                                DistributedKvChoice(
                                    selected = kvPlacementModeName ==
                                        DistributedKvPlacementMode.ACCELERATOR_DEVICE.name,
                                    onClick = {
                                        kvPlacementModeName =
                                            DistributedKvPlacementMode.ACCELERATOR_DEVICE.name
                                    },
                                    title = stringResource(R.string.dist_kv_placement_device),
                                    description = stringResource(R.string.dist_kv_placement_device_desc)
                                )

                                if (kvPlacementModeName ==
                                    DistributedKvPlacementMode.ACCELERATOR_DEVICE.name
                                ) {
                                    val eligibleKvWorkers = when (mainPlacementMode) {
                                        MainModelPlacementMode.RESIDENT -> enabledWorkers.filter {
                                            it.id == mainResidentWorkerId
                                        }
                                        MainModelPlacementMode.DISTRIBUTED -> enabledWorkers.filter {
                                            it.id in selectedMainWorkerIds
                                        }
                                    }
                                    Text(
                                        stringResource(R.string.dist_kv_row_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                    if (eligibleKvWorkers.isNotEmpty()) {
                                        Text(
                                            stringResource(R.string.dist_kv_host_title),
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                    eligibleKvWorkers.forEach { worker ->
                                        val key = "worker:${worker.id}"
                                        DistributedKvChoice(
                                            selected = kvHostKey == key,
                                            onClick = { kvHostKey = key },
                                            title = worker.deviceName,
                                            description = "${worker.ip}:${worker.port}"
                                        )
                                    }
                                    if (eligibleKvWorkers.isEmpty()) {
                                        Text(
                                            stringResource(R.string.dist_kv_no_accelerator),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                HorizontalDivider()

                                DistributedKvTypeSelector(
                                    title = stringResource(R.string.dist_cache_type_k),
                                    selected = kvCacheTypeK,
                                    onSelected = DistributedService::setMasterKvCacheTypeK
                                )
                                DistributedKvTypeSelector(
                                    title = stringResource(R.string.dist_cache_type_v),
                                    selected = kvCacheTypeV,
                                    onSelected = DistributedService::setMasterKvCacheTypeV
                                )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Cache Reuse Slider
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.kv_cache_reuse), fontWeight = FontWeight.Medium)
                                Text(
                                    if (kvCacheReuse == 0) stringResource(R.string.llm_kv_cache_disabled) else "$kvCacheReuse",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = kvCacheReuse.toFloat(),
                                onValueChange = { DistributedService.setMasterKvCacheReuse(it.toInt()) },
                                valueRange = 0f..512f,
                                steps = 7
                            )
                            }
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        
                        // Advanced Settings
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { masterShowAdvancedSettings = !masterShowAdvancedSettings },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.dist_advanced_settings_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                if (masterShowAdvancedSettings) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                        
                        if (masterShowAdvancedSettings) {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Flash Attention Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.dist_flash_attention), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = stringResource(R.string.dist_flash_attention_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = masterFlashAttention,
                                    onCheckedChange = { DistributedService.setMasterFlashAttention(it) }
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = masterParallelText,
                                    onValueChange = { text ->
                                        DistributedService.setMasterParallelText(text)
                                        DistributedService.setMasterParallel(text.toIntOrNull())
                                    },
                                    label = { Text(stringResource(R.string.dist_advanced_parallel)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    placeholder = { Text("1") }
                                )
                                OutlinedTextField(
                                    value = masterCacheRamText,
                                    onValueChange = { text ->
                                        DistributedService.setMasterCacheRamText(text)
                                        DistributedService.setMasterCacheRam(text.toIntOrNull())
                                    },
                                    label = { Text(stringResource(R.string.dist_advanced_cache_ram)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    placeholder = { Text("0") }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = masterCommandTemplate,
                                onValueChange = { DistributedService.setMasterCommandTemplate(it) },
                                label = { Text(stringResource(R.string.command_template_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                minLines = 3,
                                maxLines = 6,
                                placeholder = { Text(stringResource(R.string.command_template_placeholder)) },
                                supportingText = {
                                    Text(stringResource(R.string.command_template_placeholders))
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = masterCustomFlags,
                                onValueChange = { DistributedService.setMasterCustomFlags(it) },
                                label = { Text(stringResource(R.string.dist_advanced_custom_flags)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                placeholder = { Text("--load-mode mlock") }
                            )
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        
                        // Speculative Decoding Settings
                        // Kept temporarily for saved-command compatibility while the visible
                        // controls live in the dedicated speculative card above.
                        if (false) {
                        Text(
                            text = stringResource(R.string.dist_speculative_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.dist_speculative_enable), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = stringResource(R.string.dist_speculative_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = speculativeEnabled,
                                onCheckedChange = { DistributedService.setMasterSpeculativeEnabled(it) }
                            )
                        }
                        
                        if (speculativeEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))

                            if (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.general_mtp_use_draft_model_title), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            stringResource(R.string.general_mtp_use_draft_model_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = mtpUseDraftModel,
                                        onCheckedChange = DistributedService::setMasterMtpUseDraftModel
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            // Draft Model Picker
                            if (speculativeMode != LlamaSpeculativeMode.DRAFT_MTP || mtpUseDraftModel) {
                                OutlinedCard(
                                    onClick = { showDraftModelPicker = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = draftModel?.filename ?: stringResource(R.string.dist_speculative_select_draft),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = if (draftModel != null) {
                                                    formatFileSize(draftModel!!.sizeBytes)
                                                } else {
                                                    stringResource(R.string.dist_speculative_no_draft)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = stringResource(R.string.dist_speculative_draft_model)
                                        )
                                    }
                                }

                                // RAM Warning
                                if (draftModel != null) {
                                    val draftSizeMB = (draftModel!!.sizeBytes / (1024 * 1024)).toInt()
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.dist_speculative_ram_warning, draftSizeMB),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Draft P-Min (only for draft model mode)
                            if (draftModel != null && speculativeMode != LlamaSpeculativeMode.DRAFT_MTP) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.dist_speculative_draft_p_min), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = stringResource(R.string.dist_speculative_draft_p_min_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedTextField(
                                        value = draftPMinText,
                                        onValueChange = { text ->
                                            DistributedService.setMasterDraftPMinText(text)
                                            text.toFloatOrNull()?.let { value ->
                                                if (value in 0f..1f) {
                                                    DistributedService.setMasterDraftPMin(value)
                                                }
                                            }
                                        },
                                        modifier = Modifier.width(90.dp),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Slider(
                                    value = draftPMin,
                                    onValueChange = {
                                        DistributedService.setMasterDraftPMin(it)
                                        DistributedService.setMasterDraftPMinText("%.2f".format(it))
                                    },
                                    valueRange = 0f..1f,
                                    steps = 19
                                )
                            }
                        }
                        
                        
                        // Shared Draft parameters (shown when either mode is active)
                        if (speculativeEnabled && speculativeMode != LlamaSpeculativeMode.DRAFT_MTP) {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Draft Max
                            val draftMaxLabel = if (
                                speculativeMode == LlamaSpeculativeMode.DRAFT_DFLASH ||
                                speculativeMode == LlamaSpeculativeMode.DRAFT_DSPARK
                            ) R.string.dist_speculative_block_size else R.string.dist_speculative_draft_max
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(draftMaxLabel), style = MaterialTheme.typography.bodyMedium)
                                OutlinedTextField(
                                    value = draftMaxText,
                                    onValueChange = { text ->
                                        DistributedService.setMasterDraftMaxText(text)
                                        text.toIntOrNull()?.let { value ->
                                            if (value in 1..64) {
                                                DistributedService.setMasterDraftMax(value)
                                            }
                                        }
                                    },
                                    modifier = Modifier.width(90.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Slider(
                                value = draftMax.toFloat(),
                                onValueChange = {
                                    DistributedService.setMasterDraftMax(it.toInt())
                                    DistributedService.setMasterDraftMaxText(it.toInt().toString())
                                },
                                valueRange = 1f..64f,
                                steps = 62
                            )
                            if (
                                speculativeMode == LlamaSpeculativeMode.DRAFT_DFLASH ||
                                speculativeMode == LlamaSpeculativeMode.DRAFT_DSPARK
                            ) {
                                Text(
                                    stringResource(R.string.dist_speculative_block_size_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Draft Min
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.dist_speculative_draft_min), style = MaterialTheme.typography.bodyMedium)
                                OutlinedTextField(
                                    value = draftMinText,
                                    onValueChange = { text ->
                                        DistributedService.setMasterDraftMinText(text)
                                        text.toIntOrNull()?.let { value ->
                                            if (value in 0..16) {
                                                DistributedService.setMasterDraftMin(value)
                                            }
                                        }
                                    },
                                    modifier = Modifier.width(90.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Slider(
                                value = draftMin.toFloat(),
                                onValueChange = {
                                    DistributedService.setMasterDraftMin(it.toInt())
                                    DistributedService.setMasterDraftMinText(it.toInt().toString())
                                },
                                valueRange = 0f..16f,
                                steps = 15
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.dist_speculative_draft_threads), style = MaterialTheme.typography.bodyMedium)
                                OutlinedTextField(
                                    value = draftThreadsText,
                                    onValueChange = { text ->
                                        DistributedService.setMasterDraftThreadsText(text)
                                        text.toIntOrNull()?.let { value ->
                                            if (value in 1..16) {
                                                DistributedService.setMasterDraftThreads(value)
                                            }
                                        }
                                    },
                                    modifier = Modifier.width(90.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Slider(
                                value = draftThreads.toFloat(),
                                onValueChange = {
                                    DistributedService.setMasterDraftThreads(it.toInt())
                                    DistributedService.setMasterDraftThreadsText(it.toInt().toString())
                                },
                                valueRange = 1f..16f,
                                steps = 14
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.dist_speculative_draft_threads_batch), style = MaterialTheme.typography.bodyMedium)
                                OutlinedTextField(
                                    value = draftThreadsBatchText,
                                    onValueChange = { text ->
                                        DistributedService.setMasterDraftThreadsBatchText(text)
                                        text.toIntOrNull()?.let { value ->
                                            if (value in 1..16) {
                                                DistributedService.setMasterDraftThreadsBatch(value)
                                            }
                                        }
                                    },
                                    modifier = Modifier.width(90.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Slider(
                                value = draftThreadsBatch.toFloat(),
                                onValueChange = {
                                    DistributedService.setMasterDraftThreadsBatch(it.toInt())
                                    DistributedService.setMasterDraftThreadsBatchText(it.toInt().toString())
                                },
                                valueRange = 1f..16f,
                                steps = 14
                            )
                        }

                        if (speculativeEnabled && speculativeMode == LlamaSpeculativeMode.DRAFT_MTP) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.general_mtp_use_draft_model_title), fontWeight = FontWeight.Medium)
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                DraftIntTextField(
                                    value = mtpDraftMax,
                                    onValueChange = DistributedService::setMasterMtpDraftMax,
                                    valueRange = 1..16,
                                    label = { Text(stringResource(R.string.dist_speculative_mtp_block_size)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                DraftIntTextField(
                                    value = mtpDraftMin,
                                    onValueChange = DistributedService::setMasterMtpDraftMin,
                                    valueRange = 0..64,
                                    label = { Text(stringResource(R.string.dist_speculative_draft_min)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                DraftFloatTextField(
                                    value = mtpDraftPMin,
                                    onValueChange = DistributedService::setMasterMtpDraftPMin,
                                    valueRange = 0f..1f,
                                    label = { Text(stringResource(R.string.dist_speculative_draft_p_min)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (mtpUseDraftModel) {
                                    DraftIntTextField(
                                        value = draftThreads,
                                        onValueChange = DistributedService::setMasterDraftThreads,
                                        valueRange = 1..16,
                                        label = { Text(stringResource(R.string.dist_speculative_draft_threads)) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    DraftIntTextField(
                                        value = draftThreadsBatch,
                                        onValueChange = DistributedService::setMasterDraftThreadsBatch,
                                        valueRange = 1..16,
                                        label = { Text(stringResource(R.string.dist_speculative_draft_threads_batch)) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        if (speculativeEnabled) {
                            when (speculativeMode) {
                                LlamaSpeculativeMode.NGRAM_MOD -> {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(stringResource(R.string.dist_speculative_ngram_params), fontWeight = FontWeight.Medium)
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        DraftIntTextField(
                                            value = ngramModNMatch,
                                            onValueChange = DistributedService::setMasterNgramModNMatch,
                                            valueRange = 1..4096,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_mod_match)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        DraftIntTextField(
                                            value = ngramModNMin,
                                            onValueChange = DistributedService::setMasterNgramModNMin,
                                            valueRange = 1..4096,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_mod_min)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        DraftIntTextField(
                                            value = ngramModNMax,
                                            onValueChange = DistributedService::setMasterNgramModNMax,
                                            valueRange = 1..4096,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_mod_max)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                LlamaSpeculativeMode.NGRAM_SIMPLE -> {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(stringResource(R.string.dist_speculative_ngram_params), fontWeight = FontWeight.Medium)
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        DraftIntTextField(
                                            value = ngramSimpleSizeN,
                                            onValueChange = DistributedService::setMasterNgramSimpleSizeN,
                                            valueRange = 1..4096,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_simple_size_n)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        DraftIntTextField(
                                            value = ngramSimpleSizeM,
                                            onValueChange = DistributedService::setMasterNgramSimpleSizeM,
                                            valueRange = 1..4096,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_simple_size_m)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        DraftIntTextField(
                                            value = ngramSimpleMinHits,
                                            onValueChange = DistributedService::setMasterNgramSimpleMinHits,
                                            valueRange = 1..4096,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_simple_min_hits)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                LlamaSpeculativeMode.NGRAM_MAP_K -> {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(stringResource(R.string.dist_speculative_ngram_params), fontWeight = FontWeight.Medium)
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        DraftIntTextField(
                                            value = ngramMapKSizeN,
                                            onValueChange = DistributedService::setMasterNgramMapKSizeN,
                                            valueRange = 1..4096,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_map_k_size_n)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        DraftIntTextField(
                                            value = ngramMapKSizeM,
                                            onValueChange = DistributedService::setMasterNgramMapKSizeM,
                                            valueRange = 1..4096,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_map_k_size_m)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        DraftIntTextField(
                                            value = ngramMapKMinHits,
                                            onValueChange = DistributedService::setMasterNgramMapKMinHits,
                                            valueRange = 1..4096,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_map_k_min_hits)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                LlamaSpeculativeMode.NGRAM_MAP_K4V -> {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(stringResource(R.string.dist_speculative_ngram_params), fontWeight = FontWeight.Medium)
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        DraftIntTextField(
                                            value = ngramMapK4VSizeN,
                                            onValueChange = DistributedService::setMasterNgramMapK4VSizeN,
                                            valueRange = 1..4096,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_map_k4v_size_n)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        DraftIntTextField(
                                            value = ngramMapK4VSizeM,
                                            onValueChange = DistributedService::setMasterNgramMapK4VSizeM,
                                            valueRange = 1..4096,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_map_k4v_size_m)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        DraftIntTextField(
                                            value = ngramMapK4VMinHits,
                                            onValueChange = DistributedService::setMasterNgramMapK4VMinHits,
                                            valueRange = 1..4096,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_map_k4v_min_hits)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        }
                        
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        
                        // Layer Distribution Memory Toggle
                        Text(
                            text = stringResource(R.string.dist_layer_distribution),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val lastDistribution by settingsRepo.lastLayerDistribution.collectAsState()
                        val lastModelPath by settingsRepo.lastDistributionModelPath.collectAsState()
                        var rememberDistribution by remember { mutableStateOf(lastDistribution != null) }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.dist_remember_layer_split), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = stringResource(R.string.dist_layer_split_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = rememberDistribution,
                                onCheckedChange = { 
                                    rememberDistribution = it
                                    if (!it) {
                                        // Clear saved distribution when disabled
                                        settingsRepo.clearLayerDistributionMemory()
                                    }
                                }
                            )
                        }
                        
                        if (rememberDistribution && lastDistribution != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                         text = stringResource(R.string.dist_saved_distribution),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = lastModelPath?.substringAfterLast("/") ?: "Unknown model",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(
                                        onClick = { settingsRepo.clearLayerDistributionMemory() }
                                    ) {
                                         Text(stringResource(R.string.dist_clear_recalculate))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            
            if (newDiscoveredWorkers.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Search, 
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.dist_nearby_workers_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            newDiscoveredWorkers.forEach { service ->
                                val hostName: String = service.serviceName.removePrefix("LlamaWorker-")
                                val ip = service.host.hostAddress
                                
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = hostName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "$ip:${service.port}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            )
                                        }
                                        
                                        FilledTonalIconButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    // Add to DB
                                                    val newWorker = SavedWorker(
                                                        ip = ip,
                                                        port = service.port,
                                                        deviceName = hostName,
                                                        ramMB = 4096, // Default, user can edit
                                                        isEnabled = true
                                                    )
                                                    db.savedWorkerDao().insertWorker(newWorker)
                                                    Toast.makeText(context, resources.getString(R.string.dist_worker_added), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.dist_add_worker_btn))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                         text = stringResource(R.string.dist_workers_count, savedWorkers.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Add Worker Button
                    OutlinedButton(onClick = { showAddWorkerDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.dist_add_worker_btn))
                    }
                }
                
                if (savedWorkers.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "📱",
                                style = MaterialTheme.typography.displaySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                 text = stringResource(R.string.dist_no_workers),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                 text = stringResource(R.string.dist_no_workers_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            
            // Worker List (from database)
            items(savedWorkers) { savedWorker ->
                SavedWorkerCard(
                    savedWorker = savedWorker,
                    onToggle = { enabled ->
                        coroutineScope.launch {
                            db.savedWorkerDao().setWorkerEnabled(savedWorker.id, enabled)
                        }
                    },
                    onEdit = {
                        workerToEdit = savedWorker
                        showEditWorkerDialog = true
                    },
                    onDelete = {
                        coroutineScope.launch {
                            db.savedWorkerDao().deleteWorker(savedWorker)
                        }
                    }
                )
            }
            
            // Total RAM Summary
            item {
                val selectedRamWorkers = when (mainPlacementMode) {
                    MainModelPlacementMode.RESIDENT -> enabledWorkers.filter { it.id == mainResidentWorkerId }
                    MainModelPlacementMode.DISTRIBUTED -> enabledWorkers.filter { it.id in selectedMainWorkerIds }
                }
                val masterContribution = when (mainPlacementMode) {
                    MainModelPlacementMode.RESIDENT -> if (mainResidentWorkerId == MASTER_CPU_PLACEMENT_ID) masterRamSlider.toInt() else 0
                    MainModelPlacementMode.DISTRIBUTED -> if (includeMasterInMain) masterRamSlider.toInt() else 0
                }
                val totalRam = masterContribution + selectedRamWorkers.sumOf { it.ramMB }
                val modelSizeMB = selectedModel?.sizeBytes?.div(1024 * 1024)?.toInt() ?: 0
                val hasEnoughRam = totalRam >= modelSizeMB
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (hasEnoughRam || modelSizeMB == 0)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (hasEnoughRam || modelSizeMB == 0) "✓" else "⚠️",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                 text = stringResource(R.string.dist_total_ram, totalRam),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (modelSizeMB > 0) {
                                Text(
                                    text = stringResource(R.string.dist_model_ram_requirement, modelSizeMB),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            
            // Start Button
            item(key = "step_review_header") {
                Text(
                    stringResource(R.string.dist_step_review),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            item {
                val enabledWorkersList = savedWorkers.filter { it.isEnabled }
                val probeReady = effectiveLayerResult != null
                val canStart = selectedModel != null && enabledWorkersList.isNotEmpty() && probeReady

                if (customCommand != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.dist_custom_command_active)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, Modifier.size(16.dp)) }
                    )
                }
                
                Button(
                    onClick = {
                        if (isServerRunning) {
                            context.startService(Intent(context, DistributedMasterLlamaService::class.java).apply {
                                action = DistributedMasterLlamaService.ACTION_STOP
                            })
                        } else if (selectedModel != null && enabledWorkersList.isNotEmpty()) {
                            isStarting = true
                            coroutineScope.launch {
                                runCatching { resolveCurrentDistributedLaunch() }
                                    .onSuccess { launch ->
                                        val profile = launch.profile
                                        DistributedService.setMasterMode(profile.config.rpcWorkers)
                                        DistributedMasterRuntimeState.prepare(launch)
                                        val intent = Intent(context, DistributedMasterLlamaService::class.java).apply {
                                            action = DistributedMasterLlamaService.ACTION_START
                                            putExtra(
                                                DistributedMasterLlamaService.EXTRA_RESOLVED_LAUNCH,
                                                ResolvedDistributedLaunch.encode(launch)
                                            )
                                        }
                                        context.startForegroundService(intent)
                                        Toast.makeText(
                                            context,
                                            resources.getString(R.string.dist_started_msg, profile.workers.size),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    .onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            error.message ?: resources.getString(R.string.dist_error_start_failed),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                isStarting = false
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = (canStart || isServerRunning) && !isStarting
                ) {
                    if (isStarting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(if (isServerRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                         text = when {
                             isStarting -> stringResource(R.string.dist_starting)
                             isServerRunning -> stringResource(R.string.dist_stop_server)
                             else -> stringResource(R.string.dist_start_btn)
                         },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                // Show Command Button (for debugging/verification)
                if (!isStarting && !isServerRunning) {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val enabledWorkersListPreview = savedWorkers.filter { it.isEnabled }
                    
                    OutlinedButton(
                        onClick = {
                            if (selectedModel != null) {
                                isPreviewing = true
                                coroutineScope.launch {
                                    runCatching { buildCurrentDistributedCommand() }
                                        .onSuccess(DistributedService::setLastCommand)
                                        .onFailure { error ->
                                            Toast.makeText(
                                                context,
                                                error.message ?: resources.getString(R.string.dist_command_preview_failed),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    isPreviewing = false
                                }
                            } else {
                                Toast.makeText(context, resources.getString(R.string.dist_error_no_model), Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = selectedModel != null && enabledWorkersListPreview.isNotEmpty() &&
                            effectiveLayerResult != null && !isPreviewing
                    ) {
                        if (isPreviewing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Info, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dist_show_command))
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Save and Load Command Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showLoadCommandDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.List, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dist_load_command))
                        }
                        
                        OutlinedButton(
                            onClick = { 
                                commandNameToSave = ""
                                showSaveCommandDialog = true 
                            },
                            modifier = Modifier.weight(1f),
                            enabled = selectedModel != null
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dist_save_command))
                        }
                    }
                }
                
                if (!canStart && !isStarting) {
                    Text(
                        text = when {
                             selectedModel == null -> stringResource(R.string.dist_error_no_model)
                             enabledWorkersList.isEmpty() -> stringResource(R.string.dist_error_no_workers)
                             modelProbeState is ModelProbeUiState.Checking -> stringResource(R.string.dist_probe_checking)
                             modelProbeState is ModelProbeUiState.Failed -> stringResource(R.string.dist_probe_required)
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // View Network Status Button
                OutlinedButton(
                    onClick = { navController.navigate(Screen.NetworkVisualization.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                     Text(stringResource(R.string.dist_view_network_status))
                }
            }
        }
    }
    
    // Model Picker Dialog
    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
             title = { Text(stringResource(R.string.dist_select_model)) },
            text = {
                if (llmModels.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("📦", style = MaterialTheme.typography.displayMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                         Text(stringResource(R.string.dist_no_models))
                         Text(
                             stringResource(R.string.dist_no_models_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn {
                        items(llmModels) { model ->
                            Surface(
                                onClick = {
                                    DistributedService.setMasterSelectedModel(model)
                                    showModelPicker = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                color = if (model == selectedModel)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = model == selectedModel,
                                        onClick = {
                                            DistributedService.setMasterSelectedModel(model)
                                            showModelPicker = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.filename,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${formatFileSize(model.sizeBytes)} • ${model.type}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelPicker = false }) {
                     Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    if (showMmprojPicker) {
        AlertDialog(
            onDismissRequest = { showMmprojPicker = false },
            title = { Text(stringResource(R.string.dist_step_mmproj)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    item {
                        Surface(
                            onClick = { selectedMmprojPath = null; showMmprojPicker = false },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.llm_vision_not_selected), modifier = Modifier.padding(16.dp)) }
                    }
                    items(mmprojModels, key = { it.path }) { model ->
                        Surface(
                            onClick = { selectedMmprojPath = model.path; showMmprojPicker = false },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (selectedMmprojPath == model.path) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                model.filename,
                                modifier = Modifier.padding(16.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMmprojPicker = false }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
    
    // Draft Model Picker Dialog (for speculative decoding)
    if (showDraftModelPicker) {
        AlertDialog(
            onDismissRequest = { showDraftModelPicker = false },
            title = {
                Text(
                    stringResource(
                        if (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP) {
                            R.string.models_type_mtp
                        } else {
                            R.string.dist_speculative_draft_model
                        }
                    )
                )
            },
            text = {
                if (draftModels.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("📦", style = MaterialTheme.typography.displayMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.dist_no_models))
                    }
                } else {
                    LazyColumn {
                        items(draftModels) { model ->
                            Surface(
                                onClick = {
                                    DistributedService.setMasterDraftModel(model)
                                    showDraftModelPicker = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                color = if (model == draftModel)
                                    MaterialTheme.colorScheme.tertiaryContainer
                                else
                                    MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = model == draftModel,
                                        onClick = {
                                            DistributedService.setMasterDraftModel(model)
                                            showDraftModelPicker = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.filename,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${formatFileSize(model.sizeBytes)} • ${model.type}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDraftModelPicker = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            dismissButton = {
                if (draftModel != null) {
                    TextButton(onClick = { 
                        DistributedService.setMasterDraftModel(null)
                        showDraftModelPicker = false
                    }) {
                        Text(stringResource(R.string.dist_speculative_clear_draft))
                    }
                }
            }
        )
    }
    
    // Add Worker Dialog - manual IP entry
    if (showAddWorkerDialog) {
        var workerName by remember { mutableStateOf("") }
        var ipAddress by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("50052") }
        var ramMB by remember { mutableStateOf("4096") }
        var layers by remember { mutableStateOf("") }  // blank = auto
        
        AlertDialog(
            onDismissRequest = { showAddWorkerDialog = false },
             title = { Text(stringResource(R.string.dist_add_worker_btn)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                     Text(
                         stringResource(R.string.dist_add_worker_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    OutlinedTextField(
                        value = workerName,
                        onValueChange = { workerName = it },
                         label = { Text(stringResource(R.string.dist_device_name_optional)) },
                         placeholder = { Text(stringResource(R.string.dist_device_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                         label = { Text(stringResource(R.string.dist_ip_address_label)) },
                         placeholder = { Text(stringResource(R.string.dist_ip_address_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                         label = { Text(stringResource(R.string.dist_port_label)) },
                         placeholder = { Text(stringResource(R.string.dist_port_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = ramMB,
                        onValueChange = { ramMB = it },
                         label = { Text(stringResource(R.string.dist_worker_ram_label)) },
                         placeholder = { Text(stringResource(R.string.dist_ram_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = layers,
                        onValueChange = { layers = it },
                         label = { Text(stringResource(R.string.dist_load_proportion)) },
                         placeholder = { Text(stringResource(R.string.dist_load_proportion_hint)) },
                         singleLine = true,
                         modifier = Modifier.fillMaxWidth(),
                         supportingText = { Text(stringResource(R.string.dist_auto_load_desc)) }
                    )
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("💡", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                 stringResource(R.string.dist_add_worker_tip),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val portNum = port.toIntOrNull() ?: 50052
                        val ram = ramMB.toIntOrNull() ?: 4096
                        // Parse proportion: user enters 0-100, we store as 0.0-1.0
                        val proportion = layers.toFloatOrNull()?.let { if (it > 1f) it / 100f else it }  // null if blank/invalid = auto
                        val name = workerName.ifBlank { "Worker ${savedWorkers.size + 1}" }
                        if (ipAddress.isNotBlank()) {
                            // Save to database
                            coroutineScope.launch {
                                db.savedWorkerDao().insertWorker(
                                    SavedWorker(
                                        ip = ipAddress.trim(),
                                        port = portNum,
                                        deviceName = name,
                                        ramMB = ram,
                                        isEnabled = true,
                                        assignedProportion = proportion
                                    )
                                )
                            }
                             Toast.makeText(context, resources.getString(R.string.dist_worker_added_toast, name, ipAddress, portNum.toString()), Toast.LENGTH_SHORT).show()
                        }
                        showAddWorkerDialog = false
                    }
                ) {
                                     Text(stringResource(R.string.dist_add_worker_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddWorkerDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Edit Worker Dialog - capture worker to local val to prevent null issues during composition
    workerToEdit?.let { editingWorker ->
        if (showEditWorkerDialog) {
            key(editingWorker.id) {
                var editIp by remember { mutableStateOf(editingWorker.ip) }
                var editPort by remember { mutableStateOf(editingWorker.port.toString()) }
                var editName by remember { mutableStateOf(editingWorker.deviceName) }
                var editRam by remember { mutableStateOf(editingWorker.ramMB.toString()) }
                // Display as percentage (0-100), store as 0.0-1.0
                var editProportion by remember { mutableStateOf(editingWorker.assignedProportion?.let { "${(it * 100).toInt()}" } ?: "") }
                
                AlertDialog(
                    onDismissRequest = { 
                        showEditWorkerDialog = false
                        workerToEdit = null
                    },
                     title = { Text(stringResource(R.string.dist_edit_worker)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = editName,
                                onValueChange = { editName = it },
                                 label = { Text(stringResource(R.string.dist_device_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            OutlinedTextField(
                                value = editIp,
                                onValueChange = { editIp = it },
                                 label = { Text(stringResource(R.string.dist_ip_address_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            OutlinedTextField(
                                value = editPort,
                                onValueChange = { editPort = it },
                                 label = { Text(stringResource(R.string.dist_port_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            OutlinedTextField(
                                value = editRam,
                                onValueChange = { editRam = it },
                                 label = { Text(stringResource(R.string.dist_worker_ram_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            OutlinedTextField(
                                value = editProportion,
                                onValueChange = { editProportion = it },
                                 label = { Text(stringResource(R.string.dist_load_proportion)) },
                                 placeholder = { Text(stringResource(R.string.dist_load_proportion_hint)) },
                                 singleLine = true,
                                 modifier = Modifier.fillMaxWidth(),
                                 supportingText = { Text(stringResource(R.string.dist_auto_load_desc)) }
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                // Parse proportion: user enters 0-100, we store as 0.0-1.0
                                val proportion = editProportion.toFloatOrNull()?.let { if (it > 1f) it / 100f else it }  // null = auto
                                coroutineScope.launch {
                                    db.savedWorkerDao().updateWorker(
                                        editingWorker.copy(
                                            ip = editIp.trim(),
                                            port = editPort.toIntOrNull() ?: 50052,
                                            deviceName = editName,
                                            ramMB = editRam.toIntOrNull() ?: 4096,
                                            assignedProportion = proportion
                                        )
                                    )
                                }
                                showEditWorkerDialog = false
                                workerToEdit = null
                                 Toast.makeText(context, resources.getString(R.string.dist_worker_updated_toast), Toast.LENGTH_SHORT).show()
                            }
                        ) {
                             Text(resources.getString(R.string.action_save))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { 
                            showEditWorkerDialog = false
                            workerToEdit = null
                        }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }  // End of key() block
        }
    }
    
    // Command Preview Dialog
    if (lastCommand != null) {
        val originalCmd = lastCommand!!
        
        AlertDialog(
            onDismissRequest = { 
                DistributedService.clearLastCommand()
                isEditingCommand = false
            },
            title = {
                Text(
                    if (isEditingCommand) {
                        stringResource(R.string.dist_edit_command)
                    } else {
                        stringResource(R.string.dist_command_preview_title)
                    }
                )
            },
            text = {
                Column {
                    Text(
                        text = if (isEditingCommand) 
                            stringResource(R.string.dist_command_customize_desc)
                            else stringResource(R.string.dist_command_preview_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (isEditingCommand) {
                        OutlinedTextField(
                            value = editedCommand,
                            onValueChange = { editedCommand = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            label = { Text(stringResource(R.string.dist_command_label)) }
                        )
                    } else {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = customCommand ?: originalCmd,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    if (isEditingCommand) {
                        TextButton(
                            onClick = { 
                                runCatching {
                                    require(ProcessController().splitCommandLine(editedCommand).isNotEmpty())
                                    DistributedService.setCustomCommand(editedCommand)
                                }.onSuccess {
                                    isEditingCommand = false
                                }.onFailure {
                                    Toast.makeText(context, resources.getString(R.string.dist_custom_command_invalid), Toast.LENGTH_LONG).show()
                                }
                            }
                        ) {
                            Text(stringResource(R.string.dist_save_command))
                        }
                    } else {
                        TextButton(
                            onClick = { 
                                editedCommand = customCommand ?: originalCmd
                                isEditingCommand = true
                            }
                        ) {
                            Text(stringResource(R.string.dist_edit_command))
                        }
                        
                        TextButton(
                            onClick = {
                                val currentCmd = customCommand ?: originalCmd
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(currentCmd))
                                Toast.makeText(context, resources.getString(R.string.dist_command_copied), Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(stringResource(R.string.action_copy))
                        }
                    }
                }
            },
            dismissButton = {
                if (isEditingCommand) {
                    TextButton(onClick = { isEditingCommand = false }) {
                        Text(stringResource(R.string.dist_discard_command))
                    }
                } else {
                    TextButton(onClick = { 
                        DistributedService.clearLastCommand()
                        isEditingCommand = false
                    }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        )
    }
    
    // Save Command Dialog
    if (showSaveCommandDialog) {
        val enabledWorkersList = savedWorkers.filter { it.isEnabled }
        AlertDialog(
            onDismissRequest = { showSaveCommandDialog = false },
            title = { Text(stringResource(R.string.dist_save_command_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.dist_save_command_desc), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = commandNameToSave,
                        onValueChange = { commandNameToSave = it },
                        label = { Text(stringResource(R.string.dist_command_preset_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (commandNameToSave.isNotBlank() && selectedModel != null) {
                            coroutineScope.launch(Dispatchers.IO) {
                                val host = if (enableNetworkAccess) "0.0.0.0" else "127.0.0.1"
                                val newCommand = SavedCommandEntity(
                                    name = commandNameToSave.trim(),
                                    commandTemplate = masterCommandTemplate,
                                    scope = SavedCommandScopes.MASTER,
                                    modelPath = selectedModel?.path ?: "",
                                    contextSize = contextSize,
                                    batchSize = batchSize,
                                    temperature = temperature,
                                    threads = threads,
                                    host = host,
                                    speculativeEnabled = speculativeEnabled,
                                    speculativeMode = speculativeMode.flagValue,
                                    draftModelPath = draftModel?.path,
                                    draftMax = draftMax,
                                    draftMin = draftMin,
                                    draftPMin = draftPMin,
                                    draftThreads = draftThreads,
                                    draftThreadsBatch = draftThreadsBatch,
                                    parallel = masterParallel,
                                    cacheRam = masterCacheRam,
                                    customFlags = masterCustomFlags,
                                    flashAttention = masterFlashAttention,
                                    kvCacheEnabled = kvCacheEnabled,
                                    kvCacheTypeK = kvCacheTypeK,
                                    kvCacheTypeV = kvCacheTypeV,
                                    kvCacheReuse = kvCacheReuse,
                                    masterRamMB = masterRamSlider.toInt(),
                                    workersListStr = enabledWorkersList.joinToString(",") { "${it.ip}:${it.port}" }
                                )
                                db.savedCommandDao().insertCommand(newCommand)
                            }
                            Toast.makeText(context, resources.getString(R.string.dist_command_saved), Toast.LENGTH_SHORT).show()
                            showSaveCommandDialog = false
                        }
                    },
                    enabled = commandNameToSave.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveCommandDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Load Command Dialog
    if (showLoadCommandDialog) {
        AlertDialog(
            onDismissRequest = { showLoadCommandDialog = false },
            title = { Text(stringResource(R.string.dist_load_command_title)) },
            text = {
                if (savedCommands.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp).fillMaxWidth()
                    ) {
                        Text("📋", style = MaterialTheme.typography.displayMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.dist_no_commands_saved))
                    }
                } else {
                    LazyColumn {
                        items(savedCommands) { cmd ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).clickable {
                                        // Load settings
                                        coroutineScope.launch {
                                            // Model
                                            val model = allModels.find { it.path == cmd.modelPath }
                                            if (model != null) DistributedService.setMasterSelectedModel(model)
                                            
                                            // Base settings
                                            DistributedService.setMasterContextSize(cmd.contextSize)
                                            DistributedService.setMasterContextSizeText(cmd.contextSize.toString())
                                            DistributedService.setMasterBatchSize(cmd.batchSize)
                                            DistributedService.setMasterBatchSizeText(cmd.batchSize.toString())
                                            DistributedService.setMasterTemperature(cmd.temperature)
                                            DistributedService.setMasterThreads(cmd.threads)
                                            enableNetworkAccess = (cmd.host == "0.0.0.0")
                                            
                                            // Speculative
                                            DistributedService.setMasterSpeculativeEnabled(cmd.speculativeEnabled)
                                            val commandSpeculativeMode =
                                                LlamaSpeculativeMode.fromFlagValue(cmd.speculativeMode)
                                            DistributedService.setMasterSpeculativeMode(commandSpeculativeMode)
                                            val commandDraftModels = speculativeDraftModelsFor(
                                                availableModels,
                                                commandSpeculativeMode
                                            )
                                            DistributedService.setMasterDraftModel(
                                                commandDraftModels.firstOrNull { it.path == cmd.draftModelPath }
                                            )
                                            DistributedService.setMasterDraftMax(cmd.draftMax)
                                            DistributedService.setMasterDraftMaxText(cmd.draftMax.toString())
                                            DistributedService.setMasterDraftMin(cmd.draftMin)
                                            DistributedService.setMasterDraftMinText(cmd.draftMin.toString())
                                            DistributedService.setMasterDraftPMin(cmd.draftPMin)
                                            DistributedService.setMasterDraftPMinText(cmd.draftPMin.toString())
                                            DistributedService.setMasterDraftThreads(cmd.draftThreads)
                                            DistributedService.setMasterDraftThreadsText(cmd.draftThreads.toString())
                                            DistributedService.setMasterDraftThreadsBatch(cmd.draftThreadsBatch)
                                            DistributedService.setMasterDraftThreadsBatchText(cmd.draftThreadsBatch.toString())
                                            
                                            // Advanced
                                            DistributedService.setMasterParallel(cmd.parallel)
                                            DistributedService.setMasterParallelText(cmd.parallel?.toString() ?: "")
                                            DistributedService.setMasterCacheRam(cmd.cacheRam)
                                            DistributedService.setMasterCacheRamText(cmd.cacheRam?.toString() ?: "")
                                            DistributedService.setMasterCommandTemplate(cmd.commandTemplate)
                                            DistributedService.setMasterCustomFlags(cmd.customFlags)
                                            DistributedService.setMasterFlashAttention(cmd.flashAttention)
                                            
                                            // KV Cache
                                            DistributedService.setMasterKvCacheEnabled(cmd.kvCacheEnabled)
                                            if (cmd.kvCacheTypeK.isNotEmpty()) {
                                                DistributedService.setMasterKvCacheTypeK(cmd.kvCacheTypeK)
                                            }
                                            if (cmd.kvCacheTypeV.isNotEmpty()) {
                                                DistributedService.setMasterKvCacheTypeV(cmd.kvCacheTypeV)
                                            }
                                            DistributedService.setMasterKvCacheReuse(cmd.kvCacheReuse)
                                            
                                            // Master RAM
                                            DistributedService.setMasterRam(cmd.masterRamMB)
                                            masterRamSlider = cmd.masterRamMB.toFloat()
                                            masterRamText = cmd.masterRamMB.toString()
                                            
                                            // Enable specified workers
                                            val workerKeys = cmd.workersListStr.split(",").filter { it.isNotEmpty() }
                                            savedWorkers.forEach { w ->
                                                val isListed = workerKeys.contains("${w.ip}:${w.port}")
                                                if (w.isEnabled != isListed) {
                                                    db.savedWorkerDao().setWorkerEnabled(w.id, isListed)
                                                }
                                            }
                                        }
                                        Toast.makeText(context, resources.getString(R.string.dist_command_loaded), Toast.LENGTH_SHORT).show()
                                        showLoadCommandDialog = false
                                    }) {
                                        Text(cmd.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(stringResource(R.string.model_filename_label, cmd.modelPath.substringAfterLast("/")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    
                                    IconButton(
                                        onClick = {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                db.savedCommandDao().deleteCommand(cmd)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLoadCommandDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
}

@Composable
private fun WorkerCard(worker: WorkerInfo, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "✅",
                style = MaterialTheme.typography.headlineSmall
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = worker.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${worker.ip}:${worker.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (worker.availableRamMB > 0) {
                    Text(
                        text = "RAM: ${worker.availableRamMB} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                     contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SavedWorkerCard(
    savedWorker: SavedWorker,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (savedWorker.isEnabled) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Enable/Disable Switch
            Switch(
                checked = savedWorker.isEnabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(end = 8.dp)
            )
            
            // Worker info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = savedWorker.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (savedWorker.isEnabled) 
                        MaterialTheme.colorScheme.onSurface
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Text(
                    text = "${savedWorker.ip}:${savedWorker.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (savedWorker.isEnabled)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = "RAM: ${savedWorker.ramMB} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (savedWorker.isEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                // Show proportion if set
                 val proportionText = savedWorker.assignedProportion?.let { stringResource(R.string.dist_worker_load, (it * 100).toInt()) } ?: stringResource(R.string.dist_auto)
                Text(
                    text = "Load: $proportionText",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (savedWorker.isEnabled)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
                )
            }
            
            // Edit Button
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                     contentDescription = stringResource(R.string.action_edit),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            // Delete Button
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                     contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun LayerVisualizationCard(
    masterRamMB: Int,
    workers: List<WorkerInfo>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                 text = stringResource(R.string.dist_load_distribution_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Check if any worker has assigned proportion
            val totalWorkerProportion = workers.mapNotNull { it.assignedProportion }.sum()
            val hasProportionOverride = totalWorkerProportion > 0f
            
            // Calculate master proportion
            val masterProportion = if (hasProportionOverride) {
                (1f - totalWorkerProportion).coerceIn(0.01f, 0.99f)
            } else {
                val totalRam = masterRamMB + workers.sumOf { it.availableRamMB }
                masterRamMB.toFloat() / totalRam
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔵", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                     text = stringResource(R.string.dist_master_load_label, (masterProportion * 100).toInt(), masterRamMB),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            workers.forEachIndexed { index, worker ->
                val workerProportion = if (worker.assignedProportion != null) {
                    worker.assignedProportion
                } else if (!hasProportionOverride) {
                    val totalRam = masterRamMB + workers.sumOf { it.availableRamMB }
                    worker.availableRamMB.toFloat() / totalRam
                } else {
                    0f
                }
                
                val color = when (index % 3) {
                    0 -> "🟢"
                    1 -> "🟡"
                    else -> "🟣"
                }
                
                val proportionLabel = if (worker.assignedProportion != null) {
                     stringResource(R.string.dist_worker_load_set, (workerProportion * 100).toInt())
                 } else {
                     stringResource(R.string.dist_worker_load_auto, (workerProportion * 100).toInt())
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(color, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${worker.deviceName}: $proportionLabel (${worker.availableRamMB}MB)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}


// === Shared HTTP client helper with platform TLS validation ===
private fun createRemoteHttpClient(timeoutSec: Long = 5): okhttp3.OkHttpClient {
    return okhttp3.OkHttpClient.Builder()
        .connectTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteMasterCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    
    // ===== Server-side state =====
    val remoteEnabled by DistributedService.remoteControlEnabled.collectAsState()
    val remotePassword by DistributedService.remoteControlPassword.collectAsState()
    val remotePort by DistributedService.remoteControlPort.collectAsState()
    val remoteWhitelist by DistributedService.remoteControlWhitelist.collectAsState()
    
    // ===== Client-side state =====
    val clientConnected by DistributedService.remoteClientConnected.collectAsState()
    val clientModelsStr by DistributedService.remoteClientModelsStr.collectAsState()
    val clientStatusStr by DistributedService.remoteClientStatusStr.collectAsState()
    val currentModelFile by DistributedService.remoteClientCurrentModel.collectAsState()
    
    // Remote UI settings
    val remoteContextSize by DistributedService.remoteUIContextSize.collectAsState()
    val remoteTemperature by DistributedService.remoteUITemperature.collectAsState()
    val remoteBatchSize by DistributedService.remoteUIBatchSize.collectAsState()
    val remoteKvEnabled by DistributedService.remoteUIKvEnabled.collectAsState()
    val remoteKvK by DistributedService.remoteUIKvTypeK.collectAsState()
    val remoteKvV by DistributedService.remoteUIKvTypeV.collectAsState()
    val remoteKvReuse by DistributedService.remoteUIKvReuse.collectAsState()
    val remoteUIFlashAttention by DistributedService.remoteUIFlashAttention.collectAsState()
    val remoteParallel by DistributedService.remoteUIParallel.collectAsState()
    val remoteCacheRam by DistributedService.remoteUICacheRam.collectAsState()
    val remoteCustomFlags by DistributedService.remoteUICustomFlags.collectAsState()
    val remoteSpecEnabled by DistributedService.remoteUISpecEnabled.collectAsState()
    val remoteDraftModel by DistributedService.remoteUIDraftModel.collectAsState()
    val remoteDraftMin by DistributedService.remoteUIDraftMin.collectAsState()
    val remoteDraftMax by DistributedService.remoteUIDraftMax.collectAsState()
    val remoteDraftPMin by DistributedService.remoteUIDraftPMin.collectAsState()
    
    // Parse models from JSON - server returns {"models": [...]}
    val clientModels = remember(clientModelsStr) {
        if (clientModelsStr == null) emptyList()
        else try {
            val wrapper = JSONObject(clientModelsStr)
            val jsonArray = wrapper.getJSONArray("models")
            val list = mutableListOf<JSONObject>()
            for (i in 0 until jsonArray.length()) list.add(jsonArray.getJSONObject(i))
            list
        } catch (_: Exception) { emptyList() }
    }
    
    // Parse status from JSON
    val clientStatus = remember(clientStatusStr) {
        try { JSONObject(clientStatusStr ?: "{}") } catch (_: Exception) { null }
    }
    
    val selectedRemoteModel by DistributedService.remoteUISelectedModel.collectAsState()
    
    // Client IP and password from service state flows
    val clientIp by DistributedService.remoteClientIp.collectAsState()
    val clientPassword by DistributedService.remoteClientPassword.collectAsState()
    var clientConnecting by remember { mutableStateOf(false) }
    var clientError by remember { mutableStateOf<String?>(null) }
    var clientSwitching by remember { mutableStateOf(false) }
    var clientRestarting by remember { mutableStateOf(false) }
    var clientSpecUpdating by remember { mutableStateOf(false) }
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var showSpeculativeSettings by remember { mutableStateOf(false) }
    var showSwitchConfirm by remember { mutableStateOf<RemoteSwitchParams?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // Direct llamacpp status
    var isLlamaCppRunning by remember { mutableStateOf(false) }
    var llamaCppLoadedModel by remember { mutableStateOf<String?>(null) }
    
    val clientScope = rememberCoroutineScope()
    
    // ===== Auto-polling when connected =====
    var remoteDownloads by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var remoteWorkersList by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var remoteLogsList by remember { mutableStateOf<List<String>>(emptyList()) }
    val remoteLogsState = DistributedService.remoteLogsStr.collectAsState()
    
    // Parse logs
    LaunchedEffect(remoteLogsState.value) {
        try {
            val json = JSONObject(remoteLogsState.value ?: "{}")
            val arr = json.optJSONArray("logs") ?: org.json.JSONArray()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(entry.getLong("timestamp")))
                list.add("[$time] ${entry.getString("message")}")
            }
            remoteLogsList = list
        } catch (_: Exception) {}
    }
    
    // Auto-poll status, downloads, logs every 5s while connected
    LaunchedEffect(clientConnected) {
        if (clientConnected) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                while (true) {
                    kotlinx.coroutines.delay(5000)
                    try {
                        val httpClient = createRemoteHttpClient()
                        val authToken = RemoteMasterServer.hashPassword(clientPassword)
                        
                        // Refresh status
                        val sReq = okhttp3.Request.Builder().url("https://$clientIp/status").header("X-Auth-Token", authToken).build()
                        val sRes = httpClient.newCall(sReq).execute()
                        if (sRes.isSuccessful) sRes.body?.string()?.let { DistributedService.setRemoteClientStatusStr(it) }
                        
                        // Check llamacpp server directly on port 8080
                        try {
                            val host = clientIp.substringBefore(":")
                            val llReq = okhttp3.Request.Builder().url("http://$host:8080/v1/models").build()
                            // Use a newly built client with short timeout for this call
                            val fastClient = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val llRes = fastClient.newCall(llReq).execute()
                            if (llRes.isSuccessful) {
                                isLlamaCppRunning = true
                                val body = llRes.body?.string()
                                val json = org.json.JSONObject(body ?: "{}")
                                val data = json.optJSONArray("data")
                                if (data != null && data.length() > 0) {
                                    val modelId = data.getJSONObject(0).optString("id")
                                    val shortName = modelId.substringAfterLast("/")
                                    llamaCppLoadedModel = shortName.ifEmpty { "Unknown" }
                                } else {
                                    llamaCppLoadedModel = "Unknown"
                                }
                            } else {
                                isLlamaCppRunning = false
                                llamaCppLoadedModel = null
                            }
                        } catch (_: Exception) {
                            isLlamaCppRunning = false
                            llamaCppLoadedModel = null
                        }
                        
                        // Refresh downloads
                        val dReq = okhttp3.Request.Builder().url("https://$clientIp/download-progress").header("X-Auth-Token", authToken).build()
                        val dRes = httpClient.newCall(dReq).execute()
                        if (dRes.isSuccessful) {
                            val progJson = JSONObject(dRes.body?.string() ?: "{}")
                            val arr = progJson.optJSONArray("downloads") ?: org.json.JSONArray()
                            val list = mutableListOf<JSONObject>()
                            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                            remoteDownloads = list
                            
                            if (list.any { it.optString("status") == "complete" }) {
                                val mReq = okhttp3.Request.Builder().url("https://$clientIp/models").header("X-Auth-Token", authToken).build()
                                val mRes = httpClient.newCall(mReq).execute()
                                if (mRes.isSuccessful) mRes.body?.string()?.let { DistributedService.setRemoteClientModelsStr(it) }
                            }
                        }
                        
                        // Refresh logs
                        val lReq = okhttp3.Request.Builder().url("https://$clientIp/logs").header("X-Auth-Token", authToken).build()
                        val lRes = httpClient.newCall(lReq).execute()
                        if (lRes.isSuccessful) lRes.body?.string()?.let { DistributedService.setRemoteLogsStr(it) }
                    } catch (_: Exception) {}
                }
            }
        }
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // ===== Card Title =====
            Text(
                stringResource(R.string.dist_remote_master),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            // ========== SERVER SECTION (always visible) ==========
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "🖥️ " + stringResource(R.string.dist_remote_server_desc),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = remoteEnabled,
                            onCheckedChange = { enabled ->
                                DistributedService.setRemoteControlEnabled(enabled)
                                if (!enabled) DistributedService.stopRemoteServer()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dist_remote_enable))
                    }
                    
                    if (remoteEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = remotePassword,
                            onValueChange = { DistributedService.setRemoteControlPassword(it) },
                            label = { Text(stringResource(R.string.dist_remote_password)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DraftIntTextField(
                                value = remotePort,
                                onValueChange = DistributedService::setRemoteControlPort,
                                valueRange = 1..65535,
                                label = { Text(stringResource(R.string.dist_remote_port)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = remoteWhitelist.joinToString(", "),
                            onValueChange = { newVal -> DistributedService.setRemoteControlWhitelist(newVal.split(",").map { it.trim() }.filter { it.isNotEmpty() }) },
                            label = { Text(stringResource(R.string.dist_remote_whitelist)) },
                            placeholder = { Text(stringResource(R.string.dist_remote_whitelist_hint)) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            singleLine = true
                        )
                        if (remoteWhitelist.isEmpty()) {
                            Text(
                                stringResource(R.string.dist_remote_warning_all_ips),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Start / Stop Server button
                        val remoteRunning by DistributedService.remoteControlRunning.collectAsState()
                        Button(
                            onClick = {
                                if (remoteRunning) {
                                    DistributedService.stopRemoteServer()
                                } else {
                                    DistributedService.startRemoteServer(context)
                                }
                            },
                            enabled = !remoteRunning || remotePassword.isNotBlank(),
                            colors = if (remoteRunning)
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            else
                                ButtonDefaults.buttonColors(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (remoteRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (remoteRunning) stringResource(R.string.dist_stop_server)
                                else stringResource(R.string.dist_start_server)
                            )
                        }
                        
                        // Server-side logs
                        val serverLogs by DistributedService.remoteControlLogs.collectAsState()
                        var showServerLogs by remember { mutableStateOf(false) }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showServerLogs = !showServerLogs }) {
                                Text(if (showServerLogs) "Hide Logs" else stringResource(R.string.dist_remote_logs))
                            }
                            if (showServerLogs && serverLogs.isNotEmpty()) {
                                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                                IconButton(onClick = {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(serverLogs.joinToString("\n")))
                                    android.widget.Toast.makeText(context, resources.getString(R.string.dist_remote_logs_copied), android.widget.Toast.LENGTH_SHORT).show()
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        
                        if (showServerLogs && serverLogs.isNotEmpty()) {
                            Surface(
                                color = Color.Black,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth().height(200.dp)
                            ) {
                                val logsListState = androidx.compose.foundation.lazy.rememberLazyListState()
                                LaunchedEffect(serverLogs.size) {
                                    if (serverLogs.isNotEmpty()) logsListState.animateScrollToItem(serverLogs.size - 1)
                                }
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    LazyColumn(state = logsListState, modifier = Modifier.padding(8.dp)) {
                                        items(serverLogs.size) { i ->
                                            Text(
                                                serverLogs[i],
                                                color = Color.Green,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(bottom = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            
            // ========== CLIENT SECTION ==========
            Text(
                "📱 " + stringResource(R.string.dist_remote_client_desc),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            if (!clientConnected) {
                // ===== Login Form =====
                OutlinedTextField(
                    value = clientIp,
                    onValueChange = { DistributedService.setRemoteClientIp(it); clientError = null },
                    label = { Text(stringResource(R.string.dist_remote_ip)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("192.168.1.xxx:8089") }
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = clientPassword,
                    onValueChange = { DistributedService.setRemoteClientPassword(it); clientError = null },
                    label = { Text(stringResource(R.string.dist_remote_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                )
                
                if (clientError != null) {
                    Text(
                        clientError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                Button(
                    onClick = {
                        if (clientIp.isBlank()) { clientError = resources.getString(R.string.dist_remote_error_empty_ip); return@Button }
                        if (clientPassword.isBlank()) { clientError = resources.getString(R.string.dist_remote_error_empty_password); return@Button }
                        clientConnecting = true
                        clientError = null
                        
                        clientScope.launch(Dispatchers.IO) {
                            try {
                                val httpClient = createRemoteHttpClient()
                                val authToken = RemoteMasterServer.hashPassword(clientPassword)
                                
                                // Fetch status
                                val sReq = okhttp3.Request.Builder().url("https://$clientIp/status").header("X-Auth-Token", authToken).build()
                                val sRes = httpClient.newCall(sReq).execute()
                                if (!sRes.isSuccessful) { clientError = resources.getString(R.string.dist_remote_error_connect); return@launch }
                                sRes.body?.string()?.let { DistributedService.setRemoteClientStatusStr(it) }
                                
                                // Fetch models
                                val mReq = okhttp3.Request.Builder().url("https://$clientIp/models").header("X-Auth-Token", authToken).build()
                                val mRes = httpClient.newCall(mReq).execute()
                                if (mRes.isSuccessful) mRes.body?.string()?.let { DistributedService.setRemoteClientModelsStr(it) }
                                
                                DistributedService.setRemoteClientConnected(true)
                            } catch (e: Exception) {
                                clientError = resources.getString(R.string.dist_remote_error_connect)
                            } finally {
                                clientConnecting = false
                            }
                        }
                    },
                    enabled = !clientConnecting,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    if (clientConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.dist_remote_connect))
                }
            } else {
                // ========== CONNECTED STATE - TABBED LAYOUT ==========
                
                // --- Status Header ---
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Pulsing green dot
                                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                val alpha by infiniteTransition.animateFloat(
                                    initialValue = 0.4f, targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1000, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ), label = "pulseAlpha"
                                )
                                Canvas(modifier = Modifier.size(10.dp)) {
                                    drawCircle(color = Color(0xFF4CAF50).copy(alpha = alpha))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.dist_remote_connected_to, clientIp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            // Disconnect button
                            IconButton(
                                onClick = {
                                    DistributedService.setRemoteClientConnected(false)
                                    DistributedService.setRemoteClientStatusStr(null)
                                    DistributedService.setRemoteClientModelsStr(null)
                                    DistributedService.setRemoteLogsStr(null)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dist_remote_disconnect), modifier = Modifier.size(18.dp))
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Current model name + status chip
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val displayModel = llamaCppLoadedModel ?: currentModelFile
                            Text(
                                displayModel ?: stringResource(R.string.dist_remote_no_model_loaded),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val isRunning = isLlamaCppRunning || clientStatus?.optBoolean("running", false) == true
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isRunning) Color(0xFF4CAF50).copy(alpha = 0.2f) else MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    if (isRunning) stringResource(R.string.dist_remote_status_running) else stringResource(R.string.dist_remote_status_stopped),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // RAM bar
                        val ramUsed = clientStatus?.optLong("ramUsedMB", 0L) ?: 0L
                        val ramTotal = clientStatus?.optLong("ramTotalMB", 1L) ?: 1L
                        val ramFraction = if (ramTotal > 0) (ramUsed.toFloat() / ramTotal.toFloat()) else 0f
                        Text(stringResource(R.string.dist_remote_ram_usage, "${ramUsed}MB", "${ramTotal}MB"), style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator(
                            progress = { ramFraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = if (ramFraction > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        
                        // Storage bar
                        val availableSpace = clientStatus?.optString("availableSpaceFormatted", "?") ?: "?"
                        val totalSpace = clientStatus?.optString("totalSpaceFormatted", "?") ?: "?"
                        val availableBytes = clientStatus?.optLong("availableSpaceBytes", 0L) ?: 0L
                        val totalBytesSpace = clientStatus?.optLong("totalSpaceBytes", 1L) ?: 1L
                        val storageUsedFraction = if (totalBytesSpace > 0) 1f - (availableBytes.toFloat() / totalBytesSpace.toFloat()) else 0f
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.dist_remote_storage_info, availableSpace, totalSpace), style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator(
                            progress = { storageUsedFraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = if (storageUsedFraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // --- Tab Row ---
                val tabTitles = listOf(
                    stringResource(R.string.dist_remote_tab_control),
                    stringResource(R.string.dist_remote_tab_settings),
                    stringResource(R.string.dist_remote_tab_models),
                    stringResource(R.string.dist_remote_tab_system)
                )
                val tabIcons = listOf(
                    Icons.Default.PlayArrow,
                    Icons.Default.Settings,
                    Icons.Default.List,
                    Icons.Default.Build
                )
                
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, maxLines = 1) },
                            icon = {
                                if (index == 2 && remoteDownloads.any { it.optString("status") == "downloading" }) {
                                    BadgedBox(badge = {
                                        Badge { Text("${remoteDownloads.count { it.optString("status") == "downloading" }}") }
                                    }) {
                                        Icon(tabIcons[index], contentDescription = null)
                                    }
                                } else {
                                    Icon(tabIcons[index], contentDescription = null)
                                }
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))

                // ========== TAB 0: CONTROL ==========
                when (selectedTab) {
                    0 -> {
                        // Model selector dropdown
                        var modelExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = modelExpanded,
                            onExpandedChange = { modelExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedRemoteModel ?: stringResource(R.string.dist_remote_select_model),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.dist_remote_available_models)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = modelExpanded,
                                onDismissRequest = { modelExpanded = false }
                            ) {
                                clientModels.forEach { modelJson ->
                                    val filename = modelJson.optString("filename", "")
                                    val sizeFormatted = modelJson.optString("sizeFormatted", "")
                                    val isCurrent = filename == currentModelFile
                                    if (filename.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (isCurrent) {
                                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                    }
                                                    Column {
                                                        Text(filename, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                                                        if (sizeFormatted.isNotEmpty()) Text(sizeFormatted, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                            },
                                            onClick = { DistributedService.setRemoteUISelectedModel(filename); modelExpanded = false }
                                        )
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Switch Model button
                        Button(
                            onClick = {
                                val ctx = remoteContextSize.toIntOrNull() ?: 4096
                                val batch = remoteBatchSize.toIntOrNull() ?: 512
                                val tmp = remoteTemperature.toFloatOrNull() ?: 0.7f
                                val reuse = remoteKvReuse.toIntOrNull() ?: 0
                                showSwitchConfirm = RemoteSwitchParams(selectedRemoteModel!!, ctx, batch, tmp, remoteKvEnabled, remoteKvK, remoteKvV, reuse)
                            },
                            enabled = selectedRemoteModel != null && selectedRemoteModel != currentModelFile && !clientSwitching,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (clientSwitching) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.dist_remote_switching))
                            } else {
                                Text(stringResource(R.string.dist_remote_switch))
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Restart + Stop buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    clientRestarting = true
                                    clientScope.launch(Dispatchers.IO) {
                                        try {
                                            val httpClient = createRemoteHttpClient()
                                            val authToken = RemoteMasterServer.hashPassword(clientPassword)
                                            val jsonBody = JSONObject().apply {
                                                put("model", currentModelFile ?: "")
                                                put("contextSize", remoteContextSize.toIntOrNull() ?: 2048)
                                                put("batchSize", remoteBatchSize.toIntOrNull() ?: 512)
                                                put("temperature", remoteTemperature.toFloatOrNull() ?: 0.5f)
                                                put("kvCacheEnabled", remoteKvEnabled)
                                                put("kvCacheTypeK", remoteKvK)
                                                put("kvCacheTypeV", remoteKvV)
                                                put("kvCacheReuse", remoteKvReuse.toIntOrNull() ?: 0)
                                                put("parallel", remoteParallel.toIntOrNull() ?: 1)
                                                put("cacheRam", remoteCacheRam.toIntOrNull() ?: 0)
                                                put("customFlags", remoteCustomFlags)
                                                put("flashAttention", remoteUIFlashAttention)
                                            }
                                            val req = okhttp3.Request.Builder()
                                                .url("https://$clientIp/restart")
                                                .header("X-Auth-Token", authToken)
                                                .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                                .build()
                                            httpClient.newCall(req).execute()
                                            kotlinx.coroutines.delay(2000)
                                            val sReq = okhttp3.Request.Builder().url("https://$clientIp/status").header("X-Auth-Token", authToken).build()
                                            val sRes = httpClient.newCall(sReq).execute()
                                            if (sRes.isSuccessful) sRes.body?.string()?.let { DistributedService.setRemoteClientStatusStr(it) }
                                        } catch (e: Exception) {
                                            com.example.llamadroid.util.DebugLog.log("Remote restart error: ${e.message}")
                                        } finally { clientRestarting = false }
                                    }
                                },
                                enabled = !clientSwitching && !clientRestarting,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (clientRestarting) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(8.dp)) }
                                Text(stringResource(R.string.dist_remote_restart_server))
                            }
                            
                            Button(
                                onClick = {
                                    clientScope.launch(Dispatchers.IO) {
                                        try {
                                            val httpClient = createRemoteHttpClient()
                                            val authToken = RemoteMasterServer.hashPassword(clientPassword)
                                            val req = okhttp3.Request.Builder()
                                                .url("https://$clientIp/stop")
                                                .header("X-Auth-Token", authToken)
                                                .post(JSONObject().toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                                .build()
                                            httpClient.newCall(req).execute()
                                        } catch (e: Exception) {
                                            com.example.llamadroid.util.DebugLog.log("Remote stop error: ${e.message}")
                                        }
                                    }
                                },
                                enabled = !clientSwitching,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.dist_remote_stop_server), color = MaterialTheme.colorScheme.onError)
                            }
                        }
                        
                        // Launch command viewer
                        val launchCmd = clientStatus?.optString("lastCommand", "") ?: ""
                        if (launchCmd.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            var showCmd by remember { mutableStateOf(true) }
                            val cmdClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { showCmd = !showCmd },
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(stringResource(R.string.dist_remote_command_title), style = MaterialTheme.typography.titleSmall)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (showCmd) {
                                                IconButton(onClick = {
                                                    cmdClipboard.setText(androidx.compose.ui.text.AnnotatedString(launchCmd))
                                                    android.widget.Toast.makeText(context, resources.getString(R.string.dist_command_copied), android.widget.Toast.LENGTH_SHORT).show()
                                                }, modifier = Modifier.size(32.dp)) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy), modifier = Modifier.size(16.dp))
                                                }
                                            }
                                            Icon(if (showCmd) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                                        }
                                    }
                                    if (showCmd) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Surface(
                                            color = Color.Black,
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 200.dp)
                                        ) {
                                            androidx.compose.foundation.text.selection.SelectionContainer {
                                                Text(
                                                    launchCmd,
                                                    color = Color.Green,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(8.dp)
                                                        .verticalScroll(rememberScrollState())
                                                        .horizontalScroll(rememberScrollState())
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // ========== TAB 1: SETTINGS ==========
                    1 -> {
                        // Context/Batch/Temp
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = remoteContextSize,
                                onValueChange = { DistributedService.setRemoteUIContextSize(it.filter { char -> char.isDigit() }) },
                                label = { Text(stringResource(R.string.dist_remote_context_size), overflow = TextOverflow.Ellipsis, maxLines = 1) },
                                modifier = Modifier.weight(1f), singleLine = true
                            )
                            OutlinedTextField(
                                value = remoteTemperature,
                                onValueChange = { DistributedService.setRemoteUITemperature(it) },
                                label = { Text(stringResource(R.string.dist_remote_temperature), overflow = TextOverflow.Ellipsis, maxLines = 1) },
                                modifier = Modifier.weight(1f), singleLine = true
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = remoteBatchSize,
                            onValueChange = { DistributedService.setRemoteUIBatchSize(it.filter { char -> char.isDigit() }) },
                            label = { Text(stringResource(R.string.dist_batch_size)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // KV Cache
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = remoteKvEnabled, onCheckedChange = { DistributedService.setRemoteUIKvEnabled(it) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dist_remote_kv_enable))
                        }
                        if (remoteKvEnabled) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = remoteKvK, onValueChange = { DistributedService.setRemoteUIKvTypeK(it) }, label = { Text(stringResource(R.string.dist_remote_kv_k)) }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(value = remoteKvV, onValueChange = { DistributedService.setRemoteUIKvTypeV(it) }, label = { Text(stringResource(R.string.dist_remote_kv_v)) }, modifier = Modifier.weight(1f), singleLine = true)
                            }
                            OutlinedTextField(value = remoteKvReuse, onValueChange = { DistributedService.setRemoteUIKvReuse(it.filter { char -> char.isDigit() }) }, label = { Text(stringResource(R.string.dist_remote_kv_reuse)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true)
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Flash Attention
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.dist_flash_attention), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.dist_flash_attention_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = remoteUIFlashAttention, onCheckedChange = { DistributedService.setRemoteUIFlashAttention(it) })
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Parallel + Cache RAM
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = remoteParallel, onValueChange = { DistributedService.setRemoteUIParallel(it.filter { char -> char.isDigit() }) }, label = { Text(stringResource(R.string.dist_advanced_parallel)) }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("1") })
                            OutlinedTextField(value = remoteCacheRam, onValueChange = { DistributedService.setRemoteUICacheRam(it.filter { char -> char.isDigit() }) }, label = { Text(stringResource(R.string.dist_advanced_cache_ram)) }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("0") })
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = remoteCustomFlags, onValueChange = { DistributedService.setRemoteUICustomFlags(it) }, label = { Text(stringResource(R.string.dist_advanced_custom_flags)) }, modifier = Modifier.fillMaxWidth(), singleLine = false, placeholder = { Text("--load-mode mlock") })
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Speculative Decoding
                        Text(stringResource(R.string.dist_remote_speculative), style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = remoteSpecEnabled, onCheckedChange = { DistributedService.setRemoteUISpecEnabled(it) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dist_remote_spec_enable))
                        }
                        if (remoteSpecEnabled) {
                            var draftExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded = draftExpanded, onExpandedChange = { draftExpanded = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                OutlinedTextField(value = remoteDraftModel ?: stringResource(R.string.dist_speculative_select_draft), onValueChange = {}, readOnly = true, label = { Text(stringResource(R.string.dist_speculative_draft_model)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = draftExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                ExposedDropdownMenu(expanded = draftExpanded, onDismissRequest = { draftExpanded = false }) {
                                    clientModels.forEach { modelJson ->
                                        val filename = modelJson.optString("filename", "")
                                        if (filename.isNotEmpty() && filename != selectedRemoteModel) {
                                            DropdownMenuItem(text = { Text(filename) }, onClick = { DistributedService.setRemoteUIDraftModel(filename); draftExpanded = false })
                                        }
                                    }
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = remoteDraftMin, onValueChange = { DistributedService.setRemoteUIDraftMin(it.filter { char -> char.isDigit() }) }, label = { Text(stringResource(R.string.dist_speculative_draft_min)) }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(value = remoteDraftMax, onValueChange = { DistributedService.setRemoteUIDraftMax(it.filter { char -> char.isDigit() }) }, label = { Text(stringResource(R.string.dist_speculative_draft_max)) }, modifier = Modifier.weight(1f), singleLine = true)
                            }
                        }
                        Button(
                            onClick = {
                                clientSpecUpdating = true
                                clientScope.launch(Dispatchers.IO) {
                                    try {
                                        val body = org.json.JSONObject().apply {
                                            put("enabled", remoteSpecEnabled)
                                            if (remoteSpecEnabled) {
                                                put("draftModel", remoteDraftModel ?: "")
                                                put("draftMin", remoteDraftMin.toIntOrNull() ?: 0)
                                                put("draftMax", remoteDraftMax.toIntOrNull() ?: 3)
                                                put("draftPMin", remoteDraftPMin.toFloatOrNull() ?: 0.0f)
                                            }
                                        }
                                        val httpClient = createRemoteHttpClient()
                                        val req = okhttp3.Request.Builder()
                                            .url("https://$clientIp/speculative")
                                            .header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword))
                                            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                            .build()
                                        httpClient.newCall(req).execute()
                                    } catch (e: Exception) {
                                        com.example.llamadroid.util.DebugLog.log("Remote speculative error: ${e.message}")
                                    } finally { clientSpecUpdating = false }
                                }
                            },
                            enabled = !clientSpecUpdating && (!remoteSpecEnabled || remoteDraftModel != null),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            if (clientSpecUpdating) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(8.dp)); Text(stringResource(R.string.dist_remote_spec_updating)) }
                            else Text(stringResource(R.string.dist_remote_apply_speculative))
                        }
                    }

                    // ========== TAB 2: MODELS ==========
                    2 -> {
                        // Model list with sizes and delete
                        Text(stringResource(R.string.dist_remote_available_models), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                        
                        var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
                        var deleteInProgress by remember { mutableStateOf(false) }
                        
                        clientModels.forEach { modelJson ->
                            val filename = modelJson.optString("filename", "")
                            val sizeFormatted = modelJson.optString("sizeFormatted", "")
                            val isCurrentModel = filename == currentModelFile
                            if (filename.isNotEmpty()) {
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(filename, style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isCurrentModel) FontWeight.Bold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(sizeFormatted, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (isCurrentModel) Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.action_active), tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                                    IconButton(onClick = { showDeleteConfirm = filename }, enabled = !isCurrentModel && !deleteInProgress, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.dist_remote_delete_model), tint = if (isCurrentModel) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f) else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        
                        // Delete confirmation dialog
                        if (showDeleteConfirm != null) {
                            AlertDialog(
                                onDismissRequest = { showDeleteConfirm = null },
                                title = { Text(stringResource(R.string.dist_remote_delete_model)) },
                                text = { Text(stringResource(R.string.dist_remote_delete_confirm, showDeleteConfirm!!)) },
                                confirmButton = {
                                    Button(onClick = {
                                        val filenameToDelete = showDeleteConfirm!!
                                        showDeleteConfirm = null
                                        deleteInProgress = true
                                        clientScope.launch(Dispatchers.IO) {
                                            try {
                                                val httpClient = createRemoteHttpClient()
                                                val authToken = RemoteMasterServer.hashPassword(clientPassword)
                                                val delBody = JSONObject().put("filename", filenameToDelete).toString().toRequestBody("application/json".toMediaTypeOrNull())
                                                val delReq = okhttp3.Request.Builder().url("https://$clientIp/delete-model").header("X-Auth-Token", authToken).post(delBody).build()
                                                httpClient.newCall(delReq).execute()
                                                kotlinx.coroutines.delay(500)
                                                val mReq = okhttp3.Request.Builder().url("https://$clientIp/models").header("X-Auth-Token", authToken).build()
                                                val mRes = httpClient.newCall(mReq).execute()
                                                if (mRes.isSuccessful) mRes.body?.string()?.let { DistributedService.setRemoteClientModelsStr(it) }
                                                val sReq = okhttp3.Request.Builder().url("https://$clientIp/status").header("X-Auth-Token", authToken).build()
                                                val sRes = httpClient.newCall(sReq).execute()
                                                if (sRes.isSuccessful) sRes.body?.string()?.let { DistributedService.setRemoteClientStatusStr(it) }
                                            } catch (e: Exception) { com.example.llamadroid.util.DebugLog.log("Remote delete error: ${e.message}") }
                                            finally { deleteInProgress = false }
                                        }
                                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.onError) }
                                },
                                dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.action_cancel)) } }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Download from HuggingFace
                        Text(stringResource(R.string.dist_remote_download_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                        var downloadUrl by remember { mutableStateOf("") }
                        var downloadStarting by remember { mutableStateOf(false) }
                        var downloadError by remember { mutableStateOf<String?>(null) }
                        
                        OutlinedTextField(value = downloadUrl, onValueChange = { downloadUrl = it; downloadError = null }, label = { Text(stringResource(R.string.dist_remote_download_url)) }, placeholder = { Text(stringResource(R.string.dist_remote_download_url_hint), style = MaterialTheme.typography.bodySmall) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        if (downloadError != null) Text(downloadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        
                        Button(
                            onClick = {
                                downloadStarting = true; downloadError = null
                                clientScope.launch(Dispatchers.IO) {
                                    try {
                                        val httpClient = createRemoteHttpClient(10)
                                        val authToken = RemoteMasterServer.hashPassword(clientPassword)
                                        val dlBody = JSONObject().put("url", downloadUrl).toString().toRequestBody("application/json".toMediaTypeOrNull())
                                        val dlReq = okhttp3.Request.Builder().url("https://$clientIp/download").header("X-Auth-Token", authToken).post(dlBody).build()
                                        val dlRes = httpClient.newCall(dlReq).execute()
                                        val resBody = dlRes.body?.string()
                                        if (dlRes.isSuccessful) downloadUrl = ""
                                        else downloadError = try { JSONObject(resBody ?: "").optString("error", "Download failed") } catch (_: Exception) { "Download failed (${dlRes.code})" }
                                    } catch (e: Exception) { downloadError = e.message ?: "Connection error" }
                                    finally { downloadStarting = false }
                                }
                            },
                            enabled = downloadUrl.isNotBlank() && !downloadStarting,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            if (downloadStarting) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(8.dp)) }
                            Text(stringResource(R.string.dist_remote_download_start))
                        }
                        
                        // Active downloads
                        if (remoteDownloads.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            remoteDownloads.forEach { dl ->
                                val dlFilename = dl.optString("filename", "")
                                val dlProgress = dl.optDouble("progress", 0.0).toFloat()
                                val dlStatus = dl.optString("status", "downloading")
                                val dlSpeed = dl.optString("speedFormatted", "")
                                val dlPercent = (dlProgress * 100).toInt()
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when (dlStatus) { "complete" -> MaterialTheme.colorScheme.primaryContainer; "error" -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(dlFilename, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(when (dlStatus) { "complete" -> stringResource(R.string.dist_remote_download_complete); "error" -> stringResource(R.string.dist_remote_download_error); "cancelled" -> stringResource(R.string.dist_remote_download_cancel); else -> "$dlPercent% • $dlSpeed" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (dlStatus == "downloading") {
                                                IconButton(onClick = {
                                                    clientScope.launch(Dispatchers.IO) {
                                                        try {
                                                            val httpClient = createRemoteHttpClient()
                                                            val cancelBody = JSONObject().put("filename", dlFilename).toString().toRequestBody("application/json".toMediaTypeOrNull())
                                                            val cancelReq = okhttp3.Request.Builder().url("https://$clientIp/cancel-download").header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword)).post(cancelBody).build()
                                                            httpClient.newCall(cancelReq).execute()
                                                        } catch (_: Exception) {}
                                                    }
                                                }, modifier = Modifier.size(32.dp)) {
                                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dist_remote_download_cancel), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                        if (dlStatus == "downloading" && dlProgress > 0f) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LinearProgressIndicator(progress = { dlProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // ========== TAB 3: SYSTEM ==========
                    3 -> {
                        // Worker Management
                        Text(stringResource(R.string.dist_remote_workers_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                        
                        var workerIpInput by remember { mutableStateOf("") }
                        var workerPortInput by remember { mutableStateOf("50052") }
                        var workerRamInput by remember { mutableStateOf("4096") }
                        var workerLoading by remember { mutableStateOf(false) }
                        
                        // Fetch workers on first open of System tab
                        LaunchedEffect(Unit) {
                            if (remoteWorkersList.isEmpty()) {
                                kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    try {
                                        val httpClient = createRemoteHttpClient()
                                        val req = okhttp3.Request.Builder().url("https://$clientIp/workers").header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword)).build()
                                        val res = httpClient.newCall(req).execute()
                                        if (res.isSuccessful) {
                                            val wrapper = JSONObject(res.body?.string() ?: "{}")
                                            val arr = wrapper.optJSONArray("workers") ?: JSONArray()
                                            val list = mutableListOf<JSONObject>()
                                            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                                            remoteWorkersList = list
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        
                        remoteWorkersList.forEach { workerJson ->
                            val wIp = workerJson.optString("ip"); val wPort = workerJson.optInt("port", 50052)
                            val wName = workerJson.optString("deviceName", "Worker"); val wEnabled = workerJson.optBoolean("isEnabled", true)
                            val wRam = workerJson.optInt("availableRamMB", 0)
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("$wIp:$wPort", style = MaterialTheme.typography.bodyMedium)
                                    Text("$wName · ${wRam}MB RAM", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = wEnabled, onCheckedChange = { newEnabled ->
                                    clientScope.launch(Dispatchers.IO) {
                                        try {
                                            val httpClient = createRemoteHttpClient()
                                            val jsonBody = JSONObject().apply { put("ip", wIp); put("port", wPort); put("enabled", newEnabled) }.toString()
                                            val body = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
                                            val req = okhttp3.Request.Builder().url("https://$clientIp/toggle-worker").header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword)).post(body).build()
                                            httpClient.newCall(req).execute()
                                            remoteWorkersList = remoteWorkersList.map { w -> if (w.optString("ip") == wIp && w.optInt("port") == wPort) JSONObject(w.toString()).apply { put("isEnabled", newEnabled) } else w }
                                        } catch (_: Exception) {}
                                    }
                                })
                                IconButton(onClick = {
                                    clientScope.launch(Dispatchers.IO) {
                                        try {
                                            val httpClient = createRemoteHttpClient()
                                            val jsonBody = JSONObject().apply { put("ip", wIp); put("port", wPort) }.toString()
                                            val bdy = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
                                            val req = okhttp3.Request.Builder().url("https://$clientIp/remove-worker").header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword)).post(bdy).build()
                                            httpClient.newCall(req).execute()
                                            remoteWorkersList = remoteWorkersList.filter { w -> !(w.optString("ip") == wIp && w.optInt("port") == wPort) }
                                        } catch (_: Exception) {}
                                    }
                                }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_remove), tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = workerIpInput, onValueChange = { workerIpInput = it }, label = { Text(stringResource(R.string.label_ip)) }, modifier = Modifier.weight(2f), singleLine = true)
                            OutlinedTextField(value = workerPortInput, onValueChange = { workerPortInput = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.port_label)) }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        OutlinedTextField(value = workerRamInput, onValueChange = { workerRamInput = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.label_ram_mb)) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true)
                        Button(
                            onClick = {
                                if (workerIpInput.isNotBlank()) {
                                    workerLoading = true
                                    clientScope.launch(Dispatchers.IO) {
                                        try {
                                            val httpClient = createRemoteHttpClient()
                                            val jsonBody = JSONObject().apply { put("ip", workerIpInput.trim()); put("port", workerPortInput.toIntOrNull() ?: 50052); put("ramMB", workerRamInput.toIntOrNull() ?: 4096) }.toString()
                                            val body = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
                                            val req = okhttp3.Request.Builder().url("https://$clientIp/add-worker").header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword)).post(body).build()
                                            val addRes = httpClient.newCall(req).execute()
                                            if (addRes.isSuccessful) {
                                                val wReq = okhttp3.Request.Builder().url("https://$clientIp/workers").header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword)).build()
                                                val wRes = httpClient.newCall(wReq).execute()
                                                if (wRes.isSuccessful) {
                                                    val wrapper = JSONObject(wRes.body?.string() ?: "{}")
                                                    val arr = wrapper.optJSONArray("workers") ?: JSONArray()
                                                    val list = mutableListOf<JSONObject>()
                                                    for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                                                    remoteWorkersList = list
                                                }
                                                workerIpInput = ""
                                            }
                                        } catch (_: Exception) {}
                                        finally { workerLoading = false }
                                    }
                                }
                            },
                            enabled = workerIpInput.isNotBlank() && !workerLoading,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            if (workerLoading) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(8.dp)) }
                            Text(stringResource(R.string.dist_remote_add_worker))
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Server Logs
                        var showLogs by remember { mutableStateOf(false) }
                        Button(
                            onClick = { showLogs = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                        ) {
                            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dist_remote_view_logs))
                        }
                        
                        if (showLogs) {
                            androidx.compose.ui.window.Dialog(
                                onDismissRequest = { showLogs = false },
                                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                            ) {
                                Surface(modifier = Modifier.fillMaxSize().padding(16.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(stringResource(R.string.dist_remote_server_logs_title), style = MaterialTheme.typography.titleLarge)
                                            Row {
                                                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                                val logsCopiedStr = stringResource(R.string.dist_remote_logs_copied)
                                                IconButton(onClick = {
                                                    clientScope.launch(Dispatchers.IO) {
                                                        try {
                                                            val httpClient = createRemoteHttpClient()
                                                            val authToken = RemoteMasterServer.hashPassword(clientPassword)
                                                            val lReq = okhttp3.Request.Builder().url("https://$clientIp/clear-logs").header("X-Auth-Token", authToken).post(JSONObject().toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
                                                            val lRes = httpClient.newCall(lReq).execute()
                                                            if (lRes.isSuccessful) kotlinx.coroutines.withContext(Dispatchers.Main) { DistributedService.setRemoteLogsStr(null) }
                                                        } catch (_: Exception) {}
                                                    }
                                                }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.dist_remote_clear_logs)) }
                                                IconButton(onClick = {
                                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(remoteLogsList.joinToString("\n")))
                                                    android.widget.Toast.makeText(ctx, logsCopiedStr, android.widget.Toast.LENGTH_SHORT).show()
                                                }) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy)) }
                                                IconButton(onClick = { showLogs = false }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
                                            }
                                        }
                                        HorizontalDivider()
                                        Surface(color = Color.Black, modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp), shape = RoundedCornerShape(8.dp)) {
                                            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                                            LaunchedEffect(remoteLogsList.size) {
                                                if (remoteLogsList.isNotEmpty()) {
                                                    val isAtBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == listState.layoutInfo.totalItemsCount - 1
                                                    if (isAtBottom || listState.layoutInfo.totalItemsCount == 0) listState.animateScrollToItem(remoteLogsList.size - 1)
                                                }
                                            }
                                            androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                                                items(remoteLogsList.size) { i ->
                                                    Text(remoteLogsList[i], color = Color.Green, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 2.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } // end when(selectedTab)
            }
        }
    }
    
    // Switch confirmation dialog
    if (showSwitchConfirm != null) {
        val params = showSwitchConfirm!!
        AlertDialog(
            onDismissRequest = { showSwitchConfirm = null },
            title = { Text(stringResource(R.string.dist_remote_switch)) },
            text = { Text(resources.getString(R.string.dist_remote_switch_confirm, params.model)) },
            confirmButton = {
                Button(onClick = {
                    showSwitchConfirm = null
                    clientSwitching = true
                    clientScope.launch(Dispatchers.IO) {
                        try {
                            val httpClient = createRemoteHttpClient()
                            val authToken = RemoteMasterServer.hashPassword(clientPassword)
                            
                            // First, stop the current server
                            val stopReq = okhttp3.Request.Builder()
                                .url("https://$clientIp/stop")
                                .header("X-Auth-Token", authToken)
                                .post(JSONObject().toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                .build()
                            try { httpClient.newCall(stopReq).execute() } catch (_: Exception) { }
                            kotlinx.coroutines.delay(1000)
                            
                            // Then switch to the new model
                            val jsonBody = JSONObject().apply {
                                put("model", params.model)
                                put("contextSize", params.contextSize)
                                put("batchSize", params.batchSize)
                                put("temperature", params.temperature.toDouble())
                                put("kvCacheEnabled", params.kvEnabled)
                                put("kvCacheTypeK", params.kvK)
                                put("kvCacheTypeV", params.kvV)
                            }.toString()
                            val body = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
                            val req = okhttp3.Request.Builder()
                                .url("https://$clientIp/switch")
                                .header("X-Auth-Token", authToken)
                                .post(body)
                                .build()
                            val res = httpClient.newCall(req).execute()
                            if (res.isSuccessful) {
                                kotlinx.coroutines.delay(2000)
                                val statusReq = okhttp3.Request.Builder().url("https://$clientIp/status").header("X-Auth-Token", authToken).build()
                                val statusRes = httpClient.newCall(statusReq).execute()
                                if (statusRes.isSuccessful) statusRes.body?.string()?.let { DistributedService.setRemoteClientStatusStr(it) }
                            }
                        } catch (e: Exception) { com.example.llamadroid.util.DebugLog.log("Remote switch error: ${e.message}") }
                        finally { clientSwitching = false }
                    }
                }) { Text(stringResource(R.string.action_yes)) }
            },
            dismissButton = { TextButton(onClick = { showSwitchConfirm = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}


data class RemoteSwitchParams(
    val model: String,
    val contextSize: Int,
    val batchSize: Int,
    val temperature: Float,
    val kvEnabled: Boolean,
    val kvK: String,
    val kvV: String,
    val kvReuse: Int
)

@Composable
private fun DistributedKvChoice(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    description: String
) {
    val shape = RoundedCornerShape(12.dp)
    val outline = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, outline, shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DistributedKvTypeSelector(
    title: String,
    selected: String,
    onSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("f16" to "F16", "q8_0" to "Q8_0", "q4_0" to "Q4_0").forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    modifier = Modifier.weight(1f),
                    label = {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DistributedPlacementStepCard(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        )
    }
}

@Composable
private fun DistributedAllocationStrip(
    workers: List<SavedWorker>,
    residentWorkerId: Long?,
    includeMaster: Boolean,
    masterRamMiB: Int,
    selectedWorkerIds: Set<Long>?,
    offloadableLayers: Int?
) {
    val activeWorkers = when {
        residentWorkerId != null -> workers.filter { it.id == residentWorkerId }
        selectedWorkerIds != null -> workers.filter { it.id in selectedWorkerIds }
        else -> workers
    }
    val refs = buildList {
        if (includeMaster) add(DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU))
        activeWorkers.forEach {
            add(DistributedDeviceRef(DistributedDeviceKind.WORKER, it.id, "${it.ip}:${it.port}"))
        }
    }
    if (refs.isEmpty()) {
        Text(stringResource(R.string.dist_main_no_devices), color = MaterialTheme.colorScheme.error)
        return
    }
    val ramByKey = buildMap {
        put(DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU).stableKey(), masterRamMiB)
        activeWorkers.forEach { worker ->
            put(DistributedDeviceRef(DistributedDeviceKind.WORKER, worker.id, "${worker.ip}:${worker.port}").stableKey(), worker.ramMB)
        }
    }
    val allocations = offloadableLayers?.let { layers ->
        runCatching {
            DistributedLlamaLaunchResolver.allocateLayersByRam(layers, refs) { ramByKey[it.stableKey()] ?: 0 }
        }.getOrNull()
    }
    val weights = refs.map { (ramByKey[it.stableKey()] ?: 0).toFloat().coerceAtLeast(0.01f) }
    val total = weights.sum().coerceAtLeast(0.01f)
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error
    )
    Row(
        modifier = Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(8.dp))
    ) {
        refs.forEachIndexed { index, _ ->
            Box(
                modifier = Modifier.weight(weights[index] / total).fillMaxHeight()
                    .border(1.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(2.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .then(Modifier)
            ) {
                Canvas(Modifier.fillMaxSize()) { drawRect(colors[index % colors.size]) }
            }
        }
    }
    refs.forEachIndexed { index, ref ->
        val percent = (weights[index] / total * 100f).toInt()
        val label = if (ref.kind == DistributedDeviceKind.MASTER_CPU) {
            stringResource(R.string.dist_master_cpu)
        } else {
            activeWorkers.firstOrNull { it.id == ref.workerId }?.deviceName.orEmpty()
        }
        val layerText = allocations?.getOrNull(index)?.assignedLayers?.let { layers ->
            stringResource(R.string.dist_allocation_layers, layers)
        }.orEmpty()
        Text(
            stringResource(
                R.string.dist_allocation_device_summary,
                label,
                percent,
                ramByKey[ref.stableKey()] ?: 0,
                layerText
            ),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
