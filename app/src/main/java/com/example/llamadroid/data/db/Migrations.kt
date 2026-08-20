package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.llamadroid.data.RemoteBackendUrlSupport
import com.example.llamadroid.quadtrix.QuadtrixOptionKeys
import com.example.llamadroid.util.DebugLog

/**
 * Database migrations for AppDatabase.
 * 
 * IMPORTANT: When changing the database schema:
 * 1. Increment the version number in AppDatabase
 * 2. Add a new migration object here (e.g., MIGRATION_27_28)
 * 3. Add the migration to ALL_MIGRATIONS list
 * 4. Test the migration thoroughly before release
 * 
 * Never use fallbackToDestructiveMigration() in production as it causes data loss.
 */
object Migrations {
    
    /**
     * All migrations that should be applied.
     * Add new migrations to this list.
     */
    
    /**
     * Versions where destructive migration is allowed.
     * These are early development versions before production release.
     * Once app is released to users, remove versions from this list.
     */
    val DESTRUCTIVE_FALLBACK_VERSIONS: IntArray = intArrayOf(
        // Early development versions (1-26) can use destructive migration
        // since they were pre-release
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
        11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
        21, 22, 23, 24, 25, 26
    )
    
    // ========== EXAMPLE MIGRATION TEMPLATE ==========
    // Uncomment and modify when you need version 28:
    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 27 -> 28")
            
            // Add isSuspicious column to agent_messages table
            if (columnExists(db, "agent_messages", "isSuspicious")) {
                 DebugLog.log("[DB] Column isSuspicious already exists in agent_messages")
            } else {
                db.execSQL("ALTER TABLE agent_messages ADD COLUMN isSuspicious INTEGER NOT NULL DEFAULT 0")
            }
            
            DebugLog.log("[DB] Migration 27 -> 28 complete")
        }
    }

    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 28 -> 29")

            // Create ollama_servers table
            if (!tableExists(db, "ollama_servers")) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `ollama_servers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `lastConnected` INTEGER NOT NULL)")
            }

            DebugLog.log("[DB] Migration 28 -> 29 complete")
        }
    }
    
    // ========== HELPER FUNCTIONS ==========
    
    /**
     * All migrations that should be applied.
     * Add new migrations to this list.
     */
    val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 29 -> 30")

            // Create llama_servers table
            if (!tableExists(db, "llama_servers")) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `llama_servers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL)")
            }

            // Create llama_chats table
            if (!tableExists(db, "llama_chats")) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `llama_chats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `lastModified` INTEGER NOT NULL, `contextSize` INTEGER NOT NULL, `apiParams` TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_chats_lastModified` ON `llama_chats` (`lastModified`)")
            }

            // Create llama_messages table
            if (!tableExists(db, "llama_messages")) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `llama_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chatId` INTEGER NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isError` INTEGER NOT NULL, FOREIGN KEY(`chatId`) REFERENCES `llama_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_messages_chatId` ON `llama_messages` (`chatId`)")
            }

            DebugLog.log("[DB] Migration 29 -> 30 complete")
        }
    }

    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 30 -> 31")

            // Add systemPrompt to llama_chats
            if (!columnExists(db, "llama_chats", "systemPrompt")) {
                db.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `systemPrompt` TEXT DEFAULT NULL")
            }

            // Add supportsVision to llama_servers
            if (!columnExists(db, "llama_servers", "supportsVision")) {
                db.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `supportsVision` INTEGER NOT NULL DEFAULT 0")
            }

            // Add modelName to llama_servers
            if (!columnExists(db, "llama_servers", "modelName")) {
                db.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `modelName` TEXT DEFAULT NULL")
            }

            DebugLog.log("[DB] Migration 30 -> 31 complete")
        }
    }

    val MIGRATION_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 31 -> 32")

            // Add isTruncated to llama_messages
            if (!columnExists(db, "llama_messages", "isTruncated")) {
                db.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `isTruncated` INTEGER NOT NULL DEFAULT 0")
            }
            
            // Add thinking to llama_messages
            if (!columnExists(db, "llama_messages", "thinking")) {
                db.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `thinking` TEXT DEFAULT NULL")
            }

            DebugLog.log("[DB] Migration 31 -> 32 complete")
        }
    }

    val MIGRATION_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 32 -> 33")

            if (!columnExists(db, "llama_messages", "promptTokens")) {
                db.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `promptTokens` INTEGER NOT NULL DEFAULT 0")
            }
            if (!columnExists(db, "llama_messages", "completionTokens")) {
                db.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `completionTokens` INTEGER NOT NULL DEFAULT 0")
            }
            if (!columnExists(db, "llama_messages", "tps")) {
                db.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `tps` REAL NOT NULL DEFAULT 0.0")
            }

            DebugLog.log("[DB] Migration 32 -> 33 complete")
        }
    }

    val MIGRATION_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 33 -> 34")

            if (!columnExists(db, "llama_messages", "generationTimeMs")) {
                db.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `generationTimeMs` INTEGER NOT NULL DEFAULT 0")
            }

            DebugLog.log("[DB] Migration 33 -> 34 complete")
        }
    }

    val MIGRATION_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 34 -> 35")

            // Add sequenceNumber column for stable message ordering
            if (!columnExists(db, "agent_messages", "sequenceNumber")) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `sequenceNumber` INTEGER NOT NULL DEFAULT 0")
                // Backfill existing messages with their auto-increment id as sequence
                db.execSQL("UPDATE `agent_messages` SET `sequenceNumber` = `id`")
            }

            DebugLog.log("[DB] Migration 34 -> 35 complete")
        }
    }

    val MIGRATION_35_36 = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 35 -> 36")

            // Create saved_commands table
            if (!tableExists(db, "saved_commands")) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `saved_commands` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `command` TEXT NOT NULL)")
            }

            DebugLog.log("[DB] Migration 35 -> 36 complete")
        }
    }

    val MIGRATION_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 36 -> 37")

            // Expand saved_commands table with all master settings fields
            if (!columnExists(db, "saved_commands", "modelPath")) {
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `modelPath` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `contextSize` INTEGER NOT NULL DEFAULT 4096")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `batchSize` INTEGER NOT NULL DEFAULT 512")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `temperature` REAL NOT NULL DEFAULT 0.7")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `threads` INTEGER NOT NULL DEFAULT 4")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `host` TEXT NOT NULL DEFAULT '127.0.0.1'")
                // Speculative decoding
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `speculativeEnabled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `draftModelPath` TEXT")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `draftMax` INTEGER NOT NULL DEFAULT 16")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `draftMin` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `draftPMin` REAL NOT NULL DEFAULT 0.75")
                // Advanced
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `parallel` INTEGER")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `cacheRam` INTEGER")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `customFlags` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `flashAttention` INTEGER NOT NULL DEFAULT 0")
                // KV Cache
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `kvCacheEnabled` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `kvCacheTypeK` TEXT NOT NULL DEFAULT 'f16'")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `kvCacheTypeV` TEXT NOT NULL DEFAULT 'f16'")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `kvCacheReuse` INTEGER NOT NULL DEFAULT 0")
                // Master RAM & Workers
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `masterRamMB` INTEGER NOT NULL DEFAULT 4096")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `workersListStr` TEXT NOT NULL DEFAULT ''")
                // Legacy settings
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `lowMemoryMode` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `enableVision` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `mmprojPath` TEXT")
            }

            DebugLog.log("[DB] Migration 36 -> 37 complete")
        }
    }

    val MIGRATION_37_38 = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 37 -> 38")

            if (!columnExists(db, "saved_commands", "scope")) {
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `scope` TEXT NOT NULL DEFAULT 'GENERAL'")
            }

            // Existing presets with an assigned worker list came from Master mode.
            db.execSQL(
                """
                UPDATE `saved_commands`
                SET `scope` = CASE
                    WHEN TRIM(`workersListStr`) != '' THEN 'MASTER'
                    ELSE 'GENERAL'
                END
                """.trimIndent()
            )

            DebugLog.log("[DB] Migration 37 -> 38 complete")
        }
    }

    val MIGRATION_38_39 = object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 38 -> 39")

            if (!tableExists(db, "ai_runtime_jobs")) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_runtime_jobs` (
                        `jobId` TEXT NOT NULL,
                        `jobKey` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `conversationId` INTEGER,
                        `sessionId` TEXT,
                        `projectFolder` TEXT,
                        `backendIdentifier` TEXT,
                        `modelName` TEXT,
                        `payloadJson` TEXT NOT NULL,
                        `checkpointJson` TEXT,
                        `progressText` TEXT,
                        `errorMessage` TEXT,
                        `resumable` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`jobId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_runtime_jobs_jobKey` ON `ai_runtime_jobs` (`jobKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_runtime_jobs_status` ON `ai_runtime_jobs` (`status`)")
            }

            DebugLog.log("[DB] Migration 38 -> 39 complete")
        }
    }

    val MIGRATION_39_40 = object : Migration(39, 40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 39 -> 40")

            if (!columnExists(db, "agent_messages", "toolCallId")) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `toolCallId` TEXT")
            }
            if (!columnExists(db, "agent_messages", "terminalOutput")) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `terminalOutput` TEXT")
            }
            if (!columnExists(db, "agent_messages", "isTerminalVisible")) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `isTerminalVisible` INTEGER NOT NULL DEFAULT 0")
            }
            if (!columnExists(db, "agent_messages", "planModifiedContent")) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `planModifiedContent` TEXT")
            }
            if (!columnExists(db, "agent_messages", "isDelegation")) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `isDelegation` INTEGER NOT NULL DEFAULT 0")
            }
            if (!columnExists(db, "agent_messages", "customAgentName")) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `customAgentName` TEXT")
            }
            if (!columnExists(db, "agent_messages", "pendingToolCall")) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `pendingToolCall` TEXT")
            }
            if (!columnExists(db, "agent_messages", "isOutputExpanded")) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `isOutputExpanded` INTEGER NOT NULL DEFAULT 0")
            }

            DebugLog.log("[DB] Migration 39 -> 40 complete")
        }
    }

    val MIGRATION_40_41 = object : Migration(40, 41) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 40 -> 41")

            if (!columnExists(db, "models", "sdFamily")) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `sdFamily` TEXT")
            }
            if (!columnExists(db, "models", "sdVariant")) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `sdVariant` TEXT")
            }
            if (!columnExists(db, "models", "sdCompatProfiles")) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `sdCompatProfiles` TEXT")
            }

            DebugLog.log("[DB] Migration 40 -> 41 complete")
        }
    }

    val MIGRATION_41_42 = object : Migration(41, 42) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 41 -> 42")

            if (!columnExists(db, "models", "onnxCapabilities")) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `onnxCapabilities` TEXT")
            }

            DebugLog.log("[DB] Migration 41 -> 42 complete")
        }
    }

    val MIGRATION_42_43 = object : Migration(42, 43) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 42 -> 43")

            if (!columnExists(db, "models", "onnxAssetKind")) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `onnxAssetKind` TEXT")
            }
            if (!columnExists(db, "models", "onnxPipelineFamily")) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `onnxPipelineFamily` TEXT")
            }
            if (!columnExists(db, "models", "onnxReferenceUri")) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `onnxReferenceUri` TEXT")
            }
            if (!columnExists(db, "models", "onnxReferencePath")) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `onnxReferencePath` TEXT")
            }

            DebugLog.log("[DB] Migration 42 -> 43 complete")
        }
    }

    val MIGRATION_43_44 = object : Migration(43, 44) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 43 -> 44")

            if (!columnExists(db, "llama_servers", "supportsAudio")) {
                db.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `supportsAudio` INTEGER NOT NULL DEFAULT 0")
            }

            DebugLog.log("[DB] Migration 43 -> 44 complete")
        }
    }

    val MIGRATION_44_45 = object : Migration(44, 45) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 44 -> 45")

            if (!columnExists(db, "llama_messages", "imagePath")) {
                db.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `imagePath` TEXT")
            }
            if (!columnExists(db, "llama_messages", "audioPath")) {
                db.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `audioPath` TEXT")
            }

            DebugLog.log("[DB] Migration 44 -> 45 complete")
        }
    }

    val MIGRATION_45_46 = object : Migration(45, 46) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 45 -> 46")

            if (!columnExists(db, "agent_messages", "imagePath")) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `imagePath` TEXT")
            }
            if (!columnExists(db, "custom_agents", "visionEnabled")) {
                db.execSQL("ALTER TABLE `custom_agents` ADD COLUMN `visionEnabled` INTEGER NOT NULL DEFAULT 0")
            }

            DebugLog.log("[DB] Migration 45 -> 46 complete")
        }
    }

    val MIGRATION_46_47 = object : Migration(46, 47) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 46 -> 47")

            if (!columnExists(db, "dataset_projects", "backend")) {
                db.execSQL("ALTER TABLE `dataset_projects` ADD COLUMN `backend` TEXT NOT NULL DEFAULT 'llama-server'")
            }
            if (!columnExists(db, "dataset_projects", "ollamaUrl")) {
                db.execSQL("ALTER TABLE `dataset_projects` ADD COLUMN `ollamaUrl` TEXT NOT NULL DEFAULT 'http://localhost:11434'")
            }
            if (!columnExists(db, "dataset_projects", "ollamaModel")) {
                db.execSQL("ALTER TABLE `dataset_projects` ADD COLUMN `ollamaModel` TEXT")
            }
            if (!columnExists(db, "dataset_projects", "ollamaNumCtx")) {
                db.execSQL("ALTER TABLE `dataset_projects` ADD COLUMN `ollamaNumCtx` INTEGER NOT NULL DEFAULT 4096")
            }
            if (!columnExists(db, "dataset_projects", "ollamaThreads")) {
                db.execSQL("ALTER TABLE `dataset_projects` ADD COLUMN `ollamaThreads` INTEGER NOT NULL DEFAULT 4")
            }
            if (!columnExists(db, "dataset_projects", "ollamaMmap")) {
                db.execSQL("ALTER TABLE `dataset_projects` ADD COLUMN `ollamaMmap` INTEGER NOT NULL DEFAULT 0")
            }

            DebugLog.log("[DB] Migration 46 -> 47 complete")
        }
    }

    val MIGRATION_47_48 = object : Migration(47, 48) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 47 -> 48")

            if (!columnExists(db, "llama_servers", "engine")) {
                db.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `engine` TEXT NOT NULL DEFAULT 'llama-server'")
            }
            if (!columnExists(db, "llama_servers", "whisperModelPath")) {
                db.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `whisperModelPath` TEXT")
            }
            if (!columnExists(db, "llama_servers", "whisperLanguage")) {
                db.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `whisperLanguage` TEXT NOT NULL DEFAULT 'auto'")
            }

            DebugLog.log("[DB] Migration 47 -> 48 complete")
        }
    }

    val MIGRATION_48_49 = object : Migration(48, 49) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 48 -> 49")

            DebugLog.log("[DB] Migration 48 -> 49 complete")
        }
    }

    val MIGRATION_49_50 = object : Migration(49, 50) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 49 -> 50")

            if (!columnExists(db, "llama_servers", "defaultApiParams")) {
                db.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `defaultApiParams` TEXT")
            }

            DebugLog.log("[DB] Migration 49 -> 50 complete")
        }
    }

    val MIGRATION_50_51 = object : Migration(50, 51) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 50 -> 51")

            if (!columnExists(db, "notes", "isLlmWhitelisted")) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `isLlmWhitelisted` INTEGER NOT NULL DEFAULT 0")
            }

            if (!tableExists(db, "llama_chat_folders")) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `llama_chat_folders` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_llama_chat_folders_name` ON `llama_chat_folders` (`name`)")

            if (!columnExists(db, "llama_chats", "folderId")) {
                db.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `folderId` INTEGER DEFAULT NULL")
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_chats_folderId` ON `llama_chats` (`folderId`)")

            DebugLog.log("[DB] Migration 50 -> 51 complete")
        }
    }

    val MIGRATION_51_52 = object : Migration(51, 52) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 51 -> 52")

            if (!columnExists(db, "dataset_projects", "finalLanguage")) {
                db.execSQL("ALTER TABLE `dataset_projects` ADD COLUMN `finalLanguage` TEXT NOT NULL DEFAULT ''")
            }

            DebugLog.log("[DB] Migration 51 -> 52 complete")
        }
    }

    val MIGRATION_52_53 = object : Migration(52, 53) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 52 -> 53")

            if (!tableExists(db, "llama_chat_prompt_profiles")) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `llama_chat_prompt_profiles` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_llama_chat_prompt_profiles_name` ON `llama_chat_prompt_profiles` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_chat_prompt_profiles_updatedAt` ON `llama_chat_prompt_profiles` (`updatedAt`)")

            DebugLog.log("[DB] Migration 52 -> 53 complete")
        }
    }

    val MIGRATION_53_54 = object : Migration(53, 54) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 53 -> 54")

            if (tableExists(db, "ai_runtime_jobs")) {
                db.execSQL("DELETE FROM `ai_runtime_jobs` WHERE `type` = 'TRAINER_RUN'")
            }
            db.execSQL("DROP TABLE IF EXISTS `trainer_artifacts`")
            db.execSQL("DROP TABLE IF EXISTS `trainer_checkpoints`")
            db.execSQL("DROP TABLE IF EXISTS `trainer_runs`")
            db.execSQL("DROP TABLE IF EXISTS `trainer_schedules`")
            db.execSQL("DROP TABLE IF EXISTS `trainer_profiles`")

            DebugLog.log("[DB] Migration 53 -> 54 complete")
        }
    }

    val MIGRATION_54_55 = object : Migration(54, 55) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 54 -> 55")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `organizer_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `location` TEXT NOT NULL,
                    `startAtMillis` INTEGER NOT NULL,
                    `endAtMillis` INTEGER,
                    `allDay` INTEGER NOT NULL,
                    `timezoneId` TEXT NOT NULL,
                    `colorArgb` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_organizer_events_startAtMillis` ON `organizer_events` (`startAtMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_organizer_events_updatedAt` ON `organizer_events` (`updatedAt`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `organizer_alarms` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `eventId` INTEGER,
                    `title` TEXT NOT NULL,
                    `message` TEXT NOT NULL,
                    `triggerAtMillis` INTEGER NOT NULL,
                    `timezoneId` TEXT NOT NULL,
                    `soundEnabled` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `deliveredAt` INTEGER,
                    FOREIGN KEY(`eventId`) REFERENCES `organizer_events`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_organizer_alarms_eventId` ON `organizer_alarms` (`eventId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_organizer_alarms_triggerAtMillis` ON `organizer_alarms` (`triggerAtMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_organizer_alarms_enabled` ON `organizer_alarms` (`enabled`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `organizer_llm_settings` (
                    `id` INTEGER NOT NULL,
                    `calendarToolsAllowed` INTEGER NOT NULL,
                    `alarmToolsAllowed` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )

            DebugLog.log("[DB] Migration 54 -> 55 complete")
        }
    }

    val MIGRATION_55_56 = object : Migration(55, 56) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 55 -> 56")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `llama_scheduled_tasks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `serverId` INTEGER,
                    `contextSize` INTEGER NOT NULL,
                    `systemPrompt` TEXT,
                    `taskPrompt` TEXT NOT NULL,
                    `apiParams` TEXT,
                    `scheduleType` TEXT NOT NULL,
                    `oneTimeAtMillis` INTEGER,
                    `timeOfDayMinutes` INTEGER NOT NULL,
                    `weekdaysMask` INTEGER NOT NULL,
                    `dayOfMonth` INTEGER NOT NULL,
                    `timezoneId` TEXT NOT NULL,
                    `nextRunAtMillis` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `lastRunAtMillis` INTEGER,
                    FOREIGN KEY(`serverId`) REFERENCES `llama_servers`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_scheduled_tasks_enabled` ON `llama_scheduled_tasks` (`enabled`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_scheduled_tasks_nextRunAtMillis` ON `llama_scheduled_tasks` (`nextRunAtMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_scheduled_tasks_serverId` ON `llama_scheduled_tasks` (`serverId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_scheduled_tasks_updatedAt` ON `llama_scheduled_tasks` (`updatedAt`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `llama_scheduled_task_logs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `taskId` INTEGER,
                    `taskName` TEXT NOT NULL,
                    `scheduledAtMillis` INTEGER NOT NULL,
                    `startedAtMillis` INTEGER,
                    `finishedAtMillis` INTEGER,
                    `durationMs` INTEGER,
                    `status` TEXT NOT NULL,
                    `serverId` INTEGER,
                    `serverName` TEXT,
                    `serverBaseUrl` TEXT,
                    `finalOutput` TEXT NOT NULL,
                    `error` TEXT,
                    `toolActivity` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`taskId`) REFERENCES `llama_scheduled_tasks`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_scheduled_task_logs_taskId` ON `llama_scheduled_task_logs` (`taskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_scheduled_task_logs_scheduledAtMillis` ON `llama_scheduled_task_logs` (`scheduledAtMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_scheduled_task_logs_status` ON `llama_scheduled_task_logs` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_scheduled_task_logs_createdAt` ON `llama_scheduled_task_logs` (`createdAt`)")

            DebugLog.log("[DB] Migration 55 -> 56 complete")
        }
    }

    val MIGRATION_56_57 = object : Migration(56, 57) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 56 -> 57")

            if (!columnExists(db, "benchmark_results", "runStartedAt")) {
                db.execSQL("ALTER TABLE `benchmark_results` ADD COLUMN `runStartedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE `benchmark_results`
                    SET `runStartedAt` = (
                        SELECT MIN(`legacy`.`timestamp`)
                        FROM `benchmark_results` AS `legacy`
                        WHERE `legacy`.`modelPath` = `benchmark_results`.`modelPath`
                    )
                    WHERE `runStartedAt` = 0
                    """.trimIndent()
                )
            }

            if (!columnExists(db, "benchmark_results", "runName")) {
                db.execSQL("ALTER TABLE `benchmark_results` ADD COLUMN `runName` TEXT NOT NULL DEFAULT ''")
            }

            DebugLog.log("[DB] Migration 56 -> 57 complete")
        }
    }

    val MIGRATION_57_58 = object : Migration(57, 58) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 57 -> 58")

            db.execSQL("UPDATE `dataset_sources` SET `extractedText` = NULL WHERE `extractedText` IS NOT NULL")

            DebugLog.log("[DB] Migration 57 -> 58 complete")
        }
    }

    val MIGRATION_58_59 = object : Migration(58, 59) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 58 -> 59")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `quadtrix_profiles` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `datasetPath` TEXT NOT NULL DEFAULT '',
                    `modelFilename` TEXT NOT NULL DEFAULT 'web_model.bin',
                    `modelPath` TEXT NOT NULL DEFAULT '',
                    `batchSize` INTEGER NOT NULL DEFAULT 1,
                    `gradAccumSteps` INTEGER NOT NULL DEFAULT 20,
                    `blockSize` INTEGER NOT NULL DEFAULT 256,
                    `maxIters` INTEGER NOT NULL DEFAULT 5000,
                    `evalInterval` INTEGER NOT NULL DEFAULT 250,
                    `evalIters` INTEGER NOT NULL DEFAULT 20,
                    `logInterval` INTEGER NOT NULL DEFAULT 1,
                    `threads` INTEGER NOT NULL DEFAULT 4,
                    `learningRate` TEXT NOT NULL DEFAULT '0.0002',
                    `gradClip` TEXT NOT NULL DEFAULT '1.0',
                    `optimizer` TEXT NOT NULL DEFAULT 'adamw8',
                    `mathBackend` TEXT NOT NULL DEFAULT 'auto',
                    `dropout` TEXT NOT NULL DEFAULT '0.1',
                    `trainSplit` TEXT NOT NULL DEFAULT '0.9',
                    `nEmbd` INTEGER NOT NULL DEFAULT 256,
                    `nHead` INTEGER NOT NULL DEFAULT 4,
                    `nLayer` INTEGER NOT NULL DEFAULT 8,
                    `seed` INTEGER NOT NULL DEFAULT 1337,
                    `checkpointEvery` INTEGER NOT NULL DEFAULT 500,
                    `weightStorage` TEXT NOT NULL DEFAULT 'int8',
                    `activationQuantBits` INTEGER NOT NULL DEFAULT 8,
                    `optimizerStateBits` INTEGER NOT NULL DEFAULT 8,
                    `strictQuantizedWeights` INTEGER NOT NULL DEFAULT 0,
                    `skipInitialEval` INTEGER NOT NULL DEFAULT 0,
                    `resume` INTEGER NOT NULL DEFAULT 0,
                    `resumePath` TEXT NOT NULL DEFAULT '',
                    `parquetTextColumn` TEXT NOT NULL DEFAULT '',
                    `parquetInstructionColumn` TEXT NOT NULL DEFAULT 'instruction',
                    `parquetInputColumn` TEXT NOT NULL DEFAULT 'input',
                    `parquetOutputColumn` TEXT NOT NULL DEFAULT 'output',
                    `distMode` TEXT NOT NULL DEFAULT 'none',
                    `distRole` TEXT NOT NULL DEFAULT 'coordinator',
                    `workerHost` TEXT NOT NULL DEFAULT '0.0.0.0',
                    `workerPort` INTEGER NOT NULL DEFAULT 9091,
                    `workerToken` TEXT NOT NULL DEFAULT '',
                    `distWorkers` TEXT NOT NULL DEFAULT '',
                    `distSyncInterval` INTEGER NOT NULL DEFAULT 1,
                    `distGradientBits` INTEGER NOT NULL DEFAULT 32,
                    `distShards` TEXT NOT NULL DEFAULT 'auto',
                    `distRpcTimeoutSec` INTEGER NOT NULL DEFAULT 900,
                    `distReprobeInterval` INTEGER NOT NULL DEFAULT 5,
                    `distCoordinatorCompute` INTEGER NOT NULL DEFAULT 1,
                    `webHost` TEXT NOT NULL DEFAULT '127.0.0.1',
                    `webPort` INTEGER NOT NULL DEFAULT 8080,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_quadtrix_profiles_name` ON `quadtrix_profiles` (`name`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `quadtrix_runs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `profileId` INTEGER,
                    `profileName` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `processMode` TEXT NOT NULL,
                    `pid` INTEGER,
                    `startedAt` INTEGER NOT NULL,
                    `finishedAt` INTEGER,
                    `latestEtaSeconds` INTEGER,
                    `latestIter` INTEGER NOT NULL DEFAULT 0,
                    `maxIter` INTEGER NOT NULL DEFAULT 0,
                    `latestBatchLoss` REAL,
                    `latestTrainLoss` REAL,
                    `latestValLoss` REAL,
                    `latestGradNorm` REAL,
                    `logFilePath` TEXT NOT NULL DEFAULT '',
                    `modelOutputDir` TEXT NOT NULL DEFAULT '',
                    `errorMessage` TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quadtrix_runs_profileId` ON `quadtrix_runs` (`profileId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quadtrix_runs_status` ON `quadtrix_runs` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quadtrix_runs_startedAt` ON `quadtrix_runs` (`startedAt`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `quadtrix_metrics` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `runId` INTEGER,
                    `profileName` TEXT NOT NULL,
                    `iter` INTEGER NOT NULL,
                    `maxIter` INTEGER NOT NULL DEFAULT 0,
                    `batchLoss` REAL,
                    `trainLoss` REAL,
                    `valLoss` REAL,
                    `gradNorm` REAL,
                    `elapsedSeconds` INTEGER,
                    `etaSeconds` INTEGER,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quadtrix_metrics_runId` ON `quadtrix_metrics` (`runId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quadtrix_metrics_profileName` ON `quadtrix_metrics` (`profileName`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quadtrix_metrics_iter` ON `quadtrix_metrics` (`iter`)")

            DebugLog.log("[DB] Migration 58 -> 59 complete")
        }
    }

    val MIGRATION_59_60 = object : Migration(59, 60) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 59 -> 60")

            fun addColumn(name: String, sqlType: String, defaultSql: String) {
                if (!columnExists(db, "quadtrix_profiles", name)) {
                    db.execSQL("ALTER TABLE `quadtrix_profiles` ADD COLUMN `$name` $sqlType NOT NULL DEFAULT $defaultSql")
                }
            }

            addColumn("arch", "TEXT", "'qwen3'")
            addColumn("tokenizer", "TEXT", "'qwen3'")
            addColumn("qwenTokenizerJsonPath", "TEXT", "''")
            addColumn("nKvHead", "INTEGER", "0")
            addColumn("headDim", "INTEGER", "0")
            addColumn("intermediateSize", "INTEGER", "0")
            addColumn("ropeTheta", "TEXT", "'1000000.0'")
            addColumn("rmsNormEps", "TEXT", "'0.000001'")
            addColumn("tieWordEmbeddings", "INTEGER", "1")
            addColumn("exportGgufPath", "TEXT", "''")
            addColumn("saveGgufAfterTrain", "INTEGER", "0")
            addColumn("ggufOuttype", "TEXT", "'f16'")
            addColumn("ggufName", "TEXT", "''")
            addColumn("showGgufInModels", "INTEGER", "1")
            addColumn("streamProgress", "INTEGER", "0")
            addColumn("streamPort", "INTEGER", "9999")

            DebugLog.log("[DB] Migration 59 -> 60 complete")
        }
    }

    val MIGRATION_60_61 = object : Migration(60, 61) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 60 -> 61")

            fun addColumn(name: String, sqlType: String, defaultSql: String) {
                if (!columnExists(db, "quadtrix_profiles", name)) {
                    db.execSQL("ALTER TABLE `quadtrix_profiles` ADD COLUMN `$name` $sqlType NOT NULL DEFAULT $defaultSql")
                }
            }

            addColumn("streamHost", "TEXT", "'127.0.0.1'")
            addColumn("streamLanEnabled", "INTEGER", "0")
            addColumn("remoteStreamHost", "TEXT", "''")
            addColumn("remoteStreamPort", "INTEGER", "9999")
            addColumn("remoteStreamToken", "TEXT", "''")
            addColumn("tokenCacheMode", "TEXT", "'auto'")
            addColumn("tokenCacheDir", "TEXT", "''")
            addColumn("tokenizationMode", "TEXT", "'records'")
            addColumn("tokenizeLogIntervalSec", "INTEGER", "5")

            DebugLog.log("[DB] Migration 60 -> 61 complete")
        }
    }

    val MIGRATION_61_62 = object : Migration(61, 62) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 61 -> 62")

            fun addColumn(name: String, sqlType: String, defaultSql: String) {
                if (!columnExists(db, "quadtrix_profiles", name)) {
                    db.execSQL("ALTER TABLE `quadtrix_profiles` ADD COLUMN `$name` $sqlType NOT NULL DEFAULT $defaultSql")
                }
            }

            addColumn("distCoordinatorOnly", "INTEGER", "0")
            addColumn("printSystemInfo", "INTEGER", "0")
            addColumn("noGenerateAfterTrain", "INTEGER", "1")
            addColumn("enabledOptions", "TEXT", "'${QuadtrixOptionKeys.defaultCsv}'")

            DebugLog.log("[DB] Migration 61 -> 62 complete")
        }
    }

    val MIGRATION_62_63 = object : Migration(62, 63) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 62 -> 63")

            if (!tableExists(db, "knowledge_bases")) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `knowledge_bases` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_bases_name` ON `knowledge_bases` (`name`)")
            }

            if (!tableExists(db, "knowledge_sources")) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `knowledge_sources` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`knowledgeBaseId` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`sourceRef` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`contentHash` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`errorMessage` TEXT, " +
                        "`embeddingModelPath` TEXT, " +
                        "`embeddingDim` INTEGER NOT NULL, " +
                        "`chunkCount` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`indexedAt` INTEGER, " +
                        "FOREIGN KEY(`knowledgeBaseId`) REFERENCES `knowledge_bases`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_sources_knowledgeBaseId` ON `knowledge_sources` (`knowledgeBaseId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_sources_status` ON `knowledge_sources` (`status`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_sources_knowledgeBaseId_sourceRef` ON `knowledge_sources` (`knowledgeBaseId`, `sourceRef`)")
            }

            if (!tableExists(db, "knowledge_chunks")) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `knowledge_chunks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`knowledgeBaseId` INTEGER NOT NULL, " +
                        "`sourceId` INTEGER NOT NULL, " +
                        "`chunkIndex` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`startOffset` INTEGER NOT NULL, " +
                        "`endOffset` INTEGER NOT NULL, " +
                        "`embedding` BLOB, " +
                        "`embeddingNorm` REAL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`knowledgeBaseId`) REFERENCES `knowledge_bases`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `knowledge_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_chunks_knowledgeBaseId` ON `knowledge_chunks` (`knowledgeBaseId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_chunks_sourceId` ON `knowledge_chunks` (`sourceId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_chunks_sourceId_chunkIndex` ON `knowledge_chunks` (`sourceId`, `chunkIndex`)")
            }

            if (!columnExists(db, "agent_conversations", "knowledgeBaseIds")) {
                db.execSQL("ALTER TABLE `agent_conversations` ADD COLUMN `knowledgeBaseIds` TEXT NOT NULL DEFAULT ''")
            }

            DebugLog.log("[DB] Migration 62 -> 63 complete")
        }
    }

    val MIGRATION_63_64 = object : Migration(63, 64) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 63 -> 64")

            db.execSQL("ALTER TABLE `knowledge_sources` RENAME TO `knowledge_sources_old`")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_sources` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `knowledgeBaseId` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `sourceRef` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `contentHash` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `errorMessage` TEXT,
                    `embeddingModelPath` TEXT,
                    `embeddingBackend` TEXT NOT NULL,
                    `embeddingConfigHash` TEXT NOT NULL,
                    `embeddingDim` INTEGER NOT NULL,
                    `chunkCount` INTEGER NOT NULL,
                    `embeddedChunkCount` INTEGER NOT NULL,
                    `processingStage` TEXT NOT NULL,
                    `progressTotal` INTEGER NOT NULL,
                    `progressDone` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `indexedAt` INTEGER,
                    `progressUpdatedAt` INTEGER,
                    FOREIGN KEY(`knowledgeBaseId`) REFERENCES `knowledge_bases`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `knowledge_sources` (
                    `id`,
                    `knowledgeBaseId`,
                    `type`,
                    `sourceRef`,
                    `title`,
                    `contentHash`,
                    `enabled`,
                    `status`,
                    `errorMessage`,
                    `embeddingModelPath`,
                    `embeddingBackend`,
                    `embeddingConfigHash`,
                    `embeddingDim`,
                    `chunkCount`,
                    `embeddedChunkCount`,
                    `processingStage`,
                    `progressTotal`,
                    `progressDone`,
                    `createdAt`,
                    `updatedAt`,
                    `indexedAt`,
                    `progressUpdatedAt`
                )
                SELECT
                    `id`,
                    `knowledgeBaseId`,
                    `type`,
                    `sourceRef`,
                    `title`,
                    `contentHash`,
                    `enabled`,
                    `status`,
                    `errorMessage`,
                    `embeddingModelPath`,
                    '',
                    '',
                    `embeddingDim`,
                    `chunkCount`,
                    (
                        SELECT COUNT(*)
                        FROM `knowledge_chunks`
                        WHERE `knowledge_chunks`.`sourceId` = `knowledge_sources_old`.`id`
                        AND `knowledge_chunks`.`embedding` IS NOT NULL
                    ),
                    `status`,
                    `chunkCount`,
                    (
                        SELECT COUNT(*)
                        FROM `knowledge_chunks`
                        WHERE `knowledge_chunks`.`sourceId` = `knowledge_sources_old`.`id`
                        AND `knowledge_chunks`.`embedding` IS NOT NULL
                    ),
                    `createdAt`,
                    `updatedAt`,
                    `indexedAt`,
                    NULL
                FROM `knowledge_sources_old`
                """.trimIndent()
            )
            db.execSQL("ALTER TABLE `knowledge_chunks` RENAME TO `knowledge_chunks_old`")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `knowledge_chunks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `knowledgeBaseId` INTEGER NOT NULL,
                    `sourceId` INTEGER NOT NULL,
                    `chunkIndex` INTEGER NOT NULL,
                    `text` TEXT NOT NULL,
                    `startOffset` INTEGER NOT NULL,
                    `endOffset` INTEGER NOT NULL,
                    `embedding` BLOB,
                    `embeddingNorm` REAL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`knowledgeBaseId`) REFERENCES `knowledge_bases`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`sourceId`) REFERENCES `knowledge_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `knowledge_chunks` (
                    `id`,
                    `knowledgeBaseId`,
                    `sourceId`,
                    `chunkIndex`,
                    `text`,
                    `startOffset`,
                    `endOffset`,
                    `embedding`,
                    `embeddingNorm`,
                    `createdAt`
                )
                SELECT
                    `id`,
                    `knowledgeBaseId`,
                    `sourceId`,
                    `chunkIndex`,
                    `text`,
                    `startOffset`,
                    `endOffset`,
                    `embedding`,
                    `embeddingNorm`,
                    `createdAt`
                FROM `knowledge_chunks_old`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `knowledge_chunks_old`")
            db.execSQL("DROP TABLE `knowledge_sources_old`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_sources_knowledgeBaseId` ON `knowledge_sources` (`knowledgeBaseId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_sources_status` ON `knowledge_sources` (`status`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_sources_knowledgeBaseId_sourceRef` ON `knowledge_sources` (`knowledgeBaseId`, `sourceRef`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_chunks_knowledgeBaseId` ON `knowledge_chunks` (`knowledgeBaseId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_chunks_sourceId` ON `knowledge_chunks` (`sourceId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_chunks_sourceId_chunkIndex` ON `knowledge_chunks` (`sourceId`, `chunkIndex`)")

            DebugLog.log("[DB] Migration 63 -> 64 complete")
        }
    }

    val MIGRATION_64_65 = object : Migration(64, 65) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 64 -> 65")

            if (!tableExists(db, "litert_models")) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `litert_models` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `path` TEXT NOT NULL,
                        `sourceUri` TEXT,
                        `repoId` TEXT,
                        `filename` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `backendPreference` TEXT NOT NULL,
                        `supportsCpu` INTEGER NOT NULL,
                        `supportsGpu` INTEGER NOT NULL,
                        `supportsNpu` INTEGER NOT NULL,
                        `supportsVision` INTEGER NOT NULL DEFAULT 0,
                        `supportsAudio` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_litert_models_repoId` ON `litert_models` (`repoId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_litert_models_updatedAt` ON `litert_models` (`updatedAt`)")
            }

            if (!columnExists(db, "llama_servers", "liteRtModelId")) {
                db.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `liteRtModelId` INTEGER")
            }
            if (!columnExists(db, "llama_servers", "liteRtBackend")) {
                db.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `liteRtBackend` TEXT NOT NULL DEFAULT 'auto'")
            }

            DebugLog.log("[DB] Migration 64 -> 65 complete")
        }
    }

    val MIGRATION_65_66 = object : Migration(65, 66) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 65 -> 66")

            if (tableExists(db, "litert_models") && !columnExists(db, "litert_models", "maxContextTokens")) {
                db.execSQL("ALTER TABLE `litert_models` ADD COLUMN `maxContextTokens` INTEGER")
            }

            DebugLog.log("[DB] Migration 65 -> 66 complete")
        }
    }

    val MIGRATION_66_67 = object : Migration(66, 67) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 66 -> 67")

            if (!tableExists(db, "live_translator_templates")) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `live_translator_templates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `speaker1Language` TEXT NOT NULL,
                        `speaker2Language` TEXT NOT NULL,
                        `whisperModelPath` TEXT,
                        `whisperThreads` INTEGER NOT NULL,
                        `ttsModelPath` TEXT,
                        `ttsModelName` TEXT,
                        `ttsLanguage` TEXT NOT NULL,
                        `ttsVoiceName` TEXT,
                        `ttsSteps` INTEGER NOT NULL,
                        `ttsSpeed` REAL NOT NULL,
                        `backendEngine` TEXT NOT NULL,
                        `llamaHost` TEXT NOT NULL,
                        `llamaPort` INTEGER NOT NULL,
                        `llamaModelName` TEXT,
                        `ollamaHost` TEXT NOT NULL,
                        `ollamaPort` INTEGER NOT NULL,
                        `ollamaModelName` TEXT,
                        `liteRtModelId` INTEGER,
                        `liteRtBackend` TEXT NOT NULL,
                        `contextSize` INTEGER NOT NULL,
                        `maxTokens` INTEGER NOT NULL,
                        `temperature` REAL NOT NULL,
                        `timeoutSeconds` INTEGER NOT NULL,
                        `startSpeakingTimeoutSeconds` INTEGER NOT NULL,
                        `finishedTalkingTimeoutSeconds` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_translator_templates_updatedAt` ON `live_translator_templates` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_translator_templates_backendEngine` ON `live_translator_templates` (`backendEngine`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_translator_templates_liteRtModelId` ON `live_translator_templates` (`liteRtModelId`)")
            }

            if (!tableExists(db, "live_translator_sessions")) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `live_translator_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `templateId` INTEGER,
                        `templateSnapshotJson` TEXT NOT NULL,
                        `speaker1Language` TEXT NOT NULL,
                        `speaker2Language` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_translator_sessions_updatedAt` ON `live_translator_sessions` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_translator_sessions_templateId` ON `live_translator_sessions` (`templateId`)")
            }

            if (!tableExists(db, "live_translator_turns")) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `live_translator_turns` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `speaker` INTEGER NOT NULL,
                        `originalText` TEXT NOT NULL,
                        `translatedText` TEXT,
                        `detectedLanguage` TEXT,
                        `sourceLanguage` TEXT NOT NULL,
                        `targetLanguage` TEXT NOT NULL,
                        `audioPath` TEXT,
                        `ttsAudioPath` TEXT,
                        `timestamp` INTEGER NOT NULL,
                        `isError` INTEGER NOT NULL,
                        `errorMessage` TEXT,
                        FOREIGN KEY(`sessionId`) REFERENCES `live_translator_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_translator_turns_sessionId` ON `live_translator_turns` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_translator_turns_timestamp` ON `live_translator_turns` (`timestamp`)")
            }

            DebugLog.log("[DB] Migration 66 -> 67 complete")
        }
    }

    val MIGRATION_67_68 = object : Migration(67, 68) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 67 -> 68")

            if (tableExists(db, "live_translator_templates")) {
                if (!columnExists(db, "live_translator_templates", "speaker1TtsLanguage")) {
                    db.execSQL("ALTER TABLE `live_translator_templates` ADD COLUMN `speaker1TtsLanguage` TEXT NOT NULL DEFAULT 'en'")
                    db.execSQL(
                        """
                        UPDATE `live_translator_templates`
                        SET `speaker1TtsLanguage` = CASE
                            WHEN TRIM(COALESCE(`ttsLanguage`, '')) = '' THEN 'en'
                            ELSE `ttsLanguage`
                        END
                        """.trimIndent()
                    )
                }
                if (!columnExists(db, "live_translator_templates", "speaker2TtsLanguage")) {
                    db.execSQL("ALTER TABLE `live_translator_templates` ADD COLUMN `speaker2TtsLanguage` TEXT NOT NULL DEFAULT 'es'")
                    db.execSQL(
                        """
                        UPDATE `live_translator_templates`
                        SET `speaker2TtsLanguage` = CASE
                            WHEN TRIM(COALESCE(`ttsLanguage`, '')) = '' THEN 'es'
                            ELSE `ttsLanguage`
                        END
                        """.trimIndent()
                    )
                }
            }

            DebugLog.log("[DB] Migration 67 -> 68 complete")
        }
    }

    val MIGRATION_68_69 = object : Migration(68, 69) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 68 -> 69")

            if (tableExists(db, "litert_models") && columnExists(db, "litert_models", "maxContextTokens")) {
                db.execSQL(
                    """
                    UPDATE `litert_models`
                    SET `maxContextTokens` = 32768
                    WHERE `maxContextTokens` = 4000
                        AND (
                            LOWER(COALESCE(`filename`, '')) LIKE '%gemma-4%'
                            OR LOWER(COALESCE(`displayName`, '')) LIKE '%gemma 4%'
                            OR LOWER(COALESCE(`displayName`, '')) LIKE '%gemma-4%'
                            OR LOWER(COALESCE(`repoId`, '')) LIKE '%gemma-4%'
                        )
                    """.trimIndent()
                )
            }

            DebugLog.log("[DB] Migration 68 -> 69 complete")
        }
    }

    val MIGRATION_69_70 = object : Migration(69, 70) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 69 -> 70")

            if (tableExists(db, "live_translator_templates") &&
                !columnExists(db, "live_translator_templates", "liteRtMtpEnabled")
            ) {
                db.execSQL("ALTER TABLE `live_translator_templates` ADD COLUMN `liteRtMtpEnabled` INTEGER NOT NULL DEFAULT 0")
            }

            DebugLog.log("[DB] Migration 69 -> 70 complete")
        }
    }

    val MIGRATION_70_71 = object : Migration(70, 71) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 70 -> 71")

            if (tableExists(db, "live_translator_templates") &&
                !columnExists(db, "live_translator_templates", "liteRtThinkingEnabled")
            ) {
                db.execSQL("ALTER TABLE `live_translator_templates` ADD COLUMN `liteRtThinkingEnabled` INTEGER NOT NULL DEFAULT 0")
            }

            DebugLog.log("[DB] Migration 70 -> 71 complete")
        }
    }

    val MIGRATION_71_72 = object : Migration(71, 72) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 71 -> 72")

            if (tableExists(db, "knowledge_bases") &&
                !columnExists(db, "knowledge_bases", "contentSummary")
            ) {
                db.execSQL("ALTER TABLE `knowledge_bases` ADD COLUMN `contentSummary` TEXT NOT NULL DEFAULT ''")
            }

            DebugLog.log("[DB] Migration 71 -> 72 complete")
        }
    }

    val MIGRATION_72_73 = object : Migration(72, 73) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 72 -> 73")

            if (tableExists(db, "litert_models")) {
                if (!columnExists(db, "litert_models", "supportsVision")) {
                    db.execSQL("ALTER TABLE `litert_models` ADD COLUMN `supportsVision` INTEGER NOT NULL DEFAULT 0")
                }
                if (!columnExists(db, "litert_models", "supportsAudio")) {
                    db.execSQL("ALTER TABLE `litert_models` ADD COLUMN `supportsAudio` INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL(
                    """
                    UPDATE `litert_models`
                    SET `supportsVision` = 1
                    WHERE LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%gemma-4%'
                        OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%gemma 4%'
                        OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%gemma-3n%'
                        OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%gemma 3n%'
                        OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%multimodal%'
                        OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%vision%'
                        OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%image%'
                        OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%vlm%'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `litert_models`
                    SET `supportsAudio` = 1
                    WHERE (
                            (
                                LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%gemma-4%'
                                OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%gemma 4%'
                            )
                            AND (
                                LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%e2b%'
                                OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%e4b%'
                            )
                        )
                        OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%gemma-3n%'
                        OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%gemma 3n%'
                        OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%audio%'
                        OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%speech%'
                    """.trimIndent()
                )
            }

            DebugLog.log("[DB] Migration 72 -> 73 complete")
        }
    }

    val MIGRATION_73_74 = object : Migration(73, 74) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 73 -> 74")

            if (!columnExists(db, "llama_servers", "preferWhisperAudioTranscription")) {
                db.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `preferWhisperAudioTranscription` INTEGER NOT NULL DEFAULT 0")
            }

            DebugLog.log("[DB] Migration 73 -> 74 complete")
        }
    }

    val MIGRATION_74_75 = object : Migration(74, 75) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 74 -> 75: AI servers hub")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_server_configs` (
                    `serverType` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `port` INTEGER NOT NULL,
                    `lanVisible` INTEGER NOT NULL,
                    `accessMode` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`serverType`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_server_users` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `username` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `passwordHash` TEXT NOT NULL,
                    `passwordSalt` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_server_users_username` ON `ai_server_users` (`username`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_server_permissions` (
                    `userId` INTEGER NOT NULL,
                    `serverType` TEXT NOT NULL,
                    `canAccess` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`userId`, `serverType`),
                    FOREIGN KEY(`userId`) REFERENCES `ai_server_users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_permissions_userId` ON `ai_server_permissions` (`userId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_permissions_serverType` ON `ai_server_permissions` (`serverType`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_server_sessions` (
                    `tokenHash` TEXT NOT NULL,
                    `userId` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `expiresAt` INTEGER NOT NULL,
                    `lastSeenAt` INTEGER NOT NULL,
                    PRIMARY KEY(`tokenHash`),
                    FOREIGN KEY(`userId`) REFERENCES `ai_server_users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_sessions_userId` ON `ai_server_sessions` (`userId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_sessions_expiresAt` ON `ai_server_sessions` (`expiresAt`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_server_artifacts` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `serverType` TEXT NOT NULL,
                    `ownerUserId` INTEGER,
                    `origin` TEXT NOT NULL,
                    `jobId` TEXT NOT NULL,
                    `artifactType` TEXT NOT NULL,
                    `path` TEXT NOT NULL,
                    `mimeType` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `metadataJson` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`ownerUserId`) REFERENCES `ai_server_users`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_artifacts_serverType` ON `ai_server_artifacts` (`serverType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_artifacts_ownerUserId` ON `ai_server_artifacts` (`ownerUserId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_artifacts_origin` ON `ai_server_artifacts` (`origin`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_server_artifacts_path` ON `ai_server_artifacts` (`path`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_artifacts_jobId` ON `ai_server_artifacts` (`jobId`)")

            val now = System.currentTimeMillis()
            val defaults = listOf(
                "image" to ("Image Studio" to 10101),
                "video" to ("Video Studio" to 10102),
                "workflows" to ("Workflows" to 10103),
                "tts" to ("Voice Studio" to 10104),
                "video_upscale" to ("Video Upscale" to 10105),
                "docs_datasets" to ("Docs and Datasets" to 10106),
                "llama_chat" to ("Llama Chat" to 10107)
            )
            defaults.forEach { (serverType, details) ->
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `ai_server_configs`
                    (`serverType`, `displayName`, `port`, `lanVisible`, `accessMode`, `enabled`, `createdAt`, `updatedAt`)
                    VALUES (?, ?, ?, 0, 'PUBLIC', 0, ?, ?)
                    """.trimIndent(),
                    arrayOf(serverType, details.first, details.second, now, now)
                )
            }

            DebugLog.log("[DB] Migration 74 -> 75 complete")
        }
    }

    val MIGRATION_75_76 = object : Migration(75, 76) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 75 -> 76: AI server web chat persistence")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_server_web_providers` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `engine` TEXT NOT NULL,
                    `baseUrl` TEXT NOT NULL,
                    `modelName` TEXT,
                    `liteRtModelId` INTEGER,
                    `liteRtBackend` TEXT NOT NULL,
                    `supportsVision` INTEGER NOT NULL,
                    `supportsAudio` INTEGER NOT NULL,
                    `defaultParamsJson` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_providers_engine` ON `ai_server_web_providers` (`engine`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_providers_updatedAt` ON `ai_server_web_providers` (`updatedAt`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_server_web_chats` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `providerId` INTEGER,
                    `systemPrompt` TEXT,
                    `apiParamsJson` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    FOREIGN KEY(`providerId`) REFERENCES `ai_server_web_providers`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_chats_providerId` ON `ai_server_web_chats` (`providerId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_chats_updatedAt` ON `ai_server_web_chats` (`updatedAt`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_server_web_messages` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `chatId` INTEGER NOT NULL,
                    `role` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `imagePath` TEXT,
                    `audioPath` TEXT,
                    `documentPath` TEXT,
                    `thinking` TEXT,
                    `toolActivity` TEXT,
                    `isError` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`chatId`) REFERENCES `ai_server_web_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_messages_chatId` ON `ai_server_web_messages` (`chatId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_messages_createdAt` ON `ai_server_web_messages` (`createdAt`)")

            DebugLog.log("[DB] Migration 75 -> 76 complete")
        }
    }

    val MIGRATION_76_77 = object : Migration(76, 77) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 76 -> 77: AI server web chat attachments")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_server_web_message_attachments` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `messageId` INTEGER NOT NULL,
                    `attachmentType` TEXT NOT NULL,
                    `path` TEXT NOT NULL,
                    `mimeType` TEXT,
                    `name` TEXT,
                    `sizeBytes` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`messageId`) REFERENCES `ai_server_web_messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_message_attachments_messageId` ON `ai_server_web_message_attachments` (`messageId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_message_attachments_attachmentType` ON `ai_server_web_message_attachments` (`attachmentType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_message_attachments_path` ON `ai_server_web_message_attachments` (`path`)")

            DebugLog.log("[DB] Migration 76 -> 77 complete")
        }
    }

    val MIGRATION_77_78 = object : Migration(77, 78) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 77 -> 78: AI server web chat tool events")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ai_server_web_tool_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `messageId` INTEGER NOT NULL,
                    `toolName` TEXT NOT NULL,
                    `phase` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `argumentsJson` TEXT,
                    `resultText` TEXT,
                    `errorText` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    FOREIGN KEY(`messageId`) REFERENCES `ai_server_web_messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_tool_events_messageId` ON `ai_server_web_tool_events` (`messageId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_tool_events_toolName` ON `ai_server_web_tool_events` (`toolName`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_tool_events_status` ON `ai_server_web_tool_events` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_tool_events_createdAt` ON `ai_server_web_tool_events` (`createdAt`)")

            DebugLog.log("[DB] Migration 77 -> 78 complete")
        }
    }

    val MIGRATION_78_79 = object : Migration(78, 79) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 78 -> 79: AI server web chat ownership")

            if (!columnExists(db, "ai_server_web_providers", "ownerUserId")) {
                db.execSQL("ALTER TABLE `ai_server_web_providers` ADD COLUMN `ownerUserId` INTEGER")
            }
            if (!columnExists(db, "ai_server_web_chats", "ownerUserId")) {
                db.execSQL("ALTER TABLE `ai_server_web_chats` ADD COLUMN `ownerUserId` INTEGER")
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_providers_ownerUserId` ON `ai_server_web_providers` (`ownerUserId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_server_web_chats_ownerUserId` ON `ai_server_web_chats` (`ownerUserId`)")

            DebugLog.log("[DB] Migration 78 -> 79 complete")
        }
    }

    val MIGRATION_79_80 = object : Migration(79, 80) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 79 -> 80: speculative mode persistence")

            if (!columnExists(db, "saved_commands", "speculativeMode")) {
                db.execSQL(
                    "ALTER TABLE `saved_commands` ADD COLUMN `speculativeMode` TEXT NOT NULL DEFAULT 'draft-simple'"
                )
            }

            DebugLog.log("[DB] Migration 79 -> 80 complete")
        }
    }

    val MIGRATION_80_81 = object : Migration(80, 81) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 80 -> 81: live translator full backend URLs")

            if (tableExists(db, "live_translator_templates")) {
                if (!columnExists(db, "live_translator_templates", "llamaServerUrl")) {
                    db.execSQL(
                        "ALTER TABLE `live_translator_templates` ADD COLUMN `llamaServerUrl` TEXT NOT NULL DEFAULT 'http://localhost:8080'"
                    )
                }
                if (!columnExists(db, "live_translator_templates", "llamaSwapUrl")) {
                    db.execSQL(
                        "ALTER TABLE `live_translator_templates` ADD COLUMN `llamaSwapUrl` TEXT NOT NULL DEFAULT 'http://localhost:9292'"
                    )
                }
                if (!columnExists(db, "live_translator_templates", "ollamaUrl")) {
                    db.execSQL(
                        "ALTER TABLE `live_translator_templates` ADD COLUMN `ollamaUrl` TEXT NOT NULL DEFAULT 'http://localhost:11434'"
                    )
                }

                db.query(
                    """
                    SELECT `id`, `llamaHost`, `llamaPort`, `ollamaHost`, `ollamaPort`
                    FROM `live_translator_templates`
                    """.trimIndent()
                ).use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow("id")
                    val llamaHostIndex = cursor.getColumnIndexOrThrow("llamaHost")
                    val llamaPortIndex = cursor.getColumnIndexOrThrow("llamaPort")
                    val ollamaHostIndex = cursor.getColumnIndexOrThrow("ollamaHost")
                    val ollamaPortIndex = cursor.getColumnIndexOrThrow("ollamaPort")

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val llamaUrl = RemoteBackendUrlSupport.fromHostPort(
                            host = cursor.getString(llamaHostIndex),
                            port = cursor.getInt(llamaPortIndex),
                            defaultPort = 8080
                        ).normalizedUrl
                        val ollamaUrl = RemoteBackendUrlSupport.fromHostPort(
                            host = cursor.getString(ollamaHostIndex),
                            port = cursor.getInt(ollamaPortIndex),
                            defaultPort = 11434
                        ).normalizedUrl
                        db.execSQL(
                            """
                            UPDATE `live_translator_templates`
                            SET `llamaServerUrl` = ?, `llamaSwapUrl` = ?, `ollamaUrl` = ?
                            WHERE `id` = ?
                            """.trimIndent(),
                            arrayOf(llamaUrl, llamaUrl, ollamaUrl, id)
                        )
                    }
                }
            }

            DebugLog.log("[DB] Migration 80 -> 81 complete")
        }
    }

    val MIGRATION_81_82 = object : Migration(81, 82) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 81 -> 82: LiteRT embedding support")

            if (tableExists(db, "litert_models") && !columnExists(db, "litert_models", "supportsEmbedding")) {
                db.execSQL("ALTER TABLE `litert_models` ADD COLUMN `supportsEmbedding` INTEGER NOT NULL DEFAULT 0")
            }

            if (tableExists(db, "litert_models")) {
                db.execSQL(
                    """
                    UPDATE `litert_models`
                    SET `supportsEmbedding` = 1
                    WHERE LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%embed%'
                       OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%embedding%'
                       OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%gte%'
                       OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%bge%'
                       OR LOWER(COALESCE(`displayName`, '') || ' ' || COALESCE(`filename`, '') || ' ' || COALESCE(`repoId`, '')) LIKE '%e5%'
                    """.trimIndent()
                )
            }

            DebugLog.log("[DB] Migration 81 -> 82 complete")
        }
    }

    val MIGRATION_82_83 = object : Migration(82, 83) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 82 -> 83: LiteRT KB embedding runnable metadata")

            if (tableExists(db, "litert_models")) {
                if (!columnExists(db, "litert_models", "kbEmbeddingRunnable")) {
                    db.execSQL("ALTER TABLE `litert_models` ADD COLUMN `kbEmbeddingRunnable` INTEGER NOT NULL DEFAULT 0")
                }
                if (!columnExists(db, "litert_models", "kbEmbeddingRuntime")) {
                    db.execSQL("ALTER TABLE `litert_models` ADD COLUMN `kbEmbeddingRuntime` TEXT")
                }
                if (!columnExists(db, "litert_models", "kbEmbeddingStatus")) {
                    db.execSQL("ALTER TABLE `litert_models` ADD COLUMN `kbEmbeddingStatus` TEXT")
                }
                db.execSQL(
                    """
                    UPDATE `litert_models`
                    SET `kbEmbeddingRunnable` = 0,
                        `kbEmbeddingStatus` = 'needs_recheck'
                    WHERE `supportsEmbedding` = 1
                    """.trimIndent()
                )
            }

            DebugLog.log("[DB] Migration 82 -> 83 complete")
        }
    }

    val MIGRATION_83_84 = object : Migration(83, 84) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 83 -> 84: AI Hub chat pins and speculative run history")

            if (tableExists(db, "llama_chats")) {
                if (!columnExists(db, "llama_chats", "pinnedToAiHub")) {
                    db.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `pinnedToAiHub` INTEGER NOT NULL DEFAULT 0")
                }
                if (!columnExists(db, "llama_chats", "pinnedServerId")) {
                    db.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `pinnedServerId` INTEGER")
                }
                if (!columnExists(db, "llama_chats", "pinnedAt")) {
                    db.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `pinnedAt` INTEGER")
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_chats_pinnedToAiHub` ON `llama_chats` (`pinnedToAiHub`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_chats_pinnedServerId` ON `llama_chats` (`pinnedServerId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_chats_pinnedAt` ON `llama_chats` (`pinnedAt`)")
            }

            if (tableExists(db, "saved_commands") && !columnExists(db, "saved_commands", "nativeToolsEnabled")) {
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `nativeToolsEnabled` INTEGER NOT NULL DEFAULT 0")
            }

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `llama_speculative_runs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT,
                    `savedForever` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `modelPath` TEXT NOT NULL,
                    `modelName` TEXT NOT NULL,
                    `speculativeMode` TEXT NOT NULL,
                    `draftModelPath` TEXT,
                    `draftModelName` TEXT,
                    `acceptanceRate` REAL,
                    `promptTokensPerSecond` REAL,
                    `generationTokensPerSecond` REAL,
                    `rawMetrics` TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_speculative_runs_createdAt` ON `llama_speculative_runs` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_speculative_runs_savedForever` ON `llama_speculative_runs` (`savedForever`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_speculative_runs_speculativeMode` ON `llama_speculative_runs` (`speculativeMode`)")

            DebugLog.log("[DB] Migration 83 -> 84 complete")
        }
    }

    val MIGRATION_84_85 = object : Migration(84, 85) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 84 -> 85: speculative run live aggregate sample count")

            if (tableExists(db, "llama_speculative_runs") &&
                !columnExists(db, "llama_speculative_runs", "sampleCount")
            ) {
                db.execSQL("ALTER TABLE `llama_speculative_runs` ADD COLUMN `sampleCount` INTEGER NOT NULL DEFAULT 0")
            }

            DebugLog.log("[DB] Migration 84 -> 85 complete")
        }
    }

    val MIGRATION_85_86 = object : Migration(85, 86) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 85 -> 86: saved command n-gram speculative settings")

            if (tableExists(db, "saved_commands")) {
                if (!columnExists(db, "saved_commands", "ngramModNMatch")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `ngramModNMatch` INTEGER NOT NULL DEFAULT 24")
                }
                if (!columnExists(db, "saved_commands", "ngramModNMin")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `ngramModNMin` INTEGER NOT NULL DEFAULT 48")
                }
                if (!columnExists(db, "saved_commands", "ngramModNMax")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `ngramModNMax` INTEGER NOT NULL DEFAULT 64")
                }
                if (!columnExists(db, "saved_commands", "ngramSimpleSizeN")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `ngramSimpleSizeN` INTEGER NOT NULL DEFAULT 12")
                }
                if (!columnExists(db, "saved_commands", "ngramSimpleSizeM")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `ngramSimpleSizeM` INTEGER NOT NULL DEFAULT 48")
                }
                if (!columnExists(db, "saved_commands", "ngramSimpleMinHits")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `ngramSimpleMinHits` INTEGER NOT NULL DEFAULT 1")
                }
            }

            DebugLog.log("[DB] Migration 85 -> 86 complete")
        }
    }

    val MIGRATION_86_87 = object : Migration(86, 87) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 86 -> 87: saved command advanced n-gram speculative settings")

            if (tableExists(db, "saved_commands")) {
                if (!columnExists(db, "saved_commands", "ngramMapKSizeN")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `ngramMapKSizeN` INTEGER NOT NULL DEFAULT 12")
                }
                if (!columnExists(db, "saved_commands", "ngramMapKSizeM")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `ngramMapKSizeM` INTEGER NOT NULL DEFAULT 48")
                }
                if (!columnExists(db, "saved_commands", "ngramMapKMinHits")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `ngramMapKMinHits` INTEGER NOT NULL DEFAULT 1")
                }
                if (!columnExists(db, "saved_commands", "ngramMapK4VSizeN")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `ngramMapK4VSizeN` INTEGER NOT NULL DEFAULT 12")
                }
                if (!columnExists(db, "saved_commands", "ngramMapK4VSizeM")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `ngramMapK4VSizeM` INTEGER NOT NULL DEFAULT 48")
                }
                if (!columnExists(db, "saved_commands", "ngramMapK4VMinHits")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `ngramMapK4VMinHits` INTEGER NOT NULL DEFAULT 1")
                }
            }

            DebugLog.log("[DB] Migration 86 -> 87 complete")
        }
    }

    val MIGRATION_87_88 = object : Migration(87, 88) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 87 -> 88: saved command draft thread settings")

            if (tableExists(db, "saved_commands")) {
                if (!columnExists(db, "saved_commands", "draftThreads")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `draftThreads` INTEGER NOT NULL DEFAULT 4")
                }
                if (!columnExists(db, "saved_commands", "draftThreadsBatch")) {
                    db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `draftThreadsBatch` INTEGER NOT NULL DEFAULT 4")
                }
            }

            DebugLog.log("[DB] Migration 87 -> 88 complete")
        }
    }

    val MIGRATION_88_89 = object : Migration(88, 89) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 88 -> 89: SD distributed inference tables")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sd_distributed_workers` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `host` TEXT NOT NULL,
                    `port` INTEGER NOT NULL,
                    `deviceName` TEXT NOT NULL,
                    `ramMB` INTEGER NOT NULL,
                    `threads` INTEGER NOT NULL,
                    `backendDevice` TEXT NOT NULL,
                    `isEnabled` INTEGER NOT NULL,
                    `lastSeenAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sd_distributed_workers_host_port` ON `sd_distributed_workers` (`host`, `port`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sd_distributed_placements` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `placementMode` TEXT NOT NULL,
                    `backendSpec` TEXT NOT NULL,
                    `paramsBackendSpec` TEXT NOT NULL,
                    `autoFit` INTEGER NOT NULL,
                    `maxVramSpec` TEXT NOT NULL,
                    `splitMode` TEXT NOT NULL,
                    `customFlags` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sd_distributed_runs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `mode` TEXT NOT NULL,
                    `modelName` TEXT NOT NULL,
                    `rpcServers` TEXT NOT NULL,
                    `backendSpec` TEXT NOT NULL,
                    `paramsBackendSpec` TEXT NOT NULL,
                    `splitMode` TEXT NOT NULL,
                    `autoFit` INTEGER NOT NULL,
                    `maxVramSpec` TEXT NOT NULL,
                    `commandPreview` TEXT NOT NULL,
                    `status` TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sd_distributed_runs_createdAt` ON `sd_distributed_runs` (`createdAt`)")

            DebugLog.log("[DB] Migration 88 -> 89 complete")
        }
    }

    val MIGRATION_89_90 = object : Migration(89, 90) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 89 -> 90: SD distributed master settings")

            if (tableExists(db, "sd_distributed_workers") && !columnExists(db, "sd_distributed_workers", "sortOrder")) {
                db.execSQL("ALTER TABLE `sd_distributed_workers` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `sd_distributed_workers` SET `sortOrder` = `id`")
            }

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sd_distributed_master_settings` (
                    `id` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `placementMode` TEXT NOT NULL,
                    `backendSpec` TEXT NOT NULL,
                    `paramsBackendSpec` TEXT NOT NULL,
                    `autoFit` INTEGER NOT NULL,
                    `maxVramEnabled` INTEGER NOT NULL,
                    `maxVramSpec` TEXT NOT NULL,
                    `splitMode` TEXT NOT NULL,
                    `customFlags` TEXT NOT NULL,
                    `prompt` TEXT NOT NULL,
                    `negativePrompt` TEXT NOT NULL,
                    `dimensions` TEXT NOT NULL,
                    `steps` TEXT NOT NULL,
                    `cfg` TEXT NOT NULL,
                    `seed` TEXT NOT NULL,
                    `sampler` TEXT NOT NULL,
                    `scheduler` TEXT NOT NULL,
                    `batchCount` TEXT NOT NULL,
                    `clipSkip` TEXT NOT NULL,
                    `strength` TEXT NOT NULL,
                    `frames` TEXT NOT NULL,
                    `fps` TEXT NOT NULL,
                    `runtimeThreads` TEXT NOT NULL,
                    `mmap` INTEGER NOT NULL,
                    `diffusionFa` INTEGER NOT NULL,
                    `vaeTiling` INTEGER NOT NULL,
                    `vaeTileSize` TEXT NOT NULL,
                    `vaeTileOverlap` TEXT NOT NULL,
                    `flowShift` TEXT NOT NULL,
                    `quantization` TEXT NOT NULL,
                    `tensorRules` TEXT NOT NULL,
                    `loraStrength` TEXT NOT NULL,
                    `controlStrength` TEXT NOT NULL,
                    `cacheMode` TEXT NOT NULL,
                    `cacheOption` TEXT NOT NULL,
                    `scmMask` TEXT NOT NULL,
                    `scmPolicy` TEXT NOT NULL,
                    `devicesExpanded` INTEGER NOT NULL,
                    `plannerExpanded` INTEGER NOT NULL,
                    `generationExpanded` INTEGER NOT NULL,
                    `runtimeExpanded` INTEGER NOT NULL,
                    `adaptersExpanded` INTEGER NOT NULL,
                    `expertExpanded` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                INSERT OR IGNORE INTO `sd_distributed_master_settings` (
                    `id`, `updatedAt`, `enabled`, `placementMode`, `backendSpec`, `paramsBackendSpec`,
                    `autoFit`, `maxVramEnabled`, `maxVramSpec`, `splitMode`, `customFlags`,
                    `prompt`, `negativePrompt`, `dimensions`, `steps`, `cfg`, `seed`, `sampler`,
                    `scheduler`, `batchCount`, `clipSkip`, `strength`, `frames`, `fps`,
                    `runtimeThreads`, `mmap`, `diffusionFa`, `vaeTiling`, `vaeTileSize`,
                    `vaeTileOverlap`, `flowShift`, `quantization`, `tensorRules`,
                    `loraStrength`, `controlStrength`, `cacheMode`, `cacheOption`,
                    `scmMask`, `scmPolicy`, `devicesExpanded`, `plannerExpanded`,
                    `generationExpanded`, `runtimeExpanded`, `adaptersExpanded`, `expertExpanded`
                ) VALUES (
                    1, 0, 0, 'AUTO_RAM', '', '', 0, 0, '', 'layer', '',
                    '', '', '512 x 512', '20', '7.0', '-1', 'euler_a',
                    '', '1', '0', '0.75', '8', '5',
                    '-1', 0, 0, 0, '',
                    '0', '', '', '',
                    '1.0', '0.9', '', '',
                    '', '', 1, 1,
                    0, 0, 0, 1
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sd_distributed_templates` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `settingsJson` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sd_distributed_templates_name` ON `sd_distributed_templates` (`name`)")

            DebugLog.log("[DB] Migration 89 -> 90 complete")
        }
    }

    val MIGRATION_90_91 = object : Migration(90, 91) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 90 -> 91: local SD backend preferences")

            if (!columnExists(db, "models", "sdParamsBackendMode")) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `sdParamsBackendMode` TEXT NOT NULL DEFAULT 'auto'")
            }
            if (!columnExists(db, "models", "sdRuntimeBackendMode")) {
                db.execSQL("ALTER TABLE `models` ADD COLUMN `sdRuntimeBackendMode` TEXT NOT NULL DEFAULT 'auto'")
            }

            DebugLog.log("[DB] Migration 90 -> 91 complete")
        }
    }

    val MIGRATION_91_92 = object : Migration(91, 92) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 91 -> 92: SD distributed console workflow settings")

            fun addMasterColumn(name: String, definition: String) {
                if (tableExists(db, "sd_distributed_master_settings") &&
                    !columnExists(db, "sd_distributed_master_settings", name)
                ) {
                    db.execSQL("ALTER TABLE `sd_distributed_master_settings` ADD COLUMN `$name` $definition")
                }
            }

            addMasterColumn("masterContributes", "INTEGER NOT NULL DEFAULT 0")
            addMasterColumn("masterDisplayName", "TEXT NOT NULL DEFAULT 'This device'")
            addMasterColumn("masterRamMB", "INTEGER NOT NULL DEFAULT 4096")
            addMasterColumn("masterThreads", "INTEGER NOT NULL DEFAULT 4")
            addMasterColumn("masterBackendDevice", "TEXT NOT NULL DEFAULT 'cpu'")
            addMasterColumn("masterAllowedModules", "TEXT NOT NULL DEFAULT 'diffusion,te,vae,controlnet,upscaler'")
            addMasterColumn("masterDiffusionSharePercent", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageWorkflowMode", "TEXT NOT NULL DEFAULT 'TXT2IMG'")
            addMasterColumn("imageModelPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageUpscalerModelPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageInputPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("videoWorkflowMode", "TEXT NOT NULL DEFAULT 'TXT2VID'")
            addMasterColumn("videoModelPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("videoInputPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageExpanded", "INTEGER NOT NULL DEFAULT 1")
            addMasterColumn("videoExpanded", "INTEGER NOT NULL DEFAULT 0")

            if (tableExists(db, "sd_distributed_templates") &&
                !columnExists(db, "sd_distributed_templates", "workflowType")
            ) {
                db.execSQL("ALTER TABLE `sd_distributed_templates` ADD COLUMN `workflowType` TEXT NOT NULL DEFAULT 'IMAGE'")
                db.execSQL(
                    """
                    UPDATE `sd_distributed_templates`
                    SET `workflowType` = 'VIDEO'
                    WHERE `settingsJson` LIKE '%"videoWorkflowMode"%'
                       OR `settingsJson` LIKE '%"videoModelPath"%'
                       OR `settingsJson` LIKE '%"TXT2VID"%'
                       OR `settingsJson` LIKE '%"IMG2VID"%'
                    """.trimIndent()
                )
            }

            DebugLog.log("[DB] Migration 91 -> 92 complete")
        }
    }

    val MIGRATION_92_93 = object : Migration(92, 93) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 92 -> 93: SD distributed model components")

            fun addMasterColumn(name: String, definition: String) {
                if (tableExists(db, "sd_distributed_master_settings") &&
                    !columnExists(db, "sd_distributed_master_settings", name)
                ) {
                    db.execSQL("ALTER TABLE `sd_distributed_master_settings` ADD COLUMN `$name` $definition")
                }
            }

            addMasterColumn("imageVaePath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageTaePath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageClipLPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageClipGPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageT5xxlPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageLlmPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageLlmVisionPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imagePhotoMakerPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageControlNetEnabled", "INTEGER NOT NULL DEFAULT 0")
            addMasterColumn("imageControlNetPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageLoraEnabled", "INTEGER NOT NULL DEFAULT 0")
            addMasterColumn("imageLoraPath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageLoraApplyMode", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("videoUseVae", "INTEGER NOT NULL DEFAULT 0")
            addMasterColumn("videoVaePath", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("videoUseT5xxl", "INTEGER NOT NULL DEFAULT 0")
            addMasterColumn("videoT5xxlPath", "TEXT NOT NULL DEFAULT ''")

            DebugLog.log("[DB] Migration 92 -> 93 complete")
        }
    }

    val MIGRATION_93_94 = object : Migration(93, 94) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 93 -> 94: SD distributed Auto RAM scope")

            if (tableExists(db, "sd_distributed_master_settings") &&
                !columnExists(db, "sd_distributed_master_settings", "autoRamScope")
            ) {
                db.execSQL(
                    "ALTER TABLE `sd_distributed_master_settings` ADD COLUMN `autoRamScope` TEXT NOT NULL DEFAULT 'DIFFUSION_ONLY'"
                )
            }

            DebugLog.log("[DB] Migration 93 -> 94 complete")
        }
    }

    val MIGRATION_94_95 = object : Migration(94, 95) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 94 -> 95: durable download tasks")

            if (!tableExists(db, "download_tasks")) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `download_tasks` (" +
                        "`id` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`destPath` TEXT NOT NULL, " +
                        "`filename` TEXT NOT NULL, " +
                        "`repoId` TEXT NOT NULL, " +
                        "`progressKey` TEXT NOT NULL, " +
                        "`modelType` TEXT NOT NULL, " +
                        "`isVision` INTEGER NOT NULL DEFAULT 0, " +
                        "`sdCapabilities` TEXT DEFAULT NULL, " +
                        "`sdFamily` TEXT DEFAULT NULL, " +
                        "`sdVariant` TEXT DEFAULT NULL, " +
                        "`sdCompatProfiles` TEXT DEFAULT NULL, " +
                        "`onnxCapabilities` TEXT DEFAULT NULL, " +
                        "`onnxAssetKind` TEXT DEFAULT NULL, " +
                        "`onnxPipelineFamily` TEXT DEFAULT NULL, " +
                        "`onnxReferenceUri` TEXT DEFAULT NULL, " +
                        "`onnxReferencePath` TEXT DEFAULT NULL, " +
                        "`onnxInstallKind` TEXT DEFAULT NULL, " +
                        "`onnxInstallDirPath` TEXT DEFAULT NULL, " +
                        "`huggingFaceToken` TEXT DEFAULT NULL, " +
                        "`liteRtDisplayName` TEXT DEFAULT NULL, " +
                        "`liteRtSourceUri` TEXT DEFAULT NULL, " +
                        "`liteRtBackendPreference` TEXT DEFAULT NULL, " +
                        "`liteRtSupportsCpu` INTEGER DEFAULT NULL, " +
                        "`liteRtSupportsGpu` INTEGER DEFAULT NULL, " +
                        "`liteRtSupportsVision` INTEGER DEFAULT NULL, " +
                        "`liteRtSupportsAudio` INTEGER DEFAULT NULL, " +
                        "`liteRtSupportsEmbedding` INTEGER DEFAULT NULL, " +
                        "`liteRtMaxContextTokens` INTEGER DEFAULT NULL, " +
                        "`status` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                        "`bytesDownloaded` INTEGER NOT NULL DEFAULT 0, " +
                        "`totalBytes` INTEGER DEFAULT NULL, " +
                        "`lastError` TEXT DEFAULT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`)" +
                        ")"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_tasks_modelType` ON `download_tasks` (`modelType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_tasks_status` ON `download_tasks` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_tasks_updatedAt` ON `download_tasks` (`updatedAt`)")
            }

            DebugLog.log("[DB] Migration 94 -> 95 complete")
        }
    }

    val MIGRATION_95_96 = object : Migration(95, 96) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 95 -> 96: SD distributed card custom flags")

            fun addMasterColumn(name: String, definition: String) {
                if (tableExists(db, "sd_distributed_master_settings") &&
                    !columnExists(db, "sd_distributed_master_settings", name)
                ) {
                    db.execSQL("ALTER TABLE `sd_distributed_master_settings` ADD COLUMN `$name` $definition")
                }
            }

            addMasterColumn("imageCustomFlags", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("videoCustomFlags", "TEXT NOT NULL DEFAULT ''")

            DebugLog.log("[DB] Migration 95 -> 96 complete")
        }
    }

    val MIGRATION_96_97 = object : Migration(96, 97) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 96 -> 97: agent local sandbox workspace metadata")

            fun addConversationColumn(name: String, definition: String) {
                if (tableExists(db, "agent_conversations") &&
                    !columnExists(db, "agent_conversations", name)
                ) {
                    db.execSQL("ALTER TABLE `agent_conversations` ADD COLUMN `$name` $definition")
                }
            }

            addConversationColumn("workspaceBackend", "TEXT NOT NULL DEFAULT 'REMOTE_SSH'")
            addConversationColumn("runtimeCapabilitiesJson", "TEXT NOT NULL DEFAULT ''")
            addConversationColumn("runEntrypointPath", "TEXT DEFAULT NULL")
            addConversationColumn("runUiMode", "TEXT NOT NULL DEFAULT 'CONSOLE'")
            addConversationColumn("lastRunProfileJson", "TEXT NOT NULL DEFAULT ''")

            if (!tableExists(db, "agent_project_runs")) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_project_runs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `projectFolder` TEXT NOT NULL,
                        `backend` TEXT NOT NULL DEFAULT 'LOCAL_SANDBOX',
                        `runtime` TEXT NOT NULL DEFAULT '',
                        `entrypoint` TEXT NOT NULL DEFAULT '',
                        `uiMode` TEXT NOT NULL DEFAULT 'CONSOLE',
                        `status` TEXT NOT NULL DEFAULT 'STOPPED',
                        `logs` TEXT NOT NULL DEFAULT '',
                        `previewUrl` TEXT DEFAULT NULL,
                        `startedAt` INTEGER DEFAULT NULL,
                        `endedAt` INTEGER DEFAULT NULL,
                        `exitCode` INTEGER DEFAULT NULL,
                        `stopRequestedAt` INTEGER DEFAULT NULL,
                        `forceStopRequestedAt` INTEGER DEFAULT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`conversationId`) REFERENCES `agent_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_runs_conversationId` ON `agent_project_runs` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_runs_status` ON `agent_project_runs` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_runs_updatedAt` ON `agent_project_runs` (`updatedAt`)")
            }

            DebugLog.log("[DB] Migration 96 -> 97 complete")
        }
    }

    val MIGRATION_97_98 = object : Migration(97, 98) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 97 -> 98: agent project organization and resume controls")

            fun addConversationColumn(name: String, definition: String) {
                if (tableExists(db, "agent_conversations") &&
                    !columnExists(db, "agent_conversations", name)
                ) {
                    db.execSQL("ALTER TABLE `agent_conversations` ADD COLUMN `$name` $definition")
                }
            }

            if (!tableExists(db, "agent_project_folders")) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_project_folders` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `parentId` INTEGER DEFAULT NULL,
                        `name` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL DEFAULT 0,
                        `isCollapsed` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_folders_parentId` ON `agent_project_folders` (`parentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_folders_sortOrder` ON `agent_project_folders` (`sortOrder`)")
            }

            addConversationColumn("projectFolderId", "INTEGER DEFAULT NULL")
            addConversationColumn("sortOrder", "INTEGER NOT NULL DEFAULT 0")
            addConversationColumn("planningModeEnabled", "INTEGER NOT NULL DEFAULT 0")
            addConversationColumn("resumeState", "TEXT NOT NULL DEFAULT 'IDLE'")
            addConversationColumn("lastStopReason", "TEXT DEFAULT NULL")

            if (tableExists(db, "agent_conversations")) {
                db.execSQL(
                    """
                    UPDATE `agent_conversations`
                    SET `sortOrder` = (
                        SELECT COUNT(*)
                        FROM `agent_conversations` AS newer
                        WHERE newer.`updatedAt` > `agent_conversations`.`updatedAt`
                           OR (newer.`updatedAt` = `agent_conversations`.`updatedAt` AND newer.`id` < `agent_conversations`.`id`)
                    )
                    """.trimIndent()
                )
            }

            DebugLog.log("[DB] Migration 97 -> 98 complete")
        }
    }

    val MIGRATION_98_99 = object : Migration(98, 99) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 98 -> 99: split SD distributed image and video run settings")

            fun addMasterColumn(name: String, definition: String) {
                if (tableExists(db, "sd_distributed_master_settings") &&
                    !columnExists(db, "sd_distributed_master_settings", name)
                ) {
                    db.execSQL("ALTER TABLE `sd_distributed_master_settings` ADD COLUMN `$name` $definition")
                }
            }

            addMasterColumn("imagePrompt", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageNegativePrompt", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageWidth", "TEXT NOT NULL DEFAULT '512'")
            addMasterColumn("imageHeight", "TEXT NOT NULL DEFAULT '512'")
            addMasterColumn("imageSteps", "TEXT NOT NULL DEFAULT '20'")
            addMasterColumn("imageCfg", "TEXT NOT NULL DEFAULT '7.0'")
            addMasterColumn("imageSeed", "TEXT NOT NULL DEFAULT '-1'")
            addMasterColumn("imageSampler", "TEXT NOT NULL DEFAULT 'euler_a'")
            addMasterColumn("imageScheduler", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("imageFlowShift", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("videoPrompt", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("videoNegativePrompt", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("videoWidth", "TEXT NOT NULL DEFAULT '480'")
            addMasterColumn("videoHeight", "TEXT NOT NULL DEFAULT '832'")
            addMasterColumn("videoSteps", "TEXT NOT NULL DEFAULT '18'")
            addMasterColumn("videoCfg", "TEXT NOT NULL DEFAULT '6.0'")
            addMasterColumn("videoSeed", "TEXT NOT NULL DEFAULT '-1'")
            addMasterColumn("videoSampler", "TEXT NOT NULL DEFAULT 'euler'")
            addMasterColumn("videoScheduler", "TEXT NOT NULL DEFAULT ''")
            addMasterColumn("videoFlowShift", "TEXT NOT NULL DEFAULT ''")

            if (tableExists(db, "sd_distributed_master_settings")) {
                db.execSQL(
                    """
                    UPDATE `sd_distributed_master_settings`
                    SET
                        `imagePrompt` = `prompt`,
                        `videoPrompt` = `prompt`,
                        `imageNegativePrompt` = `negativePrompt`,
                        `videoNegativePrompt` = `negativePrompt`,
                        `imageSteps` = `steps`,
                        `videoSteps` = `steps`,
                        `imageCfg` = `cfg`,
                        `videoCfg` = `cfg`,
                        `imageSeed` = `seed`,
                        `videoSeed` = `seed`,
                        `imageSampler` = `sampler`,
                        `videoSampler` = `sampler`,
                        `imageScheduler` = `scheduler`,
                        `videoScheduler` = `scheduler`,
                        `imageFlowShift` = `flowShift`,
                        `videoFlowShift` = `flowShift`
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `sd_distributed_master_settings`
                    SET
                        `imageWidth` = substr(replace(lower(`dimensions`), ' ', ''), 1, instr(replace(lower(`dimensions`), ' ', ''), 'x') - 1),
                        `videoWidth` = substr(replace(lower(`dimensions`), ' ', ''), 1, instr(replace(lower(`dimensions`), ' ', ''), 'x') - 1),
                        `imageHeight` = substr(replace(lower(`dimensions`), ' ', ''), instr(replace(lower(`dimensions`), ' ', ''), 'x') + 1),
                        `videoHeight` = substr(replace(lower(`dimensions`), ' ', ''), instr(replace(lower(`dimensions`), ' ', ''), 'x') + 1)
                    WHERE instr(replace(lower(`dimensions`), ' ', ''), 'x') > 1
                        AND CAST(substr(replace(lower(`dimensions`), ' ', ''), 1, instr(replace(lower(`dimensions`), ' ', ''), 'x') - 1) AS INTEGER) >= 64
                        AND CAST(substr(replace(lower(`dimensions`), ' ', ''), instr(replace(lower(`dimensions`), ' ', ''), 'x') + 1) AS INTEGER) >= 64
                    """.trimIndent()
                )
            }

            DebugLog.log("[DB] Migration 98 -> 99 complete")
        }
    }

    val MIGRATION_99_100 = object : Migration(99, 100) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 99 -> 100: add Wear ephemeral llama chat metadata")

            if (tableExists(db, "llama_chats")) {
                if (!columnExists(db, "llama_chats", "isEphemeral")) {
                    db.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `isEphemeral` INTEGER NOT NULL DEFAULT 0")
                }
                if (!columnExists(db, "llama_chats", "source")) {
                    db.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `source` TEXT")
                }
                if (!columnExists(db, "llama_chats", "deleteAfterSession")) {
                    db.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `deleteAfterSession` INTEGER NOT NULL DEFAULT 0")
                }
                if (!columnExists(db, "llama_chats", "expiresAtMillis")) {
                    db.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `expiresAtMillis` INTEGER")
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_chats_isEphemeral` ON `llama_chats` (`isEphemeral`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_chats_source` ON `llama_chats` (`source`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_chats_expiresAtMillis` ON `llama_chats` (`expiresAtMillis`)")
            }

            DebugLog.log("[DB] Migration 99 -> 100 complete")
        }
    }

    val MIGRATION_100_101 = object : Migration(100, 101) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 100 -> 101: add durable AI Agent project event journal")

            if (!tableExists(db, "agent_project_events")) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_project_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `projectFolder` TEXT NOT NULL DEFAULT 'default_project',
                        `timestamp` INTEGER NOT NULL,
                        `sequenceNumber` INTEGER NOT NULL DEFAULT 0,
                        `category` TEXT NOT NULL DEFAULT 'UI',
                        `eventType` TEXT NOT NULL DEFAULT 'event',
                        `phase` TEXT,
                        `agentRole` TEXT,
                        `customAgentName` TEXT,
                        `toolName` TEXT,
                        `toolCallId` TEXT,
                        `status` TEXT,
                        `durationMs` INTEGER,
                        `contentChars` INTEGER,
                        `contentLines` INTEGER,
                        `toolOutputChars` INTEGER,
                        `toolOutputLines` INTEGER,
                        `contextPercent` INTEGER,
                        `activeJobCount` INTEGER,
                        `foregroundState` TEXT,
                        `protectionState` TEXT,
                        `connectionState` TEXT,
                        `errorClass` TEXT,
                        `errorMessage` TEXT,
                        `summary` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`conversationId`) REFERENCES `agent_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }

            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_events_conversationId` ON `agent_project_events` (`conversationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_events_timestamp` ON `agent_project_events` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_events_sequenceNumber` ON `agent_project_events` (`sequenceNumber`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_events_category` ON `agent_project_events` (`category`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_events_eventType` ON `agent_project_events` (`eventType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_events_toolCallId` ON `agent_project_events` (`toolCallId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_events_status` ON `agent_project_events` (`status`)")

            DebugLog.log("[DB] Migration 100 -> 101 complete")
        }
    }

    val MIGRATION_101_102 = object : Migration(101, 102) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 101 -> 102: add durable Agent workflow, skills, and compaction state")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_message_parts` (
                    `id` TEXT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `messageOriginalId` TEXT NOT NULL,
                    `position` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `textPreview` TEXT,
                    `canonicalJson` TEXT,
                    `contentRef` TEXT,
                    `toolName` TEXT,
                    `toolCallId` TEXT,
                    `safeTarget` TEXT,
                    `durationMs` INTEGER,
                    `metadataJson` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`conversationId`) REFERENCES `agent_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_message_parts_conversationId` ON `agent_message_parts` (`conversationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_message_parts_messageOriginalId` ON `agent_message_parts` (`messageOriginalId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_message_parts_messageOriginalId_position` ON `agent_message_parts` (`messageOriginalId`, `position`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_message_parts_type` ON `agent_message_parts` (`type`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_message_parts_toolCallId` ON `agent_message_parts` (`toolCallId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_turn_contexts` (
                    `rootTurnId` TEXT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `agentKey` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `backend` TEXT NOT NULL,
                    `modelLabel` TEXT NOT NULL,
                    `endpointGeneration` TEXT NOT NULL,
                    `contextTokens` INTEGER NOT NULL,
                    `configuredOutputTokens` INTEGER NOT NULL,
                    `effectiveOutputTokens` INTEGER NOT NULL,
                    `systemPromptHash` TEXT NOT NULL,
                    `toolDefinitionsHash` TEXT NOT NULL,
                    `stablePrefixHash` TEXT NOT NULL,
                    `parametersHash` TEXT NOT NULL,
                    `messageCount` INTEGER NOT NULL,
                    `messagesHash` TEXT NOT NULL,
                    `previousPrefixCompatible` INTEGER,
                    `cacheMissReason` TEXT,
                    `skillIdsJson` TEXT NOT NULL,
                    `slotId` INTEGER,
                    `cacheMode` TEXT NOT NULL,
                    `messageStartSequence` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `completedAt` INTEGER,
                    PRIMARY KEY(`rootTurnId`),
                    FOREIGN KEY(`conversationId`) REFERENCES `agent_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_turn_contexts_conversationId` ON `agent_turn_contexts` (`conversationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_turn_contexts_status` ON `agent_turn_contexts` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_turn_contexts_createdAt` ON `agent_turn_contexts` (`createdAt`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_skills` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT NOT NULL,
                    `version` TEXT,
                    `license` TEXT,
                    `sourceType` TEXT NOT NULL,
                    `sourceUri` TEXT,
                    `installPath` TEXT NOT NULL,
                    `manifestJson` TEXT NOT NULL,
                    `contentHash` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `installedAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_skills_name` ON `agent_skills` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_skills_sourceType` ON `agent_skills` (`sourceType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_skills_enabled` ON `agent_skills` (`enabled`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_skill_assignments` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `skillId` TEXT NOT NULL,
                    `conversationId` INTEGER,
                    `agentKey` TEXT NOT NULL,
                    `permission` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    FOREIGN KEY(`skillId`) REFERENCES `agent_skills`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_skill_assignments_skillId` ON `agent_skill_assignments` (`skillId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_skill_assignments_conversationId` ON `agent_skill_assignments` (`conversationId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_skill_assignments_skillId_conversationId_agentKey` ON `agent_skill_assignments` (`skillId`, `conversationId`, `agentKey`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_pending_questions` (
                    `id` TEXT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `rootTurnId` TEXT NOT NULL,
                        `agentSessionId` TEXT NOT NULL,
                        `toolCallId` TEXT NOT NULL,
                        `specificationJson` TEXT NOT NULL,
                    `answerJson` TEXT,
                    `status` TEXT NOT NULL,
                    `continuationEnqueued` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `answeredAt` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`conversationId`) REFERENCES `agent_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_pending_questions_conversationId` ON `agent_pending_questions` (`conversationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_pending_questions_status` ON `agent_pending_questions` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_pending_questions_rootTurnId` ON `agent_pending_questions` (`rootTurnId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_todos` (
                    `id` TEXT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `text` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `priority` TEXT NOT NULL,
                    `position` INTEGER NOT NULL,
                    `source` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`conversationId`) REFERENCES `agent_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_todos_conversationId` ON `agent_todos` (`conversationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_todos_status` ON `agent_todos` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_todos_conversationId_position` ON `agent_todos` (`conversationId`, `position`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_compactions` (
                    `id` TEXT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `rootTurnId` TEXT,
                    `summaryText` TEXT NOT NULL,
                    `focus` TEXT,
                    `previousCompactionId` TEXT,
                    `sourceStartSequence` INTEGER NOT NULL,
                    `sourceEndSequence` INTEGER NOT NULL,
                    `tailStartSequence` INTEGER,
                    `summarizedMessageCount` INTEGER NOT NULL,
                    `retainedTailTokens` INTEGER NOT NULL,
                    `targetTailTokens` INTEGER NOT NULL,
                    `modelLabel` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`conversationId`) REFERENCES `agent_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_compactions_conversationId` ON `agent_compactions` (`conversationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_compactions_createdAt` ON `agent_compactions` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_compactions_rootTurnId` ON `agent_compactions` (`rootTurnId`)")

            DebugLog.log("[DB] Migration 101 -> 102 complete")
        }
    }

    val MIGRATION_102_103 = object : Migration(102, 103) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 102 -> 103: add saved local llama launch profiles")
            if (!columnExists(db, "llama_servers", "localLaunchProfileJson")) {
                db.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `localLaunchProfileJson` TEXT DEFAULT NULL")
            }
            DebugLog.log("[DB] Migration 102 -> 103 complete")
        }
    }

    val MIGRATION_103_104 = object : Migration(103, 104) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 103 -> 104: durable plan workflow, question drafts, and canonical saved llama launch profiles")
            if (!columnExists(db, "saved_commands", "launchProfileJson")) {
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `launchProfileJson` TEXT DEFAULT NULL")
            }
            if (!columnExists(db, "saved_commands", "launchProfileSchemaVersion")) {
                db.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `launchProfileSchemaVersion` INTEGER NOT NULL DEFAULT 1")
            }
            if (!columnExists(db, "agent_pending_questions", "draftAnswerJson")) {
                db.execSQL("ALTER TABLE `agent_pending_questions` ADD COLUMN `draftAnswerJson` TEXT NOT NULL DEFAULT '{}'")
            }
            if (!columnExists(db, "agent_pending_questions", "currentPage")) {
                db.execSQL("ALTER TABLE `agent_pending_questions` ADD COLUMN `currentPage` INTEGER NOT NULL DEFAULT 0")
            }
            if (!columnExists(db, "agent_pending_questions", "isCollapsed")) {
                db.execSQL("ALTER TABLE `agent_pending_questions` ADD COLUMN `isCollapsed` INTEGER NOT NULL DEFAULT 0")
            }
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_pending_plans` (
                    `id` TEXT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `rootTurnId` TEXT NOT NULL,
                    `agentSessionId` TEXT NOT NULL,
                    `planMessageId` TEXT NOT NULL,
                    `toolCallId` TEXT NOT NULL,
                    `originalPlan` TEXT NOT NULL,
                    `editedPlan` TEXT,
                    `summary` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `approvalOperationId` TEXT,
                    `approvedAt` INTEGER,
                    `planFileWritten` INTEGER NOT NULL,
                    `buildModeActivated` INTEGER NOT NULL,
                    `continuationEnqueued` INTEGER NOT NULL,
                    `errorMessage` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`conversationId`) REFERENCES `agent_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_pending_plans_conversationId` ON `agent_pending_plans` (`conversationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_pending_plans_state` ON `agent_pending_plans` (`state`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_pending_plans_rootTurnId` ON `agent_pending_plans` (`rootTurnId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_pending_plans_planMessageId` ON `agent_pending_plans` (`planMessageId`)")
            DebugLog.log("[DB] Migration 103 -> 104 complete")
        }
    }

    val MIGRATION_104_105 = object : Migration(104, 105) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 104 -> 105: rootless system statistics history")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `system_stats_samples` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `timestampEpochMs` INTEGER NOT NULL,
                    `deviceId` TEXT NOT NULL,
                    `snapshotJson` TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_system_stats_samples_timestampEpochMs` ON `system_stats_samples` (`timestampEpochMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_system_stats_samples_deviceId` ON `system_stats_samples` (`deviceId`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `system_stats_events` (
                    `id` TEXT NOT NULL,
                    `category` TEXT NOT NULL,
                    `phase` TEXT NOT NULL,
                    `scope` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `startedAtEpochMs` INTEGER NOT NULL,
                    `endedAtEpochMs` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_system_stats_events_startedAtEpochMs` ON `system_stats_events` (`startedAtEpochMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_system_stats_events_category` ON `system_stats_events` (`category`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_system_stats_events_status` ON `system_stats_events` (`status`)")
            DebugLog.log("[DB] Migration 104 -> 105 complete")
        }
    }

    /**
     * Durable delegated-agent workspaces and FIFO user guidance.
     *
     * Existing records intentionally retain a NULL invocationId: those rows
     * remain part of the historic orchestrator timeline after upgrade.
     */
    val MIGRATION_105_106 = object : Migration(105, 106) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 105 -> 106: durable agent invocations and pending inputs")

            addNullableInvocationId(db, "agent_messages")
            addNullableInvocationId(db, "agent_message_parts")
            addNullableInvocationId(db, "agent_turn_contexts")
            addNullableInvocationId(db, "agent_compactions")
            addNullableInvocationId(db, "agent_project_events")

            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_messages_invocationId` ON `agent_messages` (`invocationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_message_parts_invocationId` ON `agent_message_parts` (`invocationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_turn_contexts_invocationId` ON `agent_turn_contexts` (`invocationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_compactions_invocationId` ON `agent_compactions` (`invocationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_project_events_invocationId` ON `agent_project_events` (`invocationId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_invocations` (
                    `id` TEXT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `rootTurnId` TEXT NOT NULL,
                    `runtimeEpoch` INTEGER NOT NULL,
                    `parentToolCallId` TEXT NOT NULL,
                    `agentClass` TEXT NOT NULL,
                    `agentKey` TEXT NOT NULL,
                    `requestedName` TEXT NOT NULL,
                    `baseNameKey` TEXT NOT NULL,
                    `occurrence` INTEGER NOT NULL,
                    `resolvedName` TEXT NOT NULL,
                    `resolvedNameKey` TEXT NOT NULL,
                    `sessionId` TEXT,
                    `task` TEXT NOT NULL,
                    `context` TEXT,
                    `status` TEXT NOT NULL,
                    `resultSummary` TEXT,
                    `errorClass` TEXT,
                    `errorMessage` TEXT,
                    `backend` TEXT,
                    `modelLabel` TEXT,
                    `serverPhase` TEXT,
                    `contextSize` INTEGER,
                    `rawEstimatedTokens` INTEGER,
                    `packedEstimatedTokens` INTEGER,
                    `actualPromptTokens` INTEGER,
                    `actualCompletionTokens` INTEGER,
                    `contextPercent` INTEGER,
                    `compactionCount` INTEGER NOT NULL,
                    `startedAt` INTEGER NOT NULL,
                    `endedAt` INTEGER,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`conversationId`) REFERENCES `agent_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_invocations_conversationId` ON `agent_invocations` (`conversationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_invocations_status` ON `agent_invocations` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_invocations_startedAt` ON `agent_invocations` (`startedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_invocations_parentToolCallId` ON `agent_invocations` (`parentToolCallId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_invocations_conversationId_resolvedNameKey` ON `agent_invocations` (`conversationId`, `resolvedNameKey`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_pending_inputs` (
                    `id` TEXT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `targetInvocationId` TEXT,
                    `batchId` TEXT,
                    `kind` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `imagePath` TEXT,
                    `status` TEXT NOT NULL,
                    `sequenceNumber` INTEGER NOT NULL,
                    `boundaryToolCallId` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `deliveredAt` INTEGER,
                    `cancelledAt` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`conversationId`) REFERENCES `agent_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_pending_inputs_conversationId` ON `agent_pending_inputs` (`conversationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_pending_inputs_targetInvocationId` ON `agent_pending_inputs` (`targetInvocationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_pending_inputs_status` ON `agent_pending_inputs` (`status`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_pending_inputs_conversationId_sequenceNumber` ON `agent_pending_inputs` (`conversationId`, `sequenceNumber`)")

            DebugLog.log("[DB] Migration 105 -> 106 complete")
        }
    }

    /**
     * Canonical project control state, structured plans, durable work reports,
     * and transactional TODO ownership.
     */
    val MIGRATION_106_107 = object : Migration(106, 107) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log(
                "[DB] Running migration 106 -> 107: Agent control plane"
            )

            db.execSQL(
                "ALTER TABLE `agent_todos` ADD COLUMN " +
                    "`planVersionId` TEXT"
            )
            db.execSQL(
                "ALTER TABLE `agent_todos` ADD COLUMN " +
                    "`planStepId` TEXT"
            )
            db.execSQL(
                "ALTER TABLE `agent_todos` ADD COLUMN " +
                    "`phaseId` TEXT"
            )
            db.execSQL(
                "ALTER TABLE `agent_todos` ADD COLUMN " +
                    "`ownerRole` TEXT"
            )
            db.execSQL(
                "ALTER TABLE `agent_todos` ADD COLUMN " +
                    "`assignedInvocationId` TEXT"
            )
            db.execSQL(
                "ALTER TABLE `agent_todos` ADD COLUMN " +
                    "`dependenciesJson` TEXT NOT NULL DEFAULT '[]'"
            )
            db.execSQL(
                "ALTER TABLE `agent_todos` ADD COLUMN " +
                    "`acceptanceCriteriaJson` TEXT NOT NULL DEFAULT '[]'"
            )
            db.execSQL(
                "ALTER TABLE `agent_todos` ADD COLUMN " +
                    "`evidenceJson` TEXT NOT NULL DEFAULT '[]'"
            )
            db.execSQL(
                "ALTER TABLE `agent_todos` ADD COLUMN " +
                    "`attemptCount` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE `agent_todos` ADD COLUMN " +
                    "`blockReason` TEXT"
            )
            db.execSQL(
                "ALTER TABLE `agent_todos` ADD COLUMN " +
                    "`resultSummary` TEXT"
            )
            db.execSQL(
                "ALTER TABLE `agent_todos` ADD COLUMN " +
                    "`completedAt` INTEGER"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_todos_planVersionId` ON " +
                    "`agent_todos` (`planVersionId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_todos_assignedInvocationId` ON " +
                    "`agent_todos` (`assignedInvocationId`)"
            )

            db.execSQL(
                "ALTER TABLE `agent_invocations` ADD COLUMN " +
                    "`todoId` TEXT"
            )
            db.execSQL(
                "ALTER TABLE `agent_invocations` ADD COLUMN " +
                    "`workReportId` TEXT"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_invocations_todoId` ON " +
                    "`agent_invocations` (`todoId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_invocations_workReportId` ON " +
                    "`agent_invocations` (`workReportId`)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_project_states` (
                    `conversationId` INTEGER NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `mode` TEXT NOT NULL,
                    `currentGoal` TEXT NOT NULL,
                    `activePlanVersionId` TEXT,
                    `currentPhaseId` TEXT,
                    `currentTodoId` TEXT,
                    `semanticEventCount` INTEGER NOT NULL,
                    `lastSemanticEvent` TEXT,
                    `lastCompactedRevision` INTEGER,
                    `lastCompactionSemanticEventCount` INTEGER NOT NULL,
                    `lastCompactionKey` TEXT,
                    `lastCompactionStatus` TEXT,
                    `lastCompactionPreTokens` INTEGER,
                    `lastCompactionPostTokens` INTEGER,
                    `lastCompactionSavedTokens` INTEGER,
                    `lastCompactionSaturationReason` TEXT,
                    `lastCompactionAt` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`conversationId`),
                    FOREIGN KEY(`conversationId`)
                        REFERENCES `agent_conversations`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_project_states_mode` ON " +
                    "`agent_project_states` (`mode`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_project_states_updatedAt` ON " +
                    "`agent_project_states` (`updatedAt`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_project_states_activePlanVersionId` ON " +
                    "`agent_project_states` (`activePlanVersionId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_project_states_currentTodoId` ON " +
                    "`agent_project_states` (`currentTodoId`)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_plan_versions` (
                    `id` TEXT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `sourcePendingPlanId` TEXT,
                    `versionNumber` INTEGER NOT NULL,
                    `summary` TEXT NOT NULL,
                    `planMarkdown` TEXT NOT NULL,
                    `structuredJson` TEXT NOT NULL,
                    `planHash` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `approvedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`conversationId`)
                        REFERENCES `agent_conversations`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_plan_versions_conversationId` ON " +
                    "`agent_plan_versions` (`conversationId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_plan_versions_status` ON " +
                    "`agent_plan_versions` (`status`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_plan_versions_approvedAt` ON " +
                    "`agent_plan_versions` (`approvedAt`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_agent_plan_versions_conversationId_versionNumber` " +
                    "ON `agent_plan_versions` " +
                    "(`conversationId`, `versionNumber`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_agent_plan_versions_conversationId_planHash` " +
                    "ON `agent_plan_versions` " +
                    "(`conversationId`, `planHash`)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_work_reports` (
                    `id` TEXT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `invocationId` TEXT NOT NULL,
                    `todoId` TEXT,
                    `agentRole` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `summary` TEXT NOT NULL,
                    `structuredJson` TEXT NOT NULL,
                    `evidenceJson` TEXT NOT NULL,
                    `changedFilesJson` TEXT NOT NULL,
                    `risksJson` TEXT NOT NULL,
                    `recommendationsJson` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`conversationId`)
                        REFERENCES `agent_conversations`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_work_reports_conversationId` ON " +
                    "`agent_work_reports` (`conversationId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_agent_work_reports_invocationId` ON " +
                    "`agent_work_reports` (`invocationId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_work_reports_todoId` ON " +
                    "`agent_work_reports` (`todoId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_work_reports_agentRole` ON " +
                    "`agent_work_reports` (`agentRole`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_work_reports_status` ON " +
                    "`agent_work_reports` (`status`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_agent_work_reports_createdAt` ON " +
                    "`agent_work_reports` (`createdAt`)"
            )

            db.execSQL(
                """
                INSERT OR IGNORE INTO `agent_project_states` (
                    `conversationId`, `revision`, `mode`, `currentGoal`,
                    `activePlanVersionId`, `currentPhaseId`, `currentTodoId`,
                    `semanticEventCount`, `lastSemanticEvent`,
                    `lastCompactedRevision`,
                    `lastCompactionSemanticEventCount`,
                    `lastCompactionKey`, `lastCompactionStatus`,
                    `lastCompactionPreTokens`, `lastCompactionPostTokens`,
                    `lastCompactionSavedTokens`,
                    `lastCompactionSaturationReason`,
                    `lastCompactionAt`, `createdAt`, `updatedAt`
                )
                SELECT
                    `id`, 0,
                    CASE WHEN `planningModeEnabled` = 1
                         THEN 'PLAN' ELSE 'BUILD' END,
                    COALESCE(`lastTask`, ''),
                    NULL, NULL, NULL, 0, 'migration_106_107',
                    NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                    `createdAt`, `updatedAt`
                FROM `agent_conversations`
                """.trimIndent()
            )

            db.execSQL(
                """
                UPDATE `agent_todos`
                SET `status` = CASE UPPER(`status`)
                    WHEN 'DONE' THEN 'COMPLETED'
                    WHEN 'SUCCESS' THEN 'COMPLETED'
                    WHEN 'VERIFIED' THEN 'COMPLETED'
                    WHEN 'RUNNING' THEN 'IN_PROGRESS'
                    WHEN 'FAILED' THEN 'NEEDS_FIX'
                    ELSE UPPER(`status`)
                END,
                `planStepId` = COALESCE(`planStepId`, `id`),
                `phaseId` = COALESCE(`phaseId`, 'legacy'),
                `source` = CASE
                    WHEN `source` IS NULL OR `source` = ''
                    THEN 'LEGACY'
                    ELSE `source`
                END
                """.trimIndent()
            )

            DebugLog.log("[DB] Migration 106 -> 107 complete")
        }
    }

    /**
     * Adds independently managed llama.cpp server cards, per-agent runtime routing,
     * and ordered multi-LoRA persistence for distributed image/video workflows.
     */
    val MIGRATION_107_108 = object : Migration(107, 108) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 107 -> 108: managed runtimes and multi-LoRA")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `llama_server_cards` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `savedCommandId` INTEGER NOT NULL,
                    `presetNameSnapshot` TEXT NOT NULL,
                    `port` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_llama_server_cards_savedCommandId` " +
                    "ON `llama_server_cards` (`savedCommandId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_llama_server_cards_updatedAt` " +
                    "ON `llama_server_cards` (`updatedAt`)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_runtime_profiles` (
                    `agentKey` TEXT NOT NULL,
                    `backend` TEXT NOT NULL,
                    `model` TEXT,
                    `managedLlamaServerId` INTEGER,
                    `liteRtModelId` INTEGER,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`agentKey`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_runtime_profiles_backend` " +
                    "ON `agent_runtime_profiles` (`backend`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_runtime_profiles_updatedAt` " +
                    "ON `agent_runtime_profiles` (`updatedAt`)"
            )

            db.execSQL(
                "ALTER TABLE `sd_distributed_master_settings` ADD COLUMN " +
                    "`imageLorasJson` TEXT NOT NULL DEFAULT '[]'"
            )
            db.execSQL(
                "ALTER TABLE `sd_distributed_master_settings` ADD COLUMN " +
                    "`videoLorasJson` TEXT NOT NULL DEFAULT '[]'"
            )
            db.execSQL(
                "ALTER TABLE `sd_distributed_master_settings` ADD COLUMN " +
                    "`videoHighNoiseLorasJson` TEXT NOT NULL DEFAULT '[]'"
            )
            db.execSQL(
                "ALTER TABLE `sd_distributed_master_settings` ADD COLUMN " +
                    "`videoLoraApplyMode` TEXT NOT NULL DEFAULT ''"
            )
            // Preserve a previously configured single image LoRA as an ordered one-item list.
            db.execSQL(
                """
                UPDATE `sd_distributed_master_settings`
                SET `imageLorasJson` = '[{' ||
                    '"path":"' || REPLACE(REPLACE(`imageLoraPath`, '\\', '\\\\'), '"', '\\"') || '",' ||
                    '"strength":' ||
                        CASE WHEN TRIM(`loraStrength`) = '' THEN '1.0' ELSE `loraStrength` END || ',' ||
                    '"enabled":true,"highNoiseOnly":false}]'
                WHERE `imageLoraEnabled` = 1 AND TRIM(`imageLoraPath`) != ''
                """.trimIndent()
            )

            DebugLog.log("[DB] Migration 107 -> 108 complete")
        }
    }

    /** Adds reusable named remote endpoints and per-agent endpoint references. */
    val MIGRATION_108_109 = object : Migration(108, 109) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 108 -> 109: named agent endpoints")
            db.execSQL(
                "ALTER TABLE `agent_runtime_profiles` ADD COLUMN `endpointConfigId` INTEGER"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_runtime_endpoint_configs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `backend` TEXT NOT NULL,
                    `baseUrl` TEXT NOT NULL,
                    `defaultModel` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_runtime_endpoint_configs_name` " +
                    "ON `agent_runtime_endpoint_configs` (`name`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_runtime_endpoint_configs_backend` " +
                    "ON `agent_runtime_endpoint_configs` (`backend`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_runtime_endpoint_configs_updatedAt` " +
                    "ON `agent_runtime_endpoint_configs` (`updatedAt`)"
            )
            DebugLog.log("[DB] Migration 108 -> 109 complete")
        }
    }

    val MIGRATION_109_110 = object : Migration(109, 110) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 109 -> 110: wear-startable server card")
            db.execSQL(
                "ALTER TABLE `llama_server_cards` " +
                    "ADD COLUMN `allowWearStart` INTEGER NOT NULL DEFAULT 0"
            )
            DebugLog.log("[DB] Migration 109 -> 110 complete")
        }
    }

    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_29_30,
        MIGRATION_30_31,
        MIGRATION_31_32,
        MIGRATION_32_33,
        MIGRATION_33_34,
        MIGRATION_34_35,
        MIGRATION_35_36,
        MIGRATION_36_37,
        MIGRATION_37_38,
        MIGRATION_38_39,
        MIGRATION_39_40,
        MIGRATION_40_41,
        MIGRATION_41_42,
        MIGRATION_42_43,
        MIGRATION_43_44,
        MIGRATION_44_45,
        MIGRATION_45_46,
        MIGRATION_46_47,
        MIGRATION_47_48,
        MIGRATION_48_49,
        MIGRATION_49_50,
        MIGRATION_50_51,
        MIGRATION_51_52,
        MIGRATION_52_53,
        MIGRATION_53_54,
        MIGRATION_54_55,
        MIGRATION_55_56,
        MIGRATION_56_57,
        MIGRATION_57_58,
        MIGRATION_58_59,
        MIGRATION_59_60,
        MIGRATION_60_61,
        MIGRATION_61_62,
        MIGRATION_62_63,
        MIGRATION_63_64,
        MIGRATION_64_65,
        MIGRATION_65_66,
        MIGRATION_66_67,
        MIGRATION_67_68,
        MIGRATION_68_69,
        MIGRATION_69_70,
        MIGRATION_70_71,
        MIGRATION_71_72,
        MIGRATION_72_73,
        MIGRATION_73_74,
        MIGRATION_74_75,
        MIGRATION_75_76,
        MIGRATION_76_77,
        MIGRATION_77_78,
        MIGRATION_78_79,
        MIGRATION_79_80,
        MIGRATION_80_81,
        MIGRATION_81_82,
        MIGRATION_82_83,
        MIGRATION_83_84,
        MIGRATION_84_85,
        MIGRATION_85_86,
        MIGRATION_86_87,
        MIGRATION_87_88,
        MIGRATION_88_89,
        MIGRATION_89_90,
        MIGRATION_90_91,
        MIGRATION_91_92,
        MIGRATION_92_93,
        MIGRATION_93_94,
        MIGRATION_94_95,
        MIGRATION_95_96,
        MIGRATION_96_97,
        MIGRATION_97_98,
        MIGRATION_98_99,
        MIGRATION_99_100,
        MIGRATION_100_101,
        MIGRATION_101_102,
        MIGRATION_102_103,
        MIGRATION_103_104,
        MIGRATION_104_105,
        MIGRATION_105_106,
        MIGRATION_106_107,
        MIGRATION_107_108,
        MIGRATION_108_109,
        MIGRATION_109_110
    )
    /**
     * Check if a column exists in a table.
     * Useful for conditional migrations.
     */
    fun columnExists(database: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
        val cursor = database.query("PRAGMA table_info($tableName)")
        val nameIndex = cursor.getColumnIndex("name")
        
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == columnName) {
                cursor.close()
                return true
            }
        }
        cursor.close()
        return false
    }

    private fun addNullableInvocationId(database: SupportSQLiteDatabase, tableName: String) {
        if (!columnExists(database, tableName, "invocationId")) {
            database.execSQL("ALTER TABLE `$tableName` ADD COLUMN `invocationId` TEXT DEFAULT NULL")
        }
    }
    
    /**
     * Check if a table exists in the database.
     */
    fun tableExists(database: SupportSQLiteDatabase, tableName: String): Boolean {
        val cursor = database.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        )
        val exists = cursor.count > 0
        cursor.close()
        return exists
    }
}
