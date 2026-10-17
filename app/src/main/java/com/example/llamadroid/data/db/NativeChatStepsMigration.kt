package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 130 -> 131: visible work steps on native chat replies (`llama_messages.stepsJson`), task options and
 * follow-up rounds on DeepSeek tasks, and the durable mailbox flag on subagent messages.
 * Idempotent: each column is added only when missing.
 */
object NativeChatStepsMigration {
    val MIGRATION_130_131: Migration = object : Migration(130, 131) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "llama_messages", "stepsJson", "TEXT")
            addColumnIfMissing(db, "native_chat_delegate_tasks", "optionsJson", "TEXT")
            addColumnIfMissing(db, "native_chat_delegate_tasks", "followUpCount", "INTEGER NOT NULL DEFAULT 0")
            // Existing rows were already handled by the in-memory mailbox: mark them delivered.
            addColumnIfMissing(db, "native_chat_delegate_messages", "delivered", "INTEGER NOT NULL DEFAULT 1")
        }
    }

    private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, definition: String) {
        val exists = db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) found = true
            found
        }
        if (!exists) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
    }
}
