package com.example.llamadroid.service

import org.junit.Assert.*
import org.junit.Test

class AgentActionBudgetTest {
    @Test fun unchangedStatePausesAfterThreeActionsAndNewEvidenceResets() {
        val budget = AgentActionBudget()
        assertFalse(budget.record("project_state", emptyMap(), "same state", true))
        repeat(2) { assertFalse(budget.record("project_state", emptyMap(), "same state", true)) }
        assertTrue(budget.record("project_state", emptyMap(), "same state", true))
        assertFalse(budget.record("write_file", mapOf("path" to "index.html"), "written", true))
    }
    @Test fun searchAndFetchBudgetsAreIndependentAndResetOnlyExplicitly() {
        val budget = AgentActionBudget()
        repeat(2) { assertTrue(budget.admitResearch("web_search")) }
        assertFalse(budget.admitResearch("kiwix_search"))
        repeat(4) { assertTrue(budget.admitResearch("fetch_url")) }
        assertFalse(budget.admitResearch("fetch_url"))
        budget.reset()
        assertTrue(budget.admitResearch("web_search"))
    }
    @Test fun failedActionsNeverCountAsEvidence() {
        val budget = AgentActionBudget()
        repeat(2) { assertFalse(budget.record("fetch_url", emptyMap(), "error $it", false)) }
        assertTrue(budget.record("fetch_url", emptyMap(), "new error", false))
    }
    @Test fun statePollingCannotUseChangingTimestampsAsProgress() {
        val budget = AgentActionBudget()
        assertFalse(budget.record("project_state_read", emptyMap(), "timestamp=1", true))
        assertFalse(budget.record("project_state_read", emptyMap(), "timestamp=2", true))
        assertFalse(budget.record("project_state_read", emptyMap(), "timestamp=3", true))
        assertTrue(budget.record("project_state_read", emptyMap(), "timestamp=4", true))
    }
    @Test fun sourceWhitespaceAndCaseChangesRemainNewEvidence() {
        val budget = AgentActionBudget()
        listOf("value", "Value", "VALUE", " Value", "  Value").forEach { content ->
            assertFalse(budget.record("write_file", mapOf("path" to "main.py", "content" to content), "written", true))
        }
    }
}
