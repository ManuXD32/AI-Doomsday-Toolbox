package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.TamaPetEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Handles conversion between TamaPet domain models and database entities.
 * Uses kotlinx.serialization for efficient and stable JSON handling.
 */
object PetMapper {
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    fun toEntity(pet: TamaPet): TamaPetEntity = TamaPetEntity(
        id = pet.id,
        name = pet.name,
        species = pet.species,
        birthTimestamp = pet.birthTimestamp,
        stageProgressStartTime = pet.stageProgressStartTime,
        lastDecayTime = pet.lastDecayTime,
        stage = pet.stage.name,
        hunger = pet.stats.hunger,
        happiness = pet.stats.happiness,
        health = pet.stats.health,
        energy = pet.stats.energy,
        hygiene = pet.stats.hygiene,
        mood = pet.mood.name,
        personality = pet.personality.name,
        eyeStyle = pet.genetics.eyeStyle,
        earStyle = pet.genetics.earStyle,
        mouthStyle = pet.genetics.mouthStyle,
        headShape = pet.genetics.headShape,
        bodyStyle = pet.genetics.bodyStyle,
        armStyle = pet.genetics.armStyle,
        legStyle = pet.genetics.legStyle,
        colorTint = pet.genetics.colorTint,
        ownerBondLevel = pet.ownerBondLevel,
        educationLevel = pet.educationLevel,
        exerciseLevel = pet.exerciseLevel,
        introspectionLevel = pet.introspectionLevel,
        currentLocationId = pet.currentLocationId,
        homeRoomId = pet.homeRoomId,
        leftDecorationId = pet.leftDecorationId,
        rightDecorationId = pet.rightDecorationId,
        growthLocked = pet.growthLocked,
        growthLockStartedAt = pet.growthLockStartedAt,
        money = pet.money,
        inventoryJson = json.encodeToString(pet.inventory),
        currentActivity = pet.currentActivity.name,
        currentWorkJobId = pet.currentWorkJobId,
        activityStartTime = pet.activityStartTime,
        isSleeping = pet.isSleeping,
        sleepStartTime = pet.sleepStartTime,
        lastDailyDreamDate = pet.lastDailyDreamDate,
        pendingDreamAlbumId = pet.pendingDreamAlbumId,
        currentParkEncounterJson = pet.currentParkEncounter?.let { json.encodeToString(it) },
        currentAmbientNpcJson = pet.currentAmbientNpc?.let { json.encodeToString(it) },
        lastRecyclerEncounterDate = pet.lastRecyclerEncounterDate,
        nextPoopAt = pet.nextPoopAt,
        poopCreatedAt = pet.poopCreatedAt,
        poopCount = pet.poopCount,
        lastPoopMiscareAt = pet.lastPoopMiscareAt,
        lastSleepWarningTime = pet.lastSleepWarningTime,
        overnightAwakeDateKey = pet.overnightAwakeDateKey,
        overnightAwakeAccumulatedMs = pet.overnightAwakeAccumulatedMs,
        miscareCount = pet.miscareCount,
        isMad = pet.isMad,
        relationshipsJson = json.encodeToString(pet.relationships),
        discoveredLocationIdsJson = json.encodeToString(pet.discoveredLocationIds)
    )

    fun toDomain(entity: TamaPetEntity): TamaPet = TamaPet(
        species = normalizePetSpecies(entity.species, entity.bodyStyle),
        id = entity.id,
        name = entity.name,
        birthTimestamp = entity.birthTimestamp,
        stageProgressStartTime = entity.stageProgressStartTime.takeIf { it > 0L } ?: entity.birthTimestamp,
        lastDecayTime = entity.lastDecayTime,
        stage = try { GrowthStage.valueOf(entity.stage) } catch (e: Exception) { GrowthStage.BABY },
        stats = PetStats(
            hunger = entity.hunger,
            happiness = entity.happiness,
            health = entity.health,
            energy = entity.energy,
            hygiene = entity.hygiene
        ),
        mood = try { Mood.valueOf(entity.mood) } catch (e: Exception) { Mood.HAPPY },
        personality = try { Personality.valueOf(entity.personality) } catch (e: Exception) { Personality.random() },
        genetics = GeneticTraits(
            eyeStyle = entity.eyeStyle,
            earStyle = entity.earStyle,
            mouthStyle = entity.mouthStyle,
            headShape = entity.headShape,
            bodyStyle = entity.bodyStyle,
            armStyle = entity.armStyle,
            legStyle = entity.legStyle,
            colorTint = entity.colorTint
        ),
        ownerBondLevel = entity.ownerBondLevel,
        educationLevel = entity.educationLevel,
        exerciseLevel = entity.exerciseLevel.coerceAtLeast(0f),
        introspectionLevel = entity.introspectionLevel.coerceAtLeast(0f),
        currentLocationId = entity.currentLocationId,
        homeRoomId = entity.homeRoomId.takeIf { it.isNotBlank() } ?: TamaRoomCatalog.PRINCIPAL_ROOM_ID,
        leftDecorationId = entity.leftDecorationId,
        rightDecorationId = entity.rightDecorationId,
        growthLocked = entity.growthLocked,
        growthLockStartedAt = entity.growthLockStartedAt,
        money = entity.money,
        inventory = try { 
            json.decodeFromString<List<InventoryItem>>(entity.inventoryJson) 
        } catch (e: Exception) { emptyList() },
        currentActivity = try { ActivityType.valueOf(entity.currentActivity) } catch (e: Exception) { ActivityType.NONE },
        currentWorkJobId = entity.currentWorkJobId,
        activityStartTime = entity.activityStartTime,
        isSleeping = entity.isSleeping,
        sleepStartTime = entity.sleepStartTime,
        lastDailyDreamDate = entity.lastDailyDreamDate,
        pendingDreamAlbumId = entity.pendingDreamAlbumId,
        currentParkEncounter = try {
            entity.currentParkEncounterJson?.let { json.decodeFromString<TamaParkEncounter>(it) }
        } catch (e: Exception) { null },
        currentAmbientNpc = try {
            entity.currentAmbientNpcJson?.let { json.decodeFromString<TamaAmbientNpcState>(it) }
        } catch (e: Exception) { null },
        lastRecyclerEncounterDate = entity.lastRecyclerEncounterDate,
        nextPoopAt = entity.nextPoopAt,
        poopCreatedAt = entity.poopCreatedAt,
        poopCount = entity.poopCount,
        lastPoopMiscareAt = entity.lastPoopMiscareAt,
        lastSleepWarningTime = entity.lastSleepWarningTime,
        overnightAwakeDateKey = entity.overnightAwakeDateKey,
        overnightAwakeAccumulatedMs = entity.overnightAwakeAccumulatedMs,
        miscareCount = entity.miscareCount,
        isMad = entity.isMad,
        relationships = try {
            json.decodeFromString<Map<String, Int>>(entity.relationshipsJson)
        } catch (e: Exception) { emptyMap() },
        discoveredLocationIds = try {
            json.decodeFromString<Set<String>>(entity.discoveredLocationIdsJson)
        } catch (e: Exception) { setOf("home") }
    )
}
