package com.example.llamadroid.ui.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.isBackgroundCommandReminder
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import androidx.compose.ui.text.input.ImeAction
import com.example.llamadroid.ui.ai.llama.MarkdownText
import coil.compose.AsyncImage
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
private fun agentRoleLabel(roleName: String): String {
    return when (roleName.uppercase()) {
        "ORCHESTRATOR" -> stringResource(R.string.agent_role_orchestrator)
        "CODER" -> stringResource(R.string.agent_role_coder)
        "REVIEWER" -> stringResource(R.string.agent_role_reviewer)
        "EXECUTOR" -> stringResource(R.string.agent_role_executor)
        "SUMMARIZER" -> stringResource(R.string.agent_role_summarizer)
        else -> roleName
    }
}

private fun formatAgentMessageTimestamp(timestamp: Long, locale: Locale = Locale.getDefault()): String {
    val nowCalendar = Calendar.getInstance()
    val messageCalendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val isSameDay = nowCalendar.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) &&
        nowCalendar.get(Calendar.DAY_OF_YEAR) == messageCalendar.get(Calendar.DAY_OF_YEAR)
    val pattern = if (isSameDay) "HH:mm" else "MMM dd HH:mm"
    return SimpleDateFormat(pattern, locale).format(Date(timestamp))
}

@Composable
fun AgentChatList(
    messages: List<AgentService.Companion.ChatMessage>,
    listState: LazyListState,
    showAllOutput: Boolean,
    onApprove: (AgentService.Companion.ChatMessage) -> Unit,
    onDeny: (AgentService.Companion.ChatMessage) -> Unit,
    onDelete: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    editingMessageId: String?,
    editingText: String,
    onEditingTextChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onToggleOutput: (String) -> Unit, // New callback
    onKnowledgeLinkClick: (String) -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    val visibleMessages = remember(messages, showAllOutput) {
        if (showAllOutput) {
            messages
        } else {
            val assistantTrackedToolCalls = messages
                .asSequence()
                .filter { it.role == "assistant" && it.toolCallId != null && it.toolName != null }
                .mapNotNull { it.toolCallId }
                .toSet()

            messages.filter { msg ->
                when {
                    msg.role == "system" -> msg.content.contains("ready") || AgentService.isTransientCompactionStatusMessageForUi(msg)
                    !showAllOutput && isBackgroundCommandReminder(msg.toolName, msg.content, msg.toolOutput) -> false
                    msg.role == "tool" && msg.toolCallId != null && assistantTrackedToolCalls.contains(msg.toolCallId) -> false
                    else -> true
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(16.dp)
    ) {
        items(visibleMessages, key = { it.id }) { message ->
            ChatMessageBubble(
                message = message,
                onApprove = { onApprove(message) },
                onDeny = { onDeny(message) },
                onDelete = { onDelete(message.id) },
                onRegenerate = { onRegenerate(message.id) },
                onEdit = { onEdit(message.id, message.content) },
                isEditing = editingMessageId == message.id,
                editingText = editingText,
                onEditingTextChange = onEditingTextChange,
                onSaveEdit = onSaveEdit,
                onCancelEdit = onCancelEdit,
                onToggleOutput = { onToggleOutput(message.id) },
                onKnowledgeLinkClick = onKnowledgeLinkClick
            )
        }
    }
}

@Composable
fun ChatMessageBubble(
    message: AgentService.Companion.ChatMessage,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    isEditing: Boolean = false,
    editingText: String = "",
    onEditingTextChange: (String) -> Unit = {},
    onSaveEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onToggleOutput: () -> Unit = {},
    onKnowledgeLinkClick: (String) -> Boolean = { false }
) {
    val currentStatusText by AgentService.statusText.collectAsState()
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val isSystem = message.role == "system"
    val isAssistant = message.role == "assistant"
    val isDelegation = message.isDelegation
    val isCompactionStatus = AgentService.isTransientCompactionStatusMessageForUi(message)
    val formattedTimestamp = remember(message.timestamp) { formatAgentMessageTimestamp(message.timestamp) }
    val imageFile = remember(message.imagePath) { message.imagePath?.let(::File)?.takeIf { it.exists() } }
    var showImagePreview by remember(message.imagePath) { mutableStateOf(false) }
    val toolPreviewImagePath = remember(message.toolOutput) {
        message.toolOutput
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("PREVIEW_IMAGE_PATH:") }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
    val toolPreviewImageFile = remember(toolPreviewImagePath) {
        toolPreviewImagePath?.let(::File)?.takeIf { it.exists() }
    }
    
    var delegationExpanded by remember { mutableStateOf(false) }
    
    val textColor = when {
        isUser -> MaterialTheme.colorScheme.onPrimary
        isCompactionStatus -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    val elevation = if (isUser) 1.dp else 2.dp
    val borderStroke = if (!isUser) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) else null
    
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        
        Card(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .then(if (message.isStreaming) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(20.dp)) else Modifier)
                .then(if (isDelegation) Modifier.clickable { delegationExpanded = !delegationExpanded } else Modifier),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isUser -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
                    isCompactionStatus -> Color(0xFF2E7D32).copy(alpha = 0.92f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                }
            ),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 20.dp
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
            border = borderStroke
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (isDelegation) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.agent_delegation_title), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Icon(if (delegationExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
                    }
                    if (delegationExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                if (isDelegation && !delegationExpanded) {
                    // Hidden
                } else if (message.needsApproval) {
                    val title = when (message.toolName) {
                        "write_file" -> stringResource(R.string.agent_approve_file_title)
                        "run_command" -> stringResource(R.string.agent_approve_cmd_title)
                        "edit_lines" -> stringResource(R.string.agent_approve_edit_title)
                        else -> stringResource(R.string.agent_approve_generic_title, message.toolName ?: "Tool")
                    }
                    Text(text = title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (message.content.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = message.content,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = Color.Black.copy(alpha = 0.05f), 
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    ) {
                        SelectionContainer {
                            Column(modifier = Modifier.padding(10.dp)) {
                                when (message.toolName) {
                                    "write_file", "edit_lines" -> {
                                        Text(stringResource(R.string.agent_path_label, message.toolArgs?.get("path") ?: ""), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        
                                        val content = if (message.toolName == "write_file") {
                                            message.toolArgs?.get("content")
                                        } else {
                                            "Lines: ${message.toolArgs?.get("start_line")} - ${message.toolArgs?.get("end_line")}\n\n${message.toolArgs?.get("new_content")}"
                                        }
                                        Text(text = content ?: "", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                    }
                                    "run_command" -> {
                                        Text(text = message.toolArgs?.get("command") ?: "", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                    }
                                    else -> {
                                        // Generic tool args preview
                                        val argsString = message.toolArgs?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: ""
                                        Text(text = argsString.ifEmpty { stringResource(R.string.agent_no_args) }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onDeny, 
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), 
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.action_deny), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = onApprove, 
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), 
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.action_allow), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                } else {
                    // Thought process
                    ThinkingBlock(message = message)

                    if (imageFile != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clickable { showImagePreview = true }
                        ) {
                            AsyncImage(
                                model = imageFile,
                                contentDescription = stringResource(R.string.agent_image_attachment_title),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 140.dp, max = 260.dp)
                            )
                        }
                    }

                    // Tool Call block
                    message.toolName?.let { tool ->
                        val isExpanded = message.isOutputExpanded || (message.isPlan && message.isPlanApproved != true)
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onToggleOutput() }
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (message.isPlan) stringResource(R.string.agent_plan_title) else stringResource(R.string.agent_tool_call_title, tool),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                    
                                }
                                
                                AnimatedVisibility(
                                    visible = isExpanded,
                                    enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)),
                                    exit = shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy))
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        ChatMessageContent(
                                            message = message,
                                            isEditing = isEditing,
                                            editingText = editingText,
                                            onEditingTextChange = onEditingTextChange,
                                            onCancelEdit = onCancelEdit,
                                            onSaveEdit = onSaveEdit,
                                            textColor = MaterialTheme.colorScheme.onSurface,
                                            onKnowledgeLinkClick = onKnowledgeLinkClick
                                        )
                                        
                                        // Terminal Output (if visible)
                                        if (message.isTerminalVisible) {
                                            TerminalView(message, onInput = { input -> 
                                                AgentService.sendTerminalInput(message.id, input)
                                            })
                                        }
                                        
                                        // Tool Results (legacy/finished)
                                        message.toolOutput?.let { output ->
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (toolPreviewImageFile != null) {
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { showImagePreview = true }
                                                    ) {
                                                        AsyncImage(
                                                            model = toolPreviewImageFile,
                                                            contentDescription = stringResource(R.string.agent_image_attachment_title),
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .heightIn(min = 160.dp, max = 260.dp)
                                                        )
                                                    }
                                                }
                                                Surface(
                                                    color = Color.Black.copy(alpha = 0.05f),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    SelectionContainer {
                                                        Text(
                                                            text = output.replaceFirst(Regex("(?m)^PREVIEW_IMAGE_PATH:.*$"), "").trim(),
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 11.sp,
                                                            modifier = Modifier.padding(8.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Action Row for Plans (at bottom)
                                        if (message.isPlan && message.isPlanApproved != true && !isEditing) {
                                            Spacer(modifier = Modifier.height(16.dp))
                                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Button(
                                                    onClick = onEdit,
                                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                                    modifier = Modifier.weight(1f).height(40.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                                                ) {
                                                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(stringResource(R.string.agent_modify_plan), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Button(
                                                    onClick = onApprove,
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                                    modifier = Modifier.weight(1f).height(40.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                                                ) {
                                                    Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp), tint = Color.White)
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(stringResource(R.string.action_approve), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (message.toolName == null) {
                        ChatMessageContent(
                            message = message,
                            isEditing = isEditing,
                            editingText = editingText,
                            onEditingTextChange = onEditingTextChange,
                            onCancelEdit = onCancelEdit,
                            onSaveEdit = onSaveEdit,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            onKnowledgeLinkClick = onKnowledgeLinkClick
                        )
                    }
                    
                    if (message.isStreaming) {
                        val streamingMessageId by AgentService.streamingMessageId.collectAsState()
                        val isTargeted = streamingMessageId == message.id
                        
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            if (isTargeted) {
                                TypingIndicator()
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(currentStatusText, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
        
        if (!message.isStreaming) {
            val roleLabel = when {
                isUser -> stringResource(R.string.agent_user_label)
                isTool -> stringResource(R.string.agent_tool_label, message.toolName ?: "Tool")
                isSystem -> stringResource(R.string.agent_system_label)
                message.customAgentName != null -> stringResource(R.string.agent_custom_agent_label, message.customAgentName)
                message.agentRole != null -> stringResource(R.string.agent_role_label, agentRoleLabel(message.agentRole))
                else -> stringResource(R.string.agent_assistant_label)
            }
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$formattedTimestamp · $roleLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        // ========== Action Row Below Bubble (like Llama Native) ==========
        if (!message.isStreaming && !isSystem) {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    stringResource(R.string.action_copy),
                    modifier = Modifier.size(14.dp).clickable {
                        val clip = ClipData.newPlainText(context.getString(R.string.clipboard_label_message), message.content)
                        clipboardManager.setPrimaryClip(clip)
                    },
                    tint = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.width(8.dp))

                if ((isUser || isAssistant || message.isPlan) && !isEditing) {
                    Icon(
                        Icons.Default.Edit,
                        stringResource(R.string.action_edit),
                        modifier = Modifier.size(14.dp).clickable { onEdit() },
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                if (isAssistant) {
                    Icon(
                        Icons.Default.Refresh,
                        stringResource(R.string.action_regenerate),
                        modifier = Modifier.size(14.dp).clickable { onRegenerate() },
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Icon(
                    Icons.Default.Delete,
                    stringResource(R.string.action_delete),
                    modifier = Modifier.size(14.dp).clickable { onDelete() },
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    if (showImagePreview && (imageFile != null || toolPreviewImageFile != null)) {
        Dialog(onDismissRequest = { showImagePreview = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AsyncImage(
                        model = imageFile ?: toolPreviewImageFile,
                        contentDescription = stringResource(R.string.agent_image_attachment_title),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 520.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { showImagePreview = false }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalView(
    message: AgentService.Companion.ChatMessage,
    onInput: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(Color.Black, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        // Terminal Window
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
        ) {
            val scrollState = rememberScrollState()
            LaunchedEffect(message.terminalOutput) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            
            SelectionContainer {
                Text(
                    text = message.terminalOutput ?: "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF4CAF50), // Classic terminal green
                    modifier = Modifier.verticalScroll(scrollState)
                )
            }
        }
        
        // Input Line
        HorizontalDivider(color = Color.White.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$ ",
                color = Color(0xFF4CAF50),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            BasicTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (inputText.isNotBlank()) {
                        onInput(inputText)
                        inputText = ""
                    }
                })
            )
        }
    }
}

@Composable
fun ChatMessageContent(
    message: AgentService.Companion.ChatMessage,
    isEditing: Boolean,
    editingText: String,
    onEditingTextChange: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    textColor: Color,
    onKnowledgeLinkClick: (String) -> Boolean = { false }
) {
    if (isEditing) {
        Column {
            OutlinedTextField(
                value = editingText,
                onValueChange = onEditingTextChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancelEdit) { Text(stringResource(R.string.action_cancel), color = textColor) }
                TextButton(onClick = onSaveEdit) { Text(stringResource(R.string.action_save), color = textColor) }
            }
        }
    } else {
        Column {
            if (message.toolName != null && !message.needsApproval) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.agent_running_tool, message.toolName ?: ""), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            if (message.isStreaming) {
                val streamingMessageId by AgentService.streamingMessageId.collectAsState()
                val isTargeted = streamingMessageId == message.id
                
                if (isTargeted) {
                    val streamingContent by AgentService.streamingContent.collectAsState()
                    val textToDisplay = streamingContent.ifEmpty { message.content }
                    if (textToDisplay.isNotBlank()) {
                        MarkdownText(
                            text = textToDisplay,
                            textColor = textColor,
                            modifier = Modifier.fillMaxWidth(),
                            onLinkClick = onKnowledgeLinkClick
                        )
                    }
                } else {
                    // Show whatever content it has, but don't collect the global stream
                    if (message.content.isNotBlank()) {
                        MarkdownText(
                            text = message.content,
                            textColor = textColor,
                            modifier = Modifier.fillMaxWidth(),
                            onLinkClick = onKnowledgeLinkClick
                        )
                    }
                }
            } else {
                if (message.content.isNotBlank()) {
                    MarkdownText(
                        text = message.content,
                        textColor = textColor,
                        modifier = Modifier.fillMaxWidth(),
                        onLinkClick = onKnowledgeLinkClick
                    )
                }
            }
        }
    }
}

@Composable
fun ThinkingBlock(message: AgentService.Companion.ChatMessage) {
    if (message.isStreaming) {
        val streamingMessageId by AgentService.streamingMessageId.collectAsState()
        val isTargeted = streamingMessageId == message.id
        
        if (isTargeted) {
            val streamingThinking by AgentService.streamingThinking.collectAsState()
            val text = streamingThinking.ifEmpty { message.thinking ?: "" }
            if (text.isNotBlank()) {
                ThinkingBlockContent(text = text, messageId = message.id, isStreaming = true)
            }
        } else {
            val text = message.thinking ?: ""
            if (text.isNotBlank()) {
                ThinkingBlockContent(text = text, messageId = message.id, isStreaming = false)
            }
        }
    } else {
        val text = message.thinking ?: ""
        if (text.isNotBlank()) {
            ThinkingBlockContent(text = text, messageId = message.id, isStreaming = false)
        }
    }
}

@Composable
fun ThinkingBlockContent(text: String, messageId: String, isStreaming: Boolean = false) {
    var thinkingExpanded by remember(messageId) { mutableStateOf(false) }
    
    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    val bgColor = if (isStreaming) {
        MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.clickable { thinkingExpanded = !thinkingExpanded }.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (thinkingExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, 
                    null, 
                    modifier = Modifier.size(16.dp), 
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(R.string.agent_thinking_process), 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }
            AnimatedVisibility(
                visible = thinkingExpanded,
                enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)),
                exit = shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy))
            ) {
                Text(
                    text = text, 
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), 
                    fontSize = 12.sp, 
                    fontStyle = FontStyle.Italic, 
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                )
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition()
    
    @Composable
    fun Dot(delay: Int) {
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, delayMillis = delay, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), RoundedCornerShape(2.dp))
        )
    }
    
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Dot(0)
        Dot(150)
        Dot(300)
    }
}
