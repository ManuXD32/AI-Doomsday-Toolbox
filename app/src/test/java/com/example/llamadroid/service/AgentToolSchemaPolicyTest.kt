package com.example.llamadroid.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolSchemaPolicyTest {
    @Test
    fun `plan palette keeps only its core tools in actual input order`() {
        val available = listOf(
            agentTool("tool_help"),
            agentTool("write_file"),
            agentTool("search_code"),
            agentTool("web_search"),
            agentTool("fetch_url"),
            agentTool("question"),
            agentTool("read_file"),
            agentTool("list_directory"),
            agentTool("propose_plan"),
            agentTool("finish_task")
        )

        val selected = selectOptimizedToolPalette(available, AgentHarnessPhase.PLAN)

        assertEquals(
            listOf(
                "tool_help",
                "search_code",
                "web_search",
                "fetch_url",
                "question",
                "read_file",
                "list_directory",
                "propose_plan",
                "finish_task"
            ),
            selected.map { it.name }
        )
    }

    @Test
    fun `build palette intersects the capability filtered backend list`() {
        val available = listOf(
            agentTool("check_project_run"),
            agentTool("read_file"),
            agentTool("run_project"),
            agentTool("stop_project_run"),
            agentTool("observe_preview"),
            agentTool("interact_preview"),
            agentTool("write_file"),
            agentTool("tool_help"),
            agentTool("finish_task"),
            agentTool("question"),
            agentTool("report_progress"),
            agentTool("todo_write")
        )

        val selected = selectOptimizedToolPalette(available, AgentHarnessPhase.BUILD)

        assertEquals(
            listOf(
                "check_project_run",
                "read_file",
                "run_project",
                "stop_project_run",
                "observe_preview",
                "interact_preview",
                "write_file",
                "tool_help",
                "finish_task",
                "question",
                "report_progress"
            ),
            selected.map { it.name }
        )
        assertFalse(selected.any { it.name == "todo_write" })
    }

    @Test
    fun `coder build palette keeps mutation completion and available run tools`() {
        val available = listOf(
            agentTool("read_file"),
            agentTool("write_file"),
            agentTool("edit_lines"),
            agentTool("run_project"),
            agentTool("check_project_run"),
            agentTool("stop_project_run"),
            agentTool("run_command"),
            agentTool("check_command"),
            agentTool("finish_task"),
            agentTool("tool_help"),
            agentTool("apply_patch"),
            agentTool("project_state_read"),
            agentTool("web_search")
        )

        val selected = selectOptimizedToolPaletteForRole(
            tools = available,
            phase = AgentHarnessPhase.BUILD,
            role = "CODER"
        )
        val activated = selectOptimizedToolPaletteForRole(
            tools = available,
            phase = AgentHarnessPhase.BUILD,
            role = "CODER",
            activatedTool = "apply_patch"
        )

        assertEquals(
            listOf(
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
            ),
            selected.map { it.name }
        )
        assertTrue(activated.map { it.name }.contains("apply_patch"))
        assertFalse(selected.map { it.name }.contains("apply_patch"))
        assertFalse(activated.map { it.name }.contains("project_state_read"))
        assertFalse(activated.map { it.name }.contains("web_search"))
    }

    @Test
    fun `verify palette keeps checks and visual tools without file mutations`() {
        val available = listOf(
            agentTool("write_file"),
            agentTool("edit_lines"),
            agentTool("read_file"),
            agentTool("run_command"),
            agentTool("check_command"),
            agentTool("run_project"),
            agentTool("check_project_run"),
            agentTool("stop_project_run"),
            agentTool("finish_task"),
            agentTool("tool_help"),
            agentTool("question"),
            agentTool("report_progress"),
            agentTool("observe_preview"),
            agentTool("interact_preview")
        )

        val selected = selectOptimizedToolPalette(available, AgentHarnessPhase.VERIFY)

        assertEquals(
            listOf(
                "read_file",
                "run_command",
                "check_command",
                "run_project",
                "check_project_run",
                "stop_project_run",
                "finish_task",
                "tool_help",
                "question",
                "report_progress",
                "observe_preview",
                "interact_preview"
            ),
            selected.map { it.name }
        )
        assertFalse(selected.any { it.name == "write_file" || it.name == "edit_lines" })
    }

    @Test
    fun `palette activates one available optional tool without inventing names`() {
        val available = listOf(
            agentTool("tool_help"),
            agentTool("read_file"),
            agentTool("todo_read"),
            agentTool("finish_task")
        )

        val activated = selectOptimizedToolPalette(
            tools = available,
            phase = AgentHarnessPhase.BUILD,
            activatedTool = "todo_read"
        )
        val missing = selectOptimizedToolPalette(
            tools = available,
            phase = AgentHarnessPhase.BUILD,
            activatedTool = "run_project"
        )

        assertEquals(
            listOf("tool_help", "read_file", "todo_read", "finish_task"),
            activated.map { it.name }
        )
        assertEquals(listOf("tool_help", "read_file", "finish_task"), missing.map { it.name })
        assertFalse(missing.any { it.name == "run_project" })
    }

    @Test
    fun `build and verify control tools stay discoverable with blocker guidance`() {
        val tools = listOf(
            agentTool("question"),
            agentTool("report_progress"),
            agentTool("finish_task")
        )
        val build = selectOptimizedToolPalette(tools, AgentHarnessPhase.BUILD)
        val verify = selectOptimizedToolPalette(tools, AgentHarnessPhase.VERIFY)

        assertEquals(tools.map { it.name }, build.map { it.name })
        assertEquals(tools.map { it.name }, verify.map { it.name })

        val compact = compactAgentToolSchemas(tools).associateBy { it.name }
        assertTrue(compact.getValue("question").description.contains("blocker"))
        assertTrue(compact.getValue("question").description.contains("preferences"))
        assertTrue(compact.getValue("report_progress").description.contains("VERIFY"))
        assertTrue(compact.getValue("finish_task").description.contains("validation"))
    }

    @Test
    fun `compaction keeps every tool argument and schema constraint`() {
        val longDescription =
            "Explain the project-relative file operation in detail, including the exact safety " +
                "boundary, approval behavior, recovery guidance, and multiple implementation " +
                "examples that are not needed in the request schema. This deliberately verbose " +
                "fixture models descriptions that make a small-model request exceed its control context."
        val tool = AgentTool(
            name = "edit_lines",
            description = longDescription,
            parameters = linkedMapOf(
                "path" to "File path relative to project root. $longDescription",
                "start_line" to "First line number to replace (1-indexed). $longDescription",
                "end_line" to "Last line number to replace (inclusive). $longDescription",
                "new_content" to "Replacement content for those lines. $longDescription"
            ),
            requiredParams = listOf("path", "start_line", "end_line", "new_content"),
            schemaJson = """
                {
                  "type":"object",
                  "title":"Long presentation title",
                  "examples":[{"path":"example.py"}],
                  "properties":{
                    "path":{"type":"string","minLength":1,"pattern":"^[^/].*","description":"$longDescription"},
                    "mode":{"type":"string","enum":["safe","force"],"description":"$longDescription"},
                    "count":{"type":"integer","minimum":1,"maximum":20}
                  },
                  "required":["path","mode"],
                  "additionalProperties":false
                }
            """.trimIndent()
        )

        val compacted = compactAgentToolSchemas(listOf(tool)).single()

        assertEquals(tool.name, compacted.name)
        assertEquals(tool.parameters.keys, compacted.parameters.keys)
        assertEquals(tool.requiredParams, compacted.requiredParams)
        assertTrue(compacted.description.length <= AgentToolSchemaPolicy.MAX_DESCRIPTION_CHARS)
        assertTrue(compacted.parameters.values.all { it.length <= AgentToolSchemaPolicy.MAX_DESCRIPTION_CHARS })
        assertTrue(compacted.parameters.getValue("path").contains("relative"))
        assertTrue(compacted.parameters.containsKey("new_content"))

        val originalParameters = schemaParameters(canonicalAgentToolSchemaJson(listOf(tool)))
        val compactParameters = schemaParameters(canonicalAgentToolSchemaJson(listOf(compacted)))
        assertEquals(originalParameters.getString("type"), compactParameters.getString("type"))
        assertEquals(
            originalParameters.getJSONArray("required").toString(),
            compactParameters.getJSONArray("required").toString()
        )
        assertEquals(
            originalParameters.getJSONObject("properties").getJSONObject("mode").getJSONArray("enum").toString(),
            compactParameters.getJSONObject("properties").getJSONObject("mode").getJSONArray("enum").toString()
        )
        assertEquals(
            originalParameters.getJSONObject("properties").getJSONObject("path").getString("pattern"),
            compactParameters.getJSONObject("properties").getJSONObject("path").getString("pattern")
        )
        assertEquals(
            originalParameters.getJSONObject("properties").getJSONObject("path").getInt("minLength"),
            compactParameters.getJSONObject("properties").getJSONObject("path").getInt("minLength")
        )
        assertEquals(
            originalParameters.getJSONObject("properties").getJSONObject("count").getInt("minimum"),
            compactParameters.getJSONObject("properties").getJSONObject("count").getInt("minimum")
        )
        assertEquals(
            originalParameters.getJSONObject("properties").getJSONObject("count").getInt("maximum"),
            compactParameters.getJSONObject("properties").getJSONObject("count").getInt("maximum")
        )
        assertEquals(
            originalParameters.getBoolean("additionalProperties"),
            compactParameters.getBoolean("additionalProperties")
        )
        assertFalse(compactParameters.has("title"))
        assertFalse(compactParameters.has("examples"))

        val originalCanonical = canonicalAgentToolSchemaJson(listOf(tool))
        val compactCanonical = canonicalAgentToolSchemaJson(listOf(compacted))
        assertNotEquals(originalCanonical, compactCanonical)
        assertTrue(
            "schema compaction should materially reduce the fixture",
            compactCanonical.length < originalCanonical.length / 2
        )
    }

    @Test
    fun `property names that look like annotations remain available`() {
        val tool = AgentTool(
            name = "payload",
            description = "Read payload.",
            parameters = mapOf("title" to "Payload title", "examples" to "Payload examples"),
            requiredParams = listOf("title"),
            schemaJson = """
                {"type":"object","properties":{"title":{"type":"string"},"examples":{"type":"array"}},"required":["title"]}
            """.trimIndent()
        )

        val compacted = compactAgentToolSchemas(listOf(tool)).single()
        val schema = schemaParameters(canonicalAgentToolSchemaJson(listOf(compacted)))

        assertEquals(setOf("title", "examples"), compacted.parameters.keys)
        assertTrue(schema.getJSONObject("properties").has("title"))
        assertTrue(schema.getJSONObject("properties").has("examples"))
        assertEquals(listOf("title"), compacted.requiredParams)
    }

    private fun schemaParameters(canonical: String): JSONObject =
        JSONArray(canonical)
            .getJSONObject(0)
            .getJSONObject("function")
            .getJSONObject("parameters")

    private fun agentTool(name: String): AgentTool = AgentTool(
        name = name,
        description = "$name description",
        parameters = emptyMap()
    )
}
