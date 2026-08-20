package com.example.llamadroid.wear

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.LiveTranslatorTurnEntity
import com.example.llamadroid.data.db.LiveTranslatorTemplateEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.data.db.OrganizerDao
import com.example.llamadroid.data.db.OrganizerAlarmEntity
import com.example.llamadroid.data.db.OrganizerEventEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.data.repository.LlamaServerCardSnapshot
import com.example.llamadroid.onnx.OnnxTtsRequest
import com.example.llamadroid.onnx.SupertonicTtsPipeline
import com.example.llamadroid.service.LiveTranslatorService
import com.example.llamadroid.service.LlamaClientService
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaServerSessionSnapshot
import com.example.llamadroid.service.LlamaServerSessionStateStore
import com.example.llamadroid.service.LlamaServerSessionStatus
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.OrganizerAlarmScheduler
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.EventType
import com.example.llamadroid.tama.data.FarmTradeItemCatalog
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.LocationType
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.PlantedCrop
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TamaRoomCatalog
import com.example.llamadroid.tama.data.TamaTrainingCatalog
import com.example.llamadroid.tama.data.TamaWorkCatalog
import com.example.llamadroid.tama.data.TileStatus
import com.example.llamadroid.tama.data.inventoryItemDisplayName
import com.example.llamadroid.tama.data.mapPetActionToSpriteState
import com.example.llamadroid.tama.data.resolvePetSpriteAssetPath
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.db.TamaChatMessageEntity
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.widget.OrganizerCalendarWidgetProvider
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableStatusCodes
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.json.JSONObject

object PhoneWearGateway {
    private const val WEAR_QUICK_CHAT_PREFS = "adt_wear_quick_chat_v1"
    private const val WEAR_QUICK_CHAT_SELECTED_SERVER_ID = "selected_server_id"
    private const val WEAR_QUICK_CHAT_SYSTEM_PROMPT = "system_prompt"
    private const val WEAR_QUICK_CHAT_ALLOWED_TOOLS = "allowed_tools"
    private const val WEAR_QUICK_CHAT_AUTO_START = "auto_start_server"
    private const val WEAR_QUICK_CHAT_AUTO_TTS = "auto_play_tts"
    private const val WEAR_QUICK_CHAT_RETAIN_FINAL = "retain_final_result"
    private const val WEAR_TOOL_CALENDAR = "calendar"
    private const val WEAR_TOOL_ALARMS = "alarms"
    private const val WEAR_TOOL_NOTES = "notes"
    private const val WEAR_TOOL_PINNED_NOTE = "pinned_note"
    private const val WEAR_TOOL_WEB = "web_search"
    private const val WEAR_TOOL_IMAGES = "image_generation"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val revision = AtomicLong(0L)
    private val processedGenerations = Collections.synchronizedSet(mutableSetOf<String>())
    private val messageListeners = ConcurrentHashMap<String, Long>()

    private lateinit var appContext: Context
    private lateinit var repository: LlamaRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var tamaDatabase: TamaDatabase
    private lateinit var tamaAgentService: TamaAgentService
    private lateinit var publisher: WearStatePublisher
    private lateinit var deltaEmitter: WearDeltaEmitter
    private lateinit var router: WearRequestRouter
    private lateinit var serverController: LlamaServerController
    @Volatile private var started = false
    @Volatile private var localNodeId = ""

    private val dataListener = DataClient.OnDataChangedListener { events ->
        events.forEach { event ->
            handleDataItem(appContext, event.dataItem)
        }
    }

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { capability ->
        handleCapabilityChanged(appContext, capability)
    }

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            appContext = context.applicationContext
            val appDatabase = AppDatabase.getDatabase(appContext)
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
            publisher = WearStatePublisher(appContext, revision)
            deltaEmitter = WearDeltaEmitter(appContext)
            serverController = LlamaServerController(appContext, settingsRepo, publisher)
            router = WearRequestRouter(
                appContext = appContext,
                repository = repository,
                tamaDatabase = tamaDatabase,
                tamaAgentService = tamaAgentService,
                publisher = publisher,
                deltaEmitter = deltaEmitter,
                serverController = serverController,
                processedGenerations = processedGenerations,
                messageListeners = messageListeners
            )
            Wearable.getDataClient(appContext).addListener(
                dataListener,
                Uri.parse("wear://*${AdtWearProtocol.PREFIX}"),
                DataClient.FILTER_PREFIX
            )
            Wearable.getCapabilityClient(appContext)
                .addListener(capabilityListener, AdtWearProtocol.WATCH_CAPABILITY)
            advertiseCapability()
            started = true
            scope.launch {
                localNodeId = runCatching { Tasks.await(Wearable.getNodeClient(appContext).localNode).id }
                    .getOrDefault("")
                publisher.publishServer(serverController.currentSnapshot())
                publishAllState()
            }
            scope.launch {
                LlamaService.state.collectLatest {
                    publisher.publishServer(serverController.currentSnapshot())
                }
            }
            scope.launch {
                tamaDatabase.tamaDao().observeActivePet().collectLatest {
                    publisher.publishPet(currentPetSnapshot())
                    publisher.publishTamaMessages(currentTamaMessages())
                }
            }
            scope.launch {
                LiveTranslatorService.state.collectLatest {
                    publisher.publishTranslatorState(currentTranslatorState())
                    if (it.sessionId > 0L) {
                        publisher.publishTranslatorTurns(currentTranslatorTurns(it.sessionId))
                    }
                }
            }
            scope.launch {
                ActiveTaskRepository.observeSnapshot(appContext).collectLatest {
                    publisher.publishActiveTasks(it)
                }
            }
            DebugLog.log("[WEAR] phone gateway started package=${appContext.packageName} version=${BuildConfig.VERSION_CODE}/${BuildConfig.VERSION_NAME}")
            Log.i("ADT-WEAR-DISCOVERY", "phone gateway started capability=${AdtWearProtocol.PHONE_CAPABILITY}")
        }
    }

    fun handleRequest(context: Context, nodeId: String, path: String, request: ByteArray): Task<ByteArray> {
        start(context)
        val completion = TaskCompletionSource<ByteArray>()
        scope.launch {
            val bytes = router.route(nodeId, path, request)
            completion.setResult(bytes)
        }
        return completion.task
    }

    fun handleMessage(context: Context, messageEvent: MessageEvent) {
        start(context)
        if (messageEvent.path.startsWith(AdtWearProtocol.PREFIX)) {
            Log.i("ADT-WEAR-RPC", "best-effort message path=${messageEvent.path} bytes=${messageEvent.data.size}")
        }
    }

    fun handleDataItem(context: Context, dataItem: DataItem) {
        start(context)
        val path = dataItem.uri.path.orEmpty()
        if (!path.startsWith(AdtWearProtocol.VOICE_UPLOAD)) return
        scope.launch {
            runCatching { router.handleVoiceUpload(dataItem) }
                .onFailure { error -> Log.w("ADT-WEAR-ASSET", "voice upload failed path=$path error=${error.javaClass.simpleName}:${error.message}") }
        }
    }

    fun handleCapabilityChanged(context: Context, capabilityInfo: CapabilityInfo) {
        start(context)
        Log.i(
            "ADT-WEAR-DISCOVERY",
            "capability=${capabilityInfo.name} nodes=${capabilityInfo.nodes.size} nearby=${capabilityInfo.nodes.count { it.isNearby }}"
        )
    }

    fun publishAllState() {
        scope.launch {
            runCatching {
                publisher.publishServer(serverController.currentSnapshot())
                publisher.publishPet(currentPetSnapshot())
                publisher.publishTamaMessages(currentTamaMessages())
                publisher.publishCapabilities(currentCapabilities())
                publisher.publishActiveTasks(ActiveTaskRepository.currentSnapshot(appContext))
                publisher.publishStats(buildWearStatsSnapshot(appContext, StatsRequest(meta = RpcMeta(UUID.randomUUID().toString(), createdAtEpochMs = System.currentTimeMillis()))))
            }.onFailure { error ->
                Log.w("ADT-WEAR-DATA", "publish all failed ${error.javaClass.simpleName}:${error.message}")
            }
        }
    }

    fun publishPinnedOrganizerNote(context: Context) {
        start(context)
        scope.launch {
            runCatching {
                val note = PinnedOrganizerNoteStore.get(appContext)
                    ?.let { AppDatabase.getDatabase(appContext).noteDao().getNoteById(it) }
                if (note == null) {
                    PinnedOrganizerNoteStore.set(appContext, null)
                }
                val revisioned = Revisioned(
                    revision = revision.incrementAndGet(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                    sourceDeviceId = localNodeId
                )
                publisher.publishPinnedNote(
                    OrganizerPinnedNoteResult(note?.toWearNoteDetail(revisioned))
                )
            }.onFailure { error ->
                Log.w("ADT-WEAR-DATA", "publish pinned note failed ${error.javaClass.simpleName}:${error.message}")
            }
        }
    }

    /**
     * Request ids we ourselves put in front of the user via the confirmation
     * notification, and which are therefore allowed to start the server.
     *
     * [WearServerStartConfirmationActivity] is exported and BROWSABLE, because the
     * watch reaches it through `RemoteActivityHelper.startRemoteActivity`. That
     * also means any app, or any web page, can launch it with an arbitrary
     * `requestId`. Only ids minted by this process for a genuinely blocked start
     * may proceed; anything else is rejected without touching the server.
     */
    private val pendingServerConfirmations = WearConfirmationRegistry()

    internal fun markPendingServerConfirmation(requestId: String) {
        pendingServerConfirmations.mark(requestId)
    }

    /**
     * Returns null when [requestId] is not a live confirmation we are waiting on,
     * so the caller can report it instead of silently starting a network-facing
     * server on behalf of an untrusted launcher.
     */
    suspend fun confirmServerStart(context: Context, requestId: String): ServerCommandResult? {
        // Validate before start(context): an unrecognised id must not even spin up
        // the gateway.
        if (!pendingServerConfirmations.consume(requestId)) {
            Log.w("ADT-WEAR-DISCOVERY", "rejected server-start confirmation for unknown request id")
            return null
        }
        start(context)
        return serverController.confirmPendingStart(requestId)
    }

    private fun advertiseCapability() {
        Wearable.getCapabilityClient(appContext).addLocalCapability(AdtWearProtocol.PHONE_CAPABILITY)
            .addOnSuccessListener { Log.i("ADT-WEAR-DISCOVERY", "advertised ${AdtWearProtocol.PHONE_CAPABILITY}") }
            .addOnFailureListener { error ->
                if (error is ApiException && error.statusCode == WearableStatusCodes.DUPLICATE_CAPABILITY) {
                    Log.i("ADT-WEAR-DISCOVERY", "capability already advertised ${AdtWearProtocol.PHONE_CAPABILITY}")
                } else {
                    Log.w("ADT-WEAR-DISCOVERY", "capability advertise failed ${error.javaClass.simpleName}:${error.message}")
                }
            }
    }

    internal fun diagnostics(): DiagnosticsSnapshot {
        val connectedNodes = runCatching { Tasks.await(Wearable.getNodeClient(appContext).connectedNodes) }
            .getOrDefault(emptyList())
        val watchCapability = runCatching {
            Tasks.await(
                Wearable.getCapabilityClient(appContext)
                    .getCapability(AdtWearProtocol.WATCH_CAPABILITY, CapabilityClient.FILTER_ALL)
            )
        }.getOrNull()
        return DiagnosticsSnapshot(
            packageName = appContext.packageName,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            certificateSha256Short = signingSha256Short(appContext),
            wearableApiAvailable = GoogleApiAvailabilityLight.getInstance()
                .isGooglePlayServicesAvailable(appContext) == com.google.android.gms.common.ConnectionResult.SUCCESS,
            localNodeId = localNodeId,
            connectedNodes = connectedNodes.size,
            reciprocalCapabilityNodes = watchCapability?.nodes?.size ?: 0,
            capabilityNames = listOfNotNull(watchCapability?.name),
            lastDataItemPath = publisher.lastDataItemPath,
            lastDataItemRevision = publisher.lastDataItemRevision,
            lastRpcError = publisher.lastRpcError
        )
    }

    private suspend fun currentPetSnapshot(): PetSnapshot {
        val source = sourceDeviceId()
        val pet = tamaDatabase.tamaDao().getActivePet()?.let(PetMapper::toDomain)
        return pet?.toPetSnapshot(appContext, source, revision.incrementAndGet())
            ?: PetSnapshot(
                revisioned = Revisioned(revision.incrementAndGet(), System.currentTimeMillis(), source),
                hasPet = false
            )
    }

    private suspend fun currentTamaMessages(limit: Int = 30): TamaMessagePage {
        val petId = tamaDatabase.tamaDao().getActivePet()?.id
        val messages = petId?.let { tamaDatabase.tamaDao().getChatHistory(it).takeLast(limit).map { msg -> msg.toWearMessage() } }
            ?: emptyList()
        return TamaMessagePage(
            revisioned = Revisioned(revision.incrementAndGet(), System.currentTimeMillis(), sourceDeviceId()),
            petId = petId,
            messages = messages,
            limit = limit
        )
    }

    private suspend fun currentTranslatorState(): TranslatorStateSnapshot {
        val state = LiveTranslatorService.state.value
        val template = state.templateId.takeIf { it > 0L }
            ?.let { AppDatabase.getDatabase(appContext).liveTranslatorTemplateDao().getTemplateById(it) }
            ?.toWearTemplate()
        return TranslatorStateSnapshot(
            revisioned = Revisioned(revision.incrementAndGet(), System.currentTimeMillis(), sourceDeviceId()),
            isActive = state.isActive,
            sessionId = state.sessionId,
            templateId = state.templateId,
            currentSpeaker = state.currentSpeaker,
            phase = state.phase.name,
            status = state.status,
            elapsedSeconds = state.elapsedSeconds,
            inputLevel = state.inputLevel,
            error = state.error,
            selectedTemplateId = template?.id,
            selectedTemplateName = template?.name,
            backendEngine = template?.backendEngine.orEmpty(),
            backendLabel = template?.backendLabel.orEmpty(),
            modelLabel = template?.modelLabel.orEmpty(),
            backendLoading = state.phase.name in setOf("IDLE", "WAITING") && state.isActive,
            backendStatus = state.status
        )
    }

    private suspend fun currentTranslatorTurns(sessionId: Long, limit: Int = 20): TranslatorTurnPage =
        TranslatorTurnPage(
            revisioned = Revisioned(revision.incrementAndGet(), System.currentTimeMillis(), sourceDeviceId()),
            sessionId = sessionId,
            turns = AppDatabase.getDatabase(appContext).liveTranslatorTurnDao()
                .getTurnsOnce(sessionId)
                .takeLast(limit)
                .map { it.toWearTranslatorTurn() },
            limit = limit
        )

    internal fun currentCapabilities(): WearCapabilities =
        WearCapabilities(
            revisioned = Revisioned(revision.incrementAndGet(), System.currentTimeMillis(), sourceDeviceId()),
            featureFlags = listOf(
                "server_control",
                "quick_chat",
                "pinned_chats",
                "organizer_calendar",
                "pet_state",
                "active_tasks",
                "stats"
            )
        )

    private fun sourceDeviceId(): String = localNodeId.ifBlank { appContext.packageName }
}

private class WearRequestRouter(
    private val appContext: Context,
    private val repository: LlamaRepository,
    private val tamaDatabase: TamaDatabase,
    private val tamaAgentService: TamaAgentService,
    private val publisher: WearStatePublisher,
    private val deltaEmitter: WearDeltaEmitter,
    private val serverController: LlamaServerController,
    private val processedGenerations: MutableSet<String>,
    private val messageListeners: ConcurrentHashMap<String, Long>
) {
    private companion object {
        private const val WEAR_QUICK_CHAT_PREFS = "adt_wear_quick_chat_v1"
        private const val WEAR_QUICK_CHAT_SELECTED_SERVER_ID = "selected_server_id"
        private const val WEAR_QUICK_CHAT_SYSTEM_PROMPT = "system_prompt"
        private const val WEAR_QUICK_CHAT_ALLOWED_TOOLS = "allowed_tools"
        private const val WEAR_QUICK_CHAT_AUTO_START = "auto_start_server"
        private const val WEAR_QUICK_CHAT_AUTO_TTS = "auto_play_tts"
        private const val WEAR_QUICK_CHAT_RETAIN_FINAL = "retain_final_result"
        private const val WEAR_TOOL_CALENDAR = "calendar"
        private const val WEAR_TOOL_ALARMS = "alarms"
        private const val WEAR_TOOL_NOTES = "notes"
        private const val WEAR_TOOL_PINNED_NOTE = "pinned_note"
        private const val WEAR_TOOL_WEB = "web_search"
        private const val WEAR_TOOL_IMAGES = "image_generation"
    }

    private val revision = AtomicLong(0L)
    private val selectedServerPrefs by lazy { appContext.getSharedPreferences("wear_companion_v1", Context.MODE_PRIVATE) }
    private val pendingVoiceUploads = ConcurrentHashMap<String, PendingVoiceUpload>()
    private val farmRepository by lazy { FarmRepository(tamaDatabase.farmDao(), appContext) }
    private val farmEngine by lazy { FarmEngine(farmRepository) }
    private val settingsRepo by lazy { SettingsRepository(appContext) }
    private val tamaGameEngine by lazy {
        TamaGameEngine(
            context = appContext,
            dao = tamaDatabase.tamaDao(),
            farmEngine = farmEngine,
            farmRepository = farmRepository,
            settingsRepo = settingsRepo
        )
    }
    @Volatile private var pendingTranslatorTemplate: TranslatorTemplateSummary? = null

    suspend fun route(nodeId: String, path: String, request: ByteArray): ByteArray {
        Log.i("ADT-WEAR-RPC", "request path=$path bytes=${request.size} node=$nodeId")
        val now = System.currentTimeMillis()
        if (!path.startsWith(AdtWearProtocol.PREFIX)) {
            return errorBytes(RpcMeta(UUID.randomUUID().toString(), createdAtEpochMs = now), "unknown_path", appContext.getString(R.string.wear_bridge_unknown_path))
        }
        if (request.size > AdtWearProtocol.MAX_RPC_BYTES) {
            return errorBytes(RpcMeta(UUID.randomUUID().toString(), createdAtEpochMs = now), "payload_too_large", appContext.getString(R.string.wear_bridge_oversized_payload))
        }
        return try {
            when (path) {
                AdtWearProtocol.PING -> handlePing(decode(PingRequest.serializer(), request).meta)
                AdtWearProtocol.SYNC -> handleSync(decode(PingRequest.serializer(), request).meta)
                AdtWearProtocol.SERVER_STATUS -> ok(
                    decode(PingRequest.serializer(), request).meta,
                    LlamaServerSnapshot.serializer(),
                    serverController.currentSnapshot()
                )
                AdtWearProtocol.SERVER_LIST -> handleServerList(decode(ServerListRequest.serializer(), request).meta)
                AdtWearProtocol.SERVER_SELECT -> handleServerSelect(decode(ServerSelectRequest.serializer(), request))
                AdtWearProtocol.SERVER_START -> handleServerStart(decode(ServerCommandRequest.serializer(), request).meta)
                AdtWearProtocol.SERVER_STOP -> handleServerStop(decode(ServerCommandRequest.serializer(), request).meta)
                AdtWearProtocol.CHAT_LIST -> handleChatList(decode(ChatListRequest.serializer(), request))
                AdtWearProtocol.CHAT_CREATE -> handleChatCreate(decode(ChatCreateRequest.serializer(), request))
                AdtWearProtocol.CHAT_MESSAGES -> handleChatMessages(decode(ChatMessagesRequest.serializer(), request))
                AdtWearProtocol.CHAT_SEND -> handleChatSend(nodeId, decode(ChatSendRequest.serializer(), request))
                AdtWearProtocol.CHAT_CLEAR -> handleChatClear(decode(ChatClearRequest.serializer(), request))
                AdtWearProtocol.CHAT_MESSAGE_DELETE -> handleChatMessageDelete(decode(ChatMessageActionRequest.serializer(), request))
                AdtWearProtocol.CHAT_MESSAGE_RETRY -> handleChatMessageRetry(nodeId, decode(ChatMessageActionRequest.serializer(), request))
                AdtWearProtocol.CHAT_PIN -> handleChatPin(decode(ChatPinRequest.serializer(), request), pinned = true)
                AdtWearProtocol.CHAT_UNPIN -> handleChatPin(decode(ChatPinRequest.serializer(), request), pinned = false)
                AdtWearProtocol.QUICK_CHAT_CONFIG -> handleQuickChatConfig(decode(PingRequest.serializer(), request).meta)
                AdtWearProtocol.QUICK_CHAT_CREATE -> handleQuickChatCreate(decode(QuickChatCreateRequest.serializer(), request))
                AdtWearProtocol.QUICK_CHAT_END -> handleQuickChatEnd(decode(QuickChatEndRequest.serializer(), request))
                AdtWearProtocol.CHAT_TOOL_CONFIRM -> handleToolConfirmation(decode(ToolConfirmationRequest.serializer(), request), accepted = true)
                AdtWearProtocol.CHAT_TOOL_REJECT -> handleToolConfirmation(decode(ToolConfirmationRequest.serializer(), request), accepted = false)
                AdtWearProtocol.TTS_GENERATE -> handleTtsGenerate(decode(TtsGenerateRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_EVENTS -> handleOrganizerEvents(decode(OrganizerEventsRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_EVENTS_MONTH -> handleOrganizerMonth(decode(OrganizerMonthRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_EVENT_UPSERT -> handleOrganizerEventUpsert(decode(OrganizerEventUpsertRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_EVENT_DELETE -> handleOrganizerEventDelete(decode(OrganizerEventDeleteRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_ALARMS -> handleOrganizerAlarms(decode(OrganizerAlarmsRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_ALARM_UPSERT -> handleOrganizerAlarmUpsert(decode(OrganizerAlarmUpsertRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_ALARM_TOGGLE -> handleOrganizerAlarmToggle(decode(OrganizerAlarmToggleRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_ALARM_DELETE -> handleOrganizerAlarmDelete(decode(OrganizerAlarmDeleteRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_NOTES -> handleOrganizerNotes(decode(OrganizerNotesRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_NOTE -> handleOrganizerNote(decode(OrganizerNoteRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_NOTE_UPSERT -> handleOrganizerNoteUpsert(decode(OrganizerNoteUpsertRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_NOTE_DELETE -> handleOrganizerNoteDelete(decode(OrganizerNoteDeleteRequest.serializer(), request))
                AdtWearProtocol.ORGANIZER_PINNED_NOTE -> handleOrganizerPinnedNote(decode(PingRequest.serializer(), request).meta)
                AdtWearProtocol.ORGANIZER_NOTE_PIN -> handleOrganizerNotePin(decode(OrganizerNotePinRequest.serializer(), request))
                AdtWearProtocol.MEDIA_REQUEST -> handleMediaRequest(decode(WearMediaRequest.serializer(), request))
                AdtWearProtocol.TRANSLATOR_TEMPLATES -> handleTranslatorTemplates(decode(PingRequest.serializer(), request).meta)
                AdtWearProtocol.TRANSLATOR_START -> handleTranslatorStart(decode(TranslatorCommandRequest.serializer(), request))
                AdtWearProtocol.TRANSLATOR_STOP -> handleTranslatorStop(decode(TranslatorCommandRequest.serializer(), request))
                AdtWearProtocol.TRANSLATOR_SPEAKER -> handleTranslatorSpeaker(decode(TranslatorCommandRequest.serializer(), request))
                AdtWearProtocol.TAMA_HUB -> handleTamaHub(decode(TamaHubRequest.serializer(), request))
                AdtWearProtocol.TAMA_ACTION -> handleTamaAction(decode(TamaActionRequest.serializer(), request))
                AdtWearProtocol.TAMA_INVENTORY -> handleTamaInventory(decode(TamaHubRequest.serializer(), request))
                AdtWearProtocol.TAMA_STORE -> handleTamaStore(decode(TamaHubRequest.serializer(), request))
                AdtWearProtocol.TAMA_FARM -> handleTamaFarm(decode(TamaHubRequest.serializer(), request))
                AdtWearProtocol.TAMA_FARM_ACTION -> handleTamaFarmAction(decode(TamaFarmActionRequest.serializer(), request))
                AdtWearProtocol.TAMA_ADVENTURE -> handleTamaAdventure(decode(TamaHubRequest.serializer(), request))
                AdtWearProtocol.TAMA_ADVENTURE_ACTION -> handleTamaAdventureAction(decode(TamaAdventureActionRequest.serializer(), request))
                AdtWearProtocol.TAMA_RPG -> handleTamaRpg(decode(TamaHubRequest.serializer(), request))
                AdtWearProtocol.TAMA_RPG_ACTION -> handleTamaRpgAction(decode(TamaRpgActionRequest.serializer(), request))
                AdtWearProtocol.TAMA_ARCADE -> handleTamaArcade(decode(TamaHubRequest.serializer(), request))
                AdtWearProtocol.VOICE_COMMIT -> handleVoiceCommit(nodeId, decode(VoiceCommitRequest.serializer(), request))
                AdtWearProtocol.GENERATION_CANCEL -> handleGenerationCancel(decode(GenerationCancelRequest.serializer(), request))
                AdtWearProtocol.TAMA_CHAT_SEND -> handleTamaChatSend(nodeId, decode(TamaChatRequest.serializer(), request))
                AdtWearProtocol.ACTIVE_TASKS -> handleActiveTasks(decode(PingRequest.serializer(), request).meta)
                AdtWearProtocol.STATS -> handleStats(decode(StatsRequest.serializer(), request))
                AdtWearProtocol.TASK_CANCEL -> handleTaskCommand(decode(TaskCommandRequest.serializer(), request), "cancel")
                AdtWearProtocol.TASK_PAUSE -> handleTaskCommand(decode(TaskCommandRequest.serializer(), request), "pause")
                AdtWearProtocol.TASK_RESUME -> handleTaskCommand(decode(TaskCommandRequest.serializer(), request), "resume")
                AdtWearProtocol.CAPABILITIES -> handleCapabilities(decode(PingRequest.serializer(), request).meta)
                else -> errorBytes(decodeMetaOrFallback(request), "unknown_path", appContext.getString(R.string.wear_bridge_unknown_path))
            }
        } catch (error: SerializationException) {
            errorBytes(RpcMeta(UUID.randomUUID().toString(), createdAtEpochMs = now), "malformed_json", appContext.getString(R.string.wear_bridge_malformed_json))
        } catch (error: Throwable) {
            Log.w("ADT-WEAR-RPC", "request failed path=$path error=${error.javaClass.simpleName}:${error.message}")
            errorBytes(decodeMetaOrFallback(request), error.stableCode(), error.wearMessage(appContext))
        }
    }

    suspend fun handleVoiceUpload(dataItem: DataItem) {
        val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
        val json = dataMap.getString(AdtWearProtocol.KEY_JSON) ?: return
        val metadata = AdtWearProtocol.json.decodeFromString(VoiceAssetMetadata.serializer(), json)
        validateVoiceMetadata(metadata)
        val asset = requireNotNull(dataMap.getAsset(AdtWearProtocol.KEY_ASSET)) {
            appContext.getString(R.string.wear_audio_missing_asset)
        }
        val audioFile = copyAssetToPrivateFile(asset, metadata)
        val actualHash = audioFile.sha256()
        require(actualHash.equals(metadata.sha256, ignoreCase = true)) {
            appContext.getString(R.string.wear_audio_hash_mismatch)
        }
        Log.i("ADT-WEAR-ASSET", "voice received request=${metadata.meta.requestId} target=${metadata.targetType} bytes=${metadata.byteCount}")
        pendingVoiceUploads[metadata.meta.requestId] = PendingVoiceUpload(metadata, audioFile, System.currentTimeMillis())
        publisher.publishVoiceAck(
            VoiceUploadAck(
                requestId = metadata.meta.requestId,
                targetType = metadata.targetType,
                targetId = metadata.targetId,
                status = "received",
                localizedMessage = appContext.getString(R.string.wear_voice_received),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    private suspend fun handleVoiceCommit(replyNodeId: String, request: VoiceCommitRequest): ByteArray {
        val pending = pendingVoiceUploads[request.uploadRequestId]
            ?: loadPendingVoiceUploadFromDataLayer(request.uploadRequestId)
            ?: return errorBytes(request.meta, "voice_not_ready", appContext.getString(R.string.wear_voice_not_ready))
        val metadata = pending.metadata
        val accepted = when (metadata.targetType) {
            "chat" -> {
                val chatId = metadata.chatId ?: metadata.targetId.toLongOrNull()
                val serverId = metadata.serverId ?: selectedServerId()
                require(chatId != null && serverId != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
                updateChatThinking(chatId, request.enableThinking)
                submitChatGeneration(
                    replyNodeId = replyNodeId,
                    requestId = metadata.meta.requestId,
                    chatId = chatId,
                    serverId = serverId,
                    text = "",
                    audioPath = pending.file.absolutePath
                )
                GenerationAccepted(metadata.meta.requestId, "chat", chatId.toString(), System.currentTimeMillis())
            }
            "tama" -> {
                submitTamaGeneration(
                    replyNodeId = replyNodeId,
                    requestId = metadata.meta.requestId,
                    petId = metadata.targetId,
                    text = "",
                    audioPath = pending.file.absolutePath,
                    audioDurationMs = metadata.durationMs,
                    enableThinking = request.enableThinking
                )
                GenerationAccepted(metadata.meta.requestId, "tama", metadata.targetId, System.currentTimeMillis())
            }
            else -> throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
        }
        pendingVoiceUploads.remove(request.uploadRequestId)
        publisher.publishVoiceAck(
            VoiceUploadAck(
                requestId = metadata.meta.requestId,
                targetType = metadata.targetType,
                targetId = metadata.targetId,
                status = "accepted",
                localizedMessage = appContext.getString(R.string.wear_generation_accepted),
                generation = accepted,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        return ok(request.meta, GenerationAccepted.serializer(), accepted)
    }

    private suspend fun loadPendingVoiceUploadFromDataLayer(requestId: String): PendingVoiceUpload? {
        val uploadPath = AdtWearProtocol.voiceUploadPath(requestId)
        val buffer = Tasks.await(Wearable.getDataClient(appContext).dataItems)
        try {
            buffer.firstOrNull { it.uri.path == uploadPath }?.let { dataItem ->
                handleVoiceUpload(dataItem)
            }
        } finally {
            buffer.release()
        }
        return pendingVoiceUploads[requestId]
    }

    private suspend fun handlePing(meta: RpcMeta): ByteArray =
        ok(
            meta,
            PingResult.serializer(),
            PingResult(
                applicationId = appContext.packageName,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                certificateSha256Short = signingSha256Short(appContext),
                localNodeId = runCatching { Tasks.await(Wearable.getNodeClient(appContext).localNode).id }.getOrDefault(""),
                wearableApiAvailable = GoogleApiAvailabilityLight.getInstance()
                    .isGooglePlayServicesAvailable(appContext) == com.google.android.gms.common.ConnectionResult.SUCCESS
            )
        )

    private suspend fun handleSync(meta: RpcMeta): ByteArray {
        publisher.publishServer(serverController.currentSnapshot())
        publisher.publishPet(currentPetSnapshot())
        publisher.publishTamaMessages(currentTamaMessages())
        publisher.publishTranslatorState(currentTranslatorState())
        publisher.publishCapabilities(PhoneWearGateway.currentCapabilities())
        publisher.publishActiveTasks(ActiveTaskRepository.currentSnapshot(appContext))
        publisher.publishStats(buildWearStatsSnapshot(appContext, StatsRequest(meta = meta)))
        return ok(meta, EmptyResult.serializer(), EmptyResult())
    }

    private suspend fun handleStats(request: StatsRequest): ByteArray =
        ok(request.meta, WearStatsSnapshot.serializer(), buildWearStatsSnapshot(appContext, request))

    private suspend fun handleServerList(meta: RpcMeta): ByteArray {
        val selected = selectedServerId()
        val servers = repository.allServers.first().map { it.toServerSummary(selected) }
        return ok(
            meta,
            ServerListPage.serializer(),
            ServerListPage(
                revisioned = revisioned(),
                servers = servers,
                selectedServerId = selected ?: servers.firstOrNull()?.id
            )
        )
    }

    private suspend fun handleServerSelect(request: ServerSelectRequest): ByteArray {
        require(repository.getServer(request.serverId) != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
        selectedServerPrefs.edit().putLong("selected_server_id", request.serverId).apply()
        val servers = repository.allServers.first().map { it.toServerSummary(request.serverId) }
        return ok(
            request.meta,
            ServerSelectResult.serializer(),
            ServerSelectResult(
                selectedServerId = request.serverId,
                page = ServerListPage(revisioned(), servers, selectedServerId = request.serverId)
            )
        )
    }

    private suspend fun handleServerStart(meta: RpcMeta): ByteArray =
        ok(meta, ServerCommandResult.serializer(), serverController.start(meta.requestId))

    private suspend fun handleServerStop(meta: RpcMeta): ByteArray =
        ok(meta, ServerCommandResult.serializer(), serverController.stop(meta.requestId))

    private suspend fun handleChatList(request: ChatListRequest): ByteArray {
        val limit = request.limit.coerceIn(1, 20)
        val offset = request.cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val folders = repository.allChatFolders.first().associate { it.id to it.name }
        val all = repository.allChats.first().filterNot { it.isEphemeral }
        val items = all.drop(offset).take(limit).map { chat ->
            val messages = repository.getMessagesOnce(chat.id)
            chat.toChatSummary(folders[chat.folderId], messages)
        }
        return ok(
            request.meta,
            ChatListPage.serializer(),
            ChatListPage(
                revisioned = revisioned(),
                chats = items,
                nextCursor = (offset + items.size).takeIf { it < all.size }?.toString(),
                totalCount = all.size,
                limit = limit
            )
        )
    }

    private suspend fun handleChatCreate(request: ChatCreateRequest): ByteArray {
        val id = repository.createChat(request.title?.takeIf { it.isNotBlank() } ?: appContext.getString(R.string.wear_chat_new_title))
        val chat = requireNotNull(repository.getChat(id))
        return ok(request.meta, ChatSummary.serializer(), chat.toChatSummary(null, emptyList()))
    }

    private suspend fun handleQuickChatConfig(meta: RpcMeta): ByteArray =
        ok(meta, QuickChatConfig.serializer(), currentQuickChatConfig())

    private suspend fun handleQuickChatCreate(request: QuickChatCreateRequest): ByteArray {
        repository.deleteExpiredEphemeralChats()
        val config = currentQuickChatConfig()
        val serverId = config.selectedServerId ?: selectedServerId()
        val selectedServer = serverId?.let {
            AppDatabase.getDatabase(appContext).llamaServerDao().getServerById(it)
        }
        if (
            config.autoStartServer &&
            selectedServer?.canAutoStartLocalLlamaServer() == true &&
            LlamaService.state.value is ServerState.Stopped
        ) {
            serverController.start(request.meta.requestId)
        }
        serverId?.let { selectedServerPrefs.edit().putLong("selected_server_id", it).apply() }
        val id = repository.createChat(
            title = appContext.getString(R.string.wear_quick_chat_title),
            systemPrompt = config.systemPrompt.ifBlank { null },
            apiParams = quickChatApiParams(config),
            isEphemeral = true,
            source = "WEAR_QUICK_CHAT",
            deleteAfterSession = true,
            expiresAtMillis = System.currentTimeMillis() + config.inactivityTimeoutSeconds.coerceAtLeast(60) * 1000L
        )
        val chat = requireNotNull(repository.getChat(id))
        return ok(request.meta, ChatSummary.serializer(), chat.toChatSummary(null, emptyList()))
    }

    private suspend fun handleQuickChatEnd(request: QuickChatEndRequest): ByteArray {
        repository.deleteEphemeralChat(request.chatId)
        return ok(
            request.meta,
            CommandAckDto.serializer(),
            CommandAckDto(request.meta.requestId, accepted = true, status = "SUCCEEDED", updatedAtEpochMs = System.currentTimeMillis())
        )
    }

    private suspend fun handleToolConfirmation(request: ToolConfirmationRequest, accepted: Boolean): ByteArray =
        ok(
            request.meta,
            CommandAckDto.serializer(),
            CommandAckDto(
                commandId = request.commandId,
                accepted = false,
                status = "FAILED",
                errorCode = if (accepted) "confirmation_not_available" else "rejected",
                errorMessage = if (accepted) appContext.getString(R.string.wear_tool_confirmation_unavailable) else appContext.getString(R.string.wear_tool_rejected),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )

    private suspend fun currentQuickChatConfig(): QuickChatConfig {
        val prefs = appContext.getSharedPreferences(WEAR_QUICK_CHAT_PREFS, Context.MODE_PRIVATE)
        val configuredServer = prefs.getLong(WEAR_QUICK_CHAT_SELECTED_SERVER_ID, -1L).takeIf { it > 0L }
        val selected = configuredServer ?: selectedServerId()
        val server = selected?.let { repository.getServer(it) }
        val allowedTools = prefs.getStringSet(
            WEAR_QUICK_CHAT_ALLOWED_TOOLS,
            setOf(WEAR_TOOL_CALENDAR, WEAR_TOOL_ALARMS, WEAR_TOOL_NOTES)
        ).orEmpty().toList().sorted()
        val prompt = prefs.getString(WEAR_QUICK_CHAT_SYSTEM_PROMPT, appContext.getString(R.string.wear_quick_chat_default_prompt))
            ?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.wear_quick_chat_default_prompt)
        return QuickChatConfig(
            revisioned = revisioned(),
            selectedServerId = selected,
            selectedServerLabel = server?.name.orEmpty(),
            systemPrompt = prompt,
            allowedTools = allowedTools,
            confirmationRequiredTools = allowedTools.filter { it in setOf(WEAR_TOOL_CALENDAR, WEAR_TOOL_ALARMS, WEAR_TOOL_NOTES) },
            autoStartServer = prefs.getBoolean(WEAR_QUICK_CHAT_AUTO_START, true),
            autoPlayTts = prefs.getBoolean(WEAR_QUICK_CHAT_AUTO_TTS, false),
            retainFinalResult = false
        )
    }

    private fun quickChatApiParams(config: QuickChatConfig): String {
        val allowed = config.allowedTools.toSet()
        val pinnedNoteId = PinnedOrganizerNoteStore.get(appContext)
            ?.takeIf { WEAR_TOOL_PINNED_NOTE in allowed }
        val nativeConfig = NativeChatToolConfig(
            toolsEnabled = allowed.isNotEmpty(),
            webSearchEnabled = WEAR_TOOL_WEB in allowed,
            fetchUrlEnabled = WEAR_TOOL_WEB in allowed,
            noteToolsEnabled = WEAR_TOOL_NOTES in allowed && pinnedNoteId == null,
            pinnedNoteId = pinnedNoteId,
            calendarToolsEnabled = WEAR_TOOL_CALENDAR in allowed,
            alarmToolsEnabled = WEAR_TOOL_ALARMS in allowed,
            imageGenerationEnabled = WEAR_TOOL_IMAGES in allowed,
            assistantTtsEnabled = config.autoPlayTts,
            maxToolRounds = 3
        )
        return JSONObject(nativeConfig.toParamMap()).toString()
    }

    private suspend fun handleChatMessages(request: ChatMessagesRequest): ByteArray {
        require(repository.getChat(request.chatId) != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
        val limit = request.limit.coerceIn(1, 30)
        val all = repository.getMessagesOnce(request.chatId)
        val filtered = request.beforeMessageId?.let { before -> all.filter { it.id < before } } ?: all
        val page = filtered.takeLast(limit)
        return ok(
            request.meta,
            ChatMessagePage.serializer(),
            ChatMessagePage(
                revisioned = revisioned(),
                chatId = request.chatId,
                messages = page.map { it.toWearMessage() },
                nextBeforeMessageId = filtered.dropLast(page.size).lastOrNull()?.id,
                limit = limit
            )
        )
    }

    private suspend fun handleChatSend(replyNodeId: String, request: ChatSendRequest): ByteArray {
        require(repository.getChat(request.chatId) != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
        require(repository.getServer(request.serverId) != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
        updateChatThinking(request.chatId, request.enableThinking)
        submitChatGeneration(replyNodeId, request.meta.requestId, request.chatId, request.serverId, request.text, null)
        return ok(
            request.meta,
            GenerationAccepted.serializer(),
            GenerationAccepted(request.meta.requestId, "chat", request.chatId.toString(), System.currentTimeMillis())
        )
    }

    private suspend fun handleChatClear(request: ChatClearRequest): ByteArray {
        require(repository.getChat(request.chatId) != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
        repository.clearChatMessages(request.chatId)
        return ok(request.meta, ChatMessagePage.serializer(), emptyChatPage(request.chatId))
    }

    private suspend fun handleChatMessageDelete(request: ChatMessageActionRequest): ByteArray {
        require(repository.getChat(request.chatId) != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
        val message = repository.getMessagesOnce(request.chatId).firstOrNull { it.id == request.messageId }
            ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
        repository.deleteMessage(message)
        return ok(request.meta, ChatMessagePage.serializer(), currentChatPage(request.chatId))
    }

    private suspend fun handleChatMessageRetry(replyNodeId: String, request: ChatMessageActionRequest): ByteArray {
        require(repository.getChat(request.chatId) != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
        val serverId = request.serverId ?: selectedServerId()
        require(serverId != null && repository.getServer(serverId) != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
        val message = repository.getMessagesOnce(request.chatId).firstOrNull { it.id == request.messageId && it.role == "user" }
            ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
        repository.deleteMessagesAfter(request.chatId, message.timestamp, message.id)
        updateChatThinking(request.chatId, request.enableThinking)
        submitChatGeneration(
            replyNodeId = replyNodeId,
            requestId = request.meta.requestId,
            chatId = request.chatId,
            serverId = serverId,
            text = "",
            audioPath = message.audioPath
        )
        return ok(
            request.meta,
            GenerationAccepted.serializer(),
            GenerationAccepted(request.meta.requestId, "chat", request.chatId.toString(), System.currentTimeMillis())
        )
    }

    private suspend fun handleChatPin(request: ChatPinRequest, pinned: Boolean): ByteArray {
        require(repository.getChat(request.chatId) != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
        repository.updateChatAiHubPin(request.chatId, pinned, request.serverId)
        return ok(request.meta, EmptyResult.serializer(), EmptyResult())
    }

    private suspend fun handleActiveTasks(meta: RpcMeta): ByteArray =
        ok(meta, ActiveTaskSnapshot.serializer(), ActiveTaskRepository.currentSnapshot(appContext))

    private suspend fun handleTaskCommand(request: TaskCommandRequest, command: String): ByteArray {
        val ack = ActiveTaskRepository.handleCommand(appContext, request, command)
        publisher.publishActiveTasks(ActiveTaskRepository.currentSnapshot(appContext))
        return ok(request.meta, CommandAckDto.serializer(), ack)
    }

    private suspend fun handleCapabilities(meta: RpcMeta): ByteArray =
        ok(meta, WearCapabilities.serializer(), PhoneWearGateway.currentCapabilities())

    private suspend fun handleOrganizerEvents(request: OrganizerEventsRequest): ByteArray {
        val limit = request.limit.coerceIn(1, 30)
        val dao = AppDatabase.getDatabase(appContext).organizerDao()
        val events = dao.getEventsInRangeOnce(request.startAtEpochMs, request.endAtEpochMs)
            .take(limit)
            .map { event -> event.toWearEvent(dao.getAlarmsForEventOnce(event.id).size) }
        return ok(
            request.meta,
            OrganizerEventPage.serializer(),
            OrganizerEventPage(revisioned(), events, limit)
        )
    }

    private suspend fun handleOrganizerMonth(request: OrganizerMonthRequest): ByteArray {
        val zone = runCatching { ZoneId.of(request.zoneId ?: ZoneId.systemDefault().id) }.getOrDefault(ZoneId.systemDefault())
        val ym = YearMonth.of(request.year, request.month.coerceIn(1, 12))
        val monthStart = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthEnd = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        val selectedStart = request.selectedDayEpochMs
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?.takeIf { it.year == ym.year && it.monthValue == ym.monthValue }
            ?: LocalDate.now(zone).takeIf { it.year == ym.year && it.monthValue == ym.monthValue }
            ?: ym.atDay(1)
        val selectedEnd = selectedStart.plusDays(1)
        val selectedStartMs = selectedStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val selectedEndMs = selectedEnd.atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        val dao = AppDatabase.getDatabase(appContext).organizerDao()
        val events = dao.getEventsInRangeOnce(monthStart, monthEnd)
        val days = (1..ym.lengthOfMonth()).map { day ->
            val date = ym.atDay(day)
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
            val dayEvents = events.filter { it.startAtMillis <= dayEnd && (it.endAtMillis ?: it.startAtMillis) >= dayStart }
            OrganizerMonthDay(
                dayOfMonth = day,
                dayStartEpochMs = dayStart,
                eventCount = dayEvents.size,
                hasAllDay = dayEvents.any { it.allDay },
                colorArgb = dayEvents.firstNotNullOfOrNull { it.colorArgb }
            )
        }
        val selectedEvents = events
            .filter { it.startAtMillis <= selectedEndMs && (it.endAtMillis ?: it.startAtMillis) >= selectedStartMs }
            .take(20)
            .map { event -> event.toWearEvent(dao.getAlarmsForEventOnce(event.id).size) }
        return ok(
            request.meta,
            OrganizerMonthPage.serializer(),
            OrganizerMonthPage(
                revisioned = revisioned(),
                year = ym.year,
                month = ym.monthValue,
                zoneId = zone.id,
                firstDayOfWeek = ym.atDay(1).dayOfWeek.value,
                daysInMonth = ym.lengthOfMonth(),
                selectedDayEpochMs = selectedStartMs,
                days = days,
                selectedDayEvents = selectedEvents
            )
        )
    }

    private suspend fun handleOrganizerEventUpsert(request: OrganizerEventUpsertRequest): ByteArray {
        val title = request.title.trim().take(120)
        require(title.isNotBlank()) { appContext.getString(R.string.wear_organizer_event_title_required) }
        val end = request.endAtEpochMs?.takeIf { it > 0L }
        require(end == null || end >= request.startAtEpochMs) { appContext.getString(R.string.wear_organizer_event_end_before_start) }
        val dao = AppDatabase.getDatabase(appContext).organizerDao()
        val now = System.currentTimeMillis()
        val existing = request.id?.takeIf { it > 0L }?.let { dao.getEventById(it) }
        val event = OrganizerEventEntity(
            id = existing?.id ?: 0L,
            title = title,
            description = request.description.trim().take(1_500),
            location = request.location.trim().take(180),
            startAtMillis = request.startAtEpochMs,
            endAtMillis = end,
            allDay = request.allDay,
            timezoneId = request.timezoneId?.takeIf { it.isNotBlank() } ?: existing?.timezoneId ?: ZoneId.systemDefault().id,
            colorArgb = request.colorArgb ?: existing?.colorArgb,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        val eventId = if (existing == null) dao.insertEvent(event) else {
            dao.updateEvent(event)
            event.id
        }
        replaceEventAlarm(dao, event.copy(id = eventId), request.alarmAtEpochMs)
        OrganizerCalendarWidgetProvider.refreshAll(appContext.applicationContext)
        return ok(
            request.meta,
            MutationResult.serializer(),
            MutationResult(eventId.toString(), appContext.getString(R.string.wear_organizer_event_saved))
        )
    }

    private suspend fun handleOrganizerEventDelete(request: OrganizerEventDeleteRequest): ByteArray {
        val dao = AppDatabase.getDatabase(appContext).organizerDao()
        val event = dao.getEventById(request.eventId)
            ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
        dao.getAlarmsForEventOnce(event.id).forEach { alarm ->
            OrganizerAlarmScheduler.cancelAlarm(appContext, alarm.id)
            dao.deleteAlarmById(alarm.id)
        }
        dao.deleteEventById(event.id)
        OrganizerCalendarWidgetProvider.refreshAll(appContext.applicationContext)
        return ok(
            request.meta,
            MutationResult.serializer(),
            MutationResult(event.id.toString(), appContext.getString(R.string.wear_organizer_event_deleted))
        )
    }

    private suspend fun handleOrganizerAlarms(request: OrganizerAlarmsRequest): ByteArray {
        val limit = request.limit.coerceIn(1, 30)
        val alarms = AppDatabase.getDatabase(appContext).organizerDao()
            .getAllAlarmsOnce()
            .filter { it.deliveredAt == null }
            .take(limit)
            .map { it.toWearAlarm() }
        return ok(
            request.meta,
            OrganizerAlarmPage.serializer(),
            OrganizerAlarmPage(revisioned(), alarms, limit)
        )
    }

    private suspend fun handleOrganizerAlarmUpsert(request: OrganizerAlarmUpsertRequest): ByteArray {
        val title = request.title.trim().ifBlank { appContext.getString(R.string.wear_alarm_default_title) }.take(120)
        require(request.triggerAtEpochMs > 0L) { appContext.getString(R.string.wear_alarm_time_required) }
        val dao = AppDatabase.getDatabase(appContext).organizerDao()
        val now = System.currentTimeMillis()
        val existing = request.id?.takeIf { it > 0L }?.let { dao.getAlarmById(it) }
        request.eventId?.takeIf { it > 0L }?.let { eventId ->
            require(dao.getEventById(eventId) != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
        }
        val alarm = existing?.copy(
            eventId = request.eventId ?: existing.eventId,
            title = title,
            message = request.message.trim().take(300),
            triggerAtMillis = request.triggerAtEpochMs,
            timezoneId = request.timezoneId?.takeIf { it.isNotBlank() } ?: existing.timezoneId,
            soundEnabled = request.soundEnabled,
            enabled = request.enabled,
            updatedAt = now,
            deliveredAt = null
        ) ?: OrganizerAlarmEntity(
            eventId = request.eventId?.takeIf { it > 0L },
            title = title,
            message = request.message.trim().take(300),
            triggerAtMillis = request.triggerAtEpochMs,
            timezoneId = request.timezoneId?.takeIf { it.isNotBlank() } ?: ZoneId.systemDefault().id,
            soundEnabled = request.soundEnabled,
            enabled = request.enabled,
            createdAt = now,
            updatedAt = now
        )
        val id = if (existing == null) dao.insertAlarm(alarm) else {
            OrganizerAlarmScheduler.cancelAlarm(appContext, alarm.id)
            dao.updateAlarm(alarm)
            alarm.id
        }
        val saved = alarm.copy(id = id)
        if (saved.enabled && saved.triggerAtMillis > now) {
            OrganizerAlarmScheduler.scheduleAlarm(appContext, saved)
        } else {
            OrganizerAlarmScheduler.cancelAlarm(appContext, saved.id)
        }
        return ok(
            request.meta,
            OrganizerAlarmPage.serializer(),
            OrganizerAlarmPage(revisioned(), dao.getAllAlarmsOnce().filter { it.deliveredAt == null }.take(20).map { it.toWearAlarm() }, 20)
        )
    }

    private suspend fun handleOrganizerAlarmToggle(request: OrganizerAlarmToggleRequest): ByteArray {
        val dao = AppDatabase.getDatabase(appContext).organizerDao()
        val alarm = dao.getAlarmById(request.alarmId)
            ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
        val updated = alarm.copy(enabled = request.enabled, updatedAt = System.currentTimeMillis(), deliveredAt = null)
        dao.updateAlarm(updated)
        if (updated.enabled && updated.triggerAtMillis > System.currentTimeMillis()) {
            OrganizerAlarmScheduler.scheduleAlarm(appContext, updated)
        } else {
            OrganizerAlarmScheduler.cancelAlarm(appContext, updated.id)
        }
        return ok(
            request.meta,
            OrganizerAlarmPage.serializer(),
            OrganizerAlarmPage(revisioned(), dao.getAllAlarmsOnce().filter { it.deliveredAt == null }.take(20).map { it.toWearAlarm() }, 20)
        )
    }

    private suspend fun handleOrganizerAlarmDelete(request: OrganizerAlarmDeleteRequest): ByteArray {
        val dao = AppDatabase.getDatabase(appContext).organizerDao()
        require(dao.getAlarmById(request.alarmId) != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
        OrganizerAlarmScheduler.cancelAlarm(appContext, request.alarmId)
        dao.deleteAlarmById(request.alarmId)
        return ok(
            request.meta,
            OrganizerAlarmPage.serializer(),
            OrganizerAlarmPage(revisioned(), dao.getAllAlarmsOnce().filter { it.deliveredAt == null }.take(20).map { it.toWearAlarm() }, 20)
        )
    }

    private suspend fun handleOrganizerNotes(request: OrganizerNotesRequest): ByteArray {
        val limit = request.limit.coerceIn(1, 20)
        val offset = request.cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val query = request.query.orEmpty().trim()
        val all = AppDatabase.getDatabase(appContext).noteDao().getAllNotesOnce()
            .filter { note ->
                query.isBlank() || note.title.contains(query, ignoreCase = true) || note.content.contains(query, ignoreCase = true)
            }
        val page = all.drop(offset).take(limit)
        return ok(
            request.meta,
            OrganizerNotePage.serializer(),
            OrganizerNotePage(
                revisioned = revisioned(),
                notes = page.map { it.toWearNoteSummary() },
                nextCursor = (offset + page.size).takeIf { it < all.size }?.toString(),
                totalCount = all.size,
                limit = limit
            )
        )
    }

    private suspend fun handleOrganizerNote(request: OrganizerNoteRequest): ByteArray {
        val note = AppDatabase.getDatabase(appContext).noteDao().getNoteById(request.noteId)
            ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
        return ok(request.meta, OrganizerNoteDetail.serializer(), note.toWearNoteDetail(revisioned()))
    }

    private suspend fun handleOrganizerPinnedNote(meta: RpcMeta): ByteArray {
        val note = PinnedOrganizerNoteStore.get(appContext)
            ?.let { AppDatabase.getDatabase(appContext).noteDao().getNoteById(it) }
        if (note == null) PinnedOrganizerNoteStore.set(appContext, null)
        return ok(
            meta,
            OrganizerPinnedNoteResult.serializer(),
            OrganizerPinnedNoteResult(note?.toWearNoteDetail(revisioned()))
        )
    }

    private suspend fun handleOrganizerNotePin(request: OrganizerNotePinRequest): ByteArray {
        val note = AppDatabase.getDatabase(appContext).noteDao().getNoteById(request.noteId)
            ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
        PinnedOrganizerNoteStore.set(appContext, note.id)
        return ok(
            request.meta,
            OrganizerPinnedNoteResult.serializer(),
            OrganizerPinnedNoteResult(note.toWearNoteDetail(revisioned()))
        )
    }

    private suspend fun handleOrganizerNoteUpsert(request: OrganizerNoteUpsertRequest): ByteArray {
        val title = request.title.trim().ifBlank { appContext.getString(R.string.wear_note_untitled) }.take(140)
        val content = request.content.trim().take(8_000)
        val dao = AppDatabase.getDatabase(appContext).noteDao()
        val now = System.currentTimeMillis()
        val existing = request.id?.takeIf { it > 0 }?.let { dao.getNoteById(it) }
        val entity = existing?.copy(
            title = title,
            content = content,
            updatedAt = now
        ) ?: NoteEntity(
            title = title,
            content = content,
            type = NoteType.MANUAL,
            createdAt = now,
            updatedAt = now
        )
        val id = if (existing == null) dao.insert(entity).toInt() else {
            dao.update(entity)
            entity.id
        }
        return ok(
            request.meta,
            MutationResult.serializer(),
            MutationResult(id.toString(), appContext.getString(R.string.wear_note_saved))
        )
    }

    private suspend fun handleOrganizerNoteDelete(request: OrganizerNoteDeleteRequest): ByteArray {
        val dao = AppDatabase.getDatabase(appContext).noteDao()
        require(dao.getNoteById(request.noteId) != null) { appContext.getString(R.string.wear_bridge_invalid_id) }
        dao.deleteById(request.noteId)
        return ok(
            request.meta,
            MutationResult.serializer(),
            MutationResult(request.noteId.toString(), appContext.getString(R.string.wear_note_deleted))
        )
    }

    private suspend fun handleMediaRequest(request: WearMediaRequest): ByteArray {
        val media = resolveWearMedia(request.mediaId)
        val result = WearMediaResult(
            mediaId = request.mediaId,
            mediaPathId = request.mediaId.toWearMediaPathId(),
            status = "complete",
            localizedMessage = appContext.getString(R.string.wear_media_ready),
            mediaType = media.type,
            mimeType = media.mimeType,
            byteCount = media.file.length(),
            sha256 = media.file.sha256()
        )
        publisher.publishMediaAsset(result, media.file)
        return ok(request.meta, WearMediaResult.serializer(), result)
    }

    private suspend fun resolveWearMedia(mediaId: String): WearResolvedMedia {
        val parts = mediaId.split(':')
        require(parts.size == 3) { appContext.getString(R.string.wear_bridge_invalid_id) }
        val file = when (parts[0] to parts[2]) {
            "chat" to "image" -> findChatMessage(parts[1].toLongOrNull())?.imagePath
            "chat" to "audio" -> findChatMessage(parts[1].toLongOrNull())?.audioPath
            "tama" to "audio" -> findTamaMessage(parts[1])?.audioPath
            "tama" to "image" -> findTamaMessage(parts[1])?.imagePath
            "note" to "audio" -> AppDatabase.getDatabase(appContext).noteDao().getNoteById(parts[1].toIntOrNull() ?: -1)?.audioPath
            else -> null
        }?.let(::File)?.takeIf { it.isFile }
            ?: throw IllegalArgumentException(appContext.getString(R.string.wear_media_missing))
        val mimeType = when (file.extension.lowercase(Locale.getDefault())) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "wav" -> "audio/wav"
            else -> if (parts[2] == "image") "image/*" else "audio/*"
        }
        return WearResolvedMedia(file, parts[2], mimeType)
    }

    private suspend fun findChatMessage(messageId: Long?): LlamaMessageEntity? {
        val id = messageId ?: return null
        return repository.allChats.first().firstNotNullOfOrNull { chat ->
            repository.getMessagesOnce(chat.id).firstOrNull { it.id == id }
        }
    }

    private suspend fun findTamaMessage(messageId: String): TamaChatMessageEntity? {
        val petId = tamaDatabase.tamaDao().getActivePet()?.id ?: return null
        return tamaDatabase.tamaDao().getChatHistory(petId).firstOrNull { it.id == messageId }
    }

    private suspend fun handleTranslatorTemplates(meta: RpcMeta): ByteArray {
        val templates = AppDatabase.getDatabase(appContext).liveTranslatorTemplateDao()
            .getTemplatesOnce()
            .map { it.toWearTemplate() }
        return ok(meta, TranslatorTemplatePage.serializer(), TranslatorTemplatePage(revisioned(), templates))
    }

    private suspend fun handleTranslatorStart(request: TranslatorCommandRequest): ByteArray {
        val templateId = requireNotNull(request.templateId) { appContext.getString(R.string.wear_bridge_invalid_id) }
        val template = AppDatabase.getDatabase(appContext).liveTranslatorTemplateDao().getTemplateById(templateId)
            ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
        pendingTranslatorTemplate = template.toWearTemplate()
        val loadingState = currentTranslatorState(
            template = pendingTranslatorTemplate,
            backendLoading = true,
            phaseOverride = "STARTING",
            statusOverride = appContext.getString(R.string.wear_translator_loading_backend)
        )
        publisher.publishTranslatorState(loadingState)
        appContext.startForegroundService(LiveTranslatorService.startIntent(appContext, templateId, request.sessionId))
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(500L)
            publisher.publishTranslatorState(currentTranslatorState())
        }
        return ok(request.meta, TranslatorStateSnapshot.serializer(), loadingState)
    }

    private suspend fun handleTranslatorStop(request: TranslatorCommandRequest): ByteArray {
        appContext.startService(LiveTranslatorService.stopIntent(appContext))
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(500L)
            publisher.publishTranslatorState(currentTranslatorState())
        }
        return ok(request.meta, TranslatorStateSnapshot.serializer(), currentTranslatorState())
    }

    private suspend fun handleTranslatorSpeaker(request: TranslatorCommandRequest): ByteArray {
        val speaker = requireNotNull(request.speaker) { appContext.getString(R.string.wear_bridge_invalid_id) }
        appContext.startService(LiveTranslatorService.setNextSpeakerIntent(appContext, speaker))
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(300L)
            publisher.publishTranslatorState(currentTranslatorState())
        }
        return ok(request.meta, TranslatorStateSnapshot.serializer(), currentTranslatorState())
    }

    private fun handleTtsGenerate(request: TtsGenerateRequest): ByteArray {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val model = AppDatabase.getDatabase(appContext).modelDao()
                    .getModelsByType(ModelType.ONNX_TTS)
                    .first()
                    .firstOrNull()
                    ?: throw IllegalStateException(appContext.getString(R.string.onnx_tts_no_model))
                val result = SupertonicTtsPipeline(appContext).generate(
                    OnnxTtsRequest(
                        modelPath = model.path,
                        modelName = model.filename,
                        text = request.text.take(3_000),
                        language = request.languageHint?.takeIf { it.isNotBlank() } ?: "en",
                        sourceName = "wear_${request.meta.requestId}"
                    )
                )
                publisher.publishTtsAudio(request.meta.requestId, result.playableFile)
            }.onFailure { error ->
                publisher.lastRpcError = "tts_failed: ${error.javaClass.simpleName}:${error.message}"
                publisher.publishTtsResult(
                    TtsAudioResult(
                        requestId = request.meta.requestId,
                        status = "error",
                        localizedMessage = error.wearMessage(appContext)
                    )
                )
            }
        }
        return ok(
            request.meta,
            TtsAudioResult.serializer(),
            TtsAudioResult(
                requestId = request.meta.requestId,
                status = "accepted",
                localizedMessage = appContext.getString(R.string.wear_tts_phone_started)
            )
        )
    }

    private fun handleGenerationCancel(request: GenerationCancelRequest): ByteArray {
        appContext.startService(Intent(appContext, LlamaClientService::class.java).apply {
            action = LlamaClientService.ACTION_STOP
        })
        messageListeners.remove(request.generationId)
        return ok(request.meta, EmptyResult.serializer(), EmptyResult())
    }

    private suspend fun handleTamaChatSend(replyNodeId: String, request: TamaChatRequest): ByteArray {
        require(request.petId.isNotBlank()) { appContext.getString(R.string.wear_bridge_invalid_id) }
        submitTamaGeneration(replyNodeId, request.meta.requestId, request.petId, request.text, null, null, request.enableThinking)
        return ok(
            request.meta,
            GenerationAccepted.serializer(),
            GenerationAccepted(request.meta.requestId, "tama", request.petId, System.currentTimeMillis())
        )
    }

    private suspend fun handleTamaHub(request: TamaHubRequest): ByteArray =
        ok(request.meta, TamaHubSnapshot.serializer(), currentTamaHub(request.petId, request.limit))

    private suspend fun handleTamaInventory(request: TamaHubRequest): ByteArray {
        val pet = activeTamaPet(request.petId)
        val limit = request.limit.coerceIn(1, 30)
        val items = pet.inventory.take(limit).map { it.toWearInventoryItem() }
        return ok(
            request.meta,
            TamaInventoryPage.serializer(),
            TamaInventoryPage(revisioned(), items, totalCount = pet.inventory.size, limit = limit)
        )
    }

    private suspend fun handleTamaStore(request: TamaHubRequest): ByteArray {
        val pet = activeTamaPet(request.petId)
        val saleItems = FarmTradeItemCatalog.allDefinitions()
            .take(18)
            .map {
                TamaInventoryItemSummary(
                    id = it.inventoryId,
                    name = FarmTradeItemCatalog.displayName(it.inventoryId, Locale.getDefault()).wearLimit(64),
                    type = "trade",
                    price = it.sellPrice.toLong(),
                    actionLabel = appContext.getString(R.string.wear_tama_sell)
                )
            }
        return ok(
            request.meta,
            TamaStorePage.serializer(),
            TamaStorePage(
                revisioned = revisioned(),
                coins = pet.money,
                items = pet.inventory.take(12).map { it.toWearInventoryItem(appContext.getString(R.string.wear_tama_sell)) },
                upgrades = saleItems.take(8),
                livestock = saleItems.drop(8).take(4)
            )
        )
    }

    private suspend fun handleTamaFarm(request: TamaHubRequest): ByteArray {
        val pet = activeTamaPet(request.petId)
        farmEngine.updateFarm(pet.id)
        return ok(request.meta, TamaFarmSnapshot.serializer(), currentFarmSnapshot(pet.id))
    }

    private suspend fun handleTamaAdventure(request: TamaHubRequest): ByteArray {
        val pet = activeTamaPet(request.petId)
        val session = tamaDatabase.tamaDao().getActiveAdventureSession(pet.id)
        val stages = session?.let { tamaDatabase.tamaDao().getAdventureStages(it.id) }.orEmpty()
        val latest = stages.lastOrNull()
        return ok(
            request.meta,
            TamaAdventureSnapshot.serializer(),
            TamaAdventureSnapshot(
                revisioned = revisioned(),
                sessionId = session?.id,
                title = session?.dungeonType.orEmpty().ifBlank { appContext.getString(R.string.wear_tama_adventure_title) }.wearLimit(80),
                status = if (session?.isCompleted == true) appContext.getString(R.string.wear_tama_complete) else appContext.getString(R.string.wear_tama_adventure_ready),
                story = latest?.storyContent.orEmpty().wearLimit(1_200),
                choices = emptyList()
            )
        )
    }

    private suspend fun handleTamaRpg(request: TamaHubRequest): ByteArray {
        val pet = activeTamaPet(request.petId)
        val profile = tamaDatabase.tamaDao().getAdventureGateProfile(pet.id)
        val battle = tamaDatabase.tamaDao().getAdventureGateBattleState(pet.id)
        val progress = tamaDatabase.tamaDao().getAdventureGateWorldProgress(pet.id).take(4)
        return ok(
            request.meta,
            TamaRpgSnapshot.serializer(),
            TamaRpgSnapshot(
                revisioned = revisioned(),
                status = battle?.stateJson?.wearLimit(600) ?: appContext.getString(R.string.wear_tama_rpg_ready),
                profile = listOfNotNull(
                    profile?.let { TamaModuleSummary("level", appContext.getString(R.string.wear_tama_level, it.level), "HP ${it.currentHp}/${it.maxHp}") },
                    TamaModuleSummary("worlds", appContext.getString(R.string.wear_tama_worlds), progress.joinToString { it.worldId }.wearLimit(80))
                ),
                actions = listOf(
                    TamaQuickAction("start_battle", appContext.getString(R.string.wear_tama_start_battle), "rpg"),
                    TamaQuickAction("recover", appContext.getString(R.string.wear_tama_recover), "rpg"),
                    TamaQuickAction("retreat", appContext.getString(R.string.wear_tama_retreat), "rpg", requiresConfirmation = true)
                )
            )
        )
    }

    private suspend fun handleTamaArcade(request: TamaHubRequest): ByteArray {
        activeTamaPet(request.petId)
        return ok(
            request.meta,
            TamaArcadeSnapshot.serializer(),
            TamaArcadeSnapshot(
                revisioned = revisioned(),
                games = listOf(
                    TamaModuleSummary("memory", appContext.getString(R.string.wear_tama_arcade_memory), appContext.getString(R.string.wear_tama_arcade_phone_runs)),
                    TamaModuleSummary("reflex", appContext.getString(R.string.wear_tama_arcade_reflex), appContext.getString(R.string.wear_tama_arcade_phone_runs))
                ),
                status = appContext.getString(R.string.wear_tama_arcade_status)
            )
        )
    }

    private suspend fun handleTamaAction(request: TamaActionRequest): ByteArray {
        val pet = tamaGameEngine.loadPet()
            ?: throw IllegalStateException(appContext.getString(R.string.wear_tama_no_pet))
        request.petId?.takeIf { it.isNotBlank() }?.let { require(it == pet.id) { appContext.getString(R.string.wear_bridge_invalid_id) } }
        val result = when (request.action) {
            "feed" -> tamaGameEngine.feed()
            "clean" -> tamaGameEngine.clean()
            "play" -> tamaGameEngine.play()
            "sleep" -> tamaGameEngine.goToBed()
            "wake" -> {
                tamaGameEngine.wakeUp()
                TamaGameEngine.ActionResult(true, appContext.getString(R.string.wear_tama_action_done))
            }
            "stop_activity" -> tamaGameEngine.stopActivity()
            "study" -> tamaGameEngine.startNormalStudySession(emptySet(), emptyList())
            "work" -> tamaGameEngine.startWork(request.args["jobId"] ?: "paper_route")
            "training" -> tamaGameEngine.startTraining(request.args["tierId"] ?: "basic")
            "travel" -> {
                val locationId = request.args["locationId"].orEmpty()
                require(locationId.isNotBlank()) { appContext.getString(R.string.wear_bridge_invalid_id) }
                tamaDatabase.tamaDao().updateLocation(pet.id, locationId)
                TamaGameEngine.ActionResult(true, appContext.getString(R.string.wear_tama_action_done), "travel")
            }
            "use_item" -> {
                val item = pet.inventory.firstOrNull { it.id == request.args["itemId"] }
                    ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
                val used = tamaGameEngine.consumeItem(item, request.args["quantity"]?.toIntOrNull()?.coerceIn(1, 99) ?: 1)
                TamaGameEngine.ActionResult(used, appContext.getString(if (used) R.string.wear_tama_action_done else R.string.wear_tama_action_failed))
            }
            "sell_item" -> {
                val item = pet.inventory.firstOrNull { it.id == request.args["itemId"] }
                    ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
                tamaGameEngine.sellItem(item, request.args["quantity"]?.toIntOrNull()?.coerceIn(1, 99) ?: 1, request.args["price"]?.toLongOrNull() ?: FarmTradeItemCatalog.sellPrice(item.id).toLong().coerceAtLeast(1L))
            }
            "delete_artwork" -> {
                val artworkId = request.args["artworkId"].orEmpty()
                require(artworkId.isNotBlank()) { appContext.getString(R.string.wear_bridge_invalid_id) }
                tamaDatabase.tamaDao().deleteArtwork(artworkId)
                TamaGameEngine.ActionResult(true, appContext.getString(R.string.wear_tama_gallery_deleted))
            }
            else -> TamaGameEngine.ActionResult(false, appContext.getString(R.string.wear_tama_action_needs_phone_args))
        }
        publisher.publishPet(currentPetSnapshot())
        return ok(
            request.meta,
            TamaActionResult.serializer(),
            TamaActionResult(result.success, result.message, currentTamaHub(pet.id))
        )
    }

    private suspend fun handleTamaFarmAction(request: TamaFarmActionRequest): ByteArray {
        val pet = tamaGameEngine.loadPet() ?: throw IllegalStateException(appContext.getString(R.string.wear_tama_no_pet))
        request.petId?.takeIf { it.isNotBlank() }?.let { require(it == pet.id) { appContext.getString(R.string.wear_bridge_invalid_id) } }
        val tileId = request.tileId?.toIntOrNull()
        val result = when (request.action) {
            "till" -> {
                val tile = farmRepository.ensureUnlockedFarmTiles(pet.id).firstOrNull { it.id == tileId }
                    ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
                farmRepository.saveTile(pet.id, tile.copy(status = TileStatus.FARMLAND))
                tamaGameEngine.logEvent(pet.id, EventType.OTHER, appContext.getString(R.string.tama_event_tilled))
                appContext.getString(R.string.wear_tama_action_done)
            }
            "water" -> {
                val tile = farmRepository.ensureUnlockedFarmTiles(pet.id).firstOrNull { it.id == tileId }
                    ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
                val water = pet.inventory.firstOrNull { it.id == "water" && it.quantity > 0 }
                    ?: throw IllegalStateException(appContext.getString(R.string.wear_tama_no_water))
                require(tamaGameEngine.consumeItem(water, 1)) { appContext.getString(R.string.wear_tama_action_failed) }
                farmRepository.saveTile(pet.id, tile.copy(status = TileStatus.WET_FARMLAND, lastWateredTime = System.currentTimeMillis()))
                appContext.getString(R.string.wear_tama_action_done)
            }
            "plant" -> {
                val tile = farmRepository.ensureUnlockedFarmTiles(pet.id).firstOrNull { it.id == tileId }
                    ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
                val seed = pet.inventory.firstOrNull { it.id == request.itemId && it.quantity > 0 }
                    ?: throw IllegalStateException(appContext.getString(R.string.wear_tama_no_seed))
                require(tile.status == TileStatus.WET_FARMLAND && tile.crop == null) { appContext.getString(R.string.wear_tama_action_failed) }
                require(tamaGameEngine.consumeItem(seed, 1)) { appContext.getString(R.string.wear_tama_action_failed) }
                farmRepository.saveTile(
                    pet.id,
                    tile.copy(
                        crop = PlantedCrop(
                            type = seed.id.removePrefix("seed_"),
                            plantedTime = System.currentTimeMillis(),
                            lastStageUpdateTime = System.currentTimeMillis()
                        )
                    )
                )
                appContext.getString(R.string.wear_tama_action_done)
            }
            "harvest" -> {
                val tile = farmRepository.ensureUnlockedFarmTiles(pet.id).firstOrNull { it.id == tileId }
                    ?: throw IllegalArgumentException(appContext.getString(R.string.wear_bridge_invalid_id))
                val crop = tile.crop ?: throw IllegalStateException(appContext.getString(R.string.wear_tama_action_failed))
                val harvest = tamaGameEngine.harvestCrop(crop)
                if (harvest.success) farmRepository.saveTile(pet.id, tile.copy(crop = null, status = TileStatus.SOIL))
                harvest.message
            }
            else -> appContext.getString(R.string.wear_tama_action_needs_phone_args)
        }
        return ok(request.meta, TamaFarmSnapshot.serializer(), currentFarmSnapshot(pet.id, result))
    }

    private suspend fun handleTamaAdventureAction(request: TamaAdventureActionRequest): ByteArray {
        activeTamaPet(request.petId)
        return ok(
            request.meta,
            TamaActionResult.serializer(),
            TamaActionResult(false, appContext.getString(R.string.wear_tama_action_needs_phone_args), null)
        )
    }

    private suspend fun handleTamaRpgAction(request: TamaRpgActionRequest): ByteArray {
        activeTamaPet(request.petId)
        return ok(
            request.meta,
            TamaActionResult.serializer(),
            TamaActionResult(false, appContext.getString(R.string.wear_tama_action_needs_phone_args), null)
        )
    }

    private suspend fun activeTamaPet(petId: String? = null): TamaPet {
        val pet = tamaDatabase.tamaDao().getActivePet()?.let(PetMapper::toDomain)
            ?: throw IllegalStateException(appContext.getString(R.string.wear_tama_no_pet))
        petId?.takeIf { it.isNotBlank() }?.let { require(it == pet.id) { appContext.getString(R.string.wear_bridge_invalid_id) } }
        return pet
    }

    private suspend fun currentTamaHub(petId: String? = null, limit: Int = 20): TamaHubSnapshot {
        val pet = activeTamaPet(petId)
        val snapshot = pet.toPetSnapshot(appContext, sourceDeviceId(), revision.incrementAndGet())
        val events = tamaDatabase.tamaDao().getRecentEvents(pet.id, 6).map { it.details.wearLimit(140) }
        val locationLabel = tamaDatabase.tamaDao().getLocation(pet.currentLocationId)?.name
            ?: pet.currentLocationId.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
        return TamaHubSnapshot(
            revisioned = revisioned(),
            pet = snapshot,
            coins = pet.money,
            locationLabel = locationLabel,
            modules = listOf(
                TamaModuleSummary("pet", appContext.getString(R.string.wear_tama_module_pet), pet.tamaActivityLabel(appContext)),
                TamaModuleSummary("care", appContext.getString(R.string.wear_tama_module_care), appContext.getString(R.string.wear_tama_stats)),
                TamaModuleSummary("inventory", appContext.getString(R.string.wear_tama_module_inventory), pet.inventory.size.toString()),
                TamaModuleSummary("store", appContext.getString(R.string.wear_tama_module_store), pet.money.toString()),
                TamaModuleSummary("farm", appContext.getString(R.string.wear_tama_module_farm)),
                TamaModuleSummary("adventure", appContext.getString(R.string.wear_tama_module_adventure)),
                TamaModuleSummary("rpg", appContext.getString(R.string.wear_tama_module_rpg)),
                TamaModuleSummary("arcade", appContext.getString(R.string.wear_tama_module_arcade)),
                TamaModuleSummary("gallery", appContext.getString(R.string.wear_tama_module_gallery)),
                TamaModuleSummary("chat", appContext.getString(R.string.wear_tama_chat))
            ),
            actions = listOf(
                TamaQuickAction("feed", appContext.getString(R.string.wear_tama_feed), "care"),
                TamaQuickAction("clean", appContext.getString(R.string.wear_tama_clean), "care"),
                TamaQuickAction("play", appContext.getString(R.string.wear_tama_play), "care"),
                TamaQuickAction(if (pet.isSleeping) "wake" else "sleep", appContext.getString(if (pet.isSleeping) R.string.wear_tama_wake else R.string.wear_tama_sleep), "care"),
                TamaQuickAction("stop_activity", appContext.getString(R.string.wear_tama_stop_activity), "care"),
                TamaQuickAction("study", appContext.getString(R.string.wear_tama_study), "care"),
                TamaQuickAction("work", appContext.getString(R.string.wear_tama_work), "care"),
                TamaQuickAction("training", appContext.getString(R.string.wear_tama_train), "care")
            ),
            inventoryPreview = pet.inventory.take(limit.coerceIn(1, 20)).map { it.toWearInventoryItem() },
            recentEvents = events
        )
    }

    private suspend fun currentFarmSnapshot(petId: String, status: String = ""): TamaFarmSnapshot {
        farmEngine.updateFarm(petId)
        val tiles = farmRepository.ensureUnlockedFarmTiles(petId).take(18).map { tile ->
            val crop = tile.crop
            TamaFarmTileSummary(
                id = tile.id.toString(),
                title = crop?.type?.replace('_', ' ')?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                    ?: tile.status.name.lowercase(Locale.getDefault()),
                status = when {
                    crop == null -> tile.status.name
                    crop.isDecayed -> appContext.getString(R.string.wear_tama_farm_decayed)
                    crop.stage >= 3 -> appContext.getString(R.string.wear_tama_farm_ready)
                    else -> appContext.getString(R.string.wear_tama_farm_growing)
                },
                progressPercent = crop?.stage?.let { (it * 33).coerceIn(0, 100) } ?: 0,
                actionLabel = when {
                    tile.status == TileStatus.SOIL -> appContext.getString(R.string.wear_tama_till)
                    tile.crop?.stage == 3 -> appContext.getString(R.string.wear_tama_harvest)
                    tile.status == TileStatus.FARMLAND -> appContext.getString(R.string.wear_tama_water)
                    tile.status == TileStatus.WET_FARMLAND && tile.crop == null -> appContext.getString(R.string.wear_tama_plant)
                    else -> null
                }
            )
        }
        val upgrades = farmRepository.getUpgrades(petId).map {
            TamaModuleSummary(it.type, it.type.replace('_', ' ').replaceFirstChar { c -> c.titlecase(Locale.getDefault()) }, "L${it.level} ${it.storedOutput}")
        }
        val livestock = farmRepository.getLivestock(petId).map {
            TamaModuleSummary(it.type, it.type.replace('_', ' ').replaceFirstChar { c -> c.titlecase(Locale.getDefault()) })
        }
        return TamaFarmSnapshot(revisioned(), tiles, upgrades, livestock, status)
    }

    private fun InventoryItem.toWearInventoryItem(actionLabel: String? = appContext.getString(R.string.wear_tama_use)): TamaInventoryItemSummary =
        TamaInventoryItemSummary(
            id = id,
            name = inventoryItemDisplayName(appContext, this).wearLimit(64),
            type = type.name,
            quantity = quantity,
            price = FarmTradeItemCatalog.sellPrice(id).takeIf { it > 0 }?.toLong(),
            actionLabel = actionLabel
        )

    private fun submitChatGeneration(
        replyNodeId: String?,
        requestId: String,
        chatId: Long,
        serverId: Long,
        text: String,
        audioPath: String?
    ) {
        if (!processedGenerations.add(requestId)) return
        messageListeners[requestId] = 0L
        appContext.startForegroundService(Intent(appContext, LlamaClientService::class.java).apply {
            action = LlamaClientService.ACTION_GENERATE
            putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
            putExtra(LlamaClientService.EXTRA_SERVER_ID, serverId)
            putExtra(LlamaClientService.EXTRA_USER_MESSAGE, text)
            audioPath?.let { putExtra(LlamaClientService.EXTRA_AUDIO_PATH, it) }
            putExtra(LlamaClientService.EXTRA_FORCE_ASSISTANT_TTS, false)
        })
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch generationCollector@{
            var observedFreshGeneration = false
            LlamaClientService.generationState
                .filter { state ->
                    when (state) {
                        is LlamaClientService.GenerationState.Generating -> state.chatId == chatId
                        is LlamaClientService.GenerationState.Completed -> state.chatId == chatId
                        is LlamaClientService.GenerationState.Error -> state.chatId == chatId || state.chatId == -1L
                        else -> false
                    }
                }
                .collect { state ->
                    when (state) {
                        is LlamaClientService.GenerationState.Generating -> {
                            observedFreshGeneration = true
                            emitCoalescedDelta(replyNodeId, requestId, "chat", chatId.toString(), state.content, state.thinking)
                        }
                        is LlamaClientService.GenerationState.Completed -> {
                            if (!observedFreshGeneration) return@collect
                            publisher.publishGenerationFinal(
                                GenerationFinal(
                                    revisioned = revisioned(),
                                    generationId = requestId,
                                    targetType = "chat",
                                    targetId = chatId.toString(),
                                    status = "complete",
                                    content = state.content.wearLimit(8_000),
                                    thinking = state.thinking?.wearLimit(8_000)
                                )
                            )
                            deleteFinishedQuickChat(chatId)
                            messageListeners.remove(requestId)
                            this@generationCollector.cancel()
                        }
                        is LlamaClientService.GenerationState.Error -> {
                            if (!observedFreshGeneration) return@collect
                            publisher.publishGenerationFinal(
                                GenerationFinal(
                                    revisioned = revisioned(),
                                    generationId = requestId,
                                    targetType = "chat",
                                    targetId = chatId.toString(),
                                    status = "error",
                                    error = RpcError("generation_failed", state.message.wearLimit(8_000))
                                )
                            )
                            deleteFinishedQuickChat(chatId)
                            messageListeners.remove(requestId)
                            this@generationCollector.cancel()
                        }
                        else -> Unit
                    }
                }
        }
    }

    private suspend fun deleteFinishedQuickChat(chatId: Long) {
        val chat = repository.getChat(chatId) ?: return
        if (chat.isEphemeral && chat.source == "WEAR_QUICK_CHAT") {
            repository.deleteEphemeralChat(chatId)
        }
    }

    private suspend fun submitTamaGeneration(
        replyNodeId: String?,
        requestId: String,
        petId: String,
        text: String,
        audioPath: String?,
        audioDurationMs: Long?,
        enableThinking: Boolean
    ) {
        val pet = tamaDatabase.tamaDao().getActivePet()?.let(PetMapper::toDomain)
            ?: throw IllegalStateException(appContext.getString(R.string.wear_tama_no_pet))
        require(pet.id == petId) { appContext.getString(R.string.wear_bridge_invalid_id) }
        require(!pet.cycleFrozen) { appContext.getString(R.string.wear_tama_frozen_no_chat) }
        if (!processedGenerations.add(requestId)) return
        settingsRepo.setTamaThinkingEnabled(enableThinking)
        val requestedAt = System.currentTimeMillis()
        var sequence = 0L
        tamaAgentService.sendMessage(pet, text, audioPath, audioDurationMs) { chunk ->
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                deltaEmitter.emit(
                    replyNodeId,
                    GenerationDelta(requestId, "tama", petId, ++sequence, chunk.wearLimit(2_000), updatedAtEpochMs = System.currentTimeMillis())
                )
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(1_200L)
            val lastAssistant = tamaDatabase.tamaDao().getChatHistory(petId)
                .lastOrNull { it.role == "assistant" && it.timestamp >= requestedAt }
            publisher.publishTamaMessages(currentTamaMessages())
            publisher.publishGenerationFinal(
                GenerationFinal(
                    revisioned = revisioned(),
                    generationId = requestId,
                    targetType = "tama",
                    targetId = petId,
                    status = if (lastAssistant == null) "error" else "complete",
                    content = lastAssistant?.content.orEmpty().wearLimit(8_000),
                    thinking = lastAssistant?.thinking?.wearLimit(8_000),
                    error = if (lastAssistant == null) RpcError("generation_missing", appContext.getString(R.string.wear_no_new_tama_reply)) else null
                )
            )
        }
    }

    private fun emitCoalescedDelta(nodeId: String?, requestId: String, targetType: String, targetId: String, content: String, thinking: String?) {
        val now = System.currentTimeMillis()
        val last = messageListeners[requestId] ?: 0L
        if (now - last < 300L && content.length < 50) return
        messageListeners[requestId] = now
        deltaEmitter.emit(
            nodeId,
            GenerationDelta(requestId, targetType, targetId, now, content.wearLimit(2_000), thinking?.wearLimit(2_000), now)
        )
    }

    private suspend fun currentPetSnapshot(): PetSnapshot {
        val pet = tamaDatabase.tamaDao().getActivePet()?.let(PetMapper::toDomain)
        return pet?.toPetSnapshot(appContext, sourceDeviceId(), revision.incrementAndGet())
            ?: PetSnapshot(revisioned = revisioned(), hasPet = false)
    }

    private suspend fun currentTamaMessages(limit: Int = 30): TamaMessagePage {
        val petId = tamaDatabase.tamaDao().getActivePet()?.id
        val messages = petId?.let { tamaDatabase.tamaDao().getChatHistory(it).takeLast(limit).map { msg -> msg.toWearMessage() } }
            ?: emptyList()
        return TamaMessagePage(revisioned = revisioned(), petId = petId, messages = messages, limit = limit)
    }

    private suspend fun currentTranslatorState(
        template: TranslatorTemplateSummary? = null,
        backendLoading: Boolean = false,
        phaseOverride: String? = null,
        statusOverride: String? = null
    ): TranslatorStateSnapshot {
        val state = LiveTranslatorService.state.value
        val resolvedTemplate = template
            ?: state.templateId.takeIf { it > 0L }
                ?.let { AppDatabase.getDatabase(appContext).liveTranslatorTemplateDao().getTemplateById(it) }
                ?.toWearTemplate()
            ?: pendingTranslatorTemplate?.takeIf { pending -> pending.id == state.templateId || state.templateId <= 0L }
        return TranslatorStateSnapshot(
            revisioned = revisioned(),
            isActive = state.isActive,
            sessionId = state.sessionId,
            templateId = state.templateId,
            currentSpeaker = state.currentSpeaker,
            phase = phaseOverride ?: state.phase.name,
            status = statusOverride ?: state.status,
            elapsedSeconds = state.elapsedSeconds,
            inputLevel = state.inputLevel,
            error = state.error,
            selectedTemplateId = resolvedTemplate?.id,
            selectedTemplateName = resolvedTemplate?.name,
            backendEngine = resolvedTemplate?.backendEngine.orEmpty(),
            backendLabel = resolvedTemplate?.backendLabel.orEmpty(),
            modelLabel = resolvedTemplate?.modelLabel.orEmpty(),
            backendLoading = backendLoading || (state.isActive && state.phase.name in setOf("IDLE", "WAITING")),
            backendStatus = statusOverride ?: state.status
        )
    }

    private suspend fun selectedServerId(): Long? {
        val stored = selectedServerPrefs.getLong("selected_server_id", -1L).takeIf { it > 0L }
        return stored ?: AppDatabase.getDatabase(appContext).llamaServerDao().getLastUsedServer()?.id
    }

    internal fun LlamaServerEntity.canAutoStartLocalLlamaServer(): Boolean {
        if (!isLlamaServerEngine()) return false
        val normalizedHost = host
            .trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .let { authority ->
                when {
                    authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
                    authority.count { it == ':' } == 1 -> authority.substringBefore(':')
                    else -> authority
                }
            }
            .lowercase(Locale.ROOT)
        return normalizedHost in setOf("localhost", "127.0.0.1", "::1")
    }

    private suspend fun currentChatPage(chatId: Long, limit: Int = 30): ChatMessagePage {
        val all = repository.getMessagesOnce(chatId)
        return ChatMessagePage(
            revisioned = revisioned(),
            chatId = chatId,
            messages = all.takeLast(limit).map { it.toWearMessage() },
            nextBeforeMessageId = all.dropLast(limit).lastOrNull()?.id,
            limit = limit
        )
    }

    private fun emptyChatPage(chatId: Long): ChatMessagePage =
        ChatMessagePage(revisioned(), chatId, emptyList(), nextBeforeMessageId = null, limit = 30)

    private suspend fun updateChatThinking(chatId: Long, enableThinking: Boolean) {
        val chat = repository.getChat(chatId) ?: return
        val json = chat.apiParams.orEmpty().trim()
        val obj = if (json.isBlank()) JSONObject() else runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        obj.put("enable_thinking", enableThinking)
        repository.updateChatApiParams(chatId, obj.toString())
    }

    private fun validateVoiceMetadata(metadata: VoiceAssetMetadata) {
        require(metadata.byteCount in 1..AdtWearProtocol.MAX_VOICE_BYTES) { appContext.getString(R.string.wear_audio_too_large) }
        require(metadata.durationMs in 1..AdtWearProtocol.MAX_VOICE_DURATION_MS) { appContext.getString(R.string.wear_audio_too_long) }
        require(metadata.sha256.length == 64) { appContext.getString(R.string.wear_audio_hash_mismatch) }
    }

    private suspend fun replaceEventAlarm(dao: OrganizerDao, event: OrganizerEventEntity, alarmAtEpochMs: Long?) {
        dao.getAlarmsForEventOnce(event.id).forEach { alarm ->
            OrganizerAlarmScheduler.cancelAlarm(appContext, alarm.id)
            dao.deleteAlarmById(alarm.id)
        }
        val trigger = alarmAtEpochMs?.takeIf { it > System.currentTimeMillis() } ?: return
        val alarm = OrganizerAlarmEntity(
            eventId = event.id,
            title = event.title,
            message = event.description.ifBlank { event.location },
            triggerAtMillis = trigger,
            timezoneId = event.timezoneId
        )
        val alarmId = dao.insertAlarm(alarm)
        OrganizerAlarmScheduler.scheduleAlarm(appContext, alarm.copy(id = alarmId))
    }

    private fun copyAssetToPrivateFile(asset: Asset, metadata: VoiceAssetMetadata): File {
        val response = Tasks.await(Wearable.getDataClient(appContext).getFdForAsset(asset))
        val dir = File(appContext.filesDir, "wear_audio").apply { mkdirs() }
        val file = File(dir, "adt_${metadata.meta.requestId}_${System.currentTimeMillis()}.m4a")
        response.inputStream.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        require(file.length() == metadata.byteCount) { appContext.getString(R.string.wear_audio_size_mismatch) }
        return file
    }

    private fun sourceDeviceId(): String = appContext.packageName
    private fun revisioned(): Revisioned = Revisioned(revision.incrementAndGet(), System.currentTimeMillis(), sourceDeviceId())

    private fun decodeMetaOrFallback(request: ByteArray): RpcMeta =
        runCatching { AdtWearProtocol.json.decodeFromString(PingRequest.serializer(), request.toString(Charsets.UTF_8)).meta }
            .getOrDefault(RpcMeta(UUID.randomUUID().toString(), createdAtEpochMs = System.currentTimeMillis()))

    private fun <T> decode(serializer: KSerializer<T>, request: ByteArray): T =
        AdtWearProtocol.json.decodeFromString(serializer, request.toString(Charsets.UTF_8))

    private fun <T> ok(meta: RpcMeta, serializer: KSerializer<T>, result: T): ByteArray =
        AdtWearProtocol.json.encodeToString(
            RpcResponse.serializer(serializer),
            RpcResponse(meta, status = "success", result = result, bridgeState = serverController.bridgeState(), phoneVersionCode = BuildConfig.VERSION_CODE, phoneVersionName = BuildConfig.VERSION_NAME, snapshotRevision = publisher.lastDataItemRevision ?: 0L, updatedAtEpochMs = System.currentTimeMillis())
        ).toByteArray(Charsets.UTF_8)

    private fun errorBytes(meta: RpcMeta, code: String, message: String): ByteArray {
        publisher.lastRpcError = "$code: $message"
        return AdtWearProtocol.json.encodeToString(
            RpcResponse.serializer(EmptyResult.serializer()),
            RpcResponse(meta, status = "error", error = RpcError(code, message, retryable = true), bridgeState = serverController.bridgeState(), phoneVersionCode = BuildConfig.VERSION_CODE, phoneVersionName = BuildConfig.VERSION_NAME, snapshotRevision = publisher.lastDataItemRevision ?: 0L, updatedAtEpochMs = System.currentTimeMillis())
        ).toByteArray(Charsets.UTF_8)
    }
}

class WearStatePublisher(
    private val appContext: Context,
    private val revision: AtomicLong
) {
    @Volatile var lastDataItemPath: String? = null
    @Volatile var lastDataItemRevision: Long? = null
    @Volatile var lastRpcError: String? = null

    fun publishServer(snapshot: LlamaServerSnapshot) {
        publishData(AdtWearProtocol.SERVER_STATUS, LlamaServerSnapshot.serializer(), snapshot)
    }

    fun publishPet(snapshot: PetSnapshot) {
        publishData(AdtWearProtocol.PET_CURRENT, PetSnapshot.serializer(), snapshot)
    }

    fun publishTamaMessages(page: TamaMessagePage) {
        publishData(AdtWearProtocol.TAMA_MESSAGES, TamaMessagePage.serializer(), page)
    }

    fun publishGenerationFinal(final: GenerationFinal) {
        publishData(AdtWearProtocol.generationFinalPath(final.generationId), GenerationFinal.serializer(), final)
    }

    fun publishVoiceAck(ack: VoiceUploadAck) {
        publishData(AdtWearProtocol.voiceAckPath(ack.requestId), VoiceUploadAck.serializer(), ack)
    }

    fun publishTranslatorState(state: TranslatorStateSnapshot) {
        publishData(AdtWearProtocol.TRANSLATOR_STATE, TranslatorStateSnapshot.serializer(), state)
    }

    fun publishTranslatorTurns(page: TranslatorTurnPage) {
        publishData(AdtWearProtocol.translatorTurnsPath(page.sessionId), TranslatorTurnPage.serializer(), page)
    }

    fun publishCapabilities(capabilities: WearCapabilities) {
        publishData(AdtWearProtocol.CAPABILITIES, WearCapabilities.serializer(), capabilities)
    }

    fun publishActiveTasks(snapshot: ActiveTaskSnapshot) {
        publishData(AdtWearProtocol.ACTIVE_TASKS, ActiveTaskSnapshot.serializer(), snapshot)
    }

    fun publishStats(snapshot: WearStatsSnapshot) {
        publishData(AdtWearProtocol.STATS, WearStatsSnapshot.serializer(), snapshot)
    }

    fun publishPinnedNote(result: OrganizerPinnedNoteResult) {
        publishData(AdtWearProtocol.ORGANIZER_PINNED_NOTE, OrganizerPinnedNoteResult.serializer(), result)
    }

    fun publishTtsResult(result: TtsAudioResult) {
        publishData(AdtWearProtocol.ttsAudioPath(result.requestId), TtsAudioResult.serializer(), result)
    }

    fun publishTtsAudio(requestId: String, file: File) {
        val result = TtsAudioResult(
            requestId = requestId,
            status = "complete",
            localizedMessage = appContext.getString(R.string.wear_tts_phone_ready),
            mimeType = if (file.extension.equals("mp3", ignoreCase = true)) "audio/mpeg" else "audio/wav",
            byteCount = file.length(),
            sha256 = file.sha256()
        )
        val json = AdtWearProtocol.json.encodeToString(TtsAudioResult.serializer(), result)
        val revisionValue = revision.incrementAndGet()
        val request = PutDataMapRequest.create(AdtWearProtocol.ttsAudioPath(requestId)).apply {
            dataMap.putString(AdtWearProtocol.KEY_JSON, json)
            dataMap.putAsset(AdtWearProtocol.KEY_ASSET, Asset.createFromBytes(file.readBytes()))
            dataMap.putLong(AdtWearProtocol.KEY_REVISION, revisionValue)
            dataMap.putLong("published_at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(appContext).putDataItem(request)
            .addOnSuccessListener {
                lastDataItemPath = AdtWearProtocol.ttsAudioPath(requestId)
                lastDataItemRevision = revisionValue
                Log.i("ADT-WEAR-DATA", "published tts request=$requestId bytes=${file.length()}")
            }
            .addOnFailureListener { error ->
                lastRpcError = "putTtsAsset_failed: ${error.javaClass.simpleName}:${error.message}"
            }
    }

    fun publishMediaAsset(result: WearMediaResult, file: File) {
        val json = AdtWearProtocol.json.encodeToString(WearMediaResult.serializer(), result)
        val revisionValue = revision.incrementAndGet()
        val request = PutDataMapRequest.create(AdtWearProtocol.mediaAssetPath(result.mediaPathId)).apply {
            dataMap.putString(AdtWearProtocol.KEY_JSON, json)
            dataMap.putAsset(AdtWearProtocol.KEY_ASSET, Asset.createFromBytes(file.readBytes()))
            dataMap.putLong(AdtWearProtocol.KEY_REVISION, revisionValue)
            dataMap.putLong("published_at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(appContext).putDataItem(request)
            .addOnSuccessListener {
                lastDataItemPath = AdtWearProtocol.mediaAssetPath(result.mediaPathId)
                lastDataItemRevision = revisionValue
                Log.i("ADT-WEAR-DATA", "published media id=${result.mediaId} bytes=${file.length()}")
            }
            .addOnFailureListener { error ->
                lastRpcError = "putMediaAsset_failed: ${error.javaClass.simpleName}:${error.message}"
            }
    }

    private fun <T> publishData(path: String, serializer: KSerializer<T>, payload: T) {
        val json = AdtWearProtocol.json.encodeToString(serializer, payload)
        if (json.toByteArray(Charsets.UTF_8).size > AdtWearProtocol.MAX_DATA_ITEM_BYTES) {
            lastRpcError = "payload_too_large: $path"
            Log.w("ADT-WEAR-DATA", "skip oversized path=$path bytes=${json.length}")
            return
        }
        val revisionValue = revision.incrementAndGet()
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putString(AdtWearProtocol.KEY_JSON, json)
            dataMap.putLong(AdtWearProtocol.KEY_REVISION, revisionValue)
            dataMap.putLong("published_at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(appContext).putDataItem(request)
            .addOnSuccessListener {
                lastDataItemPath = path
                lastDataItemRevision = revisionValue
                Log.i("ADT-WEAR-DATA", "published path=$path bytes=${json.toByteArray(Charsets.UTF_8).size}")
            }
            .addOnFailureListener { error ->
                lastRpcError = "putDataItem_failed: ${error.javaClass.simpleName}:${error.message}"
                Log.w("ADT-WEAR-DATA", "publish failed path=$path ${error.javaClass.simpleName}:${error.message}")
            }
    }
}

class WearDeltaEmitter(private val appContext: Context) {
    fun emit(nodeId: String?, delta: GenerationDelta) {
        if (nodeId.isNullOrBlank()) return
        val bytes = AdtWearProtocol.json.encodeToString(GenerationDelta.serializer(), delta).toByteArray(Charsets.UTF_8)
        if (bytes.size > AdtWearProtocol.MAX_RPC_BYTES) return
        Wearable.getMessageClient(appContext).sendMessage(nodeId, AdtWearProtocol.GENERATION_DELTA, bytes)
            .addOnSuccessListener { Log.i("ADT-WEAR-RPC", "delta queued generation=${delta.generationId} seq=${delta.sequence} bytes=${bytes.size}") }
            .addOnFailureListener { Log.w("ADT-WEAR-RPC", "delta failed generation=${delta.generationId} ${it.javaClass.simpleName}:${it.message}") }
    }
}

class LlamaServerController(
    private val appContext: Context,
    private val settingsRepo: SettingsRepository,
    private val publisher: WearStatePublisher
) {
    private val mutex = Mutex()
    private val requestCache = Collections.synchronizedMap(object : LinkedHashMap<String, ServerCommandResult>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ServerCommandResult>?): Boolean = size > 32
    })

    /**
     * Retry a start that Android previously refused, now that the user has
     * confirmed it in the foreground.
     *
     * The eviction matters: [start] short-circuits on [requestCache], and the
     * blocked attempt already cached a `PHONE_CONFIRMATION_REQUIRED` result under
     * this same id. Without dropping that entry the confirmation replays the
     * cached refusal and the server is never actually started.
     */
    suspend fun confirmPendingStart(requestId: String): ServerCommandResult {
        requestCache.remove(requestId)
        return start(requestId)
    }

    /**
     * Resolve the one server card the user allowed the watch to start.
     *
     * Returns null when no card is flagged, which is a normal state the watch has
     * to be told about rather than an error to swallow.
     */
    private suspend fun wearStartCard(): LlamaServerCardSnapshot? {
        val db = AppDatabase.getDatabase(appContext)
        val card = db.llamaServerCardDao().getWearStartCard() ?: return null
        wearSessionId = card.sessionId
        val preset = db.savedCommandDao().getGeneralCommandById(card.savedCommandId)
        return LlamaServerCardSnapshot(card, preset)
    }

    suspend fun start(requestId: String): ServerCommandResult = mutex.withLock {
        requestCache[requestId]?.let { return@withLock it }

        // Route through the card session the user opted in, so the resulting server
        // is one the phone UI owns and can stop. The old path started an unmanaged
        // server that the phone had no handle on.
        val target = wearStartCard()
            ?: return@withLock cacheResult(
                requestId,
                "FAILED_NO_WEAR_SERVER",
                currentSnapshot(),
                appContext.getString(R.string.wear_bridge_no_wear_server)
            )
        val profile = target.resolveProfile()
            ?: return@withLock cacheResult(
                requestId,
                "FAILED_NO_WEAR_PRESET",
                currentSnapshot(),
                appContext.getString(R.string.wear_bridge_wear_server_missing_preset)
            )

        val existing = sessionSnapshot(target.sessionId)
        if (existing != null && (existing.isRunning || existing.isBusy)) {
            return@withLock cacheResult(requestId, "ALREADY_RUNNING", currentSnapshot(), appContext.getString(R.string.wear_bridge_server_already_running))
        }

        val result = LlamaServerLauncher.startSession(
            context = appContext,
            sessionId = target.sessionId,
            profile = profile,
            portOverride = target.port
        )
        result.fold(
            onSuccess = {
                val snapshot = waitForNonStoppedSnapshot() ?: currentSnapshot()
                val code = if (snapshot.state == "running") "ACCEPTED" else "ACCEPTED"
                cacheResult(requestId, code, snapshot, appContext.getString(R.string.wear_bridge_server_start_sent))
            },
            onFailure = { error ->
                val confirmationUri = maybePostConfirmationNotification(requestId, error)
                val code = if (confirmationUri != null) "PHONE_CONFIRMATION_REQUIRED" else "FAILED_START"
                cacheResult(
                    requestId,
                    code,
                    currentSnapshot(error.wearMessage(appContext)),
                    if (confirmationUri != null) appContext.getString(R.string.wear_bridge_server_confirmation_required) else error.wearMessage(appContext),
                    confirmationUri
                )
            }
        )
    }

    suspend fun stop(requestId: String): ServerCommandResult = mutex.withLock {
        requestCache[requestId]?.let { return@withLock it }

        val target = wearStartCard()
            ?: return@withLock cacheResult(
                requestId,
                "FAILED_NO_WEAR_SERVER",
                currentSnapshot(),
                appContext.getString(R.string.wear_bridge_no_wear_server)
            )
        val existing = sessionSnapshot(target.sessionId)
        if (existing == null || (!existing.isRunning && !existing.isBusy)) {
            return@withLock cacheResult(requestId, "ALREADY_STOPPED", currentSnapshot(), appContext.getString(R.string.wear_bridge_server_already_stopped))
        }

        // Stop only this session; never the same-UID sweep, which would also kill
        // servers the user started from the phone for other purposes.
        val result = LlamaServerLauncher.stopSession(appContext, target.sessionId)
        result.fold(
            onSuccess = {
                val stopped = withTimeoutOrNull(5_000L) {
                    while (sessionSnapshot(target.sessionId)?.let { it.isRunning || it.isBusy } == true) {
                        delay(250L)
                    }
                    true
                }
                cacheResult(requestId, "ACCEPTED", currentSnapshot(), if (stopped != null) appContext.getString(R.string.wear_bridge_server_stop_sent) else appContext.getString(R.string.wear_bridge_server_stopping))
            },
            onFailure = { error ->
                cacheResult(requestId, "FAILED_STOP", currentSnapshot(error.wearMessage(appContext)), error.wearMessage(appContext))
            }
        )
    }

    /** Session id of the wear-startable card, cached so the non-suspend [currentSnapshot] can use it. */
    @Volatile
    private var wearSessionId: String? = null

    private fun sessionSnapshot(sessionId: String): LlamaServerSessionSnapshot? =
        runCatching { LlamaServerSessionStateStore(appContext).readAll().firstOrNull { it.sessionId == sessionId } }
            .getOrNull()

    fun currentSnapshot(errorOverride: String? = null): LlamaServerSnapshot {
        val revisioned = Revisioned(System.currentTimeMillis(), System.currentTimeMillis(), appContext.packageName)

        // Prefer the wear-startable card's session, so the watch reflects the server
        // it actually controls rather than the legacy singleton LlamaService state.
        wearSessionId?.let { sessionId ->
            sessionSnapshot(sessionId)?.let { session ->
                return when (session.status) {
                    LlamaServerSessionStatus.STOPPED ->
                        LlamaServerSnapshot(revisioned, "stopped", appContext.getString(R.string.status_stopped), error = errorOverride)
                    LlamaServerSessionStatus.STARTING ->
                        LlamaServerSnapshot(revisioned, "starting", appContext.getString(R.string.dashboard_starting), error = errorOverride)
                    LlamaServerSessionStatus.LOADING ->
                        LlamaServerSnapshot(revisioned, "loading", session.statusText ?: appContext.getString(R.string.dashboard_starting), progress = session.progress, error = errorOverride)
                    LlamaServerSessionStatus.RUNNING ->
                        LlamaServerSnapshot(revisioned, "running", appContext.getString(R.string.status_running), port = session.port, error = errorOverride)
                    LlamaServerSessionStatus.ERROR ->
                        LlamaServerSnapshot(revisioned, "error", appContext.getString(R.string.status_error), error = errorOverride ?: session.error)
                }
            }
        }

        val state = LlamaService.state.value
        return when (state) {
            ServerState.Stopped -> LlamaServerSnapshot(revisioned, "stopped", appContext.getString(R.string.status_stopped), error = errorOverride)
            ServerState.Starting -> LlamaServerSnapshot(revisioned, "starting", appContext.getString(R.string.dashboard_starting), error = errorOverride)
            is ServerState.Loading -> LlamaServerSnapshot(revisioned, "loading", state.status, progress = state.progress, error = errorOverride)
            is ServerState.Running -> LlamaServerSnapshot(revisioned, "running", appContext.getString(R.string.status_running), port = state.port, error = errorOverride)
            is ServerState.Error -> LlamaServerSnapshot(revisioned, "error", appContext.getString(R.string.status_error), error = errorOverride ?: state.message)
        }
    }

    fun bridgeState(): BridgeState {
        val snapshot = currentSnapshot()
        return BridgeState(
            serverState = snapshot.state,
            serverLabel = snapshot.label,
            lastDataItemPath = publisher.lastDataItemPath,
            lastErrorCode = publisher.lastRpcError
        )
    }

    private suspend fun waitForNonStoppedSnapshot(): LlamaServerSnapshot? =
        withTimeoutOrNull(2_500L) {
            LlamaService.state.filter { it !is ServerState.Stopped }.first()
            currentSnapshot()
        }

    private fun cacheResult(
        requestId: String,
        resultCode: String,
        snapshot: LlamaServerSnapshot,
        message: String,
        confirmationUri: String? = null
    ): ServerCommandResult {
        publisher.publishServer(snapshot)
        val result = ServerCommandResult(requestId, resultCode, snapshot, confirmationUri, message)
        requestCache[requestId] = result
        return result
    }

    private fun maybePostConfirmationNotification(requestId: String, error: Throwable): String? {
        val blocked = if (Build.VERSION.SDK_INT >= 31) error is android.app.ForegroundServiceStartNotAllowedException else false
        if (!blocked && error !is IllegalStateException) return null
        // Only ids registered here may later start the server; see
        // PhoneWearGateway.confirmServerStart.
        PhoneWearGateway.markPendingServerConfirmation(requestId)
        val uri = "adt://wear-confirm/server/start?requestId=$requestId"
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val channelId = "wear_server_control"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, appContext.getString(R.string.wear_server_confirm_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val intent = Intent(appContext, WearServerStartConfirmationActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(uri)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("request_id", requestId)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            requestId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(appContext.getString(R.string.wear_server_confirm_title))
            .setContentText(appContext.getString(R.string.wear_server_confirm_message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, appContext.getString(R.string.wear_server_confirm_action), pendingIntent)
            .build()
        manager.notify(0xAD7001, notification)
        return uri
    }
}

class WearServerStartConfirmationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This Activity is exported and BROWSABLE so the watch can reach it via
        // RemoteActivityHelper, which means an arbitrary app or web page can also
        // launch it. Do not start the gateway here, and do not invent a request
        // id when none was supplied: an id we did not mint must not be treated as
        // a pending confirmation. Validation itself lives in
        // PhoneWearGateway.confirmServerStart.
        val requestId = intent.getStringExtra("request_id")
            ?: intent.data?.getQueryParameter("requestId")
            ?: ""
        AlertDialog.Builder(this)
            .setTitle(R.string.wear_server_confirm_title)
            .setMessage(R.string.wear_server_confirm_message)
            .setPositiveButton(R.string.wear_server_confirm_action) { _, _ ->
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    val result = PhoneWearGateway.confirmServerStart(applicationContext, requestId)
                    if (result == null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                applicationContext,
                                R.string.wear_server_confirm_expired,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}

private fun LlamaServerEntity.toServerSummary(selectedServerId: Long?): ServerSummary =
    ServerSummary(
        id = id,
        name = name,
        engine = normalizedEngine(),
        endpoint = if (isLiteRtEngine()) normalizedEngine() else baseUrl(),
        modelName = modelName,
        supportsAudio = supportsAudio,
        selected = id == selectedServerId
    )

private fun com.example.llamadroid.data.model.LlamaChatEntity.toChatSummary(
    folderName: String?,
    messages: List<LlamaMessageEntity>
): ChatSummary =
    ChatSummary(
        id = id,
        title = title,
        folderId = folderId,
        folderName = folderName,
        lastModifiedEpochMs = lastModified,
        pinned = pinnedToAiHub,
        pinnedServerId = pinnedServerId,
        preview = messages.lastOrNull { !it.isError }?.content.orEmpty().wearLimit(96),
        messageCount = messages.size
    )

private fun LlamaMessageEntity.toWearMessage(): WearMessage =
    WearMessage(
        id = id.toString(),
        role = role,
        content = content.wearLimit(8_000),
        timestampEpochMs = timestamp,
        thinking = thinking?.wearLimit(8_000),
        hasAudio = !audioPath.isNullOrBlank(),
        audioMediaId = id.takeIf { !audioPath.isNullOrBlank() }?.let { "chat:$it:audio" },
        hasImage = !imagePath.isNullOrBlank(),
        imageMediaId = id.takeIf { !imagePath.isNullOrBlank() }?.let { "chat:$it:image" },
        speechText = content.toSpeechText().wearLimit(4_000),
        error = if (isError) content.wearLimit(8_000) else null,
        canRetry = role == "user",
        canDelete = true,
        isError = isError
    )

private fun TamaChatMessageEntity.toWearMessage(): WearMessage =
    WearMessage(
        id = id,
        role = role,
        content = (transcribedText?.takeIf { role == "user" && it.isNotBlank() } ?: content).wearLimit(8_000),
        timestampEpochMs = timestamp,
        thinking = thinking?.wearLimit(8_000),
        hasAudio = !audioPath.isNullOrBlank(),
        audioMediaId = id.takeIf { !audioPath.isNullOrBlank() }?.let { "tama:$it:audio" },
        hasImage = !imagePath.isNullOrBlank(),
        imageMediaId = id.takeIf { !imagePath.isNullOrBlank() }?.let { "tama:$it:image" },
        speechText = (transcribedText?.takeIf { role == "user" && it.isNotBlank() } ?: content).toSpeechText().wearLimit(4_000),
        error = transcriptionError?.wearLimit(8_000),
        canRetry = false,
        canDelete = false,
        isError = transcriptionError != null
    )

private fun OrganizerEventEntity.toWearEvent(alarmCount: Int): OrganizerEventSummary =
    OrganizerEventSummary(
        id = id,
        title = title.wearLimit(96),
        description = description.wearLimit(800),
        location = location.wearLimit(120),
        startAtEpochMs = startAtMillis,
        endAtEpochMs = endAtMillis,
        allDay = allDay,
        alarmCount = alarmCount
    )

private fun OrganizerAlarmEntity.toWearAlarm(): OrganizerAlarmSummary =
    OrganizerAlarmSummary(
        id = id,
        eventId = eventId,
        title = title.wearLimit(96),
        message = message.wearLimit(240),
        triggerAtEpochMs = triggerAtMillis,
        timezoneId = timezoneId,
        soundEnabled = soundEnabled,
        enabled = enabled,
        deliveredAtEpochMs = deliveredAt
    )

private fun NoteEntity.toWearNoteSummary(): OrganizerNoteSummary =
    OrganizerNoteSummary(
        id = id,
        title = title.wearLimit(96),
        preview = content.wearLimit(160),
        type = type.name,
        updatedAtEpochMs = updatedAt,
        hasAudio = !audioPath.isNullOrBlank(),
        audioMediaId = id.takeIf { !audioPath.isNullOrBlank() }?.let { "note:$it:audio" }
    )

private fun NoteEntity.toWearNoteDetail(revisioned: Revisioned): OrganizerNoteDetail =
    OrganizerNoteDetail(
        revisioned = revisioned,
        id = id,
        title = title.wearLimit(120),
        content = content.wearLimit(4_000),
        type = type.name,
        updatedAtEpochMs = updatedAt,
        hasAudio = !audioPath.isNullOrBlank(),
        audioMediaId = id.takeIf { !audioPath.isNullOrBlank() }?.let { "note:$it:audio" }
    )

private fun LiveTranslatorTemplateEntity.toWearTemplate(): TranslatorTemplateSummary =
    TranslatorTemplateSummary(
        id = id,
        name = name.wearLimit(80),
        speaker1Language = speaker1Language,
        speaker2Language = speaker2Language,
        backendEngine = backendEngine,
        backendLabel = backendEngine.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) },
        modelLabel = when (backendEngine) {
            "ollama" -> ollamaModelName.orEmpty()
            "litert-lm" -> liteRtModelId?.toString().orEmpty()
            "llama-swap" -> llamaModelName ?: llamaSwapUrl
            else -> llamaModelName ?: llamaServerUrl
        }.wearLimit(80)
    )

private fun LiveTranslatorTurnEntity.toWearTranslatorTurn(): TranslatorTurnSummary =
    TranslatorTurnSummary(
        id = id,
        speaker = speaker,
        originalText = originalText.wearLimit(1_000),
        translatedText = translatedText?.wearLimit(1_000),
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        timestampEpochMs = timestamp,
        isError = isError,
        errorMessage = errorMessage?.wearLimit(1_000)
    )

private data class PendingVoiceUpload(
    val metadata: VoiceAssetMetadata,
    val file: File,
    val receivedAtEpochMs: Long
)

private fun TamaPet.toPetSnapshot(context: Context, sourceDeviceId: String, revision: Long): PetSnapshot {
    val activity = tamaActivityKey()
    val spriteState = mapPetActionToSpriteState(activity, isSleeping)
    val sprite = resolvePetSpriteAssetPath(
        speciesLine = PetSpeciesLine.fromSpeciesId(species, genetics.bodyStyle),
        stage = stage,
        state = spriteState,
        frameIndex = 0
    )
    return PetSnapshot(
        revisioned = Revisioned(revision, System.currentTimeMillis(), sourceDeviceId),
        hasPet = true,
        petId = id,
        name = name,
        species = species,
        stage = stage.name,
        mood = mood.name,
        moodLabel = if (isMad) context.getString(R.string.widget_tama_mood_mad) else mood.emoji,
        activity = activity,
        activityLabel = tamaActivityLabel(context),
        location = currentLocationId,
        hungerPercent = stats.hunger.toInt().coerceIn(0, 100),
        happinessPercent = stats.happiness.toInt().coerceIn(0, 100),
        healthPercent = stats.health.toInt().coerceIn(0, 100),
        energyPercent = stats.energy.toInt().coerceIn(0, 100),
        hygienePercent = stats.hygiene.toInt().coerceIn(0, 100),
        spriteAssetId = sprite,
        backgroundAssetId = tamaBackgroundAsset(),
        frozen = cycleFrozen
    )
}

private fun TamaPet.tamaActivityKey(): String = when {
    cycleFrozen -> "frozen"
    isSleeping -> "sleeping"
    currentActivity == ActivityType.WORKING -> "working"
    currentActivity == ActivityType.STUDYING -> "studying"
    currentActivity == ActivityType.TRAINING -> "training"
    currentActivity == ActivityType.RELAXING -> "relaxing"
    else -> "idle"
}

private fun TamaPet.tamaActivityLabel(context: Context): String = when (tamaActivityKey()) {
    "frozen" -> context.getString(R.string.wear_tama_activity_frozen)
    "sleeping" -> context.getString(R.string.wear_tama_activity_sleeping)
    "working" -> context.getString(R.string.wear_tama_activity_working)
    "studying" -> context.getString(R.string.wear_tama_activity_studying)
    "training" -> context.getString(R.string.wear_tama_activity_training)
    "relaxing" -> context.getString(R.string.wear_tama_activity_relaxing)
    else -> context.getString(R.string.wear_tama_activity_idle)
}

private fun TamaPet.tamaBackgroundAsset(): String {
    if (isSleeping) return "tama/backgrounds/bedroom.png"
    return when (currentActivity) {
        ActivityType.WORKING -> TamaWorkCatalog.jobById(currentWorkJobId)?.backgroundAssetPath ?: "tama/backgrounds/workplace.png"
        ActivityType.STUDYING -> "tama/backgrounds/classroom.png"
        ActivityType.TRAINING -> TamaTrainingCatalog.tierById(currentWorkJobId)?.backgroundAssetPath ?: "tama/backgrounds/boxing_ring.png"
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

private fun Throwable.stableCode(): String = when (this) {
    is IllegalArgumentException -> "invalid_argument"
    is IllegalStateException -> "invalid_state"
    else -> javaClass.simpleName.ifBlank { "error" }.lowercase()
}

private fun Throwable.wearMessage(context: Context): String {
    val detail = message.orEmpty()
    return if (detail.isBlank()) {
        context.getString(R.string.wear_bridge_command_failed_with_type, javaClass.simpleName)
    } else {
        context.getString(R.string.wear_bridge_command_failed_with_detail, javaClass.simpleName, detail)
    }
}

private fun String.wearLimit(maxChars: Int): String =
    if (length <= maxChars) this else take(maxChars).trimEnd() + "..."

private fun String.toWearMediaPathId(): String =
    map { char -> if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.') char else '_' }
        .joinToString("")
        .take(120)
        .ifBlank { UUID.randomUUID().toString() }

private fun String.toSpeechText(): String =
    replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("[*_~>#]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

@Suppress("DEPRECATION")
internal fun signingSha256Short(context: Context): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }
    val certBytes = if (Build.VERSION.SDK_INT >= 28) {
        packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
    } else {
        packageInfo.signatures?.firstOrNull()?.toByteArray()
    } ?: return ""
    return MessageDigest.getInstance("SHA-256")
        .digest(certBytes)
        .joinToString("") { "%02x".format(it) }
        .take(16)
}

private data class WearResolvedMedia(
    val file: File,
    val type: String,
    val mimeType: String
)
