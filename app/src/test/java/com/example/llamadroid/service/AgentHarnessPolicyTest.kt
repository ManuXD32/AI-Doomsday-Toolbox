package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHarnessPolicyTest {
    @Test
    fun `all persisted profile ids resolve to direct`() {
        listOf("direct", "optimized", "legacy", "unknown", "", null).forEach { value ->
            assertEquals(AgentHarnessProfile.DIRECT, AgentHarnessProfile.fromId(value))
            assertEquals(AgentHarnessPolicy.DIRECT, AgentHarnessPolicy.normalizeProfileId(value))
            assertTrue(AgentHarnessPolicy.isDirect(value))
            assertTrue(AgentHarnessPolicy.isOptimized(value))
        }
    }

    @Test
    fun `every profile API returns the direct low-token policy`() {
        AgentHarnessProfile.entries.forEach { alias ->
            val policy = AgentHarnessPolicy.forProfile(alias)
            assertEquals(AgentHarnessProfile.DIRECT, policy.profile)
            assertEquals(16_384, policy.defaultContextTokens)
            assertEquals(2_048, policy.maxOutputTokens(AgentHarnessStage.CONTROL))
            assertEquals(4_096, policy.maxOutputTokens(AgentHarnessStage.BUILD))
            assertEquals(512, policy.maxOutputTokens(AgentHarnessStage.SUMMARY))
            assertEquals(false, policy.thinkingOverride)
            assertTrue(policy.optionalSequentialSpecialists.isEmpty())
        }
    }

    @Test
    fun `direct cache identity ignores phase role and turn branch`() {
        val plan = AgentHarnessPolicy.directPromptCacheKey(
            conversationId = 19L,
            backend = "llama-server",
            model = "spark-4b",
            endpointGeneration = "generation-2",
            coreSchemaHash = "schema",
            projectContextHash = "project"
        )
        val build = AgentHarnessPolicy.directPromptCacheKey(
            conversationId = 19L,
            backend = "llama-server",
            model = "spark-4b",
            endpointGeneration = "generation-2",
            coreSchemaHash = "schema",
            projectContextHash = "project"
        )
        assertEquals(plan, build)
        assertFalse(plan.contains("phase"))
        assertFalse(plan.contains("role"))
    }

    @Test
    fun `phase changes only the compact tail`() {
        val stable = AgentHarnessPolicy.directSystemPrompt()
        assertTrue(stable.isNotBlank())
        assertFalse(stable.contains("CURRENT MODE"))
        AgentHarnessPhase.entries.forEach { phase ->
            val tail = AgentHarnessPolicy.directCheckpointTail(phase)
            assertTrue(tail.contains("phase=${phase.name}"))
            assertTrue(tail.length < 180)
        }
    }

    @Test
    fun `direct budgeting enforces reserve and conservative fallback`() {
        assertEquals(1_250, AgentHarnessPolicy.directInputTokenCount("a".repeat(4_000)))
        assertTrue(AgentHarnessPolicy.directFitsContext(11_776, 4_096, 16_384))
        assertFalse(AgentHarnessPolicy.directFitsContext(11_777, 4_096, 16_384))
        assertEquals(11_776, AgentHarnessPolicy.directInputBudget(16_384, 4_096))
    }

    @Test
    fun `explicit direct limits are authoritative above recommendations`() {
        val policy = AgentHarnessPolicy.forProfile(AgentHarnessProfile.DIRECT)

        assertEquals(65_536, policy.resolveContextTokens(65_536, explicit = true))
        assertEquals(
            12_288,
            policy.resolveOutputTokens(
                AgentHarnessStage.BUILD,
                configuredOutputTokens = 12_288,
                explicit = true
            )
        )
        assertEquals(16_384, policy.resolveContextTokens(65_536, explicit = false))
        assertEquals(
            4_096,
            policy.resolveOutputTokens(
                AgentHarnessStage.BUILD,
                configuredOutputTokens = 12_288,
                explicit = false
            )
        )
    }

    @Test
    fun `direct phase outputs keep plan compact and honor the editable build default`() {
        assertEquals(
            2_048,
            AgentHarnessPolicy.resolveDirectPhaseOutputTokens(
                AgentHarnessStage.CONTROL,
                configuredOutputTokens = 7_000,
                globalOverrideEnabled = false
            )
        )
        assertEquals(
            7_000,
            AgentHarnessPolicy.resolveDirectPhaseOutputTokens(
                AgentHarnessStage.BUILD,
                configuredOutputTokens = 7_000,
                globalOverrideEnabled = false
            )
        )
        assertEquals(
            12_000,
            AgentHarnessPolicy.resolveDirectPhaseOutputTokens(
                AgentHarnessStage.CONTROL,
                configuredOutputTokens = 12_000,
                globalOverrideEnabled = true
            )
        )
    }

    @Test
    fun `greenfield discovery policy honors later corrections`() {
        assertTrue(
            AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                greenfield = true,
                initialGoal = "Build a new app.",
                corrections = emptyList()
            )
        )
        assertTrue(
            AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                greenfield = false,
                initialGoal = "Extend the existing app.",
                corrections = listOf("There is no need to scout the codebase; use named files only.")
            )
        )
        assertFalse(
            AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                greenfield = true,
                initialGoal = "Greenfield project; no need to scout the codebase.",
                corrections = listOf("Now inspect the existing codebase for the approved change.")
            )
        )
        assertTrue(AgentHarnessPolicy.isCodebaseDiscoveryTool("list_directory"))
        assertFalse(AgentHarnessPolicy.isCodebaseDiscoveryTool("read_file"))
    }

    @Test
    fun `direct greenfield build requires its first mutation before inspection`() {
        listOf("read_file", "list_directory", "search_code").forEach { tool ->
            assertTrue(
                AgentHarnessPolicy.shouldBlockDirectGreenfieldInspection(
                    phase = AgentHarnessPhase.BUILD,
                    codebaseDiscoverySuppressed = true,
                    hasCommittedArtifact = false,
                    toolName = tool
                )
            )
            assertFalse(
                AgentHarnessPolicy.shouldBlockDirectGreenfieldInspection(
                    phase = AgentHarnessPhase.BUILD,
                    codebaseDiscoverySuppressed = true,
                    hasCommittedArtifact = true,
                    toolName = tool
                )
            )
        }
        assertFalse(
            AgentHarnessPolicy.shouldBlockDirectGreenfieldInspection(
                phase = AgentHarnessPhase.BUILD,
                codebaseDiscoverySuppressed = true,
                hasCommittedArtifact = false,
                toolName = "write_file"
            )
        )
        assertFalse(
            AgentHarnessPolicy.shouldBlockDirectGreenfieldInspection(
                phase = AgentHarnessPhase.BUILD,
                codebaseDiscoverySuppressed = false,
                hasCommittedArtifact = false,
                toolName = "read_file"
            )
        )
    }

    @Test
    fun `direct greenfield build starts with write file`() {
        listOf("append_file", "edit_file", "edit_lines", "apply_patch", "create_folder", "run_project").forEach { tool ->
            assertTrue(
                AgentHarnessPolicy.shouldBlockDirectGreenfieldFirstMutation(
                    phase = AgentHarnessPhase.BUILD,
                    codebaseDiscoverySuppressed = true,
                    hasCommittedArtifact = false,
                    toolName = tool
                )
            )
        }
        listOf("write_file", "question", "tool_help", "finish_task").forEach { tool ->
            assertFalse(
                AgentHarnessPolicy.shouldBlockDirectGreenfieldFirstMutation(
                    phase = AgentHarnessPhase.BUILD,
                    codebaseDiscoverySuppressed = true,
                    hasCommittedArtifact = false,
                    toolName = tool
                )
            )
        }
        assertFalse(
            AgentHarnessPolicy.shouldBlockDirectGreenfieldFirstMutation(
                phase = AgentHarnessPhase.BUILD,
                codebaseDiscoverySuppressed = true,
                hasCommittedArtifact = true,
                toolName = "append_file"
            )
        )
    }

    @Test
    fun `local guidance remains WebUI first and proot free`() {
        val guidance = AgentHarnessPolicy.OPTIMIZED_LOCAL_SANDBOX_GUIDANCE
        assertTrue(guidance.contains("runtime=web"))
        assertTrue(guidance.contains("Web Workers"))
        assertTrue(guidance.contains("IndexedDB"))
        assertTrue(guidance.contains("bounded export chunks"))
        assertFalse(guidance.contains("PRoot", ignoreCase = true))
    }
}
