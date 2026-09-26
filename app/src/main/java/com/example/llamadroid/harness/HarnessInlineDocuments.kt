package com.example.llamadroid.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.ui.agent.harness.NativeHarnessWorkspaceFilePage
import com.example.llamadroid.ui.agent.harness.parseNativeHarnessWorkspaceFilePage
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal data class HarnessInlineDocumentRequest(
    val sessionId: String,
    val path: String,
    val line: Int = 1,
    val ownerSessionId: String = sessionId,
)

/** Every request uses the captured Session, including absolute paths supplied by the skill catalog. */
internal class HarnessInlineDocumentReader(private val client: HarnessClient, private val sessionId: String) {
    suspend fun skillPath(name: String): String {
        require(name.matches(Regex("[-A-Za-z0-9_]+"))) { "INLINE_SKILL_INVALID" }
        val result = call("skills", "list", buildJsonObject {
            putJsonObject("request") { put("sessionId", sessionId) }
        }) as? JsonObject ?: error("INLINE_SKILL_INVALID")
        val skills = result["skills"] as? JsonArray ?: error("INLINE_SKILL_INVALID")
        return skills.asSequence().mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("name") == name }?.string("path")
            ?.takeIf { it.isNotBlank() && it.length <= 8_192 } ?: error("INLINE_SKILL_NOT_FOUND")
    }

    suspend fun page(path: String, line: Int): NativeHarnessWorkspaceFilePage {
        require(line >= 1)
        val value = call("workspaceFiles", "read", arguments(path) {
            putJsonObject("range") { put("offset", line); put("limit", PAGE_LINES) }
        })
        return parseNativeHarnessWorkspaceFilePage(value, path)
            ?.takeIf { it.offset == line } ?: error("INLINE_FILE_INVALID")
    }

    /** Byte windows preserve full documents without a giant JSON response or silent truncation. */
    suspend fun document(path: String): ByteArray {
        val output = ByteArrayOutputStream()
        var version: String? = null
        var absolutePath: String? = null
        var size: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val offset = output.size()
            val row = call("workspaceFiles", "readBytes", arguments(path) {
                putJsonObject("range") { put("offset", offset); put("length", BYTE_PAGE) }
            }) as? JsonObject ?: error("INLINE_FILE_INVALID")
            val nextVersion = row.string("version") ?: error("INLINE_FILE_INVALID")
            val nextPath = row.string("absolutePath") ?: error("INLINE_FILE_INVALID")
            val nextSize = (row["bytes"] as? JsonPrimitive)?.longOrNull
            if (version != null) check(version == nextVersion && absolutePath == nextPath && size == nextSize) {
                "INLINE_FILE_CHANGED"
            }
            version = nextVersion
            absolutePath = nextPath
            size = nextSize
            require(size == null || size in 0..MAX_DOCUMENT.toLong()) { "INLINE_FILE_TOO_LARGE" }
            check((row["offset"] as? JsonPrimitive)?.longOrNull == offset.toLong()) { "INLINE_FILE_INVALID" }
            val encoded = row.string("data") ?: error("INLINE_FILE_INVALID")
            require(encoded.length <= (BYTE_PAGE + 2) / 3 * 4) { "INLINE_FILE_INVALID" }
            val bytes = Base64.getDecoder().decode(encoded)
            val eof = (row["eof"] as? JsonPrimitive)?.booleanOrNull ?: error("INLINE_FILE_INVALID")
            require(bytes.size <= BYTE_PAGE && output.size() <= MAX_DOCUMENT - bytes.size) { "INLINE_FILE_TOO_LARGE" }
            check(eof || bytes.isNotEmpty()) { "INLINE_FILE_INVALID" }
            output.write(bytes)
            if (eof) {
                check(size == null || output.size().toLong() == size) { "INLINE_FILE_CHANGED" }
                return output.toByteArray()
            }
        }
    }

    private fun arguments(path: String, additional: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject {
            require(path.isNotBlank() && path.length <= 8_192 && path.none { it.isISOControl() })
            put("workspaceFileScopeId", sessionId)
            put("path", path)
            additional()
        }

    private suspend fun call(namespace: String, method: String, args: JsonObject): JsonElement =
        when (val result = client.call(namespace, method, args, HarnessCallPolicy.SafeRead)) {
            is HarnessRpcResult.Success -> result.value
            is HarnessRpcResult.Failure -> error(result.error.code)
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        const val PAGE_LINES = 200
        const val BYTE_PAGE = 256 * 1024
        const val MAX_DOCUMENT = 64 * 1024 * 1024
    }
}
