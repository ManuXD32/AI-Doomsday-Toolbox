package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.model.StableAudioModelSupport
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

private val audioMainRoles = setOf(
    "tts", "tts_main", "speech", "audio_tts", "qwen3_tts", "pocket_tts"
)

private val audioCompanionRoles = setOf(
    "tts_mmproj", "tts_companion", "speech_mmproj", "speaker_encoder", "codec"
)

/** Stable Audio 3 Small component roles stay generic so LiteRT music assets
 * cannot be mistaken for a llama.cpp chat or TTS enum row. */
val stableAudioComponentRoles: Set<String> = setOf(
    "dit",
    "textencoder",
    "text_encoder",
    "tokenizer",
    "codecencoder",
    "codec_encoder",
    "codecdecoder",
    "codec_decoder",
    "lora",
    // Keep the long source/catalog spellings accepted for portable metadata.
    "stable_audio_dit",
    "stable_audio_text_encoder",
    "stable_audio_codec_encoder",
    "stable_audio_codec_decoder",
    "stable_audio_tokenizer",
    "stable_audio_lora"
)

fun isSdVideoCompanionRole(role: String?): Boolean =
    normalizedModelLibraryRole(role) in sdVideoCompanionRoles

fun isSdVisionCompanionRole(role: String?): Boolean =
    normalizedModelLibraryRole(role) in sdVisionCompanionRoles

fun isSdLlmCompanionRole(role: String?): Boolean =
    normalizedModelLibraryRole(role) in sdLlmCompanionRoles

fun isAudioMainRole(role: String?): Boolean = normalizedModelLibraryRole(role) in audioMainRoles

fun isAudioCompanionRole(role: String?): Boolean = normalizedModelLibraryRole(role) in audioCompanionRoles

fun isStableAudioComponentRole(role: String?): Boolean =
    StableAudioModelSupport.isComponentRole(role) ||
        normalizedModelLibraryRole(role) in stableAudioComponentRoles

/**
 * Companion roles require explicit selection when inspection cannot confidently
 * identify their matching runtime type. Callers with high structural confidence
 * may register the matching type directly; a parsed container alone is insufficient.
 */
fun requiresManualRoleSelection(family: ModelFamily, role: String?): Boolean =
    when (family) {
        ModelFamily.SD -> isSdVideoCompanionRole(role) || isSdVisionCompanionRole(role)
        ModelFamily.AUDIO -> role.isNullOrBlank()
        else -> false
    }

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
    // A GGUF TTS component can be structurally recognized as an LLM by older
    // inspectors. Permit the explicit audio role while keeping untyped LLM
    // sources out of audio bundles.
    bundleFamily == ModelFamily.AUDIO && sourceFamily == ModelFamily.LLM ->
        isAudioMainRole(itemRole) || isAudioCompanionRole(itemRole)
    else -> false
}
