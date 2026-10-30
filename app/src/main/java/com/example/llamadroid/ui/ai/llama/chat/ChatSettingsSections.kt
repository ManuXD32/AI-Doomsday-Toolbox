package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.onnx.supertonicLanguageCodes
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.resolvedSdFamily
import com.example.llamadroid.sd.resolveSdFamilySpec
import com.example.llamadroid.service.NativeChatImageGenerationEngine
import com.example.llamadroid.service.NativeChatSdImageToolParams
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.service.supportsSdTxt2Img
import com.example.llamadroid.ui.ai.llama.LlamaSamplerField
import com.example.llamadroid.data.db.KnowledgeBaseEntity

/** Lookup lists and callbacks the settings sections need from the screen. */
@Stable
internal class ChatSettingsEnv(
    val onnxTtsModels: List<ModelEntity>,
    val ttsVoiceOptions: List<String>,
    val knowledgeBases: List<KnowledgeBaseEntity>,
    val imageModelOptions: List<String>,
    val sdImageMainModels: List<ModelEntity>,
    val sdImageModelOptions: List<String>,
    val sdImageSupportModels: List<ModelEntity>,
    val bgrModelOptions: List<String>,
    val liteRtContextCap: Int,
    val onOpenKnowledgeBase: () -> Unit,
    val onManageCustomTools: () -> Unit,
    /**
     * True when the sections edit a server's "defaults for new chats": chat-only actions and
     * navigation away from the editor (manage custom tools / knowledge bases) are hidden.
     */
    val defaultsMode: Boolean = false
)

/** Server and context: LiteRT context length, accelerator and the GPU warning. */
@Composable
internal fun ChatSettingsState.ServerContextSection(env: ChatSettingsEnv) {
    if (activeServerIsLiteRt) {
        LlamaToolNumberRow(
            label = stringResource(R.string.litert_gallery_max_tokens),
            value = liteRtMaxTokens,
            onValueChange = { liteRtMaxTokens = it },
            range = liteRtContextRange
        )
        Text(
            text = stringResource(
                R.string.litert_gallery_context_cap_hint,
                liteRtDefaultContext,
                env.liteRtContextCap
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (liteRtAccelerator == LITERT_BACKEND_GPU && liteRtMaxTokens > LITERT_CHAT_GPU_HIGH_CONTEXT_WARNING_TOKENS) {
            Text(
                text = stringResource(R.string.litert_gallery_high_gpu_context_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = stringResource(R.string.litert_gallery_accelerator),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                LITERT_BACKEND_GPU to stringResource(R.string.litert_backend_gpu),
                LITERT_BACKEND_CPU to stringResource(R.string.general_acceleration_mode_cpu)
            ).forEach { (backend, label) ->
                FilterChip(
                    selected = liteRtAccelerator == backend,
                    onClick = { liteRtAccelerator = backend },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    } else {
        Text(
            text = stringResource(R.string.native_chat_settings_context_server_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ChatSettingsState.SamplingSection() {
    Text(
        text = stringResource(R.string.chat_sampler_auto_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
    LlamaSamplerField.visible(activeServerIsLiteRt).filter { it.basic }
        .forEach { SamplerRow(it) }
    if (!activeServerIsLiteRt) AdvancedSamplingBlock()
    ResetSamplersButton()
}

@Composable
internal fun ChatSettingsState.ThinkingSection() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    if (activeServerIsLiteRt) R.string.litert_thinking_title else R.string.llama_thinking_process
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            if (activeServerIsLiteRt) {
                Text(
                    text = stringResource(R.string.litert_thinking_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Switch(
            checked = enableThinking,
            onCheckedChange = { enableThinking = it }
        )
    }
}

@Composable
internal fun ChatSettingsState.VoiceCallSection(env: ChatSettingsEnv) {
    val onnxTtsModels = env.onnxTtsModels
    val nativeChatTtsVoiceOptions = env.ttsVoiceOptions
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.llama_voice_replies_title), style = MaterialTheme.typography.titleSmall)
        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_assistant_tts),
            description = stringResource(R.string.llama_tool_assistant_tts_desc),
            checked = assistantTtsEnabled,
            enabled = true,
            onCheckedChange = { assistantTtsEnabled = it }
        )
        if (assistantTtsEnabled) {
            if (onnxTtsModels.isEmpty()) {
                Text(
                    text = stringResource(R.string.llama_tool_assistant_tts_no_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            LlamaStringDropdown(
                label = stringResource(R.string.llama_tool_assistant_tts_language),
                selected = assistantTtsLanguage,
                values = supertonicLanguageCodes,
                onSelected = { assistantTtsLanguage = it },
                enabled = true,
                modifier = Modifier.fillMaxWidth()
            )
            LlamaStringDropdown(
                label = stringResource(R.string.llama_tool_assistant_tts_voice),
                selected = assistantTtsVoiceName.ifBlank { nativeChatTtsVoiceOptions.firstOrNull().orEmpty() },
                values = nativeChatTtsVoiceOptions,
                onSelected = { assistantTtsVoiceName = it },
                enabled = nativeChatTtsVoiceOptions.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.llama_tool_assistant_tts_speed, assistantTtsSpeed),
                    modifier = Modifier.width(120.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = assistantTtsSpeed,
                    onValueChange = {
                        assistantTtsSpeed = it.coerceIn(
                            NativeChatToolConfig.MIN_ASSISTANT_TTS_SPEED,
                            NativeChatToolConfig.MAX_ASSISTANT_TTS_SPEED
                        )
                    },
                    valueRange = NativeChatToolConfig.MIN_ASSISTANT_TTS_SPEED..NativeChatToolConfig.MAX_ASSISTANT_TTS_SPEED,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.llama_tool_assistant_tts_steps, assistantTtsSteps),
                    modifier = Modifier.width(120.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = assistantTtsSteps.toFloat(),
                    onValueChange = {
                        assistantTtsSteps = it.toInt().coerceIn(
                            NativeChatToolConfig.MIN_ASSISTANT_TTS_STEPS,
                            NativeChatToolConfig.MAX_ASSISTANT_TTS_STEPS
                        )
                    },
                    valueRange = NativeChatToolConfig.MIN_ASSISTANT_TTS_STEPS.toFloat()..NativeChatToolConfig.MAX_ASSISTANT_TTS_STEPS.toFloat(),
                    steps = NativeChatToolConfig.MAX_ASSISTANT_TTS_STEPS - NativeChatToolConfig.MIN_ASSISTANT_TTS_STEPS - 1,
                    modifier = Modifier.weight(1f)
                )
            }
            if (nativeChatTtsVoiceOptions.isEmpty() && onnxTtsModels.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.llama_tool_assistant_tts_no_voices),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(stringResource(R.string.llama_call_settings_title), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(R.string.llama_call_settings_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.llama_call_after_last_word, callSilenceAfterSpeechSeconds),
                modifier = Modifier.width(150.dp),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = callSilenceAfterSpeechSeconds.toFloat(),
                onValueChange = {
                    callSilenceAfterSpeechSeconds = it.toInt().coerceIn(
                        NativeChatToolConfig.MIN_CALL_SILENCE_AFTER_SPEECH_SECONDS,
                        NativeChatToolConfig.MAX_CALL_SILENCE_AFTER_SPEECH_SECONDS
                    )
                },
                valueRange = NativeChatToolConfig.MIN_CALL_SILENCE_AFTER_SPEECH_SECONDS.toFloat()..NativeChatToolConfig.MAX_CALL_SILENCE_AFTER_SPEECH_SECONDS.toFloat(),
                steps = NativeChatToolConfig.MAX_CALL_SILENCE_AFTER_SPEECH_SECONDS - NativeChatToolConfig.MIN_CALL_SILENCE_AFTER_SPEECH_SECONDS - 1,
                modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.llama_call_wait_for_answer, callNoSpeechTimeoutSeconds),
                modifier = Modifier.width(150.dp),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = callNoSpeechTimeoutSeconds.toFloat(),
                onValueChange = {
                    callNoSpeechTimeoutSeconds = it.toInt().coerceIn(
                        NativeChatToolConfig.MIN_CALL_NO_SPEECH_TIMEOUT_SECONDS,
                        NativeChatToolConfig.MAX_CALL_NO_SPEECH_TIMEOUT_SECONDS
                    )
                },
                valueRange = NativeChatToolConfig.MIN_CALL_NO_SPEECH_TIMEOUT_SECONDS.toFloat()..NativeChatToolConfig.MAX_CALL_NO_SPEECH_TIMEOUT_SECONDS.toFloat(),
                steps = NativeChatToolConfig.MAX_CALL_NO_SPEECH_TIMEOUT_SECONDS - NativeChatToolConfig.MIN_CALL_NO_SPEECH_TIMEOUT_SECONDS - 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Tools: master switch and the text/utility tools. Image tools and knowledge bases have their own sections. */
@Composable
internal fun ChatSettingsState.ToolsSection(env: ChatSettingsEnv) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tools_enable),
            description = stringResource(R.string.llama_tools_enable_desc),
            checked = effectiveToolsEnabled,
            enabled = serverAllowsTools,
            onCheckedChange = { toolsEnabled = it }
        )
        if (activeServerIsLiteRt) {
            Text(
                text = stringResource(R.string.litert_tools_unavailable_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (effectiveToolsEnabled) {
        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_web_search),
            description = stringResource(R.string.llama_tool_web_search_desc),
            checked = effectiveWebSearchEnabled,
            enabled = serverAllowsWebSearch,
            onCheckedChange = { webSearchEnabled = it }
        )
        if (effectiveWebSearchEnabled) {
            LlamaToolNumberRow(
                label = stringResource(R.string.llama_tool_pages),
                value = webSearchMaxPages,
                onValueChange = { webSearchMaxPages = it },
                range = NativeChatToolConfig.MIN_SEARCH_PAGES..NativeChatToolConfig.MAX_SEARCH_PAGES
            )
            LlamaToolNumberRow(
                label = stringResource(R.string.llama_tool_chars_per_page),
                value = webSearchMaxChars,
                onValueChange = { webSearchMaxChars = it },
                range = NativeChatToolConfig.MIN_PAGE_CHARS..NativeChatToolConfig.MAX_PAGE_CHARS
            )
        }

        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_kiwix_search),
            description = stringResource(R.string.llama_tool_kiwix_search_desc),
            checked = effectiveKiwixSearchEnabled,
            enabled = serverAllowsKiwixSearch,
            onCheckedChange = { kiwixSearchEnabled = it }
        )
        if (effectiveKiwixSearchEnabled) {
            OutlinedTextField(
                value = kiwixServerUrl,
                onValueChange = { kiwixServerUrl = it },
                label = { Text(stringResource(R.string.llama_tool_kiwix_url)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            LlamaToolNumberRow(
                label = stringResource(R.string.llama_tool_pages),
                value = kiwixMaxPages,
                onValueChange = { kiwixMaxPages = it },
                range = NativeChatToolConfig.MIN_SEARCH_PAGES..NativeChatToolConfig.MAX_SEARCH_PAGES
            )
            LlamaToolNumberRow(
                label = stringResource(R.string.llama_tool_chars_per_page),
                value = kiwixMaxChars,
                onValueChange = { kiwixMaxChars = it },
                range = NativeChatToolConfig.MIN_PAGE_CHARS..NativeChatToolConfig.MAX_PAGE_CHARS
            )
        }

        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_fetch_url),
            description = stringResource(R.string.llama_tool_fetch_url_desc),
            checked = effectiveFetchUrlEnabled,
            enabled = serverAllowsFetchUrl,
            onCheckedChange = { fetchUrlEnabled = it }
        )
        if (effectiveFetchUrlEnabled) {
            LlamaToolNumberRow(
                label = stringResource(R.string.llama_tool_max_chars),
                value = fetchUrlMaxChars,
                onValueChange = { fetchUrlMaxChars = it },
                range = NativeChatToolConfig.MIN_FETCH_CHARS..NativeChatToolConfig.MAX_FETCH_CHARS
            )
        }

        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_deep_research),
            description = stringResource(R.string.llama_tool_deep_research_desc),
            checked = effectiveDeepResearchEnabled,
            enabled = serverAllowsDeepResearch,
            onCheckedChange = { deepResearchEnabled = it }
        )
        if (effectiveDeepResearchEnabled) {
            LlamaToolToggleRow(
                label = stringResource(R.string.llama_tool_deep_research_import_selected_kb),
                description = stringResource(R.string.llama_tool_deep_research_import_selected_kb_desc),
                checked = effectiveDeepResearchImportIntoSelectedKbEnabled,
                enabled = serverAllowsDeepResearchSelectedKbImport,
                onCheckedChange = { deepResearchImportIntoSelectedKbEnabled = it }
            )
            LlamaToolNumberRow(
                label = stringResource(R.string.llama_tool_deep_research_source_limit),
                value = deepResearchSourceLimit,
                onValueChange = { deepResearchSourceLimit = it },
                range = NativeChatToolConfig.MIN_DEEP_RESEARCH_SOURCE_LIMIT..Int.MAX_VALUE
            )
            Text(
                text = stringResource(R.string.llama_tool_deep_research_source_limit_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_datetime),
            description = stringResource(R.string.llama_tool_datetime_desc),
            checked = effectiveDateTimeEnabled,
            enabled = serverAllowsDateTime,
            onCheckedChange = { dateTimeEnabled = it }
        )
        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_calculator),
            description = stringResource(R.string.llama_tool_calculator_desc),
            checked = effectiveCalculatorEnabled,
            enabled = serverAllowsCalculator,
            onCheckedChange = { calculatorEnabled = it }
        )
        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_notes),
            description = stringResource(R.string.llama_tool_notes_desc),
            checked = effectiveNoteToolsEnabled,
            enabled = serverAllowsNotes,
            onCheckedChange = { noteToolsEnabled = it }
        )
        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_todo_lists),
            description = stringResource(R.string.llama_tool_todo_lists_desc),
            checked = effectiveTodoToolsEnabled,
            enabled = serverAllowsTodoLists,
            onCheckedChange = { todoToolsEnabled = it }
        )
        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_calendar),
            description = stringResource(R.string.llama_tool_calendar_desc),
            checked = effectiveCalendarToolsEnabled,
            enabled = serverAllowsCalendar,
            onCheckedChange = { calendarToolsEnabled = it }
        )
        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_alarms),
            description = stringResource(R.string.llama_tool_alarms_desc),
            checked = effectiveAlarmToolsEnabled,
            enabled = serverAllowsAlarms,
            onCheckedChange = { alarmToolsEnabled = it }
        )
        LlamaToolToggleRow(
            label = stringResource(R.string.native_chat_file_tools),
            description = stringResource(R.string.native_chat_file_tools_desc),
            checked = fileToolsEnabled,
            enabled = true,
            onCheckedChange = { fileToolsEnabled = it }
        )
        LlamaToolToggleRow(
            label = stringResource(R.string.native_chat_custom_tools),
            description = stringResource(R.string.native_chat_custom_tools_desc),
            checked = customToolsEnabled,
            enabled = true,
            onCheckedChange = { customToolsEnabled = it }
        )
        if (!env.defaultsMode) {
            OutlinedButton(
                onClick = { env.onManageCustomTools() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.native_chat_manage_custom_tools))
            }
        }
        LlamaToolNumberRow(
            label = stringResource(R.string.llama_tool_max_rounds),
            value = maxToolRounds,
            onValueChange = { maxToolRounds = it },
            range = NativeChatToolConfig.MIN_TOOL_ROUNDS..NativeChatToolConfig.MAX_TOOL_ROUNDS
        )
        }
    }
}

@Composable
internal fun ChatSettingsState.KnowledgeBaseSection(env: ChatSettingsEnv) {
    val knowledgeBases = env.knowledgeBases
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (effectiveToolsEnabled) {
        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_knowledge_bases),
            description = stringResource(R.string.llama_tool_knowledge_bases_desc),
            checked = effectiveKnowledgeBaseEnabled,
            enabled = serverAllowsKnowledgeBases,
            onCheckedChange = { knowledgeBaseEnabled = it }
        )
        if (effectiveKnowledgeBaseEnabled) {
            LlamaToolToggleRow(
                label = stringResource(R.string.llama_tool_kb_auto_context),
                description = stringResource(R.string.llama_tool_kb_auto_context_desc),
                checked = effectiveKnowledgeBaseAutoContextEnabled,
                enabled = serverAllowsKnowledgeAutoContext,
                onCheckedChange = { knowledgeBaseAutoContextEnabled = it }
            )
            if (knowledgeBases.isEmpty()) {
                Text(
                    text = stringResource(R.string.kb_no_bases_yet),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.llama_tool_kb_selected_count, selectedKnowledgeBaseIds.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    knowledgeBases.forEach { kb ->
                        val selected = kb.id in selectedKnowledgeBaseIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedKnowledgeBaseIds = if (selected) {
                                        selectedKnowledgeBaseIds - kb.id
                                    } else {
                                        (selectedKnowledgeBaseIds + kb.id).distinct()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { checked ->
                                    selectedKnowledgeBaseIds = if (checked) {
                                        (selectedKnowledgeBaseIds + kb.id).distinct()
                                    } else {
                                        selectedKnowledgeBaseIds - kb.id
                                    }
                                }
                            )
                            Text(
                                text = kb.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            LlamaToolNumberRow(
                label = stringResource(R.string.llama_tool_kb_max_results),
                value = knowledgeBaseMaxResults,
                onValueChange = { knowledgeBaseMaxResults = it },
                range = NativeChatToolConfig.MIN_KB_RESULTS..NativeChatToolConfig.MAX_KB_RESULTS
            )
            if (!env.defaultsMode) {
                OutlinedButton(
                    onClick = { env.onOpenKnowledgeBase() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.kb_manage_action))
                }
            }
        }
        } else {
            ToolsOffHint()
        }
    }
}

@Composable
internal fun ChatSettingsState.ImageToolsSection(env: ChatSettingsEnv) {
    val nativeChatImageModelOptions = env.imageModelOptions
    val sdImageMainModels = env.sdImageMainModels
    val nativeChatSdImageModelOptions = env.sdImageModelOptions
    val sdImageSupportModels = env.sdImageSupportModels
    val nativeChatBgrModelOptions = env.bgrModelOptions
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (effectiveToolsEnabled) {
        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_image_generation),
            description = stringResource(R.string.llama_tool_image_generation_desc),
            checked = effectiveImageGenerationEnabled,
            enabled = serverAllowsImageGeneration,
            onCheckedChange = { imageGenerationEnabled = it }
        )
        if (effectiveImageGenerationEnabled) {
            LlamaToolToggleRow(
                label = stringResource(R.string.llama_tool_image_iteration),
                description = stringResource(R.string.llama_tool_image_iteration_desc),
                checked = effectiveImageIterationEnabled,
                enabled = serverAllowsImageIteration,
                onCheckedChange = { imageIterationEnabled = it }
            )
            LlamaImageToolEnumDropdown(
                label = stringResource(R.string.image_tool_engine_label),
                selected = imageToolEngine,
                values = NativeChatImageGenerationEngine.entries,
                labelFor = {
                    when (it) {
                        NativeChatImageGenerationEngine.ONNX -> stringResource(R.string.image_tool_engine_onnx)
                        NativeChatImageGenerationEngine.SD -> stringResource(R.string.image_tool_engine_sd)
                    }
                },
                onSelected = { imageToolEngine = it }
            )
            if (imageToolEngine == NativeChatImageGenerationEngine.ONNX) {
                LlamaNativeImageToolSettings(
                    model = imageToolModel,
                    availableModels = nativeChatImageModelOptions,
                    onModelChange = { imageToolModel = it },
                    width = imageToolWidth,
                    onWidthChange = { imageToolWidth = it },
                    height = imageToolHeight,
                    onHeightChange = { imageToolHeight = it },
                    steps = imageToolSteps,
                    onStepsChange = { imageToolSteps = it },
                    cfg = imageToolCfg,
                    onCfgChange = { imageToolCfg = it },
                    seed = imageToolSeed,
                    onSeedChange = { imageToolSeed = it },
                    negativePrompt = imageToolNegativePrompt,
                    onNegativePromptChange = { imageToolNegativePrompt = it },
                    backend = imageToolBackend,
                    onBackendChange = { imageToolBackend = it },
                    runtimeThreads = imageToolRuntimeThreads,
                    onRuntimeThreadsChange = { imageToolRuntimeThreads = it },
                    graphOptimizationLevel = imageToolGraphOpt,
                    onGraphOptimizationLevelChange = { imageToolGraphOpt = it },
                    unetBackendOverride = imageToolUnetBackend,
                    onUnetBackendOverrideChange = { imageToolUnetBackend = it },
                    vaeDecoderBackendOverride = imageToolVaeDecoderBackend,
                    onVaeDecoderBackendOverrideChange = { imageToolVaeDecoderBackend = it },
                    vaeEncoderBackendOverride = imageToolVaeEncoderBackend,
                    onVaeEncoderBackendOverrideChange = { imageToolVaeEncoderBackend = it },
                    intraOpThreads = imageToolIntraThreads,
                    onIntraOpThreadsChange = { imageToolIntraThreads = it },
                    interOpThreads = imageToolInterThreads,
                    onInterOpThreadsChange = { imageToolInterThreads = it },
                    executionMode = imageToolExecutionMode,
                    onExecutionModeChange = { imageToolExecutionMode = it },
                    memoryPatternOptimization = imageToolMemoryPattern,
                    onMemoryPatternOptimizationChange = { imageToolMemoryPattern = it },
                    cpuArenaAllocator = imageToolCpuArena,
                    onCpuArenaAllocatorChange = { imageToolCpuArena = it },
                    nnapiCpuDisabled = imageToolNnapiCpuDisabled,
                    onNnapiCpuDisabledChange = { imageToolNnapiCpuDisabled = it },
                    nnapiUseFp16 = imageToolNnapiFp16,
                    onNnapiUseFp16Change = { imageToolNnapiFp16 = it }
                )
            } else {
                val selectedSdModel = sdImageMainModels.firstOrNull {
                    it.filename == imageToolSdModel || it.path == imageToolSdModel
                } ?: sdImageMainModels.firstOrNull { it.supportsSdTxt2Img() }
                val selectedSdFamily = selectedSdModel?.resolvedSdFamily()
                val selectedSdSpec = selectedSdFamily?.first?.let { family ->
                    resolveSdFamilySpec(family, selectedSdFamily.second)
                }
                val sdRoles = selectedSdSpec?.let { spec ->
                    listOf(
                        SdComponentRole.VAE,
                        SdComponentRole.TAE,
                        SdComponentRole.CLIP_L,
                        SdComponentRole.CLIP_G,
                        SdComponentRole.T5XXL,
                        SdComponentRole.LLM,
                        SdComponentRole.LLM_VISION,
                        SdComponentRole.PHOTOMAKER
                    ).filter { it in spec.requiredRoles || it in spec.optionalRoles }
                }.orEmpty()
                LlamaNativeSdImageToolSettings(
                    model = imageToolSdModel,
                    availableModels = nativeChatSdImageModelOptions,
                    onModelChange = { imageToolSdModel = it },
                    componentRoles = sdRoles,
                    requiredRoles = selectedSdSpec?.requiredRoles.orEmpty(),
                    componentOptions = { role -> llamaSdComponentOptions(sdImageSupportModels, selectedSdModel, role) },
                    selectedComponent = { role ->
                        when (role) {
                            SdComponentRole.VAE -> imageToolSdVae
                            SdComponentRole.TAE -> imageToolSdTae
                            SdComponentRole.CLIP_L -> imageToolSdClipL
                            SdComponentRole.CLIP_G -> imageToolSdClipG
                            SdComponentRole.T5XXL -> imageToolSdT5xxl
                            SdComponentRole.LLM -> imageToolSdLlm
                            SdComponentRole.LLM_VISION -> imageToolSdLlmVision
                            SdComponentRole.PHOTOMAKER -> imageToolSdPhotoMaker
                            else -> ""
                        }
                    },
                    onComponentChange = { role, value ->
                        when (role) {
                            SdComponentRole.VAE -> imageToolSdVae = value
                            SdComponentRole.TAE -> imageToolSdTae = value
                            SdComponentRole.CLIP_L -> imageToolSdClipL = value
                            SdComponentRole.CLIP_G -> imageToolSdClipG = value
                            SdComponentRole.T5XXL -> imageToolSdT5xxl = value
                            SdComponentRole.LLM -> imageToolSdLlm = value
                            SdComponentRole.LLM_VISION -> imageToolSdLlmVision = value
                            SdComponentRole.PHOTOMAKER -> imageToolSdPhotoMaker = value
                            else -> Unit
                        }
                    },
                    width = imageToolSdWidth,
                    onWidthChange = { imageToolSdWidth = it.coerceIn(NativeChatSdImageToolParams.MIN_SIZE, NativeChatSdImageToolParams.MAX_SIZE) },
                    height = imageToolSdHeight,
                    onHeightChange = { imageToolSdHeight = it.coerceIn(NativeChatSdImageToolParams.MIN_SIZE, NativeChatSdImageToolParams.MAX_SIZE) },
                    steps = imageToolSdSteps,
                    onStepsChange = { imageToolSdSteps = it.coerceIn(NativeChatSdImageToolParams.MIN_STEPS, NativeChatSdImageToolParams.MAX_STEPS) },
                    cfg = imageToolSdCfg,
                    onCfgChange = { imageToolSdCfg = it.coerceIn(NativeChatSdImageToolParams.MIN_CFG, NativeChatSdImageToolParams.MAX_CFG) },
                    sampler = imageToolSdSampler,
                    onSamplerChange = { imageToolSdSampler = it },
                    seed = imageToolSdSeed,
                    onSeedChange = { imageToolSdSeed = it },
                    negativePrompt = imageToolSdNegativePrompt,
                    onNegativePromptChange = { imageToolSdNegativePrompt = it },
                    threads = imageToolSdThreads,
                    onThreadsChange = { imageToolSdThreads = it.coerceIn(NativeChatSdImageToolParams.MIN_THREADS, NativeChatSdImageToolParams.MAX_THREADS) },
                    flowShift = imageToolSdFlowShift,
                    onFlowShiftChange = { imageToolSdFlowShift = it },
                    showFlowShift = selectedSdSpec?.supportsFlowShift == true,
                    diffusionFa = imageToolSdDiffusionFa,
                    onDiffusionFaChange = { imageToolSdDiffusionFa = it },
                    showDiffusionFa = selectedSdSpec?.supportsDiffusionFa == true,
                    mmap = imageToolSdMmap,
                    onMmapChange = { imageToolSdMmap = it },
                    showMmap = selectedSdSpec?.supportsMmap == true,
                    vaeConvDirect = imageToolSdVaeConvDirect,
                    onVaeConvDirectChange = { imageToolSdVaeConvDirect = it },
                    showVaeConvDirect = selectedSdSpec?.supportsVaeConvDirect == true,
                    qwenZeroCondT = imageToolSdQwenZeroCondT,
                    onQwenZeroCondTChange = { imageToolSdQwenZeroCondT = it },
                    showQwenZeroCondT = selectedSdSpec?.supportsQwenImageZeroCondT == true,
                    chromaDisableDitMask = imageToolSdChromaDisableDitMask,
                    onChromaDisableDitMaskChange = { imageToolSdChromaDisableDitMask = it },
                    showChromaDisableDitMask = selectedSdSpec?.supportsChromaDisableDitMask == true
                )
            }
        }
        LlamaToolToggleRow(
            label = stringResource(R.string.llama_tool_bgr),
            description = stringResource(R.string.llama_tool_bgr_desc),
            checked = effectiveBackgroundRemovalEnabled,
            enabled = serverAllowsBackgroundRemoval,
            onCheckedChange = { backgroundRemovalEnabled = it }
        )
        if (effectiveBackgroundRemovalEnabled) {
            LlamaNativeBackgroundRemovalToolSettings(
                model = bgrToolModel,
                availableModels = nativeChatBgrModelOptions,
                onModelChange = { bgrToolModel = it },
                backend = bgrToolBackend,
                onBackendChange = { bgrToolBackend = it },
                runtimeThreads = bgrToolRuntimeThreads,
                onRuntimeThreadsChange = { bgrToolRuntimeThreads = it },
                graphOptimizationLevel = bgrToolGraphOpt,
                onGraphOptimizationLevelChange = { bgrToolGraphOpt = it },
                alphaThreshold = bgrToolAlphaThreshold,
                onAlphaThresholdChange = { bgrToolAlphaThreshold = it },
                featherRadius = bgrToolFeatherRadius,
                onFeatherRadiusChange = { bgrToolFeatherRadius = it },
                maskSoftness = bgrToolMaskSoftness,
                onMaskSoftnessChange = { bgrToolMaskSoftness = it },
                maskContrast = bgrToolMaskContrast,
                onMaskContrastChange = { bgrToolMaskContrast = it },
                exportMask = bgrToolExportMask,
                onExportMaskChange = { bgrToolExportMask = it },
                resizeBeforeProcessing = bgrToolResizeBeforeProcessing,
                onResizeBeforeProcessingChange = { bgrToolResizeBeforeProcessing = it },
                resizeMaxEdge = bgrToolResizeMaxEdge,
                onResizeMaxEdgeChange = { bgrToolResizeMaxEdge = it }
            )
        }
        } else {
            ToolsOffHint()
        }
    }
}

@Composable
private fun ToolsOffHint() {
    Text(
        text = stringResource(R.string.native_chat_settings_tools_off_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Advanced: output cap, seed and the LiteRT-only extras (MTP, system prompt). */
@Composable
internal fun ChatSettingsState.AdvancedSection(defaultsMode: Boolean = false) {
    LlamaMaxOutputTokensControl(
        enabled = maxOutputTokensEnabled,
        value = maxOutputTokens,
        onEnabledChange = { maxOutputTokensEnabled = it },
        onValueChange = { maxOutputTokens = it.coerceAtLeast(1) }
    )
    LlamaSeedControl(
        value = seedText,
        onValueChange = { seedText = it }
    )
    if (activeServerIsLiteRt) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.litert_gallery_mtp_title),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.litert_gallery_mtp_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = liteRtMtpEnabled,
                onCheckedChange = { liteRtMtpEnabled = it }
            )
        }
        // The LiteRT system prompt lives on the chat row, not in the API params, so it has no default.
        if (!defaultsMode) OutlinedTextField(
            value = liteRtSystemPrompt,
            onValueChange = { liteRtSystemPrompt = it },
            label = { Text(stringResource(R.string.llama_system_prompt)) },
            placeholder = { Text(stringResource(R.string.llama_system_prompt_placeholder)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
            minLines = 3
        )
    }
}

/** Automatic context compaction; the chat also reaches this switch from its context gauge. */
@Composable
internal fun ChatSettingsState.CompactionSection() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.native_chat_compaction_auto),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.native_chat_compaction_auto_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(checked = autoCompactEnabled, onCheckedChange = { autoCompactEnabled = it })
    }
}
