package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.world.persistence.TamaWorldQuestCompletionPresentation

/**
 * Narrow seam around the existing engine. Implementations must call the
 * engine's locked methods; they must not call public methods that enqueue a
 * second world action.
 */
internal interface TamaParkWorldActionPort {
    suspend fun refreshFromCanonicalStore()
    fun currentPet(): TamaPet?
    suspend fun questNpcIdLocked(questId: String): String?
    suspend fun acceptQuestLocked(questId: String, now: Long): TamaParkLegacyOutcome
    suspend fun finishQuestLocked(questId: String, now: Long): TamaParkLegacyOutcome
    suspend fun acceptRecyclerLocked(now: Long, dateKey: String): TamaParkLegacyOutcome
    suspend fun finishRecyclerLocked(): TamaParkLegacyOutcome
    suspend fun declineRecyclerLocked(now: Long, dateKey: String): TamaParkLegacyOutcome
    suspend fun acceptSellerLocked(): TamaParkLegacyOutcome
    suspend fun sellerSaleLocked(itemId: String, quantity: Int): TamaParkLegacyOutcome
    suspend fun declineSellerLocked(): TamaParkLegacyOutcome
    suspend fun finishSellerLocked(): TamaParkLegacyOutcome
}

internal data class TamaParkLegacyOutcome(
    val success: Boolean,
    val message: String,
    val errorCode: String? = null,
    val questPresentation: TamaWorldQuestCompletionPresentation? = null
)

/** Production port; all methods are called while the controller gate/Room transaction is held. */
internal class TamaGameEngineParkWorldActionPort(
    private val engine: TamaGameEngine
) : TamaParkWorldActionPort {
    override suspend fun refreshFromCanonicalStore() = engine.reloadPersistedPet()
    override fun currentPet(): TamaPet? = engine.pet.value
    override suspend fun questNpcIdLocked(questId: String): String? = engine.parkQuestNpcIdLocked(questId)

    override suspend fun acceptQuestLocked(questId: String, now: Long) =
        engine.acceptParkQuestLocked(questId, now).toParkOutcome()

    override suspend fun finishQuestLocked(questId: String, now: Long) =
        engine.finishParkQuestLocked(questId, now).toParkOutcome()

    override suspend fun acceptRecyclerLocked(now: Long, dateKey: String) =
        engine.acceptRecyclerEncounterLocked(now, dateKey).toParkOutcome()

    override suspend fun finishRecyclerLocked() =
        engine.finishRecyclerEncounterLocked().toParkOutcome()

    override suspend fun declineRecyclerLocked(now: Long, dateKey: String) =
        engine.declineRecyclerEncounterLocked(now, dateKey).toParkOutcome()

    override suspend fun acceptSellerLocked() =
        engine.acceptSellerEncounterLocked().toParkOutcome()

    override suspend fun sellerSaleLocked(itemId: String, quantity: Int) =
        engine.sellToParkSellerByIdLocked(itemId, quantity).toParkOutcome()

    override suspend fun declineSellerLocked() =
        engine.declineSellerEncounterLocked().toParkOutcome()

    override suspend fun finishSellerLocked() =
        engine.finishSellerEncounterLocked().toParkOutcome()

    private fun TamaGameEngine.ActionResult.toParkOutcome() = TamaParkLegacyOutcome(
        success = success,
        message = message,
        errorCode = if (success) null else "legacy_rejected"
    )

    private fun TamaGameEngine.ParkQuestFinishResult.toParkOutcome() = TamaParkLegacyOutcome(
        success = success,
        message = message,
        errorCode = if (success) null else "legacy_rejected",
        questPresentation = presentation?.let {
            TamaWorldQuestCompletionPresentation(it.npcId, it.npcName, it.thanksLine, it.rewardCoins)
        }
    )
}
