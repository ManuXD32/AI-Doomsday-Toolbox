package com.example.llamadroid.service

import com.example.llamadroid.data.db.NoteDao
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.data.db.OrganizerAlarmEntity
import com.example.llamadroid.data.db.OrganizerDao
import com.example.llamadroid.data.db.OrganizerEventEntity
import com.example.llamadroid.data.db.OrganizerLlmSettingsEntity
import com.example.llamadroid.onnx.OnnxBackendOverride
import com.example.llamadroid.onnx.OnnxExecutionMode
import com.example.llamadroid.onnx.OnnxGraphOptimizationLevel
import com.example.llamadroid.onnx.OnnxRuntimeBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NativeChatToolsTest {
    @Test
    fun `config defaults keep tools disabled`() {
        val config = NativeChatToolConfig.fromApiParams(null)

        assertFalse(config.toolsEnabled)
        assertFalse(config.imageIterationEnabled)
        assertFalse(config.hasEnabledTools())
        assertEquals(12, config.maxToolRounds)
    }

    @Test
    fun `effective config requires chat and server tool switches`() {
        val chatConfig = NativeChatToolConfig(
            toolsEnabled = true,
            webSearchEnabled = true,
            fetchUrlEnabled = true,
            deepResearchEnabled = true,
            deepResearchSourceLimit = 500,
            dateTimeEnabled = true,
            calculatorEnabled = false,
            noteToolsEnabled = false,
            todoToolsEnabled = true,
            knowledgeBaseEnabled = true,
            knowledgeBaseAutoContextEnabled = true,
            imageGenerationEnabled = true,
            imageIterationEnabled = true
        )
        val serverDefaults = NativeChatToolConfig(
            toolsEnabled = true,
            webSearchEnabled = false,
            fetchUrlEnabled = true,
            deepResearchEnabled = false,
            dateTimeEnabled = false,
            calculatorEnabled = true,
            noteToolsEnabled = true,
            todoToolsEnabled = true,
            knowledgeBaseEnabled = true,
            knowledgeBaseAutoContextEnabled = false,
            imageGenerationEnabled = true,
            imageIterationEnabled = false
        )

        val effective = chatConfig.effectiveWithServerDefaults(serverDefaults)

        assertTrue(effective.toolsEnabled)
        assertFalse(effective.webSearchEnabled)
        assertTrue(effective.fetchUrlEnabled)
        assertFalse(effective.deepResearchEnabled)
        assertEquals(500, effective.deepResearchSourceLimit)
        assertFalse(effective.dateTimeEnabled)
        assertFalse(effective.calculatorEnabled)
        assertFalse(effective.noteToolsEnabled)
        assertTrue(effective.todoToolsEnabled)
        assertTrue(effective.knowledgeBaseEnabled)
        assertFalse(effective.knowledgeBaseAutoContextEnabled)
        assertTrue(effective.imageGenerationEnabled)
        assertFalse(effective.imageIterationEnabled)
    }

    @Test
    fun `server master switch hard disables chat tools`() {
        val effective = NativeChatToolConfig(
            toolsEnabled = true,
            webSearchEnabled = true,
            fetchUrlEnabled = true,
            deepResearchEnabled = true,
            dateTimeEnabled = true,
            calculatorEnabled = true,
            noteToolsEnabled = true,
            todoToolsEnabled = true,
            calendarToolsEnabled = true,
            alarmToolsEnabled = true,
            knowledgeBaseEnabled = true,
            knowledgeBaseAutoContextEnabled = true,
            imageGenerationEnabled = true,
            imageIterationEnabled = true
        ).effectiveWithServerDefaults(
            NativeChatToolConfig(
                toolsEnabled = false,
                webSearchEnabled = true,
                fetchUrlEnabled = true,
                deepResearchEnabled = true,
                dateTimeEnabled = true,
                calculatorEnabled = true,
                noteToolsEnabled = true,
                todoToolsEnabled = true,
                calendarToolsEnabled = true,
                alarmToolsEnabled = true,
                knowledgeBaseEnabled = true,
                knowledgeBaseAutoContextEnabled = true,
                imageGenerationEnabled = true,
                imageIterationEnabled = true
            )
        )

        assertFalse(effective.toolsEnabled)
        assertFalse(effective.hasEnabledTools())
        assertFalse(effective.webSearchEnabled)
        assertFalse(effective.fetchUrlEnabled)
        assertFalse(effective.deepResearchEnabled)
        assertFalse(effective.dateTimeEnabled)
        assertFalse(effective.calculatorEnabled)
        assertFalse(effective.noteToolsEnabled)
        assertFalse(effective.todoToolsEnabled)
        assertFalse(effective.calendarToolsEnabled)
        assertFalse(effective.alarmToolsEnabled)
        assertFalse(effective.knowledgeBaseEnabled)
        assertFalse(effective.knowledgeBaseAutoContextEnabled)
        assertFalse(effective.imageGenerationEnabled)
        assertFalse(effective.imageIterationEnabled)
    }

    @Test
    fun `config round trip preserves enabled tools and coerces limits`() {
        val original = NativeChatToolConfig(
            toolsEnabled = true,
            webSearchEnabled = true,
            webSearchMaxPages = 99,
            webSearchMaxChars = 99,
            kiwixSearchEnabled = true,
            kiwixServerUrl = "http://127.0.0.1:8888/",
            fetchUrlEnabled = true,
            deepResearchEnabled = true,
            deepResearchImportIntoSelectedKbEnabled = true,
            deepResearchSourceLimit = 2_500,
            noteToolsEnabled = true,
            todoToolsEnabled = true,
            calendarToolsEnabled = true,
            alarmToolsEnabled = true,
            selectedKnowledgeBaseIds = listOf(1L, 2L),
            chatDocumentKnowledgeBaseId = 7L,
            imageGenerationEnabled = true,
            imageIterationEnabled = true,
            imageParams = NativeChatImageToolParams(
                engine = NativeChatImageGenerationEngine.SD,
                model = "sd15.onnx",
                width = 768,
                height = 512,
                steps = 28,
                cfgScale = 7.25f,
                seed = "42",
                negativePrompt = "blur",
                backend = OnnxRuntimeBackend.NNAPI,
                runtimeThreads = 6,
                graphOptimizationLevel = OnnxGraphOptimizationLevel.BASIC,
                unetBackendOverride = OnnxBackendOverride.CPU,
                vaeDecoderBackendOverride = OnnxBackendOverride.NNAPI,
                vaeEncoderBackendOverride = OnnxBackendOverride.CPU,
                intraOpThreads = 3,
                interOpThreads = 2,
                executionMode = OnnxExecutionMode.PARALLEL,
                memoryPatternOptimization = false,
                cpuArenaAllocator = false,
                nnapiCpuDisabled = false,
                nnapiUseFp16 = true,
                sdParams = NativeChatSdImageToolParams(
                    model = "flux1.gguf",
                    vaePath = "ae.safetensors",
                    clipLPath = "clip_l.safetensors",
                    t5xxlPath = "t5xxl.gguf",
                    width = 640,
                    height = 768,
                    steps = 18,
                    cfgScale = 4.5f,
                    sampler = SamplingMethod.DPM_PP_2M,
                    seed = "1234",
                    negativePrompt = "low quality",
                    threads = 4,
                    flowShift = "3.0",
                    diffusionFa = true,
                    mmap = true,
                    vaeConvDirect = true
                )
            ),
            maxToolRounds = 99
        )

        val restored = NativeChatToolConfig.fromApiParams(JSONObject(original.toParamMap()).toString())

        assertTrue(restored.toolsEnabled)
        assertTrue(restored.webSearchEnabled)
        assertTrue(restored.kiwixSearchEnabled)
        assertTrue(restored.fetchUrlEnabled)
        assertTrue(restored.deepResearchEnabled)
        assertTrue(restored.deepResearchImportIntoSelectedKbEnabled)
        assertEquals(2_500, restored.deepResearchSourceLimit)
        assertTrue(restored.noteToolsEnabled)
        assertTrue(restored.todoToolsEnabled)
        assertTrue(restored.calendarToolsEnabled)
        assertTrue(restored.alarmToolsEnabled)
        assertEquals(listOf(7L), restored.knowledgeBaseScopeIds())
        assertTrue(restored.imageGenerationEnabled)
        assertTrue(restored.imageIterationEnabled)
        assertEquals(NativeChatToolConfig.MAX_SEARCH_PAGES, restored.webSearchMaxPages)
        assertEquals(NativeChatToolConfig.MIN_PAGE_CHARS, restored.webSearchMaxChars)
        assertEquals(NativeChatToolConfig.MAX_TOOL_ROUNDS, restored.maxToolRounds)
        assertEquals("http://127.0.0.1:8888", restored.kiwixServerUrl)
        assertEquals(NativeChatImageGenerationEngine.SD, restored.imageParams.engine)
        assertEquals("sd15.onnx", restored.imageParams.model)
        assertEquals(768, restored.imageParams.width)
        assertEquals(512, restored.imageParams.height)
        assertEquals(28, restored.imageParams.steps)
        assertEquals(7.25f, restored.imageParams.cfgScale, 0.0001f)
        assertEquals("42", restored.imageParams.seed)
        assertEquals("blur", restored.imageParams.negativePrompt)
        assertEquals(OnnxRuntimeBackend.NNAPI, restored.imageParams.backend)
        assertEquals(6, restored.imageParams.runtimeThreads)
        assertEquals(OnnxGraphOptimizationLevel.BASIC, restored.imageParams.graphOptimizationLevel)
        assertEquals(OnnxBackendOverride.CPU, restored.imageParams.unetBackendOverride)
        assertEquals(OnnxBackendOverride.NNAPI, restored.imageParams.vaeDecoderBackendOverride)
        assertEquals(OnnxBackendOverride.CPU, restored.imageParams.vaeEncoderBackendOverride)
        assertEquals(3, restored.imageParams.intraOpThreads)
        assertEquals(2, restored.imageParams.interOpThreads)
        assertEquals(OnnxExecutionMode.PARALLEL, restored.imageParams.executionMode)
        assertFalse(restored.imageParams.memoryPatternOptimization)
        assertFalse(restored.imageParams.cpuArenaAllocator)
        assertFalse(restored.imageParams.nnapiCpuDisabled)
        assertTrue(restored.imageParams.nnapiUseFp16)
        assertEquals("flux1.gguf", restored.imageParams.sdParams.model)
        assertEquals("ae.safetensors", restored.imageParams.sdParams.vaePath)
        assertEquals("clip_l.safetensors", restored.imageParams.sdParams.clipLPath)
        assertEquals("t5xxl.gguf", restored.imageParams.sdParams.t5xxlPath)
        assertEquals(640, restored.imageParams.sdParams.width)
        assertEquals(768, restored.imageParams.sdParams.height)
        assertEquals(18, restored.imageParams.sdParams.steps)
        assertEquals(4.5f, restored.imageParams.sdParams.cfgScale, 0.0001f)
        assertEquals(SamplingMethod.DPM_PP_2M, restored.imageParams.sdParams.sampler)
        assertEquals("1234", restored.imageParams.sdParams.seed)
        assertEquals("low quality", restored.imageParams.sdParams.negativePrompt)
        assertEquals(4, restored.imageParams.sdParams.threads)
        assertEquals("3.0", restored.imageParams.sdParams.flowShift)
        assertTrue(restored.imageParams.sdParams.diffusionFa)
        assertTrue(restored.imageParams.sdParams.mmap)
        assertTrue(restored.imageParams.sdParams.vaeConvDirect)
    }

    @Test
    fun `chat document knowledge base narrows kb scope to uploaded documents`() {
        val config = NativeChatToolConfig(
            selectedKnowledgeBaseIds = listOf(1L, 2L),
            chatDocumentKnowledgeBaseId = 7L
        )

        assertEquals(listOf(7L), config.knowledgeBaseScopeIds())
    }

    @Test
    fun `deep research default and source limit clamp have no upper cap`() {
        val defaults = NativeChatToolConfig.fromApiParams("{}")
        assertEquals(20, defaults.deepResearchSourceLimit)

        val low = NativeChatToolConfig.fromParams(
            mapOf(
                NativeChatToolConfig.KEY_DEEP_RESEARCH_ENABLED to true,
                NativeChatToolConfig.KEY_DEEP_RESEARCH_SOURCE_LIMIT to 0
            )
        )
        assertTrue(low.deepResearchEnabled)
        assertEquals(1, low.deepResearchSourceLimit)

        val high = NativeChatToolConfig.fromParams(
            mapOf(NativeChatToolConfig.KEY_DEEP_RESEARCH_SOURCE_LIMIT to 25_000)
        )
        assertEquals(25_000, high.deepResearchSourceLimit)
    }

    @Test
    fun `deep research respects server hard disable`() {
        val effective = NativeChatToolConfig(
            toolsEnabled = true,
            deepResearchEnabled = true,
            deepResearchImportIntoSelectedKbEnabled = true
        ).effectiveWithServerDefaults(
            NativeChatToolConfig(
                toolsEnabled = true,
                deepResearchEnabled = false,
                deepResearchImportIntoSelectedKbEnabled = true
            )
        )

        assertFalse(effective.deepResearchEnabled)
        assertFalse(effective.deepResearchImportIntoSelectedKbEnabled)
    }

    @Test
    fun `selected kb deep research import requires chat and server toggle`() {
        val chatAllowed = NativeChatToolConfig(
            toolsEnabled = true,
            deepResearchEnabled = true,
            deepResearchImportIntoSelectedKbEnabled = true
        )
        val serverBlocked = NativeChatToolConfig(
            toolsEnabled = true,
            deepResearchEnabled = true,
            deepResearchImportIntoSelectedKbEnabled = false
        )
        val serverAllowed = serverBlocked.copy(deepResearchImportIntoSelectedKbEnabled = true)

        assertFalse(chatAllowed.effectiveWithServerDefaults(serverBlocked).deepResearchImportIntoSelectedKbEnabled)
        assertTrue(chatAllowed.effectiveWithServerDefaults(serverAllowed).deepResearchImportIntoSelectedKbEnabled)
        assertFalse(
            chatAllowed.copy(deepResearchEnabled = false)
                .effectiveWithServerDefaults(serverAllowed)
                .deepResearchImportIntoSelectedKbEnabled
        )
    }

    @Test
    fun `chat document only config exposes only kb tools`() {
        val tools = NativeChatToolRuntime().availableTools(
            NativeChatToolConfig(
                toolsEnabled = true,
                dateTimeEnabled = false,
                calculatorEnabled = false,
                knowledgeBaseEnabled = true,
                knowledgeBaseAutoContextEnabled = true,
                selectedKnowledgeBaseIds = listOf(1L, 2L),
                chatDocumentKnowledgeBaseId = 7L
            )
        ).map { it.name }.toSet()

        assertEquals(
            setOf(
                NativeChatToolRuntime.TOOL_KB_SEARCH,
                NativeChatToolRuntime.TOOL_KB_READ_CHUNK,
                NativeChatToolRuntime.TOOL_KB_READ_SOURCE,
                NativeChatToolRuntime.TOOL_KB_LIST_SOURCES
            ),
            tools
        )
    }

    @Test
    fun `chat document forces kb tools when chat tools are disabled`() {
        val effective = NativeChatToolConfig(
            toolsEnabled = false,
            webSearchEnabled = true,
            dateTimeEnabled = true,
            calculatorEnabled = true,
            knowledgeBaseEnabled = false,
            knowledgeBaseAutoContextEnabled = false,
            chatDocumentKnowledgeBaseId = 7L
        ).effectiveWithServerDefaults(
            NativeChatToolConfig(
                toolsEnabled = true,
                webSearchEnabled = true,
                dateTimeEnabled = true,
                calculatorEnabled = true,
                knowledgeBaseEnabled = true,
                knowledgeBaseAutoContextEnabled = true
            )
        )

        assertTrue(effective.toolsEnabled)
        assertTrue(effective.knowledgeBaseEnabled)
        assertTrue(effective.knowledgeBaseAutoContextEnabled)
        assertFalse(effective.webSearchEnabled)
        assertFalse(effective.dateTimeEnabled)
        assertFalse(effective.calculatorEnabled)
        val tools = NativeChatToolRuntime().availableTools(effective).map { it.name }.toSet()
        assertEquals(
            setOf(
                NativeChatToolRuntime.TOOL_KB_SEARCH,
                NativeChatToolRuntime.TOOL_KB_READ_CHUNK,
                NativeChatToolRuntime.TOOL_KB_READ_SOURCE,
                NativeChatToolRuntime.TOOL_KB_LIST_SOURCES
            ),
            tools
        )
    }

    @Test
    fun `chat master switch hard disables server enabled tools`() {
        val effective = NativeChatToolConfig(
            toolsEnabled = false,
            webSearchEnabled = true,
            fetchUrlEnabled = true,
            dateTimeEnabled = true,
            calculatorEnabled = true,
            noteToolsEnabled = true,
            todoToolsEnabled = true,
            calendarToolsEnabled = true,
            alarmToolsEnabled = true,
            knowledgeBaseEnabled = true,
            knowledgeBaseAutoContextEnabled = true,
            imageGenerationEnabled = true,
            imageIterationEnabled = true
        ).effectiveWithServerDefaults(
            NativeChatToolConfig(
                toolsEnabled = true,
                webSearchEnabled = true,
                fetchUrlEnabled = true,
                dateTimeEnabled = true,
                calculatorEnabled = true,
                noteToolsEnabled = true,
                todoToolsEnabled = true,
                calendarToolsEnabled = true,
                alarmToolsEnabled = true,
                knowledgeBaseEnabled = true,
                knowledgeBaseAutoContextEnabled = true,
                imageGenerationEnabled = true,
                imageIterationEnabled = true
            )
        )

        assertFalse(effective.toolsEnabled)
        assertFalse(effective.hasEnabledTools())
        assertTrue(NativeChatToolRuntime().availableTools(effective).isEmpty())
    }

    @Test
    fun `server master switch hard disables chat document kb tools`() {
        val effective = NativeChatToolConfig(
            toolsEnabled = false,
            knowledgeBaseEnabled = false,
            chatDocumentKnowledgeBaseId = 7L
        ).effectiveWithServerDefaults(
            NativeChatToolConfig(
                toolsEnabled = false,
                knowledgeBaseEnabled = true,
                knowledgeBaseAutoContextEnabled = true
            )
        )

        assertFalse(effective.toolsEnabled)
        assertFalse(effective.knowledgeBaseEnabled)
        assertTrue(NativeChatToolRuntime().availableTools(effective).isEmpty())
    }


    @Test
    fun `available tools respect enabled config`() {
        val runtime = NativeChatToolRuntime()
        val availableTools = runtime.availableTools(
            NativeChatToolConfig(
                toolsEnabled = true,
                webSearchEnabled = true,
                calculatorEnabled = true,
                noteToolsEnabled = true,
                todoToolsEnabled = true,
                calendarToolsEnabled = true,
                alarmToolsEnabled = true,
                imageGenerationEnabled = true
            )
        )
        val tools = availableTools.map { it.name }.toSet()

        assertTrue(NativeChatToolRuntime.TOOL_WEB_SEARCH in tools)
        assertTrue(NativeChatToolRuntime.TOOL_SEARCH_PAGE in tools)
        assertTrue(NativeChatToolRuntime.TOOL_CALCULATOR in tools)
        assertTrue(NativeChatToolRuntime.TOOL_LIST_NOTES in tools)
        assertTrue(NativeChatToolRuntime.TOOL_READ_NOTE in tools)
        assertTrue(NativeChatToolRuntime.TOOL_CREATE_NOTE in tools)
        assertTrue(NativeChatToolRuntime.TOOL_REPLACE_NOTE_TEXT in tools)
        assertTrue(NativeChatToolRuntime.TOOL_CREATE_TODO_LIST in tools)
        assertTrue(NativeChatToolRuntime.TOOL_SET_TODO_ITEM_CHECKED in tools)
        assertTrue(NativeChatToolRuntime.TOOL_LIST_CALENDAR_EVENTS in tools)
        assertTrue(NativeChatToolRuntime.TOOL_CREATE_CALENDAR_EVENT in tools)
        assertTrue(NativeChatToolRuntime.TOOL_LIST_ALARMS in tools)
        assertTrue(NativeChatToolRuntime.TOOL_CREATE_ALARM in tools)
        assertTrue(NativeChatToolRuntime.TOOL_GENERATE_IMAGE in tools)
        assertEquals(1, availableTools.count { it.name == NativeChatToolRuntime.TOOL_GENERATE_IMAGE })
        assertFalse(NativeChatToolRuntime.TOOL_KIWIX_SEARCH in tools)
    }

    @Test
    fun `generate image stays one advertised tool and hides when disabled`() {
        val runtime = NativeChatToolRuntime()
        val enabledTools = runtime.availableTools(
            NativeChatToolConfig(
                toolsEnabled = true,
                imageGenerationEnabled = true
            )
        )
        val imageTools = enabledTools.filter { it.name == NativeChatToolRuntime.TOOL_GENERATE_IMAGE }

        assertEquals(1, imageTools.size)
        assertEquals(setOf("prompt", "negative_prompt"), imageTools.single().parameters.keys)
        assertEquals(listOf("prompt"), imageTools.single().requiredParams)

        val disabledTools = runtime.availableTools(
            NativeChatToolConfig(
                toolsEnabled = true,
                imageGenerationEnabled = false
            )
        ).map { it.name }

        assertFalse(NativeChatToolRuntime.TOOL_GENERATE_IMAGE in disabledTools)
    }

    @Test
    fun `fetch url policy allows localhost while blocking other private addresses`() {
        assertEquals(null, blockedNativeFetchUrlReason("http://127.0.0.1:8888/wiki/Main_Page"))
        assertEquals(null, blockedNativeFetchUrlReason("http://localhost:8888/wiki/Main_Page"))
        assertEquals(null, blockedNativeFetchUrlReason("http://[::1]:8888/wiki/Main_Page"))
        assertNotNull(blockedNativeFetchUrlReason("http://192.168.1.20/wiki/Main_Page"))
        assertNotNull(blockedNativeFetchUrlReason("file:///tmp/wiki.zim"))
        assertEquals(null, blockedKiwixBaseUrlReason("http://127.0.0.1:8888"))
        assertEquals(null, blockedKiwixBaseUrlReason("http://localhost:8888"))
        assertNotNull(blockedKiwixBaseUrlReason("file:///tmp/wiki.zim"))
    }

    @Test
    fun `calculator evaluates arithmetic without code eval`() {
        assertEquals(14.0, evaluateNativeCalculatorExpression("2 + 3 * 4"), 0.000001)
        assertEquals(512.0, evaluateNativeCalculatorExpression("2^3^2"), 0.000001)
    }

    @Test
    fun `todo parser accepts markdown lines and formats normalized task list`() {
        val items = parseNativeTodoItemsFromToolInput(
            """
            - [ ] Buy rice
            - [x] Start Kiwix
            """.trimIndent()
        )

        assertEquals(listOf(false, true), items.map { it.checked })
        assertEquals(listOf("Buy rice", "Start Kiwix"), items.map { it.text })
        assertEquals("- [ ] Buy rice\n- [x] Start Kiwix", formatNativeTodoItems(items))
    }

    @Test
    fun `todo parser accepts json arrays`() {
        val items = parseNativeTodoItemsFromToolInput(
            """[{"text":"One","checked":true},"Two"]"""
        )

        assertEquals(2, items.size)
        assertEquals(NativeTodoItem("One", true), items[0])
        assertEquals(NativeTodoItem("Two", false), items[1])
    }

    @Test
    fun `tool note preview strips todo checkbox markdown`() {
        val preview = markdownPreviewForTool("- [x] Send documents\n- [ ] Call team", 120)

        assertEquals("Send documents Call team", preview)
    }

    @Test
    fun `search summary skips non text content and stays compact`() {
        assertTrue(isNativeToolReadableContentType("application/pdf"))
        assertTrue(isNativeToolPdfContentType("application/pdf; charset=binary"))
        assertTrue(isNativeToolReadableContentType("text/html; charset=utf-8"))
        assertTrue(looksLikeNativeNonTextContent("%PDF-1.6\n1 0 obj"))

        val skipped = summarizeNativeSearchTextForTool(nativeToolTextContentSkippedMessage("image/png"))
        assertTrue(skipped.contains("non_text_content_skipped"))
        assertFalse(skipped.contains("obj"))

        val summary = summarizeNativeSearchTextForTool(
            """
            Cookie settings
            Gemma models are lightweight open models for text generation. They can run with llama.cpp or Ollama on local devices.
            Tool calling quality depends on the server template and the model instruction tuning. Compact summaries reduce context pressure.
            Fetching a full page is still useful when the assistant needs exact details from one source.
            """.trimIndent(),
            maxChars = 220
        )
        assertTrue(summary.contains("Gemma models"))
        assertTrue(summary.length <= 232)
    }

    @Test
    fun `source citation markdown escapes labels and keeps source url`() {
        assertEquals(
            "[Example \\[Docs\\]](https://example.com/docs)",
            NativeChatToolRuntime.sourceCitationMarkdown("Example [Docs]", "https://example.com/docs")
        )
    }

    @Test
    fun `source citation block instructs models to use markdown links`() {
        val block = NativeChatToolRuntime.sourceCitationBlock(
            listOf(
                "1. [Example](https://example.com)",
                "2. [Docs](https://example.com/docs)"
            )
        )

        assertTrue(block.contains("source_citations:"))
        assertTrue(block.contains("1. [Example](https://example.com)"))
        assertTrue(block.contains("final_answer_requirement"))
        assertTrue(block.contains("Do not use bare [1]"))
    }

    @Test
    fun `fetch url skips oversized pdf before parsing`() = runBlocking {
        val oversizedPdf = ByteArray(4 * 1024 * 1024) { '%'.code.toByte() }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(oversizedPdf.toResponseBody("application/pdf".toMediaType()))
                    .build()
            }
            .build()
        val runtime = NativeChatToolRuntime(
            clientFactory = { client },
            pdfTextExtractor = { _, _ -> error("Oversized PDFs should be skipped before parsing.") }
        )

        val result = runtime.executeToolCall(
            toolCall = OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_FETCH_URL,
                mapOf("url" to "https://example.com/large.pdf")
            ),
            config = NativeChatToolConfig(toolsEnabled = true, fetchUrlEnabled = true)
        ).getOrThrow().content

        assertTrue(result.contains("pdf_skipped"))
    }

    @Test
    fun `search page finds matching page links and snippets`() = runBlocking {
        val html = """
            <html>
              <body>
                <nav>
                  <a href="/ggerganov/llama.cpp">Code</a>
                  <a href="/ggerganov/llama.cpp/commits/master" aria-label="Commits history">Commits</a>
                  <a href="/ggerganov/llama.cpp/releases">Releases</a>
                </nav>
                <main>
                  The latest commits page contains changes to llama.cpp server handling, tool call parsing, and docs.
                </main>
              </body>
            </html>
        """.trimIndent()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
        val runtime = NativeChatToolRuntime(clientFactory = { client })

        val result = runtime.executeToolCall(
            toolCall = OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_SEARCH_PAGE,
                mapOf(
                    "url" to "https://github.com/ggerganov/llama.cpp",
                    "query" to "commits",
                    "max_links" to "5"
                )
            ),
            config = NativeChatToolConfig(toolsEnabled = true, webSearchEnabled = true)
        ).getOrThrow().content

        assertTrue(result.contains("tool: search_page"))
        assertTrue(result.contains("https://github.com/ggerganov/llama.cpp/commits/master"))
        assertTrue(result.contains("latest commits page"))
        assertTrue(result.contains("source_citations:"))
        assertTrue(result.contains("final_answer_requirement"))
        assertTrue(result.contains("[Commits](https://github.com/ggerganov/llama.cpp/commits/master)"))
        assertFalse(result.contains("https://github.com/ggerganov/llama.cpp/releases\n"))
    }

    @Test
    fun `replace note text supports first all case sensitivity and no match`() {
        assertEquals(
            NativeNoteTextReplacement("one TWO two", 1),
            replaceNativeNoteText("one two two", "two", "TWO", replaceAll = false, caseSensitive = true)
        )
        assertEquals(
            NativeNoteTextReplacement("one TWO TWO", 2),
            replaceNativeNoteText("one two two", "two", "TWO", replaceAll = true, caseSensitive = true)
        )
        assertEquals(
            NativeNoteTextReplacement("X X", 2),
            replaceNativeNoteText("Todo todo", "todo", "X", replaceAll = true, caseSensitive = false)
        )
        assertEquals(
            NativeNoteTextReplacement("Todo todo", 0),
            replaceNativeNoteText("Todo todo", "missing", "X", replaceAll = true, caseSensitive = true)
        )
    }

    @Test
    fun `note tools only expose and edit whitelisted notes`() = runBlocking {
        val noteDao = FakeNoteDao(
            listOf(
                NoteEntity(
                    id = 1,
                    title = "Allowed",
                    content = "Call Alice",
                    type = NoteType.MANUAL,
                    isLlmWhitelisted = true
                ),
                NoteEntity(
                    id = 2,
                    title = "Private",
                    content = "hidden",
                    type = NoteType.MANUAL,
                    isLlmWhitelisted = false
                )
            )
        )
        val runtime = NativeChatToolRuntime(noteDao = noteDao)
        val config = NativeChatToolConfig(toolsEnabled = true, noteToolsEnabled = true, todoToolsEnabled = true)

        val listResult = runtime.executeToolCall(
            toolCall = OllamaService.ToolCall(NativeChatToolRuntime.TOOL_LIST_NOTES, emptyMap()),
            config = config
        ).getOrThrow().content
        assertTrue(listResult.contains("Allowed"))
        assertFalse(listResult.contains("Private"))

        val blockedRead = runtime.executeToolCall(
            OllamaService.ToolCall(NativeChatToolRuntime.TOOL_READ_NOTE, mapOf("note_id" to "2")),
            config
        )
        assertTrue(blockedRead.isFailure)

        val replaceResult = runtime.executeToolCall(
            OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_REPLACE_NOTE_TEXT,
                mapOf("note_id" to "1", "find_text" to "Alice", "replacement_text" to "Bob")
            ),
            config
        ).getOrThrow().content

        assertTrue(replaceResult.contains("replacements: 1"))
        assertEquals("Call Bob", noteDao.notes.getValue(1).content)
    }

    @Test
    fun `create note tool creates whitelisted organizer note`() = runBlocking {
        val noteDao = FakeNoteDao(emptyList())
        val runtime = NativeChatToolRuntime(noteDao = noteDao)

        val result = runtime.executeToolCall(
            toolCall = OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_CREATE_NOTE,
                mapOf("title" to "Tech news", "content" to "Summary with sources")
            ),
            config = NativeChatToolConfig(toolsEnabled = true, noteToolsEnabled = true)
        ).getOrThrow().content

        assertTrue(result.contains("note_id: 1"))
        val note = noteDao.notes.getValue(1)
        assertEquals("Tech news", note.title)
        assertEquals("Summary with sources", note.content)
        assertEquals(NoteType.MANUAL, note.type)
        assertTrue(note.isLlmWhitelisted)
    }

    @Test
    fun `note tools accept common argument aliases and return updated state`() = runBlocking {
        val noteDao = FakeNoteDao(
            listOf(
                NoteEntity(
                    id = 10,
                    title = "Research",
                    content = "Alpha\nBeta",
                    type = NoteType.MANUAL,
                    isLlmWhitelisted = true
                ),
                NoteEntity(
                    id = 11,
                    title = "Tasks",
                    content = "- [ ] send docs\n- [ ] call Ana",
                    type = NoteType.TODO_LIST,
                    isLlmWhitelisted = true
                )
            )
        )
        val runtime = NativeChatToolRuntime(noteDao = noteDao)
        val config = NativeChatToolConfig(toolsEnabled = true, noteToolsEnabled = true, todoToolsEnabled = true)

        val updateResult = runtime.executeToolCall(
            OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_UPDATE_NOTE,
                mapOf("id" to "10.0", "appendText" to "Gamma")
            ),
            config
        ).getOrThrow().content
        assertTrue(updateResult.contains("preview:"))
        assertEquals("Alpha\nBeta\n\nGamma", noteDao.notes.getValue(10).content)

        val replaceResult = runtime.executeToolCall(
            OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_REPLACE_NOTE_TEXT,
                mapOf(
                    "noteId" to "10",
                    "oldText" to "beta",
                    "newText" to "Delta",
                    "replaceAll" to "yes",
                    "matchCase" to "false"
                )
            ),
            config
        ).getOrThrow().content
        assertTrue(replaceResult.contains("replacements: 1"))
        assertEquals("Alpha\nDelta\n\nGamma", noteDao.notes.getValue(10).content)

        val checkedResult = runtime.executeToolCall(
            OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_SET_TODO_ITEM_CHECKED,
                mapOf("id" to "11", "index" to "1.0", "done" to "completed")
            ),
            config
        ).getOrThrow().content
        assertTrue(checkedResult.contains("1. [x] send docs"))
        assertTrue(noteDao.notes.getValue(11).content.startsWith("- [x] send docs"))

        val removeResult = runtime.executeToolCall(
            OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_REMOVE_TODO_ITEM,
                mapOf("noteId" to "11", "position" to "2")
            ),
            config
        ).getOrThrow().content
        assertTrue(removeResult.contains("removed: call Ana"))
        assertEquals("- [x] send docs", noteDao.notes.getValue(11).content)
    }

    @Test
    fun `calendar event retrieval includes localized weekday names`() = runBlocking {
        val madrid = ZoneId.of("Europe/Madrid")
        val event = OrganizerEventEntity(
            id = 7L,
            title = "Planning",
            startAtMillis = ZonedDateTime.of(2026, 7, 20, 9, 0, 0, 0, madrid).toInstant().toEpochMilli(),
            endAtMillis = ZonedDateTime.of(2026, 7, 21, 10, 0, 0, 0, madrid).toInstant().toEpochMilli(),
            timezoneId = madrid.id
        )
        val runtime = NativeChatToolRuntime(organizerDao = FakeOrganizerDao(events = listOf(event)))

        val result = runtime.executeToolCall(
            toolCall = OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_LIST_CALENDAR_EVENTS,
                mapOf("query" to "Planning")
            ),
            config = NativeChatToolConfig(toolsEnabled = true, calendarToolsEnabled = true)
        ).getOrThrow().content

        assertTrue(result.contains("start_datetime: 2026-07-20T09:00:00+02:00"))
        assertTrue(result.contains("start_day_of_week: Monday"))
        assertTrue(result.contains("start_day_of_week_es: lunes"))
        assertTrue(result.contains("end_datetime: 2026-07-21T10:00:00+02:00"))
        assertTrue(result.contains("end_day_of_week: Tuesday"))
        assertTrue(result.contains("end_day_of_week_es: martes"))
    }

    @Test
    fun `pinned note config exposes only pinned note tool for note access`() {
        val effective = NativeChatToolConfig(
            toolsEnabled = true,
            noteToolsEnabled = true,
            todoToolsEnabled = true,
            pinnedNoteId = 42
        ).effectiveWithServerDefaults(
            NativeChatToolConfig(
                toolsEnabled = true,
                noteToolsEnabled = true,
                todoToolsEnabled = true
            )
        )

        val names = NativeChatToolRuntime().availableTools(effective).map { it.name }.toSet()

        assertTrue(names.contains(NativeChatToolRuntime.TOOL_MODIFY_PINNED_NOTE))
        assertFalse(names.contains(NativeChatToolRuntime.TOOL_CREATE_NOTE))
        assertFalse(names.contains(NativeChatToolRuntime.TOOL_UPDATE_NOTE))
        assertFalse(names.contains(NativeChatToolRuntime.TOOL_REPLACE_NOTE_TEXT))
        assertFalse(names.contains(NativeChatToolRuntime.TOOL_LIST_NOTES))
        assertFalse(names.contains(NativeChatToolRuntime.TOOL_CREATE_TODO_LIST))
    }

    @Test
    fun `pinned note config rejects normal note mutation tools`() = runBlocking {
        val noteDao = FakeNoteDao(
            listOf(
                NoteEntity(
                    id = 42,
                    title = "Pinned",
                    content = "old text",
                    type = NoteType.MANUAL,
                    isLlmWhitelisted = true
                )
            )
        )
        val runtime = NativeChatToolRuntime(noteDao = noteDao)
        val config = NativeChatToolConfig(
            toolsEnabled = true,
            noteToolsEnabled = true,
            pinnedNoteId = 42
        )

        val createResult = runtime.executeToolCall(
            OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_CREATE_NOTE,
                mapOf("title" to "Other", "content" to "Nope")
            ),
            config
        )
        val pinnedResult = runtime.executeToolCall(
            OllamaService.ToolCall(
                NativeChatToolRuntime.TOOL_MODIFY_PINNED_NOTE,
                mapOf(
                    "operation" to "replace_text",
                    "find_text" to "old",
                    "replacement_text" to "new"
                )
            ),
            config
        )

        assertTrue(createResult.isFailure)
        assertTrue(pinnedResult.isSuccess)
        assertEquals("new text", noteDao.notes.getValue(42).content)
    }
}

private class FakeNoteDao(initialNotes: List<NoteEntity>) : NoteDao {
    val notes: MutableMap<Int, NoteEntity> = initialNotes.associateBy { it.id }.toMutableMap()
    private var nextId: Int = (notes.keys.maxOrNull() ?: 0) + 1

    override fun getAllNotes(): Flow<List<NoteEntity>> = flowOf(notes.values.sortedByDescending { it.updatedAt })

    override suspend fun getAllNotesOnce(): List<NoteEntity> = notes.values.sortedByDescending { it.updatedAt }

    override fun getNotesByType(type: NoteType): Flow<List<NoteEntity>> =
        flowOf(notes.values.filter { it.type == type }.sortedByDescending { it.updatedAt })

    override fun searchNotes(query: String): Flow<List<NoteEntity>> =
        flowOf(
            notes.values.filter {
                it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true)
            }.sortedByDescending { it.updatedAt }
        )

    override suspend fun getNoteById(id: Int): NoteEntity? = notes[id]

    override suspend fun insert(note: NoteEntity): Long {
        val id = if (note.id == 0) nextId++ else note.id
        notes[id] = note.copy(id = id)
        return id.toLong()
    }

    override suspend fun update(note: NoteEntity) {
        notes[note.id] = note
    }

    override suspend fun delete(note: NoteEntity) {
        notes.remove(note.id)
    }

    override suspend fun deleteById(id: Int) {
        notes.remove(id)
    }

    override suspend fun deleteByIds(ids: List<Int>) {
        ids.forEach { notes.remove(it) }
    }

    override suspend fun setLlmWhitelisted(ids: List<Int>, allowed: Boolean) {
        ids.forEach { id ->
            notes[id]?.let { notes[id] = it.copy(isLlmWhitelisted = allowed) }
        }
    }

    override fun getNoteCount(): Flow<Int> = flowOf(notes.size)

    override fun getNoteCountByType(type: NoteType): Flow<Int> = flowOf(notes.values.count { it.type == type })
}

private class FakeOrganizerDao(
    events: List<OrganizerEventEntity> = emptyList(),
    alarms: List<OrganizerAlarmEntity> = emptyList(),
    private var settings: OrganizerLlmSettingsEntity? = null
) : OrganizerDao {
    private val eventsById: MutableMap<Long, OrganizerEventEntity> = events.associateBy { it.id }.toMutableMap()
    private val alarmsById: MutableMap<Long, OrganizerAlarmEntity> = alarms.associateBy { it.id }.toMutableMap()
    private var nextEventId: Long = (eventsById.keys.maxOrNull() ?: 0L) + 1L
    private var nextAlarmId: Long = (alarmsById.keys.maxOrNull() ?: 0L) + 1L

    override fun getAllEvents(): Flow<List<OrganizerEventEntity>> = flowOf(sortedEvents())

    override suspend fun getAllEventsOnce(): List<OrganizerEventEntity> = sortedEvents()

    override fun getEventsInRange(
        rangeStartMillis: Long,
        rangeEndMillis: Long
    ): Flow<List<OrganizerEventEntity>> = flowOf(eventsInRange(rangeStartMillis, rangeEndMillis))

    override suspend fun getEventsInRangeOnce(
        rangeStartMillis: Long,
        rangeEndMillis: Long
    ): List<OrganizerEventEntity> = eventsInRange(rangeStartMillis, rangeEndMillis)

    override suspend fun getEventById(id: Long): OrganizerEventEntity? = eventsById[id]

    override suspend fun insertEvent(event: OrganizerEventEntity): Long {
        val id = if (event.id == 0L) nextEventId++ else event.id
        eventsById[id] = event.copy(id = id)
        return id
    }

    override suspend fun updateEvent(event: OrganizerEventEntity) {
        eventsById[event.id] = event
    }

    override suspend fun deleteEvent(event: OrganizerEventEntity) {
        eventsById.remove(event.id)
    }

    override suspend fun deleteEventById(id: Long) {
        eventsById.remove(id)
    }

    override fun getAllAlarms(): Flow<List<OrganizerAlarmEntity>> = flowOf(sortedAlarms())

    override suspend fun getAllAlarmsOnce(): List<OrganizerAlarmEntity> = sortedAlarms()

    override suspend fun getAlarmsForEventOnce(eventId: Long): List<OrganizerAlarmEntity> =
        sortedAlarms().filter { it.eventId == eventId }

    override suspend fun getAlarmById(id: Long): OrganizerAlarmEntity? = alarmsById[id]

    override suspend fun getEnabledFutureAlarms(nowMillis: Long): List<OrganizerAlarmEntity> =
        sortedAlarms().filter { it.enabled && it.triggerAtMillis >= nowMillis }

    override suspend fun insertAlarm(alarm: OrganizerAlarmEntity): Long {
        val id = if (alarm.id == 0L) nextAlarmId++ else alarm.id
        alarmsById[id] = alarm.copy(id = id)
        return id
    }

    override suspend fun updateAlarm(alarm: OrganizerAlarmEntity) {
        alarmsById[alarm.id] = alarm
    }

    override suspend fun deleteAlarm(alarm: OrganizerAlarmEntity) {
        alarmsById.remove(alarm.id)
    }

    override suspend fun deleteAlarmById(id: Long) {
        alarmsById.remove(id)
    }

    override suspend fun markAlarmDelivered(id: Long, deliveredAt: Long) {
        alarmsById[id]?.let { alarm ->
            alarmsById[id] = alarm.copy(enabled = false, deliveredAt = deliveredAt, updatedAt = deliveredAt)
        }
    }

    override fun getLlmSettings(): Flow<OrganizerLlmSettingsEntity?> = flowOf(settings)

    override suspend fun getLlmSettingsOnce(): OrganizerLlmSettingsEntity? = settings

    override suspend fun upsertLlmSettings(settings: OrganizerLlmSettingsEntity) {
        this.settings = settings
    }

    private fun sortedEvents(): List<OrganizerEventEntity> =
        eventsById.values.sortedWith(compareBy<OrganizerEventEntity> { it.startAtMillis }.thenBy { it.id })

    private fun eventsInRange(rangeStartMillis: Long, rangeEndMillis: Long): List<OrganizerEventEntity> =
        sortedEvents().filter { event ->
            event.startAtMillis <= rangeEndMillis && (event.endAtMillis ?: event.startAtMillis) >= rangeStartMillis
        }

    private fun sortedAlarms(): List<OrganizerAlarmEntity> =
        alarmsById.values.sortedWith(compareBy<OrganizerAlarmEntity> { it.triggerAtMillis }.thenBy { it.id })
}
