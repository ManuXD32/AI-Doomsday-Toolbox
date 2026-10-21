package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.sd.SdTaeCheck
import com.example.llamadroid.sd.SdTaeCompatibility
import com.example.llamadroid.sd.SdVideoFamilyProfiles
import com.example.llamadroid.util.DebugLog
import java.io.File

/**
 * Tracks the coarse native phase of a stable-diffusion.cpp video run from its log lines.
 * Decoding starts silently after sampling finishes, so the "sampling completed" /
 * "generating latent video completed" lines are the only reliable boundary.
 */
class VideoNativePhaseTracker {
    @Volatile
    var phase: SdProgressPhase = SdProgressPhase.PREPARING
        private set

    fun update(line: String) {
        val lower = line.lowercase()
        when {
            "sampling completed" in lower ||
                "generating latent video completed" in lower ||
                "sampling(latent upscale) completed" in lower -> phase = SdProgressPhase.VAE_DECODING
            phase == SdProgressPhase.VAE_DECODING -> Unit
            STEP_REGEX.containsMatchIn(lower) -> phase = SdProgressPhase.DIFFUSION
        }
    }

    private companion object {
        val STEP_REGEX = Regex("""\|\s*\d+/\d+\s*-|step\s+\d+/\d+""")
    }
}

/** True when the decoder-abort hint applies: native abort in a VAE/TAE stage with a TAE in use. */
fun shouldAppendDecoderAbortHint(report: SdFailureReport, usedTae: Boolean): Boolean =
    usedTae && report.category == SdFailureCategory.NATIVE_ABORT &&
        (report.stage == SdProgressPhase.VAE_DECODING || report.stage == SdProgressPhase.VAE_ENCODING)

/** Same rule as the command builder: the TAE is emitted when chosen or when no VAE is set. */
fun videoRunUsesTae(config: VideoGenerationConfig): Boolean {
    val paths = config.resolvedVideoComponents()
    return !paths.taePath.isNullOrBlank() && (config.useTae || paths.vaePath.isNullOrBlank())
}

fun classifyVideoNativeFailure(
    exitCode: Int,
    recentOutput: List<String>,
    phase: SdProgressPhase?,
    acceleratorBinary: Boolean
): SdFailureReport = SdNativeFailureClassifier.classify(exitCode, recentOutput, phase, acceleratorBinary)

/**
 * Accelerator failures stay plain exceptions (classified report as the cause) so the existing
 * accelerator-to-CPU retry keeps working for any native failure, as before classification.
 */
fun Context.videoNativeFailureException(
    report: SdFailureReport,
    usedTae: Boolean,
    acceleratorBinary: Boolean = false
): RuntimeException {
    DebugLog.log("[VIDEO-GEN] Native failure: ${report.technicalSummary} stage=${report.stage?.diagnosticName}")
    report.recentNativeOutput.takeLast(NATIVE_TAIL_LOG_LINES).forEach { DebugLog.log("[VIDEO-GEN] native: $it") }
    val message = buildString {
        append(getString(R.string.video_gen_error_native_failure, report.exitCode, sdNativeFailureMessage(this@videoNativeFailureException, report)))
        if (shouldAppendDecoderAbortHint(report, usedTae)) {
            append(' ')
            append(getString(R.string.video_gen_error_decoder_abort_hint))
        }
    }
    val classified = SdNativeFailureException(report, message)
    return if (acceleratorBinary) IllegalStateException(message, classified) else classified
}

/** Fails before launch when the selected TAE cannot decode the family's latents. */
fun Context.verifyVideoTaeBeforeLaunch(config: VideoGenerationConfig) {
    if (!videoRunUsesTae(config)) return
    val family = config.videoFamily ?: return
    val profile = SdVideoFamilyProfiles.resolve(family, config.videoVariant)
    val taePath = config.resolvedVideoComponents().taePath ?: return
    when (val check = SdTaeCompatibility.check(profile, SdTaeCompatibility.readLatentChannels(File(taePath)))) {
        is SdTaeCheck.Mismatch ->
            throw IllegalStateException(getString(R.string.video_tae_mismatch, check.actual, check.expected))
        SdTaeCheck.NotSupported -> throw IllegalStateException(getString(R.string.video_tae_not_supported))
        SdTaeCheck.Unknown -> DebugLog.log("[VIDEO-GEN] TAE latent channels unreadable; launching anyway")
        SdTaeCheck.Ok -> Unit
    }
}

private const val NATIVE_TAIL_LOG_LINES = 20
