@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.llamadroid.ui.distributed

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.media.MediaGalleryIndex
import com.example.llamadroid.service.DistributedPerfSettings
import com.example.llamadroid.service.SdDistributedService
import com.example.llamadroid.ui.ai.VideoGalleryDetail
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.distributed.common.DistributedTechnicalLogSection
import com.example.llamadroid.ui.distributed.sd.FarmStatusChip
import com.example.llamadroid.ui.distributed.sd.HubActionCard
import com.example.llamadroid.ui.distributed.sd.MediaPipelinePreview
import com.example.llamadroid.ui.distributed.sd.NumberSliderField
import com.example.llamadroid.ui.distributed.sd.PipelineWorkerLane
import com.example.llamadroid.ui.distributed.sd.RenderFarmPalette
import com.example.llamadroid.ui.distributed.sd.RuntimeSummary
import com.example.llamadroid.ui.distributed.sd.SdAdvancedHint
import com.example.llamadroid.ui.distributed.sd.SdDistributedMasterContent
import com.example.llamadroid.ui.distributed.sd.SdMasterMode
import com.example.llamadroid.ui.distributed.sd.SdRunProgressSection
import com.example.llamadroid.ui.distributed.sd.SdDistributedPage
import com.example.llamadroid.ui.distributed.sd.SdGeneratedMediaCard
import com.example.llamadroid.ui.distributed.sd.SdGeneratedMediaDetailDialog
import com.example.llamadroid.ui.distributed.sd.SdGeneratedMediaFilter
import com.example.llamadroid.ui.distributed.sd.SdGeneratedMediaItem
import com.example.llamadroid.ui.distributed.sd.SdGeneratedMediaKind
import com.example.llamadroid.ui.distributed.sd.SdHowItWorks
import com.example.llamadroid.ui.distributed.sd.SdHowToScreen
import com.example.llamadroid.ui.distributed.sd.SdNetworkStatusPanel
import com.example.llamadroid.ui.distributed.sd.SdWorkerStatusPanel
import com.example.llamadroid.ui.distributed.sd.WarningBand
import com.example.llamadroid.ui.distributed.sd.openSdGeneratedMedia
import com.example.llamadroid.ui.distributed.sd.shareSdGeneratedMedia
import com.example.llamadroid.ui.distributed.sd.toSdGeneratedMediaItem
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SdDistributedHubScreen(navController: NavController) {
    val walkthroughTargets = LocalWalkthroughTargets.current
    SdDistributedPage(
        navController = navController,
        title = stringResource(R.string.sd_ui2_hub_title),
        subtitle = stringResource(R.string.sd_ui2_hub_subtitle)
    ) {
        SdHowItWorks(SdHowToScreen.HUB)
        MediaPipelinePreview()
        HubActionCard(
            icon = Icons.Default.PlayArrow,
            title = stringResource(R.string.sd_ui2_hub_helper_title),
            description = stringResource(R.string.sd_ui2_hub_helper_desc),
            action = stringResource(R.string.sd_ui2_open_helper),
            onClick = {
                walkthroughTargets?.recordEvent("media.roles")
                navController.navigate(Screen.SdDistributedWorker.route)
            },
            modifier = Modifier.walkthroughTarget("media.roles")
        )
        HubActionCard(
            icon = Icons.Default.Image,
            title = stringResource(R.string.sd_r4_images_title),
            description = stringResource(R.string.sd_r4_hub_images_desc),
            action = stringResource(R.string.sd_r4_open_images),
            onClick = { navController.navigate(Screen.SdDistributedImages.route) }
        )
        HubActionCard(
            icon = Icons.Default.Movie,
            title = stringResource(R.string.sd_r4_video_title),
            description = stringResource(R.string.sd_r4_hub_video_desc),
            action = stringResource(R.string.sd_r4_open_video),
            onClick = { navController.navigate(Screen.SdDistributedVideo.route) }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    var filter by rememberSaveable { mutableStateOf(SdGeneratedMediaFilter.ALL) }
    var refreshNonce by remember { mutableStateOf(0) }
    var mediaItems by remember { mutableStateOf<List<SdGeneratedMediaItem>>(emptyList()) }
    var selectedMedia by remember { mutableStateOf<SdGeneratedMediaItem?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshNonce++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val observer = MediaGalleryIndex.observe(context) { refreshNonce++ }
        onDispose { observer.close() }
    }

    LaunchedEffect(refreshNonce) {
        mediaItems = withContext(Dispatchers.IO) {
            MediaGalleryIndex.scan(context)
                .filter(MediaGalleryIndex::isProvenDistributed)
                .map(::toSdGeneratedMediaItem)
        }
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
        AppSectionCard(
            tonalAccent = RenderFarmPalette.violet.copy(alpha = 0.12f),
            modifier = Modifier.walkthroughTarget("media.gallery")
        ) {
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
                IconButton(onClick = {
                    walkthroughTargets?.recordEvent("media.gallery")
                    refreshNonce++
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
            }

            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SdGeneratedMediaFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = {
                            walkthroughTargets?.recordEvent("media.gallery")
                            filter = item
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = {
                            Text(
                                when (item) {
                                    SdGeneratedMediaFilter.ALL -> stringResource(R.string.sd_dist_gallery_all)
                                    SdGeneratedMediaFilter.IMAGES -> stringResource(R.string.sd_dist_gallery_images)
                                    SdGeneratedMediaFilter.VIDEOS -> stringResource(R.string.sd_dist_gallery_videos)
                                },
                                maxLines = 1
                            )
                        }
                    )
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
                        onOpen = { selectedMedia = item },
                        onShare = { shareSdGeneratedMedia(context, item) }
                    )
                }
            }
        }
    }

    selectedMedia?.let { item ->
        val videoMetadata = item.videoMetadata
        if (item.kind == SdGeneratedMediaKind.VIDEO && videoMetadata != null) {
            VideoGalleryDetail(
                metadata = videoMetadata,
                navController = navController,
                onDismiss = { selectedMedia = null },
                onDeleted = {
                    selectedMedia = null
                    refreshNonce++
                }
            )
        } else {
            SdGeneratedMediaDetailDialog(
                item = item,
                onDismiss = { selectedMedia = null },
                onOpenExternal = { openSdGeneratedMedia(context, item) },
                onShare = { shareSdGeneratedMedia(context, item) }
            )
        }
    }
}

@Composable
fun SdDistributedWorkerScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val isRunning by SdDistributedService.isWorkerRunning.collectAsState()
    val localIp by SdDistributedService.localIp.collectAsState()
    val lanIp by SdDistributedService.lanIp.collectAsState()
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

    var cacheSizeBytes by remember { mutableLongStateOf(0L) }
    var rpcDebug by remember { mutableStateOf(DistributedPerfSettings.rpcDebugEnabled(context)) }

    LaunchedEffect(Unit) {
        SdDistributedService.loadWorkerSettings(context)
    }
    LaunchedEffect(isRunning) {
        cacheSizeBytes = withContext(Dispatchers.IO) { SdDistributedService.workerCacheSizeBytes(context) }
    }

    SdDistributedPage(
        navController = navController,
        title = stringResource(R.string.sd_ui2_worker_title),
        subtitle = stringResource(R.string.sd_ui2_worker_subtitle),
        footer = {
            if (isRunning) {
                OutlinedButton(
                    onClick = { SdDistributedService.stopWorker(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_ui2_worker_stop), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Button(
                    onClick = {
                        SdDistributedService.startWorker(
                            context = context,
                            host = hostDraft.ifBlank { "0.0.0.0" },
                            port = portDraft.toIntOrNull() ?: SdDistributedService.RPC_DEFAULT_PORT,
                            ramMB = ramDraft.toIntOrNull() ?: 4096,
                            threads = threadsDraft.toIntOrNull() ?: 4,
                            deviceName = deviceName.ifBlank { resources.getString(R.string.sd_dist_default_worker_name) },
                            cacheEnabled = cacheEnabled
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_ui2_worker_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    ) {
        SdHowItWorks(SdHowToScreen.WORKER)
        SdWorkerStatusPanel(
            isRunning = isRunning,
            address = "${localIp ?: "0.0.0.0"}:${portDraft.toIntOrNull() ?: SdDistributedService.RPC_DEFAULT_PORT}",
            connections = connections,
            lanAddress = lanIp?.takeIf { it != localIp }?.let { "$it:${portDraft.toIntOrNull() ?: SdDistributedService.RPC_DEFAULT_PORT}" }
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
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text(stringResource(R.string.sd_dist_device_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        AppAdvancedSection(title = stringResource(R.string.sd_ui2_worker_advanced_title)) {
            SdAdvancedHint(stringResource(R.string.sd_ui2_worker_advanced_hint))
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
                Text(
                    text = stringResource(
                        R.string.dist_ui_w_adv_cache_size,
                        android.text.format.Formatter.formatShortFileSize(context, cacheSizeBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        SdDistributedService.clearWorkerCache(context)
                        cacheSizeBytes = SdDistributedService.workerCacheSizeBytes(context)
                    },
                    enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sd_dist_worker_cache_clear), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.dist_perf_rpc_debug),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.dist_perf_rpc_debug_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = rpcDebug,
                        enabled = !isRunning,
                        onCheckedChange = {
                            rpcDebug = it
                            DistributedPerfSettings.setRpcDebugEnabled(context, it)
                        }
                    )
                }
            }
        }
        WarningBand(text = stringResource(R.string.sd_dist_security_warning))
        DistributedTechnicalLogSection(lines = logs, onClear = { SdDistributedService.clearLogs() })
    }
}

@Composable
fun SdDistributedNetworkScreen(navController: NavController) {
    val walkthroughTargets = LocalWalkthroughTargets.current
    val activeWorkers by SdDistributedService.activeWorkers.collectAsState()
    val runtimeConfig by SdDistributedService.runtimeConfig.collectAsState()
    val logs by SdDistributedService.logs.collectAsState()

    SdDistributedPage(
        navController = navController,
        title = stringResource(R.string.sd_dist_network_title),
        subtitle = stringResource(R.string.sd_dist_network_subtitle)
    ) {
        SdHowItWorks(SdHowToScreen.NETWORK)
        SdNetworkStatusPanel(activeHelpers = activeWorkers.size)
        SdRunProgressSection(navController)
        MediaPipelinePreview(runtimeConfig = runtimeConfig, workers = activeWorkers)
        AppSectionCard(modifier = Modifier.walkthroughTarget("media.workers")) {
            Text(
                text = stringResource(R.string.sd_dist_live_topology),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (activeWorkers.isEmpty()) {
                Text(stringResource(R.string.sd_dist_no_active_workers), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                MediaTopologyConnector()
                ResponsiveTopologyNodes(activeWorkers) { worker -> PipelineWorkerLane(worker) }
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
        DistributedTechnicalLogSection(lines = logs, onClear = { SdDistributedService.clearLogs() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdDistributedRunConfigScreen(navController: NavController) {
    SdDistributedMasterContent(navController, SdMasterMode.IMAGES)
}

/** Image main-device screen; also the target of the legacy `sd_distributed_master` route. */
@Composable
fun SdDistributedMasterScreen(navController: NavController) {
    SdDistributedMasterContent(navController, SdMasterMode.IMAGES)
}

@Composable
fun SdDistributedVideoScreen(navController: NavController) {
    SdDistributedMasterContent(navController, SdMasterMode.VIDEO)
}
