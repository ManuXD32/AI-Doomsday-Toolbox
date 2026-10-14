package com.example.llamadroid.service.delegation

/** How the subagent answers DeepSeek Harness permission requests. */
enum class DelegationApprovalPolicy(val wire: String) {
    /** The subagent approves or denies by itself; harness preset `workspace-write`. */
    SUBAGENT_DECIDES("subagent"),
    /** No approvals are raised; harness preset `danger-full-access`. */
    FULL_ACCESS("full"),
    /** Approvals stay with the user through the normal harness notifications. */
    ASK_ME("ask");

    companion object {
        fun fromWire(value: String?): DelegationApprovalPolicy =
            entries.firstOrNull { it.wire == value } ?: SUBAGENT_DECIDES
    }
}

/** How eagerly the main model should hand work to the DeepSeek runtime. Only guidance text changes. */
enum class DelegationMode(val wire: String) {
    /** Delegate substantial coding, file and project work; answer small things directly. */
    AUTO("auto"),
    /** Delegate any coding, file or project request, even small ones that create files. */
    PREFER("prefer"),
    /** Use the DeepSeek tools only when the user explicitly asks for them. */
    ONLY_WHEN_ASKED("ask");

    companion object {
        fun fromWire(value: String?): DelegationMode =
            entries.firstOrNull { it.wire == value } ?: AUTO
    }
}

/**
 * Which server a delegation participant uses. [SameAsChat] follows the chat's bound server.
 * For [Server], [contextTokens] and [maxOutputTokens] are the user's values for remote or LiteRT
 * rows; phone-local server cards ignore them and use the card's own limits.
 */
sealed class DelegationServerChoice {
    data object SameAsChat : DelegationServerChoice()
    data class Server(
        val rowId: Long,
        val contextTokens: Int? = null,
        val maxOutputTokens: Int? = null
    ) : DelegationServerChoice()
}

/**
 * Per-chat DeepSeek delegation settings, stored in `LlamaChatEntity.apiParams`.
 * `ChatSettingsState` must carry every field: its auto-save rebuilds the whole JSON map.
 */
data class NativeChatDelegationConfig(
    val enabled: Boolean = false,
    /** Harness runtime (proot installation) id; null means "not chosen yet". */
    val runtimeId: String? = null,
    /** Server of the subagent that orchestrates the harness. */
    val subagentServer: DelegationServerChoice = DelegationServerChoice.SameAsChat,
    /** Server of the agent working inside the DeepSeek Harness. */
    val harnessServer: DelegationServerChoice = DelegationServerChoice.SameAsChat,
    /** True: main LLM, subagent and harness agent may send requests at the same time. */
    val parallelRequests: Boolean = true,
    val approvalPolicy: DelegationApprovalPolicy = DelegationApprovalPolicy.SUBAGENT_DECIDES,
    /** Keep the subagent's KV cache on disk between its model calls. */
    val subagentKvCacheEnabled: Boolean = true,
    /** When the main model should delegate; old chats default to [DelegationMode.AUTO]. */
    val mode: DelegationMode = DelegationMode.AUTO
) {
    fun toParamMap(): Map<String, Any> = buildMap {
        put(KEY_ENABLED, enabled)
        runtimeId?.takeIf { it.isNotBlank() }?.let { put(KEY_RUNTIME_ID, it) }
        putChoice(KEY_SUBAGENT_SERVER, KEY_SUBAGENT_CTX, KEY_SUBAGENT_MAX_OUT, subagentServer)
        putChoice(KEY_HARNESS_SERVER, KEY_HARNESS_CTX, KEY_HARNESS_MAX_OUT, harnessServer)
        put(KEY_PARALLEL, parallelRequests)
        put(KEY_APPROVAL, approvalPolicy.wire)
        put(KEY_SUBAGENT_KV, subagentKvCacheEnabled)
        put(KEY_MODE, mode.wire)
    }

    companion object {
        /** Shared with `NativeChatToolConfig.deepSeekDelegationEnabled`. */
        const val KEY_ENABLED = "tool_deepseek_enabled"
        const val KEY_RUNTIME_ID = "deepseek_runtime_id"
        const val KEY_SUBAGENT_SERVER = "deepseek_subagent_server_row"
        const val KEY_SUBAGENT_CTX = "deepseek_subagent_ctx"
        const val KEY_SUBAGENT_MAX_OUT = "deepseek_subagent_max_out"
        const val KEY_HARNESS_SERVER = "deepseek_harness_server_row"
        const val KEY_HARNESS_CTX = "deepseek_harness_ctx"
        const val KEY_HARNESS_MAX_OUT = "deepseek_harness_max_out"
        const val KEY_PARALLEL = "deepseek_parallel"
        const val KEY_APPROVAL = "deepseek_approval_policy"
        const val KEY_SUBAGENT_KV = "deepseek_subagent_kv"
        const val KEY_MODE = "deepseek_delegation_mode"

        const val MIN_CONTEXT_TOKENS = 2048
        const val MAX_CONTEXT_TOKENS = 1_048_576
        const val MIN_OUTPUT_TOKENS = 256
        const val MAX_OUTPUT_TOKENS = 131_072

        /** Recommended llama-server `--parallel` so all three participants can run at once. */
        const val RECOMMENDED_PARALLEL_SLOTS = 3

        fun fromParams(params: Map<String, Any?>): NativeChatDelegationConfig = NativeChatDelegationConfig(
            enabled = params[KEY_ENABLED].asBoolean() ?: false,
            runtimeId = (params[KEY_RUNTIME_ID] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            subagentServer = readChoice(params, KEY_SUBAGENT_SERVER, KEY_SUBAGENT_CTX, KEY_SUBAGENT_MAX_OUT),
            harnessServer = readChoice(params, KEY_HARNESS_SERVER, KEY_HARNESS_CTX, KEY_HARNESS_MAX_OUT),
            parallelRequests = params[KEY_PARALLEL].asBoolean() ?: true,
            approvalPolicy = DelegationApprovalPolicy.fromWire(params[KEY_APPROVAL] as? String),
            subagentKvCacheEnabled = params[KEY_SUBAGENT_KV].asBoolean() ?: true,
            mode = DelegationMode.fromWire(params[KEY_MODE] as? String)
        )

        private fun readChoice(
            params: Map<String, Any?>,
            rowKey: String,
            ctxKey: String,
            maxOutKey: String
        ): DelegationServerChoice {
            val rowId = params[rowKey].asLong()?.takeIf { it > 0 } ?: return DelegationServerChoice.SameAsChat
            return DelegationServerChoice.Server(
                rowId = rowId,
                contextTokens = params[ctxKey].asLong()?.toInt()?.coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS),
                maxOutputTokens = params[maxOutKey].asLong()?.toInt()?.coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)
            )
        }

        private fun MutableMap<String, Any>.putChoice(
            rowKey: String,
            ctxKey: String,
            maxOutKey: String,
            choice: DelegationServerChoice
        ) {
            if (choice !is DelegationServerChoice.Server) return
            put(rowKey, choice.rowId)
            choice.contextTokens?.let { put(ctxKey, it) }
            choice.maxOutputTokens?.let { put(maxOutKey, it) }
        }

        // Gson parses JSON numbers as Double and some callers store numbers as strings.
        private fun Any?.asBoolean(): Boolean? = when (this) {
            is Boolean -> this
            is String -> toBooleanStrictOrNull()
            else -> null
        }

        private fun Any?.asLong(): Long? = when (this) {
            is Number -> toLong()
            is String -> trim().toDoubleOrNull()?.toLong()
            else -> null
        }
    }
}
