package com.example.llamadroid.service.delegation

import org.json.JSONArray

/** Pure helper: every harness session id a delegation task created. */
internal object DelegationThreadIds {
    /** Ids from `harnessThreadsJson` (`[{"id":..,"title":..}]`) plus [currentSession], distinct, in order. */
    fun parse(threadsJson: String?, currentSession: String?): List<String> {
        val ids = LinkedHashSet<String>()
        if (!threadsJson.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(threadsJson)
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(ids::add)
                }
            }
        }
        currentSession?.takeIf { it.isNotBlank() }?.let(ids::add)
        return ids.toList()
    }
}
