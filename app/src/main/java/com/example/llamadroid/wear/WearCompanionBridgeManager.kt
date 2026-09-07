package com.example.llamadroid.wear

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.service.LlamaClientService
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.LocationType
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TamaRoomCatalog
import com.example.llamadroid.tama.data.TamaTrainingCatalog
import com.example.llamadroid.tama.data.TamaWorkCatalog
import com.example.llamadroid.tama.data.mapPetActionToSpriteState
import com.example.llamadroid.tama.data.resolvePetSpriteAssetPath
import com.example.llamadroid.tama.db.TamaChatMessageEntity
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.util.DebugLog
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableStatusCodes
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object WearCompanionBridgeManager {
    private const val MAX_WEAR_CHATS = 80
    private const val MAX_WEAR_MESSAGES = 20
    private const val MAX_WEAR_PREVIEW_CHARS = 96
    private const val MAX_WEAR_MESSAGE_CHARS = 2_000
    private const val KEY_SELECTED_SERVER_ID = "selected_server_id"
    private const val TAMA_REPLY_SETTLE_MS = 1200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processedRequests = Collections.synchronizedSet(mutableSetOf<String>())
    private val snapshotRevision = AtomicLong(0L)
    private lateinit var appContext: Context
    private lateinit var appDatabase: AppDatabase
    private lateinit var tamaDatabase: TamaDatabase
    private lateinit var repository: LlamaRepository
    private lateinit var settingsRepo: SettingsRepository
    // The bridge is process-scoped and TamaAgentService stores applicationContext only.
    @SuppressLint("StaticFieldLeak")
    private lateinit var tamaAgentService: TamaAgentService
    @Volatile private var started = false

    private val messageListener = com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener { event ->
        handleMessage(appContext, event)
    }

    private val dataListener = DataClient.OnDataChangedListener { events ->
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                handleDataItem(appContext, event.dataItem)
            }
        }
    }

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            handleChannel(appContext, channel)
        }
    }

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            appContext = context.applicationContext
            appDatabase = AppDatabase.getDatabase(appContext)
            tamaDatabase = TamaDatabase.getInstance(appContext)
            repository = LlamaRepository(
                appDatabase.llamaServerDao(),
                appDatabase.llamaChatDao(),
                appDatabase.llamaChatFolderDao(),
                appDatabase.llamaMessageDao(),
                appDatabase.llamaChatPromptProfileDao()
            )
            settingsRepo = SettingsRepository(appContext)
            tamaAgentService = TamaAgentService(
                context = appContext,
                dao = tamaDatabase.tamaDao(),
                settingsRepo = settingsRepo,
                ollamaService = OllamaService(appContext),
                scope = scope
            )
            Wearable.getMessageClient(appContext).addListener(messageListener)
            advertiseBridgeCapabilities()
            Wearable.getDataClient(appContext).addListener(
                dataListener,
                commandOutboxUri(),
                DataClient.FILTER_PREFIX
            )
            Wearable.getChannelClient(appContext).registerChannelCallback(channelCallback)
            started = true
            startSnapshotObservers()
            processPendingCommandOutbox()
            scope.launch { publishAllSnapshots() }
            DebugLog.log("[WEAR] bridge identity package=${appContext.packageName} version=${BuildConfig.VERSION_CODE}/${BuildConfig.VERSION_NAME}")
            DebugLog.log("[WEAR] phone bridge manager started")
        }
    }

    private fun advertiseBridgeCapabilities() {
        val capabilityClient = Wearable.getCapabilityClient(appContext)
        listOf(
            WearCompanionContract.CAPABILITY_PHONE_BRIDGE,
            WearCompanionContract.CAPABILITY_PHONE_BRIDGE_V2
        ).forEach { capability ->
            capabilityClient.addLocalCapability(capability)
                .addOnSuccessListener {
                    DebugLog.log("[WEAR] advertised capability $capability")
                }
                .addOnFailureListener { error ->
                    if (error is ApiException && error.statusCode == WearableStatusCodes.DUPLICATE_CAPABILITY) {
                        DebugLog.log("[WEAR] capability already advertised $capability")
                    } else {
                        DebugLog.log("[WEAR] failed to advertise capability $capability: ${error.message}")
                    }
                }
        }
    }

    private fun ensureStarted(context: Context) {
        start(context)
    }

    private fun startSnapshotObservers() {
        scope.launch {
            LlamaService.state.collect {
                publishSafely("home") { publishHomeSnapshot() }
            }
        }
        scope.launch {
            repository.allServers.collect {
                publishSafely("servers") { publishServersSnapshot() }
            }
        }
        scope.launch {
            repository.allChatFolders.collect {
                publishSafely("chat folders") { publishChatsSnapshot() }
            }
        }
        scope.launch {
            repository.allChats.collect {
                publishSafely("chats") {
                    publishHomeSnapshot()
                    publishChatsSnapshot()
                }
            }
        }
        scope.launch {
            tamaDatabase.tamaDao().observeActivePet().collect {
                publishSafely("tama") {
                    publishHomeSnapshot()
                    publishTamaSnapshot()
                    publishTamaMessages()
                }
            }
        }
    }

    private suspend fun publishSafely(label: String, block: suspend () -> Unit) {
        runCatching { block() }
            .onFailure { error -> DebugLog.log("[WEAR] snapshot observer $label failed: ${error.message}") }
    }

    fun handleMessage(context: Context, messageEvent: MessageEvent) {
        ensureStarted(context)
        val payload = messageEvent.data.toString(Charsets.UTF_8)
        DebugLog.log("[WEAR] message ${messageEvent.path} from ${messageEvent.sourceNodeId}")
        routeCommand(messageEvent.path, payload, messageEvent.sourceNodeId)
    }

    fun handleRpcRequest(context: Context, nodeId: String, path: String, request: ByteArray): Task<ByteArray> {
        ensureStarted(context)
        val completion = TaskCompletionSource<ByteArray>()
        scope.launch {
            val response = runCatching {
                if (!path.startsWith(WearCompanionContract.PATH_RPC_PREFIX)) {
                    throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_command_failed))
                }
                val bridgeRequest = WearCompanionContract.json.decodeFromString(
                    WearBridgeRequest.serializer(),
                    request.toString(Charsets.UTF_8)
                )
                DebugLog.log("[WEAR] rpc ${bridgeRequest.command} from $nodeId")
                handleBridgeRequest(bridgeRequest, nodeId)
            }.getOrElse { error ->
                DebugLog.log("[WEAR] rpc $path failed: ${error.message}")
                WearBridgeResponse(
                    requestId = UUID.randomUUID().toString(),
                    command = path,
                    status = "error",
                    localizedMessage = error.wearBridgeMessage(),
                    bridgeState = LlamaService.state.value.toWearState().state,
                    phoneVersionCode = BuildConfig.VERSION_CODE,
                    snapshotRevision = snapshotRevision.get(),
                    updatedAt = System.currentTimeMillis()
                )
            }
            completion.setResult(
                WearCompanionContract.json.encodeToString(WearBridgeResponse.serializer(), response)
                    .toByteArray(Charsets.UTF_8)
            )
        }
        return completion.task
    }

    private suspend fun handleBridgeRequest(request: WearBridgeRequest, sourceNodeId: String?): WearBridgeResponse {
        if (request.protocolVersion > WearCompanionContract.PROTOCOL_VERSION) {
            return bridgeResponse(
                request,
                status = "error",
                message = appContext.getString(R.string.wear_bridge_protocol_mismatch)
            )
        }

        return runCatching {
            when (request.command) {
                WearCompanionContract.COMMAND_PING_BRIDGE -> {
                    bridgeResponse(request, "success", appContext.getString(R.string.wear_bridge_command_accepted))
                }
                WearCompanionContract.COMMAND_SYNC_PHONE -> {
                    publishAllSnapshots()
                    bridgeResponse(request, "success", appContext.getString(R.string.wear_bridge_synced))
                }
                WearCompanionContract.COMMAND_OPEN_CHAT -> {
                    val command = WearCompanionContract.json.decodeFromString<WearOpenChatCommand>(request.payload)
                    publishChatMessages(command.chatId)
                    bridgeResponse(request, "success", appContext.getString(R.string.wear_bridge_synced))
                }
                WearCompanionContract.COMMAND_SEND_CHAT_TEXT -> {
                    val command = WearCompanionContract.json.decodeFromString<WearSendChatTextCommand>(request.payload)
                    if (markRequestProcessed(command.requestId)) {
                        scope.launch { sendNativeChatTurn(command, sourceNodeId) }
                    }
                    bridgeResponse(request, "accepted", appContext.getString(R.string.wear_bridge_chat_sent))
                }
                WearCompanionContract.COMMAND_PIN_CHAT -> {
                    val command = WearCompanionContract.json.decodeFromString<WearChatPinCommand>(request.payload)
                    repository.updateChatAiHubPin(command.chatId, pinned = true, serverId = command.serverId)
                    publishChatsSnapshot()
                    bridgeResponse(request, "success", appContext.getString(R.string.wear_bridge_synced))
                }
                WearCompanionContract.COMMAND_UNPIN_CHAT -> {
                    val command = WearCompanionContract.json.decodeFromString<WearChatPinCommand>(request.payload)
                    repository.updateChatAiHubPin(command.chatId, pinned = false, serverId = null)
                    publishChatsSnapshot()
                    bridgeResponse(request, "success", appContext.getString(R.string.wear_bridge_synced))
                }
                WearCompanionContract.COMMAND_SELECT_SERVER -> {
                    val command = WearCompanionContract.json.decodeFromString<WearSelectServerCommand>(request.payload)
                    selectedServerPrefs().edit().putLong(KEY_SELECTED_SERVER_ID, command.serverId).apply()
                    publishServersSnapshot()
                    bridgeResponse(request, "success", appContext.getString(R.string.wear_bridge_synced))
                }
                WearCompanionContract.COMMAND_STOP_TURN -> {
                    stopNativeChatTurn()
                    bridgeResponse(request, "accepted", appContext.getString(R.string.wear_bridge_command_accepted))
                }
                WearCompanionContract.COMMAND_SEND_TAMA_TEXT -> {
                    val command = WearCompanionContract.json.decodeFromString<WearSendTamaTextCommand>(request.payload)
                    if (markRequestProcessed(command.requestId)) {
                        scope.launch { sendTamaText(command) }
                    }
                    bridgeResponse(request, "accepted", appContext.getString(R.string.wear_bridge_tama_sent))
                }
                WearCompanionContract.COMMAND_START_SERVER -> {
                    startLlamaCppServer().getOrThrow()
                    publishHomeSnapshot()
                    bridgeResponse(request, "accepted", appContext.getString(R.string.wear_bridge_server_start_sent))
                }
                WearCompanionContract.COMMAND_STOP_SERVER -> {
                    stopLlamaCppServer().getOrThrow()
                    publishHomeSnapshot()
                    bridgeResponse(request, "accepted", appContext.getString(R.string.wear_bridge_server_stop_sent))
                }
                else -> bridgeResponse(request, "error", appContext.getString(R.string.wear_bridge_command_failed))
            }
        }.getOrElse { error ->
            val message = error.wearBridgeMessage()
            runCatching { publishHomeSnapshot(message) }
                .onFailure { publishError ->
                    DebugLog.log("[WEAR] failed to publish rpc error snapshot: ${publishError.message}")
                }
            bridgeResponse(request, "error", message)
        }
    }

    fun handleDataItem(context: Context, dataItem: DataItem) {
        ensureStarted(context)
        val path = dataItem.uri.path ?: return
        if (!path.startsWith(WearCompanionContract.PATH_COMMAND_OUTBOX_PREFIX)) return
        val payload = runCatching {
            DataMapItem.fromDataItem(dataItem).dataMap.getString(WearCompanionContract.KEY_JSON)
        }.getOrNull() ?: return
        val command = runCatching {
            WearCompanionContract.json.decodeFromString(WearRoutedCommand.serializer(), payload)
        }.getOrElse { error ->
            DebugLog.log("[WEAR] command outbox decode failed: ${error.message}")
            return
        }
        DebugLog.log("[WEAR] data command ${command.messagePath} from ${dataItem.uri.host}")
        routeCommand(command.messagePath, command.payload, dataItem.uri.host)
        Wearable.getDataClient(appContext).deleteDataItems(dataItem.uri)
    }

    private fun processPendingCommandOutbox() {
        Wearable.getDataClient(appContext)
            .getDataItems(commandOutboxUri(), DataClient.FILTER_PREFIX)
            .addOnSuccessListener { buffer ->
                buffer.use { items ->
                    items.forEach { item -> handleDataItem(appContext, item.freeze()) }
                }
            }
            .addOnFailureListener { error ->
                DebugLog.log("[WEAR] pending command outbox read failed: ${error.message}")
            }
    }

    private fun commandOutboxUri(): Uri =
        Uri.Builder()
            .scheme("wear")
            .authority("*")
            .path(WearCompanionContract.PATH_COMMAND_OUTBOX_PREFIX)
            .build()

    private fun routeCommand(path: String, payload: String, sourceNodeId: String?) {
        scope.launch {
            var ack: WearCommandAck? = null
            runCatching {
                when (path) {
                    WearCompanionContract.MESSAGE_REQUEST_REFRESH -> {
                        val command = decodeSimpleCommand(payload, WearCompanionContract.COMMAND_SYNC_PHONE)
                        if (markRequestProcessed(command.requestId)) {
                            ack = pendingAck(command, appContext.getString(R.string.wear_bridge_syncing))
                            publishCommandAck(ack!!)
                            publishAllSnapshots()
                            ack = successAck(command, appContext.getString(R.string.wear_bridge_synced))
                        }
                    }
                    WearCompanionContract.MESSAGE_OPEN_CHAT -> {
                        val command = WearCompanionContract.json.decodeFromString<WearOpenChatCommand>(payload)
                        publishChatMessages(command.chatId)
                        if (command.requestId.isNotBlank()) {
                            ack = successAck(command.requestId, WearCompanionContract.COMMAND_OPEN_CHAT, appContext.getString(R.string.wear_bridge_synced))
                        }
                    }
                    WearCompanionContract.MESSAGE_SEND_TEXT_TURN -> {
                        val command = WearCompanionContract.json.decodeFromString<WearSendChatTextCommand>(payload)
                        if (markRequestProcessed(command.requestId)) {
                            publishCommandAck(
                                successAck(command.requestId, "send_chat_turn", appContext.getString(R.string.wear_bridge_chat_sent))
                            )
                            sendNativeChatTurn(command, sourceNodeId)
                        }
                    }
                    WearCompanionContract.MESSAGE_PIN_CHAT -> {
                        val command = WearCompanionContract.json.decodeFromString<WearChatPinCommand>(payload)
                        repository.updateChatAiHubPin(command.chatId, pinned = true, serverId = command.serverId)
                        publishChatsSnapshot()
                        if (command.requestId.isNotBlank()) {
                            ack = successAck(command.requestId, WearCompanionContract.COMMAND_PIN_CHAT, appContext.getString(R.string.wear_bridge_synced))
                        }
                    }
                    WearCompanionContract.MESSAGE_UNPIN_CHAT -> {
                        val command = WearCompanionContract.json.decodeFromString<WearChatPinCommand>(payload)
                        repository.updateChatAiHubPin(command.chatId, pinned = false, serverId = null)
                        publishChatsSnapshot()
                        if (command.requestId.isNotBlank()) {
                            ack = successAck(command.requestId, WearCompanionContract.COMMAND_UNPIN_CHAT, appContext.getString(R.string.wear_bridge_synced))
                        }
                    }
                    WearCompanionContract.MESSAGE_SELECT_SERVER -> {
                        val command = WearCompanionContract.json.decodeFromString<WearSelectServerCommand>(payload)
                        selectedServerPrefs().edit().putLong(KEY_SELECTED_SERVER_ID, command.serverId).apply()
                        publishServersSnapshot()
                        if (command.requestId.isNotBlank()) {
                            ack = successAck(command.requestId, WearCompanionContract.COMMAND_SELECT_SERVER, appContext.getString(R.string.wear_bridge_synced))
                        }
                    }
                    WearCompanionContract.MESSAGE_STOP_TURN -> {
                        val command = decodeSimpleCommand(payload, WearCompanionContract.COMMAND_STOP_TURN)
                        stopNativeChatTurn()
                        ack = successAck(command, appContext.getString(R.string.wear_bridge_command_accepted))
                    }
                    WearCompanionContract.MESSAGE_SEND_TAMA_TEXT -> {
                        val command = WearCompanionContract.json.decodeFromString<WearSendTamaTextCommand>(payload)
                        if (markRequestProcessed(command.requestId)) {
                            publishCommandAck(
                                successAck(command.requestId, "send_tama_message", appContext.getString(R.string.wear_bridge_tama_sent))
                            )
                            sendTamaText(command)
                        }
                    }
                    WearCompanionContract.MESSAGE_SEND_AUDIO_TURN,
                    WearCompanionContract.MESSAGE_SEND_TAMA_AUDIO -> {
                        publishTurnState(
                            WearTurnState(
                                requestId = UUID.randomUUID().toString(),
                                target = "audio",
                                targetId = "audio",
                                status = "error",
                                error = appContext.getString(R.string.wear_bridge_audio_channel_pending),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    WearCompanionContract.MESSAGE_LLAMA_SERVER_START -> {
                        val command = decodeSimpleCommand(payload, WearCompanionContract.COMMAND_START_SERVER)
                        if (markRequestProcessed(command.requestId)) {
                            ack = pendingAck(command, appContext.getString(R.string.wear_bridge_server_starting))
                            publishCommandAck(ack!!)
                            startLlamaCppServer().getOrThrow()
                            ack = successAck(command, appContext.getString(R.string.wear_bridge_server_start_sent))
                        }
                    }
                    WearCompanionContract.MESSAGE_LLAMA_SERVER_STOP -> {
                        val command = decodeSimpleCommand(payload, WearCompanionContract.COMMAND_STOP_SERVER)
                        if (markRequestProcessed(command.requestId)) {
                            ack = pendingAck(command, appContext.getString(R.string.wear_bridge_server_stopping))
                            publishCommandAck(ack!!)
                            stopLlamaCppServer().getOrThrow()
                            ack = successAck(command, appContext.getString(R.string.wear_bridge_server_stop_sent))
                        }
                    }
                }
            }.onSuccess {
                ack?.let { publishCommandAck(it) }
            }.onFailure { error ->
                DebugLog.log("[WEAR] command $path failed: ${error.message}")
                ack?.let {
                    publishCommandAck(
                        errorAck(
                            it.requestId,
                            it.command,
                            error.message ?: appContext.getString(R.string.wear_bridge_command_failed)
                        )
                    )
                }
                publishHomeSnapshot(error.message)
            }
        }
    }

    fun handleChannel(context: Context, channel: ChannelClient.Channel) {
        ensureStarted(context)
        val path = channel.path
        when {
            path.startsWith(WearCompanionContract.CHANNEL_AUDIO_CHAT_PREFIX) -> handleChatAudioChannel(channel, path)
            path.startsWith(WearCompanionContract.CHANNEL_AUDIO_TAMA_PREFIX) -> handleTamaAudioChannel(channel, path)
        }
    }

    private suspend fun publishAllSnapshots() {
        publishHomeSnapshot()
        publishServersSnapshot()
        publishChatsSnapshot()
        publishTamaSnapshot()
        publishTamaMessages()
    }

    private fun publishData(path: String, json: String): Task<DataItem> {
        val size = json.toByteArray(Charsets.UTF_8).size
        if (size > WearCompanionContract.MAX_DATA_ITEM_BYTES) {
            val message = "Wear snapshot $path is too large: $size bytes"
            DebugLog.log("[WEAR] $message")
            return Tasks.forException(IllegalStateException(message))
        }
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putString(WearCompanionContract.KEY_JSON, json)
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        return Wearable.getDataClient(appContext).putDataItem(request)
            .addOnFailureListener { error ->
                DebugLog.log("[WEAR] putDataItem $path failed: ${error.message}")
            }
    }

    private fun publishDataBlocking(path: String, json: String) {
        Tasks.await(publishData(path, json))
    }

    private fun publishCommandAck(ack: WearCommandAck) {
        publishData(
            WearCompanionContract.commandAckPath(ack.requestId),
            WearCompanionContract.json.encodeToString(WearCommandAck.serializer(), ack)
        )
    }

    private fun decodeSimpleCommand(payload: String, fallbackCommand: String): WearSimpleCommand =
        runCatching {
            WearCompanionContract.json.decodeFromString(WearSimpleCommand.serializer(), payload)
        }.getOrElse {
            WearSimpleCommand(
                requestId = UUID.randomUUID().toString(),
                command = fallbackCommand,
                createdAt = System.currentTimeMillis()
            )
        }

    private fun pendingAck(command: WearSimpleCommand, message: String): WearCommandAck =
        WearCommandAck(
            requestId = command.requestId,
            command = command.command,
            status = "pending",
            localizedMessage = message,
            updatedAt = System.currentTimeMillis()
        )

    private fun successAck(command: WearSimpleCommand, message: String): WearCommandAck =
        successAck(command.requestId, command.command, message)

    private fun successAck(requestId: String, command: String, message: String): WearCommandAck =
        WearCommandAck(
            requestId = requestId,
            command = command,
            status = "success",
            localizedMessage = message,
            updatedAt = System.currentTimeMillis()
        )

    private fun bridgeResponse(request: WearBridgeRequest, status: String, message: String): WearBridgeResponse =
        WearBridgeResponse(
            requestId = request.requestId,
            command = request.command,
            status = status,
            localizedMessage = message,
            bridgeState = LlamaService.state.value.toWearState().state,
            phoneVersionCode = BuildConfig.VERSION_CODE,
            snapshotRevision = snapshotRevision.get(),
            updatedAt = System.currentTimeMillis()
        )

    private fun errorAck(requestId: String, command: String, message: String): WearCommandAck =
        WearCommandAck(
            requestId = requestId,
            command = command,
            status = "error",
            localizedMessage = message,
            updatedAt = System.currentTimeMillis()
        )

    private fun markRequestProcessed(requestId: String): Boolean {
        if (requestId.isBlank()) return true
        return processedRequests.add(requestId)
    }

    private suspend fun publishHomeSnapshot(error: String? = null) {
        val revision = snapshotRevision.incrementAndGet()
        val snapshot = WearHomeSnapshot(
            llamaServerState = LlamaService.state.value.toWearState(),
            chatsAvailable = repository.allChats.first().isNotEmpty(),
            tamaAvailable = tamaDatabase.tamaDao().getActivePet() != null,
            protocolVersion = WearCompanionContract.PROTOCOL_VERSION,
            phoneVersionCode = BuildConfig.VERSION_CODE,
            phoneVersionName = BuildConfig.VERSION_NAME,
            snapshotRevision = revision,
            updatedAt = System.currentTimeMillis(),
            error = error
        )
        publishDataBlocking(
            WearCompanionContract.PATH_HOME_SNAPSHOT,
            WearCompanionContract.json.encodeToString(WearHomeSnapshot.serializer(), snapshot)
        )
    }

    private suspend fun publishServersSnapshot() {
        val selected = selectedServerId()
        val servers = repository.allServers.first().map { server ->
            WearServerSummary(
                id = server.id,
                name = server.name,
                engine = server.normalizedEngine(),
                endpoint = if (server.isLiteRtEngine()) server.normalizedEngine() else server.baseUrl(),
                modelName = server.modelName,
                supportsAudio = server.supportsAudio
            )
        }
        val snapshot = WearServersSnapshot(
            servers = servers,
            selectedServerId = selected ?: servers.firstOrNull()?.id,
            updatedAt = System.currentTimeMillis()
        )
        publishDataBlocking(
            WearCompanionContract.PATH_SERVERS_SNAPSHOT,
            WearCompanionContract.json.encodeToString(WearServersSnapshot.serializer(), snapshot)
        )
    }

    private suspend fun publishChatsSnapshot() {
        val folders = repository.allChatFolders.first().map {
            WearChatFolder(id = it.id, name = it.name)
        }
        val chats = repository.allChats.first().take(MAX_WEAR_CHATS).map { chat ->
            val preview = repository.getMessagesOnce(chat.id).lastOrNull { !it.isError }?.content.orEmpty()
            WearChatSummary(
                id = chat.id,
                title = chat.title,
                folderId = chat.folderId,
                lastModified = chat.lastModified,
                pinned = chat.pinnedToAiHub,
                pinnedServerId = chat.pinnedServerId,
                preview = preview.wearLimit(MAX_WEAR_PREVIEW_CHARS)
            )
        }
        val snapshot = WearChatsSnapshot(
            folders = listOf(WearChatFolder(id = null, name = appContext.getString(R.string.wear_folder_unfiled), isVirtual = true)) + folders,
            chats = chats,
            updatedAt = System.currentTimeMillis()
        )
        publishDataBlocking(
            WearCompanionContract.PATH_CHATS_SNAPSHOT,
            WearCompanionContract.json.encodeToString(WearChatsSnapshot.serializer(), snapshot)
        )
    }

    private suspend fun publishChatMessages(chatId: Long) {
        val snapshot = WearMessagesSnapshot(
            ownerId = chatId.toString(),
            messages = repository.getMessagesOnce(chatId).takeLast(MAX_WEAR_MESSAGES).map { it.toWearMessage() },
            updatedAt = System.currentTimeMillis()
        )
        publishDataBlocking(
            WearCompanionContract.chatMessagesPath(chatId),
            WearCompanionContract.json.encodeToString(WearMessagesSnapshot.serializer(), snapshot)
        )
    }

    private suspend fun publishTamaSnapshot() {
        val pet = tamaDatabase.tamaDao().getActivePet()?.let(PetMapper::toDomain)
        val snapshot = pet?.toWearTamaSnapshot(appContext) ?: WearTamaPetSnapshot(updatedAt = System.currentTimeMillis())
        publishDataBlocking(
            WearCompanionContract.PATH_TAMA_ACTIVE_PET,
            WearCompanionContract.json.encodeToString(WearTamaPetSnapshot.serializer(), snapshot)
        )
    }

    private suspend fun publishTamaMessages() {
        val petId = tamaDatabase.tamaDao().getActivePet()?.id
        if (petId == null) {
            publishDataBlocking(
                WearCompanionContract.PATH_TAMA_MESSAGES,
                WearCompanionContract.json.encodeToString(
                    WearMessagesSnapshot.serializer(),
                    WearMessagesSnapshot(ownerId = "tama", updatedAt = System.currentTimeMillis())
                )
            )
            return
        }
        val snapshot = WearMessagesSnapshot(
            ownerId = petId,
            messages = tamaDatabase.tamaDao().getChatHistory(petId).takeLast(MAX_WEAR_MESSAGES).map { it.toWearMessage() },
            updatedAt = System.currentTimeMillis()
        )
        publishDataBlocking(
            WearCompanionContract.PATH_TAMA_MESSAGES,
            WearCompanionContract.json.encodeToString(WearMessagesSnapshot.serializer(), snapshot)
        )
    }

    private suspend fun sendNativeChatTurn(command: WearSendChatTextCommand, replyNodeId: String?) {
        sendNativeChatTurn(
            requestId = command.requestId,
            chatId = command.chatId,
            serverId = command.serverId,
            text = command.text,
            audioPath = null,
            replyNodeId = replyNodeId
        )
    }

    private suspend fun sendNativeChatTurn(
        requestId: String,
        chatId: Long,
        serverId: Long,
        text: String,
        audioPath: String?,
        replyNodeId: String?
    ) {
        publishTurnState(
            WearTurnState(
                requestId = requestId,
                target = "chat",
                targetId = chatId.toString(),
                status = "sending",
                updatedAt = System.currentTimeMillis()
            )
        )
        val intent = Intent(appContext, LlamaClientService::class.java).apply {
            action = LlamaClientService.ACTION_GENERATE
            putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
            putExtra(LlamaClientService.EXTRA_SERVER_ID, serverId)
            putExtra(LlamaClientService.EXTRA_USER_MESSAGE, text)
            audioPath?.let { putExtra(LlamaClientService.EXTRA_AUDIO_PATH, it) }
            putExtra(LlamaClientService.EXTRA_FORCE_ASSISTANT_TTS, true)
        }
        appContext.startForegroundService(intent)
        publishTurnState(
            WearTurnState(
                requestId = requestId,
                target = "chat",
                targetId = chatId.toString(),
                status = "thinking",
                updatedAt = System.currentTimeMillis()
            )
        )
        val terminal = LlamaClientService.generationState
            .filter { state ->
                when (state) {
                    is LlamaClientService.GenerationState.Completed -> state.chatId == chatId
                    is LlamaClientService.GenerationState.Error -> state.chatId == chatId || state.chatId == -1L
                    else -> false
                }
            }
            .first()
        when (terminal) {
            is LlamaClientService.GenerationState.Completed -> {
                val assistant = repository.getMessagesOnce(chatId).lastOrNull { it.role == "assistant" }
                publishTurnState(
                    WearTurnState(
                        requestId = requestId,
                        target = "chat",
                        targetId = chatId.toString(),
                        status = "complete",
                        content = terminal.content,
                        thinking = terminal.thinking,
                        audioPath = assistant?.audioPath,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                assistant?.audioPath?.let { path ->
                    replyNodeId?.let { nodeId -> sendReplyAudio(nodeId, requestId, path) }
                }
                publishChatMessages(chatId)
                publishChatsSnapshot()
            }
            is LlamaClientService.GenerationState.Error -> publishTurnState(
                WearTurnState(
                    requestId = requestId,
                    target = "chat",
                    targetId = chatId.toString(),
                    status = "error",
                    error = terminal.message,
                    updatedAt = System.currentTimeMillis()
                )
            )
            else -> Unit
        }
    }

    private fun stopNativeChatTurn() {
        appContext.startService(Intent(appContext, LlamaClientService::class.java).apply {
            action = LlamaClientService.ACTION_STOP
        })
    }

    private suspend fun sendTamaText(command: WearSendTamaTextCommand) {
        sendTamaTurn(
            requestId = command.requestId,
            petId = command.petId,
            text = command.text,
            audioPath = null,
            audioDurationMs = null
        )
    }

    private suspend fun sendTamaTurn(
        requestId: String,
        petId: String,
        text: String,
        audioPath: String?,
        audioDurationMs: Long?
    ) {
        val pet = tamaDatabase.tamaDao().getActivePet()?.let(PetMapper::toDomain)
            ?: throw IllegalStateException(appContext.getString(R.string.wear_tama_no_pet))
        if (pet.id != petId) {
            publishTamaSnapshot()
            return
        }
        publishTurnState(
            WearTurnState(
                requestId = requestId,
                target = "tama",
                targetId = pet.id,
                status = "thinking",
                updatedAt = System.currentTimeMillis()
            )
        )
        tamaAgentService.sendMessage(
            pet = pet,
            userContent = text,
            audioPath = audioPath,
            audioDurationMs = audioDurationMs
        ) { chunk ->
            publishTurnState(
                WearTurnState(
                    requestId = requestId,
                    target = "tama",
                    targetId = pet.id,
                    status = "thinking",
                    content = chunk,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        delay(TAMA_REPLY_SETTLE_MS)
        publishTamaMessages()
        val lastAssistant = tamaDatabase.tamaDao().getChatHistory(pet.id).lastOrNull { it.role == "assistant" }
        publishTurnState(
            WearTurnState(
                requestId = requestId,
                target = "tama",
                targetId = pet.id,
                status = "complete",
                content = lastAssistant?.content.orEmpty(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun handleChatAudioChannel(channel: ChannelClient.Channel, path: String) {
        val parts = path.removePrefix(WearCompanionContract.CHANNEL_AUDIO_CHAT_PREFIX).split("/")
        val requestId = parts.getOrNull(0).orEmpty()
        val chatId = parts.getOrNull(1)?.toLongOrNull()
        val serverId = parts.getOrNull(2)?.toLongOrNull()
        if (requestId.isBlank() || chatId == null || serverId == null) {
            return
        }
        receiveAudioChannel(channel, requestId) { audioFile ->
            sendNativeChatTurn(
                requestId = requestId,
                chatId = chatId,
                serverId = serverId,
                text = "",
                audioPath = audioFile.absolutePath,
                replyNodeId = channel.nodeId
            )
        }
    }

    private fun sendReplyAudio(nodeId: String, requestId: String, audioPath: String) {
        val file = File(audioPath)
        if (!file.exists()) return
        val channelPath = WearCompanionContract.replyAudioChannelPath(requestId)
        Wearable.getChannelClient(appContext).openChannel(nodeId, channelPath)
            .addOnSuccessListener { channel ->
                Wearable.getChannelClient(appContext).sendFile(channel, Uri.fromFile(file))
                    .addOnSuccessListener {
                        Wearable.getChannelClient(appContext).close(channel)
                    }
                    .addOnFailureListener {
                        Wearable.getChannelClient(appContext).close(channel)
                    }
            }
    }

    private fun handleTamaAudioChannel(channel: ChannelClient.Channel, path: String) {
        val parts = path.removePrefix(WearCompanionContract.CHANNEL_AUDIO_TAMA_PREFIX).split("/")
        val requestId = parts.getOrNull(0).orEmpty()
        val petId = parts.getOrNull(1)?.let(Uri::decode).orEmpty()
        val durationMs = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        if (requestId.isBlank() || petId.isBlank()) {
            return
        }
        receiveAudioChannel(channel, requestId) { audioFile ->
            sendTamaTurn(
                requestId = requestId,
                petId = petId,
                text = "",
                audioPath = audioFile.absolutePath,
                audioDurationMs = durationMs.takeIf { it > 0L }
            )
        }
    }

    private fun receiveAudioChannel(
        channel: ChannelClient.Channel,
        requestId: String,
        onReceived: suspend (File) -> Unit
    ) {
        Wearable.getChannelClient(appContext).getInputStream(channel)
            .addOnSuccessListener { inputStream ->
                scope.launch {
                    runCatching {
                        val audioDir = File(appContext.filesDir, "wear_audio").apply { mkdirs() }
                        val audioFile = File(audioDir, "wear_${requestId}_${System.currentTimeMillis()}.m4a")
                        inputStream.use { input ->
                            audioFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        Wearable.getChannelClient(appContext).close(channel)
                        onReceived(audioFile)
                    }.onFailure { error ->
                        Wearable.getChannelClient(appContext).close(channel)
                        publishTurnState(
                            WearTurnState(
                                requestId = requestId,
                                target = "audio",
                                targetId = requestId,
                                status = "error",
                                error = error.message,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
            .addOnFailureListener { error ->
                publishTurnState(
                    WearTurnState(
                        requestId = requestId,
                        target = "audio",
                        targetId = requestId,
                        status = "error",
                        error = error.message,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
    }

    private fun publishTurnState(state: WearTurnState) {
        publishData(
            WearCompanionContract.activeTurnPath(state.requestId),
            WearCompanionContract.json.encodeToString(WearTurnState.serializer(), state)
        )
    }

    private fun startLlamaCppServer(): Result<Unit> =
        settingsRepo.selectedModelPath.value
            ?.takeIf { it.isNotBlank() }
            ?.let { LlamaServerLauncher.start(appContext, it) }
            ?: Result.failure(IllegalStateException(appContext.getString(R.string.wear_bridge_no_llama_model)))

    private fun stopLlamaCppServer(): Result<Unit> =
        LlamaServerLauncher.stop(appContext)

    private fun selectedServerPrefs() =
        appContext.getSharedPreferences("wear_companion", Context.MODE_PRIVATE)

    private suspend fun selectedServerId(): Long? {
        val stored = selectedServerPrefs().getLong(KEY_SELECTED_SERVER_ID, -1L).takeIf { it > 0L }
        if (stored != null) return stored
        return appDatabase.llamaServerDao().getLastUsedServer()?.id
    }

    private fun ServerState.toWearState(): WearLlamaServerState = when (this) {
        ServerState.Stopped -> WearLlamaServerState("stopped", appContext.getString(R.string.status_stopped))
        ServerState.Starting -> WearLlamaServerState("starting", appContext.getString(R.string.dashboard_starting))
        is ServerState.Loading -> WearLlamaServerState("loading", status, progress = progress)
        is ServerState.Running -> WearLlamaServerState("running", appContext.getString(R.string.status_running), port = port)
        is ServerState.Error -> WearLlamaServerState("error", appContext.getString(R.string.status_error), error = message)
    }

    private fun LlamaMessageEntity.toWearMessage(): WearMessageSummary =
        WearMessageSummary(
            id = id.toString(),
            role = role,
            content = content.wearLimit(MAX_WEAR_MESSAGE_CHARS),
            timestamp = timestamp,
            hasAudio = !audioPath.isNullOrBlank(),
            audioPath = audioPath,
            thinking = thinking?.wearLimit(MAX_WEAR_MESSAGE_CHARS),
            error = if (isError) content.wearLimit(MAX_WEAR_MESSAGE_CHARS) else null
        )

    private fun TamaChatMessageEntity.toWearMessage(): WearMessageSummary =
        WearMessageSummary(
            id = id,
            role = role,
            content = (transcribedText?.takeIf { role == "user" && it.isNotBlank() } ?: content)
                .wearLimit(MAX_WEAR_MESSAGE_CHARS),
            timestamp = timestamp,
            hasAudio = !audioPath.isNullOrBlank(),
            audioPath = audioPath,
            thinking = thinking?.wearLimit(MAX_WEAR_MESSAGE_CHARS),
            error = transcriptionError?.wearLimit(MAX_WEAR_MESSAGE_CHARS)
        )

    private fun String.wearLimit(maxChars: Int): String =
        if (length <= maxChars) this else take(maxChars).trimEnd() + "..."

    private fun Throwable.wearBridgeMessage(): String {
        val detail = message.orEmpty()
        val type = javaClass.simpleName.ifBlank { "Error" }
        return if (detail.isBlank()) {
            appContext.getString(R.string.wear_bridge_command_failed_with_type, type)
        } else {
            appContext.getString(R.string.wear_bridge_command_failed_with_detail, type, detail)
        }
    }

    private fun TamaPet.toWearTamaSnapshot(context: Context): WearTamaPetSnapshot {
        val activity = tamaActivityKey()
        val spriteState = mapPetActionToSpriteState(activity, isSleeping)
        val sprite = resolvePetSpriteAssetPath(
            speciesLine = PetSpeciesLine.fromSpeciesId(species, genetics.bodyStyle),
            stage = stage,
            state = spriteState,
            frameIndex = 0
        )
        return WearTamaPetSnapshot(
            hasPet = true,
            petId = id,
            name = name,
            species = species,
            stage = stage.name,
            mood = mood.name,
            moodLabel = if (isMad) context.applicationContext.getString(R.string.widget_tama_mood_mad) else mood.emoji,
            activity = activity,
            activityLabel = tamaActivityLabel(context),
            locationId = currentLocationId,
            backgroundAssetPath = tamaBackgroundAsset(),
            spriteAssetPath = sprite,
            hunger = stats.hunger.toInt().coerceIn(0, 100),
            happiness = stats.happiness.toInt().coerceIn(0, 100),
            health = stats.health.toInt().coerceIn(0, 100),
            energy = stats.energy.toInt().coerceIn(0, 100),
            hygiene = stats.hygiene.toInt().coerceIn(0, 100),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun TamaPet.tamaActivityKey(): String = when {
        isSleeping -> "sleeping"
        currentActivity == ActivityType.WORKING -> "working"
        currentActivity == ActivityType.STUDYING -> "studying"
        currentActivity == ActivityType.TRAINING -> "training"
        currentActivity == ActivityType.RELAXING -> "relaxing"
        else -> "idle"
    }

    private fun TamaPet.tamaActivityLabel(context: Context): String = when (tamaActivityKey()) {
        "sleeping" -> context.applicationContext.getString(R.string.wear_tama_activity_sleeping)
        "working" -> context.applicationContext.getString(R.string.wear_tama_activity_working)
        "studying" -> context.applicationContext.getString(R.string.wear_tama_activity_studying)
        "training" -> context.applicationContext.getString(R.string.wear_tama_activity_training)
        "relaxing" -> context.applicationContext.getString(R.string.wear_tama_activity_relaxing)
        else -> context.applicationContext.getString(R.string.wear_tama_activity_idle)
    }

    private fun TamaPet.tamaBackgroundAsset(): String {
        if (isSleeping) return "tama/backgrounds/bedroom.png"
        return when (currentActivity) {
            ActivityType.WORKING -> TamaWorkCatalog.jobById(currentWorkJobId)?.backgroundAssetPath
                ?: "tama/backgrounds/workplace.png"
            ActivityType.STUDYING -> "tama/backgrounds/classroom.png"
            ActivityType.TRAINING -> TamaTrainingCatalog.tierById(currentWorkJobId)?.backgroundAssetPath
                ?: "tama/backgrounds/boxing_ring.png"
            ActivityType.RELAXING -> "tama/backgrounds/park.png"
            ActivityType.NONE -> when (wearLocationType(currentLocationId)) {
                LocationType.HOME -> TamaRoomCatalog.homeRoomAssetPath(homeRoomId)
                LocationType.SCHOOL -> "tama/backgrounds/classroom.png"
                LocationType.WORKPLACE -> "tama/backgrounds/workplace.png"
                LocationType.SHOP -> "tama/backgrounds/shop.png"
                LocationType.ARCADE -> "tama/backgrounds/arcade_location.png"
                LocationType.PARK -> "tama/backgrounds/park.png"
                LocationType.HOSPITAL -> "tama/backgrounds/hospital.png"
                LocationType.ALCHEMIST -> "tama/backgrounds/alchemist.png"
                LocationType.FARM -> "tama/backgrounds/farm.png"
                LocationType.DUNGEON -> "tama/backgrounds/dungeon.png"
                LocationType.BOXING_RING -> "tama/backgrounds/boxing_ring.png"
                LocationType.ADVENTURE_GATE -> "tama/backgrounds/adventure_gate.png"
            }
        }
    }

    private fun wearLocationType(locationId: String?): LocationType {
        val id = locationId.orEmpty().lowercase()
        return when {
            id.contains("school") || id.contains("class") -> LocationType.SCHOOL
            id.contains("work") || id.contains("office") -> LocationType.WORKPLACE
            id.contains("shop") -> LocationType.SHOP
            id.contains("arcade") -> LocationType.ARCADE
            id.contains("park") -> LocationType.PARK
            id.contains("hospital") -> LocationType.HOSPITAL
            id.contains("alchemist") -> LocationType.ALCHEMIST
            id.contains("farm") -> LocationType.FARM
            id.contains("dungeon") -> LocationType.DUNGEON
            id.contains("boxing") || id.contains("training") -> LocationType.BOXING_RING
            id.contains("adventure_gate") || id.contains("gate") -> LocationType.ADVENTURE_GATE
            else -> LocationType.HOME
        }
    }
}
