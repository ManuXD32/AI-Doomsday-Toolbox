package com.example.llamadroid.service

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.floor

enum class AgentMode {
    PLAN,
    BUILD
}

/**
 * Plan mode is a hard runtime boundary, not just a prompt hint. Keep this
 * allow-list deliberately small: every tool outside it can write, execute,
 * delegate implementation, or otherwise change the project/runtime state.
 */
internal val PLAN_MODE_ALLOWED_TOOL_NAMES: Set<String> = setOf(
    "read_file",
    "list_directory",
    "search_code",
    "read_memory",
    "list_memory",
    "file_line_count",
    "read_file_lines",
    "kb_search",
    "kb_read_chunk",
    "kb_list_sources",
    "web_search",
    "fetch_url",
    "kiwix_search",
    "get_datetime",
    "view_image",
    "run_tools_sequential",
    "question",
    "todo_write",
    "todo_read",
    "skill",
    "read_skill_resource",
    "propose_plan",
    "reflection"
)

internal fun isPlanModeToolBlocked(
    planModeEnabled: Boolean,
    toolName: String
): Boolean = planModeEnabled && toolName !in PLAN_MODE_ALLOWED_TOOL_NAMES

internal fun isPlanQuestionRequirementSatisfied(
    planModeEnabled: Boolean,
    answeredQuestionCount: Int
): Boolean = !planModeEnabled || answeredQuestionCount > 0

enum class AgentMessagePartType {
    TEXT,
    REASONING,
    TOOL,
    TERMINAL,
    APPROVAL,
    QUESTION,
    TODO,
    DELEGATION,
    PLAN,
    COMPACTION,
    FILE,
    PREVIEW
}

enum class AgentPartStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    ERROR,
    INTERRUPTED
}

data class AgentMessagePart(
    val id: String,
    val messageId: String,
    val position: Int,
    val type: AgentMessagePartType,
    val status: AgentPartStatus,
    val textPreview: String? = null,
    val canonicalJson: String? = null,
    val contentRef: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val safeTarget: String? = null,
    val durationMs: Long? = null
)

data class CanonicalToolCall(
    val id: String,
    val name: String,
    val rawArgumentsJson: String
)

data class CanonicalInferenceMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<CanonicalToolCall> = emptyList(),
    val toolCallId: String? = null,
    val reasoning: String? = null
) {
    fun toStableJson(): String {
        val value = linkedMapOf<String, Any?>("role" to role)
        if (content.isNotEmpty() || toolCalls.isEmpty()) value["content"] = content
        if (reasoning != null) value["reasoning"] = reasoning
        if (toolCalls.isNotEmpty()) {
            value["tool_calls"] = toolCalls.map { call ->
                linkedMapOf(
                    "id" to call.id,
                    "type" to "function",
                    "function" to linkedMapOf(
                        "name" to call.name,
                        // Arguments are a JSON string in OpenAI-compatible tool
                        // calls. Quoting the original string preserves its bytes.
                        "arguments" to call.rawArgumentsJson
                    )
                )
            }
        }
        if (toolCallId != null) value["tool_call_id"] = toolCallId
        return stableJson(value)
    }
}

fun canonicalInferenceMessagesJson(messages: List<CanonicalInferenceMessage>): String =
    messages.joinToString(prefix = "[", postfix = "]") { it.toStableJson() }

fun canonicalInferenceMessagesHash(messages: List<CanonicalInferenceMessage>): String =
    workflowSha256(canonicalInferenceMessagesJson(messages))

fun canonicalInferenceMessagePrefixHash(
    messages: List<CanonicalInferenceMessage>,
    count: Int
): String = canonicalInferenceMessagesHash(messages.take(count.coerceIn(0, messages.size)))

fun OllamaService.ChatMessage.toCanonicalInferenceMessage(): CanonicalInferenceMessage =
    CanonicalInferenceMessage(
        role = role,
        content = content,
        toolCalls = toolCalls.orEmpty().map { call ->
            CanonicalToolCall(
                id = call.id.orEmpty(),
                name = call.name,
                rawArgumentsJson = canonicalToolArguments(call)
            )
        },
        toolCallId = toolCallId,
        reasoning = thinking
    )

data class AgentTurnContext(
    val rootTurnId: String,
    val conversationId: Long,
    val agentKey: String,
    val backend: String,
    val modelLabel: String,
    val endpointGeneration: String,
    val contextTokens: Int,
    val configuredOutputTokens: Int,
    val effectiveOutputTokens: Int,
    val stableSystemPrompt: String,
    val tools: List<AgentTool>,
    val skillIds: List<String>,
    val thinkingEnabled: Boolean,
    val parametersJson: String,
    val slotId: Int? = null
) {
    val systemPromptHash: String = workflowSha256(stableSystemPrompt)
    val toolDefinitionsHash: String = workflowSha256(canonicalToolsJson(tools))
    val parametersHash: String = workflowSha256(parametersJson)
    val stablePrefixHash: String = workflowSha256(
        listOf(
            stableSystemPrompt,
            canonicalToolsJson(tools),
            thinkingEnabled.toString(),
            parametersJson
        ).joinToString("\u001f")
    )
}

data class PromptCacheState(
    val stablePrefixHash: String,
    val previousPrefixCompatible: Boolean?,
    val changedComponents: Set<String> = emptySet(),
    val cachedPromptTokens: Int? = null,
    val evaluatedPromptTokens: Int? = null,
    val promptEvaluationMs: Long? = null,
    val reusedPrefixTokens: Int? = null,
    val slotId: Int? = null,
    val evictionReason: String? = null
)

data class SkillManifest(
    val name: String,
    val description: String,
    val version: String? = null,
    val license: String? = null,
    val invocation: String = "both",
    val allowedTools: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

enum class SkillPermission {
    ALLOW,
    ASK,
    DENY
}

data class SkillAssignment(
    val skillId: String,
    val conversationId: Long?,
    val agentKey: String,
    val permission: SkillPermission
)

data class QuestionOption(
    val id: String,
    val label: String,
    val description: String? = null
)

data class QuestionItem(
    val id: String,
    val header: String,
    val prompt: String,
    val options: List<QuestionOption>,
    val multiple: Boolean = false,
    val allowCustom: Boolean = true
)

data class QuestionSpec(
    val questions: List<QuestionItem>
) {
    init {
        require(questions.size in 1..5) { "A question request must contain 1 to 5 questions" }
        require(questions.map { it.id.trim().lowercase() }.distinct().size == questions.size) {
            "Question IDs must be unique"
        }
        require(questions.all { it.options.size in 2..3 }) { "Each question must contain 2 to 3 choices" }
        require(questions.all { question ->
            question.prompt.isNotBlank() && question.prompt.count { it == '?' } <= 1
        }) { "Each question must contain one coherent prompt" }
        require(questions.all { question ->
            question.options.map { it.id.trim().lowercase() }.distinct().size == question.options.size &&
                question.options.map { it.label.trim().lowercase() }.distinct().size == question.options.size &&
                question.options.all { option ->
                    option.id.isNotBlank() && option.label.isNotBlank() &&
                        !looksLikeQuestionOption(option.label)
                }
        }) { "Each question must contain distinct literal choices" }
        require(questions.none(::isPlanApprovalQuestion)) {
            "The question tool cannot be used to ask for plan approval"
        }
    }

    fun toJson(): String = stableJson(
        linkedMapOf(
            "questions" to questions.map { question ->
                linkedMapOf(
                    "id" to question.id,
                    "header" to question.header,
                    "prompt" to question.prompt,
                    "multiple" to question.multiple,
                    "allow_custom" to question.allowCustom,
                    "options" to question.options.map { option ->
                        linkedMapOf(
                            "id" to option.id,
                            "label" to option.label,
                            "description" to option.description
                        )
                    }
                )
            }
        )
    )
}

private fun looksLikeQuestionOption(label: String): Boolean {
    val normalized = label.trim().lowercase()
    if ('?' in normalized) return true
    return Regex(
        "^(?:what|why|when|where|who|which|how|qué|por qué|cuando|cuándo|donde|dónde|quién|cual|cuál)\\b"
    ).containsMatchIn(normalized)
}

/** Approval is an explicit UI action, never a model-authored question. */
internal fun isPlanApprovalQuestion(question: QuestionItem): Boolean {
    val text = listOf(question.header, question.prompt)
        .plus(question.options.map { "${it.label} ${it.description.orEmpty()}" })
        .joinToString(" ")
        .lowercase()
    return Regex(
        "(?:approve|approval|aprobar|aprobación|aprobacion).{0,48}plan|" +
            "plan.{0,48}(?:approve|approval|aprobar|aprobación|aprobacion)"
    )
        .containsMatchIn(text)
}

fun questionSpecFromJson(json: String): QuestionSpec {
    val root = JSONObject(json)
    val array = root.optJSONArray("questions")
        ?: throw IllegalArgumentException("Question specification is missing questions")
    return QuestionSpec(
        (0 until array.length()).map { index ->
            val question = array.getJSONObject(index)
            val options = question.optJSONArray("options") ?: JSONArray()
            QuestionItem(
                id = question.optString("id").ifBlank { "question_${index + 1}" },
                header = question.optString("header").ifBlank { "Question" },
                prompt = question.getString("prompt"),
                options = (0 until options.length()).map { optionIndex ->
                    val option = options.getJSONObject(optionIndex)
                    QuestionOption(
                        id = option.optString("id").ifBlank { "option_${optionIndex + 1}" },
                        label = option.getString("label"),
                        description = option.optString("description").takeIf { it.isNotBlank() }
                    )
                },
                multiple = question.optBoolean("multiple", false),
                allowCustom = question.optBoolean("allow_custom", true)
            )
        }
    )
}

/** Builds the immutable model-facing result once, including human-readable choices. */
fun authoritativeQuestionAnswerJson(specificationJson: String, submittedJson: String): String {
    val spec = questionSpecFromJson(specificationJson)
    val submitted = JSONObject(submittedJson).optJSONObject("answers")
        ?: throw IllegalArgumentException("Question answers are missing")
    val answers = spec.questions.map { question ->
        val value = submitted.optJSONObject(question.id) ?: JSONObject()
        val selectedIds = value.optJSONArray("selected")?.let { selected ->
            (0 until selected.length()).mapNotNull { index ->
                selected.optString(index).takeIf { it.isNotBlank() }
            }
        }.orEmpty()
        val selectedOptions = selectedIds.map { selectedId ->
            val option = question.options.firstOrNull { it.id == selectedId }
                ?: throw IllegalArgumentException("Unknown option for ${question.id}")
            linkedMapOf("id" to option.id, "label" to option.label)
        }
        val custom = value.optString("custom").trim()
        require(selectedOptions.isNotEmpty() || custom.isNotBlank()) {
            "Every question requires an answer"
        }
        linkedMapOf(
            "question_id" to question.id,
            "question" to question.prompt,
            "selected_options" to selectedOptions,
            "custom_answer" to custom
        )
    }
    return stableJson(
        linkedMapOf(
            "authority" to "critical_user_requirements",
            "instruction" to "These are direct user decisions. Follow them unless the user explicitly changes them later.",
            "answers" to answers
        )
    )
}

data class AgentTodoItem(
    val id: String,
    val text: String,
    val status: String,
    val priority: String,
    val position: Int
)

data class ContextCompactionBudget(
    val usableContextTokens: Int,
    val recentTailTargetTokens: Int,
    val toolOutputProtectTokens: Int,
    val minimumReclaimTokens: Int
)

fun resolveContextCompactionBudget(
    modelContextTokens: Int,
    outputTokens: Int,
    pinnedPromptTokens: Int,
    templateReserveTokens: Int = 256
): ContextCompactionBudget {
    val usable = (
        modelContextTokens -
            outputTokens.coerceAtLeast(0) -
            pinnedPromptTokens.coerceAtLeast(0) -
            templateReserveTokens.coerceAtLeast(0)
        ).coerceAtLeast(512)
    val desiredTail = floor(usable * 0.25).toInt()
        .coerceIn(2_000.coerceAtMost(usable), 8_000.coerceAtMost(usable))
    val availableTail = (usable * 0.55).toInt().coerceAtLeast(256)
    val tail = desiredTail.coerceAtMost(availableTail)
    return ContextCompactionBudget(
        usableContextTokens = usable,
        recentTailTargetTokens = tail,
        toolOutputProtectTokens = (usable * 0.35).toInt().coerceIn(1_000.coerceAtMost(usable), 40_000.coerceAtMost(usable)),
        minimumReclaimTokens = (usable * 0.10).toInt().coerceAtLeast(512)
    )
}

fun canonicalToolsJson(tools: List<AgentTool>): String {
    val canonical = tools
        .distinctBy { it.name }
        .sortedBy { it.name }
        .map { tool ->
            linkedMapOf(
                "name" to tool.name,
                "description" to tool.description,
                "schema" to RawJson(tool.schemaJson ?: legacyToolSchemaJson(tool)),
                "required" to tool.requiredParams.sorted()
            )
        }
    return stableJson(canonical)
}

fun legacyToolSchemaJson(tool: AgentTool): String = stableJson(
    linkedMapOf(
        "type" to "object",
        "properties" to tool.parameters.toSortedMap().mapValues { (_, description) ->
            linkedMapOf("type" to "string", "description" to description)
        },
        "required" to tool.requiredParams.sorted(),
        "additionalProperties" to false
    )
)

fun comparePromptCacheState(
    previous: AgentTurnContext?,
    current: AgentTurnContext
): PromptCacheState {
    if (previous == null) {
        return PromptCacheState(
            stablePrefixHash = current.stablePrefixHash,
            previousPrefixCompatible = null
        )
    }
    val changed = buildSet {
        if (previous.systemPromptHash != current.systemPromptHash) add("system_prompt")
        if (previous.toolDefinitionsHash != current.toolDefinitionsHash) add("tools")
        if (previous.parametersHash != current.parametersHash) add("parameters")
        if (previous.backend != current.backend || previous.modelLabel != current.modelLabel) add("model")
        if (previous.endpointGeneration != current.endpointGeneration) add("server_generation")
    }
    return PromptCacheState(
        stablePrefixHash = current.stablePrefixHash,
        previousPrefixCompatible = changed.isEmpty(),
        changedComponents = changed
    )
}

private data class RawJson(val value: String)

internal fun stableJson(value: Any?): String {
    return when (value) {
        null, JSONObject.NULL -> "null"
        is RawJson -> canonicalizeRawJson(value.value)
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        is JSONObject -> {
            val map = value.keys().asSequence().associateWith { key -> value.opt(key) }
            stableJson(map)
        }
        is JSONArray -> stableJson((0 until value.length()).map { value.opt(it) })
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .joinToString(prefix = "{", postfix = "}") { (key, nested) ->
                "${JSONObject.quote(key.toString())}:${stableJson(nested)}"
            }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { stableJson(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { stableJson(it) }
        else -> JSONObject.quote(value.toString())
    }
}

private fun canonicalizeRawJson(raw: String): String {
    val trimmed = raw.trim()
    return runCatching {
        when {
            trimmed.startsWith("{") -> stableJson(JSONObject(trimmed))
            trimmed.startsWith("[") -> stableJson(JSONArray(trimmed))
            else -> JSONObject.quote(trimmed)
        }
    }.getOrElse { JSONObject.quote(trimmed) }
}

private fun workflowSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
