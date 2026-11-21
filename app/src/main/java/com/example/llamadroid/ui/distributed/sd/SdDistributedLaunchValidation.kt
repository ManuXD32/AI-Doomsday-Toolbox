package com.example.llamadroid.ui.distributed.sd

import android.content.Context
import android.content.res.Resources
import com.example.llamadroid.R
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.SdVideoInputRole
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.ui.components.videoComponentLabel
import com.example.llamadroid.ui.components.videoGenerationReadiness
import com.example.llamadroid.ui.components.videoInputLabel

/**
 * Pure launch validation for the distributed Image and Video cards. Functions return the first
 * blocking issue (null = OK); [sdDistributedLaunchIssueMessage] turns it into the user-facing text.
 */
internal sealed interface SdDistributedLaunchIssue {
    data object SelectImageModel : SdDistributedLaunchIssue
    data object SelectVideoModel : SdDistributedLaunchIssue
    data object SelectUpscalerModel : SdDistributedLaunchIssue
    data object PromptRequired : SdDistributedLaunchIssue
    data object InputImageRequired : SdDistributedLaunchIssue

    /** [labelRes] is the string resource of the offending numeric field label. */
    data class InvalidNumber(val labelRes: Int) : SdDistributedLaunchIssue
    data class MissingImageComponents(val roles: List<SdComponentRole>) : SdDistributedLaunchIssue
    data object VideoUnsupportedWorkflow : SdDistributedLaunchIssue
    data class MissingVideoComponents(val roles: List<SdVideoComponentRole>) : SdDistributedLaunchIssue
    data class MissingVideoInputs(val roles: List<SdVideoInputRole>) : SdDistributedLaunchIssue
    data class IncompatibleVideoLoras(val paths: List<String>) : SdDistributedLaunchIssue
}

/** Same conditions, in the same order, as the former inline check in `startImageRun()`. */
@Suppress("LongParameterList")
internal fun sdDistributedImageLaunchIssue(
    mode: SDMode,
    modelPath: String,
    prompt: String,
    width: Int?,
    height: Int?,
    missingRequiredRoles: List<SdComponentRole>,
    inputPath: String,
    upscalerModelPath: String,
    controlNetEnabled: Boolean,
    controlNetPath: String,
    loraEnabled: Boolean,
    loraPath: String
): SdDistributedLaunchIssue? {
    val generates = mode != SDMode.UPSCALE
    return when {
        generates && modelPath.isBlank() -> SdDistributedLaunchIssue.SelectImageModel
        generates && prompt.isBlank() -> SdDistributedLaunchIssue.PromptRequired
        generates && width == null -> SdDistributedLaunchIssue.InvalidNumber(R.string.imagegen_width_label)
        generates && height == null -> SdDistributedLaunchIssue.InvalidNumber(R.string.imagegen_height_label)
        generates && missingRequiredRoles.isNotEmpty() ->
            SdDistributedLaunchIssue.MissingImageComponents(missingRequiredRoles)
        mode == SDMode.IMG2IMG && inputPath.isBlank() -> SdDistributedLaunchIssue.InputImageRequired
        mode == SDMode.UPSCALE && upscalerModelPath.isBlank() -> SdDistributedLaunchIssue.SelectUpscalerModel
        mode == SDMode.UPSCALE && inputPath.isBlank() -> SdDistributedLaunchIssue.InputImageRequired
        mode == SDMode.IMG2IMG && controlNetEnabled && controlNetPath.isBlank() ->
            SdDistributedLaunchIssue.MissingImageComponents(listOf(SdComponentRole.CONTROLNET))
        loraEnabled && loraPath.isBlank() ->
            SdDistributedLaunchIssue.MissingImageComponents(listOf(SdComponentRole.LORA))
        else -> null
    }
}

/**
 * Video validation over the *effective* options (card state merged with the runtime editor), so
 * components chosen only in the editor count. The legacy VAE/T5 flags are not consulted.
 */
@Suppress("LongParameterList")
internal fun sdDistributedVideoLaunchIssue(
    mode: VideoGenerationMode,
    effectiveOptions: VideoRuntimeOptions,
    prompt: String,
    framesText: String,
    width: Int?,
    height: Int?,
    loras: List<SdLoraSpec>,
    compatibleLoraPaths: Set<String>
): SdDistributedLaunchIssue? {
    val components = effectiveOptions.videoComponents
    val readiness = videoGenerationReadiness(effectiveOptions)
    val incompatibleLoras = loras.filter { it.enabled && it.path !in compatibleLoraPaths }
    return when {
        (components.diffusionModelPath ?: components.fullModelPath).isNullOrBlank() ->
            SdDistributedLaunchIssue.SelectVideoModel
        prompt.isBlank() -> SdDistributedLaunchIssue.PromptRequired
        readiness.unsupportedWorkflow -> SdDistributedLaunchIssue.VideoUnsupportedWorkflow
        readiness.missingComponents.isNotEmpty() ->
            SdDistributedLaunchIssue.MissingVideoComponents(readiness.missingComponents)
        readiness.missingInputs.isNotEmpty() ->
            SdDistributedLaunchIssue.MissingVideoInputs(readiness.missingInputs)
        framesText.toIntOrNull()?.let { it < 2 } != false ->
            SdDistributedLaunchIssue.InvalidNumber(R.string.sd_dist_frames)
        width == null -> SdDistributedLaunchIssue.InvalidNumber(R.string.video_gen_width_label)
        height == null -> SdDistributedLaunchIssue.InvalidNumber(R.string.video_gen_height_label)
        mode == VideoGenerationMode.IMG2VID && effectiveOptions.videoInputs.initImagePath.isNullOrBlank() ->
            SdDistributedLaunchIssue.InputImageRequired
        incompatibleLoras.isNotEmpty() ->
            SdDistributedLaunchIssue.IncompatibleVideoLoras(incompatibleLoras.map { it.path })
        else -> null
    }
}

internal fun sdDistributedLaunchIssueMessage(
    resources: Resources,
    context: Context,
    issue: SdDistributedLaunchIssue
): String = when (issue) {
    SdDistributedLaunchIssue.SelectImageModel -> resources.getString(R.string.sd_dist_error_select_image_model)
    SdDistributedLaunchIssue.SelectVideoModel -> resources.getString(R.string.sd_dist_error_select_video_model)
    SdDistributedLaunchIssue.SelectUpscalerModel -> resources.getString(R.string.sd_dist_error_select_upscaler_model)
    SdDistributedLaunchIssue.PromptRequired -> resources.getString(R.string.sd_dist_error_prompt_required)
    SdDistributedLaunchIssue.InputImageRequired -> resources.getString(R.string.sd_dist_error_input_image_required)
    is SdDistributedLaunchIssue.InvalidNumber -> resources.getString(
        R.string.video_gen_error_invalid_number,
        resources.getString(issue.labelRes)
    )
    is SdDistributedLaunchIssue.MissingImageComponents -> resources.getString(
        R.string.imagegen_error_missing_required_components,
        issue.roles.joinToString(", ") { sdDistributedComponentRoleLabel(context, it) }
    )
    SdDistributedLaunchIssue.VideoUnsupportedWorkflow ->
        resources.getString(R.string.video_controls_profile_unsupported_workflow)
    is SdDistributedLaunchIssue.MissingVideoComponents -> resources.getString(
        R.string.video_controls_profile_missing_components,
        issue.roles.joinToString { videoComponentLabel(resources, it) }
    )
    is SdDistributedLaunchIssue.MissingVideoInputs -> resources.getString(
        R.string.video_controls_profile_missing_inputs,
        issue.roles.joinToString { videoInputLabel(resources, it) }
    )
    is SdDistributedLaunchIssue.IncompatibleVideoLoras -> resources.getString(
        R.string.video_controls_lora_missing_or_incompatible,
        issue.paths.joinToString { it.substringAfterLast('/').ifBlank { it } }
    )
}
