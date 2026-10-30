package com.example.llamadroid.service.delegation

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcError
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.ui.agent.harness.jsonObjectOrNull
import com.example.llamadroid.ui.agent.harness.long
import com.example.llamadroid.ui.agent.harness.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `session/page` requires `throughSeq` (a safe integer >= -1) that is NOT past the session cursor
 * (the last event sequence); otherwise it rejects with `gateway/bad-request "session page through
 * seq X is past cursor Y"`. `throughSeq = -1` returns an empty page.
 *
 * Resolution order: the known sequence, then a probe with [MAX_SAFE_SEQ] whose rejection message
 * carries the real cursor, then (last resort, because following a cold session activates its agent)
 * the opening `snapshot` frame of a one-shot `session/follow`. Shared by the subagent's transcript
 * reader and the viewer's thread preview.
 */
internal object HarnessLatestPage {
    /** 2^53 - 1: the largest sequence a JavaScript runtime parses without losing precision. */
    const val MAX_SAFE_SEQ = 9_007_199_254_740_991L

    private const val FOLLOW_TIMEOUT_MS = 15_000L
    private val PAST_CURSOR = Regex("""past cursor (-?\d+)""")

    /** Ordered `throughSeq` values to try: the known latest sequence first, then the max. */
    fun throughCandidates(knownSeq: Long?): List<Long> =
        listOfNotNull(knownSeq?.takeIf { it >= 0L }, MAX_SAFE_SEQ).distinct()

    /** The cursor named by a "... is past cursor N" rejection, or null when the message has none. */
    fun parsePastCursor(message: String?): Long? =
        message?.let { PAST_CURSOR.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** True when [message] is the "past cursor" rejection (a probe result, not a real failure). */
    fun isPastCursor(message: String?): Boolean = parsePastCursor(message) != null

    /** An empty, successful page (what the runtime returns for `throughSeq = -1`). */
    fun emptyPage(): HarnessRpcResult.Success = HarnessRpcResult.Success(buildJsonObject {
        put("records", JsonArray(emptyList()))
        put("hasMore", false)
    })

    /** Pages [address] newest-first, resolving the session cursor as described on the object. */
    suspend fun page(
        client: HarnessClient,
        address: JsonObject,
        knownSeq: Long?,
        beforeSeq: Long?,
        maxMessages: Int,
    ): HarnessRpcResult {
        var firstReal: HarnessRpcResult.Failure? = null
        var cursor: Long? = null

        suspend fun through(seq: Long): HarnessRpcResult = client.pageSession(buildJsonObject {
            put("address", address)
            put("throughSeq", seq)
            beforeSeq?.let { put("beforeSeq", it) }
            put("maxMessages", maxMessages)
        })

        fun note(result: HarnessRpcResult): Boolean {
            val failure = result as? HarnessRpcResult.Failure ?: return false
            val past = parsePastCursor(failure.error.message)
            if (past != null) cursor = past else if (firstReal == null) firstReal = failure
            return true
        }

        val known = knownSeq?.takeIf { it >= 0L }
        if (known != null) {
            val result = through(known)
            if (!note(result)) return result
        }
        if (cursor == null) {
            val probe = through(MAX_SAFE_SEQ)
            if (!note(probe)) return probe
        }
        cursor?.let { found ->
            if (found < 0L) return emptyPage()
            val retry = through(found)
            if (!note(retry)) return retry
        }
        // Last resort: the follow snapshot gives the cursor and the newest records.
        val snapshot = followSnapshot(client, address, maxMessages)
        if (snapshot != null) {
            val snapCursor = snapshot.long("cursor")
            if (snapCursor != null) {
                if (snapCursor < 0L) return emptyPage()
                if (beforeSeq == null) {
                    return HarnessRpcResult.Success(buildJsonObject {
                        put("records", snapshot["records"] as? JsonArray ?: JsonArray(emptyList()))
                        put("hasMore", (snapshot["hasMore"] as? JsonPrimitive)?.content == "true")
                    })
                }
                val retry = through(snapCursor)
                if (!note(retry)) return retry
            }
        }
        return firstReal ?: HarnessRpcResult.Failure(
            HarnessRpcError("session/page_unavailable", "The session cursor could not be determined.")
        )
    }

    /** First `snapshot` frame of a one-shot `session/follow`, or null on timeout/failure. */
    private suspend fun followSnapshot(client: HarnessClient, address: JsonObject, maxMessages: Int): JsonObject? = try {
        withTimeoutOrNull(FOLLOW_TIMEOUT_MS) {
            client.followSession(buildJsonObject {
                put("address", address)
                // The snapshot's records are returned as the newest page, so ask for the same size.
                put("maxMessages", maxMessages.coerceAtLeast(1))
                put("assistantStream", false)
            }).first { it.isSnapshot() }.jsonObjectOrNull()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun JsonElement.isSnapshot(): Boolean = jsonObjectOrNull()?.string("type") == "snapshot"
}
