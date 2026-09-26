package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard

@Composable
internal fun HarnessGoalPanel(
    goal: HarnessGoalUi?,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    var objective by rememberSaveable(goal?.id, goal?.revision) {
        mutableStateOf(goal?.objective.orEmpty())
    }
    var maxRounds by rememberSaveable(goal?.id, goal?.revision) {
        mutableStateOf(goal?.maxGoalRounds?.toString() ?: "256")
    }
    val phaseLabel = when (goal?.phase) {
        "active" -> stringResource(R.string.harness_goal_phase_active)
        "paused" -> stringResource(R.string.harness_goal_phase_paused)
        "blocked" -> stringResource(R.string.harness_goal_phase_blocked)
        "complete" -> stringResource(R.string.harness_goal_phase_complete)
        else -> stringResource(R.string.harness_goal_phase_unknown)
    }
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        HarnessSectionHeading(title = stringResource(R.string.harness_goal_title), icon = Icons.Default.Tune)
        Text(stringResource(R.string.harness_goal_description), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        if (goal != null) {
            Text(
                stringResource(R.string.harness_goal_status, phaseLabel.orEmpty(), goal.roundsStarted, goal.maxGoalRounds),
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium
            )
            Text(
                stringResource(
                    if (goal.activation == "armed") {
                        R.string.harness_goal_activation_armed
                    } else {
                        R.string.harness_goal_activation_disarmed
                    }
                ),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
            goal.blockedReason?.let { reason ->
                Text(reason, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedTextField(
            value = objective,
            onValueChange = { objective = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.harness_goal_objective)) },
            singleLine = false,
            minLines = 2
        )
        OutlinedTextField(
            value = maxRounds,
            onValueChange = { maxRounds = it.filter(Char::isDigit).take(6) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.harness_goal_max_rounds)) },
            singleLine = true
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    onAction(
                        if (goal == null || goal.phase == "complete") {
                            NativeHarnessUiAction.CreateGoal(objective, maxRounds.toIntOrNull())
                        } else {
                            NativeHarnessUiAction.EditGoal(objective, maxRounds.toIntOrNull())
                        }
                    )
                },
                enabled = objective.isNotBlank()
            ) {
                Text(
                    stringResource(
                        if (goal == null || goal.phase == "complete") {
                            R.string.harness_goal_create
                        } else {
                            R.string.harness_goal_save
                        }
                    )
                )
            }
            if (goal != null && goal.phase == "active") {
                OutlinedButton(onClick = { onAction(NativeHarnessUiAction.PauseGoal) }) {
                    Text(stringResource(R.string.harness_goal_pause))
                }
            }
            if (goal != null && (goal.phase == "paused" || goal.phase == "blocked")) {
                OutlinedButton(onClick = { onAction(NativeHarnessUiAction.ResumeGoal) }) {
                    Text(stringResource(R.string.harness_goal_resume))
                }
            }
            if (goal != null && goal.phase != "complete") {
                OutlinedButton(onClick = { onAction(NativeHarnessUiAction.CompleteGoal) }) {
                    Text(stringResource(R.string.harness_goal_complete))
                }
            }
            if (goal != null) {
                OutlinedButton(onClick = { onAction(NativeHarnessUiAction.ClearGoal) }) {
                    Text(stringResource(R.string.harness_goal_clear))
                }
            }
        }
    }
}
