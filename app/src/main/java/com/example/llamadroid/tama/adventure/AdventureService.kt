package com.example.llamadroid.tama.adventure

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.OnnxImageGenConfig
import com.example.llamadroid.onnx.OnnxImageGenMode
import com.example.llamadroid.onnx.OnnxRuntimeBackend
import com.example.llamadroid.onnx.OnnxRuntimeOptions
import com.example.llamadroid.onnx.OnnxTxt2ImgPipeline
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.service.PDFSummaryLogic
import com.example.llamadroid.service.RemoteSummaryBackendConfig
import com.example.llamadroid.service.RemoteSummaryClientFactory
import com.example.llamadroid.service.RemoteSummaryRequest
import com.example.llamadroid.tama.db.AdventureSessionEntity
import com.example.llamadroid.tama.db.AdventureStageEntity
import com.example.llamadroid.tama.db.DungeonProgressEntity
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class AdventureService(
    private val database: TamaDatabase,
    private val settingsRepository: SettingsRepository,
    private val context: Context
) {
    private companion object {
        private const val MIN_ADVENTURE_IMAGE_MAX_HEAP_BYTES = 384L * 1024L * 1024L
        private const val MIN_ADVENTURE_IMAGE_AVAILABLE_HEAP_BYTES = 96L * 1024L * 1024L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    suspend fun initializeOrContinue(
        petId: String,
        dungeonType: DungeonType,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<AdventureSession> = withContext(Dispatchers.IO) {
        try {
            val existingSession = database.tamaDao()
                .getActiveAdventureSessionForDungeon(petId, dungeonType.name)
            if (existingSession != null) {
                val stages = database.tamaDao().getAdventureStages(existingSession.id)
                return@withContext Result.success(entityToSession(existingSession, stages))
            }

            onProgress(0.1f, context.getString(R.string.adventure_status_building_world))
            var schematic = generateSchematic(dungeonType)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.adventure_error_schematic)))

            val sessionId = UUID.randomUUID().toString()

            if (settingsRepository.adventureWorldImageEnabled.value) {
                onProgress(0.32f, context.getString(R.string.adventure_status_writing_image_prompts))
                schematic = tryGenerateWorldImage(
                    sessionId = sessionId,
                    dungeonType = dungeonType,
                    schematic = schematic,
                    onProgress = onProgress
                )
            }

            val session = AdventureSession(
                id = sessionId,
                petId = petId,
                dungeonType = dungeonType,
                schematic = schematic
            )

            onProgress(0.72f, context.getString(R.string.adventure_status_writing_opening_scene))
            val openingStage = generateStage(session, null)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.adventure_error_opening_scene)))
            val openingStageWithImage = if (settingsRepository.adventureStageImagesEnabled.value) {
                onProgress(0.80f, context.getString(R.string.adventure_status_writing_stage_image_prompts, openingStage.stageNumber))
                tryGenerateStageImage(
                    session = session,
                    stage = openingStage,
                    dungeonType = dungeonType,
                    onProgress = onProgress
                )
            } else {
                openingStage
            }

            val sessionWithStage = session.addStage(openingStageWithImage)
            saveSession(sessionWithStage)
            Result.success(sessionWithStage)
        } catch (e: Exception) {
            DebugLog.log("[Adventure] initializeOrContinue failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun submitChoice(
        petId: String,
        dungeonType: DungeonType,
        playerChoice: String,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<AdventureSession> = withContext(Dispatchers.IO) {
        try {
            onProgress(0.08f, context.getString(R.string.adventure_status_processing_choice))
            val sessionEntity = database.tamaDao()
                .getActiveAdventureSessionForDungeon(petId, dungeonType.name)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.adventure_error_no_active_session)))

            val stages = database.tamaDao().getAdventureStages(sessionEntity.id)
            var session = entityToSession(sessionEntity, stages).updateLastStageWithResponse(playerChoice)

            onProgress(0.36f, context.getString(R.string.adventure_status_summarizing_scene))
            val summary = summarizeStage(session.stages.last())
            if (!summary.isNullOrBlank()) {
                val updatedStages = session.stages.toMutableList()
                updatedStages[updatedStages.lastIndex] = updatedStages.last().copy(summary = summary)
                session = session.copy(
                    stages = updatedStages,
                    cumulativeSummary = listOf(session.cumulativeSummary, summary)
                        .filter(String::isNotBlank)
                        .joinToString("\n")
                )
            }

            if (session.currentStage >= session.schematic.totalStages) {
                session = session.markCompleted()
                updateDungeonProgress(petId, dungeonType)
                saveSession(session)
                return@withContext Result.success(session)
            }

            onProgress(0.72f, context.getString(R.string.adventure_status_writing_next_scene, session.currentStage + 1))
            val nextStage = generateStage(session, playerChoice)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.adventure_error_next_scene)))
            val nextStageWithImage = if (settingsRepository.adventureStageImagesEnabled.value) {
                onProgress(0.80f, context.getString(R.string.adventure_status_writing_stage_image_prompts, nextStage.stageNumber))
                tryGenerateStageImage(
                    session = session,
                    stage = nextStage,
                    dungeonType = dungeonType,
                    onProgress = onProgress
                )
            } else {
                nextStage
            }

            session = session.addStage(nextStageWithImage)
            saveSession(session)
            Result.success(session)
        } catch (e: Exception) {
            DebugLog.log("[Adventure] submitChoice failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun resetAdventure(petId: String, dungeonType: DungeonType) = withContext(Dispatchers.IO) {
        val existingSession = database.tamaDao().getLatestAdventureSessionForDungeon(petId, dungeonType.name)
        if (existingSession != null) {
            database.tamaDao().deleteAdventureStages(existingSession.id)
            database.tamaDao().deleteAdventureSession(existingSession.id)
        }
    }

    private suspend fun generateSchematic(dungeonType: DungeonType): StorySchematic? {
        val prompt = """
            You are a story planner for a retro handheld monster-pet adventure.

            DUNGEON THEME:
            ${dungeonType.stylePrompt.trim()}

            Create a brief structural outline with these exact fields:
            TOTAL_STAGES: number between 8 and 20
            STORY_THREAD: one paragraph with the main plot arc
            KEY_EVENTS: 4-5 bullet points
            POSSIBLE_ENDINGS: 2-3 bullet points
            TONE: one short phrase
            DIFFICULTY: easy, medium, or hard

            Keep it concise and practical.
            Write everything in ${settingsRepository.adventureLanguage.value}.
        """.trimIndent()

        val response = generateText(
            userPrompt = prompt,
            useSummarizer = false,
            maxTokens = 700,
            temperature = 0.7f
        ) ?: return null
        return parseSchematic(response)
    }

    private suspend fun generateStage(session: AdventureSession, playerChoice: String?): AdventureStage? {
        val systemPrompt = settingsRepository.adventureSystemPrompt.value
        val stageNumber = session.currentStage + 1
        val totalStages = session.schematic.totalStages
        val progressPercent = (stageNumber * 100) / totalStages
        val previousMoments = session.stages.takeLast(3).joinToString("\n\n") { stage ->
            buildString {
                appendLine("Scene ${stage.stageNumber}: ${stage.storyContent.trim()}")
                stage.userResponse?.takeIf(String::isNotBlank)?.let {
                    appendLine("Player response: $it")
                }
                stage.summary?.takeIf(String::isNotBlank)?.let {
                    appendLine("Scene summary: $it")
                }
            }.trim()
        }

        val userPrompt = buildString {
            appendLine("SETTING: ${dungeonTypeLabel(session.dungeonType)}")
            appendLine("THEME: ${session.dungeonType.stylePrompt.trim()}")
            appendLine("LANGUAGE: ${settingsRepository.adventureLanguage.value}")
            appendLine()
            appendLine("HIDDEN STORY GUIDE:")
            appendLine("Main arc: ${session.schematic.storyThread}")
            appendLine("Key events: ${session.schematic.keyEvents.joinToString(", ")}")
            appendLine("Possible endings: ${session.schematic.possibleEndings.joinToString(", ")}")
            appendLine("Tone: ${session.schematic.tone}")
            appendLine("Difficulty: ${session.schematic.difficulty}")
            appendLine()
            if (session.cumulativeSummary.isNotBlank()) {
                appendLine("STORY SO FAR:")
                appendLine(session.cumulativeSummary.trim())
                appendLine()
            }
            if (previousMoments.isNotBlank()) {
                appendLine("RECENT SCENES:")
                appendLine(previousMoments)
                appendLine()
            }
            if (!playerChoice.isNullOrBlank()) {
                appendLine("LATEST PLAYER CHOICE:")
                appendLine(playerChoice.trim())
                appendLine()
            }
            append("Write scene $stageNumber of $totalStages. ")
            when {
                progressPercent < 20 -> append("This is the opening stretch, so establish the danger and curiosity.")
                progressPercent < 50 -> append("Build momentum and reveal a meaningful new twist.")
                progressPercent < 80 -> append("Escalate the stakes and make the consequences sharper.")
                progressPercent < 95 -> append("Prepare the climax and narrow the possible outcomes.")
                else -> append("This is the final scene, so deliver the resolution.")
            }
            appendLine()
            appendLine()
            appendLine("Rules:")
            appendLine("- Stay fully in-world and immersive.")
            appendLine("- Do not mention hidden planning notes, schemas, or stage counts.")
            appendLine("- End with exactly 2 to 4 clear choices for the player.")
            appendLine("- Keep this scene distinct from the recent scenes.")
            appendLine("- Return only the story scene text.")
        }

        val response = generateText(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            useSummarizer = false,
            maxTokens = 1100,
            temperature = 0.85f
        ) ?: return null

        return AdventureStage(
            stageNumber = stageNumber,
            storyContent = response,
            userResponse = null,
            summary = null
        )
    }

    private suspend fun summarizeStage(stage: AdventureStage): String? {
        val prompt = """
            ${settingsRepository.adventureSummarizerPrompt.value}

            Language: ${settingsRepository.adventureLanguage.value}

            Scene text:
            ${stage.storyContent.trim()}

            Player response:
            ${stage.userResponse ?: "N/A"}

            Return only a brief 1-2 sentence summary.
        """.trimIndent()
        return generateText(
            userPrompt = prompt,
            useSummarizer = true,
            maxTokens = 220,
            temperature = 0.35f
        )
    }

    private suspend fun tryGenerateWorldImage(
        sessionId: String,
        dungeonType: DungeonType,
        schematic: StorySchematic,
        onProgress: (Float, String) -> Unit
    ): StorySchematic {
        if (!hasAdventureImageHeapHeadroom("world")) {
            onProgress(0.48f, context.getString(R.string.adventure_status_image_skipped_low_memory))
            return schematic
        }
        val model = resolveAdventureImageModel() ?: return schematic

        val positivePrompt = generateText(
            systemPrompt = adventureImagePositivePromptSystem(),
            userPrompt = adventureImagePositivePromptUser(dungeonType, schematic),
            useSummarizer = false,
            maxTokens = 320,
            temperature = 0.55f
        )?.ifBlank { null } ?: fallbackWorldImagePrompt(dungeonType, schematic)

        val negativePrompt = generateText(
            systemPrompt = adventureImageNegativePromptSystem(),
            userPrompt = adventureImageNegativePromptUser(dungeonType, schematic),
            useSummarizer = false,
            maxTokens = 120,
            temperature = 0.3f
        )?.ifBlank { null } ?: "text, ui, watermark, realistic photo, blurry, cluttered foreground, extra people"

        return runCatching {
            onProgress(0.48f, context.getString(R.string.adventure_status_generating_world_image))
            val resolution = settingsRepository.adventureOnnxResolution.value.coerceAtLeast(256)
            val outputFile = File(File(context.filesDir, "adventure_worlds").apply { mkdirs() }, "$sessionId.png")
            OnnxTxt2ImgPipeline().generate(
                config = OnnxImageGenConfig(
                    modelPath = model.path,
                    modelName = model.filename,
                    mode = OnnxImageGenMode.TXT2IMG,
                    prompt = positivePrompt,
                    negativePrompt = negativePrompt,
                    width = resolution,
                    height = resolution,
                    steps = settingsRepository.adventureOnnxSteps.value.coerceAtLeast(1),
                    cfgScale = settingsRepository.adventureOnnxCfg.value,
                    seed = -1L,
                    requestedWidth = resolution,
                    requestedHeight = resolution,
                    backend = OnnxRuntimeBackend.CPU,
                    runtimeOptions = OnnxRuntimeOptions(),
                    outputPath = outputFile.absolutePath
                ),
                onProgress = { _, _ -> }
            )
            schematic.copy(
                worldImagePath = outputFile.absolutePath,
                worldImagePrompt = positivePrompt,
                worldImageNegativePrompt = negativePrompt
            )
        }.getOrElse { error ->
            DebugLog.log("[Adventure] World image generation skipped: ${error.message}")
            schematic
        }
    }

    private suspend fun tryGenerateStageImage(
        session: AdventureSession,
        stage: AdventureStage,
        dungeonType: DungeonType,
        onProgress: (Float, String) -> Unit
    ): AdventureStage {
        if (!hasAdventureImageHeapHeadroom("stage_${stage.stageNumber}")) {
            onProgress(0.86f, context.getString(R.string.adventure_status_image_skipped_low_memory))
            return stage
        }
        val model = resolveAdventureImageModel() ?: return stage
        val positivePrompt = generateText(
            systemPrompt = adventureImagePositivePromptSystem(),
            userPrompt = adventureStageImagePositivePromptUser(
                session = session,
                stage = stage,
                dungeonType = dungeonType
            ),
            useSummarizer = false,
            maxTokens = 340,
            temperature = 0.55f
        )?.ifBlank { null } ?: fallbackStageImagePrompt(session, stage, dungeonType)

        val negativePrompt = generateText(
            systemPrompt = adventureImageNegativePromptSystem(),
            userPrompt = adventureStageImageNegativePromptUser(
                session = session,
                stage = stage,
                dungeonType = dungeonType
            ),
            useSummarizer = false,
            maxTokens = 120,
            temperature = 0.3f
        )?.ifBlank { null } ?: "text, ui, watermark, realistic photo, blurry, cluttered foreground, extra people"

        return runCatching {
            onProgress(0.86f, context.getString(R.string.adventure_status_generating_stage_image, stage.stageNumber))
            val resolution = settingsRepository.adventureOnnxResolution.value.coerceAtLeast(256)
            val stageDir = File(File(context.filesDir, "adventure_worlds").apply { mkdirs() }, session.id).apply { mkdirs() }
            val outputFile = File(stageDir, "stage_${stage.stageNumber}.png")
            OnnxTxt2ImgPipeline().generate(
                config = OnnxImageGenConfig(
                    modelPath = model.path,
                    modelName = model.filename,
                    mode = OnnxImageGenMode.TXT2IMG,
                    prompt = positivePrompt,
                    negativePrompt = negativePrompt,
                    width = resolution,
                    height = resolution,
                    steps = settingsRepository.adventureOnnxSteps.value.coerceAtLeast(1),
                    cfgScale = settingsRepository.adventureOnnxCfg.value,
                    seed = -1L,
                    requestedWidth = resolution,
                    requestedHeight = resolution,
                    backend = OnnxRuntimeBackend.CPU,
                    runtimeOptions = OnnxRuntimeOptions(),
                    outputPath = outputFile.absolutePath
                ),
                onProgress = { _, _ -> }
            )
            stage.copy(
                imagePath = outputFile.absolutePath,
                imagePrompt = positivePrompt,
                imageNegativePrompt = negativePrompt
            )
        }.getOrElse { error ->
            DebugLog.log("[Adventure] Stage image generation skipped: ${error.message}")
            stage
        }
    }

    private fun hasAdventureImageHeapHeadroom(phase: String): Boolean {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory()
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        val availableHeap = maxHeap - usedHeap
        val hasHeadroom = maxHeap >= MIN_ADVENTURE_IMAGE_MAX_HEAP_BYTES &&
            availableHeap >= MIN_ADVENTURE_IMAGE_AVAILABLE_HEAP_BYTES
        if (!hasHeadroom) {
            DebugLog.log(
                "[Adventure] Skipping optional $phase image: " +
                    "maxHeap=${maxHeap / (1024L * 1024L)}MB " +
                    "availableHeap=${availableHeap / (1024L * 1024L)}MB"
            )
        }
        return hasHeadroom
    }

    private suspend fun resolveAdventureImageModel() =
        AppDatabase.getDatabase(context.applicationContext)
            .modelDao()
            .getModelsByTypesSync(listOf(ModelType.ONNX_IMAGE_GEN))
            .filter { it.isOnnxTxt2ImgBundle() }
            .let { models ->
                val preferred = settingsRepository.adventureOnnxModelFilename.value
                preferred?.let { selected ->
                    models.firstOrNull { it.filename == selected || it.path == selected }
                } ?: models.firstOrNull()
            }

    private fun adventureImagePositivePromptSystem(): String = """
        You write one English positive prompt for local image generation.
        Return only the prompt text, no JSON, no markdown, no label.
        The prompt must clearly request:
        - retro handheld game background illustration
        - charming 1990s Tamagotchi-style scene
        - flat colorful 2d art
        - bold outlines
        - cute toy-like shapes
        - simple readable composition
        - clear open center space when appropriate
        - no text, no UI, no foreground clutter

        Follow this example style closely:
        retro handheld game background illustration, charming 1990s Tamagotchi-style room interior scene, flat colorful 2d art, large TV on the left, rounded sofa on the right, dining table in the center, shelf at the back, wall clock, rug on the floor, warm cozy mood, cute toy-like shapes, bold outlines, simple readable composition, clear open center space for pet tiles, no text, no UI, no people, no foreground objects
    """.trimIndent()

    private fun adventureImagePositivePromptUser(dungeonType: DungeonType, schematic: StorySchematic): String = """
        Create one background prompt for the opening world image of this text adventure.
        Dungeon: ${dungeonTypeLabel(dungeonType)}
        Theme: ${dungeonType.stylePrompt.trim()}
        Story thread: ${schematic.storyThread}
        Key events: ${schematic.keyEvents.joinToString(", ")}
        Tone: ${schematic.tone}
        Difficulty: ${schematic.difficulty}

        Make it feel like an explorable retro adventure location.
        Return only the English positive prompt.
    """.trimIndent()

    private fun adventureStageImagePositivePromptUser(
        session: AdventureSession,
        stage: AdventureStage,
        dungeonType: DungeonType
    ): String = """
        Create one background prompt for an in-story scene image.
        Dungeon: ${dungeonTypeLabel(dungeonType)}
        Theme: ${dungeonType.stylePrompt.trim()}
        Story thread: ${session.schematic.storyThread}
        Stage ${stage.stageNumber} story: ${stage.storyContent.trim()}
        Player response: ${stage.userResponse ?: "N/A"}
        Previous summary: ${session.cumulativeSummary.ifBlank { "N/A" }}

        Make the scene feel like part of the same retro handheld adventure world.
        Return only the English positive prompt.
    """.trimIndent()

    private fun adventureImageNegativePromptSystem(): String = """
        Write one short English negative prompt for local image generation.
        Return only a concise comma-separated exclusion list.
        No JSON, no labels, no explanation.
    """.trimIndent()

    private fun adventureImageNegativePromptUser(dungeonType: DungeonType, schematic: StorySchematic): String = """
        Write a negative prompt for an image of ${dungeonTypeLabel(dungeonType)}.
        Tone: ${schematic.tone}
        Keep it short and useful.
    """.trimIndent()

    private fun adventureStageImageNegativePromptUser(
        session: AdventureSession,
        stage: AdventureStage,
        dungeonType: DungeonType
    ): String = """
        Write a negative prompt for stage ${stage.stageNumber} of ${dungeonTypeLabel(dungeonType)}.
        Story text: ${stage.storyContent.trim()}
        Previous summary: ${session.cumulativeSummary.ifBlank { "N/A" }}
        Keep it short and useful.
    """.trimIndent()

    private fun fallbackWorldImagePrompt(dungeonType: DungeonType, schematic: StorySchematic): String {
        return buildString {
            append("retro handheld game background illustration, charming 1990s Tamagotchi-style dungeon scene, ")
            append("flat colorful 2d art, bold outlines, cute toy-like shapes, simple readable composition, ")
            append("clear open center space for pet tiles, ")
            append(dungeonType.displayName.lowercase())
            append(" adventure setting, ")
            append(schematic.tone.lowercase())
            append(" mood, ")
            append("no text, no UI, no people, no foreground objects")
        }
    }

    private fun fallbackStageImagePrompt(
        session: AdventureSession,
        stage: AdventureStage,
        dungeonType: DungeonType
    ): String {
        return buildString {
            append("retro handheld game background illustration, charming 1990s Tamagotchi-style scene, ")
            append("flat colorful 2d art, bold outlines, cute toy-like shapes, simple readable composition, ")
            append("clear open center space for pet tiles, ")
            append(dungeonType.displayName.lowercase())
            append(" adventure moment, stage ")
            append(stage.stageNumber)
            append(", ")
            append(stage.storyContent.take(180).lowercase())
            append(", ")
            append(session.schematic.tone.lowercase())
            append(" mood, no text, no UI, no foreground clutter")
        }
    }

    private suspend fun generateText(
        userPrompt: String,
        systemPrompt: String? = null,
        useSummarizer: Boolean,
        maxTokens: Int,
        temperature: Float
    ): String? {
        val config = buildBackendConfig(useSummarizer)
        val client = RemoteSummaryClientFactory.fromConfig(config)
        val response = runCatching {
            client.summarize(
                RemoteSummaryRequest(
                    systemPrompt = systemPrompt ?: "",
                    userPrompt = userPrompt,
                    contextSize = backendContextSize(),
                    maxTokens = maxTokens,
                    temperature = temperature,
                    thinkingEnabled = false
                )
            )
        }.getOrElse { error ->
            DebugLog.log("[Adventure] Backend generation failed: ${error.message}")
            return null
        }
        return cleanAdventureText(response.output)
    }

    private fun buildBackendConfig(useSummarizer: Boolean): RemoteSummaryBackendConfig {
        val backend = SettingsRepository.normalizeOllamaOrLlamaBackend(settingsRepository.adventureBackend.value)
        val baseUrl = when (backend) {
            SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> settingsRepository.adventureLlamaServerUrl.value.trim().trimEnd('/')
            SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> settingsRepository.adventureLlamaSwapUrl.value.trim().trimEnd('/')
            else -> settingsRepository.adventureOllamaUrl.value.trim().trimEnd('/')
        }
        val model = when {
            backend == SettingsRepository.PDF_BACKEND_LLAMA_SERVER ->
                settingsRepository.adventureLlamaServerModelLabel.value?.trim()?.ifBlank { null }
            useSummarizer ->
                settingsRepository.adventureSummarizerModel.value.trim().ifBlank { null }
            else ->
                settingsRepository.adventureModel.value.trim().ifBlank { null }
        }
        return RemoteSummaryBackendConfig(
            backend = backend,
            baseUrl = baseUrl,
            model = model,
            timeoutMinutes = 10,
            context = context,
            liteRtModelId = settingsRepository.adventureLiteRtModelId.value.takeIf { it > 0L },
            liteRtBackend = settingsRepository.adventureLiteRtBackend.value,
            liteRtMtpEnabled = settingsRepository.adventureLiteRtMtpEnabled.value
        )
    }

    private fun backendContextSize(): Int {
        val llamaContext = settingsRepository.adventureLlamaServerContextTokens.value
        return if (SettingsRepository.isLlamaServerBackend(settingsRepository.adventureBackend.value)) {
            llamaContext.takeIf { it > 0 } ?: settingsRepository.adventureOllamaNumCtx.value.coerceAtLeast(4096)
        } else {
            settingsRepository.adventureOllamaNumCtx.value.coerceAtLeast(4096)
        }
    }

    private fun cleanAdventureText(output: String): String {
        val cleaned = PDFSummaryLogic.cleanLlamaOutput(output)
            .replace(Regex("^```(?:json|text)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()

        val extracted = listOf("content", "story", "prompt", "summary", "text").firstNotNullOfOrNull { key ->
            Regex("\"$key\"\\s*:\\s*\"(.*?)\"", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(cleaned)
                ?.groupValues
                ?.getOrNull(1)
        }?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.trim()

        return (extracted ?: cleaned)
            .removePrefix("\"")
            .removeSuffix("\"")
            .trim()
    }

    private suspend fun updateDungeonProgress(petId: String, dungeonType: DungeonType) {
        val progress = database.tamaDao().getDungeonProgress(petId)
        if (progress == null) {
            database.tamaDao().saveDungeonProgress(
                DungeonProgressEntity(
                    petId = petId,
                    completedDungeonCount = 1,
                    lastCompletedDungeonType = dungeonType.name
                )
            )
        } else if (dungeonType.unlockOrder > progress.completedDungeonCount) {
            database.tamaDao().updateDungeonProgress(
                petId,
                progress.completedDungeonCount + 1,
                dungeonType.name
            )
        }
    }

    private fun parseSchematic(response: String): StorySchematic {
        val totalStages = listOf(
            Regex("TOTAL_STAGES:\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("total.?stages.?:?\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(\\d+)\\s*stages", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { regex ->
            regex.find(response)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 10

        val storyThread = Regex(
            "STORY_THREAD:\\s*(.+?)(?=KEY_EVENTS|POSSIBLE_ENDINGS|TONE|DIFFICULTY|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(response)?.groupValues?.get(1)?.trim() ?: response.take(300)

        val keyEvents = Regex(
            "KEY_EVENTS:\\s*(.+?)(?=POSSIBLE_ENDINGS|TONE|DIFFICULTY|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(response)?.groupValues?.get(1)?.trim()?.split(Regex("[\\n•\\-\\d.]+"))
            ?.map { it.trim() }?.filter { it.isNotBlank() && it.length > 3 } ?: listOf("Adventure begins")

        val possibleEndings = Regex(
            "POSSIBLE_ENDINGS:\\s*(.+?)(?=TONE|DIFFICULTY|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(response)?.groupValues?.get(1)?.trim()?.split(Regex("[\\n•\\-\\d.]+"))
            ?.map { it.trim() }?.filter { it.isNotBlank() && it.length > 3 } ?: listOf("Victory", "Defeat")

        val tone = Regex("TONE:\\s*(.+?)(?=DIFFICULTY|$)", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.get(1)?.trim()?.take(50) ?: "Dark Fantasy"

        val difficulty = Regex("DIFFICULTY:\\s*(easy|medium|hard)", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.get(1)?.lowercase() ?: "medium"

        return StorySchematic(
            totalStages = totalStages.coerceIn(5, 30),
            storyThread = storyThread,
            keyEvents = keyEvents.take(5),
            possibleEndings = possibleEndings.take(3),
            tone = tone,
            difficulty = difficulty
        )
    }

    private suspend fun saveSession(session: AdventureSession) {
        val entity = AdventureSessionEntity(
            id = session.id,
            petId = session.petId,
            dungeonType = session.dungeonType.name,
            schematicJson = json.encodeToString(session.schematic),
            currentStage = session.currentStage,
            isCompleted = session.isCompleted,
            cumulativeSummary = session.cumulativeSummary,
            createdAt = session.createdAt,
            lastPlayedAt = session.lastPlayedAt
        )
        database.tamaDao().saveAdventureSession(entity)
        session.stages.forEach { stage ->
            database.tamaDao().saveAdventureStage(
                AdventureStageEntity(
                    id = "${session.id}_${stage.stageNumber}",
                    sessionId = session.id,
                    stageNumber = stage.stageNumber,
                    storyContent = stage.storyContent,
                    userResponse = stage.userResponse,
                    stageSummary = stage.summary,
                    imagePath = stage.imagePath,
                    imagePrompt = stage.imagePrompt,
                    imageNegativePrompt = stage.imageNegativePrompt,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    private fun entityToSession(
        entity: AdventureSessionEntity,
        stageEntities: List<AdventureStageEntity>
    ): AdventureSession {
        val schematic = runCatching {
            json.decodeFromString<StorySchematic>(entity.schematicJson)
        }.getOrDefault(
            StorySchematic(
                totalStages = 10,
                storyThread = "Adventure continues...",
                keyEvents = emptyList(),
                possibleEndings = listOf("Victory", "Defeat"),
                tone = "dark fantasy",
                difficulty = "medium"
            )
        )

        val dungeonType = runCatching {
            DungeonType.valueOf(entity.dungeonType)
        }.getOrDefault(DungeonType.CHAOS_REALM)

        return AdventureSession(
            id = entity.id,
            petId = entity.petId,
            dungeonType = dungeonType,
            schematic = schematic,
            stages = stageEntities.map {
                AdventureStage(
                    stageNumber = it.stageNumber,
                    storyContent = it.storyContent,
                    userResponse = it.userResponse,
                    summary = it.stageSummary,
                    imagePath = it.imagePath,
                    imagePrompt = it.imagePrompt,
                    imageNegativePrompt = it.imageNegativePrompt
                )
            },
            currentStage = entity.currentStage,
            isCompleted = entity.isCompleted,
            cumulativeSummary = entity.cumulativeSummary,
            createdAt = entity.createdAt,
            lastPlayedAt = entity.lastPlayedAt
        )
    }

    private fun dungeonTypeLabel(dungeonType: DungeonType): String = dungeonType.localizedName(context)
}
