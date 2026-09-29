package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Introduces runtime ownership for Harness identity rows and the shared
 * installation catalog. Existing rows are assigned to the historical
 * DeepSeek Harness runtime; no filesystem paths are inspected or changed.
 */
object HarnessInstallationsMigration {
    private const val LEGACY_RUNTIME_ID = HarnessRuntimeIds.LEGACY
    private const val SHARED_ENVIRONMENT_ID = "deepseek-harness-shared"

    val MIGRATION_126_127: Migration = object : Migration(126, 127) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addConversationRuntimeColumn(db)
            addInstallationMetadataColumns(db)
            createTransferReceiptTable(db)
            migrateWorkspaceRuntimeOwnership(db)
            migrateSessionRuntimeOwnership(db)
            adoptSharedHarnessInstallation(db)
        }
    }

    private fun addConversationRuntimeColumn(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "agent_conversations")) return
        if (!columnExists(db, "agent_conversations", "runtimeId")) {
            db.execSQL(
                "ALTER TABLE `agent_conversations` " +
                    "ADD COLUMN `runtimeId` TEXT NOT NULL DEFAULT '$LEGACY_RUNTIME_ID'"
            )
        }
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_conversations_runtimeId` " +
                "ON `agent_conversations` (`runtimeId`)"
        )
    }

    private fun addInstallationMetadataColumns(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "agent_proot_environments")) return
        if (!columnExists(db, "agent_proot_environments", "purpose")) {
            // Existing rows belong to the original Debian/PRoot manager.
            db.execSQL(
                "ALTER TABLE `agent_proot_environments` " +
                    "ADD COLUMN `purpose` TEXT NOT NULL DEFAULT '${HarnessInstallationPurpose.LEGACY}'"
            )
        }
        if (!columnExists(db, "agent_proot_environments", "dataLayout")) {
            // Existing generic environments already use id-scoped Proot storage.
            db.execSQL(
                "ALTER TABLE `agent_proot_environments` " +
                "ADD COLUMN `dataLayout` TEXT NOT NULL DEFAULT '${HarnessInstallationDataLayout.SCOPED}'"
            )
        }
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_proot_environments_purpose` " +
                "ON `agent_proot_environments` (`purpose`)"
        )
    }

    private fun createTransferReceiptTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_harness_transfer_receipts` (
                `operationId` TEXT NOT NULL,
                `sourceRuntimeId` TEXT NOT NULL,
                `targetRuntimeId` TEXT NOT NULL,
                `mappingJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`operationId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_harness_transfer_receipts_sourceRuntimeId_targetRuntimeId` " +
                "ON `agent_harness_transfer_receipts` (`sourceRuntimeId`, `targetRuntimeId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_harness_transfer_receipts_createdAt` " +
                "ON `agent_harness_transfer_receipts` (`createdAt`)"
        )
    }

    private fun migrateWorkspaceRuntimeOwnership(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "agent_harness_workspaces")) return
        if (!columnExists(db, "agent_harness_workspaces", "runtimeId")) {
            db.execSQL(
                "ALTER TABLE `agent_harness_workspaces` " +
                    "ADD COLUMN `runtimeId` TEXT NOT NULL DEFAULT '$LEGACY_RUNTIME_ID'"
            )
        }

        // The old identity indexes were global. Replace them with runtime-aware
        // indexes so identical projects can exist in separate installations.
        db.execSQL(
            "DROP INDEX IF EXISTS `index_agent_harness_workspaces_backend_projectFolder_connectionKey`"
        )
        db.execSQL("DROP INDEX IF EXISTS `index_agent_harness_workspaces_harnessWorkspaceId`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_agent_harness_workspaces_runtimeId_backend_projectFolder_connectionKey` " +
                "ON `agent_harness_workspaces` (`runtimeId`, `backend`, `projectFolder`, `connectionKey`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_agent_harness_workspaces_runtimeId_harnessWorkspaceId` " +
                "ON `agent_harness_workspaces` (`runtimeId`, `harnessWorkspaceId`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_harness_workspaces_runtimeId_id` " +
                "ON `agent_harness_workspaces` (`runtimeId`, `id`)"
        )
    }

    private fun migrateSessionRuntimeOwnership(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "agent_harness_sessions")) return
        val needsRebuild = !columnExists(db, "agent_harness_sessions", "runtimeId") ||
            !hasCompositeSessionPrimaryKey(db)
        if (needsRebuild) {
            db.execSQL("DROP TABLE IF EXISTS `agent_harness_sessions_new`")
            db.execSQL(
                """
                CREATE TABLE `agent_harness_sessions_new` (
                    `harnessSessionId` TEXT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `workspaceId` TEXT NOT NULL,
                    `archived` INTEGER NOT NULL,
                    `lastSequence` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `runtimeId` TEXT NOT NULL,
                    PRIMARY KEY(`runtimeId`, `harnessSessionId`),
                    FOREIGN KEY(`conversationId`) REFERENCES `agent_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`runtimeId`, `workspaceId`) REFERENCES `agent_harness_workspaces`(`runtimeId`, `id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            val runtimeExpression = if (columnExists(db, "agent_harness_sessions", "runtimeId")) {
                "COALESCE(runtimeId, '$LEGACY_RUNTIME_ID')"
            } else {
                "'$LEGACY_RUNTIME_ID'"
            }
            db.execSQL(
                """
                INSERT INTO `agent_harness_sessions_new`
                    (`harnessSessionId`, `conversationId`, `workspaceId`, `archived`, `lastSequence`, `createdAt`, `updatedAt`, `runtimeId`)
                SELECT `harnessSessionId`, `conversationId`, `workspaceId`, `archived`, `lastSequence`, `createdAt`, `updatedAt`, $runtimeExpression
                FROM `agent_harness_sessions`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `agent_harness_sessions`")
            db.execSQL("ALTER TABLE `agent_harness_sessions_new` RENAME TO `agent_harness_sessions`")
        }

        db.execSQL("DROP INDEX IF EXISTS `index_agent_harness_sessions_conversationId`")
        db.execSQL("DROP INDEX IF EXISTS `index_agent_harness_sessions_workspaceId`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_harness_sessions_conversationId` " +
                "ON `agent_harness_sessions` (`conversationId`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_harness_sessions_runtimeId_conversationId` " +
                "ON `agent_harness_sessions` (`runtimeId`, `conversationId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_harness_sessions_runtimeId_workspaceId` " +
                "ON `agent_harness_sessions` (`runtimeId`, `workspaceId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_harness_sessions_runtimeId_updatedAt` " +
                "ON `agent_harness_sessions` (`runtimeId`, `updatedAt`)"
        )
    }

    private fun adoptSharedHarnessInstallation(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "agent_proot_environments")) return

        // Adoption is metadata-only. The existing rootfs, if any, is left in place.
        db.execSQL(
            "UPDATE `agent_proot_environments` SET " +
                "`purpose` = '${HarnessInstallationPurpose.HARNESS}', " +
                "`dataLayout` = '${HarnessInstallationDataLayout.LEGACY}' " +
                "WHERE `id` = '$SHARED_ENVIRONMENT_ID'"
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO `agent_proot_environments` (
                `id`, `displayName`, `storageKey`, `imageId`, `imageVersion`, `imageDigest`,
                `sharingMode`, `status`, `sizeBytes`, `lastUsedAt`, `createdAt`, `updatedAt`,
                `purpose`, `dataLayout`
            ) VALUES (
                '$SHARED_ENVIRONMENT_ID', 'DeepSeek Harness', '$SHARED_ENVIRONMENT_ID',
                'debian-trixie-arm64-20260824', '13.6 (Trixie)', '', 'SHARED', 'NOT_INSTALLED',
                0, NULL, CAST(strftime('%s', 'now') AS INTEGER) * 1000,
                CAST(strftime('%s', 'now') AS INTEGER) * 1000,
                '${HarnessInstallationPurpose.HARNESS}', '${HarnessInstallationDataLayout.LEGACY}'
            )
            """.trimIndent()
        )
    }

    private fun hasCompositeSessionPrimaryKey(db: SupportSQLiteDatabase): Boolean {
        val primaryKeys = linkedMapOf<String, Int>()
        db.query("PRAGMA table_info(`agent_harness_sessions`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                val primaryKeyOrder = cursor.getInt(primaryKeyIndex)
                if (primaryKeyOrder > 0) primaryKeys[cursor.getString(nameIndex)] = primaryKeyOrder
            }
        }
        return primaryKeys["runtimeId"] == 1 && primaryKeys["harnessSessionId"] == 2
    }

    private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean =
        Migrations.tableExists(db, tableName)

    private fun columnExists(db: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean =
        Migrations.columnExists(db, tableName, columnName)
}
