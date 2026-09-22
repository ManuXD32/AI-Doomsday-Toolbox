package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.R
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal data class NativeHarnessCommandReply(val accepted: Boolean, val text: String? = null, val code: String? = null)

/** Skills and literal slash text stay prompts; only registered human commands execute here. */
internal fun isNativeHarnessRegisteredCommand(text: String, commands: List<HarnessCommandUi>): Boolean {
    val token = text.trimStart().takeWhile { !it.isWhitespace() }
    return token.startsWith('/') && commands.any { it.name == token }
}

/** Alpha2 returns a command execution envelope; transport success is not command success. */
internal suspend fun executeNativeHarnessCommand(
    client: HarnessClient,
    sessionId: String,
    line: String,
    attachments: List<HarnessAttachmentUi>
): NativeHarnessCommandReply {
    val reply = client.call("commands", "execute", buildJsonObject {
        put("agentId", sessionId)
        put("line", line)
        putJsonArray("submittedAttachments") {
            attachments.forEach { attachment ->
                add(buildJsonObject { put("type", "file"); put("receiptId", attachment.id) })
            }
        }
    }, HarnessCallPolicy.NoRetry)
    if (reply is HarnessRpcResult.Failure) return NativeHarnessCommandReply(false, reply.error.message.take(4000), reply.error.code)
    val execution = (reply as HarnessRpcResult.Success).value as? JsonObject
    val result = execution?.get("result") as? JsonObject
    val kind = runCatching { result?.get("kind")?.jsonPrimitive?.contentOrNull }.getOrNull()
    val text = runCatching { result?.get("text")?.jsonPrimitive?.contentOrNull }.getOrNull()?.take(4000)
    return when (kind) {
        "success" -> NativeHarnessCommandReply(true, text)
        "error" -> NativeHarnessCommandReply(false, text, "COMMAND_REJECTED")
        else -> NativeHarnessCommandReply(false, code = "COMMAND_UNKNOWN")
    }
}

/** Preserve edits and newly attached files made while the original submission was in flight. */
internal fun applyNativeHarnessCommandReply(
    current: NativeHarnessUiState,
    submitted: NativeHarnessUiState,
    reply: NativeHarnessCommandReply
): NativeHarnessUiState {
    if (current.selectedSessionId != submitted.selectedSessionId || !reply.accepted) return current
    val consumed = submitted.attachments.mapTo(mutableSetOf()) { it.id }
    return current.copy(
        commandLine = if (current.commandLine == submitted.commandLine) "" else current.commandLine,
        attachments = current.attachments.filterNot { it.id in consumed },
        notice = reply.text?.takeIf { it.isNotBlank() }?.let {
            HarnessNoticeUi(titleRes = R.string.harness_command_result, message = it, recoverable = false)
        }
    )
}
