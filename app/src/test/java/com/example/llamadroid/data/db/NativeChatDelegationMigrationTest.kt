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

/** Host checks for the 128 -> 129 delegation / attachment / compaction migration. */
@RunWith(RobolectricTestRunner::class)
class NativeChatDelegationMigrationTest {
    @Test
    fun `migration spans 128 to 129 and is registered`() {
        val migration = NativeChatDelegationMigration.MIGRATION_128_129
        assertEquals(128, migration.startVersion)
        assertEquals(129, migration.endVersion)
        assertTrue(Migrations.ALL_MIGRATIONS.contains(migration))
    }

    @Test
    fun `migration adds kind column and tables, is idempotent and cascades`() {
        openLegacy().use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO llama_chats(id) VALUES (1)")
            db.execSQL("INSERT INTO llama_messages(id, chatId) VALUES (10, 1)")

            NativeChatDelegationMigration.MIGRATION_128_129.migrate(db)
            NativeChatDelegationMigration.MIGRATION_128_129.migrate(db)

            assertTrue(columns(db, "llama_messages").contains("kind"))
            assertEquals(null, scalarString(db, "SELECT kind FROM llama_messages WHERE id = 10"))
            listOf(
                "native_chat_delegate_tasks", "native_chat_delegate_messages", "native_chat_delegate_events",
                "llama_message_attachments", "native_chat_compactions"
            ).forEach { assertTrue("missing $it", tableExists(db, it)) }
            listOf(
                "index_native_chat_delegate_tasks_chatId_state",
                "index_native_chat_delegate_messages_taskId_seq",
                "index_llama_message_attachments_taskId",
                "index_native_chat_compactions_chatId"
            ).forEach { assertTrue("missing $it", indexExists(db, it)) }

            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                "INSERT INTO native_chat_delegate_tasks(id, chatId, state, title, request, runtimeId, " +
                    "harnessThreadsJson, createdAt, updatedAt) VALUES (5, 1, 'QUEUED', 't', 'r', 'rt', '[]', 1, 1)"
            )
            db.execSQL(
                "INSERT INTO native_chat_delegate_messages(taskId, seq, role, content, createdAt) " +
                    "VALUES (5, 1, 'user', 'c', 1)"
            )
            db.execSQL(
                "INSERT INTO native_chat_compactions(chatId, boundaryMessageId, summary, tokensBefore, tokensAfter, createdAt) " +
                    "VALUES (1, 10, 's', 100, 10, 1)"
            )
            assertEquals(0L, scalarLong(db, "SELECT alarmCount FROM native_chat_delegate_tasks WHERE id = 5"))
            db.execSQL("DELETE FROM llama_chats WHERE id = 1")
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM native_chat_delegate_tasks"))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM native_chat_delegate_messages"))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM native_chat_compactions"))
        }
    }

    @Test
    fun `migration rewrites shared runtime row, drops orphans and keeps per id rows`() {
        openLegacy().use { helper ->
            val db = helper.writableDatabase
            createRuntimeTables(db)
            db.execSQL("INSERT INTO agent_proot_environments(id) VALUES ('env-a')")
            db.execSQL("INSERT INTO agent_harness_runtime(id, environmentId, state) VALUES ('shared', 'env-a', 'RUNNING')")

            NativeChatDelegationMigration.MIGRATION_128_129.migrate(db)
            NativeChatDelegationMigration.MIGRATION_128_129.migrate(db)

            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM agent_harness_runtime WHERE id = 'shared'"))
            assertEquals("RUNNING", scalarString(db, "SELECT state FROM agent_harness_runtime WHERE id = 'env-a'"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM agent_harness_runtime"))
        }
    }

    @Test
    fun `migration deletes orphan shared row`() {
        openLegacy().use { helper ->
            val db = helper.writableDatabase
            createRuntimeTables(db)
            db.execSQL("INSERT INTO agent_harness_runtime(id, environmentId, state) VALUES ('shared', 'gone', 'RUNNING')")

            NativeChatDelegationMigration.MIGRATION_128_129.migrate(db)

            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM agent_harness_runtime"))
        }
    }

    @Test
    fun `migration keeps existing per id row and drops the shared duplicate`() {
        openLegacy().use { helper ->
            val db = helper.writableDatabase
            createRuntimeTables(db)
            db.execSQL("INSERT INTO agent_proot_environments(id) VALUES ('env-b')")
            db.execSQL("INSERT INTO agent_harness_runtime(id, environmentId, state) VALUES ('env-b', 'env-b', 'STOPPED')")
            db.execSQL("INSERT INTO agent_harness_runtime(id, environmentId, state) VALUES ('shared', 'env-b', 'RUNNING')")

            NativeChatDelegationMigration.MIGRATION_128_129.migrate(db)

            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM agent_harness_runtime"))
            assertEquals("STOPPED", scalarString(db, "SELECT state FROM agent_harness_runtime WHERE id = 'env-b'"))
        }
    }

    private fun createRuntimeTables(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE agent_proot_environments (id TEXT NOT NULL PRIMARY KEY)")
        db.execSQL(
            "CREATE TABLE agent_harness_runtime (id TEXT NOT NULL PRIMARY KEY, environmentId TEXT NOT NULL, state TEXT NOT NULL)"
        )
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val idx = c.getColumnIndex("name")
            buildList { while (c.moveToNext()) add(c.getString(idx)) }
        }

    private fun tableExists(db: SupportSQLiteDatabase, name: String) = existsInMaster(db, "table", name)
    private fun indexExists(db: SupportSQLiteDatabase, name: String) = existsInMaster(db, "index", name)

    private fun existsInMaster(db: SupportSQLiteDatabase, type: String, name: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?", arrayOf(type, name))
            .use { it.moveToFirst() }

    private fun scalarString(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }

    private fun scalarLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }

    private fun openLegacy(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE llama_chats (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                        db.execSQL(
                            "CREATE TABLE llama_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "chatId INTEGER, FOREIGN KEY(chatId) REFERENCES llama_chats(id) ON DELETE CASCADE)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
}
