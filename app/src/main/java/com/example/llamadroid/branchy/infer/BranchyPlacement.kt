package com.example.llamadroid.branchy.infer

import com.example.llamadroid.modeltools.BranchyExportSupport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json

/** An inference helper as the placement and the pipeline see it. [pushPort] is the worker port, [inferPort] the helper server. */
data class InferHelper(
    val id: String,
    val name: String,
    val host: String,
    val pushPort: Int,
    val inferPort: Int,
    val gflops: Double?
) {
    val pushTarget: String get() = "$host:$pushPort"
    val inferTarget: String get() = "$host:$inferPort"
}

/** Branch layout of a model: [branches] in total; branches below [localBranches] are undelayed. */
data class InferLayout(val branches: Int, val localBranches: Int = 1, val context: Int? = null)

/** Pure placement of branches: branch -> helper id. A branch that is absent stays on this phone (the main). */
object BranchyPlacement {
    const val MAX_BRANCHES = 64

    /** Reads `model.branches` and `model.local_branches` from a run.json text; null when it has no layout. */
    fun parseLayout(runJson: String?): InferLayout? {
        val model = runCatching {
            Json.parseToJsonElement(runJson ?: return null).jsonObject["model"] as? JsonObject
        }.getOrNull() ?: return null
        val branches = (model["branches"] as? JsonPrimitive)?.intOrNull ?: return null
        val local = (model["local_branches"] as? JsonPrimitive)?.intOrNull ?: 1
        val context = (model["context"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        return if (branches in 1..MAX_BRANCHES) InferLayout(branches, local.coerceIn(0, branches), context) else null
    }

    /**
     * Branch 0 stays local. Every other branch goes to the helper with the lowest (load + 1) / speed, so faster
     * helpers take more branches (round-robin when speeds are equal or unknown). Helpers without a known speed
     * count as the median known speed, or 1.
     */
    fun auto(layout: InferLayout, helpers: List<InferHelper>): Map<Int, String> {
        if (helpers.isEmpty() || layout.branches < 2) return emptyMap()
        val known = helpers.mapNotNull { it.gflops?.takeIf { g -> g > 0.0 } }.sorted()
        val fallback = if (known.isEmpty()) 1.0 else known[known.size / 2]
        val speed = helpers.associate { it.id to (it.gflops?.takeIf { g -> g > 0.0 } ?: fallback) }
        val load = helpers.associate { it.id to 0 }.toMutableMap()
        val out = linkedMapOf<Int, String>()
        for (branch in 1 until layout.branches) {
            val pick = helpers.minByOrNull { (load.getValue(it.id) + 1) / speed.getValue(it.id) } ?: break
            load[pick.id] = load.getValue(pick.id) + 1
            out[branch] = pick.id
        }
        return out
    }

    /** Moves [branch] to [helperId] (null = back to this phone). */
    fun assign(placement: Map<Int, String>, branch: Int, helperId: String?): Map<Int, String> =
        placement.toMutableMap().also { if (helperId == null) it.remove(branch) else it[branch] = helperId }

    /** helper id -> sorted branches it hosts; helpers with none are left out. */
    fun slices(placement: Map<Int, String>): Map<String, List<Int>> =
        placement.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.sorted() }

    /** Drops entries for helpers that are no longer selected or branches outside the layout. */
    fun prune(layout: InferLayout, placement: Map<Int, String>, helperIds: Set<String>): Map<Int, String> =
        placement.filter { (b, h) -> b in 0 until layout.branches && h in helperIds }

    enum class Problem { NO_REMOTE_BRANCH, NO_LOCAL_BRANCH, NO_HELPER }

    /** Null when the placement can be exported: at least one remote and one local branch. */
    fun validate(layout: InferLayout, placement: Map<Int, String>, helperCount: Int): Problem? = when {
        helperCount == 0 -> Problem.NO_HELPER
        placement.isEmpty() -> Problem.NO_REMOTE_BRANCH
        placement.size >= layout.branches -> Problem.NO_LOCAL_BRANCH
        else -> null
    }

    /** File name of a slice: encodes the checkpoint, type and branch list so a changed placement never reuses a stale file. */
    fun sliceFileName(baseName: String, ckptName: String, type: String, branches: List<Int>): String =
        "${BranchyExportSupport.safeName(baseName)}-${BranchyExportSupport.safeName(ckptName)}-${type.lowercase()}" +
            "-b${branches.joinToString("_")}.slice.gguf"

    fun mainFileName(baseName: String, ckptName: String, type: String): String =
        "${BranchyExportSupport.safeName(baseName)}-${BranchyExportSupport.safeName(ckptName)}-${type.lowercase()}-main.gguf"
}
