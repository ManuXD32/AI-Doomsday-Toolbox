package com.example.llamadroid.service.chat

import com.example.llamadroid.service.KvSlotLease
import com.example.llamadroid.service.LlamaServerChatService
import com.example.llamadroid.service.NativeChatToolConfig
import kotlinx.coroutines.Job
import okhttp3.Call
import okhttp3.ResponseBody
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Everything that used to be one set of app-wide fields in `LlamaClientService`, now per chat:
 * the job, the HTTP calls in flight, the KV lease, the stop flag, the video mode, the dedicated
 * reasoning accumulator and a private [LlamaServerChatService] (whose `stopGeneration()` only
 * cancels this session's call). It is also a coroutine context element so deep helpers can find
 * their own session with `currentCoroutineContext()[ChatGenerationSession]`.
 */
internal class ChatGenerationSession(val chatId: Long) :
    AbstractCoroutineContextElement(ChatGenerationSession) {

    companion object Key : CoroutineContext.Key<ChatGenerationSession>

    @Volatile var job: Job? = null
    val stopping = AtomicBoolean(false)
    @Volatile var activeCall: Call? = null
    @Volatile var activeRetrofitCall: retrofit2.Call<ResponseBody>? = null

    /** Lease of the turn in flight; model HTTP calls run inside [KvSlotLease.request]. */
    @Volatile var kvLease: KvSlotLease? = null

    /** Effective per-chat video mode of the turn in flight. */
    @Volatile var videoMode: String = NativeChatToolConfig.VIDEO_INPUT_SERVER

    @Volatile var cumulativeDedicatedReasoning: String = ""

    /** True when the turn talks to Ollama (its stop switch is process-wide). */
    @Volatile var usesOllama: Boolean = false

    val llamaServerChatService = LlamaServerChatService()

    fun cancelTransport() {
        activeCall?.cancel()
        activeCall = null
        activeRetrofitCall?.cancel()
        activeRetrofitCall = null
    }
}
