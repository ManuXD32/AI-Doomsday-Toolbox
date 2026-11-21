package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 137 -> 138: adds nullable `altHost` to `branchy_cluster_device` (the other Wi-Fi/VPN address of a helper,
 * used as a reachability fallback). Existing rows keep NULL.
 */
object BranchyAltHostMigration {
    val MIGRATION_137_138: Migration = object : Migration(137, 138) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `branchy_cluster_device` ADD COLUMN `altHost` TEXT")
        }
    }
}
