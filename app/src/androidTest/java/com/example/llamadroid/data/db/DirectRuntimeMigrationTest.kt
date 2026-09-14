package com.example.llamadroid.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.service.AgentProjectControlPlane
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectRuntimeMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AppDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate118To119_normalizesProfilesAndPreservesConversationData() {
        helper.createDatabase(MIGRATION_DB, 118).apply {
            execSQL(
                """
                INSERT INTO agent_conversations (
                    id, title, projectFolder, sortOrder, planningModeEnabled,
                    resumeState, knowledgeBaseIds, workspaceBackend,
                    runtimeCapabilitiesJson, runUiMode, lastRunProfileJson,
                    executionProfile, createdAt, updatedAt
                ) VALUES (
                    901, 'Legacy project', '/project', 0, 1,
                    'IDLE', '', 'LOCAL_SANDBOX', '{}', 'CONSOLE', '{}',
                    'optimized', 10, 10
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO agent_messages (
                    originalId, conversationId, role, content,
                    isTerminalVisible, needsApproval, isPlan, isStreaming,
                    isDelegation, isSuspicious, isOutputExpanded,
                    timestamp, sequenceNumber
                ) VALUES (
                    'migration-message', 901, 'user', 'keep this request',
                    0, 0, 0, 0, 0, 0, 0, 11, 1
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            MIGRATION_DB,
            119,
            true,
            Migrations.MIGRATION_118_119
        )

        migrated.query(
            "SELECT executionProfile, directRuntimeVersion, directReanchorState, directReanchorReason " +
                "FROM agent_conversations WHERE id = 901"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(AgentExecutionProfile.DIRECT, cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(AgentDirectReanchorState.PENDING, cursor.getString(2))
            assertEquals("direct_runtime_migration_118", cursor.getString(3))
        }
        migrated.query(
            "SELECT content FROM agent_messages WHERE originalId = 'migration-message'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("keep this request", cursor.getString(0))
        }
        migrated.close()
    }

    @Test
    fun reanchorConversationToDirect_isAtomicPreservingDurableProjectStateAndIdempotent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val conversationId = database.agentChatDao().insertConversation(
                AgentConversationEntity(
                    title = "Direct migration",
                    executionProfile = AgentExecutionProfile.LEGACY
                )
            )
            database.agentChatDao().insertMessage(
                AgentMessageEntity(
                    originalId = "preserve-message",
                    conversationId = conversationId,
                    role = "user",
                    content = "preserve this chat",
                    needsApproval = true,
                    isApproved = null,
                    sequenceNumber = 1
                )
            )

            val plan = AgentPlanVersionEntity(
                id = "plan-direct-1",
                conversationId = conversationId,
                versionNumber = 1,
                summary = "Build the project",
                planMarkdown = "1. Build it",
                structuredJson = "{\"todos\":[\"todo-pending\"]}",
                planHash = "plan-hash-direct-1"
            )
            database.agentWorkflowDao().upsertPlanVersion(plan)
            database.agentWorkflowDao().upsertTodos(
                listOf(
                    AgentTodoEntity(
                        id = "todo-complete",
                        conversationId = conversationId,
                        text = "Already done",
                        status = "COMPLETED",
                        position = 0,
                        planVersionId = plan.id,
                        completedAt = 20
                    ),
                    AgentTodoEntity(
                        id = "todo-pending",
                        conversationId = conversationId,
                        text = "Continue here",
                        status = "IN_PROGRESS",
                        position = 1,
                        planVersionId = plan.id,
                        assignedInvocationId = "invocation-stale"
                    )
                )
            )
            database.agentWorkflowDao().insertProjectStateIfMissing(
                AgentProjectStateEntity(
                    conversationId = conversationId,
                    mode = AgentDirectRuntime.MODE_BUILD,
                    activePlanVersionId = plan.id,
                    currentTodoId = "invocation-stale"
                )
            )
            database.agentWorkflowDao().insertDecision(
                AgentDecisionEntity(
                    id = "decision-preserved",
                    conversationId = conversationId,
                    decisionKey = "language",
                    answerJson = "{\"value\":\"Kotlin\"}"
                )
            )
            database.agentWorkflowDao().upsertPendingQuestion(
                AgentPendingQuestionEntity(
                    id = "question-stale",
                    conversationId = conversationId,
                    rootTurnId = "root-stale",
                    agentSessionId = "session-stale",
                    toolCallId = "question-call",
                    specificationJson = "{}"
                )
            )
            database.agentWorkflowDao().upsertPendingPlan(
                AgentPendingPlanEntity(
                    id = "pending-plan-stale",
                    conversationId = conversationId,
                    rootTurnId = "root-stale",
                    agentSessionId = "session-stale",
                    planMessageId = "plan-message-stale",
                    toolCallId = "plan-call",
                    originalPlan = "old plan",
                    summary = "old plan"
                )
            )
            database.agentWorkflowDao().insertInvocation(
                AgentInvocationEntity(
                    id = "invocation-stale",
                    conversationId = conversationId,
                    rootTurnId = "root-stale",
                    runtimeEpoch = 1,
                    parentToolCallId = "delegate-call",
                    agentClass = "CODER",
                    agentKey = "CODER",
                    requestedName = "coder",
                    baseNameKey = "coder",
                    occurrence = 1,
                    resolvedName = "coder",
                    resolvedNameKey = "coder",
                    task = "old delegated task"
                )
            )
            database.agentWorkflowDao().insertPendingInput(
                AgentPendingInputEntity(
                    id = "input-stale",
                    conversationId = conversationId,
                    content = "old steering",
                    sequenceNumber = 1
                )
            )
            database.agentWorkflowDao().insertContinuationReceipt(
                AgentContinuationOutboxEntity(
                    id = "continuation-stale",
                    conversationId = conversationId,
                    kind = "TOOL_CONTINUATION",
                    dedupeKey = "stale-continuation"
                )
            )
            database.agentWorkflowDao().upsertSleepWake(
                AgentSleepWakeEntity(
                    id = "wake-stale",
                    conversationId = conversationId,
                    runEpoch = 1,
                    wakeAtEpochMs = 100
                )
            )

            val first = database.agentWorkflowDao().reanchorConversationToDirect(
                conversationId = conversationId,
                reason = "migration test"
            )
            assertTrue(first.applied)
            assertEquals(AgentDirectRuntime.MODE_BUILD, first.phase)
            assertEquals("todo-pending", first.currentTodoId)
            assertEquals(1, first.cancelledContinuations)
            assertEquals(1, first.cancelledQuestions)
            assertEquals(1, first.cancelledPlans)
            assertEquals(1, first.cancelledApprovals)
            assertEquals(1, first.archivedInvocations)

            val conversation = database.agentChatDao().getConversation(conversationId)
            assertNotNull(conversation)
            assertEquals(AgentExecutionProfile.DIRECT, conversation?.executionProfile)
            assertEquals(AgentDirectRuntime.CURRENT_VERSION, conversation?.directRuntimeVersion)
            assertEquals(AgentDirectReanchorState.COMPLETE, conversation?.directReanchorState)
            assertEquals("migration test", conversation?.directReanchorReason)
            assertFalse(conversation?.planningModeEnabled ?: true)

            assertEquals("preserve this chat", database.agentChatDao()
                .getMessageByOriginalId("preserve-message")?.content)
            assertNotNull(database.agentWorkflowDao().getPlanVersionById(plan.id))
            assertNotNull(database.agentWorkflowDao().getDecision("decision-preserved"))
            assertEquals("COMPLETED", database.agentWorkflowDao().getTodoById("todo-complete")?.status)
            assertEquals("READY", database.agentWorkflowDao().getTodoById("todo-pending")?.status)
            assertNull(database.agentWorkflowDao().getTodoById("todo-pending")?.assignedInvocationId)
            assertEquals("ARCHIVED", database.agentWorkflowDao().getInvocation("invocation-stale")?.status)
            assertEquals("CANCELLED", database.agentWorkflowDao().getPendingQuestion("question-stale")?.status)
            assertEquals("CANCELLED", database.agentWorkflowDao().getPendingPlanById("pending-plan-stale")?.state)
            assertEquals("CANCELLED", database.agentWorkflowDao().getPendingInput("input-stale")?.status)
            assertEquals("CANCELLED", database.agentWorkflowDao().getContinuationReceiptById("continuation-stale")?.status)
            assertEquals(AgentSleepWakeStatus.CANCELLED, database.agentWorkflowDao().getSleepWake("wake-stale")?.status)

            val second = database.agentWorkflowDao().reanchorConversationToDirect(
                conversationId = conversationId,
                reason = "must not run twice"
            )
            assertFalse(second.applied)
            assertEquals(0, second.cancelledContinuations)
            assertEquals(0, second.cancelledQuestions)
            assertEquals(0, second.cancelledPlans)
            assertEquals(0, second.cancelledApprovals)
            assertEquals(0, second.archivedInvocations)
            assertEquals("migration test", database.agentChatDao()
                .getConversation(conversationId)?.directReanchorReason)
        } finally {
            database.close()
        }
    }

    @Test
    fun directFinishReceipt_completesCurrentStepAndAdvancesWithoutEndingProject() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val conversationId = database.agentChatDao().insertConversation(
                AgentConversationEntity(title = "Direct step advancement")
            )
            val plan = AgentPlanVersionEntity(
                id = "plan-direct-steps",
                conversationId = conversationId,
                versionNumber = 1,
                summary = "Build two artifacts",
                planMarkdown = "- Write index.html\n- Write app.js",
                structuredJson = "{}",
                planHash = "plan-direct-steps-hash",
                status = "APPROVED"
            )
            database.agentWorkflowDao().upsertPlanVersion(plan)
            database.agentWorkflowDao().upsertTodos(
                listOf(
                    AgentTodoEntity(
                        id = "todo-direct-step-1",
                        conversationId = conversationId,
                        text = "Write index.html",
                        status = "IN_PROGRESS",
                        position = 0,
                        planVersionId = plan.id
                    ),
                    AgentTodoEntity(
                        id = "todo-direct-step-2",
                        conversationId = conversationId,
                        text = "Write app.js",
                        status = "PENDING",
                        position = 1,
                        planVersionId = plan.id,
                        dependenciesJson = "[\"todo-direct-step-1\"]"
                    )
                )
            )
            database.agentWorkflowDao().insertProjectStateIfMissing(
                AgentProjectStateEntity(
                    conversationId = conversationId,
                    mode = AgentDirectRuntime.MODE_BUILD,
                    activePlanVersionId = plan.id,
                    currentTodoId = "todo-direct-step-1"
                )
            )

            val transition = AgentProjectControlPlane.applyDirectToolReceipt(
                database = database,
                conversationId = conversationId,
                toolName = "finish_task",
                actionId = "finish-step-1",
                successful = true,
                completionStatus = "SUCCESS"
            )

            assertTrue(transition.changed)
            assertEquals("COMPLETED", database.agentWorkflowDao()
                .getTodoById("todo-direct-step-1")?.status)
            assertEquals("READY", database.agentWorkflowDao()
                .getTodoById("todo-direct-step-2")?.status)
            val state = database.agentWorkflowDao().getProjectState(conversationId)
            assertEquals(AgentDirectRuntime.MODE_BUILD, state?.mode)
            assertEquals("todo-direct-step-2", state?.currentTodoId)
        } finally {
            database.close()
        }
    }

    companion object {
        private const val MIGRATION_DB = "direct-runtime-migration-test"
    }
}
