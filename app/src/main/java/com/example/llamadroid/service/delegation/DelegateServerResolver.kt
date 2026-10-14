package com.example.llamadroid.service.delegation

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.LlamaServerChatService
import com.example.llamadroid.service.ManagedLlamaServerCoordinator
import com.example.llamadroid.service.NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS
import com.example.llamadroid.service.NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS_ENABLED
import com.example.llamadroid.service.NativeChatLocalServerStarter
import com.example.llamadroid.service.NativeChatServerStartRequest
import com.example.llamadroid.service.isNativeChatLoopbackHost
import com.example.llamadroid.service.managedLlamaConnectHost
import kotlinx.coroutines.CancellationException

/** The server the subagent talks to, ready to receive requests. */
data class ResolvedDelegationServer(
    val row: LlamaServerEntity,
    val baseUrl: String,
    val contextTokens: Int,
    val maxOutputTokens: Int,
    val isLoopback: Boolean,
    val cardId: Long?
)

class DelegationServerException(val code: String, message: String) : Exception(message)

/** Pure validation of the subagent server choice. */
object DelegationServerValidation {
    sealed class Result {
        data class Ok(val row: LlamaServerEntity) : Result()
        data class Rejected(val code: String, val message: String) : Result()
    }

    fun validate(choice: DelegationServerChoice, chatServer: LlamaServerEntity?, choiceRow: LlamaServerEntity?): Result {
        val sameAsChat = choice is DelegationServerChoice.SameAsChat
        val row = if (sameAsChat) chatServer else choiceRow
        if (row == null) return Result.Rejected("server_missing", "The server for the DeepSeek subagent could not be found.")
        return when {
            row.isLiteRtEngine() -> Result.Rejected(
                "subagent_server_unsupported",
                if (sameAsChat) "This chat uses a LiteRT model, which cannot run the DeepSeek subagent. Choose another server for the subagent in the chat's DeepSeek settings."
                else "LiteRT servers cannot run the DeepSeek subagent. Choose a llama-server or llama-swap server."
            )
            row.isOllamaEngine() -> Result.Rejected(
                "subagent_server_unsupported",
                "Ollama servers cannot run the DeepSeek subagent. Choose a llama-server or llama-swap server."
            )
            !row.usesOpenAiCompatibleEngine() -> Result.Rejected(
                "subagent_server_unsupported", "This server type cannot run the DeepSeek subagent."
            )
            else -> Result.Ok(row)
        }
    }

    /** Output budget default when the user entered none. */
    fun defaultMaxOutput(contextTokens: Int): Int = (contextTokens / 4).coerceIn(512, 8192)
}

/** Resolves and prepares the subagent server (starts local/managed servers when needed). */
class DelegateServerResolver(context: Context) {
    private val appContext = context.applicationContext
    private val db get() = AppDatabase.getDatabase(appContext)

    /** The server row for [choice] without starting anything; null when missing. */
    suspend fun rowFor(chat: LlamaChatEntity, choice: DelegationServerChoice): Pair<LlamaServerEntity?, LlamaServerEntity?> {
        val dao = db.llamaServerDao()
        val chatServer = chat.serverId?.let { dao.getServerById(it) } ?: dao.getLastUsedServer()
        val choiceRow = (choice as? DelegationServerChoice.Server)?.let { dao.getServerById(it.rowId) }
        return chatServer to choiceRow
    }

    suspend fun resolve(
        chat: LlamaChatEntity,
        choice: DelegationServerChoice,
        chatMaxOutput: Int?,
        onStatus: (String) -> Unit = {}
    ): ResolvedDelegationServer {
        val (chatServer, choiceRow) = rowFor(chat, choice)
        val row = when (val v = DelegationServerValidation.validate(choice, chatServer, choiceRow)) {
            is DelegationServerValidation.Result.Ok -> v.row
            is DelegationServerValidation.Result.Rejected -> throw DelegationServerException(v.code, v.message)
        }
        val entered = choice as? DelegationServerChoice.Server
        try {
            val cardId = row.managedServerCardId
            if (cardId != null) {
                val ready = ManagedLlamaServerCoordinator(appContext, db).prepare(cardId, onStatus)
                val profile = ready.profile ?: throw DelegationServerException("server_down", "The managed server profile is missing.")
                val host = managedLlamaConnectHost(profile.host)
                val resolved = row.copy(host = host, port = profile.serverPort)
                val ctx = (ready.contextTokens ?: chat.contextSize).coerceAtLeast(2048)
                val out = (if (entered == null) chatMaxOutput else null) ?: DelegationServerValidation.defaultMaxOutput(ctx)
                return ResolvedDelegationServer(resolved, resolved.baseUrl(), ctx, out.coerceAtMost(ctx / 2).coerceAtLeast(256), true, cardId)
            }
            val loopback = isNativeChatLoopbackHost(row.host)
            if (loopback) {
                NativeChatLocalServerStarter(appContext).ensureReady(row, NativeChatServerStartRequest(userInitiated = false), onStatus)
            } else if (!LlamaServerChatService().checkConnection(row.baseUrl())) {
                throw DelegationServerException("server_down", "The subagent server ${row.name} is not reachable.")
            }
            val ctx = (entered?.contextTokens ?: chat.contextSize).coerceAtLeast(2048)
            val out = (entered?.maxOutputTokens ?: chatMaxOutput ?: DelegationServerValidation.defaultMaxOutput(ctx))
                .coerceAtMost(ctx / 2).coerceAtLeast(256)
            return ResolvedDelegationServer(row, row.baseUrl(), ctx, out, loopback, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DelegationServerException) {
            throw e
        } catch (e: Exception) {
            throw DelegationServerException("server_down", "The subagent server could not be started (${e.javaClass.simpleName}).")
        }
    }

    companion object {
        /** Chat's configured max output, when the chat limits it. */
        fun chatMaxOutput(params: Map<String, Any?>): Int? {
            if ((params[NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS_ENABLED] as? Boolean) != true) return null
            return (params[NATIVE_CHAT_PARAM_MAX_OUTPUT_TOKENS] as? Number)?.toInt()?.takeIf { it > 0 }
        }
    }
}
