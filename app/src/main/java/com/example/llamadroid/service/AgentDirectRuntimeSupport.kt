package com.example.llamadroid.service

import java.security.MessageDigest
import java.util.Locale

/** Durable states of the one Direct Agent loop. */
enum class DirectAgentState {
    PLAN,
    AWAITING_PLAN_APPROVAL,
    BUILD,
    VERIFY,
    AWAITING_INPUT,
    AWAITING_TOOL_APPROVAL,
    PAUSED,
    COMPLETE
}

/** Events that may move a Direct Agent state forward. */
enum class DirectAgentSignal {
    PLAN_SUBMITTED,
    PLAN_APPROVED,
    PLAN_REJECTED,
    BUILD_STEP_READY,
    VERIFY_REQUESTED,
    VERIFICATION_PASSED,
    REPAIR_REQUIRED,
    QUESTION_ASKED,
    TOOL_APPROVAL_REQUIRED,
    TOOL_APPROVED,
    TOOL_REJECTED,
    CONTINUE,
    STOP,
    COMPLETE,
    FAILURE
}

/**
 * Pure transition table used by the serialized coordinator.  The coordinator
 * persists the event before applying this function; there is no recursive
 * continuation or model-driven role change hidden in the transition.
 */
fun advanceDirectAgentState(
    current: DirectAgentState,
    signal: DirectAgentSignal,
    resumeState: DirectAgentState = DirectAgentState.PLAN
): DirectAgentState = when (signal) {
    DirectAgentSignal.STOP,
    DirectAgentSignal.FAILURE -> DirectAgentState.PAUSED

    DirectAgentSignal.PLAN_SUBMITTED ->
        if (current == DirectAgentState.PLAN) DirectAgentState.AWAITING_PLAN_APPROVAL else current

    DirectAgentSignal.PLAN_APPROVED ->
        if (current == DirectAgentState.AWAITING_PLAN_APPROVAL) DirectAgentState.BUILD else current

    DirectAgentSignal.PLAN_REJECTED ->
        if (current == DirectAgentState.AWAITING_PLAN_APPROVAL) DirectAgentState.PLAN else current

    DirectAgentSignal.BUILD_STEP_READY ->
        if (current == DirectAgentState.BUILD) DirectAgentState.BUILD else current

    DirectAgentSignal.VERIFY_REQUESTED ->
        if (current == DirectAgentState.BUILD) DirectAgentState.VERIFY else current

    DirectAgentSignal.VERIFICATION_PASSED ->
        if (current == DirectAgentState.VERIFY) DirectAgentState.VERIFY else current

    DirectAgentSignal.COMPLETE ->
        if (current == DirectAgentState.VERIFY) DirectAgentState.COMPLETE else current

    DirectAgentSignal.REPAIR_REQUIRED ->
        if (current == DirectAgentState.VERIFY) DirectAgentState.BUILD else current

    DirectAgentSignal.QUESTION_ASKED ->
        if (current != DirectAgentState.COMPLETE) DirectAgentState.AWAITING_INPUT else current

    DirectAgentSignal.TOOL_APPROVAL_REQUIRED ->
        if (current == DirectAgentState.BUILD || current == DirectAgentState.VERIFY) {
            DirectAgentState.AWAITING_TOOL_APPROVAL
        } else {
            current
        }

    DirectAgentSignal.TOOL_APPROVED ->
        if (current == DirectAgentState.AWAITING_TOOL_APPROVAL) resumeState else current

    DirectAgentSignal.TOOL_REJECTED ->
        if (current == DirectAgentState.AWAITING_TOOL_APPROVAL) DirectAgentState.PAUSED else current

    DirectAgentSignal.CONTINUE ->
        if (current == DirectAgentState.PAUSED || current == DirectAgentState.AWAITING_INPUT) {
            resumeState.takeUnless {
                it == DirectAgentState.AWAITING_INPUT || it == DirectAgentState.AWAITING_TOOL_APPROVAL
            } ?: DirectAgentState.PLAN
        } else {
            current
        }
}

/** A compact authoritative tail; its fields are never silently omitted. */
data class DirectControlCapsule(
    val taskContract: String,
    val corrections: List<String> = emptyList(),
    val approvedPlanReference: String? = null,
    val approvedPlanContent: String? = null,
    val currentStep: String? = null,
    val artifacts: List<String> = emptyList(),
    val activeCommandOrRunHandle: String? = null,
    val latestFailure: String? = null,
    val nextAction: String,
    val latestSteering: String? = null
) {
    /**
     * Render deterministically and compactly.  This intentionally does not
     * truncate required state; callers must use [fitsContext] and pause or
     * compact before sending a capsule that cannot fit.
     */
    fun render(): String = buildString {
        appendLine("CONTROL_CAPSULE v=1")
        appendLine("task_contract=${taskContract.singleLine()}")
        appendLine("corrections=${corrections.renderValues()}")
        appendLine("approved_plan_ref=${approvedPlanReference.orEmpty().singleLine()}")
        appendLine("approved_plan=${approvedPlanContent.orEmpty().singleLine()}")
        appendLine("current_step=${currentStep.orEmpty().singleLine()}")
        appendLine("artifacts=${artifacts.renderValues()}")
        appendLine("active_handle=${activeCommandOrRunHandle.orEmpty().singleLine()}")
        appendLine("latest_failure=${latestFailure.orEmpty().singleLine()}")
        appendLine("next_action=${nextAction.singleLine()}")
        appendLine("latest_steering=${latestSteering.orEmpty().singleLine()}")
    }.trimEnd()

    fun inputTokens(exactTokenCount: Int? = null): Int =
        AgentHarnessPolicy.directInputTokenCount(render(), exactTokenCount)

    fun fitsContext(
        reservedOutputTokens: Int,
        contextTokens: Int = AgentHarnessPolicy.DIRECT_DEFAULT_CONTEXT_TOKENS,
        exactTokenCount: Int? = null
    ): Boolean = AgentHarnessPolicy.directFitsContext(
        inputTokens = inputTokens(exactTokenCount),
        reservedOutputTokens = reservedOutputTokens,
        contextTokens = contextTokens
    )

    private fun String.singleLine(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun List<String>.renderValues(): String =
        joinToString(" || ") { it.replace(Regex("\\s+"), " ").trim() }
}

/** Metadata-only representation of one completed action. */
data class DirectActionReceipt(
    val actionId: String,
    val toolName: String,
    val argumentsHash: String,
    val status: String,
    val resultReference: String? = null
) {
    val succeeded: Boolean
        get() = status.equals("SUCCESS", ignoreCase = true) ||
            status.equals("COMPLETED", ignoreCase = true)
}

data class DirectActionRequest(
    val actionId: String,
    val toolName: String,
    val arguments: Map<String, String>
) {
    val argumentsHash: String get() = directArgumentsHash(arguments)
}

enum class DirectReceiptDecision {
    EXECUTE,
    REPLAY_SUCCESS,
    REJECT_MISMATCH
}

/** Replay a committed success without executing the side effect again. */
fun decideDirectReceiptReplay(
    request: DirectActionRequest,
    receipt: DirectActionReceipt?
): DirectReceiptDecision = when {
    receipt == null -> DirectReceiptDecision.EXECUTE
    receipt.actionId != request.actionId ||
        receipt.toolName != request.toolName ||
        receipt.argumentsHash != request.argumentsHash -> DirectReceiptDecision.REJECT_MISMATCH
    receipt.succeeded -> DirectReceiptDecision.REPLAY_SUCCESS
    else -> DirectReceiptDecision.EXECUTE
}

fun directArgumentsHash(arguments: Map<String, String>): String {
    val canonical = arguments.toSortedMap().entries.joinToString("\u001f") { (key, value) ->
        "${key.length}:$key=${value.length}:$value"
    }
    return sha256(canonical)
}

/** Recovery actions after a malformed call, repeated failure, or no progress. */
enum class DirectRecoveryAction {
    RETRY_EXACT_MALFORMED_CALL,
    CONTINUE,
    PAUSE
}

fun decideDirectRecovery(
    malformedCallRepairsUsed: Int,
    identicalFailureCount: Int,
    turnsWithoutSemanticProgress: Int
): DirectRecoveryAction = when {
    identicalFailureCount >= 2 || turnsWithoutSemanticProgress >= 3 -> DirectRecoveryAction.PAUSE
    malformedCallRepairsUsed == 0 -> DirectRecoveryAction.RETRY_EXACT_MALFORMED_CALL
    else -> DirectRecoveryAction.CONTINUE
}

/** One epoch invalidates queued continuations, approvals, alarms, and work. */
fun nextDirectRunEpoch(currentEpoch: Long): Long =
    if (currentEpoch == Long.MAX_VALUE) 0L else currentEpoch + 1L

fun acceptsDirectEpoch(messageEpoch: Long, currentEpoch: Long): Boolean =
    messageEpoch == currentEpoch

/** Extracts the exact action exposed by the authoritative Direct capsule. */
fun directCapsuleNextAction(capsule: String): String? = capsule
    .lineSequence()
    .map(String::trim)
    .firstOrNull { it.startsWith("- exact_next_action:") }
    ?.substringAfter(':')
    ?.trim()
    ?.takeIf { it.isNotBlank() }

/**
 * Small models sometimes return several otherwise valid tool proposals in one
 * provider response. The Direct runtime never executes them as a batch: it
 * commits the first proposal and records the remainder as deferred metadata so
 * the next model turn can reconsider them against the first result.
 */
data class DirectSerializedActionSelection<T>(
    val selected: T?,
    val deferred: List<T>
)

fun <T> selectDirectSerializedAction(proposals: List<T>): DirectSerializedActionSelection<T> =
    DirectSerializedActionSelection(
        selected = proposals.firstOrNull(),
        deferred = proposals.drop(1)
    )

/** Exact small-model repair for confusing project search with web research. */
fun shouldActivateDirectWebResearchRepair(
    phase: AgentHarnessPhase,
    attemptedTool: String,
    toolText: String = ""
): Boolean {
    if (phase != AgentHarnessPhase.PLAN) return false
    if (attemptedTool == "search_code") return true
    return attemptedTool == "question" &&
        toolText.lowercase(Locale.ROOT).contains("research")
}

/**
 * Direct Agent owns implementation decisions.  This narrow classifier keeps
 * a small model from turning algorithm, stack, research, or test selection
 * into a visible user question while preserving questions for destructive,
 * credential, account, and other genuinely user-owned blockers.
 */
fun isDirectModelOwnedChoiceQuestion(text: String): Boolean {
    val normalized = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    if (DIRECT_USER_OWNED_BLOCKER_TERMS.any(normalized::contains)) return false
    return DIRECT_MODEL_OWNED_CHOICE_TERMS.any(normalized::contains)
}

/** User directions that explicitly forbid further clarification questions. */
fun containsDirectNoMoreQuestionsDirective(text: String): Boolean =
    DIRECT_NO_MORE_QUESTIONS_DIRECTIVE.containsMatchIn(text)

/** Phase-correct recovery after a small model asks the user to choose owned work. */
fun directModelOwnedChoiceRecovery(phase: AgentHarnessPhase): String = when (phase) {
    AgentHarnessPhase.PLAN ->
        "This is an implementation choice owned by the Direct Agent. Choose a sensible default from the task contract and return the bounded actionable Markdown plan now. Do not ask the user to select research, algorithms, libraries, stacks, architecture, styling, or tests."
    AgentHarnessPhase.BUILD,
    AgentHarnessPhase.VERIFY ->
        "This action is already authorized by the approved plan. Perform every applicable planned build or verification action with the available tools now. Do not ask the user for permission to proceed or to choose between planned checks."
}

/** Exact one-shot repair when a response reaches its configured output cap. */
fun directOutputLimitRecovery(phase: AgentHarnessPhase): String = when (phase) {
    AgentHarnessPhase.PLAN ->
        "Return a shorter bounded Markdown plan now. Do not include implementation source code or tool-call JSON."
    AgentHarnessPhase.BUILD ->
        "Return exactly one tool call with no prose. Keep this strict-recovery write_file content or edit_file replacement below 2 KiB. If the target is larger, end this bounded increment with exactly one DIRECT-EXTEND anchor and continue only from that anchor on later turns."
    AgentHarnessPhase.VERIFY ->
        "Return exactly one concise verification tool call with no prose. Do not embed logs, source files, or repeated evidence in the call."
}

/** Resolves an exact Direct recovery tool without exposing the rest of the palette. */
fun directStrictRecoveryToolFromExactNextAction(exactNextAction: String?): String? {
    val action = exactNextAction?.lowercase(Locale.ROOT).orEmpty()
    if (action.isBlank()) return null
    val candidates = AgentHarnessPolicy.DIRECT_CORE_TOOL_NAMES +
        AgentToolSchemaPolicy.DIRECT_LOCAL_TOOL_NAMES +
        setOf("run_command")
    return candidates.firstOrNull { tool ->
        Regex("(^|[^a-z0-9_])${Regex.escape(tool)}([^a-z0-9_]|$)").containsMatchIn(action)
    }
}

/**
 * A missing structured boundary in Build/Verify gets one genuinely strict retry.
 * The authoritative next action already names the required tool, so sending only
 * that schema is more reliable for small models than repeating the full core
 * palette. Plan remains Markdown-first and therefore has no forced tool schema.
 */
fun directStructuredBoundaryRecoveryTool(
    phase: AgentHarnessPhase,
    exactNextAction: String?,
    latestFailure: String? = null,
    failedTool: String? = null
): String? = when (phase) {
    AgentHarnessPhase.PLAN -> null
    AgentHarnessPhase.BUILD,
    AgentHarnessPhase.VERIFY -> failedTool
        ?.let { directStrictRecoveryToolForExecutionFailure(it, latestFailure) }
        ?: directStrictRecoveryToolFromExactNextAction(exactNextAction)
}

/** Include the failed action in restored capsules without duplicating it in new failures. */
fun directFailureWithToolContext(failure: String?, failedTool: String?): String? {
    val normalizedFailure = failure?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedTool = failedTool?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        ?: return normalizedFailure
    return if (
        Regex("(^|[^a-z0-9_])${Regex.escape(normalizedTool)}([^a-z0-9_]|$)")
            .containsMatchIn(normalizedFailure.lowercase(Locale.ROOT))
    ) {
        normalizedFailure
    } else {
        "$normalizedTool failed: $normalizedFailure"
    }
}

/**
 * Removes only the fixed-width prefixes produced by `read_file`. Small models
 * sometimes copy those display-only line numbers into `edit_file.old_text`.
 * The caller must still attempt the unmodified exact match first.
 */
fun directExactEditCandidateWithoutReadLineNumbers(oldText: String): String? {
    if (oldText.isBlank()) return null
    val numberedLine = Regex("^\\s{0,5}\\d{1,6}  (.*)$")
    val stripped = oldText.lines().map { line ->
        numberedLine.matchEntire(line)?.groupValues?.get(1) ?: return null
    }.joinToString("\n")
    return stripped.takeIf { it != oldText }
}

/**
 * A bounded mutation reconstructed from a provider response that ended at its
 * output limit while serializing raw tool JSON.  It still goes through normal
 * tool validation, path clamping, approval, persistence, and receipt handling.
 */
data class DirectPartialMutationRecovery(
    val toolName: String,
    val arguments: Map<String, String>,
    val decodedCharacters: Int,
    val sourcePayloadComplete: Boolean
)

/**
 * Recover only the safe, already-generated prefix of an oversized Direct
 * write/edit call.  Some llama.cpp templates return a tool call as assistant
 * text and do not enforce JSON-schema maxLength. Retrying the same full file
 * consumes the next output budget too, so Direct commits a bounded staged
 * prefix with its reserved anchor and continues through exact-match edits.
 *
 * This parser is intentionally narrow: the response must look like a JSON tool
 * envelope, the path and edit anchor must be complete JSON strings, and only
 * write_file.content or edit_file.new_text may be incomplete. Unknown or prose
 * calls remain malformed and are never converted into mutations.
 */
fun recoverDirectPartialMutation(
    text: String,
    maxBytes: Int = AgentHarnessPolicy.DIRECT_WRITE_FILE_MAX_BYTES
): DirectPartialMutationRecovery? {
    if (text.isBlank() || maxBytes < 128) return null
    val firstBrace = text.indexOf('{')
    if (firstBrace < 0) return null
    val leading = text.substring(0, firstBrace)
        .replace("```json", "", ignoreCase = true)
        .replace("```", "")
        .replace("<tool_call>", "", ignoreCase = true)
        .trim()
    if (leading.isNotEmpty()) return null

    val toolMatch = Regex(
        "\\\"name\\\"\\s*:\\s*\\\"(write_file|edit_file)\\\"",
        RegexOption.IGNORE_CASE
    ).find(text) ?: return null
    val toolName = toolMatch.groupValues[1].lowercase(Locale.ROOT)
    val envelope = text.substring(toolMatch.range.first)
    val path = decodeDirectJsonStringField(envelope, "path")
        ?.takeIf { it.complete && it.value.isNotBlank() && it.value.length <= 1_024 }
        ?: return null

    val payloadField: String
    val oldText: String?
    when (toolName) {
        "write_file" -> {
            payloadField = "content"
            oldText = null
        }
        "edit_file" -> {
            payloadField = "new_text"
            oldText = decodeDirectJsonStringField(envelope, "old_text")
                ?.takeIf { it.complete }
                ?.value
                ?.takeIf { it == AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR }
                ?: return null
        }
        else -> return null
    }
    val payload = decodeDirectJsonStringField(envelope, payloadField) ?: return null
    val payloadBytes = payload.value.toByteArray(Charsets.UTF_8).size
    if (payload.complete && payloadBytes <= maxBytes) return null
    val staged = boundDirectMutationPrefix(payload.value, maxBytes) ?: return null
    val arguments = linkedMapOf("path" to path.value)
    if (oldText == null) {
        arguments["content"] = staged
    } else {
        arguments["old_text"] = oldText
        arguments["new_text"] = staged
    }
    return DirectPartialMutationRecovery(
        toolName = toolName,
        arguments = arguments,
        decodedCharacters = payload.value.length,
        sourcePayloadComplete = payload.complete
    )
}

/** Recover a partial native provider call whose name is carried out-of-band. */
fun recoverDirectPartialNativeMutation(
    toolName: String,
    rawArgumentsJson: String?,
    maxBytes: Int = AgentHarnessPolicy.DIRECT_WRITE_FILE_MAX_BYTES
): DirectPartialMutationRecovery? {
    val normalizedName = toolName.trim().lowercase(Locale.ROOT)
    if (normalizedName !in setOf("write_file", "edit_file")) return null
    val raw = rawArgumentsJson?.takeIf { it.isNotBlank() } ?: return null
    val envelope = "{\"name\":\"$normalizedName\",\"arguments\":" + raw
    return recoverDirectPartialMutation(envelope, maxBytes)
}

/** Bound an oversized native provider call using the same staged-mutation rule. */
fun stageDirectOversizedMutation(
    toolName: String,
    arguments: Map<String, String>,
    maxBytes: Int = AgentHarnessPolicy.DIRECT_WRITE_FILE_MAX_BYTES
): DirectPartialMutationRecovery? {
    val normalizedName = toolName.trim().lowercase(Locale.ROOT)
    val payloadField = when (normalizedName) {
        "write_file" -> "content"
        "edit_file" -> {
            if (arguments["old_text"] != AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR) return null
            "new_text"
        }
        else -> return null
    }
    val path = arguments["path"]?.takeIf { it.isNotBlank() } ?: return null
    val payload = arguments[payloadField] ?: return null
    if (payload.toByteArray(Charsets.UTF_8).size <= maxBytes) return null
    val staged = boundDirectMutationPrefix(payload, maxBytes) ?: return null
    val stagedArguments = linkedMapOf("path" to path)
    if (normalizedName == "write_file") {
        stagedArguments["content"] = staged
    } else {
        stagedArguments["old_text"] = AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR
        stagedArguments["new_text"] = staged
    }
    return DirectPartialMutationRecovery(
        toolName = normalizedName,
        arguments = stagedArguments,
        decodedCharacters = payload.length,
        sourcePayloadComplete = true
    )
}

/** Prevent post-build inspection loops once durable receipts prove the step. */
fun shouldBlockDirectInspectionAfterArtifactsCommitted(
    phase: AgentHarnessPhase,
    exactNextAction: String?,
    toolName: String
): Boolean = phase == AgentHarnessPhase.BUILD &&
    exactNextAction
        ?.trim()
        ?.startsWith("call finish_task now", ignoreCase = true) == true &&
    toolName in setOf("read_file", "list_directory", "search_code")

/**
 * A failed mutation or command can normally be repaired by changing that
 * tool's arguments. Stateful controls have no useful same-call repair: a
 * failed run/preview action usually requires changing project files,
 * dependencies, or active process state first. Keeping those controls out of
 * the strict one-tool palette prevents an impossible unchanged retry loop.
 */
fun directStrictRecoveryToolForExecutionFailure(
    toolName: String,
    failureMessage: String? = null
): String? {
    val normalized = toolName.trim().lowercase(Locale.ROOT)
    val normalizedFailure = failureMessage.orEmpty().lowercase(Locale.ROOT)
    if (
        normalized == "edit_file" &&
        (normalizedFailure.contains("exact_edit_no_match") ||
            normalizedFailure.contains("exact_edit_ambiguous"))
    ) {
        // Exact edits are deliberately non-destructive on a stale or ambiguous match. The
        // only safe strict recovery is to refresh the bounded current file view; forcing the
        // same edit schema would ask the model to guess old_text and repeat the failure.
        return "read_file"
    }
    if (
        normalized == "run_project" &&
        normalizedFailure.let { failure ->
            failure.contains("run.json") ||
                failure.contains("runtime must") ||
                failure.contains("entrypoint") ||
                failure.contains("ui must") ||
                failure.contains("run_config")
        }
    ) {
        return "write_file"
    }
    return normalized.takeUnless {
        it in setOf(
            "run_project",
            "check_project_run",
            "stop_project_run",
            "force_stop_project_run",
            "observe_preview",
            "interact_preview",
            "finish_task"
        )
    }?.takeIf { it.isNotBlank() }
}

fun directToolFailureRecoveryInstruction(
    toolName: String,
    failureMessage: String? = null
): String {
    val normalized = toolName.trim().lowercase(Locale.ROOT)
    val runManifestFailure = failureMessage.orEmpty().contains(
        "RUN_CONFIG_INVALID",
        ignoreCase = true
    )
    val recoveryTool = directStrictRecoveryToolForExecutionFailure(
        normalized,
        failureMessage
    )
    return if (normalized == "write_file" && runManifestFailure) {
        "The .adt/run.json write was rejected. Return exactly one write_file call with path " +
            ".adt/run.json. Its content must be a JSON object with explicit version=1, runtime, " +
            "entrypoint, and ui. For a static WebUI use " +
            "{\"version\":1,\"runtime\":\"web\",\"entrypoint\":\"index.html\",\"ui\":\"web\"}. " +
            "For Python use runtime=python and ui=console. Do not use name, script, uri, or command."
    } else if (recoveryTool == null) {
        "The previous $normalized call failed. Read the error envelope and return exactly one tool call " +
            "that repairs the project configuration, code, dependencies, or active state. Do not repeat " +
            "$normalized unchanged; retry it only after a concrete repair receipt."
    } else if (normalized == "edit_file" && recoveryTool == "read_file") {
        "The previous edit_file exact match was stale or ambiguous. Return exactly one read_file " +
            "call for the same target path so the next turn can copy the current text exactly. " +
            "Do not guess old_text and do not retry edit_file before the read receipt is committed."
    } else if (recoveryTool != normalized) {
        "The previous $normalized call failed because the project run manifest is invalid. Return exactly " +
            "one $recoveryTool call that replaces .adt/run.json with the required version 1 schema. Do not " +
            "call $normalized again until the repair receipt is committed."
    } else {
        "The previous $normalized call failed. Correct that exact call once using the error envelope. " +
            "Do not switch tools or retry unchanged arguments."
    }
}

/** A live failure outranks artifact-complete guidance in the tail capsule. */
fun directControlCapsuleNextAction(
    permittedNextAction: String?,
    latestFailure: String?
): String {
    val failure = latestFailure?.trim()?.takeIf { it.isNotBlank() }
    if (failure?.startsWith("REPAIR_COMMITTED:") == true) {
        return failure.removePrefix("REPAIR_COMMITTED:").trim()
    }
    if (failure?.contains("RUN_CONFIG_INVALID", ignoreCase = true) == true) {
        return "call write_file for .adt/run.json now; content must explicitly contain " +
            "version=1, runtime, entrypoint, and ui. Static WebUI example: " +
            "{\"version\":1,\"runtime\":\"web\",\"entrypoint\":\"index.html\",\"ui\":\"web\"}. " +
            "Do not use name, script, uri, or command"
    }
    if (failure != null) {
        return "repair the latest failure with one concrete tool call before continuing; " +
            "do not repeat the failed action unchanged: ${failure.take(220)}"
    }
    return permittedNextAction
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "pause with Needs direction; required state has no safe automatic action"
}

/** Conversation-scoped volatile projection of the latest Direct repair boundary. */
data class DirectConversationFailureState(
    val failure: String? = null,
    val failedTool: String? = null
)

class DirectConversationFailureStore {
    private val states = java.util.concurrent.ConcurrentHashMap<Long, DirectConversationFailureState>()

    fun state(conversationId: Long?): DirectConversationFailureState =
        conversationId?.let(states::get) ?: DirectConversationFailureState()

    fun setFailure(conversationId: Long?, failure: String?) {
        update(conversationId) { current ->
            current.copy(failure = failure?.trim()?.takeIf { it.isNotBlank() })
        }
    }

    fun setFailedTool(conversationId: Long?, failedTool: String?) {
        update(conversationId) { current ->
            current.copy(failedTool = failedTool?.trim()?.takeIf { it.isNotBlank() })
        }
    }

    fun restore(conversationId: Long?, failure: String?, failedTool: String?) {
        val id = conversationId ?: return
        val restored = DirectConversationFailureState(
            failure = failure?.trim()?.takeIf { it.isNotBlank() },
            failedTool = failedTool?.trim()?.takeIf { it.isNotBlank() }
        )
        if (restored == DirectConversationFailureState()) states.remove(id) else states[id] = restored
    }

    private fun update(
        conversationId: Long?,
        transform: (DirectConversationFailureState) -> DirectConversationFailureState
    ) {
        val id = conversationId ?: return
        val updated = transform(states[id] ?: DirectConversationFailureState())
        if (updated == DirectConversationFailureState()) states.remove(id) else states[id] = updated
    }
}

/** Reads and discovery do not prove that the last concrete failure was fixed. */
fun directSuccessfulToolClearsFailure(toolName: String): Boolean =
    toolName.trim().lowercase(Locale.ROOT) in setOf(
        "write_file",
        "edit_file",
        "append_file",
        "run_command",
        "run_project",
        "check_project_run",
        "stop_project_run",
        "force_stop_project_run",
        "observe_preview",
        "interact_preview"
    )

/**
 * Launching the project's declared run manifest follows the Agent Manual/Auto mode. The separate
 * command auto-accept switch remains reserved for arbitrary `run_command` execution.
 */
fun directRunProjectNeedsApproval(autoMode: Boolean, isForced: Boolean): Boolean =
    !autoMode && !isForced

fun directConcreteRepairTool(toolName: String): Boolean =
    toolName.trim().lowercase(Locale.ROOT) in setOf(
        "write_file",
        "edit_file",
        "append_file",
        "run_command"
    )

private data class DirectDecodedJsonString(
    val value: String,
    val complete: Boolean
)

private fun decodeDirectJsonStringField(
    text: String,
    field: String
): DirectDecodedJsonString? {
    val match = Regex("\\\"${Regex.escape(field)}\\\"\\s*:\\s*\\\"").find(text)
        ?: return null
    var index = match.range.last + 1
    val decoded = StringBuilder()
    while (index < text.length) {
        val character = text[index++]
        when (character) {
            '"' -> return DirectDecodedJsonString(decoded.toString(), complete = true)
            '\\' -> {
                if (index >= text.length) {
                    return DirectDecodedJsonString(decoded.toString(), complete = false)
                }
                when (val escaped = text[index++]) {
                    '"', '\\', '/' -> decoded.append(escaped)
                    'b' -> decoded.append('\b')
                    'f' -> decoded.append('\u000c')
                    'n' -> decoded.append('\n')
                    'r' -> decoded.append('\r')
                    't' -> decoded.append('\t')
                    'u' -> {
                        if (index + 4 > text.length) {
                            return DirectDecodedJsonString(decoded.toString(), complete = false)
                        }
                        val digits = text.substring(index, index + 4)
                        val codePoint = digits.toIntOrNull(16) ?: return null
                        decoded.append(codePoint.toChar())
                        index += 4
                    }
                    else -> return null
                }
            }
            else -> decoded.append(character)
        }
    }
    return DirectDecodedJsonString(decoded.toString(), complete = false)
}

private fun boundDirectMutationPrefix(value: String, maxBytes: Int): String? {
    val anchor = AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR
    val beforeExistingAnchor = value.substringBefore(anchor)
    val suffix = "\n$anchor"
    val allowedPrefixBytes = maxBytes - suffix.toByteArray(Charsets.UTF_8).size
    if (allowedPrefixBytes < 64) return null

    val bounded = StringBuilder()
    var bytes = 0
    for (character in beforeExistingAnchor) {
        val characterBytes = character.toString().toByteArray(Charsets.UTF_8).size
        if (bytes + characterBytes > allowedPrefixBytes) break
        bounded.append(character)
        bytes += characterBytes
    }
    var prefix = bounded.toString().trimEnd()
    if (prefix.length < 32) return null
    val lastLineBoundary = prefix.lastIndexOf('\n')
    if (lastLineBoundary >= prefix.length / 2) {
        prefix = prefix.substring(0, lastLineBoundary).trimEnd()
    }
    if (prefix.length < 32) return null
    return prefix + suffix
}

private val DIRECT_MODEL_OWNED_CHOICE_TERMS = listOf(
    "research",
    "algorithm",
    "concurrency",
    "parallel execution",
    "provide authoritative information",
    "research area",
    "research first",
    "focus on first",
    "technical approach",
    "implementation approach",
    "which algorithm",
    "which library",
    "which framework",
    "which stack",
    "technology stack",
    "testing approach",
    "test strategy",
    "architecture approach",
    "visual style",
    "download format",
    "proceed with verification",
    "verification checks",
    "functional checks",
    "logic verification",
    "inspect web preview"
)

private val DIRECT_NO_MORE_QUESTIONS_DIRECTIVE = Regex(
    "no (more|further) (optional )?questions|" +
        "do not ask (any )?(more )?questions|" +
        "do not ask (any )?more|" +
        "no m[aá]s preguntas|" +
        "no (hagas|hacer) (m[aá]s )?preguntas",
    RegexOption.IGNORE_CASE
)

private val DIRECT_USER_OWNED_BLOCKER_TERMS = listOf(
    "permission",
    "credential",
    "secret",
    "account",
    "irreversible",
    "delete existing",
    "overwrite existing",
    "external destination",
    "license acceptance"
)

/** Pure exact-match edit used before either local or remote write transport. */
fun applyDirectExactMatchEdit(
    original: String,
    oldText: String,
    newText: String
): Result<String> = runCatching {
    require(oldText.isNotEmpty()) { "EXACT_EDIT_REQUIRED: old_text must not be empty." }
    val firstMatch = original.indexOf(oldText)
    require(firstMatch >= 0) {
        "EXACT_EDIT_NO_MATCH: old_text was not found; reread the file and retry once with the exact current text."
    }
    require(original.indexOf(oldText, firstMatch + oldText.length) < 0) {
        "EXACT_EDIT_AMBIGUOUS: old_text occurs more than once; include more surrounding text so the match is unique."
    }
    original.replaceRange(firstMatch, firstMatch + oldText.length, newText)
}

/** Bounded preview plus a private reference; the full output is never rewritten. */
data class DirectBoundedToolOutput(
    val preview: String,
    val fullOutputReference: String,
    val originalCharacterCount: Int
)

fun boundDirectToolOutput(
    output: String,
    fullOutputReference: String,
    maxCharacters: Int = 8_000,
    tailCharacters: Int = maxCharacters / 5
): DirectBoundedToolOutput {
    require(maxCharacters > 32) { "maxCharacters must leave room for a useful preview" }
    require(tailCharacters in 0 until maxCharacters)
    val preview = if (output.length <= maxCharacters) {
        output
    } else {
        val head = (maxCharacters - tailCharacters - 5).coerceAtLeast(1)
        output.take(head) + "\n...\n" + output.takeLast(tailCharacters)
    }
    return DirectBoundedToolOutput(preview, fullOutputReference, output.length)
}

/** Entries are eligible for compaction only after a complete tool boundary. */
data class DirectHistoryEntry(
    val sequence: Long,
    val kind: String,
    val tokenCount: Int,
    val completeToolBoundary: Boolean = false,
    val reference: String? = null
)

data class DirectCompactionPlan(
    val shouldCompact: Boolean,
    val cutBeforeIndex: Int? = null,
    val retainedRecentTokenCount: Int = 0,
    val summarizedEntryCount: Int = 0,
    val reason: String
)

/**
 * Pick a safe cut only at a complete call/result boundary.  The caller runs
 * its structured summarizer in a fresh no-cache session and keeps the recent
 * tail; if no safe boundary exists it must pause rather than prune text.
 */
fun planDirectCompaction(
    entries: List<DirectHistoryEntry>,
    measuredInputTokens: Int,
    availableInputTokens: Int,
    retainRecentTokens: Int = 4_096,
    threshold: Double = 0.80
): DirectCompactionPlan {
    require(availableInputTokens > 0)
    require(retainRecentTokens > 0)
    require(threshold in 0.0..1.0)
    // Use the same ceiling boundary as Direct request admission so a
    // non-integral 80% capacity cannot trigger one token early.
    val trigger = directPromptCompactionThresholdTokens(
        availableInputTokens = availableInputTokens,
        thresholdRatio = threshold
    )
    if (measuredInputTokens < trigger) {
        return DirectCompactionPlan(false, reason = "below_threshold")
    }
    if (entries.isEmpty()) {
        return DirectCompactionPlan(false, reason = "no_history")
    }

    val totalTokens = entries.sumOf { it.tokenCount.coerceAtLeast(0) }
    var retainedTokens = totalTokens
    var cutBefore: Int? = null
    // A cut is valid after a complete result when the entire newer suffix,
    // including all of its calls/results, fits the recent-tail allowance.
    entries.forEachIndexed { index, entry ->
        if (cutBefore != null || !entry.completeToolBoundary) return@forEachIndexed
        val suffixTokens = totalTokens - entries
            .subList(0, index + 1)
            .sumOf { it.tokenCount.coerceAtLeast(0) }
        if (suffixTokens in 1..retainRecentTokens) {
            cutBefore = index + 1
            retainedTokens = suffixTokens
        }
    }
    val safeCut = cutBefore
    if (safeCut == null || safeCut == entries.size) {
        return DirectCompactionPlan(false, reason = "no_safe_tool_boundary")
    }
    return DirectCompactionPlan(
        shouldCompact = true,
        cutBeforeIndex = safeCut,
        retainedRecentTokenCount = retainedTokens,
        summarizedEntryCount = safeCut,
        reason = "safe_tool_boundary"
    )
}

/** Deterministic fallback when fresh-session summarization fails. */
fun directDeterministicReceiptSummary(
    goal: String,
    decisions: List<String>,
    changes: List<String>,
    tests: List<String>,
    failures: List<String>,
    nextAction: String
): String = buildString {
    appendLine("goal: ${goal.singleLine()}")
    appendLine("decisions: ${decisions.joinToString("; ") { it.singleLine() }}")
    appendLine("changes: ${changes.joinToString("; ") { it.singleLine() }}")
    appendLine("tests: ${tests.joinToString("; ") { it.singleLine() }}")
    appendLine("failures: ${failures.joinToString("; ") { it.singleLine() }}")
    appendLine("next_action: ${nextAction.singleLine()}")
}.trimEnd()

private fun String.singleLine(): String = replace(Regex("\\s+"), " ").trim()

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
