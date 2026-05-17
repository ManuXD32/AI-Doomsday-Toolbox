package com.example.llamadroid.ui.ai.llama

import androidx.annotation.StringRes
import com.example.llamadroid.R

data class LlamaBuiltInPromptProfile(
    val key: String,
    @StringRes val nameRes: Int,
    @StringRes val contentRes: Int
)

object LlamaBuiltInPromptProfiles {
    const val DEEP_RESEARCHER = "deep_researcher"
    const val RESEARCHER = "researcher"
    const val EXPERT_CODER = "expert_coder"
    const val STUDY_TUTOR = "study_tutor"
    const val CONCISE_ASSISTANT = "concise_assistant"
    const val KNOWLEDGE_CURATOR = "knowledge_curator"
    const val PERSONAL_ORGANIZER = "personal_organizer"
    const val PROJECT_PLANNER = "project_planner"
    const val MEETING_SECRETARY = "meeting_secretary"
    const val CREATIVE_IMAGE_DIRECTOR = "creative_image_director"
    const val DATA_ANALYST = "data_analyst"

    val all: List<LlamaBuiltInPromptProfile> = listOf(
        LlamaBuiltInPromptProfile(
            key = DEEP_RESEARCHER,
            nameRes = R.string.llama_prompt_profile_deep_researcher,
            contentRes = R.string.llama_prompt_profile_deep_researcher_prompt
        ),
        LlamaBuiltInPromptProfile(
            key = RESEARCHER,
            nameRes = R.string.llama_prompt_profile_researcher,
            contentRes = R.string.llama_prompt_profile_researcher_prompt
        ),
        LlamaBuiltInPromptProfile(
            key = EXPERT_CODER,
            nameRes = R.string.llama_prompt_profile_expert_coder,
            contentRes = R.string.llama_prompt_profile_expert_coder_prompt
        ),
        LlamaBuiltInPromptProfile(
            key = STUDY_TUTOR,
            nameRes = R.string.llama_prompt_profile_study_tutor,
            contentRes = R.string.llama_prompt_profile_study_tutor_prompt
        ),
        LlamaBuiltInPromptProfile(
            key = CONCISE_ASSISTANT,
            nameRes = R.string.llama_prompt_profile_concise_assistant,
            contentRes = R.string.llama_prompt_profile_concise_assistant_prompt
        ),
        LlamaBuiltInPromptProfile(
            key = KNOWLEDGE_CURATOR,
            nameRes = R.string.llama_prompt_profile_knowledge_curator,
            contentRes = R.string.llama_prompt_profile_knowledge_curator_prompt
        ),
        LlamaBuiltInPromptProfile(
            key = PERSONAL_ORGANIZER,
            nameRes = R.string.llama_prompt_profile_personal_organizer,
            contentRes = R.string.llama_prompt_profile_personal_organizer_prompt
        ),
        LlamaBuiltInPromptProfile(
            key = PROJECT_PLANNER,
            nameRes = R.string.llama_prompt_profile_project_planner,
            contentRes = R.string.llama_prompt_profile_project_planner_prompt
        ),
        LlamaBuiltInPromptProfile(
            key = MEETING_SECRETARY,
            nameRes = R.string.llama_prompt_profile_meeting_secretary,
            contentRes = R.string.llama_prompt_profile_meeting_secretary_prompt
        ),
        LlamaBuiltInPromptProfile(
            key = CREATIVE_IMAGE_DIRECTOR,
            nameRes = R.string.llama_prompt_profile_creative_image_director,
            contentRes = R.string.llama_prompt_profile_creative_image_director_prompt
        ),
        LlamaBuiltInPromptProfile(
            key = DATA_ANALYST,
            nameRes = R.string.llama_prompt_profile_data_analyst,
            contentRes = R.string.llama_prompt_profile_data_analyst_prompt
        )
    )
}
