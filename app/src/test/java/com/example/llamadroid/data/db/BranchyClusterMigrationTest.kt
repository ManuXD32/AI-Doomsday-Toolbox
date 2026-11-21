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

/** Host checks for the 135 -> 136 Branchy cluster device table. */
@RunWith(RobolectricTestRunner::class)
class BranchyClusterMigrationTest {
    @Test
    fun `migration spans 135 to 136 and is registered`() {
        val migration = BranchyClusterMigration.MIGRATION_135_136
        assertEquals(135, migration.startVersion)
        assertEquals(136, migration.endVersion)
        assertTrue(Migrations.ALL_MIGRATIONS.contains(migration))
    }

    @Test
    fun `table sql matches the exported 136 schema`() {
        val schema = listOf(
            "schemas/com.example.llamadroid.data.db.AppDatabase/136.json",
            "app/schemas/com.example.llamadroid.data.db.AppDatabase/136.json"
        ).map(::File).first { it.isFile }.readText()
        val marker = "\"tableName\": \"branchy_cluster_device\""
        val tail = schema.substring(schema.indexOf(marker))
        val createSql = Regex("\"createSql\": \"(.*?)\",").find(tail)!!.groupValues[1].replace("\\u0060", "`")
        val expected = BranchyClusterMigration.CREATE_DEVICE_TABLE_SQL
            .replace("`branchy_cluster_device`", "`\${TABLE_NAME}`")
        assertEquals(expected, createSql)
    }

    @Test
    fun `migration creates the table, is idempotent and keeps rows`() {
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
            BranchyClusterMigration.MIGRATION_135_136.migrate(db)
            db.execSQL(
                "INSERT INTO branchy_cluster_device(id, name, host, port, role, gflops, ramMb, lastSeen, pinned) " +
                    "VALUES ('h-1', 'Pixel', '10.0.0.2', 50210, 'train', NULL, NULL, 5, 1)"
            )
            BranchyClusterMigration.MIGRATION_135_136.migrate(db)
            db.query("SELECT name, pinned, gflops FROM branchy_cluster_device WHERE id = 'h-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Pixel", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertTrue(c.isNull(2))
            }
        }
    }
}
