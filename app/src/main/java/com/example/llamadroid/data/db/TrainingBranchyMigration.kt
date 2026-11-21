package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 134 -> 135: adds `training_dataset` (datasets for Branchy training) and `branchy_run`
 * (Branchy training runs). Idempotent; no existing data is touched.
 */
object TrainingBranchyMigration {
    val MIGRATION_134_135: Migration = object : Migration(134, 135) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `training_dataset` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, `hfRepo` TEXT, `hfConfig` TEXT, `hfSplit` TEXT, " +
                    "`revision` TEXT, `licence` TEXT NOT NULL, `format` TEXT NOT NULL, " +
                    "`columnMapJson` TEXT NOT NULL, `localPath` TEXT NOT NULL, " +
                    "`sizeBytes` INTEGER NOT NULL, `rowCount` INTEGER, `status` TEXT NOT NULL, " +
                    "`error` TEXT, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `branchy_run` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`recipeId` TEXT, `stage` INTEGER NOT NULL, `runDir` TEXT NOT NULL, " +
                    "`configJson` TEXT NOT NULL, `status` TEXT NOT NULL, `lastStep` INTEGER NOT NULL, " +
                    "`totalSteps` INTEGER NOT NULL, `lastLoss` REAL, `lastValLoss` REAL, " +
                    "`tokensSeen` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, `error` TEXT)"
            )
        }
    }
}
