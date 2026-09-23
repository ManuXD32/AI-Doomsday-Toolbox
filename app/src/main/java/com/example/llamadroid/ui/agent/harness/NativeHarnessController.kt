package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.R
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import com.example.llamadroid.harness.HarnessSessionEventSequencer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

private const val MAX_PENDING_ATTACHMENTS = 12

/**
 * App-owned adapter between the generic Harness client and the native surface.
 * Every mutation is serialized here; stream events only update presentation
 * state and never call the legacy Agent loop.
 */
class NativeHarnessController(
    parentScope: CoroutineScope,
    private val clientProvider: () -> HarnessClient?,
    private val runtime: NativeHarnessRuntimeCallbacks,
    private val workspace: NativeHarnessWorkspaceHooks = NativeHarnessWorkspaceHooks(),
    private val navigation: NativeHarnessNavigationHooks = NativeHarnessNavigationHooks(),
    private val invokeCapability: suspend (String, JsonObject) -> HarnessRpcResult =
        { _, _ -> capabilityUnavailableResult("CAPABILITY_UNAVAILABLE", "Capability is not connected") },
    /** Android-owned model rows (LiteRT and managed endpoints) for the native catalog. */
    private val localModelCatalog: suspend () -> JsonObject? = { null },
    private val interactions: NativeHarnessInteractionHooks? = null,
    private val jobs: NativeHarnessJobHooks = NativeHarnessJobHooks(),
    private val providerAuth: NativeHarnessProviderAuthHooks = NativeHarnessProviderAuthHooks(),
    /** Persists context/output overrides for Android-managed provider rows. */
    private val updateLocalModelCapability: suspend (wireId: String, contextTokens: Long?, maxOutputTokens: Long?) -> Unit =
        { _, _, _ -> },
    /** Host-only UI operations, such as opening Android's attachment picker. */
    private val handleExternalAction: suspend (NativeHarnessUiAction) -> Unit = {
        throw IllegalStateException("Host UI callback is not connected")
    },
    /** Receives the authenticated `session/attachment` value for a previewer. */
    private val deliverAttachment: suspend (sessionId: String, attachmentId: String, value: JsonObject) -> Unit = {
        _, _, _ -> throw IllegalStateException("Attachment viewer callback is not connected")
    },
    private val deliverCopiedText: suspend (sessionId: String, text: String) -> Unit = {
        _, _ -> throw IllegalStateException("Clipboard callback is not connected")
    },
    /** Metadata-only diagnostics; never pass prompts, tool output, or RPC arguments. */
    private val onDiagnostic: NativeHarnessDiagnosticCallback = { _, _, _, _ -> },
    private val sharedAttention: NativeHarnessAttentionStore? = null
) : NativeHarnessUiController, AutoCloseable {
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val connectionLock = Mutex()
    private val actionLock = Mutex()
    private val stateLock = Mutex()
    private val mutableState = MutableStateFlow(NativeHarnessUiState())
    private val sessionCwds = mutableMapOf<String, String?>()
    private val sessionTitles = mutableMapOf<String, String>()
    private val sessionPageStates = ConcurrentHashMap<String, HarnessSessionPageState>()
    private val assistantStreamTrackers = ConcurrentHashMap<String, HarnessAssistantStreamTracker>()
    private val assistantStreamUi by lazy {
        NativeHarnessAssistantStreamUi(assistantStreamTrackers) { transform -> mutate(transform) }
    }
    private var followJob: Job? = null
    private var controlJob: Job? = null
    private var activeClient: HarnessClient? = null
    /** Session-scoped cursor shared by reconnects and native/WebUI follow frames. */
    private val eventSequencer = HarnessSessionEventSequencer()
    private var lastConnectionAvailable: Boolean? = null
    private var currentActionFailed = false
    private val settingsRevisions get() = settingsReader.settingsRevisions
    private val providerBindings get() = settingsReader.providerBindings
    private val settingsReader: NativeHarnessSettingsReader by lazy {
        NativeHarnessSettingsReader(
            clientOrNull = { clientOrNull() }, state = state,
            mutate = { transform -> mutate(transform) },
            describeAuth = { providerAuthActions.describe(it) },
            isCurrent = { clientProvider() === it },
            reportFailure = { code, message -> reportFailure(code, message) },
            localModelCatalog = localModelCatalog,
        )
    }
    private val goalStore = NativeHarnessGoalStore()
    private val sessionAddresses = NativeHarnessSessionAddressStore()
    private val turnUiActions by lazy {
        NativeHarnessTurnUiActions(scope, { clientProvider() }, { state.value }, sessionAddresses::wireAddress,
            update = { transform -> mutate(transform) }, deliverCopy = deliverCopiedText,
            forked = { source, target ->
                val capturedClient = clientProvider()
                scope.launch {
                    try {
                        actionLock.withLock {
                            if (state.value.selectedSessionId == source && clientProvider() === capturedClient) {
                                refreshSessions(reportFailure = false)
                                if (state.value.selectedSessionId == source && clientProvider() === capturedClient) selectSession(target)
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { reportFailure(error) }
                }
            },
            report = { code, message -> reportFailure(code, message, event = "transcript_action") })
    }
    private val queueStore = NativeHarnessQueueStore()
    private val queueActions: NativeHarnessQueueActions by lazy {
        NativeHarnessQueueActions(
            clientProvider = { activeClient ?: clientProvider() },
            selectedSessionProvider = { state.value.selectedSessionId },
            reportFailure = { code, message -> reportFailure(code, message) }
        )
    }
    private val goalActions: NativeHarnessGoalActions by lazy {
        NativeHarnessGoalActions(
            clientProvider = { activeClient ?: clientProvider() },
            selectedSessionProvider = { state.value.selectedSessionId },
            selectedGoalProvider = { state.value.goal },
            updateGoal = { sessionId, goal -> publishGoal(sessionId, goal) },
            reportFailure = { code, message -> reportFailure(code, message) }
        )
    }
    private val permissionActions by lazy {
        NativeHarnessPermissionActions(
            clientProvider = { activeClient ?: clientProvider() },
            selectedSessionProvider = { state.value.selectedSessionId },
            mutate = { transform -> mutate(transform) },
            reportFailure = { code, message -> reportFailure(code, message) }
        )
    }
    private val pluginActions by lazy {
        NativeHarnessPluginActions(
            clientProvider = { clientOrNull() },
            stateProvider = { state.value },
            mutate = { transform -> mutate(transform) },
            reportFailure = { code, message -> reportFailure(code, message) }
        )
    }
    private val providerAuthActions by lazy {
        NativeHarnessProviderAuthActions(
            scope = scope,
            hooks = providerAuth,
            refresh = { refreshSettings(false) },
            update = { providerId, auth ->
                mutate { current ->
                    current.copy(provider = current.provider.copy(configs = current.provider.configs.map { config ->
                        if (config.id == providerId) config.copy(auth = auth) else config
                    }))
                }
            },
            reportFailure = { code, message -> reportFailure(code, message) }
        )
    }
    private val providerDiscoveryActions by lazy {
        NativeHarnessProviderDiscoveryActions(
            clientProvider = { clientOrNull() },
            configProvider = { providerId -> state.value.provider.configs.firstOrNull { it.id == providerId } },
            settingsNamespaceProvider = { providerId -> providerBindings[providerId]?.settingsNamespace },
            updateConfig = { providerId, transform ->
                mutate { current ->
                    current.copy(provider = current.provider.copy(configs = current.provider.configs.map { config ->
                        if (config.id == providerId) transform(config) else config
                    }))
                }
            },
            reportFailure = { code, message -> reportFailure(code, message) },
            reportSuccess = { count ->
                mutate {
                    it.copy(
                        notice = HarnessNoticeUi(
                            titleRes = R.string.harness_notice_models_discovered_title,
                            messageRes = R.string.harness_notice_models_discovered,
                            messageArgs = listOf(count),
                        )
                    )
                }
            },
        )
    }
    private val settingsActions by lazy {
        NativeHarnessSettingsActions(
            clientProvider = { clientOrNull() },
            stateProvider = { state.value },
            revisionProvider = { settingsRevisions[it] },
            takenProviderRoutes = { (providerBindings.keys + state.value.provider.providers.map { it.id }).toSet() },
            mutate = { transform -> mutate(transform) },
            refresh = { refreshSettings(reportFailure = false) },
            reportFailure = { code, message -> reportFailure(code, message) },
            refreshModelCatalog = { refreshModelCatalog(reportFailure = false) },
        )
    }
    private val providerCredentialActions by lazy {
        NativeHarnessProviderCredentialActions(
            clientProvider = { clientOrNull() },
            bindingProvider = { providerBindings[it] },
            refresh = {
                refreshSettings(reportFailure = false)
                refreshModelCatalog(reportFailure = false)
            },
            reportFailure = { code, message -> reportFailure(code, message) }
        )
    }
    private val feedbackActions by lazy {
        NativeHarnessFeedbackActions(
            clientProvider = { activeClient ?: clientProvider() },
            selectedSessionProvider = { state.value.selectedSessionId },
            stateProvider = { state.value },
            mutate = { transform -> mutate(transform) },
            reportFailure = { code, message -> reportFailure(code, message) }
        )
    }
    private val offlineSessions by lazy {
        NativeHarnessOfflineSessions(
            hooks = workspace,
            state = { state.value },
            mutate = { transform -> mutate(transform) },
            sessionCwds = sessionCwds,
            sessionTitles = sessionTitles,
            resolveWorkspace = { id, title, cwd, archived -> resolveWorkspace(id, title, cwd, archived) },
            stopFollow = { followJob?.cancel() },
            reportFailure = { code, message -> reportFailure(code, message) }
        )
    }
    private val parityActions by lazy {
        NativeHarnessParityController(
            scope = scope,
            clientProvider = { activeClient ?: clientProvider() },
            selectedSessionProvider = { state.value.selectedSessionId },
            mutateState = { transform -> mutate(transform) },
            reportFailure = { code, message -> reportFailure(code, message) },
            refreshSessions = { refreshSessions(reportFailure = false) },
            handleExternalAction = handleExternalAction,
            reconcileWorkspaceGroup = workspace.reconcileWorkspaceGroup,
        )
    }
    private val structuredTranscriptActions by lazy {
        NativeHarnessStructuredTranscriptActions(
            scope,
            { activeClient ?: clientProvider() },
            { state.value },
            { transform -> mutate(transform) },
            { code, message -> reportFailure(code, message) },
            { id -> sessionAddresses.wireAddress(id) },
        )
    }
    private val attention = sharedAttention ?: NativeHarnessAttentionStore(
        scope, { activeClient ?: clientProvider() },
        onFailure = { error -> if (ownerJob.isActive) reportFailure(error, event = "parser") }
    )
    override val state: StateFlow<NativeHarnessUiState> = mutableState.asStateFlow()
    private val referenceCatalogs by lazy {
        NativeHarnessReferenceCatalogs(
            clientProvider = { clientOrNull() },
            selectedSession = { state.value.selectedSessionId },
            isClientCurrent = { client -> clientProvider() === client },
            update = { transform -> mutate(transform) },
            report = { code, message -> reportFailure(code, message) },
        )
    }
    private val management by lazy {
        NativeHarnessManagementLoader(scope, clientProvider, { state.value }, { mutableState.update(it) }) { area ->
            when (area) {
                HarnessManagementArea.SETTINGS -> {
                    refreshSettings(reportFailure = true)
                    refreshModelCatalog(reportFailure = true)
                    permissionActions.refresh(report = true)
                    parityActions.dispatch(NativeHarnessUiAction.RefreshAgentPresets)
                }
                HarnessManagementArea.EXTENSIONS -> {
                    refreshPlugins(reportFailure = true)
                    refreshSkills(reportFailure = true)
                    parityActions.dispatch(NativeHarnessUiAction.RefreshCordis)
                }
                HarnessManagementArea.SESSIONS -> parityActions.dispatch(NativeHarnessUiAction.RefreshWorkspaces)
            }
        }
    }
    init {
        scope.launch {
            combine(attention.entries, mutableState.map { it.selectedSessionId }.distinctUntilChanged()) { entries, id ->
                entries.filter { it.sessionId == id }
            }.collect { entries ->
                setPendingInteractions(entries.mapNotNull { it.question }, entries.mapNotNull { it.approval }, entries.mapNotNull { it.plan })
            }
        }
        scope.launch { attention.emits.collect { applyRemoteEmit(it.event, it.args) } }
        scope.launch { initialize() }
    }

    /** Endpoint publication may follow the RUNNING database update. */
    suspend fun onEndpointChanged() {
        connectionLock.withLock {
            if (clientProvider() !== activeClient) {
                management.invalidate()
                initializeConnected()
            }
        }
    }

    /** Let the runtime graph publish its authoritative status without polling. */
    fun setRuntimeState(next: HarnessRuntimeUiState) {
        mutableState.update { it.copy(runtime = next) }
    }

    /** Compatibility name for graph observers that forward runtime changes. */
    fun onRuntimeChanged(next: HarnessRuntimeUiState) {
        setRuntimeState(next)
    }

    /** Forward a Compose/render recovery or failure without persisting content. */
    suspend fun notifyRenderDiagnostic(status: String, errorClass: String? = null) {
        emitDiagnostic(event = "render", status = status, errorClass = errorClass)
    }

    /** Retained legacy history is owned by the app graph, not by Harness. */
    fun setLegacyHistoryAvailable(available: Boolean) {
        mutableState.update { it.copy(legacyHistoryAvailable = available) }
    }

    /** Publish authenticated Web UI availability from the shared endpoint owner. */
    fun setOriginalWebUiState(next: HarnessWebUiState) {
        mutableState.update { it.copy(originalWebUi = next) }
    }

    fun setDeletingSessions(ids: Set<String>, failed: Set<String> = emptySet()) {
        mutableState.update { it.copy(deletingSessionIds = ids, failedSessionDeletionIds = failed) }
    }

    /** Publish pending interaction cards received from the runtime event adapter. */
    fun setPendingInteractions(
        questions: List<HarnessQuestionUi>,
        approvals: List<HarnessApprovalUi>,
        plans: List<HarnessPlanUi>
    ) {
        mutableState.update {
            it.copy(
            questions = questions,
            approvals = approvals,
            plans = plans
            )
        }
    }

    /** Refresh runtime/session projections and invalidate manager snapshots after Web UI use. */
    suspend fun refreshAfterWebView() {
        connectionLock.withLock {
            refreshRuntime(reportFailure = false)
            refreshSessions(reportFailure = false)
            management.invalidate()
            startControlStream()
        }
    }

    /** Attach one completed file upload receipt to the current composer. */
    suspend fun addAttachment(receipt: JsonObject) {
        val sessionId = state.value.selectedSessionId
            ?: return reportFailure("ATTACHMENT_SESSION_MISSING", "Select a Harness session before attaching a file")
        addAttachment(sessionId, receipt)
    }

    /**
     * Attach a receipt to the session captured before the Android picker opened.
     * A picker may outlive a session switch, so refuse to put the file into a
     * different composer rather than silently misrouting it.
     */
    suspend fun addAttachment(capturedSessionId: String, receipt: JsonObject) {
        val receiptId = receipt.string("receiptId")
            ?: return reportFailure("ATTACHMENT_RECEIPT_MISSING", "Harness did not return an upload receipt")
        val file = receipt.objectValue("file")
        val label = file?.string("name") ?: file?.string("filename") ?: receiptId
        val mediaType = file?.string("mediaType") ?: file?.string("mimeType") ?: "application/octet-stream"
        var accepted = false
        stateLock.withLock {
            mutableState.update { current ->
                if (capturedSessionId.isBlank() || capturedSessionId != current.selectedSessionId) {
                    current
                } else {
                    accepted = true
                    val attachment = HarnessAttachmentUi(
                        id = receiptId,
                        label = label,
                        mediaType = mediaType,
                        isUploading = false,
                        canRemove = true
                    )
                    current.copy(
                        attachments = (current.attachments.filterNot { it.id == receiptId } + attachment)
                            .takeLast(MAX_PENDING_ATTACHMENTS)
                    )
                }
            }
        }
        if (!accepted) {
            reportFailure(
                "ATTACHMENT_SESSION_CHANGED",
                "The Harness session changed while the file picker was open"
            )
        }
    }

    override fun dispatch(action: NativeHarnessUiAction) {
        if (state.value.withImmediateEdit(action) != null) {
            mutableState.update { it.withImmediateEdit(action) ?: it }
            return
        }
        if (action is NativeHarnessUiAction.LoadManagement) {
            management.request(action.area, action.force)
            return
        }
        scope.launch {
            try {
                currentActionFailed = false
                // Runtime stop operations must be able to interrupt a slow start.
                if (action is NativeHarnessUiAction.StartRuntime ||
                    action is NativeHarnessUiAction.StopRuntime ||
                    action is NativeHarnessUiAction.ForceStopRuntime ||
                    action is NativeHarnessUiAction.CancelTurn ||
                    action is NativeHarnessUiAction.CancelPluginInstall ||
                    action is NativeHarnessUiAction.StopCordis ||
                    action is NativeHarnessUiAction.RemoveCordis ||
                    action is NativeHarnessUiAction.RejectCordis ||
                    action is NativeHarnessUiAction.LoadTranscriptDetail ||
                    action is NativeHarnessUiAction.CancelTranscriptCopy
                ) {
                    dispatchInternal(action)
                } else {
                    actionLock.withLock { dispatchInternal(action) }
                }
                if (!currentActionFailed) emitDiagnostic(event = "action", status = "recovered")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportFailure(error)
            }
        }
    }

    override fun close() {
        turnUiActions.close()
        parityActions.close()
        structuredTranscriptActions.close()
        providerAuthActions.close()
        followJob?.cancel()
        controlJob?.cancel()
        if (sharedAttention == null) attention.attach(null)
        ownerJob.cancel()
    }
    private suspend fun initialize() = connectionLock.withLock {
        refreshRuntime(reportFailure = false)
        initializeConnected()
    }
    private suspend fun initializeConnected() {
        refreshSessions(reportFailure = false)
        if (clientProvider() == null) return
        startControlStream()
        scope.launch { refreshModelCatalog(reportFailure = false) }
    }
    private suspend fun dispatchInternal(action: NativeHarnessUiAction) {
        when (action) {
            NativeHarnessUiAction.DismissNotice -> mutate { it.copy(notice = null) }
            is NativeHarnessUiAction.LoadManagement -> management.request(action.area, action.force)
            NativeHarnessUiAction.StartRuntime -> updateRuntime(HarnessRuntimeStatus.STARTING) { runtime.start() }
            NativeHarnessUiAction.StopRuntime -> { turnUiActions.invalidateSelection(); updateRuntime(HarnessRuntimeStatus.STOPPING) { runtime.stop() } }
            NativeHarnessUiAction.ForceStopRuntime -> { turnUiActions.invalidateSelection(); updateRuntime(HarnessRuntimeStatus.STOPPING) { runtime.forceStop() } }
            NativeHarnessUiAction.OpenRuntimeJournal,
            NativeHarnessUiAction.RefreshRuntimeDiagnostics,
            NativeHarnessUiAction.CopyRuntimeDiagnostics -> handleExternalAction(action)
            NativeHarnessUiAction.Continue -> {
                mutate { it.copy(notice = null) }
                refreshRuntime(reportFailure = false)
                refreshSessions(reportFailure = false)
                restartSelectedSubscriptions()
            }
            NativeHarnessUiAction.OpenWorkspace -> workspace.openWorkspace(state.value.workspace)
            NativeHarnessUiAction.OpenProjectIntegration -> handleExternalAction(action)
            NativeHarnessUiAction.OpenOriginalWebUi -> navigation.openOriginalWebUi()
            NativeHarnessUiAction.OpenModelManager -> mutate {
                it.copy(provider = it.provider.copy(modelEditorOpen = true))
            }
            NativeHarnessUiAction.CloseModelManager -> mutate {
                it.copy(provider = it.provider.copy(modelEditorOpen = false))
            }
            NativeHarnessUiAction.OpenLegacyHistory -> workspace.openLegacyHistory()
            is NativeHarnessUiAction.ExportSessionLog -> parityActions.dispatch(action)
            NativeHarnessUiAction.CreateSession -> createSession()
            is NativeHarnessUiAction.SelectSession -> selectSession(action.sessionId)
            is NativeHarnessUiAction.RenameSession -> renameSession(action)
            is NativeHarnessUiAction.ForkSession -> forkSession(action)
            is NativeHarnessUiAction.DeleteSession -> handleExternalAction(action)
            is NativeHarnessUiAction.ArchiveSession -> archiveSession(action.sessionId, archived = true)
            is NativeHarnessUiAction.UnarchiveSession -> archiveSession(action.sessionId, archived = false)
            is NativeHarnessUiAction.SearchSessions -> searchSessions(action.query)
            NativeHarnessUiAction.RefreshSessions -> refreshSessions(reportFailure = true)
            is NativeHarnessUiAction.UpdateComposer -> mutate { it.copy(composerText = action.text) }
            is NativeHarnessUiAction.SetComposerMode -> mutate { it.copy(composerMode = action.mode) }
            NativeHarnessUiAction.OpenAttachmentPicker -> handleExternalAction(action)
            NativeHarnessUiAction.OpenReferencePicker -> handleExternalAction(action)
            is NativeHarnessUiAction.OpenTranscriptLink -> if (action.sessionId == state.value.selectedSessionId) {
                handleExternalAction(action.copy(
                    workspaceSessionId = sessionAddresses.addressFor(action.sessionId)?.parentSessionId ?: action.sessionId,
                ))
            }
            is NativeHarnessUiAction.InsertReference -> {
                if (state.value.selectedSessionId == action.sessionId) {
                    mutate { it.copy(composerText = com.example.llamadroid.harness.insertHarnessReference(it.composerText, action.mention)) }
                } else reportFailure("ATTACHMENT_SESSION_CHANGED", "The destination session changed")
            }
            is NativeHarnessUiAction.OpenAttachment -> openAttachment(action.attachmentId)
            is NativeHarnessUiAction.RemoveAttachment -> mutate {
                it.copy(attachments = it.attachments.filterNot { attachment -> attachment.id == action.attachmentId })
            }
            NativeHarnessUiAction.SubmitComposer -> submitComposer()
            NativeHarnessUiAction.CancelTurn -> cancelTurn()
            is NativeHarnessUiAction.EditQueueItem -> queueActions.edit(action.itemId, action.content)
            is NativeHarnessUiAction.RemoveQueueItem -> queueActions.remove(action.itemId)
            is NativeHarnessUiAction.SteerQueueItem -> queueActions.steer(action.itemId)
            is NativeHarnessUiAction.UpdateCommandLine -> mutate { it.copy(commandLine = action.line) }
            NativeHarnessUiAction.ExecuteCommand -> executeCommand()
            is NativeHarnessUiAction.AnswerQuestion -> answerQuestion(action)
            is NativeHarnessUiAction.Approve -> decideApproval(action.approvalId, approved = true)
            is NativeHarnessUiAction.Reject -> decideApproval(action.approvalId, approved = false)
            is NativeHarnessUiAction.ApprovePlan -> decidePlan(action.planId, approved = true)
            is NativeHarnessUiAction.RejectPlan -> decidePlan(action.planId, approved = false)
            is NativeHarnessUiAction.CreateGoal,
            is NativeHarnessUiAction.EditGoal,
            NativeHarnessUiAction.PauseGoal,
            NativeHarnessUiAction.ResumeGoal,
            NativeHarnessUiAction.CompleteGoal,
            NativeHarnessUiAction.ClearGoal -> goalActions.dispatch(action)
            is NativeHarnessUiAction.ToggleTranscriptItem -> mutate { current ->
                current.copy(transcript = current.transcript.map { item ->
                    if (item.id == action.itemId) item.copy(isExpanded = !item.isExpanded) else item
                })
            }
            is NativeHarnessUiAction.LoadTranscriptDetail ->
                structuredTranscriptActions.loadDetail(action.itemId, action.page)
            is NativeHarnessUiAction.CopyTranscriptTurn,
            is NativeHarnessUiAction.CopyTranscriptMessage,
            is NativeHarnessUiAction.ForkTranscriptTurn,
            NativeHarnessUiAction.CancelTranscriptCopy -> turnUiActions.dispatch(action)
            is NativeHarnessUiAction.SubmitMessageFeedback -> feedbackActions.submit(
                action.messageId,
                action.rating,
                action.note,
                action.category
            )
            is NativeHarnessUiAction.RetractMessageFeedback -> feedbackActions.retract(
                action.messageId,
                action.rating
            )
            is NativeHarnessUiAction.LoadOlderMessages -> loadOlderMessages()
            is NativeHarnessUiAction.SelectProvider -> selectProvider(action.providerId)
            is NativeHarnessUiAction.SelectModel -> selectModel(action.modelName)
            is NativeHarnessUiAction.SelectSessionModel -> selectModel(action.modelId, action.providerId)
            NativeHarnessUiAction.CreateProvider -> createProvider()
            is NativeHarnessUiAction.CreateCustomProvider -> settingsActions.createCustomProvider(action.request)
            is NativeHarnessUiAction.DiscoverCustomProviderModels ->
                settingsActions.discoverCustomProviderModels(action.request)
            NativeHarnessUiAction.DismissCustomProviderModels -> settingsActions.dismissCustomProviderModels()
            is NativeHarnessUiAction.EditProvider,
            is NativeHarnessUiAction.SaveProvider -> saveProvider(action)
            is NativeHarnessUiAction.DeleteProvider -> deleteProvider(action.providerId)
            is NativeHarnessUiAction.DiscoverProviderModels -> providerDiscoveryActions.discover(action.providerId)
            is NativeHarnessUiAction.ToggleDiscoveredProviderModel ->
                providerDiscoveryActions.toggle(action.providerId, action.modelId)
            is NativeHarnessUiAction.SetDiscoveredProviderModelsSelection ->
                providerDiscoveryActions.setSelection(action.providerId, action.modelIds, action.selected)
            is NativeHarnessUiAction.AdoptDiscoveredProviderModels -> {
                if (providerDiscoveryActions.adopt(action.providerId)) {
                    saveProvider(NativeHarnessUiAction.SaveProvider(action.providerId))
                }
            }
            is NativeHarnessUiAction.DismissDiscoveredProviderModels ->
                providerDiscoveryActions.dismiss(action.providerId)
            is NativeHarnessUiAction.UpdateProviderField -> updateProviderField(action)
            is NativeHarnessUiAction.UpdateLocalModelCapability -> {
                updateLocalModelCapability(action.wireId, action.contextTokens, action.maxOutputTokens)
                refreshModelCatalog(reportFailure = false)
            }
            is NativeHarnessUiAction.SetProviderCredential -> setProviderCredential(action)
            is NativeHarnessUiAction.UnsetProviderCredential -> unsetProviderCredential(action.providerId)
            is NativeHarnessUiAction.StartProviderLogin -> providerAuthActions.login(action.providerId, action.method)
            is NativeHarnessUiAction.AnswerProviderLogin -> providerAuthActions.answer(
                action.providerId,
                action.requestId,
                action.answer,
                action.declined
            )
            is NativeHarnessUiAction.CancelProviderLogin -> providerAuthActions.cancel(action.providerId, action.requestId)
            is NativeHarnessUiAction.LogoutProvider -> providerAuthActions.logout(action.providerId)
            NativeHarnessUiAction.RefreshModelCatalog -> refreshModelCatalog(reportFailure = true)
            is NativeHarnessUiAction.SelectReasoningEffort -> selectReasoningEffort(action.effortId)
            NativeHarnessUiAction.RefreshPermissionCatalog -> permissionActions.refresh(report = true)
            is NativeHarnessUiAction.SelectPermissionPreset -> permissionActions.select(action.value)
            is NativeHarnessUiAction.InvokeCapability -> invokeCapabilityAction(action)
            is NativeHarnessUiAction.SetThinkingEnabled -> setThinkingEnabled(action.enabled)
            is NativeHarnessUiAction.UpdateProviderSetting -> updateProviderSetting(action)
            is NativeHarnessUiAction.UpdateSchemaField -> settingsActions.update(action.key, action.value)
            is NativeHarnessUiAction.ResetSchemaField -> settingsActions.reset(action.key)
            NativeHarnessUiAction.OpenSettingsDocument -> settingsActions.openDocument()
            is NativeHarnessUiAction.SaveSettingsDocument -> settingsActions.saveDocument(action.text)
            NativeHarnessUiAction.CloseSettingsDocument -> settingsActions.closeDocument()
            is NativeHarnessUiAction.OpenAgentPresetDirectory -> handleExternalAction(action)
            is NativeHarnessUiAction.InstallPlugin -> installPlugin(action)
            is NativeHarnessUiAction.InspectPlugin -> inspectPlugin(action.packageSpec)
            is NativeHarnessUiAction.CancelPluginInstall -> cancelPluginInstall(action.requestId)
            is NativeHarnessUiAction.UninstallPlugin -> pluginCall("removeBundle", buildJsonObject { put("name", action.pluginId) })
            is NativeHarnessUiAction.SetPluginBundleEnabled -> pluginCall("setBundleEnabled", buildJsonObject {
                put("name", action.bundleName); put("enabled", action.enabled)
            })
            is NativeHarnessUiAction.SetPluginEnabled -> pluginCall("setPluginEnabled", buildJsonObject {
                put("id", action.pluginId); put("enabled", action.enabled)
            })
            is NativeHarnessUiAction.SetPluginRowEnabled -> pluginActions.setRowEnabled(
                action.entryId,
                action.enabled
            )
            is NativeHarnessUiAction.ApprovePluginBuilds -> installPlugin(
                NativeHarnessUiAction.InstallPlugin(action.packageSpec, action.approvedBuilds)
            )
            NativeHarnessUiAction.RefreshPlugins -> refreshPlugins(reportFailure = true)
            is NativeHarnessUiAction.RefreshAgentPresets,
            is NativeHarnessUiAction.ReadAgentPreset,
            is NativeHarnessUiAction.DuplicateAgentPreset,
            is NativeHarnessUiAction.DeleteAgentPreset,
            is NativeHarnessUiAction.SetAgentPresetDefault,
            is NativeHarnessUiAction.SetAgentPresetModeSelection,
            is NativeHarnessUiAction.SelectAgentPreset,
            NativeHarnessUiAction.RefreshWorkspaces,
            is NativeHarnessUiAction.RenameWorkspace,
            is NativeHarnessUiAction.DeleteWorkspace,
            is NativeHarnessUiAction.MoveWorkspace,
            is NativeHarnessUiAction.MoveSessionInWorkspace,
            NativeHarnessUiAction.RefreshCordis,
            is NativeHarnessUiAction.RunCordis,
            is NativeHarnessUiAction.ApproveCordis,
            is NativeHarnessUiAction.RejectCordis,
            is NativeHarnessUiAction.StopCordis,
            is NativeHarnessUiAction.RemoveCordis,
            is NativeHarnessUiAction.InspectCordis -> parityActions.dispatch(action)
            is NativeHarnessUiAction.SetSkillEnabled -> reportFailure(
                "SKILLS_READ_ONLY",
                "This Harness release exposes skills as a session read-only catalog"
            )
            is NativeHarnessUiAction.RemoveSkill -> reportFailure(
                "SKILLS_READ_ONLY",
                "This Harness release does not expose skill removal"
            )
            NativeHarnessUiAction.RefreshSkills -> refreshSkills(reportFailure = true)
            is NativeHarnessUiAction.ReadJob -> invokeJobAction(jobs.read, action.jobId)
            is NativeHarnessUiAction.KillJob -> invokeJobAction(jobs.kill, action.jobId)
            NativeHarnessUiAction.RefreshJobs -> startControlStream()
            is NativeHarnessUiAction.SetSubagentsEnabled -> reportFailure(
                "SUBAGENTS_READ_ONLY",
                "Subagent settings are managed by the Harness composition"
            )
            is NativeHarnessUiAction.SetSubagentConcurrency -> reportFailure(
                "SUBAGENTS_READ_ONLY",
                "Subagent concurrency is managed by the Harness composition"
            )
            is NativeHarnessUiAction.PromptSubagent -> promptSubagent(action.childSessionId)
            is NativeHarnessUiAction.InterruptSubagent -> interruptSubagent(action.childSessionId)
            NativeHarnessUiAction.RefreshSubagents -> refreshSubagents()
            is NativeHarnessUiAction.SetActualDuration -> mutate { it.copy(extensions = it.extensions.copy(
                trajectory = it.extensions.trajectory.copy(actualDuration = action.enabled)
            )) }
            is NativeHarnessUiAction.SetActualTime -> mutate { it.copy(extensions = it.extensions.copy(
                trajectory = it.extensions.trajectory.copy(actualTime = action.enabled)
            )) }
            NativeHarnessUiAction.ToggleAllTrajectoryTurns -> mutate { it.copy(extensions = it.extensions.copy(
                trajectory = it.extensions.trajectory.copy(
                    allTurnsCollapsed = !it.extensions.trajectory.allTurnsCollapsed
                )
            )) }
            NativeHarnessUiAction.ToggleAllTrajectoryCalls -> mutate { it.copy(extensions = it.extensions.copy(
                trajectory = it.extensions.trajectory.copy(
                    allAssistantsCollapsed = !it.extensions.trajectory.allAssistantsCollapsed
                )
            )) }
            is NativeHarnessUiAction.UpdateTrajectorySearch -> mutate { it.copy(extensions = it.extensions.copy(
                trajectory = it.extensions.trajectory.copy(searchQuery = action.query)
            )) }
            NativeHarnessUiAction.LoadOlderTrajectory -> loadOlderMessages()
        }
    }
    private suspend fun updateRuntime(
        pendingStatus: HarnessRuntimeStatus,
        operation: suspend () -> HarnessRuntimeUiState
    ) {
        mutate { it.copy(runtime = it.runtime.copy(status = pendingStatus)) }
        val next = operation()
        mutate { it.copy(runtime = next, notice = null) }
        if (next.status == HarnessRuntimeStatus.RUNNING) {
            connectionLock.withLock {
                initializeConnected()
                restartSelectedSubscriptions()
            }
        }
    }
    private suspend fun restartSelectedSubscriptions() {
        attention.restart()
        state.value.selectedSessionId?.let { selectSession(it) }
        startControlStream()
    }
    private suspend fun refreshRuntime(reportFailure: Boolean) {
        try {
            val currentRuntime = runtime.current()
            mutate { it.copy(runtime = currentRuntime) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (reportFailure) reportFailure(error)
        }
    }
    private suspend fun clientOrNull(): HarnessClient? {
        val client = clientProvider()
        if (client !== activeClient) {
            turnUiActions.invalidateSelection()
            structuredTranscriptActions.invalidate()
            followJob?.cancel()
            controlJob?.cancel()
            queueStore.resetClient()
            sessionAddresses.reset()
            eventSequencer.reset()
            activeClient = client
            if (sharedAttention == null) attention.attach(client)
            mutate { current -> current.copy(queue = emptyList(), commands = emptyList(),
                extensions = current.extensions.copy(skills = emptyList())) }
        }
        val available = client != null
        if (lastConnectionAvailable != available) {
            lastConnectionAvailable = available
            emitDiagnostic(
                event = "connection",
                status = if (available) "recovered" else "failure",
                errorClass = if (available) null else "HARNESS_UNAVAILABLE"
            )
        }
        if (client == null) {
            mutate {
                it.copy(
                    notice = HarnessNoticeUi(
                        titleRes = R.string.harness_notice_unavailable,
                        messageRes = R.string.harness_notice_start_runtime
                    )
                )
            }
        }
        return client
    }
    private suspend fun startControlStream() {
        val client = clientOrNull() ?: return
        controlJob?.cancel()
        controlJob = scope.launch(Dispatchers.IO) {
            var parserRecovered = false
            try {
                client.stream("session", "control", policy = HarnessStreamPolicy.SessionFollow).collect { frame ->
                    applyControlFrame(frame)
                    if (!parserRecovered) {
                        parserRecovered = true
                        emitDiagnostic(event = "parser", status = "recovered")
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (ownerJob.isActive) reportFailure(error, event = "parser")
            }
        }
    }
    private suspend fun refreshSessions(reportFailure: Boolean) {
        val client = clientOrNull()
        if (client == null) {
            offlineSessions.refresh(reportFailure)
            return
        }
        workspace.invalidateSessionProjection()
        when (val result = client.listSessions()) {
            is HarnessRpcResult.Failure -> {
                if (reportFailure) reportFailure(result.error.code, result.error.message)
            }
            is HarnessRpcResult.Success -> {
                val old = state.value.sessions.associateBy { it.id }
                val removedSessionIds = buildSet {
                    result.value.objectArray("items").forEach { raw ->
                        val id = raw.string("sessionId") ?: return@forEach
                        if (workspace.isSessionRemoved(id, raw.string("cwd"))) add(id)
                    }
                }
                val rows = result.value.objectArray("items").mapNotNull { raw ->
                    val id = raw.string("sessionId") ?: return@mapNotNull null
                    if (id in removedSessionIds) return@mapNotNull null
                    val address = sessionAddresses.observeListRow(raw)
                    queueStore.setSessionCapabilities(
                        id,
                        queueMutable = address?.queueMutable,
                        running = raw.boolean("running"),
                    )
                    queueStore.applyListSnapshot(id, raw)
                    val cwd = raw.string("cwd")
                    val previous = old[id]
                    val projection = readHarnessSessionProjection(workspace, id)
                    val title = projection?.title?.takeIf { it.isNotBlank() }
                        ?: sessionTitles[id] ?: previous?.title ?: harnessDefaultSessionTitle(id, cwd)
                    sessionCwds[id] = cwd
                    val archived = projection?.archived ?: raw.boolean("archived") ?: previous?.isArchived ?: false
                    val resolved = resolveWorkspace(id, title, cwd, archived)
                    val project = resolved?.projectFolder ?: previous?.projectFolder ?: harnessProjectFromCwd(cwd)
                    val backend = resolved?.backendLabel ?: previous?.backendLabel ?: "Harness"
                    HarnessSessionUiState(
                        id = id,
                        title = title,
                        projectFolder = project,
                        backendLabel = backend,
                        lastActivityLabel = raw.long("updatedAt")?.toString(),
                        unreadCount = previous?.unreadCount ?: 0,
                        isSelected = false,
                        isRunning = raw.boolean("running") ?: false,
                        isArchived = archived,
                        canRename = true,
                        canFork = !raw.boolean("blank").orDefault(false),
                        canArchive = !archived,
                        canUnarchive = archived
                    )
                }
                val currentSelected = state.value.selectedSessionId
                val selectedId = currentSelected?.takeIf { id -> rows.any { it.id == id } }
                    ?: rows.firstOrNull()?.id
                mutate { current ->
                    current.copy(
                        sessions = rows.map { it.copy(isSelected = it.id == selectedId) },
                        selectedSessionId = selectedId,
                        commands = if (current.selectedSessionId == selectedId) current.commands else emptyList(),
                        extensions = if (current.selectedSessionId == selectedId) current.extensions else current.extensions.copy(skills = emptyList()),
                        queue = selectedId?.let(queueStore::items).orEmpty()
                    )
                }
                if (selectedId != null && selectedId != currentSelected) selectSession(selectedId)
            }
        }
    }
    private suspend fun searchSessions(query: String) {
        mutate { it.copy(sessionSearchQuery = query) }
        if (query.isBlank()) {
            mutate { it.copy(sessionSearchResults = emptyList(), sessionSearchHasMore = false) }
            return
        }
        val client = clientOrNull() ?: return
        when (val result = client.searchSessions(query.trim())) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                // Tombstoning is asynchronous with respect to an in-flight search. Resolve
                // visibility before entering the non-suspending state transform so a stale
                // result can never be rendered or selected from the native picker.
                val visibleResults = result.value.objectArray("items").mapNotNull { item ->
                    val id = item.string("sessionId") ?: return@mapNotNull null
                    if (workspace.isSessionRemoved(id, item.string("cwd"))) return@mapNotNull null
                    HarnessSessionSearchResultUi(
                        sessionId = id,
                        title = sessionTitles[id] ?: harnessDefaultSessionTitle(id, sessionCwds[id]),
                        snippet = item.string("snippet").orEmpty()
                    )
                }
                val hasMore = result.value.boolean("hasMore").orDefault(false)
                mutate { current -> current.copy(
                    sessionSearchResults = visibleResults,
                    sessionSearchHasMore = hasMore,
                ) }
            }
        }
    }
    private suspend fun createSession() {
        val client = clientOrNull() ?: return
        val preferred = state.value.provider
        when (val result = client.createSession(workspace.createSessionArgs())) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                val id = result.value.string("sessionId") ?: return reportFailure(
                    "SESSION_ID_MISSING",
                    "Harness did not return the created session identity"
                )
                refreshSessions(reportFailure = false)
                selectSession(id)
                if (preferred.selectedProviderId != null && preferred.selectedModel != null) {
                    mutate { it.copy(provider = it.provider.copy(
                        selectedProviderId = preferred.selectedProviderId,
                        selectedModel = preferred.selectedModel,
                        thinkingEnabled = preferred.thinkingEnabled,
                        selectedReasoningEffort = preferred.selectedReasoningEffort,
                    )) }
                    selectModel(preferred.selectedModel)
                }
            }
        }
    }
    private suspend fun selectSession(sessionId: String) {
        if (sessionId.isBlank()) return
        if (workspace.isSessionRemoved(sessionId, sessionCwds[sessionId])) {
            return reportFailure(
                "PROJECT_REMOVED",
                "The selected project is no longer available",
            )
        }
        turnUiActions.invalidateSelection()
        structuredTranscriptActions.invalidate()
        val client = clientOrNull()
        if (client == null) {
            offlineSessions.select(sessionId)
            return
        }
        val selectedAddress = sessionAddresses.ensureRoutable(client, sessionId)
        queueStore.setSessionCapabilities(sessionId, queueMutable = selectedAddress?.queueMutable)
        val wireAddress = selectedAddress?.toWire() ?: sessionAddresses.wireAddress(sessionId)
        if (wireAddress == null) {
            return reportFailure(
                "SUBAGENT_ADDRESS_UNAVAILABLE",
                "The selected subagent address is not available yet",
            )
        }
        sessionPageStates.remove(sessionId)
        assistantStreamTrackers.remove(sessionId)
        eventSequencer.reset(sessionId)
        val current = state.value.sessions.firstOrNull { it.id == sessionId }
        val projection = readHarnessSessionProjection(workspace, sessionId)
        val title = projection?.title?.takeIf { it.isNotBlank() }
            ?: current?.title ?: sessionTitles[sessionId] ?: harnessDefaultSessionTitle(sessionId, sessionCwds[sessionId])
        val archived = projection?.archived ?: current?.isArchived ?: false
        val resolved = resolveWorkspace(sessionId, title, sessionCwds[sessionId], archived)
        mutate { existing ->
            existing.copy(
                selectedSessionId = sessionId,
                canCancelTurn = current?.isRunning == true,
                isStoppingTurn = false,
                provider = existing.provider.copy(selectedReasoningEffort = null),
                commands = emptyList(),
                extensions = existing.extensions.copy(skills = emptyList()),
                workspace = resolved ?: existing.workspace.copy(
                    projectFolder = current?.projectFolder ?: harnessProjectFromCwd(sessionCwds[sessionId]),
                    backendLabel = current?.backendLabel ?: "Harness"
                ),
                sessions = existing.sessions.map { it.copy(isSelected = it.id == sessionId) },
                transcript = emptyList(),
                structuredTranscript = emptyList(),
                structuredDetail = existing.structuredDetail.copy(generation = existing.structuredDetail.generation + 1, itemId = null, page = null, isLoading = false),
                tokenUsage = null,
                contextPressure = null,
                contextBreakdown = null,
                sessionStats = null,
                messageFeedback = emptyMap(),
                messageFeedbackLoaded = false,
                queue = queueStore.items(sessionId),
                questions = emptyList(),
                approvals = emptyList(),
                plans = emptyList(),
                goal = goalStore.get(sessionId),
                canLoadOlderMessages = false,
                isLoadingOlderMessages = false,
                notice = null
            )
        }
        followJob?.cancel()
        followJob = scope.launch(Dispatchers.IO) {
            var parserRecovered = false
            try {
                client.followSession(
                    buildJsonObject {
                        put("address", wireAddress)
                        put("maxMessages", 120)
                        put("assistantStream", true)
                    }
                ).collect { frame ->
                    applyFollowFrame(sessionId, frame)
                    if (!parserRecovered) {
                        parserRecovered = true
                        emitDiagnostic(event = "parser", status = "recovered", sessionId = sessionId)
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (ownerJob.isActive) reportFailure(error, event = "parser")
            }
        }
        refreshSkills(reportFailure = false)
        feedbackActions.refresh(report = false)
        refreshCommands(reportFailure = false)
        refreshSubagents()
        goalActions.refresh()
    }
    private suspend fun renameSession(action: NativeHarnessUiAction.RenameSession) {
        val client = clientOrNull() ?: return
        val result = client.call(
            "session",
            "rename",
            buildJsonObject {
                putJsonObject("request") {
                    put("sessionId", action.sessionId)
                    put("title", action.title.trim())
                }
            },
            HarnessCallPolicy.NoRetry
        )
        when (result) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                sessionTitles[action.sessionId] = result.value.string("title") ?: action.title.trim()
                mutate { current ->
                    current.copy(sessions = current.sessions.map { session ->
                        if (session.id == action.sessionId) session.copy(title = sessionTitles[action.sessionId].orEmpty()) else session
                    })
                }
                refreshSessions(reportFailure = false)
            }
        }
    }
    private suspend fun forkSession(action: NativeHarnessUiAction.ForkSession) {
        val client = clientOrNull() ?: return
        val result = client.call(
            "session",
            "fork",
            buildJsonObject {
                putJsonObject("request") {
                    put("sessionId", action.sessionId)
                    action.atSequence?.let { put("atSeq", it) }
                }
            },
            HarnessCallPolicy.NoRetry
        )
        when (result) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                val id = result.value.string("sessionId") ?: return reportFailure(
                    "SESSION_ID_MISSING",
                    "Harness did not return the forked session identity"
                )
                refreshSessions(reportFailure = false)
                selectSession(id)
            }
        }
    }
    private suspend fun archiveSession(sessionId: String, archived: Boolean) {
        val client = clientOrNull() ?: return
        val method = if (archived) "archiveSession" else "unarchiveSession"
        when (val result = client.call(
            "workspace",
            method,
            buildJsonObject { putJsonObject("request") { put("sessionId", sessionId) } },
            HarnessCallPolicy.NoRetry
        )) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                mutate { current ->
                    current.copy(sessions = current.sessions.map { session ->
                        if (session.id == sessionId) session.copy(
                            isArchived = archived,
                            canArchive = !archived,
                            canUnarchive = archived
                        ) else session
                    })
                }
                refreshSessions(reportFailure = false)
            }
        }
    }
    private suspend fun openAttachment(attachmentId: String) {
        val sessionId = state.value.selectedSessionId ?: return
        val client = clientOrNull() ?: return
        when (val result = client.call(
            "session",
            "attachment",
            buildJsonObject {
                putJsonObject("request") {
                    put("sessionId", sessionId)
                    put("attachmentId", attachmentId)
                }
            },
            HarnessCallPolicy.SafeRead
        )) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                val value = result.value as? JsonObject
                if (value == null) {
                    reportFailure("ATTACHMENT_VALUE_INVALID", "Harness returned an invalid attachment")
                } else {
                    deliverAttachment(sessionId, attachmentId, value)
                    workspace.openAttachment(sessionId, attachmentId)
                }
            }
        }
    }

    private suspend fun submitComposer() {
        val submitted = state.value
        val sessionId = submitted.selectedSessionId
        val text = submitted.composerText.trim()
        if (sessionId.isNullOrBlank() || text.isBlank()) return
        if (sessionId in state.value.deletingSessionIds) return reportFailure("SESSION_DELETE_PENDING", "Session deletion is pending")
        val client = clientOrNull() ?: return
        val attachments = submitted.attachments
        if (isNativeHarnessRegisteredCommand(text, submitted.commands)) {
            val reply = executeNativeHarnessCommand(client, sessionId, text, attachments)
            if (reply.accepted) mutate { current ->
                applyNativeHarnessCommandReply(current, submitted, reply).let { updated ->
                    if (current.selectedSessionId == sessionId && current.composerText == submitted.composerText)
                        updated.copy(composerText = "") else updated
                }
            } else reportFailure(reply.code ?: "COMMAND_REJECTED", reply.text.orEmpty())
            return
        }
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
            attachments.forEach { attachment ->
                add(buildJsonObject {
                    put("type", "file")
                    put("receiptId", attachment.id)
                })
            }
        }
        val result = promptNativeHarnessSession(
            client = client,
            address = sessionAddresses.addressFor(sessionId) ?: ordinaryHarnessSessionAddress(sessionId),
            content = content,
            delivery = if (state.value.composerMode == HarnessPromptMode.STEER) "steer" else "queue",
            clientTimeZone = TimeZone.getDefault().id,
        )
        when (result) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> mutate {
                // Cancellation is enabled only after the selected session reports active work
                // through its follow/status stream. The prompt acknowledgement alone is not a
                // reliable indication that a turn has started.
                it.copy(composerText = "", attachments = emptyList())
            }
        }
    }

    private suspend fun cancelTurn() {
        val sessionId = state.value.selectedSessionId ?: return
        val client = clientOrNull() ?: return
        if (state.value.isStoppingTurn) return
        attention.noteStop(sessionId)
        mutate { it.copy(isStoppingTurn = true) }
        try {
        when (val result = cancelNativeHarnessSession(
            client,
            sessionAddresses.addressFor(sessionId) ?: ordinaryHarnessSessionAddress(sessionId),
        )) {
            is HarnessRpcResult.Failure -> {
                mutate { if (it.selectedSessionId == sessionId) it.copy(isStoppingTurn = false) else it }
                reportFailure(result.error.code, result.error.message)
            }
            is HarnessRpcResult.Success -> Unit // session status/follow acknowledges the actual exit
        }
        } catch (failure: Throwable) {
            mutate { if (it.selectedSessionId == sessionId) it.copy(isStoppingTurn = false) else it }
            throw failure
        }
    }

    private suspend fun executeCommand() {
        val submitted = state.value
        val sessionId = submitted.selectedSessionId ?: return
        val line = submitted.commandLine.trim()
        if (line.isBlank() || !line.startsWith('/')) return
        val client = clientOrNull() ?: return
        val reply = executeNativeHarnessCommand(client, sessionId, line, submitted.attachments)
        if (reply.accepted) mutate { applyNativeHarnessCommandReply(it, submitted, reply) }
        else {
            reportFailure(reply.code ?: "COMMAND_REJECTED", reply.text.orEmpty())
            if (reply.text == null) mutate {
                it.copy(notice = HarnessNoticeUi(titleRes = R.string.harness_notice_error,
                    messageRes = if (reply.code == "COMMAND_UNKNOWN") R.string.harness_command_unknown else R.string.harness_command_rejected))
            }
        }
    }

    private suspend fun loadOlderMessages() {
        val sessionId = state.value.selectedSessionId ?: return
        val client = clientOrNull() ?: return
        val pageState = sessionPageStates[sessionId]
        val wireAddress = sessionAddresses.wireAddress(sessionId)
        if (wireAddress == null) {
            return reportFailure(
                "SUBAGENT_ADDRESS_UNAVAILABLE",
                "The selected subagent address is not available yet",
            )
        }
        mutate { it.copy(isLoadingOlderMessages = true) }
        try {
            val result = client.pageSession(
                buildJsonObject {
                    put("address", wireAddress)
                    put("throughSeq", pageState?.throughSeq ?: harnessLatestSequence(state.value.transcript))
                    val beforeSeq = pageState?.beforeSeq ?: harnessOldestSequence(state.value.transcript)
                    if (beforeSeq > 0L) put("beforeSeq", beforeSeq)
                    put("maxMessages", 120)
                }
            )
            when (result) {
                is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
                is HarnessRpcResult.Success -> {
                    val records = result.value.objectArray("records")
                    val older = records.flatMap { harnessParseRecord(it) }
                    val beforeSeq = harnessMinimumRecordSequence(records)
                    if (beforeSeq != null) {
                        sessionPageStates.compute(sessionId) { _, current ->
                            (current ?: HarnessSessionPageState(
                                throughSeq = pageState?.throughSeq ?: harnessLatestSequence(state.value.transcript),
                                beforeSeq = beforeSeq
                            )).copy(beforeSeq = beforeSeq)
                        }
                    }
                    mutate { current ->
                        current.copy(
                            transcript = boundedHarnessTranscript(older + current.transcript),
                            structuredTranscript = mergeNativeHarnessStructuredHistory(
                                current.structuredTranscript,
                                records,
                            ),
                            canLoadOlderMessages = result.value.boolean("hasMore").orDefault(false),
                            isLoadingOlderMessages = false
                        )
                    }
                }
            }
        } finally {
            mutate { it.copy(isLoadingOlderMessages = false) }
        }
    }

    private suspend fun selectProvider(providerId: String) {
        val provider = state.value.provider.providers.firstOrNull { it.id == providerId } ?: return
        val firstSelectableModel = provider.models.firstOrNull { harnessModelContextKnown(provider, it) }
        mutate { it.copy(provider = it.provider.copy(
            selectedProviderId = providerId,
            selectedModel = firstSelectableModel,
            selectedReasoningEffort = null,
            supportsThinking = firstSelectableModel?.let(provider.reasoningModels::contains) == true
        )) }
        firstSelectableModel?.let { selectModel(it) }
    }

    private suspend fun selectModel(modelName: String, requestedProviderId: String? = state.value.provider.selectedProviderId) {
        val providerId = requestedProviderId ?: return
        val selectedProvider = state.value.provider.providers.firstOrNull { it.id == providerId }
        if (modelName !in selectedProvider?.models.orEmpty()) return
        if (selectedProvider == null || !harnessModelContextKnown(selectedProvider, modelName)) {
            reportFailure(
                "MODEL_CONTEXT_UNKNOWN",
                "Set an explicit context window for this model before selecting it"
            )
            return
        }
        val sessionId = state.value.selectedSessionId
        if (sessionId == null) {
            mutate { it.copy(provider = it.provider.copy(selectedProviderId = providerId, selectedModel = modelName,
                selectedReasoningEffort = null, supportsThinking = modelName in selectedProvider?.reasoningModels.orEmpty())) }
            return
        }
        val client = clientOrNull() ?: return
        val reasoningEffort = if (state.value.provider.thinkingEnabled) {
            state.value.provider.selectedReasoningEffort
                ?.takeIf { effort -> selectedProvider?.reasoningEfforts?.get(modelName)?.any { it.id == effort } == true }
                ?: selectedProvider?.reasoningDefaults?.get(modelName)
        } else {
            null
        }
        when (val result = client.selectSessionModel(buildJsonObject {
            put("sessionId", sessionId)
            put("provider", providerId)
            put("model", modelName)
            reasoningEffort?.let { put("reasoningEffort", it) }
        })) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> mutate { current ->
                current.copy(provider = current.provider.copy(
                    selectedProviderId = providerId,
                    selectedModel = modelName,
                    selectedReasoningEffort = reasoningEffort,
                    supportsThinking = current.provider.providers
                        .firstOrNull { it.id == providerId }
                        ?.reasoningModels
                        ?.contains(modelName) == true
                ))
            }
        }
    }

    private suspend fun selectReasoningEffort(effortId: String?) {
        val sessionId = state.value.selectedSessionId ?: return
        val providerId = state.value.provider.selectedProviderId ?: return
        val model = state.value.provider.selectedModel ?: return
        val provider = state.value.provider.providers.firstOrNull { it.id == providerId }
        val allowed = provider?.reasoningEfforts?.get(model).orEmpty().map { it.id }.toSet()
        if (effortId != null && effortId !in allowed) {
            return reportFailure("MODEL_REASONING_EFFORT_INVALID", "The selected reasoning effort is not available for this model")
        }
        val client = clientOrNull() ?: return
        when (val result = client.selectSessionModel(buildJsonObject {
            put("sessionId", sessionId)
            put("provider", providerId)
            put("model", model)
            effortId?.let { put("reasoningEffort", it) }
        })) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> mutate { current ->
                current.copy(provider = current.provider.copy(
                    selectedReasoningEffort = effortId,
                    thinkingEnabled = true
                ))
            }
        }
    }

    private suspend fun answerQuestion(action: NativeHarnessUiAction.AnswerQuestion) {
        val result = if (action.custom || action.selected.isNotEmpty()) {
            attention.answer(
                sessionId = state.value.selectedSessionId,
                id = action.questionId,
                answer = action.answer,
                custom = action.custom,
                selected = action.selected
            )
        } else {
            interactions?.answerQuestion?.invoke(action.questionId, action.answer)
                ?: attention.answer(state.value.selectedSessionId, action.questionId, action.answer, false, emptyList())
        }
        when (result) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> mutate { current ->
                current.copy(questions = current.questions.filterNot { it.id == action.questionId })
            }
        }
    }

    private suspend fun decideApproval(approvalId: String, approved: Boolean) {
        val result = interactions?.decideApproval?.invoke(approvalId, approved)
            ?: attention.approve(state.value.selectedSessionId, approvalId, approved)
        when (result) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> mutate { current ->
                current.copy(approvals = current.approvals.filterNot { it.id == approvalId })
            }
        }
    }

    private suspend fun decidePlan(planId: String, approved: Boolean) {
        val result = interactions?.decidePlan?.invoke(planId, approved)
            ?: attention.reviewPlan(state.value.selectedSessionId, planId, approved)
        when (result) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> mutate { current ->
                if (approved) current.copy(plans = current.plans.map { plan ->
                    if (plan.id == planId) plan.copy(isApproved = true) else plan
                }) else current.copy(plans = current.plans.filterNot { it.id == planId })
            }
        }
    }

    private suspend fun invokeJobAction(
        operation: suspend (String) -> HarnessRpcResult,
        jobId: String
    ) {
        when (val result = operation(jobId)) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> startControlStream()
        }
    }

    private suspend fun createProvider() {
        val binding = providerBindings.values.firstOrNull { it.value == null }
            ?: return reportFailure("PROVIDER_ALREADY_CONFIGURED", "Every declared Harness provider is already configured")
        val client = clientOrNull() ?: return
        val operation = NativeHarnessCapabilities.setOperation(
            binding.settingsPath,
            buildJsonObject {}
        )
        when (val result = NativeHarnessCapabilities.mutateSettings(
            client,
            binding.settingsNamespace,
            buildJsonArray { add(operation) },
            binding.revision
        )) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                refreshSettings(reportFailure = false)
                refreshModelCatalog(reportFailure = false)
            }
        }
    }

    private suspend fun saveProvider(action: NativeHarnessUiAction) {
        if (action is NativeHarnessUiAction.EditProvider) {
            refreshSettings(reportFailure = false)
            return
        }
        val save = action as? NativeHarnessUiAction.SaveProvider ?: return
        val binding = providerBindings[save.providerId] ?: return reportFailure(
            "PROVIDER_NOT_DECLARED",
            "The selected provider is not declared by Harness"
        )
        val config = state.value.provider.configs.firstOrNull { it.id == save.providerId }
            ?: return reportFailure("PROVIDER_FORM_UNAVAILABLE", "The provider form is not available")
        val plan = buildNativeHarnessProviderSavePlan(binding, config)
        val operations = when (plan) {
            is NativeHarnessProviderSavePlan.Invalid -> {
                reportFailure(plan.code, plan.detail)
                return
            }
            is NativeHarnessProviderSavePlan.Ready -> plan.operations
        }
        if (operations.isEmpty()) return refreshSettings(reportFailure = false)
        val client = clientOrNull() ?: return
        when (val result = NativeHarnessCapabilities.mutateSettings(
            client,
            binding.settingsNamespace,
            buildJsonArray { operations.forEach(::add) },
            binding.revision
        )) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                refreshSettings(reportFailure = false)
                refreshModelCatalog(reportFailure = false)
            }
        }
    }

    private suspend fun deleteProvider(providerId: String) {
        val binding = providerBindings[providerId] ?: return reportFailure(
            "PROVIDER_NOT_DECLARED",
            "The selected provider is not declared by Harness"
        )
        if (!binding.canDelete) return reportFailure(
            "PROVIDER_NOT_REMOVABLE",
            "This provider is not a removable user-owned profile"
        )
        val client = clientOrNull() ?: return
        val result = if (binding.settingsNamespace == NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE) {
            NativeHarnessProviderGateway.delete(client, binding)
        } else {
            NativeHarnessCapabilities.mutateSettings(
                client,
                binding.settingsNamespace,
                buildJsonArray { add(NativeHarnessCapabilities.unsetOperation(binding.settingsPath)) },
                binding.revision
            )
        }
        when (result) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                refreshSettings(reportFailure = false)
                refreshModelCatalog(reportFailure = false)
            }
        }
    }

    private suspend fun updateProviderField(action: NativeHarnessUiAction.UpdateProviderField) {
        val binding = providerBindings[action.providerId] ?: return reportFailure(
            "PROVIDER_NOT_DECLARED",
            "The selected provider is not declared by Harness"
        )
        val field = state.value.provider.configs.firstOrNull { it.id == action.providerId }
            ?.fields
            ?.firstOrNull { it.key == action.key }
        val relative = field?.path?.takeIf { it.isNotEmpty() } ?: harnessProviderFieldPath(
            harnessSchemaKeyPath(listOf(binding.settingsNamespace) + binding.settingsPath), action.key
        ) ?: return reportFailure(
            "PROVIDER_FIELD_UNAVAILABLE",
            "The selected provider field is not available"
        )
        val value = harnessValueForField(field?.copy(value = action.value)) ?: run {
            reportFailure(
                if (field?.type == HarnessSchemaFieldType.JSON) "SETTINGS_JSON_INVALID" else "SETTINGS_FIELD_INVALID",
                "Enter a valid value for this Harness setting"
            )
            return
        }
        val client = clientOrNull() ?: return
        when (val result = NativeHarnessCapabilities.mutateSettings(
            client,
            binding.settingsNamespace,
            buildJsonArray {
                add(NativeHarnessCapabilities.setOperation(binding.settingsPath + relative, value))
            },
            binding.revision
        )) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> refreshSettings(reportFailure = false)
        }
    }

    private suspend fun setProviderCredential(action: NativeHarnessUiAction.SetProviderCredential) =
        providerCredentialActions.set(action.providerId, action.value)

    private suspend fun unsetProviderCredential(providerId: String) =
        providerCredentialActions.unset(providerId)

    private suspend fun setThinkingEnabled(enabled: Boolean) {
        val sessionId = state.value.selectedSessionId ?: return
        val providerId = state.value.provider.selectedProviderId ?: return
        val model = state.value.provider.selectedModel ?: return
        val provider = state.value.provider.providers.firstOrNull { it.id == providerId }
        val client = clientOrNull() ?: return
        val reasoningEffort = if (enabled) {
            state.value.provider.selectedReasoningEffort
                ?: provider?.reasoningDefaults?.get(model)
        } else null
        when (val result = client.selectSessionModel(buildJsonObject {
            put("sessionId", sessionId)
            put("provider", providerId)
            put("model", model)
            reasoningEffort?.let { put("reasoningEffort", it) }
        })) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> mutate {
                it.copy(provider = it.provider.copy(
                    thinkingEnabled = enabled,
                    selectedReasoningEffort = reasoningEffort
                ))
            }
        }
    }

    private suspend fun invokeCapabilityAction(action: NativeHarnessUiAction.InvokeCapability) {
        when (val result = invokeCapability(
            action.capabilityId,
            buildJsonObject { action.arguments.forEach { (key, value) -> put(key, value) } }
        )) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> mutate { it.copy(notice = null) }
        }
    }

    private suspend fun updateProviderSetting(action: NativeHarnessUiAction.UpdateProviderSetting) {
        val section = state.value.schemaSections.firstOrNull { schema ->
            schema.fields.any { field -> field.key.endsWith(".${action.key}") || field.key == action.key }
        }
        if (section == null) {
            reportFailure("SETTINGS_FIELD_UNAVAILABLE", "This Harness setting is not declared by the current schema")
        } else {
            settingsActions.update(
                key = section.fields.first { field ->
                    field.key.endsWith(".${action.key}") || field.key == action.key
                }.key,
                text = action.value
            )
        }
    }

    private suspend fun installPlugin(action: NativeHarnessUiAction.InstallPlugin) =
        pluginActions.install(action)

    private suspend fun inspectPlugin(packageSpec: String) =
        pluginActions.inspect(packageSpec)

    private suspend fun cancelPluginInstall(requestId: String) =
        pluginActions.cancel(requestId)

    private suspend fun pluginCall(method: String, args: JsonObject) =
        pluginActions.call(method, args)

    private suspend fun refreshPlugins(reportFailure: Boolean) =
        pluginActions.refresh(reportFailure)

    private suspend fun refreshSettings(reportFailure: Boolean): Unit = settingsReader.refreshSettings(reportFailure)
    private suspend fun refreshModelCatalog(reportFailure: Boolean): Unit = settingsReader.refreshModelCatalog(reportFailure)

    private suspend fun refreshSkills(reportFailure: Boolean) = referenceCatalogs.skills(reportFailure)
    private suspend fun refreshCommands(reportFailure: Boolean) = referenceCatalogs.commands(reportFailure)

    private suspend fun refreshSubagents() {
        val parent = state.value.selectedSessionId ?: return
        val client = clientOrNull() ?: return
        when (val result = client.call(
            "subagents",
            "list",
            buildJsonObject { put("parentSessionId", parent) },
            HarnessCallPolicy.SafeRead
        )) {
            is HarnessRpcResult.Failure -> Unit
            is HarnessRpcResult.Success -> {
                val entries = result.value.objectArray("entries")
                val children = entries.mapNotNull { entry ->
                    if (entry.string("kind") != "child") return@mapNotNull null
                    val id = entry.string("id") ?: return@mapNotNull null
                    val address = sessionAddresses.observeSubagent(parent, id, entry.string("mode"))
                    queueStore.setSessionCapabilities(
                        id,
                        queueMutable = address?.queueMutable,
                        running = entry.string("activity") == "running",
                    )
                    HarnessSubagentUi(
                        id = id,
                        title = entry.string("label") ?: id,
                        statusLabel = entry.string("activity") ?: "inactive",
                        detail = entry.string("mode"),
                        canPrompt = entry.string("mode") == "continuable",
                        canInterrupt = entry.string("activity") == "running"
                    )
                }
                mutate { it.copy(extensions = it.extensions.copy(
                    subagents = it.extensions.subagents.copy(
                        children = children,
                        activeCount = children.count { child -> child.statusLabel == "running" },
                        agentNames = children.map(HarnessSubagentUi::title)
                    )
                )) }
            }
        }
    }

    private suspend fun promptSubagent(childSessionId: String) {
        val client = clientOrNull() ?: return
        val address = sessionAddresses.addressFor(childSessionId)
            ?: return reportFailure(
                "SUBAGENT_ADDRESS_UNAVAILABLE",
                "The selected subagent address is not available yet",
            )
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", state.value.composerText)
            })
        }
        when (val result = promptNativeHarnessSession(
            client = client,
            address = address,
            content = content,
            delivery = "queue",
            clientTimeZone = TimeZone.getDefault().id,
        )) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> mutate { it.copy(composerText = "") }
        }
    }

    private suspend fun interruptSubagent(childSessionId: String) {
        val client = clientOrNull() ?: return
        val address = sessionAddresses.addressFor(childSessionId)
            ?: return reportFailure(
                "SUBAGENT_ADDRESS_UNAVAILABLE",
                "The selected subagent address is not available yet",
            )
        when (val result = cancelNativeHarnessSession(client, address)) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> refreshSubagents()
        }
    }

    private suspend fun applyControlFrame(frame: JsonElement) {
        val type = frame.string("type") ?: return
        when (type) {
            "baseline" -> {
                val value = frame.objectValue("value") ?: return
                val jobsBySession = value.objectValue("jobs")
                if (jobsBySession != null) {
                    state.value.selectedSessionId?.let { selected ->
                        val rows = parseHarnessJobsForSession(jobsBySession, selected, jobs)
                        mutate { it.copy(extensions = it.extensions.copy(jobs = rows)) }
                    }
                }
                value.objectValue("projections")?.forEach { (sessionId, projection) ->
                    val projectionObject = projection.jsonObjectOrNull() ?: return@forEach
                    val projectionValues = projectionObject.objectValue("values")
                    projectionObject.long("asOfSeq")?.let { sequence ->
                        projectionValues?.let { queueStore.applyBaseline(sessionId, sequence, it) }
                    }
                    val projectionValue = projectionValues?.get("goal") ?: JsonNull
                    publishGoal(sessionId, goalStore.applyProjection(sessionId, projectionValue))
                }
                val selected = state.value.selectedSessionId
                val selectedProjectionValues = selected
                    ?.let { value.objectValue("projections")?.get(it) }
                    ?.jsonObjectOrNull()
                    ?.objectValue("values")
                val selectedUsage = selectedProjectionValues?.let(::harnessParseUsageProjections)
                val selectedPermission = selected
                    ?.let { selectedProjectionValues?.get("permissions") }
                permissionActions.applyProjection(selectedPermission)
                val selectedQueue = selected?.let(queueStore::items).orEmpty()
                mutate { it.withHarnessUsage(selectedUsage).copy(queue = selectedQueue) }
            }
            "jobs" -> {
                val sessionId = frame.string("sessionId") ?: return
                if (sessionId == state.value.selectedSessionId) {
                    val jobs = frame.objectArray("jobs").map { parseHarnessJob(it, jobs) }
                    mutate { it.copy(extensions = it.extensions.copy(jobs = jobs)) }
                }
            }
            "projection" -> {
                val sessionId = frame.string("sessionId") ?: return
                val key = frame.string("key") ?: return
                val value = frame.jsonObjectOrNull()?.get("value") ?: JsonNull
                if (key == "goal") {
                    publishGoal(sessionId, goalStore.applyProjection(sessionId, value))
                }
                if (key == "permissions" && sessionId == state.value.selectedSessionId) {
                    permissionActions.applyProjection(value)
                }
                val sequence = frame.long("seq")
                val queueAccepted = sequence != null &&
                    queueStore.applyProjection(sessionId, sequence, key, value)
                if (sessionId == state.value.selectedSessionId) {
                    mutate { current ->
                        val next = current.withHarnessProjection(key, value)
                        if (queueAccepted) next.copy(queue = queueStore.items(sessionId)) else next
                    }
                }
            }
        }
    }

    private suspend fun applyRemoteEmit(event: String, args: JsonArray) {
        when (event) {
            "api-session/status" -> {
                if ((args.firstOrNull() as? JsonPrimitive)?.content == state.value.selectedSessionId &&
                    (args.getOrNull(1) as? JsonPrimitive)?.content == "false") {
                    mutate { it.copy(canCancelTurn = false, isStoppingTurn = false) }
                }
            }
            "goal/activation-changed" -> {
                val payload = args.firstOrNull()?.jsonObjectOrNull() ?: return
                val sessionId = payload.string("sessionId") ?: return
                val goal = payload.objectValue("goal")
                val updated = goalStore.applyActivation(
                    sessionId = sessionId,
                    goalId = goal?.string("id"),
                    revision = goal?.int("revision"),
                    activation = goal?.string("activation")
                )
                publishGoal(sessionId, updated)
            }
            "plugin-manager/install-state" -> {
                val progress = args.firstOrNull()?.jsonObjectOrNull() ?: return
                val requestId = progress.string("requestId") ?: return
                if (requestId == state.value.extensions.pluginInstall.requestId) {
                    mutate { current ->
                        current.copy(extensions = current.extensions.copy(
                            pluginInstall = current.extensions.pluginInstall.copy(
                                phase = progress.string("phase")
                            )
                        ))
                    }
                }
            }
            "plugin-manager/install-log" -> {
                val chunk = args.firstOrNull()?.jsonObjectOrNull() ?: return
                val requestId = chunk.string("requestId") ?: return
                if (requestId == state.value.extensions.pluginInstall.requestId) {
                    val text = chunk.string("text")?.takeLast(2_000).orEmpty()
                    if (text.isBlank()) return
                    mutate { current ->
                        val install = current.extensions.pluginInstall
                        current.copy(extensions = current.extensions.copy(
                            pluginInstall = install.copy(logs = (install.logs + text).takeLast(40))
                        ))
                    }
                }
            }
            // The event can arrive in bursts while a plugin is being installed.
            // Mark the extensions snapshot stale and let the destination reload
            // on its next entry instead of refreshing the whole manager for
            // every parser notification.
            "plugin-manager/changed" -> management.invalidate(HarnessManagementArea.EXTENSIONS)
            "cordis/request-run",
            "cordis/request-resolved",
            "cordis/retracted" -> scope.launch { parityActions.refreshCordisAfterSessionChange() }
        }
    }

    private suspend fun publishGoal(sessionId: String, goal: HarnessGoalUi?) {
        if (state.value.selectedSessionId == sessionId) {
            mutate { it.copy(goal = goal) }
        }
    }

    private suspend fun applyFollowFrame(sessionId: String, frame: JsonElement) {
        if (state.value.selectedSessionId != sessionId) return
        when (frame.string("type")) {
            "snapshot" -> {
                eventSequencer.advance(sessionId, frame.long("cursor"))
                structuredTranscriptActions.invalidate()
                val rawRecords = frame.objectArray("records")
                val records = rawRecords.flatMap { harnessParseRecord(it) }
                val header = frame.objectValue("header")
                val projectionEnvelope = frame.objectValue("projections")
                val projectionValues = projectionEnvelope?.objectValue("values")
                val queueAccepted = queueStore.applyFollowSnapshot(
                    sessionId,
                    frame.long("cursor") ?: -1L,
                    projectionEnvelope,
                )
                val permission = projectionValues?.get("permissions")
                val selection = projectionValues?.objectValue("modelSelection")?.let { it.objectValue("next") ?: it.objectValue("lastUsed") }
                val usage = projectionValues?.let(::harnessParseUsageProjections)
                val snapshotCwd = header?.string("cwd")
                snapshotCwd?.let { sessionCwds[sessionId] = it }
                val hasMore = frame.boolean("hasMore").orDefault(false)
                sessionPageStates[sessionId] = HarnessSessionPageState(
                    throughSeq = frame.long("cursor") ?: (harnessMinimumRecordSequence(rawRecords) ?: -1L),
                    beforeSeq = harnessMinimumRecordSequence(rawRecords)
                )
                val active = frame.objectValue("assistantStream")?.objectValue("activeAttempt")
                queueStore.setSessionCapabilities(sessionId, running = active != null)
                assistantStreamTrackers.computeIfAbsent(sessionId) { HarnessAssistantStreamTracker() }
                    .reset(frame.objectValue("assistantStream"))
                mutate { current ->
                    current.copy(
                        transcript = boundedHarnessTranscript(records),
                        structuredTranscript = harnessParseStructuredRecords(rawRecords),
                        structuredDetail = current.structuredDetail.copy(generation = current.structuredDetail.generation + 1, itemId = null, page = null, isLoading = false),
                        tokenUsage = usage,
                        contextPressure = usage?.contextPressure,
                        contextBreakdown = usage?.contextBreakdown,
                        sessionStats = usage?.sessionStats,
                        canLoadOlderMessages = hasMore,
                        isLoadingOlderMessages = false,
                        canCancelTurn = active != null || current.sessions.firstOrNull { it.id == sessionId }?.isRunning == true,
                        isStoppingTurn = current.isStoppingTurn && (active != null || current.sessions.firstOrNull { it.id == sessionId }?.isRunning == true),
                        provider = current.provider.withSessionSelection(selection),
                        queue = if (queueAccepted) queueStore.items(sessionId) else current.queue,
                        sessions = current.sessions.map { session ->
                            if (session.id == sessionId && snapshotCwd != null) {
                                session.copy(projectFolder = harnessProjectFromCwd(snapshotCwd))
                            } else session
                        }
                    )
                }
                permissionActions.applyProjection(permission)
                active?.let { attempt ->
                    val stream = harnessCompactedStreamText(attempt.objectArray("stream"))
                    assistantStreamUi.showBaseline(sessionId, stream)
                }
            }
            "event" -> {
                val event = frame.objectValue("event") ?: return
                // A WebUI reconnect can replay the same session event after the native follow has
                // already applied it.  Sequence numbers are authoritative within a session;
                // payloads without a sequence remain compatible with older Harness builds.
                if (!eventSequencer.accept(sessionId, event.long("seq"))) return
                structuredTranscriptActions.invalidate()
                val parsed = harnessParseEvent(event)
                val runningChange = when (event.string("type")) {
                    "turn/start" -> true
                    "turn/end" -> false
                    else -> null
                }
                runningChange?.let { queueStore.setSessionCapabilities(sessionId, running = it) }
                mutate { current ->
                    val settlesAssistant = event.string("type") == "assistant/message" ||
                        event.string("type") == "assistant/attempt"
                    val previous = if (settlesAssistant) {
                        current.transcript.filterNot { it.id == "stream-$sessionId" }
                    } else current.transcript
                    current.copy(
                        transcript = parsed?.let { boundedHarnessTranscript(previous + it) } ?: current.transcript,
                        structuredTranscript = mergeNativeHarnessStructuredTranscript(
                            current.structuredTranscript,
                            event,
                        ),
                        structuredDetail = current.structuredDetail.copy(generation = current.structuredDetail.generation + 1, itemId = null, page = null, isLoading = false),
                        tokenUsage = parsed?.usage ?: current.tokenUsage,
                        queue = if (runningChange != null) queueStore.items(sessionId) else current.queue,
                        canCancelTurn = runningChange ?: current.canCancelTurn,
                        isStoppingTurn = current.isStoppingTurn && runningChange != false,
                        provider = if (event.string("type") == "model/selection") current.provider.withSessionSelection(event.objectValue("data")) else current.provider,
                    )
                }
                if (event.string("type") == "session/title") {
                    val title = event.objectValue("data")?.string("title")
                    if (!title.isNullOrBlank()) {
                        sessionTitles[sessionId] = title
                        mutate { current -> current.copy(sessions = current.sessions.map { session ->
                            if (session.id == sessionId) session.copy(title = title) else session
                        }) }
                    }
                }
            }
            "assistant-stream" -> assistantStreamUi.apply(
                sessionId,
                frame.objectValue("frame") ?: frame.jsonObjectOrNull() ?: return
            )
        }
    }

    private suspend fun resolveWorkspace(
        sessionId: String,
        title: String,
        cwd: String?,
        archived: Boolean
    ): HarnessWorkspaceUiState? = try {
        workspace.resolveSession(sessionId, title, cwd, archived)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        reportFailure(error, event = "render")
        null
    }

    private suspend fun mutate(transform: (NativeHarnessUiState) -> NativeHarnessUiState) {
        stateLock.withLock { mutableState.update(transform) }
    }

    private suspend fun emitDiagnostic(
        event: String,
        status: String,
        errorClass: String? = null,
        sessionId: String? = state.value.selectedSessionId
    ) {
        try {
            onDiagnostic(sessionId, event, status, errorClass)
        } catch (_: Throwable) {
            // Diagnostics must never turn a recoverable Harness failure into a UI failure.
        }
    }

    private suspend fun reportFailure(error: Throwable, event: String = "action") {
        emitDiagnostic(
            event = event,
            status = "failure",
            errorClass = error::class.simpleName
        )
        reportFailure(
            error::class.simpleName ?: "HARNESS_ERROR",
            error.message?.take(500) ?: "Harness operation failed",
            event = event
        )
    }

    private suspend fun reportFailure(code: String, message: String, event: String = "action") {
        if (event == "action") currentActionFailed = true
        emitDiagnostic(event = event, status = "failure", errorClass = code)
        val area = kotlinx.coroutines.currentCoroutineContext()[HarnessManagementContext]?.area
        if (area != null) {
            mutate { current -> current.copy(managementLoads = current.managementLoads +
                (area to (current.managementLoads[area] ?: HarnessManagementLoadUi()).copy(errorCode = code))) }
            return
        }
        // Workspace grouping is auxiliary; its stream failure must not replace chat recovery.
        if (code == "WORKSPACE_STREAM_FAILED") return
        val messageResource = localizedHarnessNoticeMessage(code)
        mutate {
            it.copy(
                notice = HarnessNoticeUi(
                    titleRes = R.string.harness_notice_error,
                    message = message.takeUnless { messageResource != null },
                    messageRes = messageResource
                )
            )
        }
    }
}
