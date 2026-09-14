package com.example.llamadroid.service

import com.example.llamadroid.data.db.AgentProjectStateEntity
import com.example.llamadroid.data.db.AgentPlanVersionEntity
import org.json.JSONObject
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
    fun `approved plan packet section preserves full markdown identity and hash`() {
        val markdown = """
            # Approved plan
            - Implement the bounded state projection.
            ## Tail constraints
            - Preserve this exact final constraint.
        """.trimIndent()
        val plan = AgentPlanVersionEntity(
            id = "plan-packet-77",
            conversationId = 77L,
            versionNumber = 3,
            summary = "Bounded state projection",
            planMarkdown = markdown,
            structuredJson = "{}",
            planHash = "full-plan-hash-77"
        )

        val section = AgentProjectControlPlane.renderApprovedPlanControlPacketSection(plan)
        val payloadLine = section.lines().single {
            it.startsWith("- full_plan_json:")
        }
        val payload = JSONObject(payloadLine.substringAfter(":").trim())

        assertEquals("plan-packet-77", payload.optString("id"))
        assertEquals(3, payload.optInt("version"))
        assertEquals("full-plan-hash-77", payload.optString("hash"))
        assertEquals(markdown, payload.optString("plan_markdown"))
        assertTrue(section.contains("- hash: full-plan-h"))
    }

    @Test
    fun `llama server serializer preserves full plan tail value`() {
        val markdown = """
            # Approved plan
            - Implement the bounded state projection.
            ## Tail constraints
            - Preserve this exact final serializer constraint.
        """.trimIndent()
        val plan = AgentPlanVersionEntity(
            id = "plan-serialized-77",
            conversationId = 77L,
            versionNumber = 2,
            summary = "Serialized approved plan",
            planMarkdown = markdown,
            structuredJson = "{}",
            planHash = "full-serialized-hash-77"
        )
        val section = AgentProjectControlPlane.renderApprovedPlanControlPacketSection(plan)
        val payload = buildLlamaServerChatRequestPayload(
            messages = listOf(
                OllamaService.ChatMessage(
                    role = "system",
                    content = section
                )
            ),
            tools = emptyList(),
            model = "fixture",
            thinkingEnabled = false
        )
        @Suppress("UNCHECKED_CAST")
        val serializedMessage = (payload["messages"] as List<Map<String, Any?>>).single()
        val serializedContent = serializedMessage["content"] as String
        val payloadLine = serializedContent.lines().single {
            it.startsWith("- full_plan_json:")
        }

        assertEquals(
            markdown,
            JSONObject(payloadLine.substringAfter(":").trim())
                .optString("plan_markdown")
        )
        assertTrue(serializedContent.contains("Preserve this exact final serializer constraint."))
    }

    @Test
    fun `optional report section is omitted rather than exposing a partial id`() {
        val required = "# Project Control Packet\n- goal: retain exact state"
        val report = "## Recent Specialist Reports\n- report-opaque-" + "z".repeat(400)
        val tail = "## Permitted Next Actions\n- continue approved step"
        val packed = AgentProjectControlPlane.boundedSectionsWithReservedTail(
            prefixSections = listOf(required, report),
            reservedTail = tail,
            maxChars = 260,
            protectedSectionCount = 1
        )
        assertTrue(packed.contains(required))
        assertTrue(packed.contains(tail))
        assertFalse(packed.contains("report-opaque-"))
    }

    @Test
    fun `oversized protected plan returns visible capacity marker and keeps action tail when it fits`() {
        val markdown = """
            # Approved plan
            - Implement the bounded state projection.
            ## Tail constraints
            - Preserve this exact final constraint.
        """.trimIndent()
        val plan = AgentPlanVersionEntity(
            id = "plan-packet-88",
            conversationId = 88L,
            versionNumber = 1,
            summary = "Bounded state projection",
            planMarkdown = markdown,
            structuredJson = "{}",
            planHash = "full-plan-hash-88"
        )
        val contract = "## Durable User Contract\n- initial_goal: Keep this exact."
        val approvedPlan = AgentProjectControlPlane
            .renderApprovedPlanControlPacketSection(plan)
        val tail = "## Permitted Next Actions\n- preserve-tail-opaque-id"

        val fitting = AgentProjectControlPlane.boundedSectionsWithReservedTail(
            prefixSections = listOf(contract, approvedPlan),
            reservedTail = tail,
            maxChars = contract.length + approvedPlan.length + tail.length + 4,
            protectedSectionCount = 2
        )
        assertTrue(fitting.contains("Preserve this exact final constraint."))
        assertTrue(fitting.contains("preserve-tail-opaque-id"))

        val tooSmall = AgentProjectControlPlane.boundedSectionsWithReservedTail(
            prefixSections = listOf(contract, approvedPlan),
            reservedTail = tail,
            maxChars = contract.length + approvedPlan.length + tail.length - 1,
            protectedSectionCount = 2
        )
        assertTrue(tooSmall.contains("control_state_does_not_fit: true"))
        assertTrue(tooSmall.contains("durable_contract_plus_next_actions_chars:"))
    }

    @Test
    fun `direct aliases omit research prose in Build and Verify but retain it in Plan`() {
        assertFalse(
            AgentProjectControlPlane.shouldIncludeResearchBudgetInControlPacket(
                executionProfile = "optimized",
                mode = "BUILD"
            )
        )
        assertFalse(
            AgentProjectControlPlane.shouldIncludeResearchBudgetInControlPacket(
                executionProfile = "optimized",
                mode = "verify"
            )
        )
        assertTrue(
            AgentProjectControlPlane.shouldIncludeResearchBudgetInControlPacket(
                executionProfile = "optimized",
                mode = "PLAN"
            )
        )
        assertFalse(
            AgentProjectControlPlane.shouldIncludeResearchBudgetInControlPacket(
                executionProfile = "legacy",
                mode = "BUILD"
            )
        )
    }

    @Test
    fun `optimized TODO projection makes generated verification explicit and keeps other criteria`() {
        val todoText = "Implement the approved static build with the exact current constraints."
        val generated = "Complete and verify: ${todoText.take(240)}"
        val otherCriteria = (1..7).map { index ->
            "Preserve criterion $index exactly."
        }

        val projection = AgentProjectControlPlane
            .projectTodoAcceptanceCriteriaForPrompt(
                todoText = todoText,
                criteria = listOf(generated) + otherCriteria,
                optimized = true
            )

        assertTrue(projection.verificationRequired)
        assertEquals(otherCriteria, projection.criteria)

        val nearMatch = AgentProjectControlPlane
            .projectTodoAcceptanceCriteriaForPrompt(
                todoText = todoText,
                criteria = listOf("$generated ") + otherCriteria,
                optimized = true
            )
        assertFalse(nearMatch.verificationRequired)
        assertEquals(listOf("$generated ") + otherCriteria, nearMatch.criteria)
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
