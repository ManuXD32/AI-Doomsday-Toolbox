package com.example.llamadroid.branchy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BranchyRunStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(root: File, rel: String, size: Int): File =
        File(root, rel).apply { parentFile?.mkdirs(); writeBytes(ByteArray(size)) }

    private fun sampleRun(): File = tmp.newFolder("run").also { r ->
        file(r, "data/1.btok", 100); file(r, "data/1.valid.btok", 10); file(r, "data/1.done", 1)
        file(r, "tokenizer.json", 5)
        file(r, "checkpoints/step-10/w.bin", 1000); file(r, "stage2/checkpoints/step-5/w.bin", 200)
        file(r, "shards/mirror/10/u.bin", 300); file(r, "stage2/shards/mirror/5/u.bin", 30)
        file(r, "dp/shard0.bin", 50); file(r, "stage2/dp/shard1.bin", 7)
        file(r, "export/a-s10-q8_0.gguf", 400)
        file(r, "stderr.log", 3)
    }

    @Test
    fun scanSplitsPartsWithSizes() {
        val s = BranchyRunStorageScan.scan(sampleRun())
        assertEquals(111L, s.of(BranchyStoragePart.TOKENIZED))
        assertEquals(5L, s.of(BranchyStoragePart.TOKENIZER))
        assertEquals(1200L, s.of(BranchyStoragePart.CHECKPOINTS))
        assertEquals(330L, s.of(BranchyStoragePart.MIRROR))
        assertEquals(57L, s.of(BranchyStoragePart.DP))
        assertEquals(400L, s.of(BranchyStoragePart.EXPORTS))
        assertEquals(3L, s.of(BranchyStoragePart.OTHER))
        assertEquals(111L + 330L + 57L, s.freeable)
        assertEquals(2106L, s.total)
    }

    @Test
    fun freeSpaceKeepsCheckpointsAndExports() {
        val run = sampleRun()
        val freed = BranchyRunStorageScan.freeSpace(run)
        assertEquals(498L, freed)
        assertTrue(File(run, "checkpoints/step-10/w.bin").isFile)
        assertTrue(File(run, "stage2/checkpoints/step-5/w.bin").isFile)
        assertTrue(File(run, "export/a-s10-q8_0.gguf").isFile)
        assertTrue(File(run, "tokenizer.json").isFile)
        assertFalse(File(run, "data/1.btok").exists())
        assertFalse(File(run, "shards").exists())
        assertFalse(File(run, "dp").exists())
        assertEquals(0L, BranchyRunStorageScan.scan(run).freeable)
    }

    @Test
    fun exportMovePlanAvoidsClashes() {
        val run = sampleRun()
        file(run, "export/b-s10-f16.gguf", 9)
        val models = tmp.newFolder("models")
        file(models, "a-s10-q8_0.gguf", 1)
        val plan = BranchyRunStorageScan.planExportMoves(run, models, reservedNames = setOf("B-s10-f16.gguf"))
        assertEquals(2, plan.size)
        assertEquals(listOf("a-s10-q8_0.gguf", "b-s10-f16.gguf"), plan.map { it.from.name })
        assertEquals("a-s10-q8_0-2.gguf", plan[0].to.name)
        assertEquals("b-s10-f16-2.gguf", plan[1].to.name)
        assertEquals(2, plan.map { it.to.name.lowercase() }.toSet().size)
        assertTrue(plan.all { it.to.parentFile == models })
    }

    @Test
    fun classifyIgnoresNonGgufExports() {
        assertEquals(BranchyStoragePart.OTHER, BranchyRunStorageScan.classify("export/a.gguf.part"))
        assertEquals(BranchyStoragePart.EXPORTS, BranchyRunStorageScan.classify("export/a.gguf"))
    }
}
