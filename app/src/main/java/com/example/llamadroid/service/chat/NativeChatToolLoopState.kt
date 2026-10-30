package com.example.llamadroid.service.chat

import org.json.JSONObject

/**
 * Duplicate-call and per-turn limit tracking of the tool loop, rebuildable from the recorded steps so
 * a Continue does not start with an empty memory. Pure apart from `org.json` for the argument map.
 */
class NativeChatToolLoopState {
    val executedSignatures = mutableSetOf<String>()
    val completedMutatingCounts = mutableMapOf<String, Int>()

    companion object {
        /** Order-independent signature of one call: tool name + arguments sorted by key. */
        fun signature(toolName: String, arguments: Map<String, String>): String =
            "$toolName:" + arguments.toSortedMap().entries.joinToString(",", "{", "}") { (key, value) -> "$key=$value" }

        /**
         * State after the calls recorded in [steps]: every tool step counts as seen (as in the live loop,
         * where even failed or skipped calls are remembered); successful ones with a per-turn limit
         * ([hasMutatingLimit]) add to the mutating counters.
         */
        fun rebuild(steps: List<NativeChatStep>, hasMutatingLimit: (String) -> Boolean): NativeChatToolLoopState {
            val state = NativeChatToolLoopState()
            for (step in steps) {
                if (step.type != NativeChatStepType.TOOL) continue
                val name = step.toolName.orEmpty()
                state.executedSignatures += signature(name, argumentMap(step.arguments))
                if (step.status == NativeChatStepStatus.OK && hasMutatingLimit(name)) {
                    state.completedMutatingCounts[name] = (state.completedMutatingCounts[name] ?: 0) + 1
                }
            }
            return state
        }

        private fun argumentMap(arguments: String?): Map<String, String> {
            val json = arguments?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
            val map = LinkedHashMap<String, String>()
            json.keys().forEach { key -> map[key] = json.opt(key)?.let { if (it == JSONObject.NULL) "null" else it.toString() }.orEmpty() }
            return map
        }
    }
}
