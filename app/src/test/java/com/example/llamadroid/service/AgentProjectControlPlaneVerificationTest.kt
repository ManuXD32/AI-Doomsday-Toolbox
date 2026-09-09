package com.example.llamadroid.service

import androidx.room.Room
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentContinuationOutboxEntity
import com.example.llamadroid.data.db.AgentContinuationStatus
import com.example.llamadroid.data.db.AgentPlanVersionEntity
import com.example.llamadroid.data.db.AgentProjectStateEntity
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentProjectControlPlaneVerificationTest {
    private lateinit var database: AppDatabase
    private var conversationId: Long = 0L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        conversationId = runBlocking {
            database.agentChatDao().insertConversation(
                AgentConversationEntity(title = "Verification project")
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `classifier requires concrete passing evidence`() {
        val web = AgentProjectControlPlane.classifyVerificationCheck(
            toolName = "check_project_run",
            rawResult = "status: RUNNING\nruntime: web\npreview_url: http://127.0.0.1:8080/"
        )
        val python = AgentProjectControlPlane.classifyVerificationCheck(
            toolName = "check_project_run",
            rawResult = "status: STOPPED\nruntime: python\nexit_code: 0"
        )
        val command = AgentProjectControlPlane.classifyVerificationCheck(
            toolName = "check_command",
            rawResult = "Status: finished (exit code: 0)"
        )
        val running = AgentProjectControlPlane.classifyVerificationCheck(
            toolName = "check_command",
            rawResult = "Status: running"
        )
        val failed = AgentProjectControlPlane.classifyVerificationCheck(
            toolName = "check_project_run",
            rawResult = "status: FAILED\nexit_code: 1"
        )
        val unrelated = AgentProjectControlPlane.classifyVerificationCheck(
            toolName = "read_file",
            rawResult = "status: success"
        )
        val runningCommand = AgentProjectControlPlane.classifyVerificationCheck(
            toolName = "run_command",
            rawResult = "Status: finished (exit code: 0)"
        )
        val preview = AgentProjectControlPlane.classifyVerificationCheck(
            toolName = "observe_preview",
            rawResult = """
                {
                  "url":"http://127.0.0.1:8080/",
                  "load_progress":100,
                  "title":"Error Demo",
                  "screenshot_path":"/tmp/preview.png",
                  "screenshot_bytes":42
                }
            """.trimIndent()
        )
        val structuredPreviewFailure = AgentProjectControlPlane.classifyVerificationCheck(
            toolName = "observe_preview",
            rawResult = """
                {
                  "url":"http://127.0.0.1:8080/",
                  "load_progress":100,
                  "title":"Error Demo",
                  "error":"webview unavailable"
                }
            """.trimIndent()
        )
        val urlOnlyPreview = AgentProjectControlPlane.classifyVerificationCheck(
            toolName = "observe_preview",
            rawResult = """
                {
                  "url":"http://127.0.0.1:8080/",
                  "load_progress":100
                }
            """.trimIndent()
        )

        assertEquals(AgentVerificationDisposition.PENDING, web.disposition)
        assertEquals(AgentVerificationDisposition.PASS, python.disposition)
        assertEquals(AgentVerificationDisposition.PASS, command.disposition)
        assertEquals(AgentVerificationDisposition.PENDING, running.disposition)
        assertEquals(AgentVerificationDisposition.FAIL, failed.disposition)
        assertEquals(
            AgentVerificationDisposition.NOT_APPLICABLE,
            unrelated.disposition
        )
        assertEquals(
            AgentVerificationDisposition.NOT_APPLICABLE,
            runningCommand.disposition
        )
        assertEquals(AgentVerificationDisposition.PASS, preview.disposition)
        assertEquals(AgentVerificationDisposition.FAIL, structuredPreviewFailure.disposition)
        assertEquals(AgentVerificationDisposition.PENDING, urlOnlyPreview.disposition)
    }

    @Test
    fun `passing check enters verify and replay keeps revision and state stable`() = runBlocking {
        seedProject(mode = AgentProjectControlPlane.PROJECT_MODE_BUILD)

        val first = AgentProjectControlPlane.applyVerificationResult(
            database = database,
            conversationId = conversationId,
            toolName = "observe_preview",
            actionId = "run-1",
            planningEpisodeId = "plan-1",
            rawResult = "{\"url\":\"http://127.0.0.1:8080/\",\"load_progress\":100,\"screenshot_path\":\"/tmp/preview.png\"}"
        )
        assertEquals(AgentVerificationTransition.ENTERED_VERIFY, first.transition)
        assertEquals(AgentProjectControlPlane.PROJECT_MODE_VERIFY, first.state.mode)
        assertEquals("plan-1", first.state.activePlanVersionId)
        assertEquals("phase-1", first.state.currentPhaseId)
        assertEquals("todo-1", first.state.currentTodoId)
        database.agentWorkflowDao().bumpProjectStateRevision(
            conversationId = conversationId,
            semanticEvent = "unrelated_event"
        )
        val beforeReplay = database.agentWorkflowDao().getProjectState(conversationId)
            ?: error("missing replay state")
        val second = AgentProjectControlPlane.applyVerificationResult(
            database = database,
            conversationId = conversationId,
            toolName = "observe_preview",
            actionId = "run-1",
            planningEpisodeId = "plan-1",
            rawResult = "{\"url\":\"http://127.0.0.1:8080/\",\"load_progress\":100,\"screenshot_path\":\"/tmp/preview.png\"}"
        )
        assertEquals(AgentVerificationTransition.ALREADY_APPLIED, second.transition)
        assertEquals(beforeReplay.revision, second.state.revision)
        assertEquals(first.state.mode, second.state.mode)
        assertTrue(second.eventId == first.eventId)
    }

    @Test
    fun `failed check returns verify to build without changing approved plan or todo`() = runBlocking {
        seedProject(mode = AgentProjectControlPlane.PROJECT_MODE_VERIFY)

        val result = AgentProjectControlPlane.applyVerificationResult(
            database = database,
            conversationId = conversationId,
            toolName = "check_project_run",
            actionId = "run-failed",
            planningEpisodeId = "plan-1",
            rawResult = "status: FAILED\nruntime: python\nexit_code: 1"
        )

        assertEquals(AgentVerificationTransition.RETURNED_TO_BUILD, result.transition)
        assertEquals(AgentProjectControlPlane.PROJECT_MODE_BUILD, result.state.mode)
        assertEquals("plan-1", result.state.activePlanVersionId)
        assertEquals("phase-1", result.state.currentPhaseId)
        assertEquals("todo-1", result.state.currentTodoId)
        assertEquals("verification_failed", result.state.lastSemanticEvent?.substringBefore(':'))
        assertTrue(result.state.revision > 0L)
    }

    @Test
    fun `explicit build repair returns verify to build and replays idempotently`() = runBlocking {
        seedProject(mode = AgentProjectControlPlane.PROJECT_MODE_VERIFY)

        val first = AgentProjectControlPlane.applyRepairProgress(
            database = database,
            conversationId = conversationId,
            phase = "build",
            actionId = "progress-1",
            planningEpisodeId = "plan-1",
            summary = "Fix the mobile preview button spacing and rerun the check."
        )
        val afterFirst = database.agentWorkflowDao().getProjectState(conversationId)
            ?: error("missing repair state")
        val second = AgentProjectControlPlane.applyRepairProgress(
            database = database,
            conversationId = conversationId,
            phase = "build",
            actionId = "progress-1",
            planningEpisodeId = "plan-1",
            summary = "Fix the mobile preview button spacing and rerun the check."
        )

        assertEquals(AgentVerificationTransition.RETURNED_TO_BUILD, first.transition)
        assertEquals(AgentProjectControlPlane.PROJECT_MODE_BUILD, first.state.mode)
        assertEquals("plan-1", first.state.activePlanVersionId)
        assertEquals("phase-1", first.state.currentPhaseId)
        assertEquals("todo-1", first.state.currentTodoId)
        assertEquals(AgentVerificationTransition.ALREADY_APPLIED, second.transition)
        assertEquals(afterFirst.revision, second.state.revision)
        assertTrue(first.reason.contains("mobile preview button spacing"))
    }

    @Test
    fun `check without approved active plan cannot change phase`() = runBlocking {
        seedProject(
            mode = AgentProjectControlPlane.PROJECT_MODE_BUILD,
            planStatus = "DRAFT"
        )
        val before = database.agentWorkflowDao().getProjectState(conversationId)
            ?: error("missing test state")

        val result = AgentProjectControlPlane.applyVerificationResult(
            database = database,
            conversationId = conversationId,
            toolName = "check_project_run",
            actionId = "run-no-approval",
            rawResult = "status: STOPPED\nruntime: python\nexit_code: 0"
        )

        assertEquals(
            AgentVerificationTransition.REJECTED_NO_APPROVED_PLAN,
            result.transition
        )
        assertEquals(before.mode, result.state.mode)
        assertEquals(before.revision, result.state.revision)
        assertFalse(result.state.lastSemanticEvent?.startsWith("verification_") == true)
    }

    @Test
    fun `completion evidence requires a fresh pass after the latest mutation`() = runBlocking {
        seedProject(mode = AgentProjectControlPlane.PROJECT_MODE_VERIFY)
        val dao = database.agentWorkflowDao()
        dao.insertContinuationReceipt(
            AgentContinuationOutboxEntity(
                id = "mutation-1",
                conversationId = conversationId,
                kind = "ACTION_RECEIPT",
                dedupeKey = "mutation-1",
                payloadJson = JSONObject()
                    .put("receipt_type", "action")
                    .put("planning_episode_id", "plan-1")
                    .put("tool", "write_file")
                    .put("action_id", "write-1")
                    .put("status", "SUCCESS")
                    .toString(),
                status = AgentContinuationStatus.COMPLETED,
                createdAt = 10L,
                updatedAt = 10L
            )
        )
        dao.insertContinuationReceipt(
            AgentContinuationOutboxEntity(
                id = "verification-1",
                conversationId = conversationId,
                kind = "VERIFICATION_PHASE_TRANSITION",
                dedupeKey = "verification-1",
                payloadJson = JSONObject()
                    .put("receipt_type", "verification_phase")
                    .put("planning_episode_id", "plan-1")
                    .put("plan_version_id", "plan-1")
                    .put("disposition", "PASS")
                    .toString(),
                status = AgentContinuationStatus.COMPLETED,
                createdAt = 9L,
                updatedAt = 9L
            )
        )

        val stale = AgentProjectControlPlane.readCompletionEvidence(
            database = database,
            conversationId = conversationId,
            planVersionId = "plan-1"
        )
        assertFalse(stale.hasFreshPassAfterLatestMutation)

        dao.insertContinuationReceipt(
            AgentContinuationOutboxEntity(
                id = "verification-2",
                conversationId = conversationId,
                kind = "VERIFICATION_PHASE_TRANSITION",
                dedupeKey = "verification-2",
                payloadJson = JSONObject()
                    .put("receipt_type", "verification_phase")
                    .put("planning_episode_id", "plan-1")
                    .put("plan_version_id", "plan-1")
                    .put("disposition", "PASS")
                    .toString(),
                status = AgentContinuationStatus.COMPLETED,
                createdAt = 11L,
                updatedAt = 11L
            )
        )
        val fresh = AgentProjectControlPlane.readCompletionEvidence(
            database = database,
            conversationId = conversationId,
            planVersionId = "plan-1"
        )
        assertTrue(fresh.hasFreshPassAfterLatestMutation)

        dao.insertContinuationReceipt(
            AgentContinuationOutboxEntity(
                id = "mutation-failed-after-pass",
                conversationId = conversationId,
                kind = "ACTION_RECEIPT",
                dedupeKey = "mutation-failed-after-pass",
                payloadJson = JSONObject()
                    .put("receipt_type", "action")
                    .put("planning_episode_id", "plan-1")
                    .put("tool", "run_command")
                    .put("action_id", "command-2")
                    .put("status", "ERROR")
                    .toString(),
                status = AgentContinuationStatus.FAILED,
                createdAt = 12L,
                updatedAt = 12L
            )
        )
        val invalidated = AgentProjectControlPlane.readCompletionEvidence(
            database = database,
            conversationId = conversationId,
            planVersionId = "plan-1"
        )
        assertFalse(invalidated.hasFreshPassAfterLatestMutation)

        repeat(64) { index ->
            dao.insertContinuationReceipt(
                AgentContinuationOutboxEntity(
                    id = "read-after-pass-$index",
                    conversationId = conversationId,
                    kind = "ACTION_RECEIPT",
                    dedupeKey = "read-after-pass-$index",
                    payloadJson = JSONObject()
                        .put("receipt_type", "action")
                        .put("planning_episode_id", "plan-1")
                        .put("tool", "read_file")
                        .put("action_id", "read-$index")
                        .put("status", "SUCCESS")
                        .toString(),
                    status = AgentContinuationStatus.COMPLETED,
                    createdAt = 100L + index,
                    updatedAt = 100L + index
                )
            )
        }
        val staleWithOverflow = AgentProjectControlPlane.readCompletionEvidence(
            database = database,
            conversationId = conversationId,
            planVersionId = "plan-1"
        )
        assertFalse(staleWithOverflow.hasFreshPassAfterLatestMutation)

        dao.insertContinuationReceipt(
            AgentContinuationOutboxEntity(
                id = "verification-after-overflow",
                conversationId = conversationId,
                kind = "VERIFICATION_PHASE_TRANSITION",
                dedupeKey = "verification-after-overflow",
                payloadJson = JSONObject()
                    .put("receipt_type", "verification_phase")
                    .put("planning_episode_id", "plan-1")
                    .put("plan_version_id", "plan-1")
                    .put("disposition", "PASS")
                    .toString(),
                status = AgentContinuationStatus.COMPLETED,
                createdAt = 200L,
                updatedAt = 200L
            )
        )
        val recovered = AgentProjectControlPlane.readCompletionEvidence(
            database = database,
            conversationId = conversationId,
            planVersionId = "plan-1"
        )
        assertTrue(recovered.hasFreshPassAfterLatestMutation)
    }

    @Test
    fun `hydrating build mode does not overwrite durable verify`() {
        assertTrue(
            AgentProjectControlPlane.preservesVerifyDuringHydration(
                AgentProjectControlPlane.PROJECT_MODE_VERIFY,
                AgentProjectControlPlane.PROJECT_MODE_BUILD
            )
        )
        assertFalse(
            AgentProjectControlPlane.preservesVerifyDuringHydration(
                AgentProjectControlPlane.PROJECT_MODE_BUILD,
                AgentProjectControlPlane.PROJECT_MODE_BUILD
            )
        )
        assertFalse(
            AgentProjectControlPlane.preservesVerifyDuringHydration(
                AgentProjectControlPlane.PROJECT_MODE_VERIFY,
                AgentProjectControlPlane.PROJECT_MODE_PLAN
            )
        )
    }

    private suspend fun seedProject(
        mode: String,
        planStatus: String = "APPROVED"
    ) {
        val dao = database.agentWorkflowDao()
        dao.upsertPlanVersion(
            AgentPlanVersionEntity(
                id = "plan-1",
                conversationId = conversationId,
                versionNumber = 1,
                summary = "Test plan",
                planMarkdown = "- Run and verify",
                structuredJson = "{}",
                planHash = "test-plan-hash",
                status = planStatus
            )
        )
        dao.insertProjectStateIfMissing(
            AgentProjectStateEntity(
                conversationId = conversationId,
                mode = mode,
                activePlanVersionId = "plan-1",
                currentPhaseId = "phase-1",
                currentTodoId = "todo-1"
            )
        )
    }
}
