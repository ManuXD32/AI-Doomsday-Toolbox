package com.example.llamadroid.ui.training

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyClusterRun
import com.example.llamadroid.branchy.BranchyFormat
import com.example.llamadroid.branchy.BranchyPlan
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle

/** Horizontally scrolling row for chips, so long labels never wrap on small phones. */
@Composable
internal fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun weightModeLabel(mode: String): String = stringResource(
    when (mode) {
        "f32" -> R.string.branchy_ui_wm_f32
        "q4" -> R.string.branchy_ui_wm_q4
        else -> R.string.branchy_ui_wm_q8
    }
)

@Composable
private fun overrideLabel(key: String): String = stringResource(
    when (key) {
        "train.microbatch" -> R.string.branchy_ui_ov_microbatch
        "train.grad_accum" -> R.string.branchy_ui_ov_grad_accum
        "train.recompute" -> R.string.branchy_ui_ov_recompute
        "train.optimizer" -> R.string.branchy_ui_ov_optimizer
        "train.grad_mode" -> R.string.branchy_ui_ov_grad_mode
        "train.lr" -> R.string.branchy_ui_ov_lr
        "train.steps" -> R.string.branchy_ui_ov_steps
        else -> R.string.branchy_ui_ov_checkpoint_every
    }
)

// region Step 2: model

internal fun LazyListScope.branchyModelStepItems(
    state: BranchyWizardState,
    edit: ((BranchyWizardState) -> BranchyWizardState) -> Unit,
    onComputePlan: () -> Unit,
    dataEstimate: DataEstimate = DataEstimate(0.0, true),
    onCustomizeRecipe: () -> Unit = {}
) {
    if (state.recipeId != null) {
        item(key = "model_recipe") { RecipeModelSummary(state, onCustomizeRecipe) }
    } else {
        branchyModelSizeItems(state, edit)
    }
    branchyModelRestItems(state, edit, onComputePlan, dataEstimate)
}

/** Read-only size, context and vocab of the recipe the wizard was prefilled from. */
@Composable
private fun RecipeModelSummary(state: BranchyWizardState, onCustomize: () -> Unit) {
    AppSectionCard {
        AppSectionTitle(
            title = stringResource(R.string.branchy_r6_recipe_model_title),
            supporting = stringResource(
                R.string.branchy_r6_recipe_model_summary,
                BranchyWizardHelpers.formatParams(state.effectiveTarget()),
                state.effectiveContext(),
                BranchyWizardHelpers.formatVocab(state.vocab),
                BranchyWizardHelpers.formatTokens(BranchyWizardHelpers.recipeTokens(state))
            )
        )
        Text(
            text = stringResource(R.string.branchy_r6_recipe_locked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onCustomize, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.branchy_r6_recipe_customize), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun LazyListScope.branchyModelSizeItems(
    state: BranchyWizardState,
    edit: ((BranchyWizardState) -> BranchyWizardState) -> Unit
) {
    item(key = "model_size") {
        AppSectionCard {
            AppSectionTitle(
                title = stringResource(R.string.branchy_ui_size_title),
                supporting = stringResource(R.string.branchy_ui_size_hint)
            )
            ChipRow {
                BranchyWizardHelpers.sizePresets.forEach { p ->
                    FilterChip(
                        selected = state.customTargetMillions == null && state.targetParams == p,
                        onClick = { edit { it.copy(targetParams = p, customTargetMillions = null) } },
                        label = { Text(BranchyWizardHelpers.formatParams(p)) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                FilterChip(
                    selected = state.customTargetMillions != null,
                    onClick = { edit { it.copy(customTargetMillions = it.customTargetMillions ?: "") } },
                    label = { Text(stringResource(R.string.branchy_ui_custom)) }
                )
            }
            state.customTargetMillions?.let { text ->
                CommittedTextField(
                    key = "custom_target",
                    committed = text,
                    onCommit = { v -> edit { it.copy(customTargetMillions = v) } },
                    filter = { v -> v.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text(stringResource(R.string.branchy_ui_size_custom_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
    item(key = "model_context") {
        AppSectionCard {
            AppSectionTitle(
                title = stringResource(R.string.branchy_ui_context_title),
                supporting = stringResource(R.string.branchy_ui_context_hint)
            )
            ChipRow {
                BranchyWizardHelpers.contextPresets.forEach { c ->
                    FilterChip(
                        selected = state.customContext == null && state.context == c,
                        onClick = { edit { it.copy(context = c, customContext = null) } },
                        label = { Text(c.toString()) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                FilterChip(
                    selected = state.customContext != null,
                    onClick = { edit { it.copy(customContext = it.customContext ?: "") } },
                    label = { Text(stringResource(R.string.branchy_ui_custom)) }
                )
            }
            state.customContext?.let { text ->
                CommittedTextField(
                    key = "custom_context",
                    committed = text,
                    onCommit = { v -> edit { it.copy(customContext = v) } },
                    filter = { v -> v.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.branchy_ui_context_custom_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun LazyListScope.branchyModelRestItems(
    state: BranchyWizardState,
    edit: ((BranchyWizardState) -> BranchyWizardState) -> Unit,
    onComputePlan: () -> Unit,
    dataEstimate: DataEstimate
) {
    item(key = "model_weights") {
        AppSectionCard {
            AppSectionTitle(
                title = stringResource(R.string.branchy_ui_wm_title),
                supporting = stringResource(R.string.branchy_ui_wm_hint)
            )
            ChipRow {
                BranchyWizardHelpers.weightModes.forEach { m ->
                    FilterChip(
                        selected = state.weightMode == m,
                        onClick = { edit { it.copy(weightMode = m) } },
                        label = { Text(weightModeLabel(m), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
    }
    item(key = "model_branches") {
        AppSectionCard {
            AppSectionTitle(
                title = stringResource(R.string.branchy_ui_branches_title),
                supporting = stringResource(R.string.branchy_ui_branches_hint)
            )
            ChipRow {
                BranchyWizardHelpers.branchOptions.forEach { b ->
                    FilterChip(
                        selected = state.branches == b,
                        onClick = { edit { it.copy(branches = b) } },
                        label = {
                            Text(
                                if (b == 1) stringResource(R.string.branchy_ui_branches_plain) else b.toString(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            SwitchRow(
                label = stringResource(R.string.branchy_ui_mtp),
                checked = state.mtp,
                onChange = { v -> edit { it.copy(mtp = v) } }
            )
        }
    }
    item(key = "model_offload") {
        AppSectionCard {
            SwitchRow(
                label = stringResource(R.string.branchy_ui_offload),
                checked = state.allowStorageOffload,
                onChange = { v -> edit { it.copy(allowStorageOffload = v) } }
            )
            Text(
                text = stringResource(R.string.branchy_ui_offload_warning),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.allowStorageOffload) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    item(key = "model_epochs") { EpochsCard(state, dataEstimate, edit) }
    item(key = "model_plan") { AutoPlanCard(state, onComputePlan) }
    val plan = (state.plan as? BranchyPlanUi.Ready)?.plan
    if (plan != null && plan.fits) {
        item(key = "model_overrides") {
            AppAdvancedSection(title = stringResource(R.string.branchy_ui_edit_settings)) {
                Text(
                    text = stringResource(R.string.branchy_ui_edit_settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BranchyWizardHelpers.overrideFields.forEach { field ->
                    OverrideEditor(field, plan, state.overrides[field.key], edit)
                }
            }
        }
    }
}

@Composable
private fun OverrideEditor(
    field: OverrideField,
    plan: BranchyPlan,
    current: String?,
    edit: ((BranchyWizardState) -> BranchyWizardState) -> Unit
) {
    val planValue = BranchyWizardHelpers.planValue(plan, field.key)
    val label = overrideLabel(field.key)
    if (field.kind == OverrideKind.CHOICE) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        ChipRow {
            field.choices.forEach { choice ->
                FilterChip(
                    selected = (current ?: planValue) == choice,
                    onClick = { edit { it.copy(overrides = it.overrides + (field.key to choice)) } },
                    label = { Text(choice) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    } else {
        OutlinedTextField(
            value = current ?: "",
            onValueChange = { v -> edit { it.copy(overrides = it.overrides + (field.key to v)) } },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            placeholder = { Text(planValue) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (field.kind == OverrideKind.INT) KeyboardType.Number else KeyboardType.Decimal
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Run length in epochs of the selected data, with the resulting steps/ETA and a warning for runs over 24 h. */
@Composable
private fun EpochsCard(
    state: BranchyWizardState,
    estimate: DataEstimate,
    edit: ((BranchyWizardState) -> BranchyWizardState) -> Unit
) {
    AppSectionCard {
        AppSectionTitle(
            title = stringResource(R.string.branchy_r5_epochs_title),
            supporting = stringResource(R.string.branchy_r5_epochs_hint)
        )
        CommittedTextField(
            key = "epochs",
            committed = state.epochs,
            onCommit = { v -> edit { it.copy(epochs = v) } },
            filter = { v -> v.filter { c -> c.isDigit() || c == '.' }.take(6) },
            label = { Text(stringResource(R.string.branchy_r5_epochs_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        if (estimate.tokens > 0.0) {
            val target = BranchyWizardHelpers.tokensTarget(state.effectiveTarget(), state.effectiveEpochs(), estimate.tokens)
            val prefix = if (estimate.approximate) "≈" else ""
            Text(
                text = stringResource(
                    R.string.branchy_r5_epochs_tokens,
                    prefix + BranchyWizardHelpers.formatTokens(estimate.tokens),
                    BranchyWizardHelpers.formatTokens(target)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val plan = (state.plan as? BranchyPlanUi.Ready)?.plan
        if (plan != null && plan.fits && !state.planStale) {
            Text(
                text = stringResource(
                    R.string.branchy_r5_epochs_result,
                    String.format(java.util.Locale.US, "%,d", plan.train.steps),
                    BranchyFormat.duration(plan.etaS)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            if (plan.etaS > BranchyWizardHelpers.LONG_RUN_S) {
                Text(
                    text = stringResource(R.string.branchy_r5_eta_warning, BranchyFormat.duration(plan.etaS)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.branchy_r5_eta_suggestions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
internal fun AutoPlanCard(
    state: BranchyWizardState,
    onComputePlan: () -> Unit,
    deviceNames: Map<String, String> = emptyMap()
) {
    AppSectionCard {
        AppSectionTitle(
            title = stringResource(R.string.branchy_ui_plan_title),
            supporting = stringResource(R.string.branchy_ui_plan_hint)
        )
        val running = state.plan is BranchyPlanUi.Running
        Button(onClick = onComputePlan, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (state.plan is BranchyPlanUi.Ready) {
                        if (state.planStale) R.string.branchy_r5_plan_recalculate else R.string.branchy_ui_plan_recompute
                    } else R.string.branchy_ui_plan_compute
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (state.planStale && state.plan is BranchyPlanUi.Ready) {
            Text(
                text = stringResource(R.string.branchy_r5_plan_stale_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        when (val p = state.plan) {
            BranchyPlanUi.Idle -> Unit
            BranchyPlanUi.Running -> {
                Text(
                    text = stringResource(R.string.branchy_ui_plan_running),
                    style = MaterialTheme.typography.bodySmall
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is BranchyPlanUi.Failed -> Text(
                text = stringResource(R.string.branchy_ui_plan_failed, p.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            is BranchyPlanUi.Ready -> PlanResult(p.plan, deviceNames)
        }
    }
}

@Composable
private fun PlanResult(plan: BranchyPlan, deviceNames: Map<String, String>) {
    if (!plan.fits) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.branchy_ui_plan_no_fit),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                plan.needsMoreRamMb?.let { mb ->
                    Text(
                        text = stringResource(R.string.branchy_ui_plan_needs_ram, BranchyWizardHelpers.formatMb(mb)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Text(
                    text = stringResource(R.string.branchy_ui_plan_no_fit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                plan.reason?.takeIf { it.isNotBlank() }?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    } else {
        val l = plan.layout
        val t = plan.train
        PlanLine(stringResource(R.string.branchy_ui_plan_params, BranchyWizardHelpers.formatParams(plan.params)))
        PlanLine(
            stringResource(
                R.string.branchy_ui_plan_layout, l.trunkBlocks, l.branches, l.branchBlocks, l.branchWidth,
                l.fusionBlocks, l.dModel
            )
        )
        PlanLine(
            stringResource(
                R.string.branchy_ui_plan_settings, t.microbatch, t.gradAccum, t.recompute, t.optimizer, t.gradMode
            )
        )
        PlanLine(stringResource(R.string.branchy_ui_plan_storage, t.weightStorage, t.masterFp32.toString()))
        if (plan.isCluster) ClusterPlanLines(plan)
        plan.perDevice.forEach { d ->
            val peak = BranchyWizardHelpers.formatMb(d.peakMb)
            val budget = BranchyWizardHelpers.formatMb(d.budgetMb)
            PlanLine(
                if (plan.isCluster) {
                    val name = deviceNames[d.id] ?: if (d.id == BranchyClusterRun.LOCAL_ID) {
                        stringResource(R.string.branchy_main_plan_this_phone)
                    } else d.id
                    stringResource(R.string.branchy_main_plan_device_ram, name, peak, budget)
                } else stringResource(R.string.branchy_ui_plan_ram, peak, budget)
            )
        }
        PlanLine(
            stringResource(
                R.string.branchy_ui_plan_speed, "%.1f".format(java.util.Locale.US, plan.tokS),
                BranchyFormat.duration(plan.etaS)
            )
        )
    }
    if (plan.warnings.isNotEmpty()) {
        Text(
            text = stringResource(R.string.branchy_ui_plan_notes),
            style = MaterialTheme.typography.titleSmall
        )
        // Planner warnings are native English text, shown as-is; slow-link warnings have a localized line above.
        plan.warnings.filterNot { plan.isCluster && it.startsWith("Link to ") }.forEach { w ->
            Text(
                text = "• $w",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Cluster plan summary: mode (memory or speed), speed-up, bottleneck and slow links. */
@Composable
private fun ClusterPlanLines(plan: BranchyPlan) {
    PlanLine(
        stringResource(
            if (plan.mode == "data_parallel") R.string.branchy_main_plan_mode_data else R.string.branchy_main_plan_mode_branch
        )
    )
    PlanLine(stringResource(R.string.branchy_main_plan_speedup, "%.2f".format(java.util.Locale.US, plan.speedup)))
    plan.limit?.let { limit ->
        PlanLine(
            stringResource(
                R.string.branchy_main_plan_limit,
                stringResource(
                    when (limit) {
                        "link" -> R.string.branchy_main_limit_link
                        "worker" -> R.string.branchy_main_limit_worker
                        else -> R.string.branchy_main_limit_coordinator
                    }
                )
            )
        )
    }
    plan.links.filter { it.slow }.forEach { l ->
        Text(
            text = stringResource(
                R.string.branchy_main_plan_link_slow, l.id, "%.1f".format(java.util.Locale.US, l.needMbps),
                "%.1f".format(java.util.Locale.US, l.linkMbps)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun PlanLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

// endregion
