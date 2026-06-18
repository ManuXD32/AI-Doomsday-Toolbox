package com.example.llamadroid.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Locale
import java.util.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val IMG2IMG_VAE_LATENT_SCALE = 0.18215f
private const val IMG2IMG_UNET_INFERENCE_START_PROGRESS = 0.22f
private const val IMG2IMG_UNET_INFERENCE_END_PROGRESS = 0.92f
internal const val ONNX_IMG2IMG_CANVAS_SIZE = 512

data class OnnxImg2ImgPreprocessInfo(
    val originalWidth: Int,
    val originalHeight: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val fittedWidth: Int,
    val fittedHeight: Int,
    val paddingLeft: Int,
    val paddingTop: Int
)

private data class OnnxImg2ImgPreparedBitmap(
    val bitmap: Bitmap,
    val preprocessInfo: OnnxImg2ImgPreprocessInfo
)

data class OnnxImg2ImgResult(
    val outputFile: File,
    val seedUsed: Long,
    val warningMessage: String? = null,
    val runtimeSummary: OnnxRuntimeExecutionSummary,
    val preprocessInfo: OnnxImg2ImgPreprocessInfo
)

class OnnxImg2ImgPipeline {
    suspend fun generate(
        config: OnnxImageGenConfig,
        onProgress: (Float, String) -> Unit,
        onDiagnostic: (String) -> Unit = {}
    ): OnnxImg2ImgResult {
        currentCoroutineContext().ensureActive()
        require(config.width % 8 == 0 && config.height % 8 == 0) {
            "Width and height must be divisible by 8"
        }
        require(config.steps > 0) {
            "Steps must be greater than 0"
        }
        require(config.width == ONNX_IMG2IMG_CANVAS_SIZE && config.height == ONNX_IMG2IMG_CANVAS_SIZE) {
            "ONNX img2img requires a fixed ${ONNX_IMG2IMG_CANVAS_SIZE}x${ONNX_IMG2IMG_CANVAS_SIZE} canvas"
        }
        val initImagePath = config.initImagePath ?: error("Missing init image for ONNX img2img")
        val strength = (config.strength ?: ONNX_IMAGE_GEN_DEFAULT_STRENGTH).coerceIn(0.1f, 0.95f)
        val seed = if (config.seed >= 0L) config.seed else System.currentTimeMillis()
        val random = Random(seed)

        val bundle = OnnxBundleValidator.requirePaths(File(config.modelPath), OnnxImageGenMode.IMG2IMG)
        val promptWarnings = mutableListOf<String>()
        val tokenizer = ClipBpeTokenizer(bundle.tokenizerVocab, bundle.tokenizerMerges)
        onDiagnostic("tokenizer=clip_bpe max_tokens=$CLIP_MAX_TOKENS")

        onProgress(0.03f, "Loading init image")
        val sourceBitmap = BitmapFactory.decodeFile(initImagePath)
            ?: error("Could not decode init image")
        val preparedBitmap = prepareBitmapForLatentEncoding(source = sourceBitmap)
        onDiagnostic(
            "img2img_source original=${preparedBitmap.preprocessInfo.originalWidth}x${preparedBitmap.preprocessInfo.originalHeight} " +
                "fitted=${preparedBitmap.preprocessInfo.fittedWidth}x${preparedBitmap.preprocessInfo.fittedHeight} " +
                "canvas=${preparedBitmap.preprocessInfo.canvasWidth}x${preparedBitmap.preprocessInfo.canvasHeight} " +
                "padding=${preparedBitmap.preprocessInfo.paddingLeft},${preparedBitmap.preprocessInfo.paddingTop}"
        )
        currentCoroutineContext().ensureActive()

        val environment = OrtEnvironmentProvider.environment
        onProgress(0.06f, "Loading text encoder")
        createOnnxCpuSession(environment, bundle.textEncoderModel, config.runtimeOptions).use { textEncoder ->
            currentCoroutineContext().ensureActive()
            onProgress(0.10f, "Loading VAE encoder")
            val vaeEncoderBackend = config.runtimeOptions.backendFor("vae_encoder", config.backend)
            createOnnxSessionWithBackend(
                environment = environment,
                modelFile = bundle.vaeEncoderModel ?: error("Missing VAE encoder"),
                requestedBackend = vaeEncoderBackend,
                runtimeOptions = config.runtimeOptions,
                componentLabel = "vae_encoder"
            ).use { vaeEncoderResult ->
                currentCoroutineContext().ensureActive()
                onProgress(0.12f, "Loading UNet and VAE")
                val unetBackend = config.runtimeOptions.backendFor("unet", config.backend)
                val vaeDecoderBackend = config.runtimeOptions.backendFor("vae_decoder", config.backend)
                createOnnxSessionWithBackend(
                    environment = environment,
                    modelFile = bundle.unetModel,
                    requestedBackend = unetBackend,
                    runtimeOptions = config.runtimeOptions,
                    componentLabel = "unet"
                ).use { unetResult ->
                    createOnnxSessionWithBackend(
                        environment = environment,
                        modelFile = bundle.vaeDecoderModel,
                        requestedBackend = vaeDecoderBackend,
                        runtimeOptions = config.runtimeOptions,
                        componentLabel = "vae_decoder"
                    ).use { vaeResult ->
                        listOfNotNull(
                            vaeEncoderResult.summary.warningMessage,
                            unetResult.summary.warningMessage,
                            vaeResult.summary.warningMessage
                        )
                            .forEach(promptWarnings::add)
                        currentCoroutineContext().ensureActive()
                        onDiagnostic(
                            "session_backends text_encoder=CPU vae_encoder=${vaeEncoderResult.summary.resolvedBackend} " +
                                "unet=${unetResult.summary.resolvedBackend} vae_decoder=${vaeResult.summary.resolvedBackend}"
                        )

                        onProgress(0.15f, "Encoding prompts")
                        val conditionalTokenization = tokenizer.tokenize(config.prompt)
                        val negativeTokenization = tokenizer.tokenize(config.negativePrompt)
                        onDiagnostic(
                            "prompt_preprocess conditional=\"${conditionalTokenization.normalizedText.take(200)}\" " +
                                "negative=\"${negativeTokenization.normalizedText.take(200)}\""
                        )
                        onDiagnostic(
                            "tokens conditional=${conditionalTokenization.tokenCount}" +
                                if (conditionalTokenization.wasTruncated) " truncated" else "" +
                                " negative=${negativeTokenization.tokenCount}" +
                                if (negativeTokenization.wasTruncated) " truncated" else "" +
                                " conditional_edges=${conditionalTokenization.tokenIds.first()}..${conditionalTokenization.tokenIds.last()} " +
                                "negative_edges=${negativeTokenization.tokenIds.first()}..${negativeTokenization.tokenIds.last()}"
                        )
                        val conditionalEmbedding = encodePrompt(
                            session = textEncoder,
                            tokenization = conditionalTokenization,
                            onDiagnostic = onDiagnostic
                        )
                        currentCoroutineContext().ensureActive()
                        val unconditionalEmbedding = encodePrompt(
                            session = textEncoder,
                            tokenization = negativeTokenization,
                            onDiagnostic = onDiagnostic
                        )
                        currentCoroutineContext().ensureActive()
                        onDiagnostic(
                            "prompt_embeddings conditional=${conditionalEmbedding.size} unconditional=${unconditionalEmbedding.size}"
                        )
                        onDiagnostic("cfg_guidance order=unconditional_then_conditional scale=${config.cfgScale}")

                        val embeddingDim = (conditionalEmbedding.size / CLIP_MAX_TOKENS).coerceAtLeast(1)
                        val promptEmbeddings = FloatArray(conditionalEmbedding.size + unconditionalEmbedding.size)
                        unconditionalEmbedding.copyInto(promptEmbeddings, 0)
                        conditionalEmbedding.copyInto(promptEmbeddings, unconditionalEmbedding.size)

                        onProgress(0.18f, "Encoding init image")
                        val vaeEncoderInputName = resolveVaeEncoderInputName(vaeEncoderResult.session)
                        onDiagnostic("vae_encoder_input=$vaeEncoderInputName")
                        val encodedLatents = encodeInitImage(
                            session = vaeEncoderResult.session,
                            inputName = vaeEncoderInputName,
                            bitmap = preparedBitmap,
                            width = config.width,
                            height = config.height,
                            random = random,
                            onDiagnostic = onDiagnostic
                        )

                        val latentChannels = 4
                        val latentWidth = config.width / 8
                        val latentHeight = config.height / 8
                        val scheduler = OnnxEulerAncestralScheduler(
                            inferenceSteps = config.steps,
                            seed = seed,
                            predictionType = OnnxPredictionType.EPSILON
                        )
                        val img2imgSchedule = scheduler.resolveImg2ImgSchedule(strength)
                        val activeTimesteps = img2imgSchedule.timesteps
                        onDiagnostic(
                            "scheduler=euler_ancestral_img2img prediction=epsilon steps=${config.steps} strength=$strength " +
                                "effective_steps=${img2imgSchedule.activeStepCount} " +
                                "start_index=${img2imgSchedule.startIndex} first_sigma=${scheduler.sigmaAt(img2imgSchedule.startIndex)} " +
                                "first_timestep=${activeTimesteps.firstOrNull()} " +
                                "last_timestep=${activeTimesteps.lastOrNull()}"
                        )
                        onDiagnostic("latent_scale=$IMG2IMG_VAE_LATENT_SCALE latent_shape=4x${latentHeight}x${latentWidth}")
                        val noise = createInitialLatents(
                            channels = latentChannels,
                            height = latentHeight,
                            width = latentWidth,
                            random = random
                        )
                        var latents = scheduler.addNoise(
                            sample = encodedLatents,
                            noise = noise,
                            stepIndex = img2imgSchedule.startIndex
                        )
                        currentCoroutineContext().ensureActive()
                        onProgress(0.20f, "Preparing latents")
                        val unetInputs = resolveUnetInputNames(unetResult.session)
                        onDiagnostic(
                            "unet_inputs sample=${unetInputs.sampleName} timestep=${unetInputs.timestepName} " +
                                "encoder_hidden_states=${unetInputs.embeddingName}"
                        )
                        val decoderInputName = resolveVaeDecoderInputName(vaeResult.session)
                        onDiagnostic("vae_decoder_input=$decoderInputName")

                        activeTimesteps.forEachIndexed { index, timestep ->
                            currentCoroutineContext().ensureActive()
                            val denoiseProgress = IMG2IMG_UNET_INFERENCE_START_PROGRESS +
                                ((index + 1).toFloat() / activeTimesteps.size.toFloat()) *
                                (IMG2IMG_UNET_INFERENCE_END_PROGRESS - IMG2IMG_UNET_INFERENCE_START_PROGRESS)
                            onProgress(denoiseProgress, "UNet step ${index + 1}/${activeTimesteps.size}")

                            val schedulerIndex = img2imgSchedule.startIndex + index
                            val modelInput = duplicateLatents(scheduler.scaleModelInput(latents, schedulerIndex))
                            val noisePrediction = runUnet(
                                session = unetResult.session,
                                inputNames = unetInputs,
                                latents = modelInput,
                                batchSize = 2,
                                channels = latentChannels,
                                height = latentHeight,
                                width = latentWidth,
                                timestep = timestep,
                                promptEmbeddings = promptEmbeddings,
                                embeddingDim = embeddingDim
                            )
                            val guidedNoise = applyClassifierFreeGuidance(
                                noisePrediction = noisePrediction,
                                sampleSize = latents.size,
                                guidanceScale = config.cfgScale
                            )
                            latents = scheduler.step(
                                sample = latents,
                                modelOutput = guidedNoise,
                                stepIndex = schedulerIndex
                            )
                            yield()
                        }

                        currentCoroutineContext().ensureActive()
                        onProgress(0.96f, "Decoding VAE")
                        val bitmap = decodeLatents(
                            session = vaeResult.session,
                            latentInputName = decoderInputName,
                            latents = latents,
                            width = config.width,
                            height = config.height
                        )

                        val outputFile = File(config.outputPath)
                        outputFile.parentFile?.mkdirs()
                        onProgress(0.985f, "Saving PNG")
                        FileOutputStream(outputFile).use { output ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        }
                        currentCoroutineContext().ensureActive()
                        onProgress(1f, "Complete")

                        val warning = promptWarnings.joinToString("\n").ifBlank { null }
                        return OnnxImg2ImgResult(
                            outputFile = outputFile,
                            seedUsed = seed,
                            warningMessage = warning,
                            runtimeSummary = OnnxRuntimeExecutionSummary(
                                textEncoder = OnnxRuntimeComponentSummary(
                                    component = "text_encoder",
                                    requestedBackend = OnnxRuntimeBackend.CPU.name,
                                    resolvedBackend = OnnxRuntimeBackend.CPU.name
                                ),
                                unet = unetResult.summary,
                                vaeDecoder = vaeResult.summary,
                                vaeEncoder = vaeEncoderResult.summary,
                                options = config.runtimeOptions
                            ),
                            preprocessInfo = preparedBitmap.preprocessInfo
                        )
                    }
                }
            }
        }
    }

    private fun encodePrompt(
        session: OrtSession,
        tokenization: ClipTokenization,
        onDiagnostic: (String) -> Unit
    ): FloatArray {
        val tensors = mutableListOf<OnnxTensor>()
        try {
            val explicitInputName = session.inputInfo.keys.firstOrNull {
                it.equals("input_ids", ignoreCase = true)
            }
            val inputs = if (explicitInputName != null) {
                val info = session.inputInfo[explicitInputName]?.info as TensorInfo
                val tensor = createIntTensor(info, tokenization.tokenIds, shapeForTextInput(info, tokenization.tokenIds.size))
                tensors += tensor
                onDiagnostic("text_encoder_inputs=input_ids")
                mapOf(explicitInputName to tensor)
            } else {
                val fallbackInputs = buildMap {
                    session.inputInfo.forEach { (inputName, nodeInfo) ->
                        val info = nodeInfo.info as TensorInfo
                        val loweredName = inputName.lowercase(Locale.US)
                        val data = when {
                            loweredName.contains("mask") -> tokenization.attentionMask
                            loweredName.contains("position") -> tokenization.positionIds
                            else -> tokenization.tokenIds
                        }
                        val tensor = createIntTensor(info, data, shapeForTextInput(info, data.size))
                        tensors += tensor
                        put(inputName, tensor)
                    }
                }
                onDiagnostic("text_encoder_inputs=fallback:${fallbackInputs.keys.joinToString(",")}")
                fallbackInputs
            }
            session.run(inputs).use { result ->
                return extractFloatTensor(result[0].value)
            }
        } finally {
            tensors.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun encodeInitImage(
        session: OrtSession,
        inputName: String,
        bitmap: OnnxImg2ImgPreparedBitmap,
        width: Int,
        height: Int,
        random: Random,
        onDiagnostic: (String) -> Unit
    ): FloatArray {
        val imageTensor = createFloatTensor(
            data = bitmapToNormalizedChw(bitmap.bitmap),
            shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        )
        imageTensor.use {
            session.run(mapOf(inputName to imageTensor)).use { result ->
                val outputNames = session.outputInfo.keys.toList()
                val outputs = buildList {
                    for (index in 0 until result.size()) {
                        add(
                            OnnxNamedTensorOutput(
                                name = outputNames.getOrNull(index),
                                data = extractFloatTensor(result[index].value)
                            )
                        )
                    }
                }
                return resolveEncodedLatents(outputs, width, height, random, onDiagnostic)
            }
        }
    }

    private fun resolveEncodedLatents(
        outputs: List<OnnxNamedTensorOutput>,
        width: Int,
        height: Int,
        random: Random,
        onDiagnostic: (String) -> Unit
    ): FloatArray {
        val latentSize = 4 * (width / 8) * (height / 8)
        outputs.forEach { output ->
            onDiagnostic(
                "vae_encoder_output name=${output.name ?: "unknown"} size=${output.data.size}"
            )
        }

        outputs.firstOrNull { output ->
            output.data.size == latentSize &&
                isDirectLatentOutputName(output.name)
        }?.let { directLatents ->
            onDiagnostic(
                "vae_encoder_resolution strategy=direct_latent output=${directLatents.name ?: "unknown"} " +
                    "scaled=no"
            )
            return directLatents.data.copyOf()
        }

        resolveMomentLatents(outputs, latentSize, random, onDiagnostic)?.let { sampledLatents ->
            onDiagnostic(
                "vae_encoder_resolution strategy=moments scaled=yes"
            )
            return sampledLatents
        }
        error(
            "Unsupported or ambiguous VAE encoder output for ONNX img2img: " +
                outputs.joinToString { output ->
                    "${output.name ?: "unknown"}(${output.data.size})"
                }
        )
    }

    private fun resolveMomentLatents(
        outputs: List<OnnxNamedTensorOutput>,
        latentSize: Int,
        random: Random,
        onDiagnostic: (String) -> Unit
    ): FloatArray? {
        outputs.firstOrNull { output ->
            output.data.size == latentSize * 2 && isMomentOutputName(output.name)
        }?.let { meanAndVariance ->
            onDiagnostic("vae_encoder_moments source=${meanAndVariance.name ?: "unknown"} mode=combined")
            return sampleMomentLatents(
                mean = meanAndVariance.data.copyOfRange(0, latentSize),
                logVariance = meanAndVariance.data.copyOfRange(latentSize, latentSize * 2),
                random = random
            )
        }

        val meanOutput = outputs.firstOrNull { output ->
            output.data.size == latentSize && isMeanOutputName(output.name)
        }
        val logVarianceOutput = outputs.firstOrNull { output ->
            output.data.size == latentSize && isLogVarianceOutputName(output.name)
        }
        if (meanOutput != null && logVarianceOutput != null) {
            onDiagnostic(
                "vae_encoder_moments source=${meanOutput.name ?: "unknown"}+${logVarianceOutput.name ?: "unknown"} mode=separate"
            )
            return sampleMomentLatents(
                mean = meanOutput.data,
                logVariance = logVarianceOutput.data,
                random = random
            )
        }
        return null
    }

    private fun sampleMomentLatents(
        mean: FloatArray,
        logVariance: FloatArray,
        random: Random
    ): FloatArray {
        return FloatArray(mean.size) { index ->
            val std = exp((logVariance[index].coerceIn(-30f, 20f)) * 0.5f)
            (mean[index] + (gaussian(random) * std)) * IMG2IMG_VAE_LATENT_SCALE
        }
    }

    private fun runUnet(
        session: OrtSession,
        inputNames: OnnxUnetInputNames,
        latents: FloatArray,
        batchSize: Int,
        channels: Int,
        height: Int,
        width: Int,
        timestep: Int,
        promptEmbeddings: FloatArray,
        embeddingDim: Int
    ): FloatArray {
        val tensors = mutableListOf<AutoCloseable>()
        try {
            val timestepInfo = session.inputInfo[inputNames.timestepName]?.info as TensorInfo

            val sampleTensor = createFloatTensor(
                latents,
                longArrayOf(batchSize.toLong(), channels.toLong(), height.toLong(), width.toLong())
            ).also(tensors::add)
            val timestepTensor = createScalarTensor(timestepInfo, timestep).also(tensors::add)
            val embeddingTensor = createFloatTensor(
                promptEmbeddings,
                longArrayOf(batchSize.toLong(), CLIP_MAX_TOKENS.toLong(), embeddingDim.toLong())
            ).also(tensors::add)

            session.run(
                mapOf(
                    inputNames.sampleName to sampleTensor,
                    inputNames.timestepName to timestepTensor,
                    inputNames.embeddingName to embeddingTensor
                )
            ).use { result ->
                return extractFloatTensor(result[0].value)
            }
        } finally {
            tensors.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun decodeLatents(
        session: OrtSession,
        latentInputName: String,
        latents: FloatArray,
        width: Int,
        height: Int
    ): Bitmap {
        val scaledLatents = FloatArray(latents.size) { index -> latents[index] / IMG2IMG_VAE_LATENT_SCALE }
        val latentTensor = createFloatTensor(
            scaledLatents,
            longArrayOf(1, 4, (height / 8).toLong(), (width / 8).toLong())
        )
        latentTensor.use {
            session.run(mapOf(latentInputName to latentTensor)).use { result ->
                val decoded = extractFloatTensor(result[0].value)
                return bitmapFromDecodedTensor(decoded, width, height)
            }
        }
    }

    private fun prepareBitmapForLatentEncoding(source: Bitmap): OnnxImg2ImgPreparedBitmap {
        val placement = computeOnnxImg2ImgCanvasPlacement(
            originalWidth = source.width,
            originalHeight = source.height,
            canvasSize = ONNX_IMG2IMG_CANVAS_SIZE
        )
        if (
            placement.originalWidth == placement.canvasWidth &&
            placement.originalHeight == placement.canvasHeight
        ) {
            return OnnxImg2ImgPreparedBitmap(
                bitmap = source,
                preprocessInfo = placement
            )
        }

        val scaledBitmap = Bitmap.createScaledBitmap(
            source,
            placement.fittedWidth,
            placement.fittedHeight,
            true
        )
        val paddedBitmap = Bitmap.createBitmap(
            placement.canvasWidth,
            placement.canvasHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(paddedBitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            scaledBitmap,
            placement.paddingLeft.toFloat(),
            placement.paddingTop.toFloat(),
            paint
        )
        if (scaledBitmap !== source) {
            scaledBitmap.recycle()
        }
        return OnnxImg2ImgPreparedBitmap(
            bitmap = paddedBitmap,
            preprocessInfo = placement
        )
    }

    private fun bitmapToNormalizedChw(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val channelSize = width * height
        val pixels = IntArray(channelSize)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = FloatArray(channelSize * 3)
        pixels.forEachIndexed { index, pixel ->
            val r = (((pixel shr 16) and 0xFF) / 255f * 2f) - 1f
            val g = (((pixel shr 8) and 0xFF) / 255f * 2f) - 1f
            val b = ((pixel and 0xFF) / 255f * 2f) - 1f
            output[index] = r
            output[channelSize + index] = g
            output[(channelSize * 2) + index] = b
        }
        return output
    }

    private fun createFloatTensor(data: FloatArray, shape: LongArray): OnnxTensor {
        return OnnxTensor.createTensor(
            OrtEnvironmentProvider.environment,
            FloatBuffer.wrap(data),
            shape
        )
    }

    private fun createIntTensor(
        tensorInfo: TensorInfo,
        data: IntArray,
        shape: LongArray
    ): OnnxTensor {
        return when (tensorInfo.type.toString().uppercase(Locale.US)) {
            "INT64" -> OnnxTensor.createTensor(
                OrtEnvironmentProvider.environment,
                LongBuffer.wrap(LongArray(data.size) { index -> data[index].toLong() }),
                shape
            )
            "FLOAT" -> OnnxTensor.createTensor(
                OrtEnvironmentProvider.environment,
                FloatBuffer.wrap(FloatArray(data.size) { index -> data[index].toFloat() }),
                shape
            )
            else -> OnnxTensor.createTensor(
                OrtEnvironmentProvider.environment,
                IntBuffer.wrap(data),
                shape
            )
        }
    }

    private fun createScalarTensor(
        tensorInfo: TensorInfo,
        value: Int
    ): OnnxTensor {
        val shape = longArrayOf(1)
        return when (tensorInfo.type.toString().uppercase(Locale.US)) {
            "FLOAT" -> OnnxTensor.createTensor(
                OrtEnvironmentProvider.environment,
                FloatBuffer.wrap(floatArrayOf(value.toFloat())),
                shape
            )
            "INT32" -> OnnxTensor.createTensor(
                OrtEnvironmentProvider.environment,
                IntBuffer.wrap(intArrayOf(value)),
                shape
            )
            else -> OnnxTensor.createTensor(
                OrtEnvironmentProvider.environment,
                LongBuffer.wrap(longArrayOf(value.toLong())),
                shape
            )
        }
    }

    private fun shapeForTextInput(
        tensorInfo: TensorInfo,
        tokenCount: Int
    ): LongArray {
        val rawShape = tensorInfo.shape
        return when (rawShape.size) {
            0 -> longArrayOf()
            1 -> longArrayOf(tokenCount.toLong())
            else -> longArrayOf(1, tokenCount.toLong())
        }
    }

    private fun extractFloatTensor(value: Any?): FloatArray {
        return when (value) {
            is FloatArray -> value
            is DoubleArray -> FloatArray(value.size) { index -> value[index].toFloat() }
            is Array<*> -> value.flatMap { item -> extractFloatTensor(item).asIterable() }.toFloatArray()
            is OnnxTensor -> extractFloatTensor(value.value)
            null -> error("Tensor output was null")
            else -> error("Unsupported tensor output type: ${value::class.java.name}")
        }
    }

    private fun createInitialLatents(
        channels: Int,
        height: Int,
        width: Int,
        random: Random
    ): FloatArray {
        val size = channels * height * width
        return FloatArray(size) { gaussian(random) }
    }

    private fun duplicateLatents(latents: FloatArray): FloatArray {
        val duplicated = FloatArray(latents.size * 2)
        latents.copyInto(duplicated, 0)
        latents.copyInto(duplicated, latents.size)
        return duplicated
    }

    private fun applyClassifierFreeGuidance(
        noisePrediction: FloatArray,
        sampleSize: Int,
        guidanceScale: Float
    ): FloatArray {
        val unconditional = noisePrediction.copyOfRange(0, sampleSize)
        val conditional = noisePrediction.copyOfRange(sampleSize, sampleSize * 2)
        return FloatArray(sampleSize) { index ->
            unconditional[index] + guidanceScale * (conditional[index] - unconditional[index])
        }
    }

    private fun bitmapFromDecodedTensor(decoded: FloatArray, width: Int, height: Int): Bitmap {
        val channelSize = width * height
        require(decoded.size >= channelSize * 3) {
            "Decoded tensor did not contain enough pixels"
        }
        val pixels = IntArray(channelSize)
        for (index in 0 until channelSize) {
            val r = (((decoded[index] / 2f) + 0.5f).coerceIn(0f, 1f) * 255f).roundToInt()
            val g = (((decoded[channelSize + index] / 2f) + 0.5f).coerceIn(0f, 1f) * 255f).roundToInt()
            val b = (((decoded[(channelSize * 2) + index] / 2f) + 0.5f).coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[index] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun gaussian(random: Random): Float {
        val u1 = random.nextDouble().coerceAtLeast(1e-7)
        val u2 = random.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
    }

    private data class OnnxUnetInputNames(
        val sampleName: String,
        val timestepName: String,
        val embeddingName: String
    )

    private data class OnnxNamedTensorOutput(
        val name: String?,
        val data: FloatArray
    )

    private fun resolveUnetInputNames(session: OrtSession): OnnxUnetInputNames {
        val inputNames = session.inputInfo.keys.toList()
        return OnnxUnetInputNames(
            sampleName = inputNames.firstOrNull { it.equals("sample", ignoreCase = true) }
                ?: inputNames.firstOrNull {
                    it.contains("sample", ignoreCase = true) || it.contains("latent", ignoreCase = true)
                }
                ?: inputNames.first(),
            timestepName = inputNames.firstOrNull { it.equals("timestep", ignoreCase = true) }
                ?: inputNames.firstOrNull { it.contains("time", ignoreCase = true) }
                ?: inputNames.getOrElse(1) { inputNames.first() },
            embeddingName = inputNames.firstOrNull { it.equals("encoder_hidden_states", ignoreCase = true) }
                ?: inputNames.firstOrNull {
                    it.contains("encoder", ignoreCase = true) || it.contains("hidden", ignoreCase = true)
                }
                ?: inputNames.last()
        )
    }

    private fun resolveVaeDecoderInputName(session: OrtSession): String {
        val inputNames = session.inputInfo.keys.toList()
        return inputNames.firstOrNull { it.equals("latent_sample", ignoreCase = true) }
            ?: inputNames.firstOrNull {
                it.contains("latent", ignoreCase = true) || it.contains("sample", ignoreCase = true)
            }
            ?: inputNames.first()
    }

    private fun resolveVaeEncoderInputName(session: OrtSession): String {
        val inputNames = session.inputInfo.keys.toList()
        return inputNames.firstOrNull { it.equals("sample", ignoreCase = true) }
            ?: inputNames.firstOrNull {
                it.contains("sample", ignoreCase = true) ||
                    it.contains("image", ignoreCase = true) ||
                    it.contains("input", ignoreCase = true)
            }
            ?: inputNames.first()
    }
}

internal fun computeOnnxImg2ImgCanvasPlacement(
    originalWidth: Int,
    originalHeight: Int,
    canvasSize: Int = ONNX_IMG2IMG_CANVAS_SIZE
): OnnxImg2ImgPreprocessInfo {
    require(originalWidth > 0 && originalHeight > 0) {
        "Source image dimensions must be positive"
    }
    require(canvasSize > 0) {
        "Canvas size must be positive"
    }
    val scale = minOf(
        canvasSize.toFloat() / originalWidth.toFloat(),
        canvasSize.toFloat() / originalHeight.toFloat()
    )
    val fittedWidth = (originalWidth * scale).roundToInt().coerceIn(1, canvasSize)
    val fittedHeight = (originalHeight * scale).roundToInt().coerceIn(1, canvasSize)
    val paddingLeft = ((canvasSize - fittedWidth) / 2f).roundToInt().coerceAtLeast(0)
    val paddingTop = ((canvasSize - fittedHeight) / 2f).roundToInt().coerceAtLeast(0)
    return OnnxImg2ImgPreprocessInfo(
        originalWidth = originalWidth,
        originalHeight = originalHeight,
        canvasWidth = canvasSize,
        canvasHeight = canvasSize,
        fittedWidth = fittedWidth,
        fittedHeight = fittedHeight,
        paddingLeft = paddingLeft,
        paddingTop = paddingTop
    )
}

private fun isDirectLatentOutputName(name: String?): Boolean {
    val lowered = name?.lowercase(Locale.US) ?: return false
    return lowered.contains("latent") || lowered.contains("sample")
}

private fun isMomentOutputName(name: String?): Boolean {
    val lowered = name?.lowercase(Locale.US) ?: return false
    return lowered.contains("moment")
}

private fun isMeanOutputName(name: String?): Boolean {
    val lowered = name?.lowercase(Locale.US) ?: return false
    return lowered.contains("mean")
}

private fun isLogVarianceOutputName(name: String?): Boolean {
    val lowered = name?.lowercase(Locale.US) ?: return false
    return lowered.contains("logvar") || lowered.contains("log_variance") || lowered.contains("variance")
}
