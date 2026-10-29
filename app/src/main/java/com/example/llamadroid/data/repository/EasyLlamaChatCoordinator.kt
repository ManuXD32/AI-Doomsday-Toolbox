package com.example.llamadroid.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.easy.EasyContextSizer
import com.example.llamadroid.data.easy.EasyGgufFlags
import com.example.llamadroid.data.easy.EasyModelCatalog
import com.example.llamadroid.data.easy.EasyModelKind
import com.example.llamadroid.data.easy.easyModeCardModelId
import com.example.llamadroid.data.easy.EasyModeSettings
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.LlamaLoadMode
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaServerUsageLease
import com.example.llamadroid.service.checkLlamaServerPort
import com.example.llamadroid.service.ManagedLlamaServerCoordinator
import com.example.llamadroid.service.ManagedLlamaServerException
import com.example.llamadroid.service.ManagedLlamaServerInfo
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.ui.ai.llama.chatlist.newChatApiParamsForServer
import com.example.llamadroid.util.GGUFParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class EasyLlamaChatTarget(val card: LlamaServerCardEntity, val server: LlamaServerEntity)
data class EasyLlamaChatSession(val chatId: Long, val serverId: Long)
data class EasyModelSwitch(val serverId: Long, val contextTokens: Int)

/**
 * Filename of the installed vision projector that belongs to the main model [modelFilename]:
 * the curated bundle's projector (same install prefix), else an installed projector sharing the
 * model's leading name token. Pure so it can be unit tested; [installed] must be downloaded rows.
 */
internal fun easyProjectorFilenameFor(modelFilename: String, installed: List<ModelEntity>): String? {
    val projectors = installed.filter { it.type == ModelType.VISION_PROJECTOR && it.isDownloaded }
    if (projectors.isEmpty()) return null
    EasyModelCatalog.entriesFor(EasyModelKind.CHAT).forEach { entry ->
        val bundle = EasyModelCatalog.llamaBundle(entry) ?: return@forEach
        val main = bundle.files.firstOrNull { it.type == ModelType.LLM && it.matchesInstalledFilename(modelFilename) }
            ?: return@forEach
        val projector = bundle.files.firstOrNull { it.type == ModelType.VISION_PROJECTOR } ?: return@forEach
        val prefix = modelFilename.removeSuffix(main.localFilename).removeSuffix("-")
        val expected = projector.installedFilename(prefix)
        projectors.firstOrNull { it.filename == expected }?.let { return it.filename }
    }
    val token = modelFilename.substringBefore('-', "").takeIf { it.isNotBlank() } ?: return null
    return projectors.firstOrNull { it.filename.startsWith("$token-") }?.filename
}

fun isEasyLlamaChatModel(model: ModelEntity): Boolean =
    model.type in setOf(ModelType.LLM, ModelType.VISION) && model.isDownloaded &&
        model.path.endsWith(".gguf", ignoreCase = true) && File(model.path).let { it.isFile && it.canRead() }

internal fun easyLlamaProfile(
    model: ModelEntity,
    port: Int,
    knownContext: Int?,
    processors: Int,
    /** Easy-mode context (per-model override or the RAM-based recommendation); null keeps the 8K baseline. */
    contextOverride: Int? = null
): LlamaServerLaunchProfile =
    LlamaServerLaunchProfile(
        modelPath = model.path,
        mmprojPath = model.mmprojPath?.takeIf { File(it).isFile },
        visionEnabled = (model.isVision || model.type == ModelType.VISION) && model.mmprojPath?.let { File(it).isFile } == true,
        host = "127.0.0.1",
        serverPort = port,
        contextSize = contextOverride?.takeIf { it > 0 }
            ?: minOf(8192, knownContext?.takeIf { it > 0 } ?: 8192),
        threads = processors.coerceIn(1, 4),
        parallel = 1,
        batchSize = 512,
        loadMode = LlamaLoadMode.MMAP.value,
        nativeBinarySelection = SettingsRepository.NATIVE_BINARY_CPU_AUTO,
        idleStopSeconds = 600,
        // These read-only endpoints let the owner distinguish other clients' work from idle time.
        customFlags = "--slots --metrics"
    )

internal fun easyChatDisabledTools(): NativeChatToolConfig = NativeChatToolConfig(
    toolsEnabled = false, dateTimeEnabled = false, calculatorEnabled = false)

/**
 * Easy chats allow only an optional web search; every other tool (date/time, calculator, files,
 * delegation, image tool, ...) stays off. Web search is off by default.
 */
internal fun easyChatTools(webSearch: Boolean = false): NativeChatToolConfig =
    easyChatDisabledTools().copy(toolsEnabled = webSearch, webSearchEnabled = webSearch)

/** Easy chat API params: the server's seeded params (audio etc.) with the tool keys forced to [easyChatTools]. */
internal fun easyChatApiParams(serverParams: String?, webSearch: Boolean = false): String {
    val json = runCatching { serverParams?.let(::JSONObject) }.getOrNull() ?: JSONObject()
    easyChatTools(webSearch).toParamMap().forEach { (key, value) -> json.put(key, value) }
    return json.toString()
}

internal fun easyChatAllowedTools(): NativeChatToolConfig = NativeChatToolConfig.liteRtToolDefaults()
    .copy(fileToolsEnabled = true, customToolsEnabled = true)

/** One persisted profile/card/connection per installed model; all shortcuts share this owner. */
class EasyLlamaChatCoordinator(
    context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val context = context.applicationContext
    private val managed = ManagedLlamaServerCoordinator(this.context, database)
    private val local = LocalLlamaServerRepository(this.context, database)
    private val settings = EasyModeSettings(this.context)
    private val sizer = EasyContextSizer(this.context)

    /** Per-model override if set, else the RAM-based recommendation. Blocking (reads the GGUF header). */
    private fun desiredContext(modelPath: String, mmprojPath: String?): Int {
        settings.contextOverrideFor(modelPath).takeIf { it > 0 }?.let { return it }
        val mmprojBytes = mmprojPath?.let { File(it) }?.takeIf { it.isFile }?.length() ?: 0L
        return sizer.recommend(modelPath, mmprojBytes).contextSize
    }

    /** The stored launch profile of an easy card (model and projector paths), or null when missing. */
    suspend fun profileForCard(cardId: Long): LlamaServerLaunchProfile? = withContext(Dispatchers.IO) {
        val card = database.llamaServerCardDao().getCard(cardId) ?: return@withContext null
        database.savedCommandDao().getGeneralCommandById(card.savedCommandId)?.launchProfileForCard(card)
    }

    /** Aligns the stored profile of an easy card with the desired context (applies on the next launch). */
    private suspend fun syncStoredContext(card: LlamaServerCardEntity) {
        val profile = database.savedCommandDao().getGeneralCommandById(card.savedCommandId)
            ?.launchProfileForCard(card) ?: return
        val desired = desiredContext(profile.modelPath, profile.mmprojPath)
        if (profile.contextSize != desired) {
            local.updateLocalServerProfile(card.id, profile.copy(contextSize = desired))
        }
    }

    /**
     * Saves [contextSize] (0 = automatic) as the model's override, rewrites the stored profile and
     * restarts the model's managed server so the new context takes effect. Returns the session
     * ready for use; callers show progress through [onStatus].
     */
    suspend fun applyContext(cardId: Long, contextSize: Int, onStatus: (String) -> Unit = {}): ManagedLlamaServerInfo =
        withContext(Dispatchers.IO) {
            val card = requireNotNull(database.llamaServerCardDao().getCard(cardId))
            val profile = requireNotNull(database.savedCommandDao().getGeneralCommandById(card.savedCommandId)
                ?.launchProfileForCard(card))
            settings.setContextOverrideFor(profile.modelPath, contextSize)
            syncStoredContext(card)
            LlamaServerLauncher.stopSession(context, card.sessionId)
            // The stop is asynchronous; wait (bounded) until the port is free before relaunching.
            var waits = 0
            while (waits++ < 30 && !checkLlamaServerPort(profile.host, card.port).available) delay(500)
            managed.prepare(cardId, onStatus)
        }

    /**
     * Stops the Easy session of [modelFilename] unless one of its bound chats is generating or queued.
     * Only `easy:` cards are touched. Returns true when a stop was issued.
     */
    suspend fun stopEasyModelIfIdle(modelFilename: String): Boolean = withContext(Dispatchers.IO) {
        val card = database.llamaServerCardDao().getEasyCard(easyModeCardModelId(modelFilename))
            ?: return@withContext false
        val row = database.llamaServerDao().getManagedServer(card.id)
        val busy = com.example.llamadroid.service.NativeChatGenerationStates.busyChatIds()
        if (row != null && database.llamaChatDao().getChatsBoundToServer(row.id).any { it.id in busy }) {
            return@withContext false
        }
        LlamaServerLauncher.stopSession(context, card.sessionId).isSuccess
    }

    suspend fun createOrReuse(
        modelId: String,
        name: String? = null,
        customizeProfile: ((LlamaServerLaunchProfile) -> LlamaServerLaunchProfile)? = null
    ): EasyLlamaChatTarget = createOrReuseKeyed(modelId, modelId, name, customizeProfile, easy = false)

    /** Easy-mode card for [modelFilename]: keyed `easy:<filename>`, projector and audio flag linked. */
    suspend fun createOrReuseEasy(modelFilename: String, name: String? = null): EasyLlamaChatTarget =
        createOrReuseKeyed(modelFilename, easyModeCardModelId(modelFilename), name, null, easy = true)

    private suspend fun createOrReuseKeyed(
        modelId: String,
        cardKey: String,
        name: String?,
        customizeProfile: ((LlamaServerLaunchProfile) -> LlamaServerLaunchProfile)?,
        easy: Boolean
    ): EasyLlamaChatTarget = withContext(Dispatchers.IO) {
        ManagedLlamaServerCoordinator.creationLock.withLock {
            val model = database.modelDao().getModelByFilename(modelId)
                ?.takeIf(::isEasyLlamaChatModel) ?: throw ManagedLlamaServerException(
                "MANAGED_MODEL_MISSING", context.getString(R.string.managed_llama_model_missing))
            val projector = if (easy) resolveProjectorPath(model) else null
            val existing = database.llamaServerCardDao().getEasyCard(cardKey)
            if (existing != null) {
                if (easy) linkExistingProjector(existing, projector)
                return@withLock database.withTransaction { target(existing) }.let { if (easy) withAudioFlag(it) else it }
            }
            val metadata = GGUFParser.readModelInfo(model.path)
            val knownContext = metadata?.contextLength?.takeIf { metadata.contextLengthDetected }
            val linkedModel = if (projector != null && model.mmprojPath?.let { File(it).isFile } != true) {
                model.copy(mmprojPath = projector, isVision = true)
            } else model
            val mmproj = linkedModel.mmprojPath?.takeIf { File(it).isFile }
            val profile = easyLlamaProfile(linkedModel, managed.allocatePort(), knownContext,
                Runtime.getRuntime().availableProcessors(), desiredContext(model.path, mmproj)).let { customizeProfile?.invoke(it) ?: it }
            val displayName = name?.trim()?.takeIf(String::isNotBlank) ?: model.filename.removeSuffix(".gguf")
            // Port is already allocated under creationLock (non-reentrant), so pass it explicitly.
            val ids = local.createLocalServer(displayName, profile, port = profile.serverPort, easyModelId = cardKey)
            val card = requireNotNull(database.llamaServerCardDao().getCard(ids.cardId))
            val row = requireNotNull(database.llamaServerDao().getServerById(ids.serverRowId))
            EasyLlamaChatTarget(card, row).let { if (easy) withAudioFlag(it) else it }
        }
    }

    /** Projector path for [model]: its own mmproj, else the curated/prefix match among installed projectors. */
    private suspend fun resolveProjectorPath(model: ModelEntity): String? {
        model.mmprojPath?.takeIf { File(it).isFile }?.let { return it }
        val installed = database.modelDao().getModelsByTypesSync(listOf(ModelType.VISION_PROJECTOR))
        val filename = easyProjectorFilenameFor(model.filename, installed) ?: return null
        return installed.firstOrNull { it.filename == filename }?.path?.takeIf { File(it).isFile }
    }

    /** Older Easy cards created without a projector pick it up on the next launch. */
    private suspend fun linkExistingProjector(card: LlamaServerCardEntity, projector: String?) {
        if (projector == null) return
        val profile = database.savedCommandDao().getGeneralCommandById(card.savedCommandId)
            ?.launchProfileForCard(card) ?: return
        if (profile.mmprojPath?.let { File(it).isFile } == true) return
        local.updateLocalServerProfile(card.id, profile.copy(mmprojPath = projector, visionEnabled = true))
    }

    /** Sets the server row's `supportsAudio` from the projector's `clip.has_audio_encoder` flag. */
    private suspend fun withAudioFlag(target: EasyLlamaChatTarget): EasyLlamaChatTarget {
        val card = database.llamaServerCardDao().getCard(target.card.id) ?: target.card
        val row = database.llamaServerDao().getManagedServer(card.id) ?: target.server
        val mmproj = database.savedCommandDao().getGeneralCommandById(card.savedCommandId)
            ?.launchProfileForCard(card)?.mmprojPath
        val audio = mmproj != null && EasyGgufFlags.hasAudioEncoder(File(mmproj))
        if (row.supportsAudio == audio) return EasyLlamaChatTarget(card, row)
        database.llamaServerDao().updateModelMetadata(row.id, row.modelName, row.supportsVision, audio, row.supportsVideo)
        return EasyLlamaChatTarget(card, row.copy(supportsAudio = audio))
    }

    /**
     * Starts (or reuses) the Easy server of [modelFilename] and returns the row to bind a chat to.
     * Used by the in-chat model switch; the caller binds the chat and clears its KV cache.
     */
    suspend fun prepareEasyModel(modelFilename: String, onStatus: (String) -> Unit = {}): EasyModelSwitch =
        withContext(Dispatchers.IO) {
            val card = createOrReuseEasy(modelFilename).card
            val usage = LlamaServerUsageLease(context, LlamaServerCardEntity.sessionIdForCard(card.id), this)
            try {
                runCatching { syncStoredContext(card) }
                val ready = managed.prepare(card.id, onStatus)
                val linked = target(ready.card, ready.profile)
                EasyModelSwitch(linked.server.id, requireNotNull(ready.contextTokens))
            } finally { usage.close() }
        }

    private suspend fun target(card: LlamaServerCardEntity, launched: LlamaServerLaunchProfile? = null): EasyLlamaChatTarget =
        EasyLlamaChatTarget(card, requireNotNull(local.ensureLinkedRow(card.id, launched)))

    suspend fun openChat(cardId: Long, onStatus: (String) -> Unit = {}): EasyLlamaChatSession =
        openChatInternal(cardId, easy = false, onStatus)

    /** Like [openChat], but the new chat is an Easy chat (`easyMode = true`). */
    suspend fun openEasyChat(cardId: Long, onStatus: (String) -> Unit = {}): EasyLlamaChatSession =
        openChatInternal(cardId, easy = true, onStatus)

    private suspend fun openChatInternal(
        cardId: Long,
        easy: Boolean,
        onStatus: (String) -> Unit
    ): EasyLlamaChatSession = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<EasyLlamaChatSession>()
        val existing = openings.putIfAbsent(cardId, result)
        if (existing != null) return@withContext existing.await()
        try {
            val session = withTimeoutOrNull(210_000L) {
                val usage = LlamaServerUsageLease(context, LlamaServerCardEntity.sessionIdForCard(cardId), this)
                try {
                    database.llamaServerCardDao().getCard(cardId)?.let { runCatching { syncStoredContext(it) } }
                    val ready = managed.prepare(cardId, onStatus)
                    onStatus(context.getString(R.string.easy_chat_opening_conversation))
                    database.withTransaction {
                        val linked = target(ready.card, ready.profile)
                        val chatId = database.llamaChatDao().insertChat(LlamaChatEntity(
                            title = ready.card.name,
                            contextSize = requireNotNull(ready.contextTokens),
                            serverId = linked.server.id,
                            persistKvCache = easy,
                            easyMode = easy,
                            apiParams = newChatApiParamsForServer(linked.server).let { if (easy) easyChatApiParams(it) else it }))
                        database.llamaServerDao().updateLastUsed(linked.server.id)
                        EasyLlamaChatSession(chatId, linked.server.id)
                    }
                } finally { usage.close() }
            } ?: throw ManagedLlamaServerException("MANAGED_START_TIMEOUT",
                context.getString(R.string.managed_llama_timeout))
            result.complete(session)
            session
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            throw error
        } finally { openings.remove(cardId, result) }
    }

    companion object {
        private val openings = ConcurrentHashMap<Long, CompletableDeferred<EasyLlamaChatSession>>()
    }
}
