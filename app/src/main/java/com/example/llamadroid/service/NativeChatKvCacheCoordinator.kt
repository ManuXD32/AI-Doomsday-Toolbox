package com.example.llamadroid.service

import android.content.Context
import androidx.annotation.StringRes
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.LlamaServerRegistry
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus
import com.example.llamadroid.harness.HarnessKvCacheSettings
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Why the per-chat KV cache can or cannot be used. The UI enables the switch for [ELIGIBLE] and
 * [DISABLED] (capable, currently off) and shows [reasonRes] for the remaining values.
 */
enum class KvCacheEligibility(@StringRes val reasonRes: Int?) {
    ELIGIBLE(null),
    DISABLED(null),
    REMOTE_SERVER(R.string.native_chat_kv_cache_reason_remote),
    UNSUPPORTED_ENGINE(R.string.native_chat_kv_cache_reason_engine),
    SERVER_NEEDS_RESTART(R.string.native_chat_kv_cache_reason_restart)
}

/** One slot as reported by `GET /slots`. */
internal data class KvSlotFact(
    val id: Int,
    val nCtx: Int,
    val idTask: Int?,
    val nPromptTokens: Int,
    /** `is_processing` from `/slots`; null when the server does not report it. */
    val isProcessing: Boolean? = null
)

/** True once a slot finished (or never started) its task, so its state can be saved safely. */
internal fun isSlotSettled(slot: KvSlotFact?): Boolean = slot != null && slot.isProcessing != true

/** Server facts read from `/props` and `/slots`. */
internal data class KvServerFacts(
    val modelPath: String,
    val slotNCtx: Int,
    val totalSlots: Int,
    val chatTemplate: String,
    val slots: List<KvSlotFact>,
    /** False when `/slots` could not be read (timeout/error); `slots` is then empty and means UNKNOWN. */
    val slotsKnown: Boolean = true
)

/**
 * Keeps llama-server slots pinned per owner (native chat, delegation subagent, harness session)
 * and persists their KV state across idle stops, sleeps, restarts and slot sharing using
 * llama-server's `POST /slots/{id}?action=save|restore`.
 *
 * Only metadata is ever logged or written to disk by this class; never prompt or message text.
 */
class NativeChatKvCacheCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val store = NativeChatKvCacheStore(appContext)

    /** See [KvCacheEligibility]. Does no I/O. */
    fun eligibility(server: LlamaServerEntity, chat: LlamaChatEntity): KvCacheEligibility =
        kvCacheEligibility(
            isLlamaServerEngine = server.isLlamaServerEngine(),
            isLoopback = isNativeChatLoopbackHost(server.host),
            persistKvCache = chat.persistKvCache,
            slotActionsSupported = slotActionsSupport[normalize(server.baseUrl())]
        )

    /**
     * Runs [block] with the slot pinned to this chat, restoring the saved KV state first when it
     * is valid. Ineligible chats and any setup failure fall back to `block(null)`. The whole
     * [block] runs as one request under the slot lock.
     */
    suspend fun <T> withChatSlot(
        server: LlamaServerEntity,
        chat: LlamaChatEntity,
        onStatus: (String) -> Unit = {},
        block: suspend (slotId: Int?) -> T
    ): T = withChatLease(server, chat, pinOnly = false, onStatus = onStatus) { lease ->
        lease.request { slotId -> block(slotId) }
    }

    /**
     * Lease for a native chat. The chat key persists when the chat opted in and the server is
     * eligible. With [pinOnly] a loopback llama-server chat is still pinned to one slot (for
     * example when delegation is on but the disk cache is off); nothing is then saved.
     */
    suspend fun <T> withChatLease(
        server: LlamaServerEntity,
        chat: LlamaChatEntity,
        pinOnly: Boolean,
        onStatus: (String) -> Unit = {},
        block: suspend (KvSlotLease) -> T
    ): T {
        maybeScheduleCompression()
        val eligible = eligibility(server, chat) == KvCacheEligibility.ELIGIBLE
        val pin = eligible || (pinOnly && server.isLlamaServerEngine() && isNativeChatLoopbackHost(server.host))
        if (!pin) return block(UnpinnedKvSlotLease)
        val spec = KvSlotOwnerSpec(
            key = KvCacheKey.Chat(chat.id),
            persist = eligible,
            conversationId = "native-chat:${chat.id}"
        )
        return withSlot(server.baseUrl(), server, spec, onStatus, block)
    }

    /**
     * Reserves a slot on the physical server at [baseUrl] for [spec] for the length of [block].
     * Every model call inside must go through [KvSlotLease.request], which takes the per-slot lock
     * once and performs the save/restore swap. [server] supplies the launch profile used for the
     * cache fingerprint; null (harness) fingerprints from `/props` only. Remote, non-llama-server
     * or unreachable servers get an [UnpinnedKvSlotLease].
     */
    suspend fun <T> withSlot(
        baseUrl: String,
        server: LlamaServerEntity?,
        spec: KvSlotOwnerSpec,
        onStatus: (String) -> Unit = {},
        block: suspend (KvSlotLease) -> T
    ): T {
        maybeScheduleCompression()
        if (server != null && (!server.isLlamaServerEngine() || !isNativeChatLoopbackHost(server.host))) {
            return block(UnpinnedKvSlotLease)
        }
        val facts = withContext(Dispatchers.IO) { readFacts(baseUrl) } ?: return block(UnpinnedKvSlotLease)
        if (facts.totalSlots <= 0 || facts.slotNCtx <= 0) return block(UnpinnedKvSlotLease)
        val owner = LlamaSlotOwnerKey(
            endpointGeneration = "kv:$baseUrl|${facts.slotNCtx}",
            modelConfiguration = facts.modelPath,
            conversationId = spec.conversationId,
            agentSessionId = "",
            physicalEndpoint = normalize(baseUrl)
        )
        val reservation = LlamaSlotManager.reserve(owner, facts.totalSlots, LlamaSlotAffinityMode.ENABLED)
            ?: return block(UnpinnedKvSlotLease)
        markInUse(spec.key)
        try {
            return block(PinnedLease(reservation, baseUrl, server, spec, onStatus))
        } finally {
            unmarkInUse(spec.key)
            LlamaSlotManager.release(reservation)
        }
    }

    /**
     * Runs a background utility request (page summarizer, helper prompts) on a slot that does not
     * disturb a chat's state. The request body must carry `id_slot = slotId` when [block] gets a
     * non-null id. Preference: a never-held slot; otherwise a slot whose holder has no live turn,
     * whose dirty state is saved first (as the swap does) and which is then marked so the holder's
     * state is restored on its next request (a "utility" holder). Works with a single slot.
     * Remote, non-llama-server, slotless or unreachable servers run `block(null)`. The whole
     * [block] runs under the slot lock, so it never interleaves with a chat request on that slot.
     */
    suspend fun <T> withUtilitySlot(
        baseUrl: String,
        server: LlamaServerEntity?,
        block: suspend (slotId: Int?) -> T
    ): T {
        if (server != null && (!server.isLlamaServerEngine() || !isNativeChatLoopbackHost(server.host))) {
            return block(null)
        }
        val facts = withContext(Dispatchers.IO) { readFacts(baseUrl) } ?: return block(null)
        if (facts.totalSlots <= 0 || facts.slotNCtx <= 0) return block(null)
        val endpoint = normalize(baseUrl)
        val held = holders.entries
            .filter { it.key.startsWith("$endpoint#") }
            .associate { it.key.substringAfterLast('#').toInt() to inUseStems.containsKey(it.value.key.fileStem) }
        val reserved = LlamaSlotManager.snapshotAssignments()
            .filter { it.owner.allocationKey == endpoint }.mapTo(mutableSetOf()) { it.slotId }
        val choice = chooseUtilitySlot(facts.totalSlots, held, reserved) ?: return block(null)
        val counter = activeRequests.computeIfAbsent(endpoint) { AtomicInteger() }
        counter.incrementAndGet()
        try {
            return LlamaSlotManager.withSlotRequest(endpoint, choice.slot) {
                val hk = slotKey(baseUrl, choice.slot)
                val holder = holders[hk]
                if (choice.saveHolder && holder != null && holder.dirty && holder.persists &&
                    holder.key != UTILITY_KEY && withContext(Dispatchers.IO) { ensureSlotActionsSupported(baseUrl, choice.slot) }
                ) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val fresh = readFacts(baseUrl)
                        val fact = fresh?.slots?.firstOrNull { it.id == choice.slot }
                        // Only save a slot that still holds the holder; an unreadable /slots is trusted.
                        if (fresh != null && !isHolderDisturbed(true, fresh.slotsKnown, holder.matches(fact))) {
                            saveSlot(baseUrl, choice.slot, holder.key, holder.server, fresh)
                        }
                    }
                }
                try {
                    block(choice.slot)
                } finally {
                    if (holder != null || choice.saveHolder) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            recordHolder(baseUrl, server, UTILITY_KEY, persists = false, slot = choice.slot, dirty = false)
                        }
                    }
                }
            }
        } finally {
            counter.decrementAndGet()
        }
    }

    /**
     * Builds the base cache [key] on the local llama-server at [server]: [send] runs one tiny
     * prefix-only request on a leased slot and, still under the slot lock, the slot is saved as
     * [key] and verified (file and sidecar). A valid existing base is kept (never overwritten)
     * unless [force] is set. Returns the size or the concrete failure reason.
     */
    suspend fun prepareBase(
        server: LlamaServerEntity,
        key: KvCacheKey.Base,
        force: Boolean = false,
        onStatus: (String) -> Unit = {},
        send: suspend (slotId: Int) -> Unit
    ): KvBasePrepareResult {
        val baseUrl = server.baseUrl()
        val facts = withContext(Dispatchers.IO) { readFacts(baseUrl) }
        val slotSupport = if (facts == null) null else withContext(Dispatchers.IO) { probeSlotSupport(baseUrl) }
        classifyBasePrepareStart(
            isLlamaServerEngine = server.isLlamaServerEngine(),
            isLoopback = isNativeChatLoopbackHost(server.host),
            slotSupport = slotSupport,
            hasSlots = facts != null && facts.totalSlots > 0 && facts.slotNCtx > 0
        )?.let { return KvBasePrepareResult.Failed(it) }
        if (!baseBuilds.add(key.fileStem)) return KvBasePrepareResult.Failed(KvBaseFailure.BUILD_IN_PROGRESS)
        try {
            val serverFacts = facts ?: return KvBasePrepareResult.Failed(KvBaseFailure.SERVER_UNREACHABLE)
            if (!force && withContext(Dispatchers.IO) { fileStatus(server, key, serverFacts) } == KvFileStatus.VALID) {
                return KvBasePrepareResult.Ready(withContext(Dispatchers.IO) { store.sizeBytes(key) }, reused = true)
            }
            if (force) withContext(Dispatchers.IO) { keyMutex(key).withLock { store.delete(key) } }
            return runBaseBuild(baseUrl, server, key, onStatus, send)
        } finally {
            baseBuilds.remove(key.fileStem)
        }
    }

    /** Leases a slot, runs the prefix request and saves it explicitly under the same slot lock. */
    private suspend fun runBaseBuild(
        baseUrl: String,
        server: LlamaServerEntity,
        key: KvCacheKey.Base,
        onStatus: (String) -> Unit,
        send: suspend (slotId: Int) -> Unit
    ): KvBasePrepareResult {
        val spec = KvSlotOwnerSpec(key = key, persist = true, conversationId = "base-prepare:${key.fileStem}")
        var outcome: KvBasePrepareResult = KvBasePrepareResult.Failed(KvBaseFailure.NO_FREE_SLOT)
        withSlot(baseUrl, server, spec, onStatus) { lease ->
            lease.request { slot ->
                if (slot == null) return@request
                outcome = try {
                    send(slot)
                    val after = withContext(Dispatchers.IO) { readFacts(baseUrl) }
                    if (after == null) {
                        KvBasePrepareResult.Failed(KvBaseFailure.SERVER_UNREACHABLE)
                    } else {
                        // The save is queued behind the finished request inside llama-server, so
                        // no holder comparison is needed (that check raced with the finishing slot).
                        when (withContext(Dispatchers.IO) { saveSlot(baseUrl, slot, key, server, after) }) {
                            KvSaveResult.OK -> KvBasePrepareResult.Ready(0L, reused = false)
                            KvSaveResult.LOW_DISK -> KvBasePrepareResult.Failed(KvBaseFailure.LOW_DISK)
                            KvSaveResult.FAILED -> KvBasePrepareResult.Failed(KvBaseFailure.SAVE_FAILED)
                        }
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    DebugLog.log("NativeChatKvCache: base build request failed ${error.javaClass.simpleName}")
                    KvBasePrepareResult.Failed(KvBaseFailure.REQUEST_FAILED)
                }
            }
        }
        val result = outcome
        if (result !is KvBasePrepareResult.Ready) return result
        val bytes = withContext(Dispatchers.IO) { if (store.readMeta(key) != null) store.sizeBytes(key) else 0L }
        if (bytes <= 0L) return KvBasePrepareResult.Failed(KvBaseFailure.SAVE_FAILED)
        KvBaseUsageRegistry(appContext).recordBuilt(key)
        DebugLog.log("NativeChatKvCache: base built bytes=$bytes")
        return KvBasePrepareResult.Ready(bytes, reused = false)
    }

    /**
     * Saves the chat slot after a completed reply. Asynchronous and serialized behind the slot
     * lock; the caller must skip it for cancelled or failed turns. The lock is taken before this
     * returns when it is free, so a following request waits for the save instead of restoring an
     * older file over fresh state.
     */
    fun scheduleSave(server: LlamaServerEntity, chat: LlamaChatEntity, slotId: Int) {
        if (eligibility(server, chat) != KvCacheEligibility.ELIGIBLE) return
        val baseUrl = server.baseUrl()
        val key = KvCacheKey.Chat(chat.id)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { saveIfHolder(baseUrl, slotId, key, requireDirty = false) }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                DebugLog.log("NativeChatKvCache: save failed chat=${chat.id} ${it.javaClass.simpleName}")
            }
        }
    }

    /**
     * Makes sure the local llama-server behind [chat] can save slots to disk. If it was started
     * without `--slot-save-path` it is restarted through the same registry path as the server
     * cards, waited on until ready, and probed again. Returns whether saving is now available.
     */
    suspend fun ensureSlotSaveSupport(
        server: LlamaServerEntity,
        chat: LlamaChatEntity,
        onStatus: (String) -> Unit = {}
    ): Boolean {
        if (!chat.persistKvCache || !server.isLlamaServerEngine() || !isNativeChatLoopbackHost(server.host)) {
            return false
        }
        val firstUrl = server.baseUrl()
        return restartLock(firstUrl).withLock {
            // Null: not running or unreachable. A fresh launch already carries --slot-save-path.
            val supported = withContext(Dispatchers.IO) { probeSlotSupport(firstUrl) } ?: return@withLock false
            if (supported) return@withLock true
            onStatus(appContext.getString(R.string.native_chat_kv_cache_restarting_for_cache))
            val database = AppDatabase.getDatabase(appContext)
            val cardId = server.managedServerCardId
            val unified = UnifiedServer(
                rowId = server.id,
                cardId = cardId,
                kind = if (cardId != null) UnifiedServerKind.LOCAL_MANAGED else UnifiedServerKind.LOCAL_LEGACY,
                name = server.name,
                engine = server.engine,
                modelName = server.modelName,
                host = server.host,
                port = server.port,
                status = UnifiedServerStatus.RUNNING,
                errorCode = null,
                boundChatCount = 0,
                allowWearStart = false,
                isEasy = false
            )
            try {
                LlamaServerRegistry(appContext, database).restart(unified, onStatus)
                onServerRestarted(firstUrl)
                // The canonical start already waits for health; confirm readiness explicitly.
                if (cardId != null) {
                    ManagedLlamaServerCoordinator(appContext, database).prepare(cardId, onStatus)
                } else {
                    NativeChatLocalServerStarter(appContext).ensureReady(server, onStatus = onStatus)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                DebugLog.log("NativeChatKvCache: restart for cache failed ${error.javaClass.simpleName}")
                return@withLock false
            }
            // A managed easy card may have moved to another port while restarting.
            val reloaded = database.llamaServerDao().getServerById(server.id)?.baseUrl() ?: firstUrl
            onServerRestarted(reloaded)
            withContext(Dispatchers.IO) { probeSlotSupport(reloaded) } == true
        }
    }

    /** Forget everything remembered about an endpoint; call when its server (re)starts. */
    fun onServerRestarted(baseUrl: String) = Companion.onServerRestarted(baseUrl)

    // ---- leases ----

    private inner class PinnedLease(
        private val reservation: LlamaSlotReservation,
        private val baseUrl: String,
        private val server: LlamaServerEntity?,
        private val spec: KvSlotOwnerSpec,
        private val onStatus: (String) -> Unit
    ) : KvSlotLease {
        override val slotId: Int? = reservation.slotId

        /** True once a request of this owner ran on the slot, so the slot holds this owner's state. */
        @Volatile
        private var ranRequest = false

        override suspend fun saveAfterStop(): Boolean {
            if (!ranRequest || !spec.persist || spec.key is KvCacheKey.Base) return false
            return withContext(NonCancellable + Dispatchers.IO) {
                saveOwnerAfterStop(baseUrl, server, spec.key, reservation.slotId)
            }
        }

        override suspend fun <T> request(base: KvBaseHint?, block: suspend (slotId: Int?) -> T): T {
            val counter = activeRequests.computeIfAbsent(normalize(baseUrl)) { AtomicInteger() }
            counter.incrementAndGet()
            try {
                return requestUnderLock(base, block)
            } finally {
                counter.decrementAndGet()
            }
        }

        private suspend fun <T> requestUnderLock(base: KvBaseHint?, block: suspend (slotId: Int?) -> T): T =
            LlamaSlotManager.withSlotRequest(reservation) {
                val slot = reservation.slotId
                cancelDeferredSave(spec.key)
                val outcome = swapIn(baseUrl, server, spec, slot, onStatus)
                ranRequest = true
                val persists = outcome.persists
                val baseAction = if (base != null && spec.persist && persists) {
                    restoreBaseIfNeeded(baseUrl, server, spec, slot, base, outcome.ownStatePresent, onStatus)
                } else {
                    KvBaseAction.NONE
                }
                if (base != null) markInUse(base.key)
                var completed = false
                try {
                    val result = block(slot)
                    completed = true
                    result
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        // A base owner's slot is saved explicitly; it must not look dirty afterwards.
                        recordHolder(baseUrl, server, spec.key, persists, slot, dirty = spec.key !is KvCacheKey.Base)
                    }
                    if (completed && persists && spec.key !is KvCacheKey.Chat) {
                        scheduleDeferredSave(baseUrl, slot, spec.key)
                    }
                    if (base != null) {
                        when {
                            completed && baseAction == KvBaseAction.LEGACY_CAPTURE ->
                                scheduleBaseCapture(baseUrl, server, slot, spec.key, base.key)
                            completed && baseAction == KvBaseAction.BUILD && server != null -> {
                                unmarkInUse(base.key)
                                scheduleBaseBuild(baseUrl, server, base)
                            }
                            else -> unmarkInUse(base.key)
                        }
                    }
                }
            }
    }

    // ---- swap protocol ----

    /** What [swapIn] found: whether the owner persists, and whether the slot now holds its state. */
    private data class SwapOutcome(val persists: Boolean, val ownStatePresent: Boolean)

    /** Runs under the slot lock. */
    private suspend fun swapIn(
        baseUrl: String,
        server: LlamaServerEntity?,
        spec: KvSlotOwnerSpec,
        slot: Int,
        onStatus: (String) -> Unit
    ): SwapOutcome = withContext(Dispatchers.IO) {
        val hk = slotKey(baseUrl, slot)
        val persists = spec.persist && ensureSlotActionsSupported(baseUrl, slot)
        val holder = holders[hk]
        val holderNeedsSave = holder != null && holder.key != spec.key && holder.dirty && holder.persists
        if (!persists && !holderNeedsSave) return@withContext SwapOutcome(persists, holder?.key == spec.key)
        val facts = readFacts(baseUrl) ?: return@withContext SwapOutcome(persists, holder?.key == spec.key)
        val slotFact = facts.slots.firstOrNull { it.id == slot }
        val disturbed = isHolderDisturbed(holder != null, facts.slotsKnown, holder?.matches(slotFact) == true)
        val holderIsMe = holder?.key == spec.key && !disturbed
        val myFileValid = persists && !holderIsMe && ownFileValid(server, spec.key, facts)
        val decision = decideSwap(
            holder = holder?.key,
            holderDirty = holder?.dirty == true,
            holderPersists = holder?.persists == true,
            me = spec.key,
            myFileValid = myFileValid,
            slotDisturbed = disturbed
        )
        if (decision.saveHolder && holder != null) {
            saveSlot(baseUrl, slot, holder.key, holder.server, facts)
        }
        if (decision.restore) restoreSlot(baseUrl, server, spec.key, slot, onStatus)
        val present = holderIsMe || (decision.restore && holders[hk]?.key == spec.key)
        SwapOutcome(persists, present)
    }

    /**
     * Runs under the slot lock before the request of an owner with a [base] hint. Restores the
     * valid base when the owner has no state of its own in the slot and records the decision
     * (restored / miss reason) as metadata. Returns what to do after the request: rebuild a
     * missing base from the prefix only, or (hints without a builder) legacy capture.
     */
    private suspend fun restoreBaseIfNeeded(
        baseUrl: String,
        server: LlamaServerEntity?,
        spec: KvSlotOwnerSpec,
        slot: Int,
        base: KvBaseHint,
        ownStatePresent: Boolean,
        onStatus: (String) -> Unit
    ): KvBaseAction = withContext(Dispatchers.IO) {
        if (ownStatePresent) return@withContext KvBaseAction.NONE
        val facts = readFacts(baseUrl) ?: return@withContext KvBaseAction.NONE
        val registry = KvBaseUsageRegistry(appContext)
        val previousStem = registry.noteRequest(base.key)
        val status = fileStatus(server, base.key, facts)
        val baseValid = status == KvFileStatus.VALID
        if (shouldRestoreBase(hasHint = true, ownStatePresent = false, baseFileValid = baseValid)) {
            markInUse(base.key)
            try {
                restoreSlot(baseUrl, server, base.key, slot, onStatus)
            } finally {
                unmarkInUse(base.key)
            }
            if (holders[slotKey(baseUrl, slot)]?.key == base.key) {
                registry.recordUse(base.key)
                DebugLog.log("NativeChatKvCache: base restored for ${spec.key.namespace}")
            } else {
                registry.recordMiss(base.key, KvBaseMissReason.RESTORE_FAILED)
                DebugLog.log("NativeChatKvCache: base miss reason=restore_failed")
            }
        } else {
            missReasonFor(status, previousStem != null && previousStem != base.key.fileStem)?.let { reason ->
                registry.recordMiss(base.key, reason)
                DebugLog.log("NativeChatKvCache: base miss reason=${reason.name.lowercase()}")
            }
        }
        if (base.builder != null) {
            val lastAttempt = baseBuildAttempts[base.key.fileStem]
            val build = shouldBuildBase(
                capture = base.capture, hasBuilder = true, fileStatus = status,
                buildInFlight = baseBuilds.contains(base.key.fileStem),
                msSinceLastAttempt = lastAttempt?.let { System.currentTimeMillis() - it }
            )
            if (build) KvBaseAction.BUILD else KvBaseAction.NONE
        } else if (shouldCaptureBase(base.capture, baseValid, ownStatePresent = false)) {
            KvBaseAction.LEGACY_CAPTURE
        } else {
            KvBaseAction.NONE
        }
    }

    /**
     * Rebuilds a missing/invalid chat base from the stable prefix only (never from a chat's own
     * state). Runs in the background, waits briefly and gives up when the server is busy, so it
     * never competes with a generation.
     */
    private fun scheduleBaseBuild(baseUrl: String, server: LlamaServerEntity, base: KvBaseHint) {
        val builder = base.builder ?: return
        val stem = base.key.fileStem
        if (baseBuilds.contains(stem)) return
        baseBuildAttempts[stem] = System.currentTimeMillis()
        scope.launch {
            delay(BASE_BUILD_DELAY_MS)
            if ((activeRequests[normalize(baseUrl)]?.get() ?: 0) > 0) {
                DebugLog.log("NativeChatKvCache: base build skipped reason=server_busy")
                baseBuildAttempts.remove(stem)
                return@launch
            }
            val result = runCatching { prepareBase(server, base.key, force = false, send = builder) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
            DebugLog.log(
                "NativeChatKvCache: background base build " +
                    ((result as? KvBasePrepareResult.Failed)?.reason?.name?.lowercase() ?: "ok")
            )
        }
    }

    /**
     * Legacy capture for hints without a builder (DeepSeek harness): saves the slot as the base
     * after the owner's first request. Runs asynchronously behind the slot lock and is skipped
     * when the slot has advanced since the request.
     */
    private fun scheduleBaseCapture(
        baseUrl: String,
        server: LlamaServerEntity?,
        slot: Int,
        owner: KvCacheKey,
        baseKey: KvCacheKey.Base
    ) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                LlamaSlotManager.withSlotRequest(normalize(baseUrl), slot) {
                    withContext(Dispatchers.IO) {
                        val holder = holders[slotKey(baseUrl, slot)] ?: return@withContext
                        if (holder.key != owner) return@withContext
                        val facts = readFacts(baseUrl) ?: return@withContext
                        if (!holder.matches(facts.slots.firstOrNull { it.id == slot })) return@withContext
                        if (ownFileValid(server, baseKey, facts)) return@withContext
                        saveSlot(baseUrl, slot, baseKey, server, facts)
                        Unit
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                DebugLog.log("NativeChatKvCache: base capture failed ${error.javaClass.simpleName}")
            } finally {
                unmarkInUse(baseKey)
            }
        }
    }

    /**
     * After a stopped turn: waits (up to [STOP_IDLE_WAIT_MS], polling `/slots`) until the slot is
     * idle, saves the owner's state, then records it as the slot holder from fresh `/slots` facts
     * and marks it clean, so the "disturbed" check trusts it for Continue and the next turn.
     */
    private suspend fun saveOwnerAfterStop(
        baseUrl: String,
        server: LlamaServerEntity?,
        key: KvCacheKey,
        slot: Int
    ): Boolean {
        if (slotActionsSupport[normalize(baseUrl)] != true) return false
        val deadline = System.currentTimeMillis() + STOP_IDLE_WAIT_MS
        while (true) {
            val facts = readFacts(baseUrl) ?: return false
            if (isSlotSettled(facts.slots.firstOrNull { it.id == slot })) break
            if (System.currentTimeMillis() >= deadline) {
                DebugLog.log("NativeChatKvCache: save after stop skipped reason=slot_busy")
                return false
            }
            delay(STOP_POLL_MS)
        }
        return LlamaSlotManager.withSlotRequest(normalize(baseUrl), slot) {
            val facts = readFacts(baseUrl) ?: return@withSlotRequest false
            val fact = facts.slots.firstOrNull { it.id == slot }
            if (!isSlotSettled(fact) || (fact?.nPromptTokens ?: 0) <= 0) return@withSlotRequest false
            if (saveSlot(baseUrl, slot, key, server, facts) != KvSaveResult.OK) return@withSlotRequest false
            val after = readFacts(baseUrl)?.slots?.firstOrNull { it.id == slot }
            holders[slotKey(baseUrl, slot)] = KvSlotHolderState(
                key = key,
                persists = true,
                dirty = false,
                server = server,
                idTask = after?.idTask,
                nPromptTokens = after?.nPromptTokens ?: 0
            )
            DebugLog.log("NativeChatKvCache: saved after stop ${key.namespace} slot=$slot")
            true
        }
    }

    // ---- idle compression ----

    /** At most once per hour per process, compresses idle caches in the background. */
    private fun maybeScheduleCompression() {
        startIdleTimer()
        val now = System.currentTimeMillis()
        val last = lastCompressionMs.get()
        if (now - last < COMPRESSION_INTERVAL_MS || !lastCompressionMs.compareAndSet(last, now)) return
        scope.launch { runCompressionPass(now) }
    }

    private suspend fun runCompressionPass(now: Long) {
        runCatching { compressIdleCaches(now) }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            DebugLog.log("NativeChatKvCache: compression pass failed ${it.javaClass.simpleName}")
        }
    }

    /**
     * Light timer on the shared IO scope: every [IDLE_TIMER_MS] it runs a compression pass when no
     * lease is active, keeping the hourly throttle. Started once per process; cancelled by
     * [stopIdleTimer]. Sleeps in `delay`, never a busy loop.
     */
    private fun startIdleTimer() {
        val current = idleTimer.get()
        if (current?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                delay(IDLE_TIMER_MS)
                if (inUseStems.isNotEmpty() || activeRequests.values.any { it.get() > 0 }) continue
                val now = System.currentTimeMillis()
                val last = lastCompressionMs.get()
                if (now - last < COMPRESSION_INTERVAL_MS || !lastCompressionMs.compareAndSet(last, now)) continue
                runCompressionPass(now)
            }
        }
        if (idleTimer.compareAndSet(current, job)) job.start() else job.cancel()
    }

    private fun protectedCompressionStems(): MutableSet<String> =
        holders.values.map { it.key.fileStem }.toMutableSet().apply {
            addAll(deferredSaves.keys)
            addAll(inUseStems.keys)
        }

    private suspend fun compressIdleCaches(nowMs: Long) {
        val speed = KvUnpackSpeedStore(appContext).effectiveBytesPerMs()
        val candidates = store.compressionCandidates(nowMs, speed, protectedCompressionStems())
        val result = compressStems(candidates) {}
        if (result.compressed > 0) DebugLog.log("NativeChatKvCache: compressed ${result.compressed} idle caches")
    }

    /**
     * "Compress now": compresses every eligible cache immediately, ignoring idle time but still
     * skipping in-use and holder stems and still applying the first-MiB sample rule. Takes the same
     * per-stem mutex as saves and restores. Reports progress before and after each stem.
     */
    suspend fun compressAllNow(onProgress: (KvCompressProgress) -> Unit = {}): KvCompressResult =
        withContext(Dispatchers.IO) {
            val candidates = store.compressionCandidates(
                System.currentTimeMillis(), KvCompressionPolicy.DEFAULT_BYTES_PER_MS,
                protectedCompressionStems(), ignoreIdle = true
            )
            compressStems(candidates, onProgress)
        }

    private suspend fun compressStems(
        candidates: List<String>,
        onProgress: (KvCompressProgress) -> Unit
    ): KvCompressResult {
        val savedBefore = store.compressionSavedBytes()
        var compressed = 0
        onProgress(KvCompressProgress(0, candidates.size))
        candidates.forEachIndexed { index, stem ->
            if (!inUseStems.containsKey(stem) && holders.values.none { it.key.fileStem == stem }) {
                val outcome = keyMutexes.getOrPut(stem) { Mutex() }.withLock {
                    if (inUseStems.containsKey(stem)) KvCompressionOutcome.SKIPPED else store.compressStem(stem)
                }
                if (outcome == KvCompressionOutcome.COMPRESSED) compressed++
            }
            onProgress(KvCompressProgress(index + 1, candidates.size))
        }
        val saved = (store.compressionSavedBytes() - savedBefore).coerceAtLeast(0L)
        return KvCompressResult(compressed, saved)
    }

    private suspend fun saveIfHolder(baseUrl: String, slot: Int, key: KvCacheKey, requireDirty: Boolean) {
        LlamaSlotManager.withSlotRequest(normalize(baseUrl), slot) {
            withContext(Dispatchers.IO) {
                val holder = holders[slotKey(baseUrl, slot)] ?: return@withContext
                if (holder.key != key || !holder.persists || (requireDirty && !holder.dirty)) return@withContext
                val facts = readFacts(baseUrl) ?: return@withContext
                if (!holder.matches(facts.slots.firstOrNull { it.id == slot })) return@withContext
                saveSlot(baseUrl, slot, key, holder.server, facts)
                Unit
            }
        }
    }

    private fun scheduleDeferredSave(baseUrl: String, slot: Int, key: KvCacheKey) {
        val job = scope.launch {
            delay(DEFERRED_SAVE_DELAY_MS)
            runCatching { saveIfHolder(baseUrl, slot, key, requireDirty = true) }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                DebugLog.log("NativeChatKvCache: deferred save failed ${key.namespace} ${it.javaClass.simpleName}")
            }
        }
        deferredSaves.put(key.fileStem, job)?.cancel()
        job.invokeOnCompletion { deferredSaves.remove(key.fileStem, job) }
    }

    private fun cancelDeferredSave(key: KvCacheKey) {
        deferredSaves.remove(key.fileStem)?.cancel()
    }

    private fun recordHolder(
        baseUrl: String,
        server: LlamaServerEntity?,
        key: KvCacheKey,
        persists: Boolean,
        slot: Int,
        dirty: Boolean = true
    ) {
        val parsed = runCatching { httpGet("${baseUrl.trimEnd('/')}/slots") }.getOrNull()
            ?.let { parseKvSlotsOrNull(it) }
        val fact = parsed?.firstOrNull { it.id == slot }
        // An unreadable /slots keeps the holder trusted (factsKnown=false) instead of storing facts
        // that can never match, which made every following request restore an older file.
        holders[slotKey(baseUrl, slot)] = KvSlotHolderState(
            key = key,
            persists = persists,
            dirty = dirty,
            server = server,
            idTask = fact?.idTask,
            nPromptTokens = fact?.nPromptTokens ?: 0,
            factsKnown = fact != null
        )
    }

    /** A usable saved file for [key]; stale or partial files are deleted. */
    private fun ownFileValid(server: LlamaServerEntity?, key: KvCacheKey, facts: KvServerFacts): Boolean =
        fileStatus(server, key, facts) == KvFileStatus.VALID

    /** Like [ownFileValid] but says why a file is unusable; stale or partial files are deleted. */
    private fun fileStatus(server: LlamaServerEntity?, key: KvCacheKey, facts: KvServerFacts): KvFileStatus {
        val meta = store.readMeta(key)
        if (meta == null || store.sizeBytes(key) <= 0L) {
            if (meta != null || store.sizeBytes(key) > 0L) store.delete(key)
            return KvFileStatus.NO_FILE
        }
        if (meta.fingerprint != fingerprintFor(server, facts)) {
            DebugLog.log("NativeChatKvCache: stale cache dropped ${key.namespace} reason=fingerprint")
            store.delete(key)
            return KvFileStatus.FINGERPRINT_MISMATCH
        }
        return KvFileStatus.VALID
    }

    private suspend fun restoreSlot(
        baseUrl: String,
        server: LlamaServerEntity?,
        key: KvCacheKey,
        slot: Int,
        onStatus: (String) -> Unit
    ) {
        val hk = slotKey(baseUrl, slot)
        onStatus(appContext.getString(R.string.native_chat_kv_cache_restoring))
        keyMutex(key).withLock {
            if (store.isCompressed(key)) {
                onStatus(appContext.getString(R.string.native_chat_kv_cache_unpacking))
                if (!store.unpack(key)) {
                    DebugLog.log("NativeChatKvCache: unpack failed ${key.namespace}")
                    store.delete(key)
                    onStatus(appContext.getString(R.string.native_chat_kv_cache_restore_failed))
                    holders.remove(hk)
                    return
                }
            }
            val result = postSlotAction(baseUrl, slot, "restore", store.slotFileName(key), RESTORE_TIMEOUT_MS)
            if (result.ok) {
                store.touch(key)
                val after = readFacts(baseUrl)?.slots?.firstOrNull { it.id == slot }
                holders[hk] = KvSlotHolderState(
                    key = key,
                    persists = true,
                    dirty = false,
                    server = server,
                    idTask = after?.idTask,
                    nPromptTokens = after?.nPromptTokens ?: 0
                )
                DebugLog.log(
                    "NativeChatKvCache: restored ${key.namespace} slot=$slot " +
                        "tokens=${result.json?.optInt("n_restored")} bytes=${result.json?.optLong("n_read")}"
                )
            } else {
                DebugLog.log("NativeChatKvCache: restore failed ${key.namespace} http=${result.code}")
                if (!result.notSupported) {
                    store.delete(key)
                    onStatus(appContext.getString(R.string.native_chat_kv_cache_restore_failed))
                }
                holders.remove(hk)
            }
        }
    }

    /** Saves slot [slot] as [key]; the caller holds the slot lock and verified the holder. */
    private suspend fun saveSlot(
        baseUrl: String,
        slot: Int,
        key: KvCacheKey,
        server: LlamaServerEntity?,
        facts: KvServerFacts
    ): KvSaveResult {
        val hk = slotKey(baseUrl, slot)
        return keyMutex(key).withLock {
            if (!store.canSave(store.sizeBytes(key))) {
                DebugLog.log("NativeChatKvCache: save skipped ${key.namespace} reason=low_disk")
                return@withLock KvSaveResult.LOW_DISK
            }
            val result = postSlotAction(baseUrl, slot, "save", store.slotFileName(key), SAVE_TIMEOUT_MS)
            if (!result.ok) {
                DebugLog.log("NativeChatKvCache: save failed ${key.namespace} http=${result.code}")
                return@withLock KvSaveResult.FAILED
            }
            val json = result.json
            store.removeCompressedCopy(key)
            store.writeMeta(
                key,
                KvCacheMeta(
                    fingerprint = fingerprintFor(server, facts),
                    slotNCtx = facts.slotNCtx,
                    bytes = json?.optLong("n_written") ?: store.sizeBytes(key),
                    tokens = json?.optInt("n_saved") ?: 0,
                    savedAt = System.currentTimeMillis(),
                    serverRowId = server?.id ?: 0L
                )
            )
            val after = readFacts(baseUrl)?.slots?.firstOrNull { it.id == slot }
            holders[hk]?.takeIf { it.key == key }?.let {
                holders[hk] = it.copy(dirty = false, idTask = after?.idTask, nPromptTokens = after?.nPromptTokens ?: 0)
            }
            DebugLog.log(
                "NativeChatKvCache: saved ${key.namespace} slot=$slot " +
                    "tokens=${json?.optInt("n_saved")} bytes=${json?.optLong("n_written")}"
            )
            enforceBudgetAfterSave(key)
            KvSaveResult.OK
        }
    }

    private fun enforceBudgetAfterSave(saved: KvCacheKey) {
        val protectedStems = holders.values.map { it.key.fileStem }.toMutableSet().apply {
            add(saved.fileStem)
            addAll(deferredSaves.keys)
            addAll(inUseStems.keys)
        }
        if (saved is KvCacheKey.Base) {
            store.enforceBudget(BASE_BUDGET_BYTES, protectedStems, setOf(KvCacheNamespace.BASE))
        } else if (saved is KvCacheKey.Harness) {
            store.enforceBudget(
                HarnessKvCacheSettings.get(appContext).budgetBytes.value,
                protectedStems,
                setOf(KvCacheNamespace.HARNESS)
            )
        } else {
            store.enforceBudget(
                DEFAULT_BUDGET_BYTES,
                protectedStems,
                setOf(KvCacheNamespace.CHAT, KvCacheNamespace.DELEGATE)
            )
        }
    }

    private fun fingerprintFor(server: LlamaServerEntity?, facts: KvServerFacts): String =
        NativeChatKvCacheStore.fingerprint(
            NativeChatKvCacheStore.fingerprintInput(
                profile = LlamaServerLaunchProfile.decode(server?.localLaunchProfileJson),
                propsModelPath = facts.modelPath,
                slotNCtx = facts.slotNCtx,
                chatTemplate = facts.chatTemplate
            )
        )

    /**
     * A save with a deliberately invalid filename distinguishes "started without
     * --slot-save-path" (specific error, checked first by the server) from a supporting server
     * (filename rejected) without touching any slot or file. A failed restore would instead
     * clear the slot's live KV, so it is never used as a probe.
     */
    private fun ensureSlotActionsSupported(baseUrl: String, slotId: Int): Boolean =
        probeSlotSupport(baseUrl, slotId) == true

    /** Cached probe result: true/false once known, null when the server did not answer. */
    private fun probeSlotSupport(baseUrl: String, slotId: Int = 0): Boolean? {
        val cacheKey = normalize(baseUrl)
        slotActionsSupport[cacheKey]?.let { return it }
        val result = postSlotAction(baseUrl, slotId, "save", PROBE_FILENAME, PROBE_TIMEOUT_MS, validateName = false)
        return when {
            result.notSupported -> {
                slotActionsSupport[cacheKey] = false
                DebugLog.log("NativeChatKvCache: server lacks --slot-save-path; restart required")
                false
            }
            result.code > 0 -> {
                slotActionsSupport[cacheKey] = true
                true
            }
            else -> null
        }
    }

    private fun readFacts(baseUrl: String): KvServerFacts? {
        val props = runCatching { httpGet("${baseUrl.trimEnd('/')}/props") }.getOrNull()
            ?.let { parseKvServerProps(it) } ?: return null
        val parsedSlots = runCatching { httpGet("${baseUrl.trimEnd('/')}/slots") }.getOrNull()
            ?.let { parseKvSlotsOrNull(it) }
        val slots = parsedSlots.orEmpty()
        val facts = props.copy(
            slots = slots,
            totalSlots = props.totalSlots.takeIf { it > 0 } ?: slots.size,
            slotsKnown = parsedSlots != null
        )
        observe(baseUrl, facts)
        return facts
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    }

    private data class SlotActionResult(
        val code: Int,
        val ok: Boolean,
        val notSupported: Boolean,
        val json: JSONObject?
    )

    private fun postSlotAction(
        baseUrl: String,
        slotId: Int,
        action: String,
        filename: String,
        readTimeoutMs: Int,
        validateName: Boolean = true
    ): SlotActionResult {
        if (validateName && !NativeChatKvCacheStore.isValidSlotFilename(filename)) {
            return SlotActionResult(code = 400, ok = false, notSupported = false, json = null)
        }
        val conn = URL("${baseUrl.trimEnd('/')}/slots/$slotId?action=$action").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("filename", filename).toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            SlotActionResult(
                code = code,
                ok = code in 200..299,
                notSupported = !(code in 200..299) && body.contains(NOT_SUPPORTED_MARKER, ignoreCase = true),
                json = runCatching { JSONObject(body) }.getOrNull()
            )
        } catch (error: Exception) {
            DebugLog.log("NativeChatKvCache: slot $action request failed ${error.javaClass.simpleName}")
            SlotActionResult(code = -1, ok = false, notSupported = false, json = null)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Holder recorded on a slot a utility request advanced; matches no chat, so chats restore their file. */
        private val UTILITY_KEY: KvCacheKey = KvCacheKey.Chat(Long.MIN_VALUE)

        private const val RESTORE_TIMEOUT_MS = 120_000
        private const val SAVE_TIMEOUT_MS = 120_000
        private const val PROBE_TIMEOUT_MS = 8_000
        private const val PROBE_FILENAME = "adt/probe"
        private const val NOT_SUPPORTED_MARKER = "does not support slots action"

        /** Delegate and harness caches are saved this long after their last request. */
        internal const val DEFERRED_SAVE_DELAY_MS = 10_000L

        /** Budget for chat and delegate caches together; harness caches use their own setting. */
        internal const val DEFAULT_BUDGET_BYTES = 4L * 1024 * 1024 * 1024

        /** Budget for the shared base caches (system prompt + tools prefixes). */
        internal const val BASE_BUDGET_BYTES = 1L * 1024 * 1024 * 1024

        internal const val COMPRESSION_INTERVAL_MS = 60L * 60 * 1000

        /** Idle timer period; the hourly throttle still applies. */
        internal const val IDLE_TIMER_MS = 30L * 60 * 1000

        /** After a stop: how long to wait for the slot to go idle, and the `/slots` polling period. */
        internal const val STOP_IDLE_WAIT_MS = 10_000L
        internal const val STOP_POLL_MS = 250L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val keyMutexes = ConcurrentHashMap<String, Mutex>()
        private val restartLocks = ConcurrentHashMap<String, Mutex>()
        private val deferredSaves = ConcurrentHashMap<String, Job>()
        private val holders = ConcurrentHashMap<String, KvSlotHolderState>()
        private val slotActionsSupport = ConcurrentHashMap<String, Boolean>()
        private val lastMaxIdTask = ConcurrentHashMap<String, Int>()
        private val lastCompressionMs = AtomicLong(0L)
        private val idleTimer = java.util.concurrent.atomic.AtomicReference<Job?>(null)

        init {
            // Deleted caches (Erase all, Clear, eviction, failed restore) must not stay recorded as slot holders.
            NativeChatKvCacheStore.addDeletionListener { stems ->
                holders.values.removeAll { it.key.fileStem in stems }
            }
        }

        /** Cancels the idle compression timer (tests / shutdown). */
        internal fun stopIdleTimer() {
            idleTimer.getAndSet(null)?.cancel()
        }

        /** Base stems being built right now (prepare or background rebuild). */
        private val baseBuilds: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val baseBuildAttempts = ConcurrentHashMap<String, Long>()

        /** Requests currently running through leases of this process, per physical endpoint. */
        private val activeRequests = ConcurrentHashMap<String, AtomicInteger>()

        /** Background base builds wait this long and then require an idle server. */
        internal const val BASE_BUILD_DELAY_MS = 2_000L

        /** Stems pinned by an active lease or an in-flight base restore/capture; value = holders. */
        private val inUseStems = ConcurrentHashMap<String, Int>()

        private fun markInUse(key: KvCacheKey) {
            inUseStems.merge(key.fileStem, 1) { a, b -> a + b }
        }

        private fun unmarkInUse(key: KvCacheKey) {
            inUseStems.compute(key.fileStem) { _, n -> if (n == null || n <= 1) null else n - 1 }
        }

        private fun normalize(baseUrl: String) = normalizePhysicalEndpoint(baseUrl)

        private fun keyMutex(key: KvCacheKey): Mutex = keyMutexes.getOrPut(key.fileStem) { Mutex() }

        private fun restartLock(baseUrl: String): Mutex = restartLocks.getOrPut(normalize(baseUrl)) { Mutex() }

        private fun slotKey(baseUrl: String, slotId: Int) = "${normalize(baseUrl)}#$slotId"

        /**
         * llama-server task ids restart at zero with a new process, so a drop (or all slots
         * blank after tasks were seen) marks a restart of the endpoint.
         */
        private fun observe(baseUrl: String, facts: KvServerFacts) {
            if (!facts.slotsKnown) return
            val maxId = facts.slots.mapNotNull { it.idTask }.maxOrNull() ?: -1
            val previous = lastMaxIdTask.put(normalize(baseUrl), maxId)
            if (shouldInferRestart(true, previous, maxId)) onServerRestarted(baseUrl, keepLastId = maxId)
        }

        /** Clears slot-support, holder and dirty state of one physical endpoint. */
        internal fun onServerRestarted(baseUrl: String, keepLastId: Int? = null) {
            val endpoint = normalize(baseUrl)
            slotActionsSupport.remove(endpoint)
            holders.keys.removeAll { it.startsWith("$endpoint#") }
            if (keepLastId != null) lastMaxIdTask[endpoint] = keepLastId else lastMaxIdTask.remove(endpoint)
        }

        /** Test hook: clears the in-memory endpoint state. */
        internal fun resetForTest() {
            keyMutexes.clear(); holders.clear(); slotActionsSupport.clear(); lastMaxIdTask.clear()
            deferredSaves.values.forEach { it.cancel() }; deferredSaves.clear(); restartLocks.clear()
            inUseStems.clear(); lastCompressionMs.set(0L); stopIdleTimer()
            baseBuilds.clear(); baseBuildAttempts.clear(); activeRequests.clear()
        }
    }
}

/**
 * Pure base-cache decisions. The base is restored only when a hint exists, the owner has no state
 * of its own in the slot (a valid own file or the slot already holding it) and a valid base file
 * exists. It is captured after a request only if the owner started from no state of its own, so a
 * long restored conversation is never saved as a "base".
 */
internal fun shouldRestoreBase(hasHint: Boolean, ownStatePresent: Boolean, baseFileValid: Boolean): Boolean =
    hasHint && !ownStatePresent && baseFileValid

internal fun shouldCaptureBase(capture: Boolean, baseFileValid: Boolean, ownStatePresent: Boolean): Boolean =
    capture && !baseFileValid && !ownStatePresent

/** Pure mapping so the reasons can be unit-tested without Android. */
internal fun kvCacheEligibility(
    isLlamaServerEngine: Boolean,
    isLoopback: Boolean,
    persistKvCache: Boolean,
    slotActionsSupported: Boolean?
): KvCacheEligibility = when {
    !isLlamaServerEngine -> KvCacheEligibility.UNSUPPORTED_ENGINE
    !isLoopback -> KvCacheEligibility.REMOTE_SERVER
    slotActionsSupported == false -> KvCacheEligibility.SERVER_NEEDS_RESTART
    !persistKvCache -> KvCacheEligibility.DISABLED
    else -> KvCacheEligibility.ELIGIBLE
}

internal fun parseKvServerProps(body: String): KvServerFacts? = runCatching {
    val props = JSONObject(body)
    KvServerFacts(
        modelPath = props.optString("model_path"),
        slotNCtx = props.optJSONObject("default_generation_settings")?.optInt("n_ctx") ?: 0,
        totalSlots = props.optInt("total_slots"),
        chatTemplate = props.optString("chat_template"),
        slots = emptyList()
    )
}.getOrNull()

internal fun parseKvSlots(body: String): List<KvSlotFact> = parseKvSlotsOrNull(body).orEmpty()

/** Null when [body] is not a JSON array (an error page), so callers can tell UNKNOWN from empty. */
internal fun parseKvSlotsOrNull(body: String): List<KvSlotFact>? = runCatching {
    val array = JSONArray(body)
    (0 until array.length()).mapNotNull { index ->
        val slot = array.optJSONObject(index) ?: return@mapNotNull null
        KvSlotFact(
            id = slot.optInt("id", index),
            nCtx = slot.optInt("n_ctx"),
            idTask = if (slot.has("id_task")) slot.optInt("id_task") else null,
            nPromptTokens = slot.optInt("n_prompt_tokens"),
            isProcessing = if (slot.has("is_processing")) slot.optBoolean("is_processing") else null
        )
    }
}.getOrNull()
