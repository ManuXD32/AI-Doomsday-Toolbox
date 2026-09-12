package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentWorkflowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSleepWake(wake: AgentSleepWakeEntity)

    @Query("SELECT * FROM agent_sleep_wakes WHERE id = :id LIMIT 1")
    suspend fun getSleepWake(id: String): AgentSleepWakeEntity?

    @Query("SELECT * FROM agent_sleep_wakes WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getSleepWakeForConversation(conversationId: Long): AgentSleepWakeEntity?

    @Query("SELECT * FROM agent_sleep_wakes WHERE status = 'PENDING' ORDER BY wakeAtEpochMs ASC")
    suspend fun getPendingSleepWakes(): List<AgentSleepWakeEntity>

    @Query(
        "UPDATE agent_sleep_wakes SET status = 'FIRED', firedAt = :firedAt, updatedAt = :firedAt " +
            "WHERE id = :id AND status = 'PENDING' AND runEpoch = :runEpoch"
    )
    suspend fun claimSleepWake(id: String, runEpoch: Long, firedAt: Long = System.currentTimeMillis()): Int

    @Query(
        "UPDATE agent_sleep_wakes SET status = 'COMPLETED', completedAt = :completedAt, updatedAt = :completedAt " +
            "WHERE id = :id AND status = 'FIRED'"
    )
    suspend fun completeSleepWake(id: String, completedAt: Long = System.currentTimeMillis()): Int

    @Query(
        "UPDATE agent_sleep_wakes SET status = 'CANCELLED', completedAt = :cancelledAt, updatedAt = :cancelledAt " +
            "WHERE conversationId = :conversationId AND status IN ('PENDING', 'FIRED')"
    )
    suspend fun cancelSleepWakeForConversation(
        conversationId: Long,
        cancelledAt: Long = System.currentTimeMillis()
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessagePart(part: AgentMessagePartEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessageParts(parts: List<AgentMessagePartEntity>)

    @Query(
        """
        SELECT * FROM agent_message_parts
        WHERE conversationId = :conversationId
        ORDER BY messageOriginalId ASC, position ASC
        """
    )
    fun observeMessageParts(conversationId: Long): Flow<List<AgentMessagePartEntity>>

    @Query(
        """
        SELECT * FROM agent_message_parts
        WHERE invocationId = :invocationId
        ORDER BY createdAt ASC, position ASC
        """
    )
    fun observeMessagePartsForInvocation(invocationId: String): Flow<List<AgentMessagePartEntity>>

    @Query(
        """
        SELECT * FROM agent_message_parts
        WHERE conversationId = :conversationId
        ORDER BY createdAt ASC, position ASC
        """
    )
    suspend fun getMessageParts(conversationId: Long): List<AgentMessagePartEntity>

    @Query("DELETE FROM agent_message_parts WHERE messageOriginalId = :messageOriginalId")
    suspend fun deleteMessageParts(messageOriginalId: String)

    @Query(
        "DELETE FROM agent_message_parts WHERE conversationId = :conversationId AND messageOriginalId IN " +
            "(SELECT originalId FROM agent_messages WHERE conversationId = :conversationId AND " +
            "(sequenceNumber > :fromSequenceNumber OR (sequenceNumber = :fromSequenceNumber AND id >= :fromMessageId)))"
    )
    suspend fun deleteMessagePartsAtOrAfter(conversationId: Long, fromSequenceNumber: Int, fromMessageId: Long)

    @Query("DELETE FROM agent_message_parts WHERE conversationId = :conversationId")
    suspend fun deleteMessagePartsForConversation(conversationId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTurnContext(context: AgentTurnContextEntity)

    @Query("SELECT * FROM agent_turn_contexts WHERE rootTurnId = :rootTurnId")
    suspend fun getTurnContext(rootTurnId: String): AgentTurnContextEntity?

    @Query(
        """
        SELECT * FROM agent_turn_contexts
        WHERE conversationId = :conversationId AND agentKey = :agentKey
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun getLatestTurnContext(
        conversationId: Long,
        agentKey: String
    ): AgentTurnContextEntity?

    /**
     * Returns the newest turn that has not crossed a durable terminal boundary.
     * A process can disappear while the conversation row still says IDLE, so
     * cold recovery must inspect this workflow state independently.
     */
    @Query(
        """
        SELECT * FROM agent_turn_contexts
        WHERE conversationId = :conversationId
          AND status = 'ACTIVE'
          AND completedAt IS NULL
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun getLatestOpenTurnContext(
        conversationId: Long
    ): AgentTurnContextEntity?

    @Query(
        """
        UPDATE agent_turn_contexts
        SET status = :status, completedAt = :completedAt
        WHERE rootTurnId = :rootTurnId
        """
    )
    suspend fun finishTurnContext(rootTurnId: String, status: String, completedAt: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkill(skill: AgentSkillEntity)

    @Query("SELECT * FROM agent_skills ORDER BY name COLLATE NOCASE ASC")
    fun observeSkills(): Flow<List<AgentSkillEntity>>

    @Query("SELECT * FROM agent_skills WHERE enabled = 1 ORDER BY name COLLATE NOCASE ASC")
    suspend fun getEnabledSkills(): List<AgentSkillEntity>

    @Query("SELECT * FROM agent_skills WHERE id = :id")
    suspend fun getSkill(id: String): AgentSkillEntity?

    @Query("DELETE FROM agent_skills WHERE id = :id")
    suspend fun deleteSkill(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkillAssignment(assignment: AgentSkillAssignmentEntity)

    @Query(
        """
        SELECT * FROM agent_skill_assignments
        WHERE skillId = :skillId
          AND (conversationId = :conversationId OR conversationId IS NULL)
          AND (agentKey = :agentKey OR agentKey = '*')
        ORDER BY CASE WHEN conversationId IS NULL THEN 1 ELSE 0 END,
                 CASE WHEN agentKey = '*' THEN 1 ELSE 0 END
        LIMIT 1
        """
    )
    suspend fun resolveSkillAssignment(
        skillId: String,
        conversationId: Long?,
        agentKey: String
    ): AgentSkillAssignmentEntity?

    @Query("SELECT * FROM agent_skill_assignments ORDER BY updatedAt DESC")
    fun observeSkillAssignments(): Flow<List<AgentSkillAssignmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingQuestion(question: AgentPendingQuestionEntity)

    @Query(
        """
        UPDATE agent_pending_questions
        SET draftAnswerJson = :draftAnswerJson,
            currentPage = :currentPage,
            isCollapsed = :isCollapsed
        WHERE id = :id AND status = 'PENDING'
        """
    )
    suspend fun updateQuestionDraft(
        id: String,
        draftAnswerJson: String,
        currentPage: Int,
        isCollapsed: Boolean
    ): Int

    @Query(
        """
        SELECT * FROM agent_pending_questions
        WHERE conversationId = :conversationId AND status = 'PENDING'
        ORDER BY createdAt ASC
        """
    )
    fun observePendingQuestions(conversationId: Long): Flow<List<AgentPendingQuestionEntity>>

    @Query(
        """
        SELECT * FROM agent_pending_questions
        WHERE conversationId = :conversationId AND status = 'PENDING'
        ORDER BY createdAt ASC
        """
    )
    suspend fun getPendingQuestions(conversationId: Long): List<AgentPendingQuestionEntity>

    @Query("SELECT * FROM agent_pending_questions WHERE id = :id")
    suspend fun getPendingQuestion(id: String): AgentPendingQuestionEntity?

    @Query(
        """
        UPDATE agent_pending_questions
        SET answerJson = :answerJson,
            status = 'ANSWERED',
            answeredAt = :answeredAt
        WHERE id = :id AND status = 'PENDING'
        """
    )
    suspend fun answerQuestionExactlyOnce(
        id: String,
        answerJson: String,
        answeredAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Commits the answer-side receipt fields together with the ANSWERED
     * transition. The canonical message and outbox row are written by the
     * surrounding Room transaction.
     */
    @Query(
        """
        UPDATE agent_pending_questions
        SET answerJson = :answerJson,
            answerMessageOriginalId = :answerMessageOriginalId,
            answerReceiptId = :answerReceiptId,
            status = 'ANSWERED',
            continuationEnqueued = 1,
            answeredAt = :answeredAt
        WHERE id = :id AND status = 'PENDING'
        """
    )
    suspend fun answerQuestionWithReceiptExactlyOnce(
        id: String,
        answerJson: String,
        answerMessageOriginalId: String,
        answerReceiptId: String,
        answeredAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_pending_questions
        SET continuationEnqueued = 1
        WHERE id = :id AND status = 'ANSWERED' AND continuationEnqueued = 0
        """
    )
    suspend fun markQuestionContinuationEnqueued(id: String): Int

    @Query(
        """
        SELECT * FROM agent_pending_questions
        WHERE conversationId = :conversationId
          AND status = 'ANSWERED'
          AND continuationEnqueued = 0
        ORDER BY answeredAt ASC
        """
    )
    suspend fun getAnsweredQuestionsAwaitingContinuation(
        conversationId: Long
    ): List<AgentPendingQuestionEntity>

    @Query(
        """
        SELECT * FROM agent_pending_questions
        WHERE conversationId = :conversationId AND status = 'ANSWERED'
        ORDER BY answeredAt ASC, createdAt ASC
        """
    )
    suspend fun getAnsweredQuestions(
        conversationId: Long
    ): List<AgentPendingQuestionEntity>

    @Query(
        """
        UPDATE agent_pending_questions
        SET answerMessageOriginalId = :answerMessageOriginalId,
            answerReceiptId = COALESCE(:answerReceiptId, answerReceiptId)
        WHERE id = :id AND status = 'ANSWERED'
        """
    )
    suspend fun attachQuestionAnswerReceipt(
        id: String,
        answerMessageOriginalId: String,
        answerReceiptId: String? = null
    ): Int

    @Query(
        """
        UPDATE agent_pending_questions
        SET status = 'CANCELLED'
        WHERE conversationId = :conversationId AND status = 'PENDING'
        """
    )
    suspend fun cancelPendingQuestions(
        conversationId: Long
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM agent_pending_questions
        WHERE conversationId = :conversationId
          AND rootTurnId = :rootTurnId
          AND status = 'ANSWERED'
        """
    )
    suspend fun countAnsweredQuestionsForRootTurn(
        conversationId: Long,
        rootTurnId: String
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingPlan(plan: AgentPendingPlanEntity)

    @Query(
        """
        SELECT * FROM agent_pending_plans
        WHERE conversationId = :conversationId
          AND state IN ('AWAITING_APPROVAL', 'APPROVING', 'APPROVED', 'STARTING_BUILD')
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    fun observePendingPlan(conversationId: Long): Flow<AgentPendingPlanEntity?>

    @Query(
        """
        SELECT * FROM agent_pending_plans
        WHERE conversationId = :conversationId
          AND state IN ('AWAITING_APPROVAL', 'APPROVING', 'APPROVED', 'STARTING_BUILD')
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun getPendingPlan(conversationId: Long): AgentPendingPlanEntity?

    @Query("SELECT * FROM agent_pending_plans WHERE id = :id")
    suspend fun getPendingPlanById(id: String): AgentPendingPlanEntity?

    @Query("SELECT * FROM agent_pending_plans WHERE planMessageId = :planMessageId LIMIT 1")
    suspend fun getPendingPlanByMessageId(planMessageId: String): AgentPendingPlanEntity?

    @Query(
        """
        UPDATE agent_pending_plans
        SET state = 'APPROVING',
            approvalOperationId = :operationId,
            editedPlan = :editedPlan,
            errorMessage = NULL,
            updatedAt = :updatedAt
        WHERE id = :id AND state = 'AWAITING_APPROVAL'
        """
    )
    suspend fun beginPlanResolution(
        id: String,
        operationId: String,
        editedPlan: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_pending_plans
        SET state = 'AWAITING_APPROVAL',
            approvalOperationId = NULL,
            buildModeActivated = 0,
            continuationEnqueued = 0,
            errorMessage = :errorMessage,
            updatedAt = :updatedAt
        WHERE id = :id AND state != 'BUILDING' AND approvalOperationId = :operationId
        """
    )
    suspend fun failPlanResolution(
        id: String,
        operationId: String,
        errorMessage: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_pending_plans
        SET state = :state,
            planFileWritten = :planFileWritten,
            buildModeActivated = :buildModeActivated,
            continuationEnqueued = :continuationEnqueued,
            approvedAt = :approvedAt,
            errorMessage = NULL,
            updatedAt = :updatedAt
        WHERE id = :id AND approvalOperationId = :operationId
        """
    )
    suspend fun checkpointPlanResolution(
        id: String,
        operationId: String,
        state: String,
        planFileWritten: Boolean,
        buildModeActivated: Boolean,
        continuationEnqueued: Boolean,
        approvedAt: Long? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_pending_plans
        SET state = :state, updatedAt = :updatedAt
        WHERE conversationId = :conversationId
          AND state IN ('AWAITING_APPROVAL', 'APPROVING', 'APPROVED', 'STARTING_BUILD')
        """
    )
    suspend fun terminatePendingPlans(
        conversationId: Long,
        state: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTodo(todo: AgentTodoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTodos(todos: List<AgentTodoEntity>)

    @Query("SELECT * FROM agent_todos WHERE conversationId = :conversationId ORDER BY position ASC")
    fun observeTodos(conversationId: Long): Flow<List<AgentTodoEntity>>

    @Query("SELECT * FROM agent_todos WHERE conversationId = :conversationId ORDER BY position ASC")
    suspend fun getTodos(conversationId: Long): List<AgentTodoEntity>

    @Query("DELETE FROM agent_todos WHERE conversationId = :conversationId")
    suspend fun clearTodos(conversationId: Long)

    @Update
    suspend fun updateTodo(todo: AgentTodoEntity)

    @Query("SELECT * FROM agent_todos WHERE id = :id LIMIT 1")
    suspend fun getTodoById(id: String): AgentTodoEntity?

    @Query(
        """
        SELECT * FROM agent_todos
        WHERE conversationId = :conversationId
          AND planVersionId = :planVersionId
        ORDER BY position ASC
        """
    )
    suspend fun getTodosForPlanVersion(
        conversationId: Long,
        planVersionId: String
    ): List<AgentTodoEntity>

    @Query(
        """
        UPDATE agent_todos
        SET status = :newStatus,
            ownerRole = :ownerRole,
            assignedInvocationId = :assignedInvocationId,
            resultSummary = :resultSummary,
            blockReason = :blockReason,
            evidenceJson = :evidenceJson,
            completedAt = :completedAt,
            updatedAt = :updatedAt
        WHERE id = :id AND status = :expectedStatus
        """
    )
    suspend fun transitionTodoExactlyOnce(
        id: String,
        expectedStatus: String,
        newStatus: String,
        ownerRole: String?,
        assignedInvocationId: String?,
        resultSummary: String?,
        blockReason: String?,
        evidenceJson: String,
        completedAt: Long?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_todos
        SET status = 'IN_PROGRESS',
            ownerRole = :ownerRole,
            assignedInvocationId = :invocationId,
            attemptCount = attemptCount + 1,
            blockReason = NULL,
            updatedAt = :updatedAt
        WHERE id = :id
          AND status = :expectedStatus
          AND assignedInvocationId IS NULL
        """
    )
    suspend fun claimTodoExactlyOnce(
        id: String,
        expectedStatus: String,
        invocationId: String,
        ownerRole: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_todos
        SET status = :newStatus,
            ownerRole = :ownerRole,
            assignedInvocationId = NULL,
            resultSummary = :resultSummary,
            blockReason = :blockReason,
            evidenceJson = :evidenceJson,
            completedAt = :completedAt,
            updatedAt = :updatedAt
        WHERE id = :id
          AND status = :expectedStatus
          AND assignedInvocationId = :invocationId
        """
    )
    suspend fun completeTodoInvocationExactlyOnce(
        id: String,
        invocationId: String,
        expectedStatus: String,
        newStatus: String,
        ownerRole: String?,
        resultSummary: String?,
        blockReason: String?,
        evidenceJson: String,
        completedAt: Long?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_todos
        SET status = :newStatus, updatedAt = :updatedAt
        WHERE id = :id AND status = :expectedStatus
        """
    )
    suspend fun markTodoReadyExactlyOnce(
        id: String,
        expectedStatus: String,
        newStatus: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProjectStateIfMissing(
        state: AgentProjectStateEntity
    ): Long

    @Query(
        "SELECT * FROM agent_project_states " +
            "WHERE conversationId = :conversationId LIMIT 1"
    )
    suspend fun getProjectState(
        conversationId: Long
    ): AgentProjectStateEntity?

    @Query(
        """
        UPDATE agent_project_states
        SET mode = COALESCE(:mode, mode),
            currentGoal = COALESCE(:currentGoal, currentGoal),
            updatedAt = :updatedAt
        WHERE conversationId = :conversationId
        """
    )
    suspend fun updateProjectStateBasics(
        conversationId: Long,
        mode: String?,
        currentGoal: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_project_states
        SET revision = revision + 1,
            semanticEventCount = semanticEventCount + 1,
            lastSemanticEvent = :semanticEvent,
            currentGoal = COALESCE(:currentGoal, currentGoal),
            updatedAt = :updatedAt
        WHERE conversationId = :conversationId
        """
    )
    suspend fun bumpProjectStateRevision(
        conversationId: Long,
        semanticEvent: String,
        currentGoal: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_project_states
        SET revision = revision + 1,
            semanticEventCount = semanticEventCount + 1,
            lastSemanticEvent = 'plan_approved',
            mode = 'BUILD',
            currentGoal = :currentGoal,
            activePlanVersionId = :planVersionId,
            currentPhaseId = :currentPhaseId,
            currentTodoId = :currentTodoId,
            updatedAt = :updatedAt
        WHERE conversationId = :conversationId
        """
    )
    suspend fun activateApprovedPlanState(
        conversationId: Long,
        planVersionId: String,
        currentPhaseId: String?,
        currentTodoId: String?,
        currentGoal: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_project_states
        SET currentPhaseId = :phaseId,
            currentTodoId = :todoId,
            updatedAt = :updatedAt
        WHERE conversationId = :conversationId
        """
    )
    suspend fun setProjectCurrentTodo(
        conversationId: Long,
        phaseId: String?,
        todoId: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_project_states
        SET lastCompactedRevision = revision,
            lastCompactionSemanticEventCount = semanticEventCount,
            lastCompactionKey = :compactionKey,
            lastCompactionStatus = 'RUNNING',
            lastCompactionPreTokens = :preTokens,
            lastCompactionPostTokens = NULL,
            lastCompactionSavedTokens = NULL,
            lastCompactionSaturationReason = NULL,
            lastCompactionAt = :updatedAt,
            updatedAt = :updatedAt
        WHERE conversationId = :conversationId
        """
    )
    suspend fun recordProjectCompactionStarted(
        conversationId: Long,
        compactionKey: String,
        preTokens: Int,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_project_states
        SET lastCompactionStatus = :status,
            lastCompactionPostTokens = :postTokens,
            lastCompactionSavedTokens = :savedTokens,
            lastCompactionSaturationReason = :saturationReason,
            lastCompactionAt = :updatedAt,
            updatedAt = :updatedAt
        WHERE conversationId = :conversationId
        """
    )
    suspend fun recordProjectCompactionCompleted(
        conversationId: Long,
        status: String,
        postTokens: Int,
        savedTokens: Int,
        saturationReason: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_project_states
        SET lastCompactionStatus = 'FAILED',
            lastCompactionSaturationReason = :reason,
            lastCompactionAt = :updatedAt,
            updatedAt = :updatedAt
        WHERE conversationId = :conversationId
        """
    )
    suspend fun recordProjectCompactionFailed(
        conversationId: Long,
        reason: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlanVersion(plan: AgentPlanVersionEntity)

    @Query(
        """
        SELECT * FROM agent_plan_versions
        WHERE conversationId = :conversationId AND planHash = :planHash
        LIMIT 1
        """
    )
    suspend fun getPlanVersionByHash(
        conversationId: Long,
        planHash: String
    ): AgentPlanVersionEntity?

    @Query(
        "SELECT * FROM agent_plan_versions WHERE id = :id LIMIT 1"
    )
    suspend fun getPlanVersionById(id: String): AgentPlanVersionEntity?

    @Query(
        """
        SELECT * FROM agent_plan_versions
        WHERE conversationId = :conversationId AND status = 'APPROVED'
        ORDER BY versionNumber DESC
        LIMIT 1
        """
    )
    suspend fun getLatestApprovedPlan(
        conversationId: Long
    ): AgentPlanVersionEntity?

    @Query(
        """
        SELECT COALESCE(MAX(versionNumber), 0) + 1
        FROM agent_plan_versions
        WHERE conversationId = :conversationId
        """
    )
    suspend fun getNextPlanVersionNumber(conversationId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkReport(report: AgentWorkReportEntity)

    @Query(
        "SELECT * FROM agent_work_reports WHERE id = :id LIMIT 1"
    )
    suspend fun getWorkReport(id: String): AgentWorkReportEntity?

    @Query(
        """
        SELECT * FROM agent_work_reports
        WHERE conversationId = :conversationId
        ORDER BY createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentWorkReports(
        conversationId: Long,
        limit: Int
    ): List<AgentWorkReportEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompaction(compaction: AgentCompactionEntity)

    @Query(
        """
        SELECT * FROM agent_compactions
        WHERE conversationId = :conversationId
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun getLatestCompaction(conversationId: Long): AgentCompactionEntity?

    @Query("SELECT * FROM agent_compactions WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    fun observeCompactions(conversationId: Long): Flow<List<AgentCompactionEntity>>

    @Query("SELECT * FROM agent_compactions WHERE invocationId = :invocationId ORDER BY createdAt DESC")
    fun observeCompactionsForInvocation(invocationId: String): Flow<List<AgentCompactionEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInvocation(invocation: AgentInvocationEntity)

    @Query("SELECT * FROM agent_invocations WHERE id = :id")
    suspend fun getInvocation(id: String): AgentInvocationEntity?

    @Query("SELECT * FROM agent_invocations WHERE id = :id")
    fun observeInvocation(id: String): Flow<AgentInvocationEntity?>

    @Query("SELECT * FROM agent_invocations WHERE conversationId = :conversationId ORDER BY startedAt DESC")
    fun observeInvocations(conversationId: Long): Flow<List<AgentInvocationEntity>>

    @Query("SELECT * FROM agent_invocations WHERE conversationId = :conversationId ORDER BY startedAt DESC")
    suspend fun getInvocations(conversationId: Long): List<AgentInvocationEntity>

    @Query("SELECT * FROM agent_invocations WHERE parentToolCallId = :parentToolCallId LIMIT 1")
    suspend fun getInvocationForParentToolCall(parentToolCallId: String): AgentInvocationEntity?

    @Query("SELECT COALESCE(MAX(occurrence), 0) FROM agent_invocations WHERE conversationId = :conversationId AND baseNameKey = :baseNameKey")
    suspend fun getMaxInvocationOccurrence(conversationId: Long, baseNameKey: String): Int

    @Query("SELECT COUNT(*) FROM agent_invocations WHERE conversationId = :conversationId AND resolvedNameKey = :resolvedNameKey")
    suspend fun countResolvedInvocationName(conversationId: Long, resolvedNameKey: String): Int

    @Transaction
    suspend fun allocateInvocation(prototype: AgentInvocationEntity): AgentInvocationEntity {
        var occurrence = (getMaxInvocationOccurrence(prototype.conversationId, prototype.baseNameKey) + 1)
            .coerceAtLeast(1)
        while (true) {
            val resolvedName = if (occurrence == 1) prototype.requestedName else "${prototype.requestedName} $occurrence"
            val resolvedNameKey = resolvedName.lowercase()
            if (countResolvedInvocationName(prototype.conversationId, resolvedNameKey) == 0) {
                val allocated = prototype.copy(
                    occurrence = occurrence,
                    resolvedName = resolvedName,
                    resolvedNameKey = resolvedNameKey
                )
                insertInvocation(allocated)
                return allocated
            }
            occurrence += 1
        }
    }

    @Query("UPDATE agent_invocations SET sessionId = :sessionId, updatedAt = :updatedAt WHERE id = :id AND status = 'RUNNING'")
    suspend fun attachInvocationSession(
        id: String,
        sessionId: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_invocations
        SET workReportId = :workReportId,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun attachInvocationWorkReport(
        id: String,
        workReportId: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_invocations
        SET status = :status,
            resultSummary = :resultSummary,
            errorClass = :errorClass,
            errorMessage = :errorMessage,
            endedAt = :endedAt,
            updatedAt = :endedAt
        WHERE id = :id AND status = 'RUNNING'
        """
    )
    suspend fun finishInvocationExactlyOnce(
        id: String,
        status: String,
        resultSummary: String?,
        errorClass: String? = null,
        errorMessage: String? = null,
        endedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_invocations
        SET status = 'INTERRUPTED',
            errorMessage = :reason,
            endedAt = :endedAt,
            updatedAt = :endedAt
        WHERE conversationId = :conversationId AND status = 'RUNNING'
        """
    )
    suspend fun interruptRunningInvocations(
        conversationId: Long,
        reason: String,
        endedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_invocations
        SET backend = :backend,
            modelLabel = :modelLabel,
            serverPhase = :serverPhase,
            contextSize = :contextSize,
            rawEstimatedTokens = :rawEstimatedTokens,
            packedEstimatedTokens = :packedEstimatedTokens,
            actualPromptTokens = :actualPromptTokens,
            actualCompletionTokens = :actualCompletionTokens,
            contextPercent = :contextPercent,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateInvocationMetrics(
        id: String,
        backend: String?,
        modelLabel: String?,
        serverPhase: String?,
        contextSize: Int?,
        rawEstimatedTokens: Int?,
        packedEstimatedTokens: Int?,
        actualPromptTokens: Int?,
        actualCompletionTokens: Int?,
        contextPercent: Int?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingInput(input: AgentPendingInputEntity)

    /**
     * Idempotent insert for asynchronous queue writers.  A late writer must
     * never replace a row that Stop already marked CANCELLED (or a delivered
     * row that has already crossed a model boundary).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingInputIfMissing(input: AgentPendingInputEntity)

    @Query("SELECT * FROM agent_pending_inputs WHERE id = :id LIMIT 1")
    suspend fun getPendingInput(id: String): AgentPendingInputEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingInputs(inputs: List<AgentPendingInputEntity>)

    @Query("SELECT COALESCE(MAX(sequenceNumber), 0) FROM agent_pending_inputs WHERE conversationId = :conversationId")
    suspend fun getMaxPendingInputSequence(conversationId: Long): Long

    @Query(
        """
        SELECT * FROM agent_pending_inputs
        WHERE conversationId = :conversationId
          AND status = 'QUEUED'
          AND ((:targetInvocationId IS NULL AND targetInvocationId IS NULL) OR targetInvocationId = :targetInvocationId)
        ORDER BY sequenceNumber ASC
        """
    )
    suspend fun getQueuedInputs(
        conversationId: Long,
        targetInvocationId: String?
    ): List<AgentPendingInputEntity>

    @Query(
        """
        SELECT COUNT(*) FROM agent_pending_inputs
        WHERE conversationId = :conversationId
          AND status = 'QUEUED'
          AND ((:targetInvocationId IS NULL AND targetInvocationId IS NULL) OR targetInvocationId = :targetInvocationId)
        """
    )
    fun observeQueuedInputCount(conversationId: Long, targetInvocationId: String?): Flow<Int>

    @Query("SELECT COUNT(*) FROM agent_pending_inputs WHERE conversationId = :conversationId AND status = 'QUEUED'")
    suspend fun getQueuedInputCount(conversationId: Long): Int

    @Query(
        """
        SELECT * FROM agent_pending_inputs
        WHERE conversationId = :conversationId
          AND status = 'QUEUED'
          AND ((:targetInvocationId IS NULL AND targetInvocationId IS NULL) OR targetInvocationId = :targetInvocationId)
        ORDER BY sequenceNumber ASC
        """
    )
    fun observeQueuedInputs(
        conversationId: Long,
        targetInvocationId: String?
    ): Flow<List<AgentPendingInputEntity>>

    @Query(
        """
        UPDATE agent_pending_inputs
        SET status = 'DELIVERED', boundaryToolCallId = :boundaryToolCallId, deliveredAt = :deliveredAt
        WHERE id IN (:ids) AND status = 'QUEUED'
        """
    )
    suspend fun markPendingInputsDelivered(
        ids: List<String>,
        boundaryToolCallId: String?,
        deliveredAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_pending_inputs
        SET status = 'CANCELLED', cancelledAt = :cancelledAt
        WHERE targetInvocationId = :invocationId AND status = 'QUEUED'
        """
    )
    suspend fun cancelInvocationPendingInputs(
        invocationId: String,
        cancelledAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_pending_inputs
        SET status = 'CANCELLED', cancelledAt = :cancelledAt
        WHERE conversationId = :conversationId AND status = 'QUEUED'
        """
    )
    suspend fun cancelConversationPendingInputs(
        conversationId: Long,
        cancelledAt: Long = System.currentTimeMillis()
    ): Int

    // ========== Durable project contract and decisions ==========

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProjectContractIfMissing(
        contract: AgentProjectContractEntity
    ): Long

    @Query("SELECT * FROM agent_project_contracts WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getProjectContract(conversationId: Long): AgentProjectContractEntity?

    @Query(
        """
        UPDATE agent_project_contracts
        SET initialGoal = :initialGoal,
            initialGoalSource = :initialGoalSource,
            updatedAt = :updatedAt
        WHERE conversationId = :conversationId AND TRIM(initialGoal) = ''
        """
    )
    suspend fun setInitialGoalIfBlank(
        conversationId: Long,
        initialGoal: String,
        initialGoalSource: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_project_contracts
        SET noMoreQuestions = :noMoreQuestions, updatedAt = :updatedAt
        WHERE conversationId = :conversationId
        """
    )
    suspend fun setNoMoreQuestions(
        conversationId: Long,
        noMoreQuestions: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_project_contracts
        SET greenfield = :greenfield, updatedAt = :updatedAt
        WHERE conversationId = :conversationId
        """
    )
    suspend fun setGreenfield(
        conversationId: Long,
        greenfield: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDecision(decision: AgentDecisionEntity)

    @Query(
        """
        UPDATE agent_decisions
        SET isLatest = 0,
            latestCorrectionId = :correctionId,
            updatedAt = :updatedAt
        WHERE conversationId = :conversationId
          AND decisionKey = :decisionKey
          AND isLatest = 1
        """
    )
    suspend fun supersedeLatestDecision(
        conversationId: Long,
        decisionKey: String,
        correctionId: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_decisions
        SET isLatest = 0,
            latestCorrectionId = :correctionId,
            updatedAt = :updatedAt
        WHERE id = :supersededDecisionId AND conversationId = :conversationId
        """
    )
    suspend fun supersedeDecision(
        conversationId: Long,
        supersededDecisionId: String,
        correctionId: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        SELECT * FROM agent_decisions
        WHERE conversationId = :conversationId AND isLatest = 1
        ORDER BY createdAt ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getLatestDecisions(
        conversationId: Long,
        limit: Int = 20
    ): List<AgentDecisionEntity>

    @Query(
        """
        SELECT * FROM agent_decisions
        WHERE conversationId = :conversationId AND isLatest = 1
        ORDER BY createdAt ASC, id ASC
        """
    )
    suspend fun getAllLatestDecisions(
        conversationId: Long
    ): List<AgentDecisionEntity>

    @Query("SELECT * FROM agent_decisions WHERE id = :id LIMIT 1")
    suspend fun getDecision(id: String): AgentDecisionEntity?

    @Query(
        """
        SELECT * FROM agent_decisions
        WHERE conversationId = :conversationId AND decisionKey = :decisionKey
        ORDER BY createdAt DESC, id DESC
        """
    )
    suspend fun getDecisionsForKey(
        conversationId: Long,
        decisionKey: String
    ): List<AgentDecisionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContinuationReceipt(
        receipt: AgentContinuationOutboxEntity
    ): Long

    @Query(
        "SELECT * FROM agent_continuation_outbox WHERE conversationId = :conversationId AND dedupeKey = :dedupeKey LIMIT 1"
    )
    suspend fun getContinuationReceipt(
        conversationId: Long,
        dedupeKey: String
    ): AgentContinuationOutboxEntity?

    @Query(
        "SELECT * FROM agent_continuation_outbox WHERE id = :id LIMIT 1"
    )
    suspend fun getContinuationReceiptById(id: String): AgentContinuationOutboxEntity?

    /**
     * Returns a bounded newest-first view of mutation receipts. The payload
     * shape is written by actionReceiptPayload, so this avoids spending the
     * bound on read/check receipts without requiring SQLite JSON1; payload
     * status is still validated by the store.
     */
    @Query(
        """
        SELECT * FROM agent_continuation_outbox
        WHERE conversationId = :conversationId
          AND kind = 'ACTION_RECEIPT'
          AND status = 'COMPLETED'
          AND (
              payloadJson LIKE '%"tool":"write_file"%'
              OR payloadJson LIKE '%"tool":"edit_lines"%'
              OR payloadJson LIKE '%"tool":"append_file"%'
              OR payloadJson LIKE '%"tool":"apply_patch"%'
              OR payloadJson LIKE '%"tool":"delete_file"%'
          )
        ORDER BY createdAt DESC, updatedAt DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getLatestCompletedActionReceipts(
        conversationId: Long,
        limit: Int
    ): List<AgentContinuationOutboxEntity>

    /**
     * Returns the newest completed PASS transition for the active approved
     * plan. The payload filters are deliberately narrow because the existing
     * outbox table stores the control-plane receipt kind and JSON metadata
     * together without a separate verification table.
     */
    @Query(
        """
        SELECT * FROM agent_continuation_outbox
        WHERE conversationId = :conversationId
          AND kind = 'VERIFICATION_PHASE_TRANSITION'
          AND status = 'COMPLETED'
          AND payloadJson LIKE '%"receipt_type":"verification_phase"%'
          AND payloadJson LIKE '%"planning_episode_id":"' || :planningEpisodeId || '"%'
          AND payloadJson LIKE '%"plan_version_id":"' || :planVersionId || '"%'
          AND payloadJson LIKE '%"disposition":"PASS"%'
        ORDER BY createdAt DESC, updatedAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestCompletedPassVerificationReceipt(
        conversationId: Long,
        planningEpisodeId: String,
        planVersionId: String
    ): AgentContinuationOutboxEntity?

    /**
     * Returns a bounded newest-first action receipt window for the active
     * planning episode. Failed actions are included because a command or patch
     * can partially change files before reporting an error; the control plane
     * filters known read-only tools and treats enabled custom actions as
     * effectful by default.
     */
    @Query(
        """
        SELECT * FROM agent_continuation_outbox
        WHERE conversationId = :conversationId
          AND kind = 'ACTION_RECEIPT'
          AND status IN ('COMPLETED', 'FAILED')
          AND payloadJson LIKE '%"receipt_type":"action"%'
          AND payloadJson LIKE '%"planning_episode_id":"' || :planningEpisodeId || '"%'
        ORDER BY createdAt DESC, updatedAt DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getLatestActionReceiptsForEpisode(
        conversationId: Long,
        planningEpisodeId: String,
        limit: Int
    ): List<AgentContinuationOutboxEntity>

    /**
     * ENQUEUED is the durable handoff state used after a tool result has been
     * committed. It must remain recoverable after process death until the
     * continuation worker claims and completes it.
     */
    @Query(
        """
        SELECT COUNT(*) FROM agent_continuation_outbox
        WHERE conversationId = :conversationId
          AND kind = 'TOOL_CONTINUATION'
          AND status IN ('QUEUED', 'CLAIMED', 'ENQUEUED')
          AND completedAt IS NULL
        """
    )
    suspend fun countPendingToolContinuations(
        conversationId: Long
    ): Int

    @Query(
        """
        SELECT * FROM agent_continuation_outbox
        WHERE conversationId = :conversationId
          AND kind = 'TOOL_CONTINUATION'
          AND status IN ('QUEUED', 'CLAIMED', 'ENQUEUED')
          AND completedAt IS NULL
        ORDER BY COALESCE(enqueuedAt, updatedAt, createdAt) DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestPendingToolContinuation(
        conversationId: Long
    ): AgentContinuationOutboxEntity?

    /** Counts durable receipts for one bounded action family in an episode. */
    @Query(
        """
        SELECT COUNT(*) FROM agent_continuation_outbox
        WHERE conversationId = :conversationId
          AND kind = :kind
          AND dedupeKey LIKE :dedupePrefix
          AND status != 'CANCELLED'
        """
    )
    suspend fun countContinuationReceipts(
        conversationId: Long,
        kind: String,
        dedupePrefix: String
    ): Int

    @Query(
        """
        SELECT * FROM agent_continuation_outbox
        WHERE conversationId = :conversationId AND status IN ('QUEUED', 'CLAIMED')
        ORDER BY createdAt ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getPendingContinuations(
        conversationId: Long,
        limit: Int = 20
    ): List<AgentContinuationOutboxEntity>

    @Query(
        """
        UPDATE agent_continuation_outbox
        SET status = 'CLAIMED', attemptCount = attemptCount + 1,
            claimedAt = :claimedAt, updatedAt = :claimedAt
        WHERE id = :id AND status = 'QUEUED'
        """
    )
    suspend fun claimContinuation(
        id: String,
        claimedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_continuation_outbox
        SET status = :status, enqueuedAt = COALESCE(:enqueuedAt, enqueuedAt),
            completedAt = :completedAt, errorClass = :errorClass,
            errorMessage = :errorMessage, updatedAt = :updatedAt
        WHERE id = :id AND status IN ('CLAIMED', 'QUEUED')
        """
    )
    suspend fun finishContinuation(
        id: String,
        status: String,
        enqueuedAt: Long? = null,
        completedAt: Long? = null,
        errorClass: String? = null,
        errorMessage: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE agent_continuation_outbox
        SET status = 'CANCELLED', completedAt = :cancelledAt, updatedAt = :cancelledAt
        WHERE conversationId = :conversationId AND status IN ('QUEUED', 'CLAIMED', 'ENQUEUED')
        """
    )
    suspend fun cancelConversationContinuations(
        conversationId: Long,
        cancelledAt: Long = System.currentTimeMillis()
    ): Int
}
