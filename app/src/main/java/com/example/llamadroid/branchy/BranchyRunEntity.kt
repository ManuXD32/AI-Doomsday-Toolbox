package com.example.llamadroid.branchy

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Lifecycle of a Branchy training run. Stored as the lowercase [value]. */
enum class BranchyRunStatus(val value: String) {
    DRAFT("draft"), PREPARING("preparing"), TRAINING("training"), PAUSED("paused"), DONE("done"), FAILED("failed");

    companion object {
        fun fromValue(value: String?): BranchyRunStatus = entries.firstOrNull { it.value == value } ?: FAILED
    }
}

/** One Branchy training run. [runDir] holds run.json, checkpoints and metrics. */
@Entity(tableName = "branchy_run")
data class BranchyRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val recipeId: String?,
    /** Recipe stage index (0 for custom runs). */
    val stage: Int,
    val runDir: String,
    /** Full run.json content. */
    val configJson: String,
    /** `draft`, `preparing`, `training`, `paused`, `done` or `failed`. */
    val status: String,
    val lastStep: Long,
    val totalSteps: Long,
    val lastLoss: Double?,
    val lastValLoss: Double?,
    val tokensSeen: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val error: String?
)
