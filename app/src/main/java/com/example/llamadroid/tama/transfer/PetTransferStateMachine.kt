package com.example.llamadroid.tama.transfer

/** In-memory view of the persisted `tama_transfer_state` row (null everywhere means "no row"). */
data class TransferState(
    val role: TransferRole,
    val status: TransferStatus,
    val transferId: String? = null,
    val petId: String? = null,
    val petName: String? = null,
    val peerName: String? = null,
    val originDeviceId: String? = null,
    /** Outbound zip (sender / origin) or the guest's return zip (guest host). */
    val archivePath: String? = null,
    val pausedPetId: String? = null,
    val pausedArchivePath: String? = null,
    /** Row `updatedAt` (epoch ms) when read from the store; ignored on save, 0 for unsaved states. */
    val since: Long = 0L
)

/** Guard result; [reason] is a stable machine code safe to send in [HelloReply.reason]. */
data class TransferGuard(val allowed: Boolean, val reason: String? = null) {
    companion object {
        val OK = TransferGuard(true)
        fun deny(reason: String) = TransferGuard(false, reason)
    }
}

/**
 * Pure transitions. Every function returns the next state to persist, or null when the row
 * must be cleared. No I/O so the crash-recovery rules are unit-testable.
 */
object PetTransferStateMachine {
    const val REASON_BUSY = "busy"
    const val REASON_ALREADY_HOSTING = "already_hosting"
    const val REASON_PET_AWAY = "pet_away"
    const val REASON_UNEXPECTED_RETURN = "unexpected_return"
    const val REASON_NOT_HOSTING = "not_hosting"

    // ---- Sender side ----

    fun senderPrepared(
        transferId: String, petId: String, petName: String, peerName: String?,
        originDeviceId: String, archivePath: String
    ) = TransferState(
        TransferRole.SENDER, TransferStatus.OUTBOUND_PENDING, transferId, petId, petName,
        peerName, originDeviceId, archivePath
    )

    /**
     * Receiver acknowledged the commit. A temporary visit leaves the sender as ORIGIN/AWAY so the
     * pet can come back; a permanent hand-off ([keepAway] false) clears the row.
     */
    fun senderCommitAcked(state: TransferState, keepAway: Boolean = true): TransferState? {
        if (state.role != TransferRole.SENDER || state.status != TransferStatus.OUTBOUND_PENDING) return state
        return if (keepAway) state.copy(role = TransferRole.ORIGIN, status = TransferStatus.AWAY) else null
    }

    /** Commit sent but no ack arrived: the receiver may or may not hold the pet. */
    fun senderCommitAckLost(state: TransferState): TransferState {
        if (state.status != TransferStatus.OUTBOUND_PENDING) return state
        return state.copy(status = TransferStatus.OUTCOME_UNKNOWN)
    }

    /** Resolve OUTCOME_UNKNOWN from the receiver's status answer. */
    fun senderStatusResolved(state: TransferState, receiverCommitted: Boolean, keepAway: Boolean = true): TransferState? {
        if (state.status != TransferStatus.OUTCOME_UNKNOWN) return state
        return when {
            !receiverCommitted && state.role == TransferRole.GUEST_HOST ->
                state.copy(status = TransferStatus.HOSTING_GUEST) // origin never got the guest: keep hosting
            !receiverCommitted -> null // receiver never committed: sender keeps the pet
            state.role == TransferRole.GUEST_HOST -> null // guest left, restore the paused pet
            keepAway -> state.copy(role = TransferRole.ORIGIN, status = TransferStatus.AWAY)
            else -> null
        }
    }

    /**
     * User gives up. Allowed only before the commit was sent; once the outcome is unknown the
     * receiver must be asked first (duplicating the pet is worse than waiting).
     */
    fun senderAbandon(state: TransferState): TransferState? =
        if (state.role == TransferRole.SENDER && state.status == TransferStatus.OUTBOUND_PENDING) null else state

    fun canAbandon(state: TransferState?): Boolean =
        state != null && state.role == TransferRole.SENDER && state.status == TransferStatus.OUTBOUND_PENDING

    // ---- Receiver side ----

    /**
     * Receiver committed the import. As a guest the host pauses its own pet and persists the
     * guest's return data; a return or permanent receive leaves nothing pending.
     */
    fun receiverCommitted(
        current: TransferState?, hello: Hello, asGuest: Boolean, asReturn: Boolean,
        pausedPetId: String? = null, pausedArchivePath: String? = null, returnArchivePath: String? = null
    ): TransferState? {
        if (asReturn) return null
        if (!asGuest) return null
        return TransferState(
            TransferRole.GUEST_HOST, TransferStatus.HOSTING_GUEST, hello.transferId, hello.petId,
            hello.petName, hello.senderDeviceName, hello.originDeviceId, returnArchivePath,
            pausedPetId ?: current?.pausedPetId, pausedArchivePath ?: current?.pausedArchivePath
        )
    }

    // ---- Guest host side ----

    /** Host starts sending the guest home. */
    fun guestReturnPrepared(state: TransferState, archivePath: String): TransferState {
        if (state.role != TransferRole.GUEST_HOST || state.status != TransferStatus.HOSTING_GUEST) return state
        return state.copy(status = TransferStatus.RETURN_PENDING, archivePath = archivePath)
    }

    /** Origin acked the return: the host restores its paused pet and clears the row. */
    fun guestReturnedAcked(state: TransferState): TransferState? =
        if (state.role == TransferRole.GUEST_HOST && state.status == TransferStatus.RETURN_PENDING) null else state

    fun guestReturnAckLost(state: TransferState): TransferState {
        if (state.role != TransferRole.GUEST_HOST || state.status != TransferStatus.RETURN_PENDING) return state
        return state.copy(status = TransferStatus.OUTCOME_UNKNOWN)
    }

    /** Return abandoned before commit, or the origin never got it: keep hosting. */
    fun guestReturnCancelled(state: TransferState): TransferState =
        if (state.role == TransferRole.GUEST_HOST &&
            (state.status == TransferStatus.RETURN_PENDING || state.status == TransferStatus.OUTCOME_UNKNOWN)
        ) state.copy(status = TransferStatus.HOSTING_GUEST) else state

    // ---- Guards ----

    /** Whether this device may accept an incoming [hello] given its persisted [current] state. */
    fun canReceive(current: TransferState?, hello: Hello, localDeviceId: String): TransferGuard {
        if (current == null) {
            return if (hello.isReturn) TransferGuard.deny(REASON_UNEXPECTED_RETURN) else TransferGuard.OK
        }
        return when (current.role) {
            TransferRole.ORIGIN ->
                if (hello.isReturn && hello.originDeviceId == localDeviceId && hello.petId == current.petId) {
                    TransferGuard.OK
                } else if (hello.isReturn) {
                    TransferGuard.deny(REASON_UNEXPECTED_RETURN)
                } else {
                    TransferGuard.deny(REASON_PET_AWAY)
                }
            TransferRole.GUEST_HOST -> TransferGuard.deny(REASON_ALREADY_HOSTING)
            TransferRole.SENDER, TransferRole.RECEIVER -> TransferGuard.deny(REASON_BUSY)
        }
    }

    /** A new outgoing transfer needs a free device. */
    fun canSend(current: TransferState?): TransferGuard = when {
        current == null -> TransferGuard.OK
        current.role == TransferRole.ORIGIN -> TransferGuard.deny(REASON_PET_AWAY)
        current.role == TransferRole.GUEST_HOST -> TransferGuard.deny(REASON_ALREADY_HOSTING)
        else -> TransferGuard.deny(REASON_BUSY)
    }

    /** The guest can be sent home only while actively hosting. */
    fun canReturnGuest(current: TransferState?): TransferGuard =
        if (current?.role == TransferRole.GUEST_HOST && current.status == TransferStatus.HOSTING_GUEST) {
            TransferGuard.OK
        } else {
            TransferGuard.deny(REASON_NOT_HOSTING)
        }
}
