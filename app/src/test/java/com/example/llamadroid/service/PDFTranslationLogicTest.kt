package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PDFTranslationLogicTest {
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
        assertTrue(prompt.contains("id=p1_b1"))
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
