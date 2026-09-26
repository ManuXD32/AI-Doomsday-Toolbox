package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeHarnessCommandsTest {
    @Test fun composerExecutesOnlyExactRegisteredCommandsAndKeepsSkillsAsPrompts() {
        val commands = listOf(HarnessCommandUi("/compact"), HarnessCommandUi("/feedback"))
        assertTrue(isNativeHarnessRegisteredCommand("/compact", commands))
        assertTrue(isNativeHarnessRegisteredCommand(" /feedback this helps", commands))
        assertFalse(isNativeHarnessRegisteredCommand("/compact-file", commands))
        assertFalse(isNativeHarnessRegisteredCommand("/installed-skill write a file", commands))
        assertFalse(isNativeHarnessRegisteredCommand("Explain /compact", commands))
    }
    @Test fun attachmentsUseTheOfficialAgentScopedCommandEnvelopeAndHandlerErrorsStayRejected() = runBlocking {
        val client = CommandClient("""{"commandId":"cmd-1","result":{"kind":"error","text":"Rejected attachment"}}""")
        val reply = executeNativeHarnessCommand(client, "session-captured", "/goal test", listOf(attachment("receipt-1")))
        assertFalse(reply.accepted)
        assertEquals("COMMAND_REJECTED", reply.code)
        assertEquals("Rejected attachment", reply.text)
        assertEquals("commands/execute", client.method)
        assertEquals("session-captured", client.args.getValue("agentId").jsonPrimitive.content)
        assertFalse(client.args.containsKey("agent"))
        assertEquals("receipt-1", client.args.getValue("submittedAttachments").jsonArray.single().jsonObject.getValue("receiptId").jsonPrimitive.content)
        assertEquals(HarnessCallPolicy.NoRetry, client.policy)
    }

    @Test fun undefinedAndMalformedCommandsNeverConsumeTheDraft() = runBlocking {
        for (value in listOf("null", "{}", """{"result":{"kind":"new-unknown-kind"}}""")) {
            val reply = executeNativeHarnessCommand(CommandClient(value), "s", "/missing", emptyList())
            assertFalse(reply.accepted)
            assertEquals("COMMAND_UNKNOWN", reply.code)
        }
    }

    @Test fun acceptedCommandOnlyConsumesItsCapturedAttachmentsAndUnchangedDraft() {
        val submitted = NativeHarnessUiState(selectedSessionId = "s", commandLine = "/goal first", attachments = listOf(attachment("a")))
        val edited = submitted.copy(commandLine = "/goal second", attachments = submitted.attachments + attachment("b"))
        val result = applyNativeHarnessCommandReply(edited, submitted, NativeHarnessCommandReply(true, "Done"))
        assertEquals("/goal second", result.commandLine)
        assertEquals(listOf("b"), result.attachments.map { it.id })
        assertEquals("Done", result.notice?.message)
        assertEquals("", applyNativeHarnessCommandReply(submitted, submitted, NativeHarnessCommandReply(true)).commandLine)
        assertEquals(submitted, applyNativeHarnessCommandReply(submitted, submitted, NativeHarnessCommandReply(false)))
        val differentSession = submitted.copy(selectedSessionId = "other")
        assertEquals(differentSession, applyNativeHarnessCommandReply(differentSession, submitted, NativeHarnessCommandReply(true)))
    }

    private fun attachment(id: String) = HarnessAttachmentUi(id, "$id.txt", "text/plain")

    private class CommandClient(private val result: String) : HarnessClient {
        override val state = MutableStateFlow(HarnessConnectionState.READY)
        var method = ""
        var args = buildJsonObject {}
        var policy = HarnessCallPolicy.SafeRead
        override suspend fun authenticate(launchUrl: String) = HarnessAuthResult.Success("http://127.0.0.1:1")
        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String) = HarnessAuthResult.Success(origin)
        override fun close() = Unit
        override fun stream(namespace: String, method: String, args: JsonObject, policy: HarnessStreamPolicy) = emptyFlow<JsonElement>()
        override suspend fun call(namespace: String, method: String, args: JsonObject, policy: HarnessCallPolicy, requestId: String): HarnessRpcResult {
            this.method = "$namespace/$method"; this.args = args; this.policy = policy
            return HarnessRpcResult.Success(Json.parseToJsonElement(result))
        }
    }
}
