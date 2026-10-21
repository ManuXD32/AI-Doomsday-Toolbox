package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaMessageKind

/**
 * Model-request-only framing so the main model never mistakes an app/subagent notice (stored as a
 * "user" row, which chat templates need) for something the user typed. Stored rows and the UI are untouched.
 */
object NativeChatNoticeFraming {
    const val NOTICE_PREFIX = "[Automatic message from the app about your DeepSeek task — the user did not write this]"
    const val USER_LABEL = "[User]"

    fun isFramed(content: String): Boolean = content.startsWith(NOTICE_PREFIX)

    /** Prefixes a delegation notice once (idempotent); other rows are returned unchanged. */
    fun frameEntity(entity: LlamaMessageEntity): LlamaMessageEntity =
        if (entity.role == "user" && entity.kind == LlamaMessageKind.DELEGATION_NOTICE && !isFramed(entity.content)) {
            entity.copy(content = NOTICE_PREFIX + "\n" + entity.content)
        } else entity

    /** Joins two adjacent user parts; the real user part next to a framed notice is labelled [User]. */
    fun joinParts(first: String, second: String): String {
        val parts = listOf(first, second).filter { it.isNotBlank() }
        if (parts.size < 2 || parts.none { it.contains(NOTICE_PREFIX) }) return parts.joinToString("\n\n")
        return parts.joinToString("\n\n") { part ->
            if (isFramed(part) || part.startsWith(USER_LABEL)) part else USER_LABEL + "\n" + part
        }
    }

    /** Text of the last user row that should drive per-turn hints; null on a wake turn (last user row is a notice). */
    fun hintTarget(history: List<LlamaMessageEntity>): String? {
        val last = history.lastOrNull { it.role == "user" } ?: return null
        return if (last.kind == LlamaMessageKind.DELEGATION_NOTICE) null else last.content
    }
}
