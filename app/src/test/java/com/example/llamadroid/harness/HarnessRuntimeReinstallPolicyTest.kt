package com.example.llamadroid.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessRuntimeReinstallPolicyTest {
    @Test
    fun resetUsesAnExplicitCompleteAgentTableAllowlist() {
        val expected = setOf(
            "agent_compactions",
            "agent_continuation_outbox",
            "agent_conversations",
            "agent_decisions",
            "agent_harness_runtime",
            "agent_harness_sessions",
            "agent_harness_workspaces",
            "agent_invocations",
            "agent_message_parts",
            "agent_messages",
            "agent_pending_inputs",
            "agent_pending_plans",
            "agent_pending_questions",
            "agent_plan_versions",
            "agent_project_contracts",
            "agent_project_events",
            "agent_project_folders",
            "agent_project_runs",
            "agent_project_states",
            "agent_proot_environments",
            "agent_proot_runs",
            "agent_runtime_endpoint_configs",
            "agent_runtime_profiles",
            "agent_skill_assignments",
            "agent_skills",
            "agent_sleep_wakes",
            "agent_todos",
            "agent_turn_contexts",
            "agent_work_reports",
            "ai_runtime_jobs",
        )

        val actual = HarnessRuntimeRecordReset.tablesInDeleteOrder
        assertEquals(actual.size, actual.toSet().size)
        assertEquals(expected, actual.toSet())
        assertTrue(actual.indexOf("agent_messages") < actual.indexOf("agent_conversations"))
        assertTrue(actual.indexOf("agent_harness_sessions") < actual.indexOf("agent_harness_workspaces"))
    }
}
