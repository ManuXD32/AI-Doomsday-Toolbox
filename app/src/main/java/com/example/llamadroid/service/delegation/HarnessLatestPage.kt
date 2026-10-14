package com.example.llamadroid.service.delegation

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `session/page` requires `throughSeq`. Callers that follow a session know the latest sequence;
 * the others fall back to the largest integer a JS runtime represents exactly, which pages from
 * the newest event. Shared by the subagent's transcript reader and the viewer's thread preview.
 */
internal object HarnessLatestPage {
    /** 2^53 - 1: the largest sequence a JavaScript runtime parses without losing precision. */
    const val MAX_SAFE_SEQ = 9_007_199_254_740_991L

    /** Ordered `throughSeq` values to try: the known latest sequence first, then the max. */
    fun throughCandidates(knownSeq: Long?): List<Long> =
        listOfNotNull(knownSeq?.takeIf { it >= 0L }, MAX_SAFE_SEQ).distinct()

    /** Pages [address] newest-first, trying each [throughCandidates] value until one is accepted. */
    suspend fun page(
        client: HarnessClient,
        address: JsonObject,
        knownSeq: Long?,
        beforeSeq: Long?,
        maxMessages: Int,
    ): HarnessRpcResult {
        var last: HarnessRpcResult? = null
        for (through in throughCandidates(knownSeq)) {
            val result = client.pageSession(buildJsonObject {
                put("address", address)
                put("throughSeq", through)
                beforeSeq?.let { put("beforeSeq", it) }
                put("maxMessages", maxMessages)
            })
            if (result is HarnessRpcResult.Success) return result
            last = result
        }
        return last!!
    }
}
