package com.example.llamadroid.branchy

import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import com.example.llamadroid.branchy.cluster.BranchyHelperProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import kotlin.math.ceil

/** Pure helpers of the run length ("epochs of the selected data, capped at the plan target"). */
object BranchyEpochs {
    /**
     * Steps of one stage: `min(planned, ceil(epochs * trainTokens / tokensPerStep))`, at least 1. [planned] is returned
     * unchanged when [epochs] is 0 (no cap) or the token counts are unknown.
     */
    fun steps(planned: Long, epochs: Double, trainTokens: Long, tokensPerStep: Long): Long {
        val base = planned.coerceAtLeast(1L)
        if (!epochs.isFinite() || epochs <= 0.0 || trainTokens <= 0L || tokensPerStep <= 0L) return base
        val cap = ceil(epochs * trainTokens.toDouble() / tokensPerStep.toDouble()).toLong().coerceAtLeast(1L)
        return minOf(base, cap)
    }

    /** `train.steps` of an existing run.json text, or null. Used on resume to keep the steps already written. */
    fun writtenSteps(runJson: String?): Long? {
        if (runJson.isNullOrBlank()) return null
        val root = runCatching { BranchyJson.json.parseToJsonElement(runJson) as? JsonObject }.getOrNull() ?: return null
        val prim = (root["train"] as? JsonObject)?.get("steps") as? JsonPrimitive ?: return null
        return prim.content.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1.0 }?.toLong()
    }
}

/** What a failure means once the user asked the run to stop. */
object BranchyStopPolicy {
    /** The IOException of reading stdout after `destroy()` closed the pipe is expected, never a run error. */
    fun isExpectedReadFailure(error: Throwable, stopRequested: Boolean): Boolean =
        stopRequested && error is IOException

    /** After a stop request any pipeline failure ends as PAUSED (resumable); otherwise it is an error. */
    fun endsPaused(stopRequested: Boolean): Boolean = stopRequested
}

/** `<name>.done` marker next to a prepared `.btok`: records the prepared training-token count (legacy: just "ok"). */
object BranchyDataMarker {
    fun text(trainTokens: Long): String = "ok $trainTokens"

    /** Training tokens recorded in a marker, or 0 when unknown (legacy marker). */
    fun trainTokens(content: String?): Long =
        content?.trim()?.removePrefix("ok")?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
}

/**
 * The `control_file` the native coordinator polls:
 * `{"seq":N,"skip":[ids],"rest":[ids],"unrest":[ids],"retry":[ids],"forget":[ids],"checkpoint":true,"rebalance":true}`.
 * Every distinct `seq` is applied once, so each write carries a higher one (persisted in `control.seq` next to the file,
 * so a native restart in the same stage dir never sees an old number again).
 */
object BranchyControlFile {
    const val SEQ_FILE_NAME = "control.seq"

    /** Commands waiting for a `control_ack`; `skip` is cumulative for the run, the rest are one-shot. */
    data class Commands(
        val skip: Set<String> = emptySet(),
        val rest: Set<String> = emptySet(),
        val unrest: Set<String> = emptySet(),
        val retry: Set<String> = emptySet(),
        val forget: Set<String> = emptySet(),
        val checkpoint: Boolean = false,
        val rebalance: Boolean = false
    ) {
        /** True when nothing but the cumulative skip list is pending. */
        val hasOneShot: Boolean get() = rest.isNotEmpty() || unrest.isNotEmpty() || retry.isNotEmpty() ||
            forget.isNotEmpty() || checkpoint || rebalance

        /** `rest` and `unrest` of one id cancel each other (the latest wins). */
        fun rest(id: String) = copy(rest = rest + id, unrest = unrest - id)
        fun unrest(id: String) = copy(unrest = unrest + id, rest = rest - id)
        fun retry(id: String) = copy(retry = retry + id)
        fun forget(id: String) = copy(forget = forget + id, retry = retry - id)
        fun skip(id: String) = copy(skip = skip + id)

        /** Drops the one-shot commands (kept: the cumulative skip list). */
        fun acknowledged() = Commands(skip = skip)
    }

    fun encode(seq: Long, c: Commands): String {
        val map = linkedMapOf<String, kotlinx.serialization.json.JsonElement>("seq" to JsonPrimitive(seq))
        fun put(key: String, ids: Set<String>) {
            if (ids.isNotEmpty()) map[key] = JsonArray(ids.map { JsonPrimitive(it) })
        }
        put("skip", c.skip); put("rest", c.rest); put("unrest", c.unrest); put("retry", c.retry); put("forget", c.forget)
        if (c.checkpoint) map["checkpoint"] = JsonPrimitive(true)
        if (c.rebalance) map["rebalance"] = JsonPrimitive(true)
        return BranchyJson.json.encodeToString(JsonObject.serializer(), JsonObject(map))
    }

    /** Last sequence number persisted in [seqFile] (0 when absent or unreadable). */
    fun readSeq(seqFile: File): Long = runCatching { seqFile.readText().trim().toLong() }.getOrNull()?.coerceAtLeast(0L) ?: 0L

    /** Writes [text] atomically (tmp + rename) so the poller never reads a partial file. */
    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    /** Legacy: writes only a skip list (seq 1). Kept for callers without a [Writer]. */
    fun writeSkip(file: File, ids: Collection<String>) {
        writeAtomic(file, encode(1L, Commands(skip = ids.toSet())))
    }

    /**
     * Stateful writer for one stage launch: deletes the stale control file, continues the persisted sequence,
     * merges commands issued before the native side acknowledged the previous write.
     */
    class Writer(private val file: File, private val seqFile: File = File(file.parentFile, SEQ_FILE_NAME)) {
        private var seq = readSeq(seqFile)
        private var pending = Commands()

        init {
            file.delete()
        }

        val lastSeq: Long @Synchronized get() = seq

        @Synchronized
        fun pendingCommands(): Commands = pending

        /** Merges [change] into the pending commands and writes the file with the next sequence number. */
        @Synchronized
        fun issue(change: (Commands) -> Commands): Long {
            pending = change(pending)
            seq += 1
            writeAtomic(seqFile, seq.toString())
            writeAtomic(file, encode(seq, pending))
            return seq
        }

        fun rest(id: String) = issue { it.rest(id) }
        fun unrest(id: String) = issue { it.unrest(id) }
        fun retry(id: String) = issue { it.retry(id) }
        fun forget(id: String) = issue { it.forget(id) }
        fun skip(id: String) = issue { it.skip(id) }
        fun checkpointNow() = issue { it.copy(checkpoint = true) }
        fun rebalance() = issue { it.copy(rebalance = true) }

        /** The native side applied [ackSeq]; one-shot commands are dropped once the latest write was acknowledged. */
        @Synchronized
        fun onAck(ackSeq: Long) {
            if (ackSeq >= seq) pending = pending.acknowledged()
        }
    }
}

/** Pure helpers of the "BP rebalance" restart (`paused{reason:"rebalance", cluster}`). */
object BranchyRebalance {
    /**
     * Cluster file to restart with, or null. The restart happens only for a native `paused{rebalance}` with an
     * existing cluster file and when the user did not ask to pause/stop.
     */
    fun restartCluster(paused: BranchyEvent.Paused?, stopRequested: Boolean, fileExists: (String) -> Boolean): String? {
        if (paused == null || stopRequested || paused.reason != "rebalance") return null
        val path = paused.cluster?.takeIf { it.isNotBlank() } ?: return null
        return path.takeIf(fileExists)
    }

    /** Branches per device id of a cluster.json text; empty when unreadable. */
    fun placementOf(clusterJson: String): Map<String, List<Int>> {
        val root = runCatching { BranchyJson.json.parseToJsonElement(clusterJson) as? JsonObject }.getOrNull() ?: return emptyMap()
        val devices = root["devices"] as? JsonArray ?: return emptyMap()
        return devices.mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            val id = (d["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val branches = (d["branches"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }
            id to branches
        }.toMap()
    }

    /**
     * [plan] with helper placements replaced by [placement]; the coordinator keeps every branch no helper holds.
     * Helpers missing from [placement] are left unchanged.
     */
    fun applyPlacement(plan: BranchyPlan, placement: Map<String, List<Int>>): BranchyPlan {
        if (placement.isEmpty()) return plan
        val all = plan.placement.flatMap { it.branches }.toSet()
        val helpers = plan.placement.map { p ->
            if (p.role == "coordinator") p else p.copy(branches = placement[p.device] ?: p.branches)
        }
        val taken = helpers.filter { it.role != "coordinator" }.flatMap { it.branches }.toSet()
        return plan.copy(placement = helpers.map { p ->
            if (p.role == "coordinator") p.copy(branches = (all - taken).sorted()) else p
        })
    }
}

/** Heartbeat probing of the selected helpers during a run (UI only; the native side decides about loss). */
object BranchyHelperWatch {
    const val INTERVAL_MS = 15_000L

    /** Probes [devices] once, to warm VPN paths during tokenize/prepare. Result is ignored. */
    fun warm(scope: CoroutineScope, probe: BranchyHelperProbe, devices: List<BranchyClusterDeviceEntity>): Job? {
        if (devices.isEmpty()) return null
        return scope.launch { runCatching { probe.probe(devices) } }
    }

    /** Probes [devices] every [intervalMs] and folds the answers into the live helper rows until cancelled. */
    fun watch(
        scope: CoroutineScope,
        probe: BranchyHelperProbe,
        devices: List<BranchyClusterDeviceEntity>,
        intervalMs: Long = INTERVAL_MS
    ): Job? {
        if (devices.isEmpty()) return null
        return scope.launch {
            while (isActive) {
                val result = runCatching { probe.probe(devices) }.getOrNull()
                if (result != null) BranchyTrainingState.update { BranchyTrainingState.applyProbe(it, result) }
                delay(intervalMs)
            }
        }
    }
}
