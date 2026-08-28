package com.example.llamadroid.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContinuityControlsTest {

    @Test
    fun `working state keeps objective and bounded tool ledger without thinking tags`() {
        var state = AgentRuntimeSupport.createAgentWorkingState(
            role = "CODEBASE_SCOUT",
            objective = "Map the implementation source without treating runtime metadata as code.",
            context = "Inspect only the current project."
        )
        repeat(20) { index ->
            state = state.recordTool(
                toolName = "read_file",
                arguments = mapOf("path" to "src/File$index.kt"),
                status = "OK",
                rawResult = "<think>private rationale</think> File $index contains the requested symbol.",
                nextHint = "Inspect the next relevant source file."
            )
        }

        val prompt = state.toPromptBlock()
        assertTrue(prompt.contains("Map the implementation source"))
        assertTrue(prompt.contains("recent_tool_ledger"))
        assertFalse(prompt.contains("<think>"))
        assertFalse(prompt.contains("private rationale"))
        assertTrue(prompt.length <= 2400)
        assertEquals(8, state.recentTools.size)
    }

    @Test
    fun `finish task accepts empty arguments and derives bounded summary`() {
        val state = AgentRuntimeSupport.createAgentWorkingState(
            role = "PLANNER",
            objective = "Produce a bounded implementation plan."
        ).recordTool(
            toolName = "project_state_read",
            arguments = emptyMap(),
            status = "OK",
            rawResult = "Project state loaded."
        )

        val resolved = AgentRuntimeSupport.resolveFinishTaskPayload(
            agentLabel = "PLANNER",
            arguments = emptyMap(),
            fallbackSummary = state.fallbackFinishSummary()
        )

        assertEquals("SUCCESS", resolved.result.status)
        val payload = JSONObject(resolved.canonicalSummary)
        assertTrue(payload.getString("summary").contains("Produce a bounded implementation plan"))
    }

    @Test
    fun `finish task normalizes common status aliases case insensitively`() {
        val success = AgentRuntimeSupport.resolveFinishTaskPayload(
            agentLabel = "RESEARCHER",
            arguments = mapOf("status" to "completed")
        )
        val cancelled = AgentRuntimeSupport.resolveFinishTaskPayload(
            agentLabel = "EXECUTOR",
            arguments = mapOf("status" to "canceled")
        )

        assertEquals("SUCCESS", success.result.status)
        assertEquals("CANCELLED", cancelled.result.status)
    }


    @Test
    fun `empty finish reflection candidate uses the runtime working objective`() {
        val state = AgentRuntimeSupport.createAgentWorkingState(
            role = "CODEBASE_SCOUT",
            objective = "Map source files outside runtime metadata."
        )
        val candidate = AgentRuntimeSupport.finishTaskReflectionCandidate(
            arguments = emptyMap(),
            fallbackSummary = state.fallbackFinishSummary()
        )

        assertTrue(candidate.contains("Map source files outside runtime metadata"))
        assertFalse(candidate.contains("<think>"))
    }

    @Test
    fun `non success empty finish summary does not claim completion`() {
        val resolved = AgentRuntimeSupport.resolveFinishTaskPayload(
            agentLabel = "EXECUTOR",
            arguments = mapOf("status" to "FAILED"),
            fallbackSummary = "Assigned objective: run the verification suite."
        )
        val summary = JSONObject(resolved.canonicalSummary).getString("summary")

        assertEquals("FAILED", resolved.result.status)
        assertTrue(summary.contains("failed before completion"))
        assertFalse(summary.startsWith("Completed"))
    }

    @Test
    fun `execution normalizer lets explicit status override legacy embedded status`() {
        val rawArguments = mapOf(
            "status" to "SUCCESS",
            "summary" to JSONObject()
                .put("status", "FAILED")
                .put("summary", "Research completed.")
                .toString()
        )
        val normalized =
            AgentRuntimeSupport.normalizeFinishTaskArgumentsForExecution(
                rawArguments
            )
        val resolved = AgentRuntimeSupport.resolveFinishTaskPayload(
            agentLabel = "RESEARCHER",
            arguments = normalized
        )

        assertEquals("SUCCESS", resolved.result.status)
        assertEquals(
            "SUCCESS",
            JSONObject(resolved.canonicalSummary).getString("status")
        )
    }

    @Test
    fun `strict finish resolver still rejects unresolved conflicting status sources`() {
        try {
            AgentRuntimeSupport.resolveFinishTaskPayload(
                agentLabel = "EXECUTOR",
                arguments = mapOf(
                    "status" to "SUCCESS",
                    "summary" to JSONObject()
                        .put("status", "FAILED")
                        .put("summary", "Command failed.")
                        .toString()
                )
            )
            throw AssertionError("Expected conflicting status values to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("conflicts"))
        }
    }

    @Test
    fun `scout can read exact tools reference but cannot traverse brain`() {
        assertTrue(
            AgentRuntimeSupport.evaluateCodebaseScoutPath(
                "read_file",
                "brain/tools_reference.md"
            ).allowed
        )
        assertFalse(
            AgentRuntimeSupport.evaluateCodebaseScoutPath(
                "list_directory",
                "brain"
            ).allowed
        )
        assertFalse(
            AgentRuntimeSupport.evaluateCodebaseScoutPath(
                "read_file",
                "brain/timeline.md"
            ).allowed
        )
        assertTrue(
            AgentRuntimeSupport.evaluateCodebaseScoutPath(
                "list_directory",
                "."
            ).allowed
        )
        assertTrue(AgentRuntimeSupport.isCodebaseScoutExcludedPath("app/build/generated.kt"))
        assertFalse(AgentRuntimeSupport.isCodebaseScoutExcludedPath("app/src/Main.kt"))
    }


    @Test
    fun `finish task accepts legacy flat scout report arguments`() {
        val resolved = AgentRuntimeSupport.resolveFinishTaskPayload(
            agentLabel = "CODEBASE_SCOUT",
            arguments = mapOf(
                "status" to "SUCCESS",
                "relevant_files" to "[\"src/main.js\"]",
                "architecture" to "[\"Vanilla JavaScript application.\"]",
                "dependencies" to "[]",
                "constraints" to "[]",
                "risks" to "[]",
                "open_questions" to "[]",
                "recommended_scope" to "[\"Implement the game loop.\"]"
            )
        )

        val payload = JSONObject(resolved.canonicalSummary)
        assertEquals("src/main.js", payload.getJSONArray("relevant_files").getString(0))
        assertTrue(resolved.result is AgentResult.ScoutResult)
    }

    @Test
    fun `scout report drops runtime metadata and becomes deterministic greenfield report`() {
        val resolved = AgentRuntimeSupport.resolveFinishTaskPayload(
            agentLabel = "CODEBASE_SCOUT",
            arguments = mapOf(
                "summary" to JSONObject()
                    .put("status", "SUCCESS")
                    .put("summary", "Only brain metadata was inspected.")
                    .put("relevant_files", JSONArray(listOf("brain/initial_order.md", "brain/timeline.md")))
                    .put(
                        "architecture",
                        JSONArray(listOf("The repository consists only of files in the brain directory."))
                    )
                    .put("dependencies", JSONArray())
                    .put("constraints", JSONArray())
                    .put("risks", JSONArray())
                    .put("open_questions", JSONArray())
                    .put("recommended_scope", JSONArray(listOf("Create initial source files.")))
                    .toString()
            )
        )

        val payload = JSONObject(resolved.canonicalSummary)
        assertEquals(0, payload.getJSONArray("relevant_files").length())
        assertTrue(payload.getString("summary").contains("No implementation files were found"))
        assertFalse(payload.toString().contains("brain/initial_order.md"))
    }

    @Test
    fun `tool repair card is local bounded and never loads global references`() {
        val card = AgentRuntimeSupport.buildBoundedToolRepairCard(
            suspectedToolName = "finish_task",
            reason = "status was invalid",
            description = "Return control to the Orchestrator.",
            requiredParams = emptyList(),
            parameters = mapOf(
                "summary" to "Optional short summary",
                "status" to "Optional terminal status"
            ),
            availableToolNames = listOf("finish_task", "tool_help")
        )

        assertTrue(card.contains("\"arguments\":{}"))
        assertFalse(card.contains("tools_reference.md"))
        assertTrue(card.length <= 1800)
    }

    @Test
    fun `search scope honors directory and file pattern`() {
        assertTrue(
            AgentRuntimeSupport.projectPathMatchesSearchScope(
                "app/src/main/Main.kt",
                "app/src",
                "*.kt"
            )
        )
        assertFalse(
            AgentRuntimeSupport.projectPathMatchesSearchScope(
                "app/build/Main.kt",
                "app/src",
                "*.kt"
            )
        )
        assertFalse(
            AgentRuntimeSupport.projectPathMatchesSearchScope(
                "app/src/main/Main.java",
                "app/src",
                "*.kt"
            )
        )
    }

    @Test
    fun `cache lanes remain stable across child session ids`() {
        assertEquals(
            "orchestrator",
            AgentRuntimeSupport.stableAgentCacheLane("ORCHESTRATOR")
        )
        assertEquals(
            "specialist",
            AgentRuntimeSupport.stableAgentCacheLane("CODEBASE_SCOUT")
        )
        assertEquals(
            "specialist",
            AgentRuntimeSupport.stableAgentCacheLane("ORCHESTRATOR", "Custom Researcher")
        )
    }

    @Test
    fun `specialist receipt is bounded and keeps report lookup`() {
        val receipt = AgentRuntimeSupport.compactSpecialistReportReceipt(
            reportId = "report-123",
            role = "CODEBASE_SCOUT",
            status = "SUCCESS",
            summary = "A".repeat(2_000),
            todoId = "todo-1",
            todoStatus = "READY_FOR_REVIEW",
            nextAction = "Reread project_state and delegate the next permitted specialist."
        )

        assertTrue(receipt.length <= 900)
        assertTrue(receipt.contains("report-123"))
        assertTrue(receipt.contains("agent_report_read"))
    }
}
