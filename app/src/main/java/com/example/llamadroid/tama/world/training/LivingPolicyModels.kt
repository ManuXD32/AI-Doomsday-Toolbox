package com.example.llamadroid.tama.world.training

/** Stable ID used when the living world has no adopted inference artifact. */
const val BASELINE_LIVING_POLICY_ID: String = "baseline"

/**
 * A validated living-policy snapshot used by the app boundary. The artifact is optional because
 * the deterministic baseline has no neural bytes. This DTO never crosses into the pure trainer.
 */
data class LivingPolicyReference(
    val id: String = BASELINE_LIVING_POLICY_ID,
    val version: Int = 0,
    val modelHash: String? = null,
    val inferenceArtifact: ByteArray? = null,
    val sourceCheckpointId: String? = null
) {
    init {
        require(id.isNotBlank()) { "living_policy_id_missing" }
        require(version >= 0) { "living_policy_version_invalid" }
        if (inferenceArtifact == null) {
            require(id == BASELINE_LIVING_POLICY_ID) { "artifact_missing_for_adopted_policy" }
            require(modelHash == null) { "hash_without_artifact" }
        } else {
            require(id != BASELINE_LIVING_POLICY_ID) { "baseline_cannot_have_artifact" }
            require(!modelHash.isNullOrBlank()) { "artifact_hash_missing" }
        }
    }

    val isBaseline: Boolean
        get() = inferenceArtifact == null

    companion object {
        fun baseline(): LivingPolicyReference = LivingPolicyReference()
    }
}

/** Metadata shown by the training screen for previously adopted living policies. */
data class LivingPolicySummary(
    val id: String,
    val version: Int,
    val parentId: String?,
    val modelHash: String?,
    val createdAt: Long,
    val active: Boolean,
    val sourceCheckpointId: String? = null,
    val isBaseline: Boolean = id == BASELINE_LIVING_POLICY_ID
) {
    init {
        require(id.isNotBlank()) { "living_policy_id_missing" }
        require(version >= 0) { "living_policy_version_invalid" }
        if (isBaseline) {
            require(id == BASELINE_LIVING_POLICY_ID) { "invalid_baseline_id" }
            require(version == 0) { "invalid_baseline_version" }
        } else {
            require(!modelHash.isNullOrBlank()) { "adopted_policy_hash_missing" }
            require(version > 0) { "adopted_policy_version_invalid" }
        }
    }

    companion object {
        fun baseline(active: Boolean): LivingPolicySummary = LivingPolicySummary(
            id = BASELINE_LIVING_POLICY_ID,
            version = 0,
            parentId = null,
            modelHash = null,
            createdAt = 0L,
            active = active
        )
    }
}
