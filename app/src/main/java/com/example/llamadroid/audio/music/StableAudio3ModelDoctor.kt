package com.example.llamadroid.audio.music

import android.content.Context
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.StableAudioModelSupport
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/** The result of checking one installed Stable Audio component. */
enum class StableAudio3ComponentHealth {
    READY,
    ROLE_MISMATCH,
    NON_CANONICAL,
    MISSING,
    PARENT_UNAVAILABLE,
    UNREADABLE,
    SIZE_MISMATCH,
    DIGEST_MISMATCH
}

data class StableAudio3ComponentCheck(
    val role: String,
    val expectedFilename: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val actualSizeBytes: Long?,
    val actualSha256: String?,
    val readable: Boolean,
    val parentDirectoryAvailable: Boolean,
    val health: StableAudio3ComponentHealth,
    /** True when the bytes are usable but the user selected a non-curated row. */
    val classificationWarning: Boolean = false
) {
    val ready: Boolean get() = health == StableAudio3ComponentHealth.READY
}

data class StableAudio3ModelDoctorReport(
    val kind: StableAudio3Kind,
    val family: String,
    val role: String?,
    val check: StableAudio3ComponentCheck,
    val manifestRevision: String
) {
    val ready: Boolean get() = check.ready
}

data class StableAudio3RequestResolution(
    val request: StableAudio3Request,
    val warnings: List<String> = emptyList()
)

/**
 * Resolves the one pinned Stable Audio manifest entry and validates installed
 * rows against its exact filename, size, and digest. The runtime must consume
 * this resolver rather than trusting an arbitrary LiteRT row or basename.
 */
object StableAudio3ModelDoctor {
    fun entryFor(
        manifest: StableAudio3ComponentManifest,
        kind: StableAudio3Kind,
        ditPrecision: StableAudio3DitPrecision = StableAudio3DitPrecision.FP32,
        decoderPrecision: StableAudio3CodecPrecision = StableAudio3CodecPrecision.W8A8,
        encoderPrecision: StableAudio3CodecPrecision = StableAudio3CodecPrecision.W8A8
    ): StableAudio3ManifestEntry = manifest.entry(
        kind = kind,
        ditPrecision = ditPrecision,
        decoderPrecision = decoderPrecision,
        encoderPrecision = encoderPrecision
    ) ?: throw StableAudio3ValidationException(
        "Stable Audio curated graph is unavailable for ${kind.wireValue}"
    )

    /** Filename installed by StableAudioCuratedBundleCatalog for a manifest component. */
    fun canonicalFilename(kind: StableAudio3Kind, component: StableAudio3ManifestComponent): String {
        require(component.localFileName.isNotBlank()) { "Stable Audio component filename is missing" }
        require(component.sha256?.matches(Regex("[0-9a-f]{64}")) == true) {
            "Stable Audio component digest is not pinned"
        }
        require(component.sizeBytes != null && component.sizeBytes > 0L) {
            "Stable Audio component size is not pinned"
        }
        require(File(component.localFileName).name == component.localFileName) {
            "Stable Audio component filename is not canonical"
        }
        return if (StableAudioModelSupport.canonicalRole(component.role) == StableAudioModelSupport.ROLE_DIT) {
            "Stable-Audio-3-Small-${if (kind == StableAudio3Kind.MUSIC) "Music" else "SFX"}-${component.localFileName}"
        } else {
            "shared-${component.sha256.orEmpty().take(12)}-${component.localFileName}"
        }
    }

    fun canonicalComponent(
        kind: StableAudio3Kind,
        entry: StableAudio3ManifestEntry,
        role: String
    ): StableAudio3ManifestComponent {
        require(entry.kind == kind) { "Stable Audio manifest entry has the wrong family" }
        return entry.component(role)
            ?: throw StableAudio3ValidationException("Stable Audio ${roleLabel(role)} component is missing")
    }

    /** Returns true when durable row metadata identifies the exact curated artifact. */
    fun isCanonicalInstalledModel(
        kind: StableAudio3Kind,
        entry: StableAudio3ManifestEntry,
        component: StableAudio3ManifestComponent,
        model: ModelEntity
    ): Boolean {
        val payload = File(model.path)
        val canonicalPayload = runCatching { payload.canonicalFile }.getOrNull() ?: return false
        val canonicalRole = StableAudioModelSupport.canonicalRole(component.role) ?: component.role
        val expectedFamily = if (canonicalRole == StableAudioModelSupport.ROLE_DIT) {
            kind.family
        } else {
            StableAudioModelSupport.FAMILY_SHARED
        }
        return entry.kind == kind && entry.component(canonicalRole) == component &&
            model.isDownloaded &&
            model.repoId == StableAudio3Ids.SOURCE_REPOSITORY &&
            model.type == expectedType(canonicalRole) &&
            StableAudioModelSupport.canonicalRole(model.audioComponentRole) == canonicalRole &&
            model.audioFamily == expectedFamily &&
            model.filename == canonicalFilename(kind, component) &&
            model.sizeBytes == component.sizeBytes &&
            model.audioArtifactIdentity.equals("sha256:${component.sha256}", ignoreCase = true) &&
            payload.isFile &&
            payload.canRead() &&
            payload.length() == component.sizeBytes &&
            payload.name == model.filename &&
            canonicalPayload.path == payload.absolutePath &&
            canonicalPayload.name == model.filename
    }

    /** Resolves all components required by an operation from canonical rows. */
    fun resolveInstalledComponents(
        kind: StableAudio3Kind,
        entry: StableAudio3ManifestEntry,
        models: Collection<ModelEntity>,
        requireEncoder: Boolean
    ): StableAudio3Components {
        fun resolve(role: String): StableAudio3ComponentRef {
            val component = canonicalComponent(kind, entry, role)
            val model = models.firstOrNull { isCanonicalInstalledModel(kind, entry, component, it) }
                ?: throw StableAudio3ValidationException(
                    "Stable Audio ${roleLabel(role)} is stale or unavailable; re-download the curated component"
                )
            val canonicalPath = runCatching { File(model.path).canonicalPath }.getOrElse {
                throw StableAudio3ValidationException("Stable Audio ${roleLabel(role)} path is unavailable")
            }
            return StableAudio3ComponentRef(
                path = canonicalPath,
                sha256 = component.sha256,
                sizeBytes = component.sizeBytes,
                sourceIdentity = "sha256:${component.sha256}"
            )
        }
        return StableAudio3Components(
            tokenizer = resolve(StableAudioModelSupport.ROLE_TOKENIZER),
            textEncoder = resolve(StableAudioModelSupport.ROLE_TEXT_ENCODER),
            dit = resolve(StableAudioModelSupport.ROLE_DIT),
            codecDecoder = resolve(StableAudioModelSupport.ROLE_CODEC_DECODER),
            codecEncoder = if (requireEncoder) {
                resolve(StableAudioModelSupport.ROLE_CODEC_ENCODER)
            } else {
                null
            }
        )
    }

    /**
     * Canonicalizes a request received from persisted job metadata. This is
     * intentionally independent of Room so the isolated worker can fail closed
     * when an old job references a legacy row.
     */
    fun resolveRequest(context: Context, request: StableAudio3Request): StableAudio3Request =
        resolveRequestWithWarnings(context, request).request

    /**
     * Resolves curated references strictly, while permitting an explicitly
     * selected local component to be used with a bounded integrity warning.
     * Curated downloads are still verified by their catalog before reaching
     * this method; a manual row never inherits a curated digest.
     */
    fun resolveRequestWithWarnings(
        context: Context,
        request: StableAudio3Request
    ): StableAudio3RequestResolution {
        val manifest = StableAudio3ManifestLoader.load(context)
        val entry = entryFor(
            manifest = manifest,
            kind = request.kind,
            ditPrecision = request.ditPrecision,
            decoderPrecision = request.decoderPrecision,
            encoderPrecision = request.encoderPrecision
        )
        val warnings = mutableListOf<String>()
        val components = request.components.all().map { (role, ref) ->
            val canonicalRole = StableAudioModelSupport.canonicalRole(role)
                ?: throw StableAudio3ValidationException("Unknown Stable Audio component role")
            val expected = canonicalComponent(request.kind, entry, canonicalRole)
            val expectedName = canonicalFilename(request.kind, expected)
            val value = ref.normalized()
            checkCanonical(value.path.isNotBlank()) { "Stable Audio ${roleLabel(canonicalRole)} is missing" }
            val file = File(value.path)
            val canonicalFile = runCatching { file.canonicalFile }.getOrElse {
                throw StableAudio3ValidationException(
                    "Stable Audio ${roleLabel(canonicalRole)} path is unavailable"
                )
            }
            val canonicalPath = canonicalFile.path == file.absolutePath
            val isCuratedReference = file.name == expectedName &&
                canonicalFile.name == expectedName && canonicalPath &&
                value.sizeBytes == expected.sizeBytes &&
                value.sha256.equals(expected.sha256, ignoreCase = true)
            when {
                isCuratedReference -> canonicalRole to StableAudio3ComponentRef(
                    path = canonicalFile.path,
                    sha256 = expected.sha256,
                    sizeBytes = expected.sizeBytes,
                    sourceIdentity = "sha256:${expected.sha256}"
                )
                canonicalRole == StableAudioModelSupport.ROLE_DIT &&
                    validateMergedCache(
                        file = canonicalFile,
                        expectedBase = expected,
                        request = request,
                        sourceIdentity = value.sourceIdentity
                    ) -> {
                    warnings += "manual_lora_cache:${roleLabel(canonicalRole)}"
                    canonicalRole to value.copy(
                        path = canonicalFile.path,
                        sha256 = sha256(canonicalFile),
                        sizeBytes = canonicalFile.length()
                    )
                }
                value.sourceIdentity.orEmpty().startsWith("stable-audio3-lora:") ->
                    throw StableAudio3ValidationException(
                        "Stable Audio merged LoRA cache manifest is invalid"
                    )
                else -> {
                    warnings += "manual_component:${roleLabel(canonicalRole)}"
                    canonicalRole to validateManualComponent(value, canonicalRole)
                }
            }
        }.toMap()
        return StableAudio3RequestResolution(
            request = request.copy(
                components = StableAudio3Components(
                    tokenizer = requireNotNull(components[StableAudioModelSupport.ROLE_TOKENIZER]),
                    textEncoder = requireNotNull(components[StableAudioModelSupport.ROLE_TEXT_ENCODER]),
                    dit = requireNotNull(components[StableAudioModelSupport.ROLE_DIT]),
                    codecDecoder = requireNotNull(components[StableAudioModelSupport.ROLE_CODEC_DECODER]),
                    codecEncoder = components[StableAudioModelSupport.ROLE_CODEC_ENCODER]
                )
            ),
            warnings = warnings.distinct()
        )
    }

    /** Full doctor check. Call from Dispatchers.IO; digesting a DiT is expensive. */
    fun inspectInstalled(context: Context, model: ModelEntity): StableAudio3ModelDoctorReport {
        val kind = when (model.audioFamily) {
            StableAudioModelSupport.FAMILY_SFX -> StableAudio3Kind.SFX
            else -> StableAudio3Kind.MUSIC
        }
        val manifest = StableAudio3ManifestLoader.load(context)
        val entry = entryFor(manifest, kind)
        val role = StableAudioModelSupport.canonicalRole(model.audioComponentRole)
        val expected = role?.let { entry.component(it) }
        if (role == null || expected == null) {
            return StableAudio3ModelDoctorReport(
                kind = kind,
                family = model.audioFamily ?: StableAudioModelSupport.FAMILY_SHARED,
                role = role,
                check = StableAudio3ComponentCheck(
                    role = role ?: "unknown",
                    expectedFilename = "-",
                    expectedSizeBytes = 0L,
                    expectedSha256 = "-",
                    actualSizeBytes = File(model.path).takeIf { it.exists() }?.length(),
                    actualSha256 = null,
                    readable = false,
                    parentDirectoryAvailable = File(model.path).parentFile?.isDirectory == true,
                    health = StableAudio3ComponentHealth.ROLE_MISMATCH
                ),
                manifestRevision = manifest.revision
            )
        }
        val check = inspectFile(
            kind = kind,
            entry = entry,
            component = expected,
            model = model,
            calculateDigest = true
        )
        return StableAudio3ModelDoctorReport(
            kind = kind,
            family = model.audioFamily ?: StableAudioModelSupport.FAMILY_SHARED,
            role = role,
            check = check,
            manifestRevision = manifest.revision
        )
    }

    /** Metadata and bounded filesystem checks used before digesting large payloads. */
    fun inspectFile(
        kind: StableAudio3Kind,
        entry: StableAudio3ManifestEntry,
        component: StableAudio3ManifestComponent,
        model: ModelEntity,
        calculateDigest: Boolean
    ): StableAudio3ComponentCheck {
        val expectedName = canonicalFilename(kind, component)
        val file = File(model.path)
        val parentAvailable = file.parentFile?.isDirectory == true
        val readable = file.isFile && file.canRead()
        val actualSize = file.takeIf { it.exists() }?.length()
        val canonicalRole = StableAudioModelSupport.canonicalRole(component.role) ?: component.role
        val belongsToEntry = entry.kind == kind && entry.component(canonicalRole) == component
        val expectedFamily = if (canonicalRole == StableAudioModelSupport.ROLE_DIT) {
            kind.family
        } else {
            StableAudioModelSupport.FAMILY_SHARED
        }
        val baseHealth = when {
            !belongsToEntry -> StableAudio3ComponentHealth.NON_CANONICAL
            StableAudioModelSupport.canonicalRole(model.audioComponentRole) != canonicalRole ->
                StableAudio3ComponentHealth.ROLE_MISMATCH
            model.type != expectedType(canonicalRole) ||
                model.repoId != StableAudio3Ids.SOURCE_REPOSITORY ||
                model.audioFamily != expectedFamily ||
                !model.audioArtifactIdentity.equals("sha256:${component.sha256}", ignoreCase = true) ->
                StableAudio3ComponentHealth.ROLE_MISMATCH
            file.name != expectedName -> StableAudio3ComponentHealth.NON_CANONICAL
            !file.exists() -> StableAudio3ComponentHealth.MISSING
            !parentAvailable -> StableAudio3ComponentHealth.PARENT_UNAVAILABLE
            !file.isFile || !readable -> StableAudio3ComponentHealth.UNREADABLE
            actualSize != component.sizeBytes -> StableAudio3ComponentHealth.SIZE_MISMATCH
            else -> null
        }
        if (baseHealth != null || !calculateDigest) {
            return StableAudio3ComponentCheck(
                role = canonicalRole,
                expectedFilename = expectedName,
                expectedSizeBytes = component.sizeBytes ?: 0L,
                expectedSha256 = component.sha256.orEmpty(),
                actualSizeBytes = actualSize,
                actualSha256 = null,
                readable = readable,
                parentDirectoryAvailable = parentAvailable,
                health = baseHealth ?: StableAudio3ComponentHealth.READY
            )
        }
        val actualDigest = sha256(file)
        return StableAudio3ComponentCheck(
            role = canonicalRole,
            expectedFilename = expectedName,
            expectedSizeBytes = component.sizeBytes ?: 0L,
            expectedSha256 = component.sha256.orEmpty(),
            actualSizeBytes = actualSize,
            actualSha256 = actualDigest,
            readable = readable,
            parentDirectoryAvailable = parentAvailable,
            health = if (actualDigest.equals(component.sha256, ignoreCase = true)) {
                StableAudio3ComponentHealth.READY
            } else {
                StableAudio3ComponentHealth.DIGEST_MISMATCH
            }
        )
    }

    private fun expectedType(role: String): ModelType =
        if (StableAudioModelSupport.canonicalRole(role) == StableAudioModelSupport.ROLE_DIT) {
            ModelType.LITERT_AUDIO_DIT
        } else {
            ModelType.LITERT_AUDIO_COMPONENT
        }

    private fun roleLabel(role: String): String = when (role) {
        StableAudioModelSupport.ROLE_TEXT_ENCODER -> "text encoder"
        StableAudioModelSupport.ROLE_CODEC_ENCODER -> "codec encoder"
        StableAudioModelSupport.ROLE_CODEC_DECODER -> "codec decoder"
        else -> role
    }

    private inline fun checkCanonical(condition: Boolean, message: () -> String) {
        if (!condition) throw StableAudio3ValidationException(message())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    /** Validates a manually registered component without borrowing catalog metadata. */
    fun validateManualComponent(
        component: StableAudio3ComponentRef,
        role: String
    ): StableAudio3ComponentRef {
        val value = component.normalized()
        val file = File(value.path)
        require(file.isFile && file.canRead()) {
            "Stable Audio ${roleLabel(role)} component is missing or unreadable"
        }
        require(file.length() > 0L) {
            "Stable Audio ${roleLabel(role)} component is empty"
        }
        value.sizeBytes?.let { expected ->
            require(file.length() == expected) {
                "Stable Audio ${roleLabel(role)} component size changed"
            }
        }
        val actualDigest = sha256(file)
        value.sha256?.let { expected ->
            require(actualDigest.equals(expected, ignoreCase = true)) {
                "Stable Audio ${roleLabel(role)} component digest changed"
            }
        }
        if (StableAudioModelSupport.canonicalRole(role) != StableAudioModelSupport.ROLE_TOKENIZER) {
            require(hasLiteRtFlatbufferHeader(file)) {
                "Stable Audio ${roleLabel(role)} component is not a LiteRT graph"
            }
        }
        return value.copy(
            path = runCatching { file.canonicalPath }.getOrElse { file.absolutePath },
            sha256 = actualDigest,
            sizeBytes = file.length(),
            sourceIdentity = "manual;sha256:$actualDigest"
        )
    }

    private fun hasLiteRtFlatbufferHeader(file: File): Boolean = runCatching {
        file.inputStream().buffered().use { input ->
            val header = ByteArray(8)
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                if (count < 0) return@use false
                read += count
            }
            header.copyOfRange(4, 8).contentEquals(byteArrayOf('T'.code.toByte(), 'F'.code.toByte(),
                'L'.code.toByte(), '3'.code.toByte()))
        }
    }.getOrDefault(false)

    /** Validates the sidecar and bytes produced by StableAudio3LoraMerge. */
    private fun validateMergedCache(
        file: File,
        expectedBase: StableAudio3ManifestComponent,
        request: StableAudio3Request,
        sourceIdentity: String?
    ): Boolean {
        val expectedBaseDigest = expectedBase.sha256?.lowercase(Locale.US) ?: return false
        val manifest = File(file.parentFile, file.nameWithoutExtension + ".json")
        val json = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return false
        if (json.optInt("schemaVersion", -1) != 1 ||
            json.optString("family") != request.kind.family ||
            json.optString("precision") != request.ditPrecision.wireValue ||
            json.optString("baseDigest").lowercase(Locale.US) != expectedBaseDigest ||
            json.optLong("baseSizeBytes", -1L) != expectedBase.sizeBytes ||
            json.optLong("mergedSizeBytes", -1L) != file.length() ||
            json.optString("mergedPath").isBlank() ||
            runCatching { File(json.optString("mergedPath")).canonicalPath != file.canonicalPath }
                .getOrDefault(true)
        ) return false
        val cacheKey = json.optString("cacheKey").takeIf { it.isNotBlank() } ?: return false
        val provenance = json.optString("provenance")
        if (!provenance.contains(cacheKey)) return false
        if (!sourceIdentity.orEmpty().startsWith("stable-audio3-lora:$cacheKey;")) return false
        val mergedDigest = json.optString("mergedDigest")
        if (!mergedDigest.matches(Regex("[0-9a-fA-F]{64}"))) return false
        return runCatching { sha256(file).equals(mergedDigest, ignoreCase = true) }.getOrDefault(false)
    }
}
