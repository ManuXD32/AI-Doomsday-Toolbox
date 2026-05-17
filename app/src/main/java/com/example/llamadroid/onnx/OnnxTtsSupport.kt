package com.example.llamadroid.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.service.extractNativePdfTextFromBytes
import com.example.llamadroid.util.DebugLog
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val ONNX_TTS_LOG_TAG = "[ONNX-TTS]"
const val SUPERTONIC_DEFAULT_TOTAL_STEPS = 8
const val SUPERTONIC_DEFAULT_SPEED = 1.05f
const val SUPERTONIC_DEFAULT_LANGUAGE = "en"

val supertonicLanguageCodes: List<String> = listOf(
    "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi",
    "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv",
    "tr", "uk", "vi"
)

private val supertonicLanguages = supertonicLanguageCodes.toSet()

private val onnxTtsMetadataJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

data class OnnxTtsRequest(
    val modelPath: String,
    val modelName: String,
    val text: String,
    val language: String = SUPERTONIC_DEFAULT_LANGUAGE,
    val voiceName: String? = null,
    val totalSteps: Int = SUPERTONIC_DEFAULT_TOTAL_STEPS,
    val speed: Float = SUPERTONIC_DEFAULT_SPEED,
    val sourceName: String? = null
)

data class OnnxTtsResult(
    val playableFile: File,
    val wavFile: File,
    val mp3File: File?,
    val durationSeconds: Float,
    val metadata: OnnxTtsMetadata
)

@Serializable
data class OnnxTtsMetadata(
    val audioPath: String,
    val wavPath: String,
    val mp3Path: String? = null,
    val sourceName: String? = null,
    val textPreview: String,
    val modelName: String,
    val language: String,
    val voiceName: String,
    val totalSteps: Int,
    val speed: Float,
    val durationSeconds: Float,
    val sampleRate: Int,
    val createdAtEpochMs: Long,
    val mp3ConversionStatus: String
) {
    fun toJsonString(): String = onnxTtsMetadataJson.encodeToString(this)

    companion object {
        fun fromJson(raw: String): OnnxTtsMetadata = onnxTtsMetadataJson.decodeFromString(raw)
    }
}

object OnnxTtsStorage {
    fun outputDir(context: Context): File = File(context.filesDir, "onnx_tts_output").apply { mkdirs() }

    fun buildWavFile(context: Context, prefix: String = "tts"): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(outputDir(context), "${prefix}_$timestamp.wav")
    }

    fun metadataFileFor(audioFile: File): File = File(
        audioFile.parentFile ?: audioFile.absoluteFile.parentFile,
        "${audioFile.name}.json"
    )

    fun writeMetadata(audioFile: File, metadata: OnnxTtsMetadata) {
        metadataFileFor(audioFile).writeText(metadata.toJsonString())
    }

    fun readMetadata(audioFile: File): OnnxTtsMetadata? {
        val file = metadataFileFor(audioFile)
        if (!file.isFile) return null
        return runCatching { OnnxTtsMetadata.fromJson(file.readText()) }.getOrNull()
    }

    fun listGeneratedAudio(context: Context): List<File> =
        outputDir(context).listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase(Locale.US) in setOf("mp3", "wav") }
            .sortedByDescending { it.lastModified() }
}

class SupertonicTtsPipeline(
    private val context: Context? = null
) {
    fun generate(
        request: OnnxTtsRequest,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): OnnxTtsResult {
        var stage = "initializing"
        try {
            val cleanText = request.text.trim()
            require(cleanText.isNotBlank()) { "Text is required." }
            require(request.language in supertonicLanguages) { "Unsupported language: ${request.language}" }
            stage = "validating bundle"
            val paths = OnnxTtsBundleValidator.requirePaths(File(request.modelPath))
            val voice = resolveVoiceFile(paths.voiceStylesDir, request.voiceName)
            val env = OrtEnvironmentProvider.environment
            val outputWav = context?.let { OnnxTtsStorage.buildWavFile(it) }
                ?: File.createTempFile("onnx_tts_", ".wav")
            var mp3File: File? = null
            var mp3Status = "not_requested"

            DebugLog.log("$ONNX_TTS_LOG_TAG Starting generation model=${request.modelName} path=${request.modelPath} voice=${voice.nameWithoutExtension} language=${request.language} chars=${cleanText.length} steps=${request.totalSteps} speed=${request.speed}")
            stage = "loading sessions"
            onProgress(0.05f, "Loading Supertonic")
            val sessions = SupertonicSessions.load(paths, env)
            DebugLog.log("$ONNX_TTS_LOG_TAG Session IO: ${sessions.describeIo()}")
            stage = "loading voice style"
            val style = SupertonicStyle.load(voice, env)
            try {
                stage = "preparing text"
                onProgress(0.12f, "Preparing text")
                val processor = SupertonicTextProcessor(paths.unicodeIndexer)
                stage = "synthesizing"
                val result = synthesize(
                    sessions = sessions,
                    style = style,
                    processor = processor,
                    text = cleanText,
                    language = request.language,
                    totalSteps = request.totalSteps.coerceIn(1, 64),
                    speed = request.speed.coerceIn(0.5f, 2.0f),
                    onProgress = onProgress
                )
                stage = "saving WAV"
                onProgress(0.9f, "Saving WAV")
                writeWav(outputWav, result.wav, sessions.sampleRate)
                if (context != null) {
                    stage = "converting MP3"
                    onProgress(0.94f, "Converting MP3")
                    val converted = convertWavToMp3(context, outputWav)
                    mp3File = converted.getOrNull()
                    mp3Status = if (mp3File != null) "converted" else "wav_fallback"
                }
                val playable = mp3File ?: outputWav
                val metadata = OnnxTtsMetadata(
                    audioPath = playable.absolutePath,
                    wavPath = outputWav.absolutePath,
                    mp3Path = mp3File?.absolutePath,
                    sourceName = request.sourceName,
                    textPreview = cleanText.replace(Regex("""\s+"""), " ").take(300),
                    modelName = request.modelName,
                    language = request.language,
                    voiceName = voice.nameWithoutExtension,
                    totalSteps = request.totalSteps.coerceIn(1, 64),
                    speed = request.speed.coerceIn(0.5f, 2.0f),
                    durationSeconds = result.durationSeconds,
                    sampleRate = sessions.sampleRate,
                    createdAtEpochMs = System.currentTimeMillis(),
                    mp3ConversionStatus = mp3Status
                )
                OnnxTtsStorage.writeMetadata(playable, metadata)
                DebugLog.log("$ONNX_TTS_LOG_TAG Completed generation output=${playable.absolutePath} duration=${result.durationSeconds}s mp3=$mp3Status")
                onProgress(1f, "Complete")
                return OnnxTtsResult(
                    playableFile = playable,
                    wavFile = outputWav,
                    mp3File = mp3File,
                    durationSeconds = result.durationSeconds,
                    metadata = metadata
                )
            } finally {
                style.close()
                sessions.close()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            DebugLog.log("$ONNX_TTS_LOG_TAG Generation failed at $stage: ${error.message}\n${error.stackTraceToString()}")
            throw IllegalStateException("Supertonic TTS failed at $stage: ${error.message}", error)
        }
    }

    private fun synthesize(
        sessions: SupertonicSessions,
        style: SupertonicStyle,
        processor: SupertonicTextProcessor,
        text: String,
        language: String,
        totalSteps: Int,
        speed: Float,
        onProgress: (Float, String) -> Unit
    ): InternalTtsResult {
        val maxChunk = if (language == "ko" || language == "ja") 120 else 300
        val chunks = chunkText(text, maxChunk)
        val output = ArrayList<Float>()
        var duration = 0f
        chunks.forEachIndexed { index, chunk ->
            val chunkProgressBase = 0.12f + (index.toFloat() / chunks.size.coerceAtLeast(1)) * 0.74f
            onProgress(chunkProgressBase, "Synthesizing ${index + 1}/${chunks.size}")
            val result = inferOne(
                sessions = sessions,
                style = style,
                processor = processor,
                text = chunk,
                language = language,
                totalSteps = totalSteps,
                speed = speed
            )
            if (index > 0) {
                repeat((0.3f * sessions.sampleRate).toInt()) { output += 0f }
                duration += 0.3f
            }
            val actual = min(result.wav.size, (result.durationSeconds * sessions.sampleRate).toInt())
            for (i in 0 until actual) output += result.wav[i]
            duration += result.durationSeconds
        }
        return InternalTtsResult(output.toFloatArray(), duration)
    }

    private fun inferOne(
        sessions: SupertonicSessions,
        style: SupertonicStyle,
        processor: SupertonicTextProcessor,
        text: String,
        language: String,
        totalSteps: Int,
        speed: Float
    ): InternalTtsResult {
        val env = OrtEnvironmentProvider.environment
        val processed = processor.process(listOf(text), listOf(language))
        DebugLog.log("$ONNX_TTS_LOG_TAG inferOne language=$language textChars=${text.length} tokenLength=${processed.textIds.firstOrNull()?.size ?: 0} steps=$totalSteps")
        val closeables = mutableListOf<AutoCloseable>()
        try {
            val textIdsTensor = createLongTensor(processed.textIds, env).also(closeables::add)
            val textMaskTensor = createFloatTensor(processed.textMask, env).also(closeables::add)
            val duration = runOnnxStage("duration_predictor", sessions.durationPredictor) {
                sessions.durationPredictor.run(
                    mapOf("text_ids" to textIdsTensor, "style_dp" to style.dpTensor, "text_mask" to textMaskTensor)
                ).use { result ->
                    extractFloatTensor(result[0].value).also { values ->
                        for (i in values.indices) values[i] /= speed
                    }
                }
            }
            val textEncoderResult = runOnnxStage("text_encoder", sessions.textEncoder) {
                sessions.textEncoder.run(
                    mapOf("text_ids" to textIdsTensor, "style_ttl" to style.ttlTensor, "text_mask" to textMaskTensor)
                )
            }
            closeables += textEncoderResult
            val textEmbTensor = textEncoderResult[0] as OnnxTensor
            val latent = sampleNoisyLatent(duration, sessions)
            var xt = latent.noisyLatent
            val totalStepTensor = OnnxTensor.createTensor(env, floatArrayOf(totalSteps.toFloat())).also(closeables::add)
            repeat(totalSteps) { step ->
                val stepCloseables = mutableListOf<AutoCloseable>()
                try {
                    val currentStepTensor = OnnxTensor.createTensor(env, floatArrayOf(step.toFloat())).also(stepCloseables::add)
                    val noisyLatentTensor = createFloatTensor(xt, env).also(stepCloseables::add)
                    val latentMaskTensor = createFloatTensor(latent.latentMask, env).also(stepCloseables::add)
                    val textMaskTensor2 = createFloatTensor(processed.textMask, env).also(stepCloseables::add)
                    xt = runOnnxStage("vector_estimator step=${step + 1}/$totalSteps", sessions.vectorEstimator) {
                        sessions.vectorEstimator.run(
                            mapOf(
                                "noisy_latent" to noisyLatentTensor,
                                "text_emb" to textEmbTensor,
                                "style_ttl" to style.ttlTensor,
                                "latent_mask" to latentMaskTensor,
                                "text_mask" to textMaskTensor2,
                                "current_step" to currentStepTensor,
                                "total_step" to totalStepTensor
                            )
                        ).use { result -> extractFloatTensor3d(result[0].value) }
                    }
                } finally {
                    stepCloseables.asReversed().forEach { runCatching { it.close() } }
                }
            }
            val finalLatentTensor = createFloatTensor(xt, env).also(closeables::add)
            val wav = runOnnxStage("vocoder", sessions.vocoder) {
                sessions.vocoder.run(mapOf("latent" to finalLatentTensor)).use { result ->
                    extractFloatTensor(result[0].value)
                }
            }
            return InternalTtsResult(wav, duration.firstOrNull() ?: 0f)
        } finally {
            closeables.asReversed().forEach { runCatching { it.close() } }
        }
    }
}

private data class InternalTtsResult(val wav: FloatArray, val durationSeconds: Float)
private data class LatentSample(val noisyLatent: Array<Array<FloatArray>>, val latentMask: Array<Array<FloatArray>>)

private inline fun <T> runOnnxStage(stage: String, session: OrtSession, block: () -> T): T {
    return try {
        block()
    } catch (error: Throwable) {
        DebugLog.log("$ONNX_TTS_LOG_TAG ONNX stage failed: $stage io=${session.describe(stage)} error=${error.message}\n${error.stackTraceToString()}")
        throw error
    }
}

private fun OrtSession.describe(label: String): String =
    runCatching {
        "$label inputs=${inputNames.joinToString(",")} outputs=${outputNames.joinToString(",")}"
    }.getOrElse { "$label inputs/outputs unavailable: ${it.message}" }

private class SupertonicSessions(
    val config: SupertonicConfig,
    val durationPredictor: OrtSession,
    val textEncoder: OrtSession,
    val vectorEstimator: OrtSession,
    val vocoder: OrtSession
) {
    val sampleRate: Int = config.sampleRate
    val baseChunkSize: Int = config.baseChunkSize
    val chunkCompress: Int = config.chunkCompressFactor
    val latentDim: Int = config.latentDim

    fun close() {
        listOf(durationPredictor, textEncoder, vectorEstimator, vocoder).forEach { runCatching { it.close() } }
    }

    fun describeIo(): String = listOf(
        durationPredictor.describe("duration_predictor"),
        textEncoder.describe("text_encoder"),
        vectorEstimator.describe("vector_estimator"),
        vocoder.describe("vocoder")
    ).joinToString(" | ")

    companion object {
        fun load(paths: OnnxTtsBundlePaths, env: OrtEnvironment): SupertonicSessions {
            val options = OrtSession.SessionOptions()
            val config = SupertonicConfig.fromFile(paths.config)
            return SupertonicSessions(
                config = config,
                durationPredictor = env.createSession(paths.durationPredictor.absolutePath, options),
                textEncoder = env.createSession(paths.textEncoder.absolutePath, options),
                vectorEstimator = env.createSession(paths.vectorEstimator.absolutePath, options),
                vocoder = env.createSession(paths.vocoder.absolutePath, options)
            )
        }
    }
}

private data class SupertonicConfig(
    val sampleRate: Int,
    val baseChunkSize: Int,
    val chunkCompressFactor: Int,
    val latentDim: Int
) {
    companion object {
        fun fromFile(file: File): SupertonicConfig {
            val root = JSONObject(file.readText())
            val ae = root.getJSONObject("ae")
            val ttl = root.getJSONObject("ttl")
            return SupertonicConfig(
                sampleRate = ae.getInt("sample_rate"),
                baseChunkSize = ae.getInt("base_chunk_size"),
                chunkCompressFactor = ttl.getInt("chunk_compress_factor"),
                latentDim = ttl.getInt("latent_dim")
            )
        }
    }
}

private class SupertonicStyle(val ttlTensor: OnnxTensor, val dpTensor: OnnxTensor) {
    fun close() {
        runCatching { ttlTensor.close() }
        runCatching { dpTensor.close() }
    }

    companion object {
        fun load(file: File, env: OrtEnvironment): SupertonicStyle {
            val root = JSONObject(file.readText())
            val ttl = root.getJSONObject("style_ttl")
            val dp = root.getJSONObject("style_dp")
            val ttlShape = ttl.getJSONArray("dims").toLongArray()
            val dpShape = dp.getJSONArray("dims").toLongArray()
            val ttlFlat = ttl.getJSONArray("data").flattenFloats()
            val dpFlat = dp.getJSONArray("data").flattenFloats()
            return SupertonicStyle(
                ttlTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(ttlFlat), ttlShape),
                dpTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(dpFlat), dpShape)
            )
        }
    }
}

private class SupertonicTextProcessor(indexerFile: File) {
    private val indexer: LongArray = JSONArray(indexerFile.readText()).toLongArray()

    fun process(texts: List<String>, languages: List<String>): TextProcessResult {
        val processed = texts.mapIndexed { index, text -> preprocess(text, languages[index]) }
        val codePoints = processed.map { it.codePoints().toArray() }
        val maxLen = codePoints.maxOfOrNull { it.size } ?: 0
        val textIds = Array(codePoints.size) { LongArray(maxLen) }
        codePoints.forEachIndexed { row, values ->
            values.forEachIndexed { column, codePoint ->
                textIds[row][column] = indexer.getOrElse(codePoint) { 0L }
            }
        }
        val mask = Array(codePoints.size) { row ->
            Array(1) {
                FloatArray(maxLen) { column -> if (column < codePoints[row].size) 1f else 0f }
            }
        }
        return TextProcessResult(textIds, mask)
    }

    private fun preprocess(raw: String, language: String): String {
        require(language in supertonicLanguages) { "Invalid language: $language" }
        var text = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        text = removeEmojis(text)
        val replacements = mapOf(
            "–" to "-", "‑" to "-", "—" to "-", "_" to " ", "\u201C" to "\"",
            "\u201D" to "\"", "\u2018" to "'", "\u2019" to "'", "´" to "'",
            "`" to "'", "[" to " ", "]" to " ", "|" to " ", "/" to " ",
            "#" to " ", "→" to " ", "←" to " "
        )
        replacements.forEach { (from, to) -> text = text.replace(from, to) }
        text = text.replace(Regex("""[♥☆♡©\\]"""), "")
            .replace("@", " at ")
            .replace("e.g.,", "for example, ")
            .replace("i.e.,", "that is, ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (!text.matches(Regex(""".*[.!?;:,'"")\]}…。」』〗〉》›»]$"""))) {
            text += "."
        }
        return "<$language>$text"
    }
}

data class TextProcessResult(
    val textIds: Array<LongArray>,
    val textMask: Array<Array<FloatArray>>
)

fun resolveSupertonicVoices(bundleRoot: File): List<String> {
    val voiceDir = File(bundleRoot, "voice_styles")
    return voiceDir.listFiles().orEmpty()
        .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
        .map { it.nameWithoutExtension }
        .sortedWith(compareBy<String> { if (it.equals("M1", ignoreCase = true)) 0 else 1 }.thenBy { it })
}

fun stripTextForTts(content: String, thinking: String? = null): String {
    var text = content
    thinking?.takeIf { it.isNotBlank() }?.let { text = text.replace(it, "") }
    val blockTags = listOf("think", "thinking", "thought", "reasoning", "analysis")
    blockTags.forEach { tag ->
        text = text.replace(
            Regex("""<\s*$tag[^>]*>[\s\S]*?<\s*/\s*$tag\s*>""", RegexOption.IGNORE_CASE),
            ""
        )
    }
    return text
        .replace(Regex("""!\[[^\]]*]\([^)]+\)"""), "")
        .replace(Regex("""```[\s\S]*?```"""), "")
        .replace(Regex("""\[(Tools?|tool activity)]\s*:?.*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

fun extractReadableTextFromUri(context: Context, uri: Uri, displayName: String, maxChars: Int = 250_000): String {
    val lower = displayName.lowercase(Locale.US)
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Cannot open file.")
    val text = when {
        lower.endsWith(".pdf") -> extractPdfTextForTts(context, bytes, displayName, maxChars)
        lower.endsWith(".epub") -> extractEpubText(bytes)
        lower.endsWith(".docx") -> extractDocxText(bytes)
        lower.endsWith(".html") || lower.endsWith(".htm") -> stripMarkup(bytes.toString(Charsets.UTF_8))
        lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") || lower.endsWith(".json") ->
            bytes.toString(Charsets.UTF_8)
        else -> error("Unsupported document type.")
    }
    return text.replace(Regex("""\s+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim().take(maxChars)
}

private fun extractPdfTextForTts(context: Context, bytes: ByteArray, displayName: String, maxChars: Int): String {
    return try {
        PDFBoxResourceLoader.init(context.applicationContext)
        extractNativePdfTextFromBytes(bytes, maxChars)
    } catch (error: Throwable) {
        DebugLog.log("$ONNX_TTS_LOG_TAG PDF extraction failed for $displayName: ${error.message}\n${error.stackTraceToString()}")
        throw IllegalStateException("PDF text extraction failed: ${error.message ?: error::class.java.simpleName}", error)
    }
}

internal fun extractEpubText(bytes: ByteArray): String {
    val output = StringBuilder()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name.lowercase(Locale.US)
            if (!entry.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))) {
                output.appendLine(stripMarkup(zip.readBytes().toString(Charsets.UTF_8)))
                output.appendLine()
            }
            entry = zip.nextEntry
        }
    }
    return output.toString()
}

internal fun extractDocxText(bytes: ByteArray): String {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name == "word/document.xml") {
                return stripMarkup(zip.readBytes().toString(Charsets.UTF_8))
            }
            entry = zip.nextEntry
        }
    }
    return ""
}

internal fun stripMarkup(raw: String): String =
    raw.replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""<[^>]+>"""), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("""[ \t\r\f]+"""), " ")
        .replace(Regex("""\n\s+"""), "\n")
        .trim()

private fun resolveVoiceFile(voiceStylesDir: File, requested: String?): File {
    val voices = voiceStylesDir.listFiles().orEmpty()
        .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
    require(voices.isNotEmpty()) { "No Supertonic voice styles found." }
    return requested?.let { wanted ->
        voices.firstOrNull { it.nameWithoutExtension.equals(wanted, ignoreCase = true) }
    } ?: voices.firstOrNull { it.nameWithoutExtension.equals("M1", ignoreCase = true) } ?: voices.first()
}

private fun sampleNoisyLatent(duration: FloatArray, sessions: SupertonicSessions): LatentSample {
    val maxDuration = duration.maxOrNull() ?: 0f
    val wavLengthMax = (maxDuration * sessions.sampleRate).toLong()
    val wavLengths = LongArray(duration.size) { index -> (duration[index] * sessions.sampleRate).toLong() }
    val chunkSize = sessions.baseChunkSize * sessions.chunkCompress
    val latentLen = ceil(wavLengthMax.toDouble() / chunkSize.toDouble()).toInt().coerceAtLeast(1)
    val latentDim = sessions.latentDim * sessions.chunkCompress
    val random = Random()
    val noisy = Array(duration.size) {
        Array(latentDim) {
            FloatArray(latentLen) {
                val u1 = max(1e-10, random.nextDouble())
                val u2 = random.nextDouble()
                (sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)).toFloat()
            }
        }
    }
    val mask = latentMask(wavLengths, sessions)
    for (b in noisy.indices) {
        for (d in noisy[b].indices) {
            for (t in noisy[b][d].indices) {
                noisy[b][d][t] *= mask[b][0][t]
            }
        }
    }
    return LatentSample(noisy, mask)
}

private fun latentMask(wavLengths: LongArray, sessions: SupertonicSessions): Array<Array<FloatArray>> {
    val latentSize = sessions.baseChunkSize.toLong() * sessions.chunkCompress.toLong()
    val lengths = LongArray(wavLengths.size) { index -> (wavLengths[index] + latentSize - 1L) / latentSize }
    val maxLen = (lengths.maxOrNull() ?: 1L).toInt().coerceAtLeast(1)
    return Array(wavLengths.size) { row ->
        Array(1) {
            FloatArray(maxLen) { column -> if (column < lengths[row]) 1f else 0f }
        }
    }
}

fun chunkText(text: String, maxLen: Int): List<String> {
    val safeMax = maxLen.coerceAtLeast(40)
    val normalized = text.trim()
    if (normalized.isBlank()) return emptyList()
    val chunks = mutableListOf<String>()
    normalized.split(Regex("""\n\s*\n""")).forEach { paragraph ->
        val pending = paragraph.trim()
        if (pending.length <= safeMax) {
            if (pending.isNotBlank()) chunks += pending
        } else {
            val sentences = pending.split(Regex("""(?<=[.!?])\s+"""))
            var current = StringBuilder()
            sentences.forEach { sentence ->
                if (current.length + sentence.length + 1 > safeMax && current.isNotBlank()) {
                    chunks += current.toString().trim()
                    current = StringBuilder()
                }
                if (sentence.length > safeMax) {
                    sentence.chunked(safeMax).forEach { chunks += it.trim() }
                } else {
                    if (current.isNotBlank()) current.append(' ')
                    current.append(sentence)
                }
            }
            if (current.isNotBlank()) chunks += current.toString().trim()
        }
    }
    return chunks.filter { it.isNotBlank() }
}

internal fun writeWav(file: File, audioData: FloatArray, sampleRate: Int) {
    file.parentFile?.mkdirs()
    val pcm = ByteArray(audioData.size * 2)
    val pcmBuffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    audioData.forEach { sample ->
        val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        pcmBuffer.putShort(value.toShort())
    }
    file.outputStream().use { output ->
        val dataSize = pcm.size
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        output.write(header.array())
        output.write(pcm)
    }
}

private fun convertWavToMp3(context: Context, wavFile: File): Result<File> {
    return runCatching {
        setupTtsFfmpegLibrarySymlinks(context)
        val repo = BinaryRepository(context)
        val ffmpeg = repo.getFFmpegBinary() ?: error("FFmpeg binary not found")
        val mp3File = File(wavFile.parentFile, "${wavFile.nameWithoutExtension}.mp3")
        val args = listOf(
            ffmpeg.absolutePath,
            "-y",
            "-i", wavFile.absolutePath,
            "-codec:a", "libmp3lame",
            "-b:a", "128k",
            mp3File.absolutePath
        )
        val pb = ProcessBuilder(args)
        val libDir = File(context.filesDir, "ffmpeg_libs")
        pb.environment()["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${repo.getLibraryDir()}"
        pb.environment()["HOME"] = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        val process = pb.start()
        val stderr = process.errorStream.bufferedReader().readText()
        val stdout = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (stdout.isNotBlank()) DebugLog.log("$ONNX_TTS_LOG_TAG ffmpeg stdout: ${stdout.take(400)}")
        if (stderr.isNotBlank()) DebugLog.log("$ONNX_TTS_LOG_TAG ffmpeg stderr: ${stderr.lines().takeLast(5).joinToString("\n")}")
        require(exit == 0 && mp3File.isFile && mp3File.length() > 0L) { "ffmpeg exit code $exit" }
        mp3File
    }.onFailure { DebugLog.log("$ONNX_TTS_LOG_TAG MP3 conversion failed: ${it.message}") }
}

private fun setupTtsFfmpegLibrarySymlinks(context: Context) {
    val libDir = File(context.filesDir, "ffmpeg_libs").apply { mkdirs() }
    val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
    mapOf(
        "libx264.so.164" to "libx264.so.164.so",
        "libmp3lame.so.0" to "libmp3lame.so.0.so"
    ).forEach { (versionedName, actualName) ->
        val target = File(nativeLibDir, actualName)
        val link = File(libDir, versionedName)
        if (target.exists() && !link.exists()) {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("ln", "-sf", target.absolutePath, link.absolutePath)).waitFor()
            }
        }
    }
}

private fun createFloatTensor(array: Array<Array<FloatArray>>, env: OrtEnvironment): OnnxTensor {
    val d0 = array.size
    val d1 = array.firstOrNull()?.size ?: 0
    val d2 = array.firstOrNull()?.firstOrNull()?.size ?: 0
    val flat = FloatArray(d0 * d1 * d2)
    var index = 0
    for (i in 0 until d0) for (j in 0 until d1) for (k in 0 until d2) flat[index++] = array[i][j][k]
    return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(d0.toLong(), d1.toLong(), d2.toLong()))
}

private fun createLongTensor(array: Array<LongArray>, env: OrtEnvironment): OnnxTensor {
    val d0 = array.size
    val d1 = array.firstOrNull()?.size ?: 0
    val flat = LongArray(d0 * d1)
    var index = 0
    for (i in 0 until d0) for (j in 0 until d1) flat[index++] = array[i][j]
    return OnnxTensor.createTensor(env, LongBuffer.wrap(flat), longArrayOf(d0.toLong(), d1.toLong()))
}

private fun extractFloatTensor(value: Any?): FloatArray = when (value) {
    is FloatArray -> value
    is DoubleArray -> FloatArray(value.size) { value[it].toFloat() }
    is Array<*> -> value.flatMap { extractFloatTensor(it).asIterable() }.toFloatArray()
    is OnnxTensor -> extractFloatTensor(value.value)
    null -> error("Tensor output was null")
    else -> error("Unsupported tensor output: ${value::class.java.name}")
}

private fun extractFloatTensor3d(value: Any?): Array<Array<FloatArray>> = when (value) {
    is Array<*> -> value.map { plane ->
        when (plane) {
            is Array<*> -> plane.map { row -> extractFloatTensor(row) }.toTypedArray()
            else -> arrayOf(extractFloatTensor(plane))
        }
    }.toTypedArray()
    is OnnxTensor -> extractFloatTensor3d(value.value)
    else -> arrayOf(arrayOf(extractFloatTensor(value)))
}

private fun JSONArray.toLongArray(): LongArray = LongArray(length()) { index -> getLong(index) }

private fun JSONArray.flattenFloats(): FloatArray {
    val values = ArrayList<Float>()
    fun visit(item: Any?) {
        when (item) {
            is JSONArray -> for (i in 0 until item.length()) visit(item.get(i))
            is Number -> values += item.toFloat()
            JSONObject.NULL, null -> Unit
            else -> values += item.toString().toFloat()
        }
    }
    visit(this)
    return values.toFloatArray()
}

private fun removeEmojis(text: String): String {
    val builder = StringBuilder()
    text.codePoints().forEach { codePoint ->
        val emoji = (codePoint in 0x1F300..0x1FAFF) ||
            (codePoint in 0x2600..0x27BF) ||
            (codePoint in 0x1F1E6..0x1F1FF)
        if (!emoji) builder.appendCodePoint(codePoint)
    }
    return builder.toString()
}
