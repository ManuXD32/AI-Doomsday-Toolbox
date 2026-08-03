package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val filename: String,
    val path: String,
    val sizeBytes: Long,
    val type: ModelType,
    val repoId: String, // HuggingFace repo ID
    val isDownloaded: Boolean = false,
    val isVision: Boolean = false, // Whether this is a vision-capable model (for LLM)
    val mmprojPath: String? = null, // Path to the mmproj file for vision models
    // SD-specific fields
    val sdCapabilities: String? = null, // Comma-separated: "txt2img,img2img,upscale"
    val sdFamily: String? = null,
    val sdVariant: String? = null,
    val sdCompatProfiles: String? = null,
    val sdParamsBackendMode: String = "auto",
    val sdRuntimeBackendMode: String = "auto",
    // ONNX-specific fields
    val onnxCapabilities: String? = null, // Comma-separated: "txt2img,img2img"
    val onnxAssetKind: String? = null,
    val onnxPipelineFamily: String? = null,
    val onnxReferenceUri: String? = null,
    val onnxReferencePath: String? = null,
    // Layer count for distributed inference
    val layerCount: Int = 0  // Number of transformer layers (from GGUF metadata)
)

const val SD_CAPABILITY_TXT2IMG = "txt2img"
const val SD_CAPABILITY_IMG2IMG = "img2img"
const val SD_CAPABILITY_UPSCALE = "upscale"
const val SD_CAPABILITY_VID_GEN = "vid_gen"
const val ONNX_CAPABILITY_TXT2IMG = "txt2img"
const val ONNX_CAPABILITY_IMG2IMG = "img2img"
const val ONNX_CAPABILITY_TTS = "tts"
const val ONNX_CAPABILITY_BACKGROUND_REMOVAL = "background_removal"
const val QUADTRIX_LORA_REPO_PREFIX = "quadtrix/lora/"

fun String?.parseSdCapabilities(): Set<String> =
    this
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        ?: emptySet()

fun buildSdCapabilities(vararg tokens: String?): String? {
    val normalized = tokens
        .mapNotNull { it?.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    return normalized.joinToString(",").ifBlank { null }
}

fun ModelEntity.sdCapabilityTokens(): Set<String> = sdCapabilities.parseSdCapabilities()

fun ModelEntity.hasSdCapability(token: String): Boolean = sdCapabilityTokens().contains(token)

fun String?.parseOnnxCapabilities(): Set<String> =
    this
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        ?: emptySet()

fun buildOnnxCapabilities(vararg tokens: String?): String? {
    val normalized = tokens
        .mapNotNull { it?.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    return normalized.joinToString(",").ifBlank { null }
}

fun ModelEntity.onnxCapabilityTokens(): Set<String> = onnxCapabilities.parseOnnxCapabilities()

fun ModelEntity.hasOnnxCapability(token: String): Boolean = onnxCapabilityTokens().contains(token)

fun ModelEntity.isLegacyQuadtrixLoraAdapter(): Boolean =
    type == ModelType.QUADTRIX && repoId.startsWith(QUADTRIX_LORA_REPO_PREFIX)

enum class ModelType {
    // LLM types
    LLM,
    LORA,             // llama.cpp GGUF LoRA adapter
    EMBEDDING,
    VISION,
    VISION_PROJECTOR,
    MMPROJ,           // Multi-modal projector file
    // Stable Diffusion types
    SD_CHECKPOINT,    // Base SD model (supports txt2img, img2img) - SD1.5/SDXL
    SD_VAE,           // Standalone VAE for SD
    SD_LORA,          // LoRA for SD
    SD_TEXTUAL_INVERSION, // Prompt embedding for stable-diffusion.cpp
    SD_UPSCALER,      // ESRGAN upscaling model
    // FLUX-specific types (multi-component architecture)
    SD_DIFFUSION,     // Standalone diffusion/transformer model (FLUX)
    SD_CLIP_L,        // CLIP-L text encoder (for FLUX)
    SD_CLIP_G,        // CLIP-G text encoder (for SD3)
    SD_T5XXL,         // T5-XXL text encoder (for FLUX)
    SD_TAE,           // TAE/TAESD latent decoder
    SD_CONTROLNET,    // ControlNet conditioning model
    SD_PHOTOMAKER,    // PhotoMaker adapter weights
    // ONNX media types
    ONNX_IMAGE_GEN,           // End-to-end image generation model
    ONNX_BACKGROUND_REMOVAL,  // Background-removal / alpha-mask model
    ONNX_IMAGE_UPSCALER,      // Image upscaler model
    ONNX_TTS,                 // ONNX text-to-speech bundle
    // Whisper types
    WHISPER,          // WhisperCPP speech-to-text model
    // Quadtrix.cpp training checkpoints
    QUADTRIX          // Native Quadtrix checkpoint; not compatible with llama.cpp/GGUF
}
