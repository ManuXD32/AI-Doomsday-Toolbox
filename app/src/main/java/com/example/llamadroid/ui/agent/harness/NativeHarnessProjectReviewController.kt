package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only project-review state for the native Harness surface.
 *
 * Deliverables are authenticated HTTP routes in the upstream package, while
 * workflow history and projections travel through the Session API. Keeping
 * those seams as injected dependencies avoids pretending that a route is an
 * RPC method and lets the UI owner bind the already-authenticated client.
 */
internal class NativeHarnessProjectReviewController(
    private val scope: CoroutineScope,
    private val clientProvider: () -> HarnessClient?,
    private val agentIdProvider: () -> String?,
    private val transportProvider: () -> NativeHarnessProjectReviewTransport?,
    private val sessionAddressProvider: (String) -> JsonObject? = { null },
) : AutoCloseable {
    private val sessionAddresses = NativeHarnessSessionAddressStore()
    private val mutableState = MutableStateFlow(NativeHarnessProjectReviewState())
    val state: StateFlow<NativeHarnessProjectReviewState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private val refreshSerial = AtomicLong(0L)

    fun refresh(): Job {
        refreshJob?.cancel()
        val requestSerial = refreshSerial.incrementAndGet()
        val job = scope.launch { refreshNow(requestSerial) }
        refreshJob = job
        return job
    }

    /**
     * Publishes the selected session boundary and clears review data from the previous session.
     * Call this before refreshing, including when the selected session or authenticated client
     * becomes null.
     */
    fun selectSession(agentId: String?) {
        refreshJob?.cancel()
        refreshJob = null
        refreshSerial.incrementAndGet()
        mutableState.update { current ->
            val selected = agentId?.takeIf { it.isNotBlank() }
            when {
                selected == null -> NativeHarnessProjectReviewState()
                current.agentId == selected -> current.copy(loading = false)
                else -> NativeHarnessProjectReviewState(agentId = selected)
            }
        }
    }

    suspend fun refreshNow(): Boolean = refreshNow(refreshSerial.incrementAndGet())

    private suspend fun refreshNow(requestSerial: Long): Boolean {
        val agentId = selectedAgent() ?: return false
        if (!updateForRequest(agentId, requestSerial) { current ->
                current.copy(agentId = agentId, loading = true, errorCode = null, errorMessage = null)
            }
        ) return false
        val client = clientProvider() ?: return fail("CLIENT_UNAVAILABLE", agentId, requestSerial)
        return try {
            val records = readHistory(client, agentId)
            if (!isCurrentRequest(agentId, requestSerial)) return false
            val historyClosed = nativeHarnessHistoryClosed(records)
            val workflows = foldNativeHarnessWorkflows(records, historyClosed)
            if (!updateForRequest(agentId, requestSerial) { current ->
                current.copy(
                    agentId = agentId,
                    workflows = workflows,
                    presentedFiles = parseNativeHarnessPresentedFiles(records),
                    historyClosed = historyClosed,
                    loading = false,
                    errorCode = null,
                    errorMessage = null,
                )
            }) return false
            val latestChanges = records.asReversed()
                .asSequence()
                .mapNotNull { record -> parseNativeHarnessChangesCoordinates(agentId, record) }
                .firstOrNull()
            if (latestChanges != null) {
                loadChangesInternal(latestChanges, requestSerial)
            } else {
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: HistoryReadException) {
            fail(failure.code, agentId, requestSerial)
        } catch (_: Throwable) {
            fail("SESSION_HISTORY_FAILED", agentId, requestSerial)
        }
    }

    /** Fold the exact projection values delivered by the Gateway control stream. */
    fun applyProjection(agentId: String, projection: JsonElement): Boolean {
        if (agentId.isBlank() || agentId != selectedAgent()) return false
        val values = projection.objectValue("values") ?: projection.jsonObjectOrNull() ?: return false
        val todos = if (values.containsKey("todos")) {
            val raw = values["todos"] ?: JsonNull
            if (raw is JsonNull) null else parseNativeHarnessTodos(raw) ?: return false
        } else null
        val schedules = if (values.containsKey("schedule")) {
            parseNativeHarnessSchedules(values["schedule"] ?: return false) ?: return false
        } else null
        return updateForAgent(agentId) { current ->
            current.copy(
                agentId = agentId,
                todos = if (values.containsKey("todos")) todos else current.todos,
                schedules = if (values.containsKey("schedule")) schedules ?: current.schedules else current.schedules,
                projectionAvailable = true,
                errorCode = null,
                errorMessage = null,
            )
        }
    }

    /** Accept one `baseline` or `projection` control frame without exposing raw JSON. */
    fun applyProjectionFrame(frame: JsonElement): Boolean {
        val root = frame.jsonObjectOrNull() ?: return false
        val frameAgent = root.string("sessionId")
        if (frameAgent != null && frameAgent != selectedAgent()) return false
        return when (root.string("type")) {
            "baseline" -> {
                val selected = selectedAgent() ?: return false
                val row = root.objectValue("value")?.objectValue("projections")?.objectValue(selected) ?: return false
                applyProjection(selected, row)
            }
            "projection" -> {
                val selected = root.string("sessionId") ?: selectedAgent() ?: return false
                val key = root.string("key") ?: return false
                val value = root["value"] ?: JsonNull
                when (key) {
                    "todos" -> if (value is JsonNull) {
                        updateForAgent(selected) { current ->
                            current.copy(agentId = selected, todos = null, projectionAvailable = true)
                        }
                    } else {
                        val todos = parseNativeHarnessTodos(value) ?: return false
                        updateForAgent(selected) { current ->
                            current.copy(agentId = selected, todos = todos, projectionAvailable = true)
                        }
                    }
                    "schedule" -> {
                        val schedules = parseNativeHarnessSchedules(value) ?: return false
                        updateForAgent(selected) { current ->
                            current.copy(agentId = selected, schedules = schedules, projectionAvailable = true)
                        }
                    }
                    else -> return false
                }
            }
            else -> false
        }
    }

    fun applyHistory(agentId: String, records: Iterable<JsonObject>, closed: Boolean): Boolean {
        if (agentId.isBlank() || agentId != selectedAgent()) return false
        return updateForAgent(agentId) { current ->
            current.copy(
                agentId = agentId,
                workflows = foldNativeHarnessWorkflows(records, closed),
                presentedFiles = parseNativeHarnessPresentedFiles(records),
                historyClosed = closed,
                errorCode = null,
                errorMessage = null,
            )
        }
    }

    suspend fun loadChanges(coordinates: NativeHarnessProjectReviewCoordinates): Boolean {
        return loadChangesInternal(coordinates, requestSerial = null)
    }

    private suspend fun loadChangesInternal(
        coordinates: NativeHarnessProjectReviewCoordinates,
        requestSerial: Long?,
    ): Boolean {
        if (coordinates.sequence < 0L || coordinates.turn < 1 || !coordinatesBelongToSelectedAgent(coordinates.sessionId)) {
            return fail("SESSION_SCOPE_MISMATCH", coordinates.sessionId, requestSerial)
        }
        val agentId = coordinates.sessionId
        val transport = transportProvider() ?: return fail("REVIEW_TRANSPORT_UNAVAILABLE", agentId, requestSerial)
        return try {
            val value = transport.get(
                NativeHarnessProjectReviewWire.CHANGES_SUMMARY_PATH,
                NativeHarnessProjectReviewWire.query(coordinates.sessionId, coordinates.sequence),
            )
            if (requestSerial != null) {
                if (!isCurrentRequest(agentId, requestSerial)) return false
            } else if (!coordinatesBelongToSelectedAgent(agentId)) {
                return false
            }
            val summary = parseNativeHarnessChangesSummary(value, coordinates)
                ?: return fail("INVALID_CHANGES_SUMMARY", agentId, requestSerial)
            if (requestSerial != null) {
                updateForRequest(agentId, requestSerial) { current ->
                    current.copy(changes = summary, selectedDiff = null, errorCode = null, errorMessage = null)
                }
            } else {
                updateForAgent(agentId) { current ->
                    current.copy(changes = summary, selectedDiff = null, errorCode = null, errorMessage = null)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fail("CHANGES_SUMMARY_FAILED", agentId, requestSerial)
        }
    }

    suspend fun loadDiff(coordinates: NativeHarnessProjectReviewCoordinates, index: Int): Boolean {
        if (index < 0 || coordinates.sequence < 0L || !coordinatesBelongToSelectedAgent(coordinates.sessionId)
            || mutableState.value.changes?.coordinates?.sessionId != coordinates.sessionId
            || mutableState.value.changes?.coordinates?.sequence != coordinates.sequence
            || index !in mutableState.value.changes?.files.orEmpty().indices
        ) {
            return fail("INVALID_FILE_INDEX", coordinates.sessionId)
        }
        val agentId = coordinates.sessionId
        val transport = transportProvider() ?: return fail("REVIEW_TRANSPORT_UNAVAILABLE", agentId)
        return try {
            val value = transport.get(
                NativeHarnessProjectReviewWire.CHANGES_DIFF_PATH,
                NativeHarnessProjectReviewWire.query(coordinates.sessionId, coordinates.sequence, index),
            )
            if (!coordinatesBelongToSelectedAgent(agentId)) return false
            val diff = parseNativeHarnessFileDiff(value) ?: return fail("INVALID_FILE_DIFF", agentId)
            var applied = false
            updateForAgent(agentId) { current ->
                val changes = current.changes
                if (changes == null || changes.coordinates.sessionId != agentId ||
                    changes.coordinates.sequence != coordinates.sequence
                ) {
                    current
                } else {
                    applied = true
                    current.copy(selectedDiff = diff, errorCode = null, errorMessage = null)
                }
            }
            applied
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fail("CHANGES_DIFF_FAILED", agentId)
        }
    }

    suspend fun openChangedFile(coordinates: NativeHarnessProjectReviewCoordinates, index: Int): Boolean {
        if (index < 0 || coordinates.sequence < 0L || !coordinatesBelongToSelectedAgent(coordinates.sessionId)
            || mutableState.value.changes?.coordinates?.sessionId != coordinates.sessionId
            || mutableState.value.changes?.coordinates?.sequence != coordinates.sequence
            || index !in mutableState.value.changes?.files.orEmpty().indices
        ) {
            return fail("INVALID_FILE_INDEX", coordinates.sessionId)
        }
        val agentId = coordinates.sessionId
        val transport = transportProvider() ?: return fail("REVIEW_TRANSPORT_UNAVAILABLE", agentId)
        return postOpen(
            transport = transport,
            path = NativeHarnessProjectReviewWire.CHANGES_OPEN_PATH,
            coordinates = coordinates,
            index = index,
            action = null,
            stillValid = {
                val changes = mutableState.value.changes
                changes?.coordinates?.sessionId == agentId &&
                    changes.coordinates.sequence == coordinates.sequence &&
                    index in changes.files.indices
            },
        )
    }

    suspend fun openPresentedFile(
        coordinates: NativeHarnessProjectReviewCoordinates,
        index: Int,
        action: NativeHarnessProjectFileAction,
    ): Boolean {
        if (index < 0 || coordinates.sequence < 0L || !coordinatesBelongToSelectedAgent(coordinates.sessionId)) {
            return fail("INVALID_FILE_INDEX", coordinates.sessionId)
        }
        if (mutableState.value.presentedFiles.none {
                it.sequence == coordinates.sequence &&
                    it.turn == coordinates.turn &&
                    it.index == index
            }
        ) {
            return fail("INVALID_FILE_INDEX", coordinates.sessionId)
        }
        val transport = transportProvider() ?: return fail("REVIEW_TRANSPORT_UNAVAILABLE", coordinates.sessionId)
        return postOpen(
            transport = transport,
            path = "/api/present.open",
            coordinates = coordinates,
            index = index,
            action = action,
            stillValid = {
                mutableState.value.presentedFiles.any {
                    it.sequence == coordinates.sequence &&
                        it.turn == coordinates.turn &&
                        it.index == index
                }
            },
        )
    }

    suspend fun readWorkspaceFile(path: String, offset: Int = 1, limit: Int = 200): Boolean {
        val agentId = selectedAgent() ?: return fail("SESSION_SCOPE_MISSING")
        if (path.isBlank() || offset < 1 || limit !in 1..1_000) return fail("INVALID_FILE_READ", agentId)
        val transport = transportProvider() ?: return fail("REVIEW_TRANSPORT_UNAVAILABLE", agentId)
        return try {
            val value = transport.readWorkspaceFile(agentId, path, offset, limit)
            if (!coordinatesBelongToSelectedAgent(agentId)) return false
            val page = parseNativeHarnessWorkspaceFilePage(value, path)
                ?: return fail("INVALID_FILE_PAGE", agentId)
            updateForAgent(agentId) { current ->
                current.copy(filePage = page, errorCode = null, errorMessage = null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fail("FILE_READ_FAILED", agentId)
        }
    }

    override fun close() {
        refreshJob?.cancel()
        refreshJob = null
        refreshSerial.incrementAndGet()
    }

    private suspend fun postOpen(
        transport: NativeHarnessProjectReviewTransport,
        path: String,
        coordinates: NativeHarnessProjectReviewCoordinates,
        index: Int,
        action: NativeHarnessProjectFileAction?,
        stillValid: () -> Boolean = { true },
    ): Boolean {
        return try {
            val query = NativeHarnessProjectReviewWire.query(coordinates.sessionId, coordinates.sequence, index).toMutableMap()
            if (action == NativeHarnessProjectFileAction.REVEAL) query["action"] = "reveal"
            transport.post(path, query, buildJsonObject {})
            if (!coordinatesBelongToSelectedAgent(coordinates.sessionId) || !stillValid()) return false
            updateForAgent(coordinates.sessionId) { current ->
                current.copy(errorCode = null, errorMessage = null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fail("FILE_OPEN_FAILED", coordinates.sessionId)
        }
    }

    private fun selectedAgent(): String? = agentIdProvider()?.takeIf { it.isNotBlank() }

    private fun coordinatesBelongToSelectedAgent(sessionId: String): Boolean =
        sessionId.isNotBlank() && sessionId == selectedAgent()

    private fun stateForAgent(
        current: NativeHarnessProjectReviewState,
        agentId: String,
    ): NativeHarnessProjectReviewState =
        if (current.agentId == agentId) current else NativeHarnessProjectReviewState(agentId = agentId)

    private fun updateForAgent(
        agentId: String,
        transform: (NativeHarnessProjectReviewState) -> NativeHarnessProjectReviewState,
    ): Boolean {
        var applied = false
        mutableState.update { current ->
            if (selectedAgent() != agentId) {
                current
            } else {
                applied = true
                transform(stateForAgent(current, agentId))
            }
        }
        return applied
    }

    private fun updateForRequest(
        agentId: String,
        requestSerial: Long,
        transform: (NativeHarnessProjectReviewState) -> NativeHarnessProjectReviewState,
    ): Boolean {
        var applied = false
        mutableState.update { current ->
            if (!isCurrentRequest(agentId, requestSerial)) {
                current
            } else {
                applied = true
                transform(stateForAgent(current, agentId))
            }
        }
        return applied
    }

    private fun isCurrentRequest(agentId: String, requestSerial: Long): Boolean =
        refreshSerial.get() == requestSerial && selectedAgent() == agentId

    private fun fail(code: String, ownerAgentId: String? = null, requestSerial: Long? = null): Boolean {
        mutableState.update { current ->
            if ((ownerAgentId != null && selectedAgent() != ownerAgentId) ||
                (requestSerial != null && refreshSerial.get() != requestSerial)
            ) {
                current
            } else {
                current.copy(loading = false, errorCode = code, errorMessage = null)
            }
        }
        return false
    }

    private suspend fun resolveSessionAddress(client: HarnessClient, sessionId: String): JsonObject? {
        sessionAddressProvider(sessionId)?.let { return it }
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var done = false
        repeat(MAX_SESSION_LIST_PAGES) {
            if (done) return@repeat
            when (val result = client.listSessions(cursor)) {
                is HarnessRpcResult.Failure -> done = true
                is HarnessRpcResult.Success -> {
                    val value = result.value.jsonObjectOrNull()
                    if (value == null) {
                        done = true
                        return@repeat
                    }
                    var found = false
                    value.objectArray("items").forEach { row ->
                        sessionAddresses.observeListRow(row)
                        if (row.string("sessionId") == sessionId) found = true
                    }
                    if (found) {
                        done = true
                    } else {
                        val next = value.string("nextCursor")?.takeIf { it.isNotBlank() }
                        if (next == null || !seenCursors.add(next)) done = true else cursor = next
                    }
                }
            }
        }
        val resolved = sessionAddresses.ensureRoutable(client, sessionId)
        return resolved?.toWire() ?: sessionAddresses.wireAddress(sessionId)
    }

    private suspend fun readHistory(client: HarnessClient, agentId: String): List<JsonObject> {
        val address = resolveSessionAddress(client, agentId)
            ?: throw HistoryReadException("SUBAGENT_ADDRESS_UNAVAILABLE")
        val records = mutableListOf<JsonObject>()
        val seenSequences = mutableSetOf<Long>()
        var retainedBytes = 0
        var beforeSequence: Long? = null
        var throughSequence: Long? = null
        repeat(MAX_HISTORY_PAGES) {
            val result = client.pageSession(
                buildJsonObject {
                    put("address", address)
                    put("maxMessages", MAX_PAGE_MESSAGES)
                    throughSequence?.let { put("throughSeq", it) }
                    beforeSequence?.let { put("beforeSeq", it) }
                }
            )
            val value = when (result) {
                is HarnessRpcResult.Failure -> throw HistoryReadException(result.error.code)
                is HarnessRpcResult.Success -> result.value.jsonObjectOrNull() ?: throw HistoryReadException("INVALID_HISTORY_PAGE")
            }
            val page = value.objectArray("records")
            if (throughSequence == null) throughSequence = page.mapNotNull(::historySequence).maxOrNull()
            val unseen = page
                .filter(::isReviewHistoryRecord)
                .filter { row -> historySequence(row)?.let(seenSequences::add) ?: true }
            if (unseen.isNotEmpty()) {
                records.addAll(0, unseen)
                retainedBytes += unseen.sumOf(::historyRecordBytes)
                while (records.isNotEmpty() &&
                    (records.size > MAX_HISTORY_RECORDS || retainedBytes > MAX_HISTORY_BYTES)
                ) {
                    retainedBytes -= historyRecordBytes(records.removeAt(0))
                }
            }
            if (records.size >= MAX_HISTORY_RECORDS || retainedBytes >= MAX_HISTORY_BYTES ||
                !value.boolean("hasMore").orDefault(false)
            ) return records
            val oldest = page.mapNotNull(::historySequence).minOrNull() ?: return records
            if (beforeSequence == oldest) return records
            beforeSequence = oldest
        }
        return records
    }

    private fun historySequence(record: JsonObject): Long? =
        record.long("seq") ?: record.objectValue("event")?.long("seq")

    private fun isReviewHistoryRecord(record: JsonObject): Boolean {
        val type = (record.objectValue("event") ?: record).string("type") ?: return false
        return type == "workspace/changes" ||
            type == "deliverables/presented" ||
            type.startsWith("tool-workflow/") ||
            type == "turn/start" ||
            type == "turn/end" ||
            type == "step/start" ||
            type == "step/end"
    }

    private fun historyRecordBytes(record: JsonObject): Int =
        record.toString().toByteArray(Charsets.UTF_8).size.coerceAtMost(MAX_HISTORY_BYTES)

    private class HistoryReadException(val code: String) : IllegalStateException(code)

    private companion object {
        const val MAX_PAGE_MESSAGES = 512
        const val MAX_HISTORY_PAGES = 32
        const val MAX_SESSION_LIST_PAGES = 64
        const val MAX_HISTORY_RECORDS = 16_384
        const val MAX_HISTORY_BYTES = 4 * 1024 * 1024
    }
}
