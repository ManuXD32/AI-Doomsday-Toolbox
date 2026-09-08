package com.example.llamadroid.audio.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class StableAudio3OperationCapability(
    val operation: StableAudio3Operation,
    val requiresInputAudio: Boolean,
    val supports: Boolean,
    val reason: String? = null
)

data class StableAudio3Capabilities(
    val kind: StableAudio3Kind,
    val operations: List<StableAudio3OperationCapability>,
    val supportsNegativePrompt: Boolean = true,
    val supportsCfg: Boolean = true,
    val supportsApg: Boolean = true,
    val supportsCfgBatching: Boolean = true,
    val supportsLora: Boolean = true,
    val loraPrecisions: Set<StableAudio3DitPrecision> = setOf(
        StableAudio3DitPrecision.FP32,
        StableAudio3DitPrecision.W16A32
    ),
    val supportedLanguages: List<String> = listOf("en"),
    val sampleRate: Int = StableAudio3Ids.SAMPLE_RATE,
    val maxSeconds: Double = StableAudio3Ids.SMALL_MAX_SECONDS
) {
    fun operation(operation: StableAudio3Operation): StableAudio3OperationCapability =
        operations.firstOrNull { it.operation == operation }
            ?: StableAudio3OperationCapability(operation, false, false, "operation_unknown")

    fun supports(request: StableAudio3Request): Boolean =
        request.kind == kind && operation(request.operation).supports &&
            (request.loras.isEmpty() || request.ditPrecision in loraPrecisions)
}

data class StableAudio3ManifestComponent(
    val role: String,
    val remotePath: String,
    val localFileName: String,
    val sha256: String?,
    val sizeBytes: Long?,
    val license: String,
    val sourceRevision: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("remotePath", remotePath)
        put("localFileName", localFileName)
        if (sha256 == null) put("sha256", JSONObject.NULL) else put("sha256", sha256)
        if (sizeBytes == null) put("sizeBytes", JSONObject.NULL) else put("sizeBytes", sizeBytes)
        put("license", license)
        put("sourceRevision", sourceRevision)
    }
}

data class StableAudio3ManifestEntry(
    val id: String,
    val kind: StableAudio3Kind,
    val ditPrecision: StableAudio3DitPrecision,
    val decoderPrecision: StableAudio3CodecPrecision,
    val encoderPrecision: StableAudio3CodecPrecision,
    val decoder: String,
    val components: List<StableAudio3ManifestComponent>,
    val supportsExtend: Boolean = false,
    val minRuntimeBytes: Long = 0L
) {
    val hasVerifiedDigests: Boolean
        get() = components.isNotEmpty() && components.all {
            it.sha256?.matches(Regex("[0-9a-f]{64}")) == true
        }

    /** Curated downloads must remain disabled until every payload is pinned. */
    val downloadEligible: Boolean
        get() = hasVerifiedDigests && components.all { it.sizeBytes != null && it.sizeBytes > 0L }

    fun capabilities(): StableAudio3Capabilities = StableAudio3Capabilities(
        kind = kind,
        operations = listOf(
            StableAudio3OperationCapability(StableAudio3Operation.GENERATE, false, true),
            StableAudio3OperationCapability(StableAudio3Operation.REMIX, true, true),
            StableAudio3OperationCapability(StableAudio3Operation.INPAINT, true, true),
            StableAudio3OperationCapability(
                StableAudio3Operation.EXTEND,
                true,
                supportsExtend,
                if (supportsExtend) "inpaint_continuation_mask" else "continuation_mask_unavailable"
            )
        ),
        supportsLora = ditPrecision in setOf(
            StableAudio3DitPrecision.FP32,
            StableAudio3DitPrecision.W16A32
        ),
        loraPrecisions = setOf(
            StableAudio3DitPrecision.FP32,
            StableAudio3DitPrecision.W16A32
        )
    )

    fun component(role: String): StableAudio3ManifestComponent? =
        components.firstOrNull { it.role == role }
}

data class StableAudio3ComponentManifest(
    val schemaVersion: Int,
    val repository: String,
    val revision: String,
    val license: String,
    val entries: List<StableAudio3ManifestEntry>
) {
    fun entry(
        kind: StableAudio3Kind,
        ditPrecision: StableAudio3DitPrecision,
        decoderPrecision: StableAudio3CodecPrecision,
        encoderPrecision: StableAudio3CodecPrecision
    ): StableAudio3ManifestEntry? = entries.firstOrNull {
        it.kind == kind && it.ditPrecision == ditPrecision &&
            it.decoderPrecision == decoderPrecision && it.encoderPrecision == encoderPrecision
    }

    fun verifiedEntries(): List<StableAudio3ManifestEntry> = entries.filter { it.downloadEligible }
}

object StableAudio3ManifestLoader {
    const val ASSET_NAME = "stable_audio3_components.json"

    fun load(context: Context): StableAudio3ComponentManifest {
        val json = try {
            context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: IOException) {
            throw IllegalStateException("Stable Audio component manifest is unavailable", error)
        }
        return parse(json)
    }

    fun parse(raw: String): StableAudio3ComponentManifest {
        val root = JSONObject(raw)
        val components = root.optJSONArray("entries") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until components.length()) {
                val item = components.optJSONObject(index) ?: continue
                val files = item.optJSONArray("components") ?: JSONArray()
                val parsedFiles = buildList {
                    for (fileIndex in 0 until files.length()) {
                        val file = files.optJSONObject(fileIndex) ?: continue
                        add(
                            StableAudio3ManifestComponent(
                                role = file.optString("role"),
                                remotePath = file.optString("remotePath"),
                                localFileName = file.optString("localFileName"),
                                sha256 = file.optString("sha256")
                                    .takeIf { it.isNotBlank() && it != "null" },
                                sizeBytes = file.optLong("sizeBytes")
                                    .takeIf { it > 0L },
                                license = file.optString("license", root.optString("license")),
                                sourceRevision = file.optString("sourceRevision", root.optString("revision"))
                            )
                        )
                    }
                }
                add(
                    StableAudio3ManifestEntry(
                        id = item.optString("id"),
                        kind = StableAudio3Kind.fromWire(item.optString("kind")),
                        ditPrecision = StableAudio3DitPrecision.fromWire(item.optString("ditPrecision")),
                        decoderPrecision = StableAudio3CodecPrecision.fromWire(item.optString("decoderPrecision")),
                        encoderPrecision = StableAudio3CodecPrecision.fromWire(item.optString("encoderPrecision")),
                        decoder = item.optString("decoder", "same-s"),
                        components = parsedFiles,
                        supportsExtend = item.optBoolean("supportsExtend", false),
                        minRuntimeBytes = item.optLong("minRuntimeBytes", 0L)
                    )
                )
            }
        }
        require(root.optInt("schemaVersion", 0) == 1) { "Unsupported Stable Audio manifest schema" }
        require(root.optString("repository") == StableAudio3Ids.SOURCE_REPOSITORY) {
            "Unexpected Stable Audio source repository"
        }
        require(root.optString("revision") == StableAudio3Ids.SOURCE_REVISION) {
            "Stable Audio manifest revision is not pinned"
        }
        return StableAudio3ComponentManifest(
            schemaVersion = 1,
            repository = root.optString("repository"),
            revision = root.optString("revision"),
            license = root.optString("license"),
            entries = entries
        )
    }
}
