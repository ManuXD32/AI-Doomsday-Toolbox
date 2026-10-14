package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds `llama_message_image_descriptions` (vision-helper text for images sent to non-vision models). */
object ImageDescriptionMigration {
    val MIGRATION_129_130: Migration = object : Migration(129, 130) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `llama_message_image_descriptions` (" +
                    "`messageId` INTEGER NOT NULL, " +
                    "`imagePath` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`model` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`messageId`), " +
                    "FOREIGN KEY(`messageId`) REFERENCES `llama_messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
        }
    }
}
