package com.example.llamadroid.data.model

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Answer state of one interactive chat card. [cardIndex] is the 0-based index among the `card`
 * fences of the message; [stateJson] is `ChatCardState` output (no message text, only the chosen values).
 */
@Entity(
    tableName = "llama_message_card_state",
    primaryKeys = ["messageId", "cardIndex"],
    foreignKeys = [
        ForeignKey(
            entity = LlamaMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LlamaMessageCardStateEntity(
    val messageId: Long,
    val cardIndex: Int,
    val stateJson: String,
    val answeredAt: Long = System.currentTimeMillis()
)
