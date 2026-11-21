package com.example.llamadroid.branchy.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BranchyWorkerStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(root: File, rel: String, text: String) {
        File(root, rel).apply { parentFile?.mkdirs() }.writeText(text)
    }

    private fun layout(): File {
        val root = tmp.newFolder("worker")
        write(root, "c1/meta.json", """{"run":"My run","cluster_id":"c1","last_used":2000}""")
        write(root, "c1/shard.bin", "x".repeat(100))
        write(root, "c2/shard.bin", "y".repeat(10))
        write(root, "dp/meta.json", """{"runs":{"r1":{"run":"Run one","last_used":3000},"r1_b":{"run":"Run b","last_used":4000}}}""")
        write(root, "dp/dp_r1_r0_s0.btok", "z".repeat(30))
        write(root, "dp/dp_r1_r0_s1.btok.part", "z".repeat(20))
        write(root, "dp/dp_r1_b_r0_s0.btok", "w".repeat(9))
        write(root, "dp/loose.bin", "q".repeat(5))
        write(root, "files/a.slice.gguf", "s".repeat(7))
        write(root, "files/other.txt", "no")
        return root
    }

    @Test
    fun scanFindsEveryKindWithSizesAndNames() {
        val e = BranchyWorkerStorage.scan(layout()).associateBy { it.kind.name + ":" + it.id }
        assertEquals(6, e.size)
        assertEquals("My run", e["SHARDS:c1"]?.name)
        assertEquals(2_000_000L, e["SHARDS:c1"]?.lastUsedMs)
        assertNull(e["SHARDS:c2"]?.name)
        assertEquals(50L, e["DP_RUN:r1"]?.bytes)
        assertEquals("Run one", e["DP_RUN:r1"]?.name)
        assertEquals(9L, e["DP_RUN:r1_b"]?.bytes)
        assertEquals(5L, e["DP_DATA:dp"]?.bytes)
        assertEquals(7L, e["SLICE:a.slice.gguf"]?.bytes)
    }

    @Test
    fun deleteRunUpdatesMetaAndKeepsOthers() {
        val root = layout()
        val run = BranchyWorkerStorage.scan(root).first { it.kind == BranchyWorkerStorageKind.DP_RUN && it.id == "r1" }
        assertTrue(BranchyWorkerStorage.delete(root, run))
        assertEquals("r1", run.id)
        assertFalse(File(root, "dp/dp_r1_r0_s0.btok").exists())
        assertFalse(File(root, "dp/dp_r1_r0_s1.btok.part").exists())
        assertTrue(File(root, "dp/dp_r1_b_r0_s0.btok").exists())
        assertTrue(File(root, "dp/loose.bin").exists())
        assertFalse(File(root, "dp/meta.json").readText().contains("\"r1\":"))
        assertTrue(File(root, "dp/meta.json").readText().contains("r1_b"))
        assertTrue(File(root, "c1/shard.bin").exists())
    }

    @Test
    fun dpWithoutRunsIsOneEntryAndDeleteAllEmptiesRoot() {
        val root = tmp.newFolder("w2")
        write(root, "dp/part.bin", "12345")
        write(root, "c9/s", "1")
        val all = BranchyWorkerStorage.scan(root)
        assertEquals(setOf(BranchyWorkerStorageKind.DP_DATA, BranchyWorkerStorageKind.SHARDS), all.map { it.kind }.toSet())
        assertTrue(BranchyWorkerStorage.deleteAll(root, all))
        assertTrue(BranchyWorkerStorage.scan(root).isEmpty())
    }

    @Test
    fun refusesPathsOutsideRoot() {
        val root = layout()
        val outside = tmp.newFile("keep.slice.gguf")
        val entry = BranchyWorkerStorageEntry(BranchyWorkerStorageKind.SLICE, "x", null, outside, 1, 0)
        assertFalse(BranchyWorkerStorage.delete(root, entry))
        assertTrue(outside.exists())
        assertNotNull(BranchyWorkerStorage.scan(root))
    }
}
