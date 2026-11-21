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

/** Host checks for the 136 -> 137 model lineage columns. */
@RunWith(RobolectricTestRunner::class)
class ModelLineageMigrationTest {
    @Test
    fun `migration spans 136 to 137 and is registered`() {
        val migration = ModelLineageMigration.MIGRATION_136_137
        assertEquals(136, migration.startVersion)
        assertEquals(137, migration.endVersion)
        assertTrue(Migrations.ALL_MIGRATIONS.contains(migration))
    }

    @Test
    fun `exported 137 schema lists the lineage columns`() {
        val schema = listOf(
            "schemas/com.example.llamadroid.data.db.AppDatabase/137.json",
            "app/schemas/com.example.llamadroid.data.db.AppDatabase/137.json"
        ).map(::File).first { it.isFile }.readText()
        assertTrue(schema.contains("\"version\": 137"))
        assertTrue(schema.contains("`origin` TEXT, `derivedFrom` TEXT, PRIMARY KEY(`filename`))"))
    }

    @Test
    fun `migration adds nullable columns and keeps rows`() {
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
            db.execSQL("CREATE TABLE `models` (`filename` TEXT NOT NULL, `path` TEXT NOT NULL, PRIMARY KEY(`filename`))")
            db.execSQL("INSERT INTO models(filename, path) VALUES ('a.gguf', '/m/a.gguf')")
            ModelLineageMigration.MIGRATION_136_137.migrate(db)
            db.execSQL("UPDATE models SET origin = 'quantized', derivedFrom = 'b.gguf' WHERE filename = 'a.gguf'")
            db.execSQL("INSERT INTO models(filename, path) VALUES ('c.gguf', '/m/c.gguf')")
            db.query("SELECT origin, derivedFrom FROM models WHERE filename = 'a.gguf'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("quantized", c.getString(0))
                assertEquals("b.gguf", c.getString(1))
            }
            db.query("SELECT origin, derivedFrom FROM models WHERE filename = 'c.gguf'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
                assertTrue(c.isNull(1))
            }
        }
    }
}
