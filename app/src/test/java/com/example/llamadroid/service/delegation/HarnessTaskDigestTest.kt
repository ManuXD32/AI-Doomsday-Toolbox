package com.example.llamadroid.service.delegation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTaskDigestTest {
    private fun event(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test fun foldsTurnLifecycleAndAssistantText() {
        var d = HarnessTaskDigest()
        d = d.foldEvent(event("""{"seq":1,"type":"turn/start","data":{"turn":1}}"""))
        assertEquals(HarnessDigestState.WORKING, d.state)
        d = d.foldEvent(event("""{"seq":2,"type":"tool/call","data":{"callId":"c1","name":"bash"}}"""))
        d = d.foldEvent(event("""{"seq":3,"type":"tool/call","data":{"callId":"c2","name":"bash"}}"""))
        d = d.foldEvent(event("""{"seq":4,"type":"assistant/message","data":{"message":{"id":"m","content":"All done here"}}}"""))
        d = d.foldEvent(event("""{"seq":5,"type":"turn/end","data":{"turn":1}}"""))
        assertEquals(HarnessDigestState.IDLE, d.state)
        assertEquals(1, d.turnCount)
        assertEquals(2, d.toolCounts["bash"])
        assertEquals(2, d.toolCallsTotal)
        assertEquals("All done here", d.lastAssistant)
        assertTrue(d.render().contains("bash×2"))
        assertTrue(d.markRendered().toolCounts.isEmpty())
    }

    @Test fun replayedEventsAreIgnored() {
        val e = event("""{"seq":7,"type":"tool/call","data":{"name":"write"}}""")
        val d = HarnessTaskDigest().foldEvent(e).foldEvent(e)
        assertEquals(1, d.toolCallsTotal)
    }

    @Test fun errorTurnEndSetsErrorState() {
        val d = HarnessTaskDigest()
            .foldEvent(event("""{"seq":1,"type":"turn/start","data":{"turn":1}}"""))
            .foldEvent(event("""{"seq":2,"type":"turn/end","data":{"reason":{"kind":"error","message":"boom"}}}"""))
        assertEquals(HarnessDigestState.ERROR, d.state)
        assertTrue(d.errors.single().contains("boom"))
    }

    @Test fun turnEndKeepsMessageAndProviderCode() {
        val failed = HarnessTaskDigest()
            .foldEvent(event("""{"seq":1,"type":"turn/start","data":{"turn":1}}"""))
            .foldEvent(event("""{"seq":2,"type":"llm/retry","data":{"code":"PROVIDER_INTERRUPTED"}}"""))
            .foldEvent(event("""{"seq":3,"type":"turn/end","data":{"reason":{"kind":"error","message":"ADT provider request failed","code":"CONTEXT_WINDOW_EXCEEDED"}}}"""))
        assertTrue(failed.errors.last().contains("ADT provider request failed (CONTEXT_WINDOW_EXCEEDED)"))
        // The earlier retry is counted, not listed as an error.
        assertEquals(1, failed.errors.size)
        assertEquals(1, failed.modelRetries)
        assertEquals("CONTEXT_WINDOW_EXCEEDED", failed.providerFailureCode())
        assertEquals("CONTEXT_WINDOW_EXCEEDED", HarnessTaskDigest.fromJson(failed.toJson())?.providerFailureCode())
        val recovered = failed.foldEvent(event("""{"seq":4,"type":"turn/start","data":{"turn":2}}"""))
        assertNull(recovered.providerFailureCode())
    }

    @Test fun recoveredRetryIsNotAnError() {
        val d = HarnessTaskDigest()
            .foldEvent(event("""{"seq":1,"type":"turn/start","data":{"turn":1}}"""))
            .foldEvent(event("""{"seq":2,"type":"llm/retry","data":{"code":"TRANSPORT"}}"""))
            .foldEvent(event("""{"seq":3,"type":"turn/end","data":{"reason":{"kind":"done"}}}"""))
        assertTrue(d.errors.isEmpty())
        assertEquals(HarnessDigestState.IDLE, d.state)
        assertTrue(d.render().contains("Retried"))
        assertEquals(1, HarnessTaskDigest.fromJson(d.toJson())?.modelRetries)
    }

    @Test fun providerFailureCodeFallsBackToMessageAndIgnoresOtherErrors() {
        val msgOnly = HarnessTaskDigest()
            .foldEvent(event("""{"seq":1,"type":"turn/end","data":{"reason":{"kind":"error","message":"ADT provider request failed"}}}"""))
        assertEquals("PROVIDER_INTERRUPTED", msgOnly.providerFailureCode())
        val other = HarnessTaskDigest()
            .foldEvent(event("""{"seq":1,"type":"turn/end","data":{"reason":{"kind":"error","message":"boom"}}}"""))
        assertNull(other.providerFailureCode())
    }

    @Test fun foldsDeliverablesChangesAndCompactions() {
        var d = HarnessTaskDigest()
        d = d.foldEvent(event("""{"seq":1,"type":"deliverables/presented","data":{"turn":1,"files":[{"path":"out/report.pdf","description":"Final"},{"path":"out/report.pdf"}]}}"""))
        d = d.foldEvent(event("""{"seq":2,"type":"workspace/changes","data":{"turn":1}}"""))
        d = d.foldEvent(event("""{"seq":3,"type":"compaction/end","data":{}}"""))
        assertEquals(1, d.deliverables.size)
        assertEquals(2L, d.changesSeq)
        assertEquals(1, d.changesTurn)
        assertEquals(1, d.compactions)
    }

    @Test fun snapshotProjectionsGiveContextAndTodos() {
        val frame = Json.parseToJsonElement("""{"type":"snapshot","records":[],"projections":{"values":{
            "contextPressure":{"projectedTokens":6100,"contextWindow":10000},
            "todos":[{"content":"write tests","status":"in_progress"}]}}}""")
        val d = HarnessTaskDigest().foldFrame(frame)
        assertEquals(61, d.contextPercent)
        assertEquals(1, d.todos.size)
        assertTrue(d.render().contains("ctx 61% of 10k"))
    }

    @Test fun pendingItemsRenderFirstAndSurviveTruncation() {
        val d = HarnessTaskDigest(
            running = true, turnCount = 4, threadIndex = 1, threadCount = 2,
            pending = listOf(
                HarnessDigestPending("approval", "ap-3", "Run: npm install"),
                HarnessDigestPending("question", "q-1", "Which DB?", listOf("sqlite", "postgres")),
            ),
            errors = listOf("e".repeat(500), "f".repeat(500)),
            lastAssistant = "x".repeat(4000),
            changedFiles = (1..20).map { HarnessDigestFile("src/file$it.kt", it, 0) }, changedTotal = 25,
            toolCounts = mapOf("bash" to 5, "write" to 3),
        ).withElapsed(12 * 60_000L)
        val full = d.render()
        assertTrue(full.length <= 2400)
        assertTrue(full.startsWith("[digest v1] thread 1/2 · state=WAITING_APPROVAL · turn 4 · 12m"))
        assertTrue(full.contains("Pending: approval ap-3 \"Run: npm install\" | question q-1 \"Which DB?\" [sqlite|postgres]"))
        val tiny = d.render(300)
        assertTrue(tiny.length <= 300)
        assertTrue(tiny.contains("Pending:"))
        assertFalse(tiny.contains("Tools since last"))
    }

    @Test fun hashIgnoresElapsedButTracksContent() {
        val a = HarnessTaskDigest(turnCount = 1).withElapsed(1000)
        val b = a.withElapsed(99_000)
        assertEquals(a.hash(), b.hash())
        assertNotEquals(a.hash(), a.copy(lastAssistant = "new").hash())
        assertNotEquals(a.hash(), a.copy(toolCallsTotal = 1).hash())
    }

    @Test fun jsonRoundTripAndSizeCap() {
        val d = HarnessTaskDigest(
            threadIndex = 2, threadCount = 3, running = true, turnCount = 9, ctxUsedTokens = 100, ctxWindowTokens = 1000,
            pending = listOf(HarnessDigestPending("question", "q", "Which?", listOf("a", "b"))),
            errors = listOf("bad"), lastAssistant = "hello",
            changedFiles = listOf(HarnessDigestFile("a.kt", 1, 2)), changedTotal = 1,
            deliverables = listOf(HarnessDigestDeliverable("out.pdf", "x")), compactions = 2,
            todos = listOf(HarnessDigestTodo("t", "pending")), lastSeq = 42, changesSeq = 40, changesTurn = 3,
        )
        val back = HarnessTaskDigest.fromJson(d.toJson())
        assertNotNull(back)
        assertEquals(d.copy(toolCounts = emptyMap()), back)
        val big = d.copy(lastAssistant = "z".repeat(4000), changedFiles = (1..20).map { HarnessDigestFile("p".repeat(200) + it, 1, 1) })
        assertTrue(big.toJson().length <= HarnessTaskDigest.MAX_JSON_CHARS)
        assertNull(HarnessTaskDigest.fromJson("not json"))
        assertNull(HarnessTaskDigest.fromJson(null))
    }
}
