package com.example.llamadroid.ui.easy

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.sd.SdArtifactRole
import com.example.llamadroid.sd.SdMainLayout
import com.example.llamadroid.sd.SdModelFamily

/** File kind of a picked document, from its extension plus the first bytes (magic). */
enum class EasyFileKind { GGUF, SAFETENSORS, CKPT, ZIP, GGML_BIN, UNKNOWN }

/** Which importer entry point the file was picked for. */
enum class EasyImportSlot { CHAT_MODEL, CHAT_PROJECTOR, SD_MODEL, ONNX_ARCHIVE, WHISPER }

enum class EasyImportVerdict { OK, WRONG_FILE, PROJECTOR_AS_MODEL }

private fun ByteArray.startsWithAscii(text: String, offset: Int = 0): Boolean =
    size >= offset + text.length && text.indices.all { this[offset + it] == text[it].code.toByte() }

/**
 * Detects the kind of a picked file. A readable header that contradicts the extension makes the
 * file [EasyFileKind.UNKNOWN]; an empty header (unreadable provider) trusts the extension.
 */
fun detectEasyFileKind(fileName: String, header: ByteArray): EasyFileKind {
    if (header.startsWithAscii("GGUF")) return EasyFileKind.GGUF
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "gguf" -> if (header.size >= 4) EasyFileKind.UNKNOWN else EasyFileKind.GGUF
        // A safetensors file starts with an 8-byte length followed by a JSON object.
        "safetensors" ->
            if (header.size >= 9 && header[8] != '{'.code.toByte()) EasyFileKind.UNKNOWN else EasyFileKind.SAFETENSORS
        "ckpt" -> EasyFileKind.CKPT
        "zip" -> if (header.size >= 2 && !header.startsWithAscii("PK")) EasyFileKind.UNKNOWN else EasyFileKind.ZIP
        // whisper.cpp ggml files start with the little-endian magic 0x67676d6c ("lmgg").
        "bin" ->
            if (header.size < 4 || header.startsWithAscii("lmgg") || header.startsWithAscii("ggml")) {
                EasyFileKind.GGML_BIN
            } else {
                EasyFileKind.UNKNOWN
            }
        else -> EasyFileKind.UNKNOWN
    }
}

/** Vision/audio projector GGUFs are recognisable by name. */
fun isEasyProjectorName(fileName: String): Boolean {
    val lower = fileName.lowercase()
    return "mmproj" in lower || "projector" in lower
}

fun easyImportVerdict(slot: EasyImportSlot, fileName: String, header: ByteArray): EasyImportVerdict {
    val kind = detectEasyFileKind(fileName, header)
    return when (slot) {
        EasyImportSlot.CHAT_MODEL -> when {
            kind != EasyFileKind.GGUF -> EasyImportVerdict.WRONG_FILE
            isEasyProjectorName(fileName) -> EasyImportVerdict.PROJECTOR_AS_MODEL
            else -> EasyImportVerdict.OK
        }
        EasyImportSlot.CHAT_PROJECTOR ->
            if (kind == EasyFileKind.GGUF) EasyImportVerdict.OK else EasyImportVerdict.WRONG_FILE
        EasyImportSlot.SD_MODEL ->
            if ((kind == EasyFileKind.GGUF || kind == EasyFileKind.SAFETENSORS || kind == EasyFileKind.CKPT) &&
                !isEasyProjectorName(fileName)
            ) EasyImportVerdict.OK else EasyImportVerdict.WRONG_FILE
        EasyImportSlot.ONNX_ARCHIVE ->
            if (kind == EasyFileKind.ZIP) EasyImportVerdict.OK else EasyImportVerdict.WRONG_FILE
        EasyImportSlot.WHISPER ->
            if (kind == EasyFileKind.GGML_BIN) EasyImportVerdict.OK else EasyImportVerdict.WRONG_FILE
    }
}

private val EASY_VIDEO_FAMILIES = setOf(
    SdModelFamily.WAN, SdModelFamily.HUNYUAN_VIDEO, SdModelFamily.LINGBOT_VIDEO, SdModelFamily.LTX_VIDEO,
    SdModelFamily.MINIMAX_H3, SdModelFamily.SVD, SdModelFamily.ANIMATEDIFF
)

/** True when [family] is an image family Easy can generate with (video families are not). */
fun isEasyImageFamily(family: SdModelFamily?): Boolean = family == null || family !in EASY_VIDEO_FAMILIES

/**
 * Chooses the stored type of an inspected stable-diffusion.cpp file: full checkpoints and files of
 * unknown layout become `SD_CHECKPOINT`, bare diffusion weights become `SD_DIFFUSION`, and
 * side components (VAE, text encoders, LoRA, ...) return null because Easy cannot use them alone.
 */
fun easySdImportType(role: SdArtifactRole?, layout: SdMainLayout): ModelType? = when (role) {
    SdArtifactRole.FULL_MODEL, SdArtifactRole.MAIN_MODEL -> ModelType.SD_CHECKPOINT
    SdArtifactRole.STANDALONE_DIFFUSION -> ModelType.SD_DIFFUSION
    null, SdArtifactRole.UNKNOWN -> when (layout) {
        SdMainLayout.STANDALONE_DIFFUSION -> ModelType.SD_DIFFUSION
        SdMainLayout.COMPONENT -> null
        else -> ModelType.SD_CHECKPOINT
    }
    else -> null
}

/**
 * Easy lists a model when it is downloaded, or when it is an imported file that still exists
 * (advanced imports store `isDownloaded=false`; Easy must not rewrite those rows).
 */
fun com.example.llamadroid.data.db.ModelEntity.isEasyUsable(): Boolean =
    isDownloaded || ((repoId == com.example.llamadroid.data.db.ModelBackupPolicy.LOCAL_IMPORT_REPO_ID ||
        repoId.startsWith("custom-import/")) && java.io.File(path).exists())
