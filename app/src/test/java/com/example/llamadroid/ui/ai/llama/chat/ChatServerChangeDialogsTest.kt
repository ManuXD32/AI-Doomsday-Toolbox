package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServerChangeDialogsTest {
    @Test
    fun warnsOnlyWhenSwitchingToDifferentServer() {
        assertTrue(shouldWarnOnServerSwitch(1L, 2L))
        assertFalse(shouldWarnOnServerSwitch(2L, 2L))
        assertFalse(shouldWarnOnServerSwitch(null, 2L))
    }

    @Test
    fun cacheMessageFollowsPersistFlag() {
        assertTrue(serverSwitchDeletesCache(true))
        assertFalse(serverSwitchDeletesCache(false))
    }

    @Test
    fun restartNeededOnlyForLiveLocalLlamaServer() {
        val live = listOf(UnifiedServerStatus.RUNNING, UnifiedServerStatus.STARTING)
        for (s in live) assertTrue(kvCacheEnableNeedsRestart(UnifiedServerKind.LOCAL_MANAGED, "llama-server", s))
        assertFalse(kvCacheEnableNeedsRestart(UnifiedServerKind.LOCAL_MANAGED, "llama-server", UnifiedServerStatus.STOPPED))
        assertFalse(kvCacheEnableNeedsRestart(UnifiedServerKind.REMOTE, "llama-server", UnifiedServerStatus.RUNNING))
        assertFalse(kvCacheEnableNeedsRestart(UnifiedServerKind.LOCAL_LEGACY, "ollama", UnifiedServerStatus.RUNNING))
        assertFalse(kvCacheEnableNeedsRestart(null, null, null))
    }
}
