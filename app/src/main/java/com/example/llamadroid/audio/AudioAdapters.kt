package com.example.llamadroid.audio

import android.content.Context
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.onnx.OnnxTtsRequest
import com.example.llamadroid.onnx.OnnxTtsStorage
import com.example.llamadroid.onnx.SupertonicTtsPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Local generation boundary shared by speech, music and sound-effect adapters. */
interface AudioGenerationAdapter {
    val id: String
    val info: AudioAdapterInfo
    fun supports(model: AudioModelDescriptor): Boolean

    suspend fun generate(
        request: AudioGenerationRequest,
        text: String,
        outputDir: File,
        onProgress: suspend (AudioProgress) -> Unit,
        isCancelled: () -> Boolean
    ): AudioAdapterResult

    fun cancel() {}
}

/** Source compatibility for existing speech integrations. */
typealias AudioTtsAdapter = AudioGenerationAdapter

/** Capability metadata is derived from the adapter, so the UI cannot expose a
 * control that the selected runtime silently ignores. */
data class AudioAdapterInfo(
    val id: String,
    val families: Set<String>,
    val supportsLanguageSelection: Boolean,
    val supportsReferenceAudio: Boolean,
    val referenceAudioRequired: Boolean,
    val supportsTemperature: Boolean,
    val supportsTopP: Boolean,
    val supportsTopK: Boolean,
    val supportsSeed: Boolean,
    val supportsSpeed: Boolean,
    val supportsMaxFrames: Boolean,
    val supportsOutputSampleRate: Boolean,
    val supportedLanguages: List<String> = emptyList(),
    val requiresCompanionProjector: Boolean = false,
    val parameterDefinitions: List<AudioParameterDescriptor> = emptyList()
)

enum class AudioParameterKind { FLOAT, INTEGER, LONG, BOOLEAN, TEXT }

/** Authoritative bounds/defaults for controls exposed by a model adapter. */
data class AudioParameterDescriptor(
    val key: String,
    val kind: AudioParameterKind,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val step: Double? = null,
    val defaultValue: String? = null,
    val supported: Boolean = true,
    val unsupportedReason: String? = null
)

class SupertonicAudioAdapter(private val context: Context) : AudioTtsAdapter {
    override val id: String = AudioAdapterIds.SUPERTONIC
    override val info: AudioAdapterInfo = AudioAdapterInfo(
        id = id,
        families = setOf(AudioModelFamilies.SUPERTONIC),
        supportsLanguageSelection = true,
        supportsReferenceAudio = false,
        referenceAudioRequired = false,
        supportsTemperature = false,
        supportsTopP = false,
        supportsTopK = false,
        supportsSeed = false,
        supportsSpeed = true,
        supportsMaxFrames = false,
        supportsOutputSampleRate = true,
        supportedLanguages = com.example.llamadroid.onnx.supertonicLanguageCodes,
        parameterDefinitions = listOf(
            AudioParameterDescriptor("language", AudioParameterKind.TEXT, defaultValue = "en"),
            AudioParameterDescriptor("voiceStyle", AudioParameterKind.TEXT),
            AudioParameterDescriptor("speed", AudioParameterKind.FLOAT, 0.5, 2.0, 0.01, "1.05"),
            AudioParameterDescriptor("totalSteps", AudioParameterKind.INTEGER, 1.0, 64.0, 1.0, "8"),
            AudioParameterDescriptor("outputSampleRate", AudioParameterKind.INTEGER, 8_000.0, 96_000.0, 1.0, "24000")
        )
    )

    override fun supports(model: AudioModelDescriptor): Boolean =
        model.adapterId == id || model.family == AudioModelFamilies.SUPERTONIC

    override suspend fun generate(
        request: AudioGenerationRequest,
        text: String,
        outputDir: File,
        onProgress: suspend (AudioProgress) -> Unit,
        isCancelled: () -> Boolean
    ): AudioAdapterResult = withContext(Dispatchers.IO) {
        require(File(request.model.modelPath).isDirectory) {
            "Supertonic bundle directory is missing"
        }
        if (isCancelled()) throw CancellationException("Audio generation cancelled")
        val chunks = AudioTextChunker.chunk(text, request.chunkSize, overlap = 0)
        require(chunks.isNotEmpty()) { "Text is required" }
        val chunkFiles = mutableListOf<File>()
        val temporaryDirectory = File(outputDir, ".supertonic_chunks").apply { mkdirs() }
        var combined: File? = null
        var keepCombined = false
        val effectiveSpeed = request.speed.coerceIn(0.5f, 2.0f)
        try {
            chunks.forEachIndexed { index, chunk ->
                ensureActive()
                if (isCancelled()) throw CancellationException("Audio generation cancelled")
                onProgress(
                    AudioProgress(
                        progress = index.toFloat() / chunks.size,
                        stage = "Preparing chunk ${index + 1} of ${chunks.size}",
                        completedChunks = index,
                        totalChunks = chunks.size
                    )
                )
                val result = SupertonicTtsPipeline(
                    context = null,
                    outputDirectory = temporaryDirectory
                ).generate(
                    OnnxTtsRequest(
                        modelPath = request.model.modelPath,
                        modelName = request.model.displayName,
                        text = chunk,
                        language = request.language ?: request.model.language ?: SUPERTONIC_DEFAULT_LANGUAGE,
                        voiceName = request.voiceStyle,
                        totalSteps = request.totalSteps,
                        speed = effectiveSpeed,
                        sourceName = null
                    )
                )
                chunkFiles += result.wavFile
                onProgress(
                    AudioProgress(
                        progress = (index + 1).toFloat() / chunks.size,
                        stage = "Generated chunk ${index + 1} of ${chunks.size}",
                        completedChunks = index + 1,
                        totalChunks = chunks.size
                    )
                )
            }
            val combinedFile = File(outputDir, "supertonic_combined.wav")
            combined = combinedFile
            val info = AudioWavSupport.concatPcm16(chunkFiles, combinedFile)
            val metadata = JSONObject()
                .put("adapterId", id)
                .put("family", request.model.family)
                .put("modelId", request.model.id)
                .put("chunkCount", chunks.size)
                .put("sampleRate", info.sampleRate)
                .put("effectiveSpeed", effectiveSpeed)
                .toString()
            val adapterResult = AudioAdapterResult(
                wavFile = combinedFile,
                playableFile = combinedFile,
                durationMs = info.durationMs,
                sampleRate = info.sampleRate,
                chunkCount = chunks.size,
                metadataJson = metadata
            )
            keepCombined = true
            adapterResult
        } finally {
            chunkFiles.forEach { file ->
                OnnxTtsStorage.metadataFileFor(file).delete()
                file.delete()
            }
            if (!keepCombined) combined?.delete()
            temporaryDirectory.deleteRecursively()
        }
    }
}

class LlamaCliAudioAdapter(private val context: Context) : AudioTtsAdapter {
    override val id: String = AudioAdapterIds.LLAMA_CLI
    override val info: AudioAdapterInfo = AudioAdapterInfo(
        id = id,
        families = setOf(AudioModelFamilies.QWEN3_TTS, AudioModelFamilies.POCKET_TTS, AudioModelFamilies.CUSTOM),
        supportsLanguageSelection = true,
        supportsReferenceAudio = true,
        referenceAudioRequired = false,
        supportsTemperature = true,
        supportsTopP = true,
        supportsTopK = true,
        supportsSeed = true,
        supportsSpeed = true,
        supportsMaxFrames = true,
        supportsOutputSampleRate = true,
        supportedLanguages = listOf("en", "es", "de", "it", "pt", "ja", "ko", "fr", "ru", "zh"),
        requiresCompanionProjector = true,
        parameterDefinitions = listOf(
            AudioParameterDescriptor("language", AudioParameterKind.TEXT, defaultValue = "en"),
            AudioParameterDescriptor("speed", AudioParameterKind.FLOAT, 0.25, 4.0, 0.01, "1.0"),
            AudioParameterDescriptor("temperature", AudioParameterKind.FLOAT, 0.0, 2.0, 0.01, "0.7"),
            AudioParameterDescriptor("topP", AudioParameterKind.FLOAT, 0.0, 1.0, 0.01, "0.9"),
            AudioParameterDescriptor("topK", AudioParameterKind.INTEGER, 0.0, 4096.0, 1.0, "40"),
            AudioParameterDescriptor("seed", AudioParameterKind.LONG),
            AudioParameterDescriptor("maxFrames", AudioParameterKind.INTEGER, 1.0, 100_000.0, 1.0, "512"),
            AudioParameterDescriptor("runtimeThreads", AudioParameterKind.INTEGER, 1.0, 256.0, 1.0, "4"),
            AudioParameterDescriptor("batchSize", AudioParameterKind.INTEGER, 1.0, 256.0, 1.0, "1"),
            AudioParameterDescriptor("microBatchSize", AudioParameterKind.INTEGER, 1.0, 256.0, 1.0, "1"),
            AudioParameterDescriptor("outputSampleRate", AudioParameterKind.INTEGER, 8_000.0, 96_000.0, 1.0, "24000")
        )
    )

    @Volatile private var cancelRequested: Boolean = false

    override fun supports(model: AudioModelDescriptor): Boolean =
        model.adapterId == id || model.family == AudioModelFamilies.QWEN3_TTS ||
            model.family == AudioModelFamilies.POCKET_TTS || model.family == AudioModelFamilies.CUSTOM

    override fun cancel() {
        cancelRequested = true
    }

    override suspend fun generate(
        request: AudioGenerationRequest,
        text: String,
        outputDir: File,
        onProgress: suspend (AudioProgress) -> Unit,
        isCancelled: () -> Boolean
    ): AudioAdapterResult = withContext(Dispatchers.IO) {
        cancelRequested = false
        val model = request.model
        val binary = BinaryRepository(context).getLlamaTtsBinary()
            ?: error("llama-tts binary is not installed")
        require(binary.isFile && binary.canExecute()) { "llama-tts binary is not executable" }
        require(File(model.modelPath).isFile) { "TTS model file is missing" }
        val companion = model.companionFile()
            ?: error("TTS companion projector is required")
        require(companion.isFile) { "TTS companion projector is missing" }

        val chunks = AudioTextChunker.chunk(text, request.chunkSize, overlap = 0)
        require(chunks.isNotEmpty()) { "Text is required" }
        val reference = request.referenceAudioPath?.takeIf { it.isNotBlank() }?.let(::File)
        val requiresReference = model.family == AudioModelFamilies.POCKET_TTS
        if (requiresReference) {
            require(reference?.isFile == true) { "Pocket TTS requires a speaker reference" }
        }
        reference?.let(AudioVoiceAssetStore::validateReference)

        val chunkFiles = mutableListOf<File>()
        val generatedFiles = mutableSetOf<File>()
        var combined: File? = null
        var keepCombined = false
        try {
            chunks.forEachIndexed { index, chunk ->
                ensureActive()
                if (isCancelled() || cancelRequested) throw CancellationException("Audio generation cancelled")
                val output = File(outputDir, "chunk_${index.toString().padStart(4, '0')}.wav")
                val promptFile = File(outputDir, ".prompt_${index.toString().padStart(4, '0')}.txt")
                output.parentFile?.mkdirs()
                output.delete()
                generatedFiles += output
                try {
                    onProgress(
                        AudioProgress(
                            progress = index.toFloat() / chunks.size,
                            stage = "Preparing chunk ${index + 1} of ${chunks.size}",
                            completedChunks = index,
                            totalChunks = chunks.size
                        )
                    )
                    promptFile.writeText(chunk, Charsets.UTF_8)
                    val command = buildCommand(binary, request, promptFile, output, reference)
                    val process = AudioProcessRunner.run(
                        context = context,
                        command = command,
                        isCancelled = { cancelRequested || isCancelled() },
                        environmentOverrides = mapOf(
                            "OMP_NUM_THREADS" to request.runtimeThreads.toString()
                        )
                    )
                    if (process.exitCode != 0 || !output.isFile || output.length() <= 44L) {
                        throw IllegalStateException("llama-tts failed for chunk ${index + 1} (${process.exitCode})")
                    }
                    chunkFiles += output
                    onProgress(
                        AudioProgress(
                            progress = (index + 1).toFloat() / chunks.size,
                            stage = "Generated chunk ${index + 1} of ${chunks.size}",
                            completedChunks = index + 1,
                            totalChunks = chunks.size
                        )
                    )
                } finally {
                    promptFile.delete()
                }
            }
            combined = File(outputDir, "combined.wav")
            val combinedFile = combined!!
            combinedFile.delete()
            val info = AudioWavSupport.concatPcm16(chunkFiles, combinedFile)
            val metadata = JSONObject()
                .put("adapterId", id)
                .put("family", model.family)
                .put("modelId", model.id)
                .put("chunkCount", chunks.size)
                .put("sampleRate", info.sampleRate)
                .toString()
            val result = AudioAdapterResult(
                wavFile = combinedFile,
                playableFile = combinedFile,
                durationMs = info.durationMs,
                sampleRate = info.sampleRate,
                chunkCount = chunks.size,
                metadataJson = metadata
            )
            keepCombined = true
            result
        } finally {
            generatedFiles.forEach { it.delete() }
            if (!keepCombined) combined?.delete()
        }
    }

    private fun buildCommand(
        binary: File,
        request: AudioGenerationRequest,
        promptFile: File,
        output: File,
        reference: File?
    ): List<String> {
        val model = request.model
        return buildList {
            add(binary.absolutePath)
            addAll(listOf("-m", model.modelPath, "--mmproj", model.companionPath!!))
            addAll(listOf("-f", promptFile.absolutePath, "--output", output.absolutePath))
            addAll(listOf("-n", request.maxFrames.toString(), "-ngl", "0", "--no-mmproj-offload"))
            addAll(listOf("--threads", request.runtimeThreads.toString()))
            addAll(listOf("--batch-size", request.batchSize.toString(), "--ubatch-size", request.microBatchSize.toString()))
            if (model.family != AudioModelFamilies.POCKET_TTS) {
                addAll(listOf("--top-k", request.topK.toString(), "--top-p", request.topP.toString()))
                addAll(listOf("--temp", request.temperature.toString()))
            }
            request.seed?.let { addAll(listOf("--seed", it.toString())) }
            if (model.family != AudioModelFamilies.POCKET_TTS) {
                request.language?.let { addAll(listOf("--tts-lang", it)) }
            }
            reference?.let { addAll(listOf("--tts-speaker-file", it.absolutePath)) }
        }
    }
}

class AudioAdapterRegistry(private val adapters: List<AudioTtsAdapter>) {
    fun resolve(model: AudioModelDescriptor): AudioTtsAdapter? =
        adapters.firstOrNull { it.supports(model.normalized()) }

    fun cancelAll() = adapters.forEach { it.cancel() }

    fun descriptors(): List<AudioAdapterInfo> = adapters.map { it.info }

    fun describe(model: AudioModelDescriptor): AudioAdapterInfo? {
        val normalized = model.normalized()
        val adapter = resolve(normalized) ?: return null
        if (adapter.id != AudioAdapterIds.LLAMA_CLI || normalized.family != AudioModelFamilies.POCKET_TTS) {
            return adapter.info
        }
        return adapter.info.copy(
            supportsLanguageSelection = false,
            referenceAudioRequired = true,
            supportsTemperature = false,
            supportsTopP = false,
            supportsTopK = false,
            supportedLanguages = listOf(normalizeAudioLanguage(normalized.language)),
            parameterDefinitions = adapter.info.parameterDefinitions.map { parameter ->
                when (parameter.key) {
                    "language" -> parameter.copy(
                        defaultValue = normalizeAudioLanguage(normalized.language),
                        supported = false,
                        unsupportedReason = "pocket_language_fixed"
                    )
                    "temperature", "topP", "topK" -> parameter.copy(
                        supported = false,
                        unsupportedReason = "pocket_sampling_pack_defined"
                    )
                    else -> parameter
                }
            }
        )
    }

    companion object {
        fun forContext(context: Context): AudioAdapterRegistry = AudioAdapterRegistry(
            listOf(
                SupertonicAudioAdapter(context.applicationContext),
                LlamaCliAudioAdapter(context.applicationContext),
                com.example.llamadroid.audio.music.StableAudio3Adapter(context.applicationContext)
            )
        )
    }
}

private const val SUPERTONIC_DEFAULT_LANGUAGE = "en"
