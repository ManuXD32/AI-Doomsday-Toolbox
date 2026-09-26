package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve all legacy content while withdrawing old execution receipts before any recovery runs. */
object HarnessMigration {
    val MIGRATION_122_123: Migration = object : Migration(122, 123) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE agent_conversations ADD COLUMN runtimeSource TEXT NOT NULL DEFAULT 'LEGACY_ARCHIVE'")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS agent_harness_runtime (
                    id TEXT NOT NULL PRIMARY KEY,
                    environmentId TEXT NOT NULL, state TEXT NOT NULL, generation TEXT,
                    brokerPid INTEGER, processGroupId INTEGER, processStartTicks INTEGER,
                    childPid INTEGER, childStartTicks INTEGER, nodePid INTEGER, nodeStartTicks INTEGER,
                    port INTEGER, runtimeVersion TEXT NOT NULL, startedAt INTEGER,
                    stopRequestedAt INTEGER, forceStopRequestedAt INTEGER, endedAt INTEGER, errorCode TEXT,
                    updatedAt INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS agent_harness_workspaces (
                    id TEXT NOT NULL PRIMARY KEY, backend TEXT NOT NULL,
                    projectFolder TEXT NOT NULL, connectionKey TEXT NOT NULL,
                    title TEXT NOT NULL, guestPath TEXT NOT NULL, harnessWorkspaceId TEXT,
                    prootEnvironmentId TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_harness_workspaces_backend_projectFolder_connectionKey ON agent_harness_workspaces(backend, projectFolder, connectionKey)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_harness_workspaces_harnessWorkspaceId ON agent_harness_workspaces(harnessWorkspaceId)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS agent_harness_sessions (
                    harnessSessionId TEXT NOT NULL PRIMARY KEY, conversationId INTEGER NOT NULL,
                    workspaceId TEXT NOT NULL, archived INTEGER NOT NULL, lastSequence INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(conversationId) REFERENCES agent_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(workspaceId) REFERENCES agent_harness_workspaces(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_harness_sessions_conversationId ON agent_harness_sessions(conversationId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_harness_sessions_workspaceId ON agent_harness_sessions(workspaceId)")

            // Content is deliberately retained. These status changes make already-posted alarms,
            // outbox recovery and old UI callbacks unable to resume the retired model loop.
            db.execSQL("UPDATE agent_continuation_outbox SET status = 'CANCELLED', errorClass = 'EngineReplaced', errorMessage = NULL WHERE status IN ('QUEUED', 'CLAIMED', 'ENQUEUED', 'FAILED')")
            db.execSQL("UPDATE agent_sleep_wakes SET status = 'CANCELLED' WHERE status IN ('PENDING', 'FIRED')")
            db.execSQL("UPDATE agent_pending_inputs SET status = 'CANCELLED' WHERE status IN ('QUEUED', 'CLAIMED', 'PENDING')")
            db.execSQL("UPDATE agent_pending_questions SET status = 'CANCELLED', continuationEnqueued = 0 WHERE status = 'PENDING'")
            db.execSQL("UPDATE agent_pending_questions SET continuationEnqueued = 1 WHERE status = 'ANSWERED'")
            db.execSQL("UPDATE agent_pending_plans SET state = 'CANCELLED', continuationEnqueued = 0 WHERE state IN ('AWAITING_APPROVAL', 'APPROVED', 'APPROVING', 'STARTING_BUILD', 'BUILDING')")
            db.execSQL("UPDATE agent_invocations SET status = 'INTERRUPTED', errorClass = 'EngineReplaced', errorMessage = NULL WHERE status IN ('RUNNING', 'QUEUED', 'WAITING', 'PENDING')")
            db.execSQL("UPDATE agent_turn_contexts SET status = 'MIGRATED', completedAt = COALESCE(completedAt, createdAt) WHERE status = 'ACTIVE'")
            db.execSQL("UPDATE ai_runtime_jobs SET status = 'CANCELLED' WHERE type = 'AGENT_CHAT' AND status IN ('RUNNING', 'RECOVERING')")
            db.execSQL("UPDATE agent_messages SET needsApproval = 0, isStreaming = 0 WHERE needsApproval = 1 OR isStreaming = 1")
            db.execSQL("UPDATE agent_conversations SET resumeState = 'INTERRUPTED', lastStopReason = 'legacy_engine_replaced' WHERE resumeState != 'IDLE'")
        }
    }
}
