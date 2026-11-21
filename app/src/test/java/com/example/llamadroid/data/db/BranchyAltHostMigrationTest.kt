package com.example.llamadroid.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Host checks for the 137 -> 138 Branchy helper altHost column. */
@RunWith(RobolectricTestRunner::class)
class BranchyAltHostMigrationTest {
    @Test
    fun `migration spans 137 to 138 and is registered`() {
        val migration = BranchyAltHostMigration.MIGRATION_137_138
        assertEquals(137, migration.startVersion)
        assertEquals(138, migration.endVersion)
        assertTrue(Migrations.ALL_MIGRATIONS.contains(migration))
    }

    @Test
    fun `exported 138 schema lists altHost`() {
        val schema = listOf(
            "schemas/com.example.llamadroid.data.db.AppDatabase/138.json",
            "app/schemas/com.example.llamadroid.data.db.AppDatabase/138.json"
        ).map(::File).first { it.isFile }.readText()
        assertTrue(schema.contains("\"version\": 138"))
        assertTrue(schema.contains("`pinned` INTEGER NOT NULL, `altHost` TEXT, PRIMARY KEY(`id`))"))
    }

    @Test
    fun `migration adds nullable altHost and keeps rows`() {
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
            db.execSQL("INSERT INTO branchy_cluster_device(id, host) VALUES ('a', '10.2.0.3')")
            BranchyAltHostMigration.MIGRATION_137_138.migrate(db)
            db.execSQL("UPDATE branchy_cluster_device SET altHost = '192.168.1.5' WHERE id = 'a'")
            db.execSQL("INSERT INTO branchy_cluster_device(id, host) VALUES ('b', '192.168.1.6')")
            db.query("SELECT altHost FROM branchy_cluster_device WHERE id = 'a'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("192.168.1.5", c.getString(0))
            }
            db.query("SELECT altHost FROM branchy_cluster_device WHERE id = 'b'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
            }
        }
    }
}
