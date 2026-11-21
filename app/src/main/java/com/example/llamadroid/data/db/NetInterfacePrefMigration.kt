package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 138 -> 139: per-helper preferred network interface (`auto`, `vpn`, `lan`) for Branchy, LLM distributed and SD
 * distributed helpers. LLM and SD helpers also keep their second address (`altIp` / `altHost`, NULL for existing
 * rows) so a connection can fall back to it.
 */
object NetInterfacePrefMigration {
    val MIGRATION_138_139: Migration = object : Migration(138, 139) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `branchy_cluster_device` ADD COLUMN `preferredInterface` TEXT NOT NULL DEFAULT 'auto'")
            db.execSQL("ALTER TABLE `saved_workers` ADD COLUMN `altIp` TEXT")
            db.execSQL("ALTER TABLE `saved_workers` ADD COLUMN `preferredInterface` TEXT NOT NULL DEFAULT 'auto'")
            db.execSQL("ALTER TABLE `sd_distributed_workers` ADD COLUMN `altHost` TEXT")
            db.execSQL("ALTER TABLE `sd_distributed_workers` ADD COLUMN `preferredInterface` TEXT NOT NULL DEFAULT 'auto'")
        }
    }
}
