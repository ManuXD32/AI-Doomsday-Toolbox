package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.OnnxBackgroundRemovalConfig
import com.example.llamadroid.onnx.OnnxBackgroundRemovalPipeline
import com.example.llamadroid.onnx.OnnxRuntimeOptions
import com.example.llamadroid.onnx.isOnnxBackgroundRemovalModel
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class NativeChatOnnxBackgroundRemover(
    private val context: Context,
    private val database: AppDatabase
) : NativeChatBackgroundRemover {

    override suspend fun removeBackground(
        imagePath: String,
        config: NativeChatToolConfig
    ): Result<NativeChatBackgroundRemovalImage> = withContext(Dispatchers.IO) {
        try {
            val inputFile = File(imagePath.trim())
            if (!inputFile.isFile) {
                return@withContext Result.failure(Exception(context.getString(R.string.native_chat_bgr_input_missing)))
            }
            if (inputFile.extension.lowercase(Locale.US) !in SUPPORTED_IMAGE_EXTENSIONS) {
                return@withContext Result.failure(Exception(context.getString(R.string.native_chat_bgr_input_unsupported)))
            }
            val models = database.modelDao()
                .getModelsByTypesSync(listOf(ModelType.ONNX_BACKGROUND_REMOVAL))
                .filter { it.isOnnxBackgroundRemovalModel() }
            val params = config.backgroundRemovalParams
            val selectedModelId = params.model?.trim().orEmpty()
            val model = models.firstOrNull { it.filename == selectedModelId || it.path == selectedModelId }
                ?: models.firstOrNull()
                ?: return@withContext Result.failure(Exception(context.getString(R.string.native_chat_bgr_model_missing)))

            val result = OnnxBackgroundRemovalPipeline().removeBackground(
                context = context,
                config = OnnxBackgroundRemovalConfig(
                    modelPath = model.path,
                    modelName = model.filename,
                    inputPaths = listOf(inputFile.absolutePath),
                    inputNames = listOf(inputFile.name),
                    backend = params.backend,
                    runtimeOptions = OnnxRuntimeOptions(
                        runtimeThreadCount = params.runtimeThreads,
                        graphOptimizationLevel = params.graphOptimizationLevel
                    ),
                    alphaThreshold = params.alphaThreshold,
                    featherRadius = params.featherRadius,
                    maskSoftness = params.maskSoftness,
                    maskContrast = params.maskContrast,
                    exportMask = params.exportMask,
                    resizeBeforeProcessing = params.resizeBeforeProcessing,
                    resizeMaxEdge = params.resizeMaxEdge,
                    preserveSourceNames = true
                ),
                inputFile = inputFile,
                sourceName = inputFile.name,
                onDiagnostic = { DebugLog.log("[NativeChatBgR] $it") },
                onProgress = { stage, progress ->
                    DebugLog.log("[NativeChatBgR] ${stage.name.lowercase(Locale.US)} ${String.format(Locale.US, "%.0f", progress * 100f)}%")
                }
            )

            val metadata = result.metadata
            val content = buildString {
                appendLine("tool: remove_image_background")
                appendLine("status: created")
                appendLine("source_image_path: ${inputFile.absolutePath}")
                appendLine("image_path: ${result.outputFile.absolutePath}")
                appendLine("note_markdown: ![Background removed](${result.outputFile.absolutePath})")
                appendLine("model: ${model.filename}")
                appendLine("backend: ${metadata.backend}")
                appendLine("resolved_backend: ${metadata.resolvedBackend}")
                appendLine("output_resolution: ${metadata.width}x${metadata.height}")
                appendLine("original_resolution: ${metadata.originalWidth}x${metadata.originalHeight}")
                appendLine("resize_before_processing: ${metadata.resizeBeforeProcessing}")
                metadata.resizeMaxEdge?.let { appendLine("resize_max_edge: $it") }
                result.maskFile?.let { appendLine("mask_path: ${it.absolutePath}") }
                metadata.runtimeWarning?.takeIf { it.isNotBlank() }?.let { appendLine("warning: $it") }
            }.trimEnd()

            Result.success(
                NativeChatBackgroundRemovalImage(
                    content = content,
                    imagePath = result.outputFile.absolutePath
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        val SUPPORTED_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")
    }
}
