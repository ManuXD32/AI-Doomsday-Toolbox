package com.example.llamadroid.service

import java.security.MessageDigest

/** Which kind of owner a saved llama-server slot file belongs to. */
enum class KvCacheNamespace(val stemPrefix: String) {
    CHAT("chat-"),
    DELEGATE("delegate-"),
    HARNESS("harness-"),
    /** Preprocessed system prompt + tools prefix shared by new chats / harness threads. */
    BASE("base-")
}

/**
 * Identity of one persisted KV cache in `files/llama_kv_slots/`. File stems never contain chat
 * text; harness session ids are hashed so they cannot be read back from the file name.
 */
sealed class KvCacheKey {
    abstract val namespace: KvCacheNamespace
    abstract val fileStem: String

    /** A native chat; the stem is unchanged from DB 128 (`chat-<id>`, `chat-n<k>` for negatives). */
    data class Chat(val chatId: Long) : KvCacheKey() {
        override val namespace: KvCacheNamespace get() = KvCacheNamespace.CHAT
        override val fileStem: String
            get() = if (chatId >= 0) "chat-$chatId" else "chat-n${-(chatId + 1)}"
    }

    /** The delegation subagent of one DeepSeek task. */
    data class Delegate(val taskId: Long) : KvCacheKey() {
        override val namespace: KvCacheNamespace get() = KvCacheNamespace.DELEGATE
        override val fileStem: String get() = "delegate-${taskId.coerceAtLeast(0)}"
    }

    /** A DeepSeek Harness session routed through the Android bridge. */
    data class Harness(val runtimeId: String, val sessionId: String) : KvCacheKey() {
        override val namespace: KvCacheNamespace get() = KvCacheNamespace.HARNESS
        override val fileStem: String
            get() = "${harnessRuntimeStemPrefix(runtimeId)}${sha256Hex(sessionId).take(16)}"
    }

    /**
     * A preprocessed prefix (system prompt + tool definitions) that new owners restore before their
     * first request. [scope] is `chat` or `harness`; [prefixHash] covers the exact prefix text and
     * the server's model fingerprint, so any change produces a different key.
     */
    data class Base(val scope: String, val prefixHash: String) : KvCacheKey() {
        override val namespace: KvCacheNamespace get() = KvCacheNamespace.BASE
        override val fileStem: String
            get() = "base-${scope.filter { it.isLetter() }.take(8)}-${sha256Hex(prefixHash).take(16)}"
    }

    companion object {
        /** Prefix shared by every harness cache of [runtimeId]; used to clear one runtime. */
        fun harnessRuntimeStemPrefix(runtimeId: String): String =
            "harness-${sha256Hex(runtimeId).take(8)}-"

        /** Namespace of a slot file stem, or null for files this app did not write. */
        fun namespaceOfStem(stem: String): KvCacheNamespace? = when {
            CHAT_STEM.matches(stem) -> KvCacheNamespace.CHAT
            DELEGATE_STEM.matches(stem) -> KvCacheNamespace.DELEGATE
            HARNESS_STEM.matches(stem) -> KvCacheNamespace.HARNESS
            BASE_STEM.matches(stem) -> KvCacheNamespace.BASE
            else -> null
        }

        private val CHAT_STEM = Regex("chat-n?\\d+")
        private val DELEGATE_STEM = Regex("delegate-\\d+")
        private val HARNESS_STEM = Regex("harness-[0-9a-f]{8}-[0-9a-f]{16}")
        private val BASE_STEM = Regex("base-[a-z]{1,8}-[0-9a-f]{16}")

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * How one owner uses a slot. [persist] saves and restores [key] on disk; when false the slot is
 * only pinned for affinity. [conversationId] groups requests of the same owner for slot affinity.
 */
data class KvSlotOwnerSpec(
    val key: KvCacheKey,
    val persist: Boolean,
    val conversationId: String
)

/**
 * A slot reserved for one owner for the length of a turn. The per-slot lock is held only inside
 * [request], so tool execution between model calls never blocks other owners of the same server.
 */
interface KvSlotLease {
    /** The pinned slot, or null when the server does not support slot selection. */
    val slotId: Int?

    /**
     * Runs exactly one HTTP request on the slot. Before [block] runs, the previous holder's state
     * is saved when it is dirty and persisted, and this owner's saved state is restored when
     * valid. When this owner has no valid state of its own and [base] names a valid base cache,
     * the base is restored instead; after a successful request a missing base is rebuilt (or,
     * for hints without a builder, captured).
     * Callers must pass `slotOwner = null` to `LlamaServerChatService` inside [block],
     * because the slot lock is not re-entrant.
     */
    suspend fun <T> request(base: KvBaseHint? = null, block: suspend (slotId: Int?) -> T): T

    /**
     * After a stopped (cancelled) turn: waits until the slot is idle, saves this owner's state to
     * disk when it persists, and records it as the slot's current holder, so Continue and the next
     * turn reuse the work instead of an older file. Returns true when a state was saved.
     */
    suspend fun saveAfterStop(): Boolean = false
}

/**
 * Base-cache hint for one request; see [KvSlotLease.request]. When [builder] is set, a missing or
 * invalid base is rebuilt in the background by running [builder] (a 1-token request containing
 * only the stable prefix) on a free slot and saving that slot; the owner's own state is never
 * saved as a base. Without a [builder] (DeepSeek harness) the legacy capture-after-first-request
 * behaviour applies when [capture] is true.
 */
data class KvBaseHint(
    val key: KvCacheKey.Base,
    val capture: Boolean = true,
    val builder: (suspend (slotId: Int) -> Unit)? = null
)

/** Lease used when caching or slot selection is unavailable; requests run unpinned. */
object UnpinnedKvSlotLease : KvSlotLease {
    override val slotId: Int? = null
    override suspend fun <T> request(base: KvBaseHint?, block: suspend (slotId: Int?) -> T): T = block(null)
}
