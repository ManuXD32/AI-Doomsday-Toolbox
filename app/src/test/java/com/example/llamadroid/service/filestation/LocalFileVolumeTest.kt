package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.LocalFileVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalFileVolumeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun vol(readOnly: Boolean = false): LocalFileVolume {
        val root = File(tmp.root, "root").apply { mkdirs() }
        return LocalFileVolume("v", "Vol", root, readOnly)
    }

    private fun code(block: () -> Unit): FsErrorCode {
        try { block() } catch (e: FsException) { return e.code }
        throw AssertionError("expected FsException")
    }

    private fun LocalFileVolume.write(path: String, text: String, append: Boolean = false) =
        openWrite(path, append).use { it.write(text.toByteArray()) }

    private fun LocalFileVolume.read(path: String, offset: Long = 0) =
        openRead(path, offset).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun writeReadStatListAppend() {
        val v = vol()
        v.mkdirs("a/b")
        v.write("a/b/f.txt", "hello")
        v.write("a/b/f.txt", " world", append = true)
        assertEquals("hello world", v.read("a/b/f.txt"))
        assertEquals("world", v.read("a/b/f.txt", 6))
        v.write("a/b/f.txt", "x")
        assertEquals("x", v.read("a/b/f.txt"))
        val e = v.stat("a/b/f.txt")
        assertEquals(1L, e.size)
        assertEquals("text/plain", e.mime)
        assertEquals("\"1-${e.mtime}\"", e.etag)
        assertEquals(listOf("b"), v.list("a").map { it.name })
        assertEquals(setOf("a", "a/b", "a/b/f.txt"), v.walk("").map { it.path }.toSet())
        assertEquals(-1L, v.stat("a").size)
    }

    @Test fun renameDeleteAndConflicts() {
        val v = vol()
        v.write("a.txt", "1"); v.write("b.txt", "2")
        assertEquals(FsErrorCode.EXISTS, code { v.rename("a.txt", "b.txt") })
        assertEquals("c.txt", v.rename("a.txt", "c.txt"))
        assertEquals(FsErrorCode.INVALID_PATH, code { v.rename("c.txt", "x/y") })
        assertEquals(FsErrorCode.NOT_FOUND, code { v.stat("a.txt") })
        v.mkdirs("d/e"); v.write("d/e/f", "z")
        v.delete("d")
        assertEquals(FsErrorCode.NOT_FOUND, code { v.stat("d") })
        assertEquals(FsErrorCode.INVALID_PATH, code { v.delete("") })
        assertEquals(FsErrorCode.NOT_FOUND, code { v.openWrite("missing/x") })
        v.mkdirs("d")
        assertEquals(FsErrorCode.EXISTS, code { v.mkdirs("b.txt") })
        v.mkdirs("d")
    }

    @Test fun fastMoveRefusesClash() {
        val v = vol()
        v.mkdirs("dst"); v.write("f", "1"); v.write("dst/f", "2")
        assertFalse(v.fastMove("f", "dst"))
        v.delete("dst/f")
        assertTrue(v.fastMove("f", "dst"))
        assertEquals("1", v.read("dst/f"))
        assertFalse(v.fastCopy("dst/f", ""))
    }

    @Test fun containment() {
        val v = vol()
        assertEquals(FsErrorCode.INVALID_PATH, code { v.stat("../x") })
        File(tmp.root, "outside").mkdirs()
        File(tmp.root, "outside/secret.txt").writeText("s")
        java.nio.file.Files.createSymbolicLink(
            File(tmp.root, "root/link").toPath(), File(tmp.root, "outside").toPath(),
        )
        assertEquals(FsErrorCode.INVALID_PATH, code { v.stat("link/secret.txt") })
        assertEquals(FsErrorCode.INVALID_PATH, code { v.openRead("link/secret.txt") })
        assertTrue(v.list("").none { it.name == "link" })
        // Deleting the link removes only the link.
        v.delete("link")
        assertTrue(File(tmp.root, "outside/secret.txt").exists())
    }

    @Test fun readOnlyRefusesWrites() {
        val root = File(tmp.root, "ro").apply { mkdirs() }
        File(root, "f").writeText("x")
        val v = LocalFileVolume("ro", "RO", root, readOnly = true)
        assertEquals(FsErrorCode.READ_ONLY, code { v.openWrite("g") })
        assertEquals(FsErrorCode.READ_ONLY, code { v.mkdirs("d") })
        assertEquals(FsErrorCode.READ_ONLY, code { v.delete("f") })
        assertEquals(FsErrorCode.READ_ONLY, code { v.rename("f", "g") })
        assertEquals("x", v.read("f"))
    }
}
