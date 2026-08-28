package com.example.llamadroid.ui.ai.llama

internal enum class LlamaSamplerField {
    TEMPERATURE,
    TOP_P,
    TOP_K,
    MIN_P,
    REPETITION_PENALTY
}

internal data class LlamaSamplerRange(
    val minimum: Float,
    val maximum: Float,
    val integer: Boolean = false
)

internal object LlamaChatSamplerValidation {
    fun range(field: LlamaSamplerField, liteRt: Boolean): LlamaSamplerRange? = when (field) {
        LlamaSamplerField.TEMPERATURE -> LlamaSamplerRange(0f, if (liteRt) 1f else 2f)
        LlamaSamplerField.TOP_P -> LlamaSamplerRange(0f, if (liteRt) 0.95f else 1f)
        LlamaSamplerField.TOP_K -> LlamaSamplerRange(if (liteRt) 5f else 1f, if (liteRt) 64f else 100f, integer = true)
        LlamaSamplerField.MIN_P -> if (liteRt) null else LlamaSamplerRange(0f, 1f)
        LlamaSamplerField.REPETITION_PENALTY -> if (liteRt) null else LlamaSamplerRange(1f, 2f)
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

    private fun compact(value: Float): String = if (value == value.toInt().toFloat()) {
        value.toInt().toString()
    } else {
        "%.2f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
    }
}
