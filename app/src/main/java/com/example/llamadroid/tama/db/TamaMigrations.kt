package com.example.llamadroid.tama.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.llamadroid.util.DebugLog

/**
 * Database migrations for TamaDatabase.
 * 
 * IMPORTANT: When changing the Tama database schema:
 * 1. Increment the version number in TamaDatabase
 * 2. Add a new migration object here
 * 3. Add the migration to ALL_MIGRATIONS list
 * 4. Test the migration thoroughly before release
 */
object TamaMigrations {
    
    /**
     * Versions where destructive migration is allowed.
     * These are early development versions before the migration system was added.
     * From v13 onwards, proper migrations should be used.
     */
    val DESTRUCTIVE_FALLBACK_VERSIONS: IntArray = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
    )
    
    // ========== MIGRATIONS ==========
    
    // Migration from v13 to v14: Add adventure tables, remove RPG stats columns
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 13 -> 14: Adventure system")
            
            // Create adventure_sessions table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS adventure_sessions (
                    id TEXT PRIMARY KEY NOT NULL,
                    petId TEXT NOT NULL,
                    dungeonType TEXT NOT NULL,
                    schematicJson TEXT NOT NULL,
                    currentStage INTEGER NOT NULL,
                    isCompleted INTEGER NOT NULL,
                    cumulativeSummary TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    lastPlayedAt INTEGER NOT NULL
                )
            """)
            
            // Create adventure_stages table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS adventure_stages (
                    id TEXT PRIMARY KEY NOT NULL,
                    sessionId TEXT NOT NULL,
                    stageNumber INTEGER NOT NULL,
                    storyContent TEXT NOT NULL,
                    userResponse TEXT,
                    stageSummary TEXT,
                    timestamp INTEGER NOT NULL
                )
            """)
            
            // Create dungeon_progress table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS dungeon_progress (
                    petId TEXT PRIMARY KEY NOT NULL,
                    completedDungeonCount INTEGER NOT NULL DEFAULT 0,
                    lastCompletedDungeonType TEXT
                )
            """)
            
            // Note: RPG columns (level, experience, strength, etc.) are left in the table
            // for backwards compatibility but ignored by the app. They can be removed
            // in a future migration with table recreation.
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 14 -> 15: Tama artworks")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tama_artworks (
                    id TEXT PRIMARY KEY NOT NULL,
                    petId TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    status TEXT NOT NULL,
                    title TEXT NOT NULL,
                    prompt TEXT NOT NULL,
                    negativePrompt TEXT NOT NULL,
                    modelFilename TEXT NOT NULL,
                    modelLabel TEXT NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    steps INTEGER NOT NULL,
                    cfgScale REAL NOT NULL,
                    seed INTEGER,
                    sourceActivity TEXT,
                    filePath TEXT,
                    errorMessage TEXT,
                    createdAt INTEGER NOT NULL,
                    startedAt INTEGER,
                    completedAt INTEGER
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 15 -> 16: Tama room ownership")
            db.execSQL(
                """
                ALTER TABLE tama_pets ADD COLUMN homeRoomId TEXT NOT NULL DEFAULT 'principal_room'
                """.trimIndent()
            )
        }
    }

    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 16 -> 17: Dream albums and room decor")
            db.execSQL(
                """
                ALTER TABLE tama_pets ADD COLUMN leftDecorationId TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE tama_pets ADD COLUMN rightDecorationId TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE tama_pets ADD COLUMN currentWorkJobId TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE tama_pets ADD COLUMN lastDailyDreamDate TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE tama_pets ADD COLUMN pendingDreamAlbumId TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE tama_artworks ADD COLUMN albumId TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE tama_artworks ADD COLUMN albumIndex INTEGER NOT NULL DEFAULT 0
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE tama_artworks ADD COLUMN albumDate TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE tama_artworks ADD COLUMN albumSummary TEXT
                """.trimIndent()
            )
        }
    }

    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 17 -> 18: Tama chat audio messages")
            db.execSQL(
                """
                ALTER TABLE tama_chat_messages ADD COLUMN audioPath TEXT
                """.trimIndent()
            )
        }
    }

    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 18 -> 19: Tama poop cycle")
            db.execSQL(
                """
                ALTER TABLE tama_pets ADD COLUMN nextPoopAt INTEGER
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE tama_pets ADD COLUMN poopCreatedAt INTEGER
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE tama_pets ADD COLUMN lastPoopMiscareAt INTEGER
                """.trimIndent()
            )
        }
    }

    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 19 -> 20: Tama poop stacks and chat images")
            db.execSQL(
                """
                ALTER TABLE tama_pets ADD COLUMN poopCount INTEGER NOT NULL DEFAULT 0
                """.trimIndent()
            )
            if (!columnExists(db, "tama_chat_messages", "imagePath")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_chat_messages ADD COLUMN imagePath TEXT
                    """.trimIndent()
                )
            }
        }
    }

    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 20 -> 21: Tama structured memory and chat transcription state")
            if (!columnExists(db, "tama_chat_messages", "transcriptionStatus")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_chat_messages ADD COLUMN transcriptionStatus TEXT
                    """.trimIndent()
                )
            }
            if (!columnExists(db, "tama_chat_messages", "transcribedText")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_chat_messages ADD COLUMN transcribedText TEXT
                    """.trimIndent()
                )
            }
            if (!columnExists(db, "tama_chat_messages", "transcriptionError")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_chat_messages ADD COLUMN transcriptionError TEXT
                    """.trimIndent()
                )
            }
            if (!columnExists(db, "tama_summaries", "shortTermSummary")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_summaries ADD COLUMN shortTermSummary TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
            }
            if (!columnExists(db, "tama_summaries", "longTermSummary")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_summaries ADD COLUMN longTermSummary TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
            }
            if (!columnExists(db, "tama_summaries", "retrievalNotesJson")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_summaries ADD COLUMN retrievalNotesJson TEXT NOT NULL DEFAULT '[]'
                    """.trimIndent()
                )
            }
        }
    }

    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 21 -> 22: Tama audio duration metadata")
            if (!columnExists(db, "tama_chat_messages", "audioDurationMs")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_chat_messages ADD COLUMN audioDurationMs INTEGER
                    """.trimIndent()
                )
            }
        }
    }

    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 22 -> 23: Tama park encounter persistence")
            if (!columnExists(db, "tama_pets", "currentParkEncounterJson")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_pets ADD COLUMN currentParkEncounterJson TEXT
                    """.trimIndent()
                )
            }
            if (!columnExists(db, "tama_pets", "lastRecyclerEncounterDate")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_pets ADD COLUMN lastRecyclerEncounterDate TEXT
                    """.trimIndent()
                )
            }
        }
    }

    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 23 -> 24: Farm upgrade slot-capacity rules")
            // No schema change is required here. Level 1 now intentionally maps to a single
            // composter slot and further capacity comes from upgrades, so we leave existing
            // levels untouched and let runtime normalization rebuild slot JSON as needed.
        }
    }

    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 24 -> 25: Tama alchemist timers and ambient NPC state")
            if (!columnExists(db, "tama_pets", "stageProgressStartTime")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_pets ADD COLUMN stageProgressStartTime INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
            }
            if (!columnExists(db, "tama_pets", "growthLocked")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_pets ADD COLUMN growthLocked INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
            }
            if (!columnExists(db, "tama_pets", "currentAmbientNpcJson")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_pets ADD COLUMN currentAmbientNpcJson TEXT
                    """.trimIndent()
                )
            }
            db.execSQL(
                """
                UPDATE tama_pets
                SET stageProgressStartTime = CASE stage
                    WHEN 'EGG' THEN birthTimestamp
                    WHEN 'BABY' THEN birthTimestamp + 60000
                    WHEN 'CHILD' THEN birthTimestamp + 3600000
                    WHEN 'TEEN' THEN birthTimestamp + 90000000
                    WHEN 'ADULT' THEN birthTimestamp + 176400000
                    WHEN 'SENIOR' THEN birthTimestamp + 262800000
                    ELSE birthTimestamp
                END
                WHERE stageProgressStartTime = 0
                """.trimIndent()
            )
        }
    }

    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 25 -> 26: Adventure stage image metadata")
            if (!columnExists(db, "adventure_stages", "imagePath")) {
                db.execSQL(
                    """
                    ALTER TABLE adventure_stages ADD COLUMN imagePath TEXT
                    """.trimIndent()
                )
            }
            if (!columnExists(db, "adventure_stages", "imagePrompt")) {
                db.execSQL(
                    """
                    ALTER TABLE adventure_stages ADD COLUMN imagePrompt TEXT
                    """.trimIndent()
                )
            }
            if (!columnExists(db, "adventure_stages", "imageNegativePrompt")) {
                db.execSQL(
                    """
                    ALTER TABLE adventure_stages ADD COLUMN imageNegativePrompt TEXT
                    """.trimIndent()
                )
            }
        }
    }

    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 26 -> 27: Growth lock pause timestamp")
            if (!columnExists(db, "tama_pets", "growthLockStartedAt")) {
                db.execSQL(
                    """
                    ALTER TABLE tama_pets ADD COLUMN growthLockStartedAt INTEGER
                    """.trimIndent()
                )
            }
            db.execSQL(
                """
                UPDATE tama_pets
                SET growthLockStartedAt = CASE
                    WHEN growthLocked = 1 AND growthLockStartedAt IS NULL THEN lastDecayTime
                    ELSE growthLockStartedAt
                END
                """.trimIndent()
            )
        }
    }

    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 27 -> 28: Park quest board persistence")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tama_quests (
                    id TEXT PRIMARY KEY NOT NULL,
                    petId TEXT NOT NULL,
                    status TEXT NOT NULL,
                    generatedDateKey TEXT NOT NULL,
                    acceptedAt INTEGER,
                    expiresAt INTEGER,
                    completedAt INTEGER,
                    npcId TEXT NOT NULL,
                    requestsJson TEXT NOT NULL,
                    rewardCoins INTEGER NOT NULL,
                    summaryJson TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 28 -> 29: Tama deep dream run persistence")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tama_deep_dream_runs (
                    id TEXT PRIMARY KEY NOT NULL,
                    petId TEXT NOT NULL,
                    signature TEXT NOT NULL,
                    dreamDate TEXT NOT NULL,
                    status TEXT NOT NULL,
                    stage TEXT NOT NULL,
                    albumId TEXT,
                    ownsLocalLlama INTEGER NOT NULL,
                    startedAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    lastHeartbeatAt INTEGER NOT NULL,
                    errorMessage TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS index_tama_deep_dream_runs_petId_signature
                ON tama_deep_dream_runs(petId, signature)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_tama_deep_dream_runs_status
                ON tama_deep_dream_runs(status)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_tama_deep_dream_runs_albumId
                ON tama_deep_dream_runs(albumId)
                """.trimIndent()
            )
        }
    }

    val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 29 -> 30: Farm livestock persistence")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tama_farm_livestock (
                    petId TEXT NOT NULL,
                    type TEXT NOT NULL,
                    slotsJson TEXT NOT NULL,
                    PRIMARY KEY(petId, type)
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 30 -> 31: Overnight awake tracking")
            db.execSQL(
                """
                ALTER TABLE tama_pets ADD COLUMN overnightAwakeDateKey TEXT
                """.trimIndent()
            )
            db.execSQL(
                """
                ALTER TABLE tama_pets ADD COLUMN overnightAwakeAccumulatedMs INTEGER NOT NULL DEFAULT 0
                """.trimIndent()
            )
        }
    }

    val MIGRATION_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 31 -> 32: Tama study Pomodoro sessions")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tama_study_labels (
                    id TEXT PRIMARY KEY NOT NULL,
                    petId TEXT NOT NULL,
                    name TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    lastUsedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS index_tama_study_labels_petId_name
                ON tama_study_labels(petId, name)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_tama_study_labels_petId_lastUsedAt
                ON tama_study_labels(petId, lastUsedAt)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tama_study_sessions (
                    id TEXT PRIMARY KEY NOT NULL,
                    petId TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    status TEXT NOT NULL,
                    labelIdsJson TEXT NOT NULL,
                    labelNamesSnapshotJson TEXT NOT NULL,
                    focusMinutes INTEGER NOT NULL,
                    shortBreakMinutes INTEGER NOT NULL,
                    longBreakMinutes INTEGER NOT NULL,
                    roundsPlanned INTEGER NOT NULL,
                    currentRound INTEGER NOT NULL,
                    currentPhase TEXT NOT NULL,
                    phaseStartedAt INTEGER,
                    phaseEndsAt INTEGER,
                    focusAccumulatedMs INTEGER NOT NULL,
                    restAccumulatedMs INTEGER NOT NULL,
                    educationAwarded REAL NOT NULL,
                    startedAt INTEGER NOT NULL,
                    completedAt INTEGER,
                    stoppedAt INTEGER,
                    lastUpdatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_tama_study_sessions_petId_status
                ON tama_study_sessions(petId, status)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_tama_study_sessions_petId_startedAt
                ON tama_study_sessions(petId, startedAt)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_tama_study_sessions_petId_completedAt
                ON tama_study_sessions(petId, completedAt)
                """.trimIndent()
            )
        }
    }

    val MIGRATION_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 32 -> 33: Tama quest checklist")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tama_quest_checklist_items (
                    id TEXT PRIMARY KEY NOT NULL,
                    petId TEXT NOT NULL,
                    itemId TEXT NOT NULL,
                    quantity INTEGER NOT NULL,
                    checked INTEGER NOT NULL DEFAULT 0,
                    sourceQuestIdsJson TEXT NOT NULL DEFAULT '[]',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS index_tama_quest_checklist_items_petId_itemId
                ON tama_quest_checklist_items(petId, itemId)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_tama_quest_checklist_items_petId_checked
                ON tama_quest_checklist_items(petId, checked)
                """.trimIndent()
            )
        }
    }

    val MIGRATION_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 33 -> 34: Adventure Gate RPG")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS adventure_gate_profiles (
                    petId TEXT PRIMARY KEY NOT NULL,
                    level INTEGER NOT NULL DEFAULT 1,
                    xp INTEGER NOT NULL DEFAULT 0,
                    maxHp INTEGER NOT NULL DEFAULT 120,
                    maxMana INTEGER NOT NULL DEFAULT 40,
                    attack INTEGER NOT NULL DEFAULT 18,
                    magic INTEGER NOT NULL DEFAULT 14,
                    defense INTEGER NOT NULL DEFAULT 10,
                    speed INTEGER NOT NULL DEFAULT 10,
                    learnedAttackIdsJson TEXT NOT NULL DEFAULT '["paw_strike"]',
                    equippedAttackIdsJson TEXT NOT NULL DEFAULT '["paw_strike"]',
                    learnedMagicIdsJson TEXT NOT NULL DEFAULT '["spark","guard"]',
                    equippedMagicIdsJson TEXT NOT NULL DEFAULT '["spark","guard"]',
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS adventure_gate_world_progress (
                    petId TEXT NOT NULL,
                    worldId TEXT NOT NULL,
                    highestClearedPhase INTEGER NOT NULL DEFAULT 0,
                    midBossCleared INTEGER NOT NULL DEFAULT 0,
                    finalBossCleared INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(petId, worldId)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_adventure_gate_world_progress_petId
                ON adventure_gate_world_progress(petId)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS adventure_gate_battle_state (
                    petId TEXT PRIMARY KEY NOT NULL,
                    worldId TEXT NOT NULL,
                    phaseNumber INTEGER NOT NULL,
                    stateJson TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_adventure_gate_battle_state_petId_worldId_phaseNumber
                ON adventure_gate_battle_state(petId, worldId, phaseNumber)
                """.trimIndent()
            )
        }
    }

    val MIGRATION_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 34 -> 35: Adventure Gate RPG V2 profile persistence")
            if (!columnExists(db, "adventure_gate_profiles", "currentHp")) {
                db.execSQL("ALTER TABLE adventure_gate_profiles ADD COLUMN currentHp INTEGER NOT NULL DEFAULT 120")
                db.execSQL("UPDATE adventure_gate_profiles SET currentHp = maxHp")
            }
            if (!columnExists(db, "adventure_gate_profiles", "currentMana")) {
                db.execSQL("ALTER TABLE adventure_gate_profiles ADD COLUMN currentMana INTEGER NOT NULL DEFAULT 40")
                db.execSQL("UPDATE adventure_gate_profiles SET currentMana = maxMana")
            }
            if (!columnExists(db, "adventure_gate_profiles", "skillPoints")) {
                db.execSQL("ALTER TABLE adventure_gate_profiles ADD COLUMN skillPoints INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE adventure_gate_profiles SET skillPoints = CASE WHEN level > 1 THEN level - 1 ELSE 0 END")
            }
            if (!columnExists(db, "adventure_gate_profiles", "purchasedSkillIdsJson")) {
                db.execSQL(
                    """
                    ALTER TABLE adventure_gate_profiles
                    ADD COLUMN purchasedSkillIdsJson TEXT NOT NULL DEFAULT '["paw_strike","spark","guard"]'
                    """.trimIndent()
                )
            }
            if (!columnExists(db, "adventure_gate_profiles", "lastRecoveryAt")) {
                db.execSQL("ALTER TABLE adventure_gate_profiles ADD COLUMN lastRecoveryAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE adventure_gate_profiles SET lastRecoveryAt = updatedAt")
            }
            db.execSQL("UPDATE adventure_gate_profiles SET purchasedSkillIdsJson = '[\"paw_strike\",\"spark\",\"guard\"]'")
            db.execSQL("UPDATE adventure_gate_profiles SET equippedAttackIdsJson = '[\"paw_strike\"]'")
            db.execSQL("UPDATE adventure_gate_profiles SET equippedMagicIdsJson = '[\"spark\",\"guard\"]'")
        }
    }

    val MIGRATION_35_36 = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 35 -> 36: Adventure Gate equipment slots")
            if (!columnExists(db, "adventure_gate_profiles", "equippedWeaponId")) {
                db.execSQL("ALTER TABLE adventure_gate_profiles ADD COLUMN equippedWeaponId TEXT")
            }
            if (!columnExists(db, "adventure_gate_profiles", "equippedShieldId")) {
                db.execSQL("ALTER TABLE adventure_gate_profiles ADD COLUMN equippedShieldId TEXT")
            }
            if (!columnExists(db, "adventure_gate_profiles", "equippedRingId")) {
                db.execSQL("ALTER TABLE adventure_gate_profiles ADD COLUMN equippedRingId TEXT")
            }
        }
    }

    val MIGRATION_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 36 -> 37: Adventure Gate relic slot")
            if (!columnExists(db, "adventure_gate_profiles", "equippedRelicId")) {
                db.execSQL("ALTER TABLE adventure_gate_profiles ADD COLUMN equippedRelicId TEXT")
            }
            db.execSQL(
                """
                UPDATE adventure_gate_profiles
                SET equippedRelicId = CASE
                    WHEN equippedRingId LIKE 'ag_relic_%' THEN equippedRingId
                    WHEN equippedShieldId LIKE 'ag_relic_%' THEN equippedShieldId
                    WHEN equippedWeaponId LIKE 'ag_relic_%' THEN equippedWeaponId
                    ELSE equippedRelicId
                END
                """.trimIndent()
            )
            db.execSQL("UPDATE adventure_gate_profiles SET equippedRingId = NULL WHERE equippedRingId LIKE 'ag_relic_%'")
            db.execSQL("UPDATE adventure_gate_profiles SET equippedShieldId = NULL WHERE equippedShieldId LIKE 'ag_relic_%'")
            db.execSQL("UPDATE adventure_gate_profiles SET equippedWeaponId = NULL WHERE equippedWeaponId LIKE 'ag_relic_%'")
        }
    }

    val MIGRATION_37_38 = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 37 -> 38: Adventure Gate accuracy/evasion")
            if (!columnExists(db, "adventure_gate_profiles", "accuracy")) {
                db.execSQL("ALTER TABLE adventure_gate_profiles ADD COLUMN accuracy INTEGER NOT NULL DEFAULT 100")
                db.execSQL("UPDATE adventure_gate_profiles SET accuracy = 100 + (level - 1)")
            }
            if (!columnExists(db, "adventure_gate_profiles", "evasion")) {
                db.execSQL("ALTER TABLE adventure_gate_profiles ADD COLUMN evasion INTEGER NOT NULL DEFAULT 5")
                db.execSQL("UPDATE adventure_gate_profiles SET evasion = 5 + (level - 1)")
            }
        }
    }

    val MIGRATION_38_39 = object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 38 -> 39: Refresh Adventure Gate level stats")
            db.execSQL(
                """
                UPDATE adventure_gate_profiles
                SET
                    maxHp = 120 + ((level - 1) * 10) + ${adventureGateGearBonusSql(AdventureGateProfileStat.MAX_HP)},
                    maxMana = 40 + ((level - 1) * 5) + ${adventureGateGearBonusSql(AdventureGateProfileStat.MAX_MANA)} + ${adventureGateStudyManaBonusSql()},
                    attack = 18 + ((level - 1) * 2) + ${adventureGateGearBonusSql(AdventureGateProfileStat.ATTACK)},
                    magic = 14 + ((level - 1) * 2) + ${adventureGateGearBonusSql(AdventureGateProfileStat.MAGIC)} + ${adventureGateStudyMagicBonusSql()},
                    defense = 10 + ((level - 1) * 2) + ${adventureGateGearBonusSql(AdventureGateProfileStat.DEFENSE)},
                    speed = 10 + (level - 1) + ${adventureGateGearBonusSql(AdventureGateProfileStat.SPEED)},
                    accuracy = 100 + (level - 1) + ${adventureGateGearBonusSql(AdventureGateProfileStat.ACCURACY)},
                    evasion = 5 + (level - 1) + ${adventureGateGearBonusSql(AdventureGateProfileStat.EVASION)}
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE adventure_gate_profiles
                SET
                    currentHp = CASE WHEN currentHp > maxHp THEN maxHp ELSE currentHp END,
                    currentMana = CASE WHEN currentMana > maxMana THEN maxMana ELSE currentMana END
                """.trimIndent()
            )
        }
    }

    val MIGRATION_39_40 = object : Migration(39, 40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 39 -> 40: Adventure Gate Night Arena")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS adventure_gate_night_arena_runs (
                    petId TEXT PRIMARY KEY NOT NULL,
                    nightKey TEXT NOT NULL,
                    levelsJson TEXT NOT NULL,
                    clearedLevelIdsJson TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private enum class AdventureGateProfileStat {
        MAX_HP,
        MAX_MANA,
        ATTACK,
        MAGIC,
        DEFENSE,
        SPEED,
        ACCURACY,
        EVASION
    }

    private data class AdventureGateMigrationStatBonus(
        val maxHp: Int = 0,
        val maxMana: Int = 0,
        val attack: Int = 0,
        val magic: Int = 0,
        val defense: Int = 0,
        val speed: Int = 0,
        val accuracy: Int = 0,
        val evasion: Int = 0
    ) {
        fun valueFor(stat: AdventureGateProfileStat): Int = when (stat) {
            AdventureGateProfileStat.MAX_HP -> maxHp
            AdventureGateProfileStat.MAX_MANA -> maxMana
            AdventureGateProfileStat.ATTACK -> attack
            AdventureGateProfileStat.MAGIC -> magic
            AdventureGateProfileStat.DEFENSE -> defense
            AdventureGateProfileStat.SPEED -> speed
            AdventureGateProfileStat.ACCURACY -> accuracy
            AdventureGateProfileStat.EVASION -> evasion
        }
    }

    private val adventureGateMigrationGearBonuses = mapOf(
        "ag_weapon_sprout_baton" to AdventureGateMigrationStatBonus(attack = 4, accuracy = 3),
        "ag_shield_leaf_shell" to AdventureGateMigrationStatBonus(defense = 5),
        "ag_ring_dewdrop" to AdventureGateMigrationStatBonus(maxHp = 12),
        "ag_weapon_ember_hammer" to AdventureGateMigrationStatBonus(attack = 8),
        "ag_shield_brass_button" to AdventureGateMigrationStatBonus(defense = 9),
        "ag_ring_warm_gear" to AdventureGateMigrationStatBonus(maxMana = 8),
        "ag_weapon_coral_bubble_rod" to AdventureGateMigrationStatBonus(magic = 10),
        "ag_shield_pearl_shell" to AdventureGateMigrationStatBonus(defense = 13),
        "ag_ring_tide_loop" to AdventureGateMigrationStatBonus(speed = 2, evasion = 4),
        "ag_weapon_clockwork_clawblade" to AdventureGateMigrationStatBonus(attack = 14, speed = 2, accuracy = 6),
        "ag_shield_gyro_plate" to AdventureGateMigrationStatBonus(defense = 17),
        "ag_ring_second_hand" to AdventureGateMigrationStatBonus(speed = 5, evasion = 5),
        "ag_weapon_moonmoss_quillblade" to AdventureGateMigrationStatBonus(magic = 18),
        "ag_shield_bookcover_ward" to AdventureGateMigrationStatBonus(maxMana = 18, defense = 20),
        "ag_ring_bookmark" to AdventureGateMigrationStatBonus(maxMana = 16, magic = 6),
        "ag_weapon_ribbon_ice_mallet" to AdventureGateMigrationStatBonus(attack = 22),
        "ag_shield_snowbutton_bulwark" to AdventureGateMigrationStatBonus(maxHp = 40, defense = 25),
        "ag_ring_aurora" to AdventureGateMigrationStatBonus(magic = 10, speed = 4, accuracy = 4, evasion = 4),
        "ag_weapon_starfall_fang" to AdventureGateMigrationStatBonus(attack = 30, magic = 8, accuracy = 8),
        "ag_shield_eclipse_mirror" to AdventureGateMigrationStatBonus(defense = 34, evasion = 6),
        "ag_ring_dreamlight_crown" to AdventureGateMigrationStatBonus(maxHp = 55, maxMana = 35, magic = 12),
        "ag_relic_bramble_crown" to AdventureGateMigrationStatBonus(defense = 11),
        "ag_relic_moth_lantern" to AdventureGateMigrationStatBonus(magic = 8),
        "ag_relic_foreman_tongs" to AdventureGateMigrationStatBonus(attack = 16),
        "ag_relic_marionette_core" to AdventureGateMigrationStatBonus(maxMana = 22),
        "ag_relic_tide_gauntlet" to AdventureGateMigrationStatBonus(attack = 18),
        "ag_relic_leviathan_scale" to AdventureGateMigrationStatBonus(defense = 24),
        "ag_relic_roc_spring" to AdventureGateMigrationStatBonus(speed = 7, evasion = 8),
        "ag_relic_chrono_key" to AdventureGateMigrationStatBonus(maxMana = 30, speed = 5, accuracy = 6, evasion = 4),
        "ag_relic_basilisk_clip" to AdventureGateMigrationStatBonus(magic = 22, accuracy = 10),
        "ag_relic_libram_seal" to AdventureGateMigrationStatBonus(maxMana = 24, defense = 28),
        "ag_relic_nutcracker_star" to AdventureGateMigrationStatBonus(attack = 28),
        "ag_relic_aurora_ribbon" to AdventureGateMigrationStatBonus(maxHp = 60, magic = 14),
        "ag_relic_eclipse_prism" to AdventureGateMigrationStatBonus(defense = 38, evasion = 8),
        "ag_relic_regent_dream_key" to AdventureGateMigrationStatBonus(maxHp = 80, maxMana = 50, magic = 18),
        "ag_relic_nexum_heart" to AdventureGateMigrationStatBonus(maxHp = 50, maxMana = 50)
    )

    private fun adventureGateGearBonusSql(stat: AdventureGateProfileStat): String =
        listOf("equippedWeaponId", "equippedShieldId", "equippedRingId", "equippedRelicId")
            .joinToString(" + ") { column -> adventureGateGearBonusSql(column, stat) }

    private fun adventureGateGearBonusSql(columnName: String, stat: AdventureGateProfileStat): String {
        val clauses = adventureGateMigrationGearBonuses.mapNotNull { (equipmentId, bonus) ->
            bonus.valueFor(stat).takeIf { it != 0 }?.let { value -> "WHEN '$equipmentId' THEN $value" }
        }
        return if (clauses.isEmpty()) {
            "0"
        } else {
            "(CASE $columnName ${clauses.joinToString(" ")} ELSE 0 END)"
        }
    }

    private fun adventureGateStudyMagicBonusSql(): String =
        "COALESCE((SELECT CAST(educationLevel / 15 AS INTEGER) FROM tama_pets WHERE tama_pets.id = adventure_gate_profiles.petId), 0)"

    private fun adventureGateStudyManaBonusSql(): String =
        "COALESCE((SELECT CAST(educationLevel / 10 AS INTEGER) * 4 FROM tama_pets WHERE tama_pets.id = adventure_gate_profiles.petId), 0)"
    
    /**
     * All migrations that should be applied.
     * Add new migrations to this list when schema changes.
     */
    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24,
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
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
        MIGRATION_39_40
    )
    
    // ========== HELPER FUNCTIONS ==========
    
    /**
     * Check if a column exists in a table.
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
}
