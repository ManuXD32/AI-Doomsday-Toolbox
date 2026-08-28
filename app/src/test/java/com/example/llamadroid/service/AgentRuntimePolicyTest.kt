package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimePolicyTest {
    @Test
    fun `planning specialist calls are allowed in Plan mode without a todo`() {
        listOf(
            "CODEBASE_SCOUT",
            "SCOUT",
            "RESEARCHER",
            "RESEARCH",
            "PLANNER"
        ).forEach { agent ->
            val decision = evaluatePlanModeToolPolicy(
                isPlanMode = true,
                toolName = "call_agent",
                arguments = mapOf(
                    "agent" to agent,
                    "name" to "plan-read",
                    "task" to "Inspect one bounded planning question"
                )
            )
            assertTrue("$agent should be allowed: ${decision.message}", decision.allowed)
            assertEquals("ALLOWED_PLAN_RESEARCH_DELEGATION", decision.code)
        }
    }

    @Test
    fun `Build workers and mutating custom agents are blocked in Plan mode`() {
        listOf(
            "CODER",
            "REVIEWER",
            "EXECUTOR",
            "VISUAL_TESTER",
            "SUMMARIZER",
            "MUTATING_CUSTOM"
        ).forEach { agent ->
            val decision = evaluatePlanModeToolPolicy(
                isPlanMode = true,
                toolName = "call_agent",
                arguments = mapOf("agent" to agent)
            )
            assertFalse("$agent must be blocked", decision.allowed)
            assertEquals("PLAN_MODE_AGENT_BLOCKED", decision.code)
        }
    }

    @Test
    fun `Plan discovery delegation cannot claim a Build todo`() {
        val decision = evaluatePlanModeToolPolicy(
            isPlanMode = true,
            toolName = "call_agent",
            arguments = mapOf(
                "agent" to "CODEBASE_SCOUT",
                "todo_id" to "todo-123"
            )
        )

        assertFalse(decision.allowed)
        assertEquals("PLAN_MODE_TODO_DELEGATION_BLOCKED", decision.code)
    }

    @Test
    fun `explicitly read-only custom agents can be delegated in Plan mode`() {
        val configuredTools = setOf(
            "read_file",
            "search_code",
            "read_memory",
            "finish_task"
        )
        assertTrue(isPlanSafeCustomAgentToolSet(configuredTools))

        val decision = evaluatePlanModeToolPolicy(
            isPlanMode = true,
            toolName = "call_agent",
            arguments = mapOf("agent" to "ARCHITECTURE_AUDITOR"),
            planSafeCustomAgentNames = setOf("ARCHITECTURE_AUDITOR")
        )
        assertTrue(decision.allowed)
    }

    @Test
    fun `empty or mutating custom tool sets are not Plan safe`() {
        assertFalse(isPlanSafeCustomAgentToolSet(emptySet()))
        assertFalse(
            isPlanSafeCustomAgentToolSet(
                setOf("read_file", "write_file", "finish_task")
            )
        )
        assertFalse(
            isPlanSafeCustomAgentToolSet(
                setOf("read_file", "run_command", "finish_task")
            )
        )
    }

    @Test
    fun `Plan mode blocks mutation but keeps read-only inspection available`() {
        val blocked = listOf(
            "write_file",
            "edit_lines",
            "apply_patch",
            "run_command",
            "generate_image",
            "remove_image_background",
            "write_memory",
            "run_skill_script"
        )
        blocked.forEach { tool ->
            val decision = evaluatePlanModeToolPolicy(
                isPlanMode = true,
                toolName = tool,
                arguments = emptyMap()
            )
            assertFalse("$tool must be blocked", decision.allowed)
            assertEquals("PLAN_MODE_MUTATION_BLOCKED", decision.code)
        }

        listOf(
            "read_file",
            "search_code",
            "project_state_read",
            "question",
            "propose_plan"
        ).forEach { tool ->
            assertTrue(
                evaluatePlanModeToolPolicy(
                    isPlanMode = true,
                    toolName = tool,
                    arguments = emptyMap()
                ).allowed
            )
        }
    }

    @Test
    fun `Build mode does not apply the Plan read-only policy`() {
        val decision = evaluatePlanModeToolPolicy(
            isPlanMode = false,
            toolName = "call_agent",
            arguments = mapOf("agent" to "CODER")
        )
        assertTrue(decision.allowed)
        assertEquals("ALLOWED_BUILD_MODE", decision.code)
    }

    @Test
    fun `delegation terminal states remain distinct`() {
        val success = resolveAgentTerminalPresentation("SUCCESS")
        assertEquals(AgentTerminalKind.SUCCESS, success.kind)
        assertEquals("COMPLETED", success.invocationStatus)
        assertEquals("ok", success.envelopeStatus)

        val blocked = resolveAgentTerminalPresentation("BLOCKED")
        assertEquals(AgentTerminalKind.BLOCKED, blocked.kind)
        assertEquals("BLOCKED", blocked.invocationStatus)
        assertEquals("blocked", blocked.envelopeStatus)

        val failed = resolveAgentTerminalPresentation("FAILED")
        assertEquals(AgentTerminalKind.FAILED, failed.kind)
        assertEquals("FAILED", failed.invocationStatus)
        assertEquals("error", failed.envelopeStatus)

        val cancelled = resolveAgentTerminalPresentation("CANCELLED")
        assertEquals(AgentTerminalKind.CANCELLED, cancelled.kind)
        assertEquals("CANCELLED", cancelled.invocationStatus)
        assertEquals("cancelled", cancelled.envelopeStatus)

        val interrupted = resolveAgentTerminalPresentation("INTERRUPTED")
        assertEquals(AgentTerminalKind.INTERRUPTED, interrupted.kind)
        assertEquals("INTERRUPTED", interrupted.invocationStatus)
        assertEquals("error", interrupted.envelopeStatus)

        assertEquals(
            AgentTerminalKind.SUCCESS,
            resolveAgentTerminalPresentation("PASS").kind
        )
        assertEquals(
            AgentTerminalKind.CANCELLED,
            resolveAgentTerminalPresentation("CANCELED").kind
        )
    }

    @Test
    fun `unstructured specialist output is never inferred as success`() {
        assertEquals(
            "FAILED",
            inferAgentTerminalStatusFromSummary(
                "I inspected the repository and I am done."
            )
        )
        assertEquals(
            "FAILED",
            inferAgentTerminalStatusFromSummary("")
        )
    }

    @Test
    fun `runtime parser marks plain specialist output as failed`() {
        val result = AgentRuntimeSupport.parseAgentResult(
            "PLANNER",
            "I made a plan but did not call finish_task."
        )
        assertEquals("FAILED", result.status)
    }

    @Test
    fun `explicit terminal JSON survives role parser rejection`() {
        assertEquals(
            "FAILED",
            inferAgentTerminalStatusFromSummary(
                """{"status":"SUCCESS","summary":"missing role fields"}"""
            )
        )
        assertEquals(
            "FAILED",
            inferAgentTerminalStatusFromSummary(
                """{"status":"FAILED","summary":"backend stopped"}"""
            )
        )
        assertEquals(
            "BLOCKED",
            inferAgentTerminalStatusFromSummary(
                """{"status":"BLOCKED","summary":"need user input"}"""
            )
        )
        assertEquals(
            "CANCELLED",
            inferAgentTerminalStatusFromSummary(
                """{"status":"CANCELED"}"""
            )
        )
        assertEquals(
            "INTERRUPTED",
            inferAgentTerminalStatusFromSummary(
                """{"status":"INTERRUPTED"}"""
            )
        )
    }

    @Test
    fun `cancelled worker TODOs return to retryable role-owned states`() {
        assertEquals(
            AgentTodoStatus.NEEDS_FIX to "CODER",
            AgentProjectControlPlane.cancelledTodoRecoveryForRole("CODER")
        )
        assertEquals(
            AgentTodoStatus.READY_FOR_REVIEW to "REVIEWER",
            AgentProjectControlPlane.cancelledTodoRecoveryForRole("REVIEWER")
        )
        assertEquals(
            AgentTodoStatus.READY_FOR_VERIFICATION to "EXECUTOR",
            AgentProjectControlPlane.cancelledTodoRecoveryForRole("EXECUTOR")
        )
        assertEquals(
            AgentTodoStatus.READY_FOR_VERIFICATION to "VISUAL_TESTER",
            AgentProjectControlPlane.cancelledTodoRecoveryForRole(
                "VISUAL_TESTER"
            )
        )
    }

    @Test
    fun `Plan runtime prompt explicitly permits bounded planning specialists`() {
        val prompt = buildAgentRuntimeModeControl(
            isPlanMode = true,
            isOrchestrator = true
        )

        assertTrue(prompt.contains("CODEBASE_SCOUT"))
        assertTrue(prompt.contains("RESEARCHER"))
        assertTrue(prompt.contains("PLANNER"))
        assertTrue(prompt.contains("omit todo_id"))
        assertTrue("Plan control prompt grew unexpectedly", prompt.length < 1_000)
    }

    @Test
    fun `workflow protocol tools cannot be disabled`() {
        listOf(
            "question",
            "call_agent",
            "propose_plan",
            "finish_task",
            "project_state_read",
            "todo_read",
            "todo_transition"
        ).forEach { tool ->
            assertTrue("$tool should be protocol-critical", isCriticalAgentProtocolTool(tool))
        }
        assertFalse(isCriticalAgentProtocolTool("web_search"))
        assertFalse(isCriticalAgentProtocolTool("generate_image"))
    }
}
