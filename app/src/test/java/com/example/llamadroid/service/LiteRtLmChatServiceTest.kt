package com.example.llamadroid.service

import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.estimateNativeChatTextTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmChatServiceTest {
    @Test
    fun `sanitizeLiteRtRenderedText removes turn tokens before markdown rendering`() {
        val raw = """
            <|start_of_turn|>model
            Hay informacion sobre la otitis.
            <|end_of_turn|>
            <|start_of_turn|>thought
            <|end_of_turn|>
        """.trimIndent()

        val cleaned = sanitizeLiteRtRenderedText(raw)

        assertEquals("Hay informacion sobre la otitis.", cleaned.trim())
        assertFalse(cleaned.contains("|"))
        assertFalse(cleaned.contains("start_of_turn"))
        assertFalse(cleaned.contains("end_of_turn"))
    }

    @Test
    fun `sanitizeLiteRtRenderedText keeps tool calls and ordinary angle text`() {
        val raw = """
            <|start_header_id|>assistant<|end_header_id|>
            Compare 2 < 3 and emit <tool_call>{"name":"web_search"}</tool_call>.
            <|eot_id|>
        """.trimIndent()

        val cleaned = sanitizeLiteRtRenderedText(raw)

        assertTrue(cleaned.contains("2 < 3"))
        assertTrue(cleaned.contains("<tool_call>"))
        assertFalse(cleaned.contains("start_header_id"))
        assertFalse(cleaned.contains("eot_id"))
    }

    @Test
    fun `extractLiteRtToolCallPayloads accepts stripped LiteRT tool call tags`() {
        val extracted = extractLiteRtToolCallPayloads(
            """_call>{"name":"web_search","arguments":{"query":"recetadetartadequeso"}}</_call>"""
        )

        assertEquals("", extracted.visibleContent)
        assertEquals(
            """{"name":"web_search","arguments":{"query":"recetadetartadequeso"}}""",
            extracted.payloads.single()
        )
    }

    @Test
    fun `extractLiteRtToolCallPayloads accepts call tag variants`() {
        val extracted = extractLiteRtToolCallPayloads(
            """<call>{"name":"calculator","arguments":{"expression":"2+2"}}</call>Done"""
        )

        assertEquals("Done", extracted.visibleContent)
        assertEquals(
            """{"name":"calculator","arguments":{"expression":"2+2"}}""",
            extracted.payloads.single()
        )
    }

    @Test
    fun `sanitizeLiteRtRenderedText removes compact and split turn token spill`() {
        val raw = """
            <|start_of_turn|>model
            Hello<turn>
            < | turn>model
            !<|end_of_turn|>
        """.trimIndent()

        val cleaned = sanitizeLiteRtRenderedText(raw)

        assertEquals("Hello\n!", cleaned.trim())
        assertFalse(cleaned.contains("turn>model"))
        assertFalse(cleaned.contains("|"))
    }

    @Test
    fun `sanitizeLiteRtRenderedText removes leaked channel markers`() {
        val cleaned = sanitizeLiteRtRenderedText("<| channel>final\nHello there")

        assertEquals("Hello there", cleaned.trim())
        assertFalse(cleaned.contains("channel"))
    }

    @Test
    fun `sanitizeLiteRtRenderedTextForStreaming withholds dangling control token tails`() {
        assertEquals("", sanitizeLiteRtRenderedTextForStreaming("<"))
        assertEquals("Hello", sanitizeLiteRtRenderedTextForStreaming("Hello<|start_of"))
        assertEquals("2 < 3", sanitizeLiteRtRenderedTextForStreaming("2 < 3"))
    }

    @Test
    fun `sanitizeLiteRtRenderedTextForStreaming preserves leading space chunks`() {
        assertEquals(" tarta", sanitizeLiteRtRenderedTextForStreaming(" tarta"))
        assertEquals("tarta", sanitizeLiteRtRenderedText(" tarta"))
    }

    @Test
    fun `liteRtStreamingDelta emits only new suffix for sanitized cumulative snapshots`() {
        assertEquals(" world", liteRtStreamingDelta("Hello world", "Hello"))
        assertEquals("", liteRtStreamingDelta("Hello", "Hello world"))
        assertEquals("there", liteRtStreamingDelta("Hi there", "Hi <"))
    }

    @Test
    fun `liteRtStreamingDelta preserves explicit boundaries between streamed Spanish chunks`() {
        val rendered = StringBuilder()

        val first = liteRtStreamingDelta("Aquí", "")
        rendered.append(first)
        val second = liteRtStreamingDelta(" tienes", "Aquí")
        rendered.append(second)
        val third = liteRtStreamingDelta(" la", " tienes")
        rendered.append(third)
        val fourth = liteRtStreamingDelta(" receta", " la")
        rendered.append(fourth)

        assertEquals("Aquí tienes la receta", rendered.toString())
    }

    @Test
    fun `liteRtStreamingDelta does not invent spaces inside split word chunks`() {
        val rendered = StringBuilder("t")

        val delta = liteRtStreamingDelta(
            currentSnapshot = "arta",
            lastSnapshot = "t"
        )
        rendered.append(delta)

        assertEquals("tarta", rendered.toString())
    }

    @Test
    fun `liteRtStreamingDelta keeps punctuation tight`() {
        assertEquals(".", liteRtStreamingDelta(".", "receta"))
    }

    @Test
    fun `LiteRtStreamTextAssembler preserves explicit whitespace chunks`() {
        val assembler = LiteRtStreamTextAssembler()
        val rendered = StringBuilder()

        listOf("Aquí", " tienes", " la", " receta", " de", " t", "arta", ".").forEach { snapshot ->
            rendered.append(assembler.appendSnapshot(snapshot))
        }
        rendered.append(assembler.finish())

        assertEquals("Aquí tienes la receta de tarta.", rendered.toString())
    }

    @Test
    fun `LiteRtStreamTextAssembler handles cumulative snapshots without duplicating text`() {
        val assembler = LiteRtStreamTextAssembler()
        val rendered = StringBuilder()

        listOf(
            "Hello",
            "Hello! ",
            "Hello! How ",
            "Hello! How can ",
            "Hello! How can I help you today?"
        ).forEach { snapshot ->
            rendered.append(assembler.appendSnapshot(snapshot))
        }
        rendered.append(assembler.finish())

        assertEquals("Hello! How can I help you today?", rendered.toString())
    }

    @Test
    fun `LiteRtLeakedThinkingStreamFilter buffers token chunk thinking until final answer`() {
        val filter = LiteRtLeakedThinkingStreamFilter()
        val visible = StringBuilder()
        val thought = StringBuilder()

        listOf(
            "T",
            "hinking",
            " Process:",
            "1.**Analyze",
            " the Request:** The user wants a story.",
            "2.**Determine Key Elements:** A magical fisherman.",
            "7. **Final Story Generation.** Silas cast a moonlit net."
        ).forEach { chunk ->
            val message = FakeLiteRtMessage(
                contents = FakeLiteRtContents(listOf(FakeLiteRtText(chunk)))
            )
            val filtered = filter.filter(liteRtMessageSnapshot(message, thinkingEnabled = true))

            visible.append(filtered.text)
            thought.append(filtered.thought)
        }
        filter.finish()?.let { pending ->
            visible.append(pending.text)
            thought.append(pending.thought)
        }

        assertEquals("Silas cast a moonlit net.", visible.toString())
        assertTrue(thought.toString().contains("Thinking Process"))
        assertFalse(visible.toString().contains("Process"))
        assertFalse(visible.toString().contains("Final Story Generation"))
    }

    @Test
    fun `LiteRtLeakedThinkingStreamFilter releases ordinary token chunks after prefix diverges`() {
        val filter = LiteRtLeakedThinkingStreamFilter()
        val visible = StringBuilder()

        listOf("T", "he sea was calm.").forEach { chunk ->
            val message = FakeLiteRtMessage(
                contents = FakeLiteRtContents(listOf(FakeLiteRtText(chunk)))
            )
            val filtered = filter.filter(liteRtMessageSnapshot(message, thinkingEnabled = true))

            visible.append(filtered.text)
        }

        assertEquals("The sea was calm.", visible.toString())
    }

    @Test
    fun `liteRtMessageSnapshot extracts text content instead of object string`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("Hello! How can I help you today?"))
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Hello! How can I help you today?", snapshot.text)
        assertEquals("", snapshot.thought)
    }

    @Test
    fun `liteRtMessageSnapshot prefers Gallery message string when reflected content is compact`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("Hello!HowcanIhelpyoutoday?"))
            ),
            renderedString = "Hello! How can I help you today?"
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Hello! How can I help you today?", snapshot.text)
    }

    @Test
    fun `liteRtMessageSnapshot keeps official rendered string when reflected content loses spaces`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("Silas stood at the edge of the harbor and lowered hisand towardhe sea."))
            ),
            renderedString = "Silas stood at the edge of the harbor and lowered his hand toward the sea."
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals(
            "Silas stood at the edge of the harbor and lowered his hand toward the sea.",
            snapshot.text
        )
    }

    @Test
    fun `liteRtMessageSnapshot joins reflected text parts with word boundaries`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText("Hello"),
                    FakeLiteRtText("!"),
                    FakeLiteRtText("How"),
                    FakeLiteRtText("can"),
                    FakeLiteRtText("I"),
                    FakeLiteRtText("help"),
                    FakeLiteRtText("you"),
                    FakeLiteRtText("today"),
                    FakeLiteRtText("?")
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Hello! How can I help you today?", snapshot.text)
    }

    @Test
    fun `liteRtMessageSnapshot joins reflected Spanish text parts without dictionary gating`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText("Eso"),
                    FakeLiteRtText("suena"),
                    FakeLiteRtText("muy"),
                    FakeLiteRtText("bien"),
                    FakeLiteRtText(".")
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Eso suena muy bien.", snapshot.text)
    }

    @Test
    fun `liteRtMessageSnapshot prefers spaced reflected content over compact rendered string`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText("That"),
                    FakeLiteRtText("sounds"),
                    FakeLiteRtText("like"),
                    FakeLiteRtText("a"),
                    FakeLiteRtText("wonderful"),
                    FakeLiteRtText("project"),
                    FakeLiteRtText("!")
                )
            ),
            renderedString = "Thatsoundslikeawonderfulproject!"
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("That sounds like a wonderful project!", snapshot.text)
    }

    @Test
    fun `repairLiteRtCompactTextForDisplay does not invent English word boundaries`() {
        val repaired = repairLiteRtCompactTextForDisplay("Hello!HowcanIhelpyoutoday?")

        assertEquals("Hello! Howcan Ihelpyoutoday?", repaired)
    }

    @Test
    fun `repairLiteRtCompactTextForDisplay does not invent Spanish word boundaries`() {
        val repaired = repairLiteRtCompactTextForDisplay("Hola!Comopuedoayudartehoy?")

        assertEquals("Hola! Comopuedoayudartehoy?", repaired)
    }

    @Test
    fun `repairLiteRtCompactTextForDisplay only formats structural compact thinking labels`() {
        val repaired = repairLiteRtCompactTextForDisplay(
            "ThinkingProcess:1.Analyzetheinput:Theinputs\"Hello\".2.Determinetheintent:Thisisasimplegreeting."
        )

        assertTrue(repaired.contains("Thinking Process"))
        assertTrue(repaired.contains("\n1. "))
        assertTrue(repaired.contains("\n2. "))
        assertFalse(repaired.contains("This is a simple greeting"))
    }

    @Test
    fun `repairLiteRtCompactTextForDisplay preserves tool calls`() {
        val raw = """<tool_call>{"name":"web_search","arguments":{"query":"HowcanIhelp"}}</tool_call>"""

        assertEquals(raw, repairLiteRtCompactTextForDisplay(raw))
    }

    @Test
    fun `liteRtMessageSnapshot withholds provisional channel thinking before final marker`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("<| channel>ThinkingProcess:1.AnalyzeTheRequest: hidden"))
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("", snapshot.text)
        assertEquals("", snapshot.thought)
    }

    @Test
    fun `liteRtMessageSnapshot withholds provisional bare compact thinking before final marker`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "Thinking Process:1.Analyzetheinput:Theinputs\"Hello\".2.Determinetheintent:Thisisasimplegreeting."
                    )
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("", snapshot.text)
        assertEquals("", snapshot.thought)
    }

    @Test
    fun `liteRtMessageSnapshot withholds partial leaked thinking prefix fragments`() {
        listOf(
            "T",
            "Thinking",
            "Thinking Process",
            "1. **Analyze the Re",
            "Analyze the Request"
        ).forEach { fragment ->
            val message = FakeLiteRtMessage(
                contents = FakeLiteRtContents(listOf(FakeLiteRtText(fragment)))
            )

            val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

            assertEquals("Fragment should be buffered: $fragment", "", snapshot.text)
            assertEquals("Fragment should not be shown as thought yet: $fragment", "", snapshot.thought)
        }
    }

    @Test
    fun `liteRtMessageSnapshot releases normal text after partial thinking prefix diverges`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("The sea was calm and bright."))
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("The sea was calm and bright.", snapshot.text)
        assertEquals("", snapshot.thought)
    }

    @Test
    fun `liteRtMessageSnapshot splits final output generation marker from GPU thinking leak`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "Thinking Process:1.**Analyze the input:**The inputs\"Hello\"." +
                            "2.**Determine the intent:**This is a simple greeting." +
                            "3.**Determine the appropriate response:**A greeting should be reciprocated in a friendly and polite manner." +
                            "4.**Formulate the response:**A standard,warm greeting is appropriate." +
                            "5.**Review constraints/style:**The request doesn'tspecifyaformat,butthepreviousinstructionsmplyahelpful,readable tone." +
                            "*Self-Correction/Refinement:*Since the inputs very brief,theresponseshouldbeequallyfriendlyandopen-ended,invitingthetostatetheiractualneed." +
                            "6.**FinalOutputGeneration.**Hello! How can I help you today?"
                    )
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Hello! How can I help you today?", snapshot.text)
        assertTrue(snapshot.thought.contains("Thinking Process"))
        assertFalse(snapshot.thought.contains("FinalOutputGeneration"))
    }

    @Test
    fun `liteRtMessageSnapshot splits final story generation marker from Gemma 4 thinking leak`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "Thinking Process:1.**Analyze the Request:** The user wants a very short story." +
                            "2.**Determine Key Elements:** A magical fisherman and the sea." +
                            "7. **Final Story Generation.** Silas cast a net of moonlight and calmed the storm."
                    )
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Silas cast a net of moonlight and calmed the storm.", snapshot.text)
        assertTrue(snapshot.thought.contains("Thinking Process"))
        assertFalse(snapshot.thought.contains("Final Story Generation"))
    }

    @Test
    fun `liteRtMessageSnapshot splits generic final generation marker after thinking preamble`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "Thinking Process:1.**Analyze the Request:** The user wants a concise summary." +
                            "2.**Choose the format:** Return one sentence." +
                            "4. **Final Summary Generation:** The lake glowed at dawn, and the village woke safely."
                    )
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("The lake glowed at dawn, and the village woke safely.", snapshot.text)
        assertTrue(snapshot.thought.contains("Analyze the Request"))
        assertFalse(snapshot.thought.contains("Final Summary Generation"))
    }

    @Test
    fun `liteRtMessageSnapshot splits compact generic final generation marker after thinking preamble`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "Analyze the input:The user asked for Spanish translation." +
                            "Determine the appropriate response:Translate directly." +
                            "3.**FinalTranslationGeneration.**Hola, mar brillante."
                    )
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Hola, mar brillante.", snapshot.text)
        assertTrue(snapshot.thought.contains("Analyze the input"))
        assertFalse(snapshot.thought.contains("FinalTranslationGeneration"))
    }

    @Test
    fun `liteRtMessageSnapshot does not split arbitrary final generation phrase without thinking preamble`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText("The final generation of lanterns floated over the harbor.")
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("The final generation of lanterns floated over the harbor.", snapshot.text)
        assertEquals("", snapshot.thought)
    }

    @Test
    fun `liteRtMessageSnapshot emits leaked thinking only once final marker appears`() {
        val provisional = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "Thinking Process:" +
                            "1. **Analyze the input:** The inputs\"Hello\"." +
                            "2. **Determine the intent:** This is a simple greeting."
                    )
                )
            )
        )
        val finalized = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "Thinking Process:" +
                            "1. **Analyze the input:** The inputs\"Hello\"." +
                            "2. **Determine the intent:** This is a simple greeting." +
                            "3. **Final Output Generation:** Hello! How can I help you today?"
                    )
                )
            )
        )

        val provisionalSnapshot = liteRtMessageSnapshot(provisional, thinkingEnabled = true)
        val finalizedSnapshot = liteRtMessageSnapshot(finalized, thinkingEnabled = true)

        assertEquals("", provisionalSnapshot.text)
        assertEquals("", provisionalSnapshot.thought)
        assertEquals("Hello! How can I help you today?", finalizedSnapshot.text)
        assertTrue(finalizedSnapshot.thought.contains("Analyze the input"))
        assertFalse(finalizedSnapshot.thought.contains("Final Output Generation"))
    }

    @Test
    fun `liteRtMessageSnapshot drops final output generation instruction before visible answer`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "Thinking Process:" +
                            "1. **Analyze the input:** The inputs\"Hello\"." +
                            "2. **Determine the intent:** This is a simple greeting." +
                            "3. **Determine the appropriate response style:** Sincetheinputsacasualgreeting,the response should be friendly, polite, andreciprocal." +
                            "4. **Formulatepotentialresponses:** \"Hello! How can I help you today?\"(Standard, helpful)\"Hithere!\"(Casual)\"Hello! What'sonyourmind?\"(Engaging)" +
                            "5. **Selectthebestresponse:** A standard, welcoming, andopen-endedresponseisusuallythesafestandmosteffectivestartingpointforanAI." +
                            "6. **Final Output Generation:** Generateafriendlyacknowledgmentndinvitationtostatethe'sneed. Hello! How can I help you today?"
                    )
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Hello! How can I help you today?", snapshot.text)
        assertTrue(snapshot.thought.contains("Thinking Process"))
        assertFalse(snapshot.thought.contains("Final Output Generation"))
    }

    @Test
    fun `liteRtMessageSnapshot treats final output generation as boundary even without perfect preamble`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "1. **Analyze the input:** The inputs\"Hello\"." +
                            "2. **Determine the intent:** This is a simple greeting." +
                            "3. **Determine the appropriate response style:** Since the inputs a casual greeting, the response should be friendly, polite, and reciprocal." +
                            "4. **Formulate potential responses:**\"Hello! How can I help you today?\"(Standard, helpful)\"Hithere!\"(Casual)\"Hello! What'sonyourmind?\"(Engaging)" +
                            "5.** Select the best response:** A standard, welcoming, andopen-ended response is usually the safest and most effective starting point for an A I." +
                            "6. **Final Output Generation:** Generateafriendlyacknowledgmentndinvitationtostatethe'sneed. Hello! How can I help you today?"
                    )
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Hello! How can I help you today?", snapshot.text)
        assertTrue(snapshot.thought.contains("Analyze the input"))
        assertFalse(snapshot.text.contains("Thinking Process"))
        assertFalse(snapshot.text.contains("Final Output Generation"))
    }

    @Test
    fun `liteRtMessageSnapshot keeps Gallery thought channel separate from visible text`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("Hello! How can I help you today?"))
            ),
            channels = mapOf("thought" to "Thinking Process:\n1. Analyze the input."),
            renderedString = "Hello! How can I help you today?"
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Hello! How can I help you today?", snapshot.text)
        assertEquals("Thinking Process:\n1. Analyze the input.", snapshot.thought)
    }

    @Test
    fun `liteRtMessageSnapshot splits documented Gemma channel tokens`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "<|channel|>thought\nCheck the request privately." +
                            "<|channel|>final\nHere is the answer."
                    )
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Here is the answer.", snapshot.text)
        assertEquals("Check the request privately.", snapshot.thought)
    }

    @Test
    fun `liteRtMessageSnapshot treats closing thought channel marker as final boundary`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "<|channel>thought\nInternal reasoning only.<channel|>\nVisible response."
                    )
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Visible response.", snapshot.text)
        assertEquals("Internal reasoning only.", snapshot.thought)
    }

    @Test
    fun `liteRtMessageSnapshot streams documented thought channel before final text`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("<|channel|>thought\nChecking constraints."))
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("", snapshot.text)
        assertEquals("Checking constraints.", snapshot.thought)
    }

    @Test
    fun `liteRtMessageSnapshot keeps documented channel tokens visible when thinking disabled`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "<|channel|>thought\nCheck the request privately." +
                            "<|channel|>final\nHere is the answer."
                    )
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = false)

        assertEquals("Check the request privately.\nHere is the answer.", snapshot.text.trim())
        assertEquals("", snapshot.thought)
    }

    @Test
    fun `liteRtMessageSnapshot extracts thought channel from Contents value`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("Final answer."))
            ),
            channels = mapOf(
                "thought" to FakeLiteRtContents(
                    listOf(
                        FakeLiteRtText("Thinking Process:"),
                        FakeLiteRtText("\n1. Analyze the input.")
                    )
                )
            ),
            renderedString = "Final answer."
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Final answer.", snapshot.text)
        assertEquals("Thinking Process:\n1. Analyze the input.", snapshot.thought)
    }

    @Test
    fun `liteRtMessageSnapshot ignores thinking channel when thinking disabled`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("Hello! How can I help you today?"))
            ),
            channels = mapOf("thought" to "Thinking Process:\n1. Analyze the input."),
            renderedString = "Hello! How can I help you today?"
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = false)

        assertEquals("Hello! How can I help you today?", snapshot.text)
        assertEquals("", snapshot.thought)
    }

    @Test
    fun `repairLiteRtCompactTextForDisplay formats GPU thinking steps without hiding final answer`() {
        val repaired = repairLiteRtCompactTextForDisplay(
            "Thinking Process:1.**Analyze the input:**The inputs\"Hello\"." +
                "2.**Determine the intent:**This is a simple greeting." +
                "5.**Review constraints/style:**The request doesn'tspecifyaformat,butthepreviousinstructionsmplyahelpful,readable tone." +
                "*Self-Correction/Refinement:*Since the inputs very brief,theresponseshouldbeequallyfriendlyandopen-ended,invitingthetostatetheiractualneed."
        )

        assertTrue(repaired.contains("\n1. **Analyze the input:** The inputs"))
        assertTrue(repaired.contains("\n2. **Determine the intent:** This is a simple greeting"))
        assertTrue(repaired.contains("doesn't"))
        assertTrue(repaired.contains("specifyaformat"))
        assertTrue(repaired.contains("theresponseshouldbeequallyfriendlyandopen-ended"))
    }

    @Test
    fun `repairLiteRtCompactTextForDisplay preserves compact prose runs instead of rewriting words`() {
        val repaired = repairLiteRtCompactTextForDisplay(
            "Sincetheinputsacasualgreeting,the response should be friendly, polite, andreciprocal." +
                "4.**Formulatepotentialresponses:**\"Hello! How can I help you today?\"" +
                "5.**Selectthebestresponse:**A standard, welcoming, andopen-endedresponseisusuallythesafestandmosteffectivestartingpointforanAI."
        )

        assertTrue(repaired.contains("Sincetheinputsacasualgreeting, the response"))
        assertTrue(repaired.contains("andreciprocal"))
        assertTrue(repaired.contains("Formulatepotentialresponses"))
        assertTrue(repaired.contains("Selectthebestresponse"))
        assertTrue(repaired.contains("open-ended"))
        assertTrue(repaired.contains("responseisusuallythesafestandmosteffectivestartingpointforan AI"))
    }

    @Test
    fun `repairLiteRtCompactTextForDisplay does not create mixed-word corruption`() {
        val raw = "The wants a very shorttory about magical fisherman. Talk tohe sea. He canommand theides."

        val repaired = repairLiteRtCompactTextForDisplay(raw)

        assertEquals(raw, repaired)
        assertFalse(repaired.contains("short tory"))
        assertFalse(repaired.contains("to he"))
    }

    @Test
    fun `LiteRtToolDefinition renders OpenAPI tool json`() {
        val tool = LiteRtToolDefinition(
            name = "web_search",
            description = "Search the web.",
            parameters = mapOf("query" to "Search query."),
            requiredParams = listOf("query")
        )

        val json = org.json.JSONObject(tool.toLiteRtOpenApiToolJson())

        assertEquals("web_search", json.getString("name"))
        assertEquals("Search the web.", json.getString("description"))
        assertEquals("object", json.getJSONObject("parameters").getString("type"))
        assertTrue(json.getJSONObject("parameters").getJSONArray("required").toString().contains("query"))
    }

    @Test
    fun `liteRtMessageSnapshot extracts structured tool calls`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(emptyList()),
            toolCalls = listOf(FakeLiteRtToolCall("web_search", mapOf("query" to "LiteRT tools")))
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("", snapshot.text)
        assertEquals(1, snapshot.toolCalls.size)
        assertEquals("web_search", snapshot.toolCalls.single().name)
        assertEquals("LiteRT tools", snapshot.toolCalls.single().arguments["query"])
    }

    @Test
    fun `hasLiteRtCorruptOutputSignature detects invalid GPU text`() {
        val corrupt = "answer \uFFFD\uFFFD\uFFFD\u0001\u0002"

        assertTrue(corrupt.hasLiteRtCorruptOutputSignature())
        assertFalse("Silas listened to the sea.".hasLiteRtCorruptOutputSignature())
    }

    @Test
    fun `liteRtMessageSnapshot prefers Gallery string for long visible prose`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "Thatsoundslikeawonderfulproject! Singingisarichandexpressivesubject. " +
                            "Tohelpyougetstarted, wecanbreakdowntheprocess."
                    )
                )
            ),
            renderedString = "That sounds like a wonderful project! " +
                "Singing is a rich and expressive subject.\n\n" +
                "To help you get started, we can break down the process."
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals(
            "That sounds like a wonderful project! " +
                "Singing is a rich and expressive subject.\n\n" +
                "To help you get started, we can break down the process.",
            snapshot.text
        )
    }


    @Test
    fun `liteRtMessageSnapshot strips duplicated rendered thinking even when channel exists`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("Thinking Process:1.Analyze the input: hidden"))
            ),
            channels = mapOf("thought" to "actual thought channel")
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("", snapshot.text)
        assertEquals("actual thought channel", snapshot.thought)
    }

    @Test
    fun `estimateLiteRtCompletionTokens counts compact output beyond one token`() {
        val tokens = estimateLiteRtCompletionTokens("Hello!HowcanIhelpyoutoday?")

        assertTrue(tokens > 1)
    }

    @Test
    fun `effectiveLiteRtEngineMaxTokens clamps user setting to package limit`() {
        val model = LiteRtModelEntity(
            displayName = "Gemma 4 E2B IT LiteRT-LM",
            path = "/tmp/gemma-4-E2B-it.litertlm",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            filename = "gemma-4-E2B-it.litertlm"
        )

        assertEquals(8000, effectiveLiteRtEngineMaxTokens(model, requestedMaxTokens = 8000))
        assertEquals(16384, effectiveLiteRtEngineMaxTokens(model, requestedMaxTokens = 16384))
        assertEquals(32768, effectiveLiteRtEngineMaxTokens(model, requestedMaxTokens = 32768))
        assertEquals(2000, effectiveLiteRtEngineMaxTokens(model, requestedMaxTokens = 2000))
        assertEquals(32768, effectiveLiteRtEngineMaxTokens(model, requestedMaxTokens = null))
    }

    @Test
    fun `effectiveLiteRtEngineMaxTokensForBackend caps GPU context to stable size`() {
        val model = LiteRtModelEntity(
            displayName = "Gemma 4 E2B IT LiteRT-LM",
            path = "/tmp/gemma-4-E2B-it.litertlm",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            filename = "gemma-4-E2B-it.litertlm"
        )

        assertEquals(4096, effectiveLiteRtEngineMaxTokensForBackend(model, 10000, "GPU"))
        assertEquals(10000, effectiveLiteRtEngineMaxTokensForBackend(model, 10000, "CPU"))
    }

    @Test
    fun `LiteRtLeakedThinkingStreamFilter routes tool planning prose to thinking`() {
        val filter = LiteRtLeakedThinkingStreamFilter()

        val filtered = filter.filter(
            LiteRtMessageSnapshot(
                text = "The wants a deep research on water purifying. I should formulate a query.",
                thought = "",
                rawText = "The wants a deep research on water purifying. I should formulate a query."
            )
        )
        val final = filter.finish()

        assertEquals("", filtered.text)
        assertTrue(final?.thought.orEmpty().contains("deep research"))
        assertEquals("", final?.text.orEmpty())
    }

    @Test
    fun `fitLiteRtConversationOverrideForContext keeps latest user message within model context`() {
        val model = LiteRtModelEntity(
            displayName = "Gemma 4 E2B IT LiteRT-LM",
            path = "/tmp/gemma-4-E2B-it.litertlm",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            filename = "gemma-4-E2B-it.litertlm"
        )
        val longText = List(900) { index -> "section$index voice breath posture resonance" }.joinToString(" ")
        val conversation = LiteRtConversationOverride(
            systemInstruction = longText,
            initialMessages = listOf(
                LiteRtConversationMessage("user", longText),
                LiteRtConversationMessage("assistant", longText),
                LiteRtConversationMessage("user", longText)
            ),
            userMessage = "Please continue with the first practical exercise."
        )

        val fitted = fitLiteRtConversationOverrideForContext(
            conversation = conversation,
            model = model,
            contextSize = 512
        )
        val rendered = buildString {
            append(fitted.systemInstruction)
            fitted.initialMessages.forEach { message ->
                append("\n\n")
                append(message.role)
                append(":\n")
                append(message.content)
            }
            append("\n\nUser:\n")
            append(fitted.userMessage)
        }

        assertTrue(estimateNativeChatTextTokens(rendered) <= 512)
        assertEquals("Please continue with the first practical exercise.", fitted.userMessage)
        assertTrue(fitted.initialMessages.size < conversation.initialMessages.size)
    }

    @Test
    fun `fitLiteRtConversationOverrideForContext preserves image attachment metadata`() {
        val model = LiteRtModelEntity(
            displayName = "Gemma 4 E2B IT LiteRT-LM",
            path = "/tmp/gemma-4-E2B-it.litertlm",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            filename = "gemma-4-E2B-it.litertlm"
        )
        val conversation = LiteRtConversationOverride(
            systemInstruction = "You can inspect attached images.",
            initialMessages = listOf(
                LiteRtConversationMessage(
                    role = "user",
                    content = "Earlier image",
                    imagePath = "/tmp/earlier.png"
                )
            ),
            userMessage = "What is in this image?",
            userImagePath = "/tmp/current.jpg"
        )

        val fitted = fitLiteRtConversationOverrideForContext(
            conversation = conversation,
            model = model,
            contextSize = 512
        )

        assertEquals("/tmp/current.jpg", fitted.userImagePath)
        assertEquals("/tmp/earlier.png", fitted.initialMessages.firstOrNull()?.imagePath)
    }

    @Test
    fun `fitLiteRtConversationOverrideForContext preserves audio attachment metadata`() {
        val model = LiteRtModelEntity(
            displayName = "Gemma 4 E2B IT LiteRT-LM",
            path = "/tmp/gemma-4-E2B-it.litertlm",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            filename = "gemma-4-E2B-it.litertlm"
        )
        val conversation = LiteRtConversationOverride(
            systemInstruction = "You can inspect attached audio.",
            initialMessages = listOf(
                LiteRtConversationMessage(
                    role = "user",
                    content = "Earlier audio",
                    audioPath = "/tmp/earlier.wav"
                )
            ),
            userMessage = "What is in this audio?",
            userAudioPath = "/tmp/current.wav"
        )

        val fitted = fitLiteRtConversationOverrideForContext(
            conversation = conversation,
            model = model,
            contextSize = 512
        )

        assertEquals("/tmp/current.wav", fitted.userAudioPath)
        assertEquals("/tmp/earlier.wav", fitted.initialMessages.firstOrNull()?.audioPath)
    }
}

private class FakeLiteRtMessage(
    private val contents: FakeLiteRtContents,
    private val channels: Map<String, Any> = emptyMap(),
    private val toolCalls: List<FakeLiteRtToolCall> = emptyList(),
    private val renderedString: String = "FakeLiteRtMessage(contents=$contents)"
) {
    fun getContents(): FakeLiteRtContents = contents
    fun getChannels(): Map<String, Any> = channels
    fun getToolCalls(): List<FakeLiteRtToolCall> = toolCalls
    override fun toString(): String = renderedString
}

private class FakeLiteRtContents(private val contents: List<Any>) {
    fun getContents(): List<Any> = contents
    override fun toString(): String = "FakeLiteRtContents(contents=$contents)"
}

private class FakeLiteRtText(private val text: String) {
    fun getText(): String = text
    override fun toString(): String = "FakeLiteRtText(text=$text)"
}

private class FakeLiteRtToolCall(
    private val name: String,
    private val arguments: Map<String, Any?>
) {
    fun getName(): String = name
    fun getArguments(): Map<String, Any?> = arguments
}
