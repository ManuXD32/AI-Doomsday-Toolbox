package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.data.db.KnowledgeBaseSourceStatus
import com.example.llamadroid.data.db.KnowledgeChunkEntity
import com.example.llamadroid.data.db.KnowledgeSourceEntity
import com.example.llamadroid.service.compaction.AttachmentManifestItem
import com.example.llamadroid.service.compaction.NativeChatTurnDecoratorLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDocumentAttachTest {
    private fun source(id: Long, status: String, updated: Long = 1_000L) = KnowledgeSourceEntity(
        id = id, knowledgeBaseId = 1, type = "file", sourceRef = "file:/x/$id", title = "d$id",
        status = status, updatedAt = updated, progressUpdatedAt = updated
    )

    private fun chunk(index: Int, text: String, start: Int, end: Int) = KnowledgeChunkEntity(
        knowledgeBaseId = 1, sourceId = 1, chunkIndex = index, text = text, startOffset = start, endOffset = end
    )

    @Test fun sendBlockedWhileAnySourceIsPending() {
        val s = summarizeDocumentIndexing(
            listOf(source(1, KnowledgeBaseSourceStatus.INDEXED), source(2, KnowledgeBaseSourceStatus.EMBEDDING)), nowMs = 2_000L
        )
        assertEquals(2, s.total)
        assertEquals(1, s.finished)
        assertFalse(s.canSend)
    }

    @Test fun sendAllowedWhenIndexedOrFailed() {
        val s = summarizeDocumentIndexing(
            listOf(source(1, KnowledgeBaseSourceStatus.INDEXED), source(2, KnowledgeBaseSourceStatus.ERROR)), nowMs = 2_000L
        )
        assertTrue(s.canSend)
        assertEquals(1, s.failed)
        assertTrue(summarizeDocumentIndexing(emptyList(), 0L).canSend)
    }

    @Test fun staleQueuedSourceDoesNotBlockForever() {
        val sources = listOf(source(1, KnowledgeBaseSourceStatus.QUEUED, updated = 0L))
        assertFalse(summarizeDocumentIndexing(sources, nowMs = DOCUMENT_INDEX_STALE_MS - 1).canSend)
        assertTrue(summarizeDocumentIndexing(sources, nowMs = DOCUMENT_INDEX_STALE_MS + 1).canSend)
    }

    @Test fun pathsAndCompanion() {
        assertEquals("file:/data/a%20b.pdf", chatDocumentSourceRef("/data/a b.pdf"))
        assertEquals("/data/a.pdf.txt", chatDocumentCompanionPath("/data/a.pdf"))
        assertTrue(chatDocumentNeedsCompanion("r.pdf", "application/pdf"))
        assertTrue(chatDocumentNeedsCompanion("r.docx", "application/octet-stream"))
        assertFalse(chatDocumentNeedsCompanion("n.md", "text/markdown"))
    }

    @Test fun chunksAreStitchedWithoutOverlap() {
        val text = reassembleChunkText(
            listOf(chunk(1, "cdefgh", 2, 8), chunk(0, "abcdef", 0, 6), chunk(2, "ghij", 6, 10))
        )
        assertEquals("abcdefghij", text)
        assertEquals("ab\ncd", reassembleChunkText(listOf(chunk(0, "ab", 0, 2), chunk(1, "cd", 5, 7))))
    }

    @Test fun announceNamesWorkspacePathAndId() {
        val line = NativeChatTurnDecoratorLogic.manifestLine(
            listOf(
                AttachmentManifestItem("f12", "attachments/report.pdf", 1_258_291),
                AttachmentManifestItem("f13", "attachments/report.pdf.txt", 40_000)
            )
        )
        assertEquals("[Attached files: f12 attachments/report.pdf (1.2 MB), f13 attachments/report.pdf.txt (39 KB)]", line)
    }
}
