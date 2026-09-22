package com.example.llamadroid.ui.agent.harness

import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * The native Harness surface talks to the runtime through this small contract.
 * The UI does not know about the Harness transport, Room, or the legacy agent
 * loop. Runtime owners can map their state into these presentation models and
 * handle [NativeHarnessUiAction] from one serialized controller.
 */
interface NativeHarnessUiController {
    val state: StateFlow<NativeHarnessUiState>

    fun dispatch(action: NativeHarnessUiAction)
}

/** Metadata-only diagnostic callback; payloads must never contain prompts or tool data. */
typealias NativeHarnessDiagnosticCallback = suspend (
    sessionId: String?,
    event: String,
    status: String,
    errorClass: String?
) -> Unit

enum class HarnessRuntimeStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    INTERRUPTED,
    ERROR
}

/** Metadata-only runtime journal row. Content, prompts, and tool payloads are excluded. */
data class HarnessRuntimeDiagnosticUi(
    val id: String,
    val timestampMs: Long,
    val eventLabel: String,
    val stateLabel: String? = null,
    val errorCode: String? = null,
    val durationMs: Long? = null,
    val exitCode: Int? = null,
    val connectionMetadata: String? = null,
)

data class HarnessRuntimeUiState(
    val status: HarnessRuntimeStatus = HarnessRuntimeStatus.STOPPED,
    val detail: String? = null,
    val instanceLabel: String = "DeepSeek Harness",
    val endpointLabel: String? = null,
    val canStart: Boolean = true,
    val canStop: Boolean = false,
    val canForceStop: Boolean = false,
    val phaseLabel: String? = null,
    val errorCode: String? = null,
    /** Chronological metadata rows; the Runtime tab presents the newest rows first. */
    val connectionLabel: String? = null,
    val diagnostics: List<HarnessRuntimeDiagnosticUi> = emptyList(),
    val diagnosticsLoading: Boolean = false,
    val diagnosticsLastUpdatedMs: Long? = null
)

data class HarnessSessionUiState(
    val id: String,
    val title: String,
    val projectFolder: String,
    val backendLabel: String,
    val lastActivityLabel: String? = null,
    val unreadCount: Int = 0,
    val isSelected: Boolean = false,
    val isRunning: Boolean = false,
    val isArchived: Boolean = false,
    val canRename: Boolean = true,
    val canFork: Boolean = true,
    val canArchive: Boolean = false,
    val canUnarchive: Boolean = false
)

data class HarnessSessionSearchResultUi(
    val sessionId: String,
    val title: String,
    val snippet: String
)

enum class HarnessPromptMode {
    QUEUE,
    STEER
}

data class HarnessQueueItemUi(
    val id: String,
    /** Bounded user-facing preview; durable content stays in the Host projection. */
    val text: String,
    /** Full text is retained only for a text-only row within the edit budget. */
    val editText: String? = text,
    val textTruncated: Boolean = false,
    val attachments: List<HarnessQueueAttachmentUi> = emptyList(),
    val canEdit: Boolean = true,
    val canRemove: Boolean = true,
    val canSteer: Boolean = true
)

data class HarnessCommandUi(
    val name: String,
    val description: String? = null
)

data class HarnessAttachmentUi(
    val id: String,
    val label: String,
    val mediaType: String,
    val isUploading: Boolean = false,
    val canRemove: Boolean = true
)

/** Provider-reported token accounting retained for session and turn summaries. */
data class HarnessContextPressureUi(
    val pressureTokens: Long? = null,
    val projectedTokens: Long? = null,
    val contextWindow: Long? = null
) {
    val occupancyPercent: Int?
        get() {
            val used = projectedTokens ?: pressureTokens ?: return null
            val capacity = contextWindow ?: return null
            if (capacity <= 0L) return null
            return ((used.coerceAtLeast(0L).coerceAtMost(capacity).toDouble() * 100.0) / capacity)
                .roundToInt()
                .coerceIn(0, 100)
        }
}

data class HarnessContextBreakdownUi(
    val systemTokens: Long,
    val toolsTokens: Long,
    val messageTokens: Long
)

data class HarnessSessionStatsUi(
    val turns: Long,
    val steps: Long,
    val llmMs: Long,
    val toolMs: Long,
    val ttftMs: Long,
    val ttftSteps: Long,
    val decodeMs: Long,
    val decodeTokens: Long
)

data class HarnessTokenUsageUi(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    /** True only when the provider supplied both cache buckets for this projection. */
    val cacheMetricsKnown: Boolean = false,
    val reasoningTokens: Long? = null,
    val totalTokens: Long? = null,
    val contextPressure: HarnessContextPressureUi? = null,
    val contextBreakdown: HarnessContextBreakdownUi? = null,
    val sessionStats: HarnessSessionStatsUi? = null
) {
    val billedInputTokens: Long
        get() = inputTokens + cacheReadTokens + cacheWriteTokens
    val total: Long
        get() = totalTokens ?: billedInputTokens + outputTokens
    val cacheHitPercent: Int?
        get() {
            if (!cacheMetricsKnown) return null
            val promptTokens = inputTokens + cacheReadTokens
            if (promptTokens <= 0L) return null
            return ((cacheReadTokens.toDouble() * 100.0) / promptTokens)
                .roundToInt()
                .coerceIn(0, 100)
        }
    val tokensPerSecond: Double?
        get() = sessionStats?.takeIf { it.decodeMs > 0L && it.decodeTokens > 0L }
            ?.let { it.decodeTokens.toDouble() * 1_000.0 / it.decodeMs.toDouble() }
}

enum class HarnessTranscriptRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
    THINKING
}

data class HarnessTranscriptItem(
    val id: String,
    val role: HarnessTranscriptRole,
    val text: String,
    /** Stable upstream assistant message id; absent for streams and non-message events. */
    val messageId: String? = null,
    val usage: HarnessTokenUsageUi? = null,
    val label: String? = null,
    val isStreaming: Boolean = false,
    val isExpandable: Boolean = false,
    val isExpanded: Boolean = false
)

/** One bounded on-demand detail page for the structured transcript card. */
data class HarnessStructuredTranscriptDetailUiState(
    val generation: Long = 0L,
    val itemId: String? = null,
    val page: NativeHarnessStructuredTranscriptDetailPage? = null,
    val isLoading: Boolean = false
)

enum class HarnessFeedbackRating(val wireValue: String) {
    POSITIVE("positive"),
    NEGATIVE("negative")
}

data class HarnessMessageFeedbackUi(
    val rating: HarnessFeedbackRating,
    val version: String,
    val note: String? = null,
    val category: String? = null
)

data class HarnessQuestionUi(
    val id: String,
    val title: String,
    val prompt: String,
    val options: List<String> = emptyList(),
    val allowsFreeText: Boolean = true,
    val multiSelect: Boolean = false
)

data class HarnessApprovalUi(
    val id: String,
    val title: String,
    val summary: String,
    val riskLabel: String? = null,
    val detail: String? = null
)

data class HarnessPlanUi(
    val id: String,
    val title: String,
    val summary: String,
    val steps: List<String> = emptyList(),
    val canApprove: Boolean = true,
    val canReject: Boolean = true,
    val isApproved: Boolean = false
)

/** Durable goal projection plus the process-local activation edge. */
data class HarnessGoalUi(
    val id: String,
    val revision: Int,
    val objective: String,
    val phase: String,
    val roundsStarted: Int,
    val maxGoalRounds: Int,
    val blockedReason: String? = null,
    val activation: String = "disarmed"
)

data class HarnessProviderOption(
    val id: String,
    val name: String,
    val models: List<String> = emptyList(),
    val reasoningModels: Set<String> = emptySet(),
    val reasoningDefaults: Map<String, String> = emptyMap(),
    val reasoningEfforts: Map<String, List<HarnessReasoningEffortUi>> = emptyMap(),
    val detail: String? = null,
    val configured: Boolean = true,
    val canEdit: Boolean = true,
    val canDelete: Boolean = false,
    /** Wire IDs remain stable for requests; this map supplies canonical display names. */
    val modelNames: Map<String, String> = emptyMap(),
    /** Effective context window per wire ID. A missing value means unknown. */
    val modelContextWindows: Map<String, Long?> = emptyMap(),
    /** Effective output limit per wire ID. A missing value means unknown. */
    val modelMaxOutputTokens: Map<String, Long?> = emptyMap(),
    /** `explicit`, `saved`, `detected`, or `unknown`; kept separate from labels. */
    val modelCapabilitySources: Map<String, String> = emptyMap()
)

data class HarnessReasoningEffortUi(
    val id: String,
    val name: String,
    val description: String? = null
)

data class HarnessModelCatalogFailureUi(
    val providerId: String,
    val providerName: String,
    val message: String
)

/** One model advertised by an endpoint discovery request, before adoption. */
data class HarnessDiscoveredModelUi(
    val id: String,
    val name: String? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val inputModalities: List<String> = emptyList(),
)

/** Candidate state is kept in the form until the user explicitly adopts it. */
data class HarnessProviderDiscoveryUi(
    val candidates: List<HarnessDiscoveredModelUi> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorCode: String? = null,
    val hasRun: Boolean = false,
)

data class HarnessProviderConfigUi(
    val id: String,
    val name: String,
    val fields: List<HarnessSchemaField> = emptyList(),
    val auth: HarnessProviderAuthUi = HarnessProviderAuthUi(),
    /** Redacted credential reference named by this provider profile. */
    val credentialReference: String? = null,
    val credentialConfigured: Boolean = false,
    /** True when this custom endpoint is valid without an API key. */
    val credentialOptional: Boolean = false,
    val credentialSource: String? = null,
    val credentialWritable: Boolean = false,
    val canSave: Boolean = true,
    val canDelete: Boolean = false,
    val discovery: HarnessProviderDiscoveryUi = HarnessProviderDiscoveryUi(),
)

data class HarnessProviderUiState(
    val selectedProviderId: String? = null,
    val selectedModel: String? = null,
    val selectedReasoningEffort: String? = null,
    val providers: List<HarnessProviderOption> = emptyList(),
    val catalogFailures: List<HarnessModelCatalogFailureUi> = emptyList(),
    val supportsThinking: Boolean = false,
    val thinkingEnabled: Boolean = false,
    val endpointLabel: String? = null,
    val contextSize: String = "",
    val configs: List<HarnessProviderConfigUi> = emptyList(),
    val canCreateProvider: Boolean = false,
    /** Protocols exposed by the pinned pi-ai custom route factory. */
    val customProviderProtocols: List<String> = emptyList(),
    /** Ephemeral discovery candidates for the unsaved custom-provider draft. */
    val customProviderDiscovery: HarnessProviderDiscoveryUi = HarnessProviderDiscoveryUi(),
    /** True while the dedicated Harness model editor is presented. */
    val modelEditorOpen: Boolean = false,
    val isCatalogLoading: Boolean = false
)

enum class HarnessSchemaFieldType {
    TOGGLE,
    TEXT,
    INTEGER,
    DECIMAL,
    CHOICE,
    JSON
}

data class HarnessSchemaField(
    val key: String,
    val label: String,
    val description: String? = null,
    val type: HarnessSchemaFieldType,
    val value: String = "",
    val options: List<String> = emptyList(),
    val enabled: Boolean = true,
    /** True when this field is present in the provider's raw user layer. */
    val isOverridden: Boolean = false,
    /**
     * The lossless settings path represented by this field. The display key
     * remains dotted for compatibility, but a schema key may itself contain a
     * dot and must not be reconstructed by splitting [key].
     */
    val path: List<String> = emptyList()
)

data class HarnessSchemaSection(
    val title: String,
    val description: String? = null,
    val fields: List<HarnessSchemaField> = emptyList()
)

/** Bounded Android editor for the redacted user layer returned by settings/describe. */
data class HarnessSettingsDocumentUi(
    val text: String,
    val canEdit: Boolean,
    val isSaving: Boolean = false
)

/** One executable capability exposed by the release-specific Harness manifest. */
data class HarnessCapabilityActionUi(
    val id: String,
    val title: String,
    val summary: String,
    val fields: List<HarnessSchemaField> = emptyList(),
    val enabled: Boolean = true,
    val requiresApproval: Boolean = false
)

data class HarnessCapabilitySectionUi(
    val title: String,
    val description: String? = null,
    val actions: List<HarnessCapabilityActionUi> = emptyList()
)

data class HarnessPluginUi(
    val id: String,
    val name: String,
    val summary: String,
    val versionLabel: String? = null,
    val installed: Boolean = false,
    /** True when the DSH installation ships this bundle for opt-in activation. */
    val optional: Boolean = false,
    val enabled: Boolean = false,
    val canInstall: Boolean = true,
    val canUninstall: Boolean = true,
    /** Whether the bundle layer may be switched in this profile. */
    val canToggle: Boolean = true,
    val readOnlyReason: String? = null,
    /** Stable BundleInfo.error.code, when the patch cannot be read/applied. */
    val errorCode: String? = null,
    /** Bounded host diagnostic associated with [errorCode]. */
    val errorDiagnostic: String? = null,
    val pendingBuildApproval: Boolean = false,
    val pendingBuilds: List<String> = emptyList(),
    val bundleName: String? = null,
    /** Built-in loader rows configured by this bundle but not declared by it. */
    val overrides: List<String> = emptyList(),
    val rows: List<HarnessPluginRowUi> = emptyList()
)

data class HarnessPluginInventoryUi(
    val entryId: String,
    val moduleName: String,
    val enabled: Boolean,
    val phase: String? = null,
    val patchId: String? = null,
    val readOnlyReason: String? = null
)

data class HarnessPluginRowUi(
    /** The row id declared by the bundle patch; it is display identity only. */
    val rowId: String,
    /** The live loader entry id, absent while an optional bundle is disabled. */
    val entryId: String? = null,
    val label: String,
    val enabled: Boolean,
    val canChange: Boolean = entryId != null,
    /** Cordis fiber phase from pluginInventory/listPlugins. */
    val phase: String? = null,
    val readOnlyReason: String? = null
)

data class HarnessPluginInspectionUi(
    val spec: String,
    val accepted: Boolean,
    val name: String? = null,
    val version: String? = null,
    val description: String? = null,
    val isBundle: Boolean? = null,
    val problem: String? = null,
    val reason: String? = null
)

data class HarnessPluginInstallUiState(
    val requestId: String? = null,
    val packageSpec: String? = null,
    val phase: String? = null,
    val logs: List<String> = emptyList(),
    val errorCode: String? = null,
    val pendingBuilds: List<String> = emptyList(),
    val approvedBuilds: List<String> = emptyList(),
    val restartRequired: Boolean = false
)

data class HarnessSkillUi(
    val id: String,
    val name: String,
    val summary: String,
    val enabled: Boolean = false,
    val installed: Boolean = true,
    val canToggle: Boolean = false,
    val canRemove: Boolean = false
)

data class HarnessJobUi(
    val id: String,
    val title: String,
    val statusLabel: String,
    val detail: String? = null,
    val canRead: Boolean = false,
    val canKill: Boolean = false
)

data class HarnessSubagentUi(
    val id: String,
    val title: String,
    val statusLabel: String,
    val detail: String? = null,
    val canPrompt: Boolean = true,
    val canInterrupt: Boolean = false
)

data class HarnessSubagentUiState(
    val enabled: Boolean = true,
    val canConfigure: Boolean = false,
    val maximumConcurrent: Int = 1,
    val maximumAllowed: Int = 4,
    val activeCount: Int = 0,
    val agentNames: List<String> = emptyList(),
    val children: List<HarnessSubagentUi> = emptyList()
)

data class HarnessTrajectoryUiState(
    val actualDuration: Boolean = false,
    val actualTime: Boolean = false,
    val allTurnsCollapsed: Boolean = false,
    val allAssistantsCollapsed: Boolean = false,
    val searchQuery: String = "",
    val canLoadOlder: Boolean = false,
    val isLoadingOlder: Boolean = false
)

data class HarnessAgentPresetUi(
    val id: String,
    val trust: String,
    val isDefault: Boolean,
    val name: String? = null,
    val description: String? = null,
    val broken: String? = null
)

data class HarnessAgentPresetUiState(
    val presets: List<HarnessAgentPresetUi> = emptyList(),
    val authorable: Boolean = false,
    val modeSelectionEnabled: Boolean = false,
    val selectedPresetId: String? = null,
    val documentPreview: String? = null,
    val isLoading: Boolean = false
)

data class HarnessWorkspaceGroupUi(
    val workspaceId: String,
    val path: String,
    val title: String,
    val sessionIds: List<String> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null
)

data class HarnessWorkspaceManagerUiState(
    val workspaces: List<HarnessWorkspaceGroupUi> = emptyList(),
    val archivedSessionIds: Set<String> = emptySet(),
    val isLoading: Boolean = false
)

data class HarnessCordisPackageUi(
    val packageId: String,
    val name: String,
    val purpose: String,
    val hasHostHalf: Boolean,
    val hasClientHalf: Boolean
)

data class HarnessCordisRunUi(
    val pluginRunId: String,
    val packageId: String,
    val mode: String,
    val status: String,
    val approvalRequestId: String? = null,
    val requiresApproval: Boolean = false,
    val error: String? = null
)

data class HarnessCordisPluginUi(
    val pluginId: String,
    val agentId: String,
    val packages: List<HarnessCordisPackageUi> = emptyList(),
    val currentPackageId: String? = null,
    val nextPackageId: String? = null,
    val activePackageId: String? = null,
    val latestRun: HarnessCordisRunUi? = null
)

data class HarnessCordisUiState(
    val plugins: List<HarnessCordisPluginUi> = emptyList(),
    val isLoading: Boolean = false,
    val inspectedPluginId: String? = null
)

data class HarnessExtensionsUiState(
    val plugins: List<HarnessPluginUi> = emptyList(),
    val pluginInventory: List<HarnessPluginInventoryUi> = emptyList(),
    /** Null while the first inventory read is pending; false means no managed profile. */
    val pluginManagementAvailable: Boolean? = null,
    val pluginInstall: HarnessPluginInstallUiState = HarnessPluginInstallUiState(),
    val pluginInspection: HarnessPluginInspectionUi? = null,
    val skills: List<HarnessSkillUi> = emptyList(),
    val jobs: List<HarnessJobUi> = emptyList(),
    val subagents: HarnessSubagentUiState = HarnessSubagentUiState(),
    val trajectory: HarnessTrajectoryUiState = HarnessTrajectoryUiState(),
    val cordis: HarnessCordisUiState = HarnessCordisUiState()
)

data class HarnessWorkspaceUiState(
    val projectFolder: String = ".",
    val backendLabel: String = "Local",
    val rootLabel: String? = null,
    val previewAvailable: Boolean = false,
    val runStatusLabel: String? = null
)

data class HarnessWebUiState(
    val available: Boolean = false,
    val title: String = "DeepSeek Harness Web UI",
    val endpointLabel: String? = null,
    val authenticated: Boolean = false
)

data class HarnessPermissionOptionUi(
    val value: String,
    val name: String,
    val description: String? = null
)

data class HarnessPermissionUiState(
    val currentValue: String? = null,
    val options: List<HarnessPermissionOptionUi> = emptyList(),
    val available: Boolean = false,
    val isLoading: Boolean = false
)

data class HarnessNoticeUi(
    val title: String? = null,
    val message: String? = null,
    val titleRes: Int? = null,
    val messageRes: Int? = null,
    val messageArgs: List<Any> = emptyList(),
    val recoverable: Boolean = true
)

data class NativeHarnessUiState(
    val runtime: HarnessRuntimeUiState = HarnessRuntimeUiState(),
    val sessions: List<HarnessSessionUiState> = emptyList(),
    val selectedSessionId: String? = null,
    val sessionSearchQuery: String = "",
    val sessionSearchResults: List<HarnessSessionSearchResultUi> = emptyList(),
    val sessionSearchHasMore: Boolean = false,
    val transcript: List<HarnessTranscriptItem> = emptyList(),
    val structuredTranscript: List<NativeHarnessStructuredTranscriptItem> = emptyList(),
    val structuredDetail: HarnessStructuredTranscriptDetailUiState = HarnessStructuredTranscriptDetailUiState(),
    val isCopyingTranscript: Boolean = false,
    val tokenUsage: HarnessTokenUsageUi? = null,
    val contextPressure: HarnessContextPressureUi? = null,
    val contextBreakdown: HarnessContextBreakdownUi? = null,
    val sessionStats: HarnessSessionStatsUi? = null,
    val messageFeedback: Map<String, HarnessMessageFeedbackUi> = emptyMap(),
    val messageFeedbackLoaded: Boolean = false,
    val canLoadOlderMessages: Boolean = false,
    val isLoadingOlderMessages: Boolean = false,
    val composerText: String = "",
    val composerMode: HarnessPromptMode = HarnessPromptMode.QUEUE,
    val attachments: List<HarnessAttachmentUi> = emptyList(),
    val queue: List<HarnessQueueItemUi> = emptyList(),
    val canCancelTurn: Boolean = false,
    val generationActivity: com.example.llamadroid.harness.HarnessGenerationActivity? = null,
    val isStoppingTurn: Boolean = false,
    val deletingSessionIds: Set<String> = emptySet(),
    val failedSessionDeletionIds: Set<String> = emptySet(),
    val commandLine: String = "",
    val commands: List<HarnessCommandUi> = emptyList(),
    val questions: List<HarnessQuestionUi> = emptyList(),
    val approvals: List<HarnessApprovalUi> = emptyList(),
    val plans: List<HarnessPlanUi> = emptyList(),
    val goal: HarnessGoalUi? = null,
    val provider: HarnessProviderUiState = HarnessProviderUiState(),
    val permission: HarnessPermissionUiState = HarnessPermissionUiState(),
    val settingsWritable: Boolean = false,
    val settingsHasDocument: Boolean = false,
    val settingsDocument: HarnessSettingsDocumentUi? = null,
    val managementLoads: Map<HarnessManagementArea, HarnessManagementLoadUi> = emptyMap(),
    val schemaSections: List<HarnessSchemaSection> = emptyList(),
    val capabilitySections: List<HarnessCapabilitySectionUi> = emptyList(),
    val agentPresets: HarnessAgentPresetUiState = HarnessAgentPresetUiState(),
    val workspaceManager: HarnessWorkspaceManagerUiState = HarnessWorkspaceManagerUiState(),
    val extensions: HarnessExtensionsUiState = HarnessExtensionsUiState(),
    val workspace: HarnessWorkspaceUiState = HarnessWorkspaceUiState(),
    val originalWebUi: HarnessWebUiState = HarnessWebUiState(),
    val legacyHistoryAvailable: Boolean = false,
    val isHistoryLoading: Boolean = false,
    val notice: HarnessNoticeUi? = null
)

sealed interface NativeHarnessUiAction {
    data object DismissNotice : NativeHarnessUiAction
    data class LoadManagement(val area: HarnessManagementArea, val force: Boolean = false) : NativeHarnessUiAction
    data object StartRuntime : NativeHarnessUiAction
    data object StopRuntime : NativeHarnessUiAction
    data object ForceStopRuntime : NativeHarnessUiAction
    data object OpenRuntimeJournal : NativeHarnessUiAction
    data object RefreshRuntimeDiagnostics : NativeHarnessUiAction
    data object CopyRuntimeDiagnostics : NativeHarnessUiAction
    data object Continue : NativeHarnessUiAction
    data object OpenWorkspace : NativeHarnessUiAction
    data object OpenProjectIntegration : NativeHarnessUiAction
    data object OpenOriginalWebUi : NativeHarnessUiAction
    data object OpenModelManager : NativeHarnessUiAction
    data object CloseModelManager : NativeHarnessUiAction
    data object OpenLegacyHistory : NativeHarnessUiAction
    data object CreateSession : NativeHarnessUiAction
    data class SelectSession(val sessionId: String) : NativeHarnessUiAction
    data class RenameSession(val sessionId: String, val title: String) : NativeHarnessUiAction
    data class ForkSession(val sessionId: String, val atSequence: Long? = null) : NativeHarnessUiAction
    data class DeleteSession(val sessionId: String) : NativeHarnessUiAction
    data class ArchiveSession(val sessionId: String) : NativeHarnessUiAction
    data class UnarchiveSession(val sessionId: String) : NativeHarnessUiAction
    data class SearchSessions(val query: String) : NativeHarnessUiAction
    data object RefreshSessions : NativeHarnessUiAction
    data class UpdateComposer(val text: String) : NativeHarnessUiAction
    data class SetComposerMode(val mode: HarnessPromptMode) : NativeHarnessUiAction
    data object OpenAttachmentPicker : NativeHarnessUiAction
    data object OpenReferencePicker : NativeHarnessUiAction
    data class OpenTranscriptLink(
        val sessionId: String,
        val target: HarnessInlineTarget,
        val workspaceSessionId: String? = null,
    ) : NativeHarnessUiAction
    data class InsertReference(val sessionId: String, val mention: String) : NativeHarnessUiAction
    data class OpenAttachment(val attachmentId: String) : NativeHarnessUiAction
    data class RemoveAttachment(val attachmentId: String) : NativeHarnessUiAction
    data object SubmitComposer : NativeHarnessUiAction
    data object CancelTurn : NativeHarnessUiAction
    data class EditQueueItem(val itemId: String, val content: String) : NativeHarnessUiAction
    data class RemoveQueueItem(val itemId: String) : NativeHarnessUiAction
    data class SteerQueueItem(val itemId: String) : NativeHarnessUiAction
    data class UpdateCommandLine(val line: String) : NativeHarnessUiAction
    data object ExecuteCommand : NativeHarnessUiAction
    data class AnswerQuestion(
        val questionId: String,
        val answer: String,
        val custom: Boolean = false,
        val selected: List<String> = emptyList()
    ) : NativeHarnessUiAction
    data class Approve(val approvalId: String) : NativeHarnessUiAction
    data class Reject(val approvalId: String) : NativeHarnessUiAction
    data class ApprovePlan(val planId: String) : NativeHarnessUiAction
    data class RejectPlan(val planId: String) : NativeHarnessUiAction
    data class CreateGoal(val objective: String, val maxGoalRounds: Int? = null) : NativeHarnessUiAction
    data class EditGoal(val objective: String? = null, val maxGoalRounds: Int? = null) : NativeHarnessUiAction
    data object PauseGoal : NativeHarnessUiAction
    data object ResumeGoal : NativeHarnessUiAction
    data object CompleteGoal : NativeHarnessUiAction
    data object ClearGoal : NativeHarnessUiAction
    data class ToggleTranscriptItem(val itemId: String) : NativeHarnessUiAction
    data class LoadTranscriptDetail(val itemId: String, val page: Int) : NativeHarnessUiAction
    data class CopyTranscriptTurn(val sessionId: String, val turn: Long, val throughSequence: Long, val finalAssistantSequence: Long?) : NativeHarnessUiAction
    data class CopyTranscriptMessage(val sessionId: String, val turn: Long?, val messageSequence: Long) : NativeHarnessUiAction
    data class ForkTranscriptTurn(val sessionId: String, val assistantSequence: Long) : NativeHarnessUiAction
    data object CancelTranscriptCopy : NativeHarnessUiAction
    data class SubmitMessageFeedback(
        val messageId: String,
        val rating: HarnessFeedbackRating,
        val note: String? = null,
        val category: String? = null
    ) : NativeHarnessUiAction
    data class RetractMessageFeedback(val messageId: String, val rating: HarnessFeedbackRating) : NativeHarnessUiAction
    data object LoadOlderMessages : NativeHarnessUiAction
    data class SelectProvider(val providerId: String) : NativeHarnessUiAction
    data class SelectModel(val modelName: String) : NativeHarnessUiAction
    data class SelectSessionModel(val providerId: String, val modelId: String) : NativeHarnessUiAction
    data object CreateProvider : NativeHarnessUiAction
    /** Creates a new `llm-pi-ai` route and stores an optional key separately. */
    data class CreateCustomProvider(val request: NativeHarnessCustomProviderRequest) : NativeHarnessUiAction
    /** Discovers models from an unsaved provider draft using ephemeral credentials. */
    data class DiscoverCustomProviderModels(val request: NativeHarnessCustomProviderRequest) : NativeHarnessUiAction
    data object DismissCustomProviderModels : NativeHarnessUiAction
    data class EditProvider(val providerId: String) : NativeHarnessUiAction
    data class SaveProvider(val providerId: String) : NativeHarnessUiAction
    data class DeleteProvider(val providerId: String) : NativeHarnessUiAction
    data class DiscoverProviderModels(val providerId: String) : NativeHarnessUiAction
    data class ToggleDiscoveredProviderModel(val providerId: String, val modelId: String) : NativeHarnessUiAction
    data class SetDiscoveredProviderModelsSelection(
        val providerId: String,
        val modelIds: List<String>,
        val selected: Boolean,
    ) : NativeHarnessUiAction
    data class AdoptDiscoveredProviderModels(val providerId: String) : NativeHarnessUiAction
    data class DismissDiscoveredProviderModels(val providerId: String) : NativeHarnessUiAction
    data class UpdateProviderField(val providerId: String, val key: String, val value: String) : NativeHarnessUiAction
    /** Secret value is transient and is never retained in [NativeHarnessUiState]. */
    data class SetProviderCredential(val providerId: String, val value: String) : NativeHarnessUiAction
    data class UnsetProviderCredential(val providerId: String) : NativeHarnessUiAction
    data class StartProviderLogin(val providerId: String, val method: String) : NativeHarnessUiAction
    data class AnswerProviderLogin(
        val providerId: String,
        val requestId: String,
        val answer: String,
        val declined: Boolean = false
    ) : NativeHarnessUiAction
    data class CancelProviderLogin(val providerId: String, val requestId: String) : NativeHarnessUiAction
    data class LogoutProvider(val providerId: String) : NativeHarnessUiAction
    data object RefreshModelCatalog : NativeHarnessUiAction
    data class SelectReasoningEffort(val effortId: String?) : NativeHarnessUiAction
    data object RefreshPermissionCatalog : NativeHarnessUiAction
    data class SelectPermissionPreset(val value: String) : NativeHarnessUiAction
    data class InvokeCapability(val capabilityId: String, val arguments: Map<String, String>) : NativeHarnessUiAction
    data class SetThinkingEnabled(val enabled: Boolean) : NativeHarnessUiAction
    data class UpdateProviderSetting(val key: String, val value: String) : NativeHarnessUiAction
    data class UpdateSchemaField(val key: String, val value: String) : NativeHarnessUiAction
    data class ResetSchemaField(val key: String) : NativeHarnessUiAction
    data object OpenSettingsDocument : NativeHarnessUiAction
    data class SaveSettingsDocument(val text: String) : NativeHarnessUiAction
    data object CloseSettingsDocument : NativeHarnessUiAction
    data class InstallPlugin(val packageSpec: String, val approvedBuilds: List<String> = emptyList()) : NativeHarnessUiAction
    data class InspectPlugin(val packageSpec: String) : NativeHarnessUiAction
    data class CancelPluginInstall(val requestId: String) : NativeHarnessUiAction
    data class UninstallPlugin(val pluginId: String) : NativeHarnessUiAction
    data class SetPluginBundleEnabled(val bundleName: String, val enabled: Boolean) : NativeHarnessUiAction
    data class SetPluginEnabled(val pluginId: String, val enabled: Boolean) : NativeHarnessUiAction
    data class SetPluginRowEnabled(val entryId: String?, val enabled: Boolean) : NativeHarnessUiAction
    data class ApprovePluginBuilds(val packageSpec: String, val approvedBuilds: List<String>) : NativeHarnessUiAction
    data object RefreshPlugins : NativeHarnessUiAction
    data object RefreshAgentPresets : NativeHarnessUiAction
    data class ReadAgentPreset(val presetId: String) : NativeHarnessUiAction
    data class OpenAgentPresetDirectory(val presetId: String) : NativeHarnessUiAction
    data class DuplicateAgentPreset(val fromPresetId: String, val presetId: String, val name: String? = null) : NativeHarnessUiAction
    data class DeleteAgentPreset(val presetId: String) : NativeHarnessUiAction
    data class SetAgentPresetDefault(val presetId: String) : NativeHarnessUiAction
    data class SetAgentPresetModeSelection(val enabled: Boolean) : NativeHarnessUiAction
    data class SelectAgentPreset(val sessionId: String, val presetId: String) : NativeHarnessUiAction
    data object RefreshWorkspaces : NativeHarnessUiAction
    data class RenameWorkspace(val workspaceId: String, val title: String) : NativeHarnessUiAction
    data class DeleteWorkspace(val workspaceId: String) : NativeHarnessUiAction
    data class MoveWorkspace(val workspaceId: String, val beforeWorkspaceId: String? = null) : NativeHarnessUiAction
    data class MoveSessionInWorkspace(
        val workspaceId: String,
        val sessionId: String,
        val beforeSessionId: String? = null
    ) : NativeHarnessUiAction
    data class ExportSessionLog(val sessionId: String) : NativeHarnessUiAction
    data object RefreshCordis : NativeHarnessUiAction
    data class RunCordis(
        val pluginId: String,
        val packageId: String,
        val update: Boolean = false
    ) : NativeHarnessUiAction
    data class ApproveCordis(
        val pluginId: String,
        val packageId: String,
        val requestId: String,
        val update: Boolean,
        val approveFutureVersions: Boolean
    ) : NativeHarnessUiAction
    data class RejectCordis(val requestId: String, val pluginRunId: String? = null) : NativeHarnessUiAction
    data class StopCordis(val pluginId: String) : NativeHarnessUiAction
    data class RemoveCordis(val pluginId: String) : NativeHarnessUiAction
    data class InspectCordis(val pluginId: String) : NativeHarnessUiAction
    data class SetSkillEnabled(val skillId: String, val enabled: Boolean) : NativeHarnessUiAction
    data class RemoveSkill(val skillId: String) : NativeHarnessUiAction
    data object RefreshSkills : NativeHarnessUiAction
    data class ReadJob(val jobId: String) : NativeHarnessUiAction
    data class KillJob(val jobId: String) : NativeHarnessUiAction
    data object RefreshJobs : NativeHarnessUiAction
    data class SetSubagentsEnabled(val enabled: Boolean) : NativeHarnessUiAction
    data class SetSubagentConcurrency(val maximum: Int) : NativeHarnessUiAction
    data class PromptSubagent(val childSessionId: String) : NativeHarnessUiAction
    data class InterruptSubagent(val childSessionId: String) : NativeHarnessUiAction
    data object RefreshSubagents : NativeHarnessUiAction
    data class SetActualDuration(val enabled: Boolean) : NativeHarnessUiAction
    data class SetActualTime(val enabled: Boolean) : NativeHarnessUiAction
    data object ToggleAllTrajectoryTurns : NativeHarnessUiAction
    data object ToggleAllTrajectoryCalls : NativeHarnessUiAction
    data class UpdateTrajectorySearch(val query: String) : NativeHarnessUiAction
    data object LoadOlderTrajectory : NativeHarnessUiAction
}

const val HARNESS_MAX_RENDERED_TRANSCRIPT_ITEMS = 600
const val HARNESS_MAX_RENDERED_MESSAGE_CHARACTERS = 24_000

/** Keeps transcript rendering bounded even if a restored session is very large. */
fun boundedHarnessTranscript(
    transcript: List<HarnessTranscriptItem>,
    maxItems: Int = HARNESS_MAX_RENDERED_TRANSCRIPT_ITEMS
): List<HarnessTranscriptItem> = transcript.takeLast(maxItems.coerceAtLeast(1))

/** Keeps a single stream or tool preview bounded before it enters a Compose text tree. */
fun boundedHarnessMessage(text: String): String {
    if (text.length <= HARNESS_MAX_RENDERED_MESSAGE_CHARACTERS) return text
    return text.take(HARNESS_MAX_RENDERED_MESSAGE_CHARACTERS) + "…"
}
