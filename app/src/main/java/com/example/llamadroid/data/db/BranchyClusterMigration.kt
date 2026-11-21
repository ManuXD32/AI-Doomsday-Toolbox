package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 135 -> 136: adds `branchy_cluster_device` (helper devices of the Branchy training cluster).
 * Idempotent; no existing data is touched.
 */
object BranchyClusterMigration {
    /** Exact table definition Room generates for `BranchyClusterDeviceEntity`. */
    const val CREATE_DEVICE_TABLE_SQL: String =
        "CREATE TABLE IF NOT EXISTS `branchy_cluster_device` (" +
            "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, " +
            "`role` TEXT NOT NULL, `gflops` REAL, `ramMb` INTEGER, `lastSeen` INTEGER NOT NULL, " +
            "`pinned` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    val MIGRATION_135_136: Migration = object : Migration(135, 136) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_DEVICE_TABLE_SQL)
        }
    }
}
