package com.example.llamadroid.service

import com.example.llamadroid.data.model.LiteRtModelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class AgentRuntimeSupportTest {
    @Test
    fun `ling tagged tool call is recovered from reasoning output`() {
        val calls = parseLegacyTaggedToolCalls(
            """
            Action rationale: Inspect the workspace.
            <tool_call>tool_help
            <arg_key>tool_name</arg_key>
            <arg_value>ls</arg_value>
            </tool_call>
            """.trimIndent()
        )

        assertEquals(1, calls.size)
        assertEquals("tool_help", calls.single().name)
        assertEquals(mapOf("tool_name" to "ls"), calls.single().arguments)
        assertEquals("{\"tool_name\":\"ls\"}", calls.single().rawArgumentsJson)
    }

    @Test
    fun `tagged tool recovery preserves multiple argument values and order`() {
        val call = parseLegacyTaggedToolCalls(
            """
            <tool_call>write_file
            <arg_key>path</arg_key><arg_value>index.html</arg_value>
            <arg_key>content</arg_key><arg_value>&lt;h1&gt;Ling QA&lt;/h1&gt;</arg_value>
            </tool_call>
            """.trimIndent()
        ).single()

        assertEquals(listOf("path", "content"), call.arguments.keys.toList())
        assertEquals("index.html", call.arguments["path"])
        assertEquals("&lt;h1&gt;Ling QA&lt;/h1&gt;", call.arguments["content"])
    }

    @Test
    fun `tagged write payload preserves multiline content`() {
        val call = parseLegacyTaggedToolCalls(
            """
            <tool_call>write_file
            <arg_key>path</arg_key><arg_value>index.html</arg_value>
            <arg_key>content</arg_key><arg_value><!doctype html>
            <h1>Ling Agent QA</h1>
            <button>Run</button></arg_value>
            </tool_call>
            """.trimIndent()
        ).single()

        assertEquals(
            "<!doctype html>\n<h1>Ling Agent QA</h1>\n<button>Run</button>",
            call.arguments["content"]
        )
    }

    @Test
    fun `partial tagged tool calls remain malformed instead of becoming executable`() {
        assertTrue(
            parseLegacyTaggedToolCalls(
                "<tool_call>write_file <arg_key>path</arg_key></tool_call>"
            ).isEmpty()
        )
        assertTrue(
            parseLegacyTaggedToolCalls(
                "<tool_call>write_file; run_command <arg_key>path</arg_key>" +
                    "<arg_value>index.html</arg_value></tool_call>"
            ).isEmpty()
        )
    }

    @Test
    fun `tool help resolves ling shell listing vocabulary without exposing a shell`() {
        val available = listOf("read_file", "list_directory", "tool_help")

        assertEquals("list_directory", resolveToolHelpName("ls", available))
        assertEquals("list_directory", resolveToolHelpName("DIR", available))
        assertEquals("read_file", resolveToolHelpName("READ_FILE", available))
        assertNull(resolveToolHelpName("rm", available))
    }

    @Test
    fun `legacy runtime snapshot without backend preserves the current local workspace`() {
        assertEquals(
            AgentWorkspaceBackendType.LOCAL_SANDBOX,
            resolveSnapshotWorkspaceBackend(
                payload = JSONObject(),
                currentBackend = AgentWorkspaceBackendType.LOCAL_SANDBOX
            )
        )
        assertEquals(
            AgentWorkspaceBackendType.REMOTE_SSH,
            resolveSnapshotWorkspaceBackend(
                payload = JSONObject().put("workspaceBackend", "REMOTE_SSH"),
                currentBackend = AgentWorkspaceBackendType.LOCAL_SANDBOX
            )
        )
        listOf("", "UNKNOWN_BACKEND").forEach { stored ->
            assertEquals(
                AgentWorkspaceBackendType.LOCAL_SANDBOX,
                resolveSnapshotWorkspaceBackend(
                    payload = JSONObject().put("workspaceBackend", stored),
                    currentBackend = AgentWorkspaceBackendType.LOCAL_SANDBOX
                )
            )
        }
    }

    @Test
    fun `restored delegated work is interrupted from either session or database evidence`() {
        assertTrue(
            shouldInterruptRestoredAgentWork(
                hadRestoredSessionState = true,
                runningInvocationCount = 0
            )
        )
        assertTrue(
            shouldInterruptRestoredAgentWork(
                hadRestoredSessionState = false,
                runningInvocationCount = 1
            )
        )
        assertFalse(
            shouldInterruptRestoredAgentWork(
                hadRestoredSessionState = false,
                runningInvocationCount = 0
            )
        )
    }

    @Test
    fun `memory rollover stays at the trigger and preserves the heading without recursion`() {
        val source = listOf("# Timeline") + (1..400).map { "event-$it" }

        val retained = selectMemoryRolloverLines(
            lines = source,
            sizeBudgetLines = 240,
            rolloverTriggerLines = 220,
            preserveFirstLine = true
        )

        assertEquals(220, retained.size)
        assertEquals("# Timeline", retained.first())
        assertEquals("event-400", retained.last())
    }

    @Test
    fun `memory rollover without heading keeps only the newest bounded lines`() {
        val retained = selectMemoryRolloverLines(
            lines = (1..400).map(Int::toString),
            sizeBudgetLines = 320,
            rolloverTriggerLines = 280,
            preserveFirstLine = false
        )

        assertEquals(280, retained.size)
        assertEquals("121", retained.first())
        assertEquals("400", retained.last())
    }

    @Test
    fun `invocation names remove controls normalize whitespace and use a stable key`() {
        val normalized = normalizeAgentInvocationName("  Dar\u0000win\n\t  Lovelace  ")

        assertNotNull(normalized)
        assertEquals("Darwin Lovelace", normalized?.displayName)
        assertEquals("darwin lovelace", normalized?.key)
    }

    @Test
    fun `invocation names are bounded and reject blank control-only values`() {
        val longName = "a".repeat(50)

        assertEquals(40, normalizeAgentInvocationName(longName)?.displayName?.length)
        assertNull(normalizeAgentInvocationName(" \n\t\u0000\u001F "))
    }

    @Test
    fun `finalized Agent messages and history mutations persist while heartbeats stay metadata only`() {
        assertTrue(shouldPersistFullAgentSnapshot("Agent message added", force = false))
        assertTrue(shouldPersistFullAgentSnapshot("Agent message updated", force = false))
        assertFalse(shouldPersistFullAgentSnapshot("Terminal output updated", force = false))
        assertTrue(shouldPersistFullAgentSnapshot("Conversation history truncated", force = false))
        assertTrue(shouldPersistFullAgentSnapshot("Regenerate history truncated", force = false))
        assertTrue(shouldPersistFullAgentSnapshot("llama-server heartbeat", force = true))
        assertFalse(shouldPersistFullAgentSnapshot("Agent status update", force = false))
        assertFalse(shouldPersistFullAgentSnapshot("llama-server heartbeat", force = false))
    }

    @Test
    fun `runtime snapshot is active only for live work or unresolved user attention`() {
        assertEquals(
            AiRuntimeJobStore.STATUS_RUNNING,
            resolveAgentRuntimeSnapshotStatus(
                isLoading = true,
                hasPendingToolApproval = false,
                hasPendingPlanApproval = false,
                pendingQuestionCount = 0
            )
        )
        assertEquals(
            AiRuntimeJobStore.STATUS_RECOVERING,
            resolveAgentRuntimeSnapshotStatus(
                isLoading = false,
                hasPendingToolApproval = true,
                hasPendingPlanApproval = false,
                pendingQuestionCount = 0
            )
        )
        assertEquals(
            AiRuntimeJobStore.STATUS_RECOVERING,
            resolveAgentRuntimeSnapshotStatus(
                isLoading = false,
                hasPendingToolApproval = false,
                hasPendingPlanApproval = true,
                pendingQuestionCount = 0
            )
        )
        assertEquals(
            AiRuntimeJobStore.STATUS_RECOVERING,
            resolveAgentRuntimeSnapshotStatus(
                isLoading = false,
                hasPendingToolApproval = false,
                hasPendingPlanApproval = false,
                pendingQuestionCount = 1
            )
        )
        assertEquals(
            AiRuntimeJobStore.STATUS_COMPLETED,
            resolveAgentRuntimeSnapshotStatus(
                isLoading = false,
                hasPendingToolApproval = false,
                hasPendingPlanApproval = false,
                pendingQuestionCount = 0
            )
        )
        assertEquals(
            AiRuntimeJobStore.STATUS_CANCELLED,
            resolveAgentRuntimeSnapshotStatus(
                isLoading = false,
                hasPendingToolApproval = false,
                hasPendingPlanApproval = false,
                pendingQuestionCount = 0,
                existingStatus = AiRuntimeJobStore.STATUS_CANCELLED
            )
        )
        assertEquals(
            AiRuntimeJobStore.STATUS_RUNNING,
            resolveAgentRuntimeSnapshotStatus(
                isLoading = true,
                hasPendingToolApproval = false,
                hasPendingPlanApproval = false,
                pendingQuestionCount = 0,
                existingStatus = AiRuntimeJobStore.STATUS_CANCELLED
            )
        )
        assertTrue(isCriticalAgentProtocolTool("todo_write"))
    }

    @Test
    fun `custom tool parameter specs support legacy and structured json`() {
        val legacy = AgentRuntimeSupport.parseCustomToolParameterSpecs("""{"city":"City name"}""")
        assertEquals("City name", legacy["city"]?.description)

        val structured = AgentRuntimeSupport.parseCustomToolParameterSpecs(
            """{"mode":{"description":"Mode","maxLength":8,"enum":["fast","slow"]}}"""
        )
        assertEquals("Mode", structured["mode"]?.description)
        assertEquals(8, structured["mode"]?.maxLength)
        assertEquals(listOf("fast", "slow"), structured["mode"]?.enumValues)
    }

    @Test
    fun `argv tokenization keeps placeholders as single arguments`() {
        val argv = AgentRuntimeSupport.tokenizeArgvTemplate(
            """python3 tools/run.py --target={target} "{message}" """.trim(),
            mapOf("target" to "src/main.kt", "message" to "hello world")
        )

        assertEquals(
            listOf("python3", "tools/run.py", "--target=src/main.kt", "hello world"),
            argv
        )
    }

    @Test
    fun `shell rendering escapes injected custom tool arguments`() {
        val rendered = AgentRuntimeSupport.renderShellTemplate(
            "shell: curl -s {url}",
            mapOf("url" to "https://example.com'; rm -rf / #'")
        )

        assertTrue(rendered.startsWith("curl -s "))
        assertTrue(rendered.contains("'\"'\"'"))
        assertTrue(rendered.contains("rm -rf /"))
    }

    @Test
    fun `placeholder parsing handles literal braces safely`() {
        val rendered = AgentRuntimeSupport.renderShellTemplate(
            "shell: echo {value}",
            mapOf("value" to "hello")
        )

        assertEquals("echo 'hello'", rendered)
    }

    @Test
    fun `blocked url reason rejects local and private targets`() {
        assertNotNull(AgentRuntimeSupport.blockedUrlReason("file:///etc/passwd"))
        assertNotNull(AgentRuntimeSupport.blockedUrlReason("http://localhost:8080"))
        assertNotNull(AgentRuntimeSupport.blockedUrlReason("http://127.0.0.1/test"))
        assertNotNull(AgentRuntimeSupport.blockedUrlReason("http://192.168.1.20/"))
        assertEquals(null, AgentRuntimeSupport.blockedUrlReason("https://example.com/docs"))
    }

    @Test
    fun `traversal helper only flags real traversal segments`() {
        assertTrue(AgentRuntimeSupport.containsTraversalSegments("../etc/passwd"))
        assertTrue(AgentRuntimeSupport.containsTraversalSegments("foo/../bar"))
        assertFalse(AgentRuntimeSupport.containsTraversalSegments("foo/..bar"))
        assertFalse(AgentRuntimeSupport.containsTraversalSegments("foo/bar"))
    }

    @Test
    fun `sequential batch blocked tools include mutating and completion tools`() {
        assertTrue(AgentRuntimeSupport.isSequentialBatchBlockedTool("finish_task"))
        assertTrue(AgentRuntimeSupport.isSequentialBatchBlockedTool("write_memory"))
        assertTrue(AgentRuntimeSupport.isSequentialBatchBlockedTool("rewrite_memory"))
        assertTrue(AgentRuntimeSupport.isSequentialBatchBlockedTool("delete_memory"))
        assertFalse(AgentRuntimeSupport.isSequentialBatchBlockedTool("read_file"))
    }

    @Test
    fun `chat num ctx override resolves per call`() {
        assertEquals(4096, AgentRuntimeSupport.resolveChatNumCtx(4096))
        assertEquals(2048, AgentRuntimeSupport.resolveChatNumCtx(4096, 2048))
    }

    @Test
    fun `friendly backend model label strips local paths`() {
        assertEquals(
            "gemma-4-E4B-it-Q4_K_M.gguf",
            AgentRuntimeSupport.friendlyBackendModelLabel(
                "/storage/emulated/0/Android/data/com.example/files/models/llm/gemma-4-E4B-it-Q4_K_M.gguf"
            )
        )
        assertEquals("Qwen3.6-14B-A3B", AgentRuntimeSupport.friendlyBackendModelLabel("Qwen3.6-14B-A3B"))
        assertNull(AgentRuntimeSupport.friendlyBackendModelLabel("   "))
    }

    @Test
    fun `agent litert context resolves default and clamps to phone safe cap`() {
        val model = LiteRtModelEntity(
            id = 7L,
            displayName = "Gemma 4 E4B",
            path = "/models/gemma.task",
            filename = "gemma-4-E4B-it-Q4.task",
            maxContextTokens = 32768
        )

        assertEquals(8192, AgentRuntimeSupport.resolveAgentLiteRtContextTokens(-1, model))
        assertEquals(8192, AgentRuntimeSupport.resolveAgentLiteRtContextTokens(12000, model))
        assertEquals(8192, AgentRuntimeSupport.resolveAgentLiteRtContextTokens(59384, model))
        assertEquals(512, AgentRuntimeSupport.resolveAgentLiteRtContextTokens(128, model))
    }

    @Test
    fun `agent litert context falls back when model has no advertised cap`() {
        assertEquals(4000, AgentRuntimeSupport.resolveAgentLiteRtContextTokens(-1, null))
        assertEquals(4000, AgentRuntimeSupport.resolveAgentLiteRtContextTokens(8192, null))
    }

    @Test
    fun `agent litert max output defaults to long code budget`() {
        val model = LiteRtModelEntity(
            id = 7L,
            displayName = "Gemma 4 E4B",
            path = "/models/gemma.task",
            filename = "gemma-4-E4B-it-Q4.task",
            maxContextTokens = 32768
        )

        assertEquals(8096, AgentRuntimeSupport.resolveAgentLiteRtMaxOutputTokens(-1, 8192, model))
        assertEquals(8096, AgentRuntimeSupport.resolveAgentLiteRtMaxOutputTokens(-1, 16200, model))
        assertEquals(4096, AgentRuntimeSupport.resolveAgentLiteRtMaxOutputTokens(4096, 16200, model))
    }

    @Test
    fun `agent litert max output clamps to selected context and model cap`() {
        val model = LiteRtModelEntity(
            id = 8L,
            displayName = "Small LiteRT",
            path = "/models/small.task",
            filename = "small.task",
            maxContextTokens = 4096
        )

        assertEquals(4096, AgentRuntimeSupport.resolveAgentLiteRtMaxOutputTokens(8096, 12000, model))
        assertEquals(4000, AgentRuntimeSupport.resolveAgentLiteRtMaxOutputTokens(-1, 4000, null))
        assertEquals(128, AgentRuntimeSupport.resolveAgentLiteRtMaxOutputTokens(64, 256, model))
    }

    @Test
    fun `loading counter clamp prevents negative idle state`() {
        val clamped = AgentRuntimeSupport.normalizeLoadingCounterAfterDecrement(-1)
        val unchanged = AgentRuntimeSupport.normalizeLoadingCounterAfterDecrement(2)

        assertEquals(0, clamped.count)
        assertTrue(clamped.wasClamped)
        assertEquals(2, unchanged.count)
        assertFalse(unchanged.wasClamped)
    }

    @Test
    fun `connection loss only releases loading when work is active`() {
        assertFalse(
            AgentRuntimeSupport.shouldReleaseLoadingOnConnectionLoss(
                loadingCount = 0,
                hasActiveJob = false
            )
        )
        assertTrue(
            AgentRuntimeSupport.shouldReleaseLoadingOnConnectionLoss(
                loadingCount = 1,
                hasActiveJob = false
            )
        )
        assertTrue(
            AgentRuntimeSupport.shouldReleaseLoadingOnConnectionLoss(
                loadingCount = 0,
                hasActiveJob = true
            )
        )
    }

    @Test
    fun `queued continuation from an invalidated epoch cannot be dispatched`() {
        assertFalse(
            AgentRuntimeSupport.shouldDispatchQueuedContinuation(
                queuedEpoch = 4L,
                activeEpoch = 5L,
                automaticContinuationsBlocked = false,
                userInitiated = false
            )
        )
        assertFalse(
            AgentRuntimeSupport.shouldDispatchQueuedContinuation(
                queuedEpoch = 5L,
                activeEpoch = 5L,
                automaticContinuationsBlocked = true,
                userInitiated = false
            )
        )
        assertTrue(
            AgentRuntimeSupport.shouldDispatchQueuedContinuation(
                queuedEpoch = 5L,
                activeEpoch = 5L,
                automaticContinuationsBlocked = true,
                userInitiated = true
            )
        )
    }

    @Test
    fun `current job completion wakes a waiting continuation drain only when work is queued`() {
        assertTrue(
            AgentRuntimeSupport.shouldWakeContinuationDrainAfterCurrentJobCompletion(
                currentJobWasCleared = true,
                pendingContinuationCount = 1
            )
        )
        assertFalse(
            AgentRuntimeSupport.shouldWakeContinuationDrainAfterCurrentJobCompletion(
                currentJobWasCleared = true,
                pendingContinuationCount = 0
            )
        )
        assertFalse(
            AgentRuntimeSupport.shouldWakeContinuationDrainAfterCurrentJobCompletion(
                currentJobWasCleared = false,
                pendingContinuationCount = 1
            )
        )
    }

    @Test
    fun `drain completion waits for current job completion before restarting`() {
        assertFalse(
            AgentRuntimeSupport.shouldRestartContinuationDrainAfterCompletion(
                runEpochStillActive = true,
                currentJobActive = true,
                pendingContinuationCount = 1
            )
        )
        assertTrue(
            AgentRuntimeSupport.shouldRestartContinuationDrainAfterCompletion(
                runEpochStillActive = true,
                currentJobActive = false,
                pendingContinuationCount = 1
            )
        )
        assertFalse(
            AgentRuntimeSupport.shouldRestartContinuationDrainAfterCompletion(
                runEpochStillActive = false,
                currentJobActive = false,
                pendingContinuationCount = 1
            )
        )
    }

    @Test
    fun `background command disconnect reason only triggers for running commands`() {
        assertEquals(
            "SSH session disconnected while command was still running.",
            AgentRuntimeSupport.backgroundCommandDisconnectReason(
                isRunning = true,
                sessionConnected = false,
                channelConnected = true
            )
        )
        assertEquals(
            "Shell channel disconnected while command was still running.",
            AgentRuntimeSupport.backgroundCommandDisconnectReason(
                isRunning = true,
                sessionConnected = true,
                channelConnected = false
            )
        )
        assertNull(
            AgentRuntimeSupport.backgroundCommandDisconnectReason(
                isRunning = false,
                sessionConnected = false,
                channelConnected = false
            )
        )
    }

    @Test
    fun `html stripping removes common markup`() {
        val stripped = AgentRuntimeSupport.stripHtmlTags(
            "<html><head><style>body{color:red}</style><script>alert(1)</script></head><body>hello <b>world</b></body></html>"
        )

        assertEquals("hello world", stripped)
    }

    @Test
    fun `running command reminders are detected only from command tool output`() {
        assertTrue(
            isBackgroundCommandReminder(
                "run_command",
                "Command ID: cmd_123\nStatus: running\nRequested tail lines: 10\nOutput:\nhello",
                null
            )
        )
        assertFalse(
            isBackgroundCommandReminder(
                null,
                "Command ID: cmd_123\nStatus: running\nRequested tail lines: 10\nOutput:\nhello",
                null
            )
        )
        assertFalse(
            isBackgroundCommandReminder(
                "run_command",
                "Command ID: cmd_123\nStatus: finished (exit code: 0)\nRequested tail lines: 10\nOutput:\nhello",
                null
            )
        )
    }

    @Test
    fun `runtime checkpoints are throttled unless forced`() {
        assertTrue(shouldWriteRuntimeCheckpoint(nowMs = 30_000L, lastCheckpointMs = 0L, intervalMs = 30_000L, force = false))
        assertFalse(shouldWriteRuntimeCheckpoint(nowMs = 29_999L, lastCheckpointMs = 0L, intervalMs = 30_000L, force = false))
        assertTrue(shouldWriteRuntimeCheckpoint(nowMs = 1L, lastCheckpointMs = 0L, intervalMs = 30_000L, force = true))
    }

    @Test
    fun `structured command result requires command id status requested tail and output`() {
        assertTrue(
            isStructuredCommandResult(
                "Command ID: cmd_123\nStatus: finished (exit code: 0)\nRequested tail lines: 20\nOutput:\nDone"
            )
        )
        assertFalse(
            isStructuredCommandResult(
                "Command ID: cmd_123\nStatus: finished (exit code: 0)\nOutput:\nDone"
            )
        )
    }

    @Test
    fun `command output tail keeps newest completed lines`() {
        val completedLines = (1..12).map { "line $it" }

        assertEquals(
            listOf("line 9", "line 10", "line 11", "line 12"),
            commandOutputTailLines(
                completedLines = completedLines,
                pendingLine = "",
                requestedLines = 4
            )
        )
    }

    @Test
    fun `command output tail includes newest pending progress line`() {
        val completedLines = listOf("configure", "compile")

        assertEquals(
            listOf("compile", "linking 73%"),
            commandOutputTailLines(
                completedLines = completedLines,
                pendingLine = "linking 73%",
                requestedLines = 2
            )
        )
    }

    @Test
    fun `real prompt compactions are recorded only when history is reduced`() {
        assertTrue(
            shouldRecordPromptCompactionEvent(
                rawEstimatedTokens = 2_000,
                packedEstimatedTokens = 1_200,
                omittedCount = 3,
                compactionPasses = 2,
                didCompactHistory = true
            )
        )
        assertFalse(
            shouldRecordPromptCompactionEvent(
                rawEstimatedTokens = 2_000,
                packedEstimatedTokens = 2_000,
                omittedCount = 0,
                compactionPasses = 1,
                didCompactHistory = false
            )
        )
    }

    @Test
    fun `hard compaction scheduling triggers immediately before any prior hard compaction exists`() {
        assertTrue(
            shouldScheduleHardCompaction(
                percentUsed = 70,
                thresholdPercent = 70,
                emergencyThresholdPercent = 85,
                hardCompactionActive = false,
                completedTurnGroupsSinceLastCompaction = 0,
                minTurnGroupsBetweenCompactions = 2
            )
        )
    }

    @Test
    fun `hard compaction scheduling respects cooldown unless emergency threshold is reached`() {
        assertFalse(
            shouldScheduleHardCompaction(
                percentUsed = 74,
                thresholdPercent = 70,
                emergencyThresholdPercent = 85,
                hardCompactionActive = true,
                completedTurnGroupsSinceLastCompaction = 1,
                minTurnGroupsBetweenCompactions = 2
            )
        )
        assertTrue(
            shouldScheduleHardCompaction(
                percentUsed = 85,
                thresholdPercent = 70,
                emergencyThresholdPercent = 85,
                hardCompactionActive = true,
                completedTurnGroupsSinceLastCompaction = 1,
                minTurnGroupsBetweenCompactions = 2
            )
        )
    }

    @Test
    fun `compact prompt basis keeps only retained primacy sections and compact state as optional`() {
        val sections = buildCompactPromptBasisSections(
            systemPrompt = "SYSTEM",
            initialOrder = "Build the feature.",
            planContent = "# Plan\n- Step 1",
            compactionSummary = "# Context Compaction Summary\n## Tasks Done\n- done",
            compactStateSnapshot = "COMPACT STATE SNAPSHOT:\n{}"
        )

        assertEquals(4, sections.requiredSections.size)
        assertTrue(sections.requiredSections[1].contains("# Initial Order"))
        assertTrue(sections.requiredSections[2].contains("# Plan"))
        assertTrue(sections.requiredSections[3].contains("# Context Compaction Summary"))
        assertEquals(listOf("COMPACT STATE SNAPSHOT:\n{}"), sections.optionalSections)
    }

    @Test
    fun `hard compaction summary document omits duplicated initial order and plan body sections`() {
        val summary = buildHardCompactionSummaryDocument(
            generatedAt = "2026-04-21 12:00:00",
            summarizedMessageCount = 14,
            retainedRecentMessageCount = 5,
            retainedRecentTokenEstimate = 1600,
            retainedRecentTargetTokens = 1800,
            planCoverageLabel = "50% (1/2 plan items evidenced)",
            completedPlanItems = listOf("Implemented the fix"),
            missingPlanItems = listOf("Run the final verification"),
            tasksDone = listOf("Updated runtime packing"),
            readFiles = listOf("app/src/main/java/Foo.kt"),
            changedFiles = listOf("AgentService.kt"),
            importantFindings = listOf("Pinned context was too large"),
            openRisks = listOf("Needs final verification"),
            activeCommands = listOf("none"),
            carryForward = listOf("Keep the compact basis small")
        )

        assertFalse(summary.contains("## Initial Order"))
        assertFalse(summary.contains("Approved implementation plan"))
        assertTrue(summary.contains("## Implementation Plan Status"))
        assertTrue(summary.contains("## Compaction Window"))
        assertTrue(summary.contains("## Files Read / Referenced"))
        assertTrue(summary.contains("## Carry Forward For Next Turns"))
    }

    @Test
    fun `history token budget can drop to zero when primacy already fills the target`() {
        assertEquals(0, computeHistoryTokenBudget(targetTokens = 2_048, pinnedBudget = 2_400))
        assertEquals(512, computeHistoryTokenBudget(targetTokens = 2_048, pinnedBudget = 1_536))
    }

    @Test
    fun `token budgeted recent tail keeps newest messages within target`() {
        val selection = selectTokenBudgetedRecentTail(
            messageTokenEstimates = listOf(400, 400, 400, 400, 400),
            tokenLimit = 2_000,
            recentTailFraction = 0.40,
            minRecentMessages = 1
        )

        assertEquals(3, selection.splitIndex)
        assertEquals(3, selection.summarizedCount)
        assertEquals(2, selection.recentCount)
        assertEquals(800, selection.recentTokenEstimate)
        assertEquals(800, selection.targetRecentTokens)
    }

    @Test
    fun `token budgeted recent tail keeps at least one oversized newest message`() {
        val selection = selectTokenBudgetedRecentTail(
            messageTokenEstimates = listOf(100, 100, 2_000),
            tokenLimit = 2_000,
            recentTailFraction = 0.40,
            minRecentMessages = 1
        )

        assertEquals(2, selection.splitIndex)
        assertEquals(1, selection.recentCount)
        assertEquals(2_000, selection.recentTokenEstimate)
    }

    @Test
    fun `normalize tool arguments accepts json string payloads`() {
        val args = AgentRuntimeSupport.normalizeToolArguments("""{"path":"src/Main.kt","start_line":10}""")

        assertEquals("src/Main.kt", args["path"])
        assertEquals("10", args["start_line"])
    }

    @Test
    fun `chat message json round trips persisted fields`() {
        val original = AgentService.Companion.ChatMessage(
            role = "assistant",
            content = "hello",
            imagePath = "/workspace/project/image.png",
            thinking = "pondering",
            toolName = "read_file",
            toolCallId = "call_1",
            toolArgs = mapOf("path" to "src/Main.kt"),
            toolOutput = "output",
            terminalOutput = "term output",
            isTerminalVisible = true,
            isStreaming = false,
            needsApproval = true,
            isApproved = true,
            isPlan = true,
            isPlanApproved = true,
            planModifiedContent = "modified plan",
            isDelegation = true,
            agentRole = "ORCHESTRATOR",
            customAgentName = "CustomAgent",
            isSuspicious = true,
            pendingToolCall = OllamaService.ToolCall(
                name = "read_file",
                arguments = mapOf("path" to "src/Main.kt"),
                id = "call_1"
            ),
            isOutputExpanded = true,
            timestamp = 123456789L,
            sequenceNumber = 42
        )

        val restored = AgentService.chatMessageFromJson(AgentService.chatMessageToJson(original))

        assertEquals(original.id, restored.id)
        assertEquals(original.role, restored.role)
        assertEquals(original.content, restored.content)
        assertEquals(original.imagePath, restored.imagePath)
        assertEquals(original.thinking, restored.thinking)
        assertEquals(original.toolName, restored.toolName)
        assertEquals(original.toolCallId, restored.toolCallId)
        assertEquals(original.toolArgs, restored.toolArgs)
        assertEquals(original.toolOutput, restored.toolOutput)
        assertEquals(original.terminalOutput, restored.terminalOutput)
        assertEquals(original.isTerminalVisible, restored.isTerminalVisible)
        assertEquals(original.needsApproval, restored.needsApproval)
        assertEquals(original.isApproved, restored.isApproved)
        assertEquals(original.isPlan, restored.isPlan)
        assertEquals(original.isPlanApproved, restored.isPlanApproved)
        assertEquals(original.planModifiedContent, restored.planModifiedContent)
        assertEquals(original.isDelegation, restored.isDelegation)
        assertEquals(original.agentRole, restored.agentRole)
        assertEquals(original.customAgentName, restored.customAgentName)
        assertEquals(original.isSuspicious, restored.isSuspicious)
        assertEquals(original.pendingToolCall?.name, restored.pendingToolCall?.name)
        assertEquals(original.pendingToolCall?.arguments, restored.pendingToolCall?.arguments)
        assertEquals(original.pendingToolCall?.id, restored.pendingToolCall?.id)
        assertEquals(original.isOutputExpanded, restored.isOutputExpanded)
        assertEquals(original.timestamp, restored.timestamp)
        assertEquals(original.sequenceNumber, restored.sequenceNumber)
    }

    @Test
    fun `compute edited file content preserves missing trailing newline`() {
        val computation = AgentRuntimeSupport.computeEditedFileContent(
            originalContent = "one\ntwo",
            startLine = 2,
            endLine = 2,
            newContent = "updated"
        )

        assertEquals("one\nupdated", computation.updatedContent)
        assertFalse(computation.preservedTrailingNewline)
        assertEquals(2, computation.originalLineCount)
        assertEquals(1, computation.insertedLineCount)
    }

    @Test
    fun `compute edited file content preserves trailing newline and inserted blank line`() {
        val computation = AgentRuntimeSupport.computeEditedFileContent(
            originalContent = "one\ntwo\n",
            startLine = 2,
            endLine = 2,
            newContent = "updated\n"
        )

        assertEquals("one\nupdated\n\n", computation.updatedContent)
        assertTrue(computation.preservedTrailingNewline)
        assertEquals(3, computation.originalLineCount)
        assertEquals(2, computation.insertedLineCount)
    }

    @Test
    fun `optional long reader keeps null distinct from zero`() {
        val payload = JSONObject().apply {
            put("activeConversationId", 0L)
            put("missingConversationId", JSONObject.NULL)
        }

        assertEquals(0L, AgentRuntimeSupport.readOptionalLong(payload, "activeConversationId"))
        assertNull(AgentRuntimeSupport.readOptionalLong(payload, "missingConversationId"))
        assertNull(AgentRuntimeSupport.readOptionalLong(payload, "unknown"))
    }

    @Test
    fun `parse agent result validates coder schema`() {
        val result = AgentRuntimeSupport.parseAgentResult(
            "CODER",
            """{"status":"SUCCESS","changed_files":["app/src/Main.kt"],"intent_per_file":{"app/src/Main.kt":"Fix validation"},"verification_reads":["app/src/Main.kt:10-30"],"remaining_risks":["Need runtime test"]}"""
        )

        assertTrue(result is AgentResult.CoderResult)
        val coder = result as AgentResult.CoderResult
        assertEquals(listOf("app/src/Main.kt"), coder.changedFiles)
        assertEquals("Fix validation", coder.intentPerFile["app/src/Main.kt"])
    }

    @Test
    fun `continuation guard allows bounded serial work`() {
        val decision = AgentRuntimeSupport.evaluateContinuationGuard(
            continuationCount = 4,
            queueDepth = 1,
            maxContinuations = 12,
            maxQueueDepth = 3,
            reason = "tool result"
        )

        assertFalse(decision.shouldPause)
        assertNull(decision.reason)
    }

    @Test
    fun `continuation guard pauses when queue is too deep`() {
        val decision = AgentRuntimeSupport.evaluateContinuationGuard(
            continuationCount = 4,
            queueDepth = 4,
            maxContinuations = 12,
            maxQueueDepth = 3,
            reason = "nested recovery"
        )

        assertTrue(decision.shouldPause)
        assertTrue(decision.reason!!.contains("queueDepth=4/3"))
    }

    @Test
    fun `continuation guard pauses repeated runaway turns before active jobs can climb`() {
        val decision = AgentRuntimeSupport.evaluateContinuationGuard(
            continuationCount = 13,
            queueDepth = 1,
            maxContinuations = 12,
            maxQueueDepth = 3,
            reason = "finish_task reflection memory recovery loop"
        )

        assertTrue(decision.shouldPause)
        assertTrue(decision.reason!!.contains("continuations=13/12"))
    }

    @Test
    fun `continuation guard permits long healthy serialized workflows`() {
        val decision = AgentRuntimeSupport.evaluateContinuationGuard(
            continuationCount = 24,
            queueDepth = 1,
            maxContinuations = 96,
            maxQueueDepth = 3,
            reason = "delegation REVIEWER completed",
            consecutiveNoProgress = 0,
            maxNoProgress = 4
        )

        assertFalse(decision.shouldPause)
    }

    @Test
    fun `continuation guard pauses repeated no progress recovery`() {
        val decision = AgentRuntimeSupport.evaluateContinuationGuard(
            continuationCount = 8,
            queueDepth = 1,
            maxContinuations = 96,
            maxQueueDepth = 3,
            reason = "supervisor retry",
            consecutiveNoProgress = 5,
            maxNoProgress = 4
        )

        assertTrue(decision.shouldPause)
        assertTrue(decision.reason!!.contains("noProgress=5/4"))
    }

    @Test
    fun `queued user guidance waits for an atomic tool boundary`() {
        assertFalse(
            AgentRuntimeSupport.shouldInjectQueuedUserGuidance(
                pendingCount = 1,
                toolCallDetected = true,
                toolResultCommitted = false,
                modelTurnCompleted = true
            )
        )
        assertTrue(
            AgentRuntimeSupport.shouldInjectQueuedUserGuidance(
                pendingCount = 1,
                toolCallDetected = true,
                toolResultCommitted = true,
                modelTurnCompleted = false
            )
        )
    }

    @Test
    fun `completed no-tool turn is a safe fallback for queued guidance`() {
        assertTrue(
            AgentRuntimeSupport.shouldInjectQueuedUserGuidance(
                pendingCount = 1,
                toolCallDetected = false,
                toolResultCommitted = false,
                modelTurnCompleted = true
            )
        )
        assertFalse(
            AgentRuntimeSupport.shouldInjectQueuedUserGuidance(
                pendingCount = 0,
                toolCallDetected = false,
                toolResultCommitted = false,
                modelTurnCompleted = true
            )
        )
    }

    @Test
    fun `output budget defaults and clamps to remaining context`() {
        assertEquals(
            8096,
            resolveAgentEffectiveMaxOutputTokens(
                configuredMaxOutputTokens = 0,
                contextTokens = 65_536,
                estimatedPromptTokens = 10_000
            )
        )
        assertEquals(
            744,
            resolveAgentEffectiveMaxOutputTokens(
                configuredMaxOutputTokens = 8096,
                contextTokens = 10_000,
                estimatedPromptTokens = 9_000
            )
        )
        assertEquals(
            1,
            resolveAgentEffectiveMaxOutputTokens(
                configuredMaxOutputTokens = 8096,
                contextTokens = 10_000,
                estimatedPromptTokens = 20_000
            )
        )
    }

    @Test
    fun `streaming preview remains bounded while preserving head and latest tail`() {
        val raw = "a".repeat(80) + "LATEST"
        val preview = boundedStreamingPreview(raw, maxChars = 40, tailChars = 10)

        assertTrue(preview.length <= 40)
        assertTrue(preview.startsWith("a"))
        assertTrue(preview.endsWith("LATEST"))
        assertTrue(preview.contains("…"))
    }

    @Test
    fun `unchanged plan approval keeps the plan out of its compact tool result`() {
        val decision = planApprovalPromptCacheDecision("# Plan\n- Implement cache", "# Plan\n- Implement cache")

        assertEquals("Implement the plan.", decision.summary)
        assertNull(decision.modifiedPlanForToolResult)
        assertTrue(decision.retainsRootCacheEpoch)
    }

    @Test
    fun `edited plan approval returns only the modified plan`() {
        val decision = planApprovalPromptCacheDecision("# Plan\n- Old", "# Plan\n- Edited")

        assertEquals("Implement the modified plan.", decision.summary)
        assertEquals("# Plan\n- Edited", decision.modifiedPlanForToolResult)
        assertTrue(decision.retainsRootCacheEpoch)
    }

    @Test
    fun `tool schema stays stable across plan and build modes while mode control changes`() {
        val schema = listOf("read_file", "write_file", "propose_plan")

        assertEquals(schema, stableAgentToolSchemaAcrossModes(schema, isPlanMode = true))
        assertEquals(schema, stableAgentToolSchemaAcrossModes(schema, isPlanMode = false))
        assertTrue(buildAgentRuntimeModeControl(true, true).contains("PLAN"))
        assertTrue(buildAgentRuntimeModeControl(true, true).contains("microsteps"))
        assertTrue(buildAgentRuntimeModeControl(false, true).contains("todo_write"))
        assertTrue(buildAgentRuntimeModeControl(false, true).contains("zero durable TODOs"))
        assertTrue(buildAgentRuntimeModeControl(false, true).contains("before any todo_transition"))
        assertFalse(buildAgentRuntimeModeControl(false, true).contains("microsteps"))
    }
}
