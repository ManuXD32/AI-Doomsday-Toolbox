package com.example.llamadroid.branchy.infer

import com.example.llamadroid.branchy.BranchyPhase

/** Pure builders and parsers for the main side of distributed Branchy inference. */
object BranchyInferArgs {
    /**
     * Extra `llama-server` flags for the main device. The helper-side token is passed as a 0600 file path (never the
     * token itself). `-ngl 0 -np 1 --poll 0` are mandatory for the remote graph: CPU only, one slot, and no
     * busy-wait polling that would starve the recv wait. [wire] is `f32` or `bf16`.
     */
    fun serverArgs(helperTargets: List<String>, tokenFile: String, wire: String = "f32"): List<String> = buildList {
        add("--branchy-helper"); add(helperTargets.joinToString(","))
        add("--branchy-token-file"); add(tokenFile)
        if (wire == "bf16") { add("--branchy-wire"); add("bf16") }
        add("-ngl"); add("0")
        add("-np"); add("1")
        add("--poll"); add("0")
    }

    /** Latency above which one device is expected to be faster than the split (T10 measurement). */
    const val SLOW_RTT_MS = 80

    /**
     * Whether a helper's round-trip time is high enough that the split is slower than one device. [rttMs] null =
     * unknown.
     */
    fun isSlowLink(rttMs: Int?): Boolean = rttMs != null && rttMs >= SLOW_RTT_MS

    /**
     * A push replaces the worker's current session, so it must not target a helper that is part of a live
     * training run on this device. Returns the blocking helper ids.
     */
    fun pushBlockedHelpers(
        helperIds: Collection<String>,
        livePhase: BranchyPhase,
        liveDeviceIds: Collection<String>
    ): List<String> {
        val active = livePhase != BranchyPhase.IDLE && livePhase != BranchyPhase.DONE && livePhase != BranchyPhase.ERROR
        if (!active) return emptyList()
        val busy = liveDeviceIds.toSet()
        return helperIds.filter { it in busy }
    }
}

/** What the main server reports about one helper. */
enum class HelperLink { CONNECTED, DEGRADED, RESYNC }

/** Folded view of the main server's stderr: per-helper link state and the last decode speed. */
data class InferLiveStatus(
    val links: Map<String, HelperLink> = emptyMap(),
    val tokensPerSecond: Double? = null
)

/** Parses `llama-server` log lines (`branchy-remote: helper H:P is down (...)` and friends, plus the eval timing line). */
object BranchyInferStatusParser {
    private val down = Regex("""helper (\S+) is down""")
    private val resync = Regex("""helper (\S+) reconnected, resynchronising""")
    private val back = Regex("""helper (\S+) is back in sync""")
    private val speed = Regex("""([0-9]+(?:\.[0-9]+)?) tokens per second""")

    /** Applies one log line to [status]; */
    fun apply(status: InferLiveStatus, line: String): InferLiveStatus {
        if ("branchy-remote" in line) {
            down.find(line)?.let { return status.withLink(it.groupValues[1], HelperLink.DEGRADED) }
            resync.find(line)?.let { return status.withLink(it.groupValues[1], HelperLink.RESYNC) }
            back.find(line)?.let { return status.withLink(it.groupValues[1], HelperLink.CONNECTED) }
        }
        // Generation timing only ("eval time"); the prompt line reads "prompt eval time" and is skipped.
        if ("eval time" in line && "prompt eval time" !in line) {
            speed.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return status.copy(tokensPerSecond = it) }
        }
        return status
    }

    fun fold(initial: InferLiveStatus, lines: List<String>): InferLiveStatus = lines.fold(initial) { s, l -> apply(s, l) }

    /** All helpers connected at start (the server only starts when every `hello` succeeded). */
    fun connected(helperTargets: List<String>): InferLiveStatus =
        InferLiveStatus(links = helperTargets.associateWith { HelperLink.CONNECTED })

    private fun InferLiveStatus.withLink(target: String, link: HelperLink) = copy(links = links + (target to link))
}
