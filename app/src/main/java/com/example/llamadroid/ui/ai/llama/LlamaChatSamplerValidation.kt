package com.example.llamadroid.ui.ai.llama

import com.example.llamadroid.service.chat.LlamaSamplerKeys

/**
 * Catalog of every sampler a chat can set, one entry per [LlamaSamplerKeys] key. [startValue] is the
 * number shown when the user turns Auto off. [basic] fields stay in the main sampling block; the
 * rest live in the collapsible "Advanced sampling" block. [llamaOnly] fields are hidden for LiteRT
 * (which only knows temperature, top_p and top_k). [choice] fields render as chips instead of text.
 */
internal enum class LlamaSamplerField(
    val key: String,
    val minimum: Float,
    val maximum: Float,
    val startValue: Float,
    val integer: Boolean = false,
    val basic: Boolean = false,
    val llamaOnly: Boolean = true,
    val choice: Boolean = false
) {
    TEMPERATURE(LlamaSamplerKeys.TEMPERATURE, 0f, 2f, 0.8f, basic = true, llamaOnly = false),
    TOP_P(LlamaSamplerKeys.TOP_P, 0f, 1f, 0.95f, basic = true, llamaOnly = false),
    TOP_K(LlamaSamplerKeys.TOP_K, 0f, 200f, 40f, integer = true, basic = true, llamaOnly = false),
    MIN_P(LlamaSamplerKeys.MIN_P, 0f, 1f, 0.05f, basic = true),
    REPETITION_PENALTY(LlamaSamplerKeys.REPEAT_PENALTY, 0.5f, 2f, 1.1f, basic = true),
    PRESENCE_PENALTY(LlamaSamplerKeys.PRESENCE_PENALTY, -2f, 2f, 0f),
    FREQUENCY_PENALTY(LlamaSamplerKeys.FREQUENCY_PENALTY, -2f, 2f, 0f),
    REPEAT_LAST_N(LlamaSamplerKeys.REPEAT_LAST_N, -1f, 4096f, 64f, integer = true),
    TYPICAL_P(LlamaSamplerKeys.TYPICAL_P, 0f, 1f, 1f),
    TOP_N_SIGMA(LlamaSamplerKeys.TOP_N_SIGMA, -1f, 10f, -1f),
    XTC_PROBABILITY(LlamaSamplerKeys.XTC_PROBABILITY, 0f, 1f, 0f),
    XTC_THRESHOLD(LlamaSamplerKeys.XTC_THRESHOLD, 0f, 1f, 0.1f),
    DRY_MULTIPLIER(LlamaSamplerKeys.DRY_MULTIPLIER, 0f, 5f, 0f),
    DRY_BASE(LlamaSamplerKeys.DRY_BASE, 1f, 4f, 1.75f),
    DRY_ALLOWED_LENGTH(LlamaSamplerKeys.DRY_ALLOWED_LENGTH, 0f, 64f, 2f, integer = true),
    DRY_PENALTY_LAST_N(LlamaSamplerKeys.DRY_PENALTY_LAST_N, -1f, 8192f, -1f, integer = true),
    DYNATEMP_RANGE(LlamaSamplerKeys.DYNATEMP_RANGE, 0f, 2f, 0f),
    DYNATEMP_EXPONENT(LlamaSamplerKeys.DYNATEMP_EXPONENT, 0f, 5f, 1f),
    MIROSTAT(LlamaSamplerKeys.MIROSTAT, 0f, 2f, 0f, integer = true, choice = true),
    MIROSTAT_TAU(LlamaSamplerKeys.MIROSTAT_TAU, 0f, 20f, 5f),
    MIROSTAT_ETA(LlamaSamplerKeys.MIROSTAT_ETA, 0f, 1f, 0.1f);

    /** True when the key is sent as a JSON integer. */
    val isIntegerKey: Boolean get() = key in LlamaSamplerKeys.INTEGER_KEYS

    companion object {
        /** Fields the sampling UI shows for the given engine. */
        fun visible(liteRt: Boolean): List<LlamaSamplerField> =
            entries.filter { !liteRt || !it.llamaOnly }

        fun fromKey(key: String): LlamaSamplerField? = entries.firstOrNull { it.key == key }
    }
}

internal data class LlamaSamplerRange(
    val minimum: Float,
    val maximum: Float,
    val integer: Boolean = false
)

internal object LlamaChatSamplerValidation {
    /** Allowed range, or null when LiteRT does not support the field. */
    fun range(field: LlamaSamplerField, liteRt: Boolean): LlamaSamplerRange? {
        if (liteRt) {
            return when (field) {
                LlamaSamplerField.TEMPERATURE -> LlamaSamplerRange(0f, 1f)
                LlamaSamplerField.TOP_P -> LlamaSamplerRange(0f, 0.95f)
                LlamaSamplerField.TOP_K -> LlamaSamplerRange(5f, 64f, integer = true)
                else -> null
            }
        }
        return LlamaSamplerRange(field.minimum, field.maximum, field.integer)
    }

    fun parse(text: String, field: LlamaSamplerField, liteRt: Boolean): Float? {
        val range = range(field, liteRt) ?: return null
        val parsed = text.trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        if (parsed < range.minimum || parsed > range.maximum) return null
        if (range.integer && parsed != parsed.toInt().toFloat()) return null
        return parsed
    }

    fun isNumeric(text: String): Boolean = text.trim().toFloatOrNull()?.isFinite() == true

    fun rangeText(field: LlamaSamplerField, liteRt: Boolean): String {
        val range = range(field, liteRt) ?: return ""
        val minimum = if (range.integer) range.minimum.toInt().toString() else compact(range.minimum)
        val maximum = if (range.integer) range.maximum.toInt().toString() else compact(range.maximum)
        return "$minimum–$maximum"
    }

    /** Start value for turning Auto off, clamped into the engine's range. */
    fun startValue(field: LlamaSamplerField, liteRt: Boolean): Float {
        val range = range(field, liteRt) ?: return field.startValue
        return field.startValue.coerceIn(range.minimum, range.maximum)
    }

    private fun compact(value: Float): String = if (value == value.toInt().toFloat()) {
        value.toInt().toString()
    } else {
        "%.2f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
    }
}
