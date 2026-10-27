package com.example.llamadroid.tama.adventure

import android.content.Context
import androidx.room.withTransaction
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
import com.example.llamadroid.tama.data.TamaDialogTextCatalog
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.TamaActionGate
import com.example.llamadroid.tama.rpg.AdventureGateCatalog
import com.example.llamadroid.tama.rpg.AdventureGateRepository
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
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
    private val gateRepository by lazy { AdventureGateRepository(database) }
    private val chronicleStore by lazy { AdventureChronicleStore(context) }

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
            val language = storyLanguage()
            val hero = loadHero(petId)
            val sessionId = UUID.randomUUID().toString()
            val visit = if (dungeonType.isRandom) pickChaosVisit(petId, sessionId) else null
            val planned = generateSchematic(dungeonType, hero, language, visit)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.adventure_error_schematic)))
            val schematic = if (visit == null) planned else planned.copy(chaosWorldId = visit.first, cameoMonsterIds = visit.second)

            var session = AdventureSession(
                id = sessionId,
                petId = petId,
                dungeonType = dungeonType,
                schematic = schematic
            )

            onProgress(0.45f, context.getString(R.string.adventure_status_writing_opening_scene))
            val opening = generateStage(session, null, hero, language)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.adventure_error_opening_scene)))
            // The opening hope is the starting hope; only items and flags are taken from the first scene.
            session = session.addStage(opening.stage)
                .withRunState(session.runState.applying(opening.state?.copy(hope = null)))
            // Save the text first so the player can read it while the optional images render.
            saveSession(session)

            session = addImages(session, includeWorld = true, onProgress = onProgress)
            Result.success(session)
        } catch (e: Exception) {
            DebugLog.log("[Adventure] initializeOrContinue failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Applies the player's choice and writes the next scene.
     * The choice is saved before any generation so a failed backend call never loses it;
     * calling again with a blank [playerChoice] retries the saved choice.
     * The scene text is saved before optional images are generated.
     */
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
            var session = entityToSession(sessionEntity, stages)
            if (session.isCompleted) return@withContext Result.success(session)
            if (session.stages.isEmpty()) {
                return@withContext Result.failure(Exception(context.getString(R.string.adventure_error_no_active_session)))
            }

            val pendingChoice = session.stages.last().userResponse?.takeIf(String::isNotBlank)
            val choice = playerChoice.trim().ifBlank { pendingChoice }
                ?: return@withContext Result.failure(Exception(context.getString(R.string.adventure_error_no_active_session)))

            // Persist the choice first so it survives a failed generation.
            session = session.updateLastStageWithResponse(choice)
            saveSession(session)

            if (session.stages.last().summary.isNullOrBlank()) {
                onProgress(0.36f, context.getString(R.string.adventure_status_summarizing_scene))
                val summary = summarizeStage(session.stages.last())
                if (!summary.isNullOrBlank()) {
                    val updatedStages = session.stages.toMutableList()
                    updatedStages[updatedStages.lastIndex] = updatedStages.last().copy(summary = summary)
                    session = session.copy(
                        stages = updatedStages,
                        cumulativeSummary = AdventureTextUtils.appendCappedSummary(session.cumulativeSummary, summary)
                    )
                    saveSession(session)
                }
            }

            val language = storyLanguage()
            val hero = loadHero(petId)
            if (session.currentStage >= session.schematic.totalStages) {
                // Legacy runs that reached the last stage before completion was automatic.
                val outcome = AdventureRewards.outcomeFor(session.runState.hope, reachedFinalScene = true)
                return@withContext Result.success(completeRun(session, hero, language, outcome))
            }

            onProgress(0.72f, context.getString(R.string.adventure_status_writing_next_scene, session.currentStage + 1))
            val isEpilogue = session.currentStage + 1 >= session.schematic.totalStages
            val next = generateStage(
                session, choice, hero, language,
                if (isEpilogue) AdventurePrompts.SceneKind.EPILOGUE else AdventurePrompts.SceneKind.NORMAL
            ) ?: return@withContext Result.failure(Exception(context.getString(R.string.adventure_error_next_scene)))

            // The epilogue never changes hope: the ending was earned by reaching it.
            val newState = session.runState.applying(if (isEpilogue) next.state?.copy(hope = null) else next.state)
            session = session.addStage(next.stage).withRunState(newState)
            saveSession(session)

            when {
                isEpilogue -> {
                    val outcome = AdventureRewards.outcomeFor(newState.hope, reachedFinalScene = true)
                    session = completeRun(session, hero, language, outcome)
                }
                newState.isDepleted -> {
                    session = writeCarriedBackEnding(session, choice, hero, language)
                    session = completeRun(session, hero, language, AdventureOutcome.CARRIED_BACK)
                }
            }
            session = addImages(session, includeWorld = false, onProgress = onProgress)
            Result.success(session)
        } catch (e: Exception) {
            DebugLog.log("[Adventure] submitChoice failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Hope reached 0: add one gentle "carried back" scene (never a death) and shorten the run to end there. */
    private suspend fun writeCarriedBackEnding(
        session: AdventureSession,
        choice: String,
        hero: AdventureHero,
        language: String
    ): AdventureSession {
        val generated = generateStage(session, choice, hero, language, AdventurePrompts.SceneKind.CARRIED_BACK)
        val stageNumber = session.currentStage + 1
        val stage = generated?.stage ?: AdventureStage(
            stageNumber = stageNumber,
            storyContent = AdventurePrompts.fallbackCarriedBackScene(language, hero)
        )
        val ended = session.addStage(stage).let {
            it.copy(schematic = it.schematic.copy(totalStages = stageNumber))
        }
        saveSession(ended)
        return ended
    }

    /**
     * Ends the run: writes the Dream Chronicle page, pays the pet (coins, XP, bond) once and stores the
     * typed [ChronicleGrant] list on the session for the Gate/journal hooks to grant later.
     */
    private suspend fun completeRun(
        session: AdventureSession,
        hero: AdventureHero,
        language: String,
        outcome: AdventureOutcome
    ): AdventureSession {
        val state = session.runState
        val dungeonType = session.dungeonType
        val code = AdventurePrompts.loreCode(language)
        val lore = AdventureLore.loreFor(code)
        val dungeonTitle = lore.dungeon(dungeonType)?.title ?: dungeonType.displayName
        val fallback = AdventurePrompts.fallbackChronicle(language, hero, dungeonTitle, outcome)
        val lastScene = session.stages.lastOrNull()?.let { AdventureSceneParser.parse(it.storyContent).text.take(300) }.orEmpty()
        val storySoFar = AdventureTextUtils.appendCappedSummary(session.cumulativeSummary, lastScene.ifBlank { "-" }, 1200)
        val response = generateText(
            userPrompt = AdventurePrompts.chroniclePrompt(language, hero, dungeonTitle, outcome, state, storySoFar),
            useSummarizer = false,
            maxTokens = 260,
            temperature = 0.7f
        )
        val (title, summary) = response?.let { AdventurePrompts.parseChronicle(it, fallback.first, fallback.second) } ?: fallback

        val reward = AdventureRewards.compute(session.schematic.difficulty, state.hope, outcome, dungeonType)
        val applied = applyPetRewards(session.petId, reward)
        val grants = reward.grants.mapIndexed { index, grant ->
            if (index == 0) grant.copy(detail = lore.dungeon(dungeonType)?.chronicleReward) else grant
        }
        val page = DreamChroniclePage(
            title = title,
            summary = summary,
            outcome = outcome,
            finalHope = state.hope,
            difficulty = session.schematic.difficulty,
            coins = reward.coins,
            xp = reward.xp,
            bond = reward.bond,
            grants = grants,
            rewardsApplied = applied
        )
        val completed = session
            .copy(schematic = session.schematic.copy(chronicle = page))
            .markCompleted()
        saveSession(completed)
        // A carried-back run writes a page but does not unlock the next dungeon.
        if (outcome == AdventureOutcome.VICTORY) updateDungeonProgress(session.petId, dungeonType)
        return applyChronicleGrants(completed)
    }

    /** Retry hook for the results card: grants a finished run's Chronicle rewards if that never succeeded. */
    suspend fun grantPendingChronicle(sessionId: String): AdventureSession? = withContext(Dispatchers.IO) {
        val entity = database.tamaDao().getAdventureSession(sessionId) ?: return@withContext null
        val session = entityToSession(entity, database.tamaDao().getAdventureStages(sessionId))
        if (!session.isCompleted) session else applyChronicleGrants(session)
    }

    /**
     * Grants the Chronicle rewards of a finished run exactly once: resolves them (Gate supply, Nexum fragment,
     * lantern spark, coin fallback), then commits the supplies/coins, the log entry and `grantsApplied` together
     * under the Tama action gate and one DB transaction. A repeated call finds `grantsApplied` and does nothing.
     */
    private suspend fun applyChronicleGrants(session: AdventureSession): AdventureSession {
        val page = session.schematic.chronicle ?: return session
        if (page.grantsApplied) return session
        val dao = database.tamaDao()
        return runCatching {
            val rows = gateRepository.getProgress(session.petId)
            val held = chronicleStore.load(session.petId).entries
                .filter { it.sessionId != session.id }
                .flatMap { entry -> entry.grants.mapNotNull(AdventureChronicleLink::fragmentIdOf) }
                .toSet()
            val resolved = AdventureChronicleLink.resolveGrants(page.grants, session.id, rows, held)
            val updatedPage = page.copy(grants = resolved, grantsApplied = true)
            val updated = session.copy(schematic = session.schematic.copy(chronicle = updatedPage))
            val granted = gateRepository.applyChronicleGrant(
                petId = session.petId,
                supplyIds = resolved.mapNotNull(AdventureChronicleLink::supplyIdOf),
                bonusCoins = resolved.sumOf(AdventureChronicleLink::fallbackCoinsOf)
            ) {
                val entity = dao.getAdventureSession(session.id) ?: return@applyChronicleGrant false
                val stored = runCatching { json.decodeFromString<StorySchematic>(entity.schematicJson) }.getOrNull()
                if (stored?.chronicle?.grantsApplied == true) return@applyChronicleGrant false
                dao.saveAdventureSession(entity.copy(schematicJson = json.encodeToString(updated.schematic)))
                chronicleStore.upsert(
                    session.petId,
                    ChronicleLogEntry(
                        sessionId = session.id,
                        dungeon = session.dungeonType.name,
                        title = page.title,
                        finishedAt = System.currentTimeMillis(),
                        outcome = page.outcome,
                        coins = page.coins,
                        xp = page.xp,
                        bond = page.bond,
                        grants = resolved
                    )
                )
                true
            }
            if (granted) updated else session
        }.getOrElse {
            DebugLog.log("[Adventure] Chronicle grant failed: ${it.message}")
            session
        }
    }

    /** Coins, XP (introspection level) and bond, committed under the Tama action gate. */
    private suspend fun applyPetRewards(petId: String, reward: AdventureRewards.Computation): Boolean {
        if (reward.coins <= 0 && reward.xp <= 0 && reward.bond <= 0) return true
        return runCatching {
            TamaActionGate.run {
                database.withTransaction {
                    val dao = database.tamaDao()
                    val pet = dao.getPet(petId) ?: return@withTransaction false
                    dao.savePet(
                        pet.copy(
                            money = pet.money + reward.coins,
                            introspectionLevel = pet.introspectionLevel + reward.xp,
                            ownerBondLevel = (pet.ownerBondLevel + reward.bond).coerceAtMost(100f)
                        )
                    )
                    true
                }
            }
        }.getOrElse {
            DebugLog.log("[Adventure] Reward grant failed: ${it.message}")
            false
        }
    }

    /** Generates the optional world/stage images after the scene text is already saved. */
    private suspend fun addImages(
        session: AdventureSession,
        includeWorld: Boolean,
        onProgress: (Float, String) -> Unit
    ): AdventureSession {
        var current = session
        if (includeWorld && settingsRepository.adventureWorldImageEnabled.value) {
            onProgress(0.76f, context.getString(R.string.adventure_status_writing_image_prompts))
            val withWorld = tryGenerateWorldImage(current.id, current.dungeonType, current.schematic, onProgress)
            if (withWorld != current.schematic) {
                current = current.copy(schematic = withWorld)
                saveSession(current)
            }
        }
        val stage = current.stages.lastOrNull()
        if (stage != null && settingsRepository.adventureStageImagesEnabled.value) {
            onProgress(0.84f, context.getString(R.string.adventure_status_writing_stage_image_prompts, stage.stageNumber))
            val withImage = tryGenerateStageImage(current, stage, current.dungeonType, onProgress)
            if (withImage != stage) {
                current = current.copy(stages = current.stages.dropLast(1) + withImage)
                saveSession(current)
            }
        }
        return current
    }

    suspend fun resetAdventure(petId: String, dungeonType: DungeonType) = withContext(Dispatchers.IO) {
        val existingSession = database.tamaDao().getLatestAdventureSessionForDungeon(petId, dungeonType.name)
        if (existingSession != null) {
            database.tamaDao().deleteAdventureStages(existingSession.id)
            database.tamaDao().deleteAdventureSession(existingSession.id)
            deleteSessionImages(existingSession.id)
        }
    }

    private fun deleteSessionImages(sessionId: String) {
        val root = File(context.filesDir, "adventure_worlds")
        runCatching {
            File(root, "$sessionId.png").delete()
            File(root, sessionId).deleteRecursively()
        }.onFailure { DebugLog.log("[Adventure] Failed to delete session images: ${it.message}") }
    }

    /** Chaos Realm: an unlocked Gate world (seeded by the session id) and its cameo monster ids, or null. */
    private suspend fun pickChaosVisit(petId: String, sessionId: String): Pair<String, List<String>>? {
        val rows = runCatching { gateRepository.getProgress(petId) }.getOrNull().orEmpty()
        val worldId = AdventureChronicleLink.pickChaosWorld(sessionId, AdventureChronicleLink.unlockedWorldIds(rows)) ?: return null
        return worldId to AdventureChronicleLink.cameoMonsterIds(worldId, sessionId)
    }

    /** Lore, landmarks, inhabitants and localized cameo names of the Gate world a Chaos run visits. */
    private fun gateVisitBlock(worldId: String?, cameoIds: List<String>, language: String): String? {
        if (worldId == null) return null
        val world = AdventureLore.loreFor(AdventurePrompts.loreCode(language)).world(worldId) ?: return null
        val names = cameoIds.mapNotNull { id ->
            runCatching {
                TamaDialogTextCatalog.localizedResource(context, AdventureGateCatalog.monster(id).nameRes)
            }.getOrNull()
        }
        return AdventureChronicleLink.chaosBlock(world, names)
    }

    private suspend fun generateSchematic(
        dungeonType: DungeonType,
        hero: AdventureHero,
        language: String,
        visit: Pair<String, List<String>>? = null
    ): StorySchematic? {
        val response = generateText(
            userPrompt = AdventurePrompts.plannerPrompt(
                language, dungeonType, hero,
                gateVisit = gateVisitBlock(visit?.first, visit?.second.orEmpty(), language)
            ),
            useSummarizer = false,
            maxTokens = 700,
            temperature = 0.7f
        ) ?: return null
        return parseSchematic(response)
    }

    private class GeneratedScene(val stage: AdventureStage, val state: SceneStateUpdate?)

    private suspend fun generateStage(
        session: AdventureSession,
        playerChoice: String?,
        hero: AdventureHero,
        language: String,
        kind: AdventurePrompts.SceneKind = AdventurePrompts.SceneKind.NORMAL
    ): GeneratedScene? {
        val systemPrompt = systemPromptFor(language, session.dungeonType)
        val stageNumber = session.currentStage + 1
        val totalStages = maxOf(session.schematic.totalStages, stageNumber)
        val sceneBlocks = session.stages.takeLast(3).map { stage ->
            buildString {
                appendLine("Scene ${stage.stageNumber}: ${AdventureSceneParser.parse(stage.storyContent).text}")
                stage.userResponse?.takeIf(String::isNotBlank)?.let {
                    appendLine("Player response: $it")
                }
                stage.summary?.takeIf(String::isNotBlank)?.let {
                    appendLine("Scene summary: $it")
                }
            }.trim()
        }

        val header = AdventurePrompts.sceneHeader(
            language = language,
            dungeon = session.dungeonType,
            schematic = session.schematic,
            hero = hero,
            state = session.runState,
            dungeonLabel = dungeonTitle(session.dungeonType, language),
            gateVisit = gateVisitBlock(session.schematic.chaosWorldId, session.schematic.cameoMonsterIds, language)
        )
        val choiceBlock = if (!playerChoice.isNullOrBlank()) {
            "LATEST PLAYER CHOICE:\n${playerChoice.trim()}\n\n"
        } else {
            ""
        }
        val footer = AdventurePrompts.sceneFooter(language, stageNumber, totalStages, kind)

        val maxOutputTokens = 1100
        val budget = AdventureTextUtils.historyBudgetChars(
            contextTokens = backendContextSize(),
            maxOutputTokens = maxOutputTokens,
            fixedChars = systemPrompt.length + header.length + choiceBlock.length + footer.length + 64
        )
        val (fittedSummary, fittedScenes) = AdventureTextUtils.fitHistory(
            summary = session.cumulativeSummary,
            scenes = sceneBlocks,
            budgetChars = budget
        )

        val userPrompt = buildString {
            append(header)
            if (fittedSummary.isNotBlank()) {
                appendLine("STORY SO FAR:")
                appendLine(fittedSummary.trim())
                appendLine()
            }
            if (fittedScenes.isNotEmpty()) {
                appendLine("RECENT SCENES:")
                appendLine(fittedScenes.joinToString("\n\n"))
                appendLine()
            }
            append(choiceBlock)
            append(footer)
        }

        val response = generateText(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            useSummarizer = false,
            maxTokens = maxOutputTokens,
            temperature = 0.85f
        ) ?: return null

        val parsed = AdventureSceneParser.parse(response)
        if (parsed.text.isBlank()) return null
        return GeneratedScene(
            stage = AdventureStage(
                stageNumber = stageNumber,
                // The raw reply is stored; the UI and later prompts re-parse it (scene, choices, state).
                storyContent = response,
                userResponse = null,
                summary = null
            ),
            state = parsed.state
        )
    }

    private suspend fun summarizeStage(stage: AdventureStage): String? {
        val prompt = """
            ${settingsRepository.adventureSummarizerPrompt.value.ifBlank { SettingsRepository.FALLBACK_ADVENTURE_SUMMARIZER_PROMPT }}

            Language: ${storyLanguage()}

            Scene text:
            ${AdventureSceneParser.parse(stage.storyContent).text}

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

    /** The story language: explicit settings choices win; Auto follows the app language (EN/ES). */
    private fun storyLanguage(): String =
        AdventurePrompts.resolveLanguage(settingsRepository.adventureLanguage.value, appLanguageCode())

    private fun appLanguageCode(): String {
        val selected = context.getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
            .getString("selected_language", "system") ?: "system"
        return if (selected == "system") Locale.getDefault().language else selected
    }

    /** A custom (non-default) system prompt overrides the generated lore prompt; blank or legacy = default. */
    private fun systemPromptFor(language: String, dungeonType: DungeonType): String {
        val stored = settingsRepository.adventureSystemPrompt.value
        return if (AdventurePrompts.isDefaultSystemPrompt(stored, SettingsRepository.FALLBACK_ADVENTURE_SYSTEM_PROMPT)) {
            AdventurePrompts.systemPrompt(language, dungeonType)
        } else {
            stored
        }
    }

    private suspend fun loadHero(petId: String): AdventureHero {
        val dao = database.tamaDao()
        val pet = dao.getPet(petId)
        val gate = runCatching { dao.getAdventureGateProfile(petId) }.getOrNull()
        return pet?.let { AdventurePrompts.heroFrom(it, gate) } ?: AdventureHero(
            name = "the pet",
            species = "pet",
            stage = "child",
            personality = "curious",
            personalityNote = "",
            mood = "neutral"
        )
    }

    private fun dungeonTitle(type: DungeonType, language: String): String =
        AdventureLore.loreFor(AdventurePrompts.loreCode(language)).dungeon(type)?.title ?: dungeonTypeLabel(type)

    private suspend fun tryGenerateWorldImage(
        sessionId: String,
        dungeonType: DungeonType,
        schematic: StorySchematic,
        onProgress: (Float, String) -> Unit
    ): StorySchematic {
        if (!hasAdventureImageHeapHeadroom("world")) {
            onProgress(0.78f, context.getString(R.string.adventure_status_image_skipped_low_memory))
            return schematic
        }
        val model = resolveAdventureImageModel() ?: return schematic

        // Deterministic prompts: fixed style prefix + the dungeon's world prompt, static negative prompt.
        val positivePrompt = AdventurePrompts.imagePrompt(dungeonType, null)
        val negativePrompt = AdventurePrompts.IMAGE_NEGATIVE_PROMPT

        return runCatching {
            onProgress(0.8f, context.getString(R.string.adventure_status_generating_world_image))
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
                    seed = AdventureTextUtils.sessionSeed(sessionId),
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
        val sceneText = AdventureSceneParser.parse(stage.storyContent).text
        // One short call turns the (possibly Spanish) scene into an English visual detail; the style
        // prefix and the dungeon world prompt are fixed, and the negative prompt is static.
        val detail = generateText(
            systemPrompt = adventureImageDetailSystem(),
            userPrompt = sceneText.take(900),
            useSummarizer = false,
            maxTokens = 120,
            temperature = 0.5f
        )?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        val positivePrompt = AdventurePrompts.imagePrompt(dungeonType, detail)
        val negativePrompt = AdventurePrompts.IMAGE_NEGATIVE_PROMPT

        return runCatching {
            onProgress(0.88f, context.getString(R.string.adventure_status_generating_stage_image, stage.stageNumber))
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
                    seed = AdventureTextUtils.sessionSeed(session.id),
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

    private fun adventureImageDetailSystem(): String = """
        You describe one story scene as a short English visual detail for a background image.
        Return a single line of at most 30 words: the location, the key objects and the mood.
        No style words, no character names, no quotes, no labels.
    """.trimIndent()

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

    private fun cleanAdventureText(output: String): String =
        AdventureTextUtils.cleanOutput(PDFSummaryLogic.cleanLlamaOutput(output))

    private suspend fun updateDungeonProgress(petId: String, dungeonType: DungeonType) {
        // The Chaos Realm (unlockOrder 0) is always open and never unlocks another dungeon.
        if (dungeonType.unlockOrder <= 0) return
        val progress = database.tamaDao().getDungeonProgress(petId)
        val completed = progress?.completedDungeonCount ?: 0
        if (dungeonType.unlockOrder > completed) {
            database.tamaDao().saveDungeonProgress(
                DungeonProgressEntity(
                    petId = petId,
                    completedDungeonCount = dungeonType.unlockOrder,
                    lastCompletedDungeonType = dungeonType.name
                )
            )
        }
    }

    private fun parseSchematic(response: String): StorySchematic = AdventureTextUtils.parseSchematic(response)

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