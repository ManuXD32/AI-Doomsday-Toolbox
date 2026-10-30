package com.example.llamadroid.service.chat

/**
 * Request keys of the llama.cpp samplers a chat can set. A key that is absent from a chat's API
 * params means "Auto": the request omits it and llama-server uses its own (or the model's) default.
 * The UI catalog (`LlamaSamplerField`) and every request builder read from this one list.
 */
object LlamaSamplerKeys {
    const val TEMPERATURE = "temperature"
    const val TOP_P = "top_p"
    const val TOP_K = "top_k"
    const val MIN_P = "min_p"
    const val REPEAT_PENALTY = "repeat_penalty"
    const val PRESENCE_PENALTY = "presence_penalty"
    const val FREQUENCY_PENALTY = "frequency_penalty"
    const val REPEAT_LAST_N = "repeat_last_n"
    const val TYPICAL_P = "typical_p"
    const val TOP_N_SIGMA = "top_n_sigma"
    const val XTC_PROBABILITY = "xtc_probability"
    const val XTC_THRESHOLD = "xtc_threshold"
    const val DRY_MULTIPLIER = "dry_multiplier"
    const val DRY_BASE = "dry_base"
    const val DRY_ALLOWED_LENGTH = "dry_allowed_length"
    const val DRY_PENALTY_LAST_N = "dry_penalty_last_n"
    const val DYNATEMP_RANGE = "dynatemp_range"
    const val DYNATEMP_EXPONENT = "dynatemp_exponent"
    const val MIROSTAT = "mirostat"
    const val MIROSTAT_TAU = "mirostat_tau"
    const val MIROSTAT_ETA = "mirostat_eta"

    /** Keys sent as JSON integers; every other key is a float. */
    val INTEGER_KEYS: Set<String> = setOf(
        TOP_K, REPEAT_LAST_N, DRY_ALLOWED_LENGTH, DRY_PENALTY_LAST_N, MIROSTAT
    )

    /**
     * Samplers beyond the typed fields of `LlamaServerSamplingParams` / `LlamaChatRequest`
     * (temperature, top_p, top_k, min_p, repeat_penalty, presence/frequency_penalty, seed).
     */
    val EXTENDED_KEYS: List<String> = listOf(
        REPEAT_LAST_N, TYPICAL_P, TOP_N_SIGMA,
        XTC_PROBABILITY, XTC_THRESHOLD,
        DRY_MULTIPLIER, DRY_BASE, DRY_ALLOWED_LENGTH, DRY_PENALTY_LAST_N,
        DYNATEMP_RANGE, DYNATEMP_EXPONENT,
        MIROSTAT, MIROSTAT_TAU, MIROSTAT_ETA
    )

    /**
     * Extended samplers present in [params], typed for JSON (Int for [INTEGER_KEYS], else Float).
     * Absent or non-numeric values are Auto and are left out.
     */
    fun extendedFrom(params: Map<String, Any?>): Map<String, Number> = buildMap {
        EXTENDED_KEYS.forEach { key ->
            val value = params[key] as? Number ?: return@forEach
            put(key, if (key in INTEGER_KEYS) value.toInt() else value.toFloat())
        }
    }
}

/** Values LiteRT uses when a chat leaves its sampler on Auto (LiteRT needs concrete numbers). */
object LiteRtSamplerDefaults {
    const val TEMPERATURE = 1.0
    const val TOP_P = 0.95
    const val TOP_K = 40
}
