package com.example.llamadroid.service.chat

import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.service.compaction.AttachmentManifestItem
import com.example.llamadroid.service.compaction.NativeChatTurnDecoratorLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachedImageLogicTest {
    private fun ref(id: Long, msg: Long) = AttachedImageRef(id, msg, "/a/$id.jpg", "$id.jpg", "image/jpeg")
    private fun msg(id: Long, role: String) = LlamaMessageEntity(id = id, chatId = 1, role = role, content = "x", timestamp = id)

    @Test fun detectsImages() {
        assertTrue(AttachedImageLogic.isImage("a.HEIC", "application/octet-stream"))
        assertTrue(AttachedImageLogic.isImage("a", "image/png"))
        assertFalse(AttachedImageLogic.isImage("a.pdf", "application/pdf"))
    }

    @Test fun newestFourOnly() {
        val sel = AttachedImageLogic.newest((1L..6L).map { ref(it, 10) })
        assertEquals(listOf(6L, 5L, 4L, 3L), sel.map { it.attachmentId })
    }

    @Test fun visionServerGetsPictures() {
        val sel = listOf(ref(1, 10), ref(2, 10))
        val plan = AttachedImageLogic.plan(sel, true, false, true, 10, false)
        assertTrue(plan.values.all { it == AttachedImageMode.PICTURE })
        assertEquals(mapOf(10L to listOf("/a/1.jpg", "/a/2.jpg")), AttachedImageLogic.picturesByMessage(sel, plan))
    }

    @Test fun blindServerDescribesOrMarks() {
        val sel = listOf(ref(1, 10))
        assertEquals(AttachedImageMode.DESCRIBE, AttachedImageLogic.plan(sel, false, false, true, 10, false)[1L])
        assertEquals(AttachedImageMode.MARKED, AttachedImageLogic.plan(sel, false, false, false, 10, false)[1L])
    }

    @Test fun liteRtOnlyOnePictureOnLatestMessage() {
        val sel = listOf(ref(1, 8), ref(2, 10), ref(3, 10))
        val plan = AttachedImageLogic.plan(sel, true, true, true, 10, false)
        assertEquals(AttachedImageMode.DESCRIBE, plan[1L])
        assertEquals(AttachedImageMode.PICTURE, plan[2L])
        assertEquals(AttachedImageMode.DESCRIBE, plan[3L])
        val own = AttachedImageLogic.plan(sel, true, true, false, 10, true)
        assertTrue(own.values.all { it == AttachedImageMode.MARKED })
    }

    @Test fun markers() {
        assertNull(AttachedImageLogic.marker(null))
        assertEquals("(image)", AttachedImageLogic.marker(AttachedImageMode.MARKED))
    }

    @Test fun manifestWithMarkerNoteAndBlocks() {
        val items = listOf(AttachmentManifestItem("f1", "a.jpg", 100, AttachedImageLogic.MARKER_DESCRIBED))
        val out = NativeChatTurnDecoratorLogic.appendManifest("hi", items, "NOTE", listOf("BLOCK"))
        assertEquals("hi\n\n[Attached files: f1 a.jpg (100 B) (image, described below)]\nNOTE\n\nBLOCK", out)
    }

    @Test fun remapsToLastIdOfUserRun() {
        val list = listOf(msg(1, "user"), msg(2, "user"), msg(3, "assistant"), msg(4, "user"))
        val out = AttachedImageLogic.remapToMergedIds(list, mapOf(1L to listOf("a"), 2L to listOf("b"), 4L to listOf("c")))
        assertEquals(mapOf(2L to listOf("a", "b"), 4L to listOf("c")), out)
    }
}
