package com.example.llamadroid.service

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.example.llamadroid.R
import com.example.llamadroid.data.HttpEndpointUrlSupport
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.repository.launchProfileForCard
import com.example.llamadroid.util.NativeProcessCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class ManagedLlamaServerInfo(
    val card: LlamaServerCardEntity,
    val profile: LlamaServerLaunchProfile?,
    val status: LlamaServerSessionStatus,
    val contextTokens: Int?,
    val errorCode: String? = null
) {
    val baseUrl: String? get() = profile?.let { HttpEndpointUrlSupport.fromHostPort(managedLlamaConnectHost(it.host), it.serverPort) }
    fun modelRow(): JSONObject = JSONObject().put("id", "llama:${card.id}")
        .put("object", "model").put("owned_by", "adt-llama-server").put("name", card.name)
        .put("status", status.name.lowercase()).put("available", errorCode == null)
        .put("capabilitySource", "server").put("contextInherited", true)
        .put("input_modalities", org.json.JSONArray().put("text"))
        .apply {
            contextTokens?.takeIf { it > 0 }?.let { put("context_length", it); put("contextWindow", it) }
            errorCode?.let { put("errorCode", it) }
        }
}

/** Stable error codes cross the bridge; native UI receives localized recovery guidance. */
class ManagedLlamaServerException(val code: String, message: String) : IllegalStateException(message)

/** Shared card/preset authority for native chat, Harness, and management shortcuts. */
class ManagedLlamaServerCoordinator(
    context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val context = context.applicationContext
    private val states = LlamaServerSessionStateStore(this.context)
    private val owners = LlamaServerSessionOwnerStore(this.context)
    private val contexts = ManagedLlamaContextStore(this.context)

    suspend fun catalog(): List<ManagedLlamaServerInfo> = withContext(Dispatchers.IO) {
        val cards = database.llamaServerCardDao().observeCards().first()
        val presets = database.savedCommandDao().getCommandsByScope("GENERAL").first().associateBy { it.id }
        val snapshots = states.readAll().associateBy { it.sessionId }
        val runningOwners = owners.readAll().associateBy { it.sessionId }
        cards.map { card ->
            val snapshot = snapshots[card.sessionId]
            val owner = runningOwners[card.sessionId]
            val running = snapshot?.status == LlamaServerSessionStatus.RUNNING && owner != null &&
                NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(owner.pid, owner.processStartTimeTicks, owner.port)
            val launched = if (running) LlamaServerLaunchProfile.decode(owner?.launchProfileJson) else null
            val profile = launched ?: presets[card.savedCommandId]?.launchProfileForCard(card)
            val error = when {
                presets[card.savedCommandId] == null || profile == null -> "MANAGED_PRESET_MISSING"
                !hasManagedModelFiles(profile) -> "MANAGED_MODEL_MISSING"
                else -> null
            }
            val capacity = if (running && owner != null && profile != null) {
                contexts.read(owner) ?: HttpEndpointUrlSupport.fromHostPort(managedLlamaConnectHost(profile.host), profile.serverPort)?.let { url ->
                    ManagedLlamaServerHttp.contextTokens(url, configuredManagedContext(profile))?.also { contexts.write(owner, it) }
                }
            } else configuredManagedContext(profile)
            val status = if (snapshot?.status == LlamaServerSessionStatus.RUNNING && !running)
                LlamaServerSessionStatus.STOPPED else snapshot?.status ?: LlamaServerSessionStatus.STOPPED
            ManagedLlamaServerInfo(card, profile, status, capacity, error)
        }
    }

    suspend fun prepare(cardId: Long, onStatus: (String) -> Unit = {}): ManagedLlamaServerInfo =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(READY_TIMEOUT_MS + 10_000L) {
                val usage = LlamaServerUsageLease(context, "card:$cardId", this)
                try {
                    startLocks.getOrPut(cardId) { Mutex() }.withLock {
                        var card = database.llamaServerCardDao().getCard(cardId) ?: fail("MANAGED_CARD_MISSING")
                        val preset = database.savedCommandDao().getGeneralCommandById(card.savedCommandId)
                            ?: fail("MANAGED_PRESET_MISSING")
                        var profile = preset.launchProfileForCard(card)
                        val snapshot = states.readAll().firstOrNull { it.sessionId == card.sessionId }
                        val owner = owners.get(card.sessionId)
                        val ownerAlive = owner != null && NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(
                            owner.pid, owner.processStartTimeTicks, owner.port)
                        if (ownerAlive) {
                            profile = LlamaServerLaunchProfile.decode(owner?.launchProfileJson) ?: profile
                        }
                        if (!hasManagedModelFiles(profile)) fail("MANAGED_MODEL_MISSING")
                        val baseUrl = HttpEndpointUrlSupport.fromHostPort(managedLlamaConnectHost(profile.host), profile.serverPort)
                            ?: fail("MANAGED_ENDPOINT_INVALID")
                        if (snapshot?.status == LlamaServerSessionStatus.RUNNING && ownerAlive && ManagedLlamaServerHttp.healthy(baseUrl)) {
                            // Explicit use revives the owning watchdog if Android recreated its service.
                            ensureOwner(card, profile)
                            onStatus(context.getString(R.string.managed_llama_verifying))
                            return@withLock ready(card, profile, baseUrl)
                        }
                        val joining = snapshot?.isRunning == true || (snapshot?.isBusy == true && System.currentTimeMillis() - snapshot.updatedAt < READY_TIMEOUT_MS) || ownerAlive
                        if (!joining) {
                            if (card.easyModelId != null && !checkLlamaServerPort(profile.host, card.port).available) {
                                creationLock.withLock {
                                    val port = allocatePort(card.id)
                                    database.withTransaction {
                                        database.llamaServerCardDao().updatePort(card.id, port)
                                        database.llamaServerDao().updateManagedEndpoint(card.id, "127.0.0.1", port)
                                    }
                                    card = card.copy(port = port)
                                    profile = profile.copy(host = "127.0.0.1", serverPort = port)
                                }
                            } else if (!checkLlamaServerPort(profile.host, profile.serverPort).available) {
                                fail("MANAGED_PORT_BUSY")
                            }
                        }
                        onStatus(context.getString(R.string.managed_llama_starting))
                        val startedAt = System.currentTimeMillis()
                        val failureAfter = if (joining) snapshot?.updatedAt ?: startedAt else startedAt
                        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
                        var runningWithoutOwnerSince: Long? = null
                        var verifying = false
                        ensureOwner(card, profile)
                        while (SystemClock.elapsedRealtime() < deadline) {
                            val current = states.readAll().firstOrNull { it.sessionId == card.sessionId }
                            val liveOwner = owners.get(card.sessionId)
                            if (current?.status == LlamaServerSessionStatus.RUNNING) {
                                if (!verifying) {
                                    verifying = true
                                    onStatus(context.getString(R.string.managed_llama_verifying))
                                }
                                if (liveOwner == null) {
                                    val now = SystemClock.elapsedRealtime()
                                    val missingSince = runningWithoutOwnerSince ?: now.also { runningWithoutOwnerSince = it }
                                    if (now - missingSince >= 5_000) fail("MANAGED_OWNER_UNAVAILABLE")
                                } else runningWithoutOwnerSince = null
                            }
                            if (current?.status == LlamaServerSessionStatus.RUNNING && liveOwner != null && NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(
                                    liveOwner.pid, liveOwner.processStartTimeTicks, liveOwner.port)) {
                                val launched = (LlamaServerLaunchProfile.decode(liveOwner.launchProfileJson) ?: profile)
                                    .copy(serverPort = liveOwner.port)
                                val launchedUrl = HttpEndpointUrlSupport.fromHostPort(managedLlamaConnectHost(launched.host), liveOwner.port)
                                if (launchedUrl != null && ManagedLlamaServerHttp.healthy(launchedUrl))
                                    return@withLock ready(card, launched, launchedUrl)
                            }
                            if (current?.status == LlamaServerSessionStatus.ERROR && current.updatedAt >= failureAfter) {
                                fail("MANAGED_START_FAILED")
                            }
                            delay(500)
                        }
                        fail("MANAGED_START_TIMEOUT")
                    }
                } finally { usage.close() }
            } ?: fail("MANAGED_START_TIMEOUT")
        }

    private fun ensureOwner(card: LlamaServerCardEntity, profile: LlamaServerLaunchProfile) {
        LlamaServerLauncher.startSession(context, card.sessionId, profile, profile.serverPort,
            ensureRunning = true).getOrElse { fail("MANAGED_START_FAILED") }
    }

    private suspend fun ready(card: LlamaServerCardEntity, profile: LlamaServerLaunchProfile, baseUrl: String): ManagedLlamaServerInfo {
        val contextTokens = ManagedLlamaServerHttp.contextTokens(baseUrl, configuredManagedContext(profile))
            ?: fail("MANAGED_CONTEXT_UNKNOWN")
        owners.get(card.sessionId)?.let { contexts.write(it, contextTokens) }
        database.withTransaction {
            database.llamaServerDao().updateManagedEndpoint(card.id, managedLlamaConnectHost(profile.host), profile.serverPort)
            database.llamaServerDao().getManagedServer(card.id)?.let { server ->
                database.llamaServerDao().updateModelMetadata(server.id, profile.modelPath.substringAfterLast('/'),
                    profile.visionEnabled, server.supportsAudio, profile.videoEnabled)
            }
        }
        return ManagedLlamaServerInfo(card, profile, LlamaServerSessionStatus.RUNNING, contextTokens)
    }

    /** Caller holds creationLock; a bind race is still rechecked by the owning process at launch. */
    suspend fun allocatePort(exceptCardId: Long? = null): Int {
        val excluded = database.llamaServerCardDao().observeCards().first()
            .filterNot { it.id == exceptCardId }.map { it.port }.toSet() +
            database.llamaServerDao().getAllServers().first()
                .filter { it.managedServerCardId != exceptCardId || exceptCardId == null }.map { it.port } +
            states.readAll().filter { it.isRunning || it.isBusy }.mapNotNull { it.port }
        return chooseEasyLlamaPort(excluded) { checkLlamaServerPort("127.0.0.1", it).available }
            ?: fail("MANAGED_NO_FREE_PORT")
    }

    private fun fail(code: String): Nothing = throw ManagedLlamaServerException(code, context.getString(when (code) {
        "MANAGED_CARD_MISSING" -> R.string.managed_llama_card_missing
        "MANAGED_PRESET_MISSING" -> R.string.managed_llama_preset_missing
        "MANAGED_MODEL_MISSING" -> R.string.managed_llama_model_missing
        "MANAGED_PORT_BUSY", "MANAGED_NO_FREE_PORT" -> R.string.managed_llama_port_busy
        "MANAGED_START_TIMEOUT" -> R.string.managed_llama_timeout
        "MANAGED_CONTEXT_UNKNOWN" -> R.string.managed_llama_context_unknown
        "MANAGED_OWNER_UNAVAILABLE" -> R.string.managed_llama_owner_unavailable
        else -> R.string.managed_llama_start_failed
    }))

    companion object {
        internal val creationLock = Mutex()
        private val startLocks = ConcurrentHashMap<Long, Mutex>()
        private const val READY_TIMEOUT_MS = 180_000L
    }
}

internal fun configuredManagedContext(profile: LlamaServerLaunchProfile?): Int? =
    profile?.contextSize?.takeIf { it > 0 }?.let { total ->
        (total / (profile.parallel?.coerceAtLeast(1) ?: 1)).takeIf { it > 0 }
    }

internal fun managedLlamaConnectHost(host: String): String = when (host.trim().trim('[', ']')) {
    "0.0.0.0" -> "127.0.0.1"
    "::" -> "::1"
    else -> host
}

private fun hasManagedModelFiles(profile: LlamaServerLaunchProfile): Boolean =
    File(profile.modelPath).let { it.isFile && it.canRead() } &&
        (!profile.visionEnabled || profile.mmprojPath?.let { File(it).let { file -> file.isFile && file.canRead() } } == true)

internal fun chooseEasyLlamaPort(excluded: Set<Int>, available: (Int) -> Boolean): Int? {
    val start = Random.nextInt(49152, 65536)
    return (0 until 16384).asSequence().map { 49152 + (start - 49152 + it) % 16384 }
        .firstOrNull { it !in excluded && available(it) }
}
