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

/** Host checks for the 138 -> 139 preferred-interface columns. */
@RunWith(RobolectricTestRunner::class)
class NetInterfacePrefMigrationTest {
    @Test
    fun `migration spans 138 to 139 and is registered`() {
        val migration = NetInterfacePrefMigration.MIGRATION_138_139
        assertEquals(138, migration.startVersion)
        assertEquals(139, migration.endVersion)
        assertTrue(Migrations.ALL_MIGRATIONS.contains(migration))
    }

    @Test
    fun `migration adds the columns and existing rows default to auto`() {
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        ).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("CREATE TABLE `branchy_cluster_device` (`id` TEXT NOT NULL, `host` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE `saved_workers` (`id` INTEGER NOT NULL, `ip` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE `sd_distributed_workers` (`id` INTEGER NOT NULL, `host` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO branchy_cluster_device(id, host) VALUES ('a', '10.2.0.3')")
            db.execSQL("INSERT INTO saved_workers(id, ip) VALUES (1, '10.2.0.3')")
            db.execSQL("INSERT INTO sd_distributed_workers(id, host) VALUES (1, '10.2.0.3')")
            NetInterfacePrefMigration.MIGRATION_138_139.migrate(db)
            db.query("SELECT preferredInterface FROM branchy_cluster_device").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("auto", c.getString(0))
            }
            db.query("SELECT preferredInterface, altIp FROM saved_workers").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("auto", c.getString(0)); assertTrue(c.isNull(1))
            }
            db.query("SELECT preferredInterface, altHost FROM sd_distributed_workers").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("auto", c.getString(0)); assertTrue(c.isNull(1))
            }
        }
    }
}
