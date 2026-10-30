package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the DeepSeek delegation, chat attachment and main-chat compaction tables plus the
 * `llama_messages.kind` column. Each table lives in its own private function so later
 * additions (for example per-runtime rows) can extend this single 128 -> 129 step.
 */
object NativeChatDelegationMigration {
    val MIGRATION_128_129: Migration = object : Migration(128, 129) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addMessageKindColumn(db)
            createDelegateTasks(db)
            createDelegateMessages(db)
            createDelegateEvents(db)
            createMessageAttachments(db)
            createCompactions(db)
            rewriteSharedRuntimeRow(db)
        }
    }

    /**
     * Multi-runtime: the process row is keyed by runtime id instead of the singleton `'shared'`.
     * Rewrites the shared row to `id = environmentId` when that id is free and the environment still
     * exists, then drops whatever shared row remains. Idempotent; tolerates missing tables.
     */
    private fun rewriteSharedRuntimeRow(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "agent_harness_runtime")) return
        if (tableExists(db, "agent_proot_environments")) {
            db.execSQL(
                "UPDATE `agent_harness_runtime` SET `id` = `environmentId` WHERE `id` = 'shared' " +
                    "AND NOT EXISTS (SELECT 1 FROM `agent_harness_runtime` r WHERE r.`id` = `agent_harness_runtime`.`environmentId`) " +
                    "AND EXISTS (SELECT 1 FROM `agent_proot_environments` e WHERE e.`id` = `agent_harness_runtime`.`environmentId`)"
            )
        }
        db.execSQL("DELETE FROM `agent_harness_runtime` WHERE `id` = 'shared'")
    }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name))
            .use { it.moveToFirst() }

    private fun addMessageKindColumn(db: SupportSQLiteDatabase) {
        if (!hasColumn(db, "llama_messages", "kind")) {
            db.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `kind` TEXT")
        }
    }

    private fun createDelegateTasks(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `native_chat_delegate_tasks` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`chatId` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`request` TEXT NOT NULL, " +
                "`runtimeId` TEXT NOT NULL, " +
                "`workspaceId` TEXT, " +
                "`harnessSessionId` TEXT, " +
                "`harnessThreadsJson` TEXT NOT NULL, " +
                "`harnessConversationId` INTEGER, " +
                "`harnessModelJson` TEXT, " +
                "`subagentServerJson` TEXT, " +
                "`resultStatus` TEXT, " +
                "`resultSummary` TEXT, " +
                "`resultFilesJson` TEXT, " +
                "`digestJson` TEXT, " +
                "`progressNote` TEXT, " +
                "`nextCheckAt` INTEGER, " +
                "`checkNote` TEXT, " +
                "`checkCadenceMinutes` INTEGER, " +
                "`alarmCount` INTEGER NOT NULL DEFAULT 0, " +
                "`wakeCount` INTEGER NOT NULL DEFAULT 0, " +
                "`subagentModelCalls` INTEGER NOT NULL DEFAULT 0, " +
                "`harnessPrompts` INTEGER NOT NULL DEFAULT 0, " +
                "`compactionCount` INTEGER NOT NULL DEFAULT 0, " +
                "`pendingWakeKind` TEXT, " +
                "`pendingWakeAt` INTEGER, " +
                "`lastErrorCode` TEXT, " +
                "`ctxUsedTokens` INTEGER NOT NULL DEFAULT 0, " +
                "`ctxWindowTokens` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`finishedAt` INTEGER, " +
                "FOREIGN KEY(`chatId`) REFERENCES `llama_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_native_chat_delegate_tasks_chatId` ON `native_chat_delegate_tasks` (`chatId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_native_chat_delegate_tasks_chatId_state` ON `native_chat_delegate_tasks` (`chatId`, `state`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_native_chat_delegate_tasks_state` ON `native_chat_delegate_tasks` (`state`)")
    }

    private fun createDelegateMessages(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `native_chat_delegate_messages` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`taskId` INTEGER NOT NULL, " +
                "`seq` INTEGER NOT NULL, " +
                "`role` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, " +
                "`toolName` TEXT, " +
                "`toolCallId` TEXT, " +
                "`toolCallsJson` TEXT, " +
                "`thinking` TEXT, " +
                "`promptTokens` INTEGER NOT NULL DEFAULT 0, " +
                "`completionTokens` INTEGER NOT NULL DEFAULT 0, " +
                "`compactedOut` INTEGER NOT NULL DEFAULT 0, " +
                "`isSummary` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`taskId`) REFERENCES `native_chat_delegate_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_native_chat_delegate_messages_taskId` ON `native_chat_delegate_messages` (`taskId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_native_chat_delegate_messages_taskId_seq` ON `native_chat_delegate_messages` (`taskId`, `seq`)")
    }

    private fun createDelegateEvents(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `native_chat_delegate_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`taskId` INTEGER NOT NULL, " +
                "`at` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`phase` TEXT, " +
                "`toolName` TEXT, " +
                "`status` TEXT, " +
                "`count` INTEGER, " +
                "`durationMs` INTEGER, " +
                "`errorClass` TEXT, " +
                "FOREIGN KEY(`taskId`) REFERENCES `native_chat_delegate_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_native_chat_delegate_events_taskId` ON `native_chat_delegate_events` (`taskId`)")
    }

    private fun createMessageAttachments(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `llama_message_attachments` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`messageId` INTEGER, " +
                "`chatId` INTEGER NOT NULL, " +
                "`path` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, " +
                "`mimeType` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`taskId` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`messageId`) REFERENCES `llama_messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`chatId`) REFERENCES `llama_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_message_attachments_messageId` ON `llama_message_attachments` (`messageId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_message_attachments_chatId` ON `llama_message_attachments` (`chatId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_message_attachments_taskId` ON `llama_message_attachments` (`taskId`)")
    }

    private fun createCompactions(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `native_chat_compactions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`chatId` INTEGER NOT NULL, " +
                "`boundaryMessageId` INTEGER NOT NULL, " +
                "`summary` TEXT NOT NULL, " +
                "`tokensBefore` INTEGER NOT NULL, " +
                "`tokensAfter` INTEGER NOT NULL, " +
                "`measured` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`chatId`) REFERENCES `llama_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_native_chat_compactions_chatId` ON `native_chat_compactions` (`chatId`)")
    }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return@use true
            }
            false
        }
}
