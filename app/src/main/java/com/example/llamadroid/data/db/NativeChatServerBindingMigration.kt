package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Binds native chats to the connection row they talk to and adds the per-chat KV cache opt-in.
 * Existing chats stay unbound (`serverId` NULL) and are bound on their first send.
 */
object NativeChatServerBindingMigration {
    val MIGRATION_127_128: Migration = object : Migration(127, 128) {
        override fun migrate(db: SupportSQLiteDatabase) {
            if (!hasColumn(db, "serverId")) {
                db.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `serverId` INTEGER")
            }
            if (!hasColumn(db, "persistKvCache")) {
                db.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `persistKvCache` INTEGER NOT NULL DEFAULT 0")
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_chats_serverId` ON `llama_chats` (`serverId`)")
        }
    }

    private fun hasColumn(db: SupportSQLiteDatabase, column: String): Boolean =
        db.query("PRAGMA table_info(`llama_chats`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return@use true
            }
            false
        }
}
