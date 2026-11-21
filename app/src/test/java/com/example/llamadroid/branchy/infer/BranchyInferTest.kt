package com.example.llamadroid.branchy.infer

import com.example.llamadroid.branchy.BranchyCommandBuilder
import com.example.llamadroid.branchy.BranchyPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchyInferTest {
    private fun helper(id: String, gflops: Double?) = InferHelper(id, id, "10.0.0.1", 50210, 50213, gflops)

    @Test
    fun layoutParsing() {
        val l = BranchyPlacement.parseLayout("""{"model":{"branches":4,"local_branches":1,"context":1024}}""")
        assertEquals(InferLayout(4, 1, 1024), l)
        assertNull(BranchyPlacement.parseLayout("{}"))
        assertNull(BranchyPlacement.parseLayout("not json"))
        assertNull(BranchyPlacement.parseLayout(null))
    }

    @Test
    fun autoKeepsBranchZeroLocalAndRoundRobinsEqualSpeeds() {
        val p = BranchyPlacement.auto(InferLayout(5), listOf(helper("a", 10.0), helper("b", 10.0)))
        assertFalse(0 in p)
        assertEquals(mapOf(1 to "a", 2 to "b", 3 to "a", 4 to "b"), p)
    }

    @Test
    fun autoGivesFasterHelperMoreBranches() {
        val p = BranchyPlacement.auto(InferLayout(5), listOf(helper("slow", 5.0), helper("fast", 20.0)))
        val slices = BranchyPlacement.slices(p)
        assertTrue(slices.getValue("fast").size > slices.getValue("slow").size)
        assertEquals(4, p.size)
    }

    @Test
    fun placementEditAndValidation() {
        val layout = InferLayout(3)
        var p = BranchyPlacement.auto(layout, listOf(helper("a", null)))
        assertNull(BranchyPlacement.validate(layout, p, 1))
        p = BranchyPlacement.assign(p, 1, null).let { BranchyPlacement.assign(it, 2, null) }
        assertEquals(BranchyPlacement.Problem.NO_REMOTE_BRANCH, BranchyPlacement.validate(layout, p, 1))
        p = (0 until 3).fold(emptyMap()) { m, b -> BranchyPlacement.assign(m, b, "a") }
        assertEquals(BranchyPlacement.Problem.NO_LOCAL_BRANCH, BranchyPlacement.validate(layout, p, 1))
        assertEquals(BranchyPlacement.Problem.NO_HELPER, BranchyPlacement.validate(layout, emptyMap(), 0))
        assertEquals(mapOf(1 to "a"), BranchyPlacement.prune(layout, mapOf(1 to "a", 2 to "gone", 7 to "a"), setOf("a")))
    }

    @Test
    fun sliceNamesEncodeBranches() {
        assertEquals("run-ck.bin-q8_0-b1_2.slice.gguf", BranchyPlacement.sliceFileName("run", "ck.bin", "Q8_0", listOf(1, 2)))
        assertTrue(BranchyPlacement.mainFileName("a b", "c", "f16").endsWith("-main.gguf"))
    }

    @Test
    fun sliceExportAndPushArgv() {
        assertEquals(
            listOf("/e", "export-gguf", "--ckpt", "/c", "--out", "/o", "--type", "q8_0", "--slice-branches", "1,2"),
            BranchyCommandBuilder.exportGguf("/e", "/c", null, "/o", "q8_0", listOf(1, 2))
        )
        // The full export is unchanged.
        assertEquals(
            listOf("/e", "export-gguf", "--ckpt", "/c", "--tokenizer", "/t", "--out", "/o", "--type", "auto"),
            BranchyCommandBuilder.exportGguf("/e", "/c", "/t", "/o")
        )
        assertEquals(
            listOf("/e", "push", "--to", "h:1", "--token-file", "/tok", "--file", "/f", "--name", "n.slice.gguf"),
            BranchyCommandBuilder.push("/e", "h:1", "/tok", "/f", "n.slice.gguf")
        )
    }

    @Test
    fun serverArgsCarryMandatoryFlagsAndNoToken() {
        val a = BranchyInferArgs.serverArgs(listOf("h1:5", "h2:6"), "/tok")
        assertEquals(
            listOf("--branchy-helper", "h1:5,h2:6", "--branchy-token-file", "/tok", "-ngl", "0", "-np", "1", "--poll", "0"), a
        )
        assertTrue(BranchyInferArgs.serverArgs(listOf("h:1"), "/t", "bf16").containsAll(listOf("--branchy-wire", "bf16")))
    }

    @Test
    fun slowLinkThreshold() {
        assertFalse(BranchyInferArgs.isSlowLink(null))
        assertFalse(BranchyInferArgs.isSlowLink(79))
        assertTrue(BranchyInferArgs.isSlowLink(80))
    }

    @Test
    fun pushIsBlockedForHelpersInALiveTrainingRun() {
        val ids = listOf("a", "b")
        assertEquals(listOf("a"), BranchyInferArgs.pushBlockedHelpers(ids, BranchyPhase.TRAIN, listOf("a", "x")))
        assertEquals(listOf("b"), BranchyInferArgs.pushBlockedHelpers(ids, BranchyPhase.PAUSED, listOf("b")))
        assertTrue(BranchyInferArgs.pushBlockedHelpers(ids, BranchyPhase.DONE, listOf("a")).isEmpty())
        assertTrue(BranchyInferArgs.pushBlockedHelpers(ids, BranchyPhase.IDLE, listOf("a")).isEmpty())
        assertTrue(BranchyInferArgs.pushBlockedHelpers(ids, BranchyPhase.TRAIN, listOf("z")).isEmpty())
    }

    @Test
    fun statusParserTracksDegradedAndRecovery() {
        val start = BranchyInferStatusParser.connected(listOf("h:1", "g:2"))
        val down = BranchyInferStatusParser.apply(
            start, "srv: branchy-remote: helper h:1 is down (timeout): its branches contribute zeros (degraded) until it reconnects"
        )
        assertEquals(HelperLink.DEGRADED, down.links["h:1"])
        assertEquals(HelperLink.CONNECTED, down.links["g:2"])
        val resync = BranchyInferStatusParser.apply(down, "branchy-remote: helper h:1 reconnected, resynchronising")
        assertEquals(HelperLink.RESYNC, resync.links["h:1"])
        val back = BranchyInferStatusParser.apply(resync, "branchy-remote: helper h:1 is back in sync")
        assertEquals(HelperLink.CONNECTED, back.links["h:1"])
    }

    @Test
    fun statusParserReadsGenerationSpeedOnly() {
        val s = InferLiveStatus()
        val prompt = BranchyInferStatusParser.apply(
            s, "prompt eval time =  100.00 ms /  50 tokens (  2.00 ms per token,   500.00 tokens per second)"
        )
        assertNull(prompt.tokensPerSecond)
        val gen = BranchyInferStatusParser.apply(
            s, "       eval time =    1234.56 ms /    20 tokens (   61.73 ms per token,    16.20 tokens per second)"
        )
        assertEquals(16.2, gen.tokensPerSecond!!, 1e-9)
        assertEquals(s, BranchyInferStatusParser.apply(s, "unrelated line"))
    }
}
