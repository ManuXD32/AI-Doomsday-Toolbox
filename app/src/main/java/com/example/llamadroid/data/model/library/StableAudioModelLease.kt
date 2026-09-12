package com.example.llamadroid.data.model.library

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Cross-process lease for Stable Audio payloads.
 *
 * The worker process holds this lease for the complete verify/load/run window.
 * Model deletion takes the same lease before removing a payload, so a stale
 * replacement cannot race LiteRT's model mapping. Lock files are deliberately
 * retained: deleting a lock file while another process has it open would make
 * the coordination ineffective.
 */
internal object StableAudioModelLease {
    private val processLease = Mutex()

    suspend fun <T> withLease(paths: Iterable<String>, block: suspend () -> T): T =
        processLease.withLock {
            withContext(Dispatchers.IO) {
                val models = paths
                    .mapNotNull { path -> runCatching { File(path).canonicalFile }.getOrNull() }
                    .distinctBy { it.absolutePath }
                    .sortedBy { it.absolutePath }
                require(models.isNotEmpty()) { "stable_audio_model_lease_unavailable" }
                val channels = mutableListOf<FileChannel>()
                try {
                    models.forEach { model -> channels += openLock(model) }
                    block()
                } finally {
                    channels.asReversed().forEach { channel ->
                        runCatching { channel.close() }
                    }
                }
            }
        }

    private fun openLock(model: File): FileChannel {
        val parent = model.parentFile
            ?: throw IllegalStateException("stable_audio_model_lease_unavailable")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IllegalStateException("stable_audio_model_lease_unavailable")
        }
        val lockFile = File(parent, "${model.name}.stable-audio.lock")
        return try {
            RandomAccessFile(lockFile, "rw").channel.apply { lock() }
        } catch (error: Throwable) {
            throw IllegalStateException("stable_audio_model_lease_unavailable", error)
        }
    }
}
