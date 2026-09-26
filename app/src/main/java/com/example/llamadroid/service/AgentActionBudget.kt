package com.example.llamadroid.service

/** Per-run guard: successful calls are useful only when they bring distinct evidence. */
internal class AgentActionBudget {
    private val evidence = linkedSetOf<String>()
    private var stagnant = 0
    private var searches = 0
    private var fetches = 0

    @Synchronized fun reset() {
        evidence.clear()
        stagnant = 0
        searches = 0
        fetches = 0
    }

    @Synchronized fun admitResearch(tool: String): Boolean = when (tool) {
        "web_search", "kiwix_search" -> if (searches < 2) { searches++; true } else false
        "fetch_url" -> if (fetches < 4) { fetches++; true } else false
        else -> true
    }

    @Synchronized fun record(tool: String, arguments: Map<String, String>, result: String, success: Boolean): Boolean {
        val stateReads = setOf("project_state", "project_state_read", "agent_state", "todo_read", "plan_read", "project_order_read")
        // State polling cannot manufacture progress from timestamps or revision counters.
        // Source text and opaque IDs remain case/whitespace sensitive.
        val key = if (tool in stateReads) tool else
            "$tool:${arguments.toSortedMap().hashCode()}:${result.hashCode()}"
        val newEvidence = success && result.isNotBlank() && evidence.add(key)
        stagnant = if (newEvidence) 0 else stagnant + 1
        while (evidence.size > 256) evidence.remove(evidence.first())
        return stagnant >= 3
    }
}
