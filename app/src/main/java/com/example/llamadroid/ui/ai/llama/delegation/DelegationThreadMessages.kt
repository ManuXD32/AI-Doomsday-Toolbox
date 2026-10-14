package com.example.llamadroid.ui.ai.llama.delegation

import android.content.Context
import com.example.llamadroid.harness.HarnessAppRuntime
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.service.delegation.HarnessLatestPage
import com.example.llamadroid.ui.agent.harness.HarnessTranscriptRole
import com.example.llamadroid.ui.agent.harness.harnessParseRecord
import com.example.llamadroid.ui.agent.harness.objectArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One message of a harness thread: [text] is capped at [DelegationThreadMessages.DETAIL_CHARS]; [preview] at [DelegationThreadMessages.MAX_CHARS]. */
data class DelegationThreadMessage(val role: String, val text: String, val preview: String, val truncated: Boolean)

/** Outcome of loading a thread's last messages. */
sealed class DelegationThreadMessagesResult {
    data class Loaded(val messages: List<DelegationThreadMessage>) : DelegationThreadMessagesResult()
    object RuntimeNotRunning : DelegationThreadMessagesResult()
    /** The read failed; [detail] is the runtime's bounded `CODE: message`, or empty when unknown. */
    data class Failed(val detail: String = "") : DelegationThreadMessagesResult()
}

/** On-demand reader of a delegated thread's last messages through the runtime client. */
object DelegationThreadMessages {
    const val MAX_CHARS = 1_500
    const val DETAIL_CHARS = 20_000
    const val MESSAGE_COUNT = 2
    /** Records requested: tool-only records are skipped, so ask for more than [MESSAGE_COUNT]. */
    private const val PAGE_RECORDS = 16
    private const val MAX_DETAIL_ERROR_CHARS = 200

    /** Keeps the last [MESSAGE_COUNT] entries and bounds their texts; pure and testable. */
    fun bound(items: List<Pair<String, String>>): List<DelegationThreadMessage> =
        items.takeLast(MESSAGE_COUNT).map { (role, text) ->
            DelegationThreadMessage(role, text.take(DETAIL_CHARS), text.take(MAX_CHARS), text.length > MAX_CHARS)
        }

    /** Bounded one-line `CODE: message` for the dialog; pure and testable. */
    fun failureDetail(code: String, message: String): String =
        listOf(code.trim(), message.trim()).filter { it.isNotEmpty() }.joinToString(": ").take(MAX_DETAIL_ERROR_CHARS)

    /** [lastSeq] is the newest known session sequence (or -1); the read is pinned to it via `throughSeq`. */
    suspend fun load(context: Context, runtimeId: String, sessionId: String, lastSeq: Long = -1L): DelegationThreadMessagesResult =
        withContext(Dispatchers.IO) {
            try {
                val client = HarnessAppRuntime.forRuntime(context.applicationContext, runtimeId).client
                    ?.takeIf { it.state.value == HarnessConnectionState.READY }
                    ?: return@withContext DelegationThreadMessagesResult.RuntimeNotRunning
                // session/page rejects a request without throughSeq (the cause of "could not be loaded").
                val result = HarnessLatestPage.page(
                    client = client,
                    address = buildJsonObject { put("kind", "session"); put("sessionId", sessionId) },
                    knownSeq = lastSeq,
                    beforeSeq = null,
                    maxMessages = PAGE_RECORDS,
                )
                val value = (result as? HarnessRpcResult.Success)?.value
                    ?: return@withContext DelegationThreadMessagesResult.Failed(
                        (result as? HarnessRpcResult.Failure)?.error?.let { failureDetail(it.code, it.message) }.orEmpty()
                    )
                val items = value.objectArray("records").flatMap { harnessParseRecord(it) }
                    .filter { it.role == HarnessTranscriptRole.USER || it.role == HarnessTranscriptRole.ASSISTANT }
                    .map { it.role.name to it.text }
                DelegationThreadMessagesResult.Loaded(bound(items))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DelegationThreadMessagesResult.Failed(failureDetail(error.javaClass.simpleName, error.message.orEmpty()))
            }
        }
}
