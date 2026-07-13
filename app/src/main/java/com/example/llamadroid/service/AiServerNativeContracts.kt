package com.example.llamadroid.service

import org.json.JSONArray
import org.json.JSONObject

data class AiServerNativeActionContract(
    val action: String,
    val appScreen: String,
    val entryPoint: String,
    val configType: String,
    val progressSource: String,
    val modelSource: String,
    val defaultsSource: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("action", action)
        .put("appScreen", appScreen)
        .put("entryPoint", entryPoint)
        .put("configType", configType)
        .put("progressSource", progressSource)
        .put("modelSource", modelSource)
        .put("defaultsSource", defaultsSource)
}

object AiServerNativeContracts {
    fun forServer(type: AiServerType): List<AiServerNativeActionContract> = when (type) {
        AiServerType.IMAGE -> listOf(
            contract("sd_txt2img", "ImageGenScreen", "StableDiffusionService.createStartIntent", "SDConfig", "StableDiffusionStateHolder", "ModelRepository/ModelType.SD_*", "SDConfig"),
            contract("sd_img2img", "ImageGenScreen", "StableDiffusionService.createStartIntent", "SDConfig", "StableDiffusionStateHolder", "ModelRepository/ModelType.SD_*", "SDConfig"),
            contract("sd_upscale", "ImageGenScreen", "StableDiffusionService.createStartUpscaleIntent", "SDUpscaleConfig", "StableDiffusionStateHolder", "ModelRepository/ModelType.SD_UPSCALER", "SDUpscaleConfig"),
            contract("onnx_txt2img", "OnnxImageGenScreen", "OnnxImageGenerationService.start", "OnnxImageGenConfig", "OnnxImageGenRuntimeState", "OnnxStorage/ModelRepository.ONNX_IMAGE_GEN", "OnnxImageGenConfig"),
            contract("onnx_img2img", "OnnxImageGenScreen", "OnnxImageGenerationService.start", "OnnxImageGenConfig", "OnnxImageGenRuntimeState", "OnnxStorage/ModelRepository.ONNX_IMAGE_GEN", "OnnxImageGenConfig"),
            contract("onnx_bgr", "OnnxBackgroundRemovalScreen", "OnnxBackgroundRemovalService.start", "OnnxBackgroundRemovalConfig", "OnnxBackgroundRemovalRuntimeState", "OnnxStorage/ModelRepository.ONNX_BACKGROUND_REMOVAL", "OnnxBackgroundRemovalConfig")
        )
        AiServerType.VIDEO -> listOf(
            contract("txt2vid", "VideoGenScreen", "VideoGenerationService.createStartIntent", "VideoGenerationConfig", "VideoGenerationStateHolder.txt2vid", "ModelRepository/ModelType.SD_DIFFUSION + SD_CAPABILITY_VID_GEN", "VideoGenerationConfig"),
            contract("img2vid", "VideoGenScreen", "VideoGenerationService.createStartIntent", "VideoGenerationConfig", "VideoGenerationStateHolder.img2vid", "ModelRepository/ModelType.SD_DIFFUSION + SD_CAPABILITY_VID_GEN", "VideoGenerationConfig")
        )
        AiServerType.WORKFLOWS -> listOf(
            contract("transcribe_summary", "WorkflowsScreen", "VideoSumupService.startSummarization", "RemoteSummarySettingsSnapshot + Whisper args", "WorkflowStateHolder", "ModelRepository/ModelType.WHISPER", "SettingsRepository.DEFAULT_TRANSCRIPT_*"),
            contract("txt2img_upscale", "WorkflowsScreen", "StableDiffusionService.createStartWorkflowIntent", "SDWorkflowConfig", "StableDiffusionStateHolder", "ModelRepository/ModelType.SD_*", "SDConfig + SDUpscaleConfig"),
            contract("manga_translation", "WorkflowsScreen", "PDFService.translateMangaCbzBatch", "RemoteSummarySettingsSnapshot + PDFTranslationJobSpec", "PDFTranslationJobService", "ModelRepository/summary backends", "PDFTranslationLogic.DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT"),
            contract("media_translation", "WorkflowsScreen", "MediaTranslationWorkflowService.start", "MediaTranslationJobSpec", "MediaTranslationWorkflowStateHolder", "ModelRepository/WHISPER + ONNX_TTS", "MediaTranslationWorkflowService + SettingsRepository"),
            contract("subtitle_translation", "WorkflowsScreen", "MediaTranslationWorkflowService.startSubtitleTranslation", "SubtitleTranslationJobSpec", "MediaTranslationWorkflowStateHolder", "ModelRepository/WHISPER", "SubtitleBurnStyleSpec + SettingsRepository")
        )
        AiServerType.TTS -> listOf(
            contract("tts_text", "OnnxTtsScreen", "OnnxTtsGenerationService.start", "SupertonicTtsConfig", "OnnxTtsRuntimeState", "OnnxStorage/ModelRepository.ONNX_TTS + resolveSupertonicVoices", "SUPERTONIC_DEFAULT_*"),
            contract("tts_document", "OnnxTtsScreen", "OnnxTtsGenerationService.start", "SupertonicTtsConfig", "OnnxTtsRuntimeState", "OnnxStorage/ModelRepository.ONNX_TTS + resolveSupertonicVoices", "SUPERTONIC_DEFAULT_*")
        )
        AiServerType.VIDEO_UPSCALE -> listOf(
            contract("video_upscale", "VideoUpscalerScreen", "VideoUpscalerService.upscale", "VideoUpscalerConfig", "VideoUpscalerStateHolder", "UpscalerModels", "VideoUpscalerConfig + UpscalerModels")
        )
        AiServerType.DOCS_DATASETS -> listOf(
            contract("pdf_merge", "PDFToolboxScreen", "PDFService.mergePdfs", "PDF tool request", "AiServerJobStore/PDFService", "Uploaded PDFs", "PDFService"),
            contract("pdf_split", "PDFToolboxScreen", "PDFService.splitPdf", "PDF tool request", "AiServerJobStore/PDFService", "Uploaded PDF", "PDFService"),
            contract("pdf_extract_text", "PDFToolboxScreen", "PDFService.extractText", "PDF tool request", "AiServerJobStore/PDFService", "Uploaded PDF", "PDFService"),
            contract("pdf_ocr_text", "PDFToolboxScreen", "PDFService.ocrText", "PDF tool request", "AiServerJobStore/PDFService", "Uploaded PDF/image", "PDFService"),
            contract("pdf_ocr_searchable", "PDFToolboxScreen", "PDFService.createSearchableOcrPdf", "PDF tool request", "AiServerJobStore/PDFService", "Uploaded PDF", "PDFService"),
            contract("pdf_translate_ocr", "PDFTranslationSettingsScreen/PDFToolboxScreen", "PDFTranslationJobService.enqueue", "RemoteSummarySettingsSnapshot + PDF OCR request", "PDFTranslationJobService", "ModelRepository/summary backends", "PDFTranslationLogic.DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT"),
            contract("pdf_translate_text_layer", "PDFTranslationSettingsScreen/PDFToolboxScreen", "PDFTranslationJobService.enqueue", "RemoteSummarySettingsSnapshot + PDF text-layer request", "PDFTranslationJobService", "ModelRepository/summary backends", "PDFTranslationLogic.DEFAULT_PAGE_TRANSLATION_SYSTEM_PROMPT"),
            contract("pdf_images_to_pdf", "PDFToolboxScreen", "PDFService.imagesToPdf", "PDF tool request", "AiServerJobStore/PDFService", "Uploaded images", "PDFService"),
            contract("pdf_compress", "PDFToolboxScreen", "PDFService.compressPdf", "PDF tool request", "AiServerJobStore/PDFService", "Uploaded PDF", "PDFService"),
            contract("pdf_split_size", "PDFToolboxScreen", "PDFService.splitBySize", "PDF tool request", "AiServerJobStore/PDFService", "Uploaded PDF", "PDFService"),
            contract("pdf_summary", "PDFSummaryScreen", "PDFSummaryService.startSummarization", "RemoteSummarySettingsSnapshot", "PDFSummaryService", "ModelRepository/summary backends", "PDFSummaryService.DEFAULT_*"),
            contract("video_summary", "VideoSumupScreen", "VideoSumupService.startSummarization", "RemoteSummarySettingsSnapshot + Whisper args", "VideoSummaryStateHolder", "ModelRepository.WHISPER + summary backends", "SettingsRepository.videoSummarySettings"),
            contract("dataset_import", "DatasetProjectScreen", "DatasetForegroundService.enqueueImport", "DatasetSource", "DatasetProcessor/AiRuntimeJobStore", "Dataset DAO projects", "DatasetProjectScreen defaults"),
            contract("dataset_pipeline", "DatasetProjectScreen", "DatasetForegroundService.enqueueBatch", "Dataset queue request", "DatasetProcessor/AiRuntimeJobStore", "Dataset DAO projects", "PromptType defaults"),
            contract("dataset_export", "DatasetProjectScreen", "DatasetExporter.export", "DatasetExportRequest", "AiServerJobStore", "Dataset DAO projects", "DatasetExporter")
        )
        AiServerType.LLAMA_CHAT -> listOf(
            contract("web_chat_send", "LlamaChatScreen", "LlamaClientService-compatible web runner", "LlamaChatEntity + LlamaServerEntity + NativeChatToolConfig", "AiServerWebMessageEntity + AiServerWebToolEventEntity", "Web providers + ModelRepository/LiteRT/Ollama/llama-swap", "NativeChatToolConfig + web provider params")
        )
        AiServerType.AI_HUB -> emptyList()
    }

    fun serverJson(type: AiServerType): JSONObject = JSONObject()
        .put("policy", "Descriptor fields, model buckets, defaults, validation, job dispatch, and progress are sourced from the native app screen/service/config listed for each action.")
        .put("actions", JSONArray(forServer(type).map { it.toJson() }))

    fun actionIdsForServer(type: AiServerType): Set<String> = forServer(type).map { it.action }.toSet()

    private fun contract(
        action: String,
        appScreen: String,
        entryPoint: String,
        configType: String,
        progressSource: String,
        modelSource: String,
        defaultsSource: String
    ) = AiServerNativeActionContract(
        action = action,
        appScreen = appScreen,
        entryPoint = entryPoint,
        configType = configType,
        progressSource = progressSource,
        modelSource = modelSource,
        defaultsSource = defaultsSource
    )
}
