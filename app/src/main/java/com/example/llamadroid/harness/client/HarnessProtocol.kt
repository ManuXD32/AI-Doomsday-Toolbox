package com.example.llamadroid.harness.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Immutable wire constants from DeepSeek Harness alpha2. */
object HarnessWire {
    const val API_CHANNEL = "/api"
    const val REMOTE_MUX_PATH = "/api/remote.mux"
    /** Gateway-owned forwarded Cordis event stream (a bare endpoint). */
    const val REMOTE_EVENT_ENDPOINT = "\$events"
    /** Unary result endpoint paired with [REMOTE_EVENT_ENDPOINT]. */
    const val REMOTE_EVENT_RESULT_ENDPOINT = "\$events/result"
    const val CLIENT_REQUEST = "client-request"
    const val SERVER_RESPONSE = "server-response"
    const val STREAM_OPEN = "open"
    const val STREAM_CANCEL = "cancel"
    const val STREAM_ITEM = "item"
    const val STREAM_ERROR = "error"
    const val STREAM_END = "end"
}

/** Metadata-only lifecycle facts emitted by the native transport. */
enum class HarnessTransportKind {
    RPC,
    WEBSOCKET,
}

/** Lifecycle phase for one RPC attempt or physical WebSocket generation. */
enum class HarnessTransportPhase {
    START,
    SUCCESS,
    FAILURE,
    OPEN,
    CLOSE,
    RECONNECT,
}

/**
 * Safe transport metadata for durable diagnostics. It deliberately has no
 * URL, cookie, request arguments, response value, prompt, or exception text.
 */
data class HarnessTransportEvent(
    val kind: HarnessTransportKind,
    val phase: HarnessTransportPhase,
    val namespace: String? = null,
    val method: String? = null,
    val outcome: String? = null,
    val durationMs: Long? = null,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    val webSocketStatus: Int? = null,
) {
    init {
        require(namespace == null || namespace.length <= 128)
        require(method == null || method.length <= 256)
        require(outcome == null || outcome.length <= 64)
        require(durationMs == null || durationMs >= 0L)
        require(httpStatus == null || httpStatus in 100..599)
        require(errorCode == null || errorCode.length <= 128)
        require(webSocketStatus == null || webSocketStatus in 1000..4999 || webSocketStatus in 100..599)
    }
}

/** One validated `<namespace>/<method>` target below the `/api` channel. */
data class HarnessEndpoint(val namespace: String, val method: String) {
    private val isBareRemoteEvents: Boolean =
        namespace == HarnessWire.REMOTE_EVENT_ENDPOINT && method.isEmpty()
    val endpoint: String = if (isBareRemoteEvents) namespace else "$namespace/$method"

    init {
        require(namespace.matches(SEGMENT_PATTERN)) { "Harness namespace is invalid" }
        require(namespace.length <= 128) { "Harness namespace is too long" }
        if (!isBareRemoteEvents) {
            require(method.split('/').all { it.matches(SEGMENT_PATTERN) }) {
                "Harness method contains an invalid segment"
            }
            require(method.isNotEmpty() && !method.startsWith('/') && !method.endsWith('/')) {
                "Harness method is empty"
            }
            require(method.length <= 256) { "Harness method is too long" }
        }
    }

    companion object {
        private val SEGMENT_PATTERN = Regex("""[A-Za-z0-9_$.-]+""")

        fun parse(endpoint: String): HarnessEndpoint {
            if (endpoint == HarnessWire.REMOTE_EVENT_ENDPOINT) return HarnessEndpoint(endpoint, "")
            val parts = endpoint.split('/')
            require(parts.size >= 2) { "Harness endpoint must contain a namespace and method" }
            return HarnessEndpoint(parts.first(), parts.drop(1).joinToString("/"))
        }
    }
}

data class HarnessRpcError(
    val code: String,
    val message: String,
    val details: JsonObject = buildJsonObject {}
) {
    init {
        require(code.isNotBlank()) { "Harness error code is empty" }
    }
}

sealed interface HarnessRpcResult {
    data class Success(val value: JsonElement = JsonNull) : HarnessRpcResult
    data class Failure(val error: HarnessRpcError) : HarnessRpcResult
}

/** Result of the process-token to browser-cookie exchange. */
sealed interface HarnessAuthResult {
    data class Success(val origin: String) : HarnessAuthResult
    data class Failure(val code: String, val message: String) : HarnessAuthResult
}

enum class HarnessConnectionState {
    DISCONNECTED,
    AUTHENTICATING,
    READY,
    RECONNECTING,
    FAILED,
    CLOSED
}

/** Retry policy is explicit so prompt/install mutations are never replayed accidentally. */
data class HarnessCallPolicy(
    val maxAttempts: Int = 1,
    val initialBackoffMs: Long = 150L,
    val maxBackoffMs: Long = 2_000L,
    val retryHttpStatuses: Set<Int> = setOf(408, 425, 429, 500, 502, 503, 504)
) {
    init {
        require(maxAttempts in 1..5) { "Harness retry attempts are out of range" }
        require(initialBackoffMs in 0..60_000L) { "Harness retry backoff is invalid" }
        require(maxBackoffMs in initialBackoffMs..120_000L) { "Harness retry cap is invalid" }
    }

    companion object {
        val NoRetry = HarnessCallPolicy()
        val SafeRead = HarnessCallPolicy(maxAttempts = 3)
        // The pinned gateway does not promise mutation replay after a lost HTTP response.
        val SafeMutation = NoRetry
    }
}

data class HarnessStreamPolicy(
    val maxReconnects: Int = 2,
    val initialBackoffMs: Long = 200L,
    val maxBackoffMs: Long = 3_000L,
    val deduplicationKey: ((JsonElement) -> String?)? = null
) {
    init {
        require(maxReconnects in 0..5) { "Harness stream reconnects are out of range" }
        require(initialBackoffMs in 0..60_000L) { "Harness stream backoff is invalid" }
        require(maxBackoffMs in initialBackoffMs..120_000L) { "Harness stream retry cap is invalid" }
    }

    companion object {
        val Default = HarnessStreamPolicy()
        val SessionFollow = HarnessStreamPolicy(deduplicationKey = ::durableEventKey)
    }
}

sealed interface HarnessStreamFrame {
    val streamId: String

    data class Item(override val streamId: String, val value: JsonElement) : HarnessStreamFrame
    data class Error(override val streamId: String, val error: HarnessRpcError) : HarnessStreamFrame
    data class End(override val streamId: String) : HarnessStreamFrame
}

internal val HarnessJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
}

internal fun rpcRequestJson(rpcId: String, endpoint: HarnessEndpoint, args: JsonObject): String =
    HarnessJson.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("type", HarnessWire.CLIENT_REQUEST)
            put("rpcId", rpcId)
            put("method", endpoint.endpoint)
            putJsonObject("payload") { put("args", args) }
        }
    )

internal fun streamOpenJson(streamId: String, endpoint: HarnessEndpoint, args: JsonObject): String =
    HarnessJson.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("type", HarnessWire.STREAM_OPEN)
            put("streamId", streamId)
            put("endpoint", endpoint.endpoint)
            put("payload", buildJsonObject { put("args", args) })
        }
    )

internal fun streamCancelJson(streamId: String): String = HarnessJson.encodeToString(
    JsonElement.serializer(),
    buildJsonObject {
        put("type", HarnessWire.STREAM_CANCEL)
        put("streamId", streamId)
    }
)

internal fun parseRpcResponse(text: String, expectedRpcId: String): HarnessRpcResult {
    val root = parseObject(text, "server response")
    require(root.stringValue("type") == HarnessWire.SERVER_RESPONSE) {
        "Harness response has an invalid type"
    }
    require(root.stringValue("rpcId") == expectedRpcId) {
        "Harness response rpcId does not match the request"
    }
    val result = root.objectValue("result")
    return when (result.booleanValue("ok")) {
        true -> HarnessRpcResult.Success(result["value"] ?: JsonNull)
        false -> HarnessRpcResult.Failure(parseRpcError(result.objectValue("error")))
    }
}

internal fun parseStreamFrame(text: String): HarnessStreamFrame {
    val root = parseObject(text, "stream frame")
    val streamId = root.stringValue("streamId")
    return when (root.stringValue("type")) {
        HarnessWire.STREAM_ITEM -> {
            require(root.keys == setOf("type", "streamId") || root.keys == setOf("type", "streamId", "value")) {
                "Harness item frame has unexpected fields"
            }
            HarnessStreamFrame.Item(streamId, root["value"] ?: JsonNull)
        }
        HarnessWire.STREAM_END -> {
            require(root.keys == setOf("type", "streamId")) { "Harness end frame has unexpected fields" }
            HarnessStreamFrame.End(streamId)
        }
        HarnessWire.STREAM_ERROR -> {
            require(root.keys == setOf("type", "streamId", "error")) {
                "Harness error frame has unexpected fields"
            }
            val error = root.objectValue("error")
            require(error.keys == setOf("code", "message", "details")) {
                "Harness stream error has unexpected fields"
            }
            HarnessStreamFrame.Error(streamId, parseRpcError(error))
        }
        else -> error("Harness stream frame type is invalid")
    }
}

private fun parseRpcError(error: JsonObject): HarnessRpcError = HarnessRpcError(
    code = error.stringValue("code"),
    message = error.stringValue("message"),
    details = error.objectValue("details")
)

private fun parseObject(text: String, label: String): JsonObject = try {
    HarnessJson.parseToJsonElement(text).jsonObject
} catch (error: Throwable) {
    throw IllegalArgumentException("Harness $label is not a JSON object", error)
}

private fun JsonObject.stringValue(key: String): String =
    get(key)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: error("Harness JSON field '$key' is missing")

private fun JsonObject.booleanValue(key: String): Boolean =
    get(key)?.jsonPrimitive?.content?.let { value ->
        when (value) {
            "true" -> true
            "false" -> false
            else -> error("Harness JSON field '$key' is not boolean")
        }
    } ?: error("Harness JSON field '$key' is missing")

private fun JsonObject.objectValue(key: String): JsonObject =
    get(key)?.jsonObject ?: error("Harness JSON field '$key' is not an object")

/** Stable replay identity for common session event frames. */
fun durableEventKey(value: JsonElement): String? {
    val objectValue = value as? JsonObject ?: return null
    val type = (objectValue["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    if (type in setOf("baseline", "snapshot", "state")) return null
    // alpha2 session/follow uses { type: "event", event: { seq, ... } }.
    // Never derive identity from event/entity IDs that can be reused by a
    // later update; alpha2's durable sequence is nested under event.
    val nestedEvent = objectValue["event"] as? JsonObject
    val seq = (nestedEvent?.get("seq") as? kotlinx.serialization.json.JsonPrimitive)?.content
    if (!seq.isNullOrBlank()) return "seq:$seq"
    // Entity IDs are reused by updates and deltas; they are not durable event identities.
    return null
}
