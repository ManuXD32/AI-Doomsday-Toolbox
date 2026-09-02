package com.example.llamadroid.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.llamadroid.quadtrix.QuadtrixOptionKeys
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AppDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate103To104_preservesSavedCommandsAndAddsCanonicalProfile() {
        helper.createDatabase(TEST_DB, 103).apply {
            execSQL(
                """
                INSERT INTO agent_conversations (
                    id, title, projectFolder, sortOrder, planningModeEnabled,
                    resumeState, knowledgeBaseIds, workspaceBackend,
                    runtimeCapabilitiesJson, runUiMode, lastRunProfileJson,
                    createdAt, updatedAt
                ) VALUES (
                    700, 'Migration project', '/project', 0, 1,
                    'WAITING_FOR_USER', '', 'LOCAL_PROOT', '{}', 'TERMINAL', '{}',
                    1, 1
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO agent_pending_questions (
                    id, conversationId, rootTurnId, agentSessionId, toolCallId,
                    specificationJson, status, continuationEnqueued, createdAt
                ) VALUES (
                    'question-1', 700, 'root-1', 'session-1', 'call-1',
                    '{"questions":[]}', 'PENDING', 0, 1
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO saved_commands (
                    name, command, scope, modelPath, contextSize, batchSize,
                    temperature, threads, host, speculativeEnabled,
                    speculativeMode, draftMax, draftMin, draftPMin,
                    draftThreads, draftThreadsBatch, ngramModNMatch,
                    ngramModNMin, ngramModNMax, ngramSimpleSizeN,
                    ngramSimpleSizeM, ngramSimpleMinHits, ngramMapKSizeN,
                    ngramMapKSizeM, ngramMapKMinHits, ngramMapK4VSizeN,
                    ngramMapK4VSizeM, ngramMapK4VMinHits,
                    nativeToolsEnabled, customFlags, flashAttention,
                    kvCacheEnabled, kvCacheTypeK, kvCacheTypeV, kvCacheReuse,
                    masterRamMB, workersListStr, lowMemoryMode, enableVision
                ) VALUES (
                    'Phone preset', '', 'GENERAL', '/models/test.gguf', 8192, 512,
                    0.7, 4, '127.0.0.1', 0,
                    'draft-simple', 3, 0, 0.0,
                    4, 4, 24,
                    48, 64, 12,
                    48, 1, 12,
                    48, 1, 12,
                    48, 1,
                    1, '--cache-prompt', 1,
                    1, 'f16', 'f16', 0,
                    4096, '', 0, 0
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            104,
            true,
            Migrations.MIGRATION_103_104
        )

        migratedDb.query(
            "SELECT name, modelPath, launchProfileJson, launchProfileSchemaVersion FROM saved_commands WHERE name = 'Phone preset'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Phone preset", cursor.getString(0))
            assertEquals("/models/test.gguf", cursor.getString(1))
            assertNull(cursor.getString(2))
            assertEquals(1, cursor.getInt(3))
        }
        migratedDb.query(
            "SELECT draftAnswerJson, currentPage, isCollapsed FROM agent_pending_questions WHERE id = 'question-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{}", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
        }
        migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'agent_pending_plans'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
    }

    @Test
    fun migrate104To105_addsRootlessStatsSamplesAndEvents() {
        helper.createDatabase(TEST_DB, 104).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            105,
            true,
            Migrations.MIGRATION_104_105
        )

        migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('system_stats_samples', 'system_stats_events')"
        ).use { cursor ->
            val tables = mutableSetOf<String>()
            while (cursor.moveToNext()) tables += cursor.getString(0)
            assertEquals(setOf("system_stats_samples", "system_stats_events"), tables)
        }
        migratedDb.query("PRAGMA table_info(system_stats_samples)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            assertTrue(columns.containsAll(setOf("timestampEpochMs", "deviceId", "snapshotJson")))
        }
        migratedDb.query("PRAGMA table_info(system_stats_events)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            assertTrue(columns.containsAll(setOf("id", "category", "phase", "status", "startedAtEpochMs")))
        }
    }

    @Test
    fun migrate105To106_preservesOrchestratorRowsAndCreatesDelegationStorage() {
        helper.createDatabase(TEST_DB, 105).apply {
            execSQL(
                """
                INSERT INTO agent_conversations (
                    id, title, projectFolder, sortOrder, planningModeEnabled,
                    resumeState, knowledgeBaseIds, workspaceBackend,
                    runtimeCapabilitiesJson, runUiMode, lastRunProfileJson,
                    createdAt, updatedAt
                ) VALUES (900, 'Delegation migration', '/project', 0, 1,
                    'IDLE', '', 'LOCAL_SANDBOX', '{}', 'CONSOLE', '{}', 1, 1)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO agent_messages (
                    originalId, conversationId, role, content, isTerminalVisible,
                    needsApproval, isPlan, isStreaming, isDelegation, isSuspicious,
                    isOutputExpanded, timestamp, sequenceNumber
                ) VALUES ('orchestrator-message', 900, 'assistant', 'already committed',
                    0, 0, 0, 0, 0, 0, 0, 1, 1)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            106,
            true,
            Migrations.MIGRATION_105_106
        )

        migratedDb.query("SELECT invocationId FROM agent_messages WHERE originalId = 'orchestrator-message'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        migratedDb.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('agent_invocations', 'agent_pending_inputs')").use { cursor ->
            val tables = mutableSetOf<String>()
            while (cursor.moveToNext()) tables += cursor.getString(0)
            assertEquals(setOf("agent_invocations", "agent_pending_inputs"), tables)
        }
        migratedDb.query("PRAGMA table_info(agent_pending_inputs)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            assertTrue(columns.containsAll(setOf("targetInvocationId", "sequenceNumber", "status", "content")))
        }
    }

    @Test
    fun migrate40To41_preservesExistingModels() {
        helper.createDatabase(TEST_DB, 40).apply {
            execSQL(
                """
                INSERT INTO models (
                    filename,
                    path,
                    sizeBytes,
                    type,
                    repoId,
                    isDownloaded,
                    isVision,
                    mmprojPath,
                    sdCapabilities,
                    layerCount
                ) VALUES (
                    'sdxl.safetensors',
                    '/models/sdxl.safetensors',
                    123456,
                    'SD_CHECKPOINT',
                    'local-import',
                    1,
                    0,
                    NULL,
                    'txt2img,img2img',
                    32
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            41,
            true,
            Migrations.MIGRATION_40_41
        )

        migratedDb.query(
            "SELECT sdFamily, sdVariant, sdCompatProfiles, sdCapabilities FROM models WHERE filename = 'sdxl.safetensors'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull(cursor.getString(0))
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
            assertEquals("txt2img,img2img", cursor.getString(3))
        }
    }

    @Test
    fun migrate41To42_preservesExistingModels() {
        helper.createDatabase(TEST_DB, 41).apply {
            execSQL(
                """
                INSERT INTO models (
                    filename,
                    path,
                    sizeBytes,
                    type,
                    repoId,
                    isDownloaded,
                    isVision,
                    mmprojPath,
                    sdCapabilities,
                    sdFamily,
                    sdVariant,
                    sdCompatProfiles,
                    layerCount
                ) VALUES (
                    'model.onnx',
                    '/models/model.onnx',
                    987654,
                    'ONNX_IMAGE_GEN',
                    'local-import',
                    1,
                    0,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    0
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            42,
            true,
            Migrations.MIGRATION_41_42
        )

        migratedDb.query(
            "SELECT onnxCapabilities, type FROM models WHERE filename = 'model.onnx'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull(cursor.getString(0))
            assertEquals("ONNX_IMAGE_GEN", cursor.getString(1))
        }
    }

    @Test
    fun migrate42To43_preservesExistingOnnxRows() {
        helper.createDatabase(TEST_DB, 42).apply {
            execSQL(
                """
                INSERT INTO models (
                    filename,
                    path,
                    sizeBytes,
                    type,
                    repoId,
                    isDownloaded,
                    isVision,
                    mmprojPath,
                    sdCapabilities,
                    sdFamily,
                    sdVariant,
                    sdCompatProfiles,
                    onnxCapabilities,
                    layerCount
                ) VALUES (
                    'ben2.onnx',
                    '/models/ben2.onnx',
                    222,
                    'ONNX_BACKGROUND_REMOVAL',
                    'local-import',
                    1,
                    0,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    0
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            43,
            true,
            Migrations.MIGRATION_42_43
        )

        migratedDb.query(
            "SELECT onnxAssetKind, onnxPipelineFamily, onnxReferenceUri, onnxReferencePath, type FROM models WHERE filename = 'ben2.onnx'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull(cursor.getString(0))
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
            assertNull(cursor.getString(3))
            assertEquals("ONNX_BACKGROUND_REMOVAL", cursor.getString(4))
        }
    }

    @Test
    fun migrate43To44_addsAudioSupportToLlamaServers() {
        helper.createDatabase(TEST_DB, 43).apply {
            execSQL(
                """
                INSERT INTO llama_servers (
                    name,
                    host,
                    port,
                    supportsVision,
                    modelName,
                    lastUsed
                ) VALUES (
                    'local',
                    '127.0.0.1',
                    8080,
                    1,
                    'gemma-4',
                    123456789
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            44,
            true,
            Migrations.MIGRATION_43_44
        )

        migratedDb.query(
            "SELECT supportsAudio, supportsVision, name, modelName FROM llama_servers WHERE host = '127.0.0.1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals("local", cursor.getString(2))
            assertEquals("gemma-4", cursor.getString(3))
        }
    }

    @Test
    fun migrate44To45_addsMediaPathsToLlamaMessages() {
        helper.createDatabase(TEST_DB, 44).apply {
            execSQL(
                """
                INSERT INTO llama_chats (
                    title,
                    lastModified,
                    contextSize,
                    systemPrompt,
                    apiParams
                ) VALUES (
                    'Media Chat',
                    123456789,
                    8192,
                    NULL,
                    NULL
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO llama_messages (
                    chatId,
                    role,
                    content,
                    timestamp,
                    isError,
                    isTruncated,
                    promptTokens,
                    completionTokens,
                    tps,
                    generationTimeMs
                ) VALUES (
                    1,
                    'user',
                    'hello media',
                    123456790,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    0
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            45,
            true,
            Migrations.MIGRATION_44_45
        )

        migratedDb.query(
            "SELECT content, imagePath, audioPath FROM llama_messages WHERE chatId = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("hello media", cursor.getString(0))
            assertNull(cursor.getString(1))
            assertNull(cursor.getString(2))
        }
    }

    @Test
    fun migrate47To48_addsEngineAndWhisperFallbackToLlamaServers() {
        helper.createDatabase(TEST_DB, 47).apply {
            execSQL(
                """
                INSERT INTO llama_servers (
                    name,
                    host,
                    port,
                    supportsVision,
                    supportsAudio,
                    modelName,
                    lastUsed
                ) VALUES (
                    'Native Chat',
                    '192.168.1.20',
                    11434,
                    1,
                    0,
                    'gemma3:4b',
                    987654321
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            48,
            true,
            Migrations.MIGRATION_47_48
        )

        migratedDb.query(
            """
            SELECT
                name,
                host,
                port,
                engine,
                supportsVision,
                supportsAudio,
                modelName,
                whisperModelPath,
                whisperLanguage,
                lastUsed
            FROM llama_servers
            WHERE host = '192.168.1.20'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Native Chat", cursor.getString(0))
            assertEquals("192.168.1.20", cursor.getString(1))
            assertEquals(11434, cursor.getInt(2))
            assertEquals("llama-server", cursor.getString(3))
            assertEquals(1, cursor.getInt(4))
            assertEquals(0, cursor.getInt(5))
            assertEquals("gemma3:4b", cursor.getString(6))
            assertNull(cursor.getString(7))
            assertEquals("auto", cursor.getString(8))
            assertEquals(987654321L, cursor.getLong(9))
        }
    }

    @Test
    fun migrate50To51_addsNoteWhitelistAndLlamaChatFolders() {
        helper.createDatabase(TEST_DB, 50).apply {
            execSQL(
                """
                INSERT INTO notes (
                    title,
                    content,
                    type,
                    sourceFile,
                    language,
                    audioPath,
                    createdAt,
                    updatedAt
                ) VALUES (
                    'Private note',
                    'not whitelisted yet',
                    'MANUAL',
                    NULL,
                    NULL,
                    NULL,
                    1000,
                    1000
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO llama_chats (
                    title,
                    lastModified,
                    contextSize,
                    systemPrompt,
                    apiParams
                ) VALUES (
                    'Loose chat',
                    2000,
                    8192,
                    NULL,
                    NULL
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            51,
            true,
            Migrations.MIGRATION_50_51
        )

        migratedDb.query("SELECT isLlmWhitelisted FROM notes WHERE title = 'Private note'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migratedDb.query("SELECT folderId FROM llama_chats WHERE title = 'Loose chat'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        migratedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='llama_chat_folders'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("llama_chat_folders", cursor.getString(0))
        }
    }

    @Test
    fun migrate52To53_addsNativeChatPromptProfiles() {
        helper.createDatabase(TEST_DB, 52).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            53,
            true,
            Migrations.MIGRATION_52_53
        )

        migratedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='llama_chat_prompt_profiles'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("llama_chat_prompt_profiles", cursor.getString(0))
        }

        migratedDb.execSQL(
            """
            INSERT INTO llama_chat_prompt_profiles (name, content, createdAt, updatedAt)
            VALUES ('Researcher', 'Use sources carefully.', 10, 20)
            """.trimIndent()
        )
        migratedDb.query("SELECT name, content, createdAt, updatedAt FROM llama_chat_prompt_profiles").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Researcher", cursor.getString(0))
            assertEquals("Use sources carefully.", cursor.getString(1))
            assertEquals(10L, cursor.getLong(2))
            assertEquals(20L, cursor.getLong(3))
        }
    }

    @Test
    fun migrate53To54_removesTrainerTablesAndRuntimeJobs() {
        helper.createDatabase(TEST_DB, 53).apply {
            execSQL(
                """
                INSERT INTO ai_runtime_jobs (
                    jobId,
                    jobKey,
                    type,
                    status,
                    conversationId,
                    sessionId,
                    projectFolder,
                    backendIdentifier,
                    modelName,
                    payloadJson,
                    checkpointJson,
                    progressText,
                    errorMessage,
                    resumable,
                    createdAt,
                    updatedAt
                ) VALUES (
                    'trainer-job',
                    'trainer-key',
                    'TRAINER_RUN',
                    'RUNNING',
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    '{}',
                    NULL,
                    NULL,
                    NULL,
                    1,
                    10,
                    20
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO ai_runtime_jobs (
                    jobId,
                    jobKey,
                    type,
                    status,
                    conversationId,
                    sessionId,
                    projectFolder,
                    backendIdentifier,
                    modelName,
                    payloadJson,
                    checkpointJson,
                    progressText,
                    errorMessage,
                    resumable,
                    createdAt,
                    updatedAt
                ) VALUES (
                    'agent-job',
                    'agent-key',
                    'AGENT_CHAT',
                    'RUNNING',
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    '{}',
                    NULL,
                    NULL,
                    NULL,
                    1,
                    10,
                    20
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO trainer_profiles (
                    id,
                    name,
                    mode,
                    baseModelFilename,
                    datasetProjectId,
                    configJson,
                    createdAt,
                    updatedAt,
                    lastUsedAt
                ) VALUES (
                    1,
                    'Old trainer profile',
                    'ADAPTER_SFT',
                    NULL,
                    NULL,
                    '{}',
                    10,
                    20,
                    30
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO trainer_schedules (
                    id,
                    profileId,
                    enabled,
                    daysMask,
                    startMinutesOfDay,
                    endMinutesOfDay,
                    requiresCharging,
                    requiresUnmeteredNetwork,
                    requiresBatteryNotLow,
                    requiresStorageNotLow,
                    requiresDeviceIdle,
                    updatedAt
                ) VALUES (
                    1,
                    1,
                    1,
                    127,
                    0,
                    60,
                    0,
                    0,
                    0,
                    0,
                    0,
                    40
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO trainer_runs (
                    id,
                    profileId,
                    name,
                    mode,
                    status,
                    baseModelFilename,
                    datasetProjectId,
                    configJson,
                    outputDir,
                    manifestPath,
                    datasetManifestPath,
                    trainerStatePath,
                    lastCheckpointPath,
                    lastCheckpointStep,
                    workerRequestId,
                    progressFraction,
                    progressText,
                    errorMessage,
                    startedAt,
                    finishedAt,
                    createdAt,
                    updatedAt
                ) VALUES (
                    'run-1',
                    1,
                    'Old trainer run',
                    'ADAPTER_SFT',
                    'FAILED',
                    NULL,
                    NULL,
                    '{}',
                    '/tmp/trainer/run-1',
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    0.0,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    10,
                    20
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO trainer_checkpoints (
                    id,
                    runId,
                    checkpointPath,
                    globalStep,
                    metricJson,
                    createdAt
                ) VALUES (
                    1,
                    'run-1',
                    '/tmp/trainer/run-1/checkpoint.gguf',
                    1,
                    NULL,
                    50
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO trainer_artifacts (
                    id,
                    runId,
                    kind,
                    displayName,
                    filePath,
                    baseModelPath,
                    adapterPath,
                    metadataJson,
                    createdAt
                ) VALUES (
                    1,
                    'run-1',
                    'GGUF_LORA_ADAPTER',
                    'adapter.gguf',
                    '/tmp/trainer/run-1/adapter.gguf',
                    NULL,
                    NULL,
                    NULL,
                    60
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            54,
            true,
            Migrations.MIGRATION_53_54
        )

        listOf(
            "trainer_artifacts",
            "trainer_checkpoints",
            "trainer_runs",
            "trainer_schedules",
            "trainer_profiles"
        ).forEach { tableName ->
            migratedDb.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(tableName)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        migratedDb.query("SELECT COUNT(*) FROM ai_runtime_jobs WHERE type = 'TRAINER_RUN'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migratedDb.query("SELECT COUNT(*) FROM ai_runtime_jobs WHERE type = 'AGENT_CHAT'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun migrate54To55_addsOrganizerCalendarAlarmAndSettingsTables() {
        helper.createDatabase(TEST_DB, 54).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            55,
            true,
            Migrations.MIGRATION_54_55
        )

        listOf(
            "organizer_events",
            "organizer_alarms",
            "organizer_llm_settings"
        ).forEach { tableName ->
            migratedDb.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(tableName)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
        migratedDb.query("SELECT COUNT(*) FROM organizer_llm_settings").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migrate55To56_addsNativeLlamaSchedulerTables() {
        helper.createDatabase(TEST_DB, 55).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            56,
            true,
            Migrations.MIGRATION_55_56
        )

        listOf(
            "llama_scheduled_tasks",
            "llama_scheduled_task_logs"
        ).forEach { tableName ->
            migratedDb.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(tableName)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }

        migratedDb.execSQL(
            """
            INSERT INTO llama_scheduled_tasks (
                name,
                enabled,
                serverId,
                contextSize,
                systemPrompt,
                taskPrompt,
                apiParams,
                scheduleType,
                oneTimeAtMillis,
                timeOfDayMinutes,
                weekdaysMask,
                dayOfMonth,
                timezoneId,
                nextRunAtMillis,
                createdAt,
                updatedAt,
                lastRunAtMillis
            ) VALUES (
                'Tech news',
                1,
                NULL,
                8192,
                'Researcher',
                'Summarize tech news.',
                '{"toolsEnabled":true}',
                'DAILY',
                NULL,
                420,
                0,
                1,
                'UTC',
                10000,
                1000,
                1000,
                NULL
            )
            """.trimIndent()
        )
        migratedDb.execSQL(
            """
            INSERT INTO llama_scheduled_task_logs (
                taskId,
                taskName,
                scheduledAtMillis,
                startedAtMillis,
                finishedAtMillis,
                durationMs,
                status,
                serverId,
                serverName,
                serverBaseUrl,
                finalOutput,
                error,
                toolActivity,
                createdAt
            ) VALUES (
                1,
                'Tech news',
                10000,
                NULL,
                NULL,
                NULL,
                'QUEUED',
                NULL,
                NULL,
                NULL,
                '',
                NULL,
                '',
                1000
            )
            """.trimIndent()
        )

        migratedDb.query("SELECT name, scheduleType, timeOfDayMinutes FROM llama_scheduled_tasks").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Tech news", cursor.getString(0))
            assertEquals("DAILY", cursor.getString(1))
            assertEquals(420, cursor.getInt(2))
        }
        migratedDb.query("SELECT taskName, status FROM llama_scheduled_task_logs").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Tech news", cursor.getString(0))
            assertEquals("QUEUED", cursor.getString(1))
        }
    }

    @Test
    fun migrate56To57_addsBenchmarkRunMetadata() {
        helper.createDatabase(TEST_DB, 56).apply {
            execSQL(
                """
                INSERT INTO benchmark_results (
                    modelPath,
                    modelName,
                    threads,
                    promptTokensPerSecond,
                    genTokensPerSecond,
                    promptTokens,
                    genTokens,
                    timestamp
                ) VALUES (
                    '/models/test.gguf',
                    'test.gguf',
                    4,
                    12.5,
                    8.75,
                    512,
                    128,
                    123456789
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO benchmark_results (
                    modelPath,
                    modelName,
                    threads,
                    promptTokensPerSecond,
                    genTokensPerSecond,
                    promptTokens,
                    genTokens,
                    timestamp
                ) VALUES (
                    '/models/test.gguf',
                    'test.gguf',
                    8,
                    18.25,
                    11.5,
                    512,
                    128,
                    123456999
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            57,
            true,
            Migrations.MIGRATION_56_57
        )

        migratedDb.query(
            "SELECT runStartedAt, runName, timestamp FROM benchmark_results WHERE modelPath = '/models/test.gguf' ORDER BY threads ASC"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(123456789L, cursor.getLong(0))
            assertEquals("", cursor.getString(1))
            assertEquals(123456789L, cursor.getLong(2))
            assertTrue(cursor.moveToNext())
            assertEquals(123456789L, cursor.getLong(0))
            assertEquals("", cursor.getString(1))
            assertEquals(123456999L, cursor.getLong(2))
        }
    }

    @Test
    fun migrate57To58_clearsDatasetSourceTextAndPreservesChunks() {
        helper.createDatabase(TEST_DB, 57).apply {
            execSQL(
                """
                INSERT INTO dataset_projects (
                    id,
                    name,
                    createdAt,
                    backend,
                    serverUrl,
                    ollamaUrl,
                    ollamaModel,
                    ollamaNumCtx,
                    ollamaThreads,
                    ollamaMmap,
                    temperature,
                    maxTokens,
                    useCoT,
                    finalLanguage,
                    chunkSize,
                    questionsPerChunk
                ) VALUES (
                    1,
                    'Large PDF dataset',
                    123456789,
                    'llama_server',
                    'http://127.0.0.1:8080',
                    'http://127.0.0.1:11434',
                    NULL,
                    4096,
                    4,
                    0,
                    0.7,
                    512,
                    0,
                    '',
                    1000,
                    5
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO dataset_sources (
                    id,
                    projectId,
                    type,
                    uri,
                    name,
                    extractedText,
                    addedAt
                ) VALUES (
                    10,
                    1,
                    'PDF',
                    'content://dataset/large.pdf',
                    'large.pdf',
                    'very large extracted source text',
                    123456790
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO dataset_chunks (
                    id,
                    projectId,
                    sourceId,
                    chunkIndex,
                    originalText,
                    cleanedText,
                    status
                ) VALUES (
                    20,
                    1,
                    10,
                    0,
                    'chunk text stays durable',
                    NULL,
                    'PENDING'
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            58,
            true,
            Migrations.MIGRATION_57_58
        )

        migratedDb.query(
            "SELECT extractedText FROM dataset_sources WHERE id = 10"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull(cursor.getString(0))
        }
        migratedDb.query(
            "SELECT originalText FROM dataset_chunks WHERE id = 20"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("chunk text stays durable", cursor.getString(0))
        }
    }

    @Test
    fun migrate58To59_createsQuadtrixTables() {
        helper.createDatabase(TEST_DB, 58).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            59,
            true,
            Migrations.MIGRATION_58_59
        )

        migratedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='quadtrix_profiles'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        migratedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='quadtrix_runs'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        migratedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='quadtrix_metrics'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
    }

    @Test
    fun migrate59To60_addsQuadtrixQwenGgufAndStreamingColumns() {
        helper.createDatabase(TEST_DB, 59).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            60,
            true,
            Migrations.MIGRATION_59_60
        )

        listOf(
            "arch",
            "tokenizer",
            "qwenTokenizerJsonPath",
            "nKvHead",
            "headDim",
            "intermediateSize",
            "exportGgufPath",
            "saveGgufAfterTrain",
            "ggufOuttype",
            "showGgufInModels",
            "streamProgress",
            "streamPort"
        ).forEach { column ->
            migratedDb.query("PRAGMA table_info(quadtrix_profiles)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    found = found || cursor.getString(nameIndex) == column
                }
                assertTrue("Missing quadtrix_profiles.$column", found)
            }
        }
    }

    @Test
    fun migrate60To61_addsQuadtrixTokenCacheAndStreamConnectionColumns() {
        helper.createDatabase(TEST_DB, 60).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            61,
            true,
            Migrations.MIGRATION_60_61
        )

        listOf(
            "streamHost",
            "streamLanEnabled",
            "remoteStreamHost",
            "remoteStreamPort",
            "remoteStreamToken",
            "tokenCacheMode",
            "tokenCacheDir",
            "tokenizationMode",
            "tokenizeLogIntervalSec"
        ).forEach { column ->
            migratedDb.query("PRAGMA table_info(quadtrix_profiles)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    found = found || cursor.getString(nameIndex) == column
                }
                assertTrue("Missing quadtrix_profiles.$column", found)
            }
        }
    }

    @Test
    fun migrate61To62_addsQuadtrixOptionEnablementColumns() {
        helper.createDatabase(TEST_DB, 61).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            62,
            true,
            Migrations.MIGRATION_61_62
        )

        listOf(
            "distCoordinatorOnly",
            "printSystemInfo",
            "noGenerateAfterTrain",
            "enabledOptions"
        ).forEach { column ->
            migratedDb.query("PRAGMA table_info(quadtrix_profiles)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    found = found || cursor.getString(nameIndex) == column
                }
                assertTrue("Missing quadtrix_profiles.$column", found)
            }
        }

        migratedDb.query("PRAGMA table_info(quadtrix_profiles)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val defaultIndex = cursor.getColumnIndex("dflt_value")
            var defaultEnabledOptions = ""
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "enabledOptions") {
                    defaultEnabledOptions = cursor.getString(defaultIndex).trim('\'')
                }
            }
            assertEquals(QuadtrixOptionKeys.defaultCsv, defaultEnabledOptions)
        }
    }

    @Test
    fun migrate62To63_addsKnowledgeBaseTablesAndAgentScope() {
        helper.createDatabase(TEST_DB, 62).apply {
            execSQL(
                """
                INSERT INTO agent_conversations (
                    id,
                    title,
                    projectFolder,
                    lastAgentRole,
                    lastTask,
                    createdAt,
                    updatedAt
                ) VALUES (
                    1,
                    'Medicine project',
                    'medicine_project',
                    'ORCHESTRATOR',
                    NULL,
                    1000,
                    1000
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            63,
            true,
            Migrations.MIGRATION_62_63
        )

        listOf(
            "knowledge_bases" to "name",
            "knowledge_sources" to "knowledgeBaseId",
            "knowledge_sources" to "sourceRef",
            "knowledge_sources" to "enabled",
            "knowledge_sources" to "status",
            "knowledge_chunks" to "sourceId",
            "knowledge_chunks" to "chunkIndex",
            "agent_conversations" to "knowledgeBaseIds"
        ).forEach { (table, column) ->
            migratedDb.query("PRAGMA table_info($table)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    found = found || cursor.getString(nameIndex) == column
                }
                assertTrue("Missing $table.$column", found)
            }
        }

        migratedDb.query("SELECT knowledgeBaseIds FROM agent_conversations WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
        }

        migratedDb.execSQL("PRAGMA foreign_keys=ON")
        migratedDb.execSQL(
            """
            INSERT INTO knowledge_bases (
                id,
                name,
                description,
                createdAt,
                updatedAt
            ) VALUES (
                10,
                'Medicine',
                '',
                2000,
                2000
            )
            """.trimIndent()
        )
        migratedDb.execSQL(
            """
            INSERT INTO knowledge_sources (
                id,
                knowledgeBaseId,
                type,
                sourceRef,
                title,
                contentHash,
                enabled,
                status,
                errorMessage,
                embeddingModelPath,
                embeddingDim,
                chunkCount,
                createdAt,
                updatedAt,
                indexedAt
            ) VALUES (
                20,
                10,
                'file',
                'content://medicine',
                'Medicine notes',
                'hash',
                1,
                'indexed',
                NULL,
                NULL,
                0,
                1,
                2000,
                2000,
                2000
            )
            """.trimIndent()
        )
        migratedDb.execSQL(
            """
            INSERT INTO knowledge_chunks (
                id,
                knowledgeBaseId,
                sourceId,
                chunkIndex,
                text,
                startOffset,
                endOffset,
                embedding,
                embeddingNorm,
                createdAt
            ) VALUES (
                30,
                10,
                20,
                0,
                'Aspirin notes',
                0,
                13,
                NULL,
                NULL,
                2000
            )
            """.trimIndent()
        )
        migratedDb.execSQL("DELETE FROM knowledge_bases WHERE id = 10")

        migratedDb.query("SELECT COUNT(*) FROM knowledge_sources WHERE knowledgeBaseId = 10").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migratedDb.query("SELECT COUNT(*) FROM knowledge_chunks WHERE knowledgeBaseId = 10").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migrate63To64_addsKnowledgeBaseProgressFieldsAndBackfillsVectorCounts() {
        helper.createDatabase(TEST_DB, 63).apply {
            execSQL(
                """
                INSERT INTO knowledge_bases (
                    id,
                    name,
                    description,
                    createdAt,
                    updatedAt
                ) VALUES (
                    10,
                    'Medicine',
                    '',
                    2000,
                    2000
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO knowledge_sources (
                    id,
                    knowledgeBaseId,
                    type,
                    sourceRef,
                    title,
                    contentHash,
                    enabled,
                    status,
                    errorMessage,
                    embeddingModelPath,
                    embeddingDim,
                    chunkCount,
                    createdAt,
                    updatedAt,
                    indexedAt
                ) VALUES (
                    20,
                    10,
                    'file',
                    'content://medicine',
                    'Medicine notes',
                    'hash',
                    1,
                    'indexed',
                    NULL,
                    '/models/embed.gguf',
                    3,
                    2,
                    2000,
                    2000,
                    2000
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO knowledge_chunks (
                    id,
                    knowledgeBaseId,
                    sourceId,
                    chunkIndex,
                    text,
                    startOffset,
                    endOffset,
                    embedding,
                    embeddingNorm,
                    createdAt
                ) VALUES (
                    30,
                    10,
                    20,
                    0,
                    'Aspirin notes',
                    0,
                    13,
                    X'0000803F0000000000000000',
                    1.0,
                    2000
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO knowledge_chunks (
                    id,
                    knowledgeBaseId,
                    sourceId,
                    chunkIndex,
                    text,
                    startOffset,
                    endOffset,
                    embedding,
                    embeddingNorm,
                    createdAt
                ) VALUES (
                    31,
                    10,
                    20,
                    1,
                    'Raw extracted notes',
                    14,
                    33,
                    NULL,
                    NULL,
                    2000
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            64,
            true,
            Migrations.MIGRATION_63_64
        )

        listOf(
            "embeddingBackend",
            "embeddingConfigHash",
            "embeddedChunkCount",
            "processingStage",
            "progressTotal",
            "progressDone",
            "progressUpdatedAt"
        ).forEach { column ->
            migratedDb.query("PRAGMA table_info(knowledge_sources)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    found = found || cursor.getString(nameIndex) == column
                }
                assertTrue("Missing knowledge_sources.$column", found)
            }
        }

        migratedDb.query(
            "SELECT embeddedChunkCount, progressTotal, progressDone, processingStage FROM knowledge_sources WHERE id = 20"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(2, cursor.getInt(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals("indexed", cursor.getString(3))
        }
    }

    @Test
    fun migrate66To67_addsLiveTranslatorTables() {
        helper.createDatabase(TEST_DB, 66).close()

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            67,
            true,
            Migrations.MIGRATION_66_67
        )

        listOf(
            "live_translator_templates",
            "live_translator_sessions",
            "live_translator_turns"
        ).forEach { table ->
            migratedDb.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'"
            ).use { cursor ->
                assertTrue("Missing $table", cursor.moveToFirst())
            }
        }

        migratedDb.query("PRAGMA table_info(live_translator_templates)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("speaker1Language"))
            assertTrue(columns.contains("speaker2Language"))
            assertTrue(columns.contains("whisperModelPath"))
            assertTrue(columns.contains("backendEngine"))
            assertTrue(columns.contains("startSpeakingTimeoutSeconds"))
            assertTrue(columns.contains("finishedTalkingTimeoutSeconds"))
        }
    }

    @Test
    fun migrate67To68_addsLiveTranslatorSpeakerTtsLanguages() {
        helper.createDatabase(TEST_DB, 67).apply {
            execSQL(
                """
                INSERT INTO live_translator_templates (
                    name,
                    speaker1Language,
                    speaker2Language,
                    whisperModelPath,
                    whisperThreads,
                    ttsModelPath,
                    ttsModelName,
                    ttsLanguage,
                    ttsVoiceName,
                    ttsSteps,
                    ttsSpeed,
                    backendEngine,
                    llamaHost,
                    llamaPort,
                    llamaModelName,
                    ollamaHost,
                    ollamaPort,
                    ollamaModelName,
                    liteRtModelId,
                    liteRtBackend,
                    contextSize,
                    maxTokens,
                    temperature,
                    timeoutSeconds,
                    startSpeakingTimeoutSeconds,
                    finishedTalkingTimeoutSeconds,
                    createdAt,
                    updatedAt
                ) VALUES (
                    'Clinic',
                    'English',
                    'Spanish',
                    NULL,
                    4,
                    NULL,
                    NULL,
                    'fr',
                    NULL,
                    8,
                    1.0,
                    'llama-server',
                    '127.0.0.1',
                    8080,
                    NULL,
                    '127.0.0.1',
                    11434,
                    NULL,
                    NULL,
                    'auto',
                    4096,
                    512,
                    0.2,
                    120,
                    10,
                    5,
                    1,
                    1
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            68,
            true,
            Migrations.MIGRATION_67_68
        )

        migratedDb.query("PRAGMA table_info(live_translator_templates)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("speaker1TtsLanguage"))
            assertTrue(columns.contains("speaker2TtsLanguage"))
        }

        migratedDb.query(
            "SELECT speaker1TtsLanguage, speaker2TtsLanguage FROM live_translator_templates WHERE name = 'Clinic'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("fr", cursor.getString(0))
            assertEquals("fr", cursor.getString(1))
        }
    }

    @Test
    fun migrate69To70_addsLiveTranslatorLiteRtMtpToggle() {
        helper.createDatabase(TEST_DB, 69).close()

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            70,
            true,
            Migrations.MIGRATION_69_70
        )

        migratedDb.query("PRAGMA table_info(live_translator_templates)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("liteRtMtpEnabled"))
        }
    }

    @Test
    fun migrate70To71_addsLiveTranslatorLiteRtThinkingToggle() {
        helper.createDatabase(TEST_DB, 70).close()

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            71,
            true,
            Migrations.MIGRATION_70_71
        )

        migratedDb.query("PRAGMA table_info(live_translator_templates)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("liteRtThinkingEnabled"))
        }
    }

    @Test
    fun migrate74To75_addsAiServerHubTablesAndDefaults() {
        helper.createDatabase(TEST_DB, 74).close()

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            75,
            true,
            Migrations.MIGRATION_74_75
        )

        migratedDb.query("SELECT serverType, port, accessMode, lanVisible FROM ai_server_configs ORDER BY port ASC").use { cursor ->
            val rows = mutableListOf<Pair<String, Int>>()
            while (cursor.moveToNext()) {
                rows += cursor.getString(0) to cursor.getInt(1)
                assertEquals("PUBLIC", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
            }
            assertEquals(
                listOf(
                    "image" to 10101,
                    "video" to 10102,
                    "workflows" to 10103,
                    "tts" to 10104,
                    "video_upscale" to 10105,
                    "docs_datasets" to 10106,
                    "llama_chat" to 10107
                ),
                rows
            )
        }

        migratedDb.query("PRAGMA table_info(ai_server_artifacts)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("ownerUserId"))
            assertTrue(columns.contains("origin"))
            assertTrue(columns.contains("serverType"))
            assertTrue(columns.contains("jobId"))
        }
    }

    @Test
    fun migrate75To76_addsAiServerWebChatTables() {
        helper.createDatabase(TEST_DB, 75).close()

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            76,
            true,
            Migrations.MIGRATION_75_76
        )

        migratedDb.query("PRAGMA table_info(ai_server_web_providers)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("engine"))
            assertTrue(columns.contains("baseUrl"))
            assertTrue(columns.contains("supportsVision"))
            assertTrue(columns.contains("supportsAudio"))
        }

        migratedDb.query("PRAGMA table_info(ai_server_web_chats)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("providerId"))
            assertTrue(columns.contains("systemPrompt"))
        }

        migratedDb.query("PRAGMA table_info(ai_server_web_messages)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("imagePath"))
            assertTrue(columns.contains("audioPath"))
            assertTrue(columns.contains("documentPath"))
            assertTrue(columns.contains("toolActivity"))
        }
    }

    @Test
    fun migrate76To77_addsAiServerWebChatAttachmentTable() {
        helper.createDatabase(TEST_DB, 76).close()

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            77,
            true,
            Migrations.MIGRATION_76_77
        )

        migratedDb.query("PRAGMA table_info(ai_server_web_message_attachments)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("messageId"))
            assertTrue(columns.contains("attachmentType"))
            assertTrue(columns.contains("path"))
            assertTrue(columns.contains("mimeType"))
            assertTrue(columns.contains("sizeBytes"))
        }
    }

    @Test
    fun migrate80To81_backfillsLiveTranslatorFullUrls() {
        helper.createDatabase(TEST_DB, 80).apply {
            execSQL(
                """
                INSERT INTO live_translator_templates (
                    id,
                    name,
                    speaker1Language,
                    speaker2Language,
                    whisperModelPath,
                    whisperThreads,
                    ttsModelPath,
                    ttsModelName,
                    ttsLanguage,
                    speaker1TtsLanguage,
                    speaker2TtsLanguage,
                    ttsVoiceName,
                    ttsSteps,
                    ttsSpeed,
                    backendEngine,
                    llamaHost,
                    llamaPort,
                    llamaModelName,
                    ollamaHost,
                    ollamaPort,
                    ollamaModelName,
                    liteRtModelId,
                    liteRtBackend,
                    liteRtMtpEnabled,
                    liteRtThinkingEnabled,
                    contextSize,
                    maxTokens,
                    temperature,
                    timeoutSeconds,
                    startSpeakingTimeoutSeconds,
                    finishedTalkingTimeoutSeconds,
                    createdAt,
                    updatedAt
                ) VALUES (
                    1,
                    'Travel',
                    'English',
                    'Spanish',
                    NULL,
                    4,
                    NULL,
                    NULL,
                    'en',
                    'en',
                    'es',
                    NULL,
                    8,
                    1.05,
                    'llama-swap',
                    'legacy-llama.local',
                    8088,
                    NULL,
                    'legacy-ollama.local',
                    11555,
                    NULL,
                    NULL,
                    'auto',
                    0,
                    0,
                    4096,
                    512,
                    0.2,
                    120,
                    10,
                    5,
                    123456789,
                    123456799
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            81,
            true,
            Migrations.MIGRATION_80_81
        )

        migratedDb.query(
            """
            SELECT llamaServerUrl, llamaSwapUrl, ollamaUrl
            FROM live_translator_templates
            WHERE id = 1
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("http://legacy-llama.local:8088", cursor.getString(0))
            assertEquals("http://legacy-llama.local:8088", cursor.getString(1))
            assertEquals("http://legacy-ollama.local:11555", cursor.getString(2))
        }
    }

    @Test
    fun migrate83To84_addsAiHubPinsSpeculativeRunsAndNativeToolsPresetFlag() {
        helper.createDatabase(TEST_DB, 83).apply {
            execSQL(
                """
                INSERT INTO llama_chats (
                    title,
                    lastModified,
                    contextSize,
                    systemPrompt,
                    apiParams,
                    folderId
                ) VALUES (
                    'Pinned later',
                    2000,
                    8192,
                    NULL,
                    NULL,
                    NULL
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            84,
            true,
            Migrations.MIGRATION_83_84
        )

        migratedDb.query(
            """
            SELECT pinnedToAiHub, pinnedServerId, pinnedAt
            FROM llama_chats
            WHERE title = 'Pinned later'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }

        migratedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='llama_speculative_runs'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("llama_speculative_runs", cursor.getString(0))
        }

        migratedDb.query("PRAGMA table_info(saved_commands)").use { cursor ->
            var foundNativeToolsColumn = false
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                foundNativeToolsColumn = foundNativeToolsColumn || cursor.getString(nameIndex) == "nativeToolsEnabled"
            }
            assertTrue(foundNativeToolsColumn)
        }
    }

    @Test
    fun migrate84To85_addsSpeculativeRunSampleCount() {
        helper.createDatabase(TEST_DB, 84).apply {
            execSQL(
                """
                INSERT INTO llama_speculative_runs (
                    name,
                    savedForever,
                    createdAt,
                    updatedAt,
                    modelPath,
                    modelName,
                    speculativeMode,
                    draftModelPath,
                    draftModelName,
                    acceptanceRate,
                    promptTokensPerSecond,
                    generationTokensPerSecond,
                    rawMetrics
                ) VALUES (
                    NULL,
                    0,
                    1000,
                    1000,
                    '/models/main.gguf',
                    'main.gguf',
                    'draft-dflash',
                    '/models/draft.gguf',
                    'draft.gguf',
                    NULL,
                    NULL,
                    NULL,
                    ''
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            85,
            true,
            Migrations.MIGRATION_84_85
        )

        migratedDb.query("SELECT sampleCount FROM llama_speculative_runs WHERE modelName = 'main.gguf'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migrate86To87_addsAdvancedNgramSavedCommandFields() {
        helper.createDatabase(TEST_DB, 86).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            87,
            true,
            Migrations.MIGRATION_86_87
        )

        migratedDb.query("PRAGMA table_info(saved_commands)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("ngramMapKSizeN"))
            assertTrue(columns.contains("ngramMapKSizeM"))
            assertTrue(columns.contains("ngramMapKMinHits"))
            assertTrue(columns.contains("ngramMapK4VSizeN"))
            assertTrue(columns.contains("ngramMapK4VSizeM"))
            assertTrue(columns.contains("ngramMapK4VMinHits"))
        }
    }

    @Test
    fun migrate87To88_addsDraftThreadSavedCommandFields() {
        helper.createDatabase(TEST_DB, 87).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            88,
            true,
            Migrations.MIGRATION_87_88
        )

        migratedDb.query("PRAGMA table_info(saved_commands)").use { cursor ->
            val defaultsByColumn = mutableMapOf<String, String?>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                defaultsByColumn[cursor.getString(nameIndex)] = cursor.getString(defaultIndex)
            }
            assertEquals("4", defaultsByColumn["draftThreads"])
            assertEquals("4", defaultsByColumn["draftThreadsBatch"])
        }
    }

    @Test
    fun migrate88To89_addsSdDistributedTables() {
        helper.createDatabase(TEST_DB, 88).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            89,
            true,
            Migrations.MIGRATION_88_89
        )

        val expectedTables = setOf(
            "sd_distributed_workers",
            "sd_distributed_placements",
            "sd_distributed_runs"
        )
        expectedTables.forEach { table ->
            migratedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { cursor ->
                assertTrue("Missing table $table", cursor.moveToFirst())
            }
        }

        migratedDb.query("PRAGMA table_info(sd_distributed_workers)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("ramMB"))
            assertTrue(columns.contains("threads"))
            assertTrue(columns.contains("backendDevice"))
        }

        migratedDb.query("PRAGMA table_info(sd_distributed_placements)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("backendSpec"))
            assertTrue(columns.contains("paramsBackendSpec"))
            assertTrue(columns.contains("splitMode"))
        }
    }

    @Test
    fun migrate89To90_addsSdDistributedMasterSettingsTemplatesAndWorkerOrder() {
        helper.createDatabase(TEST_DB, 89).apply {
            execSQL(
                """
                INSERT INTO sd_distributed_workers (
                    host,
                    port,
                    deviceName,
                    ramMB,
                    threads,
                    backendDevice,
                    isEnabled,
                    lastSeenAt
                ) VALUES (
                    '10.0.0.9',
                    50062,
                    'Pixel worker',
                    4096,
                    4,
                    '',
                    1,
                    0
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            90,
            true,
            Migrations.MIGRATION_89_90
        )

        migratedDb.query("PRAGMA table_info(sd_distributed_workers)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("sortOrder"))
        }

        migratedDb.query("SELECT sortOrder FROM sd_distributed_workers WHERE host = '10.0.0.9'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        migratedDb.query("SELECT placementMode, autoFit, devicesExpanded FROM sd_distributed_master_settings WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("AUTO_RAM", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(1, cursor.getInt(2))
        }

        migratedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='sd_distributed_templates'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
    }

    @Test
    fun migrate90To91_addsLocalSdBackendPreferencesToModels() {
        helper.createDatabase(TEST_DB, 90).apply {
            execSQL(
                """
                INSERT INTO models (
                    filename,
                    path,
                    sizeBytes,
                    type,
                    repoId,
                    isDownloaded,
                    isVision,
                    mmprojPath,
                    sdCapabilities,
                    sdFamily,
                    sdVariant,
                    sdCompatProfiles,
                    onnxCapabilities,
                    onnxAssetKind,
                    onnxPipelineFamily,
                    onnxReferenceUri,
                    onnxReferencePath,
                    layerCount
                ) VALUES (
                    'low-ram.safetensors',
                    '/models/low-ram.safetensors',
                    1234,
                    'SD_CHECKPOINT',
                    'local-import',
                    1,
                    0,
                    NULL,
                    'txt2img,img2img',
                    'checkpoint',
                    'sdxl',
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    0
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            91,
            true,
            Migrations.MIGRATION_90_91
        )

        migratedDb.query(
            "SELECT sdParamsBackendMode, sdRuntimeBackendMode FROM models WHERE filename = 'low-ram.safetensors'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("auto", cursor.getString(0))
            assertEquals("auto", cursor.getString(1))
        }
    }

    @Test
    fun migrate91To92_addsSdDistributedConsoleWorkflowSettings() {
        helper.createDatabase(TEST_DB, 91).apply {
            execSQL(
                """
                INSERT INTO sd_distributed_templates (
                    name,
                    settingsJson,
                    createdAt,
                    updatedAt
                ) VALUES (
                    'Video preset',
                    '{"videoWorkflowMode":"TXT2VID","videoModelPath":"/models/video.gguf"}',
                    1,
                    1
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            92,
            true,
            Migrations.MIGRATION_91_92
        )

        migratedDb.query("PRAGMA table_info(sd_distributed_master_settings)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("masterContributes"))
            assertTrue(columns.contains("masterAllowedModules"))
            assertTrue(columns.contains("imageWorkflowMode"))
            assertTrue(columns.contains("videoWorkflowMode"))
        }

        migratedDb.query("SELECT workflowType FROM sd_distributed_templates WHERE name = 'Video preset'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("VIDEO", cursor.getString(0))
        }
    }

    @Test
    fun migrate92To93_addsSdDistributedComponentModelSettings() {
        helper.createDatabase(TEST_DB, 92).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            93,
            true,
            Migrations.MIGRATION_92_93
        )

        migratedDb.query("PRAGMA table_info(sd_distributed_master_settings)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("imageVaePath"))
            assertTrue(columns.contains("imageClipLPath"))
            assertTrue(columns.contains("imageT5xxlPath"))
            assertTrue(columns.contains("imageControlNetEnabled"))
            assertTrue(columns.contains("imageLoraApplyMode"))
            assertTrue(columns.contains("videoUseVae"))
            assertTrue(columns.contains("videoT5xxlPath"))
        }
    }

    @Test
    fun migrate93To94_addsSdDistributedAutoRamScope() {
        helper.createDatabase(TEST_DB, 93).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            94,
            true,
            Migrations.MIGRATION_93_94
        )

        migratedDb.query("PRAGMA table_info(sd_distributed_master_settings)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("autoRamScope"))
        }
    }

    @Test
    fun migrate96To97_addsAgentLocalSandboxRuntimeMetadata() {
        helper.createDatabase(TEST_DB, 96).apply {
            execSQL(
                """
                INSERT INTO agent_conversations (
                    id,
                    title,
                    projectFolder,
                    lastAgentRole,
                    lastTask,
                    knowledgeBaseIds,
                    createdAt,
                    updatedAt
                ) VALUES (
                    42,
                    'Existing SSH project',
                    'existing_project',
                    'ORCHESTRATOR',
                    'Keep current behavior',
                    '',
                    100,
                    200
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            97,
            true,
            Migrations.MIGRATION_96_97
        )

        migratedDb.query(
            """
            SELECT workspaceBackend, runtimeCapabilitiesJson, runEntrypointPath, runUiMode, lastRunProfileJson
            FROM agent_conversations
            WHERE id = 42
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("REMOTE_SSH", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertNull(cursor.getString(2))
            assertEquals("CONSOLE", cursor.getString(3))
            assertEquals("", cursor.getString(4))
        }

        migratedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='agent_project_runs'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        migratedDb.query("PRAGMA table_info(agent_project_runs)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("conversationId"))
            assertTrue(columns.contains("backend"))
            assertTrue(columns.contains("runtime"))
            assertTrue(columns.contains("entrypoint"))
            assertTrue(columns.contains("forceStopRequestedAt"))
        }
    }

    @Test
    fun migrate97To98_addsAgentProjectFoldersPlanningAndResumeMetadata() {
        helper.createDatabase(TEST_DB, 97).apply {
            execSQL(
                """
                INSERT INTO agent_conversations (
                    id,
                    title,
                    projectFolder,
                    lastAgentRole,
                    lastTask,
                    knowledgeBaseIds,
                    workspaceBackend,
                    runtimeCapabilitiesJson,
                    runEntrypointPath,
                    runUiMode,
                    lastRunProfileJson,
                    createdAt,
                    updatedAt
                ) VALUES (
                    101,
                    'Newer project',
                    'newer_project',
                    'ORCHESTRATOR',
                    NULL,
                    '',
                    'REMOTE_SSH',
                    '',
                    NULL,
                    'CONSOLE',
                    '',
                    100,
                    300
                ), (
                    102,
                    'Older project',
                    'older_project',
                    'ORCHESTRATOR',
                    NULL,
                    '',
                    'LOCAL_SANDBOX',
                    '',
                    NULL,
                    'CONSOLE',
                    '',
                    100,
                    200
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            98,
            true,
            Migrations.MIGRATION_97_98
        )

        migratedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='agent_project_folders'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        migratedDb.query("PRAGMA table_info(agent_conversations)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("projectFolderId"))
            assertTrue(columns.contains("sortOrder"))
            assertTrue(columns.contains("planningModeEnabled"))
            assertTrue(columns.contains("resumeState"))
            assertTrue(columns.contains("lastStopReason"))
        }
        migratedDb.query(
            """
            SELECT id, workspaceBackend, projectFolderId, sortOrder, planningModeEnabled, resumeState, lastStopReason
            FROM agent_conversations
            ORDER BY sortOrder ASC
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(101L, cursor.getLong(0))
            assertEquals("REMOTE_SSH", cursor.getString(1))
            assertNull(cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals(0, cursor.getInt(4))
            assertEquals("IDLE", cursor.getString(5))
            assertNull(cursor.getString(6))

            assertTrue(cursor.moveToNext())
            assertEquals(102L, cursor.getLong(0))
            assertEquals("LOCAL_SANDBOX", cursor.getString(1))
            assertNull(cursor.getString(2))
            assertEquals(1, cursor.getInt(3))
            assertEquals(0, cursor.getInt(4))
            assertEquals("IDLE", cursor.getString(5))
            assertNull(cursor.getString(6))
        }
    }

    @Test
    fun migrate98To99_addsSplitSdDistributedMediaRunSettings() {
        helper.createDatabase(TEST_DB, 98).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            99,
            true,
            Migrations.MIGRATION_98_99
        )

        migratedDb.query("PRAGMA table_info(sd_distributed_master_settings)").use { cursor ->
            val defaultsByColumn = mutableMapOf<String, String?>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                defaultsByColumn[cursor.getString(nameIndex)] = cursor.getString(defaultIndex)
            }

            assertEquals("''", defaultsByColumn["imagePrompt"])
            assertEquals("'512'", defaultsByColumn["imageWidth"])
            assertEquals("'512'", defaultsByColumn["imageHeight"])
            assertEquals("'20'", defaultsByColumn["imageSteps"])
            assertEquals("'7.0'", defaultsByColumn["imageCfg"])
            assertEquals("'euler_a'", defaultsByColumn["imageSampler"])
            assertEquals("''", defaultsByColumn["videoPrompt"])
            assertEquals("'480'", defaultsByColumn["videoWidth"])
            assertEquals("'832'", defaultsByColumn["videoHeight"])
            assertEquals("'18'", defaultsByColumn["videoSteps"])
            assertEquals("'6.0'", defaultsByColumn["videoCfg"])
            assertEquals("'euler'", defaultsByColumn["videoSampler"])
        }
    }

    @Test
    fun migrate99To100_addsWearEphemeralLlamaChatMetadata() {
        helper.createDatabase(TEST_DB, 99).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            100,
            true,
            Migrations.MIGRATION_99_100
        )

        migratedDb.query("PRAGMA table_info(llama_chats)").use { cursor ->
            val defaultsByColumn = mutableMapOf<String, String?>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                defaultsByColumn[cursor.getString(nameIndex)] = cursor.getString(defaultIndex)
            }

            assertEquals("0", defaultsByColumn["isEphemeral"])
            assertEquals(null, defaultsByColumn["source"])
            assertEquals("0", defaultsByColumn["deleteAfterSession"])
            assertEquals(null, defaultsByColumn["expiresAtMillis"])
        }
    }

    @Test
    fun migrate100To101_addsAgentProjectEventJournal() {
        helper.createDatabase(TEST_DB, 100).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            101,
            true,
            Migrations.MIGRATION_100_101
        )

        migratedDb.query("PRAGMA table_info(agent_project_events)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }

            assertTrue(columns.contains("conversationId"))
            assertTrue(columns.contains("category"))
            assertTrue(columns.contains("eventType"))
            assertTrue(columns.contains("toolName"))
            assertTrue(columns.contains("toolCallId"))
            assertTrue(columns.contains("contentChars"))
            assertTrue(columns.contains("toolOutputChars"))
            assertTrue(columns.contains("errorClass"))
            assertTrue(columns.contains("errorMessage"))
            assertTrue(columns.contains("summary"))
        }
    }

    @Test
    fun migrate101To102_addsStructuredAgentWorkflowTables() {
        helper.createDatabase(TEST_DB, 101).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            102,
            true,
            Migrations.MIGRATION_101_102
        )

        val expectedTables = setOf(
            "agent_message_parts",
            "agent_turn_contexts",
            "agent_skills",
            "agent_skill_assignments",
            "agent_pending_questions",
            "agent_todos",
            "agent_compactions"
        )
        migratedDb.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            val actual = mutableSetOf<String>()
            while (cursor.moveToNext()) actual += cursor.getString(0)
            assertTrue(actual.containsAll(expectedTables))
        }
        migratedDb.query("PRAGMA table_info(agent_turn_contexts)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            assertTrue(columns.contains("messagesHash"))
            assertTrue(columns.contains("previousPrefixCompatible"))
            assertTrue(columns.contains("cacheMissReason"))
        }
        migratedDb.query("PRAGMA table_info(agent_pending_questions)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            assertTrue(columns.contains("toolCallId"))
            assertTrue(columns.contains("continuationEnqueued"))
        }
    }

    @Test
    fun migrate102To103_addsSavedLocalLlamaLaunchProfile() {
        helper.createDatabase(TEST_DB, 102).apply {
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            103,
            true,
            Migrations.MIGRATION_102_103
        )

        migratedDb.query("PRAGMA table_info(llama_servers)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            assertTrue(columns.contains("localLaunchProfileJson"))
        }
    }

    @Test
    fun migrate107To108_addsManagedRuntimesAndMultiLoraStorage() {
        helper.createDatabase(TEST_DB, 107).apply { close() }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            108,
            true,
            Migrations.MIGRATION_107_108
        )

        migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name IN ('llama_server_cards', 'agent_runtime_profiles')"
        ).use { cursor ->
            val tables = mutableSetOf<String>()
            while (cursor.moveToNext()) tables += cursor.getString(0)
            assertEquals(setOf("llama_server_cards", "agent_runtime_profiles"), tables)
        }
        migratedDb.query("PRAGMA table_info(llama_server_cards)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            assertTrue(
                columns.containsAll(
                    setOf("id", "name", "savedCommandId", "presetNameSnapshot", "port")
                )
            )
        }
        migratedDb.query("PRAGMA table_info(agent_runtime_profiles)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            assertTrue(
                columns.containsAll(
                    setOf("agentKey", "backend", "model", "managedLlamaServerId", "liteRtModelId")
                )
            )
        }
        migratedDb.query("PRAGMA table_info(sd_distributed_master_settings)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            assertTrue(
                columns.containsAll(
                    setOf(
                        "imageLorasJson",
                        "videoLorasJson",
                        "videoHighNoiseLorasJson",
                        "videoLoraApplyMode"
                    )
                )
            )
        }
    }

    @Test
    fun migrate108To109_addsNamedAgentEndpointsAndPreservesProfiles() {
        helper.createDatabase(TEST_DB, 108).apply {
            execSQL(
                """
                INSERT INTO agent_runtime_profiles(
                    agentKey, backend, model, managedLlamaServerId, liteRtModelId, updatedAt
                ) VALUES ('CODER', 'ollama', 'qwen-test', NULL, NULL, 1234)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            109,
            true,
            Migrations.MIGRATION_108_109
        )

        migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name = 'agent_runtime_endpoint_configs'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("agent_runtime_endpoint_configs", cursor.getString(0))
        }
        migratedDb.query("PRAGMA table_info(agent_runtime_profiles)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            assertTrue(columns.contains("endpointConfigId"))
        }
        migratedDb.query(
            "SELECT backend, model, endpointConfigId, updatedAt " +
                "FROM agent_runtime_profiles WHERE agentKey = 'CODER'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ollama", cursor.getString(0))
            assertEquals("qwen-test", cursor.getString(1))
            assertNull(cursor.getString(2))
            assertEquals(1234L, cursor.getLong(3))
        }
    }

    @Test
    fun migrate109To110_addsWearStartFlagAndPreservesCards() {
        helper.createDatabase(TEST_DB, 109).apply {
            execSQL(
                """
                INSERT INTO llama_server_cards (
                    id, name, savedCommandId, presetNameSnapshot, port, createdAt, updatedAt
                ) VALUES (1, 'Phone server', 7, 'general preset', 8080, 100, 200)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            110,
            true,
            Migrations.MIGRATION_109_110
        )

        migratedDb.query("SELECT name, port, allowWearStart FROM llama_server_cards WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Phone server", cursor.getString(0))
            assertEquals(8080, cursor.getInt(1))
            // Existing cards must default to disabled: upgrading must not silently
            // grant the watch permission to start a server.
            assertEquals(0, cursor.getInt(2))
        }
    }

    @Test
    fun migrate110To111_addsInspectionColumnsWithoutChangingModelRows() {
        helper.createDatabase(TEST_DB, 110).apply {
            execSQL(
                """
                INSERT INTO models (
                    filename, path, sizeBytes, type, repoId, isDownloaded, isVision,
                    mmprojPath, sdCapabilities, sdFamily, sdVariant, sdCompatProfiles,
                    sdParamsBackendMode, sdRuntimeBackendMode, onnxCapabilities,
                    onnxAssetKind, onnxPipelineFamily, onnxReferenceUri, onnxReferencePath,
                    layerCount
                ) VALUES (
                    'legacy-sd3.safetensors', '/models/legacy-sd3.safetensors', 987654321,
                    'SD_CHECKPOINT', 'local-import', 1, 0, NULL, 'txt2img', 'sd3',
                    'sd3', 'sd3', 'auto', 'auto', NULL, NULL, NULL, NULL, NULL, 0
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            111,
            true,
            Migrations.MIGRATION_110_111
        )

        migratedDb.query(
            "SELECT path, sizeBytes, sdFamily, sdVariant, sdCompatProfiles, " +
                "sdDetectedFamily, sdDetectedRole, sdArtifactLayout, " +
                "sdInspectionConfidence, sdInspectionVersion, sdInspectionJson " +
                "FROM models WHERE filename = 'legacy-sd3.safetensors'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("/models/legacy-sd3.safetensors", cursor.getString(0))
            assertEquals(987654321L, cursor.getLong(1))
            assertEquals("sd3", cursor.getString(2))
            assertEquals("sd3", cursor.getString(3))
            assertEquals("sd3", cursor.getString(4))
            assertNull(cursor.getString(5))
            assertNull(cursor.getString(6))
            assertNull(cursor.getString(7))
            assertNull(cursor.getString(8))
            assertEquals(0, cursor.getInt(9))
            assertNull(cursor.getString(10))
        }
        migratedDb.close()
    }

    @Test
    fun migrate111To112_addsSdParamsBackendSpecAndPreservesLegacyPlacement() {
        helper.createDatabase(TEST_DB, 111).apply {
            execSQL(
                """
                INSERT INTO models (
                    filename, path, sizeBytes, type, repoId, isDownloaded, isVision,
                    mmprojPath, sdCapabilities, sdFamily, sdVariant, sdCompatProfiles,
                    sdParamsBackendMode, sdRuntimeBackendMode, onnxCapabilities,
                    onnxAssetKind, onnxPipelineFamily, onnxReferenceUri, onnxReferencePath,
                    layerCount, sdDetectedFamily, sdDetectedRole, sdArtifactLayout,
                    sdInspectionConfidence, sdInspectionVersion, sdInspectionJson
                ) VALUES (
                    'legacy-placement.safetensors', '/models/legacy-placement.safetensors', 42,
                    'SD_CHECKPOINT', 'local-import', 1, 0, NULL, 'txt2img', 'checkpoint',
                    'sd1', 'checkpoint:sd1', 'disk', 'auto', NULL, NULL, NULL, NULL, NULL,
                    0, 'checkpoint', 'full_model', 'full_model', 'high', 1, '{}'
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            112,
            true,
            Migrations.MIGRATION_111_112
        )

        migratedDb.query(
            "SELECT path, sdParamsBackendMode, sdParamsBackendSpec, sdDetectedFamily, " +
                "sdInspectionJson FROM models WHERE filename = 'legacy-placement.safetensors'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("/models/legacy-placement.safetensors", cursor.getString(0))
            assertEquals("disk", cursor.getString(1))
            assertEquals("auto", cursor.getString(2))
            assertEquals("checkpoint", cursor.getString(3))
            assertEquals("{}", cursor.getString(4))
        }
        migratedDb.close()
    }

    /**
     * Walks the entire registered migration chain in one run.
     *
     * The per-migration tests above each validate one hop in isolation, which
     * cannot catch ordering mistakes, a hop missing from
     * [Migrations.ALL_MIGRATIONS], or a migration that only fails once an earlier
     * one has already reshaped the table. This is the path a real user upgrading
     * from an old install actually takes, and at 80+ hops it is the failure mode
     * with no hotfix: a broken chain corrupts local databases on upgrade.
     *
     * Starts at 28 because that is the oldest schema JSON exported under
     * `app/schemas`, so it is the oldest version [MigrationTestHelper] can build.
     */
    @Test
    fun migrateFullChain_fromOldestExportedSchemaToLatest() {
        helper.createDatabase(TEST_DB, OLDEST_EXPORTED_VERSION).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            LATEST_VERSION,
            true,
            *Migrations.ALL_MIGRATIONS
        ).close()
    }

    companion object {
        private const val TEST_DB = "app-migration-test"

        /** Oldest schema JSON present in `app/schemas`. */
        private const val OLDEST_EXPORTED_VERSION = 28

        /** Keep in step with the `version` in [AppDatabase]'s `@Database`. */
        private const val LATEST_VERSION = 112
    }
}
