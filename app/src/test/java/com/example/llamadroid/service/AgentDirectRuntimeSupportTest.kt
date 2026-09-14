package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDirectRuntimeSupportTest {
    @Test
    fun `exact edit recovery removes only read view line prefixes`() {
        assertEquals(
            "const value = 7;\nDIRECT-EXTEND",
            directExactEditCandidateWithoutReadLineNumbers(
                "    41  const value = 7;\n    42  DIRECT-EXTEND"
            )
        )
        assertEquals(
            "DIRECT-EXTEND",
            directExactEditCandidateWithoutReadLineNumbers("    47  DIRECT-EXTEND")
        )
        assertEquals(null, directExactEditCandidateWithoutReadLineNumbers("DIRECT-EXTEND"))
        assertEquals(
            null,
            directExactEditCandidateWithoutReadLineNumbers(
                "    41  const value = 7;\nplain source line"
            )
        )
    }

    @Test
    fun `status projection reads the authoritative capsule next action`() {
        val capsule = """
            # Project Control Packet — Direct Control Capsule
            - direct_runtime_version: 1
            - exact_next_action: call finish_task now with committed artifact evidence
            - authority: this tail capsule overrides conflicting historical prose
        """.trimIndent()

        assertEquals(
            "call finish_task now with committed artifact evidence",
            directCapsuleNextAction(capsule)
        )
        assertEquals(null, directCapsuleNextAction("# no action"))
    }

    @Test
    fun `coordinator projects every durable wait state without a role lane`() {
        assertEquals(
            DirectAgentState.AWAITING_PLAN_APPROVAL,
            AgentDirectRuntimeCoordinator.resolveState(
                projectMode = "PLAN",
                pendingPlanApproval = true,
                pendingQuestion = false,
                pendingToolApproval = false,
                paused = false,
                complete = false
            )
        )
        assertEquals(
            DirectAgentState.AWAITING_TOOL_APPROVAL,
            AgentDirectRuntimeCoordinator.resolveState(
                projectMode = "BUILD",
                pendingPlanApproval = false,
                pendingQuestion = false,
                pendingToolApproval = true,
                paused = false,
                complete = false
            )
        )
        assertEquals(
            DirectAgentState.VERIFY,
            AgentDirectRuntimeCoordinator.resolveState(
                projectMode = "VERIFY",
                pendingPlanApproval = false,
                pendingQuestion = false,
                pendingToolApproval = false,
                paused = false,
                complete = false
            )
        )
    }

    @Test
    fun `slot ownership survives coordinator recreation and changes only for invalidation inputs`() {
        val identity = AgentDirectSlotIdentity(
            endpointGeneration = "endpoint-1",
            modelConfiguration = "spark-4b|16384|thinking=false",
            conversationId = "42",
            coreSchemaHash = "core-a",
            projectContextHash = "project-a"
        )
        val beforeProcessDeath = AgentDirectRuntimeCoordinator.slotOwner(identity)
        val afterProcessDeath = AgentDirectRuntimeCoordinator.slotOwner(identity)
        assertEquals(beforeProcessDeath, afterProcessDeath)
        assertEquals("direct", beforeProcessDeath.agentSessionId)
        assertNotEquals(
            beforeProcessDeath,
            AgentDirectRuntimeCoordinator.slotOwner(identity.copy(coreSchemaHash = "core-b"))
        )
        assertNotEquals(
            beforeProcessDeath,
            AgentDirectRuntimeCoordinator.slotOwner(identity.copy(projectContextHash = "project-b"))
        )
        assertEquals(null, AgentDirectRuntimeCoordinator.cacheInvalidationReason())
        assertEquals(
            "compaction",
            AgentDirectRuntimeCoordinator.cacheInvalidationReason(compacted = true)
        )
    }

    @Test
    fun `state machine has one approval boundary and no specialist state`() {
        assertEquals(
            DirectAgentState.AWAITING_PLAN_APPROVAL,
            advanceDirectAgentState(DirectAgentState.PLAN, DirectAgentSignal.PLAN_SUBMITTED)
        )
        assertEquals(
            DirectAgentState.BUILD,
            advanceDirectAgentState(
                DirectAgentState.AWAITING_PLAN_APPROVAL,
                DirectAgentSignal.PLAN_APPROVED
            )
        )
        assertEquals(
            DirectAgentState.VERIFY,
            advanceDirectAgentState(DirectAgentState.BUILD, DirectAgentSignal.VERIFY_REQUESTED)
        )
        assertEquals(
            DirectAgentState.VERIFY,
            advanceDirectAgentState(DirectAgentState.VERIFY, DirectAgentSignal.VERIFICATION_PASSED)
        )
        assertEquals(
            DirectAgentState.COMPLETE,
            advanceDirectAgentState(DirectAgentState.VERIFY, DirectAgentSignal.COMPLETE)
        )
        assertEquals(
            DirectAgentState.PAUSED,
            advanceDirectAgentState(DirectAgentState.BUILD, DirectAgentSignal.STOP)
        )
    }

    @Test
    fun `capsule retains every authoritative field and budgets without pruning`() {
        val capsule = DirectControlCapsule(
            taskContract = "Build the requested WebUI",
            corrections = listOf("Use a worker", "Keep download bounded"),
            approvedPlanReference = "plan-7",
            approvedPlanContent = "1. Write files 2. Run preview",
            currentStep = "Write index.html",
            artifacts = listOf("index.html", ".adt/run.json"),
            activeCommandOrRunHandle = "run-3",
            latestFailure = "none",
            nextAction = "write_file index.html",
            latestSteering = "Do not ask again"
        )

        val rendered = capsule.render()
        listOf(
            "Build the requested WebUI",
            "Use a worker",
            "plan-7",
            "Write index.html",
            "index.html",
            "run-3",
            "write_file index.html",
            "Do not ask again"
        ).forEach { assertTrue("missing $it", rendered.contains(it)) }
        assertTrue(capsule.inputTokens() > 0)
        assertTrue(capsule.fitsContext(reservedOutputTokens = 2_048))
    }

    @Test
    fun `successful receipt is replayed while mismatched receipt is rejected`() {
        val request = DirectActionRequest(
            actionId = "action-1",
            toolName = "write_file",
            arguments = mapOf("path" to "index.html", "content" to "hello")
        )
        val receipt = DirectActionReceipt(
            actionId = "action-1",
            toolName = "write_file",
            argumentsHash = request.argumentsHash,
            status = "SUCCESS",
            resultReference = "receipt://action-1"
        )
        assertEquals(DirectReceiptDecision.REPLAY_SUCCESS, decideDirectReceiptReplay(request, receipt))
        assertEquals(
            DirectReceiptDecision.REJECT_MISMATCH,
            decideDirectReceiptReplay(request.copy(actionId = "action-2"), receipt)
        )
        assertEquals(DirectReceiptDecision.EXECUTE, decideDirectReceiptReplay(request, null))
    }

    @Test
    fun `recovery permits one malformed repair then pauses on repeated failure`() {
        assertEquals(
            DirectRecoveryAction.RETRY_EXACT_MALFORMED_CALL,
            decideDirectRecovery(0, identicalFailureCount = 0, turnsWithoutSemanticProgress = 0)
        )
        assertEquals(
            DirectRecoveryAction.CONTINUE,
            decideDirectRecovery(1, identicalFailureCount = 0, turnsWithoutSemanticProgress = 1)
        )
        assertEquals(
            DirectRecoveryAction.PAUSE,
            decideDirectRecovery(1, identicalFailureCount = 2, turnsWithoutSemanticProgress = 0)
        )
        assertEquals(
            DirectRecoveryAction.PAUSE,
            decideDirectRecovery(1, identicalFailureCount = 0, turnsWithoutSemanticProgress = 3)
        )
    }

    @Test
    fun `stateful run failure exposes repair tools instead of requiring impossible retry`() {
        assertEquals(null, directStrictRecoveryToolForExecutionFailure("run_project"))
        assertEquals(null, directStrictRecoveryToolForExecutionFailure("observe_preview"))
        val instruction = directToolFailureRecoveryInstruction("run_project")
        assertTrue(instruction.contains("repairs the project configuration"))
        assertTrue(instruction.contains("Do not repeat run_project unchanged"))
    }

    @Test
    fun `invalid run manifest failure narrows recovery to one manifest replacement`() {
        val failure = "runtime must be python or web"
        assertEquals(
            "write_file",
            directStrictRecoveryToolForExecutionFailure("run_project", failure)
        )
        val instruction = directToolFailureRecoveryInstruction("run_project", failure)
        assertTrue(instruction.contains("exactly one write_file call"))
        assertTrue(instruction.contains(".adt/run.json"))
    }

    @Test
    fun `argument repair retains one exact tool recovery`() {
        assertEquals("write_file", directStrictRecoveryToolForExecutionFailure("write_file"))
        val instruction = directToolFailureRecoveryInstruction("write_file")
        assertTrue(instruction.contains("Correct that exact call once"))
        assertTrue(instruction.contains("Do not switch tools"))
    }

    @Test
    fun `stale exact edit recovery rereads instead of guessing the same match`() {
        listOf(
            "EXACT_EDIT_NO_MATCH: old_text was not found",
            "EXACT_EDIT_AMBIGUOUS: old_text occurs more than once"
        ).forEach { failure ->
            assertEquals(
                "read_file",
                directStrictRecoveryToolForExecutionFailure("edit_file", failure)
            )
            val instruction = directToolFailureRecoveryInstruction("edit_file", failure)
            assertTrue(instruction.contains("exactly one read_file"))
            assertTrue(instruction.contains("same target path"))
            assertTrue(instruction.contains("Do not guess old_text"))
        }
        assertEquals(
            "edit_file",
            directStrictRecoveryToolForExecutionFailure(
                "edit_file",
                "Temporary write transport failure"
            )
        )
        assertEquals(
            "edit_file failed: IllegalStateException: EXACT_EDIT_NO_MATCH",
            directFailureWithToolContext(
                "IllegalStateException: EXACT_EDIT_NO_MATCH",
                "edit_file"
            )
        )
        assertEquals(
            "edit_file failed: IllegalStateException: EXACT_EDIT_NO_MATCH",
            directFailureWithToolContext(
                "edit_file failed: IllegalStateException: EXACT_EDIT_NO_MATCH",
                "edit_file"
            )
        )
    }

    @Test
    fun `live failure outranks artifact complete capsule action until repair succeeds`() {
        val artifactComplete =
            "call finish_task now with the committed artifacts and evidence"
        val failure = "IllegalStateException: runtime must be python or web"

        val repairAction = directControlCapsuleNextAction(artifactComplete, failure)
        assertTrue(repairAction.startsWith("repair the latest failure"))
        assertTrue(repairAction.contains("runtime must be python or web"))
        assertFalse(repairAction.contains("finish_task"))
        assertEquals(
            artifactComplete,
            directControlCapsuleNextAction(artifactComplete, null)
        )
        assertEquals(
            "retry run_project now; do not finish first",
            directControlCapsuleNextAction(
                artifactComplete,
                "REPAIR_COMMITTED: retry run_project now; do not finish first"
            )
        )
    }

    @Test
    fun `read success does not erase failure but concrete repair receipt does`() {
        assertFalse(directSuccessfulToolClearsFailure("read_file"))
        assertFalse(directSuccessfulToolClearsFailure("list_directory"))
        assertTrue(directSuccessfulToolClearsFailure("write_file"))
        assertTrue(directSuccessfulToolClearsFailure("run_command"))
        assertTrue(directSuccessfulToolClearsFailure("run_project"))
        assertTrue(directConcreteRepairTool("write_file"))
        assertFalse(directConcreteRepairTool("run_project"))
    }

    @Test
    fun `run project approval follows manual auto mode instead of command auto accept`() {
        assertTrue(directRunProjectNeedsApproval(autoMode = false, isForced = false))
        assertFalse(directRunProjectNeedsApproval(autoMode = true, isForced = false))
        assertFalse(directRunProjectNeedsApproval(autoMode = false, isForced = true))
    }

    @Test
    fun `compaction triggers at eighty percent and cuts only after a complete result`() {
        val entries = listOf(
            DirectHistoryEntry(1, "user", 100),
            DirectHistoryEntry(2, "tool_call", 100),
            DirectHistoryEntry(3, "tool_result", 100, completeToolBoundary = true),
            DirectHistoryEntry(4, "assistant", 100),
            DirectHistoryEntry(5, "tool_call", 100),
            DirectHistoryEntry(6, "tool_result", 100, completeToolBoundary = true)
        )
        val noCompaction = planDirectCompaction(entries, measuredInputTokens = 799, availableInputTokens = 1_000)
        assertFalse(noCompaction.shouldCompact)

        val plan = planDirectCompaction(
            entries,
            measuredInputTokens = 800,
            availableInputTokens = 1_000,
            retainRecentTokens = 300
        )
        assertTrue(plan.shouldCompact)
        assertEquals(3, plan.cutBeforeIndex)
        assertEquals(3, plan.summarizedEntryCount)
        assertEquals(300, plan.retainedRecentTokenCount)
    }

    @Test
    fun `direct compaction boundary does not round a fractional capacity down`() {
        val entries = listOf(
            DirectHistoryEntry(1, "user", 100),
            DirectHistoryEntry(2, "tool_result", 100, completeToolBoundary = true),
            DirectHistoryEntry(3, "assistant", 100)
        )

        assertFalse(
            planDirectCompaction(
                entries = entries,
                measuredInputTokens = 799,
                availableInputTokens = 999,
                retainRecentTokens = 200
            ).shouldCompact
        )
        assertTrue(
            planDirectCompaction(
                entries = entries,
                measuredInputTokens = 800,
                availableInputTokens = 999,
                retainRecentTokens = 200
            ).shouldCompact
        )
    }

    @Test
    fun `epoch stop invalidates old continuations and output is bounded once`() {
        assertEquals(8L, nextDirectRunEpoch(7L))
        assertFalse(acceptsDirectEpoch(7L, 8L))
        assertTrue(acceptsDirectEpoch(8L, 8L))

        val output = "a".repeat(100)
        val bounded = boundDirectToolOutput(output, "content://full", maxCharacters = 40, tailCharacters = 8)
        assertEquals(100, bounded.originalCharacterCount)
        assertEquals("content://full", bounded.fullOutputReference)
        assertTrue(bounded.preview.length <= 40)
        assertNotEquals(output, bounded.preview)
    }

    @Test
    fun `exact edit changes one match and rejects missing or ambiguous text`() {
        assertEquals(
            "alpha BETA omega",
            applyDirectExactMatchEdit("alpha beta omega", "beta", "BETA").getOrThrow()
        )
        assertTrue(applyDirectExactMatchEdit("alpha", "missing", "x").isFailure)
        assertTrue(applyDirectExactMatchEdit("same same", "same", "x").isFailure)
        assertEquals("alpha omega", applyDirectExactMatchEdit("alpha beta omega", "beta ", "").getOrThrow())
    }

    @Test
    fun `output-limited raw write becomes one bounded staged prefix`() {
        val raw = """
            {"name":"write_file","arguments":{"path":"index.html","content":"<!doctype html>\n<html>\n<body>\n<script>\nconst title = \"Prime Lab\";\nfunction start() {\n  return true;\n
        """.trimIndent()

        val recovered = recoverDirectPartialMutation(raw)!!

        assertEquals("write_file", recovered.toolName)
        assertFalse(recovered.sourcePayloadComplete)
        assertEquals("index.html", recovered.arguments["path"])
        assertTrue(recovered.arguments.getValue("content").contains("const title = \"Prime Lab\";"))
        assertTrue(
            recovered.arguments.getValue("content")
                .endsWith("\n${AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR}")
        )
        assertTrue(
            recovered.arguments.getValue("content").toByteArray(Charsets.UTF_8).size <=
                AgentHarnessPolicy.DIRECT_WRITE_FILE_MAX_BYTES
        )
    }

    @Test
    fun `output-limited native write becomes one bounded staged prefix`() {
        val raw =
            "{\"path\":\"index.html\",\"content\":\"<!doctype html>\\n<html>\\n<body>\\n" +
                "<script>\\nconst title = \\\"Prime Lab\\\";\\nfunction start() {\\n  return true;\\n"

        val recovered = recoverDirectPartialNativeMutation("write_file", raw)!!

        assertEquals("write_file", recovered.toolName)
        assertFalse(recovered.sourcePayloadComplete)
        assertEquals("index.html", recovered.arguments["path"])
        assertTrue(recovered.arguments.getValue("content").contains("Prime Lab"))
        assertTrue(recovered.arguments.getValue("content").endsWith(AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR))
    }

    @Test
    fun `output-limited exact edit extends only the reserved anchor`() {
        val raw = """
            {"name":"edit_file","arguments":{"path":"app.js","old_text":"DIRECT-EXTEND","new_text":"function nextSegment() {\n  const values = [];\n  for (let i = 0; i < 100; i += 1) {\n    values.push(i);\n  }\n
        """.trimIndent()

        val recovered = recoverDirectPartialMutation(raw)!!

        assertEquals("edit_file", recovered.toolName)
        assertFalse(recovered.sourcePayloadComplete)
        assertEquals(AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR, recovered.arguments["old_text"])
        assertTrue(recovered.arguments.getValue("new_text").endsWith(AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR))
    }

    @Test
    fun `partial mutation recovery refuses prose small complete calls and broad edits`() {
        assertEquals(
            null,
            recoverDirectPartialMutation(
                "I suggest " +
                    "{\"name\":\"write_file\",\"arguments\":{\"path\":\"x.txt\",\"content\":\"hello\"}}"
            )
        )
        assertEquals(
            null,
            recoverDirectPartialMutation(
                "{\"name\":\"write_file\",\"arguments\":{\"path\":\"x.txt\",\"content\":\"hello\"}}"
            )
        )
        assertEquals(
            null,
            recoverDirectPartialMutation(
                "{\"name\":\"edit_file\",\"arguments\":{\"path\":\"x.txt\",\"old_text\":\"anything\",\"new_text\":\"replacement"
            )
        )
    }

    @Test
    fun `complete raw mutation above the schema bound is staged instead of retried`() {
        val oversized = "line\\n".repeat(1_200)
        val raw =
            "{\"name\":\"write_file\",\"arguments\":{\"path\":\"app.js\",\"content\":\"$oversized\"}}"

        val recovered = recoverDirectPartialMutation(raw)!!

        assertTrue(recovered.sourcePayloadComplete)
        assertTrue(
            recovered.arguments.getValue("content").toByteArray(Charsets.UTF_8).size <=
                AgentHarnessPolicy.DIRECT_WRITE_FILE_MAX_BYTES
        )
        assertTrue(recovered.arguments.getValue("content").endsWith(AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR))
    }

    @Test
    fun `oversized native write and anchor edit use the same staged protocol`() {
        val payload = "const value = 1;\n".repeat(500)
        val write = stageDirectOversizedMutation(
            toolName = "write_file",
            arguments = mapOf("path" to "worker.js", "content" to payload)
        )!!
        val edit = stageDirectOversizedMutation(
            toolName = "edit_file",
            arguments = mapOf(
                "path" to "worker.js",
                "old_text" to AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR,
                "new_text" to payload
            )
        )!!

        assertTrue(write.arguments.getValue("content").endsWith(AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR))
        assertTrue(edit.arguments.getValue("new_text").endsWith(AgentHarnessPolicy.DIRECT_EXTEND_ANCHOR))
        assertEquals(
            null,
            stageDirectOversizedMutation(
                toolName = "edit_file",
                arguments = mapOf("path" to "worker.js", "old_text" to "broad", "new_text" to payload)
            )
        )
    }

    @Test
    fun `committed Build receipts force finish instead of repeated inspection`() {
        listOf("read_file", "list_directory", "search_code").forEach { tool ->
            assertTrue(
                shouldBlockDirectInspectionAfterArtifactsCommitted(
                    phase = AgentHarnessPhase.BUILD,
                    exactNextAction = "call finish_task now with the committed artifacts and evidence",
                    toolName = tool
                )
            )
        }
        assertFalse(
            shouldBlockDirectInspectionAfterArtifactsCommitted(
                phase = AgentHarnessPhase.VERIFY,
                exactNextAction = "call finish_task now with evidence",
                toolName = "read_file"
            )
        )
        assertFalse(
            shouldBlockDirectInspectionAfterArtifactsCommitted(
                phase = AgentHarnessPhase.BUILD,
                exactNextAction = "create index.html with write_file",
                toolName = "list_directory"
            )
        )
    }

    @Test
    fun `direct failure recovery state never crosses project conversations`() {
        val store = DirectConversationFailureStore()

        store.setFailedTool(41L, "observe_preview")
        store.setFailure(41L, "No active preview")

        assertEquals(
            DirectConversationFailureState("No active preview", "observe_preview"),
            store.state(41L)
        )
        assertEquals(DirectConversationFailureState(), store.state(42L))

        store.restore(42L, "Invalid run manifest", "write_file")
        assertEquals(
            DirectConversationFailureState("Invalid run manifest", "write_file"),
            store.state(42L)
        )
        assertEquals("No active preview", store.state(41L).failure)

        store.setFailure(41L, null)
        store.setFailedTool(41L, null)
        assertEquals(DirectConversationFailureState(), store.state(41L))
        assertEquals("Invalid run manifest", store.state(42L).failure)
    }

    @Test
    fun `invalid run manifest recovery exposes the exact version one shape`() {
        val failure = "RUN_CONFIG_INVALID: version is required"
        val instruction = directToolFailureRecoveryInstruction("write_file", failure)
        val nextAction = directControlCapsuleNextAction(
            "create the next artifact",
            "IllegalArgumentException: $failure"
        )

        listOf(instruction, nextAction).forEach { text ->
            assertTrue(text.contains(".adt/run.json"))
            assertTrue(text.contains("version"))
            assertTrue(text.contains("runtime"))
            assertTrue(text.contains("entrypoint"))
            assertTrue(text.contains("ui"))
            assertTrue(text.contains("index.html"))
        }
        assertEquals(
            "write_file",
            directStrictRecoveryToolForExecutionFailure("write_file", failure)
        )
    }
}
