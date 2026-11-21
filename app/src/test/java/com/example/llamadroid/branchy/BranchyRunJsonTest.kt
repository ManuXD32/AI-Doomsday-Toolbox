package com.example.llamadroid.branchy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchyRunJsonTest {
    private val plan = BranchyPlan(
        fits = true,
        layout = BranchyModelLayout(vocab = 8192, context = 512, dModel = 384, nHead = 6, nHeadKv = 3, branchWidth = 192),
        train = BranchyPlanTrain(microbatch = 2, gradAccum = 16, lr = 6e-4, warmup = 200, steps = 5000, optimizer = "adamw8", gradMode = "fused")
    )
    private val sources = listOf(RunSource("data/1.btok", 0.7), RunSource("data/2-0.btok", 0.3))

    private fun build(steps: Long? = null, lrScale: Double = 1.0, overrides: Map<String, String> = emptyMap()): JsonObject =
        Json.parseToJsonElement(
            BranchyRunJson.toJsonString(
                BranchyRunJson.build("demo", 42, 3, plan, "q4", "tokenizer.json", sources, steps, lrScale, overrides)
            )
        ).jsonObject

    @Test
    fun writerMatchesSpecKeys() {
        val root = build()
        assertEquals(setOf("name", "seed", "threads", "model", "train", "data"), root.keys)
        assertEquals("demo", root["name"]!!.jsonPrimitive.content)
        assertEquals(42, root["seed"]!!.jsonPrimitive.int)
        assertEquals(3, root["threads"]!!.jsonPrimitive.int)
        val model = root["model"]!!.jsonObject
        assertEquals(
            setOf(
                "vocab", "context", "d_model", "head_dim", "n_head", "n_head_kv", "trunk_blocks", "branches", "branch_blocks",
                "branch_width", "fusion_blocks", "ffn_mult", "qk_norm", "tie_embeddings", "rope_theta", "delay",
                "local_branches", "drop_p", "mtp", "mtp_weight"
            ),
            model.keys
        )
        assertEquals(8192, model["vocab"]!!.jsonPrimitive.int)
        assertEquals(3, model["n_head_kv"]!!.jsonPrimitive.int)
        assertEquals(192, model["branch_width"]!!.jsonPrimitive.int)
        assertTrue(model["qk_norm"]!!.jsonPrimitive.boolean)
        assertEquals(10000.0, model["rope_theta"]!!.jsonPrimitive.double, 0.0)
        val train = root["train"]!!.jsonObject
        assertEquals(
            setOf(
                "weight_mode", "master_fp32", "optimizer", "lr", "min_lr_ratio", "warmup", "steps", "microbatch", "grad_accum",
                "grad_mode", "weight_decay", "grad_clip", "recompute", "eval_every", "eval_batches", "checkpoint_every",
                "keep_checkpoints", "sample_every", "sample_prompt", "weight_storage"
            ),
            train.keys
        )
        assertEquals("q4", train["weight_mode"]!!.jsonPrimitive.content)
        assertEquals("adamw8", train["optimizer"]!!.jsonPrimitive.content)
        assertEquals("fused", train["grad_mode"]!!.jsonPrimitive.content)
        assertEquals(5000L, train["steps"]!!.jsonPrimitive.long)
        assertEquals(6e-4, train["lr"]!!.jsonPrimitive.double, 1e-12)
        val data = root["data"]!!.jsonObject
        assertEquals("tokenizer.json", data["tokenizer"]!!.jsonPrimitive.content)
        val srcs: JsonArray = data["sources"]!!.jsonArray
        assertEquals(2, srcs.size)
        assertEquals("data/2-0.btok", srcs[1].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(0.7, srcs[0].jsonObject["weight"]!!.jsonPrimitive.double, 1e-12)
    }

    @Test
    fun stageStepsAndLrScaleApply() {
        val train = build(steps = 800L, lrScale = 0.3)["train"]!!.jsonObject
        assertEquals(800L, train["steps"]!!.jsonPrimitive.long)
        assertEquals(1.8e-4, train["lr"]!!.jsonPrimitive.double, 1e-12)
        // eval/checkpoint cadence and warmup never exceed a short stage.
        assertEquals(250L, train["eval_every"]!!.jsonPrimitive.long)
        val tiny = build(steps = 100L)["train"]!!.jsonObject
        assertEquals(100L, tiny["eval_every"]!!.jsonPrimitive.long)
        assertEquals(100L, tiny["checkpoint_every"]!!.jsonPrimitive.long)
        assertEquals(50L, tiny["warmup"]!!.jsonPrimitive.long)
    }

    @Test
    fun overridesKeepTheExistingValueType() {
        val root = build(
            overrides = mapOf(
                "train.lr" to "0.001", "train.microbatch" to "8", "train.master_fp32" to "false",
                "train.optimizer" to "factored", "model.drop_p" to "0.2", "train.steps" to "123",
                "bogus.key" to "1", "train.lr.extra" to "1", "train.master_fp32x" to "true", "train.grad_clip" to "abc"
            )
        )
        val train = root["train"]!!.jsonObject
        assertEquals(0.001, train["lr"]!!.jsonPrimitive.double, 0.0)
        assertEquals(8, train["microbatch"]!!.jsonPrimitive.int)
        assertEquals(false, train["master_fp32"]!!.jsonPrimitive.boolean)
        assertEquals("factored", train["optimizer"]!!.jsonPrimitive.content)
        assertTrue((train["optimizer"] as JsonPrimitive).isString)
        assertEquals(123L, train["steps"]!!.jsonPrimitive.long)
        assertEquals(0.2, root["model"]!!.jsonObject["drop_p"]!!.jsonPrimitive.double, 0.0)
        // Invalid numbers for a numeric field are ignored; unknown sections are skipped; unknown keys are added.
        assertEquals(1.0, train["grad_clip"]!!.jsonPrimitive.double, 0.0)
        assertNull(root["bogus"])
        assertNotNull(train["master_fp32x"])
    }

    @Test
    fun stepsForTokens() {
        assertEquals(916L, BranchyRunJson.stepsForTokens(3e7, 2, 16, 1024))
        assertEquals(1L, BranchyRunJson.stepsForTokens(10.0, 4, 8, 1024))
        assertEquals(1L, BranchyRunJson.stepsForTokens(0.0, 4, 8, 1024))
        assertEquals(1L, BranchyRunJson.stepsForTokens(1e6, 0, 8, 1024))
    }

    @Test
    fun configRoundTripsThroughJson() {
        val cfg = BranchyRunConfig(
            mix = listOf(MixEntry(3L, 1.0)), vocab = 8192,
            planInput = BranchyPlanInput(targetParams = 30e6, devices = listOf(BranchyDevice("local", 6000, 20000, 40.0, 4))),
            plan = plan, guards = TrainingGuards(25, true, 40), autoResume = true, recipeId = "mini_assistant_30m",
            stages = listOf(RunStage("pre", listOf(MixEntry(1L, 0.5), MixEntry(2L, 0.5)), 2.5e8, 1.0), RunStage("sft", emptyList(), 4.5e7, 0.3)),
            currentStage = 1, overrides = mapOf("train.lr" to "0.001")
        )
        val back = BranchyRunConfig.decode(cfg.encode())
        assertEquals(cfg, back)
        assertEquals(2, back!!.effectiveStages().size)
        assertEquals(1, BranchyRunConfig(mix = listOf(MixEntry(1L, 1.0))).effectiveStages().size)
        assertNull(BranchyRunConfig.decode("not json"))
        // An older/partial config still decodes with defaults.
        assertEquals(20, BranchyRunConfig.decode("""{"vocab":8192}""")!!.guards.pauseBelowBatteryPct)
    }
}
