package com.example.llamadroid.ui.ai.llama.chat

import androidx.core.content.edit
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.data.model.LlamaMessageAttachmentSource
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.NativeChatCompactionEntity
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.ui.ai.llama.chatlist.defaultPersistKvCacheForServer
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.harness.HarnessProjectFilesNavigator
import com.example.llamadroid.harness.HarnessRuntimeContent
import com.example.llamadroid.service.NativeChatWorkspaceSupport
import com.example.llamadroid.service.delegation.NativeChatDelegation
import com.example.llamadroid.service.titlegen.TitleGenerationSettings
import com.example.llamadroid.service.vision.VisionHelperSettings
import com.example.llamadroid.ui.settings.TitleGenerationSheet
import com.example.llamadroid.ui.agent.harness.HarnessRootfsFileBrowser
import com.example.llamadroid.ui.components.AppTextDetailsDialog
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------------------------------
// Top bar actions (folder menu + delegation viewer icon)
// ---------------------------------------------------------------------------------------------

/** Inputs for the delegation actions in [ChatTopBar]; null when the DeepSeek tool is off for the chat. */
internal class ChatDelegationBar(
    val chatId: Long,
    val configuredRuntimeId: String?,
    val navController: NavController
)

/** Latest DeepSeek task of the chat (null while none exists). */
@Composable
internal fun rememberLatestDelegationTask(chatId: Long): State<NativeChatDelegateTaskEntity?> {
    val context = LocalContext.current
    val flow = remember(chatId) { NativeChatDelegation.get(context).observeLatestTask(chatId) }
    return flow.collectAsState(initial = null)
}

internal val ATTENTION_TASK_STATES: Set<String> = setOf(
    NativeChatDelegateTaskState.NEEDS_DIRECTION,
    NativeChatDelegateTaskState.INTERRUPTED,
    NativeChatDelegateTaskState.WAITING_MAIN
)

/** Folder + delegation-viewer icons for the top bar; observes the latest task once for both. */
@Composable
internal fun DelegationTopBarActions(bar: ChatDelegationBar) {
    val task by rememberLatestDelegationTask(bar.chatId)
    DelegationFilesAction(bar, task)
    DelegationViewerAction(bar, task)
}

/** Folder icon: "Project files" (latest task's workspace) and "Runtime files" (full-screen browser). */
@Composable
internal fun DelegationFilesAction(bar: ChatDelegationBar, task: NativeChatDelegateTaskEntity?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var showRuntimeFiles by remember { mutableStateOf(false) }
    var projectUnavailable by remember(task?.workspaceId) { mutableStateOf(false) }
    val runtimeId = task?.runtimeId?.takeIf { it.isNotBlank() } ?: bar.configuredRuntimeId?.takeIf { it.isNotBlank() }
    val workspaceId = task?.workspaceId?.takeIf { it.isNotBlank() }
    val projectFailedText = stringResource(R.string.native_chat_compaction_files_project_failed)

    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("native_chat_files_action")) {
            Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.native_chat_compaction_files))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (workspaceId != null && runtimeId != null && !projectUnavailable) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.native_chat_compaction_files_project),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        menuOpen = false
                        scope.launch {
                            val opened = HarnessProjectFilesNavigator.openProjectFiles(
                                context, bar.navController, runtimeId, workspaceId
                            )
                            if (!opened) {
                                projectUnavailable = true
                                Toast.makeText(context, projectFailedText, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.native_chat_compaction_files_runtime),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                enabled = runtimeId != null,
                onClick = {
                    menuOpen = false
                    showRuntimeFiles = true
                }
            )
        }
    }
    if (showRuntimeFiles && runtimeId != null) {
        Dialog(
            onDismissRequest = { showRuntimeFiles = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(Modifier.fillMaxSize()) {
                HarnessRuntimeContent(runtimeId) {
                    HarnessRootfsFileBrowser(onClose = { showRuntimeFiles = false })
                }
            }
        }
    }
}

/** Delegation-viewer icon with a spinner while the latest task is live and a dot when it needs attention. */
@Composable
internal fun DelegationViewerAction(bar: ChatDelegationBar, task: NativeChatDelegateTaskEntity?) {
    val state = task?.state
    val live = state != null && state in NativeChatDelegateTaskState.LIVE && state !in ATTENTION_TASK_STATES
    val attention = state != null && state in ATTENTION_TASK_STATES
    val description = stringResource(
        when {
            attention -> R.string.native_chat_compaction_delegation_attention
            live -> R.string.native_chat_compaction_delegation_running
            else -> R.string.native_chat_compaction_delegation
        }
    )
    IconButton(
        onClick = { bar.navController.navigate(Screen.NativeChatDelegation.createRoute(bar.chatId)) },
        modifier = Modifier.testTag("native_chat_delegation_action")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.SmartToy, contentDescription = description)
            if (live) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
            }
            if (attention) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(9.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Delegation notice rows
// ---------------------------------------------------------------------------------------------

/** Localized short label for a notice's leading `[DeepSeek ...]` tag, or null for an unknown tag. */
internal fun delegationNoticeLabelRes(text: String): Int? {
    val t = text.trimStart()
    return when {
        t.startsWith("[DeepSeek task finished]") -> R.string.native_chat_compaction_notice_finished
        t.startsWith("[DeepSeek scheduled check]") -> R.string.native_chat_compaction_notice_check
        t.startsWith("[DeepSeek subagent question]") -> R.string.native_chat_compaction_notice_question
        t.startsWith("[DeepSeek task needs direction]") -> R.string.native_chat_compaction_notice_direction
        else -> null
    }
}

/** Grey notice line for an app-written wake message; tap opens the full text. No message actions. */
@Composable
internal fun ChatDelegationNoticeRow(message: LlamaMessageEntity) {
    var showFull by remember(message.id) { mutableStateOf(false) }
    val labelRes = remember(message.content) { delegationNoticeLabelRes(message.content) }
    val label = if (labelRes != null) stringResource(labelRes) else message.content.trim().take(60)
    ChatNoticeLine(text = label, onClick = { showFull = true })
    if (showFull) {
        AppTextDetailsDialog(
            title = stringResource(R.string.native_chat_compaction_notice_title),
            text = message.content,
            onDismiss = { showFull = false }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Composer file attachments
// ---------------------------------------------------------------------------------------------

internal const val CHAT_FILE_MAX_BYTES: Long = 256L * 1024 * 1024

/** Keeps letters, digits, dots, dashes, underscores and spaces; never returns a path separator. */
internal fun sanitizeChatFileName(name: String): String {
    val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
        .map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ' ') it else '_' }
        .joinToString("")
        .trim()
        .trimStart('.')
        .take(80)
    return cleaned.ifBlank { "file" }
}

internal sealed class ChatFileImportResult {
    data class Added(val displayName: String) : ChatFileImportResult()
    data class TooLarge(val displayName: String) : ChatFileImportResult()
    data class Failed(val displayName: String) : ChatFileImportResult()
}

/**
 * Copies each picked document into `files/native_chat_workspaces/<chatId>/attachments/<name>` (unique names) and inserts a
 * pending USER attachment row. Runs on the IO dispatcher; each file is capped at [CHAT_FILE_MAX_BYTES].
 */
internal suspend fun importChatFiles(context: Context, chatId: Long, uris: List<Uri>): List<ChatFileImportResult> =
    withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).llamaMessageAttachmentDao()
        val dir = NativeChatWorkspaceSupport.subDir(context, chatId, NativeChatWorkspaceSupport.ATTACHMENTS_DIR)
        uris.map { uri ->
            val displayName = runCatching { queryDocumentDisplayName(context, uri) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: "file"
            val target = NativeChatWorkspaceSupport.uniqueFile(dir, sanitizeChatFileName(displayName))
            try {
                var total = 0L
                var tooLarge = false
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@map ChatFileImportResult.Failed(displayName)
                input.use { source ->
                    target.outputStream().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > CHAT_FILE_MAX_BYTES) {
                                tooLarge = true
                                break
                            }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                if (tooLarge) {
                    target.delete()
                    return@map ChatFileImportResult.TooLarge(displayName)
                }
                val mime = context.contentResolver.getType(uri)?.takeIf { it.isNotBlank() }
                    ?: MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(displayName.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                dao.insert(
                    LlamaMessageAttachmentEntity(
                        messageId = null,
                        chatId = chatId,
                        path = target.absolutePath,
                        displayName = displayName,
                        mimeType = mime,
                        sizeBytes = total,
                        source = LlamaMessageAttachmentSource.USER
                    )
                )
                ChatFileImportResult.Added(displayName)
            } catch (error: Exception) {
                target.delete()
                ChatFileImportResult.Failed(displayName)
            }
        }
    }

/** Deletes a still-pending attachment row and its file. */
internal suspend fun removePendingChatFile(context: Context, attachment: LlamaMessageAttachmentEntity) {
    withContext(Dispatchers.IO) {
        runCatching { AppDatabase.getDatabase(context).llamaMessageAttachmentDao().delete(attachment.id) }
        runCatching { File(attachment.path).delete() }
    }
}

/** User-visible toasts for failed imports. */
internal fun toastChatFileImportErrors(context: Context, results: List<ChatFileImportResult>) {
    results.forEach { result ->
        when (result) {
            is ChatFileImportResult.TooLarge -> Toast.makeText(
                context, context.getString(R.string.native_chat_compaction_attach_too_large, result.displayName),
                Toast.LENGTH_LONG
            ).show()
            is ChatFileImportResult.Failed -> Toast.makeText(
                context, context.getString(R.string.native_chat_compaction_attach_failed, result.displayName),
                Toast.LENGTH_LONG
            ).show()
            is ChatFileImportResult.Added -> Unit
        }
    }
}

// ---------------------------------------------------------------------------------------------
// New-chat disk KV cache notice
// ---------------------------------------------------------------------------------------------

internal const val KV_NOTICE_MAX_ACKED = 500

/** Appends [id] to the oldest-first list, de-duplicated and capped at [cap] (newest kept). */
internal fun appendAckedChatId(current: List<Long>, id: Long, cap: Int = KV_NOTICE_MAX_ACKED): List<Long> {
    val next = current.filterNot { it == id } + id
    return if (next.size > cap) next.takeLast(cap) else next
}

/** Remembers which chats already showed the "Disk KV cache is on" notice. */
internal object ChatKvNoticeStore {
    private const val PREFS = "native_chat_kv_notice"
    private const val KEY = "acked_chat_ids"

    private fun read(context: Context): List<Long> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }

    fun isAcked(context: Context, chatId: Long): Boolean = chatId in read(context)

    fun ack(context: Context, chatId: Long) {
        val next = appendAckedChatId(read(context), chatId)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, next.joinToString(",")) }
    }
}

/**
 * One-time pop-up for a fresh chat: the disk KV cache text ([notice].diskKvCache) and one warning
 * row with a "Set up" action for each helper model that is not configured. The body scrolls.
 */
@Composable
internal fun ChatKvCacheNoticeDialog(
    notice: NewChatNotice,
    onGotIt: () -> Unit,
    onTurnOff: () -> Unit,
    onSetUpHelpers: () -> Unit
) {
    // Turning the disk cache off only makes sense when the notice is about it.
    val turnOffButton: (@Composable () -> Unit)? = if (notice.diskKvCache) {
        {
            TextButton(onClick = onTurnOff) {
                Text(stringResource(R.string.native_chat_compaction_kv_notice_off), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    } else null
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onGotIt,
        title = {
            Text(
                stringResource(
                    if (notice.diskKvCache) R.string.native_chat_compaction_kv_notice_title
                    else R.string.helper_notice_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (notice.diskKvCache) {
                    Text(stringResource(R.string.native_chat_compaction_kv_notice_text))
                }
                if (notice.anyHelperUnset) {
                    if (notice.diskKvCache) HorizontalDivider()
                    Text(
                        stringResource(R.string.helper_notice_intro),
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (notice.titleHelperUnset) {
                        HelperWarningRow(R.string.helper_notice_title_unset, onSetUpHelpers)
                    }
                    if (notice.visionHelperUnset) {
                        HelperWarningRow(R.string.helper_notice_vision_unset, onSetUpHelpers)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onGotIt) {
                Text(stringResource(R.string.native_chat_compaction_kv_notice_ok), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = turnOffButton
    )
}

@Composable
private fun HelperWarningRow(textRes: Int, onSetUp: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(textRes), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onSetUp, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.helper_notice_set_up), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Screen glue (kept out of LlamaChatScreen to bound its size)
// ---------------------------------------------------------------------------------------------

/** Compaction markers, per-message attachments and the composer's pending files for one chat. */
internal class ChatAttachmentsUi(
    val compactions: List<NativeChatCompactionEntity>,
    val byMessage: Map<Long, List<LlamaMessageAttachmentEntity>>,
    val pendingFiles: List<LlamaMessageAttachmentEntity>
)

@Composable
internal fun rememberChatAttachmentsUi(repository: LlamaRepository, chatId: Long): ChatAttachmentsUi {
    val context = LocalContext.current
    val compactions by remember(chatId) { repository.observeCompactions(chatId) }
        .collectAsState(initial = emptyList())
    val rows by remember(chatId) { repository.observeAttachmentsForChat(chatId) }
        .collectAsState(initial = emptyList())
    val pending by remember(chatId) {
        AppDatabase.getDatabase(context).llamaMessageAttachmentDao()
            .observePending(chatId, LlamaMessageAttachmentSource.USER)
    }.collectAsState(initial = emptyList())
    val byMessage = remember(rows) {
        rows.filter { it.messageId != null }.groupBy { it.messageId!! }
    }
    return ChatAttachmentsUi(compactions, byMessage, pending)
}

/** The composer's "Attach file" launcher plus whether a picked file is still being imported. */
internal class ChatFilePicker(val launch: () -> Unit, val importing: Boolean)

/** Returns the launcher for the composer's "Attach file" item (any mime type, several files). */
@Composable
internal fun rememberChatFilePicker(chatId: Long): ChatFilePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember(chatId) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val picked = uris.distinctBy { it.toString() }
        if (picked.isNotEmpty()) {
            importing = true
            scope.launch {
                try {
                    toastChatFileImportErrors(context, importChatFiles(context, chatId, picked))
                } finally {
                    importing = false
                }
            }
        }
    }
    return ChatFilePicker(launch = { launcher.launch(arrayOf("*/*")) }, importing = importing)
}

/** Text sent when the user attaches files but types nothing. */
internal fun attachedFilesFallbackText(context: Context, files: List<LlamaMessageAttachmentEntity>): String =
    context.getString(
        R.string.native_chat_compaction_attached_files_text,
        files.joinToString(", ") { it.displayName }
    )

/**
 * Pop-up for a brand-new chat (empty, first open, once per chat id). It shows the "Disk KV cache is
 * on" text for eligible local llama-server chats and a warning for each helper model (chat titles,
 * vision helper) that is not set up, with a "Set up" action that opens the Helper models sheet.
 * Eligibility is decided here. [hasNoMessages] is checked against storage so a still-loading
 * message list never triggers it.
 */
@Composable
internal fun ChatKvCacheNoticeHost(
    chatId: Long,
    persistKvCache: Boolean,
    server: LlamaServerEntity?,
    hasNoMessages: suspend () -> Boolean,
    onTurnOff: () -> Unit
) {
    val context = LocalContext.current
    var notice by remember(chatId) { mutableStateOf<NewChatNotice?>(null) }
    var showHelperModels by remember(chatId) { mutableStateOf(false) }
    val kvEligible = persistKvCache && defaultPersistKvCacheForServer(server)
    LaunchedEffect(chatId, kvEligible) {
        val showing = notice
        if (showing != null) {
            // The chat/server rows load after the first frame: add the disk-cache text once known.
            if (kvEligible && !showing.diskKvCache) notice = showing.copy(diskKvCache = true)
            return@LaunchedEffect
        }
        if (ChatKvNoticeStore.isAcked(context, chatId)) return@LaunchedEffect
        val candidate = withContext(Dispatchers.IO) {
            val title = TitleGenerationSettings(context)
            val vision = VisionHelperSettings(context)
            NewChatNotice(
                diskKvCache = kvEligible,
                titleHelperUnset = titleHelperUnset(title.enabled, title.modelPath),
                visionHelperUnset = visionHelperUnset(vision.enabled, vision.serverRowId)
            )
        }
        if (candidate.needed && hasNoMessages()) {
            ChatKvNoticeStore.ack(context, chatId)
            notice = candidate
        }
    }
    notice?.let { shown ->
        ChatKvCacheNoticeDialog(
            notice = shown,
            onGotIt = { notice = null },
            onTurnOff = {
                notice = null
                onTurnOff()
            },
            onSetUpHelpers = {
                notice = null
                showHelperModels = true
            }
        )
    }
    if (showHelperModels) {
        TitleGenerationSheet(onDismiss = { showHelperModels = false })
    }
}
