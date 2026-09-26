package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Video-only additive migration; existing conversations and server choices are retained. */
object VideoVisionMigration {
    val MIGRATION_116_117 = object : Migration(116, 117) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `supportsVideo` INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `videoPath` TEXT")
        }
    }
}
