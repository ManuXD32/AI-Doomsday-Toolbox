package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageRole
import com.example.llamadroid.service.OllamaService.ChatMessage
import com.example.llamadroid.service.OllamaService.ToolCall
import com.example.llamadroid.service.compaction.CompactionPlan
import com.example.llamadroid.service.compaction.CompactionPolicy
import com.example.llamadroid.service.compaction.ConversationCompactor
import com.example.llamadroid.service.compaction.TokenEstimator
import org.json.JSONArray
import org.json.JSONObject

/** Pure mapping between durable subagent rows and model messages. */
object DelegateSubagentMessages {
    const val MAX_CONTENT_CHARS = 16_000
    const val MAX_TOOL_ROW_CHARS = 6_000
    const val MAX_THINKING_CHARS = 4_000

    fun bound(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 24).trimEnd() + "\n…[truncated]"

    /** Rows sent to the model: user/assistant/tool, not compacted out; the live summary row first. */
    fun modelRows(rows: List<NativeChatDelegateMessageEntity>): List<NativeChatDelegateMessageEntity> {
        val usable = rows.filter {
            !it.compactedOut && (it.role == NativeChatDelegateMessageRole.USER ||
                it.role == NativeChatDelegateMessageRole.ASSISTANT || it.role == NativeChatDelegateMessageRole.TOOL)
        }
        val summary = usable.lastOrNull { it.isSummary }
        return listOfNotNull(summary) + usable.filter { !it.isSummary }
    }

    fun toChatMessage(row: NativeChatDelegateMessageEntity): ChatMessage = when (row.role) {
        NativeChatDelegateMessageRole.ASSISTANT -> ChatMessage(
            role = "assistant", content = row.content,
            toolCalls = parseToolCalls(row.toolCallsJson).takeIf { it.isNotEmpty() },
            thinking = row.thinking
        )
        NativeChatDelegateMessageRole.TOOL -> ChatMessage(
            role = "tool", content = row.content, toolCallId = row.toolCallId, toolName = row.toolName
        )
        else -> ChatMessage(
            role = "user",
            content = if (row.isSummary) ConversationCompactor.summaryBlock(row.content) else row.content
        )
    }

    fun toChatMessages(rows: List<NativeChatDelegateMessageEntity>): List<ChatMessage> = rows.map(::toChatMessage)

    /** Strict chat templates reject two user messages in a row. */
    fun mergeConsecutiveUsers(messages: List<ChatMessage>): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(messages.size)
        for (m in messages) {
            val last = out.lastOrNull()
            if (last != null && last.role == "user" && m.role == "user") {
                out[out.lastIndex] = last.copy(content = last.content + "\n\n" + m.content)
            } else out += m
        }
        return out
    }

    /** OpenAI-style `tool_calls` array. */
    fun toolCallsJson(calls: List<ToolCall>): String = JSONArray().also { array ->
        calls.forEach { call ->
            val args = call.rawArgumentsJson?.takeIf { it.isNotBlank() }
                ?: JSONObject(call.arguments as Map<*, *>).toString()
            array.put(
                JSONObject().put("id", call.id.orEmpty()).put("type", "function")
                    .put("function", JSONObject().put("name", call.name).put("arguments", args))
            )
        }
    }.toString()

    fun parseToolCalls(json: String?): List<ToolCall> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val fn = o.optJSONObject("function") ?: return@mapNotNull null
                val name = fn.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val raw = fn.optString("arguments").takeIf { it.isNotBlank() }
                ToolCall(name = name, arguments = parseArguments(raw), id = o.optString("id").takeIf { it.isNotBlank() }, rawArgumentsJson = raw)
            }
        }.getOrDefault(emptyList())
    }

    fun parseArguments(rawJson: String?): Map<String, String> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(rawJson)
            o.keys().asSequence().associateWith { k -> o.opt(k)?.let { if (it == JSONObject.NULL) "" else it.toString() }.orEmpty() }
        }.getOrDefault(emptyMap())
    }

    /** Tool calls of the newest assistant row that have no result row yet (crash mid-execution). */
    fun missingToolResults(rows: List<NativeChatDelegateMessageEntity>): List<ToolCall> {
        val modelRows = modelRows(rows)
        val lastAssistant = modelRows.indexOfLast { it.role == NativeChatDelegateMessageRole.ASSISTANT }
        if (lastAssistant < 0) return emptyList()
        val calls = parseToolCalls(modelRows[lastAssistant].toolCallsJson)
        if (calls.isEmpty()) return emptyList()
        val answered = modelRows.drop(lastAssistant + 1).filter { it.role == NativeChatDelegateMessageRole.TOOL }
            .mapNotNull { it.toolCallId }.toSet()
        return calls.filter { it.id != null && it.id !in answered }
    }

    /** Stable key of a call for the repeated-call guard. */
    fun signature(call: ToolCall): String =
        call.name + "|" + call.arguments.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
}

/** Compaction cut selection for the subagent, whose conversation has few user turns. */
object DelegateSubagentCompaction {
    private const val MIN_MESSAGES = 6

    /**
     * Index in [messages] where the kept tail begins, or null. Cuts only before user/assistant
     * messages, so a tool call is never separated from its results; keeps at least two assistant
     * steps and about [CompactionPolicy.retainRatio] of the tokens.
     */
    fun planCut(messages: List<ChatMessage>, estimator: TokenEstimator, policy: CompactionPolicy = CompactionPolicy()): Int? {
        val n = messages.size
        if (n < MIN_MESSAGES) return null
        val budget = (estimator.estimateMessages(messages) * policy.retainRatio).toInt()
        var cut: Int? = null
        var reachedMinimum = false
        for (i in n - 1 downTo 1) {
            if (messages[i].role == "tool") continue
            val tail = messages.subList(i, n)
            val steps = tail.count { it.role == "assistant" }
            if (!reachedMinimum) {
                cut = i
                if (steps >= 2) reachedMinimum = true
            } else if (estimator.estimateMessages(tail) <= budget) cut = i else break
        }
        if (!reachedMinimum) return null
        val c = cut ?: return null
        return c.takeIf { messages.subList(0, c).any { m -> m.role == "assistant" } }
    }

    fun plan(system: ChatMessage, messages: List<ChatMessage>, cut: Int, estimator: TokenEstimator): CompactionPlan =
        CompactionPlan(
            leadingSystem = listOf(system),
            toSummarize = messages.subList(0, cut).toList(),
            kept = messages.subList(cut, messages.size).toList(),
            keptStartIndex = cut,
            estimatedTokensBefore = estimator.estimateMessages(listOf(system) + messages)
        )

    /** Highest sequence number among the non-summary rows being replaced. */
    fun throughSeq(rows: List<NativeChatDelegateMessageEntity>, cut: Int): Int? =
        rows.subList(0, cut).filter { !it.isSummary }.maxOfOrNull { it.seq }
}
