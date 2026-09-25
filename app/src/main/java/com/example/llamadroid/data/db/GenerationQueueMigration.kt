package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object GenerationQueueMigration {
    val MIGRATION_123_124: Migration = object : Migration(123, 124) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS generation_queue_items (
                    id TEXT NOT NULL PRIMARY KEY,
                    kind TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    promptPreview TEXT NOT NULL,
                    configJson TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    runId TEXT,
                    createdAtMillis INTEGER NOT NULL,
                    startedAtMillis INTEGER,
                    finishedAtMillis INTEGER,
                    resultPath TEXT,
                    metadataPath TEXT,
                    errorMessage TEXT
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_generation_queue_items_status_sortOrder ON generation_queue_items(status, sortOrder)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_generation_queue_items_runId ON generation_queue_items(runId)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS generation_queue_control (
                    id INTEGER NOT NULL PRIMARY KEY,
                    state TEXT NOT NULL,
                    scheduledAtMillis INTEGER,
                    runId TEXT,
                    activeItemId TEXT,
                    updatedAtMillis INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }
}
