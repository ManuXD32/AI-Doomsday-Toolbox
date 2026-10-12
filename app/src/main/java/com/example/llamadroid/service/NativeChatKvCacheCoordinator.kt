package com.example.llamadroid.service

import android.content.Context
import androidx.annotation.StringRes
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
internal data class KvSlotFact(val id: Int, val nCtx: Int, val idTask: Int?, val nPromptTokens: Int)

/** Server facts read from `/props` and `/slots`. */
internal data class KvServerFacts(
    val modelPath: String,
    val slotNCtx: Int,
    val totalSlots: Int,
    val chatTemplate: String,
    val slots: List<KvSlotFact>
)

/**
 * Keeps one llama-server slot per opted-in chat and persists its KV state across idle stops,
 * sleeps and restarts using llama-server's `POST /slots/{id}?action=save|restore`.
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
            slotActionsSupported = slotActionsSupport[server.baseUrl()]
        )

    /**
     * Runs [block] with the slot pinned to this chat, restoring the saved KV state first when it
     * is valid. Ineligible chats and any setup failure fall back to `block(null)`.
     */
    suspend fun <T> withChatSlot(
        server: LlamaServerEntity,
        chat: LlamaChatEntity,
        onStatus: (String) -> Unit = {},
        block: suspend (slotId: Int?) -> T
    ): T {
        if (eligibility(server, chat) != KvCacheEligibility.ELIGIBLE) return block(null)
        val baseUrl = server.baseUrl()
        val facts = withContext(Dispatchers.IO) { readFacts(baseUrl) } ?: return block(null)
        if (facts.totalSlots <= 0 || facts.slotNCtx <= 0) return block(null)
        val owner = LlamaSlotOwnerKey(
            endpointGeneration = "native-chat-kv:$baseUrl|${facts.slotNCtx}",
            modelConfiguration = facts.modelPath,
            conversationId = "native-chat:${chat.id}",
            agentSessionId = ""
        )
        return LlamaSlotManager.withAssignedSlot(
            owner = owner,
            slotCount = facts.totalSlots,
            affinityMode = LlamaSlotAffinityMode.ENABLED
        ) { slotId ->
            if (slotId == null) return@withAssignedSlot block(null)
            val usable = withContext(Dispatchers.IO) { ensureSlotActionsSupported(baseUrl, slotId) }
            if (usable) {
                // Contended only by a save still in flight for this chat; wait for it.
                chatMutex(chat.id).withLock {
                    withContext(Dispatchers.IO) { restoreIfNeeded(server, chat, facts, slotId, baseUrl, onStatus) }
                }
            }
            block(slotId.takeIf { usable })
        }
    }

    /**
     * Saves the slot after a completed reply. Asynchronous and serialized per chat; the caller
     * must skip it for cancelled or failed turns. The chat lock is taken before this returns so
     * a following turn waits for the save instead of restoring an older file over fresh state.
     */
    fun scheduleSave(server: LlamaServerEntity, chat: LlamaChatEntity, slotId: Int) {
        if (eligibility(server, chat) != KvCacheEligibility.ELIGIBLE) return
        val mutex = chatMutex(chat.id)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching { saveSlot(server, chat, slotId) }.onFailure {
                        DebugLog.log("NativeChatKvCache: save failed chat=${chat.id} ${it.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    /** Forget everything remembered about an endpoint; call when its server (re)starts. */
    fun onServerRestarted(baseUrl: String) = Companion.onServerRestarted(baseUrl)

    // ---- internals ----

    private fun restoreIfNeeded(
        server: LlamaServerEntity,
        chat: LlamaChatEntity,
        facts: KvServerFacts,
        slotId: Int,
        baseUrl: String,
        onStatus: (String) -> Unit
    ) {
        val meta = store.readMeta(chat.id)
        if (meta == null || store.sizeBytes(chat.id) <= 0L) {
            if (meta != null || store.sizeBytes(chat.id) > 0L) store.delete(chat.id)
            return
        }
        // The slot may have been advanced by this chat's own save or turn; re-read for freshness.
        val current = readFacts(baseUrl) ?: facts
        val expected = fingerprintFor(server, current)
        if (meta.fingerprint != expected) {
            DebugLog.log("NativeChatKvCache: stale cache dropped chat=${chat.id} reason=fingerprint")
            store.delete(chat.id)
            holders.remove(slotKey(baseUrl, slotId))
            return
        }
        val slot = current.slots.firstOrNull { it.id == slotId }
        val holder = holders[slotKey(baseUrl, slotId)]
        if (holder != null && slot != null && holder.matches(chat.id, slot)) return

        onStatus(appContext.getString(R.string.native_chat_kv_cache_restoring))
        val result = postSlotAction(baseUrl, slotId, "restore", store.slotFileName(chat.id), RESTORE_TIMEOUT_MS)
        if (result.ok) {
            val after = readFacts(baseUrl)?.slots?.firstOrNull { it.id == slotId }
            if (after != null) holders[slotKey(baseUrl, slotId)] = SlotHolder(chat.id, after.idTask, after.nPromptTokens)
            DebugLog.log(
                "NativeChatKvCache: restored chat=${chat.id} slot=$slotId " +
                    "tokens=${result.json?.optInt("n_restored")} bytes=${result.json?.optLong("n_read")}"
            )
        } else {
            DebugLog.log("NativeChatKvCache: restore failed chat=${chat.id} http=${result.code}")
            if (!result.notSupported) {
                store.delete(chat.id)
                onStatus(appContext.getString(R.string.native_chat_kv_cache_restore_failed))
            }
            holders.remove(slotKey(baseUrl, slotId))
        }
    }

    private fun saveSlot(server: LlamaServerEntity, chat: LlamaChatEntity, slotId: Int) {
        val baseUrl = server.baseUrl()
        val facts = readFacts(baseUrl) ?: return
        val result = postSlotAction(baseUrl, slotId, "save", store.slotFileName(chat.id), SAVE_TIMEOUT_MS)
        if (!result.ok) {
            DebugLog.log("NativeChatKvCache: save failed chat=${chat.id} http=${result.code}")
            return
        }
        val json = result.json
        store.writeMeta(
            chat.id,
            KvCacheMeta(
                fingerprint = fingerprintFor(server, facts),
                slotNCtx = facts.slotNCtx,
                bytes = json?.optLong("n_written") ?: store.sizeBytes(chat.id),
                tokens = json?.optInt("n_saved") ?: 0,
                savedAt = System.currentTimeMillis(),
                serverRowId = server.id
            )
        )
        val after = readFacts(baseUrl)?.slots?.firstOrNull { it.id == slotId }
        if (after != null) holders[slotKey(baseUrl, slotId)] = SlotHolder(chat.id, after.idTask, after.nPromptTokens)
        DebugLog.log(
            "NativeChatKvCache: saved chat=${chat.id} slot=$slotId " +
                "tokens=${json?.optInt("n_saved")} bytes=${json?.optLong("n_written")}"
        )
    }

    private fun fingerprintFor(server: LlamaServerEntity, facts: KvServerFacts): String =
        NativeChatKvCacheStore.fingerprint(
            NativeChatKvCacheStore.fingerprintInput(
                profile = LlamaServerLaunchProfile.decode(server.localLaunchProfileJson),
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
    private fun ensureSlotActionsSupported(baseUrl: String, slotId: Int): Boolean {
        slotActionsSupport[baseUrl]?.let { return it }
        val result = postSlotAction(baseUrl, slotId, "save", PROBE_FILENAME, PROBE_TIMEOUT_MS, validateName = false)
        return when {
            result.notSupported -> {
                slotActionsSupport[baseUrl] = false
                DebugLog.log("NativeChatKvCache: server lacks --slot-save-path; restart required")
                false
            }
            result.code > 0 -> {
                slotActionsSupport[baseUrl] = true
                true
            }
            else -> false
        }
    }

    private fun readFacts(baseUrl: String): KvServerFacts? {
        val props = runCatching { httpGet("${baseUrl.trimEnd('/')}/props") }.getOrNull()
            ?.let { parseKvServerProps(it) } ?: return null
        val slots = runCatching { httpGet("${baseUrl.trimEnd('/')}/slots") }.getOrNull()
            ?.let { parseKvSlots(it) }.orEmpty()
        val facts = props.copy(slots = slots, totalSlots = props.totalSlots.takeIf { it > 0 } ?: slots.size)
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

    private data class SlotHolder(val chatId: Long, val idTask: Int?, val nPromptTokens: Int) {
        fun matches(chatId: Long, slot: KvSlotFact): Boolean =
            this.chatId == chatId && idTask != null && idTask == slot.idTask &&
                nPromptTokens > 0 && nPromptTokens == slot.nPromptTokens
    }

    companion object {
        private const val RESTORE_TIMEOUT_MS = 120_000
        private const val SAVE_TIMEOUT_MS = 120_000
        private const val PROBE_TIMEOUT_MS = 8_000
        private const val PROBE_FILENAME = "adt/probe"
        private const val NOT_SUPPORTED_MARKER = "does not support slots action"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val chatMutexes = ConcurrentHashMap<Long, Mutex>()
        private val holders = ConcurrentHashMap<String, SlotHolder>()
        private val slotActionsSupport = ConcurrentHashMap<String, Boolean>()
        private val lastMaxIdTask = ConcurrentHashMap<String, Int>()

        private fun chatMutex(chatId: Long): Mutex = chatMutexes.getOrPut(chatId) { Mutex() }

        private fun slotKey(baseUrl: String, slotId: Int) = "$baseUrl#$slotId"

        /**
         * llama-server task ids restart at zero with a new process, so a drop (or all slots
         * blank after tasks were seen) marks a restart of the endpoint.
         */
        private fun observe(baseUrl: String, facts: KvServerFacts) {
            val maxId = facts.slots.mapNotNull { it.idTask }.maxOrNull() ?: -1
            val previous = lastMaxIdTask.put(baseUrl, maxId)
            if (previous != null && maxId < previous) onServerRestarted(baseUrl, keepLastId = maxId)
        }

        internal fun onServerRestarted(baseUrl: String, keepLastId: Int? = null) {
            slotActionsSupport.remove(baseUrl)
            holders.keys.removeAll { it.startsWith("$baseUrl#") }
            if (keepLastId != null) lastMaxIdTask[baseUrl] = keepLastId else lastMaxIdTask.remove(baseUrl)
        }

        /** Test hook: clears the in-memory endpoint state. */
        internal fun resetForTest() {
            chatMutexes.clear(); holders.clear(); slotActionsSupport.clear(); lastMaxIdTask.clear()
        }
    }
}

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

internal fun parseKvSlots(body: String): List<KvSlotFact> = runCatching {
    val array = JSONArray(body)
    (0 until array.length()).mapNotNull { index ->
        val slot = array.optJSONObject(index) ?: return@mapNotNull null
        KvSlotFact(
            id = slot.optInt("id", index),
            nCtx = slot.optInt("n_ctx"),
            idTask = if (slot.has("id_task")) slot.optInt("id_task") else null,
            nPromptTokens = slot.optInt("n_prompt_tokens")
        )
    }
}.getOrDefault(emptyList())
