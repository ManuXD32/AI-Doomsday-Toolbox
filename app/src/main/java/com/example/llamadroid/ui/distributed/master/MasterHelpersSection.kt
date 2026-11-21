@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
import com.example.llamadroid.service.DistributedLlamaArguments
import com.example.llamadroid.service.DistributedLlamaLaunchProfile
import com.example.llamadroid.service.DistributedLlamaLaunchResolver
import com.example.llamadroid.service.DistributedLlamaResolveRequest
import com.example.llamadroid.service.DistributedMasterLlamaService
import com.example.llamadroid.service.DistributedConnectivity
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
import com.example.llamadroid.ui.components.NetInterfacePrefChips
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

/** Step 1: nearby helpers (with a searching state), the saved list and the Add button. */
@Composable
internal fun MasterHelpersSection(s: MasterScreenState) {
    val liveWorkers by DistributedService.workers.collectAsState()
    val adverts by DistributedService.workerAdverts.collectAsState()
    // "Test connection" results per helper address; null while a probe is running.
    val probeTexts = remember { mutableStateMapOf<String, String?>() }
    with(s) {
        AppSectionCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppSectionTitle(
                    title = stringResource(R.string.dist_ui_step_helpers),
                    supporting = stringResource(R.string.dist_ui_step_helpers_desc)
                )
                if (newDiscoveredWorkers.isNotEmpty()) {
                    Text(
                        stringResource(R.string.dist_ui_found_nearby),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    newDiscoveredWorkers.forEach { service ->
                        val hostName: String = service.serviceName.removePrefix("LlamaWorker-")
                        val resolvedIp = service.host.hostAddress.orEmpty()
                        // The worker's VPN address when both devices have a VPN, else the resolved one.
                        val ip = DistributedService.endpointIpFor(service)
                        val advert = adverts["$resolvedIp:${service.port}"] ?: DistributedService.advertFor(service)
                        DistributedHelperListItem(
                            name = hostName,
                            address = "$ip:${service.port}",
                            advertLabel = DistributedPerfText.advertLabel(
                                context, advert.ramFreeMb, advert.cores
                            ),
                            highlighted = true,
                            trailing = {
                                FilledTonalIconButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            // VPN address first, but fall back to the Wi-Fi one when it is unreachable.
                                            val endpointIp = kotlinx.coroutines.withContext(
                                                kotlinx.coroutines.Dispatchers.IO
                                            ) { DistributedService.reachableEndpointIpFor(service) }
                                            val newWorker = SavedWorker(
                                                ip = endpointIp,
                                                altIp = DistributedService.endpointCandidatesFor(service)
                                                    .firstOrNull { it != endpointIp },
                                                port = service.port,
                                                deviceName = hostName,
                                                // Advertised over NSD, else 4096; the user can edit it.
                                                ramMB = advert.ramOrDefault(),
                                                isEnabled = true
                                            )
                                            db.savedWorkerDao().insertWorker(newWorker)
                                            Toast.makeText(
                                                context,
                                                resources.getString(R.string.dist_worker_added),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = stringResource(R.string.dist_add_worker_btn)
                                    )
                                }
                            }
                        )
                    }
                } else {
                    DistributedSearchingState()
                }

                if (savedWorkers.isEmpty()) {
                    DistributedNoHelpersState(onAdd = { showAddWorkerDialog = true })
                } else {
                    Text(
                        stringResource(R.string.dist_workers_count, savedWorkers.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    savedWorkers.forEach { saved ->
                        val live = liveWorkers.firstOrNull { it.ip == saved.ip && it.port == saved.port }
                        val advert = adverts["${saved.ip}:${saved.port}"]
                        val loadText = saved.assignedProportion
                            ?.let { stringResource(R.string.dist_worker_load, (it * 100).toInt()) }
                            ?: stringResource(R.string.dist_auto)
                        DistributedHelperListItem(
                            name = saved.deviceName,
                            address = "${saved.ip}:${saved.port}",
                            detail = stringResource(R.string.dist_ui_helper_share, saved.ramMB) +
                                " · " + stringResource(R.string.dist_worker_load_line, loadText),
                            advertLabel = advert?.let {
                                DistributedPerfText.advertLabel(context, it.ramFreeMb, it.cores)
                            },
                            rttMs = live?.rttMs,
                            showRtt = saved.isEnabled,
                            enabled = saved.isEnabled,
                            leading = {
                                Switch(
                                    checked = saved.isEnabled,
                                    onCheckedChange = { enabled ->
                                        coroutineScope.launch {
                                            db.savedWorkerDao().setWorkerEnabled(saved.id, enabled)
                                        }
                                    }
                                )
                            },
                            footer = {
                                val address = "${saved.ip}:${saved.port}"
                                val testing = probeTexts.containsKey(address) && probeTexts[address] == null
                                val reachableLabel = { ms: Long ->
                                    resources.getString(R.string.dist_r3_probe_ok, ms)
                                }
                                val notReachableLabel = stringResource(R.string.dist_r3_probe_fail)
                                if (!saved.altIp.isNullOrBlank()) {
                                    NetInterfacePrefChips(
                                        pref = saved.preferredInterface,
                                        activeHost = saved.ip,
                                        onChange = { pref ->
                                            coroutineScope.launch {
                                                db.savedWorkerDao().setPreferredInterface(saved.id, pref)
                                                withContext(Dispatchers.IO) {
                                                    DistributedService.applyInterfacePreferences(
                                                        mapOf("${saved.ip}:${saved.port}" to pref)
                                                    ) { oldIp, port, newIp, newAlt ->
                                                        db.savedWorkerDao().switchAddress(oldIp, port, newIp, newAlt)
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    TextButton(
                                        enabled = !testing,
                                        onClick = {
                                            probeTexts[address] = null
                                            coroutineScope.launch {
                                                val result = DistributedConnectivity.probeAll(listOf(address))[address]
                                                probeTexts[address] = result?.let {
                                                    DistributedConnectivity.formatProbe(it, reachableLabel, notReachableLabel)
                                                } ?: notReachableLabel
                                            }
                                        }
                                    ) {
                                        Text(
                                            stringResource(
                                                if (testing) R.string.dist_r3_testing else R.string.dist_r3_test_connection
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    probeTexts[address]?.let { text ->
                                        Text(
                                            text = text,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (text == notReachableLabel) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.tertiary
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                    }
                                }
                            },
                            trailing = {
                                IconButton(onClick = {
                                    workerToEdit = saved
                                    showEditWorkerDialog = true
                                }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.action_edit),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = {
                                    coroutineScope.launch { db.savedWorkerDao().deleteWorker(saved) }
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.action_delete),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = { showAddWorkerDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.dist_ui_add_manually),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
