package com.example.llamadroid.harness

import android.net.Uri
import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.runtime.HarnessEndpoint
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.AgentForegroundService
import com.example.llamadroid.service.HarnessRecoveryTerminalSessionManager
import com.example.llamadroid.ui.agent.harness.HarnessProjectUi
import com.example.llamadroid.ui.agent.harness.HarnessComposerReferenceUi
import com.example.llamadroid.ui.agent.harness.HarnessProjectActionDialog
import com.example.llamadroid.ui.agent.harness.HarnessProjectBatchRemovalDialog
import com.example.llamadroid.ui.agent.harness.HarnessRecoveryFileServerPanel
import com.example.llamadroid.ui.agent.harness.HarnessRecoveryTerminalScreen
import com.example.llamadroid.ui.agent.harness.HarnessRootfsFileBrowser
import com.example.llamadroid.ui.agent.harness.HarnessRuntimeReinstallDialog
import com.example.llamadroid.ui.agent.harness.HarnessWorkspaceUiState
import com.example.llamadroid.ui.agent.harness.HarnessInlineTarget
import com.example.llamadroid.ui.agent.harness.LocalHarnessInlineAction
import com.example.llamadroid.ui.agent.harness.parseHarnessInlineDestination
import com.example.llamadroid.ui.agent.harness.NativeHarnessController
import com.example.llamadroid.ui.agent.harness.NativeHarnessNavigationHooks
import com.example.llamadroid.ui.agent.harness.NativeHarnessOfflineSession
import com.example.llamadroid.ui.agent.harness.nativeHarnessProviderAuthHooks
import com.example.llamadroid.ui.agent.harness.NativeHarnessRuntimeCallbacks
import com.example.llamadroid.ui.agent.harness.NativeHarnessScreen
import com.example.llamadroid.ui.agent.harness.NativeHarnessSessionProjectionReader
import com.example.llamadroid.ui.agent.harness.NativeHarnessUiAction
import com.example.llamadroid.ui.agent.harness.NativeHarnessWorkspaceHooks
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Canonical Agent entry point. Reuses the existing explorer and model manager. */
@Composable
fun HarnessAgentRoute(navController: NavController, initialConversationId: Long? = null, initialAttentionTab: String? = null) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val localWorkspaceLabel by rememberUpdatedState(stringResource(R.string.harness_workspace_local))
    val sshWorkspaceLabel by rememberUpdatedState(stringResource(R.string.harness_workspace_ssh))
    val copyLabel by rememberUpdatedState(stringResource(R.string.harness_turn_copy))
    val diagnosticsLabel by rememberUpdatedState(stringResource(R.string.harness_runtime_diagnostics_title))
    val runtime = remember { HarnessAppRuntime.get(context) }
    // Own the persisted LAN preference for the whole Harness route so an enabled gateway
    // follows runtime lifecycle even when the user is not currently viewing the Runtime tab.
    val lanAccess = remember { HarnessLanAccessManager.get(context) }
    val projectManager = remember(runtime) {
        HarnessProjectManagement(context.applicationContext, runtime.database, runtime.workspaces, runtime.files)
    }
    val projectionReader = remember(runtime) { NativeHarnessSessionProjectionReader() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val runtimeState by runtime.status.collectAsState()
    val endpoint by runtime.endpoint.collectAsState()
    val runtimeError by runtime.errorCode.collectAsState()
    val startupInProgress by runtime.startupInProgress.collectAsState()
    val reinstallState by runtime.reinstallState.collectAsState()
    val runtimeJournal by runtime.diagnostics.runtimeEntries.collectAsState()
    // Keep the first Room emission distinguishable from the provisional empty value. The native
    // project route must not clear a deep-linked project while this query is still loading.
    val workspaceRowsSnapshot by produceState<List<HarnessWorkspaceEntity>?>(null, runtime) {
        runtime.database.harnessDao().observeWorkspaces().collect { value = it }
    }
    val workspaceRows = workspaceRowsSnapshot.orEmpty()
    val legacy by runtime.database.harnessDao().observeLegacyConversations().collectAsState(initial = emptyList())
    val sessionRows by runtime.database.harnessDao().observeSessions().collectAsState(initial = emptyList())
    val deletingSessions by runtime.sessionDeletion.pending.collectAsState()
    val generationActivities by runtime.models.generationActivity.collectAsState()
    val deletionStore = remember(context) { HarnessSessionDeletionStore(context) }
    var projectRevision by remember { mutableStateOf(0) }
    val visibleWorkspaceRows by produceState(emptyList<HarnessWorkspaceEntity>(), workspaceRows, projectRevision) {
        value = withContext(Dispatchers.IO) {
            val visibleIds = projectManager.visibleWorkspaceIds(workspaceRows)
            workspaceRows.filter { it.id in visibleIds }
        }
    }
    // This is a canonical workspace id, never a display-name key. Clear a saved selection as
    // soon as Room confirms that the workspace was removed so a recreated same-name project
    // cannot inherit the previous controller selection.
    var activeProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    // Prevent an unchanged DSH group snapshot from issuing the same title repair repeatedly.
    val repairedDshGroups = remember { ConcurrentHashMap.newKeySet<String>() }
    var showProjectCreator by remember { mutableStateOf(false) }
    var projectAction by remember { mutableStateOf<Pair<HarnessProjectUi, Boolean>?>(null) }
    var projectRemovalPreview by remember { mutableStateOf<HarnessProjectRemovalPreview?>(null) }
    var projectBatchPreviews by remember { mutableStateOf<List<HarnessProjectRemovalPreview>?>(null) }
    val projectChoices = remember(visibleWorkspaceRows, sessionRows, localWorkspaceLabel, sshWorkspaceLabel) {
        visibleWorkspaceRows.groupBy { row ->
            if (row.backend == "REMOTE_SSH") "ssh:${row.connectionKey}:${row.projectFolder}"
            else "local:${com.example.llamadroid.service.AgentLocalWorkspaceSupport.sanitizeProjectFolder(row.projectFolder)}"
        }.values.map { rows ->
            val first = rows.minBy { it.createdAt }
            val ids = rows.mapTo(mutableSetOf()) { it.id }
            HarnessProjectUi(first.id, first.title, first.projectFolder,
                if (first.backend == "REMOTE_SSH") sshWorkspaceLabel else localWorkspaceLabel,
                sessionRows.filter { it.workspaceId in ids }.mapTo(mutableSetOf()) { it.harnessSessionId })
        }.sortedBy { it.title.lowercase() }
    }
    val reinstallInProgress = reinstallState.phase in setOf(
        HarnessRuntimeReinstallPhase.STOPPING,
        HarnessRuntimeReinstallPhase.RESETTING,
        HarnessRuntimeReinstallPhase.RESTORING,
    )
    // The Room snapshot can still contain the pre-reset workspace/session rows while the
    // destructive reset is stopping the controller. Hide that snapshot immediately so a
    // recreated same-name project cannot briefly render the old conversation.
    val displayedProjectChoices = if (reinstallInProgress) emptyList() else projectChoices
    LaunchedEffect(reinstallInProgress) {
        if (reinstallInProgress) activeProjectId = null
    }
    LaunchedEffect(
        projectChoices.map { it.id },
        visibleWorkspaceRows,
        workspaceRowsSnapshot,
        reinstallInProgress,
    ) {
        // A restored selection is valid until Room has emitted its first authoritative snapshot.
        // The provisional empty value must not turn a deep link into the unselected landing route.
        if (workspaceRowsSnapshot == null || reinstallInProgress) return@LaunchedEffect
        if (activeProjectId != null && projectChoices.none { it.id == activeProjectId }) {
            activeProjectId = null
        }
    }
    var showError by remember { mutableStateOf(false) }
    val openProjectFiles: (String) -> Unit = { id -> scope.launch {
        runCatching {
            val captured = withContext(Dispatchers.IO) { runtime.workspaces.openWorkspace(id) }
            AgentService.selectHarnessWorkspace(captured.conversation)
            navController.currentBackStackEntry?.savedStateHandle?.set("harness_workspace_files", true)
            navController.navigate(Screen.AgentWorkspace.route)
        }.onFailure { showError = true }
    } }
    var showJournal by remember { mutableStateOf(false) }
    var showLegacy by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var showWorkspaceBrowser by remember { mutableStateOf(false) }
    var showRecoveryTerminal by rememberSaveable { mutableStateOf(false) }
    var showRecoveryFileBrowser by rememberSaveable { mutableStateOf(false) }
    var showRecoveryFileServer by rememberSaveable { mutableStateOf(false) }
    var showRuntimeReinstall by rememberSaveable { mutableStateOf(false) }
    var integrationSession by remember { mutableStateOf<HarnessSessionScope?>(null) }
    var referenceSession by remember { mutableStateOf<HarnessSessionScope?>(null) }
    var referenceQuery by remember { mutableStateOf<String?>(null) }
    var composerReferences by remember { mutableStateOf<List<HarnessComposerReferenceUi>>(emptyList()) }
    var attachmentSession by remember { mutableStateOf<String?>(null) }
    var downloaded by remember { mutableStateOf<HarnessDownloadedFile?>(null) }
    var presetFiles by remember { mutableStateOf<HarnessPresetFiles?>(null) }
    var inlineDocument by remember { mutableStateOf<HarnessInlineDocumentRequest?>(null) }
    val receiveDownload: (String) -> Unit = { url -> scope.launch {
        runCatching { downloadHarnessFile(context, requireNotNull(endpoint), url) }
            .onSuccess { downloaded = it }.onFailure { showError = true }
    } }
    var workspaceRequest by remember { mutableStateOf<CompletableDeferred<JsonObject>?>(null) }
    var sshWorkspace by remember { mutableStateOf<HarnessWorkspaceEntity?>(null) }
    var initialHandled by remember(initialConversationId) { mutableStateOf(false) }
    var controllerRef by remember { mutableStateOf<NativeHarnessController?>(null) }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val capturedSession = attachmentSession
        attachmentSession = null
        if (uri != null) scope.launch {
            runCatching {
                val controller = requireNotNull(controllerRef)
                val sessionId = requireNotNull(capturedSession)
                val receipt = uploadHarnessAttachment(runtime, uri, sessionId)
                controller.addAttachment(sessionId, receipt)
            }.onFailure { showError = true }
        }
    }
    val controller = remember(runtime, navController) {
        NativeHarnessController(
            parentScope = runtime.scope,
            clientProvider = { runtime.client },
            sharedAttention = runtime.attention,
            runtime = NativeHarnessRuntimeCallbacks(
                current = { runtime.database.harnessDao().runtime().toRuntimePresentation(context, runtime.errorCode.value, runtime.diagnostics.runtimeEntries.value, runtime.startupInProgress.value) },
                start = { runtime.start(); runtime.database.harnessDao().runtime().toRuntimePresentation(context, runtime.errorCode.value, runtime.diagnostics.runtimeEntries.value, runtime.startupInProgress.value) },
                stop = { runtime.stop(); runtime.database.harnessDao().runtime().toRuntimePresentation(context, runtime.errorCode.value, runtime.diagnostics.runtimeEntries.value, runtime.startupInProgress.value) },
                forceStop = { runtime.forceStop(); runtime.database.harnessDao().runtime().toRuntimePresentation(context, runtime.errorCode.value, runtime.diagnostics.runtimeEntries.value, runtime.startupInProgress.value) }
            ),
            workspace = NativeHarnessWorkspaceHooks(
                isSessionRemoved = { id, cwd -> deletionStore.read(id)?.removed == true || projectManager.isSessionRemoved(id, cwd) },
                reconcileWorkspaceGroup = reconcile@{ group ->
                    val mapped = withContext(Dispatchers.IO) {
                        runtime.workspaces.reconcileHarnessWorkspace(group.workspaceId, group.path)
                    }
                    val localTitle = mapped?.title?.trim().orEmpty()
                    val client = runtime.client
                    if (mapped?.harnessWorkspaceId == null || localTitle.isBlank() || client == null ||
                        localTitle == group.title.trim() || !repairedDshGroups.add(group.workspaceId)
                    ) return@reconcile
                    val result = client.call("workspace", "rename", buildJsonObject {
                        putJsonObject("request") {
                            put("workspaceId", group.workspaceId)
                            put("title", localTitle)
                        }
                    })
                    if (result is HarnessRpcResult.Failure) repairedDshGroups.remove(group.workspaceId)
                },
                resolveSession = resolveSession@{ id, title, cwd, archived ->
                    if (deletionStore.read(id)?.removed == true || projectManager.isSessionRemoved(id, cwd)) return@resolveSession null
                    if (cwd != null) runtime.workspaces.importSession(id, title, cwd, archived = archived)
                    if (runtime.database.harnessDao().session(id) != null) {
                        val mapped = runtime.workspaces.scope(id)
                        HarnessWorkspaceUiState(mapped.workspace.projectFolder,
                            if (mapped.workspace.backend == "REMOTE_SSH") sshWorkspaceLabel else localWorkspaceLabel,
                            rootLabel = mapped.workspace.guestPath, previewAvailable = true)
                        } else null
                },
                readSessionProjection = { sessionId ->
                    projectionReader.read(runtime.client, sessionId)
                },
                invalidateSessionProjection = { projectionReader.invalidate() },
                readOfflineSessions = {
                    runtime.database.harnessDao().observeSessions().first().mapNotNull { session ->
                        if (deletionStore.read(session.harnessSessionId)?.removed == true || projectManager.isSessionRemoved(session.harnessSessionId)) return@mapNotNull null
                        val workspace = runtime.database.harnessDao().workspace(session.workspaceId) ?: return@mapNotNull null
                        val conversation = runtime.database.agentChatDao().getConversation(session.conversationId)
                            ?: return@mapNotNull null
                        NativeHarnessOfflineSession(
                            sessionId = session.harnessSessionId,
                            title = conversation.title.ifBlank { workspace.title },
                            cwd = workspace.guestPath,
                            archived = session.archived
                        )
                    }
                },
                createSessionArgs = {
                    val selected = activeProjectId?.let { runtime.database.harnessDao().workspace(it) }
                    if (selected != null) prepareHarnessProject(runtime, selected)
                    else {
                        val request = CompletableDeferred<JsonObject>()
                        withContext(Dispatchers.Main) { workspaceRequest = request }
                        request.await()
                    }
                },
                openWorkspace = {
                    withContext(Dispatchers.Main) { showWorkspaceBrowser = true }
                },
                openLegacyHistory = { withContext(Dispatchers.Main) { showLegacy = true } }
            ),
            navigation = NativeHarnessNavigationHooks(
                openOriginalWebUi = { withContext(Dispatchers.Main) { showOriginal = true } },
                // `models` is a legacy alias and is not registered in the
                // current graph. Route directly to the canonical Model Hub.
                openModelManager = { withContext(Dispatchers.Main) { navController.navigate(Screen.ModelHub.route) } }
            ),
            providerAuth = nativeHarnessProviderAuthHooks({ runtime.client }, context),
            handleExternalAction = { action ->
                when (action) {
                    is NativeHarnessUiAction.DeleteSession -> runtime.sessionDeletion.request(action.sessionId)
                    NativeHarnessUiAction.RefreshRuntimeDiagnostics -> {
                        withContext(Dispatchers.IO) { runtime.diagnostics.loadRuntimeJournal() }
                        controllerRef?.setRuntimeState(runtime.database.harnessDao().runtime().toRuntimePresentation(
                            context, runtime.errorCode.value, runtime.diagnostics.runtimeEntries.value, runtime.startupInProgress.value
                        ))
                    }
                    NativeHarnessUiAction.OpenRuntimeJournal -> withContext(Dispatchers.Main) { showJournal = true }
                    NativeHarnessUiAction.CopyRuntimeDiagnostics -> {
                        val metadata = "appVersion=${com.example.llamadroid.BuildConfig.VERSION_NAME} " +
                            "androidApi=${android.os.Build.VERSION.SDK_INT} " +
                            "abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}\n" +
                            runtimeDiagnosticsText(runtime.diagnostics.runtimeEntries.value.takeLast(200))
                        withContext(Dispatchers.Main) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                diagnosticsLabel, metadata
                            ))
                        }
                    }
                    is NativeHarnessUiAction.OpenTranscriptLink -> {
                        val capturedClient = requireNotNull(runtime.client)
                        val fileScope = action.workspaceSessionId ?: action.sessionId
                        when (val target = action.target) {
                            is HarnessInlineTarget.External -> withContext(Dispatchers.Main) {
                                require(parseHarnessInlineDestination(target.url) == target)
                                context.startActivity(Intent(Intent.ACTION_VIEW, target.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            else -> {
                                val file = when (target) {
                                    is HarnessInlineTarget.File -> target
                                    is HarnessInlineTarget.Skill -> HarnessInlineTarget.File(
                                        HarnessInlineDocumentReader(capturedClient, fileScope).skillPath(target.name),
                                    )
                                    is HarnessInlineTarget.External -> error("INLINE_LINK_INVALID")
                                }
                                withContext(Dispatchers.Main) {
                                    if (controllerRef?.state?.value?.selectedSessionId == action.sessionId && runtime.client === capturedClient) {
                                        inlineDocument = HarnessInlineDocumentRequest(fileScope, file.path, file.line ?: 1, action.sessionId)
                                    }
                                }
                            }
                        }
                    }
                    is NativeHarnessUiAction.OpenAgentPresetDirectory -> {
                        val files = openHarnessPresetFiles(context, requireNotNull(runtime.client), action.presetId)
                        withContext(Dispatchers.Main) { presetFiles = files }
                    }
                    NativeHarnessUiAction.OpenReferencePicker -> {
                        val selected = requireNotNull(controllerRef?.state?.value?.selectedSessionId)
                        val captured = runtime.workspaces.scope(selected)
                        withContext(Dispatchers.Main) { referenceSession = captured }
                    }
                    NativeHarnessUiAction.OpenAttachmentPicker -> withContext(Dispatchers.Main) {
                        attachmentSession = requireNotNull(controllerRef?.state?.value?.selectedSessionId)
                        attachmentPicker.launch(arrayOf("*/*"))
                    }
                    NativeHarnessUiAction.OpenProjectIntegration -> {
                        val selected = requireNotNull(controllerRef?.state?.value?.selectedSessionId)
                        val captured = runtime.workspaces.scope(selected)
                        withContext(Dispatchers.Main) { integrationSession = captured }
                    }
                    is NativeHarnessUiAction.ExportSessionLog -> {
                        try {
                            val currentEndpoint = requireNotNull(runtime.endpoint.value)
                            val file = downloadHarnessFile(
                                context,
                                currentEndpoint,
                                buildHarnessSessionLogExportUrl(currentEndpoint, action.sessionId)
                            )
                            withContext(Dispatchers.Main) { downloaded = file }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            withContext(Dispatchers.Main) { showError = true }
                        }
                    }
                    else -> error("HARNESS_ACTION_NOT_CONNECTED")
                }
            },
            deliverAttachment = { _, _, value ->
                val download = decodeHarnessAttachment(context, value)
                withContext(Dispatchers.Main) { downloaded = download }
            },
            deliverCopiedText = { sessionId, text ->
                withContext(Dispatchers.Main) {
                    if (controllerRef?.state?.value?.selectedSessionId == sessionId) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(copyLabel, text))
                        if (android.os.Build.VERSION.SDK_INT < 33) {
                            android.widget.Toast.makeText(context, R.string.harness_transcript_copied, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onDiagnostic = { session, event, status, errorClass ->
                runtime.diagnostics.event(session, event, status, errorCode = errorClass)
            }
        ).also { controllerRef = it }
    }
    val state by controller.state.collectAsState()
    LaunchedEffect(sessionRows.map { it.harnessSessionId }, deletingSessions) {
        val removedVisibleSession = withContext(Dispatchers.IO) {
            controller.state.value.sessions.any { deletionStore.read(it.id)?.removed == true }
        }
        if (removedVisibleSession) controller.dispatch(NativeHarnessUiAction.RefreshSessions)
    }
    LaunchedEffect(state.selectedSessionId, endpoint, referenceQuery) {
        composerReferences = emptyList()
        val selected = state.selectedSessionId ?: return@LaunchedEffect
        val query = referenceQuery?.take(2048) ?: return@LaunchedEffect
        val capturedClient = runtime.client ?: return@LaunchedEffect
        delay(150)
        val results = loadHarnessReferences(capturedClient, selected, query)
        if (runtime.client === capturedClient && controller.state.value.selectedSessionId == selected) {
            composerReferences = results.entries.map { entry ->
                HarnessComposerReferenceUi(entry.key, entry.title, entry.mention,
                    kind = if (entry.path == null) "session" else "file", detail = entry.workspace)
            }
            if (results.incomplete) runtime.diagnostics.event(selected, "reference_catalog", "INTERRUPTED")
        }
    }
    LaunchedEffect(runtime) {
        runtime.workspaceActions.requests.collect { request ->
            if (!request.completion.isActive) return@collect
            try {
                if (request.reveal || request.directory) {
                    AgentService.selectHarnessWorkspace(request.scope.conversation)
                    val directory = if (request.directory) request.relativePath else request.relativePath.substringBeforeLast('/', ".")
                    navController.currentBackStackEntry?.savedStateHandle?.set("harness_workspace_files", true)
                    navController.currentBackStackEntry?.savedStateHandle?.set("agent_workspace_initial_relative_path", directory)
                    showOriginal = false
                    navController.navigate(Screen.AgentWorkspace.route)
                } else {
                    val file = withContext(Dispatchers.IO) {
                        val bytes = runtime.files.readBytes(request.scope, request.relativePath, 16 * 1024 * 1024)
                        val name = request.relativePath.substringAfterLast('/')
                        val mime = java.net.URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
                        cacheHarnessDownload(context, bytes, name, mime)
                    }
                    if (request.completion.isActive) { downloaded = file; showOriginal = false }
                }
                request.completion.complete(Unit)
            } catch (cancelled: CancellationException) {
                request.completion.completeExceptionally(cancelled)
                throw cancelled
            } catch (error: Exception) {
                request.completion.completeExceptionally(error)
                runtime.diagnostics.event(request.scope.session.harnessSessionId, "workspace_open", "FAILED", errorCode = error.javaClass.simpleName)
                showError = true
            }
        }
    }
    LaunchedEffect(runtimeState, runtimeError, runtimeJournal, locale, startupInProgress) {
        controller.setRuntimeState(runtimeState.toRuntimePresentation(context, runtimeError, runtimeJournal, startupInProgress))
    }
    LaunchedEffect(endpoint) {
        controller.setOriginalWebUiState(endpoint.toWebUiPresentation())
        controller.onEndpointChanged()
    }
    LaunchedEffect(legacy.isNotEmpty()) { controller.setLegacyHistoryAvailable(legacy.isNotEmpty()) }
    LaunchedEffect(deletingSessions) { controller.setDeletingSessions(deletingSessions.mapTo(mutableSetOf()) { it.sessionId }, deletingSessions.filter { it.error }.mapTo(mutableSetOf()) { it.sessionId }) }
    LaunchedEffect(initialConversationId, endpoint, projectChoices) {
        if (!initialHandled && initialConversationId != null) {
            val mapping = runtime.database.harnessDao().sessionForConversation(initialConversationId)
            if (mapping == null) { showLegacy = true; initialHandled = true }
            else {
                // Multiple preserved backend records can point to the same local project.
                // Open the canonical project card that owns this thread, not a hidden alias.
                val project = projectChoices.firstOrNull { mapping.harnessSessionId in it.threadIds }
                    ?: return@LaunchedEffect // Wait for the Room project/session flows to load.
                activeProjectId = project.id
                controller.dispatch(NativeHarnessUiAction.SelectSession(mapping.harnessSessionId))
                initialHandled = true
            }
        }
    }
    DisposableEffect(controller, lifecycle) {
        var wasPaused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) wasPaused = true
            if (event == Lifecycle.Event.ON_RESUME && wasPaused && runtime.endpoint.value != null) {
                wasPaused = false
                scope.launch { controller.refreshAfterWebView() }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); controller.close(); controllerRef = null }
    }
    Column(Modifier.fillMaxSize()) {
        if (runtimeError == "SSH_CLEANUP_PENDING" || runtimeError == "INFERENCE_CLEANUP_PENDING") {
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                Text(if (runtimeError == "SSH_CLEANUP_PENDING") stringResource(R.string.harness_runtime_ssh_cleanup_pending, runtime.files.pendingCleanupSessions().size)
                    else stringResource(R.string.harness_runtime_inference_cleanup_pending))
                TextButton(onClick = { scope.launch { runtime.retryCleanup() } }) { Text(stringResource(R.string.harness_runtime_retry)) }
            }
        }
        val linkSession = state.selectedSessionId
        val openInline = remember(controller, linkSession) {
            { target: HarnessInlineTarget ->
                linkSession?.let { controller.dispatch(NativeHarnessUiAction.OpenTranscriptLink(it, target)) }
                Unit
            }
        }
        CompositionLocalProvider(LocalHarnessInlineAction provides openInline) {
            NativeHarnessScreen(state.copy(generationActivity = generationActivities.values
                .filter { it.sessionId != null && it.sessionId == state.selectedSessionId }
                .maxByOrNull { it.updatedAtMs }), controller::dispatch, modifier = Modifier.weight(1f), onBack = {
                if (!navController.popBackStack(Screen.Dashboard.route, false)) navController.navigate(Screen.Dashboard.route) {
                    popUpTo(navController.graph.id) { inclusive = false }; launchSingleTop = true
                }
            }, initialAttentionTab = initialAttentionTab,
                referenceCatalog = composerReferences, onReferenceQuery = { referenceQuery = it }, originalWebUiContent = {
                endpoint?.let { HarnessOriginalWebView(it, onDownload = receiveDownload, onDiagnostic = runtime.diagnostics::webView,
                    onBootDiagnostic = runtime.diagnostics::webBoot) }
            }, onLeavingOriginalWebUi = { scope.launch { controller.refreshAfterWebView() } },
                projectReviewContent = { HarnessProjectReviewRoute(runtime, state.selectedSessionId?.takeIf { id ->
                    displayedProjectChoices.firstOrNull { it.id == activeProjectId }?.threadIds?.contains(id) == true
                }) },
                projects = displayedProjectChoices,
                projectNavigationEnabled = true,
                projectsLoaded = workspaceRowsSnapshot != null && !reinstallInProgress,
                initialProjectId = activeProjectId,
                onOpenProject = { activeProjectId = it },
                onCloseProject = { activeProjectId = null },
                onCreateProject = { showProjectCreator = true },
                onRenameProject = { project ->
                    projectRemovalPreview = null
                    projectAction = project to false
                },
                onRemoveProject = { project -> scope.launch {
                    try {
                        projectRemovalPreview = projectManager.preview(project.id)
                        projectAction = project to true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { showError = true }
                } },
                canBatchRemove = canRemoveHarnessProject(runtimeState, startupInProgress, endpoint != null),
                onBatchRemove = { projects -> scope.launch {
                    try {
                        projectBatchPreviews = projectManager.previewBatch(projects.map { it.id })
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { showError = true }
                } },
                onOpenProjectFiles = openProjectFiles,
                terminalContent = { id -> HarnessTerminalRoute(runtime, id) },
                onOpenRecoveryTerminal = { showRecoveryTerminal = true },
                onOpenRecoveryFileServer = { showRecoveryFileBrowser = true },
                onReinstallRuntime = {
                    scope.launch {
                        try {
                            runtime.prepareRuntimeReinstallConfirmation()
                            showRuntimeReinstall = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            showError = true
                        }
                    }
                },
            )
        }
    }
    projectAction?.let { (project, removing) ->
        HarnessProjectActionDialog(
            project = project,
            removing = removing,
            canRemove = canRemoveHarnessProject(runtimeState, startupInProgress, endpoint != null),
            canDeleteFiles = projectRemovalPreview?.fileDeletionSupported != false,
            pendingDeleteFiles = projectRemovalPreview?.takeIf { it.pendingRemoval }?.pendingDeleteFiles,
            onRename = { title ->
                projectManager.rename(project.id, title)
                val client = runtime.client
                if (client != null) {
                    withContext(Dispatchers.IO) {
                        runtime.workspaces.harnessWorkspaceIdsForProject(project.id)
                    }.forEach { harnessWorkspaceId ->
                        val result = client.call("workspace", "rename", buildJsonObject {
                            putJsonObject("request") {
                                put("workspaceId", harnessWorkspaceId)
                                put("title", title.trim())
                            }
                        })
                        if (result is HarnessRpcResult.Failure) repairedDshGroups.remove(harnessWorkspaceId)
                    }
                }
                projectRevision++
            },
            onRemove = { deleteFiles ->
                try {
                    runtime.withStoppedProjectMutation {
                        if (projectRemovalPreview?.pendingRemoval == true) projectManager.retryRemoval(project.id)
                        else projectManager.remove(project.id, deleteFiles)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    projectRemovalPreview = runCatching { projectManager.preview(project.id) }.getOrNull()
                    projectRevision++
                    throw failure
                }
                projectRevision++
                if (activeProjectId == project.id) activeProjectId = null
                controller.dispatch(NativeHarnessUiAction.RefreshSessions)
            },
            onDismiss = { projectAction = null; projectRemovalPreview = null },
        )
    }
    projectBatchPreviews?.let { previews ->
        HarnessProjectBatchRemovalDialog(
            previews = previews,
            canRemove = canRemoveHarnessProject(runtimeState, startupInProgress, endpoint != null),
            onRemove = { deleteFiles ->
                try {
                    runtime.withStoppedProjectMutation {
                        projectManager.removeBatch(previews.map { it.id }, deleteFiles)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    projectBatchPreviews = runCatching {
                        projectManager.previewBatch(previews.map { it.id })
                    }.getOrNull()
                    projectRevision++
                    throw failure
                }
                projectRevision++
                if (activeProjectId in previews.map { it.id }) activeProjectId = null
                controller.dispatch(NativeHarnessUiAction.RefreshSessions)
            },
            onDismiss = { projectBatchPreviews = null },
        )
    }
    LaunchedEffect(state.selectedSessionId, endpoint) {
        if (inlineDocument?.ownerSessionId != state.selectedSessionId || endpoint == null) inlineDocument = null
    }
    inlineDocument?.let { request ->
        runtime.client?.let { currentClient ->
            key(request, currentClient) {
                HarnessInlineDocumentDialog(request, currentClient, onDownload = { file ->
                    if (inlineDocument == request && runtime.client === currentClient &&
                        controller.state.value.selectedSessionId == request.ownerSessionId) downloaded = file
                }, onFailure = { runtime.diagnostics.event(request.ownerSessionId, "inline_file", "INTERRUPTED") },
                    onDismiss = { inlineDocument = null })
            }
        }
    }
    referenceSession?.let { captured ->
        HarnessReferencesDialog(
            runtime.client, captured.session.harnessSessionId,
            onPick = { mention ->
                controller.dispatch(NativeHarnessUiAction.InsertReference(captured.session.harnessSessionId, mention))
                referenceSession = null
            },
            onPreview = { path ->
                val file = withContext(Dispatchers.IO) {
                    val bytes = runtime.files.readBytes(captured, path, 16 * 1024 * 1024)
                    val mime = java.net.URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
                    cacheHarnessDownload(context, bytes, path.substringAfterLast('/'), mime)
                }
                downloaded = file
            },
            onFailure = { runtime.diagnostics.event(captured.session.harnessSessionId, "references", "INTERRUPTED") },
            onDismiss = { referenceSession = null }
        )
    }
    downloaded?.let { HarnessAttachmentViewer(it) { downloaded = null } }
    if (showRecoveryTerminal) Dialog(
        onDismissRequest = { showRecoveryTerminal = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            HarnessRecoveryTerminalScreen(onBack = { showRecoveryTerminal = false })
        }
    }
    if (showRecoveryFileBrowser) Dialog(
        onDismissRequest = { showRecoveryFileBrowser = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            HarnessRootfsFileBrowser(
                onClose = { showRecoveryFileBrowser = false },
                onOpenAdvancedSftp = { showRecoveryFileServer = true },
            )
        }
    }
    if (showRecoveryFileServer) Dialog(
        onDismissRequest = { showRecoveryFileServer = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                TextButton(onClick = { showRecoveryFileServer = false }) {
                    Text(stringResource(R.string.harness_recovery_advanced_sftp_close))
                }
                HarnessRecoveryFileServerPanel(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    if (showRuntimeReinstall) HarnessRuntimeReinstallDialog(
        state = reinstallState,
        onDismiss = { showRuntimeReinstall = false },
        onConfirm = {
            scope.launch {
                val result = runtime.reinstallManagedRuntime {
                    lanAccess.setEnabled(false)
                    lanAccess.state.first { !it.running }
                    HarnessRecoveryTerminalSessionManager.get(context).close().getOrThrow()
                    HarnessRecoveryFileServer.get(context).stop().getOrThrow()
                }
                if (result.success) showRuntimeReinstall = false
            }
        },
    )
    presetFiles?.let { files -> HarnessPresetFilesDialog(files) {
        presetFiles = null
        controller.dispatch(NativeHarnessUiAction.RefreshAgentPresets)
    } }
    integrationSession?.let { captured -> HarnessProjectIntegrationDialog(runtime, captured,
        onManageKnowledge = { integrationSession = null; navController.navigate(Screen.KnowledgeBase.route) },
        onDismiss = { integrationSession = null }) }
    if (showJournal) HarnessRuntimeJournalDialog(runtime.diagnostics) { showJournal = false }
    if (showLegacy) HarnessLegacyHistory(
        runtime.database, initialConversationId?.takeIf { id -> legacy.any { it.id == id } },
        deletion = remember(runtime) { HarnessLegacyDeletion(context.applicationContext, runtime.database, runtime.workspaces, runtime.files) },
        onOpenFiles = { id -> scope.launch {
            runCatching {
                val captured = withContext(Dispatchers.IO) { runtime.workspaces.fileScopeForConversation(id) }
                AgentService.selectHarnessWorkspace(captured.conversation)
                navController.currentBackStackEntry?.savedStateHandle?.set("harness_workspace_files", true)
                showLegacy = false
                navController.navigate(Screen.AgentWorkspace.route)
            }.onFailure { showError = true }
        } },
        onClose = { showLegacy = false },
    )
    if (showOriginal && endpoint != null) Dialog(
        onDismissRequest = { showOriginal = false; scope.launch { controller.refreshAfterWebView() } },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) { Column {
            TextButton(onClick = { showOriginal = false; scope.launch { controller.refreshAfterWebView() } }) { Text(stringResource(R.string.harness_runtime_close)) }
            HarnessOriginalWebView(requireNotNull(endpoint), Modifier.weight(1f), onDownload = receiveDownload, onDiagnostic = runtime.diagnostics::webView,
                onBootDiagnostic = runtime.diagnostics::webBoot)
        } }
    }
    workspaceRequest?.let { request ->
        val selectWorkspace: (HarnessWorkspaceEntity) -> Unit = { selected -> scope.launch {
            runCatching {
                val prepared = prepareHarnessProject(runtime, selected)
                activeProjectId = selected.id
                request.complete(prepared)
                workspaceRequest = null; sshWorkspace = null
            }.onFailure { showError = true }
        } }
        WorkspaceSelectionDialog(visibleWorkspaceRows,
            onSelect = { selected ->
                if (selected.backend == "REMOTE_SSH") sshWorkspace = selected else selectWorkspace(selected)
            },
            onCreate = { title -> scope.launch {
                runCatching { runtime.workspaces.createWorkspace(title) }.onSuccess {
                    selectWorkspace(it)
                }.onFailure { showError = true }
            } },
            onCreateSsh = { title -> scope.launch {
                runCatching { runtime.workspaces.createWorkspace(title, remote = true) }
                    .onSuccess { sshWorkspace = it }.onFailure { showError = true }
            } },
            onDismiss = { request.cancel(); workspaceRequest = null; sshWorkspace = null })
        sshWorkspace?.let { selected -> HarnessSshWorkspaceDialog(runtime, selected,
            onReady = { selectWorkspace(selected) }, onDismiss = { sshWorkspace = null }) }
    }
    if (showProjectCreator) {
        val created: (HarnessWorkspaceEntity) -> Unit = { project ->
            activeProjectId = project.id
            showProjectCreator = false
            sshWorkspace = null
        }
        WorkspaceSelectionDialog(emptyList(), onSelect = created,
            onCreate = { title -> scope.launch {
                runCatching { withContext(Dispatchers.IO) { runtime.workspaces.createWorkspace(title) } }
                    .onSuccess(created).onFailure { showError = true }
            } },
            onCreateSsh = { title -> scope.launch {
                runCatching { withContext(Dispatchers.IO) { runtime.workspaces.createWorkspace(title, remote = true) } }
                    .onSuccess { sshWorkspace = it }.onFailure { showError = true }
            } },
            onDismiss = { showProjectCreator = false; sshWorkspace = null })
        sshWorkspace?.let { project -> HarnessSshWorkspaceDialog(runtime, project,
            onReady = { created(project) }, onDismiss = { sshWorkspace = null }) }
    }
    if (showWorkspaceBrowser) {
        val openFiles: (HarnessWorkspaceEntity) -> Unit = { selected -> scope.launch {
            try {
                val captured = withContext(Dispatchers.IO) { runtime.workspaces.openWorkspace(selected.id) }
                AgentService.selectHarnessWorkspace(captured.conversation)
                navController.currentBackStackEntry?.savedStateHandle?.set("harness_workspace_files", true)
                showWorkspaceBrowser = false
                sshWorkspace = null
                navController.navigate(Screen.AgentWorkspace.route)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError = true }
        } }
        WorkspaceSelectionDialog(visibleWorkspaceRows,
            onSelect = { selected -> if (selected.backend == "REMOTE_SSH") sshWorkspace = selected else openFiles(selected) },
            onCreate = { title -> scope.launch {
                runCatching { runtime.workspaces.createWorkspace(title) }.onSuccess(openFiles).onFailure { showError = true }
            } },
            onCreateSsh = { title -> scope.launch {
                runCatching { runtime.workspaces.createWorkspace(title, remote = true) }
                    .onSuccess { sshWorkspace = it }.onFailure { showError = true }
            } },
            onDismiss = { showWorkspaceBrowser = false; sshWorkspace = null })
        sshWorkspace?.let { selected -> HarnessSshWorkspaceDialog(runtime, selected,
            onReady = { openFiles(selected) }, onDismiss = { sshWorkspace = null }) }
    }
    if (showError) AlertDialog(onDismissRequest = { showError = false }, text = { Text(stringResource(R.string.harness_runtime_failure)) },
        confirmButton = { TextButton(onClick = { showError = false }) { Text(stringResource(android.R.string.ok)) } })
}

/** Build the official authenticated session-log ZIP URL used by the Harness Web UI. */
internal fun buildHarnessSessionLogExportUrl(endpoint: HarnessEndpoint, sessionId: String): String {
    require(sessionId.isNotBlank()) { "SESSION_ID_REQUIRED" }
    val encodedSessionId = URLEncoder.encode(sessionId, StandardCharsets.UTF_8.name())
    return "${endpoint.url("/api/session.export")}?sessionId=$encodedSessionId&includeDescendants=true"
}

@Composable
private fun WorkspaceSelectionDialog(workspaces: List<HarnessWorkspaceEntity>, onSelect: (HarnessWorkspaceEntity) -> Unit, onCreate: (String) -> Unit,
    onCreateSsh: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.harness_workspace_choose)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            workspaces.forEach { workspace -> TextButton(onClick = { onSelect(workspace) }, modifier = Modifier.fillMaxWidth()) { Text(workspace.title) } }
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.harness_workspace_new)) }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { onCreateSsh(title.trim()) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.harness_workspace_create_ssh)) }
        } },
        confirmButton = { TextButton(onClick = { onCreate(title.trim()) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.harness_workspace_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

internal suspend fun uploadHarnessAttachment(runtime: HarnessAppRuntime, uri: Uri, sessionId: String): JsonObject = withContext(Dispatchers.IO) {
    // Content URIs are consumed by Android, never handed to guest code or exposed through a JS bridge.
    val context = runtime.applicationContext
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "attachment"
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 16 * 1024 * 1024) { "ATTACHMENT_TOO_LARGE" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("ATTACHMENT_UNREADABLE")
    val captured = runtime.workspaces.scope(sessionId)
    if (captured.localRoot != null) {
        val directory = runtime.workspaces.threadDirectory(captured)
        val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100).ifBlank { "attachment" }
        val relative = ".adt/threads/${directory.name}/attachments/${java.util.UUID.randomUUID()}-$safeName"
        runtime.files.writeBytes(captured, relative, bytes)
    }
    val data = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val result = requireNotNull(runtime.client).call("fileUploads", "upload", buildJsonObject {
        put("agentId", sessionId)
        putJsonObject("request") { put("name", name); put("data", data) }
    })
    when (result) {
        is HarnessRpcResult.Success -> result.value.jsonObject
        is HarnessRpcResult.Failure -> error(result.error.code)
    }
}

/** Capture the project identity before an asynchronous session create. */
private suspend fun prepareHarnessProject(runtime: HarnessAppRuntime, selected: HarnessWorkspaceEntity): JsonObject {
    val result = requireNotNull(runtime.client).call("adt", "prepareWorkspace", buildJsonObject {
        put("workspaceId", selected.id); put("guestPath", selected.guestPath); put("backend", selected.backend)
    })
    when (result) {
        is HarnessRpcResult.Failure -> error(result.error.code)
        is HarnessRpcResult.Success -> {
            val value = result.value as? JsonObject
            if (value?.get("ok") == kotlinx.serialization.json.JsonPrimitive(false)) {
                val failure = value["error"] as? JsonObject
                error((failure?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "WORKSPACE_PREPARE_FAILED")
            }
        }
    }
    return buildJsonObject { put("cwd", selected.guestPath) }
}
