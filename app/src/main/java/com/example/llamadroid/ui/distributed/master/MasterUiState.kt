package com.example.llamadroid.ui.distributed.master

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdServiceInfo
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle as collectAsState
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SavedCommandEntity
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.data.model.SavedWorker
import com.example.llamadroid.service.DistributedDeviceKind
import com.example.llamadroid.service.DistributedDeviceRef
import com.example.llamadroid.service.DistributedKvPlacement
import com.example.llamadroid.service.DistributedKvPlacementMode
import com.example.llamadroid.service.DistributedLaunchResolution
import com.example.llamadroid.service.DistributedLayerAllocation
import com.example.llamadroid.service.DistributedLayerPlanning
import com.example.llamadroid.service.DistributedManualSplit
import com.example.llamadroid.service.DistributedLlamaArguments
import com.example.llamadroid.service.DistributedLlamaLaunchProfile
import com.example.llamadroid.service.DistributedLlamaLaunchResolver
import com.example.llamadroid.service.DistributedLlamaResolveRequest
import com.example.llamadroid.service.DistributedMasterLlamaService
import com.example.llamadroid.service.DistributedMasterRuntimeState
import com.example.llamadroid.service.DistributedPerfSettings
import com.example.llamadroid.service.DistributedPerfText
import com.example.llamadroid.service.DistributedPlanWarningType
import com.example.llamadroid.service.DistributedService
import com.example.llamadroid.service.DistributedSpeculativePlacement
import com.example.llamadroid.service.DistributedWorkerLaunchSpec
import com.example.llamadroid.service.DraftModelPlacement
import com.example.llamadroid.service.DraftModelPlacementMode
import com.example.llamadroid.service.LlamaModelMetadataProbe
import com.example.llamadroid.service.LlamaModelProbeNoLayersException
import com.example.llamadroid.service.LlamaModelProbeResult
import com.example.llamadroid.service.LlamaModelProbeTimeoutException
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.LlamaSpeculativeMode
import com.example.llamadroid.service.MainModelPlacement
import com.example.llamadroid.service.MainModelPlacementMode
import com.example.llamadroid.service.MmprojPlacement
import com.example.llamadroid.service.ProcessController
import com.example.llamadroid.service.RemoteMasterServer
import com.example.llamadroid.service.ResolvedDistributedLaunch
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.service.WorkerInfo
import com.example.llamadroid.service.effectiveSpeculativeDraftPath
import com.example.llamadroid.service.speculativeDraftModelsFor
import com.example.llamadroid.service.stableKey
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppChoiceRow
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import com.example.llamadroid.ui.distributed.common.DistributedHelperListItem
import com.example.llamadroid.ui.distributed.common.DistributedHowItWorksStrip
import com.example.llamadroid.ui.distributed.common.DistributedNoHelpersState
import com.example.llamadroid.ui.distributed.common.DistributedSearchingState
import com.example.llamadroid.ui.distributed.common.DistributedStatusPanel
import com.example.llamadroid.ui.distributed.common.DistributedTechnicalLogSection
import com.example.llamadroid.ui.distributed.common.DistributedWarningRow
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.util.GGUFParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

internal const val MASTER_CPU_PLACEMENT_ID = -1L

internal sealed interface ModelProbeUiState {
    data object Missing : ModelProbeUiState
    data object Checking : ModelProbeUiState
    data class Ready(val result: LlamaModelProbeResult, val parserLayers: Int?) : ModelProbeUiState
    data class Manual(val result: LlamaModelProbeResult) : ModelProbeUiState
    data class Failed(val message: String, val parserLayers: Int?) : ModelProbeUiState
}

/** Persisted choices that only this screen owns. */
internal object MasterUiPrefs {
    private const val FILE = "distributed_master_ui"
    private const val KEY_PLANNING = "layer_planning"
    private const val KEY_SPLIT = "split_inputs"

    fun layerPlanning(context: Context): DistributedLayerPlanning {
        val stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PLANNING, null)
        return DistributedLayerPlanning.fromStored(stored)
    }

    /** Manual split inputs keyed "MODE:endpoint" (master endpoint is [MASTER_SPLIT_ENDPOINT]). */
    fun splitInputs(context: Context): Map<String, Int> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SPLIT, null)
            ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getInt(it) }
        }.getOrDefault(emptyMap())
    }

    fun setSplitInputs(context: Context, values: Map<String, Int>) {
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value) }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY_SPLIT, json.toString()) }
    }

    fun setLayerPlanning(context: Context, value: DistributedLayerPlanning) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY_PLANNING, value.name) }
    }
}

/** Everything the section composables read or change. Rebuilt per composition; state lives in MutableState holders. */
internal class MasterScreenState(
    val context: Context,
    val resources: android.content.res.Resources,
    val walkthroughTargets: com.example.llamadroid.ui.walkthrough.WalkthroughTargets?,
    val navController: NavController,
    val db: AppDatabase,
    val settingsRepo: SettingsRepository,
    val coroutineScope: CoroutineScope,
    val savedWorkers: List<SavedWorker>,
    val enabledWorkers: List<SavedWorker>,
    val isServerRunning: Boolean,
    val distributedRuntimeState: ServerState,
    val llmModels: List<ModelEntity>,
    val mmprojModels: List<ModelEntity>,
    val selectedModel: ModelEntity?,
    val draftModels: List<ModelEntity>,
    val draftModel: ModelEntity?,
    val availableModels: List<ModelEntity>,
    val allModels: List<ModelEntity>,
    val modelProbeState: ModelProbeUiState,
    val effectiveLayerResult: LlamaModelProbeResult?,
    val totalRamMB: Int,
    val availableRamMB: Int,
    val newDiscoveredWorkers: List<NsdServiceInfo>,
    val deviceSlots: List<String>,
    val fitValidationError: String?,
    val mainPlacementMode: MainModelPlacementMode,
    val savedCommands: List<SavedCommandEntity>,
    val clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    val planProfile: DistributedLlamaLaunchProfile?,
    private val showModelPickerState: MutableState<Boolean>,
    private val showAddWorkerDialogState: MutableState<Boolean>,
    private val showEditWorkerDialogState: MutableState<Boolean>,
    private val workerToEditState: MutableState<SavedWorker?>,
    private val isStartingState: MutableState<Boolean>,
    private val isPreviewingState: MutableState<Boolean>,
    private val showSaveCommandDialogState: MutableState<Boolean>,
    private val showLoadCommandDialogState: MutableState<Boolean>,
    private val commandNameToSaveState: MutableState<String>,
    private val serverPortTextState: MutableState<String>,
    private val showDraftModelPickerState: MutableState<Boolean>,
    private val speculativeModeMenuExpandedState: MutableState<Boolean>,
    private val draftDeviceMenuExpandedState: MutableState<Boolean>,
    private val mainPlacementModeNameState: MutableState<String>,
    private val mainResidentWorkerIdState: MutableState<Long?>,
    private val includeMasterInMainState: MutableState<Boolean>,
    private val selectedMainWorkerIdsState: MutableState<List<Long>>,
    private val kvPlacementModeNameState: MutableState<String>,
    private val kvHostKeyState: MutableState<String?>,
    private val selectedDraftWorkerIdsState: MutableState<List<Long>>,
    private val selectedMmprojPathState: MutableState<String?>,
    private val mmprojPlacementNameState: MutableState<String>,
    private val showMmprojPickerState: MutableState<Boolean>,
    private val modelProbeAttemptState: MutableIntState,
    private val showManualLayerInputState: MutableState<Boolean>,
    private val manualLayerTextState: MutableState<String>,
    private val manualTransformerBlocksState: MutableState<Int?>,
    private val enableNetworkAccessState: MutableState<Boolean>,
    private val masterRamSliderState: MutableFloatState,
    private val isEditingCommandState: MutableState<Boolean>,
    private val editedCommandState: MutableState<String>,
    private val masterRamTextState: MutableState<String>,
    private val layerPlanningState: MutableState<DistributedLayerPlanning>,
    private val splitInputsState: MutableState<Map<String, Int>>,
    private val resolveLaunch: suspend () -> ResolvedDistributedLaunch,
    private val buildCommand: suspend () -> String
) {
    var showModelPicker: Boolean by showModelPickerState
    var showAddWorkerDialog: Boolean by showAddWorkerDialogState
    var showEditWorkerDialog: Boolean by showEditWorkerDialogState
    var workerToEdit: SavedWorker? by workerToEditState
    var isStarting: Boolean by isStartingState
    var isPreviewing: Boolean by isPreviewingState
    var showSaveCommandDialog: Boolean by showSaveCommandDialogState
    var showLoadCommandDialog: Boolean by showLoadCommandDialogState
    var commandNameToSave: String by commandNameToSaveState
    var serverPortText: String by serverPortTextState
    var showDraftModelPicker: Boolean by showDraftModelPickerState
    var speculativeModeMenuExpanded: Boolean by speculativeModeMenuExpandedState
    var draftDeviceMenuExpanded: Boolean by draftDeviceMenuExpandedState
    var mainPlacementModeName: String by mainPlacementModeNameState
    var mainResidentWorkerId: Long? by mainResidentWorkerIdState
    var includeMasterInMain: Boolean by includeMasterInMainState
    var selectedMainWorkerIds: List<Long> by selectedMainWorkerIdsState
    var kvPlacementModeName: String by kvPlacementModeNameState
    var kvHostKey: String? by kvHostKeyState
    var selectedDraftWorkerIds: List<Long> by selectedDraftWorkerIdsState
    var selectedMmprojPath: String? by selectedMmprojPathState
    var mmprojPlacementName: String by mmprojPlacementNameState
    var showMmprojPicker: Boolean by showMmprojPickerState
    var modelProbeAttempt: Int by modelProbeAttemptState
    var showManualLayerInput: Boolean by showManualLayerInputState
    var manualLayerText: String by manualLayerTextState
    var manualTransformerBlocks: Int? by manualTransformerBlocksState
    var enableNetworkAccess: Boolean by enableNetworkAccessState
    var masterRamSlider: Float by masterRamSliderState
    var isEditingCommand: Boolean by isEditingCommandState
    var editedCommand: String by editedCommandState
    var masterRamText: String by masterRamTextState
    var layerPlanning: DistributedLayerPlanning by layerPlanningState
        private set

    fun updateLayerPlanning(value: DistributedLayerPlanning) {
        layerPlanningState.value = value
        MasterUiPrefs.setLayerPlanning(context, value)
    }

    /** Devices that get an input row in PERCENT / LAYERS mode, in launch order (master first). */
    val manualSplitDevices: List<ManualSplitDevice>
        get() = manualSplitDevicesFor(
            distributed = mainPlacementMode == MainModelPlacementMode.DISTRIBUTED,
            includeMaster = includeMasterInMain,
            workers = enabledWorkers.filter { it.id in selectedMainWorkerIds },
            masterName = resources.getString(R.string.dist_master_cpu)
        )

    fun manualSplitValue(device: ManualSplitDevice): Int? =
        splitInputsState.value[manualSplitKey(layerPlanning, device.endpoint)]

    fun updateManualSplitValue(device: ManualSplitDevice, value: Int?) {
        val key = manualSplitKey(layerPlanning, device.endpoint)
        val next = splitInputsState.value.toMutableMap()
        if (value == null) next.remove(key) else next[key] = value
        splitInputsState.value = next
        MasterUiPrefs.setSplitInputs(context, next)
    }

    /** Null when the current mode is not manual, or the layer count is not known yet. */
    val manualSplitResult: DistributedManualSplit.Result?
        get() {
            if (!layerPlanning.isManual || mainPlacementMode != MainModelPlacementMode.DISTRIBUTED) return null
            val layers = effectiveLayerResult?.offloadableLayers ?: return null
            return DistributedManualSplit.evaluate(layerPlanning, manualSplitDevices.map(::manualSplitValue), layers)
        }

    /** True while a PERCENT / LAYERS split is incomplete or does not add up; the Start button must stay disabled. */
    val manualSplitBlocksLaunch: Boolean
        get() = manualSplitResult is DistributedManualSplit.Result.Invalid

    suspend fun resolveCurrentDistributedLaunch(): ResolvedDistributedLaunch = resolveLaunch()

    suspend fun buildCurrentDistributedCommand(): String = buildCommand()
}

@Composable
internal fun rememberMasterScreenState(navController: NavController): MasterScreenState {
    val context = LocalContext.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val resources = LocalResources.current
    val db = remember { AppDatabase.getDatabase(context) }
    val settingsRepo = remember { com.example.llamadroid.data.SettingsRepository(context) }
    val nativeBinarySelection by settingsRepo.llmNativeBinarySelection.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val layerPlanningState = remember { mutableStateOf(MasterUiPrefs.layerPlanning(context)) }
    val splitInputsState = remember { mutableStateOf(MasterUiPrefs.splitInputs(context)) }
    val layerPlanning by layerPlanningState
    val planProfileState = remember { mutableStateOf<DistributedLlamaLaunchProfile?>(null) }
    
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
                saved.assignedProportion,
                saved.altIp,
                saved.preferredInterface
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
    val showModelPickerState = remember { mutableStateOf(false) }
    var showModelPicker by showModelPickerState
    val showAddWorkerDialogState = remember { mutableStateOf(false) }
    var showAddWorkerDialog by showAddWorkerDialogState
    val showEditWorkerDialogState = remember { mutableStateOf(false) }
    var showEditWorkerDialog by showEditWorkerDialogState
    val workerToEditState = remember { mutableStateOf<SavedWorker?>(null) }
    var workerToEdit by workerToEditState
    val isStartingState = remember { mutableStateOf(false) }
    var isStarting by isStartingState
    val isPreviewingState = remember { mutableStateOf(false) }
    var isPreviewing by isPreviewingState
    
    // Custom Commands
    val savedCommands by db.savedCommandDao()
        .getCommandsByScope(SavedCommandScopes.MASTER)
        .collectAsState(initial = emptyList())
    val showSaveCommandDialogState = remember { mutableStateOf(false) }
    var showSaveCommandDialog by showSaveCommandDialogState
    val showLoadCommandDialogState = remember { mutableStateOf(false) }
    var showLoadCommandDialog by showLoadCommandDialogState
    val commandNameToSaveState = remember { mutableStateOf("") }
    var commandNameToSave by commandNameToSaveState
    
    // Model configuration settings
    val contextSize by DistributedService.masterContextSize.collectAsState()
    val serverPort by DistributedService.masterServerPort.collectAsState()
    val serverPortTextState = rememberSaveable(serverPort) { mutableStateOf(serverPort.toString()) }
    var serverPortText by serverPortTextState
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

    // Speculative decoding settings
    val speculativeEnabled by DistributedService.masterSpeculativeEnabled.collectAsState()
    val storedDraftModelPath by DistributedService.masterDraftModelPath.collectAsState()
    val showDraftModelPickerState = remember { mutableStateOf(false) }
    var showDraftModelPicker by showDraftModelPickerState
    val draftMax by DistributedService.masterDraftMax.collectAsState()
    val draftMin by DistributedService.masterDraftMin.collectAsState()
    val draftPMin by DistributedService.masterDraftPMin.collectAsState()
    val draftThreads by DistributedService.masterDraftThreads.collectAsState()
    val draftThreadsBatch by DistributedService.masterDraftThreadsBatch.collectAsState()
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
    val speculativeModeMenuExpandedState = remember { mutableStateOf(false) }
    var speculativeModeMenuExpanded by speculativeModeMenuExpandedState
    val draftDeviceMenuExpandedState = remember { mutableStateOf(false) }
    var draftDeviceMenuExpanded by draftDeviceMenuExpandedState
    val mainPlacementModeNameState = rememberSaveable { mutableStateOf(MainModelPlacementMode.DISTRIBUTED.name) }
    var mainPlacementModeName by mainPlacementModeNameState
    val mainPlacementMode = MainModelPlacementMode.valueOf(mainPlacementModeName)
    val mainResidentWorkerIdState = rememberSaveable { mutableStateOf<Long?>(null) }
    var mainResidentWorkerId by mainResidentWorkerIdState
    val includeMasterInMainState = rememberSaveable { mutableStateOf(true) }
    var includeMasterInMain by includeMasterInMainState
    val selectedMainWorkerIdsState = rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    var selectedMainWorkerIds by selectedMainWorkerIdsState
    val kvPlacementModeNameState = rememberSaveable { mutableStateOf(DistributedKvPlacementMode.DISTRIBUTED_WITH_LAYERS.name) }
    var kvPlacementModeName by kvPlacementModeNameState


    val kvHostKeyState = rememberSaveable { mutableStateOf<String?>(null) }
    var kvHostKey by kvHostKeyState
    val selectedDraftWorkerIdsState = rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    var selectedDraftWorkerIds by selectedDraftWorkerIdsState
    val selectedMmprojPathState = rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMmprojPath by selectedMmprojPathState
    val mmprojPlacementNameState = rememberSaveable { mutableStateOf(MmprojPlacement.MASTER_ACCELERATOR.name) }
    var mmprojPlacementName by mmprojPlacementNameState
    val showMmprojPickerState = remember { mutableStateOf(false) }
    var showMmprojPicker by showMmprojPickerState
    val modelProbeAttemptState = rememberSaveable { mutableIntStateOf(0) }
    var modelProbeAttempt by modelProbeAttemptState
    val showManualLayerInputState = rememberSaveable(selectedModel?.path) { mutableStateOf(false) }
    var showManualLayerInput by showManualLayerInputState
    val manualLayerTextState = rememberSaveable(selectedModel?.path) { mutableStateOf("") }
    var manualLayerText by manualLayerTextState
    val manualTransformerBlocksState = rememberSaveable(selectedModel?.path) { mutableStateOf<Int?>(null) }
    var manualTransformerBlocks by manualTransformerBlocksState

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
    val enableNetworkAccessState = remember { mutableStateOf(true) }
    var enableNetworkAccess by enableNetworkAccessState
    
    // Reset custom command when any parameter changes
    val commandInvalidationArmedState = remember { mutableStateOf(false) }
    var commandInvalidationArmed by commandInvalidationArmedState
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
        manualTransformerBlocks, layerPlanning, splitInputsState.value
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
    
    val masterRamSliderState = remember { mutableFloatStateOf(masterRamMB.toFloat().coerceIn(0f, totalRamMB.toFloat())) }
    var masterRamSlider by masterRamSliderState
    
    // Command editing state
    val isEditingCommandState = remember { mutableStateOf(false) }
    var isEditingCommand by isEditingCommandState
    val editedCommandState = remember { mutableStateOf("") }
    var editedCommand by editedCommandState
    val masterRamTextState = remember { mutableStateOf(masterRamMB.toString()) }
    var masterRamText by masterRamTextState
    
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
            (it.ip == service.host.hostAddress || it.ip == DistributedService.endpointIpFor(service)) &&
                it.port == service.port
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
                kvPlacement = DistributedKvPlacement(kvMode, kvDevice),
                ubatchSize = DistributedPerfSettings.ubatchSize(context),
                workerRttMs = DistributedService.workerRttByAddress(),
                layerPlanning = layerPlanning,
                manualSplit = manualSplitDevicesFor(
                    distributed = mainPlacementMode == MainModelPlacementMode.DISTRIBUTED,
                    includeMaster = includeMasterInMain,
                    workers = selectedMainWorkers,
                    masterName = ""
                ).mapNotNull { device ->
                    splitInputsState.value[manualSplitKey(layerPlanning, device.endpoint)]?.let { device.stableKey to it }
                }.toMap()
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

    // Resolve the plan (debounced, off the main thread) so warnings and the plan line stay current.
    val liveWorkersForPlan by DistributedService.workers.collectAsState()
    val rttKey = liveWorkersForPlan.mapNotNull { w -> w.rttMs?.let { "${w.ip}:${w.port}=${it / 10}" } }
    LaunchedEffect(
        selectedModel?.path, effectiveLayerResult?.offloadableLayers, enabledWorkers, mainPlacementModeName,
        mainResidentWorkerId, includeMasterInMain, selectedMainWorkerIds, masterRamSlider.toInt(),
        layerPlanning, splitInputsState.value, kvPlacementModeName, rttKey, isServerRunning
    ) {
        if (isServerRunning) return@LaunchedEffect
        delay(400)
        planProfileState.value = runCatching { resolveCurrentDistributedProfile() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
    }

    return MasterScreenState(
        context = context,
        resources = resources,
        walkthroughTargets = walkthroughTargets,
        navController = navController,
        db = db,
        settingsRepo = settingsRepo,
        coroutineScope = coroutineScope,
        savedWorkers = savedWorkers,
        enabledWorkers = enabledWorkers,
        isServerRunning = isServerRunning,
        distributedRuntimeState = distributedRuntimeState,
        llmModels = llmModels,
        mmprojModels = mmprojModels,
        selectedModel = selectedModel,
        draftModels = draftModels,
        draftModel = draftModel,
        availableModels = availableModels,
        allModels = allModels,
        modelProbeState = modelProbeState,
        effectiveLayerResult = effectiveLayerResult,
        totalRamMB = totalRamMB,
        availableRamMB = availableRamMB,
        newDiscoveredWorkers = newDiscoveredWorkers,
        deviceSlots = deviceSlots,
        fitValidationError = fitValidationError,
        mainPlacementMode = mainPlacementMode,
        savedCommands = savedCommands,
        clipboardManager = clipboardManager,
        planProfile = planProfileState.value,
        showModelPickerState = showModelPickerState,
        showAddWorkerDialogState = showAddWorkerDialogState,
        showEditWorkerDialogState = showEditWorkerDialogState,
        workerToEditState = workerToEditState,
        isStartingState = isStartingState,
        isPreviewingState = isPreviewingState,
        showSaveCommandDialogState = showSaveCommandDialogState,
        showLoadCommandDialogState = showLoadCommandDialogState,
        commandNameToSaveState = commandNameToSaveState,
        serverPortTextState = serverPortTextState,
        showDraftModelPickerState = showDraftModelPickerState,
        speculativeModeMenuExpandedState = speculativeModeMenuExpandedState,
        draftDeviceMenuExpandedState = draftDeviceMenuExpandedState,
        mainPlacementModeNameState = mainPlacementModeNameState,
        mainResidentWorkerIdState = mainResidentWorkerIdState,
        includeMasterInMainState = includeMasterInMainState,
        selectedMainWorkerIdsState = selectedMainWorkerIdsState,
        kvPlacementModeNameState = kvPlacementModeNameState,
        kvHostKeyState = kvHostKeyState,
        selectedDraftWorkerIdsState = selectedDraftWorkerIdsState,
        selectedMmprojPathState = selectedMmprojPathState,
        mmprojPlacementNameState = mmprojPlacementNameState,
        showMmprojPickerState = showMmprojPickerState,
        modelProbeAttemptState = modelProbeAttemptState,
        showManualLayerInputState = showManualLayerInputState,
        manualLayerTextState = manualLayerTextState,
        manualTransformerBlocksState = manualTransformerBlocksState,
        enableNetworkAccessState = enableNetworkAccessState,
        masterRamSliderState = masterRamSliderState,
        isEditingCommandState = isEditingCommandState,
        editedCommandState = editedCommandState,
        masterRamTextState = masterRamTextState,
        layerPlanningState = layerPlanningState,
        splitInputsState = splitInputsState,
        resolveLaunch = { resolveCurrentDistributedLaunch() },
        buildCommand = { buildCurrentDistributedCommand() }
    )
}
