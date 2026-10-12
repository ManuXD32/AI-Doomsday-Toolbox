package com.example.llamadroid.ui.ai.llama.chatlist

import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListLogicTest {
    private fun server(
        name: String,
        rowId: Long? = null,
        kind: UnifiedServerKind = UnifiedServerKind.LOCAL_MANAGED,
        status: UnifiedServerStatus = UnifiedServerStatus.STOPPED
    ) = UnifiedServer(
        rowId = rowId, cardId = null, kind = kind, name = name, engine = "llama-server",
        modelName = null, host = "127.0.0.1", port = 8080, status = status, errorCode = null,
        boundChatCount = 0, allowWearStart = false, isEasy = false
    )

    @Test
    fun stripPutsLocalFirstThenRunningThenName() {
        val sorted = sortServersForStrip(
            listOf(
                server("zeta-remote", kind = UnifiedServerKind.REMOTE, status = UnifiedServerStatus.RUNNING),
                server("beta", status = UnifiedServerStatus.STOPPED),
                server("alpha", status = UnifiedServerStatus.STOPPED),
                server("gamma", status = UnifiedServerStatus.RUNNING)
            )
        )
        assertEquals(listOf("gamma", "alpha", "beta", "zeta-remote"), sorted.map { it.name })
    }

    @Test
    fun preselectionPrefersLastUsedThenRunningThenFirst() {
        val list = listOf(
            server("a", rowId = 1),
            server("b", rowId = 2, status = UnifiedServerStatus.RUNNING),
            server("c", rowId = 3)
        )
        assertEquals("c", choosePreselectedServer(list, 3)?.name)
        assertEquals("b", choosePreselectedServer(list, 99)?.name)
        assertEquals("b", choosePreselectedServer(list, null)?.name)
        assertEquals("a", choosePreselectedServer(list.filter { it.name != "b" }, null)?.name)
        assertNull(choosePreselectedServer(emptyList(), 1))
    }

    @Test
    fun toolDefaultsComeFromTheChosenServer() {
        val remote = LlamaServerEntity(name = "r", host = "h", port = 1, defaultApiParams = "{\"tools\":true}")
        val other = LlamaServerEntity(name = "o", host = "h", port = 2, defaultApiParams = "{\"tools\":false}")
        val blank = LlamaServerEntity(name = "b", host = "h", port = 3, defaultApiParams = "  ")
        val managed = LlamaServerEntity(name = "m", host = "h", port = 4, defaultApiParams = "{\"x\":1}", managedServerCardId = 5)
        assertEquals("{\"tools\":true}", newChatApiParamsForServer(remote))
        assertEquals("{\"tools\":false}", newChatApiParamsForServer(other))
        assertNull(newChatApiParamsForServer(blank))
        assertNull(newChatApiParamsForServer(null))
        assertTrue(!newChatApiParamsForServer(managed)!!.contains("\"x\""))
    }

    @Test
    fun chatServerLookupIgnoresUnboundAndDeleted() {
        val list = listOf(server("a", rowId = 1))
        assertEquals("a", serverForChat(list, 1)?.name)
        assertNull(serverForChat(list, null))
        assertNull(serverForChat(list, 7))
    }
}
