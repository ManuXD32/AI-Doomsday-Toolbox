package com.example.llamadroid.ui.ai.llama

import androidx.annotation.StringRes
import com.example.llamadroid.R
import com.example.llamadroid.service.NativeChatToolConfig
import org.json.JSONObject

data class LlamaBuiltInPromptProfile(
    val key: String,
    @StringRes val nameRes: Int,
    @StringRes val contentRes: Int,
    /** True when the prompt relies on the chat workspace file tools (search/read/replace), so picking it should turn them on. */
    val requiresFileTools: Boolean = false
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
    const val WORLD_BUILDER = "world_builder"
    const val WORLD_CODER = "world_coder"
    const val WORLD_CODER_GLB = "world_coder_glb"

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
        ),
        LlamaBuiltInPromptProfile(
            key = WORLD_BUILDER,
            nameRes = R.string.llama_prompt_profile_world_builder,
            contentRes = R.string.llama_prompt_profile_world_builder_prompt,
            requiresFileTools = true
        ),
        // The two 3D coder models are trained on fixed prompts; these carry them verbatim plus an
        // editing section. Recommended samplers are not part of a profile (no field for them).
        LlamaBuiltInPromptProfile(
            key = WORLD_CODER,
            nameRes = R.string.llama_prompt_profile_world_coder,
            contentRes = R.string.llama_prompt_profile_world_coder_prompt,
            requiresFileTools = true
        ),
        LlamaBuiltInPromptProfile(
            key = WORLD_CODER_GLB,
            nameRes = R.string.llama_prompt_profile_world_coder_glb,
            contentRes = R.string.llama_prompt_profile_world_coder_glb_prompt,
            requiresFileTools = true
        )
    )
}

/**
 * Merges the effect of picking a prompt profile into a chat's `apiParams` JSON. A profile that
 * [requiresFileTools] turns on tools and the workspace file tools; any other profile leaves the
 * params untouched (switching away never turns tools off).
 */
internal fun mergeProfileToolParams(apiParams: String?, requiresFileTools: Boolean): String? {
    if (!requiresFileTools) return apiParams
    val json = runCatching { apiParams?.takeIf { it.isNotBlank() }?.let(::JSONObject) }.getOrNull() ?: JSONObject()
    json.put(NativeChatToolConfig.KEY_TOOLS_ENABLED, true)
    json.put(NativeChatToolConfig.KEY_FILE_TOOLS_ENABLED, true)
    return json.toString()
}
