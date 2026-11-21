package com.example.llamadroid.branchy.cluster

import com.example.llamadroid.branchy.BranchyEventParser
import com.example.llamadroid.branchy.BranchyJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** State change reported by the native worker on stdout: `{"event":"worker","state":...}`. */
sealed class BranchyWorkerEvent {
    /** Worker is up but no master has connected yet. */
    data object WaitingMaster : BranchyWorkerEvent()

    /** A master opened a session. */
    data object Session : BranchyWorkerEvent()

    /** The master's session ended (clean close or error); the worker is waiting again. */
    data object SessionEnded : BranchyWorkerEvent()

    /** The worker's compute thread count changed (set remotely by the master). */
    data class Threads(val threads: Int) : BranchyWorkerEvent()

    /** Training progress reported by the worker (data-parallel round or branch-parallel step). */
    data class Progress(val progress: BranchyTrainingProgress) : BranchyWorkerEvent()
}

/** Live training progress on a worker. [dataParallel] picks which fields are meaningful. */
data class BranchyTrainingProgress(
    val dataParallel: Boolean,
    /** DP round or BP step. */
    val round: Int,
    /** DP: compute|upload|wait|resting; empty for BP. */
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val msPerMicro: Double? = null,
    val etaSeconds: Double? = null,
    val msFwd: Double? = null,
    val msBwd: Double? = null,
    /** Run name sent by the coordinator (A8 `run`), when present. */
    val run: String? = null
) {
    /** Microbatches left this DP round, never negative. */
    val left: Int get() = (total - done).coerceAtLeast(0)
}

object BranchyWorkerEventParser {
    /** Native log wording for a clean session end. */
    const val LOG_MASTER_CLOSED = "master closed the session"

    /** Native log wording for a reachability probe that connected and left before logging in (changes nothing). */
    const val LOG_PROBE_CLOSED = "probe closed before login"

    /** New "waiting for a master" flag after [event]; null-safe, a probe never reaches here (it parses to null). */
    fun waitingAfter(current: Boolean, event: BranchyWorkerEvent?): Boolean = when (event) {
        BranchyWorkerEvent.WaitingMaster, BranchyWorkerEvent.SessionEnded -> true
        BranchyWorkerEvent.Session -> false
        is BranchyWorkerEvent.Threads, is BranchyWorkerEvent.Progress, null -> current
    }

    /** Returns null for anything that is not a well-formed worker state event. */
    fun parse(line: String): BranchyWorkerEvent? {
        var text = line.trim()
        if (!text.startsWith("{") && !text.startsWith(BranchyEventParser.PREFIX)) {
            return when {
                text.contains(LOG_PROBE_CLOSED, ignoreCase = true) -> null
                text.contains(LOG_MASTER_CLOSED, ignoreCase = true) -> BranchyWorkerEvent.SessionEnded
                else -> null
            }
        }
        if (text.startsWith(BranchyEventParser.PREFIX)) text = text.substring(BranchyEventParser.PREFIX.length).trim()
        if (!text.startsWith("{")) return null
        val obj = try {
            BranchyJson.json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val kind = (obj["event"] as? JsonPrimitive)?.contentOrNull ?: (obj["type"] as? JsonPrimitive)?.contentOrNull
        if (kind == "dp_progress" || kind == "bp_progress") return parseProgress(kind == "dp_progress", obj)
        if (kind != "worker") return null
        return when ((obj["state"] as? JsonPrimitive)?.contentOrNull) {
            "listening", "waiting_master" -> BranchyWorkerEvent.WaitingMaster
            "session_end", "session_error" -> BranchyWorkerEvent.SessionEnded
            "session" -> BranchyWorkerEvent.Session
            "threads" -> (obj["threads"] as? JsonPrimitive)?.doubleOrNull?.toInt()
                ?.takeIf { it in 1..64 }?.let { BranchyWorkerEvent.Threads(it) }
            else -> null
        }
    }

    private fun num(obj: JsonObject, key: String): Double? = (obj[key] as? JsonPrimitive)?.doubleOrNull

    private fun runOf(obj: JsonObject): String? =
        (obj["run"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun parseProgress(dp: Boolean, obj: JsonObject): BranchyWorkerEvent? {
        return if (dp) {
            val round = num(obj, "round")?.toInt() ?: return null
            BranchyWorkerEvent.Progress(
                BranchyTrainingProgress(
                    dataParallel = true,
                    round = round,
                    phase = (obj["phase"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    done = num(obj, "done")?.toInt()?.coerceAtLeast(0) ?: 0,
                    total = num(obj, "total")?.toInt()?.coerceAtLeast(0) ?: 0,
                    msPerMicro = num(obj, "ms_per_mb")?.takeIf { it > 0 },
                    etaSeconds = num(obj, "eta_s")?.takeIf { it >= 0 },
                    run = runOf(obj)
                )
            )
        } else {
            val step = num(obj, "step")?.toInt() ?: return null
            BranchyWorkerEvent.Progress(
                BranchyTrainingProgress(
                    dataParallel = false,
                    round = step,
                    done = num(obj, "mb")?.toInt()?.coerceAtLeast(0) ?: 0,
                    total = num(obj, "of")?.toInt()?.coerceAtLeast(0) ?: 0,
                    msFwd = num(obj, "ms_fwd")?.takeIf { it >= 0 },
                    msBwd = num(obj, "ms_bwd")?.takeIf { it >= 0 },
                    run = runOf(obj)
                )
            )
        }
    }
}
