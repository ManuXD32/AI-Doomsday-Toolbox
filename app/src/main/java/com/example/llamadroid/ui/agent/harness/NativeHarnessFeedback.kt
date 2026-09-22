package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Native adapter for the pinned `messageFeedback` Remote. The official web
 * plugin uses the same list/put/delete compare-and-set protocol; this helper
 * keeps versions and the nested business result out of Compose and the main
 * controller.
 */
internal class NativeHarnessFeedbackActions(
    private val clientProvider: () -> HarnessClient?,
    private val selectedSessionProvider: () -> String?,
    private val stateProvider: () -> NativeHarnessUiState,
    private val mutate: suspend ((NativeHarnessUiState) -> NativeHarnessUiState) -> Unit,
    private val reportFailure: suspend (String, String) -> Unit,
) {
    suspend fun refresh(report: Boolean): Boolean {
        val sessionId = selectedSessionProvider()?.takeIf { it.isNotBlank() }
        if (sessionId == null) {
            mutate { it.copy(messageFeedback = emptyMap(), messageFeedbackLoaded = false) }
            return false
        }
        val client = clientProvider()
        if (client == null) {
            if (report) reportFailure("FEEDBACK_RPC_FAILED", "Message feedback is unavailable")
            return false
        }
        return try {
            when (val result = client.call(
                "messageFeedback",
                "list",
                request(sessionId),
                HarnessCallPolicy.SafeRead
            )) {
                is HarnessRpcResult.Failure -> {
                    if (report) reportFailure("FEEDBACK_RPC_FAILED", "Message feedback is unavailable")
                    false
                }
                is HarnessRpcResult.Success -> {
                    val rows = acceptedValue(result.value)?.objectArray("items")
                    if (rows == null) {
                        if (report) reportFailure("FEEDBACK_INVALID_RESULT", "Harness returned invalid feedback")
                        false
                    } else {
                        val items = rows.mapNotNull(::parseItem).associateBy { it.first }
                        mutate { current ->
                            current.copy(
                                messageFeedback = items.mapValues { it.value.second },
                                messageFeedbackLoaded = true
                            )
                        }
                        true
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (report) reportFailure("FEEDBACK_RPC_FAILED", "Message feedback is unavailable")
            false
        }
    }

    suspend fun submit(
        messageId: String,
        rating: HarnessFeedbackRating,
        note: String?,
        category: String?,
    ): Boolean {
        val sessionId = selectedSessionProvider()?.takeIf { it.isNotBlank() }
            ?: return fail("FEEDBACK_SESSION_MISSING", "Select a Harness session before rating a message")
        val client = clientProvider()
            ?: return fail("FEEDBACK_RPC_FAILED", "Message feedback is unavailable")
        val current = stateProvider().messageFeedback[messageId]
        val args = request(sessionId) {
            put("messageId", messageId)
            put("rating", rating.wireValue)
            if (!note.isNullOrBlank()) put("note", note)
            if (!category.isNullOrBlank()) put("category", category)
            if (current == null) put("ifVersion", JsonNull)
            else put("ifVersion", JsonPrimitive(current.version))
        }
        return try {
            when (val result = client.call("messageFeedback", "put", args, HarnessCallPolicy.NoRetry)) {
                is HarnessRpcResult.Failure -> fail("FEEDBACK_RPC_FAILED", "Message feedback is unavailable")
                is HarnessRpcResult.Success -> {
                    val item = acceptedValue(result.value)?.let(::parseItem)?.second
                    if (item == null) {
                        val code = rejectedCode(result.value)
                        fail(
                            if (code == "version-conflict") "FEEDBACK_CONFLICT" else "FEEDBACK_REJECTED",
                            "Harness rejected message feedback"
                        )
                    } else {
                        mutate { it.copy(messageFeedback = it.messageFeedback + (messageId to item)) }
                        true
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fail("FEEDBACK_RPC_FAILED", "Message feedback is unavailable")
        }
    }

    suspend fun retract(messageId: String, rating: HarnessFeedbackRating): Boolean {
        val sessionId = selectedSessionProvider()?.takeIf { it.isNotBlank() }
            ?: return fail("FEEDBACK_SESSION_MISSING", "Select a Harness session before changing feedback")
        val client = clientProvider()
            ?: return fail("FEEDBACK_RPC_FAILED", "Message feedback is unavailable")
        val current = stateProvider().messageFeedback[messageId]
        if (current?.rating != rating || current.version.isBlank()) return true
        return try {
            val result = client.call(
                "messageFeedback",
                "delete",
                request(sessionId) {
                    put("messageId", messageId)
                    put("ifVersion", current.version)
                },
                HarnessCallPolicy.NoRetry
            )
            when (result) {
                is HarnessRpcResult.Failure -> fail("FEEDBACK_RPC_FAILED", "Message feedback is unavailable")
                is HarnessRpcResult.Success -> if (acceptedValue(result.value) != null) {
                    mutate { it.copy(messageFeedback = it.messageFeedback - messageId) }
                    true
                } else {
                    val code = rejectedCode(result.value)
                    fail(
                        if (code == "version-conflict") "FEEDBACK_CONFLICT" else "FEEDBACK_REJECTED",
                        "Harness rejected removing message feedback"
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fail("FEEDBACK_RPC_FAILED", "Message feedback is unavailable")
        }
    }

    private suspend fun fail(code: String, message: String): Boolean {
        reportFailure(code, message)
        return false
    }

    private fun request(sessionId: String, fill: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            putJsonObject("request") {
                put("sessionId", sessionId)
                fill()
            }
        }

    private fun acceptedValue(value: JsonElement): JsonObject? {
        val outer = value.jsonObjectOrNull() ?: return null
        if (outer.boolean("ok") != true) return null
        return outer.objectValue("value")?.takeIf { it.boolean("ok") == true }
            ?.objectValue("value")
    }

    private fun rejectedCode(value: JsonElement): String? =
        value.jsonObjectOrNull()?.objectValue("value")?.objectValue("error")?.string("code")

    private fun parseItem(row: JsonObject): Pair<String, HarnessMessageFeedbackUi>? {
        val messageId = row.string("messageId")?.takeIf { it.isNotBlank() } ?: return null
        val rating = when (row.string("rating")) {
            "positive" -> HarnessFeedbackRating.POSITIVE
            "negative" -> HarnessFeedbackRating.NEGATIVE
            else -> return null
        }
        val version = row.string("version")?.takeIf { it.isNotBlank() } ?: return null
        return messageId to HarnessMessageFeedbackUi(
            rating = rating,
            version = version,
            note = row.string("note"),
            category = row.string("category")
        )
    }

}
