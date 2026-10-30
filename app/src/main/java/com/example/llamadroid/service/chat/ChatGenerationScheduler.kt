package com.example.llamadroid.service.chat

import com.example.llamadroid.service.LlamaClientService.GenerationState
import com.example.llamadroid.service.LlamaClientService.QueueReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Suspends a chat's generation until [ChatAdmissionController] admits it, publishing
 * [GenerationState.Queued] while it waits. Re-checks free RAM every [RAM_RECHECK_MS] while any
 * chat waits for memory (sessions ending trigger an immediate re-check through [finish]).
 */
internal class ChatGenerationScheduler(
    private val scope: CoroutineScope,
    val controller: ChatAdmissionController,
    private val publish: (GenerationState) -> Unit
) {
    private val gates = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private var ticker: Job? = null

    /** Returns when [target] may run; throws CancellationException if the caller is cancelled. */
    suspend fun awaitAdmission(target: AdmissionTarget) {
        val gate = CompletableDeferred<Unit>()
        gates[target.chatId] = gate
        when (controller.request(target)) {
            AdmissionDecision.Start -> {
                gates.remove(target.chatId)
                return
            }
            is AdmissionDecision.Queued -> {
                publishQueue()
                ensureTicker()
                try {
                    gate.await()
                } finally {
                    gates.remove(target.chatId)
                }
            }
        }
    }

    fun markServerUp(chatId: Long) = admitted(controller.markServerUp(chatId))

    /** Call when a session ends (or is cancelled while queued): frees its place and starts waiters. */
    fun finish(chatId: Long) {
        gates.remove(chatId)
        admitted(controller.finish(chatId))
    }

    fun isQueued(chatId: Long): Boolean = controller.isQueued(chatId)

    private fun admitted(started: List<AdmissionTarget>) {
        started.forEach { gates[it.chatId]?.complete(Unit) }
        publishQueue()
    }

    private fun publishQueue() {
        controller.queuedSnapshot().forEach { queued ->
            publish(
                GenerationState.Queued(
                    chatId = queued.chatId,
                    reason = when (queued.reason) {
                        AdmissionWait.SLOT -> QueueReason.SLOT
                        AdmissionWait.RAM -> QueueReason.RAM
                        AdmissionWait.ENGINE -> QueueReason.ENGINE
                    },
                    position = queued.position,
                    resourceLabel = queued.label
                )
            )
        }
    }

    @Synchronized
    private fun ensureTicker() {
        if (ticker?.isActive == true || !controller.hasRamWaiters()) return
        ticker = scope.launch {
            while (controller.hasRamWaiters()) {
                delay(RAM_RECHECK_MS)
                admitted(controller.recheck())
            }
        }
    }

    companion object {
        const val RAM_RECHECK_MS = 15_000L
    }
}
