package com.example.llamadroid.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stable Audio bundles share multi-hundred-megabyte LiteRT components. Keep
 * their network transfers on one cancellable lane so two bundle selections do
 * not compete for the same large shared payload or exhaust device storage.
 * The durable DownloadService task remains the owner of resume/cancel state.
 */
internal object StableAudioDownloadCoordinator {
    private val transferMutex = Mutex()

    suspend fun <T> withTransferLock(block: suspend () -> T): T =
        transferMutex.withLock { block() }
}
