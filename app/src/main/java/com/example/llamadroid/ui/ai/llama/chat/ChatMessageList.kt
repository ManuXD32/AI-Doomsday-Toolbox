package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.KnowledgeSourceEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.service.LlamaCallUiState
import com.example.llamadroid.service.LlamaClientService
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyListState
import com.example.llamadroid.ui.ai.llama.MarkdownText

/**
 * Message list with streaming bubble, completion stats, Continue, scroll-to-bottom button and the
 * call overlay. Rendering stays bounded: each message is a lazy item and the streaming bubble is a
 * single item.
 */
@Composable
internal fun ChatMessageList(
    listState: LazyListState,
    displayedMessages: List<LlamaMessageEntity>,
    isExtractingDocument: Boolean,
    chatDocumentSources: List<KnowledgeSourceEntity>,
    streamingGenerationState: LlamaClientService.GenerationState.Generating?,
    completedGenerationState: LlamaClientService.GenerationState.Completed?,
    canContinueChat: Boolean,
    isGeneratingAnyChat: Boolean,
    hasActiveServer: Boolean,
    userWantsAutoScroll: Boolean,
    onUserWantsAutoScrollChange: (Boolean) -> Unit,
    isCallActiveForChat: Boolean,
    callState: LlamaCallUiState,
    onHangUp: () -> Unit,
    assistantTtsEnabled: Boolean,
    openedAtMs: Long,
    autoPlayedAssistantAudioPaths: MutableList<String>,
    onKnowledgeLinkClick: (String) -> Boolean,
    onRegenerate: (LlamaMessageEntity) -> Unit,
    onEditMessage: (LlamaMessageEntity, String) -> Unit,
    onRetryMessage: (LlamaMessageEntity) -> Unit,
    onRetryTranscription: (LlamaMessageEntity) -> Unit,
    onDiscardFailedMessage: (LlamaMessageEntity) -> Unit,
    onDeleteMessage: (LlamaMessageEntity) -> Unit,
    onOpenToolActivity: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (isExtractingDocument || chatDocumentSources.isNotEmpty()) {
                item(key = "chat_document_knowledge_status") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isExtractingDocument) {
                            DocumentExtractionPending()
                        }
                        if (chatDocumentSources.isNotEmpty()) {
                            ChatDocumentKnowledgeCard(sources = chatDocumentSources)
                        }
                    }
                }
            }

            items(displayedMessages, key = { it.id }) { msg ->
                LlamaMessageItem(
                    message = msg,
                    onRegenerate = { onRegenerate(msg) },
                    onEdit = { newContent -> onEditMessage(msg, newContent) },
                    onRetry = { onRetryMessage(msg) },
                    retryEnabled = !isGeneratingAnyChat && !msg.isError && hasActiveServer,
                    onRetryTranscription = { onRetryTranscription(msg) },
                    onDiscardFailedMessage = { onDiscardFailedMessage(msg) },
                    onDelete = { onDeleteMessage(msg) },
                    autoPlayAssistantAudio = msg.role == "assistant" &&
                        assistantTtsEnabled &&
                        !isCallActiveForChat &&
                        msg.timestamp >= openedAtMs &&
                        msg.audioPath?.takeIf { it.isNotBlank() } !in autoPlayedAssistantAudioPaths,
                    onAssistantAudioAutoPlayed = { path ->
                        if (path !in autoPlayedAssistantAudioPaths) {
                            autoPlayedAssistantAudioPaths.add(path)
                        }
                    },
                    onKnowledgeLinkClick = onKnowledgeLinkClick
                )
            }

        // Active Generation Indicator
            streamingGenerationState?.let { genState ->
                item {
                    Column(modifier = Modifier.padding(8.dp)) {
                        if (!genState.thinking.isNullOrBlank()) {
                            ThinkingMessageContent(
                                genState.thinking,
                                genState.content,
                                forceExpand = true,
                                onKnowledgeLinkClick = onKnowledgeLinkClick
                            )
                        } else if (genState.content.isNotBlank()) {
                            MarkdownText(
                                text = genState.content,
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                onLinkClick = onKnowledgeLinkClick
                            )
                        }

                        genState.videoProgress?.let { videoProgress ->
                            Column(
                                modifier = Modifier.padding(top = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                LinearProgressIndicator(
                                    progress = { videoProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                genState.videoVisualPhase
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { phase ->
                                        Text(
                                            stringResource(
                                                R.string.video_recognition_visual_phase,
                                                phase
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                genState.videoAudioPhase
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { phase ->
                                        Text(
                                            stringResource(
                                                R.string.video_recognition_audio_phase,
                                                phase
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = genState.statusText ?: if (genState.tokenCount > 0) {
                                    stringResource(
                                        R.string.llama_stream_stats,
                                        genState.tokenCount,
                                        genState.tokensPerSecond
                                    )
                                } else {
                                    stringResource(R.string.llama_thinking)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (genState.toolEvents.isNotEmpty()) {
                                TextButton(
                                    onClick = onOpenToolActivity,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.llama_tool_activity_open),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                        genState.promptProgress?.let { promptProgress ->
                            LinearProgressIndicator(
                                progress = { promptProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                            )
                        }
                    }
                }
            }

            // Completed stats
            completedGenerationState?.let { compState ->
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✓ ",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (compState.promptTokens > 0) {
                                stringResource(
                                    R.string.llama_completion_stats_with_prompt,
                                    compState.completionTokens,
                                    compState.tokensPerSecond,
                                    compState.promptTokens
                                )
                            } else {
                                stringResource(
                                    R.string.llama_completion_stats,
                                    compState.completionTokens,
                                    compState.tokensPerSecond
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // Continue button – shown when NOT generating and last message is from assistant
            if (canContinueChat) {
                item {
                    TextButton(
                        onClick = onContinue,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.llama_continue))
                    }
                }
            }
        }

        // Scroll-to-bottom FAB
        if (!userWantsAutoScroll) {
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        if (listState.layoutInfo.totalItemsCount > 0) {
                            listState.animateScrollToItem(
                                index = listState.layoutInfo.totalItemsCount - 1,
                                scrollOffset = Int.MAX_VALUE
                            )
                        }
                        onUserWantsAutoScrollChange(true)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.llama_scroll_to_bottom))
            }
        }
        if (isCallActiveForChat) {
            LlamaCallOverlay(
                state = callState,
                onHangUp = onHangUp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
