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

/** Host checks for the 129 -> 130 image description migration. */
@RunWith(RobolectricTestRunner::class)
class ImageDescriptionMigrationTest {
    @Test
    fun `migration spans 129 to 130 and is registered`() {
        val migration = ImageDescriptionMigration.MIGRATION_129_130
        assertEquals(129, migration.startVersion)
        assertEquals(130, migration.endVersion)
        assertTrue(Migrations.ALL_MIGRATIONS.contains(migration))
    }

    @Test
    fun `migration creates the table, is idempotent and cascades with the message`() {
        openLegacy().use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO llama_messages(id) VALUES (10)")

            ImageDescriptionMigration.MIGRATION_129_130.migrate(db)
            ImageDescriptionMigration.MIGRATION_129_130.migrate(db)

            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                "INSERT INTO llama_message_image_descriptions(messageId, imagePath, description, model, createdAt) " +
                    "VALUES (10, '/a.png', 'a cat', 'm', 1)"
            )
            assertEquals(1L, count(db))
            db.execSQL("DELETE FROM llama_messages WHERE id = 10")
            assertEquals(0L, count(db))
        }
    }

    private fun count(db: SupportSQLiteDatabase): Long =
        db.query("SELECT COUNT(*) FROM llama_message_image_descriptions").use { c ->
            assertTrue(c.moveToFirst()); c.getLong(0)
        }

    private fun openLegacy(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE llama_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
}
