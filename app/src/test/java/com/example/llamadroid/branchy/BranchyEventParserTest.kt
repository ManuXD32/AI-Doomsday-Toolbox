package com.example.llamadroid.branchy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchyEventParserTest {
    private fun ev(json: String) = BranchyEventParser.parse("BRANCHY_EVENT $json")

    @Test
    fun startEvent() {
        val e = ev(
            """{"type":"start","params":30123456,"params_by_stage":{"trunk":1,"branches":2,"merge":3,"fusion":4,"mtp":0,"embd":5},""" +
                """"tokens_per_step":32768,"steps":9000,"resumed_from":null}"""
        ) as BranchyEvent.Start
        assertEquals(30123456L, e.params)
        assertEquals(5L, e.paramsByStage?.embd)
        assertEquals(32768L, e.tokensPerStep)
        assertEquals(9000L, e.steps)
        assertNull(e.resumedFrom)
        val resumed = ev("""{"type":"start","params":1,"tokens_per_step":2,"steps":3,"resumed_from":120}""") as BranchyEvent.Start
        assertEquals(120L, resumed.resumedFrom)
        assertNull(resumed.paramsByStage)
    }

    @Test
    fun stepEventWithAndWithoutMtp() {
        val e = ev(
            """{"type":"step","step":12,"loss":3.5,"lr":0.0006,"grad_norm":0.9,"tok_s":410.5,"elapsed_s":60,"eta_s":3600.5,"rss_mb":812}"""
        ) as BranchyEvent.Step
        assertEquals(12L, e.step)
        assertEquals(3.5, e.loss, 1e-9)
        assertNull(e.mtpLoss)
        assertEquals(410.5, e.tokS, 1e-9)
        assertEquals(3600.5, e.etaS, 1e-9)
        val m = ev("""{"type":"step","step":1,"loss":1.0,"mtp_loss":2.5}""") as BranchyEvent.Step
        assertEquals(2.5, m.mtpLoss!!, 1e-9)
        assertEquals(0.0, m.lr, 0.0)
    }

    @Test
    fun checkpointBeginIsParsed() {
        assertEquals(BranchyEvent.CheckpointBegin(12, "manual"), ev("""{"type":"checkpoint_begin","step":12,"reason":"manual"}"""))
        assertEquals(BranchyEvent.CheckpointBegin(3, null), ev("""{"type":"checkpoint_begin","step":3}"""))
    }

    @Test
    fun smallEvents() {
        assertEquals(BranchyEvent.Eval(250, 2.75), ev("""{"type":"eval","step":250,"val_loss":2.75}"""))
        assertEquals(BranchyEvent.Sample(10, "Hola \"mundo\""), ev("""{"type":"sample","step":10,"text":"Hola \"mundo\""}"""))
        assertEquals(BranchyEvent.Checkpoint(250, "checkpoints/step_250.bckpt"), ev("""{"type":"checkpoint","step":250,"path":"checkpoints/step_250.bckpt"}"""))
        assertEquals(BranchyEvent.Paused(77), ev("""{"type":"paused","step":77}"""))
        assertEquals(BranchyEvent.Done(9000, "c/step_9000.bckpt"), ev("""{"type":"done","step":9000,"path":"c/step_9000.bckpt"}"""))
        assertEquals(BranchyEvent.Error("boom"), ev("""{"type":"error","message":"boom"}"""))
        assertEquals(BranchyEvent.Progress("tokenize", 5, 10), ev("""{"type":"progress","phase":"tokenize","done":5,"total":10}"""))
        assertEquals(BranchyEvent.Prepared(100, 90, 10, 7), ev("""{"type":"prepared","tokens":100,"train_tokens":90,"valid_tokens":10,"docs":7}"""))
        assertEquals(BranchyEvent.Bench(41.5, 4), ev("""{"type":"bench","gflops":41.5,"threads":4}"""))
        assertEquals(BranchyEvent.Token("hi"), ev("""{"type":"token","text":"hi"}"""))
    }

    @Test
    fun planEventDecodesLayoutAndTrain() {
        val e = ev(
            """{"type":"plan","fits":true,"params":30100000,"layout":{"vocab":8192,"d_model":384,"n_head":6,"n_head_kv":6,"branches":4,"branch_width":192,"future_field":1},""" +
                """"train":{"microbatch":2,"grad_accum":16,"recompute":"branches","optimizer":"adamw8","weight_storage":"ram","master_fp32":false,"checkpoint_every":200,"lr":0.0008,"warmup":100,"steps":5000,"grad_mode":"fused"},""" +
                """"per_device":[{"id":"local","peak_mb":3000,"budget_mb":4200}],"tok_s":120.5,"eta_s":86400,"warnings":["slow"],"extra":true}"""
        ) as BranchyEvent.Plan
        val plan = e.plan!!
        assertTrue(plan.fits)
        assertEquals(384, plan.layout.dModel)
        assertEquals(64, plan.layout.headDim)
        assertEquals("fused", plan.train.gradMode)
        assertEquals(16, plan.train.gradAccum)
        assertEquals("slow", plan.warnings.single())
        assertEquals(4200.0, plan.perDevice.single().budgetMb, 0.0)
        assertNull(plan.needsMoreRamMb)
    }

    @Test
    fun unknownAndNonEventLines() {
        val u = ev("""{"type":"future_thing","x":1}""") as BranchyEvent.Unknown
        assertEquals("future_thing", u.type)
        assertNotNull(u.raw["x"])
        assertNull(BranchyEventParser.parse(""))
        assertNull(BranchyEventParser.parse("loading model..."))
        assertNull(BranchyEventParser.parse("""{"type":"step"}"""))
        assertNull(BranchyEventParser.parse("BRANCHY_EVENT"))
        assertNull(ev("not json"))
        assertNull(ev("""{"step":1}"""))
        assertNull(ev("""[1,2]"""))
        assertNull(ev("""{"type":"step","step":"""))
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val e = ev("""{"type":"step"}""") as BranchyEvent.Step
        assertEquals(0L, e.step)
        assertTrue(e.loss.isNaN())
        assertEquals(BranchyEvent.Paused(0), ev("""{"type":"paused"}"""))
        assertEquals(BranchyEvent.Error(""), ev("""{"type":"error","message":null}"""))
    }

    @Test
    fun integersMayBePrintedAsFloats() {
        assertEquals(BranchyEvent.Paused(42), ev("""{"type":"paused","step":42.0}"""))
    }

    @Test
    fun benchSweepEvents() {
        val point = ev("""{"type":"bench_point","threads":4,"gflops":31.5}""") as BranchyEvent.BenchPointEvent
        assertEquals(BranchyEvent.BenchPoint(4, 31.5), point.point)
        val sweep = ev(
            """{"event":"bench_sweep","best_threads":4,"gflops":31.5,"results":[{"threads":2,"gflops":18.0},{"threads":4,"gflops":31.5},{"threads":"x"}]}"""
        ) as BranchyEvent.BenchSweep
        assertEquals(4, sweep.bestThreads)
        assertEquals(listOf(BranchyEvent.BenchPoint(2, 18.0), BranchyEvent.BenchPoint(4, 31.5)), sweep.results)
    }

    @Test
    fun benchClusterEvents() {
        val ok = ev(
            """{"type":"bench_device","id":"h1","name":"Tab","best_threads":6,"gflops":40.0,"results":[{"threads":6,"gflops":40.0}]}"""
        ) as BranchyEvent.BenchDevice
        assertEquals("h1", ok.id)
        assertEquals("Tab", ok.name)
        assertEquals(6, ok.bestThreads)
        assertNull(ok.error)
        val bad = ev("""{"type":"bench_device","id":"h2","error":"auth"}""") as BranchyEvent.BenchDevice
        assertEquals("auth", bad.error)
        assertTrue(bad.results.isEmpty())
        assertEquals(BranchyEvent.BenchClusterDone, ev("""{"type":"bench_cluster_done"}"""))
    }

    @Test
    fun clusterWaitingAndResumedParse() {
        val w = BranchyEventParser.parse("""BRANCHY_EVENT {"type":"cluster_waiting","lost":["a","b"],"retry_ms":2000}""")
        assertEquals(BranchyEvent.ClusterWaiting(listOf("a", "b"), 2000L), w)
        assertEquals(BranchyEvent.ClusterResumed, BranchyEventParser.parse("""BRANCHY_EVENT {"event":"cluster_resumed"}"""))
    }

    @Test
    fun dpModeAndRoundModeParse() {
        val m = ev("""{"type":"dp_mode","mode":"local","reason":"network took 25 % of the step","compute_s":3.0,"comm_s":1.0}""")
        assertEquals(BranchyEvent.DpMode("local", "network took 25 % of the step", 3.0, 1.0), m)
        val r = ev("""{"type":"dp_round","round":4,"mode":"exact","devices":[]}""") as BranchyEvent.DpRound
        assertEquals("exact", r.mode)
        assertNull((ev("""{"type":"dp_round","round":5,"devices":[]}""") as BranchyEvent.DpRound).mode)
    }

    @Test
    fun rejoinEventsParse() {
        val f = ev("""{"type":"rejoin","id":"w0","phase":"failed","reason":"cannot connect","next_retry_s":4.5,"manual":false}""")
        assertEquals(BranchyEvent.Rejoin("w0", "failed", "cannot connect", 4.5, false), f)
        val t = ev("""{"type":"rejoin","id":"w0","phase":"trying","manual":true}""")
        assertEquals(BranchyEvent.Rejoin("w0", "trying", null, null, true), t)
        assertEquals(BranchyEvent.Rejoin("w1", "joined", null, null, false), ev("""{"type":"rejoin","id":"w1","phase":"joined"}"""))
    }
}
