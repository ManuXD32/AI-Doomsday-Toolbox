package com.example.llamadroid.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.harness.RoomHarnessRuntimeStore
import com.example.llamadroid.harness.runtime.HarnessRuntimeRecord
import com.example.llamadroid.harness.runtime.HarnessRuntimeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HarnessMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun legacyContentSurvivesButEveryPendingExecutionIsRetired() {
        helper.createDatabase(DB_NAME, 122).apply {
            seed("agent_conversations", mapOf(
                "id" to 701, "title" to "Preserved title", "projectFolder" to "shared_project",
                "workspaceBackend" to "LOCAL_PROOT", "prootEnvironmentId" to "existing-debian",
                "lastRunProfileJson" to "{\"entrypoint\":\"serve.py\"}", "resumeState" to "RUNNING"
            ))
            seed("agent_messages", mapOf(
                "id" to 702, "originalId" to "kept-message", "conversationId" to 701,
                "role" to "assistant", "content" to "Keep this history", "needsApproval" to 1,
                "isStreaming" to 1
            ))
            seed("agent_continuation_outbox", mapOf(
                "id" to "outbox", "conversationId" to 701, "status" to "CLAIMED",
                "payloadJson" to "{\"keep\":true}"
            ))
            seed("agent_sleep_wakes", mapOf("id" to "wake", "conversationId" to 701, "status" to "FIRED"))
            seed("agent_pending_inputs", mapOf("id" to "input", "conversationId" to 701, "status" to "QUEUED", "content" to "Unsent text"))
            seed("agent_pending_questions", mapOf(
                "id" to "question", "conversationId" to 701, "status" to "ANSWERED",
                "answerJson" to "{\"answer\":\"saved\"}", "continuationEnqueued" to 0
            ))
            seed("agent_pending_plans", mapOf(
                "id" to "plan", "conversationId" to 701, "state" to "STARTING_BUILD",
                "originalPlan" to "Plan retained"
            ))
            close()
        }
        helper.runMigrationsAndValidate(DB_NAME, 123, true, HarnessMigration.MIGRATION_122_123).use { db ->
            db.query("SELECT title, projectFolder, workspaceBackend, prootEnvironmentId, runtimeSource, lastRunProfileJson FROM agent_conversations WHERE id=701").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("Preserved title", row.getString(0))
                assertEquals("shared_project", row.getString(1))
                assertEquals("LOCAL_PROOT", row.getString(2))
                assertEquals("existing-debian", row.getString(3))
                assertEquals("LEGACY_ARCHIVE", row.getString(4))
                assertEquals("{\"entrypoint\":\"serve.py\"}", row.getString(5))
            }
            assertEquals("Keep this history", db.scalar("SELECT content FROM agent_messages WHERE id=702"))
            assertEquals("0", db.scalar("SELECT needsApproval FROM agent_messages WHERE id=702"))
            assertEquals("0", db.scalar("SELECT isStreaming FROM agent_messages WHERE id=702"))
            assertEquals("CANCELLED", db.scalar("SELECT status FROM agent_continuation_outbox"))
            assertEquals("CANCELLED", db.scalar("SELECT status FROM agent_sleep_wakes"))
            assertEquals("CANCELLED", db.scalar("SELECT status FROM agent_pending_inputs"))
            assertEquals("1", db.scalar("SELECT continuationEnqueued FROM agent_pending_questions"))
            assertEquals("{\"answer\":\"saved\"}", db.scalar("SELECT answerJson FROM agent_pending_questions"))
            assertEquals("CANCELLED", db.scalar("SELECT state FROM agent_pending_plans"))
            assertEquals("Plan retained", db.scalar("SELECT originalPlan FROM agent_pending_plans"))
            assertEquals("0", db.scalar("SELECT COUNT(*) FROM agent_harness_runtime"))
            assertEquals("0", db.scalar("SELECT COUNT(*) FROM agent_harness_sessions"))
            val runtimeColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(agent_harness_runtime)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) runtimeColumns += cursor.getString(nameIndex)
            }
            assertTrue("nodePid" in runtimeColumns)
            assertTrue("nodeStartTicks" in runtimeColumns)
        }
    }

    @Test
    fun runtimeStoreRoundTripsNodeIdentity() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val record = HarnessRuntimeRecord(
                environmentId = HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID,
                state = HarnessRuntimeState.RUNNING,
                generation = "generation-node-roundtrip",
                brokerPid = 100,
                processGroupId = 101,
                processStartTicks = 777,
                childPid = 101,
                childStartTicks = 778,
                nodePid = 102,
                nodeStartTicks = 779,
                port = 39000,
                startedAt = 1_700_000_000_000L
            )
            val store = RoomHarnessRuntimeStore(database)

            store.replace(record)

            val restored = requireNotNull(store.current())
            assertEquals(record.generation, restored.generation)
            assertEquals(102, restored.nodePid)
            assertEquals(779L, restored.nodeStartTicks)
        } finally {
            database.close()
        }
    }

    /** Fill only required old-schema columns, then set the state under test explicitly. */
    private fun SupportSQLiteDatabase.seed(table: String, values: Map<String, Any>) {
        val row = linkedMapOf<String, Any?>()
        query("PRAGMA table_info($table)").use { columns ->
            while (columns.moveToNext()) {
                val name = columns.getString(columns.getColumnIndexOrThrow("name"))
                val required = columns.getInt(columns.getColumnIndexOrThrow("notnull")) != 0
                val default = columns.getString(columns.getColumnIndexOrThrow("dflt_value"))
                if (required && default == null) {
                    val type = columns.getString(columns.getColumnIndexOrThrow("type"))
                    row[name] = if (type == "TEXT") "" else 0
                }
            }
        }
        row.putAll(values)
        execSQL("INSERT INTO $table (${row.keys.joinToString()}) VALUES (${row.keys.joinToString { "?" }})", row.values.toTypedArray())
    }

    private fun SupportSQLiteDatabase.scalar(sql: String): String = query(sql).use {
        check(it.moveToFirst())
        it.getString(0)
    }

    private companion object { const val DB_NAME = "harness-migration-test" }
}
