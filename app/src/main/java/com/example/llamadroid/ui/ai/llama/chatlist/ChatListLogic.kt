package com.example.llamadroid.ui.ai.llama.chatlist

import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus
import com.example.llamadroid.data.repository.effectiveToolDefaultsParams
import com.example.llamadroid.service.chatAudioSeedParams
import com.example.llamadroid.service.isNativeChatLoopbackHost
import org.json.JSONObject

/** Strip order: local servers before remote ones, running before stopped, then by name. */
internal fun sortServersForStrip(servers: List<UnifiedServer>): List<UnifiedServer> =
    servers.sortedWith(
        compareBy<UnifiedServer>(
            { it.kind == UnifiedServerKind.REMOTE },
            { it.status != UnifiedServerStatus.RUNNING },
            { it.name.lowercase() }
        )
    )

/**
 * Server preselected in the new-chat sheet: the last-used connection row if it still exists,
 * otherwise a running server, otherwise the first one. Null only when there are no servers.
 */
internal fun choosePreselectedServer(servers: List<UnifiedServer>, lastUsedRowId: Long?): UnifiedServer? =
    servers.firstOrNull { lastUsedRowId != null && it.rowId == lastUsedRowId }
        ?: servers.firstOrNull { it.status == UnifiedServerStatus.RUNNING }
        ?: servers.firstOrNull()

/**
 * New chats copy the server's own tool defaults (managed servers normalize the legacy all-true value
 * to "all tools off" so existing users see no change). The server's Whisper settings are copied into
 * the chat too; both are edited per chat from then on.
 */
internal fun newChatApiParamsForServer(server: LlamaServerEntity?): String? {
    val base = effectiveToolDefaultsParams(server)
    val audio = server?.chatAudioSeedParams().orEmpty()
    if (audio.isEmpty()) return base
    val json = runCatching { base?.let(::JSONObject) }.getOrNull() ?: JSONObject()
    audio.forEach { (key, value) -> json.put(key, value) }
    return json.toString()
}

/** Bound server of a chat, looked up by connection row id; null for unbound or deleted servers. */
internal fun serverForChat(servers: List<UnifiedServer>, boundRowId: Long?): UnifiedServer? =
    if (boundRowId == null) null else servers.firstOrNull { it.rowId == boundRowId }

/** New chats on a loopback llama-server (managed card or legacy local) keep their KV cache on disk by default. */
internal fun defaultPersistKvCacheForServer(server: LlamaServerEntity?): Boolean =
    server != null && server.isLlamaServerEngine() && isNativeChatLoopbackHost(server.host)
