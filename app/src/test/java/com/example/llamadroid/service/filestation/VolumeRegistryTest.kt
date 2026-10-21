package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.volume.ConflictPolicy
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.LocalFileVolume
import com.example.llamadroid.service.filestation.volume.VolumeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VolumeRegistryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun mk(name: String, ro: Boolean = false): LocalFileVolume {
        val root = File(tmp.root, name).apply { mkdirs() }
        return LocalFileVolume(name, name, root, ro)
    }

    private fun LocalFileVolume.put(path: String, text: String) {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) mkdirs(parent)
        openWrite(path).use { it.write(text.toByteArray()) }
    }

    private fun LocalFileVolume.text(path: String) = openRead(path).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun code(block: () -> Unit): FsErrorCode {
        try { block() } catch (e: FsException) { return e.code }
        throw AssertionError("expected FsException")
    }

    @Test fun lookupAndRequireWritable() {
        val reg = VolumeRegistry(listOf(mk("a"), mk("ro", true)))
        assertEquals("a", reg.get("a")?.id)
        assertNull(reg.get("zzz"))
        assertEquals(FsErrorCode.NOT_FOUND, code { reg.requireWritable("zzz") })
        assertEquals(FsErrorCode.READ_ONLY, code { reg.requireWritable("ro") })
    }

    @Test fun crossVolumeCopyAndMoveFolders() {
        val a = mk("a"); val b = mk("b")
        val reg = VolumeRegistry(listOf(a, b))
        a.put("dir/x.txt", "X"); a.put("dir/sub/y.txt", "Y"); b.mkdirs("in")
        val seen = ArrayList<Long>()
        val copied = reg.transfer("a", "dir", "b", "in", move = false, progress = { seen += it })
        assertEquals("in/dir", copied?.path)
        assertEquals("Y", b.text("in/dir/sub/y.txt"))
        assertEquals("X", a.text("dir/x.txt"))
        assertEquals(2L, seen.last())
        val moved = reg.transfer("a", "dir", "b", "in", move = true)
        assertEquals("in/dir (1)", moved?.path)
        assertEquals(FsErrorCode.NOT_FOUND, code { a.stat("dir") })
        assertEquals("X", b.text("in/dir (1)/x.txt"))
    }

    @Test fun conflictPolicies() {
        val a = mk("a"); val b = mk("b")
        val reg = VolumeRegistry(listOf(a, b))
        a.put("f.txt", "new"); b.put("f.txt", "old")
        assertNull(reg.transfer("a", "f.txt", "b", "", false, ConflictPolicy.SKIP))
        assertEquals("old", b.text("f.txt"))
        reg.transfer("a", "f.txt", "b", "", false, ConflictPolicy.OVERWRITE)
        assertEquals("new", b.text("f.txt"))
        assertEquals("f (1).txt", reg.transfer("a", "f.txt", "b", "", false, ConflictPolicy.RENAME)?.path)
        // copy onto itself renames; move onto itself is a no-op
        assertEquals("f (1).txt", reg.transfer("a", "f.txt", "a", "", false)?.path)
        assertEquals("f.txt", reg.transfer("a", "f.txt", "a", "", true)?.path)
        assertEquals(ConflictPolicy.SKIP, ConflictPolicy.parse("skip"))
        assertEquals(FsErrorCode.INVALID_PATH, code { ConflictPolicy.parse("bogus") })
    }

    @Test fun readOnlyRefusal() {
        val a = mk("a"); val ro = mk("ro", true)
        val reg = VolumeRegistry(listOf(a, ro))
        a.put("f", "1")
        assertEquals(FsErrorCode.READ_ONLY, code { reg.transfer("a", "f", "ro", "", false) })
        java.io.File(tmp.root, "ro/g").writeText("2")
        assertEquals(FsErrorCode.READ_ONLY, code { reg.transfer("ro", "g", "a", "", true) })
        // copying out of a read-only volume is fine
        assertEquals("g", reg.transfer("ro", "g", "a", "", false)?.path)
        assertEquals(FsErrorCode.READ_ONLY, code { reg.rename("ro", "g", "h") })
    }

    @Test fun folderIntoItselfRefused() {
        val a = mk("a")
        val reg = VolumeRegistry(listOf(a))
        a.mkdirs("d/e")
        assertEquals(FsErrorCode.INVALID_PATH, code { reg.transfer("a", "d", "a", "d/e", true) })
        assertEquals(FsErrorCode.INVALID_PATH, code { reg.transfer("a", "d", "a", "d", false) })
        assertEquals(FsErrorCode.INVALID_PATH, code { reg.transfer("a", "", "a", "d", false) })
    }

    @Test fun cancelLeavesNoPartialTarget() {
        val a = mk("a"); val b = mk("b")
        val reg = VolumeRegistry(listOf(a, b))
        a.put("big.bin", "x".repeat(200_000))
        val polls = intArrayOf(0)
        assertEquals(FsErrorCode.IO, code {
            reg.transfer("a", "big.bin", "b", "", true, cancelled = { ++polls[0] > 2 })
        })
        assertEquals(FsErrorCode.NOT_FOUND, code { b.stat("big.bin") })
        assertTrue(a.stat("big.bin").size > 0)
    }

    @Test fun attachmentsFollowDoc() {
        val a = mk("a"); val b = mk("b")
        val reg = VolumeRegistry(listOf(a, b))
        a.put("notes/doc.md", "# hi"); a.put("notes/.attachments.doc.md/img.png", "png"); b.mkdirs("out")
        val e = reg.rename("a", "notes/doc.md", "renamed.md")
        assertEquals("notes/renamed.md", e.path)
        assertEquals("png", a.text("notes/.attachments.renamed.md/img.png"))
        assertEquals(FsErrorCode.NOT_FOUND, code { a.stat("notes/.attachments.doc.md") })
        // same-volume move
        a.mkdirs("arch")
        reg.transfer("a", "notes/renamed.md", "a", "arch", move = true)
        assertEquals("png", a.text("arch/.attachments.renamed.md/img.png"))
        // cross-volume copy keeps the source attachments, renamed on clash
        b.put("out/renamed.md", "old")
        val c = reg.transfer("a", "arch/renamed.md", "b", "out", move = false)
        assertEquals("out/renamed (1).md", c?.path)
        assertEquals("png", b.text("out/.attachments.renamed (1).md/img.png"))
        assertEquals("png", a.text("arch/.attachments.renamed.md/img.png"))
    }
}
