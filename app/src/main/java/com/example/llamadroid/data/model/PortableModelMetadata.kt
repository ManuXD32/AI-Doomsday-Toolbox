package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelEntity
import org.json.JSONObject

/** Whitelist of portable runtime choices: weights, paths, credentials and private input never enter it. */
object PortableModelMetadata {
    private val stringKeys = setOf("modelType", "sdCapabilities", "sdFamily", "sdVariant", "sdCompatProfiles",
        "onnxCapabilities", "onnxAssetKind", "onnxPipelineFamily", "liteRtBackend", "liteRtProfile", "whisperVariant")
    private val booleanKeys = setOf("isVision", "supportsCpu", "supportsGpu", "supportsNpu",
        "supportsVision", "supportsAudio", "supportsEmbedding")

    fun fromModel(model: ModelEntity): String = sanitize(JSONObject().apply {
        put("modelType", model.type.name)
        put("isVision", model.isVision)
        put("sdCapabilities", model.sdCapabilities)
        put("sdFamily", model.sdFamily)
        put("sdVariant", model.sdVariant)
        put("sdCompatProfiles", model.sdCompatProfiles)
        put("onnxCapabilities", model.onnxCapabilities)
        put("onnxAssetKind", model.onnxAssetKind)
        put("onnxPipelineFamily", model.onnxPipelineFamily)
    }.toString())

    fun fromLiteRt(model: LiteRtModelEntity): String = sanitize(JSONObject().apply {
        put("liteRtBackend", model.backendPreference)
        put("supportsCpu", model.supportsCpu)
        put("supportsGpu", model.supportsGpu)
        put("supportsNpu", model.supportsNpu)
        put("supportsVision", model.supportsVision)
        put("supportsAudio", model.supportsAudio)
        put("supportsEmbedding", model.supportsEmbedding)
        put("maxContextTokens", model.maxContextTokens)
    }.toString())

    fun sanitize(raw: String?): String {
        if (raw == null || raw.length > 32_768) return "{}"
        val source = runCatching { JSONObject(raw) }.getOrNull() ?: return "{}"
        return JSONObject().apply {
            stringKeys.forEach { key ->
                val value = source.opt(key) as? String
                if (value != null && value.length <= 4096 && !value.startsWith("/") &&
                    !value.contains("://") && !value.contains('\\')) put(key, value)
            }
            booleanKeys.forEach { key -> (source.opt(key) as? Boolean)?.let { put(key, it) } }
            (source.opt("maxContextTokens") as? Number)?.toLong()?.takeIf { it in 1L..16_777_216L }
                ?.let { put("maxContextTokens", it.toInt()) }
        }.toString()
    }
}
