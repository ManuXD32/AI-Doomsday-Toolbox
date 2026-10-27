package com.example.llamadroid.tama.transfer

import com.example.llamadroid.tama.db.TamaTransferStateDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide "pet is away / hand-off pending" switch shared by every [com.example.llamadroid.tama.game.TamaGameEngine]
 * instance (app UI, watch gateway, Shimeji overlay and chat). The durable truth is the transfer
 * row; [sync] re-derives the flag from it so a restarted process, or an engine created while the
 * pet is away, agrees with the database.
 *
 * Ordering contract used by the coordinator: freeze first, then persist (a crash in between
 * loses only the in-memory flag and the DB shows no transfer, so the pet is intact and playable);
 * persist first, then unfreeze (a crash in between leaves the DB unfrozen, the same end state).
 */
object TamaTransferFreeze {
    private val _frozen = MutableStateFlow(false)
    val flow: StateFlow<Boolean> = _frozen.asStateFlow()

    /** Explicit freeze requested by the coordinator, e.g. while exporting before the row exists. */
    @Volatile
    private var held = false

    val frozen: Boolean get() = _frozen.value

    /**
     * Frozen when the local pet is not freely playable:
     * - SENDER / ORIGIN (any status: OUTBOUND_PENDING, OUTCOME_UNKNOWN, AWAY): the pet is leaving,
     *   may already live on the peer, or is away on a visit.
     * - GUEST_HOST while not HOSTING_GUEST (RETURN_PENDING, OUTCOME_UNKNOWN): the guest is being sent home.
     *   While HOSTING_GUEST the guest is a normal playable pet.
     * - RECEIVER and no row: not frozen.
     */
    fun isFrozenStatus(state: TransferState?): Boolean = when (state?.role) {
        null -> false
        TransferRole.SENDER, TransferRole.ORIGIN -> true
        TransferRole.GUEST_HOST -> state.status != TransferStatus.HOSTING_GUEST
        TransferRole.RECEIVER -> false
    }

    /** Sets the explicit hold and the flag together (coordinator / [TamaGameEnginePetTransferEngine]). */
    fun set(value: Boolean) {
        held = value
        _frozen.value = value
    }

    /** Re-reads the single transfer row; an explicit hold keeps the flag raised until released. */
    suspend fun sync(dao: TamaTransferStateDao) {
        _frozen.value = held || isFrozenStatus(PetTransferStore(dao).get())
    }

    /** Test hook: back to the cold-start state. */
    internal fun resetForTest() {
        held = false
        _frozen.value = false
    }
}
