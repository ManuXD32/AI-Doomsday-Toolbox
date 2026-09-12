package com.example.llamadroid.audio.music

import android.content.Context
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.StableAudioModelSupport
import java.io.File
import java.security.MessageDigest
import java.util.Locale

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
    val health: StableAudio3ComponentHealth
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
        return if (component.role == StableAudioModelSupport.ROLE_DIT) {
            "Stable-Audio-3-Small-${if (kind == StableAudio3Kind.MUSIC) "Music" else "SFX"}-${component.localFileName}"
        } else {
            "shared-${component.sha256.orEmpty().take(12)}-${component.localFileName}"
        }
    }

    fun canonicalComponent(
        kind: StableAudio3Kind,
        entry: StableAudio3ManifestEntry,
        role: String
    ): StableAudio3ManifestComponent = entry.component(role)
        ?: throw StableAudio3ValidationException("Stable Audio ${roleLabel(role)} component is missing")

    /** Returns true when durable row metadata identifies the exact curated artifact. */
    fun isCanonicalInstalledModel(
        kind: StableAudio3Kind,
        entry: StableAudio3ManifestEntry,
        component: StableAudio3ManifestComponent,
        model: ModelEntity
    ): Boolean {
        val payload = File(model.path)
        val canonicalPayload = runCatching { payload.canonicalFile }.getOrNull() ?: return false
        val expectedFamily = if (component.role == StableAudioModelSupport.ROLE_DIT) {
            kind.family
        } else {
            StableAudioModelSupport.FAMILY_SHARED
        }
        return model.isDownloaded &&
            model.repoId == StableAudio3Ids.SOURCE_REPOSITORY &&
            model.type == expectedType(component.role) &&
            model.audioComponentRole == component.role &&
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
    fun resolveRequest(context: Context, request: StableAudio3Request): StableAudio3Request {
        val manifest = StableAudio3ManifestLoader.load(context)
        val entry = entryFor(
            manifest = manifest,
            kind = request.kind,
            ditPrecision = request.ditPrecision,
            decoderPrecision = request.decoderPrecision,
            encoderPrecision = request.encoderPrecision
        )
        val components = request.components.all().map { (role, ref) ->
            val canonicalRole = when (role) {
                "textEncoder" -> StableAudioModelSupport.ROLE_TEXT_ENCODER
                "codecDecoder" -> StableAudioModelSupport.ROLE_CODEC_DECODER
                "codecEncoder" -> StableAudioModelSupport.ROLE_CODEC_ENCODER
                "tokenizer", "dit" -> role
                else -> throw StableAudio3ValidationException("Unknown Stable Audio component role")
            }
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
            checkCanonical(
                file.name == expectedName &&
                    canonicalFile.name == expectedName &&
                    canonicalFile.path == file.absolutePath
            ) {
                "Stable Audio ${roleLabel(canonicalRole)} is stale; re-download the curated component"
            }
            checkCanonical(value.sizeBytes == expected.sizeBytes) {
                "Stable Audio ${roleLabel(canonicalRole)} size is stale; re-download the curated component"
            }
            checkCanonical(value.sha256.equals(expected.sha256, ignoreCase = true)) {
                "Stable Audio ${roleLabel(canonicalRole)} digest is stale; re-download the curated component"
            }
            role to StableAudio3ComponentRef(
                path = canonicalFile.path,
                sha256 = expected.sha256,
                sizeBytes = expected.sizeBytes,
                sourceIdentity = "sha256:${expected.sha256}"
            )
        }.toMap()
        return request.copy(
            components = StableAudio3Components(
                tokenizer = requireNotNull(components["tokenizer"]),
                textEncoder = requireNotNull(components["textEncoder"]),
                dit = requireNotNull(components["dit"]),
                codecDecoder = requireNotNull(components["codecDecoder"]),
                codecEncoder = components["codecEncoder"]
            )
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
        val expectedFamily = if (component.role == StableAudioModelSupport.ROLE_DIT) {
            kind.family
        } else {
            StableAudioModelSupport.FAMILY_SHARED
        }
        val baseHealth = when {
            model.audioComponentRole != component.role ->
                StableAudio3ComponentHealth.ROLE_MISMATCH
            model.type != expectedType(component.role) ||
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
                role = component.role,
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
            role = component.role,
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
        if (role == StableAudioModelSupport.ROLE_DIT) {
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
}
