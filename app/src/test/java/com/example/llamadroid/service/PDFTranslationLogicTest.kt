package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PDFTranslationLogicTest {
    @Test
    fun `partial parser recovers nested arrays and single plain values`() {
        val nested = PDFTranslationLogic.parsePartialPageTranslationJson(
            """{"translations":[{"id":"p1_b1","translation":"Hola"},{"id":"p1_b2","text":"Adiós"}]}""",
            setOf("p1_b1", "p1_b2")
        )
        val single = PDFTranslationLogic.parsePartialPageTranslationJson(
            "```json\n{\"translation\":\"Buenos días\"}\n```",
            setOf("p1_b3")
        )

        assertEquals("Hola", nested.translations["p1_b1"])
        assertEquals("Adiós", nested.translations["p1_b2"])
        assertEquals("Buenos días", single.translations["p1_b3"])
        assertTrue(nested.missingIds.isEmpty())
        assertTrue(single.missingIds.isEmpty())
    }

    @Test
    fun `single unit fallback prompt does not request json`() {
        val prompt = PDFTranslationLogic.buildSingleUnitPlainTextPrompt(
            "Spanish",
            PDFTranslationLogic.PageTranslationBlock(
                id = "p1_b1",
                text = "Hello",
                x = 0f,
                y = 0f,
                width = 10f,
                height = 10f,
                readingOrder = 1,
                sourceBlockCount = 1,
                sourceLineCount = 1
            )
        )

        assertTrue(prompt.contains("Return only the translated text"))
        assertTrue(!prompt.contains("Return JSON"))
    }

    @Test
    fun `maps OCR bitmap boxes to PDF coordinates with y axis inversion`() {
        val rect = PDFTranslationLogic.mapBitmapBoxToPdfRect(
            box = PdfOcrBox(left = 100, top = 200, right = 300, bottom = 500),
            bitmapWidth = 1000,
            bitmapHeight = 2000,
            pdfWidth = 500f,
            pdfHeight = 1000f
        )

        assertEquals(50f, rect.x, 0.001f)
        assertEquals(750f, rect.y, 0.001f)
        assertEquals(100f, rect.width, 0.001f)
        assertEquals(150f, rect.height, 0.001f)
    }

    @Test
    fun `maps text layer boxes from top origin to PDF bottom origin`() {
        val rect = PDFTranslationLogic.mapTextLayerBoxToPdfRect(
            x = 50f,
            yFromTop = 120f,
            width = 200f,
            height = 20f,
            pageHeight = 800f
        )

        assertEquals(50f, rect.x, 0.001f)
        assertEquals(660f, rect.y, 0.001f)
        assertEquals(200f, rect.width, 0.001f)
        assertEquals(20f, rect.height, 0.001f)
    }

    @Test
    fun `translation prompt asks for target language and output only translation`() {
        val prompt = PDFTranslationLogic.buildTranslationUserPrompt(
            text = "Were you maybe about to confess your love to me?",
            targetLanguage = "Spanish"
        )

        assertTrue(prompt.contains("Translate the following text to Spanish."))
        assertTrue(prompt.contains("Output only the translated text."))
        assertTrue(prompt.contains("Were you maybe about to confess your love to me?"))
    }

    @Test
    fun `page translation prompt includes ids geometry and strict json instructions`() {
        val prompt = PDFTranslationLogic.buildPageTranslationUserPrompt(
            targetLanguage = "Spanish",
            pageNumber = 2,
            totalPages = 4,
            hasImageContext = false,
            blocks = listOf(
                PDFTranslationLogic.PageTranslationBlock(
                    id = "p2_b1",
                    text = "Were you maybe...",
                    x = 10f,
                    y = 20f,
                    width = 100f,
                    height = 40f,
                    readingOrder = 1,
                    sourceBlockCount = 1,
                    sourceLineCount = 1
                )
            )
        )

        assertTrue(prompt.contains("Translate this PDF/comic page to Spanish."))
        assertTrue(prompt.contains("id=p2_b1"))
        assertTrue(prompt.contains("rect={x:10.00,y:20.00,w:100.00,h:40.00}"))
        assertTrue(prompt.contains("Return only a valid JSON object"))
        assertTrue(prompt.contains("Every listed id must appear exactly once"))
        assertTrue(prompt.contains("Its translation must cover every grouped source line"))
        assertTrue(prompt.contains("Never combine, split, rename, or reorder region IDs"))
        assertTrue(prompt.contains("Do not copy the source text"))
    }

    @Test
    fun `multi fragment completeness detector retries a partial bubble translation`() {
        val bubble = PDFTranslationLogic.PageTranslationBlock(
            id = "p1_u1",
            text = "Maybe I cannot see them anymore\nI have not seen anyone today",
            x = 10f,
            y = 20f,
            width = 180f,
            height = 120f,
            readingOrder = 1,
            sourceBlockCount = 2,
            sourceLineCount = 2,
            sourceLines = listOf(
                "Maybe I cannot see them anymore",
                "I have not seen anyone today"
            )
        )

        assertTrue(
            PDFTranslationLogic.isIncompleteMultiFragmentTranslation(
                bubble,
                translatedText = "Tal vez no pueda verlos.",
                targetLanguage = "Spanish"
            )
        )
        assertEquals(
            false,
            PDFTranslationLogic.isIncompleteMultiFragmentTranslation(
                bubble,
                translatedText = "Tal vez ya no pueda verlos; hoy no he visto a nadie.",
                targetLanguage = "Spanish"
            )
        )
    }

    @Test
    fun `single fragment is not rejected by multi fragment completeness heuristic`() {
        val caption = PDFTranslationLogic.PageTranslationBlock(
            id = "p1_u2",
            text = "Morning.",
            x = 10f,
            y = 20f,
            width = 80f,
            height = 40f,
            readingOrder = 2,
            sourceBlockCount = 1,
            sourceLineCount = 1,
            sourceLines = listOf("Morning.")
        )

        assertEquals(
            false,
            PDFTranslationLogic.isIncompleteMultiFragmentTranslation(
                caption,
                translatedText = "Mañana.",
                targetLanguage = "Spanish"
            )
        )
    }

    @Test
    fun `page translation json parser accepts expected map`() {
        val parsed = PDFTranslationLogic.parsePageTranslationJson(
            """{"p1_b1":"Hola","p1_b2":"Adiós"}""",
            setOf("p1_b1", "p1_b2")
        )

        assertEquals("Hola", parsed["p1_b1"])
        assertEquals("Adiós", parsed["p1_b2"])
    }

    @Test
    fun `correction prompt asks for fixes only`() {
        val prompt = PDFTranslationLogic.buildTranslationCorrectionPrompt(
            targetLanguage = "Spanish",
            entries = listOf(
                PDFTranslationLogic.TranslationCorrectionEntry(
                    id = "p1_b1",
                    sourceText = "Were you maybe...",
                    translatedText = "Debería tal vez...",
                    pageNumber = 1
                )
            )
        )

        assertTrue(prompt.contains("Return only a strict JSON object with fixes"))
        assertTrue(prompt.contains("If no fixes are needed, return exactly {}."))
        assertTrue(prompt.contains("untranslated"))
        assertTrue(prompt.contains("id=p1_b1"))
    }

    @Test
    fun `model name vision heuristic is conservative`() {
        assertTrue(PDFTranslationLogic.modelNameLikelySupportsVision("qwen2.5-vl:7b"))
        assertTrue(PDFTranslationLogic.modelNameLikelySupportsVision("llava-llama3"))
        assertEquals(false, PDFTranslationLogic.modelNameLikelySupportsVision("llama3.1:8b"))
    }

    @Test
    fun `weak translation detector rejects empty unchanged and untranslated cjk`() {
        assertTrue(PDFTranslationLogic.isWeakPageTranslation("好きだ", "好きだ"))
        assertTrue(PDFTranslationLogic.isWeakPageTranslation("Hello", ""))
        assertEquals(false, PDFTranslationLogic.isWeakPageTranslation("好きだ", "Te quiero"))
    }

    @Test
    fun `weak translation detector accepts valid cjk target languages`() {
        assertEquals(
            false,
            PDFTranslationLogic.isWeakPageTranslation(
                sourceText = "I love you",
                translatedText = "好きです",
                targetLanguage = "Japanese"
            )
        )
        assertEquals(
            false,
            PDFTranslationLogic.isWeakPageTranslation(
                sourceText = "Hello",
                translatedText = "你好",
                targetLanguage = "Chinese (Simplified)"
            )
        )
    }

    @Test
    fun `optional fixes parser accepts sparse map`() {
        val parsed = PDFTranslationLogic.parseOptionalTranslationFixesJson(
            """```json
            {"p1_b2":"¿Dónde puedo comprar un rosario?"}
            ```""".trimIndent()
        )

        assertEquals("¿Dónde puedo comprar un rosario?", parsed["p1_b2"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `page translation json parser rejects missing block ids`() {
        PDFTranslationLogic.parsePageTranslationJson(
            """{"p1_b1":"Hola"}""",
            setOf("p1_b1", "p1_b2")
        )
    }

    @Test
    fun `natural sort key orders comic page names`() {
        val pages = listOf("010.png", "1.png", "002.png").sortedBy {
            PDFTranslationLogic.naturalSortKey(it).joinToString("\u0000")
        }

        assertEquals(listOf("1.png", "002.png", "010.png"), pages)
    }

    @Test
    fun `translation cleanup strips llama noise and enclosing quotes`() {
        val cleaned = PDFTranslationLogic.cleanTranslationOutput(
            """
            [ Prompt: 20.0 t/s | Generation: 5.0 t/s ]
            "Tal vez ibas a confesarme tu amor."
            total time = 10.00 ms
            """.trimIndent()
        )

        assertEquals("Tal vez ibas a confesarme tu amor.", cleaned)
    }

    @Test
    fun `translation language defaults follow app language with Spanish fallback`() {
        assertEquals("English", PDFTranslationLogic.defaultTranslationLanguageForAppLanguage("en"))
        assertEquals("Spanish", PDFTranslationLogic.defaultTranslationLanguageForAppLanguage("es"))
        assertEquals("Spanish", PDFTranslationLogic.defaultTranslationLanguageForAppLanguage("system"))
        assertEquals("Spanish", PDFTranslationLogic.defaultTranslationLanguageForAppLanguage(null))
    }

    @Test
    fun `translation token budget stays inside configured max`() {
        val maxTokens = PDFTranslationLogic.estimateTranslationMaxTokens(
            sourceText = "short paragraph",
            configuredMaxTokens = 48
        )

        assertEquals(64, maxTokens)
    }
}
