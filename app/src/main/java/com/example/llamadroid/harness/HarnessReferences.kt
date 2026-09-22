package com.example.llamadroid.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class HarnessReference(
    val key: String,
    val title: String,
    val mention: String,
    val path: String? = null,
    val directory: Boolean = false,
    val workspace: String? = null
)

internal data class HarnessReferenceResults(val entries: List<HarnessReference>, val incomplete: Boolean)

/** The same two read-only discovery contracts used by alpha2's web @ picker. */
internal suspend fun loadHarnessReferences(client: HarnessClient, sessionId: String, query: String): HarnessReferenceResults = coroutineScope {
    require(query.length <= 2048)
    val args = buildJsonObject { put("agentId", sessionId); put("query", query) }
    suspend fun read(namespace: String, method: String, files: Boolean): Pair<List<HarnessReference>, Boolean> = try {
        when (val result = client.call(namespace, method, args, HarnessCallPolicy.SafeRead)) {
            is HarnessRpcResult.Failure -> emptyList<HarnessReference>() to true
            is HarnessRpcResult.Success -> {
                val rows = result.value as? JsonArray ?: error("REFERENCE_REPLY_INVALID")
                rows.take(200).mapNotNull { row ->
                    val value = row as? JsonObject ?: return@mapNotNull null
                    fun string(name: String): String? = runCatching { value[name]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    if (files) {
                        val path = string("path")?.takeIf { it.length in 1..2048 } ?: return@mapNotNull null
                        val directory = when (string("kind")) { "directory" -> true; "file" -> false; else -> return@mapNotNull null }
                        val mention = formatHarnessFileMention(path, directory) ?: return@mapNotNull null
                        HarnessReference("file:$path", path, mention, path, directory)
                    } else {
                        val id = string("sessionId") ?: return@mapNotNull null
                        val mention = string("mention")?.takeIf { it.length in 1..4096 && it.startsWith("@[") } ?: return@mapNotNull null
                        HarnessReference("session:$id", (string("displayTitle") ?: string("label") ?: id).take(2048), mention,
                            workspace = string("cwd")?.take(2048))
                    }
                } to false
            }
        }
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { emptyList<HarnessReference>() to true }
    val files = async { read("fileReferences", "list", true) }
    val sessions = async { read("sessionReferenceResolver", "candidates", false) }
    val first = files.await()
    val second = sessions.await()
    HarnessReferenceResults((first.first + second.first).distinctBy { it.key }, first.second || second.second)
}

/** Matches @deepseek-ai/dsh-file-reference/grammar, including directory descent. */
internal fun formatHarnessFileMention(path: String, directory: Boolean): String? {
    if (path.any { it == '"' || it.code in 0..31 || it.code in 127..159 }) return null
    val target = path + if (directory) "/" else ""
    return if (target.any(Char::isWhitespace)) "@\"$target" + if (directory) "" else "\"" else "@$target"
}

internal fun insertHarnessReference(draft: String, mention: String): String {
    val token = Regex("(?:^|\\s)(@\"[^\"]*|@[^\\s]*)$").find(draft)?.groups?.get(1)
    val prefix = if (token != null) draft.substring(0, token.range.first) else draft + if (draft.isBlank() || draft.last().isWhitespace()) "" else " "
    val directory = !mention.startsWith("@[") && mention.endsWith('/')
    return prefix + mention + if (directory) "" else " "
}
