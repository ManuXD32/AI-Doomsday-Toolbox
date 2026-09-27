package com.example.llamadroid.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.savedCommandFromLaunchProfile
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.LlamaLoadMode
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaServerUsageLease
import com.example.llamadroid.service.ManagedLlamaServerCoordinator
import com.example.llamadroid.service.ManagedLlamaServerException
import com.example.llamadroid.service.managedLlamaConnectHost
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.util.GGUFParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class EasyLlamaChatTarget(val card: LlamaServerCardEntity, val server: LlamaServerEntity)
data class EasyLlamaChatSession(val chatId: Long, val serverId: Long)

fun isEasyLlamaChatModel(model: ModelEntity): Boolean =
    model.type in setOf(ModelType.LLM, ModelType.VISION) && model.isDownloaded &&
        model.path.endsWith(".gguf", ignoreCase = true) && File(model.path).let { it.isFile && it.canRead() }

internal fun easyLlamaProfile(model: ModelEntity, port: Int, knownContext: Int?, processors: Int): LlamaServerLaunchProfile =
    LlamaServerLaunchProfile(
        modelPath = model.path,
        mmprojPath = model.mmprojPath?.takeIf { File(it).isFile },
        visionEnabled = (model.isVision || model.type == ModelType.VISION) && model.mmprojPath?.let { File(it).isFile } == true,
        host = "127.0.0.1",
        serverPort = port,
        contextSize = minOf(8192, knownContext?.takeIf { it > 0 } ?: 8192),
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

internal fun easyChatAllowedTools(): NativeChatToolConfig = NativeChatToolConfig.liteRtToolDefaults()
    .copy(fileToolsEnabled = true, customToolsEnabled = true)

/** One persisted profile/card/connection per installed model; all shortcuts share this owner. */
class EasyLlamaChatCoordinator(
    context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val context = context.applicationContext
    private val managed = ManagedLlamaServerCoordinator(this.context, database)

    suspend fun createOrReuse(modelId: String, name: String? = null): EasyLlamaChatTarget = withContext(Dispatchers.IO) {
        ManagedLlamaServerCoordinator.creationLock.withLock {
            val model = database.modelDao().getModelByFilename(modelId)
                ?.takeIf(::isEasyLlamaChatModel) ?: throw ManagedLlamaServerException(
                "MANAGED_MODEL_MISSING", context.getString(R.string.managed_llama_model_missing))
            val existing = database.llamaServerCardDao().getEasyCard(modelId)
            if (existing != null) return@withLock database.withTransaction { target(existing) }
            val metadata = GGUFParser.readModelInfo(model.path)
            val knownContext = metadata?.contextLength?.takeIf { metadata.contextLengthDetected }
            val profile = easyLlamaProfile(model, managed.allocatePort(), knownContext,
                Runtime.getRuntime().availableProcessors())
            database.withTransaction {
                val displayName = name?.trim()?.takeIf(String::isNotBlank) ?: model.filename.removeSuffix(".gguf")
                val commandId = database.savedCommandDao().insertCommand(savedCommandFromLaunchProfile(displayName, profile))
                val draft = LlamaServerCardEntity(name = displayName, savedCommandId = commandId,
                    presetNameSnapshot = displayName, port = profile.serverPort, easyModelId = model.filename)
                val id = database.llamaServerCardDao().insertCard(draft)
                target(draft.copy(id = id))
            }
        }
    }

    private suspend fun target(card: LlamaServerCardEntity, launched: LlamaServerLaunchProfile? = null): EasyLlamaChatTarget {
        val existing = database.llamaServerDao().getManagedServer(card.id)
        if (existing != null) return EasyLlamaChatTarget(card, existing)
        val profile = launched ?: database.savedCommandDao().getGeneralCommandById(card.savedCommandId)?.launchProfileForCard(card)
            ?: throw ManagedLlamaServerException("MANAGED_PRESET_MISSING", context.getString(R.string.managed_llama_preset_missing))
        val server = LlamaServerEntity(name = card.name, host = managedLlamaConnectHost(profile.host), port = profile.serverPort,
            modelName = profile.modelPath.substringAfterLast('/'),
            supportsVision = profile.visionEnabled,
            supportsVideo = profile.videoEnabled,
            managedServerCardId = card.id,
            defaultApiParams = JSONObject(easyChatAllowedTools().toParamMap()).toString())
        return EasyLlamaChatTarget(card, server.copy(id = database.llamaServerDao().insertServer(server)))
    }

    suspend fun openChat(cardId: Long, onStatus: (String) -> Unit = {}): EasyLlamaChatSession = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<EasyLlamaChatSession>()
        val existing = openings.putIfAbsent(cardId, result)
        if (existing != null) return@withContext existing.await()
        try {
            val session = withTimeoutOrNull(210_000L) {
                val usage = LlamaServerUsageLease(context, LlamaServerCardEntity.sessionIdForCard(cardId), this)
                try {
                    val ready = managed.prepare(cardId, onStatus)
                    onStatus(context.getString(R.string.easy_chat_opening_conversation))
                    database.withTransaction {
                        val linked = target(ready.card, ready.profile)
                        val chatId = database.llamaChatDao().insertChat(LlamaChatEntity(
                            title = ready.card.name,
                            contextSize = requireNotNull(ready.contextTokens),
                            apiParams = JSONObject(easyChatDisabledTools().toParamMap()).toString()))
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
