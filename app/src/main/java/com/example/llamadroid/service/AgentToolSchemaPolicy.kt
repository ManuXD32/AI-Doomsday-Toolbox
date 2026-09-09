package com.example.llamadroid.service

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure presentation policy for the tool definitions sent to a small model.
 *
 * Tool names, argument names, required arguments, and the JSON Schema
 * contract are kept intact. Only prose annotations are shortened and
 * non-semantic JSON Schema annotations are removed. The caller still owns the
 * complete uncompressed list for tool execution and tool_help.
 */
object AgentToolSchemaPolicy {
    const val MAX_DESCRIPTION_CHARS = 96

    /**
     * Select the small, phase-specific advertised palette from an already
     * capability-filtered tool list. The complete list stays with the caller
     * for execution validation and tool_help; this function cannot invent a
     * tool that is absent from [tools].
     *
     * [activatedTool] is the one optional capability explicitly requested
     * through tool_help. It is added only when the runtime says it is
     * available, and the input order is retained for stable request output.
     */
    fun selectOptimizedToolPalette(
        tools: List<AgentTool>,
        phase: AgentHarnessPhase,
        activatedTool: String? = null
    ): List<AgentTool> =
        selectOptimizedToolPaletteForRole(
            tools = tools,
            phase = phase,
            role = null,
            activatedTool = activatedTool
        )

    /**
     * Select the compact core for an optimized built-in role. The complete
     * capability-filtered list remains with the caller for validation and
     * tool_help; role filtering only changes advertised schemas.
     */
    fun selectOptimizedToolPaletteForRole(
        tools: List<AgentTool>,
        phase: AgentHarnessPhase,
        role: String?,
        activatedTool: String? = null
    ): List<AgentTool> {
        val allowedNames = optimizedCoreToolNames(phase, role).toMutableSet()
        val requestedActivation = activatedTool?.trim().orEmpty()
        if (requestedActivation.isNotEmpty() && tools.any { it.name == requestedActivation }) {
            allowedNames += requestedActivation
        }
        return tools.filter { it.name in allowedNames }
    }

    /**
     * Return copies of [tools] with bounded descriptions and compact schemas.
     * No tool is filtered, renamed, or made optional by this function.
     */
    fun compactAgentToolSchemas(tools: List<AgentTool>): List<AgentTool> =
        tools.map { tool ->
            AgentTool(
                name = tool.name,
                description = compactDescription(
                    raw = TOOL_DESCRIPTION_HINTS[tool.name] ?: tool.description,
                    fallback = "${tool.name} tool."
                ),
                parameters = tool.parameters.mapValues { (parameterName, description) ->
                    compactDescription(
                        raw = TOOL_PARAMETER_HINTS[tool.name to parameterName]
                            ?: PARAMETER_HINTS[parameterName]
                            ?: description,
                        fallback = "$parameterName argument."
                    )
                },
                requiredParams = tool.requiredParams.toList(),
                schemaJson = tool.schemaJson?.let(::compactSchemaJson)
            )
        }

    private fun compactDescription(raw: String, fallback: String): String {
        val normalized = raw
            .replace(WHITESPACE, " ")
            .replace(EXAMPLE_SUFFIX, "")
            .trim()
            .ifBlank { fallback }

        if (normalized.length <= MAX_DESCRIPTION_CHARS) return normalized

        val firstSentenceEnd = normalized.indexOfFirst { it == '.' || it == '!' || it == '?' }
        if (firstSentenceEnd in 1 until MAX_DESCRIPTION_CHARS) {
            return normalized.substring(0, firstSentenceEnd + 1).trim()
        }

        val cutoff = normalized
            .lastIndexOf(' ', MAX_DESCRIPTION_CHARS - 2)
            .takeIf { it > 20 }
            ?: MAX_DESCRIPTION_CHARS - 1
        return normalized.substring(0, cutoff).trimEnd() + "…"
    }

    private fun compactSchemaJson(raw: String): String {
        val schema = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        return compactSchemaObject(schema).toString()
    }

    /**
     * Compact schema annotations while retaining every structural keyword.
     * `properties` and definition maps are traversed specially so a property
     * named `title` or `examples` is never mistaken for an annotation.
     */
    private fun compactSchemaObject(source: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (key) {
                "title", "examples" -> Unit
                "description" -> {
                    val value = source.opt(key)
                    if (value is String) {
                        result.put(
                            key,
                            compactDescription(value, "Schema description.")
                        )
                    } else {
                        result.put(key, value)
                    }
                }
                "properties", "patternProperties", "\$defs", "definitions", "dependentSchemas" -> {
                    val value = source.opt(key)
                    if (value is JSONObject) {
                        result.put(key, compactSchemaMap(value))
                    } else {
                        result.put(key, value)
                    }
                }
                "enum", "const", "default", "example" -> {
                    // These values can be arbitrary JSON and are part of the
                    // contract or caller-facing defaults; retain them exactly.
                    result.put(key, source.opt(key))
                }
                else -> result.put(key, compactSchemaValue(source.opt(key)))
            }
        }
        return result
    }

    private fun compactSchemaMap(source: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result.put(key, compactSchemaValue(source.opt(key)))
        }
        return result
    }

    private fun compactSchemaValue(value: Any?): Any? = when (value) {
        is JSONObject -> compactSchemaObject(value)
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) {
                put(compactSchemaValue(value.opt(index)))
            }
        }
        else -> value
    }

    private fun optimizedCoreToolNames(
        phase: AgentHarnessPhase,
        role: String? = null
    ): Set<String> {
        if (phase == AgentHarnessPhase.BUILD && role?.equals("CODER", ignoreCase = true) == true) {
            return CODER_BUILD_CORE_TOOL_NAMES
        }
        return when (phase) {
            AgentHarnessPhase.PLAN -> PLAN_CORE_TOOL_NAMES
            AgentHarnessPhase.BUILD -> BUILD_CORE_TOOL_NAMES
            AgentHarnessPhase.VERIFY -> VERIFY_CORE_TOOL_NAMES
        }
    }

    private val PLAN_CORE_TOOL_NAMES = setOf(
        "web_search",
        "fetch_url",
        "read_file",
        "list_directory",
        "search_code",
        "propose_plan",
        "question",
        "finish_task",
        "tool_help"
    )

    private val BUILD_CORE_TOOL_NAMES = setOf(
        "read_file",
        "write_file",
        "edit_lines",
        "run_project",
        "check_project_run",
        "stop_project_run",
        "run_command",
        "check_command",
        "question",
        "report_progress",
        "finish_task",
        "tool_help",
        "observe_preview",
        "interact_preview"
    )

    /**
     * Coder keeps edit and execution primitives in the small core. Any name
     * absent from the capability-filtered input remains unavailable; tools
     * such as apply_patch are loaded one at a time through tool_help.
     */
    private val CODER_BUILD_CORE_TOOL_NAMES = setOf(
        "read_file",
        "write_file",
        "edit_lines",
        "run_project",
        "check_project_run",
        "stop_project_run",
        "run_command",
        "check_command",
        "finish_task",
        "tool_help"
    )

    private val VERIFY_CORE_TOOL_NAMES = BUILD_CORE_TOOL_NAMES - setOf(
        "write_file",
        "edit_lines"
    )

    private val WHITESPACE = Regex("\\s+")
    private val EXAMPLE_SUFFIX = Regex("(?i)\\s+(?:examples?|for example)\\s*:.*$")

    private val TOOL_DESCRIPTION_HINTS = mapOf(
        "agent_report_read" to "Read one specialist report by report_id.",
        "apply_patch" to "Apply an approved unified diff patch.",
        "call_agent" to "Start one sequential specialist; Build calls require todo_id.",
        "cancel_command" to "Cancel a tracked background command.",
        "check_command" to "Check a background command's status and output.",
        "check_project_run" to "Check local project run status and logs.",
        "command_list" to "List tracked command sessions.",
        "create_folder" to "Create a project-relative folder.",
        "delete_memory" to "Delete selected lines from an agent memory file.",
        "edit_lines" to "Replace a bounded line range in a project file.",
        "fetch_url" to "Fetch a URL for cited source content.",
        "file_line_count" to "Count lines in a project-relative file.",
        "finish_task" to "Finish with validation evidence, or report a blocker; Plan success needs approval.",
        "force_stop_project_run" to "Force-stop the local project.",
        "generate_image" to "Generate a PNG inside the project workspace.",
        "get_datetime" to "Get the current date and time.",
        "interact_preview" to "Perform one action in the active WebUI preview.",
        "install_python_dependency" to "Install an approved project-local pure-Python wheel.",
        "kb_list_sources" to "List selected knowledge-base sources.",
        "kb_read_chunk" to "Read a knowledge-base chunk by chunk_id.",
        "kb_search" to "Search selected knowledge bases.",
        "kiwix_search" to "Search the offline Kiwix library.",
        "list_directory" to "List project-relative files; use . for the project root.",
        "list_memory" to "List agent memory files.",
        "observe_preview" to "Read bounded page text and control coordinates from the open WebUI preview.",
        "plan_read" to "Read an approved plan by ID.",
        "project_order_read" to "Read the original project order.",
        "project_state_read" to "Read the bounded canonical project state.",
        "propose_plan" to "Structured propose_plan with plan + summary (<=500 words/4000 chars); opens approval, then wait.",
        "question" to "Ask one unresolved blocker with choices; never ask preferences or repeats.",
        "read_file" to "Read a project-relative file.",
        "read_file_lines" to "Read a bounded line range from a project file.",
        "read_memory" to "Read a bounded agent memory file.",
        "read_skill_resource" to "Read a bounded file from an installed skill.",
        "reflection" to "Compare work against the approved plan.",
        "remove_image_background" to "Remove an image background inside the project.",
        "report_progress" to "Record bounded progress or a concrete VERIFY repair.",
        "run_command" to "Run an approved shell command; returns a command ID.",
        "run_project" to "Run the local project from .adt/run.json.",
        "run_skill_script" to "Run an approved project-local skill script.",
        "run_tools_sequential" to "Run up to four read-only tools sequentially.",
        "search_code" to "Search project files for text or regex.",
        "send_command_input" to "Send text to a running command's stdin.",
        "skill" to "Load an installed skill by name or ID.",
        "stop_project_run" to "Gracefully stop the local project.",
        "todo_read" to "Read the durable TODO list.",
        "todo_reconcile" to "Merge a bounded TODO update without regressions.",
        "todo_transition" to "Apply one compare-and-set TODO transition.",
        "todo_write" to "Reconcile TODO items while preserving completed work.",
        "tool_help" to "Show one available tool's compact schema and example.",
        "view_image" to "Inspect a project-relative image.",
        "wait_command" to "Wait for a background command and return output.",
        "web_search" to "Search the web and return cited results.",
        "write_file" to "Write content to a project-relative file.",
        "write_memory" to "Append content to an agent memory file.",
        "rewrite_memory" to "Replace an agent memory file after reading it."
    )

    private val TOOL_PARAMETER_HINTS = mapOf(
        ("apply_patch" to "patch") to "Unified diff patch text.",
        ("call_agent" to "agent") to "Specialist role (for example, RESEARCHER or CODER).",
        ("call_agent" to "task") to "One atomic specialist task.",
        ("call_agent" to "todo_id") to "Stable TODO ID required in Build.",
        ("edit_lines" to "new_content") to "Replacement content for the selected lines.",
        ("interact_preview" to "action") to "One action: tap, type (append), fill (replace), key, scroll, wait, or reload.",
        ("propose_plan" to "plan") to "Required plan text, at most 500 words/4000 chars.",
        ("propose_plan" to "summary") to "Required one-line plan summary.",
        ("question" to "questions") to "Structured blocker questions.",
        ("read_skill_resource" to "path") to "Relative resource path inside the skill.",
        ("run_tools_sequential" to "tools_json") to "JSON array of read-only tool calls.",
        ("write_file" to "content") to "Complete file content."
    )

    private val PARAMETER_HINTS = mapOf(
        "action" to "Preview action.",
        "agent" to "Specialist role name.",
        "allow_custom" to "Whether custom answers are allowed.",
        "append_newline" to "Whether to append a newline.",
        "args_json" to "JSON array of string arguments.",
        "block_reason" to "Specific reason the TODO is blocked.",
        "blocker_reason" to "Specific unresolved blocker.",
        "candidate_summary" to "Optional bounded work summary.",
        "chunk_id" to "Knowledge-base chunk ID.",
        "command" to "Shell command.",
        "command_id" to "Command ID.",
        "content" to "Text content.",
        "context" to "Bounded handoff context.",
        "directory" to "Project-relative directory.",
        "end_line" to "Last line, inclusive.",
        "evidence_json" to "JSON evidence references.",
        "expected_status" to "Expected current TODO status.",
        "file_pattern" to "Optional file glob.",
        "filename" to "Agent memory filename.",
        "image_path" to "Project-relative source image path.",
        "include_neighbors" to "Whether to include adjacent chunks.",
        "input" to "Text sent to command stdin.",
        "key" to "Keyboard key.",
        "lines" to "Maximum output lines.",
        "max_results" to "Maximum results.",
        "mode" to "Requested operation mode.",
        "multiple" to "Whether multiple choices are allowed.",
        "name" to "Name or ID.",
        "negative_prompt" to "Optional negative image prompt.",
        "new_status" to "Target TODO status.",
        "output_path" to "Project-relative output path.",
        "package" to "Python package name.",
        "path" to "Project-relative path.",
        "plan_id" to "Approved plan ID.",
        "plan_source" to "Optional plan memory filename.",
        "prompt" to "Image-generation prompt.",
        "query" to "Search query or pattern.",
        "report_id" to "Structured report ID.",
        "result_summary" to "Bounded result summary.",
        "scope" to "Reflection scope.",
        "scroll_dx" to "Horizontal scroll delta.",
        "scroll_dy" to "Vertical scroll delta.",
        "skill" to "Installed skill ID.",
        "start_line" to "First line, 1-indexed.",
        "summary" to "Plan summary.",
        "task" to "Atomic task description.",
        "text" to "Text value.",
        "todo_id" to "Stable TODO ID.",
        "todo_ids" to "Stable TODO ID list.",
        "tool_name" to "Available tool name.",
        "todos" to "Ordered TODO items.",
        "url" to "URL to fetch.",
        "wait_ms" to "Wait duration in milliseconds.",
        "wait_seconds" to "Wait duration in seconds.",
        "wheel_path" to "Project-local pure-Python wheel path.",
        "working_directory" to "Project-relative working directory.",
        "x" to "Viewport X coordinate.",
        "y" to "Viewport Y coordinate."
    )
}

/** Short call-site API for the optimized root request path. */
fun compactAgentToolSchemas(tools: List<AgentTool>): List<AgentTool> =
    AgentToolSchemaPolicy.compactAgentToolSchemas(tools)

/** Short call-site API for selecting the optimized phase palette. */
fun selectOptimizedToolPalette(
    tools: List<AgentTool>,
    phase: AgentHarnessPhase,
    activatedTool: String? = null
): List<AgentTool> =
    AgentToolSchemaPolicy.selectOptimizedToolPalette(tools, phase, activatedTool)

/** Role-aware selector for optimized specialist request assembly. */
fun selectOptimizedToolPaletteForRole(
    tools: List<AgentTool>,
    phase: AgentHarnessPhase,
    role: String,
    activatedTool: String? = null
): List<AgentTool> =
    AgentToolSchemaPolicy.selectOptimizedToolPaletteForRole(
        tools = tools,
        phase = phase,
        role = role,
        activatedTool = activatedTool
    )
