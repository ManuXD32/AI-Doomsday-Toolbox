package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Loads one bounded structured transcript page without retaining raw history. */
internal class NativeHarnessStructuredTranscriptActions(
    private val scope: CoroutineScope,
    private val clientProvider: () -> HarnessClient?,
    private val stateProvider: () -> NativeHarnessUiState,
    private val mutate: suspend ((NativeHarnessUiState) -> NativeHarnessUiState) -> Unit,
    private val reportFailure: suspend (String, String) -> Unit,
    private val sessionAddressProvider: (String) -> JsonObject? = { ordinaryHarnessSessionAddress(it).toWire() },
) : AutoCloseable {
    private val generation = AtomicLong(0L)
    private val detailJob = AtomicReference<Job?>()

    suspend fun loadDetail(itemId: String, requestedPage: Int) {
        if (requestedPage < 0) return reportFailure(
            "TRANSCRIPT_DETAIL_PAGE_INVALID",
            "The requested transcript page is invalid",
        )
        val snapshot = stateProvider()
        val sessionId = snapshot.selectedSessionId ?: return
        val item = snapshot.structuredTranscript.firstOrNull { it.id == itemId }
            ?: return reportFailure("TRANSCRIPT_DETAIL_ITEM_MISSING", "The transcript item is no longer available")
        val reference = item.detailRef
            ?: return reportFailure("TRANSCRIPT_DETAIL_REFERENCE_INVALID", "The transcript item has no detail reference")
        val client = clientProvider()
            ?: return reportFailure("HARNESS_UNAVAILABLE", "The Harness runtime is unavailable")
        val sessionReference = reference as? NativeHarnessTranscriptDetailRef.SessionEvents
            ?: return reportFailure("TRANSCRIPT_DETAIL_REFERENCE_INVALID", "The transcript detail reference is unsupported")
        val address = sessionAddressProvider(sessionId)
            ?: return reportFailure(
                "SUBAGENT_ADDRESS_UNAVAILABLE",
                "The selected subagent address is not available yet",
            )
        val sequences = sessionReference.sequences.distinct()
        if (sequences.isEmpty()) return reportFailure(
            "TRANSCRIPT_DETAIL_REFERENCE_INVALID",
            "The transcript detail reference is empty",
        )
        detailJob.getAndSet(null)?.cancel()
        val token = generation.incrementAndGet()
        mutate { current ->
            if (isCurrent(current, sessionId, itemId, client, token)) {
                current.copy(
                    structuredDetail = HarnessStructuredTranscriptDetailUiState(
                        generation = current.structuredDetail.generation,
                        itemId = itemId,
                        isLoading = true,
                    ),
                )
            } else {
                current
            }
        }
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                // Session/page is cursor based. A single window sized for the whole reference can
                // omit a call/result pair when unrelated events sit between their sequences. Fetch
                // each referenced event with maxMessages=1, retaining only the bounded event rows.
                val expectedSequences = sequences.take(MAX_DETAIL_REFERENCE_SEQUENCES).toSet()
                val records = mutableListOf<JsonObject>()
                var failure: HarnessRpcResult.Failure? = null
                for (sequence in expectedSequences) {
                    when (val result = client.pageSession(
                        buildJsonObject {
                            put("address", address)
                            put("throughSeq", sequence)
                            if (sequence < Long.MAX_VALUE) put("beforeSeq", sequence + 1L)
                            put("maxMessages", 1)
                        },
                    )) {
                        is HarnessRpcResult.Failure -> {
                            failure = result
                            break
                        }
                        is HarnessRpcResult.Success -> result.value.objectArray("records").forEach { record ->
                            val event = record.objectValue("event") ?: record
                            val returnedSequence = event.long("seq") ?: record.long("seq")
                            if (returnedSequence != null && returnedSequence in expectedSequences) {
                                records += record
                            }
                        }
                    }
                }
                failure?.let { result ->
                    if (isCurrent(stateProvider(), sessionId, itemId, client, token)) {
                        publishError(sessionId, itemId, client, token, result.error.code)
                        if (isCurrent(stateProvider(), sessionId, itemId, client, token)) {
                            reportFailure(result.error.code, result.error.message)
                        }
                    }
                    return@launch
                }
                val page = parseNativeHarnessTranscriptDetailPage(
                    reference = reference,
                    records = records.distinctBy { record ->
                        val event = record.objectValue("event") ?: record
                        event.long("seq") ?: record.long("seq")
                    },
                    requestedPage = requestedPage,
                )
                publish(sessionId, itemId, client, token, page)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(stateProvider(), sessionId, itemId, client, token)) {
                    publishError(sessionId, itemId, client, token, "TRANSCRIPT_DETAIL_REQUEST_FAILED")
                    if (isCurrent(stateProvider(), sessionId, itemId, client, token)) {
                        reportFailure(
                            "TRANSCRIPT_DETAIL_REQUEST_FAILED",
                            error.message ?: "The transcript detail request failed",
                        )
                    }
                }
            } finally {
                mutate { current ->
                    if (isCurrent(current, sessionId, itemId, client, token)) {
                        current.copy(
                            structuredDetail = current.structuredDetail.copy(isLoading = false),
                        )
                    } else {
                        current
                    }
                }
            }
        }
        detailJob.set(job)
        if (generation.get() == token) job.start() else job.cancel()
    }

    fun invalidate() {
        generation.incrementAndGet()
        detailJob.getAndSet(null)?.cancel()
    }

    override fun close() = invalidate()

    private suspend fun publish(
        sessionId: String,
        itemId: String,
        client: HarnessClient,
        token: Long,
        page: NativeHarnessStructuredTranscriptDetailPage,
    ) {
        mutate { current ->
            if (isCurrent(current, sessionId, itemId, client, token)) {
                current.copy(
                    structuredDetail = HarnessStructuredTranscriptDetailUiState(
                        generation = current.structuredDetail.generation,
                        itemId = itemId,
                        page = page,
                        isLoading = false,
                    ),
                )
            } else {
                current
            }
        }
    }

    private suspend fun publishError(
        sessionId: String,
        itemId: String,
        client: HarnessClient,
        token: Long,
        code: String,
    ) {
        publish(
            sessionId,
            itemId,
            client,
            token,
            NativeHarnessStructuredTranscriptDetailPage(
                reference = stateProvider().structuredTranscript.firstOrNull { it.id == itemId }
                    ?.detailRef ?: NativeHarnessTranscriptDetailRef.SessionEvents(emptyList()),
                pageIndex = 0,
                pageCount = 1,
                errorCode = code,
            ),
        )
    }

    private fun isCurrent(
        state: NativeHarnessUiState,
        sessionId: String,
        itemId: String,
        client: HarnessClient,
        token: Long,
    ): Boolean = generation.get() == token &&
        state.selectedSessionId == sessionId &&
        state.structuredTranscript.any { it.id == itemId } &&
        clientProvider() === client

    private companion object {
        const val MAX_DETAIL_REFERENCE_SEQUENCES = 4
    }
}
