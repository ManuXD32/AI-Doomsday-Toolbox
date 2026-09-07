package com.example.llamadroid.sd

import org.json.JSONArray
import org.json.JSONObject

/**
 * File container formats understood by the SD artifact inspector.
 *
 * A format is deliberately not an architecture.  Both SafeTensors and GGUF can
 * contain a complete model or just one pipeline component.
 */
enum class SdArtifactFormat(val storedValue: String) {
    SAFETENSORS("safetensors"),
    GGUF("gguf"),
    CKPT("ckpt"),
    UNKNOWN("unknown");

    companion object {
        /** Compatibility spelling used by some importers. */
        val SAFE_TENSORS: SdArtifactFormat
            get() = SAFETENSORS

        fun fromStoredValue(value: String?): SdArtifactFormat =
            entries.firstOrNull {
                it.storedValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true) ||
                    (it == SAFETENSORS && value?.equals("safe_tensors", ignoreCase = true) == true)
            } ?: UNKNOWN
    }
}

/**
 * The semantic role of an artifact in a Stable Diffusion pipeline.
 *
 * MAIN_MODEL is retained as a compatibility spelling for callers which used
 * that name before full-vs-standalone layout was modelled explicitly.
 */
enum class SdArtifactRole(val storedValue: String) {
    FULL_MODEL("full_model"),
    MAIN_MODEL("main_model"),
    STANDALONE_DIFFUSION("standalone_diffusion"),
    VAE("vae"),
    TAE("tae"),
    CLIP_L("clip_l"),
    CLIP_G("clip_g"),
    T5XXL("t5xxl"),
    LLM("llm"),
    LLM_VISION("llm_vision"),
    LORA("lora"),
    CONTROLNET("controlnet"),
    UNKNOWN("unknown"),
    // Append video companion roles. UNKNOWN retains its historical ordinal.
    AUDIO_VAE("audio_vae"),
    EMBEDDINGS_CONNECTORS("embeddings_connectors"),
    MOTION_MODULE("motion_module");

    companion object {
        /** Compatibility spelling for the standalone diffusion role. */
        val DIFFUSION: SdArtifactRole
            get() = STANDALONE_DIFFUSION

        fun fromStoredValue(value: String?): SdArtifactRole =
            entries.firstOrNull {
                it.storedValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: UNKNOWN
    }
}

/** How the main artifact is packaged. */
enum class SdMainLayout(val storedValue: String) {
    FULL_MODEL("full_model"),
    STANDALONE_DIFFUSION("standalone_diffusion"),
    COMPONENT("component"),
    UNKNOWN("unknown");

    companion object {
        /** Compatibility spelling for callers that omit the model suffix. */
        val FULL: SdMainLayout
            get() = FULL_MODEL

        fun fromStoredValue(value: String?): SdMainLayout =
            entries.firstOrNull {
                it.storedValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: UNKNOWN
    }
}

/** Confidence in the structural classification, independent of user config. */
enum class SdInspectionConfidence(val storedValue: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    UNKNOWN("unknown");

    companion object {
        /** Alias for rows which have not yet been inspected. */
        val UNINSPECTED: SdInspectionConfidence
            get() = UNKNOWN

        fun fromStoredValue(value: String?): SdInspectionConfidence =
            entries.firstOrNull {
                it.storedValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: UNKNOWN
    }
}

/**
 * Versioned, payload-free facts collected from one SD artifact.
 *
 * The first fields intentionally follow the compact shape described in the
 * engineering report.  Additional fields are appended so callers can use
 * named arguments while older integrations remain source-compatible.
 */
data class SdArtifactInspection(
    val format: SdArtifactFormat = SdArtifactFormat.UNKNOWN,
    val detectedFamily: SdModelFamily? = null,
    val detectedRole: SdArtifactRole? = null,
    val containsDiffusion: Boolean = false,
    val containsVae: Boolean = false,
    val containsClipL: Boolean = false,
    val containsClipG: Boolean = false,
    val containsT5xxl: Boolean = false,
    val containsLlm: Boolean = false,
    /** Video-family components found in bounded tensor/header evidence. */
    val containsAudioVae: Boolean = false,
    val containsEmbeddingsConnectors: Boolean = false,
    val containsMotionModule: Boolean = false,
    /** Structural/metadata variant evidence; never inferred from filenames. */
    val detectedVariant: String? = null,
    val tensorCount: Long? = null,
    val confidence: SdInspectionConfidence = SdInspectionConfidence.UNKNOWN,
    val warnings: List<String> = emptyList(),
    /** Role selected by the user/import metadata; never replaces detectedRole. */
    val configuredRole: SdArtifactRole? = null,
    /** Canonical packaging/layout determined from tensor evidence. */
    val mainLayout: SdMainLayout = SdMainLayout.UNKNOWN,
    /** Optional alias accepted when reading older/newer persisted summaries. */
    val layout: SdMainLayout? = null,
    /** Digest of bounded header/metadata/tensor-descriptor bytes, never payload. */
    val headerFingerprint: String? = null,
    val inspectionVersion: Int = CURRENT_INSPECTION_VERSION,
    val fileSizeBytes: Long? = null,
    val modifiedAtMillis: Long? = null,
    /** Whether the bounded format header and descriptor table parsed cleanly. */
    val headerValid: Boolean = true,
    /** Small metadata subset useful for diagnostics; never tensor data. */
    val metadata: Map<String, String> = emptyMap(),
    /** Bounded set of representative tensor-name prefixes for expert diagnostics. */
    val tensorNamePrefixes: Set<String> = emptySet()
) {
    /** Canonical layout, accepting the persisted compatibility alias. */
    val artifactLayout: SdMainLayout
        get() = layout ?: mainLayout

    /** Alternate concise spelling used by a few callers. */
    val resolvedLayout: SdMainLayout
        get() = artifactLayout

    /** Alternate spelling for the bounded header digest. */
    val fingerprint: String?
        get() = headerFingerprint

    /** A video family uses the shared model-family storage namespace. */
    val detectedVideoFamily: SdVideoFamily?
        get() = detectedFamily?.toVideoFamily()

    val isInspected: Boolean
        get() = inspectionVersion > 0 && format != SdArtifactFormat.UNKNOWN && headerValid

    val isStructurallyUsable: Boolean
        get() = when (format) {
            SdArtifactFormat.CKPT -> headerValid && detectedRole == SdArtifactRole.FULL_MODEL
            SdArtifactFormat.SAFETENSORS, SdArtifactFormat.GGUF -> headerValid && tensorCount != null && tensorCount > 0L
            SdArtifactFormat.UNKNOWN -> false
        }

    fun toPersistedJson(): String = toJson()

    fun toJson(): String = JSONObject().apply {
        put("inspectionVersion", inspectionVersion)
        put("format", format.storedValue)
        put("detectedFamily", detectedFamily?.storedValue ?: JSONObject.NULL)
        put("detectedRole", detectedRole?.storedValue ?: JSONObject.NULL)
        put("configuredRole", configuredRole?.storedValue ?: JSONObject.NULL)
        put("layout", artifactLayout.storedValue)
        put("containsDiffusion", containsDiffusion)
        put("containsVae", containsVae)
        put("containsClipL", containsClipL)
        put("containsClipG", containsClipG)
        put("containsT5xxl", containsT5xxl)
        put("containsLlm", containsLlm)
        put("containsAudioVae", containsAudioVae)
        put("containsEmbeddingsConnectors", containsEmbeddingsConnectors)
        put("containsMotionModule", containsMotionModule)
        put("detectedVariant", detectedVariant ?: JSONObject.NULL)
        tensorCount?.let { put("tensorCount", it) } ?: put("tensorCount", JSONObject.NULL)
        put("confidence", confidence.storedValue)
        put("headerFingerprint", headerFingerprint ?: JSONObject.NULL)
        fileSizeBytes?.let { put("fileSizeBytes", it) } ?: put("fileSizeBytes", JSONObject.NULL)
        modifiedAtMillis?.let { put("modifiedAtMillis", it) } ?: put("modifiedAtMillis", JSONObject.NULL)
        put("headerValid", headerValid)
        put("warnings", JSONArray(warnings.take(MAX_PERSISTED_WARNINGS).map { it.take(MAX_WARNING_LENGTH) }))
        put("metadata", JSONObject().apply {
            metadata.entries
                .sortedBy { it.key }
                .take(MAX_PERSISTED_METADATA)
                .forEach { (key, value) -> put(key.take(MAX_METADATA_KEY_LENGTH), value.take(MAX_METADATA_VALUE_LENGTH)) }
        })
        put("tensorNamePrefixes", JSONArray(tensorNamePrefixes.toList().sorted().take(MAX_PERSISTED_PREFIXES)))
    }.toString()

    companion object {
        const val CURRENT_INSPECTION_VERSION: Int = 2
        const val MAX_PERSISTED_WARNINGS: Int = 32
        const val MAX_PERSISTED_METADATA: Int = 64
        const val MAX_PERSISTED_PREFIXES: Int = 64
        private const val MAX_WARNING_LENGTH = 256
        private const val MAX_METADATA_KEY_LENGTH = 128
        private const val MAX_METADATA_VALUE_LENGTH = 512

        fun uninspected(): SdArtifactInspection = SdArtifactInspection(
            inspectionVersion = 0,
            confidence = SdInspectionConfidence.UNKNOWN
        )

        fun fromPersistedJson(json: String?): SdArtifactInspection? = fromJson(json)

        fun fromJson(json: String?): SdArtifactInspection? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(json)
                val metadata = linkedMapOf<String, String>()
                root.optJSONObject("metadata")?.keys()?.forEach { key ->
                    val value = root.optJSONObject("metadata")?.optString(key, "") ?: ""
                    metadata[key.take(MAX_METADATA_KEY_LENGTH)] = value.take(MAX_METADATA_VALUE_LENGTH)
                }
                val warnings = mutableListOf<String>()
                root.optJSONArray("warnings")?.let { values ->
                    for (index in 0 until values.length().coerceAtMost(MAX_PERSISTED_WARNINGS)) {
                        values.optString(index).takeIf { it.isNotBlank() }?.let { warnings += boundedWarning(it) }
                    }
                }
                val prefixes = linkedSetOf<String>()
                root.optJSONArray("tensorNamePrefixes")?.let { values ->
                    for (index in 0 until values.length().coerceAtMost(MAX_PERSISTED_PREFIXES)) {
                        values.optString(index).takeIf { it.isNotBlank() }?.let { prefixes += it.take(MAX_METADATA_VALUE_LENGTH) }
                    }
                }
                val layout = SdMainLayout.fromStoredValue(root.optString("layout", null))
                SdArtifactInspection(
                    format = SdArtifactFormat.fromStoredValue(root.optString("format", null)),
                    detectedFamily = SdModelFamily.fromStoredValue(root.optString("detectedFamily", null)),
                    detectedRole = root.optString("detectedRole", null)
                        ?.let { SdArtifactRole.fromStoredValue(it) }
                        ?.takeUnless { it == SdArtifactRole.UNKNOWN },
                    containsDiffusion = root.optBoolean("containsDiffusion", false),
                    containsVae = root.optBoolean("containsVae", false),
                    containsClipL = root.optBoolean("containsClipL", false),
                    containsClipG = root.optBoolean("containsClipG", false),
                    containsT5xxl = root.optBoolean("containsT5xxl", false),
                    containsLlm = root.optBoolean("containsLlm", false),
                    containsAudioVae = root.optBoolean("containsAudioVae", false),
                    containsEmbeddingsConnectors = root.optBoolean("containsEmbeddingsConnectors", false),
                    containsMotionModule = root.optBoolean("containsMotionModule", false),
                    detectedVariant = root.optString("detectedVariant", null)?.takeIf { it.isNotBlank() },
                    tensorCount = if (root.isNull("tensorCount")) null else root.optLong("tensorCount"),
                    confidence = SdInspectionConfidence.fromStoredValue(root.optString("confidence", null)),
                    warnings = warnings,
                    configuredRole = root.optString("configuredRole", null)
                        ?.let { SdArtifactRole.fromStoredValue(it) }
                        ?.takeUnless { it == SdArtifactRole.UNKNOWN },
                    mainLayout = layout,
                    headerFingerprint = root.optString("headerFingerprint", null)?.takeIf { it.isNotBlank() },
                    inspectionVersion = root.optInt("inspectionVersion", 0).coerceAtLeast(0),
                    fileSizeBytes = if (root.isNull("fileSizeBytes")) null else root.optLong("fileSizeBytes"),
                    modifiedAtMillis = if (root.isNull("modifiedAtMillis")) null else root.optLong("modifiedAtMillis"),
                    headerValid = root.optBoolean("headerValid", true),
                    metadata = metadata,
                    tensorNamePrefixes = prefixes
                )
            }.getOrNull()
        }

        private fun boundedWarning(value: String): String = value.take(MAX_WARNING_LENGTH)
    }
}

/** Convert persisted inspection facts back to a compact model-row update. */
fun com.example.llamadroid.data.db.ModelEntity.withSdArtifactInspection(
    inspection: SdArtifactInspection
): com.example.llamadroid.data.db.ModelEntity = copy(
    sdDetectedFamily = inspection.detectedFamily?.storedValue,
    sdDetectedRole = inspection.detectedRole?.storedValue,
    // Keep an explicit edited variant authoritative, while allowing a
    // structurally detected video variant to participate in selectors.
    sdVariant = sdVariant ?: inspection.detectedVariant,
    sdArtifactLayout = inspection.artifactLayout.storedValue.takeUnless { it == SdMainLayout.UNKNOWN.storedValue },
    sdInspectionConfidence = inspection.confidence.storedValue,
    sdInspectionVersion = inspection.inspectionVersion,
    sdInspectionJson = inspection.toPersistedJson()
)

/** Read the cached structural facts on a model row, if a valid summary exists. */
fun com.example.llamadroid.data.db.ModelEntity.sdArtifactInspection(): SdArtifactInspection? =
    SdArtifactInspection.fromPersistedJson(sdInspectionJson)
