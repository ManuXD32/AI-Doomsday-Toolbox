package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.*
import com.example.llamadroid.tama.rpg.AdventureGateCatalog
import com.example.llamadroid.tama.rpg.AdventureGateCombatEngine
import com.example.llamadroid.tama.rpg.AdventureGateProfile
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.tama.rpg.AdventureGateRepository
import com.example.llamadroid.tama.rpg.AdventureGateStats
import com.example.llamadroid.tama.rpg.AdventureGateSupplyKind
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.widget.TamaPetWidgetProvider
import androidx.room.withTransaction
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Core game engine for Tama virtual pet.
 * Manages pet state, real-time updates, and game logic.
 */
class TamaGameEngine(
    private val context: Context,
    private val dao: TamaDao,
    private val farmEngine: FarmEngine,
    private val farmRepository: FarmRepository,
    private val settingsRepo: SettingsRepository
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _pet = MutableStateFlow<TamaPet?>(null)
    val pet: StateFlow<TamaPet?> = _pet.asStateFlow()

    private val _events = MutableStateFlow<List<TamaEvent>>(emptyList())
    val events: StateFlow<List<TamaEvent>> = _events.asStateFlow()

    private val _currentLocation = MutableStateFlow<TamaLocation?>(null)
    val currentLocation: StateFlow<TamaLocation?> = _currentLocation.asStateFlow()

    // Time tracking for real-time updates
    private var lastUpdateTime = 0L
    private var hasLoggedReturnThisSession = false  // Prevent OWNER_RETURNED spam
    private var eventSyncJob: Job? = null
    private var observedEventsPetId: String? = null

    private data class GrowthAdvanceResult(
        val pet: TamaPet,
        val evolvedStages: List<GrowthStage>,
        val initialStage: GrowthStage
    )

    private data class OvernightAwakeTrackingResult(
        val pet: TamaPet,
        val miscareTriggerCount: Int
    )

    companion object {
        const val STAT_DECAY_PER_HOUR = 5  // Stats decrease by this much per hour
        const val MAX_WORK_HOURS_HEALTHY = 8
        const val NEGLECT_THRESHOLD_HOURS = 4  // After this long without care = neglect
        private const val EVENT_COMPRESSION_WINDOW_MS = 15 * 60 * 1000L
        private const val BACKUP_MANIFEST_ENTRY = "manifest.json"
        private const val BACKUP_ARTWORK_PREFIX = "gallery/"
        private const val BACKUP_CHAT_AUDIO_PREFIX = "chat_audio/"
        private const val BACKUP_CHAT_IMAGE_PREFIX = "chat_images/"
        private const val BACKUP_ADVENTURE_WORLD_PREFIX = "adventure_worlds/"
        private const val BACKUP_ADVENTURE_STAGE_PREFIX = "adventure_stages/"
        private const val CHAT_AUDIO_DIR = "tama_chat_audio"
        private const val CHAT_IMAGE_DIR = "tama_chat_images"
        private const val ADVENTURE_WORLD_DIR = "adventure_worlds"
        private const val POOP_MIN_INTERVAL_MS = 50 * 60 * 1000L
        private const val POOP_MAX_INTERVAL_MS = 120 * 60 * 1000L
        private const val POOP_MISCARE_WINDOW_MS = 50 * 60 * 1000L
        private const val PARK_QUEST_COUNT = 3
        private const val PARK_QUEST_ACCEPT_WINDOW_MS = 48L * 60L * 60L * 1000L
        private const val PARK_SEED_GIFT_CHANCE = 0.10f
        private const val WORK_ENERGY_DECAY_MULTIPLIER = 1.5f
        private const val SLEEPY_FAIRY_PREFS = "tama_sleepy_fairy"
    }

    private data class TamaBackupPackage(
        val bundle: TamaTransferBundle,
        val artworks: List<TamaArtworkEntity>
    )

    private val FIXED_LOCATIONS_BY_ID: Map<String, TamaLocation> by lazy {
        listOf(
            fixedLocation(0, 0, LocationType.HOME),
            fixedLocation(1, 0, LocationType.SHOP),
            fixedLocation(2, 0, LocationType.PARK),
            fixedLocation(3, 0, LocationType.HOSPITAL),
            fixedLocation(4, 0, LocationType.ARCADE),
            fixedLocation(0, 1, LocationType.ALCHEMIST),
            fixedLocation(1, 1, LocationType.SCHOOL),
            fixedLocation(2, 1, LocationType.WORKPLACE),
            fixedLocation(3, 1, LocationType.FARM),
            fixedLocation(4, 1, LocationType.BOXING_RING),
            fixedLocation(0, 2, LocationType.DUNGEON),
            fixedLocation(2, 2, LocationType.ADVENTURE_GATE),
            fixedLocation(4, 2, LocationType.DUNGEON)
        ).associateBy { it.id }
    }

    init {
        backgroundScope.launch {
            dao.observeActivePet().collectLatest { entity ->
                if (entity == null) {
                    observedEventsPetId = null
                    eventSyncJob?.cancel()
                    eventSyncJob = null
                    _pet.value = null
                    _events.value = emptyList()
                    _currentLocation.value = null
                    return@collectLatest
                }

                val syncedPet = PetMapper.toDomain(entity)
                _pet.value = syncedPet
                _currentLocation.value = resolveLocation(syncedPet.currentLocationId)

                if (observedEventsPetId != syncedPet.id) {
                    observedEventsPetId = syncedPet.id
                    eventSyncJob?.cancel()
                    eventSyncJob = backgroundScope.launch {
                        dao.observeRecentEvents(syncedPet.id, 100).collect { entities ->
                            _events.value = entities.map(::entityToEvent)
                        }
                    }
                }
            }
        }

        backgroundScope.launch {
            while (true) {
                runCatching {
                    ensurePetLoadedForBackgroundUpdates()
                    updateForTimePassed()
                }.onFailure { error ->
                    DebugLog.log("[TamaGameEngine] Background decay tick failed: ${error.message}")
                }
                delay(5_000L)
            }
        }
    }

    // ==================== Pet Creation ====================

    /**
     * Create a new pet (starts as egg).
     */
    suspend fun createPet(name: String, species: String = PetSpeciesLine.DRAGON.id): TamaPet {
        val speciesLine = PetSpeciesLine.fromSpeciesId(species)
        val now = System.currentTimeMillis()
        dao.getAllPetIds().forEach { petId ->
            clearPetScopedTransferData(petId)
        }
        val pet = TamaPet(
            name = name,
            species = speciesLine.id,
            birthTimestamp = now,
            stageProgressStartTime = now,
            stage = GrowthStage.EGG,
            genetics = GeneticTraits(bodyStyle = speciesLine.ordinal),
            homeRoomId = TamaRoomCatalog.PRINCIPAL_ROOM_ID,
            inventory = listOf(
                InventoryItem(id = "hoe_starter", name = "Hoe", type = ItemType.TOOL, durability = 100, maxDurability = 100),
                InventoryItem(id = "watering_can_starter", name = "Watering Can", type = ItemType.TOOL, durability = 100, maxDurability = 100)
            )
        )
        _pet.value = pet
        savePet(pet)
        return pet
    }

    /**
     * Completely reset/delete current pet.
     */
    suspend fun resetPet() {
        _pet.value?.let { pet ->
            dao.deletePet(PetMapper.toEntity(pet))
            dao.deleteArtworksForPet(pet.id)
            TamaNotificationScheduler.cancelPetAlarms(context.applicationContext, pet.id)
            File(context.filesDir, "tama_gallery/${pet.id}").deleteRecursively()
            _pet.value = null
            _events.value = emptyList()
        }
    }

    /**
     * Load existing pet from database.
     */
    suspend fun loadPet(): TamaPet? {
        val entity = dao.getActivePet() ?: return null
        val pet = PetMapper.toDomain(entity)
        pruneExtraPetsKeeping(pet.id)
        _pet.value = pet
        _currentLocation.value = resolveLocation(pet.currentLocationId)
        if (pet.species != entity.species) {
            savePet(pet)
        }

        // Load recent events
        val eventEntities = dao.getRecentEvents(pet.id)
        _events.value = eventEntities.map { entityToEvent(it) }

        // Update for time passed while app was closed
        updateForTimePassed()
        if (_pet.value?.poopCount == 0) {
            UnifiedNotificationManager.dismissTamaPoopNotifications(pet.id)
        }
        TamaNotificationScheduler.scheduleForPet(context.applicationContext, pet.id)

        return pet
    }

    private suspend fun ensurePetLoadedForBackgroundUpdates(): TamaPet? {
        val currentPet = _pet.value
        if (currentPet != null) return currentPet
        val entity = dao.getActivePet() ?: return null
        val pet = PetMapper.toDomain(entity)
        pruneExtraPetsKeeping(pet.id)
        _pet.value = pet
        _currentLocation.value = resolveLocation(pet.currentLocationId)
        _events.value = dao.getRecentEvents(pet.id).map(::entityToEvent)
        return pet
    }

    private suspend fun pruneExtraPetsKeeping(activePetId: String) {
        val stalePetIds = dao.getAllPetIds().filter { it != activePetId }
        stalePetIds.forEach { petId ->
            clearPetScopedTransferData(petId)
        }
    }

    // ==================== Time-Based Updates ====================

    /**
     * Update pet state based on real time passed.
     * Called on app open and periodically.
     */
    suspend fun updateForTimePassed() {
        val pet = _pet.value ?: return
        val now = System.currentTimeMillis()
        if (lastUpdateTime > 0L && now - lastUpdateTime < 4_500L) {
            return
        }
        if (pet.cycleFrozen) {
            lastUpdateTime = now
            return
        }

        // Update farm state (growth, production, decay)
        farmEngine.updateFarm(pet.id, now)

        var updatedPet = pet
        if (updatedPet.currentActivity == ActivityType.STUDYING) {
            val studyRefresh = TamaStudySessionSupport.advanceActiveSession(
                context = context,
                dao = dao,
                pet = updatedPet,
                now = now,
                showNotification = false
            )
            updatedPet = studyRefresh.pet
            if (studyRefresh.completed) {
                TamaNotificationScheduler.scheduleForPet(context.applicationContext, updatedPet.id)
            }
        }
        val intervalStart = pet.lastDecayTime
        val minutesPassed = (now - intervalStart) / (1000f * 60)
        val secondsPassed = (now - intervalStart) / 1000L

        // 1. Check for stage progression using the per-stage timer while preserving total lifetime.
        val growthAdvance = advanceGrowthStage(updatedPet, now)
        updatedPet = growthAdvance.pet
        val sleepFreezesStats = updatedPet.isSleeping && isSleepStatFreezeWindow(now)
        if (sleepFreezesStats) {
            updatedPet = updatedPet.copy(lastDecayTime = now)
        } else if (secondsPassed < 5L) {
            if (updatedPet != pet) {
                savePet(updatedPet)
                logEvolutionEvents(updatedPet, growthAdvance.evolvedStages, growthAdvance.initialStage)
            }
            lastUpdateTime = now
            return
        }

        // 2. Real-time Decay (Smooth)
        // STAT_DECAY_PER_HOUR (5 by default) points per hour.
        // We calculate the fraction of decay based on the exact seconds passed.
        if (!sleepFreezesStats) {
            val decayPerHour = STAT_DECAY_PER_HOUR.toFloat()
            val decayMultiplier = if (updatedPet.stage == GrowthStage.BABY) 5f else 1f
            val decayAmount = (secondsPassed / 3600f) * decayPerHour * decayMultiplier
            val energyDecayAmount = if (updatedPet.currentActivity == ActivityType.WORKING) {
                decayAmount * WORK_ENERGY_DECAY_MULTIPLIER
            } else {
                decayAmount
            }

            if (decayAmount > 0.001f) {
                val newStats = updatedPet.stats.copy(
                    hunger = (updatedPet.stats.hunger - decayAmount).coerceIn(0f, 100f),
                    happiness = (updatedPet.stats.happiness - decayAmount).coerceIn(0f, 100f),
                    hygiene = (updatedPet.stats.hygiene - decayAmount).coerceIn(0f, 100f),
                    energy = (updatedPet.stats.energy - energyDecayAmount).coerceIn(0f, 100f)
                )

                updatedPet = updatedPet.copy(stats = newStats, lastDecayTime = now)

                // 4. Check for neglect (only if not sleeping and not an egg)
                if (newStats.needsAttention() && !updatedPet.isSleeping && updatedPet.stage != GrowthStage.EGG) {
                    updatedPet = updatedPet.copy(
                        stats = updatedPet.stats.copy(happiness = (updatedPet.stats.happiness - 0.1f).coerceIn(0f, 100f)),
                        ownerBondLevel = (updatedPet.ownerBondLevel - 0.1f).coerceIn(0f, 100f)
                    )
                    if (minutesPassed > 60) {
                        logEvent(pet.id, EventType.NEGLECTED, context.getString(R.string.tama_event_neglected, pet.name))
                    }
                }
            }
        }

        // 5. Bedtime tracking
        if (updatedPet.stage != GrowthStage.BABY && updatedPet.stage != GrowthStage.EGG) {
            val overnightTracking = applyOvernightAwakeTracking(
                pet = updatedPet,
                intervalStart = intervalStart,
                intervalEnd = now
            )
            updatedPet = overnightTracking.pet
            repeat(overnightTracking.miscareTriggerCount) {
                logEvent(
                    pet.id,
                    EventType.NEGLECTED,
                    context.getString(R.string.tama_event_stayed_up_mad, pet.name)
                )
            }
        }

        // 6. Update mood
        updatedPet = updatedPet.copy(mood = effectiveMood(updatedPet))

        // 8. Log return if away a while (only once per session)
        val minutesPassedSinceDecay = (now - pet.lastDecayTime) / (1000f * 60)
        if (minutesPassedSinceDecay > 5 && !hasLoggedReturnThisSession) {
            logEvent(pet.id, EventType.OWNER_RETURNED, context.getString(R.string.tama_event_welcome_back, minutesPassedSinceDecay.toInt()))
            hasLoggedReturnThisSession = true
        }

        updatedPet = ensurePoopSchedule(
            maybeQueueDailyDream(
                advancePoopState(updatedPet, now),
                now
            ),
            now
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvolutionEvents(updatedPet, growthAdvance.evolvedStages, growthAdvance.initialStage)
        lastUpdateTime = now
    }

    suspend fun freezeCycle(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (pet.cycleFrozen) {
            return ActionResult(false, context.getString(R.string.tama_cycle_freeze_already_frozen))
        }
        val now = System.currentTimeMillis()
        val frozenPet = pet.copy(
            cycleFrozen = true,
            cycleFreezeStartedAt = now,
            lastDecayTime = now
        )
        _pet.value = frozenPet
        savePet(frozenPet)
        TamaNotificationScheduler.cancelPetAlarms(context.applicationContext, frozenPet.id)
        UnifiedNotificationManager.cancelTamaSleepNotification(frozenPet.id)
        logEvent(
            frozenPet.id,
            EventType.OTHER,
            context.getString(R.string.tama_event_cycle_frozen, frozenPet.name)
        )
        lastUpdateTime = now
        return ActionResult(true, context.getString(R.string.tama_cycle_freeze_started, frozenPet.name), "frozen")
    }

    suspend fun unfreezeCycle(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (!pet.cycleFrozen) {
            return ActionResult(false, context.getString(R.string.tama_cycle_freeze_not_frozen))
        }
        val now = System.currentTimeMillis()
        val durationMs = (now - (pet.cycleFreezeStartedAt ?: now)).coerceAtLeast(0L)
        val shiftedPet = pet
            .withCycleTimestampsShifted(durationMs)
            .copy(
                cycleFrozen = false,
                cycleFreezeStartedAt = null,
                lastDecayTime = now
            )
        TamaDatabase.getInstance(context).withTransaction {
            dao.saveStudySessions(dao.getStudySessionsForPet(pet.id).map { it.withCycleTimestampsShifted(durationMs) })
            dao.saveQuests(dao.getQuestsForPet(pet.id).map { it.withCycleTimestampsShifted(durationMs) })
            farmRepository.shiftCycleTimestamps(pet.id, durationMs)
            dao.savePet(PetMapper.toEntity(shiftedPet))
        }
        _pet.value = shiftedPet
        TamaNotificationScheduler.scheduleForPet(context.applicationContext, shiftedPet.id)
        if (shiftedPet.isSleeping) {
            UnifiedNotificationManager.showTamaSleepNotification(shiftedPet)
        }
        logEvent(
            shiftedPet.id,
            EventType.OTHER,
            context.getString(R.string.tama_event_cycle_unfrozen, shiftedPet.name)
        )
        lastUpdateTime = now
        return ActionResult(true, context.getString(R.string.tama_cycle_freeze_stopped, shiftedPet.name), "idle")
    }

    private suspend fun advanceGrowthStage(pet: TamaPet, now: Long): GrowthAdvanceResult {
        if (pet.growthLocked) return GrowthAdvanceResult(pet, emptyList(), pet.stage)
        var updatedPet = pet
        val evolvedStages = mutableListOf<GrowthStage>()
        val initialStage = pet.stage
        while (true) {
            val nextStage = GrowthStage.entries.getOrNull(updatedPet.stage.ordinal + 1) ?: break
            val requiredDuration = GrowthStage.durationUntilNextStageMillis(updatedPet.stage) ?: break
            val elapsed = (now - updatedPet.stageProgressStartTime).coerceAtLeast(0L)
            if (elapsed < requiredDuration) break
            val overflow = (elapsed - requiredDuration).coerceAtLeast(0L)
            updatedPet = updatedPet.copy(
                stage = nextStage,
                stageProgressStartTime = now - overflow
            )
            evolvedStages += nextStage
            _pet.value = updatedPet
        }
        return GrowthAdvanceResult(updatedPet, evolvedStages, initialStage)
    }

    private suspend fun logEvolutionEvents(pet: TamaPet, evolvedStages: List<GrowthStage>, initialStage: GrowthStage) {
        var previousStage = initialStage
        evolvedStages.forEach { evolvedStage ->
            val eventType = if (previousStage == GrowthStage.EGG && evolvedStage == GrowthStage.BABY) {
                EventType.HATCHED
            } else {
                EventType.EVOLVED
            }
            logEvent(
                pet.id,
                eventType,
                context.getString(R.string.tama_event_evolved, pet.name, evolvedStage.displayName)
            )
            previousStage = evolvedStage
        }
    }

    private fun currentLocale(): Locale =
        context.resources.configuration.locales[0] ?: Locale.getDefault()

    private fun localizeParkEncounterLine(encounter: TamaParkEncounter): String {
        return TamaParkSocialCatalog.localizedLine(context, encounter)
    }

    private suspend fun buildParkEncounter(
        pet: TamaPet,
        now: Long
    ): Pair<TamaParkEncounter, InventoryItem?> {
        val encounter = TamaParkSocialCatalog.pickEncounter(pet, now)
        if (encounter.type != TamaParkEncounterType.REGULAR) {
            return encounter to null
        }
        if (hasReceivedParkGiftToday(pet.id, now)) {
            return encounter to null
        }
        if (Random.nextFloat() >= PARK_SEED_GIFT_CHANCE) {
            return encounter to null
        }
        val cropId = CropDefinitions.CROPS.keys.sorted().random()
        val seedItem = InventoryItem(
            id = "seed_$cropId",
            name = seedDisplayText(cropId).resolve(currentLocale()),
            type = ItemType.SEED,
            quantity = 1
        )
        return encounter.copy(
            giftItemId = seedItem.id,
            giftQuantity = 1
        ) to seedItem
    }

    private suspend fun hasReceivedParkGiftToday(petId: String, now: Long): Boolean {
        return dao.getEventsSince(petId, startOfLocalDayMillis(now))
            .any { event ->
                event.eventType == EventType.RECEIVED_GIFT.name
            }
    }

    private fun startOfLocalDayMillis(now: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun maybeCreateSleepyFairyReminder(now: Long = System.currentTimeMillis()): TamaSleepyFairyReminder? {
        val pet = _pet.value ?: return null
        val currentLocation = _currentLocation.value ?: resolveLocation(pet.currentLocationId)
        if (currentLocation?.type != LocationType.HOME) return null
        if (pet.homeRoomId != TamaRoomCatalog.PRINCIPAL_ROOM_ID) return null
        if (pet.isSleeping) return null
        if (!isSleepyFairyReminderWindow(now)) return null
        val lastShown = sleepyFairyPrefs().getLong(sleepyFairyLastShownKey(pet.id), 0L)
        if (lastShown > 0L && now - lastShown < TAMA_SLEEPY_FAIRY_COOLDOWN_MS) {
            return null
        }
        val reminder = TamaSleepyFairyReminder(
            lineIndex = Random.nextInt(TamaSleepyFairyCatalog.definition.lines.size),
            shownAt = now
        )
        sleepyFairyPrefs().edit()
            .putLong(sleepyFairyLastShownKey(pet.id), now)
            .apply()
        return reminder
    }

    private fun applyOvernightAwakeTracking(
        pet: TamaPet,
        intervalStart: Long,
        intervalEnd: Long
    ): OvernightAwakeTrackingResult {
        if (intervalEnd <= intervalStart || pet.isSleeping) {
            return OvernightAwakeTrackingResult(pet, 0)
        }
        val overlaps = overnightAwakeOverlaps(intervalStart, intervalEnd)
        if (overlaps.isEmpty()) {
            return OvernightAwakeTrackingResult(pet, 0)
        }
        val progress = accumulateOvernightAwake(
            currentDateKey = pet.overnightAwakeDateKey,
            currentAccumulatedMs = pet.overnightAwakeAccumulatedMs,
            overlaps = overlaps,
            lastSleepWarningDateKey = pet.lastSleepWarningTime?.let(::localDateKey)
        )
        var updatedPet = pet.copy(
            overnightAwakeDateKey = progress.dateKey,
            overnightAwakeAccumulatedMs = progress.accumulatedMs
        )
        progress.triggerTimestamps.forEach { warningTime ->
            updatedPet = applyMiscarePenalty(updatedPet).copy(lastSleepWarningTime = warningTime)
        }
        return OvernightAwakeTrackingResult(updatedPet, progress.triggerTimestamps.size)
    }

    private fun sleepyFairyPrefs() =
        context.applicationContext.getSharedPreferences(SLEEPY_FAIRY_PREFS, Context.MODE_PRIVATE)

    private fun sleepyFairyLastShownKey(petId: String): String = "last_shown_$petId"

    private fun consumeInventoryItem(
        inventory: List<InventoryItem>,
        itemId: String,
        quantity: Int = 1
    ): List<InventoryItem>? {
        val targetIndex = inventory.indexOfFirst { it.id.equals(itemId, ignoreCase = true) }
        if (targetIndex < 0) return null
        val targetItem = inventory[targetIndex]
        if (targetItem.quantity < quantity) return null
        val updatedInventory = inventory.toMutableList()
        if (targetItem.quantity == quantity) {
            updatedInventory.removeAt(targetIndex)
        } else {
            updatedInventory[targetIndex] = targetItem.copy(quantity = targetItem.quantity - quantity)
        }
        return updatedInventory
    }

    private fun addInventoryItem(
        inventory: List<InventoryItem>,
        item: InventoryItem,
        quantity: Int = 1
    ): List<InventoryItem> {
        val updatedInventory = inventory.toMutableList()
        val existingIndex = updatedInventory.indexOfFirst { it.id == item.id }
        if (existingIndex >= 0) {
            val existing = updatedInventory[existingIndex]
            updatedInventory[existingIndex] = existing.copy(quantity = existing.quantity + quantity)
        } else {
            updatedInventory.add(item.copy(quantity = quantity))
        }
        return updatedInventory
    }

    private fun DroneStoredCrop.toInventoryItem(): InventoryItem =
        InventoryItem(
            id = inventoryId,
            name = inventoryIdDisplayName(inventoryId),
            type = if (inventoryId.startsWith("crop_")) ItemType.CROP else ItemType.MATERIAL
        )

    private fun inventoryIdDisplayName(inventoryId: String): String {
        if (inventoryId == "rotten_crop") return context.getString(R.string.tama_item_rotten_crop)
        if (inventoryId.startsWith("crop_")) {
            return cropDisplayName(context, inventoryId.removePrefix("crop_"))
        }
        return FarmTradeItemCatalog.displayName(inventoryId, context.resources.configuration.locales[0])
    }

    private fun stagePotionDetails(targetStage: GrowthStage): Pair<Boolean, String> {
        val pet = _pet.value ?: return false to context.getString(R.string.tama_error_no_pet)
        if (targetStage == GrowthStage.EGG) {
            return false to context.getString(R.string.tama_potion_stage_invalid)
        }
        if (pet.stage == targetStage) {
            return false to context.getString(R.string.tama_potion_stage_same, targetStage.displayName)
        }
        return true to context.getString(R.string.tama_potion_used_stage, pet.name, targetStage.displayName)
    }

    private fun speciesPotionDetails(targetSpecies: PetSpeciesLine): Pair<Boolean, String> {
        val pet = _pet.value ?: return false to context.getString(R.string.tama_error_no_pet)
        if (pet.species.equals(targetSpecies.id, ignoreCase = true)) {
            return false to context.getString(
                R.string.tama_potion_species_same,
                context.getString(targetSpecies.displayNameRes)
            )
        }
        return true to context.getString(
            R.string.tama_potion_used_species,
            pet.name,
            context.getString(targetSpecies.displayNameRes)
        )
    }

    private fun calculateMood(stats: PetStats): Mood = when {
        stats.health < 50 -> Mood.ANGRY
        stats.health < 20 -> Mood.SICK
        stats.energy < 20 -> Mood.SLEEPING
        stats.critical() -> Mood.SAD
        stats.happiness > 80 && stats.hunger > 60 -> Mood.ECSTATIC
        stats.happiness > 50 -> Mood.HAPPY
        stats.happiness > 30 -> Mood.NEUTRAL
        stats.happiness > 10 -> Mood.SAD
        else -> Mood.ANGRY
    }

    private fun effectiveMood(pet: TamaPet, now: Long = System.currentTimeMillis()): Mood = when {
        pet.isSleeping -> Mood.SLEEPING
        pet.isEffectivelyMad() -> Mood.ANGRY
        isSleepyMoodWindow(now) && pet.stage != GrowthStage.EGG -> Mood.SLEEPY
        else -> calculateMood(pet.stats)
    }

    // ==================== Action Result ====================

    /**
     * Result of an action - indicates if it was performed and any message.
     */
    data class ActionResult(
        val success: Boolean,
        val message: String,
        val action: String = ""
    )

    data class ParkQuestFinishResult(
        val success: Boolean,
        val message: String,
        val presentation: TamaQuestCompletionPresentation? = null
    )

    private fun foodDisplayName(foodId: String): String {
        return when (foodId.lowercase(Locale.ROOT)) {
            "lettuce" -> context.getString(R.string.tama_food_lettuce)
            "candy" -> context.getString(R.string.tama_food_candy)
            "apple" -> context.getString(R.string.tama_food_apple)
            "bread" -> context.getString(R.string.tama_food_bread)
            "cake" -> context.getString(R.string.tama_food_cake)
            "pizza" -> context.getString(R.string.tama_food_pizza)
            "burger" -> context.getString(R.string.tama_food_burger)
            "sushi" -> context.getString(R.string.tama_food_sushi)
            "donut" -> context.getString(R.string.tama_food_donut)
            "salad" -> context.getString(R.string.tama_food_salad)
            else -> foodId.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }

    private fun activityDisplayName(activity: ActivityType): String {
        return when (activity) {
            ActivityType.WORKING -> context.getString(R.string.tama_activity_working)
            ActivityType.STUDYING -> context.getString(R.string.tama_activity_studying)
            ActivityType.TRAINING -> context.getString(R.string.tama_activity_training)
            ActivityType.RELAXING -> context.getString(R.string.tama_activity_relaxing)
            ActivityType.NONE -> context.getString(R.string.tama_activity_none)
        }
    }

    // ==================== Care Actions ====================

    suspend fun feed(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (pet.isSleeping) return ActionResult(false, context.getString(R.string.tama_sleeping_busy, pet.name))
        if (pet.stage == GrowthStage.EGG) return ActionResult(false, context.getString(R.string.tama_action_egg_cannot_eat))
        if (pet.stats.hunger >= 100) return ActionResult(false, context.getString(R.string.tama_action_food_full, pet.name))

        val hungerGain = 30f
        val newStats = pet.stats.copy(hunger = (pet.stats.hunger + hungerGain).coerceAtMost(100f))
        val updatedPet = pet.copy(
            stats = newStats,
            ownerBondLevel = (pet.ownerBondLevel + 1f).coerceAtMost(100f)
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(pet.id, EventType.FED, context.getString(R.string.tama_event_fed, pet.name, pet.stats.hunger.toInt(), newStats.hunger.toInt()),
            statsChange = mapOf("hunger" to hungerGain))
        return ActionResult(true, context.getString(R.string.tama_action_food_ate, pet.name), "eating")
    }

    suspend fun feedWithFood(foodId: String, hungerGain: Int, happinessGain: Int): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (pet.isSleeping) return ActionResult(false, context.getString(R.string.tama_sleeping_busy, pet.name))
        if (pet.stage == GrowthStage.EGG) return ActionResult(false, context.getString(R.string.tama_action_egg_cannot_eat))

        // Lettuce specific override: user requested value 5
        val finalHungerGain = if (foodId.equals("lettuce", ignoreCase = true)) 5 else hungerGain

        // Check if free food or from inventory
        val freeFoods = setOf("lettuce", "candy")
        val isFreeFood = freeFoods.contains(foodId.lowercase())

        var newInventory = pet.inventory.toMutableList()
        if (!isFreeFood) {
            val matchingItem = newInventory.find { it.id.equals(foodId, ignoreCase = true) && it.quantity > 0 }
            if (matchingItem == null) {
                return ActionResult(false, context.getString(R.string.tama_action_no_item_food, foodDisplayName(foodId)))
            }

            val index = newInventory.indexOf(matchingItem)
            if (matchingItem.quantity == 1) {
                newInventory.removeAt(index)
            } else {
                newInventory[index] = matchingItem.copy(quantity = matchingItem.quantity - 1)
            }
        }

        val finalHungerGainFloat = finalHungerGain.toFloat()
        val happinessGainFloat = happinessGain.toFloat()

        val newStats = pet.stats.copy(
            hunger = (pet.stats.hunger + finalHungerGainFloat).coerceAtMost(100f),
            happiness = (pet.stats.happiness + happinessGainFloat).coerceAtMost(100f)
        )
        val updatedPet = pet.copy(
            stats = newStats,
            inventory = newInventory,
            ownerBondLevel = (pet.ownerBondLevel + 1f).coerceAtMost(100f)
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        val foodLabel = foodDisplayName(foodId)
        logEvent(pet.id, EventType.FED, context.getString(R.string.tama_event_ate_food, pet.name, foodLabel))
        return ActionResult(true, context.getString(R.string.tama_action_ate_food, pet.name, foodLabel), "eating")
    }

    suspend fun usePotion(potionId: String): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (pet.isSleeping) return ActionResult(false, context.getString(R.string.tama_sleeping_busy, pet.name))

        val potion = TamaPotionCatalog.byId(potionId)
            ?: return ActionResult(false, context.getString(R.string.tama_potion_missing))
        val updatedInventory = consumeInventoryItem(pet.inventory, potion.id)
            ?: return ActionResult(false, context.getString(R.string.tama_potion_not_owned))
        val now = System.currentTimeMillis()

        val updatedPet: TamaPet
        val eventDetails: String
        val successMessage: String
        when (potion.kind) {
            TamaPotionKind.STAGE -> {
                val targetStage = potion.targetStage
                    ?: return ActionResult(false, context.getString(R.string.tama_potion_stage_invalid))
                val (allowed, message) = stagePotionDetails(targetStage)
                if (!allowed) return ActionResult(false, message)
                updatedPet = pet.copy(
                    stage = targetStage,
                    stageProgressStartTime = now,
                    growthLockStartedAt = if (pet.growthLocked) now else pet.growthLockStartedAt,
                    inventory = updatedInventory
                )
                eventDetails = context.getString(
                    R.string.tama_event_potion_used_stage,
                    pet.name,
                    context.getString(potion.titleRes),
                    targetStage.displayName
                )
                successMessage = message
            }
            TamaPotionKind.SPECIES -> {
                val targetSpecies = potion.targetSpecies
                    ?: return ActionResult(false, context.getString(R.string.tama_potion_species_invalid))
                val (allowed, message) = speciesPotionDetails(targetSpecies)
                if (!allowed) return ActionResult(false, message)
                updatedPet = pet.copy(
                    species = targetSpecies.id,
                    genetics = pet.genetics.copy(bodyStyle = targetSpecies.ordinal),
                    inventory = updatedInventory
                )
                eventDetails = context.getString(
                    R.string.tama_event_potion_used_species,
                    pet.name,
                    context.getString(potion.titleRes),
                    context.getString(targetSpecies.displayNameRes)
                )
                successMessage = message
            }
            TamaPotionKind.GROWTH_LOCK -> {
                if (pet.growthLocked) {
                    return ActionResult(false, context.getString(R.string.tama_potion_growth_lock_already))
                }
                updatedPet = pet.copy(
                    growthLocked = true,
                    growthLockStartedAt = now,
                    inventory = updatedInventory
                )
                eventDetails = context.getString(
                    R.string.tama_event_potion_growth_locked,
                    pet.name,
                    context.getString(potion.titleRes)
                )
                successMessage = context.getString(R.string.tama_potion_used_growth_lock, pet.name)
            }
            TamaPotionKind.GROWTH_UNLOCK -> {
                if (!pet.growthLocked) {
                    return ActionResult(false, context.getString(R.string.tama_potion_growth_unlock_already))
                }
                updatedPet = pet.copy(
                    growthLocked = false,
                    growthLockStartedAt = null,
                    stageProgressStartTime = pet.stageProgressStartTime + pausedGrowthDuration(pet, now),
                    inventory = updatedInventory
                )
                eventDetails = context.getString(
                    R.string.tama_event_potion_growth_unlocked,
                    pet.name,
                    context.getString(potion.titleRes)
                )
                successMessage = context.getString(R.string.tama_potion_used_growth_unlock, pet.name)
            }
            TamaPotionKind.HEALING -> {
                val healAmount = potion.healAmount
                    ?: return ActionResult(false, context.getString(R.string.tama_potion_heal_invalid))
                if (pet.stats.health >= 100f) {
                    return ActionResult(false, context.getString(R.string.tama_potion_heal_already_full))
                }
                val healedHealth = (pet.stats.health + healAmount).coerceAtMost(100f)
                val healedPet = pet.copy(
                    stats = pet.stats.copy(
                        health = healedHealth
                    ),
                    isMad = if (healedHealth >= 50f) false else pet.isMad,
                    inventory = updatedInventory
                )
                updatedPet = healedPet.copy(mood = effectiveMood(healedPet))
                eventDetails = context.getString(
                    R.string.tama_event_potion_healed,
                    pet.name,
                    context.getString(potion.titleRes),
                    healAmount
                )
                successMessage = context.getString(R.string.tama_potion_used_healing, pet.name, healAmount)
            }
        }

        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(
            pet.id,
            if (potion.kind == TamaPotionKind.HEALING) EventType.HEALED else EventType.OTHER,
            eventDetails,
            statsChange = potion.healAmount?.let { mapOf("health" to it.toFloat()) }
        )
        return ActionResult(
            true,
            successMessage,
            if (potion.kind == TamaPotionKind.HEALING) "eating" else "transforming"
        )
    }

    suspend fun useAdventureGateSupply(supplyId: String): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val supply = AdventureGateCatalog.supply(supplyId)
            ?: return ActionResult(false, context.getString(R.string.tama_potion_missing))
        val existingEntity = dao.getAdventureGateProfile(pet.id)
        val rawProfile = existingEntity?.let { entity ->
            AdventureGateProfile(
                petId = entity.petId,
                level = entity.level,
                xp = entity.xp,
                stats = AdventureGateStats(
                    maxHp = entity.maxHp,
                    maxMana = entity.maxMana,
                    attack = entity.attack,
                    magic = entity.magic,
                    defense = entity.defense,
                    speed = entity.speed,
                    accuracy = entity.accuracy,
                    evasion = entity.evasion
                ),
                currentHp = entity.currentHp,
                currentMana = entity.currentMana,
                skillPoints = entity.skillPoints,
                equippedWeaponId = entity.equippedWeaponId,
                equippedShieldId = entity.equippedShieldId,
                equippedRingId = entity.equippedRingId,
                equippedRelicId = entity.equippedRelicId,
                lastRecoveryAt = entity.lastRecoveryAt,
                updatedAt = entity.updatedAt
            )
        } ?: AdventureGateProfile(petId = pet.id)
        val profile = AdventureGateCombatEngine.normalizedProfile(rawProfile, pet.educationLevel, pet.introspectionLevel, pet.exerciseLevel)
        val potionBonus = AdventureGateCatalog.loadoutForProfile(profile).equipment.sumOf { it.effect.potionBonusPercent }
        val amount = (supply.amount * (100 + potionBonus) / 100f).toInt().coerceAtLeast(1)
        val updatedProfile = when (supply.kind) {
            AdventureGateSupplyKind.HP -> {
                if (profile.currentHp >= profile.stats.maxHp) {
                    return ActionResult(false, context.getString(R.string.tama_potion_heal_already_full))
                }
                profile.copy(currentHp = (profile.currentHp + amount).coerceAtMost(profile.stats.maxHp))
            }
            AdventureGateSupplyKind.MANA -> {
                if (profile.currentMana >= profile.stats.maxMana) {
                    return ActionResult(false, context.getString(R.string.tama_potion_heal_already_full))
                }
                profile.copy(currentMana = (profile.currentMana + amount).coerceAtMost(profile.stats.maxMana))
            }
            AdventureGateSupplyKind.CLEANSE -> {
                return ActionResult(false, context.getString(R.string.tama_action_adventure_gate_cleanse_battle_only))
            }
            AdventureGateSupplyKind.SKILL_POINT -> profile.copy(skillPoints = profile.skillPoints + supply.amount)
        }.copy(updatedAt = System.currentTimeMillis())
        val updatedInventory = consumeInventoryItem(pet.inventory, supply.id)
            ?: return ActionResult(false, context.getString(R.string.tama_potion_not_owned))
        val updatedPet = pet.copy(inventory = updatedInventory)
        _pet.value = updatedPet
        savePet(updatedPet)
        dao.saveAdventureGateProfile(
            (existingEntity ?: AdventureGateProfileEntity(petId = pet.id)).copy(
                level = updatedProfile.level,
                xp = updatedProfile.xp,
                maxHp = updatedProfile.stats.maxHp,
                maxMana = updatedProfile.stats.maxMana,
                attack = updatedProfile.stats.attack,
                magic = updatedProfile.stats.magic,
                defense = updatedProfile.stats.defense,
                speed = updatedProfile.stats.speed,
                accuracy = updatedProfile.stats.accuracy,
                evasion = updatedProfile.stats.evasion,
                currentHp = updatedProfile.currentHp,
                currentMana = updatedProfile.currentMana,
                skillPoints = updatedProfile.skillPoints,
                equippedWeaponId = updatedProfile.equippedWeaponId,
                equippedShieldId = updatedProfile.equippedShieldId,
                equippedRingId = updatedProfile.equippedRingId,
                equippedRelicId = updatedProfile.equippedRelicId,
                lastRecoveryAt = System.currentTimeMillis(),
                updatedAt = updatedProfile.updatedAt
            )
        )
        val itemName = context.getString(supply.nameRes)
        logEvent(
            pet.id,
            EventType.OTHER,
            context.getString(R.string.tama_event_adventure_gate_supply_used, pet.name, itemName)
        )
        return ActionResult(
            true,
            if (supply.kind == AdventureGateSupplyKind.SKILL_POINT) {
                context.getString(R.string.tama_action_adventure_gate_skill_star_used, itemName)
            } else if (supply.kind == AdventureGateSupplyKind.CLEANSE) {
                context.getString(R.string.tama_action_adventure_gate_cleanse_battle_only)
            } else {
                context.getString(R.string.tama_action_adventure_gate_supply_used, itemName, amount)
            },
            "eating"
        )
    }

    suspend fun brewAdventureGatePotion(ingredientItemIds: List<String>): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (ingredientItemIds.isEmpty()) {
            return ActionResult(false, context.getString(R.string.tama_alchemist_kitchen_empty_selection))
        }
        if (ingredientItemIds.any { !FarmTradeItemCatalog.isTradeItem(it) || !it.startsWith("crop_") }) {
            return ActionResult(false, context.getString(R.string.tama_alchemist_kitchen_crops_only))
        }

        val selectedCounts = ingredientItemIds.groupingBy { it }.eachCount()
        val ownedCounts = pet.inventory.groupingBy { it.id }.fold(0) { acc, item -> acc + item.quantity }
        val hasIngredients = selectedCounts.all { (itemId, count) -> (ownedCounts[itemId] ?: 0) >= count }
        if (!hasIngredients) {
            return ActionResult(false, context.getString(R.string.tama_alchemist_kitchen_missing_ingredients))
        }

        val ownedRecipes = pet.inventory
            .filter { it.type == ItemType.RECIPE }
            .mapNotNull { AdventureGateCatalog.recipe(it.id) }
        val matchedRecipe = ownedRecipes.firstOrNull { it.ingredientCounts == selectedCounts }
        var updatedInventory: List<InventoryItem> = pet.inventory
        selectedCounts.forEach { (itemId, count) ->
            updatedInventory = consumeInventoryItem(updatedInventory, itemId, count)
                ?: return ActionResult(false, context.getString(R.string.tama_alchemist_kitchen_missing_ingredients))
        }

        val resultItem = if (matchedRecipe != null) {
            val supply = AdventureGateCatalog.supply(matchedRecipe.supplyId)
                ?: return ActionResult(false, context.getString(R.string.tama_potion_missing))
            InventoryItem(
                id = supply.id,
                name = context.getString(supply.nameRes),
                type = ItemType.POTION,
                quantity = 1
            )
        } else {
            InventoryItem(
                id = "rotten_crop",
                name = context.getString(R.string.tama_item_rotten_crop),
                type = ItemType.MATERIAL,
                quantity = 2
            )
        }
        updatedInventory = addInventoryItem(updatedInventory, resultItem, resultItem.quantity)

        val updatedPet = pet.copy(inventory = updatedInventory)
        _pet.value = updatedPet
        savePet(updatedPet)
        val action = if (matchedRecipe != null) "alchemy_success" else "alchemy_failure"
        val message = if (matchedRecipe != null) {
            val supplyName = AdventureGateCatalog.supply(matchedRecipe.supplyId)
                ?.let { context.getString(it.nameRes) }
                ?: resultItem.name
            context.getString(R.string.tama_alchemist_kitchen_success_message, supplyName)
        } else {
            context.getString(R.string.tama_alchemist_kitchen_failure_message)
        }
        logEvent(pet.id, EventType.OTHER, message)
        return ActionResult(true, message, action)
    }

    private suspend fun grantAdventureGateWorkXp(pet: TamaPet, xpAwarded: Int) {
        if (xpAwarded <= 0) return
        val existingEntity = dao.getAdventureGateProfile(pet.id)
        val profile = AdventureGateCombatEngine.normalizedProfile(
            adventureGateProfileFromEntity(pet.id, existingEntity),
            pet.educationLevel,
            pet.introspectionLevel,
            pet.exerciseLevel
        )
        val result = AdventureGateCombatEngine.grantXp(profile, xpAwarded, pet.educationLevel, pet.introspectionLevel)
        dao.saveAdventureGateProfile(adventureGateProfileToEntity(result.profile, existingEntity))
    }

    private fun adventureGateProfileFromEntity(
        petId: String,
        entity: AdventureGateProfileEntity?
    ): AdventureGateProfile {
        if (entity == null) return AdventureGateProfile(petId = petId)
        return AdventureGateProfile(
            petId = entity.petId,
            level = entity.level,
            xp = entity.xp,
            stats = AdventureGateStats(
                maxHp = entity.maxHp,
                maxMana = entity.maxMana,
                attack = entity.attack,
                magic = entity.magic,
                defense = entity.defense,
                speed = entity.speed,
                accuracy = entity.accuracy,
                evasion = entity.evasion
            ),
            currentHp = entity.currentHp,
            currentMana = entity.currentMana,
            skillPoints = entity.skillPoints,
            purchasedSkillIds = jsonArrayToStringList(entity.purchasedSkillIdsJson)
                .ifEmpty { AdventureGateCatalog.starterSkillIds },
            learnedAttackIds = jsonArrayToStringList(entity.learnedAttackIdsJson)
                .ifEmpty { AdventureGateCatalog.startingAttackIds },
            equippedAttackIds = jsonArrayToStringList(entity.equippedAttackIdsJson)
                .ifEmpty { AdventureGateCatalog.startingAttackIds },
            learnedMagicIds = jsonArrayToStringList(entity.learnedMagicIdsJson)
                .ifEmpty { AdventureGateCatalog.startingMagicIds },
            equippedMagicIds = jsonArrayToStringList(entity.equippedMagicIdsJson)
                .ifEmpty { AdventureGateCatalog.startingMagicIds },
            equippedWeaponId = entity.equippedWeaponId.takeUnless { it.isNullOrBlank() },
            equippedShieldId = entity.equippedShieldId.takeUnless { it.isNullOrBlank() },
            equippedRingId = entity.equippedRingId.takeUnless { it.isNullOrBlank() },
            equippedRelicId = entity.equippedRelicId.takeUnless { it.isNullOrBlank() },
            lastRecoveryAt = entity.lastRecoveryAt,
            updatedAt = entity.updatedAt
        )
    }

    private fun adventureGateProfileToEntity(
        profile: AdventureGateProfile,
        existingEntity: AdventureGateProfileEntity?
    ): AdventureGateProfileEntity =
        (existingEntity ?: AdventureGateProfileEntity(petId = profile.petId)).copy(
            level = profile.level,
            xp = profile.xp,
            maxHp = profile.stats.maxHp,
            maxMana = profile.stats.maxMana,
            attack = profile.stats.attack,
            magic = profile.stats.magic,
            defense = profile.stats.defense,
            speed = profile.stats.speed,
            accuracy = profile.stats.accuracy,
            evasion = profile.stats.evasion,
            currentHp = profile.currentHp,
            currentMana = profile.currentMana,
            skillPoints = profile.skillPoints,
            purchasedSkillIdsJson = Json.encodeToString(profile.purchasedSkillIds),
            learnedAttackIdsJson = Json.encodeToString(profile.learnedAttackIds),
            equippedAttackIdsJson = Json.encodeToString(profile.equippedAttackIds),
            learnedMagicIdsJson = Json.encodeToString(profile.learnedMagicIds),
            equippedMagicIdsJson = Json.encodeToString(profile.equippedMagicIds),
            equippedWeaponId = profile.equippedWeaponId,
            equippedShieldId = profile.equippedShieldId,
            equippedRingId = profile.equippedRingId,
            equippedRelicId = profile.equippedRelicId,
            lastRecoveryAt = profile.lastRecoveryAt,
            updatedAt = profile.updatedAt
        )

    suspend fun buyItem(itemName: String, price: Int): ActionResult {
        val item = InventoryItem(
            id = itemName.lowercase().replace(" ", "_"),
            name = itemName,
            type = if (itemName.lowercase().contains("seed")) ItemType.SEED else ItemType.FOOD
        )
        return buyItem(item, 1, price)
    }

    suspend fun clean(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (pet.isSleeping) return ActionResult(false, context.getString(R.string.tama_sleeping_busy, pet.name))
        if (pet.stage == GrowthStage.EGG) return ActionResult(false, context.getString(R.string.tama_action_egg_cannot_bathe))
        if (pet.poopCount > 0) {
            val now = System.currentTimeMillis()
            val updatedPet = ensurePoopSchedule(
                pet.copy(
                    poopCreatedAt = null,
                    poopCount = 0,
                    lastPoopMiscareAt = null,
                    nextPoopAt = now + randomPoopDelayMs()
                ),
                now
            )
            TamaNotificationScheduler.cancelPoopAlarms(context.applicationContext, pet.id)
            _pet.value = updatedPet
            savePet(updatedPet)
            UnifiedNotificationManager.dismissTamaPoopNotifications(pet.id)
            logEvent(
                pet.id,
                EventType.POOP_CLEANED,
                context.getString(R.string.tama_event_poop_cleaned, pet.name)
            )
            return ActionResult(true, context.getString(R.string.tama_action_cleaned_mess, pet.name), "poop_cleaning")
        }
        if (pet.stats.hygiene >= 100) return ActionResult(false, context.getString(R.string.tama_action_already_clean, pet.name))

        val hygieneGain = 40f
        val newStats = pet.stats.copy(hygiene = (pet.stats.hygiene + hygieneGain).coerceAtMost(100f))
        val updatedPet = pet.copy(stats = newStats)
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(pet.id, EventType.CLEANED, context.getString(R.string.tama_event_cleaned, pet.name, pet.stats.hygiene.toInt(), newStats.hygiene.toInt()),
            statsChange = mapOf("hygiene" to hygieneGain))
        return ActionResult(true, context.getString(R.string.tama_action_cleaned_bath, pet.name), "cleaning")
    }

    suspend fun play(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (pet.isSleeping) return ActionResult(false, context.getString(R.string.tama_sleeping_busy, pet.name))
        if (pet.stage == GrowthStage.EGG) return ActionResult(false, context.getString(R.string.tama_action_egg_cannot_play))
        if (pet.stats.happiness >= 100) return ActionResult(false, context.getString(R.string.tama_action_already_super_happy, pet.name))
        if (pet.stats.energy < 10) return ActionResult(false, context.getString(R.string.tama_action_too_tired_to_play, pet.name))

        val happinessGain = 20f
        val energyCost = 10f
        val newStats = pet.stats.copy(
            happiness = (pet.stats.happiness + happinessGain).coerceAtMost(100f),
            energy = (pet.stats.energy - energyCost).coerceAtLeast(0f)
        )
        val updatedPet = pet.copy(
            stats = newStats,
            ownerBondLevel = (pet.ownerBondLevel + 2f).coerceAtMost(100f)
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(pet.id, EventType.PLAYED, context.getString(R.string.tama_event_played, pet.name),
            statsChange = mapOf("happiness" to happinessGain, "energy" to -energyCost))
        return ActionResult(true, context.getString(R.string.tama_action_had_fun, pet.name), "playing")
    }

    /**
     * Put pet to bed - they stay asleep until woken up.
     */
    suspend fun goToBed(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (pet.isSleeping) return ActionResult(false, context.getString(R.string.tama_action_already_asleep, pet.name))
        if (pet.stage == GrowthStage.EGG) return ActionResult(false, context.getString(R.string.tama_action_egg_cannot_sleep))
        if (pet.stats.energy >= 100) return ActionResult(false, context.getString(R.string.tama_action_not_tired, pet.name))
        val now = System.currentTimeMillis()
        val bedtimeTrackedPet = if (pet.stage != GrowthStage.BABY && pet.stage != GrowthStage.EGG) {
            val tracking = applyOvernightAwakeTracking(pet, pet.lastDecayTime, now)
            repeat(tracking.miscareTriggerCount) {
                logEvent(
                    pet.id,
                    EventType.NEGLECTED,
                    context.getString(R.string.tama_event_stayed_up_mad, pet.name)
                )
            }
            tracking.pet
        } else {
            pet
        }
        recoverAdventureGateProfile(pet.id)
        val updatedPet = advancePoopState(bedtimeTrackedPet, now).copy(
            isSleeping = true,
            sleepStartTime = now,
            mood = Mood.SLEEPING
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(pet.id, EventType.SLEPT, context.getString(R.string.tama_event_slept, pet.name))
        UnifiedNotificationManager.showTamaSleepNotification(updatedPet)
        maybeQueueNormalDream(updatedPet)
        return ActionResult(true, context.getString(R.string.tama_action_sleeping_now, pet.name), "sleeping")
    }

    /**
     * Wake pet up - restores energy based on time slept.
     */
    suspend fun wakeUp() {
        val initialPet = _pet.value ?: return
        val now = System.currentTimeMillis()
        val latestPet = dao.getPet(initialPet.id)?.let(PetMapper::toDomain) ?: initialPet
        val currentPet = maybeQueueDailyDream(latestPet, now)
        if (!currentPet.isSleeping) return  // Not sleeping

        val sleepStart = currentPet.sleepStartTime ?: now
        val minutesSlept = ((now - sleepStart) / (1000 * 60)).toInt()
        val sleepDurationMs = (now - sleepStart).coerceAtLeast(0L)

        val energyGain = (minutesSlept * 10f).coerceIn(10f, 100f)
        val newStats = currentPet.stats.copy(
            energy = (currentPet.stats.energy + energyGain).coerceAtMost(100f),
            health = (currentPet.stats.health + minutesSlept.toFloat()).coerceAtMost(100f)  // Sleep heals
        )

        recoverAdventureGateProfile(currentPet.id)
        val updatedPet = currentPet.copy(
            isSleeping = false,
            sleepStartTime = null,
            isMad = false,
            nextPoopAt = currentPet.nextPoopAt?.plus(sleepDurationMs),
            poopCreatedAt = currentPet.poopCreatedAt?.plus(sleepDurationMs),
            lastPoopMiscareAt = currentPet.lastPoopMiscareAt?.plus(sleepDurationMs),
            stats = newStats,
            pendingDreamAlbumId = if (minutesSlept >= 240) currentPet.pendingDreamAlbumId else null
        )
        val adjustedPet = updatedPet.copy(mood = effectiveMood(updatedPet))
        _pet.value = adjustedPet
        savePet(adjustedPet)
        UnifiedNotificationManager.cancelTamaSleepNotification(adjustedPet.id)
        logEvent(adjustedPet.id, EventType.WOKE_UP, context.getString(R.string.tama_event_woke_up, adjustedPet.name, minutesSlept, energyGain.toInt()),
            statsChange = mapOf("energy" to energyGain))
    }

    suspend fun triggerDeepDreamDebug(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (!pet.isSleeping) {
            return ActionResult(false, context.getString(R.string.tama_deep_dream_debug_requires_sleep))
        }

        val now = System.currentTimeMillis()
        com.example.llamadroid.service.TamaDeepDreamService.start(
            context = context,
            petId = pet.id,
            signature = "deep:${TamaDailyDreamManager.formatDreamDate(pet.sleepStartTime ?: now)}:$now:force",
            force = true
        )
        return ActionResult(true, context.getString(R.string.tama_deep_dream_debug_started), "sleeping")
    }

    /**
     * Check if action is allowed (blocked when sleeping).
     */
    fun canDoAction(): Boolean {
        val pet = _pet.value ?: return false
        return !pet.cycleFrozen && !pet.isSleeping && pet.stage != GrowthStage.EGG
    }

    // ==================== Activity System ====================

    /**
     * Start an activity (working, studying, relaxing).
     */
    suspend fun startActivity(activity: ActivityType): ActionResult {
        _pet.value?.let { pet ->
            if (pet.cycleFrozen) return ActionResult(false, context.getString(R.string.tama_cycle_frozen_busy))
        }
        if (activity == ActivityType.STUDYING) {
            return startNormalStudySession(emptySet(), emptyList())
        }
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (pet.isSleeping) return ActionResult(false, context.getString(R.string.tama_sleeping_busy, pet.name))
        if (pet.currentActivity != ActivityType.NONE) {
            return ActionResult(false, context.getString(R.string.tama_action_already_busy, pet.name))
        }

        // Stage restrictions
        if (activity == ActivityType.WORKING && !pet.stage.canWork()) {
            return ActionResult(false, context.getString(R.string.tama_action_only_teens_work))
        }

        recoverAdventureGateProfile(pet.id)
        val updatedPet = pet.copy(
            currentActivity = activity,
            currentWorkJobId = if (activity == ActivityType.WORKING) pet.currentWorkJobId else null,
            activityStartTime = System.currentTimeMillis()
        )
        _pet.value = updatedPet
        savePet(updatedPet)

        val action = when (activity) {
            ActivityType.WORKING -> "working"
            ActivityType.STUDYING -> "studying"
            ActivityType.TRAINING -> "training"
            ActivityType.RELAXING -> "sunbathing"
            else -> "idle"
        }
        val emoji = when (activity) {
            ActivityType.WORKING -> "💼"
            ActivityType.STUDYING -> "📚"
            ActivityType.TRAINING -> "🥊"
            ActivityType.RELAXING -> "🌳"
            else -> ""
        }
        logEvent(pet.id, EventType.STARTED_WORK, context.getString(R.string.tama_event_started_activity, pet.name, activityDisplayName(activity)))
        return ActionResult(true, context.getString(R.string.tama_action_started_activity, emoji, activityDisplayName(activity)), action)
    }

    fun observeStudyLabels(petId: String): Flow<List<TamaStudyLabelEntity>> =
        dao.observeStudyLabels(petId)

    fun observeStudySessions(petId: String): Flow<List<TamaStudySessionEntity>> =
        dao.observeStudySessionsForPet(petId)

    fun observeActiveStudySession(petId: String): Flow<TamaStudySessionEntity?> =
        dao.observeActiveStudySession(petId)

    fun observeQuestChecklist(petId: String): Flow<List<TamaQuestChecklistItemEntity>> =
        dao.observeQuestChecklist(petId)

    suspend fun startNormalStudySession(
        selectedLabelIds: Set<String>,
        newLabelNames: List<String>
    ): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val validation = validateStudyStart(pet)
        if (validation != null) return validation
        val now = System.currentTimeMillis()
        val labels = resolveStudyLabels(pet.id, selectedLabelIds, newLabelNames, now)
        val session = TamaStudySessionEntity(
            id = UUID.randomUUID().toString(),
            petId = pet.id,
            mode = TamaStudyMode.NORMAL.name,
            status = TamaStudyStatus.ACTIVE.name,
            labelIdsJson = TamaStudySessionSupport.encodeStringList(labels.map { it.id }),
            labelNamesSnapshotJson = TamaStudySessionSupport.encodeStringList(labels.map { it.name }),
            focusMinutes = 0,
            shortBreakMinutes = 0,
            longBreakMinutes = 0,
            roundsPlanned = 0,
            currentRound = 0,
            currentPhase = TamaStudyPhase.FOCUS.name,
            phaseStartedAt = now,
            phaseEndsAt = null,
            focusAccumulatedMs = 0L,
            restAccumulatedMs = 0L,
            educationAwarded = 0f,
            startedAt = now,
            completedAt = null,
            stoppedAt = null,
            lastUpdatedAt = now
        )
        return beginStudySession(pet, session, now)
    }

    suspend fun startPomodoroStudySession(
        selectedLabelIds: Set<String>,
        newLabelNames: List<String>,
        settings: TamaPomodoroSettings
    ): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val validation = validateStudyStart(pet)
        if (validation != null) return validation
        val normalized = settings.normalized()
        val now = System.currentTimeMillis()
        val labels = resolveStudyLabels(pet.id, selectedLabelIds, newLabelNames, now)
        val session = TamaStudySessionEntity(
            id = UUID.randomUUID().toString(),
            petId = pet.id,
            mode = TamaStudyMode.POMODORO.name,
            status = TamaStudyStatus.ACTIVE.name,
            labelIdsJson = TamaStudySessionSupport.encodeStringList(labels.map { it.id }),
            labelNamesSnapshotJson = TamaStudySessionSupport.encodeStringList(labels.map { it.name }),
            focusMinutes = normalized.focusMinutes,
            shortBreakMinutes = normalized.shortBreakMinutes,
            longBreakMinutes = normalized.longBreakMinutes,
            roundsPlanned = normalized.rounds,
            currentRound = 1,
            currentPhase = TamaStudyPhase.FOCUS.name,
            phaseStartedAt = now,
            phaseEndsAt = now + normalized.focusMinutes * 60_000L,
            focusAccumulatedMs = 0L,
            restAccumulatedMs = 0L,
            educationAwarded = 0f,
            startedAt = now,
            completedAt = null,
            stoppedAt = null,
            lastUpdatedAt = now
        )
        return beginStudySession(pet, session, now)
    }

    suspend fun refreshActiveStudySession(now: Long = System.currentTimeMillis()): TamaStudySessionEntity? {
        val pet = _pet.value ?: return null
        val result = TamaStudySessionSupport.advanceActiveSession(
            context = context,
            dao = dao,
            pet = pet,
            now = now,
            showNotification = false
        )
        if (result.pet != pet) {
            _pet.value = result.pet
        }
        result.session?.let {
            TamaNotificationScheduler.scheduleForPet(context.applicationContext, pet.id)
        }
        return result.session ?: dao.getActiveStudySession(pet.id)
    }

    private suspend fun validateStudyStart(pet: TamaPet): ActionResult? {
        if (pet.isSleeping) return ActionResult(false, context.getString(R.string.tama_sleeping_busy, pet.name))
        if (pet.currentActivity != ActivityType.NONE) {
            return ActionResult(false, context.getString(R.string.tama_action_already_busy, pet.name))
        }
        if (!pet.stage.canStudy()) {
            return ActionResult(false, context.getString(R.string.tama_action_only_students_study))
        }
        return null
    }

    private suspend fun resolveStudyLabels(
        petId: String,
        selectedLabelIds: Set<String>,
        newLabelNames: List<String>,
        now: Long
    ): List<TamaStudyLabelEntity> {
        val existing = dao.getStudyLabels(petId)
        val selected = existing.filter { it.id in selectedLabelIds }
        val created = newLabelNames
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .map { name ->
                existing.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: TamaStudyLabelEntity(
                        id = UUID.randomUUID().toString(),
                        petId = petId,
                        name = name.take(48),
                        createdAt = now,
                        lastUsedAt = now
                    )
            }
        val labels = (selected + created).distinctBy { it.id }
        labels.forEach { label ->
            dao.saveStudyLabel(label.copy(lastUsedAt = now))
        }
        return labels
    }

    private suspend fun beginStudySession(
        pet: TamaPet,
        session: TamaStudySessionEntity,
        now: Long
    ): ActionResult {
        dao.saveStudySession(session)
        val updatedPet = pet.copy(
            currentActivity = ActivityType.STUDYING,
            currentWorkJobId = null,
            activityStartTime = now
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        val labels = TamaStudySessionSupport.decodeLabelNames(session).joinToString(", ").ifBlank {
            context.getString(R.string.tama_study_no_labels_short)
        }
        logEvent(
            pet.id,
            EventType.STARTED_WORK,
            context.getString(R.string.tama_event_started_study_session, pet.name, labels)
        )
        TamaNotificationScheduler.scheduleForPet(context.applicationContext, pet.id)
        return ActionResult(
            true,
            context.getString(R.string.tama_action_started_activity, "📚", activityDisplayName(ActivityType.STUDYING)),
            "studying"
        )
    }

    /**
     * Stop current activity and collect rewards.
     */
    suspend fun stopActivity(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (pet.currentActivity == ActivityType.NONE) {
            return ActionResult(false, context.getString(R.string.tama_action_not_doing_anything))
        }

        val now = System.currentTimeMillis()
        val activityStartTime = pet.activityStartTime ?: now
        val pausedDurationMs = (now - activityStartTime).coerceAtLeast(0L)
        val hoursActive = (pausedDurationMs / (1000 * 60 * 60f)).coerceAtMost(8f)
        val minutesActive = (pausedDurationMs / (1000 * 60f)).toInt()

        if (pet.currentActivity == ActivityType.STUDYING) {
            recoverAdventureGateProfile(pet.id)
            val result = TamaStudySessionSupport.stopActiveSession(
                context = context,
                dao = dao,
                pet = pet,
                now = now
            )
            _pet.value = result.pet
            TamaNotificationScheduler.scheduleForPet(context.applicationContext, pet.id)
            return ActionResult(true, result.message, "idle")
        }

        recoverAdventureGateProfile(pet.id)
        var updatedPet = pet
        var message = ""

        when (pet.currentActivity) {
            ActivityType.WORKING -> {
                val job = TamaWorkCatalog.jobById(pet.currentWorkJobId) ?: TamaWorkCatalog.jobs.first()
                val earnings = (hoursActive * job.hourlyPay).toLong().coerceAtLeast(if (minutesActive > 0) 1 else 0)
                val workXp = earnings.toInt().coerceAtLeast(0)
                updatedPet = updatedPet.copy(money = pet.money + earnings)
                grantAdventureGateWorkXp(pet, workXp)
                message = context.getString(R.string.tama_action_work_result, earnings)
                logEvent(
                    pet.id,
                    EventType.GOT_PAID,
                    context.getString(R.string.tama_event_earned_job, earnings.toInt(), context.getString(job.titleRes))
                )
            }
            ActivityType.TRAINING -> {
                val tier = TamaTrainingCatalog.tierById(pet.currentWorkJobId) ?: TamaTrainingCatalog.tiers.first()
                val earnings = (hoursActive * tier.hourlyPay).toLong().coerceAtLeast(if (minutesActive > 0) 1 else 0)
                val exerciseGain = hoursActive * TAMA_TRAINING_EXERCISE_PER_HOUR
                val happinessGain = hoursActive * TAMA_TRAINING_HAPPINESS_PER_HOUR
                updatedPet = updatedPet.copy(
                    money = pet.money + earnings,
                    exerciseLevel = pet.exerciseLevel + exerciseGain,
                    stats = pet.stats.copy(
                        happiness = (pet.stats.happiness + happinessGain).coerceIn(0f, 100f)
                    )
                )
                message = context.getString(
                    R.string.tama_action_training_result,
                    exerciseGain.roundToInt(),
                    happinessGain.roundToInt(),
                    earnings.toInt()
                )
                logEvent(
                    pet.id,
                    EventType.GOT_PAID,
                    context.getString(
                        R.string.tama_event_trained,
                        exerciseGain.roundToInt(),
                        earnings.toInt(),
                        context.getString(tier.titleRes)
                    ),
                    statsChange = mapOf(
                        "exercise" to exerciseGain,
                        "happiness" to happinessGain
                    )
                )
            }
            ActivityType.STUDYING -> {
                val intGain = (hoursActive * 5f)
                updatedPet = updatedPet.copy(educationLevel = pet.educationLevel + intGain)
                message = context.getString(R.string.tama_action_study_result, intGain.toInt())
                logEvent(pet.id, EventType.STUDIED, context.getString(R.string.tama_event_studied, intGain.toInt()))
            }
            ActivityType.RELAXING -> {
                val happinessGain = (hoursActive * 40f)  // 40 happiness/hour
                val healthGain = (hoursActive * 2f)
                val introspectionGain = hoursActive * TAMA_RELAX_INTROSPECTION_PER_HOUR
                updatedPet = updatedPet.copy(
                    introspectionLevel = pet.introspectionLevel + introspectionGain,
                    stats = pet.stats.copy(
                        happiness = (pet.stats.happiness + happinessGain).coerceIn(0f, 100f),
                        health = (pet.stats.health + healthGain).coerceIn(0f, 100f)
                    )
                )
                message = context.getString(
                    R.string.tama_action_relax_result,
                    happinessGain.toInt(),
                    healthGain.toInt(),
                    introspectionGain.toInt()
                )
                logEvent(
                    pet.id,
                    EventType.RELAXED,
                    context.getString(R.string.tama_event_relaxed, introspectionGain.toInt()),
                    statsChange = mapOf(
                        "happiness" to happinessGain,
                        "health" to healthGain,
                        "introspection" to introspectionGain
                    )
                )
            }
            else -> {}
        }

        updatedPet = shiftPoopTimersForPausedActivity(updatedPet, pausedDurationMs).copy(
            currentActivity = ActivityType.NONE,
            currentWorkJobId = null,
            activityStartTime = null
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        if (pet.currentActivity == ActivityType.TRAINING) {
            recoverAdventureGateProfile(updatedPet.id)
        }

        return ActionResult(true, message, "idle")
    }

    /**
     * Start a job.
     */
    suspend fun startWork(jobId: String): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val job = TamaWorkCatalog.jobById(jobId)
            ?: return ActionResult(false, context.getString(R.string.tama_work_job_missing))
        if (pet.educationLevel < job.requiredEducation) {
            return ActionResult(
                false,
                context.getString(
                    R.string.tama_work_job_locked,
                    job.requiredEducation,
                    context.getString(job.titleRes)
                )
            )
        }
        if (pet.isSleeping) return ActionResult(false, context.getString(R.string.tama_sleeping_busy, pet.name))
        if (pet.currentActivity != ActivityType.NONE) {
            return ActionResult(false, context.getString(R.string.tama_action_already_busy, pet.name))
        }
        if (!pet.stage.canWork()) {
            return ActionResult(false, context.getString(R.string.tama_action_only_teens_work))
        }

        val updatedPet = pet.copy(
            currentActivity = ActivityType.WORKING,
            currentWorkJobId = job.id,
            activityStartTime = System.currentTimeMillis()
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(
            pet.id,
            EventType.STARTED_WORK,
            context.getString(R.string.tama_event_started_job, pet.name, context.getString(job.titleRes))
        )
        return ActionResult(
            true,
            context.getString(R.string.tama_work_started, context.getString(job.titleRes), job.hourlyPay.toInt()),
            "working"
        )
    }

    suspend fun finishWork(): Long {
        val pet = _pet.value ?: return 0
        if (pet.currentActivity != ActivityType.WORKING) return 0
        val before = pet.money
        stopActivity()
        return (_pet.value?.money ?: before) - before
    }

    suspend fun startTraining(tierId: String): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val tier = TamaTrainingCatalog.tierById(tierId)
            ?: return ActionResult(false, context.getString(R.string.tama_training_tier_missing))
        if (pet.exerciseLevel < tier.requiredExercise) {
            return ActionResult(
                false,
                context.getString(
                    R.string.tama_training_tier_locked,
                    tier.requiredExercise,
                    context.getString(tier.titleRes)
                )
            )
        }
        if (pet.isSleeping) return ActionResult(false, context.getString(R.string.tama_sleeping_busy, pet.name))
        if (pet.currentActivity != ActivityType.NONE) {
            return ActionResult(false, context.getString(R.string.tama_action_already_busy, pet.name))
        }
        if (!pet.stage.canTrain()) {
            return ActionResult(false, context.getString(R.string.tama_action_only_students_train))
        }

        recoverAdventureGateProfile(pet.id)
        val updatedPet = pet.copy(
            currentActivity = ActivityType.TRAINING,
            currentWorkJobId = tier.id,
            activityStartTime = System.currentTimeMillis()
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(
            pet.id,
            EventType.STARTED_WORK,
            context.getString(R.string.tama_event_started_training, pet.name, context.getString(tier.titleRes))
        )
        return ActionResult(
            true,
            context.getString(R.string.tama_training_started, context.getString(tier.titleRes), tier.hourlyPay.toInt()),
            "training"
        )
    }

    // ==================== Travel System ====================

    /**
     * Travel to a new location (costs energy).
     */
    suspend fun travelTo(location: TamaLocation): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (pet.isSleeping) return ActionResult(false, context.getString(R.string.tama_sleeping_busy, pet.name))
        if (pet.currentActivity != ActivityType.NONE) return ActionResult(false, context.getString(R.string.tama_busy_cannot_travel, pet.name))
        if (pet.stage == GrowthStage.EGG) return ActionResult(false, context.getString(R.string.tama_eggs_cannot_travel))

        val energyCost = previewTravelEnergyCost(_currentLocation.value, location)

        if (pet.stats.energy < energyCost) {
            return ActionResult(false, context.getString(R.string.tama_too_tired_to_travel, pet.name, energyCost))
        }

        // Update pet
        val newDiscovered = pet.discoveredLocationIds + location.id
        val newStats = pet.stats.copy(energy = pet.stats.energy - energyCost)
        val now = System.currentTimeMillis()
        val (parkEncounter, parkGiftItem) = if (location.type == com.example.llamadroid.tama.data.LocationType.PARK) {
            buildParkEncounter(pet, now)
        } else {
            null to null
        }
        val ambientNpcState = if (location.type == com.example.llamadroid.tama.data.LocationType.PARK) {
            null
        } else {
            TamaAmbientNpcCatalog.createState(location.type, now)
        }
        val updatedInventory = if (parkGiftItem != null) {
            addInventoryItem(pet.inventory, parkGiftItem, parkGiftItem.quantity.coerceAtLeast(1))
        } else {
            pet.inventory
        }
        val updatedPet = pet.copy(
            stats = newStats,
            currentLocationId = location.id,
            discoveredLocationIds = newDiscovered,
            currentParkEncounter = parkEncounter,
            currentAmbientNpc = ambientNpcState,
            inventory = updatedInventory
        )
        _pet.value = updatedPet
        savePet(updatedPet)

        // Update location state
        _currentLocation.value = location

        // Log discovery if new
        if (!pet.discoveredLocationIds.contains(location.id)) {
            logEvent(pet.id, EventType.DISCOVERED, context.getString(R.string.tama_event_discovered, location.name, location.type.emoji),
                locationId = location.id)
        }

        logEvent(pet.id, EventType.TRAVELED, context.getString(R.string.tama_event_traveled, pet.name, location.name),
            locationId = location.id,
            statsChange = if (energyCost > 0) mapOf("energy" to -energyCost.toFloat()) else emptyMap())

        parkEncounter?.let { encounter ->
            val npcName = TamaParkSocialCatalog.localizedName(context, encounter.npcId)
            val line = localizeParkEncounterLine(encounter)
            logEvent(
                updatedPet.id,
                EventType.MET_NPC,
                context.getString(
                    R.string.tama_park_event_met_friend_line,
                    npcName,
                    line
                ),
                locationId = location.id,
                npcId = encounter.npcId
            )
            if (encounter.giftItemId != null && parkGiftItem != null) {
                logEvent(
                    updatedPet.id,
                    EventType.RECEIVED_GIFT,
                    context.getString(
                        R.string.tama_park_gift_event,
                        npcName,
                        inventoryItemDisplayName(context, parkGiftItem)
                    ),
                    locationId = location.id,
                    npcId = encounter.npcId
                )
            }
        }

        ambientNpcState?.let { ambientNpc ->
            val npcName = TamaAmbientNpcCatalog.resolveName(context, ambientNpc.npcId)
            val line = TamaAmbientNpcCatalog.resolveLine(context, ambientNpc)
            logEvent(
                updatedPet.id,
                EventType.MET_NPC,
                context.getString(R.string.tama_event_met_ambient_npc, npcName, line),
                locationId = location.id,
                npcId = ambientNpc.npcId
            )
        }

        val arrivalMessage = if (location.type == LocationType.HOME && energyCost == 0) {
            context.getString(R.string.tama_arrived_home_free, location.name)
        } else {
            context.getString(R.string.tama_arrived_energy_cost, location.name, energyCost.toInt())
        }

        return ActionResult(
            true,
            arrivalMessage,
            "walking"
        )
    }

    suspend fun dismissParkEncounter(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (pet.currentParkEncounter == null) {
            return ActionResult(false, context.getString(R.string.tama_park_no_encounter))
        }
        val updatedPet = pet.copy(currentParkEncounter = null)
        _pet.value = updatedPet
        savePet(updatedPet)
        return ActionResult(true, context.getString(R.string.tama_park_back_to_relax, pet.name))
    }

    suspend fun acceptRecyclerEncounter(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val encounter = pet.currentParkEncounter
            ?: return ActionResult(false, context.getString(R.string.tama_park_no_encounter))
        if (encounter.type != TamaParkEncounterType.RECYCLER) {
            return ActionResult(false, context.getString(R.string.tama_park_wrong_encounter))
        }
        val dateKey = TamaParkSocialCatalog.parkDateKey(Calendar.getInstance())
        val updatedPet = pet.copy(
            currentParkEncounter = encounter.copy(phase = TamaParkEncounterPhase.CLEANUP),
            lastRecyclerEncounterDate = dateKey
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        return ActionResult(true, context.getString(R.string.tama_park_recycler_cleanup_started), "playing")
    }

    suspend fun declineRecyclerEncounter(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val encounter = pet.currentParkEncounter
            ?: return ActionResult(false, context.getString(R.string.tama_park_no_encounter))
        if (encounter.type != TamaParkEncounterType.RECYCLER) {
            return ActionResult(false, context.getString(R.string.tama_park_wrong_encounter))
        }
        val dateKey = TamaParkSocialCatalog.parkDateKey(Calendar.getInstance())
        val updatedPet = pet.copy(
            currentParkEncounter = null,
            lastRecyclerEncounterDate = dateKey
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(
            pet.id,
            EventType.OTHER,
            context.getString(R.string.tama_park_recycler_declined, pet.name),
            locationId = pet.currentLocationId,
            npcId = encounter.npcId
        )
        return ActionResult(true, context.getString(R.string.tama_park_recycler_declined_toast))
    }

    suspend fun finishRecyclerEncounter(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val encounter = pet.currentParkEncounter
            ?: return ActionResult(false, context.getString(R.string.tama_park_no_encounter))
        if (encounter.type != TamaParkEncounterType.RECYCLER) {
            return ActionResult(false, context.getString(R.string.tama_park_wrong_encounter))
        }
        val updatedPet = pet.copy(currentParkEncounter = null)
        _pet.value = updatedPet
        savePet(updatedPet)
        awardMoney(
            200L,
            context.getString(R.string.tama_park_recycler_reward_details, pet.name)
        )
        logEvent(
            pet.id,
            EventType.OTHER,
            context.getString(R.string.tama_park_recycler_reward_details, pet.name),
            locationId = pet.currentLocationId,
            npcId = encounter.npcId
        )
        return ActionResult(true, context.getString(R.string.tama_park_recycler_reward_toast, 200))
    }

    suspend fun acceptSellerEncounter(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val encounter = pet.currentParkEncounter
            ?: return ActionResult(false, context.getString(R.string.tama_park_no_encounter))
        if (encounter.type != TamaParkEncounterType.SELLER) {
            return ActionResult(false, context.getString(R.string.tama_park_wrong_encounter))
        }
        val updatedPet = pet.copy(
            currentParkEncounter = encounter.copy(phase = TamaParkEncounterPhase.SELLER_MARKET)
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        return ActionResult(true, context.getString(R.string.tama_park_seller_market_open))
    }

    suspend fun declineSellerEncounter(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val encounter = pet.currentParkEncounter
            ?: return ActionResult(false, context.getString(R.string.tama_park_no_encounter))
        if (encounter.type != TamaParkEncounterType.SELLER) {
            return ActionResult(false, context.getString(R.string.tama_park_wrong_encounter))
        }
        val updatedPet = pet.copy(currentParkEncounter = null)
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(
            pet.id,
            EventType.OTHER,
            context.getString(R.string.tama_park_seller_declined, pet.name),
            locationId = pet.currentLocationId,
            npcId = encounter.npcId
        )
        return ActionResult(true, context.getString(R.string.tama_park_back_to_relax, pet.name))
    }

    suspend fun finishSellerEncounter(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val encounter = pet.currentParkEncounter
            ?: return ActionResult(false, context.getString(R.string.tama_park_no_encounter))
        if (encounter.type != TamaParkEncounterType.SELLER) {
            return ActionResult(false, context.getString(R.string.tama_park_wrong_encounter))
        }
        val updatedPet = pet.copy(currentParkEncounter = null)
        _pet.value = updatedPet
        savePet(updatedPet)
        return ActionResult(true, context.getString(R.string.tama_park_back_to_relax, pet.name))
    }

    suspend fun sellToParkSeller(item: InventoryItem, quantity: Int = 1): ActionResult {
        if (!FarmTradeItemCatalog.isTradeItem(item.id)) {
            return ActionResult(false, context.getString(R.string.tama_park_seller_only_crops))
        }
        val quote = ensureMarketQuotesForPet(
            petId = _pet.value?.id ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        ).firstOrNull { it.itemId == item.id }
            ?: return ActionResult(false, context.getString(R.string.tama_park_seller_only_crops))
        val result = sellItem(item, quantity, quote.currentPrice.toLong())
        if (result.success) {
            val pet = _pet.value
            recordMarketSale(pet?.id ?: return result, item.id, quantity)
            val displayName = inventoryItemDisplayName(context, item)
            logEvent(
                pet?.id ?: return result,
                EventType.OTHER,
                context.getString(
                    R.string.tama_park_seller_sale_details,
                    quantity,
                    displayName,
                    quote.currentPrice * quantity
                ),
                locationId = pet.currentLocationId,
                npcId = pet.currentParkEncounter?.npcId
            )
        }
        return result
    }

    suspend fun getParkMarketBoard(now: Long = System.currentTimeMillis()): TamaMarketBoard {
        val pet = _pet.value ?: return TamaMarketBoard(emptyList(), TamaMarketPricing.quoteWeekKey(now), TamaMarketPricing.nextFridayRefreshAt(now))
        val quotes = ensureMarketQuotesForPet(pet.id, now)
        return TamaMarketBoard(
            quotes = quotes,
            quoteWeekKey = TamaMarketPricing.quoteWeekKey(now),
            nextRefreshAt = TamaMarketPricing.nextFridayRefreshAt(now)
        )
    }

    private suspend fun ensureMarketQuotesForPet(
        petId: String,
        now: Long = System.currentTimeMillis()
    ): List<TamaMarketQuote> {
        val weekKey = TamaMarketPricing.quoteWeekKey(now)
        val existing = dao.getMarketQuotesForPet(petId).associateBy { it.itemId }
        val definitions = FarmTradeItemCatalog.allDefinitions()
        val updatedEntities = definitions.map { definition ->
            val row = existing[definition.inventoryId]
            when {
                row == null -> TamaMarketQuoteEntity(
                    petId = petId,
                    itemId = definition.inventoryId,
                    quoteWeekKey = weekKey,
                    currentPrice = TamaMarketPricing.maxPrice(definition.inventoryId),
                    unitsSoldSinceRefresh = 0,
                    updatedAt = now
                )
                row.quoteWeekKey != weekKey -> row.copy(
                    quoteWeekKey = weekKey,
                    currentPrice = TamaMarketPricing.priceForWeeklySales(
                        definition.inventoryId,
                        row.unitsSoldSinceRefresh
                    ),
                    unitsSoldSinceRefresh = 0,
                    updatedAt = now
                )
                else -> row
            }
        }
        val changed = updatedEntities.filter { entity ->
            existing[entity.itemId] != entity
        }
        if (changed.isNotEmpty()) {
            dao.saveMarketQuotes(changed)
        }
        return updatedEntities.mapNotNull(::marketQuoteEntityToDomain)
    }

    private suspend fun recordMarketSale(petId: String, itemId: String, quantity: Int) {
        val now = System.currentTimeMillis()
        val quote = ensureMarketQuotesForPet(petId, now).firstOrNull { it.itemId == itemId } ?: return
        dao.saveMarketQuote(
            TamaMarketQuoteEntity(
                petId = petId,
                itemId = itemId,
                quoteWeekKey = quote.quoteWeekKey,
                currentPrice = quote.currentPrice,
                unitsSoldSinceRefresh = quote.unitsSoldSinceRefresh + quantity.coerceAtLeast(0),
                updatedAt = now
            )
        )
    }

    private fun marketQuoteEntityToDomain(entity: TamaMarketQuoteEntity): TamaMarketQuote? {
        val definition = FarmTradeItemCatalog.definitionForInventoryId(entity.itemId) ?: return null
        val maxPrice = TamaMarketPricing.maxPrice(entity.itemId)
        val minPrice = TamaMarketPricing.minPrice(entity.itemId).coerceAtMost(maxPrice)
        return TamaMarketQuote(
            itemId = entity.itemId,
            displayText = definition.displayText,
            assetPath = definition.assetPath,
            currentPrice = entity.currentPrice.coerceIn(minPrice, maxPrice),
            maxPrice = maxPrice,
            minPrice = minPrice,
            unitsSoldSinceRefresh = entity.unitsSoldSinceRefresh.coerceAtLeast(0),
            quoteWeekKey = entity.quoteWeekKey
        )
    }

    suspend fun getParkQuestBoard(now: Long = System.currentTimeMillis()): TamaQuestBoard {
        val pet = _pet.value ?: return TamaQuestBoard(emptyList(), emptyList(), nextLocalMidnightMillis(now))
        val dateKey = questDateKey(now)
        dao.deleteStaleAvailableQuests(pet.id, dateKey)
        expireAcceptedParkQuests(pet.id, now)

        val currentQuests = dao.getQuestsForPet(pet.id).map(::questEntityToDomain)
        val hasAnyQuestForToday = currentQuests.any { it.generatedDateKey == dateKey }
        if (!hasAnyQuestForToday) {
            val generated = generateDailyParkQuests(pet.id, dateKey, now)
            dao.saveQuests(generated.map(::questToEntity))
        }

        val refreshed = dao.getQuestsForPet(pet.id).map(::questEntityToDomain)
        return TamaQuestBoard(
            available = refreshed.filter { it.status == TamaQuestStatus.AVAILABLE && it.generatedDateKey == dateKey },
            accepted = refreshed.filter {
                it.status == TamaQuestStatus.ACCEPTED &&
                    (it.expiresAt ?: Long.MIN_VALUE) > now
            }.sortedBy { it.expiresAt ?: Long.MAX_VALUE },
            nextRefreshAt = nextLocalMidnightMillis(now)
        )
    }

    suspend fun acceptParkQuest(questId: String, now: Long = System.currentTimeMillis()): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val quest = dao.getQuestsForPet(pet.id)
            .map(::questEntityToDomain)
            .firstOrNull { it.id == questId }
            ?: return ActionResult(false, context.getString(R.string.tama_quest_not_found))
        if (quest.status != TamaQuestStatus.AVAILABLE) {
            return ActionResult(false, context.getString(R.string.tama_quest_unavailable))
        }
        val acceptedQuest = quest.copy(
            status = TamaQuestStatus.ACCEPTED,
            acceptedAt = now,
            expiresAt = now + PARK_QUEST_ACCEPT_WINDOW_MS
        )
        dao.saveQuest(questToEntity(acceptedQuest))
        val npcName = TamaParkSocialCatalog.localizedName(context, acceptedQuest.npcId)
        val questSummary = acceptedQuest.summary.resolve(context.resources.configuration.locales[0])
        logEvent(
            pet.id,
            EventType.QUEST_STARTED,
            questAcceptedEventText(context, pet.name, npcName, questSummary, acceptedQuest.rewardCoins),
            locationId = pet.currentLocationId,
            npcId = acceptedQuest.npcId
        )
        return ActionResult(true, context.getString(R.string.tama_quest_accept_success, npcName))
    }

    suspend fun addQuestToChecklist(questId: String, now: Long = System.currentTimeMillis()): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val quest = dao.getQuestsForPet(pet.id)
            .map(::questEntityToDomain)
            .firstOrNull { it.id == questId }
            ?: return ActionResult(false, context.getString(R.string.tama_quest_not_found))
        var changed = false
        quest.requests.forEach { request ->
            val existing = dao.getQuestChecklistItem(pet.id, request.itemId)
            val sourceQuestIds = decodeQuestChecklistSources(existing?.sourceQuestIdsJson)
            if (sourceQuestIds.add(quest.id)) {
                val updated = existing?.copy(
                    quantity = (existing.quantity + request.quantity).coerceAtLeast(1),
                    checked = false,
                    sourceQuestIdsJson = Json.encodeToString(sourceQuestIds.toList()),
                    updatedAt = now
                ) ?: TamaQuestChecklistItemEntity(
                    id = UUID.randomUUID().toString(),
                    petId = pet.id,
                    itemId = request.itemId,
                    quantity = request.quantity.coerceAtLeast(1),
                    checked = false,
                    sourceQuestIdsJson = Json.encodeToString(sourceQuestIds.toList()),
                    createdAt = now,
                    updatedAt = now
                )
                dao.saveQuestChecklistItem(updated)
                changed = true
            }
        }
        return if (changed) {
            ActionResult(true, context.getString(R.string.tama_quest_added_to_checklist))
        } else {
            ActionResult(true, context.getString(R.string.tama_quest_already_in_checklist))
        }
    }

    suspend fun addQuestChecklistItem(
        itemId: String,
        quantity: Int = 1,
        now: Long = System.currentTimeMillis()
    ): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        if (!FarmTradeItemCatalog.isTradeItem(itemId)) {
            return ActionResult(false, context.getString(R.string.tama_quest_checklist_invalid_item))
        }
        val safeQuantity = quantity.coerceAtLeast(1)
        val existing = dao.getQuestChecklistItem(pet.id, itemId)
        val updated = existing?.copy(
            quantity = (existing.quantity + safeQuantity).coerceAtLeast(1),
            checked = false,
            updatedAt = now
        ) ?: TamaQuestChecklistItemEntity(
            id = UUID.randomUUID().toString(),
            petId = pet.id,
            itemId = itemId,
            quantity = safeQuantity,
            checked = false,
            createdAt = now,
            updatedAt = now
        )
        dao.saveQuestChecklistItem(updated)
        return ActionResult(true, context.getString(R.string.tama_quest_checklist_item_added))
    }

    suspend fun updateQuestChecklistItem(
        itemId: String,
        quantity: Int,
        checked: Boolean,
        now: Long = System.currentTimeMillis()
    ): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val existing = dao.getQuestChecklistItem(pet.id, itemId)
            ?: return ActionResult(false, context.getString(R.string.tama_quest_checklist_not_found))
        if (quantity <= 0) {
            dao.deleteQuestChecklistItem(pet.id, itemId)
            return ActionResult(true, context.getString(R.string.tama_quest_checklist_item_deleted))
        }
        dao.saveQuestChecklistItem(
            existing.copy(
                quantity = quantity,
                checked = checked,
                updatedAt = now
            )
        )
        return ActionResult(true, context.getString(R.string.tama_quest_checklist_item_updated))
    }

    suspend fun deleteQuestChecklistItem(itemId: String): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        dao.deleteQuestChecklistItem(pet.id, itemId)
        return ActionResult(true, context.getString(R.string.tama_quest_checklist_item_deleted))
    }

    suspend fun clearCheckedQuestChecklistItems(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val checkedCount = dao.getQuestChecklist(pet.id).count { it.checked }
        if (checkedCount == 0) {
            return ActionResult(false, context.getString(R.string.tama_quest_checklist_no_checked))
        }
        dao.deleteCheckedQuestChecklistItems(pet.id)
        return ActionResult(true, context.getString(R.string.tama_quest_checklist_checked_cleared))
    }

    suspend fun canFinishParkQuest(questId: String): Boolean {
        val pet = _pet.value ?: return false
        val quest = dao.getQuestsForPet(pet.id)
            .map(::questEntityToDomain)
            .firstOrNull { it.id == questId } ?: return false
        return hasQuestRequirements(pet.inventory, quest)
    }

    suspend fun finishParkQuest(questId: String, now: Long = System.currentTimeMillis()): ParkQuestFinishResult {
        val pet = _pet.value ?: return ParkQuestFinishResult(false, context.getString(R.string.tama_error_no_pet))
        expireAcceptedParkQuests(pet.id, now)
        val quest = dao.getQuestsForPet(pet.id)
            .map(::questEntityToDomain)
            .firstOrNull { it.id == questId }
            ?: return ParkQuestFinishResult(false, context.getString(R.string.tama_quest_not_found))
        if (quest.status != TamaQuestStatus.ACCEPTED || (quest.expiresAt ?: 0L) <= now) {
            return ParkQuestFinishResult(false, context.getString(R.string.tama_quest_expired))
        }
        if (!hasQuestRequirements(pet.inventory, quest)) {
            return ParkQuestFinishResult(false, context.getString(R.string.tama_quest_missing_items))
        }

        quest.requests.forEach { request ->
            val item = _pet.value?.inventory?.firstOrNull { it.id == request.itemId }
                ?: return ParkQuestFinishResult(false, context.getString(R.string.tama_quest_missing_items))
            if (!consumeItem(item, request.quantity)) {
                return ParkQuestFinishResult(false, context.getString(R.string.tama_quest_missing_items))
            }
        }

        val npcName = TamaParkSocialCatalog.localizedName(context, quest.npcId)
        val questSummary = quest.summary.resolve(context.resources.configuration.locales[0])
        val thanksLine = questCompletionThanksMessage(npcName, quest.rewardCoins)
        awardMoney(
            quest.rewardCoins,
            questRewardDetailsText(context, pet.name, npcName, questSummary, quest.rewardCoins)
        )

        val completedQuest = quest.copy(
            status = TamaQuestStatus.COMPLETED,
            completedAt = now
        )
        dao.saveQuest(questToEntity(completedQuest))
        logEvent(
            pet.id,
            EventType.QUEST_COMPLETED,
            questCompletedEventText(context, pet.name, npcName, questSummary, completedQuest.rewardCoins),
            locationId = pet.currentLocationId,
            npcId = completedQuest.npcId
        )
        return ParkQuestFinishResult(
            success = true,
            message = thanksLine,
            presentation = TamaQuestCompletionPresentation(
                npcId = completedQuest.npcId,
                npcName = npcName,
                thanksLine = thanksLine,
                rewardCoins = completedQuest.rewardCoins
            )
        )
    }

    suspend fun harvestCrop(crop: PlantedCrop): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        return if (crop.isDecayed) {
            grantItem(
                InventoryItem(
                    id = "rotten_crop",
                    name = context.getString(R.string.tama_item_rotten_crop),
                    type = ItemType.MATERIAL
                ),
                1
            )
            logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_collected_rotten_crop))
            ActionResult(true, context.getString(R.string.tama_event_collected_rotten_crop), "harvesting")
        } else {
            val quantity = harvestYieldForCrop(crop)
            val displayName = cropDisplayName(context, crop.type)
            grantItem(
                InventoryItem(
                    id = "crop_${crop.type}",
                    name = displayName,
                    type = ItemType.CROP
                ),
                quantity
            )
            logEvent(
                pet.id,
                EventType.HARVESTED,
                context.getString(R.string.tama_event_harvested, quantity, displayName)
            )
            ActionResult(
                true,
                context.getString(R.string.tama_farm_harvest_toast, quantity, displayName),
                "harvesting"
            )
        }
    }

    fun previewTravelEnergyCost(currentLocation: TamaLocation?, destination: TamaLocation): Int {
        if (destination.type == LocationType.HOME) return 0
        return if (currentLocation != null) {
            val distance = kotlin.math.abs(currentLocation.x - destination.x) +
                kotlin.math.abs(currentLocation.y - destination.y)
            sharedTravelEnergyCost(distance)
        } else {
            3
        }
    }


    suspend fun setCurrentLocation(location: TamaLocation) {
        _currentLocation.value = location
        // Also mark as discovered
        val pet = _pet.value ?: return
        if (!pet.discoveredLocationIds.contains(location.id)) {
            val updatedPet = pet.copy(
                discoveredLocationIds = pet.discoveredLocationIds + location.id,
                currentLocationId = location.id
            )
            _pet.value = updatedPet
            savePet(updatedPet)
        }
    }

    // ==================== Economy System ====================

    suspend fun buyItem(item: InventoryItem, quantity: Int, pricePerUnit: Int): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        TamaPotionCatalog.byId(item.id)?.let { potion ->
            if (potion.kind == TamaPotionKind.STAGE && potion.targetStage == pet.stage) {
                return ActionResult(false, context.getString(R.string.tama_potion_stage_already_current))
            }
        }
        farmToolFamilyId(item.id)?.takeIf { item.type == ItemType.TOOL }?.let { familyId ->
            return buyFarmToolDurability(pet, item, familyId, quantity, pricePerUnit)
        }
        val totalCost = pricePerUnit.toLong() * quantity
        if (pet.money < totalCost) {
            return ActionResult(false, context.getString(R.string.tama_action_not_enough_coins, totalCost, pet.money))
        }

        val newInventory = pet.inventory.toMutableList()
        val isRoom = TamaRoomCatalog.isRoomId(item.id)
        val isDecor = TamaDecorCatalog.isDecorId(item.id)
        val existingIndex = newInventory.indexOfFirst { it.id == item.id }
        val alreadyPlacedDecor = pet.leftDecorationId.equals(item.id, ignoreCase = true) ||
            pet.rightDecorationId.equals(item.id, ignoreCase = true)

        if (isRoom || isDecor || item.type == ItemType.TOOL || item.type == ItemType.RECIPE) {
            if (isDecor && alreadyPlacedDecor) {
                return ActionResult(false, context.getString(R.string.tama_toy_already_owned))
            }
            if (existingIndex != -1) {
                return ActionResult(
                    false,
                    when {
                        isRoom -> context.getString(R.string.tama_room_already_owned)
                        item.type == ItemType.RECIPE -> context.getString(R.string.tama_alchemist_recipe_already_owned)
                        else -> context.getString(R.string.tama_toy_already_owned)
                    }
                )
            }
            newInventory.add(item.copy(quantity = 1))
        } else if (existingIndex != -1) {
            val existing = newInventory[existingIndex]
            newInventory[existingIndex] = existing.copy(quantity = existing.quantity + quantity)
        } else {
            newInventory.add(item.copy(quantity = quantity))
        }

        val updatedPet = pet.copy(
            money = pet.money - totalCost,
            inventory = newInventory
        )
        _pet.value = updatedPet
        savePet(updatedPet)

        logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_bought, quantity, item.name, totalCost.toInt()))
        return ActionResult(true, context.getString(R.string.tama_action_bought_item, quantity, item.name), "buying")
    }

    private suspend fun buyFarmToolDurability(
        pet: TamaPet,
        item: InventoryItem,
        familyId: String,
        requestedQuantity: Int,
        pricePerUnit: Int
    ): ActionResult {
        val currentDurability = farmToolTotalDurability(pet.inventory, familyId)
        if (currentDurability >= FARM_TOOL_DURABILITY_CAP) {
            return ActionResult(false, context.getString(R.string.tama_farm_tool_durability_maxed))
        }
        val remainingDurability = FARM_TOOL_DURABILITY_CAP - currentDurability
        val unitsToBuy = minOf(
            requestedQuantity.coerceAtLeast(1),
            ((remainingDurability + FARM_TOOL_REPAIR_AMOUNT - 1) / FARM_TOOL_REPAIR_AMOUNT).coerceAtLeast(1)
        )
        val totalCost = pricePerUnit.toLong() * unitsToBuy
        if (pet.money < totalCost) {
            return ActionResult(false, context.getString(R.string.tama_action_not_enough_coins, totalCost, pet.money))
        }
        val addedDurability = minOf(remainingDurability, unitsToBuy * FARM_TOOL_REPAIR_AMOUNT)
        val updatedInventory = mergedFarmToolInventory(
            inventory = pet.inventory,
            familyId = familyId,
            durability = currentDurability + addedDurability
        )
        val updatedPet = pet.copy(
            money = pet.money - totalCost,
            inventory = updatedInventory
        )
        _pet.value = updatedPet
        savePet(updatedPet)

        val displayName = farmToolDisplayName(familyId)
        logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_bought, unitsToBuy, displayName, totalCost.toInt()))
        return ActionResult(
            true,
            context.getString(R.string.tama_farm_tool_durability_added, addedDurability, displayName),
            "buying"
        )
    }

    suspend fun grantItem(item: InventoryItem, quantity: Int = 1): Boolean {
        val pet = _pet.value ?: return false
        farmToolFamilyId(item.id)?.takeIf { item.type == ItemType.TOOL }?.let { familyId ->
            val currentDurability = farmToolTotalDurability(pet.inventory, familyId)
            val addedDurability = (item.durability ?: item.maxDurability ?: FARM_TOOL_REPAIR_AMOUNT) * quantity.coerceAtLeast(1)
            val updatedInventory = mergedFarmToolInventory(
                inventory = pet.inventory,
                familyId = familyId,
                durability = (currentDurability + addedDurability).coerceAtMost(FARM_TOOL_DURABILITY_CAP)
            )
            val updatedPet = pet.copy(inventory = updatedInventory)
            _pet.value = updatedPet
            savePet(updatedPet)
            return true
        }
        val newInventory = pet.inventory.toMutableList()
        val isRoom = TamaRoomCatalog.isRoomId(item.id)
        val isDecor = TamaDecorCatalog.isDecorId(item.id)
        val existingIndex = newInventory.indexOfFirst { it.id == item.id }
        val alreadyPlacedDecor = pet.leftDecorationId.equals(item.id, ignoreCase = true) ||
            pet.rightDecorationId.equals(item.id, ignoreCase = true)

        if (isRoom || isDecor || item.type == ItemType.TOOL) {
            if (isDecor && alreadyPlacedDecor) {
                return true
            }
            if (existingIndex == -1) {
                newInventory.add(item.copy(quantity = 1))
            }
        } else if (existingIndex != -1) {
            val existing = newInventory[existingIndex]
            newInventory[existingIndex] = existing.copy(quantity = existing.quantity + quantity)
        } else {
            newInventory.add(item.copy(quantity = quantity))
        }

        val updatedPet = pet.copy(inventory = newInventory)
        _pet.value = updatedPet
        savePet(updatedPet)
        return true
    }

    suspend fun collectHarvesterDroneStorage(): Boolean {
        val currentPet = _pet.value ?: return false
        val now = System.currentTimeMillis()
        val database = TamaDatabase.getInstance(context.applicationContext)
        val updatedPet = database.withTransaction {
            val latestPet = dao.getPet(currentPet.id)?.let(PetMapper::toDomain) ?: currentPet
            val upgrade = database.farmDao().getUpgrade(latestPet.id, FARM_HARVESTING_DRONE_ID)
                ?: return@withTransaction null
            if (!upgrade.isPurchased) return@withTransaction null

            val state = farmRepository.decodeHarvesterDroneState(upgrade, now)
            if (state.storage.isEmpty()) return@withTransaction null

            val updatedInventory = state.storage.fold(latestPet.inventory) { inventory, stored ->
                addInventoryItem(inventory, stored.toInventoryItem(), stored.quantity)
            }
            val normalized = normalizeGrowthTimerState(
                ensurePoopSchedule(latestPet.copy(inventory = updatedInventory), now),
                now
            )
            dao.savePet(PetMapper.toEntity(normalized))
            database.farmDao().saveUpgrade(
                upgrade.copy(
                    isPurchased = true,
                    storedOutput = state.fuel,
                    extraDataJson = Json.encodeToString(
                        state.copy(storage = emptyList(), lastUpdatedAt = now)
                    )
                )
            )
            normalized
        } ?: return false

        _pet.value = updatedPet
        _currentLocation.value = resolveLocation(updatedPet.currentLocationId)
        TamaNotificationScheduler.scheduleForPet(context.applicationContext, updatedPet.id)
        return true
    }

    suspend fun setHomeRoom(roomId: String): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val room = TamaRoomCatalog.roomById(roomId)
            ?: return ActionResult(false, context.getString(R.string.tama_room_not_found))

        val targetRoomId = room.id
        val currentRoomId = pet.homeRoomId.ifBlank { TamaRoomCatalog.PRINCIPAL_ROOM_ID }
        if (currentRoomId.equals(targetRoomId, ignoreCase = true)) {
            return ActionResult(false, context.getString(R.string.tama_room_already_in_use))
        }

        val ownsTargetRoom = targetRoomId == TamaRoomCatalog.PRINCIPAL_ROOM_ID ||
            pet.inventory.any { it.id.equals(targetRoomId, ignoreCase = true) }
        if (!ownsTargetRoom) {
            return ActionResult(false, context.getString(R.string.tama_room_not_owned))
        }

        val updatedInventory = pet.inventory.toMutableList()
        val currentRoomItem = TamaRoomCatalog.roomInventoryItem(context, currentRoomId)
        if (currentRoomItem != null && updatedInventory.none { it.id.equals(currentRoomItem.id, ignoreCase = true) }) {
            updatedInventory.add(currentRoomItem)
        }
        updatedInventory.removeAll { it.id.equals(targetRoomId, ignoreCase = true) }

        val updatedPet = pet.copy(
            homeRoomId = targetRoomId,
            inventory = updatedInventory
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_room_switched, roomByIdLabel(room)))
        return ActionResult(true, context.getString(R.string.tama_room_switched, roomByIdLabel(room)))
    }

    suspend fun placeDecoration(decorId: String, slot: TamaDecorSlot): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val decor = TamaDecorCatalog.decorById(decorId)
            ?: return ActionResult(false, context.getString(R.string.tama_toy_not_found))
        val decorItem = pet.inventory.find { it.id.equals(decor.id, ignoreCase = true) }
            ?: return ActionResult(false, context.getString(R.string.tama_toy_not_owned))

        val updatedInventory = pet.inventory.toMutableList().apply {
            removeAll { it.id.equals(decor.id, ignoreCase = true) }
        }
        val replacedDecorId = when (slot) {
            TamaDecorSlot.LEFT -> pet.leftDecorationId
            TamaDecorSlot.RIGHT -> pet.rightDecorationId
        }
        if (!replacedDecorId.isNullOrBlank()) {
            TamaDecorCatalog.decorInventoryItem(context, replacedDecorId)?.let { replacedItem ->
                if (updatedInventory.none { it.id.equals(replacedItem.id, ignoreCase = true) }) {
                    updatedInventory.add(replacedItem)
                }
            }
        }

        val updatedPet = when (slot) {
            TamaDecorSlot.LEFT -> pet.copy(
                leftDecorationId = decor.id,
                inventory = updatedInventory
            )
            TamaDecorSlot.RIGHT -> pet.copy(
                rightDecorationId = decor.id,
                inventory = updatedInventory
            )
        }
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(
            pet.id,
            EventType.OTHER,
            context.getString(
                R.string.tama_event_toy_placed,
                context.getString(decor.titleRes),
                if (slot == TamaDecorSlot.LEFT) context.getString(R.string.tama_slot_left) else context.getString(R.string.tama_slot_right)
            )
        )
        return ActionResult(
            true,
            context.getString(
                R.string.tama_toy_placed,
                context.getString(decor.titleRes),
                if (slot == TamaDecorSlot.LEFT) context.getString(R.string.tama_slot_left) else context.getString(R.string.tama_slot_right)
            )
        )
    }

    suspend fun removeDecoration(slot: TamaDecorSlot): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val currentDecorId = when (slot) {
            TamaDecorSlot.LEFT -> pet.leftDecorationId
            TamaDecorSlot.RIGHT -> pet.rightDecorationId
        } ?: return ActionResult(false, context.getString(R.string.tama_toy_slot_already_empty))

        val decor = TamaDecorCatalog.decorById(currentDecorId)
            ?: return ActionResult(false, context.getString(R.string.tama_toy_not_found))
        val inventoryItem = TamaDecorCatalog.decorInventoryItem(context, currentDecorId)
            ?: return ActionResult(false, context.getString(R.string.tama_toy_not_found))
        val updatedInventory = pet.inventory.toMutableList()
        val existingIndex = updatedInventory.indexOfFirst { it.id.equals(inventoryItem.id, ignoreCase = true) }
        if (existingIndex >= 0) {
            val existing = updatedInventory[existingIndex]
            updatedInventory[existingIndex] = existing.copy(quantity = existing.quantity + 1)
        } else {
            updatedInventory.add(inventoryItem)
        }
        val updatedPet = when (slot) {
            TamaDecorSlot.LEFT -> pet.copy(leftDecorationId = null, inventory = updatedInventory)
            TamaDecorSlot.RIGHT -> pet.copy(rightDecorationId = null, inventory = updatedInventory)
        }
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(
            pet.id,
            EventType.OTHER,
            context.getString(
                R.string.tama_event_toy_removed,
                context.getString(decor.titleRes),
                if (slot == TamaDecorSlot.LEFT) context.getString(R.string.tama_slot_left) else context.getString(R.string.tama_slot_right)
            )
        )
        return ActionResult(
            true,
            context.getString(
                R.string.tama_toy_removed,
                context.getString(decor.titleRes),
                if (slot == TamaDecorSlot.LEFT) context.getString(R.string.tama_slot_left) else context.getString(R.string.tama_slot_right)
            )
        )
    }

    suspend fun clearPendingDreamAlbum(albumId: String? = null) {
        val pet = _pet.value ?: return
        if (albumId != null && pet.pendingDreamAlbumId != albumId) return
        val updatedPet = pet.copy(pendingDreamAlbumId = null)
        _pet.value = updatedPet
        savePet(updatedPet)
    }

    /**
     * Consume an item from inventory without logging it as a sale.
     * Used for planting, crafting, etc.
     */
    suspend fun consumeItem(item: InventoryItem, quantity: Int = 1): Boolean {
        val pet = _pet.value ?: return false
        val existing = pet.inventory.find { it.id == item.id } ?: return false

        if (existing.quantity < quantity) return false

        val newInventory = if (existing.quantity == quantity) {
            pet.inventory.filter { it.id != item.id }
        } else {
            pet.inventory.map {
                if (it.id == item.id) it.copy(quantity = it.quantity - quantity)
                else it
            }
        }

        val updatedPet = pet.copy(inventory = newInventory)
        _pet.value = updatedPet
        savePet(updatedPet)
        return true
    }

    suspend fun consumeFarmToolDurability(familyId: String, amount: Int): Int {
        val pet = _pet.value ?: return 0
        val safeFamilyId = farmToolFamilyId(familyId) ?: familyId
        if (safeFamilyId != "hoe" && safeFamilyId != "watering_can") return 0
        val currentDurability = farmToolTotalDurability(pet.inventory, safeFamilyId)
        val consumed = minOf(amount.coerceAtLeast(0), currentDurability)
        if (consumed <= 0) return 0
        val remaining = currentDurability - consumed
        val updatedInventory = if (remaining > 0) {
            mergedFarmToolInventory(pet.inventory, safeFamilyId, remaining)
        } else {
            pet.inventory.filterNot { farmToolFamilyId(it.id) == safeFamilyId }
        }
        val updatedPet = pet.copy(inventory = updatedInventory)
        _pet.value = updatedPet
        savePet(updatedPet)
        return consumed
    }

    private fun mergedFarmToolInventory(
        inventory: List<InventoryItem>,
        familyId: String,
        durability: Int
    ): List<InventoryItem> {
        val clampedDurability = durability.coerceIn(0, FARM_TOOL_DURABILITY_CAP)
        val withoutFamily = inventory.filterNot { farmToolFamilyId(it.id) == familyId }
        if (clampedDurability <= 0) return withoutFamily
        return withoutFamily + InventoryItem(
            id = familyId,
            name = farmToolDisplayName(familyId),
            type = ItemType.TOOL,
            quantity = 1,
            durability = clampedDurability,
            maxDurability = FARM_TOOL_DURABILITY_CAP
        )
    }

    private fun farmToolDisplayName(familyId: String): String = when (familyId) {
        "watering_can" -> context.getString(R.string.tama_inventory_watering_can)
        else -> context.getString(R.string.tama_inventory_hoe)
    }

    private fun roomByIdLabel(room: TamaRoomDefinition): String {
        return TamaRoomCatalog.localizedTitle(context, room)
    }

    /**
     * Spend money.
     */
    suspend fun spendMoney(amount: Long): Boolean {
        val pet = _pet.value ?: return false
        if (pet.money < amount) return false

        val updatedPet = pet.copy(money = pet.money - amount)
        _pet.value = updatedPet
        savePet(updatedPet)
        return true
    }

    suspend fun awardMoney(amount: Long, details: String? = null): Boolean {
        val pet = _pet.value ?: return false
        val safeAmount = amount.coerceAtLeast(0)
        if (safeAmount == 0L) {
            if (!details.isNullOrBlank()) {
                logEvent(pet.id, EventType.OTHER, details)
            }
            return true
        }

        val updatedPet = pet.copy(money = pet.money + safeAmount)
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(
            pet.id,
            EventType.GOT_PAID,
            details ?: context.getString(R.string.tama_event_arcade_reward, safeAmount.toInt())
        )
        return true
    }

    suspend fun awardHappiness(amount: Float, details: String? = null): Boolean {
        val pet = _pet.value ?: return false
        val safeAmount = amount.coerceAtLeast(0f)
        if (safeAmount == 0f) {
            if (!details.isNullOrBlank()) {
                logEvent(pet.id, EventType.OTHER, details)
            }
            return true
        }

        val updatedStats = pet.stats.copy(happiness = (pet.stats.happiness + safeAmount).coerceAtMost(100f))
        val updatedPet = pet.copy(stats = updatedStats).let { it.copy(mood = effectiveMood(it)) }
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(
            pet.id,
            EventType.PLAYED,
            details ?: context.getString(R.string.tama_event_happiness_reward, pet.name, safeAmount.roundToInt()),
            statsChange = mapOf("happiness" to safeAmount)
        )
        return true
    }

    suspend fun sellItem(item: InventoryItem, quantity: Int = 1, price: Long): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, context.getString(R.string.tama_error_no_pet))
        val totalGain = price * quantity
        val displayName = inventoryItemDisplayName(context, item)

        val newInventory = pet.inventory.toMutableList()
        val existingIndex = newInventory.indexOfFirst { it.id == item.id }
        if (existingIndex == -1 || newInventory[existingIndex].quantity < quantity) {
            return ActionResult(false, context.getString(R.string.tama_action_not_enough_item, displayName))
        }

        val existing = newInventory[existingIndex]
        if (existing.quantity == quantity) {
            newInventory.removeAt(existingIndex)
        } else {
            newInventory[existingIndex] = existing.copy(quantity = existing.quantity - quantity)
        }

        val updatedPet = pet.copy(
            money = pet.money + totalGain,
            inventory = newInventory
        )
        _pet.value = updatedPet
        savePet(updatedPet)

        logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_sold, quantity, displayName, totalGain.toInt()))
        return ActionResult(true, context.getString(R.string.tama_action_sold_item, quantity, displayName), "selling")
    }

    // ==================== Event Logging ====================

    suspend fun logEvent(
        petId: String,
        eventType: EventType,
        details: String,
        locationId: String? = null,
        npcId: String? = null,
        statsChange: Map<String, Float>? = null
    ) {
        val now = System.currentTimeMillis()
        val baseDetails = normalizeEventDetails(details)
        val matchingIndex = _events.value.indexOfFirst { existingEvent ->
            existingEvent.eventType == eventType &&
                (now - existingEvent.timestamp) < EVENT_COMPRESSION_WINDOW_MS &&
                normalizeEventDetails(existingEvent.details) == baseDetails
        }

        if (matchingIndex >= 0) {
            val existingEvent = _events.value[matchingIndex]
            val updatedCount = extractEventRepeatCount(existingEvent.details) + 1
            val updatedEvent = existingEvent.copy(
                details = context.getString(R.string.tama_event_repeat_total, baseDetails, updatedCount),
                timestamp = now,
                statsChange = null
            )
            val reorderedEvents = buildList {
                add(updatedEvent)
                _events.value.forEachIndexed { index, event ->
                    if (index != matchingIndex) add(event)
                }
            }.take(100)
            _events.value = reorderedEvents
            dao.saveEvent(eventToEntity(updatedEvent))
            return
        }

        val event = TamaEvent(
            petId = petId,
            eventType = eventType,
            details = details,
            locationId = locationId ?: _pet.value?.currentLocationId,
            npcId = npcId,
            statsChange = statsChange,
            timestamp = now
        )
        _events.value = listOf(event) + _events.value.take(99)  // Keep last 100
        dao.saveEvent(eventToEntity(event))
    }

    fun observeArtworks(petId: String): Flow<List<TamaArtworkEntity>> = dao.observeArtworks(petId)

    fun observeLatestArtwork(petId: String): Flow<TamaArtworkEntity?> = dao.observeLatestArtwork(petId)

    fun observeArtwork(artworkId: String): Flow<TamaArtworkEntity?> = dao.observeArtwork(artworkId)

    suspend fun getLatestArtwork(petId: String): TamaArtworkEntity? = dao.getLatestArtwork(petId)

    suspend fun getLatestSleepDreamArtwork(petId: String, sinceMillis: Long): TamaArtworkEntity? {
        return dao.getArtworks(petId)
            .asSequence()
            .filter { it.kind == TamaArtworkKind.DREAM.name }
            .filter { it.sourceActivity == "sleeping" }
            .filter { it.createdAt >= sinceMillis }
            .maxByOrNull { it.createdAt }
    }

    suspend fun deleteArtwork(artwork: TamaArtworkEntity) {
        artwork.filePath?.let(::File)?.takeIf { it.exists() }?.delete()
        dao.deleteArtwork(artwork.id)
    }

    private suspend fun buildBackupPackage(): TamaBackupPackage {
        val database = TamaDatabase.getInstance(context)
        val pet = _pet.value ?: dao.getActivePet()?.let(PetMapper::toDomain)
            ?: throw IllegalStateException("No active pet to export")
        val now = System.currentTimeMillis()
        val events = dao.getAllEvents(pet.id)
        val chatMessages = dao.getChatHistory(pet.id)
        val summaries = dao.getAllSummaries(pet.id)
        val deepDreamRuns = dao.getDeepDreamRunsForPet(pet.id)
        val farmTiles = database.farmDao().getTiles(pet.id)
        val farmUpgrades = database.farmDao().getUpgrades(pet.id)
        val farmLivestock = database.farmDao().getLivestock(pet.id)
        val quests = dao.getQuestsForPet(pet.id)
        val questChecklist = dao.getQuestChecklist(pet.id)
        val studyLabels = dao.getStudyLabels(pet.id)
        val studySessions = dao.getStudySessionsForPet(pet.id)
        val marketQuotes = dao.getMarketQuotesForPet(pet.id)
        val locations = dao.getAllLocations()
        val npcs = dao.getAllNpcs()
        val adventureSessions = dao.getAdventureHistory(pet.id)
        val adventureStages = adventureSessions.flatMap { dao.getAdventureStages(it.id) }
        val dungeonProgress = dao.getDungeonProgress(pet.id)
        val adventureGateProfile = dao.getAdventureGateProfile(pet.id)
        val adventureGateWorldProgress = dao.getAdventureGateWorldProgress(pet.id)
        val adventureGateBattleState = dao.getAdventureGateBattleState(pet.id)
        val adventureGateNightArenaRun = dao.getAdventureGateNightArenaRun(pet.id)
        val artworks = dao.getArtworks(pet.id)
        val exportedSummaries = buildExportSummaries(pet.id, summaries, artworks)
        val audioPaths = chatMessages.associate { message ->
            val audioFile = message.audioPath?.let(::File)?.takeIf { it.exists() }
            message.id to audioFile?.let { backupChatAudioEntryName(message.petId, message.id) }
        }
        val imagePaths = chatMessages.associate { message ->
            val imageFile = message.imagePath?.let(::File)?.takeIf { it.exists() }
            message.id to imageFile?.let { backupChatImageEntryName(message.petId, message.id) }
        }

        val bundle = TamaTransferBundle(
            pet = pet,
            exportDate = now,
            petAgeMillis = (now - pet.birthTimestamp).coerceAtLeast(0L),
            artworks = artworks.map { artwork ->
                TamaTransferArtwork.fromEntity(artwork, backupArtworkEntryName(artwork.petId, artwork.id))
            },
            events = events.map(TamaTransferEvent::fromEntity),
            chatMessages = chatMessages.map { message ->
                TamaTransferChatMessage.fromEntity(message, audioPaths[message.id], imagePaths[message.id])
            },
            summaries = exportedSummaries,
            settings = buildTransferSettings(),
            farmTiles = farmTiles.map(TamaTransferFarmTile::fromEntity),
            farmUpgrades = farmUpgrades.map(TamaTransferFarmUpgrade::fromEntity),
            farmLivestock = farmLivestock.map(TamaTransferFarmLivestock::fromEntity),
            quests = quests.map(TamaTransferQuest::fromEntity),
            questChecklist = questChecklist.map(TamaTransferQuestChecklistItem::fromEntity),
            deepDreamRuns = deepDreamRuns.map(TamaTransferDeepDreamRun::fromEntity),
            studyLabels = studyLabels.map(TamaTransferStudyLabel::fromEntity),
            studySessions = studySessions.map(TamaTransferStudySession::fromEntity),
            marketQuotes = marketQuotes.map(TamaTransferMarketQuote::fromEntity),
            locations = locations.map(TamaTransferLocation::fromEntity),
            npcs = npcs.map(TamaTransferNpc::fromEntity),
            adventureSessions = adventureSessions.map { session ->
                val worldImagePath = decodeSchematicWorldImagePath(session.schematicJson)
                val relativeWorldImagePath = worldImagePath
                    ?.let(::File)
                    ?.takeIf { it.exists() }
                    ?.let { backupAdventureWorldEntryName(session.id) }
                TamaTransferAdventureSession.fromEntity(
                    entity = session,
                    relativeWorldImagePath = relativeWorldImagePath
                )
            },
            adventureStages = adventureStages.map { stage ->
                val relativeImagePath = stage.imagePath
                    ?.let(::File)
                    ?.takeIf { it.exists() }
                    ?.let { backupAdventureStageEntryName(stage.sessionId, stage.stageNumber) }
                TamaTransferAdventureStage.fromEntity(
                    entity = stage,
                    relativeImagePath = relativeImagePath
                )
            },
            dungeonProgress = dungeonProgress?.let(TamaTransferDungeonProgress::fromEntity),
            adventureGateProfile = adventureGateProfile?.let(TamaTransferAdventureGateProfile::fromEntity),
            adventureGateWorldProgress = adventureGateWorldProgress.map(TamaTransferAdventureGateWorldProgress::fromEntity),
            adventureGateBattleState = adventureGateBattleState?.let(TamaTransferAdventureGateBattleState::fromEntity),
            adventureGateNightArenaRun = adventureGateNightArenaRun?.let(TamaTransferAdventureGateNightArenaRun::fromEntity)
        )
        return TamaBackupPackage(bundle = bundle, artworks = artworks)
    }

    private suspend fun importFromBackupZip(inputStream: InputStream): Boolean {
        return try {
            val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
            var bundle: TamaTransferBundle? = null
            val artworkFiles = mutableMapOf<String, File>()
            val chatAudioFiles = mutableMapOf<String, File>()
            val chatImageFiles = mutableMapOf<String, File>()
            val adventureWorldFiles = mutableMapOf<String, File>()
            val adventureStageFiles = mutableMapOf<String, File>()
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        when (entry.name) {
                            BACKUP_MANIFEST_ENTRY -> {
                                val manifestBuffer = java.io.ByteArrayOutputStream()
                                zipIn.copyTo(manifestBuffer)
                                val manifestJson = manifestBuffer.toString(Charsets.UTF_8.name())
                                bundle = parseTamaTransferBundle(manifestJson, json)
                                val importedPet = bundle?.pet?.normalizedSpeciesPet() ?: return false
                                val petIdsToReplace = replacementPetIds(dao.getAllPetIds(), importedPet.id)
                                petIdsToReplace.forEach { petId ->
                                    clearPetScopedTransferData(petId)
                                }
                                if ((bundle?.version ?: 0) >= 20) {
                                    clearGlobalTransferData()
                                }
                            }
                            else -> {
                                val currentBundle = bundle
                                val artwork = currentBundle?.artworks?.firstOrNull { it.relativeFilePath == entry.name }
                                if (artwork != null) {
                                    val actualTarget = TamaArtworkManager.artworkFile(context, currentBundle.pet.id, artwork.id)
                                    actualTarget.parentFile?.mkdirs()
                                    actualTarget.outputStream().use { output ->
                                        zipIn.copyTo(output)
                                    }
                                    artworkFiles[artwork.id] = actualTarget
                                } else {
                                    val chatMessage = currentBundle?.chatMessages?.firstOrNull { it.relativeAudioPath == entry.name }
                                    if (chatMessage != null) {
                                        val actualTarget = chatAudioFile(currentBundle.pet.id, chatMessage.id)
                                        actualTarget.parentFile?.mkdirs()
                                        actualTarget.outputStream().use { output ->
                                            zipIn.copyTo(output)
                                        }
                                        chatAudioFiles[chatMessage.id] = actualTarget
                                    } else {
                                        val chatImage = currentBundle?.chatMessages?.firstOrNull { it.relativeImagePath == entry.name }
                                        if (chatImage != null) {
                                            val actualTarget = chatImageFile(currentBundle.pet.id, chatImage.id)
                                            actualTarget.parentFile?.mkdirs()
                                            actualTarget.outputStream().use { output ->
                                                zipIn.copyTo(output)
                                            }
                                            chatImageFiles[chatImage.id] = actualTarget
                                        } else {
                                            val adventureWorld = currentBundle?.adventureSessions
                                                ?.firstOrNull { it.relativeWorldImagePath == entry.name }
                                            if (adventureWorld != null) {
                                                val actualTarget = adventureWorldImageFile(adventureWorld.id)
                                                actualTarget.parentFile?.mkdirs()
                                                actualTarget.outputStream().use { output ->
                                                    zipIn.copyTo(output)
                                                }
                                                adventureWorldFiles[adventureWorld.id] = actualTarget
                                            } else {
                                                val adventureStage = currentBundle?.adventureStages
                                                    ?.firstOrNull { it.relativeImagePath == entry.name }
                                                if (adventureStage != null) {
                                                    val actualTarget = adventureStageImageFile(
                                                        adventureStage.sessionId,
                                                        adventureStage.stageNumber
                                                    )
                                                    actualTarget.parentFile?.mkdirs()
                                                    actualTarget.outputStream().use { output ->
                                                        zipIn.copyTo(output)
                                                    }
                                                    adventureStageFiles[adventureStage.id] = actualTarget
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            val importedBundle = bundle ?: return false
            restoreTransferBundle(
                bundle = importedBundle,
                artworkFiles = artworkFiles,
                chatAudioFiles = chatAudioFiles,
                chatImageFiles = chatImageFiles,
                adventureWorldFiles = adventureWorldFiles,
                adventureStageFiles = adventureStageFiles,
                freezePetAge = true
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun restoreTransferBundle(
        bundle: TamaTransferBundle,
        artworkFiles: Map<String, File>,
        chatAudioFiles: Map<String, File>,
        chatImageFiles: Map<String, File>,
        adventureWorldFiles: Map<String, File>,
        adventureStageFiles: Map<String, File>,
        freezePetAge: Boolean
    ) {
        val now = System.currentTimeMillis()
        val importedPet = if (freezePetAge) {
            bundle.pet.normalizedSpeciesPet().copy(
                birthTimestamp = now - bundle.petAgeMillis.coerceAtLeast(0L),
                lastDecayTime = now
            )
        } else {
            bundle.pet.normalizedSpeciesPet().copy(
                lastDecayTime = now
            )
        }
        val database = TamaDatabase.getInstance(context)
        val restoredArtworkEntities = bundle.artworks.map { artwork ->
            val targetFile = artworkFiles[artwork.id] ?: TamaArtworkManager.artworkFile(context, importedPet.id, artwork.id)
            artwork.toEntity(targetFile.absolutePath)
        }

        database.withTransaction {
            dao.savePet(PetMapper.toEntity(importedPet))
            if (bundle.events.isNotEmpty()) {
                dao.saveEvents(bundle.events.map { it.toEntity() })
            }
            if (bundle.chatMessages.isNotEmpty()) {
                dao.saveChatMessages(bundle.chatMessages.map { message ->
                    val audioPath = chatAudioFiles[message.id]?.absolutePath ?: message.audioPath
                    val imagePath = chatImageFiles[message.id]?.absolutePath ?: message.imagePath
                    message.toEntity(audioPath, imagePath)
                })
            }
            if (bundle.summaries.isNotEmpty()) {
                dao.saveSummaries(bundle.summaries.map { it.toEntity() })
            }
            if (bundle.deepDreamRuns.isNotEmpty()) {
                dao.saveDeepDreamRuns(bundle.deepDreamRuns.map { it.toEntity() })
            }
            if (bundle.studyLabels.isNotEmpty()) {
                dao.saveStudyLabels(bundle.studyLabels.map { it.toEntity() })
            }
            if (bundle.studySessions.isNotEmpty()) {
                dao.saveStudySessions(bundle.studySessions.map { it.toEntity() })
            }
            if (bundle.marketQuotes.isNotEmpty()) {
                dao.saveMarketQuotes(bundle.marketQuotes.map { it.toEntity() })
            }
            if (bundle.locations.isNotEmpty()) {
                dao.saveLocations(bundle.locations.map { it.toEntity() })
            }
            if (bundle.npcs.isNotEmpty()) {
                dao.saveNpcs(bundle.npcs.map { it.toEntity() })
            }
            if (bundle.farmTiles.isNotEmpty()) {
                database.farmDao().saveTiles(bundle.farmTiles.map { it.toEntity() })
            }
            if (bundle.farmUpgrades.isNotEmpty()) {
                database.farmDao().saveUpgrades(bundle.farmUpgrades.map { it.toEntity() })
            }
            if (bundle.farmLivestock.isNotEmpty()) {
                database.farmDao().saveLivestock(bundle.farmLivestock.map { it.toEntity() })
            }
            if (bundle.quests.isNotEmpty()) {
                dao.saveQuests(bundle.quests.map { it.toEntity() })
            }
            if (bundle.questChecklist.isNotEmpty()) {
                dao.saveQuestChecklistItems(bundle.questChecklist.map { it.toEntity() })
            }
            if (bundle.adventureSessions.isNotEmpty()) {
                dao.saveAdventureSessions(bundle.adventureSessions.map { session ->
                    val restoredWorldImagePath = adventureWorldFiles[session.id]?.absolutePath
                        ?: session.relativeWorldImagePath
                            ?.takeIf { it.isNotBlank() }
                            ?.let { adventureWorldImageFile(session.id).absolutePath }
                        ?: decodeSchematicWorldImagePath(session.schematicJson)
                    session.toEntity(restoredWorldImagePath)
                })
            }
            if (bundle.adventureStages.isNotEmpty()) {
                dao.saveAdventureStages(bundle.adventureStages.map { stage ->
                    val restoredImagePath = adventureStageFiles[stage.id]?.absolutePath
                        ?: stage.relativeImagePath
                            ?.takeIf { it.isNotBlank() }
                            ?.let { adventureStageImageFile(stage.sessionId, stage.stageNumber).absolutePath }
                        ?: stage.imagePath
                    stage.toEntity(restoredImagePath)
                })
            }
            bundle.dungeonProgress?.let { dao.saveDungeonProgress(it.toEntity()) }
            bundle.adventureGateProfile?.let { dao.saveAdventureGateProfile(it.toEntity()) }
            if (bundle.adventureGateWorldProgress.isNotEmpty()) {
                dao.saveAdventureGateWorldProgress(bundle.adventureGateWorldProgress.map { it.toEntity() })
            }
            bundle.adventureGateBattleState?.let { dao.saveAdventureGateBattleState(it.toEntity()) }
            bundle.adventureGateNightArenaRun?.let { dao.saveAdventureGateNightArenaRun(it.toEntity()) }
            if (restoredArtworkEntities.isNotEmpty()) {
                dao.saveArtworks(restoredArtworkEntities)
            }
        }
        bundle.settings?.let(::applyTransferSettings)

        _pet.value = null
        _events.value = emptyList()
        loadPet()
    }

    private fun backupArtworkEntryName(petId: String, artworkId: String): String {
        return "$BACKUP_ARTWORK_PREFIX$petId/$artworkId.png"
    }

    private fun backupChatAudioEntryName(petId: String, messageId: String): String {
        return "$BACKUP_CHAT_AUDIO_PREFIX$petId/$messageId.m4a"
    }

    private fun backupChatImageEntryName(petId: String, messageId: String): String {
        return "$BACKUP_CHAT_IMAGE_PREFIX$petId/$messageId.png"
    }

    private fun backupAdventureWorldEntryName(sessionId: String): String {
        return "$BACKUP_ADVENTURE_WORLD_PREFIX$sessionId.png"
    }

    private fun backupAdventureStageEntryName(sessionId: String, stageNumber: Int): String {
        return "$BACKUP_ADVENTURE_STAGE_PREFIX$sessionId/stage_$stageNumber.png"
    }

    private fun chatAudioFile(petId: String, messageId: String): File {
        val petDir = File(context.filesDir, "$CHAT_AUDIO_DIR/$petId")
        petDir.mkdirs()
        return File(petDir, "$messageId.m4a")
    }

    private fun chatImageFile(petId: String, messageId: String): File {
        val petDir = File(context.filesDir, "$CHAT_IMAGE_DIR/$petId")
        petDir.mkdirs()
        return File(petDir, "$messageId.png")
    }

    private fun adventureWorldImageFile(sessionId: String): File {
        val baseDir = File(context.filesDir, ADVENTURE_WORLD_DIR).apply { mkdirs() }
        return File(baseDir, "$sessionId.png")
    }

    private fun adventureStageImageFile(sessionId: String, stageNumber: Int): File {
        val sessionDir = File(File(context.filesDir, ADVENTURE_WORLD_DIR), sessionId).apply { mkdirs() }
        return File(sessionDir, "stage_$stageNumber.png")
    }

    private fun randomPoopDelayMs(): Long {
        return Random.nextLong(POOP_MIN_INTERVAL_MS, POOP_MAX_INTERVAL_MS + 1)
    }

    private fun isPoopGenerationPaused(pet: TamaPet): Boolean {
        return pet.isPoopGenerationPaused()
    }

    private fun ensurePoopSchedule(pet: TamaPet, now: Long): TamaPet {
        if (pet.stage == GrowthStage.EGG || isPoopGenerationPaused(pet)) return pet
        if (pet.poopCount > 0 && pet.poopCount < 4 && pet.nextPoopAt == null) {
            return pet.copy(nextPoopAt = now + randomPoopDelayMs())
        }
        if (pet.poopCount >= 4) return pet.copy(nextPoopAt = null)
        if (pet.poopCount > 0) return pet
        return if (pet.nextPoopAt == null) {
            pet.copy(nextPoopAt = now + randomPoopDelayMs())
        } else {
            pet
        }
    }

    private fun shiftPoopTimersForSleep(pet: TamaPet, sleepDurationMs: Long): TamaPet {
        if (sleepDurationMs <= 0L) return pet
        return pet.copy(
            nextPoopAt = pet.nextPoopAt?.plus(sleepDurationMs),
            poopCreatedAt = pet.poopCreatedAt?.plus(sleepDurationMs),
            lastPoopMiscareAt = pet.lastPoopMiscareAt?.plus(sleepDurationMs)
        )
    }

    private fun shiftPoopTimersForPausedActivity(pet: TamaPet, pausedDurationMs: Long): TamaPet {
        if (pausedDurationMs <= 0L) return pet
        return pet.copy(
            nextPoopAt = pet.nextPoopAt?.plus(pausedDurationMs),
            poopCreatedAt = pet.poopCreatedAt?.plus(pausedDurationMs),
            lastPoopMiscareAt = pet.lastPoopMiscareAt?.plus(pausedDurationMs)
        )
    }

    private suspend fun recoverAdventureGateProfile(petId: String) {
        runCatching {
            AdventureGateRepository(TamaDatabase.getInstance(context)).recoverProfile(petId)
        }.onFailure { error ->
            DebugLog.log("[TamaGameEngine] Adventure Gate recovery skipped: ${error.message}")
        }
    }

    private suspend fun advancePoopState(pet: TamaPet, now: Long): TamaPet {
        if (pet.stage == GrowthStage.EGG) return pet
        if (isPoopGenerationPaused(pet)) return pet
        val scheduledPoopAt = pet.nextPoopAt
        if (pet.poopCount == 0) {
            if (scheduledPoopAt == null) return pet.copy(nextPoopAt = now + randomPoopDelayMs())
            if (scheduledPoopAt > now) return pet
            val createdPet = pet.copy(
                poopCreatedAt = now,
                poopCount = 1,
                nextPoopAt = null,
                lastPoopMiscareAt = null
            )
            logEvent(
                createdPet.id,
                EventType.POOPED,
                context.getString(R.string.tama_event_pooped, createdPet.name)
            )
            UnifiedNotificationManager.showTamaPoopNotification(createdPet)
            return createdPet
        }

        val poopCreatedAt = pet.poopCreatedAt ?: return pet
        if (pet.poopCount in 1 until 4 && scheduledPoopAt != null && scheduledPoopAt <= now && !isPoopGenerationPaused(pet)) {
            val updatedCount = (pet.poopCount + 1).coerceAtMost(4)
            val updatedPet = pet.copy(
                poopCount = updatedCount,
                nextPoopAt = if (updatedCount >= 4) null else now + randomPoopDelayMs()
            )
            logEvent(
                updatedPet.id,
                EventType.POOPED,
                context.getString(R.string.tama_event_pooped, updatedPet.name)
            )
            UnifiedNotificationManager.showTamaPoopNotification(updatedPet)
            return updatedPet
        }

        val neglectAt = poopCreatedAt + POOP_MISCARE_WINDOW_MS
        if (pet.lastPoopMiscareAt != poopCreatedAt && now >= neglectAt) {
            val updatedPet = applyMiscarePenalty(pet).copy(lastPoopMiscareAt = poopCreatedAt)
            logEvent(updatedPet.id, EventType.POOP_NEGLECTED, context.getString(R.string.tama_event_poop_neglected, updatedPet.name))
            UnifiedNotificationManager.showTamaPoopNeglectNotification(updatedPet)
            return updatedPet
        }
        return if (pet.poopCount in 1 until 4 && scheduledPoopAt == null && !isPoopGenerationPaused(pet)) {
            pet.copy(nextPoopAt = now + randomPoopDelayMs())
        } else {
            pet
        }
    }

    private suspend fun maybeQueueDailyDream(pet: TamaPet, now: Long): TamaPet {
        val latestStoredPet = dao.getPet(pet.id)?.let(PetMapper::toDomain)
        return latestStoredPet?.let { stored ->
            pet.copy(
                lastDailyDreamDate = stored.lastDailyDreamDate,
                pendingDreamAlbumId = stored.pendingDreamAlbumId
            )
        } ?: pet
    }

    private suspend fun maybeQueueNormalDream(pet: TamaPet) {
        if (!settingsRepo.tamaNormalDreamingEnabled.value) return
        runCatching {
            TamaArtworkManager.queueDream(
                context = context,
                pet = pet,
                settingsRepository = settingsRepo
            ).getOrThrow()
        }
    }

    private fun sharedTravelEnergyCost(distance: Int): Int {
        return ceil((distance.coerceAtLeast(1) * 3f) / 2f).toInt().coerceAtLeast(2)
    }

    private fun normalizeEventDetails(details: String): String {
        return details
            .replace(Regex(""" x\d+$"""), "")
            .replace(Regex(""" \(\d+ total\)$"""), "")
            .replace(Regex(""" \(\d+ en total\)$"""), "")
            .trim()
    }

    private fun extractEventRepeatCount(details: String): Int {
        val patterns = listOf(
            Regex(""" x(\d+)$"""),
            Regex(""" \((\d+) total\)$"""),
            Regex(""" \((\d+) en total\)$""")
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(details)?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: 1
    }

    /**
     * Export all Tama transfer data to a JSON manifest.
     * This stays as a helper for legacy compatibility and ZIP manifests.
     */
    suspend fun exportToJson(): String {
        return withContext(Dispatchers.IO) {
            val packageData = runCatching { buildBackupPackage() }.getOrNull() ?: return@withContext "{}"
            Json { prettyPrint = true; encodeDefaults = true }.encodeToString(packageData.bundle)
        }
    }

    suspend fun exportToBackupZip(outputStream: OutputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val packageData = buildBackupPackage()
                val json = Json { prettyPrint = true; encodeDefaults = true }.encodeToString(packageData.bundle)
                BufferedOutputStream(outputStream).use { bufferedOut ->
                    ZipOutputStream(bufferedOut).use { zipOut ->
                        zipOut.putNextEntry(ZipEntry(BACKUP_MANIFEST_ENTRY))
                        zipOut.write(json.toByteArray(Charsets.UTF_8))
                        zipOut.closeEntry()

                        packageData.artworks.forEach { artwork ->
                            val file = artwork.filePath?.let(::File)
                            if (file?.exists() != true) return@forEach
                            val entryName = backupArtworkEntryName(artwork.petId, artwork.id)
                            zipOut.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }

                        packageData.bundle.chatMessages.forEach { message ->
                            val audioFile = message.audioPath?.let(::File)
                            if (audioFile?.exists() != true) return@forEach
                            val entryName = message.relativeAudioPath ?: backupChatAudioEntryName(message.petId, message.id)
                            zipOut.putNextEntry(ZipEntry(entryName))
                            audioFile.inputStream().use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }

                        packageData.bundle.chatMessages.forEach { message ->
                            val imageFile = message.imagePath?.let(::File)
                            if (imageFile?.exists() != true) return@forEach
                            val entryName = message.relativeImagePath ?: backupChatImageEntryName(message.petId, message.id)
                            zipOut.putNextEntry(ZipEntry(entryName))
                            imageFile.inputStream().use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }

                        packageData.bundle.adventureSessions.forEach { session ->
                            val worldImagePath = decodeSchematicWorldImagePath(session.schematicJson)
                            val worldImageFile = worldImagePath?.let(::File)
                            if (worldImageFile?.exists() != true) return@forEach
                            val entryName = session.relativeWorldImagePath ?: backupAdventureWorldEntryName(session.id)
                            zipOut.putNextEntry(ZipEntry(entryName))
                            worldImageFile.inputStream().use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }

                        packageData.bundle.adventureStages.forEach { stage ->
                            val stageImageFile = stage.imagePath?.let(::File)
                            if (stageImageFile?.exists() != true) return@forEach
                            val entryName = stage.relativeImagePath ?: backupAdventureStageEntryName(
                                stage.sessionId,
                                stage.stageNumber
                            )
                            zipOut.putNextEntry(ZipEntry(entryName))
                            stageImageFile.inputStream().use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Import pet data from either a legacy JSON export or the newer ZIP backup.
     */
    suspend fun importFromBackup(inputStream: InputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val buffered = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream)
                buffered.mark(8)
                val signature = ByteArray(4)
                val read = buffered.read(signature)
                buffered.reset()
                val isZip = read >= 2 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()
                if (isZip) {
                    importFromBackupZip(buffered)
                } else {
                    val jsonString = buffered.bufferedReader().use { it.readText() }
                    importFromJson(jsonString)
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Import pet data from the legacy JSON format.
     */
    suspend fun importFromJson(jsonString: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
                val bundle = parseTamaTransferBundle(jsonString, json)
                restoreTransferBundle(
                    bundle = bundle,
                    artworkFiles = emptyMap(),
                    chatAudioFiles = emptyMap(),
                    chatImageFiles = emptyMap(),
                    adventureWorldFiles = emptyMap(),
                    adventureStageFiles = emptyMap(),
                    freezePetAge = false
                )
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    // ==================== Helpers ====================

    private suspend fun savePet(pet: TamaPet) {
        val refreshTamaWidget = TamaPetWidgetProvider.hasWidgets(context.applicationContext)
        val previousWidgetSignature = if (refreshTamaWidget) {
            dao.getPet(pet.id)?.let(PetMapper::toDomain)?.tamaPetWidgetSignature()
        } else {
            null
        }
        val normalized = normalizeGrowthTimerState(
            ensurePoopSchedule(pet, System.currentTimeMillis()),
            System.currentTimeMillis()
        )
        _pet.value = normalized
        _currentLocation.value = resolveLocation(normalized.currentLocationId)
        dao.savePet(PetMapper.toEntity(normalized))
        if (refreshTamaWidget && previousWidgetSignature != normalized.tamaPetWidgetSignature()) {
            TamaPetWidgetProvider.refreshAll(context.applicationContext)
        }
        if (normalized.poopCount <= 0) {
            UnifiedNotificationManager.dismissTamaPoopNotifications(normalized.id)
        }
        TamaNotificationScheduler.scheduleForPet(context.applicationContext, normalized.id)
    }

    private fun TamaPet.tamaPetWidgetSignature(): String = listOf(
        id,
        name,
        species,
        stage.name,
        mood.name,
        isMad,
        currentLocationId,
        homeRoomId,
        leftDecorationId.orEmpty(),
        rightDecorationId.orEmpty(),
        currentActivity.name,
        currentWorkJobId.orEmpty(),
        activityStartTime ?: 0L,
        isSleeping,
        sleepStartTime ?: 0L,
        poopCount
    ).joinToString("|")

    private fun pausedGrowthDuration(pet: TamaPet, now: Long): Long {
        val lockedAt = pet.growthLockStartedAt ?: return 0L
        return (now - lockedAt).coerceAtLeast(0L)
    }

    private fun normalizeGrowthTimerState(pet: TamaPet, now: Long): TamaPet {
        return when {
            pet.growthLocked && pet.growthLockStartedAt == null -> pet.copy(growthLockStartedAt = now)
            !pet.growthLocked && pet.growthLockStartedAt != null -> pet.copy(growthLockStartedAt = null)
            else -> pet
        }
    }

    private suspend fun expireAcceptedParkQuests(petId: String, now: Long) {
        val expired = dao.getQuestsByStatus(petId, TamaQuestStatus.ACCEPTED.name)
            .map(::questEntityToDomain)
            .filter { (it.expiresAt ?: Long.MAX_VALUE) <= now }
            .map { it.copy(status = TamaQuestStatus.EXPIRED) }
        if (expired.isNotEmpty()) {
            dao.saveQuests(expired.map(::questToEntity))
        }
    }

    private suspend fun generateDailyParkQuests(
        petId: String,
        dateKey: String,
        now: Long
    ): List<TamaQuest> {
        val random = Random((petId + dateKey).hashCode())
        val livestock = farmRepository.getLivestock(petId)
        val livestockTypes = livestock.mapNotNull { FarmLivestockType.fromId(it.type) }
        val requestableItemIds = buildList {
            addAll(CropDefinitions.CROPS.keys.sorted().map { "crop_$it" })
            if (livestockTypes.any { it == FarmLivestockType.BARN }) add(FarmLivestockType.BARN.productInventoryId)
            if (livestockTypes.any { it == FarmLivestockType.COOP }) add(FarmLivestockType.COOP.productInventoryId)
        }
        val npcs = TamaParkSocialCatalog.regularCatalog
        val usedPayloads = mutableSetOf<String>()
        return buildList {
            repeat(PARK_QUEST_COUNT) {
                var requests: List<QuestItemRequest>
                var payloadKey: String
                do {
                    val requestCount = random.nextInt(1, 5)
                    requests = requestableItemIds.shuffled(random)
                        .take(requestCount)
                        .sorted()
                        .map { itemId -> QuestItemRequest(itemId = itemId, quantity = random.nextInt(1, 4)) }
                    payloadKey = requests.joinToString("|") { "${it.itemId}:${it.quantity}" }
                } while (!usedPayloads.add(payloadKey))
                val npc = npcs[random.nextInt(npcs.size)]
                val multiplier = questRewardMultiplier(random)
                add(
                    TamaQuest(
                        id = UUID.randomUUID().toString(),
                        petId = petId,
                        status = TamaQuestStatus.AVAILABLE,
                        generatedDateKey = dateKey,
                        npcId = npc.id,
                        requests = requests,
                        rewardCoins = calculateQuestReward(requests, multiplier),
                        summary = questSummaryText(context, requests)
                    )
                )
            }
        }
    }

    private fun hasQuestRequirements(inventory: List<InventoryItem>, quest: TamaQuest): Boolean {
        return quest.requests.all { request ->
            (inventory.firstOrNull { it.id == request.itemId }?.quantity ?: 0) >= request.quantity
        }
    }

    private fun questEntityToDomain(entity: TamaQuestEntity): TamaQuest {
        val requests = decodeQuestRequests(entity.requestsJson)
        val summary = runCatching { Json.decodeFromString<TamaLocalizedText>(entity.summaryJson) }
            .getOrElse { questSummaryText(context, requests) }
        return TamaQuest(
            id = entity.id,
            petId = entity.petId,
            status = runCatching { TamaQuestStatus.valueOf(entity.status) }.getOrDefault(TamaQuestStatus.AVAILABLE),
            generatedDateKey = entity.generatedDateKey,
            acceptedAt = entity.acceptedAt,
            expiresAt = entity.expiresAt,
            completedAt = entity.completedAt,
            npcId = entity.npcId,
            requests = requests,
            rewardCoins = entity.rewardCoins,
            summary = summary
        )
    }

    private fun decodeQuestRequests(requestsJson: String): List<QuestItemRequest> {
        return runCatching {
            Json.decodeFromString<List<QuestItemRequest>>(requestsJson)
        }.recoverCatching {
            Json.decodeFromString<List<LegacyQuestCropRequest>>(requestsJson).map { legacy ->
                QuestItemRequest(itemId = "crop_${legacy.cropId}", quantity = legacy.quantity)
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeQuestChecklistSources(sourceQuestIdsJson: String?): MutableSet<String> {
        if (sourceQuestIdsJson.isNullOrBlank()) return mutableSetOf()
        return runCatching {
            Json.decodeFromString<List<String>>(sourceQuestIdsJson)
                .filter { it.isNotBlank() }
                .toMutableSet()
        }.getOrDefault(mutableSetOf())
    }

    private fun questCompletionThanksMessage(npcName: String, rewardCoins: Long): String {
        val templates = listOf(
            R.string.tama_quest_thanks_1,
            R.string.tama_quest_thanks_2,
            R.string.tama_quest_thanks_3,
            R.string.tama_quest_thanks_4,
            R.string.tama_quest_thanks_5,
            R.string.tama_quest_thanks_6,
            R.string.tama_quest_thanks_7,
            R.string.tama_quest_thanks_8,
            R.string.tama_quest_thanks_9,
            R.string.tama_quest_thanks_10
        )
        val template = templates.random()
        return context.getString(template, npcName, rewardCoins)
    }

    private fun harvestYieldForCrop(crop: PlantedCrop, randomValue: Float = Random.nextFloat()): Int {
        if (!crop.isFertilized || crop.isDecayed) return 1
        return when {
            randomValue < 0.15f -> 3
            randomValue < 0.45f -> 2
            else -> 1
        }
    }

    private fun questToEntity(quest: TamaQuest): TamaQuestEntity {
        return TamaQuestEntity(
            id = quest.id,
            petId = quest.petId,
            status = quest.status.name,
            generatedDateKey = quest.generatedDateKey,
            acceptedAt = quest.acceptedAt,
            expiresAt = quest.expiresAt,
            completedAt = quest.completedAt,
            npcId = quest.npcId,
            requestsJson = Json.encodeToString(quest.requests),
            rewardCoins = quest.rewardCoins,
            summaryJson = Json.encodeToString(quest.summary)
        )
    }

    private fun resolveLocation(locationId: String?): TamaLocation? {
        val normalized = locationId?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return FIXED_LOCATIONS_BY_ID[normalized]
            ?: FIXED_LOCATIONS_BY_ID.values.firstOrNull { known ->
                known.type.name.equals(normalized, ignoreCase = true) ||
                    known.name.equals(normalized, ignoreCase = true)
            }
    }

    private fun fixedLocation(x: Int, y: Int, type: LocationType): TamaLocation {
        return TamaLocation(
            id = "fixed_${x}_${y}",
            name = type.localizedName(context),
            type = type,
            description = type.localizedDescription(context),
            cityId = "hometown",
            x = x,
            y = y,
            isDiscovered = type == LocationType.HOME
        )
    }

    internal fun applyMiscarePenalty(pet: TamaPet): TamaPet {
        val penalized = pet.applyMiscarePenalty()
        return penalized.copy(mood = effectiveMood(penalized))
    }

    fun close() {
        eventSyncJob?.cancel()
        eventSyncJob = null
        backgroundScope.cancel()
    }

    // Simplified engine - legacy mapper methods removed

    private fun eventToEntity(event: TamaEvent): TamaEventEntity = TamaEventEntity(
        id = event.id,
        timestamp = event.timestamp,
        petId = event.petId,
        eventType = event.eventType.name,
        details = event.details,
        locationId = event.locationId,
        npcId = event.npcId,
        statsChangeJson = event.statsChange?.let { Json.encodeToString(it) }
    )

    private fun entityToEvent(entity: TamaEventEntity): TamaEvent = TamaEvent(
        id = entity.id,
        timestamp = entity.timestamp,
        petId = entity.petId,
        eventType = try { EventType.valueOf(entity.eventType) } catch(e: Exception) { EventType.OTHER },
        details = entity.details,
        locationId = entity.locationId,
        npcId = entity.npcId,
        statsChange = entity.statsChangeJson?.let { jsonObjectToFloatMap(it) }
    )

    private fun jsonArrayToStringList(jsonStr: String): List<String> {
        return try { Json.decodeFromString<List<String>>(jsonStr) } catch(e: Exception) { emptyList() }
    }

    private fun jsonObjectToFloatMap(jsonStr: String): Map<String, Float> {
        return try { Json.decodeFromString<Map<String, Float>>(jsonStr) } catch(e: Exception) { emptyMap() }
    }

    private fun buildExportSummaries(
        petId: String,
        summaries: List<TamaSummaryEntity>,
        artworks: List<TamaArtworkEntity>
    ): List<TamaTransferSummary> {
        val persisted = summaries.map(TamaTransferSummary::fromEntity)
        val existingIds = persisted.map { it.id }.toMutableSet()
        val backfilledDreamDays = artworks
            .asSequence()
            .filter { it.kind == TamaArtworkKind.DAILY_DREAM.name }
            .filter { it.status == TamaArtworkStatus.COMPLETED.name }
            .filter { !it.albumId.isNullOrBlank() }
            .groupBy { it.albumId }
            .values
            .mapNotNull { albumItems ->
                val albumSummary = albumItems.firstNotNullOfOrNull { it.albumSummary?.takeIf(String::isNotBlank) }
                    ?: return@mapNotNull null
                val albumDate = albumItems.firstNotNullOfOrNull { it.albumDate?.takeIf(String::isNotBlank) }
                    ?: albumItems.maxOfOrNull { it.completedAt ?: it.createdAt }
                        ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it)) }
                    ?: return@mapNotNull null
                val summaryId = "${petId}_day_$albumDate"
                if (!existingIds.add(summaryId)) return@mapNotNull null
                val completedAt = albumItems.maxOfOrNull { it.completedAt ?: it.createdAt } ?: System.currentTimeMillis()
                TamaTransferSummary(
                    id = summaryId,
                    petId = petId,
                    date = albumDate,
                    summary = albumSummary,
                    createdAt = completedAt,
                    lastEventTimestamp = completedAt,
                    lastChatMessageTimestamp = 0L
                )
            }
            .toList()
        return (persisted + backfilledDreamDays).sortedByDescending { it.createdAt }
    }

    private fun buildTransferSettings(): TamaTransferSettings {
        return TamaTransferSettings(
            tamaNormalDreamingEnabled = settingsRepo.tamaNormalDreamingEnabled.value,
            tamaDeepDreamingEnabled = settingsRepo.tamaDeepDreamingEnabled.value,
            tamaDeepDreamRetryCount = settingsRepo.tamaDeepDreamRetryCount.value,
            tamaDeepDreamDesiredLanguage = settingsRepo.tamaDeepDreamDesiredLanguage.value,
            tamaSchoolPaintingEnabled = settingsRepo.tamaSchoolPaintingEnabled.value,
            tamaPicGenModelFilename = settingsRepo.tamaPicGenModelFilename.value,
            tamaPicGenResolution = settingsRepo.tamaPicGenResolution.value,
            tamaWhisperModelPath = settingsRepo.tamaWhisperModelPath.value,
            tamaWhisperLanguage = settingsRepo.tamaWhisperLanguage.value,
            tamaChatImageInputEnabled = settingsRepo.tamaChatImageInputEnabled.value,
            tamaBackend = settingsRepo.tamaBackend.value,
            tamaThinkingEnabled = settingsRepo.tamaThinkingEnabled.value,
            tamaLlamaServerUrl = settingsRepo.tamaLlamaServerUrl.value,
            tamaLlamaSwapUrl = settingsRepo.tamaLlamaSwapUrl.value,
            tamaLlamaServerModelLabel = settingsRepo.tamaLlamaServerModelLabel.value,
            tamaLlamaServerContextTokens = settingsRepo.tamaLlamaServerContextTokens.value,
            tamaLlamaServerContextLabel = settingsRepo.tamaLlamaServerContextLabel.value,
            tamaPetModel = settingsRepo.tamaPetModel.value,
            tamaSummarizerModel = settingsRepo.tamaSummarizerModel.value,
            tamaPetPrompt = settingsRepo.tamaPetPrompt.value,
            tamaSummarizerPrompt = settingsRepo.tamaSummarizerPrompt.value,
            tamaOllamaUrl = settingsRepo.tamaOllamaUrl.value,
            tamaOllamaMmap = settingsRepo.tamaOllamaMmap.value,
            tamaOllamaThreads = settingsRepo.tamaOllamaThreads.value,
            tamaOllamaNumCtx = settingsRepo.tamaOllamaNumCtx.value,
            adventureModel = settingsRepo.adventureModel.value,
            adventureSummarizerModel = settingsRepo.adventureSummarizerModel.value,
            adventureSystemPrompt = settingsRepo.adventureSystemPrompt.value,
            adventureSummarizerPrompt = settingsRepo.adventureSummarizerPrompt.value,
            adventureOllamaUrl = settingsRepo.adventureOllamaUrl.value,
            adventureOllamaMmap = settingsRepo.adventureOllamaMmap.value,
            adventureOllamaThreads = settingsRepo.adventureOllamaThreads.value,
            adventureOllamaNumCtx = settingsRepo.adventureOllamaNumCtx.value,
            adventureLanguage = settingsRepo.adventureLanguage.value,
            adventureBackend = settingsRepo.adventureBackend.value,
            adventureLlamaServerUrl = settingsRepo.adventureLlamaServerUrl.value,
            adventureLlamaSwapUrl = settingsRepo.adventureLlamaSwapUrl.value,
            adventureLlamaServerModelLabel = settingsRepo.adventureLlamaServerModelLabel.value,
            adventureLlamaServerContextTokens = settingsRepo.adventureLlamaServerContextTokens.value,
            adventureLlamaServerContextLabel = settingsRepo.adventureLlamaServerContextLabel.value,
            adventureWorldImageEnabled = settingsRepo.adventureWorldImageEnabled.value,
            adventureStageImagesEnabled = settingsRepo.adventureStageImagesEnabled.value,
            adventureOnnxModelFilename = settingsRepo.adventureOnnxModelFilename.value,
            adventureOnnxSteps = settingsRepo.adventureOnnxSteps.value,
            adventureOnnxCfg = settingsRepo.adventureOnnxCfg.value,
            adventureOnnxResolution = settingsRepo.adventureOnnxResolution.value
        )
    }

    private fun applyTransferSettings(settings: TamaTransferSettings) {
        settingsRepo.setTamaNormalDreamingEnabled(settings.tamaNormalDreamingEnabled)
        settingsRepo.setTamaDeepDreamingEnabled(settings.tamaDeepDreamingEnabled)
        settingsRepo.setTamaDeepDreamRetryCount(settings.tamaDeepDreamRetryCount)
        settingsRepo.setTamaDeepDreamDesiredLanguage(settings.tamaDeepDreamDesiredLanguage)
        settingsRepo.setTamaSchoolPaintingEnabled(settings.tamaSchoolPaintingEnabled)
        settingsRepo.setTamaPicGenModelFilename(settings.tamaPicGenModelFilename)
        settingsRepo.setTamaPicGenResolution(settings.tamaPicGenResolution)
        settingsRepo.setTamaWhisperModelPath(settings.tamaWhisperModelPath)
        settingsRepo.setTamaWhisperLanguage(settings.tamaWhisperLanguage)
        settingsRepo.setTamaChatImageInputEnabled(settings.tamaChatImageInputEnabled)
        settingsRepo.setTamaBackend(settings.tamaBackend)
        settingsRepo.setTamaThinkingEnabled(settings.tamaThinkingEnabled)
        settingsRepo.setTamaLlamaServerUrl(settings.tamaLlamaServerUrl)
        settingsRepo.setTamaLlamaSwapUrl(settings.tamaLlamaSwapUrl)
        settingsRepo.setTamaLlamaServerModelLabel(settings.tamaLlamaServerModelLabel)
        settingsRepo.setTamaLlamaServerContextTokens(settings.tamaLlamaServerContextTokens)
        settingsRepo.setTamaLlamaServerContextLabel(settings.tamaLlamaServerContextLabel)
        settingsRepo.setTamaPetModel(settings.tamaPetModel)
        settingsRepo.setTamaSummarizerModel(settings.tamaSummarizerModel)
        settingsRepo.setTamaPetPrompt(settings.tamaPetPrompt)
        settingsRepo.setTamaSummarizerPrompt(settings.tamaSummarizerPrompt)
        settingsRepo.setTamaOllamaUrl(settings.tamaOllamaUrl)
        settingsRepo.setTamaOllamaMmap(settings.tamaOllamaMmap)
        settingsRepo.setTamaOllamaThreads(settings.tamaOllamaThreads)
        settingsRepo.setTamaOllamaNumCtx(settings.tamaOllamaNumCtx)
        settingsRepo.setAdventureModel(settings.adventureModel)
        settingsRepo.setAdventureSummarizerModel(settings.adventureSummarizerModel)
        settingsRepo.setAdventureSystemPrompt(settings.adventureSystemPrompt)
        settingsRepo.setAdventureSummarizerPrompt(settings.adventureSummarizerPrompt)
        settingsRepo.setAdventureOllamaUrl(settings.adventureOllamaUrl)
        settingsRepo.setAdventureOllamaMmap(settings.adventureOllamaMmap)
        settingsRepo.setAdventureOllamaThreads(settings.adventureOllamaThreads)
        settingsRepo.setAdventureOllamaNumCtx(settings.adventureOllamaNumCtx)
        settingsRepo.setAdventureLanguage(settings.adventureLanguage)
        settingsRepo.setAdventureBackend(settings.adventureBackend)
        settingsRepo.setAdventureLlamaServerUrl(settings.adventureLlamaServerUrl)
        settingsRepo.setAdventureLlamaSwapUrl(settings.adventureLlamaSwapUrl)
        settingsRepo.setAdventureLlamaServerModelLabel(settings.adventureLlamaServerModelLabel)
        settingsRepo.setAdventureLlamaServerContextTokens(settings.adventureLlamaServerContextTokens)
        settingsRepo.setAdventureLlamaServerContextLabel(settings.adventureLlamaServerContextLabel)
        settingsRepo.setAdventureWorldImageEnabled(settings.adventureWorldImageEnabled)
        settingsRepo.setAdventureStageImagesEnabled(settings.adventureStageImagesEnabled)
        settingsRepo.setAdventureOnnxModelFilename(settings.adventureOnnxModelFilename)
        settingsRepo.setAdventureOnnxSteps(settings.adventureOnnxSteps)
        settingsRepo.setAdventureOnnxCfg(settings.adventureOnnxCfg)
        settingsRepo.setAdventureOnnxResolution(settings.adventureOnnxResolution)
    }

    private suspend fun clearGlobalTransferData() {
        dao.deleteAllNpcs()
        dao.deleteAllLocations()
    }

    private suspend fun clearPetScopedTransferData(petId: String) {
        val database = TamaDatabase.getInstance(context)
        val adventureSessions = dao.getAdventureHistory(petId)
        adventureSessions.forEach { session ->
            adventureWorldImageFile(session.id).delete()
            File(File(context.filesDir, ADVENTURE_WORLD_DIR), session.id).deleteRecursively()
            dao.deleteAdventureStages(session.id)
        }
        dao.deleteAdventureSessionsForPet(petId)
        dao.deleteDungeonProgress(petId)
        dao.deleteAdventureGateBattleState(petId)
        dao.deleteAdventureGateWorldProgress(petId)
        dao.deleteAdventureGateNightArenaRun(petId)
        dao.deleteAdventureGateProfile(petId)
        dao.deleteDeepDreamRunsForPet(petId)
        dao.deleteStudySessionsForPet(petId)
        dao.deleteStudyLabelsForPet(petId)
        dao.clearChatHistory(petId)
        dao.deleteSummariesForPet(petId)
        dao.deleteEventsForPet(petId)
        dao.deleteQuestsForPet(petId)
        dao.deleteQuestChecklistForPet(petId)
        dao.deleteMarketQuotesForPet(petId)
        dao.deleteArtworksForPet(petId)
        database.farmDao().clearTilesForPet(petId)
        database.farmDao().clearUpgradesForPet(petId)
        database.farmDao().clearLivestockForPet(petId)
        dao.deletePetById(petId)
        File(context.filesDir, "tama_gallery/$petId").deleteRecursively()
        File(context.filesDir, "$CHAT_AUDIO_DIR/$petId").deleteRecursively()
        File(context.filesDir, "$CHAT_IMAGE_DIR/$petId").deleteRecursively()
        TamaNotificationScheduler.cancelPetAlarms(context.applicationContext, petId)
    }

    suspend fun reduceToolDurability(tool: InventoryItem, amount: Int): Boolean {
        val pet = _pet.value ?: return false
        val inventory = pet.inventory.toMutableList()
        val index = inventory.indexOfFirst { it.id == tool.id }
        if (index == -1) return false

        val currentTool = inventory[index]
        val currentDurability = currentTool.durability ?: 100
        val newDurability = currentDurability - amount

        if (newDurability <= 0) {
            inventory.removeAt(index)
            logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_tool_broke, currentTool.name))
        } else {
            inventory[index] = currentTool.copy(durability = newDurability)
        }

        val updatedPet = pet.copy(inventory = inventory)
        _pet.value = updatedPet
        savePet(updatedPet)
        return true
    }
}
