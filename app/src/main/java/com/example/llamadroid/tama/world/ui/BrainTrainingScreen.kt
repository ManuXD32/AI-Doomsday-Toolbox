package com.example.llamadroid.tama.world.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val BrainTileUnknown = Color(0xFF343A44)
private val BrainTileFloor = Color(0xFF6B9D62)
private val BrainTileWater = Color(0xFF5A93B8)
private val BrainTileFood = Color(0xFFE4B65F)
private val BrainTileResource = Color(0xFFBD83AF)
private val BrainTileHazard = Color(0xFFB96A5C)
private val BrainChartTraining = Color(0xFF6D5AA8)
private val BrainChartEvaluation = Color(0xFFD87568)

/** Actual policy/trainer snapshot surface. All controls dispatch callbacks. */
@Composable
fun BrainTrainingScreen(
    state: BrainTrainingUiState,
    callbacks: BrainTrainingCallbacks,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") { BrainHeader(callbacks) }
        if (state.error != null) {
            item(key = "error") { BrainErrorCard(state.error) }
        }
        item(key = "current") { CurrentBrainCard(state) }
        item(key = "controls") { TrainingControls(state, callbacks) }
        item(key = "live") {
            state.liveEnvironment?.let { environment ->
                LiveTrainingCard(environment)
            } ?: EmptyLiveTrainingCard()
        }
        item(key = "metrics") { MetricsCard(state.metrics) }
        item(key = "charts_header") { SectionTitle(stringResource(R.string.tama_world_brain_charts_title)) }
        if (state.charts.isEmpty()) {
            item(key = "charts_empty") { EmptyDataCard(stringResource(R.string.tama_world_brain_charts_empty)) }
        } else {
            items(state.charts, key = { "chart:${it.id}" }) { series ->
                BrainMetricChart(series)
            }
        }
        item(key = "checkpoint_header") {
            SectionTitle(stringResource(R.string.tama_world_brain_checkpoints_title))
        }
        if (state.checkpoints.isEmpty()) {
            item(key = "checkpoints_empty") { EmptyDataCard(stringResource(R.string.tama_world_brain_checkpoints_empty)) }
        } else {
            items(state.checkpoints, key = { "checkpoint:${it.id}" }) { checkpoint ->
                CheckpointCard(checkpoint, state.evaluation, callbacks)
            }
        }
        item(key = "adopted_policy_header") {
            SectionTitle(stringResource(R.string.tama_world_brain_policy_history_title))
        }
        item(key = "adopted_policy_history") {
            AdoptedPolicyHistoryCard(state.adoptedPolicies, callbacks.onRestoreAdoptedPolicy)
        }
        state.evaluation?.let { evaluation ->
            item(key = "evaluation") { EvaluationCard(evaluation, callbacks) }
        }
        item(key = "profiles") { TrainingProfilesCard(state.profiles, callbacks) }
        item(key = "resources") { BrainResourceSettingsCard(state.resourceSettings, callbacks.onResourcesChanged) }
        item(key = "autonomy") { SafeAutonomyCard(state.safeAutonomy, callbacks) }
    }
}

@Composable
private fun BrainHeader(callbacks: BrainTrainingCallbacks) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = callbacks.onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.tama_world_brain_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.tama_world_brain_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CurrentBrainCard(state: BrainTrainingUiState) {
    BrainCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = state.currentBrainName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.tama_world_brain_version, state.currentBrainVersion),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.tama_world_brain_training_age, state.trainingAgeEpisodes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = if (state.adopted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = stringResource(
                        when {
                            state.adopted -> R.string.tama_world_brain_adopted
                            state.activePolicyIsBaseline -> R.string.tama_world_brain_baseline
                            else -> R.string.tama_world_brain_untrained_model
                        }
                    ),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
        Text(
            text = stringResource(R.string.tama_world_brain_curriculum, state.currentCurriculum),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TrainingControls(
    state: BrainTrainingUiState,
    callbacks: BrainTrainingCallbacks
) {
    BrainCard {
        Text(
            text = stringResource(R.string.tama_world_brain_run_controls),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            text = stringResource(R.string.tama_world_brain_curriculum_selector),
            style = MaterialTheme.typography.labelLarge
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            (0..10).forEach { curriculumId ->
                FilterChip(
                    selected = state.currentCurriculumId == curriculumId,
                    onClick = { callbacks.onCurriculumSelected(curriculumId) },
                    label = {
                        Text(
                            text = stringResource(R.string.tama_world_brain_curriculum_level, curriculumId),
                            maxLines = 1
                        )
                    }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (state.runState) {
                BrainRunState.RUNNING -> Button(onClick = callbacks.onPause, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Pause, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tama_world_brain_pause))
                }
                BrainRunState.PAUSED -> Button(onClick = callbacks.onResume, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tama_world_brain_resume))
                }
                BrainRunState.IDLE, BrainRunState.FAILED -> Button(onClick = callbacks.onStart, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tama_world_brain_start))
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = stringResource(state.runState.labelRes),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(20.dp))
            listOf(1, 2, 4, 8, 16, 32).forEach { speed ->
                FilterChip(
                    selected = state.speedMultiplier == speed,
                    onClick = { callbacks.onSpeedChanged(speed) },
                    label = {
                        Text(
                            text = if (speed == 32) stringResource(R.string.tama_world_brain_speed_max)
                            else stringResource(R.string.tama_world_brain_speed, speed),
                            maxLines = 1
                        )
                    }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = callbacks.onCheckpointSaved,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.tama_world_brain_save_checkpoint), maxLines = 2)
            }
            OutlinedButton(
                onClick = callbacks.onOpenJournal,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.tama_world_brain_open_journal), maxLines = 2)
            }
        }
    }
}

@Composable
private fun LiveTrainingCard(environment: BrainLiveEnvironmentUi) {
    BrainCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tama_world_brain_live_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = stringResource(R.string.tama_world_brain_episode, environment.episodeNumber),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.tama_world_brain_seed, environment.seed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = environment.goal,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        BrainMiniWorld(environment)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.tama_world_brain_action, environment.currentAction), maxLines = 1)
            environment.hunger?.let { Text(stringResource(R.string.tama_world_brain_hunger, it), maxLines = 1) }
            environment.hydration?.let { Text(stringResource(R.string.tama_world_brain_hydration, it), maxLines = 1) }
            environment.energy?.let { Text(stringResource(R.string.tama_world_brain_energy, it), maxLines = 1) }
            environment.reward?.let { Text(stringResource(R.string.tama_world_brain_reward, it), maxLines = 1) }
        }
    }
}

@Composable
private fun EmptyLiveTrainingCard() {
    EmptyDataCard(stringResource(R.string.tama_world_brain_live_empty))
}

@Composable
private fun BrainMiniWorld(environment: BrainLiveEnvironmentUi) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp, max = 300.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D333A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            if (environment.widthTiles <= 0 || environment.heightTiles <= 0) return@Canvas
            val cellWidth = size.width / environment.widthTiles.toFloat()
            val cellHeight = size.height / environment.heightTiles.toFloat()
            environment.tiles.forEach { tile ->
                drawRect(
                    color = brainTileColor(tile),
                    topLeft = Offset(tile.x * cellWidth, tile.y * cellHeight),
                    size = Size(cellWidth + 0.5f, cellHeight + 0.5f)
                )
            }
            if (environment.goalX != null && environment.goalY != null) {
                drawCircle(
                    color = if (environment.success) Color(0xFF8BD17C) else Color(0xFFFF8C69),
                    radius = max(3f, min(cellWidth, cellHeight) * 0.35f),
                    center = Offset(
                        (environment.goalX + 0.5f) * cellWidth,
                        (environment.goalY + 0.5f) * cellHeight
                    ),
                    style = Stroke(width = 2f)
                )
            }
            environment.actors.forEach { actor ->
                drawCircle(
                    color = if (actor.isPet) Color(0xFFFFD166) else Color(0xFFE7EDF1),
                    radius = max(2.5f, min(cellWidth, cellHeight) * 0.42f),
                    center = Offset((actor.x + 0.5f) * cellWidth, (actor.y + 0.5f) * cellHeight)
                )
            }
        }
    }
}

private fun brainTileColor(tile: BrainMiniTileUi): Color = when {
    !tile.known -> BrainTileUnknown
    tile.hazard -> BrainTileHazard
    tile.food -> BrainTileFood
    tile.resource -> BrainTileResource
    tile.terrainId.contains("water", ignoreCase = true) -> BrainTileWater
    else -> BrainTileFloor
}

@Composable
private fun MetricsCard(metrics: BrainTrainingMetricsUi) {
    BrainCard {
        SectionTitle(stringResource(R.string.tama_world_brain_metrics_title))
        MetricGrid(
            listOf(
                stringResource(R.string.tama_world_brain_metric_episodes) to metrics.episodes.toString(),
                stringResource(R.string.tama_world_brain_metric_success) to percent(metrics.successRatePercent),
                stringResource(R.string.tama_world_brain_metric_reward) to formatDecimal(metrics.meanReward),
                stringResource(R.string.tama_world_brain_metric_steps) to formatDecimal(metrics.objectiveSteps),
                stringResource(R.string.tama_world_brain_metric_efficiency) to percent(metrics.pathEfficiencyPercent),
                stringResource(R.string.tama_world_brain_metric_stuck) to percent(metrics.stuckRatePercent),
                stringResource(R.string.tama_world_brain_metric_critical) to percent(metrics.criticalNeedsRatePercent),
                stringResource(R.string.tama_world_brain_metric_exploration) to percent(metrics.explorationScorePercent)
            )
        )
    }
}

@Composable
private fun MetricGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { (label, value) ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(9.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
                    ) {
                        Column(modifier = Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                        }
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BrainMetricChart(series: BrainMetricSeriesUi) {
    BrainCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = series.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(series.unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                val allPoints = series.points + series.evaluationPoints
                if (allPoints.isEmpty()) return@Canvas
                val minX = allPoints.minOf { it.step }.toFloat()
                val maxX = allPoints.maxOf { it.step }.toFloat().coerceAtLeast(minX + 1f)
                val minY = allPoints.minOf { it.value }
                val maxY = allPoints.maxOf { it.value }.coerceAtLeast(minY + 1f)
                drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, size.height), Offset(size.width, size.height), 1f)
                drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, 0f), Offset(0f, size.height), 1f)
                drawMetricSeries(series.points, minX, maxX, minY, maxY, BrainChartTraining)
                drawMetricSeries(series.evaluationPoints, minX, maxX, minY, maxY, BrainChartEvaluation)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChartLegend(BrainChartTraining, stringResource(R.string.tama_world_brain_chart_training))
            if (series.evaluationPoints.isNotEmpty()) {
                ChartLegend(BrainChartEvaluation, stringResource(R.string.tama_world_brain_chart_evaluation))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMetricSeries(
    points: List<BrainMetricPointUi>,
    minX: Float,
    maxX: Float,
    minY: Float,
    maxY: Float,
    color: Color
) {
    if (points.isEmpty()) return
    val path = Path()
    points.forEachIndexed { index, point ->
        val x = ((point.step - minX) / (maxX - minX)).coerceIn(0f, 1f) * size.width
        val y = size.height - ((point.value - minY) / (maxY - minY)).coerceIn(0f, 1f) * size.height
        // A newly completed evaluation is already meaningful before a second sample exists.
        drawCircle(color, radius = 3f, center = Offset(x, y))
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = color, style = Stroke(width = 3f, cap = StrokeCap.Round))
}

@Composable
private fun ChartLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(Modifier.size(10.dp), shape = RoundedCornerShape(3.dp), color = color) {}
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CheckpointCard(
    checkpoint: BrainCheckpointUi,
    evaluation: BrainEvaluationUi?,
    callbacks: BrainTrainingCallbacks
) {
    BrainCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(checkpoint.displayName, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(checkpoint.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = stringResource(R.string.tama_world_brain_checkpoint_episodes, checkpoint.trainingEpisodes),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (checkpoint.isCurrent) {
                Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.tama_world_brain_current_checkpoint), tint = MaterialTheme.colorScheme.secondary)
            }
        }
        CheckpointField(stringResource(R.string.tama_world_brain_checkpoint_hash), checkpoint.modelHash)
        CheckpointField(stringResource(R.string.tama_world_brain_checkpoint_curriculum), checkpoint.curriculum)
        CheckpointField(stringResource(R.string.tama_world_brain_checkpoint_reward), checkpoint.rewardConfiguration)
        CheckpointField(stringResource(R.string.tama_world_brain_checkpoint_metrics), checkpoint.metricSummary)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (checkpoint.isCandidate) {
                OutlinedButton(onClick = { callbacks.onEvaluate(checkpoint.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Science, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tama_world_brain_evaluate), maxLines = 2)
                }
                if (evaluation?.candidateCheckpointId == checkpoint.id && evaluation.complete && evaluation.candidateWins) {
                    Button(onClick = { callbacks.onAdoptCandidate(checkpoint.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.tama_world_brain_adopt), maxLines = 2)
                    }
                }
            }
            if (checkpoint.restorable && !checkpoint.isCurrent) {
                OutlinedButton(onClick = { callbacks.onRestoreCheckpoint(checkpoint.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Restore, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tama_world_brain_restore), maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun CheckpointField(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(value, modifier = Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AdoptedPolicyHistoryCard(
    policies: List<BrainAdoptedPolicyUi>,
    onRestore: (String) -> Unit
) {
    val context = LocalContext.current
    BrainCard {
        Text(
            stringResource(R.string.tama_world_brain_policy_history_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (policies.isEmpty()) {
            Text(stringResource(R.string.tama_world_brain_policy_history_empty), style = MaterialTheme.typography.bodySmall)
        } else {
            policies.take(MAX_POLICY_HISTORY_ROWS).forEach { policy ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (policy.baseline) stringResource(R.string.tama_world_brain_policy_baseline)
                            else stringResource(R.string.tama_world_brain_policy_version, policy.version),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(
                                R.string.tama_world_brain_policy_hash,
                                policy.modelHash?.take(POLICY_HASH_PREVIEW_LENGTH)
                                    ?: stringResource(R.string.tama_world_brain_policy_no_hash)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!policy.baseline) {
                            Text(
                                text = stringResource(
                                    R.string.tama_world_brain_policy_created,
                                    formatBrainTimestamp(context, policy.createdAt)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (policy.active) {
                        Text(
                            stringResource(R.string.tama_world_brain_policy_active),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    } else {
                        OutlinedButton(onClick = { onRestore(policy.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Default.Restore, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.tama_world_brain_policy_restore), maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_POLICY_HISTORY_ROWS = 50
private const val POLICY_HASH_PREVIEW_LENGTH = 12

@Composable
private fun EvaluationCard(evaluation: BrainEvaluationUi, callbacks: BrainTrainingCallbacks) {
    BrainCard {
        Text(stringResource(R.string.tama_world_brain_evaluation_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        Text(
            text = stringResource(R.string.tama_world_brain_evaluation_seeds, evaluation.unseenSeedCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(
                    if (evaluation.currentPolicyIsAdopted) R.string.tama_world_brain_adopted
                    else R.string.tama_world_brain_untrained_model
                ),
                modifier = Modifier.weight(0.55f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.tama_world_brain_candidate),
                modifier = Modifier.weight(0.55f),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        EvaluationRow(stringResource(R.string.tama_world_brain_metric_success), percent(evaluation.currentSuccessPercent), percent(evaluation.candidateSuccessPercent))
        EvaluationRow(stringResource(R.string.tama_world_brain_metric_steps), formatDecimal(evaluation.currentObjectiveSteps), formatDecimal(evaluation.candidateObjectiveSteps))
        EvaluationRow(stringResource(R.string.tama_world_brain_metric_stuck), percent(evaluation.currentStuckPercent), percent(evaluation.candidateStuckPercent))
        EvaluationRow(stringResource(R.string.tama_world_brain_metric_need_failure), percent(evaluation.currentNeedFailurePercent), percent(evaluation.candidateNeedFailurePercent))
        EvaluationRow(stringResource(R.string.tama_world_brain_metric_efficiency), percent(evaluation.currentPathEfficiencyPercent), percent(evaluation.candidatePathEfficiencyPercent))
        EvaluationRow(stringResource(R.string.tama_world_brain_metric_exploration), percent(evaluation.currentExplorationPercent), percent(evaluation.candidateExplorationPercent))
        EvaluationRow(stringResource(R.string.tama_world_brain_metric_invalid), percent(evaluation.currentInvalidActionPercent), percent(evaluation.candidateInvalidActionPercent))
        if (!evaluation.complete) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Text(
                text = stringResource(
                    if (evaluation.candidateWins) R.string.tama_world_brain_candidate_wins
                    else R.string.tama_world_brain_candidate_keep_current
                ),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (evaluation.candidateWins) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (evaluation.candidateWins) {
                    Button(onClick = { callbacks.onAdoptCandidate(evaluation.candidateCheckpointId) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.tama_world_brain_adopt), maxLines = 2)
                    }
                }
                OutlinedButton(onClick = callbacks.onKeepCurrent, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.tama_world_brain_keep_current), maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun EvaluationRow(label: String, current: String, candidate: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(current, modifier = Modifier.weight(0.55f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text(candidate, modifier = Modifier.weight(0.55f), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

@Composable
private fun TrainingProfilesCard(
    profiles: List<BrainTrainingProfileUi>,
    callbacks: BrainTrainingCallbacks
) {
    BrainCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            SectionTitle(stringResource(R.string.tama_world_brain_profiles_title))
        }
        if (profiles.isEmpty()) {
            Text(stringResource(R.string.tama_world_brain_profiles_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            profiles.forEach { profile ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { callbacks.onProfileSelected(profile.id) },
                    shape = RoundedCornerShape(11.dp),
                    color = if (profile.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(profile.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (profile.selected) Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Text(profile.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = stringResource(R.string.tama_world_brain_profile_details, profile.parallelEnvironments, profile.cpuThreads),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SafeAutonomyCard(
    settings: SafeAutonomyUi,
    callbacks: BrainTrainingCallbacks
) {
    BrainCard {
        Text(stringResource(R.string.tama_world_brain_safe_autonomy_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        Text(stringResource(R.string.tama_world_brain_safe_autonomy_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.tama_world_brain_autonomy_level), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WorldAutonomyLevel.entries.forEach { level ->
                FilterChip(
                    selected = settings.level == level,
                    onClick = {
                        callbacks.onSafeAutonomyChanged(
                            settings.copy(level = level)
                        )
                    },
                    label = { Text(stringResource(level.labelRes), maxLines = 1) }
                )
            }
        }
        Text(stringResource(R.string.tama_world_brain_autonomy_permissions), style = MaterialTheme.typography.labelLarge)
        SafeSwitchRow(stringResource(R.string.tama_world_brain_allow_purchases), settings.allowPurchases) {
            callbacks.onSafeAutonomyChanged(settings.copy(allowPurchases = it))
        }
        Text(
            text = stringResource(
                R.string.tama_world_brain_purchase_budget,
                settings.maximumAutonomousPurchase
            ),
            style = MaterialTheme.typography.labelMedium
        )
        Slider(
            value = settings.maximumAutonomousPurchase.toFloat(),
            onValueChange = {
                callbacks.onSafeAutonomyChanged(settings.copy(maximumAutonomousPurchase = it.roundToInt()))
            },
            valueRange = 0f..500f,
            steps = 49
        )
        SafeSwitchRow(stringResource(R.string.tama_world_brain_allow_sales), settings.allowSellingItems) {
            callbacks.onSafeAutonomyChanged(settings.copy(allowSellingItems = it))
        }
        SafeSwitchRow(stringResource(R.string.tama_world_brain_allow_rare_items), settings.allowUsingRareItems) {
            callbacks.onSafeAutonomyChanged(settings.copy(allowUsingRareItems = it))
        }
        SafeSwitchRow(stringResource(R.string.tama_world_brain_allow_dungeon), settings.allowDungeonEntry) {
            callbacks.onSafeAutonomyChanged(settings.copy(allowDungeonEntry = it))
        }
        SafeSwitchRow(stringResource(R.string.tama_world_brain_allow_adventure_gate), settings.allowAdventureGate) {
            callbacks.onSafeAutonomyChanged(settings.copy(allowAdventureGate = it))
        }
        SafeSwitchRow(stringResource(R.string.tama_world_brain_allow_overnight), settings.allowOvernightExploration) {
            callbacks.onSafeAutonomyChanged(settings.copy(allowOvernightExploration = it))
        }
        SafeSwitchRow(stringResource(R.string.tama_world_brain_allow_work), settings.allowWork) {
            callbacks.onSafeAutonomyChanged(settings.copy(allowWork = it))
        }
        SafeSwitchRow(stringResource(R.string.tama_world_brain_allow_study), settings.allowStudy) {
            callbacks.onSafeAutonomyChanged(settings.copy(allowStudy = it))
        }
        SafeSwitchRow(stringResource(R.string.tama_world_brain_allow_farming), settings.allowFarming) {
            callbacks.onSafeAutonomyChanged(settings.copy(allowFarming = it))
        }
        SafeSwitchRow(stringResource(R.string.tama_world_brain_allow_user_crops), settings.allowHarvestingUserCrops) {
            callbacks.onSafeAutonomyChanged(settings.copy(allowHarvestingUserCrops = it))
        }
    }
}

@Composable
internal fun SafeSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun BrainErrorCard(error: String) {
    val message = stringResource(when (error) {
        "charging" -> R.string.tama_world_runtime_charging
        "battery" -> R.string.tama_world_runtime_battery
        "thermal" -> R.string.tama_world_runtime_thermal
        "evaluate_candidate_before_adoption", "evaluation_candidate_changed" -> R.string.tama_world_brain_evaluate_again
        "checkpoint_curriculum_mismatch" -> R.string.tama_world_brain_checkpoint_curriculum_error
        else -> R.string.tama_world_brain_recoverable_error
    })
    BrainCard {
        Text(stringResource(R.string.tama_world_brain_error_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.error)
        Text(message, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyDataCard(body: String) {
    BrainCard {
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun BrainCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

private fun percent(value: Float): String = "${formatDecimal(value)}%"

private fun formatDecimal(value: Float): String = "%.1f".format(java.util.Locale.US, value)
