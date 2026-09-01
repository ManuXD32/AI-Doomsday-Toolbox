package com.example.llamadroid.service

import com.example.llamadroid.data.model.LlamaServerSessionIds
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaOcrRuntimeCoordinatorTest {
    private fun captured(
        sessionId: String,
        kind: LlamaOcrCapturedRuntimeKind,
        port: Int
    ) = LlamaOcrCapturedRuntime(
        sessionId = sessionId,
        kind = kind,
        port = port,
        launchProfileJson = LlamaServerLaunchProfile.encode(
            LlamaServerLaunchProfile(modelPath = "/models/$sessionId.gguf", serverPort = port)
        )
    )

    @Test
    fun `translation endpoint restores before remaining cards`() {
        val runtimes = listOf(
            captured("card:other", LlamaOcrCapturedRuntimeKind.CARD, 9090),
            captured("card:translation", LlamaOcrCapturedRuntimeKind.CARD, 8080),
            captured(LlamaServerSessionIds.GENERAL, LlamaOcrCapturedRuntimeKind.RESERVED, 8181)
        )

        assertEquals(
            listOf("card:translation", LlamaServerSessionIds.GENERAL, "card:other"),
            orderLlamaOcrRestoration(runtimes, translationPort = 8080).map { it.sessionId }
        )
    }

    @Test
    fun `legacy translation endpoint has first restore priority`() {
        val runtimes = listOf(
            captured("card:2", LlamaOcrCapturedRuntimeKind.CARD, 8082),
            captured("legacy:llama", LlamaOcrCapturedRuntimeKind.LEGACY, 8080),
            captured("card:1", LlamaOcrCapturedRuntimeKind.CARD, 8081)
        )

        assertEquals(
            "legacy:llama",
            orderLlamaOcrRestoration(runtimes, translationPort = 8080).first().sessionId
        )
    }

    @Test
    fun `stale active projection is not treated as a live runtime without an owner`() {
        val now = 1_000_000L
        val stale = LlamaServerSessionSnapshot(
            sessionId = "card:1",
            status = LlamaServerSessionStatus.RUNNING,
            port = 8080,
            updatedAt = now - 60_001L
        )
        val freshStarting = stale.copy(
            status = LlamaServerSessionStatus.STARTING,
            updatedAt = now - 1_000L
        )

        assertFalse(sessionSnapshotIsCapturable(stale, ownerAlive = false, now = now))
        assertTrue(sessionSnapshotIsCapturable(freshStarting, ownerAlive = false, now = now))
        assertTrue(sessionSnapshotIsCapturable(stale, ownerAlive = true, now = now))
    }

    @Test
    fun `twenty restoration order cycles remain deterministic and translation first`() {
        val runtimes = listOf(
            captured("card:b", LlamaOcrCapturedRuntimeKind.CARD, 8082),
            captured("card:a", LlamaOcrCapturedRuntimeKind.CARD, 8080),
            captured("card:c", LlamaOcrCapturedRuntimeKind.CARD, 8083)
        )

        repeat(20) {
            val ordered = orderLlamaOcrRestoration(runtimes.shuffled(kotlin.random.Random(it)), 8080)
            assertEquals(listOf("card:a", "card:b", "card:c"), ordered.map { runtime -> runtime.sessionId })
            assertEquals(ordered.size, ordered.map { runtime -> runtime.port }.distinct().size)
        }
    }

    @Test
    fun `ML Kit recovers runtime failures but not cancellation or preflight blocks`() {
        assertTrue(shouldRecoverLlamaOcrWithMlKit(IllegalStateException("server process died")))
        assertFalse(shouldRecoverLlamaOcrWithMlKit(CancellationException("cancelled")))
        assertFalse(
            shouldRecoverLlamaOcrWithMlKit(
                LlamaOcrRuntimeBlockedException("distributed runtime active")
            )
        )
    }
}
