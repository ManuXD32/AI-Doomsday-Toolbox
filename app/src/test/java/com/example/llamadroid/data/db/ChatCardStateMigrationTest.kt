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

/** Host checks for the 133 -> 134 chat card state table migration. */
@RunWith(RobolectricTestRunner::class)
class ChatCardStateMigrationTest {
    @Test
    fun `migration spans 133 to 134 and is registered`() {
        val migration = ChatCardStateMigration.MIGRATION_133_134
        assertEquals(133, migration.startVersion)
        assertEquals(134, migration.endVersion)
        assertTrue(Migrations.ALL_MIGRATIONS.contains(migration))
    }

    @Test
    fun `migration creates the table, is idempotent and cascades with the message`() {
        openLegacy().use { helper ->
            val db = helper.writableDatabase
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("INSERT INTO llama_messages(id) VALUES (7)")

            ChatCardStateMigration.MIGRATION_133_134.migrate(db)
            ChatCardStateMigration.MIGRATION_133_134.migrate(db)

            db.execSQL("INSERT INTO llama_message_card_state VALUES (7, 0, '{\"s\":[\"A\"]}', 1)")
            db.execSQL("INSERT OR REPLACE INTO llama_message_card_state VALUES (7, 0, '{}', 2)")
            db.execSQL("INSERT INTO llama_message_card_state VALUES (7, 1, '{}', 3)")
            assertEquals(2, single(db, "SELECT COUNT(*) FROM llama_message_card_state"))

            db.execSQL("DELETE FROM llama_messages WHERE id = 7")
            assertEquals(0, single(db, "SELECT COUNT(*) FROM llama_message_card_state"))
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
                        db.execSQL("CREATE TABLE llama_messages (id INTEGER PRIMARY KEY NOT NULL)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
}
