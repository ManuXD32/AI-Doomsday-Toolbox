package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 131 -> 132: marks chats created in Easy mode (`llama_chats.easyMode`) so advanced lists, search and
 * backups can exclude them. Existing chats are advanced chats (0). Idempotent.
 */
object EasyModeMigration {
    val MIGRATION_131_132: Migration = object : Migration(131, 132) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val exists = db.query("PRAGMA table_info(`llama_chats`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(nameIndex) == "easyMode") found = true
                found
            }
            if (!exists) db.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `easyMode` INTEGER NOT NULL DEFAULT 0")
        }
    }
}
