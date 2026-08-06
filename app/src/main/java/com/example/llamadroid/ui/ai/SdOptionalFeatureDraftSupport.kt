package com.example.llamadroid.ui.ai

import org.json.JSONObject

data class SdIpAdapterDraftState(
    val enabled: Boolean = false,
    val adapterPath: String? = null,
    val clipVisionPath: String? = null,
    val imagePath: String? = null,
    val strength: Float = DEFAULT_STRENGTH
) {
    fun normalized(): SdIpAdapterDraftState = copy(
        adapterPath = adapterPath?.trim()?.ifBlank { null },
        clipVisionPath = clipVisionPath?.trim()?.ifBlank { null },
        imagePath = imagePath?.trim()?.ifBlank { null },
        strength = strength.takeIf { it.isFinite() && it >= 0f } ?: DEFAULT_STRENGTH
    )

    fun isDefault(): Boolean {
        val value = normalized()
        return !value.enabled &&
            value.adapterPath == null &&
            value.clipVisionPath == null &&
            value.imagePath == null &&
            value.strength == DEFAULT_STRENGTH
    }

    companion object {
        const val DEFAULT_STRENGTH = 1.0f
    }
}

fun JSONObject.readSdIpAdapterDraft(): SdIpAdapterDraftState {
    val value = optJSONObject(IP_ADAPTER_DRAFT_KEY) ?: return SdIpAdapterDraftState()
    return runCatching {
        SdIpAdapterDraftState(
            enabled = value.optBooleanStrict("enabled") ?: false,
            adapterPath = value.optStringStrict("adapter"),
            clipVisionPath = value.optStringStrict("clipVision"),
            imagePath = value.optStringStrict("image"),
            strength = value.optNumberStrict("strength")?.toFloat()
                ?: SdIpAdapterDraftState.DEFAULT_STRENGTH
        ).normalized()
    }.getOrDefault(SdIpAdapterDraftState())
}

fun JSONObject.putSdIpAdapterDraft(state: SdIpAdapterDraftState): JSONObject = apply {
    val normalized = state.normalized()
    if (normalized.isDefault()) {
        remove(IP_ADAPTER_DRAFT_KEY)
        return@apply
    }
    put(
        IP_ADAPTER_DRAFT_KEY,
        JSONObject().apply {
            put("enabled", normalized.enabled)
            put("adapter", normalized.adapterPath ?: JSONObject.NULL)
            put("clipVision", normalized.clipVisionPath ?: JSONObject.NULL)
            put("image", normalized.imagePath ?: JSONObject.NULL)
            put("strength", normalized.strength.toDouble())
        }
    )
}

private fun JSONObject.optBooleanStrict(key: String): Boolean? =
    takeIf { has(key) && !isNull(key) }
        ?.opt(key)
        ?.takeIf { it is Boolean }
        ?.let { it as Boolean }

private fun JSONObject.optStringStrict(key: String): String? =
    takeIf { has(key) && !isNull(key) }
        ?.opt(key)
        ?.takeIf { it is String }
        ?.let { (it as String).trim().ifBlank { null } }

private fun JSONObject.optNumberStrict(key: String): Number? =
    takeIf { has(key) && !isNull(key) }
        ?.opt(key)
        ?.takeIf { it is Number }
        ?.let { it as Number }

private const val IP_ADAPTER_DRAFT_KEY = "ipAdapter"
