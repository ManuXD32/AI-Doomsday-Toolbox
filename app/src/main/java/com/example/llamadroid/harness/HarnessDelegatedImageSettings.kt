package com.example.llamadroid.harness

import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.NativeChatToolConfig

/** Which settings the Harness image tools run with. */
enum class HarnessImageSource { CHAT, AGENT }

/** Chat settings win only when the chat enabled the tool and picked a model; otherwise the Agent settings apply. */
fun decideHarnessImageSource(chatToolEnabled: Boolean, chatModel: String?): HarnessImageSource =
    if (chatToolEnabled && !chatModel.isNullOrBlank()) HarnessImageSource.CHAT else HarnessImageSource.AGENT

/** Per-chat image settings for Harness sessions that belong to a native-chat delegation task. */
class HarnessDelegatedImageSettings(private val database: AppDatabase) {
    /** The delegating chat's tool config, or null when the session is not delegated or the chat is gone. */
    suspend fun chatConfig(sessionId: String): NativeChatToolConfig? {
        val task = database.nativeChatDelegateTaskDao().getByHarnessSession(sessionId) ?: return null
        val chat = database.llamaChatDao().getChatById(task.chatId) ?: return null
        return NativeChatToolConfig.fromApiParams(chat.apiParams)
    }

    suspend fun generationConfig(sessionId: String): NativeChatToolConfig? =
        chatConfig(sessionId)?.takeIf {
            decideHarnessImageSource(it.toolsEnabled && it.imageGenerationEnabled, it.imageParams.model) ==
                HarnessImageSource.CHAT
        }

    suspend fun backgroundRemovalConfig(sessionId: String): NativeChatToolConfig? =
        chatConfig(sessionId)?.takeIf {
            decideHarnessImageSource(it.toolsEnabled && it.backgroundRemovalEnabled, it.backgroundRemovalParams.model) ==
                HarnessImageSource.CHAT
        }
}
