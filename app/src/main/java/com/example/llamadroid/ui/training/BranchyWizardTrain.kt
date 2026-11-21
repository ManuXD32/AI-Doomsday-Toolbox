package com.example.llamadroid.ui.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyFormat
import com.example.llamadroid.branchy.BranchyRunEdit
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import kotlin.math.roundToInt

private fun BranchyWizardStep.labelRes(): Int = when (this) {
    BranchyWizardStep.DATA -> R.string.branchy_ui_step_data
    BranchyWizardStep.MODEL -> R.string.branchy_ui_step_model
    BranchyWizardStep.DEVICES -> R.string.branchy_main_step_devices
    BranchyWizardStep.TRAIN -> R.string.branchy_ui_step_train
}

/** Tappable Data . Model . Devices . Train header: the current step is highlighted, earlier ones are ticked. */
@Composable
internal fun BranchyWizardStepHeader(current: BranchyWizardStep, onGoTo: (BranchyWizardStep) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppChromeDefaults.ScreenPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BranchyWizardStep.entries.forEach { step ->
            val isCurrent = step == current
            val done = step.ordinal < current.ordinal
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Tab) { onGoTo(step) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isCurrent || done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(26.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (done) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Text(
                                text = (step.ordinal + 1).toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(step.labelRes()),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// region Step 3: train

internal fun LazyListScope.branchyTrainStepItems(
    state: BranchyWizardState,
    edit: ((BranchyWizardState) -> BranchyWizardState) -> Unit
) {
    item(key = "train_name") {
        AppSectionCard {
            AppSectionTitle(title = stringResource(R.string.branchy_ui_name_title))
            OutlinedTextField(
                value = state.runName,
                onValueChange = { v -> edit { it.copy(runName = v.take(60)) } },
                label = { Text(stringResource(R.string.branchy_ui_name_label)) },
                placeholder = { Text(BranchyWizardHelpers.defaultRunName(state.effectiveTarget())) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    item(key = "train_guards") {
        AppSectionCard {
            AppSectionTitle(
                title = stringResource(R.string.branchy_ui_guards_title),
                supporting = stringResource(R.string.branchy_ui_guards_hint)
            )
            val battery = state.guards.pauseBelowBatteryPct
            Text(
                text = if (battery > 0) stringResource(R.string.branchy_ui_guard_battery, battery)
                else stringResource(R.string.branchy_ui_guard_battery_off),
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = battery.toFloat(),
                onValueChange = { v -> edit { it.copy(guards = it.guards.copy(pauseBelowBatteryPct = v.roundToInt())) } },
                valueRange = 0f..80f
            )
            val temp = state.guards.maxBatteryTempC
            Text(
                text = if (temp > 0) stringResource(R.string.branchy_ui_guard_temp, temp)
                else stringResource(R.string.branchy_ui_guard_temp_off),
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = temp.toFloat(),
                onValueChange = { v -> edit { it.copy(guards = it.guards.copy(maxBatteryTempC = v.roundToInt())) } },
                valueRange = 0f..55f
            )
            SwitchRow(
                label = stringResource(R.string.branchy_ui_guard_unplugged),
                checked = state.guards.pauseWhenUnplugged,
                onChange = { v -> edit { it.copy(guards = it.guards.copy(pauseWhenUnplugged = v)) } }
            )
            SwitchRow(
                label = stringResource(R.string.branchy_ui_guard_auto_resume),
                checked = state.autoResume,
                onChange = { v -> edit { it.copy(autoResume = v) } }
            )
        }
    }
    item(key = "train_checkpoint") {
        val plan = (state.plan as? BranchyPlanUi.Ready)?.plan
        BranchyCheckpointEveryCard(
            committed = state.overrides[BranchyRunEdit.CHECKPOINT_KEY].orEmpty(),
            planDefault = plan?.train?.checkpointEvery,
            onChange = { v -> edit { it.copy(overrides = BranchyWizardHelpers.withCheckpointEvery(it.overrides, v)) } }
        )
    }
    item(key = "train_summary") { SummaryCard(state) }
}

/** "Save a checkpoint every N steps"; empty = the planner's default, shown as the placeholder. Shared with the run editor. */
@Composable
internal fun BranchyCheckpointEveryCard(committed: String, planDefault: Int?, onChange: (String) -> Unit) {
    AppSectionCard {
        AppSectionTitle(
            title = stringResource(R.string.branchy_r6_checkpoint_title),
            supporting = stringResource(R.string.branchy_r6_checkpoint_hint)
        )
        CommittedTextField(
            key = "checkpoint_every",
            committed = committed,
            onCommit = onChange,
            filter = { v -> v.filter { c -> c.isDigit() }.take(7) },
            label = { Text(stringResource(R.string.branchy_r6_checkpoint_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    if (planDefault != null) stringResource(R.string.branchy_r6_checkpoint_default, planDefault)
                    else stringResource(R.string.branchy_r6_checkpoint_default_unknown),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        )
    }
}

@Composable
private fun SummaryCard(state: BranchyWizardState) {
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.branchy_ui_summary_title))
        Text(
            text = stringResource(
                R.string.branchy_ui_summary_data, state.weights.size, BranchyWizardHelpers.formatVocab(state.vocab)
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(
                R.string.branchy_ui_summary_model, BranchyWizardHelpers.formatParams(state.effectiveTarget()),
                state.effectiveContext(), state.weightMode
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        val plan = (state.plan as? BranchyPlanUi.Ready)?.plan
        if (plan != null && plan.fits) {
            Text(
                text = stringResource(
                    R.string.branchy_ui_summary_plan, "%.1f".format(java.util.Locale.US, plan.tokS),
                    BranchyFormat.duration(plan.etaS)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                text = stringResource(R.string.branchy_ui_summary_no_plan),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// endregion
