package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanProposalRecoveryTest {
    @Test
    fun `bounded implementation plan prose becomes an unapproved propose plan call`() {
        val response = """
            Research is complete.

            ## Implementation Plan: Prime calculator UI
            1. Create the static HTML, CSS, and JavaScript entry files.
            2. Add a worker that calculates primes and persists bounded results.
            3. Run the preview and verify the requested controls and counts.
        """.trimIndent()

        val recovery = recoverImplementationPlanProse(response)

        assertEquals(PlanProposalRecoveryDisposition.SUBMIT, recovery.disposition)
        assertEquals("PLAN_PROSE_HEADING_RECOVERED", recovery.reasonCode)
        val proposal = recovery.proposal
        assertNotNull(proposal)
        assertEquals("Prime calculator UI", proposal?.summary)
        assertTrue(proposal?.plan?.contains("1. Create") == true)
        assertTrue(proposal?.toolCallId?.startsWith("recovered-propose-plan-") == true)

        val call = proposal?.toToolCall()
        assertEquals("propose_plan", call?.name)
        assertEquals(proposal?.plan, call?.arguments?.get("plan"))
        assertEquals(proposal?.summary, call?.arguments?.get("summary"))
    }

    @Test
    fun `headingless numbered actionable plan gets a deterministic summary`() {
        val response = """
            1. Build the project-relative entrypoint.
            2. Run focused checks against the generated output.
        """.trimIndent()

        val recovery = recoverImplementationPlanProse(response)

        assertEquals(PlanProposalRecoveryDisposition.SUBMIT, recovery.disposition)
        assertEquals("PLAN_PROSE_NUMBERED_RECOVERED", recovery.reasonCode)
        assertEquals("Build the project-relative entrypoint.", recovery.proposal?.summary)
    }

    @Test
    fun `ambiguous and unresolved plan prose receives one exact repair instruction`() {
        val response = """
            Implementation Plan:
            1. Build the interface.
            2. Use the selected runtime.
            Which approach do you prefer?
        """.trimIndent()

        val recovery = recoverImplementationPlanProse(response)

        assertEquals(recovery.reasonCode, PlanProposalRecoveryDisposition.REPROMPT, recovery.disposition)
        assertEquals("PLAN_PROSE_UNRESOLVED_DECISION", recovery.reasonCode)
        assertTrue(recovery.instruction.orEmpty().contains("propose_plan"))
        assertTrue(recovery.instruction.orEmpty().contains("plan and summary"))
        assertTrue(recovery.instruction.orEmpty().contains("question"))
        assertTrue(recovery.instruction.orEmpty().contains("do not approve"))
        assertTrue(recovery.instruction.orEmpty().length <= 1_200)
    }

    @Test
    fun `under budget but non actionable plan is repaired instead of submitted`() {
        val recovery = recoverImplementationPlanProse(
            "Implementation Plan:\nThis is a plan without concrete steps."
        )

        assertEquals(recovery.reasonCode, PlanProposalRecoveryDisposition.REPROMPT, recovery.disposition)
        assertEquals("PLAN_PROSE_NOT_ACTIONABLE", recovery.reasonCode)
        assertNull(recovery.proposal)
    }

    @Test
    fun `oversized response is bounded before plan extraction`() {
        val recovery = recoverImplementationPlanProse("Implementation Plan:\n${"word ".repeat(4_000)}")

        assertEquals(PlanProposalRecoveryDisposition.REPROMPT, recovery.disposition)
        assertEquals("PLAN_PROSE_INPUT_TOO_LARGE", recovery.reasonCode)
        assertTrue(recovery.instruction.orEmpty().contains("4000 characters"))
    }

    @Test
    fun `research prose and explicit tool shapes stay with normal recovery`() {
        assertEquals(
            PlanProposalRecoveryDisposition.NO_MATCH,
            recoverImplementationPlanProse(
                "Research findings:\n1. The source describes the API.\n2. The source lists limits."
            ).disposition
        )
        assertEquals(
            PlanProposalRecoveryDisposition.NO_MATCH,
            recoverImplementationPlanProse(
                "```json\n{\"name\":\"propose_plan\",\"arguments\":{}}\n```"
            ).disposition
        )
        assertEquals(
            PlanProposalRecoveryDisposition.NO_MATCH,
            recoverImplementationPlanProse(
                "Implementation Plan:\n1. Build it.\n2. Verify it.",
                phase = AgentHarnessPhase.BUILD
            ).disposition
        )
    }

    @Test
    fun `plan contract names the approval boundary without approving it`() {
        assertTrue(OPTIMIZED_PLAN_REQUIRED_ACTION_CONTRACT.contains("propose_plan"))
        assertTrue(OPTIMIZED_PLAN_REQUIRED_ACTION_CONTRACT.contains("summary"))
        assertTrue(OPTIMIZED_PLAN_REQUIRED_ACTION_CONTRACT.contains("wait"))
        assertTrue(OPTIMIZED_PLAN_REQUIRED_ACTION_CONTRACT.contains("never approve"))
        val prompt = AgentHarnessPolicy.optimizedSystemPromptForPhase(AgentHarnessPhase.PLAN)
        assertTrue(prompt.contains("native structured propose_plan"))
        assertTrue(prompt.contains("required plan and summary"))
        assertTrue(prompt.contains(OPTIMIZED_PLAN_REQUIRED_ACTION_CONTRACT))
    }
}
