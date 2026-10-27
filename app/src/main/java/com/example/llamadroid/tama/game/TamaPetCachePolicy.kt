package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.normalizeLlamaServerEngine
import com.example.llamadroid.service.KvCacheKey
import com.example.llamadroid.service.NativeChatKvCacheCoordinator
import com.example.llamadroid.service.isNativeChatLoopbackHost

/** Why a pet chat cannot keep its KV cache on disk; null in [TamaPetCacheDecision] means it can. */
enum class TamaPetNoCacheReason { TOGGLE_OFF, CUSTOM_URL, NO_CARD, ENGINE, REMOTE }

/** Whether the pet chat may use a pinned llama-server slot with a disk cache, and why not. */
data class TamaPetCacheDecision(val usesDiskCache: Boolean, val reason: TamaPetNoCacheReason?) {
    val showWarning: Boolean get() = !usesDiskCache
}

/**
 * Pure rule shared by the settings section, the chat header and [TamaAgentService]. The disk cache
 * needs a server card that is a local (loopback) llama-server and the per-pet switch on. Custom
 * URLs, remote cards, Ollama, llama-swap and LiteRT keep working without it.
 */
fun decideTamaPetCache(
    source: String,
    cardResolved: Boolean,
    cardEngine: String?,
    cardHost: String?,
    toggleOn: Boolean
): TamaPetCacheDecision {
    val reason = when {
        source != SettingsRepository.TAMA_SERVER_SOURCE_CARD -> TamaPetNoCacheReason.CUSTOM_URL
        !cardResolved -> TamaPetNoCacheReason.NO_CARD
        !toggleOn -> TamaPetNoCacheReason.TOGGLE_OFF
        normalizeLlamaServerEngine(cardEngine.orEmpty()) != LlamaServerEntity.ENGINE_LLAMA_SERVER -> TamaPetNoCacheReason.ENGINE
        !isNativeChatLoopbackHost(cardHost.orEmpty()) -> TamaPetNoCacheReason.REMOTE
        else -> null
    }
    return TamaPetCacheDecision(usesDiskCache = reason == null, reason = reason)
}

/** Convenience overload for a resolved server row (null row = no card resolved). */
fun decideTamaPetCache(
    source: String,
    card: LlamaServerEntity?,
    toggleOn: Boolean
): TamaPetCacheDecision = decideTamaPetCache(source, card != null, card?.engine, card?.host, toggleOn)

/**
 * Deletes the pet's saved KV slot file and sidecar: on pet reset and from the per-pet "Delete this
 * pet's cache" button. Transfer-out and abandon keep it (it is device-specific, never part of the
 * transfer bundle, and the shared 4 GiB budget evicts it eventually), so a returning pet chats instantly.
 */
suspend fun deleteTamaPetKvCache(context: Context, petId: String) {
    runCatching { NativeChatKvCacheCoordinator(context).deleteCache(KvCacheKey.Pet(petId)) }
}
