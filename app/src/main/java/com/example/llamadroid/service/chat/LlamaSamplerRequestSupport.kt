package com.example.llamadroid.service.chat

import com.example.llamadroid.data.api.LlamaChatRequest

/**
 * Copies the extended llama.cpp samplers present in [params] onto the request. Absent keys stay
 * null (Auto) and are omitted from the JSON body because the request Gson never serializes nulls.
 */
internal fun LlamaChatRequest.withExtendedSamplers(params: Map<String, Any?>): LlamaChatRequest {
    val ext = LlamaSamplerKeys.extendedFrom(params)
    if (ext.isEmpty()) return this
    fun int(key: String): Int? = ext[key]?.toInt()
    fun float(key: String): Float? = ext[key]?.toFloat()
    return copy(
        repeat_last_n = int(LlamaSamplerKeys.REPEAT_LAST_N),
        typical_p = float(LlamaSamplerKeys.TYPICAL_P),
        top_n_sigma = float(LlamaSamplerKeys.TOP_N_SIGMA),
        xtc_probability = float(LlamaSamplerKeys.XTC_PROBABILITY),
        xtc_threshold = float(LlamaSamplerKeys.XTC_THRESHOLD),
        dry_multiplier = float(LlamaSamplerKeys.DRY_MULTIPLIER),
        dry_base = float(LlamaSamplerKeys.DRY_BASE),
        dry_allowed_length = int(LlamaSamplerKeys.DRY_ALLOWED_LENGTH),
        dry_penalty_last_n = int(LlamaSamplerKeys.DRY_PENALTY_LAST_N),
        dynatemp_range = float(LlamaSamplerKeys.DYNATEMP_RANGE),
        dynatemp_exponent = float(LlamaSamplerKeys.DYNATEMP_EXPONENT),
        mirostat = int(LlamaSamplerKeys.MIROSTAT),
        mirostat_tau = float(LlamaSamplerKeys.MIROSTAT_TAU),
        mirostat_eta = float(LlamaSamplerKeys.MIROSTAT_ETA)
    )
}
