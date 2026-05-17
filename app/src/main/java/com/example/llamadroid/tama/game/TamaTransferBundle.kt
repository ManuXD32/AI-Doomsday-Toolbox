package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.adventure.StorySchematic
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.TamaArtworkEntity
import com.example.llamadroid.tama.db.AdventureSessionEntity
import com.example.llamadroid.tama.db.AdventureStageEntity
import com.example.llamadroid.tama.db.AdventureGateBattleStateEntity
import com.example.llamadroid.tama.db.AdventureGateProfileEntity
import com.example.llamadroid.tama.db.AdventureGateWorldProgressEntity
import com.example.llamadroid.tama.db.DungeonProgressEntity
import com.example.llamadroid.tama.db.FarmLivestockEntity
import com.example.llamadroid.tama.db.FarmTileEntity
import com.example.llamadroid.tama.db.FarmUpgradeEntity
import com.example.llamadroid.tama.db.TamaChatMessageEntity
import com.example.llamadroid.tama.db.TamaEventEntity
import com.example.llamadroid.tama.db.TamaQuestChecklistItemEntity
import com.example.llamadroid.tama.db.TamaQuestEntity
import com.example.llamadroid.tama.db.TamaStudyLabelEntity
import com.example.llamadroid.tama.db.TamaStudySessionEntity
import com.example.llamadroid.tama.db.TamaSummaryEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val TAMA_TRANSFER_VERSION = 18

@Serializable
data class TamaTransferBundle(
    val version: Int = TAMA_TRANSFER_VERSION,
    val exportDate: Long = System.currentTimeMillis(),
    val petAgeMillis: Long = 0L,
    val pet: TamaPet,
    val artworks: List<TamaTransferArtwork> = emptyList(),
    val events: List<TamaTransferEvent> = emptyList(),
    val chatMessages: List<TamaTransferChatMessage> = emptyList(),
    val summaries: List<TamaTransferSummary> = emptyList(),
    val settings: TamaTransferSettings? = null,
    val farmTiles: List<TamaTransferFarmTile> = emptyList(),
    val farmUpgrades: List<TamaTransferFarmUpgrade> = emptyList(),
    val farmLivestock: List<TamaTransferFarmLivestock> = emptyList(),
    val quests: List<TamaTransferQuest> = emptyList(),
    val questChecklist: List<TamaTransferQuestChecklistItem> = emptyList(),
    val deepDreamRuns: List<TamaTransferDeepDreamRun> = emptyList(),
    val studyLabels: List<TamaTransferStudyLabel> = emptyList(),
    val studySessions: List<TamaTransferStudySession> = emptyList(),
    val adventureSessions: List<TamaTransferAdventureSession> = emptyList(),
    val adventureStages: List<TamaTransferAdventureStage> = emptyList(),
    val dungeonProgress: TamaTransferDungeonProgress? = null,
    val adventureGateProfile: TamaTransferAdventureGateProfile? = null,
    val adventureGateWorldProgress: List<TamaTransferAdventureGateWorldProgress> = emptyList(),
    val adventureGateBattleState: TamaTransferAdventureGateBattleState? = null
)

@Serializable
data class TamaTransferSettings(
    val tamaNormalDreamingEnabled: Boolean = true,
    val tamaDeepDreamingEnabled: Boolean = true,
    val tamaDeepDreamRetryCount: Int = 3,
    val tamaDeepDreamDesiredLanguage: String = "English",
    val tamaSchoolPaintingEnabled: Boolean = true,
    val tamaPicGenModelFilename: String? = null,
    val tamaPicGenResolution: Int = 512,
    val tamaWhisperModelPath: String? = null,
    val tamaWhisperLanguage: String = "auto",
    val tamaChatImageInputEnabled: Boolean = false,
    val tamaBackend: String = "OLLAMA",
    val tamaThinkingEnabled: Boolean = true,
    val tamaLlamaServerUrl: String = "",
    val tamaLlamaSwapUrl: String = "",
    val tamaLlamaServerModelLabel: String? = null,
    val tamaLlamaServerContextTokens: Int = -1,
    val tamaLlamaServerContextLabel: String? = null,
    val tamaPetModel: String = "qwen3.5:9b",
    val tamaSummarizerModel: String = "qwen3.5:9b",
    val tamaPetPrompt: String = "",
    val tamaSummarizerPrompt: String = "",
    val tamaOllamaUrl: String = "",
    val tamaOllamaMmap: Boolean = false,
    val tamaOllamaThreads: Int = 4,
    val tamaOllamaNumCtx: Int = 16384,
    val adventureModel: String = "qwen3.5:9b",
    val adventureSummarizerModel: String = "qwen3.5:9b",
    val adventureSystemPrompt: String = "",
    val adventureSummarizerPrompt: String = "",
    val adventureOllamaUrl: String = "",
    val adventureOllamaMmap: Boolean = false,
    val adventureOllamaThreads: Int = 4,
    val adventureOllamaNumCtx: Int = 16384,
    val adventureLanguage: String = "English",
    val adventureBackend: String = "OLLAMA",
    val adventureLlamaServerUrl: String = "",
    val adventureLlamaSwapUrl: String = "",
    val adventureLlamaServerModelLabel: String? = null,
    val adventureLlamaServerContextTokens: Int = -1,
    val adventureLlamaServerContextLabel: String? = null,
    val adventureWorldImageEnabled: Boolean = false,
    val adventureStageImagesEnabled: Boolean = false,
    val adventureOnnxModelFilename: String? = null,
    val adventureOnnxSteps: Int = 20,
    val adventureOnnxCfg: Float = 6.5f,
    val adventureOnnxResolution: Int = 512
)

@Serializable
data class TamaTransferArtwork(
    val id: String,
    val petId: String,
    val kind: String,
    val status: String,
    val title: String,
    val prompt: String,
    val negativePrompt: String,
    val modelFilename: String,
    val modelLabel: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Float,
    val seed: Long? = null,
    val sourceActivity: String? = null,
    val albumId: String? = null,
    val albumIndex: Int = 0,
    val albumDate: String? = null,
    val albumSummary: String? = null,
    val relativeFilePath: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null
) {
    fun toEntity(filePath: String? = null) = TamaArtworkEntity(
        id = id,
        petId = petId,
        kind = kind,
        status = status,
        title = title,
        prompt = prompt,
        negativePrompt = negativePrompt,
        modelFilename = modelFilename,
        modelLabel = modelLabel,
        width = width,
        height = height,
        steps = steps,
        cfgScale = cfgScale,
        seed = seed,
        sourceActivity = sourceActivity,
        albumId = albumId,
        albumIndex = albumIndex,
        albumDate = albumDate,
        albumSummary = albumSummary,
        filePath = filePath ?: relativeFilePath,
        errorMessage = errorMessage,
        createdAt = createdAt,
        startedAt = startedAt,
        completedAt = completedAt
    )

    companion object {
        fun fromEntity(entity: TamaArtworkEntity, relativeFilePath: String? = null) = TamaTransferArtwork(
            id = entity.id,
            petId = entity.petId,
            kind = entity.kind,
            status = entity.status,
            title = entity.title,
            prompt = entity.prompt,
            negativePrompt = entity.negativePrompt,
            modelFilename = entity.modelFilename,
            modelLabel = entity.modelLabel,
            width = entity.width,
            height = entity.height,
            steps = entity.steps,
            cfgScale = entity.cfgScale,
            seed = entity.seed,
            sourceActivity = entity.sourceActivity,
            albumId = entity.albumId,
            albumIndex = entity.albumIndex,
            albumDate = entity.albumDate,
            albumSummary = entity.albumSummary,
            relativeFilePath = relativeFilePath,
            errorMessage = entity.errorMessage,
            createdAt = entity.createdAt,
            startedAt = entity.startedAt,
            completedAt = entity.completedAt
        )
    }
}

@Serializable
data class TamaTransferEvent(
    val id: String,
    val timestamp: Long,
    val petId: String,
    val eventType: String,
    val details: String,
    val locationId: String? = null,
    val npcId: String? = null,
    val statsChangeJson: String? = null
) {
    fun toEntity() = TamaEventEntity(id, timestamp, petId, eventType, details, locationId, npcId, statsChangeJson)

    companion object {
        fun fromEntity(entity: TamaEventEntity) = TamaTransferEvent(
            id = entity.id,
            timestamp = entity.timestamp,
            petId = entity.petId,
            eventType = entity.eventType,
            details = entity.details,
            locationId = entity.locationId,
            npcId = entity.npcId,
            statsChangeJson = entity.statsChangeJson
        )
    }
}

@Serializable
data class TamaTransferChatMessage(
    val id: String,
    val petId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val thinking: String? = null,
    val audioPath: String? = null,
    val audioDurationMs: Long? = null,
    val relativeAudioPath: String? = null,
    val imagePath: String? = null,
    val relativeImagePath: String? = null,
    val transcriptionStatus: String? = null,
    val transcribedText: String? = null,
    val transcriptionError: String? = null
) {
    fun toEntity(
        audioFilePath: String? = audioPath,
        imageFilePath: String? = imagePath
    ) = TamaChatMessageEntity(
        id = id,
        petId = petId,
        role = role,
        content = content,
        timestamp = timestamp,
        thinking = thinking,
        audioPath = audioFilePath,
        audioDurationMs = audioDurationMs,
        imagePath = imageFilePath,
        transcriptionStatus = transcriptionStatus,
        transcribedText = transcribedText,
        transcriptionError = transcriptionError
    )

    companion object {
        fun fromEntity(
            entity: TamaChatMessageEntity,
            relativeAudioPath: String? = null,
            relativeImagePath: String? = null
        ) = TamaTransferChatMessage(
            id = entity.id,
            petId = entity.petId,
            role = entity.role,
            content = entity.content,
            timestamp = entity.timestamp,
            thinking = entity.thinking,
            audioPath = entity.audioPath,
            audioDurationMs = entity.audioDurationMs,
            relativeAudioPath = relativeAudioPath,
            imagePath = entity.imagePath,
            relativeImagePath = relativeImagePath,
            transcriptionStatus = entity.transcriptionStatus,
            transcribedText = entity.transcribedText,
            transcriptionError = entity.transcriptionError
        )
    }
}

@Serializable
data class TamaTransferSummary(
    val id: String,
    val petId: String,
    val date: String,
    val summary: String,
    val shortTermSummary: String = "",
    val longTermSummary: String = "",
    val retrievalNotesJson: String = "[]",
    val createdAt: Long,
    val lastEventTimestamp: Long = 0L,
    val lastChatMessageTimestamp: Long = 0L
) {
    fun toEntity() = TamaSummaryEntity(
        id = id,
        petId = petId,
        date = date,
        summary = summary,
        shortTermSummary = shortTermSummary,
        longTermSummary = longTermSummary,
        retrievalNotesJson = retrievalNotesJson,
        createdAt = createdAt,
        lastEventTimestamp = lastEventTimestamp,
        lastChatMessageTimestamp = lastChatMessageTimestamp
    )

    companion object {
        fun fromEntity(entity: TamaSummaryEntity) = TamaTransferSummary(
            id = entity.id,
            petId = entity.petId,
            date = entity.date,
            summary = entity.summary,
            shortTermSummary = entity.shortTermSummary,
            longTermSummary = entity.longTermSummary,
            retrievalNotesJson = entity.retrievalNotesJson,
            createdAt = entity.createdAt,
            lastEventTimestamp = entity.lastEventTimestamp,
            lastChatMessageTimestamp = entity.lastChatMessageTimestamp
        )
    }
}

@Serializable
data class TamaTransferDeepDreamRun(
    val id: String,
    val petId: String,
    val signature: String,
    val dreamDate: String,
    val status: String,
    val stage: String,
    val albumId: String? = null,
    val ownsLocalLlama: Boolean = false,
    val startedAt: Long,
    val updatedAt: Long,
    val lastHeartbeatAt: Long,
    val errorMessage: String? = null
) {
    fun toEntity() = com.example.llamadroid.tama.db.TamaDeepDreamRunEntity(
        id = id,
        petId = petId,
        signature = signature,
        dreamDate = dreamDate,
        status = status,
        stage = stage,
        albumId = albumId,
        ownsLocalLlama = ownsLocalLlama,
        startedAt = startedAt,
        updatedAt = updatedAt,
        lastHeartbeatAt = lastHeartbeatAt,
        errorMessage = errorMessage
    )

    companion object {
        fun fromEntity(entity: com.example.llamadroid.tama.db.TamaDeepDreamRunEntity) = TamaTransferDeepDreamRun(
            id = entity.id,
            petId = entity.petId,
            signature = entity.signature,
            dreamDate = entity.dreamDate,
            status = entity.status,
            stage = entity.stage,
            albumId = entity.albumId,
            ownsLocalLlama = entity.ownsLocalLlama,
            startedAt = entity.startedAt,
            updatedAt = entity.updatedAt,
            lastHeartbeatAt = entity.lastHeartbeatAt,
            errorMessage = entity.errorMessage
        )
    }
}

@Serializable
data class TamaTransferFarmTile(
    val id: Int,
    val petId: String,
    val status: String,
    val cropJson: String? = null,
    val lastWateredTime: Long? = null
) {
    fun toEntity() = FarmTileEntity(id, petId, status, cropJson, lastWateredTime)

    companion object {
        fun fromEntity(entity: FarmTileEntity) = TamaTransferFarmTile(
            id = entity.id,
            petId = entity.petId,
            status = entity.status,
            cropJson = entity.cropJson,
            lastWateredTime = entity.lastWateredTime
        )
    }
}

@Serializable
data class TamaTransferFarmUpgrade(
    val type: String,
    val petId: String,
    val isPurchased: Boolean = false,
    val level: Int = 1,
    val lastProductionTime: Long = System.currentTimeMillis(),
    val storedOutput: Int = 0,
    val extraDataJson: String? = null
) {
    fun toEntity() = FarmUpgradeEntity(type, petId, isPurchased, level, lastProductionTime, storedOutput, extraDataJson)

    companion object {
        fun fromEntity(entity: FarmUpgradeEntity) = TamaTransferFarmUpgrade(
            type = entity.type,
            petId = entity.petId,
            isPurchased = entity.isPurchased,
            level = entity.level,
            lastProductionTime = entity.lastProductionTime,
            storedOutput = entity.storedOutput,
            extraDataJson = entity.extraDataJson
        )
    }
}

@Serializable
data class TamaTransferFarmLivestock(
    val petId: String,
    val type: String,
    val slotsJson: String
) {
    fun toEntity() = FarmLivestockEntity(
        petId = petId,
        type = type,
        slotsJson = slotsJson
    )

    companion object {
        fun fromEntity(entity: FarmLivestockEntity) = TamaTransferFarmLivestock(
            petId = entity.petId,
            type = entity.type,
            slotsJson = entity.slotsJson
        )
    }
}

@Serializable
data class TamaTransferQuest(
    val id: String,
    val petId: String,
    val status: String,
    val generatedDateKey: String,
    val acceptedAt: Long? = null,
    val expiresAt: Long? = null,
    val completedAt: Long? = null,
    val npcId: String,
    val requestsJson: String,
    val rewardCoins: Long,
    val summaryJson: String
) {
    fun toEntity() = TamaQuestEntity(
        id = id,
        petId = petId,
        status = status,
        generatedDateKey = generatedDateKey,
        acceptedAt = acceptedAt,
        expiresAt = expiresAt,
        completedAt = completedAt,
        npcId = npcId,
        requestsJson = requestsJson,
        rewardCoins = rewardCoins,
        summaryJson = summaryJson
    )

    companion object {
        fun fromEntity(entity: TamaQuestEntity) = TamaTransferQuest(
            id = entity.id,
            petId = entity.petId,
            status = entity.status,
            generatedDateKey = entity.generatedDateKey,
            acceptedAt = entity.acceptedAt,
            expiresAt = entity.expiresAt,
            completedAt = entity.completedAt,
            npcId = entity.npcId,
            requestsJson = entity.requestsJson,
            rewardCoins = entity.rewardCoins,
            summaryJson = entity.summaryJson
        )
    }
}

@Serializable
data class TamaTransferQuestChecklistItem(
    val id: String,
    val petId: String,
    val itemId: String,
    val quantity: Int,
    val checked: Boolean = false,
    val sourceQuestIdsJson: String = "[]",
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toEntity() = TamaQuestChecklistItemEntity(
        id = id,
        petId = petId,
        itemId = itemId,
        quantity = quantity,
        checked = checked,
        sourceQuestIdsJson = sourceQuestIdsJson,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromEntity(entity: TamaQuestChecklistItemEntity) = TamaTransferQuestChecklistItem(
            id = entity.id,
            petId = entity.petId,
            itemId = entity.itemId,
            quantity = entity.quantity,
            checked = entity.checked,
            sourceQuestIdsJson = entity.sourceQuestIdsJson,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }
}

@Serializable
data class TamaTransferStudyLabel(
    val id: String,
    val petId: String,
    val name: String,
    val createdAt: Long,
    val lastUsedAt: Long
) {
    fun toEntity() = TamaStudyLabelEntity(
        id = id,
        petId = petId,
        name = name,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt
    )

    companion object {
        fun fromEntity(entity: TamaStudyLabelEntity) = TamaTransferStudyLabel(
            id = entity.id,
            petId = entity.petId,
            name = entity.name,
            createdAt = entity.createdAt,
            lastUsedAt = entity.lastUsedAt
        )
    }
}

@Serializable
data class TamaTransferStudySession(
    val id: String,
    val petId: String,
    val mode: String,
    val status: String,
    val labelIdsJson: String,
    val labelNamesSnapshotJson: String,
    val focusMinutes: Int,
    val shortBreakMinutes: Int,
    val longBreakMinutes: Int,
    val roundsPlanned: Int,
    val currentRound: Int,
    val currentPhase: String,
    val phaseStartedAt: Long? = null,
    val phaseEndsAt: Long? = null,
    val focusAccumulatedMs: Long,
    val restAccumulatedMs: Long,
    val educationAwarded: Float,
    val startedAt: Long,
    val completedAt: Long? = null,
    val stoppedAt: Long? = null,
    val lastUpdatedAt: Long
) {
    fun toEntity() = TamaStudySessionEntity(
        id = id,
        petId = petId,
        mode = mode,
        status = status,
        labelIdsJson = labelIdsJson,
        labelNamesSnapshotJson = labelNamesSnapshotJson,
        focusMinutes = focusMinutes,
        shortBreakMinutes = shortBreakMinutes,
        longBreakMinutes = longBreakMinutes,
        roundsPlanned = roundsPlanned,
        currentRound = currentRound,
        currentPhase = currentPhase,
        phaseStartedAt = phaseStartedAt,
        phaseEndsAt = phaseEndsAt,
        focusAccumulatedMs = focusAccumulatedMs,
        restAccumulatedMs = restAccumulatedMs,
        educationAwarded = educationAwarded,
        startedAt = startedAt,
        completedAt = completedAt,
        stoppedAt = stoppedAt,
        lastUpdatedAt = lastUpdatedAt
    )

    companion object {
        fun fromEntity(entity: TamaStudySessionEntity) = TamaTransferStudySession(
            id = entity.id,
            petId = entity.petId,
            mode = entity.mode,
            status = entity.status,
            labelIdsJson = entity.labelIdsJson,
            labelNamesSnapshotJson = entity.labelNamesSnapshotJson,
            focusMinutes = entity.focusMinutes,
            shortBreakMinutes = entity.shortBreakMinutes,
            longBreakMinutes = entity.longBreakMinutes,
            roundsPlanned = entity.roundsPlanned,
            currentRound = entity.currentRound,
            currentPhase = entity.currentPhase,
            phaseStartedAt = entity.phaseStartedAt,
            phaseEndsAt = entity.phaseEndsAt,
            focusAccumulatedMs = entity.focusAccumulatedMs,
            restAccumulatedMs = entity.restAccumulatedMs,
            educationAwarded = entity.educationAwarded,
            startedAt = entity.startedAt,
            completedAt = entity.completedAt,
            stoppedAt = entity.stoppedAt,
            lastUpdatedAt = entity.lastUpdatedAt
        )
    }
}

@Serializable
data class TamaTransferAdventureSession(
    val id: String,
    val petId: String,
    val dungeonType: String,
    val schematicJson: String,
    val relativeWorldImagePath: String? = null,
    val currentStage: Int,
    val isCompleted: Boolean,
    val cumulativeSummary: String,
    val createdAt: Long,
    val lastPlayedAt: Long
) {
    fun toEntity(restoredWorldImagePath: String? = null) = AdventureSessionEntity(
        id = id,
        petId = petId,
        dungeonType = dungeonType,
        schematicJson = patchSchematicWorldImagePath(
            schematicJson,
            restoredWorldImagePath ?: decodeSchematicWorldImagePath(schematicJson)
        ),
        currentStage = currentStage,
        isCompleted = isCompleted,
        cumulativeSummary = cumulativeSummary,
        createdAt = createdAt,
        lastPlayedAt = lastPlayedAt
    )

    companion object {
        fun fromEntity(
            entity: AdventureSessionEntity,
            relativeWorldImagePath: String? = null
        ) = TamaTransferAdventureSession(
            id = entity.id,
            petId = entity.petId,
            dungeonType = entity.dungeonType,
            schematicJson = entity.schematicJson,
            relativeWorldImagePath = relativeWorldImagePath,
            currentStage = entity.currentStage,
            isCompleted = entity.isCompleted,
            cumulativeSummary = entity.cumulativeSummary,
            createdAt = entity.createdAt,
            lastPlayedAt = entity.lastPlayedAt
        )
    }
}

@Serializable
data class TamaTransferAdventureStage(
    val id: String,
    val sessionId: String,
    val stageNumber: Int,
    val storyContent: String,
    val userResponse: String? = null,
    val stageSummary: String? = null,
    val imagePath: String? = null,
    val relativeImagePath: String? = null,
    val imagePrompt: String? = null,
    val imageNegativePrompt: String? = null,
    val timestamp: Long
) {
    fun toEntity(restoredImagePath: String? = null) = AdventureStageEntity(
        id = id,
        sessionId = sessionId,
        stageNumber = stageNumber,
        storyContent = storyContent,
        userResponse = userResponse,
        stageSummary = stageSummary,
        imagePath = restoredImagePath ?: imagePath,
        imagePrompt = imagePrompt,
        imageNegativePrompt = imageNegativePrompt,
        timestamp = timestamp
    )

    companion object {
        fun fromEntity(
            entity: AdventureStageEntity,
            relativeImagePath: String? = null
        ) = TamaTransferAdventureStage(
            id = entity.id,
            sessionId = entity.sessionId,
            stageNumber = entity.stageNumber,
            storyContent = entity.storyContent,
            userResponse = entity.userResponse,
            stageSummary = entity.stageSummary,
            imagePath = entity.imagePath,
            relativeImagePath = relativeImagePath,
            imagePrompt = entity.imagePrompt,
            imageNegativePrompt = entity.imageNegativePrompt,
            timestamp = entity.timestamp
        )
    }
}

@Serializable
data class TamaTransferDungeonProgress(
    val petId: String,
    val completedDungeonCount: Int = 0,
    val lastCompletedDungeonType: String? = null
) {
    fun toEntity() = DungeonProgressEntity(petId, completedDungeonCount, lastCompletedDungeonType)

    companion object {
        fun fromEntity(entity: DungeonProgressEntity) = TamaTransferDungeonProgress(
            petId = entity.petId,
            completedDungeonCount = entity.completedDungeonCount,
            lastCompletedDungeonType = entity.lastCompletedDungeonType
        )
    }
}

@Serializable
data class TamaTransferAdventureGateProfile(
    val petId: String,
    val level: Int = 1,
    val xp: Int = 0,
    val maxHp: Int = 120,
    val maxMana: Int = 40,
    val attack: Int = 18,
    val magic: Int = 14,
    val defense: Int = 10,
    val speed: Int = 10,
    val accuracy: Int = 100,
    val evasion: Int = 5,
    val currentHp: Int = maxHp,
    val currentMana: Int = maxMana,
    val skillPoints: Int = 0,
    val purchasedSkillIdsJson: String = """["paw_strike","spark","guard"]""",
    val learnedAttackIdsJson: String = """["paw_strike"]""",
    val equippedAttackIdsJson: String = """["paw_strike"]""",
    val learnedMagicIdsJson: String = """["spark","guard"]""",
    val equippedMagicIdsJson: String = """["spark","guard"]""",
    val equippedWeaponId: String? = null,
    val equippedShieldId: String? = null,
    val equippedRingId: String? = null,
    val equippedRelicId: String? = null,
    val lastRecoveryAt: Long = 0L,
    val updatedAt: Long
) {
    fun toEntity() = AdventureGateProfileEntity(
        petId = petId,
        level = level,
        xp = xp,
        maxHp = maxHp,
        maxMana = maxMana,
        attack = attack,
        magic = magic,
        defense = defense,
        speed = speed,
        accuracy = accuracy,
        evasion = evasion,
        currentHp = currentHp,
        currentMana = currentMana,
        skillPoints = skillPoints,
        purchasedSkillIdsJson = purchasedSkillIdsJson,
        learnedAttackIdsJson = learnedAttackIdsJson,
        equippedAttackIdsJson = equippedAttackIdsJson,
        learnedMagicIdsJson = learnedMagicIdsJson,
        equippedMagicIdsJson = equippedMagicIdsJson,
        equippedWeaponId = equippedWeaponId,
        equippedShieldId = equippedShieldId,
        equippedRingId = equippedRingId,
        equippedRelicId = equippedRelicId,
        lastRecoveryAt = lastRecoveryAt.takeIf { it > 0L } ?: updatedAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromEntity(entity: AdventureGateProfileEntity) = TamaTransferAdventureGateProfile(
            petId = entity.petId,
            level = entity.level,
            xp = entity.xp,
            maxHp = entity.maxHp,
            maxMana = entity.maxMana,
            attack = entity.attack,
            magic = entity.magic,
            defense = entity.defense,
            speed = entity.speed,
            accuracy = entity.accuracy,
            evasion = entity.evasion,
            currentHp = entity.currentHp,
            currentMana = entity.currentMana,
            skillPoints = entity.skillPoints,
            purchasedSkillIdsJson = entity.purchasedSkillIdsJson,
            learnedAttackIdsJson = entity.learnedAttackIdsJson,
            equippedAttackIdsJson = entity.equippedAttackIdsJson,
            learnedMagicIdsJson = entity.learnedMagicIdsJson,
            equippedMagicIdsJson = entity.equippedMagicIdsJson,
            equippedWeaponId = entity.equippedWeaponId,
            equippedShieldId = entity.equippedShieldId,
            equippedRingId = entity.equippedRingId,
            equippedRelicId = entity.equippedRelicId,
            lastRecoveryAt = entity.lastRecoveryAt,
            updatedAt = entity.updatedAt
        )
    }
}

@Serializable
data class TamaTransferAdventureGateWorldProgress(
    val petId: String,
    val worldId: String,
    val highestClearedPhase: Int = 0,
    val midBossCleared: Boolean = false,
    val finalBossCleared: Boolean = false,
    val updatedAt: Long
) {
    fun toEntity() = AdventureGateWorldProgressEntity(
        petId = petId,
        worldId = worldId,
        highestClearedPhase = highestClearedPhase,
        midBossCleared = midBossCleared,
        finalBossCleared = finalBossCleared,
        updatedAt = updatedAt
    )

    companion object {
        fun fromEntity(entity: AdventureGateWorldProgressEntity) = TamaTransferAdventureGateWorldProgress(
            petId = entity.petId,
            worldId = entity.worldId,
            highestClearedPhase = entity.highestClearedPhase,
            midBossCleared = entity.midBossCleared,
            finalBossCleared = entity.finalBossCleared,
            updatedAt = entity.updatedAt
        )
    }
}

@Serializable
data class TamaTransferAdventureGateBattleState(
    val petId: String,
    val worldId: String,
    val phaseNumber: Int,
    val stateJson: String,
    val updatedAt: Long
) {
    fun toEntity() = AdventureGateBattleStateEntity(
        petId = petId,
        worldId = worldId,
        phaseNumber = phaseNumber,
        stateJson = stateJson,
        updatedAt = updatedAt
    )

    companion object {
        fun fromEntity(entity: AdventureGateBattleStateEntity) = TamaTransferAdventureGateBattleState(
            petId = entity.petId,
            worldId = entity.worldId,
            phaseNumber = entity.phaseNumber,
            stateJson = entity.stateJson,
            updatedAt = entity.updatedAt
        )
    }
}

internal fun parseTamaTransferBundle(jsonString: String, json: Json): TamaTransferBundle {
    val element = json.parseToJsonElement(jsonString)
    if (element !is JsonObject) {
        throw IllegalArgumentException("Invalid Tama transfer payload")
    }

    return if (element["version"] != null) {
        json.decodeFromJsonElement<TamaTransferBundle>(element)
    } else {
        parseLegacyTamaTransferBundle(element, json)
    }
}

private fun parseLegacyTamaTransferBundle(element: JsonObject, json: Json): TamaTransferBundle {
    val pet = element["pet"]?.let { json.decodeFromJsonElement<TamaPet>(it) }
        ?: throw IllegalArgumentException("Missing pet in legacy Tama export")
    val artworks = element["artworks"]?.let { json.decodeFromJsonElement<List<TamaTransferArtwork>>(it) } ?: emptyList()
    val events = element["events"]?.let { json.decodeFromJsonElement<List<TamaTransferEvent>>(it) } ?: emptyList()
    val summaries = element["summaries"]?.let { json.decodeFromJsonElement<List<TamaTransferSummary>>(it) } ?: emptyList()

    return TamaTransferBundle(
        version = 1,
        exportDate = element["exportDate"]?.toString()?.trim('"')?.toLongOrNull() ?: System.currentTimeMillis(),
        pet = pet,
        petAgeMillis = 0L,
        artworks = artworks,
        events = events,
        summaries = summaries,
        chatMessages = emptyList(),
        farmTiles = emptyList(),
        settings = null,
        farmUpgrades = emptyList(),
        farmLivestock = emptyList(),
        quests = emptyList(),
        questChecklist = emptyList(),
        deepDreamRuns = emptyList(),
        studyLabels = emptyList(),
        studySessions = emptyList(),
        adventureSessions = emptyList(),
        adventureStages = emptyList(),
        dungeonProgress = null
    )
}

internal fun replacementPetIds(existingPetIds: Collection<String>, importedPetId: String): Set<String> {
    return buildSet {
        existingPetIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach(::add)
        if (importedPetId.isNotBlank()) add(importedPetId)
    }
}

internal fun decodeSchematicWorldImagePath(schematicJson: String): String? {
    return runCatching {
        Json.decodeFromString<StorySchematic>(schematicJson).worldImagePath
    }.getOrNull()
}

private fun patchSchematicWorldImagePath(schematicJson: String, worldImagePath: String?): String {
    return runCatching {
        val schematic = Json.decodeFromString<StorySchematic>(schematicJson)
        Json.encodeToString(schematic.copy(worldImagePath = worldImagePath))
    }.getOrElse { schematicJson }
}
