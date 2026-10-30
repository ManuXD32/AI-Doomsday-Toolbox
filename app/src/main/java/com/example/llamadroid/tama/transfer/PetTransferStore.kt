package com.example.llamadroid.tama.transfer

import com.example.llamadroid.tama.db.TamaTransferStateDao
import com.example.llamadroid.tama.db.TamaTransferStateEntity
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Maps the persisted single-row transfer state to/from [TransferState]. */
class PetTransferStore(
    private val dao: TamaTransferStateDao,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun get(): TransferState? {
        val e = dao.get() ?: return null
        val role = runCatching { TransferRole.valueOf(e.role) }.getOrNull() ?: return null
        val status = runCatching { TransferStatus.valueOf(e.status) }.getOrNull() ?: return null
        return TransferState(
            role, status, e.transferId, e.petId, e.petName, e.peerName, e.originDeviceId,
            e.archivePath, e.pausedPetId, e.pausedArchivePath, since = e.updatedAt
        )
    }

    /** Persists [state]; null clears the row. */
    suspend fun save(state: TransferState?) {
        if (state == null) {
            dao.clear()
            return
        }
        dao.upsert(
            TamaTransferStateEntity(
                role = state.role.name, status = state.status.name, transferId = state.transferId,
                petId = state.petId, petName = state.petName, peerName = state.peerName,
                originDeviceId = state.originDeviceId, archivePath = state.archivePath,
                pausedPetId = state.pausedPetId, pausedArchivePath = state.pausedArchivePath,
                updatedAt = clock()
            )
        )
    }
}

/** On-disk layout under `filesDir/tama_transfer`. */
class PetTransferPaths(val root: File) {
    val incomingDir: File get() = File(root, "incoming").apply { mkdirs() }
    val outboundDir: File get() = File(root, "outbound").apply { mkdirs() }
    val pausedDir: File get() = File(root, "paused").apply { mkdirs() }
    private val completedFile: File get() = File(root, "completed.txt")

    fun part(transferId: String) = File(incomingDir, "$transferId.part")
    fun incoming(transferId: String) = File(incomingDir, "$transferId.zip")
    fun outbound(transferId: String) = File(outboundDir, "$transferId.zip")
    fun paused(petId: String) = File(pausedDir, "$petId.zip")

    /** Remembers committed transfer ids so /status stays correct after the row is cleared. */
    @Synchronized
    fun markCompleted(transferId: String) {
        root.mkdirs()
        val ids = (completedIds() + transferId).takeLast(MAX_COMPLETED)
        completedFile.writeText(ids.joinToString("\n"))
    }

    @Synchronized
    fun isCompleted(transferId: String): Boolean = completedIds().contains(transferId)

    private fun completedIds(): List<String> =
        if (completedFile.exists()) completedFile.readLines().filter { it.isNotBlank() } else emptyList()

    private companion object {
        const val MAX_COMPLETED = 64
    }
}

internal fun File.sha256Hex(): String = inputStream().use { it.sha256Hex() }

internal fun InputStream.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
