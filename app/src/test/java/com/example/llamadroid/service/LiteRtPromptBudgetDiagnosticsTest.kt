package com.example.llamadroid.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtPromptBudgetDiagnosticsTest {
    @Test
    fun pinnedStandardAndAppToolSchemasAreCountedWithoutLoggingContent() {
        val tools = pinnedStandardAndAppTools()
        val privatePrompt = "PRIVATE_PROMPT_MARKER " + "Use available tools carefully. ".repeat(360)
        val conversation = LiteRtConversationOverride(
            systemInstruction = privatePrompt,
            initialMessages = emptyList(),
            userMessage = "continue",
            tools = tools,
        )
        val renderedPrompt = renderLiteRtPromptForEstimate(conversation)
        val expectedSchemaBytes = tools.sumOf { tool ->
            JSONObject(tool.toLiteRtOpenApiToolJson())
                .getJSONObject("parameters")
                .toString()
                .toByteArray(Charsets.UTF_8)
                .size
        }

        val sixteenK = liteRtPromptBudgetDiagnostics(
            prompt = renderedPrompt,
            tools = tools,
            engineMaxTokens = 16_384,
            requestedOutputTokens = 2_048,
        )
        val eightK = liteRtPromptBudgetDiagnostics(
            prompt = renderedPrompt,
            tools = tools,
            engineMaxTokens = 8_192,
            requestedOutputTokens = 2_048,
        )

        assertEquals(5, sixteenK.compactToolCount)
        assertEquals(expectedSchemaBytes, sixteenK.compactToolSchemaUtf8Bytes)
        assertEquals(13_312, sixteenK.availableInputTokens)
        assertTrue("Pinned DSH + app schemas should fit a normal 16K turn", sixteenK.fits)
        assertEquals(5_632, eightK.availableInputTokens)
        assertFalse("The longer harness prompt should be reported as over budget at 8K", eightK.fits)

        val safeLine = eightK.toSafeDiagnosticLine("over_limit")
        assertTrue(safeLine.contains("compactToolCount=5"))
        assertTrue(safeLine.contains("compactToolSchemaUtf8Bytes=$expectedSchemaBytes"))
        assertTrue(safeLine.contains("result=over_limit"))
        assertFalse(safeLine.contains("PRIVATE_PROMPT_MARKER"))
        assertFalse(safeLine.contains("file_path"))
        assertFalse(safeLine.contains("images_generate"))
        assertEquals(
            listOf("read", "web_search", "web_fetch", "images_generate", "app_web_search"),
            tools.map { it.name },
        )
    }

    private fun pinnedStandardAndAppTools(): List<LiteRtToolDefinition> = listOf(
        LiteRtToolDefinition(
            name = "read",
            description = "Read a UTF-8 text file and return line-numbered content.",
            parameters = emptyMap(),
            parameterSchemaJson = """{
                "type":"object",
                "properties":{
                    "file_path":{"type":"string","description":"Path to read, resolved by the filesystem backend."},
                    "offset":{"type":"number","description":"1-based first line to return. Defaults to 1."},
                    "limit":{"type":"number","description":"Maximum number of lines to return."}
                },
                "required":["file_path"]
            }""",
        ),
        LiteRtToolDefinition(
            name = "web_search",
            description = "Search the web for current information. Provide queries in the required queries array.",
            parameters = emptyMap(),
            parameterSchemaJson = """{
                "type":"object",
                "properties":{
                    "queries":{"type":"array","items":{"type":"string"},"description":"Required search queries; accepts 1–4 items and merges their results."}
                },
                "required":["queries"]
            }""",
        ),
        LiteRtToolDefinition(
            name = "web_fetch",
            description = "Fetch the content of a specific HTTP(S) URL and return it decoded to text.",
            parameters = emptyMap(),
            parameterSchemaJson = """{
                "type":"object",
                "properties":{"url":{"type":"string","description":"The HTTP(S) URL to fetch."}},
                "required":["url"]
            }""",
        ),
        LiteRtToolDefinition(
            name = "images_generate",
            description = "Generate an image through the configured app image provider.",
            parameters = emptyMap(),
            parameterSchemaJson = """{
                "type":"object",
                "properties":{
                    "prompt":{"type":"string","description":"Image generation prompt."},
                    "negativePrompt":{"type":"string","description":"Optional negative prompt."},
                    "outputPath":{"type":"string","description":"Workspace-relative output path."}
                },
                "required":["prompt","outputPath"]
            }""",
        ),
        LiteRtToolDefinition(
            name = "app_web_search",
            description = "Search the web with the app-owned search tool. Works with custom models and does not require provider-native web search.",
            parameters = emptyMap(),
            parameterSchemaJson = """{
                "type":"object",
                "properties":{"query":{"type":"string","description":"Search query, up to 240 characters."}},
                "required":["query"]
            }""",
        ),
    )
}
