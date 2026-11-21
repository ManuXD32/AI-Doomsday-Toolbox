package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 136 -> 137: adds nullable lineage columns `origin` (`branchy_export` | `quantized`) and
 * `derivedFrom` (source filename) to `models`. Existing rows keep NULL.
 */
object ModelLineageMigration {
    val MIGRATION_136_137: Migration = object : Migration(136, 137) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `models` ADD COLUMN `origin` TEXT")
            db.execSQL("ALTER TABLE `models` ADD COLUMN `derivedFrom` TEXT")
        }
    }
}
