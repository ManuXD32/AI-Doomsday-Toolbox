package com.example.llamadroid.wear

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.items
import androidx.wear.compose.material.rememberScalingLazyListState
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import androidx.wear.remote.interactions.RemoteActivityHelper
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val RPC_TIMEOUT_MS = 18_000L
private const val AUTO_SYNC_COOLDOWN_MS = 120_000L

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {
    private lateinit var store: WearCompanionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WearCompanionStore(this)
        val initialRoute = intent.getStringExtra(EXTRA_OPEN_ROUTE).toWearRoute()
        val initialChatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L).takeIf { it > 0L }
        setContent { WearCompanionApp(store, initialRoute, initialChatId) }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        Wearable.getMessageClient(this).addListener(this)
        Wearable.getCapabilityClient(this).addLocalCapability(AdtWearProtocol.WATCH_CAPABILITY)
        store.loadCache()
        store.loadExistingData()
        store.requestRefresh(auto = true)
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        store.close()
        super.onDestroy()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) store.consumeDataItem(event.dataItem)
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        store.consumeMessage(messageEvent)
    }

    companion object {
        const val EXTRA_OPEN_ROUTE = "com.example.llamadroid.wear.OPEN_ROUTE"
        const val EXTRA_CHAT_ID = "com.example.llamadroid.wear.CHAT_ID"
        const val ROUTE_HOME = "home"
        const val ROUTE_CHATS = "chats"
        const val ROUTE_CHAT_DETAIL = "chat_detail"
        const val ROUTE_PET = "pet"
        const val ROUTE_CALENDAR = "calendar"
        const val ROUTE_TASKS = "tasks"
        const val ROUTE_TRANSLATE = "translate"
        const val ROUTE_STATS = "stats"
        const val ROUTE_DIAGNOSTICS = "diagnostics"
    }
}

private class WearCompanionStore(private val activity: Activity) {
    var serverSnapshot by mutableStateOf<LlamaServerSnapshot?>(null)
    var serverPage by mutableStateOf<ServerListPage?>(null)
    var chatPage by mutableStateOf<ChatListPage?>(null)
    var petSnapshot by mutableStateOf<PetSnapshot?>(null)
    var tamaMessages by mutableStateOf<TamaMessagePage?>(null)
    var organizerEvents by mutableStateOf<OrganizerEventPage?>(null)
    var organizerMonth by mutableStateOf<OrganizerMonthPage?>(null)
    var organizerAlarms by mutableStateOf<OrganizerAlarmPage?>(null)
    var organizerNotes by mutableStateOf<OrganizerNotePage?>(null)
    var selectedNoteDetail by mutableStateOf<OrganizerNoteDetail?>(null)
    var tamaHub by mutableStateOf<TamaHubSnapshot?>(null)
    var tamaInventory by mutableStateOf<TamaInventoryPage?>(null)
    var tamaStore by mutableStateOf<TamaStorePage?>(null)
    var tamaFarm by mutableStateOf<TamaFarmSnapshot?>(null)
    var tamaAdventure by mutableStateOf<TamaAdventureSnapshot?>(null)
    var tamaRpg by mutableStateOf<TamaRpgSnapshot?>(null)
    var tamaArcade by mutableStateOf<TamaArcadeSnapshot?>(null)
    var translatorTemplates by mutableStateOf<TranslatorTemplatePage?>(null)
    var translatorState by mutableStateOf<TranslatorStateSnapshot?>(null)
    var translatorTurns by mutableStateOf<TranslatorTurnPage?>(null)
    var activeTasks by mutableStateOf<ActiveTaskSnapshot?>(null)
    var statsSnapshot by mutableStateOf<WearStatsSnapshot?>(null)
    var capabilities by mutableStateOf<WearCapabilities?>(null)
    var translatorStartPending by mutableStateOf(false)
    var translatorStopPending by mutableStateOf(false)
    var lastError by mutableStateOf<String?>(null)
    var lastCommandMessage by mutableStateOf<String?>(null)
    var audioRecordingActive by mutableStateOf(false)
    var autoReadAnswers by mutableStateOf(false)
    var phoneTtsEnabled by mutableStateOf(false)
    var vibrateOnAnswer by mutableStateOf(true)
    var thinkingEnabled by mutableStateOf(true)
    var watchPinnedChatIds by mutableStateOf<Set<Long>>(emptySet())
    var pendingServerId by mutableStateOf<Long?>(null)
    var phoneReachable by mutableStateOf(false)
    var genericPhoneReachable by mutableStateOf(false)
    var bridgeUnavailable by mutableStateOf(false)
    var syncing by mutableStateOf(false)
    var inFlightRequestId by mutableStateOf<String?>(null)
    var connectedNodeCount by mutableStateOf(0)
    var bridgeNodeCount by mutableStateOf(0)
    var nearbyBridgeNodeCount by mutableStateOf(0)
    var reciprocalWearNodeCount by mutableStateOf(0)
    var capabilitiesSummary by mutableStateOf("-")
    var localNodeId by mutableStateOf("-")
    var lastPingAt by mutableStateOf(0L)
    var lastSnapshotAt by mutableStateOf(0L)
    var lastDataItemPath by mutableStateOf("-")
    var lastDataItemRevision by mutableStateOf(0L)
    var phoneVersion by mutableStateOf("-")
    var phoneProtocol by mutableStateOf("-")
    val watchIdentity = "${activity.packageName} ${BuildConfig.VERSION_CODE}/${BuildConfig.VERSION_NAME}"
    val watchSignature = signingSha256Short(activity)
    val wearableApiAvailable = GoogleApiAvailabilityLight.getInstance()
        .isGooglePlayServicesAvailable(activity) == ConnectionResult.SUCCESS
    val chatMessages = mutableStateMapOf<Long, ChatMessagePage>()
    val generationDeltas = mutableStateMapOf<String, GenerationDelta>()
    val generationFinals = mutableStateMapOf<String, GenerationFinal>()
    val voiceAcks = mutableStateMapOf<String, VoiceUploadAck>()
    val voiceRequestStates = mutableStateMapOf<String, String>()
    val ttsResults = mutableStateMapOf<String, TtsAudioResult>()
    val ttsAudioFiles = mutableStateMapOf<String, File>()
    val mediaResults = mutableStateMapOf<String, WearMediaResult>()
    val mediaFiles = mutableStateMapOf<String, File>()
    val activeGenerationIds = mutableStateMapOf<String, String>()
    val activeTtsRequestIds = mutableStateMapOf<String, Boolean>()
    private val activeMediaRequestIds = mutableStateMapOf<String, Boolean>()

    private val cache = WearLocalCache(activity)
    private val tts = WatchTtsController(activity) { lastError = it }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastAutoSyncAt = 0L
    private var cachedPhoneNodeId: String? = null
    private val prefs = activity.getSharedPreferences("adt_wear_settings_v1", Context.MODE_PRIVATE)
    private val handledGenerationIds = mutableSetOf<String>()

    fun close() {
        tts.close()
    }

    fun loadCache() {
        autoReadAnswers = prefs.getBoolean("auto_read_answers", false)
        phoneTtsEnabled = prefs.getBoolean("phone_tts_enabled", false)
        vibrateOnAnswer = prefs.getBoolean("vibrate_on_answer", true)
        thinkingEnabled = prefs.getBoolean("thinking_enabled", true)
        watchPinnedChatIds = prefs.getStringSet("watch_pinned_chat_ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        cache.readServer()?.let { serverSnapshot = it }
        cache.readServers()?.let { serverPage = it }
        cache.readChats()?.let { chatPage = it }
        cache.readPet()?.let { petSnapshot = it }
        cache.readTamaMessages()?.let { tamaMessages = it }
        cache.readOrganizerEvents()?.let { organizerEvents = it }
        cache.readOrganizerMonth()?.let { organizerMonth = it }
        cache.readOrganizerAlarms()?.let { organizerAlarms = it }
        cache.readOrganizerNotes()?.let { organizerNotes = it }
        cache.readTamaHub()?.let { tamaHub = it }
        cache.readTamaInventory()?.let { tamaInventory = it }
        cache.readTamaFarm()?.let { tamaFarm = it }
        cache.readTranslatorTemplates()?.let { translatorTemplates = it }
        cache.readTranslatorState()?.let { translatorState = it }
        cache.readTranslatorTurns()?.let { translatorTurns = it }
        cache.readActiveTasks()?.let { activeTasks = it }
        cache.readStats()?.let { statsSnapshot = it }
        cache.readCapabilities()?.let { capabilities = it }
    }

    fun loadExistingData() {
        Wearable.getDataClient(activity).dataItems
            .addOnSuccessListener { buffer ->
                buffer.use { items -> items.forEach { consumeDataItem(it) } }
            }
    }

    fun consumeDataItem(item: DataItem) {
        val path = item.uri.path.orEmpty()
        if (!path.startsWith(AdtWearProtocol.PREFIX)) return
        val payload = runCatching { DataMapItem.fromDataItem(item).dataMap.getString(AdtWearProtocol.KEY_JSON) }
            .getOrNull() ?: return
        val itemRevision = runCatching { DataMapItem.fromDataItem(item).dataMap.getLong(AdtWearProtocol.KEY_REVISION) }.getOrDefault(0L)
        runCatching {
            when {
                path == AdtWearProtocol.SERVER_STATUS -> applyServer(AdtWearProtocol.json.decodeFromString(payload))
                path == AdtWearProtocol.PET_CURRENT -> applyPet(AdtWearProtocol.json.decodeFromString(payload))
                path == AdtWearProtocol.TAMA_MESSAGES -> applyTamaMessages(AdtWearProtocol.json.decodeFromString(payload))
                path.startsWith(AdtWearProtocol.GENERATION_FINAL) -> applyGenerationFinal(AdtWearProtocol.json.decodeFromString(payload))
                path.startsWith(AdtWearProtocol.VOICE_ACK) -> {
                    val ack = AdtWearProtocol.json.decodeFromString<VoiceUploadAck>(payload)
                    voiceAcks[ack.requestId] = ack
                    voiceRequestStates[ack.requestId] = ack.localizedMessage
                }
                path.startsWith(AdtWearProtocol.TTS_AUDIO) -> applyTtsAudio(item, payload)
                path.startsWith(AdtWearProtocol.MEDIA_ASSET) -> applyMediaAsset(item, payload)
                path == AdtWearProtocol.TRANSLATOR_STATE -> applyTranslatorState(AdtWearProtocol.json.decodeFromString(payload))
                path.startsWith(AdtWearProtocol.TRANSLATOR_TURNS) -> applyTranslatorTurns(AdtWearProtocol.json.decodeFromString(payload))
                path == AdtWearProtocol.ACTIVE_TASKS -> applyActiveTasks(AdtWearProtocol.json.decodeFromString(payload))
                path == AdtWearProtocol.STATS -> applyStats(AdtWearProtocol.json.decodeFromString(payload))
                path == AdtWearProtocol.CAPABILITIES -> applyCapabilities(AdtWearProtocol.json.decodeFromString(payload))
            }
            phoneReachable = true
            bridgeUnavailable = false
            lastSnapshotAt = System.currentTimeMillis()
            lastDataItemPath = path
            lastDataItemRevision = itemRevision
            lastError = null
        }.onFailure { error ->
            lastError = error.localizedMessage ?: activity.getString(R.string.wear_error_unknown)
        }
    }

    fun consumeMessage(messageEvent: MessageEvent) {
        if (messageEvent.path != AdtWearProtocol.GENERATION_DELTA) return
        runCatching {
            val delta = AdtWearProtocol.json.decodeFromString<GenerationDelta>(messageEvent.data.toString(Charsets.UTF_8))
            if (activeGenerationIds[delta.generationId] != "${delta.targetType}:${delta.targetId}") return@runCatching
            val existing = generationDeltas[delta.generationId]
            if (existing == null || delta.sequence > existing.sequence) {
                generationDeltas[delta.generationId] = delta
            }
        }
    }

    fun requestRefresh(auto: Boolean = false) {
        val now = System.currentTimeMillis()
        if (auto && (syncing || now - lastAutoSyncAt < AUTO_SYNC_COOLDOWN_MS)) return
        if (auto) lastAutoSyncAt = now
        if (syncing) return
        syncing = true
        lastCommandMessage = activity.getString(R.string.wear_syncing)
        sendRpc(AdtWearProtocol.SYNC, PingRequest.serializer(), PingRequest(meta()), EmptyResult.serializer()) {
            fetchServers()
            fetchChats()
            fetchOrganizerEvents()
            fetchOrganizerAlarms()
            fetchOrganizerNotes()
            fetchTranslatorTemplates()
            fetchActiveTasks()
            fetchStats()
            fetchCapabilities()
            pingPhone()
            syncing = false
            lastCommandMessage = activity.getString(R.string.wear_synced)
        }
    }

    fun refreshDiagnostics() {
        resolvePhoneNode { nodeId ->
            if (nodeId != null) pingPhone()
        }
        refreshCapabilityDiagnostics()
    }

    fun startLlamaServer() {
        if (inFlightRequestId != null) return
        lastCommandMessage = activity.getString(R.string.wear_starting_phone_server)
        sendRpc(AdtWearProtocol.SERVER_START, ServerCommandRequest.serializer(), ServerCommandRequest(meta()), ServerCommandResult.serializer()) { result ->
            result?.let {
                applyServer(it.snapshot)
                lastCommandMessage = it.localizedMessage
                it.confirmationUri?.let { uri -> openPhoneConfirmation(uri) }
            }
        }
    }

    fun stopLlamaServer() {
        if (inFlightRequestId != null) return
        lastCommandMessage = activity.getString(R.string.wear_stopping_phone_server)
        sendRpc(AdtWearProtocol.SERVER_STOP, ServerCommandRequest.serializer(), ServerCommandRequest(meta()), ServerCommandResult.serializer()) { result ->
            result?.let {
                applyServer(it.snapshot)
                lastCommandMessage = it.localizedMessage
            }
        }
    }

    fun selectServer(serverId: Long) {
        if (pendingServerId == serverId) return
        val previous = serverPage
        pendingServerId = serverId
        serverPage = serverPage?.let { page ->
            page.copy(
                selectedServerId = serverId,
                servers = page.servers.map { it.copy(selected = it.id == serverId) }
            )
        }
        sendRpc(AdtWearProtocol.SERVER_SELECT, ServerSelectRequest.serializer(), ServerSelectRequest(meta(), serverId), ServerSelectResult.serializer()) { result ->
            pendingServerId = null
            if (result != null) {
                serverPage = result.page
                cache.writeServers(result.page)
            } else {
                serverPage = previous
            }
        }
        mainHandler.postDelayed({
            if (pendingServerId == serverId) {
                pendingServerId = null
                serverPage = previous
            }
        }, RPC_TIMEOUT_MS)
    }

    fun openChat(chatId: Long) {
        sendRpc(
            AdtWearProtocol.CHAT_MESSAGES,
            ChatMessagesRequest.serializer(),
            ChatMessagesRequest(meta(), chatId = chatId, limit = 30),
            ChatMessagePage.serializer()
        ) { page ->
            if (page != null) {
                chatMessages[chatId] = page
            }
        }
    }

    fun createChat(onCreated: (Long) -> Unit) {
        sendRpc(AdtWearProtocol.CHAT_CREATE, ChatCreateRequest.serializer(), ChatCreateRequest(meta()), ChatSummary.serializer()) { summary ->
            summary?.let {
                fetchChats()
                onCreated(it.id)
            }
        }
    }

    fun createQuickChat(onCreated: (Long) -> Unit) {
        sendRpc(
            AdtWearProtocol.QUICK_CHAT_CREATE,
            QuickChatCreateRequest.serializer(),
            QuickChatCreateRequest(meta()),
            ChatSummary.serializer()
        ) { summary ->
            summary?.let {
                fetchChats()
                onCreated(it.id)
            }
        }
    }

    fun toggleWatchPin(chatId: Long) {
        watchPinnedChatIds = if (chatId in watchPinnedChatIds) watchPinnedChatIds - chatId else watchPinnedChatIds + chatId
        prefs.edit().putStringSet("watch_pinned_chat_ids", watchPinnedChatIds.map { it.toString() }.toSet()).apply()
    }

    fun isWatchPinned(chatId: Long): Boolean = chatId in watchPinnedChatIds

    fun pinChat(chatId: Long, serverId: Long?) {
        toggleWatchPin(chatId)
        if (serverId == null) return
    }

    fun sendChatText(chatId: Long, serverId: Long, text: String): String {
        val requestId = UUID.randomUUID().toString()
        registerActiveGeneration(requestId, "chat", chatId.toString())
        sendRpc(
            AdtWearProtocol.CHAT_SEND,
            ChatSendRequest.serializer(),
            ChatSendRequest(meta(requestId), chatId, serverId, text, thinkingEnabled),
            GenerationAccepted.serializer()
        ) { accepted ->
            if (accepted != null) {
                lastCommandMessage = activity.getString(R.string.wear_generation_accepted)
                refreshChatAfterAcceptedSend(chatId)
            }
        }
        return requestId
    }

    fun sendTamaText(petId: String, text: String): String {
        if (petSnapshot?.frozen == true || tamaHub?.pet?.frozen == true) {
            lastError = activity.getString(R.string.wear_tama_frozen_no_chat)
            return ""
        }
        val requestId = UUID.randomUUID().toString()
        registerActiveGeneration(requestId, "tama", petId)
        sendRpc(
            AdtWearProtocol.TAMA_CHAT_SEND,
            TamaChatRequest.serializer(),
            TamaChatRequest(meta(requestId), petId, text, thinkingEnabled),
            GenerationAccepted.serializer()
        ) { accepted ->
            if (accepted != null) lastCommandMessage = activity.getString(R.string.wear_generation_accepted)
        }
        return requestId
    }

    fun cancelGeneration(generationId: String?) {
        val id = generationId ?: return
        sendRpc(
            AdtWearProtocol.GENERATION_CANCEL,
            GenerationCancelRequest.serializer(),
            GenerationCancelRequest(meta(), id),
            EmptyResult.serializer()
        ) { lastCommandMessage = activity.getString(R.string.wear_cancelled) }
    }

    fun cancelTask(taskId: String) {
        sendRpc(
            AdtWearProtocol.TASK_CANCEL,
            TaskCommandRequest.serializer(),
            TaskCommandRequest(meta(), taskId),
            CommandAckDto.serializer()
        ) { ack ->
            lastCommandMessage = ack?.errorMessage ?: activity.getString(R.string.wear_task_cancelling)
            fetchActiveTasks()
        }
    }

    fun pauseTask(taskId: String) {
        sendRpc(
            AdtWearProtocol.TASK_PAUSE,
            TaskCommandRequest.serializer(),
            TaskCommandRequest(meta(), taskId),
            CommandAckDto.serializer()
        ) { ack ->
            lastCommandMessage = ack?.errorMessage ?: activity.getString(R.string.wear_pause)
            fetchActiveTasks()
        }
    }

    fun resumeTask(taskId: String) {
        sendRpc(
            AdtWearProtocol.TASK_RESUME,
            TaskCommandRequest.serializer(),
            TaskCommandRequest(meta(), taskId),
            CommandAckDto.serializer()
        ) { ack ->
            lastCommandMessage = ack?.errorMessage ?: activity.getString(R.string.wear_resume)
            fetchActiveTasks()
        }
    }

    fun sendChatAudio(chatId: Long, serverId: Long, audioFile: File, durationMs: Long): String {
        val requestId = UUID.randomUUID().toString()
        registerActiveGeneration(requestId, "chat", chatId.toString())
        voiceRequestStates[requestId] = activity.getString(R.string.wear_voice_uploading)
        sendVoiceAsset(requestId, "chat", chatId.toString(), audioFile, durationMs, chatId, serverId)
        return requestId
    }

    fun sendTamaAudio(petId: String, audioFile: File, durationMs: Long): String {
        if (petSnapshot?.frozen == true || tamaHub?.pet?.frozen == true) {
            lastError = activity.getString(R.string.wear_tama_frozen_no_chat)
            return ""
        }
        val requestId = UUID.randomUUID().toString()
        registerActiveGeneration(requestId, "tama", petId)
        voiceRequestStates[requestId] = activity.getString(R.string.wear_voice_uploading)
        sendVoiceAsset(requestId, "tama", petId, audioFile, durationMs, null, null)
        return requestId
    }

    fun speak(text: String) {
        tts.speak(plainTextForSpeech(text))
    }

    fun stopSpeaking() {
        tts.stop()
    }

    fun updateThinkingEnabled(value: Boolean) {
        thinkingEnabled = value
        prefs.edit().putBoolean("thinking_enabled", value).apply()
    }

    fun updateAutoReadAnswers(value: Boolean) {
        autoReadAnswers = value
        prefs.edit().putBoolean("auto_read_answers", value).apply()
    }

    fun updatePhoneTtsEnabled(value: Boolean) {
        phoneTtsEnabled = value
        prefs.edit().putBoolean("phone_tts_enabled", value).apply()
    }

    fun updateVibrateOnAnswer(value: Boolean) {
        vibrateOnAnswer = value
        prefs.edit().putBoolean("vibrate_on_answer", value).apply()
    }

    fun clearChat(chatId: Long) {
        clearGenerationStateForTarget("chat", chatId.toString())
        sendRpc(AdtWearProtocol.CHAT_CLEAR, ChatClearRequest.serializer(), ChatClearRequest(meta(), chatId), ChatMessagePage.serializer()) { page ->
            if (page != null) {
                chatMessages[chatId] = page
                fetchChats()
            }
        }
    }

    fun deleteMessage(chatId: Long, message: WearMessage) {
        val messageId = message.id.toLongOrNull() ?: return
        sendRpc(AdtWearProtocol.CHAT_MESSAGE_DELETE, ChatMessageActionRequest.serializer(), ChatMessageActionRequest(meta(), chatId, messageId), ChatMessagePage.serializer()) { page ->
            if (page != null) {
                chatMessages[chatId] = page
                fetchChats()
            }
        }
    }

    fun retryMessage(chatId: Long, serverId: Long?, message: WearMessage): String? {
        val messageId = message.id.toLongOrNull() ?: return null
        val requestId = UUID.randomUUID().toString()
        registerActiveGeneration(requestId, "chat", chatId.toString())
        sendRpc(
            AdtWearProtocol.CHAT_MESSAGE_RETRY,
            ChatMessageActionRequest.serializer(),
            ChatMessageActionRequest(meta(requestId), chatId, messageId, serverId, thinkingEnabled),
            GenerationAccepted.serializer()
        ) { accepted ->
            if (accepted != null) {
                lastCommandMessage = activity.getString(R.string.wear_generation_accepted)
                refreshChatAfterAcceptedSend(chatId)
            }
        }
        return requestId
    }

    private fun refreshChatAfterAcceptedSend(chatId: Long) {
        openChat(chatId)
        fetchChats()
    }

    private fun refreshChatAfterAcceptedGeneration(requestId: String) {
        val target = activeGenerationIds[requestId] ?: return
        val chatId = target.removePrefix("chat:").takeIf { it != target }?.toLongOrNull() ?: return
        refreshChatAfterAcceptedSend(chatId)
    }

    private fun fetchServers() {
        sendRpc(AdtWearProtocol.SERVER_LIST, ServerListRequest.serializer(), ServerListRequest(meta()), ServerListPage.serializer()) { page ->
            if (page != null) {
                serverPage = page
                cache.writeServers(page)
            }
        }
    }

    fun fetchChats() {
        sendRpc(AdtWearProtocol.CHAT_LIST, ChatListRequest.serializer(), ChatListRequest(meta(), limit = 20), ChatListPage.serializer()) { page ->
            if (page != null) {
                chatPage = page
                cache.writeChats(page)
            }
        }
    }

    fun fetchCapabilities() {
        sendRpc(AdtWearProtocol.CAPABILITIES, PingRequest.serializer(), PingRequest(meta()), WearCapabilities.serializer()) { snapshot ->
            snapshot?.let { applyCapabilities(it) }
        }
    }

    fun fetchActiveTasks() {
        sendRpc(AdtWearProtocol.ACTIVE_TASKS, PingRequest.serializer(), PingRequest(meta()), ActiveTaskSnapshot.serializer()) { snapshot ->
            snapshot?.let { applyActiveTasks(it) }
        }
    }

    fun fetchStats() {
        val now = System.currentTimeMillis()
        sendRpc(
            AdtWearProtocol.STATS,
            StatsRequest.serializer(),
            StatsRequest(meta(), sinceEpochMs = now - 15L * 60L * 1000L, untilEpochMs = now, maxPoints = 15),
            WearStatsSnapshot.serializer()
        ) { snapshot -> snapshot?.let { applyStats(it) } }
    }

    fun fetchOrganizerEvents() {
        val now = System.currentTimeMillis()
        sendRpc(
            AdtWearProtocol.ORGANIZER_EVENTS,
            OrganizerEventsRequest.serializer(),
            OrganizerEventsRequest(meta(), now, Long.MAX_VALUE, limit = 20),
            OrganizerEventPage.serializer()
        ) { page ->
            if (page != null) {
                organizerEvents = page
                cache.writeOrganizerEvents(page)
            }
        }
    }

    fun fetchOrganizerMonth(year: Int? = null, month: Int? = null, selectedDayEpochMs: Long? = null) {
        val calendar = Calendar.getInstance()
        val requestYear = year ?: calendar.get(Calendar.YEAR)
        val requestMonth = month ?: (calendar.get(Calendar.MONTH) + 1)
        sendRpc(
            AdtWearProtocol.ORGANIZER_EVENTS_MONTH,
            OrganizerMonthRequest.serializer(),
            OrganizerMonthRequest(meta(), requestYear, requestMonth, TimeZone.getDefault().id, selectedDayEpochMs),
            OrganizerMonthPage.serializer()
        ) { page ->
            if (page != null) {
                organizerMonth = page
                cache.writeOrganizerMonth(page)
            }
        }
    }

    fun fetchOrganizerAlarms() {
        sendRpc(
            AdtWearProtocol.ORGANIZER_ALARMS,
            OrganizerAlarmsRequest.serializer(),
            OrganizerAlarmsRequest(meta(), limit = 20),
            OrganizerAlarmPage.serializer()
        ) { page ->
            if (page != null) {
                organizerAlarms = page
                cache.writeOrganizerAlarms(page)
            }
        }
    }

    fun saveOrganizerEvent(
        id: Long?,
        title: String,
        description: String,
        location: String,
        startAtEpochMs: Long,
        endAtEpochMs: Long?,
        allDay: Boolean,
        colorArgb: Long?,
        alarmAtEpochMs: Long?
    ) {
        sendRpc(
            AdtWearProtocol.ORGANIZER_EVENT_UPSERT,
            OrganizerEventUpsertRequest.serializer(),
            OrganizerEventUpsertRequest(meta(), id, title, description, location, startAtEpochMs, endAtEpochMs, allDay, TimeZone.getDefault().id, colorArgb, alarmAtEpochMs),
            MutationResult.serializer()
        ) { result ->
            if (result != null) {
                lastCommandMessage = result.localizedMessage
                fetchOrganizerMonth(organizerMonth?.year, organizerMonth?.month, organizerMonth?.selectedDayEpochMs)
                fetchOrganizerEvents()
                fetchOrganizerAlarms()
            }
        }
    }

    fun deleteOrganizerEvent(eventId: Long) {
        sendRpc(
            AdtWearProtocol.ORGANIZER_EVENT_DELETE,
            OrganizerEventDeleteRequest.serializer(),
            OrganizerEventDeleteRequest(meta(), eventId),
            MutationResult.serializer()
        ) { result ->
            if (result != null) {
                lastCommandMessage = result.localizedMessage
                fetchOrganizerMonth(organizerMonth?.year, organizerMonth?.month, organizerMonth?.selectedDayEpochMs)
                fetchOrganizerEvents()
                fetchOrganizerAlarms()
            }
        }
    }

    fun saveOrganizerAlarm(id: Long?, eventId: Long?, title: String, message: String, triggerAtEpochMs: Long, soundEnabled: Boolean, enabled: Boolean) {
        sendRpc(
            AdtWearProtocol.ORGANIZER_ALARM_UPSERT,
            OrganizerAlarmUpsertRequest.serializer(),
            OrganizerAlarmUpsertRequest(meta(), id, eventId, title, message, triggerAtEpochMs, TimeZone.getDefault().id, soundEnabled, enabled),
            OrganizerAlarmPage.serializer()
        ) { page ->
            if (page != null) {
                organizerAlarms = page
                cache.writeOrganizerAlarms(page)
                lastCommandMessage = activity.getString(R.string.wear_alarm_saved)
            }
        }
    }

    fun toggleOrganizerAlarm(alarm: OrganizerAlarmSummary) {
        sendRpc(
            AdtWearProtocol.ORGANIZER_ALARM_TOGGLE,
            OrganizerAlarmToggleRequest.serializer(),
            OrganizerAlarmToggleRequest(meta(), alarm.id, !alarm.enabled),
            OrganizerAlarmPage.serializer()
        ) { page ->
            if (page != null) {
                organizerAlarms = page
                cache.writeOrganizerAlarms(page)
            }
        }
    }

    fun deleteOrganizerAlarm(alarmId: Long) {
        sendRpc(
            AdtWearProtocol.ORGANIZER_ALARM_DELETE,
            OrganizerAlarmDeleteRequest.serializer(),
            OrganizerAlarmDeleteRequest(meta(), alarmId),
            OrganizerAlarmPage.serializer()
        ) { page ->
            if (page != null) {
                organizerAlarms = page
                cache.writeOrganizerAlarms(page)
                lastCommandMessage = activity.getString(R.string.wear_alarm_deleted)
            }
        }
    }

    fun fetchOrganizerNotes(query: String? = null) {
        sendRpc(
            AdtWearProtocol.ORGANIZER_NOTES,
            OrganizerNotesRequest.serializer(),
            OrganizerNotesRequest(meta(), query = query, limit = 20),
            OrganizerNotePage.serializer()
        ) { page ->
            if (page != null) {
                organizerNotes = page
                cache.writeOrganizerNotes(page)
            }
        }
    }

    fun openNote(noteId: Int) {
        sendRpc(
            AdtWearProtocol.ORGANIZER_NOTE,
            OrganizerNoteRequest.serializer(),
            OrganizerNoteRequest(meta(), noteId),
            OrganizerNoteDetail.serializer()
        ) { detail ->
            if (detail != null) selectedNoteDetail = detail
        }
    }

    fun saveNote(id: Int?, title: String, content: String) {
        sendRpc(
            AdtWearProtocol.ORGANIZER_NOTE_UPSERT,
            OrganizerNoteUpsertRequest.serializer(),
            OrganizerNoteUpsertRequest(meta(), id, title, content),
            MutationResult.serializer()
        ) { result ->
            if (result != null) {
                lastCommandMessage = result.localizedMessage
                selectedNoteDetail = null
                fetchOrganizerNotes()
            }
        }
    }

    fun deleteNote(noteId: Int) {
        sendRpc(
            AdtWearProtocol.ORGANIZER_NOTE_DELETE,
            OrganizerNoteDeleteRequest.serializer(),
            OrganizerNoteDeleteRequest(meta(), noteId),
            MutationResult.serializer()
        ) { result ->
            if (result != null) {
                lastCommandMessage = result.localizedMessage
                selectedNoteDetail = null
                fetchOrganizerNotes()
            }
        }
    }

    fun pinNote(noteId: Int) {
        sendRpc(
            AdtWearProtocol.ORGANIZER_NOTE_PIN,
            OrganizerNotePinRequest.serializer(),
            OrganizerNotePinRequest(meta(), noteId),
            OrganizerPinnedNoteResult.serializer()
        ) { result ->
            if (result != null) {
                WearLocalCache(activity).writePinnedNote(result)
                AdtTileBridge.refreshTiles(activity)
                lastCommandMessage = activity.getString(R.string.wear_note_pinned)
            }
        }
    }

    fun fetchTamaHub() {
        sendRpc(AdtWearProtocol.TAMA_HUB, TamaHubRequest.serializer(), TamaHubRequest(meta()), TamaHubSnapshot.serializer()) { hub ->
            if (hub != null && isNewer(hub.revisioned, tamaHub?.revisioned)) {
                tamaHub = hub
                petSnapshot = hub.pet
                cache.writeTamaHub(hub)
            }
        }
    }

    fun fetchTamaInventory() {
        sendRpc(AdtWearProtocol.TAMA_INVENTORY, TamaHubRequest.serializer(), TamaHubRequest(meta()), TamaInventoryPage.serializer()) { page ->
            if (page != null && isNewer(page.revisioned, tamaInventory?.revisioned)) {
                tamaInventory = page
                cache.writeTamaInventory(page)
            }
        }
    }

    fun fetchTamaStore() {
        sendRpc(AdtWearProtocol.TAMA_STORE, TamaHubRequest.serializer(), TamaHubRequest(meta()), TamaStorePage.serializer()) { page ->
            if (page != null) tamaStore = page
        }
    }

    fun fetchTamaFarm() {
        sendRpc(AdtWearProtocol.TAMA_FARM, TamaHubRequest.serializer(), TamaHubRequest(meta()), TamaFarmSnapshot.serializer()) { page ->
            if (page != null && isNewer(page.revisioned, tamaFarm?.revisioned)) {
                tamaFarm = page
                cache.writeTamaFarm(page)
            }
        }
    }

    fun fetchTamaAdventure() {
        sendRpc(AdtWearProtocol.TAMA_ADVENTURE, TamaHubRequest.serializer(), TamaHubRequest(meta()), TamaAdventureSnapshot.serializer()) { page ->
            if (page != null) tamaAdventure = page
        }
    }

    fun fetchTamaRpg() {
        sendRpc(AdtWearProtocol.TAMA_RPG, TamaHubRequest.serializer(), TamaHubRequest(meta()), TamaRpgSnapshot.serializer()) { page ->
            if (page != null) tamaRpg = page
        }
    }

    fun fetchTamaArcade() {
        sendRpc(AdtWearProtocol.TAMA_ARCADE, TamaHubRequest.serializer(), TamaHubRequest(meta()), TamaArcadeSnapshot.serializer()) { page ->
            if (page != null) tamaArcade = page
        }
    }

    fun sendTamaAction(action: String, args: Map<String, String> = emptyMap()) {
        sendRpc(
            AdtWearProtocol.TAMA_ACTION,
            TamaActionRequest.serializer(),
            TamaActionRequest(meta(), petSnapshot?.petId, action, args),
            TamaActionResult.serializer()
        ) { result ->
            if (result != null) {
                lastCommandMessage = result.localizedMessage
                result.hub?.let {
                    tamaHub = it
                    petSnapshot = it.pet
                    cache.writeTamaHub(it)
                }
                fetchTamaHub()
            }
        }
    }

    fun sendTamaFarmAction(action: String, tileId: String? = null, itemId: String? = null) {
        sendRpc(
            AdtWearProtocol.TAMA_FARM_ACTION,
            TamaFarmActionRequest.serializer(),
            TamaFarmActionRequest(meta(), petSnapshot?.petId, action, tileId, itemId),
            TamaFarmSnapshot.serializer()
        ) { snapshot ->
            if (snapshot != null) {
                tamaFarm = snapshot
                cache.writeTamaFarm(snapshot)
                fetchTamaHub()
            }
        }
    }

    fun fetchTranslatorTemplates() {
        sendRpc(AdtWearProtocol.TRANSLATOR_TEMPLATES, PingRequest.serializer(), PingRequest(meta()), TranslatorTemplatePage.serializer()) { page ->
            if (page != null) {
                translatorTemplates = page
                cache.writeTranslatorTemplates(page)
            }
        }
    }

    fun startTranslator(templateId: Long) {
        translatorStartPending = true
        translatorTemplates?.templates?.firstOrNull { it.id == templateId }?.let { template ->
            translatorState = TranslatorStateSnapshot(
                revisioned = Revisioned(0L, System.currentTimeMillis(), activity.packageName),
                isActive = true,
                templateId = template.id,
                phase = "STARTING",
                status = activity.getString(R.string.wear_translator_loading_backend),
                selectedTemplateId = template.id,
                selectedTemplateName = template.name,
                backendEngine = template.backendEngine,
                backendLabel = template.backendLabel,
                modelLabel = template.modelLabel,
                backendLoading = true,
                backendStatus = activity.getString(R.string.wear_translator_loading_backend)
            )
        }
        sendRpc(
            AdtWearProtocol.TRANSLATOR_START,
            TranslatorCommandRequest.serializer(),
            TranslatorCommandRequest(meta(), templateId = templateId),
            TranslatorStateSnapshot.serializer()
        ) { state ->
            translatorStartPending = false
            state?.let { applyTranslatorState(it, force = true) }
        }
        mainHandler.postDelayed({ translatorStartPending = false }, RPC_TIMEOUT_MS)
    }

    fun stopTranslator() {
        translatorStopPending = true
        translatorState = translatorState?.copy(
            isActive = false,
            phase = "STOPPING",
            status = activity.getString(R.string.wear_translator_stopping),
            backendLoading = false,
            backendStatus = activity.getString(R.string.wear_translator_stopping)
        )
        sendRpc(
            AdtWearProtocol.TRANSLATOR_STOP,
            TranslatorCommandRequest.serializer(),
            TranslatorCommandRequest(meta()),
            TranslatorStateSnapshot.serializer()
        ) { state ->
            translatorStopPending = false
            state?.let { applyTranslatorState(it, force = true) }
        }
        mainHandler.postDelayed({ translatorStopPending = false }, RPC_TIMEOUT_MS)
    }

    fun setTranslatorSpeaker(speaker: Int) {
        sendRpc(
            AdtWearProtocol.TRANSLATOR_SPEAKER,
            TranslatorCommandRequest.serializer(),
            TranslatorCommandRequest(meta(), speaker = speaker),
            TranslatorStateSnapshot.serializer()
        ) { state -> state?.let { applyTranslatorState(it) } }
    }

    fun requestPhoneTts(text: String): String {
        val requestId = UUID.randomUUID().toString()
        activeTtsRequestIds[requestId] = true
        val speech = plainTextForSpeech(text)
        sendRpc(
            AdtWearProtocol.TTS_GENERATE,
            TtsGenerateRequest.serializer(),
            TtsGenerateRequest(meta(requestId), speech, Locale.getDefault().language),
            TtsAudioResult.serializer()
        ) { result ->
            if (result != null) ttsResults[requestId] = result
        }
        return requestId
    }

    fun requestMedia(mediaId: String?) {
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return
        if (mediaFiles.containsKey(id) || activeMediaRequestIds.containsKey(id)) return
        activeMediaRequestIds[id] = true
        sendRpc(
            AdtWearProtocol.MEDIA_REQUEST,
            WearMediaRequest.serializer(),
            WearMediaRequest(meta(), id),
            WearMediaResult.serializer()
        ) { result ->
            activeMediaRequestIds.remove(id)
            if (result != null) mediaResults[result.mediaId] = result
        }
        mainHandler.postDelayed({ activeMediaRequestIds.remove(id) }, RPC_TIMEOUT_MS)
    }

    fun playMedia(mediaId: String?) {
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return
        val file = mediaFiles[id]
        if (file != null) {
            playAudio(file)
        } else {
            requestMedia(id)
            lastCommandMessage = activity.getString(R.string.wear_media_loading)
        }
    }

    private fun pingPhone() {
        sendRpc(AdtWearProtocol.PING, PingRequest.serializer(), PingRequest(meta()), PingResult.serializer()) { result ->
            if (result != null) {
                phoneVersion = "${result.versionCode}/${result.versionName}"
                phoneProtocol = result.protocolVersion.toString()
                lastPingAt = System.currentTimeMillis()
            }
        }
    }

    private fun <TRequest, TResult> sendRpc(
        path: String,
        requestSerializer: KSerializer<TRequest>,
        request: TRequest,
        resultSerializer: KSerializer<TResult>,
        onResult: (TResult?) -> Unit = {}
    ) {
        val requestId = when (request) {
            is PingRequest -> request.meta.requestId
            is ServerCommandRequest -> request.meta.requestId
            is ServerListRequest -> request.meta.requestId
            is ServerSelectRequest -> request.meta.requestId
            is ChatListRequest -> request.meta.requestId
            is ChatCreateRequest -> request.meta.requestId
            is QuickChatCreateRequest -> request.meta.requestId
            is QuickChatEndRequest -> request.meta.requestId
            is ToolConfirmationRequest -> request.meta.requestId
            is ChatMessagesRequest -> request.meta.requestId
            is ChatPinRequest -> request.meta.requestId
            is ChatSendRequest -> request.meta.requestId
            is ChatClearRequest -> request.meta.requestId
            is ChatMessageActionRequest -> request.meta.requestId
            is VoiceCommitRequest -> request.meta.requestId
            is TtsGenerateRequest -> request.meta.requestId
            is OrganizerEventsRequest -> request.meta.requestId
            is OrganizerMonthRequest -> request.meta.requestId
            is OrganizerAlarmsRequest -> request.meta.requestId
            is OrganizerAlarmUpsertRequest -> request.meta.requestId
            is OrganizerAlarmToggleRequest -> request.meta.requestId
            is OrganizerAlarmDeleteRequest -> request.meta.requestId
            is OrganizerEventUpsertRequest -> request.meta.requestId
            is OrganizerEventDeleteRequest -> request.meta.requestId
            is OrganizerNotesRequest -> request.meta.requestId
            is OrganizerNoteRequest -> request.meta.requestId
            is OrganizerNoteUpsertRequest -> request.meta.requestId
            is OrganizerNoteDeleteRequest -> request.meta.requestId
            is WearMediaRequest -> request.meta.requestId
            is TranslatorCommandRequest -> request.meta.requestId
            is TamaHubRequest -> request.meta.requestId
            is TamaActionRequest -> request.meta.requestId
            is TamaFarmActionRequest -> request.meta.requestId
            is TamaAdventureActionRequest -> request.meta.requestId
            is TamaRpgActionRequest -> request.meta.requestId
            is TamaChatRequest -> request.meta.requestId
            is GenerationCancelRequest -> request.meta.requestId
            is TaskCommandRequest -> request.meta.requestId
            else -> UUID.randomUUID().toString()
        }
        val nonBlockingPaths = setOf(
            AdtWearProtocol.PING,
            AdtWearProtocol.CHAT_LIST,
            AdtWearProtocol.CHAT_MESSAGES,
            AdtWearProtocol.SERVER_LIST,
            AdtWearProtocol.ORGANIZER_EVENTS,
            AdtWearProtocol.ORGANIZER_EVENTS_MONTH,
            AdtWearProtocol.ORGANIZER_ALARMS,
            AdtWearProtocol.ORGANIZER_NOTES,
            AdtWearProtocol.ORGANIZER_NOTE,
            AdtWearProtocol.MEDIA_REQUEST,
            AdtWearProtocol.VOICE_COMMIT,
            AdtWearProtocol.TRANSLATOR_TEMPLATES,
            AdtWearProtocol.TRANSLATOR_STOP,
            AdtWearProtocol.TRANSLATOR_SPEAKER,
            AdtWearProtocol.TAMA_HUB,
            AdtWearProtocol.TAMA_INVENTORY,
            AdtWearProtocol.TAMA_STORE,
            AdtWearProtocol.TAMA_FARM,
            AdtWearProtocol.TAMA_ADVENTURE,
            AdtWearProtocol.TAMA_RPG,
            AdtWearProtocol.TAMA_ARCADE,
            AdtWearProtocol.ACTIVE_TASKS,
            AdtWearProtocol.CAPABILITIES
        )
        if (path !in nonBlockingPaths && inFlightRequestId != null) {
            lastError = activity.getString(R.string.wear_bridge_busy)
            return
        }
        if (path !in nonBlockingPaths) {
            inFlightRequestId = requestId
            scheduleTimeout(requestId)
        }
        resolvePhoneNode { nodeId ->
            if (nodeId == null) {
                if (path !in nonBlockingPaths) inFlightRequestId = null
                syncing = false
                lastError = if (genericPhoneReachable) activity.getString(R.string.wear_phone_bridge_unavailable) else activity.getString(R.string.wear_no_phone)
                return@resolvePhoneNode
            }
            val bytes = AdtWearProtocol.json.encodeToString(requestSerializer, request).toByteArray(Charsets.UTF_8)
            Wearable.getMessageClient(activity).sendRequest(nodeId, path, bytes)
                .addOnSuccessListener { responseBytes ->
                    val response = runCatching {
                        AdtWearProtocol.json.decodeFromString(
                            RpcResponse.serializer(resultSerializer),
                            responseBytes.toString(Charsets.UTF_8)
                        )
                    }.getOrElse { error ->
                        lastError = error.localizedMessage ?: activity.getString(R.string.wear_message_failed)
                        if (inFlightRequestId == requestId) inFlightRequestId = null
                        return@addOnSuccessListener
                    }
                    phoneReachable = true
                    bridgeUnavailable = false
                    phoneVersion = "${response.phoneVersionCode}/${response.phoneVersionName}"
                    lastCommandMessage = response.error?.localizedMessage
                        ?: response.bridgeState.serverLabel.takeIf { it.isNotBlank() }
                        ?: lastCommandMessage
                    if (response.status == "error") {
                        lastError = response.error?.localizedMessage ?: activity.getString(R.string.wear_message_failed)
                        val isVoiceCommitPending = path == AdtWearProtocol.VOICE_COMMIT && response.error?.code == "voice_not_ready"
                        if (isVoiceCommitPending) {
                            voiceRequestStates[requestId] = lastError ?: activity.getString(R.string.wear_voice_uploading)
                        } else {
                            activeGenerationIds.remove(requestId)
                            voiceRequestStates.remove(requestId)
                        }
                    } else {
                        lastError = null
                        onResult(response.result)
                    }
                    if (inFlightRequestId == requestId) inFlightRequestId = null
                }
                .addOnFailureListener { error ->
                    if (inFlightRequestId == requestId) inFlightRequestId = null
                    syncing = false
                    lastError = error.localizedMessage ?: activity.getString(R.string.wear_message_failed)
                }
        }
    }

    private fun resolvePhoneNode(onResolved: (String?) -> Unit) {
        Wearable.getCapabilityClient(activity)
            .getCapability(AdtWearProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capability ->
                bridgeNodeCount = capability.nodes.size
                nearbyBridgeNodeCount = capability.nodes.count { it.isNearby }
                val node = capability.nodes.firstOrNull { it.isNearby } ?: capability.nodes.firstOrNull()
                cachedPhoneNodeId = node?.id
                phoneReachable = node != null
                bridgeUnavailable = node == null
                refreshConnectedNodeCount { onResolved(node?.id) }
            }
            .addOnFailureListener {
                bridgeNodeCount = 0
                nearbyBridgeNodeCount = 0
                phoneReachable = false
                bridgeUnavailable = true
                refreshConnectedNodeCount { onResolved(null) }
            }
    }

    private fun refreshConnectedNodeCount(onDone: () -> Unit) {
        Wearable.getNodeClient(activity).connectedNodes
            .addOnSuccessListener { nodes ->
                connectedNodeCount = nodes.size
                genericPhoneReachable = nodes.isNotEmpty()
                onDone()
            }
            .addOnFailureListener {
                connectedNodeCount = 0
                genericPhoneReachable = false
                onDone()
            }
    }

    private fun refreshCapabilityDiagnostics() {
        Wearable.getNodeClient(activity).localNode
            .addOnSuccessListener { localNodeId = it.id }
        Wearable.getCapabilityClient(activity).getAllCapabilities(CapabilityClient.FILTER_ALL)
            .addOnSuccessListener { capabilities ->
                reciprocalWearNodeCount = capabilities[AdtWearProtocol.WATCH_CAPABILITY]?.nodes?.size ?: 0
                capabilitiesSummary = capabilities.entries
                    .sortedBy { it.key }
                    .filter { it.key.contains("adt") }
                    .joinToString(", ") { "${it.key}:${it.value.nodes.size}" }
                    .ifBlank { "-" }
            }
            .addOnFailureListener { capabilitiesSummary = it.localizedMessage ?: "-" }
    }

    private fun sendVoiceAsset(
        requestId: String,
        targetType: String,
        targetId: String,
        audioFile: File,
        durationMs: Long,
        chatId: Long?,
        serverId: Long?
    ) {
        resolvePhoneNode { nodeId ->
            if (nodeId == null) {
                lastError = if (genericPhoneReachable) activity.getString(R.string.wear_phone_bridge_unavailable) else activity.getString(R.string.wear_no_phone)
                activeGenerationIds.remove(requestId)
                return@resolvePhoneNode
            }
            if (durationMs > AdtWearProtocol.MAX_VOICE_DURATION_MS || audioFile.length() > AdtWearProtocol.MAX_VOICE_BYTES) {
                lastError = activity.getString(R.string.wear_audio_too_large)
                activeGenerationIds.remove(requestId)
                return@resolvePhoneNode
            }
            voiceRequestStates[requestId] = activity.getString(R.string.wear_voice_uploading)
            val metadata = VoiceAssetMetadata(
                meta = meta(requestId),
                targetType = targetType,
                targetId = targetId,
                chatId = chatId,
                serverId = serverId,
                mimeType = "audio/mp4",
                durationMs = durationMs.coerceAtLeast(1L),
                byteCount = audioFile.length(),
                sha256 = audioFile.sha256(),
                languageHint = Locale.getDefault().language
            )
            val putRequest = PutDataMapRequest.create(AdtWearProtocol.voiceUploadPath(requestId)).apply {
                dataMap.putString(AdtWearProtocol.KEY_JSON, AdtWearProtocol.json.encodeToString(VoiceAssetMetadata.serializer(), metadata))
                dataMap.putAsset(AdtWearProtocol.KEY_ASSET, Asset.createFromBytes(audioFile.readBytes()))
                dataMap.putLong(AdtWearProtocol.KEY_REVISION, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(activity).putDataItem(putRequest)
                .addOnSuccessListener {
                    lastCommandMessage = activity.getString(R.string.wear_voice_uploaded)
                    voiceRequestStates[requestId] = activity.getString(R.string.wear_voice_delivered)
                    commitVoiceAsset(requestId, retryCount = 0)
                }
                .addOnFailureListener {
                    voiceRequestStates.remove(requestId)
                    activeGenerationIds.remove(requestId)
                    lastError = it.localizedMessage ?: activity.getString(R.string.wear_message_failed)
                }
        }
    }

    private fun commitVoiceAsset(requestId: String, retryCount: Int) {
        if (voiceAcks[requestId]?.status == "accepted") return
        sendRpc(
            AdtWearProtocol.VOICE_COMMIT,
            VoiceCommitRequest.serializer(),
            VoiceCommitRequest(meta(requestId), requestId, thinkingEnabled),
            GenerationAccepted.serializer()
        ) { accepted ->
            if (accepted != null) {
                lastCommandMessage = activity.getString(R.string.wear_generation_accepted)
                voiceRequestStates[requestId] = activity.getString(R.string.wear_generation_accepted)
                refreshChatAfterAcceptedGeneration(requestId)
            }
        }
        if (retryCount < 2) {
            mainHandler.postDelayed({
                if (activeGenerationIds.containsKey(requestId) && voiceAcks[requestId]?.status != "accepted" && !generationFinals.containsKey(requestId)) {
                    commitVoiceAsset(requestId, retryCount + 1)
                }
            }, 750L + retryCount * 500L)
        } else {
            mainHandler.postDelayed({
                if (activeGenerationIds.containsKey(requestId) && voiceAcks[requestId]?.status != "accepted" && !generationFinals.containsKey(requestId)) {
                    activeGenerationIds.remove(requestId)
                    voiceRequestStates[requestId] = activity.getString(R.string.wear_voice_commit_failed)
                    lastError = activity.getString(R.string.wear_voice_commit_failed)
                }
            }, 1_200L)
        }
    }

    private fun openPhoneConfirmation(uri: String) {
        val node = cachedPhoneNodeId ?: return
        RemoteActivityHelper(activity, Executors.newSingleThreadExecutor()).startRemoteActivity(
            Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse(uri)
                addCategory(Intent.CATEGORY_BROWSABLE)
            },
            node
        )
    }

    private fun scheduleTimeout(requestId: String) {
        mainHandler.postDelayed({
            if (inFlightRequestId == requestId) {
                inFlightRequestId = null
                syncing = false
                lastError = activity.getString(R.string.wear_phone_no_reply)
            }
        }, RPC_TIMEOUT_MS)
    }

    private fun applyServer(snapshot: LlamaServerSnapshot) {
        if (isNewer(snapshot.revisioned, serverSnapshot?.revisioned)) {
            serverSnapshot = snapshot
            cache.writeServer(snapshot)
        }
    }

    private fun applyPet(snapshot: PetSnapshot) {
        if (isNewer(snapshot.revisioned, petSnapshot?.revisioned)) {
            petSnapshot = snapshot
            cache.writePet(snapshot)
        }
    }

    private fun applyTamaMessages(page: TamaMessagePage) {
        if (isNewer(page.revisioned, tamaMessages?.revisioned)) {
            tamaMessages = page
            cache.writeTamaMessages(page)
        }
    }

    private fun applyGenerationFinal(final: GenerationFinal) {
        val targetKey = "${final.targetType}:${final.targetId}"
        val watchInitiated = activeGenerationIds[final.generationId] == targetKey
        if (!watchInitiated && final.generationId in handledGenerationIds) return
        if (!watchInitiated) return
        val previous = generationFinals[final.generationId]
        if (previous == null || final.revisioned.revision > previous.revisioned.revision) {
            generationFinals[final.generationId] = final
            if (final.targetType == "chat") final.targetId.toLongOrNull()?.let { openChat(it) }
            if (final.status == "complete" && final.content.isNotBlank()) {
                if (handledGenerationIds.add(final.generationId)) handleAnswerComplete(final)
            }
            if (final.status == "complete" || final.status == "error") activeGenerationIds.remove(final.generationId)
        }
    }

    private fun handleAnswerComplete(final: GenerationFinal) {
        if (autoReadAnswers) {
            if (phoneTtsEnabled) {
                requestPhoneTts(final.content)
            } else {
                speak(final.content)
            }
        }
        if (vibrateOnAnswer) vibrate()
        showAnswerNotification(final)
    }

    private fun vibrate() {
        val vibrator = activity.getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(180L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(180L)
        }
    }

    private fun showAnswerNotification(final: GenerationFinal) {
        val manager = activity.getSystemService(NotificationManager::class.java)
        val channelId = "wear_chat_answers"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, activity.getString(R.string.wear_answer_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val intent = PendingIntent.getActivity(
            activity,
            final.generationId.hashCode(),
            Intent(activity, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (final.targetType == "tama") activity.getString(R.string.wear_tama_title) else {
            val chatTitle = final.targetId.toLongOrNull()?.let { id -> chatPage?.chats?.firstOrNull { it.id == id }?.title }
            chatTitle ?: activity.getString(R.string.wear_home_chats)
        }
        val preview = plainTextForSpeech(final.content).replace(Regex("\\s+"), " ").take(160)
        val notification = NotificationCompat.Builder(activity, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(activity).notify(final.generationId.hashCode(), notification)
    }

    private fun playAudio(file: File) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { player -> player.release() }
                prepare()
                start()
            }
        }.onFailure { lastError = it.localizedMessage ?: activity.getString(R.string.wear_message_failed) }
    }

    private fun applyTtsAudio(item: DataItem, payload: String) {
        val result = AdtWearProtocol.json.decodeFromString<TtsAudioResult>(payload)
        ttsResults[result.requestId] = result
        val asset = runCatching { DataMapItem.fromDataItem(item).dataMap.getAsset(AdtWearProtocol.KEY_ASSET) }.getOrNull()
        if (asset != null && result.status == "complete") {
            Wearable.getDataClient(activity).getFdForAsset(asset)
                .addOnSuccessListener { response ->
                    val suffix = if (result.mimeType == "audio/mpeg") ".mp3" else ".wav"
                    val file = File(activity.cacheDir, "wear_tts_${result.requestId}$suffix")
                    response.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                    ttsAudioFiles[result.requestId] = file
                    if (phoneTtsEnabled && activeTtsRequestIds.remove(result.requestId) != null) playAudio(file)
                }
                .addOnFailureListener { lastError = it.localizedMessage ?: activity.getString(R.string.wear_message_failed) }
        }
    }

    private fun applyMediaAsset(item: DataItem, payload: String) {
        val result = AdtWearProtocol.json.decodeFromString<WearMediaResult>(payload)
        mediaResults[result.mediaId] = result
        val asset = runCatching { DataMapItem.fromDataItem(item).dataMap.getAsset(AdtWearProtocol.KEY_ASSET) }.getOrNull()
        if (asset != null && result.status == "complete") {
            Wearable.getDataClient(activity).getFdForAsset(asset)
                .addOnSuccessListener { response ->
                    val suffix = when (result.mimeType) {
                        "image/png" -> ".png"
                        "image/jpeg" -> ".jpg"
                        "image/webp" -> ".webp"
                        "audio/mpeg" -> ".mp3"
                        "audio/mp4" -> ".m4a"
                        "audio/wav" -> ".wav"
                        else -> if (result.mediaType == "image") ".img" else ".aud"
                    }
                    val file = File(activity.cacheDir, "wear_media_${result.mediaPathId}$suffix")
                    response.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                    mediaFiles[result.mediaId] = file
                }
                .addOnFailureListener { lastError = it.localizedMessage ?: activity.getString(R.string.wear_message_failed) }
        }
    }

    private fun registerActiveGeneration(requestId: String, targetType: String, targetId: String) {
        activeGenerationIds[requestId] = "$targetType:$targetId"
        generationDeltas.remove(requestId)
        generationFinals.remove(requestId)
        voiceAcks.remove(requestId)
        voiceRequestStates.remove(requestId)
    }

    private fun clearGenerationStateForTarget(targetType: String, targetId: String) {
        val targetKey = "$targetType:$targetId"
        val voiceIds = voiceAcks.filter { "${it.value.targetType}:${it.value.targetId}" == targetKey }.keys.toSet()
        activeGenerationIds.entries.removeAll { it.value == targetKey }
        generationDeltas.entries.removeAll { "${it.value.targetType}:${it.value.targetId}" == targetKey }
        generationFinals.entries.removeAll { "${it.value.targetType}:${it.value.targetId}" == targetKey }
        voiceAcks.entries.removeAll { "${it.value.targetType}:${it.value.targetId}" == targetKey }
        voiceRequestStates.keys.removeAll(voiceIds)
    }

    private fun applyTranslatorState(snapshot: TranslatorStateSnapshot, force: Boolean = false) {
        if (force || isNewer(snapshot.revisioned, translatorState?.revisioned) || translatorState?.revisioned?.sourceDeviceId == activity.packageName) {
            translatorState = snapshot
            cache.writeTranslatorState(snapshot)
        }
    }

    private fun applyTranslatorTurns(page: TranslatorTurnPage) {
        if (isNewer(page.revisioned, translatorTurns?.revisioned)) {
            translatorTurns = page
            cache.writeTranslatorTurns(page)
        }
    }

    private fun applyActiveTasks(snapshot: ActiveTaskSnapshot) {
        if (isNewer(snapshot.revisioned, activeTasks?.revisioned)) {
            activeTasks = snapshot
            cache.writeActiveTasks(snapshot)
        }
    }

    private fun applyStats(snapshot: WearStatsSnapshot) {
        if (isNewer(snapshot.revisioned, statsSnapshot?.revisioned)) {
            statsSnapshot = snapshot
            cache.writeStats(snapshot)
        }
    }

    private fun applyCapabilities(snapshot: WearCapabilities) {
        if (isNewer(snapshot.revisioned, capabilities?.revisioned)) {
            capabilities = snapshot
            cache.writeCapabilities(snapshot)
        }
    }

    private fun meta(requestId: String = UUID.randomUUID().toString()): RpcMeta =
        RpcMeta(requestId, watchVersionCode = BuildConfig.VERSION_CODE, createdAtEpochMs = System.currentTimeMillis())

    private fun isNewer(newer: Revisioned, older: Revisioned?): Boolean =
        older == null || newer.revision >= older.revision
}

internal class WearLocalCache(context: Context) {
    private val prefs = context.getSharedPreferences("adt_wear_cache_v1", Context.MODE_PRIVATE)

    fun readServer(): LlamaServerSnapshot? = read("server", LlamaServerSnapshot.serializer())
    fun writeServer(value: LlamaServerSnapshot) = write("server", LlamaServerSnapshot.serializer(), value)
    fun readServers(): ServerListPage? = read("servers", ServerListPage.serializer())
    fun writeServers(value: ServerListPage) = write("servers", ServerListPage.serializer(), value)
    fun readChats(): ChatListPage? = read("chats", ChatListPage.serializer())
    fun writeChats(value: ChatListPage) = write("chats", ChatListPage.serializer(), value)
    fun readPet(): PetSnapshot? = read("pet", PetSnapshot.serializer())
    fun writePet(value: PetSnapshot) = write("pet", PetSnapshot.serializer(), value)
    fun readTamaMessages(): TamaMessagePage? = read("tama_messages", TamaMessagePage.serializer())
    fun writeTamaMessages(value: TamaMessagePage) = write("tama_messages", TamaMessagePage.serializer(), value)
    fun readOrganizerEvents(): OrganizerEventPage? = read("organizer_events", OrganizerEventPage.serializer())
    fun writeOrganizerEvents(value: OrganizerEventPage) = write("organizer_events", OrganizerEventPage.serializer(), value)
    fun readOrganizerMonth(): OrganizerMonthPage? = read("organizer_month", OrganizerMonthPage.serializer())
    fun writeOrganizerMonth(value: OrganizerMonthPage) = write("organizer_month", OrganizerMonthPage.serializer(), value)
    fun readOrganizerAlarms(): OrganizerAlarmPage? = read("organizer_alarms", OrganizerAlarmPage.serializer())
    fun writeOrganizerAlarms(value: OrganizerAlarmPage) = write("organizer_alarms", OrganizerAlarmPage.serializer(), value)
    fun readOrganizerNotes(): OrganizerNotePage? = read("organizer_notes", OrganizerNotePage.serializer())
    fun writeOrganizerNotes(value: OrganizerNotePage) = write("organizer_notes", OrganizerNotePage.serializer(), value)
    fun writePinnedNote(value: OrganizerPinnedNoteResult) = write("pinned_note", OrganizerPinnedNoteResult.serializer(), value)
    fun readTamaHub(): TamaHubSnapshot? = read("tama_hub", TamaHubSnapshot.serializer())
    fun writeTamaHub(value: TamaHubSnapshot) = write("tama_hub", TamaHubSnapshot.serializer(), value)
    fun readTamaInventory(): TamaInventoryPage? = read("tama_inventory", TamaInventoryPage.serializer())
    fun writeTamaInventory(value: TamaInventoryPage) = write("tama_inventory", TamaInventoryPage.serializer(), value)
    fun readTamaFarm(): TamaFarmSnapshot? = read("tama_farm", TamaFarmSnapshot.serializer())
    fun writeTamaFarm(value: TamaFarmSnapshot) = write("tama_farm", TamaFarmSnapshot.serializer(), value)
    fun readTranslatorTemplates(): TranslatorTemplatePage? = read("translator_templates", TranslatorTemplatePage.serializer())
    fun writeTranslatorTemplates(value: TranslatorTemplatePage) = write("translator_templates", TranslatorTemplatePage.serializer(), value)
    fun readTranslatorState(): TranslatorStateSnapshot? = read("translator_state", TranslatorStateSnapshot.serializer())
    fun writeTranslatorState(value: TranslatorStateSnapshot) = write("translator_state", TranslatorStateSnapshot.serializer(), value)
    fun readTranslatorTurns(): TranslatorTurnPage? = read("translator_turns", TranslatorTurnPage.serializer())
    fun writeTranslatorTurns(value: TranslatorTurnPage) = write("translator_turns", TranslatorTurnPage.serializer(), value)
    fun readActiveTasks(): ActiveTaskSnapshot? = read("active_tasks", ActiveTaskSnapshot.serializer())
    fun writeActiveTasks(value: ActiveTaskSnapshot) = write("active_tasks", ActiveTaskSnapshot.serializer(), value)
    fun readStats(): WearStatsSnapshot? = read("stats", WearStatsSnapshot.serializer())
    fun writeStats(value: WearStatsSnapshot) = write("stats", WearStatsSnapshot.serializer(), value)
    fun readCapabilities(): WearCapabilities? = read("capabilities", WearCapabilities.serializer())
    fun writeCapabilities(value: WearCapabilities) = write("capabilities", WearCapabilities.serializer(), value)

    private fun <T> read(key: String, serializer: KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { AdtWearProtocol.json.decodeFromString(serializer, it) }.getOrNull() }

    private fun <T> write(key: String, serializer: KSerializer<T>, value: T) {
        prefs.edit().putString(key, AdtWearProtocol.json.encodeToString(serializer, value)).apply()
    }
}

private class WatchTtsController(context: Context, private val onError: (String) -> Unit) {
    private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val result = tts.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    onError(context.getString(R.string.wear_tts_missing_language))
                }
            }
        }
    }

    fun speak(text: String) {
        if (!ready) {
            onError("")
            return
        }
        tts.speak(text.take(3_000), TextToSpeech.QUEUE_FLUSH, null, "adt-wear-${System.currentTimeMillis()}")
    }

    fun stop() {
        tts.stop()
    }

    fun close() {
        tts.shutdown()
    }
}

private enum class WearRoute { HOME, CHATS, CHAT_DETAIL, PET, ORGANIZER, TASKS, TRANSLATE, STATS, DIAGNOSTICS }

@Composable
private fun WearCompanionApp(
    store: WearCompanionStore,
    initialRoute: WearRoute = WearRoute.HOME,
    initialChatId: Long? = null
) {
    MaterialTheme {
        var route by remember { mutableStateOf(initialRoute) }
        var selectedChatId by remember { mutableStateOf(initialChatId) }
        val listState = rememberScalingLazyListState()
        LaunchedEffect(initialRoute, initialChatId) {
            when (initialRoute) {
                WearRoute.CHATS -> store.fetchChats()
                WearRoute.CHAT_DETAIL -> initialChatId?.let { store.openChat(it) } ?: store.fetchChats()
                WearRoute.PET -> store.fetchTamaHub()
                WearRoute.ORGANIZER -> {
                    store.fetchOrganizerMonth()
                    store.fetchOrganizerEvents()
                }
                WearRoute.TASKS -> store.fetchActiveTasks()
                WearRoute.TRANSLATE -> store.fetchTranslatorTemplates()
                WearRoute.STATS -> store.fetchStats()
                else -> Unit
            }
        }
        KeepScreenAwake(
            store.audioRecordingActive ||
                (route == WearRoute.TRANSLATE &&
                    (store.translatorStartPending || store.translatorStopPending || store.translatorState?.isActive == true || store.translatorState?.backendLoading == true))
        )
        Scaffold(
            timeText = { TimeText() },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
        ) {
            when (route) {
                WearRoute.HOME -> HomeScreen(
                    store,
                    listState,
                    { route = WearRoute.CHATS },
                    {
                        store.fetchTamaHub()
                        route = WearRoute.PET
                    },
                    {
                        store.fetchOrganizerMonth()
                        store.fetchOrganizerEvents()
                        store.fetchOrganizerAlarms()
                        store.fetchOrganizerNotes()
                        route = WearRoute.ORGANIZER
                    },
                    {
                        store.fetchActiveTasks()
                        route = WearRoute.TASKS
                    },
                    {
                        store.fetchTranslatorTemplates()
                        route = WearRoute.TRANSLATE
                    },
                    {
                        store.fetchStats()
                        route = WearRoute.STATS
                    },
                    { route = WearRoute.DIAGNOSTICS }
                )
                WearRoute.CHATS -> ChatsScreen(store, listState, { route = WearRoute.HOME }) { chat ->
                    selectedChatId = chat.id
                    store.openChat(chat.id)
                    route = WearRoute.CHAT_DETAIL
                }
                WearRoute.CHAT_DETAIL -> ChatDetailScreen(store, listState, selectedChatId) { route = WearRoute.CHATS }
                WearRoute.PET -> PetScreen(store, listState) { route = WearRoute.HOME }
                WearRoute.ORGANIZER -> OrganizerScreen(store, listState) { route = WearRoute.HOME }
                WearRoute.TASKS -> ActiveTasksScreen(store, listState) { route = WearRoute.HOME }
                WearRoute.TRANSLATE -> TranslatorScreen(store, listState) { route = WearRoute.HOME }
                WearRoute.STATS -> StatsScreen(store, listState) { route = WearRoute.HOME }
                WearRoute.DIAGNOSTICS -> DiagnosticsScreen(store, listState) { route = WearRoute.HOME }
            }
        }
    }
}

private fun String?.toWearRoute(): WearRoute = when (this) {
    MainActivity.ROUTE_CHATS -> WearRoute.CHATS
    MainActivity.ROUTE_CHAT_DETAIL -> WearRoute.CHAT_DETAIL
    MainActivity.ROUTE_PET -> WearRoute.PET
    MainActivity.ROUTE_CALENDAR -> WearRoute.ORGANIZER
    MainActivity.ROUTE_TASKS -> WearRoute.TASKS
    MainActivity.ROUTE_TRANSLATE -> WearRoute.TRANSLATE
    MainActivity.ROUTE_STATS -> WearRoute.STATS
    MainActivity.ROUTE_DIAGNOSTICS -> WearRoute.DIAGNOSTICS
    else -> WearRoute.HOME
}

@Composable
private fun KeepScreenAwake(enabled: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(activity, enabled) {
        if (enabled) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (enabled) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
private fun HomeScreen(
    store: WearCompanionStore,
    listState: androidx.wear.compose.material.ScalingLazyListState,
    onChats: () -> Unit,
    onPet: () -> Unit,
    onOrganizer: () -> Unit,
    onTasks: () -> Unit,
    onTranslate: () -> Unit,
    onStats: () -> Unit,
    onDiagnostics: () -> Unit
) {
    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { ScreenTitle(stringResource(R.string.wear_home_title)) }
        item { ServerControlCard(store.serverSnapshot, store.inFlightRequestId == null, store::startLlamaServer, store::stopLlamaServer) }
        item {
            Chip(onClick = onChats, label = { Text(stringResource(R.string.wear_home_chats)) }, secondaryLabel = { Text((store.chatPage?.totalCount ?: 0).toString()) }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Chip(onClick = onPet, label = { Text(stringResource(R.string.wear_home_pet)) }, secondaryLabel = { Text(store.tamaHub?.pet?.name ?: store.petSnapshot?.name ?: stringResource(R.string.wear_tama_no_pet)) }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Chip(onClick = onOrganizer, label = { Text(stringResource(R.string.wear_home_organizer)) }, secondaryLabel = { Text(stringResource(R.string.wear_organizer_full_access)) }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Chip(onClick = onTasks, label = { Text(stringResource(R.string.wear_home_tasks)) }, secondaryLabel = { Text(store.activeTasks?.tasks?.firstOrNull()?.subtitle ?: stringResource(R.string.wear_tasks_empty)) }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Chip(onClick = onTranslate, label = { Text(stringResource(R.string.wear_home_translate)) }, secondaryLabel = { Text(store.translatorState?.phase ?: "-") }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Chip(onClick = onStats, label = { Text(stringResource(R.string.wear_home_stats)) }, secondaryLabel = { Text(store.statsSnapshot?.summary?.get("cpu") ?: "-") }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Chip(onClick = { store.requestRefresh() }, enabled = !store.syncing, label = { Text(stringResource(R.string.wear_sync_phone)) }, modifier = Modifier.fillMaxWidth())
        }
        item { Chip(onClick = onDiagnostics, label = { Text(stringResource(R.string.wear_diagnostics)) }, modifier = Modifier.fillMaxWidth()) }
        item { PhoneStatus(store) }
        store.lastError?.let { item { ErrorText(it) } }
    }
}

@Composable
private fun PhoneStatus(store: WearCompanionStore) {
    val status = when {
        store.syncing -> stringResource(R.string.wear_syncing)
        store.phoneReachable -> stringResource(R.string.wear_phone_connected)
        store.genericPhoneReachable && store.bridgeUnavailable -> stringResource(R.string.wear_phone_bridge_unavailable)
        else -> stringResource(R.string.wear_waiting_for_phone)
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1B2028)).padding(9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(status, fontSize = 11.sp, color = Color(0xFFC7D0DD))
        store.lastCommandMessage?.let { Text(it, fontSize = 10.sp, color = Color(0xFFB8E6C2), maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun ServerControlCard(
    state: LlamaServerSnapshot?,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF20242C)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.wear_llama_server), fontWeight = FontWeight.Bold)
        Text(state?.label ?: stringResource(R.string.wear_waiting_for_phone), fontSize = 12.sp, color = Color(0xFFC7D0DD), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = enabled, modifier = Modifier.size(54.dp)) { Text(stringResource(R.string.wear_llama_start), fontSize = 10.sp) }
            Button(onClick = onStop, enabled = enabled, modifier = Modifier.size(54.dp)) { Text(stringResource(R.string.wear_llama_stop), fontSize = 10.sp) }
        }
        state?.error?.let { ErrorText(it) }
    }
}

@Composable
private fun ChatsScreen(
    store: WearCompanionStore,
    listState: androidx.wear.compose.material.ScalingLazyListState,
    onBack: () -> Unit,
    onChat: (ChatSummary) -> Unit
) {
    val chats = store.chatPage?.chats.orEmpty()
    var selectedFolderKey by remember { mutableStateOf<String?>(null) }
    val noFolderLabel = stringResource(R.string.wear_folder_unfiled)
    val folderGroups = chats.groupBy { it.folderId?.toString() ?: "none" }
    val folderKeys = folderGroups.keys.sortedBy { key ->
        if (key == "none") "zzzz" else chats.firstOrNull { it.folderId?.toString() == key }?.folderName?.lowercase(Locale.getDefault()) ?: key
    }
    fun folderLabel(folderKey: String): String =
        if (folderKey == "none") noFolderLabel else chats.firstOrNull { it.folderId?.toString() == folderKey }?.folderName?.takeIf { it.isNotBlank() } ?: folderKey
    val pinned = chats.filter { store.isWatchPinned(it.id) }
    val visibleChats = selectedFolderKey?.let { folderGroups[it].orEmpty() }.orEmpty()
    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            BackHeader(
                title = selectedFolderKey?.let { folderLabel(it) } ?: stringResource(R.string.wear_home_chats),
                onBack = { if (selectedFolderKey == null) onBack() else selectedFolderKey = null }
            )
        }
        item { SectionLabel(stringResource(R.string.wear_servers)) }
        items(store.serverPage?.servers.orEmpty()) { server ->
            Chip(
                onClick = { store.selectServer(server.id) },
                enabled = store.pendingServerId == null || store.pendingServerId == server.id,
                label = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text(if (store.pendingServerId == server.id) stringResource(R.string.wear_selecting) else if (server.selected) stringResource(R.string.wear_selected) else server.engine) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = { store.createChat { } },
                label = { Text(stringResource(R.string.wear_new_chat)) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (chats.isEmpty()) {
            item { Text(stringResource(if (store.phoneReachable) R.string.wear_no_chats_phone else R.string.wear_waiting_for_phone), fontSize = 12.sp) }
        } else if (selectedFolderKey == null) {
            if (pinned.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.wear_pinned_chats)) }
                items(pinned) { chat -> ChatChip(chat, store.isWatchPinned(chat.id), onChat) }
            }
            item { SectionLabel(stringResource(R.string.wear_chat_folders)) }
            folderKeys.forEach { folderKey ->
                val groupChats = folderGroups[folderKey].orEmpty()
                item {
                    Chip(
                        onClick = { selectedFolderKey = folderKey },
                        label = { Text(folderLabel(folderKey), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text(stringResource(R.string.wear_chat_count, groupChats.size)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            item { SectionLabel(stringResource(R.string.wear_folder_chats)) }
            items(visibleChats) { chat -> ChatChip(chat, store.isWatchPinned(chat.id), onChat) }
        }
    }
}

@Composable
private fun ChatChip(chat: ChatSummary, watchPinned: Boolean, onChat: (ChatSummary) -> Unit) {
    Chip(
        onClick = { onChat(chat) },
        label = { Text(if (watchPinned) "* ${chat.title}" else chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(chat.preview, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ChatDetailScreen(
    store: WearCompanionStore,
    listState: androidx.wear.compose.material.ScalingLazyListState,
    chatId: Long?,
    onBack: () -> Unit
) {
    val chat = store.chatPage?.chats.orEmpty().firstOrNull { it.id == chatId }
    val messages = chatId?.let { store.chatMessages[it]?.messages }.orEmpty()
    val selectedServerId = store.serverPage?.selectedServerId ?: chat?.pinnedServerId ?: store.serverPage?.servers?.firstOrNull()?.id
    var draft by remember(chatId) { mutableStateOf("") }
    var lastRequestId by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf<WearRecording?>(null) }
    var recordedAudio by remember { mutableStateOf<RecordedAudio?>(null) }
    var actionMessage by remember { mutableStateOf<WearMessage?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val generationText = lastRequestId?.let { id ->
        store.generationFinals[id]?.content ?: store.generationDeltas[id]?.content
    }.orEmpty()
    val context = LocalContext.current
    val dictationLauncher = dictationLauncher { draft = appendDictation(draft, it) }
    val permissionLauncher = recordPermissionLauncher(context, {
        recording = it
        store.audioRecordingActive = true
    }, { store.lastError = it })

    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item { BackHeader(title = chat?.title ?: stringResource(R.string.wear_home_chats), onBack = onBack) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { chat?.let { store.toggleWatchPin(it.id) } }, modifier = Modifier.size(52.dp)) {
                    Text(if (chat?.id?.let(store::isWatchPinned) == true) stringResource(R.string.wear_unpin) else stringResource(R.string.wear_pin), fontSize = 9.sp)
                }
                Button(onClick = { store.cancelGeneration(lastRequestId) }, modifier = Modifier.size(58.dp)) { Text(stringResource(R.string.wear_stop), fontSize = 10.sp) }
                Button(onClick = { store.speak(generationText) }, enabled = generationText.isNotBlank(), modifier = Modifier.size(52.dp)) { Text(stringResource(R.string.wear_play), fontSize = 9.sp) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ToggleButton(store.thinkingEnabled, stringResource(R.string.wear_thinking_toggle)) { store.updateThinkingEnabled(!store.thinkingEnabled) }
                ToggleButton(store.autoReadAnswers, stringResource(R.string.wear_auto_read)) { store.updateAutoReadAnswers(!store.autoReadAnswers) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ToggleButton(store.phoneTtsEnabled, stringResource(if (store.phoneTtsEnabled) R.string.wear_phone_tts else R.string.wear_watch_tts)) { store.updatePhoneTtsEnabled(!store.phoneTtsEnabled) }
                ToggleButton(store.vibrateOnAnswer, stringResource(R.string.wear_vibrate)) { store.updateVibrateOnAnswer(!store.vibrateOnAnswer) }
            }
        }
        item {
            Chip(onClick = { confirmClear = true }, label = { Text(stringResource(R.string.wear_clear_chat)) }, modifier = Modifier.fillMaxWidth())
        }
        if (confirmClear) {
            item {
                ConfirmActionCard(
                    title = stringResource(R.string.wear_clear_chat_confirm),
                    onConfirm = {
                        chatId?.let(store::clearChat)
                        confirmClear = false
                    },
                    onCancel = { confirmClear = false }
                )
            }
        }
        item { MessageInput(draft) { draft = it } }
        item {
            AudioRecorderPanel(
                recording = recording,
                recordedAudio = recordedAudio,
                onPlay = { recordedAudio?.file?.let { playLocalAudio(it) } },
                onStopRecording = {
                    val current = recording
                    if (current != null) {
                        stopWearRecording(current)
                        recording = null
                        store.audioRecordingActive = false
                        if (current.file.exists()) {
                            recordedAudio = RecordedAudio(current.file, System.currentTimeMillis() - current.startedAt)
                        }
                    }
                },
                onDiscard = {
                    recordedAudio?.file?.delete()
                    recordedAudio = null
                },
                onSend = {
                    val audio = recordedAudio
                    val id = chatId
                    val server = selectedServerId
                    if (audio != null && id != null && server != null && audio.file.exists()) {
                        lastRequestId = store.sendChatAudio(id, server, audio.file, audio.durationMs)
                        recordedAudio = null
                    }
                }
            )
        }
        item {
            InputButtons(
                recording = recording,
                onDictate = { dictationLauncher.launch(dictationIntent(context)) },
                onRecord = {
                    val current = recording
                    if (current == null) {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            recording = startWearRecording(context) { store.lastError = it }
                            if (recording != null) store.audioRecordingActive = true
                        }
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        stopWearRecording(current)
                        recording = null
                        store.audioRecordingActive = false
                        if (current.file.exists()) {
                            recordedAudio = RecordedAudio(current.file, System.currentTimeMillis() - current.startedAt)
                        }
                    }
                },
                onSend = {
                    val id = chatId
                    val server = selectedServerId
                    val text = draft.trim()
                    if (id != null && server != null && text.isNotBlank()) {
                        lastRequestId = store.sendChatText(id, server, text)
                        draft = ""
                    }
                }
            )
        }
        lastRequestId?.let { requestId ->
            item { GenerationStatus(requestId, store) }
        }
        actionMessage?.let { message ->
            item {
                MessageActionsCard(
                    message = message,
                    onSpeak = { store.speak(message.content) },
                    onPlayAudio = { store.playMedia(message.audioMediaId) },
                    onRetry = {
                        chatId?.let { id -> lastRequestId = store.retryMessage(id, selectedServerId, message) ?: lastRequestId }
                        actionMessage = null
                    },
                    onDelete = {
                        chatId?.let { id -> store.deleteMessage(id, message) }
                        actionMessage = null
                    },
                    onDismiss = { actionMessage = null }
                )
            }
        }
        items(messages.asReversed()) { message -> MessageBubble(message, store::speak, store.mediaFiles, store::requestMedia, store::playMedia) { actionMessage = message } }
    }
}

@Composable
private fun PetScreen(store: WearCompanionStore, listState: androidx.wear.compose.material.ScalingLazyListState, onBack: () -> Unit) {
    val hub = store.tamaHub
    val pet = hub?.pet ?: store.petSnapshot
    var draft by remember { mutableStateOf("") }
    var lastRequestId by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf<WearRecording?>(null) }
    var recordedAudio by remember { mutableStateOf<RecordedAudio?>(null) }
    val context = LocalContext.current
    val dictationLauncher = dictationLauncher { draft = appendDictation(draft, it) }
    val permissionLauncher = recordPermissionLauncher(context, {
        recording = it
        store.audioRecordingActive = true
    }, { store.lastError = it })

    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item { BackHeader(title = stringResource(R.string.wear_tama_title), onBack = onBack) }
        if (pet?.hasPet != true) {
            item { Text(stringResource(if (store.phoneReachable) R.string.wear_tama_no_pet_phone else R.string.wear_waiting_for_phone), fontSize = 12.sp) }
        } else {
            item { TamaScene(pet) }
            item {
                Text("${pet.name} · ${hub?.locationLabel.orEmpty().ifBlank { pet.location.orEmpty() }} · ${pet.activityLabel}", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            item { SectionLabel(stringResource(R.string.wear_tama_stats)) }
            item { StatsGrid(pet) }
            items(hub?.recentEvents.orEmpty()) { event -> CompactInfoCard(event) }
            item { SectionLabel(stringResource(R.string.wear_tama_chat)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    ToggleButton(store.thinkingEnabled, stringResource(R.string.wear_thinking_toggle)) { store.updateThinkingEnabled(!store.thinkingEnabled) }
                    ToggleButton(store.autoReadAnswers, stringResource(R.string.wear_auto_read)) { store.updateAutoReadAnswers(!store.autoReadAnswers) }
                }
            }
            if (pet.frozen) {
                item { ErrorText(stringResource(R.string.wear_tama_frozen_no_chat)) }
            } else {
                item { MessageInput(draft) { draft = it } }
                item {
                    AudioRecorderPanel(
                        recording = recording,
                        recordedAudio = recordedAudio,
                        onPlay = { recordedAudio?.file?.let { playLocalAudio(it) } },
                        onStopRecording = {
                            val current = recording
                            if (current != null) {
                                stopWearRecording(current)
                                recording = null
                                store.audioRecordingActive = false
                                if (current.file.exists()) {
                                    recordedAudio = RecordedAudio(current.file, System.currentTimeMillis() - current.startedAt)
                                }
                            }
                        },
                        onDiscard = {
                            recordedAudio?.file?.delete()
                            recordedAudio = null
                        },
                        onSend = {
                            val audio = recordedAudio
                            val petId = pet.petId
                            if (!petId.isNullOrBlank() && audio != null && audio.file.exists()) {
                                val requestId = store.sendTamaAudio(petId, audio.file, audio.durationMs)
                                if (requestId.isNotBlank()) {
                                    lastRequestId = requestId
                                    recordedAudio = null
                                }
                            }
                        }
                    )
                }
                item {
                    InputButtons(
                        recording = recording,
                        onDictate = { dictationLauncher.launch(dictationIntent(context)) },
                        onRecord = {
                            val current = recording
                            if (current == null) {
                                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    recording = startWearRecording(context) { store.lastError = it }
                                    if (recording != null) store.audioRecordingActive = true
                                }
                                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                stopWearRecording(current)
                                recording = null
                                store.audioRecordingActive = false
                                if (current.file.exists()) {
                                    recordedAudio = RecordedAudio(current.file, System.currentTimeMillis() - current.startedAt)
                                }
                            }
                        },
                        onSend = {
                            val petId = pet.petId
                            val text = draft.trim()
                            if (!petId.isNullOrBlank() && text.isNotBlank()) {
                                val requestId = store.sendTamaText(petId, text)
                                if (requestId.isNotBlank()) {
                                    lastRequestId = requestId
                                    draft = ""
                                }
                            }
                        }
                    )
                }
            }
            lastRequestId?.let { requestId ->
                item { GenerationStatus(requestId, store) }
            }
            items(store.tamaMessages?.messages.orEmpty().asReversed()) { message -> MessageBubble(message, store::speak, store.mediaFiles, store::requestMedia, store::playMedia) }
        }
    }
}

@Composable
private fun CompactInfoCard(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        maxLines = 5,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)
    )
}

@Composable
private fun TamaItemCard(item: TamaInventoryItemSummary, onUse: () -> Unit, onSell: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
        Text("${item.name} x${item.quantity}", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.type, fontSize = 10.sp, color = Color(0xFFB6C2D0))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onUse, modifier = Modifier.size(44.dp)) { Text(stringResource(R.string.wear_tama_use), fontSize = 7.sp) }
            Button(onClick = onSell, modifier = Modifier.size(44.dp)) { Text(stringResource(R.string.wear_tama_sell), fontSize = 7.sp) }
        }
    }
}

@Composable
private fun TamaFarmTileCard(tile: TamaFarmTileSummary, store: WearCompanionStore) {
    val tillLabel = stringResource(R.string.wear_tama_till)
    val waterLabel = stringResource(R.string.wear_tama_water)
    val harvestLabel = stringResource(R.string.wear_tama_harvest)
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
        Text("${stringResource(R.string.wear_tama_tile)} ${tile.id}: ${tile.title}", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${tile.status} ${tile.progressPercent}%", fontSize = 10.sp, color = Color(0xFFB6C2D0))
        tile.actionLabel?.let { label ->
            Button(onClick = {
                val action = when (label) {
                    tillLabel -> "till"
                    waterLabel -> "water"
                    harvestLabel -> "harvest"
                    else -> "plant"
                }
                val seed = store.tamaInventory?.items?.firstOrNull { it.id.startsWith("seed_") }?.id
                store.sendTamaFarmAction(action, tile.id, seed)
            }, modifier = Modifier.size(64.dp, 38.dp)) { Text(label, fontSize = 8.sp) }
        }
    }
}

@Composable
private fun OrganizerScreen(store: WearCompanionStore, listState: androidx.wear.compose.material.ScalingLazyListState, onBack: () -> Unit) {
    var showNotes by remember { mutableStateOf(false) }
    var showAlarms by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editingEvent by remember { mutableStateOf<OrganizerEventSummary?>(null) }
    var creatingEvent by remember { mutableStateOf(false) }
    var deletingEvent by remember { mutableStateOf<OrganizerEventSummary?>(null) }
    var editingNote by remember { mutableStateOf<OrganizerNoteDetail?>(null) }
    var creatingNote by remember { mutableStateOf(false) }
    var deletingNote by remember { mutableStateOf<OrganizerNoteDetail?>(null) }
    var editingAlarm by remember { mutableStateOf<OrganizerAlarmSummary?>(null) }
    var creatingAlarm by remember { mutableStateOf(false) }
    var deletingAlarm by remember { mutableStateOf<OrganizerAlarmSummary?>(null) }
    val context = LocalContext.current
    val dictationLauncher = dictationLauncher {
        query = it
        store.fetchOrganizerNotes(it)
    }
    LaunchedEffect(creatingEvent, editingEvent?.id, deletingEvent?.id) {
        if (creatingEvent || editingEvent != null || deletingEvent != null) {
            // Back header, tabs, calendar, and New Event occupy indices 0..3.
            // Wait for the conditional editor/confirmation item to enter composition.
            kotlinx.coroutines.delay(50)
            listState.animateScrollToItem(4)
        }
    }
    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item { BackHeader(title = stringResource(R.string.wear_home_organizer), onBack = onBack) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { showNotes = false; showAlarms = false; store.fetchOrganizerMonth(); store.fetchOrganizerEvents() }, modifier = Modifier.size(54.dp, 42.dp)) { Text(stringResource(R.string.wear_calendar), fontSize = 8.sp) }
                Button(onClick = { showNotes = true; showAlarms = false; store.fetchOrganizerNotes(query) }, modifier = Modifier.size(54.dp, 42.dp)) { Text(stringResource(R.string.wear_notes), fontSize = 8.sp) }
                Button(onClick = { showNotes = false; showAlarms = true; store.fetchOrganizerAlarms() }, modifier = Modifier.size(54.dp, 42.dp)) { Text(stringResource(R.string.wear_alarms), fontSize = 8.sp) }
            }
        }
        if (showAlarms) {
            item { Chip(onClick = { creatingAlarm = true; editingAlarm = null }, label = { Text(stringResource(R.string.wear_alarm_new)) }, modifier = Modifier.fillMaxWidth()) }
            if (creatingAlarm || editingAlarm != null) {
                item {
                    AlarmEditCard(
                        alarm = editingAlarm,
                        seedStartMs = System.currentTimeMillis() + 60L * 60L * 1000L,
                        onSave = { id, title, message, triggerMs, sound, enabled ->
                            store.saveOrganizerAlarm(id, editingAlarm?.eventId, title, message, triggerMs, sound, enabled)
                            creatingAlarm = false
                            editingAlarm = null
                        },
                        onCancel = {
                            creatingAlarm = false
                            editingAlarm = null
                        }
                    )
                }
            }
            deletingAlarm?.let { alarm ->
                item {
                    ConfirmActionCard(
                        title = stringResource(R.string.wear_alarm_delete_confirm),
                        onConfirm = {
                            store.deleteOrganizerAlarm(alarm.id)
                            deletingAlarm = null
                        },
                        onCancel = { deletingAlarm = null }
                    )
                }
            }
            val alarms = store.organizerAlarms?.alarms.orEmpty()
            if (alarms.isEmpty()) item { Text(stringResource(R.string.wear_no_alarms), fontSize = 12.sp) }
            items(alarms) { alarm ->
                AlarmCard(
                    alarm = alarm,
                    onToggle = { store.toggleOrganizerAlarm(alarm) },
                    onEdit = { editingAlarm = alarm },
                    onDelete = { deletingAlarm = alarm }
                )
            }
        } else if (showNotes) {
            item { Chip(onClick = { creatingNote = true; editingNote = null }, label = { Text(stringResource(R.string.wear_note_new)) }, modifier = Modifier.fillMaxWidth()) }
            item { MessageInput(query) { query = it } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { store.fetchOrganizerNotes(query) }, modifier = Modifier.size(58.dp)) { Text(stringResource(R.string.wear_search), fontSize = 8.sp) }
                    Button(onClick = { dictationLauncher.launch(dictationIntent(context)) }, modifier = Modifier.size(58.dp)) { Text(stringResource(R.string.wear_dictate), fontSize = 8.sp) }
                }
            }
            if (creatingNote || editingNote != null) {
                item {
                    NoteEditCard(
                        detail = editingNote,
                        onSave = { id, title, content ->
                            store.saveNote(id, title, content)
                            creatingNote = false
                            editingNote = null
                        },
                        onCancel = {
                            creatingNote = false
                            editingNote = null
                        }
                    )
                }
            }
            deletingNote?.let { detail ->
                item {
                    ConfirmActionCard(
                        title = stringResource(R.string.wear_note_delete_confirm),
                        onConfirm = {
                            store.deleteNote(detail.id)
                            deletingNote = null
                        },
                        onCancel = { deletingNote = null }
                    )
                }
            }
            store.selectedNoteDetail?.let { detail ->
                item {
                    OrganizerNoteDetailCard(
                        detail = detail,
                        onClose = { store.selectedNoteDetail = null },
                        onPin = { store.pinNote(detail.id) },
                        onEdit = { editingNote = detail },
                        onDelete = { deletingNote = detail },
                        onPlayAudio = { store.playMedia(detail.audioMediaId) }
                    )
                }
            }
            val notes = store.organizerNotes?.notes.orEmpty()
            if (notes.isEmpty()) item { Text(stringResource(R.string.wear_no_notes), fontSize = 12.sp) }
            items(notes) { note ->
                Chip(
                    onClick = { store.openNote(note.id) },
                    label = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(if (note.hasAudio) stringResource(R.string.wear_voice_note) else note.preview, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            item { MonthCalendarCard(store.organizerMonth, store::fetchOrganizerMonth) }
            item {
                Chip(
                    onClick = {
                        creatingEvent = true
                        editingEvent = null
                        deletingEvent = null
                    },
                    label = { Text(stringResource(R.string.wear_event_new)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (creatingEvent || editingEvent != null) {
                item {
                    EventEditCard(
                        event = editingEvent,
                        seedStartMs = store.organizerMonth?.selectedDayEpochMs ?: System.currentTimeMillis(),
                        onSave = { id, title, desc, location, startMs, endMs, allDay, alarmMs ->
                            store.saveOrganizerEvent(id, title, desc, location, startMs, endMs, allDay, null, alarmMs)
                            creatingEvent = false
                            editingEvent = null
                        },
                        onCancel = {
                            creatingEvent = false
                            editingEvent = null
                        }
                    )
                }
            }
            deletingEvent?.let { event ->
                item {
                    ConfirmActionCard(
                        title = stringResource(R.string.wear_event_delete_confirm),
                        onConfirm = {
                            store.deleteOrganizerEvent(event.id)
                            deletingEvent = null
                        },
                        onCancel = { deletingEvent = null }
                    )
                }
            }
            val selectedDayEvents = store.organizerMonth?.selectedDayEvents.orEmpty()
            val fallbackEvents = store.organizerEvents?.events.orEmpty()
            val events = selectedDayEvents.ifEmpty { fallbackEvents }
            if (events.isEmpty()) {
                item { Text(stringResource(R.string.wear_no_events), fontSize = 12.sp) }
            } else if (selectedDayEvents.isEmpty() && store.organizerMonth != null) {
                item { SectionLabel(stringResource(R.string.wear_next_events)) }
            }
            items(events, key = { "event_${it.id}" }) { event ->
                OrganizerEventCard(
                    event = event,
                    onEdit = {
                        creatingEvent = false
                        deletingEvent = null
                        editingEvent = event
                    },
                    onDelete = {
                        creatingEvent = false
                        editingEvent = null
                        deletingEvent = event
                    }
                )
            }
        }
    }
}

@Composable
private fun TranslatorScreen(store: WearCompanionStore, listState: androidx.wear.compose.material.ScalingLazyListState, onBack: () -> Unit) {
    val state = store.translatorState
    val templates = store.translatorTemplates?.templates.orEmpty()
    var selectedTemplateId by remember(templates, state?.selectedTemplateId) { mutableStateOf(state?.selectedTemplateId ?: templates.firstOrNull()?.id ?: -1L) }
    val selectedTemplate = templates.firstOrNull { it.id == selectedTemplateId }
    LaunchedEffect(Unit) {
        while (true) {
            store.fetchTranslatorTemplates()
            delay(10_000L)
        }
    }
    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item { BackHeader(title = stringResource(R.string.wear_home_translate), onBack = onBack) }
        item {
            TranslatorOverlayCard(
                state = state,
                selectedTemplateName = selectedTemplate?.name ?: state?.selectedTemplateName,
                pendingStart = store.translatorStartPending
            )
        }
        item {
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
                Text(state?.phase ?: stringResource(R.string.wear_translator_idle), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(state?.status ?: stringResource(R.string.wear_waiting_for_phone), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                state?.selectedTemplateName?.let { Text(stringResource(R.string.wear_translator_selected, it), fontSize = 10.sp, color = Color(0xFFB8E6C2), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val backendLine = listOf(state?.backendLabel, state?.modelLabel).filterNot { it.isNullOrBlank() }.joinToString(" · ")
                if (backendLine.isNotBlank()) Text(backendLine, fontSize = 10.sp, color = Color(0xFFB6C2D0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state?.backendLoading == true || store.translatorStartPending) {
                    Text(state?.backendStatus?.ifBlank { stringResource(R.string.wear_translator_loading_backend) } ?: stringResource(R.string.wear_translator_loading_backend), fontSize = 10.sp, color = Color(0xFFFFD27A), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("${state?.elapsedSeconds ?: 0}s ${"I".repeat((((state?.inputLevel ?: 0f) * 8f).toInt()).coerceIn(1, 8))}", fontSize = 11.sp)
            }
        }
        item { SectionLabel(stringResource(R.string.wear_translator_templates)) }
        items(templates) { template ->
            Chip(
                onClick = { selectedTemplateId = template.id },
                label = { Text(if (selectedTemplateId == template.id) stringResource(R.string.wear_translator_selected_short, template.name) else template.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text("${template.speaker1Language} / ${template.speaker2Language} ${template.backendLabel}") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { if (selectedTemplateId > 0L) store.startTranslator(selectedTemplateId) }, enabled = !store.translatorStartPending, modifier = Modifier.size(58.dp)) { Text(stringResource(if (store.translatorStartPending) R.string.wear_loading else R.string.wear_start), fontSize = 8.sp) }
                Button(onClick = { store.stopTranslator() }, enabled = !store.translatorStopPending, modifier = Modifier.size(58.dp)) { Text(stringResource(if (store.translatorStopPending) R.string.wear_stopping else R.string.wear_stop), fontSize = 8.sp) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { store.setTranslatorSpeaker(1) }, modifier = Modifier.size(58.dp)) { Text(if (state?.currentSpeaker == 1) stringResource(R.string.wear_speaker_one_active) else stringResource(R.string.wear_speaker_one), fontSize = 8.sp) }
                Button(onClick = { store.setTranslatorSpeaker(2) }, modifier = Modifier.size(58.dp)) { Text(if (state?.currentSpeaker == 2) stringResource(R.string.wear_speaker_two_active) else stringResource(R.string.wear_speaker_two), fontSize = 8.sp) }
            }
        }
        item { SectionLabel(stringResource(R.string.wear_translator_turns)) }
        items(store.translatorTurns?.turns.orEmpty().asReversed()) { turn ->
            TranslatorTurnCard(turn)
        }
    }
}

@Composable
private fun ActiveTasksScreen(store: WearCompanionStore, listState: androidx.wear.compose.material.ScalingLazyListState, onBack: () -> Unit) {
    LaunchedEffect(Unit) { store.fetchActiveTasks() }
    val tasks = store.activeTasks?.tasks.orEmpty()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { BackHeader(title = stringResource(R.string.wear_home_tasks), onBack = onBack) }
        item { PhoneStatus(store) }
        if (tasks.isEmpty()) {
            item { Text(stringResource(R.string.wear_tasks_empty), fontSize = 12.sp, color = Color(0xFFC7D0DD)) }
        } else {
            items(tasks) { task ->
                ActiveTaskCard(
                    task = task,
                    onPause = { store.pauseTask(task.taskId) },
                    onResume = { store.resumeTask(task.taskId) },
                    onCancel = { store.cancelTask(task.taskId) }
                )
            }
        }
        item { Chip(onClick = { store.fetchActiveTasks() }, label = { Text(stringResource(R.string.wear_refresh)) }, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun StatsScreen(
    store: WearCompanionStore,
    listState: androidx.wear.compose.material.ScalingLazyListState,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { store.fetchStats() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            store.fetchStats()
        }
    }
    val snapshot = store.statsSnapshot
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { BackHeader(title = stringResource(R.string.wear_home_stats), onBack = onBack) }
        item { PhoneStatus(store) }
        item {
            Text(
                if (snapshot?.enabled == true) stringResource(R.string.wear_stats_collecting) else stringResource(R.string.wear_stats_cached),
                fontSize = 10.sp,
                color = Color(0xFFB6C2D0)
            )
        }
        snapshot?.summary?.entries?.let { entries ->
            items(entries.toList()) { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label.uppercase(Locale.getDefault()), fontSize = 11.sp, color = Color(0xFFB6C2D0))
                    Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        snapshot?.series.orEmpty().forEach { series ->
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${series.id.uppercase(Locale.getDefault())} · ${series.unit}", fontSize = 10.sp, color = Color(0xFFB6C2D0))
                    MiniStatsChart(series)
                }
            }
        }
        item { Chip(onClick = { store.fetchStats() }, label = { Text(stringResource(R.string.wear_refresh)) }, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun MiniStatsChart(series: WearStatsSeries) {
    val values = series.points.mapNotNull { it.value }
    Canvas(modifier = Modifier.fillMaxWidth().height(46.dp)) {
        if (values.size < 2) return@Canvas
        val minValue = values.minOrNull() ?: 0f
        val maxValue = values.maxOrNull() ?: 1f
        val span = (maxValue - minValue).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1).toFloat()
        var previous: androidx.compose.ui.geometry.Offset? = null
        values.forEachIndexed { index, value ->
            val point = androidx.compose.ui.geometry.Offset(
                x = index * stepX,
                y = size.height - ((value - minValue) / span) * size.height
            )
            previous?.let { drawLine(Color(0xFF8BD3DD), it, point, strokeWidth = 3f) }
            previous = point
        }
    }
}

@Composable
private fun ActiveTaskCard(
    task: ActiveTaskSummary,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111827))
            .padding(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(task.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            task.progressPercent?.let {
                Text("$it%", fontSize = 11.sp, color = Color(0xFF93C5FD))
            }
        }
        val statusLine = listOf(task.stage, task.subtitle.ifBlank { task.state })
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
        Text(statusLine.ifBlank { task.state }, fontSize = 11.sp, color = Color(0xFFC7D0DD), maxLines = 2, overflow = TextOverflow.Ellipsis)
        TaskProgressStrip(task)
        val detailLine = listOf(
            task.speed.takeIf { it.isNotBlank() },
            task.etaSeconds?.let { "${it}s" }
        ).filterNotNull().joinToString(" · ")
        if (detailLine.isNotBlank()) {
            Text(detailLine, fontSize = 10.sp, color = Color(0xFFB6C2D0), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            if (task.canPause) {
                Button(onClick = onPause, modifier = Modifier.size(54.dp, 36.dp)) {
                    Text(stringResource(R.string.wear_pause), fontSize = 8.sp, maxLines = 1)
                }
            }
            if (task.canResume) {
                Button(onClick = onResume, modifier = Modifier.size(58.dp, 36.dp)) {
                    Text(stringResource(R.string.wear_resume), fontSize = 8.sp, maxLines = 1)
                }
            }
            if (task.canCancel) {
                Button(onClick = onCancel, modifier = Modifier.size(58.dp, 36.dp), colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4B1F26))) {
                    Text(stringResource(R.string.wear_cancel), fontSize = 8.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TaskProgressStrip(task: ActiveTaskSummary) {
    val percent = task.progressPercent?.coerceIn(0, 100)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF0B1220))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (task.indeterminate || percent == null) 0.38f else percent / 100f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (task.indeterminate || percent == null) Color(0xFF64748B) else Color(0xFF93C5FD))
        )
    }
}

@Composable
private fun TranslatorOverlayCard(state: TranslatorStateSnapshot?, selectedTemplateName: String?, pendingStart: Boolean) {
    val loading = pendingStart || state?.backendLoading == true
    val phase = state?.phase.orEmpty().uppercase(Locale.getDefault())
    val speakerLabel = stringResource(if (state?.currentSpeaker == 2) R.string.wear_speaker_two else R.string.wear_speaker_one)
    val instruction = when {
        loading -> stringResource(R.string.wear_translator_overlay_loading)
        phase.contains("SPEAK") -> stringResource(R.string.wear_translator_overlay_speaking)
        phase.contains("TRANSCRIB") -> stringResource(R.string.wear_translator_overlay_transcribing)
        phase.contains("TRANSLAT") -> stringResource(R.string.wear_translator_overlay_translating)
        state?.isActive == true -> stringResource(R.string.wear_translator_overlay_talk_now, speakerLabel)
        else -> stringResource(R.string.wear_translator_overlay_ready)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (loading) Color(0xFF3A2E18) else Color(0xFF14251D))
            .padding(10.dp)
    ) {
        Text(stringResource(R.string.wear_translator_overlay_title), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(instruction, fontSize = 13.sp, color = Color(0xFFB8E6C2), maxLines = 2, overflow = TextOverflow.Ellipsis)
        selectedTemplateName?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(R.string.wear_translator_selected, it), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val backendLine = listOf(state?.backendLabel, state?.modelLabel).filterNot { it.isNullOrBlank() }.joinToString(" · ")
        if (backendLine.isNotBlank()) Text(backendLine, fontSize = 10.sp, color = Color(0xFFB6C2D0), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DiagnosticsScreen(store: WearCompanionStore, listState: androidx.wear.compose.material.ScalingLazyListState, onBack: () -> Unit) {
    LaunchedEffect(Unit) { store.refreshDiagnostics() }
    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { BackHeader(title = stringResource(R.string.wear_diagnostics), onBack = onBack) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_package), activityLabel(store.watchIdentity)) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_signature), store.watchSignature) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_api), store.wearableApiAvailable.toString()) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_local_node), store.localNodeId) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_bridge_nodes), store.bridgeNodeCount.toString()) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_nearby_nodes), store.nearbyBridgeNodeCount.toString()) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_connected_nodes), store.connectedNodeCount.toString()) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_reciprocal_capability), store.reciprocalWearNodeCount.toString()) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_capabilities), store.capabilitiesSummary) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_protocol), AdtWearProtocol.VERSION.toString()) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_phone_version), store.phoneVersion) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_phone_protocol), store.phoneProtocol) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_last_ping), store.lastPingAt.asTimeLabel()) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_last_snapshot), store.lastSnapshotAt.asTimeLabel()) }
        item { DiagnosticRow(stringResource(R.string.wear_diag_last_data_item), "${store.lastDataItemPath} #${store.lastDataItemRevision}") }
        store.lastError?.let { item { ErrorText(it) } }
        item { Chip(onClick = { store.refreshDiagnostics() }, label = { Text(stringResource(R.string.wear_refresh_diagnostics)) }, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun InputButtons(recording: WearRecording?, onDictate: () -> Unit, onRecord: () -> Unit, onSend: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onDictate, modifier = Modifier.size(52.dp)) { Text(stringResource(R.string.wear_dictate), fontSize = 9.sp) }
        Button(onClick = onRecord, modifier = Modifier.size(52.dp)) { Text(stringResource(if (recording == null) R.string.wear_record else R.string.wear_recording), fontSize = 8.sp) }
        Button(onClick = onSend, modifier = Modifier.size(58.dp)) { Text(stringResource(R.string.wear_send), fontSize = 10.sp) }
    }
}

@Composable
private fun ToggleButton(checked: Boolean, label: String, onToggle: () -> Unit) {
    Button(
        onClick = onToggle,
        modifier = Modifier.size(width = 82.dp, height = 42.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(
                modifier = Modifier
                    .size(width = 32.dp, height = 16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (checked) Color(0xFFB8E6C2) else Color(0xFF596171))
                    .padding(2.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
private fun ConfirmActionCard(title: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF2B2430)).padding(9.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onConfirm, modifier = Modifier.size(54.dp)) { Text(stringResource(R.string.wear_confirm), fontSize = 8.sp) }
            Button(onClick = onCancel, modifier = Modifier.size(54.dp)) { Text(stringResource(R.string.wear_cancel), fontSize = 8.sp) }
        }
    }
}

@Composable
private fun MessageActionsCard(
    message: WearMessage,
    onSpeak: () -> Unit,
    onPlayAudio: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
        Text(stringResource(R.string.wear_message_actions), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSpeak, modifier = Modifier.size(44.dp)) { Text(stringResource(R.string.wear_play), fontSize = 7.sp) }
            Button(onClick = onPlayAudio, enabled = message.hasAudio, modifier = Modifier.size(44.dp)) { Text(stringResource(R.string.wear_audio), fontSize = 7.sp) }
            Button(onClick = onRetry, enabled = message.canRetry, modifier = Modifier.size(44.dp)) { Text(stringResource(R.string.wear_retry), fontSize = 7.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onDelete, enabled = message.canDelete, modifier = Modifier.size(44.dp)) { Text(stringResource(R.string.wear_delete), fontSize = 7.sp) }
            Button(onClick = onDismiss, modifier = Modifier.size(44.dp)) { Text(stringResource(R.string.wear_close), fontSize = 7.sp) }
        }
    }
}

@Composable
private fun OrganizerEventCard(event: OrganizerEventSummary, onEdit: () -> Unit = {}, onDelete: () -> Unit = {}) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
        Text(event.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(event.startAtEpochMs.asDateTimeLabel(event.allDay), fontSize = 11.sp, color = Color(0xFFB6C2D0))
        if (event.location.isNotBlank()) Text(event.location, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (event.description.isNotBlank()) Text(event.description, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (event.alarmCount > 0) Text(stringResource(R.string.wear_alarm_count, event.alarmCount), fontSize = 10.sp, color = Color(0xFFB8E6C2))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onEdit, modifier = Modifier.size(48.dp)) { Text(stringResource(R.string.wear_edit), fontSize = 7.sp) }
            Button(onClick = onDelete, modifier = Modifier.size(48.dp)) { Text(stringResource(R.string.wear_delete), fontSize = 7.sp) }
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: OrganizerAlarmSummary,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
        Text(alarm.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(alarm.triggerAtEpochMs.asDateTimeLabel(false), fontSize = 11.sp, color = Color(0xFFB6C2D0))
        if (alarm.message.isNotBlank()) Text(alarm.message, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(if (alarm.enabled) R.string.wear_alarm_enabled else R.string.wear_alarm_disabled), fontSize = 10.sp, color = if (alarm.enabled) Color(0xFFB8E6C2) else Color(0xFFFFA0A0))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onToggle, modifier = Modifier.size(46.dp)) { Text(stringResource(R.string.wear_toggle), fontSize = 7.sp) }
            Button(onClick = onEdit, modifier = Modifier.size(46.dp)) { Text(stringResource(R.string.wear_edit), fontSize = 7.sp) }
            Button(onClick = onDelete, modifier = Modifier.size(46.dp)) { Text(stringResource(R.string.wear_delete), fontSize = 7.sp) }
        }
    }
}

@Composable
private fun OrganizerNoteDetailCard(detail: OrganizerNoteDetail, onClose: () -> Unit, onPin: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onPlayAudio: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(detail.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Button(onClick = onClose, modifier = Modifier.size(34.dp)) { Text(stringResource(R.string.wear_close), fontSize = 7.sp) }
        }
        Text(detail.content, fontSize = 11.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
        if (detail.hasAudio) {
            Button(onClick = onPlayAudio, modifier = Modifier.size(width = 92.dp, height = 40.dp)) { Text(stringResource(R.string.wear_play_audio), fontSize = 8.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onPin, modifier = Modifier.size(48.dp)) { Text(stringResource(R.string.wear_pin), fontSize = 7.sp) }
            Button(onClick = onEdit, modifier = Modifier.size(48.dp)) { Text(stringResource(R.string.wear_edit), fontSize = 7.sp) }
            Button(onClick = onDelete, modifier = Modifier.size(48.dp)) { Text(stringResource(R.string.wear_delete), fontSize = 7.sp) }
        }
    }
}

@Composable
private fun MonthCalendarCard(page: OrganizerMonthPage?, fetchMonth: (Int?, Int?, Long?) -> Unit) {
    val current = page
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                val c = current ?: return@Button
                val prev = Calendar.getInstance().apply {
                    set(Calendar.YEAR, c.year)
                    set(Calendar.MONTH, c.month - 2)
                    set(Calendar.DAY_OF_MONTH, 1)
                }
                fetchMonth(prev.get(Calendar.YEAR), prev.get(Calendar.MONTH) + 1, null)
            }, modifier = Modifier.size(36.dp)) { Text("<", fontSize = 10.sp) }
            Text(current?.monthTitle() ?: stringResource(R.string.wear_calendar), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Button(onClick = {
                val c = current ?: return@Button
                val next = Calendar.getInstance().apply {
                    set(Calendar.YEAR, c.year)
                    set(Calendar.MONTH, c.month)
                    set(Calendar.DAY_OF_MONTH, 1)
                }
                fetchMonth(next.get(Calendar.YEAR), next.get(Calendar.MONTH) + 1, null)
            }, modifier = Modifier.size(36.dp)) { Text(">", fontSize = 10.sp) }
        }
        if (current == null) {
            Text(stringResource(R.string.wear_waiting_for_phone), fontSize = 11.sp)
        } else {
            val weekdayLabels = listOf(
                stringResource(R.string.wear_week_monday),
                stringResource(R.string.wear_week_tuesday),
                stringResource(R.string.wear_week_wednesday),
                stringResource(R.string.wear_week_thursday),
                stringResource(R.string.wear_week_friday),
                stringResource(R.string.wear_week_saturday),
                stringResource(R.string.wear_week_sunday)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.fillMaxWidth()) {
                weekdayLabels.forEach { label ->
                    Box(modifier = Modifier.size(23.dp, 16.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 7.sp, color = Color(0xFFB6C2D0), maxLines = 1)
                    }
                }
            }
            val paddedDays = List((current.firstDayOfWeek - 1).coerceIn(0, 6)) { null } + current.days.map { it as OrganizerMonthDay? }
            val rows = paddedDays.chunked(7)
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { day ->
                        if (day == null) {
                            Spacer(modifier = Modifier.size(23.dp))
                        } else {
                            val selected = day.dayStartEpochMs == current.selectedDayEpochMs
                            val hasEvents = day.eventCount > 0
                            Button(
                                onClick = { fetchMonth(current.year, current.month, day.dayStartEpochMs) },
                                modifier = Modifier.size(23.dp),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = when {
                                        selected -> Color(0xFF1D5F48)
                                        hasEvents -> Color(0xFF574919)
                                        else -> Color(0xFF2A303B)
                                    },
                                    contentColor = when {
                                        selected -> Color(0xFFB8E6C2)
                                        hasEvents -> Color(0xFFFFD27A)
                                        else -> Color.White
                                    }
                                )
                            ) {
                                Text(
                                    day.dayOfMonth.toString(),
                                    fontSize = 7.sp,
                                    fontWeight = if (selected || hasEvents) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        selected -> Color(0xFFB8E6C2)
                                        hasEvents -> Color(0xFFFFD27A)
                                        else -> Color.White
                                    }
                                )
                            }
                        }
                    }
                    repeat(7 - row.size) { Spacer(modifier = Modifier.size(23.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EventEditCard(
    event: OrganizerEventSummary?,
    seedStartMs: Long,
    onSave: (Long?, String, String, String, Long, Long?, Boolean, Long?) -> Unit,
    onCancel: () -> Unit
) {
    var title by remember(event) { mutableStateOf(event?.title.orEmpty()) }
    var date by remember(event) { mutableStateOf((event?.startAtEpochMs ?: seedStartMs).asEditDate()) }
    var time by remember(event) { mutableStateOf((event?.startAtEpochMs ?: seedStartMs).asEditTime()) }
    var endTime by remember(event) { mutableStateOf(event?.endAtEpochMs?.asEditTime().orEmpty()) }
    var location by remember(event) { mutableStateOf(event?.location.orEmpty()) }
    var description by remember(event) { mutableStateOf(event?.description.orEmpty()) }
    var allDay by remember(event) { mutableStateOf(event?.allDay ?: false) }
    var alarm by remember(event) { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
        Text(stringResource(if (event == null) R.string.wear_event_new else R.string.wear_event_edit), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        MessageInput(title) { title = it }
        MessageInput(date) { date = it }
        MessageInput(time) { time = it }
        MessageInput(endTime) { endTime = it }
        ToggleButton(allDay, stringResource(R.string.wear_all_day)) { allDay = !allDay }
        MessageInput(location) { location = it }
        MessageInput(description) { description = it }
        MessageInput(alarm) { alarm = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                val startMs = parseDateTime(date, time) ?: return@Button
                val endMs = endTime.takeIf { it.isNotBlank() }?.let { parseDateTime(date, it) }
                val alarmMs = alarm.takeIf { it.isNotBlank() }?.let { parseDateTime(date, it) }
                onSave(event?.id, title, description, location, startMs, endMs, allDay, alarmMs)
            }, modifier = Modifier.size(52.dp)) { Text(stringResource(R.string.wear_save), fontSize = 8.sp) }
            Button(onClick = onCancel, modifier = Modifier.size(52.dp)) { Text(stringResource(R.string.wear_cancel), fontSize = 8.sp) }
        }
    }
}

@Composable
private fun AlarmEditCard(
    alarm: OrganizerAlarmSummary?,
    seedStartMs: Long,
    onSave: (Long?, String, String, Long, Boolean, Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var title by remember(alarm) { mutableStateOf(alarm?.title.orEmpty()) }
    var date by remember(alarm) { mutableStateOf((alarm?.triggerAtEpochMs ?: seedStartMs).asEditDate()) }
    var time by remember(alarm) { mutableStateOf((alarm?.triggerAtEpochMs ?: seedStartMs).asEditTime()) }
    var message by remember(alarm) { mutableStateOf(alarm?.message.orEmpty()) }
    var sound by remember(alarm) { mutableStateOf(alarm?.soundEnabled ?: true) }
    var enabled by remember(alarm) { mutableStateOf(alarm?.enabled ?: true) }
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
        Text(stringResource(if (alarm == null) R.string.wear_alarm_new else R.string.wear_alarm_edit), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        MessageInput(title) { title = it }
        MessageInput(date) { date = it }
        MessageInput(time) { time = it }
        MessageInput(message) { message = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            ToggleButton(sound, stringResource(R.string.wear_alarm_sound)) { sound = !sound }
            ToggleButton(enabled, stringResource(R.string.wear_alarm_enabled_short)) { enabled = !enabled }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                val triggerMs = parseDateTime(date, time) ?: return@Button
                onSave(alarm?.id, title, message, triggerMs, sound, enabled)
            }, modifier = Modifier.size(52.dp)) { Text(stringResource(R.string.wear_save), fontSize = 8.sp) }
            Button(onClick = onCancel, modifier = Modifier.size(52.dp)) { Text(stringResource(R.string.wear_cancel), fontSize = 8.sp) }
        }
    }
}

@Composable
private fun NoteEditCard(detail: OrganizerNoteDetail?, onSave: (Int?, String, String) -> Unit, onCancel: () -> Unit) {
    var title by remember(detail) { mutableStateOf(detail?.title.orEmpty()) }
    var content by remember(detail) { mutableStateOf(detail?.content.orEmpty()) }
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
        Text(stringResource(if (detail == null) R.string.wear_note_new else R.string.wear_note_edit), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        MessageInput(title) { title = it }
        MessageInput(content) { content = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { onSave(detail?.id, title, content) }, modifier = Modifier.size(52.dp)) { Text(stringResource(R.string.wear_save), fontSize = 8.sp) }
            Button(onClick = onCancel, modifier = Modifier.size(52.dp)) { Text(stringResource(R.string.wear_cancel), fontSize = 8.sp) }
        }
    }
}

@Composable
private fun TranslatorTurnCard(turn: TranslatorTurnSummary) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
        Text(stringResource(if (turn.speaker == 1) R.string.wear_speaker_one else R.string.wear_speaker_two), fontSize = 10.sp, color = Color(0xFFB6C2D0))
        Text(turn.originalText, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(turn.errorMessage ?: turn.translatedText.orEmpty(), fontSize = 12.sp, color = if (turn.isError) Color(0xFFFFA0A0) else Color(0xFFB8E6C2), maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AudioRecorderPanel(
    recording: WearRecording?,
    recordedAudio: RecordedAudio?,
    onPlay: () -> Unit,
    onStopRecording: () -> Unit,
    onDiscard: () -> Unit,
    onSend: () -> Unit
) {
    if (recording == null && recordedAudio == null) return
    var elapsed by remember(recording, recordedAudio) { mutableStateOf(0L) }
    var amplitude by remember(recording) { mutableStateOf(0) }
    LaunchedEffect(recording) {
        while (recording != null) {
            elapsed = System.currentTimeMillis() - recording.startedAt
            amplitude = runCatching { recording.recorder.maxAmplitude }.getOrDefault(0)
            if (elapsed >= AdtWearProtocol.MAX_VOICE_DURATION_MS) {
                onStopRecording()
                break
            }
            delay(180L)
        }
    }
    val displayMs = recordedAudio?.durationMs ?: elapsed
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E2430)).padding(9.dp)) {
        Text(
            if (recording != null) stringResource(R.string.wear_recording_audio) else stringResource(R.string.wear_audio_ready),
            fontSize = 11.sp,
            color = Color(0xFFB8E6C2)
        )
        Text("${displayMs / 1000}s ${"I".repeat(((amplitude / 4000).coerceIn(1, 8)))}", fontSize = 12.sp, maxLines = 1)
        if (recordedAudio != null && recordedAudio.durationMs >= AdtWearProtocol.MAX_VOICE_DURATION_MS) {
            Text(stringResource(R.string.wear_audio_max_duration), fontSize = 10.sp, color = Color(0xFFFFD27A))
        }
        if (recordedAudio != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onPlay, modifier = Modifier.size(46.dp)) { Text(stringResource(R.string.wear_play), fontSize = 8.sp) }
                Button(onClick = onDiscard, modifier = Modifier.size(52.dp)) { Text(stringResource(R.string.wear_discard), fontSize = 7.sp) }
                Button(onClick = onSend, modifier = Modifier.size(46.dp)) { Text(stringResource(R.string.wear_send), fontSize = 8.sp) }
            }
        }
    }
}

@Composable
private fun GenerationStatus(requestId: String, store: WearCompanionStore) {
    val final = store.generationFinals[requestId]
    val delta = store.generationDeltas[requestId]
    val ack = store.voiceAcks[requestId]
    val voiceState = store.voiceRequestStates[requestId]
    val waitingForVoiceAcceptance = final == null && delta == null && voiceState != null && ack?.status != "accepted"
    val text = when {
        final?.status == "error" -> final.error?.localizedMessage ?: stringResource(R.string.wear_error_short)
        final?.status == "complete" -> stringResource(R.string.wear_answer_ready)
        waitingForVoiceAcceptance -> voiceState ?: stringResource(R.string.wear_voice_uploading)
        else -> stringResource(R.string.wear_thinking)
    }
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF20242C)).padding(9.dp)) {
        Text(
            when {
                final?.status == "error" -> stringResource(R.string.wear_error_short)
                final?.status == "complete" -> stringResource(R.string.wear_answer_ready)
                waitingForVoiceAcceptance -> stringResource(R.string.wear_audio_message)
                else -> stringResource(R.string.wear_thinking)
            },
            fontSize = 11.sp,
            color = Color(0xFFB8E6C2)
        )
        Text(text, fontSize = 12.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = store::stopSpeaking, modifier = Modifier.size(46.dp)) { Text(stringResource(R.string.wear_stop), fontSize = 8.sp) }
        }
    }
}

@Composable
private fun TamaScene(pet: PetSnapshot) {
    Box(modifier = Modifier.fillMaxWidth().height(118.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF20242C)), contentAlignment = Alignment.BottomCenter) {
        AssetImage(pet.backgroundAssetId, Modifier.fillMaxSize(), ContentScale.Crop)
        AssetImage(pet.spriteAssetId, Modifier.size(86.dp), ContentScale.Fit)
    }
}

@Composable
private fun AssetImage(assetPath: String?, modifier: Modifier, contentScale: ContentScale) {
    if (assetPath.isNullOrBlank()) return
    val context = LocalContext.current
    val image = remember(assetPath) {
        runCatching { context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
    } ?: return
    Image(bitmap = image, contentDescription = null, modifier = modifier, contentScale = contentScale)
}

@Composable
private fun StatsGrid(pet: PetSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatRow(stringResource(R.string.wear_stat_hunger), pet.hungerPercent)
        StatRow(stringResource(R.string.wear_stat_happiness), pet.happinessPercent)
        StatRow(stringResource(R.string.wear_stat_health), pet.healthPercent)
        StatRow(stringResource(R.string.wear_stat_energy), pet.energyPercent)
        StatRow(stringResource(R.string.wear_stat_hygiene), pet.hygienePercent)
    }
}

@Composable
private fun StatRow(label: String, value: Int) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF242A33)).padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp)
        Text("$value%", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MessageInput(draft: String, onDraft: (String) -> Unit) {
    BasicTextField(
        value = draft,
        onValueChange = onDraft,
        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E2430)).padding(10.dp),
        decorationBox = { inner ->
            if (draft.isBlank()) Text(stringResource(R.string.wear_type_message), color = Color(0xFF8792A2), fontSize = 12.sp)
            inner()
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: WearMessage,
    speak: (String) -> Unit,
    mediaFiles: Map<String, File>,
    requestMedia: (String?) -> Unit,
    playMedia: (String?) -> Unit,
    onLongPress: () -> Unit = {}
) {
    val isUser = message.role == "user"
    val imageFile = message.imageMediaId?.let { mediaFiles[it] }
    LaunchedEffect(message.imageMediaId) {
        if (message.hasImage && imageFile == null) requestMedia(message.imageMediaId)
    }
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (isUser) Color(0xFF1F5E66) else Color(0xFF2B3040)).combinedClickable(onClick = {}, onLongClick = onLongPress).padding(9.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(if (isUser) stringResource(R.string.wear_sender_you) else stringResource(R.string.wear_sender_assistant), fontSize = 10.sp, color = Color(0xFFB6C2D0))
            if (!isUser) Button(onClick = { speak(message.speechText ?: message.content) }, modifier = Modifier.size(34.dp)) { Text(stringResource(R.string.wear_play), fontSize = 7.sp) }
        }
        if (imageFile != null) {
            FileImage(imageFile, Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(10.dp)))
        } else if (message.hasImage) {
            Chip(onClick = { requestMedia(message.imageMediaId) }, label = { Text(stringResource(R.string.wear_load_image)) }, modifier = Modifier.fillMaxWidth())
        }
        if (message.hasAudio) {
            Chip(onClick = { playMedia(message.audioMediaId) }, label = { Text(stringResource(R.string.wear_play_audio)) }, modifier = Modifier.fillMaxWidth())
        }
        WearMarkdownText(message.error ?: message.content)
    }
}

@Composable
private fun FileImage(file: File, modifier: Modifier) {
    val image = remember(file.absolutePath, file.lastModified()) {
        runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
    } ?: return
    Image(bitmap = image, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

@Composable
private fun WearMarkdownText(content: String) {
    val lines = content.lines()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                line.trimStart().startsWith("#") -> Text(
                    line.trimStart('#', ' '),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Text(
                    "- ${line.trimStart().drop(2)}",
                    fontSize = 12.sp
                )
                else -> Text(
                    line.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1").replace(Regex("`([^`]*)`"), "$1"),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B2028)).padding(8.dp)) {
        Text(label, fontSize = 10.sp, color = Color(0xFF9AA8BA), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 12.sp, color = Color(0xFFE8EEF8), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onBack, modifier = Modifier.size(36.dp)) { Text("<", fontSize = 14.sp) }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ScreenTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, color = Color(0xFF9AA8BA), fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

@Composable
private fun ErrorText(text: String) {
    Text(stringResource(R.string.wear_error, text), fontSize = 11.sp, color = Color(0xFFFFA0A0), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun dictationLauncher(onText: (String) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.maxByOrNull { it.length }
            .orEmpty()
        if (text.isNotBlank()) onText(text)
    }

private fun appendDictation(current: String, dictated: String): String {
    val clean = dictated.trim()
    if (clean.isBlank()) return current
    val trimmed = current.trimEnd()
    return if (trimmed.isBlank()) clean else "$trimmed $clean"
}

private fun plainTextForSpeech(text: String): String =
    text.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("[*_~>#]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

@Composable
private fun recordPermissionLauncher(
    context: Context,
    onRecording: (WearRecording) -> Unit,
    onError: (String) -> Unit
) = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) {
        startWearRecording(context, onError)?.let(onRecording)
    } else {
        onError(context.getString(R.string.wear_record_permission_denied))
    }
}

private fun dictationIntent(context: Context): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.wear_type_message))
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 20_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
    }

private data class WearRecording(val recorder: MediaRecorder, val file: File, val startedAt: Long)
private data class RecordedAudio(val file: File, val durationMs: Long)

private fun startWearRecording(context: Context, onError: (String) -> Unit): WearRecording? =
    runCatching {
        val file = File(context.cacheDir, "wear_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(64_000)
        recorder.setAudioSamplingRate(16_000)
        recorder.setOutputFile(file.absolutePath)
        recorder.prepare()
        recorder.start()
        WearRecording(recorder, file, System.currentTimeMillis())
    }.getOrElse { error ->
        onError(error.localizedMessage ?: context.getString(R.string.wear_record_failed))
        null
    }

private fun stopWearRecording(recording: WearRecording) {
    runCatching { recording.recorder.stop() }
    recording.recorder.release()
}

private fun playLocalAudio(file: File) {
    runCatching {
        MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { player -> player.release() }
            prepare()
            start()
        }
    }
}

private fun Long.asTimeLabel(): String =
    if (this <= 0L) "-" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(this))

private fun Long.asDateTimeLabel(allDay: Boolean = false): String {
    if (this <= 0L) return "-"
    val date = Date(this)
    val dateText = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
    return if (allDay) dateText else "$dateText ${DateFormat.getTimeInstance(DateFormat.SHORT).format(date)}"
}

private fun Long.asEditDate(): String =
    if (this <= 0L) "" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(this))

private fun Long.asEditTime(): String =
    if (this <= 0L) "" else SimpleDateFormat("HH:mm", Locale.US).format(Date(this))

private fun parseDateTime(date: String, time: String): Long? =
    runCatching {
        val normalizedTime = time.ifBlank { "09:00" }
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
            .parse("${date.trim()} ${normalizedTime.trim()}")
            ?.time
    }.getOrNull()

private fun OrganizerMonthPage.monthTitle(): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, 1)
    }
    return SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(calendar.time)
}

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
private fun signingSha256Short(context: Context): String {
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
    return MessageDigest.getInstance("SHA-256").digest(certBytes).joinToString("") { "%02x".format(it) }.take(16)
}

private fun activityLabel(value: String): String = value
