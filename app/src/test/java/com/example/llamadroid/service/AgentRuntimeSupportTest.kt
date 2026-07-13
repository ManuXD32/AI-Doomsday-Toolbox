package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class AgentRuntimeSupportTest {
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
}
