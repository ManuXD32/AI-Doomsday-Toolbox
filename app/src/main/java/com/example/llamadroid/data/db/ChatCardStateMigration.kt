package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 133 -> 134: adds `llama_message_card_state`, the answered state of interactive chat cards
 * (one row per message and card index, deleted with the message). Idempotent.
 */
object ChatCardStateMigration {
    val MIGRATION_133_134: Migration = object : Migration(133, 134) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `llama_message_card_state` (" +
                    "`messageId` INTEGER NOT NULL, `cardIndex` INTEGER NOT NULL, " +
                    "`stateJson` TEXT NOT NULL, `answeredAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`messageId`, `cardIndex`), " +
                    "FOREIGN KEY(`messageId`) REFERENCES `llama_messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
        }
    }
}
