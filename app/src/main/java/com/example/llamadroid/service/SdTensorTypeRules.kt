package com.example.llamadroid.service

enum class SdTensorTypeRulesPreset {
    AUTO,
    VAE_F16,
    CUSTOM
}

object SdTensorTypeRules {
    const val VAE_F16 = "^vae\\.=f16,^first_stage_model\\.=f16"

    fun presetFor(value: String): SdTensorTypeRulesPreset =
        when (value.trim()) {
            "" -> SdTensorTypeRulesPreset.AUTO
            VAE_F16 -> SdTensorTypeRulesPreset.VAE_F16
            else -> SdTensorTypeRulesPreset.CUSTOM
        }

    fun valueFor(preset: SdTensorTypeRulesPreset, customValue: String = ""): String =
        when (preset) {
            SdTensorTypeRulesPreset.AUTO -> ""
            SdTensorTypeRulesPreset.VAE_F16 -> VAE_F16
            SdTensorTypeRulesPreset.CUSTOM -> customValue
        }
}
