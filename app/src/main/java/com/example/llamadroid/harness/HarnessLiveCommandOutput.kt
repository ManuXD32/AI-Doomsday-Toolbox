package com.example.llamadroid.harness

import org.json.JSONObject

/** Transient output from the authenticated host bridge; never written to diagnostics. */
data class HarnessLiveCommandOutput(
    val sessionId: String,
    val callId: String,
    val stream: String,
    val offset: Long,
    val text: String,
)

private const val MAX_COMMAND_OUTPUT_CHUNK_BYTES = 2 * 1024
private const val MAX_SAFE_JS_INTEGER = 9_007_199_254_740_991L

internal fun parseHarnessLiveCommandOutput(sessionId: String?, args: JSONObject): HarnessLiveCommandOutput {
    val session = requireNotNull(sessionId?.takeIf { it.isNotBlank() && it.length <= 256 }) {
        "SESSION_REQUIRED"
    }
    val callId = args.getString("callId")
    require(callId.isNotBlank() && callId.length <= 256) { "COMMAND_CALL_ID_INVALID" }
    val stream = args.getString("stream")
    require(stream == "stdout" || stream == "stderr") { "COMMAND_STREAM_INVALID" }
    val offset = args.getLong("offset")
    require(offset in 0..MAX_SAFE_JS_INTEGER) { "COMMAND_OFFSET_INVALID" }
    val output = args.getString("text")
    require(output.isNotEmpty() && output.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_OUTPUT_CHUNK_BYTES) {
        "COMMAND_OUTPUT_LIMIT"
    }
    return HarnessLiveCommandOutput(session, callId, stream, offset, output)
}
