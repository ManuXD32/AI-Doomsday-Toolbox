package com.example.llamadroid.branchy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** One `BRANCHY_EVENT {json}` line printed by the native `branchy` binary. */
sealed class BranchyEvent {
    data class ParamsByStage(
        val trunk: Long = 0L,
        val branches: Long = 0L,
        val merge: Long = 0L,
        val fusion: Long = 0L,
        val mtp: Long = 0L,
        val embd: Long = 0L
    )

    data class Start(
        val params: Long,
        val paramsByStage: ParamsByStage?,
        val tokensPerStep: Long,
        val steps: Long,
        val resumedFrom: Long?
    ) : BranchyEvent()

    data class Step(
        val step: Long,
        val loss: Double,
        val mtpLoss: Double?,
        val lr: Double,
        val gradNorm: Double,
        val tokS: Double,
        val elapsedS: Double,
        val etaS: Double,
        val rssMb: Double,
        /** Cluster runs: milliseconds each helper spent on the last step, keyed by device id. */
        val stepMsByDevice: Map<String, Double> = emptyMap(),
        /** Branch-parallel: compute milliseconds per device of the last step (`compute_ms_by_device`). */
        val computeMsByDevice: Map<String, Double> = emptyMap()
    ) : BranchyEvent()

    data class Eval(val step: Long, val valLoss: Double) : BranchyEvent()
    data class Sample(val step: Long, val text: String) : BranchyEvent()
    /** [reason]: `every`, `manual`, `wait`, `pause`, `end` (null from older binaries). */
    /** A checkpoint write starts (before the slow part): lets the UI show "saving". */
    data class CheckpointBegin(val step: Long, val reason: String? = null) : BranchyEvent()
    data class Checkpoint(val step: Long, val path: String, val reason: String? = null, val tokens: Long = 0L) : BranchyEvent()

    /** [reason] `rebalance` carries the patched cluster file in [cluster]: the app restarts with `--resume --cluster`. */
    data class Paused(val step: Long, val reason: String? = null, val cluster: String? = null) : BranchyEvent()
    data class Done(val step: Long, val path: String) : BranchyEvent()
    data class Error(val message: String) : BranchyEvent()
    data class Progress(val phase: String, val done: Long, val total: Long) : BranchyEvent()
    data class Prepared(val tokens: Long, val trainTokens: Long, val validTokens: Long, val docs: Long) : BranchyEvent()
    data class Bench(val gflops: Double, val threads: Int) : BranchyEvent()
    data class Plan(val plan: BranchyPlan?) : BranchyEvent()

    /** One thread-count measurement of a `bench --sweep` (also used per device of `cluster-bench`). */
    data class BenchPoint(val threads: Int, val gflops: Double)

    /** Live `bench_point` event of the local sweep. */
    data class BenchPointEvent(val point: BenchPoint) : BranchyEvent()

    /** End of the local sweep: [bestThreads] is the smallest count within 5% of the maximum. */
    data class BenchSweep(val bestThreads: Int, val gflops: Double, val results: List<BenchPoint>) : BranchyEvent()

    /** One helper of `cluster-bench`; [error] is set (and the rest empty) when the helper failed. */
    data class BenchDevice(
        val id: String,
        val name: String?,
        val bestThreads: Int,
        val gflops: Double,
        val results: List<BenchPoint>,
        val error: String?
    ) : BranchyEvent()

    data object BenchClusterDone : BranchyEvent()
    data class Token(val text: String) : BranchyEvent()

    /** One entry of the `cluster` event; [up] is false when the native state is `lost`. */
    data class ClusterDevice(val id: String, val host: String, val port: Int, val branches: List<Int>, val up: Boolean)

    data class Cluster(val devices: List<ClusterDevice>, val wire: String?, val pipelineDepth: Int) : BranchyEvent()
    data class WorkerLost(val device: String, val reason: String?, val step: Long) : BranchyEvent()
    data class WorkerJoined(val device: String, val step: Long) : BranchyEvent()
    /**
     * One connection attempt to a lost helper: [phase] `trying`, `failed` (with [reason] and, when the native side
     * will retry on its own, [nextRetrySeconds]) or `joined`; [manual] = started by the user's Retry now.
     */
    data class Rejoin(
        val device: String,
        val phase: String,
        val reason: String?,
        val nextRetrySeconds: Double?,
        val manual: Boolean
    ) : BranchyEvent()
    /** Wait-on-loss: the coordinator keeps the run in RAM and retries the [lost] helpers every [retryMs]. */
    data class ClusterWaiting(
        val lost: List<String>,
        val retryMs: Long,
        /** `join` (initial connect window) or `run` (mid-run loss). */
        val phase: String = "run",
        /** Data-parallel only: the user may continue without the missing helpers. */
        val skippable: Boolean = false
    ) : BranchyEvent()
    data object ClusterResumed : BranchyEvent()
    data class Sync(
        val round: Long,
        val bytes: Long,
        val workers: Int,
        val loss: Double?,
        val syncS: Double?,
        val stepsByDevice: Map<String, Long>
    ) : BranchyEvent()
    /** Microbatch progress inside the optimizer step in progress ([step] = last completed + 1). */
    data class Micro(val step: Long, val done: Long, val total: Long, val tokS: Double, val rssMb: Double) : BranchyEvent()

    /** One replica of a `dp_round`; the coordinator is `main`. */
    data class DpRoundDevice(
        val id: String,
        val steps: Long,
        val tokens: Long,
        val tokS: Double,
        val rssMb: Double,
        val elapsedS: Double,
        /** `active`, `resting`, `lost` or `idle_end` (finished its share, waiting for the others); null = old binary. */
        val state: String? = null,
        val mb: Long = 0L,
        val mbDone: Long = 0L,
        val optSteps: Long = 0L,
        val seconds: Double = 0.0,
        val msPerMb: Double = 0.0,
        val idleS: Double = 0.0,
        val commS: Double = 0.0,
        val downS: Double = 0.0
    )

    data class DpRound(
        val round: Long,
        val devices: List<DpRoundDevice>,
        val roundS: Double = 0.0,
        val syncS: Double = 0.0,
        val globalStep: Long = 0L,
        val gmb: Long = 0L,
        /** `exact` or `local`; null = old binary. */
        val mode: String? = null
    ) : BranchyEvent()

    /** DP step mode change: `exact` (same result as one device) or `local` (local rounds), with the reason and the measured split. */
    data class DpMode(val mode: String, val reason: String?, val computeS: Double, val commS: Double) : BranchyEvent()

    /** Advice about a helper: `not_needed` (suggest resting it) or `ok`; [gainPct]/[costPct] are the estimated effects. */
    data class DpAdvice(val id: String, val verdict: String, val gainPct: Double, val costPct: Double, val rounds: Int) : BranchyEvent()

    data class BpPlacementAdvice(val id: String, val branches: List<Int>)

    /** Branch-parallel rebalance suggestion made at a checkpoint (`verdict` = `rebalance`). */
    data class BpAdvice(
        val verdict: String,
        val step: Long,
        val gainPct: Double,
        val currentMs: Map<String, Double>,
        val proposedMs: Map<String, Double>,
        val placement: List<BpPlacementAdvice>,
        val applicable: Boolean,
        val reason: String?
    ) : BranchyEvent()

    /** A helper was reached on another host than last time. */
    data class Endpoint(val id: String, val host: String, val previous: String?) : BranchyEvent()

    data class ControlIgnored(val target: String, val reason: String)

    /** Native acknowledgement of one `control.json` sequence number. */
    data class ControlAck(val seq: Long, val applied: List<String>, val ignored: List<ControlIgnored>) : BranchyEvent()

    /** `branchy consolidate` progress while one remote unit is fetched. */
    data class Consolidate(val device: String, val unit: String, val doneBytes: Long, val totalBytes: Long) : BranchyEvent()
    data class ConsolidateDone(val step: Long) : BranchyEvent()
    data class DpStart(val replicas: Int, val bits: Int, val syncEvery: Int, val params: Long) : BranchyEvent()
    /** DP worker setup progress (`phase` is `shards` or `start`); sizes are bytes. */
    data class DpSetup(val phase: String, val device: String, val doneBytes: Long, val totalBytes: Long) : BranchyEvent()
    data class Unknown(val type: String, val raw: JsonObject) : BranchyEvent()
}

/** Parses stdout lines of the native binary. Anything that is not a well-formed event returns null. */
object BranchyEventParser {
    const val PREFIX = "BRANCHY_EVENT "

    fun parse(line: String): BranchyEvent? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith(PREFIX)) return null
        val obj = try {
            BranchyJson.json.parseToJsonElement(trimmed.substring(PREFIX.length).trim()) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val type = obj.str("type") ?: obj.str("event") ?: return null
        return when (type) {
            "start" -> BranchyEvent.Start(
                params = obj.long("params") ?: 0L,
                paramsByStage = (obj["params_by_stage"] as? JsonObject)?.let { p ->
                    BranchyEvent.ParamsByStage(
                        trunk = p.long("trunk") ?: 0L, branches = p.long("branches") ?: 0L,
                        merge = p.long("merge") ?: 0L, fusion = p.long("fusion") ?: 0L,
                        mtp = p.long("mtp") ?: 0L, embd = p.long("embd") ?: 0L
                    )
                },
                tokensPerStep = obj.long("tokens_per_step") ?: 0L,
                steps = obj.long("steps") ?: 0L,
                resumedFrom = obj.long("resumed_step") ?: obj.long("resumed_from")
            )
            "step" -> BranchyEvent.Step(
                step = obj.long("step") ?: 0L,
                loss = obj.double("loss") ?: Double.NaN,
                mtpLoss = obj.double("mtp_loss"),
                lr = obj.double("lr") ?: 0.0,
                gradNorm = obj.double("grad_norm") ?: 0.0,
                tokS = obj.double("tok_s") ?: 0.0,
                elapsedS = obj.double("elapsed_s") ?: 0.0,
                etaS = obj.double("eta_s") ?: 0.0,
                rssMb = obj.double("rss_mb") ?: 0.0,
                stepMsByDevice = obj.doubleMap("step_ms_by_device"),
                computeMsByDevice = obj.doubleMap("compute_ms_by_device")
            )
            "eval" -> BranchyEvent.Eval(obj.long("step") ?: 0L, obj.double("val_loss") ?: Double.NaN)
            "sample" -> BranchyEvent.Sample(obj.long("step") ?: 0L, obj.str("text").orEmpty())
            "checkpoint_begin" -> BranchyEvent.CheckpointBegin(obj.long("step") ?: 0L, obj.str("reason"))
            "checkpoint" -> BranchyEvent.Checkpoint(
                obj.long("step") ?: 0L, obj.str("path").orEmpty(), obj.str("reason"), obj.long("tokens") ?: 0L
            )
            "paused" -> BranchyEvent.Paused(obj.long("step") ?: 0L, obj.str("reason"), obj.str("cluster"))
            "done" -> BranchyEvent.Done(obj.long("step") ?: 0L, obj.str("path").orEmpty())
            "error" -> BranchyEvent.Error(obj.str("message").orEmpty())
            "progress" -> BranchyEvent.Progress(
                obj.str("phase").orEmpty(), obj.long("done") ?: 0L, obj.long("total") ?: 0L
            )
            "prepared" -> BranchyEvent.Prepared(
                obj.long("tokens") ?: 0L, obj.long("train_tokens") ?: 0L,
                obj.long("valid_tokens") ?: 0L, obj.long("docs") ?: 0L
            )
            "bench" -> BranchyEvent.Bench(obj.double("gflops") ?: 0.0, obj.long("threads")?.toInt() ?: 0)
            "bench_point" -> BranchyEvent.BenchPointEvent(
                BranchyEvent.BenchPoint(obj.long("threads")?.toInt() ?: 0, obj.double("gflops") ?: 0.0)
            )
            "bench_sweep" -> BranchyEvent.BenchSweep(
                obj.long("best_threads")?.toInt() ?: 0, obj.double("gflops") ?: 0.0, obj.benchResults()
            )
            "bench_device" -> BranchyEvent.BenchDevice(
                id = obj.str("id").orEmpty(), name = obj.str("name"),
                bestThreads = obj.long("best_threads")?.toInt() ?: 0, gflops = obj.double("gflops") ?: 0.0,
                results = obj.benchResults(), error = obj.str("error")
            )
            "bench_cluster_done" -> BranchyEvent.BenchClusterDone
            "plan" -> BranchyEvent.Plan(
                try {
                    BranchyJson.json.decodeFromJsonElement(BranchyPlan.serializer(), obj)
                } catch (_: Exception) {
                    null
                }
            )
            "cluster" -> BranchyEvent.Cluster(
                devices = (obj["devices"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val d = e as? JsonObject ?: return@mapNotNull null
                    val id = d.str("id") ?: return@mapNotNull null
                    BranchyEvent.ClusterDevice(
                        id = id, host = d.str("host").orEmpty(), port = d.long("port")?.toInt() ?: 0,
                        branches = d.longList("branches").map { it.toInt() }, up = d.str("state") != "lost"
                    )
                },
                wire = obj.str("wire"),
                pipelineDepth = obj.long("pipeline_depth")?.toInt() ?: 1
            )
            "worker_lost" -> BranchyEvent.WorkerLost(
                obj.str("id") ?: obj.str("device").orEmpty(), obj.str("reason"), obj.long("step") ?: 0L
            )
            "worker_joined" -> BranchyEvent.WorkerJoined(obj.str("id") ?: obj.str("device").orEmpty(), obj.long("step") ?: 0L)
            "rejoin" -> BranchyEvent.Rejoin(
                device = obj.str("id") ?: obj.str("device").orEmpty(),
                phase = obj.str("phase").orEmpty(),
                reason = obj.str("reason"),
                nextRetrySeconds = obj.double("next_retry_s"),
                manual = obj.prim("manual")?.booleanOrNull ?: false
            )
            "cluster_waiting" -> BranchyEvent.ClusterWaiting(
                lost = (obj["lost"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                retryMs = obj.long("retry_ms") ?: 0L,
                phase = obj.str("phase") ?: "run",
                skippable = obj.prim("skippable")?.booleanOrNull ?: false
            )
            "micro" -> BranchyEvent.Micro(
                step = obj.long("step") ?: 0L, done = obj.long("done") ?: 0L, total = obj.long("total") ?: 0L,
                tokS = obj.double("tok_s") ?: 0.0, rssMb = obj.double("rss_mb") ?: 0.0
            )
            "dp_round" -> BranchyEvent.DpRound(
                round = obj.long("round") ?: 0L,
                devices = (obj["devices"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val d = e as? JsonObject ?: return@mapNotNull null
                    val id = d.str("id") ?: return@mapNotNull null
                    val optSteps = d.long("opt_steps") ?: d.long("steps") ?: 0L
                    val seconds = d.double("seconds") ?: d.double("elapsed_s") ?: 0.0
                    BranchyEvent.DpRoundDevice(
                        id = id, steps = optSteps, tokens = d.long("tokens") ?: 0L,
                        tokS = d.double("tok_s") ?: 0.0, rssMb = d.double("rss_mb") ?: 0.0,
                        elapsedS = seconds,
                        state = d.str("state"), mb = d.long("mb") ?: 0L, mbDone = d.long("mb_done") ?: 0L,
                        optSteps = optSteps, seconds = seconds, msPerMb = d.double("ms_per_mb") ?: 0.0,
                        idleS = d.double("idle_s") ?: 0.0, commS = d.double("comm_s") ?: 0.0,
                        downS = d.double("down_s") ?: 0.0
                    )
                },
                roundS = obj.double("round_s") ?: 0.0, syncS = obj.double("sync_s") ?: 0.0,
                globalStep = obj.long("global_step") ?: 0L, gmb = obj.long("gmb") ?: 0L,
                mode = obj.str("mode")
            )
            "dp_mode" -> BranchyEvent.DpMode(
                mode = obj.str("mode").orEmpty(), reason = obj.str("reason"),
                computeS = obj.double("compute_s") ?: 0.0, commS = obj.double("comm_s") ?: 0.0
            )
            "dp_advice" -> BranchyEvent.DpAdvice(
                id = obj.str("id").orEmpty(), verdict = obj.str("verdict").orEmpty(),
                gainPct = obj.double("gain_pct") ?: 0.0, costPct = obj.double("cost_pct") ?: 0.0,
                rounds = obj.long("rounds")?.toInt() ?: 0
            )
            "bp_advice" -> BranchyEvent.BpAdvice(
                verdict = obj.str("verdict").orEmpty(), step = obj.long("step") ?: 0L,
                gainPct = obj.double("gain_pct") ?: 0.0,
                currentMs = obj.doubleMap("current_ms"), proposedMs = obj.doubleMap("proposed_ms"),
                placement = (obj["placement"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val d = e as? JsonObject ?: return@mapNotNull null
                    val id = d.str("id") ?: return@mapNotNull null
                    BranchyEvent.BpPlacementAdvice(id, d.longList("branches").map { it.toInt() })
                },
                applicable = obj.prim("applicable")?.booleanOrNull ?: false,
                reason = obj.str("reason")
            )
            "endpoint" -> BranchyEvent.Endpoint(
                id = obj.str("id").orEmpty(), host = obj.str("host").orEmpty(), previous = obj.str("previous")
            )
            "control_ack" -> BranchyEvent.ControlAck(
                seq = obj.long("seq") ?: 0L,
                applied = (obj["applied"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                ignored = (obj["ignored"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val d = e as? JsonObject ?: return@mapNotNull null
                    val target = d.str("id") ?: d.str("cmd") ?: return@mapNotNull null
                    BranchyEvent.ControlIgnored(target, d.str("reason").orEmpty())
                }
            )
            "consolidate" -> BranchyEvent.Consolidate(
                device = obj.str("device") ?: obj.str("id").orEmpty(), unit = obj.str("unit").orEmpty(),
                doneBytes = obj.long("done_bytes") ?: 0L, totalBytes = obj.long("total_bytes") ?: 0L
            )
            "consolidate_done" -> BranchyEvent.ConsolidateDone(obj.long("step") ?: 0L)
            "cluster_resumed" -> BranchyEvent.ClusterResumed
            "sync" -> BranchyEvent.Sync(
                round = obj.long("round") ?: 0L,
                bytes = obj.long("bytes") ?: 0L,
                workers = obj.long("workers")?.toInt() ?: 0,
                loss = obj.double("loss"),
                syncS = obj.double("sync_s"),
                stepsByDevice = (obj["steps"] as? JsonObject)?.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.let { k to it.toLong() }
                }?.toMap().orEmpty()
            )
            "dp_start" -> BranchyEvent.DpStart(
                replicas = obj.long("replicas")?.toInt() ?: 0, bits = obj.long("bits")?.toInt() ?: 0,
                syncEvery = obj.long("sync_every")?.toInt() ?: 0, params = obj.long("params") ?: 0L
            )
            "dp_setup" -> BranchyEvent.DpSetup(
                phase = obj.str("phase").orEmpty(), device = obj.str("device") ?: obj.str("id").orEmpty(),
                doneBytes = obj.long("done_bytes") ?: 0L, totalBytes = obj.long("total_bytes") ?: 0L
            )
            "token" -> BranchyEvent.Token(obj.str("text").orEmpty())
            else -> BranchyEvent.Unknown(type, obj)
        }
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? {
        val e: JsonElement = this[key] ?: return null
        if (e is JsonNull) return null
        return e as? JsonPrimitive
    }

    private fun JsonObject.benchResults(): List<BranchyEvent.BenchPoint> =
        (this["results"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val t = o.long("threads")?.toInt() ?: return@mapNotNull null
            val g = o.double("gflops")?.takeIf { it.isFinite() } ?: return@mapNotNull null
            BranchyEvent.BenchPoint(t, g)
        }

    private fun JsonObject.doubleMap(key: String): Map<String, Double> =
        (this[key] as? JsonObject)?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.let { k to it }
        }?.toMap().orEmpty()

    private fun JsonObject.longList(key: String): List<Long> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toLong() }

    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull

    private fun JsonObject.double(key: String): Double? = prim(key)?.doubleOrNull

    /** Integers may be printed as 5 or 5.0 by the native side. */
    private fun JsonObject.long(key: String): Long? = double(key)?.takeIf { it.isFinite() }?.toLong()
}
