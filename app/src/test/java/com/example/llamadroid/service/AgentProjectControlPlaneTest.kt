package com.example.llamadroid.service

import com.example.llamadroid.data.db.AgentProjectStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProjectControlPlaneTest {
    @Test
    fun `markdown plan creates stable sequential todo ids`() {
        val plan = """
            # Context repair

            ## State engine
            1. Add a canonical project control state.
            2. Link every delegation to a durable TODO.

            ## Verification
            - Run focused migration and workflow tests.
        """.trimIndent()

        val first = AgentProjectControlPlane.parseApprovedPlan(
            "Repair context handling",
            plan
        )
        val second = AgentProjectControlPlane.parseApprovedPlan(
            "Repair context handling",
            plan
        )

        assertEquals(first.planHash, second.planHash)
        assertEquals(first.todos.map { it.id }, second.todos.map { it.id })
        assertEquals(3, first.todos.size)
        assertTrue(first.todos.first().dependencies.isEmpty())
        assertEquals(
            listOf(first.todos.first().id),
            first.todos[1].dependencies
        )
    }

    @Test
    fun `orchestrator tool bundle contains no raw code command or web tools`() {
        val tools = AgentProjectControlPlane.allowedToolsForRole(
            role = "ORCHESTRATOR",
            localBackend = false
        )

        assertTrue("project_state_read" in tools)
        assertTrue("call_agent" in tools)
        assertTrue("todo_write" in tools)
        assertFalse("read_file" in tools)
        assertFalse("search_code" in tools)
        assertFalse("run_command" in tools)
        assertFalse("web_search" in tools)
    }

    @Test
    fun `specialists receive isolated capability bundles`() {
        val scout = AgentProjectControlPlane.allowedToolsForRole(
            "CODEBASE_SCOUT",
            localBackend = false
        )
        val researcher = AgentProjectControlPlane.allowedToolsForRole(
            "RESEARCHER",
            localBackend = false
        )
        val planner = AgentProjectControlPlane.allowedToolsForRole(
            "PLANNER",
            localBackend = false
        )

        assertTrue("search_code" in scout)
        assertFalse("write_file" in scout)
        assertTrue("web_search" in researcher)
        assertFalse("write_file" in researcher)
        assertTrue("project_state_read" in planner)
        assertFalse("search_code" in planner)
    }

    @Test
    fun `same semantic revision cannot hard compact repeatedly`() {
        AgentProjectControlPlane.cacheState(
            AgentProjectStateEntity(
                conversationId = 42L,
                revision = 8L,
                semanticEventCount = 15L,
                lastCompactedRevision = 8L,
                lastCompactionSemanticEventCount = 15L,
                lastCompactionKey = "42|8|root|tools",
                lastCompactionStatus = AgentCompactionStatus.APPLIED
            )
        )

        val decision = AgentProjectControlPlane.compactionDecision(
            conversationId = 42L,
            percentUsed = 96,
            thresholdPercent = 75,
            emergencyThresholdPercent = 90,
            rootTurnId = "root",
            toolDefinitionsHash = "tools"
        )

        assertFalse(decision.shouldCompact)
        assertEquals(
            "same_semantic_revision_already_compacted",
            decision.reason
        )
    }

    @Test
    fun `new semantic revision permits another compaction`() {
        AgentProjectControlPlane.cacheState(
            AgentProjectStateEntity(
                conversationId = 43L,
                revision = 9L,
                semanticEventCount = 16L,
                lastCompactedRevision = 8L,
                lastCompactionSemanticEventCount = 15L,
                lastCompactionStatus = AgentCompactionStatus.APPLIED
            )
        )

        val decision = AgentProjectControlPlane.compactionDecision(
            conversationId = 43L,
            percentUsed = 80,
            thresholdPercent = 75,
            emergencyThresholdPercent = 90,
            rootTurnId = "root-2",
            toolDefinitionsHash = "tools-2"
        )

        assertTrue(decision.shouldCompact)
        assertEquals("semantic_threshold", decision.reason)
    }

    @Test
    fun `compact document reference is bounded and hash addressed`() {
        val reference = AgentProjectControlPlane.compactDocumentReference(
            title = "Approved Plan",
            content = buildString {
                appendLine("# Plan")
                repeat(100) { index ->
                    appendLine("- Step $index: perform a detailed change.")
                }
            },
            maxChars = 900
        )

        requireNotNull(reference)
        assertTrue(reference.length <= 900)
        assertTrue(reference.contains("hash:"))
        assertTrue(reference.contains("full_document_is_durable: true"))
    }

    @Test
    fun `structured JSON plan preserves explicit ownership and criteria`() {
        val plan = """
            {
              "plan_version":"plan-control-v1",
              "summary":"Control-plane migration",
              "phases":[
                {
                  "id":"phase-research",
                  "title":"Research",
                  "todos":[
                    {
                      "id":"todo-research-1",
                      "text":"Verify the current external API.",
                      "owner_role":"RESEARCHER",
                      "dependencies":[],
                      "acceptance_criteria":["Primary source recorded"]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = AgentProjectControlPlane.parseApprovedPlan(
            "Control-plane migration",
            plan
        )

        assertEquals("plan-control-v1", parsed.id)
        assertEquals("RESEARCHER", parsed.todos.single().ownerRole)
        assertEquals(
            listOf("Primary source recorded"),
            parsed.todos.single().acceptanceCriteria
        )
    }
}
