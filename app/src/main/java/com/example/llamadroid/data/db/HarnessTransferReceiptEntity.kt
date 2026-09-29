package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable operation receipt used to make a repeated Harness import idempotent. */
@Entity(
    tableName = "agent_harness_transfer_receipts",
    indices = [
        Index(value = ["sourceRuntimeId", "targetRuntimeId"]),
        Index("createdAt")
    ]
)
data class HarnessTransferImportReceiptEntity(
    @PrimaryKey val operationId: String,
    val sourceRuntimeId: String,
    val targetRuntimeId: String,
    val mappingJson: String,
    val createdAt: Long,
)
