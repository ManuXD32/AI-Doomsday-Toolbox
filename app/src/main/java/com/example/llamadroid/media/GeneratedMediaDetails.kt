package com.example.llamadroid.media

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.service.GeneratedVideoMetadata
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SdDistributedRuntimeConfig
import com.example.llamadroid.service.SdGeneratedImageMetadata
import com.example.llamadroid.service.countSdRpcHelpers
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatGalleryDetailDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

/** True when helpers were really used (enabled with at least one server); null without metadata. */
internal fun generatedMediaUsedHelpers(runtime: SdDistributedRuntimeConfig?): Boolean? =
    runtime?.let { it.enabled && it.hasRpcServers }

/** "Time 1m 20s · Distributed: Yes (2 helpers)" as one labelled line. */
internal fun generatedMediaSummaryLine(
    context: Context,
    durationMs: Long?,
    runtime: SdDistributedRuntimeConfig?
): Pair<String, String> {
    val time = durationMs?.let { context.getString(R.string.gallery_details_time, formatSdGenerationDuration(it)) }
        ?: context.getString(R.string.gallery_details_time_unknown)
    val distributed = when (generatedMediaUsedHelpers(runtime)) {
        true -> {
            val helpers = countSdRpcHelpers(runtime?.rpcServers.orEmpty())
            if (helpers > 0) {
                context.resources.getQuantityString(R.plurals.gallery_details_distributed_yes, helpers, helpers)
            } else {
                context.getString(R.string.gallery_details_distributed_yes_plain)
            }
        }
        false -> context.getString(R.string.gallery_details_distributed_no)
        null -> context.getString(R.string.gallery_details_distributed_unknown)
    }
    return context.getString(R.string.gallery_details_summary_label) to
        context.getString(R.string.gallery_details_summary_value, time, distributed)
}

internal fun addLoraLines(
    context: Context,
    lines: MutableList<Pair<String, String>>,
    loras: List<SdLoraSpec>,
    fallbackName: String?
) {
    if (loras.isEmpty()) {
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_lora), fallbackName)
        return
    }
    loras.forEach { lora ->
        lines += context.getString(R.string.imagegen_component_lora) to
            context.getString(R.string.gallery_details_lora_value, lora.filename, lora.strength.toString())
    }
}

/**
 * Single formatter for every gallery detail dialog (all-media gallery, SD gallery, image and
 * video screens). The first line summarises generation time and whether helpers were used.
 */
internal fun buildGeneratedMediaDetailLines(
    context: Context,
    file: File,
    modeLabel: String,
    createdAt: Long,
    resolution: String?,
    imageMetadata: SdGeneratedImageMetadata?,
    videoMetadata: GeneratedVideoMetadata?
): List<Pair<String, String>> {
    val lines = mutableListOf<Pair<String, String>>()
    val runtime = imageMetadata?.distributedRuntime ?: videoMetadata?.distributedRuntime
    val durationMs = imageMetadata?.generationDurationMs ?: videoMetadata?.generationDurationMs
    lines += generatedMediaSummaryLine(context, durationMs, runtime)
    lines += context.getString(R.string.sd_dist_gallery_file_label) to file.name
    lines += context.getString(R.string.sd_dist_gallery_mode_label) to modeLabel
    lines += context.getString(R.string.sd_dist_gallery_created_label) to formatGalleryDetailDate(createdAt)
    resolution?.let { lines += context.getString(R.string.sd_dist_gallery_resolution_label) to it }

    imageMetadata?.let { metadata ->
        metadata.generationDurationMs?.let {
            lines += context.getString(R.string.sd_dist_gallery_total_time_label) to formatSdGenerationDuration(it)
        }
        metadata.conditioningDurationMs?.let { lines += context.getString(R.string.sd_stage_conditioning_label) to formatSdGenerationDuration(it) }
        metadata.samplingDurationMs?.let { lines += context.getString(R.string.sd_stage_sampling_label) to formatSdGenerationDuration(it) }
        metadata.decodingDurationMs?.let { lines += context.getString(R.string.sd_stage_decoding_label) to formatSdGenerationDuration(it) }
        addIfNotBlank(lines, context.getString(R.string.imagegen_prompt_label), metadata.prompt)
        addIfNotBlank(lines, context.getString(R.string.imagegen_negative_prompt_label), metadata.negativePrompt)
        addIfNotBlank(lines, context.getString(R.string.imagegen_sd_model), metadata.modelName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_vae), metadata.vaeName)
        addLoraLines(context, lines, metadata.loras, metadata.loraName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_controlnet), metadata.controlNetName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_tae), metadata.taeName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_clip_l), metadata.clipLName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_clip_g), metadata.clipGName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_t5xxl), metadata.t5xxlName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_llm), metadata.llmName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_llm_vision), metadata.llmVisionName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_photomaker), metadata.photoMakerName)
        if (metadata.width > 0) lines += context.getString(R.string.imagegen_width_label) to metadata.width.toString()
        if (metadata.height > 0) lines += context.getString(R.string.imagegen_height_label) to metadata.height.toString()
        lines += context.getString(R.string.imagegen_steps_label) to metadata.steps.toString()
        if (metadata.cfgScale > 0f) lines += context.getString(R.string.imagegen_cfg_label) to metadata.cfgScale.toString()
        lines += context.getString(R.string.imagegen_seed_label) to metadata.seed.toString()
        lines += context.getString(R.string.imagegen_sampler_label) to metadata.samplingMethod.cliName
        metadata.scheduler?.let { lines += context.getString(R.string.imagegen_scheduler) to it.cliName }
        metadata.flowShift?.let { lines += context.getString(R.string.video_gen_flow_shift_label) to it.toString() }
        metadata.cacheMode?.let { lines += context.getString(R.string.gen_cache_mode_label) to it.cliName }
        addIfNotBlank(lines, context.getString(R.string.gen_cache_option_label), metadata.cacheOption)
        metadata.scmPolicy?.let { lines += context.getString(R.string.gen_cache_scm_policy_label) to it.cliName }
        addIfNotBlank(lines, context.getString(R.string.gen_cache_scm_mask_label), metadata.scmMask)
        addIfNotBlank(lines, context.getString(R.string.imagegen_upscale_repeats), metadata.upscaleRepeats.takeIf { it > 0 }?.toString())
        addIfNotBlank(lines, context.getString(R.string.video_gen_input_image_title), metadata.initImagePath?.let { File(it).name })
        if (metadata.modeEnum == SDMode.IMG2IMG) {
            lines += context.getString(R.string.imagegen_strength_label) to metadata.strength.toString()
        }
        if (metadata.loraStrength != 0f) {
            lines += context.getString(R.string.imagegen_lora_strength_label) to metadata.loraStrength.toString()
        }
        if (metadata.controlStrength != 0f) {
            lines += context.getString(R.string.imagegen_control_strength_label) to metadata.controlStrength.toString()
        }
        lines += context.getString(R.string.imagegen_threads) to metadata.threads.toString()
        lines += context.getString(R.string.video_gen_vae_tiling_label) to enabledLabel(context, metadata.vaeTiling)
        lines += context.getString(R.string.video_gen_diffusion_fa_label) to enabledLabel(context, metadata.diffusionFa)
        lines += context.getString(R.string.video_gen_mmap_label) to enabledLabel(context, metadata.mmap)
        addIfNotBlank(lines, context.getString(R.string.sd_models_params_backend_label), metadata.sdParamsBackendMode)
        addIfNotBlank(lines, context.getString(R.string.sd_models_runtime_backend_label), metadata.sdRuntimeBackendMode)
        addIfNotBlank(lines, context.getString(R.string.imagegen_max_cpu_ram_label), metadata.maxVramCpuGiB)
        addDistributedRuntimeLines(context, lines, metadata.distributedRuntime)
        addIfNotBlank(lines, context.getString(R.string.sd_dist_custom_flags), metadata.customFlags)
    }

    videoMetadata?.let { metadata ->
        metadata.generationDurationMs?.let {
            lines += context.getString(R.string.sd_dist_gallery_total_time_label) to formatSdGenerationDuration(it)
        }
        addIfNotBlank(lines, context.getString(R.string.video_gen_prompt_label), metadata.prompt)
        addIfNotBlank(lines, context.getString(R.string.video_gen_negative_prompt_label), metadata.negativePrompt)
        addIfNotBlank(lines, context.getString(R.string.video_gen_model_label), metadata.diffusionModelName)
        addLoraLines(context, lines, metadata.loras + metadata.highNoiseLoras, null)
        lines += context.getString(R.string.video_gen_frames_label) to metadata.videoFrames.toString()
        lines += context.getString(R.string.video_gen_fps_label) to metadata.fps.toString()
        lines += context.getString(R.string.video_gen_width_label) to metadata.width.toString()
        lines += context.getString(R.string.video_gen_height_label) to metadata.height.toString()
        lines += context.getString(R.string.video_gen_steps_label) to metadata.steps.toString()
        lines += context.getString(R.string.video_gen_cfg_scale_label) to metadata.cfgScale.toString()
        metadata.flowShift?.let { lines += context.getString(R.string.video_gen_flow_shift_label) to it.toString() }
        lines += context.getString(R.string.video_gen_sampler_label) to metadata.samplingMethod.cliName
        metadata.scheduler?.let { lines += context.getString(R.string.imagegen_scheduler) to it.cliName }
        metadata.cacheMode?.let { lines += context.getString(R.string.gen_cache_mode_label) to it.cliName }
        addIfNotBlank(lines, context.getString(R.string.gen_cache_option_label), metadata.cacheOption)
        metadata.scmPolicy?.let { lines += context.getString(R.string.gen_cache_scm_policy_label) to it.cliName }
        addIfNotBlank(lines, context.getString(R.string.gen_cache_scm_mask_label), metadata.scmMask)
        lines += context.getString(R.string.video_gen_threads_label) to metadata.threads.toString()
        lines += context.getString(R.string.video_gen_vae_toggle_label) to if (metadata.vaeEnabled) (metadata.vaeName ?: "-") else context.getString(R.string.video_gen_disabled)
        lines += context.getString(R.string.video_gen_t5_toggle_label) to if (metadata.t5xxlEnabled) (metadata.t5xxlName ?: "-") else context.getString(R.string.video_gen_disabled)
        lines += context.getString(R.string.video_gen_vae_tiling_label) to enabledLabel(context, metadata.vaeTiling)
        addIfNotBlank(lines, context.getString(R.string.video_gen_vae_tile_size_label), metadata.vaeTileSize)
        lines += context.getString(R.string.video_gen_diffusion_fa_label) to enabledLabel(context, metadata.diffusionFa)
        lines += context.getString(R.string.video_gen_mmap_label) to enabledLabel(context, metadata.mmap)
        addIfNotBlank(lines, context.getString(R.string.sd_models_params_backend_label), metadata.sdParamsBackendMode)
        addIfNotBlank(lines, context.getString(R.string.sd_models_runtime_backend_label), metadata.sdRuntimeBackendMode)
        addIfNotBlank(lines, context.getString(R.string.imagegen_max_cpu_ram_label), metadata.maxVramCpuGiB)
        addIfNotBlank(lines, context.getString(R.string.video_gen_input_image_title), metadata.initImagePath?.let { File(it).name })
        addDistributedRuntimeLines(context, lines, metadata.distributedRuntime)
    }

    return lines.distinctBy { it.first to it.second }
}

internal fun addDistributedRuntimeLines(
    context: Context,
    lines: MutableList<Pair<String, String>>,
    runtime: SdDistributedRuntimeConfig
) {
    if (!runtime.enabled) return
    lines += context.getString(R.string.sd_dist_enabled) to context.getString(R.string.sd_dist_live)
    if (runtime.compressLayerTransfers) {
        lines += context.getString(R.string.gallery_details_compress_transfers) to enabledLabel(context, true)
    }
    addIfNotBlank(lines, context.getString(R.string.sd_dist_rpc_servers), runtime.rpcServers)
    lines += context.getString(R.string.sd_dist_placement_mode) to runtime.placementMode.name
    addIfNotBlank(lines, context.getString(R.string.sd_dist_backend_spec), runtime.backendSpec)
    addIfNotBlank(lines, context.getString(R.string.sd_dist_params_backend_spec), runtime.paramsBackendSpec)
    addIfNotBlank(lines, context.getString(R.string.sd_dist_max_vram), runtime.maxVramSpec)
    addIfNotBlank(lines, context.getString(R.string.sd_dist_custom_flags), runtime.customFlags)
}

internal fun addIfNotBlank(
    lines: MutableList<Pair<String, String>>,
    label: String,
    value: String?
) {
    if (!value.isNullOrBlank()) {
        lines += label to value
    }
}

internal fun enabledLabel(context: Context, enabled: Boolean): String =
    context.getString(if (enabled) R.string.video_gen_enabled else R.string.video_gen_disabled)

internal fun formatSdGenerationDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
