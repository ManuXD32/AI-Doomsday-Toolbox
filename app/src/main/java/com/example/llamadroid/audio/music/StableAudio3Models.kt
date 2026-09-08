package com.example.llamadroid.audio.music

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil

/** Stable Audio 3 identifiers used by the audio workspace and model catalog. */
object StableAudio3Ids {
    const val ADAPTER_ID = "stable_audio_litert"
    const val FAMILY_MUSIC = "stable_audio_music"
    const val FAMILY_SFX = "stable_audio_sfx"
    const val SOURCE_REPOSITORY = "stabilityai/stable-audio-3-optimized"
    /** Hugging Face artifact revision; distinct from the inference implementation. */
    const val SOURCE_REVISION = "da6edc54ddba10bfd79a077102ded687f80e882b"
    const val UPSTREAM_REVISION = "779434a908193105335fd8d833418603625b2859"
    const val LICENSE = "stability-ai-community"
    const val SAMPLE_RATE = 44_100
    const val SAMPLES_PER_LATENT = 4_096
    const val CONDITIONING_TOKENS = 256
    const val CONDITIONING_DIMENSION = 768
    const val SMALL_MAX_SECONDS = 120.0
}

enum class StableAudio3Kind(val wireValue: String, val family: String) {
    MUSIC("sm-music", StableAudio3Ids.FAMILY_MUSIC),
    SFX("sm-sfx", StableAudio3Ids.FAMILY_SFX);

    companion object {
        fun fromWire(value: String?): StableAudio3Kind = entries.firstOrNull {
            it.wireValue.equals(value?.trim(), ignoreCase = true) ||
                it.name.equals(value?.trim(), ignoreCase = true)
        } ?: throw StableAudio3ValidationException("Unknown Stable Audio model kind")
    }
}

/** Extension uses audio conditioning and an end mask in the same generation graph. */
enum class StableAudio3Operation(val wireValue: String) {
    GENERATE("generate"),
    REMIX("remix"),
    INPAINT("inpaint"),
    EXTEND("extend");

    companion object {
        fun fromWire(value: String?): StableAudio3Operation = entries.firstOrNull {
            it.wireValue.equals(value?.trim(), ignoreCase = true) ||
                it.name.equals(value?.trim(), ignoreCase = true)
        } ?: throw StableAudio3ValidationException("Unknown Stable Audio operation")
    }
}

enum class StableAudio3DitPrecision(val wireValue: String, val supportsLora: Boolean) {
    FP32("fp32", true),
    W16A32("w16a32", true),
    W8A32("w8a32", false),
    W8A8_DYNAMIC("w8a8-dyn", false);

    companion object {
        fun fromWire(value: String?): StableAudio3DitPrecision = entries.firstOrNull {
            it.wireValue.equals(value?.trim(), ignoreCase = true) ||
                it.name.equals(value?.trim(), ignoreCase = true)
        } ?: throw StableAudio3ValidationException("Unknown Stable Audio DiT precision")
    }
}

enum class StableAudio3CodecPrecision(val wireValue: String) {
    FP32("fp32"),
    W8A8("w8a8");

    companion object {
        fun fromWire(value: String?): StableAudio3CodecPrecision = entries.firstOrNull {
            it.wireValue.equals(value?.trim(), ignoreCase = true) ||
                it.name.equals(value?.trim(), ignoreCase = true)
        } ?: throw StableAudio3ValidationException("Unknown Stable Audio codec precision")
    }
}

data class StableAudio3ComponentRef(
    val path: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val sourceIdentity: String? = null
) {
    fun normalized(): StableAudio3ComponentRef = copy(
        path = path.trim(),
        sha256 = sha256?.trim()?.lowercase(Locale.US)?.removePrefix("sha256:")
            ?.takeIf { it.isNotBlank() },
        sizeBytes = sizeBytes?.takeIf { it > 0L },
        sourceIdentity = sourceIdentity?.trim()?.takeIf { it.isNotBlank() }
    )

    fun validate(role: String, requireDigest: Boolean = false): StableAudio3ComponentRef {
        val value = normalized()
        require(value.path.isNotBlank()) { "$role component path is required" }
        if (requireDigest) {
            require(value.sha256?.matches(SHA256_PATTERN) == true) {
                "$role component digest is required"
            }
        } else if (value.sha256 != null) {
            require(value.sha256.matches(SHA256_PATTERN)) {
                "$role component digest is malformed"
            }
        }
        value.sizeBytes?.let { require(it > 0L) { "$role component size must be positive" } }
        return value
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        sha256?.let { put("sha256", it) }
        sizeBytes?.let { put("sizeBytes", it) }
        sourceIdentity?.let { put("sourceIdentity", it) }
    }

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun fromJson(value: JSONObject?): StableAudio3ComponentRef? {
            if (value == null || value == JSONObject.NULL) return null
            return StableAudio3ComponentRef(
                path = value.optString("path"),
                sha256 = value.optString("sha256").takeIf { it.isNotBlank() },
                sizeBytes = value.optLong("sizeBytes").takeIf { it > 0L },
                sourceIdentity = value.optString("sourceIdentity").takeIf { it.isNotBlank() }
            )
        }
    }
}

/** Paths are explicit because a DiT and SAME codec may be shared by bundles. */
data class StableAudio3Components(
    val tokenizer: StableAudio3ComponentRef,
    val textEncoder: StableAudio3ComponentRef,
    val dit: StableAudio3ComponentRef,
    val codecDecoder: StableAudio3ComponentRef,
    val codecEncoder: StableAudio3ComponentRef? = null
) {
    fun normalized(): StableAudio3Components = copy(
        tokenizer = tokenizer.normalized(),
        textEncoder = textEncoder.normalized(),
        dit = dit.normalized(),
        codecDecoder = codecDecoder.normalized(),
        codecEncoder = codecEncoder?.normalized()
    )

    fun validate(requireEncoder: Boolean, requireDigests: Boolean = false): StableAudio3Components {
        val value = normalized()
        value.tokenizer.validate("tokenizer", requireDigests)
        value.textEncoder.validate("text encoder", requireDigests)
        value.dit.validate("DiT", requireDigests)
        value.codecDecoder.validate("codec decoder", requireDigests)
        if (requireEncoder) {
            require(value.codecEncoder != null) { "codec encoder is required for this operation" }
        }
        value.codecEncoder?.validate("codec encoder", requireDigests)
        return value
    }

    fun all(): List<Pair<String, StableAudio3ComponentRef>> = buildList {
        add("tokenizer" to tokenizer)
        add("textEncoder" to textEncoder)
        add("dit" to dit)
        add("codecDecoder" to codecDecoder)
        codecEncoder?.let { add("codecEncoder" to it) }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("tokenizer", tokenizer.toJson())
        put("textEncoder", textEncoder.toJson())
        put("dit", dit.toJson())
        put("codecDecoder", codecDecoder.toJson())
        codecEncoder?.let { put("codecEncoder", it.toJson()) }
    }

    companion object {
        fun fromJson(value: JSONObject): StableAudio3Components = StableAudio3Components(
            tokenizer = StableAudio3ComponentRef.fromJson(value.optJSONObject("tokenizer"))
                ?: throw StableAudio3ValidationException("tokenizer component is missing"),
            textEncoder = StableAudio3ComponentRef.fromJson(value.optJSONObject("textEncoder"))
                ?: throw StableAudio3ValidationException("text encoder component is missing"),
            dit = StableAudio3ComponentRef.fromJson(value.optJSONObject("dit"))
                ?: throw StableAudio3ValidationException("DiT component is missing"),
            codecDecoder = StableAudio3ComponentRef.fromJson(value.optJSONObject("codecDecoder"))
                ?: throw StableAudio3ValidationException("codec decoder component is missing"),
            codecEncoder = StableAudio3ComponentRef.fromJson(value.optJSONObject("codecEncoder"))
        )
    }
}

data class StableAudio3Lora(
    val path: String,
    val strength: Float = 1.0f
) {
    fun normalized(): StableAudio3Lora = copy(
        path = path.trim(),
        strength = strength.coerceIn(-8.0f, 8.0f)
    )

    fun validate(): StableAudio3Lora {
        val value = normalized()
        require(value.path.isNotBlank()) { "LoRA path is required" }
        require(value.strength.isFinite()) { "LoRA strength must be finite" }
        return value
    }

    fun toJson(): JSONObject = JSONObject().put("path", path).put("strength", strength.toDouble())

    companion object {
        fun fromJson(value: JSONObject): StableAudio3Lora = StableAudio3Lora(
            path = value.optString("path"),
            strength = value.optDouble("strength", 1.0).toFloat()
        )
    }
}

/** Persisted job payload consumed by the isolated worker. */
data class StableAudio3Request(
    val kind: StableAudio3Kind,
    val operation: StableAudio3Operation,
    val components: StableAudio3Components,
    val prompt: String,
    val negativePrompt: String? = null,
    val durationSeconds: Double = 5.0,
    val steps: Int = 8,
    val seed: Long = 0L,
    val initNoiseLevel: Float = 1.0f,
    val cfg: Float = 1.0f,
    val apg: Float = 1.0f,
    val cfgBatched: Boolean = true,
    val initAudioPath: String? = null,
    val maskStartSeconds: Double? = null,
    val maskEndSeconds: Double? = null,
    val threads: Int = 4,
    val freeModels: Boolean = true,
    val ditPrecision: StableAudio3DitPrecision = StableAudio3DitPrecision.FP32,
    val decoderPrecision: StableAudio3CodecPrecision = StableAudio3CodecPrecision.W8A8,
    val encoderPrecision: StableAudio3CodecPrecision = StableAudio3CodecPrecision.W8A8,
    val maxRung: Int? = null,
    val loras: List<StableAudio3Lora> = emptyList(),
    val outputPath: String
) {
    fun normalized(): StableAudio3Request = copy(
        prompt = prompt.trim(),
        negativePrompt = negativePrompt?.trim()?.takeIf { it.isNotBlank() },
        durationSeconds = durationSeconds,
        steps = steps,
        initAudioPath = initAudioPath?.trim()?.takeIf { it.isNotBlank() },
        maskStartSeconds = maskStartSeconds,
        maskEndSeconds = if (operation == StableAudio3Operation.EXTEND &&
            maskStartSeconds != null && maskEndSeconds == null
        ) durationSeconds else maskEndSeconds,
        threads = threads,
        maxRung = maxRung,
        loras = loras.map { it.normalized() },
        outputPath = outputPath.trim(),
        components = components.normalized()
    )

    /** Validate user input and graph requirements before starting a worker. */
    fun validate(requireDigests: Boolean = false, supportsExtend: Boolean = false): StableAudio3Request {
        val value = normalized()
        // The upstream baked conditioner intentionally supports an empty
        // prompt for unconditional generation and CFG's learned padding
        // branch. Keep that valid instead of forcing a text-only mode.
        require(value.durationSeconds.isFinite() && value.durationSeconds > 0.0) {
            "Stable Audio duration must be positive"
        }
        require(value.durationSeconds <= StableAudio3Ids.SMALL_MAX_SECONDS) {
            "Stable Audio small models support up to ${StableAudio3Ids.SMALL_MAX_SECONDS.toInt()} seconds"
        }
        require(value.steps in 1..64) { "Stable Audio steps must be between 1 and 64" }
        require(value.initNoiseLevel.isFinite() && value.initNoiseLevel >= 0.01f && value.initNoiseLevel <= 2.0f) {
            "Stable Audio noise level must be between 0.01 and 2.0"
        }
        require(value.cfg.isFinite() && value.cfg in 0.0f..20.0f) {
            "Stable Audio CFG must be between 0 and 20"
        }
        require(value.apg.isFinite() && value.apg in 0.0f..1.0f) {
            "Stable Audio APG must be between 0 and 1"
        }
        require(value.threads in 1..256) { "Stable Audio thread count must be between 1 and 256" }
        value.maxRung?.let { require(it > 0) { "Stable Audio max rung must be positive" } }
        require(value.outputPath.isNotBlank()) { "Stable Audio output path is required" }
        require(!value.outputPath.contains('\u0000')) { "Stable Audio output path is malformed" }

        val needsInput = value.operation == StableAudio3Operation.REMIX ||
            value.operation == StableAudio3Operation.INPAINT ||
            value.operation == StableAudio3Operation.EXTEND
        require(!needsInput || !value.initAudioPath.isNullOrBlank()) {
            "This Stable Audio operation requires an input recording"
        }
        require(value.operation != StableAudio3Operation.GENERATE || value.maskStartSeconds == null && value.maskEndSeconds == null) {
            "An inpaint range requires the inpaint operation"
        }
        if (value.operation == StableAudio3Operation.INPAINT) {
            val start = value.maskStartSeconds
            val end = value.maskEndSeconds
            require(start != null && end != null && start.isFinite() && end.isFinite()) {
                "Inpaint start and end are required"
            }
            require(start >= 0.0 && start < end && end <= value.durationSeconds) {
                "Inpaint range must be inside the requested duration"
            }
        } else if (value.operation == StableAudio3Operation.EXTEND &&
            (value.maskStartSeconds != null || value.maskEndSeconds != null)
        ) {
            val start = value.maskStartSeconds
            val end = value.maskEndSeconds
            require(start != null && end != null && start.isFinite() && end.isFinite()) {
                "Extension mask start and end must be provided together"
            }
            require(start >= 0.0 && start < end && end <= value.durationSeconds) {
                "Extension mask must start at the source end and finish inside the requested duration"
            }
        } else {
            require(value.maskStartSeconds == null && value.maskEndSeconds == null) {
                "Mask range is only supported by inpaint or extension"
            }
        }
        if (value.operation == StableAudio3Operation.EXTEND) {
            require(supportsExtend) {
                "Stable Audio continuation is unavailable for the installed graph"
            }
            if (value.maskStartSeconds != null) {
                require(value.maskStartSeconds > 0.0) {
                    "Extension mask start must be the end of the source recording"
                }
            }
        }
        if (value.loras.isNotEmpty()) {
            require(value.ditPrecision.supportsLora) {
                "Stable Audio LoRA requires fp32 or w16a32 DiT precision"
            }
            value.loras.forEach { it.validate() }
        }
        value.components.validate(requireEncoder = needsInput, requireDigests = requireDigests)
        return value
    }

    /** Latent length used by the upstream variable-length graphs. */
    fun latentLength(): Int = maxOf(
        1,
        ceil(durationSeconds * StableAudio3Ids.SAMPLE_RATE.toDouble() /
            StableAudio3Ids.SAMPLES_PER_LATENT).toInt()
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.wireValue)
        put("operation", operation.wireValue)
        put("components", components.toJson())
        put("prompt", prompt)
        negativePrompt?.let { put("negativePrompt", it) }
        put("durationSeconds", durationSeconds)
        put("steps", steps)
        put("seed", seed)
        put("initNoiseLevel", initNoiseLevel.toDouble())
        put("cfg", cfg.toDouble())
        put("apg", apg.toDouble())
        put("cfgBatched", cfgBatched)
        initAudioPath?.let { put("initAudioPath", it) }
        maskStartSeconds?.let { put("maskStartSeconds", it) }
        maskEndSeconds?.let { put("maskEndSeconds", it) }
        put("threads", threads)
        put("freeModels", freeModels)
        put("ditPrecision", ditPrecision.wireValue)
        put("decoderPrecision", decoderPrecision.wireValue)
        put("encoderPrecision", encoderPrecision.wireValue)
        maxRung?.let { put("maxRung", it) }
        put("loras", JSONArray(loras.map { it.toJson() }))
        put("outputPath", outputPath)
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun fromJson(value: JSONObject): StableAudio3Request = StableAudio3Request(
            kind = StableAudio3Kind.fromWire(value.optString("kind")),
            operation = StableAudio3Operation.fromWire(value.optString("operation")),
            components = StableAudio3Components.fromJson(value.optJSONObject("components")
                ?: throw StableAudio3ValidationException("Stable Audio components are missing")),
            prompt = value.optString("prompt"),
            negativePrompt = value.optString("negativePrompt").takeIf { it.isNotBlank() },
            durationSeconds = value.optDouble("durationSeconds", 5.0),
            steps = value.optInt("steps", 8),
            seed = value.optLong("seed", 0L),
            initNoiseLevel = value.optDouble("initNoiseLevel", 1.0).toFloat(),
            cfg = value.optDouble("cfg", 1.0).toFloat(),
            apg = value.optDouble("apg", 1.0).toFloat(),
            cfgBatched = value.optBoolean("cfgBatched", true),
            initAudioPath = value.optString("initAudioPath").takeIf { it.isNotBlank() },
            maskStartSeconds = value.optDouble("maskStartSeconds").takeUnless { it.isNaN() },
            maskEndSeconds = value.optDouble("maskEndSeconds").takeUnless { it.isNaN() },
            threads = value.optInt("threads", 4),
            freeModels = value.optBoolean("freeModels", true),
            ditPrecision = StableAudio3DitPrecision.fromWire(value.optString("ditPrecision", "fp32")),
            decoderPrecision = StableAudio3CodecPrecision.fromWire(value.optString("decoderPrecision", "w8a8")),
            encoderPrecision = StableAudio3CodecPrecision.fromWire(value.optString("encoderPrecision", "w8a8")),
            maxRung = value.optInt("maxRung").takeIf { it > 0 },
            loras = buildList {
                val array = value.optJSONArray("loras") ?: return@buildList
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(StableAudio3Lora.fromJson(it)) }
                }
            },
            outputPath = value.optString("outputPath")
        )

        fun fromJsonString(value: String): StableAudio3Request =
            fromJson(JSONObject(value))
    }
}

data class StableAudio3Progress(
    val stage: String,
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val fraction: Float? = null
)

data class StableAudio3Result(
    val outputPath: String,
    val sampleRate: Int = StableAudio3Ids.SAMPLE_RATE,
    val channels: Int = 2,
    val durationMs: Long,
    val latentLength: Int,
    val metadataJson: String = "{}"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("outputPath", outputPath)
        put("sampleRate", sampleRate)
        put("channels", channels)
        put("durationMs", durationMs)
        put("latentLength", latentLength)
        put("metadataJson", metadataJson)
    }

    companion object {
        fun fromJson(value: JSONObject): StableAudio3Result = StableAudio3Result(
            outputPath = value.optString("outputPath"),
            sampleRate = value.optInt("sampleRate", StableAudio3Ids.SAMPLE_RATE),
            channels = value.optInt("channels", 2),
            durationMs = value.optLong("durationMs", 0L),
            latentLength = value.optInt("latentLength", 0),
            metadataJson = value.optString("metadataJson", "{}")
        )
    }
}

class StableAudio3ValidationException(message: String) : IllegalArgumentException(message)
