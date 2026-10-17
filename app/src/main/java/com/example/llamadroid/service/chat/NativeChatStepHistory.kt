package com.example.llamadroid.service.chat

import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.service.OllamaService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns the recorded steps of an assistant reply into request messages. Pure and deterministic, and
 * the SINGLE source of the model-facing transcript: the live tool loop builds its messages from the
 * recorder's steps through [turnMessages] every round, and later turns / Continue replay the stored
 * row through [expand]. Stored (already bounded) values are used as they are, never re-bounded or
 * re-ordered, so what the model saw live is byte-identical to what is re-sent and the server's KV
 * prefix keeps matching.
 *
 * - [expand]: real OpenAI-style history (assistant `tool_calls`, one `tool` message per call, NOTE
 *   steps as their own messages, final answer) for llama-server / Ollama turns that run with tools.
 * - [flatten]: plain text for engines without tool messages (LiteRT) and for turns without tools.
 * - Replies without steps are left alone by every entry point.
 *
 * Reasoning rule: THINKING steps become the assistant message's `thinking` (llama-server
 * `reasoning_content`, Ollama `thinking`) ONLY for the current turn (the live loop, or a history whose
 * last message is this reply). Earlier completed turns omit it, exactly what chat templates render
 * once a new user message follows.
 */
object NativeChatStepHistory {
    private const val FLATTEN_RESULT_CHARS = 200
    private val JSON = Json { ignoreUnknownKeys = true }

    fun decode(stepsJson: String?): NativeChatSteps? =
        NativeChatStepsCodec.decode(stepsJson)?.takeIf { it.steps.isNotEmpty() }

    /**
     * Messages for an assistant reply that has [steps]. Per round: leading NOTEs, then one assistant
     * message (that round's text, optional reasoning, tool calls) with its tool messages, then trailing
     * NOTEs; finally the final [content] when not blank. Text-only rounds fold into the next assistant
     * message so two assistants never touch. [encodeImage] fills `images` of a NOTE that carries an
     * image (Ollama only; pass the same encoder live and on replay).
     */
    fun expand(
        content: String,
        steps: NativeChatSteps,
        withReasoning: Boolean = false,
        encodeImage: ((String) -> String?)? = null,
        /** Reasoning of the final answer round (the message's own `thinking` column), when replayed. */
        finalThinking: String? = null
    ): List<OllamaService.ChatMessage> {
        val out = ArrayList<OllamaService.ChatMessage>()
        var carry = ""
        var carryThinking = ""
        fun flushCarry() {
            if (carry.isNotBlank()) {
                out += OllamaService.ChatMessage(role = "assistant", content = carry, thinking = carryThinking.ifEmpty { null })
            }
            carry = ""
            carryThinking = ""
        }
        fun note(step: NativeChatStep) {
            flushCarry()
            out += OllamaService.ChatMessage(
                role = step.role ?: "system",
                content = step.text,
                images = step.imagePath?.let { path -> encodeImage?.invoke(path) }?.let { listOf(it) },
                imagePath = step.imagePath
            )
        }
        for ((round, roundSteps) in roundsOf(steps)) {
            val firstContent = roundSteps.indexOfFirst { it.isContent() }
            val lead = if (firstContent < 0) roundSteps else roundSteps.take(firstContent)
            val body = if (firstContent < 0) emptyList() else roundSteps.drop(firstContent)
            lead.filter { it.type == NativeChatStepType.NOTE }.forEach(::note)
            val text = joinText(carry, body.filter { it.type == NativeChatStepType.TEXT }.map { it.text })
            val thinking = if (withReasoning) joinText("", body.filter { it.type == NativeChatStepType.THINKING }.map { it.text }) else ""
            val tools = body.filter { it.type == NativeChatStepType.TOOL }
            if (tools.isEmpty()) {
                carry = text
                carryThinking = thinking
            } else {
                carry = ""
                carryThinking = ""
                val calls = tools.mapIndexed { index, step ->
                    val id = step.toolCallId?.takeIf { it.isNotBlank() } ?: "call_${round}_$index"
                    val (argumentsJson, argumentsMap) = normalizedArguments(step.arguments)
                    OllamaService.ToolCall(
                        name = step.toolName.orEmpty(),
                        arguments = argumentsMap,
                        id = id,
                        rawArgumentsJson = argumentsJson
                    )
                }
                out += OllamaService.ChatMessage(
                    role = "assistant", content = text, toolCalls = calls, thinking = thinking.ifEmpty { null }
                )
                tools.forEachIndexed { index, step ->
                    out += OllamaService.ChatMessage(
                        role = "tool",
                        content = resultText(step),
                        toolCallId = calls[index].id,
                        toolName = step.toolName
                    )
                }
            }
            body.filter { it.type == NativeChatStepType.NOTE }.forEach(::note)
        }
        val finalText = joinText(carry, listOf(content))
        if (finalText.isNotBlank()) {
            out += OllamaService.ChatMessage(
                role = "assistant",
                content = finalText,
                thinking = (finalThinking?.takeIf { withReasoning && it.isNotBlank() })
                    ?: carryThinking.takeIf { content.isBlank() }?.ifEmpty { null }
            )
        }
        return out
    }

    /**
     * Messages of the turn in progress, rebuilt from the recorder's [steps] (reasoning included) every
     * round. [prefill] is the partial assistant text of a Continue, sent as the trailing assistant message.
     */
    fun turnMessages(
        steps: List<NativeChatStep>,
        prefill: OllamaService.ChatMessage? = null,
        encodeImage: ((String) -> String?)? = null
    ): List<OllamaService.ChatMessage> =
        expand("", NativeChatSteps(steps = steps), withReasoning = true, encodeImage = encodeImage) + listOfNotNull(prefill)

    /** "[Used tool name: first 200 chars of result]" lines (and round texts) before the final content. */
    fun flatten(content: String, steps: NativeChatSteps): String {
        val lines = ArrayList<String>()
        for ((_, roundSteps) in roundsOf(steps)) {
            for (step in roundSteps) {
                when (step.type) {
                    NativeChatStepType.TEXT -> if (step.text.isNotBlank()) lines += step.text
                    NativeChatStepType.TOOL -> {
                        val result = resultText(step).replace(Regex("\\s+"), " ").trim().take(FLATTEN_RESULT_CHARS)
                        lines += "[Used tool ${step.toolName.orEmpty()}: $result]"
                    }
                }
            }
        }
        return joinText(lines.joinToString("\n"), listOf(content))
    }

    /** History entities with step replies replaced by their flattened text and without steps. */
    fun flattenEntities(history: List<LlamaMessageEntity>): List<LlamaMessageEntity> =
        if (history.none { it.role == "assistant" && !it.stepsJson.isNullOrBlank() }) history
        else history.map { message ->
            val steps = if (message.role == "assistant") decode(message.stepsJson) else null
            if (steps == null) message
            else message.copy(content = flatten(message.content, steps), stepsJson = null)
        }

    /** Messages one history entity expands to (1 for everything but replies with steps). */
    fun expandedSize(message: LlamaMessageEntity): Int {
        val steps = if (message.role == "assistant") decode(message.stepsJson) else null
        return steps?.let { expand(message.content, it).size } ?: 1
    }

    /** Entity id per request message of [history] (parallel list for the compaction hooks). */
    fun entityIds(history: List<LlamaMessageEntity>): List<Long?> = history.flatMap { message ->
        List(expandedSize(message)) { message.id.takeIf { it > 0L } }
    }

    private fun roundsOf(steps: NativeChatSteps): List<Pair<Int, List<NativeChatStep>>> =
        steps.steps.withIndex()
            .sortedWith(compareBy<IndexedValue<NativeChatStep>>({ it.value.round }, { it.index }))
            .map { it.value }
            .groupBy { it.round }
            .toList()

    /** Non-blank pieces verbatim: one piece is returned untouched, several are joined by a blank line. */
    private fun joinText(first: String, rest: List<String>): String =
        (listOf(first) + rest).filter { it.isNotBlank() }.joinToString("\n\n")

    private fun NativeChatStep.isContent() =
        type == NativeChatStepType.TEXT || type == NativeChatStepType.THINKING || type == NativeChatStepType.TOOL

    private fun resultText(step: NativeChatStep): String =
        if (step.status == NativeChatStepStatus.RUNNING && step.text.isBlank()) NativeChatStepRecorder.INTERRUPTED_RESULT
        else step.text

    /** Valid JSON object text plus its string map; a cut-off or malformed value becomes "{}". */
    private fun normalizedArguments(arguments: String?): Pair<String, Map<String, String>> {
        val raw = arguments?.trim().orEmpty()
        val parsed = raw.takeIf { it.isNotEmpty() }
            ?.let { runCatching { JSON.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return "{}" to emptyMap()
        val map = LinkedHashMap<String, String>()
        parsed.forEach { (key, value) -> map[key] = if (value is JsonPrimitive) value.content else value.toString() }
        return raw to map
    }
}
