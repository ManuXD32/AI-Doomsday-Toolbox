package com.example.llamadroid.service.compaction

import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaMessageKind
import java.util.Locale

/** One file listed in a user-turn manifest line. */
data class AttachmentManifestItem(val ref: String, val name: String, val sizeBytes: Long)

/**
 * Pure list transforms behind [NativeChatTurnDecorators]. None of them touch Room or the
 * filesystem, so they are deterministic and unit-testable. Output never mutates the input list.
 */
object NativeChatTurnDecoratorLogic {

    /**
     * Id of the newest ordinary user message that has no assistant reply after it, or null.
     * Delegation notice rows never receive user attachments.
     */
    fun bindTargetMessageId(history: List<LlamaMessageEntity>): Long? {
        val lastUser = history.indexOfLast { it.role == "user" && it.kind != LlamaMessageKind.DELEGATION_NOTICE }
        if (lastUser < 0) return null
        val answered = history.drop(lastUser + 1).any { it.role == "assistant" }
        return if (answered || history[lastUser].id <= 0L) null else history[lastUser].id
    }

    /**
     * Drops entities with id <= [boundaryId] and merges the summary block into the first remaining
     * user entity. When the first remaining entity is not a user turn (or nothing remains) a
     * synthetic user entity (id 0, never persisted) carries the block.
     */
    fun applyBoundary(
        history: List<LlamaMessageEntity>,
        chatId: Long,
        boundaryId: Long,
        summary: String
    ): List<LlamaMessageEntity> {
        val remaining = history.filter { it.id > boundaryId }
        val first = remaining.firstOrNull()
        return if (first != null && first.role == "user") {
            listOf(first.copy(content = ConversationCompactor.mergeSummaryIntoUserText(first.content, summary))) +
                remaining.drop(1)
        } else {
            val synthetic = LlamaMessageEntity(
                id = 0L,
                chatId = chatId,
                role = "user",
                content = ConversationCompactor.summaryBlock(summary),
                timestamp = first?.timestamp ?: 0L
            )
            listOf(synthetic) + remaining
        }
    }

    /** `1.2 MB` / `340 KB` / `12 B`, locale independent. */
    fun formatSize(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> "${Math.round(b / 1024.0)} KB"
            else -> String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024.0))
        }
    }

    /** `[Attached files: f12 report.pdf (1.2 MB), img55 photo.jpg (340 KB)]`, or null when empty. */
    fun manifestLine(items: List<AttachmentManifestItem>): String? {
        if (items.isEmpty()) return null
        return items.joinToString(", ", prefix = "[Attached files: ", postfix = "]") {
            "${it.ref} ${it.name.replace(Regex("[\\r\\n\\[\\]]"), " ").trim()} (${formatSize(it.sizeBytes)})"
        }
    }

    fun appendManifest(content: String, items: List<AttachmentManifestItem>): String {
        val line = manifestLine(items) ?: return content
        return if (content.isBlank()) line else content.trimEnd() + "\n\n" + line
    }

    /**
     * Merges runs of consecutive user entities (ordinary + notice rows) into one so strict chat
     * templates keep alternating. Contents are joined by a blank line; the merged entity keeps the
     * first entity's media and the LAST entity's id, so a compaction boundary stored against the
     * run never leaves part of it behind. Assistant entities, and therefore the role of the last
     * entity, are never changed.
     */
    fun mergeConsecutiveUsers(list: List<LlamaMessageEntity>): List<LlamaMessageEntity> {
        val out = ArrayList<LlamaMessageEntity>(list.size)
        for (entity in list) {
            val prev = out.lastOrNull()
            if (prev != null && prev.role == "user" && entity.role == "user") {
                out[out.lastIndex] = prev.copy(
                    id = entity.id,
                    content = listOf(prev.content, entity.content).filter { it.isNotBlank() }.joinToString("\n\n"),
                    timestamp = entity.timestamp
                )
            } else {
                out += entity
            }
        }
        return out
    }

    /**
     * Id of the last persisted entity inside `[fromIndex, toExclusive)` of the parallel id list
     * (null = system or synthetic row), or null when the span has none.
     */
    fun boundaryMessageId(ids: List<Long?>, fromIndex: Int, toExclusive: Int): Long? {
        val end = toExclusive.coerceAtMost(ids.size)
        for (i in end - 1 downTo fromIndex.coerceAtLeast(0)) {
            val id = ids[i]
            if (id != null && id > 0L) return id
        }
        return null
    }

    /** Context-window overflow error text produced by llama-server (and compatible servers). */
    fun isContextOverflowMessage(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return "exceeds the available context size" in m ||
            "exceed_context_size" in m ||
            ("context" in m && "exceed" in m) ||
            "context length" in m && ("too long" in m || "maximum" in m)
    }
}
