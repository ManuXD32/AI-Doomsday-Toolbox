package com.example.llamadroid.tama.world.training

import com.example.llamadroid.tama.world.core.AutonomyPolicy
import kotlinx.serialization.Serializable

/** Presentation data only. None of these training records are adventure events. */
@Serializable
data class BrainCheckpointEvaluation(
    /** The immutable checkpoint whose candidate artifact was evaluated. */
    val checkpointId: String,
    /** SHA-256 of the exact candidate inference artifact used by the holdout run. */
    val candidateArtifactHash: String,
    /** SHA-256 of the exact current/reference inference artifact used in the same run. */
    val currentArtifactHash: String,
    val holdoutDomain: String = "evaluation-high-bit",
    val candidate: EvaluationMetrics,
    val current: EvaluationMetrics,
    val evaluatedAt: Long
)

/** Presentation data only. None of these training records are adventure events. */
@Serializable
data class BrainRuntimeCheckpoint(
    val id: String,
    val createdAt: Long,
    val episodes: Long,
    val curriculumId: Int,
    val modelHash: String,
    val profile: String = "ECO",
    /** Candidate policy version embedded in the inference artifact, distinct from [modelHash]. */
    val policyVersion: String? = null,
    val objectiveSteps: Float = 0f,
    val pathEfficiency: Float = 0f,
    val stuckRate: Float = 0f,
    val criticalNeedsRate: Float = 0f,
    val explorationScore: Float = 0f,
    /** Stable reward schema metadata; null is retained for indexes written before this field. */
    val rewardConfiguration: String? = null,
    /** The saved checkpoint from which this training branch was restored or continued. */
    val parentCheckpointId: String? = null,
    /** Holdout metrics attached only after the exact artifact hash has been verified. */
    val evaluation: BrainCheckpointEvaluation? = null
)

@Serializable
data class BrainMetricSample(
    val step: Long,
    val reward: Float,
    val success: Float,
    val loss: Float,
    /** Mean action count to measured objective completion. */
    val objectiveSteps: Float = 0f,
    /** Mean shortest-feasible-route / actual-movement ratio. */
    val pathEfficiency: Float = 0f,
    val stuckRate: Float = 0f,
    val criticalNeedsRate: Float = 0f,
    /** Mean discovered traversable fraction. */
    val explorationScore: Float = 0f
)

/**
 * Small presentation state stored beside synthetic checkpoints. The training checkpoint remains
 * the source of truth for optimizer and rollout state; this record lets the UI hydrate history and
 * controls before a worker is started.
 */
@Serializable
internal data class BrainRuntimePersistence(
    /** New sessions start at trainable level 1; an explicitly persisted level 0 remains valid. */
    val curriculumId: Int = 1,
    val profile: String = "ECO",
    val chargingOnly: Boolean = true,
    val minimumBatteryPercent: Int = 20,
    val maxThreads: Int = 0,
    val environmentCount: Int = 0,
    val speed: Int = 1,
    val history: List<BrainMetricSample> = emptyList(),
    val evaluationHistory: List<BrainMetricSample> = emptyList(),
    val adoptedPolicyId: String? = null,
    val adoptedTrainingCheckpointId: String? = null,
    val candidateCheckpointId: String? = null
)

data class BrainRuntimeState(
    val snapshot: TrainerSnapshot? = null,
    val requestedRunning: Boolean = false,
    val running: Boolean = false,
    val speed: Int = 1,
    val profile: TrainerProfile = TrainerProfile.ECO,
    val chargingOnly: Boolean = true,
    val minimumBatteryPercent: Int = 20,
    val maxThreads: Int = 1,
    val environmentCount: Int = 2,
    val pauseReason: String? = null,
    val error: String? = null,
    val checkpoints: List<BrainRuntimeCheckpoint> = emptyList(),
    val comparison: PolicyComparison? = null,
    val evaluating: Boolean = false,
    val adoptedPolicyId: String? = null,
    /** ID of the currently installed living policy, or [BASELINE_LIVING_POLICY_ID]. */
    val activePolicyId: String = BASELINE_LIVING_POLICY_ID,
    /** Monotonic living-policy version; zero identifies the deterministic baseline. */
    val activePolicyVersion: Int = 0,
    /** Previously adopted policies, hydrated from the living repository. */
    val adoptedPolicies: List<LivingPolicySummary> = emptyList(),
    /** Saved synthetic checkpoint that supplied the current adopted artifact, when known. */
    val adoptedTrainingCheckpointId: String? = null,
    /** Latest saved checkpoint whose candidate policy version can be evaluated. */
    val candidateCheckpointId: String? = null,
    /** Checkpoint ID used by the currently displayed holdout comparison; `candidate` means live candidate. */
    val evaluatedCheckpointId: String? = null,
    /** True only when the controller loaded an active living inference artifact. */
    val hasAdoptedComparisonPolicy: Boolean = false,
    val history: List<BrainMetricSample> = emptyList(),
    val evaluationHistory: List<BrainMetricSample> = emptyList(),
    val autonomy: AutonomyPolicy = AutonomyPolicy()
)
