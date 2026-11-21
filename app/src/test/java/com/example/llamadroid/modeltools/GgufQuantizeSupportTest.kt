package com.example.llamadroid.modeltools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufQuantizeSupportTest {
    @Test
    fun argvOrdersFlagsInputOutputTypeThreads() {
        assertEquals(
            listOf("/x/q", "--allow-requantize", "in.gguf", "out.gguf", "Q4_K_M", "4"),
            GgufQuantizeSupport.buildArgv("/x/q", "in.gguf", "out.gguf", QuantTarget.Q4_K_M, 4, true)
        )
        assertEquals(
            listOf("/x/q", "in.gguf", "out.gguf", "Q8_0"),
            GgufQuantizeSupport.buildArgv("/x/q", "in.gguf", "out.gguf", QuantTarget.Q8_0, 0, false)
        )
    }

    @Test
    fun outputNameReplacesTrailingQuantTag() {
        assertEquals("model-Q8_0.gguf", GgufQuantizeSupport.outputName("model.gguf", "Q8_0"))
        assertEquals("model-Q4_0.gguf", GgufQuantizeSupport.outputName("model-F16.gguf", "Q4_0"))
        assertEquals("model-Q4_0.gguf", GgufQuantizeSupport.outputName("model-Q8_0.gguf", "Q4_0"))
        assertEquals("model-Q4_K_M.gguf", GgufQuantizeSupport.outputName("model.Q6_K.gguf", "Q4_K_M"))
    }

    @Test
    fun detectsQuantizedNames() {
        assertTrue(GgufQuantizeSupport.looksQuantized("a-Q4_K_M.gguf"))
        assertFalse(GgufQuantizeSupport.looksQuantized("a-F16.gguf"))
        assertFalse(GgufQuantizeSupport.looksQuantized("a.gguf"))
    }

    @Test
    fun estimatesScaleBySourceBits() {
        val q8 = GgufQuantizeSupport.estimateOutputBytes(1_000_000_000L, "m-F16.gguf", QuantTarget.Q8_0)
        assertTrue(q8 in 520_000_000L..540_000_000L)
        val unknown = GgufQuantizeSupport.estimateOutputBytes(1_000_000_000L, "m.gguf", QuantTarget.Q4_0)
        assertTrue(unknown in 270_000_000L..290_000_000L)
        assertEquals(0L, GgufQuantizeSupport.estimateOutputBytes(0L, "m.gguf", QuantTarget.Q4_0))
        assertEquals(1000L, GgufQuantizeSupport.estimateExportBytes(1000L, "f16"))
    }

    @Test
    fun parsesProgressLines() {
        assertEquals(12 to 291, GgufQuantizeSupport.parseProgress("[  12/ 291]  blk.0.attn_q.weight - [ 2048, 2048], type = f16"))
        assertNull(GgufQuantizeSupport.parseProgress("llama_model_quantize: n_layer = 22"))
        assertNull(GgufQuantizeSupport.parseProgress("[ 1/ 0] x"))
    }

    @Test
    fun spaceCheckKeepsMargin() {
        assertFalse(GgufQuantizeSupport.hasEnoughSpace(1_000_000_000L, 1_000_000_000L))
        assertTrue(GgufQuantizeSupport.hasEnoughSpace(1_200_000_000L, 1_000_000_000L))
    }

    @Test
    fun branchyExportPaths() {
        val dir = File("/r/1")
        assertEquals(File("/r/1"), BranchyExportSupport.stageDir(dir, 0))
        assertEquals(File("/r/1/stage3"), BranchyExportSupport.stageDir(dir, 2))
        assertEquals("My-run", BranchyExportSupport.safeName(" My run! "))
        assertEquals("branchy", BranchyExportSupport.safeName("///"))
        assertEquals(File("/r/1/export/My-run-s120-q8_0.gguf"), BranchyExportSupport.outputFile(dir, "My run", 120L, "Q8_0"))
    }

    @Test
    fun uniqueOutputFileNeverReturnsSourceOrExisting() {
        val dir = java.nio.file.Files.createTempDirectory("quant").toFile()
        try {
            val source = File(dir, "x-Q8_0.gguf").apply { writeText("s") }
            // Same name as the source: gets -2.
            assertEquals("x-Q8_0-2.gguf", GgufQuantizeSupport.uniqueOutputFile(dir, "x-Q8_0.gguf", source).name)
            // Case-insensitive collision with an existing export.
            File(dir, "run-q8_0.gguf").writeText("e")
            assertEquals("run-Q8_0-2.gguf", GgufQuantizeSupport.uniqueOutputFile(dir, "run-Q8_0.gguf", source).name)
            // Existing -2 pushes to -3.
            File(dir, "run-Q8_0-2.gguf").writeText("e")
            assertEquals("run-Q8_0-3.gguf", GgufQuantizeSupport.uniqueOutputFile(dir, "run-Q8_0.gguf", source).name)
            // Free name is kept.
            assertEquals("fresh-Q4_0.gguf", GgufQuantizeSupport.uniqueOutputFile(dir, "fresh-Q4_0.gguf", source).name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
