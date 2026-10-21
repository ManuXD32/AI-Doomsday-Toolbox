package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.http.Clock
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.LocalFileVolume
import com.example.llamadroid.service.filestation.volume.TrashStore
import com.example.llamadroid.service.filestation.volume.VolumeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrashStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000_000L

    private fun setup(ro: Boolean = false): Triple<LocalFileVolume, VolumeRegistry, TrashStore> {
        val root = File(tmp.root, "v").apply { mkdirs() }
        val v = LocalFileVolume("v", "V", root, ro)
        val reg = VolumeRegistry(listOf(v))
        return Triple(v, reg, TrashStore(reg, Clock { now }))
    }

    private fun LocalFileVolume.put(path: String, text: String) {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) mkdirs(parent)
        openWrite(path).use { it.write(text.toByteArray()) }
    }

    private fun exists(v: LocalFileVolume, p: String) = try { v.stat(p); true } catch (_: FsException) { false }

    @Test fun trashAndRestore() {
        val (v, _, trash) = setup()
        v.put("docs/a.md", "hello"); v.put("docs/.attachments.a.md/i.png", "p")
        val item = trash.trash("v", "docs/a.md")
        assertEquals("a.md", item.name); assertEquals(5L, item.size)
        assertFalse(exists(v, "docs/a.md"))
        assertFalse(exists(v, "docs/.attachments.a.md"))
        assertEquals(listOf(item.id), trash.list("v").map { it.id })
        // a new file took the place; restore renames
        v.put("docs/a.md", "new")
        val restored = trash.restore("v", listOf(item.id))
        assertEquals("docs/a (1).md", restored.single().path)
        assertTrue(exists(v, "docs/.attachments.a (1).md/i.png"))
        assertTrue(trash.list("v").isEmpty())
    }

    @Test fun restoreRecreatesParent() {
        val (v, _, trash) = setup()
        v.put("x/y/f.txt", "1")
        val item = trash.trash("v", "x/y")
        v.delete("x")
        assertTrue(item.isDir)
        assertEquals("x/y", trash.restore("v", listOf(item.id)).single().path)
        assertTrue(exists(v, "x/y/f.txt"))
    }

    @Test fun emptyAndPurge() {
        val (v, _, trash) = setup()
        v.put("a", "1"); v.put("b", "2"); v.put("c", "3")
        val ia = trash.trash("v", "a")
        now += 10 * 86_400_000L
        val ib = trash.trash("v", "b")
        val ic = trash.trash("v", "c")
        assertEquals(0, trash.purge("v", 0))
        assertEquals(1, trash.purge("v", 5))  // only "a" is older than 5 days
        assertEquals(setOf(ib.id, ic.id), trash.list("v").map { it.id }.toSet())
        assertFalse(exists(v, ".filestation-trash/${ia.id}"))
        assertEquals(1, trash.empty("v", listOf(ib.id)))
        assertEquals(1, trash.empty("v"))
        assertFalse(exists(v, ".filestation-trash"))
        assertTrue(trash.list("v").isEmpty())
    }

    @Test fun disabledOnReadOnlyAndInvalidTargets() {
        val root = File(tmp.root, "ro").apply { mkdirs() }
        File(root, "f").writeText("x")
        val reg = VolumeRegistry(listOf(LocalFileVolume("ro", "RO", root, readOnly = true)))
        val trash = TrashStore(reg)
        assertFalse(trash.isEnabled("ro"))
        try { trash.trash("ro", "f"); throw AssertionError() } catch (e: FsException) {
            assertEquals(FsErrorCode.READ_ONLY, e.code)
        }
        val (v, _, t2) = setup()
        v.put("f", "1")
        val item = t2.trash("v", "f")
        try { t2.trash("v", ".filestation-trash/${item.id}"); throw AssertionError() } catch (e: FsException) {
            assertEquals(FsErrorCode.INVALID_PATH, e.code)
        }
    }
}
