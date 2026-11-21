package com.example.llamadroid.ui.training

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyEvent
import com.example.llamadroid.branchy.BranchyLiveState
import com.example.llamadroid.branchy.BranchyRunEntity
import com.example.llamadroid.branchy.BranchyRunStatus
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.components.AppTaskActionFooter

/**
 * Edit the devices of a paused run: add or remove helpers, change the master's and each helper's threads, then
 * recalculate for the saved model layout and save. Reuses the Devices-step cards of the wizard; the run's config
 * is only written after a branch-parallel consolidation (when the placement changed) succeeded.
 */
@Composable
fun BranchyRunEditor(runId: Long, session: Int, run: BranchyRunEntity?, live: BranchyLiveState, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: BranchyRunEditViewModel = viewModel(
        key = BranchyEditSeed.sessionKey(runId, session),
        factory = remember(runId, session) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = BranchyRunEditViewModel(app, runId) as T
            }
        }
    )
    val state by vm.wizard.collectAsState()
    val ready by vm.ready.collectAsState()
    val changed by vm.changed.collectAsState()
    val op by vm.op.collectAsState()
    val helpers by vm.helperDevices.collectAsState()
    val helperStatus by vm.helperStatus.collectAsState()
    val helperThreads by vm.helperThreads.collectAsState()
    val bench by vm.bench.collectAsState()
    val checkpointEvery by vm.checkpointEvery.collectAsState()
    val context = LocalContext.current

    val busy = op is BranchyEditOp.Consolidating || op is BranchyEditOp.Saving
    val editable = !live.isActive && run?.status == BranchyRunStatus.PAUSED.value && ready
    // Back is held while consolidating or saving so the user does not leave a half-finished edit unnoticed.
    BackHandler { if (!busy) onClose() }
    val savedText = stringResource(R.string.branchy_r5_edit_saved)
    LaunchedEffect(op) {
        if (op is BranchyEditOp.Saved) {
            Toast.makeText(context, savedText, Toast.LENGTH_LONG).show()
            onClose()
        }
    }
    DisposableEffect(editable) {
        if (editable) {
            vm.startDiscovery()
            vm.startHelperProbing()
        }
        onDispose {
            vm.stopDiscovery()
            vm.stopHelperProbing()
        }
    }
    val names = helperNames(helpers)
    val canSave = vm.canSave(state, changed, op)

    AppScreenScaffold(
        title = stringResource(R.string.branchy_r5_edit_title),
        subtitle = run?.name,
        onBack = { if (!busy) onClose() },
        bottomBar = {
            AppTaskActionFooter {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onClose,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = vm::save,
                        enabled = editable && canSave,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.action_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (editable && !canSave && !busy) {
                    Text(
                        text = stringResource(R.string.branchy_r5_edit_save_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AppChromeDefaults.ScreenPadding,
                end = AppChromeDefaults.ScreenPadding,
                top = 8.dp,
                bottom = AppChromeDefaults.ScreenPadding
            ),
            verticalArrangement = Arrangement.spacedBy(AppChromeDefaults.SectionSpacing)
        ) {
            item(key = "edit_intro") {
                AppSectionCard {
                    AppSectionTitle(
                        title = stringResource(R.string.branchy_r5_edit_intro_title),
                        supporting = stringResource(R.string.branchy_r5_edit_intro_hint)
                    )
                }
            }
            if (!editable) {
                item(key = "edit_locked") {
                    AppSectionCard {
                        Text(
                            text = stringResource(
                                when {
                                    live.isActive -> R.string.branchy_r5_edit_active
                                    !ready -> R.string.branchy_r5_edit_no_plan
                                    else -> R.string.branchy_r5_edit_not_paused
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                editOpItems(op, names, vm::clearError)
                branchyDevicesStepItems(
                    state = state,
                    helpers = helpers,
                    helperStatus = helperStatus,
                    edit = vm::updateWizard,
                    onToggle = vm::toggleHelper,
                    onEditDevice = vm::editHelper,
                    onAdd = vm::addHelperManually,
                    onRemove = vm::removeHelper,
                    onComputePlan = vm::recalculate,
                    cores = vm.cores,
                    showBench = false,
                    bench = bench,
                    onPreferredInterface = vm::setHelperInterface,
                    onHelperThreads = vm::setHelperThreads,
                    onBenchHelper = vm::benchHelper,
                    helperThreads = helperThreads
                )
                item(key = "edit_checkpoint_every") {
                    BranchyCheckpointEveryCard(
                        committed = checkpointEvery,
                        planDefault = (state.plan as? BranchyPlanUi.Ready)?.plan?.train?.checkpointEvery,
                        onChange = vm::setCheckpointEvery
                    )
                }
            }
        }
    }
}

/** Consolidation progress and the last error, above the device cards. */
private fun androidx.compose.foundation.lazy.LazyListScope.editOpItems(
    op: BranchyEditOp,
    names: Map<String, String>,
    onDismissError: () -> Unit
) {
    when (op) {
        is BranchyEditOp.Consolidating -> item(key = "edit_consolidating") { ConsolidateCard(op.progress, names) }
        BranchyEditOp.Saving -> item(key = "edit_saving") {
            AppSectionCard {
                Text(stringResource(R.string.branchy_r5_edit_saving), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        is BranchyEditOp.Failed -> item(key = "edit_error") {
            AppSectionCard {
                Text(
                    text = stringResource(R.string.branchy_r5_edit_failed, op.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.branchy_r5_edit_failed_kept),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onDismissError, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_close), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        BranchyEditOp.Idle, BranchyEditOp.Saved -> Unit
    }
}

@Composable
private fun ConsolidateCard(progress: BranchyEvent.Consolidate?, names: Map<String, String>) {
    AppSectionCard {
        AppSectionTitle(
            title = stringResource(R.string.branchy_r5_edit_consolidating_title),
            supporting = stringResource(R.string.branchy_r5_edit_consolidating_hint)
        )
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            val total = progress.totalBytes.coerceAtLeast(1L)
            Text(
                text = stringResource(
                    R.string.branchy_r5_edit_consolidating_unit,
                    progress.unit,
                    names[progress.device] ?: progress.device,
                    BranchyWizardHelpers.formatMb(progress.doneBytes / MB),
                    BranchyWizardHelpers.formatMb(progress.totalBytes / MB)
                ),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = { (progress.doneBytes.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private const val MB = 1_048_576.0
