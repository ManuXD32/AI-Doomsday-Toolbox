package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.GenerationQueueItemEntity
import com.example.llamadroid.data.binary.BinaryRepository
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

data class PreparedGenerationQueueItem(
    val id: String,
    val kind: String,
    val mode: String,
    val promptPreview: String,
    val configJson: String
)

/** Persist complete, versioned requests rather than restoring mutable form drafts at run time. */
object GenerationQueueSnapshot {
    const val IMAGE = "IMAGE"
    const val UPSCALE = "UPSCALE"
    const val VIDEO = "VIDEO"
    private const val SCHEMA_VERSION = 1
    private val gson = Gson()

    fun encode(kind: String, config: Any): String = JSONObject()
        .put("version", SCHEMA_VERSION)
        .put("kind", kind)
        .put("config", JSONObject(gson.toJson(config)))
        .toString()

    fun decodeImage(json: String): SDConfig = decode(json, IMAGE, SDConfig::class.java)
    fun decodeUpscale(json: String): SDUpscaleConfig = decode(json, UPSCALE, SDUpscaleConfig::class.java)
    fun decodeVideo(json: String): VideoGenerationConfig = decode(json, VIDEO, VideoGenerationConfig::class.java)

    private fun <T> decode(json: String, kind: String, type: Class<T>): T {
        val objectValue = JSONObject(json)
        require(objectValue.getInt("version") == SCHEMA_VERSION) { "Unsupported queued request version" }
        require(objectValue.getString("kind") == kind) { "Queued request type mismatch" }
        return requireNotNull(gson.fromJson(objectValue.getJSONObject("config").toString(), type))
    }

    suspend fun image(context: Context, original: SDConfig): PreparedGenerationQueueItem =
        withContext(Dispatchers.IO) { prepareImage(context, original, selectedBinary(context)) }

    private suspend fun prepareImage(context: Context, original: SDConfig, binary: String): PreparedGenerationQueueItem =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val stage = InputStage(context, id)
            try {
                val output = uniqueOutput(original.outputPath, id)
                val config = original.copy(
                    outputPath = output,
                    initImage = stage.copy(original.initImage),
                    referenceImages = original.referenceImages.map { stage.copy(it)!! },
                    maskImage = stage.copy(original.maskImage),
                    controlImagePath = stage.copy(original.controlImagePath),
                    ipAdapter = original.ipAdapter?.let { it.copy(imagePath = stage.copy(it.imagePath)!!) },
                    sdBinaryPathOverride = binary
                )
                PreparedGenerationQueueItem(id, IMAGE, config.operation ?: config.mode.name,
                    config.prompt.take(160), encode(IMAGE, config))
            } catch (error: Exception) {
                stage.discard()
                throw error
            }
        }

    suspend fun upscale(context: Context, original: SDUpscaleConfig): PreparedGenerationQueueItem =
        withContext(Dispatchers.IO) { prepareUpscale(context, original, selectedBinary(context)) }

    private suspend fun prepareUpscale(context: Context, original: SDUpscaleConfig, binary: String): PreparedGenerationQueueItem =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val stage = InputStage(context, id)
            try {
                val config = original.copy(
                    outputPath = uniqueOutput(original.outputPath, id),
                    inputImagePath = stage.copy(original.inputImagePath)!!,
                    sdBinaryPathOverride = binary
                )
                PreparedGenerationQueueItem(id, UPSCALE, SDMode.UPSCALE.name,
                    File(original.inputImagePath).name.take(160), encode(UPSCALE, config))
            } catch (error: Exception) {
                stage.discard()
                throw error
            }
        }

    suspend fun video(context: Context, original: VideoGenerationConfig): PreparedGenerationQueueItem =
        withContext(Dispatchers.IO) { prepareVideo(context, original, selectedBinary(context)) }

    private suspend fun prepareVideo(context: Context, original: VideoGenerationConfig, binary: String): PreparedGenerationQueueItem =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val stage = InputStage(context, id)
            try {
                val inputs = original.videoInputs.copy(
                    initImagePath = stage.copy(original.videoInputs.initImagePath ?: original.initImagePath),
                    endImagePath = stage.copy(original.videoInputs.endImagePath),
                    controlImagePath = stage.copy(original.videoInputs.controlImagePath),
                    controlVideoPath = stage.copy(original.videoInputs.controlVideoPath),
                    referenceImages = original.videoInputs.referenceImages.map { stage.copy(it)!! },
                    referenceVideos = original.videoInputs.referenceVideos.map { stage.copy(it)!! },
                    referenceVideoAudios = original.videoInputs.referenceVideoAudios.map { stage.copy(it)!! },
                    referenceAudios = original.videoInputs.referenceAudios.map { stage.copy(it)!! },
                    ipAdapterImagePath = stage.copy(original.videoInputs.ipAdapterImagePath)
                )
                val config = original.copy(
                    outputAviPath = uniqueOutput(original.outputAviPath, id),
                    outputMp4Path = uniqueOutput(original.outputMp4Path, id),
                    metadataPath = uniqueOutput(original.metadataPath, id),
                    nativeOutputPath = original.nativeOutputPath?.let { uniqueOutput(it, id) },
                    initImagePath = inputs.initImagePath,
                    videoInputs = inputs,
                    sdBinaryPathOverride = binary
                )
                PreparedGenerationQueueItem(id, VIDEO, config.mode.name,
                    config.prompt.take(160), encode(VIDEO, config))
            } catch (error: Exception) {
                stage.discard()
                throw error
            }
        }

    /** Re-stage saved inputs without resolving a new binary or changing generation settings. */
    suspend fun retry(context: Context, item: GenerationQueueItemEntity): PreparedGenerationQueueItem {
        return try {
            when (item.kind) {
                IMAGE -> decodeImage(item.configJson).let { config ->
                    requireRetryFile(config.modelPath, GenerationQueueRetryIssue.MODEL)
                    prepareImage(context, config, requireRetryFile(config.sdBinaryPathOverride,
                        GenerationQueueRetryIssue.BINARY))
                }
                UPSCALE -> decodeUpscale(item.configJson).let { config ->
                    requireRetryFile(config.modelPath, GenerationQueueRetryIssue.MODEL)
                    prepareUpscale(context, config, requireRetryFile(config.sdBinaryPathOverride,
                        GenerationQueueRetryIssue.BINARY))
                }
                VIDEO -> decodeVideo(item.configJson).let { config ->
                    requireRetryFile(config.diffusionModelPath, GenerationQueueRetryIssue.MODEL)
                    prepareVideo(context, config, requireRetryFile(config.sdBinaryPathOverride,
                        GenerationQueueRetryIssue.BINARY))
                }
                else -> throw GenerationQueueRetryException(GenerationQueueRetryIssue.UNAVAILABLE)
            }
        } catch (error: GenerationQueueRetryException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            val issue = if (error.message?.startsWith("Queued input is unavailable:") == true) {
                GenerationQueueRetryIssue.INPUT
            } else GenerationQueueRetryIssue.UNAVAILABLE
            throw GenerationQueueRetryException(issue, error)
        } catch (error: IOException) {
            throw GenerationQueueRetryException(GenerationQueueRetryIssue.STORAGE, error)
        } catch (error: IllegalStateException) {
            throw GenerationQueueRetryException(GenerationQueueRetryIssue.STORAGE, error)
        } catch (error: Exception) {
            throw GenerationQueueRetryException(GenerationQueueRetryIssue.UNAVAILABLE, error)
        }
    }

    private fun requireRetryFile(path: String?, issue: GenerationQueueRetryIssue): String {
        val file = path?.takeIf { it.isNotBlank() }?.let(::File)
        if (file == null || !file.isFile || !file.canRead()) throw GenerationQueueRetryException(issue)
        return file.absolutePath
    }

    private fun selectedBinary(context: Context): String =
        BinaryRepository(context).getSdBinary()?.takeIf { it.isFile && it.canRead() }?.absolutePath
            ?: error("Stable Diffusion binary is unavailable")

    private fun uniqueOutput(originalPath: String, id: String): String {
        val original = File(originalPath)
        val extension = original.extension.ifBlank { "png" }
        val stem = original.nameWithoutExtension
            .replace(Regex("_queue_[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"), "")
            .ifBlank { "generation" }
        return File(original.parentFile, "${stem}_queue_${id}.$extension").absolutePath
    }

    private class InputStage(context: Context, id: String) {
        private val folder = File(context.filesDir, "generation_queue_inputs/$id")
        private val staged = mutableMapOf<String, String>()

        fun copy(path: String?): String? {
            val sourcePath = path?.takeIf { it.isNotBlank() } ?: return null
            val source = File(sourcePath)
            require((source.isFile || source.isDirectory) && source.canRead()) {
                "Queued input is unavailable: ${source.name}"
            }
            return staged.getOrPut(source.canonicalPath) {
                check(folder.exists() || folder.mkdirs()) { "Cannot create queue input storage" }
                val target = File(folder, "${staged.size}_${source.name}")
                if (source.isDirectory) {
                    check(source.copyRecursively(target, overwrite = false)) {
                        "Cannot copy queued input directory: ${source.name}"
                    }
                } else {
                    source.copyTo(target)
                }
                target.absolutePath
            }
        }

        fun discard() {
            // This folder contains only copies made for this not-yet-saved queue item.
            folder.deleteRecursively()
        }
    }
}

enum class GenerationQueueRetryIssue { BINARY, MODEL, INPUT, STORAGE, UNAVAILABLE }

class GenerationQueueRetryException(val issue: GenerationQueueRetryIssue, cause: Throwable? = null) :
    IllegalStateException("Queued retry is unavailable: $issue", cause)
