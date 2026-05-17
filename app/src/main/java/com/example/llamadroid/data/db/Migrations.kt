package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        MIGRATION_64_65
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
