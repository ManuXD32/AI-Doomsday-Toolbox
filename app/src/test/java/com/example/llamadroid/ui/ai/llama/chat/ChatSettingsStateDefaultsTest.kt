package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.TOOL_DEFAULTS_MARKER_KEY
import com.example.llamadroid.data.repository.markedDefaultsJson
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.service.compaction.NativeChatCompactionParams
import com.example.llamadroid.service.delegation.DelegationApprovalPolicy
import com.example.llamadroid.service.delegation.DelegationServerChoice
import com.example.llamadroid.service.delegation.NativeChatDelegationConfig
import com.example.llamadroid.ui.ai.llama.chatlist.newChatApiParamsForServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Server "defaults for new chats" must carry exactly the keys a chat stores, through new-chat creation. */
class ChatSettingsStateDefaultsTest {
    private fun configuredState() = ChatSettingsState().apply {
        loadFromDefaults(null)
        toolsEnabled = true
        webSearchEnabled = true
        webSearchMaxPages = 5
        fileToolsEnabled = true
        customToolsEnabled = true
        maxToolRounds = 7
        autoCompactEnabled = false
        assistantTtsEnabled = true
        enableThinking = false
        delegation = NativeChatDelegationConfig(
            enabled = true,
            runtimeId = "rt-1",
            subagentServer = DelegationServerChoice.Server(rowId = 42L, contextTokens = 8192, maxOutputTokens = 1024),
            harnessServer = DelegationServerChoice.SameAsChat,
            parallelRequests = false,
            approvalPolicy = DelegationApprovalPolicy.ASK_ME,
            subagentKvCacheEnabled = false
        )
    }

    private fun assertRoundTrip(original: ChatSettingsState, loaded: ChatSettingsState) {
        assertTrue(loaded.toolsEnabled)
        assertTrue(loaded.webSearchEnabled)
        assertEquals(5, loaded.webSearchMaxPages)
        assertTrue(loaded.fileToolsEnabled)
        assertTrue(loaded.customToolsEnabled)
        assertEquals(7, loaded.maxToolRounds)
        assertFalse(loaded.autoCompactEnabled)
        assertTrue(loaded.assistantTtsEnabled)
        assertFalse(loaded.enableThinking)
        assertEquals(original.delegation, loaded.delegation)
        assertEquals(original.buildApiParamsJson(), loaded.buildApiParamsJson())
    }

    @Test
    fun defaultsJsonContainsEveryDelegationAndCompactionKey() {
        val json = JSONObject(configuredState().buildApiParamsJson())
        listOf(
            NativeChatDelegationConfig.KEY_ENABLED, NativeChatDelegationConfig.KEY_RUNTIME_ID,
            NativeChatDelegationConfig.KEY_SUBAGENT_SERVER, NativeChatDelegationConfig.KEY_SUBAGENT_CTX,
            NativeChatDelegationConfig.KEY_SUBAGENT_MAX_OUT, NativeChatDelegationConfig.KEY_MODE,
            NativeChatDelegationConfig.KEY_PARALLEL, NativeChatDelegationConfig.KEY_APPROVAL,
            NativeChatDelegationConfig.KEY_SUBAGENT_KV, NativeChatCompactionParams.KEY_AUTO_COMPACT,
            NativeChatToolConfig.KEY_FILE_TOOLS_ENABLED, NativeChatToolConfig.KEY_CUSTOM_TOOLS_ENABLED
        ).forEach { key -> assertTrue("missing $key", json.has(key)) }
    }

    @Test
    fun stateRoundTripsThroughDefaultsJson() {
        val original = configuredState()
        val loaded = ChatSettingsState().apply { loadFromDefaults(original.buildApiParamsJson()) }
        assertRoundTrip(original, loaded)
    }

    @Test
    fun remoteServerDefaultsSurviveNewChatCreation() {
        val original = configuredState()
        val server = LlamaServerEntity(name = "r", host = "h", port = 1, defaultApiParams = original.buildApiParamsJson())
        val chatParams = requireNotNull(newChatApiParamsForServer(server))
        val loaded = ChatSettingsState().apply { loadFromDefaults(chatParams) }
        assertRoundTrip(original, loaded)
    }

    @Test
    fun managedServerKeepsKeysAndDropsTheEditorMarker() {
        val original = configuredState()
        val stored = markedDefaultsJson(original.buildApiParamsJson())
        assertTrue(JSONObject(stored).has(TOOL_DEFAULTS_MARKER_KEY))
        val server = LlamaServerEntity(
            name = "m", host = "h", port = 2, defaultApiParams = stored, managedServerCardId = 9
        )
        val chatParams = requireNotNull(newChatApiParamsForServer(server))
        assertFalse(JSONObject(chatParams).has(TOOL_DEFAULTS_MARKER_KEY))
        assertRoundTrip(original, ChatSettingsState().apply { loadFromDefaults(chatParams) })
    }

    @Test
    fun markedManagedServerKeepsAnAllToolsOnChoice() {
        val allOn = ChatSettingsState().apply {
            loadFromDefaults(null)
            toolsEnabled = true; webSearchEnabled = true; kiwixSearchEnabled = true; fetchUrlEnabled = true
        }
        val marked = LlamaServerEntity(
            name = "m", host = "h", port = 3, defaultApiParams = markedDefaultsJson(allOn.buildApiParamsJson()),
            managedServerCardId = 1
        )
        val chat = ChatSettingsState().apply { loadFromDefaults(newChatApiParamsForServer(marked)) }
        assertTrue(chat.toolsEnabled && chat.webSearchEnabled && chat.kiwixSearchEnabled && chat.fetchUrlEnabled)
    }
}
