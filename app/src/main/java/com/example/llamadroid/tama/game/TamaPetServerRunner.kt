package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.KvCacheKey
import com.example.llamadroid.service.KvSlotOwnerSpec
import com.example.llamadroid.service.NativeChatKvCacheCoordinator
import com.example.llamadroid.service.NativeChatLocalServerStarter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A picked server card for the pet chat, with the backend its engine maps to. */
internal data class TamaCardTarget(
    val row: LlamaServerEntity,
    val backend: String,
    /** True when this pet's KV cache is kept on disk (local llama-server, switch on, saving supported). */
    val persist: Boolean
)

/**
 * Runs pet chat requests on a server card: starts the card when it is stopped, pins one llama-server
 * slot per pet through [NativeChatKvCacheCoordinator] (`pet-<sha16(petId)>` on disk) and routes
 * background utility requests (the summarizer) to another slot so they cannot clobber the pet slot.
 * Custom-URL chats never reach this class. No message text is logged or stored here.
 */
internal class TamaPetServerRunner(
    context: Context,
    private val settingsRepo: SettingsRepository
) {
    private val appContext = context.applicationContext
    private val kvCache by lazy { NativeChatKvCacheCoordinator(appContext) }

    /** The picked card for [petId], or null for the custom-URL source or when the card row is gone. */
    suspend fun resolve(petId: String): TamaCardTarget? {
        if (settingsRepo.tamaServerSource.value != SettingsRepository.TAMA_SERVER_SOURCE_CARD) return null
        val rowId = settingsRepo.tamaServerCardId.value.takeIf { it > 0L } ?: return null
        val row = AppDatabase.getDatabase(appContext).llamaServerDao().getServerById(rowId) ?: return null
        val decision = decideTamaPetCache(
            SettingsRepository.TAMA_SERVER_SOURCE_CARD,
            row,
            settingsRepo.isTamaPetKvCacheEnabled(petId)
        )
        return TamaCardTarget(row, backendFor(row), decision.usesDiskCache)
    }

    /**
     * Starts the card when needed (same starter as the Wear quick chat and native chat), reloads the
     * row because a managed card may move ports, and makes sure the server can save slots.
     * Throws the starter's exception when the card cannot start.
     */
    suspend fun prepare(target: TamaCardTarget): TamaCardTarget {
        NativeChatLocalServerStarter(appContext).ensureReady(target.row)
        val row = AppDatabase.getDatabase(appContext).llamaServerDao().getServerById(target.row.id) ?: target.row
        var persist = target.persist
        if (persist) persist = kvCache.ensureSlotSaveSupport(row, true)
        return target.copy(row = row, persist = persist)
    }

    /**
     * Runs one pet chat request. [call] receives the pinned slot (null when the server cannot pin)
     * and must pass it as `id_slot` with `slotOwner = null`. After a complete reply the slot is saved;
     * on cancellation it is saved after the slot goes idle so Continue reuses the work.
     */
    suspend fun <R> runChat(
        target: TamaCardTarget,
        petId: String,
        call: suspend (slotId: Int?) -> Result<R>
    ): Result<R> {
        if (target.backend != SettingsRepository.PDF_BACKEND_LLAMA_SERVER) return call(null)
        val key = KvCacheKey.Pet(petId)
        val spec = KvSlotOwnerSpec(key, target.persist, "tama-pet:$petId")
        val baseUrl = target.row.baseUrl()
        return kvCache.withSlot(baseUrl, target.row, spec) { lease ->
            var usedSlot: Int? = null
            val result = try {
                lease.request { slot ->
                    usedSlot = slot
                    call(slot)
                }
            } catch (cancel: CancellationException) {
                if (target.persist) {
                    withContext(NonCancellable) { runCatching { lease.saveAfterStop() } }
                }
                throw cancel
            }
            val slot = usedSlot
            if (target.persist && result.isSuccess && slot != null) {
                kvCache.scheduleSave(baseUrl, key, slot)
            }
            result
        }
    }

    /** Runs a background request (summarizer) on a slot other than the pet's when possible. */
    suspend fun <R> runUtility(target: TamaCardTarget, call: suspend (slotId: Int?) -> R): R =
        if (target.backend != SettingsRepository.PDF_BACKEND_LLAMA_SERVER) {
            call(null)
        } else {
            kvCache.withUtilitySlot(target.row.baseUrl(), target.row, call)
        }

    private fun backendFor(row: LlamaServerEntity): String = when {
        row.isLlamaServerEngine() -> SettingsRepository.PDF_BACKEND_LLAMA_SERVER
        row.isLlamaSwapEngine() -> SettingsRepository.PDF_BACKEND_LLAMA_SWAP
        row.isLiteRtEngine() -> SettingsRepository.PDF_BACKEND_LITERT
        else -> SettingsRepository.PDF_BACKEND_OLLAMA
    }
}
