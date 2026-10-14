package com.example.llamadroid.ui.ai.llama.servers

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.harness.HarnessBaseCacheRegistry
import com.example.llamadroid.service.KvBaseFailure
import com.example.llamadroid.service.KvBaseMissReason
import com.example.llamadroid.service.KvBasePrepareResult
import com.example.llamadroid.service.KvBaseUsage
import com.example.llamadroid.service.KvBaseUsageRegistry
import com.example.llamadroid.service.KvCacheKey
import com.example.llamadroid.service.NativeChatBasePrefix
import com.example.llamadroid.service.NativeChatKvCacheCoordinator
import com.example.llamadroid.service.NativeChatKvCacheStore
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.service.NativeChatToolRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Prepare caches" for one running local llama-server card: rebuilds the chat base cache (system
 * awareness + default tools of the server) and shows or clears the DeepSeek harness base. The
 * chat base only helps chats whose system prompt and tools equal the server defaults; the
 * coordinator also captures bases from real chats automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepareCachesSheet(server: UnifiedServer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { NativeChatKvCacheStore(context.applicationContext) }
    var refresh by remember { mutableIntStateOf(0) }
    var chatBytes by remember { mutableLongStateOf(0L) }
    var deepSeekBytes by remember { mutableLongStateOf(0L) }
    var hasDeepSeekInfo by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<KvBaseFailure?>(null) }
    var keptExisting by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(KvBaseUsage()) }
    var chatTotal by remember { mutableStateOf(KvBaseUsage()) }

    LaunchedEffect(refresh, server.rowId) {
        withContext(Dispatchers.IO) {
            val entity = server.rowId?.let { AppDatabase.getDatabase(context).llamaServerDao().getServerById(it) }
            val chatKey = entity?.let { chatBaseKey(context, it) }
            chatBytes = chatKey?.let { store.sizeBytes(it) } ?: 0L
            val registry = KvBaseUsageRegistry(context)
            usage = chatKey?.let { registry.get(it) } ?: KvBaseUsage()
            chatTotal = registry.chatTotal()
            val info = server.cardId?.let { HarnessBaseCacheRegistry.latest(context, it) }
            hasDeepSeekInfo = info != null
            deepSeekBytes = info?.let { store.sizeBytes(HarnessBaseCacheRegistry.baseKey(it.cardId, it.model, it.prefixHash)) } ?: 0L
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.kv_prepare_caches_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.kv_prepare_caches_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            BaseRow(
                title = stringResource(R.string.kv_prepare_chat_base),
                hint = stringResource(R.string.kv_prepare_chat_base_hint),
                status = if (working) {
                    stringResource(R.string.kv_prepare_working)
                } else if (chatBytes > 0L) {
                    stringResource(R.string.kv_prepare_status_ready, Formatter.formatShortFileSize(context, chatBytes))
                } else {
                    stringResource(R.string.kv_prepare_status_none)
                },
                actionLabel = stringResource(
                    if (chatBytes > 0L) R.string.kv_prepare_action_rebuild else R.string.kv_prepare_action
                ),
                actionEnabled = !working,
                onAction = {
                    working = true
                    failure = null
                    keptExisting = false
                    val force = chatBytes > 0L
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { prepareChatBase(context, server, force) }
                        when (result) {
                            is KvBasePrepareResult.Failed -> failure = result.reason
                            is KvBasePrepareResult.Ready -> keptExisting = result.reused
                        }
                        working = false
                        refresh++
                    }
                }
            )
            if (!working && chatBytes > 0L) {
                Text(
                    if (usage.uses > 0) {
                        stringResource(
                            R.string.kv_prepare_usage,
                            usage.uses,
                            DateUtils.getRelativeTimeSpanString(
                                usage.lastUsedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                            ).toString()
                        )
                    } else {
                        stringResource(R.string.kv_prepare_usage_never)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (chatTotal.uses > 0 || chatTotal.misses > 0) {
                val totalText = stringResource(R.string.kv_prepare_usage_total, chatTotal.uses, chatTotal.misses)
                val missText = chatTotal.lastMissReason?.let {
                    stringResource(R.string.kv_prepare_last_miss, stringResource(missReasonRes(it)))
                }
                Text(
                    listOfNotNull(totalText, missText).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (keptExisting) {
                Text(
                    stringResource(R.string.kv_prepare_kept_existing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            failure?.let { reason ->
                Text(
                    stringResource(failureRes(reason)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            HorizontalDivider()
            BaseRow(
                title = stringResource(R.string.kv_prepare_deepseek_base),
                hint = stringResource(R.string.kv_prepare_deepseek_base_hint),
                status = if (deepSeekBytes > 0L) {
                    stringResource(R.string.kv_prepare_status_ready, Formatter.formatShortFileSize(context, deepSeekBytes))
                } else {
                    stringResource(R.string.kv_prepare_status_none)
                },
                actionLabel = stringResource(R.string.kv_prepare_clear),
                actionEnabled = hasDeepSeekInfo && deepSeekBytes > 0L,
                onAction = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            server.cardId?.let { cardId ->
                                HarnessBaseCacheRegistry.latest(context, cardId)?.let {
                                    store.delete(HarnessBaseCacheRegistry.baseKey(it.cardId, it.model, it.prefixHash))
                                }
                                HarnessBaseCacheRegistry.clear(context, cardId)
                            }
                        }
                        refresh++
                    }
                }
            )
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.kv_prepare_close), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BaseRow(
    title: String,
    hint: String,
    status: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(enabled = actionEnabled, onClick = onAction) {
            Text(actionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun failureRes(reason: KvBaseFailure): Int = when (reason) {
    KvBaseFailure.UNSUPPORTED_ENGINE -> R.string.kv_prepare_fail_engine
    KvBaseFailure.NOT_LOCAL -> R.string.kv_prepare_fail_remote
    KvBaseFailure.SERVER_UNREACHABLE -> R.string.kv_prepare_fail_unreachable
    KvBaseFailure.SLOT_SAVE_DISABLED -> R.string.kv_prepare_fail_slot_save
    KvBaseFailure.NO_FREE_SLOT -> R.string.kv_prepare_fail_no_slot
    KvBaseFailure.NO_PREFIX -> R.string.kv_prepare_fail_no_prefix
    KvBaseFailure.BUILD_IN_PROGRESS -> R.string.kv_prepare_fail_in_progress
    KvBaseFailure.REQUEST_FAILED -> R.string.kv_prepare_fail_request
    KvBaseFailure.LOW_DISK -> R.string.kv_prepare_fail_disk
    KvBaseFailure.SAVE_FAILED -> R.string.kv_prepare_fail_save
}

private fun missReasonRes(reason: KvBaseMissReason): Int = when (reason) {
    KvBaseMissReason.NO_FILE -> R.string.kv_prepare_miss_no_file
    KvBaseMissReason.FINGERPRINT_MISMATCH -> R.string.kv_prepare_miss_fingerprint
    KvBaseMissReason.PREFIX_CHANGED -> R.string.kv_prepare_miss_prefix
    KvBaseMissReason.RESTORE_FAILED -> R.string.kv_prepare_miss_restore
}

/** Stable prefix of the server defaults: awareness text and default tool definitions. */
private fun chatBasePrefix(
    context: android.content.Context,
    entity: LlamaServerEntity
): Pair<String, List<com.example.llamadroid.service.AgentTool>> {
    val config = NativeChatToolConfig.fromApiParams(entity.defaultApiParams)
    val app = context.applicationContext
    // Same extensions as the chat service, so DeepSeek tools and awareness match a chat's prefix.
    val runtime = NativeChatToolRuntime(
        context = app,
        extensions = listOf(com.example.llamadroid.service.delegation.NativeChatDelegationTools(app))
    )
    val tools = runtime.availableTools(config)
    return NativeChatBasePrefix.stableText(config, runtime.extensionAwareness(config)) to tools
}

private fun chatBaseKey(context: android.content.Context, entity: LlamaServerEntity): KvCacheKey.Base {
    val (stable, tools) = chatBasePrefix(context, entity)
    return NativeChatBasePrefix.key(entity.baseUrl(), entity.modelName?.takeIf { it.isNotBlank() }.orEmpty(), stable, tools)
}

/**
 * Builds the chat base with the same prefix function the chats use. A valid base is kept unless
 * [force]; the result carries the size or the reason it could not be built.
 */
private suspend fun prepareChatBase(
    context: android.content.Context,
    server: UnifiedServer,
    force: Boolean
): KvBasePrepareResult {
    val entity = server.rowId?.let { AppDatabase.getDatabase(context).llamaServerDao().getServerById(it) }
        ?: return KvBasePrepareResult.Failed(KvBaseFailure.SERVER_UNREACHABLE)
    if (!entity.isLlamaServerEngine()) return KvBasePrepareResult.Failed(KvBaseFailure.UNSUPPORTED_ENGINE)
    val (stable, tools) = chatBasePrefix(context, entity)
    if (stable.isBlank() && tools.isEmpty()) return KvBasePrepareResult.Failed(KvBaseFailure.NO_PREFIX)
    val key = NativeChatBasePrefix.key(entity.baseUrl(), entity.modelName?.takeIf { it.isNotBlank() }.orEmpty(), stable, tools)
    return NativeChatKvCacheCoordinator(context).prepareBase(
        server = entity,
        key = key,
        force = force,
        send = NativeChatBasePrefix.prefixSender(entity.baseUrl(), entity.modelName, stable, tools)
    )
}
