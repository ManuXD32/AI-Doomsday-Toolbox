package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.R

/** Detailed model and media guide expansion kept separate from the host and chooser UI. */
internal object FeatureMediaModelGuides {
    val guides: List<FeatureGuide> = listOf(
        FeatureGuide(
            id = "models",
            titleRes = R.string.feature_guide_models_title,
            route = "model_hub",
            routeBases = setOf("model_hub", "model_sources", "models", "llm_models", "sd_models", "onnx_models", "whisper_models", "litert_models", "model_share"),
            recipes = listOf(
                featureRecipe(
                    id = "models.manager",
                    titleRes = R.string.feature_recipe_models_manager_title,
                    previewKey = "model_library",
                    route = "model_hub",
                    targetId = "models.download",
                    eventId = "models.download",
                    orientBodyRes = R.string.feature_step_models_manager_orient_body,
                    readBodyRes = R.string.feature_step_models_manager_read_body,
                    recoverBodyRes = R.string.feature_step_models_manager_recover_body
                ),
                featureRecipe(
                    id = "models.llm",
                    titleRes = R.string.feature_recipe_models_llm_title,
                    previewKey = "models_llm",
                    route = "llm_models",
                    targetId = "models.download",
                    eventId = "models.download",
                    orientBodyRes = R.string.feature_step_models_llm_orient_body,
                    readBodyRes = R.string.feature_step_models_llm_read_body,
                    recoverBodyRes = R.string.feature_step_models_llm_recover_body
                ),
                featureRecipe(
                    id = "models.sd_components",
                    titleRes = R.string.feature_recipe_models_sd_components_title,
                    previewKey = "models_sd_components",
                    route = "sd_models",
                    targetId = "models.download",
                    eventId = "models.download",
                    orientBodyRes = R.string.feature_step_models_sd_components_orient_body,
                    readBodyRes = R.string.feature_step_models_sd_components_read_body,
                    recoverBodyRes = R.string.feature_step_models_sd_components_recover_body
                ),
                featureRecipe(
                    id = "models.onnx_bundle",
                    titleRes = R.string.feature_recipe_models_onnx_bundle_title,
                    previewKey = "models_onnx_bundle",
                    route = "onnx_models",
                    targetId = "models.download",
                    eventId = "models.download",
                    orientBodyRes = R.string.feature_step_models_onnx_bundle_orient_body,
                    readBodyRes = R.string.feature_step_models_onnx_bundle_read_body,
                    recoverBodyRes = R.string.feature_step_models_onnx_bundle_recover_body
                ),
                featureRecipe(
                    id = "models.litert_backend",
                    titleRes = R.string.feature_recipe_models_litert_backend_title,
                    previewKey = "models_litert_backend",
                    route = "litert_models",
                    targetId = "models.download",
                    eventId = "models.download",
                    orientBodyRes = R.string.feature_step_models_litert_backend_orient_body,
                    readBodyRes = R.string.feature_step_models_litert_backend_read_body,
                    recoverBodyRes = R.string.feature_step_models_litert_backend_recover_body
                ),
                featureRecipe(
                    id = "models.whisper_vad",
                    titleRes = R.string.feature_recipe_models_whisper_vad_title,
                    previewKey = "models_whisper_vad",
                    route = "whisper_models",
                    targetId = "models.download",
                    eventId = "models.download",
                    orientBodyRes = R.string.feature_step_models_whisper_vad_orient_body,
                    readBodyRes = R.string.feature_step_models_whisper_vad_read_body,
                    recoverBodyRes = R.string.feature_step_models_whisper_vad_recover_body
                ),
                featureRecipe(
                    id = "models.custom_url",
                    titleRes = R.string.feature_recipe_models_custom_url_title,
                    previewKey = "models_custom_url",
                    route = "model_sources",
                    targetId = "models.download",
                    eventId = "models.download",
                    orientBodyRes = R.string.feature_step_models_custom_url_orient_body,
                    readBodyRes = R.string.feature_step_models_custom_url_read_body,
                    recoverBodyRes = R.string.feature_step_models_custom_url_recover_body
                ),
                featureRecipe(
                    id = "models.saved_links",
                    titleRes = R.string.feature_recipe_models_saved_links_title,
                    previewKey = "models_saved_links",
                    route = "model_sources",
                    targetId = "models.sources",
                    eventId = "models.sources",
                    orientBodyRes = R.string.feature_step_models_saved_links_orient_body,
                    readBodyRes = R.string.feature_step_models_saved_links_read_body,
                    recoverBodyRes = R.string.feature_step_models_saved_links_recover_body
                ),
                featureRecipe(
                    id = "models.unknown",
                    titleRes = R.string.feature_recipe_models_unknown_title,
                    previewKey = "models_unknown",
                    route = "model_sources",
                    targetId = "models.unknown",
                    eventId = "models.unknown",
                    orientBodyRes = R.string.feature_step_models_unknown_orient_body,
                    readBodyRes = R.string.feature_step_models_unknown_read_body,
                    recoverBodyRes = R.string.feature_step_models_unknown_recover_body
                ),
                featureRecipe(
                    id = "models.hf_folder",
                    titleRes = R.string.feature_recipe_models_hf_folder_title,
                    previewKey = "models_hf_folder",
                    route = "model_sources",
                    targetId = "models.browser",
                    eventId = "models.browser",
                    orientBodyRes = R.string.feature_step_models_hf_folder_orient_body,
                    readBodyRes = R.string.feature_step_models_hf_folder_read_body,
                    recoverBodyRes = R.string.feature_step_models_hf_folder_recover_body
                ),
                featureRecipe(
                    id = "models.bundles",
                    titleRes = R.string.feature_recipe_models_bundles_title,
                    previewKey = "models_bundles",
                    route = "model_sources",
                    targetId = "models.bundles",
                    eventId = "models.bundles",
                    orientBodyRes = R.string.feature_step_models_bundles_orient_body,
                    readBodyRes = R.string.feature_step_models_bundles_read_body,
                    recoverBodyRes = R.string.feature_step_models_bundles_recover_body
                ),
                featureRecipe(
                    id = "models.import",
                    titleRes = R.string.feature_recipe_models_import_title,
                    previewKey = "model_library",
                    route = "models",
                    targetId = "models.import",
                    eventId = "models.import",
                    orientBodyRes = R.string.feature_step_models_import_orient_body,
                    readBodyRes = R.string.feature_step_models_import_read_body,
                    recoverBodyRes = R.string.feature_step_models_import_recover_body
                ),
                featureRecipe(
                    id = "models.share",
                    titleRes = R.string.feature_recipe_models_share_title,
                    previewKey = "model_library",
                    route = "model_share",
                    targetId = "models.share",
                    eventId = "models.share",
                    orientBodyRes = R.string.feature_step_models_share_orient_body,
                    readBodyRes = R.string.feature_step_models_share_read_body,
                    recoverBodyRes = R.string.feature_step_models_share_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "image",
            titleRes = R.string.feature_guide_image_title,
            route = "image_gen",
            routeBases = setOf("image_gen", "image_gen_upscale", "onnx_image_gen", "onnx_background_removal"),
            recipes = listOf(
                featureRecipe(
                    id = "image.quickstart",
                    titleRes = R.string.feature_recipe_image_quickstart_title,
                    previewKey = "image_generation",
                    route = "image_gen",
                    targetId = "image.prompt",
                    eventId = null,
                    orientBodyRes = R.string.feature_step_image_quickstart_orient_body,
                    readBodyRes = R.string.feature_step_image_quickstart_read_body,
                    recoverBodyRes = R.string.feature_step_image_quickstart_recover_body
                ),
                featureRecipe(
                    id = "image.inpaint",
                    titleRes = R.string.feature_recipe_image_inpaint_title,
                    previewKey = "image_generation",
                    route = "image_gen",
                    targetId = "image.options",
                    eventId = "image.options",
                    orientBodyRes = R.string.feature_step_image_inpaint_orient_body,
                    readBodyRes = R.string.feature_step_image_inpaint_read_body,
                    recoverBodyRes = R.string.feature_step_image_inpaint_recover_body
                ),
                featureRecipe(
                    id = "image.onnx",
                    titleRes = R.string.feature_recipe_image_onnx_title,
                    previewKey = "onnx_image_generation",
                    route = "onnx_image_gen",
                    targetId = "image.onnx.input",
                    eventId = "image.onnx.input",
                    orientBodyRes = R.string.feature_step_image_onnx_orient_body,
                    readBodyRes = R.string.feature_step_image_onnx_read_body,
                    recoverBodyRes = R.string.feature_step_image_onnx_recover_body
                ),
                featureRecipe(
                    id = "image.background_remove",
                    titleRes = R.string.feature_recipe_image_background_remove_title,
                    previewKey = "onnx_background_removal",
                    route = "onnx_background_removal",
                    targetId = "image.background.input",
                    eventId = "image.background.input",
                    orientBodyRes = R.string.feature_step_image_background_remove_orient_body,
                    readBodyRes = R.string.feature_step_image_background_remove_read_body,
                    recoverBodyRes = R.string.feature_step_image_background_remove_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "video",
            titleRes = R.string.feature_guide_video_title,
            route = "video_gen",
            routeBases = setOf("video_gen", "video_upscaler", "video_interpolation", "subtitle_burn"),
            recipes = listOf(
                featureRecipe(
                    id = "video.quickstart",
                    titleRes = R.string.feature_recipe_video_quickstart_title,
                    previewKey = "video_generation",
                    route = "video_gen",
                    targetId = "video.create_tab",
                    eventId = "video.create_tab",
                    readTargetId = "video.profile",
                    readEventId = "video.profile",
                    recoverTargetId = "video.gallery_tab",
                    recoverEventId = "video.gallery_tab",
                    orientBodyRes = R.string.feature_step_video_quickstart_orient_body,
                    readBodyRes = R.string.feature_step_video_quickstart_read_body,
                    recoverBodyRes = R.string.feature_step_video_quickstart_recover_body
                ),
                featureRecipe(
                    id = "video.families",
                    titleRes = R.string.feature_recipe_video_families_title,
                    previewKey = "video_generation",
                    route = "video_gen",
                    targetId = "video.create_tab",
                    eventId = "video.create_tab",
                    readTargetId = "video.profile",
                    readEventId = "video.profile",
                    recoverTargetId = "video.gallery_tab",
                    recoverEventId = "video.gallery_tab",
                    orientBodyRes = R.string.feature_step_video_families_orient_body,
                    readBodyRes = R.string.feature_step_video_families_read_body,
                    recoverBodyRes = R.string.feature_step_video_families_recover_body
                ),
                featureRecipe(
                    id = "video.lingbot",
                    titleRes = R.string.feature_recipe_video_lingbot_title,
                    previewKey = "video_generation",
                    route = "video_gen",
                    targetId = "video.create_tab",
                    eventId = "video.create_tab",
                    readTargetId = "video.profile",
                    readEventId = "video.profile",
                    recoverTargetId = "video.gallery_tab",
                    recoverEventId = "video.gallery_tab",
                    orientBodyRes = R.string.feature_step_video_lingbot_orient_body,
                    readBodyRes = R.string.feature_step_video_lingbot_read_body,
                    recoverBodyRes = R.string.feature_step_video_lingbot_recover_body
                ),
                featureRecipe(
                    id = "video.lora",
                    titleRes = R.string.feature_recipe_video_lora_title,
                    previewKey = "video_generation",
                    route = "video_gen",
                    targetId = "video.create_tab",
                    eventId = "video.create_tab",
                    readTargetId = "video.loras",
                    readEventId = "video.loras",
                    recoverTargetId = "video.gallery_tab",
                    recoverEventId = "video.gallery_tab",
                    orientBodyRes = R.string.feature_step_video_lora_orient_body,
                    readBodyRes = R.string.feature_step_video_lora_read_body,
                    recoverBodyRes = R.string.feature_step_video_lora_recover_body
                ),
                featureRecipe(
                    id = "video.upscale",
                    titleRes = R.string.feature_recipe_video_upscale_title,
                    previewKey = "video_upscaler",
                    route = "video_upscaler",
                    targetId = "video.upscale.options",
                    eventId = "video.upscale.options",
                    orientBodyRes = R.string.feature_step_video_upscale_orient_body,
                    readBodyRes = R.string.feature_step_video_upscale_read_body,
                    recoverBodyRes = R.string.feature_step_video_upscale_recover_body
                ),
                featureRecipe(
                    id = "video.interpolation",
                    titleRes = R.string.feature_recipe_video_interpolation_title,
                    previewKey = "video_interpolation",
                    route = "video_interpolation",
                    targetId = "video.interpolation.options",
                    eventId = "video.interpolation.options",
                    orientBodyRes = R.string.feature_step_video_interpolation_orient_body,
                    readBodyRes = R.string.feature_step_video_interpolation_read_body,
                    recoverBodyRes = R.string.feature_step_video_interpolation_recover_body
                ),
                featureRecipe(
                    id = "video.subtitles",
                    titleRes = R.string.feature_recipe_video_subtitles_title,
                    previewKey = "subtitle_burn",
                    route = "subtitle_burn",
                    targetId = "video.subtitle.options",
                    eventId = "video.subtitle.options",
                    orientBodyRes = R.string.feature_step_video_subtitles_orient_body,
                    readBodyRes = R.string.feature_step_video_subtitles_read_body,
                    recoverBodyRes = R.string.feature_step_video_subtitles_recover_body
                ),
                featureRecipe(
                    id = "video.summary",
                    titleRes = R.string.feature_recipe_video_summary_title,
                    previewKey = "video_summary",
                    route = "video_sumup",
                    targetId = "documents.summary.input",
                    eventId = "documents.summary.input",
                    orientBodyRes = R.string.feature_step_video_summary_orient_body,
                    readBodyRes = R.string.feature_step_video_summary_read_body,
                    recoverBodyRes = R.string.feature_step_video_summary_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "voice",
            titleRes = R.string.feature_guide_voice_title,
            route = "onnx_tts",
            routeBases = setOf("onnx_tts", "onnx_tts_gallery", "live_translator", "audio_transcription"),
            recipes = listOf(
                featureRecipe(
                    id = "voice.tts",
                    titleRes = R.string.feature_recipe_voice_tts_title,
                    previewKey = "onnx_tts",
                    route = "onnx_tts",
                    targetId = "voice.tts.input",
                    eventId = "voice.tts.input",
                    orientBodyRes = R.string.feature_step_voice_tts_orient_body,
                    readBodyRes = R.string.feature_step_voice_tts_read_body,
                    recoverBodyRes = R.string.feature_step_voice_tts_recover_body
                ),
                featureRecipe(
                    id = "voice.translator",
                    titleRes = R.string.feature_recipe_voice_translator_title,
                    previewKey = "live_translator",
                    route = "live_translator",
                    targetId = "voice.translator.input",
                    eventId = "voice.translator.input",
                    orientBodyRes = R.string.feature_step_voice_translator_orient_body,
                    readBodyRes = R.string.feature_step_voice_translator_read_body,
                    recoverBodyRes = R.string.feature_step_voice_translator_recover_body
                ),
                featureRecipe(
                    id = "voice.transcription",
                    titleRes = R.string.feature_recipe_voice_transcription_title,
                    previewKey = "transcription",
                    route = "audio_transcription",
                    targetId = "voice.transcription.input",
                    eventId = "voice.transcription.input",
                    orientBodyRes = R.string.feature_step_voice_transcription_orient_body,
                    readBodyRes = R.string.feature_step_voice_transcription_read_body,
                    recoverBodyRes = R.string.feature_step_voice_transcription_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "documents",
            titleRes = R.string.feature_guide_documents_title,
            route = "pdf_toolbox",
            routeBases = setOf("pdf_toolbox", "pdf_summary", "settings_pdf", "workflows", "video_sumup", "dataset", "dataset_project"),
            recipes = listOf(
                featureRecipe(
                    id = "documents.pdf",
                    titleRes = R.string.feature_recipe_documents_pdf_title,
                    previewKey = "pdf_tools",
                    route = "pdf_toolbox",
                    targetId = "documents.pdf.input",
                    eventId = "documents.pdf.input",
                    orientBodyRes = R.string.feature_step_documents_pdf_orient_body,
                    readBodyRes = R.string.feature_step_documents_pdf_read_body,
                    recoverBodyRes = R.string.feature_step_documents_pdf_recover_body
                ),
                featureRecipe(
                    id = "documents.workflow",
                    titleRes = R.string.feature_recipe_documents_workflow_title,
                    previewKey = "workflows",
                    route = "workflows",
                    targetId = "documents.workflow.canvas",
                    eventId = "documents.workflow.canvas",
                    orientBodyRes = R.string.feature_step_documents_workflow_orient_body,
                    readBodyRes = R.string.feature_step_documents_workflow_read_body,
                    recoverBodyRes = R.string.feature_step_documents_workflow_recover_body
                ),
                featureRecipe(
                    id = "documents.video_summary",
                    titleRes = R.string.feature_recipe_documents_video_summary_title,
                    previewKey = "video_summary",
                    route = "video_sumup",
                    targetId = "documents.summary.input",
                    eventId = "documents.summary.input",
                    orientBodyRes = R.string.feature_step_documents_video_summary_orient_body,
                    readBodyRes = R.string.feature_step_documents_video_summary_read_body,
                    recoverBodyRes = R.string.feature_step_documents_video_summary_recover_body
                ),
                featureRecipe(
                    id = "documents.dataset",
                    titleRes = R.string.feature_recipe_documents_dataset_title,
                    previewKey = "dataset",
                    route = "dataset",
                    targetId = "documents.dataset.tabs",
                    eventId = "documents.dataset.tabs",
                    orientBodyRes = R.string.feature_step_documents_dataset_orient_body,
                    readBodyRes = R.string.feature_step_documents_dataset_read_body,
                    recoverBodyRes = R.string.feature_step_documents_dataset_recover_body
                )
            )
        )
    )
}

private fun featureRecipe(
    id: String,
    titleRes: Int,
    previewKey: String,
    route: String?,
    targetId: String?,
    eventId: String?,
    readTargetId: String? = null,
    readEventId: String? = null,
    recoverTargetId: String? = null,
    recoverEventId: String? = null,
    orientBodyRes: Int,
    readBodyRes: Int,
    recoverBodyRes: Int
) = FeatureRecipe(
    id = id,
    titleRes = titleRes,
    steps = listOf(
        FeatureGuideStep(
            id = "${id}.orient",
            titleRes = R.string.feature_step_purpose_title,
            bodyRes = orientBodyRes,
            previewKey = previewKey,
            targetId = targetId,
            eventId = eventId,
            route = route
        ),
        FeatureGuideStep(
            id = "${id}.read",
            titleRes = R.string.feature_step_result_title,
            bodyRes = readBodyRes,
            previewKey = previewKey,
            targetId = readTargetId,
            eventId = readEventId
        ),
        FeatureGuideStep(
            id = "${id}.recover",
            titleRes = R.string.feature_step_recover_title,
            bodyRes = recoverBodyRes,
            previewKey = previewKey,
            targetId = recoverTargetId,
            eventId = recoverEventId
        )
    )
)
