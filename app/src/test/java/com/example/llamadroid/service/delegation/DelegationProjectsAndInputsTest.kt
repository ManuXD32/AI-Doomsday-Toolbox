package com.example.llamadroid.service.delegation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationProjectsAndInputsTest {
    private val now = 1_800_000_000_000L

    @Test
    fun projectAndThreadChoicesParse() {
        assertEquals(DelegationToolArgs.ProjectChoice.New, DelegationToolArgs.parseProjectChoice(null))
        assertEquals(DelegationToolArgs.ProjectChoice.New, DelegationToolArgs.parseProjectChoice(" NEW "))
        assertEquals(DelegationToolArgs.ProjectChoice.Continue, DelegationToolArgs.parseProjectChoice("Continue"))
        assertEquals(DelegationToolArgs.ProjectChoice.Id("ws-123"), DelegationToolArgs.parseProjectChoice("\"ws-123\""))
        assertNull(DelegationToolArgs.parseThreadChoice(""))
        assertNull(DelegationToolArgs.parseThreadChoice("new"))
        assertEquals("sess-9", DelegationToolArgs.parseThreadChoice(" sess-9 "))
    }

    @Test
    fun messageSchemaHasAttachmentsAndStartSchemaHasThread() {
        assertTrue(JSONObject(DelegationToolArgs.messageSchema()).getJSONObject("properties").has("attachments"))
        val start = JSONObject(DelegationToolArgs.startSchema()).getJSONObject("properties")
        assertTrue(start.has("thread"))
        assertFalse(start.getJSONObject("project").has("enum"))
        assertEquals("message", JSONObject(DelegationToolArgs.messageSchema()).getJSONArray("required").getString(0))
    }

    @Test
    fun catalogFiltersCaseInsensitiveAndSortsNewestFirst() {
        val projects = listOf(
            CatalogProject("a", "Snake Game", now - 3_600_000L * 5, 1, false),
            CatalogProject("b", "snake clone", now - 60_000L * 3, 2, true),
            CatalogProject("c", "Website", now, 1, false)
        )
        assertEquals(listOf("b", "a"), DelegationProjectCatalog.filterProjects(projects, "SNAKE").map { it.id })
        val text = DelegationProjectCatalog.formatProjects(projects, null, now)
        assertTrue(text.contains("- b | snake clone | 3m ago | 2 threads | used by this chat"))
        assertTrue(text.contains("- a | Snake Game | 5h ago | 1 thread"))
        assertTrue(text.indexOf("- c |") < text.indexOf("- b |"))
        assertTrue(DelegationProjectCatalog.formatProjects(projects, "zzz", now).contains("No projects match"))
    }

    @Test
    fun catalogIsBoundedWithMoreMarker() {
        val many = (1..200).map { CatalogProject("workspace-id-$it", "Project number $it with a title", now - it * 1000L, 1, false) }
        val text = DelegationProjectCatalog.formatProjects(many, null, now)
        assertTrue(text.length <= DelegationProjectCatalog.MAX_CHARS)
        assertTrue(Regex("\\+\\d+ more").containsMatchIn(text))
        val threads = (1..200).map { CatalogThread("session-$it", "Thread $it", now - it, if (it == 2) 1 else null) }
        val tt = DelegationProjectCatalog.formatThreads(threads, now)
        assertTrue(tt.length <= DelegationProjectCatalog.MAX_CHARS)
        assertTrue(tt.contains("+") && tt.contains("more"))
        assertTrue(DelegationProjectCatalog.formatThreads(listOf(CatalogThread("s", "t", now, 2)), now).contains("thread 2 of this task"))
    }

    @Test
    fun pendingRefBookkeepingKeepsRefsAddedMeanwhile() {
        val options = DelegationTaskOptions(pendingAttachmentRefs = listOf("f1", "img2"))
        val withNew = DelegationPendingRefs.added(options, listOf("img2", "f3"))
        assertEquals(listOf("f1", "img2", "f3"), withNew.pendingAttachmentRefs)
        val cleared = DelegationPendingRefs.cleared(withNew, listOf("f1", "img2"))
        assertEquals(listOf("f3"), cleared.pendingAttachmentRefs)
        assertTrue(DelegationPendingRefs.cleared(cleared, listOf("f3")).pendingAttachmentRefs.isEmpty())
        assertEquals(options, DelegationPendingRefs.cleared(options, emptyList()))
    }

    @Test
    fun promptBlocksFollowComposerShape() {
        val blocks = DelegateHarnessInputs.promptBlocks("hello", listOf("r1", "r2"))
        assertEquals(3, blocks.size)
        val text = JSONObject(blocks[0].toString())
        assertEquals("text", text.getString("type"))
        assertEquals("hello", text.getString("text"))
        val file = JSONObject(blocks[1].toString())
        assertEquals("file", file.getString("type"))
        assertEquals("r1", file.getString("receiptId"))
        assertEquals(1, DelegateHarnessInputs.promptBlocks("x", emptyList()).size)
    }

    @Test
    fun imageHelpersAndNotes() {
        assertTrue(DelegateHarnessInputs.isImageName("Photo.JPG"))
        assertFalse(DelegateHarnessInputs.isImageName("report.pdf"))
        assertEquals(55L, DelegateHarnessInputs.messageIdOfImageRef("img55"))
        assertNull(DelegateHarnessInputs.messageIdOfImageRef("f55"))
        assertEquals("", DelegateHarnessInputs.imageNotes(emptyList()))
        val notes = DelegateHarnessInputs.imageNotes(listOf(
            DelegateImageNote("inputs/a.png", "A red button"), DelegateImageNote("inputs/b.png", null)))
        assertTrue(notes.contains("- inputs/a.png: A red button"))
        assertTrue(notes.contains("- inputs/b.png") && !notes.contains("b.png:"))
        val long = DelegateHarnessInputs.imageNotes(listOf(DelegateImageNote("p", "x".repeat(2000))))
        assertTrue(long.length < 800)
    }

    @Test
    fun messageReplyFormatsReopened() {
        val reopened = NativeChatDelegationTools.formatMessageReply(DelegationReply(null, true, "running", reopened = true))
        assertTrue(reopened.startsWith("The finished task was reopened"))
        assertTrue(reopened.contains("working on it"))
        assertEquals("done", NativeChatDelegationTools.formatMessageReply(DelegationReply("done", false, "running")))
        assertTrue(NativeChatDelegationTools.formatMessageReply(DelegationReply(null, true, "running")).startsWith("no reply yet"))
    }

    @Test
    fun subagentToolSurfaceIsStaticAndComplete() {
        val names = DelegateSubagentToolDefs.all().map { it.name }
        assertEquals(names.size, names.toSet().size)
        for (n in listOf("harness_list_projects", "harness_open_project", "harness_list_threads", "harness_open_thread")) {
            assertTrue(n, n in names)
        }
        assertEquals(names, DelegateSubagentToolDefs.all().map { it.name })
        val first = DelegateSubagentPrompt.firstUserMessage("b", emptyList(), "m", 4096, 512, DelegationApprovalPolicy.FULL_ACCESS,
            DelegationTaskOptions(projectId = "ws1", threadId = "s1"))
        assertTrue(first.contains("harness_open_project with id=ws1") && first.contains("s1"))
    }
}
