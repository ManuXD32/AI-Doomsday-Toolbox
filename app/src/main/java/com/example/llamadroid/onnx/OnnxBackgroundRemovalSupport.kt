package com.example.llamadroid.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val BGR_INPUT_SIZE = 1024
private const val BGR_RUN_HEARTBEAT_MS = 5_000L
private val BGR_IMAGE_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val BGR_IMAGE_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

@Parcelize
data class OnnxBackgroundRemovalConfig(
    val modelPath: String,
    val modelName: String,
    val inputPaths: List<String>,
    val inputNames: List<String>,
    val backend: OnnxRuntimeBackend = OnnxRuntimeBackend.CPU,
    val runtimeOptions: OnnxRuntimeOptions = OnnxRuntimeOptions(),
    val alphaThreshold: Float = 0.5f,
    val featherRadius: Int = 1,
    val maskSoftness: Float = 1f,
    val maskContrast: Float = 1f,
    val exportMask: Boolean = false,
    val resizeBeforeProcessing: Boolean = true,
    val resizeMaxEdge: Int = 512,
    val preserveSourceNames: Boolean = true
) : Parcelable

data class OnnxBackgroundRemovalResult(
    val outputFile: File,
    val maskFile: File?,
    val metadata: OnnxBackgroundRemovalMetadata,
    val runtimeSummary: OnnxRuntimeComponentSummary
)

enum class OnnxBackgroundRemovalStage {
    DECODING_IMAGE,
    LOADING_MODEL,
    PREPARING_TENSOR,
    RUNNING_MODEL,
    READING_MASK,
    POSTPROCESSING_MASK,
    SAVING_OUTPUT,
    COMPLETE
}

@Serializable
data class OnnxBackgroundRemovalRuntimeState(
    val state: String,
    val progress: Float = 0f,
    val status: String = "",
    val completed: Int = 0,
    val total: Int = 0,
    val outputPaths: List<String> = emptyList(),
    val failed: Int = 0,
    val durationMs: Long = 0L,
    val message: String? = null,
    val startedAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class OnnxBackgroundRemovalMetadata(
    val outputPath: String,
    val maskPath: String? = null,
    val sourceName: String,
    val sourcePath: String,
    val modelName: String,
    val backend: String,
    val resolvedBackend: String,
    val runtimeWarning: String? = null,
    val alphaThreshold: Float,
    val featherRadius: Int,
    val maskSoftness: Float,
    val maskContrast: Float,
    val exportMask: Boolean,
    val width: Int,
    val height: Int,
    val originalWidth: Int = width,
    val originalHeight: Int = height,
    val resizeBeforeProcessing: Boolean = false,
    val resizeMaxEdge: Int? = null,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val sharedOutputRelativePath: String? = null,
    val sharedMetadataRelativePath: String? = null,
    val sharedMaskRelativePath: String? = null,
    val warningMessage: String? = null
) {
    fun toJsonString(): String = OnnxBackgroundRemovalStorage.json.encodeToString(this)

    companion object {
        fun fromJson(rawJson: String): OnnxBackgroundRemovalMetadata =
            OnnxBackgroundRemovalStorage.json.decodeFromString(rawJson)
    }
}

object OnnxBackgroundRemovalStorage {
    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun outputDir(context: Context): File = File(context.filesDir, "bgr_output")

    fun runtimeStateFile(context: Context): File = File(outputDir(context), "runtime_state.json")

    fun buildOutputFile(context: Context, sourceName: String, preserveSourceName: Boolean): File {
        val dir = outputDir(context).apply { mkdirs() }
        val safeBase = sourceName.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .ifBlank { "image" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = if (preserveSourceName) "${safeBase}_bgr_$timestamp.png" else "bgr_$timestamp.png"
        return File(dir, name)
    }

    fun metadataFileFor(imageFile: File): File = File(imageFile.parentFile, "${imageFile.name}.json")

    fun maskFileFor(imageFile: File): File = File(imageFile.parentFile, "${imageFile.nameWithoutExtension}_mask.png")

    fun writeMetadata(imageFile: File, metadata: OnnxBackgroundRemovalMetadata) {
        metadataFileFor(imageFile).writeText(metadata.toJsonString())
    }

    fun readMetadata(imageFile: File): OnnxBackgroundRemovalMetadata? {
        val sidecar = metadataFileFor(imageFile)
        if (!sidecar.isFile) return null
        return runCatching { OnnxBackgroundRemovalMetadata.fromJson(sidecar.readText()) }.getOrNull()
    }

    fun deleteImageWithMetadata(imageFile: File): Boolean {
        val metadata = metadataFileFor(imageFile)
        val mask = maskFileFor(imageFile)
        val imageDeleted = !imageFile.exists() || imageFile.delete()
        if (metadata.exists()) metadata.delete()
        if (mask.exists()) mask.delete()
        return imageDeleted
    }

    fun listOutputs(context: Context): List<File> {
        return outputDir(context)
            .listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) && !it.nameWithoutExtension.endsWith("_mask") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun writeRuntimeState(context: Context, state: OnnxBackgroundRemovalRuntimeState) {
        val file = runtimeStateFile(context)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(state.copy(updatedAtEpochMs = System.currentTimeMillis())))
    }

    fun readRuntimeState(context: Context): OnnxBackgroundRemovalRuntimeState? {
        val file = runtimeStateFile(context)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<OnnxBackgroundRemovalRuntimeState>(file.readText()) }.getOrNull()
    }

    fun clearRuntimeState(context: Context) {
        runtimeStateFile(context).delete()
    }
}

class OnnxBackgroundRemovalPipeline {
    fun removeBackground(
        context: Context,
        config: OnnxBackgroundRemovalConfig,
        inputFile: File,
        sourceName: String,
        onDiagnostic: (String) -> Unit = {},
        onProgress: (OnnxBackgroundRemovalStage, Float) -> Unit = { _, _ -> }
    ): OnnxBackgroundRemovalResult {
        val start = System.currentTimeMillis()
        onProgress(OnnxBackgroundRemovalStage.DECODING_IMAGE, 0.02f)
        val decodedSource = decodeSourceBitmap(inputFile, config)
        val sourceBitmap = decodedSource.bitmap
        if (decodedSource.resized) {
            onDiagnostic(
                "resized source ${decodedSource.originalWidth}x${decodedSource.originalHeight} -> " +
                    "${sourceBitmap.width}x${sourceBitmap.height} maxEdge=${config.resizeMaxEdge.coerceAtLeast(1)}"
            )
        }
        val outputFile = OnnxBackgroundRemovalStorage.buildOutputFile(
            context = context,
            sourceName = sourceName,
            preserveSourceName = config.preserveSourceNames
        )
        val environment = OrtEnvironmentProvider.environment
        try {
            onProgress(OnnxBackgroundRemovalStage.LOADING_MODEL, 0.10f)
            createBackgroundRemovalSession(
                environment = environment,
                modelFile = File(config.modelPath),
                config = config,
            ).use { sessionResult ->
                onDiagnostic(
                    "session loaded model=${config.modelName} requested=${config.backend.name} " +
                        "resolved=${sessionResult.summary.resolvedBackend} warning=${sessionResult.summary.warningMessage.orEmpty()}"
                )
                onProgress(OnnxBackgroundRemovalStage.PREPARING_TENSOR, 0.22f)
                val input = prepareInput(sourceBitmap)
                val inputName = sessionResult.session.inputNames.first()
                OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(input.tensor),
                    longArrayOf(1, 3, BGR_INPUT_SIZE.toLong(), BGR_INPUT_SIZE.toLong())
                ).use { tensor ->
                    onDiagnostic("running input=$inputName source=${sourceBitmap.width}x${sourceBitmap.height}")
                    onProgress(OnnxBackgroundRemovalStage.RUNNING_MODEL, 0.38f)
                    runBackgroundRemovalSession(
                        session = sessionResult.session,
                        inputs = mapOf(inputName to tensor),
                        modelName = config.modelName,
                        onDiagnostic = onDiagnostic
                    ).use { result ->
                        onProgress(OnnxBackgroundRemovalStage.READING_MASK, 0.72f)
                        val outputInfos = result.mapIndexed { index, entry ->
                            BgrOutputInfo(
                                index = index,
                                name = entry.key,
                                shape = tensorShape(entry.value),
                                type = entry.value.info?.javaClass?.simpleName.orEmpty()
                            )
                        }
                        val selectedOutput = selectMaskOutput(outputInfos)
                        onDiagnostic(
                            "outputs=${outputInfos.joinToString { it.toDiagnosticString() }} " +
                                "selected=#${selectedOutput.index}:${selectedOutput.name}"
                        )
                        val rawMask = extractMask(result[selectedOutput.index])
                        val rawStats = rawMask.values.statsString()
                        val normalizedMask = normalizeMask(rawMask.values, config.modelName)
                        onDiagnostic(
                            "mask output=${rawMask.width}x${rawMask.height} values=${rawMask.values.size} " +
                                "raw=$rawStats normalized=${normalizedMask.statsString()}"
                        )
                        val croppedMask = cropMask(
                            mask = normalizedMask,
                            maskWidth = rawMask.width,
                            maskHeight = rawMask.height,
                            rect = input.fittedRect
                        )
                        val fittedMask = resizeMaskBilinear(
                            mask = croppedMask.values,
                            sourceWidth = croppedMask.width,
                            sourceHeight = croppedMask.height,
                            targetWidth = sourceBitmap.width,
                            targetHeight = sourceBitmap.height
                        )
                        onProgress(OnnxBackgroundRemovalStage.POSTPROCESSING_MASK, 0.82f)
                        val alpha = postProcessAlpha(
                            mask = fittedMask,
                            width = sourceBitmap.width,
                            height = sourceBitmap.height,
                            threshold = config.alphaThreshold,
                            featherRadius = config.featherRadius,
                            softness = config.maskSoftness,
                            contrast = config.maskContrast
                        )
                        onProgress(OnnxBackgroundRemovalStage.SAVING_OUTPUT, 0.92f)
                        writeTransparentPng(sourceBitmap, alpha, outputFile)
                        val maskFile = if (config.exportMask) {
                            OnnxBackgroundRemovalStorage.maskFileFor(outputFile).also {
                                writeMaskPng(alpha, sourceBitmap.width, sourceBitmap.height, it)
                            }
                        } else {
                            null
                        }
                        val durationMs = System.currentTimeMillis() - start
                        val metadata = OnnxBackgroundRemovalMetadata(
                            outputPath = outputFile.absolutePath,
                            maskPath = maskFile?.absolutePath,
                            sourceName = sourceName,
                            sourcePath = inputFile.absolutePath,
                            modelName = config.modelName,
                            backend = config.backend.name,
                            resolvedBackend = sessionResult.summary.resolvedBackend,
                            runtimeWarning = sessionResult.summary.warningMessage,
                            alphaThreshold = config.alphaThreshold,
                            featherRadius = config.featherRadius,
                            maskSoftness = config.maskSoftness,
                            maskContrast = config.maskContrast,
                            exportMask = config.exportMask,
                            width = sourceBitmap.width,
                            height = sourceBitmap.height,
                            originalWidth = decodedSource.originalWidth,
                            originalHeight = decodedSource.originalHeight,
                            resizeBeforeProcessing = config.resizeBeforeProcessing,
                            resizeMaxEdge = config.resizeMaxEdge.takeIf { config.resizeBeforeProcessing },
                            createdAtEpochMs = System.currentTimeMillis(),
                            durationMs = durationMs
                        )
                        OnnxBackgroundRemovalStorage.writeMetadata(outputFile, metadata)
                        onDiagnostic("background_removal output=${outputFile.name} size=${sourceBitmap.width}x${sourceBitmap.height}")
                        onProgress(OnnxBackgroundRemovalStage.COMPLETE, 1f)
                        return OnnxBackgroundRemovalResult(outputFile, maskFile, metadata, sessionResult.summary)
                    }
                }
            }
        } finally {
            if (!sourceBitmap.isRecycled) sourceBitmap.recycle()
        }
    }
}

private fun createBackgroundRemovalSession(
    environment: OrtEnvironment,
    modelFile: File,
    config: OnnxBackgroundRemovalConfig
): OnnxSessionLoadResult {
    return createOnnxSessionWithBackend(
        environment = environment,
        modelFile = modelFile,
        requestedBackend = config.backend,
        runtimeOptions = config.runtimeOptions,
        componentLabel = "background_removal",
        loadOrtFormat = false
    )
}

private fun runBackgroundRemovalSession(
    session: ai.onnxruntime.OrtSession,
    inputs: Map<String, OnnxTensor>,
    modelName: String,
    onDiagnostic: (String) -> Unit
): ai.onnxruntime.OrtSession.Result {
    val executor = Executors.newSingleThreadExecutor()
    try {
        val start = System.currentTimeMillis()
        val future = executor.submit<ai.onnxruntime.OrtSession.Result> {
            session.run(inputs)
        }
        while (true) {
            try {
                return future.get(BGR_RUN_HEARTBEAT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                val elapsedMs = System.currentTimeMillis() - start
                onDiagnostic("running_model heartbeat elapsedMs=$elapsedMs model=$modelName")
            }
        }
    } finally {
        executor.shutdownNow()
    }
}

data class OnnxBgrMask(val values: FloatArray, val width: Int, val height: Int)

private data class BgrOutputInfo(
    val index: Int,
    val name: String,
    val shape: List<Int>,
    val type: String
) {
    fun toDiagnosticString(): String {
        val shapeText = shape.joinToString("x").ifBlank { "unknown" }
        return "#$index:$name:$shapeText:$type"
    }
}

fun postProcessAlpha(
    mask: FloatArray,
    width: Int,
    height: Int,
    threshold: Float,
    featherRadius: Int,
    softness: Float,
    contrast: Float
): IntArray {
    val safeThreshold = threshold.coerceIn(0.01f, 0.99f)
    val safeSoftness = softness.coerceIn(0f, 1f)
    val safeContrast = contrast.coerceIn(0.25f, 4f)
    val alpha = IntArray(mask.size) { index ->
        val contrasted = ((mask[index].coerceIn(0f, 1f) - 0.5f) * safeContrast + 0.5f).coerceIn(0f, 1f)
        val hard = if (contrasted >= safeThreshold) 1f else 0f
        ((hard * (1f - safeSoftness) + contrasted * safeSoftness) * 255f).roundToInt().coerceIn(0, 255)
    }
    return if (featherRadius > 0) blurAlpha(alpha, width, height, featherRadius.coerceIn(0, 12)) else alpha
}

private data class PreparedInput(
    val tensor: FloatArray,
    val fittedRect: FittedRect
)

private data class DecodedSourceBitmap(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    val resized: Boolean
)

private data class FittedRect(val left: Int, val top: Int, val width: Int, val height: Int)

private fun decodeSourceBitmap(inputFile: File, config: OnnxBackgroundRemovalConfig): DecodedSourceBitmap {
    if (!config.resizeBeforeProcessing) {
        val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
            ?: error("Could not decode ${inputFile.name}")
        return DecodedSourceBitmap(bitmap, bitmap.width, bitmap.height, resized = false)
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(inputFile.absolutePath, bounds)
    val originalWidth = bounds.outWidth.takeIf { it > 0 } ?: 0
    val originalHeight = bounds.outHeight.takeIf { it > 0 } ?: 0
    if (originalWidth <= 0 || originalHeight <= 0) {
        val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
            ?: error("Could not decode ${inputFile.name}")
        return DecodedSourceBitmap(bitmap, bitmap.width, bitmap.height, resized = false)
    }

    val targetMaxEdge = config.resizeMaxEdge.coerceAtLeast(1)
    val originalMaxEdge = max(originalWidth, originalHeight)
    if (originalMaxEdge <= targetMaxEdge) {
        val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
            ?: error("Could not decode ${inputFile.name}")
        return DecodedSourceBitmap(bitmap, originalWidth, originalHeight, resized = false)
    }

    val sampled = BitmapFactory.decodeFile(
        inputFile.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = calculateDecodeSampleSize(originalWidth, originalHeight, targetMaxEdge)
        }
    ) ?: error("Could not decode ${inputFile.name}")
    val sampledMaxEdge = max(sampled.width, sampled.height)
    if (sampledMaxEdge <= targetMaxEdge) {
        return DecodedSourceBitmap(sampled, originalWidth, originalHeight, resized = sampled.width != originalWidth || sampled.height != originalHeight)
    }

    val scale = targetMaxEdge.toFloat() / sampledMaxEdge.toFloat()
    val targetWidth = max(1, (sampled.width * scale).roundToInt())
    val targetHeight = max(1, (sampled.height * scale).roundToInt())
    val resized = Bitmap.createScaledBitmap(sampled, targetWidth, targetHeight, true)
    if (resized !== sampled) sampled.recycle()
    return DecodedSourceBitmap(resized, originalWidth, originalHeight, resized = true)
}

private fun calculateDecodeSampleSize(width: Int, height: Int, targetMaxEdge: Int): Int {
    var sampleSize = 1
    var sampledMaxEdge = max(width, height)
    while (sampledMaxEdge / 2 >= targetMaxEdge) {
        sampleSize *= 2
        sampledMaxEdge /= 2
    }
    return sampleSize.coerceAtLeast(1)
}

private fun prepareInput(source: Bitmap): PreparedInput {
    val inputBitmap = Bitmap.createBitmap(BGR_INPUT_SIZE, BGR_INPUT_SIZE, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(inputBitmap)
    canvas.drawColor(Color.WHITE)
    val scale = min(BGR_INPUT_SIZE.toFloat() / source.width.toFloat(), BGR_INPUT_SIZE.toFloat() / source.height.toFloat())
    val fittedWidth = max(1, (source.width * scale).roundToInt())
    val fittedHeight = max(1, (source.height * scale).roundToInt())
    val left = (BGR_INPUT_SIZE - fittedWidth) / 2
    val top = (BGR_INPUT_SIZE - fittedHeight) / 2
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val destination = android.graphics.Rect(left, top, left + fittedWidth, top + fittedHeight)
    canvas.drawBitmap(source, null, destination, paint)
    val pixels = IntArray(BGR_INPUT_SIZE * BGR_INPUT_SIZE)
    inputBitmap.getPixels(pixels, 0, BGR_INPUT_SIZE, 0, 0, BGR_INPUT_SIZE, BGR_INPUT_SIZE)
    val plane = BGR_INPUT_SIZE * BGR_INPUT_SIZE
    val tensor = FloatArray(plane * 3)
    pixels.forEachIndexed { index, color ->
        tensor[index] = (((color shr 16) and 0xFF) / 255f - BGR_IMAGE_MEAN[0]) / BGR_IMAGE_STD[0]
        tensor[plane + index] = (((color shr 8) and 0xFF) / 255f - BGR_IMAGE_MEAN[1]) / BGR_IMAGE_STD[1]
        tensor[plane * 2 + index] = ((color and 0xFF) / 255f - BGR_IMAGE_MEAN[2]) / BGR_IMAGE_STD[2]
    }
    inputBitmap.recycle()
    return PreparedInput(tensor, FittedRect(left, top, fittedWidth, fittedHeight))
}

private fun tensorShape(value: OnnxValue): List<Int> {
    val tensorInfo = value.info as? TensorInfo ?: return emptyList()
    return tensorInfo.shape.map { it.toInt() }
}

private fun selectMaskOutput(outputs: List<BgrOutputInfo>): BgrOutputInfo {
    require(outputs.isNotEmpty()) { "Model did not return any outputs" }
    return outputs.lastOrNull { it.hasPlausibleMaskShape() } ?: outputs.last()
}

private fun BgrOutputInfo.hasPlausibleMaskShape(): Boolean {
    val dims = shape.filter { it > 0 }
    if (dims.size >= 4) {
        val d1 = dims[dims.size - 3]
        val d2 = dims[dims.size - 2]
        val d3 = dims[dims.size - 1]
        if (d1 in 1..4 && d2 >= 16 && d3 >= 16) return true
        if (d1 >= 16 && d2 >= 16 && d3 in 1..4) return true
    }
    if (dims.size >= 3) {
        val d0 = dims[dims.size - 3]
        val d1 = dims[dims.size - 2]
        val d2 = dims[dims.size - 1]
        if (d0 in 1..4 && d1 >= 16 && d2 >= 16) return true
        if (d0 >= 16 && d1 >= 16 && d2 in 1..4) return true
    }
    val spatial = dims.takeLast(2)
    return spatial.size == 2 && spatial[0] >= 16 && spatial[1] >= 16
}

private fun extractMask(value: OnnxValue): OnnxBgrMask {
    val values = extractFloatTensor(value.value)
    val shape = tensorShape(value)
    return extractMaskFromValues(values, shape)
}

internal fun extractMaskFromValues(values: FloatArray, shape: List<Int>): OnnxBgrMask {
    val dims = shape.filter { it > 0 }
    if (dims.size >= 4) {
        val batch = dims[dims.size - 4]
        val d1 = dims[dims.size - 3]
        val d2 = dims[dims.size - 2]
        val d3 = dims[dims.size - 1]
        if (batch >= 1 && d1 in 1..4 && d2 > 4 && d3 > 4) {
            return extractNchwMask(values, channels = d1, height = d2, width = d3)
        }
        if (batch >= 1 && d1 > 4 && d2 > 4 && d3 in 1..4) {
            return extractNhwcMask(values, height = d1, width = d2, channels = d3)
        }
    }
    if (dims.size >= 3) {
        val d0 = dims[dims.size - 3]
        val d1 = dims[dims.size - 2]
        val d2 = dims[dims.size - 1]
        if (d0 in 1..4 && d1 > 4 && d2 > 4) {
            return extractNchwMask(values, channels = d0, height = d1, width = d2)
        }
        if (d0 > 4 && d1 > 4 && d2 in 1..4) {
            return extractNhwcMask(values, height = d0, width = d1, channels = d2)
        }
    }
    if (dims.size >= 2) {
        val height = dims[dims.size - 2]
        val width = dims[dims.size - 1]
        val pixels = height * width
        if (pixels > 0 && values.size >= pixels) {
            return OnnxBgrMask(values.copyOfRange(0, pixels), width, height)
        }
    }
    val inferredSide = sqrt(values.size.toDouble()).roundToInt().takeIf { it * it == values.size }
    val height = inferredSide ?: BGR_INPUT_SIZE
    val width = inferredSide ?: (values.size / height).coerceAtLeast(1)
    val pixels = (width * height).coerceAtMost(values.size)
    return OnnxBgrMask(values.copyOfRange(0, pixels), width, height)
}

private fun extractNchwMask(values: FloatArray, channels: Int, height: Int, width: Int): OnnxBgrMask {
    val pixels = width * height
    require(values.size >= pixels) { "Tensor output does not contain a full mask plane" }
    if (channels == 1) {
        return OnnxBgrMask(values.copyOfRange(0, pixels), width, height)
    }
    if (channels >= 4) {
        val offset = (3 * pixels).coerceAtMost(values.size - pixels)
        return OnnxBgrMask(values.copyOfRange(offset, offset + pixels), width, height)
    }
    val mask = FloatArray(pixels) { index ->
        var sum = 0f
        for (channel in 0 until channels) {
            sum += values[channel * pixels + index]
        }
        sum / channels.toFloat()
    }
    return OnnxBgrMask(mask, width, height)
}

private fun extractNhwcMask(values: FloatArray, height: Int, width: Int, channels: Int): OnnxBgrMask {
    val pixels = width * height
    require(values.size >= pixels * channels) { "Tensor output does not contain a full interleaved mask" }
    val mask = FloatArray(pixels) { index ->
        if (channels >= 4) {
            values[index * channels + 3]
        } else {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += values[index * channels + channel]
            }
            sum / channels.toFloat()
        }
    }
    return OnnxBgrMask(mask, width, height)
}

private fun extractFloatTensor(value: Any?): FloatArray {
    return when (value) {
        is FloatArray -> value
        is DoubleArray -> FloatArray(value.size) { value[it].toFloat() }
        is Array<*> -> value.flatMap { extractFloatTensor(it).asIterable() }.toFloatArray()
        is OnnxTensor -> extractFloatTensor(value.value)
        null -> error("Tensor output was null")
        else -> error("Unsupported tensor output type: ${value::class.java.name}")
    }
}

internal fun normalizeMask(mask: FloatArray, modelName: String = ""): FloatArray {
    val minValue = mask.minOrNull() ?: 0f
    val maxValue = mask.maxOrNull() ?: 1f
    val activated = if (minValue < 0f || maxValue > 1f) {
        FloatArray(mask.size) { index -> (1f / (1f + exp(-mask[index]))).coerceIn(0f, 1f) }
    } else {
        FloatArray(mask.size) { index -> mask[index].coerceIn(0f, 1f) }
    }
    return if (requiresMinMaxMaskNormalization(modelName)) {
        minMaxNormalizeMask(activated)
    } else {
        activated
    }
}

private fun requiresMinMaxMaskNormalization(modelName: String): Boolean {
    return modelName.contains("ben2", ignoreCase = true) ||
        modelName.contains("background_removal__ben", ignoreCase = true)
}

private fun minMaxNormalizeMask(mask: FloatArray): FloatArray {
    val minValue = mask.minOrNull() ?: 0f
    val maxValue = mask.maxOrNull() ?: 1f
    val range = maxValue - minValue
    if (range <= 1e-6f) return mask
    return FloatArray(mask.size) { index ->
        ((mask[index] - minValue) / range).coerceIn(0f, 1f)
    }
}

private fun FloatArray.statsString(): String {
    if (isEmpty()) return "empty"
    val minValue = minOrNull() ?: 0f
    val maxValue = maxOrNull() ?: 0f
    val mean = sum() / size.toFloat()
    return "min=${"%.4f".format(Locale.US, minValue)} max=${"%.4f".format(Locale.US, maxValue)} mean=${"%.4f".format(Locale.US, mean)}"
}

private fun cropMask(mask: FloatArray, maskWidth: Int, maskHeight: Int, rect: FittedRect): OnnxBgrMask {
    val scaleX = maskWidth.toFloat() / BGR_INPUT_SIZE.toFloat()
    val scaleY = maskHeight.toFloat() / BGR_INPUT_SIZE.toFloat()
    val left = (rect.left * scaleX).roundToInt().coerceIn(0, maskWidth - 1)
    val top = (rect.top * scaleY).roundToInt().coerceIn(0, maskHeight - 1)
    val width = (rect.width * scaleX).roundToInt().coerceIn(1, maskWidth - left)
    val height = (rect.height * scaleY).roundToInt().coerceIn(1, maskHeight - top)
    val cropped = FloatArray(width * height)
    for (y in 0 until height) {
        System.arraycopy(mask, (top + y) * maskWidth + left, cropped, y * width, width)
    }
    return OnnxBgrMask(cropped, width, height)
}

private fun resizeMaskBilinear(
    mask: FloatArray,
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): FloatArray {
    val output = FloatArray(targetWidth * targetHeight)
    for (y in 0 until targetHeight) {
        val sourceY = if (targetHeight == 1) 0f else y * (sourceHeight - 1).toFloat() / (targetHeight - 1).toFloat()
        val y0 = sourceY.toInt().coerceIn(0, sourceHeight - 1)
        val y1 = (y0 + 1).coerceAtMost(sourceHeight - 1)
        val yWeight = sourceY - y0
        for (x in 0 until targetWidth) {
            val sourceX = if (targetWidth == 1) 0f else x * (sourceWidth - 1).toFloat() / (targetWidth - 1).toFloat()
            val x0 = sourceX.toInt().coerceIn(0, sourceWidth - 1)
            val x1 = (x0 + 1).coerceAtMost(sourceWidth - 1)
            val xWeight = sourceX - x0
            val top = mask[y0 * sourceWidth + x0] * (1f - xWeight) + mask[y0 * sourceWidth + x1] * xWeight
            val bottom = mask[y1 * sourceWidth + x0] * (1f - xWeight) + mask[y1 * sourceWidth + x1] * xWeight
            output[y * targetWidth + x] = top * (1f - yWeight) + bottom * yWeight
        }
    }
    return output
}

private fun blurAlpha(alpha: IntArray, width: Int, height: Int, radius: Int): IntArray {
    if (radius <= 0) return alpha
    val output = IntArray(alpha.size)
    for (y in 0 until height) {
        val yMin = (y - radius).coerceAtLeast(0)
        val yMax = (y + radius).coerceAtMost(height - 1)
        for (x in 0 until width) {
            val xMin = (x - radius).coerceAtLeast(0)
            val xMax = (x + radius).coerceAtMost(width - 1)
            var sum = 0
            var count = 0
            for (sampleY in yMin..yMax) {
                for (sampleX in xMin..xMax) {
                    sum += alpha[sampleY * width + sampleX]
                    count++
                }
            }
            output[y * width + x] = (sum / count).coerceIn(0, 255)
        }
    }
    return output
}

private fun writeTransparentPng(source: Bitmap, alpha: IntArray, outputFile: File) {
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (index in pixels.indices) {
        pixels[index] = (alpha[index] shl 24) or (pixels[index] and 0x00FFFFFF)
    }
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    output.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    outputFile.parentFile?.mkdirs()
    FileOutputStream(outputFile).use { stream -> output.compress(Bitmap.CompressFormat.PNG, 100, stream) }
    output.recycle()
}

private fun writeMaskPng(alpha: IntArray, width: Int, height: Int, outputFile: File) {
    val pixels = IntArray(alpha.size) { index ->
        val value = alpha[index].coerceIn(0, 255)
        Color.argb(255, value, value, value)
    }
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    output.setPixels(pixels, 0, width, 0, 0, width, height)
    outputFile.parentFile?.mkdirs()
    FileOutputStream(outputFile).use { stream -> output.compress(Bitmap.CompressFormat.PNG, 100, stream) }
    output.recycle()
}
