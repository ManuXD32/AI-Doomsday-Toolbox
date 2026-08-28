package com.example.llamadroid.service

import com.example.llamadroid.ui.settings.calculateLlamaParallelContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanApprovalAndContextTest {
    @Test
    fun contextPerSequenceDefaultsToOneParallel() {
        val result = calculateLlamaParallelContext(
            totalContext = 32_768,
            configuredParallel = null
        )

        assertEquals(1, result.parallel)
        assertEquals(32_768, result.contextPerSequence)
        assertEquals(0, result.remainder)
    }

    @Test
    fun contextPerSequenceUsesFloorAndReportsRemainder() {
        val result = calculateLlamaParallelContext(
            totalContext = 16_385,
            configuredParallel = 4
        )

        assertEquals(4, result.parallel)
        assertEquals(4_096, result.contextPerSequence)
        assertEquals(1, result.remainder)
    }

    @Test
    fun markdownPlanParsesHeadingsCheckboxesAndNumbersWithoutRegex() {
        val parsed = AgentProjectControlPlane.parseApprovedPlan(
            summary = "Polish the game",
            markdown = """
                ## Gameplay [safe even with unmatched regex characters: ( [ *
                - [ ] Add a drop interval controller.
                - [x] Recalculate the interval after cleared lines.

                ### Final polish
                1. Review transitions around microstep 5.3 and keep feedback clear.
                2) Verify the finished interaction on a phone-sized viewport.
            """.trimIndent()
        )

        assertEquals(4, parsed.todos.size)
        assertTrue(parsed.todos[0].text.contains("drop interval"))
        assertTrue(parsed.todos[2].text.contains("5.3"))
        assertEquals("Final polish", parsed.todos[2].phaseTitle)
    }

    @Test
    fun fencedStructuredPlanStillParses() {
        val parsed = AgentProjectControlPlane.parseApprovedPlan(
            summary = "Fallback summary",
            markdown = """
                ```json
                {
                  "plan_version": "plan-user-v1",
                  "summary": "Structured plan",
                  "phases": [
                    {
                      "id": "phase-ui",
                      "title": "UI",
                      "todos": [
                        {
                          "id": "todo-ui-1",
                          "text": "Add a stable modal plan editor.",
                          "owner_role": "CODER",
                          "dependencies": [],
                          "acceptance_criteria": ["Save and Cancel are reachable."],
                          "priority": "HIGH"
                        }
                      ]
                    }
                  ]
                }
                ```
            """.trimIndent()
        )

        assertEquals("plan-user-v1", parsed.id)
        assertEquals("Structured plan", parsed.summary)
        assertEquals(1, parsed.todos.size)
        assertEquals("todo-ui-1", parsed.todos.single().id)
    }

    @Test
    fun proseFallbackDoesNotSplitDecimalMicrosteps() {
        val parsed = AgentProjectControlPlane.parseApprovedPlan(
            summary = "Fallback",
            markdown = "Implement microstep 5.3 carefully. Then verify the complete interaction on mobile."
        )

        assertEquals(2, parsed.todos.size)
        assertTrue(parsed.todos.first().text.contains("5.3"))
    }
}
