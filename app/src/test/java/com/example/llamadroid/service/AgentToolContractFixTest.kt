package com.example.llamadroid.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentToolContractFixTest {

    @Test
    fun `finish task accepts declared plain summary and status for coder`() {
        val resolved = AgentRuntimeSupport.resolveFinishTaskPayload(
            agentLabel = "CODER",
            arguments = mapOf(
                "summary" to "Updated the requested implementation.",
                "status" to "SUCCESS"
            )
        )

        assertEquals("SUCCESS", resolved.result.status)
        assertTrue(resolved.result is AgentResult.GenericResult)
        assertEquals(
            "Updated the requested implementation.",
            (resolved.result as AgentResult.GenericResult).summary
        )
        val canonical = JSONObject(resolved.canonicalSummary)
        assertEquals("SUCCESS", canonical.getString("status"))
        assertEquals(
            "Updated the requested implementation.",
            canonical.getString("summary")
        )
    }

    @Test
    fun `finish task keeps valid structured coder report typed`() {
        val resolved = AgentRuntimeSupport.resolveFinishTaskPayload(
            agentLabel = "CODER",
            arguments = mapOf(
                "summary" to JSONObject()
                    .put("status", "SUCCESS")
                    .put("summary", "Implemented and verified the change.")
                    .put("changed_files", listOf("src/Main.kt"))
                    .put("intent_per_file", mapOf("src/Main.kt" to "Fix parser"))
                    .put("verification_reads", listOf("src/Main.kt"))
                    .put("remaining_risks", emptyList<String>())
                    .toString()
            )
        )

        assertTrue(resolved.result is AgentResult.CoderResult)
        assertEquals(
            listOf("src/Main.kt"),
            (resolved.result as AgentResult.CoderResult).changedFiles
        )
    }

    @Test
    fun `finish task accepts blocked plain report`() {
        val resolved = AgentRuntimeSupport.resolveFinishTaskPayload(
            agentLabel = "REVIEWER",
            arguments = mapOf(
                "summary" to "Required fixture is unavailable.",
                "status" to "BLOCKED"
            )
        )

        assertEquals("BLOCKED", resolved.result.status)
        assertTrue(resolved.result is AgentResult.GenericResult)
    }

    @Test
    fun `finish task rejects conflicting status sources`() {
        try {
            AgentRuntimeSupport.resolveFinishTaskPayload(
                agentLabel = "EXECUTOR",
                arguments = mapOf(
                    "summary" to """{"status":"FAILED","summary":"Command failed."}""",
                    "status" to "SUCCESS"
                )
            )
            fail("Expected conflicting status values to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("conflicts"))
        }
    }

    @Test
    fun `finish task rejects unsupported status`() {
        try {
            AgentRuntimeSupport.resolveFinishTaskPayload(
                agentLabel = "PLANNER",
                arguments = mapOf(
                    "summary" to "Finished.",
                    "status" to "DONE"
                )
            )
            fail("Expected unsupported status to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("must be SUCCESS"))
        }
    }

    @Test
    fun `directory listing always ends with explicit completion marker`() {
        assertEquals(
            "[dir] src\n[file] README.md\nthere is nothing else in here",
            AgentRuntimeSupport.formatDirectoryListing(
                listOf("[dir] src", "[file] README.md")
            )
        )
        assertEquals(
            "there is nothing else in here",
            AgentRuntimeSupport.formatDirectoryListing(emptyList())
        )
    }
}
