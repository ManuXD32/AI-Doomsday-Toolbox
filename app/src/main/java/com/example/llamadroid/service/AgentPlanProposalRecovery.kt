package com.example.llamadroid.service

import java.security.MessageDigest
import java.util.Locale

/**
 * Result of inspecting an assistant response that did not contain a native
 * tool call while the optimized root is in Plan.
 *
 * [SUBMIT] is only a mechanical conversion of an explicit, bounded plan into
 * the arguments for the existing `propose_plan` approval boundary. It does
 * not approve the plan and it does not execute any tool. [REPROMPT] asks the
 * model to emit the native call when the response cannot be converted without
 * guessing. [NO_MATCH] leaves normal no-action recovery untouched.
 */
internal enum class PlanProposalRecoveryDisposition {
    SUBMIT,
    REPROMPT,
    NO_MATCH
}

internal data class RecoveredPlanProposal(
    val plan: String,
    val summary: String,
    /** Stable across retries so host-side recovery can deduplicate the draft. */
    val toolCallId: String
) {
    fun toToolCall(): OllamaService.ToolCall = OllamaService.ToolCall(
        name = "propose_plan",
        arguments = linkedMapOf(
            "plan" to plan,
            "summary" to summary
        ),
        id = toolCallId
    )
}

internal data class PlanProposalRecovery(
    val disposition: PlanProposalRecoveryDisposition,
    val proposal: RecoveredPlanProposal? = null,
    val instruction: String? = null,
    val reasonCode: String
)

private const val PLAN_PROPOSAL_MAX_INPUT_CHARS = 16_000
private const val PLAN_PROPOSAL_MAX_SUMMARY_CHARS = 160

private val PLAN_HEADER = Regex(
    "(?i)^(?:implementation\\s+plan|implementation\\s+steps|plan\\s+of\\s+action|" +
        "proposed\\s+implementation|plan)(?:\\s*[:\\-]\\s*(.*))?$"
)
private val LIST_STEP = Regex("^\\s*(?:[-*+]\\s+|\\d{1,3}[.)]\\s+).+")
private val ACTION_WORD = Regex(
    "(?i)\\b(?:add|adopt|build|check|configure|create|define|implement|" +
        "integrate|make|persist|read|run|store|test|use|validate|verify|write)\\b"
)
private val UNRESOLVED_DECISION = Regex(
    "(?i)\\b(?:i\\s+need\\s+(?:your\\s+)?(?:answer|choice|decision|clarification)|" +
        "please\\s+(?:choose|select|clarify)|which\\s+(?:option|approach|one)|" +
        "what\\s+do\\s+you\\s+prefer|should\\s+we\\s+(?:use|choose|build)|" +
        "awaiting\\s+(?:your\\s+)?(?:answer|decision|choice))\\b"
)
private val EXPLICIT_TOOL_SHAPE = Regex(
    "(?i)(?:\\\"name\\\"\\s*:|<tool(?:_call)?\\b|\\btool\\s+call\\s*:|" +
        "\\b(?:propose_plan|question)\\s*\\()"
)

/** Stable, compact Plan-phase contract appended to the optimized system prompt. */
internal const val DIRECT_PLAN_REQUIRED_ACTION_CONTRACT =
    "Required outcome: return one bounded actionable Markdown plan, then wait; never approve or build. The runtime projects it through the durable approval boundary."

/** Source-compatible name for older optimized request assembly. */
internal const val OPTIMIZED_PLAN_REQUIRED_ACTION_CONTRACT = DIRECT_PLAN_REQUIRED_ACTION_CONTRACT

/**
 * Direct-runtime entry point.  The host owns the one-retry counter; this pure
 * parser never approves a plan and never performs a second repair itself.
 */
internal fun recoverDirectImplementationPlanProse(text: String): PlanProposalRecovery =
    recoverImplementationPlanProse(text, phase = AgentHarnessPhase.PLAN)

/**
 * Inspects a bounded Plan response for an explicit implementation-plan body.
 *
 * The classifier deliberately accepts only a plan heading (or a clearly
 * numbered/actionable plan) and at least two actionable steps. Research prose,
 * arbitrary markdown, and malformed tool examples are not converted. A plan
 * that contains a genuine unresolved user decision receives a question-aware
 * repair instruction instead of being placed at the approval boundary.
 */
internal fun recoverImplementationPlanProse(
    text: String,
    phase: AgentHarnessPhase = AgentHarnessPhase.PLAN
): PlanProposalRecovery {
    if (phase != AgentHarnessPhase.PLAN || text.isBlank()) {
        return PlanProposalRecovery(
            disposition = PlanProposalRecoveryDisposition.NO_MATCH,
            reasonCode = "PLAN_PROSE_NOT_PLAN_PHASE"
        )
    }

    if (EXPLICIT_TOOL_SHAPE.containsMatchIn(text)) {
        // Native/legacy tool recovery owns explicit shapes. Do not compete with
        // its parser or accidentally turn malformed JSON into a plan body.
        return PlanProposalRecovery(
            disposition = PlanProposalRecoveryDisposition.NO_MATCH,
            reasonCode = "PLAN_PROSE_EXPLICIT_TOOL_SHAPE"
        )
    }

    if (text.length > PLAN_PROPOSAL_MAX_INPUT_CHARS) {
        return repair(
            reasonCode = "PLAN_PROSE_INPUT_TOO_LARGE",
            questionAware = false
        )
    }

    val lines = text.lines()
    val header = lines.withIndex().firstNotNullOfOrNull { indexed ->
        parsePlanHeader(indexed.value)?.let { parsed -> indexed.index to parsed.inlineSummary }
    }

    val extracted = when {
        header != null -> {
            val (headerIndex, inlineSummary) = header
            val bodyLines = mutableListOf<String>()
            inlineSummary?.takeIf(String::isNotBlank)?.let(bodyLines::add)
            lines.drop(headerIndex + 1).forEach { line ->
                if (!isClosingFence(line)) bodyLines += line
            }
            val body = bodyLines.joinToString("\n")
            body to true
        }
        looksLikeHeadinglessPlan(lines) -> text to false
        else -> {
            return PlanProposalRecovery(
                disposition = PlanProposalRecoveryDisposition.NO_MATCH,
                reasonCode = "PLAN_PROSE_NO_EXPLICIT_PLAN_SHAPE"
            )
        }
    }

    var normalizedPlan = normalizePlanBody(extracted.first)
    if (normalizedPlan.isBlank()) {
        return repair(
            reasonCode = "PLAN_PROSE_EMPTY_BODY",
            questionAware = false
        )
    }

    var structurallyCompacted = false
    if (!AgentPlanBudgetSupport.isWithinBudget(normalizedPlan)) {
        val compacted = compactPlanStructure(normalizedPlan)
        if (!AgentPlanBudgetSupport.isWithinBudget(compacted)) {
            return repair(
                reasonCode = "PLAN_PROSE_OVER_BUDGET",
                questionAware = false
            )
        }
        normalizedPlan = compacted
        structurallyCompacted = true
    }

    val steps = normalizedPlan.lines().count { LIST_STEP.matches(it) }
    val hasAction = ACTION_WORD.containsMatchIn(normalizedPlan)
    if (steps < 2 || !hasAction) {
        return repair(
            reasonCode = "PLAN_PROSE_NOT_ACTIONABLE",
            questionAware = UNRESOLVED_DECISION.containsMatchIn(normalizedPlan)
        )
    }

    if (UNRESOLVED_DECISION.containsMatchIn(normalizedPlan)) {
        return repair(
            reasonCode = "PLAN_PROSE_UNRESOLVED_DECISION",
            questionAware = true
        )
    }

    val inlineSummary = header?.second
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let(::cleanSummary)
    val summary = inlineSummary
        ?: normalizedPlan
            .lineSequence()
            .firstOrNull { LIST_STEP.matches(it) }
            ?.replaceFirst(Regex("^\\s*(?:[-*+]\\s+|\\d{1,3}[.)]\\s+)"), "")
            ?.let(::cleanSummary)
        ?: "Implementation plan"

    val boundedSummary = summary.take(PLAN_PROPOSAL_MAX_SUMMARY_CHARS).trim()
    val callId = stableRecoveredPlanId(normalizedPlan, boundedSummary)
    return PlanProposalRecovery(
        disposition = PlanProposalRecoveryDisposition.SUBMIT,
        proposal = RecoveredPlanProposal(
            plan = normalizedPlan,
            summary = boundedSummary.ifBlank { "Implementation plan" },
            toolCallId = callId
        ),
        reasonCode = if (structurallyCompacted) {
            "PLAN_PROSE_STRUCTURE_COMPACTED"
        } else if (extracted.second) {
            "PLAN_PROSE_HEADING_RECOVERED"
        } else {
            "PLAN_PROSE_NUMBERED_RECOVERED"
        }
    )
}

/**
 * Compact instruction for the one permitted repair attempt. The host should
 * append the selected tool schema when available; this text intentionally
 * never asks for or interprets approval.
 */
internal fun planProposalRecoveryInstruction(
    reasonCode: String,
    availableToolNames: Collection<String> = emptyList()
): String {
    val hasQuestion = availableToolNames.any { it.equals("question", ignoreCase = true) }
    val questionRule = if (hasQuestion) {
        "Use question only for one genuine unresolved blocker with 2 to 3 literal choices."
    } else {
        "Do not invent a question or ask for approval in prose."
    }
    return buildString {
        append("Your previous response was not a bounded actionable plan (reason=")
        append(reasonCode)
        append("). ")
        append("Return exactly one Markdown implementation plan with at least two concrete numbered steps (<=500 words and <=4000 characters). ")
        append("The runtime opens the approval UI; do not approve, reject, or execute anything yourself. ")
        append(questionRule)
        append(" Do not print JSON or name an internal plan tool.")
    }.take(1_200)
}

private fun repair(reasonCode: String, questionAware: Boolean): PlanProposalRecovery =
    PlanProposalRecovery(
        disposition = PlanProposalRecoveryDisposition.REPROMPT,
        instruction = planProposalRecoveryInstruction(
            reasonCode = reasonCode,
            availableToolNames = if (questionAware) listOf("question") else emptyList()
        ),
        reasonCode = reasonCode
    )

private data class ParsedPlanHeader(val inlineSummary: String?)

private fun parsePlanHeader(line: String): ParsedPlanHeader? {
    var candidate = line.trim()
    if (candidate.isBlank()) return null
    candidate = candidate.replaceFirst(Regex("^#{1,6}\\s*"), "")
    candidate = candidate.replaceFirst(Regex("^\\*{1,3}"), "")
    candidate = candidate.replaceFirst(Regex("\\*{1,3}$"), "")
    candidate = candidate.trim().trim('`', '_').trim()
    val match = PLAN_HEADER.matchEntire(candidate) ?: return null
    val inline = match.groupValues.getOrNull(1)?.trim().orEmpty()
    return ParsedPlanHeader(inline.takeIf { it.isNotBlank() })
}

private fun looksLikeHeadinglessPlan(lines: List<String>): Boolean {
    val steps = lines.count { LIST_STEP.matches(it) }
    if (steps < 2) return false
    val body = lines.joinToString("\n")
    return ACTION_WORD.containsMatchIn(body)
}

private fun normalizePlanBody(raw: String): String {
    var normalized = raw.trim()
    if (normalized.startsWith("```") && normalized.endsWith("```")) {
        normalized = normalized
            .removePrefix("```")
            .removeSuffix("```")
            .trimStart()
            .lineSequence()
            .dropWhile { it.trim().lowercase(Locale.ROOT) in setOf("markdown", "md", "text") }
            .joinToString("\n")
            .trim()
    }
    return normalized
        .lineSequence()
        .map { it.trimEnd() }
        .joinToString("\n")
        .trim()
}

/**
 * Preserve the model's explicit headings and actionable list items while
 * removing explanatory paragraphs, tables, fences, and separators. This is a
 * deterministic projection, not a generated summary, so no plan decision is
 * invented and every retained action remains verbatim.
 */
private fun compactPlanStructure(plan: String): String {
    val retained = plan.lineSequence()
        .map(String::trim)
        .filter { line ->
            line.isNotBlank() && (
                line.matches(Regex("^#{1,6}\\s+.+")) ||
                    LIST_STEP.matches(line)
                )
        }
        .toList()
    if (retained.count { LIST_STEP.matches(it) } < 2) return plan
    return retained.joinToString("\n")
}

private fun cleanSummary(raw: String): String {
    val cleaned = raw
        .replace(Regex("^[-*+>#\\d.)\\s]+"), "")
        .replace(Regex("[*_`]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (cleaned.length <= PLAN_PROPOSAL_MAX_SUMMARY_CHARS) return cleaned
    val cutoff = cleaned.lastIndexOf(' ', PLAN_PROPOSAL_MAX_SUMMARY_CHARS - 1)
        .takeIf { it > 20 }
        ?: PLAN_PROPOSAL_MAX_SUMMARY_CHARS
    return cleaned.substring(0, cutoff).trimEnd() + "…"
}

private fun isClosingFence(line: String): Boolean = line.trim() == "```"

private fun stableRecoveredPlanId(plan: String, summary: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$summary\u001f$plan".toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "recovered-propose-plan-${digest.take(20)}"
}
