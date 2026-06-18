package com.example.llamadroid.service

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

data class LlamaCallVadDecision(
    val hasSpeechStarted: Boolean,
    val shouldSubmit: Boolean,
    val shouldTimeout: Boolean
)

class LlamaCallVoiceActivityDetector(
    private val sampleRate: Int,
    private val speechThreshold: Float = DEFAULT_SPEECH_THRESHOLD,
    private val silenceAfterSpeechMs: Long = DEFAULT_SILENCE_AFTER_SPEECH_MS,
    private val noSpeechTimeoutMs: Long = DEFAULT_NO_SPEECH_TIMEOUT_MS
) {
    private var elapsedMs: Long = 0L
    private var speechStarted = false
    private var silenceAfterSpeechMsSoFar = 0L

    fun accept(samples: ShortArray, sampleCount: Int): LlamaCallVadDecision {
        if (sampleCount <= 0) {
            return LlamaCallVadDecision(speechStarted, shouldSubmit = false, shouldTimeout = false)
        }
        val frameMs = ((sampleCount.toDouble() / sampleRate.toDouble()) * 1000.0).toLong().coerceAtLeast(1L)
        elapsedMs += frameMs
        val level = LlamaCallAudioLevel.rms(samples, sampleCount)
        val isSpeech = level >= speechThreshold
        if (isSpeech) {
            speechStarted = true
            silenceAfterSpeechMsSoFar = 0L
        } else if (speechStarted) {
            silenceAfterSpeechMsSoFar += frameMs
        }
        return LlamaCallVadDecision(
            hasSpeechStarted = speechStarted,
            shouldSubmit = speechStarted && silenceAfterSpeechMsSoFar >= silenceAfterSpeechMs,
            shouldTimeout = !speechStarted && elapsedMs >= noSpeechTimeoutMs
        )
    }

    fun reset() {
        elapsedMs = 0L
        speechStarted = false
        silenceAfterSpeechMsSoFar = 0L
    }

    companion object {
        const val DEFAULT_SPEECH_THRESHOLD = 0.018f
        const val DEFAULT_SILENCE_AFTER_SPEECH_MS = 5_000L
        const val DEFAULT_NO_SPEECH_TIMEOUT_MS = 10_000L
    }
}

object LlamaCallAudioLevel {
    fun rms(samples: ShortArray, sampleCount: Int): Float {
        if (sampleCount <= 0) return 0f
        var sum = 0.0
        for (i in 0 until sampleCount.coerceAtMost(samples.size)) {
            val normalized = samples[i] / Short.MAX_VALUE.toDouble()
            sum += normalized * normalized
        }
        return sqrt(sum / sampleCount.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}

class LlamaCallWavFileWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channelCount: Int = 1,
    private val bitsPerSample: Int = 16
) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    private var pcmBytesWritten: Long = 0L
    private var closed = false

    init {
        output.setLength(0)
        writeWavHeader(output, sampleRate, channelCount, bitsPerSample, 0L)
    }

    fun write(samples: ShortArray, sampleCount: Int) {
        val safeCount = sampleCount.coerceIn(0, samples.size)
        for (i in 0 until safeCount) {
            val value = samples[i].toInt()
            output.write(value and 0xFF)
            output.write((value shr 8) and 0xFF)
        }
        pcmBytesWritten += safeCount * 2L
    }

    fun finish(): File {
        if (!closed) {
            output.seek(0)
            writeWavHeader(output, sampleRate, channelCount, bitsPerSample, pcmBytesWritten)
            output.close()
            closed = true
        }
        return file
    }

    override fun close() {
        finish()
    }
}

fun buildLlamaCallWavBytes(
    samples: ShortArray,
    sampleRate: Int,
    channelCount: Int = 1,
    bitsPerSample: Int = 16
): ByteArray {
    val output = ByteArrayOutputStream()
    val pcmBytes = samples.size * 2L
    writeWavHeader(output, sampleRate, channelCount, bitsPerSample, pcmBytes)
    samples.forEach { sample ->
        val value = sample.toInt()
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }
    return output.toByteArray()
}

private fun writeWavHeader(
    output: RandomAccessFile,
    sampleRate: Int,
    channelCount: Int,
    bitsPerSample: Int,
    pcmBytes: Long
) {
    val bytes = ByteArrayOutputStream()
    writeWavHeader(bytes, sampleRate, channelCount, bitsPerSample, pcmBytes)
    output.write(bytes.toByteArray())
}

private fun writeWavHeader(
    output: ByteArrayOutputStream,
    sampleRate: Int,
    channelCount: Int,
    bitsPerSample: Int,
    pcmBytes: Long
) {
    val byteRate = sampleRate * channelCount * bitsPerSample / 8
    val blockAlign = channelCount * bitsPerSample / 8
    output.writeAscii("RIFF")
    output.writeIntLe((36L + pcmBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    output.writeAscii("WAVE")
    output.writeAscii("fmt ")
    output.writeIntLe(16)
    output.writeShortLe(1)
    output.writeShortLe(channelCount)
    output.writeIntLe(sampleRate)
    output.writeIntLe(byteRate)
    output.writeShortLe(blockAlign)
    output.writeShortLe(bitsPerSample)
    output.writeAscii("data")
    output.writeIntLe(pcmBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
}

private fun ByteArrayOutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun ByteArrayOutputStream.writeIntLe(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 24) and 0xFF)
}

private fun ByteArrayOutputStream.writeShortLe(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}
