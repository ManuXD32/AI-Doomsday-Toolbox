package com.example.llamadroid.ui.agent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AgentPendingQuestionEntity
import com.example.llamadroid.data.db.AgentTodoEntity
import com.example.llamadroid.service.QuestionOption
import com.example.llamadroid.service.QuestionSpec
import com.example.llamadroid.service.questionSpecFromJson
import org.json.JSONArray
import org.json.JSONObject

data class AgentCommandSuggestion(
    val command: String,
    val description: String
)

@Composable
fun AgentCommandSuggestions(
    input: String,
    installedSkillNames: List<String>,
    customAgentNames: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val trimmed = input.trimStart()
    val commands = listOf(
        AgentCommandSuggestion("/plan", stringResource(R.string.agent_command_plan_help)),
        AgentCommandSuggestion("/build", stringResource(R.string.agent_command_build_help)),
        AgentCommandSuggestion("/compact", stringResource(R.string.agent_command_compact_help)),
        AgentCommandSuggestion("/details", stringResource(R.string.agent_command_details_help)),
        AgentCommandSuggestion("/skills", stringResource(R.string.agent_command_skills_help)),
        AgentCommandSuggestion("/todos", stringResource(R.string.agent_command_todos_help)),
        AgentCommandSuggestion("/agents", stringResource(R.string.agent_command_agents_help)),
        AgentCommandSuggestion("/custom-agents", stringResource(R.string.agent_command_custom_agents_help)),
        AgentCommandSuggestion("/custom-tools", stringResource(R.string.agent_command_custom_tools_help)),
        AgentCommandSuggestion("/commands", stringResource(R.string.agent_command_commands_help)),
        AgentCommandSuggestion("/help", stringResource(R.string.agent_command_help_help))
    ) + installedSkillNames.map { AgentCommandSuggestion("/$it", stringResource(R.string.agent_command_skill_help)) }
    val suggestions = when {
        trimmed.startsWith("/") -> commands.filter {
            it.command.startsWith(trimmed.substringBefore(' '), ignoreCase = true)
        }
        trimmed.startsWith("@") -> customAgentNames
            .filter { "@$it".startsWith(trimmed.substringBefore(' '), ignoreCase = true) }
            .map { AgentCommandSuggestion("@$it", stringResource(R.string.agent_command_agent_help)) }
        else -> emptyList()
    }.take(6)
    if (suggestions.isEmpty()) return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            suggestions.forEachIndexed { index, suggestion ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onSelect("${suggestion.command} ") }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        suggestion.command,
                        modifier = Modifier.weight(0.35f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        suggestion.description,
                        modifier = Modifier.weight(0.65f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (index != suggestions.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
fun AgentCommandsDialog(
    onCommandSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_commands_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.agent_commands_view_heading), fontWeight = FontWeight.SemiBold)
                listOf(
                    "/todos" to R.string.agent_command_todos_help,
                    "/details" to R.string.agent_command_details_help,
                    "/skills" to R.string.agent_command_skills_help,
                    "/agents" to R.string.agent_command_agents_help,
                    "/custom-agents" to R.string.agent_command_custom_agents_help,
                    "/custom-tools" to R.string.agent_command_custom_tools_help,
                    "/help" to R.string.agent_command_help_help
                ).forEach { (command, description) ->
                    AgentCommandDialogRow(
                        command = command,
                        description = stringResource(description),
                        onClick = { onCommandSelected(command) }
                    )
                }
                HorizontalDivider()
                Text(stringResource(R.string.agent_commands_workflow_heading), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.agent_commands_workflow_busy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    "/plan" to R.string.agent_command_plan_help,
                    "/build" to R.string.agent_command_build_help,
                    "/compact" to R.string.agent_command_compact_help
                ).forEach { (command, description) ->
                    AgentCommandDialogRow(
                        command = command,
                        description = stringResource(description),
                        onClick = { onCommandSelected(command) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } }
    )
}

@Composable
private fun AgentCommandDialogRow(
    command: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(command, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AgentPendingQuestionPanel(
    pendingQuestion: AgentPendingQuestionEntity,
    onSubmit: (String) -> Unit,
    onDraftChanged: (String, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val spec = remember(pendingQuestion.specificationJson) {
        runCatching { questionSpecFromJson(pendingQuestion.specificationJson) }.getOrNull()
    }
    if (spec == null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Text(
                stringResource(R.string.agent_question_invalid),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.error
            )
        }
        return
    }
    QuestionSpecPanel(
        spec = spec,
        stateKey = pendingQuestion.id,
        initialDraftJson = pendingQuestion.draftAnswerJson,
        initialPage = pendingQuestion.currentPage,
        initialCollapsed = pendingQuestion.isCollapsed,
        onSubmit = onSubmit,
        onDraftChanged = onDraftChanged,
        modifier = modifier
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun QuestionSpecPanel(
    spec: QuestionSpec,
    stateKey: String,
    initialDraftJson: String,
    initialPage: Int,
    initialCollapsed: Boolean,
    onSubmit: (String) -> Unit,
    onDraftChanged: (String, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val restoredDraft = remember(stateKey) { decodeQuestionDraft(initialDraftJson, spec) }
    val selections = remember(stateKey) {
        mutableStateMapOf<String, Set<String>>().apply { putAll(restoredDraft.first) }
    }
    val customAnswers = remember(stateKey) {
        mutableStateMapOf<String, String>().apply { putAll(restoredDraft.second) }
    }
    var page by rememberSaveable(stateKey) {
        mutableStateOf(initialPage.coerceIn(0, spec.questions.lastIndex))
    }
    var collapsed by rememberSaveable(stateKey) { mutableStateOf(initialCollapsed) }
    var detailsOption by remember(spec) { mutableStateOf<QuestionOption?>(null) }
    val question = spec.questions[page.coerceIn(0, spec.questions.lastIndex)]
    val answered = selections[question.id].orEmpty().isNotEmpty() ||
        customAnswers[question.id].orEmpty().isNotBlank()
    val allAnswered = spec.questions.all { item ->
        selections[item.id].orEmpty().isNotEmpty() || customAnswers[item.id].orEmpty().isNotBlank()
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HelpOutline, contentDescription = null)
                Text(
                    stringResource(R.string.agent_questions_title),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    collapsed = !collapsed
                    onDraftChanged(buildQuestionAnswersJson(spec, selections, customAnswers), page, collapsed)
                }) {
                    Icon(
                        if (collapsed) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.agent_workflow_toggle_questions)
                    )
                }
            }
            if (collapsed) {
                Text(
                    stringResource(R.string.agent_workflow_questions_collapsed, page + 1, spec.questions.size),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(question.header, fontWeight = FontWeight.SemiBold)
                    Text(question.prompt, style = MaterialTheme.typography.bodyMedium)
                    question.options.take(3).forEach { option ->
                        val selected = option.id in selections[question.id].orEmpty()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        selections[question.id] = if (question.multiple) {
                                            selections[question.id].orEmpty().let {
                                                if (selected) it - option.id else it + option.id
                                            }
                                        } else {
                                            setOf(option.id)
                                        }
                                        onDraftChanged(
                                            buildQuestionAnswersJson(spec, selections, customAnswers),
                                            page,
                                            collapsed
                                        )
                                    },
                                    onLongClick = {
                                        if (!option.description.isNullOrBlank()) {
                                            detailsOption = option
                                        }
                                    }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (question.multiple) {
                                Checkbox(checked = selected, onCheckedChange = null)
                            } else {
                                RadioButton(selected = selected, onClick = null)
                            }
                            Column {
                                Text(option.label)
                            }
                        }
                    }
                    if (question.options.any { !it.description.isNullOrBlank() }) {
                        Text(
                            stringResource(R.string.agent_question_option_details_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = customAnswers[question.id].orEmpty(),
                        onValueChange = {
                            customAnswers[question.id] = it.take(2_000)
                            onDraftChanged(
                                buildQuestionAnswersJson(spec, selections, customAnswers),
                                page,
                                collapsed
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.agent_question_custom_answer)) },
                        minLines = 1,
                        maxLines = 4
                    )
                }
            }
            if (!collapsed) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        page = (page - 1).coerceAtLeast(0)
                        onDraftChanged(buildQuestionAnswersJson(spec, selections, customAnswers), page, collapsed)
                    }, enabled = page > 0) {
                        Text(stringResource(R.string.agent_workflow_back))
                    }
                    if (page < spec.questions.lastIndex) {
                        Button(onClick = {
                            page += 1
                            onDraftChanged(buildQuestionAnswersJson(spec, selections, customAnswers), page, collapsed)
                        }, enabled = answered) {
                            Text(stringResource(R.string.agent_workflow_next))
                        }
                    } else {
                        Button(
                            onClick = {
                                onSubmit(buildQuestionAnswersJson(spec, selections, customAnswers))
                            },
                            enabled = allAnswered
                        ) {
                            Text(stringResource(R.string.agent_question_submit))
                        }
                    }
                }
            }
        }
    }
    detailsOption?.let { option ->
        AlertDialog(
            onDismissRequest = { detailsOption = null },
            title = { Text(option.label) },
            text = {
                Text(
                    option.description.orEmpty(),
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { detailsOption = null }) {
                    Text(stringResource(R.string.agent_question_details_close))
                }
            }
        )
    }
}

private fun buildQuestionAnswersJson(
    spec: QuestionSpec,
    selections: Map<String, Set<String>>,
    customAnswers: Map<String, String>
): String {
    val answers = JSONObject()
    spec.questions.forEach { item ->
        answers.put(
            item.id,
            JSONObject().apply {
                put("selected", JSONArray(selections[item.id].orEmpty().sorted()))
                put("custom", customAnswers[item.id].orEmpty().trim())
            }
        )
    }
    return JSONObject().put("answers", answers).toString()
}

private fun decodeQuestionDraft(
    json: String,
    spec: QuestionSpec
): Pair<Map<String, Set<String>>, Map<String, String>> {
    val selections = linkedMapOf<String, Set<String>>()
    val custom = linkedMapOf<String, String>()
    runCatching {
        val answers = JSONObject(json).optJSONObject("answers") ?: return@runCatching
        spec.questions.forEach { item ->
            val answer = answers.optJSONObject(item.id) ?: return@forEach
            val selected = answer.optJSONArray("selected")
            selections[item.id] = buildSet {
                if (selected != null) {
                    for (index in 0 until selected.length()) {
                        selected.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            custom[item.id] = answer.optString("custom")
        }
    }
    return selections to custom
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTodoDialog(
    todos: List<AgentTodoEntity>,
    onClose: () -> Unit
) {
    var selectedFilter by rememberSaveable { mutableStateOf("ALL") }
    val visibleTodos = remember(todos, selectedFilter) {
        if (selectedFilter == "ALL") todos else todos.filter { it.status == selectedFilter }
    }
    val completed = todos.count { it.status == "COMPLETED" }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    actions = { com.example.llamadroid.ui.walkthrough.FeatureGuideAction() },
                    title = {
                        Column {
                            Text(stringResource(R.string.agent_todos_title))
                            Text(
                                stringResource(R.string.agent_todos_progress, completed, todos.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_close)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "ALL" to R.string.agent_todo_filter_all,
                            "PENDING" to R.string.agent_todo_filter_pending,
                            "IN_PROGRESS" to R.string.agent_todo_filter_in_progress,
                            "COMPLETED" to R.string.agent_todo_filter_completed
                        ).forEach { (filter, label) ->
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = { selectedFilter = filter },
                                label = { Text(stringResource(label)) }
                            )
                        }
                    }
                }
                if (visibleTodos.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Text(
                                stringResource(R.string.agent_todos_empty),
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(visibleTodos, key = { it.id }) { todo ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(Icons.Default.Checklist, contentDescription = null)
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        localizedTodoStatus(todo.status),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(todo.text, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun localizedTodoStatus(status: String): String = when (status) {
    "COMPLETED" -> stringResource(R.string.agent_todo_filter_completed)
    "IN_PROGRESS" -> stringResource(R.string.agent_todo_filter_in_progress)
    else -> stringResource(R.string.agent_todo_filter_pending)
}
