package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.OnnxBundleValidator
import com.example.llamadroid.onnx.OnnxTtsBundleValidator
import com.example.llamadroid.sd.SdArtifactFormat
import com.example.llamadroid.sd.SdArtifactInspector
import com.example.llamadroid.sd.SdArtifactRole
import com.example.llamadroid.sd.SdInspectionConfidence
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

enum class ArtifactConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
}

data class ArtifactRecognitionResult(
    val family: ModelFamily? = null,
    val detectedType: String? = null,
    val role: String? = null,
    val confidence: ArtifactConfidence = ArtifactConfidence.UNKNOWN,
    val isStructurallyValid: Boolean = false,
    val requiresManualPromotion: Boolean = true,
    val validationMessage: String? = null,
    val validationJson: String? = null,
    val errorCode: ModelLibraryErrorCode? = null
) {
    val isKnown: Boolean get() = family != null && detectedType != null
}

/**
 * Bounded recognizer for staged artifacts. It intentionally never maps an
 * arbitrary extension to LLM. A caller must retain the staged row and ask for
 * manual promotion whenever evidence is incomplete.
 */
object ModelArtifactRecognizer {
    private val liteRtExtensions = setOf("litertlm", "litert", "tflite", "task", "lite")

    fun inspect(target: File): ArtifactRecognitionResult {
        if (!target.exists()) {
            return ArtifactRecognitionResult(
                validationMessage = "Artifact does not exist",
                errorCode = ModelLibraryErrorCode.RECOGNITION_FAILED
            )
        }
        if (target.isDirectory) return inspectDirectory(target)
        if (!target.isFile || !target.canRead()) {
            return ArtifactRecognitionResult(
                validationMessage = "Artifact is not a readable regular file",
                errorCode = ModelLibraryErrorCode.RECOGNITION_FAILED
            )
        }
        if (target.length() <= 0L) {
            return ArtifactRecognitionResult(
                validationMessage = "Artifact is empty",
                errorCode = ModelLibraryErrorCode.RECOGNITION_FAILED
            )
        }

        val extension = target.extension.lowercase(Locale.US)
        if (extension in setOf("gguf", "safetensors", "ckpt", "pt", "pth", "bin")) {
            val inspection = runCatching { SdArtifactInspector.inspect(target) }.getOrNull()
            if (inspection != null && inspection.isStructurallyUsable) {
                val role = inspection.detectedRole
                val confidence = inspection.confidence.toArtifactConfidence()
                val structurallyConfident = confidence == ArtifactConfidence.HIGH
                when (role) {
                    SdArtifactRole.LLM, SdArtifactRole.LLM_VISION -> {
                        return ArtifactRecognitionResult(
                            family = ModelFamily.LLM,
                            detectedType = if (role == SdArtifactRole.LLM_VISION) {
                                ModelType.VISION_PROJECTOR.name
                            } else {
                                ModelType.LLM.name
                            },
                            role = role.storedValue,
                            confidence = confidence,
                            isStructurallyValid = true,
                            requiresManualPromotion = !structurallyConfident,
                            validationMessage = if (structurallyConfident) {
                                "Valid GGUF language model structure"
                            } else {
                                "GGUF language model container is valid, but its semantic role is ambiguous"
                            },
                            validationJson = inspection.toJson(),
                            errorCode = ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED.takeUnless { structurallyConfident }
                        )
                    }
                    null -> Unit
                    else -> {
                        return ArtifactRecognitionResult(
                            family = ModelFamily.SD,
                            detectedType = modelTypeForSdRole(role).name,
                            role = role.storedValue,
                            confidence = confidence,
                            isStructurallyValid = true,
                            requiresManualPromotion = !structurallyConfident,
                            validationMessage = if (structurallyConfident) {
                                "Valid Stable Diffusion artifact structure"
                            } else {
                                "Stable Diffusion container is valid, but its semantic role is ambiguous"
                            },
                            validationJson = inspection.toJson(),
                            errorCode = ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED.takeUnless { structurallyConfident }
                        )
                    }
                }
            }
        }

        if (extension in setOf("onnx", "ort")) {
            val structurallyValid = isLikelyOnnxFile(target)
            return ArtifactRecognitionResult(
                family = ModelFamily.ONNX,
                detectedType = ModelType.ONNX_IMAGE_GEN.name,
                role = "onnx_file",
                confidence = if (structurallyValid) ArtifactConfidence.HIGH else ArtifactConfidence.UNKNOWN,
                isStructurallyValid = structurallyValid,
                requiresManualPromotion = true,
                validationMessage = if (structurallyValid) {
                    "ONNX model header validated; select a runtime role before promotion"
                } else {
                    "ONNX file header is missing or malformed"
                },
                errorCode = if (structurallyValid) {
                    ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED
                } else {
                    ModelLibraryErrorCode.RECOGNITION_FAILED
                }
            )
        }

        if (extension in liteRtExtensions) {
            val structurallyValid = isLikelyLiteRtFile(target)
            return ArtifactRecognitionResult(
                family = ModelFamily.LITERT,
                detectedType = ModelType.LLM.name,
                role = "litert_model",
                confidence = if (structurallyValid) ArtifactConfidence.HIGH else ArtifactConfidence.UNKNOWN,
                isStructurallyValid = structurallyValid,
                requiresManualPromotion = true,
                validationMessage = if (structurallyValid) {
                    "LiteRT container validated; confirm the runtime profile before promotion"
                } else {
                    "LiteRT container header is missing or malformed"
                },
                errorCode = if (structurallyValid) {
                    ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED
                } else {
                    ModelLibraryErrorCode.RECOGNITION_FAILED
                }
            )
        }

        // Whisper.cpp models are commonly distributed as `.bin`/`.ggml`, so
        // the native header must be checked independently of the filename.
        // The result remains manual because the runtime variant is user data.
        if (isLikelyWhisperFile(target)) {
            return ArtifactRecognitionResult(
                family = ModelFamily.WHISPER,
                detectedType = ModelType.WHISPER.name,
                role = "whisper_model",
                confidence = ArtifactConfidence.HIGH,
                isStructurallyValid = true,
                requiresManualPromotion = true,
                validationMessage = "Whisper.cpp model header validated; select the runtime variant",
                errorCode = ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED
            )
        }

        return ArtifactRecognitionResult(
            validationMessage = "No supported structural signature was found; keep the artifact pending",
            errorCode = ModelLibraryErrorCode.RECOGNITION_FAILED
        )
    }

    /**
     * Manual promotion is allowed only after the selected family is checked
     * again. The result remains explicit so UI can show why a row is pending.
     */
    fun validateForPromotion(target: File, family: ModelFamily, role: String? = null): ArtifactRecognitionResult {
        val observed = inspect(target)
        if (!target.exists() || !target.isFile && !target.isDirectory) return observed
        return when (family) {
            ModelFamily.SD -> validateSd(target, role)
            ModelFamily.LLM -> validateLlm(target, role)
            ModelFamily.ONNX -> validateOnnx(target)
            ModelFamily.LITERT -> validateLiteRt(target)
            ModelFamily.WHISPER -> validateWhisper(target)
        }.let { validated ->
            if (!validated.isStructurallyValid) validated
            else validated.copy(
                family = family,
                // A valid container can still require an explicit runtime
                // role (ONNX, LiteRT, Whisper, and video companions).
                requiresManualPromotion = validated.requiresManualPromotion,
                validationMessage = validated.validationMessage ?: observed.validationMessage,
                errorCode = validated.errorCode
            )
        }
    }

    private fun inspectDirectory(target: File): ArtifactRecognitionResult {
        val image = runCatching { OnnxBundleValidator.validateDirectory(target) }.getOrNull()
        if (image?.isValid == true) {
            return ArtifactRecognitionResult(
                family = ModelFamily.ONNX,
                detectedType = ModelType.ONNX_IMAGE_GEN.name,
                role = "onnx_image_bundle",
                confidence = ArtifactConfidence.HIGH,
                isStructurallyValid = true,
                requiresManualPromotion = false,
                validationMessage = "Valid ONNX image-generation bundle"
            )
        }
        val tts = runCatching { OnnxTtsBundleValidator.validateDirectory(target) }.getOrNull()
        if (tts?.isValid == true) {
            return ArtifactRecognitionResult(
                family = ModelFamily.ONNX,
                detectedType = ModelType.ONNX_TTS.name,
                role = "onnx_tts_bundle",
                confidence = ArtifactConfidence.HIGH,
                isStructurallyValid = true,
                requiresManualPromotion = false,
                validationMessage = "Valid ONNX speech bundle"
            )
        }
        val liteRtChild = target.walkTopDown().firstOrNull {
            it.isFile && it.extension.lowercase(Locale.US) in liteRtExtensions && isLikelyLiteRtFile(it)
        }
        if (liteRtChild != null) {
            return ArtifactRecognitionResult(
                family = ModelFamily.LITERT,
                detectedType = ModelType.LLM.name,
                role = "litert_package",
                confidence = ArtifactConfidence.MEDIUM,
                isStructurallyValid = true,
                requiresManualPromotion = true,
                validationMessage = "LiteRT package found; confirm runtime metadata before promotion",
                errorCode = ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED
            )
        }
        return ArtifactRecognitionResult(
            validationMessage = "No supported bundle structure was found",
            errorCode = ModelLibraryErrorCode.RECOGNITION_FAILED
        )
    }

    private fun validateSd(target: File, role: String?): ArtifactRecognitionResult {
        if (!target.isFile) return ArtifactRecognitionResult(family = ModelFamily.SD, validationMessage = "SD promotion requires a file")
        if (!role.isNullOrBlank()) {
            val structural = runCatching { SdArtifactInspector.inspect(target) }.getOrNull()
            val expectedRole = artifactRoleForManualSelection(role)
            val validContainer = structural?.isStructurallyUsable == true
            val highConfidenceContradiction = structural?.confidence == SdInspectionConfidence.HIGH &&
                expectedRole != null && structural.detectedRole != null &&
                !isCompatibleManualRole(expectedRole, structural.detectedRole)
            val valid = validContainer && !highConfidenceContradiction
            val message = when {
                !validContainer -> "Stable Diffusion container is malformed or incomplete"
                highConfidenceContradiction -> "Detected artifact role contradicts the selected role"
                requiresManualRoleSelection(ModelFamily.SD, role) ->
                    "Stable Diffusion container validated; confirm the selected video companion role"
                structural?.confidence == SdInspectionConfidence.HIGH ->
                    "Stable Diffusion artifact validated"
                else ->
                    "Stable Diffusion container parsed; the selected role remains manual"
            }
            return ArtifactRecognitionResult(
                family = ModelFamily.SD,
                detectedType = ModelSourceRepository.runtimeModelTypeFor(ModelFamily.SD, role).name,
                role = role,
                confidence = structural?.confidence.toArtifactConfidence(),
                isStructurallyValid = valid,
                requiresManualPromotion = true,
                validationMessage = message,
                validationJson = structural?.toJson(),
                errorCode = if (valid) {
                    ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED
                } else {
                    ModelLibraryErrorCode.RECOGNITION_FAILED
                }
            )
        }
        val configuredRole = role?.let { SdArtifactRole.fromStoredValue(it) }
        val inspection = runCatching { SdArtifactInspector.inspect(target, configuredRole) }.getOrNull()
        val valid = inspection?.isStructurallyUsable == true &&
            inspection.confidence == SdInspectionConfidence.HIGH &&
            (configuredRole == null || inspection.detectedRole == null ||
                configuredRole == inspection.detectedRole ||
                configuredRole == SdArtifactRole.STANDALONE_DIFFUSION && inspection.detectedRole == SdArtifactRole.FULL_MODEL)
        return ArtifactRecognitionResult(
            family = ModelFamily.SD,
            detectedType = (inspection?.detectedRole ?: configuredRole)
                ?.let(::modelTypeForSdRole)
                ?.name,
            role = inspection?.detectedRole?.storedValue ?: role,
            confidence = inspection?.confidence.toArtifactConfidence(),
            isStructurallyValid = valid,
            requiresManualPromotion = !valid,
            validationMessage = if (valid) "Stable Diffusion artifact validated" else "Stable Diffusion structure or role could not be validated",
            validationJson = inspection?.toJson(),
            errorCode = ModelLibraryErrorCode.RECOGNITION_FAILED.takeUnless { valid }
        )
    }

    private fun artifactRoleForManualSelection(role: String): SdArtifactRole? = when (normalizedModelLibraryRole(role)) {
        "full_model", "main_model", "checkpoint" -> SdArtifactRole.FULL_MODEL
        "standalone_diffusion", "diffusion", "high_noise", "highnoise",
        "high_noise_diffusion", "high_noise_diffusion_model" -> SdArtifactRole.STANDALONE_DIFFUSION
        "vae" -> SdArtifactRole.VAE
        "tae", "taesd" -> SdArtifactRole.TAE
        "clip_l" -> SdArtifactRole.CLIP_L
        "clip_g" -> SdArtifactRole.CLIP_G
        "t5xxl", "t5_xxl" -> SdArtifactRole.T5XXL
        "llm", "text_encoder", "textencoder" -> SdArtifactRole.LLM
        "llm_vision", "llmvision", "vision_projector", "mmproj" -> SdArtifactRole.LLM_VISION
        "lora" -> SdArtifactRole.LORA
        "controlnet", "control_net" -> SdArtifactRole.CONTROLNET
        "audio_vae", "audiovae" -> SdArtifactRole.AUDIO_VAE
        "embeddings_connectors", "embeddingsconnector", "connectors" -> SdArtifactRole.EMBEDDINGS_CONNECTORS
        "motion_module", "motionmodule" -> SdArtifactRole.MOTION_MODULE
        else -> null
    }

    private fun isCompatibleManualRole(expected: SdArtifactRole, detected: SdArtifactRole): Boolean = when (expected) {
        SdArtifactRole.FULL_MODEL, SdArtifactRole.MAIN_MODEL ->
            detected == SdArtifactRole.FULL_MODEL || detected == SdArtifactRole.MAIN_MODEL
        // A configured generic diffusion row may intentionally contain a full
        // checkpoint; this is the existing migration compatibility rule.
        SdArtifactRole.STANDALONE_DIFFUSION ->
            detected == SdArtifactRole.STANDALONE_DIFFUSION || detected == SdArtifactRole.FULL_MODEL
        else -> detected == expected
    }

    private fun validateLlm(target: File, role: String?): ArtifactRecognitionResult {
        if (!target.isFile) return ArtifactRecognitionResult(family = ModelFamily.LLM, validationMessage = "LLM promotion requires a file")
        val requestedRole = normalizedModelLibraryRole(role)
        val expectedRole = when (requestedRole) {
            "lora", "adapter" -> SdArtifactRole.LORA
            "llm_vision", "llmvision", "vision_projector", "vision", "clip_vision", "clipvision", "mmproj" ->
                SdArtifactRole.LLM_VISION
            else -> SdArtifactRole.LLM
        }
        val inspection = runCatching { SdArtifactInspector.inspect(target, expectedRole) }.getOrNull()
        val detectedRole = inspection?.detectedRole
        val roleMatches = when (expectedRole) {
            SdArtifactRole.LORA -> detectedRole == SdArtifactRole.LORA
            SdArtifactRole.LLM_VISION -> detectedRole == SdArtifactRole.LLM_VISION ||
                isVisionProjectorEvidence(inspection)
            else -> detectedRole == SdArtifactRole.LLM && !isVisionProjectorEvidence(inspection)
        }
        // Base, draft, and embedding rows intentionally share the GGUF LLM
        // structure. Adapters and vision projectors must still carry the
        // matching structural role; a filename or user-selected type cannot
        // turn a base model into either companion.
        val confidenceAcceptable = when (expectedRole) {
            SdArtifactRole.LORA -> inspection?.confidence in setOf(
                SdInspectionConfidence.HIGH,
                SdInspectionConfidence.MEDIUM,
                SdInspectionConfidence.LOW
            )
            else -> inspection?.confidence == SdInspectionConfidence.HIGH
        }
        val valid = inspection?.isStructurallyUsable == true && roleMatches && confidenceAcceptable
        val runtimeRole = role?.trim()?.takeIf { it.isNotBlank() }
            ?: detectedRole?.storedValue
            ?: "llm"
        val runtimeType = ModelSourceRepository.runtimeModelTypeFor(ModelFamily.LLM, runtimeRole)
        return ArtifactRecognitionResult(
            family = ModelFamily.LLM,
            detectedType = runtimeType.name,
            role = runtimeRole,
            confidence = inspection?.confidence.toArtifactConfidence(),
            isStructurallyValid = valid,
            requiresManualPromotion = !valid,
            validationMessage = when {
                valid -> "Language model GGUF validated for the selected role"
                inspection?.isStructurallyUsable != true -> "Language model GGUF structure could not be validated"
                !roleMatches -> "Detected artifact role contradicts the selected LLM role"
                else -> "Language model GGUF evidence is not strong enough for automatic promotion"
            },
            validationJson = inspection?.toJson(),
            errorCode = ModelLibraryErrorCode.RECOGNITION_FAILED.takeUnless { valid }
        )
    }

    private fun validateOnnx(target: File): ArtifactRecognitionResult {
        val image = target.takeIf { it.isDirectory }?.let { OnnxBundleValidator.validateDirectory(it) }
        val tts = target.takeIf { it.isDirectory }?.let { OnnxTtsBundleValidator.validateDirectory(it) }
        val valid = image?.isValid == true || tts?.isValid == true || isLikelyOnnxFile(target)
        return ArtifactRecognitionResult(
            family = ModelFamily.ONNX,
            detectedType = if (tts?.isValid == true) ModelType.ONNX_TTS.name else ModelType.ONNX_IMAGE_GEN.name,
            role = if (tts?.isValid == true) "onnx_tts_bundle" else "onnx_bundle",
            confidence = if (valid && target.isDirectory) ArtifactConfidence.HIGH else ArtifactConfidence.MEDIUM,
            isStructurallyValid = valid,
            requiresManualPromotion = !valid || target.isFile,
            validationMessage = if (valid) "ONNX artifact validated" else "ONNX bundle files are incomplete",
            errorCode = ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED.takeIf { target.isFile || !valid }
        )
    }

    private fun isVisionProjectorEvidence(inspection: com.example.llamadroid.sd.SdArtifactInspection?): Boolean {
        if (inspection == null) return false
        if (inspection.detectedRole == SdArtifactRole.LLM_VISION) return true
        val evidence = buildString {
            append(inspection.metadata.entries.joinToString(" ") { "${it.key} ${it.value}" })
            append(' ')
            append(inspection.tensorNamePrefixes.joinToString(" "))
        }.lowercase(Locale.US)
        return listOf(
            "mmproj", "clip_vision", "clipvision", "vision_tower",
            "image_encoder", "visual_projection", "vision_projector"
        ).any(evidence::contains)
    }

    private fun validateLiteRt(target: File): ArtifactRecognitionResult {
        val valid = when {
            target.isFile -> isLikelyLiteRtFile(target)
            target.isDirectory -> target.walkTopDown().any { it.isFile && isLikelyLiteRtFile(it) }
            else -> false
        }
        return ArtifactRecognitionResult(
            family = ModelFamily.LITERT,
            detectedType = ModelType.LLM.name,
            role = "litert_model",
            confidence = if (valid) ArtifactConfidence.HIGH else ArtifactConfidence.UNKNOWN,
            isStructurallyValid = valid,
            requiresManualPromotion = true,
            validationMessage = if (valid) "LiteRT package shape validated" else "LiteRT package is empty or unsupported",
            errorCode = if (valid) ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED else ModelLibraryErrorCode.RECOGNITION_FAILED
        )
    }

    private fun validateWhisper(target: File): ArtifactRecognitionResult {
        val valid = target.isFile && isLikelyWhisperFile(target)
        return ArtifactRecognitionResult(
            family = ModelFamily.WHISPER,
            detectedType = ModelType.WHISPER.name,
            role = "whisper_model",
            confidence = if (valid) ArtifactConfidence.HIGH else ArtifactConfidence.UNKNOWN,
            isStructurallyValid = valid,
            requiresManualPromotion = true,
            validationMessage = if (valid) "Whisper model header validated" else "Whisper model header is missing or malformed",
            errorCode = if (valid) ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED else ModelLibraryErrorCode.RECOGNITION_FAILED
        )
    }

    /**
     * Bounded ONNX ModelProto check. It validates the top-level protobuf wire
     * structure and requires both ir_version (field 1) and graph (field 7),
     * while skipping tensor payloads without reading them into memory.
     */
    private fun isLikelyOnnxFile(target: File): Boolean {
        if (!target.isFile || target.length() < 8L) return false
        return runCatching {
            java.io.RandomAccessFile(target, "r").use { file ->
                var irVersion = false
                var graph = false
                var fields = 0
                while (file.filePointer < file.length() && fields++ < MAX_PROTO_FIELDS) {
                    val tag = readVarint(file) ?: return@use false
                    val fieldNumber = tag ushr 3
                    val wireType = (tag and 0x7L).toInt()
                    if (fieldNumber !in 1L..MAX_PROTO_FIELD_NUMBER) return@use false
                    when (wireType) {
                        0 -> {
                            val value = readVarint(file) ?: return@use false
                            if (fieldNumber == 1L && value > 0L) irVersion = true
                        }
                        1 -> if (!skipBytes(file, 8L)) return@use false
                        2 -> {
                            val length = readVarint(file) ?: return@use false
                            if (length <= 0L || !skipBytes(file, length)) return@use false
                            if (fieldNumber == 7L) graph = true
                        }
                        5 -> if (!skipBytes(file, 4L)) return@use false
                        else -> return@use false
                    }
                }
                irVersion && graph
            }
        }.getOrDefault(false)
    }

    /**
     * Validate the container used by a LiteRT extension. `.litertlm` is a
     * versioned LiteRT-LM package and is not a TFLite FlatBuffer. `.task` is
     * normally a zip package, while `.tflite`/`.lite` use the TFL3 FlatBuffer.
     */
    private fun isLikelyLiteRtFile(target: File): Boolean {
        if (!target.isFile || target.length() < 8L) return false
        return when (target.extension.lowercase(Locale.US)) {
            "litertlm" -> isLikelyLiteRtLmFile(target)
            "task" -> isLikelyTaskFile(target) || isLikelyTfliteFile(target)
            else -> isLikelyTfliteFile(target)
        }
    }

    /** FlatBuffer/TFLite check with a bounded root table and vtable. */
    private fun isLikelyTfliteFile(target: File): Boolean {
        if (!target.isFile || target.length() < 16L) return false
        return runCatching {
            java.io.RandomAccessFile(target, "r").use { file ->
                file.seek(4L)
                val magic = ByteArray(4)
                file.readFully(magic)
                if (!magic.contentEquals(byteArrayOf(
                        'T'.code.toByte(), 'F'.code.toByte(),
                        'L'.code.toByte(), '3'.code.toByte()
                    ))) return@use false
                file.seek(0L)
                val root = readLittleEndianUInt32(file)
                isLikelyFlatBufferTable(file, root, 0L, file.length())
            }
        }.getOrDefault(false)
    }

    /** LiteRT-LM's prefix followed by the metadata FlatBuffer. */
    private fun isLikelyLiteRtLmFile(target: File): Boolean {
        if (!target.isFile || target.length() < LITERT_LM_PREFIX_BYTES + 8L) return false
        return runCatching {
            java.io.RandomAccessFile(target, "r").use { file ->
                val magic = ByteArray(8)
                file.readFully(magic)
                if (!magic.contentEquals("LITERTLM".toByteArray(Charsets.US_ASCII))) return@use false
                val major = readLittleEndianUInt32(file)
                val minor = readLittleEndianUInt32(file)
                val patch = readLittleEndianUInt32(file)
                val padding = readLittleEndianUInt32(file)
                val headerEnd = readLittleEndianUInt64(file)
                if (major != 1L || minor > MAX_LITERT_LM_MINOR || patch > MAX_LITERT_LM_PATCH || padding != 0L) {
                    return@use false
                }
                if (headerEnd <= LITERT_LM_PREFIX_BYTES ||
                    headerEnd > target.length() ||
                    headerEnd - LITERT_LM_PREFIX_BYTES > MAX_LITERT_LM_HEADER_BYTES
                ) return@use false
                file.seek(LITERT_LM_PREFIX_BYTES)
                val root = readLittleEndianUInt32(file)
                isLikelyFlatBufferTable(file, root, LITERT_LM_PREFIX_BYTES, headerEnd)
            }
        }.getOrDefault(false)
    }

    /**
     * A LiteRT task is a zip archive; require a structurally valid nested model
     * before allowing automatic recognition. Checking only the local header and
     * the entry name lets arbitrary bytes called `model.tflite` through.
     * Raw TFLite task files are still handled by [isLikelyTfliteFile].
     */
    private fun isLikelyTaskFile(target: File): Boolean {
        if (!target.isFile || target.length() < 4L) return false
        return runCatching {
            ZipFile(target).use { archive ->
                val entries = archive.entries()
                var inspectedEntries = 0
                while (entries.hasMoreElements() && inspectedEntries < MAX_TASK_ENTRIES) {
                    val entry = entries.nextElement()
                    inspectedEntries += 1
                    if (entry.isDirectory || entry.name.length > MAX_TASK_ENTRY_NAME_BYTES) continue
                    val name = entry.name.lowercase(Locale.US)
                    val valid = when {
                        name.endsWith(".tflite") || name.endsWith(".lite") ->
                            hasValidTaskEntry(archive, entry, ::isLikelyTflitePayload)
                        name.endsWith(".litertlm") ->
                            hasValidTaskEntry(archive, entry, ::isLikelyLiteRtLmPayload)
                        else -> false
                    }
                    if (valid) return@use true
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun hasValidTaskEntry(
        archive: ZipFile,
        entry: ZipEntry,
        validator: (ByteArray) -> Boolean
    ): Boolean {
        if (entry.size == 0L) return false
        return runCatching {
            archive.getInputStream(entry).use { input ->
                validator(readBoundedTaskPayload(input))
            }
        }.getOrDefault(false)
    }

    /** Read only the prefix needed by a structural check; never unpack a task model. */
    private fun readBoundedTaskPayload(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(minOf(MAX_TASK_PAYLOAD_SCAN_BYTES, Int.MAX_VALUE.toLong()).toInt())
        val buffer = ByteArray(TASK_PAYLOAD_READ_BUFFER_BYTES)
        var remaining = MAX_TASK_PAYLOAD_SCAN_BYTES
        while (remaining > 0L) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    /** FlatBuffer/TFLite validation for an unpacked task entry. */
    private fun isLikelyTflitePayload(payload: ByteArray): Boolean {
        if (payload.size < 16 || !payload.hasAsciiAt(4, "TFL3")) return false
        val rootOffset = payload.readLittleEndianUInt32(0)
        return isLikelyFlatBufferTable(payload, rootOffset, 0L, payload.size.toLong())
    }

    /** LiteRT-LM validation for a nested package entry. */
    private fun isLikelyLiteRtLmPayload(payload: ByteArray): Boolean {
        if (payload.size < LITERT_LM_PREFIX_BYTES + 8L) return false
        if (!payload.hasAsciiAt(0, "LITERTLM")) return false
        val major = payload.readLittleEndianUInt32(8)
        val minor = payload.readLittleEndianUInt32(12)
        val patch = payload.readLittleEndianUInt32(16)
        val padding = payload.readLittleEndianUInt32(20)
        val headerEnd = payload.readLittleEndianUInt64(24)
        if (major != 1L || minor > MAX_LITERT_LM_MINOR || patch > MAX_LITERT_LM_PATCH ||
            padding != 0L || headerEnd <= LITERT_LM_PREFIX_BYTES ||
            headerEnd > payload.size.toLong() ||
            headerEnd - LITERT_LM_PREFIX_BYTES > MAX_LITERT_LM_HEADER_BYTES
        ) return false
        val rootOffset = payload.readLittleEndianUInt32(LITERT_LM_PREFIX_BYTES.toInt())
        return isLikelyFlatBufferTable(payload, rootOffset, LITERT_LM_PREFIX_BYTES, headerEnd)
    }

    private fun isLikelyFlatBufferTable(
        payload: ByteArray,
        rootOffset: Long,
        bufferStart: Long,
        bufferEnd: Long
    ): Boolean {
        if (rootOffset < 4L || rootOffset > bufferEnd - bufferStart - 4L) return false
        val root = bufferStart + rootOffset
        if (root < bufferStart + 4L || root + 4L > bufferEnd || root > Int.MAX_VALUE) return false
        val vtableDistance = payload.readLittleEndianInt32(root.toInt())
        if (vtableDistance <= 0L || vtableDistance > root - bufferStart) return false
        val vtable = root - vtableDistance
        if (vtable < bufferStart || vtable + 4L > bufferEnd || vtable > Int.MAX_VALUE) return false
        val vtableSize = payload.readLittleEndianUInt16(vtable.toInt())
        val objectSize = payload.readLittleEndianUInt16(vtable.toInt() + 2)
        if (vtableSize < 4L || objectSize < 4L) return false
        if (vtable + vtableSize > bufferEnd || root + objectSize > bufferEnd) return false
        return vtableSize >= 6L
    }

    private fun ByteArray.hasAsciiAt(offset: Int, value: String): Boolean {
        if (offset < 0 || offset + value.length > size) return false
        return value.indices.all { index -> this[offset + index] == value[index].code.toByte() }
    }

    private fun ByteArray.readLittleEndianUInt16(offset: Int): Long {
        if (offset < 0 || offset + 2 > size) return -1L
        return (this[offset].toLong() and 0xffL) or
            ((this[offset + 1].toLong() and 0xffL) shl 8)
    }

    private fun ByteArray.readLittleEndianUInt32(offset: Int): Long {
        if (offset < 0 || offset + 4 > size) return -1L
        return (this[offset].toLong() and 0xffL) or
            ((this[offset + 1].toLong() and 0xffL) shl 8) or
            ((this[offset + 2].toLong() and 0xffL) shl 16) or
            ((this[offset + 3].toLong() and 0xffL) shl 24)
    }

    private fun ByteArray.readLittleEndianInt32(offset: Int): Long {
        val value = readLittleEndianUInt32(offset)
        return if (value >= 0L && value and 0x8000_0000L != 0L) {
            value - 0x1_0000_0000L
        } else {
            value
        }
    }

    private fun ByteArray.readLittleEndianUInt64(offset: Int): Long {
        if (offset < 0 || offset + 8 > size) return -1L
        var value = 0L
        repeat(8) { index ->
            value = value or ((this[offset + index].toLong() and 0xffL) shl (index * 8))
        }
        return value
    }

    /**
     * Check just enough FlatBuffer table structure to reject a short/fake
     * signature. `bufferStart` is the beginning of the serialized buffer;
     * LiteRT-LM stores its FlatBuffer after a 32-byte package prefix.
     */
    private fun isLikelyFlatBufferTable(
        file: java.io.RandomAccessFile,
        rootOffset: Long,
        bufferStart: Long,
        bufferEnd: Long
    ): Boolean {
        if (rootOffset < 4L || rootOffset > bufferEnd - bufferStart - 4L) return false
        val root = bufferStart + rootOffset
        if (root < bufferStart + 4L || root + 4L > bufferEnd) return false
        file.seek(root)
        val vtableDistance = readLittleEndianInt32(file).toLong()
        if (vtableDistance <= 0L || vtableDistance > root - bufferStart) return false
        val vtable = root - vtableDistance
        if (vtable < bufferStart || vtable + 4L > bufferEnd) return false
        file.seek(vtable)
        val vtableSize = readLittleEndianUInt16(file).toLong()
        val objectSize = readLittleEndianUInt16(file).toLong()
        if (vtableSize < 4L || objectSize < 4L) return false
        if (vtable + vtableSize > bufferEnd || root + objectSize > bufferEnd) return false
        // A table with no fields is not a usable model metadata/header table.
        return vtableSize >= 6L
    }

    /** Whisper.cpp GGML container check; filenames are deliberately ignored. */
    private fun isLikelyWhisperFile(target: File): Boolean {
        if (!target.isFile || target.length() < WHISPER_FIXED_HEADER_BYTES) return false
        return runCatching {
            java.io.RandomAccessFile(target, "r").use { file ->
                val magic = readLittleEndianInt32(file)
                if (magic !in WHISPER_GGML_MAGIC_VALUES) return@use false
                val nVocab = readLittleEndianInt32(file)
                val nAudioContext = readLittleEndianInt32(file)
                val nAudioState = readLittleEndianInt32(file)
                val nAudioHead = readLittleEndianInt32(file)
                val nAudioLayer = readLittleEndianInt32(file)
                val nTextContext = readLittleEndianInt32(file)
                val nTextState = readLittleEndianInt32(file)
                val nTextHead = readLittleEndianInt32(file)
                val nTextLayer = readLittleEndianInt32(file)
                val nMels = readLittleEndianInt32(file)
                val ftype = readLittleEndianInt32(file)
                val filterRows = readLittleEndianInt32(file)
                val filterColumns = readLittleEndianInt32(file)
                val positiveDimensions = listOf(
                    nVocab, nAudioContext, nAudioState, nAudioHead, nAudioLayer,
                    nTextContext, nTextState, nTextHead, nTextLayer, nMels,
                    filterRows, filterColumns
                ).all { it > 0L }
                if (!positiveDimensions ||
                    nVocab > MAX_WHISPER_VOCAB ||
                    nAudioContext > MAX_WHISPER_CONTEXT ||
                    nTextContext > MAX_WHISPER_CONTEXT ||
                    nAudioState > MAX_WHISPER_STATE ||
                    nTextState > MAX_WHISPER_STATE ||
                    nAudioHead > MAX_WHISPER_HEADS ||
                    nTextHead > MAX_WHISPER_HEADS ||
                    nAudioLayer > MAX_WHISPER_LAYERS ||
                    nTextLayer > MAX_WHISPER_LAYERS ||
                    nMels > MAX_WHISPER_MELS ||
                    filterRows > MAX_WHISPER_FILTER_DIMENSION ||
                    filterColumns > MAX_WHISPER_FILTER_DIMENSION ||
                    !isValidWhisperFtype(ftype)
                ) return@use false
                val filterBytes = filterRows * filterColumns * WHISPER_FLOAT_BYTES
                if (filterBytes <= 0L || filterBytes > MAX_WHISPER_FILTER_BYTES ||
                    !skipBytes(file, filterBytes)
                ) return@use false
                val tokenCount = readLittleEndianInt32(file)
                if (tokenCount <= 0L || tokenCount > MAX_WHISPER_VOCAB) return@use false
                for (index in 0 until tokenCount.toInt()) {
                    val tokenLength = readLittleEndianInt32(file)
                    if (tokenLength < 0L || tokenLength > MAX_WHISPER_TOKEN_BYTES) {
                        return@use false
                    }
                    // Whisper's GGML tokenizer stores a length-prefixed byte
                    // string. There is no per-token float weight in this
                    // format; the next record starts immediately after it.
                    if (!skipBytes(file, tokenLength)) {
                        return@use false
                    }
                    if (file.filePointer > MAX_WHISPER_HEADER_SCAN_BYTES) return@use false
                }
                // A valid model continues with at least one tensor descriptor.
                // Check its bounded descriptor header without reading tensor
                // payload bytes into memory.
                if (file.length() - file.filePointer < WHISPER_TENSOR_DESCRIPTOR_BYTES) return@use false
                val dimensions = readLittleEndianInt32(file)
                val nameLength = readLittleEndianInt32(file)
                val tensorType = readLittleEndianInt32(file)
                if (dimensions !in 1L..WHISPER_MAX_TENSOR_DIMENSIONS ||
                    nameLength !in 1L..MAX_WHISPER_TOKEN_BYTES ||
                    tensorType !in 0L until WHISPER_GGML_TYPE_COUNT
                ) return@use false
                for (index in 0 until dimensions.toInt()) {
                    val dimension = readLittleEndianInt32(file)
                    if (dimension <= 0L || dimension > MAX_WHISPER_TENSOR_DIMENSION) return@use false
                }
                if (!skipBytes(file, nameLength)) return@use false
                file.filePointer < file.length()
            }
        }.getOrDefault(false)
    }

    private fun isValidWhisperFtype(value: Long): Boolean {
        if (value < 0L) return false
        val quantizationVersion = value / WHISPER_QNT_VERSION_FACTOR
        val baseType = value % WHISPER_QNT_VERSION_FACTOR
        return quantizationVersion in 0L..WHISPER_MAX_QNT_VERSION &&
            baseType in WHISPER_FTYPE_VALUES
    }

    private fun readVarint(file: java.io.RandomAccessFile): Long? {
        var value = 0L
        var shift = 0
        while (shift <= 63 && file.filePointer < file.length()) {
            val byte = file.readUnsignedByte()
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
        return null
    }

    private fun skipBytes(file: java.io.RandomAccessFile, count: Long): Boolean {
        if (count < 0L || count > file.length() - file.filePointer) return false
        file.seek(file.filePointer + count)
        return true
    }

    private fun readLittleEndianUInt32(file: java.io.RandomAccessFile): Long {
        val b0 = file.readUnsignedByte().toLong()
        val b1 = file.readUnsignedByte().toLong()
        val b2 = file.readUnsignedByte().toLong()
        val b3 = file.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readLittleEndianInt32(file: java.io.RandomAccessFile): Long =
        readLittleEndianUInt32(file).let { value ->
            if (value and 0x8000_0000L != 0L) value - 0x1_0000_0000L else value
        }

    private fun readLittleEndianUInt16(file: java.io.RandomAccessFile): Int =
        file.readUnsignedByte() or (file.readUnsignedByte() shl 8)

    private fun readLittleEndianUInt64(file: java.io.RandomAccessFile): Long {
        var value = 0L
        repeat(8) { index ->
            value = value or (file.readUnsignedByte().toLong() shl (index * 8))
        }
        return value
    }

    private fun SdInspectionConfidence?.toArtifactConfidence(): ArtifactConfidence = when (this) {
        SdInspectionConfidence.HIGH -> ArtifactConfidence.HIGH
        SdInspectionConfidence.MEDIUM -> ArtifactConfidence.MEDIUM
        SdInspectionConfidence.LOW -> ArtifactConfidence.LOW
        else -> ArtifactConfidence.UNKNOWN
    }

    private fun modelTypeForSdRole(role: SdArtifactRole): ModelType = when (role) {
        SdArtifactRole.FULL_MODEL, SdArtifactRole.MAIN_MODEL -> ModelType.SD_CHECKPOINT
        SdArtifactRole.STANDALONE_DIFFUSION -> ModelType.SD_DIFFUSION
        SdArtifactRole.VAE -> ModelType.SD_VAE
        SdArtifactRole.TAE -> ModelType.SD_TAE
        SdArtifactRole.CLIP_L -> ModelType.SD_CLIP_L
        SdArtifactRole.CLIP_G -> ModelType.SD_CLIP_G
        SdArtifactRole.T5XXL -> ModelType.SD_T5XXL
        SdArtifactRole.LORA -> ModelType.SD_LORA
        SdArtifactRole.CONTROLNET -> ModelType.SD_CONTROLNET
        SdArtifactRole.AUDIO_VAE -> ModelType.SD_AUDIO_VAE
        SdArtifactRole.EMBEDDINGS_CONNECTORS -> ModelType.SD_EMBEDDINGS_CONNECTORS
        SdArtifactRole.MOTION_MODULE -> ModelType.SD_MOTION_MODULE
        SdArtifactRole.LLM, SdArtifactRole.LLM_VISION, SdArtifactRole.UNKNOWN -> ModelType.LLM
    }

    private const val MAX_PROTO_FIELDS = 512
    private const val MAX_PROTO_FIELD_NUMBER = 2048L
    private const val LITERT_LM_PREFIX_BYTES = 32L
    private const val MAX_LITERT_LM_HEADER_BYTES = 32L * 1024L * 1024L
    private const val MAX_LITERT_LM_MINOR = 100L
    private const val MAX_LITERT_LM_PATCH = 100L
    private const val WHISPER_FIXED_HEADER_BYTES = 56L
    private const val WHISPER_FLOAT_BYTES = 4L
    private const val WHISPER_TENSOR_DESCRIPTOR_BYTES = 12L
    private const val WHISPER_QNT_VERSION_FACTOR = 1000L
    private const val WHISPER_MAX_QNT_VERSION = 16L
    private const val WHISPER_GGML_TYPE_COUNT = 43L
    private const val WHISPER_MAX_TENSOR_DIMENSIONS = 4L
    private const val MAX_WHISPER_TENSOR_DIMENSION = 16_777_216L
    private const val MAX_WHISPER_VOCAB = 2_000_000L
    private const val MAX_WHISPER_CONTEXT = 1_000_000L
    private const val MAX_WHISPER_STATE = 65_536L
    private const val MAX_WHISPER_HEADS = 4_096L
    private const val MAX_WHISPER_LAYERS = 4_096L
    private const val MAX_WHISPER_MELS = 1_024L
    private const val MAX_WHISPER_FILTER_DIMENSION = 8_192L
    private const val MAX_WHISPER_FILTER_BYTES = 32L * 1024L * 1024L
    private const val MAX_WHISPER_TOKEN_BYTES = 1L * 1024L * 1024L
    private const val MAX_WHISPER_HEADER_SCAN_BYTES = 32L * 1024L * 1024L
    private const val MAX_TASK_ENTRY_NAME_BYTES = 4096
    private const val MAX_TASK_ENTRIES = 256
    private const val MAX_TASK_PAYLOAD_SCAN_BYTES = 64L * 1024L
    private const val TASK_PAYLOAD_READ_BUFFER_BYTES = 16 * 1024
    private val WHISPER_GGML_MAGIC_VALUES = setOf(
        0x67676d6cL,
        0x67676d66L,
        0x67676d74L,
        0x67676d61L
    )
    private val WHISPER_FTYPE_VALUES = setOf(
        0L, 1L, 2L, 3L, 4L, 7L, 8L, 9L, 10L, 11L, 12L, 13L,
        14L, 15L, 16L, 17L, 18L, 19L, 20L, 21L, 22L, 23L, 24L,
        25L, 26L, 27L, 28L
    )
}
