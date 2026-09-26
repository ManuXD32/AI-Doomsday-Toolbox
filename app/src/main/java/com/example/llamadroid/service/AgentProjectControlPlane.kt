package com.example.llamadroid.service

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.data.db.AgentExecutionProfile
import com.example.llamadroid.data.db.AgentDirectRuntime
import com.example.llamadroid.data.db.AgentContinuationOutboxEntity
import com.example.llamadroid.data.db.AgentContinuationStatus
import com.example.llamadroid.data.db.AgentInvocationEntity
import com.example.llamadroid.data.db.AgentPlanVersionEntity
import com.example.llamadroid.data.db.AgentProjectStateEntity
import com.example.llamadroid.data.db.AgentTodoEntity
import com.example.llamadroid.data.db.AgentWorkReportEntity
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

internal const val AGENT_CONTROL_PLANE_VERSION = 1

private const val COMPLETION_RECEIPT_SCAN_LIMIT = 64

internal object AgentTodoStatus {
    const val PENDING = "PENDING"
    const val READY = "READY"
    const val IN_PROGRESS = "IN_PROGRESS"
    const val READY_FOR_REVIEW = "READY_FOR_REVIEW"
    const val NEEDS_FIX = "NEEDS_FIX"
    const val READY_FOR_VERIFICATION = "READY_FOR_VERIFICATION"
    const val VERIFIED = "VERIFIED"
    const val COMPLETED = "COMPLETED"
    const val BLOCKED = "BLOCKED"
    const val CANCELLED = "CANCELLED"

    val all: Set<String> = setOf(
        PENDING,
        READY,
        IN_PROGRESS,
        READY_FOR_REVIEW,
        NEEDS_FIX,
        READY_FOR_VERIFICATION,
        VERIFIED,
        COMPLETED,
        BLOCKED,
        CANCELLED
    )
    val actionPriority: List<String> = listOf(
        IN_PROGRESS,
        NEEDS_FIX,
        READY_FOR_REVIEW,
        READY_FOR_VERIFICATION,
        VERIFIED,
        READY,
        BLOCKED,
        PENDING
    )
    val terminal: Set<String> = setOf(COMPLETED, CANCELLED)
    val open: Set<String> = setOf(
        PENDING,
        READY,
        IN_PROGRESS,
        READY_FOR_REVIEW,
        NEEDS_FIX,
        READY_FOR_VERIFICATION,
        VERIFIED,
        BLOCKED
    )
}

internal object AgentCompactionStatus {
    const val REQUESTED = "REQUESTED"
    const val RUNNING = "RUNNING"
    const val APPLIED = "APPLIED"
    const val SATURATED = "SATURATED"
    const val FAILED = "FAILED"
}

internal data class StructuredPlanTodo(
    val id: String,
    val phaseId: String,
    val phaseTitle: String,
    val text: String,
    val ownerRole: String,
    val dependencies: List<String>,
    val acceptanceCriteria: List<String>,
    val priority: String = "NORMAL"
)

internal data class StructuredApprovedPlan(
    val id: String,
    val summary: String,
    val markdown: String,
    val planHash: String,
    val todos: List<StructuredPlanTodo>,
    val structuredJson: String
)

internal data class AgentPlanMaterializationResult(
    val planVersion: AgentPlanVersionEntity,
    val todos: List<AgentTodoEntity>,
    val created: Boolean,
    val stateRevision: Long
)

internal data class AgentWorkReportTransition(
    val report: AgentWorkReportEntity,
    val previousTodoStatus: String?,
    val nextTodoStatus: String?,
    val nextOwnerRole: String?,
    val stateRevision: Long
) {
    fun compactEnvelope(): String {
        return AgentRuntimeSupport.compactSpecialistReportReceipt(
            reportId = report.id,
            role = report.agentRole,
            status = report.status,
            summary = report.summary,
            todoId = report.todoId,
            todoStatus = nextTodoStatus,
            nextAction = recommendedAction()
        )
    }

    private fun recommendedAction(): String = when (nextTodoStatus) {
        AgentTodoStatus.READY_FOR_REVIEW ->
            "delegate this TODO to REVIEWER"
        AgentTodoStatus.READY_FOR_VERIFICATION ->
            "delegate this TODO to EXECUTOR"
        AgentTodoStatus.NEEDS_FIX ->
            "delegate this TODO back to CODER with the report findings"
        AgentTodoStatus.BLOCKED ->
            "resolve the blocker or ask the user"
        AgentTodoStatus.VERIFIED ->
            "transition this verified TODO to COMPLETED"
        AgentTodoStatus.COMPLETED ->
            "read project_state and choose the next READY TODO"
        else ->
            "read project_state and follow the permitted next action"
    }
}

internal data class AgentCompactionGateDecision(
    val shouldCompact: Boolean,
    val reason: String,
    val compactionKey: String? = null
)

internal data class AgentCompactionMeasurement(
    val status: String,
    val preTokens: Int,
    val postTokens: Int,
    val savedTokens: Int,
    val minimumUsefulSavings: Int,
    val stateRevision: Long
)

/**
 * Bounded model-facing projection of the current TODO acceptance criteria.
 * Durable TODO rows remain the source of truth; this only controls packet
 * wording for the current request.
 */
internal data class AgentTodoPromptCriteriaProjection(
    val criteria: List<String>,
    val verificationRequired: Boolean
)

/** Semantic result of a concrete runtime check used by the optimized root. */
internal enum class AgentVerificationDisposition {
    PASS,
    FAIL,
    PENDING,
    NOT_APPLICABLE
}

internal data class AgentVerificationAssessment(
    val disposition: AgentVerificationDisposition,
    val reason: String
)

internal enum class AgentVerificationTransition {
    ENTERED_VERIFY,
    RETURNED_TO_BUILD,
    RECORDED_IN_VERIFY,
    RECORDED_IN_BUILD,
    ALREADY_APPLIED,
    IGNORED,
    REJECTED_NO_APPROVED_PLAN
}

/** Result of one durable, replay-safe verification phase decision. */
internal data class AgentVerificationTransitionResult(
    val state: AgentProjectStateEntity,
    val disposition: AgentVerificationDisposition,
    val transition: AgentVerificationTransition,
    val actionId: String,
    val eventId: String?,
    val reason: String
)

/**
 * Durable evidence used by the optimized root completion gate. A model
 * supplied validation sentence cannot replace either receipt: the PASS must
 * belong to the active approved plan and be at or after its newest mutation.
 */
internal data class AgentCompletionEvidence(
    val planVersionId: String,
    val planningEpisodeId: String,
    val latestMutationReceipt: AgentContinuationOutboxEntity?,
    val passVerificationReceipt: AgentContinuationOutboxEntity?,
    val actionReceiptScanTruncated: Boolean = false,
    val oldestScannedActionAt: Long? = null
) {
    val latestMutationAt: Long? get() = latestMutationReceipt?.createdAt
    val passVerificationAt: Long? get() = passVerificationReceipt?.createdAt

    /**
     * A fresh PASS is sufficient for the durable check portion of completion;
     * the caller still validates changed-artifact and review fields from the
     * finish_task payload.
    */
    val hasFreshPassAfterLatestMutation: Boolean
        get() {
            val verificationAt = passVerificationAt ?: return false
            val mutationAt = latestMutationAt
            if (
                actionReceiptScanTruncated &&
                latestMutationReceipt == null &&
                (oldestScannedActionAt == null || oldestScannedActionAt > verificationAt)
            ) return false
            return mutationAt == null || verificationAt >= mutationAt
        }
}

/** Result of an explicit root repair request while VERIFY is active. */
internal data class AgentRepairTransitionResult(
    val state: AgentProjectStateEntity,
    val transition: AgentVerificationTransition,
    val actionId: String,
    val eventId: String?,
    val reason: String
)

internal data class AgentDirectReceiptTransition(
    val previousTodoStatus: String?,
    val currentTodoStatus: String?,
    val projectMode: String,
    val changed: Boolean
)

internal object AgentProjectControlPlane {
    const val DIRECT_RUNTIME_VERSION = 1
    const val PROJECT_MODE_PLAN = "PLAN"
    const val PROJECT_MODE_BUILD = "BUILD"
    const val PROJECT_MODE_VERIFY = "VERIFY"

    private const val VERIFICATION_PHASE_RECEIPT_KIND =
        "VERIFICATION_PHASE_TRANSITION"
    private const val VERIFICATION_REPAIR_RECEIPT_KIND =
        "VERIFICATION_REPAIR_TRANSITION"

    private val stateCache =
        ConcurrentHashMap<Long, AgentProjectStateEntity>()

    private val sequentialWorkerRoles = setOf(
        "CODER",
        "REVIEWER",
        "EXECUTOR",
        "VISUAL_TESTER",
        "SUMMARIZER"
    )

    private val planningSpecialists = setOf(
        "CODEBASE_SCOUT",
        "RESEARCHER",
        "PLANNER"
    )

    private val validTodoTransitions: Map<String, Set<String>> = mapOf(
        AgentTodoStatus.PENDING to setOf(
            AgentTodoStatus.READY,
            AgentTodoStatus.BLOCKED,
            AgentTodoStatus.CANCELLED
        ),
        AgentTodoStatus.READY to setOf(
            AgentTodoStatus.IN_PROGRESS,
            AgentTodoStatus.VERIFIED,
            AgentTodoStatus.BLOCKED,
            AgentTodoStatus.CANCELLED
        ),
        AgentTodoStatus.IN_PROGRESS to setOf(
            AgentTodoStatus.READY_FOR_REVIEW,
            AgentTodoStatus.NEEDS_FIX,
            AgentTodoStatus.READY_FOR_VERIFICATION,
            AgentTodoStatus.VERIFIED,
            AgentTodoStatus.COMPLETED,
            AgentTodoStatus.BLOCKED,
            AgentTodoStatus.CANCELLED
        ),
        AgentTodoStatus.READY_FOR_REVIEW to setOf(
            AgentTodoStatus.IN_PROGRESS,
            AgentTodoStatus.NEEDS_FIX,
            AgentTodoStatus.READY_FOR_VERIFICATION,
            AgentTodoStatus.BLOCKED
        ),
        AgentTodoStatus.NEEDS_FIX to setOf(
            AgentTodoStatus.IN_PROGRESS,
            AgentTodoStatus.BLOCKED,
            AgentTodoStatus.CANCELLED
        ),
        AgentTodoStatus.READY_FOR_VERIFICATION to setOf(
            AgentTodoStatus.IN_PROGRESS,
            AgentTodoStatus.VERIFIED,
            AgentTodoStatus.COMPLETED,
            AgentTodoStatus.NEEDS_FIX,
            AgentTodoStatus.BLOCKED
        ),
        AgentTodoStatus.VERIFIED to setOf(
            AgentTodoStatus.COMPLETED,
            AgentTodoStatus.NEEDS_FIX
        ),
        AgentTodoStatus.BLOCKED to setOf(
            AgentTodoStatus.READY,
            AgentTodoStatus.NEEDS_FIX,
            AgentTodoStatus.CANCELLED
        ),
        AgentTodoStatus.COMPLETED to emptySet(),
        AgentTodoStatus.CANCELLED to emptySet()
    )

    fun cachedState(conversationId: Long): AgentProjectStateEntity? =
        stateCache[conversationId]

    fun cacheState(state: AgentProjectStateEntity?) {
        if (state != null) {
            stateCache[state.conversationId] = state
        }
    }

    /**
     * Research admission counters stay in planning packets and durable
     * receipts. Optimized Build/Verify packets omit only the model-facing
     * budget prose because those phases must not perform research.
     */
    internal fun shouldIncludeResearchBudgetInControlPacket(
        executionProfile: String,
        mode: String?
    ): Boolean {
        val normalizedMode = mode?.trim()?.uppercase(Locale.ROOT)
        return AgentExecutionProfile.normalize(executionProfile) !=
            AgentExecutionProfile.DIRECT ||
            normalizedMode != PROJECT_MODE_BUILD && normalizedMode != PROJECT_MODE_VERIFY
    }

    /**
     * Removes only the exact generated verification criterion in an optimized
     * packet. The durable criterion is represented by an explicit marker,
     * while every other criterion remains model-visible.
     */
    internal fun projectTodoAcceptanceCriteriaForPrompt(
        todoText: String,
        criteria: List<String>,
        optimized: Boolean
    ): AgentTodoPromptCriteriaProjection {
        if (!optimized) {
            return AgentTodoPromptCriteriaProjection(
                criteria = criteria.take(6),
                verificationRequired = false
            )
        }
        val generatedVerificationCriterion =
            "Complete and verify: ${todoText.take(240)}"
        val generatedCriterionPresent = criteria.any {
            it == generatedVerificationCriterion
        }
        return AgentTodoPromptCriteriaProjection(
            criteria = criteria.filterNot {
                it == generatedVerificationCriterion
            },
            verificationRequired = generatedCriterionPresent
        )
    }

    /** True when a non-Plan hydration request must retain durable VERIFY. */
    fun preservesVerifyDuringHydration(
        currentMode: String?,
        requestedMode: String?
    ): Boolean = currentMode?.equals(PROJECT_MODE_VERIFY, ignoreCase = true) == true &&
        requestedMode?.equals(PROJECT_MODE_BUILD, ignoreCase = true) == true

    /**
     * Classifies only concrete check results. Starting a command, reading a
     * file, or observing a still-loading process does not pass verification.
     * A local web RUNNING status only proves that a preview is serving; the
     * root must inspect the loaded preview separately. Terminal Python
     * projects pass only after an explicit zero exit code.
     */
    fun classifyVerificationCheck(
        toolName: String,
        rawResult: String
    ): AgentVerificationAssessment {
        val tool = toolName.trim().lowercase(Locale.ROOT)
        val supported = setOf(
            "check_project_run",
            "check_command",
            "wait_command",
            "observe_preview"
        )
        if (tool !in supported) {
            return AgentVerificationAssessment(
                disposition = AgentVerificationDisposition.NOT_APPLICABLE,
                reason = "Tool $tool is not a verification check."
            )
        }

        val fields = verificationFields(rawResult)
        val status = fields["status"].orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
        val previewUrl = fields["preview_url"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fields["url"]?.trim()?.takeIf { it.isNotBlank() }
        val exitCode = parseVerificationExitCode(fields, rawResult)

        if (
            status.contains("fail") ||
            status.contains("error") ||
            status.contains("crash") ||
            status.contains("abort") ||
            exitCode != null && exitCode != 0
        ) {
            return AgentVerificationAssessment(
                disposition = AgentVerificationDisposition.FAIL,
                reason = "The check reported a failed status or non-zero exit code."
            )
        }

        if (tool == "observe_preview") {
            if (hasStructuredVerificationError(fields)) {
                return AgentVerificationAssessment(
                    disposition = AgentVerificationDisposition.FAIL,
                    reason = "Preview observation reported a structured error."
                )
            }
            val screenshotPath = fields["screenshot_path"]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val screenshotBytes = fields["screenshot_bytes"]?.toLongOrNull() ?: 0L
            val loadProgress = fields["load_progress"]?.toIntOrNull() ?: 0
            val bodyText = fields["body_text"]?.trim().orEmpty()
            val controls = fields["controls"]?.trim().orEmpty()
            val hasDomEvidence = bodyText.isNotBlank() ||
                controls.isNotBlank() && controls != "[]" && controls != "null"
            val hasVisualEvidence = screenshotPath != null || screenshotBytes > 0L
            if (loadProgress >= 100 && (hasDomEvidence || hasVisualEvidence)) {
                return AgentVerificationAssessment(
                    disposition = AgentVerificationDisposition.PASS,
                    reason = "Preview evidence was captured."
                )
            }
            return AgentVerificationAssessment(
                disposition = AgentVerificationDisposition.PENDING,
                reason = "Preview evidence is not available yet."
            )
        }

        val runningStatuses = setOf(
            "running",
            "starting",
            "pending",
            "queued",
            "waiting"
        )
        if (tool == "check_project_run" && status == "running" && previewUrl != null) {
            return AgentVerificationAssessment(
                disposition = AgentVerificationDisposition.PENDING,
                reason = "The project is serving a preview; inspect it with observe_preview."
            )
        }
        if (status in runningStatuses) {
            return AgentVerificationAssessment(
                disposition = AgentVerificationDisposition.PENDING,
                reason = "The checked process is still running or waiting."
            )
        }

        val successfulStatuses = setOf(
            "success",
            "successful",
            "passed",
            "pass",
            "complete",
            "completed",
            "succeeded",
            "ok"
        )
        if (
            exitCode == 0 &&
            (
                status in successfulStatuses ||
                    status.contains("finished") ||
                    status == "stopped"
                )
        ) {
            return AgentVerificationAssessment(
                disposition = AgentVerificationDisposition.PASS,
                reason = "The checked process completed with exit code 0."
            )
        }

        return AgentVerificationAssessment(
            disposition = AgentVerificationDisposition.PENDING,
            reason = "The check does not contain completed passing evidence."
        )
    }

    /**
     * Reads the bounded durable receipts needed by the optimized root's
     * finish gate. Both reads are scoped to the active plan episode, so a PASS
     * from an older plan cannot unlock the current one. The existing action
     * receipt is reused when it already follows the latest mutation; callers
     * should only schedule another check when this predicate is false.
     */
    suspend fun readCompletionEvidence(
        database: AppDatabase,
        conversationId: Long,
        planVersionId: String,
        planningEpisodeId: String = planVersionId
    ): AgentCompletionEvidence = database.withTransaction {
        val normalizedPlanId = planVersionId.trim()
        val normalizedEpisodeId = planningEpisodeId.trim()
        if (normalizedPlanId.isBlank() || normalizedEpisodeId.isBlank()) {
            return@withTransaction AgentCompletionEvidence(
                planVersionId = normalizedPlanId,
                planningEpisodeId = normalizedEpisodeId,
                latestMutationReceipt = null,
                passVerificationReceipt = null
            )
        }

        val dao = database.agentWorkflowDao()
        val activePlan = dao.getPlanVersionById(normalizedPlanId)
        val state = dao.getProjectState(conversationId)
        if (
            activePlan == null ||
            activePlan.conversationId != conversationId ||
            !activePlan.status.equals("APPROVED", ignoreCase = true) ||
            state?.activePlanVersionId != normalizedPlanId
        ) {
            return@withTransaction AgentCompletionEvidence(
                planVersionId = normalizedPlanId,
                planningEpisodeId = normalizedEpisodeId,
                latestMutationReceipt = null,
                passVerificationReceipt = null
            )
        }
        val actionReceipts = dao.getLatestActionReceiptsForEpisode(
            conversationId = conversationId,
            planningEpisodeId = normalizedEpisodeId,
            limit = COMPLETION_RECEIPT_SCAN_LIMIT + 1
        )
        val latestMutation = actionReceipts.firstOrNull { receipt ->
            isValidMutationReceipt(receipt, normalizedEpisodeId)
        }
        val passVerification = dao.getLatestCompletedPassVerificationReceipt(
            conversationId = conversationId,
            planningEpisodeId = normalizedEpisodeId,
            planVersionId = normalizedPlanId
        )?.takeIf { receipt ->
            isValidPassVerificationReceipt(
                receipt = receipt,
                planningEpisodeId = normalizedEpisodeId,
                planVersionId = normalizedPlanId
            )
        }
        AgentCompletionEvidence(
            planVersionId = normalizedPlanId,
            planningEpisodeId = normalizedEpisodeId,
            latestMutationReceipt = latestMutation,
            passVerificationReceipt = passVerification,
            actionReceiptScanTruncated = actionReceipts.size > COMPLETION_RECEIPT_SCAN_LIMIT,
            oldestScannedActionAt = actionReceipts.minOfOrNull { it.createdAt }
        )
    }

    private fun isValidPassVerificationReceipt(
        receipt: AgentContinuationOutboxEntity,
        planningEpisodeId: String,
        planVersionId: String
    ): Boolean {
        if (
            receipt.kind != VERIFICATION_PHASE_RECEIPT_KIND ||
            receipt.status != AgentContinuationStatus.COMPLETED
        ) return false
        val payload = runCatching { JSONObject(receipt.payloadJson) }.getOrNull()
            ?: return false
        return payload.optString("receipt_type") == "verification_phase" &&
            payload.optString("planning_episode_id") == planningEpisodeId &&
            payload.optString("plan_version_id") == planVersionId &&
            payload.optString("disposition").equals("PASS", ignoreCase = true)
    }

    private fun isValidMutationReceipt(
        receipt: AgentContinuationOutboxEntity,
        planningEpisodeId: String
    ): Boolean {
        if (
            receipt.kind != "ACTION_RECEIPT" ||
            receipt.status !in setOf(
                AgentContinuationStatus.COMPLETED,
                AgentContinuationStatus.FAILED
            )
        ) return false
        val payload = runCatching { JSONObject(receipt.payloadJson) }.getOrNull()
            ?: return false
        val tool = payload.optString("tool").trim().lowercase(Locale.ROOT)
        val knownNonMutatingTool = tool in setOf(
            "agent_report_read",
            "check_command",
            "check_project_run",
            "command_list",
            "fetch_url",
            "file_line_count",
            "get_datetime",
            "sleep_until",
            "kb_list_sources",
            "kb_read_chunk",
            "kb_search",
            "kiwix_search",
            "list_directory",
            "list_memory",
            "observe_preview",
            "plan_read",
            "project_order_read",
            "project_state_read",
            "propose_plan",
            "question",
            "read_file",
            "read_file_lines",
            "read_memory",
            "read_skill_resource",
            "reflection",
            "report_progress",
            "run_tools_sequential",
            "search_code",
            "skill",
            "todo_read",
            "todo_reconcile",
            "todo_transition",
            "todo_write",
            "tool_help",
            "view_image",
            "wait_command",
            "web_search",
            "write_memory",
            "rewrite_memory",
            "delete_memory",
            "finish_task",
            "call_agent"
        )
        return payload.optString("receipt_type") == "action" &&
            payload.optString("planning_episode_id") == planningEpisodeId &&
            tool.isNotBlank() &&
            !knownNonMutatingTool
    }

    /**
     * Applies a concrete verification result to the durable project phase.
     * The action ID is persisted in a completed outbox receipt, making a
     * replay a no-op even after another semantic event changed lastSemanticEvent.
     */
    suspend fun applyVerificationResult(
        context: Context,
        conversationId: Long,
        toolName: String,
        actionId: String,
        rawResult: String,
        planningEpisodeId: String? = null
    ): AgentVerificationTransitionResult = withContext(Dispatchers.IO) {
        applyVerificationResult(
            database = AppDatabase.getDatabase(context.applicationContext),
            conversationId = conversationId,
            toolName = toolName,
            actionId = actionId,
            rawResult = rawResult,
            planningEpisodeId = planningEpisodeId
        )
    }

    /** Database overload keeps this transition directly testable with Room. */
    suspend fun applyVerificationResult(
        database: AppDatabase,
        conversationId: Long,
        toolName: String,
        actionId: String,
        rawResult: String,
        planningEpisodeId: String? = null
    ): AgentVerificationTransitionResult {
        val normalizedActionId = actionId.trim()
        require(normalizedActionId.isNotBlank()) {
            "A stable action ID is required for verification."
        }
        val normalizedTool = toolName.trim().lowercase(Locale.ROOT)
        require(normalizedTool.isNotBlank()) {
            "A tool name is required for verification."
        }
        val assessment = classifyVerificationCheck(normalizedTool, rawResult)
        val result = database.withTransaction {
            val dao = database.agentWorkflowDao()
            val current = dao.getProjectState(conversationId)
                ?: error("Project state is required before verification.")
            val normalizedMode = current.mode.trim().uppercase(Locale.ROOT)
            val planId = current.activePlanVersionId?.trim()
                ?.takeIf { it.isNotBlank() }
            val approvedPlan = planId?.let { dao.getPlanVersionById(it) }
                ?.takeIf {
                    it.conversationId == conversationId &&
                        it.status.equals("APPROVED", ignoreCase = true)
                }

            if (assessment.disposition !in setOf(
                    AgentVerificationDisposition.PASS,
                    AgentVerificationDisposition.FAIL
                )
            ) {
                return@withTransaction AgentVerificationTransitionResult(
                    state = current,
                    disposition = assessment.disposition,
                    transition = AgentVerificationTransition.IGNORED,
                    actionId = normalizedActionId,
                    eventId = null,
                    reason = assessment.reason
                )
            }

            if (approvedPlan == null) {
                return@withTransaction AgentVerificationTransitionResult(
                    state = current,
                    disposition = assessment.disposition,
                    transition = AgentVerificationTransition.REJECTED_NO_APPROVED_PLAN,
                    actionId = normalizedActionId,
                    eventId = null,
                    reason = "Verification requires the active plan to be approved."
                )
            }

            val episode = planningEpisodeId?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: approvedPlan.id
            val actionKey = sha256(
                "$conversationId|$episode|${approvedPlan.id}|$normalizedTool|$normalizedActionId"
            )
            val eventPrefix = if (
                assessment.disposition == AgentVerificationDisposition.PASS
            ) {
                "verification_passed"
            } else {
                "verification_failed"
            }
            val eventId = "$eventPrefix:${actionKey.take(32)}"
            val receiptId = "verification-phase:${actionKey.take(48)}"

            if (current.lastSemanticEvent == eventId) {
                return@withTransaction AgentVerificationTransitionResult(
                    state = current,
                    disposition = assessment.disposition,
                    transition = AgentVerificationTransition.ALREADY_APPLIED,
                    actionId = normalizedActionId,
                    eventId = eventId,
                    reason = "This verification action was already applied."
                )
            }
            if (dao.getContinuationReceiptById(receiptId) != null) {
                return@withTransaction AgentVerificationTransitionResult(
                    state = current,
                    disposition = assessment.disposition,
                    transition = AgentVerificationTransition.ALREADY_APPLIED,
                    actionId = normalizedActionId,
                    eventId = eventId,
                    reason = "This verification action was already applied."
                )
            }

            val transition = when (assessment.disposition) {
                AgentVerificationDisposition.PASS -> when (normalizedMode) {
                    PROJECT_MODE_BUILD -> AgentVerificationTransition.ENTERED_VERIFY
                    PROJECT_MODE_VERIFY -> AgentVerificationTransition.RECORDED_IN_VERIFY
                    else -> AgentVerificationTransition.IGNORED
                }

                AgentVerificationDisposition.FAIL -> when (normalizedMode) {
                    PROJECT_MODE_VERIFY -> AgentVerificationTransition.RETURNED_TO_BUILD
                    PROJECT_MODE_BUILD -> AgentVerificationTransition.RECORDED_IN_BUILD
                    else -> AgentVerificationTransition.IGNORED
                }

                else -> AgentVerificationTransition.IGNORED
            }
            if (transition == AgentVerificationTransition.IGNORED) {
                return@withTransaction AgentVerificationTransitionResult(
                    state = current,
                    disposition = assessment.disposition,
                    transition = transition,
                    actionId = normalizedActionId,
                    eventId = null,
                    reason = "Verification phase changes are allowed only from BUILD or VERIFY."
                )
            }

            val targetMode = if (assessment.disposition ==
                AgentVerificationDisposition.PASS
            ) {
                PROJECT_MODE_VERIFY
            } else {
                PROJECT_MODE_BUILD
            }
            val now = System.currentTimeMillis()
            val phaseReceipt = AgentContinuationOutboxEntity(
                id = receiptId,
                conversationId = conversationId,
                kind = VERIFICATION_PHASE_RECEIPT_KIND,
                dedupeKey = receiptId,
                payloadJson = JSONObject()
                    .put("receipt_type", "verification_phase")
                    .put("tool", normalizedTool)
                    .put("action_id", normalizedActionId)
                    .put("planning_episode_id", episode)
                    .put("plan_version_id", approvedPlan.id)
                    .put("disposition", assessment.disposition.name)
                    .put("target_mode", targetMode)
                    .toString(),
                status = AgentContinuationStatus.COMPLETED,
                createdAt = now,
                updatedAt = now
            )
            if (dao.insertContinuationReceipt(phaseReceipt) == 0L) {
                return@withTransaction AgentVerificationTransitionResult(
                    state = dao.getProjectState(conversationId) ?: current,
                    disposition = assessment.disposition,
                    transition = AgentVerificationTransition.ALREADY_APPLIED,
                    actionId = normalizedActionId,
                    eventId = eventId,
                    reason = "This verification action was already applied."
                )
            }

            if (normalizedMode != targetMode) {
                require(
                    dao.updateProjectStateBasics(
                        conversationId = conversationId,
                        mode = targetMode,
                        currentGoal = null
                    ) == 1
                ) {
                    "Project state changed while applying verification."
                }
            }
            require(
                dao.bumpProjectStateRevision(
                    conversationId = conversationId,
                    semanticEvent = eventId
                ) == 1
            ) {
                "Project state disappeared while recording verification."
            }
            val updated = dao.getProjectState(conversationId)
                ?: error("Project state missing after verification transition.")
            AgentVerificationTransitionResult(
                state = updated,
                disposition = assessment.disposition,
                transition = transition,
                actionId = normalizedActionId,
                eventId = eventId,
                reason = assessment.reason
            )
        }
        cacheState(result.state)
        return result
    }

    /**
     * Records an explicit report_progress build repair from VERIFY. This is
     * separate from runtime-check classification because a model can discover
     * a UI defect that the run status cannot represent. The repair summary is
     * returned to the caller but only its hash is kept in durable metadata.
     */
    suspend fun applyRepairProgress(
        context: Context,
        conversationId: Long,
        phase: String,
        actionId: String,
        summary: String,
        planningEpisodeId: String? = null
    ): AgentRepairTransitionResult = withContext(Dispatchers.IO) {
        applyRepairProgress(
            database = AppDatabase.getDatabase(context.applicationContext),
            conversationId = conversationId,
            phase = phase,
            actionId = actionId,
            summary = summary,
            planningEpisodeId = planningEpisodeId
        )
    }

    /**
     * Projects successful Direct Agent receipts onto runtime-owned TODO state.
     * The model never needs TODO maintenance tools: the first mutation starts the
     * current step, verification receipts advance it, and a successful final
     * receipt completes it. Exact-status updates make replay a no-op.
     *
     * This function is intended to run inside the caller's Room transaction,
     * immediately after the action receipt is committed.
     */
    suspend fun applyDirectToolReceipt(
        database: AppDatabase,
        conversationId: Long,
        toolName: String,
        actionId: String,
        successful: Boolean,
        completionStatus: String? = null,
        /** Explicit PASS from the phase classifier; null falls back to Room evidence. */
        verificationPassed: Boolean? = null
    ): AgentDirectReceiptTransition {
        val dao = database.agentWorkflowDao()
        val state = dao.getProjectState(conversationId)
            ?: return AgentDirectReceiptTransition(null, null, PROJECT_MODE_PLAN, false)
        val todos = dao.getTodos(conversationId)
        val current = state.currentTodoId?.let { todoId -> dao.getTodoById(todoId) }
            ?: chooseNextTodo(todos)
        if (!successful || current == null) {
            return AgentDirectReceiptTransition(current?.status, current?.status, state.mode, false)
        }

        val normalizedTool = toolName.trim().lowercase(Locale.ROOT)
        val mutationTools = setOf(
            "write_file",
            "edit_file",
            "append_file",
            "edit_lines",
            "apply_patch",
            "create_folder",
            "generate_image",
            "remove_image_background",
            "install_python_dependency"
        )
        val verificationTools = setOf(
            "check_project_run",
            "check_command",
            "wait_command",
            "observe_preview",
            "interact_preview"
        )
        val normalizedCompletion = completionStatus
            ?.trim()
            ?.uppercase(Locale.ROOT)
            .orEmpty()
        val finishSucceeded = normalizedTool == "finish_task" &&
            normalizedCompletion !in setOf(
                "FAILED", "FAIL", "ERROR", "BLOCKED", "CANCELLED", "CANCELED", "INTERRUPTED"
            )
        val directVerificationPass = if (normalizedTool in verificationTools) {
            verificationPassed ?: state.activePlanVersionId?.let { planVersionId ->
                dao.getCompletedPassVerificationReceiptForAction(
                    conversationId = conversationId,
                    actionId = actionId,
                    planVersionId = planVersionId
                ) != null
            } ?: false
        } else {
            false
        }
        val hasUnfinishedOtherTodos = todos.any { todo ->
            todo.id != current.id && todo.status !in AgentTodoStatus.terminal
        }
        val targetStatus = when {
            normalizedTool in mutationTools && current.status in setOf(
                AgentTodoStatus.READY,
                AgentTodoStatus.NEEDS_FIX
            ) -> AgentTodoStatus.IN_PROGRESS

            normalizedTool in verificationTools &&
                directVerificationPass &&
                state.mode.equals(PROJECT_MODE_VERIFY, ignoreCase = true) &&
                current.status in setOf(
                    AgentTodoStatus.READY,
                    AgentTodoStatus.IN_PROGRESS,
                    AgentTodoStatus.READY_FOR_VERIFICATION
                ) -> AgentTodoStatus.VERIFIED

            normalizedTool in verificationTools &&
                current.status == AgentTodoStatus.IN_PROGRESS -> AgentTodoStatus.READY_FOR_VERIFICATION

            finishSucceeded && state.mode.equals(PROJECT_MODE_BUILD, ignoreCase = true) &&
                hasUnfinishedOtherTodos &&
                current.status == AgentTodoStatus.IN_PROGRESS -> AgentTodoStatus.COMPLETED

            finishSucceeded && state.mode.equals(PROJECT_MODE_BUILD, ignoreCase = true) &&
                !hasUnfinishedOtherTodos &&
                current.status == AgentTodoStatus.IN_PROGRESS -> AgentTodoStatus.READY_FOR_VERIFICATION

            finishSucceeded && state.mode.equals(PROJECT_MODE_VERIFY, ignoreCase = true) &&
                current.status in setOf(
                    AgentTodoStatus.IN_PROGRESS,
                    AgentTodoStatus.READY_FOR_VERIFICATION,
                    AgentTodoStatus.VERIFIED
                ) -> AgentTodoStatus.COMPLETED

            else -> null
        }
        if (targetStatus == null || targetStatus !in validTodoTransitions[current.status].orEmpty()) {
            return AgentDirectReceiptTransition(current.status, current.status, state.mode, false)
        }

        val evidence = runCatching { JSONArray(current.evidenceJson) }
            .getOrElse { JSONArray() }
            .put(
                JSONObject()
                    .put("source", "DIRECT_TOOL_RECEIPT")
                    .put("tool", normalizedTool)
                    .put("action_id", actionId)
                    .put("status", "SUCCESS")
            )
        val changed = dao.transitionTodoExactlyOnce(
            id = current.id,
            expectedStatus = current.status,
            newStatus = targetStatus,
            ownerRole = null,
            assignedInvocationId = null,
            resultSummary = "$normalizedTool completed successfully.",
            blockReason = null,
            evidenceJson = evidence.toString(),
            completedAt = if (targetStatus == AgentTodoStatus.COMPLETED) System.currentTimeMillis() else null
        ) == 1
        if (changed) {
            unlockDependencyReadyTodos(dao, conversationId)
            val refreshedTodos = dao.getTodos(conversationId)
            val enteringVerify = finishSucceeded &&
                state.mode.equals(PROJECT_MODE_BUILD, ignoreCase = true) &&
                targetStatus == AgentTodoStatus.READY_FOR_VERIFICATION
            val projectCompleted = finishSucceeded &&
                state.mode.equals(PROJECT_MODE_VERIFY, ignoreCase = true) &&
                refreshedTodos.none {
                it.status !in setOf(AgentTodoStatus.COMPLETED, AgentTodoStatus.CANCELLED)
            }
            val next = if (projectCompleted) null else chooseNextTodo(refreshedTodos)
            val advancingToProjectedVerify = finishSucceeded &&
                targetStatus == AgentTodoStatus.COMPLETED &&
                next?.ownerRole.equals("EXECUTOR", ignoreCase = true)
            if (enteringVerify || advancingToProjectedVerify) {
                dao.updateProjectStateBasics(
                    conversationId = conversationId,
                    mode = PROJECT_MODE_VERIFY,
                    currentGoal = null
                )
            } else if (projectCompleted) {
                dao.updateProjectStateBasics(
                    conversationId = conversationId,
                    mode = AgentDirectRuntime.MODE_COMPLETE,
                    currentGoal = null
                )
            } else if (finishSucceeded && targetStatus == AgentTodoStatus.COMPLETED) {
                dao.updateProjectStateBasics(
                    conversationId = conversationId,
                    mode = PROJECT_MODE_BUILD,
                    currentGoal = null
                )
            }
            dao.bumpProjectStateRevision(
                conversationId = conversationId,
                semanticEvent = "direct_receipt:${current.id}:$targetStatus:${actionId.take(48)}"
            )
            dao.setProjectCurrentTodo(conversationId, next?.phaseId, next?.id)
            cacheState(dao.getProjectState(conversationId))
        }
        return AgentDirectReceiptTransition(
            previousTodoStatus = current.status,
            currentTodoStatus = if (changed) targetStatus else current.status,
            projectMode = dao.getProjectState(conversationId)?.mode ?: state.mode,
            changed = changed
        )
    }

    /** Database overload keeps the repair boundary directly testable. */
    suspend fun applyRepairProgress(
        database: AppDatabase,
        conversationId: Long,
        phase: String,
        actionId: String,
        summary: String,
        planningEpisodeId: String? = null
    ): AgentRepairTransitionResult {
        val normalizedPhase = phase.trim().lowercase(Locale.ROOT)
        require(normalizedPhase == "build") {
            "Only report_progress phase=build can request a VERIFY repair."
        }
        val normalizedActionId = actionId.trim()
        require(normalizedActionId.isNotBlank()) {
            "A stable action ID is required for a VERIFY repair."
        }
        val normalizedSummary = summary.trim()
        require(normalizedSummary.isNotBlank()) {
            "A concrete repair summary is required for a VERIFY repair."
        }
        val result = database.withTransaction {
            val dao = database.agentWorkflowDao()
            val current = dao.getProjectState(conversationId)
                ?: error("Project state is required before a VERIFY repair.")
            val planId = current.activePlanVersionId?.trim()
                ?.takeIf { it.isNotBlank() }
            val approvedPlan = planId?.let { dao.getPlanVersionById(it) }
                ?.takeIf {
                    it.conversationId == conversationId &&
                        it.status.equals("APPROVED", ignoreCase = true)
                }
            if (approvedPlan == null) {
                return@withTransaction AgentRepairTransitionResult(
                    state = current,
                    transition = AgentVerificationTransition.REJECTED_NO_APPROVED_PLAN,
                    actionId = normalizedActionId,
                    eventId = null,
                    reason = "A VERIFY repair requires the active plan to be approved."
                )
            }

            val episode = planningEpisodeId?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: approvedPlan.id
            val actionKey = sha256(
                "$conversationId|repair|$episode|${approvedPlan.id}|$normalizedActionId"
            )
            val eventId = "verification_repair:${actionKey.take(32)}"
            val receiptId = "verification-repair:${actionKey.take(48)}"
            if (
                current.lastSemanticEvent == eventId ||
                dao.getContinuationReceiptById(receiptId) != null
            ) {
                return@withTransaction AgentRepairTransitionResult(
                    state = current,
                    transition = AgentVerificationTransition.ALREADY_APPLIED,
                    actionId = normalizedActionId,
                    eventId = eventId,
                    reason = "This VERIFY repair request was already applied."
                )
            }

            if (!current.mode.equals(PROJECT_MODE_VERIFY, ignoreCase = true)) {
                return@withTransaction AgentRepairTransitionResult(
                    state = current,
                    transition = AgentVerificationTransition.IGNORED,
                    actionId = normalizedActionId,
                    eventId = null,
                    reason = "A VERIFY repair request is only actionable while VERIFY is active."
                )
            }

            val now = System.currentTimeMillis()
            val receipt = AgentContinuationOutboxEntity(
                id = receiptId,
                conversationId = conversationId,
                kind = VERIFICATION_REPAIR_RECEIPT_KIND,
                dedupeKey = receiptId,
                payloadJson = JSONObject()
                    .put("receipt_type", "verification_repair")
                    .put("phase", normalizedPhase)
                    .put("action_id", normalizedActionId)
                    .put("planning_episode_id", episode)
                    .put("plan_version_id", approvedPlan.id)
                    .put("summary_hash", sha256(normalizedSummary))
                    .put("target_mode", PROJECT_MODE_BUILD)
                    .toString(),
                status = AgentContinuationStatus.COMPLETED,
                createdAt = now,
                updatedAt = now
            )
            if (dao.insertContinuationReceipt(receipt) == 0L) {
                return@withTransaction AgentRepairTransitionResult(
                    state = dao.getProjectState(conversationId) ?: current,
                    transition = AgentVerificationTransition.ALREADY_APPLIED,
                    actionId = normalizedActionId,
                    eventId = eventId,
                    reason = "This VERIFY repair request was already applied."
                )
            }

            require(
                dao.updateProjectStateBasics(
                    conversationId = conversationId,
                    mode = PROJECT_MODE_BUILD,
                    currentGoal = null
                ) == 1
            ) {
                "Project state changed while applying the VERIFY repair."
            }
            require(
                dao.bumpProjectStateRevision(
                    conversationId = conversationId,
                    semanticEvent = eventId
                ) == 1
            ) {
                "Project state disappeared while recording the VERIFY repair."
            }
            val updated = dao.getProjectState(conversationId)
                ?: error("Project state missing after VERIFY repair.")
            AgentRepairTransitionResult(
                state = updated,
                transition = AgentVerificationTransition.RETURNED_TO_BUILD,
                actionId = normalizedActionId,
                eventId = eventId,
                reason = "Repair requested: ${normalizedSummary.take(240)}"
            )
        }
        cacheState(result.state)
        return result
    }

    suspend fun ensureState(
        context: Context,
        conversationId: Long,
        goal: String? = null,
        mode: String? = null
    ): AgentProjectStateEntity = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(context.applicationContext)
        val contract = AgentDurableContractStore.ensureContract(
            database = database,
            conversationId = conversationId,
            initialGoal = goal
        )
        val dao = database.agentWorkflowDao()
        val requestedMode = mode?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.ROOT)
        dao.insertProjectStateIfMissing(
            AgentProjectStateEntity(
                conversationId = conversationId,
                mode = requestedMode ?: PROJECT_MODE_PLAN,
                currentGoal = contract.initialGoal
            )
        )
        val existingState = dao.getProjectState(conversationId)
            ?: error("Failed to initialize project control state")
        // Restore/hydration callers pass BUILD for every non-Plan turn. Keep
        // a durable VERIFY phase until an explicit verification result moves
        // it back to BUILD, otherwise a process restart loses the phase.
        if (
            requestedMode != null &&
            !preservesVerifyDuringHydration(existingState.mode, requestedMode)
        ) {
            dao.updateProjectStateBasics(
                conversationId = conversationId,
                mode = requestedMode,
                currentGoal = null
            )
        }
        val state = dao.getProjectState(conversationId)
            ?: error("Failed to initialize project control state")
        if (state.currentGoal.isBlank() && contract.initialGoal.isNotBlank()) {
            dao.updateProjectStateBasics(
                conversationId = conversationId,
                mode = null,
                currentGoal = contract.initialGoal
            )
        }
        dao.getProjectState(conversationId)
            ?.also(::cacheState)
            ?: error("Failed to initialize project control state")
    }

    suspend fun noteSemanticEvent(
        context: Context,
        conversationId: Long,
        kind: String,
        goal: String? = null
    ): AgentProjectStateEntity = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val contract = AgentDurableContractStore.ensureContract(
            database = db,
            conversationId = conversationId,
            initialGoal = goal
        )
        val state = db.withTransaction {
            val dao = db.agentWorkflowDao()
            dao.insertProjectStateIfMissing(
                AgentProjectStateEntity(
                    conversationId = conversationId,
                    currentGoal = contract.initialGoal
                )
            )
            dao.bumpProjectStateRevision(
                conversationId = conversationId,
                // The project state is only a compatibility projection. Keep
                // its goal aligned with the write-once contract; later user
                // corrections live in durable decisions.
                currentGoal = contract.initialGoal.takeIf { it.isNotBlank() },
                semanticEvent = kind.take(80)
            )
            dao.getProjectState(conversationId)
                ?: error("Project state disappeared during semantic update")
        }
        cacheState(state)
        state
    }

    suspend fun markNoMoreQuestions(
        context: Context,
        conversationId: Long,
        value: Boolean = true
    ) = AgentDurableContractStore.markNoMoreQuestions(
        context = context,
        conversationId = conversationId,
        value = value
    )

    suspend fun markGreenfield(
        context: Context,
        conversationId: Long,
        value: Boolean = true
    ) = AgentDurableContractStore.markGreenfield(
        context = context,
        conversationId = conversationId,
        value = value
    )

    fun isSequentialWorkerRole(role: String): Boolean =
        role.uppercase(Locale.ROOT) in sequentialWorkerRoles

    fun isPlanningSpecialist(role: String): Boolean =
        role.uppercase(Locale.ROOT) in planningSpecialists

    fun allowedToolsForRole(
        role: String,
        localBackend: Boolean
    ): Set<String> {
        val commonState = setOf(
            "project_state_read",
            "project_order_read",
            "plan_read",
            "agent_report_read",
            "todo_read",
            "reflection",
            "get_datetime",
            "tool_help"
        )
        val codeRead = setOf(
            "read_file",
            "read_file_lines",
            "file_line_count",
            "list_directory",
            "search_code",
            "run_tools_sequential"
        )
        val memoryRead = setOf(
            "read_memory",
            "list_memory"
        )
        val roleName = role.uppercase(Locale.ROOT)
        return when (roleName) {
            "ORCHESTRATOR" -> commonState + setOf(
                "question",
                "sleep_until",
                "todo_write",
                "todo_transition",
                "todo_reconcile",
                "call_agent",
                "propose_plan",
                "report_progress",
                "skill",
                "read_skill_resource"
            )

            "CODEBASE_SCOUT" -> commonState + codeRead +
                setOf("finish_task")

            "RESEARCHER" -> commonState + memoryRead + setOf(
                "web_search",
                "fetch_url",
                "kiwix_search",
                "kb_search",
                "kb_read_chunk",
                "kb_list_sources",
                "finish_task"
            )

            "PLANNER" -> commonState + memoryRead + setOf(
                "finish_task"
            )

            "CODER" -> commonState + codeRead + memoryRead + setOf(
                "write_file",
                "append_file",
                "edit_lines",
                "apply_patch",
                "create_folder",
                "write_memory",
                "rewrite_memory",
                "skill",
                "read_skill_resource",
                "run_skill_script",
                "generate_image",
                "remove_image_background",
                "view_image",
                "finish_task"
            )

            "REVIEWER" -> commonState + codeRead + memoryRead +
                setOf("finish_task")

            "EXECUTOR" -> commonState + codeRead + memoryRead + setOf(
                "run_command",
                "wait_command",
                "check_command",
                "command_list",
                "cancel_command",
                "send_command_input",
                "run_project",
                "check_project_run",
                "stop_project_run",
                "force_stop_project_run",
                "finish_task"
            )

            "SUMMARIZER" -> commonState + setOf(
                "read_memory",
                "list_memory",
                "write_memory",
                "rewrite_memory",
                "delete_memory",
                "finish_task"
            )

            "VISUAL_TESTER" -> setOf(
                "observe_preview",
                "interact_preview",
                "finish_task",
                "tool_help"
            )

            else -> emptySet()
        }.let { tools ->
            if (localBackend) {
                tools - setOf(
                    "run_command",
                    "wait_command",
                    "check_command",
                    "command_list",
                    "cancel_command",
                    "send_command_input",
                    "apply_patch"
                )
            } else {
                tools
            }
        }
    }

    // ADT plan controls v6.2.0: parse user-authored plan Markdown without
    // compiling regular expressions on the approval path. This avoids
    // device/runtime-specific PatternSyntaxException failures while keeping
    // the accepted heading, checklist, numbered-list, sentence, and fenced
    // structured-plan forms deterministic.
    private fun planHeadingTitle(line: String): String? {
        val trimmed = line.trim()
        val hashCount = trimmed.takeWhile { it == '#' }.length
        if (hashCount !in 2..4 || hashCount >= trimmed.length) return null
        if (!trimmed[hashCount].isWhitespace()) return null
        return trimmed.drop(hashCount).trim().takeIf { it.isNotBlank() }
    }

    private fun planListItemText(line: String): String? {
        val trimmed = line.trim()

        fun stripCheckbox(value: String): String {
            val candidate = value.trimStart()
            return if (
                candidate.length >= 3 &&
                candidate[0] == '[' &&
                candidate[2] == ']' &&
                candidate[1] in setOf(' ', 'x', 'X')
            ) {
                candidate.drop(3).trimStart()
            } else {
                candidate
            }
        }

        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            return stripCheckbox(trimmed.drop(2))
                .trim()
                .takeIf { it.length >= 8 }
        }

        val digitCount = trimmed.takeWhile(Char::isDigit).length
        if (digitCount == 0 || digitCount >= trimmed.length) return null
        val marker = trimmed[digitCount]
        if (marker != '.' && marker != ')') return null
        val remainder = trimmed.drop(digitCount + 1)
        if (remainder.isEmpty() || !remainder.first().isWhitespace()) return null
        return remainder.trim().takeIf { it.length >= 8 }
    }

    private fun splitPlanSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text[index] in setOf('.', '!', '?')) {
                var next = index + 1
                while (next < text.length && text[next].isWhitespace()) {
                    next += 1
                }
                if (next > index + 1) {
                    text.substring(start, next)
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let(sentences::add)
                    start = next
                    index = next
                    continue
                }
            }
            index += 1
        }
        text.substring(start)
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let(sentences::add)
        return sentences
    }

    private fun structuredPlanJsonCandidate(markdown: String): String? {
        val trimmed = markdown.trim()
        if (trimmed.startsWith("{")) return trimmed

        var searchFrom = 0
        while (searchFrom < markdown.length) {
            val fenceStart = markdown.indexOf("```", searchFrom)
            if (fenceStart < 0) return null
            val headerEnd = markdown.indexOf('\n', fenceStart + 3)
            if (headerEnd < 0) return null
            val fenceEnd = markdown.indexOf("```", headerEnd + 1)
            if (fenceEnd < 0) return null

            val info = markdown.substring(fenceStart + 3, headerEnd).trim()
            val body = markdown.substring(headerEnd + 1, fenceEnd).trim()
            if (
                (info.isBlank() || info.equals("json", ignoreCase = true)) &&
                body.startsWith("{") &&
                body.endsWith("}") &&
                body.contains("\"phases\"")
            ) {
                return body
            }
            searchFrom = fenceEnd + 3
        }
        return null
    }

    fun parseApprovedPlan(
        summary: String,
        markdown: String
    ): StructuredApprovedPlan {
        val normalizedMarkdown = markdown.replace("\r\n", "\n").trim()
        val planHash = sha256(normalizedMarkdown)
        val structuredFromJson = parseStructuredPlanJson(
            summary,
            normalizedMarkdown,
            planHash
        )
        if (structuredFromJson != null) return structuredFromJson

        var phaseTitle = "Implementation"
        var phaseId = "phase-${planHash.take(8)}-1"
        var phaseIndex = 1
        val rawItems = mutableListOf<Triple<String, String, String>>()

        normalizedMarkdown.lineSequence().forEach { rawLine ->
            planHeadingTitle(rawLine)?.let { heading ->
                phaseTitle = heading.take(120)
                phaseIndex += 1
                phaseId = "phase-${planHash.take(8)}-$phaseIndex"
                return@forEach
            }
            planListItemText(rawLine)?.let { item ->
                rawItems += Triple(
                    phaseId,
                    phaseTitle,
                    item.take(500)
                )
            }
        }

        val fallbackItems = if (rawItems.isEmpty()) {
            splitPlanSentences(normalizedMarkdown)
                .map { it.trim() }
                .filter { it.length >= 20 }
                .take(20)
                .map { Triple(phaseId, phaseTitle, it.take(500)) }
        } else {
            rawItems
        }

        val todos = fallbackItems.mapIndexed { index, (itemPhaseId, title, text) ->
            val id = "todo-${planHash.take(8)}-${(index + 1).toString().padStart(3, '0')}"
            val dependency = if (index == 0) {
                emptyList()
            } else {
                listOf(
                    "todo-${planHash.take(8)}-${index.toString().padStart(3, '0')}"
                )
            }
            StructuredPlanTodo(
                id = id,
                phaseId = itemPhaseId,
                phaseTitle = title,
                text = text,
                ownerRole = inferOwnerRole(text),
                dependencies = dependency,
                acceptanceCriteria = listOf(
                    "Complete and verify: ${text.take(240)}"
                ),
                priority = inferPriority(text)
            )
        }.ifEmpty {
            listOf(
                StructuredPlanTodo(
                    id = "todo-${planHash.take(8)}-001",
                    phaseId = phaseId,
                    phaseTitle = phaseTitle,
                    text = summary.ifBlank { "Implement the approved plan." },
                    ownerRole = "CODER",
                    dependencies = emptyList(),
                    acceptanceCriteria = listOf(
                        "The approved implementation is complete and verified."
                    )
                )
            )
        }

        return structuredPlan(
            id = "plan-${planHash.take(12)}",
            summary = summary,
            markdown = normalizedMarkdown,
            planHash = planHash,
            todos = todos
        )
    }

    private fun parseStructuredPlanJson(
        summary: String,
        markdown: String,
        planHash: String
    ): StructuredApprovedPlan? {
        val candidate = structuredPlanJsonCandidate(markdown) ?: return null

        return runCatching {
            val root = JSONObject(candidate)
            val phases = root.optJSONArray("phases") ?: return@runCatching null
            val todos = mutableListOf<StructuredPlanTodo>()
            for (phaseIndex in 0 until phases.length()) {
                val phase = phases.optJSONObject(phaseIndex) ?: continue
                val phaseId = phase.optString("id").trim().ifBlank {
                    "phase-${planHash.take(8)}-${phaseIndex + 1}"
                }
                val phaseTitle = phase.optString("title").trim().ifBlank {
                    "Phase ${phaseIndex + 1}"
                }
                val phaseTodos = phase.optJSONArray("todos") ?: continue
                for (todoIndex in 0 until phaseTodos.length()) {
                    val todo = phaseTodos.optJSONObject(todoIndex) ?: continue
                    val text = todo.optString("text")
                        .ifBlank { todo.optString("title") }
                        .trim()
                    if (text.isBlank()) continue
                    val stableIndex = todos.size + 1
                    val id = todo.optString("id").trim().ifBlank {
                        "todo-${planHash.take(8)}-${stableIndex.toString().padStart(3, '0')}"
                    }
                    todos += StructuredPlanTodo(
                        id = id.take(96),
                        phaseId = phaseId.take(96),
                        phaseTitle = phaseTitle.take(120),
                        text = text.take(500),
                        ownerRole = todo.optString("owner_role")
                            .trim()
                            .uppercase(Locale.ROOT)
                            .ifBlank { inferOwnerRole(text) },
                        dependencies = todo.optJSONArray("dependencies")
                            .toStringList(),
                        acceptanceCriteria =
                            todo.optJSONArray("acceptance_criteria")
                                .toStringList()
                                .ifEmpty {
                                    listOf("Complete and verify: ${text.take(240)}")
                                },
                        priority = todo.optString("priority")
                            .trim()
                            .uppercase(Locale.ROOT)
                            .takeIf { it in setOf("LOW", "NORMAL", "HIGH") }
                            ?: inferPriority(text)
                    )
                }
            }
            if (todos.isEmpty()) return@runCatching null
            structuredPlan(
                id = root.optString("plan_version").trim().ifBlank {
                    "plan-${planHash.take(12)}"
                },
                summary = root.optString("summary")
                    .trim()
                    .ifBlank { summary },
                markdown = markdown,
                planHash = planHash,
                todos = todos
            )
        }.getOrNull()
    }

    private fun structuredPlan(
        id: String,
        summary: String,
        markdown: String,
        planHash: String,
        todos: List<StructuredPlanTodo>
    ): StructuredApprovedPlan {
        val phases = todos.groupBy { it.phaseId }
        val structured = JSONObject().apply {
            put("version", AGENT_CONTROL_PLANE_VERSION)
            put("plan_version", id)
            put("summary", summary)
            put("plan_hash", planHash)
            put(
                "phases",
                JSONArray().apply {
                    phases.forEach { (phaseId, phaseTodos) ->
                        put(
                            JSONObject().apply {
                                put("id", phaseId)
                                put(
                                    "title",
                                    phaseTodos.firstOrNull()?.phaseTitle
                                        ?: phaseId
                                )
                                put(
                                    "todos",
                                    JSONArray().apply {
                                        phaseTodos.forEach { todo ->
                                            put(
                                                JSONObject().apply {
                                                    put("id", todo.id)
                                                    put("text", todo.text)
                                                    put(
                                                        "owner_role",
                                                        todo.ownerRole
                                                    )
                                                    put(
                                                        "dependencies",
                                                        JSONArray(
                                                            todo.dependencies
                                                        )
                                                    )
                                                    put(
                                                        "acceptance_criteria",
                                                        JSONArray(
                                                            todo.acceptanceCriteria
                                                        )
                                                    )
                                                    put(
                                                        "priority",
                                                        todo.priority
                                                    )
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }.toString()

        return StructuredApprovedPlan(
            id = id.take(96),
            summary = summary.trim().take(500),
            markdown = markdown,
            planHash = planHash,
            todos = todos,
            structuredJson = structured
        )
    }

    suspend fun materializeApprovedPlan(
        context: Context,
        conversationId: Long,
        pendingPlanId: String?,
        summary: String,
        approvedPlan: String,
        executionProfile: String = AgentExecutionProfile.DIRECT
    ): AgentPlanMaterializationResult = withContext(Dispatchers.IO) {
        val parsed = parseApprovedPlan(summary, approvedPlan)
        // Keep the parser's complete result for the durable plan version. Direct
        // only collapses the runtime TODO projection; an explicitly legacy
        // caller keeps the historical one-row-per-action materialization.
        val runtimePlan = if (
            executionProfile.trim().equals(AgentExecutionProfile.DIRECT, ignoreCase = true)
        ) {
            AgentDirectPlanProjection.project(parsed).asProjectedPlan()
        } else {
            parsed
        }
        val scopedTodoIds = runtimePlan.todos.mapIndexed { index, todo ->
            todo.id to (
                "todo-$conversationId-${parsed.planHash.take(8)}-" +
                    (index + 1).toString().padStart(3, '0')
                )
        }.toMap()
        val scopedTodos = runtimePlan.todos.map { todo ->
            todo.copy(
                id = scopedTodoIds.getValue(todo.id),
                dependencies = todo.dependencies.mapNotNull(scopedTodoIds::get)
            )
        }
        val scopedPlanId = "plan-$conversationId-${parsed.planHash.take(12)}"
        val db = AppDatabase.getDatabase(context.applicationContext)
        val result = db.withTransaction {
            val dao = db.agentWorkflowDao()
            dao.insertProjectStateIfMissing(
                AgentProjectStateEntity(
                    conversationId = conversationId,
                    mode = "BUILD",
                    currentGoal = summary.trim()
                )
            )

            val existing = dao.getPlanVersionByHash(
                conversationId,
                parsed.planHash
            )
            val created = existing == null
            val planVersion = existing ?: AgentPlanVersionEntity(
                id = scopedPlanId,
                conversationId = conversationId,
                sourcePendingPlanId = pendingPlanId,
                versionNumber = dao.getNextPlanVersionNumber(conversationId),
                summary = parsed.summary,
                // planMarkdown and structuredJson are the complete approved
                // plan. They must not become a lossy projection of runtime
                // TODOs, because decisions/history can be restored from them.
                planMarkdown = parsed.markdown,
                structuredJson = parsed.structuredJson,
                planHash = parsed.planHash,
                status = "APPROVED",
                approvedAt = System.currentTimeMillis()
            ).also { dao.upsertPlanVersion(it) }

            var todos = dao.getTodosForPlanVersion(
                conversationId,
                planVersion.id
            )
            if (todos.isEmpty()) {
                val knownIds = runtimePlan.todos.map { it.id }.toSet()
                todos = scopedTodos.mapIndexed { index, todo ->
                    val dependencies = todo.dependencies
                        .filter { it in knownIds }
                    AgentTodoEntity(
                        id = todo.id,
                        conversationId = conversationId,
                        text = todo.text,
                        status = if (dependencies.isEmpty()) {
                            AgentTodoStatus.READY
                        } else {
                            AgentTodoStatus.PENDING
                        },
                        priority = todo.priority,
                        position = index,
                        source = "APPROVED_PLAN",
                        planVersionId = planVersion.id,
                        planStepId = todo.id,
                        phaseId = todo.phaseId,
                        ownerRole = todo.ownerRole,
                        dependenciesJson = JSONArray(dependencies).toString(),
                        acceptanceCriteriaJson =
                            JSONArray(todo.acceptanceCriteria).toString()
                    )
                }
                dao.upsertTodos(todos)
            }

            val currentTodo = chooseNextTodo(todos)

            dao.activateApprovedPlanState(
                conversationId = conversationId,
                planVersionId = planVersion.id,
                currentPhaseId = currentTodo?.phaseId,
                currentTodoId = currentTodo?.id,
                currentGoal = parsed.summary
            )
            val state = dao.getProjectState(conversationId)
                ?: error("Project state missing after plan materialization")
            Triple(planVersion, todos, created) to state
        }
        cacheState(result.second)
        AgentPlanMaterializationResult(
            planVersion = result.first.first,
            todos = result.first.second,
            created = result.first.third,
            stateRevision = result.second.revision
        )
    }

    suspend fun reconcileTodos(
        context: Context,
        conversationId: Long,
        incoming: List<AgentTodoEntity>,
        reason: String
    ): List<AgentTodoEntity> = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val result = db.withTransaction {
            val dao = db.agentWorkflowDao()
            dao.insertProjectStateIfMissing(
                AgentProjectStateEntity(conversationId = conversationId)
            )
            val existing = dao.getTodos(conversationId)
            val existingById = existing.associateBy { it.id }
            val activePlanVersion = dao.getProjectState(conversationId)
                ?.activePlanVersionId
            val merged = incoming.mapIndexed { index, candidate ->
                val previous = existingById[candidate.id]
                if (previous == null) {
                    candidate.copy(
                        status = normalizeLegacyTodoStatus(candidate.status),
                        position = candidate.position.takeIf { it >= 0 } ?: index,
                        planVersionId =
                            candidate.planVersionId ?: activePlanVersion,
                        source = candidate.source.ifBlank {
                            "SAFE_RECONCILE"
                        }
                    )
                } else {
                    val requestedStatus =
                        normalizeLegacyTodoStatus(candidate.status)
                    previous.copy(
                        text = candidate.text.trim()
                            .takeIf { it.isNotBlank() }
                            ?: previous.text,
                        priority = candidate.priority.takeIf {
                            it in setOf("LOW", "NORMAL", "HIGH")
                        } ?: previous.priority,
                        position = candidate.position.takeIf { it >= 0 }
                            ?: previous.position,
                        status = nonRegressiveTodoStatus(
                            previous.status,
                            requestedStatus
                        ),
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
            if (merged.isNotEmpty()) dao.upsertTodos(merged)
            unlockDependencyReadyTodos(dao, conversationId)
            dao.bumpProjectStateRevision(
                conversationId = conversationId,
                semanticEvent = "todo_reconcile:${reason.take(60)}"
            )
            val all = dao.getTodos(conversationId)
            val state = dao.getProjectState(conversationId)
            if (state != null && state.currentTodoId == null) {
                val next = chooseNextTodo(all)
                dao.setProjectCurrentTodo(
                    conversationId,
                    next?.phaseId,
                    next?.id
                )
            }
            all to (dao.getProjectState(conversationId)
                ?: error("Project state missing after TODO reconcile"))
        }
        cacheState(result.second)
        result.first
    }

    suspend fun transitionTodo(
        context: Context,
        conversationId: Long,
        todoId: String,
        expectedStatus: String?,
        requestedStatus: String,
        resultSummary: String? = null,
        blockReason: String? = null,
        evidenceJson: String? = null
    ): AgentTodoEntity = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val result = db.withTransaction {
            val dao = db.agentWorkflowDao()
            val current = dao.getTodoById(todoId)
                ?: error("Unknown TODO: $todoId")
            require(current.conversationId == conversationId) {
                "TODO belongs to another project"
            }
            val expected = expectedStatus
                ?.uppercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
                ?: current.status
            require(current.status == expected) {
                "TODO $todoId changed from the expected state: " +
                    "expected=$expected actual=${current.status}"
            }
            val target = requestedStatus.uppercase(Locale.ROOT)
            require(target in validTodoTransitions[current.status].orEmpty()) {
                "Invalid TODO transition ${current.status} -> $target"
            }
            val nextOwner = ownerRoleForTodoStatus(target, current.ownerRole)
            val updated = dao.transitionTodoExactlyOnce(
                id = todoId,
                expectedStatus = current.status,
                newStatus = target,
                ownerRole = nextOwner,
                assignedInvocationId = null,
                resultSummary = resultSummary?.trim()?.take(4_000),
                blockReason = blockReason?.trim()?.take(1_000),
                evidenceJson = evidenceJson ?: current.evidenceJson,
                completedAt = if (target == AgentTodoStatus.COMPLETED) {
                    System.currentTimeMillis()
                } else {
                    null
                }
            )
            require(updated == 1) {
                "TODO changed concurrently; reload project_state and retry"
            }
            unlockDependencyReadyTodos(dao, conversationId)
            dao.bumpProjectStateRevision(
                conversationId = conversationId,
                semanticEvent = "todo_transition:$todoId:$target"
            )
            val todos = dao.getTodos(conversationId)
            val next = chooseNextTodo(todos)
            dao.setProjectCurrentTodo(
                conversationId,
                next?.phaseId,
                next?.id
            )
            val state = dao.getProjectState(conversationId)
                ?: error("Project state missing after TODO transition")
            (dao.getTodoById(todoId)
                ?: error("TODO disappeared after transition")) to state
        }
        cacheState(result.second)
        result.first
    }

    suspend fun allocateInvocationForTodo(
        context: Context,
        prototype: AgentInvocationEntity,
        todoId: String,
        role: String
    ): AgentInvocationEntity = withContext(Dispatchers.IO) {
        val roleName = role.uppercase(Locale.ROOT)
        val db = AppDatabase.getDatabase(context.applicationContext)
        val result = db.withTransaction {
            val dao = db.agentWorkflowDao()
            val todo = dao.getTodoById(todoId)
                ?: error("Unknown TODO: $todoId")
            require(todo.conversationId == prototype.conversationId) {
                "TODO belongs to another project"
            }
            val allowedStatuses = allowedClaimStatuses(roleName)
            require(todo.status in allowedStatuses) {
                "$roleName cannot claim TODO $todoId while it is ${todo.status}. " +
                    "Expected one of ${allowedStatuses.joinToString()}."
            }
            require(
                todo.ownerRole.isNullOrBlank() ||
                    todo.ownerRole.equals(roleName, ignoreCase = true) ||
                    todo.status == AgentTodoStatus.NEEDS_FIX
            ) {
                "TODO $todoId is owned by ${todo.ownerRole}, not $roleName"
            }
            val all = dao.getTodos(todo.conversationId)
            val byId = all.associateBy { it.id }
            val unmetDependencies = todo.dependencies()
                .filter { dependency ->
                    byId[dependency]?.status != AgentTodoStatus.COMPLETED
                }
            require(unmetDependencies.isEmpty()) {
                "TODO $todoId has incomplete dependencies: " +
                    unmetDependencies.joinToString()
            }

            val claimed = dao.claimTodoExactlyOnce(
                id = todoId,
                expectedStatus = todo.status,
                invocationId = prototype.id,
                ownerRole = roleName
            )
            require(claimed == 1) {
                "TODO $todoId was claimed concurrently"
            }
            val allocated = dao.allocateInvocation(
                prototype.copy(todoId = todoId)
            )
            dao.bumpProjectStateRevision(
                conversationId = todo.conversationId,
                semanticEvent = "todo_claim:$todoId:$roleName"
            )
            dao.setProjectCurrentTodo(
                todo.conversationId,
                todo.phaseId,
                todo.id
            )
            allocated to (dao.getProjectState(todo.conversationId)
                ?: error("Project state missing after TODO claim"))
        }
        cacheState(result.second)
        result.first
    }

    internal fun cancelledTodoRecoveryForRole(
        role: String
    ): Pair<String, String?> = when (role.uppercase(Locale.ROOT)) {
        "REVIEWER" ->
            AgentTodoStatus.READY_FOR_REVIEW to "REVIEWER"
        "EXECUTOR" ->
            AgentTodoStatus.READY_FOR_VERIFICATION to "EXECUTOR"
        "VISUAL_TESTER" ->
            AgentTodoStatus.READY_FOR_VERIFICATION to "VISUAL_TESTER"
        "SUMMARIZER" ->
            AgentTodoStatus.NEEDS_FIX to "SUMMARIZER"
        else ->
            AgentTodoStatus.NEEDS_FIX to "CODER"
    }

    suspend fun cancelInvocationAndReleaseTodo(
        context: Context,
        invocationId: String,
        reason: String
    ): AgentProjectStateEntity? = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val updatedState = db.withTransaction {
            val dao = db.agentWorkflowDao()
            val invocation = dao.getInvocation(invocationId)
                ?: return@withTransaction null
            val boundedReason = reason.trim().ifBlank {
                "Invocation cancelled."
            }.take(1_000)
            dao.finishInvocationExactlyOnce(
                id = invocation.id,
                status = "CANCELLED",
                resultSummary = "Cancelled: $boundedReason",
                errorClass = "CancellationException",
                errorMessage = boundedReason
            )
            invocation.todoId?.let { todoId ->
                val todo = dao.getTodoById(todoId)
                if (
                    todo != null &&
                    todo.status == AgentTodoStatus.IN_PROGRESS &&
                    todo.assignedInvocationId == invocation.id
                ) {
                    val recovery = cancelledTodoRecoveryForRole(
                        invocation.agentClass
                    )
                    dao.completeTodoInvocationExactlyOnce(
                        id = todo.id,
                        invocationId = invocation.id,
                        expectedStatus = AgentTodoStatus.IN_PROGRESS,
                        newStatus = recovery.first,
                        ownerRole = recovery.second,
                        resultSummary = "Invocation cancelled: $boundedReason",
                        blockReason = null,
                        evidenceJson = todo.evidenceJson,
                        completedAt = null
                    )
                }
            }
            dao.cancelInvocationPendingInputs(invocation.id)
            dao.bumpProjectStateRevision(
                conversationId = invocation.conversationId,
                semanticEvent =
                    "invocation_cancelled:${invocation.agentClass}"
            )
            unlockDependencyReadyTodos(dao, invocation.conversationId)
            val todos = dao.getTodos(invocation.conversationId)
            val next = chooseNextTodo(todos)
            dao.setProjectCurrentTodo(
                invocation.conversationId,
                next?.phaseId,
                next?.id
            )
            dao.getProjectState(invocation.conversationId)
        }
        cacheState(updatedState)
        updatedState
    }

    suspend fun recordWorkReportAndTransition(
        context: Context,
        invocationId: String,
        rawSummary: String,
        result: AgentResult,
        evidence: AgentEvidenceBundle
    ): AgentWorkReportTransition = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val transition = db.withTransaction {
            val dao = db.agentWorkflowDao()
            val invocation = dao.getInvocation(invocationId)
                ?: error("Invocation no longer exists: $invocationId")
            invocation.workReportId?.let { existingReportId ->
                val existingReport = dao.getWorkReport(existingReportId)
                if (existingReport != null) {
                    val todo = invocation.todoId?.let { dao.getTodoById(it) }
                    val state = dao.getProjectState(invocation.conversationId)
                        ?: error("Project state missing while replaying work report")
                    return@withTransaction AgentWorkReportTransition(
                        report = existingReport,
                        previousTodoStatus = todo?.status,
                        nextTodoStatus = todo?.status,
                        nextOwnerRole = todo?.ownerRole,
                        stateRevision = state.revision
                    ) to state
                }
            }
            val reportId = "report-${UUID.randomUUID()}"
            val outcome = AgentDurableContractStore.normalizeAgentReportOutcome(
                result,
                rawSummary
            )
            val reportStatus = outcome.status
            val structuredJson = normalizeStructuredReportJson(
                rawSummary,
                reportStatus
            )
            val changedFiles = changedFilesForResult(result, evidence)
            val risks = risksForResult(result)
            val recommendations = recommendationsForResult(result)
            val report = AgentWorkReportEntity(
                id = reportId,
                conversationId = invocation.conversationId,
                invocationId = invocation.id,
                todoId = invocation.todoId,
                agentRole = invocation.agentClass,
                status = reportStatus,
                summary = result
                    .toParentFacingSummary(
                        invocation.agentClass,
                        evidence
                    )
                    .take(4_000),
                structuredJson = structuredJson,
                evidenceJson = evidence.toJson(),
                changedFilesJson = JSONArray(changedFiles).toString(),
                risksJson = JSONArray(risks).toString(),
                recommendationsJson =
                    JSONArray(recommendations).toString()
            )
            dao.upsertWorkReport(report)
            dao.attachInvocationWorkReport(
                invocation.id,
                report.id
            )
            dao.finishInvocationExactlyOnce(
                id = invocation.id,
                status = reportStatus,
                resultSummary = report.summary,
                errorClass = outcome.errorClass,
                errorMessage = outcome.errorMessage
            )

            var previousTodoStatus: String? = null
            var nextTodoStatus: String? = null
            var nextOwnerRole: String? = null
            invocation.todoId?.let { todoId ->
                val todo = dao.getTodoById(todoId)
                    ?: error("Invocation TODO disappeared: $todoId")
                previousTodoStatus = todo.status
                val todoOutcome = todoOutcomeForReport(
                    invocation.agentClass,
                    result,
                    reportStatus
                )
                nextTodoStatus = todoOutcome.first
                nextOwnerRole = todoOutcome.second
                val transitioned = dao.completeTodoInvocationExactlyOnce(
                    id = todo.id,
                    invocationId = invocation.id,
                    expectedStatus = AgentTodoStatus.IN_PROGRESS,
                    newStatus = nextTodoStatus!!,
                    ownerRole = nextOwnerRole,
                    resultSummary = report.summary,
                    blockReason = if (
                        nextTodoStatus == AgentTodoStatus.BLOCKED
                    ) {
                        risks.firstOrNull()
                            ?: recommendations.firstOrNull()
                            ?: "Specialist reported a blocker."
                    } else {
                        null
                    },
                    evidenceJson = report.evidenceJson,
                    completedAt = if (
                        nextTodoStatus == AgentTodoStatus.COMPLETED
                    ) {
                        System.currentTimeMillis()
                    } else {
                        null
                    }
                )
                require(transitioned == 1) {
                    "TODO ${todo.id} is no longer owned by invocation " +
                        invocation.id
                }
                unlockDependencyReadyTodos(
                    dao,
                    invocation.conversationId
                )
            }

            dao.bumpProjectStateRevision(
                conversationId = invocation.conversationId,
                semanticEvent = "work_report:${invocation.agentClass}:${report.status}"
            )
            val todos = dao.getTodos(invocation.conversationId)
            val next = chooseNextTodo(todos)
            dao.setProjectCurrentTodo(
                invocation.conversationId,
                next?.phaseId,
                next?.id
            )
            val state = dao.getProjectState(invocation.conversationId)
                ?: error("Project state missing after work report")
            AgentWorkReportTransition(
                report = report,
                previousTodoStatus = previousTodoStatus,
                nextTodoStatus = nextTodoStatus,
                nextOwnerRole = nextOwnerRole,
                stateRevision = state.revision
            ) to state
        }
        cacheState(transition.second)
        transition.first
    }

    suspend fun buildControlPacket(
        context: Context,
        conversationId: Long,
        initialOrder: String? = null,
        maxChars: Int = 12_000
    ): String = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(context.applicationContext)
        val dao = database.agentWorkflowDao()
        val conversation = database.agentChatDao().getConversation(conversationId)
        val executionProfile = AgentExecutionProfile.normalize(
            conversation?.executionProfile
        )
        val state = ensureState(
            context,
            conversationId,
            goal = initialOrder?.trim()
        )
        val contract = dao.getProjectContract(conversationId)
            ?: AgentDurableContractStore.ensureContract(
                database = AppDatabase.getDatabase(context.applicationContext),
                conversationId = conversationId,
                initialGoal = initialOrder?.trim()
            )
        val planningEpisodeId = state.activePlanVersionId
            ?.takeIf { it.isNotBlank() }
            ?: "initial-plan"
        val researchBudget = AgentDurableContractStore.readResearchBudget(
            database = database,
            conversationId = conversationId,
            planningEpisodeId = planningEpisodeId
        )
        val todos = dao.getTodos(conversationId)
        val invocations = dao.getInvocations(conversationId)
        val reports = dao.getRecentWorkReports(conversationId, 6)
        val decisions = dao.getAllLatestDecisions(conversationId)
        val questions = dao.getPendingQuestions(conversationId)
        val pendingPlan = dao.getPendingPlan(conversationId)
        val activePlan = state.activePlanVersionId?.let {
            dao.getPlanVersionById(it)
        } ?: dao.getLatestApprovedPlan(conversationId)
        val currentTodo = state.currentTodoId?.let {
            dao.getTodoById(it)
        } ?: chooseNextTodo(todos)
        val counts = todos.groupingBy { it.status }.eachCount()
        val activeInvocations = invocations.filter {
            it.status.equals("RUNNING", ignoreCase = true)
        }
        val blockers = todos.filter {
            it.status == AgentTodoStatus.BLOCKED
        }

        val controlHeader = buildString {
            appendLine("# Project Control Packet")
            appendLine()
            appendLine("- control_version: $AGENT_CONTROL_PLANE_VERSION")
            appendLine("- state_revision: ${state.revision}")
            appendLine("- mode: ${state.mode}")
            appendLine("- execution_profile: $executionProfile")
            appendLine("- goal: durable_contract.initial_goal")
        }.trim()

        // These sections are durable state. They must be emitted as complete
        // values so a bounded packet can never silently change the contract.
        val contractSection = buildString {
            appendLine("## Durable User Contract")
            appendLine("- contract_version: ${contract.contractVersion}")
            appendLine("- initial_goal_source: ${contract.initialGoalSource}")
            appendLine(
                "- initial_goal: ${contract.initialGoal.ifBlank { "No initial goal recorded." }}"
            )
            appendLine("- no_more_questions: ${contract.noMoreQuestions}")
            appendLine("- greenfield: ${contract.greenfield}")
            appendLine("- authority: durable project contract; packet rendering cannot replace initial_goal")
        }.trim()

        val researchBudgetSection = buildString {
            appendLine("## Durable Research Budget")
            appendLine("- planning_episode_id: ${researchBudget.planningEpisodeId}")
            appendLine(
                "- web_search: ${researchBudget.searchUsed}/${researchBudget.searchLimit} " +
                    "used; ${researchBudget.searchRemaining} remaining"
            )
            appendLine(
                "- fetch_url: ${researchBudget.fetchUsed}/${researchBudget.fetchLimit} " +
                    "used; ${researchBudget.fetchRemaining} remaining"
            )
            appendLine("- authority: durable research admission receipts")
            appendLine("- over_limit_policy: a specific blocker reason is required")
        }.trim()

        // Exact answer values belong in every packet; verbose provenance and
        // supersession payloads remain in Room and do not consume model context.
        val corrections = AgentDurableContractStore.projectUserCorrections(decisions)
        val decisionsSection = decisions.filterNot(
            AgentDurableContractStore::isUserCorrectionDecision
        ).takeIf { it.isNotEmpty() }?.let { values ->
            buildString {
                appendLine("## Durable Decisions")
                values.forEach { decision ->
                    appendLine("- decision_id: ${decision.id}")
                    appendLine("  decision_key: ${decision.decisionKey}")
                    appendLine("  answer: ${decision.answerJson}")
                    decision.questionId?.let { appendLine("  question_id: $it") }
                    decision.supersedesDecisionId?.let { appendLine("  supersedes: $it") }
                }
            }.trim()
        }
        val correctionsSection = corrections.values.takeIf { it.isNotEmpty() }?.let { values ->
            buildString {
                appendLine("## Durable User Corrections")
                appendLine("- latest_decision_id: ${corrections.latestDecisionId}")
                values.forEach { correction ->
                    appendLine("- decision_id: ${correction.decisionId}")
                    appendLine("  content: ${correction.content}")
                }
            }.trim()
        }

        val sections = mutableListOf<String>().apply {
            add(controlHeader)
            add(contractSection)
            if (shouldIncludeResearchBudgetInControlPacket(executionProfile, state.mode)) {
                add(researchBudgetSection)
            }
            decisionsSection?.let(::add)
            correctionsSection?.let(::add)
        }

        activePlan?.let { plan ->
            sections += renderApprovedPlanControlPacketSection(plan)
        }
        val committedArtifactLedger = if (
            executionProfile == AgentExecutionProfile.DIRECT &&
            state.mode != PROJECT_MODE_PLAN
        ) {
            AgentDurableContractStore.readCommittedArtifactLedger(
                database,
                conversationId
            )
        } else {
            null
        }
        committedArtifactLedger?.let { sections += it.render() }

        val directBuildStepMissingArtifacts = if (
            executionProfile == AgentExecutionProfile.DIRECT &&
            state.mode.equals(PROJECT_MODE_BUILD, ignoreCase = true) &&
            currentTodo != null
        ) {
            committedArtifactLedger?.let {
                AgentDirectPlanProjection.missingDeclaredBuildArtifacts(
                    todo = currentTodo,
                    ledger = it
                )
            }.orEmpty()
        } else {
            emptyList()
        }
        val directBuildStepPartialArtifacts = committedArtifactLedger
            ?.entries
            ?.filter { it.operation.equals("partial", ignoreCase = true) }
            ?.map { it.path }
            ?.toSet()
            .orEmpty()
        val directBuildStepArtifactsComplete =
            currentTodo?.status == AgentTodoStatus.IN_PROGRESS &&
                directBuildStepMissingArtifacts.isEmpty() &&
                currentTodo?.let {
                    committedArtifactLedger?.let { ledger ->
                        AgentDirectPlanProjection.buildStepArtifactsAreReadyForFinish(it, ledger)
                    }
                } == true

        sections += buildString {
            appendLine("## Progress")
            appendLine("- total: ${todos.size}")
            listOf(
                AgentTodoStatus.PENDING,
                AgentTodoStatus.READY,
                AgentTodoStatus.IN_PROGRESS,
                AgentTodoStatus.READY_FOR_REVIEW,
                AgentTodoStatus.NEEDS_FIX,
                AgentTodoStatus.READY_FOR_VERIFICATION,
                AgentTodoStatus.VERIFIED,
                AgentTodoStatus.BLOCKED,
                AgentTodoStatus.COMPLETED,
                AgentTodoStatus.CANCELLED
            ).forEach { status ->
                val count = counts[status] ?: 0
                if (count > 0) {
                    appendLine("- ${status.lowercase()}: $count")
                }
            }
        }.trim()

        currentTodo?.let { todo ->
            sections += buildString {
                val optimizedDirectWorkflow =
                    executionProfile == AgentExecutionProfile.DIRECT
                appendLine("## Current TODO")
                appendLine("- id: ${todo.id}")
                if (!optimizedDirectWorkflow) {
                    appendLine("- phase: ${todo.phaseId ?: "legacy"}")
                }
                appendLine("- status: ${todo.status}")
                if (optimizedDirectWorkflow) {
                    appendLine("- status_scope: planning/verification status, not file existence; consult committed artifacts before choosing the next write")
                }
                if (!optimizedDirectWorkflow) {
                    appendLine("- expected_owner: ${todo.ownerRole ?: "unassigned"}")
                }
                appendLine("- task: ${todo.text.take(800)}")
                val criteriaProjection = projectTodoAcceptanceCriteriaForPrompt(
                    todoText = todo.text,
                    criteria = todo.acceptanceCriteria(),
                    optimized = optimizedDirectWorkflow
                )
                if (criteriaProjection.criteria.isNotEmpty()) {
                    appendLine("- acceptance_criteria:")
                    criteriaProjection.criteria.forEach {
                        val criterion = if (optimizedDirectWorkflow) {
                            it
                        } else {
                            it.take(300)
                        }
                        appendLine("  - $criterion")
                    }
                }
                if (criteriaProjection.verificationRequired) {
                    appendLine("- verification_required: true")
                }
                val dependencies = todo.dependencies()
                if (dependencies.isNotEmpty()) {
                    appendLine(
                        "- dependencies: ${dependencies.joinToString()}"
                    )
                }
                todo.blockReason?.let {
                    appendLine("- blocker: ${it.take(600)}")
                }
            }.trim()
        }

        if (activeInvocations.isNotEmpty()) {
            sections += buildString {
                appendLine("## Active Invocations")
                activeInvocations.take(6).forEach { invocation ->
                    appendLine(
                        "- ${invocation.id}: ${invocation.agentClass} / " +
                            "${invocation.resolvedName}; " +
                            "todo=${invocation.todoId ?: "none"}; " +
                            "task=${invocation.task.take(260)}"
                    )
                }
            }.trim()
        }

        if (blockers.isNotEmpty() || questions.isNotEmpty() || pendingPlan != null) {
            sections += buildString {
                appendLine("## Blocking State")
                blockers.take(6).forEach { todo ->
                    appendLine(
                        "- blocked TODO ${todo.id}: " +
                            (todo.blockReason ?: todo.text).take(400)
                    )
                }
                questions.take(5).forEach { question ->
                    appendLine(
                        "- pending user question: ${question.id}"
                    )
                }
                pendingPlan?.let {
                    appendLine(
                        "- pending plan approval: ${it.id} (${it.state})"
                    )
                }
            }.trim()
        }
        // Everything through the current control/blocking state is required.
        // Reports remain optional evidence and may be evicted as whole sections.
        val protectedSectionCount = sections.size

        if (reports.isNotEmpty()) {
            sections += buildString {
                appendLine("## Recent Specialist Reports")
                reports.take(5).forEach { report ->
                    appendLine(
                        "- ${report.id}: ${report.agentRole} " +
                            "${report.status}; " +
                            "todo=${report.todoId ?: "none"}; " +
                            projectAgentReportForControlPacket(
                                summary = report.summary,
                                evidenceJson = report.evidenceJson
                            )
                    )
                }
            }.trim()
        }

        val actionsSection = buildString {
            appendLine("## Permitted Next Actions")
            val codebaseDiscoverySuppressed =
                AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                    greenfield = contract.greenfield,
                    initialGoal = contract.initialGoal,
                    corrections = corrections.values.map { it.content }
                )
            permittedNextActions(
                state = state,
                currentTodo = currentTodo,
                activeInvocations = activeInvocations,
                pendingQuestionCount = questions.size,
                pendingPlanPresent = pendingPlan != null,
                executionProfile = executionProfile,
                researchBudget = researchBudget,
                codebaseDiscoverySuppressed = codebaseDiscoverySuppressed,
                hasCommittedArtifact =
                    committedArtifactLedger?.let {
                        it.entries.isNotEmpty() || it.incomplete
                    } == true,
                currentBuildStepArtifactsComplete = directBuildStepArtifactsComplete,
                currentBuildStepMissingArtifacts = directBuildStepMissingArtifacts,
                currentBuildStepPartialArtifacts = directBuildStepPartialArtifacts
            ).forEach { appendLine("- $it") }
        }.trim()

        boundedSectionsWithReservedTail(
            prefixSections = sections,
            reservedTail = actionsSection,
            maxChars = maxChars,
            protectedSectionCount = protectedSectionCount
        )
    }

    /**
     * Authoritative, request-tail capsule for the single Direct Agent. The
     * underlying packet renderer already refuses to truncate protected contract,
     * correction, plan, TODO, and blocking sections. This wrapper makes the
     * runtime version and one exact next action explicit while retaining the
     * established packet prefix used by prompt ordering and cache diagnostics.
     */
    suspend fun buildDirectControlCapsule(
        context: Context,
        conversationId: Long,
        initialOrder: String? = null,
        activeHandle: String? = null,
        latestFailure: String? = null,
        maxChars: Int = 24_000
    ): String = withContext(Dispatchers.IO) {
        val normalizedHandle = activeHandle?.trim()?.takeIf { it.isNotBlank() } ?: "none"
        val normalizedFailure = latestFailure?.trim()?.takeIf { it.isNotBlank() } ?: "none"
        val wrapperReserve = 640
        val packet = buildControlPacket(
            context = context,
            conversationId = conversationId,
            initialOrder = initialOrder,
            maxChars = (maxChars - wrapperReserve).coerceAtLeast(2_000)
        )
        if (isControlPacketCapacityFailure(packet)) return@withContext packet
        val permittedNextAction = packet
            .substringAfter("## Permitted Next Actions", missingDelimiterValue = "")
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("- ") }
            ?.removePrefix("- ")
            ?.trim()
        val exactNextAction = directControlCapsuleNextAction(
            permittedNextAction = permittedNextAction,
            latestFailure = latestFailure
        )
        val capsule = buildString {
            appendLine("# Project Control Packet — Direct Control Capsule")
            appendLine()
            appendLine("- direct_runtime_version: $DIRECT_RUNTIME_VERSION")
            appendLine("- exact_next_action: $exactNextAction")
            appendLine("- active_command_or_run: $normalizedHandle")
            appendLine("- latest_failure: ${normalizedFailure.take(360)}")
            appendLine("- authority: this tail capsule overrides conflicting historical prose")
            appendLine()
            append(packet.substringAfter('\n', missingDelimiterValue = packet).trimStart())
        }.trim()
        if (capsule.length <= maxChars) {
            capsule
        } else {
            buildString {
                appendLine("# Project Control Packet — Direct Control Capsule")
                appendLine("- control_state_does_not_fit: true")
                appendLine("- requested_max_chars: $maxChars")
                appendLine("- required_chars: ${capsule.length}")
                appendLine("- action: pause with Needs direction before model dispatch")
                append("- reason: the authoritative Direct capsule is protected from truncation")
            }
        }
    }

    suspend fun renderCompactionSummary(
        context: Context,
        conversationId: Long,
        summarizedMessageCount: Int,
        retainedRecentMessageCount: Int,
        retainedRecentTokenEstimate: Int,
        retainedRecentTargetTokens: Int,
        maxChars: Int
    ): String {
        val packet = buildControlPacket(
            context = context,
            conversationId = conversationId,
            maxChars = (maxChars - 500).coerceAtLeast(1_500)
        )
        return buildString {
            appendLine("# Context Compaction State Projection")
            appendLine()
            appendLine(
                "- summarized_older_messages: $summarizedMessageCount"
            )
            appendLine(
                "- retained_recent_messages: $retainedRecentMessageCount"
            )
            appendLine(
                "- retained_recent_tokens: " +
                    "$retainedRecentTokenEstimate / " +
                    "$retainedRecentTargetTokens target"
            )
            appendLine(
                "- authority: Room project state, TODOs, invocations, " +
                    "work reports, pending questions, and approved plans"
            )
            appendLine(
                "- note: this document is a rendered projection; it is not " +
                    "used as evidence for the next projection"
            )
            appendLine()
            append(packet)
        }.take(maxChars.coerceAtLeast(2_000))
    }

    suspend fun readPlan(
        context: Context,
        conversationId: Long,
        planId: String?
    ): String = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context.applicationContext)
            .agentWorkflowDao()
        val plan = planId?.takeIf { it.isNotBlank() }?.let {
            dao.getPlanVersionById(it)
        } ?: dao.getLatestApprovedPlan(conversationId)
        plan?.let {
            buildString {
                appendLine("# ${it.summary}")
                appendLine()
                appendLine("Plan ID: ${it.id}")
                appendLine("Version: ${it.versionNumber}")
                appendLine("Hash: ${it.planHash}")
                appendLine()
                append(it.planMarkdown)
            }
        } ?: "No approved plan is stored for this project."
    }

    suspend fun readWorkReport(
        context: Context,
        conversationId: Long,
        reportId: String
    ): String = withContext(Dispatchers.IO) {
        val report = AppDatabase.getDatabase(context.applicationContext)
            .agentWorkflowDao()
            .getWorkReport(reportId)
            ?.takeIf { it.conversationId == conversationId }
            ?: return@withContext "No work report found: $reportId"
        JSONObject().apply {
            put("id", report.id)
            put("invocation_id", report.invocationId)
            put("todo_id", report.todoId)
            put("agent_role", report.agentRole)
            put("status", report.status)
            put("summary", report.summary)
            put(
                "structured",
                parseJsonOrString(report.structuredJson)
            )
            put("evidence", JSONObject(report.evidenceJson))
            put("changed_files", JSONArray(report.changedFilesJson))
            put("risks", JSONArray(report.risksJson))
            put(
                "recommendations",
                JSONArray(report.recommendationsJson)
            )
            put("created_at", report.createdAt)
        }.toString(2)
    }

    fun compactDocumentReference(
        title: String,
        content: String?,
        maxChars: Int
    ): String? {
        val normalized = content
            ?.replace("\r\n", "\n")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val hash = sha256(normalized)
        val lines = normalized.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        val headings = lines.filter { it.startsWith("#") }.take(8)
        val constraints = lines.filter {
            it.startsWith("- ") ||
                it.startsWith("* ") ||
                Regex("""^\d+[.)]\s+""").containsMatchIn(it)
        }.take(12)
        val preview = buildString {
            appendLine("# $title Reference")
            appendLine("- hash: ${hash.take(16)}")
            appendLine("- full_document_is_durable: true")
            appendLine("- first_line: ${lines.firstOrNull()?.take(500).orEmpty()}")
            if (headings.isNotEmpty()) {
                appendLine("- headings:")
                headings.forEach { appendLine("  - ${it.take(240)}") }
            }
            if (constraints.isNotEmpty()) {
                appendLine("- key_items:")
                constraints.forEach { appendLine("  - ${it.take(300)}") }
            }
        }.trim()
        return if (preview.length <= maxChars) {
            preview
        } else {
            preview.take(maxChars)
        }
    }

    /**
     * Renders the approved plan as one parseable canonical field. The full
     * markdown body is deliberately kept beside its identity and full hash;
     * a bounded packet returns an explicit capacity marker before this
     * protected section can be truncated.
     */
    internal fun renderApprovedPlanControlPacketSection(
        plan: AgentPlanVersionEntity
    ): String {
        val fullPlan = JSONObject()
            .put("id", plan.id)
            .put("version", plan.versionNumber)
            .put("hash", plan.planHash)
            .put("plan_markdown", plan.planMarkdown)
        return buildString {
            appendLine("## Approved Plan")
            appendLine("- id: ${plan.id}")
            appendLine("- version: ${plan.versionNumber}")
            appendLine("- hash: ${plan.planHash.take(16)}")
            appendLine("- full_plan_json: ${fullPlan}")
        }.trim()
    }

    fun compactionDecision(
        conversationId: Long,
        percentUsed: Int,
        thresholdPercent: Int,
        emergencyThresholdPercent: Int,
        rootTurnId: String,
        toolDefinitionsHash: String
    ): AgentCompactionGateDecision {
        if (percentUsed < thresholdPercent) {
            return AgentCompactionGateDecision(
                false,
                "usage_below_threshold"
            )
        }
        val state = cachedState(conversationId)
            ?: return AgentCompactionGateDecision(
                shouldCompact = true,
                reason = "state_not_loaded"
            )
        val key = listOf(
            conversationId,
            state.revision,
            rootTurnId,
            toolDefinitionsHash
        ).joinToString("|")

        if (
            state.lastCompactedRevision == state.revision &&
            state.lastCompactionStatus in setOf(
                AgentCompactionStatus.RUNNING,
                AgentCompactionStatus.APPLIED,
                AgentCompactionStatus.SATURATED
            )
        ) {
            return AgentCompactionGateDecision(
                false,
                "same_semantic_revision_already_compacted"
            )
        }

        val semanticChangesSinceCompaction =
            state.semanticEventCount -
                state.lastCompactionSemanticEventCount
        val emergency = percentUsed >= emergencyThresholdPercent
        if (!emergency && semanticChangesSinceCompaction <= 0L) {
            return AgentCompactionGateDecision(
                false,
                "no_semantic_state_change"
            )
        }
        if (
            emergency &&
            state.lastCompactionKey == key
        ) {
            return AgentCompactionGateDecision(
                false,
                "emergency_already_used_for_revision"
            )
        }
        return AgentCompactionGateDecision(
            true,
            if (emergency) "emergency" else "semantic_threshold",
            key
        )
    }

    suspend fun markCompactionStarted(
        context: Context,
        conversationId: Long,
        compactionKey: String,
        preTokens: Int
    ): AgentProjectStateEntity = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context.applicationContext)
            .agentWorkflowDao()
        dao.recordProjectCompactionStarted(
            conversationId = conversationId,
            compactionKey = compactionKey,
            preTokens = preTokens.coerceAtLeast(0)
        )
        dao.getProjectState(conversationId)
            ?.also(::cacheState)
            ?: error("Project state missing at compaction start")
    }

    suspend fun completeCompactionMeasurement(
        context: Context,
        conversationId: Long,
        postTokens: Int,
        maximumInputTokens: Int
    ): AgentCompactionMeasurement = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context.applicationContext)
            .agentWorkflowDao()
        val current = dao.getProjectState(conversationId)
            ?: ensureState(context, conversationId)
        val pre = current.lastCompactionPreTokens ?: postTokens
        val post = postTokens.coerceAtLeast(0)
        val saved = (pre - post).coerceAtLeast(0)
        val minimumSavings = max(
            1_024,
            maximumInputTokens.coerceAtLeast(1) / 10
        )
        val status = if (saved >= minimumSavings) {
            AgentCompactionStatus.APPLIED
        } else {
            AgentCompactionStatus.SATURATED
        }
        dao.recordProjectCompactionCompleted(
            conversationId = conversationId,
            status = status,
            postTokens = post,
            savedTokens = saved,
            saturationReason = if (
                status == AgentCompactionStatus.SATURATED
            ) {
                "Compaction saved $saved tokens; minimum useful savings " +
                    "is $minimumSavings. Required state/tool basis is saturated."
            } else {
                null
            }
        )
        val updated = dao.getProjectState(conversationId)
            ?: error("Project state missing after compaction measurement")
        cacheState(updated)
        AgentCompactionMeasurement(
            status = status,
            preTokens = pre,
            postTokens = post,
            savedTokens = saved,
            minimumUsefulSavings = minimumSavings,
            stateRevision = updated.revision
        )
    }

    suspend fun markCompactionFailed(
        context: Context,
        conversationId: Long,
        reason: String
    ) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context.applicationContext)
            .agentWorkflowDao()
        dao.recordProjectCompactionFailed(
            conversationId,
            reason.take(1_000)
        )
        cacheState(dao.getProjectState(conversationId))
    }

    fun renderTodoMarkdown(todos: List<AgentTodoEntity>): String =
        buildString {
            appendLine("# TODO")
            appendLine()
            if (todos.isEmpty()) {
                appendLine("- No pending tasks recorded.")
            } else {
                todos.sortedBy { it.position }.forEach { todo ->
                    val marker = if (
                        todo.status == AgentTodoStatus.COMPLETED
                    ) {
                        "x"
                    } else {
                        " "
                    }
                    appendLine(
                        "- [$marker] ${todo.id} | ${todo.status} | " +
                            "${todo.ownerRole ?: "unassigned"} | ${todo.text}"
                    )
                }
            }
        }.trimEnd()

    private suspend fun unlockDependencyReadyTodos(
        dao: com.example.llamadroid.data.db.AgentWorkflowDao,
        conversationId: Long
    ) {
        var changed: Boolean
        do {
            changed = false
            val todos = dao.getTodos(conversationId)
            val byId = todos.associateBy { it.id }
            todos.filter { it.status == AgentTodoStatus.PENDING }
                .forEach { todo ->
                    val dependencies = todo.dependencies()
                    if (
                        dependencies.all {
                            byId[it]?.status == AgentTodoStatus.COMPLETED
                        }
                    ) {
                        if (
                            dao.markTodoReadyExactlyOnce(
                                todo.id,
                                AgentTodoStatus.PENDING,
                                AgentTodoStatus.READY
                            ) == 1
                        ) {
                            changed = true
                        }
                    }
                }
        } while (changed)
    }

    private fun chooseNextTodo(
        todos: List<AgentTodoEntity>
    ): AgentTodoEntity? {
        return todos
            .filter { it.status !in AgentTodoStatus.terminal }
            .minWithOrNull(
                compareBy<AgentTodoEntity> {
                    AgentTodoStatus.actionPriority.indexOf(it.status).takeIf { index -> index >= 0 }
                        ?: Int.MAX_VALUE
                }.thenBy { it.position }
            )
    }

    private fun allowedClaimStatuses(role: String): Set<String> =
        when (role) {
            "CODER" -> setOf(
                AgentTodoStatus.READY,
                AgentTodoStatus.NEEDS_FIX
            )
            "REVIEWER" -> setOf(
                AgentTodoStatus.READY_FOR_REVIEW
            )
            "EXECUTOR", "VISUAL_TESTER" -> setOf(
                AgentTodoStatus.READY_FOR_VERIFICATION
            )
            "SUMMARIZER" -> setOf(
                AgentTodoStatus.READY,
                AgentTodoStatus.NEEDS_FIX
            )
            else -> setOf(
                AgentTodoStatus.READY,
                AgentTodoStatus.PENDING,
                AgentTodoStatus.NEEDS_FIX
            )
        }

    private fun todoOutcomeForReport(
        role: String,
        result: AgentResult,
        reportStatus: String
    ): Pair<String, String?> {
        val normalizedStatus = reportStatus.uppercase(Locale.ROOT)
        when (normalizedStatus) {
            "BLOCKED" ->
                return AgentTodoStatus.BLOCKED to role.uppercase(Locale.ROOT)
            "CANCELLED" ->
                return cancelledTodoRecoveryForRole(role)
            "INTERRUPTED" ->
                return AgentTodoStatus.BLOCKED to role.uppercase(Locale.ROOT)
        }
        val success = normalizedStatus == "SUCCESS"
        return when (role.uppercase(Locale.ROOT)) {
            "CODER" -> if (success) {
                AgentTodoStatus.READY_FOR_REVIEW to "REVIEWER"
            } else {
                AgentTodoStatus.NEEDS_FIX to "CODER"
            }

            "REVIEWER" -> {
                val hasFindings = (result as? AgentResult.ReviewerResult)
                    ?.findings
                    ?.isNotEmpty() == true
                if (success && !hasFindings) {
                    AgentTodoStatus.READY_FOR_VERIFICATION to "EXECUTOR"
                } else {
                    AgentTodoStatus.NEEDS_FIX to "CODER"
                }
            }

            "EXECUTOR", "VISUAL_TESTER" -> if (success) {
                AgentTodoStatus.COMPLETED to null
            } else {
                AgentTodoStatus.NEEDS_FIX to "CODER"
            }

            "CODEBASE_SCOUT", "RESEARCHER", "PLANNER", "SUMMARIZER" ->
                if (success) {
                    AgentTodoStatus.COMPLETED to null
                } else {
                    AgentTodoStatus.BLOCKED to role.uppercase(Locale.ROOT)
                }

            else -> if (success) {
                AgentTodoStatus.COMPLETED to null
            } else {
                AgentTodoStatus.BLOCKED to role.uppercase(Locale.ROOT)
            }
        }
    }


    private fun ownerRoleForTodoStatus(
        status: String,
        previousOwner: String?
    ): String? = when (status) {
        AgentTodoStatus.READY_FOR_REVIEW -> "REVIEWER"
        AgentTodoStatus.NEEDS_FIX -> "CODER"
        AgentTodoStatus.READY_FOR_VERIFICATION -> "EXECUTOR"
        AgentTodoStatus.COMPLETED,
        AgentTodoStatus.CANCELLED -> null
        else -> previousOwner
    }

    private fun permittedNextActions(
        state: AgentProjectStateEntity,
        currentTodo: AgentTodoEntity?,
        activeInvocations: List<AgentInvocationEntity>,
        pendingQuestionCount: Int,
        pendingPlanPresent: Boolean,
        executionProfile: String = AgentExecutionProfile.DIRECT,
        researchBudget: AgentResearchBudget? = null,
        codebaseDiscoverySuppressed: Boolean = false,
        hasCommittedArtifact: Boolean = false,
        currentBuildStepArtifactsComplete: Boolean = false,
        currentBuildStepMissingArtifacts: List<String> = emptyList(),
        currentBuildStepPartialArtifacts: Set<String> = emptySet()
    ): List<String> {
        if (pendingPlanPresent) {
            return listOf("wait for the user to resolve the proposed plan")
        }
        if (pendingQuestionCount > 0) {
            return listOf("wait for the pending user answer")
        }
        if (activeInvocations.isNotEmpty()) {
            return listOf(
                "wait for the running specialist invocation",
                "read project_state again after it returns"
            )
        }
        if (state.mode.equals(AgentDirectRuntime.MODE_COMPLETE, ignoreCase = true)) {
            return listOf("wait for a new user task")
        }
        if (state.mode.equals(PROJECT_MODE_VERIFY, ignoreCase = true)) {
            return listOf(
                "run the focused checks and inspect current runtime or preview evidence",
                "use check_project_run, check_command, wait_command, or observe_preview as needed",
                "if a check fails, follow its durable receipt back to BUILD and repair the concrete defect",
                "call finish_task only after checks pass, with actual artifacts and validation evidence",
                "do not write files, edit files, apply patches, or install dependencies while VERIFY is active"
            )
        }
        if (state.mode.equals(PROJECT_MODE_PLAN, ignoreCase = true)) {
            if (executionProfile == AgentExecutionProfile.DIRECT) {
                if (codebaseDiscoverySuppressed) {
                    return listOf(
                        "return one bounded actionable Markdown plan now; the greenfield/no-scout declaration completes inspection, no external research is required, and implementation choices belong to the Direct Agent"
                    )
                }
                val researchAction = researchBudget?.let { budget ->
                    if (budget.exhausted) {
                        "use the collected evidence; request additional research only with a specific blocker reason"
                    } else {
                        "research only when an external fact is required " +
                            "(web_search ${budget.searchUsed}/${budget.searchLimit}; " +
                            "fetch_url ${budget.fetchUsed}/${budget.fetchLimit})"
                    }
                } ?: "research only when an external fact is required"
                return listOf(
                    "inspect only the project files required to make the plan",
                    researchAction,
                    "choose algorithms, libraries, stacks, and tests yourself; ask only for a genuine user-owned blocker",
                    "return one bounded actionable Markdown plan when it is ready"
                )
            }
            return buildList {
                if (AgentService.isBuiltInAgentEnabled("CODEBASE_SCOUT")) {
                    add("delegate repository discovery to CODEBASE_SCOUT")
                }
                if (AgentService.isBuiltInAgentEnabled("RESEARCHER")) {
                    add("delegate external or knowledge research to RESEARCHER when needed")
                }
                if (AgentService.isBuiltInAgentEnabled("PLANNER")) {
                    add("delegate plan synthesis to PLANNER")
                }
                add("ask any remaining blocking user question")
                add("submit one final propose_plan")
            }
        }
        return when (currentTodo?.status) {
            AgentTodoStatus.READY,
            AgentTodoStatus.IN_PROGRESS,
            AgentTodoStatus.NEEDS_FIX ->
                if (executionProfile == AgentExecutionProfile.DIRECT) {
                    buildList {
                        if (currentBuildStepPartialArtifacts.isNotEmpty()) {
                            val nextArtifact = currentBuildStepPartialArtifacts.sorted().first()
                            add(
                                "extend the partial artifact with edit_file now: path=$nextArtifact; " +
                                    "old_text=${AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR}; new_text must stay below 5 KiB and retain exactly one ${AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR} anchor until the file is complete, then remove the anchor in the final edit"
                            )
                        } else if (currentBuildStepArtifactsComplete) {
                            add(
                                "call finish_task now with the committed artifacts and evidence; this closes BUILD only and still requires VERIFY checks"
                            )
                        } else if (currentBuildStepMissingArtifacts.isNotEmpty()) {
                            val nextArtifact = currentBuildStepMissingArtifacts.first()
                            add(
                                "create the next missing approved artifact with write_file: $nextArtifact; " +
                                    "content must stay below 5 KiB; if incomplete, end with exactly one ${AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR} anchor; " +
                                    "remaining declared artifacts: " + currentBuildStepMissingArtifacts.joinToString(", ")
                            )
                        } else if (
                            codebaseDiscoverySuppressed &&
                            !hasCommittedArtifact
                        ) {
                            add(
                                "call write_file now for the first artifact required by the current approved-plan step; greenfield inspection is complete, so do not read, list, or search first"
                            )
                        } else {
                            add(
                                "complete only the current approved-plan step: ${currentTodo.text.take(320)}"
                            )
                        }
                        add("use committed artifact receipts to preserve completed writes and do not repeat successful mutations")
                        add("a TODO awaiting verification does not mean its files are missing; do not recreate completed artifacts without a concrete defect")
                        add("when this step is satisfied, call finish_task with its artifacts and evidence; the runtime will advance to the next approved step")
                        add("on the final step, run focused checks and preview inspection before finish_task")
                    }
                } else
                if (AgentService.isBuiltInAgentEnabled("CODER")) {
                    listOf(
                        "delegate ${currentTodo.id} to CODER",
                        "include the TODO acceptance criteria and latest relevant report"
                    )
                } else {
                    listOf("enable CODER in Agent settings or resolve the TODO manually")
                }
            AgentTodoStatus.READY_FOR_REVIEW ->
                if (executionProfile == AgentExecutionProfile.DIRECT) {
                    listOf(
                        "review the current TODO directly",
                        "report review findings with finish_task"
                    )
                } else
                if (AgentService.isBuiltInAgentEnabled("REVIEWER")) {
                    listOf("delegate ${currentTodo.id} to REVIEWER")
                } else {
                    listOf("enable REVIEWER or explicitly accept review being disabled")
                }
            AgentTodoStatus.READY_FOR_VERIFICATION ->
                if (executionProfile == AgentExecutionProfile.DIRECT) {
                    listOf(
                        "verify the current TODO directly",
                        "report verification evidence with finish_task"
                    )
                } else
                if (AgentService.isBuiltInAgentEnabled("EXECUTOR")) {
                    listOf("delegate ${currentTodo.id} to EXECUTOR")
                } else {
                    listOf("enable EXECUTOR or ask the user how to verify the TODO")
                }
            AgentTodoStatus.VERIFIED ->
                listOf("transition ${currentTodo.id} from VERIFIED to COMPLETED")
            AgentTodoStatus.BLOCKED ->
                listOf(
                    "read the blocker evidence",
                    "ask the user only when an external decision is required"
                )
            AgentTodoStatus.PENDING ->
                listOf("complete its dependency TODOs first")
            null ->
                listOf(
                    "verify all required TODOs are terminal",
                    "run final reflection and present the result"
                )
            else ->
                listOf("read project_state and follow the current TODO state")
        }
    }


    private fun boundedSections(
        sections: List<String>,
        maxChars: Int,
        minimumChars: Int = 1_500
    ): String {
        val limit = maxChars.coerceAtLeast(minimumChars)
        val builder = StringBuilder()
        sections.forEach { section ->
            val separator = if (builder.isEmpty()) "" else "\n\n"
            if (builder.length + separator.length + section.length <= limit) {
                builder.append(separator).append(section)
            }
            // Optional report sections are atomic: dropping one is safe,
            // while cutting it can expose a partial opaque ID as a valid one.
        }
        return builder.toString()
    }

    /** Keeps the action tail visible when optional reports would fill a packet. */
    internal fun boundedSectionsWithReservedTail(
        prefixSections: List<String>,
        reservedTail: String,
        maxChars: Int,
        protectedSectionCount: Int = 0
    ): String {
        val limit = maxChars.coerceAtLeast(1)
        val protectedCount = protectedSectionCount.coerceIn(0, prefixSections.size)
        val protected = joinPacketSections(prefixSections.take(protectedCount))
        val tail = reservedTail.trim()
        val required = joinPacketSections(
            listOf(protected, tail).filter { it.isNotBlank() }
        )

        // Contract and decisions are authoritative. If they cannot coexist
        // with the action tail at the requested capacity, return an explicit
        // state marker instead of silently truncating either one.
        if (protectedCount > 0 && required.length > limit) {
            return controlStateDoesNotFit(
                maxChars = maxChars,
                requiredChars = required.length,
                protectedChars = protected.length,
                actionChars = tail.length
            )
        }

        val optional = prefixSections.drop(protectedCount)
        val optionalBudget = (
            limit - protected.length - tail.length -
                if (protected.isNotBlank() && tail.isNotBlank()) 4 else 0
            ).coerceAtLeast(0)
        val optionalText = if (optionalBudget > 0) {
            boundedSections(
                sections = optional,
                maxChars = optionalBudget,
                minimumChars = 1
            )
        } else {
            ""
        }
        return joinPacketSections(
            listOf(protected, optionalText, tail).filter { it.isNotBlank() }
        ).take(limit)
    }

    private fun joinPacketSections(sections: List<String>): String =
        sections.filter { it.isNotBlank() }.joinToString("\n\n")

    private fun controlStateDoesNotFit(
        maxChars: Int,
        requiredChars: Int,
        protectedChars: Int,
        actionChars: Int
    ): String = buildString {
        appendLine("# Project Control Packet")
        appendLine("- control_state_does_not_fit: true")
        appendLine("- requested_max_chars: $maxChars")
        appendLine("- durable_contract_and_decisions_chars: $protectedChars")
        appendLine("- durable_contract_plus_next_actions_chars: $requiredChars")
        appendLine("- next_actions_chars: $actionChars")
        appendLine("- action: increase the control packet capacity before continuing")
        appendLine("- reason: durable contract and exact user answers are protected from truncation")
    }.trim()

    private fun inferOwnerRole(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("research") ||
                lower.contains("documentation") ||
                lower.contains("internet") -> "RESEARCHER"
            lower.contains("inspect") ||
                lower.contains("locate") ||
                lower.contains("map the code") ||
                lower.contains("explore") -> "CODEBASE_SCOUT"
            lower.contains("review") ||
                lower.contains("audit") -> "REVIEWER"
            lower.contains("test") ||
                lower.contains("build") ||
                lower.contains("verify") ||
                lower.contains("run ") -> "EXECUTOR"
            lower.contains("visual") ||
                lower.contains("webui") ||
                lower.contains("preview") -> "VISUAL_TESTER"
            lower.contains("summary") ||
                lower.contains("memory") -> "SUMMARIZER"
            else -> "CODER"
        }
    }

    private fun inferPriority(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("critical") ||
                lower.contains("security") ||
                lower.contains("data loss") -> "HIGH"
            lower.contains("optional") ||
                lower.contains("nice to have") -> "LOW"
            else -> "NORMAL"
        }
    }

    private fun normalizeLegacyTodoStatus(status: String): String =
        when (status.uppercase(Locale.ROOT)) {
            "DONE", "SUCCESS" -> AgentTodoStatus.COMPLETED
            "VERIFIED" -> AgentTodoStatus.VERIFIED
            "IN_PROGRESS", "RUNNING" -> AgentTodoStatus.IN_PROGRESS
            "CANCELLED", "CANCELED" -> AgentTodoStatus.CANCELLED
            "BLOCKED" -> AgentTodoStatus.BLOCKED
            "READY_FOR_REVIEW" -> AgentTodoStatus.READY_FOR_REVIEW
            "NEEDS_FIX", "FAILED" -> AgentTodoStatus.NEEDS_FIX
            "READY_FOR_VERIFICATION" ->
                AgentTodoStatus.READY_FOR_VERIFICATION
            "READY" -> AgentTodoStatus.READY
            "PENDING" -> AgentTodoStatus.PENDING
            else -> AgentTodoStatus.PENDING
        }

    private fun verificationFields(rawResult: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        rawResult.lineSequence().forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator)
                .trim()
                .lowercase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_')
            val value = line.substring(separator + 1)
                .trim()
                .trim('"')
            if (key.isNotBlank() && value.isNotBlank()) fields[key] = value
        }
        runCatching {
            JSONObject(rawResult.trim())
        }.onSuccess { json ->
            listOf(
                "status",
                "state",
                "runtime",
                "preview_url",
                "url",
                "exit_code",
                "exitCode",
                "error",
                "error_message",
                "error_class",
                "error_code",
                "load_progress",
                "screenshot_path",
                "screenshot_bytes",
                "body_text",
                "controls",
                "dom_truncated"
            ).forEach { key ->
                val value = json.opt(key)
                if (value != null && value != JSONObject.NULL) {
                    fields[
                        key.lowercase(Locale.ROOT).replace('-', '_')
                    ] = value.toString()
                }
            }
        }
        if (!fields.containsKey("status") && fields.containsKey("state")) {
            fields["status"] = fields.getValue("state")
        }
        return fields
    }

    private fun hasStructuredVerificationError(fields: Map<String, String>): Boolean {
        val clearValues = setOf("", "false", "0", "none", "null", "ok", "success", "no error", "no_error")
        fun isPresent(key: String): Boolean {
            val value = fields[key]?.trim()?.lowercase(Locale.ROOT) ?: return false
            return value !in clearValues
        }
        if (isPresent("error") || isPresent("error_message") || isPresent("error_class")) {
            return true
        }
        val errorCode = fields["error_code"]?.trim()?.lowercase(Locale.ROOT)
        return errorCode != null && errorCode !in clearValues
    }

    private fun parseVerificationExitCode(
        fields: Map<String, String>,
        rawResult: String
    ): Int? {
        fields["exit_code"]?.toIntOrNull()?.let { return it }
        fields["exitcode"]?.toIntOrNull()?.let { return it }
        return Regex(
            "exit(?:[_ -]?code)\\s*[:=]\\s*(-?\\d+)",
            RegexOption.IGNORE_CASE
        ).find(rawResult)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun nonRegressiveTodoStatus(
        existing: String,
        requested: String
    ): String {
        if (existing in AgentTodoStatus.terminal) return existing
        if (requested == existing) return existing
        return if (requested in validTodoTransitions[existing].orEmpty()) {
            requested
        } else {
            existing
        }
    }

    private fun normalizeReportStatus(status: String): String =
        when (status.uppercase(Locale.ROOT)) {
            "SUCCESS", "PASSED", "PASS", "COMPLETED" -> "SUCCESS"
            "BLOCKED" -> "BLOCKED"
            "CANCELLED", "CANCELED" -> "CANCELLED"
            "INTERRUPTED" -> "INTERRUPTED"
            else -> "FAILED"
        }

    private fun normalizeStructuredReportJson(
        raw: String,
        status: String
    ): String = runCatching {
        JSONObject(raw).toString()
    }.getOrElse {
        JSONObject()
            .put("status", status)
            .put("summary", raw.take(8_000))
            .toString()
    }

    private fun changedFilesForResult(
        result: AgentResult,
        evidence: AgentEvidenceBundle
    ): List<String> = (
        evidence.changedFiles +
            when (result) {
                is AgentResult.CoderResult -> result.changedFiles
                else -> emptyList()
            }
        ).map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(100)

    private fun risksForResult(result: AgentResult): List<String> =
        when (result) {
            is AgentResult.CoderResult -> result.remainingRisks
            is AgentResult.ReviewerResult -> result.remainingRisks +
                result.findings.map {
                    "${it.severity} ${it.file}: ${it.description}"
                }
            is AgentResult.ExecutorResult ->
                if (
                    result.status.equals("SUCCESS", true) &&
                    Regex("pass|success|complete", RegexOption.IGNORE_CASE)
                        .containsMatchIn(result.finalStatus)
                ) {
                    emptyList()
                } else {
                    listOf(result.finalStatus)
                }
            is AgentResult.SummarizerResult -> emptyList()
            is AgentResult.ScoutResult -> result.risks
            is AgentResult.ResearcherResult -> result.uncertainties
            is AgentResult.PlannerResult -> result.openQuestions
            is AgentResult.GenericResult ->
                if (result.status.equals("SUCCESS", true)) {
                    emptyList()
                } else {
                    listOf(result.summary)
                }
        }.map { it.take(500) }.distinct().take(30)

    private fun recommendationsForResult(
        result: AgentResult
    ): List<String> = when (result) {
        is AgentResult.ReviewerResult ->
            result.findings.map { it.recommendation }
        is AgentResult.ExecutorResult ->
            listOf(result.nextRecommendation)
        is AgentResult.SummarizerResult ->
            result.carryForwardNotes
        is AgentResult.ScoutResult ->
            result.recommendedScope
        is AgentResult.ResearcherResult ->
            result.recommendations
        is AgentResult.PlannerResult ->
            result.recommendedNextSteps
        is AgentResult.CoderResult ->
            result.remainingRisks.map { "Resolve: $it" }
        is AgentResult.GenericResult ->
            listOf(result.summary)
    }.map { it.take(500) }.filter { it.isNotBlank() }.distinct().take(30)

    private fun AgentEvidenceBundle.toJson(): String =
        JSONObject()
            .put("changed_files", JSONArray(changedFiles))
            .put("command_ids", JSONArray(commandIds))
            .put("line_references", JSONArray(lineReferences))
            .put("memory_files_touched", JSONArray(memoryFilesTouched))
            .toString()

    private fun AgentTodoEntity.dependencies(): List<String> =
        runCatching {
            JSONArray(dependenciesJson).toStringList()
        }.getOrDefault(emptyList())

    private fun AgentTodoEntity.acceptanceCriteria(): List<String> =
        runCatching {
            JSONArray(acceptanceCriteriaJson).toStringList()
        }.getOrDefault(emptyList())

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }

    private fun parseJsonOrString(raw: String): Any =
        runCatching { JSONObject(raw) }.getOrElse { raw }

    private fun firstMeaningfulLine(text: String): String =
        text.lineSequence()
            .map { it.trim().removePrefix("#").trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(600)
            ?: "No active goal recorded."

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
