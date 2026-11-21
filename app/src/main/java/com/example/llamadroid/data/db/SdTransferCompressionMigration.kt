package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 132 -> 133: adds `sd_distributed_master_settings.compressLayerTransfers` (BF16 transfers between
 * split devices, off by default). Idempotent.
 */
object SdTransferCompressionMigration {
    val MIGRATION_132_133: Migration = object : Migration(132, 133) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val exists = db.query("PRAGMA table_info(`sd_distributed_master_settings`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(nameIndex) == "compressLayerTransfers") found = true
                found
            }
            if (!exists) {
                db.execSQL(
                    "ALTER TABLE `sd_distributed_master_settings` ADD COLUMN `compressLayerTransfers` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
