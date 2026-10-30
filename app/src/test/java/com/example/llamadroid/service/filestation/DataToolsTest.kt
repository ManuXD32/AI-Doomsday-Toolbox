package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.tools.DataTools
import com.example.llamadroid.service.filestation.tools.TaskControl
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.LocalFileVolume
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DataToolsTest {
    private lateinit var dir: File
    private lateinit var vol: LocalFileVolume
    private val ctl = TaskControl()

    @Before fun setUp() {
        dir = Files.createTempDirectory("fs-data").toFile()
        vol = LocalFileVolume("a", "A", dir)
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun write(path: String, text: String) {
        File(dir, path).also { it.parentFile?.mkdirs() }.writeText(text)
    }

    @Test fun sha256KnownVector() {
        write("abc.txt", "abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DataTools.sha256(vol, "abc.txt", ctl))
        val all = DataTools.sha256Items(listOf(vol to "abc.txt"), ctl)
        assertEquals(1, all.size)
        assertEquals(3L, all[0].size)
        assertTrue(DataTools.hashesJson(all).toString().contains("ba7816bf"))
    }

    @Test fun sha256FolderWalksFilesAndSkipsHidden() {
        write("d/one.txt", "1")
        write("d/sub/two.txt", "2")
        write("d/.attachments.x/img.png", "3")
        val paths = DataTools.sha256Items(listOf(vol to "d"), ctl).map { it.path }.sorted()
        assertEquals(listOf("d/one.txt", "d/sub/two.txt"), paths)
    }

    @Test fun duplicatesGroupBySizeThenHash() {
        write("a/same1.txt", "hello world")
        write("b/same2.txt", "hello world")
        write("c/deep/same3.txt", "hello world")
        write("a/other.txt", "HELLO WORLD") // same size, different content
        write("a/unique.txt", "zzz")
        write("a/empty1.txt", "")
        write("b/empty2.txt", "")
        write(".attachments.q/dup.txt", "hello world") // hidden, ignored
        val r = DataTools.findDuplicates(vol, "", 1, ctl)
        assertEquals(1, r.groups.size)
        val g = r.groups[0]
        assertEquals(11L, g.size)
        assertEquals(listOf("a/same1.txt", "b/same2.txt", "c/deep/same3.txt"), g.paths)
        assertFalse(r.truncated)
        assertTrue(DataTools.duplicatesJson(r).toString().contains("\"groups\""))
        // minSize 0 still ignores empties because unknown/empty files are floored at 1 byte
        assertEquals(1, DataTools.findDuplicates(vol, "", 0, ctl).groups.size)
    }

    @Test fun cancellationStopsHashing() {
        write("big.bin", "x".repeat(300_000))
        var calls = 0
        val cancelling = TaskControl(isCancelled = { ++calls > 2 })
        try {
            DataTools.sha256(vol, "big.bin", cancelling)
            fail("expected cancel")
        } catch (e: FsException) {
            assertEquals(FsErrorCode.IO, e.code)
            assertEquals("cancelled", e.message)
        }
    }
}
