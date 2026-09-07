package com.example.llamadroid.service

import com.example.llamadroid.sd.SdVideoAudioCodec
import com.example.llamadroid.sd.SdVideoOutputFormat
import java.io.File

/** Explicit stream mapping and muxer selection also work with temporary output filenames. */
internal fun buildVideoConversionArgs(
    executable: String,
    input: File,
    output: File,
    format: SdVideoOutputFormat,
    audioCodec: SdVideoAudioCodec?,
    audioSidecar: File? = null
): List<String> = buildList {
    addAll(listOf(executable, "-y", "-i", input.absolutePath))
    audioSidecar?.let { addAll(listOf("-i", it.absolutePath)) }
    addAll(listOf("-map", "0:v:0"))
    when (format) {
        SdVideoOutputFormat.MP4 -> addAll(listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart"))
        SdVideoOutputFormat.WEBM -> addAll(listOf("-c:v", "libvpx-vp9", "-pix_fmt", "yuv420p"))
        SdVideoOutputFormat.AVI -> addAll(listOf("-c:v", "mjpeg", "-q:v", "3"))
    }
    val codec = audioCodec ?: if (format == SdVideoOutputFormat.WEBM) SdVideoAudioCodec.OPUS else SdVideoAudioCodec.AAC
    if (codec == SdVideoAudioCodec.NONE) add("-an") else {
        addAll(listOf("-map", if (audioSidecar == null) "0:a:0?" else "1:a:0"))
        when (codec) {
            SdVideoAudioCodec.AAC -> addAll(listOf("-c:a", "aac", "-b:a", "192k"))
            SdVideoAudioCodec.OPUS -> addAll(listOf("-c:a", "libopus", "-b:a", "128k"))
            SdVideoAudioCodec.COPY -> addAll(listOf("-c:a", "copy"))
            SdVideoAudioCodec.NONE -> Unit
        }
    }
    addAll(listOf("-f", format.extension, output.absolutePath))
}

/** Pinned native CLI writes a WAV beside formats that cannot carry its generated audio. */
internal fun findVideoAudioSidecar(video: File): File? =
    File(video.parentFile, "${video.nameWithoutExtension}.wav").takeIf { it.isFile && it.length() > 44L }
