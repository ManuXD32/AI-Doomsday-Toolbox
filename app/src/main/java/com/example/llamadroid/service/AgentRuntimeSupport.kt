package com.example.llamadroid.service

import com.example.llamadroid.data.db.CustomToolEntity
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.defaultLiteRtChatContextTokens
import com.example.llamadroid.data.model.defaultLiteRtEngineMaxTokens
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class ToolRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

enum class CustomToolExecutionMode {
    ARGV,
    SHELL
}

enum class RetrievedContextSourceClass {
    TRUSTED_RUNTIME_STATE,
    PROJECT_CODE,
    UNTRUSTED_FETCHED_CONTENT,
    GENERATED_MEMORY_SUMMARY
}

data class NormalizedAgentInvocationName(
    val displayName: String,
    val key: String
)

/** Keeps model-provided invocation labels safe while allowing Room to suffix duplicates. */
fun normalizeAgentInvocationName(rawName: String): NormalizedAgentInvocationName? {
    val displayName = rawName
        .filterNot { it.isISOControl() }
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(40)
        .trim()
    if (displayName.isBlank()) return null
    return NormalizedAgentInvocationName(displayName, displayName.lowercase(Locale.ROOT))
}

fun boundedStreamingPreview(raw: String, maxChars: Int, tailChars: Int = maxChars / 5): String {
    require(maxChars > 0 && tailChars in 0 until maxChars)
    if (raw.length <= maxChars) return raw
    val headChars = (maxChars - tailChars - 3).coerceAtLeast(1)
    return raw.take(headChars) + "\n…\n" + raw.takeLast(tailChars)
}

/**
 * Selects a rollover body that cannot immediately cross the same trigger again.
 * Keeping a heading is useful for Markdown/event files, but it still counts toward the cap.
 */
internal fun selectMemoryRolloverLines(
    lines: List<String>,
    sizeBudgetLines: Int,
    rolloverTriggerLines: Int,
    preserveFirstLine: Boolean
): List<String> {
    val retainedLimit = minOf(sizeBudgetLines, rolloverTriggerLines).coerceAtLeast(1)
    if (lines.size <= retainedLimit) return lines
    return if (preserveFirstLine && retainedLimit > 1) {
        listOf(lines.first()) + lines.takeLast(retainedLimit - 1)
    } else {
        lines.takeLast(retainedLimit)
    }
}

/** Kept outside the frozen prefix so Plan → Build preserves prompt-cache ownership. */
internal fun buildAgentRuntimeModeControl(isPlanMode: Boolean, isOrchestrator: Boolean): String =
    when {
        isPlanMode && isOrchestrator ->
            "CURRENT RUNTIME MODE: PLAN. Keep the project read-only. Delegate work as bounded planning microsteps, one at a time, with call_agent to CODEBASE_SCOUT for repository inspection, RESEARCHER for external/Kiwix/knowledge-base research, or PLANNER for plan synthesis; omit todo_id. Build workers and mutation tools remain blocked. Ask at least one structured user question, then submit one propose_plan and wait."
        isPlanMode ->
            "CURRENT RUNTIME MODE: PLAN. Complete only the assigned read-only discovery, research, or planning task. Do not mutate files, memory, media, dependencies, commands, or project execution. Return a structured finish_task report to the Orchestrator."
        isOrchestrator ->
            "CURRENT RUNTIME MODE: BUILD. Follow the approved durable TODO state. First call todo_write to create or update the durable TODO list, then delegate exactly one current TODO at a time, reread project_state after each specialist report, and finalize only after required review and verification."
        else ->
            "CURRENT RUNTIME MODE: BUILD. Complete only the assigned TODO and return a structured report to the Orchestrator."
    }

/** Tool schemas are prefix-stable across modes; Plan safety is enforced at execution time. */
internal fun <T> stableAgentToolSchemaAcrossModes(tools: List<T>, @Suppress("UNUSED_PARAMETER") isPlanMode: Boolean): List<T> = tools


internal enum class AgentTerminalKind {
    SUCCESS,
    BLOCKED,
    CANCELLED,
    INTERRUPTED,
    FAILED
}

internal data class AgentTerminalPresentation(
    val kind: AgentTerminalKind,
    val invocationStatus: String,
    val envelopeStatus: String,
    val continuationLabel: String
)

internal fun resolveAgentTerminalPresentation(rawStatus: String?): AgentTerminalPresentation =
    when (rawStatus?.trim()?.uppercase(Locale.ROOT)) {
        "SUCCESS", "COMPLETED", "PASSED", "PASS" -> AgentTerminalPresentation(
            kind = AgentTerminalKind.SUCCESS,
            invocationStatus = "COMPLETED",
            envelopeStatus = "ok",
            continuationLabel = "completed successfully"
        )
        "BLOCKED" -> AgentTerminalPresentation(
            kind = AgentTerminalKind.BLOCKED,
            invocationStatus = "BLOCKED",
            envelopeStatus = "blocked",
            continuationLabel = "reported a blocker"
        )
        "CANCELLED", "CANCELED" -> AgentTerminalPresentation(
            kind = AgentTerminalKind.CANCELLED,
            invocationStatus = "CANCELLED",
            envelopeStatus = "cancelled",
            continuationLabel = "was cancelled"
        )
        "INTERRUPTED" -> AgentTerminalPresentation(
            kind = AgentTerminalKind.INTERRUPTED,
            invocationStatus = "INTERRUPTED",
            envelopeStatus = "error",
            continuationLabel = "was interrupted"
        )
        else -> AgentTerminalPresentation(
            kind = AgentTerminalKind.FAILED,
            invocationStatus = "FAILED",
            envelopeStatus = "error",
            continuationLabel = "failed"
        )
    }

/**
 * A specialist may terminate because the backend stopped before it emitted the
 * required finish_task JSON. Never infer success from ordinary prose or from a
 * role parser rejecting an otherwise explicit FAILED/BLOCKED terminal object.
 */
internal fun inferAgentTerminalStatusFromSummary(rawSummary: String): String {
    val trimmed = rawSummary.trim()
    val explicit = if (trimmed.startsWith("{")) {
        runCatching {
            JSONObject(trimmed)
                .optString("status", "")
                .trim()
                .uppercase(Locale.ROOT)
        }.getOrNull()
    } else {
        null
    }
    return when (explicit) {
        // This helper runs only after the role-specific parser rejected the
        // payload. An explicit success that does not satisfy the role contract
        // is therefore a failure, never a completed delegation.
        "SUCCESS", "COMPLETED", "PASSED", "PASS" -> "FAILED"
        "BLOCKED" -> "BLOCKED"
        "CANCELLED", "CANCELED" -> "CANCELLED"
        "INTERRUPTED" -> "INTERRUPTED"
        "FAILED" -> "FAILED"
        else -> "FAILED"
    }
}

internal data class AgentToolPolicyDecision(
    val allowed: Boolean,
    val code: String,
    val message: String = "",
    val recoveryHint: String = ""
)

internal class AgentToolPolicyException(
    val decision: AgentToolPolicyDecision
) : IllegalStateException(decision.message) {
    val policyCode: String get() = decision.code
    val recoveryHint: String get() = decision.recoveryHint
}

internal class AgentDelegationStartException(
    val agentLabel: String,
    cause: Throwable
) : IllegalStateException(
    "Agent $agentLabel failed to start: " +
        (cause.message ?: cause.javaClass.simpleName),
    cause
)

private val PLAN_MODE_PLANNING_AGENT_ALIASES = mapOf(
    "CODEBASE_SCOUT" to "CODEBASE_SCOUT",
    "SCOUT" to "CODEBASE_SCOUT",
    "RESEARCHER" to "RESEARCHER",
    "RESEARCH" to "RESEARCHER",
    "PLANNER" to "PLANNER"
)

private val PLAN_MODE_MUTATING_TOOLS = setOf(
    "write_file",
    "edit_lines",
    "apply_patch",
    "create_folder",
    "run_command",
    "cancel_command",
    "send_command_input",
    "generate_image",
    "remove_image_background",
    "run_project",
    "stop_project_run",
    "force_stop_project_run",
    "install_python_dependency",
    "write_memory",
    "rewrite_memory",
    "delete_memory",
    "run_skill_script"
)

private val PLAN_SAFE_CUSTOM_AGENT_TOOLS = setOf(
    "read_file",
    "read_file_lines",
    "file_line_count",
    "list_directory",
    "search_code",
    "view_image",
    "read_memory",
    "list_memory",
    "project_state_read",
    "project_order_read",
    "plan_read",
    "agent_report_read",
    "todo_read",
    "reflection",
    "get_datetime",
    "web_search",
    "fetch_url",
    "kiwix_search",
    "kb_search",
    "kb_read_chunk",
    "kb_list_sources",
    "run_tools_sequential",
    "skill",
    "read_skill_resource",
    "finish_task"
)

private val CRITICAL_AGENT_PROTOCOL_TOOLS = setOf(
    "question",
    "call_agent",
    "propose_plan",
    "finish_task",
    "project_state_read",
    "todo_read",
    "todo_transition"
)

internal fun isCriticalAgentProtocolTool(toolName: String): Boolean =
    toolName.trim().lowercase(Locale.ROOT) in CRITICAL_AGENT_PROTOCOL_TOOLS

internal fun isPlanSafeCustomAgentToolSet(configuredTools: Set<String>): Boolean {
    if (configuredTools.isEmpty()) return false
    return configuredTools
        .map { it.trim().lowercase(Locale.ROOT) }
        .all { it in PLAN_SAFE_CUSTOM_AGENT_TOOLS }
}

internal fun evaluatePlanModeToolPolicy(
    isPlanMode: Boolean,
    toolName: String,
    arguments: Map<String, String>,
    planSafeCustomAgentNames: Set<String> = emptySet()
): AgentToolPolicyDecision {
    if (!isPlanMode) {
        return AgentToolPolicyDecision(true, "ALLOWED_BUILD_MODE")
    }

    val normalizedTool = toolName.trim().lowercase(Locale.ROOT)
    if (normalizedTool == "call_agent") {
        val requested = arguments["agent"]
            ?.trim()
            ?.uppercase(Locale.ROOT)
            .orEmpty()
        if (requested.isBlank()) {
            // Required-argument validation produces the precise schema error later.
            return AgentToolPolicyDecision(true, "ALLOWED_PENDING_SCHEMA_VALIDATION")
        }
        if (!arguments["todo_id"].isNullOrBlank()) {
            return AgentToolPolicyDecision(
                allowed = false,
                code = "PLAN_MODE_TODO_DELEGATION_BLOCKED",
                message = "Plan-mode discovery and research delegations must not claim a Build TODO.",
                recoveryHint = "Remove todo_id and delegate one bounded read-only task to CODEBASE_SCOUT, RESEARCHER, or PLANNER. Switch to Build mode before claiming a TODO."
            )
        }
        val planningRole = PLAN_MODE_PLANNING_AGENT_ALIASES[requested]
        val normalizedCustomNames = planSafeCustomAgentNames
            .map { it.trim().uppercase(Locale.ROOT) }
            .toSet()
        if (planningRole != null || requested in normalizedCustomNames) {
            return AgentToolPolicyDecision(true, "ALLOWED_PLAN_RESEARCH_DELEGATION")
        }
        return AgentToolPolicyDecision(
            allowed = false,
            code = "PLAN_MODE_AGENT_BLOCKED",
            message = "Plan mode permits only read-only CODEBASE_SCOUT, RESEARCHER, PLANNER, or explicitly read-only custom-agent delegations. Requested agent: $requested.",
            recoveryHint = "Choose CODEBASE_SCOUT for repository inspection, RESEARCHER for external or knowledge-base research, or PLANNER for plan synthesis. Do not retry the blocked worker unchanged."
        )
    }

    if (normalizedTool in PLAN_MODE_MUTATING_TOOLS) {
        return AgentToolPolicyDecision(
            allowed = false,
            code = "PLAN_MODE_MUTATION_BLOCKED",
            message = "Plan mode is read-only. Tool `$toolName` cannot mutate files, memory, media, dependencies, commands, or project execution.",
            recoveryHint = "Use a read-only inspection or research action, ask the user a structured question, or propose the plan. Switch to Build mode before mutation."
        )
    }

    return AgentToolPolicyDecision(true, "ALLOWED_PLAN_READ_ONLY_TOOL")
}

internal data class PlanApprovalPromptCacheDecision(
    val summary: String,
    val modifiedPlanForToolResult: String?,
    val retainsRootCacheEpoch: Boolean = true
)

internal fun planApprovalPromptCacheDecision(originalPlan: String, approvedPlan: String): PlanApprovalPromptCacheDecision {
    val wasEdited = approvedPlan.trim() != originalPlan.trim()
    return PlanApprovalPromptCacheDecision(
        summary = if (wasEdited) "Implement the modified plan." else "Implement the plan.",
        modifiedPlanForToolResult = approvedPlan.trim().takeIf { wasEdited }
    )
}

data class ToolCapabilityPolicy(
    val agentLabel: String,
    val allowedToolNames: Set<String>,
    val canDelegate: Boolean,
    val modelOverride: String? = null,
    val customAgentName: String? = null
)

data class CustomToolParameterSpec(
    val description: String,
    val maxLength: Int? = null,
    val enumValues: List<String> = emptyList()
)

data class ValidatedToolCall(
    val toolCall: OllamaService.ToolCall,
    val tool: AgentTool,
    val normalizedArguments: Map<String, String>,
    val riskLevel: ToolRiskLevel,
    val approvalRequired: Boolean,
    val customTool: CustomToolEntity? = null,
    val customExecutionMode: CustomToolExecutionMode? = null,
    val workingDirectory: String? = null
)

data class LoadingCounterUpdate(
    val count: Int,
    val wasClamped: Boolean
)

internal fun shouldPersistFullAgentSnapshot(
    reason: String?,
    force: Boolean
): Boolean {
    if (force) return true
    // Finalized message mutations are semantic recovery boundaries. AgentService
    // coalesces them through a short debounce and upserts only changed rows, so they
    // survive process death without writing on every streaming token or heartbeat.
    return reason in setOf(
        "Agent message added",
        "Agent message updated",
        "Conversation history replaced",
        "Conversation history truncated",
        "Regenerate history truncated"
    )
}

data class FileEditComputation(
    val updatedContent: String,
    val originalLineCount: Int,
    val insertedLineCount: Int,
    val preservedTrailingNewline: Boolean
)

data class CompactPromptBasisSections(
    val requiredSections: List<String>,
    val optionalSections: List<String>
)

data class TokenBudgetedRecentTail(
    val splitIndex: Int,
    val summarizedCount: Int,
    val recentCount: Int,
    val recentTokenEstimate: Int,
    val targetRecentTokens: Int
)

fun buildCompactPromptBasisSections(
    systemPrompt: String,
    initialOrder: String,
    planContent: String?,
    compactionSummary: String,
    compactStateSnapshot: String?
): CompactPromptBasisSections {
    val required = buildList {
        add(systemPrompt)
        AgentProjectControlPlane.compactDocumentReference(
            title = "Initial Order",
            content = initialOrder,
            maxChars = 1_400
        )?.let(::add)
        AgentProjectControlPlane.compactDocumentReference(
            title = "Plan",
            content = planContent,
            maxChars = 2_000
        )?.let(::add)
        add(compactionSummary.take(8_000))
    }
    val optional = listOfNotNull(
        compactStateSnapshot
            ?.takeIf { it.isNotBlank() }
            ?.take(12_000)
    )
    return CompactPromptBasisSections(
        requiredSections = required,
        optionalSections = optional
    )
}



fun buildHardCompactionSummaryDocument(
    generatedAt: String,
    summarizedMessageCount: Int = 0,
    retainedRecentMessageCount: Int = 0,
    retainedRecentTokenEstimate: Int = 0,
    retainedRecentTargetTokens: Int = 0,
    planCoverageLabel: String?,
    completedPlanItems: List<String>,
    missingPlanItems: List<String>,
    tasksDone: List<String>,
    readFiles: List<String> = emptyList(),
    changedFiles: List<String>,
    importantFindings: List<String>,
    openRisks: List<String>,
    activeCommands: List<String>,
    carryForward: List<String>
): String {
    fun section(title: String, items: List<String>): String {
        return buildString {
            appendLine("## $title")
            if (items.isEmpty()) {
                appendLine("- none")
            } else {
                items.forEach { appendLine("- $it") }
            }
        }.trimEnd()
    }

    return buildString {
        appendLine("# Context Compaction Summary")
        appendLine()
        appendLine("Generated at: $generatedAt")
        appendLine()
        appendLine("## Compaction Window")
        appendLine("- Summarized older messages: $summarizedMessageCount")
        appendLine("- Retained recent messages: $retainedRecentMessageCount")
        appendLine("- Retained recent token estimate: $retainedRecentTokenEstimate / $retainedRecentTargetTokens target")
        appendLine()
        appendLine("## Implementation Plan Status")
        if (planCoverageLabel == null) {
            appendLine("- No approved plan.md was available.")
        } else {
            appendLine("- Coverage: $planCoverageLabel")
            appendLine("- Completed plan items:")
            if (completedPlanItems.isEmpty()) appendLine("  - none evidenced")
            completedPlanItems.forEach { appendLine("  - $it") }
            appendLine("- Missing plan items:")
            if (missingPlanItems.isEmpty()) appendLine("  - none")
            missingPlanItems.forEach { appendLine("  - $it") }
        }
        appendLine()
        appendLine(section("Tasks Done", tasksDone))
        appendLine()
        appendLine(section("Files Read / Referenced", readFiles))
        appendLine()
        appendLine(section("Files/Artifacts Changed", changedFiles))
        appendLine()
        appendLine(section("Important Findings / Decisions", importantFindings))
        appendLine()
        appendLine(section("Open Risks / Missing Work", openRisks))
        appendLine()
        appendLine(section("Active Commands / Pending Approvals", activeCommands))
        appendLine()
        appendLine(section("Carry Forward For Next Turns", carryForward))
    }.trim()
}

fun computeHistoryTokenBudget(targetTokens: Int, pinnedBudget: Int): Int {
    return (targetTokens - pinnedBudget).coerceAtLeast(0)
}

fun selectTokenBudgetedRecentTail(
    messageTokenEstimates: List<Int>,
    tokenLimit: Int,
    recentTailFraction: Double = 0.40,
    minRecentMessages: Int = 1
): TokenBudgetedRecentTail {
    if (messageTokenEstimates.isEmpty()) {
        return TokenBudgetedRecentTail(
            splitIndex = 0,
            summarizedCount = 0,
            recentCount = 0,
            recentTokenEstimate = 0,
            targetRecentTokens = 0
        )
    }

    val targetRecentTokens = (tokenLimit.coerceAtLeast(1).toDouble() * recentTailFraction)
        .roundToInt()
        .coerceAtLeast(1)
    val minimumRecent = minRecentMessages.coerceAtLeast(1)
    var splitIndex = messageTokenEstimates.size
    var recentTokenEstimate = 0

    for (index in messageTokenEstimates.indices.reversed()) {
        val messageTokens = messageTokenEstimates[index].coerceAtLeast(1)
        val alreadyKept = messageTokenEstimates.size - splitIndex
        if (alreadyKept >= minimumRecent && recentTokenEstimate + messageTokens > targetRecentTokens) {
            break
        }
        recentTokenEstimate += messageTokens
        splitIndex = index
    }

    return TokenBudgetedRecentTail(
        splitIndex = splitIndex,
        summarizedCount = splitIndex,
        recentCount = messageTokenEstimates.size - splitIndex,
        recentTokenEstimate = recentTokenEstimate,
        targetRecentTokens = targetRecentTokens
    )
}

fun shouldRecordPromptCompactionEvent(
    rawEstimatedTokens: Int,
    packedEstimatedTokens: Int,
    omittedCount: Int,
    compactionPasses: Int,
    didCompactHistory: Boolean
): Boolean {
    if (!didCompactHistory) return false
    return omittedCount > 0 || packedEstimatedTokens < rawEstimatedTokens || compactionPasses > 1
}

fun shouldWriteRuntimeCheckpoint(
    nowMs: Long,
    lastCheckpointMs: Long,
    intervalMs: Long,
    force: Boolean
): Boolean {
    return force || nowMs - lastCheckpointMs >= intervalMs
}

fun isBackgroundCommandReminder(
    toolName: String?,
    content: String,
    toolOutput: String? = null
): Boolean {
    val commandToolNames = setOf(
        "run_command",
        "check_command",
        "wait_command",
        "command_list",
        "cancel_command",
        "send_command_input"
    )
    if (toolName.isNullOrBlank() || toolName !in commandToolNames) return false

    val combinedText = buildString {
        append(content)
        if (!toolOutput.isNullOrBlank()) {
            if (isNotEmpty()) append('\n')
            append(toolOutput)
        }
    }

    return isStructuredCommandResult(combinedText) &&
        (
            combinedText.contains("Status: running", ignoreCase = true) ||
                combinedText.contains("Command is still running", ignoreCase = true)
        )
}

fun isStructuredCommandResult(content: String): Boolean {
    return content.contains("Command ID:", ignoreCase = true) &&
        content.contains("Status:", ignoreCase = true) &&
        content.contains("Requested tail lines:", ignoreCase = true) &&
        content.contains("Output:", ignoreCase = true)
}

fun commandOutputTailLines(
    completedLines: List<String>,
    pendingLine: String,
    requestedLines: Int
): List<String> {
    val boundedLines = requestedLines.coerceAtLeast(1)
    val visiblePendingLine = pendingLine
        .removeSuffix("\n")
        .removeSuffix("\r")
        .takeIf { it.isNotEmpty() }
    return buildList {
        addAll(completedLines)
        if (visiblePendingLine != null) {
            add(visiblePendingLine)
        }
    }.takeLast(boundedLines)
}

fun containsTraversalSegments(path: String): Boolean {
    return path
        .replace('\\', '/')
        .split('/')
        .any { it == ".." }
}

fun resolveChatNumCtx(baseNumCtx: Int, overrideNumCtx: Int? = null): Int {
    return overrideNumCtx ?: baseNumCtx
}

fun friendlyBackendModelLabel(rawLabel: String?): String? {
    val trimmed = rawLabel?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalized = trimmed.trimEnd('/', '\\')
    val separatorIndex = normalized.lastIndexOfAny(charArrayOf('/', '\\'))
    return if (separatorIndex >= 0 && separatorIndex < normalized.lastIndex) {
        normalized.substring(separatorIndex + 1)
    } else {
        normalized
    }
}

fun resolveAgentLiteRtContextTokens(
    savedContextTokens: Int,
    model: LiteRtModelEntity?,
    fallbackContextTokens: Int = AGENT_LITERT_FALLBACK_CONTEXT_TOKENS,
    minContextTokens: Int = AGENT_LITERT_MIN_CONTEXT_TOKENS,
    safeMaxContextTokens: Int = AGENT_LITERT_SAFE_MAX_CONTEXT_TOKENS
): Int {
    val advertisedCap = (model?.defaultLiteRtEngineMaxTokens() ?: fallbackContextTokens)
        .coerceAtLeast(minContextTokens)
    val safeCap = safeMaxContextTokens
        .takeIf { it > 0 }
        ?.coerceAtLeast(minContextTokens)
        ?: advertisedCap
    val cap = minOf(advertisedCap, safeCap).coerceAtLeast(minContextTokens)
    val defaultContext = (model?.defaultLiteRtChatContextTokens() ?: cap)
        .coerceIn(minContextTokens, cap)
    return savedContextTokens
        .takeIf { it > 0 }
        ?.coerceIn(minContextTokens, cap)
        ?: defaultContext
}

fun resolveAgentLiteRtMaxOutputTokens(
    savedMaxOutputTokens: Int,
    resolvedContextTokens: Int,
    model: LiteRtModelEntity?,
    fallbackMaxOutputTokens: Int = AGENT_LITERT_FALLBACK_MAX_OUTPUT_TOKENS,
    minMaxOutputTokens: Int = AGENT_LITERT_MIN_MAX_OUTPUT_TOKENS
): Int {
    val contextCap = listOfNotNull(
        resolvedContextTokens.takeIf { it > 0 },
        model?.defaultLiteRtEngineMaxTokens()?.takeIf { it > 0 }
    ).minOrNull()
        ?: AGENT_LITERT_FALLBACK_CONTEXT_TOKENS
    val outputCap = contextCap.coerceAtLeast(minMaxOutputTokens)
    val defaultOutput = fallbackMaxOutputTokens.coerceIn(minMaxOutputTokens, outputCap)
    return savedMaxOutputTokens
        .takeIf { it > 0 }
        ?.coerceIn(minMaxOutputTokens, outputCap)
        ?: defaultOutput
}

/**
 * Resolves the user-configured output budget independently from the model
 * context, then clamps the effective request to the remaining prompt space.
 *
 * The configured value is intentionally not modified when the current prompt is
 * large; callers can continue to display and persist the user's original choice.
 */
fun resolveAgentEffectiveMaxOutputTokens(
    configuredMaxOutputTokens: Int,
    contextTokens: Int,
    estimatedPromptTokens: Int,
    fallbackMaxOutputTokens: Int = AGENT_DEFAULT_MAX_OUTPUT_TOKENS,
    templateSafetyReserveTokens: Int = AGENT_OUTPUT_TEMPLATE_SAFETY_RESERVE_TOKENS
): Int {
    val configured = configuredMaxOutputTokens
        .takeIf { it > 0 }
        ?: fallbackMaxOutputTokens
    if (contextTokens <= 0) return configured.coerceAtLeast(1)

    val remaining = contextTokens -
        estimatedPromptTokens.coerceAtLeast(0) -
        templateSafetyReserveTokens.coerceAtLeast(0)
    return minOf(configured, remaining.coerceAtLeast(1)).coerceAtLeast(1)
}

fun shouldScheduleHardCompaction(
    percentUsed: Int,
    thresholdPercent: Int,
    emergencyThresholdPercent: Int,
    hardCompactionActive: Boolean,
    completedTurnGroupsSinceLastCompaction: Int,
    minTurnGroupsBetweenCompactions: Int
): Boolean {
    if (percentUsed < thresholdPercent) return false
    if (!hardCompactionActive) return true
    if (percentUsed >= emergencyThresholdPercent) return true
    return completedTurnGroupsSinceLastCompaction >= minTurnGroupsBetweenCompactions
}

fun stripHtmlTags(html: String): String {
    return html
        .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("&[a-zA-Z]+;"), " ")
        .replace(Regex("&#\\d+;"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun sanitizeTerminalTranscript(raw: String): String {
    fun consumeCsiSequence(text: String, start: Int, onSequence: (String, Char) -> Unit): Int {
        var index = start
        while (index < text.length) {
            val ch = text[index]
            if (ch in '@'..'~') {
                onSequence(text.substring(start, index), ch)
                return index + 1
            }
            index += 1
        }
        return text.length
    }

    fun consumeStringTerminatedSequence(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '\u0007' -> return index + 1
                '\u001B' -> {
                    if (index + 1 < text.length && text[index + 1] == '\\') {
                        return index + 2
                    }
                }
            }
            index += 1
        }
        return text.length
    }

    val output = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        when (val ch = raw[index]) {
            '\u001B' -> {
                index = when (raw.getOrNull(index + 1)) {
                    '[' -> consumeCsiSequence(raw, index + 2) { params, finalChar ->
                        if (finalChar == 'J' && (params.contains("2") || params.contains("3"))) {
                            output.setLength(0)
                        }
                    }
                    ']' -> consumeStringTerminatedSequence(raw, index + 2)
                    'P', '^', '_' -> consumeStringTerminatedSequence(raw, index + 2)
                    null -> raw.length
                    else -> (index + 2).coerceAtMost(raw.length)
                }
            }
            '\u009B' -> {
                index = consumeCsiSequence(raw, index + 1) { params, finalChar ->
                    if (finalChar == 'J' && (params.contains("2") || params.contains("3"))) {
                        output.setLength(0)
                    }
                }
            }
            '\b' -> {
                if (output.isNotEmpty()) {
                    output.deleteCharAt(output.length - 1)
                }
                index += 1
            }
            '\r' -> index += 1
            else -> {
                if (ch == '\n' || ch == '\t' || ch.code >= 0x20) {
                    output.append(ch)
                }
                index += 1
            }
        }
    }
    return output.toString()
}

private const val AGENT_LITERT_MIN_CONTEXT_TOKENS = 512
private const val AGENT_LITERT_FALLBACK_CONTEXT_TOKENS = 4000
private const val AGENT_LITERT_SAFE_MAX_CONTEXT_TOKENS = 8192
private const val AGENT_LITERT_MIN_MAX_OUTPUT_TOKENS = 128
private const val AGENT_LITERT_FALLBACK_MAX_OUTPUT_TOKENS = 8096
const val AGENT_DEFAULT_MAX_OUTPUT_TOKENS = 8096
const val AGENT_OUTPUT_TEMPLATE_SAFETY_RESERVE_TOKENS = 256

private val SEQUENTIAL_BATCH_BLOCKED_TOOLS = setOf(
    "write_file",
    "run_command",
    "edit_lines",
    "apply_patch",
    "create_folder",
    "generate_image",
    "remove_image_background",
    "run_project",
    "stop_project_run",
    "force_stop_project_run",
    "install_python_dependency",
    "view_image",
    "cancel_command",
    "send_command_input",
    "call_agent",
    "propose_plan",
    "finish_task",
    "reflection",
    "write_memory",
    "rewrite_memory",
    "delete_memory"
)

fun isSequentialBatchBlockedTool(toolName: String): Boolean {
    return toolName in SEQUENTIAL_BATCH_BLOCKED_TOOLS
}

data class AgentStateSnapshot(
    val currentGoal: String,
    val activeSessionId: String?,
    val currentAgent: String,
    val activeCommands: List<String>,
    val focusFiles: List<String>,
    val repoStatusSummary: String,
    val activeRisks: List<String>,
    val guardrails: List<String>,
    val memoryPressure: List<String>
) {
    fun toJson(): String {
        return JSONObject(
            linkedMapOf(
                "current_goal" to currentGoal,
                "active_session_id" to activeSessionId,
                "current_agent" to currentAgent,
                "active_commands" to activeCommands,
                "focus_files" to focusFiles,
                "repo_status_summary" to repoStatusSummary,
                "active_risks" to activeRisks,
                "guardrails" to guardrails,
                "memory_pressure" to memoryPressure
            )
        ).toString(2)
    }

    fun toPromptBlock(): String {
        return buildString {
            appendLine("AGENT STATE SNAPSHOT:")
            appendLine("- current_goal: $currentGoal")
            appendLine("- current_agent: $currentAgent")
            appendLine("- active_session_id: ${activeSessionId ?: "none"}")
            appendLine("- active_commands: ${activeCommands.joinToString().ifBlank { "none" }}")
            appendLine("- focus_files: ${focusFiles.joinToString().ifBlank { "none" }}")
            appendLine("- repo_status_summary: $repoStatusSummary")
            if (activeRisks.isNotEmpty()) {
                appendLine("- active_risks:")
                activeRisks.forEach { appendLine("  - $it") }
            }
            if (guardrails.isNotEmpty()) {
                appendLine("- guardrails:")
                guardrails.forEach { appendLine("  - $it") }
            }
            if (memoryPressure.isNotEmpty()) {
                appendLine("- memory_pressure:")
                memoryPressure.forEach { appendLine("  - $it") }
            }
        }.trim()
    }
}

data class RetrievedContextItem(
    val sourceClass: RetrievedContextSourceClass,
    val title: String,
    val content: String,
    val sourceRef: String? = null,
    val score: Int = 0
) {
    fun toPromptBlock(): String {
        return buildString {
            append("- [")
            append(sourceClass.name.lowercase())
            append("] ")
            append(title)
            sourceRef?.takeIf { it.isNotBlank() }?.let {
                append(" (")
                append(it)
                append(")")
            }
            append(": ")
            append(content)
        }
    }
}

data class AgentEvidenceBundle(
    val changedFiles: List<String> = emptyList(),
    val commandIds: List<String> = emptyList(),
    val lineReferences: List<String> = emptyList(),
    val memoryFilesTouched: List<String> = emptyList()
) {
    fun toPromptBlock(): String {
        return buildString {
            appendLine("Evidence bundle:")
            appendLine("- changed_files: ${changedFiles.joinToString().ifBlank { "none" }}")
            appendLine("- command_ids: ${commandIds.joinToString().ifBlank { "none" }}")
            appendLine("- line_references: ${lineReferences.joinToString().ifBlank { "none" }}")
            append("- memory_files_touched: ${memoryFilesTouched.joinToString().ifBlank { "none" }}")
        }.trim()
    }
}

sealed class AgentResult {
    abstract val status: String

    data class CoderResult(
        override val status: String,
        val changedFiles: List<String>,
        val intentPerFile: Map<String, String>,
        val verificationReads: List<String>,
        val remainingRisks: List<String>
    ) : AgentResult()

    data class ReviewerFinding(
        val file: String,
        val line: Int?,
        val severity: String,
        val description: String,
        val recommendation: String
    )

    data class ReviewerResult(
        override val status: String,
        val findings: List<ReviewerFinding>,
        val remainingRisks: List<String>
    ) : AgentResult()

    data class ExecutorResult(
        override val status: String,
        val commandsRun: List<String>,
        val commandIds: List<String>,
        val finalStatus: String,
        val keyOutputs: List<String>,
        val nextRecommendation: String
    ) : AgentResult()

    data class SummarizerResult(
        override val status: String,
        val memoryFilesUpdated: List<String>,
        val reasonPerFile: Map<String, String>,
        val carryForwardNotes: List<String>
    ) : AgentResult()

    data class ScoutResult(
        override val status: String,
        val relevantFiles: List<String>,
        val architecture: List<String>,
        val dependencies: List<String>,
        val constraints: List<String>,
        val risks: List<String>,
        val openQuestions: List<String>,
        val recommendedScope: List<String>
    ) : AgentResult()

    data class ResearcherResult(
        override val status: String,
        val researchQuestion: String,
        val sources: List<String>,
        val facts: List<String>,
        val conflicts: List<String>,
        val uncertainties: List<String>,
        val recommendations: List<String>
    ) : AgentResult()

    data class PlannerResult(
        override val status: String,
        val planMarkdown: String,
        val structuredPlanJson: String,
        val openQuestions: List<String>,
        val recommendedNextSteps: List<String>
    ) : AgentResult()

    data class GenericResult(
        override val status: String,
        val summary: String
    ) : AgentResult()

    fun toParentFacingSummary(agentLabel: String, evidence: AgentEvidenceBundle): String {
        return buildString {
            appendLine("$agentLabel result:")
            when (this@AgentResult) {
                is CoderResult -> {
                    appendLine("- status: $status")
                    appendLine("- changed_files: ${changedFiles.joinToString().ifBlank { "none" }}")
                    if (intentPerFile.isNotEmpty()) {
                        appendLine("- intent_per_file:")
                        intentPerFile.forEach { (file, intent) -> appendLine("  - $file: $intent") }
                    }
                    appendLine("- verification_reads: ${verificationReads.joinToString().ifBlank { "none" }}")
                    append("- remaining_risks: ${remainingRisks.joinToString().ifBlank { "none" }}")
                }
                is ReviewerResult -> {
                    appendLine("- status: $status")
                    if (findings.isEmpty()) {
                        appendLine("- findings: none")
                    } else {
                        appendLine("- findings:")
                        findings.forEach { finding ->
                            appendLine("  - ${finding.severity} ${finding.file}${finding.line?.let { ":$it" } ?: ""}: ${finding.description} | ${finding.recommendation}")
                        }
                    }
                    append("- remaining_risks: ${remainingRisks.joinToString().ifBlank { "none" }}")
                }
                is ExecutorResult -> {
                    appendLine("- status: $status")
                    appendLine("- final_status: $finalStatus")
                    appendLine("- commands_run: ${commandsRun.joinToString().ifBlank { "none" }}")
                    appendLine("- command_ids: ${commandIds.joinToString().ifBlank { "none" }}")
                    appendLine("- key_outputs: ${keyOutputs.joinToString().ifBlank { "none" }}")
                    append("- next_recommendation: $nextRecommendation")
                }
                is SummarizerResult -> {
                    appendLine("- status: $status")
                    appendLine("- memory_files_updated: ${memoryFilesUpdated.joinToString().ifBlank { "none" }}")
                    if (reasonPerFile.isNotEmpty()) {
                        appendLine("- reason_per_file:")
                        reasonPerFile.forEach { (file, reason) -> appendLine("  - $file: $reason") }
                    }
                    append("- carry_forward_notes: ${carryForwardNotes.joinToString().ifBlank { "none" }}")
                }
                is ScoutResult -> {
                    appendLine("- status: $status")
                    appendLine("- relevant_files: ${relevantFiles.joinToString().ifBlank { "none" }}")
                    appendLine("- architecture: ${architecture.joinToString().ifBlank { "none" }}")
                    appendLine("- dependencies: ${dependencies.joinToString().ifBlank { "none" }}")
                    appendLine("- constraints: ${constraints.joinToString().ifBlank { "none" }}")
                    appendLine("- risks: ${risks.joinToString().ifBlank { "none" }}")
                    appendLine("- open_questions: ${openQuestions.joinToString().ifBlank { "none" }}")
                    append("- recommended_scope: ${recommendedScope.joinToString().ifBlank { "none" }}")
                }
                is ResearcherResult -> {
                    appendLine("- status: $status")
                    appendLine("- research_question: $researchQuestion")
                    appendLine("- sources: ${sources.joinToString().ifBlank { "none" }}")
                    appendLine("- facts: ${facts.joinToString().ifBlank { "none" }}")
                    appendLine("- conflicts: ${conflicts.joinToString().ifBlank { "none" }}")
                    appendLine("- uncertainties: ${uncertainties.joinToString().ifBlank { "none" }}")
                    append("- recommendations: ${recommendations.joinToString().ifBlank { "none" }}")
                }
                is PlannerResult -> {
                    appendLine("- status: $status")
                    appendLine("- plan_markdown: ${planMarkdown.take(1_200)}")
                    appendLine("- structured_plan_json: ${structuredPlanJson.take(1_200)}")
                    appendLine("- open_questions: ${openQuestions.joinToString().ifBlank { "none" }}")
                    append("- recommended_next_steps: ${recommendedNextSteps.joinToString().ifBlank { "none" }}")
                }
                is GenericResult -> {
                    appendLine("- status: $status")
                    append("- summary: $summary")
                }
            }
            appendLine()
            append(evidence.toPromptBlock())
        }.trim()
    }
}

data class CompletedAgentSession(
    val sessionId: String,
    val agentLabel: String,
    val customAgentName: String? = null,
    val rawSummary: String,
    val result: AgentResult,
    val evidence: AgentEvidenceBundle
)

data class ToolAuditRecord(
    val eventType: String,
    val toolName: String? = null,
    val backend: String? = null,
    val model: String? = null,
    val packedTokenEstimate: Int? = null,
    val actualTokenCount: Int? = null,
    val validationResult: String? = null,
    val approvalDecision: String? = null,
    val commandArgv: List<String> = emptyList(),
    val commandCwd: String? = null,
    val mutatedFiles: List<String> = emptyList(),
    val memorySnapshotVersion: String? = null,
    val notes: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJsonLine(): String {
        return JSONObject(
            linkedMapOf(
                "event_type" to eventType,
                "tool_name" to toolName,
                "backend" to backend,
                "model" to model,
                "packed_token_estimate" to packedTokenEstimate,
                "actual_token_count" to actualTokenCount,
                "validation_result" to validationResult,
                "approval_decision" to approvalDecision,
                "command_argv" to commandArgv,
                "command_cwd" to commandCwd,
                "mutated_files" to mutatedFiles,
                "memory_snapshot_version" to memorySnapshotVersion,
                "notes" to notes,
                "timestamp" to timestamp
            )
        ).toString()
    }
}

data class MemoryFilePolicy(
    val sizeBudgetLines: Int,
    val rolloverTriggerLines: Int? = null,
    val consolidationTriggerLines: Int? = null
)

internal object AgentRuntimeSupport {
    data class ContinuationGuardDecision(
        val shouldPause: Boolean,
        val reason: String?
    )

    fun evaluateContinuationGuard(
        continuationCount: Int,
        queueDepth: Int,
        maxContinuations: Int,
        maxQueueDepth: Int,
        reason: String,
        consecutiveNoProgress: Int = 0,
        maxNoProgress: Int = 4
    ): ContinuationGuardDecision {
        val tooManyContinuations = continuationCount > maxContinuations
        val queueTooDeep = queueDepth > maxQueueDepth
        val repeatedNoProgress = consecutiveNoProgress > maxNoProgress
        return if (tooManyContinuations || queueTooDeep || repeatedNoProgress) {
            ContinuationGuardDecision(
                shouldPause = true,
                reason = "reason=${reason.take(120)} continuations=$continuationCount/$maxContinuations " +
                    "queueDepth=$queueDepth/$maxQueueDepth noProgress=$consecutiveNoProgress/$maxNoProgress"
            )
        } else {
            ContinuationGuardDecision(shouldPause = false, reason = null)
        }
    }

    fun shouldInjectQueuedUserGuidance(
        pendingCount: Int,
        toolCallDetected: Boolean,
        toolResultCommitted: Boolean,
        modelTurnCompleted: Boolean
    ): Boolean {
        if (pendingCount <= 0) return false
        if (toolResultCommitted) return true
        return modelTurnCompleted && !toolCallDetected
    }

    private val PLACEHOLDER_REGEX = Regex("""\{([a-zA-Z0-9_]+)\}""")
    private val SHELL_EXPLICIT_PREFIX = "shell:"
    private val SEQUENTIAL_BATCH_BLOCKED_TOOLS = setOf(
        "write_file",
        "run_command",
        "edit_lines",
        "apply_patch",
        "create_folder",
        "generate_image",
        "remove_image_background",
        "view_image",
        "cancel_command",
        "send_command_input",
        "call_agent",
        "propose_plan",
        "finish_task",
        "reflection",
        "write_memory",
        "rewrite_memory",
        "delete_memory",
        "todo_write",
        "todo_reconcile",
        "todo_transition"
    )

    fun containsTraversalSegments(path: String): Boolean {
        return path
            .replace('\\', '/')
            .split('/')
            .any { it == ".." }
    }

    fun resolveChatNumCtx(baseNumCtx: Int, overrideNumCtx: Int? = null): Int {
        return overrideNumCtx ?: baseNumCtx
    }

    fun friendlyBackendModelLabel(rawLabel: String?): String? =
        com.example.llamadroid.service.friendlyBackendModelLabel(rawLabel)

    fun resolveAgentLiteRtContextTokens(
        savedContextTokens: Int,
        model: LiteRtModelEntity?,
        fallbackContextTokens: Int = AGENT_LITERT_FALLBACK_CONTEXT_TOKENS,
        minContextTokens: Int = AGENT_LITERT_MIN_CONTEXT_TOKENS,
        safeMaxContextTokens: Int = AGENT_LITERT_SAFE_MAX_CONTEXT_TOKENS
    ): Int =
        com.example.llamadroid.service.resolveAgentLiteRtContextTokens(
            savedContextTokens = savedContextTokens,
            model = model,
            fallbackContextTokens = fallbackContextTokens,
            minContextTokens = minContextTokens,
            safeMaxContextTokens = safeMaxContextTokens
        )

    fun resolveAgentLiteRtMaxOutputTokens(
        savedMaxOutputTokens: Int,
        resolvedContextTokens: Int,
        model: LiteRtModelEntity?,
        fallbackMaxOutputTokens: Int = AGENT_LITERT_FALLBACK_MAX_OUTPUT_TOKENS,
        minMaxOutputTokens: Int = AGENT_LITERT_MIN_MAX_OUTPUT_TOKENS
    ): Int =
        com.example.llamadroid.service.resolveAgentLiteRtMaxOutputTokens(
            savedMaxOutputTokens = savedMaxOutputTokens,
            resolvedContextTokens = resolvedContextTokens,
            model = model,
            fallbackMaxOutputTokens = fallbackMaxOutputTokens,
            minMaxOutputTokens = minMaxOutputTokens
        )

    fun resolveAgentEffectiveMaxOutputTokens(
        configuredMaxOutputTokens: Int,
        contextTokens: Int,
        estimatedPromptTokens: Int,
        fallbackMaxOutputTokens: Int = AGENT_DEFAULT_MAX_OUTPUT_TOKENS,
        templateSafetyReserveTokens: Int = AGENT_OUTPUT_TEMPLATE_SAFETY_RESERVE_TOKENS
    ): Int =
        com.example.llamadroid.service.resolveAgentEffectiveMaxOutputTokens(
            configuredMaxOutputTokens = configuredMaxOutputTokens,
            contextTokens = contextTokens,
            estimatedPromptTokens = estimatedPromptTokens,
            fallbackMaxOutputTokens = fallbackMaxOutputTokens,
            templateSafetyReserveTokens = templateSafetyReserveTokens
        )

    fun stripHtmlTags(html: String): String {
        return com.example.llamadroid.service.stripHtmlTags(html)
    }

    fun isSequentialBatchBlockedTool(toolName: String): Boolean {
        return toolName in SEQUENTIAL_BATCH_BLOCKED_TOOLS
    }

    fun normalizeLoadingCounterAfterDecrement(newCount: Int): LoadingCounterUpdate {
        return if (newCount < 0) {
            LoadingCounterUpdate(count = 0, wasClamped = true)
        } else {
            LoadingCounterUpdate(count = newCount, wasClamped = false)
        }
    }

    fun shouldReleaseLoadingOnConnectionLoss(loadingCount: Int, hasActiveJob: Boolean): Boolean {
        return loadingCount > 0 || hasActiveJob
    }

    fun backgroundCommandDisconnectReason(
        isRunning: Boolean,
        sessionConnected: Boolean,
        channelConnected: Boolean
    ): String? {
        if (!isRunning) return null
        return when {
            !sessionConnected -> "SSH session disconnected while command was still running."
            !channelConnected -> "Shell channel disconnected while command was still running."
            else -> null
        }
    }

    fun computeEditedFileContent(
        originalContent: String,
        startLine: Int,
        endLine: Int,
        newContent: String
    ): FileEditComputation {
        val preservedTrailingNewline = originalContent.endsWith("\n")
        val originalLinesSnapshot = splitPreservingTerminalBlankLine(originalContent, preservedTrailingNewline)
        val originalLines = originalLinesSnapshot.toMutableList()

        require(startLine >= 1) { "start_line must be >= 1, got $startLine" }
        require(endLine <= originalLines.size) { "end_line ($endLine) exceeds file length (${originalLines.size} lines)" }
        require(startLine <= endLine) { "start_line ($startLine) must be <= end_line ($endLine)" }

        val replacementLines = splitPreservingTerminalBlankLine(newContent, newContent.endsWith("\n"))
        val linesToRemove = endLine - startLine + 1
        repeat(linesToRemove) { originalLines.removeAt(startLine - 1) }
        originalLines.addAll(startLine - 1, replacementLines)

        val rebuiltContent = originalLines.joinToString("\n")

        return FileEditComputation(
            updatedContent = rebuiltContent,
            originalLineCount = originalLinesSnapshot.size,
            insertedLineCount = replacementLines.size,
            preservedTrailingNewline = preservedTrailingNewline
        )
    }

    fun readOptionalLong(payload: JSONObject, key: String): Long? {
        if (!payload.has(key) || payload.isNull(key)) return null
        return payload.optLong(key)
    }

    fun parseAllowedToolNames(rawJson: String?): Set<String> {
        if (rawJson.isNullOrBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(rawJson)
            buildSet {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun parseCustomToolParameterSpecs(parametersJson: String): Map<String, CustomToolParameterSpec> {
        return runCatching {
            val json = JSONObject(parametersJson)
            buildMap {
                json.keys().forEach { key ->
                    val node = json.opt(key)
                    val spec = when (node) {
                        is JSONObject -> CustomToolParameterSpec(
                            description = node.optString("description", key),
                            maxLength = node.optInt("maxLength").takeIf { it > 0 },
                            enumValues = node.optJSONArray("enum")?.toStringList().orEmpty()
                        )
                        else -> CustomToolParameterSpec(description = node?.toString().orEmpty().ifBlank { key })
                    }
                    put(key, spec)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun inferCustomToolExecutionMode(commandTemplate: String): CustomToolExecutionMode {
        return if (commandTemplate.trimStart().startsWith(SHELL_EXPLICIT_PREFIX, ignoreCase = true)) {
            CustomToolExecutionMode.SHELL
        } else {
            CustomToolExecutionMode.ARGV
        }
    }

    fun stripShellPrefix(commandTemplate: String): String {
        val trimmed = commandTemplate.trim()
        return if (trimmed.startsWith(SHELL_EXPLICIT_PREFIX, ignoreCase = true)) {
            trimmed.removePrefix(SHELL_EXPLICIT_PREFIX).trimStart()
        } else {
            trimmed
        }
    }

    fun renderShellTemplate(commandTemplate: String, arguments: Map<String, String>): String {
        var rendered = stripShellPrefix(commandTemplate)
        arguments.entries
            .sortedByDescending { it.key.length }
            .forEach { (key, value) ->
                rendered = rendered.replace("{$key}", escapeShellArgument(value))
            }
        val leftover = PLACEHOLDER_REGEX.find(rendered)?.groupValues?.getOrNull(1)
        if (!leftover.isNullOrBlank()) {
            throw IllegalArgumentException("Missing value for custom tool parameter `$leftover`.")
        }
        return rendered
    }

    fun tokenizeArgvTemplate(commandTemplate: String, arguments: Map<String, String>): List<String> {
        val template = stripShellPrefix(commandTemplate)
        val rawTokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        template.forEach { ch ->
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' && quote != '\'' -> escaped = true
                quote == null && (ch == '"' || ch == '\'') -> quote = ch
                quote != null && ch == quote -> quote = null
                quote == null && ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        rawTokens += current.toString()
                        current.setLength(0)
                    }
                }
                else -> current.append(ch)
            }
        }

        if (escaped) current.append('\\')
        if (quote != null) {
            throw IllegalArgumentException("Custom tool command template has an unclosed quote.")
        }
        if (current.isNotEmpty()) {
            rawTokens += current.toString()
        }
        if (rawTokens.isEmpty()) {
            throw IllegalArgumentException("Custom tool command template produced no argv tokens.")
        }

        return rawTokens.map { token ->
            var substituted = token
            arguments.entries
                .sortedByDescending { it.key.length }
                .forEach { (key, value) ->
                    substituted = substituted.replace("{$key}", value)
                }
            val leftover = PLACEHOLDER_REGEX.find(substituted)?.groupValues?.getOrNull(1)
            if (!leftover.isNullOrBlank()) {
                throw IllegalArgumentException("Missing value for custom tool parameter `$leftover`.")
            }
            substituted
        }
    }

    fun normalizeToolArguments(arguments: Any?): Map<String, String> {
        return when (arguments) {
            null -> emptyMap()
            is JSONObject -> buildMap {
                arguments.keys().forEach { key -> put(key, arguments.opt(key)?.toString().orEmpty()) }
            }
            is Map<*, *> -> buildMap {
                arguments.forEach { (key, value) ->
                    key?.toString()?.takeIf { it.isNotBlank() }?.let { put(it, value?.toString().orEmpty()) }
                }
            }
            is String -> {
                val trimmed = arguments.trim()
                if (!trimmed.startsWith("{")) emptyMap() else normalizeToolArguments(JSONObject(trimmed))
            }
            else -> emptyMap()
        }
    }

    fun parseAgentResult(agentLabel: String, summary: String): AgentResult {
        val trimmed = summary.trim()
        if (!trimmed.startsWith("{")) {
            return AgentResult.GenericResult(
                status = "FAILED",
                summary = trimmed.ifBlank {
                    "Specialist ended without a structured finish_task result."
                }
            )
        }

        val json = JSONObject(trimmed)
        val status = json.optString("status", "SUCCESS")
            .ifBlank { "SUCCESS" }
            .uppercase()
        require(
            status in setOf(
                "SUCCESS",
                "FAILED",
                "BLOCKED",
                "CANCELLED",
                "INTERRUPTED"
            )
        ) {
            "Agent result status must be SUCCESS, FAILED, BLOCKED, " +
                "CANCELLED, or INTERRUPTED."
        }
        return when (agentLabel.uppercase()) {
            "CODER" -> AgentResult.CoderResult(
                status = status,
                changedFiles = json.optJSONArray("changed_files").toStringList(),
                intentPerFile = json.optJSONObject("intent_per_file").toStringMap(),
                verificationReads = json.optJSONArray("verification_reads").toStringList(),
                remainingRisks = json.optJSONArray("remaining_risks").toStringList()
            ).also {
                if (it.status == "SUCCESS") {
                    require(it.changedFiles.isNotEmpty()) {
                        "CoderResult.changed_files must not be empty on success."
                    }
                }
            }
            "REVIEWER" -> AgentResult.ReviewerResult(
                status = status,
                findings = json.optJSONArray("findings").toReviewerFindings(),
                remainingRisks = json.optJSONArray("remaining_risks").toStringList()
            )
            "EXECUTOR" -> AgentResult.ExecutorResult(
                status = status,
                commandsRun = json.optJSONArray("commands_run").toStringList(),
                commandIds = json.optJSONArray("command_ids").toStringList(),
                finalStatus = json.optString("final_status").ifBlank { status },
                keyOutputs = json.optJSONArray("key_outputs").toStringList(),
                nextRecommendation = json.optString("next_recommendation").ifBlank { "Review the command results before deciding the next step." }
            )
            "SUMMARIZER" -> AgentResult.SummarizerResult(
                status = status,
                memoryFilesUpdated = json.optJSONArray("memory_files_updated").toStringList(),
                reasonPerFile = json.optJSONObject("reason_per_file").toStringMap(),
                carryForwardNotes = json.optJSONArray("carry_forward_notes").toStringList()
            ).also {
                if (it.status == "SUCCESS") {
                    require(it.memoryFilesUpdated.isNotEmpty()) {
                        "SummarizerResult.memory_files_updated must not be empty on success."
                    }
                }
            }
            "CODEBASE_SCOUT" -> AgentResult.ScoutResult(
                status = status,
                relevantFiles = json.optJSONArray("relevant_files").toStringList(),
                architecture = json.optJSONArray("architecture").toStringList(),
                dependencies = json.optJSONArray("dependencies").toStringList(),
                constraints = json.optJSONArray("constraints").toStringList(),
                risks = json.optJSONArray("risks").toStringList(),
                openQuestions = json.optJSONArray("open_questions").toStringList(),
                recommendedScope = json.optJSONArray("recommended_scope").toStringList()
            )
            "RESEARCHER" -> AgentResult.ResearcherResult(
                status = status,
                researchQuestion = json.optString("research_question").ifBlank { "Unspecified research question" },
                sources = json.optJSONArray("sources").toStringList(),
                facts = json.optJSONArray("facts").toStringList(),
                conflicts = json.optJSONArray("conflicts").toStringList(),
                uncertainties = json.optJSONArray("uncertainties").toStringList(),
                recommendations = json.optJSONArray("recommendations").toStringList()
            )
            "PLANNER" -> AgentResult.PlannerResult(
                status = status,
                planMarkdown = json.optString("plan_markdown"),
                structuredPlanJson = json.optJSONObject("structured_plan")?.toString()
                    ?: json.optString("structured_plan_json"),
                openQuestions = json.optJSONArray("open_questions").toStringList(),
                recommendedNextSteps = json.optJSONArray("recommended_next_steps").toStringList()
            ).also {
                require(it.planMarkdown.isNotBlank() || it.structuredPlanJson.isNotBlank()) {
                    "PlannerResult requires plan_markdown or structured_plan."
                }
            }
            else -> AgentResult.GenericResult(
                status = status,
                summary = json.optString("summary").ifBlank { trimmed }
            )
        }
    }

    fun blockedUrlReason(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return "Invalid URL."
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "file") return "file:// URLs are blocked."
        if (scheme !in setOf("http", "https")) return "Only http:// and https:// URLs are allowed."

        val host = uri.host?.trim()?.lowercase().orEmpty()
        if (host.isBlank()) return "URL must include a host."
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")) {
            return "Localhost and .local hosts are blocked."
        }
        if (host in setOf("0.0.0.0", "[::]")) {
            return "Wildcard local addresses are blocked."
        }

        val literalAddress = parseInetAddressIfLiteral(host)
        if (literalAddress != null && isBlockedAddress(literalAddress)) {
            return "Private, loopback, or link-local IP addresses are blocked."
        }
        return null
    }

    fun scoreContextItem(queryTokens: Set<String>, title: String, content: String, baseWeight: Int): Int {
        if (content.isBlank() && title.isBlank()) return 0
        if (queryTokens.isEmpty()) return baseWeight
        val haystack = "$title $content".lowercase()
        val tokenHits = queryTokens.sumOf { token ->
            val occurrences = Regex("\\b${Regex.escape(token)}\\b").findAll(haystack).count()
            if (occurrences == 0) 0 else max(1, occurrences)
        }
        return baseWeight + tokenHits
    }

    private fun escapeShellArgument(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun splitPreservingTerminalBlankLine(content: String, hadTrailingNewline: Boolean): List<String> {
        if (content.isEmpty()) return listOf("")
        val trimmed = if (hadTrailingNewline) content.dropLast(1) else content
        val base = if (trimmed.isEmpty()) listOf("") else trimmed.split("\n")
        return if (hadTrailingNewline) base + "" else base
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val value = opt(i)?.toString()?.trim().orEmpty()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            this@toStringMap.keys().forEach { key ->
                val value = this@toStringMap.opt(key)?.toString().orEmpty().trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    put(key, value)
                }
            }
        }
    }

    private fun JSONArray?.toReviewerFindings(): List<AgentResult.ReviewerFinding> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val obj = optJSONObject(i) ?: continue
                val file = obj.optString("file").trim()
                val severity = obj.optString("severity").trim().ifBlank { "MEDIUM" }
                val description = obj.optString("description").trim()
                val recommendation = obj.optString("recommendation").trim()
                if (file.isBlank() || description.isBlank() || recommendation.isBlank()) continue
                add(
                    AgentResult.ReviewerFinding(
                        file = file,
                        line = obj.optInt("line").takeIf { it > 0 },
                        severity = severity,
                        description = description,
                        recommendation = recommendation
                    )
                )
            }
        }
    }

    private fun parseInetAddressIfLiteral(host: String): InetAddress? {
        val candidate = host.removePrefix("[").removeSuffix("]")
        val looksLikeIp = candidate.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) || candidate.contains(':')
        if (!looksLikeIp) return null
        return runCatching { InetAddress.getByName(candidate) }.getOrNull()
    }

    private fun isBlockedAddress(address: InetAddress): Boolean {
        return address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isSiteLocalAddress ||
            address.isLinkLocalAddress ||
            address.isMulticastAddress
    }
}
