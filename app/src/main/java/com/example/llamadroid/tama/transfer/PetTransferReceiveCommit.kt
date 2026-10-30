package com.example.llamadroid.tama.transfer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Receiver-side commit shared by the LAN server and the file fallback.
 *
 * Guest visit: pause the local pet to `paused/{petId}.zip` (verified), persist a RECEIVER row,
 * import the guest, then persist GUEST_HOST. Any failure restores the paused pet. A return at the
 * origin replaces the frozen copy and clears the row. Idempotent by transfer id.
 */
class PetTransferReceiveCommit(
    private val engine: PetTransferEngine,
    private val store: PetTransferStore,
    private val identity: DeviceIdentity,
    private val paths: PetTransferPaths
) {
    private val mutex = Mutex()

    /**
     * [permanent] is the file-import path: the received pet simply replaces the local one (the
     * paused copy only serves rollback and is deleted on success).
     */
    suspend fun commit(hello: Hello, zip: java.io.File, permanent: Boolean = false): CommitReply = mutex.withLock {
        if (paths.isCompleted(hello.transferId)) return@withLock CommitReply(true)
        val current = store.get()
        val guard = if (current?.role == TransferRole.RECEIVER && current.transferId == hello.transferId) {
            TransferGuard.OK // our own half-done commit being retried
        } else {
            PetTransferStateMachine.canReceive(current, hello, identity.deviceId())
        }
        if (!guard.allowed) return@withLock CommitReply(false, guard.reason)
        try {
            if (hello.isReturn) commitReturn(hello, zip, current) else commitGuest(hello, zip, current, permanent)
        } catch (e: Exception) {
            CommitReply(false, REASON_COMMIT_FAILED)
        }
    }

    private suspend fun commitReturn(hello: Hello, zip: java.io.File, current: TransferState?): CommitReply {
        // The import is one database transaction: a failure leaves the frozen copy untouched.
        if (!engine.importPetZip(zip, applySettings = false)) return CommitReply(false, REASON_IMPORT_FAILED)
        paths.markCompleted(hello.transferId)
        store.save(null)
        engine.setFrozen(false)
        current?.archivePath?.let { java.io.File(it).delete() }
        zip.delete()
        return CommitReply(true)
    }

    private suspend fun commitGuest(
        hello: Hello, zip: java.io.File, current: TransferState?, permanent: Boolean
    ): CommitReply {
        val local = engine.currentPetSummary()
        var pausedFile: java.io.File? = null
        if (local != null) {
            val target = paths.paused(local.petId)
            if (!engine.exportPetZip(target) || !engine.validateZip(target)) {
                target.delete()
                return CommitReply(false, REASON_PAUSE_FAILED)
            }
            pausedFile = target
        }
        val pausedPetId = local?.petId
        val receiving = TransferState(
            TransferRole.RECEIVER, TransferStatus.OUTBOUND_PENDING, hello.transferId, hello.petId,
            hello.petName, hello.senderDeviceName, if (permanent) "" else hello.originDeviceId, null,
            pausedPetId, pausedFile?.absolutePath
        )
        store.save(receiving)
        val imported = try {
            engine.importPetZip(zip, applySettings = false)
        } catch (e: Exception) {
            false
        }
        if (!imported) {
            rollbackGuest(pausedFile, pausedPetId, previous = current)
            return CommitReply(false, REASON_IMPORT_FAILED)
        }
        paths.markCompleted(hello.transferId)
        val next = if (permanent) null else {
            PetTransferStateMachine.receiverCommitted(
                current, hello, asGuest = true, asReturn = false,
                pausedPetId = pausedPetId, pausedArchivePath = pausedFile?.absolutePath
            )
        }
        store.save(next)
        if (permanent) pausedFile?.delete()
        engine.setFrozen(false)
        zip.delete()
        return CommitReply(true)
    }

    private suspend fun rollbackGuest(pausedFile: java.io.File?, pausedPetId: String?, previous: TransferState?) {
        if (pausedFile != null && pausedFile.exists()) {
            val restored = try {
                engine.importPetZip(pausedFile, applySettings = false)
            } catch (e: Exception) {
                false
            }
            // Keep the RECEIVER row and the archive when the restore fails: recovery retries it.
            if (!restored) return
            pausedFile.delete()
        }
        if (pausedPetId == null) {
            // The host had no pet; a failed import can leave nothing behind, but make sure.
            engine.currentPetSummary()?.let { engine.deletePetData(it.petId) }
        }
        store.save(previous?.takeUnless { it.role == TransferRole.RECEIVER })
    }

    /**
     * Resolve a RECEIVER row left by a crash: if the guest is already the current pet the import
     * finished (become GUEST_HOST); otherwise restore the paused pet.
     */
    suspend fun recoverInterrupted(row: TransferState) = mutex.withLock {
        val currentId = engine.currentPetSummary()?.petId
        val hello = Hello(
            row.transferId.orEmpty(), row.petId.orEmpty(), row.petName.orEmpty(), "", "", 0, "", 0,
            row.originDeviceId.orEmpty(), row.peerName.orEmpty()
        )
        if (row.originDeviceId.isNullOrEmpty()) {
            // File import: the outcome is ambiguous without the pet id, so keep the paused
            // archive (recoverable by hand) and just clear the row.
            store.save(null)
            engine.setFrozen(false)
            return@withLock
        }
        if (currentId != null && currentId == row.petId) {
            paths.markCompleted(hello.transferId)
            store.save(
                PetTransferStateMachine.receiverCommitted(
                    null, hello, asGuest = true, asReturn = false,
                    pausedPetId = row.pausedPetId, pausedArchivePath = row.pausedArchivePath
                )
            )
            engine.setFrozen(false)
            return@withLock
        }
        val paused = row.pausedArchivePath?.let { java.io.File(it) }
        if (paused != null && paused.exists()) {
            if (!engine.importPetZip(paused, applySettings = false)) return@withLock // retry next start
            paused.delete()
        }
        store.save(null)
        engine.setFrozen(false)
    }

    companion object {
        const val REASON_COMMIT_FAILED = "commit_failed"
        const val REASON_IMPORT_FAILED = "import_failed"
        const val REASON_PAUSE_FAILED = "pause_failed"
    }
}
