package com.example.llamadroid.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Focused contract test for the non-destructive Debian environment schema hop. */
@RunWith(AndroidJUnit4::class)
class AgentProotMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AppDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate119To120_addsEnvironmentAndRunMetadataWithoutStartingProjects() {
        helper.createDatabase(TEST_DB, 119).apply {
            execSQL(
                """
                INSERT INTO agent_conversations (
                    id, title, projectFolder, projectFolderId, sortOrder,
                    planningModeEnabled, resumeState, lastAgentRole, lastTask,
                    knowledgeBaseIds, workspaceBackend, runtimeCapabilitiesJson,
                    runEntrypointPath, runUiMode, lastRunProfileJson,
                    executionProfile, directRuntimeVersion, directReanchorState,
                    directReanchorReason, directReanchoredAt, createdAt, updatedAt
                ) VALUES (
                    801, 'Preserved project', 'preserved_project', NULL, 0,
                    1, 'IDLE', 'ORCHESTRATOR', NULL,
                    '', 'LOCAL_SANDBOX', '', NULL, 'CONSOLE', '',
                    'direct', 1, 'COMPLETE', 'test', 100, 100, 100
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            120,
            true,
            Migrations.MIGRATION_119_120
        )

        migrated.query("SELECT title, prootEnvironmentId FROM agent_conversations WHERE id = 801").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Preserved project", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'agent_proot_environments'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'agent_proot_runs'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        migrated.query("PRAGMA table_info(agent_proot_environments)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            assertTrue(columns.containsAll(setOf("id", "storageKey", "imageDigest", "sharingMode", "status", "sizeBytes")))
        }
        migrated.query("PRAGMA table_info(agent_proot_runs)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            assertTrue(columns.containsAll(setOf("id", "conversationId", "environmentId", "processGeneration", "outputReference")))
        }
        migrated.close()
    }

    companion object {
        private const val TEST_DB = "agent-proot-migration-test"
    }
}
