package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Process ownership only. Tokens, credentials and command/output content never enter this row. */
@Entity(tableName = "agent_harness_runtime")
data class HarnessRuntimeEntity(
    @PrimaryKey val id: String = "shared",
    val environmentId: String = "deepseek-harness-shared",
    val state: String = "STOPPED",
    val generation: String? = null,
    val brokerPid: Long? = null,
    val processGroupId: Long? = null,
    val processStartTicks: Long? = null,
    val childPid: Int? = null,
    val childStartTicks: Long? = null,
    val nodePid: Int? = null,
    val nodeStartTicks: Long? = null,
    val port: Int? = null,
    val runtimeVersion: String = "0.1.6-alpha.2",
    val startedAt: Long? = null,
    val stopRequestedAt: Long? = null,
    val forceStopRequestedAt: Long? = null,
    val endedAt: Long? = null,
    val errorCode: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Stable file identity is independent of whichever session happens to be visible. */
@Entity(
    tableName = "agent_harness_workspaces",
    indices = [
        Index(value = ["runtimeId", "backend", "projectFolder", "connectionKey"], unique = true),
        Index(value = ["runtimeId", "harnessWorkspaceId"], unique = true),
        // Required by the session foreign key so a session cannot cross runtime boundaries.
        Index(value = ["runtimeId", "id"], unique = true)
    ]
)
data class HarnessWorkspaceEntity(
    @PrimaryKey val id: String,
    val backend: String,
    val projectFolder: String,
    val connectionKey: String = "",
    val title: String,
    val guestPath: String,
    val harnessWorkspaceId: String? = null,
    val prootEnvironmentId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Captured runtime ownership; legacy rows are assigned [HarnessRuntimeIds.LEGACY]. */
    val runtimeId: String = HarnessRuntimeIds.LEGACY
)

/** Harness owns message history; this table only connects its ids to the native navigation. */
@Entity(
    tableName = "agent_harness_sessions",
    primaryKeys = ["runtimeId", "harnessSessionId"],
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"], childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = HarnessWorkspaceEntity::class,
            parentColumns = ["runtimeId", "id"], childColumns = ["runtimeId", "workspaceId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        // The conversation foreign key is single-column; keep the exact
        // parent-key prefix so Room/SQLite can validate and delete efficiently.
        Index("conversationId"),
        Index(value = ["runtimeId", "conversationId"], unique = true),
        Index(value = ["runtimeId", "workspaceId"]),
        Index(value = ["runtimeId", "updatedAt"])
    ]
)
data class HarnessSessionEntity(
    val harnessSessionId: String,
    val conversationId: Long,
    val workspaceId: String,
    val archived: Boolean = false,
    val lastSequence: Long = -1L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Captured runtime ownership; legacy rows are assigned [HarnessRuntimeIds.LEGACY]. */
    val runtimeId: String = HarnessRuntimeIds.LEGACY
)

/** Stable runtime id used for rows written before multi-runtime Harness support. */
object HarnessRuntimeIds {
    const val LEGACY = "deepseek-harness-shared"
}

object AgentRuntimeSource {
    const val LEGACY_ARCHIVE = "LEGACY_ARCHIVE"
    const val DEEPSEEK = "DEEPSEEK"
    const val WORKSPACE_ONLY = "WORKSPACE_ONLY"
}
