package com.example.llamadroid.service.chat

import com.example.llamadroid.service.ram.LlamaRamEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAdmissionControllerTest {
    private fun endpoint(chat: Long, key: String = "http://127.0.0.1:8080", capacity: Int = 1, ramBytes: Long = 0L) =
        AdmissionTarget(
            chatId = chat, kind = AdmissionKind.ENDPOINT, resourceKey = key, label = "srv", capacity = capacity,
            ramEstimate = if (ramBytes > 0L) LlamaRamEstimate(ramBytes, 0, 0, 0, 0, true) else null
        )

    private fun liteRt(chat: Long) = AdmissionTarget(chat, AdmissionKind.ENGINE, "litert", "LiteRT")

    private fun unlimited(chat: Long) = AdmissionTarget(chat, AdmissionKind.UNLIMITED, "ollama", "Ollama", Int.MAX_VALUE)

    @Test fun sameEndpointStartsWhileSlotsAreFree() {
        val controller = ChatAdmissionController()
        assertEquals(AdmissionDecision.Start, controller.request(endpoint(1, capacity = 2)))
        assertEquals(AdmissionDecision.Start, controller.request(endpoint(2, capacity = 2)))
        assertEquals(
            AdmissionDecision.Queued(AdmissionWait.SLOT, 1),
            controller.request(endpoint(3, capacity = 2))
        )
        assertEquals(
            AdmissionDecision.Queued(AdmissionWait.SLOT, 2),
            controller.request(endpoint(4, capacity = 2))
        )
    }

    @Test fun differentEndpointsDoNotShareSlots() {
        val controller = ChatAdmissionController()
        assertEquals(AdmissionDecision.Start, controller.request(endpoint(1, key = "a")))
        assertEquals(AdmissionDecision.Start, controller.request(endpoint(2, key = "b")))
    }

    @Test fun waitersStartFifoWhenASessionEnds() {
        val controller = ChatAdmissionController()
        controller.request(endpoint(1, capacity = 2))
        controller.request(endpoint(2, capacity = 2))
        controller.request(endpoint(3, capacity = 2))
        controller.request(endpoint(4, capacity = 2))
        assertEquals(listOf(3L), controller.finish(1).map { it.chatId })
        assertEquals(listOf(4L), controller.finish(2).map { it.chatId })
        assertTrue(controller.finish(3).isEmpty())
        assertEquals(1, controller.activeCount("http://127.0.0.1:8080"))
    }

    @Test fun aLaterRequestNeverOvertakesAnEarlierWaiter() {
        val controller = ChatAdmissionController()
        controller.request(endpoint(1))
        controller.request(endpoint(2))
        // A slot frees up, but chat 2 is ahead of chat 3.
        assertEquals(listOf(2L), controller.finish(1).map { it.chatId })
        assertEquals(AdmissionDecision.Queued(AdmissionWait.SLOT, 1), controller.request(endpoint(3)))
    }

    @Test fun duplicateRequestsAreIdempotent() {
        val controller = ChatAdmissionController()
        assertEquals(AdmissionDecision.Start, controller.request(endpoint(1)))
        assertEquals(AdmissionDecision.Start, controller.request(endpoint(1)))
        controller.request(endpoint(2))
        assertEquals(AdmissionDecision.Queued(AdmissionWait.SLOT, 1), controller.request(endpoint(2)))
        assertEquals(1, controller.queuedSnapshot().size)
    }

    @Test fun liteRtRunsOneAtATime() {
        val controller = ChatAdmissionController()
        assertEquals(AdmissionDecision.Start, controller.request(liteRt(1)))
        assertEquals(AdmissionDecision.Queued(AdmissionWait.ENGINE, 1), controller.request(liteRt(2)))
        assertEquals(AdmissionDecision.Queued(AdmissionWait.ENGINE, 2), controller.request(liteRt(3)))
        assertEquals(listOf(2L), controller.finish(1).map { it.chatId })
    }

    @Test fun ollamaAndRemoteServersRunInParallel() {
        val controller = ChatAdmissionController()
        repeat(5) { assertEquals(AdmissionDecision.Start, controller.request(unlimited(it + 1L))) }
        assertTrue(controller.queuedSnapshot().isEmpty())
    }

    @Test fun notRunningModelWaitsForRamAndStartsOnRecheck() {
        var fits = false
        val controller = ChatAdmissionController(ramFits = { _, _ -> fits })
        // Another local model is generating, so waiting for its memory makes sense.
        assertEquals(AdmissionDecision.Start, controller.request(endpoint(9, key = "card:1")))
        assertEquals(
            AdmissionDecision.Queued(AdmissionWait.RAM, 1),
            controller.request(endpoint(1, key = "card:7", ramBytes = 100))
        )
        assertTrue(controller.hasRamWaiters())
        assertTrue(controller.recheck().isEmpty())
        fits = true
        assertEquals(listOf(1L), controller.recheck().map { it.chatId })
        assertFalse(controller.hasRamWaiters())
    }

    @Test fun ramGateOnlyAppliesWhileAnotherLocalModelIsActive() {
        val controller = ChatAdmissionController(ramFits = { _, _ -> false })
        // Nothing of ours to wait for: start as before (Android manages memory).
        assertEquals(AdmissionDecision.Start, controller.request(endpoint(1, key = "card:7", ramBytes = 100)))
        assertEquals(
            AdmissionDecision.Queued(AdmissionWait.RAM, 1),
            controller.request(endpoint(2, key = "card:8", ramBytes = 100))
        )
        // The other model's chat ends: the waiter starts even though the estimate is still tight.
        assertEquals(listOf(2L), controller.finish(1).map { it.chatId })
    }

    @Test fun ramReservationCountsServersThatAreStillStarting() {
        val controller = ChatAdmissionController(ramFits = { target, reserved -> reserved + target.ramNeedBytes <= 100 })
        assertEquals(AdmissionDecision.Start, controller.request(endpoint(1, key = "card:1", ramBytes = 60)))
        // The first server is not up yet, so its 60 bytes are reserved.
        assertEquals(
            AdmissionDecision.Queued(AdmissionWait.RAM, 1),
            controller.request(endpoint(2, key = "card:2", ramBytes = 60))
        )
        assertTrue(controller.recheck().isEmpty())
        // Once it answers, the real free memory (not the reservation) is what the gate reads.
        assertEquals(listOf(2L), controller.markServerUp(1).map { it.chatId })
    }

    @Test fun secondChatOnARunningOrStartingServerSkipsTheRamGate() {
        val controller = ChatAdmissionController(ramFits = { _, _ -> false })
        assertEquals(AdmissionDecision.Start, controller.request(endpoint(9, key = "card:9")))
        // First chat fails the gate, so nothing runs; a server that is already active skips it.
        val allow = ChatAdmissionController(ramFits = { _, reserved -> reserved == 0L })
        assertEquals(AdmissionDecision.Start, allow.request(endpoint(1, key = "card:1", capacity = 2, ramBytes = 50)))
        assertEquals(AdmissionDecision.Start, allow.request(endpoint(2, key = "card:1", capacity = 2, ramBytes = 50)))
        assertEquals(
            AdmissionDecision.Queued(AdmissionWait.RAM, 1),
            controller.request(endpoint(3, key = "card:3", ramBytes = 50))
        )
    }

    @Test fun cancelRemovesAQueuedChatAndRenumbersTheRest() {
        val controller = ChatAdmissionController()
        controller.request(endpoint(1))
        controller.request(endpoint(2))
        controller.request(endpoint(3))
        controller.request(endpoint(4))
        val cancelled = controller.cancel(2)
        assertTrue(cancelled.removed)
        assertTrue(cancelled.started.isEmpty())
        assertEquals(listOf(3L to 1, 4L to 2), controller.queuedSnapshot().map { it.chatId to it.position })
        assertFalse(controller.cancel(1).removed) // running chats are stopped, not cancelled
        assertFalse(controller.cancel(99).removed)
        assertEquals(listOf(3L), controller.finish(1).map { it.chatId })
    }

    @Test fun finishingAQueuedChatDropsItWithoutStartingAnything() {
        val controller = ChatAdmissionController()
        controller.request(endpoint(1))
        controller.request(endpoint(2))
        assertTrue(controller.finish(2).isEmpty())
        assertFalse(controller.isQueued(2))
        assertTrue(controller.isActive(1))
    }

    @Test fun queuedSnapshotReportsPositionPerResource() {
        val controller = ChatAdmissionController()
        controller.request(endpoint(1, key = "a"))
        controller.request(endpoint(2, key = "b"))
        controller.request(endpoint(3, key = "a"))
        controller.request(endpoint(4, key = "b"))
        controller.request(endpoint(5, key = "a"))
        val byChat = controller.queuedSnapshot().associate { it.chatId to it.position }
        assertEquals(mapOf(3L to 1, 4L to 1, 5L to 2), byChat)
    }
}
