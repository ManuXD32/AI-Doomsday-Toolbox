package com.example.llamadroid.service

import androidx.room.Room
import com.example.llamadroid.data.db.AgentContinuationOutboxEntity
import com.example.llamadroid.data.db.AgentContinuationStatus
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentMessageEntity
import com.example.llamadroid.data.db.AgentPendingInputEntity
import com.example.llamadroid.data.db.AgentPendingQuestionEntity
import com.example.llamadroid.data.db.AgentTurnContextEntity
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class AgentDurableContractStoreTest {
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
                AgentConversationEntity(title = "Harness project")
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `initial goal is write once and decisions retain correction provenance`() = runBlocking {
        val first = AgentDurableContractStore.ensureContract(
            database,
            conversationId,
            initialGoal = "Build the prime-number WebUI"
        )
        val second = AgentDurableContractStore.ensureContract(
            database,
            conversationId,
            initialGoal = "A renderer may replace this"
        )
        assertEquals("Build the prime-number WebUI", first.initialGoal)
        assertEquals(first.initialGoal, second.initialGoal)

        val original = AgentDurableContractStore.recordDecision(
            database = database,
            conversationId = conversationId,
            decisionKey = "range",
            answerJson = "{\"selected\":\"small\"}",
            submittedAnswerJson = "{\"custom\":\"exact\"}",
            selectedOptionsJson = "[\"small\"]",
            customAnswer = "exact",
            provenanceJson = "{\"source\":\"user\"}"
        )
        val correction = AgentDurableContractStore.recordDecision(
            database = database,
            conversationId = conversationId,
            decisionKey = "range",
            answerJson = "{\"selected\":\"large\"}",
            submittedAnswerJson = "{\"custom\":\"exact correction\"}",
            selectedOptionsJson = "[\"large\"]",
            customAnswer = "exact correction",
            provenanceJson = "{\"source\":\"user_correction\"}"
        )
        val history = database.agentWorkflowDao().getDecisionsForKey(conversationId, "range")
        assertEquals(2, history.size)
        assertFalse(history.first { it.id == original.id }.isLatest)
        assertEquals(correction.id, history.first { it.id == original.id }.latestCorrectionId)
        assertEquals(original.id, correction.supersedesDecisionId)
        assertEquals("{\"custom\":\"exact correction\"}", correction.submittedAnswerJson)
        assertEquals("exact correction", correction.customAnswer)
    }

    @Test
    fun `user corrections use independent stable keys and retain every exact value`() = runBlocking {
        val first = AgentDurableContractStore.recordUserCorrection(
            database = database,
            conversationId = conversationId,
            messageId = "message-one",
            content = "Keep intervals near 10^12 and use bounded memory.",
            createdAt = 100L
        )
        val second = AgentDurableContractStore.recordUserCorrection(
            database = database,
            conversationId = conversationId,
            messageId = "message-two",
            content = "Run independent parallel jobs and keep the original CSV requirement.",
            createdAt = 200L,
            provenanceJson = "{\"private\":\"large provenance omitted from projection\"}"
        )
        val retry = AgentDurableContractStore.recordUserCorrection(
            database = database,
            conversationId = conversationId,
            messageId = "message-one",
            content = "A retry must not replace the first correction.",
            createdAt = 300L
        )

        assertTrue(first.decisionKey != second.decisionKey)
        assertEquals(
            first.decisionKey,
            AgentDurableContractStore.userCorrectionDecisionKey("message-one")
        )
        assertEquals(
            first.id,
            AgentDurableContractStore.userCorrectionDecisionId("message-one")
        )
        assertEquals(first.id, retry.id)
        assertEquals(first.customAnswer, retry.customAnswer)
        val latest = database.agentWorkflowDao().getAllLatestDecisions(conversationId)
        val projection = AgentDurableContractStore.projectUserCorrections(latest)
        assertEquals(
            listOf(first.id, second.id),
            projection.values.map { it.decisionId }
        )
        assertEquals(
            listOf(first.customAnswer, second.customAnswer),
            projection.values.map { it.content }
        )
        assertEquals(second.id, projection.latestDecisionId)
        assertEquals(second.id, AgentDurableContractStore.latestUserCorrectionDecisionId(latest))
    }

    @Test
    fun `legacy correction chain is repaired in place without dropping earlier requirements`() = runBlocking {
        val first = AgentDurableContractStore.recordDecision(
            database = database,
            conversationId = conversationId,
            decisionKey = AgentDurableContractStore.LEGACY_USER_CORRECTION_DECISION_KEY,
            decisionId = "legacy-correction-one",
            answerJson = "{\"message_id\":\"legacy-message-one\",\"content\":\"near 10^12\"}",
            customAnswer = "near 10^12",
            provenanceJson = "{\"message_id\":\"legacy-message-one\",\"debug\":\"retain\"}",
            createdAt = 1L
        )
        val second = AgentDurableContractStore.recordDecision(
            database = database,
            conversationId = conversationId,
            decisionKey = AgentDurableContractStore.LEGACY_USER_CORRECTION_DECISION_KEY,
            decisionId = "legacy-correction-two",
            answerJson = "{\"message_id\":\"legacy-message-two\",\"content\":\"bounded segments\"}",
            customAnswer = "bounded segments",
            provenanceJson = "{\"message_id\":\"legacy-message-two\"}",
            createdAt = 2L
        )
        val historyBeforeMigration = database.agentWorkflowDao().getDecisionsForKey(
            conversationId,
            AgentDurableContractStore.LEGACY_USER_CORRECTION_DECISION_KEY
        )
        // The returned entities are immutable snapshots; verify the
        // supersession on the durable rows after the second insert.
        assertFalse(historyBeforeMigration.first { it.id == first.id }.isLatest)
        assertTrue(historyBeforeMigration.first { it.id == second.id }.isLatest)

        val migration = AgentDurableContractStore.migrateLegacyUserCorrections(
            database,
            conversationId
        )
        assertEquals(2, migration.migratedCount)
        assertEquals(0, migration.collisionCount)
        assertEquals(
            0,
            database.agentWorkflowDao().getDecisionsForKey(
                conversationId,
                AgentDurableContractStore.LEGACY_USER_CORRECTION_DECISION_KEY
            ).size
        )
        val repaired = database.agentWorkflowDao().getAllLatestDecisions(conversationId)
            .filter(AgentDurableContractStore::isUserCorrectionDecision)
        assertEquals(setOf(first.id, second.id), repaired.map { it.id }.toSet())
        assertTrue(repaired.all { it.isLatest })
        assertTrue(repaired.all { it.supersedesDecisionId == null })
        assertEquals(
            setOf("near 10^12", "bounded segments"),
            AgentDurableContractStore.projectUserCorrections(repaired)
                .values.map { it.content }.toSet()
        )

        val replay = AgentDurableContractStore.migrateLegacyUserCorrections(
            database,
            conversationId
        )
        assertEquals(0, replay.migratedCount)
    }

    @Test
    fun `legacy repair leaves explicit structured decision supersession intact`() = runBlocking {
        val original = AgentDurableContractStore.recordDecision(
            database = database,
            conversationId = conversationId,
            decisionKey = AgentDurableContractStore.LEGACY_USER_CORRECTION_DECISION_KEY,
            questionId = "structured-question",
            decisionId = "structured-original",
            answerJson = "{\"selected\":\"small\"}",
            selectedOptionsJson = "[\"small\"]"
        )
        val correction = AgentDurableContractStore.recordDecision(
            database = database,
            conversationId = conversationId,
            decisionKey = AgentDurableContractStore.LEGACY_USER_CORRECTION_DECISION_KEY,
            questionId = "structured-question",
            decisionId = "structured-correction",
            answerJson = "{\"selected\":\"large\"}",
            selectedOptionsJson = "[\"large\"]"
        )

        val migration = AgentDurableContractStore.migrateLegacyUserCorrections(
            database,
            conversationId
        )
        assertEquals(0, migration.migratedCount)
        val history = database.agentWorkflowDao().getDecisionsForKey(
            conversationId,
            AgentDurableContractStore.LEGACY_USER_CORRECTION_DECISION_KEY
        )
        val storedOriginal = history.first { it.id == original.id }
        val storedCorrection = history.first { it.id == correction.id }
        assertFalse(storedOriginal.isLatest)
        assertEquals(original.id, storedCorrection.supersedesDecisionId)
        assertEquals(correction.id, storedOriginal.latestCorrectionId)
    }

    @Test
    fun `answer transaction repairs canonical message after enqueue flag is already set`() = runBlocking {
        val questionId = "question-1"
        database.agentWorkflowDao().upsertPendingQuestion(
            AgentPendingQuestionEntity(
                id = questionId,
                conversationId = conversationId,
                rootTurnId = "root-1",
                agentSessionId = "session-1",
                toolCallId = "tool-1",
                specificationJson = questionSpecJson()
            )
        )
        val submitted = """
            {"answers":{"range":{"selected":["large"],"custom":"exact custom"}}}
        """.trimIndent()
        val committed = AgentDurableContractStore.answerQuestionAtomically(
            database = database,
            questionId = questionId,
            submittedAnswerJson = submitted,
            provenanceJson = "{\"source\":\"answer_panel\"}"
        )
        assertEquals("ANSWERED", committed.question.status)
        assertTrue(committed.question.continuationEnqueued)
        assertEquals("tool", committed.message.role)
        assertEquals(1, committed.decisions.size)
        assertEquals("exact custom", committed.decisions.single().customAnswer)
        assertEquals(AgentContinuationStatus.QUEUED, committed.receipt.status)

        database.agentChatDao().deleteMessage(committed.message)
        val repaired = AgentDurableContractStore.restoreAnsweredQuestionMessages(
            database,
            conversationId
        )
        assertEquals(1, repaired.size)
        assertEquals(committed.message.originalId, repaired.single().originalId)
        assertNotNull(database.agentChatDao().getMessageByOriginalId(committed.message.originalId))
    }

    @Test
    fun `tool replay resolves only canonical result rows within the conversation`() = runBlocking {
        val toolName = "run_project"
        val toolCallId = "call-replay"
        database.agentChatDao().insertMessage(
            AgentMessageEntity(
                originalId = "assistant-tool-request",
                conversationId = conversationId,
                role = "assistant",
                content = "",
                toolName = toolName,
                toolCallId = toolCallId,
                sequenceNumber = 1
            )
        )
        assertEquals(
            null,
            database.agentChatDao().getToolMessage(conversationId, toolName, toolCallId)
        )

        val otherConversationId = database.agentChatDao().insertConversation(
            AgentConversationEntity(title = "Other replay project")
        )
        database.agentChatDao().insertMessage(
            AgentMessageEntity(
                originalId = "other-tool-result",
                conversationId = otherConversationId,
                role = "tool",
                content = "status: success",
                toolName = toolName,
                toolCallId = toolCallId,
                sequenceNumber = 1
            )
        )
        assertEquals(
            null,
            database.agentChatDao().getToolMessage(conversationId, toolName, toolCallId)
        )

        database.agentChatDao().insertMessage(
            AgentMessageEntity(
                originalId = "canonical-tool-result",
                conversationId = conversationId,
                role = "tool",
                content = "status: success",
                toolName = toolName,
                toolCallId = toolCallId,
                sequenceNumber = 2
            )
        )
        assertEquals(
            "canonical-tool-result",
            database.agentChatDao().getToolMessage(conversationId, toolName, toolCallId)?.originalId
        )
    }

    @Test
    fun `stop fence cancels pending work and unresolved tool approvals only`() = runBlocking {
        database.agentWorkflowDao().upsertPendingQuestion(
            AgentPendingQuestionEntity(
                id = "question-stop",
                conversationId = conversationId,
                rootTurnId = "root-stop",
                agentSessionId = "session-stop",
                toolCallId = "tool-stop",
                specificationJson = questionSpecJson()
            )
        )
        database.agentWorkflowDao().insertPendingInput(
            AgentPendingInputEntity(
                id = "input-stop",
                conversationId = conversationId,
                content = "queued guidance",
                sequenceNumber = 1
            )
        )
        database.agentWorkflowDao().insertContinuationReceipt(
            AgentContinuationOutboxEntity(
                id = "receipt-stop",
                conversationId = conversationId,
                kind = "TEST",
                dedupeKey = "test:stop"
            )
        )
        database.agentChatDao().insertMessages(
            listOf(
                AgentMessageEntity(
                    originalId = "pending-tool-approval",
                    conversationId = conversationId,
                    role = "assistant",
                    content = "pending approval",
                    toolName = "run_command",
                    toolCallId = "pending-call",
                    needsApproval = true,
                    isApproved = null,
                    sequenceNumber = 1
                ),
                AgentMessageEntity(
                    originalId = "approved-tool-approval",
                    conversationId = conversationId,
                    role = "assistant",
                    content = "approved approval",
                    toolName = "run_command",
                    toolCallId = "approved-call",
                    needsApproval = true,
                    isApproved = true,
                    sequenceNumber = 2
                ),
                AgentMessageEntity(
                    originalId = "denied-tool-approval",
                    conversationId = conversationId,
                    role = "assistant",
                    content = "denied approval",
                    toolName = "run_command",
                    toolCallId = "denied-call",
                    needsApproval = true,
                    isApproved = false,
                    sequenceNumber = 3
                )
            )
        )
        val result = AgentDurableContractStore.cancelConversationWork(
            database,
            conversationId,
            reason = "user stop"
        )
        assertEquals(1, result.cancelledQuestions)
        assertEquals(1, result.cancelledInputs)
        assertEquals(1, result.cancelledContinuations)
        assertEquals(
            "CANCELLED",
            database.agentWorkflowDao().getPendingQuestion("question-stop")?.status
        )
        assertEquals(
            "CANCELLED",
            database.agentWorkflowDao().getContinuationReceiptById("receipt-stop")?.status
        )
        val pendingApproval = requireNotNull(
            database.agentChatDao().getMessageByOriginalId("pending-tool-approval")
        )
        assertFalse(pendingApproval.needsApproval)
        assertEquals(false, pendingApproval.isApproved)
        val approvedApproval = requireNotNull(
            database.agentChatDao().getMessageByOriginalId("approved-tool-approval")
        )
        assertTrue(approvedApproval.needsApproval)
        assertEquals(true, approvedApproval.isApproved)
        val deniedApproval = requireNotNull(
            database.agentChatDao().getMessageByOriginalId("denied-tool-approval")
        )
        assertTrue(deniedApproval.needsApproval)
        assertEquals(false, deniedApproval.isApproved)
    }

    @Test
    fun `cold recovery DAO sees an enqueued tool continuation and closes the turn boundary`() = runBlocking {
        val rootTurnId = "cold-root"
        database.agentWorkflowDao().upsertTurnContext(
            AgentTurnContextEntity(
                rootTurnId = rootTurnId,
                conversationId = conversationId,
                agentKey = "ORCHESTRATOR",
                backend = "test",
                modelLabel = "test",
                endpointGeneration = "test",
                contextTokens = 1,
                configuredOutputTokens = 1,
                effectiveOutputTokens = 1,
                systemPromptHash = "system",
                toolDefinitionsHash = "tools",
                stablePrefixHash = "prefix",
                parametersHash = "parameters",
                messageCount = 0,
                messagesHash = "messages",
                messageStartSequence = 0,
                createdAt = 100L
            )
        )
        database.agentWorkflowDao().insertContinuationReceipt(
            AgentContinuationOutboxEntity(
                id = "cold-continuation",
                conversationId = conversationId,
                rootTurnId = rootTurnId,
                kind = "TOOL_CONTINUATION",
                dedupeKey = "tool:cold",
                status = AgentContinuationStatus.ENQUEUED,
                enqueuedAt = 120L,
                createdAt = 110L,
                updatedAt = 120L
            )
        )

        assertNotNull(database.agentWorkflowDao().getLatestOpenTurnContext(conversationId))
        assertEquals(1, database.agentWorkflowDao().countPendingToolContinuations(conversationId))
        assertEquals(
            120L,
            requireNotNull(database.agentWorkflowDao().getLatestPendingToolContinuation(conversationId)).enqueuedAt
        )
        assertEquals(
            1,
            database.agentChatDao().updateResumeStateIfIdle(
                id = conversationId,
                resumeState = "INTERRUPTED",
                reason = "cold recovery"
            )
        )
        // A second recovery scan must not overwrite a state another owner has published.
        assertEquals(
            0,
            database.agentChatDao().updateResumeStateIfIdle(
                id = conversationId,
                resumeState = "INTERRUPTED",
                reason = "duplicate cold recovery"
            )
        )

        database.agentWorkflowDao().finishTurnContext(
            rootTurnId = rootTurnId,
            status = "COMPLETED",
            completedAt = 130L
        )
        assertEquals(null, database.agentWorkflowDao().getLatestOpenTurnContext(conversationId))
        // The outbox remains pending until its worker crosses the continuation boundary.
        assertEquals(1, database.agentWorkflowDao().countPendingToolContinuations(conversationId))
    }

    @Test
    fun `stop cancels enqueued continuations while preserving completed action receipts`() = runBlocking {
        database.agentWorkflowDao().insertContinuationReceipt(
            AgentContinuationOutboxEntity(
                id = "stop-enqueued-continuation",
                conversationId = conversationId,
                kind = "TOOL_CONTINUATION",
                dedupeKey = "tool:stop-enqueued",
                status = AgentContinuationStatus.ENQUEUED,
                createdAt = 100L,
                updatedAt = 100L
            )
        )
        database.agentWorkflowDao().insertContinuationReceipt(
            AgentContinuationOutboxEntity(
                id = "stop-completed-action",
                conversationId = conversationId,
                kind = "ACTION_RECEIPT",
                dedupeKey = "action:stop-completed",
                payloadJson = actionReceiptPayload(
                    tool = "write_file",
                    actionId = "stop-action",
                    status = "SUCCESS",
                    metadataJson = AgentDurableContractStore.mutationReceiptMetadata(
                        "write_file",
                        mapOf("path" to "index.html", "content" to "opaque-content"),
                        success = true
                    )
                ),
                status = AgentContinuationStatus.COMPLETED,
                createdAt = 101L,
                updatedAt = 101L
            )
        )

        assertEquals(1, database.agentWorkflowDao().cancelConversationContinuations(conversationId, 200L))
        assertEquals(
            AgentContinuationStatus.CANCELLED,
            database.agentWorkflowDao().getContinuationReceipt(conversationId, "tool:stop-enqueued")?.status
        )
        assertEquals(
            AgentContinuationStatus.COMPLETED,
            database.agentWorkflowDao().getContinuationReceipt(conversationId, "action:stop-completed")?.status
        )
    }

    @Test
    fun `consumed tool continuation closes its enqueued durable handoff`() = runBlocking {
        val receipt = AgentContinuationOutboxEntity(
            id = "tool-continuation:consumed",
            conversationId = conversationId,
            rootTurnId = "root-consumed",
            kind = "TOOL_CONTINUATION",
            dedupeKey = "tool:consumed",
            status = AgentContinuationStatus.ENQUEUED,
            enqueuedAt = 100L,
            createdAt = 90L,
            updatedAt = 100L
        )
        database.agentWorkflowDao().insertContinuationReceipt(receipt)

        val completed = AgentDurableContractStore.completeContinuation(
            database = database,
            receiptId = receipt.id,
            status = AgentContinuationStatus.COMPLETED
        )

        assertEquals(AgentContinuationStatus.COMPLETED, completed?.status)
        assertNotNull(completed?.completedAt)
        assertEquals(0, database.agentWorkflowDao().countPendingToolContinuations(conversationId))
    }

    @Test
    fun `mutation receipt metadata keeps exact paths and omits file content`() {
        val write = AgentDurableContractStore.mutationReceiptMetadata(
            tool = "write_file",
            args = mapOf("path" to "index.html", "content" to "DO NOT PERSIST THIS"),
            success = true
        )
        val writeEntry = JSONObject(write).getJSONArray("entries").getJSONObject(0)
        assertEquals("index.html", writeEntry.getString("path"))
        assertEquals("write", writeEntry.getString("operation"))
        assertFalse(write.contains("DO NOT PERSIST THIS"))

        val exactEdit = AgentDurableContractStore.mutationReceiptMetadata(
            tool = "edit_file",
            args = mapOf(
                "path" to "src/app.js",
                "old_text" to "PRIVATE OLD CONTENT",
                "new_text" to "PRIVATE NEW CONTENT"
            ),
            success = true
        )
        val editEntry = JSONObject(exactEdit).getJSONArray("entries").getJSONObject(0)
        assertEquals("src/app.js", editEntry.getString("path"))
        assertEquals("edit", editEntry.getString("operation"))
        assertFalse(exactEdit.contains("PRIVATE OLD CONTENT"))
        assertFalse(exactEdit.contains("PRIVATE NEW CONTENT"))

        val partialWrite = AgentDurableContractStore.mutationReceiptMetadata(
            tool = "write_file",
            args = mapOf("path" to "src/large.js", "content" to "const ready = true; // DIRECT-EXTEND"),
            success = true
        )
        assertEquals(
            "partial",
            JSONObject(partialWrite).getJSONArray("entries").getJSONObject(0).getString("operation")
        )
        val partialEdit = AgentDurableContractStore.mutationReceiptMetadata(
            tool = "edit_file",
            args = mapOf("path" to "src/large.js", "old_text" to "DIRECT-EXTEND", "new_text" to "more(); // DIRECT-EXTEND"),
            success = true
        )
        assertEquals(
            "partial",
            JSONObject(partialEdit).getJSONArray("entries").getJSONObject(0).getString("operation")
        )

        val patch = AgentDurableContractStore.mutationReceiptMetadata(
            tool = "apply_patch",
            args = mapOf(
                "patch" to """
                    *** Begin Patch
                    *** Update File: app.js
                    @@
                    -old
                    +new
                    *** Delete File: old.css
                    *** End Patch
                """.trimIndent()
            ),
            success = true
        )
        val patchEntries = JSONObject(patch).getJSONArray("entries")
        assertEquals(2, patchEntries.length())
        assertEquals("app.js", patchEntries.getJSONObject(0).getString("path"))
        assertEquals("patch", patchEntries.getJSONObject(0).getString("operation"))
        assertEquals("old.css", patchEntries.getJSONObject(1).getString("path"))
        assertEquals("delete", patchEntries.getJSONObject(1).getString("operation"))

        val unifiedDelete = AgentDurableContractStore.mutationReceiptMetadata(
            tool = "apply_patch",
            args = mapOf("patch" to "--- a/legacy.js\n+++ /dev/null\n@@\n-old\n"),
            success = true
        )
        val deleteEntry = JSONObject(unifiedDelete).getJSONArray("entries").getJSONObject(0)
        assertEquals("legacy.js", deleteEntry.getString("path"))
        assertEquals("delete", deleteEntry.getString("operation"))
        assertEquals(
            "{}",
            AgentDurableContractStore.mutationReceiptMetadata(
                "write_file",
                mapOf("path" to "index.html", "content" to "ignored"),
                success = false
            )
        )
    }

    @Test
    fun `committed artifact ledger keeps newest path and ignores failures reads and other conversations`() = runBlocking {
        insertActionReceipt(
            id = "artifact-old-index",
            actionId = "action-old-index",
            tool = "write_file",
            metadataJson = AgentDurableContractStore.mutationReceiptMetadata(
                "write_file", mapOf("path" to "index.html", "content" to "old"), true
            ),
            createdAt = 100L
        )
        insertActionReceipt(
            id = "artifact-style",
            actionId = "action-style",
            tool = "edit_file",
            metadataJson = AgentDurableContractStore.mutationReceiptMetadata(
                "edit_file",
                mapOf(
                    "path" to "styles.css",
                    "old_text" to "old",
                    "new_text" to "new"
                ),
                true
            ),
            createdAt = 110L
        )
        insertActionReceipt(
            id = "artifact-new-index",
            actionId = "action-new-index",
            tool = "write_file",
            metadataJson = AgentDurableContractStore.mutationReceiptMetadata(
                "write_file", mapOf("path" to "index.html", "content" to "new"), true
            ),
            createdAt = 120L
        )
        insertActionReceipt(
            id = "artifact-failed",
            actionId = "action-failed",
            tool = "write_file",
            metadataJson = AgentDurableContractStore.mutationReceiptMetadata(
                "write_file", mapOf("path" to "failed.js", "content" to "never"), true
            ),
            createdAt = 130L,
            payloadStatus = "ERROR",
            entityStatus = AgentContinuationStatus.FAILED
        )
        insertActionReceipt(
            id = "artifact-read",
            actionId = "action-read",
            tool = "read_file",
            metadataJson = "{}",
            createdAt = 140L
        )
        val otherConversationId = database.agentChatDao().insertConversation(
            AgentConversationEntity(title = "Other artifact project")
        )
        insertActionReceipt(
            id = "artifact-other-project",
            actionId = "action-other",
            tool = "write_file",
            metadataJson = AgentDurableContractStore.mutationReceiptMetadata(
                "write_file", mapOf("path" to "other.html", "content" to "other"), true
            ),
            createdAt = 150L,
            targetConversationId = otherConversationId
        )

        val ledger = AgentDurableContractStore.readCommittedArtifactLedger(database, conversationId)
        assertEquals(setOf("index.html", "styles.css"), ledger.entries.map { it.path }.toSet())
        assertEquals("action-new-index", ledger.entries.first { it.path == "index.html" }.actionId)
        assertEquals("write", ledger.entries.first { it.path == "index.html" }.operation)
        assertEquals("edit", ledger.entries.first { it.path == "styles.css" }.operation)
        assertFalse(ledger.incomplete)
        assertEquals(0, ledger.omittedCount)
        assertTrue(ledger.render().contains("action-new-index"))
        assertTrue(ledger.render().contains("validation still required"))
    }

    @Test
    fun `artifact ledger marks missing metadata and receipt overflow explicitly`() = runBlocking {
        insertActionReceipt(
            id = "artifact-missing-metadata",
            actionId = "action-missing",
            tool = "write_file",
            metadataJson = "{}",
            createdAt = 100L
        )
        val missing = AgentDurableContractStore.readCommittedArtifactLedger(database, conversationId)
        assertTrue(missing.incomplete)
        assertTrue(missing.omittedCount >= 1)

        insertActionReceipt(
            id = "artifact-overflow-one",
            actionId = "action-overflow-one",
            tool = "write_file",
            metadataJson = AgentDurableContractStore.mutationReceiptMetadata(
                "write_file", mapOf("path" to "one.js"), true
            ),
            createdAt = 200L
        )
        insertActionReceipt(
            id = "artifact-overflow-two",
            actionId = "action-overflow-two",
            tool = "write_file",
            metadataJson = AgentDurableContractStore.mutationReceiptMetadata(
                "write_file", mapOf("path" to "two.js"), true
            ),
            createdAt = 210L
        )
        insertActionReceipt(
            id = "artifact-overflow-three",
            actionId = "action-overflow-three",
            tool = "write_file",
            metadataJson = AgentDurableContractStore.mutationReceiptMetadata(
                "write_file", mapOf("path" to "three.js"), true
            ),
            createdAt = 220L
        )
        val overflow = AgentDurableContractStore.readCommittedArtifactLedger(
            database = database,
            conversationId = conversationId,
            maxReceipts = 2,
            maxPaths = 8
        )
        assertEquals(2, overflow.entries.size)
        assertTrue(overflow.incomplete)
        assertTrue(overflow.omittedCount >= 1)
        assertTrue(overflow.render().contains("ledger_incomplete: true"))
    }

    @Test
    fun `idle completion cannot erase a pause regardless of write order`() = runBlocking {
        val dao = database.agentChatDao()
        for (state in listOf("NEEDS_DIRECTION", "INTERRUPTED", "WAITING_FOR_USER", "STOPPED_BY_USER")) {
            dao.updateResumeState(conversationId, "IDLE", null)
            assertEquals(1, dao.updateResumeStateIfIdle(conversationId, "IDLE", null))
            dao.updateResumeState(conversationId, state, "retained reason")
            assertEquals(0, dao.updateResumeStateIfIdle(conversationId, "IDLE", null))
            val saved = requireNotNull(dao.getConversation(conversationId))
            assertEquals(state, saved.resumeState)
            assertEquals("retained reason", saved.lastStopReason)
        }
    }

    @Test
    fun `queued input commits exact correction and positive contract directives`() = runBlocking {
        val rawUserGuidance = """
            Keep the exact JSON value {"selected":["large"],"custom":"10^12"}.
            No more optional questions. This is a greenfield project.
        """.trimIndent()
        val userInput = AgentPendingInputEntity(
            id = "queued-user-1",
            conversationId = conversationId,
            content = rawUserGuidance,
            sequenceNumber = 10L
        )

        val userResult = AgentDurableContractStore.enqueuePendingInputAtomically(
            database = database,
            input = userInput,
            rootTurnId = "root-queued"
        )
        val userCorrection = requireNotNull(userResult.correction)
        assertTrue(userResult.inserted)
        assertEquals(rawUserGuidance, userResult.input.content)
        assertEquals(rawUserGuidance, userCorrection.submittedAnswerJson)
        assertEquals(rawUserGuidance, userCorrection.customAnswer)
        assertEquals(rawUserGuidance, JSONObject(userCorrection.answerJson).getString("content"))
        assertTrue(userCorrection.provenanceJson.contains("queued_user_guidance"))

        val modeGuidance = "Apply the approved plan exactly; no more questions."
        val modeResult = AgentDurableContractStore.enqueuePendingInputAtomically(
            database = database,
            input = AgentPendingInputEntity(
                id = "queued-plan-1",
                conversationId = conversationId,
                kind = "MODE_PLAN",
                content = modeGuidance,
                sequenceNumber = 11L
            ),
            rootTurnId = "root-queued"
        )
        assertEquals(modeGuidance, requireNotNull(modeResult.correction).customAnswer)
        val contract = requireNotNull(database.agentWorkflowDao().getProjectContract(conversationId))
        assertTrue(contract.noMoreQuestions)
        assertTrue(contract.greenfield)
    }

    @Test
    fun `queued input retry and cancelled replay never replace durable first value`() = runBlocking {
        val firstContent = "Keep the first exact correction."
        val first = AgentDurableContractStore.enqueuePendingInputAtomically(
            database = database,
            input = AgentPendingInputEntity(
                id = "queued-retry-1",
                conversationId = conversationId,
                content = firstContent,
                sequenceNumber = 20L
            )
        )
        val retry = AgentDurableContractStore.enqueuePendingInputAtomically(
            database = database,
            input = AgentPendingInputEntity(
                id = "queued-retry-1",
                conversationId = conversationId,
                content = "A changed retry must be ignored.",
                sequenceNumber = 21L
            )
        )
        assertTrue(first.inserted)
        assertFalse(retry.inserted)
        assertEquals(firstContent, retry.input.content)
        assertEquals(first.correction?.id, retry.correction?.id)

        AgentDurableContractStore.cancelConversationWork(
            database = database,
            conversationId = conversationId,
            reason = "test stop"
        )
        val late = AgentDurableContractStore.enqueuePendingInputAtomically(
            database = database,
            input = AgentPendingInputEntity(
                id = "queued-retry-1",
                conversationId = conversationId,
                content = "A late writer must not revive the row.",
                sequenceNumber = 22L
            )
        )
        assertFalse(late.inserted)
        assertEquals("CANCELLED", late.input.status)
        assertEquals(firstContent, late.input.content)
        assertEquals(
            1,
            database.agentWorkflowDao().getDecisionsForKey(
                conversationId,
                AgentDurableContractStore.userCorrectionDecisionKey("queued-retry-1")
            ).size
        )
    }

    @Test
    fun `legacy queued correction repair is stable and keeps no more questions`() = runBlocking {
        val rawContent = "Legacy exact guidance. No more optional questions."
        AgentDurableContractStore.markNoMoreQuestions(database, conversationId, true)
        database.agentWorkflowDao().insertPendingInput(
            AgentPendingInputEntity(
                id = "legacy-queued-user-1",
                conversationId = conversationId,
                content = rawContent,
                sequenceNumber = 30L
            )
        )
        val legacyInput = AgentPendingInputEntity(
            id = "legacy-queued-user-1",
            conversationId = conversationId,
            content = rawContent,
            sequenceNumber = 30L
        )
        val first = AgentDurableContractStore.enqueuePendingInputAtomically(
            database = database,
            input = legacyInput
        )
        val replay = AgentDurableContractStore.enqueuePendingInputAtomically(
            database = database,
            input = legacyInput.copy(content = "Changed replay must not replace legacy content.")
        )
        assertEquals(first.correction?.id, replay.correction?.id)
        assertEquals(rawContent, replay.correction?.customAnswer)
        assertEquals(
            1,
            database.agentWorkflowDao().getDecisionsForKey(
                conversationId,
                AgentDurableContractStore.userCorrectionDecisionKey("legacy-queued-user-1")
            ).size
        )
        val contract = requireNotNull(database.agentWorkflowDao().getProjectContract(conversationId))
        assertTrue(contract.noMoreQuestions)
    }

    @Test
    fun `report outcomes carry explicit error semantics`() {
        val blocked = AgentDurableContractStore.normalizeAgentReportOutcome(
            status = "blocked",
            rawSummary = "Needs a user choice"
        )
        assertEquals("BLOCKED", blocked.status)
        assertEquals("AgentBlocked", blocked.errorClass)
        assertTrue(blocked.requiresUserDirection)

        val success = AgentDurableContractStore.normalizeAgentReportOutcome(
            status = "success",
            rawSummary = "done"
        )
        assertEquals("SUCCESS", success.status)
        assertEquals(null, success.errorClass)
        assertFalse(success.requiresUserDirection)
    }

    @Test
    fun `action receipts and research admissions survive retries and enforce episode limits`() = runBlocking {
        val receipt = AgentDurableContractStore.recordActionReceipt(
            database = database,
            conversationId = conversationId,
            planningEpisodeId = "episode/one",
            tool = "web_search",
            actionId = "call-1",
            status = "SUCCESS",
            rootTurnId = "root-1",
            evidenceFingerprint = "evidence-1",
            metadataJson = "{\"query\":\"prime numbers\"}"
        )
        val retry = AgentDurableContractStore.recordActionReceipt(
            database = database,
            conversationId = conversationId,
            planningEpisodeId = "episode/one",
            tool = "web_search",
            actionId = "call-1",
            status = "FAILED",
            metadataJson = "{\"query\":\"changed\"}"
        )
        assertEquals(receipt.id, retry.id)
        assertTrue(retry.payloadJson.contains("evidence-1"))

        repeat(2) {
            assertTrue(
                AgentDurableContractStore.admitResearch(
                    database,
                    conversationId,
                    planningEpisodeId = "episode/one",
                    tool = "web_search"
                ).admitted
            )
        }
        val deniedSearch = AgentDurableContractStore.admitResearch(
            database,
            conversationId,
            planningEpisodeId = "episode/one",
            tool = "web_search"
        )
        assertFalse(deniedSearch.admitted)
        assertEquals(2, deniedSearch.used)
        assertTrue(deniedSearch.reason.orEmpty().contains("RESEARCH_BUDGET_EXHAUSTED"))

        repeat(4) {
            assertTrue(
                AgentDurableContractStore.admitResearch(
                    database,
                    conversationId,
                    planningEpisodeId = "episode/one",
                    tool = "fetch_url"
                ).admitted
            )
        }
        val override = AgentDurableContractStore.admitResearch(
            database,
            conversationId,
            planningEpisodeId = "episode/one",
            tool = "fetch_url",
            specificBlockerReason = "The approved plan depends on this primary source."
        )
        assertTrue(override.admitted)
        assertEquals(5, override.used)
        assertEquals("explicit_blocker_override", override.reason)

        val budget = AgentDurableContractStore.readResearchBudget(
            database = database,
            conversationId = conversationId,
            planningEpisodeId = "episode/one"
        )
        assertEquals(2, budget.searchUsed)
        assertEquals(0, budget.searchRemaining)
        assertEquals(5, budget.fetchUsed)
        assertEquals(0, budget.fetchRemaining)
        assertTrue(budget.exhausted)
    }

    @Test
    fun `canonical early outcome is idempotent and repairs its durable receipt`() = runBlocking {
        val first = AgentDurableContractStore.recordCanonicalToolOutcomeAtomically(
            database = database,
            conversationId = conversationId,
            stableOutcomeId = "validation-call-1",
            toolName = "question",
            toolCallId = "call-1",
            content = "status: error\ntool: question\nsummary: invalid question",
            toolOutput = "validation failure",
            rootTurnId = "root-1",
            continuation = ContinuationReceiptSpec(
                id = "repair-receipt-1",
                conversationId = conversationId,
                rootTurnId = "root-1",
                kind = "TOOL_REPAIR",
                dedupeKey = "tool-repair:validation-call-1",
                payloadJson = "{\"repair\":true}"
            )
        )
        val replay = AgentDurableContractStore.recordCanonicalToolOutcomeAtomically(
            database = database,
            conversationId = conversationId,
            stableOutcomeId = "validation-call-1",
            toolName = "question",
            toolCallId = "call-1",
            content = "status: error\ntool: question\nsummary: changed retry",
            toolOutput = "changed retry",
            rootTurnId = "root-2",
            continuation = ContinuationReceiptSpec(
                id = "repair-receipt-different",
                conversationId = conversationId,
                kind = "TOOL_REPAIR",
                dedupeKey = "tool-repair:validation-call-1"
            )
        )

        assertTrue(first.messageInserted)
        assertTrue(first.receiptInserted)
        assertEquals(first.message.originalId, replay.message.originalId)
        assertEquals(first.message.content, replay.message.content)
        assertEquals(first.receipt?.id, replay.receipt?.id)
        assertTrue(!replay.messageInserted)
        assertTrue(!replay.receiptInserted)
        assertEquals(1, database.agentChatDao().getMessagesForConversationSync(conversationId).size)
    }

    @Test
    fun `canonical outcome rejects an original id collision across conversations`() = runBlocking {
        val otherConversationId = database.agentChatDao().insertConversation(
            AgentConversationEntity(title = "Other harness project")
        )
        AgentDurableContractStore.recordCanonicalToolOutcomeAtomically(
            database = database,
            conversationId = conversationId,
            stableOutcomeId = "shared-outcome",
            toolName = "question",
            toolCallId = "call-shared",
            content = "status: error\ntool: question\nsummary: first project"
        )

        var rejected = false
        try {
            AgentDurableContractStore.recordCanonicalToolOutcomeAtomically(
                database = database,
                conversationId = otherConversationId,
                stableOutcomeId = "shared-outcome",
                toolName = "question",
                toolCallId = "call-shared",
                content = "status: error\ntool: question\nsummary: other project"
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun `research budget failure permits one durable repair then pauses repeats`() = runBlocking {
        fun repairSpec(id: String) = ContinuationReceiptSpec(
            id = id,
            conversationId = conversationId,
            rootTurnId = "root-budget",
            kind = "ignored-by-store",
            dedupeKey = "ignored-by-store",
            payloadJson = "{\"action\":\"propose_plan\"}"
        )

        val first = AgentDurableContractStore.recordResearchBudgetFailureAndRepairAtomically(
            database = database,
            conversationId = conversationId,
            planningEpisodeId = "episode-budget",
            toolName = "web_search",
            used = 2,
            limit = 2,
            stableOutcomeId = "budget-call-1",
            toolCallId = "call-1",
            content = "status: blocked\ntool: web_search\nsummary: budget exhausted",
            rootTurnId = "root-budget",
            repairContinuation = repairSpec("caller-repair-1")
        )
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM agent_continuation_outbox WHERE kind = ?",
            arrayOf("RESEARCH_BUDGET_REPAIR")
        )
        val exactRetry = AgentDurableContractStore.recordResearchBudgetFailureAndRepairAtomically(
            database = database,
            conversationId = conversationId,
            planningEpisodeId = "episode-budget",
            toolName = "web_search",
            used = 2,
            limit = 2,
            stableOutcomeId = "budget-call-1",
            toolCallId = "call-1",
            content = "status: blocked\ntool: web_search\nsummary: changed retry",
            rootTurnId = "root-budget",
            repairContinuation = repairSpec("caller-repair-retry")
        )
        val repeated = AgentDurableContractStore.recordResearchBudgetFailureAndRepairAtomically(
            database = database,
            conversationId = conversationId,
            planningEpisodeId = "episode-budget",
            toolName = "kiwix_search",
            used = 2,
            limit = 2,
            stableOutcomeId = "budget-call-2",
            toolCallId = "call-2",
            content = "status: blocked\ntool: kiwix_search\nsummary: budget exhausted",
            rootTurnId = "root-budget",
            repairContinuation = repairSpec("caller-repair-2")
        )
        val repeatedReplay = AgentDurableContractStore.recordResearchBudgetFailureAndRepairAtomically(
            database = database,
            conversationId = conversationId,
            planningEpisodeId = "episode-budget",
            toolName = "kiwix_search",
            used = 2,
            limit = 2,
            stableOutcomeId = "budget-call-2",
            toolCallId = "call-2",
            content = "status: blocked\ntool: kiwix_search\nsummary: changed retry",
            rootTurnId = "root-budget",
            repairContinuation = repairSpec("caller-repair-repeated-retry")
        )

        assertTrue(first.automaticRepairAllowed)
        assertTrue(!first.repeatedFailure)
        assertEquals("RESEARCH_BUDGET_REPAIR", first.repairReceipt?.kind)
        assertEquals(AgentContinuationStatus.QUEUED, first.repairReceipt?.status)
        assertTrue(exactRetry.automaticRepairAllowed)
        assertTrue(!exactRetry.repeatedFailure)
        assertTrue(!exactRetry.outcome.messageInserted)
        assertTrue(exactRetry.outcome.receiptInserted)
        assertEquals(first.outcome.message.originalId, exactRetry.outcome.message.originalId)
        assertEquals(first.outcome.message.content, exactRetry.outcome.message.content)
        assertEquals(first.outcome.receipt?.id, exactRetry.outcome.receipt?.id)
        assertTrue(repeated.repeatedFailure)
        assertTrue(!repeated.automaticRepairAllowed)
        assertEquals(null, repeated.repairReceipt)
        assertTrue(repeatedReplay.repeatedFailure)
        assertTrue(!repeatedReplay.automaticRepairAllowed)
        assertEquals(null, repeatedReplay.repairReceipt)
        assertEquals(
            1,
            database.agentWorkflowDao().countContinuationReceipts(
                conversationId,
                "RESEARCH_BUDGET_FAILURE",
                "%"
            )
        )
        assertEquals(
            1,
            database.agentWorkflowDao().countContinuationReceipts(
                conversationId,
                "RESEARCH_BUDGET_REPAIR",
                "%"
            )
        )
    }

    private suspend fun insertActionReceipt(
        id: String,
        actionId: String,
        tool: String,
        metadataJson: String,
        createdAt: Long,
        payloadStatus: String = "SUCCESS",
        entityStatus: String = AgentContinuationStatus.COMPLETED,
        targetConversationId: Long = conversationId
    ) {
        database.agentWorkflowDao().insertContinuationReceipt(
            AgentContinuationOutboxEntity(
                id = id,
                conversationId = targetConversationId,
                kind = "ACTION_RECEIPT",
                dedupeKey = "action:$id",
                payloadJson = actionReceiptPayload(tool, actionId, payloadStatus, metadataJson),
                status = entityStatus,
                createdAt = createdAt,
                updatedAt = createdAt
            )
        )
    }

    private fun actionReceiptPayload(
        tool: String,
        actionId: String,
        status: String,
        metadataJson: String
    ): String = JSONObject()
        .put("receipt_type", "action")
        .put("planning_episode_id", "test-episode")
        .put("tool", tool)
        .put("action_id", actionId)
        .put("status", status)
        .put("metadata_json", metadataJson)
        .toString()

    private fun questionSpecJson(): String = """
        {
          "questions":[{
            "id":"range",
            "header":"Range",
            "prompt":"Which range should be supported?",
            "multiple":false,
            "allow_custom":true,
            "options":[
              {"id":"small","label":"Small"},
              {"id":"large","label":"Large"}
            ]
          }]
        }
    """.trimIndent()
}
