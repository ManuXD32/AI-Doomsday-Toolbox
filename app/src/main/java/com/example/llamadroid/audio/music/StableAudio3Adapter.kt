package com.example.llamadroid.audio.music

import android.content.Context
import com.example.llamadroid.audio.*
import com.example.llamadroid.data.binary.BinaryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Local music/SFX adapter; queue, history and exports remain owned by the Audio workspace. */
class StableAudio3Adapter(context: Context) : AudioGenerationAdapter {
    private val app = context.applicationContext
    private val cancelled = AtomicBoolean(false)
    override val id = StableAudio3Ids.ADAPTER_ID
    override val info = AudioAdapterInfo(
        id = id,
        families = setOf(StableAudio3Ids.FAMILY_MUSIC, StableAudio3Ids.FAMILY_SFX),
        supportsLanguageSelection = false, supportsReferenceAudio = false, referenceAudioRequired = false,
        supportsTemperature = false, supportsTopP = false, supportsTopK = false,
        supportsSeed = true, supportsSpeed = false, supportsMaxFrames = false,
        supportsOutputSampleRate = false,
        parameterDefinitions = listOf(
            AudioParameterDescriptor("seed", AudioParameterKind.LONG),
            AudioParameterDescriptor("steps", AudioParameterKind.INTEGER, 1.0, 64.0, 1.0, "8"),
            AudioParameterDescriptor("cfg", AudioParameterKind.FLOAT, 0.0, 20.0, 0.1, "1"),
            AudioParameterDescriptor("apg", AudioParameterKind.FLOAT, 0.0, 1.0, 0.1, "1"),
            AudioParameterDescriptor("cfgBatched", AudioParameterKind.BOOLEAN, defaultValue = "true"),
            AudioParameterDescriptor("negativePrompt", AudioParameterKind.TEXT),
            AudioParameterDescriptor("durationSeconds", AudioParameterKind.FLOAT, 0.01, 120.0),
            AudioParameterDescriptor("initNoiseLevel", AudioParameterKind.FLOAT, 0.01, 2.0, 0.01, "0.7"),
            AudioParameterDescriptor("threads", AudioParameterKind.INTEGER, 1.0, 256.0, 1.0, "4"),
            AudioParameterDescriptor("freeModels", AudioParameterKind.BOOLEAN, defaultValue = "true")
        )
    )

    override fun supports(model: AudioModelDescriptor): Boolean = model.adapterId == id && model.family in info.families
    override fun cancel() { cancelled.set(true) }

    override suspend fun generate(
        request: AudioGenerationRequest,
        text: String,
        outputDir: File,
        onProgress: suspend (AudioProgress) -> Unit,
        isCancelled: () -> Boolean
    ): AudioAdapterResult = withContext(Dispatchers.IO) {
        cancelled.set(false)
        val cancelCheck = { cancelled.get() || isCancelled() }
        fun checkCancellation() {
            if (cancelCheck()) throw CancellationException("Audio cancelled")
        }
        ensureActive()
        checkCancellation()
        var music = StableAudio3Request.fromJson(JSONObject(request.metadataJson).getJSONObject("stableAudio"))
        require(music.kind.family == request.model.family) { "Audio model family mismatch" }
        require(File(music.components.dit.path).canonicalPath == File(request.model.modelPath).canonicalPath) {
            "Audio component identity mismatch"
        }
        check(outputDir.isDirectory || outputDir.mkdirs()) { "Audio output directory is unavailable" }
        val output = File(outputDir, "stable_audio.wav")
        music = music.copy(outputPath = output.absolutePath, prompt = text)
        if (music.operation != StableAudio3Operation.GENERATE) {
            onProgress(AudioProgress(0f, "audio encoding", unit = AudioProgressUnit.STAGE))
            val source = File(requireNotNull(music.initAudioPath))
            val sourceInfo = AudioFileInspector.inspect(source)
            require(sourceInfo.durationMs in 1..120_000) { "Input audio duration is invalid" }
            val ffmpeg = BinaryRepository(app).getFFmpegBinary() ?: error("FFmpeg is missing")
            val converted = File(outputDir, "conditioning.wav")
            val conversion = AudioProcessRunner.run(app, listOf(ffmpeg.absolutePath,
                "-hide_banner", "-loglevel", "error", "-y", "-i", source.absolutePath,
                "-vn", "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le", converted.absolutePath), cancelCheck)
            check(conversion.exitCode == 0) { "Input audio conversion failed" }
            checkCancellation()
            val inputInfo = AudioFileInspector.inspect(converted)
            require(inputInfo.sampleRate == 44_100 && inputInfo.channels == 2 && inputInfo.durationMs in 1..120_000)
            music = music.copy(initAudioPath = converted.absolutePath)
            if (music.operation == StableAudio3Operation.EXTEND) {
                val end = inputInfo.durationMs / 1000.0
                require(music.durationSeconds > end) { "Extension duration must exceed source duration" }
                music = music.copy(maskStartSeconds = end, maskEndSeconds = music.durationSeconds)
            }
        }
        music = music.validate(supportsExtend = true)
        music = StableAudio3LoraMerge.prepare(app, music, onProgress = {
            checkCancellation()
            onProgress(AudioProgress(0f, "lora preparing", unit = AudioProgressUnit.STAGE))
        }, isCancelled = cancelCheck)
        val result = StableAudio3WorkerClient(app).run(music, onProgress = { stage, completed, total ->
            checkCancellation()
            val sampling = "sampl" in stage.lowercase()
            onProgress(AudioProgress(if (sampling && total > 0) completed.toFloat() / total else 0f, stage, completedChunks = completed, totalChunks = total,
                unit = if (sampling && total > 0) AudioProgressUnit.STEPS else AudioProgressUnit.STAGE))
        }, isCancelled = cancelCheck)
        ensureActive()
        checkCancellation()
        require(File(result.outputPath).canonicalFile == output.canonicalFile && output.isFile) { "Invalid audio output" }
        val outputInfo = AudioFileInspector.inspect(output)
        require(outputInfo.sampleRate == 44_100 && outputInfo.channels == 2 && outputInfo.durationMs > 0 &&
            abs(outputInfo.durationMs - (music.durationSeconds * 1000).toLong()) < 1000) { "Invalid audio output" }
        val metadata = JSONObject(result.metadataJson).put("sourceRevision", StableAudio3Ids.SOURCE_REVISION)
            .put("upstreamRevision", StableAudio3Ids.UPSTREAM_REVISION)
            .put("operation", music.operation.wireValue).put("seed", music.seed)
            .put("effectiveDit", music.components.dit.toJson())
            .put("maskStartSeconds", music.maskStartSeconds).put("maskEndSeconds", music.maskEndSeconds)
        AudioAdapterResult(output, durationMs = outputInfo.durationMs, sampleRate = outputInfo.sampleRate,
            metadataJson = metadata.toString())
    }
}
