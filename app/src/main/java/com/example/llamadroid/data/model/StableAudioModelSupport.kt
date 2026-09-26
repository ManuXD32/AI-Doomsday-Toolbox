package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import java.util.Locale

/**
 * Shared model-library contract for Stable Audio LiteRT components.
 *
 * The runtime uses camel-case component keys in its manifest while model
 * sources and older drafts may use snake-case or the longer
 * `stable_audio_*` names. Keep one canonical wire spelling here and accept
 * every documented alias at the import/promotion boundary. Families are
 * persisted separately because the tokenizer, text encoder, and codec can be
 * reused by both music and SFX bundles.
 */
object StableAudioModelSupport {
    const val FAMILY_MUSIC = "stable_audio_music"
    const val FAMILY_SFX = "stable_audio_sfx"
    const val FAMILY_SHARED = "stable_audio_shared"

    const val ROLE_DIT = "dit"
    const val ROLE_TEXT_ENCODER = "textEncoder"
    const val ROLE_TOKENIZER = "tokenizer"
    const val ROLE_CODEC_ENCODER = "codecEncoder"
    const val ROLE_CODEC_DECODER = "codecDecoder"
    const val ROLE_LORA = "lora"

    private val roleAliases = mapOf(
        "dit" to ROLE_DIT,
        "stable_audio_dit" to ROLE_DIT,
        "diffusion" to ROLE_DIT,
        "textencoder" to ROLE_TEXT_ENCODER,
        "stable_audio_text_encoder" to ROLE_TEXT_ENCODER,
        "text_encoder" to ROLE_TEXT_ENCODER,
        "tokenizer" to ROLE_TOKENIZER,
        "stable_audio_tokenizer" to ROLE_TOKENIZER,
        "codecencoder" to ROLE_CODEC_ENCODER,
        "codec_encoder" to ROLE_CODEC_ENCODER,
        "stable_audio_codec_encoder" to ROLE_CODEC_ENCODER,
        "codecdecoder" to ROLE_CODEC_DECODER,
        "codec_decoder" to ROLE_CODEC_DECODER,
        "stable_audio_codec_decoder" to ROLE_CODEC_DECODER,
        "lora" to ROLE_LORA,
        "adapter" to ROLE_LORA,
        "stable_audio_lora" to ROLE_LORA
    )

    /** Returns the canonical manifest role, or null for a generic LiteRT model. */
    fun canonicalRole(role: String?): String? {
        val normalized = role
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace('-', '_')
            ?.replace(' ', '_')
            ?.replace(Regex("[^a-z0-9_]"), "")
            .orEmpty()
        return roleAliases[normalized]
    }

    fun isComponentRole(role: String?): Boolean = canonicalRole(role) != null

    fun typeForRole(role: String?): ModelType? = when (canonicalRole(role)) {
        ROLE_DIT -> ModelType.LITERT_AUDIO_DIT
        ROLE_TEXT_ENCODER,
        ROLE_TOKENIZER,
        ROLE_CODEC_ENCODER,
        ROLE_CODEC_DECODER,
        ROLE_LORA -> ModelType.LITERT_AUDIO_COMPONENT
        else -> null
    }

    fun defaultRoleForType(type: ModelType): String? = when (type) {
        ModelType.LITERT_AUDIO_DIT -> ROLE_DIT
        // The component enum groups several incompatible assets. Never infer
        // a codec/tokenizer/encoder role from the broad enum alone; imported
        // rows without a persisted role must remain manually classifiable.
        ModelType.LITERT_AUDIO_COMPONENT -> null
        else -> null
    }

    fun isFamily(value: String?): Boolean = value?.trim()?.lowercase(Locale.US) in
        setOf(FAMILY_MUSIC, FAMILY_SFX, FAMILY_SHARED)

    fun familyForRole(role: String?): String = when (canonicalRole(role)) {
        ROLE_DIT -> FAMILY_MUSIC
        else -> FAMILY_SHARED
    }
}
