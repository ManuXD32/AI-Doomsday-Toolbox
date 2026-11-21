package com.example.llamadroid.ui.distributed.sd

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.sd.matchesSdVideoFamily
import com.example.llamadroid.sd.resolvedSdFamily
import com.example.llamadroid.sd.resolvedSdVideoFamily
import com.example.llamadroid.service.SDConfig
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SDUpscaleConfig
import com.example.llamadroid.service.SdDistributedRunMonitor
import com.example.llamadroid.service.SdDistributedService
import com.example.llamadroid.service.SdRunKind
import com.example.llamadroid.service.SdTransferExpectation
import com.example.llamadroid.service.StableDiffusionService
import com.example.llamadroid.service.VideoGenerationConfig
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoGenerationService
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.service.probeSdBinaryCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Start / Stop of the main-device screen: validation, the run-monitor setup and the service calls
 * the former `startImageRun` / `startVideoRun` made, split from the UI so a test can replace the
 * service seam ([SdMasterLauncher]).
 */

/** Seam to the generation services. [ServiceSdMasterLauncher] is the production implementation. */
internal interface SdMasterLauncher {
    fun startImage(config: SDConfig)
    fun startUpscale(config: SDUpscaleConfig)
    fun startVideo(config: VideoGenerationConfig)
    fun cancelImage(mode: SDMode)
    fun cancelVideo(mode: VideoGenerationMode)
}

internal class ServiceSdMasterLauncher(private val context: Context) : SdMasterLauncher {
    override fun startImage(config: SDConfig) {
        ContextCompat.startForegroundService(
            context,
            StableDiffusionService.createStartIntent(context, config, useDistributedStateHolder = true)
        )
    }

    override fun startUpscale(config: SDUpscaleConfig) {
        ContextCompat.startForegroundService(
            context,
            StableDiffusionService.createStartUpscaleIntent(context, config, useDistributedStateHolder = true)
        )
    }

    override fun startVideo(config: VideoGenerationConfig) {
        ContextCompat.startForegroundService(
            context,
            VideoGenerationService.createStartIntent(context, config, useDistributedStateHolder = true)
        )
    }

    override fun cancelImage(mode: SDMode) {
        context.startService(
            StableDiffusionService.createCancelModeIntent(context, mode, useDistributedStateHolder = true)
        )
    }

    override fun cancelVideo(mode: VideoGenerationMode) {
        context.startService(
            VideoGenerationService.createCancelIntent(context, mode, useDistributedStateHolder = true)
        )
    }
}

/** Tests provide a fake; null means "use the real services". */
internal val LocalSdMasterLauncher = staticCompositionLocalOf<SdMasterLauncher?> { null }

internal sealed interface SdMasterStartOutcome {
    data object Started : SdMasterStartOutcome

    /** Start was refused: [message] is shown next to the button, [step] is where the fix lives. */
    data class Blocked(val message: String, val step: SdMasterStep) : SdMasterStartOutcome
}

/** The step whose controls fix [issue]. */
internal fun sdMasterIssueStep(issue: SdDistributedLaunchIssue, mode: SdMasterMode): SdMasterStep = when (issue) {
    SdDistributedLaunchIssue.PromptRequired,
    is SdDistributedLaunchIssue.InvalidNumber -> SdMasterStep.RUN
    // The image workflow keeps its input picker on the Run step; the video editor on the Model step.
    SdDistributedLaunchIssue.InputImageRequired ->
        if (mode == SdMasterMode.VIDEO) SdMasterStep.MODEL else SdMasterStep.RUN
    else -> SdMasterStep.MODEL
}

/** True when the SD binary can be probed; the video launch needs its capability flags. */
internal suspend fun sdMasterBinaryReady(context: Context): Boolean = withContext(Dispatchers.IO) {
    try {
        val repository = BinaryRepository(context)
        repository.getSdBinary()
            ?.takeIf { it.exists() && it.isFile }
            ?.let { probeSdBinaryCapabilities(context, it, repository) } != null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }
}

/** Records the run for the run monitor (transfer estimate per Helper). */
private fun beginRunMonitor(kind: SdRunKind, mode: String, modulePaths: Map<String, List<String?>>) {
    SdDistributedRunMonitor.begin(
        kind = kind,
        mode = mode,
        nowMs = System.currentTimeMillis(),
        txBytes = TrafficStats.getUidTxBytes(Process.myUid()),
        expected = SdTransferExpectation.expected(
            SdDistributedService.activeWorkers.value,
            SdTransferExpectation.moduleBytes(modulePaths)
        )
    )
}

/**
 * Validates the form, persists it and starts the image or video run through [launcher]. A refused
 * start is reported as [SdMasterStartOutcome.Blocked]; nothing is started and the monitor untouched.
 */
internal suspend fun startSdMasterRun(
    context: Context,
    vm: SdMasterViewModel,
    installed: List<ModelEntity>,
    launcher: SdMasterLauncher,
    binaryReady: suspend () -> Boolean = { sdMasterBinaryReady(context) }
): SdMasterStartOutcome {
    val state = vm.state.value
    return if (state.mode == SdMasterMode.VIDEO) {
        startVideoRun(context, vm, state, installed, launcher, binaryReady)
    } else {
        startImageRun(context, vm, state, installed, launcher)
    }
}

private fun blocked(
    context: Context,
    vm: SdMasterViewModel,
    issue: SdDistributedLaunchIssue,
    mode: SdMasterMode
): SdMasterStartOutcome.Blocked {
    vm.reportLaunchIssue(issue)
    return SdMasterStartOutcome.Blocked(
        message = sdDistributedLaunchIssueMessage(context.resources, context, issue),
        step = sdMasterIssueStep(issue, mode)
    )
}

private suspend fun startImageRun(
    context: Context,
    vm: SdMasterViewModel,
    state: SdMasterUiState,
    installed: List<ModelEntity>,
    launcher: SdMasterLauncher
): SdMasterStartOutcome {
    val settings = state.settings
    val mode = settings.imageWorkflowMode.toSdMasterImageMode()
    val issue = sdMasterImageLaunchIssue(settings, sdMasterImageMissingRoles(settings, installed))
    if (issue != null) return blocked(context, vm, issue, state.mode)

    vm.resolveWorkerAddresses()
    vm.saveNow()
    vm.reportLaunchIssue(null)
    val runtimeConfig = vm.runtimeConfig()
    val outputPath = sdMasterImageOutputFile(context.filesDir, mode).absolutePath
    SdDistributedService.appendLog(context.getString(R.string.sd_dist_log_run_requested, mode.name))
    beginRunMonitor(SdRunKind.IMAGE, mode.name, sdMasterImageModulePaths(settings))

    if (mode == SDMode.UPSCALE) {
        launcher.startUpscale(buildSdUpscaleConfig(settings, runtimeConfig, outputPath))
    } else {
        val family = installed.firstOrNull { it.path == settings.imageModelPath }?.resolvedSdFamily()
        launcher.startImage(
            buildSdImageConfig(
                settings = settings,
                runtimeConfig = runtimeConfig,
                outputPath = outputPath,
                modelFamily = family?.first?.storedValue,
                modelVariant = family?.second
            )
        )
    }
    return SdMasterStartOutcome.Started
}

/** Installed LoRA files that fit the selected video family (all of them while the family is unknown). */
internal fun sdMasterCompatibleVideoLoraPaths(
    installed: List<ModelEntity>,
    options: VideoRuntimeOptions
): Set<String> {
    val mainPath = options.videoComponents.diffusionModelPath ?: options.videoComponents.fullModelPath
    val resolved = installed.firstOrNull { it.path == mainPath }?.resolvedSdVideoFamily()
    val family = options.videoFamily ?: resolved?.first
    val variant = if (options.videoFamily != null) options.videoVariant else resolved?.second
    val loras = installed.filter { it.type == ModelType.SD_LORA }
    return (if (family == null) loras else loras.filter { it.matchesSdVideoFamily(family, variant) })
        .map { it.path }
        .toSet()
}

private suspend fun startVideoRun(
    context: Context,
    vm: SdMasterViewModel,
    state: SdMasterUiState,
    installed: List<ModelEntity>,
    launcher: SdMasterLauncher,
    binaryReady: suspend () -> Boolean
): SdMasterStartOutcome {
    if (!binaryReady()) {
        return SdMasterStartOutcome.Blocked(
            message = context.getString(R.string.video_controls_binary_unavailable_selection),
            step = state.step
        )
    }
    val settings = state.settings
    val options = state.videoOptions
    val mode = settings.videoWorkflowMode.toSdMasterVideoMode()
    val issue = sdMasterVideoLaunchIssue(settings, options, sdMasterCompatibleVideoLoraPaths(installed, options))
    if (issue != null) return blocked(context, vm, issue, state.mode)

    vm.resolveWorkerAddresses()
    vm.saveNow()
    vm.reportLaunchIssue(null)
    val runtimeConfig = vm.runtimeConfig()
    SdDistributedService.appendLog(context.getString(R.string.sd_dist_log_run_requested, mode.name))
    beginRunMonitor(
        SdRunKind.VIDEO,
        mode.name,
        sdMasterVideoModulePaths(effectiveSdVideoOptions(settings, options))
    )
    launcher.startVideo(
        buildSdVideoConfig(
            settings = settings,
            videoOptions = options,
            runtimeConfig = runtimeConfig,
            outputDir = sdMasterVideoOutputDir(context.filesDir, mode),
            baseName = sdMasterVideoBaseName()
        )
    )
    return SdMasterStartOutcome.Started
}

/** Stops the active run of the current workflow. */
internal fun cancelSdMasterRun(state: SdMasterUiState, launcher: SdMasterLauncher) {
    if (state.mode == SdMasterMode.VIDEO) {
        launcher.cancelVideo(state.settings.videoWorkflowMode.toSdMasterVideoMode())
    } else {
        launcher.cancelImage(state.settings.imageWorkflowMode.toSdMasterImageMode())
    }
}
