package com.example.llamadroid.ui.ai.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownTextTest {
    @Test
    fun normalizeMarkdownUrl_acceptsKnowledgeChunkUris() {
        assertEquals("kb://chunk/123", normalizeMarkdownUrl("kb://chunk/123"))
        assertEquals("kb://chunk/123", normalizeMarkdownUrl("<kb://chunk/123>"))
        assertEquals("kb://chunk/123", normalizeMarkdownUrl("kb://chunk/123 \"Manual source\""))
        assertEquals("https://example.com", normalizeMarkdownUrl("https://example.com"))
        assertNull(normalizeMarkdownUrl("file:///tmp/nope"))
    }

    @Test
    fun rawKnowledgeChunkUris_areClickableFallbacks() {
        assertEquals("kb://chunk/123", knowledgeChunkUriForRawUrlAt("kb://chunk/123"))
        assertEquals("kb://chunk/456", knowledgeChunkUriForRawUrlAt("kb://chunk/456."))
        assertNull(knowledgeChunkUriForRawUrlAt("https://example.com"))
    }

    @Test
    fun knowledgeChunkUriForReferenceAt_linksRawChunkIds() {
        assertEquals("kb://chunk/123", knowledgeChunkUriForReferenceAt("chunk_id=123"))
        assertEquals("kb://chunk/456", knowledgeChunkUriForReferenceAt("[chunk_id=456]"))
        assertNull(knowledgeChunkUriForReferenceAt("not a chunk"))
    }

    @Test
    fun markdownLinks_acceptKnowledgeCitationsWithSpacedSourceNames() {
        val citation = "[Manual de diagnostico y terapeutica medicas H 12 OCTUBRE 2022 (1).pdf chunk 12](kb://chunk/123)"

        assertEquals("kb://chunk/123", markdownLinkUriForLinkAt(citation))
        assertEquals(
            "Manual de diagnostico y terapeutica medicas H 12 OCTUBRE 2022 (1).pdf chunk 12",
            markdownLinkLabelForLinkAt(citation)
        )
    }

    @Test
    fun markdownLinks_acceptModelGeneratedSpaceBeforeUrl() {
        assertEquals(
            "kb://chunk/123",
            markdownLinkUriForLinkAt("[Manual with spaces chunk 12] (kb://chunk/123)")
        )
        assertEquals(
            "kb://chunk/123",
            markdownLinkUriForLinkAt("[Manual with spaces chunk 12](<kb://chunk/123>)")
        )
    }

    @Test
    fun markdownLinks_unescapeEscapedLabels() {
        val citation = "[Manual \\[Draft\\] chunk 12](kb://chunk/123)"

        assertEquals("Manual [Draft] chunk 12", markdownLinkLabelForLinkAt(citation))
    }

    @Test
    fun markdownBlockParser_detectsTablesBetweenTextBlocks() {
        val markdown = """
            Intro

            | Name | Value |
            | --- | --- |
            | Alpha | 1 |
            | Beta | 2 |

            Outro
        """.trimIndent()

        assertEquals(listOf("text", "table", "text"), markdownBlockKindsForText(markdown))
        assertEquals(2 to 2, markdownTableShapeForText(markdown))
    }

    @Test
    fun markdownBlockParser_detectsPipeTablesWithoutSeparatorRows() {
        val markdown = """
            | Source | Use |
            | Manual 12 Octubre | Treatment guidance |
            | Cardioteca | Clinical summaries |
        """.trimIndent()

        assertEquals(listOf("table"), markdownBlockKindsForText(markdown))
        assertEquals(2 to 2, markdownTableShapeForText(markdown))
    }

    @Test
    fun markdownBlockParser_keepsClosedCodeBlocks() {
        val markdown = """
            ```kotlin
            val answer = 42
            ```
        """.trimIndent()

        assertEquals(listOf("code"), markdownBlockKindsForText(markdown))
        assertEquals("kotlin" to "val answer = 42", markdownCodeBlockForText(markdown))
    }

    @Test
    fun markdownBlockParser_treatsTrailingOpenFenceAsCodeBlock() {
        val markdown = """
            ```python
            print("hola")
        """.trimIndent()

        assertEquals(listOf("code"), markdownBlockKindsForText(markdown))
        assertEquals("python" to "print(\"hola\")", markdownCodeBlockForText(markdown))
    }

    @Test
    fun markdownBlockParser_keepsTextBeforeTrailingOpenFence() {
        val markdown = """
            Intro

            ```sql
            select * from chats
        """.trimIndent()

        assertEquals(listOf("text", "code"), markdownBlockKindsForText(markdown))
        assertEquals("sql" to "select * from chats", markdownCodeBlockForText(markdown))
    }

    @Test
    fun markdownHeadingParser_acceptsCommonAtxHeaders() {
        assertEquals(2, markdownHeadingLevelForLine("## Results"))
        assertEquals(3, markdownHeadingLevelForLine("###Results"))
        assertNull(markdownHeadingLevelForLine("plain text"))
    }

    @Test
    fun markdownHeadingParser_acceptsSetextHeaders() {
        assertEquals(1, markdownSetextHeadingLevelForLine("===="))
        assertEquals(2, markdownSetextHeadingLevelForLine("---"))
        assertNull(markdownSetextHeadingLevelForLine("not a heading"))
    }
}
