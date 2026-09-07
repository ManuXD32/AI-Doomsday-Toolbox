package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.R

/** Non-Tama feature guide expansion for conversations, infrastructure, and settings. */
internal object FeatureOtherGuides {
    val guides: List<FeatureGuide> = listOf(
        FeatureGuide(
            id = "conversations",
            titleRes = R.string.feature_guide_conversations_title,
            route = "llama_servers",
            routeBases = setOf("chat", "llama_servers", "llama_server_list", "llama_chat_list", "llama_chat", "llama_scheduler"),
            recipes = listOf(
                otherRecipe(
                    id = "conversations.chat",
                    titleRes = R.string.feature_recipe_conversations_chat_title,
                    previewKey = "chat",
                    route = "chat",
                    targetId = "chat.server_selection",
                    eventId = "chat.server_selection",
                    orientBodyRes = R.string.feature_step_conversations_chat_orient_body,
                    readBodyRes = R.string.feature_step_conversations_chat_read_body,
                    recoverBodyRes = R.string.feature_step_conversations_chat_recover_body
                ),
                otherRecipe(
                    id = "conversations.native_management",
                    titleRes = R.string.feature_recipe_conversations_native_management_title,
                    previewKey = "conversations_native_management",
                    route = "llama_server_list",
                    targetId = "llama.servers",
                    eventId = "llama.server_card",
                    orientBodyRes = R.string.feature_step_conversations_native_management_orient_body,
                    readBodyRes = R.string.feature_step_conversations_native_management_read_body,
                    recoverBodyRes = R.string.feature_step_conversations_native_management_recover_body
                ),
                otherRecipe(
                    id = "conversations.history",
                    titleRes = R.string.feature_recipe_conversations_history_title,
                    previewKey = "chat",
                    route = "llama_chat_list",
                    targetId = "llama.history",
                    eventId = "llama.history",
                    orientBodyRes = R.string.feature_step_conversations_history_orient_body,
                    readBodyRes = R.string.feature_step_conversations_history_read_body,
                    recoverBodyRes = R.string.feature_step_conversations_history_recover_body
                ),
                otherRecipe(
                    id = "conversations.scheduler",
                    titleRes = R.string.feature_recipe_conversations_scheduler_title,
                    previewKey = "conversations_scheduler",
                    route = "llama_scheduler",
                    targetId = "llama.scheduler",
                    eventId = "llama.scheduler",
                    orientBodyRes = R.string.feature_step_conversations_scheduler_orient_body,
                    readBodyRes = R.string.feature_step_conversations_scheduler_read_body,
                    recoverBodyRes = R.string.feature_step_conversations_scheduler_recover_body
                ),
                otherRecipe(
                    id = "conversations.server_selection",
                    titleRes = R.string.feature_recipe_conversations_server_selection_title,
                    previewKey = "conversations_server_selection",
                    route = "llama_servers",
                    targetId = "llama.server_selection",
                    eventId = "llama.server_selection",
                    orientBodyRes = R.string.feature_step_conversations_server_selection_orient_body,
                    readBodyRes = R.string.feature_step_conversations_server_selection_read_body,
                    recoverBodyRes = R.string.feature_step_conversations_server_selection_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "agent",
            titleRes = R.string.feature_guide_agent_title,
            route = "agent",
            routeBases = setOf("agent", "agent_workspace", "agent_invocation"),
            recipes = listOf(
                otherRecipe(
                    id = "agent.quickstart",
                    titleRes = R.string.feature_recipe_agent_quickstart_title,
                    previewKey = "agent",
                    route = "agent",
                    targetId = "agent.workspace",
                    eventId = "agent.workspace",
                    orientBodyRes = R.string.feature_step_agent_quickstart_orient_body,
                    readBodyRes = R.string.feature_step_agent_quickstart_read_body,
                    recoverBodyRes = R.string.feature_step_agent_quickstart_recover_body
                ),
                otherRecipe(
                    id = "agent.projects",
                    titleRes = R.string.feature_recipe_agent_projects_title,
                    previewKey = "agent",
                    route = "agent_workspace",
                    targetId = "agent.projects",
                    eventId = "agent.project",
                    orientBodyRes = R.string.feature_step_agent_projects_orient_body,
                    readBodyRes = R.string.feature_step_agent_projects_read_body,
                    recoverBodyRes = R.string.feature_step_agent_projects_recover_body
                ),
                otherRecipe(
                    id = "agent.plans",
                    titleRes = R.string.feature_recipe_agent_plans_title,
                    previewKey = "agent",
                    route = "agent",
                    targetId = "agent.plan",
                    eventId = "agent.plan",
                    orientBodyRes = R.string.feature_step_agent_plans_orient_body,
                    readBodyRes = R.string.feature_step_agent_plans_read_body,
                    recoverBodyRes = R.string.feature_step_agent_plans_recover_body
                ),
                otherRecipe(
                    id = "agent.approvals",
                    titleRes = R.string.feature_recipe_agent_approvals_title,
                    previewKey = "agent",
                    route = "agent",
                    targetId = "agent.approvals",
                    eventId = "agent.approval",
                    orientBodyRes = R.string.feature_step_agent_approvals_orient_body,
                    readBodyRes = R.string.feature_step_agent_approvals_read_body,
                    recoverBodyRes = R.string.feature_step_agent_approvals_recover_body
                ),
                otherRecipe(
                    id = "agent.recovery",
                    titleRes = R.string.feature_recipe_agent_recovery_title,
                    previewKey = "agent",
                    route = null, // Keep an open detail page; the owning guide supplies a safe parent entry.
                    targetId = "agent.continue",
                    eventId = "agent.continue",
                    orientBodyRes = R.string.feature_step_agent_recovery_orient_body,
                    readBodyRes = R.string.feature_step_agent_recovery_read_body,
                    recoverBodyRes = R.string.feature_step_agent_recovery_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "organizer",
            titleRes = R.string.feature_guide_organizer_title,
            route = "notes_manager",
            routeBases = setOf("notes_manager"),
            recipes = listOf(
                otherRecipe(
                    id = "organizer.notes",
                    titleRes = R.string.feature_recipe_organizer_notes_title,
                    previewKey = "organizer",
                    route = "notes_manager",
                    targetId = "organizer.notes",
                    eventId = "organizer.notes",
                    orientBodyRes = R.string.feature_step_organizer_notes_orient_body,
                    readBodyRes = R.string.feature_step_organizer_notes_read_body,
                    recoverBodyRes = R.string.feature_step_organizer_notes_recover_body
                ),
                otherRecipe(
                    id = "organizer.calendar",
                    titleRes = R.string.feature_recipe_organizer_calendar_title,
                    previewKey = "organizer",
                    route = "notes_manager",
                    targetId = "organizer.calendar",
                    eventId = "organizer.calendar",
                    orientBodyRes = R.string.feature_step_organizer_calendar_orient_body,
                    readBodyRes = R.string.feature_step_organizer_calendar_read_body,
                    recoverBodyRes = R.string.feature_step_organizer_calendar_recover_body
                ),
                otherRecipe(
                    id = "organizer.alarms",
                    titleRes = R.string.feature_recipe_organizer_alarms_title,
                    previewKey = "organizer",
                    route = "notes_manager",
                    targetId = "organizer.alarms",
                    eventId = "organizer.alarms",
                    orientBodyRes = R.string.feature_step_organizer_alarms_orient_body,
                    readBodyRes = R.string.feature_step_organizer_alarms_read_body,
                    recoverBodyRes = R.string.feature_step_organizer_alarms_recover_body
                ),
                otherRecipe(
                    id = "organizer.editors",
                    titleRes = R.string.feature_recipe_organizer_editors_title,
                    previewKey = "organizer",
                    route = "notes_manager",
                    targetId = "organizer.editor",
                    eventId = "organizer.editor",
                    orientBodyRes = R.string.feature_step_organizer_editors_orient_body,
                    readBodyRes = R.string.feature_step_organizer_editors_read_body,
                    recoverBodyRes = R.string.feature_step_organizer_editors_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "library",
            titleRes = R.string.feature_guide_library_title,
            route = "library",
            routeBases = setOf("library"),
            recipes = listOf(
                otherRecipe(
                    id = "library.resources",
                    titleRes = R.string.feature_recipe_library_resources_title,
                    previewKey = "offline_library",
                    route = "library",
                    targetId = "library.resources",
                    eventId = "library.resources",
                    orientBodyRes = R.string.feature_step_library_resources_orient_body,
                    readBodyRes = R.string.feature_step_library_resources_read_body,
                    recoverBodyRes = R.string.feature_step_library_resources_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "knowledge",
            titleRes = R.string.feature_guide_knowledge_title,
            route = "knowledge_base",
            routeBases = setOf("knowledge_base", "knowledge_chunk"),
            recipes = listOf(
                otherRecipe(
                    id = "knowledge.index",
                    titleRes = R.string.feature_recipe_knowledge_index_title,
                    previewKey = "knowledge",
                    route = "knowledge_base",
                    targetId = "knowledge.sources",
                    eventId = "knowledge.sources",
                    orientBodyRes = R.string.feature_step_knowledge_index_orient_body,
                    readBodyRes = R.string.feature_step_knowledge_index_read_body,
                    recoverBodyRes = R.string.feature_step_knowledge_index_recover_body
                ),
                otherRecipe(
                    id = "knowledge.search",
                    titleRes = R.string.feature_recipe_knowledge_search_title,
                    previewKey = "knowledge",
                    route = "knowledge_base",
                    targetId = "knowledge.search",
                    eventId = "knowledge.search",
                    orientBodyRes = R.string.feature_step_knowledge_search_orient_body,
                    readBodyRes = R.string.feature_step_knowledge_search_read_body,
                    recoverBodyRes = R.string.feature_step_knowledge_search_recover_body
                ),
                otherRecipe(
                    id = "knowledge.chunk",
                    titleRes = R.string.feature_recipe_knowledge_chunk_title,
                    previewKey = "knowledge",
                    route = null, // Keep an open detail page; the owning guide supplies a safe parent entry.
                    targetId = "knowledge.chunk",
                    eventId = "knowledge.chunk",
                    orientBodyRes = R.string.feature_step_knowledge_chunk_orient_body,
                    readBodyRes = R.string.feature_step_knowledge_chunk_read_body,
                    recoverBodyRes = R.string.feature_step_knowledge_chunk_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "offline",
            titleRes = R.string.feature_guide_offline_title,
            route = "zim_manager",
            routeBases = setOf("kiwix_hub", "zim_manager", "kiwix_viewer"),
            recipes = listOf(
                otherRecipe(
                    id = "offline.manage",
                    titleRes = R.string.feature_recipe_offline_manage_title,
                    previewKey = "offline_library",
                    route = "zim_manager",
                    targetId = "kiwix.catalog",
                    eventId = "kiwix.catalog",
                    orientBodyRes = R.string.feature_step_offline_manage_orient_body,
                    readBodyRes = R.string.feature_step_offline_manage_read_body,
                    recoverBodyRes = R.string.feature_step_offline_manage_recover_body
                ),
                otherRecipe(
                    id = "offline.read",
                    titleRes = R.string.feature_recipe_offline_read_title,
                    previewKey = "offline_library",
                    route = "kiwix_viewer",
                    targetId = "kiwix.reader",
                    eventId = "kiwix.reader",
                    orientBodyRes = R.string.feature_step_offline_read_orient_body,
                    readBodyRes = R.string.feature_step_offline_read_read_body,
                    recoverBodyRes = R.string.feature_step_offline_read_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "servers",
            titleRes = R.string.feature_guide_servers_title,
            route = "ai_servers_hub",
            routeBases = setOf("ai_servers_hub", "file_server", "ollama_manager"),
            recipes = listOf(
                otherRecipe(
                    id = "servers.hub",
                    titleRes = R.string.feature_recipe_servers_hub_title,
                    previewKey = "servers_hub",
                    route = "ai_servers_hub",
                    targetId = "servers.cards",
                    eventId = "servers.card",
                    orientBodyRes = R.string.feature_step_servers_hub_orient_body,
                    readBodyRes = R.string.feature_step_servers_hub_read_body,
                    recoverBodyRes = R.string.feature_step_servers_hub_recover_body
                ),
                otherRecipe(
                    id = "servers.files",
                    titleRes = R.string.feature_recipe_servers_files_title,
                    previewKey = "file_server",
                    route = "file_server",
                    targetId = "file_server.folder",
                    eventId = "file_server.folder",
                    orientBodyRes = R.string.feature_step_servers_files_orient_body,
                    readBodyRes = R.string.feature_step_servers_files_read_body,
                    recoverBodyRes = R.string.feature_step_servers_files_recover_body
                ),
                otherRecipe(
                    id = "servers.ollama",
                    titleRes = R.string.feature_recipe_servers_ollama_title,
                    previewKey = "ollama",
                    route = "ollama_manager",
                    targetId = "ollama.models",
                    eventId = "ollama.models",
                    orientBodyRes = R.string.feature_step_servers_ollama_orient_body,
                    readBodyRes = R.string.feature_step_servers_ollama_read_body,
                    recoverBodyRes = R.string.feature_step_servers_ollama_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "distributed",
            titleRes = R.string.feature_guide_distributed_title,
            route = "distributed",
            routeBases = setOf("distributed", "distributed_worker", "distributed_master", "distributed_network"),
            recipes = listOf(
                otherRecipe(
                    id = "distributed.roles",
                    titleRes = R.string.feature_recipe_distributed_roles_title,
                    previewKey = "distributed_roles",
                    route = "distributed",
                    targetId = "distributed.roles",
                    eventId = "distributed.roles",
                    orientBodyRes = R.string.feature_step_distributed_roles_orient_body,
                    readBodyRes = R.string.feature_step_distributed_roles_read_body,
                    recoverBodyRes = R.string.feature_step_distributed_roles_recover_body
                ),
                otherRecipe(
                    id = "distributed.master",
                    titleRes = R.string.feature_recipe_distributed_master_title,
                    previewKey = "distributed_llm",
                    route = "distributed_master",
                    targetId = "distributed.master",
                    eventId = "distributed.master",
                    orientBodyRes = R.string.feature_step_distributed_master_orient_body,
                    readBodyRes = R.string.feature_step_distributed_master_read_body,
                    recoverBodyRes = R.string.feature_step_distributed_master_recover_body
                ),
                otherRecipe(
                    id = "distributed.worker",
                    titleRes = R.string.feature_recipe_distributed_worker_title,
                    previewKey = "distributed_llm",
                    route = "distributed_worker",
                    targetId = "distributed.worker",
                    eventId = "distributed.worker",
                    orientBodyRes = R.string.feature_step_distributed_worker_orient_body,
                    readBodyRes = R.string.feature_step_distributed_worker_read_body,
                    recoverBodyRes = R.string.feature_step_distributed_worker_recover_body
                ),
                otherRecipe(
                    id = "distributed.topology",
                    titleRes = R.string.feature_recipe_distributed_topology_title,
                    previewKey = "distributed_llm",
                    route = "distributed_network",
                    targetId = "distributed.topology",
                    eventId = "distributed.topology",
                    orientBodyRes = R.string.feature_step_distributed_topology_orient_body,
                    readBodyRes = R.string.feature_step_distributed_topology_read_body,
                    recoverBodyRes = R.string.feature_step_distributed_topology_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "media_runtime",
            titleRes = R.string.feature_guide_media_runtime_title,
            route = "sd_distributed",
            routeBases = setOf("sd_distributed", "sd_distributed_worker", "sd_distributed_master", "sd_distributed_network", "sd_distributed_run_config", "sd_distributed_gallery"),
            recipes = listOf(
                otherRecipe(
                    id = "media_runtime.roles",
                    titleRes = R.string.feature_recipe_media_runtime_roles_title,
                    previewKey = "distributed_media",
                    route = "sd_distributed",
                    targetId = "media.roles",
                    eventId = "media.roles",
                    orientBodyRes = R.string.feature_step_media_runtime_roles_orient_body,
                    readBodyRes = R.string.feature_step_media_runtime_roles_read_body,
                    recoverBodyRes = R.string.feature_step_media_runtime_roles_recover_body
                ),
                otherRecipe(
                    id = "media_runtime.topology",
                    titleRes = R.string.feature_recipe_media_runtime_topology_title,
                    previewKey = "distributed_media",
                    route = "sd_distributed_network",
                    targetId = "media.workers",
                    eventId = null,
                    orientBodyRes = R.string.feature_step_media_runtime_topology_orient_body,
                    readBodyRes = R.string.feature_step_media_runtime_topology_read_body,
                    recoverBodyRes = R.string.feature_step_media_runtime_topology_recover_body
                ),
                otherRecipe(
                    id = "media_runtime.gallery",
                    titleRes = R.string.feature_recipe_media_runtime_gallery_title,
                    previewKey = "distributed_media",
                    route = "sd_distributed_run_config",
                    targetId = "media.gallery",
                    eventId = "media.gallery",
                    orientBodyRes = R.string.feature_step_media_runtime_gallery_orient_body,
                    readBodyRes = R.string.feature_step_media_runtime_gallery_read_body,
                    recoverBodyRes = R.string.feature_step_media_runtime_gallery_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "benchmark",
            titleRes = R.string.feature_guide_benchmark_title,
            route = "benchmark",
            routeBases = setOf("benchmark", "benchmark_history"),
            recipes = listOf(
                otherRecipe(
                    id = "benchmark.run",
                    titleRes = R.string.feature_recipe_benchmark_run_title,
                    previewKey = "benchmark",
                    route = "benchmark",
                    targetId = "benchmark.run",
                    eventId = "benchmark.run",
                    orientBodyRes = R.string.feature_step_benchmark_run_orient_body,
                    readBodyRes = R.string.feature_step_benchmark_run_read_body,
                    recoverBodyRes = R.string.feature_step_benchmark_run_recover_body
                ),
                otherRecipe(
                    id = "benchmark.history",
                    titleRes = R.string.feature_recipe_benchmark_history_title,
                    previewKey = "benchmark",
                    route = "benchmark_history",
                    targetId = "benchmark.history",
                    eventId = "benchmark.history",
                    orientBodyRes = R.string.feature_step_benchmark_history_orient_body,
                    readBodyRes = R.string.feature_step_benchmark_history_read_body,
                    recoverBodyRes = R.string.feature_step_benchmark_history_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "training",
            titleRes = R.string.feature_guide_training_title,
            route = "quadtrix_trainer",
            routeBases = setOf("quadtrix_trainer", "quadtrix_webui"),
            recipes = listOf(
                otherRecipe(
                    id = "training.quadtrix",
                    titleRes = R.string.feature_recipe_training_quadtrix_title,
                    previewKey = "quadtrix",
                    route = "quadtrix_trainer",
                    targetId = "quadtrix.config",
                    eventId = "quadtrix.config",
                    orientBodyRes = R.string.feature_step_training_quadtrix_orient_body,
                    readBodyRes = R.string.feature_step_training_quadtrix_read_body,
                    recoverBodyRes = R.string.feature_step_training_quadtrix_recover_body
                ),
                otherRecipe(
                    id = "training.progress",
                    titleRes = R.string.feature_recipe_training_progress_title,
                    previewKey = "quadtrix",
                    route = null, // Keep an open detail page; the owning guide supplies a safe parent entry.
                    targetId = "quadtrix.progress",
                    eventId = "quadtrix.progress",
                    orientBodyRes = R.string.feature_step_training_progress_orient_body,
                    readBodyRes = R.string.feature_step_training_progress_read_body,
                    recoverBodyRes = R.string.feature_step_training_progress_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "termux",
            titleRes = R.string.feature_guide_termux_title,
            route = "termux",
            routeBases = setOf("termux", "termux_webview", "termux_file_manager"),
            recipes = listOf(
                otherRecipe(
                    id = "termux.shell",
                    titleRes = R.string.feature_recipe_termux_shell_title,
                    previewKey = "termux",
                    route = "termux",
                    targetId = "termux.tools",
                    eventId = "termux.tools",
                    orientBodyRes = R.string.feature_step_termux_shell_orient_body,
                    readBodyRes = R.string.feature_step_termux_shell_read_body,
                    recoverBodyRes = R.string.feature_step_termux_shell_recover_body
                ),
                otherRecipe(
                    id = "termux.webview",
                    titleRes = R.string.feature_recipe_termux_webview_title,
                    previewKey = "termux",
                    route = null, // Keep an open detail page; the owning guide supplies a safe parent entry.
                    targetId = "termux.webview",
                    eventId = "termux.webview",
                    orientBodyRes = R.string.feature_step_termux_webview_orient_body,
                    readBodyRes = R.string.feature_step_termux_webview_read_body,
                    recoverBodyRes = R.string.feature_step_termux_webview_recover_body
                ),
                otherRecipe(
                    id = "termux.files",
                    titleRes = R.string.feature_recipe_termux_files_title,
                    previewKey = "termux",
                    route = "termux_file_manager",
                    targetId = "termux.files",
                    eventId = "termux.files",
                    orientBodyRes = R.string.feature_step_termux_files_orient_body,
                    readBodyRes = R.string.feature_step_termux_files_read_body,
                    recoverBodyRes = R.string.feature_step_termux_files_recover_body
                )
            )
        ),
        FeatureGuide(
            id = "settings",
            titleRes = R.string.feature_guide_settings_title,
            route = "settings",
            routeBases = setOf("settings", "settings_general", "settings_llm", "settings_imagegen", "settings_whisper", "settings_upscaler", "settings_prompts", "settings_logs", "settings_stats", "logs", "about"),
            recipes = listOf(
                otherRecipe(
                    id = "settings.appearance",
                    titleRes = R.string.feature_recipe_settings_appearance_title,
                    previewKey = "settings",
                    route = "settings_general",
                    targetId = "settings.settings_general",
                    eventId = "settings.appearance",
                    orientBodyRes = R.string.feature_step_settings_appearance_orient_body,
                    readBodyRes = R.string.feature_step_settings_appearance_read_body,
                    recoverBodyRes = R.string.feature_step_settings_appearance_recover_body
                ),
                otherRecipe(
                    id = "settings.language",
                    titleRes = R.string.feature_recipe_settings_language_title,
                    previewKey = "settings",
                    route = "settings_general",
                    targetId = "settings.settings_general",
                    eventId = "settings.language",
                    orientBodyRes = R.string.feature_step_settings_language_orient_body,
                    readBodyRes = R.string.feature_step_settings_language_read_body,
                    recoverBodyRes = R.string.feature_step_settings_language_recover_body
                ),
                otherRecipe(
                    id = "settings.backups",
                    titleRes = R.string.feature_recipe_settings_backups_title,
                    previewKey = "settings",
                    route = "settings_general",
                    targetId = "settings.settings_general",
                    eventId = "settings.backups",
                    orientBodyRes = R.string.feature_step_settings_backups_orient_body,
                    readBodyRes = R.string.feature_step_settings_backups_read_body,
                    recoverBodyRes = R.string.feature_step_settings_backups_recover_body
                ),
                otherRecipe(
                    id = "settings.prompts",
                    titleRes = R.string.feature_recipe_settings_prompts_title,
                    previewKey = "settings",
                    route = "settings_prompts",
                    targetId = "settings.settings_prompts",
                    eventId = "settings.prompts",
                    orientBodyRes = R.string.feature_step_settings_prompts_orient_body,
                    readBodyRes = R.string.feature_step_settings_prompts_read_body,
                    recoverBodyRes = R.string.feature_step_settings_prompts_recover_body
                ),
                otherRecipe(
                    id = "settings.diagnostics",
                    titleRes = R.string.feature_recipe_settings_diagnostics_title,
                    previewKey = "diagnostics",
                    route = "logs",
                    targetId = "settings.settings_logs",
                    eventId = "settings.diagnostics",
                    orientBodyRes = R.string.feature_step_settings_diagnostics_orient_body,
                    readBodyRes = R.string.feature_step_settings_diagnostics_read_body,
                    recoverBodyRes = R.string.feature_step_settings_diagnostics_recover_body
                ),
                otherRecipe(
                    id = "settings.about_support",
                    titleRes = R.string.feature_recipe_settings_about_support_title,
                    previewKey = "about",
                    route = "about",
                    targetId = "settings.about",
                    eventId = "settings.support",
                    orientBodyRes = R.string.feature_step_settings_about_support_orient_body,
                    readBodyRes = R.string.feature_step_settings_about_support_read_body,
                    recoverBodyRes = R.string.feature_step_settings_about_support_recover_body
                )
            )
        )
    )
}

private fun otherRecipe(
    id: String,
    titleRes: Int,
    previewKey: String,
    route: String?,
    targetId: String,
    eventId: String?,
    orientBodyRes: Int,
    readBodyRes: Int,
    recoverBodyRes: Int
) = FeatureRecipe(
    id = id,
    titleRes = titleRes,
    steps = listOf(
        FeatureGuideStep(
            id = "${id}.orient",
            titleRes = R.string.feature_step_purpose_title,
            bodyRes = orientBodyRes,
            previewKey = previewKey,
            targetId = targetId,
            eventId = eventId,
            route = route
        ),
        FeatureGuideStep(
            id = "${id}.read",
            titleRes = R.string.feature_step_result_title,
            bodyRes = readBodyRes,
            previewKey = previewKey
        ),
        FeatureGuideStep(
            id = "${id}.recover",
            titleRes = R.string.feature_step_recover_title,
            bodyRes = recoverBodyRes,
            previewKey = previewKey
        )
    )
)
