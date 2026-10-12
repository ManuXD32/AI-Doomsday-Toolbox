package com.example.llamadroid.ui.ai.llama.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.KnowledgeBaseEntity
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.DraftIntTextField
import com.google.gson.Gson

/** Default tool permissions a remote server grants to chats; also the tool-default ceiling. */
@Composable
internal fun NativeServerToolDefaultsSection(
    toolsEnabled: Boolean,
    onToolsEnabledChange: (Boolean) -> Unit,
    webSearchEnabled: Boolean,
    onWebSearchEnabledChange: (Boolean) -> Unit,
    kiwixSearchEnabled: Boolean,
    onKiwixSearchEnabledChange: (Boolean) -> Unit,
    kiwixServerUrl: String,
    onKiwixServerUrlChange: (String) -> Unit,
    fetchUrlEnabled: Boolean,
    onFetchUrlEnabledChange: (Boolean) -> Unit,
    deepResearchEnabled: Boolean,
    onDeepResearchEnabledChange: (Boolean) -> Unit,
    deepResearchImportIntoSelectedKbEnabled: Boolean,
    onDeepResearchImportIntoSelectedKbEnabledChange: (Boolean) -> Unit,
    deepResearchSourceLimit: Int,
    onDeepResearchSourceLimitChange: (Int) -> Unit,
    dateTimeEnabled: Boolean,
    onDateTimeEnabledChange: (Boolean) -> Unit,
    calculatorEnabled: Boolean,
    onCalculatorEnabledChange: (Boolean) -> Unit,
    noteToolsEnabled: Boolean,
    onNoteToolsEnabledChange: (Boolean) -> Unit,
    todoToolsEnabled: Boolean,
    onTodoToolsEnabledChange: (Boolean) -> Unit,
    knowledgeBases: List<KnowledgeBaseEntity>,
    knowledgeBaseEnabled: Boolean,
    onKnowledgeBaseEnabledChange: (Boolean) -> Unit,
    knowledgeBaseAutoContextEnabled: Boolean,
    onKnowledgeBaseAutoContextEnabledChange: (Boolean) -> Unit,
    selectedKnowledgeBaseIds: List<Long>,
    onSelectedKnowledgeBaseIdsChange: (List<Long>) -> Unit,
    knowledgeBaseMaxResults: Int,
    onKnowledgeBaseMaxResultsChange: (Int) -> Unit,
    calendarToolsEnabled: Boolean,
    onCalendarToolsEnabledChange: (Boolean) -> Unit,
    alarmToolsEnabled: Boolean,
    onAlarmToolsEnabledChange: (Boolean) -> Unit,
    imageGenerationEnabled: Boolean,
    onImageGenerationEnabledChange: (Boolean) -> Unit,
    imageIterationEnabled: Boolean,
    onImageIterationEnabledChange: (Boolean) -> Unit,
    backgroundRemovalEnabled: Boolean,
    onBackgroundRemovalEnabledChange: (Boolean) -> Unit,
    maxToolRounds: Int,
    onMaxToolRoundsChange: (Int) -> Unit
) {
    AppAdvancedSection(
        title = stringResource(R.string.llama_server_default_tools_title),
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.llama_server_default_tools_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ServerToolDefaultSwitchRow(
                title = stringResource(R.string.llama_tools_master_switch),
                checked = toolsEnabled,
                onCheckedChange = onToolsEnabledChange
            )
            if (toolsEnabled) {
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_web_search),
                    checked = webSearchEnabled,
                    onCheckedChange = onWebSearchEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_kiwix_search),
                    checked = kiwixSearchEnabled,
                    onCheckedChange = onKiwixSearchEnabledChange
                )
                if (kiwixSearchEnabled) {
                    OutlinedTextField(
                        value = kiwixServerUrl,
                        onValueChange = onKiwixServerUrlChange,
                        label = { Text(stringResource(R.string.llama_tool_kiwix_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_fetch_url),
                    checked = fetchUrlEnabled,
                    onCheckedChange = onFetchUrlEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_deep_research),
                    checked = deepResearchEnabled,
                    onCheckedChange = onDeepResearchEnabledChange
                )
                if (deepResearchEnabled) {
                    ServerToolDefaultSwitchRow(
                        title = stringResource(R.string.llama_tool_deep_research_import_selected_kb),
                        checked = deepResearchImportIntoSelectedKbEnabled,
                        onCheckedChange = onDeepResearchImportIntoSelectedKbEnabledChange
                    )
                    DraftIntTextField(
                        value = deepResearchSourceLimit,
                        onValueChange = onDeepResearchSourceLimitChange,
                        valueRange = NativeChatToolConfig.MIN_DEEP_RESEARCH_SOURCE_LIMIT..Int.MAX_VALUE,
                        label = { Text(stringResource(R.string.llama_tool_deep_research_source_limit)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_datetime),
                    checked = dateTimeEnabled,
                    onCheckedChange = onDateTimeEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_calculator),
                    checked = calculatorEnabled,
                    onCheckedChange = onCalculatorEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_notes),
                    checked = noteToolsEnabled,
                    onCheckedChange = onNoteToolsEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_todo_lists),
                    checked = todoToolsEnabled,
                    onCheckedChange = onTodoToolsEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_knowledge_bases),
                    checked = knowledgeBaseEnabled,
                    onCheckedChange = onKnowledgeBaseEnabledChange
                )
                if (knowledgeBaseEnabled) {
                    ServerToolDefaultSwitchRow(
                        title = stringResource(R.string.llama_tool_kb_auto_context),
                        checked = knowledgeBaseAutoContextEnabled,
                        onCheckedChange = onKnowledgeBaseAutoContextEnabledChange
                    )
                    if (knowledgeBases.isEmpty()) {
                        Text(
                            text = stringResource(R.string.kb_no_bases_yet),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
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
                                            onSelectedKnowledgeBaseIdsChange(
                                                if (selected) {
                                                    selectedKnowledgeBaseIds - kb.id
                                                } else {
                                                    (selectedKnowledgeBaseIds + kb.id).distinct()
                                                }
                                            )
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { checked ->
                                            onSelectedKnowledgeBaseIdsChange(
                                                if (checked) {
                                                    (selectedKnowledgeBaseIds + kb.id).distinct()
                                                } else {
                                                    selectedKnowledgeBaseIds - kb.id
                                                }
                                            )
                                        }
                                    )
                                    Text(
                                        text = kb.name,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    DraftIntTextField(
                        value = knowledgeBaseMaxResults,
                        onValueChange = onKnowledgeBaseMaxResultsChange,
                        valueRange = NativeChatToolConfig.MIN_KB_RESULTS..NativeChatToolConfig.MAX_KB_RESULTS,
                        label = { Text(stringResource(R.string.llama_tool_kb_max_results)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_calendar),
                    checked = calendarToolsEnabled,
                    onCheckedChange = onCalendarToolsEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_alarms),
                    checked = alarmToolsEnabled,
                    onCheckedChange = onAlarmToolsEnabledChange
                )
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_image_generation),
                    checked = imageGenerationEnabled,
                    onCheckedChange = onImageGenerationEnabledChange
                )
                if (imageGenerationEnabled) {
                    ServerToolDefaultSwitchRow(
                        title = stringResource(R.string.llama_tool_image_iteration),
                        checked = imageIterationEnabled,
                        onCheckedChange = onImageIterationEnabledChange
                    )
                }
                ServerToolDefaultSwitchRow(
                    title = stringResource(R.string.llama_tool_bgr),
                    checked = backgroundRemovalEnabled,
                    onCheckedChange = onBackgroundRemovalEnabledChange
                )
                DraftIntTextField(
                    value = maxToolRounds,
                    onValueChange = onMaxToolRoundsChange,
                    valueRange = NativeChatToolConfig.MIN_TOOL_ROUNDS..NativeChatToolConfig.MAX_TOOL_ROUNDS,
                    label = { Text(stringResource(R.string.llama_tool_max_rounds)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

}

@Composable
internal fun ServerToolDefaultSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

internal fun parseServerDefaultToolConfig(apiParams: String?): NativeChatToolConfig {
    if (apiParams.isNullOrBlank()) return NativeChatToolConfig()
    return runCatching {
        @Suppress("UNCHECKED_CAST")
        val params = Gson().fromJson(apiParams, Map::class.java) as? Map<String, Any>
        NativeChatToolConfig.fromParams(params.orEmpty())
    }.getOrDefault(NativeChatToolConfig())
}

internal fun buildServerDefaultToolApiParams(
    toolsEnabled: Boolean,
    webSearchEnabled: Boolean,
    kiwixSearchEnabled: Boolean,
    kiwixServerUrl: String,
    fetchUrlEnabled: Boolean,
    deepResearchEnabled: Boolean,
    deepResearchImportIntoSelectedKbEnabled: Boolean,
    deepResearchSourceLimit: Int,
    dateTimeEnabled: Boolean,
    calculatorEnabled: Boolean,
    noteToolsEnabled: Boolean,
    todoToolsEnabled: Boolean,
    knowledgeBaseEnabled: Boolean,
    knowledgeBaseAutoContextEnabled: Boolean,
    selectedKnowledgeBaseIds: List<Long>,
    knowledgeBaseMaxResults: Int,
    calendarToolsEnabled: Boolean,
    alarmToolsEnabled: Boolean,
    imageGenerationEnabled: Boolean,
    imageIterationEnabled: Boolean,
    backgroundRemovalEnabled: Boolean,
    maxToolRounds: Int
): String {
    return Gson().toJson(
        NativeChatToolConfig(
            toolsEnabled = toolsEnabled,
            webSearchEnabled = webSearchEnabled,
            kiwixSearchEnabled = kiwixSearchEnabled,
            kiwixServerUrl = kiwixServerUrl.ifBlank { NativeChatToolConfig.DEFAULT_KIWIX_URL },
            fetchUrlEnabled = fetchUrlEnabled,
            deepResearchEnabled = deepResearchEnabled,
            deepResearchImportIntoSelectedKbEnabled = deepResearchImportIntoSelectedKbEnabled,
            deepResearchSourceLimit = deepResearchSourceLimit,
            dateTimeEnabled = dateTimeEnabled,
            calculatorEnabled = calculatorEnabled,
            noteToolsEnabled = noteToolsEnabled,
            todoToolsEnabled = todoToolsEnabled,
            knowledgeBaseEnabled = knowledgeBaseEnabled,
            knowledgeBaseAutoContextEnabled = knowledgeBaseAutoContextEnabled,
            selectedKnowledgeBaseIds = selectedKnowledgeBaseIds,
            knowledgeBaseMaxResults = knowledgeBaseMaxResults,
            calendarToolsEnabled = calendarToolsEnabled,
            alarmToolsEnabled = alarmToolsEnabled,
            imageGenerationEnabled = imageGenerationEnabled,
            imageIterationEnabled = imageIterationEnabled,
            backgroundRemovalEnabled = backgroundRemovalEnabled,
            maxToolRounds = maxToolRounds
        ).toParamMap()
    )
}

@Composable
internal fun LlamaServerBadge(
    label: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
