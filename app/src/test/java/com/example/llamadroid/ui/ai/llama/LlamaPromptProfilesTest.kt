package com.example.llamadroid.ui.ai.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaPromptProfilesTest {
    @Test
    fun `built in prompt profiles are stable and read only examples`() {
        val keys = LlamaBuiltInPromptProfiles.all.map { it.key }

        assertEquals(
            listOf(
                LlamaBuiltInPromptProfiles.DEEP_RESEARCHER,
                LlamaBuiltInPromptProfiles.RESEARCHER,
                LlamaBuiltInPromptProfiles.EXPERT_CODER,
                LlamaBuiltInPromptProfiles.STUDY_TUTOR,
                LlamaBuiltInPromptProfiles.CONCISE_ASSISTANT,
                LlamaBuiltInPromptProfiles.KNOWLEDGE_CURATOR,
                LlamaBuiltInPromptProfiles.PERSONAL_ORGANIZER,
                LlamaBuiltInPromptProfiles.PROJECT_PLANNER,
                LlamaBuiltInPromptProfiles.MEETING_SECRETARY,
                LlamaBuiltInPromptProfiles.CREATIVE_IMAGE_DIRECTOR,
                LlamaBuiltInPromptProfiles.DATA_ANALYST,
                LlamaBuiltInPromptProfiles.WORLD_BUILDER,
                LlamaBuiltInPromptProfiles.WORLD_CODER,
                LlamaBuiltInPromptProfiles.WORLD_CODER_GLB
            ),
            keys
        )
        assertTrue(LlamaBuiltInPromptProfiles.all.all { it.nameRes != 0 && it.contentRes != 0 })
    }

    @Test
    fun `file tool profile turns on tools and file tools and keeps other params`() {
        val merged = org.json.JSONObject(mergeProfileToolParams("""{"temperature":0.5}""", true)!!)
        assertTrue(merged.getBoolean("tools_enabled"))
        assertTrue(merged.getBoolean("tool_file_tools_enabled"))
        assertEquals(0.5, merged.getDouble("temperature"), 0.0)
        assertTrue(org.json.JSONObject(mergeProfileToolParams(null, true)!!).getBoolean("tool_file_tools_enabled"))
    }

    @Test
    fun `other profiles never turn tools off`() {
        val params = """{"tools_enabled":true,"tool_file_tools_enabled":true}"""
        assertEquals(params, mergeProfileToolParams(params, false))
        assertEquals(null, mergeProfileToolParams(null, false))
    }

    @Test
    fun `world builder requires file tools`() {
        listOf(
            LlamaBuiltInPromptProfiles.WORLD_BUILDER,
            LlamaBuiltInPromptProfiles.WORLD_CODER,
            LlamaBuiltInPromptProfiles.WORLD_CODER_GLB
        ).forEach { key -> assertTrue(key, LlamaBuiltInPromptProfiles.all.first { it.key == key }.requiresFileTools) }
    }
}
