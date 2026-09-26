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

@RunWith(AndroidJUnit4::class)
class AgentPreviewMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AppDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate120To121_preservesProjectAndDefaultsPreviewToAutomatic() {
        helper.createDatabase(TEST_DB, 120).apply {
            execSQL(
                """
                INSERT INTO agent_conversations (
                    id, title, projectFolder, projectFolderId, sortOrder,
                    planningModeEnabled, resumeState, lastAgentRole, lastTask,
                    knowledgeBaseIds, workspaceBackend, prootEnvironmentId,
                    runtimeCapabilitiesJson, runEntrypointPath, runUiMode,
                    lastRunProfileJson, executionProfile, directRuntimeVersion,
                    directReanchorState, directReanchorReason, directReanchoredAt,
                    createdAt, updatedAt
                ) VALUES (
                    901, 'Debian project', 'debian_project', NULL, 0,
                    1, 'IDLE', 'ORCHESTRATOR', NULL,
                    '', 'LOCAL_PROOT', NULL,
                    '', NULL, 'WEB', '', 'direct', 1,
                    'COMPLETE', 'test', 100, 100, 100
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            121,
            true,
            Migrations.MIGRATION_120_121
        )
        migrated.query(
            "SELECT title, workspaceBackend, previewUrlOverride FROM agent_conversations WHERE id = 901"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Debian project", cursor.getString(0))
            assertEquals("LOCAL_PROOT", cursor.getString(1))
            assertTrue(cursor.isNull(2))
        }
        migrated.close()
    }

    companion object {
        private const val TEST_DB = "agent-preview-migration-test"
    }
}
