package com.example.llamadroid.tama.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.style.TextOverflow
import com.example.llamadroid.service.KvCacheKey
import com.example.llamadroid.service.NativeChatKvCacheStore
import com.example.llamadroid.tama.game.deleteTamaPetKvCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.LlamaPromptProcessingProgress
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.game.TamaContextUsage
import kotlinx.coroutines.launch

/** Prompt-processing line plus thin bar shown under the pet chat thinking indicator. */
@Composable
internal fun TamaPromptProgressLine(progress: LlamaPromptProcessingProgress, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(
                R.string.llama_prompt_processing_progress,
                progress.processed,
                progress.total,
                (progress.fraction * 100f).toInt(),
                progress.cached
            ),
            color = TamaMutedText,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth(),
            color = TamaDark,
            trackColor = TamaDark.copy(alpha = 0.2f)
        )
    }
}

/** Context details for the pet chat: usage, per-pet auto-compact switch and "Compact now". */
@Composable
internal fun TamaChatContextSheet(
    pet: TamaPet,
    usage: TamaContextUsage,
    generating: Boolean,
    settingsRepo: SettingsRepository,
    agentService: TamaAgentService,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val notice = rememberTamaNotice()
    val disabledIds by settingsRepo.tamaPetAutoCompactDisabledIds.collectAsState()
    var compacting by remember { mutableStateOf(false) }
    val doneText = stringResource(R.string.tama_chat_compact_done)

    TamaPopupDialog(
        title = stringResource(R.string.tama_chat_context_title),
        backgroundAsset = "tama/backgrounds/library_room.png",
        onDismissRequest = onDismiss,
        compact = true,
        bodyContent = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.tama_chat_context_usage, usage.usedTokens, usage.windowTokens, usage.percent),
                    color = TamaDark,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
                LinearProgressIndicator(
                    progress = { usage.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = TamaDark,
                    trackColor = TamaDark.copy(alpha = 0.2f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            stringResource(R.string.tama_chat_auto_compact_title),
                            fontSize = 12.sp,
                            color = TamaDark,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.tama_chat_auto_compact_desc),
                            fontSize = 11.sp,
                            color = TamaMutedText,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = pet.id !in disabledIds,
                        onCheckedChange = { settingsRepo.setTamaPetAutoCompactEnabled(pet.id, it) },
                        colors = tamaSwitchColors()
                    )
                }
                Button(
                    onClick = {
                        compacting = true
                        scope.launch {
                            try {
                                agentService.compactNow(pet)
                                notice.show(doneText)
                            } finally {
                                compacting = false
                            }
                        }
                    },
                    enabled = !generating && !compacting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TamaDark,
                        contentColor = TamaLight,
                        disabledContainerColor = TamaDark.copy(alpha = 0.35f),
                        disabledContentColor = TamaLight
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        stringResource(if (compacting) R.string.tama_chat_compact_working else R.string.tama_chat_compact_now),
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    stringResource(R.string.tama_chat_context_explain),
                    fontSize = 11.sp,
                    color = TamaMutedText,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        footerContent = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = TamaLight)
            }
        }
    )
}

/** Size and saved time of one pet's disk prompt cache, with a confirmed per-pet delete. */
@Composable
internal fun TamaPetCacheSection(petId: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var sizeBytes by remember { mutableLongStateOf(0L) }
    var savedAt by remember { mutableStateOf<Long?>(null) }
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(petId, tick) {
        withContext(Dispatchers.IO) {
            val store = NativeChatKvCacheStore(context.applicationContext)
            val key = KvCacheKey.Pet(petId)
            val size = store.sizeBytes(key)
            sizeBytes = size
            savedAt = if (size > 0) store.readMeta(key)?.savedAt else null
        }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Text(stringResource(R.string.tama_chat_pet_cache_title), fontSize = 12.sp, color = TamaDark, fontWeight = FontWeight.Bold)
    val sizeText = Formatter.formatShortFileSize(context, sizeBytes)
    val at = savedAt
    Text(
        text = when {
            sizeBytes <= 0L -> stringResource(R.string.native_chat_kv_cache_empty)
            at != null -> stringResource(
                R.string.native_chat_kv_cache_size_saved,
                sizeText,
                DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            )
            else -> stringResource(R.string.native_chat_kv_cache_size, sizeText)
        },
        fontSize = 11.sp,
        color = TamaMutedText,
        fontFamily = FontFamily.Monospace
    )
    Spacer(modifier = Modifier.height(4.dp))
    OutlinedButton(
        enabled = sizeBytes > 0L,
        onClick = { confirm = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            stringResource(R.string.tama_chat_pet_cache_delete),
            color = TamaDark,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.tama_chat_pet_cache_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.tama_chat_pet_cache_delete_message),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        deleteTamaPetKvCache(context.applicationContext, petId)
                        tick++
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
