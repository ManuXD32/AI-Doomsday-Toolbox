package com.example.llamadroid.ui.distributed.branchy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.branchy.infer.BranchyInferArgs
import com.example.llamadroid.branchy.infer.BranchyPlacement
import com.example.llamadroid.branchy.infer.HelperLink
import com.example.llamadroid.branchy.infer.InferStep
import com.example.llamadroid.branchy.infer.InferStepState
import com.example.llamadroid.modeltools.BranchyExportSupport
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.navigation.Screen

/**
 * Main device of distributed Branchy inference: pick a run, pick helpers, edit the placement, then export, push the
 * slices and start the managed native llama-server card. The whole page scrolls; the Start/Cancel/Stop control is a
 * fixed footer.
 */
@Composable
fun BranchyInferenceMainScreen(navController: NavController) {
    val vm: BranchyInferenceMainViewModel = viewModel()
    val s by vm.state.collectAsState()
    val running = s.phase == InferPhase.RUNNING
    val ready = s.phase == InferPhase.READY

    AppScreenScaffold(
        title = stringResource(R.string.branchy_infer_title),
        subtitle = stringResource(R.string.branchy_infer_subtitle),
        onBack = { navController.popBackStack() },
        bottomBar = {
            AppTaskActionFooter {
                when {
                    running -> OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.branchy_infer_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ready -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = vm::stopServer, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.branchy_infer_stop_server), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            onClick = { navController.navigate(Screen.Chats.route) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.branchy_infer_open_chat), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    else -> Button(onClick = vm::start, enabled = vm.canStart(s), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.branchy_infer_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppChromeDefaults.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(AppChromeDefaults.SectionSpacing)
        ) {
            AppSectionCard {
                AppSectionTitle(title = stringResource(R.string.branchy_infer_honest_title))
                Text(stringResource(R.string.branchy_infer_honest_body, BranchyInferArgs.SLOW_RTT_MS.toString()), style = MaterialTheme.typography.bodyMedium)
            }

            s.message?.let { m ->
                Text(
                    if (m.arg != null) stringResource(m.res, m.arg) else stringResource(m.res),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (!running && !ready) {
                AppSectionCard {
                    AppSectionTitle(title = stringResource(R.string.branchy_infer_run_title))
                    if (s.runs.isEmpty()) {
                        Text(
                            stringResource(R.string.branchy_infer_run_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    s.runs.forEach { run ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = s.runId == run.id, onClick = { vm.selectRun(run) })
                            Text(run.name, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                    if (s.runId != null && s.layout == null) {
                        Text(stringResource(R.string.branchy_infer_run_no_layout), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        BranchyExportSupport.TYPES.forEach { t ->
                            FilterChip(selected = s.type == t, onClick = { vm.setType(t) }, label = { Text(t.uppercase(), maxLines = 1) })
                        }
                    }
                }

                AppSectionCard {
                    AppSectionTitle(title = stringResource(R.string.branchy_infer_helpers_title), supporting = stringResource(R.string.branchy_infer_helpers_hint))
                    if (s.helpers.isEmpty()) {
                        Text(
                            stringResource(R.string.branchy_infer_helpers_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    s.helpers.forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = row.helper.id in s.selected, onCheckedChange = { vm.toggleHelper(row.helper.id) })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(
                                        if (row.online) R.string.branchy_infer_helper_online else R.string.branchy_infer_helper_offline,
                                        row.helper.pushTarget
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                row.rttMs?.let { ms ->
                                    val slow = BranchyInferArgs.isSlowLink(ms)
                                    Text(
                                        stringResource(if (slow) R.string.branchy_infer_rtt_slow else R.string.branchy_infer_rtt, ms.toString()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (slow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                val layout = s.layout
                if (layout != null && s.selected.isNotEmpty()) {
                    AppSectionCard {
                        AppSectionTitle(title = stringResource(R.string.branchy_infer_placement_title), supporting = stringResource(R.string.branchy_infer_placement_hint))
                        val names = s.helpers.associate { it.helper.id to it.name }
                        for (branch in 0 until layout.branches) {
                            val where = s.placement[branch]?.let { names[it] } ?: stringResource(R.string.branchy_infer_this_phone)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.branchy_infer_branch_row, branch, where),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { vm.cycleBranch(branch) }) {
                                    Text(stringResource(R.string.branchy_infer_branch_move), maxLines = 1)
                                }
                            }
                        }
                        OutlinedButton(onClick = vm::autoPlace, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.branchy_infer_auto_place), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        when (BranchyPlacement.validate(layout, s.placement, s.selected.size)) {
                            BranchyPlacement.Problem.NO_REMOTE_BRANCH -> ProblemText(R.string.branchy_infer_problem_no_remote)
                            BranchyPlacement.Problem.NO_LOCAL_BRANCH -> ProblemText(R.string.branchy_infer_problem_no_local)
                            BranchyPlacement.Problem.NO_HELPER, null -> Unit
                        }
                    }
                }
            }

            if (s.steps.isNotEmpty()) {
                val names = s.helpers.associate { it.helper.id to it.name }
                AppSectionCard {
                    AppSectionTitle(title = stringResource(R.string.branchy_infer_progress_title))
                    s.steps.forEach { step -> StepRow(step, s.stepStates[step.key] ?: InferStepState.Pending, names) }
                }
            }

            if (ready) {
                AppSectionCard {
                    AppSectionTitle(title = stringResource(R.string.branchy_infer_live_title))
                    s.live.links.forEach { (target, link) ->
                        Text(
                            stringResource(
                                when (link) {
                                    HelperLink.CONNECTED -> R.string.branchy_infer_link_connected
                                    HelperLink.DEGRADED -> R.string.branchy_infer_link_degraded
                                    HelperLink.RESYNC -> R.string.branchy_infer_link_resync
                                },
                                target
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (link == HelperLink.DEGRADED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        s.live.tokensPerSecond?.let { stringResource(R.string.branchy_infer_tps, "%.1f".format(java.util.Locale.US, it)) }
                            ?: stringResource(R.string.branchy_infer_tps_none),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.branchy_infer_live_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProblemText(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun StepRow(step: InferStep, state: InferStepState, helperNames: Map<String, String>) {
    val helper = step.helperId?.let { helperNames[it] }.orEmpty()
    val label = when {
        step.key == "export_main" -> stringResource(R.string.branchy_infer_step_export_main)
        step.key == "server" -> stringResource(R.string.branchy_infer_step_server)
        step.key.startsWith("export:") -> stringResource(R.string.branchy_infer_step_export_slice, helper)
        else -> stringResource(R.string.branchy_infer_step_push, helper)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        when (state) {
            InferStepState.Pending -> StepNote(R.string.branchy_infer_state_pending)
            is InferStepState.Running -> if (state.total > 0L) {
                LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is InferStepState.Done -> StepNote(if (state.reused) R.string.branchy_infer_state_reused else R.string.branchy_infer_state_done)
            is InferStepState.Failed -> Text(
                stringResource(R.string.branchy_infer_state_failed, state.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun StepNote(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
