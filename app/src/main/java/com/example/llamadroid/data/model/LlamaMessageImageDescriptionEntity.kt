package com.example.llamadroid.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Text description of the image(s) on one chat message, produced by the vision helper so a model
 * without vision can still "see" them. The image itself stays on the message.
 */
@Entity(
    tableName = "llama_message_image_descriptions",
    foreignKeys = [
        ForeignKey(
            entity = LlamaMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LlamaMessageImageDescriptionEntity(
    @PrimaryKey val messageId: Long,
    val imagePath: String,
    val description: String,
    val model: String,
    val createdAt: Long = System.currentTimeMillis()
)
