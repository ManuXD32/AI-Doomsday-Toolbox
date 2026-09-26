package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanBudgetSupportTest {
    @Test
    fun `accepts a plan at the character limit`() {
        assertTrue(AgentPlanBudgetSupport.isWithinBudget("x".repeat(AgentPlanBudgetSupport.MAX_CHARS)))
    }

    @Test
    fun `rejects a plan over the character limit`() {
        assertFalse(AgentPlanBudgetSupport.isWithinBudget("x".repeat(AgentPlanBudgetSupport.MAX_CHARS + 1)))
    }

    @Test
    fun `accepts exactly the word limit across multiline whitespace`() {
        val plan = List(AgentPlanBudgetSupport.MAX_WORDS) { "x" }
            .joinToString(separator = "\n\n\t")

        assertTrue(AgentPlanBudgetSupport.isWithinBudget(plan))
    }

    @Test
    fun `rejects a plan over the word limit`() {
        val plan = List(AgentPlanBudgetSupport.MAX_WORDS + 1) { "x" }
            .joinToString(separator = " ")

        assertFalse(AgentPlanBudgetSupport.isWithinBudget(plan))
    }

    @Test
    fun `repeated whitespace does not create empty words`() {
        val plan = "  first\n\n\tsecond  "

        assertTrue(AgentPlanBudgetSupport.isWithinBudget(plan))
    }
}
