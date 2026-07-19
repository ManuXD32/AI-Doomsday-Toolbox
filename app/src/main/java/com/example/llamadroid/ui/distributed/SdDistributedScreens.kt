@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.llamadroid.ui.distributed

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_VID_GEN
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedTemplateEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.data.db.hasSdCapability
import com.example.llamadroid.data.db.parseSdCapabilities
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdModelFamily
import com.example.llamadroid.sd.SdModelFamilySpec
import com.example.llamadroid.sd.effectiveSdCompatProfiles
import com.example.llamadroid.sd.isSdImageMainModel
import com.example.llamadroid.sd.matchesSdFamily
import com.example.llamadroid.sd.resolvedSdFamily
import com.example.llamadroid.sd.resolveSdFamilySpec
import com.example.llamadroid.service.SDConfig
import com.example.llamadroid.service.SDGenerationState
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SDModeStateHolder
import com.example.llamadroid.service.SDUpscaleConfig
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.SdCacheMode
import com.example.llamadroid.service.SdCacheScmPolicy
import com.example.llamadroid.service.SdDistributedPlacementMode
import com.example.llamadroid.service.SdDistributedPlacementPlan
import com.example.llamadroid.service.SdDistributedRuntimeConfig
import com.example.llamadroid.service.SdDistributedService
import com.example.llamadroid.service.SdDistributedSplitMode
import com.example.llamadroid.service.SdDistributedWorkerRuntime
import com.example.llamadroid.service.SdDistributedModules
import com.example.llamadroid.service.SdDistributedAutoRamScope
import com.example.llamadroid.service.StableDiffusionService
import com.example.llamadroid.service.VideoGenerationConfig
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoGenerationService
import com.example.llamadroid.service.VideoGenerationState
import com.example.llamadroid.service.VideoGenerationStateHolder
import com.example.llamadroid.service.assignmentsByRpc
import com.example.llamadroid.service.buildRamWeightedSdPlacementPlan
import com.example.llamadroid.service.buildSdDistributedPreviewArgs
import com.example.llamadroid.service.loadGeneratedVideoMetadata
import com.example.llamadroid.service.settingsFromJson
import com.example.llamadroid.service.settingsToJson
import com.example.llamadroid.service.toMasterPlanningWorker
import com.example.llamadroid.service.toPlanningWorkers
import com.example.llamadroid.service.toRuntimeConfig
import com.example.llamadroid.service.toRamPlannerOptions
import com.example.llamadroid.service.toSdPlanningWorkers
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SdDistributedHubScreen(navController: NavController) {
    SdDistributedPage(
        navController = navController,
        title = stringResource(R.string.sd_dist_title),
        subtitle = stringResource(R.string.sd_dist_subtitle)
    ) {
        MediaPipelinePreview()
        HubActionCard(
            icon = Icons.Default.PlayArrow,
            title = stringResource(R.string.sd_dist_hub_worker_title),
            description = stringResource(R.string.sd_dist_hub_worker_desc),
            action = stringResource(R.string.sd_dist_open_worker),
            onClick = { navController.navigate(Screen.SdDistributedWorker.route) }
        )
        HubActionCard(
            icon = Icons.Default.Settings,
            title = stringResource(R.string.sd_dist_hub_master_title),
            description = stringResource(R.string.sd_dist_hub_master_desc),
            action = stringResource(R.string.sd_dist_open_master),
            onClick = { navController.navigate(Screen.SdDistributedMaster.route) }
        )
        HubActionCard(
            icon = Icons.Default.Share,
            title = stringResource(R.string.sd_dist_network_title),
            description = stringResource(R.string.sd_dist_network_subtitle),
            action = stringResource(R.string.sd_dist_view_network),
            onClick = { navController.navigate(Screen.SdDistributedNetwork.route) }
        )
        HubActionCard(
            icon = Icons.Default.Collections,
            title = stringResource(R.string.sd_dist_gallery_title),
            description = stringResource(R.string.sd_dist_gallery_subtitle),
            action = stringResource(R.string.sd_dist_open_gallery),
            onClick = { navController.navigate(Screen.SdDistributedGallery.route) }
        )
        WarningBand(text = stringResource(R.string.sd_dist_security_warning))
    }
}

@Composable
fun SdDistributedGalleryScreen(navController: NavController) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(SdGeneratedMediaFilter.ALL) }
    var refreshNonce by remember { mutableStateOf(0) }
    var mediaItems by remember { mutableStateOf<List<SdGeneratedMediaItem>>(emptyList()) }

    LaunchedEffect(refreshNonce) {
        mediaItems = withContext(Dispatchers.IO) { scanSdGeneratedMedia(context) }
    }

    val filteredItems = remember(mediaItems, filter) {
        when (filter) {
            SdGeneratedMediaFilter.IMAGES -> mediaItems.filter { it.kind == SdGeneratedMediaKind.IMAGE }
            SdGeneratedMediaFilter.VIDEOS -> mediaItems.filter { it.kind == SdGeneratedMediaKind.VIDEO }
            SdGeneratedMediaFilter.ALL -> mediaItems
        }
    }

    SdDistributedPage(
        navController = navController,
        title = stringResource(R.string.sd_dist_gallery_title),
        subtitle = stringResource(R.string.sd_dist_gallery_subtitle)
    ) {
        AppSectionCard(tonalAccent = RenderFarmPalette.violet.copy(alpha = 0.12f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FarmStatusChip(stringResource(R.string.sd_dist_gallery_all), mediaItems.size.toString(), RenderFarmPalette.violet)
                    FarmStatusChip(stringResource(R.string.sd_dist_gallery_images), mediaItems.count { it.kind == SdGeneratedMediaKind.IMAGE }.toString(), RenderFarmPalette.coral)
                    FarmStatusChip(stringResource(R.string.sd_dist_gallery_videos), mediaItems.count { it.kind == SdGeneratedMediaKind.VIDEO }.toString(), RenderFarmPalette.lime)
                }
                IconButton(onClick = { refreshNonce++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SdGeneratedMediaFilter.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = filter == item,
                        onClick = { filter = item },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SdGeneratedMediaFilter.entries.size)
                    ) {
                        Text(
                            when (item) {
                                SdGeneratedMediaFilter.ALL -> stringResource(R.string.sd_dist_gallery_all)
                                SdGeneratedMediaFilter.IMAGES -> stringResource(R.string.sd_dist_gallery_images)
                                SdGeneratedMediaFilter.VIDEOS -> stringResource(R.string.sd_dist_gallery_videos)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (filteredItems.isEmpty()) {
            AppSectionCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Collections, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = stringResource(R.string.sd_dist_gallery_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp, max = 900.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredItems, key = { it.file.absolutePath }) { item ->
                    SdGeneratedMediaCard(
                        item = item,
                        onOpen = { openSdGeneratedMedia(context, item) },
                        onShare = { shareSdGeneratedMedia(context, item) }
                    )
                }
            }
        }
    }
}

@Composable
fun SdDistributedWorkerScreen(navController: NavController) {
    val context = LocalContext.current
    val isRunning by SdDistributedService.isWorkerRunning.collectAsState()
    val localIp by SdDistributedService.localIp.collectAsState()
    val connections by SdDistributedService.connectionCount.collectAsState()
    val logs by SdDistributedService.logs.collectAsState()
    val currentHost by SdDistributedService.workerHost.collectAsState()
    val currentPort by SdDistributedService.workerPort.collectAsState()
    val currentRam by SdDistributedService.workerRamMB.collectAsState()
    val currentThreads by SdDistributedService.workerThreads.collectAsState()
    val currentName by SdDistributedService.workerDeviceName.collectAsState()
    val currentCacheEnabled by SdDistributedService.workerCacheEnabled.collectAsState()

    var hostDraft by remember(currentHost) { mutableStateOf(currentHost) }
    var portDraft by remember(currentPort) { mutableStateOf(currentPort.toString()) }
    var ramDraft by remember(currentRam) { mutableStateOf(currentRam.toString()) }
    var threadsDraft by remember(currentThreads) { mutableStateOf(currentThreads.toString()) }
    var deviceName by remember(currentName) { mutableStateOf(currentName) }
    var cacheEnabled by remember(currentCacheEnabled) { mutableStateOf(currentCacheEnabled) }

    LaunchedEffect(Unit) {
        SdDistributedService.loadWorkerSettings(context)
    }

    SdDistributedPage(
        navController = navController,
        title = stringResource(R.string.sd_dist_worker_title),
        subtitle = stringResource(R.string.sd_dist_worker_subtitle)
    ) {
        WorkerStatusCard(
            isRunning = isRunning,
            address = "${localIp ?: "0.0.0.0"}:${portDraft.toIntOrNull() ?: SdDistributedService.RPC_DEFAULT_PORT}",
            connections = connections
        )
        AppSectionCard(tonalAccent = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)) {
            Text(
                text = stringResource(R.string.sd_dist_worker_resources),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            NumberSliderField(
                label = stringResource(R.string.sd_dist_threads_to_share),
                valueDraft = threadsDraft,
                onValueDraftChange = { threadsDraft = it },
                range = 1f..32f
            )
            NumberSliderField(
                label = stringResource(R.string.sd_dist_ram_to_share),
                valueDraft = ramDraft,
                onValueDraftChange = { ramDraft = it },
                range = 512f..65536f,
                suffix = stringResource(R.string.sd_dist_mb_suffix)
            )
            WarningBand(text = stringResource(R.string.sd_dist_worker_ram_budget_warning))
            OutlinedTextField(
                value = hostDraft,
                onValueChange = { hostDraft = it },
                label = { Text(stringResource(R.string.sd_dist_worker_bind_host)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = portDraft,
                onValueChange = { portDraft = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.sd_dist_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text(stringResource(R.string.sd_dist_device_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = RenderFarmPalette.lime.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.sd_dist_worker_cache_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.sd_dist_worker_cache_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = cacheEnabled,
                        onCheckedChange = {
                            cacheEnabled = it
                            SdDistributedService.setWorkerCacheEnabled(context, it)
                        },
                        enabled = !isRunning
                    )
                    Text(
                        text = stringResource(R.string.sd_dist_worker_cache_enable),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(
                    onClick = { SdDistributedService.clearWorkerCache(context) },
                    enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_dist_worker_cache_clear), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        SdDistributedService.startWorker(
                            context = context,
                            host = hostDraft.ifBlank { "0.0.0.0" },
                            port = portDraft.toIntOrNull() ?: SdDistributedService.RPC_DEFAULT_PORT,
                            ramMB = ramDraft.toIntOrNull() ?: 4096,
                            threads = threadsDraft.toIntOrNull() ?: 4,
                            deviceName = deviceName.ifBlank { context.getString(R.string.sd_dist_default_worker_name) },
                            cacheEnabled = cacheEnabled
                        )
                    },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_dist_start_worker), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { SdDistributedService.stopWorker(context) },
                    enabled = isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_dist_stop_worker), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        WarningBand(text = stringResource(R.string.sd_dist_security_warning))
        LogsCard(title = stringResource(R.string.sd_dist_logs), logs = logs)
    }
}

@Composable
fun SdDistributedMasterScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = db.sdDistributedDao()
    val workers by dao.observeWorkersOrdered().collectAsState(initial = emptyList())
    val settingsEntity by dao.observeMasterSettings().collectAsState(initial = null)
    val templates by dao.observeTemplates().collectAsState(initial = emptyList())
    val imageModels by db.modelDao().getModelsByTypes(listOf(ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION)).collectAsState(initial = emptyList())
    val upscalerModels by db.modelDao().getModelsByType(ModelType.SD_UPSCALER).collectAsState(initial = emptyList())
    val videoModelsRaw by db.modelDao().getModelsByType(ModelType.SD_DIFFUSION).collectAsState(initial = emptyList())
    val vaeModels by db.modelDao().getModelsByType(ModelType.SD_VAE).collectAsState(initial = emptyList())
    val taeModels by db.modelDao().getModelsByType(ModelType.SD_TAE).collectAsState(initial = emptyList())
    val clipLModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_L).collectAsState(initial = emptyList())
    val clipGModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_G).collectAsState(initial = emptyList())
    val t5xxlModels by db.modelDao().getModelsByType(ModelType.SD_T5XXL).collectAsState(initial = emptyList())
    val controlNetModels by db.modelDao().getModelsByType(ModelType.SD_CONTROLNET).collectAsState(initial = emptyList())
    val loraModels by db.modelDao().getModelsByType(ModelType.SD_LORA).collectAsState(initial = emptyList())
    val photoMakerModels by db.modelDao().getModelsByType(ModelType.SD_PHOTOMAKER).collectAsState(initial = emptyList())
    val imageSupportModels by db.modelDao().getModelsByTypes(listOf(ModelType.LLM, ModelType.VISION_PROJECTOR)).collectAsState(initial = emptyList())

    val settings = settingsEntity ?: SdDistributedMasterSettingsEntity()
    val enabledWorkers = workers.filter { it.isEnabled }
    val imageMainModels = remember(imageModels) { imageModels.filter { it.isSdImageMainModel() } }
    val videoModels = remember(videoModelsRaw) { videoModelsRaw.filter { it.hasSdCapability(SD_CAPABILITY_VID_GEN) } }

    var hostDraft by remember { mutableStateOf("") }
    var portDraft by remember { mutableStateOf(SdDistributedService.RPC_DEFAULT_PORT.toString()) }
    var nameDraft by remember { mutableStateOf("") }
    var ramDraft by remember { mutableStateOf("4096") }
    var threadsDraft by remember { mutableStateOf("4") }
    var backendDraft by remember { mutableStateOf("") }
    var editingWorkerId by remember { mutableStateOf<Long?>(null) }
    var templateNameDraft by remember { mutableStateOf("") }

    var enabled by remember { mutableStateOf(settings.enabled) }
    var placementMode by remember { mutableStateOf(settings.placementMode.toSdPlacementMode()) }
    var backendSpec by remember { mutableStateOf(settings.backendSpec) }
    var paramsBackend by remember { mutableStateOf(settings.paramsBackendSpec) }
    var autoFit by remember { mutableStateOf(settings.autoFit) }
    var autoRamScope by remember { mutableStateOf(SdDistributedAutoRamScope.fromStoredValue(settings.autoRamScope)) }
    var maxVramEnabled by remember { mutableStateOf(settings.maxVramEnabled) }
    var maxVram by remember { mutableStateOf(settings.maxVramSpec) }
    var splitMode by remember { mutableStateOf(SdDistributedSplitMode.fromCliName(settings.splitMode)) }
    var customFlags by remember { mutableStateOf(settings.customFlags) }

    var prompt by remember { mutableStateOf(settings.prompt) }
    var negativePrompt by remember { mutableStateOf(settings.negativePrompt) }
    var dimensions by remember { mutableStateOf(settings.dimensions) }
    var steps by remember { mutableStateOf(settings.steps) }
    var cfg by remember { mutableStateOf(settings.cfg) }
    var seed by remember { mutableStateOf(settings.seed) }
    var sampler by remember { mutableStateOf(settings.sampler) }
    var scheduler by remember { mutableStateOf(settings.scheduler) }
    var batch by remember { mutableStateOf(settings.batchCount) }
    var clipSkip by remember { mutableStateOf(settings.clipSkip) }
    var strength by remember { mutableStateOf(settings.strength) }
    var frames by remember { mutableStateOf(settings.frames) }
    var fps by remember { mutableStateOf(settings.fps) }
    var runtimeThreads by remember { mutableStateOf(settings.runtimeThreads) }
    var mmap by remember { mutableStateOf(settings.mmap) }
    var diffusionFa by remember { mutableStateOf(settings.diffusionFa) }
    var vaeTiling by remember { mutableStateOf(settings.vaeTiling) }
    var vaeTileSize by remember { mutableStateOf(settings.vaeTileSize) }
    var vaeTileOverlap by remember { mutableStateOf(settings.vaeTileOverlap) }
    var flowShift by remember { mutableStateOf(settings.flowShift) }
    var quantization by remember { mutableStateOf(settings.quantization) }
    var tensorRules by remember { mutableStateOf(settings.tensorRules) }
    var loraStrength by remember { mutableStateOf(settings.loraStrength) }
    var controlStrength by remember { mutableStateOf(settings.controlStrength) }
    var cacheMode by remember { mutableStateOf(settings.cacheMode) }
    var cacheOption by remember { mutableStateOf(settings.cacheOption) }
    var scmMask by remember { mutableStateOf(settings.scmMask) }
    var scmPolicy by remember { mutableStateOf(settings.scmPolicy) }
    var masterContributes by remember { mutableStateOf(settings.masterContributes) }
    var masterDisplayName by remember { mutableStateOf(settings.masterDisplayName) }
    var masterRamDraft by remember { mutableStateOf(settings.masterRamMB.toString()) }
    var masterThreadsDraft by remember { mutableStateOf(settings.masterThreads.toString()) }
    var masterBackendDevice by remember { mutableStateOf(settings.masterBackendDevice) }
    var masterAllowedModules by remember { mutableStateOf(SdDistributedModules.normalizeCsv(settings.masterAllowedModules)) }
    var masterDiffusionShareDraft by remember { mutableStateOf(settings.masterDiffusionSharePercent) }
    var imageWorkflowMode by remember { mutableStateOf(settings.imageWorkflowMode) }
    var imageModelPath by remember { mutableStateOf(settings.imageModelPath) }
    var imageUpscalerModelPath by remember { mutableStateOf(settings.imageUpscalerModelPath) }
    var imageInputPath by remember { mutableStateOf(settings.imageInputPath) }
    var imageVaePath by remember { mutableStateOf(settings.imageVaePath) }
    var imageTaePath by remember { mutableStateOf(settings.imageTaePath) }
    var imageClipLPath by remember { mutableStateOf(settings.imageClipLPath) }
    var imageClipGPath by remember { mutableStateOf(settings.imageClipGPath) }
    var imageT5xxlPath by remember { mutableStateOf(settings.imageT5xxlPath) }
    var imageLlmPath by remember { mutableStateOf(settings.imageLlmPath) }
    var imageLlmVisionPath by remember { mutableStateOf(settings.imageLlmVisionPath) }
    var imagePhotoMakerPath by remember { mutableStateOf(settings.imagePhotoMakerPath) }
    var imageControlNetEnabled by remember { mutableStateOf(settings.imageControlNetEnabled) }
    var imageControlNetPath by remember { mutableStateOf(settings.imageControlNetPath) }
    var imageLoraEnabled by remember { mutableStateOf(settings.imageLoraEnabled) }
    var imageLoraPath by remember { mutableStateOf(settings.imageLoraPath) }
    var imageLoraApplyMode by remember { mutableStateOf(settings.imageLoraApplyMode) }
    var imageCustomFlags by remember { mutableStateOf(settings.imageCustomFlags) }
    var videoWorkflowMode by remember { mutableStateOf(settings.videoWorkflowMode) }
    var videoModelPath by remember { mutableStateOf(settings.videoModelPath) }
    var videoInputPath by remember { mutableStateOf(settings.videoInputPath) }
    var videoUseVae by remember { mutableStateOf(settings.videoUseVae) }
    var videoVaePath by remember { mutableStateOf(settings.videoVaePath) }
    var videoUseT5xxl by remember { mutableStateOf(settings.videoUseT5xxl) }
    var videoT5xxlPath by remember { mutableStateOf(settings.videoT5xxlPath) }
    var videoCustomFlags by remember { mutableStateOf(settings.videoCustomFlags) }
    var launchError by remember { mutableStateOf<String?>(null) }

    val activeImageMode = imageWorkflowMode.toSdMasterImageMode()
    val imageGenerationState by remember(activeImageMode) {
        SDModeStateHolder.getDistributedForMode(activeImageMode).state
    }.collectAsState()
    val imageWorkRunning = imageGenerationState is SDGenerationState.Generating
    val activeVideoMode = videoWorkflowMode.toSdMasterVideoMode()
    val videoGenerationState by remember(activeVideoMode) {
        VideoGenerationStateHolder.getForMode(activeVideoMode, useDistributedStateHolder = true).state
    }.collectAsState()
    val videoWorkRunning = when (videoGenerationState) {
        is VideoGenerationState.Generating,
        is VideoGenerationState.Converting,
        is VideoGenerationState.Copying -> true
        else -> false
    }

    var devicesExpanded by remember { mutableStateOf(settings.devicesExpanded) }
    var plannerExpanded by remember { mutableStateOf(settings.plannerExpanded) }
    var generationExpanded by remember { mutableStateOf(settings.generationExpanded) }
    var imageExpanded by remember { mutableStateOf(settings.imageExpanded) }
    var videoExpanded by remember { mutableStateOf(settings.videoExpanded) }
    var runtimeExpanded by remember { mutableStateOf(settings.runtimeExpanded) }
    var adaptersExpanded by remember { mutableStateOf(settings.adaptersExpanded) }
    var expertExpanded by remember { mutableStateOf(settings.expertExpanded) }

    LaunchedEffect(settingsEntity?.updatedAt) {
        val fresh = settingsEntity ?: return@LaunchedEffect
        enabled = fresh.enabled
        placementMode = fresh.placementMode.toSdPlacementMode()
        backendSpec = fresh.backendSpec
        paramsBackend = fresh.paramsBackendSpec
        autoFit = fresh.autoFit
        autoRamScope = SdDistributedAutoRamScope.fromStoredValue(fresh.autoRamScope)
        maxVramEnabled = fresh.maxVramEnabled
        maxVram = fresh.maxVramSpec
        splitMode = SdDistributedSplitMode.fromCliName(fresh.splitMode)
        customFlags = fresh.customFlags
        prompt = fresh.prompt
        negativePrompt = fresh.negativePrompt
        dimensions = fresh.dimensions
        steps = fresh.steps
        cfg = fresh.cfg
        seed = fresh.seed
        sampler = fresh.sampler
        scheduler = fresh.scheduler
        batch = fresh.batchCount
        clipSkip = fresh.clipSkip
        strength = fresh.strength
        frames = fresh.frames
        fps = fresh.fps
        runtimeThreads = fresh.runtimeThreads
        mmap = fresh.mmap
        diffusionFa = fresh.diffusionFa
        vaeTiling = fresh.vaeTiling
        vaeTileSize = fresh.vaeTileSize
        vaeTileOverlap = fresh.vaeTileOverlap
        flowShift = fresh.flowShift
        quantization = fresh.quantization
        tensorRules = fresh.tensorRules
        loraStrength = fresh.loraStrength
        controlStrength = fresh.controlStrength
        cacheMode = fresh.cacheMode
        cacheOption = fresh.cacheOption
        scmMask = fresh.scmMask
        scmPolicy = fresh.scmPolicy
        masterContributes = fresh.masterContributes
        masterDisplayName = fresh.masterDisplayName
        masterRamDraft = fresh.masterRamMB.toString()
        masterThreadsDraft = fresh.masterThreads.toString()
        masterBackendDevice = fresh.masterBackendDevice
        masterAllowedModules = SdDistributedModules.normalizeCsv(fresh.masterAllowedModules)
        masterDiffusionShareDraft = fresh.masterDiffusionSharePercent
        imageWorkflowMode = fresh.imageWorkflowMode
        imageModelPath = fresh.imageModelPath
        imageUpscalerModelPath = fresh.imageUpscalerModelPath
        imageInputPath = fresh.imageInputPath
        imageVaePath = fresh.imageVaePath
        imageTaePath = fresh.imageTaePath
        imageClipLPath = fresh.imageClipLPath
        imageClipGPath = fresh.imageClipGPath
        imageT5xxlPath = fresh.imageT5xxlPath
        imageLlmPath = fresh.imageLlmPath
        imageLlmVisionPath = fresh.imageLlmVisionPath
        imagePhotoMakerPath = fresh.imagePhotoMakerPath
        imageControlNetEnabled = fresh.imageControlNetEnabled
        imageControlNetPath = fresh.imageControlNetPath
        imageLoraEnabled = fresh.imageLoraEnabled
        imageLoraPath = fresh.imageLoraPath
        imageLoraApplyMode = fresh.imageLoraApplyMode
        imageCustomFlags = fresh.imageCustomFlags
        videoWorkflowMode = fresh.videoWorkflowMode
        videoModelPath = fresh.videoModelPath
        videoInputPath = fresh.videoInputPath
        videoUseVae = fresh.videoUseVae
        videoVaePath = fresh.videoVaePath
        videoUseT5xxl = fresh.videoUseT5xxl
        videoT5xxlPath = fresh.videoT5xxlPath
        videoCustomFlags = fresh.videoCustomFlags
        devicesExpanded = fresh.devicesExpanded
        plannerExpanded = fresh.plannerExpanded
        generationExpanded = fresh.generationExpanded
        imageExpanded = fresh.imageExpanded
        videoExpanded = fresh.videoExpanded
        runtimeExpanded = fresh.runtimeExpanded
        adaptersExpanded = fresh.adaptersExpanded
        expertExpanded = fresh.expertExpanded
    }

    val draftSettings = settings.copy(
        updatedAt = System.currentTimeMillis(),
        enabled = enabled,
        placementMode = placementMode.name,
        backendSpec = backendSpec,
        paramsBackendSpec = paramsBackend,
        autoFit = autoFit,
        autoRamScope = autoRamScope.name,
        maxVramEnabled = maxVramEnabled,
        maxVramSpec = maxVram,
        splitMode = splitMode.cliName,
        customFlags = customFlags,
        prompt = prompt,
        negativePrompt = negativePrompt,
        dimensions = dimensions,
        steps = steps,
        cfg = cfg,
        seed = seed,
        sampler = sampler,
        scheduler = scheduler,
        batchCount = batch,
        clipSkip = clipSkip,
        strength = strength,
        frames = frames,
        fps = fps,
        runtimeThreads = runtimeThreads,
        mmap = mmap,
        diffusionFa = diffusionFa,
        vaeTiling = vaeTiling,
        vaeTileSize = vaeTileSize,
        vaeTileOverlap = vaeTileOverlap,
        flowShift = flowShift,
        quantization = quantization,
        tensorRules = tensorRules,
        loraStrength = loraStrength,
        controlStrength = controlStrength,
        cacheMode = cacheMode,
        cacheOption = cacheOption,
        scmMask = scmMask,
        scmPolicy = scmPolicy,
        masterContributes = masterContributes,
        masterDisplayName = masterDisplayName.ifBlank { stringResource(R.string.sd_dist_this_device) },
        masterRamMB = masterRamDraft.toIntOrNull() ?: 4096,
        masterThreads = masterThreadsDraft.toIntOrNull() ?: 4,
        masterBackendDevice = masterBackendDevice.ifBlank { "cpu" },
        masterAllowedModules = SdDistributedModules.toCsv(masterAllowedModules),
        masterDiffusionSharePercent = masterDiffusionShareDraft.filter(Char::isDigit).take(2),
        imageWorkflowMode = imageWorkflowMode,
        imageModelPath = imageModelPath,
        imageUpscalerModelPath = imageUpscalerModelPath,
        imageInputPath = imageInputPath,
        imageVaePath = imageVaePath,
        imageTaePath = imageTaePath,
        imageClipLPath = imageClipLPath,
        imageClipGPath = imageClipGPath,
        imageT5xxlPath = imageT5xxlPath,
        imageLlmPath = imageLlmPath,
        imageLlmVisionPath = imageLlmVisionPath,
        imagePhotoMakerPath = imagePhotoMakerPath,
        imageControlNetEnabled = imageControlNetEnabled,
        imageControlNetPath = imageControlNetPath,
        imageLoraEnabled = imageLoraEnabled,
        imageLoraPath = imageLoraPath,
        imageLoraApplyMode = imageLoraApplyMode,
        imageCustomFlags = imageCustomFlags,
        videoWorkflowMode = videoWorkflowMode,
        videoModelPath = videoModelPath,
        videoInputPath = videoInputPath,
        videoUseVae = videoUseVae,
        videoVaePath = videoVaePath,
        videoUseT5xxl = videoUseT5xxl,
        videoT5xxlPath = videoT5xxlPath,
        videoCustomFlags = videoCustomFlags,
        devicesExpanded = devicesExpanded,
        plannerExpanded = plannerExpanded,
        generationExpanded = generationExpanded,
        imageExpanded = imageExpanded,
        videoExpanded = videoExpanded,
        runtimeExpanded = runtimeExpanded,
        adaptersExpanded = adaptersExpanded,
        expertExpanded = expertExpanded
    )

    val planningWorkers = draftSettings.toPlanningWorkers(enabledWorkers)
    val autoRamPlan = buildRamWeightedSdPlacementPlan(planningWorkers, draftSettings.toRamPlannerOptions())
    val assignmentByRpc = assignmentsByRpc(autoRamPlan)
    val runtimeConfig = draftSettings.toRuntimeConfig(autoRamPlan)
    val preview = buildSdDistributedPreviewArgs(runtimeConfig).joinToString(" ")
    val imageTemplates = remember(templates) { templates.filter { it.workflowType == SdDistributedTemplateWorkflow.IMAGE.name } }
    val videoTemplates = remember(templates) { templates.filter { it.workflowType == SdDistributedTemplateWorkflow.VIDEO.name } }
    val imageSelectedModel = imageMainModels.firstOrNull { it.path == imageModelPath }
    val imageSelectedUpscaler = upscalerModels.firstOrNull { it.path == imageUpscalerModelPath }
    val videoSelectedModel = videoModels.firstOrNull { it.path == videoModelPath }
    val imageFamilyInfo = imageSelectedModel?.resolvedSdFamily()
    val imageFamily = imageFamilyInfo?.first
    val imageVariant = imageFamilyInfo?.second
    val imageFamilySpec = imageSelectedModel?.resolveSdFamilySpec()
    val imageComponentRoles = imageFamilySpec?.let { spec ->
        listOf(
            SdComponentRole.VAE,
            SdComponentRole.TAE,
            SdComponentRole.CLIP_L,
            SdComponentRole.CLIP_G,
            SdComponentRole.T5XXL,
            SdComponentRole.LLM,
            SdComponentRole.LLM_VISION,
            SdComponentRole.PHOTOMAKER
        ).filter { it in spec.requiredRoles || it in spec.optionalRoles }
    }.orEmpty()
    val imageCompatibleModels = mapOf(
        SdComponentRole.VAE to filterSdDistributedComponents(vaeModels, imageFamily, imageVariant),
        SdComponentRole.TAE to filterSdDistributedComponents(taeModels, imageFamily, imageVariant),
        SdComponentRole.CLIP_L to filterSdDistributedComponents(clipLModels, imageFamily, imageVariant),
        SdComponentRole.CLIP_G to filterSdDistributedComponents(clipGModels, imageFamily, imageVariant),
        SdComponentRole.T5XXL to filterSdDistributedComponents(t5xxlModels, imageFamily, imageVariant),
        SdComponentRole.LLM to filterSdDistributedComponents(
            imageSupportModels.filter { it.type == ModelType.LLM && it.effectiveSdCompatProfiles().isNotEmpty() },
            imageFamily,
            imageVariant
        ),
        SdComponentRole.LLM_VISION to filterSdDistributedComponents(
            imageSupportModels.filter { it.type == ModelType.VISION_PROJECTOR && it.effectiveSdCompatProfiles().isNotEmpty() },
            imageFamily,
            imageVariant
        ),
        SdComponentRole.PHOTOMAKER to filterSdDistributedComponents(photoMakerModels, imageFamily, imageVariant),
        SdComponentRole.CONTROLNET to filterSdDistributedComponents(controlNetModels, imageFamily, imageVariant),
        SdComponentRole.LORA to filterSdDistributedComponents(loraModels, imageFamily, imageVariant)
    )
    val imageSelectedComponentPaths = mapOf(
        SdComponentRole.VAE to imageVaePath,
        SdComponentRole.TAE to imageTaePath,
        SdComponentRole.CLIP_L to imageClipLPath,
        SdComponentRole.CLIP_G to imageClipGPath,
        SdComponentRole.T5XXL to imageT5xxlPath,
        SdComponentRole.LLM to imageLlmPath,
        SdComponentRole.LLM_VISION to imageLlmVisionPath,
        SdComponentRole.PHOTOMAKER to imagePhotoMakerPath,
        SdComponentRole.CONTROLNET to imageControlNetPath,
        SdComponentRole.LORA to imageLoraPath
    )
    val imageMissingRequiredRoles = imageFamilySpec?.requiredRoles?.filter { role ->
        imageSelectedComponentPaths[role].isNullOrBlank()
    }.orEmpty()
    val imageComponentChoices = SdDistributedImageComponentChoices(
        familyLabel = imageFamily?.storedValue.orEmpty(),
        spec = imageFamilySpec,
        roles = imageComponentRoles,
        compatibleModels = imageCompatibleModels,
        selectedPaths = imageSelectedComponentPaths,
        controlNetEnabled = imageControlNetEnabled,
        loraEnabled = imageLoraEnabled,
        loraApplyMode = imageLoraApplyMode
    )

    fun saveDraftSettings() {
        scope.launch(Dispatchers.IO) { dao.upsertMasterSettings(draftSettings) }
        SdDistributedService.setRuntimeConfig(runtimeConfig)
        SdDistributedService.setActiveWorkers(
            planningWorkers.map {
                SdDistributedWorkerRuntime(
                    host = it.host,
                    port = it.port,
                    deviceName = it.displayName,
                    ramMB = it.ramMB,
                    threads = it.threads,
                    backendDevice = it.backendDevice,
                    rpcName = it.rpcName,
                    isLocalMaster = it.isLocalMaster,
                    assignedModules = assignmentByRpc[it.rpcName].orEmpty().map { assignment -> assignment.module },
                    plannedAssignments = assignmentByRpc[it.rpcName].orEmpty()
                )
            }
        )
    }

    fun startImageRun() {
        saveDraftSettings()
        val mode = imageWorkflowMode.toSdMasterImageMode()
        val (width, height) = parseSdDimensions(dimensions, 512, 512)
        val outputDir = File(context.filesDir, "sd_output").apply { mkdirs() }
        val subfolder = when (mode) {
            SDMode.TXT2IMG -> "txt2img"
            SDMode.IMG2IMG -> "img2img"
            SDMode.UPSCALE -> "upscaled"
        }
        val outputFile = File(File(outputDir, subfolder).apply { mkdirs() }, "sd_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png")
        val samplerMethod = SamplingMethod.entries.firstOrNull { it.cliName == sampler || it.name == sampler } ?: SamplingMethod.EULER_A
        val parsedCacheMode = SdCacheMode.fromStoredValue(cacheMode)
        val parsedScmPolicy = SdCacheScmPolicy.fromStoredValue(scmPolicy)

        launchError = when {
            mode != SDMode.UPSCALE && imageModelPath.isBlank() -> context.getString(R.string.sd_dist_error_select_image_model)
            mode != SDMode.UPSCALE && prompt.isBlank() -> context.getString(R.string.sd_dist_error_prompt_required)
            mode != SDMode.UPSCALE && imageMissingRequiredRoles.isNotEmpty() -> context.getString(
                R.string.imagegen_error_missing_required_components,
                imageMissingRequiredRoles.joinToString(", ") { sdDistributedComponentRoleLabel(context, it) }
            )
            mode == SDMode.IMG2IMG && imageInputPath.isBlank() -> context.getString(R.string.sd_dist_error_input_image_required)
            mode == SDMode.UPSCALE && imageUpscalerModelPath.isBlank() -> context.getString(R.string.sd_dist_error_select_upscaler_model)
            mode == SDMode.UPSCALE && imageInputPath.isBlank() -> context.getString(R.string.sd_dist_error_input_image_required)
            mode == SDMode.IMG2IMG && imageControlNetEnabled && imageControlNetPath.isBlank() -> context.getString(
                R.string.imagegen_error_missing_required_components,
                sdDistributedComponentRoleLabel(context, SdComponentRole.CONTROLNET)
            )
            imageLoraEnabled && imageLoraPath.isBlank() -> context.getString(
                R.string.imagegen_error_missing_required_components,
                sdDistributedComponentRoleLabel(context, SdComponentRole.LORA)
            )
            else -> null
        }
        if (launchError != null) return

        if (mode == SDMode.UPSCALE) {
            val config = SDUpscaleConfig(
                modelPath = imageUpscalerModelPath,
                inputImagePath = imageInputPath,
                outputPath = outputFile.absolutePath,
                threads = runtimeThreads.toIntOrNull() ?: -1,
                sdParamsBackendMode = imageSelectedUpscaler?.sdParamsBackendMode ?: "auto",
                sdRuntimeBackendMode = imageSelectedUpscaler?.sdRuntimeBackendMode ?: "auto",
                maxVramCpuGiB = "",
                distributedRuntime = runtimeConfig,
                customFlags = imageCustomFlags
            )
            ContextCompat.startForegroundService(
                context,
                StableDiffusionService.createStartUpscaleIntent(
                    context,
                    config,
                    useDistributedStateHolder = true
                )
            )
        } else {
            val config = SDConfig(
                mode = mode,
                modelPath = imageModelPath,
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = width,
                height = height,
                steps = steps.toIntOrNull() ?: 20,
                cfgScale = cfg.toFloatOrNull() ?: 7.0f,
                seed = seed.toLongOrNull() ?: -1L,
                samplingMethod = samplerMethod,
                outputPath = outputFile.absolutePath,
                initImage = imageInputPath.takeIf { mode == SDMode.IMG2IMG },
                strength = strength.toFloatOrNull() ?: 0.75f,
                threads = runtimeThreads.toIntOrNull() ?: -1,
                vaeTiling = vaeTiling,
                vaeTileOverlap = vaeTileOverlap.toFloatOrNull() ?: 0f,
                vaeTileSize = vaeTileSize.ifBlank { "32x32" },
                tensorTypeRules = tensorRules,
                modelFamily = imageFamily?.storedValue,
                modelVariant = imageVariant,
                vaePath = imageVaePath.ifBlank { null },
                taePath = imageTaePath.ifBlank { null },
                clipLPath = imageClipLPath.ifBlank { null },
                clipGPath = imageClipGPath.ifBlank { null },
                t5xxlPath = imageT5xxlPath.ifBlank { null },
                llmPath = imageLlmPath.ifBlank { null },
                llmVisionPath = imageLlmVisionPath.ifBlank { null },
                photoMakerPath = imagePhotoMakerPath.ifBlank { null },
                controlNetPath = if (imageControlNetEnabled) imageControlNetPath.ifBlank { null } else null,
                controlImagePath = if (imageControlNetEnabled && mode == SDMode.IMG2IMG) imageInputPath.ifBlank { null } else null,
                controlStrength = controlStrength.toFloatOrNull() ?: 0.9f,
                loraPath = if (imageLoraEnabled) imageLoraPath.ifBlank { null } else null,
                loraStrength = loraStrength.toFloatOrNull() ?: 1.0f,
                loraApplyMode = if (imageLoraEnabled) SdLoraApplyMode.fromStoredValue(imageLoraApplyMode) else null,
                cacheMode = parsedCacheMode,
                cacheOption = if (parsedCacheMode != null) cacheOption else "",
                scmMask = if (parsedCacheMode == SdCacheMode.CACHE_DIT) scmMask else "",
                scmPolicy = if (parsedCacheMode == SdCacheMode.CACHE_DIT) parsedScmPolicy else null,
                flowShift = flowShift.toFloatOrNull(),
                diffusionFa = diffusionFa,
                mmap = mmap,
                sdParamsBackendMode = imageSelectedModel?.sdParamsBackendMode ?: "auto",
                sdRuntimeBackendMode = imageSelectedModel?.sdRuntimeBackendMode ?: "auto",
                quantizationType = quantization,
                distributedRuntime = runtimeConfig,
                customFlags = imageCustomFlags
            )
            ContextCompat.startForegroundService(
                context,
                StableDiffusionService.createStartIntent(
                    context,
                    config,
                    useDistributedStateHolder = true
                )
            )
        }
    }

    fun cancelImageRun() {
        context.startService(
            StableDiffusionService.createCancelModeIntent(
                context,
                imageWorkflowMode.toSdMasterImageMode(),
                useDistributedStateHolder = true
            )
        )
    }

    fun startVideoRun() {
        saveDraftSettings()
        val mode = videoWorkflowMode.toSdMasterVideoMode()
        val (width, height) = parseSdDimensions(dimensions, 480, 832)
        val outputDir = File(context.filesDir, "video_gen_output").apply { mkdirs() }
        val modeDir = File(outputDir, mode.folderName).apply { mkdirs() }
        val baseName = "video_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        val samplerMethod = SamplingMethod.entries.firstOrNull { it.cliName == sampler || it.name == sampler } ?: SamplingMethod.EULER
        val parsedCacheMode = SdCacheMode.fromStoredValue(cacheMode)
        val parsedScmPolicy = SdCacheScmPolicy.fromStoredValue(scmPolicy)

        launchError = when {
            videoModelPath.isBlank() -> context.getString(R.string.sd_dist_error_select_video_model)
            prompt.isBlank() -> context.getString(R.string.sd_dist_error_prompt_required)
            mode == VideoGenerationMode.IMG2VID && videoInputPath.isBlank() -> context.getString(R.string.sd_dist_error_input_image_required)
            videoUseVae && videoVaePath.isBlank() -> context.getString(
                R.string.imagegen_error_missing_required_components,
                sdDistributedComponentRoleLabel(context, SdComponentRole.VAE)
            )
            videoUseT5xxl && videoT5xxlPath.isBlank() -> context.getString(
                R.string.imagegen_error_missing_required_components,
                sdDistributedComponentRoleLabel(context, SdComponentRole.T5XXL)
            )
            else -> null
        }
        if (launchError != null) return

        val config = VideoGenerationConfig(
            mode = mode,
            prompt = prompt,
            negativePrompt = negativePrompt,
            diffusionModelPath = videoModelPath,
            outputAviPath = File(modeDir, "$baseName.avi").absolutePath,
            outputMp4Path = File(modeDir, "$baseName.mp4").absolutePath,
            metadataPath = File(modeDir, "$baseName.json").absolutePath,
            initImagePath = videoInputPath.takeIf { mode == VideoGenerationMode.IMG2VID },
            useVae = videoUseVae,
            vaePath = if (videoUseVae) videoVaePath.ifBlank { null } else null,
            useT5xxl = videoUseT5xxl,
            t5xxlPath = if (videoUseT5xxl) videoT5xxlPath.ifBlank { null } else null,
            videoFrames = frames.toIntOrNull() ?: 8,
            fps = fps.toIntOrNull() ?: 5,
            width = width,
            height = height,
            steps = steps.toIntOrNull() ?: 18,
            cfgScale = cfg.toFloatOrNull() ?: 6.0f,
            flowShift = flowShift.toFloatOrNull(),
            samplingMethod = samplerMethod,
            cacheMode = parsedCacheMode,
            cacheOption = if (parsedCacheMode != null) cacheOption else "",
            scmMask = if (parsedCacheMode == SdCacheMode.CACHE_DIT) scmMask else "",
            scmPolicy = if (parsedCacheMode == SdCacheMode.CACHE_DIT) parsedScmPolicy else null,
            vaeTiling = vaeTiling,
            vaeTileSize = vaeTileSize.ifBlank { "24x24" },
            diffusionFa = diffusionFa,
            mmap = mmap,
            threads = runtimeThreads.toIntOrNull() ?: -1,
            sdParamsBackendMode = videoSelectedModel?.sdParamsBackendMode ?: "auto",
            sdRuntimeBackendMode = videoSelectedModel?.sdRuntimeBackendMode ?: "auto",
            maxVramCpuGiB = "",
            distributedRuntime = runtimeConfig,
            customFlags = videoCustomFlags
        )
        ContextCompat.startForegroundService(
            context,
            VideoGenerationService.createStartIntent(
                context,
                config,
                useDistributedStateHolder = true
            )
        )
    }

    fun cancelVideoRun() {
        context.startService(
            VideoGenerationService.createCancelIntent(
                context,
                videoWorkflowMode.toSdMasterVideoMode(),
                useDistributedStateHolder = true
            )
        )
    }

    LaunchedEffect(planningWorkers, autoRamPlan) {
        SdDistributedService.setActiveWorkers(
            planningWorkers.map {
                SdDistributedWorkerRuntime(
                    host = it.host,
                    port = it.port,
                    deviceName = it.displayName,
                    ramMB = it.ramMB,
                    threads = it.threads,
                    backendDevice = it.backendDevice,
                    rpcName = it.rpcName,
                    isLocalMaster = it.isLocalMaster,
                    assignedModules = assignmentByRpc[it.rpcName].orEmpty().map { assignment -> assignment.module },
                    plannedAssignments = assignmentByRpc[it.rpcName].orEmpty()
                )
            }
        )
    }

    SdDistributedPage(
        navController = navController,
        title = stringResource(R.string.sd_dist_master_title),
        subtitle = stringResource(R.string.sd_dist_master_subtitle)
    ) {
        RenderFarmHeader(runtimeConfig = runtimeConfig, workers = planningWorkers, plan = autoRamPlan)

        CollapsibleFarmCard(
            title = stringResource(R.string.sd_dist_card_devices),
            subtitle = stringResource(R.string.sd_dist_card_devices_desc),
            expanded = devicesExpanded,
            onExpandedChange = { devicesExpanded = it },
            accent = RenderFarmPalette.lime.copy(alpha = 0.14f)
        ) {
            MasterContributionCard(
                enabled = masterContributes,
                onEnabledChange = { masterContributes = it },
                displayName = masterDisplayName,
                onDisplayNameChange = { masterDisplayName = it },
                ramDraft = masterRamDraft,
                onRamDraftChange = { masterRamDraft = it },
                threadsDraft = masterThreadsDraft,
                onThreadsDraftChange = { masterThreadsDraft = it },
                backendDevice = masterBackendDevice,
                onBackendDeviceChange = { masterBackendDevice = it },
                allowedModules = masterAllowedModules,
                onAllowedModulesChange = { masterAllowedModules = it },
                diffusionShareDraft = masterDiffusionShareDraft,
                onDiffusionShareDraftChange = { masterDiffusionShareDraft = it }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.sd_dist_remote_workers),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        hostDraft = ""
                        portDraft = SdDistributedService.RPC_DEFAULT_PORT.toString()
                        nameDraft = ""
                        ramDraft = "4096"
                        threadsDraft = "4"
                        backendDraft = ""
                        editingWorkerId = null
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_dist_new_worker), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            HorizontalDivider()
            Text(
                text = if (editingWorkerId == null) stringResource(R.string.sd_dist_manual_worker) else stringResource(R.string.sd_dist_edit_worker),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = hostDraft,
                onValueChange = { hostDraft = it },
                label = { Text(stringResource(R.string.sd_dist_worker_host)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = portDraft,
                    onValueChange = { portDraft = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.sd_dist_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = threadsDraft,
                    onValueChange = { threadsDraft = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.sd_dist_threads_short)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text(stringResource(R.string.sd_dist_device_name)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = ramDraft,
                    onValueChange = { ramDraft = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.sd_dist_ram_mb_short)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            BackendDevicePicker(
                label = stringResource(R.string.sd_dist_backend_device_optional),
                selected = backendDraft,
                onSelected = { backendDraft = it },
                includeRemoteChoices = false
            )
            Button(
                onClick = {
                    val host = hostDraft.trim()
                    if (host.isBlank()) return@Button
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val sortOrder = if (editingWorkerId == null) dao.getMaxWorkerSortOrder() + 1 else {
                                workers.firstOrNull { it.id == editingWorkerId }?.sortOrder ?: dao.getMaxWorkerSortOrder() + 1
                            }
                            dao.upsertWorker(
                                SdDistributedWorkerEntity(
                                    id = editingWorkerId ?: 0L,
                                    host = host,
                                    port = portDraft.toIntOrNull() ?: SdDistributedService.RPC_DEFAULT_PORT,
                                    deviceName = nameDraft.ifBlank { host },
                                    ramMB = ramDraft.toIntOrNull() ?: 4096,
                                    threads = threadsDraft.toIntOrNull() ?: 4,
                                    backendDevice = backendDraft,
                                    isEnabled = workers.firstOrNull { it.id == editingWorkerId }?.isEnabled ?: true,
                                    sortOrder = sortOrder
                                )
                            )
                        }
                        hostDraft = ""
                        nameDraft = ""
                        backendDraft = ""
                        editingWorkerId = null
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (editingWorkerId == null) stringResource(R.string.sd_dist_add_worker) else stringResource(R.string.sd_dist_save_worker))
            }

            if (workers.isEmpty()) {
                Text(
                    text = stringResource(R.string.sd_dist_no_workers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                workers.forEachIndexed { index, worker ->
                    WorkerRow(
                        worker = worker,
                        rpcName = if (worker.isEnabled) "RPC${enabledWorkers.indexOfFirst { it.id == worker.id }.coerceAtLeast(0)}" else "-",
                        onToggle = {
                            scope.launch(Dispatchers.IO) { dao.setWorkerEnabled(worker.id, !worker.isEnabled) }
                        },
                        onEdit = {
                            editingWorkerId = worker.id
                            hostDraft = worker.host
                            portDraft = worker.port.toString()
                            nameDraft = worker.deviceName
                            ramDraft = worker.ramMB.toString()
                            threadsDraft = worker.threads.toString()
                            backendDraft = worker.backendDevice
                        },
                        onDelete = {
                            scope.launch(Dispatchers.IO) { dao.deleteWorker(worker.id) }
                        }
                    )
                    if (index < workers.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }
            HorizontalDivider()
            SummaryLine(stringResource(R.string.sd_dist_rpc_servers_preview), autoRamPlan.rpcServers.ifBlank { "-" })
        }

        CollapsibleFarmCard(
            title = stringResource(R.string.sd_dist_card_planner),
            subtitle = stringResource(R.string.sd_dist_card_planner_desc),
            expanded = plannerExpanded,
            onExpandedChange = { plannerExpanded = it },
            accent = RenderFarmPalette.violet.copy(alpha = 0.16f)
        ) {
            ToggleRow(
                title = stringResource(R.string.sd_dist_enable_distributed),
                subtitle = stringResource(R.string.sd_dist_enable_distributed_desc),
                checked = enabled,
                onCheckedChange = { enabled = it }
            )
            PlacementModeRow(placementMode) { placementMode = it }
            if (placementMode == SdDistributedPlacementMode.AUTO_RAM) {
                AutoRamScopePicker(autoRamScope) { autoRamScope = it }
                RamPlanPreview(plan = autoRamPlan)
                Text(
                    text = stringResource(R.string.sd_dist_auto_ram_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (placementMode == SdDistributedPlacementMode.AUTO_FIT) {
                ToggleRow(
                    title = stringResource(R.string.sd_dist_auto_fit),
                    subtitle = stringResource(R.string.sd_dist_auto_fit_desc),
                    checked = autoFit,
                    onCheckedChange = { autoFit = it }
                )
            } else {
                LabeledTextField(R.string.sd_dist_backend_spec, backendSpec, { backendSpec = it }, R.string.sd_dist_backend_spec_hint)
            }
            LabeledTextField(R.string.sd_dist_params_backend_spec, paramsBackend, { paramsBackend = it }, R.string.sd_dist_params_backend_hint)
        }

        launchError?.let {
            WarningBand(text = it)
        }

        ImageRunCard(
            expanded = imageExpanded,
            onExpandedChange = { imageExpanded = it },
            mode = imageWorkflowMode,
            onModeChange = { imageWorkflowMode = it },
            models = imageMainModels,
            upscalerModels = upscalerModels,
            modelPath = imageModelPath,
            onModelPathChange = { imageModelPath = it },
            upscalerModelPath = imageUpscalerModelPath,
            onUpscalerModelPathChange = { imageUpscalerModelPath = it },
            inputPath = imageInputPath,
            onInputPathChange = { imageInputPath = it },
            prompt = prompt,
            onPromptChange = { prompt = it },
            negativePrompt = negativePrompt,
            onNegativePromptChange = { negativePrompt = it },
            dimensions = dimensions,
            onDimensionsChange = { dimensions = it },
            steps = steps,
            onStepsChange = { steps = it },
            cfg = cfg,
            onCfgChange = { cfg = it },
            seed = seed,
            onSeedChange = { seed = it },
            sampler = sampler,
            onSamplerChange = { sampler = it },
            strength = strength,
            onStrengthChange = { strength = it },
            components = imageComponentChoices,
            onComponentPathChange = { role, path ->
                when (role) {
                    SdComponentRole.VAE -> imageVaePath = path
                    SdComponentRole.TAE -> imageTaePath = path
                    SdComponentRole.CLIP_L -> imageClipLPath = path
                    SdComponentRole.CLIP_G -> imageClipGPath = path
                    SdComponentRole.T5XXL -> imageT5xxlPath = path
                    SdComponentRole.LLM -> imageLlmPath = path
                    SdComponentRole.LLM_VISION -> imageLlmVisionPath = path
                    SdComponentRole.PHOTOMAKER -> imagePhotoMakerPath = path
                    SdComponentRole.CONTROLNET -> imageControlNetPath = path
                    SdComponentRole.LORA -> imageLoraPath = path
                    else -> Unit
                }
            },
            onControlNetEnabledChange = {
                imageControlNetEnabled = it
                if (!it) imageControlNetPath = ""
            },
            onLoraEnabledChange = {
                imageLoraEnabled = it
                if (!it) {
                    imageLoraPath = ""
                    imageLoraApplyMode = ""
                }
            },
            onLoraApplyModeChange = { imageLoraApplyMode = it },
            customFlags = imageCustomFlags,
            onCustomFlagsChange = { imageCustomFlags = it },
            templates = imageTemplates,
            templateNameDraft = templateNameDraft,
            onTemplateNameChange = { templateNameDraft = it },
            onSaveTemplate = {
                val name = templateNameDraft.trim()
                if (name.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        dao.upsertTemplate(
                            SdDistributedTemplateEntity(
                                name = name,
                                workflowType = SdDistributedTemplateWorkflow.IMAGE.name,
                                settingsJson = settingsToJson(draftSettings),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    templateNameDraft = ""
                }
            },
            onLoadTemplate = { template ->
                scope.launch(Dispatchers.IO) {
                    dao.upsertMasterSettings(settingsFromJson(template.settingsJson, draftSettings).copy(id = 1))
                }
            },
            onDeleteTemplate = { template -> scope.launch(Dispatchers.IO) { dao.deleteTemplate(template.id) } },
            isRunning = imageWorkRunning,
            generationState = imageGenerationState,
            onStart = { startImageRun() },
            onCancel = { cancelImageRun() }
        )

        VideoRunCard(
            expanded = videoExpanded,
            onExpandedChange = { videoExpanded = it },
            mode = videoWorkflowMode,
            onModeChange = { videoWorkflowMode = it },
            models = videoModels,
            vaeModels = vaeModels,
            t5xxlModels = t5xxlModels,
            modelPath = videoModelPath,
            onModelPathChange = { videoModelPath = it },
            useVae = videoUseVae,
            onUseVaeChange = {
                videoUseVae = it
                if (!it) videoVaePath = ""
            },
            vaePath = videoVaePath,
            onVaePathChange = { videoVaePath = it },
            useT5xxl = videoUseT5xxl,
            onUseT5xxlChange = {
                videoUseT5xxl = it
                if (!it) videoT5xxlPath = ""
            },
            t5xxlPath = videoT5xxlPath,
            onT5xxlPathChange = { videoT5xxlPath = it },
            inputPath = videoInputPath,
            onInputPathChange = { videoInputPath = it },
            prompt = prompt,
            onPromptChange = { prompt = it },
            negativePrompt = negativePrompt,
            onNegativePromptChange = { negativePrompt = it },
            dimensions = dimensions,
            onDimensionsChange = { dimensions = it },
            steps = steps,
            onStepsChange = { steps = it },
            cfg = cfg,
            onCfgChange = { cfg = it },
            seed = seed,
            onSeedChange = { seed = it },
            sampler = sampler,
            onSamplerChange = { sampler = it },
            frames = frames,
            onFramesChange = { frames = it },
            fps = fps,
            onFpsChange = { fps = it },
            customFlags = videoCustomFlags,
            onCustomFlagsChange = { videoCustomFlags = it },
            templates = videoTemplates,
            templateNameDraft = templateNameDraft,
            onTemplateNameChange = { templateNameDraft = it },
            onSaveTemplate = {
                val name = templateNameDraft.trim()
                if (name.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        dao.upsertTemplate(
                            SdDistributedTemplateEntity(
                                name = name,
                                workflowType = SdDistributedTemplateWorkflow.VIDEO.name,
                                settingsJson = settingsToJson(draftSettings),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    templateNameDraft = ""
                }
            },
            onLoadTemplate = { template ->
                scope.launch(Dispatchers.IO) {
                    dao.upsertMasterSettings(settingsFromJson(template.settingsJson, draftSettings).copy(id = 1))
                }
            },
            onDeleteTemplate = { template -> scope.launch(Dispatchers.IO) { dao.deleteTemplate(template.id) } },
            isRunning = videoWorkRunning,
            generationState = videoGenerationState,
            onStart = { startVideoRun() },
            onCancel = { cancelVideoRun() }
        )

        CollapsibleFarmCard(
            title = stringResource(R.string.sd_dist_runtime_controls),
            subtitle = stringResource(R.string.sd_dist_card_runtime_desc),
            expanded = runtimeExpanded,
            onExpandedChange = { runtimeExpanded = it },
            accent = RenderFarmPalette.orange.copy(alpha = 0.14f)
        ) {
            ToggleRow(stringResource(R.string.sd_dist_mmap), stringResource(R.string.sd_dist_mmap_desc), mmap) { mmap = it }
            ToggleRow(stringResource(R.string.sd_dist_diffusion_fa), stringResource(R.string.sd_dist_diffusion_fa_desc), diffusionFa) { diffusionFa = it }
            ToggleRow(stringResource(R.string.sd_dist_vae_tiling), stringResource(R.string.sd_dist_vae_tiling_desc), vaeTiling) { vaeTiling = it }
            ToggleRow(stringResource(R.string.sd_dist_max_vram_enabled), stringResource(R.string.sd_dist_max_vram_enabled_desc), maxVramEnabled) { maxVramEnabled = it }
            if (maxVramEnabled) {
                LabeledTextField(R.string.sd_dist_max_vram, maxVram, { maxVram = it }, R.string.sd_dist_max_vram_hint)
            }
            SplitModeRow(splitMode) { splitMode = it }
            TwoColumnFields(
                first = { LabeledTextField(R.string.sd_dist_vae_tile_size, vaeTileSize, { vaeTileSize = it }, R.string.sd_dist_vae_tile_size_hint) },
                second = { LabeledTextField(R.string.sd_dist_vae_tile_overlap, vaeTileOverlap, { vaeTileOverlap = it }, R.string.sd_dist_vae_tile_overlap_hint, KeyboardType.Number) }
            )
            TwoColumnFields(
                first = { LabeledTextField(R.string.sd_dist_flow_shift, flowShift, { flowShift = it }, R.string.sd_dist_flow_shift_hint, KeyboardType.Decimal) },
                second = {
                    SimpleStringPicker(
                        label = stringResource(R.string.sd_dist_quantization),
                        selected = quantization,
                        options = sdQuantizationOptions(),
                        offLabel = stringResource(R.string.sd_dist_picker_auto),
                        onSelected = { quantization = it }
                    )
                }
            )
            LabeledTextField(R.string.sd_dist_tensor_rules, tensorRules, { tensorRules = it }, R.string.sd_dist_tensor_rules_hint)
        }

        CollapsibleFarmCard(
            title = stringResource(R.string.sd_dist_adapter_cache_controls),
            subtitle = stringResource(R.string.sd_dist_card_adapters_desc),
            expanded = adaptersExpanded,
            onExpandedChange = { adaptersExpanded = it },
            accent = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
        ) {
            TwoColumnFields(
                first = { LabeledTextField(R.string.sd_dist_lora_strength, loraStrength, { loraStrength = it }, R.string.sd_dist_lora_strength_hint, KeyboardType.Decimal) },
                second = { LabeledTextField(R.string.sd_dist_control_strength, controlStrength, { controlStrength = it }, R.string.sd_dist_control_strength_hint, KeyboardType.Decimal) }
            )
            CacheModePicker(
                selected = cacheMode,
                onSelected = {
                    cacheMode = it
                    if (it.isBlank()) {
                        cacheOption = ""
                        scmMask = ""
                        scmPolicy = ""
                    } else if (it != SdCacheMode.CACHE_DIT.cliName) {
                        scmMask = ""
                        scmPolicy = ""
                    }
                }
            )
            if (cacheMode.isNotBlank()) {
                CacheOptionPicker(cacheMode = cacheMode, value = cacheOption, onValueChange = { cacheOption = it })
                if (cacheMode == SdCacheMode.CACHE_DIT.cliName) {
                    TwoColumnFields(
                        first = { ScmMaskPicker(value = scmMask, onValueChange = { scmMask = it }) },
                        second = {
                            SimpleStringPicker(
                                label = stringResource(R.string.sd_dist_scm_policy),
                                selected = scmPolicy,
                                options = SdCacheScmPolicy.entries.map { it.cliName },
                                offLabel = stringResource(R.string.sd_dist_picker_off),
                                onSelected = { scmPolicy = it }
                            )
                        }
                    )
                }
            }
        }

        CollapsibleFarmCard(
            title = stringResource(R.string.sd_dist_command_preview),
            subtitle = stringResource(R.string.sd_dist_card_expert_desc),
            expanded = expertExpanded,
            onExpandedChange = { expertExpanded = it },
            accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        ) {
            LabeledTextField(R.string.sd_dist_custom_flags, customFlags, { customFlags = it }, R.string.sd_dist_custom_flags_hint)
            CodeBlock(text = preview.ifBlank { "-" })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { saveDraftSettings() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_dist_save_apply), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { navController.navigate(Screen.SdDistributedNetwork.route) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_dist_view_network), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun SdDistributedNetworkScreen(navController: NavController) {
    val activeWorkers by SdDistributedService.activeWorkers.collectAsState()
    val runtimeConfig by SdDistributedService.runtimeConfig.collectAsState()
    val logs by SdDistributedService.logs.collectAsState()

    SdDistributedPage(
        navController = navController,
        title = stringResource(R.string.sd_dist_network_title),
        subtitle = stringResource(R.string.sd_dist_network_subtitle)
    ) {
        MediaPipelinePreview(runtimeConfig = runtimeConfig, workers = activeWorkers)
        AppSectionCard {
            Text(
                text = stringResource(R.string.sd_dist_live_topology),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (activeWorkers.isEmpty()) {
                Text(stringResource(R.string.sd_dist_no_active_workers), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                activeWorkers.forEach { worker ->
                    PipelineWorkerLane(worker)
                }
            }
        }
        AppSectionCard(tonalAccent = MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f)) {
            Text(
                text = stringResource(R.string.sd_dist_current_plan),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            RuntimeSummary(runtimeConfig)
        }
        LogsCard(title = stringResource(R.string.sd_dist_logs), logs = logs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdDistributedRunConfigScreen(navController: NavController) {
    SdDistributedMasterScreen(navController)
}

@Composable
private fun SdDistributedPage(
    navController: NavController,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    AppPageBackground {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            AppContentColumn {
                AppPageHeader(
                    title = title,
                    subtitle = subtitle,
                    trailing = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.kiwix_back))
                        }
                    }
                )
                content()
            }
        }
    }
}

@Composable
private fun HubActionCard(icon: ImageVector, title: String, description: String, action: String, onClick: () -> Unit) {
    AppSectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(action)
        }
    }
}

private object RenderFarmPalette {
    val graphite = Color(0xFF161A1D)
    val graphiteSoft = Color(0xFF242A2E)
    val lime = Color(0xFF93D94E)
    val orange = Color(0xFFFFB14A)
    val violet = Color(0xFF8D7CFF)
    val coral = Color(0xFFFF6E6C)
}

private enum class SdGeneratedMediaKind { IMAGE, VIDEO }

private enum class SdGeneratedMediaFilter { ALL, IMAGES, VIDEOS }

private data class SdGeneratedMediaItem(
    val file: File,
    val kind: SdGeneratedMediaKind,
    val mode: String,
    val prompt: String = "",
    val mimeType: String,
    val createdAt: Long
)

@Composable
private fun SdGeneratedMediaCard(
    item: SdGeneratedMediaItem,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, item.file.absolutePath, item.mimeType) {
        value = withContext(Dispatchers.IO) { loadSdGalleryThumbnail(item) }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.12f)
                .background(RenderFarmPalette.graphiteSoft),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                ComposeImage(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = if (item.kind == SdGeneratedMediaKind.VIDEO) Icons.Default.Movie else Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        if (item.kind == SdGeneratedMediaKind.VIDEO) stringResource(R.string.sd_dist_gallery_video) else stringResource(R.string.sd_dist_gallery_image),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            )
        }
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = item.prompt.ifBlank { item.file.nameWithoutExtension },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.sd_dist_gallery_item_meta, item.mode, formatSdGalleryDate(item.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onOpen) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_open), maxLines = 1)
                }
                OutlinedButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_share), maxLines = 1)
                }
            }
        }
    }
}

private fun scanSdGeneratedMedia(context: Context): List<SdGeneratedMediaItem> {
    val imageRoot = File(context.filesDir, "sd_output")
    val imageItems = if (imageRoot.exists()) {
        imageRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.US) in setOf("png", "jpg", "jpeg") }
            .map { file ->
                SdGeneratedMediaItem(
                    file = file,
                    kind = SdGeneratedMediaKind.IMAGE,
                    mode = file.parentFile?.name?.takeIf { it != imageRoot.name } ?: context.getString(R.string.sd_dist_gallery_image),
                    mimeType = "image/${if (file.extension.equals("png", ignoreCase = true)) "png" else "jpeg"}",
                    createdAt = file.lastModified()
                )
            }
            .toList()
    } else {
        emptyList()
    }

    val videoRoot = File(context.filesDir, "video_gen_output")
    val metadataItems = loadGeneratedVideoMetadata(videoRoot).mapNotNull { metadata ->
        val file = File(metadata.mp4Path).takeIf { it.exists() } ?: File(metadata.aviPath).takeIf { it.exists() }
        file?.let {
            SdGeneratedMediaItem(
                file = it,
                kind = SdGeneratedMediaKind.VIDEO,
                mode = metadata.mode,
                prompt = metadata.prompt,
                mimeType = if (it.extension.equals("avi", ignoreCase = true)) "video/x-msvideo" else "video/mp4",
                createdAt = metadata.createdAt.takeIf { createdAt -> createdAt > 0L } ?: it.lastModified()
            )
        }
    }
    val metadataPaths = metadataItems.map { it.file.absolutePath }.toSet()
    val looseVideoItems = if (videoRoot.exists()) {
        videoRoot.walkTopDown()
            .filter { it.isFile && it.absolutePath !in metadataPaths && it.extension.lowercase(Locale.US) in setOf("mp4", "avi") }
            .map { file ->
                SdGeneratedMediaItem(
                    file = file,
                    kind = SdGeneratedMediaKind.VIDEO,
                    mode = file.parentFile?.name ?: context.getString(R.string.sd_dist_gallery_video),
                    mimeType = if (file.extension.equals("avi", ignoreCase = true)) "video/x-msvideo" else "video/mp4",
                    createdAt = file.lastModified()
                )
            }
            .toList()
    } else {
        emptyList()
    }

    return (imageItems + metadataItems + looseVideoItems).sortedByDescending { it.createdAt }
}

private fun loadSdGalleryThumbnail(item: SdGeneratedMediaItem): android.graphics.Bitmap? =
    runCatching {
        when (item.kind) {
            SdGeneratedMediaKind.IMAGE -> decodeSdGalleryImage(item.file.absolutePath)
            SdGeneratedMediaKind.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(item.file.absolutePath)
                    retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            }
        }
    }.getOrNull()

private fun decodeSdGalleryImage(path: String): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val maxDimension = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    val sampleSize = generateSequence(1) { it * 2 }
        .first { maxDimension / it <= 768 }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(path, options)
}

private fun openSdGeneratedMedia(context: Context, item: SdGeneratedMediaItem) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.sd_dist_gallery_open_with)))
    }.onFailure { error ->
        Toast.makeText(context, context.getString(R.string.sd_dist_gallery_open_failed, error.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

private fun shareSdGeneratedMedia(context: Context, item: SdGeneratedMediaItem) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
    }.onFailure { error ->
        Toast.makeText(context, context.getString(R.string.sd_dist_gallery_open_failed, error.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

private fun formatSdGalleryDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
private fun RenderFarmHeader(
    runtimeConfig: SdDistributedRuntimeConfig,
    workers: List<com.example.llamadroid.service.SdDistributedPlanningWorker>,
    plan: SdDistributedPlacementPlan
) {
    AppSectionCard(containerColor = RenderFarmPalette.graphite, tonalAccent = RenderFarmPalette.lime.copy(alpha = 0.18f)) {
        Text(
            text = stringResource(R.string.sd_dist_render_farm_console),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FarmStatusChip(stringResource(R.string.sd_dist_enabled), if (runtimeConfig.enabled) stringResource(R.string.sd_dist_live) else stringResource(R.string.sd_dist_disabled), RenderFarmPalette.lime)
            FarmStatusChip(stringResource(R.string.sd_dist_workers), workers.size.toString(), RenderFarmPalette.violet)
            FarmStatusChip(stringResource(R.string.sd_dist_ram_to_share), "${workers.sumOf { it.ramMB }} ${stringResource(R.string.sd_dist_mb_suffix)}", RenderFarmPalette.orange)
            FarmStatusChip(stringResource(R.string.sd_dist_backend_spec), plan.backendSpec.ifBlank { "-" }, RenderFarmPalette.coral)
        }
    }
}

@Composable
private fun FarmStatusChip(label: String, value: String, color: Color) {
    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = RenderFarmPalette.graphiteSoft)
    ) {
        Column(modifier = Modifier.width(150.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CollapsibleFarmCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    accent: Color,
    content: @Composable () -> Unit
) {
    AppSectionCard(containerColor = MaterialTheme.colorScheme.surface, tonalAccent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onExpandedChange(!expanded) }) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
        }
        if (expanded) {
            HorizontalDivider()
            content()
        }
    }
}

private enum class SdDistributedTemplateWorkflow { IMAGE, VIDEO }

private data class SdDistributedImageComponentChoices(
    val familyLabel: String,
    val spec: SdModelFamilySpec?,
    val roles: List<SdComponentRole>,
    val compatibleModels: Map<SdComponentRole, List<ModelEntity>>,
    val selectedPaths: Map<SdComponentRole, String>,
    val controlNetEnabled: Boolean,
    val loraEnabled: Boolean,
    val loraApplyMode: String
)

@Composable
private fun MasterContributionCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    ramDraft: String,
    onRamDraftChange: (String) -> Unit,
    threadsDraft: String,
    onThreadsDraftChange: (String) -> Unit,
    backendDevice: String,
    onBackendDeviceChange: (String) -> Unit,
    allowedModules: Set<String>,
    onAllowedModulesChange: (Set<String>) -> Unit,
    diffusionShareDraft: String,
    onDiffusionShareDraftChange: (String) -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = RenderFarmPalette.graphiteSoft)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleRow(
                title = stringResource(R.string.sd_dist_master_contribute),
                subtitle = stringResource(R.string.sd_dist_master_contribute_desc),
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
            if (enabled) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text(stringResource(R.string.sd_dist_master_display_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                BackendDevicePicker(
                    label = stringResource(R.string.sd_dist_backend_device_optional),
                    selected = backendDevice,
                    onSelected = onBackendDeviceChange,
                    includeRemoteChoices = false
                )
                NumberSliderField(
                    label = stringResource(R.string.sd_dist_ram_to_share),
                    valueDraft = ramDraft,
                    onValueDraftChange = onRamDraftChange,
                    range = 512f..65536f,
                    suffix = stringResource(R.string.sd_dist_mb_suffix)
                )
                NumberSliderField(
                    label = stringResource(R.string.sd_dist_threads_to_share),
                    valueDraft = threadsDraft,
                    onValueDraftChange = onThreadsDraftChange,
                    range = 1f..32f
                )
                Text(stringResource(R.string.sd_dist_master_allowed_modules), style = MaterialTheme.typography.labelLarge, color = Color.White)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SdDistributedModules.defaultModuleSet.forEach { module ->
                        val selected = module in allowedModules
                        AssistChip(
                            onClick = {
                                val next = if (selected) allowedModules - module else allowedModules + module
                                onAllowedModulesChange(next.ifEmpty { setOf(SdDistributedModules.DIFFUSION) })
                            },
                            label = { Text(moduleLabel(module), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else {
                                null
                            }
                        )
                    }
                }
                LabeledTextField(
                    R.string.sd_dist_master_diffusion_share,
                    diffusionShareDraft,
                    { onDiffusionShareDraftChange(it.filter(Char::isDigit).take(2)) },
                    R.string.sd_dist_master_diffusion_share_hint,
                    KeyboardType.Number
                )
                Text(
                    stringResource(R.string.sd_dist_master_diffusion_share_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f)
                )
            }
        }
    }
}

@Composable
private fun ImageRunCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    mode: String,
    onModeChange: (String) -> Unit,
    models: List<ModelEntity>,
    upscalerModels: List<ModelEntity>,
    modelPath: String,
    onModelPathChange: (String) -> Unit,
    upscalerModelPath: String,
    onUpscalerModelPathChange: (String) -> Unit,
    inputPath: String,
    onInputPathChange: (String) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    dimensions: String,
    onDimensionsChange: (String) -> Unit,
    steps: String,
    onStepsChange: (String) -> Unit,
    cfg: String,
    onCfgChange: (String) -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    sampler: String,
    onSamplerChange: (String) -> Unit,
    strength: String,
    onStrengthChange: (String) -> Unit,
    components: SdDistributedImageComponentChoices,
    onComponentPathChange: (SdComponentRole, String) -> Unit,
    onControlNetEnabledChange: (Boolean) -> Unit,
    onLoraEnabledChange: (Boolean) -> Unit,
    onLoraApplyModeChange: (String) -> Unit,
    customFlags: String,
    onCustomFlagsChange: (String) -> Unit,
    templates: List<SdDistributedTemplateEntity>,
    templateNameDraft: String,
    onTemplateNameChange: (String) -> Unit,
    onSaveTemplate: () -> Unit,
    onLoadTemplate: (SdDistributedTemplateEntity) -> Unit,
    onDeleteTemplate: (SdDistributedTemplateEntity) -> Unit,
    isRunning: Boolean,
    generationState: SDGenerationState,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    CollapsibleFarmCard(
        title = stringResource(R.string.sd_dist_image_card_title),
        subtitle = stringResource(R.string.sd_dist_image_card_desc),
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        accent = RenderFarmPalette.coral.copy(alpha = 0.14f)
    ) {
        SimpleStringPicker(
            label = stringResource(R.string.sd_dist_image_mode),
            selected = mode,
            options = listOf("TXT2IMG", "IMG2IMG", "UPSCALE"),
            offLabel = "TXT2IMG",
            onSelected = onModeChange
        )
        if (mode == "UPSCALE") {
            ModelPicker(
                label = stringResource(R.string.sd_dist_upscaler_model),
                models = upscalerModels,
                selectedPath = upscalerModelPath,
                onSelectedPath = onUpscalerModelPathChange
            )
        } else {
            ModelPicker(
                label = stringResource(R.string.sd_dist_image_model),
                models = models,
                selectedPath = modelPath,
                onSelectedPath = onModelPathChange
            )
            ImageComponentPickerSection(
                mode = mode,
                components = components,
                onComponentPathChange = onComponentPathChange,
                onControlNetEnabledChange = onControlNetEnabledChange,
                onLoraEnabledChange = onLoraEnabledChange,
                onLoraApplyModeChange = onLoraApplyModeChange
            )
        }
        if (mode != "TXT2IMG") {
            LabeledTextField(R.string.sd_dist_input_image_path, inputPath, onInputPathChange, R.string.sd_dist_input_image_path_hint)
        }
        LabeledTextField(R.string.sd_dist_prompt, prompt, onPromptChange, R.string.sd_dist_prompt_hint)
        LabeledTextField(R.string.sd_dist_negative_prompt, negativePrompt, onNegativePromptChange, R.string.sd_dist_negative_prompt_hint)
        TwoColumnFields(
            first = { LabeledTextField(R.string.sd_dist_dimensions, dimensions, onDimensionsChange, R.string.sd_dist_dimensions_hint) },
            second = { LabeledTextField(R.string.sd_dist_steps, steps, onStepsChange, R.string.sd_dist_steps_hint, KeyboardType.Number) }
        )
        TwoColumnFields(
            first = { LabeledTextField(R.string.sd_dist_cfg, cfg, onCfgChange, R.string.sd_dist_cfg_hint, KeyboardType.Decimal) },
            second = { LabeledTextField(R.string.sd_dist_seed, seed, onSeedChange, R.string.sd_dist_seed_hint, KeyboardType.Number) }
        )
        SamplingPicker(selected = sampler, onSelected = onSamplerChange)
        if (mode == "IMG2IMG") {
            LabeledTextField(R.string.sd_dist_strength, strength, onStrengthChange, R.string.sd_dist_strength_hint, KeyboardType.Decimal)
        }
        LabeledTextField(
            R.string.sd_manual_flags_label,
            customFlags,
            onCustomFlagsChange,
            R.string.sd_manual_flags_hint
        )
        TemplateManagerCard(
            title = stringResource(R.string.sd_dist_image_templates),
            templates = templates,
            templateNameDraft = templateNameDraft,
            onTemplateNameChange = onTemplateNameChange,
            onSaveTemplate = onSaveTemplate,
            onLoadTemplate = onLoadTemplate,
            onDeleteTemplate = onDeleteTemplate
        )
        RunButtons(isRunning = isRunning, onStart = onStart, onCancel = onCancel)
        if (isRunning) {
            SdDistributedImageProgressCard(generationState)
        }
    }
}

@Composable
private fun VideoRunCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    mode: String,
    onModeChange: (String) -> Unit,
    models: List<ModelEntity>,
    vaeModels: List<ModelEntity>,
    t5xxlModels: List<ModelEntity>,
    modelPath: String,
    onModelPathChange: (String) -> Unit,
    useVae: Boolean,
    onUseVaeChange: (Boolean) -> Unit,
    vaePath: String,
    onVaePathChange: (String) -> Unit,
    useT5xxl: Boolean,
    onUseT5xxlChange: (Boolean) -> Unit,
    t5xxlPath: String,
    onT5xxlPathChange: (String) -> Unit,
    inputPath: String,
    onInputPathChange: (String) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    dimensions: String,
    onDimensionsChange: (String) -> Unit,
    steps: String,
    onStepsChange: (String) -> Unit,
    cfg: String,
    onCfgChange: (String) -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    sampler: String,
    onSamplerChange: (String) -> Unit,
    frames: String,
    onFramesChange: (String) -> Unit,
    fps: String,
    onFpsChange: (String) -> Unit,
    customFlags: String,
    onCustomFlagsChange: (String) -> Unit,
    templates: List<SdDistributedTemplateEntity>,
    templateNameDraft: String,
    onTemplateNameChange: (String) -> Unit,
    onSaveTemplate: () -> Unit,
    onLoadTemplate: (SdDistributedTemplateEntity) -> Unit,
    onDeleteTemplate: (SdDistributedTemplateEntity) -> Unit,
    isRunning: Boolean,
    generationState: VideoGenerationState,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    CollapsibleFarmCard(
        title = stringResource(R.string.sd_dist_video_card_title),
        subtitle = stringResource(R.string.sd_dist_video_card_desc),
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        accent = RenderFarmPalette.violet.copy(alpha = 0.14f)
    ) {
        SimpleStringPicker(
            label = stringResource(R.string.sd_dist_video_mode),
            selected = mode,
            options = listOf("TXT2VID", "IMG2VID"),
            offLabel = "TXT2VID",
            onSelected = onModeChange
        )
        ModelPicker(
            label = stringResource(R.string.sd_dist_video_model),
            models = models,
            selectedPath = modelPath,
            onSelectedPath = onModelPathChange
        )
        OptionalComponentPicker(
            enabled = useVae,
            onEnabledChange = onUseVaeChange,
            label = stringResource(R.string.imagegen_component_vae),
            models = vaeModels,
            selectedPath = vaePath,
            onSelectedPath = onVaePathChange,
            emptyMessage = stringResource(R.string.imagegen_no_vae_installed)
        )
        OptionalComponentPicker(
            enabled = useT5xxl,
            onEnabledChange = onUseT5xxlChange,
            label = stringResource(R.string.imagegen_component_t5xxl),
            models = t5xxlModels,
            selectedPath = t5xxlPath,
            onSelectedPath = onT5xxlPathChange,
            emptyMessage = stringResource(R.string.imagegen_no_t5xxl)
        )
        if (mode == "IMG2VID") {
            LabeledTextField(R.string.sd_dist_input_image_path, inputPath, onInputPathChange, R.string.sd_dist_input_image_path_hint)
        }
        LabeledTextField(R.string.sd_dist_prompt, prompt, onPromptChange, R.string.sd_dist_prompt_hint)
        LabeledTextField(R.string.sd_dist_negative_prompt, negativePrompt, onNegativePromptChange, R.string.sd_dist_negative_prompt_hint)
        TwoColumnFields(
            first = { LabeledTextField(R.string.sd_dist_dimensions, dimensions, onDimensionsChange, R.string.sd_dist_dimensions_hint) },
            second = { LabeledTextField(R.string.sd_dist_steps, steps, onStepsChange, R.string.sd_dist_steps_hint, KeyboardType.Number) }
        )
        TwoColumnFields(
            first = { LabeledTextField(R.string.sd_dist_cfg, cfg, onCfgChange, R.string.sd_dist_cfg_hint, KeyboardType.Decimal) },
            second = { LabeledTextField(R.string.sd_dist_seed, seed, onSeedChange, R.string.sd_dist_seed_hint, KeyboardType.Number) }
        )
        TwoColumnFields(
            first = { LabeledTextField(R.string.sd_dist_frames, frames, onFramesChange, R.string.sd_dist_frames_hint, KeyboardType.Number) },
            second = { LabeledTextField(R.string.sd_dist_fps, fps, onFpsChange, R.string.sd_dist_fps_hint, KeyboardType.Number) }
        )
        SamplingPicker(selected = sampler, onSelected = onSamplerChange)
        LabeledTextField(
            R.string.sd_manual_flags_label,
            customFlags,
            onCustomFlagsChange,
            R.string.sd_manual_flags_hint
        )
        TemplateManagerCard(
            title = stringResource(R.string.sd_dist_video_templates),
            templates = templates,
            templateNameDraft = templateNameDraft,
            onTemplateNameChange = onTemplateNameChange,
            onSaveTemplate = onSaveTemplate,
            onLoadTemplate = onLoadTemplate,
            onDeleteTemplate = onDeleteTemplate
        )
        RunButtons(isRunning = isRunning, onStart = onStart, onCancel = onCancel)
        if (isRunning) {
            SdDistributedVideoProgressCard(generationState)
        }
    }
}

@Composable
private fun RunButtons(isRunning: Boolean, onStart: () -> Unit, onCancel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (isRunning) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sd_dist_start_work), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SdDistributedImageProgressCard(state: SDGenerationState) {
    val generating = state as? SDGenerationState.Generating ?: return
    val snapshot = generating.snapshot
    SdDistributedProgressCard(
        title = stringResource(R.string.sd_dist_image_progress_title),
        status = snapshot.statusText.ifBlank { stringResource(R.string.gen_status_calculating_eta) },
        progress = snapshot.progress,
        currentStep = snapshot.currentStep,
        totalSteps = snapshot.totalSteps,
        etaSeconds = snapshot.etaSeconds
    )
}

@Composable
private fun SdDistributedVideoProgressCard(state: VideoGenerationState) {
    when (state) {
        is VideoGenerationState.Generating -> SdDistributedProgressCard(
            title = stringResource(R.string.sd_dist_video_progress_title),
            status = state.status.ifBlank { stringResource(R.string.gen_status_calculating_eta) },
            progress = state.progress,
            currentStep = state.currentStep,
            totalSteps = state.totalSteps,
            etaSeconds = state.etaSeconds
        )
        is VideoGenerationState.Converting -> SdDistributedProgressCard(
            title = stringResource(R.string.sd_dist_video_progress_title),
            status = state.status,
            progress = state.progress,
            currentStep = 0,
            totalSteps = 0,
            etaSeconds = null
        )
        is VideoGenerationState.Copying -> SdDistributedProgressCard(
            title = stringResource(R.string.sd_dist_video_progress_title),
            status = state.status,
            progress = state.progress,
            currentStep = 0,
            totalSteps = 0,
            etaSeconds = null
        )
        else -> Unit
    }
}

@Composable
private fun SdDistributedProgressCard(
    title: String,
    status: String,
    progress: Float,
    currentStep: Int,
    totalSteps: Int,
    etaSeconds: Double?
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = RenderFarmPalette.graphite.copy(alpha = 0.90f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = RenderFarmPalette.lime
                )
                Text(
                    text = stringResource(
                        R.string.sd_dist_progress_percent,
                        (progress.coerceIn(0f, 1f) * 100).toInt()
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = RenderFarmPalette.lime,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val stepText = if (totalSteps > 0) {
                    stringResource(
                        R.string.sd_dist_progress_steps,
                        currentStep.coerceAtLeast(0),
                        totalSteps
                    )
                } else {
                    stringResource(R.string.sd_dist_progress_steps_unknown)
                }
                AssistChip(onClick = {}, label = { Text(stepText) })
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(
                                R.string.sd_dist_progress_eta,
                                etaSeconds?.let { sdDistributedEtaLabel(it) }
                                    ?: stringResource(R.string.sd_dist_progress_eta_calculating)
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun sdDistributedEtaLabel(etaSeconds: Double): String {
    val roundedSeconds = etaSeconds.coerceAtLeast(0.0).toInt()
    return when {
        roundedSeconds < 60 -> stringResource(R.string.gen_eta_seconds_short, roundedSeconds)
        roundedSeconds < 3600 -> stringResource(
            R.string.gen_eta_minutes_seconds_short,
            roundedSeconds / 60,
            roundedSeconds % 60
        )
        else -> stringResource(
            R.string.gen_eta_hours_minutes_short,
            roundedSeconds / 3600,
            (roundedSeconds % 3600) / 60
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    label: String,
    models: List<ModelEntity>,
    selectedPath: String,
    onSelectedPath: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = models.firstOrNull { it.path == selectedPath }?.filename ?: stringResource(R.string.sd_dist_select_model)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (models.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.sd_dist_no_models_available)) }, onClick = { expanded = false })
            } else {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            onSelectedPath(model.path)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageComponentPickerSection(
    mode: String,
    components: SdDistributedImageComponentChoices,
    onComponentPathChange: (SdComponentRole, String) -> Unit,
    onControlNetEnabledChange: (Boolean) -> Unit,
    onLoraEnabledChange: (Boolean) -> Unit,
    onLoraApplyModeChange: (String) -> Unit
) {
    val spec = components.spec ?: return
    if (components.roles.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.imagegen_components_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = RenderFarmPalette.lime
            )
            Text(
                text = stringResource(R.string.imagegen_components_desc, components.familyLabel.ifBlank { "-" }),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f)
            )
            components.roles.forEach { role ->
                ComponentModelPicker(
                    label = sdDistributedComponentRoleLabel(role),
                    models = components.compatibleModels[role].orEmpty(),
                    selectedPath = components.selectedPaths[role].orEmpty(),
                    onSelectedPath = { onComponentPathChange(role, it) },
                    required = role in spec.requiredRoles,
                    emptyMessage = stringResource(sdDistributedEmptyComponentMessageRes(role))
                )
            }
        }
    }

    val controlNetModels = components.compatibleModels[SdComponentRole.CONTROLNET].orEmpty()
    val loraModels = components.compatibleModels[SdComponentRole.LORA].orEmpty()
    if ((mode == "IMG2IMG" && controlNetModels.isNotEmpty()) || loraModels.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.imagegen_adapters_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = RenderFarmPalette.coral
            )
            Text(
                text = stringResource(R.string.imagegen_adapters_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f)
            )
            if (mode == "IMG2IMG" && controlNetModels.isNotEmpty()) {
                OptionalComponentPicker(
                    enabled = components.controlNetEnabled,
                    onEnabledChange = onControlNetEnabledChange,
                    label = sdDistributedComponentRoleLabel(SdComponentRole.CONTROLNET),
                    models = controlNetModels,
                    selectedPath = components.selectedPaths[SdComponentRole.CONTROLNET].orEmpty(),
                    onSelectedPath = { onComponentPathChange(SdComponentRole.CONTROLNET, it) },
                    emptyMessage = stringResource(R.string.imagegen_no_controlnet)
                )
            }
            if (loraModels.isNotEmpty()) {
                OptionalComponentPicker(
                    enabled = components.loraEnabled,
                    onEnabledChange = onLoraEnabledChange,
                    label = sdDistributedComponentRoleLabel(SdComponentRole.LORA),
                    models = loraModels,
                    selectedPath = components.selectedPaths[SdComponentRole.LORA].orEmpty(),
                    onSelectedPath = { onComponentPathChange(SdComponentRole.LORA, it) },
                    emptyMessage = stringResource(R.string.imagegen_no_lora)
                )
                if (components.loraEnabled && spec.supportsLoraApplyMode) {
                    SimpleStringPicker(
                        label = stringResource(R.string.imagegen_lora_apply_mode_label),
                        selected = components.loraApplyMode,
                        options = listOf("") + SdLoraApplyMode.entries.map { it.cliName },
                        offLabel = stringResource(R.string.imagegen_lora_apply_mode_default),
                        onSelected = onLoraApplyModeChange
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionalComponentPicker(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    label: String,
    models: List<ModelEntity>,
    selectedPath: String,
    onSelectedPath: (String) -> Unit,
    emptyMessage: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = enabled,
                onCheckedChange = { checked ->
                    onEnabledChange(checked)
                    if (!checked) onSelectedPath("")
                }
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (enabled) {
            ComponentModelPicker(
                label = label,
                models = models,
                selectedPath = selectedPath,
                onSelectedPath = onSelectedPath,
                required = true,
                emptyMessage = emptyMessage
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComponentModelPicker(
    label: String,
    models: List<ModelEntity>,
    selectedPath: String,
    onSelectedPath: (String) -> Unit,
    required: Boolean,
    emptyMessage: String
) {
    if (models.isEmpty()) {
        Text(
            text = emptyMessage,
            style = MaterialTheme.typography.bodySmall,
            color = RenderFarmPalette.orange
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val noneLabel = stringResource(R.string.imagegen_none_builtin)
    val selectedName = models.firstOrNull { it.path == selectedPath }?.filename
        ?: if (required) stringResource(R.string.sd_dist_select_model) else noneLabel
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!required) {
                DropdownMenuItem(
                    text = { Text(noneLabel) },
                    onClick = {
                        onSelectedPath("")
                        expanded = false
                    }
                )
            }
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelectedPath(model.path)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SamplingPicker(selected: String, onSelected: (String) -> Unit) {
    SimpleStringPicker(
        label = stringResource(R.string.sd_dist_sampler),
        selected = selected,
        options = SamplingMethod.entries.map { it.cliName },
        offLabel = SamplingMethod.EULER_A.cliName,
        onSelected = onSelected
    )
}

@Composable
private fun BackendDevicePicker(
    label: String,
    selected: String,
    onSelected: (String) -> Unit,
    includeRemoteChoices: Boolean
) {
    val options = buildList {
        add("")
        add("cpu")
        if (includeRemoteChoices) {
            add("RPC0")
            add("RPC1")
            add("RPC2")
        }
    }
    SimpleStringPicker(
        label = label,
        selected = selected,
        options = options,
        offLabel = stringResource(R.string.sd_dist_picker_auto),
        onSelected = onSelected
    )
}

@Composable
private fun CacheModePicker(selected: String, onSelected: (String) -> Unit) {
    SimpleStringPicker(
        label = stringResource(R.string.sd_dist_cache_mode),
        selected = selected,
        options = listOf("") + SdCacheMode.entries.map { it.cliName },
        offLabel = stringResource(R.string.sd_dist_cache_mode_off),
        onSelected = onSelected
    )
}

@Composable
private fun CacheOptionPicker(cacheMode: String, value: String, onValueChange: (String) -> Unit) {
    val presets = cacheOptionPresets(cacheMode)
    SimpleStringPicker(
        label = stringResource(R.string.sd_dist_cache_option_preset),
        selected = value,
        options = listOf("") + presets,
        offLabel = stringResource(R.string.sd_dist_picker_custom),
        onSelected = onValueChange
    )
    LabeledTextField(R.string.sd_dist_cache_option, value, onValueChange, R.string.sd_dist_cache_option_hint)
}

@Composable
private fun ScmMaskPicker(value: String, onValueChange: (String) -> Unit) {
    val presets = listOf("1,1,1,0,0,1,0,0,1,0", "1,0,1,0,1,0,1,0,1,0", "1")
    SimpleStringPicker(
        label = stringResource(R.string.sd_dist_scm_mask_preset),
        selected = value,
        options = listOf("") + presets,
        offLabel = stringResource(R.string.sd_dist_picker_custom),
        onSelected = onValueChange
    )
    LabeledTextField(R.string.sd_dist_scm_mask, value, onValueChange, R.string.sd_dist_scm_mask_hint)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleStringPicker(
    label: String,
    selected: String,
    options: List<String>,
    offLabel: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = selected.ifBlank { offLabel }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.distinct().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.ifBlank { offLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun cacheOptionPresets(cacheMode: String): List<String> = when (SdCacheMode.fromStoredValue(cacheMode)) {
    SdCacheMode.UCACHE -> listOf("threshold=1.5,reset=0")
    SdCacheMode.EASYCACHE -> listOf("threshold=0.3,start=0.15,end=0.95")
    SdCacheMode.DBCACHE,
    SdCacheMode.TAYLORSEER,
    SdCacheMode.CACHE_DIT -> listOf("threshold=0.25,warmup=4,Fn=8,Bn=0")
    SdCacheMode.SPECTRUM -> listOf("w=0.4,m=3,lam=1.0,window=2,flex=0.5,warmup=4,stop=0.9")
    null -> emptyList()
}

private fun sdQuantizationOptions(): List<String> =
    listOf("", "f32", "f16", "q8_0", "q5_1", "q5_0", "q4_1", "q4_0")

@Composable
private fun TemplateManagerCard(
    title: String,
    templates: List<SdDistributedTemplateEntity>,
    templateNameDraft: String,
    onTemplateNameChange: (String) -> Unit,
    onSaveTemplate: () -> Unit,
    onLoadTemplate: (SdDistributedTemplateEntity) -> Unit,
    onDeleteTemplate: (SdDistributedTemplateEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RenderFarmPalette.coral.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.sd_dist_templates_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = templateNameDraft,
                onValueChange = onTemplateNameChange,
                label = { Text(stringResource(R.string.sd_dist_template_name)) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = onSaveTemplate, enabled = templateNameDraft.isNotBlank()) {
                Icon(Icons.Default.Save, contentDescription = null)
            }
        }
        if (templates.isEmpty()) {
            Text(stringResource(R.string.sd_dist_no_templates), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            templates.forEach { template ->
                val templateSettings = remember(template.settingsJson) { settingsFromJson(template.settingsJson) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(template.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.sd_dist_template_summary, templateSettings.placementMode, templateSettings.steps, templateSettings.sampler), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { onLoadTemplate(template) }) {
                        Text(stringResource(R.string.sd_dist_load_template), maxLines = 1)
                    }
                    IconButton(onClick = { onDeleteTemplate(template) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.sd_dist_delete_template))
                    }
                }
            }
        }
    }
}

@Composable
private fun RamPlanPreview(plan: SdDistributedPlacementPlan) {
    Text(stringResource(R.string.sd_dist_auto_ram_plan), style = MaterialTheme.typography.labelLarge, color = RenderFarmPalette.violet)
    CodeBlock(text = plan.backendSpec.ifBlank { "-" })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.assignments.forEach { assignment ->
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        "${moduleLabel(assignment.module)} ${assignment.devices.joinToString("&")}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

@Composable
private fun MediaPipelinePreview(
    runtimeConfig: SdDistributedRuntimeConfig = SdDistributedRuntimeConfig(),
    workers: List<SdDistributedWorkerRuntime> = emptyList()
) {
    val autoFitLabel = stringResource(R.string.sd_dist_auto_fit)
    val unassignedLabel = stringResource(R.string.sd_dist_unassigned)
    val budgetAutoLabel = stringResource(R.string.sd_dist_budget_auto)
    val localOnlyLabel = stringResource(R.string.sd_dist_local_only)
    AppSectionCard(tonalAccent = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
        Text(
            text = stringResource(R.string.sd_dist_pipeline_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PipelineChip(stringResource(R.string.sd_dist_module_text_encoder), runtimeConfig.backendSpec)
            PipelineChip(stringResource(R.string.sd_dist_module_diffusion), runtimeConfig.splitMode.cliName)
            PipelineChip(stringResource(R.string.sd_dist_module_vae), runtimeConfig.paramsBackendSpec.ifBlank { autoFitLabel })
            PipelineChip(stringResource(R.string.sd_dist_module_controlnet), workers.firstOrNull()?.deviceName ?: unassignedLabel)
            PipelineChip(stringResource(R.string.sd_dist_module_upscaler), runtimeConfig.maxVramSpec.ifBlank { budgetAutoLabel })
            PipelineChip(stringResource(R.string.sd_dist_module_video_frames), runtimeConfig.rpcServers.ifBlank { localOnlyLabel })
        }
    }
}

@Composable
private fun PipelineChip(title: String, subtitle: String) {
    ElevatedCard(
        modifier = Modifier.width(170.dp).heightIn(min = 86.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle.ifBlank { "-" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WorkerStatusCard(isRunning: Boolean, address: String, connections: Int) {
    AppSectionCard(tonalAccent = if (isRunning) Color(0xFF1B8A5A).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(if (isRunning) stringResource(R.string.sd_dist_status_running) else stringResource(R.string.sd_dist_status_stopped), fontWeight = FontWeight.SemiBold)
                Text(address, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.sd_dist_connection_count, connections), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun NumberSliderField(
    label: String,
    valueDraft: String,
    onValueDraftChange: (String) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = ""
) {
    val numeric = valueDraft.toFloatOrNull()?.coerceIn(range.start, range.endInclusive) ?: range.start
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = valueDraft,
                onValueChange = { onValueDraftChange(it.filter(Char::isDigit).take(6)) },
                suffix = { if (suffix.isNotBlank()) Text(suffix) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(150.dp),
                singleLine = true
            )
        }
        Slider(
            value = numeric,
            onValueChange = { onValueDraftChange(it.toInt().toString()) },
            valueRange = range
        )
    }
}

@Composable
private fun WorkerRow(
    worker: SdDistributedWorkerEntity,
    rpcName: String,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = worker.isEnabled, onCheckedChange = { onToggle() })
                Column(modifier = Modifier.weight(1f)) {
                    Text(worker.deviceName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${worker.host}:${worker.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                AssistChip(onClick = {}, label = { Text(rpcName, maxLines = 1) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sd_dist_worker_budget_line, worker.ramMB, worker.threads),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.sd_dist_edit_worker))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.sd_dist_delete_worker))
                }
            }
        }
    }
}

@Composable
private fun PipelineWorkerLane(worker: SdDistributedWorkerRuntime) {
    val liveColor = if (worker.isConnected) RenderFarmPalette.lime else MaterialTheme.colorScheme.error
    ElevatedCard(shape = RoundedCornerShape(8.dp), colors = CardDefaults.elevatedCardColors(containerColor = RenderFarmPalette.graphiteSoft)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(worker.deviceName, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(worker.rpcName.ifBlank { "-" }, style = MaterialTheme.typography.labelMedium, color = RenderFarmPalette.violet)
                }
                AssistChip(
                    onClick = {},
                    label = { Text(if (worker.isConnected) stringResource(R.string.sd_dist_live_online) else stringResource(R.string.sd_dist_live_offline)) },
                    border = null
                )
            }
            Text(
                if (worker.isLocalMaster) {
                    stringResource(R.string.sd_dist_master_resource_line, worker.ramMB, worker.threads)
                } else {
                    stringResource(
                        R.string.sd_dist_worker_resource_line,
                        worker.host,
                        worker.port,
                        worker.ramMB,
                        worker.threads,
                        stringResource(R.string.sd_dist_threads_short)
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.74f)
            )
            Text(
                stringResource(
                    R.string.sd_dist_last_seen,
                    if (worker.lastSeenAt > 0) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(worker.lastSeenAt)) else "-"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = liveColor
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (worker.plannedAssignments.isEmpty()) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.sd_dist_unassigned), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                } else {
                    worker.plannedAssignments.forEach { assignment ->
                        val label = if (assignment.isSplit) {
                            stringResource(R.string.sd_dist_planned_split_assignment, moduleLabel(assignment.module), assignment.estimatedLayerShare)
                        } else {
                            stringResource(R.string.sd_dist_planned_whole_assignment, moduleLabel(assignment.module))
                        }
                        AssistChip(onClick = {}, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeSummary(config: SdDistributedRuntimeConfig) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryLine(stringResource(R.string.sd_dist_enable_distributed), if (config.enabled) stringResource(R.string.sd_dist_enabled) else stringResource(R.string.sd_dist_disabled))
        SummaryLine(stringResource(R.string.sd_dist_rpc_servers), config.rpcServers.ifBlank { "-" })
        SummaryLine(stringResource(R.string.sd_dist_placement_mode), placementModeLabel(config.placementMode))
        SummaryLine(stringResource(R.string.sd_dist_backend_spec), config.backendSpec.ifBlank { "-" })
        SummaryLine(stringResource(R.string.sd_dist_params_backend_spec), config.paramsBackendSpec.ifBlank { "-" })
        SummaryLine(stringResource(R.string.sd_dist_split_mode), config.splitMode.cliName)
        SummaryLine(stringResource(R.string.sd_dist_max_vram), config.maxVramSpec.ifBlank { "-" })
    }
}

@Composable
private fun placementModeLabel(mode: SdDistributedPlacementMode): String = when (mode) {
    SdDistributedPlacementMode.AUTO_RAM -> stringResource(R.string.sd_dist_placement_auto_ram)
    SdDistributedPlacementMode.AUTO_FIT -> stringResource(R.string.sd_dist_placement_auto_fit)
    SdDistributedPlacementMode.COMPONENTS -> stringResource(R.string.sd_dist_placement_components)
    SdDistributedPlacementMode.MANUAL -> stringResource(R.string.sd_dist_placement_manual)
}

@Composable
private fun moduleLabel(module: String): String = when (module) {
    "diffusion" -> stringResource(R.string.sd_dist_module_diffusion)
    "te" -> stringResource(R.string.sd_dist_module_text_encoder)
    "vae" -> stringResource(R.string.sd_dist_module_vae)
    "upscaler" -> stringResource(R.string.sd_dist_module_upscaler)
    "controlnet" -> stringResource(R.string.sd_dist_module_controlnet)
    else -> module
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.width(150.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LogsCard(title: String, logs: List<String>) {
    AppSectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState()).padding(12.dp)
        ) {
            Text(
                text = logs.takeLast(120).joinToString("\n").ifBlank { stringResource(R.string.sd_dist_no_logs) },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun WarningBand(text: String) {
    AppSectionCard(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f), tonalAccent = Color.Transparent) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun CodeBlock(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f), RoundedCornerShape(8.dp)).padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LabeledTextField(
    labelRes: Int,
    value: String,
    onValueChange: (String) -> Unit,
    placeholderRes: Int,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        placeholder = { Text(stringResource(placeholderRes)) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        minLines = 1
    )
}

@Composable
private fun TwoColumnFields(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) { first() }
        Column(modifier = Modifier.weight(1f)) { second() }
    }
}

@Composable
private fun AutoRamScopePicker(
    value: SdDistributedAutoRamScope,
    onChange: (SdDistributedAutoRamScope) -> Unit
) {
    Text(stringResource(R.string.sd_dist_auto_ram_scope), style = MaterialTheme.typography.labelLarge)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SdDistributedAutoRamScope.entries.forEach { scope ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .clickable { onChange(scope) }
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = value == scope,
                    onClick = { onChange(scope) }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(autoRamScopeLabel(scope), fontWeight = FontWeight.SemiBold)
                    Text(
                        autoRamScopeDescription(scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun autoRamScopeLabel(scope: SdDistributedAutoRamScope): String = when (scope) {
    SdDistributedAutoRamScope.DIFFUSION_ONLY -> stringResource(R.string.sd_dist_auto_ram_scope_diffusion)
    SdDistributedAutoRamScope.FULL_PIPELINE -> stringResource(R.string.sd_dist_auto_ram_scope_full)
}

@Composable
private fun autoRamScopeDescription(scope: SdDistributedAutoRamScope): String = when (scope) {
    SdDistributedAutoRamScope.DIFFUSION_ONLY -> stringResource(R.string.sd_dist_auto_ram_scope_diffusion_desc)
    SdDistributedAutoRamScope.FULL_PIPELINE -> stringResource(R.string.sd_dist_auto_ram_scope_full_desc)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacementModeRow(value: SdDistributedPlacementMode, onChange: (SdDistributedPlacementMode) -> Unit) {
    Text(stringResource(R.string.sd_dist_placement_mode), style = MaterialTheme.typography.labelLarge)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SdDistributedPlacementMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .clickable { onChange(mode) }
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                selected = value == mode,
                    onClick = { onChange(mode) }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(placementModeLabel(mode), fontWeight = FontWeight.SemiBold)
                    Text(placementModeDescription(mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitModeRow(value: SdDistributedSplitMode, onChange: (SdDistributedSplitMode) -> Unit) {
    Text(stringResource(R.string.sd_dist_split_mode), style = MaterialTheme.typography.labelLarge)
    SimpleStringPicker(
        label = stringResource(R.string.sd_dist_split_mode),
        selected = value.cliName,
        options = SdDistributedSplitMode.entries.map { it.cliName },
        offLabel = SdDistributedSplitMode.LAYER.cliName,
        onSelected = { onChange(SdDistributedSplitMode.fromCliName(it)) }
    )
    Text(stringResource(R.string.sd_dist_row_split_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun modulesForWorker(
    worker: SdDistributedWorkerEntity,
    runtimeConfig: SdDistributedRuntimeConfig
): List<String> {
    val name = worker.backendDevice.ifBlank { worker.deviceName }
    if (runtimeConfig.backendSpec.isBlank()) return emptyList()
    return runtimeConfig.backendSpec.split(",")
        .mapNotNull { assignment ->
            val parts = assignment.split("=")
            if (parts.size != 2) return@mapNotNull null
            val devices = parts[1].split("&")
            parts[0].takeIf { devices.any { device -> device.contains(name, ignoreCase = true) || device.contains(worker.host) } }
        }
}

private fun filterSdDistributedComponents(
    models: List<ModelEntity>,
    family: SdModelFamily?,
    variant: String?
): List<ModelEntity> {
    if (family == null) return emptyList()
    return models.filter { it.matchesSdFamily(family, variant) }
}

private fun sdDistributedComponentRoleLabelRes(role: SdComponentRole): Int = when (role) {
    SdComponentRole.MAIN_MODEL -> R.string.imagegen_component_main_model
    SdComponentRole.VAE -> R.string.imagegen_component_vae
    SdComponentRole.TAE -> R.string.imagegen_component_tae
    SdComponentRole.CLIP_L -> R.string.imagegen_component_clip_l
    SdComponentRole.CLIP_G -> R.string.imagegen_component_clip_g
    SdComponentRole.T5XXL -> R.string.imagegen_component_t5xxl
    SdComponentRole.LLM -> R.string.imagegen_component_llm
    SdComponentRole.LLM_VISION -> R.string.imagegen_component_llm_vision
    SdComponentRole.CONTROLNET -> R.string.imagegen_component_controlnet
    SdComponentRole.LORA -> R.string.imagegen_component_lora
    SdComponentRole.PHOTOMAKER -> R.string.imagegen_component_photomaker
    SdComponentRole.UPSCALER -> R.string.imagegen_component_upscaler
}

private fun sdDistributedEmptyComponentMessageRes(role: SdComponentRole): Int = when (role) {
    SdComponentRole.VAE -> R.string.imagegen_no_vae_installed
    SdComponentRole.TAE -> R.string.imagegen_no_tae_installed
    SdComponentRole.CLIP_L -> R.string.imagegen_no_clip_l
    SdComponentRole.CLIP_G -> R.string.imagegen_no_clip_g
    SdComponentRole.T5XXL -> R.string.imagegen_no_t5xxl
    SdComponentRole.LLM -> R.string.imagegen_no_llm
    SdComponentRole.LLM_VISION -> R.string.imagegen_no_llm_vision
    SdComponentRole.CONTROLNET -> R.string.imagegen_no_controlnet
    SdComponentRole.LORA -> R.string.imagegen_no_lora
    SdComponentRole.PHOTOMAKER -> R.string.imagegen_no_photomaker
    SdComponentRole.MAIN_MODEL,
    SdComponentRole.UPSCALER -> R.string.sd_dist_no_models_available
}

private fun sdDistributedComponentRoleLabel(context: Context, role: SdComponentRole): String =
    context.getString(sdDistributedComponentRoleLabelRes(role))

@Composable
private fun sdDistributedComponentRoleLabel(role: SdComponentRole): String =
    stringResource(sdDistributedComponentRoleLabelRes(role))

private fun String.toSdPlacementMode(): SdDistributedPlacementMode =
    runCatching { SdDistributedPlacementMode.valueOf(this) }.getOrDefault(SdDistributedPlacementMode.AUTO_RAM)

private fun String.toSdMasterImageMode(): SDMode = when (uppercase(Locale.US)) {
    "IMG2IMG" -> SDMode.IMG2IMG
    "UPSCALE" -> SDMode.UPSCALE
    else -> SDMode.TXT2IMG
}

private fun String.toSdMasterVideoMode(): VideoGenerationMode = when (uppercase(Locale.US)) {
    "IMG2VID" -> VideoGenerationMode.IMG2VID
    else -> VideoGenerationMode.TXT2VID
}

private fun parseSdDimensions(value: String, defaultWidth: Int, defaultHeight: Int): Pair<Int, Int> {
    val parts = value
        .lowercase(Locale.US)
        .replace(" ", "")
        .split("x")
    val width = parts.getOrNull(0)?.toIntOrNull() ?: defaultWidth
    val height = parts.getOrNull(1)?.toIntOrNull() ?: defaultHeight
    return width.coerceAtLeast(64) to height.coerceAtLeast(64)
}

@Composable
private fun placementModeDescription(mode: SdDistributedPlacementMode): String = when (mode) {
    SdDistributedPlacementMode.AUTO_RAM -> stringResource(R.string.sd_dist_placement_auto_ram_desc)
    SdDistributedPlacementMode.AUTO_FIT -> stringResource(R.string.sd_dist_placement_auto_fit_desc)
    SdDistributedPlacementMode.COMPONENTS -> stringResource(R.string.sd_dist_placement_components_desc)
    SdDistributedPlacementMode.MANUAL -> stringResource(R.string.sd_dist_placement_manual_desc)
}
