package com.example.llamadroid.service

import com.example.llamadroid.data.db.CustomToolEntity
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
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
        add(
            buildString {
                appendLine("# Initial Order")
                appendLine()
                append(initialOrder)
            }.trimEnd()
        )
        planContent?.takeIf { it.isNotBlank() }?.let { add(it) }
        add(compactionSummary)
    }
    val optional = listOfNotNull(compactStateSnapshot?.takeIf { it.isNotBlank() })
    return CompactPromptBasisSections(requiredSections = required, optionalSections = optional)
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
    if (!toolName.isNullOrBlank() && toolName !in commandToolNames) return false

    val combinedText = buildString {
        append(content)
        if (!toolOutput.isNullOrBlank()) {
            if (isNotEmpty()) append('\n')
            append(toolOutput)
        }
    }

    return combinedText.contains("Command ID:", ignoreCase = true) &&
        (
            combinedText.contains("Status: running", ignoreCase = true) ||
                combinedText.contains("Command is still running", ignoreCase = true)
        )
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
        "delete_memory"
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
            return AgentResult.GenericResult(status = "SUCCESS", summary = trimmed)
        }

        val json = JSONObject(trimmed)
        val status = json.optString("status", "SUCCESS").ifBlank { "SUCCESS" }
        return when (agentLabel.uppercase()) {
            "CODER" -> AgentResult.CoderResult(
                status = status,
                changedFiles = json.optJSONArray("changed_files").toStringList(),
                intentPerFile = json.optJSONObject("intent_per_file").toStringMap(),
                verificationReads = json.optJSONArray("verification_reads").toStringList(),
                remainingRisks = json.optJSONArray("remaining_risks").toStringList()
            ).also {
                require(it.changedFiles.isNotEmpty()) { "CoderResult.changed_files must not be empty." }
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
                require(it.memoryFilesUpdated.isNotEmpty()) { "SummarizerResult.memory_files_updated must not be empty." }
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
