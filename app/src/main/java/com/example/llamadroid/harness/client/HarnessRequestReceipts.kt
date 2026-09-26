package com.example.llamadroid.harness.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.security.MessageDigest

/** Bounded, process-local coalescing. Ambiguous mutations are never automatically replayed. */
internal class HarnessRequestReceipts {
    private data class Receipt(val fingerprint: String, val result: CompletableDeferred<HarnessRpcResult>)
    private val receipts = LinkedHashMap<String, Receipt>()

    suspend fun execute(id: String, request: String, action: suspend () -> HarnessRpcResult): HarnessRpcResult {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(request.toByteArray()).toList().toString()
        var ownsRequest = false
        val receipt = synchronized(receipts) {
            val existing = receipts[id]
            if (existing != null && existing.fingerprint != fingerprint) return failure("client/request-id-reused")
            existing ?: run {
                if (receipts.size >= 128) {
                    val iterator = receipts.entries.iterator()
                    while (iterator.hasNext() && receipts.size >= 128) {
                        if (iterator.next().value.result.isCompleted) iterator.remove()
                    }
                }
                if (receipts.size >= 128) return failure("client/too-many-requests")
                ownsRequest = true
                Receipt(fingerprint, CompletableDeferred()).also { receipts[id] = it }
            }
        }
        if (!ownsRequest) return receipt.result.await()
        return try {
            val result = action()
            // Large attachment/history replies remain one-shot; their receipts still prevent a replay.
            val retained = if (result is HarnessRpcResult.Success && result.value.toString().length > 64 * 1024)
                failure("client/result-not-retained") else result
            receipt.result.complete(retained)
            result
        } catch (cancelled: CancellationException) {
            receipt.result.complete(failure("client/request-cancelled"))
            throw cancelled
        } catch (error: Exception) {
            receipt.result.complete(failure("client/request-interrupted"))
            throw error
        }
    }

    fun clear() = synchronized(receipts) {
        receipts.values.forEach { it.result.complete(failure("client/connection-changed")) }
        receipts.clear()
    }

    private fun failure(code: String) = HarnessRpcResult.Failure(HarnessRpcError(code, code))
}
