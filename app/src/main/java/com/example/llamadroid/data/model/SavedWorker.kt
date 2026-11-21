package com.example.llamadroid.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted worker configuration for distributed inference.
 * Workers can be enabled/disabled without being removed.
 */
@Entity(tableName = "saved_workers")
data class SavedWorker(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ip: String,
    val port: Int = 50052,
    val deviceName: String = "Worker",
    val ramMB: Int = 4096,
    val isEnabled: Boolean = true,
    val assignedProportion: Float? = null,  // null = auto calculate based on RAM, 0.0-1.0 = override
    /** The helper's other address (VPN vs LAN); the fallback when [ip] does not answer. */
    val altIp: String? = null,
    /** `auto`, `vpn` or `lan`. */
    @ColumnInfo(defaultValue = "'auto'") val preferredInterface: String = "auto"
)

