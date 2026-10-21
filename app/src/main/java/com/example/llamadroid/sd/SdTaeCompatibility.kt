package com.example.llamadroid.sd

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Result of comparing a TAE's latent channel count with the selected video family. */
sealed interface SdTaeCheck {
    data object Ok : SdTaeCheck
    data object Unknown : SdTaeCheck
    data object NotSupported : SdTaeCheck
    data class Mismatch(val expected: Int, val actual: Int) : SdTaeCheck
}

/**
 * Detects TAE/family latent-channel mismatches before launch. The native TAEHV decoder aborts
 * (ggml assert) when the TAE's first decoder conv does not match the family's latent channels.
 */
object SdTaeCompatibility {
    private const val MAX_HEADER_BYTES = 16L * 1024L * 1024L
    private const val DECODER_FIRST_CONV = "decoder.1.weight"

    /** TAEHV `decoder.1.weight` is [out, latentChannels, kh, kw]; tolerates a name prefix. */
    fun latentChannelsFromHeader(header: JSONObject): Int? {
        val keys = header.keys().asSequence().filter { it != "__metadata__" }.toList()
        val key = keys.firstOrNull { it == DECODER_FIRST_CONV }
            ?: keys.firstOrNull { it.endsWith(".$DECODER_FIRST_CONV") }
            ?: return null
        val shape = header.optJSONObject(key)?.optJSONArray("shape") ?: return null
        if (shape.length() < 2) return null
        val channels = shape.optInt(1, -1)
        return channels.takeIf { it > 0 }
    }

    fun readLatentChannels(file: File): Int? = try {
        if (!file.isFile) {
            null
        } else {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 8L) return@use null
                val lengthBytes = ByteArray(8)
                raf.readFully(lengthBytes)
                var length = 0L
                for (i in 7 downTo 0) length = (length shl 8) or (lengthBytes[i].toLong() and 0xFF)
                if (length <= 0L || length > MAX_HEADER_BYTES || 8L + length > raf.length()) {
                    return@use null
                }
                val bytes = ByteArray(length.toInt())
                raf.readFully(bytes)
                latentChannelsFromHeader(JSONObject(String(bytes, Charsets.UTF_8)))
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Null means the family's decoder is not a TAEHV variant this app can pair with a TAE. */
    fun expectedLatentChannels(profile: SdVideoFamilyProfile): Int? = when (profile.decoderCompatibility) {
        SdVideoDecoderCompatibility.TAEHV_WAN21 -> 16
        SdVideoDecoderCompatibility.TAEHV_WAN22 -> 48
        SdVideoDecoderCompatibility.NATIVE_VAE_ONLY,
        SdVideoDecoderCompatibility.LTX_CONV_VIDEO_VAE -> null
    }

    /** Call only when a TAE is selected. */
    fun check(profile: SdVideoFamilyProfile, taeLatentChannels: Int?): SdTaeCheck {
        val expected = expectedLatentChannels(profile) ?: return SdTaeCheck.NotSupported
        if (taeLatentChannels == null) return SdTaeCheck.Unknown
        if (taeLatentChannels == expected) return SdTaeCheck.Ok
        // Without an explicit 2.1/2.2 variant the Wan profile cannot tell Wan 2.1 (16) from
        // Wan 2.2 TI2V (48) latents, so either valid Wan TAE is accepted rather than blocked.
        val wanVersionUnknown = profile.family == SdVideoFamily.WAN &&
            !SdVideoFamilyProfiles.wanGenerationKnown(profile.variant)
        if (wanVersionUnknown && taeLatentChannels in WAN_TAE_LATENT_CHANNELS) return SdTaeCheck.Ok
        return SdTaeCheck.Mismatch(expected, taeLatentChannels)
    }

    private val WAN_TAE_LATENT_CHANNELS = setOf(16, 48)
}
