package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessQueueStoreTest {
    @Test
    fun onlyNextTurnRowsAreDisplayedAndAttachmentsRemainReadable() {
        val store = NativeHarnessQueueStore()
        assertTrue(store.applyProjection("s", 4, "inbox", inboxWithNextStep(
            nextTurn = listOf(
                row("turn", text("later")),
                row("file-row", file("file-1", "notes.txt", 12L)),
            ),
            nextStep = listOf(row("step", text("now"))),
        )))

        assertEquals(listOf("turn", "file-row"), store.items("s").map { it.id })
        val attachment = store.items("s").last().attachments.single()
        assertEquals("file-1", attachment.id)
        assertEquals("notes.txt", attachment.name)
        assertTrue(attachment.canOpen)
        assertFalse(store.items("s").last().canEdit)
    }

    @Test
    fun delayedLowerBaselineCannotOverwriteNewerListSnapshot() {
        val store = NativeHarnessQueueStore()
        assertTrue(store.applyListSnapshot("s", buildJsonObject {
            put("sessionId", "s")
            putJsonObject("projections") {
                put("asOfSeq", 21)
                putJsonObject("values") { put("inbox", inbox(row("new", text("new")))) }
            }
        }))
        assertFalse(store.applyBaseline("s", 20, buildJsonObject {
            put("inbox", inbox(row("old", text("old"))))
        }))
        assertEquals(listOf("new"), store.items("s").map { it.id })
    }

    @Test
    fun lowerSequenceIsIgnoredAndNewClientGenerationStartsFresh() {
        val store = NativeHarnessQueueStore()
        assertTrue(store.applyProjection("s", 9, "inbox", inbox(row("latest", text("latest")))))
        assertFalse(store.applyProjection("s", 8, "inbox", inbox(row("old", text("old")))))
        assertEquals(listOf("latest"), store.items("s").map { it.id })

        store.resetClient()
        assertTrue(store.applyProjection("s", 1, "inbox", inbox(row("fresh", text("fresh")))))
        assertEquals(listOf("fresh"), store.items("s").map { it.id })
    }

    @Test
    fun textEditIsBoundedAndNonTextRowsCannotBeEdited() {
        val longText = "x".repeat(MAX_HARNESS_QUEUE_PREVIEW_CHARS + 40)
        val store = NativeHarnessQueueStore()
        assertTrue(store.applyProjection("s", 1, "inbox", inbox(row("long", text(longText)))))
        val long = store.items("s").single()
        assertTrue(long.textTruncated)
        assertEquals(longText, long.editText)
        assertTrue(long.canEdit)

        assertTrue(store.applyProjection("s", 2, "inbox", inbox(row("attachment", file("a", "a.txt", 1L)))))
        assertFalse(store.items("s").single().canEdit)
    }

    @Test
    fun queueActionsUseOfficialEnvelopeAndDoNotApplyOptimisticRemoval() = runBlocking {
        val client = RecordingQueueClient()
        val failures = mutableListOf<String>()
        val actions = NativeHarnessQueueActions(
            clientProvider = { client },
            selectedSessionProvider = { "session-1" },
            reportFailure = { code, _ -> failures += code },
        )

        actions.edit("item-1", "updated")
        actions.remove("item-1")
        actions.steer("item-1")

        assertEquals(listOf("edit", "remove", "steer"), client.actions)
        assertEquals("session-1", client.lastRequest["sessionId"]?.toString()?.trim('"'))
        assertEquals("item-1", client.lastRequest["itemId"]?.toString()?.trim('"'))
        assertEquals(HarnessCallPolicy.NoRetry, client.lastPolicy)
        assertTrue(failures.isEmpty())
    }

    private fun text(value: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

    private fun file(id: String, name: String, bytes: Long): JsonObject = buildJsonObject {
        put("type", "file")
        putJsonObject("attachment") {
            put("attachmentId", id)
            put("name", name)
            put("mediaType", "text/plain")
            put("bytes", bytes)
        }
    }

    private fun row(id: String, vararg content: JsonObject): JsonObject = buildJsonObject {
        put("id", id)
        putJsonArray("content") { content.forEach(::add) }
    }

    private fun inbox(vararg rows: JsonObject): JsonObject = inboxWithNextStep(
        nextTurn = rows.toList(),
        nextStep = emptyList(),
    )

    private fun inboxWithNextStep(
        nextTurn: List<JsonObject>,
        nextStep: List<JsonObject>,
    ): JsonObject = buildJsonObject {
        putJsonArray("next-turn") { nextTurn.forEach(::add) }
        putJsonArray("next-step") { nextStep.forEach(::add) }
    }

    private class RecordingQueueClient : HarnessClient {
        override val state = MutableStateFlow(HarnessConnectionState.READY)
        val actions = mutableListOf<String>()
        var lastRequest = buildJsonObject {}
        var lastPolicy = HarnessCallPolicy.SafeRead

        override suspend fun authenticate(launchUrl: String): HarnessAuthResult =
            HarnessAuthResult.Success("http://127.0.0.1")

        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult =
            HarnessAuthResult.Success(origin)

        override fun close() = Unit

        override fun stream(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessStreamPolicy,
        ) = emptyFlow<JsonElement>()

        override suspend fun call(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessCallPolicy,
            requestId: String,
        ): HarnessRpcResult {
            assertEquals("session", namespace)
            assertEquals("updateQueue", method)
            lastRequest = args["request"]!!.jsonObject
            lastPolicy = policy
            actions += lastRequest["action"]!!.jsonObject["kind"]!!.toString().trim('"')
            return HarnessRpcResult.Success()
        }
    }
}
