package com.example.llamadroid.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Stable (config-independent) rule so every tool chat shares the same cached prefix. */
internal const val NATIVE_CHAT_STEP_GUIDANCE =
    "Work in short visible steps: before a tool call, tell the user in one short sentence what you are about to do; after the results, say briefly what you found. When you have what you need, give the final answer. Never repeat a tool call that already succeeded."

internal fun nativeChatToolAwarenessMessages(
    toolConfig: NativeChatToolConfig,
    knowledgeBaseRoutingGuide: String? = null,
    extensionBlocks: List<String> = emptyList()
): List<OllamaService.ChatMessage> = buildList {
    if (!toolConfig.hasEnabledTools()) return@buildList
    add(
        OllamaService.ChatMessage(
            role = "system",
            content = "You are enclosed in the app runtime. Use only the native tools explicitly available in this turn; do not assume shell access, arbitrary files, network, Android APIs, or external services unless a listed tool provides them. Files listed in [Attached files: ...] lines on user messages were attached by the user and are available through chat_file_read. $NATIVE_CHAT_STEP_GUIDANCE"
        )
    )
    val currentYear = java.time.LocalDate.now().year

    if (toolConfig.fileToolsEnabled) {
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "A private workspace is available for this saved chat. Use list/read before editing existing files. Keep files focused, write at most one bounded batch per call, then use append_workspace_file, edit_workspace_lines, or apply_workspace_patch. Do not claim access to workspace files outside this chat workspace; files the user attached (listed in [Attached files: ...] lines) are read with chat_file_read."
            )
        )
    }

    if (toolConfig.noteToolsEnabled || toolConfig.todoToolsEnabled) {
        val noteGuidance = if (toolConfig.noteToolsEnabled) {
            "When the user asks you to write, save, create, update, or improve a note, call the appropriate note tool in this turn instead of only saying that you will do it. Use create_note to save durable findings and long research notes with source citations. Use list_notes to discover whitelisted note IDs, then read_note to inspect exact note content before editing. Do not ask the user to provide note IDs when list_notes/read_note can find them. Use update_note or replace_note_text to revise notes later, and read_note to recover previous research instead of forgetting it."
        } else {
            "Use list_notes to discover whitelisted todo-list note IDs, then read_note to inspect exact todo item indexes before editing. Do not ask the user to provide note IDs or todo indexes when list_notes/read_note can find them."
        }
        val todoGuidance = if (toolConfig.todoToolsEnabled) {
            " For todo lists, use the todo-list tools for creating lists, checking, unchecking, adding, editing, or removing items."
        } else {
            ""
        }
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "Native chat note tools are available. $noteGuidance$todoGuidance Existing notes outside the whitelist are intentionally invisible."
            )
        )
    }

    if (toolConfig.imageGenerationEnabled) {
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "When using generate_image, first improve the user's image idea into a stronger prompt with clear subject, composition, style, lighting, color, medium, and constraints. If generated-image review is enabled and an attached result image appears, inspect it before the final answer; regenerate with a better optimized prompt when it misses the request, otherwise answer using the available result."
            )
        )
    }

    if (toolConfig.webSearchEnabled || toolConfig.fetchUrlEnabled) {
        val webGuidance = buildList {
            if (toolConfig.webSearchEnabled) {
                add("Use web_search for broad discovery")
            }
            add("use search_page to inspect links and snippets inside a specific page")
            if (toolConfig.fetchUrlEnabled) {
                add("use fetch_url to read the full content of a chosen URL")
            }
        }.joinToString(", ").replaceFirstChar { it.uppercase() }
        val workflowGuidance = when {
            toolConfig.webSearchEnabled && toolConfig.fetchUrlEnabled ->
                " For tasks like latest commits, releases, issues, changelogs, or docs on a project site, first find the official page, then use search_page with a navigation query such as commits or releases, then fetch_url the returned URL before summarizing."
            toolConfig.webSearchEnabled ->
                " For tasks like latest commits, releases, issues, changelogs, or docs on a project site, first find the official page, then use search_page with a navigation query such as commits or releases before summarizing."
            else ->
                " Use the URL the user provides as the starting point; search_page can inspect same-page matches and links, and fetch_url can read the chosen URL."
        }
        val kbFallbackTargets = buildList {
            if (toolConfig.webSearchEnabled) add("web_search")
            if (toolConfig.fetchUrlEnabled) add("fetch_url")
            add("Kiwix")
        }
        val kbFallbackTargetText = when (kbFallbackTargets.size) {
            0 -> "external tools"
            1 -> kbFallbackTargets.single()
            2 -> kbFallbackTargets.joinToString(" or ")
            else -> kbFallbackTargets.dropLast(1).joinToString(", ") + ", or " + kbFallbackTargets.last()
        }
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "Web navigation tools are available. $webGuidance.$workflowGuidance If selected knowledge-base guidance says a KB is relevant, call kb_search/kb_read_source before $kbFallbackTargetText unless the user explicitly needs current/external information."
            )
        )
    }

    if (toolConfig.deepResearchEnabled) {
        val selectedKbImportGuidance = if (toolConfig.deepResearchImportIntoSelectedKbEnabled && toolConfig.selectedKnowledgeBaseIds.any { it > 0L }) {
            " If the user explicitly asks to find/import new resources for the currently selected knowledge base, call deep_research with target_knowledge_base_id set to one of the selected KB ids; this permission is enabled for this chat."
        } else {
            " Do not import Deep Research sources into an already selected knowledge base; create a new visible KB unless the selected-KB import permission is enabled."
        }
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "Deep Research is available. Use deep_research when the user asks for broad, multi-source research that should create a normal visible knowledge base. Refine the query before calling it, include a short title/focus and content_summary that explain what the generated KB will contain, and set source_limit only when the user asks for a custom maximum import count. Treat source_limit as an upper bound, not as a required number of sources. The tool runs more than one search when useful, scores candidates, skips uninteresting/low-quality results, downloads readable webpages and PDFs, extracts their text, imports the best sources into the KB, vectorizes them, and selects the KB for this chat; it is not a note-writing tool.$selectedKbImportGuidance The tool may run for a long time and returns only after the knowledge base has been created or updated, imported, vectorized, and selected for this chat. Do not send a final answer saying research will start; wait for the tool result, then answer using kb_search/kb_read_source with exact KB citation links when needed."
            )
        )
    }

    if (toolConfig.calendarToolsEnabled || toolConfig.alarmToolsEnabled) {
        val organizerGuidance = buildList {
            if (toolConfig.calendarToolsEnabled) {
                add("Use list_calendar_events/read_calendar_event to discover event IDs before editing or deleting")
            }
            if (toolConfig.alarmToolsEnabled) {
                add("use list_alarms/read_alarm to discover alarm IDs before editing or deleting")
            }
        }.joinToString("; ").replaceFirstChar { it.uppercase() }
        val idGuidance = when {
            toolConfig.calendarToolsEnabled && toolConfig.alarmToolsEnabled ->
                "Do not ask the user for event or alarm IDs when the tools can find them. Calendar events can exist without alarms. Create or update phone alarms only when the user asks for an alert/reminder or when it is clearly useful for scheduling."
            toolConfig.calendarToolsEnabled ->
                "Do not ask the user for event IDs when the tools can find them. Calendar events can exist without alarms."
            else ->
                "Do not ask the user for alarm IDs when the tools can find them. Create or update phone alarms only when the user asks for an alert/reminder or when it is clearly useful for scheduling."
        }
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "Organizer tools are available. $organizerGuidance; $idGuidance For enabled organizer tool times, use the device-local timezone; if the user gives a month/day without a year, assume the current year ($currentYear) unless they explicitly say another year."
            )
        )
    }

    if (toolConfig.knowledgeBaseEnabled) {
        val routingGuide = knowledgeBaseRoutingGuide?.takeIf { it.isNotBlank() }
            ?.let { " $it" }
            .orEmpty()
        val guidance = if (toolConfig.chatDocumentKnowledgeBaseId?.let { it > 0L } == true) {
            "This chat has uploaded document vectors available through the knowledge-base tools. When the user mentions the document, file, attachment, PDF, or says to use what they sent, use the retrieved chat-document context or call kb_search before answering. Do not say no document was attached when the chat document tools are enabled; use the returned chunks and cite them with the exact Markdown citation links, for example [AL.pdf chunk 9](kb://chunk/123), not bare labels like [AL.pdf chunk 9].$routingGuide"
        } else {
            "Knowledge-base tools are available. Use kb_search before answering questions that ask for local knowledge-base content, and cite KB-derived claims with the returned exact Markdown citation links, not bare citation labels.$routingGuide"
        }
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = guidance
            )
        )
    }

    if (toolConfig.webSearchEnabled || toolConfig.fetchUrlEnabled || toolConfig.kiwixSearchEnabled || toolConfig.knowledgeBaseEnabled) {
        val citation = "Citation requirement: when tool results include source_citations or KB citation Markdown links, cite web, Kiwix, search_page, fetch_url, and KB-derived claims using the exact Markdown links. If you use numeric citations like [1], make them Markdown links to the matching source URL. If you use KB labels like [AL.pdf chunk 9], keep the full link form [AL.pdf chunk 9](kb://chunk/123)."
        // Appended to the preceding guidance message (stable text) rather than added as a new message.
        if (isEmpty()) add(OllamaService.ChatMessage(role = "system", content = citation))
        else this[lastIndex] = this[lastIndex].let { it.copy(content = it.content + "\n\n" + citation) }
    }

    extensionBlocks.filter { it.isNotBlank() }.forEach { add(OllamaService.ChatMessage(role = "system", content = it)) }
}
