package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.R
import com.example.llamadroid.ui.navigation.Screen

/** Round 7 guides for the Chats root, Audio, queue, delegation viewer, server editor, Servers hub, and search. */
internal object FeatureRound7Guides {
    val guides: List<FeatureGuide> = listOf(
        FeatureGuide(
            id = "audio_workspace",
            titleRes = R.string.feature_guide_r7_audio_workspace_title,
            route = Screen.AudioWorkspace.route,
            routeBases = setOf(Screen.AudioWorkspace.route),
            recipes = listOf(
                FeatureRecipe(
                    id = "r7.audio_workspace_create",
                    titleRes = R.string.feature_recipe_r7_audio_workspace_create_title,
                    steps = listOf(
                        r7Step("r7.audio_workspace_create.orient", R.string.feature_step_purpose_title, R.string.feature_step_r7_audio_workspace_create_orient, "audio_workspace", Screen.AudioWorkspace.route),
                        r7Step("r7.audio_workspace_create.read", R.string.feature_step_result_title, R.string.feature_step_r7_audio_workspace_create_read, "audio_workspace"),
                        r7Step("r7.audio_workspace_create.recover", R.string.feature_step_recover_title, R.string.feature_step_r7_audio_workspace_create_recover, "audio_workspace")
                    )
                ),
                FeatureRecipe(
                    id = "r7.audio_workspace_queue",
                    titleRes = R.string.feature_recipe_r7_audio_workspace_queue_title,
                    steps = listOf(
                        r7Step("r7.audio_workspace_queue.orient", R.string.feature_step_purpose_title, R.string.feature_step_r7_audio_workspace_queue_orient, "audio_workspace", Screen.AudioWorkspace.route),
                        r7Step("r7.audio_workspace_queue.read", R.string.feature_step_result_title, R.string.feature_step_r7_audio_workspace_queue_read, "audio_workspace"),
                        r7Step("r7.audio_workspace_queue.recover", R.string.feature_step_recover_title, R.string.feature_step_r7_audio_workspace_queue_recover, "audio_workspace")
                    )
                )
            )
        ),
        FeatureGuide(
            id = "audio_models",
            titleRes = R.string.feature_guide_r7_audio_models_title,
            route = Screen.AudioModels.route,
            routeBases = setOf(Screen.AudioModels.route),
            recipes = listOf(
                FeatureRecipe(
                    id = "r7.audio_models_install",
                    titleRes = R.string.feature_recipe_r7_audio_models_install_title,
                    steps = listOf(
                        r7Step("r7.audio_models_install.orient", R.string.feature_step_purpose_title, R.string.feature_step_r7_audio_models_install_orient, "audio_workspace", Screen.AudioModels.route),
                        r7Step("r7.audio_models_install.read", R.string.feature_step_result_title, R.string.feature_step_r7_audio_models_install_read, "audio_workspace"),
                        r7Step("r7.audio_models_install.recover", R.string.feature_step_recover_title, R.string.feature_step_r7_audio_models_install_recover, "audio_workspace")
                    )
                )
            )
        ),
        FeatureGuide(
            id = "generation_queue",
            titleRes = R.string.feature_guide_r7_generation_queue_title,
            route = Screen.GenerationQueue.route,
            routeBases = setOf(Screen.GenerationQueue.route),
            recipes = listOf(
                FeatureRecipe(
                    id = "r7.generation_queue_follow",
                    titleRes = R.string.feature_recipe_r7_generation_queue_follow_title,
                    steps = listOf(
                        r7Step("r7.generation_queue_follow.orient", R.string.feature_step_purpose_title, R.string.feature_step_r7_generation_queue_follow_orient, "tools", Screen.GenerationQueue.route),
                        r7Step("r7.generation_queue_follow.read", R.string.feature_step_result_title, R.string.feature_step_r7_generation_queue_follow_read, "tools"),
                        r7Step("r7.generation_queue_follow.recover", R.string.feature_step_recover_title, R.string.feature_step_r7_generation_queue_follow_recover, "tools")
                    )
                )
            )
        ),
        FeatureGuide(
            id = "native_chat_delegation",
            titleRes = R.string.feature_guide_r7_native_chat_delegation_title,
            route = "native_chat_delegation",
            routeBases = setOf("native_chat_delegation"),
            recipes = listOf(
                FeatureRecipe(
                    id = "r7.native_chat_delegation_watch",
                    titleRes = R.string.feature_recipe_r7_native_chat_delegation_watch_title,
                    steps = listOf(
                        r7Step("r7.native_chat_delegation_watch.orient", R.string.feature_step_purpose_title, R.string.feature_step_r7_native_chat_delegation_watch_orient, "chat", "native_chat_delegation"),
                        r7Step("r7.native_chat_delegation_watch.read", R.string.feature_step_result_title, R.string.feature_step_r7_native_chat_delegation_watch_read, "chat"),
                        r7Step("r7.native_chat_delegation_watch.recover", R.string.feature_step_recover_title, R.string.feature_step_r7_native_chat_delegation_watch_recover, "chat")
                    )
                ),
                FeatureRecipe(
                    id = "r7.native_chat_delegation_correct",
                    titleRes = R.string.feature_recipe_r7_delegation_correct_title,
                    steps = listOf(
                        r7Step("r7.native_chat_delegation_correct.orient", R.string.feature_step_purpose_title, R.string.feature_step_r7_delegation_correct_orient, "chat"),
                        r7Step("r7.native_chat_delegation_correct.read", R.string.feature_step_result_title, R.string.feature_step_r7_delegation_correct_read, "chat"),
                        r7Step("r7.native_chat_delegation_correct.recover", R.string.feature_step_recover_title, R.string.feature_step_r7_delegation_correct_recover, "chat")
                    )
                ),
                FeatureRecipe(
                    id = "r7.native_chat_delegation_files",
                    titleRes = R.string.feature_recipe_r7_delegation_files_title,
                    steps = listOf(
                        r7Step("r7.native_chat_delegation_files.orient", R.string.feature_step_purpose_title, R.string.feature_step_r7_delegation_files_orient, "chat"),
                        r7Step("r7.native_chat_delegation_files.read", R.string.feature_step_result_title, R.string.feature_step_r7_delegation_files_read, "chat"),
                        r7Step("r7.native_chat_delegation_files.recover", R.string.feature_step_recover_title, R.string.feature_step_r7_delegation_files_recover, "chat")
                    )
                )
            )
        ),
        FeatureGuide(
            id = "server_profile_editor",
            titleRes = R.string.feature_guide_r7_server_profile_editor_title,
            route = "server_profile_editor",
            routeBases = setOf("server_profile_editor"),
            recipes = listOf(
                FeatureRecipe(
                    id = "r7.server_profile_editor_edit",
                    titleRes = R.string.feature_recipe_r7_server_profile_editor_edit_title,
                    steps = listOf(
                        r7Step("r7.server_profile_editor_edit.orient", R.string.feature_step_purpose_title, R.string.feature_step_r7_server_profile_editor_edit_orient, "chat", "server_profile_editor"),
                        r7Step("r7.server_profile_editor_edit.read", R.string.feature_step_result_title, R.string.feature_step_r7_server_profile_editor_edit_read, "chat"),
                        r7Step("r7.server_profile_editor_edit.recover", R.string.feature_step_recover_title, R.string.feature_step_r7_server_profile_editor_edit_recover, "chat")
                    )
                )
            )
        ),
        FeatureGuide(
            id = "servers_hub",
            titleRes = R.string.feature_guide_r7_servers_hub_title,
            route = "servers_hub",
            routeBases = setOf("servers_hub"),
            recipes = listOf(
                FeatureRecipe(
                    id = "r7.servers_hub_overview",
                    titleRes = R.string.feature_recipe_r7_servers_hub_overview_title,
                    steps = listOf(
                        r7Step("r7.servers_hub_overview.orient", R.string.feature_step_purpose_title, R.string.feature_step_r7_servers_hub_overview_orient, "servers_hub", "servers_hub"),
                        r7Step("r7.servers_hub_overview.read", R.string.feature_step_result_title, R.string.feature_step_r7_servers_hub_overview_read, "servers_hub"),
                        r7Step("r7.servers_hub_overview.recover", R.string.feature_step_recover_title, R.string.feature_step_r7_servers_hub_overview_recover, "servers_hub")
                    )
                )
            )
        ),
        FeatureGuide(
            id = "global_search",
            titleRes = R.string.feature_guide_r7_global_search_title,
            route = "global_search",
            routeBases = setOf("global_search"),
            recipes = listOf(
                FeatureRecipe(
                    id = "r7.global_search_find",
                    titleRes = R.string.feature_recipe_r7_global_search_find_title,
                    steps = listOf(
                        r7Step("r7.global_search_find.orient", R.string.feature_step_purpose_title, R.string.feature_step_r7_global_search_find_orient, "tools", "global_search"),
                        r7Step("r7.global_search_find.read", R.string.feature_step_result_title, R.string.feature_step_r7_global_search_find_read, "tools"),
                        r7Step("r7.global_search_find.recover", R.string.feature_step_recover_title, R.string.feature_step_r7_global_search_find_recover, "tools")
                    )
                )
            )
        )
    )
}

private fun r7Step(id: String, titleRes: Int, bodyRes: Int, previewKey: String, route: String? = null) =
    FeatureGuideStep(id = id, titleRes = titleRes, bodyRes = bodyRes, previewKey = previewKey, route = route)
