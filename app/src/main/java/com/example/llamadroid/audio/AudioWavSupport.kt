package com.example.llamadroid.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PcmWav(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcm: ByteArray
)

internal object AudioWavSupport {
    fun concatPcm16(inputFiles: List<File>, output: File): AudioFileInfo {
        require(inputFiles.isNotEmpty()) { "No WAV chunks to concatenate" }
        val layouts = inputFiles.map(::readLayout)
        val first = layouts.first()
        require(first.bitsPerSample == 16 && layouts.all { it.bitsPerSample == 16 }) {
            "Only 16-bit PCM WAV is supported"
        }
        require(layouts.all { it.sampleRate == first.sampleRate && it.channels == first.channels }) {
            "WAV chunks have incompatible formats"
        }
        require(layouts.sumOf { it.dataSize } <= Int.MAX_VALUE.toLong() - 44L) {
            "Combined WAV is too large"
        }
        output.parentFile?.mkdirs()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        writeHeader(header, first.sampleRate, first.channels, 0L)
        var totalBytes = 0L
        output.outputStream().use { target ->
            target.write(header.array())
            val buffer = ByteArray(64 * 1024)
            inputFiles.forEachIndexed { index, file ->
                val layout = layouts[index]
                RandomAccessFile(file, "r").use { input ->
                    input.seek(layout.dataOffset)
                    var remaining = layout.dataSize
                    while (remaining > 0L) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(read > 0) { "Truncated WAV data" }
                        target.write(buffer, 0, read)
                        remaining -= read
                        totalBytes += read
                    }
                }
            }
        }
        RandomAccessFile(output, "rw").use { file ->
            file.seek(4)
            file.writeInt(Integer.reverseBytes((36L + totalBytes).toInt()))
            file.seek(40)
            file.writeInt(Integer.reverseBytes(totalBytes.toInt()))
        }
        return AudioFileInspector.inspectWav(output)
    }

    fun readPcmWav(file: File): PcmWav {
        val layout = readLayout(file)
        require(layout.dataSize <= Int.MAX_VALUE) { "WAV data is too large" }
        val data = ByteArray(layout.dataSize.toInt())
        RandomAccessFile(file, "r").use { input ->
            input.seek(layout.dataOffset)
            input.readFully(data)
        }
        return PcmWav(layout.sampleRate, layout.channels, layout.bitsPerSample, data)
    }

    /** Scans PCM16 data in bounded blocks so silence checks cannot allocate the whole clip. */
    fun isPcm16Silent(file: File, threshold: Int = 2): Boolean {
        val layout = readLayout(file)
        require(layout.bitsPerSample == 16) { "Only 16-bit PCM WAV is supported" }
        require(layout.dataSize % 2L == 0L) { "Unaligned PCM data" }
        val buffer = ByteArray(64 * 1024)
        RandomAccessFile(file, "r").use { input ->
            input.seek(layout.dataOffset)
            var remaining = layout.dataSize
            var carry: Int? = null
            while (remaining > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(count > 0) { "Truncated WAV data" }
                var index = 0
                carry?.let { low ->
                    val value = low or ((buffer[0].toInt() and 0xff) shl 8)
                    val signed = if (value and 0x8000 != 0) value - 0x10000 else value
                    if (kotlin.math.abs(signed) > threshold) return false
                    index = 1
                    carry = null
                }
                while (index + 1 < count) {
                    val value = (buffer[index].toInt() and 0xff) or
                        ((buffer[index + 1].toInt() and 0xff) shl 8)
                    val signed = if (value and 0x8000 != 0) value - 0x10000 else value
                    if (kotlin.math.abs(signed) > threshold) return false
                    index += 2
                }
                if (index < count) carry = buffer[index].toInt() and 0xff
                remaining -= count
            }
            require(carry == null) { "Unaligned PCM data" }
        }
        return true
    }

    fun writePcmWav(file: File, sampleRate: Int, channels: Int, pcm: ByteArray) {
        require(sampleRate > 0 && channels > 0) { "Invalid WAV format" }
        file.parentFile?.mkdirs()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        writeHeader(header, sampleRate, channels, pcm.size.toLong())
        file.outputStream().use { output ->
            output.write(header.array())
            output.write(pcm)
        }
    }

    private fun writeHeader(header: ByteBuffer, sampleRate: Int, channels: Int, dataSize: Long) {
        val blockAlign = channels * 2
        val byteRate = sampleRate * blockAlign
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36L + dataSize).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize.toInt())
    }

    private data class WavLayout(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long
    )

    private fun readLayout(file: File): WavLayout {
        RandomAccessFile(file, "r").use { input ->
            require(readAscii(input, 4) == "RIFF") { "Not a WAV file" }
            input.skipBytes(4)
            require(readAscii(input, 4) == "WAVE") { "Not a WAV file" }
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var format = 0
            var hasFormat = false
            var dataOffset = -1L
            var dataSize = 0L
            while (input.filePointer + 8 <= input.length()) {
                val id = readAscii(input, 4)
                val size = readLittleInt(input).toLong() and 0xffffffffL
                val start = input.filePointer
                require(size <= input.length() - start) { "Truncated WAV chunk" }
                when (id) {
                    "fmt " -> {
                        require(size >= 16L) { "Invalid WAV format chunk" }
                        format = readLittleShort(input)
                        channels = readLittleShort(input)
                        sampleRate = readLittleInt(input)
                        input.skipBytes(6)
                        bits = readLittleShort(input)
                        hasFormat = true
                    }
                    "data" -> {
                        // RIFF files are only valid for this reader once a
                        // complete fmt chunk has been accepted. Ignore an
                        // early data chunk and continue looking for a valid
                        // format/data pair.
                        if (hasFormat) {
                            dataOffset = start
                            dataSize = size
                            break
                        }
                    }
                }
                input.seek((start + size + (size and 1L)).coerceAtMost(input.length()))
            }
            require(
                hasFormat && format == 1 && channels in 1..32 &&
                    sampleRate in 1..768_000 && bits == 16
            ) {
                "WAV is not 16-bit PCM"
            }
            require(dataOffset >= 0L) { "WAV data chunk is missing" }
            require(dataSize % (channels * 2L) == 0L) { "Unaligned PCM data" }
            return WavLayout(sampleRate, channels, bits, dataOffset, dataSize)
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

    private fun readLittleShort(input: RandomAccessFile): Int =
        input.read() or (input.read() shl 8)
}
