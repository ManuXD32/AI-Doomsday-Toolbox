package com.example.llamadroid.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Host checks for the 130 -> 131 steps / mailbox migration. */
@RunWith(RobolectricTestRunner::class)
class NativeChatStepsMigrationTest {
    @Test
    fun `migration spans 130 to 131 and is registered`() {
        val migration = NativeChatStepsMigration.MIGRATION_130_131
        assertEquals(130, migration.startVersion)
        assertEquals(131, migration.endVersion)
        assertTrue(Migrations.ALL_MIGRATIONS.contains(migration))
    }

    @Test
    fun `migration adds the columns with their defaults and is idempotent`() {
        openLegacy().use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO llama_messages(id) VALUES (1)")
            db.execSQL("INSERT INTO native_chat_delegate_tasks(id) VALUES (2)")
            db.execSQL("INSERT INTO native_chat_delegate_messages(id) VALUES (3)")

            NativeChatStepsMigration.MIGRATION_130_131.migrate(db)
            NativeChatStepsMigration.MIGRATION_130_131.migrate(db)

            db.query("SELECT stepsJson FROM llama_messages WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst()); assertNull(c.getString(0))
            }
            db.query("SELECT optionsJson, followUpCount FROM native_chat_delegate_tasks WHERE id = 2").use { c ->
                assertTrue(c.moveToFirst()); assertNull(c.getString(0)); assertEquals(0, c.getInt(1))
            }
            assertEquals(1, single(db, "SELECT delivered FROM native_chat_delegate_messages WHERE id = 3"))
            db.execSQL("INSERT INTO native_chat_delegate_messages(id) VALUES (4)")
            assertEquals(1, single(db, "SELECT delivered FROM native_chat_delegate_messages WHERE id = 4"))
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
                        db.execSQL("CREATE TABLE llama_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                        db.execSQL("CREATE TABLE native_chat_delegate_tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                        db.execSQL("CREATE TABLE native_chat_delegate_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
}
