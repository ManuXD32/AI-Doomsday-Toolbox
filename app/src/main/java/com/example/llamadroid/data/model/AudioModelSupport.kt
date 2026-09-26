package com.example.llamadroid.data.model

import android.content.Context
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.isAudioTtsComponentType
import com.example.llamadroid.data.db.isStableAudioComponentType
import com.example.llamadroid.sd.SdArtifactInspection
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/** Stable identifiers and bounded recognition helpers for native TTS artifacts. */
object AudioModelSupport {
    const val FAMILY_QWEN3_TTS = "qwen3_tts"
    const val FAMILY_POCKET_TTS = "pocket_tts"
    const val FAMILY_SUPERTONIC = "supertonic_tts"
    const val FAMILY_CUSTOM_TTS = "custom_tts"
    const val ROLE_MAIN = "tts_main"
    const val ROLE_MMProj = "tts_mmproj"
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_SPANISH = "es"

    data class Descriptor(
        val family: String,
        val role: String = ROLE_MAIN,
        val language: String? = null,
        val modelType: ModelType = if (role == ROLE_MMProj) ModelType.LLAMA_TTS_COMPANION else ModelType.LLAMA_TTS
    )

    private fun ModelType.isSupportedAudioArtifactType(): Boolean =
        isAudioTtsComponentType() || isStableAudioComponentType()

    /** Resolve the durable identity of an installed audio model row. */
    fun descriptorForModel(model: ModelEntity, context: Context? = null): Descriptor? {
        if (!model.type.isSupportedAudioArtifactType()) return null
        if (model.type.isStableAudioComponentType()) {
            val verifiedCatalog = model.audioArtifactIdentity
                ?.let { CuratedModelBundleRegistry.fileForArtifactDigest(it, model.type, context) }
                ?.let(::descriptorForCuratedFile)
            val role = StableAudioModelSupport.canonicalRole(model.audioComponentRole)
                ?: verifiedCatalog?.role
                ?: StableAudioModelSupport.defaultRoleForType(model.type)
                ?: return null
            return Descriptor(
                family = model.audioFamily?.trim().takeIf { !it.isNullOrBlank() }
                    ?: verifiedCatalog?.family
                    ?: StableAudioModelSupport.FAMILY_SHARED,
                role = role,
                modelType = model.type
            )
        }
        val verifiedCatalog = model.audioArtifactIdentity
            ?.let { CuratedModelBundleRegistry.fileForArtifactDigest(it, model.type, context) }
            ?.let(::descriptorForCuratedFile)
        val role = normalizeRole(model.audioComponentRole)
            ?: verifiedCatalog?.role
            ?: if (model.type == ModelType.LLAMA_TTS_COMPANION) ROLE_MMProj else ROLE_MAIN
        return Descriptor(
            family = model.audioFamily?.trim().takeIf { !it.isNullOrBlank() }
                ?: verifiedCatalog?.family
                ?: FAMILY_CUSTOM_TTS,
            role = role,
            language = model.audioLanguage?.trim().takeIf { !it.isNullOrBlank() }
                ?: verifiedCatalog?.language,
            modelType = model.type
        )
    }

    /**
     * Resolve metadata while a curated or explicitly typed download is being
     * finalized. Curated identity is accepted only for a hash-pinned catalog
     * entry; arbitrary renamed files become custom TTS.
     */
    fun descriptorForDownload(
        type: ModelType,
        repoId: String?,
        filename: String,
        familyHint: String? = null,
        roleHint: String? = null,
        sourceUrl: String? = null,
        context: Context? = null
    ): Descriptor? {
        if (!type.isSupportedAudioArtifactType()) return null
        if (type.isStableAudioComponentType()) {
            val curated = curatedFile(repoId, filename, sourceUrl, context)
            val role = StableAudioModelSupport.canonicalRole(roleHint)
                ?: curated?.componentRole?.let(StableAudioModelSupport::canonicalRole)
                ?: StableAudioModelSupport.defaultRoleForType(type)
                ?: return null
            return Descriptor(
                family = familyHint?.takeIf { StableAudioModelSupport.isFamily(it) }
                    ?: curated?.audioFamily?.takeIf { StableAudioModelSupport.isFamily(it) }
                    ?: StableAudioModelSupport.FAMILY_SHARED,
                role = role,
                modelType = type
            )
        }
        val curated = curatedDescriptor(repoId, filename, sourceUrl, context)
        val hintedFamily = familyHint?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("AUDIO", ignoreCase = true) }
        val role = normalizeRole(roleHint)
            ?: curated?.role
            ?: if (type == ModelType.LLAMA_TTS_COMPANION) ROLE_MMProj else ROLE_MAIN
        return Descriptor(
            family = hintedFamily
                ?: curated?.family
                ?: FAMILY_CUSTOM_TTS,
            role = role,
            language = curated?.language,
            modelType = type
        )
    }

    /**
     * Resolve a verified installed payload without relying on its presentation
     * filename or the source URL retained by an import. A content digest is
     * sufficient to recover curated family/language metadata after redirects,
     * manual promotion, or a user-selected rename.
     */
    fun descriptorForPayload(
        type: ModelType,
        digest: String?,
        repoId: String? = null,
        filename: String = "",
        familyHint: String? = null,
        roleHint: String? = null,
        sourceUrl: String? = null,
        context: Context? = null
    ): Descriptor? {
        if (!type.isSupportedAudioArtifactType()) return null
        val digestMatch = digest
            ?.let { CuratedModelBundleRegistry.fileForArtifactDigest(it, type, context) }
            ?.let(::descriptorForCuratedFile)
        return digestMatch ?: descriptorForDownload(
            type = type,
            repoId = repoId,
            filename = filename,
            familyHint = familyHint,
            roleHint = roleHint,
            sourceUrl = sourceUrl,
            context = context
        )
    }

    /** Maps a curated model-library item to the same descriptor used at install time. */
    fun fromCuratedFile(file: CuratedBundleFile): Descriptor? {
        if (!file.type.isSupportedAudioArtifactType()) return null
        val role = if (file.type.isStableAudioComponentType()) {
            StableAudioModelSupport.canonicalRole(file.componentRole)
                ?: StableAudioModelSupport.defaultRoleForType(file.type)
        } else {
            normalizeRole(file.componentRole)
                ?: if (file.type == ModelType.LLAMA_TTS_COMPANION) ROLE_MMProj else ROLE_MAIN
        } ?: return null
        val family = file.audioFamily ?: if (file.type.isStableAudioComponentType()) {
            StableAudioModelSupport.FAMILY_SHARED
        } else {
            FAMILY_CUSTOM_TTS
        }
        return Descriptor(family = family, role = role, language = file.audioLanguage, modelType = file.type)
    }

    /** Digest identity for a matching curated component, independent of prefix. */
    fun curatedArtifactIdentity(repoId: String?, filename: String, context: Context? = null): String? =
        curatedFile(repoId, filename, null, context)?.artifactIdentity

    /**
     * Recognizes known speech families from bounded GGUF evidence. Generic
     * compatible imports can still be promoted as
     * [FAMILY_CUSTOM_TTS] after the user explicitly chooses the audio family.
     */
    fun recognize(inspection: SdArtifactInspection, filename: String): Descriptor? {
        // Keep filename in the signature for import diagnostics; never use it
        // as compatibility evidence.
        if (!inspection.isStructurallyUsable) return null
        val evidence = buildString {
            inspection.metadata.entries.forEach { (key, value) -> append(key).append(' ').append(value).append(' ') }
            inspection.tensorNamePrefixes.forEach { append(it).append(' ') }
        }.lowercase(Locale.US)
        val compactEvidence = evidence.replace(Regex("[^a-z0-9]"), "")
        val family = when {
            compactEvidence.contains("qwen3tts") -> FAMILY_QWEN3_TTS
            compactEvidence.contains("pockettts") -> FAMILY_POCKET_TTS
            evidence.contains("supertonic") -> FAMILY_SUPERTONIC
            compactEvidence.contains("tts") && setOf("audio", "codec", "speaker", "phoneme", "mel")
                .any(compactEvidence::contains) -> FAMILY_CUSTOM_TTS
            else -> null
        } ?: return null
        val companion = evidence.contains("mmproj") || evidence.contains("projector")
        val language = when {
            evidence.contains("spanish") || evidence.contains("language.es") || evidence.contains("lang_es") -> LANGUAGE_SPANISH
            evidence.contains("english") || evidence.contains("language.en") || evidence.contains("lang_en") -> LANGUAGE_ENGLISH
            else -> null
        }
        return Descriptor(family, if (companion) ROLE_MMProj else ROLE_MAIN, language)
    }

    private fun normalizeRole(value: String?): String? = value
        ?.substringBefore(':')
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun curatedDescriptor(
        repoId: String?,
        filename: String,
        sourceUrl: String?,
        context: Context? = null
    ): Descriptor? {
        val file = curatedFile(repoId, filename, sourceUrl, context) ?: return null
        return descriptorForCuratedFile(file)
    }

    private fun descriptorForCuratedFile(file: CuratedBundleFile): Descriptor? {
        return fromCuratedFile(file)
    }

    private fun curatedFile(
        repoId: String?,
        filename: String,
        sourceUrl: String?,
        context: Context? = null
    ): CuratedBundleFile? = CuratedModelBundleRegistry.fileForDownload(
        localFilename = filename,
        repoId = repoId,
        sourceUrl = sourceUrl,
        context = context
    )
            ?.takeIf { it.type.isSupportedAudioArtifactType() }

    fun portableMetadata(descriptor: Descriptor, artifactIdentity: String? = null): String =
        JSONObject().apply {
            if (descriptor.modelType.isStableAudioComponentType()) {
                put("stableAudioFamily", descriptor.family)
                put("stableAudioComponentRole", descriptor.role)
            } else {
                put("audioFamily", descriptor.family)
                put("audioComponentRole", descriptor.role)
                descriptor.language?.let { put("audioLanguage", it) }
            }
            artifactIdentity?.let { put("audioArtifactIdentity", it) }
        }.toString()

    /** Digest of source identity, independent of presentation prefixes/suffixes. */
    fun artifactIdentityDigest(repoId: String, revision: String, remotePath: String): String {
        val canonical = "hf|$repoId|$revision|$remotePath"
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") {
            "%02x".format(Locale.US, it.toInt() and 0xff)
        }
    }

    /**
     * Content identity assigned only after the payload is present locally.
     * Curated source identity and filename are presentation metadata; this
     * digest is the verification marker used for reuse and deletion safety.
     */
    fun payloadArtifactIdentity(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return "sha256:" + digest.digest().joinToString("") {
            "%02x".format(Locale.US, it.toInt() and 0xff)
        }
    }
}
