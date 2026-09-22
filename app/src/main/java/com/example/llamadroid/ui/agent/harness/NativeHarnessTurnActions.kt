package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

private const val MAX_COPY_BYTES = 256 * 1024

/** Copy target with the same visible-text distinction as the pinned Chat actions. */
internal enum class NativeHarnessTurnCopyKind {
    USER_MESSAGE,
    ASSISTANT_TURN,
}

/** Actions owned by the transcript surface and intentionally independent of UI state models. */
internal sealed interface NativeHarnessTurnAction {
    data class CopyUserMessage(
        val sessionId: String,
        val turn: Long?,
        val throughSequence: Long,
        val messageSequence: Long,
    ) : NativeHarnessTurnAction

    /**
     * With [finalAssistantSequence], this is the exact pinned turn-tail copy. Without it,
     * every append-origin assistant text message in the turn is copied in event order.
     */
    data class CopyAssistantTurn(
        val sessionId: String,
        val turn: Long,
        val throughSequence: Long,
        val finalAssistantSequence: Long? = null,
    ) : NativeHarnessTurnAction

    /** [assistantSequence] is the real finalized assistant event seq, never a synthetic UI seq. */
    data class ForkAt(
        val sessionId: String,
        val assistantSequence: Long,
    ) : NativeHarnessTurnAction
}

internal sealed interface NativeHarnessTurnResult {
    data class Copied(
        val sessionId: String,
        val turn: Long?,
        val kind: NativeHarnessTurnCopyKind,
        val text: String,
        val pageCount: Int,
    ) : NativeHarnessTurnResult

    data class Forked(
        val sourceSessionId: String,
        val assistantSequence: Long,
        val childSessionId: String,
    ) : NativeHarnessTurnResult

    data class Failed(
        val sessionId: String,
        val code: String,
        val message: String,
    ) : NativeHarnessTurnResult

    /** Stale work is silently dropped by the UI caller; it must never replace a newer session. */
    data class Ignored(val code: String) : NativeHarnessTurnResult
}

/** Narrow transport seam so history tests do not need to construct an authenticated HTTP client. */
internal interface NativeHarnessTurnTransport {
    /** Stable identity of the authenticated client instance backing this transport. */
    val identity: Any

    /** Receives the raw SessionPageRequest object; the client adapter adds the RPC request wrapper. */
    suspend fun pageSession(request: JsonObject): HarnessRpcResult

    /** Receives the complete `session/fork` call args, including its `{request:{...}}` wrapper. */
    suspend fun forkSession(callArgs: JsonObject): HarnessRpcResult
}

/** Production adapter preserving the authenticated client and the official RPC envelopes. */
internal class NativeHarnessClientTurnTransport(
    private val client: HarnessClient,
) : NativeHarnessTurnTransport {
    override val identity: Any get() = client

    override suspend fun pageSession(request: JsonObject): HarnessRpcResult = client.pageSession(request)

    override suspend fun forkSession(callArgs: JsonObject): HarnessRpcResult = client.call(
        namespace = "session",
        method = "fork",
        args = callArgs,
        policy = HarnessCallPolicy.NoRetry,
    )
}

/**
 * Session-scoped, cancellable backend for transcript copy and branch actions.
 *
 * A caller creates one instance for the controller lifetime, calls [invalidateSelection] whenever
 * the selected session/client changes, and maps [NativeHarnessTurnResult] into its existing UI
 * state. Each copy reads backwards through `session/page`, retaining only extracted text chunks;
 * raw history records are never accumulated across pages.
 */
internal class NativeHarnessTurnActions(
    private val scope: CoroutineScope,
    private val transportProvider: () -> NativeHarnessTurnTransport?,
    private val selectedSessionProvider: () -> String?,
    private val sessionAddressProvider: (String) -> JsonObject? = { ordinaryHarnessSessionAddress(it).toWire() },
    private val onResult: suspend (NativeHarnessTurnResult) -> Unit = {},
) : AutoCloseable {
    private val generation = AtomicLong(0L)
    private val activeJob = AtomicReference<Job?>()

    /** Start one action and cancel any older copy/fork operation. */
    fun dispatch(action: NativeHarnessTurnAction): Job {
        val token = generation.incrementAndGet()
        val capturedIdentity = transportProvider()?.identity
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val result = runAction(action, token, capturedIdentity)
                if (result !is NativeHarnessTurnResult.Ignored && isCurrent(action, token, capturedIdentity)) {
                    onResult(result)
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                activeJob.compareAndSet(coroutineContext[Job], null)
            }
        }
        activeJob.getAndSet(job)?.cancel()
        job.start()
        return job
    }

    /** Cancel current work and invalidate callbacks from all older session generations. */
    fun invalidateSelection() {
        generation.incrementAndGet()
        activeJob.getAndSet(null)?.cancel()
    }

    override fun close() = invalidateSelection()

    private suspend fun runAction(action: NativeHarnessTurnAction, token: Long, capturedIdentity: Any?): NativeHarnessTurnResult {
        val sessionId = action.sessionId()
        if (sessionId.isBlank() || selectedSessionProvider() != sessionId) {
            return NativeHarnessTurnResult.Ignored(SESSION_CHANGED)
        }
        val transport = transportProvider()
            ?: return NativeHarnessTurnResult.Failed(sessionId, "HARNESS_UNAVAILABLE", "Harness runtime is unavailable")
        if (transport.identity !== capturedIdentity) return NativeHarnessTurnResult.Ignored(SESSION_CHANGED)
        val context = ActionContext(sessionId, transport, token)
        return try {
            copyResult(action, context)
        } catch (_: StaleRequest) {
            NativeHarnessTurnResult.Ignored(SESSION_CHANGED)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Transport and shape failures are recoverable UI errors. Keep the raw exception out
            // of the result because a provider/plugin may have echoed private request content.
            NativeHarnessTurnResult.Failed(
                sessionId = sessionId,
                code = "TRANSCRIPT_ACTION_FAILED",
                message = "Transcript action failed",
            )
        }
    }

    private suspend fun copyUserMessage(
        action: NativeHarnessTurnAction.CopyUserMessage,
        context: ActionContext,
    ): NativeHarnessTurnResult {
        if (!validCopyCoordinates(action.turn, action.throughSequence, action.messageSequence)) {
            return failed(action.sessionId, "TRANSCRIPT_COPY_SEQUENCE_INVALID", "The transcript sequence is invalid")
        }
        val address = addressFor(action.sessionId, context) ?: return failed(
            action.sessionId,
            "SUBAGENT_ADDRESS_UNAVAILABLE",
            "The selected session address is not available yet",
        )
        val pages = PageReader(context, address, action.throughSequence)
        var page: PageRead
        while (true) {
            page = pages.read() ?: break
            val target = page.events.firstOrNull { it.sequence == action.messageSequence }
            if (target != null) {
                val turnMismatch = action.turn != null && target.turn != null && target.turn != action.turn
                if (target.type != "user/message" || turnMismatch || !target.isAppend || target.textTooLarge) {
                    if (target.textTooLarge) return failed(action.sessionId, "TRANSCRIPT_COPY_TOO_LARGE", "The transcript is too large to copy")
                    return failed(action.sessionId, "TRANSCRIPT_COPY_TARGET_INVALID", "The selected message is not visible")
                }
                return copied(action, NativeHarnessTurnCopyKind.USER_MESSAGE, listOf(target.text), pages.count)
            }
            if (!page.hasMore) break
        }
        return failed(action.sessionId, "TRANSCRIPT_COPY_TARGET_MISSING", "The selected message is no longer available")
    }

    private suspend fun copyAssistantTurn(
        action: NativeHarnessTurnAction.CopyAssistantTurn,
        context: ActionContext,
    ): NativeHarnessTurnResult {
        if (!validCopyCoordinates(action.turn, action.throughSequence, action.finalAssistantSequence)) {
            return failed(action.sessionId, "TRANSCRIPT_COPY_SEQUENCE_INVALID", "The transcript sequence is invalid")
        }
        val address = addressFor(action.sessionId, context) ?: return failed(
            action.sessionId,
            "SUBAGENT_ADDRESS_UNAVAILABLE",
            "The selected session address is not available yet",
        )
        val pages = PageReader(context, address, action.throughSequence)
        val pageChunks = ArrayDeque<List<String>>()
        var retainedTextBytes = 0
        var retainedTextPieces = 0
        var foundTurnStart = false
        var foundFinal = false
        while (true) {
            val page = pages.read() ?: break
            val chunks = ArrayList<String>()
            for (event in page.events) {
                if (event.type == "turn/start" && event.turn == action.turn) foundTurnStart = true
                if (event.sequence == action.finalAssistantSequence) {
                    if (event.type != "assistant/message" || event.turn != action.turn || !event.isAppend || event.textTooLarge) {
                        if (event.textTooLarge) return failed(action.sessionId, "TRANSCRIPT_COPY_TOO_LARGE", "The transcript is too large to copy")
                        return failed(action.sessionId, "TRANSCRIPT_COPY_TARGET_INVALID", "The selected assistant message is not visible")
                    }
                    if (event.text.isNotEmpty()) {
                        val bytes = utf8BytesWithin(
                            event.text,
                            MAX_COPY_BYTES - retainedTextBytes - if (retainedTextPieces == 0) 0 else 1,
                        ) ?: return failed(action.sessionId, "TRANSCRIPT_COPY_TOO_LARGE", "The transcript is too large to copy")
                        if (retainedTextPieces >= MAX_TEXT_PIECES) {
                            return failed(action.sessionId, "TRANSCRIPT_COPY_TOO_MANY_PIECES", "The transcript has too many text pieces")
                        }
                        retainedTextBytes += bytes + if (retainedTextPieces == 0) 0 else 1
                        retainedTextPieces++
                        chunks += event.text
                    }
                    foundFinal = true
                } else if (action.finalAssistantSequence == null &&
                    event.type == "assistant/message" && event.turn == action.turn && event.isAppend
                ) {
                    if (event.textTooLarge) return failed(action.sessionId, "TRANSCRIPT_COPY_TOO_LARGE", "The transcript is too large to copy")
                    if (event.text.isNotEmpty()) {
                        val bytes = utf8BytesWithin(
                            event.text,
                            MAX_COPY_BYTES - retainedTextBytes - if (retainedTextPieces == 0) 0 else 1,
                        ) ?: return failed(action.sessionId, "TRANSCRIPT_COPY_TOO_LARGE", "The transcript is too large to copy")
                        if (retainedTextPieces >= MAX_TEXT_PIECES) {
                            return failed(action.sessionId, "TRANSCRIPT_COPY_TOO_MANY_PIECES", "The transcript has too many text pieces")
                        }
                        retainedTextBytes += bytes + if (retainedTextPieces == 0) 0 else 1
                        retainedTextPieces++
                        chunks += event.text
                    }
                }
            }
            if (chunks.isNotEmpty()) {
                if (pageChunks.size >= MAX_TEXT_PAGES) {
                    return failed(action.sessionId, "TRANSCRIPT_COPY_TOO_MANY_PIECES", "The transcript has too many text pieces")
                }
                pageChunks.addFirst(chunks)
            }
            if (foundFinal || (action.finalAssistantSequence == null && foundTurnStart)) break
            if (!page.hasMore) break
        }
        if (!foundFinal && action.finalAssistantSequence != null) {
            return failed(action.sessionId, "TRANSCRIPT_COPY_TARGET_MISSING", "The selected assistant message is no longer available")
        }
        if (action.finalAssistantSequence == null && !foundTurnStart) {
            return failed(action.sessionId, "TRANSCRIPT_COPY_TURN_INCOMPLETE", "The selected turn start is not available")
        }
        val chunks = pageChunks.flatMap { it }
        return copied(action, NativeHarnessTurnCopyKind.ASSISTANT_TURN, chunks, pages.count)
    }

    private suspend fun forkAt(
        action: NativeHarnessTurnAction.ForkAt,
        context: ActionContext,
    ): NativeHarnessTurnResult {
        if (!validSequence(action.assistantSequence)) {
            return failed(action.sessionId, "TRANSCRIPT_FORK_SEQUENCE_INVALID", "The branch sequence is invalid")
        }
        if (addressFor(action.sessionId, context) == null) return failed(
            action.sessionId,
            "SUBAGENT_ADDRESS_UNAVAILABLE",
            "The selected session address is not available yet",
        )
        ensureCurrent(context)
        val callArgs = buildNativeHarnessForkCallArgs(action.sessionId, action.assistantSequence)
        return when (val result = context.transport.forkSession(callArgs)) {
            is HarnessRpcResult.Failure -> {
                ensureCurrent(context)
                failed(action.sessionId, result.error.code, result.error.message)
            }
            is HarnessRpcResult.Success -> {
                ensureCurrent(context)
                val childId = result.value.string("sessionId")?.takeIf { it.isNotBlank() }
                    ?: return failed(action.sessionId, "SESSION_ID_MISSING", "Harness did not return the forked session identity")
                NativeHarnessTurnResult.Forked(action.sessionId, action.assistantSequence, childId)
            }
        }
    }

    private suspend fun addressFor(sessionId: String, context: ActionContext): JsonObject? {
        ensureCurrent(context)
        val address = sessionAddressProvider(sessionId)
        ensureCurrent(context)
        return address
    }

    private suspend fun copied(
        action: NativeHarnessTurnAction,
        kind: NativeHarnessTurnCopyKind,
        chunks: List<String>,
        pageCount: Int,
    ): NativeHarnessTurnResult {
        if (chunks.isEmpty()) return failed(action.sessionId(), "TRANSCRIPT_COPY_EMPTY", "The selected transcript has no copyable text")
        val output = StringBuilder()
        var bytes = 0
        for (chunk in chunks) {
            if (chunk.isEmpty()) continue
            if (output.isNotEmpty()) {
                val separatorBytes = utf8BytesWithin("\n", MAX_COPY_BYTES - bytes)
                    ?: return failed(action.sessionId(), "TRANSCRIPT_COPY_TOO_LARGE", "The transcript is too large to copy")
                output.append('\n')
                bytes += separatorBytes
            }
            val chunkBytes = utf8BytesWithin(chunk, MAX_COPY_BYTES - bytes)
                ?: return failed(action.sessionId(), "TRANSCRIPT_COPY_TOO_LARGE", "The transcript is too large to copy")
            output.append(chunk)
            bytes += chunkBytes
        }
        if (output.isEmpty()) return failed(action.sessionId(), "TRANSCRIPT_COPY_EMPTY", "The selected transcript has no copyable text")
        return when (action) {
            is NativeHarnessTurnAction.CopyUserMessage -> NativeHarnessTurnResult.Copied(
                action.sessionId, action.turn, kind, output.toString(), pageCount,
            )
            is NativeHarnessTurnAction.CopyAssistantTurn -> NativeHarnessTurnResult.Copied(
                action.sessionId, action.turn, kind, output.toString(), pageCount,
            )
            is NativeHarnessTurnAction.ForkAt -> error("fork cannot produce copied text")
        }
    }

    private fun failed(sessionId: String, code: String, message: String): NativeHarnessTurnResult.Failed =
        NativeHarnessTurnResult.Failed(sessionId, code, message)

    private fun ensureCurrent(context: ActionContext) {
        if (!isCurrent(context.sessionId, context.token, context.transport)) throw StaleRequest()
    }

    private fun isCurrent(sessionId: String, token: Long, transport: NativeHarnessTurnTransport?): Boolean =
        transport != null && generation.get() == token && selectedSessionProvider() == sessionId &&
            transportProvider()?.identity === transport.identity

    private fun isCurrent(action: NativeHarnessTurnAction, token: Long, identity: Any?): Boolean =
        generation.get() == token && selectedSessionProvider() == action.sessionId() &&
            transportProvider()?.identity === identity

    private data class ActionContext(
        val sessionId: String,
        val transport: NativeHarnessTurnTransport,
        val token: Long,
    )

    private class StaleRequest : RuntimeException()

    private inner class PageReader(
        private val context: ActionContext,
        private val address: JsonObject,
        private val throughSequence: Long,
    ) {
        private var beforeSequence: Long? = null
        private var finished = false
        var count: Int = 0
            private set

        suspend fun read(): PageRead? {
            if (finished) return null
            if (count >= MAX_PAGES) throw CopyFailure("TRANSCRIPT_COPY_PAGE_LIMIT", "The transcript page limit was reached")
            if (count > 0 && beforeSequence == null) throw CopyFailure("TRANSCRIPT_COPY_CURSOR_STALLED", "The transcript cursor did not advance")
            if (count > 0 && beforeSequence!! < 0) throw CopyFailure("TRANSCRIPT_COPY_CURSOR_STALLED", "The transcript cursor is invalid")
            if (generationInvalid()) throw StaleRequest()
            val request = buildJsonObject {
                put("address", address)
                put("throughSeq", throughSequence)
                beforeSequence?.let { put("beforeSeq", it) }
                put("maxMessages", PAGE_MESSAGES)
            }
            val result = context.transport.pageSession(request)
            ensureLive()
            count++
            when (result) {
                is HarnessRpcResult.Failure -> throw CopyFailure(result.error.code, result.error.message)
                is HarnessRpcResult.Success -> {
                    val records = result.value.objectArray("records")
                    if (records.size > MAX_PAGE_RECORDS) throw CopyFailure(
                        "TRANSCRIPT_COPY_PAGE_TOO_LARGE",
                        "The transcript page contains too many records",
                    )
                    val events = records.mapNotNull(::parseTurnEvent).sortedBy { it.sequence }
                    val hasMore = result.value.boolean("hasMore") == true
                    if (events.isEmpty() && hasMore) throw CopyFailure(
                        "TRANSCRIPT_COPY_CURSOR_STALLED",
                        "The transcript returned an empty page with more history",
                    )
                    if (!hasMore) finished = true
                    if (hasMore) {
                        val minimum = events.minOfOrNull { it.sequence }
                            ?: throw CopyFailure("TRANSCRIPT_COPY_CURSOR_STALLED", "The transcript cursor did not advance")
                        if (beforeSequence != null && minimum >= beforeSequence!!) throw CopyFailure(
                            "TRANSCRIPT_COPY_CURSOR_STALLED",
                            "The transcript cursor did not advance",
                        )
                        beforeSequence = minimum
                    }
                    return PageRead(events, hasMore)
                }
            }
        }

        private fun generationInvalid(): Boolean =
            generation.get() != context.token || selectedSessionProvider() != context.sessionId ||
                transportProvider()?.identity !== context.transport.identity

        private fun ensureLive() {
            if (generationInvalid()) throw StaleRequest()
        }
    }

    private data class PageRead(val events: List<ParsedTurnEvent>, val hasMore: Boolean)

    private class CopyFailure(val code: String, val reason: String) : RuntimeException()

    private suspend fun copyResult(action: NativeHarnessTurnAction, context: ActionContext): NativeHarnessTurnResult =
        try {
            when (action) {
                is NativeHarnessTurnAction.CopyUserMessage -> copyUserMessage(action, context)
                is NativeHarnessTurnAction.CopyAssistantTurn -> copyAssistantTurn(action, context)
                is NativeHarnessTurnAction.ForkAt -> forkAt(action, context)
            }
        } catch (error: CopyFailure) {
            failed(action.sessionId(), error.code, error.reason)
        }

    private fun validCopyCoordinates(turn: Long?, through: Long, target: Long?): Boolean =
        (turn == null || validSequence(turn)) && validSequence(through) && (target == null || validSequence(target))

    private fun validSequence(sequence: Long): Boolean = sequence in 0..MAX_SAFE_SEQUENCE

    private fun NativeHarnessTurnAction.sessionId(): String = when (this) {
        is NativeHarnessTurnAction.CopyUserMessage -> sessionId
        is NativeHarnessTurnAction.CopyAssistantTurn -> sessionId
        is NativeHarnessTurnAction.ForkAt -> sessionId
    }

    private companion object {
        const val PAGE_MESSAGES = 32
        const val MAX_PAGE_RECORDS = 512
        const val MAX_PAGES = 1_024
        const val MAX_TEXT_PAGES = 4_096
        const val MAX_TEXT_PIECES = 4_096
        const val MAX_SAFE_SEQUENCE = 9_007_199_254_740_991L
        const val SESSION_CHANGED = "TRANSCRIPT_ACTION_STALE"
    }
}

/** Exact pinned `session/fork` call envelope; no UI synthetic sequence or title option is sent. */
internal fun buildNativeHarnessForkCallArgs(sessionId: String, assistantSequence: Long): JsonObject = buildJsonObject {
    putJsonObject("request") {
        put("sessionId", sessionId)
        put("atSeq", assistantSequence)
    }
}

private data class ParsedText(val value: String, val tooLarge: Boolean)

private data class ParsedTurnEvent(
    val type: String,
    val sequence: Long,
    val turn: Long?,
    val isAppend: Boolean,
    val text: String,
    val textTooLarge: Boolean,
)

private fun parseTurnEvent(record: JsonObject): ParsedTurnEvent? {
    val event = record.objectValue("event") ?: record
    val type = event.string("type") ?: return null
    val sequence = event.long("seq") ?: record.long("seq") ?: return null
    val data = event.objectValue("data")
    val turn = data?.long("turn")
    val isAppend = event.string("surfaceOp") == "append"
    val text = if (type == "assistant/message" || type == "user/message") {
        val message = data?.objectValue("message") ?: data
        extractText(message?.get("content") ?: data?.get("content"))
    } else {
        ParsedText("", false)
    }
    return ParsedTurnEvent(type, sequence, turn, isAppend, text.value, text.tooLarge)
}

private fun extractText(value: JsonElement?): ParsedText {
    if (value == null) return ParsedText("", false)
    if (value is JsonPrimitive) {
        val text = value.contentOrNull.orEmpty()
        return ParsedText(text.take(MAX_COPY_BYTES), utf8BytesWithin(text, MAX_COPY_BYTES) == null)
    }
    val blocks = value as? JsonArray ?: return ParsedText("", false)
    val output = StringBuilder()
    var bytes = 0
    for (block in blocks) {
        val objectBlock = block as? JsonObject ?: continue
        if (objectBlock.string("type") != "text") continue
        val text = objectBlock.string("text").orEmpty()
        val added = utf8BytesWithin(text, MAX_COPY_BYTES - bytes)
            ?: return ParsedText(output.toString(), true)
        output.append(text)
        bytes += added
    }
    return ParsedText(output.toString(), false)
}

private fun utf8BytesWithin(value: String, limit: Int): Int? {
    if (limit < 0) return null
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        bytes += when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }
        if (bytes > limit) return null
        index += Character.charCount(codePoint)
    }
    return bytes
}
