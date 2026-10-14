package com.example.llamadroid.service.compaction

import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.parseKvServerProps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * The context window a native chat really has. Chats created from the New chat sheet store
 * `contextSize = 0` ("use the server's"); without resolving it the context ring has no window
 * and compaction never triggers. Order: the chat's own positive value, the managed card's probed
 * per-slot context, then llama-server's `/props` per-slot `n_ctx` (cached briefly per endpoint).
 */
object NativeChatContextWindow {
    private const val CACHE_MS = 10 * 60_000L
    private data class Cached(val tokens: Int, val at: Long)
    private val probed = ConcurrentHashMap<String, Cached>()

    /** Pure choice between the known sources; 0 when none is known. */
    fun choose(chatContext: Int, managedContext: Int?, probedContext: Int?): Int = when {
        chatContext > 0 && managedContext != null && managedContext > 0 -> minOf(chatContext, managedContext)
        chatContext > 0 -> chatContext
        managedContext != null && managedContext > 0 -> managedContext
        probedContext != null && probedContext > 0 -> probedContext
        else -> 0
    }

    /** Resolves the window for a llama-server chat; other engines keep the chat's own value. */
    suspend fun resolve(server: LlamaServerEntity, chat: LlamaChatEntity, managedContext: Int?): Int {
        if (server.isLiteRtEngine() || server.isOllamaEngine()) return chat.contextSize.coerceAtLeast(0)
        val direct = choose(chat.contextSize, managedContext, null)
        if (direct > 0) return direct
        return choose(0, null, probe(server.baseUrl()))
    }

    /** Last probed per-slot context for [baseUrl], without network I/O. */
    fun cached(baseUrl: String): Int? = probed[baseUrl.trimEnd('/')]?.tokens

    private suspend fun probe(baseUrl: String): Int? {
        val key = baseUrl.trimEnd('/')
        probed[key]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }?.let { return it.tokens }
        val tokens = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL("$key/props").openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 3_000
                    conn.readTimeout = 3_000
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.bufferedReader().use { it.readText() }
                            .let { parseKvServerProps(it)?.slotNCtx }
                    } else null
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }?.takeIf { it > 0 } ?: return null
        probed[key] = Cached(tokens, System.currentTimeMillis())
        return tokens
    }
}
