package com.example.llamadroid.ui.ai.llama.delegation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationViewerSupportTest {
    @Test
    fun everyKnownStateHasALabel() {
        DELEGATION_ALL_STATES.forEach { assertNotNull("missing label for $it", delegationStateLabelRes(it)) }
        assertNull(delegationStateLabelRes("SOMETHING_ELSE"))
    }

    @Test
    fun elapsedFormatting() {
        assertEquals("0:00", formatDelegationElapsed(-5))
        assertEquals("1:05", formatDelegationElapsed(65_000))
        assertEquals("1:02:03", formatDelegationElapsed(3_723_000))
    }

    @Test
    fun contextPercentIsBounded() {
        assertNull(delegationContextPercent(10, 0))
        assertEquals(50, delegationContextPercent(500, 1000))
        assertEquals(100, delegationContextPercent(5000, 1000))
    }

    @Test
    fun previewTruncates() {
        assertEquals("abc", boundedPreview("  abc  ", 10))
        val long = "x".repeat(500)
        val preview = boundedPreview(long, 400)
        assertEquals(401, preview.length)
        assertTrue(preview.endsWith("…"))
        assertTrue(isPreviewTruncated(long))
    }

    @Test
    fun toolCallNamesParseDefensively() {
        val json = """[{"id":"1","function":{"name":"harness_wait","arguments":"{}"}},{"name":"finish_task"},{"x":1}]"""
        assertEquals(listOf("harness_wait", "finish_task"), parseToolCallNames(json))
        assertTrue(parseToolCallNames("not json").isEmpty())
        assertTrue(parseToolCallNames(null).isEmpty())
    }

    @Test
    fun resultFilesParseDefensively() {
        val files = parseResultFiles("""[{"path":"/a/b.txt","name":"b.txt","attachmentId":7},{"path":"/c/d.md"},{}]""")
        assertEquals(2, files.size)
        assertEquals(7L, files[0].attachmentId)
        assertEquals("d.md", files[1].name)
        assertNull(files[1].attachmentId)
        assertTrue(parseResultFiles("{{").isEmpty())
    }

    @Test
    fun digestParsesStructuredAndFallsBackToRaw() {
        val view = parseDigest(
            """{"state":"WAITING_APPROVAL","pendingApprovals":["Run: npm install"],"errors":[],"changedFiles":["a.kt","b.kt"],"turn":4}"""
        )
        assertNull(view.raw)
        val kinds = view.sections.map { it.kind }
        assertTrue(DigestSectionKind.STATE in kinds)
        assertTrue(DigestSectionKind.PENDING in kinds)
        assertTrue(DigestSectionKind.CHANGED in kinds)
        assertTrue(DigestSectionKind.OTHER in kinds)
        assertTrue(DigestSectionKind.ERRORS !in kinds)
        assertEquals(DigestSectionKind.STATE, view.sections.first().kind)

        val raw = parseDigest("[digest v1] thread 1/2")
        assertTrue(raw.sections.isEmpty())
        assertEquals("[digest v1] thread 1/2", raw.raw)
        assertTrue(parseDigest(null).sections.isEmpty())
    }

    @Test
    fun digestLinesAreBounded() {
        val many = (1..50).joinToString(",") { "\"f$it\"" }
        val view = parseDigest("""{"changedFiles":[$many]}""")
        val section = view.sections.single()
        assertEquals(DELEGATION_DIGEST_MAX_LINES, section.lines.size)
        assertEquals(20, section.hiddenCount)
    }

    @Test
    fun promptPercentIsBounded() {
        assertEquals(45, delegationPromptPercent(1234, 2740))
        assertEquals(100, delegationPromptPercent(5000, 2740))
        org.junit.Assert.assertNull(delegationPromptPercent(10, 0))
    }
}
