package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class NativeHarnessProjectReviewTest {
    @Test
    fun reviewCoordinatesEncodeSessionAndKeepPinnedRoutes() {
        val coordinates = NativeHarnessProjectReviewCoordinates("agent id/1", 12, 3)
        assertEquals(
            mapOf("sessionId" to "agent id/1", "seq" to "12"),
            NativeHarnessProjectReviewWire.query(coordinates.sessionId, coordinates.sequence),
        )
        assertEquals(
            "/api/changes.diff",
            NativeHarnessProjectReviewWire.CHANGES_DIFF_PATH,
        )
        assertEquals(
            "dsh-resource://changes-review/session/agent%20id%2F1/12/3",
            NativeHarnessProjectReviewWire.reviewAddress(coordinates.sessionId, coordinates.sequence, coordinates.turn),
        )
    }

    @Test
    fun summaryAndDiffParsingRejectMalformedWireAndPreserveSpecialFiles() {
        val coordinates = NativeHarnessProjectReviewCoordinates("s", 7, 2)
        val summary = parseNativeHarnessChangesSummary(Json.parseToJsonElement(
            """{"turn":2,"files":[{"path":"a.txt","display":"a.txt","added":2,"deleted":1},{"path":"blob","display":"blob","added":0,"deleted":0,"binary":true}],"total":2,"added":2,"deleted":1}"""
        ), coordinates)
        assertNotNull(summary)
        assertTrue(summary!!.files[1].binary)
        assertEquals(2, summary.coordinates.turn)
        assertNull(parseNativeHarnessChangesSummary(Json.parseToJsonElement("""{"turn":2,"files":[],"total":0,"added":-1,"deleted":0}"""), coordinates))

        val diff = parseNativeHarnessFileDiff(Json.parseToJsonElement(
            """{"kind":"text","path":"a.txt","display":"a.txt","before":true,"after":true,"coarse":false,"hunks":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":2,"lines":["-old","+new"," new"]}]}"""
        ))
        assertTrue(diff is NativeHarnessFileDiff.Text)
        assertEquals(3, (diff as NativeHarnessFileDiff.Text).hunks.single().lines.size)
        assertNull(parseNativeHarnessFileDiff(Json.parseToJsonElement("""{"kind":"text","path":"a","display":"a","before":true,"after":true,"coarse":false,"hunks":[{"oldStart":0,"oldLines":0,"newStart":0,"newLines":0,"lines":["oops"]}]}""")))
    }

    @Test
    fun reviewPagingBoundsListsFilesAndDiffTextWithoutDroppingAcceptedContent() {
        val list = (0 until 51).toList()
        val first = nativeHarnessReviewPage(list, 0)
        val last = nativeHarnessReviewPage(list, 2)
        assertEquals(25, first.items.size)
        assertEquals(listOf(50), last.items)
        assertEquals(3, first.pageCount)

        val diff = NativeHarnessFileDiff.Text(
            path = "large.txt",
            display = "large.txt",
            before = true,
            after = true,
            hunks = listOf(
                NativeHarnessDiffHunk(
                    oldStart = 1,
                    oldLines = 2,
                    newStart = 1,
                    newLines = 2,
                    lines = listOf("-before", "+after", " ${"x".repeat(24 * 1024)}"),
                ),
            ),
            coarse = false,
        )
        val pages = (0 until nativeHarnessDiffPage(diff, 0).pageCount)
            .map { nativeHarnessDiffPage(diff, it).text }
        val expected = "@@ -1,2 +1,2 @@\n-before\n+after\n ${"x".repeat(24 * 1024)}\n"
        assertEquals(expected, pages.joinToString(""))
        assertTrue(pages.all { it.length <= NativeHarnessProjectReviewWire.DIFF_PAGE_TEXT })
        assertTrue(nativeHarnessDiffTextLength(diff) <= NativeHarnessProjectReviewWire.MAX_DIFF_TEXT)
    }

    @Test
    fun oversizedDiffsAndOverlongFilePagesAreRejectedOrMarkedOversized() {
        val oversized = parseNativeHarnessFileDiff(Json.parseToJsonElement(
            """{"kind":"text","path":"large","display":"large","before":true,"after":true,"coarse":false,"hunks":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["+${"x".repeat(NativeHarnessProjectReviewWire.MAX_DIFF_TEXT)}"]}]}"""
        ))
        assertTrue(oversized is NativeHarnessFileDiff.Oversized)
        assertNull(parseNativeHarnessWorkspaceFilePage(
            Json.parseToJsonElement("""{"version":"v1","offset":1,"lines":201,"eof":false,"text":"x"}"""),
            "large.txt",
        ))
        assertEquals(201, NativeHarnessProjectReviewUiAction.Read("large.txt", 201).offset)
    }

    @Test
    fun workflowFoldPreservesMissingAndEmptyPhasesAndMarksOpenRunsInterrupted() {
        val records = listOf(
            event("tool-workflow/run-start", """{"runId":"r","name":"Deploy"}"""),
            event("tool-workflow/agent-start", """{"runId":"r","seq":1,"label":"one","childId":"c1"}"""),
            event("tool-workflow/agent-start", """{"runId":"r","seq":2,"label":"two","phase":"","childId":"c2"}"""),
        )
        val run = foldNativeHarnessWorkflows(records, historyClosed = true).single()
        assertEquals(NativeHarnessWorkflowStatus.INTERRUPTED, run.status)
        assertEquals(listOf("missing", "value:0:"), run.phases.map { it.key })
        assertEquals(NativeHarnessWorkflowStatus.INTERRUPTED, run.phases[0].members.single().status)

        val presented = parseNativeHarnessPresentedFiles(listOf(buildJsonObject {
            put("seq", 9)
            put("type", "deliverables/presented")
            putJsonObject("data") {
                put("turn", 4)
                putJsonArray("files") {
                    add(buildJsonObject { put("path", "out/report.pdf"); put("description", "Final report") })
                }
            }
        }))
        assertEquals("out/report.pdf", presented.single().path)
        assertEquals(4, presented.single().turn)
        assertEquals(
            NativeHarnessProjectReviewCoordinates("selected", 11, 4),
            parseNativeHarnessChangesCoordinates("selected", buildJsonObject {
                put("seq", 11)
                put("type", "workspace/changes")
                putJsonObject("data") { put("turn", 4) }
            }),
        )
        assertTrue(parseNativeHarnessPresentedFiles(listOf(buildJsonObject {
            put("seq", 10)
            put("type", "deliverables/presented")
            putJsonObject("data") {
                put("turn", 4)
                putJsonArray("files") { add(buildJsonObject { put("path", "bad"); put("description", 1) }) }
            }
        })).isEmpty())
    }

    @Test
    fun workflowFoldUsesOwningClosedStepEvenWhenALaterStepIsOpen() {
        val records = listOf(
            event("turn/start", """{"turn":1}"""),
            event("step/start", """{"turn":1,"step":1}"""),
            event("tool-workflow/run-start", """{"runId":"closed","name":"closed-step"}"""),
            event("tool-workflow/agent-start", """{"runId":"closed","seq":1,"label":"worker","childId":"child-1"}"""),
            event("step/end", """{"turn":1,"step":1}"""),
            event("turn/start", """{"turn":2}"""),
            event("step/start", """{"turn":2,"step":1}"""),
            event("tool-workflow/run-start", """{"runId":"open","name":"open-step"}"""),
            event("tool-workflow/agent-start", """{"runId":"open","seq":1,"label":"worker","childId":"child-2"}"""),
        )
        val runs = foldNativeHarnessWorkflows(records, historyClosed = false)
        assertEquals(NativeHarnessWorkflowStatus.INTERRUPTED, runs.first { it.runId == "closed" }.status)
        assertEquals(NativeHarnessWorkflowStatus.RUNNING, runs.first { it.runId == "open" }.status)
        assertTrue(nativeHarnessHistoryClosed(records.take(5)))
        assertFalse(nativeHarnessHistoryClosed(records))
    }

    @Test
    fun workflowFoldClosesRunWhenOpeningBoundaryWasPrunedFromBoundedHistory() {
        val records = listOf(
            event("tool-workflow/run-start", """{"runId":"pruned","name":"pruned opening"}"""),
            event("tool-workflow/agent-start", """{"runId":"pruned","seq":1,"label":"worker","childId":"child-pruned"}"""),
            event("step/end", """{"turn":1,"step":1}"""),
            event("turn/start", """{"turn":2}"""),
            event("step/start", """{"turn":2,"step":1}"""),
            event("tool-workflow/run-start", """{"runId":"current","name":"current"}"""),
            event("tool-workflow/agent-start", """{"runId":"current","seq":1,"label":"worker","childId":"child-current"}"""),
        )
        val runs = foldNativeHarnessWorkflows(records, historyClosed = false)
        assertEquals(NativeHarnessWorkflowStatus.INTERRUPTED, runs.first { it.runId == "pruned" }.status)
        assertEquals(NativeHarnessWorkflowStatus.RUNNING, runs.first { it.runId == "current" }.status)
    }

    @Test
    fun todoAndScheduleProjectionKeepNullBeforeFirstWriteAndCanonicalRecords() {
        assertNull(parseNativeHarnessTodos(JsonNull))
        val todos = parseNativeHarnessTodos(Json.parseToJsonElement("""[{"content":"ship","status":"in_progress"}]"""))
        assertEquals(NativeHarnessTodoStatus.IN_PROGRESS, todos!!.single().status)
        assertNull(parseNativeHarnessTodos(Json.parseToJsonElement("""[{"content":" ship","status":"pending"}]""")))

        val schedules = parseNativeHarnessSchedules(Json.parseToJsonElement(
            """[{"id":"a","kind":"after","prompt":"once","afterSeconds":30,"scheduledAt":"2026-09-20T10:00:00.000Z"},{"id":"e","kind":"every","prompt":"repeat","everySeconds":300,"scheduledAt":"2026-09-20T11:00:00.000Z"}]"""
        ))
        assertEquals(2, schedules!!.size)
        assertTrue(schedules[1] is NativeHarnessScheduleRecord.Every)
        assertNull(parseNativeHarnessSchedules(Json.parseToJsonElement("""[{"id":"e","kind":"every","prompt":"repeat","everySeconds":1,"scheduledAt":"2026-09-20T11:00:00.000Z"}]""")))
    }

    @Test
    fun schedulePresentationMatchesPinnedFrequencyAndRelativeRules() {
        val every = NativeHarnessScheduleRecord.Every(
            id = "e",
            prompt = "repeat",
            everySeconds = 86_400,
            scheduledAt = "2026-09-21T12:00:00.000Z",
        )
        val frequency = nativeHarnessScheduleFrequency(every)
        assertNotNull(frequency)
        assertEquals(1L, frequency!!.value)
        assertEquals(NativeHarnessScheduleUnit.DAY, frequency.unit)

        val future = nativeHarnessScheduleTiming(
            scheduledAt = every.scheduledAt,
            nowMillis = java.time.Instant.parse("2026-09-20T12:00:00.000Z").toEpochMilli(),
            locale = Locale.US,
            zoneId = ZoneId.of("UTC"),
        )
        assertFalse(future.overdue)
        assertNotNull(future.relativeValue)
        assertEquals(NativeHarnessScheduleUnit.DAY, future.relativeUnit)
        assertTrue(future.localTime.isNotBlank())

        val overdue = nativeHarnessScheduleTiming(
            scheduledAt = "2026-09-21T12:00:00.000Z",
            nowMillis = java.time.Instant.parse("2026-09-22T14:01:00.000Z").toEpochMilli(),
            locale = Locale.US,
            zoneId = ZoneId.of("UTC"),
        )
        assertTrue(overdue.overdue)
        assertEquals(1L, overdue.relativeValue)
        assertEquals(NativeHarnessScheduleUnit.DAY, overdue.relativeUnit)
    }

    @Test
    fun controllerUsesSelectedAgentScopeForHistoryAndRejectsAnotherSession(): Unit = runBlocking {
        val client = HistoryClient()
        val scope = CoroutineScope(SupervisorJob())
        val controller = NativeHarnessProjectReviewController(
            scope = scope,
            clientProvider = { client },
            agentIdProvider = { "selected" },
            transportProvider = { null },
        )
        assertTrue(controller.refreshNow())
        assertEquals("selected", client.pageRequest?.get("address")?.jsonObject?.get("sessionId")?.toString()?.trim('"'))
        assertFalse(controller.applyHistory("other", emptyList(), false))
        controller.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun projectReviewFindsSubagentAddressOnLaterSessionListPage(): Unit = runBlocking {
        val client = HistoryClient().apply {
            listPages[null] = buildJsonObject {
                putJsonArray("items") {
                    add(buildJsonObject { put("sessionId", "older") })
                }
                put("nextCursor", "page-2")
            }
            listPages["page-2"] = buildJsonObject {
                putJsonArray("items") {
                    add(buildJsonObject {
                        put("sessionId", "selected")
                        put("origin", "subagent")
                        put("parentSessionId", "parent")
                        putJsonObject("projections") {
                            putJsonObject("values") {
                                putJsonObject("subagent") { put("mode", "continuable") }
                            }
                        }
                    })
                }
            }
        }
        val controller = NativeHarnessProjectReviewController(
            scope = CoroutineScope(SupervisorJob()),
            clientProvider = { client },
            agentIdProvider = { "selected" },
            transportProvider = { null },
        )
        assertTrue(controller.refreshNow())
        assertEquals(listOf(null, "page-2"), client.listCursors)
        assertEquals(
            "subagent",
            client.pageRequest?.get("address")?.jsonObject?.get("kind")?.toString()?.trim('"'),
        )
        assertEquals(
            "parent",
            client.pageRequest?.get("address")?.jsonObject?.get("parentSessionId")?.toString()?.trim('"'),
        )
        controller.close()
    }

    @Test
    fun refresh_discovers_latest_workspace_changes_and_loads_summary() = runBlocking {
        val client = HistoryClient().apply {
            pageRecords = listOf(buildJsonObject {
                put("seq", 12)
                put("type", "workspace/changes")
                putJsonObject("data") { put("turn", 4) }
            })
        }
        val transport = ReviewTransport()
        val controller = NativeHarnessProjectReviewController(
            scope = CoroutineScope(SupervisorJob()),
            clientProvider = { client },
            agentIdProvider = { "selected" },
            transportProvider = { transport },
        )

        assertTrue(controller.refreshNow())
        assertEquals(12L, controller.state.value.changes?.coordinates?.sequence)
        assertEquals(NativeHarnessProjectReviewWire.CHANGES_SUMMARY_PATH, transport.lastPath)
        controller.close()
    }

    @Test
    fun deliverablesTransportKeepsSessionCoordinatesAndReadOnlyActions() = runBlocking {
        val transport = ReviewTransport()
        val controller = NativeHarnessProjectReviewController(
            scope = CoroutineScope(SupervisorJob()),
            clientProvider = { null },
            agentIdProvider = { "selected" },
            transportProvider = { transport },
        )
        val coordinates = NativeHarnessProjectReviewCoordinates("selected", 8, 4)
        assertTrue(controller.applyHistory("selected", listOf(buildJsonObject {
            put("seq", 8)
            put("type", "deliverables/presented")
            putJsonObject("data") {
                put("turn", 4)
                putJsonArray("files") { add(buildJsonObject { put("path", "out.txt") }) }
            }
        }), closed = false))
        assertTrue(controller.loadChanges(coordinates))
        assertEquals(NativeHarnessProjectReviewWire.CHANGES_SUMMARY_PATH, transport.lastPath)
        assertEquals("selected", transport.lastQuery["sessionId"])
        assertTrue(controller.loadDiff(coordinates, 0))
        assertEquals(NativeHarnessProjectReviewWire.CHANGES_DIFF_PATH, transport.lastPath)
        assertTrue(controller.openPresentedFile(coordinates, 0, NativeHarnessProjectFileAction.REVEAL))
        assertEquals("reveal", transport.lastQuery["action"])
        assertTrue(controller.readWorkspaceFile("README.md"))
        assertEquals("selected", transport.lastReadSession)
        assertEquals(1, transport.lastReadOffset)
        assertFalse(controller.readWorkspaceFile("README.md", offset = 0))
        assertEquals("INVALID_FILE_READ", controller.state.value.errorCode)
        assertFalse(controller.state.value.revertSupported)
        assertFalse(controller.openChangedFile(coordinates.copy(sessionId = "other"), 0))
        controller.close()
    }

    @Test
    fun stale_deliverables_reply_is_discarded_after_session_selection_changes() = runBlocking {
        var selected = "first"
        val transport = ReviewTransport().apply { blockSummary = true }
        val controller = NativeHarnessProjectReviewController(
            scope = CoroutineScope(SupervisorJob()),
            clientProvider = { null },
            agentIdProvider = { selected },
            transportProvider = { transport },
        )
        val coordinates = NativeHarnessProjectReviewCoordinates("first", 8, 4)
        val request = async { controller.loadChanges(coordinates) }
        transport.summaryStarted.await()
        selected = "second"
        transport.summaryGate.complete(Unit)

        assertFalse(request.await())
        assertNull(controller.state.value.changes)
        controller.close()
    }

    @Test
    fun selecting_another_session_clears_previous_review_state() {
        val selected = "first"
        val controller = NativeHarnessProjectReviewController(
            scope = CoroutineScope(SupervisorJob()),
            clientProvider = { null },
            agentIdProvider = { selected },
            transportProvider = { null },
        )
        assertTrue(controller.applyHistory("first", emptyList(), closed = true))
        controller.selectSession("second")
        assertEquals("second", controller.state.value.agentId)
        assertTrue(controller.state.value.presentedFiles.isEmpty())
        controller.selectSession(null)
        assertNull(controller.state.value.agentId)
        controller.close()
    }

    private fun event(type: String, data: String): JsonObject = buildJsonObject {
        put("type", type)
        put("data", Json.parseToJsonElement(data))
    }

    private class HistoryClient : HarnessClient {
        override val state = MutableStateFlow(HarnessConnectionState.READY)
        var pageRequest: JsonObject? = null
        val listPages = mutableMapOf<String?, JsonObject>()
        val listCursors = mutableListOf<String?>()
        var pageRecords: List<JsonObject> = listOf(buildJsonObject {
            put("type", "tool-workflow/run-start")
            putJsonObject("data") {
                put("runId", "r")
                put("name", "Run")
            }
        })

        override suspend fun call(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessCallPolicy,
            requestId: String,
        ): HarnessRpcResult {
            if (namespace == "session" && method == "list") {
                val request = args["_request"] as? JsonObject
                val cursor = request?.string("cursor")
                listCursors += cursor
                return HarnessRpcResult.Success(
                    listPages[cursor] ?: buildJsonObject { putJsonArray("items") {} },
                )
            }
            if (namespace == "session" && method == "page") {
                pageRequest = args["request"] as? JsonObject
                return HarnessRpcResult.Success(buildJsonObject {
                    putJsonArray("records") { pageRecords.forEach { add(it) } }
                })
            }
            return HarnessRpcResult.Success(JsonNull)
        }

        override fun stream(namespace: String, method: String, args: JsonObject, policy: HarnessStreamPolicy): Flow<JsonElement> = emptyFlow()
        override suspend fun authenticate(launchUrl: String): HarnessAuthResult = HarnessAuthResult.Success("http://127.0.0.1")
        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult = HarnessAuthResult.Success(origin)
        override fun close() = Unit
    }

    private class ReviewTransport : NativeHarnessProjectReviewTransport {
        var lastPath: String? = null
        var lastQuery: Map<String, String> = emptyMap()
        var lastReadSession: String? = null
        var lastReadOffset: Int? = null
        var blockSummary: Boolean = false
        val summaryStarted = CompletableDeferred<Unit>()
        val summaryGate = CompletableDeferred<Unit>()

        override suspend fun get(path: String, query: Map<String, String>): JsonElement {
            lastPath = path
            lastQuery = query
            return if (path == NativeHarnessProjectReviewWire.CHANGES_SUMMARY_PATH) {
                if (blockSummary) {
                    summaryStarted.complete(Unit)
                    summaryGate.await()
                }
                Json.parseToJsonElement("""{"turn":4,"files":[{"path":"a","display":"a","added":1,"deleted":0}],"total":1,"added":1,"deleted":0}""")
            } else {
                Json.parseToJsonElement("""{"kind":"binary","path":"a","display":"a"}""")
            }
        }

        override suspend fun post(path: String, query: Map<String, String>, body: JsonObject): JsonElement {
            lastPath = path
            lastQuery = query
            return JsonNull
        }

        override suspend fun readWorkspaceFile(sessionId: String, path: String, offset: Int, limit: Int): JsonElement {
            lastReadSession = sessionId
            lastReadOffset = offset
            return Json.parseToJsonElement("""{"absolutePath":"/workspace/$path","version":"v1","offset":$offset,"text":"hello","lines":1,"eof":true}""")
        }
    }
}
