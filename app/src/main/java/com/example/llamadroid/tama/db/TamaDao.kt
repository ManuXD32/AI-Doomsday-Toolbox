package com.example.llamadroid.tama.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room entity for persisting TamaPet data.
 */
@Entity(tableName = "tama_pets")
data class TamaPetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val species: String,
    val birthTimestamp: Long,
    val stageProgressStartTime: Long = birthTimestamp,
    val lastDecayTime: Long = System.currentTimeMillis(),
    val stage: String,

    // Stats
    val hunger: Float,
    val happiness: Float,
    val health: Float,
    val energy: Float,
    val hygiene: Float,

    val mood: String,
    val personality: String,

    // Genetics
    val eyeStyle: Int,
    val earStyle: Int,
    val mouthStyle: Int,
    val headShape: Int,
    val bodyStyle: Int,
    val armStyle: Int,
    val legStyle: Int,
    val colorTint: Int,

    val ownerBondLevel: Float,
    val educationLevel: Float,
    val exerciseLevel: Float = 0f,
    val introspectionLevel: Float = 0f,
    val currentLocationId: String,
    val homeRoomId: String = "principal_room",
    val leftDecorationId: String? = null,
    val rightDecorationId: String? = null,
    val growthLocked: Boolean = false,
    val growthLockStartedAt: Long? = null,
    val cycleFrozen: Boolean = false,
    val cycleFreezeStartedAt: Long? = null,
    val money: Long,
    val inventoryJson: String,
    // Activity system
    val currentActivity: String = "NONE",  // ActivityType enum name
    val currentWorkJobId: String? = null,
    val activityStartTime: Long? = null,
    val isSleeping: Boolean = false,
    val sleepStartTime: Long? = null,
    val lastDailyDreamDate: String? = null,
    val pendingDreamAlbumId: String? = null,
    val currentParkEncounterJson: String? = null,
    val currentAmbientNpcJson: String? = null,
    val lastRecyclerEncounterDate: String? = null,
    val nextPoopAt: Long? = null,
    val poopCreatedAt: Long? = null,
    val poopCount: Int = 0,
    val lastPoopMiscareAt: Long? = null,
    val lastSleepWarningTime: Long? = null,
    val overnightAwakeDateKey: String? = null,
    val overnightAwakeAccumulatedMs: Long = 0L,
    val miscareCount: Int = 0,
    val isMad: Boolean = false,
    val relationshipsJson: String,
    val discoveredLocationIdsJson: String = "[\"home\"]"
)

/**
 * Room entity for persisting chat messages.
 */
@Entity(tableName = "tama_chat_messages")
data class TamaChatMessageEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val thinking: String? = null,
    val audioPath: String? = null,
    val audioDurationMs: Long? = null,
    val imagePath: String? = null,
    val transcriptionStatus: String? = null,
    val transcribedText: String? = null,
    val transcriptionError: String? = null
)

/**
 * Room entity for persisting events (for LLM context).
 */
@Entity(tableName = "tama_events")
data class TamaEventEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val petId: String,
    val eventType: String,
    val details: String,
    val locationId: String?,
    val npcId: String?,
    val statsChangeJson: String?
)

@Entity(tableName = "tama_quests")
data class TamaQuestEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val status: String,
    val generatedDateKey: String,
    val acceptedAt: Long?,
    val expiresAt: Long?,
    val completedAt: Long?,
    val npcId: String,
    val requestsJson: String,
    val rewardCoins: Long,
    val summaryJson: String
)

@Entity(
    tableName = "tama_quest_checklist_items",
    indices = [
        Index(value = ["petId", "itemId"], unique = true),
        Index(value = ["petId", "checked"])
    ]
)
data class TamaQuestChecklistItemEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val itemId: String,
    val quantity: Int,
    val checked: Boolean = false,
    val sourceQuestIdsJson: String = "[]",
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "tama_deep_dream_runs",
    indices = [
        Index(value = ["petId", "signature"], unique = true),
        Index(value = ["status"]),
        Index(value = ["albumId"])
    ]
)
data class TamaDeepDreamRunEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val signature: String,
    val dreamDate: String,
    val status: String,
    val stage: String,
    val albumId: String?,
    val ownsLocalLlama: Boolean,
    val startedAt: Long,
    val updatedAt: Long,
    val lastHeartbeatAt: Long,
    val errorMessage: String?
)

@Entity(
    tableName = "tama_study_labels",
    indices = [
        Index(value = ["petId", "name"], unique = true),
        Index(value = ["petId", "lastUsedAt"])
    ]
)
data class TamaStudyLabelEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val name: String,
    val createdAt: Long,
    val lastUsedAt: Long
)

@Entity(
    tableName = "tama_study_sessions",
    indices = [
        Index(value = ["petId", "status"]),
        Index(value = ["petId", "startedAt"]),
        Index(value = ["petId", "completedAt"])
    ]
)
data class TamaStudySessionEntity(
    @PrimaryKey val id: String,
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
    val phaseStartedAt: Long?,
    val phaseEndsAt: Long?,
    val focusAccumulatedMs: Long,
    val restAccumulatedMs: Long,
    val educationAwarded: Float,
    val startedAt: Long,
    val completedAt: Long?,
    val stoppedAt: Long?,
    val lastUpdatedAt: Long
)

@Entity(
    tableName = "tama_market_quotes",
    primaryKeys = ["petId", "itemId"],
    indices = [Index(value = ["petId", "quoteWeekKey"])]
)
data class TamaMarketQuoteEntity(
    val petId: String,
    val itemId: String,
    val quoteWeekKey: String,
    val currentPrice: Int,
    val unitsSoldSinceRefresh: Int,
    val updatedAt: Long
)

/**
 * Room entity for locations.
 */
@Entity(tableName = "tama_locations")
data class TamaLocationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val description: String,
    val cityId: String,
    val x: Int,
    val y: Int,
    val isDiscovered: Boolean,
    val npcIdsJson: String,
    val shopInventoryJson: String?,
    val jobsJson: String?
)


/**
 * Room entity for NPCs.
 */
@Entity(tableName = "tama_npcs")
data class TamaNpcEntity(
    @PrimaryKey val id: String,
    val name: String,
    val species: String,
    val personality: String,
    val geneticsJson: String,
    val homeLocationId: String,
    val currentLocationId: String,
    val job: String?,
    val age: Int,
    val marriedToPetId: String?,
    val childrenIdsJson: String,
    val likesJson: String,
    val dislikesJson: String
)

/**
 * Room entity for memory summaries.
 */
@Entity(tableName = "tama_summaries")
data class TamaSummaryEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val date: String,  // YYYY-MM-DD
    val summary: String,
    val shortTermSummary: String = "",
    val longTermSummary: String = "",
    val retrievalNotesJson: String = "[]",
    val createdAt: Long,
    val lastEventTimestamp: Long = 0L, // To know from which event to start next summary
    val lastChatMessageTimestamp: Long = 0L // To know from which message to start next summary
)

/**
 * Room entity for adventure sessions.
 */
@Entity(tableName = "adventure_sessions")
data class AdventureSessionEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val dungeonType: String,
    val schematicJson: String,
    val currentStage: Int,
    val isCompleted: Boolean,
    val cumulativeSummary: String,
    val createdAt: Long,
    val lastPlayedAt: Long
)

/**
 * Room entity for adventure stages.
 */
@Entity(tableName = "adventure_stages")
data class AdventureStageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val stageNumber: Int,
    val storyContent: String,
    val userResponse: String?,
    val stageSummary: String?,
    val imagePath: String?,
    val imagePrompt: String?,
    val imageNegativePrompt: String?,
    val timestamp: Long
)

/**
 * Room entity for dungeon unlock progress.
 */
@Entity(tableName = "dungeon_progress")
data class DungeonProgressEntity(
    @PrimaryKey val petId: String,
    val completedDungeonCount: Int = 0,
    val lastCompletedDungeonType: String? = null
)

@Entity(tableName = "adventure_gate_profiles")
data class AdventureGateProfileEntity(
    @PrimaryKey val petId: String,
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
    val currentHp: Int = 120,
    val currentMana: Int = 40,
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
    val lastRecoveryAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "adventure_gate_world_progress",
    primaryKeys = ["petId", "worldId"],
    indices = [Index(value = ["petId"])]
)
data class AdventureGateWorldProgressEntity(
    val petId: String,
    val worldId: String,
    val highestClearedPhase: Int = 0,
    val midBossCleared: Boolean = false,
    val finalBossCleared: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "adventure_gate_battle_state",
    indices = [Index(value = ["petId", "worldId", "phaseNumber"])]
)
data class AdventureGateBattleStateEntity(
    @PrimaryKey val petId: String,
    val worldId: String,
    val phaseNumber: Int,
    val stateJson: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "adventure_gate_night_arena_runs")
data class AdventureGateNightArenaRunEntity(
    @PrimaryKey val petId: String,
    val nightKey: String,
    val levelsJson: String,
    val clearedLevelIdsJson: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "tama_artworks")
data class TamaArtworkEntity(
    @PrimaryKey val id: String,
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
    val seed: Long?,
    val sourceActivity: String?,
    val albumId: String?,
    val albumIndex: Int,
    val albumDate: String?,
    val albumSummary: String?,
    val filePath: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?
)

/**
 * DAO for Tama database operations.
 */
@Dao
interface TamaDao {
    // Pet operations
    @Query("SELECT * FROM tama_pets WHERE id = :id")
    suspend fun getPet(id: String): TamaPetEntity?

    @Query("SELECT * FROM tama_pets WHERE id = :id")
    fun observePet(id: String): Flow<TamaPetEntity?>

    @Query(
        """
        SELECT * FROM tama_pets
        ORDER BY CASE stage
            WHEN 'EGG' THEN 0
            WHEN 'BABY' THEN 1
            WHEN 'CHILD' THEN 2
            WHEN 'TEEN' THEN 3
            WHEN 'ADULT' THEN 4
            WHEN 'SENIOR' THEN 5
            ELSE -1
        END DESC,
        lastDecayTime DESC,
        rowid DESC
        LIMIT 1
        """
    )
    suspend fun getActivePet(): TamaPetEntity?

    @Query(
        """
        SELECT * FROM tama_pets
        ORDER BY CASE stage
            WHEN 'EGG' THEN 0
            WHEN 'BABY' THEN 1
            WHEN 'CHILD' THEN 2
            WHEN 'TEEN' THEN 3
            WHEN 'ADULT' THEN 4
            WHEN 'SENIOR' THEN 5
            ELSE -1
        END DESC,
        lastDecayTime DESC,
        rowid DESC
        LIMIT 1
        """
    )
    fun observeActivePet(): Flow<TamaPetEntity?>

    @Query("SELECT id FROM tama_pets")
    suspend fun getAllPetIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePet(pet: TamaPetEntity)

    @Delete
    suspend fun deletePet(pet: TamaPetEntity)

    @Query("UPDATE tama_pets SET hunger = :hunger, happiness = :happiness, health = :health, energy = :energy, hygiene = :hygiene WHERE id = :id")
    suspend fun updateStats(id: String, hunger: Float, happiness: Float, health: Float, energy: Float, hygiene: Float)

    @Query("UPDATE tama_pets SET money = :money WHERE id = :id")
    suspend fun updateMoney(id: String, money: Long)

    @Query("UPDATE tama_pets SET currentLocationId = :locationId WHERE id = :id")
    suspend fun updateLocation(id: String, locationId: String)

    @Query("UPDATE tama_pets SET currentActivity = :activity, activityStartTime = :startTime WHERE id = :id")
    suspend fun updateActivityStatus(id: String, activity: String, startTime: Long?)

    // Event operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveEvent(event: TamaEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveEvents(events: List<TamaEventEntity>)

    @Query("SELECT * FROM tama_events WHERE petId = :petId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEvents(petId: String, limit: Int = 50): List<TamaEventEntity>

    @Query("SELECT * FROM tama_events WHERE petId = :petId ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentEvents(petId: String, limit: Int = 100): Flow<List<TamaEventEntity>>

    @Query("SELECT * FROM tama_events WHERE petId = :petId ORDER BY timestamp DESC")
    suspend fun getAllEvents(petId: String): List<TamaEventEntity>

    @Query("SELECT * FROM tama_events WHERE petId = :petId AND timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getEventsSince(petId: String, since: Long): List<TamaEventEntity>

    @Query("DELETE FROM tama_events WHERE petId = :petId")
    suspend fun deleteEventsForPet(petId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveQuest(quest: TamaQuestEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveQuests(quests: List<TamaQuestEntity>)

    @Query("SELECT * FROM tama_quests WHERE petId = :petId ORDER BY status ASC, generatedDateKey DESC, expiresAt ASC")
    suspend fun getQuestsForPet(petId: String): List<TamaQuestEntity>

    @Query("SELECT * FROM tama_quests WHERE petId = :petId AND status = :status ORDER BY generatedDateKey DESC, id ASC")
    suspend fun getQuestsByStatus(petId: String, status: String): List<TamaQuestEntity>

    @Query("DELETE FROM tama_quests WHERE petId = :petId AND status = 'AVAILABLE' AND generatedDateKey != :dateKey")
    suspend fun deleteStaleAvailableQuests(petId: String, dateKey: String)

    @Query("DELETE FROM tama_quests WHERE petId = :petId")
    suspend fun deleteQuestsForPet(petId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveQuestChecklistItem(item: TamaQuestChecklistItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveQuestChecklistItems(items: List<TamaQuestChecklistItemEntity>)

    @Query("SELECT * FROM tama_quest_checklist_items WHERE petId = :petId ORDER BY checked ASC, updatedAt DESC, itemId ASC")
    fun observeQuestChecklist(petId: String): Flow<List<TamaQuestChecklistItemEntity>>

    @Query("SELECT * FROM tama_quest_checklist_items WHERE petId = :petId ORDER BY checked ASC, updatedAt DESC, itemId ASC")
    suspend fun getQuestChecklist(petId: String): List<TamaQuestChecklistItemEntity>

    @Query("SELECT * FROM tama_quest_checklist_items WHERE petId = :petId AND itemId = :itemId LIMIT 1")
    suspend fun getQuestChecklistItem(petId: String, itemId: String): TamaQuestChecklistItemEntity?

    @Query("DELETE FROM tama_quest_checklist_items WHERE petId = :petId AND itemId = :itemId")
    suspend fun deleteQuestChecklistItem(petId: String, itemId: String)

    @Query("DELETE FROM tama_quest_checklist_items WHERE petId = :petId AND checked = 1")
    suspend fun deleteCheckedQuestChecklistItems(petId: String)

    @Query("DELETE FROM tama_quest_checklist_items WHERE petId = :petId")
    suspend fun deleteQuestChecklistForPet(petId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDeepDreamRun(run: TamaDeepDreamRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDeepDreamRuns(runs: List<TamaDeepDreamRunEntity>)

    @Query("SELECT * FROM tama_deep_dream_runs WHERE id = :id LIMIT 1")
    suspend fun getDeepDreamRun(id: String): TamaDeepDreamRunEntity?

    @Query("SELECT * FROM tama_deep_dream_runs WHERE petId = :petId AND signature = :signature LIMIT 1")
    suspend fun getDeepDreamRunBySignature(petId: String, signature: String): TamaDeepDreamRunEntity?

    @Query("SELECT * FROM tama_deep_dream_runs WHERE albumId = :albumId LIMIT 1")
    suspend fun getDeepDreamRunByAlbumId(albumId: String): TamaDeepDreamRunEntity?

    @Query("SELECT * FROM tama_deep_dream_runs WHERE petId = :petId ORDER BY updatedAt DESC")
    suspend fun getDeepDreamRunsForPet(petId: String): List<TamaDeepDreamRunEntity>

    @Query("SELECT * FROM tama_deep_dream_runs WHERE petId = :petId AND status IN (:statuses) ORDER BY updatedAt DESC")
    suspend fun getDeepDreamRunsForPetByStatuses(petId: String, statuses: List<String>): List<TamaDeepDreamRunEntity>

    @Query("SELECT * FROM tama_deep_dream_runs WHERE status IN (:statuses) ORDER BY updatedAt DESC")
    suspend fun getDeepDreamRunsByStatuses(statuses: List<String>): List<TamaDeepDreamRunEntity>

    @Query("DELETE FROM tama_deep_dream_runs WHERE petId = :petId")
    suspend fun deleteDeepDreamRunsForPet(petId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveArtwork(artwork: TamaArtworkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveArtworks(artworks: List<TamaArtworkEntity>)

    @Query("SELECT * FROM tama_artworks WHERE id = :id")
    suspend fun getArtwork(id: String): TamaArtworkEntity?

    @Query("SELECT * FROM tama_artworks WHERE id = :id")
    fun observeArtwork(id: String): Flow<TamaArtworkEntity?>

    @Query("SELECT * FROM tama_artworks WHERE petId = :petId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestArtwork(petId: String): TamaArtworkEntity?

    @Query("SELECT * FROM tama_artworks WHERE petId = :petId ORDER BY createdAt DESC")
    fun observeArtworks(petId: String): Flow<List<TamaArtworkEntity>>

    @Query("SELECT * FROM tama_artworks WHERE petId = :petId ORDER BY createdAt ASC")
    suspend fun getArtworks(petId: String): List<TamaArtworkEntity>

    @Query("SELECT * FROM tama_artworks WHERE petId = :petId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestArtwork(petId: String): Flow<TamaArtworkEntity?>

    @Query("SELECT * FROM tama_artworks WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextQueuedArtwork(): TamaArtworkEntity?

    @Query("UPDATE tama_artworks SET status = 'QUEUED', startedAt = NULL WHERE status = 'GENERATING'")
    suspend fun requeueGeneratingArtworks()

    @Query("DELETE FROM tama_artworks WHERE id = :id")
    suspend fun deleteArtwork(id: String)

    @Query("DELETE FROM tama_artworks WHERE petId = :petId")
    suspend fun deleteArtworksForPet(petId: String)

    // Location operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLocation(location: TamaLocationEntity)

    @Query("SELECT * FROM tama_locations WHERE id = :id")
    suspend fun getLocation(id: String): TamaLocationEntity?

    @Query("SELECT * FROM tama_locations WHERE cityId = :cityId")
    suspend fun getLocationsInCity(cityId: String): List<TamaLocationEntity>

    @Query("SELECT * FROM tama_locations ORDER BY id ASC")
    suspend fun getAllLocations(): List<TamaLocationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLocations(locations: List<TamaLocationEntity>)

    @Query("DELETE FROM tama_locations")
    suspend fun deleteAllLocations()


    // NPC operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNpc(npc: TamaNpcEntity)

    @Query("SELECT * FROM tama_npcs WHERE id = :id")
    suspend fun getNpc(id: String): TamaNpcEntity?

    @Query("SELECT * FROM tama_npcs WHERE currentLocationId = :locationId")
    suspend fun getNpcsAtLocation(locationId: String): List<TamaNpcEntity>

    @Query("SELECT * FROM tama_npcs ORDER BY id ASC")
    suspend fun getAllNpcs(): List<TamaNpcEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNpcs(npcs: List<TamaNpcEntity>)

    @Query("DELETE FROM tama_npcs")
    suspend fun deleteAllNpcs()

    // Summary operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSummary(summary: TamaSummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSummaries(summaries: List<TamaSummaryEntity>)

    @Query(
        """
        SELECT * FROM tama_summaries
        WHERE petId = :petId
        ORDER BY CASE WHEN id LIKE '%_latest' THEN 0 ELSE 1 END, createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentSummaries(petId: String, limit: Int = 7): List<TamaSummaryEntity>

    @Query("SELECT * FROM tama_summaries WHERE petId = :petId ORDER BY createdAt DESC")
    suspend fun getAllSummaries(petId: String): List<TamaSummaryEntity>

    @Query(
        """
        SELECT * FROM tama_summaries
        WHERE petId = :petId
        ORDER BY CASE WHEN id LIKE '%_latest' THEN 0 ELSE 1 END, createdAt DESC
        LIMIT 1
        """
    )
    fun getSummaryForPet(petId: String): Flow<List<TamaSummaryEntity>>

    @Query("DELETE FROM tama_summaries WHERE petId = :petId")
    suspend fun deleteSummariesForPet(petId: String)

    // Study label/session operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStudyLabel(label: TamaStudyLabelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStudyLabels(labels: List<TamaStudyLabelEntity>)

    @Query("SELECT * FROM tama_study_labels WHERE petId = :petId ORDER BY lastUsedAt DESC, name COLLATE NOCASE ASC")
    fun observeStudyLabels(petId: String): Flow<List<TamaStudyLabelEntity>>

    @Query("SELECT * FROM tama_study_labels WHERE petId = :petId ORDER BY lastUsedAt DESC, name COLLATE NOCASE ASC")
    suspend fun getStudyLabels(petId: String): List<TamaStudyLabelEntity>

    @Query("SELECT * FROM tama_study_labels WHERE petId = :petId AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getStudyLabelByName(petId: String, name: String): TamaStudyLabelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStudySession(session: TamaStudySessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStudySessions(sessions: List<TamaStudySessionEntity>)

    @Query("SELECT * FROM tama_study_sessions WHERE petId = :petId AND status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveStudySession(petId: String): TamaStudySessionEntity?

    @Query("SELECT * FROM tama_study_sessions WHERE petId = :petId AND status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    fun observeActiveStudySession(petId: String): Flow<TamaStudySessionEntity?>

    @Query("SELECT * FROM tama_study_sessions WHERE petId = :petId ORDER BY startedAt DESC")
    suspend fun getStudySessionsForPet(petId: String): List<TamaStudySessionEntity>

    @Query("SELECT * FROM tama_study_sessions WHERE petId = :petId ORDER BY startedAt DESC")
    fun observeStudySessionsForPet(petId: String): Flow<List<TamaStudySessionEntity>>

    @Query("SELECT * FROM tama_study_sessions WHERE petId = :petId AND startedAt >= :startInclusive AND startedAt < :endExclusive ORDER BY startedAt DESC")
    suspend fun getStudySessionsBetween(petId: String, startInclusive: Long, endExclusive: Long): List<TamaStudySessionEntity>

    @Query("DELETE FROM tama_study_sessions WHERE petId = :petId")
    suspend fun deleteStudySessionsForPet(petId: String)

    @Query("DELETE FROM tama_study_labels WHERE petId = :petId")
    suspend fun deleteStudyLabelsForPet(petId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMarketQuote(quote: TamaMarketQuoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMarketQuotes(quotes: List<TamaMarketQuoteEntity>)

    @Query("SELECT * FROM tama_market_quotes WHERE petId = :petId ORDER BY itemId ASC")
    suspend fun getMarketQuotesForPet(petId: String): List<TamaMarketQuoteEntity>

    @Query("SELECT * FROM tama_market_quotes WHERE petId = :petId AND itemId = :itemId LIMIT 1")
    suspend fun getMarketQuote(petId: String, itemId: String): TamaMarketQuoteEntity?

    @Query("DELETE FROM tama_market_quotes WHERE petId = :petId")
    suspend fun deleteMarketQuotesForPet(petId: String)

    // Chat operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveChatMessage(message: TamaChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveChatMessages(messages: List<TamaChatMessageEntity>)

    @Query("SELECT * FROM tama_chat_messages WHERE petId = :petId ORDER BY timestamp ASC")
    suspend fun getChatHistory(petId: String): List<TamaChatMessageEntity>

    @Query("DELETE FROM tama_chat_messages WHERE petId = :petId")
    suspend fun clearChatHistory(petId: String)

    @Query("DELETE FROM tama_chat_messages WHERE id = :id")
    suspend fun deleteChatMessage(id: String)

    // Adventure operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdventureSession(session: AdventureSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdventureSessions(sessions: List<AdventureSessionEntity>)

    @Query("SELECT * FROM adventure_sessions WHERE id = :id")
    suspend fun getAdventureSession(id: String): AdventureSessionEntity?

    @Query("SELECT * FROM adventure_sessions WHERE petId = :petId AND isCompleted = 0 ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun getActiveAdventureSession(petId: String): AdventureSessionEntity?

    @Query("SELECT * FROM adventure_sessions WHERE petId = :petId AND dungeonType = :dungeonType AND isCompleted = 0 ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun getActiveAdventureSessionForDungeon(petId: String, dungeonType: String): AdventureSessionEntity?

    @Query("SELECT * FROM adventure_sessions WHERE petId = :petId AND dungeonType = :dungeonType AND isCompleted = 0 ORDER BY lastPlayedAt DESC LIMIT 1")
    fun observeActiveAdventureSessionForDungeon(petId: String, dungeonType: String): Flow<AdventureSessionEntity?>

    @Query("SELECT * FROM adventure_sessions WHERE petId = :petId AND dungeonType = :dungeonType ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun getLatestAdventureSessionForDungeon(petId: String, dungeonType: String): AdventureSessionEntity?

    @Query("SELECT * FROM adventure_sessions WHERE petId = :petId AND dungeonType = :dungeonType ORDER BY lastPlayedAt DESC LIMIT 1")
    fun observeLatestAdventureSessionForDungeon(petId: String, dungeonType: String): Flow<AdventureSessionEntity?>

    @Query("SELECT * FROM adventure_sessions WHERE petId = :petId ORDER BY lastPlayedAt DESC")
    suspend fun getAdventureHistory(petId: String): List<AdventureSessionEntity>

    @Query("DELETE FROM adventure_sessions WHERE id = :id")
    suspend fun deleteAdventureSession(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdventureStage(stage: AdventureStageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdventureStages(stages: List<AdventureStageEntity>)

    @Query("SELECT * FROM adventure_stages WHERE sessionId = :sessionId ORDER BY stageNumber ASC")
    suspend fun getAdventureStages(sessionId: String): List<AdventureStageEntity>

    @Query("SELECT * FROM adventure_stages WHERE sessionId = :sessionId ORDER BY stageNumber ASC")
    fun observeAdventureStages(sessionId: String): Flow<List<AdventureStageEntity>>

    @Query("DELETE FROM adventure_stages WHERE sessionId = :sessionId")
    suspend fun deleteAdventureStages(sessionId: String)

    @Query("DELETE FROM adventure_sessions WHERE petId = :petId")
    suspend fun deleteAdventureSessionsForPet(petId: String)

    // Dungeon progress operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDungeonProgress(progress: DungeonProgressEntity)

    @Query("SELECT * FROM dungeon_progress WHERE petId = :petId")
    suspend fun getDungeonProgress(petId: String): DungeonProgressEntity?

    @Query("UPDATE dungeon_progress SET completedDungeonCount = :count, lastCompletedDungeonType = :dungeonType WHERE petId = :petId")
    suspend fun updateDungeonProgress(petId: String, count: Int, dungeonType: String)

    @Query("DELETE FROM dungeon_progress WHERE petId = :petId")
    suspend fun deleteDungeonProgress(petId: String)

    // Adventure Gate RPG operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdventureGateProfile(profile: AdventureGateProfileEntity)

    @Query("SELECT * FROM adventure_gate_profiles WHERE petId = :petId")
    suspend fun getAdventureGateProfile(petId: String): AdventureGateProfileEntity?

    @Query("SELECT * FROM adventure_gate_profiles WHERE petId = :petId")
    fun observeAdventureGateProfile(petId: String): Flow<AdventureGateProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdventureGateWorldProgress(progress: AdventureGateWorldProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdventureGateWorldProgress(progress: List<AdventureGateWorldProgressEntity>)

    @Query("SELECT * FROM adventure_gate_world_progress WHERE petId = :petId ORDER BY worldId ASC")
    suspend fun getAdventureGateWorldProgress(petId: String): List<AdventureGateWorldProgressEntity>

    @Query("SELECT * FROM adventure_gate_world_progress WHERE petId = :petId ORDER BY worldId ASC")
    fun observeAdventureGateWorldProgress(petId: String): Flow<List<AdventureGateWorldProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdventureGateBattleState(state: AdventureGateBattleStateEntity)

    @Query("SELECT * FROM adventure_gate_battle_state WHERE petId = :petId")
    suspend fun getAdventureGateBattleState(petId: String): AdventureGateBattleStateEntity?

    @Query("SELECT * FROM adventure_gate_battle_state WHERE petId = :petId")
    fun observeAdventureGateBattleState(petId: String): Flow<AdventureGateBattleStateEntity?>

    @Query("DELETE FROM adventure_gate_battle_state WHERE petId = :petId")
    suspend fun deleteAdventureGateBattleState(petId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdventureGateNightArenaRun(run: AdventureGateNightArenaRunEntity)

    @Query("SELECT * FROM adventure_gate_night_arena_runs WHERE petId = :petId")
    suspend fun getAdventureGateNightArenaRun(petId: String): AdventureGateNightArenaRunEntity?

    @Query("SELECT * FROM adventure_gate_night_arena_runs WHERE petId = :petId")
    fun observeAdventureGateNightArenaRun(petId: String): Flow<AdventureGateNightArenaRunEntity?>

    @Query("DELETE FROM adventure_gate_night_arena_runs WHERE petId = :petId")
    suspend fun deleteAdventureGateNightArenaRun(petId: String)

    @Query("DELETE FROM adventure_gate_profiles WHERE petId = :petId")
    suspend fun deleteAdventureGateProfile(petId: String)

    @Query("DELETE FROM adventure_gate_world_progress WHERE petId = :petId")
    suspend fun deleteAdventureGateWorldProgress(petId: String)

    @Query("DELETE FROM tama_pets WHERE id = :petId")
    suspend fun deletePetById(petId: String)
}
