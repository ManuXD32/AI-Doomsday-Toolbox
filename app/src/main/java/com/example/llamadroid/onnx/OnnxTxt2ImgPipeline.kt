package com.example.llamadroid.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
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
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val VAE_LATENT_SCALE = 0.18215f
private const val UNET_INFERENCE_START_PROGRESS = 0.12f
private const val UNET_INFERENCE_END_PROGRESS = 0.92f

data class OnnxTxt2ImgResult(
    val outputFile: File,
    val seedUsed: Long,
    val warningMessage: String? = null,
    val runtimeSummary: OnnxRuntimeExecutionSummary
)

object OrtEnvironmentProvider {
    val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
}

class OnnxTxt2ImgPipeline {
    suspend fun generate(
        config: OnnxImageGenConfig,
        onProgress: (Float, String) -> Unit,
        onDiagnostic: (String) -> Unit = {}
    ): OnnxTxt2ImgResult {
        currentCoroutineContext().ensureActive()
        require(config.width % 8 == 0 && config.height % 8 == 0) {
            "Width and height must be divisible by 8"
        }
        require(config.steps > 0) {
            "Steps must be greater than 0"
        }

        val bundle = OnnxBundleValidator.requirePaths(File(config.modelPath), OnnxImageGenMode.TXT2IMG)
        onProgress(0.02f, "Loading tokenizer")
        val tokenizer = ClipBpeTokenizer(bundle.tokenizerVocab, bundle.tokenizerMerges)
        val environment = OrtEnvironmentProvider.environment
        val promptWarnings = mutableListOf<String>()
        onDiagnostic("tokenizer=clip_bpe max_tokens=$CLIP_MAX_TOKENS")

        onProgress(0.06f, "Loading text encoder")
        createOnnxCpuSession(environment, bundle.textEncoderModel, config.runtimeOptions).use { textEncoder ->
            currentCoroutineContext().ensureActive()
            onProgress(0.10f, "Loading UNet and VAE")
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
                    currentCoroutineContext().ensureActive()
                    listOfNotNull(unetResult.summary.warningMessage, vaeResult.summary.warningMessage)
                        .forEach(promptWarnings::add)
                    onDiagnostic(
                        "session_backends text_encoder=CPU unet=${unetResult.summary.resolvedBackend} " +
                            "vae_decoder=${vaeResult.summary.resolvedBackend}"
                    )

                    onProgress(0.16f, "Encoding prompts")
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

                    val latentChannels = 4
                    val latentWidth = config.width / 8
                    val latentHeight = config.height / 8
                    val seed = if (config.seed >= 0) config.seed else System.currentTimeMillis()
                    val scheduler = OnnxEulerAncestralScheduler(
                        inferenceSteps = config.steps,
                        seed = seed,
                        predictionType = OnnxPredictionType.EPSILON
                    )
                    val random = Random(seed)
                    onDiagnostic(
                        "scheduler=euler_ancestral prediction=epsilon steps=${config.steps} " +
                            "first_timestep=${scheduler.timesteps.firstOrNull()} " +
                            "last_timestep=${scheduler.timesteps.lastOrNull()} " +
                            "first_sigma=${scheduler.sigmaAt(0)}"
                    )
                    onDiagnostic("latent_scale=$VAE_LATENT_SCALE latent_shape=4x${latentHeight}x${latentWidth}")
                    onProgress(0.18f, "Preparing latents")
                    var latents = createInitialLatents(
                        channels = latentChannels,
                        height = latentHeight,
                        width = latentWidth,
                        random = random
                    ).let { initial ->
                        FloatArray(initial.size) { index -> initial[index] * scheduler.initNoiseSigma }
                    }
                    val unetInputs = resolveUnetInputNames(unetResult.session)
                    onDiagnostic(
                        "unet_inputs sample=${unetInputs.sampleName} timestep=${unetInputs.timestepName} " +
                            "encoder_hidden_states=${unetInputs.embeddingName}"
                    )
                    val decoderInputName = resolveVaeDecoderInputName(vaeResult.session)
                    onDiagnostic("vae_decoder_input=$decoderInputName")

                    scheduler.timesteps.forEachIndexed { index, timestep ->
                        currentCoroutineContext().ensureActive()
                        val denoiseProgress = UNET_INFERENCE_START_PROGRESS +
                            ((index + 1).toFloat() / config.steps.toFloat()) *
                            (UNET_INFERENCE_END_PROGRESS - UNET_INFERENCE_START_PROGRESS)
                        onProgress(denoiseProgress, "UNet step ${index + 1}/${config.steps}")

                        val modelInput = duplicateLatents(scheduler.scaleModelInput(latents, index))
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
                            stepIndex = index
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
                    try {
                        FileOutputStream(outputFile).use { output ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    currentCoroutineContext().ensureActive()
                    onProgress(1f, "Complete")

                    val warning = promptWarnings.joinToString("\n").ifBlank { null }
                    return OnnxTxt2ImgResult(
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
                            options = config.runtimeOptions
                        )
                    )
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
        val scaledLatents = FloatArray(latents.size) { index -> latents[index] / VAE_LATENT_SCALE }
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
}
