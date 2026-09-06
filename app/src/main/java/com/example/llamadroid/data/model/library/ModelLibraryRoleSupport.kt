package com.example.llamadroid.data.model.library

import java.util.Locale

/**
 * Canonical spellings used by saved bundle metadata. The persisted role keeps
 * the user's original spelling; these helpers are only for compatibility and
 * runtime mapping decisions.
 */
fun normalizedModelLibraryRole(role: String?): String = role
    ?.trim()
    ?.lowercase(Locale.US)
    ?.replace('-', '_')
    ?.replace(' ', '_')
    ?.replace(Regex("[^a-z0-9_]+"), "")
    .orEmpty()

private val sdVideoCompanionRoles = setOf(
    "audio_vae",
    "audiovae",
    "embeddings_connectors",
    "embeddingsconnector",
    "connectors",
    "motion_module",
    "motionmodule",
    "high_noise",
    "highnoise",
    "high_noise_diffusion",
    "high_noise_diffusion_model"
)

private val sdVisionCompanionRoles = setOf(
    "llm_vision",
    "llmvision",
    "vision_projector",
    "clip_vision",
    "clipvision",
    "vision_encoder",
    "mmproj"
)

private val sdLlmCompanionRoles = setOf(
    "llm",
    "text_encoder",
    "textencoder",
    "tokenizer",
    "shared_tokenizer",
    "shared_text_encoder"
)

fun isSdVideoCompanionRole(role: String?): Boolean =
    normalizedModelLibraryRole(role) in sdVideoCompanionRoles

fun isSdVisionCompanionRole(role: String?): Boolean =
    normalizedModelLibraryRole(role) in sdVisionCompanionRoles

fun isSdLlmCompanionRole(role: String?): Boolean =
    normalizedModelLibraryRole(role) in sdLlmCompanionRoles

/**
 * Companion roles require explicit selection when inspection cannot confidently
 * identify their matching runtime type. Callers with high structural confidence
 * may register the matching type directly; a parsed container alone is insufficient.
 */
fun requiresManualRoleSelection(family: ModelFamily, role: String?): Boolean =
    family == ModelFamily.SD &&
        (isSdVideoCompanionRole(role) || isSdVisionCompanionRole(role))

/**
 * Source family describes provenance, while the bundle item describes runtime
 * use. A cross-family edge is allowed only for an explicit SD companion role;
 * an untyped LLM source can never silently become an SD item.
 */
fun isCompatibleSourceFamily(
    sourceFamily: ModelFamily,
    bundleFamily: ModelFamily,
    itemRole: String?
): Boolean = when {
    sourceFamily == bundleFamily -> true
    bundleFamily == ModelFamily.SD && sourceFamily == ModelFamily.LLM ->
        isSdLlmCompanionRole(itemRole) || isSdVisionCompanionRole(itemRole)
    else -> false
}
