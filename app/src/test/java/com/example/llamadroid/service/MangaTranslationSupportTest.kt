package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaTranslationSupportTest {
    @Test
    fun `source classification accepts cbz zip and pdf sources`() {
        assertEquals(
            MangaTranslationSourceKind.CBZ,
            MangaTranslationSupport.sourceKindFor("chapter.cbz", null)
        )
        assertEquals(
            MangaTranslationSourceKind.CBZ,
            MangaTranslationSupport.sourceKindFor("chapter.zip", "application/octet-stream")
        )
        assertEquals(
            MangaTranslationSourceKind.PDF,
            MangaTranslationSupport.sourceKindFor("chapter.pdf", "application/pdf")
        )
        assertTrue(MangaTranslationSupport.isSupportedSource("chapter.CBZ", null))
        assertFalse(MangaTranslationSupport.isSupportedSource("chapter.txt", "text/plain"))
    }

    @Test
    fun `picker accepts include comic archives and pdfs`() {
        val accepted = MangaTranslationSupport.primaryPickerMimeTypes.toSet()

        assertTrue("application/vnd.comicbook+zip" in accepted)
        assertTrue("application/zip" in accepted)
        assertTrue("application/pdf" in accepted)
        assertEquals(listOf("*/*"), MangaTranslationSupport.fallbackPickerMimeTypes.toList())
    }

    @Test
    fun `zip entry validation rejects traversal and unsafe paths`() {
        assertTrue(MangaTranslationSupport.isSafeComicZipEntryName("chapter/001.png"))
        assertFalse(MangaTranslationSupport.isSafeComicZipEntryName("../001.png"))
        assertFalse(MangaTranslationSupport.isSafeComicZipEntryName("chapter/../001.png"))
        assertFalse(MangaTranslationSupport.isSafeComicZipEntryName("/chapter/001.png"))
        assertFalse(MangaTranslationSupport.isSafeComicZipEntryName("chapter\\001.png"))
        assertFalse(MangaTranslationSupport.isSafeComicZipEntryName("chapter//001.png"))
    }

    @Test
    fun `checkpoint json round trips completed pages and translations`() {
        val checkpoint = MangaTranslationCheckpoint(
            jobId = "job-1",
            sourceName = "chapter.cbz",
            sourceKind = MangaTranslationSourceKind.CBZ,
            exportPdf = true,
            exportCbz = true,
            totalPages = 4,
            completedPageIndexes = setOf(0, 2),
            translations = mapOf("p1_u1" to "Hola", "p3_u2" to "Adios")
        )

        val restored = MangaTranslationSupport.checkpointFromJson(
            MangaTranslationSupport.checkpointToJson(checkpoint)
        )

        assertEquals(checkpoint.jobId, restored.jobId)
        assertEquals(checkpoint.sourceKind, restored.sourceKind)
        assertEquals(checkpoint.completedPageIndexes, restored.completedPageIndexes)
        assertEquals(checkpoint.translations, restored.translations)
    }

    @Test
    fun `remaining ids skips already translated checkpoint entries`() {
        val checkpoint = MangaTranslationCheckpoint(
            jobId = "job-1",
            sourceName = "chapter.pdf",
            sourceKind = MangaTranslationSourceKind.PDF,
            exportPdf = true,
            exportCbz = false,
            translations = mapOf("p1_u1" to "Done")
        )

        assertEquals(
            listOf("p1_u2", "p2_u1"),
            MangaTranslationSupport.remainingTranslationIds(listOf("p1_u1", "p1_u2", "p2_u1"), checkpoint)
        )
    }

    @Test
    fun `layout helper expands skinny manga OCR regions`() {
        val original = PdfMappedRect(x = 100f, y = 200f, width = 20f, height = 140f)
        val expanded = MangaTranslationSupport.expandedBubbleRect(original, pageWidth = 1000f, pageHeight = 1600f)

        assertTrue(expanded.width > original.width * 2f)
        assertTrue(expanded.height >= original.height)
        assertTrue(expanded.x >= 0f)
    }

    @Test
    fun `layout helper rejects oversized merged regions`() {
        assertTrue(
            MangaTranslationSupport.mergedRegionIsTooLarge(
                PdfMappedRect(x = 0f, y = 0f, width = 800f, height = 500f),
                pageWidth = 1000f,
                pageHeight = 1600f
            )
        )
        assertFalse(
            MangaTranslationSupport.mergedRegionIsTooLarge(
                PdfMappedRect(x = 120f, y = 200f, width = 180f, height = 90f),
                pageWidth = 1000f,
                pageHeight = 1600f
            )
        )
    }

    @Test
    fun `text fit helper reduces size for narrow regions`() {
        val wide = MangaTranslationSupport.fittedTextSize(lineCount = 2, maxWidth = 300f, maxHeight = 120f)
        val narrow = MangaTranslationSupport.fittedTextSize(lineCount = 8, maxWidth = 45f, maxHeight = 90f)

        assertTrue(wide > narrow)
        assertTrue(narrow >= 5f)
    }
}
