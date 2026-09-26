package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.isStableAudioComponentType
import com.example.llamadroid.data.model.library.ModelClassificationPolicy
import org.json.JSONObject

/** Whitelist of portable runtime choices: weights, paths, credentials and private input never enter it. */
object PortableModelMetadata {
    private val stringKeys = setOf("modelType", "sdCapabilities", "sdFamily", "sdVariant", "sdCompatProfiles",
        "onnxCapabilities", "onnxAssetKind", "onnxPipelineFamily", "liteRtBackend", "liteRtProfile", "whisperVariant",
        "audioFamily", "audioComponentRole", "audioLanguage", "audioArtifactIdentity",
        "classificationSource", "detectedClassificationJson",
        // Stable Audio uses appended model rows alongside the LiteRT runtime;
        // its bundle items still need portable, role-specific metadata.
        "stableAudioFamily", "stableAudioRole", "stableAudioComponentRole", "stableAudioVersion")
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
        put("classificationSource", model.classificationSource)
        model.detectedClassificationJson?.let { put("detectedClassificationJson", it) }
        (AudioModelSupport.descriptorForModel(model)
            ?: AudioModelSupport.descriptorForDownload(model.type, model.repoId, model.filename))?.let { descriptor ->
            if (model.type.isStableAudioComponentType()) {
                put("stableAudioFamily", descriptor.family)
                put("stableAudioComponentRole", descriptor.role)
            } else {
                put("audioFamily", descriptor.family)
                put("audioComponentRole", descriptor.role)
                descriptor.language?.let { put("audioLanguage", it) }
            }
            model.audioArtifactIdentity?.let { put("audioArtifactIdentity", it) }
        }
    }.toString())

    /** Adds explicit audio provenance for custom compatible imports. */
    fun fromAudioModel(
        model: ModelEntity,
        family: String,
        role: String,
        language: String? = null,
        artifactIdentity: String? = null
    ): String = sanitize(JSONObject(fromModel(model)).apply {
        put("audioFamily", family)
        put("audioComponentRole", role)
        language?.let { put("audioLanguage", it) }
        artifactIdentity?.let { put("audioArtifactIdentity", it) }
    }.toString())

    /** Portable role contract for Stable Audio LiteRT bundle components. */
    fun fromStableAudioComponent(
        family: String,
        role: String,
        version: String? = null
    ): String = sanitize(JSONObject().apply {
        put("stableAudioFamily", family)
        put("stableAudioRole", StableAudioModelSupport.canonicalRole(role) ?: role)
        put("stableAudioComponentRole", StableAudioModelSupport.canonicalRole(role) ?: role)
        version?.takeIf { it.isNotBlank() }?.let { put("stableAudioVersion", it) }
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
        put("classificationSource", model.classificationSource)
        model.detectedClassificationJson?.let { put("detectedClassificationJson", it) }
    }.toString())

    fun sanitize(raw: String?): String {
        if (raw == null || raw.length > 32_768) return "{}"
        val source = runCatching { JSONObject(raw) }.getOrNull() ?: return "{}"
        return JSONObject().apply {
            stringKeys.forEach { key ->
                val value = source.opt(key) as? String
                val maxLength = if (key == "detectedClassificationJson") {
                    ModelClassificationPolicy.MAX_DETECTED_EVIDENCE_LENGTH
                } else {
                    4096
                }
                if (value != null && value.length <= maxLength && !value.startsWith("/") &&
                    !value.contains("://") && !value.contains('\\')) put(key, value)
            }
            booleanKeys.forEach { key -> (source.opt(key) as? Boolean)?.let { put(key, it) } }
            (source.opt("maxContextTokens") as? Number)?.toLong()?.takeIf { it in 1L..16_777_216L }
                ?.let { put("maxContextTokens", it.toInt()) }
        }.toString()
    }
}
