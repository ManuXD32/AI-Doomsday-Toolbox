package com.example.llamadroid.wear

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object AdtWearProtocol {
    const val VERSION = 1
    const val PREFIX = "/adt/v1"
    const val PHONE_CAPABILITY = "adt_phone_v1"
    const val WATCH_CAPABILITY = "adt_wear_v1"

    const val KEY_JSON = "json"
    const val KEY_ASSET = "asset"
    const val KEY_REVISION = "revision"
    const val MAX_DATA_ITEM_BYTES = 100 * 1024
    const val MAX_RPC_BYTES = 32 * 1024
    const val MAX_VOICE_BYTES = 3 * 1024 * 1024
    const val MAX_VOICE_DURATION_MS = 60_000L

    const val PING = "$PREFIX/ping"
    const val SYNC = "$PREFIX/sync"
    const val SERVER_STATUS = "$PREFIX/server/status"
    const val SERVER_LIST = "$PREFIX/server/list"
    const val SERVER_SELECT = "$PREFIX/server/select"
    const val SERVER_START = "$PREFIX/server/start"
    const val SERVER_STOP = "$PREFIX/server/stop"
    const val CHAT_LIST = "$PREFIX/chats/list"
    const val CHAT_CREATE = "$PREFIX/chats/create"
    const val CHAT_MESSAGES = "$PREFIX/chats/messages"
    const val CHAT_SEND = "$PREFIX/chats/send"
    const val CHAT_CLEAR = "$PREFIX/chat/clear"
    const val CHAT_MESSAGE_DELETE = "$PREFIX/chat/message/delete"
    const val CHAT_MESSAGE_RETRY = "$PREFIX/chat/message/retry"
    const val CHAT_PIN = "$PREFIX/chats/pin"
    const val CHAT_UNPIN = "$PREFIX/chats/unpin"
    const val QUICK_CHAT_CONFIG = "$PREFIX/chats/quick/config"
    const val QUICK_CHAT_CREATE = "$PREFIX/chats/quick/create"
    const val QUICK_CHAT_END = "$PREFIX/chats/quick/end"
    const val CHAT_TOOL_CONFIRM = "$PREFIX/chats/tool/confirm"
    const val CHAT_TOOL_REJECT = "$PREFIX/chats/tool/reject"
    const val TTS_GENERATE = "$PREFIX/tts/generate"
    const val TTS_AUDIO = "$PREFIX/tts/audio"
    const val GENERATION_CANCEL = "$PREFIX/generation/cancel"
    const val GENERATION_DELTA = "$PREFIX/generation/delta"
    const val GENERATION_FINAL = "$PREFIX/generation/final"
    const val ORGANIZER_EVENTS = "$PREFIX/organizer/events"
    const val ORGANIZER_EVENTS_MONTH = "$PREFIX/organizer/events/month"
    const val ORGANIZER_EVENT_UPSERT = "$PREFIX/organizer/event/upsert"
    const val ORGANIZER_EVENT_DELETE = "$PREFIX/organizer/event/delete"
    const val ORGANIZER_ALARMS = "$PREFIX/organizer/alarms"
    const val ORGANIZER_ALARM_UPSERT = "$PREFIX/organizer/alarm/upsert"
    const val ORGANIZER_ALARM_TOGGLE = "$PREFIX/organizer/alarm/toggle"
    const val ORGANIZER_ALARM_DELETE = "$PREFIX/organizer/alarm/delete"
    const val ORGANIZER_NOTES = "$PREFIX/organizer/notes"
    const val ORGANIZER_NOTE = "$PREFIX/organizer/note"
    const val ORGANIZER_NOTE_UPSERT = "$PREFIX/organizer/note/upsert"
    const val ORGANIZER_NOTE_DELETE = "$PREFIX/organizer/note/delete"
    const val ORGANIZER_PINNED_NOTE = "$PREFIX/organizer/note/pinned"
    const val ORGANIZER_NOTE_PIN = "$PREFIX/organizer/note/pin"
    const val MEDIA_REQUEST = "$PREFIX/media/request"
    const val MEDIA_ASSET = "$PREFIX/media/asset"
    const val PET_CURRENT = "$PREFIX/pet/current"
    const val TAMA_HUB = "$PREFIX/tama/hub"
    const val TAMA_ACTION = "$PREFIX/tama/action"
    const val TAMA_INVENTORY = "$PREFIX/tama/inventory"
    const val TAMA_STORE = "$PREFIX/tama/store"
    const val TAMA_FARM = "$PREFIX/tama/farm"
    const val TAMA_FARM_ACTION = "$PREFIX/tama/farm/action"
    const val TAMA_ADVENTURE = "$PREFIX/tama/adventure"
    const val TAMA_ADVENTURE_ACTION = "$PREFIX/tama/adventure/action"
    const val TAMA_RPG = "$PREFIX/tama/rpg"
    const val TAMA_RPG_ACTION = "$PREFIX/tama/rpg/action"
    const val TAMA_ARCADE = "$PREFIX/tama/arcade"
    const val TAMA_MESSAGES = "$PREFIX/tama/messages"
    const val TAMA_CHAT_SEND = "$PREFIX/tama/chat/send"
    const val VOICE_UPLOAD = "$PREFIX/voice/upload"
    const val VOICE_COMMIT = "$PREFIX/voice/commit"
    const val VOICE_ACK = "$PREFIX/voice/ack"
    const val TRANSLATOR_TEMPLATES = "$PREFIX/translator/templates"
    const val TRANSLATOR_START = "$PREFIX/translator/start"
    const val TRANSLATOR_STOP = "$PREFIX/translator/stop"
    const val TRANSLATOR_SPEAKER = "$PREFIX/translator/speaker"
    const val TRANSLATOR_STATE = "$PREFIX/translator/state"
    const val TRANSLATOR_TURNS = "$PREFIX/translator/turns"
    const val ACTIVE_TASKS = "$PREFIX/tasks/active"
    const val STATS = "$PREFIX/stats"
    const val TASK_CANCEL = "$PREFIX/tasks/cancel"
    const val TASK_PAUSE = "$PREFIX/tasks/pause"
    const val TASK_RESUME = "$PREFIX/tasks/resume"
    const val CAPABILITIES = "$PREFIX/capabilities"

    fun generationFinalPath(generationId: String): String = "$GENERATION_FINAL/$generationId"
    fun ttsAudioPath(requestId: String): String = "$TTS_AUDIO/$requestId"
    fun voiceUploadPath(requestId: String): String = "$VOICE_UPLOAD/$requestId"
    fun voiceAckPath(requestId: String): String = "$VOICE_ACK/$requestId"
    fun translatorTurnsPath(sessionId: Long): String = "$TRANSLATOR_TURNS/$sessionId"
    fun mediaAssetPath(mediaPathId: String): String = "$MEDIA_ASSET/$mediaPathId"

    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}

@Serializable
data class RpcMeta(
    val requestId: String,
    val protocolVersion: Int = AdtWearProtocol.VERSION,
    val watchVersionCode: Int = 0,
    val createdAtEpochMs: Long,
    val sourceNodeId: String? = null
)

@Serializable
data class RpcError(
    val code: String,
    val localizedMessage: String,
    val retryable: Boolean = false
)

@Serializable
data class RpcResponse<T>(
    val meta: RpcMeta,
    val status: String,
    val result: T? = null,
    val error: RpcError? = null,
    val bridgeState: BridgeState = BridgeState(),
    val phoneVersionCode: Int = 0,
    val phoneVersionName: String = "",
    val snapshotRevision: Long = 0L,
    val updatedAtEpochMs: Long
)

@Serializable
data class EmptyResult(val ok: Boolean = true)

@Serializable
data class BridgeState(
    val serverState: String = "unknown",
    val serverLabel: String = "",
    val connected: Boolean = true,
    val lastDataItemPath: String? = null,
    val lastErrorCode: String? = null
)

@Serializable
data class Revisioned(
    val revision: Long,
    val updatedAtEpochMs: Long,
    val sourceDeviceId: String
)

@Serializable
data class PingRequest(
    val meta: RpcMeta
)

@Serializable
data class PingResult(
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val certificateSha256Short: String,
    val localNodeId: String,
    val wearableApiAvailable: Boolean,
    val protocolVersion: Int = AdtWearProtocol.VERSION
)

@Serializable
data class LlamaServerSnapshot(
    val revisioned: Revisioned,
    val state: String,
    val label: String,
    val port: Int? = null,
    val progress: Float? = null,
    val error: String? = null
)

@Serializable
data class ServerCommandRequest(
    val meta: RpcMeta
)

@Serializable
data class ServerCommandResult(
    val requestId: String,
    val resultCode: String,
    val snapshot: LlamaServerSnapshot,
    val confirmationUri: String? = null,
    val localizedMessage: String
)

@Serializable
data class ServerListRequest(
    val meta: RpcMeta
)

@Serializable
data class ServerSelectRequest(
    val meta: RpcMeta,
    val serverId: Long
)

@Serializable
data class ServerSelectResult(
    val selectedServerId: Long,
    val page: ServerListPage
)

@Serializable
data class ServerSummary(
    val id: Long,
    val name: String,
    val engine: String,
    val endpoint: String,
    val modelName: String? = null,
    val supportsAudio: Boolean = false,
    val selected: Boolean = false
)

@Serializable
data class ServerListPage(
    val revisioned: Revisioned,
    val servers: List<ServerSummary>,
    val selectedServerId: Long? = null
)

@Serializable
data class ChatListRequest(
    val meta: RpcMeta,
    val cursor: String? = null,
    val limit: Int = 20
)

@Serializable
data class ChatSummary(
    val id: Long,
    val title: String,
    val folderId: Long? = null,
    val folderName: String? = null,
    val lastModifiedEpochMs: Long,
    val pinned: Boolean = false,
    val pinnedServerId: Long? = null,
    val preview: String = "",
    val messageCount: Int = 0
)

@Serializable
data class ChatListPage(
    val revisioned: Revisioned,
    val chats: List<ChatSummary>,
    val nextCursor: String? = null,
    val totalCount: Int,
    val limit: Int
)

@Serializable
data class ChatCreateRequest(
    val meta: RpcMeta,
    val title: String? = null
)

@Serializable
data class QuickChatConfig(
    val revisioned: Revisioned,
    val selectedServerId: Long? = null,
    val selectedServerLabel: String = "",
    val systemPrompt: String = "",
    val allowedTools: List<String> = emptyList(),
    val confirmationRequiredTools: List<String> = emptyList(),
    val inactivityTimeoutSeconds: Int = 300,
    val autoStartServer: Boolean = false,
    val autoPlayTts: Boolean = false,
    val retainFinalResult: Boolean = false,
    val maxResponseChars: Int = 2_000,
    val defaultInputMethod: String = "dictation"
)

@Serializable
data class QuickChatCreateRequest(
    val meta: RpcMeta
)

@Serializable
data class QuickChatEndRequest(
    val meta: RpcMeta,
    val chatId: Long
)

@Serializable
data class ToolConfirmationRequest(
    val meta: RpcMeta,
    val sessionId: String,
    val toolCallId: String,
    val commandId: String,
    val expiresAtEpochMs: Long
)

@Serializable
data class ChatPinRequest(
    val meta: RpcMeta,
    val chatId: Long,
    val serverId: Long? = null
)

@Serializable
data class ChatMessagesRequest(
    val meta: RpcMeta,
    val chatId: Long,
    val beforeMessageId: Long? = null,
    val limit: Int = 30
)

@Serializable
data class WearMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestampEpochMs: Long,
    val thinking: String? = null,
    val hasAudio: Boolean = false,
    val audioMediaId: String? = null,
    val hasImage: Boolean = false,
    val imageMediaId: String? = null,
    val speechText: String? = null,
    val error: String? = null,
    val canRetry: Boolean = false,
    val canDelete: Boolean = true,
    val isError: Boolean = false
)

@Serializable
data class ChatMessagePage(
    val revisioned: Revisioned,
    val chatId: Long,
    val messages: List<WearMessage>,
    val nextBeforeMessageId: Long? = null,
    val limit: Int
)

@Serializable
data class TamaMessagePage(
    val revisioned: Revisioned,
    val petId: String? = null,
    val messages: List<WearMessage>,
    val limit: Int
)

@Serializable
data class ChatSendRequest(
    val meta: RpcMeta,
    val chatId: Long,
    val serverId: Long,
    val text: String,
    val enableThinking: Boolean = true
)

@Serializable
data class ChatClearRequest(
    val meta: RpcMeta,
    val chatId: Long
)

@Serializable
data class ChatMessageActionRequest(
    val meta: RpcMeta,
    val chatId: Long,
    val messageId: Long,
    val serverId: Long? = null,
    val enableThinking: Boolean = true
)

@Serializable
data class TamaChatRequest(
    val meta: RpcMeta,
    val petId: String,
    val text: String,
    val enableThinking: Boolean = true
)

@Serializable
data class GenerationAccepted(
    val generationId: String,
    val targetType: String,
    val targetId: String,
    val acceptedAtEpochMs: Long
)

@Serializable
data class GenerationCancelRequest(
    val meta: RpcMeta,
    val generationId: String
)

@Serializable
data class GenerationDelta(
    val generationId: String,
    val targetType: String,
    val targetId: String,
    val sequence: Long,
    val content: String,
    val thinking: String? = null,
    val updatedAtEpochMs: Long
)

@Serializable
data class GenerationFinal(
    val revisioned: Revisioned,
    val generationId: String,
    val targetType: String,
    val targetId: String,
    val status: String,
    val content: String = "",
    val thinking: String? = null,
    val error: RpcError? = null
)

@Serializable
data class VoiceAssetMetadata(
    val meta: RpcMeta,
    val targetType: String,
    val targetId: String,
    val chatId: Long? = null,
    val serverId: Long? = null,
    val mimeType: String,
    val durationMs: Long,
    val byteCount: Long,
    val sha256: String,
    val languageHint: String? = null
)

@Serializable
data class VoiceCommitRequest(
    val meta: RpcMeta,
    val uploadRequestId: String,
    val enableThinking: Boolean = true
)

@Serializable
data class VoiceUploadAck(
    val requestId: String,
    val targetType: String,
    val targetId: String,
    val status: String,
    val localizedMessage: String,
    val generation: GenerationAccepted? = null,
    val updatedAtEpochMs: Long
)

@Serializable
data class TtsGenerateRequest(
    val meta: RpcMeta,
    val text: String,
    val languageHint: String? = null
)

@Serializable
data class TtsAudioResult(
    val requestId: String,
    val status: String,
    val localizedMessage: String,
    val mimeType: String? = null,
    val byteCount: Long = 0,
    val sha256: String? = null,
    val durationMs: Long? = null
)

@Serializable
data class WearMediaRequest(
    val meta: RpcMeta,
    val mediaId: String
)

@Serializable
data class WearMediaResult(
    val mediaId: String,
    val mediaPathId: String,
    val status: String,
    val localizedMessage: String,
    val mediaType: String,
    val mimeType: String,
    val byteCount: Long,
    val sha256: String? = null
)

@Serializable
data class OrganizerEventsRequest(
    val meta: RpcMeta,
    val startAtEpochMs: Long,
    val endAtEpochMs: Long,
    val limit: Int = 30
)

@Serializable
data class OrganizerEventSummary(
    val id: Long,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startAtEpochMs: Long,
    val endAtEpochMs: Long? = null,
    val allDay: Boolean = false,
    val alarmCount: Int = 0,
    val timezoneId: String = "",
    val colorArgb: Long? = null
)

@Serializable
data class OrganizerEventPage(
    val revisioned: Revisioned,
    val events: List<OrganizerEventSummary>,
    val limit: Int
)

@Serializable
data class OrganizerMonthRequest(
    val meta: RpcMeta,
    val year: Int,
    val month: Int,
    val zoneId: String? = null,
    val selectedDayEpochMs: Long? = null
)

@Serializable
data class OrganizerMonthDay(
    val dayOfMonth: Int,
    val dayStartEpochMs: Long,
    val eventCount: Int = 0,
    val hasAllDay: Boolean = false,
    val colorArgb: Long? = null
)

@Serializable
data class OrganizerMonthPage(
    val revisioned: Revisioned,
    val year: Int,
    val month: Int,
    val zoneId: String,
    val firstDayOfWeek: Int,
    val daysInMonth: Int,
    val selectedDayEpochMs: Long,
    val days: List<OrganizerMonthDay>,
    val selectedDayEvents: List<OrganizerEventSummary>
)

@Serializable
data class OrganizerEventUpsertRequest(
    val meta: RpcMeta,
    val id: Long? = null,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startAtEpochMs: Long,
    val endAtEpochMs: Long? = null,
    val allDay: Boolean = false,
    val timezoneId: String? = null,
    val colorArgb: Long? = null,
    val alarmAtEpochMs: Long? = null
)

@Serializable
data class OrganizerEventDeleteRequest(
    val meta: RpcMeta,
    val eventId: Long
)

@Serializable
data class OrganizerAlarmsRequest(
    val meta: RpcMeta,
    val limit: Int = 20
)

@Serializable
data class OrganizerAlarmSummary(
    val id: Long,
    val eventId: Long? = null,
    val title: String,
    val message: String = "",
    val triggerAtEpochMs: Long,
    val timezoneId: String = "",
    val soundEnabled: Boolean = true,
    val enabled: Boolean = true,
    val deliveredAtEpochMs: Long? = null
)

@Serializable
data class OrganizerAlarmPage(
    val revisioned: Revisioned,
    val alarms: List<OrganizerAlarmSummary>,
    val limit: Int
)

@Serializable
data class OrganizerAlarmUpsertRequest(
    val meta: RpcMeta,
    val id: Long? = null,
    val eventId: Long? = null,
    val title: String,
    val message: String = "",
    val triggerAtEpochMs: Long,
    val timezoneId: String? = null,
    val soundEnabled: Boolean = true,
    val enabled: Boolean = true
)

@Serializable
data class OrganizerAlarmToggleRequest(
    val meta: RpcMeta,
    val alarmId: Long,
    val enabled: Boolean
)

@Serializable
data class OrganizerAlarmDeleteRequest(
    val meta: RpcMeta,
    val alarmId: Long
)

@Serializable
data class OrganizerNotesRequest(
    val meta: RpcMeta,
    val query: String? = null,
    val cursor: String? = null,
    val limit: Int = 20
)

@Serializable
data class OrganizerNoteSummary(
    val id: Int,
    val title: String,
    val preview: String,
    val type: String,
    val updatedAtEpochMs: Long,
    val hasAudio: Boolean = false,
    val audioMediaId: String? = null
)

@Serializable
data class OrganizerNotePage(
    val revisioned: Revisioned,
    val notes: List<OrganizerNoteSummary>,
    val nextCursor: String? = null,
    val totalCount: Int,
    val limit: Int
)

@Serializable
data class OrganizerNoteRequest(
    val meta: RpcMeta,
    val noteId: Int
)

@Serializable
data class OrganizerNoteDetail(
    val revisioned: Revisioned,
    val id: Int,
    val title: String,
    val content: String,
    val type: String,
    val updatedAtEpochMs: Long,
    val hasAudio: Boolean = false,
    val audioMediaId: String? = null
)

@Serializable
data class OrganizerNoteUpsertRequest(
    val meta: RpcMeta,
    val id: Int? = null,
    val title: String,
    val content: String
)

@Serializable
data class OrganizerNoteDeleteRequest(
    val meta: RpcMeta,
    val noteId: Int
)

@Serializable
data class OrganizerPinnedNoteResult(
    val note: OrganizerNoteDetail? = null
)

@Serializable
data class OrganizerNotePinRequest(
    val meta: RpcMeta,
    val noteId: Int
)

@Serializable
data class MutationResult(
    val id: String,
    val localizedMessage: String
)

@Serializable
data class CommandAckDto(
    val commandId: String,
    val accepted: Boolean,
    val status: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val updatedAtEpochMs: Long
)

@Serializable
data class TranslatorTemplateSummary(
    val id: Long,
    val name: String,
    val speaker1Language: String,
    val speaker2Language: String,
    val backendEngine: String = "",
    val backendLabel: String = "",
    val modelLabel: String = ""
)

@Serializable
data class TranslatorTemplatePage(
    val revisioned: Revisioned,
    val templates: List<TranslatorTemplateSummary>
)

@Serializable
data class TranslatorCommandRequest(
    val meta: RpcMeta,
    val templateId: Long? = null,
    val sessionId: Long? = null,
    val speaker: Int? = null
)

@Serializable
data class TranslatorStateSnapshot(
    val revisioned: Revisioned,
    val isActive: Boolean,
    val sessionId: Long = -1L,
    val templateId: Long = -1L,
    val currentSpeaker: Int = 1,
    val phase: String = "IDLE",
    val status: String = "",
    val elapsedSeconds: Int = 0,
    val inputLevel: Float = 0f,
    val error: String? = null,
    val selectedTemplateId: Long? = null,
    val selectedTemplateName: String? = null,
    val backendEngine: String = "",
    val backendLabel: String = "",
    val modelLabel: String = "",
    val backendLoading: Boolean = false,
    val backendStatus: String = ""
)

@Serializable
data class TranslatorTurnSummary(
    val id: Long,
    val speaker: Int,
    val originalText: String,
    val translatedText: String? = null,
    val sourceLanguage: String,
    val targetLanguage: String,
    val timestampEpochMs: Long,
    val isError: Boolean = false,
    val errorMessage: String? = null
)

@Serializable
data class TranslatorTurnPage(
    val revisioned: Revisioned,
    val sessionId: Long,
    val turns: List<TranslatorTurnSummary>,
    val limit: Int
)

@Serializable
data class WearCapabilities(
    val revisioned: Revisioned,
    val minimumSupportedVersion: Int = 1,
    val featureFlags: List<String> = emptyList(),
    val supportsQuickChat: Boolean = true,
    val supportsPinnedChats: Boolean = true,
    val supportsServerControl: Boolean = true,
    val supportsTaskCancellation: Boolean = true,
    val supportsPetAssets: Boolean = true,
    val supportsAudioStreaming: Boolean = false,
    val supportsActiveTasks: Boolean = true,
    val supportsStats: Boolean = true
)

@Serializable
data class ActiveTaskSummary(
    val taskId: String,
    val taskType: String,
    val title: String,
    val subtitle: String = "",
    val state: String,
    val stage: String = "",
    val progressCurrent: Long? = null,
    val progressMaximum: Long? = null,
    val progressPercent: Int? = null,
    val indeterminate: Boolean = false,
    val speed: String = "",
    val etaSeconds: Long? = null,
    val startedAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long,
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val canCancel: Boolean = false,
    val deepLink: String = "",
    val errorMessage: String? = null
)

@Serializable
data class ActiveTaskSnapshot(
    val revisioned: Revisioned,
    val tasks: List<ActiveTaskSummary>
)

@Serializable
data class StatsRequest(
    val meta: RpcMeta,
    val sinceEpochMs: Long = System.currentTimeMillis() - 15L * 60L * 1000L,
    val untilEpochMs: Long = System.currentTimeMillis(),
    val maxPoints: Int = 15
)

@Serializable
data class WearStatsPoint(
    val timestampEpochMs: Long,
    val value: Float? = null
)

@Serializable
data class WearStatsSeries(
    val id: String,
    val unit: String = "",
    val points: List<WearStatsPoint> = emptyList()
)

@Serializable
data class WearStatsSnapshot(
    val revisioned: Revisioned,
    val enabled: Boolean,
    val sampledAtEpochMs: Long? = null,
    val summary: Map<String, String> = emptyMap(),
    val series: List<WearStatsSeries> = emptyList(),
    val availability: Map<String, String> = emptyMap()
)

@Serializable
data class TaskCommandRequest(
    val meta: RpcMeta,
    val taskId: String
)

@Serializable
data class PetSnapshot(
    val revisioned: Revisioned,
    val hasPet: Boolean,
    val petId: String? = null,
    val name: String? = null,
    val species: String? = null,
    val stage: String? = null,
    val mood: String? = null,
    val moodLabel: String? = null,
    val activity: String? = null,
    val activityLabel: String? = null,
    val location: String? = null,
    val hungerPercent: Int = 0,
    val happinessPercent: Int = 0,
    val healthPercent: Int = 0,
    val energyPercent: Int = 0,
    val hygienePercent: Int = 0,
    val spriteAssetId: String? = null,
    val backgroundAssetId: String? = null,
    val frozen: Boolean = false
)

@Serializable
data class TamaHubRequest(
    val meta: RpcMeta,
    val petId: String? = null,
    val limit: Int = 20
)

@Serializable
data class TamaModuleSummary(
    val id: String,
    val title: String,
    val status: String = "",
    val enabled: Boolean = true
)

@Serializable
data class TamaQuickAction(
    val id: String,
    val label: String,
    val moduleId: String,
    val requiresConfirmation: Boolean = false,
    val args: Map<String, String> = emptyMap()
)

@Serializable
data class TamaInventoryItemSummary(
    val id: String,
    val name: String,
    val type: String = "",
    val quantity: Int = 0,
    val price: Long? = null,
    val description: String = "",
    val actionLabel: String? = null
)

@Serializable
data class TamaHubSnapshot(
    val revisioned: Revisioned,
    val pet: PetSnapshot,
    val coins: Long = 0,
    val locationLabel: String = "",
    val modules: List<TamaModuleSummary> = emptyList(),
    val actions: List<TamaQuickAction> = emptyList(),
    val inventoryPreview: List<TamaInventoryItemSummary> = emptyList(),
    val recentEvents: List<String> = emptyList()
)

@Serializable
data class TamaActionRequest(
    val meta: RpcMeta,
    val petId: String? = null,
    val action: String,
    val args: Map<String, String> = emptyMap()
)

@Serializable
data class TamaActionResult(
    val success: Boolean,
    val localizedMessage: String,
    val hub: TamaHubSnapshot? = null
)

@Serializable
data class TamaInventoryPage(
    val revisioned: Revisioned,
    val items: List<TamaInventoryItemSummary>,
    val nextCursor: String? = null,
    val totalCount: Int = 0,
    val limit: Int = 20
)

@Serializable
data class TamaStorePage(
    val revisioned: Revisioned,
    val coins: Long = 0,
    val items: List<TamaInventoryItemSummary> = emptyList(),
    val upgrades: List<TamaInventoryItemSummary> = emptyList(),
    val livestock: List<TamaInventoryItemSummary> = emptyList()
)

@Serializable
data class TamaFarmTileSummary(
    val id: String,
    val title: String,
    val status: String = "",
    val progressPercent: Int = 0,
    val actionLabel: String? = null
)

@Serializable
data class TamaFarmSnapshot(
    val revisioned: Revisioned,
    val tiles: List<TamaFarmTileSummary> = emptyList(),
    val upgrades: List<TamaModuleSummary> = emptyList(),
    val livestock: List<TamaModuleSummary> = emptyList(),
    val status: String = ""
)

@Serializable
data class TamaFarmActionRequest(
    val meta: RpcMeta,
    val petId: String? = null,
    val action: String,
    val tileId: String? = null,
    val itemId: String? = null,
    val args: Map<String, String> = emptyMap()
)

@Serializable
data class TamaChoiceSummary(
    val id: String,
    val label: String,
    val description: String = ""
)

@Serializable
data class TamaAdventureSnapshot(
    val revisioned: Revisioned,
    val mode: String = "adventure",
    val sessionId: String? = null,
    val title: String = "",
    val status: String = "",
    val story: String = "",
    val choices: List<TamaChoiceSummary> = emptyList()
)

@Serializable
data class TamaAdventureActionRequest(
    val meta: RpcMeta,
    val petId: String? = null,
    val mode: String = "adventure",
    val action: String,
    val choiceId: String? = null,
    val args: Map<String, String> = emptyMap()
)

@Serializable
data class TamaRpgSnapshot(
    val revisioned: Revisioned,
    val status: String = "",
    val profile: List<TamaModuleSummary> = emptyList(),
    val actions: List<TamaQuickAction> = emptyList(),
    val battleLog: List<String> = emptyList()
)

@Serializable
data class TamaRpgActionRequest(
    val meta: RpcMeta,
    val petId: String? = null,
    val action: String,
    val args: Map<String, String> = emptyMap()
)

@Serializable
data class TamaArcadeSnapshot(
    val revisioned: Revisioned,
    val games: List<TamaModuleSummary> = emptyList(),
    val status: String = ""
)

@Serializable
data class DiagnosticsSnapshot(
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val certificateSha256Short: String,
    val wearableApiAvailable: Boolean,
    val localNodeId: String = "",
    val reachablePhoneNodes: Int = 0,
    val connectedNodes: Int = 0,
    val nearbyPhoneNodes: Int = 0,
    val reciprocalCapabilityNodes: Int = 0,
    val capabilityNames: List<String> = emptyList(),
    val lastPingAtEpochMs: Long? = null,
    val lastDataItemPath: String? = null,
    val lastDataItemRevision: Long? = null,
    val lastRpcError: String? = null
)
