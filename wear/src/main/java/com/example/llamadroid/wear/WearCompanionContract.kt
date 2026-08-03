package com.example.llamadroid.wear

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object WearCompanionContract {
    const val PROTOCOL_VERSION = 2
    const val MAX_DATA_ITEM_BYTES = 100_000

    const val PATH_HOME_SNAPSHOT = "/wear/home_snapshot"
    const val PATH_CHATS_SNAPSHOT = "/wear/chats_snapshot"
    const val PATH_SERVERS_SNAPSHOT = "/wear/servers_snapshot"
    const val PATH_TAMA_ACTIVE_PET = "/wear/tama/active_pet"
    const val PATH_TAMA_MESSAGES = "/wear/tama/messages"
    const val PATH_ACTIVE_TURN_PREFIX = "/wear/active_turn/"
    const val PATH_COMMAND_ACK_PREFIX = "/wear/command_ack/"
    const val PATH_COMMAND_OUTBOX_PREFIX = "/wear/command_outbox/"
    const val PATH_CHAT_MESSAGES_PREFIX = "/wear/chat/"
    const val PATH_RPC_PREFIX = "/wear/rpc/"
    const val PATH_RPC_COMMAND = "/wear/rpc/command"
    const val CHANNEL_AUDIO_CHAT_PREFIX = "/wear/audio/chat/"
    const val CHANNEL_AUDIO_TAMA_PREFIX = "/wear/audio/tama/"
    const val CHANNEL_AUDIO_REPLY_PREFIX = "/wear/audio/reply/"

    const val MESSAGE_REQUEST_REFRESH = "/wear/request_refresh"
    const val MESSAGE_OPEN_CHAT = "/wear/open_chat"
    const val MESSAGE_SEND_TEXT_TURN = "/wear/send_text_turn"
    const val MESSAGE_SEND_AUDIO_TURN = "/wear/send_audio_turn"
    const val MESSAGE_PIN_CHAT = "/wear/pin_chat"
    const val MESSAGE_UNPIN_CHAT = "/wear/unpin_chat"
    const val MESSAGE_STOP_TURN = "/wear/stop_turn"
    const val MESSAGE_SELECT_SERVER = "/wear/select_server"
    const val MESSAGE_SEND_TAMA_TEXT = "/wear/tama/send_text"
    const val MESSAGE_SEND_TAMA_AUDIO = "/wear/tama/send_audio"
    const val MESSAGE_LLAMA_SERVER_START = "/wear/llama_server/start"
    const val MESSAGE_LLAMA_SERVER_STOP = "/wear/llama_server/stop"

    const val CAPABILITY_PHONE_BRIDGE = "adt_phone_bridge"
    const val CAPABILITY_PHONE_BRIDGE_V2 = "adt_phone_bridge_v2"

    const val COMMAND_SYNC_PHONE = "sync_phone"
    const val COMMAND_START_SERVER = "start_server"
    const val COMMAND_STOP_SERVER = "stop_server"
    const val COMMAND_OPEN_CHAT = "open_chat"
    const val COMMAND_SEND_CHAT_TEXT = "send_chat_text"
    const val COMMAND_PIN_CHAT = "pin_chat"
    const val COMMAND_UNPIN_CHAT = "unpin_chat"
    const val COMMAND_SELECT_SERVER = "select_server"
    const val COMMAND_SEND_TAMA_TEXT = "send_tama_text"
    const val COMMAND_STOP_TURN = "stop_turn"
    const val COMMAND_PING_BRIDGE = "ping_bridge"

    const val KEY_JSON = "json"

    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun chatMessagesPath(chatId: Long): String = "$PATH_CHAT_MESSAGES_PREFIX$chatId/messages"

    fun activeTurnPath(requestId: String): String = "$PATH_ACTIVE_TURN_PREFIX$requestId"

    fun commandAckPath(requestId: String): String = "$PATH_COMMAND_ACK_PREFIX$requestId"

    fun commandOutboxPath(requestId: String): String = "$PATH_COMMAND_OUTBOX_PREFIX$requestId"

    fun chatAudioChannelPath(requestId: String, chatId: Long, serverId: Long, durationMs: Long): String =
        "$CHANNEL_AUDIO_CHAT_PREFIX$requestId/$chatId/$serverId/$durationMs"

    fun tamaAudioChannelPath(requestId: String, petId: String, durationMs: Long): String =
        "$CHANNEL_AUDIO_TAMA_PREFIX$requestId/$petId/$durationMs"

    fun replyAudioChannelPath(requestId: String): String = "$CHANNEL_AUDIO_REPLY_PREFIX$requestId"
}

@Serializable
data class WearHomeSnapshot(
    val llamaServerState: WearLlamaServerState = WearLlamaServerState(),
    val chatsAvailable: Boolean = false,
    val tamaAvailable: Boolean = false,
    val protocolVersion: Int = WearCompanionContract.PROTOCOL_VERSION,
    val phoneVersionCode: Int = 0,
    val phoneVersionName: String = "",
    val snapshotRevision: Long = 0L,
    val updatedAt: Long = 0L,
    val error: String? = null
)

@Serializable
data class WearLlamaServerState(
    val state: String = "stopped",
    val label: String = "Stopped",
    val port: Int? = null,
    val progress: Float? = null,
    val error: String? = null
)

@Serializable
data class WearChatsSnapshot(
    val folders: List<WearChatFolder> = emptyList(),
    val chats: List<WearChatSummary> = emptyList(),
    val updatedAt: Long = 0L
)

@Serializable
data class WearChatFolder(
    val id: Long? = null,
    val name: String,
    val isVirtual: Boolean = false
)

@Serializable
data class WearChatSummary(
    val id: Long,
    val title: String,
    val folderId: Long? = null,
    val lastModified: Long,
    val pinned: Boolean = false,
    val pinnedServerId: Long? = null,
    val preview: String = ""
)

@Serializable
data class WearServersSnapshot(
    val servers: List<WearServerSummary> = emptyList(),
    val selectedServerId: Long? = null,
    val updatedAt: Long = 0L
)

@Serializable
data class WearServerSummary(
    val id: Long,
    val name: String,
    val engine: String,
    val endpoint: String,
    val modelName: String? = null,
    val supportsAudio: Boolean = false
)

@Serializable
data class WearMessagesSnapshot(
    val ownerId: String,
    val messages: List<WearMessageSummary> = emptyList(),
    val updatedAt: Long = 0L
)

@Serializable
data class WearMessageSummary(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val hasAudio: Boolean = false,
    val audioPath: String? = null,
    val thinking: String? = null,
    val error: String? = null
)

@Serializable
data class WearTamaPetSnapshot(
    val hasPet: Boolean = false,
    val petId: String? = null,
    val name: String = "",
    val species: String = "",
    val stage: String = "",
    val mood: String = "",
    val moodLabel: String = "",
    val activity: String = "",
    val activityLabel: String = "",
    val locationId: String = "",
    val backgroundAssetPath: String? = null,
    val spriteAssetPath: String? = null,
    val hunger: Int = 0,
    val happiness: Int = 0,
    val health: Int = 0,
    val energy: Int = 0,
    val hygiene: Int = 0,
    val updatedAt: Long = 0L
)

@Serializable
data class WearTurnState(
    val requestId: String,
    val target: String,
    val targetId: String,
    val status: String,
    val content: String = "",
    val thinking: String? = null,
    val audioPath: String? = null,
    val error: String? = null,
    val updatedAt: Long = 0L
)

@Serializable
data class WearSimpleCommand(
    val requestId: String,
    val command: String,
    val createdAt: Long = 0L
)

@Serializable
data class WearCommandAck(
    val requestId: String,
    val command: String,
    val status: String,
    val localizedMessage: String,
    val updatedAt: Long = 0L
)

@Serializable
data class WearBridgeRequest(
    val requestId: String,
    val command: String,
    val payload: String = "",
    val protocolVersion: Int = WearCompanionContract.PROTOCOL_VERSION,
    val watchVersionCode: Int = 0,
    val createdAt: Long = 0L
)

@Serializable
data class WearBridgeResponse(
    val requestId: String,
    val command: String,
    val status: String,
    val localizedMessage: String,
    val bridgeState: String = "",
    val phoneVersionCode: Int = 0,
    val snapshotRevision: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class WearRoutedCommand(
    val requestId: String,
    val messagePath: String,
    val payload: String,
    val createdAt: Long = 0L
)

@Serializable
data class WearOpenChatCommand(
    val chatId: Long,
    val requestId: String = ""
)

@Serializable
data class WearSendChatTextCommand(
    val requestId: String,
    val chatId: Long,
    val serverId: Long,
    val text: String
)

@Serializable
data class WearChatPinCommand(
    val chatId: Long,
    val serverId: Long? = null,
    val requestId: String = ""
)

@Serializable
data class WearSelectServerCommand(
    val serverId: Long,
    val requestId: String = ""
)

@Serializable
data class WearSendTamaTextCommand(
    val requestId: String,
    val petId: String,
    val text: String
)
