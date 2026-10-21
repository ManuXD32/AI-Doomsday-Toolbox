package com.example.llamadroid.ui.ai

import com.example.llamadroid.R
import com.example.llamadroid.sd.SdTaeCheck
import com.example.llamadroid.sd.SdTaeCompatibility
import com.example.llamadroid.sd.SdVideoFamilyProfiles
import com.example.llamadroid.service.VideoRuntimeOptions
import java.io.File

/**
 * Pre-launch TAE/model compatibility check. Reads the safetensors header, so call it off the
 * main thread. Only the decoder the command will actually emit is checked.
 */
internal fun videoTaePreflight(options: VideoRuntimeOptions): SdTaeCheck {
    val profile = options.videoFamily?.let { family ->
        runCatching { SdVideoFamilyProfiles.resolve(family, options.videoVariant) }.getOrNull()
    } ?: return SdTaeCheck.Ok
    val components = options.videoComponents
    val tae = components.taePath?.takeIf {
        it.isNotBlank() && (options.useTae || components.vaePath.isNullOrBlank())
    } ?: return SdTaeCheck.Ok
    return SdTaeCompatibility.check(profile, SdTaeCompatibility.readLatentChannels(File(tae)))
}

/** Localized blocking message for a failed TAE check; null when generation may proceed. */
internal fun videoTaeErrorMessage(resources: android.content.res.Resources, check: SdTaeCheck?): String? = when (check) {
    is SdTaeCheck.Mismatch -> resources.getString(R.string.video_tae_mismatch, check.actual, check.expected)
    SdTaeCheck.NotSupported -> resources.getString(R.string.video_tae_not_supported)
    else -> null
}
