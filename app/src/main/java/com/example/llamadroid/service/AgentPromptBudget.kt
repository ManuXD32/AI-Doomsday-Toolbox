package com.example.llamadroid.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

const val AGENT_PROMPT_BUDGET_VERSION = 4

enum class AgentPromptCountSource(val wireValue: String) {
    LLAMA_SERVER_EXACT("llama-server exact"),
    CALIBRATED_SERIALIZED_FALLBACK("calibrated serialized estimate"),
    UNCALIBRATED_SERIALIZED_FALLBACK("serialized estimate")
}

data class AgentPromptCapacity(
    val configuredContextTokens: Int,
    val reportedContextTokens: Int?,
    val contextCapacityTokens: Int,
    val safetyReserveTokens: Int,
    val minimumGenerationReserveTokens: Int,
    val maximumInputTokens: Int,
    val exactCountingAvailable: Boolean
)

data class AgentPromptOutputBudget(
    val configuredMaxOutputTokens: Int,
    val effectiveMaxOutputTokens: Int,
    val minimumUsefulOutputTokens: Int,
    val availableOutputTokens: Int,
    val canSend: Boolean
)

data class AgentPromptPackingLimits(
    val triggerTokens: Int,
    val targetTokens: Int,
    val maximumCompactedTokens: Int
)

data class AgentPromptCalibration(
    val conservativeFactor: Double = 1.0,
    val sampleCount: Int = 0,
    val lastRawEstimate: Int = 0,
    val lastActualTokens: Int = 0
)

data class AgentPromptCountResolution(
    val rawMessageTokens: Int,
    val rawToolSchemaTokens: Int,
    val rawSerializedRequestTokens: Int,
    val calibratedFallbackTokens: Int,
    val resolvedInputTokens: Int,
    val exactInputTokens: Int?,
    val countSource: AgentPromptCountSource,
    val calibrationFactor: Double,
    val countLatencyMs: Long? = null,
    val exactCountError: String? = null
)

enum class AgentPromptUnitKind {
    USER_MESSAGE,
    ASSISTANT_RESPONSE,
    TOOL_EXCHANGE,
    SYSTEM_CONTROL,
    OTHER
}

data class AgentPromptAtomicUnit(
    val id: String,
    val kind: AgentPromptUnitKind,
    val messages: List<AgentService.Companion.ChatMessage>
) {
    val containsUserMessage: Boolean
        get() = messages.any { it.role == "user" }

    val isToolExchange: Boolean
        get() = kind == AgentPromptUnitKind.TOOL_EXCHANGE
}

data class AgentHardCompactionMetadata(
    val version: Int = AGENT_PROMPT_BUDGET_VERSION,
    val conversationId: Long,
    val sourceSnapshotEndSequence: Int,
    val sourceTurnGroupCount: Int,
    val contextTokens: Int,
    val maximumInputTokens: Int,
    val requiredPrimacyTokens: Int,
    val profileName: String,
    val toolDefinitionsHash: String,
    val summaryHash: String,
    val stateRevision: Long = 0L,
    val semanticEventCount: Long = 0L,
    val compactionKey: String = "",
    val preCompactionTokens: Int? = null,
    val postCompactionTokens: Int? = null,
    val savedTokens: Int? = null,
    val status: String = AgentCompactionStatus.REQUESTED,
    val saturationReason: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject().apply {
        put("kind", "agent_hard_compaction_metadata")
        put("version", version)
        put("conversation_id", conversationId)
        put("source_snapshot_end_sequence", sourceSnapshotEndSequence)
        put("source_turn_group_count", sourceTurnGroupCount)
        put("context_tokens", contextTokens)
        put("maximum_input_tokens", maximumInputTokens)
        put("required_primacy_tokens", requiredPrimacyTokens)
        put("profile_name", profileName)
        put("tool_definitions_hash", toolDefinitionsHash)
        put("summary_hash", summaryHash)
        put("state_revision", stateRevision)
        put("semantic_event_count", semanticEventCount)
        put("compaction_key", compactionKey)
        put("pre_compaction_tokens", preCompactionTokens)
        put("post_compaction_tokens", postCompactionTokens)
        put("saved_tokens", savedTokens)
        put("status", status)
        put("saturation_reason", saturationReason)
        put("created_at", createdAt)
    }.toString()

    companion object {
        fun fromJson(raw: String?): AgentHardCompactionMetadata? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                if (
                    json.optString("kind") !=
                    "agent_hard_compaction_metadata"
                ) {
                    return@runCatching null
                }
                AgentHardCompactionMetadata(
                    version = json.optInt("version", 1),
                    conversationId = json.getLong("conversation_id"),
                    sourceSnapshotEndSequence =
                        json.getInt("source_snapshot_end_sequence"),
                    sourceTurnGroupCount =
                        json.optInt("source_turn_group_count", 0),
                    contextTokens = json.optInt("context_tokens", 0),
                    maximumInputTokens =
                        json.optInt("maximum_input_tokens", 0),
                    requiredPrimacyTokens =
                        json.optInt("required_primacy_tokens", 0),
                    profileName =
                        json.optString("profile_name", "unknown"),
                    toolDefinitionsHash =
                        json.optString("tool_definitions_hash", ""),
                    summaryHash = json.optString("summary_hash", ""),
                    stateRevision =
                        json.optLong("state_revision", 0L),
                    semanticEventCount =
                        json.optLong("semantic_event_count", 0L),
                    compactionKey =
                        json.optString("compaction_key", ""),
                    preCompactionTokens =
                        json.optInt("pre_compaction_tokens")
                            .takeIf {
                                json.has("pre_compaction_tokens") &&
                                    !json.isNull("pre_compaction_tokens")
                            },
                    postCompactionTokens =
                        json.optInt("post_compaction_tokens")
                            .takeIf {
                                json.has("post_compaction_tokens") &&
                                    !json.isNull("post_compaction_tokens")
                            },
                    savedTokens =
                        json.optInt("saved_tokens")
                            .takeIf {
                                json.has("saved_tokens") &&
                                    !json.isNull("saved_tokens")
                            },
                    status = json.optString(
                        "status",
                        AgentCompactionStatus.APPLIED
                    ),
                    saturationReason =
                        json.optString("saturation_reason")
                            .takeIf { it.isNotBlank() },
                    createdAt = json.optLong("created_at", 0L)
                )
            }.getOrNull()
        }
    }
}



fun resolveAgentPromptCapacity(
    configuredContextTokens: Int,
    reportedContextTokens: Int?,
    exactCountingAvailable: Boolean
): AgentPromptCapacity {
    val configured = configuredContextTokens.coerceAtLeast(512)
    val reported = reportedContextTokens?.takeIf { it > 0 }
    val capacity = reported?.let { minOf(configured, it) } ?: configured
    val minimumGenerationReserve = when {
        capacity <= 4_096 -> 512
        capacity <= 8_192 -> 768
        capacity <= 16_384 -> 1_024
        capacity <= 32_768 -> 1_536
        else -> 2_048
    }
    val safetyReserve = if (exactCountingAvailable) {
        maxOf(256, capacity / 100)
    } else {
        maxOf(1_024, capacity / 10)
    }
    val maximumInput = (
        capacity - minimumGenerationReserve - safetyReserve
    ).coerceAtLeast(1)
    return AgentPromptCapacity(
        configuredContextTokens = configured,
        reportedContextTokens = reported,
        contextCapacityTokens = capacity,
        safetyReserveTokens = safetyReserve,
        minimumGenerationReserveTokens = minimumGenerationReserve,
        maximumInputTokens = maximumInput,
        exactCountingAvailable = exactCountingAvailable
    )
}

fun resolveAgentPromptOutputBudget(
    configuredMaxOutputTokens: Int,
    capacity: AgentPromptCapacity,
    authoritativeInputTokens: Int,
    fallbackMaxOutputTokens: Int = AGENT_DEFAULT_MAX_OUTPUT_TOKENS
): AgentPromptOutputBudget {
    val configured = configuredMaxOutputTokens
        .takeIf { it > 0 }
        ?: fallbackMaxOutputTokens
    val available = (
        capacity.contextCapacityTokens -
            authoritativeInputTokens.coerceAtLeast(0) -
            capacity.safetyReserveTokens
    ).coerceAtLeast(0)
    val effective = minOf(configured, available)
    val usefulMinimum = minOf(
        configured.coerceAtLeast(1),
        capacity.minimumGenerationReserveTokens
    )
    return AgentPromptOutputBudget(
        configuredMaxOutputTokens = configured,
        effectiveMaxOutputTokens = effective.coerceAtLeast(0),
        minimumUsefulOutputTokens = usefulMinimum,
        availableOutputTokens = available,
        canSend = effective >= usefulMinimum
    )
}

fun resolveAgentPromptPackingLimits(
    maximumInputTokens: Int,
    softTargetRatio: Double,
    compactMode: Boolean
): AgentPromptPackingLimits {
    val available = maximumInputTokens.coerceAtLeast(256)
    if (compactMode) {
        val target = (available * 0.50).roundToInt().coerceAtLeast(256)
        val maximum = (available * 0.55).roundToInt().coerceAtLeast(target)
        return AgentPromptPackingLimits(
            triggerTokens = target,
            targetTokens = target,
            maximumCompactedTokens = maximum
        )
    }

    val normalizedTargetRatio = softTargetRatio.coerceIn(0.35, 0.75)
    val triggerRatio = (normalizedTargetRatio + 0.15).coerceAtMost(0.90)
    val target = (available * normalizedTargetRatio)
        .roundToInt()
        .coerceAtLeast(256)
    val trigger = (available * triggerRatio)
        .roundToInt()
        .coerceAtLeast(target)
    return AgentPromptPackingLimits(
        triggerTokens = trigger,
        targetTokens = target,
        maximumCompactedTokens = trigger
    )
}

fun estimateRawPromptTextTokens(text: String): Int {
    if (text.isBlank()) return 0
    return ceil(text.length.toDouble() / 4.0).toInt().coerceAtLeast(1)
}

fun estimateRawOllamaMessageTokens(
    messages: List<OllamaService.ChatMessage>
): Int = messages.sumOf { message ->
    var total = estimateRawPromptTextTokens(message.role) +
        estimateRawPromptTextTokens(message.content) +
        8
    message.toolCallId?.let {
        total += estimateRawPromptTextTokens(it) + 2
    }
    message.toolCalls.orEmpty().forEach { call ->
        total += estimateRawPromptTextTokens(call.name)
        total += estimateRawPromptTextTokens(canonicalToolArguments(call))
        total += 8
    }
    if (!message.images.isNullOrEmpty() || !message.imagePath.isNullOrBlank()) {
        total += 1_024
    }
    total
}

fun canonicalAgentToolSchemaJson(tools: List<AgentTool>): String {
    val array = JSONArray()
    tools.sortedBy { it.name }.forEach { tool ->
        val parameters = tool.schemaJson
            ?.let { schema -> runCatching { JSONObject(schema) }.getOrNull() }
            ?: JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    tool.parameters.toSortedMap().forEach { (name, description) ->
                        put(
                            name,
                            JSONObject()
                                .put("type", "string")
                                .put("description", description)
                        )
                    }
                })
                put("required", JSONArray(tool.requiredParams.sorted()))
                put("additionalProperties", false)
            }
        array.put(
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                        .put("parameters", parameters)
                )
        )
    }
    return array.toString()
}

fun estimateRawAgentToolSchemaTokens(tools: List<AgentTool>): Int =
    estimateRawPromptTextTokens(canonicalAgentToolSchemaJson(tools))

fun estimateFallbackMultimodalPromptTokens(
    messages: List<OllamaService.ChatMessage>
): Int = messages.sumOf { message ->
    val imageCount = maxOf(
        message.images.orEmpty().size,
        if (message.imagePath.isNullOrBlank()) 0 else 1
    )
    val audioCount = if (message.audioPath.isNullOrBlank()) 0 else 1
    imageCount * 1_024 + audioCount * 1_024
}

fun buildCanonicalAgentPromptRequestJson(
    model: String,
    messages: List<OllamaService.ChatMessage>,
    tools: List<AgentTool>,
    thinkingEnabled: Boolean
): String {
    val messageArray = JSONArray()
    messages.forEach { message ->
        val node = JSONObject()
            .put("role", message.role)
            .put("content", message.content)
        message.toolCallId?.let { node.put("tool_call_id", it) }
        message.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
            node.put(
                "tool_calls",
                JSONArray().apply {
                    calls.forEach { call ->
                        put(
                            JSONObject()
                                .put(
                                    "id",
                                    call.id ?: stableToolCallId(
                                        call.name,
                                        canonicalToolArguments(call)
                                    )
                                )
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", call.name)
                                        .put(
                                            "arguments",
                                            canonicalToolArguments(call)
                                        )
                                )
                        )
                    }
                }
            )
        }
        messageArray.put(node)
    }

    return JSONObject()
        .put("model", model)
        .put("messages", messageArray)
        .put("tools", JSONArray(canonicalAgentToolSchemaJson(tools)))
        .put(
            "chat_template_kwargs",
            JSONObject().put("enable_thinking", thinkingEnabled)
        )
        .toString()
}

fun estimateRawSerializedAgentRequestTokens(serializedRequest: String): Int =
    estimateRawPromptTextTokens(serializedRequest)

fun applyAgentPromptCalibration(
    rawTokens: Int,
    calibration: AgentPromptCalibration
): Int = ceil(
    rawTokens.coerceAtLeast(0) *
        calibration.conservativeFactor.coerceIn(1.0, 3.0)
).toInt()

fun updateAgentPromptCalibration(
    existing: AgentPromptCalibration,
    rawSerializedRequestTokens: Int,
    actualInputTokens: Int
): AgentPromptCalibration {
    if (rawSerializedRequestTokens <= 0 || actualInputTokens <= 0) {
        return existing
    }
    val observedWithMargin = (
        actualInputTokens.toDouble() /
            rawSerializedRequestTokens.toDouble() *
            1.03
    ).coerceIn(1.0, 3.0)
    val nextSampleCount = existing.sampleCount + 1
    val nextFactor = when {
        observedWithMargin > existing.conservativeFactor ->
            observedWithMargin

        nextSampleCount >= 8 ->
            (
                existing.conservativeFactor * 0.98 +
                    observedWithMargin * 0.02
                ).coerceIn(1.0, 3.0)

        else ->
            existing.conservativeFactor
    }
    return AgentPromptCalibration(
        conservativeFactor = nextFactor,
        sampleCount = nextSampleCount,
        lastRawEstimate = rawSerializedRequestTokens,
        lastActualTokens = actualInputTokens
    )
}

fun agentPromptSha256(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

fun buildAgentPromptCalibrationKey(
    backend: String,
    endpointGeneration: String,
    model: String,
    toolDefinitionsHash: String,
    thinkingEnabled: Boolean
): String = agentPromptSha256(
    listOf(
        AGENT_PROMPT_BUDGET_VERSION.toString(),
        backend.trim().lowercase(),
        endpointGeneration.trim().lowercase(),
        model.trim().lowercase(),
        toolDefinitionsHash,
        thinkingEnabled.toString()
    ).joinToString("|")
)

object AgentPromptCalibrationStore {
    private const val PREFS_NAME = "agent_prompt_calibration_v4"

    fun load(context: Context, key: String): AgentPromptCalibration {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AgentPromptCalibration(
            conservativeFactor = prefs
                .getFloat("$key.factor", 1.0f)
                .toDouble()
                .coerceIn(1.0, 3.0),
            sampleCount = prefs.getInt("$key.samples", 0).coerceAtLeast(0),
            lastRawEstimate = prefs.getInt("$key.last_raw", 0).coerceAtLeast(0),
            lastActualTokens =
                prefs.getInt("$key.last_actual", 0).coerceAtLeast(0)
        )
    }

    fun update(
        context: Context,
        key: String,
        rawSerializedRequestTokens: Int,
        actualInputTokens: Int
    ): AgentPromptCalibration {
        val updated = updateAgentPromptCalibration(
            existing = load(context, key),
            rawSerializedRequestTokens = rawSerializedRequestTokens,
            actualInputTokens = actualInputTokens
        )
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat("$key.factor", updated.conservativeFactor.toFloat())
            .putInt("$key.samples", updated.sampleCount)
            .putInt("$key.last_raw", updated.lastRawEstimate)
            .putInt("$key.last_actual", updated.lastActualTokens)
            .apply()
        return updated
    }
}

fun buildAgentPromptAtomicUnits(
    messages: List<AgentService.Companion.ChatMessage>
): List<AgentPromptAtomicUnit> {
    if (messages.isEmpty()) return emptyList()
    val result = mutableListOf<AgentPromptAtomicUnit>()
    var index = 0

    while (index < messages.size) {
        val message = messages[index]
        val toolCallIds = buildSet {
            message.toolCallId?.takeIf { it.isNotBlank() }?.let(::add)
            message.pendingToolCall?.id
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }

        if (message.role == "assistant" && toolCallIds.isNotEmpty()) {
            val grouped = mutableListOf(message)
            var cursor = index + 1
            while (cursor < messages.size) {
                val next = messages[cursor]
                if (
                    next.role == "tool" &&
                    next.toolCallId != null &&
                    next.toolCallId in toolCallIds
                ) {
                    grouped += next
                    cursor += 1
                } else {
                    break
                }
            }
            result += AgentPromptAtomicUnit(
                id = "tool:${toolCallIds.sorted().joinToString(",")}",
                kind = AgentPromptUnitKind.TOOL_EXCHANGE,
                messages = grouped
            )
            index = cursor
            continue
        }

        val kind = when (message.role) {
            "user" -> AgentPromptUnitKind.USER_MESSAGE
            "assistant" -> AgentPromptUnitKind.ASSISTANT_RESPONSE
            "system" -> AgentPromptUnitKind.SYSTEM_CONTROL
            "tool" -> AgentPromptUnitKind.TOOL_EXCHANGE
            else -> AgentPromptUnitKind.OTHER
        }
        result += AgentPromptAtomicUnit(
            id = "${kind.name.lowercase()}:${message.id}",
            kind = kind,
            messages = listOf(message)
        )
        index += 1
    }
    return result
}

fun resolveHardCompactionRecentTailBudget(
    maximumInputTokens: Int,
    requiredPrimacyTokens: Int,
    toolSchemaTokens: Int
): Int {
    val available = maximumInputTokens.coerceAtLeast(0)
    val hardTarget = (available * 0.50).roundToInt()
    val summaryTarget = (available * 0.15).roundToInt()
    return (
        hardTarget -
            requiredPrimacyTokens.coerceAtLeast(0) -
            toolSchemaTokens.coerceAtLeast(0) -
            summaryTarget
    ).coerceAtLeast(0)
}
