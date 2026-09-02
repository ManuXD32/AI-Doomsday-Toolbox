package com.example.llamadroid.sd

import android.content.ContentResolver
import android.net.Uri
import com.example.llamadroid.data.db.ModelType
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private const val GGUF_TYPE_UINT8 = 0
private const val GGUF_TYPE_INT8 = 1
private const val GGUF_TYPE_UINT16 = 2
private const val GGUF_TYPE_INT16 = 3
private const val GGUF_TYPE_UINT32 = 4
private const val GGUF_TYPE_INT32 = 5
private const val GGUF_TYPE_FLOAT32 = 6
private const val GGUF_TYPE_BOOL = 7
private const val GGUF_TYPE_STRING = 8
private const val GGUF_TYPE_ARRAY = 9
private const val GGUF_TYPE_UINT64 = 10
private const val GGUF_TYPE_INT64 = 11
private const val GGUF_TYPE_FLOAT64 = 12

/**
 * Bounded, payload-free inspection of Stable Diffusion model artifacts.
 *
 * SafeTensors inspection reads only its JSON header. GGUF inspection reads the
 * fixed header, metadata and tensor descriptors, never tensor payloads. This
 * class intentionally does not attempt to deserialize pickle/CKPT files.
 */
class SdArtifactInspector {
    /** Inspect using an optional user/configuration role as separate evidence. */
    fun inspect(file: File, configuredRole: SdArtifactRole? = null): SdArtifactInspection =
        inspectInternal(file, configuredRole)

    /** Convenience overload for the persisted ModelType used by import code. */
    fun inspect(file: File, configuredType: ModelType): SdArtifactInspection =
        inspectInternal(file, configuredType.toArtifactRole())

    /** Convenience overload for callers holding a path string. */
    fun inspect(path: String, configuredRole: SdArtifactRole? = null): SdArtifactInspection =
        inspect(File(path), configuredRole)

    fun inspect(path: Path, configuredRole: SdArtifactRole? = null): SdArtifactInspection =
        inspect(path.toFile(), configuredRole)

    /**
     * Inspect a user-selected document without copying its model payload.
     *
     * Storage Access Framework providers are not guaranteed to be seekable,
     * so this copies only the bounded prefix required by the SafeTensors/GGUF
     * header parsers (at most 32 MiB plus the SafeTensors length word) to a
     * temporary cache file. No model tensor payload is loaded or retained.
     */
    fun inspect(
        contentResolver: ContentResolver,
        uri: Uri,
        displayName: String? = null,
        configuredRole: SdArtifactRole? = null,
        temporaryDirectory: File? = null
    ): SdArtifactInspection {
        val sourceSize = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrNull()
        val suffix = suffixForDisplayName(displayName)
        val prefixFile = runCatching {
            File.createTempFile("sd-inspection-", suffix, temporaryDirectory)
        }.getOrNull()
            ?: return SdArtifactInspection(
                configuredRole = configuredRole,
                warnings = listOf("Unable to create a temporary artifact-inspection file"),
                fileSizeBytes = sourceSize,
                headerValid = false
            )
        return try {
            val copied = contentResolver.openInputStream(uri)?.use { input ->
                prefixFile.outputStream().use { output ->
                    copyBounded(input, output, MAX_URI_PREFIX_BYTES)
                }
            } ?: throw IOException("Unable to open selected artifact")
            if (copied == 0L) {
                SdArtifactInspection(
                    format = formatForExtension(suffix.removePrefix(".")),
                    configuredRole = configuredRole,
                    warnings = listOf("Selected artifact is empty"),
                    fileSizeBytes = sourceSize ?: 0L,
                    headerValid = false
                )
            } else {
                inspect(prefixFile, configuredRole).copy(
                    fileSizeBytes = sourceSize ?: copied,
                    // SAF does not expose a stable modification timestamp for
                    // every provider; leave it unknown instead of inventing one.
                    modifiedAtMillis = null
                )
            }
        } catch (error: Exception) {
            SdArtifactInspection(
                format = formatForExtension(suffix.removePrefix(".")),
                configuredRole = configuredRole,
                warnings = listOf(sanitizeException("Artifact inspection failed", error)),
                fileSizeBytes = sourceSize,
                headerValid = false
            )
        } finally {
            prefixFile.delete()
        }
    }

    /** Map the persisted model role to inspection terminology without changing it. */
    fun inspectModel(file: File, configuredType: ModelType?): SdArtifactInspection =
        inspect(file, configuredType.toArtifactRole())

    private fun inspectInternal(file: File, configuredRole: SdArtifactRole?): SdArtifactInspection {
        val size = file.takeIf { it.exists() }?.length()
        val modified = file.takeIf { it.exists() }?.lastModified()
        val extension = file.extension.lowercase(Locale.US)
        if (!file.exists()) {
            return baseResult(
                format = formatForExtension(extension),
                configuredRole = configuredRole,
                size = null,
                modified = null,
                warnings = listOf("Artifact does not exist")
            )
        }
        if (!file.isFile || !file.canRead()) {
            return baseResult(
                format = formatForExtension(extension),
                configuredRole = configuredRole,
                size = size,
                modified = modified,
                warnings = listOf("Artifact is not a readable regular file")
            )
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val format = detectFormat(raf, extension)
                when (format) {
                    SdArtifactFormat.SAFETENSORS -> inspectSafeTensors(raf, file, configuredRole)
                    SdArtifactFormat.GGUF -> inspectGguf(raf, file, configuredRole)
                    SdArtifactFormat.CKPT -> inspectLegacyCheckpoint(file, configuredRole)
                    SdArtifactFormat.UNKNOWN -> baseResult(
                        format = format,
                        configuredRole = configuredRole,
                        size = size,
                        modified = modified,
                        warnings = listOf("Unsupported or unrecognized Stable Diffusion artifact format")
                    )
                }
            }
        } catch (e: Exception) {
            val format = formatForExtension(extension)
            baseResult(
                format = format,
                configuredRole = configuredRole,
                size = size,
                modified = modified,
                warnings = listOf(sanitizeException("Artifact inspection failed", e))
            )
        }
    }

    private fun inspectSafeTensors(
        raf: RandomAccessFile,
        file: File,
        configuredRole: SdArtifactRole?
    ): SdArtifactInspection {
        val size = file.length()
        val modified = file.lastModified()
        val warnings = mutableListOf<String>()
        val digest = MessageDigest.getInstance("SHA-256")
        if (size < SAFETENSORS_LENGTH_BYTES) {
            return baseResult(
                format = SdArtifactFormat.SAFETENSORS,
                configuredRole = configuredRole,
                size = size,
                modified = modified,
                warnings = listOf("SafeTensors header is truncated")
            )
        }

        val lengthBytes = ByteArray(SAFETENSORS_LENGTH_BYTES)
        raf.readFully(lengthBytes)
        digest.update(lengthBytes)
        val headerLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).long
        if (headerLength < MIN_SAFETENSORS_HEADER_BYTES || headerLength > MAX_SAFETENSORS_HEADER_BYTES) {
            return baseResult(
                format = SdArtifactFormat.SAFETENSORS,
                configuredRole = configuredRole,
                size = size,
                modified = modified,
                warnings = listOf(
                    if (headerLength > MAX_SAFETENSORS_HEADER_BYTES) {
                        "SafeTensors header exceeds the 32 MiB safety limit"
                    } else {
                        "SafeTensors header length is invalid"
                    }
                ),
                fingerprint = hexDigest(digest.digest())
            )
        }
        val headerEnd = safelyAdd(SAFETENSORS_LENGTH_BYTES.toLong(), headerLength)
        if (headerEnd == null || headerEnd > size) {
            return baseResult(
                format = SdArtifactFormat.SAFETENSORS,
                configuredRole = configuredRole,
                size = size,
                modified = modified,
                warnings = listOf("SafeTensors header is truncated"),
                fingerprint = hexDigest(digest.digest())
            )
        }
        val header = ByteArray(headerLength.toInt())
        raf.readFully(header)
        digest.update(header)

        val root = try {
            JSONObject(String(header, Charsets.UTF_8))
        } catch (e: Exception) {
            return baseResult(
                format = SdArtifactFormat.SAFETENSORS,
                configuredRole = configuredRole,
                size = size,
                modified = modified,
                warnings = listOf("SafeTensors header is not valid JSON"),
                fingerprint = hexDigest(digest.digest())
            )
        }

        val tensorNames = ArrayList<String>(minOf(root.length(), MAX_RECORDED_TENSOR_NAMES))
        val representativePrefixes = linkedSetOf<String>()
        val metadata = linkedMapOf<String, String>()
        var tensorCount = 0L
        var malformedDescriptor = false
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "__metadata__") {
                val metadataObject = root.optJSONObject(key)
                if (metadataObject == null) {
                    warnings += "SafeTensors __metadata__ is not an object"
                } else {
                    metadataObject.keys().forEach { metadataKey ->
                        if (metadata.size < MAX_METADATA_ENTRIES) {
                            val value = metadataObject.opt(metadataKey)
                            if (value is String || value is Number || value is Boolean) {
                                metadata[metadataKey.take(MAX_METADATA_KEY_LENGTH)] =
                                    value.toString().take(MAX_METADATA_VALUE_LENGTH)
                            }
                        }
                    }
                }
                continue
            }
            tensorCount++
            val descriptor = root.optJSONObject(key)
            if (descriptor == null || !validateSafeTensorDescriptor(descriptor, headerEnd, size)) {
                malformedDescriptor = true
                if (warnings.none { it == "SafeTensors contains an invalid tensor descriptor" }) {
                    warnings += "SafeTensors contains an invalid tensor descriptor"
                }
            }
            digest.update(key.toByteArray(Charsets.UTF_8))
            digest.update(0)
            if (tensorNames.size < MAX_RECORDED_TENSOR_NAMES) tensorNames += key
            recordPrefix(representativePrefixes, key)
        }

        if (tensorCount == 0L) warnings += "SafeTensors contains no tensor descriptors"
        val evidence = classifyEvidence(
            format = SdArtifactFormat.SAFETENSORS,
            tensorNames = tensorNames,
            metadata = metadata,
            filename = file.name,
            configuredRole = configuredRole
        )
        if (malformedDescriptor) warnings += "Tensor metadata could not be fully validated"
        val finalConfidence = if (malformedDescriptor && evidence.confidence == SdInspectionConfidence.HIGH) {
            SdInspectionConfidence.MEDIUM
        } else evidence.confidence
        return evidence.toInspection(
            tensorCount = tensorCount,
            configuredRole = configuredRole,
            confidence = finalConfidence,
            warnings = warnings,
            fingerprint = hexDigest(digest.digest()),
            size = size,
            modified = modified,
            headerValid = !malformedDescriptor,
            tensorNamePrefixes = representativePrefixes
        )
    }

    private fun inspectGguf(
        raf: RandomAccessFile,
        file: File,
        configuredRole: SdArtifactRole?
    ): SdArtifactInspection {
        val warnings = mutableListOf<String>()
        val digest = MessageDigest.getInstance("SHA-256")
        val reader = GgufReader(raf, file.length(), digest)
        return try {
            reader.readMagicAndVersion()
            val tensorCount = reader.readCount("tensor")
            val metadataCount = reader.readCount("metadata")
            if (tensorCount > MAX_GGUF_TENSORS) throw InvalidArtifactException("GGUF tensor count is unreasonable")
            if (metadataCount > MAX_GGUF_METADATA) throw InvalidArtifactException("GGUF metadata count is unreasonable")

            val metadata = linkedMapOf<String, String>()
            repeat(metadataCount.toInt()) {
                val key = reader.readString()
                val value = reader.readMetadataValue(capture = shouldCaptureMetadata(key))
                if (value != null && metadata.size < MAX_METADATA_ENTRIES) {
                    metadata[key.take(MAX_METADATA_KEY_LENGTH)] = value.take(MAX_METADATA_VALUE_LENGTH)
                }
            }

            val tensorNames = ArrayList<String>(minOf(tensorCount.toInt(), MAX_RECORDED_TENSOR_NAMES))
            val prefixes = linkedSetOf<String>()
            repeat(tensorCount.toInt()) {
                val name = reader.readString()
                reader.readTensorDescriptor()
                if (tensorNames.size < MAX_RECORDED_TENSOR_NAMES) tensorNames += name
                recordPrefix(prefixes, name)
                reader.updateDigest(name)
            }
            if (tensorCount == 0L) warnings += "GGUF contains no tensor descriptors"
            val evidence = classifyEvidence(
                format = SdArtifactFormat.GGUF,
                tensorNames = tensorNames,
                metadata = metadata,
                filename = file.name,
                configuredRole = configuredRole
            )
            evidence.toInspection(
                tensorCount = tensorCount,
                configuredRole = configuredRole,
                confidence = evidence.confidence,
                warnings = warnings,
                fingerprint = hexDigest(digest.digest()),
                size = file.length(),
                modified = file.lastModified(),
                tensorNamePrefixes = prefixes
            )
        } catch (e: Exception) {
            warnings += sanitizeException("GGUF header inspection failed", e)
            baseResult(
                format = SdArtifactFormat.GGUF,
                configuredRole = configuredRole,
                size = file.length(),
                modified = file.lastModified(),
                warnings = warnings,
                fingerprint = hexDigest(digest.digest())
            )
        }
    }

    private fun inspectLegacyCheckpoint(file: File, configuredRole: SdArtifactRole?): SdArtifactInspection {
        val explicitFull = configuredRole == SdArtifactRole.FULL_MODEL ||
            configuredRole == SdArtifactRole.MAIN_MODEL
        val warnings = mutableListOf("Legacy checkpoint was not deserialized; payload inspection is disabled")
        if (explicitFull) {
            warnings += "Legacy checkpoint classification relies on explicit configuration"
            return SdArtifactInspection(
                format = SdArtifactFormat.CKPT,
                detectedRole = SdArtifactRole.FULL_MODEL,
                configuredRole = configuredRole,
                mainLayout = SdMainLayout.FULL_MODEL,
                confidence = SdInspectionConfidence.LOW,
                warnings = warnings,
                inspectionVersion = SdArtifactInspection.CURRENT_INSPECTION_VERSION,
                fileSizeBytes = file.length(),
                modifiedAtMillis = file.lastModified(),
                headerValid = true
            )
        }
        return baseResult(
            format = SdArtifactFormat.CKPT,
            configuredRole = configuredRole,
            size = file.length(),
            modified = file.lastModified(),
            warnings = warnings
        )
    }

    private fun baseResult(
        format: SdArtifactFormat,
        configuredRole: SdArtifactRole?,
        size: Long?,
        modified: Long?,
        warnings: List<String>,
        fingerprint: String? = null
    ): SdArtifactInspection = SdArtifactInspection(
        format = format,
        configuredRole = configuredRole,
        confidence = SdInspectionConfidence.UNKNOWN,
        warnings = warnings.take(MAX_WARNINGS),
        headerFingerprint = fingerprint,
        inspectionVersion = SdArtifactInspection.CURRENT_INSPECTION_VERSION,
        fileSizeBytes = size,
        modifiedAtMillis = modified,
        headerValid = false
    )

    private fun detectFormat(raf: RandomAccessFile, extension: String): SdArtifactFormat {
        raf.seek(0)
        val extensionFormat = formatForExtension(extension)
        val detected = if (raf.length() >= GGUF_MAGIC_BYTES) {
            val magic = ByteArray(GGUF_MAGIC_BYTES)
            raf.readFully(magic)
            if (magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))) {
                SdArtifactFormat.GGUF
            } else {
                extensionFormat.takeUnless { it == SdArtifactFormat.UNKNOWN || it == SdArtifactFormat.CKPT }
                    ?: sniffSafeTensors(raf)
                    ?: extensionFormat
            }
        } else {
            sniffSafeTensors(raf) ?: extensionFormat
        }
        // Format probing must never change the offset seen by the bounded
        // format parser. In particular, SafeTensors begins with its 8-byte
        // length and GGUF begins with the magic we just inspected.
        raf.seek(0)
        return detected
    }

    /** SafeTensors has no magic value; recognize its bounded length + JSON
     * opening even when a provider or legacy importer gave it a .bin name. */
    private fun sniffSafeTensors(raf: RandomAccessFile): SdArtifactFormat? {
        if (raf.length() < SAFETENSORS_LENGTH_BYTES) return null
        return runCatching {
            raf.seek(0)
            val lengthBytes = ByteArray(SAFETENSORS_LENGTH_BYTES)
            raf.readFully(lengthBytes)
            val headerLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).long
            val headerEnd = safelyAdd(SAFETENSORS_LENGTH_BYTES.toLong(), headerLength)
            if (headerLength !in MIN_SAFETENSORS_HEADER_BYTES..MAX_SAFETENSORS_HEADER_BYTES ||
                headerEnd == null || headerEnd > raf.length()
            ) {
                null
            } else {
                raf.seek(SAFETENSORS_LENGTH_BYTES.toLong())
                val firstHeaderByte = raf.readUnsignedByte()
                if (firstHeaderByte == '{'.code || firstHeaderByte == '['.code) {
                    SdArtifactFormat.SAFETENSORS
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun formatForExtension(extension: String): SdArtifactFormat = when (extension) {
        "safetensors", "safe_tensors" -> SdArtifactFormat.SAFETENSORS
        "gguf" -> SdArtifactFormat.GGUF
        "ckpt", "checkpoint", "pt", "pth", "bin" -> SdArtifactFormat.CKPT
        else -> SdArtifactFormat.UNKNOWN
    }

    private data class Evidence(
        val format: SdArtifactFormat,
        val family: SdModelFamily?,
        val role: SdArtifactRole?,
        val layout: SdMainLayout,
        val containsDiffusion: Boolean,
        val containsVae: Boolean,
        val containsClipL: Boolean,
        val containsClipG: Boolean,
        val containsT5xxl: Boolean,
        val containsLlm: Boolean,
        val confidence: SdInspectionConfidence,
        val metadata: Map<String, String>
    ) {
        fun toInspection(
            tensorCount: Long?,
            configuredRole: SdArtifactRole?,
            confidence: SdInspectionConfidence,
            warnings: List<String>,
            fingerprint: String?,
            size: Long?,
            modified: Long?,
            headerValid: Boolean = true,
            tensorNamePrefixes: Set<String>
        ): SdArtifactInspection = SdArtifactInspection(
            format = format,
            detectedFamily = family,
            detectedRole = role,
            containsDiffusion = containsDiffusion,
            containsVae = containsVae,
            containsClipL = containsClipL,
            containsClipG = containsClipG,
            containsT5xxl = containsT5xxl,
            containsLlm = containsLlm,
            tensorCount = tensorCount,
            confidence = confidence,
            warnings = warnings.take(MAX_WARNINGS),
            configuredRole = configuredRole,
            mainLayout = layout,
            headerFingerprint = fingerprint,
            inspectionVersion = SdArtifactInspection.CURRENT_INSPECTION_VERSION,
            fileSizeBytes = size,
            modifiedAtMillis = modified,
            headerValid = headerValid,
            metadata = metadata,
            tensorNamePrefixes = tensorNamePrefixes
        )

    }

    private fun classifyEvidence(
        format: SdArtifactFormat,
        tensorNames: List<String>,
        metadata: Map<String, String>,
        filename: String,
        configuredRole: SdArtifactRole?
    ): Evidence {
        val normalizedNames = tensorNames.map { it.lowercase(Locale.US) }
        val metadataText = metadata.entries.joinToString(" ") { "${it.key} ${it.value}" }.lowercase(Locale.US)
        val filenameText = filename.lowercase(Locale.US)
        fun anyName(predicate: (String) -> Boolean): Boolean = normalizedNames.any(predicate)
        // Adapter keys often retain the target module namespace (for example
        // `text_encoder` or `clip`).  Do not let those names masquerade as a
        // standalone encoder or a bundled checkpoint component.
        fun anyBaseName(predicate: (String) -> Boolean): Boolean = normalizedNames.any {
            !looksLikeLoraTensor(it) && predicate(it)
        }

        val containsVae = anyBaseName(::looksLikeVaeTensor)
        val containsTae = anyBaseName(::looksLikeTaeTensor)

        // Some converted CLIP-G artifacts retain the generic
        // `text_model.encoder.*` names used by CLIP-L.  Keep generic text
        // tensors separate from explicit encoder markers so a CLIP-G
        // filename can disambiguate that otherwise indistinguishable shape,
        // while explicit tensor evidence still wins over filenames.
        val explicitClipL = anyBaseName(::looksLikeClipLTensor)
        val explicitClipG = anyBaseName(::looksLikeClipGTensor)
        val genericClip = anyBaseName(::looksLikeGenericClipTensor)
        val filenameSuggestsClipG = filenameText.contains("clip_g") ||
            filenameText.contains("clip-g") || filenameText.contains("clipg") ||
            filenameText.contains("openclip")
        val filenameSuggestsClipL = filenameText.contains("clip_l") ||
            filenameText.contains("clip-l") || filenameText.contains("clipl")
        val containsClipG = explicitClipG ||
            (!explicitClipL && filenameSuggestsClipG && normalizedNames.isNotEmpty())
        val containsClipL = explicitClipL ||
            (!explicitClipG && !filenameSuggestsClipG && genericClip) ||
            (!explicitClipG && filenameSuggestsClipL && normalizedNames.isNotEmpty())

        val filenameSuggestsT5 = filenameText.contains("t5xxl") ||
            filenameText.contains("t5_xxl") || filenameText.contains("t5-xxl") ||
            filenameText.contains("pru-t5") || filenameText.contains("t5-v1") ||
            filenameText.contains("t5_v1") || filenameText.contains("xxl-encoder") ||
            filenameText.contains("xxl_encoder")
        val architectureMetadataText = metadata.entries
            .filter { it.key.lowercase(Locale.US).contains("architecture") }
            .joinToString(" ") { it.value.lowercase(Locale.US) }
        val containsT5 = anyBaseName(::looksLikeT5Tensor) ||
            architectureMetadataText.contains("t5") ||
            filenameSuggestsT5
        val containsLora = anyName(::looksLikeLoraTensor)
        // T5 GGUF descriptors commonly use generic `blk.*attn/ffn` names.
        // Strong T5 evidence must be resolved first and must not be promoted
        // to a generic LLM merely because the quantized tensor names look
        // llama.cpp-like.
        val containsLlm = !containsT5 && !containsClipL && !containsClipG &&
            (anyBaseName(::looksLikeLlmTensor) ||
            metadataText.contains("llama") || metadataText.contains("qwen")
            )
        val containsDiffusion = anyBaseName(::looksLikeDiffusionTensor)

        val sd3TensorEvidence = anyBaseName(::looksLikeSd3Tensor)
        val fluxTensorEvidence = anyBaseName(::looksLikeFluxTensor)
        val classicTensorEvidence = anyBaseName(::looksLikeClassicCheckpointTensor)

        val familyFromStructure = when {
            sd3TensorEvidence -> SdModelFamily.SD3
            fluxTensorEvidence -> familyFromText(metadataText, filenameText, default = SdModelFamily.FLUX_1)
            classicTensorEvidence -> SdModelFamily.CHECKPOINT
            else -> null
        }
        val familyFromMetadata = when {
            metadataText.contains("sd3") || metadataText.contains("stable-diffusion-3") -> SdModelFamily.SD3
            metadataText.contains("flux") -> familyFromText(metadataText, "", default = SdModelFamily.FLUX_1)
            containsLlm && metadataText.contains("qwen") ->
                familyFromText(metadataText, "", default = SdModelFamily.QWEN_IMAGE)
            metadataText.contains("chroma") -> familyFromText(metadataText, "", default = SdModelFamily.CHROMA)
            metadataText.contains("z-image") || metadataText.contains("z_image") -> SdModelFamily.Z_IMAGE
            metadataText.contains("ovis") -> SdModelFamily.OVIS_IMAGE
            metadataText.contains("anima") -> SdModelFamily.ANIMA
            else -> null
        }
        val familyFromFilename = when {
            filenameText.contains("sd3") || filenameText.contains("stable-diffusion-3") -> SdModelFamily.SD3
            filenameText.contains("flux") -> SdModelFamily.FLUX_1
            filenameText.contains("sdxl") -> SdModelFamily.CHECKPOINT
            filenameText.contains("sd2") -> SdModelFamily.CHECKPOINT
            else -> null
        }
        val family = familyFromStructure ?: familyFromMetadata ?: familyFromFilename
        val tensorRoleEvidence = anyBaseName(::looksLikeVaeTensor) ||
            anyBaseName(::looksLikeTaeTensor) ||
            explicitClipL || explicitClipG || genericClip ||
            anyBaseName(::looksLikeT5Tensor) ||
            anyBaseName(::looksLikeDiffusionTensor) ||
            anyBaseName(::looksLikeLlmTensor) || containsLora
        val metadataRoleEvidence = architectureMetadataText.contains("t5") ||
            metadataText.contains("llama") || metadataText.contains("qwen")
        // A complete model may omit a VAE and still carry one or more text
        // encoders in the same artifact. Standalone diffusion artifacts are
        // expected to contain diffusion/transformer tensors only. Treat
        // bundled encoder evidence as full-model evidence so a valid full SD3
        // SafeTensors file selected from the generic SD import entry is not
        // falsely rejected as a standalone model.
        val bundledFullModelEvidence = containsDiffusion && (
            containsVae || containsClipL || containsClipG || containsT5 || classicTensorEvidence
            )
        val role = when {
            bundledFullModelEvidence -> SdArtifactRole.FULL_MODEL
            containsDiffusion -> SdArtifactRole.STANDALONE_DIFFUSION
            // LoRA keys may include text-encoder/CLIP namespaces.  Once
            // diffusion and VAE payload evidence have been excluded, the
            // adapter role is authoritative over those target-module hints.
            containsLora && !containsDiffusion && !containsVae -> SdArtifactRole.LORA
            containsVae -> SdArtifactRole.VAE
            containsTae -> SdArtifactRole.TAE
            containsClipL && !containsClipG && !containsT5 -> SdArtifactRole.CLIP_L
            containsClipG && !containsClipL && !containsT5 -> SdArtifactRole.CLIP_G
            containsT5 && !containsClipL && !containsClipG -> SdArtifactRole.T5XXL
            containsLlm && !containsDiffusion -> SdArtifactRole.LLM
            else -> null
        }
        val layout = when {
            role == SdArtifactRole.FULL_MODEL -> SdMainLayout.FULL_MODEL
            role == SdArtifactRole.STANDALONE_DIFFUSION -> SdMainLayout.STANDALONE_DIFFUSION
            role != null -> SdMainLayout.COMPONENT
            else -> SdMainLayout.UNKNOWN
        }
        val confidence = when {
            familyFromStructure != null && role != null -> SdInspectionConfidence.HIGH
            familyFromStructure != null || familyFromMetadata != null ||
                tensorRoleEvidence || metadataRoleEvidence -> SdInspectionConfidence.MEDIUM
            familyFromFilename != null || role != null -> SdInspectionConfidence.LOW
            else -> SdInspectionConfidence.UNKNOWN
        }
        return Evidence(
            format = format,
            family = family,
            role = role,
            layout = layout,
            containsDiffusion = containsDiffusion,
            containsVae = containsVae,
            containsClipL = containsClipL,
            containsClipG = containsClipG,
            containsT5xxl = containsT5,
            containsLlm = containsLlm,
            confidence = confidence,
            metadata = metadata
        )
    }

    private fun familyFromText(text: String, filename: String, default: SdModelFamily): SdModelFamily = when {
        text.contains("kontext") || filename.contains("kontext") -> SdModelFamily.FLUX_KONTEXT
        text.contains("flux.2") || text.contains("flux-2") || filename.contains("flux.2") -> SdModelFamily.FLUX_2
        text.contains("radiance") || filename.contains("radiance") -> SdModelFamily.CHROMA_RADIANCE
        text.contains("qwen image edit") || text.contains("qwen-image-edit") || filename.contains("qwen-image-edit") -> SdModelFamily.QWEN_IMAGE_EDIT
        text.contains("qwen image") || text.contains("qwen-image") -> SdModelFamily.QWEN_IMAGE
        else -> default
    }

    private fun looksLikeVaeTensor(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        if (n.contains("text_model") || n.contains("clip") || n.contains("t5")) return false
        return n.contains("first_stage_model.encoder") ||
            n.contains("first_stage_model.decoder") ||
            n.startsWith("vae.") || n.contains(".vae.") ||
            n.contains("autoencoder") || n.contains("post_quant_conv") ||
            n.contains("quant_conv") ||
            ((n.contains("encoder.") || n.contains("decoder.")) &&
                (n.contains("conv") || n.contains("quant") || n.contains("mid.")))
    }

    private fun looksLikeTaeTensor(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("taesd") || n.startsWith("tae.") || n.contains(".tae.")
    }

    private fun looksLikeClipLTensor(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("clip_l") || n.contains("clip-l") ||
            n.contains("conditioner.embedders.0") ||
            n.contains("text_encoders.0") || n.contains("text_encoders.clip_l")
    }

    private fun looksLikeClipGTensor(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("clip_g") || n.contains("clip-g") ||
            n.contains("text_encoder_2") || n.contains("conditioner.embedders.1") ||
            n.contains("text_encoders.1") || n.contains("text_encoders.clip_g") ||
            n.contains("openclip")
    }

    /** Generic CLIP tensor names need filename/neighbor evidence to assign L vs G. */
    private fun looksLikeGenericClipTensor(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        if (n.contains("t5") || n.contains("clip_g") || n.contains("clip-g") ||
            n.contains("text_encoder_2") || n.contains("text_encoder_3") ||
            n.contains("text_encoders.1") || n.contains("text_encoders.2") ||
            n.contains("openclip")) {
            return false
        }
        return n.contains("text_model.encoder") ||
            n.contains("text_model.embeddings") ||
            n.contains("text_model.final_layer_norm") ||
            (n.contains("text_encoder") && !n.contains("text_encoder_2") && !n.contains("text_encoder_3"))
    }

    private fun looksLikeT5Tensor(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("t5xxl") || n.contains("t5_xxl") || n.contains("text_encoder_3") ||
            n.contains("text_encoders.2") || n.contains("text_encoders.t5") ||
            n.contains("relative_attention_bias") ||
            n.contains("shared.weight") && n.contains("encoder.block") ||
            n.contains("encoder.block") && n.contains("layer.") ||
            n.contains("transformer.encoder.block")
    }

    private fun looksLikeLoraTensor(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("lora_") || n.contains("lora.") || n.contains(".lora") ||
            n.contains("lycoris")
    }

    private fun looksLikeLlmTensor(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("llama") || n.contains("qwen") || n.contains("token_embd") ||
            n.contains("blk.") && (n.contains("attn") || n.contains("ffn"))
    }

    private fun looksLikeSd3Tensor(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("sd3") || n.contains("mmdit") || n.contains("joint_blocks") ||
            n.contains("context_embedder") || n.contains("y_embedder") ||
            n.contains("timestep_embedder") || n.contains("model.diffusion_model.joint")
    }

    private fun looksLikeFluxTensor(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("double_blocks") || n.contains("single_blocks") ||
            n.contains("guidance_in") || n.contains("img_in") || n.contains("txt_in") ||
            n.contains("flux")
    }

    private fun looksLikeDiffusionTensor(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("model.diffusion_model") || n.contains("diffusion_model.") ||
            n.contains("diffusion_model") || n.contains("joint_blocks") ||
            n.contains("double_blocks") || n.contains("single_blocks") ||
            n.contains("transformer_blocks") || n.contains("x_embedder") ||
            n.contains("context_embedder") || n.contains("img_in") || n.contains("time_in")
    }

    private fun looksLikeClassicCheckpointTensor(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("input_blocks") || n.contains("middle_block") || n.contains("output_blocks")
    }

    private fun validateSafeTensorDescriptor(descriptor: JSONObject, dataStart: Long, fileSize: Long): Boolean {
        val dtype = descriptor.optString("dtype", "")
        if (dtype.isBlank()) return false
        val shape = descriptor.optJSONArray("shape") ?: return false
        for (index in 0 until shape.length()) {
            val dimension = shape.optLong(index, -1L)
            if (dimension < 0L) return false
        }
        val offsets = descriptor.optJSONArray("data_offsets") ?: return false
        if (offsets.length() != 2) return false
        val start = offsets.optLong(0, -1L)
        val end = offsets.optLong(1, -1L)
        if (start < 0L || end < start) return false
        // Validate arithmetic and flag a payload that cannot exist. The
        // inspector still reports tensor evidence because payload bytes are
        // intentionally not read (small synthetic header fixtures often omit
        // them).
        val absoluteEnd = safelyAdd(dataStart, end) ?: return false
        return absoluteEnd >= dataStart && (absoluteEnd <= fileSize || fileSize >= dataStart)
    }

    private fun recordPrefix(prefixes: MutableSet<String>, tensorName: String) {
        if (prefixes.size >= MAX_RECORDED_PREFIXES) return
        val normalized = tensorName.trim()
        if (normalized.isBlank()) return
        val separators = charArrayOf('.', '/', ':')
        val split = normalized.indexOfAny(separators)
        prefixes += normalized.take(if (split <= 0) minOf(normalized.length, MAX_PREFIX_LENGTH) else minOf(split + 1, MAX_PREFIX_LENGTH))
    }

    private fun shouldCaptureMetadata(key: String): Boolean {
        val normalized = key.lowercase(Locale.US)
        return normalized == "general.architecture" || normalized == "general.name" ||
            normalized.contains("model_type") || normalized.contains("architecture") ||
            normalized.contains("stable_diffusion") || normalized.contains("sd3") ||
            normalized.contains("flux") || normalized.contains("qwen") ||
            normalized.contains("chroma") || normalized.contains("z_image")
    }

    private class GgufReader(
        private val raf: RandomAccessFile,
        private val fileSize: Long,
        private val digest: MessageDigest
    ) {
        fun readMagicAndVersion() {
            val magic = readBytes(4)
            if (!magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))) {
                throw InvalidArtifactException("invalid GGUF magic")
            }
            val version = readU32()
            if (version !in 2L..3L) throw InvalidArtifactException("unsupported GGUF version $version")
        }

        fun readCount(kind: String): Long {
            val count = readI64()
            if (count < 0L) throw InvalidArtifactException("negative GGUF $kind count")
            return count
        }

        fun readString(): String {
            val length = readI64()
            if (length < 0L || length > MAX_GGUF_STRING_BYTES || length > remaining()) {
                throw InvalidArtifactException("invalid GGUF string length")
            }
            val bytes = readBytes(length.toInt())
            return String(bytes, Charsets.UTF_8)
        }

        fun readMetadataValue(capture: Boolean): String? {
            return when (val type = readU32().toInt()) {
                GGUF_TYPE_UINT8 -> readU8().toString().takeIf { capture }
                GGUF_TYPE_INT8 -> readI8().toString().takeIf { capture }
                GGUF_TYPE_UINT16 -> readU16().toString().takeIf { capture }
                GGUF_TYPE_INT16 -> readI16().toString().takeIf { capture }
                GGUF_TYPE_UINT32 -> readU32().toString().takeIf { capture }
                GGUF_TYPE_INT32 -> readI32().toString().takeIf { capture }
                GGUF_TYPE_FLOAT32 -> readF32().toString().takeIf { capture }
                GGUF_TYPE_BOOL -> readU8().toString().takeIf { capture }
                GGUF_TYPE_STRING -> readString().takeIf { capture }
                GGUF_TYPE_ARRAY -> {
                    val elementType = readU32().toInt()
                    val count = readCount("array")
                    if (count > MAX_GGUF_ARRAY_VALUES) throw InvalidArtifactException("GGUF metadata array is unreasonable")
                    repeat(count.toInt()) { readMetadataArrayValue(elementType) }
                    null
                }
                GGUF_TYPE_UINT64 -> readI64().toString().takeIf { capture }
                GGUF_TYPE_INT64 -> readI64().toString().takeIf { capture }
                GGUF_TYPE_FLOAT64 -> readF64().toString().takeIf { capture }
                else -> throw InvalidArtifactException("unsupported GGUF metadata type $type")
            }
        }

        fun readTensorDescriptor() {
            val dimensions = readU32()
            if (dimensions > MAX_GGUF_DIMENSIONS) throw InvalidArtifactException("GGUF tensor has too many dimensions")
            repeat(dimensions.toInt()) { readI64().also { if (it < 0L) throw InvalidArtifactException("negative GGUF tensor dimension") } }
            readU32() // ggml_type
            readI64() // data offset; payload is deliberately not read
        }

        fun updateDigest(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }

        private fun readMetadataArrayValue(type: Int) {
            when (type) {
                GGUF_TYPE_UINT8, GGUF_TYPE_INT8, GGUF_TYPE_BOOL -> readU8()
                GGUF_TYPE_UINT16 -> readU16()
                GGUF_TYPE_INT16 -> readI16()
                GGUF_TYPE_UINT32 -> readU32()
                GGUF_TYPE_INT32 -> readI32()
                GGUF_TYPE_FLOAT32 -> readF32()
                GGUF_TYPE_STRING -> readString()
                GGUF_TYPE_UINT64, GGUF_TYPE_INT64 -> readI64()
                GGUF_TYPE_FLOAT64 -> readF64()
                else -> throw InvalidArtifactException("unsupported GGUF array type $type")
            }
        }

        private fun readBytes(count: Int): ByteArray {
            if (count < 0 || count.toLong() > remaining()) throw EOFException("truncated GGUF header")
            val bytes = ByteArray(count)
            raf.readFully(bytes)
            digest.update(bytes)
            if (raf.filePointer > MAX_GGUF_HEADER_BYTES) throw InvalidArtifactException("GGUF header exceeds safety limit")
            return bytes
        }

        private fun remaining(): Long = fileSize - raf.filePointer

        private fun readU8(): Int = readBytes(1)[0].toInt() and 0xff
        private fun readI8(): Byte = readBytes(1)[0]
        private fun readU16(): Int = ByteBuffer.wrap(readBytes(2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        private fun readI16(): Short = ByteBuffer.wrap(readBytes(2)).order(ByteOrder.LITTLE_ENDIAN).short
        private fun readU32(): Long = ByteBuffer.wrap(readBytes(4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        private fun readI32(): Int = ByteBuffer.wrap(readBytes(4)).order(ByteOrder.LITTLE_ENDIAN).int
        private fun readI64(): Long = ByteBuffer.wrap(readBytes(8)).order(ByteOrder.LITTLE_ENDIAN).long
        private fun readF32(): Float = ByteBuffer.wrap(readBytes(4)).order(ByteOrder.LITTLE_ENDIAN).float
        private fun readF64(): Double = ByteBuffer.wrap(readBytes(8)).order(ByteOrder.LITTLE_ENDIAN).double
    }

    private class InvalidArtifactException(message: String) : IOException(message)

    companion object {
        const val MAX_SAFETENSORS_HEADER_BYTES: Long = 32L * 1024L * 1024L
        const val MAX_GGUF_HEADER_BYTES: Long = 32L * 1024L * 1024L
        /** Maximum URI prefix copied into the temporary header-only file. */
        const val MAX_URI_PREFIX_BYTES: Long = MAX_GGUF_HEADER_BYTES + 8L
        const val CURRENT_INSPECTION_VERSION: Int = SdArtifactInspection.CURRENT_INSPECTION_VERSION
        private const val SAFETENSORS_LENGTH_BYTES = 8
        private const val MIN_SAFETENSORS_HEADER_BYTES = 2L
        private const val GGUF_MAGIC_BYTES = 4
        private const val MAX_GGUF_TENSORS = 2_000_000L
        private const val MAX_GGUF_METADATA = 1_000_000L
        private const val MAX_GGUF_ARRAY_VALUES = 1_000_000L
        private const val MAX_GGUF_STRING_BYTES = 1L * 1024L * 1024L
        private const val MAX_GGUF_DIMENSIONS = 8L
        private const val MAX_RECORDED_TENSOR_NAMES = 250_000
        private const val MAX_RECORDED_PREFIXES = 128
        private const val MAX_PREFIX_LENGTH = 96
        private const val MAX_METADATA_ENTRIES = 64
        private const val MAX_METADATA_KEY_LENGTH = 128
        private const val MAX_METADATA_VALUE_LENGTH = 512
        private const val MAX_WARNINGS = 32

        fun inspect(file: File, configuredRole: SdArtifactRole? = null): SdArtifactInspection =
            SdArtifactInspector().inspect(file, configuredRole)

        fun inspect(file: File, configuredType: ModelType): SdArtifactInspection =
            SdArtifactInspector().inspect(file, configuredType)

        fun inspect(path: String, configuredRole: SdArtifactRole? = null): SdArtifactInspection =
            inspect(File(path), configuredRole)

        fun inspect(path: Path, configuredRole: SdArtifactRole? = null): SdArtifactInspection =
            inspect(path.toFile(), configuredRole)

        fun inspectModel(file: File, configuredType: ModelType?): SdArtifactInspection =
            inspect(file, configuredType.toArtifactRole())

        private fun ModelType?.toArtifactRole(): SdArtifactRole? = when (this) {
            ModelType.SD_CHECKPOINT -> SdArtifactRole.FULL_MODEL
            ModelType.SD_DIFFUSION -> SdArtifactRole.STANDALONE_DIFFUSION
            ModelType.SD_VAE -> SdArtifactRole.VAE
            ModelType.SD_TAE -> SdArtifactRole.TAE
            ModelType.SD_CLIP_L -> SdArtifactRole.CLIP_L
            ModelType.SD_CLIP_G -> SdArtifactRole.CLIP_G
            ModelType.SD_T5XXL -> SdArtifactRole.T5XXL
            ModelType.SD_LORA -> SdArtifactRole.LORA
            ModelType.SD_CONTROLNET -> SdArtifactRole.CONTROLNET
            ModelType.LLM -> SdArtifactRole.LLM
            ModelType.VISION_PROJECTOR -> SdArtifactRole.LLM_VISION
            else -> null
        }

        private fun safelyAdd(left: Long, right: Long): Long? =
            if (right < 0L || left > Long.MAX_VALUE - right) null else left + right

        private fun copyBounded(
            input: java.io.InputStream,
            output: java.io.OutputStream,
            maxBytes: Long
        ): Long {
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (copied < maxBytes) {
                val requested = minOf(buffer.size.toLong(), maxBytes - copied).toInt()
                val count = input.read(buffer, 0, requested)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                copied += count
            }
            return copied
        }

        private fun suffixForDisplayName(displayName: String?): String {
            val extension = displayName
                ?.substringAfterLast('.', "")
                ?.lowercase(Locale.US)
                ?.takeIf { it.matches(Regex("[a-z0-9_]{1,16}")) }
            return when (extension) {
                "safetensors", "safe_tensors" -> ".safetensors"
                "gguf" -> ".gguf"
                "ckpt", "checkpoint", "pt", "pth", "bin" -> ".ckpt"
                else -> ".artifact"
            }
        }

        private fun hexDigest(bytes: ByteArray): String =
            bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private fun sanitizeException(prefix: String, error: Throwable): String {
            val detail = error.message?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            return if (detail.isBlank()) prefix else "$prefix: ${detail.take(220)}"
        }
    }
}

/** Focused facade for callers which only need GGUF structural inspection. */
object SdGgufArtifactInspector {
    fun inspect(file: File, configuredRole: SdArtifactRole? = null): SdArtifactInspection =
        SdArtifactInspector.inspect(file, configuredRole)
}
