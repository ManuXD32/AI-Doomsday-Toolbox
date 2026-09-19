package com.example.llamadroid.tama.world.presentation

import com.example.llamadroid.tama.world.core.AutonomyLevel
import com.example.llamadroid.tama.world.core.AutonomyPolicy
import com.example.llamadroid.tama.world.training.BrainRuntimeCheckpoint
import com.example.llamadroid.tama.world.training.BrainRuntimeState
import com.example.llamadroid.tama.world.training.BASELINE_LIVING_POLICY_ID
import com.example.llamadroid.tama.world.training.CurriculumCatalog
import com.example.llamadroid.tama.world.training.CurriculumLevel
import com.example.llamadroid.tama.world.training.EnvironmentPreview
import com.example.llamadroid.tama.world.training.LivingPolicySummary
import com.example.llamadroid.tama.world.training.PolicyComparison
import com.example.llamadroid.tama.world.training.TrainerSnapshot
import com.example.llamadroid.tama.world.policy.PolicySpec
import com.example.llamadroid.tama.world.ui.BrainAdoptedPolicyUi
import com.example.llamadroid.tama.world.ui.BrainCheckpointUi
import com.example.llamadroid.tama.world.ui.BrainEvaluationUi
import com.example.llamadroid.tama.world.ui.BrainLiveEnvironmentUi
import com.example.llamadroid.tama.world.ui.BrainMetricPointUi
import com.example.llamadroid.tama.world.ui.BrainMetricSeriesUi
import com.example.llamadroid.tama.world.ui.BrainMiniActorUi
import com.example.llamadroid.tama.world.ui.BrainRunState
import com.example.llamadroid.tama.world.ui.BrainResourceSettingsUi
import com.example.llamadroid.tama.world.ui.BrainTrainingMetricsUi
import com.example.llamadroid.tama.world.ui.BrainTrainingProfileUi
import com.example.llamadroid.tama.world.ui.BrainTrainingUiState
import com.example.llamadroid.tama.world.ui.SafeAutonomyUi
import com.example.llamadroid.tama.world.ui.WorldAutonomyLevel
import kotlin.math.roundToInt

/** Labels for trainer data that is not part of the living-world locale catalog. */
data class BrainUiLabels(
    val currentBrainName: String = "Pet brain",
    val curriculumName: (CurriculumLevel) -> String = { humanize(it.name) },
    val checkpointName: (BrainRuntimeCheckpoint) -> String = { checkpoint -> "Checkpoint ${checkpoint.id}" },
    val unavailable: String = "Unavailable",
    val actionName: (com.example.llamadroid.tama.world.policy.MovementIntent) -> String = { humanize(it.name) },
    val rewardMetricName: String = "Reward",
    val successMetricName: String = "Success",
    val lossMetricName: String = "Loss",
    val objectiveStepsMetricName: String = "Objective steps",
    val pathEfficiencyMetricName: String = "Path efficiency",
    val stuckMetricName: String = "Stuck rate",
    val criticalNeedsMetricName: String = "Critical-needs rate",
    val explorationMetricName: String = "Exploration",
    val stepsUnit: String = "steps",
    val returnUnit: String = "return",
    val percentageUnit: String = "%",
    val lossUnit: String = "loss",
    val checkpointMetrics: (Long) -> String = { episodes -> "episodes=$episodes" },
    val checkpointCreatedAt: (Long) -> String = { timestamp -> timestamp.toString() },
    val checkpointMeasurements: (BrainRuntimeCheckpoint) -> String = { checkpoint ->
        "objective=${checkpoint.objectiveSteps}; path=${(checkpoint.pathEfficiency * 100f).roundToInt()}%; " +
            "exploration=${(checkpoint.explorationScore * 100f).roundToInt()}%"
    },
    /** Formats the stable reward descriptor for the active locale. */
    val checkpointRewardConfiguration: (String) -> String = { it }
)

/**
 * Adapts the trainer's actual snapshot, history, previews, and comparisons to
 * the scrollable Brain screen. Missing telemetry stays null or empty rather
 * than being replaced with invented samples.
 */
fun projectBrainRuntimeState(
    runtime: BrainRuntimeState,
    labels: BrainUiLabels = BrainUiLabels(),
    currentBrainName: String = labels.currentBrainName,
    currentBrainVersion: Int = runtime.activePolicyVersion,
    profiles: List<BrainTrainingProfileUi> = emptyList(),
    autonomyBase: SafeAutonomyUi = SafeAutonomyUi(),
    autonomyPolicy: AutonomyPolicy = runtime.autonomy,
    candidateCheckpointId: String? = null
): BrainTrainingUiState {
    val snapshot = runtime.snapshot
    val metrics = projectMetrics(snapshot)
    val currentCurriculum = snapshot?.curriculum ?: CurriculumLevel.LOCOMOTION_VALIDATION
    val checkpoints = runtime.checkpoints.map { checkpoint ->
        projectCheckpoint(runtime, snapshot, checkpoint, labels)
    }
    val adoptedPolicies = runtime.adoptedPolicies.map(::projectAdoptedPolicy)
    return BrainTrainingUiState(
        currentBrainName = currentBrainName,
        currentBrainVersion = currentBrainVersion,
        trainingAgeEpisodes = snapshot?.metrics?.episodes ?: 0L,
        // The persisted ID is historical metadata. The controller sets this bit only after it
        // has loaded and validated the artifact currently installed in the living world.
        adopted = runtime.hasAdoptedComparisonPolicy,
        activePolicyIsBaseline = runtime.activePolicyId == BASELINE_LIVING_POLICY_ID,
        currentCurriculum = labels.curriculumName(currentCurriculum),
        currentCurriculumId = currentCurriculum.id,
        metrics = metrics,
        liveEnvironment = snapshot?.previews?.firstOrNull()?.let { preview ->
            projectPreview(snapshot, preview, labels)
        },
        runState = when {
            runtime.error != null -> BrainRunState.FAILED
            runtime.running -> BrainRunState.RUNNING
            runtime.requestedRunning || runtime.pauseReason != null || snapshot?.paused == true -> BrainRunState.PAUSED
            else -> BrainRunState.IDLE
        },
        speedMultiplier = runtime.speed.coerceAtLeast(1),
        charts = projectCharts(runtime, labels),
        checkpoints = checkpoints,
        adoptedPolicies = adoptedPolicies,
        evaluation = runtime.comparison?.let { comparison ->
            projectEvaluation(
                comparison,
                candidateCheckpointId ?: runtime.evaluatedCheckpointId ?: "candidate",
                runtime.hasAdoptedComparisonPolicy
            )
        },
        profiles = profiles,
        safeAutonomy = autonomyPolicy.toUiAutonomy(autonomyBase),
        resourceSettings = BrainResourceSettingsUi(
            chargingOnly = runtime.chargingOnly,
            minimumBatteryPercent = runtime.minimumBatteryPercent,
            maxThreads = runtime.maxThreads,
            environmentCount = runtime.environmentCount
        ),
        isEvaluating = runtime.evaluating,
        isLoading = snapshot == null && runtime.error == null,
        error = runtime.error ?: runtime.pauseReason
    )
}

private fun projectMetrics(snapshot: TrainerSnapshot?): BrainTrainingMetricsUi {
    val metrics = snapshot?.metrics ?: return BrainTrainingMetricsUi(
        episodes = 0L,
        successRatePercent = 0f,
        meanReward = 0f,
        objectiveSteps = 0f,
        pathEfficiencyPercent = 0f,
        stuckRatePercent = 0f,
        criticalNeedsRatePercent = 0f,
        explorationScorePercent = 0f
    )
    return BrainTrainingMetricsUi(
        episodes = metrics.episodes,
        successRatePercent = metrics.successRate * 100f,
        meanReward = metrics.meanEpisodeReturn,
        objectiveSteps = metrics.objectiveSteps,
        pathEfficiencyPercent = metrics.pathEfficiency * 100f,
        stuckRatePercent = metrics.stuckRate * 100f,
        criticalNeedsRatePercent = metrics.criticalNeedsRate * 100f,
        explorationScorePercent = metrics.explorationScore * 100f
    )
}

private fun projectCharts(runtime: BrainRuntimeState, labels: BrainUiLabels): List<BrainMetricSeriesUi> {
    val history = runtime.history.takeLast(120)
    val evaluations = runtime.evaluationHistory.takeLast(120)
    if (history.isEmpty() && evaluations.isEmpty()) return emptyList()
    fun series(
        id: String,
        label: String,
        unit: String,
        evaluation: Boolean = true,
        value: (com.example.llamadroid.tama.world.training.BrainMetricSample) -> Float
    ) = BrainMetricSeriesUi(
        id = id,
        label = label,
        unit = unit,
        points = history.map { BrainMetricPointUi(it.step, value(it)) },
        evaluationPoints = if (evaluation) evaluations.map { BrainMetricPointUi(it.step, value(it)) } else emptyList()
    )
    return listOf(
        series("reward", labels.rewardMetricName, labels.returnUnit) { it.reward },
        series("success", labels.successMetricName, labels.percentageUnit) { it.success * 100f },
        series("objective_steps", labels.objectiveStepsMetricName, labels.stepsUnit) { it.objectiveSteps },
        series("path_efficiency", labels.pathEfficiencyMetricName, labels.percentageUnit) { it.pathEfficiency * 100f },
        series("stuck", labels.stuckMetricName, labels.percentageUnit) { it.stuckRate * 100f },
        series("critical_needs", labels.criticalNeedsMetricName, labels.percentageUnit) { it.criticalNeedsRate * 100f },
        series("exploration", labels.explorationMetricName, labels.percentageUnit) { it.explorationScore * 100f },
        series("loss", labels.lossMetricName, labels.lossUnit, evaluation = false) { it.loss }
    )
}

private fun projectPreview(
    snapshot: TrainerSnapshot,
    preview: EnvironmentPreview,
    labels: BrainUiLabels
): BrainLiveEnvironmentUi {
    val definition = CurriculumCatalog.definition(preview.curriculum)
    val observation = preview.observation
    val observationSide = PolicySpec.OBSERVATION_SIDE
    val hasObservation = observation != null
    val radius = PolicySpec.OBSERVATION_RADIUS
    val goalX = if (hasObservation && kotlin.math.abs(preview.goalX - preview.x) <= radius) {
        preview.goalX - preview.x + radius
    } else if (!hasObservation) {
        preview.goalX
    } else {
        null
    }
    val goalY = if (hasObservation && kotlin.math.abs(preview.goalY - preview.y) <= radius) {
        preview.goalY - preview.y + radius
    } else if (!hasObservation) {
        preview.goalY
    } else {
        null
    }
    return BrainLiveEnvironmentUi(
        episodeNumber = snapshot.metrics.episodes,
        seed = preview.seed,
        goal = "${preview.goalX}, ${preview.goalY}",
        currentAction = labels.actionName(preview.lastAction),
        hunger = (preview.needs.hunger * 100f).roundToInt().coerceIn(0, 100),
        hydration = (preview.needs.hydration * 100f).roundToInt().coerceIn(0, 100),
        energy = (preview.needs.energy * 100f).roundToInt().coerceIn(0, 100),
        widthTiles = if (hasObservation) observationSide else definition.worldWidth,
        heightTiles = if (hasObservation) observationSide else definition.worldHeight,
        tiles = observation?.let(::projectPreviewTiles).orEmpty(),
        actors = listOf(
            BrainMiniActorUi(
                id = "environment:${preview.environmentId}",
                x = if (hasObservation) radius.toFloat() else preview.x.toFloat(),
                y = if (hasObservation) radius.toFloat() else preview.y.toFloat(),
                isPet = true,
                label = labels.currentBrainName
            )
        ),
        goalX = goalX,
        goalY = goalY,
        terminated = preview.terminated,
        success = preview.success
    )
}

private fun projectPreviewTiles(observation: com.example.llamadroid.tama.world.policy.Observation): List<com.example.llamadroid.tama.world.ui.BrainMiniTileUi> {
    val side = PolicySpec.OBSERVATION_SIDE
    val channels = PolicySpec.TILE_CHANNELS
    return buildList(side * side) {
        for (y in 0 until side) {
            for (x in 0 until side) {
                val offset = (y * side + x) * channels
                val features = observation.tileFeatures
                val terrainOrdinal = (features[offset + 1] * (PolicySpec.TERRAIN_CLASS_NAMES.size - 1))
                    .roundToInt()
                    .coerceIn(0, PolicySpec.TERRAIN_CLASS_NAMES.lastIndex)
                add(
                    com.example.llamadroid.tama.world.ui.BrainMiniTileUi(
                        x = x,
                        y = y,
                        terrainId = "terrain_${PolicySpec.TERRAIN_CLASS_NAMES[terrainOrdinal].lowercase()}",
                        known = features[offset + 11] >= 0.5f,
                        walkable = features[offset] >= 0.5f,
                        food = features[offset + 4] >= 0.5f,
                        resource = features[offset + 5] >= 0.5f,
                        hazard = features[offset + 10] >= 0.5f,
                        goal = features[offset + 12] >= 0.5f
                    )
                )
            }
        }
    }
}

private fun projectCheckpoint(
    runtime: BrainRuntimeState,
    snapshot: TrainerSnapshot?,
    checkpoint: BrainRuntimeCheckpoint,
    labels: BrainUiLabels
): BrainCheckpointUi {
    val curriculum = runCatching { CurriculumLevel.fromId(checkpoint.curriculumId) }
        .getOrNull()
        ?.let(labels.curriculumName)
        ?: checkpoint.curriculumId.toString()
    val isCurrent = runtime.adoptedTrainingCheckpointId == checkpoint.id
    val isCandidate = checkpoint.policyVersion?.let { it == snapshot?.candidatePolicyVersion }
        ?: (runtime.candidateCheckpointId == checkpoint.id)
    return BrainCheckpointUi(
        id = checkpoint.id,
        displayName = labels.checkpointName(checkpoint),
        modelHash = checkpoint.modelHash,
        trainingEpisodes = checkpoint.episodes,
        curriculum = curriculum,
        rewardConfiguration = checkpoint.rewardConfiguration
            ?.takeIf(String::isNotBlank)
            ?.let(labels.checkpointRewardConfiguration)
            ?: labels.unavailable,
        metricSummary = labels.checkpointMetrics(checkpoint.episodes) + " · " + labels.checkpointMeasurements(checkpoint),
        createdAt = labels.checkpointCreatedAt(checkpoint.createdAt),
        isCurrent = isCurrent,
        isCandidate = isCandidate,
        restorable = true
    )
}

private fun projectEvaluation(
    comparison: PolicyComparison,
    candidateCheckpointId: String,
    currentPolicyIsAdopted: Boolean
): BrainEvaluationUi = BrainEvaluationUi(
    candidateCheckpointId = candidateCheckpointId,
    candidateName = comparison.candidate.policyVersion,
    currentSuccessPercent = (comparison.current.successRate * 100.0).toFloat(),
    candidateSuccessPercent = (comparison.candidate.successRate * 100.0).toFloat(),
    currentObjectiveSteps = comparison.current.meanObjectiveSteps.toFloat(),
    candidateObjectiveSteps = comparison.candidate.meanObjectiveSteps.toFloat(),
    currentStuckPercent = (comparison.current.stuckRate * 100.0).toFloat(),
    candidateStuckPercent = (comparison.candidate.stuckRate * 100.0).toFloat(),
    currentNeedFailurePercent = rate(comparison.current.needFailures, comparison.current.seedCount),
    candidateNeedFailurePercent = rate(comparison.candidate.needFailures, comparison.candidate.seedCount),
    unseenSeedCount = minOf(comparison.current.seedCount, comparison.candidate.seedCount),
    complete = true,
    candidateWins = comparison.candidateImproves,
    currentPolicyIsAdopted = currentPolicyIsAdopted,
    currentPathEfficiencyPercent = (comparison.current.pathEfficiency * 100.0).toFloat(),
    candidatePathEfficiencyPercent = (comparison.candidate.pathEfficiency * 100.0).toFloat(),
    currentExplorationPercent = (comparison.current.explorationScore * 100.0).toFloat(),
    candidateExplorationPercent = (comparison.candidate.explorationScore * 100.0).toFloat(),
    currentInvalidActionPercent = (comparison.current.invalidActionRate * 100.0).toFloat(),
    candidateInvalidActionPercent = (comparison.candidate.invalidActionRate * 100.0).toFloat()
)

private fun projectAdoptedPolicy(policy: LivingPolicySummary): BrainAdoptedPolicyUi =
    BrainAdoptedPolicyUi(
        id = policy.id,
        version = policy.version,
        modelHash = policy.modelHash,
        createdAt = policy.createdAt,
        parentId = policy.parentId,
        sourceCheckpointId = policy.sourceCheckpointId,
        active = policy.active,
        baseline = policy.isBaseline
    )

private fun rate(value: Int, total: Int): Float =
    if (total <= 0) 0f else value.toFloat() / total * 100f

private fun AutonomyPolicy.toUiAutonomy(base: SafeAutonomyUi): SafeAutonomyUi = base.copy(
    level = when (level) {
        AutonomyLevel.OFF -> WorldAutonomyLevel.OFF
        AutonomyLevel.SAFE -> WorldAutonomyLevel.SAFE
        AutonomyLevel.NORMAL -> WorldAutonomyLevel.NORMAL
        AutonomyLevel.FULL -> WorldAutonomyLevel.FULL
    },
    allowPurchases = allowPurchases,
    maximumAutonomousPurchase = maximumAutonomousPurchase,
    allowSellingItems = allowSellingItems,
    allowUsingRareItems = allowUsingRareItems,
    allowDungeonEntry = allowDungeonEntry,
    allowAdventureGate = allowAdventureGate,
    allowOvernightExploration = allowOvernightExploration,
    allowWork = allowWork,
    allowStudy = allowStudy,
    allowFarming = allowFarming,
    allowHarvestingUserCrops = allowHarvestingUserCrops
)

private fun humanize(value: String): String = value
    .lowercase()
    .split('_')
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
