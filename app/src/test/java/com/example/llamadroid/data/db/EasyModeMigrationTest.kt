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

/** Host checks for the 131 -> 132 Easy-mode chat flag migration. */
@RunWith(RobolectricTestRunner::class)
class EasyModeMigrationTest {
    @Test
    fun `migration spans 131 to 132 and is registered`() {
        val migration = EasyModeMigration.MIGRATION_131_132
        assertEquals(131, migration.startVersion)
        assertEquals(132, migration.endVersion)
        assertTrue(Migrations.ALL_MIGRATIONS.contains(migration))
    }

    @Test
    fun `migration adds easyMode defaulting to advanced and is idempotent`() {
        openLegacy().use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO llama_chats(id) VALUES (1)")

            EasyModeMigration.MIGRATION_131_132.migrate(db)
            EasyModeMigration.MIGRATION_131_132.migrate(db)

            assertEquals(0, single(db, "SELECT easyMode FROM llama_chats WHERE id = 1"))
            db.execSQL("INSERT INTO llama_chats(id) VALUES (2)")
            assertEquals(0, single(db, "SELECT easyMode FROM llama_chats WHERE id = 2"))
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
                        db.execSQL("CREATE TABLE llama_chats (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
}
