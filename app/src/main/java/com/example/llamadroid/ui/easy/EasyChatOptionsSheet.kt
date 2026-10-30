package com.example.llamadroid.ui.easy

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.easy.EasyContextChoice
import com.example.llamadroid.data.easy.EasyContextOverrideEvaluation
import com.example.llamadroid.data.easy.EasyContextSizer
import com.example.llamadroid.data.easy.EasyModeSettings
import com.example.llamadroid.data.repository.EasyLlamaChatCoordinator
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.util.GGUFParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Conversation-memory choices offered besides Auto; capped at the model's trained context. */
internal val EASY_MEMORY_CHOICES = listOf(8192, 16384, 32768)

internal fun easyContextLabel(ctx: Int): String = "${ctx / 1024}K"

/** Chips shown after Auto: the fixed choices that do not exceed [trainedCtx] (null = unknown, all shown). */
internal fun easyMemoryChoices(trainedCtx: Int?): List<Int> =
    EASY_MEMORY_CHOICES.filter { trainedCtx == null || trainedCtx <= 0 || it <= trainedCtx }

private class EasyContextInfo(
    val profile: LlamaServerLaunchProfile,
    val mmprojBytes: Long,
    val trained: Int?,
    val recommended: EasyContextChoice
)

/**
 * Easy chat options: web search, "remember this chat", conversation memory (context) and
 * rename/delete. The sheet scrolls so it stays usable on small phones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EasyChatOptionsSheet(
    cardId: Long?,
    webSearchEnabled: Boolean,
    onWebSearchChange: (Boolean) -> Unit,
    rememberChat: Boolean,
    onRememberChatChange: (Boolean) -> Unit,
    busy: Boolean,
    onContextApplied: (Int) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** Starts the floating chat bubble; null hides the button (e.g. inside the floating window). */
    onBubbleChat: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { EasyModeSettings(context) }
    val coordinator = remember { EasyLlamaChatCoordinator(context) }
    val sizer = remember { EasyContextSizer(context) }
    var confirmDelete by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    var saved by remember { mutableIntStateOf(0) }

    val info by produceState<EasyContextInfo?>(null, cardId) {
        value = withContext(Dispatchers.IO) {
            val profile = cardId?.let { coordinator.profileForCard(it) } ?: return@withContext null
            val mmproj = profile.mmprojPath?.let(::File)?.takeIf { it.isFile }?.length() ?: 0L
            val trained = GGUFParser.readModelInfo(profile.modelPath)
                ?.takeIf { it.contextLengthDetected }?.contextLength
            EasyContextInfo(profile, mmproj, trained, sizer.recommend(profile.modelPath, mmproj))
        }
    }
    LaunchedEffect(info) {
        info?.let {
            saved = settings.contextOverrideFor(it.profile.modelPath)
            selected = saved
        }
    }
    val evaluation by produceState<EasyContextOverrideEvaluation?>(null, info, selected) {
        val current = info
        value = if (current == null || selected <= 0) null else withContext(Dispatchers.IO) {
            sizer.evaluate(current.profile.modelPath, selected, current.mmprojBytes)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                stringResource(R.string.easy_chat_opts_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            EasyServerStatusRow(cardId)
            OptionSwitch(
                title = stringResource(R.string.easy_chat_opts_web_search),
                hint = stringResource(R.string.easy_chat_opts_web_search_hint),
                checked = webSearchEnabled,
                onChange = onWebSearchChange
            )
            OptionSwitch(
                title = stringResource(R.string.easy_chat_opts_remember),
                hint = stringResource(R.string.easy_chat_opts_remember_hint),
                checked = rememberChat,
                onChange = onRememberChatChange
            )
            info?.let { current ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.easy_chat_opts_memory),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        stringResource(R.string.easy_chat_opts_memory_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selected == 0,
                            onClick = { selected = 0 },
                            enabled = !applying,
                            label = {
                                Text(
                                    stringResource(
                                        R.string.easy_chat_opts_memory_auto,
                                        easyContextLabel(current.recommended.contextSize)
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        easyMemoryChoices(current.trained).forEach { choice ->
                            FilterChip(
                                selected = selected == choice,
                                onClick = { selected = choice },
                                enabled = !applying,
                                label = { Text(easyContextLabel(choice), maxLines = 1) }
                            )
                        }
                    }
                    if (evaluation?.exceedsRecommendation == true || evaluation?.fitsBudget == false) {
                        Text(
                            stringResource(R.string.easy_chat_opts_memory_warning,
                                easyContextLabel(current.recommended.contextSize)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (selected != saved) {
                        Button(
                            enabled = !applying && !busy,
                            onClick = {
                                applying = true
                                applyError = false
                                scope.launch {
                                    try {
                                        val ready = coordinator.applyContext(requireNotNull(cardId), selected)
                                        saved = selected
                                        ready.contextTokens?.let(onContextApplied)
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (_: Throwable) {
                                        applyError = true
                                    } finally {
                                        applying = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    if (applying) R.string.easy_chat_opts_memory_applying
                                    else R.string.easy_chat_opts_memory_apply
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (busy) {
                            Text(
                                stringResource(R.string.easy_chat_opts_memory_busy),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (applyError) {
                        Text(
                            stringResource(R.string.easy_chat_opts_memory_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            if (onBubbleChat != null) {
                OutlinedButton(onClick = { onDismiss(); onBubbleChat() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chat_bubble_menu_item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.easy_chat_opts_rename), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.easy_chat_opts_delete),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.easy_chat_opts_delete_title)) },
            text = { Text(stringResource(R.string.easy_chat_opts_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun OptionSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
