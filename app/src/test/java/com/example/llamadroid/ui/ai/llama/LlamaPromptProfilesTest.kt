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
                LlamaBuiltInPromptProfiles.DATA_ANALYST
            ),
            keys
        )
        assertTrue(LlamaBuiltInPromptProfiles.all.all { it.nameRes != 0 && it.contentRes != 0 })
    }
}
