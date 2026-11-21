package com.example.llamadroid.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Host checks for the 132 -> 133 split-transfer compression flag migration. */
@RunWith(RobolectricTestRunner::class)
class SdTransferCompressionMigrationTest {
    @Test
    fun `migration spans 132 to 133 and is registered`() {
        val migration = SdTransferCompressionMigration.MIGRATION_132_133
        assertEquals(132, migration.startVersion)
        assertEquals(133, migration.endVersion)
        assertTrue(Migrations.ALL_MIGRATIONS.contains(migration))
    }

    @Test
    fun `migration adds compressLayerTransfers defaulting to off and is idempotent`() {
        openLegacy().use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO sd_distributed_master_settings(id) VALUES (1)")

            SdTransferCompressionMigration.MIGRATION_132_133.migrate(db)
            SdTransferCompressionMigration.MIGRATION_132_133.migrate(db)

            assertEquals(0, single(db, "SELECT compressLayerTransfers FROM sd_distributed_master_settings WHERE id = 1"))
            db.execSQL("INSERT INTO sd_distributed_master_settings(id) VALUES (2)")
            assertEquals(0, single(db, "SELECT compressLayerTransfers FROM sd_distributed_master_settings WHERE id = 2"))
        }
    }

    private fun single(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { c -> assertTrue(c.moveToFirst()); c.getInt(0) }

    private fun openLegacy(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE sd_distributed_master_settings (id INTEGER PRIMARY KEY NOT NULL)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
}
