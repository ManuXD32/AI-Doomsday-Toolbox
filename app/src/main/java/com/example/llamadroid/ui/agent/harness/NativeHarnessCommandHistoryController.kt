package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

private const val MAX_PENDING_LIVE_OUTPUT_BYTES = 64 * 1024
private const val MAX_PENDING_LIVE_OUTPUT_PER_CALL_BYTES = 8 * 1024

private data class PendingHarnessCommandOutput(
    val stream: String,
    val offset: Long,
    val text: String,
)

/** Owns bounded Commands history paging for the selected Session. */
internal class NativeHarnessCommandHistoryController(
    private val scope: CoroutineScope,
    private val clientProvider: suspend () -> HarnessClient?,
    private val selectedSessionProvider: () -> String?,
    private val addressForSession: (String) -> JsonObject?,
    private val currentThroughSequence: () -> Long,
    private val currentHistory: () -> HarnessCommandHistoryUiState,
    private val updateHistory: suspend (
        sessionId: String,
        update: (HarnessCommandHistoryUiState) -> HarnessCommandHistoryUiState,
    ) -> Unit,
    private val reportFailure: suspend (code: String, message: String) -> Unit,
) : AutoCloseable {
    private var pageCursor: HarnessCommandHistoryPageCursor? = null
    private val selectionGeneration = AtomicLong(0L)
    private val jobOutputCalls = linkedMapOf<String, String>()
    private val jobOutputById = linkedMapOf<String, HarnessCommandJobOutputProjection>()
    private val liveOutputLock = Any()
    private val liveOutputOffsets = linkedMapOf<HarnessCommandOutputCursorKey, Long>()
    private val pendingLiveOutput = linkedMapOf<String, MutableList<PendingHarnessCommandOutput>>()
    private val pendingLiveOutputOffsets = linkedMapOf<HarnessCommandOutputCursorKey, Long>()
    private var pendingLiveOutputBytes = 0

    suspend fun reset(sessionId: String, loading: Boolean) {
        selectionGeneration.incrementAndGet()
        pageCursor = null
        jobOutputCalls.clear()
        jobOutputById.clear()
        clearLiveOutputState()
        updateHistory(sessionId) {
            HarnessCommandHistoryUiState(isLoading = loading)
        }
    }

    suspend fun clear() {
        selectionGeneration.incrementAndGet()
        pageCursor = null
        jobOutputCalls.clear()
        jobOutputById.clear()
        clearLiveOutputState()
    }

    suspend fun applySnapshot(
        sessionId: String,
        records: List<JsonObject>,
        cursor: Long?,
        hasMore: Boolean,
    ) {
        if (selectedSessionProvider() != sessionId) return
        val beforeSeq = harnessMinimumRecordSequence(records)
        val pageAvailable = hasMore && beforeSeq != null
        pageCursor = HarnessCommandHistoryPageCursor(
            throughSeq = cursor ?: maximumHarnessCommandRecordSequence(records) ?: -1L,
            beforeSeq = beforeSeq,
            hasMore = pageAvailable,
        )
        val mergedRuns = applyPendingLiveOutput(
            mergeNativeHarnessCommandRuns(emptyList(), records, jobOutputCalls, jobOutputById),
        )
        updateHistory(sessionId) {
            HarnessCommandHistoryUiState(
                runs = mergedRuns,
                canLoadOlder = pageAvailable && mergedRuns.size < HARNESS_COMMAND_HISTORY_LIMIT,
                hasSnapshot = true,
            )
        }
    }

    suspend fun applyEvents(sessionId: String, records: List<JsonObject>) {
        if (records.isEmpty() || selectedSessionProvider() != sessionId) return
        updateHistory(sessionId) { current ->
            current.copy(
                runs = applyPendingLiveOutput(
                    mergeNativeHarnessCommandRuns(current.runs, records, jobOutputCalls, jobOutputById),
                ),
                loadFailed = false,
                hasSnapshot = true,
            )
        }
    }

    /** Applies a transient authenticated process-output delta without persisting its content. */
    suspend fun applyLiveOutput(
        sessionId: String,
        callId: String,
        stream: String,
        offset: Long,
        text: String,
    ) {
        if (selectedSessionProvider() != sessionId || callId.isBlank() ||
            stream !in setOf("stdout", "stderr") || offset < 0L || text.isEmpty() ||
            text.toByteArray(Charsets.UTF_8).size > HARNESS_COMMAND_LIVE_CHUNK_LIMIT_BYTES
        ) return

        updateHistory(sessionId) { current ->
            val run = current.runs.firstOrNull { it.id == callId }
            if (run == null) {
                rememberPendingLiveOutput(callId, stream, offset, text)
                current
            } else if (run.status != HarnessCommandRunStatus.RUNNING) {
                current
            } else {
                synchronized(liveOutputLock) {
                    current.copy(
                        runs = appendNativeHarnessCommandLiveOutput(
                            current.runs,
                            callId,
                            stream,
                            offset,
                            text,
                            liveOutputOffsets,
                        ),
                    )
                }
            }
        }
    }

    suspend fun refresh() {
        val sessionId = selectedSessionProvider() ?: return
        val client = clientProvider() ?: return
        val address = addressForSession(sessionId) ?: return reportFailure(
            "SUBAGENT_ADDRESS_UNAVAILABLE",
            "The selected Session address is not available yet",
        )
        if (currentHistory().isLoading) return
        val generation = selectionGeneration.get()
        val oldCursor = pageCursor
        updateHistory(sessionId) { it.copy(isLoading = true, loadFailed = false) }
        try {
            val result = client.pageSession(buildJsonObject {
                put("address", address)
                put("throughSeq", oldCursor?.throughSeq ?: currentThroughSequence())
                put("maxMessages", HARNESS_COMMAND_PAGE_SIZE)
            })
            // The selected Session may change while the page request is in flight.
            // Do not let its older cursor replace the new Session's cursor.
            if (selectedSessionProvider() != sessionId || selectionGeneration.get() != generation) return
            when (result) {
                is HarnessRpcResult.Failure -> {
                    updateHistory(sessionId) { it.copy(isLoading = false, loadFailed = true) }
                    reportFailure(result.error.code, result.error.message)
                }
                is HarnessRpcResult.Success -> {
                    val records = result.value.objectArray("records")
                    val beforeSeq = harnessMinimumRecordSequence(records)
                    val cursor = maxOf(
                        oldCursor?.throughSeq ?: Long.MIN_VALUE,
                        maximumHarnessCommandRecordSequence(records) ?: Long.MIN_VALUE,
                        currentThroughSequence(),
                    )
                    val hasMore = commandHistoryCanLoadOlder(
                        records,
                        result.value.boolean("hasMore").orDefault(false),
                    )
                    pageCursor = HarnessCommandHistoryPageCursor(
                        throughSeq = cursor,
                        beforeSeq = oldCursor?.beforeSeq ?: beforeSeq,
                        hasMore = hasMore,
                    )
                    updateHistory(sessionId) { current ->
                        val mergedRuns = applyPendingLiveOutput(
                            mergeNativeHarnessCommandRuns(current.runs, records, jobOutputCalls, jobOutputById),
                        )
                        current.copy(
                            runs = mergedRuns,
                            isLoading = false,
                            canLoadOlder = hasMore && mergedRuns.size < HARNESS_COMMAND_HISTORY_LIMIT,
                            loadFailed = false,
                            hasSnapshot = true,
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (selectedSessionProvider() != sessionId || selectionGeneration.get() != generation) return
            updateHistory(sessionId) { it.copy(isLoading = false, loadFailed = true) }
            throw error
        }
    }

    suspend fun loadOlder() {
        val sessionId = selectedSessionProvider() ?: return
        val client = clientProvider() ?: return
        val address = addressForSession(sessionId) ?: return reportFailure(
            "SUBAGENT_ADDRESS_UNAVAILABLE",
            "The selected Session address is not available yet",
        )
        val cursor = pageCursor ?: return
        if (!cursor.hasMore || !currentHistory().canLoadOlder || cursor.beforeSeq == null ||
            currentHistory().isLoadingOlder
        ) return
        val generation = selectionGeneration.get()
        updateHistory(sessionId) { it.copy(isLoadingOlder = true, loadFailed = false) }
        try {
            val result = client.pageSession(buildJsonObject {
                put("address", address)
                put("throughSeq", cursor.throughSeq)
                put("beforeSeq", cursor.beforeSeq)
                put("maxMessages", HARNESS_COMMAND_PAGE_SIZE)
            })
            if (selectedSessionProvider() != sessionId || selectionGeneration.get() != generation) return
            when (result) {
                is HarnessRpcResult.Failure -> {
                    updateHistory(sessionId) { it.copy(isLoadingOlder = false, loadFailed = true) }
                    reportFailure(result.error.code, result.error.message)
                }
                is HarnessRpcResult.Success -> {
                    val records = result.value.objectArray("records")
                    val beforeSeq = harnessMinimumRecordSequence(records)
                    val hasMore = commandHistoryCanLoadOlder(
                        records,
                        result.value.boolean("hasMore").orDefault(false),
                    )
                    pageCursor = cursor.copy(
                        beforeSeq = beforeSeq ?: cursor.beforeSeq,
                        hasMore = hasMore,
                    )
                    updateHistory(sessionId) { current ->
                        val mergedRuns = applyPendingLiveOutput(
                            mergeNativeHarnessCommandRuns(current.runs, records, jobOutputCalls, jobOutputById),
                        )
                        current.copy(
                            runs = mergedRuns,
                            isLoadingOlder = false,
                            canLoadOlder = hasMore && mergedRuns.size < HARNESS_COMMAND_HISTORY_LIMIT,
                            loadFailed = false,
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (selectedSessionProvider() != sessionId || selectionGeneration.get() != generation) return
            updateHistory(sessionId) { it.copy(isLoadingOlder = false, loadFailed = true) }
            throw error
        }
    }

    override fun close() {
        selectionGeneration.incrementAndGet()
        pageCursor = null
        jobOutputCalls.clear()
        jobOutputById.clear()
        clearLiveOutputState()
    }

    private fun applyPendingLiveOutput(runs: List<HarnessCommandRunUi>): List<HarnessCommandRunUi> =
        synchronized(liveOutputLock) {
            var updated = runs
            pendingLiveOutput.keys.toList().forEach { callId ->
                val run = updated.firstOrNull { it.id == callId } ?: return@forEach
                val pending = pendingLiveOutput.remove(callId).orEmpty()
                pending.forEach { chunk -> pendingLiveOutputBytes -= chunk.text.toByteArray(Charsets.UTF_8).size }
                pendingLiveOutputOffsets.keys.removeAll { it.callId == callId }
                if (run.status == HarnessCommandRunStatus.RUNNING) {
                    pending.forEach { chunk ->
                        updated = appendNativeHarnessCommandLiveOutput(
                            updated,
                            callId,
                            chunk.stream,
                            chunk.offset,
                            chunk.text,
                            liveOutputOffsets,
                        )
                    }
                }
            }
            updated.filter { it.status != HarnessCommandRunStatus.RUNNING }.forEach { run ->
                liveOutputOffsets.keys.removeAll { it.callId == run.id }
            }
            updated
        }

    private fun rememberPendingLiveOutput(callId: String, stream: String, offset: Long, text: String) {
        synchronized(liveOutputLock) {
            val key = HarnessCommandOutputCursorKey(callId, stream)
            val previousOffset = pendingLiveOutputOffsets[key]
            if (previousOffset != null && offset < previousOffset) return
            val chunkBytes = text.toByteArray(Charsets.UTF_8).size
            if (chunkBytes > MAX_PENDING_LIVE_OUTPUT_PER_CALL_BYTES) return
            val chunks = pendingLiveOutput.getOrPut(callId) { mutableListOf() }
            val currentBytes = chunks.sumOf { it.text.toByteArray(Charsets.UTF_8).size }
            if (currentBytes + chunkBytes > MAX_PENDING_LIVE_OUTPUT_PER_CALL_BYTES) return
            if (previousOffset == null && offset > 0L) {
                // The projection marks a first non-zero offset as a visible gap.
            }
            pendingLiveOutputOffsets.remove(key)
            pendingLiveOutputOffsets[key] = offset + chunkBytes
            chunks += PendingHarnessCommandOutput(stream, offset, text)
            pendingLiveOutputBytes += chunkBytes
            while (pendingLiveOutputBytes > MAX_PENDING_LIVE_OUTPUT_BYTES ||
                pendingLiveOutput.size > HARNESS_COMMAND_HISTORY_LIMIT
            ) {
                val oldestCall = pendingLiveOutput.keys.firstOrNull() ?: break
                pendingLiveOutput.remove(oldestCall).orEmpty().forEach { old ->
                    pendingLiveOutputBytes -= old.text.toByteArray(Charsets.UTF_8).size
                }
                pendingLiveOutputOffsets.keys.removeAll { it.callId == oldestCall }
            }
        }
    }

    private fun clearLiveOutputState() {
        synchronized(liveOutputLock) {
            liveOutputOffsets.clear()
            pendingLiveOutput.clear()
            pendingLiveOutputOffsets.clear()
            pendingLiveOutputBytes = 0
        }
    }

    private companion object {
        const val HARNESS_COMMAND_PAGE_SIZE = 120
    }
}
