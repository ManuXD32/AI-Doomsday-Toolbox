package com.example.llamadroid.audio

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

internal object AudioOutputStorage {
    suspend fun persist(
        context: Context,
        job: AudioGenerationJobEntity,
        result: AudioAdapterResult,
        isCancelled: () -> Boolean = { false }
    ): PersistedAudioOutput = withContext(Dispatchers.IO) {
        val jobContext = currentCoroutineContext()
        val cancelCheck = { jobContext.ensureActive(); isCancelled() }
        val exportStartedAt = System.currentTimeMillis()
        val outputDir = AudioWorkspaceStorage.jobRoot(context, job.id)
        outputDir.mkdirs()
        val wavTarget = AudioWorkspaceStorage.jobAudioFile(context, job.id, "wav")
        val processedInput = File(outputDir, "processed.wav")
        val needsSpeed = job.adapterId == AudioAdapterIds.LLAMA_CLI &&
            kotlin.math.abs(job.speed - 1.0f) > 0.001f
        val sourceInfo = AudioFileInspector.inspect(result.wavFile)
        val sourceSampleRate = result.sampleRate.takeIf { it > 0 } ?: sourceInfo.sampleRate
        val needsRate = sourceSampleRate != job.outputSampleRate
        if (needsSpeed || needsRate) {
            AudioFfmpeg.postProcessOutput(
                context = context,
                input = result.wavFile,
                output = processedInput,
                sampleRate = job.outputSampleRate,
                speed = if (needsSpeed) job.speed else 1.0f,
                isCancelled = cancelCheck
            )
            copyOrReplace(processedInput, wavTarget, cancelCheck)
            processedInput.delete()
        } else {
            copyOrReplace(result.wavFile, wavTarget, cancelCheck)
        }
        val selected = if (job.outputFormat == "mp3") {
            val mp3Target = AudioWorkspaceStorage.jobAudioFile(context, job.id, "mp3")
            if (!needsSpeed && !needsRate && result.playableFile.extension.equals("mp3", ignoreCase = true)) {
                copyOrReplace(result.playableFile, mp3Target, cancelCheck)
            } else {
                AudioFfmpeg.convertToMp3(context, wavTarget, mp3Target, cancelCheck)
            }
            mp3Target
        } else {
            wavTarget
        }
        if (cancelCheck()) throw CancellationException("Audio export cancelled")
        val finalInfo = AudioFileInspector.inspect(wavTarget)
        require(finalInfo.durationMs > 0 && finalInfo.sampleRate > 0) { "Invalid audio output" }
        val metadataPath = if (job.includeMetadata) {
            val metadataFile = AudioWorkspaceStorage.jobMetadataFile(context, job.id)
            val metadata = JSONObject()
            .put("jobId", job.id)
            .put("adapterId", job.adapterId)
            .put("family", job.family)
            .put("modelId", job.modelId)
            .put("modelName", job.modelDisplayName)
            .put("language", if (job.family == AudioModelFamilies.POCKET_TTS) job.modelLanguage else job.language ?: job.modelLanguage)
            .put("voiceProfileId", job.voiceProfileId)
            .put("modelSizeBytes", File(job.modelPath).length())
            .put("companionSizeBytes", job.companionPath?.let(::File)?.length() ?: 0L)
            .put("modelPath", job.modelPath)
            .put("companionPath", job.companionPath)
            .put("components", componentDigests(job, cancelCheck))
            .put("effectiveSettings", effectiveSettings(job))
            .put("durationMs", finalInfo.durationMs)
            .put("sampleRate", finalInfo.sampleRate)
            .put("channels", finalInfo.channels)
            .put("chunkCount", result.chunkCount)
            .put("outputFormat", selected.extension)
            .put("outputBytes", selected.length())
            .put("generationElapsedMs", (System.currentTimeMillis() - (job.startedAt ?: job.createdAt)).coerceAtLeast(0L))
            .put("exportElapsedMs", (System.currentTimeMillis() - exportStartedAt).coerceAtLeast(0L))
            .put("createdAt", System.currentTimeMillis())
            .put("requestMetadata", runCatching { JSONObject(job.metadataJson) }.getOrElse { JSONObject() })
            .put("adapterMetadata", runCatching { JSONObject(result.metadataJson) }.getOrElse { JSONObject() })
            .also { if (cancelCheck()) throw CancellationException("Audio export cancelled") }
            metadataFile.writeText(metadata.toString(2))
            metadataFile.absolutePath
        } else {
            AudioWorkspaceStorage.jobMetadataFile(context, job.id).delete()
            null
        }
        mirrorToSharedOutputFolder(context, job.id, job.family, selected, metadataPath, cancelCheck)
        PersistedAudioOutput(
            wavPath = wavTarget.absolutePath,
            outputPath = selected.absolutePath,
            metadataPath = metadataPath,
            durationMs = finalInfo.durationMs,
            sampleRate = finalInfo.sampleRate
        )
    }

    private fun copyOrReplace(source: File, target: File, isCancelled: () -> Boolean) {
        require(source.isFile) { "Audio output is missing" }
        target.parentFile?.mkdirs()
        if (source.canonicalFile == target.canonicalFile) return
        val buffer = ByteArray(64 * 1024)
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                while (true) {
                    if (isCancelled()) throw CancellationException("Audio export cancelled")
                    val count = input.read(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        require(target.isFile && target.length() > 0L) { "Audio output could not be saved" }
    }

    private fun sha256(file: File, isCancelled: () -> Boolean): String? {
        if (!file.isFile) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                if (isCancelled()) throw CancellationException("Audio export cancelled")
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun effectiveSettings(job: AudioGenerationJobEntity): JSONObject {
        if (AudioModelFamilies.isMusicOrSfx(job.family)) {
            return JSONObject(job.metadataJson).getJSONObject("stableAudio").apply {
                remove("outputPath")
                put("outputFormat", job.outputFormat)
                put("outputSampleRate", job.outputSampleRate)
            }
        }
        val llama = job.adapterId == AudioAdapterIds.LLAMA_CLI
        val pocket = job.family == AudioModelFamilies.POCKET_TTS
        return JSONObject()
            .put("language", if (pocket) job.modelLanguage else job.language ?: job.modelLanguage)
            .put("speed", job.speed)
            .put("voiceStyle", job.voiceStyle.takeIf { !llama })
            .put("totalSteps", job.totalSteps.takeIf { !llama })
            .put("temperature", job.temperature.takeIf { llama && !pocket })
            .put("topP", job.topP.takeIf { llama && !pocket })
            .put("topK", job.topK.takeIf { llama && !pocket })
            .put("seed", job.seed.takeIf { llama })
            .put("maxFrames", job.maxFrames.takeIf { llama })
            .put("runtimeThreads", job.runtimeThreads.takeIf { llama })
            .put("batchSize", job.batchSize.takeIf { llama })
            .put("microBatchSize", job.microBatchSize.takeIf { llama })
            .put("chunkSize", job.chunkSize)
            .put("outputSampleRate", job.outputSampleRate)
            .put("normalizeReference", job.normalizeReference.takeIf { llama })
            .put("denoiseReference", job.denoiseReference.takeIf { llama })
            .put("trimStartMs", job.trimStartMs.takeIf { llama })
            .put("trimEndMs", job.trimEndMs.takeIf { llama })
            .put("outputFormat", job.outputFormat)
    }

    private fun mirrorToSharedOutputFolder(
        context: Context,
        jobId: String,
        family: String,
        audioFile: File,
        metadataPath: String?,
        isCancelled: () -> Boolean
    ) {
        val outputFolderUri = SettingsRepository(context).outputFolderUri.value ?: return
        val created = mutableListOf<DocumentFile>()
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(outputFolderUri))
                ?: error("Could not open configured output folder")
            val outputDirName = when (family) {
                AudioModelFamilies.STABLE_AUDIO_MUSIC -> "music"
                AudioModelFamilies.STABLE_AUDIO_SFX -> "sfx"
                else -> SHARED_OUTPUT_DIR
            }
            val ttsDir = rootDoc.findFile(outputDirName)
                ?: rootDoc.createDirectory(outputDirName)
                ?: error("Could not create audio output folder")
            val name = "audio_${AudioWorkspaceStorage.safeStem(jobId)}_${System.currentTimeMillis()}.${audioFile.extension}"
            copyFileIntoDocument(context, audioFile, ttsDir, name, mimeTypeForAudio(audioFile), created, isCancelled)
            metadataPath?.let { path ->
                copyFileIntoDocument(context, File(path), ttsDir, "$name.json", "application/json", created, isCancelled)
            }
            DebugLog.log("[AUDIO] Mirrored output to shared audio folder")
        } catch (error: Throwable) {
            // Only files created by this failed export are removed; existing
            // exported copies and the app-private result remain intact.
            created.forEach { runCatching { it.delete() } }
            if (error is CancellationException) throw error
            throw IllegalStateException("Shared output export failed", error)
        }
    }

    private fun copyFileIntoDocument(
        context: Context,
        sourceFile: File,
        targetDir: DocumentFile,
        targetName: String,
        mimeType: String,
        created: MutableList<DocumentFile>,
        isCancelled: () -> Boolean
    ) {
        require(sourceFile.isFile) { "Shared export source is missing" }
        val target = targetDir.createFile(mimeType, targetName)
            ?: error("Could not create shared audio file")
        created += target
        val buffer = ByteArray(64 * 1024)
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
            sourceFile.inputStream().use { input ->
                while (true) {
                    if (isCancelled()) throw CancellationException("Audio export cancelled")
                    val count = input.read(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("Could not open shared audio output")
    }

    private fun mimeTypeForAudio(file: File): String = when (file.extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }

    private const val SHARED_OUTPUT_DIR = "tts"

    private fun componentDigests(job: AudioGenerationJobEntity, isCancelled: () -> Boolean): JSONArray = JSONArray().apply {
        val components = if (AudioModelFamilies.isMusicOrSfx(job.family)) {
            val request = com.example.llamadroid.audio.music.StableAudio3Request.fromJson(
                JSONObject(job.metadataJson).getJSONObject("stableAudio"))
            request.components.all().map { (role, ref) -> role to ref.path } +
                request.loras.map { "lora" to it.path }
        } else listOfNotNull("tts_main" to job.modelPath, job.companionPath?.let { "tts_mmproj" to it })
        components.distinct().forEach { (role, path) ->
            val root = File(path)
            val files = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.toList().sortedBy { it.relativeTo(root).path } else listOf(root)
            files.forEach { file ->
                val hash = sha256(file, isCancelled) ?: error("Model component is missing")
                put(JSONObject()
                    .put("role", role)
                    .put("name", if (root.isDirectory) file.relativeTo(root).path else file.name)
                    .put("identity", "sha256:$hash")
                    .put("sha256", hash)
                    .put("sizeBytes", file.length()))
            }
        }
    }
}

internal data class PersistedAudioOutput(
    val wavPath: String,
    val outputPath: String,
    val metadataPath: String?,
    val durationMs: Long,
    val sampleRate: Int
)
