package com.example.llamadroid.audio

import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.llamadroid.util.DebugLog
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID

object AudioWorkspaceStorage {
    fun root(context: Context): File = File(context.filesDir, "audio_workspace").apply { mkdirs() }
    fun voiceRoot(context: Context): File = File(root(context), "voices").apply { mkdirs() }
    fun outputRoot(context: Context): File = File(root(context), "outputs").apply { mkdirs() }
    fun stagingRoot(context: Context): File = File(root(context), "staging").apply { mkdirs() }

    fun voiceOriginal(context: Context, id: String, extension: String): File =
        File(voiceRoot(context), "${safeStem(id)}_original.${safeExtension(extension, "audio")}")

    fun voiceNormalized(context: Context, id: String): File =
        File(voiceRoot(context), "${safeStem(id)}_normalized.wav")

    fun voiceDenoised(context: Context, id: String): File =
        File(voiceRoot(context), "${safeStem(id)}_denoised.wav")

    fun jobRoot(context: Context, jobId: String): File =
        File(outputRoot(context), safeStem(jobId)).apply { mkdirs() }

    fun jobAudioFile(context: Context, jobId: String, extension: String): File =
        File(jobRoot(context, jobId), "audio.${safeExtension(extension, "wav")}")

    fun jobWavFile(context: Context, jobId: String): File = jobAudioFile(context, jobId, "wav")
    fun jobMetadataFile(context: Context, jobId: String): File =
        File(jobRoot(context, jobId), "metadata.json")

    fun deleteJobOutput(context: Context, jobId: String) {
        val root = outputRoot(context).canonicalFile
        val target = runCatching { jobRoot(context, jobId).canonicalFile }.getOrNull() ?: return
        if (target.parentFile == root) target.deleteRecursively()
    }

    fun safeStem(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_', '.', '-')
        .take(80)
        .ifBlank { UUID.randomUUID().toString() }

    fun safeExtension(value: String?, fallback: String): String = value
        ?.substringAfterLast('.', value)
        ?.lowercase(Locale.US)
        ?.replace(Regex("[^a-z0-9]"), "")
        ?.take(8)
        ?.ifBlank { fallback }
        ?: fallback
}

data class AudioFileInfo(
    val durationMs: Long = 0L,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitsPerSample: Int = 0,
    val format: String? = null
)

object AudioFileInspector {
    fun inspect(file: File): AudioFileInfo {
        require(file.isFile) { "Audio file does not exist" }
        val extension = file.extension.lowercase(Locale.US)
        val wav = if (extension == "wav") inspectWav(file) else null
        val duration = wav?.durationMs ?: runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
            } finally {
                retriever.release()
            }
        }.getOrNull() ?: 0L
        return (wav ?: AudioFileInfo()).copy(
            durationMs = duration,
            format = extension.takeIf { it.isNotBlank() }
        )
    }

    fun inspectWav(file: File): AudioFileInfo {
        RandomAccessFile(file, "r").use { input ->
            require(readAscii(input, 4) == "RIFF") { "Not a RIFF WAV file" }
            input.skipBytes(4)
            require(readAscii(input, 4) == "WAVE") { "Not a WAVE file" }
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var format = 0
            var hasFormat = false
            var dataBytes = 0L
            var hasData = false
            while (input.filePointer + 8 <= input.length()) {
                val chunkId = readAscii(input, 4)
                val chunkSize = readLittleInt(input).toLong() and 0xffffffffL
                val chunkStart = input.filePointer
                require(chunkSize <= input.length() - chunkStart) { "Truncated WAV chunk" }
                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= 16L) { "Invalid WAV format chunk" }
                        format = readLittleShort(input)
                        channels = readLittleShort(input)
                        sampleRate = readLittleInt(input)
                        input.skipBytes(6)
                        bits = readLittleShort(input)
                        hasFormat = true
                    }
                    "data" -> {
                        if (hasFormat) {
                            dataBytes = chunkSize
                            hasData = true
                            break
                        }
                    }
                }
                input.seek((chunkStart + chunkSize + (chunkSize and 1L)).coerceAtMost(input.length()))
            }
            require(hasFormat && hasData && format in setOf(1, 3)) { "WAV format chunk is missing" }
            require(channels in 1..32 && sampleRate in 1..768_000) { "Invalid WAV audio format" }
            require(
                when (format) {
                    1 -> bits in setOf(8, 16, 24, 32)
                    3 -> bits in setOf(32, 64)
                    else -> false
                }
            ) { "Invalid WAV sample depth" }
            val bytesPerSecond = sampleRate.toLong() * channels * (bits / 8).coerceAtLeast(1)
            return AudioFileInfo(
                durationMs = if (bytesPerSecond > 0) dataBytes * 1000L / bytesPerSecond else 0L,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bits,
                format = "wav"
            )
        }
    }

    private fun readAscii(input: RandomAccessFile, count: Int): String {
        val bytes = ByteArray(count)
        input.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readLittleInt(input: RandomAccessFile): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readLittleShort(input: RandomAccessFile): Int {
        val b0 = input.read()
        val b1 = input.read()
        return b0 or (b1 shl 8)
    }
}

internal fun Context.queryAudioDisplayName(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }

internal fun copyUriToFile(context: Context, uri: Uri, destination: File) {
    destination.parentFile?.mkdirs()
    val input = context.contentResolver.openInputStream(uri)
        ?: error("Could not open selected audio")
    input.use { source ->
        destination.outputStream().use { target -> source.copyTo(target) }
    }
    require(destination.isFile && destination.length() > 0L) { "Selected audio is empty" }
}

internal fun deleteIfOwned(root: File, path: String?) {
    if (path.isNullOrBlank()) return
    runCatching {
        val canonicalRoot = root.canonicalFile
        val target = File(path).canonicalFile
        if (target.parentFile?.canonicalFile == canonicalRoot || target.parentFile?.parentFile?.canonicalFile == canonicalRoot) {
            if (target.isFile) target.delete()
        }
    }.onFailure { DebugLog.log("[AUDIO] Failed to remove owned asset: ${it.javaClass.simpleName}") }
}
