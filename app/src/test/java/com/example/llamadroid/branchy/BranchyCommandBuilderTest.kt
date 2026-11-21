package com.example.llamadroid.branchy

import org.junit.Assert.assertEquals
import org.junit.Test

class BranchyCommandBuilderTest {
    private val exe = "/bin/branchy"

    @Test
    fun benchAndPlan() {
        assertEquals(listOf(exe, "bench", "--threads", "4"), BranchyCommandBuilder.bench(exe, 4))
        assertEquals(listOf(exe, "plan", "--in", "/t/p.json"), BranchyCommandBuilder.plan(exe, "/t/p.json"))
    }

    @Test
    fun tokenizerTrainInputsAndMaxBytes() {
        val argv = BranchyCommandBuilder.tokenizerTrain(
            exe, "/r/tok.json", 16384,
            listOf(
                TokenizerInput("/d/a.jsonl"),
                TokenizerInput("/d/b.json", "chat"),
                TokenizerInput("/d/c.parquet", "instruction", "instruction=q,output=a"),
                TokenizerInput("/d/d.parquet", null, "text=t")
            ),
            1000L
        )
        assertEquals(
            listOf(
                exe, "tokenizer-train", "--out", "/r/tok.json", "--vocab", "16384",
                "--input", "/d/a.jsonl", "--input", "/d/b.json:chat",
                "--input", "/d/c.parquet:instruction:instruction=q,output=a",
                "--input", "/d/d.parquet:auto:text=t", "--max-bytes", "1000"
            ),
            argv
        )
        assertEquals(false, BranchyCommandBuilder.tokenizerTrain(exe, "o", 8192, emptyList()).contains("--max-bytes"))
    }

    @Test
    fun prepare() {
        assertEquals(
            listOf(exe, "prepare", "--tokenizer", "t.json", "--input", "a.parquet", "--format", "text", "--columns", "text=text", "--out", "o.btok", "--max-rows", "500"),
            BranchyCommandBuilder.prepare(exe, "t.json", "a.parquet", "text", "text=text", "o.btok", 500L)
        )
        assertEquals(
            listOf(exe, "prepare", "--tokenizer", "t.json", "--input", "a.txt", "--format", "auto", "--out", "o.btok"),
            BranchyCommandBuilder.prepare(exe, "t.json", "a.txt", "", "", "o.btok", null)
        )
    }

    @Test
    fun singleDeviceTrainPassesTheControlFile() {
        assertEquals(
            listOf(exe, "train", "--run", "/r", "--threads", "4", "--control-file", "/r/control.json"),
            BranchyCommandBuilder.train(exe, "/r", false, null, 4, "/r/control.json")
        )
    }

    @Test
    fun train() {
        assertEquals(listOf(exe, "train", "--run", "/r"), BranchyCommandBuilder.train(exe, "/r", false, null, 0))
        assertEquals(
            listOf(exe, "train", "--run", "/r", "--resume", "--threads", "6"),
            BranchyCommandBuilder.train(exe, "/r", true, null, 6)
        )
        assertEquals(
            listOf(exe, "train", "--run", "/r", "--init-from", "/r/checkpoints/step_9.bckpt", "--threads", "2"),
            BranchyCommandBuilder.train(exe, "/r", false, "/r/checkpoints/step_9.bckpt", 2)
        )
    }

    @Test
    fun exportAndGenerate() {
        assertEquals(
            listOf(exe, "export-gguf", "--ckpt", "c", "--tokenizer", "t", "--out", "m.gguf", "--type", "q8_0"),
            BranchyCommandBuilder.exportGguf(exe, "c", "t", "m.gguf", "q8_0")
        )
        assertEquals(
            listOf(exe, "generate", "--ckpt", "c", "--tokenizer", "t", "--prompt", "hi there", "--chat", "--max-tokens", "64", "--temperature", "0.8", "--seed", "7"),
            BranchyCommandBuilder.generate(exe, "c", "t", "hi there", true, 64, 0.8, 7L)
        )
        assertEquals(
            false,
            BranchyCommandBuilder.generate(exe, "c", "t", "p", false, 8, 1.0, 1L).contains("--chat")
        )
    }

    @Test
    fun benchSweepAndClusterBench() {
        assertEquals(listOf(exe, "bench", "--sweep", "--seconds", "1.5"), BranchyCommandBuilder.benchSweep(exe, 1.5))
        assertEquals(
            listOf(exe, "cluster-bench", "--config", "/c.json", "--token-file", "/t", "--apply"),
            BranchyCommandBuilder.clusterBench(exe, "/c.json", "/t")
        )
        assertEquals(
            listOf(exe, "cluster-bench", "--config", "/c.json", "--token-file", "/t"),
            BranchyCommandBuilder.clusterBench(exe, "/c.json", "/t", apply = false)
        )
    }
}
