package com.example.llamadroid.ui.ai.llama.chat

import com.google.gson.Gson
import androidx.compose.runtime.*
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.service.LITERT_PARAM_MAX_OUTPUT_TOKENS
import com.example.llamadroid.service.LITERT_PARAM_MTP_ENABLED
import com.example.llamadroid.service.NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS
import com.example.llamadroid.service.NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS_ENABLED
import com.example.llamadroid.service.NativeChatImageToolParams
import com.example.llamadroid.service.NativeChatSdImageToolParams
import com.example.llamadroid.service.NativeChatBackgroundRemovalToolParams
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.ui.ai.llama.LlamaChatSamplerValidation
import com.example.llamadroid.ui.ai.llama.LlamaSamplerField
import com.example.llamadroid.data.model.LlamaChatEntity

/**
 * Everything the chat settings sheet edits, as Compose state. Property names match the original
 * inline panel so the section composables read like the old code. The screen owns one instance,
 * calls [loadFrom] when the chat or server changes, and auto-saves through [buildApiParamsJson].
 */
@Stable
internal class ChatSettingsState {
    /** True once [loadFrom] ran for a chat; auto-save must not write before that. */
    var loaded by mutableStateOf(false)
    var activeServer by mutableStateOf<LlamaServerEntity?>(null)

    var liteRtMaxTokens by mutableIntStateOf(LITERT_CHAT_FALLBACK_CONTEXT_MAX)
    var liteRtSystemPrompt by mutableStateOf("")
    var liteRtAccelerator by mutableStateOf(LITERT_BACKEND_GPU)
    var liteRtContextRange: IntRange by mutableStateOf(LITERT_CHAT_MIN_CONTEXT_TOKENS..LITERT_CHAT_FALLBACK_CONTEXT_MAX)
    var liteRtDefaultContext by mutableIntStateOf(LITERT_CHAT_FALLBACK_CONTEXT_MAX)

    var temperature by mutableFloatStateOf(parseParam(null, "temperature", 0.8f))
    var temperatureText by mutableStateOf(formatSamplerText(parseParam(null, "temperature", 0.8f)))
    var topP by mutableFloatStateOf(parseParam(null, "top_p", 0.95f))
    var topPText by mutableStateOf(formatSamplerText(parseParam(null, "top_p", 0.95f)))
    var topK by mutableFloatStateOf(parseParam(null, "top_k", 40f))
    var topKText by mutableStateOf(formatSamplerText(parseParam(null, "top_k", 40f), integer = true))
    var minP by mutableFloatStateOf(parseParam(null, "min_p", 0.05f))
    var minPText by mutableStateOf(formatSamplerText(parseParam(null, "min_p", 0.05f)))
    var repPen by mutableFloatStateOf(parseParam(null, "repeat_penalty", 1.1f))
    var repPenText by mutableStateOf(formatSamplerText(parseParam(null, "repeat_penalty", 1.1f)))
    var samplerErrorField by mutableStateOf<LlamaSamplerField?>(null)
    var samplerErrorMessage by mutableStateOf<String?>(null)
    var enableThinking by mutableStateOf(parseParam(null, "enable_thinking", true))
    var liteRtMtpEnabled by mutableStateOf(parseParam(null, LITERT_PARAM_MTP_ENABLED, false))
    var maxOutputTokensEnabled by mutableStateOf(parseParam(null, NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS_ENABLED, false))
    var maxOutputTokens by mutableIntStateOf( parseParam( null, NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS, parseParam(null, LITERT_PARAM_MAX_OUTPUT_TOKENS, 1024) ).coerceAtLeast(1) )
    var seedText by mutableStateOf(parseOptionalIntParam(null, "seed"))
    var toolsEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_TOOLS_ENABLED, false))
    var webSearchEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_WEB_SEARCH_ENABLED, false))
    var webSearchMaxPages by mutableIntStateOf(parseParam(null, NativeChatToolConfig.KEY_WEB_SEARCH_MAX_PAGES, NativeChatToolConfig.DEFAULT_SEARCH_PAGES))
    var webSearchMaxChars by mutableIntStateOf(parseParam(null, NativeChatToolConfig.KEY_WEB_SEARCH_MAX_CHARS, NativeChatToolConfig.DEFAULT_PAGE_CHARS))
    var kiwixSearchEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_KIWIX_SEARCH_ENABLED, false))
    var kiwixServerUrl by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_KIWIX_SERVER_URL, NativeChatToolConfig.DEFAULT_KIWIX_URL))
    var kiwixMaxPages by mutableIntStateOf(parseParam(null, NativeChatToolConfig.KEY_KIWIX_MAX_PAGES, NativeChatToolConfig.DEFAULT_SEARCH_PAGES))
    var kiwixMaxChars by mutableIntStateOf(parseParam(null, NativeChatToolConfig.KEY_KIWIX_MAX_CHARS, NativeChatToolConfig.DEFAULT_PAGE_CHARS))
    var fetchUrlEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_FETCH_URL_ENABLED, false))
    var fetchUrlMaxChars by mutableIntStateOf(parseParam(null, NativeChatToolConfig.KEY_FETCH_URL_MAX_CHARS, NativeChatToolConfig.DEFAULT_FETCH_CHARS))
    var deepResearchEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_DEEP_RESEARCH_ENABLED, false))
    var deepResearchImportIntoSelectedKbEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_DEEP_RESEARCH_IMPORT_SELECTED_KB_ENABLED, false))
    var deepResearchSourceLimit by mutableIntStateOf(parseParam(null, NativeChatToolConfig.KEY_DEEP_RESEARCH_SOURCE_LIMIT, NativeChatToolConfig.DEFAULT_DEEP_RESEARCH_SOURCE_LIMIT))
    var dateTimeEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_DATETIME_ENABLED, true))
    var calculatorEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_CALCULATOR_ENABLED, true))
    var noteToolsEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_NOTE_TOOLS_ENABLED, false))
    var todoToolsEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_TODO_TOOLS_ENABLED, false))
    var calendarToolsEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_CALENDAR_TOOLS_ENABLED, false))
    var alarmToolsEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_ALARM_TOOLS_ENABLED, false))
    var fileToolsEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_FILE_TOOLS_ENABLED, false))
    var customToolsEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_CUSTOM_TOOLS_ENABLED, false))
    var knowledgeBaseEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_KNOWLEDGE_BASE_ENABLED, false))
    var knowledgeBaseAutoContextEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_KNOWLEDGE_AUTO_CONTEXT_ENABLED, false))
    var selectedKnowledgeBaseIds by mutableStateOf(NativeChatToolConfig.fromApiParams(null).selectedKnowledgeBaseIds)
    var chatDocumentKnowledgeBaseId by mutableStateOf(NativeChatToolConfig.fromApiParams(null).chatDocumentKnowledgeBaseId)
    var knowledgeBaseMaxResults by mutableIntStateOf(parseParam(null, NativeChatToolConfig.KEY_KNOWLEDGE_MAX_RESULTS, NativeChatToolConfig.DEFAULT_KB_RESULTS))
    var imageGenerationEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_IMAGE_GENERATION_ENABLED, false))
    var imageIterationEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_IMAGE_ITERATION_ENABLED, false))
    var backgroundRemovalEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_BGR_ENABLED, false))
    var assistantTtsEnabled by mutableStateOf(parseParam(null, NativeChatToolConfig.KEY_ASSISTANT_TTS_ENABLED, false))
    var assistantTtsLanguage by mutableStateOf(NativeChatToolConfig.fromApiParams(null).assistantTtsLanguage)
    var assistantTtsVoiceName by mutableStateOf(NativeChatToolConfig.fromApiParams(null).assistantTtsVoiceName.orEmpty())
    var assistantTtsSpeed by mutableFloatStateOf(NativeChatToolConfig.fromApiParams(null).assistantTtsSpeed)
    var assistantTtsSteps by mutableIntStateOf(NativeChatToolConfig.fromApiParams(null).assistantTtsTotalSteps)
    var callSilenceAfterSpeechSeconds by mutableIntStateOf(NativeChatToolConfig.fromApiParams(null).callSilenceAfterSpeechSeconds)
    var callNoSpeechTimeoutSeconds by mutableIntStateOf(NativeChatToolConfig.fromApiParams(null).callNoSpeechTimeoutSeconds)
    var maxToolRounds by mutableIntStateOf(parseParam(null, NativeChatToolConfig.KEY_MAX_TOOL_ROUNDS, NativeChatToolConfig.DEFAULT_TOOL_ROUNDS))
    var imageToolModel by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.model.orEmpty())
    var imageToolEngine by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.engine)
    var imageToolWidth by mutableIntStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.width)
    var imageToolHeight by mutableIntStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.height)
    var imageToolSteps by mutableIntStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.steps)
    var imageToolCfg by mutableFloatStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.cfgScale)
    var imageToolSeed by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.seed)
    var imageToolNegativePrompt by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.negativePrompt)
    var imageToolBackend by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.backend)
    var imageToolRuntimeThreads by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.runtimeThreads)
    var imageToolGraphOpt by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.graphOptimizationLevel)
    var imageToolUnetBackend by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.unetBackendOverride)
    var imageToolVaeDecoderBackend by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.vaeDecoderBackendOverride)
    var imageToolVaeEncoderBackend by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.vaeEncoderBackendOverride)
    var imageToolIntraThreads by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.intraOpThreads)
    var imageToolInterThreads by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.interOpThreads)
    var imageToolExecutionMode by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.executionMode)
    var imageToolMemoryPattern by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.memoryPatternOptimization)
    var imageToolCpuArena by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.cpuArenaAllocator)
    var imageToolNnapiCpuDisabled by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.nnapiCpuDisabled)
    var imageToolNnapiFp16 by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.nnapiUseFp16)
    var imageToolSdModel by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.model.orEmpty())
    var imageToolSdVae by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.vaePath.orEmpty())
    var imageToolSdTae by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.taePath.orEmpty())
    var imageToolSdClipL by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.clipLPath.orEmpty())
    var imageToolSdClipG by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.clipGPath.orEmpty())
    var imageToolSdT5xxl by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.t5xxlPath.orEmpty())
    var imageToolSdLlm by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.llmPath.orEmpty())
    var imageToolSdLlmVision by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.llmVisionPath.orEmpty())
    var imageToolSdPhotoMaker by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.photoMakerPath.orEmpty())
    var imageToolSdWidth by mutableIntStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.width)
    var imageToolSdHeight by mutableIntStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.height)
    var imageToolSdSteps by mutableIntStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.steps)
    var imageToolSdCfg by mutableFloatStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.cfgScale)
    var imageToolSdSampler by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.sampler)
    var imageToolSdSeed by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.seed)
    var imageToolSdNegativePrompt by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.negativePrompt)
    var imageToolSdThreads by mutableIntStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.threads)
    var imageToolSdFlowShift by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.flowShift)
    var imageToolSdDiffusionFa by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.diffusionFa)
    var imageToolSdMmap by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.mmap)
    var imageToolSdVaeConvDirect by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.vaeConvDirect)
    var imageToolSdQwenZeroCondT by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.qwenImageZeroCondT)
    var imageToolSdChromaDisableDitMask by mutableStateOf(NativeChatToolConfig.fromApiParams(null).imageParams.sdParams.chromaDisableDitMask)
    var bgrToolModel by mutableStateOf(NativeChatToolConfig.fromApiParams(null).backgroundRemovalParams.model.orEmpty())
    var bgrToolBackend by mutableStateOf(NativeChatToolConfig.fromApiParams(null).backgroundRemovalParams.backend)
    var bgrToolRuntimeThreads by mutableStateOf(NativeChatToolConfig.fromApiParams(null).backgroundRemovalParams.runtimeThreads)
    var bgrToolGraphOpt by mutableStateOf(NativeChatToolConfig.fromApiParams(null).backgroundRemovalParams.graphOptimizationLevel)
    var bgrToolAlphaThreshold by mutableFloatStateOf(NativeChatToolConfig.fromApiParams(null).backgroundRemovalParams.alphaThreshold)
    var bgrToolFeatherRadius by mutableIntStateOf(NativeChatToolConfig.fromApiParams(null).backgroundRemovalParams.featherRadius)
    var bgrToolMaskSoftness by mutableFloatStateOf(NativeChatToolConfig.fromApiParams(null).backgroundRemovalParams.maskSoftness)
    var bgrToolMaskContrast by mutableFloatStateOf(NativeChatToolConfig.fromApiParams(null).backgroundRemovalParams.maskContrast)
    var bgrToolExportMask by mutableStateOf(NativeChatToolConfig.fromApiParams(null).backgroundRemovalParams.exportMask)
    var bgrToolResizeBeforeProcessing by mutableStateOf(NativeChatToolConfig.fromApiParams(null).backgroundRemovalParams.resizeBeforeProcessing)
    var bgrToolResizeMaxEdge by mutableIntStateOf(NativeChatToolConfig.fromApiParams(null).backgroundRemovalParams.resizeMaxEdge)

    // Server gating ------------------------------------------------------------------------
    private val activeServerToolDefaults by derivedStateOf {
        NativeChatToolConfig.fromApiParams(activeServer?.defaultApiParams)
    }
    val activeServerIsLiteRt get() = activeServer?.isLiteRtEngine() == true
    val serverToolGate get() = if (activeServerIsLiteRt) NativeChatToolConfig.liteRtToolDefaults() else activeServerToolDefaults
    val serverAllowsTools get() = serverToolGate.toolsEnabled
    val serverAllowsWebSearch get() = serverAllowsTools && serverToolGate.webSearchEnabled
    val serverAllowsKiwixSearch get() = serverAllowsTools && serverToolGate.kiwixSearchEnabled
    val serverAllowsFetchUrl get() = serverAllowsTools && serverToolGate.fetchUrlEnabled
    val serverAllowsDeepResearch get() = serverAllowsTools && serverToolGate.deepResearchEnabled
    val serverAllowsDeepResearchSelectedKbImport get() = serverAllowsDeepResearch && serverToolGate.deepResearchImportIntoSelectedKbEnabled
    val serverAllowsDateTime get() = serverAllowsTools && serverToolGate.dateTimeEnabled
    val serverAllowsCalculator get() = serverAllowsTools && serverToolGate.calculatorEnabled
    val serverAllowsNotes get() = serverAllowsTools && serverToolGate.noteToolsEnabled
    val serverAllowsTodoLists get() = serverAllowsTools && serverToolGate.todoToolsEnabled
    val serverAllowsKnowledgeBases get() = serverAllowsTools && serverToolGate.knowledgeBaseEnabled
    val serverAllowsKnowledgeAutoContext get() = serverAllowsKnowledgeBases && serverToolGate.knowledgeBaseAutoContextEnabled
    val serverAllowsCalendar get() = serverAllowsTools && serverToolGate.calendarToolsEnabled
    val serverAllowsAlarms get() = serverAllowsTools && serverToolGate.alarmToolsEnabled
    val serverAllowsImageGeneration get() = serverAllowsTools && serverToolGate.imageGenerationEnabled
    val serverAllowsImageIteration get() = serverAllowsImageGeneration && serverToolGate.imageIterationEnabled
    val serverAllowsBackgroundRemoval get() = serverAllowsTools && serverToolGate.backgroundRemovalEnabled
    val chatDocumentForcesKnowledgeTools get() = chatDocumentKnowledgeBaseId != null && serverAllowsKnowledgeBases
    val chatToolsEnabledByUser get() = toolsEnabled && serverAllowsTools
    val effectiveToolsEnabled get() = chatToolsEnabledByUser || chatDocumentForcesKnowledgeTools
    val effectiveWebSearchEnabled get() = chatToolsEnabledByUser && webSearchEnabled && serverAllowsWebSearch
    val effectiveKiwixSearchEnabled get() = chatToolsEnabledByUser && kiwixSearchEnabled && serverAllowsKiwixSearch
    val effectiveFetchUrlEnabled get() = chatToolsEnabledByUser && fetchUrlEnabled && serverAllowsFetchUrl
    val effectiveDeepResearchEnabled get() = chatToolsEnabledByUser && deepResearchEnabled && serverAllowsDeepResearch
    val effectiveDeepResearchImportIntoSelectedKbEnabled get() = effectiveDeepResearchEnabled && deepResearchImportIntoSelectedKbEnabled && serverAllowsDeepResearchSelectedKbImport
    val effectiveDateTimeEnabled get() = chatToolsEnabledByUser && dateTimeEnabled && serverAllowsDateTime
    val effectiveCalculatorEnabled get() = chatToolsEnabledByUser && calculatorEnabled && serverAllowsCalculator
    val effectiveNoteToolsEnabled get() = chatToolsEnabledByUser && noteToolsEnabled && serverAllowsNotes
    val effectiveTodoToolsEnabled get() = chatToolsEnabledByUser && todoToolsEnabled && serverAllowsTodoLists
    val effectiveKnowledgeBaseEnabled get() = chatDocumentForcesKnowledgeTools || (chatToolsEnabledByUser && knowledgeBaseEnabled && serverAllowsKnowledgeBases)
    val effectiveKnowledgeBaseAutoContextEnabled get() = chatDocumentForcesKnowledgeTools || (chatToolsEnabledByUser && knowledgeBaseAutoContextEnabled && serverAllowsKnowledgeAutoContext)
    val effectiveCalendarToolsEnabled get() = chatToolsEnabledByUser && calendarToolsEnabled && serverAllowsCalendar
    val effectiveAlarmToolsEnabled get() = chatToolsEnabledByUser && alarmToolsEnabled && serverAllowsAlarms
    val effectiveImageGenerationEnabled get() = chatToolsEnabledByUser && imageGenerationEnabled && serverAllowsImageGeneration
    val effectiveImageIterationEnabled get() = chatToolsEnabledByUser && imageIterationEnabled && serverAllowsImageIteration
    val effectiveBackgroundRemovalEnabled get() = chatToolsEnabledByUser && backgroundRemovalEnabled && serverAllowsBackgroundRemoval

    /** Number of primary tools the user has switched on, and how many exist. */
    val toolsSummary: ChatToolsSummary
        get() = ChatToolsSummary.of(
            effectiveWebSearchEnabled,
            effectiveKiwixSearchEnabled,
            effectiveFetchUrlEnabled,
            effectiveDeepResearchEnabled,
            effectiveDateTimeEnabled,
            effectiveCalculatorEnabled,
            effectiveNoteToolsEnabled,
            effectiveTodoToolsEnabled,
            effectiveKnowledgeBaseEnabled,
            effectiveCalendarToolsEnabled,
            effectiveAlarmToolsEnabled,
            fileToolsEnabled,
            customToolsEnabled,
            effectiveImageGenerationEnabled,
            effectiveBackgroundRemovalEnabled
        )

    /** Replace every field from the persisted chat/server (the old "Cancel" path, now only used on load). */
    fun loadFrom(
        chat: LlamaChatEntity?,
        server: LlamaServerEntity?,
        liteRtDefaultContext: Int,
        liteRtContextRange: IntRange
    ) {
        activeServer = server
        this.liteRtDefaultContext = liteRtDefaultContext
        this.liteRtContextRange = liteRtContextRange
        val params = chat?.apiParams
        temperature = parseParam(params, "temperature", 0.8f)
        temperatureText = formatSamplerText(temperature)
        topP = parseParam(params, "top_p", 0.95f)
        topPText = formatSamplerText(topP)
        topK = parseParam(params, "top_k", 40f)
        topKText = formatSamplerText(topK, integer = true)
        minP = parseParam(params, "min_p", 0.05f)
        minPText = formatSamplerText(minP)
        repPen = parseParam(params, "repeat_penalty", 1.1f)
        repPenText = formatSamplerText(repPen)
        samplerErrorField = null
        samplerErrorMessage = null
        enableThinking = parseParam(params, "enable_thinking", true)
        liteRtMtpEnabled = parseParam(params, LITERT_PARAM_MTP_ENABLED, false)
        maxOutputTokensEnabled = parseParam(params, NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS_ENABLED, false)
        maxOutputTokens = parseParam(
            params,
            NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS,
            parseParam(params, LITERT_PARAM_MAX_OUTPUT_TOKENS, 1024)
        ).coerceAtLeast(1)
        seedText = parseOptionalIntParam(params, "seed")
        enableThinking = parseParam(params, "enable_thinking", true)
        liteRtMaxTokens = (chat?.contextSize?.takeIf { it > 0 } ?: liteRtDefaultContext)
            .coerceIn(liteRtContextRange)
        liteRtSystemPrompt = chat?.systemPrompt.orEmpty()
        liteRtAccelerator = if (normalizeLiteRtBackend(server?.liteRtBackend) == LITERT_BACKEND_CPU) {
            LITERT_BACKEND_CPU
        } else {
            LITERT_BACKEND_GPU
        }
        toolsEnabled = parseParam(params, NativeChatToolConfig.KEY_TOOLS_ENABLED, false)
        webSearchEnabled = parseParam(params, NativeChatToolConfig.KEY_WEB_SEARCH_ENABLED, false)
        webSearchMaxPages = parseParam(params, NativeChatToolConfig.KEY_WEB_SEARCH_MAX_PAGES, NativeChatToolConfig.DEFAULT_SEARCH_PAGES)
        webSearchMaxChars = parseParam(params, NativeChatToolConfig.KEY_WEB_SEARCH_MAX_CHARS, NativeChatToolConfig.DEFAULT_PAGE_CHARS)
        kiwixSearchEnabled = parseParam(params, NativeChatToolConfig.KEY_KIWIX_SEARCH_ENABLED, false)
        kiwixServerUrl = parseParam(params, NativeChatToolConfig.KEY_KIWIX_SERVER_URL, NativeChatToolConfig.DEFAULT_KIWIX_URL)
        kiwixMaxPages = parseParam(params, NativeChatToolConfig.KEY_KIWIX_MAX_PAGES, NativeChatToolConfig.DEFAULT_SEARCH_PAGES)
        kiwixMaxChars = parseParam(params, NativeChatToolConfig.KEY_KIWIX_MAX_CHARS, NativeChatToolConfig.DEFAULT_PAGE_CHARS)
        fetchUrlEnabled = parseParam(params, NativeChatToolConfig.KEY_FETCH_URL_ENABLED, false)
        fetchUrlMaxChars = parseParam(params, NativeChatToolConfig.KEY_FETCH_URL_MAX_CHARS, NativeChatToolConfig.DEFAULT_FETCH_CHARS)
        deepResearchEnabled = parseParam(params, NativeChatToolConfig.KEY_DEEP_RESEARCH_ENABLED, false)
        deepResearchImportIntoSelectedKbEnabled = parseParam(params, NativeChatToolConfig.KEY_DEEP_RESEARCH_IMPORT_SELECTED_KB_ENABLED, false)
        deepResearchSourceLimit = parseParam(params, NativeChatToolConfig.KEY_DEEP_RESEARCH_SOURCE_LIMIT, NativeChatToolConfig.DEFAULT_DEEP_RESEARCH_SOURCE_LIMIT)
        dateTimeEnabled = parseParam(params, NativeChatToolConfig.KEY_DATETIME_ENABLED, true)
        calculatorEnabled = parseParam(params, NativeChatToolConfig.KEY_CALCULATOR_ENABLED, true)
        noteToolsEnabled = parseParam(params, NativeChatToolConfig.KEY_NOTE_TOOLS_ENABLED, false)
        todoToolsEnabled = parseParam(params, NativeChatToolConfig.KEY_TODO_TOOLS_ENABLED, false)
        calendarToolsEnabled = parseParam(params, NativeChatToolConfig.KEY_CALENDAR_TOOLS_ENABLED, false)
        alarmToolsEnabled = parseParam(params, NativeChatToolConfig.KEY_ALARM_TOOLS_ENABLED, false)
        fileToolsEnabled = parseParam(params, NativeChatToolConfig.KEY_FILE_TOOLS_ENABLED, false)
        customToolsEnabled = parseParam(params, NativeChatToolConfig.KEY_CUSTOM_TOOLS_ENABLED, false)
        knowledgeBaseEnabled = parseParam(params, NativeChatToolConfig.KEY_KNOWLEDGE_BASE_ENABLED, false)
        knowledgeBaseAutoContextEnabled = parseParam(params, NativeChatToolConfig.KEY_KNOWLEDGE_AUTO_CONTEXT_ENABLED, false)
        selectedKnowledgeBaseIds = NativeChatToolConfig.fromApiParams(params).selectedKnowledgeBaseIds
        chatDocumentKnowledgeBaseId = NativeChatToolConfig.fromApiParams(params).chatDocumentKnowledgeBaseId
        knowledgeBaseMaxResults = parseParam(params, NativeChatToolConfig.KEY_KNOWLEDGE_MAX_RESULTS, NativeChatToolConfig.DEFAULT_KB_RESULTS)
        imageGenerationEnabled = parseParam(params, NativeChatToolConfig.KEY_IMAGE_GENERATION_ENABLED, false)
        imageIterationEnabled = parseParam(params, NativeChatToolConfig.KEY_IMAGE_ITERATION_ENABLED, false)
        backgroundRemovalEnabled = parseParam(params, NativeChatToolConfig.KEY_BGR_ENABLED, false)
        assistantTtsEnabled = parseParam(params, NativeChatToolConfig.KEY_ASSISTANT_TTS_ENABLED, false)
        assistantTtsLanguage = NativeChatToolConfig.fromApiParams(params).assistantTtsLanguage
        assistantTtsVoiceName = NativeChatToolConfig.fromApiParams(params).assistantTtsVoiceName.orEmpty()
        assistantTtsSpeed = NativeChatToolConfig.fromApiParams(params).assistantTtsSpeed
        assistantTtsSteps = NativeChatToolConfig.fromApiParams(params).assistantTtsTotalSteps
        callSilenceAfterSpeechSeconds = NativeChatToolConfig.fromApiParams(params).callSilenceAfterSpeechSeconds
        callNoSpeechTimeoutSeconds = NativeChatToolConfig.fromApiParams(params).callNoSpeechTimeoutSeconds
        maxToolRounds = parseParam(params, NativeChatToolConfig.KEY_MAX_TOOL_ROUNDS, NativeChatToolConfig.DEFAULT_TOOL_ROUNDS)
        val imageParams = NativeChatToolConfig.fromApiParams(params).imageParams
        imageToolEngine = imageParams.engine
        imageToolModel = imageParams.model.orEmpty()
        imageToolWidth = imageParams.width
        imageToolHeight = imageParams.height
        imageToolSteps = imageParams.steps
        imageToolCfg = imageParams.cfgScale
        imageToolSeed = imageParams.seed
        imageToolNegativePrompt = imageParams.negativePrompt
        imageToolBackend = imageParams.backend
        imageToolRuntimeThreads = imageParams.runtimeThreads
        imageToolGraphOpt = imageParams.graphOptimizationLevel
        imageToolUnetBackend = imageParams.unetBackendOverride
        imageToolVaeDecoderBackend = imageParams.vaeDecoderBackendOverride
        imageToolVaeEncoderBackend = imageParams.vaeEncoderBackendOverride
        imageToolIntraThreads = imageParams.intraOpThreads
        imageToolInterThreads = imageParams.interOpThreads
        imageToolExecutionMode = imageParams.executionMode
        imageToolMemoryPattern = imageParams.memoryPatternOptimization
        imageToolCpuArena = imageParams.cpuArenaAllocator
        imageToolNnapiCpuDisabled = imageParams.nnapiCpuDisabled
        imageToolNnapiFp16 = imageParams.nnapiUseFp16
        val sdParams = imageParams.sdParams
        imageToolSdModel = sdParams.model.orEmpty()
        imageToolSdVae = sdParams.vaePath.orEmpty()
        imageToolSdTae = sdParams.taePath.orEmpty()
        imageToolSdClipL = sdParams.clipLPath.orEmpty()
        imageToolSdClipG = sdParams.clipGPath.orEmpty()
        imageToolSdT5xxl = sdParams.t5xxlPath.orEmpty()
        imageToolSdLlm = sdParams.llmPath.orEmpty()
        imageToolSdLlmVision = sdParams.llmVisionPath.orEmpty()
        imageToolSdPhotoMaker = sdParams.photoMakerPath.orEmpty()
        imageToolSdWidth = sdParams.width
        imageToolSdHeight = sdParams.height
        imageToolSdSteps = sdParams.steps
        imageToolSdCfg = sdParams.cfgScale
        imageToolSdSampler = sdParams.sampler
        imageToolSdSeed = sdParams.seed
        imageToolSdNegativePrompt = sdParams.negativePrompt
        imageToolSdThreads = sdParams.threads
        imageToolSdFlowShift = sdParams.flowShift
        imageToolSdDiffusionFa = sdParams.diffusionFa
        imageToolSdMmap = sdParams.mmap
        imageToolSdVaeConvDirect = sdParams.vaeConvDirect
        imageToolSdQwenZeroCondT = sdParams.qwenImageZeroCondT
        imageToolSdChromaDisableDitMask = sdParams.chromaDisableDitMask
        val bgrParams = NativeChatToolConfig.fromApiParams(params).backgroundRemovalParams
        bgrToolModel = bgrParams.model.orEmpty()
        bgrToolBackend = bgrParams.backend
        bgrToolRuntimeThreads = bgrParams.runtimeThreads
        bgrToolGraphOpt = bgrParams.graphOptimizationLevel
        bgrToolAlphaThreshold = bgrParams.alphaThreshold
        bgrToolFeatherRadius = bgrParams.featherRadius
        bgrToolMaskSoftness = bgrParams.maskSoftness
        bgrToolMaskContrast = bgrParams.maskContrast
        bgrToolExportMask = bgrParams.exportMask
        bgrToolResizeBeforeProcessing = bgrParams.resizeBeforeProcessing
        bgrToolResizeMaxEdge = bgrParams.resizeMaxEdge
        loaded = chat != null
    }

    /** Validates a sampler text field and records an inline error; invalid text is never saved. */
    fun updateSamplerText(
        field: LlamaSamplerField,
        text: String,
        liteRt: Boolean,
        invalidNumber: String,
        invalidRangeFormat: String,
        onValid: (Float) -> Unit
    ) {
        val value = LlamaChatSamplerValidation.parse(text, field, liteRt)
        samplerErrorField = field
        samplerErrorMessage = when {
            text.isBlank() -> invalidNumber
            !LlamaChatSamplerValidation.isNumeric(text) -> invalidNumber
            value == null -> formatLlamaChatString(
                invalidRangeFormat,
                LlamaChatSamplerValidation.rangeText(field, liteRt)
            )
            else -> null
        }
        if (value != null) {
            samplerErrorField = null
            onValid(value)
        }
    }

    /** The chat-level tool configuration as persisted in the chat's API params. */
    fun toToolConfig(
        nextToolsEnabled: Boolean = toolsEnabled,
        nextKnowledgeBaseEnabled: Boolean = knowledgeBaseEnabled,
        nextKnowledgeBaseAutoContextEnabled: Boolean = knowledgeBaseAutoContextEnabled,
        chatDocumentKnowledgeBaseIdOverride: Long? = chatDocumentKnowledgeBaseId
    ): NativeChatToolConfig = NativeChatToolConfig(
            toolsEnabled = nextToolsEnabled,
            webSearchEnabled = webSearchEnabled,
            webSearchMaxPages = webSearchMaxPages,
            webSearchMaxChars = webSearchMaxChars,
            kiwixSearchEnabled = kiwixSearchEnabled,
            kiwixServerUrl = kiwixServerUrl,
            kiwixMaxPages = kiwixMaxPages,
            kiwixMaxChars = kiwixMaxChars,
            fetchUrlEnabled = fetchUrlEnabled,
            fetchUrlMaxChars = fetchUrlMaxChars,
            deepResearchEnabled = deepResearchEnabled,
            deepResearchImportIntoSelectedKbEnabled = deepResearchImportIntoSelectedKbEnabled,
            deepResearchSourceLimit = deepResearchSourceLimit,
            dateTimeEnabled = dateTimeEnabled,
            calculatorEnabled = calculatorEnabled,
            noteToolsEnabled = noteToolsEnabled,
            todoToolsEnabled = todoToolsEnabled,
            calendarToolsEnabled = calendarToolsEnabled,
            alarmToolsEnabled = alarmToolsEnabled,
            fileToolsEnabled = fileToolsEnabled,
            customToolsEnabled = customToolsEnabled,
            knowledgeBaseEnabled = nextKnowledgeBaseEnabled,
            knowledgeBaseAutoContextEnabled = nextKnowledgeBaseAutoContextEnabled,
            selectedKnowledgeBaseIds = selectedKnowledgeBaseIds,
            chatDocumentKnowledgeBaseId = chatDocumentKnowledgeBaseIdOverride,
            knowledgeBaseMaxResults = knowledgeBaseMaxResults,
            imageGenerationEnabled = imageGenerationEnabled,
            imageIterationEnabled = imageIterationEnabled,
            backgroundRemovalEnabled = backgroundRemovalEnabled,
            assistantTtsEnabled = assistantTtsEnabled,
            assistantTtsLanguage = assistantTtsLanguage,
            assistantTtsVoiceName = assistantTtsVoiceName.takeIf { it.isNotBlank() },
            assistantTtsTotalSteps = assistantTtsSteps,
            assistantTtsSpeed = assistantTtsSpeed,
            callSilenceAfterSpeechSeconds = callSilenceAfterSpeechSeconds,
            callNoSpeechTimeoutSeconds = callNoSpeechTimeoutSeconds,
            imageParams = NativeChatImageToolParams(
                engine = imageToolEngine,
                model = imageToolModel.takeIf { it.isNotBlank() },
                width = imageToolWidth,
                height = imageToolHeight,
                steps = imageToolSteps,
                cfgScale = imageToolCfg,
                seed = imageToolSeed,
                negativePrompt = imageToolNegativePrompt,
                backend = imageToolBackend,
                runtimeThreads = imageToolRuntimeThreads,
                graphOptimizationLevel = imageToolGraphOpt,
                unetBackendOverride = imageToolUnetBackend,
                vaeDecoderBackendOverride = imageToolVaeDecoderBackend,
                vaeEncoderBackendOverride = imageToolVaeEncoderBackend,
                intraOpThreads = imageToolIntraThreads,
                interOpThreads = imageToolInterThreads,
                executionMode = imageToolExecutionMode,
                memoryPatternOptimization = imageToolMemoryPattern,
                cpuArenaAllocator = imageToolCpuArena,
                nnapiCpuDisabled = imageToolNnapiCpuDisabled,
                nnapiUseFp16 = imageToolNnapiFp16,
                sdParams = NativeChatSdImageToolParams(
                    model = imageToolSdModel.takeIf { it.isNotBlank() },
                    vaePath = imageToolSdVae.takeIf { it.isNotBlank() },
                    taePath = imageToolSdTae.takeIf { it.isNotBlank() },
                    clipLPath = imageToolSdClipL.takeIf { it.isNotBlank() },
                    clipGPath = imageToolSdClipG.takeIf { it.isNotBlank() },
                    t5xxlPath = imageToolSdT5xxl.takeIf { it.isNotBlank() },
                    llmPath = imageToolSdLlm.takeIf { it.isNotBlank() },
                    llmVisionPath = imageToolSdLlmVision.takeIf { it.isNotBlank() },
                    photoMakerPath = imageToolSdPhotoMaker.takeIf { it.isNotBlank() },
                    width = imageToolSdWidth,
                    height = imageToolSdHeight,
                    steps = imageToolSdSteps,
                    cfgScale = imageToolSdCfg,
                    sampler = imageToolSdSampler,
                    seed = imageToolSdSeed,
                    negativePrompt = imageToolSdNegativePrompt,
                    threads = imageToolSdThreads,
                    flowShift = imageToolSdFlowShift,
                    diffusionFa = imageToolSdDiffusionFa,
                    mmap = imageToolSdMmap,
                    vaeConvDirect = imageToolSdVaeConvDirect,
                    qwenImageZeroCondT = imageToolSdQwenZeroCondT,
                    chromaDisableDitMask = imageToolSdChromaDisableDitMask
                )
            ),
            backgroundRemovalParams = NativeChatBackgroundRemovalToolParams(
                model = bgrToolModel.takeIf { it.isNotBlank() },
                backend = bgrToolBackend,
                runtimeThreads = bgrToolRuntimeThreads,
                graphOptimizationLevel = bgrToolGraphOpt,
                alphaThreshold = bgrToolAlphaThreshold,
                featherRadius = bgrToolFeatherRadius,
                maskSoftness = bgrToolMaskSoftness,
                maskContrast = bgrToolMaskContrast,
                exportMask = bgrToolExportMask,
                resizeBeforeProcessing = bgrToolResizeBeforeProcessing,
                resizeMaxEdge = bgrToolResizeMaxEdge
            ),
            maxToolRounds = maxToolRounds
    )

    /**
     * JSON for the chat's API params from the current (always numerically valid) values. Invalid
     * sampler text never reaches the numeric fields, so this is safe to call on every change.
     */
    fun buildApiParamsJson(
        chatDocumentKnowledgeBaseIdOverride: Long? = chatDocumentKnowledgeBaseId,
        forceKnowledgeBaseTools: Boolean = false
    ): String {
        val liteRt = activeServerIsLiteRt
        val config = toToolConfig(
            nextToolsEnabled = toolsEnabled || forceKnowledgeBaseTools,
            nextKnowledgeBaseEnabled = knowledgeBaseEnabled || forceKnowledgeBaseTools,
            nextKnowledgeBaseAutoContextEnabled = knowledgeBaseAutoContextEnabled || forceKnowledgeBaseTools,
            chatDocumentKnowledgeBaseIdOverride = chatDocumentKnowledgeBaseIdOverride
        )
        val map = linkedMapOf<String, Any>(
            "temperature" to if (liteRt) temperature.coerceIn(0f, 1f) else temperature,
            "top_p" to if (liteRt) topP.coerceIn(0f, 0.95f) else topP,
            "top_k" to if (liteRt) topK.toInt().coerceIn(5, 64) else topK.toInt(),
            NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS_ENABLED to maxOutputTokensEnabled,
            NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS to maxOutputTokens.coerceAtLeast(1)
        )
        seedText.trim().toIntOrNull()?.let { map["seed"] = it }
        if (liteRt) {
            map[LITERT_PARAM_MTP_ENABLED] = liteRtMtpEnabled
            if (maxOutputTokensEnabled) {
                map[LITERT_PARAM_MAX_OUTPUT_TOKENS] = maxOutputTokens.coerceAtLeast(1)
            }
            map["enable_thinking"] = enableThinking
        } else {
            map["min_p"] = minP
            map["repeat_penalty"] = repPen
            map["enable_thinking"] = enableThinking
        }
        map.putAll(config.toParamMap())
        return Gson().toJson(map)
    }

    /** Context size / system prompt written to the chat row for LiteRT (outside the API params). */
    fun liteRtContextForSave(): Int = liteRtMaxTokens.coerceIn(liteRtContextRange)

    fun liteRtSystemPromptForSave(): String? = liteRtSystemPrompt.trim().takeIf { it.isNotBlank() }

    fun liteRtBackendForSave(): String =
        if (liteRtAccelerator == LITERT_BACKEND_CPU) LITERT_BACKEND_CPU else LITERT_BACKEND_GPU
}
