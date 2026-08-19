package com.example.llamadroid.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A persistent launcher card for an app-managed llama.cpp server.
 *
 * The card deliberately stores the GENERAL saved-command id instead of copying its
 * launch profile.  A card therefore follows edits to its preset on its next restart;
 * [port] is the only card-owned launch override.
 */
@Entity(
    tableName = "llama_server_cards",
    indices = [
        Index(value = ["savedCommandId"]),
        Index(value = ["updatedAt"])
    ]
)
data class LlamaServerCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val savedCommandId: Long,
    /** Non-authoritative label retained when a preset is later renamed or deleted. */
    val presetNameSnapshot: String = "",
    val port: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val sessionId: String get() = sessionIdForCard(id)

    companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65535

        fun sessionIdForCard(cardId: Long): String = "card:$cardId"
    }
}

/** Stable, non-card session ids reserved for workflows that must keep old launch APIs. */
object LlamaServerSessionIds {
    const val GENERAL = "internal:general"
    const val OCR = "internal:ocr"
    const val MASTER = "internal:master"
    const val WORKER = "internal:worker"

    fun isReserved(value: String): Boolean = value in setOf(GENERAL, OCR, MASTER, WORKER)
}
