package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for storing benchmark results.
 * Each result represents a single thread count test.
 */
@Entity(tableName = "benchmark_results")
data class BenchmarkResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelPath: String,
    val modelName: String,  // Extracted from path for display
    val threads: Int,
    val promptTokensPerSecond: Float,  // pp speed
    val genTokensPerSecond: Float,     // tg speed
    val promptTokens: Int,   // -p value used
    val genTokens: Int,      // -n value used
    val timestamp: Long = System.currentTimeMillis(),
    val runStartedAt: Long = timestamp,
    val runName: String = ""
)
