package com.example.llamadroid.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Focused 126 -> 127 checks for runtime ownership and installation metadata. */
@RunWith(RobolectricTestRunner::class)
class HarnessInstallationsMigrationTest {
    @Test
    fun `migration scopes existing rows and adopts shared environment metadata`() {
        openLegacy().use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO agent_conversations(id) VALUES (1)")
            db.execSQL(
                "INSERT INTO agent_proot_environments " +
                    "(id, displayName, storageKey, imageId, imageVersion, imageDigest, sharingMode, status, sizeBytes, lastUsedAt, createdAt, updatedAt) " +
                    "VALUES ('legacy-env', 'Legacy', 'legacy-env', 'image', '1', '', 'ISOLATED', 'READY', 12, NULL, 1, 1)"
            )
            db.execSQL(
                "INSERT INTO agent_harness_workspaces " +
                    "(id, backend, projectFolder, connectionKey, title, guestPath, harnessWorkspaceId, prootEnvironmentId, createdAt, updatedAt) " +
                    "VALUES ('w1', 'LOCAL_PROOT', 'demo', '', 'Demo', '/workspace/projects/demo', 'group-1', 'legacy-env', 1, 1)"
            )
            db.execSQL(
                "INSERT INTO agent_harness_sessions " +
                    "(harnessSessionId, conversationId, workspaceId, archived, lastSequence, createdAt, updatedAt) " +
                    "VALUES ('session-1', 1, 'w1', 0, -1, 1, 1)"
            )

            HarnessInstallationsMigration.MIGRATION_126_127.migrate(db)

            assertEquals(
                HarnessRuntimeIds.LEGACY,
                scalarString(db, "SELECT runtimeId FROM agent_conversations WHERE id = 1")
            )
            assertEquals(
                HarnessRuntimeIds.LEGACY,
                scalarString(db, "SELECT runtimeId FROM agent_harness_workspaces WHERE id = 'w1'")
            )
            assertEquals(
                HarnessRuntimeIds.LEGACY,
                scalarString(db, "SELECT runtimeId FROM agent_harness_sessions WHERE harnessSessionId = 'session-1'")
            )
            assertEquals(
                "LEGACY",
                scalarString(db, "SELECT purpose FROM agent_proot_environments WHERE id = 'legacy-env'")
            )
            assertEquals(
                "SCOPED",
                scalarString(db, "SELECT dataLayout FROM agent_proot_environments WHERE id = 'legacy-env'")
            )
            assertEquals(
                "HARNESS",
                scalarString(db, "SELECT purpose FROM agent_proot_environments WHERE id = 'deepseek-harness-shared'")
            )
            assertEquals(
                "LEGACY",
                scalarString(db, "SELECT dataLayout FROM agent_proot_environments WHERE id = 'deepseek-harness-shared'")
            )
            assertTrue(indexExists(db, "index_agent_harness_workspaces_runtimeId_backend_projectFolder_connectionKey"))
            assertTrue(indexExists(db, "index_agent_harness_sessions_runtimeId_conversationId"))
            assertTrue(indexExists(db, "index_agent_conversations_runtimeId"))
            assertTrue(indexExists(db, "index_agent_proot_environments_purpose"))
            assertTrue(tableExists(db, "agent_harness_transfer_receipts"))
            assertFalse(indexExists(db, "index_agent_harness_workspaces_backend_projectFolder_connectionKey"))
            assertTrue(indexExists(db, "index_agent_harness_sessions_conversationId"))

            // A retry after an interrupted upgrade is metadata-only and does not duplicate rows.
            HarnessInstallationsMigration.MIGRATION_126_127.migrate(db)
            assertEquals(1, scalarLong(db, "SELECT COUNT(*) FROM agent_proot_environments WHERE id = 'deepseek-harness-shared'"))
            assertEquals(1, scalarLong(db, "SELECT COUNT(*) FROM agent_harness_sessions"))
        }
    }

    @Test
    fun `composite session identity allows the same Harness id in separate runtimes`() {
        openLegacy().use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO agent_conversations(id) VALUES (1)")
            db.execSQL("INSERT INTO agent_conversations(id) VALUES (2)")
            db.execSQL(
                "INSERT INTO agent_harness_workspaces " +
                    "(id, backend, projectFolder, connectionKey, title, guestPath, harnessWorkspaceId, prootEnvironmentId, createdAt, updatedAt) " +
                    "VALUES ('w1', 'LOCAL_PROOT', 'demo', '', 'Demo', '/workspace/projects/demo', 'group-1', NULL, 1, 1)"
            )
            HarnessInstallationsMigration.MIGRATION_126_127.migrate(db)
            db.execSQL(
                "INSERT INTO agent_harness_workspaces " +
                    "(id, backend, projectFolder, connectionKey, title, guestPath, harnessWorkspaceId, prootEnvironmentId, createdAt, updatedAt, runtimeId) " +
                    "VALUES ('w2', 'LOCAL_PROOT', 'demo', '', 'Demo 2', '/workspace/projects/demo', 'group-1', NULL, 1, 1, 'runtime-2')"
            )
            db.execSQL(
                "INSERT INTO agent_harness_sessions " +
                    "(harnessSessionId, conversationId, workspaceId, archived, lastSequence, createdAt, updatedAt, runtimeId) " +
                    "VALUES ('same-session', 1, 'w1', 0, -1, 1, 1, '${HarnessRuntimeIds.LEGACY}')"
            )
            db.execSQL(
                "INSERT INTO agent_harness_sessions " +
                    "(harnessSessionId, conversationId, workspaceId, archived, lastSequence, createdAt, updatedAt, runtimeId) " +
                    "VALUES ('same-session', 2, 'w2', 0, -1, 1, 1, 'runtime-2')"
            )
            assertEquals(2, scalarLong(db, "SELECT COUNT(*) FROM agent_harness_sessions WHERE harnessSessionId = 'same-session'"))
        }
    }

    private fun scalarString(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun scalarLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun indexExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name)
            ).use { it.moveToFirst() }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(name)
        ).use { it.moveToFirst() }

    private fun openLegacy(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE agent_conversations (id INTEGER PRIMARY KEY NOT NULL)")
                        db.execSQL(
                            "CREATE TABLE agent_proot_environments (" +
                                "id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, storageKey TEXT NOT NULL, " +
                                "imageId TEXT NOT NULL, imageVersion TEXT NOT NULL, imageDigest TEXT NOT NULL, " +
                                "sharingMode TEXT NOT NULL, status TEXT NOT NULL, sizeBytes INTEGER NOT NULL, " +
                                "lastUsedAt INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE agent_harness_workspaces (" +
                                "id TEXT NOT NULL PRIMARY KEY, backend TEXT NOT NULL, projectFolder TEXT NOT NULL, " +
                                "connectionKey TEXT NOT NULL, title TEXT NOT NULL, guestPath TEXT NOT NULL, " +
                                "harnessWorkspaceId TEXT, prootEnvironmentId TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                        )
                        db.execSQL("CREATE UNIQUE INDEX index_agent_harness_workspaces_backend_projectFolder_connectionKey " +
                            "ON agent_harness_workspaces(backend, projectFolder, connectionKey)")
                        db.execSQL("CREATE UNIQUE INDEX index_agent_harness_workspaces_harnessWorkspaceId " +
                            "ON agent_harness_workspaces(harnessWorkspaceId)")
                        db.execSQL(
                            "CREATE TABLE agent_harness_sessions (" +
                                "harnessSessionId TEXT NOT NULL PRIMARY KEY, conversationId INTEGER NOT NULL, " +
                                "workspaceId TEXT NOT NULL, archived INTEGER NOT NULL, lastSequence INTEGER NOT NULL, " +
                                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                                "FOREIGN KEY(conversationId) REFERENCES agent_conversations(id) ON DELETE CASCADE, " +
                                "FOREIGN KEY(workspaceId) REFERENCES agent_harness_workspaces(id) ON DELETE RESTRICT)"
                        )
                        db.execSQL("CREATE UNIQUE INDEX index_agent_harness_sessions_conversationId " +
                            "ON agent_harness_sessions(conversationId)")
                        db.execSQL("CREATE INDEX index_agent_harness_sessions_workspaceId " +
                            "ON agent_harness_sessions(workspaceId)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
}
