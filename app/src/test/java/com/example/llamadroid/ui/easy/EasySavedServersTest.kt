package com.example.llamadroid.ui.easy

import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class EasySavedServersTest {
    private fun server(name: String, kind: UnifiedServerKind, cardId: Long?, easy: Boolean = false) = UnifiedServer(
        rowId = cardId, cardId = cardId, kind = kind, name = name, engine = "llama", modelName = "m.gguf",
        host = "127.0.0.1", port = 8080, status = UnifiedServerStatus.STOPPED, errorCode = null,
        boundChatCount = 0, allowWearStart = false, isEasy = easy)

    @Test fun `only local managed non-easy servers are offered, sorted by name`() {
        val list = listOf(
            server("beta", UnifiedServerKind.LOCAL_MANAGED, 2),
            server("easy", UnifiedServerKind.LOCAL_MANAGED, 3, easy = true),
            server("remote", UnifiedServerKind.REMOTE, null),
            server("legacy", UnifiedServerKind.LOCAL_LEGACY, null),
            server("Alpha", UnifiedServerKind.LOCAL_MANAGED, 1)
        )
        assertEquals(listOf("Alpha", "beta"), easySavedServerChoices(list).map { it.name })
    }
}
