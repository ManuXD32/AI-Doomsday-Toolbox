package com.example.llamadroid.service

import com.example.llamadroid.data.db.NoteDao
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.data.db.OrganizerAlarmEntity
import com.example.llamadroid.data.db.OrganizerDao
import com.example.llamadroid.data.db.OrganizerEventEntity
import com.example.llamadroid.data.db.OrganizerLlmSettingsEntity
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.onnx.OnnxBackendOverride
import com.example.llamadroid.onnx.OnnxExecutionMode
import com.example.llamadroid.onnx.OnnxGraphOptimizationLevel
import com.example.llamadroid.onnx.OnnxRuntimeBackend
import com.example.llamadroid.wear.PhoneWearGateway
import com.example.llamadroid.wear.PinnedOrganizerNoteStore
import com.example.llamadroid.onnx.SUPERTONIC_DEFAULT_LANGUAGE
import com.example.llamadroid.onnx.SUPERTONIC_DEFAULT_SPEED
import com.example.llamadroid.onnx.SUPERTONIC_DEFAULT_TOTAL_STEPS
import com.example.llamadroid.onnx.supertonicLanguageCodes
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.toJsonArray
import com.example.llamadroid.sd.toSdLoraSpecs
import com.example.llamadroid.util.AIConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.Reader
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.pow

enum class NativeChatImageGenerationEngine {
    ONNX,
    SD
}

data class NativeChatSdImageToolParams(
    val model: String? = null,
    val vaePath: String? = null,
    val taePath: String? = null,
    val clipLPath: String? = null,
    val clipGPath: String? = null,
    val t5xxlPath: String? = null,
    val llmPath: String? = null,
    val llmVisionPath: String? = null,
    val photoMakerPath: String? = null,
    val loras: List<SdLoraSpec> = emptyList(),
    val loraApplyMode: SdLoraApplyMode? = null,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val steps: Int = DEFAULT_STEPS,
    val cfgScale: Float = DEFAULT_CFG,
    val sampler: SamplingMethod = SamplingMethod.EULER_A,
    val seed: String = "",
    val negativePrompt: String = "",
    val threads: Int = DEFAULT_THREADS,
    val flowShift: String = "",
    val diffusionFa: Boolean = false,
    val mmap: Boolean = false,
    val vaeConvDirect: Boolean = false,
    val qwenImageZeroCondT: Boolean = false,
    val chromaDisableDitMask: Boolean = false
) {
    fun toParamMap(): Map<String, Any?> = linkedMapOf(
        NativeChatToolConfig.KEY_IMAGE_SD_MODEL to model.orEmpty(),
        NativeChatToolConfig.KEY_IMAGE_SD_VAE to vaePath.orEmpty(),
        NativeChatToolConfig.KEY_IMAGE_SD_TAE to taePath.orEmpty(),
        NativeChatToolConfig.KEY_IMAGE_SD_CLIP_L to clipLPath.orEmpty(),
        NativeChatToolConfig.KEY_IMAGE_SD_CLIP_G to clipGPath.orEmpty(),
        NativeChatToolConfig.KEY_IMAGE_SD_T5XXL to t5xxlPath.orEmpty(),
        NativeChatToolConfig.KEY_IMAGE_SD_LLM to llmPath.orEmpty(),
        NativeChatToolConfig.KEY_IMAGE_SD_LLM_VISION to llmVisionPath.orEmpty(),
        NativeChatToolConfig.KEY_IMAGE_SD_PHOTOMAKER to photoMakerPath.orEmpty(),
        NativeChatToolConfig.KEY_IMAGE_SD_LORAS to loras.toJsonArray().toString(),
        NativeChatToolConfig.KEY_IMAGE_SD_LORA_APPLY_MODE to loraApplyMode?.cliName.orEmpty(),
        NativeChatToolConfig.KEY_IMAGE_SD_WIDTH to width.coerceIn(MIN_SIZE, MAX_SIZE),
        NativeChatToolConfig.KEY_IMAGE_SD_HEIGHT to height.coerceIn(MIN_SIZE, MAX_SIZE),
        NativeChatToolConfig.KEY_IMAGE_SD_STEPS to steps.coerceIn(MIN_STEPS, MAX_STEPS),
        NativeChatToolConfig.KEY_IMAGE_SD_CFG to cfgScale.coerceIn(MIN_CFG, MAX_CFG),
        NativeChatToolConfig.KEY_IMAGE_SD_SAMPLER to sampler.name,
        NativeChatToolConfig.KEY_IMAGE_SD_SEED to seed,
        NativeChatToolConfig.KEY_IMAGE_SD_NEGATIVE_PROMPT to negativePrompt,
        NativeChatToolConfig.KEY_IMAGE_SD_THREADS to threads.coerceIn(MIN_THREADS, MAX_THREADS),
        NativeChatToolConfig.KEY_IMAGE_SD_FLOW_SHIFT to flowShift,
        NativeChatToolConfig.KEY_IMAGE_SD_DIFFUSION_FA to diffusionFa,
        NativeChatToolConfig.KEY_IMAGE_SD_MMAP to mmap,
        NativeChatToolConfig.KEY_IMAGE_SD_VAE_CONV_DIRECT to vaeConvDirect,
        NativeChatToolConfig.KEY_IMAGE_SD_QWEN_ZERO_COND_T to qwenImageZeroCondT,
        NativeChatToolConfig.KEY_IMAGE_SD_CHROMA_DISABLE_DIT_MASK to chromaDisableDitMask
    )

    companion object {
        const val DEFAULT_WIDTH = 512
        const val DEFAULT_HEIGHT = 512
        const val DEFAULT_STEPS = 20
        const val DEFAULT_CFG = 7.0f
        const val DEFAULT_THREADS = -1
        const val MIN_SIZE = 256
        const val MAX_SIZE = 1024
        const val MIN_STEPS = 1
        const val MAX_STEPS = 50
        const val MIN_CFG = 1f
        const val MAX_CFG = 20f
        const val MIN_THREADS = -1
        const val MAX_THREADS = 16
    }
}

data class NativeChatImageToolParams(
    val engine: NativeChatImageGenerationEngine = NativeChatImageGenerationEngine.ONNX,
    val model: String? = null,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val steps: Int = DEFAULT_STEPS,
    val cfgScale: Float = DEFAULT_CFG,
    val seed: String = "",
    val negativePrompt: String = "",
    val backend: OnnxRuntimeBackend = OnnxRuntimeBackend.CPU,
    val runtimeThreads: Int? = null,
    val graphOptimizationLevel: OnnxGraphOptimizationLevel = OnnxGraphOptimizationLevel.ALL,
    val unetBackendOverride: OnnxBackendOverride = OnnxBackendOverride.DEFAULT,
    val vaeDecoderBackendOverride: OnnxBackendOverride = OnnxBackendOverride.DEFAULT,
    val vaeEncoderBackendOverride: OnnxBackendOverride = OnnxBackendOverride.DEFAULT,
    val intraOpThreads: Int? = null,
    val interOpThreads: Int? = null,
    val executionMode: OnnxExecutionMode = OnnxExecutionMode.SEQUENTIAL,
    val memoryPatternOptimization: Boolean = true,
    val cpuArenaAllocator: Boolean = true,
    val nnapiCpuDisabled: Boolean = true,
    val nnapiUseFp16: Boolean = false,
    val sdParams: NativeChatSdImageToolParams = NativeChatSdImageToolParams()
) {
    fun toParamMap(): Map<String, Any?> = linkedMapOf(
        NativeChatToolConfig.KEY_IMAGE_ENGINE to engine.name,
        NativeChatToolConfig.KEY_IMAGE_MODEL to model.orEmpty(),
        NativeChatToolConfig.KEY_IMAGE_WIDTH to width.coerceIn(MIN_SIZE, MAX_SIZE),
        NativeChatToolConfig.KEY_IMAGE_HEIGHT to height.coerceIn(MIN_SIZE, MAX_SIZE),
        NativeChatToolConfig.KEY_IMAGE_STEPS to steps.coerceIn(MIN_STEPS, MAX_STEPS),
        NativeChatToolConfig.KEY_IMAGE_CFG to cfgScale.coerceIn(MIN_CFG, MAX_CFG),
        NativeChatToolConfig.KEY_IMAGE_SEED to seed,
        NativeChatToolConfig.KEY_IMAGE_NEGATIVE_PROMPT to negativePrompt,
        NativeChatToolConfig.KEY_IMAGE_BACKEND to backend.name,
        NativeChatToolConfig.KEY_IMAGE_RUNTIME_THREADS to runtimeThreads,
        NativeChatToolConfig.KEY_IMAGE_GRAPH_OPT to graphOptimizationLevel.name,
        NativeChatToolConfig.KEY_IMAGE_UNET_BACKEND to unetBackendOverride.name,
        NativeChatToolConfig.KEY_IMAGE_VAE_DECODER_BACKEND to vaeDecoderBackendOverride.name,
        NativeChatToolConfig.KEY_IMAGE_VAE_ENCODER_BACKEND to vaeEncoderBackendOverride.name,
        NativeChatToolConfig.KEY_IMAGE_INTRA_THREADS to intraOpThreads,
        NativeChatToolConfig.KEY_IMAGE_INTER_THREADS to interOpThreads,
        NativeChatToolConfig.KEY_IMAGE_EXECUTION_MODE to executionMode.name,
        NativeChatToolConfig.KEY_IMAGE_MEMORY_PATTERN to memoryPatternOptimization,
        NativeChatToolConfig.KEY_IMAGE_CPU_ARENA to cpuArenaAllocator,
        NativeChatToolConfig.KEY_IMAGE_NNAPI_CPU_DISABLED to nnapiCpuDisabled,
        NativeChatToolConfig.KEY_IMAGE_NNAPI_FP16 to nnapiUseFp16
    ).plus(sdParams.toParamMap()).filterValues { it != null }

    companion object {
        const val DEFAULT_WIDTH = 512
        const val DEFAULT_HEIGHT = 512
        const val DEFAULT_STEPS = 20
        const val DEFAULT_CFG = 6.5f
        const val MIN_SIZE = 64
        const val MAX_SIZE = 2048
        const val MIN_STEPS = 1
        const val MAX_STEPS = 150
        const val MIN_CFG = 0.1f
        const val MAX_CFG = 30f
    }
}

data class NativeChatBackgroundRemovalToolParams(
    val model: String? = null,
    val backend: OnnxRuntimeBackend = OnnxRuntimeBackend.CPU,
    val runtimeThreads: Int? = null,
    val graphOptimizationLevel: OnnxGraphOptimizationLevel = OnnxGraphOptimizationLevel.ALL,
    val alphaThreshold: Float = DEFAULT_ALPHA_THRESHOLD,
    val featherRadius: Int = DEFAULT_FEATHER_RADIUS,
    val maskSoftness: Float = DEFAULT_MASK_SOFTNESS,
    val maskContrast: Float = DEFAULT_MASK_CONTRAST,
    val exportMask: Boolean = false,
    val resizeBeforeProcessing: Boolean = true,
    val resizeMaxEdge: Int = DEFAULT_RESIZE_MAX_EDGE
) {
    fun toParamMap(): Map<String, Any?> = linkedMapOf(
        NativeChatToolConfig.KEY_BGR_MODEL to model.orEmpty(),
        NativeChatToolConfig.KEY_BGR_BACKEND to backend.name,
        NativeChatToolConfig.KEY_BGR_RUNTIME_THREADS to runtimeThreads,
        NativeChatToolConfig.KEY_BGR_GRAPH_OPT to graphOptimizationLevel.name,
        NativeChatToolConfig.KEY_BGR_ALPHA_THRESHOLD to alphaThreshold.coerceIn(MIN_ALPHA_THRESHOLD, MAX_ALPHA_THRESHOLD),
        NativeChatToolConfig.KEY_BGR_FEATHER_RADIUS to featherRadius.coerceIn(MIN_FEATHER_RADIUS, MAX_FEATHER_RADIUS),
        NativeChatToolConfig.KEY_BGR_MASK_SOFTNESS to maskSoftness.coerceIn(MIN_MASK_SOFTNESS, MAX_MASK_SOFTNESS),
        NativeChatToolConfig.KEY_BGR_MASK_CONTRAST to maskContrast.coerceIn(MIN_MASK_CONTRAST, MAX_MASK_CONTRAST),
        NativeChatToolConfig.KEY_BGR_EXPORT_MASK to exportMask,
        NativeChatToolConfig.KEY_BGR_RESIZE_BEFORE_PROCESSING to resizeBeforeProcessing,
        NativeChatToolConfig.KEY_BGR_RESIZE_MAX_EDGE to resizeMaxEdge.coerceIn(MIN_RESIZE_MAX_EDGE, MAX_RESIZE_MAX_EDGE)
    ).filterValues { it != null }

    companion object {
        const val DEFAULT_ALPHA_THRESHOLD = 0.5f
        const val DEFAULT_FEATHER_RADIUS = 1
        const val DEFAULT_MASK_SOFTNESS = 1f
        const val DEFAULT_MASK_CONTRAST = 1f
        const val DEFAULT_RESIZE_MAX_EDGE = 512
        const val MIN_ALPHA_THRESHOLD = 0f
        const val MAX_ALPHA_THRESHOLD = 1f
        const val MIN_FEATHER_RADIUS = 0
        const val MAX_FEATHER_RADIUS = 16
        const val MIN_MASK_SOFTNESS = 0f
        const val MAX_MASK_SOFTNESS = 1f
        const val MIN_MASK_CONTRAST = 0.25f
        const val MAX_MASK_CONTRAST = 4f
        const val MIN_RESIZE_MAX_EDGE = 128
        const val MAX_RESIZE_MAX_EDGE = 2048
    }
}

data class NativeChatToolConfig(
    val toolsEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val webSearchMaxPages: Int = DEFAULT_SEARCH_PAGES,
    val webSearchMaxChars: Int = DEFAULT_PAGE_CHARS,
    val kiwixSearchEnabled: Boolean = false,
    val kiwixServerUrl: String = DEFAULT_KIWIX_URL,
    val kiwixMaxPages: Int = DEFAULT_SEARCH_PAGES,
    val kiwixMaxChars: Int = DEFAULT_PAGE_CHARS,
    val fetchUrlEnabled: Boolean = false,
    val fetchUrlMaxChars: Int = DEFAULT_FETCH_CHARS,
    val deepResearchEnabled: Boolean = false,
    val deepResearchImportIntoSelectedKbEnabled: Boolean = false,
    val deepResearchSourceLimit: Int = DeepResearchSupport.DEFAULT_SOURCE_LIMIT,
    val dateTimeEnabled: Boolean = true,
    val calculatorEnabled: Boolean = true,
    val noteToolsEnabled: Boolean = false,
    val pinnedNoteId: Int? = null,
    val todoToolsEnabled: Boolean = false,
    val calendarToolsEnabled: Boolean = false,
    val alarmToolsEnabled: Boolean = false,
    val knowledgeBaseEnabled: Boolean = false,
    val knowledgeBaseAutoContextEnabled: Boolean = false,
    val selectedKnowledgeBaseIds: List<Long> = emptyList(),
    val chatDocumentKnowledgeBaseId: Long? = null,
    val knowledgeBaseMaxResults: Int = DEFAULT_KB_RESULTS,
    val imageGenerationEnabled: Boolean = false,
    val imageIterationEnabled: Boolean = false,
    val imageParams: NativeChatImageToolParams = NativeChatImageToolParams(),
    val backgroundRemovalEnabled: Boolean = false,
    val backgroundRemovalParams: NativeChatBackgroundRemovalToolParams = NativeChatBackgroundRemovalToolParams(),
    val assistantTtsEnabled: Boolean = false,
    val assistantTtsLanguage: String = SUPERTONIC_DEFAULT_LANGUAGE,
    val assistantTtsVoiceName: String? = null,
    val assistantTtsTotalSteps: Int = SUPERTONIC_DEFAULT_TOTAL_STEPS,
    val assistantTtsSpeed: Float = SUPERTONIC_DEFAULT_SPEED,
    val callSilenceAfterSpeechSeconds: Int = DEFAULT_CALL_SILENCE_AFTER_SPEECH_SECONDS,
    val callNoSpeechTimeoutSeconds: Int = DEFAULT_CALL_NO_SPEECH_TIMEOUT_SECONDS,
    val maxToolRounds: Int = DEFAULT_TOOL_ROUNDS
) {
    fun hasEnabledTools(): Boolean = toolsEnabled && (
        webSearchEnabled ||
            kiwixSearchEnabled ||
            fetchUrlEnabled ||
            deepResearchEnabled ||
            dateTimeEnabled ||
            calculatorEnabled ||
            noteToolsEnabled ||
            pinnedNoteId != null ||
            todoToolsEnabled ||
            calendarToolsEnabled ||
            alarmToolsEnabled ||
            knowledgeBaseEnabled ||
            imageGenerationEnabled ||
            backgroundRemovalEnabled
        )

    fun knowledgeBaseScopeIds(): List<Long> {
        val chatDocumentId = chatDocumentKnowledgeBaseId?.takeIf { it > 0L }
        if (chatDocumentId != null) return listOf(chatDocumentId)
        return selectedKnowledgeBaseIds
            .filter { it > 0L }
            .distinct()
    }

    fun effectiveWithServerDefaults(serverDefaults: NativeChatToolConfig): NativeChatToolConfig {
        val hasChatDocumentScope = chatDocumentKnowledgeBaseId?.let { it > 0L } == true
        val effectiveChatToolsEnabled = toolsEnabled && serverDefaults.toolsEnabled
        val forceChatDocumentKnowledgeBase = hasChatDocumentScope &&
            serverDefaults.toolsEnabled &&
            serverDefaults.knowledgeBaseEnabled
        val effectiveToolsEnabled = effectiveChatToolsEnabled || forceChatDocumentKnowledgeBase
        val effectiveKnowledgeBaseEnabled = (
            effectiveChatToolsEnabled &&
                knowledgeBaseEnabled &&
                serverDefaults.knowledgeBaseEnabled
            ) || forceChatDocumentKnowledgeBase
        val pinnedNoteOnly = pinnedNoteId?.let { it > 0 } == true
        return copy(
            toolsEnabled = effectiveToolsEnabled,
            webSearchEnabled = effectiveChatToolsEnabled && webSearchEnabled && serverDefaults.webSearchEnabled,
            kiwixSearchEnabled = effectiveChatToolsEnabled && kiwixSearchEnabled && serverDefaults.kiwixSearchEnabled,
            fetchUrlEnabled = effectiveChatToolsEnabled && fetchUrlEnabled && serverDefaults.fetchUrlEnabled,
            deepResearchEnabled = effectiveChatToolsEnabled && deepResearchEnabled && serverDefaults.deepResearchEnabled,
            deepResearchImportIntoSelectedKbEnabled = effectiveChatToolsEnabled &&
                deepResearchEnabled &&
                serverDefaults.deepResearchEnabled &&
                deepResearchImportIntoSelectedKbEnabled &&
                serverDefaults.deepResearchImportIntoSelectedKbEnabled,
            deepResearchSourceLimit = DeepResearchSupport.normalizeSourceLimit(deepResearchSourceLimit),
            dateTimeEnabled = effectiveChatToolsEnabled && dateTimeEnabled && serverDefaults.dateTimeEnabled,
            calculatorEnabled = effectiveChatToolsEnabled && calculatorEnabled && serverDefaults.calculatorEnabled,
            noteToolsEnabled = effectiveChatToolsEnabled && noteToolsEnabled && serverDefaults.noteToolsEnabled && !pinnedNoteOnly,
            pinnedNoteId = pinnedNoteId?.takeIf { effectiveChatToolsEnabled && it > 0 },
            todoToolsEnabled = effectiveChatToolsEnabled && todoToolsEnabled && serverDefaults.todoToolsEnabled && !pinnedNoteOnly,
            calendarToolsEnabled = effectiveChatToolsEnabled && calendarToolsEnabled && serverDefaults.calendarToolsEnabled,
            alarmToolsEnabled = effectiveChatToolsEnabled && alarmToolsEnabled && serverDefaults.alarmToolsEnabled,
            knowledgeBaseEnabled = effectiveKnowledgeBaseEnabled,
            knowledgeBaseAutoContextEnabled = forceChatDocumentKnowledgeBase || (
                effectiveChatToolsEnabled &&
                    knowledgeBaseEnabled &&
                    serverDefaults.knowledgeBaseEnabled &&
                    knowledgeBaseAutoContextEnabled &&
                    serverDefaults.knowledgeBaseAutoContextEnabled
                ),
            imageGenerationEnabled = effectiveChatToolsEnabled && imageGenerationEnabled && serverDefaults.imageGenerationEnabled,
            imageIterationEnabled = effectiveChatToolsEnabled &&
                imageGenerationEnabled &&
                serverDefaults.imageGenerationEnabled &&
                imageIterationEnabled &&
                serverDefaults.imageIterationEnabled,
            backgroundRemovalEnabled = effectiveChatToolsEnabled &&
                backgroundRemovalEnabled &&
                serverDefaults.backgroundRemovalEnabled,
            assistantTtsEnabled = assistantTtsEnabled,
            assistantTtsLanguage = assistantTtsLanguage,
            assistantTtsVoiceName = assistantTtsVoiceName,
            assistantTtsTotalSteps = assistantTtsTotalSteps,
            assistantTtsSpeed = assistantTtsSpeed,
            callSilenceAfterSpeechSeconds = callSilenceAfterSpeechSeconds,
            callNoSpeechTimeoutSeconds = callNoSpeechTimeoutSeconds
        )
    }

    fun toParamMap(): Map<String, Any> = linkedMapOf<String, Any>().apply {
        put(KEY_TOOLS_ENABLED, toolsEnabled)
        put(KEY_WEB_SEARCH_ENABLED, webSearchEnabled)
        put(KEY_WEB_SEARCH_MAX_PAGES, webSearchMaxPages.coerceIn(MIN_SEARCH_PAGES, MAX_SEARCH_PAGES))
        put(KEY_WEB_SEARCH_MAX_CHARS, webSearchMaxChars.coerceIn(MIN_PAGE_CHARS, MAX_PAGE_CHARS))
        put(KEY_KIWIX_SEARCH_ENABLED, kiwixSearchEnabled)
        put(KEY_KIWIX_SERVER_URL, normalizedKiwixUrl())
        put(KEY_KIWIX_MAX_PAGES, kiwixMaxPages.coerceIn(MIN_SEARCH_PAGES, MAX_SEARCH_PAGES))
        put(KEY_KIWIX_MAX_CHARS, kiwixMaxChars.coerceIn(MIN_PAGE_CHARS, MAX_PAGE_CHARS))
        put(KEY_FETCH_URL_ENABLED, fetchUrlEnabled)
        put(KEY_FETCH_URL_MAX_CHARS, fetchUrlMaxChars.coerceIn(MIN_FETCH_CHARS, MAX_FETCH_CHARS))
        put(KEY_DEEP_RESEARCH_ENABLED, deepResearchEnabled)
        put(KEY_DEEP_RESEARCH_IMPORT_SELECTED_KB_ENABLED, deepResearchImportIntoSelectedKbEnabled)
        put(KEY_DEEP_RESEARCH_SOURCE_LIMIT, DeepResearchSupport.normalizeSourceLimit(deepResearchSourceLimit))
        put(KEY_DATETIME_ENABLED, dateTimeEnabled)
        put(KEY_CALCULATOR_ENABLED, calculatorEnabled)
        put(KEY_NOTE_TOOLS_ENABLED, noteToolsEnabled)
        put(KEY_PINNED_NOTE_ID, pinnedNoteId ?: 0)
        put(KEY_TODO_TOOLS_ENABLED, todoToolsEnabled)
        put(KEY_CALENDAR_TOOLS_ENABLED, calendarToolsEnabled)
        put(KEY_ALARM_TOOLS_ENABLED, alarmToolsEnabled)
        put(KEY_KNOWLEDGE_BASE_ENABLED, knowledgeBaseEnabled)
        put(KEY_KNOWLEDGE_AUTO_CONTEXT_ENABLED, knowledgeBaseAutoContextEnabled)
        put(KEY_KNOWLEDGE_BASE_IDS, KnowledgeBaseRepository.selectedKnowledgeBaseIdsToCsv(selectedKnowledgeBaseIds))
        put(KEY_CHAT_DOCUMENT_KNOWLEDGE_BASE_ID, chatDocumentKnowledgeBaseId ?: 0L)
        put(KEY_KNOWLEDGE_MAX_RESULTS, knowledgeBaseMaxResults.coerceIn(MIN_KB_RESULTS, MAX_KB_RESULTS))
        put(KEY_IMAGE_GENERATION_ENABLED, imageGenerationEnabled)
        put(KEY_IMAGE_ITERATION_ENABLED, imageIterationEnabled)
        put(KEY_BGR_ENABLED, backgroundRemovalEnabled)
        put(KEY_ASSISTANT_TTS_ENABLED, assistantTtsEnabled)
        put(KEY_ASSISTANT_TTS_LANGUAGE, normalizedAssistantTtsLanguage())
        assistantTtsVoiceName?.trim()?.takeIf { it.isNotBlank() }?.let { put(KEY_ASSISTANT_TTS_VOICE, it) }
        put(KEY_ASSISTANT_TTS_STEPS, assistantTtsTotalSteps.coerceIn(MIN_ASSISTANT_TTS_STEPS, MAX_ASSISTANT_TTS_STEPS))
        put(KEY_ASSISTANT_TTS_SPEED, assistantTtsSpeed.coerceIn(MIN_ASSISTANT_TTS_SPEED, MAX_ASSISTANT_TTS_SPEED))
        put(KEY_CALL_SILENCE_AFTER_SPEECH_SECONDS, callSilenceAfterSpeechSeconds.coerceIn(MIN_CALL_SILENCE_AFTER_SPEECH_SECONDS, MAX_CALL_SILENCE_AFTER_SPEECH_SECONDS))
        put(KEY_CALL_NO_SPEECH_TIMEOUT_SECONDS, callNoSpeechTimeoutSeconds.coerceIn(MIN_CALL_NO_SPEECH_TIMEOUT_SECONDS, MAX_CALL_NO_SPEECH_TIMEOUT_SECONDS))
        imageParams.toParamMap().forEach { (key, value) ->
            if (value != null) put(key, value)
        }
        backgroundRemovalParams.toParamMap().forEach { (key, value) ->
            if (value != null) put(key, value)
        }
        put(KEY_MAX_TOOL_ROUNDS, maxToolRounds.coerceIn(MIN_TOOL_ROUNDS, MAX_TOOL_ROUNDS))
    }

    fun normalizedKiwixUrl(): String = kiwixServerUrl.trim().ifBlank { DEFAULT_KIWIX_URL }.trimEnd('/')

    fun normalizedAssistantTtsLanguage(): String =
        assistantTtsLanguage.trim().takeIf { it in supertonicLanguageCodes } ?: SUPERTONIC_DEFAULT_LANGUAGE

    companion object {
        const val KEY_TOOLS_ENABLED = "tools_enabled"
        const val KEY_WEB_SEARCH_ENABLED = "tool_web_search_enabled"
        const val KEY_WEB_SEARCH_MAX_PAGES = "tool_web_search_max_pages"
        const val KEY_WEB_SEARCH_MAX_CHARS = "tool_web_search_max_chars"
        const val KEY_KIWIX_SEARCH_ENABLED = "tool_kiwix_search_enabled"
        const val KEY_KIWIX_SERVER_URL = "tool_kiwix_server_url"
        const val KEY_KIWIX_MAX_PAGES = "tool_kiwix_max_pages"
        const val KEY_KIWIX_MAX_CHARS = "tool_kiwix_max_chars"
        const val KEY_FETCH_URL_ENABLED = "tool_fetch_url_enabled"
        const val KEY_FETCH_URL_MAX_CHARS = "tool_fetch_url_max_chars"
        const val KEY_DEEP_RESEARCH_ENABLED = "tool_deep_research_enabled"
        const val KEY_DEEP_RESEARCH_IMPORT_SELECTED_KB_ENABLED = "tool_deep_research_import_selected_kb_enabled"
        const val KEY_DEEP_RESEARCH_SOURCE_LIMIT = "tool_deep_research_source_limit"
        const val KEY_DATETIME_ENABLED = "tool_datetime_enabled"
        const val KEY_CALCULATOR_ENABLED = "tool_calculator_enabled"
        const val KEY_NOTE_TOOLS_ENABLED = "tool_note_tools_enabled"
        const val KEY_PINNED_NOTE_ID = "tool_pinned_note_id"
        const val KEY_TODO_TOOLS_ENABLED = "tool_todo_tools_enabled"
        const val KEY_CALENDAR_TOOLS_ENABLED = "tool_calendar_tools_enabled"
        const val KEY_ALARM_TOOLS_ENABLED = "tool_alarm_tools_enabled"
        const val KEY_KNOWLEDGE_BASE_ENABLED = "tool_knowledge_base_enabled"
        const val KEY_KNOWLEDGE_AUTO_CONTEXT_ENABLED = "tool_knowledge_auto_context_enabled"
        const val KEY_KNOWLEDGE_BASE_IDS = "tool_knowledge_base_ids"
        const val KEY_CHAT_DOCUMENT_KNOWLEDGE_BASE_ID = "tool_chat_document_kb_id"
        const val KEY_KNOWLEDGE_MAX_RESULTS = "tool_knowledge_max_results"
        const val KEY_IMAGE_GENERATION_ENABLED = "tool_image_generation_enabled"
        const val KEY_IMAGE_ITERATION_ENABLED = "tool_image_iteration_enabled"
        const val KEY_BGR_ENABLED = "tool_background_removal_enabled"
        const val KEY_ASSISTANT_TTS_ENABLED = "assistant_tts_enabled"
        const val KEY_ASSISTANT_TTS_LANGUAGE = "assistant_tts_language"
        const val KEY_ASSISTANT_TTS_VOICE = "assistant_tts_voice"
        const val KEY_ASSISTANT_TTS_STEPS = "assistant_tts_steps"
        const val KEY_ASSISTANT_TTS_SPEED = "assistant_tts_speed"
        const val KEY_CALL_SILENCE_AFTER_SPEECH_SECONDS = "call_silence_after_speech_seconds"
        const val KEY_CALL_NO_SPEECH_TIMEOUT_SECONDS = "call_no_speech_timeout_seconds"
        const val KEY_IMAGE_ENGINE = "tool_image_engine"
        const val KEY_IMAGE_MODEL = "tool_image_model"
        const val KEY_IMAGE_WIDTH = "tool_image_width"
        const val KEY_IMAGE_HEIGHT = "tool_image_height"
        const val KEY_IMAGE_STEPS = "tool_image_steps"
        const val KEY_IMAGE_CFG = "tool_image_cfg"
        const val KEY_IMAGE_SEED = "tool_image_seed"
        const val KEY_IMAGE_NEGATIVE_PROMPT = "tool_image_negative_prompt"
        const val KEY_IMAGE_BACKEND = "tool_image_backend"
        const val KEY_IMAGE_RUNTIME_THREADS = "tool_image_runtime_threads"
        const val KEY_IMAGE_GRAPH_OPT = "tool_image_graph_optimization"
        const val KEY_IMAGE_UNET_BACKEND = "tool_image_unet_backend"
        const val KEY_IMAGE_VAE_DECODER_BACKEND = "tool_image_vae_decoder_backend"
        const val KEY_IMAGE_VAE_ENCODER_BACKEND = "tool_image_vae_encoder_backend"
        const val KEY_IMAGE_INTRA_THREADS = "tool_image_intra_threads"
        const val KEY_IMAGE_INTER_THREADS = "tool_image_inter_threads"
        const val KEY_IMAGE_EXECUTION_MODE = "tool_image_execution_mode"
        const val KEY_IMAGE_MEMORY_PATTERN = "tool_image_memory_pattern"
        const val KEY_IMAGE_CPU_ARENA = "tool_image_cpu_arena"
        const val KEY_IMAGE_NNAPI_CPU_DISABLED = "tool_image_nnapi_cpu_disabled"
        const val KEY_IMAGE_NNAPI_FP16 = "tool_image_nnapi_fp16"
        const val KEY_IMAGE_SD_MODEL = "tool_image_sd_model"
        const val KEY_IMAGE_SD_VAE = "tool_image_sd_vae"
        const val KEY_IMAGE_SD_TAE = "tool_image_sd_tae"
        const val KEY_IMAGE_SD_CLIP_L = "tool_image_sd_clip_l"
        const val KEY_IMAGE_SD_CLIP_G = "tool_image_sd_clip_g"
        const val KEY_IMAGE_SD_T5XXL = "tool_image_sd_t5xxl"
        const val KEY_IMAGE_SD_LLM = "tool_image_sd_llm"
        const val KEY_IMAGE_SD_LLM_VISION = "tool_image_sd_llm_vision"
        const val KEY_IMAGE_SD_PHOTOMAKER = "tool_image_sd_photomaker"
        const val KEY_IMAGE_SD_LORAS = "tool_image_sd_loras"
        const val KEY_IMAGE_SD_LORA_APPLY_MODE = "tool_image_sd_lora_apply_mode"
        /** Legacy single-adapter keys retained for saved native-chat profiles. */
        const val KEY_IMAGE_SD_LORA_PATH = "tool_image_sd_lora_path"
        const val KEY_IMAGE_SD_LORA_STRENGTH = "tool_image_sd_lora_strength"
        const val KEY_IMAGE_SD_WIDTH = "tool_image_sd_width"
        const val KEY_IMAGE_SD_HEIGHT = "tool_image_sd_height"
        const val KEY_IMAGE_SD_STEPS = "tool_image_sd_steps"
        const val KEY_IMAGE_SD_CFG = "tool_image_sd_cfg"
        const val KEY_IMAGE_SD_SAMPLER = "tool_image_sd_sampler"
        const val KEY_IMAGE_SD_SEED = "tool_image_sd_seed"
        const val KEY_IMAGE_SD_NEGATIVE_PROMPT = "tool_image_sd_negative_prompt"
        const val KEY_IMAGE_SD_THREADS = "tool_image_sd_threads"
        const val KEY_IMAGE_SD_FLOW_SHIFT = "tool_image_sd_flow_shift"
        const val KEY_IMAGE_SD_DIFFUSION_FA = "tool_image_sd_diffusion_fa"
        const val KEY_IMAGE_SD_MMAP = "tool_image_sd_mmap"
        const val KEY_IMAGE_SD_VAE_CONV_DIRECT = "tool_image_sd_vae_conv_direct"
        const val KEY_IMAGE_SD_QWEN_ZERO_COND_T = "tool_image_sd_qwen_zero_cond_t"
        const val KEY_IMAGE_SD_CHROMA_DISABLE_DIT_MASK = "tool_image_sd_chroma_disable_dit_mask"
        const val KEY_BGR_MODEL = "tool_background_removal_model"
        const val KEY_BGR_BACKEND = "tool_background_removal_backend"
        const val KEY_BGR_RUNTIME_THREADS = "tool_background_removal_runtime_threads"
        const val KEY_BGR_GRAPH_OPT = "tool_background_removal_graph_optimization"
        const val KEY_BGR_ALPHA_THRESHOLD = "tool_background_removal_alpha_threshold"
        const val KEY_BGR_FEATHER_RADIUS = "tool_background_removal_feather_radius"
        const val KEY_BGR_MASK_SOFTNESS = "tool_background_removal_mask_softness"
        const val KEY_BGR_MASK_CONTRAST = "tool_background_removal_mask_contrast"
        const val KEY_BGR_EXPORT_MASK = "tool_background_removal_export_mask"
        const val KEY_BGR_RESIZE_BEFORE_PROCESSING = "tool_background_removal_resize_before_processing"
        const val KEY_BGR_RESIZE_MAX_EDGE = "tool_background_removal_resize_max_edge"
        const val KEY_MAX_TOOL_ROUNDS = "tool_max_rounds"

        const val DEFAULT_SEARCH_PAGES = 3
        const val DEFAULT_PAGE_CHARS = 2_000
        const val DEFAULT_FETCH_CHARS = 4_000
        const val DEFAULT_DEEP_RESEARCH_SOURCE_LIMIT = DeepResearchSupport.DEFAULT_SOURCE_LIMIT
        const val MIN_DEEP_RESEARCH_SOURCE_LIMIT = DeepResearchSupport.MIN_SOURCE_LIMIT
        const val DEFAULT_TOOL_ROUNDS = 12
        const val DEFAULT_KB_RESULTS = 6
        const val MIN_SEARCH_PAGES = 1
        const val MAX_SEARCH_PAGES = 10
        const val MIN_PAGE_CHARS = 500
        const val MAX_PAGE_CHARS = 20_000
        const val MIN_FETCH_CHARS = 500
        const val MAX_FETCH_CHARS = 40_000
        const val MIN_TOOL_ROUNDS = 1
        const val MAX_TOOL_ROUNDS = 24
        const val MIN_KB_RESULTS = 1
        const val MAX_KB_RESULTS = 20
        const val MIN_ASSISTANT_TTS_STEPS = 1
        const val MAX_ASSISTANT_TTS_STEPS = 32
        const val MIN_ASSISTANT_TTS_SPEED = 0.5f
        const val MAX_ASSISTANT_TTS_SPEED = 2.0f
        const val DEFAULT_CALL_SILENCE_AFTER_SPEECH_SECONDS = 5
        const val DEFAULT_CALL_NO_SPEECH_TIMEOUT_SECONDS = 10
        const val MIN_CALL_SILENCE_AFTER_SPEECH_SECONDS = 1
        const val MAX_CALL_SILENCE_AFTER_SPEECH_SECONDS = 15
        const val MIN_CALL_NO_SPEECH_TIMEOUT_SECONDS = 3
        const val MAX_CALL_NO_SPEECH_TIMEOUT_SECONDS = 60
        const val DEFAULT_KIWIX_URL = "http://127.0.0.1:${AIConstants.Ports.KIWIX}"

        fun liteRtToolDefaults(): NativeChatToolConfig = NativeChatToolConfig(
            toolsEnabled = true,
            webSearchEnabled = true,
            kiwixSearchEnabled = true,
            fetchUrlEnabled = true,
            deepResearchEnabled = true,
            deepResearchImportIntoSelectedKbEnabled = true,
            dateTimeEnabled = true,
            calculatorEnabled = true,
            noteToolsEnabled = true,
            todoToolsEnabled = true,
            calendarToolsEnabled = true,
            alarmToolsEnabled = true,
            knowledgeBaseEnabled = true,
            knowledgeBaseAutoContextEnabled = true,
            imageGenerationEnabled = true,
            imageIterationEnabled = true,
            backgroundRemovalEnabled = true
        )

        fun fromApiParams(apiParams: String?): NativeChatToolConfig {
            if (apiParams.isNullOrBlank()) return NativeChatToolConfig()
            return try {
                val json = JSONObject(apiParams)
                val values = mutableMapOf<String, Any?>()
                json.keys().forEach { key -> values[key] = json.opt(key) }
                fromParams(values)
            } catch (_: Exception) {
                NativeChatToolConfig()
            }
        }

        fun fromParams(params: Map<String, Any?>): NativeChatToolConfig = NativeChatToolConfig(
            toolsEnabled = booleanParam(params, KEY_TOOLS_ENABLED, false),
            webSearchEnabled = booleanParam(params, KEY_WEB_SEARCH_ENABLED, false),
            webSearchMaxPages = intParam(params, KEY_WEB_SEARCH_MAX_PAGES, DEFAULT_SEARCH_PAGES)
                .coerceIn(MIN_SEARCH_PAGES, MAX_SEARCH_PAGES),
            webSearchMaxChars = intParam(params, KEY_WEB_SEARCH_MAX_CHARS, DEFAULT_PAGE_CHARS)
                .coerceIn(MIN_PAGE_CHARS, MAX_PAGE_CHARS),
            kiwixSearchEnabled = booleanParam(params, KEY_KIWIX_SEARCH_ENABLED, false),
            kiwixServerUrl = stringParam(params, KEY_KIWIX_SERVER_URL, DEFAULT_KIWIX_URL)
                .trim()
                .ifBlank { DEFAULT_KIWIX_URL }
                .trimEnd('/'),
            kiwixMaxPages = intParam(params, KEY_KIWIX_MAX_PAGES, DEFAULT_SEARCH_PAGES)
                .coerceIn(MIN_SEARCH_PAGES, MAX_SEARCH_PAGES),
            kiwixMaxChars = intParam(params, KEY_KIWIX_MAX_CHARS, DEFAULT_PAGE_CHARS)
                .coerceIn(MIN_PAGE_CHARS, MAX_PAGE_CHARS),
            fetchUrlEnabled = booleanParam(params, KEY_FETCH_URL_ENABLED, false),
            fetchUrlMaxChars = intParam(params, KEY_FETCH_URL_MAX_CHARS, DEFAULT_FETCH_CHARS)
                .coerceIn(MIN_FETCH_CHARS, MAX_FETCH_CHARS),
            deepResearchEnabled = booleanParam(params, KEY_DEEP_RESEARCH_ENABLED, false),
            deepResearchImportIntoSelectedKbEnabled = booleanParam(params, KEY_DEEP_RESEARCH_IMPORT_SELECTED_KB_ENABLED, false),
            deepResearchSourceLimit = intParam(params, KEY_DEEP_RESEARCH_SOURCE_LIMIT, DEFAULT_DEEP_RESEARCH_SOURCE_LIMIT)
                .coerceAtLeast(MIN_DEEP_RESEARCH_SOURCE_LIMIT),
            dateTimeEnabled = booleanParam(params, KEY_DATETIME_ENABLED, true),
            calculatorEnabled = booleanParam(params, KEY_CALCULATOR_ENABLED, true),
            noteToolsEnabled = booleanParam(params, KEY_NOTE_TOOLS_ENABLED, false),
            pinnedNoteId = intParam(params, KEY_PINNED_NOTE_ID, 0).takeIf { it > 0 },
            todoToolsEnabled = booleanParam(params, KEY_TODO_TOOLS_ENABLED, false),
            calendarToolsEnabled = booleanParam(params, KEY_CALENDAR_TOOLS_ENABLED, false),
            alarmToolsEnabled = booleanParam(params, KEY_ALARM_TOOLS_ENABLED, false),
            knowledgeBaseEnabled = booleanParam(params, KEY_KNOWLEDGE_BASE_ENABLED, false),
            knowledgeBaseAutoContextEnabled = booleanParam(params, KEY_KNOWLEDGE_AUTO_CONTEXT_ENABLED, false),
            selectedKnowledgeBaseIds = knowledgeBaseIdsParam(params, KEY_KNOWLEDGE_BASE_IDS),
            chatDocumentKnowledgeBaseId = optionalLongParam(params, KEY_CHAT_DOCUMENT_KNOWLEDGE_BASE_ID),
            knowledgeBaseMaxResults = intParam(params, KEY_KNOWLEDGE_MAX_RESULTS, DEFAULT_KB_RESULTS)
                .coerceIn(MIN_KB_RESULTS, MAX_KB_RESULTS),
            imageGenerationEnabled = booleanParam(params, KEY_IMAGE_GENERATION_ENABLED, false),
            imageIterationEnabled = booleanParam(params, KEY_IMAGE_ITERATION_ENABLED, false),
            imageParams = imageParamsFromParams(params),
            backgroundRemovalEnabled = booleanParam(params, KEY_BGR_ENABLED, false),
            backgroundRemovalParams = backgroundRemovalParamsFromParams(params),
            assistantTtsEnabled = booleanParam(params, KEY_ASSISTANT_TTS_ENABLED, false),
            assistantTtsLanguage = stringParam(params, KEY_ASSISTANT_TTS_LANGUAGE, SUPERTONIC_DEFAULT_LANGUAGE)
                .trim()
                .takeIf { it in supertonicLanguageCodes }
                ?: SUPERTONIC_DEFAULT_LANGUAGE,
            assistantTtsVoiceName = stringParam(params, KEY_ASSISTANT_TTS_VOICE, "").trim().takeIf { it.isNotBlank() },
            assistantTtsTotalSteps = intParam(params, KEY_ASSISTANT_TTS_STEPS, SUPERTONIC_DEFAULT_TOTAL_STEPS)
                .coerceIn(MIN_ASSISTANT_TTS_STEPS, MAX_ASSISTANT_TTS_STEPS),
            assistantTtsSpeed = floatParam(params, KEY_ASSISTANT_TTS_SPEED, SUPERTONIC_DEFAULT_SPEED)
                .coerceIn(MIN_ASSISTANT_TTS_SPEED, MAX_ASSISTANT_TTS_SPEED),
            callSilenceAfterSpeechSeconds = intParam(params, KEY_CALL_SILENCE_AFTER_SPEECH_SECONDS, DEFAULT_CALL_SILENCE_AFTER_SPEECH_SECONDS)
                .coerceIn(MIN_CALL_SILENCE_AFTER_SPEECH_SECONDS, MAX_CALL_SILENCE_AFTER_SPEECH_SECONDS),
            callNoSpeechTimeoutSeconds = intParam(params, KEY_CALL_NO_SPEECH_TIMEOUT_SECONDS, DEFAULT_CALL_NO_SPEECH_TIMEOUT_SECONDS)
                .coerceIn(MIN_CALL_NO_SPEECH_TIMEOUT_SECONDS, MAX_CALL_NO_SPEECH_TIMEOUT_SECONDS),
            maxToolRounds = intParam(params, KEY_MAX_TOOL_ROUNDS, DEFAULT_TOOL_ROUNDS)
                .coerceIn(MIN_TOOL_ROUNDS, MAX_TOOL_ROUNDS)
        )

        private fun imageParamsFromParams(params: Map<String, Any?>): NativeChatImageToolParams =
            NativeChatImageToolParams(
                engine = enumParam(params, KEY_IMAGE_ENGINE, NativeChatImageGenerationEngine.ONNX),
                model = stringParam(params, KEY_IMAGE_MODEL, "").takeIf { it.isNotBlank() },
                width = intParam(params, KEY_IMAGE_WIDTH, NativeChatImageToolParams.DEFAULT_WIDTH)
                    .coerceIn(NativeChatImageToolParams.MIN_SIZE, NativeChatImageToolParams.MAX_SIZE),
                height = intParam(params, KEY_IMAGE_HEIGHT, NativeChatImageToolParams.DEFAULT_HEIGHT)
                    .coerceIn(NativeChatImageToolParams.MIN_SIZE, NativeChatImageToolParams.MAX_SIZE),
                steps = intParam(params, KEY_IMAGE_STEPS, NativeChatImageToolParams.DEFAULT_STEPS)
                    .coerceIn(NativeChatImageToolParams.MIN_STEPS, NativeChatImageToolParams.MAX_STEPS),
                cfgScale = floatParam(params, KEY_IMAGE_CFG, NativeChatImageToolParams.DEFAULT_CFG)
                    .coerceIn(NativeChatImageToolParams.MIN_CFG, NativeChatImageToolParams.MAX_CFG),
                seed = stringParam(params, KEY_IMAGE_SEED, ""),
                negativePrompt = stringParam(params, KEY_IMAGE_NEGATIVE_PROMPT, ""),
                backend = enumParam(params, KEY_IMAGE_BACKEND, OnnxRuntimeBackend.CPU),
                runtimeThreads = optionalIntParam(params, KEY_IMAGE_RUNTIME_THREADS),
                graphOptimizationLevel = enumParam(params, KEY_IMAGE_GRAPH_OPT, OnnxGraphOptimizationLevel.ALL),
                unetBackendOverride = enumParam(params, KEY_IMAGE_UNET_BACKEND, OnnxBackendOverride.DEFAULT),
                vaeDecoderBackendOverride = enumParam(params, KEY_IMAGE_VAE_DECODER_BACKEND, OnnxBackendOverride.DEFAULT),
                vaeEncoderBackendOverride = enumParam(params, KEY_IMAGE_VAE_ENCODER_BACKEND, OnnxBackendOverride.DEFAULT),
                intraOpThreads = optionalIntParam(params, KEY_IMAGE_INTRA_THREADS),
                interOpThreads = optionalIntParam(params, KEY_IMAGE_INTER_THREADS),
                executionMode = enumParam(params, KEY_IMAGE_EXECUTION_MODE, OnnxExecutionMode.SEQUENTIAL),
                memoryPatternOptimization = booleanParam(params, KEY_IMAGE_MEMORY_PATTERN, true),
                cpuArenaAllocator = booleanParam(params, KEY_IMAGE_CPU_ARENA, true),
                nnapiCpuDisabled = booleanParam(params, KEY_IMAGE_NNAPI_CPU_DISABLED, true),
                nnapiUseFp16 = booleanParam(params, KEY_IMAGE_NNAPI_FP16, false),
                sdParams = sdImageParamsFromParams(params)
            )

        private fun sdImageParamsFromParams(params: Map<String, Any?>): NativeChatSdImageToolParams =
            NativeChatSdImageToolParams(
                model = stringParam(params, KEY_IMAGE_SD_MODEL, "").takeIf { it.isNotBlank() },
                vaePath = stringParam(params, KEY_IMAGE_SD_VAE, "").takeIf { it.isNotBlank() },
                taePath = stringParam(params, KEY_IMAGE_SD_TAE, "").takeIf { it.isNotBlank() },
                clipLPath = stringParam(params, KEY_IMAGE_SD_CLIP_L, "").takeIf { it.isNotBlank() },
                clipGPath = stringParam(params, KEY_IMAGE_SD_CLIP_G, "").takeIf { it.isNotBlank() },
                t5xxlPath = stringParam(params, KEY_IMAGE_SD_T5XXL, "").takeIf { it.isNotBlank() },
                llmPath = stringParam(params, KEY_IMAGE_SD_LLM, "").takeIf { it.isNotBlank() },
                llmVisionPath = stringParam(params, KEY_IMAGE_SD_LLM_VISION, "").takeIf { it.isNotBlank() },
                photoMakerPath = stringParam(params, KEY_IMAGE_SD_PHOTOMAKER, "").takeIf { it.isNotBlank() },
                loras = parseSdLoras(
                    json = stringParam(params, KEY_IMAGE_SD_LORAS, ""),
                    legacyPath = stringParam(params, KEY_IMAGE_SD_LORA_PATH, "").takeIf { it.isNotBlank() },
                    legacyStrength = floatParam(params, KEY_IMAGE_SD_LORA_STRENGTH, 1.0f)
                ),
                loraApplyMode = SdLoraApplyMode.fromStoredValue(
                    stringParam(params, KEY_IMAGE_SD_LORA_APPLY_MODE, "").takeIf { it.isNotBlank() }
                ),
                width = intParam(params, KEY_IMAGE_SD_WIDTH, NativeChatSdImageToolParams.DEFAULT_WIDTH)
                    .coerceIn(NativeChatSdImageToolParams.MIN_SIZE, NativeChatSdImageToolParams.MAX_SIZE),
                height = intParam(params, KEY_IMAGE_SD_HEIGHT, NativeChatSdImageToolParams.DEFAULT_HEIGHT)
                    .coerceIn(NativeChatSdImageToolParams.MIN_SIZE, NativeChatSdImageToolParams.MAX_SIZE),
                steps = intParam(params, KEY_IMAGE_SD_STEPS, NativeChatSdImageToolParams.DEFAULT_STEPS)
                    .coerceIn(NativeChatSdImageToolParams.MIN_STEPS, NativeChatSdImageToolParams.MAX_STEPS),
                cfgScale = floatParam(params, KEY_IMAGE_SD_CFG, NativeChatSdImageToolParams.DEFAULT_CFG)
                    .coerceIn(NativeChatSdImageToolParams.MIN_CFG, NativeChatSdImageToolParams.MAX_CFG),
                sampler = enumParam(params, KEY_IMAGE_SD_SAMPLER, SamplingMethod.EULER_A),
                seed = stringParam(params, KEY_IMAGE_SD_SEED, ""),
                negativePrompt = stringParam(params, KEY_IMAGE_SD_NEGATIVE_PROMPT, ""),
                threads = intParam(params, KEY_IMAGE_SD_THREADS, NativeChatSdImageToolParams.DEFAULT_THREADS)
                    .coerceIn(NativeChatSdImageToolParams.MIN_THREADS, NativeChatSdImageToolParams.MAX_THREADS),
                flowShift = stringParam(params, KEY_IMAGE_SD_FLOW_SHIFT, ""),
                diffusionFa = booleanParam(params, KEY_IMAGE_SD_DIFFUSION_FA, false),
                mmap = booleanParam(params, KEY_IMAGE_SD_MMAP, false),
                vaeConvDirect = booleanParam(params, KEY_IMAGE_SD_VAE_CONV_DIRECT, false),
                qwenImageZeroCondT = booleanParam(params, KEY_IMAGE_SD_QWEN_ZERO_COND_T, false),
                chromaDisableDitMask = booleanParam(params, KEY_IMAGE_SD_CHROMA_DISABLE_DIT_MASK, false)
            )

        private fun parseSdLoras(
            json: String,
            legacyPath: String?,
            legacyStrength: Float
        ): List<SdLoraSpec> = runCatching {
            if (json.isBlank()) emptyList()
            else JSONArray(json).toSdLoraSpecs()
        }.getOrElse {
            SdLoraSpec.fromLegacy(legacyPath, legacyStrength)
        }.ifEmpty {
            SdLoraSpec.fromLegacy(legacyPath, legacyStrength)
        }

        private fun backgroundRemovalParamsFromParams(params: Map<String, Any?>): NativeChatBackgroundRemovalToolParams =
            NativeChatBackgroundRemovalToolParams(
                model = stringParam(params, KEY_BGR_MODEL, "").takeIf { it.isNotBlank() },
                backend = enumParam(params, KEY_BGR_BACKEND, OnnxRuntimeBackend.CPU),
                runtimeThreads = optionalIntParam(params, KEY_BGR_RUNTIME_THREADS),
                graphOptimizationLevel = enumParam(params, KEY_BGR_GRAPH_OPT, OnnxGraphOptimizationLevel.ALL),
                alphaThreshold = floatParam(params, KEY_BGR_ALPHA_THRESHOLD, NativeChatBackgroundRemovalToolParams.DEFAULT_ALPHA_THRESHOLD)
                    .coerceIn(NativeChatBackgroundRemovalToolParams.MIN_ALPHA_THRESHOLD, NativeChatBackgroundRemovalToolParams.MAX_ALPHA_THRESHOLD),
                featherRadius = intParam(params, KEY_BGR_FEATHER_RADIUS, NativeChatBackgroundRemovalToolParams.DEFAULT_FEATHER_RADIUS)
                    .coerceIn(NativeChatBackgroundRemovalToolParams.MIN_FEATHER_RADIUS, NativeChatBackgroundRemovalToolParams.MAX_FEATHER_RADIUS),
                maskSoftness = floatParam(params, KEY_BGR_MASK_SOFTNESS, NativeChatBackgroundRemovalToolParams.DEFAULT_MASK_SOFTNESS)
                    .coerceIn(NativeChatBackgroundRemovalToolParams.MIN_MASK_SOFTNESS, NativeChatBackgroundRemovalToolParams.MAX_MASK_SOFTNESS),
                maskContrast = floatParam(params, KEY_BGR_MASK_CONTRAST, NativeChatBackgroundRemovalToolParams.DEFAULT_MASK_CONTRAST)
                    .coerceIn(NativeChatBackgroundRemovalToolParams.MIN_MASK_CONTRAST, NativeChatBackgroundRemovalToolParams.MAX_MASK_CONTRAST),
                exportMask = booleanParam(params, KEY_BGR_EXPORT_MASK, false),
                resizeBeforeProcessing = booleanParam(params, KEY_BGR_RESIZE_BEFORE_PROCESSING, true),
                resizeMaxEdge = intParam(params, KEY_BGR_RESIZE_MAX_EDGE, NativeChatBackgroundRemovalToolParams.DEFAULT_RESIZE_MAX_EDGE)
                    .coerceIn(NativeChatBackgroundRemovalToolParams.MIN_RESIZE_MAX_EDGE, NativeChatBackgroundRemovalToolParams.MAX_RESIZE_MAX_EDGE)
            )

        private fun booleanParam(params: Map<String, Any?>, key: String, default: Boolean): Boolean {
            return when (val value = params[key]) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true)
                else -> default
            }
        }

        private fun intParam(params: Map<String, Any?>, key: String, default: Int): Int {
            return when (val value = params[key]) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: default
                else -> default
            }
        }

        private fun optionalIntParam(params: Map<String, Any?>, key: String): Int? {
            return when (val value = params[key]) {
                is Number -> value.toInt().takeIf { it > 0 }
                is String -> value.toIntOrNull()?.takeIf { it > 0 }
                else -> null
            }
        }

        private fun optionalLongParam(params: Map<String, Any?>, key: String): Long? {
            return when (val value = params[key]) {
                is Number -> value.toLong().takeIf { it > 0L }
                is String -> value.toLongOrNull()?.takeIf { it > 0L }
                else -> null
            }
        }

        private fun floatParam(params: Map<String, Any?>, key: String, default: Float): Float {
            return when (val value = params[key]) {
                is Number -> value.toFloat()
                is String -> value.toFloatOrNull() ?: default
                else -> default
            }
        }

        private fun stringParam(params: Map<String, Any?>, key: String, default: String): String {
            return when (val value = params[key]) {
                is String -> value
                else -> default
            }
        }

        private fun knowledgeBaseIdsParam(params: Map<String, Any?>, key: String): List<Long> {
            return when (val value = params[key]) {
                is String -> KnowledgeBaseRepository.selectedKnowledgeBaseIdsFromCsv(value)
                is Iterable<*> -> value.mapNotNull {
                    when (it) {
                        is Number -> it.toLong()
                        is String -> it.toLongOrNull()
                        else -> null
                    }
                }.filter { it > 0L }.distinct()
                else -> emptyList()
            }
        }

        private inline fun <reified T : Enum<T>> enumParam(params: Map<String, Any?>, key: String, default: T): T {
            val raw = stringParam(params, key, default.name)
            return enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
        }
    }
}

data class NativeChatToolResult(
    val content: String,
    val generatedImagePath: String? = null
)

private fun Throwable.asException(): Exception =
    this as? Exception ?: RuntimeException(message ?: javaClass.simpleName, this)

private fun isDocumentSummaryRequest(query: String): Boolean {
    val lower = query.lowercase(Locale.ROOT)
    val mentionsDocument = listOf(
        "document",
        "doc ",
        "docs",
        "pdf",
        "file",
        "attachment",
        "documento",
        "archivo",
        "adjunto"
    ).any { it in lower }
    val asksSummary = listOf(
        "summarize",
        "summary",
        "summarise",
        "resume",
        "resumen",
        "resumir",
        "resúm",
        "sintetiza",
        "síntesis",
        "abstract"
    ).any { it in lower }
    return asksSummary && (mentionsDocument || lower.length < 80)
}

data class NativeChatToolProgress(
    val status: String,
    val phase: String = "",
    val current: Int? = null,
    val total: Int? = null,
    val count: Int? = null,
    val title: String? = null,
    val url: String? = null,
    val outputPreview: String? = null,
    val isComplete: Boolean = false
)

data class NativeChatSearchSummaryRequest(
    val title: String,
    val url: String,
    val content: String,
    val source: String,
    val maxChars: Int = 700
)

object NativeChatToolProgressPhase {
    const val SEARCHING = "searching"
    const val RESEARCHING = "researching"
    const val FOUND = "found"
    const val READING = "reading"
    const val SUMMARIZED = "summarized"
    const val FETCHING = "fetching"
    const val GENERATING = "generating"
}

data class NativeChatGeneratedImage(
    val content: String,
    val imagePath: String
)

data class NativeChatBackgroundRemovalImage(
    val content: String,
    val imagePath: String
)

class NativeChatToolRuntime(
    private val context: android.content.Context? = null,
    private val noteDao: NoteDao? = null,
    private val organizerDao: OrganizerDao? = null,
    private val alarmScheduler: (suspend (OrganizerAlarmEntity) -> Unit)? = null,
    private val alarmCanceler: ((Long) -> Unit)? = null,
    private val organizerChanged: (() -> Unit)? = null,
    private val notesChanged: (() -> Unit)? = null,
    private val knowledgeBaseRepository: KnowledgeBaseRepository? = null,
    private val imageGenerator: NativeChatImageGenerator? = null,
    private val backgroundRemover: NativeChatBackgroundRemover? = null,
    private val pdfTextExtractor: (ByteArray, Int) -> String = ::extractNativePdfTextFromBytes,
    private val clientFactory: () -> OkHttpClient = { defaultNativeChatToolClient() },
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() }
) {
    private val client by lazy(clientFactory)

    suspend fun configWithOrganizerPermissions(config: NativeChatToolConfig): NativeChatToolConfig {
        if (!config.calendarToolsEnabled && !config.alarmToolsEnabled) return config
        val settings = organizerDao?.getLlmSettingsOnce() ?: OrganizerLlmSettingsEntity()
        return config.copy(
            calendarToolsEnabled = config.calendarToolsEnabled && settings.calendarToolsAllowed,
            alarmToolsEnabled = config.alarmToolsEnabled && settings.alarmToolsAllowed
        )
    }

    fun availableTools(config: NativeChatToolConfig): List<AgentTool> {
        if (!config.toolsEnabled) return emptyList()
        val currentYear = nowProvider().year
        return buildList {
            config.pinnedNoteId?.takeIf { it > 0 }?.let {
                add(
                    AgentTool(
                        name = TOOL_MODIFY_PINNED_NOTE,
                        description = "Read or modify the single Organizer note pinned for Wear Quick Chat. This tool cannot access any other note. Use operation=read to inspect it, operation=replace_text to change a matching part, or operation=rewrite to replace the whole content.",
                        parameters = mapOf(
                            "operation" to "read, replace_text, or rewrite",
                            "find_text" to "Exact text to replace when operation=replace_text",
                            "replacement_text" to "Replacement text when operation=replace_text",
                            "content" to "Complete new content when operation=rewrite",
                            "replace_all" to "Optional true to replace every exact match"
                        ),
                        requiredParams = listOf("operation")
                    )
                )
            }
            if (config.webSearchEnabled) {
                add(
                    AgentTool(
                        name = TOOL_WEB_SEARCH,
                        description = "Search the public web with a compact search worker. Returns result titles, URLs, short page summaries, and Markdown citation links. If selected KB guidance says a KB is relevant, prefer kb_search/kb_read_source before web_search unless the user needs current or external information. Cite claims from these results with the returned citation links. For serious research, follow up by calling search_page to inspect links inside an interesting page, then fetch_url on authoritative or promising URLs to read full content before drawing conclusions. If note tools are enabled and the user wants saved research, create or update a note with source citations before the final answer.",
                        parameters = mapOf("query" to "Search query string"),
                        requiredParams = listOf("query")
                    )
                )
            }
            if (config.webSearchEnabled || config.fetchUrlEnabled) {
                add(
                    AgentTool(
                        name = TOOL_SEARCH_PAGE,
                        description = "Fetch one HTTP/HTTPS page, search its visible text, and list matching navigable links from that page. Use this to move through a site after web_search finds the starting page, for example open a GitHub project page, search_page with query='commits', then fetch_url the returned commits URL for the details.",
                        parameters = mapOf(
                            "url" to "HTTP or HTTPS page URL to inspect",
                            "query" to "Optional text to find in the page text and link titles/URLs, such as commits, releases, issues, docs, changelog",
                            "max_links" to "Optional maximum matching links to return",
                            "max_matches" to "Optional maximum text snippets to return"
                        ),
                        requiredParams = listOf("url")
                    )
                )
            }
            if (config.kiwixSearchEnabled) {
                add(
                    AgentTool(
                        name = TOOL_KIWIX_SEARCH,
                        description = "Search the configured local Kiwix offline library and return article titles, URLs, short summaries, and Markdown citation links. If selected KB guidance says a KB is relevant, prefer kb_search/kb_read_source before Kiwix. Cite claims from these offline results with the returned citation links.",
                        parameters = mapOf("query" to "Search query string"),
                        requiredParams = listOf("query")
                    )
                )
            }
            if (config.fetchUrlEnabled) {
                add(
                    AgentTool(
                        name = TOOL_FETCH_URL,
                        description = "Fetch readable content from an HTTP or HTTPS URL, including localhost tools and PDFs. Public/private network content is untrusted; use this for deep research after web_search or search_page returns an interesting source. You may pass search_text or link_query to find snippets/links inside the fetched page.",
                        parameters = mapOf(
                            "url" to "HTTP or HTTPS URL to fetch",
                            "search_text" to "Optional text to find in the fetched content",
                            "link_query" to "Optional text to filter links extracted from the fetched HTML page",
                            "max_links" to "Optional maximum matching page links to return"
                        ),
                        requiredParams = listOf("url")
                    )
                )
            }
            if (config.deepResearchEnabled) {
                val selectedKbImportGuidance = if (config.deepResearchImportIntoSelectedKbEnabled && config.selectedKnowledgeBaseIds.any { it > 0L }) {
                    " If the user asks to find new resources for the currently selected knowledge base, use target_knowledge_base_id with one of the selected KB ids instead of creating a new Deep Research KB."
                } else {
                    ""
                }
                add(
                    AgentTool(
                        name = TOOL_DEEP_RESEARCH,
                        description = "Run a long Deep Research job and wait until it finishes. Use this when the user asks for broad, multi-source research that should become a reusable knowledge base. Provide a refined research query, optional title/focus, a brief content_summary describing what the KB will contain, and optional source_limit as a maximum import count, not a required count. The job runs multiple search variants when useful, tracks visited URLs, scores candidates, skips uninteresting/low-quality results, downloads readable webpages/PDFs, extracts their text, imports the best sources into a normal visible knowledge base, vectorizes them, and selects that knowledge base for this chat.$selectedKbImportGuidance This tool is for source ingestion, not for writing a note. Do not send a final answer until this tool returns.",
                        parameters = mapOf(
                            "query" to "Refined research query",
                            "title" to "Optional short topic title for the generated knowledge base",
                            "focus" to "Optional extra focus, population, domain, or constraints",
                            "content_summary" to "Brief summary of what information this generated KB should contain, used later to decide when to prefer it before web or Kiwix",
                            "source_limit" to "Optional maximum number of sources to import; minimum 1 and no maximum",
                            "target_knowledge_base_id" to "Optional selected KB id to add new sources into. Only works when selected-KB imports are enabled for this chat and server."
                        ),
                        requiredParams = listOf("query")
                    )
                )
            }
            if (config.dateTimeEnabled) {
                add(
                    AgentTool(
                        name = TOOL_GET_DATETIME,
                        description = "Get the current local date and time.",
                        parameters = emptyMap()
                    )
                )
            }
            if (config.calculatorEnabled) {
                add(
                    AgentTool(
                        name = TOOL_CALCULATOR,
                        description = "Evaluate a basic arithmetic expression with +, -, *, /, %, ^, and parentheses.",
                        parameters = mapOf("expression" to "Arithmetic expression to evaluate"),
                        requiredParams = listOf("expression")
                    )
                )
            }
            val pinnedNoteOnly = config.pinnedNoteId?.let { it > 0 } == true
            if (config.noteToolsEnabled && !pinnedNoteOnly) {
                add(
                    AgentTool(
                        name = TOOL_LIST_NOTES,
                        description = "List whitelisted notes with their IDs, titles, types, and previews. Use this before editing a note when the note id is unknown; do not ask the user for a note ID first.",
                        parameters = mapOf(
                            "query" to "Optional search text for title or content",
                            "type" to "Optional note type filter: manual, todo_list, transcription, pdf_summary, video_summary, workflow",
                            "max_results" to "Optional maximum number of notes to return"
                        )
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_READ_NOTE,
                        description = "Read one whitelisted note by ID. Todo-list notes include 1-based item indexes for checking, unchecking, editing, or removing items.",
                        parameters = mapOf("note_id" to "Numeric note id"),
                        requiredParams = listOf("note_id")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_CREATE_NOTE,
                        description = "Create a regular manual note in Organizer Notes now. Use this whenever the user asks to write, save, store, or keep research, summaries, plans, citations, or notes. Tool-created notes are automatically whitelisted for later read/update calls. Content may include markdown image lines returned by generate_image.",
                        parameters = mapOf(
                            "title" to "Note title",
                            "content" to "Note body content"
                        ),
                        requiredParams = listOf("title", "content")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_UPDATE_NOTE,
                        description = "Update an existing whitelisted note title and/or content by note id. Use this directly when the user asks to improve, expand, append to, or rewrite a known note. Call list_notes/read_note first when the ID or current content is unknown. Preserve existing content unless the user asked to replace it; use append_text to add new material without rewriting the whole note, and insert generate_image note_markdown lines when adding images.",
                        parameters = mapOf(
                            "note_id" to "Numeric note id",
                            "title" to "Optional replacement title",
                            "content" to "Optional replacement content",
                            "append_text" to "Optional text to append to the existing note content"
                        ),
                        requiredParams = listOf("note_id")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_REPLACE_NOTE_TEXT,
                        description = "Replace exact text in a whitelisted note. Use this directly for targeted edits, corrections, or section replacement when rewriting the whole note is unnecessary. Call list_notes/read_note first when the ID or exact text is unknown.",
                        parameters = mapOf(
                            "note_id" to "Numeric note id",
                            "find_text" to "Exact text to find",
                            "replacement_text" to "Replacement text",
                            "replace_all" to "Optional true to replace all matches; false replaces the first match",
                            "case_sensitive" to "Optional true for case-sensitive matching; defaults to true"
                        ),
                        requiredParams = listOf("note_id", "find_text", "replacement_text")
                    )
                )
            }
            if (config.todoToolsEnabled && !pinnedNoteOnly) {
                if (!config.noteToolsEnabled) {
                    add(
                        AgentTool(
                            name = TOOL_LIST_NOTES,
                            description = "List whitelisted notes with their IDs, titles, types, and previews. Use this before editing a todo list when the note id is unknown; do not ask the user for a note ID first.",
                            parameters = mapOf(
                                "query" to "Optional search text for title or content",
                                "type" to "Optional note type filter, usually todo_list",
                                "max_results" to "Optional maximum number of notes to return"
                            )
                        )
                    )
                    add(
                        AgentTool(
                            name = TOOL_READ_NOTE,
                            description = "Read one whitelisted note by ID. Todo-list notes include 1-based item indexes for checking, unchecking, editing, or removing items.",
                            parameters = mapOf("note_id" to "Numeric note id"),
                            requiredParams = listOf("note_id")
                        )
                    )
                }
                add(
                    AgentTool(
                        name = TOOL_CREATE_TODO_LIST,
                        description = "Create a todo-list note in Organizer Notes now. Use this whenever the user asks you to save, track, or organize tasks as a checklist.",
                        parameters = mapOf(
                            "title" to "Todo list title",
                            "items" to "Todo items as a JSON array, newline-separated list, or markdown task list"
                        ),
                        requiredParams = listOf("title", "items")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_ADD_TODO_ITEM,
                        description = "Add an unchecked item to an existing whitelisted todo-list note. Call list_notes/read_note first when the note ID is unknown.",
                        parameters = mapOf(
                            "note_id" to "Numeric todo-list note id",
                            "item" to "Todo item text"
                        ),
                        requiredParams = listOf("note_id", "item")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_UPDATE_TODO_ITEM,
                        description = "Edit the text of one item in a whitelisted todo-list note. Item index is 1-based; call read_note first when the index is unknown.",
                        parameters = mapOf(
                            "note_id" to "Numeric todo-list note id",
                            "item_index" to "1-based item index",
                            "item" to "Replacement item text"
                        ),
                        requiredParams = listOf("note_id", "item_index", "item")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_REMOVE_TODO_ITEM,
                        description = "Remove one item from a whitelisted todo-list note. Item index is 1-based; call read_note first when the index is unknown.",
                        parameters = mapOf(
                            "note_id" to "Numeric todo-list note id",
                            "item_index" to "1-based item index"
                        ),
                        requiredParams = listOf("note_id", "item_index")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_SET_TODO_ITEM_CHECKED,
                        description = "Check or uncheck one item in a whitelisted todo-list note. Item index is 1-based; call read_note first when the index is unknown.",
                        parameters = mapOf(
                            "note_id" to "Numeric todo-list note id",
                            "item_index" to "1-based item index",
                            "checked" to "true to check the item, false to uncheck it"
                        ),
                        requiredParams = listOf("note_id", "item_index", "checked")
                    )
                )
            }
            if (config.calendarToolsEnabled) {
                add(
                    AgentTool(
                        name = TOOL_LIST_CALENDAR_EVENTS,
                        description = "List Organizer calendar events by optional date range or search query. Returned event dates include English and Spanish day-of-week names. Use this before editing or deleting an event when the event ID is unknown.",
                        parameters = mapOf(
                            "start_datetime" to "Optional ISO-8601 range start; local timezone is used if no offset is included",
                            "end_datetime" to "Optional ISO-8601 range end; local timezone is used if no offset is included",
                            "query" to "Optional text to match title, description, or location",
                            "max_results" to "Optional maximum number of events to return"
                        )
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_READ_CALENDAR_EVENT,
                        description = "Read one Organizer calendar event by ID, including English and Spanish day-of-week names plus any linked alarms.",
                        parameters = mapOf("event_id" to "Numeric event id"),
                        requiredParams = listOf("event_id")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_CREATE_CALENDAR_EVENT,
                        description = "Create an Organizer calendar event. Add alarm_datetime or alarm_offset_minutes only when the user wants a phone alarm for the event. If the user gives a month/day without a year, assume the current device year ($currentYear) unless they explicitly say another year.",
                        parameters = mapOf(
                            "title" to "Event title",
                            "start_datetime" to "ISO-8601 start date/time; use current year $currentYear when the user omits the year",
                            "end_datetime" to "Optional ISO-8601 end date/time",
                            "all_day" to "Optional true for all-day event",
                            "description" to "Optional event notes",
                            "location" to "Optional event location",
                            "color" to "Optional color as #RRGGBB or #AARRGGBB",
                            "alarm_datetime" to "Optional ISO-8601 alarm time; use current year $currentYear when the user omits the year",
                            "alarm_offset_minutes" to "Optional minutes before start for an alarm"
                        ),
                        requiredParams = listOf("title", "start_datetime")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_UPDATE_CALENDAR_EVENT,
                        description = "Update an existing Organizer event by event_id. Call list_calendar_events/read_calendar_event first when the ID or current details are unknown. If the user gives a month/day without a year, assume the current device year ($currentYear) unless they explicitly say another year.",
                        parameters = mapOf(
                            "event_id" to "Numeric event id",
                            "title" to "Optional replacement title",
                            "start_datetime" to "Optional replacement ISO-8601 start; use current year $currentYear when omitted",
                            "end_datetime" to "Optional replacement ISO-8601 end; empty string clears it; use current year $currentYear when omitted",
                            "all_day" to "Optional true or false",
                            "description" to "Optional replacement description",
                            "location" to "Optional replacement location",
                            "color" to "Optional #RRGGBB or #AARRGGBB; empty string clears it"
                        ),
                        requiredParams = listOf("event_id")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_DELETE_CALENDAR_EVENT,
                        description = "Delete an Organizer calendar event by event_id. Linked alarms are deleted too. Use only when the user asked to delete/cancel the event.",
                        parameters = mapOf("event_id" to "Numeric event id"),
                        requiredParams = listOf("event_id")
                    )
                )
            }
            if (config.alarmToolsEnabled) {
                add(
                    AgentTool(
                        name = TOOL_LIST_ALARMS,
                        description = "List Organizer alarms by optional date range or search query. Use this before editing or deleting an alarm when the alarm ID is unknown.",
                        parameters = mapOf(
                            "start_datetime" to "Optional ISO-8601 range start",
                            "end_datetime" to "Optional ISO-8601 range end",
                            "query" to "Optional title/message search",
                            "enabled_only" to "Optional true to show only active alarms",
                            "max_results" to "Optional maximum number of alarms to return"
                        )
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_READ_ALARM,
                        description = "Read one Organizer alarm by alarm_id.",
                        parameters = mapOf("alarm_id" to "Numeric alarm id"),
                        requiredParams = listOf("alarm_id")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_CREATE_ALARM,
                        description = "Create a one-shot Organizer phone alarm with notification sound/vibration. If the user gives a month/day without a year, assume the current device year ($currentYear) unless they explicitly say another year.",
                        parameters = mapOf(
                            "title" to "Alarm title",
                            "trigger_datetime" to "ISO-8601 alarm time; use current year $currentYear when the user omits the year",
                            "message" to "Optional alarm message",
                            "sound_enabled" to "Optional true to play notification sound",
                            "event_id" to "Optional event id to link this alarm to"
                        ),
                        requiredParams = listOf("title", "trigger_datetime")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_UPDATE_ALARM,
                        description = "Update an existing Organizer alarm by alarm_id. Call list_alarms/read_alarm first when the ID or current details are unknown. If the user gives a month/day without a year, assume the current device year ($currentYear) unless they explicitly say another year.",
                        parameters = mapOf(
                            "alarm_id" to "Numeric alarm id",
                            "title" to "Optional replacement title",
                            "trigger_datetime" to "Optional replacement ISO-8601 trigger time; use current year $currentYear when omitted",
                            "message" to "Optional replacement message",
                            "sound_enabled" to "Optional true or false",
                            "enabled" to "Optional true or false"
                        ),
                        requiredParams = listOf("alarm_id")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_DELETE_ALARM,
                        description = "Delete an Organizer alarm by alarm_id. Use only when the user asked to delete/cancel that alarm.",
                        parameters = mapOf("alarm_id" to "Numeric alarm id"),
                        requiredParams = listOf("alarm_id")
                    )
                )
            }
            if (config.knowledgeBaseEnabled) {
                add(
                    AgentTool(
                        name = TOOL_KB_SEARCH,
                        description = "Search only the knowledge bases selected for this chat, including documents uploaded into this chat. Pass either query for one search or queries as a JSON array/newline-separated list for multiple sequenced searches. Returns cited chunks with chunk_id values and Markdown citation links. Use those links when citing KB-derived claims.",
                        parameters = mapOf(
                            "query" to "Question or search text",
                            "queries" to "Optional JSON array or newline-separated list of related search queries to run in sequence",
                            "max_results" to "Optional maximum matching chunks to return per query"
                        )
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_KB_READ_CHUNK,
                        description = "Read a selected knowledge-base chunk by chunk_id. Set include_neighbors=true when the answer needs surrounding context.",
                        parameters = mapOf(
                            "chunk_id" to "Numeric chunk id returned by kb_search",
                            "include_neighbors" to "Optional true to include adjacent chunks from the same source"
                        ),
                        requiredParams = listOf("chunk_id")
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_KB_READ_SOURCE,
                        description = "Read ordered chunks from one selected knowledge-base source for document-level tasks such as summarizing an uploaded document. If the chat has an uploaded document and the user asks to summarize it, call this tool. Use start_chunk to continue through long documents.",
                        parameters = mapOf(
                            "source_id" to "Optional numeric source id returned by kb_list_sources. If omitted and the chat has one uploaded document, that source is used.",
                            "start_chunk" to "Optional zero-based chunk index to start from when continuing a long source",
                            "max_chunks" to "Optional maximum chunks to return, up to 40"
                        )
                    )
                )
                add(
                    AgentTool(
                        name = TOOL_KB_LIST_SOURCES,
                        description = "List the sources available in the knowledge bases selected for this chat.",
                        parameters = emptyMap()
                    )
                )
            }
            if (config.imageGenerationEnabled) {
                add(
                    AgentTool(
                        name = TOOL_GENERATE_IMAGE,
                        description = "Generate a PNG image with the native-chat configured image engine. Before calling this tool, rewrite the user's idea into a stronger optimized image prompt with clear subject, composition, style, lighting, colors, and constraints so the result is better. Return the saved app-local path plus a note_markdown line that can be inserted into a note.",
                        parameters = mapOf(
                            "prompt" to "Optimized positive prompt describing the image to generate. Improve sparse user wording instead of passing it through unchanged.",
                            "negative_prompt" to "Optional negative prompt for defects, unwanted styles, or objects to avoid"
                        ),
                        requiredParams = listOf("prompt")
                    )
                )
            }
            if (config.backgroundRemovalEnabled) {
                add(
                    AgentTool(
                        name = TOOL_REMOVE_IMAGE_BACKGROUND,
                        description = "Remove the background from an existing image with the native-chat ONNX background-removal preset. Use the user-selected model and settings; only provide the image path returned by another tool or shown in the chat context. Returns a transparent PNG path plus a note_markdown line.",
                        parameters = mapOf(
                            "image_path" to "Path to the source image to process"
                        ),
                        requiredParams = listOf("image_path")
                    )
                )
            }
        }
    }

    suspend fun buildKnowledgeBaseSelectionGuidance(config: NativeChatToolConfig): String? {
        if (!config.toolsEnabled || !config.knowledgeBaseEnabled) return null
        val scopeIds = config.knowledgeBaseScopeIds()
        if (scopeIds.isEmpty()) return null
        val repo = knowledgeBaseRepository ?: return null
        return repo.buildKnowledgeBaseSelectionGuidance(scopeIds)
    }

    suspend fun buildAutoKnowledgeContext(query: String, config: NativeChatToolConfig): String? {
        val hasChatDocumentScope = config.chatDocumentKnowledgeBaseId?.let { it > 0L } == true
        if (!config.toolsEnabled || !config.knowledgeBaseEnabled) return null
        if (!hasChatDocumentScope && !config.knowledgeBaseAutoContextEnabled) return null
        val scopeIds = config.knowledgeBaseScopeIds()
        if (scopeIds.isEmpty() || query.isBlank()) return null
        val repo = knowledgeBaseRepository ?: return null
        if (hasChatDocumentScope && isDocumentSummaryRequest(query)) {
            return runCatching {
                repo.readSourcesForSummary(
                    knowledgeBaseIds = scopeIds,
                    maxChunksPerSource = config.knowledgeBaseMaxResults.coerceIn(6, 20),
                    maxCharsPerChunk = 850
                )
            }.getOrNull()?.let { sourceContext ->
                buildString {
                    appendLine("The user has asked to summarize uploaded document(s) in this chat. The following ordered chunks are from those chat documents. Produce a document summary from this material, cite KB-derived claims by copying the provided Markdown citation links exactly, for example [AL.pdf chunk 9](kb://chunk/123). Do not shorten them to bare labels like [AL.pdf chunk 9]. Call kb_read_source with a later start_chunk if more source text is needed.")
                    appendLine()
                    append(sourceContext)
                }.trim()
            }
        }
        val results = repo.search(
            query = query,
            knowledgeBaseIds = scopeIds,
            maxResults = config.knowledgeBaseMaxResults
        )
        if (results.isEmpty()) return null
        val routingGuide = repo.buildKnowledgeBaseSelectionGuidance(scopeIds)
        return buildString {
            if (hasChatDocumentScope) {
                appendLine("The user has uploaded document(s) to this chat. The following retrieved chunks are from those chat documents. Use them to answer document-referential questions and do not claim that no document was attached. Cite KB-derived claims by copying the provided Markdown citation links exactly, for example [AL.pdf chunk 9](kb://chunk/123). Do not shorten them to bare labels like [AL.pdf chunk 9].")
            } else {
                appendLine("Knowledge base context selected for this chat. Use it only when relevant. Cite KB-derived claims by copying the provided Markdown citation links exactly, not bare citation labels.")
            }
            routingGuide?.let {
                appendLine(it)
            }
            results.forEachIndexed { index, result ->
                appendLine()
                appendLine("[KB${index + 1} chunk_id=${result.chunkId} source=\"${result.sourceTitle}\" base=\"${result.knowledgeBaseName}\"]")
                appendLine("Citation: ${result.citationMarkdown}")
                appendLine(result.text.take(1_200))
            }
        }.trim()
    }

    suspend fun executeToolCall(
        toolCall: OllamaService.ToolCall,
        config: NativeChatToolConfig,
        chatId: Long? = null,
        onProgress: (NativeChatToolProgress) -> Unit = {},
        searchSummarizer: (suspend (NativeChatSearchSummaryRequest) -> String)? = null
    ): Result<NativeChatToolResult> = withContext(Dispatchers.IO) {
        try {
            val output = when (toolCall.name) {
                TOOL_MODIFY_PINNED_NOTE -> {
                    val pinnedId = config.pinnedNoteId
                    require(config.toolsEnabled && pinnedId != null && pinnedId > 0) {
                        "No pinned note is available for this Quick Chat."
                    }
                    modifyPinnedNote(
                        noteId = pinnedId,
                        operation = toolArg(toolCall.arguments, "operation", "action").orEmpty(),
                        findText = toolArg(toolCall.arguments, "find_text", "findText", "old_text", "oldText"),
                        replacementText = toolArg(toolCall.arguments, "replacement_text", "replacementText", "new_text", "newText"),
                        content = toolArg(toolCall.arguments, "content", "body", "text"),
                        replaceAll = parseOptionalBoolean(toolArg(toolCall.arguments, "replace_all", "replaceAll"), false)
                    )
                }
                TOOL_WEB_SEARCH -> {
                    require(config.toolsEnabled && config.webSearchEnabled) { "web_search is disabled for this chat." }
                    webSearch(
                        query = toolCall.arguments["query"].orEmpty(),
                        maxPages = config.webSearchMaxPages,
                        maxCharsPerPage = config.webSearchMaxChars,
                        onProgress = onProgress,
                        searchSummarizer = searchSummarizer
                    )
                }
                TOOL_KIWIX_SEARCH -> {
                    require(config.toolsEnabled && config.kiwixSearchEnabled) { "kiwix_search is disabled for this chat." }
                    kiwixSearch(
                        query = toolCall.arguments["query"].orEmpty(),
                        baseUrl = config.normalizedKiwixUrl(),
                        maxPages = config.kiwixMaxPages,
                        maxCharsPerPage = config.kiwixMaxChars,
                        onProgress = onProgress,
                        searchSummarizer = searchSummarizer
                    )
                }
                TOOL_KB_SEARCH -> {
                    require(config.toolsEnabled && config.knowledgeBaseEnabled) { "kb_search is disabled for this chat." }
                    val scopeIds = config.knowledgeBaseScopeIds()
                    require(scopeIds.isNotEmpty()) { "Select a knowledge base or upload a document to this chat before using kb_search." }
                    val queries = parseToolStringList(toolCall.arguments, "queries", "query_list", "queryList")
                        .ifEmpty {
                            listOf(toolArg(toolCall.arguments, "query", "question").orEmpty().trim())
                                .filter { it.isNotBlank() }
                        }
                    require(queries.isNotEmpty()) { "query or queries is required." }
                    val maxResults = parseOptionalInt(toolArg(toolCall.arguments, "max_results", "limit"))
                        ?: config.knowledgeBaseMaxResults
                    onProgress(
                        NativeChatToolProgress(
                            status = "Searching knowledge base",
                            phase = NativeChatToolProgressPhase.SEARCHING,
                            outputPreview = queries.joinToString(" | ").take(300)
                        )
                    )
                    val repo = requireNotNull(knowledgeBaseRepository) { "Knowledge base repository is unavailable." }
                    buildString {
                        appendLine("kb_search_queries: ${queries.size}")
                        queries.forEachIndexed { queryIndex, query ->
                            appendLine("query_${queryIndex + 1}: $query")
                            val results = repo.search(
                                query = query,
                                knowledgeBaseIds = scopeIds,
                                maxResults = maxResults
                            )
                            if (results.isEmpty()) {
                                appendLine("No matching knowledge-base chunks found for query_${queryIndex + 1}.")
                            } else {
                                results.forEach { result ->
                                    appendLine()
                                    appendLine("[query_${queryIndex + 1} chunk_id=${result.chunkId}] ${result.knowledgeBaseName} / ${result.sourceTitle}")
                                    appendLine("citation=${result.citationMarkdown}")
                                    appendLine("score=${"%.3f".format(Locale.US, result.score)}")
                                    appendLine(result.text.take(1_600))
                                }
                            }
                            if (queryIndex != queries.lastIndex) appendLine()
                        }
                        appendLine()
                        appendLine("final_answer_instruction: If these searches answer the user's request, send a visible chat response now and cite KB-derived claims with the citation links above.")
                    }.trim()
                }
                TOOL_KB_READ_CHUNK -> {
                    require(config.toolsEnabled && config.knowledgeBaseEnabled) { "kb_read_chunk is disabled for this chat." }
                    val scopeIds = config.knowledgeBaseScopeIds()
                    val chunkId = toolArg(toolCall.arguments, "chunk_id", "id")?.toLongOrNull()
                        ?: error("chunk_id is required.")
                    val includeNeighbors = toolArg(toolCall.arguments, "include_neighbors", "neighbors")
                        ?.equals("true", ignoreCase = true) == true
                    val repo = requireNotNull(knowledgeBaseRepository) { "Knowledge base repository is unavailable." }
                    repo.readChunk(chunkId, includeNeighbors, scopeIds)
                }
                TOOL_KB_READ_SOURCE -> {
                    require(config.toolsEnabled && config.knowledgeBaseEnabled) { "kb_read_source is disabled for this chat." }
                    val scopeIds = config.knowledgeBaseScopeIds()
                    require(scopeIds.isNotEmpty()) { "Select a knowledge base or upload a document to this chat before using kb_read_source." }
                    val sourceId = toolArg(toolCall.arguments, "source_id", "id")?.toLongOrNull()
                    val startChunk = parseOptionalInt(toolArg(toolCall.arguments, "start_chunk", "offset")) ?: 0
                    val maxChunks = parseOptionalInt(toolArg(toolCall.arguments, "max_chunks", "limit")) ?: 18
                    onProgress(
                        NativeChatToolProgress(
                            status = "Reading document chunks",
                            phase = NativeChatToolProgressPhase.READING,
                            outputPreview = sourceId?.let { "#$it" } ?: "chat document"
                        )
                    )
                    val repo = requireNotNull(knowledgeBaseRepository) { "Knowledge base repository is unavailable." }
                    repo.readSourcesForSummary(
                        knowledgeBaseIds = scopeIds,
                        sourceId = sourceId,
                        startChunk = startChunk,
                        maxChunksPerSource = maxChunks
                    )
                }
                TOOL_KB_LIST_SOURCES -> {
                    require(config.toolsEnabled && config.knowledgeBaseEnabled) { "kb_list_sources is disabled for this chat." }
                    val scopeIds = config.knowledgeBaseScopeIds()
                    require(scopeIds.isNotEmpty()) { "Select a knowledge base or upload a document to this chat before using kb_list_sources." }
                    val repo = requireNotNull(knowledgeBaseRepository) { "Knowledge base repository is unavailable." }
                    repo.listSources(scopeIds)
                }
                TOOL_SEARCH_PAGE -> {
                    require(config.toolsEnabled && (config.webSearchEnabled || config.fetchUrlEnabled)) { "search_page is disabled for this chat." }
                    val maxChars = if (config.fetchUrlEnabled) config.fetchUrlMaxChars else config.webSearchMaxChars
                    onProgress(
                        NativeChatToolProgress(
                            status = "Searching page",
                            phase = NativeChatToolProgressPhase.FETCHING,
                            url = toolArg(toolCall.arguments, "url").orEmpty(),
                            outputPreview = toolArg(toolCall.arguments, "query", "search_text", "searchText", "find").orEmpty()
                        )
                    )
                    searchPage(
                        url = toolArg(toolCall.arguments, "url").orEmpty(),
                        query = toolArg(toolCall.arguments, "query", "search_text", "searchText", "find", "link_query", "linkQuery"),
                        maxChars = maxChars,
                        maxLinks = parseOptionalInt(toolArg(toolCall.arguments, "max_links", "maxLinks", "limit")),
                        maxMatches = parseOptionalInt(toolArg(toolCall.arguments, "max_matches", "maxMatches")),
                        onProgress = onProgress
                    )
                }
                TOOL_FETCH_URL -> {
                    require(config.toolsEnabled && config.fetchUrlEnabled) { "fetch_url is disabled for this chat." }
                    val url = toolArg(toolCall.arguments, "url", "href", "link").orEmpty()
                    onProgress(
            NativeChatToolProgress(
                status = "Fetching URL",
                phase = NativeChatToolProgressPhase.FETCHING,
                url = url
            )
                    )
                    fetchUrl(
                        url = url,
                        maxChars = config.fetchUrlMaxChars,
                        searchText = toolArg(toolCall.arguments, "search_text", "searchText", "query", "find", "find_text", "findText"),
                        linkQuery = toolArg(toolCall.arguments, "link_query", "linkQuery", "navigate", "navigation_query", "navigationQuery"),
                        maxLinks = parseOptionalInt(toolArg(toolCall.arguments, "max_links", "maxLinks", "link_limit", "linkLimit"))
                    )
                }
                TOOL_DEEP_RESEARCH -> {
                    require(config.toolsEnabled && config.deepResearchEnabled) { "deep_research is disabled for this chat." }
                    val appContext = context?.applicationContext ?: error("Deep Research requires an application context.")
                    val cleanQuery = toolArg(toolCall.arguments, "query", "q", "research_query", "researchQuery").orEmpty()
                    require(cleanQuery.isNotBlank()) { "query is required." }
                    val safeChatId = chatId?.takeIf { it > 0L } ?: error("Deep Research requires a chat id.")
                    val sourceLimit = parseOptionalInt(toolArg(toolCall.arguments, "source_limit", "sourceLimit", "limit"))
                        ?: config.deepResearchSourceLimit
                    val contentSummary = toolArg(
                        toolCall.arguments,
                        "content_summary",
                        "contentSummary",
                        "kb_summary",
                        "kbSummary",
                        "knowledge_base_summary",
                        "knowledgeBaseSummary"
                    )
                    val selectedTargets = config.selectedKnowledgeBaseIds.filter { it > 0L }.distinct()
                    val requestedTarget = parseOptionalLong(
                        toolArg(
                            toolCall.arguments,
                            "target_knowledge_base_id",
                            "targetKnowledgeBaseId",
                            "knowledge_base_id",
                            "knowledgeBaseId",
                            "kb_id",
                            "kbId"
                        )
                    )
                    val targetKnowledgeBaseId = if (config.deepResearchImportIntoSelectedKbEnabled) {
                        requestedTarget?.also { requested ->
                            require(requested in selectedTargets) {
                                "target_knowledge_base_id must be one of the knowledge bases selected for this chat."
                            }
                        } ?: selectedTargets.firstOrNull()
                    } else {
                        null
                    }
                    onProgress(
                        NativeChatToolProgress(
                            status = "Starting Deep Research",
                            phase = NativeChatToolProgressPhase.RESEARCHING,
                            outputPreview = cleanQuery
                        )
                    )
                    val result = DeepResearchService.runAndAwait(
                        context = appContext,
                        chatId = safeChatId,
                        query = cleanQuery,
                        title = toolArg(toolCall.arguments, "title", "topic"),
                        focus = toolArg(toolCall.arguments, "focus", "scope"),
                        contentSummary = contentSummary,
                        sourceLimit = DeepResearchSupport.normalizeSourceLimit(sourceLimit),
                        targetKnowledgeBaseId = targetKnowledgeBaseId
                    )
                    onProgress(
                        NativeChatToolProgress(
                            status = "Deep Research finished",
                            phase = NativeChatToolProgressPhase.RESEARCHING,
                            outputPreview = "${result.importedSources}/${result.sourceLimit} sources"
                        )
                    )
                    buildString {
                        appendLine("Deep Research finished for: ${result.query}")
                        appendLine("Imported sources: ${result.importedSources} (maximum ${result.sourceLimit})")
                        appendLine("Knowledge base id: ${result.knowledgeBaseId}")
                        appendLine(
                            if (result.usedExistingKnowledgeBase) {
                                "New sources were imported into the selected knowledge base for this chat."
                            } else {
                                "The knowledge base has been created, indexed, and selected for this chat."
                            }
                        )
                        appendLine("final_answer_instruction: Send a visible chat message now. Briefly tell the user the Deep Research job finished, mention how many sources were imported, and offer to search or summarize the updated knowledge base. If you answer with facts from the KB, call kb_search or kb_read_source first and cite exact KB links.")
                        if (!config.knowledgeBaseEnabled) {
                            appendLine("Warning: knowledge-base search tools are currently disabled by the effective chat/server permissions. The generated KB will stay selected, but the chat cannot search it until KB tools are allowed.")
                        }
                    }.trim()
                }
                TOOL_GET_DATETIME -> {
                    require(config.toolsEnabled && config.dateTimeEnabled) { "get_datetime is disabled for this chat." }
                    currentDateTime()
                }
                TOOL_CALCULATOR -> {
                    require(config.toolsEnabled && config.calculatorEnabled) { "calculator is disabled for this chat." }
                    calculate(toolCall.arguments["expression"].orEmpty())
                }
                TOOL_LIST_NOTES -> {
                    require(config.toolsEnabled && config.pinnedNoteId == null && (config.noteToolsEnabled || config.todoToolsEnabled)) { "list_notes is disabled for this chat." }
                    listNotes(
                        query = toolArg(toolCall.arguments, "query", "q", "search"),
                        type = toolArg(toolCall.arguments, "type", "note_type", "noteType"),
                        maxResults = parseOptionalInt(toolArg(toolCall.arguments, "max_results", "maxResults", "limit"))
                    )
                }
                TOOL_READ_NOTE -> {
                    require(config.toolsEnabled && config.pinnedNoteId == null && (config.noteToolsEnabled || config.todoToolsEnabled)) { "read_note is disabled for this chat." }
                    readNote(
                        noteId = parseRequiredInt(toolArg(toolCall.arguments, "note_id", "noteId", "id"), "note_id")
                    )
                }
                TOOL_CREATE_NOTE -> {
                    require(config.toolsEnabled && config.pinnedNoteId == null && config.noteToolsEnabled) { "create_note is disabled for this chat." }
                    createNote(
                        title = toolArg(toolCall.arguments, "title", "name").orEmpty(),
                        content = toolArg(toolCall.arguments, "content", "body", "text").orEmpty()
                    )
                }
                TOOL_UPDATE_NOTE -> {
                    require(config.toolsEnabled && config.pinnedNoteId == null && config.noteToolsEnabled) { "update_note is disabled for this chat." }
                    updateNote(
                        noteId = parseRequiredInt(toolArg(toolCall.arguments, "note_id", "noteId", "id"), "note_id"),
                        title = toolArg(toolCall.arguments, "title", "name", "new_title", "newTitle"),
                        content = toolArg(toolCall.arguments, "content", "body", "text", "new_content", "newContent"),
                        appendText = toolArg(toolCall.arguments, "append_text", "appendText", "append_content", "appendContent")
                    )
                }
                TOOL_REPLACE_NOTE_TEXT -> {
                    require(config.toolsEnabled && config.pinnedNoteId == null && config.noteToolsEnabled) { "replace_note_text is disabled for this chat." }
                    replaceNoteText(
                        noteId = parseRequiredInt(toolArg(toolCall.arguments, "note_id", "noteId", "id"), "note_id"),
                        findText = toolArg(toolCall.arguments, "find_text", "findText", "old_text", "oldText", "search_text", "searchText", "find").orEmpty(),
                        replacementText = toolArg(toolCall.arguments, "replacement_text", "replacementText", "new_text", "newText", "replace_with", "replaceWith", "replacement").orEmpty(),
                        replaceAll = parseOptionalBoolean(toolArg(toolCall.arguments, "replace_all", "replaceAll", "all", "global"), false),
                        caseSensitive = parseOptionalBoolean(toolArg(toolCall.arguments, "case_sensitive", "caseSensitive", "match_case", "matchCase"), true)
                    )
                }
                TOOL_CREATE_TODO_LIST -> {
                    require(config.toolsEnabled && config.pinnedNoteId == null && config.todoToolsEnabled) { "create_todo_list is disabled for this chat." }
                    createTodoList(
                        title = toolArg(toolCall.arguments, "title", "name").orEmpty(),
                        itemsText = toolArg(toolCall.arguments, "items", "tasks", "todos", "content").orEmpty()
                    )
                }
                TOOL_ADD_TODO_ITEM -> {
                    require(config.toolsEnabled && config.pinnedNoteId == null && config.todoToolsEnabled) { "add_todo_item is disabled for this chat." }
                    addTodoItem(
                        noteId = parseRequiredInt(toolArg(toolCall.arguments, "note_id", "noteId", "id"), "note_id"),
                        item = toolArg(toolCall.arguments, "item", "text", "content", "task").orEmpty()
                    )
                }
                TOOL_UPDATE_TODO_ITEM -> {
                    require(config.toolsEnabled && config.pinnedNoteId == null && config.todoToolsEnabled) { "update_todo_item is disabled for this chat." }
                    updateTodoItem(
                        noteId = parseRequiredInt(toolArg(toolCall.arguments, "note_id", "noteId", "id"), "note_id"),
                        itemIndex = parseRequiredInt(toolArg(toolCall.arguments, "item_index", "itemIndex", "index", "position"), "item_index"),
                        item = toolArg(toolCall.arguments, "item", "text", "content", "task", "new_item", "newItem").orEmpty()
                    )
                }
                TOOL_REMOVE_TODO_ITEM -> {
                    require(config.toolsEnabled && config.pinnedNoteId == null && config.todoToolsEnabled) { "remove_todo_item is disabled for this chat." }
                    removeTodoItem(
                        noteId = parseRequiredInt(toolArg(toolCall.arguments, "note_id", "noteId", "id"), "note_id"),
                        itemIndex = parseRequiredInt(toolArg(toolCall.arguments, "item_index", "itemIndex", "index", "position"), "item_index")
                    )
                }
                TOOL_SET_TODO_ITEM_CHECKED -> {
                    require(config.toolsEnabled && config.pinnedNoteId == null && config.todoToolsEnabled) { "set_todo_item_checked is disabled for this chat." }
                    setTodoItemChecked(
                        noteId = parseRequiredInt(toolArg(toolCall.arguments, "note_id", "noteId", "id"), "note_id"),
                        itemIndex = parseRequiredInt(toolArg(toolCall.arguments, "item_index", "itemIndex", "index", "position"), "item_index"),
                        checked = parseRequiredBoolean(toolArg(toolCall.arguments, "checked", "is_checked", "isChecked", "complete", "completed", "done"), "checked")
                    )
                }
                TOOL_LIST_CALENDAR_EVENTS -> {
                    require(config.toolsEnabled && config.calendarToolsEnabled) { "list_calendar_events is disabled. Enable Organizer calendar access and this chat's calendar tool first." }
                    listCalendarEvents(
                        startText = toolArg(toolCall.arguments, "start_datetime", "startDateTime", "start", "from"),
                        endText = toolArg(toolCall.arguments, "end_datetime", "endDateTime", "end", "to"),
                        query = toolArg(toolCall.arguments, "query", "q", "search"),
                        maxResults = parseOptionalInt(toolArg(toolCall.arguments, "max_results", "maxResults", "limit"))
                    )
                }
                TOOL_READ_CALENDAR_EVENT -> {
                    require(config.toolsEnabled && config.calendarToolsEnabled) { "read_calendar_event is disabled. Enable Organizer calendar access and this chat's calendar tool first." }
                    readCalendarEvent(
                        eventId = parseRequiredLong(toolArg(toolCall.arguments, "event_id", "eventId", "id"), "event_id")
                    )
                }
                TOOL_CREATE_CALENDAR_EVENT -> {
                    require(config.toolsEnabled && config.calendarToolsEnabled) { "create_calendar_event is disabled. Enable Organizer calendar access and this chat's calendar tool first." }
                    createCalendarEvent(
                        title = toolArg(toolCall.arguments, "title", "name").orEmpty(),
                        startText = toolArg(toolCall.arguments, "start_datetime", "startDateTime", "start").orEmpty(),
                        endText = toolArg(toolCall.arguments, "end_datetime", "endDateTime", "end"),
                        allDayText = toolArg(toolCall.arguments, "all_day", "allDay"),
                        description = toolArg(toolCall.arguments, "description", "notes", "details"),
                        location = toolArg(toolCall.arguments, "location", "place"),
                        color = toolArg(toolCall.arguments, "color", "color_argb", "colorArgb"),
                        alarmText = toolArg(toolCall.arguments, "alarm_datetime", "alarmDateTime", "alarm_time", "alarmTime"),
                        alarmOffsetMinutes = parseOptionalInt(toolArg(toolCall.arguments, "alarm_offset_minutes", "alarmOffsetMinutes", "reminder_minutes", "reminderMinutes")),
                        canCreateAlarm = config.alarmToolsEnabled
                    )
                }
                TOOL_UPDATE_CALENDAR_EVENT -> {
                    require(config.toolsEnabled && config.calendarToolsEnabled) { "update_calendar_event is disabled. Enable Organizer calendar access and this chat's calendar tool first." }
                    updateCalendarEvent(
                        eventId = parseRequiredLong(toolArg(toolCall.arguments, "event_id", "eventId", "id"), "event_id"),
                        title = toolArg(toolCall.arguments, "title", "name"),
                        startText = toolArg(toolCall.arguments, "start_datetime", "startDateTime", "start"),
                        endText = toolArg(toolCall.arguments, "end_datetime", "endDateTime", "end"),
                        allDayText = toolArg(toolCall.arguments, "all_day", "allDay"),
                        description = toolArg(toolCall.arguments, "description", "notes", "details"),
                        location = toolArg(toolCall.arguments, "location", "place"),
                        color = toolArg(toolCall.arguments, "color", "color_argb", "colorArgb")
                    )
                }
                TOOL_DELETE_CALENDAR_EVENT -> {
                    require(config.toolsEnabled && config.calendarToolsEnabled) { "delete_calendar_event is disabled. Enable Organizer calendar access and this chat's calendar tool first." }
                    deleteCalendarEvent(
                        eventId = parseRequiredLong(toolArg(toolCall.arguments, "event_id", "eventId", "id"), "event_id")
                    )
                }
                TOOL_LIST_ALARMS -> {
                    require(config.toolsEnabled && config.alarmToolsEnabled) { "list_alarms is disabled. Enable Organizer alarm access and this chat's alarm tool first." }
                    listAlarms(
                        startText = toolArg(toolCall.arguments, "start_datetime", "startDateTime", "start", "from"),
                        endText = toolArg(toolCall.arguments, "end_datetime", "endDateTime", "end", "to"),
                        query = toolArg(toolCall.arguments, "query", "q", "search"),
                        enabledOnly = parseOptionalBoolean(toolArg(toolCall.arguments, "enabled_only", "enabledOnly", "active_only", "activeOnly"), false),
                        maxResults = parseOptionalInt(toolArg(toolCall.arguments, "max_results", "maxResults", "limit"))
                    )
                }
                TOOL_READ_ALARM -> {
                    require(config.toolsEnabled && config.alarmToolsEnabled) { "read_alarm is disabled. Enable Organizer alarm access and this chat's alarm tool first." }
                    readAlarm(
                        alarmId = parseRequiredLong(toolArg(toolCall.arguments, "alarm_id", "alarmId", "id"), "alarm_id")
                    )
                }
                TOOL_CREATE_ALARM -> {
                    require(config.toolsEnabled && config.alarmToolsEnabled) { "create_alarm is disabled. Enable Organizer alarm access and this chat's alarm tool first." }
                    createAlarm(
                        title = toolArg(toolCall.arguments, "title", "name").orEmpty(),
                        triggerText = toolArg(toolCall.arguments, "trigger_datetime", "triggerDateTime", "time", "datetime").orEmpty(),
                        message = toolArg(toolCall.arguments, "message", "body", "description"),
                        soundEnabled = parseOptionalBoolean(toolArg(toolCall.arguments, "sound_enabled", "soundEnabled", "sound"), true),
                        eventId = parseOptionalLong(toolArg(toolCall.arguments, "event_id", "eventId"))
                    )
                }
                TOOL_UPDATE_ALARM -> {
                    require(config.toolsEnabled && config.alarmToolsEnabled) { "update_alarm is disabled. Enable Organizer alarm access and this chat's alarm tool first." }
                    updateAlarm(
                        alarmId = parseRequiredLong(toolArg(toolCall.arguments, "alarm_id", "alarmId", "id"), "alarm_id"),
                        title = toolArg(toolCall.arguments, "title", "name"),
                        triggerText = toolArg(toolCall.arguments, "trigger_datetime", "triggerDateTime", "time", "datetime"),
                        message = toolArg(toolCall.arguments, "message", "body", "description"),
                        soundEnabledText = toolArg(toolCall.arguments, "sound_enabled", "soundEnabled", "sound"),
                        enabledText = toolArg(toolCall.arguments, "enabled", "active")
                    )
                }
                TOOL_DELETE_ALARM -> {
                    require(config.toolsEnabled && config.alarmToolsEnabled) { "delete_alarm is disabled. Enable Organizer alarm access and this chat's alarm tool first." }
                    deleteAlarm(
                        alarmId = parseRequiredLong(toolArg(toolCall.arguments, "alarm_id", "alarmId", "id"), "alarm_id")
                    )
                }
                TOOL_GENERATE_IMAGE -> {
                    require(config.toolsEnabled && config.imageGenerationEnabled) { "generate_image is disabled for this chat." }
                    onProgress(
                        NativeChatToolProgress(
                            status = "Generating image",
                            phase = NativeChatToolProgressPhase.GENERATING,
                            title = toolCall.arguments["prompt"].orEmpty().take(120)
                        )
                    )
                    generateImage(
                        prompt = toolCall.arguments["prompt"].orEmpty(),
                        negativePrompt = toolCall.arguments["negative_prompt"].orEmpty(),
                        config = config
                    )
                }
                TOOL_REMOVE_IMAGE_BACKGROUND -> {
                    require(config.toolsEnabled && config.backgroundRemovalEnabled) { "remove_image_background is disabled for this chat." }
                    val imagePath = toolArg(toolCall.arguments, "image_path", "path", "source_image_path").orEmpty()
                    onProgress(
                        NativeChatToolProgress(
                            status = "Removing image background",
                            phase = NativeChatToolProgressPhase.GENERATING,
                            title = imagePath.takeLast(120)
                        )
                    )
                    removeImageBackground(
                        imagePath = imagePath,
                        config = config
                    )
                }
                else -> "tool_error: Unknown tool '${toolCall.name}'."
            }
            val normalizedResult = when (output) {
                    is NativeChatToolResult -> output
                    else -> NativeChatToolResult(output.toString())
                }
            Result.success(
                if (toolCall.name in ORGANIZER_TIME_CONTEXT_TOOLS) {
                    normalizedResult.copy(
                        content = normalizedResult.content.trimEnd() +
                            "\n\n" + currentDateTimeReminder()
                    )
                } else {
                    normalizedResult
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            Result.failure(error.asException())
        }
    }

    private suspend fun webSearch(
        query: String,
        maxPages: Int,
        maxCharsPerPage: Int,
        onProgress: (NativeChatToolProgress) -> Unit,
        searchSummarizer: (suspend (NativeChatSearchSummaryRequest) -> String)?
    ): String {
        val trimmedQuery = query.trim()
        require(trimmedQuery.isNotBlank()) { "Search query is required." }
        onProgress(
            NativeChatToolProgress(
                status = "Searching web",
                phase = NativeChatToolProgressPhase.SEARCHING,
                outputPreview = trimmedQuery
            )
        )

        val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
        val searchUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"
        val html = client.newCall(
            Request.Builder()
                .url(searchUrl)
                .header(USER_AGENT_HEADER, DEFAULT_USER_AGENT)
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Web search failed: HTTP ${response.code}.")
            }
            response.body?.charStream()?.use { reader ->
                readLimited(reader, MAX_SEARCH_HTML_CHARS)
            }.orEmpty()
        }

        val links = DUCKDUCKGO_RESULT_PATTERN.findAll(html)
            .map { match ->
                SearchLink(
                    url = decodeDuckDuckGoUrl(match.groupValues[1]),
                    title = decodeHtmlEntities(AgentRuntimeSupport.stripHtmlTags(match.groupValues[2])).ifBlank { "Untitled result" }
                )
            }
            .distinctBy { it.url }
            .take(maxPages.coerceIn(NativeChatToolConfig.MIN_SEARCH_PAGES, NativeChatToolConfig.MAX_SEARCH_PAGES))
            .toList()

        if (links.isEmpty()) {
            return "tool: web_search\nquery: $trimmedQuery\nresults_returned: 0\n\nNo web results found."
        }
        onProgress(
            NativeChatToolProgress(
                status = "Found ${links.size} web results",
                phase = NativeChatToolProgressPhase.FOUND,
                count = links.size,
                outputPreview = links.joinToString("\n") { "${it.title}\n${it.url}" }
            )
        )

        return buildString {
            appendLine("trust: untrusted_external_content")
            appendLine("tool: web_search")
            appendLine("mode: compact_search_agent")
            appendLine("query: $trimmedQuery")
            appendLine("results_returned: ${links.size}")
            appendLine("citation_policy: Cite KB/web/Kiwix-derived claims using the returned Markdown citation links.")
            appendLine()
            links.forEachIndexed { index, link ->
                onProgress(
                    NativeChatToolProgress(
                        status = "Reading web result ${index + 1}/${links.size}",
                        phase = NativeChatToolProgressPhase.READING,
                        current = index + 1,
                        total = links.size,
                        title = link.title,
                        url = link.url
                    )
                )
                appendLine("[${index + 1}] ${link.title}")
                appendLine("source_url: ${link.url}")
                appendLine("citation_token: [${index + 1}]")
                val content = runCatching {
                    fetchUrlText(
                        initialUrl = link.url,
                        maxChars = maxCharsPerPage,
                        allowLocal = false
                    )
                }.getOrElse { error ->
                    FetchedText(
                        finalUrl = link.url,
                        contentType = "unknown",
                        redirectsFollowed = 0,
                        text = "tool_fetch_error: ${error.message ?: error::class.java.simpleName}"
                    )
                }
                val summary = summarizeNativeSearchPage(
                    title = link.title,
                    url = content.finalUrl,
                    content = content.text,
                    source = "web_search",
                    searchSummarizer = searchSummarizer
                )
                val citation = sourceCitationMarkdown(link.title, content.finalUrl)
                onProgress(
                    NativeChatToolProgress(
                        status = "Summarized web result ${index + 1}/${links.size}",
                        phase = NativeChatToolProgressPhase.SUMMARIZED,
                        current = index + 1,
                        total = links.size,
                        title = link.title,
                        url = content.finalUrl,
                        outputPreview = summary,
                        isComplete = true
                    )
                )
                appendLine("final_url: ${content.finalUrl}")
                appendLine("citation: $citation")
                appendLine("source_citation: $citation")
                appendLine("content_type: ${content.contentType}")
                appendLine("redirects_followed: ${content.redirectsFollowed}")
                appendLine("summary:")
                appendLine(summary)
                appendLine()
            }
            appendLine(
                sourceCitationBlock(
                    links.mapIndexed { index, link ->
                        "${index + 1}. ${sourceCitationMarkdown(link.title, link.url)}"
                    }
                )
            )
            appendLine()
            appendLine("TIP: Results are compact summaries. For deep research, use fetch_url with promising or authoritative URLs above before relying on a source.")
            appendLine("TIP: To navigate within a result page, call search_page with that source_url and a query such as commits, releases, changelog, docs, or issues; then call fetch_url on the returned page link for full content.")
            appendLine("TIP: If note tools are enabled and the research should be saved, call create_note or update_note with cited sources before the final answer.")
        }.trimEnd()
    }

    private suspend fun kiwixSearch(
        query: String,
        baseUrl: String,
        maxPages: Int,
        maxCharsPerPage: Int,
        onProgress: (NativeChatToolProgress) -> Unit,
        searchSummarizer: (suspend (NativeChatSearchSummaryRequest) -> String)?
    ): String {
        val trimmedQuery = query.trim()
        require(trimmedQuery.isNotBlank()) { "Kiwix query is required." }
        blockedKiwixBaseUrlReason(baseUrl)?.let { reason -> throw IllegalArgumentException(reason) }
        onProgress(
            NativeChatToolProgress(
                status = "Searching Kiwix",
                phase = NativeChatToolProgressPhase.SEARCHING,
                outputPreview = trimmedQuery
            )
        )

        val normalizedBase = baseUrl.trimEnd('/')
        val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
        val searchUrl = "$normalizedBase/search?pattern=$encodedQuery"
        val html = client.newCall(
            Request.Builder()
                .url(searchUrl)
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Kiwix search failed: HTTP ${response.code}.")
            }
            response.body?.charStream()?.use { reader ->
                readLimited(reader, MAX_SEARCH_HTML_CHARS)
            }.orEmpty()
        }

        val links = KIWIX_RESULT_PATTERN.findAll(html)
            .map { match ->
                SearchLink(
                    url = resolveUrl(normalizedBase, match.groupValues[1]),
                    title = decodeHtmlEntities(AgentRuntimeSupport.stripHtmlTags(match.groupValues[2])).ifBlank { "Untitled article" }
                )
            }
            .distinctBy { it.url }
            .take(maxPages.coerceIn(NativeChatToolConfig.MIN_SEARCH_PAGES, NativeChatToolConfig.MAX_SEARCH_PAGES))
            .toList()

        if (links.isEmpty()) {
            return "tool: kiwix_search\nquery: $trimmedQuery\nkiwix_url: $normalizedBase\nresults_returned: 0\n\nNo Kiwix results found."
        }
        onProgress(
            NativeChatToolProgress(
                status = "Found ${links.size} Kiwix results",
                phase = NativeChatToolProgressPhase.FOUND,
                count = links.size,
                outputPreview = links.joinToString("\n") { "${it.title}\n${it.url}" }
            )
        )

        return buildString {
            appendLine("trust: untrusted_kiwix_content")
            appendLine("tool: kiwix_search")
            appendLine("mode: compact_search_agent")
            appendLine("query: $trimmedQuery")
            appendLine("kiwix_url: $normalizedBase")
            appendLine("results_returned: ${links.size}")
            appendLine("citation_policy: Cite KB/web/Kiwix-derived claims using the returned Markdown citation links.")
            appendLine()
            links.forEachIndexed { index, link ->
                onProgress(
                    NativeChatToolProgress(
                        status = "Reading Kiwix result ${index + 1}/${links.size}",
                        phase = NativeChatToolProgressPhase.READING,
                        current = index + 1,
                        total = links.size,
                        title = link.title,
                        url = link.url
                    )
                )
                appendLine("[${index + 1}] ${link.title}")
                appendLine("source_url: ${link.url}")
                appendLine("citation_token: [${index + 1}]")
                val content = runCatching {
                    fetchUrlText(
                        initialUrl = link.url,
                        maxChars = maxCharsPerPage,
                        allowLocal = true
                    )
                }.getOrElse { error ->
                    FetchedText(
                        finalUrl = link.url,
                        contentType = "unknown",
                        redirectsFollowed = 0,
                        text = "tool_fetch_error: ${error.message ?: error::class.java.simpleName}"
                    )
                }
                val summary = summarizeNativeSearchPage(
                    title = link.title,
                    url = content.finalUrl,
                    content = content.text,
                    source = "kiwix_search",
                    searchSummarizer = searchSummarizer
                )
                val citation = sourceCitationMarkdown(link.title, content.finalUrl)
                onProgress(
                    NativeChatToolProgress(
                        status = "Summarized Kiwix result ${index + 1}/${links.size}",
                        phase = NativeChatToolProgressPhase.SUMMARIZED,
                        current = index + 1,
                        total = links.size,
                        title = link.title,
                        url = content.finalUrl,
                        outputPreview = summary,
                        isComplete = true
                    )
                )
                appendLine("final_url: ${content.finalUrl}")
                appendLine("citation: $citation")
                appendLine("source_citation: $citation")
                appendLine("content_type: ${content.contentType}")
                appendLine("summary:")
                appendLine(summary)
                appendLine()
            }
            appendLine(
                sourceCitationBlock(
                    links.mapIndexed { index, link ->
                        "${index + 1}. ${sourceCitationMarkdown(link.title, link.url)}"
                    }
                )
            )
            appendLine()
            appendLine("TIP: Results are compact offline summaries. Run kiwix_search with a narrower query when you need more offline context; use fetch_url only for public HTTP/HTTPS URLs from other tools.")
        }.trimEnd()
    }

    private suspend fun summarizeNativeSearchPage(
        title: String,
        url: String,
        content: String,
        source: String,
        searchSummarizer: (suspend (NativeChatSearchSummaryRequest) -> String)?
    ): String {
        val fallback = summarizeNativeSearchTextForTool(content)
        if (searchSummarizer == null ||
            fallback.startsWith("non_text_content_skipped:") ||
            fallback.startsWith("tool_fetch_error:") ||
            fallback == "No readable text found."
        ) {
            return fallback
        }
        return runCatching {
            searchSummarizer(
                NativeChatSearchSummaryRequest(
                    title = title,
                    url = url,
                    content = content,
                    source = source
                )
            ).trim()
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.limitForTool(900)
            ?: fallback
    }

    private fun searchPage(
        url: String,
        query: String?,
        maxChars: Int,
        maxLinks: Int?,
        maxMatches: Int?,
        onProgress: (NativeChatToolProgress) -> Unit
    ): String {
        val cleanQuery = query?.trim().orEmpty()
        val content = fetchUrlText(
            initialUrl = url,
            maxChars = maxChars.coerceIn(NativeChatToolConfig.MIN_PAGE_CHARS, NativeChatToolConfig.MAX_FETCH_CHARS),
            allowLocal = false
        )
        val linkLimit = (maxLinks ?: DEFAULT_PAGE_LINK_LIMIT).coerceIn(1, MAX_PAGE_LINK_LIMIT)
        val matchLimit = (maxMatches ?: DEFAULT_PAGE_MATCH_LIMIT).coerceIn(1, MAX_PAGE_MATCH_LIMIT)
        val snippets = findNativePageTextSnippetsForTool(content.text, cleanQuery, matchLimit)
        val links = filterNativePageLinksForTool(content.links, cleanQuery, linkLimit)
        onProgress(
            NativeChatToolProgress(
                status = "Found ${links.size} page links",
                phase = NativeChatToolProgressPhase.FOUND,
                count = links.size,
                url = content.finalUrl,
                outputPreview = links.joinToString("\n") { "${it.title}\n${it.url}" },
                isComplete = true
            )
        )

        return buildString {
            val pageCitation = sourceCitationMarkdown(content.finalUrl, content.finalUrl)
            val sourceCitations = mutableListOf("1. $pageCitation")
            appendLine("trust: untrusted_external_content")
            appendLine("tool: search_page")
            appendLine("source_url: ${content.finalUrl}")
            appendLine("citation_token: [1]")
            appendLine("citation: $pageCitation")
            appendLine("source_citation: $pageCitation")
            appendLine("content_type: ${content.contentType}")
            appendLine("redirects_followed: ${content.redirectsFollowed}")
            if (cleanQuery.isNotBlank()) appendLine("query: $cleanQuery")
            appendLine("page_summary:")
            appendLine(summarizeNativeSearchTextForTool(content.text, 700))
            appendLine()
            appendLine("text_matches_returned: ${snippets.size}")
            if (snippets.isNotEmpty()) {
                appendLine("text_matches:")
                snippets.forEachIndexed { index, snippet ->
                    appendLine("${index + 1}. $snippet")
                }
                appendLine()
            }
            appendLine("navigation_links_returned: ${links.size}")
            if (links.isNotEmpty()) {
                appendLine("navigation_links:")
                links.forEachIndexed { index, link ->
                    val citation = sourceCitationMarkdown(link.title, link.url)
                    sourceCitations += "${index + 2}. $citation"
                    appendLine("[${index + 1}] ${link.title}")
                    appendLine("url: ${link.url}")
                    appendLine("citation_token: [${index + 2}]")
                    appendLine("citation: $citation")
                    appendLine("source_citation: $citation")
                }
                appendLine()
            }
            appendLine(sourceCitationBlock(sourceCitations))
            appendLine()
            appendLine("TIP: Use fetch_url on a returned URL to read full content; use search_page again on a returned URL to keep navigating within the site.")
        }.trimEnd()
    }

    private fun fetchUrl(
        url: String,
        maxChars: Int,
        searchText: String? = null,
        linkQuery: String? = null,
        maxLinks: Int? = null
    ): String {
        val content = fetchUrlText(
            initialUrl = url,
            maxChars = maxChars.coerceIn(NativeChatToolConfig.MIN_FETCH_CHARS, NativeChatToolConfig.MAX_FETCH_CHARS),
            allowLocal = false
        )
        val cleanSearchText = searchText?.trim().orEmpty()
        val cleanLinkQuery = linkQuery?.trim().orEmpty()
        val linksQuery = cleanLinkQuery.ifBlank { cleanSearchText }
        val linkLimit = (maxLinks ?: DEFAULT_PAGE_LINK_LIMIT).coerceIn(1, MAX_PAGE_LINK_LIMIT)
        val snippets = findNativePageTextSnippetsForTool(content.text, cleanSearchText, DEFAULT_PAGE_MATCH_LIMIT)
        val links = if (linksQuery.isNotBlank()) {
            filterNativePageLinksForTool(content.links, linksQuery, linkLimit)
        } else {
            emptyList()
        }
        return buildString {
            val pageCitation = sourceCitationMarkdown(content.finalUrl, content.finalUrl)
            val sourceCitations = mutableListOf("1. $pageCitation")
            appendLine("trust: untrusted_external_content")
            appendLine("tool: fetch_url")
            appendLine("source_url: ${content.finalUrl}")
            appendLine("citation_token: [1]")
            appendLine("citation: $pageCitation")
            appendLine("source_citation: $pageCitation")
            appendLine("content_type: ${content.contentType}")
            appendLine("redirects_followed: ${content.redirectsFollowed}")
            appendLine("content:")
            appendLine(content.text)
            if (cleanSearchText.isNotBlank()) {
                appendLine()
                appendLine("search_text: $cleanSearchText")
                appendLine("text_matches_returned: ${snippets.size}")
                snippets.forEachIndexed { index, snippet ->
                    appendLine("${index + 1}. $snippet")
                }
            }
            if (linksQuery.isNotBlank()) {
                appendLine()
                appendLine("link_query: $linksQuery")
                appendLine("navigation_links_returned: ${links.size}")
                links.forEachIndexed { index, link ->
                    val citation = sourceCitationMarkdown(link.title, link.url)
                    sourceCitations += "${index + 2}. $citation"
                    appendLine("[${index + 1}] ${link.title}")
                    appendLine("url: ${link.url}")
                    appendLine("citation_token: [${index + 2}]")
                    appendLine("citation: $citation")
                    appendLine("source_citation: $citation")
                }
            }
            appendLine()
            appendLine(sourceCitationBlock(sourceCitations))
        }.trimEnd()
    }

    private fun fetchUrlText(initialUrl: String, maxChars: Int, allowLocal: Boolean): FetchedText {
        var currentUrl = initialUrl.trim()
        require(currentUrl.isNotBlank()) { "URL is required." }
        if (allowLocal) {
            blockedKiwixBaseUrlReason(currentUrl)?.let { reason -> throw IllegalArgumentException(reason) }
        } else {
            blockedNativeFetchUrlReason(currentUrl)?.let { reason -> throw IllegalArgumentException(reason) }
        }

        var redirectCount = 0
        while (true) {
            val response = client.newCall(
                Request.Builder()
                    .url(currentUrl)
                    .header(USER_AGENT_HEADER, DEFAULT_USER_AGENT)
                    .build()
            ).execute()

            response.use {
                if (response.isRedirect) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw IllegalArgumentException("Too many redirects while fetching URL.")
                    }
                    val location = response.header("Location")
                    val nextUrl = location?.let { header -> response.request.url.resolve(header)?.toString() }
                    if (nextUrl.isNullOrBlank()) {
                        throw IllegalArgumentException("Redirect response did not include a valid Location header.")
                    }
                    if (allowLocal) {
                        blockedKiwixBaseUrlReason(nextUrl)?.let { reason -> throw IllegalArgumentException("Redirect blocked: $reason") }
                    } else {
                        blockedNativeFetchUrlReason(nextUrl)?.let { reason -> throw IllegalArgumentException("Redirect blocked: $reason") }
                    }
                    currentUrl = nextUrl
                    redirectCount++
                    return@use
                }

                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP error code: ${response.code}")
                }

                val contentType = response.header("Content-Type", "").orEmpty()
                val finalUrl = response.request.url.toString()
                val pdfResponse = isNativeToolPdfContentType(contentType) ||
                    finalUrl.substringBefore('?').lowercase(Locale.US).endsWith(".pdf")
                if (pdfResponse) {
                    val declaredLength = response.body?.contentLength() ?: -1L
                    if (declaredLength > MAX_NATIVE_PDF_FETCH_BYTES) {
                        return FetchedText(
                            finalUrl = finalUrl,
                            contentType = contentType.ifBlank { "application/pdf" },
                            redirectsFollowed = redirectCount,
                            text = nativeToolPdfSkippedMessage(declaredLength)
                        )
                    }
                    val pdfBytes = readLimitedBytes(response.body?.byteStream(), MAX_NATIVE_PDF_FETCH_BYTES)
                    if (pdfBytes.truncated) {
                        return FetchedText(
                            finalUrl = finalUrl,
                            contentType = contentType.ifBlank { "application/pdf" },
                            redirectsFollowed = redirectCount,
                            text = nativeToolPdfSkippedMessage(pdfBytes.bytes.size.toLong())
                        )
                    }
                    val pdfText = pdfTextExtractor(pdfBytes.bytes, maxChars)
                        .ifBlank { "No readable PDF text found." }
                    return FetchedText(
                        finalUrl = finalUrl,
                        contentType = contentType.ifBlank { "application/pdf" },
                        redirectsFollowed = redirectCount,
                        text = pdfText.limitForTool(maxChars)
                    )
                }
                if (contentType.isNotBlank() && !isNativeToolReadableContentType(contentType)) {
                    return FetchedText(
                        finalUrl = finalUrl,
                        contentType = contentType,
                        redirectsFollowed = redirectCount,
                        text = nativeToolTextContentSkippedMessage(contentType)
                    )
                }
                val rawLimit = (maxChars * 4).coerceAtLeast(maxChars).coerceAtMost(MAX_RAW_FETCH_CHARS)
                val rawBody = response.body?.charStream()?.use { reader ->
                    readLimited(reader, rawLimit)
                }.orEmpty()
                if (looksLikeNativeNonTextContent(rawBody)) {
                    return FetchedText(
                        finalUrl = finalUrl,
                        contentType = contentType.ifBlank { "unknown" },
                        redirectsFollowed = redirectCount,
                        text = nativeToolTextContentSkippedMessage(contentType.ifBlank { "unknown" })
                    )
                }
                val isHtml = contentType.contains("html", ignoreCase = true) || rawBody.trimStart().startsWith("<")
                val pageLinks = if (isHtml) extractNativePageLinksForTool(rawBody, finalUrl) else emptyList()
                val readableText = if (isHtml) {
                    AgentRuntimeSupport.stripHtmlTags(rawBody)
                } else {
                    rawBody
                }.trim()
                return FetchedText(
                    finalUrl = finalUrl,
                    contentType = contentType.ifBlank { "unknown" },
                    redirectsFollowed = redirectCount,
                    text = readableText.limitForTool(maxChars),
                    links = pageLinks
                )
            }
        }
    }

    private fun currentDateTime(): String {
        val now = nowProvider()
        return buildString {
            appendLine("tool: get_datetime")
            appendLine("local_datetime: ${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
            appendLine("timezone: ${now.zone.id}")
            appendLine("date: ${now.toLocalDate()}")
            appendLine("time: ${now.toLocalTime().withNano(0)}")
        }.trimEnd()
    }

    private fun currentDateTimeReminder(): String {
        val now = nowProvider()
        return buildString {
            appendLine("current_datetime_reminder:")
            appendLine("local_datetime: ${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
            appendLine("timezone: ${now.zone.id}")
            append("instruction: Use this as the current reference for relative calendar and alarm dates.")
        }
    }

    private fun calculate(expression: String): String {
        val trimmed = expression.trim()
        require(trimmed.isNotBlank()) { "Expression is required." }
        require(trimmed.length <= MAX_CALCULATOR_EXPRESSION_CHARS) { "Expression is too long." }
        val result = evaluateNativeCalculatorExpression(trimmed)
        return buildString {
            appendLine("tool: calculator")
            appendLine("expression: $trimmed")
            append("result: ${formatCalculatorResult(result)}")
        }
    }

    private suspend fun listNotes(query: String?, type: String?, maxResults: Int?): String {
        val notes = requireNoteDao().getAllNotesOnce()
        val normalizedQuery = query?.trim().orEmpty()
        val typeFilter = parseOptionalNoteType(type)
        val limit = (maxResults ?: DEFAULT_NOTE_LIST_LIMIT).coerceIn(1, MAX_NOTE_LIST_LIMIT)
        val filtered = notes.asSequence()
            .filter { it.isLlmWhitelisted }
            .filter { note ->
                normalizedQuery.isBlank() ||
                    note.title.contains(normalizedQuery, ignoreCase = true) ||
                    note.content.contains(normalizedQuery, ignoreCase = true)
            }
            .filter { note -> typeFilter == null || note.type == typeFilter }
            .take(limit)
            .toList()

        return buildString {
            appendLine("tool: list_notes")
            if (normalizedQuery.isNotBlank()) appendLine("query: $normalizedQuery")
            typeFilter?.let { appendLine("type: ${it.name.lowercase(Locale.US)}") }
            appendLine("results_returned: ${filtered.size}")
            appendLine("hint: Use read_note on a listed note_id before editing; use update_note, replace_note_text, or todo-list tools instead of asking the user for IDs.")
            appendLine()
            filtered.forEach { note ->
                appendLine("note_id: ${note.id}")
                appendLine("title: ${note.title}")
                appendLine("type: ${note.type.name.lowercase(Locale.US)}")
                appendLine("updated_at_ms: ${note.updatedAt}")
                if (note.type == NoteType.TODO_LIST) {
                    val items = parseNativeTodoItems(note.content)
                    appendLine("todo_items: ${items.size}")
                    appendLine("todo_checked: ${items.count { it.checked }}")
                }
                appendLine("preview: ${markdownPreviewForTool(note.content, NOTE_PREVIEW_CHARS)}")
                appendLine()
            }
        }.trimEnd()
    }

    private suspend fun readNote(noteId: Int): String {
        val note = requireWhitelistedNote(noteId)
        return buildString {
            appendLine("tool: read_note")
            appendLine("note_id: ${note.id}")
            appendLine("title: ${note.title}")
            appendLine("type: ${note.type.name.lowercase(Locale.US)}")
            appendLine("updated_at_ms: ${note.updatedAt}")
            appendLine("hint: Use update_note or replace_note_text for regular note edits. For todo lists, use the 1-based item indexes shown here with the todo-list tools.")
            if (note.type == NoteType.TODO_LIST) {
                val items = parseNativeTodoItems(note.content)
                appendLine("todo_items:")
                items.forEachIndexed { index, item ->
                    appendLine("${index + 1}. [${if (item.checked) "x" else " "}] ${item.text}")
                }
            } else {
                appendLine("content:")
                appendLine(note.content.limitForTool(MAX_NOTE_READ_CHARS))
            }
        }.trimEnd()
    }

    private suspend fun modifyPinnedNote(
        noteId: Int,
        operation: String,
        findText: String?,
        replacementText: String?,
        content: String?,
        replaceAll: Boolean
    ): String {
        val dao = requireNoteDao()
        val storedPinnedId = context?.let { PinnedOrganizerNoteStore.get(it) }
        if (context != null) {
            require(storedPinnedId == noteId) { "The pinned note changed before this tool call could run." }
        }
        val currentPinnedId = storedPinnedId ?: noteId
        val note = dao.getNoteById(currentPinnedId) ?: error("The pinned note no longer exists.")
        val normalizedOperation = operation.trim().lowercase(Locale.US)
        if (normalizedOperation == "read") {
            return buildString {
                appendLine("tool: $TOOL_MODIFY_PINNED_NOTE")
                appendLine("operation: read")
                appendLine("title: ${note.title}")
                appendLine("content:")
                append(note.content.limitForTool(MAX_NOTE_READ_CHARS))
            }
        }
        val updatedContent = when (normalizedOperation) {
            "rewrite", "replace" -> content ?: error("content is required for rewrite.")
            "replace_text", "patch" -> {
                val find = findText.orEmpty()
                require(find.isNotEmpty()) { "find_text is required for replace_text." }
                require(note.content.contains(find)) { "find_text was not found in the pinned note." }
                if (replaceAll) note.content.replace(find, replacementText.orEmpty())
                else note.content.replaceFirst(find, replacementText.orEmpty())
            }
            else -> error("operation must be read, replace_text, or rewrite.")
        }
        dao.update(note.copy(content = updatedContent, updatedAt = System.currentTimeMillis()))
        notesChanged?.invoke()
        context?.let { appContext ->
            PhoneWearGateway.publishPinnedOrganizerNote(appContext)
        }
        return "tool: $TOOL_MODIFY_PINNED_NOTE\noperation: $normalizedOperation\ncontent_chars: ${updatedContent.length}\nstatus: updated"
    }

    private suspend fun createNote(title: String, content: String): String {
        val dao = requireNoteDao()
        val cleanTitle = title.trim()
        require(cleanTitle.isNotBlank()) { "Note title is required." }
        val now = System.currentTimeMillis()
        val id = dao.insert(
            NoteEntity(
                title = cleanTitle,
                content = content.trim(),
                type = NoteType.MANUAL,
                sourceFile = NOTE_SOURCE_NATIVE_CHAT,
                createdAt = now,
                updatedAt = now,
                isLlmWhitelisted = true
            )
        ).toInt()
        notesChanged?.invoke()
        return "tool: create_note\nnote_id: $id\ntitle: $cleanTitle\nstatus: created"
    }

    private suspend fun updateNote(noteId: Int, title: String?, content: String?, appendText: String?): String {
        val dao = requireNoteDao()
        val existing = requireWhitelistedNote(noteId)
        val replacementTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: existing.title
        val appendContent = appendText?.trim()?.takeIf { it.isNotBlank() }
        val replacementContent = when {
            content != null -> content.trim()
            appendContent != null -> buildString {
                append(existing.content.trimEnd())
                if (isNotEmpty()) append("\n\n")
                append(appendContent)
            }
            else -> existing.content
        }
        require(replacementTitle != existing.title || replacementContent != existing.content) {
            "No title, content, or append_text change was provided."
        }
        dao.update(
            existing.copy(
                title = replacementTitle,
                content = replacementContent,
                updatedAt = System.currentTimeMillis()
            )
        )
        notesChanged?.invoke()
        return buildString {
            appendLine("tool: update_note")
            appendLine("note_id: $noteId")
            appendLine("title: $replacementTitle")
            appendLine("content_chars: ${replacementContent.length}")
            appendLine("preview: ${markdownPreviewForTool(replacementContent, NOTE_PREVIEW_CHARS)}")
            append("status: updated")
        }
    }

    private suspend fun replaceNoteText(
        noteId: Int,
        findText: String,
        replacementText: String,
        replaceAll: Boolean,
        caseSensitive: Boolean
    ): String {
        val dao = requireNoteDao()
        val existing = requireWhitelistedNote(noteId)
        val replacement = replaceNativeNoteText(
            content = existing.content,
            findText = findText,
            replacementText = replacementText,
            replaceAll = replaceAll,
            caseSensitive = caseSensitive
        )
        if (replacement.count > 0) {
            dao.update(
                existing.copy(
                    content = replacement.content,
                    updatedAt = System.currentTimeMillis()
                )
            )
            notesChanged?.invoke()
        }
        return buildString {
            appendLine("tool: replace_note_text")
            appendLine("note_id: $noteId")
            appendLine("replacements: ${replacement.count}")
            if (replacement.count > 0) {
                appendLine("preview: ${markdownPreviewForTool(replacement.content, NOTE_PREVIEW_CHARS)}")
            }
            append("status: ${if (replacement.count > 0) "updated" else "no_match"}")
        }
    }

    private suspend fun createTodoList(title: String, itemsText: String): String {
        val dao = requireNoteDao()
        val cleanTitle = title.trim()
        require(cleanTitle.isNotBlank()) { "Todo list title is required." }
        val items = parseNativeTodoItemsFromToolInput(itemsText)
        require(items.isNotEmpty()) { "At least one todo item is required." }
        val now = System.currentTimeMillis()
        val id = dao.insert(
            NoteEntity(
                title = cleanTitle,
                content = formatNativeTodoItems(items),
                type = NoteType.TODO_LIST,
                sourceFile = NOTE_SOURCE_NATIVE_CHAT,
                createdAt = now,
                updatedAt = now,
                isLlmWhitelisted = true
            )
        ).toInt()
        notesChanged?.invoke()
        return "tool: create_todo_list\nnote_id: $id\ntitle: $cleanTitle\nitems: ${items.size}\nstatus: created"
    }

    private suspend fun addTodoItem(noteId: Int, item: String): String {
        val note = requireTodoNote(noteId)
        val cleanItem = item.trim()
        require(cleanItem.isNotBlank()) { "Todo item text is required." }
        val items = parseNativeTodoItems(note.content).toMutableList()
        items += NativeTodoItem(text = cleanItem, checked = false)
        updateTodoNote(note, items)
        return todoMutationResult(
            tool = TOOL_ADD_TODO_ITEM,
            noteId = noteId,
            status = "added",
            changedIndex = items.size,
            items = items
        )
    }

    private suspend fun updateTodoItem(noteId: Int, itemIndex: Int, item: String): String {
        val note = requireTodoNote(noteId)
        val cleanItem = item.trim()
        require(cleanItem.isNotBlank()) { "Todo item text is required." }
        val items = parseNativeTodoItems(note.content).toMutableList()
        val index = requireTodoIndex(items, itemIndex)
        items[index] = items[index].copy(text = cleanItem)
        updateTodoNote(note, items)
        return todoMutationResult(
            tool = TOOL_UPDATE_TODO_ITEM,
            noteId = noteId,
            status = "updated",
            changedIndex = itemIndex,
            items = items
        )
    }

    private suspend fun removeTodoItem(noteId: Int, itemIndex: Int): String {
        val note = requireTodoNote(noteId)
        val items = parseNativeTodoItems(note.content).toMutableList()
        val index = requireTodoIndex(items, itemIndex)
        val removed = items.removeAt(index)
        updateTodoNote(note, items)
        return todoMutationResult(
            tool = TOOL_REMOVE_TODO_ITEM,
            noteId = noteId,
            status = "removed",
            changedIndex = itemIndex,
            items = items,
            extraLines = listOf("removed: ${removed.text}")
        )
    }

    private suspend fun setTodoItemChecked(noteId: Int, itemIndex: Int, checked: Boolean): String {
        val note = requireTodoNote(noteId)
        val items = parseNativeTodoItems(note.content).toMutableList()
        val index = requireTodoIndex(items, itemIndex)
        items[index] = items[index].copy(checked = checked)
        updateTodoNote(note, items)
        return todoMutationResult(
            tool = TOOL_SET_TODO_ITEM_CHECKED,
            noteId = noteId,
            status = "updated",
            changedIndex = itemIndex,
            items = items,
            extraLines = listOf("checked: $checked")
        )
    }

    private suspend fun generateImage(
        prompt: String,
        negativePrompt: String,
        config: NativeChatToolConfig
    ): NativeChatToolResult {
        val cleanPrompt = prompt.trim()
        require(cleanPrompt.isNotBlank()) { "Image prompt is required." }
        val generator = imageGenerator ?: throw IllegalStateException("Image generation is unavailable.")
        val generated = generator.generateImage(cleanPrompt, negativePrompt.trim(), config).getOrThrow()
        return NativeChatToolResult(
            content = generated.content,
            generatedImagePath = generated.imagePath
        )
    }

    private suspend fun removeImageBackground(
        imagePath: String,
        config: NativeChatToolConfig
    ): NativeChatToolResult {
        val cleanPath = imagePath.trim()
        require(cleanPath.isNotBlank()) { "Image path is required." }
        val remover = backgroundRemover ?: throw IllegalStateException("ONNX background removal is unavailable.")
        val generated = remover.removeBackground(cleanPath, config).getOrThrow()
        return NativeChatToolResult(
            content = generated.content,
            generatedImagePath = generated.imagePath
        )
    }

    private fun requireNoteDao(): NoteDao =
        noteDao ?: throw IllegalStateException("Notes storage is unavailable.")

    private fun requireOrganizerDao(): OrganizerDao =
        organizerDao ?: throw IllegalStateException("Organizer storage is unavailable.")

    private suspend fun scheduleOrganizerAlarm(alarm: OrganizerAlarmEntity) {
        alarmScheduler?.invoke(alarm)
    }

    private fun cancelOrganizerAlarm(alarmId: Long) {
        alarmCanceler?.invoke(alarmId)
    }

    private suspend fun requireWhitelistedNote(noteId: Int): NoteEntity {
        val note = requireNoteDao().getNoteById(noteId)
            ?: throw IllegalArgumentException("No note found with id $noteId.")
        require(note.isLlmWhitelisted) {
            "Note $noteId is not whitelisted for LLM tools. Enable LLM access for this note in the Notes screen first."
        }
        return note
    }

    private suspend fun requireTodoNote(noteId: Int): NoteEntity {
        val note = requireWhitelistedNote(noteId)
        require(note.type == NoteType.TODO_LIST) { "Note $noteId is not a todo-list note." }
        return note
    }

    private suspend fun updateTodoNote(note: NoteEntity, items: List<NativeTodoItem>) {
        requireNoteDao().update(
            note.copy(
                content = formatNativeTodoItems(items),
                updatedAt = System.currentTimeMillis()
            )
        )
        notesChanged?.invoke()
    }

    private fun requireTodoIndex(items: List<NativeTodoItem>, itemIndex: Int): Int {
        require(itemIndex in 1..items.size) {
            "item_index must be between 1 and ${items.size}."
        }
        return itemIndex - 1
    }

    private fun todoMutationResult(
        tool: String,
        noteId: Int,
        status: String,
        changedIndex: Int,
        items: List<NativeTodoItem>,
        extraLines: List<String> = emptyList()
    ): String = buildString {
        appendLine("tool: $tool")
        appendLine("note_id: $noteId")
        appendLine("item_index: $changedIndex")
        extraLines.forEach { appendLine(it) }
        appendLine("todo_items:")
        items.forEachIndexed { index, item ->
            appendLine("${index + 1}. [${if (item.checked) "x" else " "}] ${item.text}")
        }
        append("status: $status")
    }

    private suspend fun listCalendarEvents(
        startText: String?,
        endText: String?,
        query: String?,
        maxResults: Int?
    ): String {
        val dao = requireOrganizerDao()
        val start = parseOptionalOrganizerDateTime(startText, "start_datetime")
        val end = parseOptionalOrganizerDateTime(endText, "end_datetime")
        val normalizedQuery = query?.trim().orEmpty()
        val limit = (maxResults ?: DEFAULT_ORGANIZER_LIST_LIMIT).coerceIn(1, MAX_ORGANIZER_LIST_LIMIT)
        val events = if (start == null && end == null) {
            dao.getAllEventsOnce()
        } else {
            dao.getEventsInRangeOnce(
                rangeStartMillis = (start ?: ZonedDateTime.of(LocalDate.of(1970, 1, 1), LocalTime.MIN, ZoneId.systemDefault())).toInstant().toEpochMilli(),
                rangeEndMillis = (end ?: ZonedDateTime.of(LocalDate.of(2999, 12, 31), LocalTime.MAX, ZoneId.systemDefault())).toInstant().toEpochMilli()
            )
        }.asSequence()
            .filter { event ->
                normalizedQuery.isBlank() ||
                    event.title.contains(normalizedQuery, ignoreCase = true) ||
                    event.description.contains(normalizedQuery, ignoreCase = true) ||
                    event.location.contains(normalizedQuery, ignoreCase = true)
            }
            .take(limit)
            .toList()

        return buildString {
            appendLine("tool: list_calendar_events")
            start?.let { appendLine("range_start: ${it.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}") }
            end?.let { appendLine("range_end: ${it.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}") }
            if (normalizedQuery.isNotBlank()) appendLine("query: $normalizedQuery")
            appendLine("results_returned: ${events.size}")
            appendLine("hint: Use read_calendar_event on a listed event_id before editing or deleting.")
            appendLine()
            events.forEach { event ->
                appendLine(formatCalendarEventForTool(event))
                appendLine()
            }
        }.trimEnd()
    }

    private suspend fun readCalendarEvent(eventId: Long): String {
        val dao = requireOrganizerDao()
        val event = dao.getEventById(eventId) ?: throw IllegalArgumentException("No calendar event found with id $eventId.")
        val alarms = dao.getAlarmsForEventOnce(eventId)
        return buildString {
            appendLine("tool: read_calendar_event")
            appendLine(formatCalendarEventForTool(event))
            if (alarms.isNotEmpty()) {
                appendLine("linked_alarms:")
                alarms.forEach { alarm ->
                    appendLine(formatAlarmForTool(alarm).prependIndent("  "))
                }
            } else {
                appendLine("linked_alarms: none")
            }
        }.trimEnd()
    }

    private suspend fun createCalendarEvent(
        title: String,
        startText: String,
        endText: String?,
        allDayText: String?,
        description: String?,
        location: String?,
        color: String?,
        alarmText: String?,
        alarmOffsetMinutes: Int?,
        canCreateAlarm: Boolean
    ): String {
        val dao = requireOrganizerDao()
        val cleanTitle = title.trim()
        require(cleanTitle.isNotBlank()) { "Event title is required." }
        val start = parseOrganizerDateTime(startText, "start_datetime")
        val end = parseOptionalOrganizerDateTime(endText, "end_datetime")
        require(end == null || !end.toInstant().isBefore(start.toInstant())) { "end_datetime must be after start_datetime." }
        val now = System.currentTimeMillis()
        val eventId = dao.insertEvent(
            OrganizerEventEntity(
                title = cleanTitle,
                description = description?.trim().orEmpty(),
                location = location?.trim().orEmpty(),
                startAtMillis = start.toInstant().toEpochMilli(),
                endAtMillis = end?.toInstant()?.toEpochMilli(),
                allDay = parseOptionalBoolean(allDayText, false),
                timezoneId = start.zone.id,
                colorArgb = parseOptionalToolColor(color),
                createdAt = now,
                updatedAt = now
            )
        )

        val alarmAt = when {
            !alarmText.isNullOrBlank() -> parseOrganizerDateTime(alarmText, "alarm_datetime")
            alarmOffsetMinutes != null -> start.minusMinutes(alarmOffsetMinutes.toLong())
            else -> null
        }
        val alarmId = if (alarmAt != null) {
            require(canCreateAlarm) { "Creating event alarms requires Organizer alarm access and this chat's alarm tool to be enabled." }
            val alarm = OrganizerAlarmEntity(
                eventId = eventId,
                title = cleanTitle,
                message = description?.trim().orEmpty(),
                triggerAtMillis = alarmAt.toInstant().toEpochMilli(),
                timezoneId = alarmAt.zone.id,
                createdAt = now,
                updatedAt = now
            )
            dao.insertAlarm(alarm).also { insertedId ->
                scheduleOrganizerAlarm(alarm.copy(id = insertedId))
            }
        } else {
            null
        }
        organizerChanged?.invoke()

        return buildString {
            appendLine("tool: create_calendar_event")
            appendLine("event_id: $eventId")
            alarmId?.let { appendLine("alarm_id: $it") }
            append("status: created")
        }
    }

    private suspend fun updateCalendarEvent(
        eventId: Long,
        title: String?,
        startText: String?,
        endText: String?,
        allDayText: String?,
        description: String?,
        location: String?,
        color: String?
    ): String {
        val dao = requireOrganizerDao()
        val existing = dao.getEventById(eventId) ?: throw IllegalArgumentException("No calendar event found with id $eventId.")
        val start = parseOptionalOrganizerDateTime(startText, "start_datetime")
        val end = if (endText?.trim() == "") null else parseOptionalOrganizerDateTime(endText, "end_datetime")
        val updatedWithoutTimestamp = existing.copy(
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: existing.title,
            description = description?.trim() ?: existing.description,
            location = location?.trim() ?: existing.location,
            startAtMillis = start?.toInstant()?.toEpochMilli() ?: existing.startAtMillis,
            endAtMillis = if (endText != null) end?.toInstant()?.toEpochMilli() else existing.endAtMillis,
            allDay = allDayText?.let { parseOptionalBoolean(it, existing.allDay) } ?: existing.allDay,
            timezoneId = start?.zone?.id ?: existing.timezoneId,
            colorArgb = if (color != null) parseOptionalToolColor(color) else existing.colorArgb
        )
        require(updatedWithoutTimestamp != existing) { "No event changes were provided." }
        val updated = updatedWithoutTimestamp.copy(updatedAt = System.currentTimeMillis())
        require(updated.endAtMillis == null || updated.endAtMillis >= updated.startAtMillis) {
            "end_datetime must be after start_datetime."
        }
        dao.updateEvent(updated)
        organizerChanged?.invoke()
        return buildString {
            appendLine("tool: update_calendar_event")
            appendLine(formatCalendarEventForTool(updated))
            append("status: updated")
        }
    }

    private suspend fun deleteCalendarEvent(eventId: Long): String {
        val dao = requireOrganizerDao()
        val existing = dao.getEventById(eventId) ?: throw IllegalArgumentException("No calendar event found with id $eventId.")
        dao.getAlarmsForEventOnce(eventId).forEach { alarm ->
            cancelOrganizerAlarm(alarm.id)
        }
        dao.deleteEvent(existing)
        organizerChanged?.invoke()
        return "tool: delete_calendar_event\nevent_id: $eventId\nstatus: deleted"
    }

    private suspend fun listAlarms(
        startText: String?,
        endText: String?,
        query: String?,
        enabledOnly: Boolean,
        maxResults: Int?
    ): String {
        val dao = requireOrganizerDao()
        val start = parseOptionalOrganizerDateTime(startText, "start_datetime")
        val end = parseOptionalOrganizerDateTime(endText, "end_datetime")
        val normalizedQuery = query?.trim().orEmpty()
        val limit = (maxResults ?: DEFAULT_ORGANIZER_LIST_LIMIT).coerceIn(1, MAX_ORGANIZER_LIST_LIMIT)
        val alarms = dao.getAllAlarmsOnce().asSequence()
            .filter { alarm -> start == null || alarm.triggerAtMillis >= start.toInstant().toEpochMilli() }
            .filter { alarm -> end == null || alarm.triggerAtMillis <= end.toInstant().toEpochMilli() }
            .filter { alarm -> !enabledOnly || alarm.enabled }
            .filter { alarm ->
                normalizedQuery.isBlank() ||
                    alarm.title.contains(normalizedQuery, ignoreCase = true) ||
                    alarm.message.contains(normalizedQuery, ignoreCase = true)
            }
            .take(limit)
            .toList()

        return buildString {
            appendLine("tool: list_alarms")
            if (enabledOnly) appendLine("enabled_only: true")
            appendLine("results_returned: ${alarms.size}")
            appendLine("hint: Use read_alarm on a listed alarm_id before editing or deleting.")
            appendLine()
            alarms.forEach { alarm ->
                appendLine(formatAlarmForTool(alarm))
                appendLine()
            }
        }.trimEnd()
    }

    private suspend fun readAlarm(alarmId: Long): String {
        val alarm = requireOrganizerDao().getAlarmById(alarmId)
            ?: throw IllegalArgumentException("No alarm found with id $alarmId.")
        return buildString {
            appendLine("tool: read_alarm")
            appendLine(formatAlarmForTool(alarm))
        }.trimEnd()
    }

    private suspend fun createAlarm(
        title: String,
        triggerText: String,
        message: String?,
        soundEnabled: Boolean,
        eventId: Long?
    ): String {
        val dao = requireOrganizerDao()
        val cleanTitle = title.trim()
        require(cleanTitle.isNotBlank()) { "Alarm title is required." }
        val trigger = parseOrganizerDateTime(triggerText, "trigger_datetime")
        require(trigger.toInstant().toEpochMilli() > System.currentTimeMillis()) { "trigger_datetime must be in the future." }
        if (eventId != null) {
            require(dao.getEventById(eventId) != null) { "No calendar event found with id $eventId." }
        }
        val now = System.currentTimeMillis()
        val alarm = OrganizerAlarmEntity(
            eventId = eventId,
            title = cleanTitle,
            message = message?.trim().orEmpty(),
            triggerAtMillis = trigger.toInstant().toEpochMilli(),
            timezoneId = trigger.zone.id,
            soundEnabled = soundEnabled,
            createdAt = now,
            updatedAt = now
        )
        val id = dao.insertAlarm(alarm)
        scheduleOrganizerAlarm(alarm.copy(id = id))
        organizerChanged?.invoke()
        return "tool: create_alarm\nalarm_id: $id\nstatus: created"
    }

    private suspend fun updateAlarm(
        alarmId: Long,
        title: String?,
        triggerText: String?,
        message: String?,
        soundEnabledText: String?,
        enabledText: String?
    ): String {
        val dao = requireOrganizerDao()
        val existing = dao.getAlarmById(alarmId) ?: throw IllegalArgumentException("No alarm found with id $alarmId.")
        val trigger = parseOptionalOrganizerDateTime(triggerText, "trigger_datetime")
        val nextEnabled = enabledText?.let { parseOptionalBoolean(it, existing.enabled) } ?: existing.enabled
        val updatedWithoutTimestamp = existing.copy(
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: existing.title,
            message = message?.trim() ?: existing.message,
            triggerAtMillis = trigger?.toInstant()?.toEpochMilli() ?: existing.triggerAtMillis,
            timezoneId = trigger?.zone?.id ?: existing.timezoneId,
            soundEnabled = soundEnabledText?.let { parseOptionalBoolean(it, existing.soundEnabled) } ?: existing.soundEnabled,
            enabled = nextEnabled,
            deliveredAt = if (nextEnabled) null else existing.deliveredAt
        )
        require(updatedWithoutTimestamp != existing) { "No alarm changes were provided." }
        val updated = updatedWithoutTimestamp.copy(updatedAt = System.currentTimeMillis())
        dao.updateAlarm(updated)
        if (updated.enabled) {
            scheduleOrganizerAlarm(updated)
        } else {
            cancelOrganizerAlarm(updated.id)
        }
        organizerChanged?.invoke()
        return buildString {
            appendLine("tool: update_alarm")
            appendLine(formatAlarmForTool(updated))
            append("status: updated")
        }
    }

    private suspend fun deleteAlarm(alarmId: Long): String {
        val dao = requireOrganizerDao()
        val existing = dao.getAlarmById(alarmId) ?: throw IllegalArgumentException("No alarm found with id $alarmId.")
        cancelOrganizerAlarm(existing.id)
        dao.deleteAlarm(existing)
        organizerChanged?.invoke()
        return "tool: delete_alarm\nalarm_id: $alarmId\nstatus: deleted"
    }

    private fun toolArg(arguments: Map<String, String>, vararg names: String): String? {
        val normalizedArguments = arguments.entries.associate { (key, value) ->
            normalizeToolArgName(key) to value
        }
        for (name in names) {
            val value = normalizedArguments[normalizeToolArgName(name)]
            if (value != null) return value
        }
        return null
    }

    private fun parseToolStringList(arguments: Map<String, String>, vararg names: String): List<String> {
        val raw = toolArg(arguments, *names)?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()
        if (raw.startsWith("[")) {
            runCatching {
                val array = JSONArray(raw)
                return (0 until array.length())
                    .mapNotNull { index -> array.optString(index).trim().takeIf { it.isNotBlank() } }
                    .distinct()
            }
        }
        return raw
            .split('\n', ';')
            .map { it.trim().trimStart('-', '*').trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalizeToolArgName(name: String): String =
        name.filter { it.isLetterOrDigit() }.lowercase(Locale.US)

    private fun parseRequiredInt(value: String?, name: String): Int =
        parseOptionalInt(value) ?: throw IllegalArgumentException("$name must be a number.")

    private fun parseRequiredLong(value: String?, name: String): Long =
        parseOptionalLong(value) ?: throw IllegalArgumentException("$name must be a number.")

    private fun parseOptionalLong(value: String?): Long? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        trimmed.toLongOrNull()?.let { return it }
        trimmed.toDoubleOrNull()?.let { numeric ->
            val longValue = numeric.toLong()
            if (numeric == longValue.toDouble()) return longValue
        }
        return Regex("""-?\d+""").find(trimmed)?.value?.toLongOrNull()
    }

    private fun parseOptionalInt(value: String?): Int? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        trimmed.toIntOrNull()?.let { return it }
        trimmed.toDoubleOrNull()?.let { numeric ->
            val intValue = numeric.toInt()
            if (numeric == intValue.toDouble()) return intValue
        }
        return Regex("""-?\d+""").find(trimmed)?.value?.toIntOrNull()
    }

    private fun parseRequiredBoolean(value: String?, name: String): Boolean =
        when (value?.trim()?.lowercase(Locale.US)) {
            "true", "yes", "1", "checked", "done", "complete", "completed" -> true
            "false", "no", "0", "unchecked", "undone", "incomplete", "not_done", "not done" -> false
            else -> throw IllegalArgumentException("$name must be true or false.")
        }

    private fun parseOptionalBoolean(value: String?, default: Boolean): Boolean =
        when (value?.trim()?.lowercase(Locale.US)) {
            null, "" -> default
            "true", "yes", "1", "checked", "done", "complete", "completed" -> true
            "false", "no", "0", "unchecked", "undone", "incomplete", "not_done", "not done" -> false
            else -> default
        }

    private fun parseOptionalNoteType(value: String?): NoteType? {
        val normalized = value?.trim()?.lowercase(Locale.US).orEmpty()
        if (normalized.isBlank()) return null
        return when (normalized) {
            "manual", "note", "notes" -> NoteType.MANUAL
            "todo", "todo_list", "todo-list", "checklist" -> NoteType.TODO_LIST
            "transcription", "transcriptions" -> NoteType.TRANSCRIPTION
            "pdf", "pdf_summary", "pdf-summary" -> NoteType.PDF_SUMMARY
            "video", "video_summary", "video-summary" -> NoteType.VIDEO_SUMMARY
            "workflow", "workflows" -> NoteType.WORKFLOW
            else -> throw IllegalArgumentException("Unknown note type '$value'.")
        }
    }

    private fun parseOrganizerDateTime(value: String, name: String): ZonedDateTime {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$name is required." }
        val zone = ZoneId.systemDefault()
        return runCatching { ZonedDateTime.parse(trimmed) }.getOrNull()
            ?: runCatching { Instant.parse(trimmed).atZone(zone) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(trimmed).atZone(zone) }.getOrNull()
            ?: runCatching { LocalDate.parse(trimmed).atStartOfDay(zone) }.getOrNull()
            ?: parseOrganizerDateTimeWithCurrentYear(trimmed, zone)
            ?: throw IllegalArgumentException("$name must be ISO-8601 date/time, for example 2026-04-28T09:00:00 or 2026-04-28T09:00:00-04:00.")
    }

    private fun parseOrganizerDateTimeWithCurrentYear(value: String, zone: ZoneId): ZonedDateTime? {
        val currentYear = LocalDate.now(zone).year
        val normalized = value.trim().replace('/', '-')
        val dateTimeCandidates = listOf(
            "${currentYear}-$normalized",
            "${currentYear}-${normalized.replace(' ', 'T')}"
        ).distinct()
        dateTimeCandidates.forEach { candidate ->
            runCatching { LocalDateTime.parse(candidate).atZone(zone) }.getOrNull()?.let { return it }
        }
        return runCatching { LocalDate.parse("${currentYear}-$normalized").atStartOfDay(zone) }.getOrNull()
    }

    private fun parseOptionalOrganizerDateTime(value: String?, name: String): ZonedDateTime? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return parseOrganizerDateTime(trimmed, name)
    }

    private fun parseOptionalToolColor(value: String?): Long? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val hex = trimmed.removePrefix("#")
        require(hex.length == 6 || hex.length == 8) { "color must be #RRGGBB or #AARRGGBB." }
        return hex.toLong(16).let { raw ->
            if (hex.length == 6) 0xFF000000L or raw else raw
        }
    }

    private fun formatCalendarEventForTool(event: OrganizerEventEntity): String = buildString {
        appendLine("event_id: ${event.id}")
        appendLine("title: ${event.title}")
        appendLine("start_datetime: ${formatOrganizerMillis(event.startAtMillis, event.timezoneId)}")
        appendLine("start_day_of_week: ${formatOrganizerWeekday(event.startAtMillis, event.timezoneId, Locale.ENGLISH)}")
        appendLine("start_day_of_week_es: ${formatOrganizerWeekday(event.startAtMillis, event.timezoneId, Locale.forLanguageTag("es"))}")
        event.endAtMillis?.let {
            appendLine("end_datetime: ${formatOrganizerMillis(it, event.timezoneId)}")
            appendLine("end_day_of_week: ${formatOrganizerWeekday(it, event.timezoneId, Locale.ENGLISH)}")
            appendLine("end_day_of_week_es: ${formatOrganizerWeekday(it, event.timezoneId, Locale.forLanguageTag("es"))}")
        }
        appendLine("all_day: ${event.allDay}")
        if (event.location.isNotBlank()) appendLine("location: ${event.location}")
        if (event.description.isNotBlank()) appendLine("description: ${event.description.limitForTool(700)}")
        event.colorArgb?.let { appendLine("color: #${it.toString(16).uppercase(Locale.US).padStart(8, '0')}") }
        appendLine("updated_at_ms: ${event.updatedAt}")
    }.trimEnd()

    private fun formatAlarmForTool(alarm: OrganizerAlarmEntity): String = buildString {
        appendLine("alarm_id: ${alarm.id}")
        alarm.eventId?.let { appendLine("event_id: $it") }
        appendLine("title: ${alarm.title}")
        if (alarm.message.isNotBlank()) appendLine("message: ${alarm.message.limitForTool(700)}")
        appendLine("trigger_datetime: ${formatOrganizerMillis(alarm.triggerAtMillis, alarm.timezoneId)}")
        appendLine("enabled: ${alarm.enabled}")
        appendLine("sound_enabled: ${alarm.soundEnabled}")
        alarm.deliveredAt?.let { appendLine("delivered_at: ${formatOrganizerMillis(it, alarm.timezoneId)}") }
        appendLine("updated_at_ms: ${alarm.updatedAt}")
    }.trimEnd()

    private fun formatOrganizerMillis(millis: Long, timezoneId: String): String {
        val zone = runCatching { ZoneId.of(timezoneId) }.getOrDefault(ZoneId.systemDefault())
        return Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    private fun formatOrganizerWeekday(millis: Long, timezoneId: String, locale: Locale): String {
        val zone = runCatching { ZoneId.of(timezoneId) }.getOrDefault(ZoneId.systemDefault())
        return Instant.ofEpochMilli(millis)
            .atZone(zone)
            .format(DateTimeFormatter.ofPattern("EEEE", locale))
    }

    companion object {
        const val TOOL_WEB_SEARCH = "web_search"
        const val TOOL_SEARCH_PAGE = "search_page"
        const val TOOL_KIWIX_SEARCH = "kiwix_search"
        const val TOOL_FETCH_URL = "fetch_url"
        const val TOOL_DEEP_RESEARCH = "deep_research"
        const val TOOL_GET_DATETIME = "get_datetime"
        const val TOOL_CALCULATOR = "calculator"
        const val TOOL_LIST_NOTES = "list_notes"
        const val TOOL_READ_NOTE = "read_note"
        const val TOOL_CREATE_NOTE = "create_note"
        const val TOOL_UPDATE_NOTE = "update_note"
        const val TOOL_REPLACE_NOTE_TEXT = "replace_note_text"
        const val TOOL_MODIFY_PINNED_NOTE = "modify_pinned_note"
        const val TOOL_CREATE_TODO_LIST = "create_todo_list"
        const val TOOL_ADD_TODO_ITEM = "add_todo_item"
        const val TOOL_UPDATE_TODO_ITEM = "update_todo_item"
        const val TOOL_REMOVE_TODO_ITEM = "remove_todo_item"
        const val TOOL_SET_TODO_ITEM_CHECKED = "set_todo_item_checked"
        const val TOOL_LIST_CALENDAR_EVENTS = "list_calendar_events"
        const val TOOL_READ_CALENDAR_EVENT = "read_calendar_event"
        const val TOOL_CREATE_CALENDAR_EVENT = "create_calendar_event"
        const val TOOL_UPDATE_CALENDAR_EVENT = "update_calendar_event"
        const val TOOL_DELETE_CALENDAR_EVENT = "delete_calendar_event"
        const val TOOL_LIST_ALARMS = "list_alarms"
        const val TOOL_READ_ALARM = "read_alarm"
        const val TOOL_CREATE_ALARM = "create_alarm"
        const val TOOL_UPDATE_ALARM = "update_alarm"
        const val TOOL_DELETE_ALARM = "delete_alarm"
        private val ORGANIZER_TIME_CONTEXT_TOOLS = setOf(
            TOOL_LIST_CALENDAR_EVENTS,
            TOOL_READ_CALENDAR_EVENT,
            TOOL_CREATE_CALENDAR_EVENT,
            TOOL_UPDATE_CALENDAR_EVENT,
            TOOL_DELETE_CALENDAR_EVENT,
            TOOL_LIST_ALARMS,
            TOOL_READ_ALARM,
            TOOL_CREATE_ALARM,
            TOOL_UPDATE_ALARM,
            TOOL_DELETE_ALARM
        )
        const val TOOL_KB_SEARCH = "kb_search"
        const val TOOL_KB_READ_CHUNK = "kb_read_chunk"
        const val TOOL_KB_READ_SOURCE = "kb_read_source"
        const val TOOL_KB_LIST_SOURCES = "kb_list_sources"
        const val TOOL_GENERATE_IMAGE = "generate_image"
        const val TOOL_REMOVE_IMAGE_BACKGROUND = "remove_image_background"

        private const val USER_AGENT_HEADER = "User-Agent"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        private const val MAX_REDIRECTS = 5
        private const val MAX_RAW_FETCH_CHARS = 100_000
        private const val MAX_CALCULATOR_EXPRESSION_CHARS = 512
        private const val DEFAULT_NOTE_LIST_LIMIT = 20
        private const val MAX_NOTE_LIST_LIMIT = 50
        private const val NOTE_PREVIEW_CHARS = 260
        private const val MAX_NOTE_READ_CHARS = 20_000
        private const val DEFAULT_PAGE_LINK_LIMIT = 20
        private const val MAX_PAGE_LINK_LIMIT = 50
        private const val DEFAULT_PAGE_MATCH_LIMIT = 5
        private const val MAX_PAGE_MATCH_LIMIT = 12
        private const val DEFAULT_ORGANIZER_LIST_LIMIT = 20
        private const val MAX_ORGANIZER_LIST_LIMIT = 50
        private const val NOTE_SOURCE_NATIVE_CHAT = "Native chat"
        private val DUCKDUCKGO_RESULT_PATTERN =
            Regex("""<a[^>]*class=["'][^"']*result__a[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val KIWIX_RESULT_PATTERN =
            Regex("""<a[^>]*href=["'](/content/[^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        fun sourceCitationMarkdown(title: String, url: String): String {
            val cleanUrl = url.trim()
            val label = title.trim().ifBlank { cleanUrl.ifBlank { "Source" } }
                .replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
            return "[$label]($cleanUrl)"
        }

        fun sourceCitationBlock(numberedCitations: List<String>): String {
            val citations = numberedCitations
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (citations.isEmpty()) return ""
            return buildString {
                appendLine("source_citations:")
                citations.forEach { appendLine(it) }
                append("final_answer_requirement: Use the source_citations Markdown links when answering from web, Kiwix, search_page, or fetch_url results. Do not use bare [1] citations unless they are Markdown links.")
            }
        }
    }

    private data class SearchLink(val url: String, val title: String)

    private data class FetchedText(
        val finalUrl: String,
        val contentType: String,
        val redirectsFollowed: Int,
        val text: String,
        val links: List<NativePageLink> = emptyList()
    )
}

internal data class NativePageLink(
    val title: String,
    val url: String
)

internal fun extractNativePageLinksForTool(
    html: String,
    baseUrl: String,
    maxLinks: Int = 120
): List<NativePageLink> {
    if (html.isBlank()) return emptyList()
    return PAGE_LINK_PATTERN.findAll(html)
        .mapNotNull { match ->
            val attributes = match.groupValues[1]
            val href = HREF_ATTRIBUTE_PATTERN.find(attributes)
                ?.groupValues
                ?.getOrNull(2)
                ?.let(::decodeHtmlEntities)
                ?.trim()
                ?: return@mapNotNull null
            if (href.isBlank() || href.startsWith("#")) return@mapNotNull null
            val lowerHref = href.lowercase(Locale.US)
            if (lowerHref.startsWith("javascript:") ||
                lowerHref.startsWith("mailto:") ||
                lowerHref.startsWith("tel:") ||
                lowerHref.startsWith("data:")
            ) {
                return@mapNotNull null
            }

            val resolved = resolvePageUrl(baseUrl, href) ?: return@mapNotNull null
            val scheme = runCatching { URI(resolved).scheme?.lowercase(Locale.US) }.getOrNull()
            if (scheme !in setOf("http", "https")) return@mapNotNull null
            val title = cleanNativePageLinkTitle(
                visibleText = match.groupValues[2],
                attributes = attributes,
                url = resolved
            )
            NativePageLink(title = title, url = resolved)
        }
        .distinctBy { it.url }
        .take(maxLinks.coerceAtLeast(1))
        .toList()
}

internal fun filterNativePageLinksForTool(
    links: List<NativePageLink>,
    query: String,
    maxLinks: Int
): List<NativePageLink> {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) return links.take(maxLinks.coerceAtLeast(1))
    val terms = nativeToolSearchTerms(cleanQuery)
    if (terms.isEmpty()) return links.take(maxLinks.coerceAtLeast(1))
    val exact = cleanQuery.lowercase(Locale.US)
    return links.mapIndexedNotNull { index, link ->
        val title = link.title.lowercase(Locale.US)
        val url = link.url.lowercase(Locale.US)
        val haystack = "$title $url"
        var score = terms.count { haystack.contains(it) } * 10
        if (title.contains(exact)) score += 30
        if (url.contains(exact)) score += 20
        if (score <= 0) null else Triple(score, index, link)
    }
        .sortedWith(compareByDescending<Triple<Int, Int, NativePageLink>> { it.first }.thenBy { it.second })
        .map { it.third }
        .take(maxLinks.coerceAtLeast(1))
}

internal fun findNativePageTextSnippetsForTool(
    text: String,
    query: String,
    maxMatches: Int,
    snippetChars: Int = 260
): List<String> {
    val cleanQuery = query.trim()
    if (text.isBlank() || cleanQuery.isBlank()) return emptyList()
    val normalizedText = text
        .replace(Regex("""[ \t\r\f]+"""), " ")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    val terms = nativeToolSearchTerms(cleanQuery)
    if (terms.isEmpty()) return emptyList()
    val lower = normalizedText.lowercase(Locale.US)
    val indexes = mutableListOf<Int>()
    val exactIndex = lower.indexOf(cleanQuery.lowercase(Locale.US))
    if (exactIndex >= 0) indexes += exactIndex
    terms.forEach { term ->
        var start = 0
        while (indexes.size < maxMatches * 4) {
            val index = lower.indexOf(term, start)
            if (index < 0) break
            indexes += index
            start = index + term.length
        }
    }
    return indexes
        .distinct()
        .sorted()
        .map { index ->
            val half = snippetChars / 2
            val start = (index - half).coerceAtLeast(0)
            val end = (index + half).coerceAtMost(normalizedText.length)
            val prefix = if (start > 0) "..." else ""
            val suffix = if (end < normalizedText.length) "..." else ""
            "$prefix${normalizedText.substring(start, end).trim()}$suffix"
                .replace(Regex("""\s+"""), " ")
        }
        .filter { it.isNotBlank() }
        .distinct()
        .take(maxMatches.coerceAtLeast(1))
}

private fun cleanNativePageLinkTitle(
    visibleText: String,
    attributes: String,
    url: String
): String {
    val visible = decodeHtmlEntities(AgentRuntimeSupport.stripHtmlTags(visibleText))
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (visible.isNotBlank()) return visible.limitForTool(160)

    val attributeTitle = LINK_LABEL_ATTRIBUTE_PATTERN.findAll(attributes)
        .mapNotNull { match -> match.groupValues.getOrNull(2)?.let(::decodeHtmlEntities)?.trim() }
        .firstOrNull { it.isNotBlank() }
    if (!attributeTitle.isNullOrBlank()) return attributeTitle.limitForTool(160)

    val uri = runCatching { URI(url) }.getOrNull()
    return uri?.path
        ?.trim('/')
        ?.substringAfterLast('/')
        ?.replace('-', ' ')
        ?.replace('_', ' ')
        ?.takeIf { it.isNotBlank() }
        ?.limitForTool(160)
        ?: url.limitForTool(160)
}

private fun nativeToolSearchTerms(query: String): List<String> =
    query.lowercase(Locale.US)
        .split(Regex("""[^a-z0-9._/-]+"""))
        .map { it.trim('/', '.', '_', '-') }
        .filter { it.length >= 2 }
        .distinct()

private fun resolvePageUrl(baseUrl: String, relativeOrAbsolute: String): String? =
    runCatching { URL(URL(baseUrl), relativeOrAbsolute).toString() }.getOrNull()

interface NativeChatImageGenerator {
    suspend fun generateImage(
        prompt: String,
        negativePrompt: String,
        config: NativeChatToolConfig
    ): Result<NativeChatGeneratedImage>
}

interface NativeChatBackgroundRemover {
    suspend fun removeBackground(
        imagePath: String,
        config: NativeChatToolConfig
    ): Result<NativeChatBackgroundRemovalImage>
}

internal data class NativeTodoItem(
    val text: String,
    val checked: Boolean = false
)

internal fun parseNativeTodoItems(content: String): List<NativeTodoItem> {
    return content.lines().mapNotNull { line ->
        val match = TODO_MARKDOWN_PATTERN.matchEntire(line.trim()) ?: return@mapNotNull null
        NativeTodoItem(
            text = match.groupValues[2].trim(),
            checked = match.groupValues[1].equals("x", ignoreCase = true)
        )
    }
}

internal fun parseNativeTodoItemsFromToolInput(input: String): List<NativeTodoItem> {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return emptyList()
    if (trimmed.startsWith("[")) {
        runCatching {
            val array = JSONArray(trimmed)
            return (0 until array.length()).mapNotNull { index ->
                when (val item = array.opt(index)) {
                    is JSONObject -> NativeTodoItem(
                        text = item.optString("text", item.optString("item", "")).trim(),
                        checked = item.optBoolean("checked", false)
                    ).takeIf { it.text.isNotBlank() }
                    else -> item?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { NativeTodoItem(text = it) }
                }
            }
        }
    }
    val markdownItems = parseNativeTodoItems(trimmed)
    if (markdownItems.isNotEmpty()) return markdownItems
    return trimmed.lines()
        .map { line ->
            line.trim()
                .removePrefix("-")
                .removePrefix("*")
                .trim()
        }
        .filter { it.isNotBlank() }
        .map { NativeTodoItem(text = it, checked = false) }
}

internal fun formatNativeTodoItems(items: List<NativeTodoItem>): String =
    items.joinToString("\n") { item ->
        "- [${if (item.checked) "x" else " "}] ${item.text.trim()}"
    }

internal data class NativeNoteTextReplacement(
    val content: String,
    val count: Int
)

internal fun replaceNativeNoteText(
    content: String,
    findText: String,
    replacementText: String,
    replaceAll: Boolean,
    caseSensitive: Boolean
): NativeNoteTextReplacement {
    require(findText.isNotEmpty()) { "find_text is required." }
    val ignoreCase = !caseSensitive
    var replacements = 0
    val nextContent = if (replaceAll) {
        content.replace(findText, replacementText, ignoreCase = ignoreCase).also {
            var startIndex = 0
            while (true) {
                val index = content.indexOf(findText, startIndex, ignoreCase = ignoreCase)
                if (index < 0) break
                replacements++
                startIndex = index + findText.length.coerceAtLeast(1)
            }
        }
    } else {
        val index = content.indexOf(findText, ignoreCase = ignoreCase)
        if (index < 0) {
            content
        } else {
            replacements = 1
            content.replaceRange(index, index + findText.length, replacementText)
        }
    }
    return NativeNoteTextReplacement(nextContent, replacements)
}

internal fun markdownPreviewForTool(content: String, maxChars: Int): String =
    AgentRuntimeSupport.stripHtmlTags(content)
        .replace(Regex("""(^|\s)[-*]\s+\[[ xX]\]\s+"""), " ")
        .lineSequence()
        .map { line ->
            line.trim()
                .replace(Regex("""^[-*]\s+\[[ xX]\]\s+"""), "")
                .removePrefix("#")
                .trim()
        }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .limitForTool(maxChars)

internal fun summarizeNativeSearchTextForTool(text: String, maxChars: Int = 700): String {
    val clean = text
        .replace(Regex("""[ \t\r\f]+"""), " ")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filterNot { line ->
            val lower = line.lowercase(Locale.US)
            lower.startsWith("cookie ") ||
                lower.contains("enable javascript") ||
                lower == "menu" ||
                lower == "navigation"
        }
        .joinToString("\n")
        .trim()
    if (clean.isBlank()) return "No readable text found."
    if (clean.startsWith("non_text_content_skipped:") || clean.startsWith("tool_fetch_error:")) {
        return clean.limitForTool(maxChars)
    }

    val paragraphText = clean.lineSequence()
        .map { it.trim() }
        .filter { it.length >= 40 }
        .take(4)
        .joinToString(" ")
        .ifBlank { clean.take(1_200) }

    val sentences = Regex("""(?<=[.!?])\s+""")
        .split(paragraphText)
        .map { it.trim() }
        .filter { it.isNotBlank() }

    val summary = sentences
        .take(3)
        .joinToString(" ")
        .ifBlank { paragraphText }

    return summary.limitForTool(maxChars)
}

private val TODO_MARKDOWN_PATTERN = Regex("""^[-*]\s+\[([ xX])\]\s+(.+)$""")
private val PAGE_LINK_PATTERN =
    Regex("""<a\b([^>]*)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HREF_ATTRIBUTE_PATTERN =
    Regex("""\bhref\s*=\s*(['"])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val LINK_LABEL_ATTRIBUTE_PATTERN =
    Regex("""\b(?:aria-label|title)\s*=\s*(['"])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

private const val MAX_SEARCH_HTML_CHARS = 300_000
private const val MAX_NATIVE_PDF_FETCH_BYTES = 3 * 1024 * 1024
private const val MAX_NATIVE_PDF_TEXT_PAGES = 24

internal fun blockedNativeFetchUrlReason(url: String): String? {
    val uri = runCatching { URI(url.trim()) }.getOrNull()
        ?: return "Invalid URL."
    val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
    if (scheme == "file") return "file:// URLs are blocked."
    if (scheme !in setOf("http", "https")) return "Only http:// and https:// URLs are allowed."

    val host = uri.host?.trim()?.lowercase(Locale.US).orEmpty()
    if (host.isBlank()) return "URL must include a host."
    val normalizedHost = host.removePrefix("[").removeSuffix("]")
    if (normalizedHost == "localhost" || normalizedHost == "::1") return null
    if (host.endsWith(".local")) return ".local hosts are blocked."
    if (normalizedHost in setOf("0.0.0.0", "::")) return "Wildcard local addresses are blocked."

    val literalAddress = parseNativeToolInetAddressIfLiteral(normalizedHost)
    if (literalAddress != null) {
        if (literalAddress.isLoopbackAddress) return null
        if (literalAddress.isAnyLocalAddress ||
            literalAddress.isSiteLocalAddress ||
            literalAddress.isLinkLocalAddress ||
            literalAddress.isMulticastAddress
        ) {
            return "Private, link-local, multicast, or wildcard IP addresses are blocked."
        }
    }
    return null
}

internal fun blockedKiwixBaseUrlReason(url: String): String? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "Invalid Kiwix URL."
    val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
    if (scheme !in setOf("http", "https")) return "Kiwix URL must use http:// or https://."
    if (uri.host.isNullOrBlank()) return "Kiwix URL must include a host."
    return null
}

internal fun evaluateNativeCalculatorExpression(expression: String): Double =
    NativeCalculatorParser(expression).parse()

private fun defaultNativeChatToolClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false)
    .build()

private fun decodeDuckDuckGoUrl(url: String): String {
    if (!url.contains("uddg=")) return decodeHtmlEntities(url)
    return try {
        val encoded = url.substringAfter("uddg=").substringBefore("&")
        URLDecoder.decode(encoded, "UTF-8")
    } catch (_: Exception) {
        decodeHtmlEntities(url)
    }
}

private fun decodeHtmlEntities(text: String): String {
    var decoded = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&nbsp;", " ")

    decoded = Regex("""&#(\d+);""").replace(decoded) { match ->
        match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
    }
    decoded = Regex("""&#x([0-9a-fA-F]+);""").replace(decoded) { match ->
        match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
    }
    return decoded.trim()
}

private fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String =
    URL(URL("${baseUrl.trimEnd('/')}/"), relativeOrAbsolute).toString()

private fun parseNativeToolInetAddressIfLiteral(host: String): InetAddress? {
    val looksLikeIp = host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) || host.contains(':')
    if (!looksLikeIp) return null
    return runCatching { InetAddress.getByName(host) }.getOrNull()
}

private data class LimitedBytes(
    val bytes: ByteArray,
    val truncated: Boolean
)

private fun readLimitedBytes(inputStream: InputStream?, maxBytes: Int): LimitedBytes {
    if (inputStream == null) return LimitedBytes(ByteArray(0), truncated = false)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0
    var truncated = false
    inputStream.use { input ->
        while (total < maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (count <= 0) break
            output.write(buffer, 0, count)
            total += count
        }
        if (total >= maxBytes && input.read() >= 0) {
            truncated = true
        }
    }
    return LimitedBytes(output.toByteArray(), truncated)
}

internal fun extractNativePdfTextFromBytes(pdfBytes: ByteArray, maxChars: Int): String {
    if (pdfBytes.isEmpty()) return ""
    val cappedChars = maxChars.coerceIn(
        NativeChatToolConfig.MIN_FETCH_CHARS,
        NativeChatToolConfig.MAX_FETCH_CHARS
    )
    val doc = PDDocument.load(ByteArrayInputStream(pdfBytes))
    try {
        if (doc.isEncrypted) {
            runCatching { doc.setAllSecurityToBeRemoved(true) }
        }
        val pageCount = doc.numberOfPages
        if (pageCount <= 0) return ""

        val stripper = PDFTextStripper()
        val output = StringBuilder()
        val lastPage = minOf(pageCount, MAX_NATIVE_PDF_TEXT_PAGES)
        for (pageNumber in 1..lastPage) {
            if (output.length >= cappedChars) break
            stripper.startPage = pageNumber
            stripper.endPage = pageNumber
            val pageText = runCatching { stripper.getText(doc) }
                .getOrDefault("")
                .replace(Regex("""[ \t\r\f]+"""), " ")
                .trim()
            if (pageText.isBlank()) continue
            if (output.isNotEmpty()) output.append("\n\n")
            output.append("--- page ")
                .append(pageNumber)
                .append(" of ")
                .append(pageCount)
                .appendLine(" ---")
            output.append(pageText)
        }
        if (pageCount > lastPage && output.length < cappedChars) {
            if (output.isNotEmpty()) output.append("\n\n")
            output.append("[pdf_pages_truncated: read first ")
                .append(lastPage)
                .append(" of ")
                .append(pageCount)
                .append(" pages]")
        }
        return output.toString().limitForTool(cappedChars)
    } finally {
        doc.close()
    }
}

private fun readLimited(reader: Reader, maxChars: Int): String {
    val buffer = CharArray(4096)
    val output = StringBuilder()
    while (output.length < maxChars) {
        val remaining = maxChars - output.length
        val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
        if (count <= 0) break
        output.append(buffer, 0, count)
    }
    return output.toString()
}

internal fun isNativeToolReadableContentType(contentType: String): Boolean {
    val lower = contentType.substringBefore(';').trim().lowercase(Locale.US)
    if (lower.isBlank()) return true
    if (lower.startsWith("text/")) return true
    if (isNativeToolPdfContentType(lower)) return true
    return lower in setOf(
        "application/json",
        "application/ld+json",
        "application/xml",
        "application/xhtml+xml",
        "application/rss+xml",
        "application/atom+xml",
        "application/javascript",
        "application/x-javascript",
        "application/x-www-form-urlencoded"
    )
}

internal fun isNativeToolPdfContentType(contentType: String): Boolean {
    val lower = contentType.substringBefore(';').trim().lowercase(Locale.US)
    return lower in setOf(
        "application/pdf",
        "application/x-pdf",
        "application/acrobat",
        "applications/vnd.pdf",
        "text/pdf",
        "text/x-pdf"
    )
}

internal fun looksLikeNativeNonTextContent(text: String): Boolean {
    val sample = text.take(2048)
    if (sample.trimStart().startsWith("%PDF")) return true
    if (sample.isBlank()) return false
    val suspicious = sample.count { char ->
        char == '\uFFFD' ||
            (char.code in 0..8) ||
            (char.code in 14..31)
    }
    return suspicious > (sample.length / 20).coerceAtLeast(8)
}

internal fun nativeToolTextContentSkippedMessage(contentType: String): String =
    "non_text_content_skipped: Content type '${contentType.ifBlank { "unknown" }}' is not safe readable text, so it was not inserted into chat context."

internal fun nativeToolPdfSkippedMessage(sizeBytes: Long): String {
    val sizeLabel = if (sizeBytes > 0L) "$sizeBytes bytes" else "unknown size"
    return "pdf_skipped: PDF is too large for the on-device tool memory guard ($sizeLabel). Use a narrower source, a smaller PDF, or fetch a specific article/page instead."
}

private fun String.limitForTool(maxChars: Int): String {
    val normalized = replace(Regex("""[ \t\r\f]+"""), " ")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    return if (normalized.length <= maxChars) {
        normalized
    } else {
        normalized.take(maxChars).trimEnd() + "\n[truncated]"
    }
}

private fun formatCalculatorResult(value: Double): String {
    if (!value.isFinite()) return value.toString()
    val asLong = value.toLong()
    return if (value == asLong.toDouble()) asLong.toString() else "%.12g".format(Locale.US, value)
}

private class NativeCalculatorParser(private val input: String) {
    private var index = 0

    fun parse(): Double {
        val result = parseExpression()
        skipWhitespace()
        require(index == input.length) { "Unexpected token at position ${index + 1}." }
        return result
    }

    private fun parseExpression(): Double {
        var value = parseTerm()
        while (true) {
            skipWhitespace()
            value = when {
                consume('+') -> value + parseTerm()
                consume('-') -> value - parseTerm()
                else -> return value
            }
        }
    }

    private fun parseTerm(): Double {
        var value = parsePower()
        while (true) {
            skipWhitespace()
            value = when {
                consume('*') -> value * parsePower()
                consume('/') -> {
                    val divisor = parsePower()
                    require(divisor != 0.0) { "Division by zero." }
                    value / divisor
                }
                consume('%') -> {
                    val divisor = parsePower()
                    require(divisor != 0.0) { "Modulo by zero." }
                    value % divisor
                }
                else -> return value
            }
        }
    }

    private fun parsePower(): Double {
        var value = parseUnary()
        skipWhitespace()
        if (consume('^')) {
            value = value.pow(parsePower())
        }
        return value
    }

    private fun parseUnary(): Double {
        skipWhitespace()
        return when {
            consume('+') -> parseUnary()
            consume('-') -> -parseUnary()
            else -> parsePrimary()
        }
    }

    private fun parsePrimary(): Double {
        skipWhitespace()
        if (consume('(')) {
            val value = parseExpression()
            skipWhitespace()
            require(consume(')')) { "Missing closing parenthesis." }
            return value
        }
        return parseNumber()
    }

    private fun parseNumber(): Double {
        skipWhitespace()
        val start = index
        var sawDigit = false
        while (index < input.length && input[index].isDigit()) {
            sawDigit = true
            index++
        }
        if (index < input.length && input[index] == '.') {
            index++
            while (index < input.length && input[index].isDigit()) {
                sawDigit = true
                index++
            }
        }
        if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
            val exponentStart = index
            index++
            if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
            var exponentDigits = false
            while (index < input.length && input[index].isDigit()) {
                exponentDigits = true
                index++
            }
            if (!exponentDigits) index = exponentStart
        }
        require(sawDigit) { "Expected number at position ${start + 1}." }
        return input.substring(start, index).toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid number at position ${start + 1}.")
    }

    private fun consume(char: Char): Boolean {
        if (index < input.length && input[index] == char) {
            index++
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) index++
    }
}
