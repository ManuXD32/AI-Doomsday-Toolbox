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

    companion object {
        private const val TEST_DB = "app-migration-test"
    }
}
