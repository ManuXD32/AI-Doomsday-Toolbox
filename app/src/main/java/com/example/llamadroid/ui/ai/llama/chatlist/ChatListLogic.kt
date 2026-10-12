package com.example.llamadroid.ui.ai.llama.chatlist

import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus
import com.example.llamadroid.data.repository.easyChatDisabledTools
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

/** New managed chats start with every tool disabled; other servers pass on their own default tools. */
internal fun newChatApiParamsForServer(server: LlamaServerEntity?): String? =
    if (server?.managedServerCardId != null) {
        JSONObject(easyChatDisabledTools().toParamMap()).toString()
    } else {
        server?.defaultApiParams?.takeIf { it.isNotBlank() }
    }

/** Bound server of a chat, looked up by connection row id; null for unbound or deleted servers. */
internal fun serverForChat(servers: List<UnifiedServer>, boundRowId: Long?): UnifiedServer? =
    if (boundRowId == null) null else servers.firstOrNull { it.rowId == boundRowId }
