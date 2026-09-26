package com.example.llamadroid.tama.world.memory

import android.app.Application
import androidx.room.Room
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.world.core.*
import com.example.llamadroid.tama.world.persistence.WorldInitializer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LivingWorldJournalTest {
    private lateinit var database: TamaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TamaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aggregatedCatchUpKeepsEventContextsAndEpisodeBounds() = runBlocking {
        val pet = createPet("journal-catch-up")
        val before = WorldInitializer(database).ensure(pet, 1_000L)
        val after = before.copy(
            tick = before.tick + 50,
            lastSimulatedAt = 20_000L,
            actor = before.actor.copy(x = 220, y = 220, presence = PresenceMode.WORLD)
        )
        val events = listOf(
            event("left_structure", 2_000L, WorldCoordinate(7, 8), Biome.FOREST),
            event("found_water", 3_000L, WorldCoordinate(8, 8), Biome.WETLANDS)
        )

        val journal = LivingWorldJournal(database)
        journal.record(before, after, events, transitionId = "catch-up")
        journal.record(before, after, events, transitionId = "catch-up")

        val saved = database.worldDao().events(pet.id, 100).sortedBy { it.timestamp }
        assertEquals("replaying an aggregate must remain idempotent", 2, saved.size)
        assertEquals(listOf(2_000L, 3_000L), saved.map { it.timestamp })
        assertEquals(listOf(7, 8), saved.map { LivingWorldJournal.decodeFacts(it.payload).getValue("x").toInt() })
        assertEquals(listOf(8, 8), saved.map { LivingWorldJournal.decodeFacts(it.payload).getValue("y").toInt() })
        assertEquals(listOf("FOREST", "WETLANDS"), saved.map { LivingWorldJournal.decodeFacts(it.payload).getValue("biome") })
        assertEquals(listOf("71.0", "71.0"), saved.map { LivingWorldJournal.decodeFacts(it.payload).getValue("hungerBefore") })
        assertTrue(saved.all { LivingWorldJournal.decodeFacts(it.payload).getValue("x").toInt() != after.actor.x })

        val episode = database.worldDao().episodes(pet.id).single()
        assertEquals(2_000L, episode.startTime)
        assertEquals(3_000L, episode.endTime)
        assertEquals(saved.map { it.id }, LivingWorldJournal.decodeEvidence(episode.evidenceJson))
    }

    @Test
    fun aggregatedAndUninterruptedTransitionsPersistTheSameEventFacts() = runBlocking {
        val aggregatePet = createPet("journal-aggregate")
        val uninterruptedPet = createPet("journal-uninterrupted")
        val aggregateStart = WorldInitializer(database).ensure(aggregatePet, 1_000L)
        val uninterruptedStart = WorldInitializer(database).ensure(uninterruptedPet, 1_000L)
        val aggregateEnd = aggregateStart.copy(tick = 40, lastSimulatedAt = 20_000L,
            actor = aggregateStart.actor.copy(x = 230, y = 230, presence = PresenceMode.WORLD))
        val uninterruptedMiddle = uninterruptedStart.copy(tick = 10, lastSimulatedAt = 2_000L,
            actor = uninterruptedStart.actor.copy(x = 7, y = 8, presence = PresenceMode.WORLD))
        val uninterruptedEnd = uninterruptedStart.copy(tick = 40, lastSimulatedAt = 20_000L,
            actor = uninterruptedStart.actor.copy(x = 230, y = 230, presence = PresenceMode.WORLD))
        val events = listOf(
            event("left_structure", 2_000L, WorldCoordinate(7, 8), Biome.FOREST),
            event("found_water", 3_000L, WorldCoordinate(8, 8), Biome.WETLANDS)
        )
        val journal = LivingWorldJournal(database)
        journal.record(aggregateStart, aggregateEnd, events, transitionId = "aggregate")
        journal.record(uninterruptedStart, uninterruptedMiddle, events.take(1), transitionId = "tick-1")
        journal.record(uninterruptedMiddle, uninterruptedEnd, events.drop(1), transitionId = "tick-2")

        val aggregateFacts = database.worldDao().events(aggregatePet.id, 100).sortedBy { it.timestamp }
            .map { eventFacts(it) }
        val uninterruptedFacts = database.worldDao().events(uninterruptedPet.id, 100).sortedBy { it.timestamp }
            .map { eventFacts(it) }
        assertEquals(uninterruptedFacts, aggregateFacts)
        assertEquals(2_000L to 3_000L, database.worldDao().episodes(aggregatePet.id).single().let { it.startTime to it.endTime })
        assertEquals(2_000L to 3_000L, database.worldDao().episodes(uninterruptedPet.id).single().let { it.startTime to it.endTime })
    }

    @Test
    fun eventWithoutContextRetainsLegacyFinalStateFallback() = runBlocking {
        val pet = createPet("journal-legacy")
        val before = WorldInitializer(database).ensure(pet, 1_000L)
        val after = before.copy(tick = 4, lastSimulatedAt = 9_000L,
            actor = before.actor.copy(x = 19, y = 21, presence = PresenceMode.WORLD))
        LivingWorldJournal(database).record(before, after, listOf(
            WorldEffectRequest.Event("legacy_event", EventImportance.NOTABLE, pet.id)
        ), transitionId = "legacy")

        val saved = database.worldDao().events(pet.id).single()
        val facts = LivingWorldJournal.decodeFacts(saved.payload)
        assertEquals(9_000L, saved.timestamp)
        assertEquals("19", facts.getValue("x"))
        assertEquals("21", facts.getValue("y"))
    }

    @Test
    fun repeatedRoutineCompressionMatchesAggregateAndUninterruptedCatchUp() = runBlocking {
        val aggregatePet = createPet("journal-routine-aggregate")
        val uninterruptedPet = createPet("journal-routine-uninterrupted")
        val aggregateStart = WorldInitializer(database).ensure(aggregatePet, 1_000L)
        val uninterruptedStart = WorldInitializer(database).ensure(uninterruptedPet, 1_000L)
        val routines = listOf(
            event("routine_walk", 2_000L, WorldCoordinate(7, 8), Biome.FOREST, EventImportance.ROUTINE),
            event("routine_walk", 3_000L, WorldCoordinate(7, 8), Biome.FOREST, EventImportance.ROUTINE),
            event("routine_walk", 4_000L, WorldCoordinate(7, 8), Biome.FOREST, EventImportance.ROUTINE)
        )
        val aggregateAfter = aggregateStart.copy(tick = 4, lastSimulatedAt = 4_000L,
            actor = aggregateStart.actor.copy(x = 7, y = 8, presence = PresenceMode.WORLD))
        val journal = LivingWorldJournal(database)
        journal.record(aggregateStart, aggregateAfter, routines, transitionId = "routine-aggregate")

        var previous = uninterruptedStart
        routines.forEachIndexed { index, routine ->
            val timestamp = checkNotNull(routine.context).timestamp
            val next = previous.copy(tick = previous.tick + 1, lastSimulatedAt = timestamp,
                actor = previous.actor.copy(x = 7, y = 8, presence = PresenceMode.WORLD))
            journal.record(previous, next, listOf(routine), transitionId = "routine-$index")
            previous = next
        }

        val aggregateEvents = database.worldDao().events(aggregatePet.id, 100)
        val uninterruptedEvents = database.worldDao().events(uninterruptedPet.id, 100)
        assertEquals(1, aggregateEvents.size)
        assertEquals(1, uninterruptedEvents.size)
        assertEquals(aggregateEvents.single().eventType, uninterruptedEvents.single().eventType)
        assertEquals(1, database.worldDao().episodes(aggregatePet.id).size)
        assertEquals(1, database.worldDao().episodes(uninterruptedPet.id).size)
    }

    @Test
    fun continuousThirtyMinuteChainMatchesBatchAndStepEpisodeGrouping() = runBlocking {
        val aggregatePet = createPet("journal-chain-aggregate")
        val steppedPet = createPet("journal-chain-stepped")
        val aggregateStart = WorldInitializer(database).ensure(aggregatePet, 1_000L)
        val steppedStart = WorldInitializer(database).ensure(steppedPet, 1_000L)
        val seed = event("chain_seed", 2_000L, WorldCoordinate(7, 8), Biome.FOREST)
        val aggregateSeedState = aggregateStart.copy(tick = 2, lastSimulatedAt = 2_000L,
            actor = aggregateStart.actor.copy(x = 7, y = 8, presence = PresenceMode.WORLD))
        val steppedSeedState = steppedStart.copy(tick = 2, lastSimulatedAt = 2_000L,
            actor = steppedStart.actor.copy(x = 7, y = 8, presence = PresenceMode.WORLD))
        val journal = LivingWorldJournal(database)
        journal.record(aggregateStart, aggregateSeedState, listOf(seed), transitionId = "chain-seed")
        journal.record(steppedStart, steppedSeedState, listOf(seed), transitionId = "chain-seed")

        val chain = (1..30).map { minute ->
            event("chain_$minute", 2_000L + minute * 60_000L,
                WorldCoordinate(7 + minute % 4, 8), Biome.FOREST)
        }
        val aggregateEnd = aggregateSeedState.copy(tick = 40, lastSimulatedAt = 1_802_000L,
            actor = aggregateSeedState.actor.copy(x = 9, y = 8, presence = PresenceMode.WORLD))
        journal.record(aggregateSeedState, aggregateEnd, chain, transitionId = "chain-batch")

        var steppedPrevious = steppedSeedState
        chain.forEachIndexed { index, nextEvent ->
            val next = steppedPrevious.copy(
                tick = steppedPrevious.tick + 1,
                lastSimulatedAt = checkNotNull(nextEvent.context).timestamp,
                actor = steppedPrevious.actor.copy(x = 7 + (index + 1) % 4, y = 8, presence = PresenceMode.WORLD)
            )
            journal.record(steppedPrevious, next, listOf(nextEvent), transitionId = "chain-$index")
            steppedPrevious = next
        }

        val aggregateEpisode = database.worldDao().episodes(aggregatePet.id).single()
        val steppedEpisode = database.worldDao().episodes(steppedPet.id).single()
        assertEquals(aggregateEpisode.startTime, steppedEpisode.startTime)
        assertEquals(aggregateEpisode.endTime, steppedEpisode.endTime)
        assertEquals(31, LivingWorldJournal.decodeEvidence(aggregateEpisode.evidenceJson).size)
        assertEquals(31, LivingWorldJournal.decodeEvidence(steppedEpisode.evidenceJson).size)
    }

    @Test
    fun internalHomeBoundariesSplitOneCatchUpIntoSeparateOutings() = runBlocking {
        val pet = createPet("journal-home-catch-up")
        val before = WorldInitializer(database).ensure(pet, 1_000L)
        val homeId = before.structures.first { it.type == StructureType.HOME }.id
        val petActor = before.actor.actorId
        val homePayload = mapOf("structureId" to homeId, "structureType" to StructureType.HOME.name)
        val after = before.copy(tick = 20, lastSimulatedAt = 6_000L,
            actor = before.actor.copy(x = 12, y = 12, presence = PresenceMode.WORLD))
        val events = listOf(
            event("left_structure", 2_000L, WorldCoordinate(7, 8), Biome.FOREST,
                EventImportance.ROUTINE, petActor, homePayload),
            event("first_outing", 3_000L, WorldCoordinate(8, 8), Biome.FOREST),
            event("entered_structure", 4_000L, WorldCoordinate(9, 8), Biome.FOREST,
                EventImportance.ROUTINE, petActor, homePayload),
            event("left_structure", 5_000L, WorldCoordinate(7, 8), Biome.FOREST,
                EventImportance.ROUTINE, petActor, homePayload),
            event("second_outing", 6_000L, WorldCoordinate(10, 8), Biome.FOREST)
        )
        LivingWorldJournal(database).record(before, after, events, transitionId = "home-catch-up")

        val episodes = database.worldDao().episodes(pet.id, 10).sortedBy { it.startTime }
        assertEquals(2, episodes.size)
        assertEquals(listOf("CLOSED", "OPEN"), episodes.map { it.memoryStatus })
        val saved = database.worldDao().events(pet.id, 20).associateBy { it.eventType }
        assertTrue(saved.getValue("first_outing").episodeId != saved.getValue("second_outing").episodeId)
    }

    @Test
    fun replayDoesNotRestoreSuppressedRoutineAfterNotableRowsBuryIt() = runBlocking {
        val pet = createPet("journal-routine-replay")
        val before = WorldInitializer(database).ensure(pet, 1_000L)
        val journal = LivingWorldJournal(database)
        val seedState = before.copy(tick = 2, lastSimulatedAt = 2_000L,
            actor = before.actor.copy(x = 7, y = 8, presence = PresenceMode.WORLD))
        val routine = event("same_routine", 2_000L, WorldCoordinate(7, 8), Biome.FOREST, EventImportance.ROUTINE)
        journal.record(before, seedState, listOf(routine), transitionId = "routine-seed")
        val suppressed = routine.copy(context = routine.context?.copy(timestamp = 3_000L))
        val suppressedState = seedState.copy(tick = 3, lastSimulatedAt = 3_000L)
        journal.record(seedState, suppressedState, listOf(suppressed), transitionId = "routine-suppressed")
        assertEquals(1, database.worldDao().events(pet.id, 20).size)

        val notable = (0 until 101).map { index ->
            event("buried_$index", 4_000L + index * 1_000L,
                WorldCoordinate(8 + index % 4, 8), Biome.FOREST)
        }
        val notableState = suppressedState.copy(tick = 110, lastSimulatedAt = 105_000L)
        journal.record(suppressedState, notableState, notable, transitionId = "routine-bury")
        val countBeforeReplay = database.worldDao().events(pet.id, 200).size
        journal.record(seedState, suppressedState, listOf(suppressed), transitionId = "routine-suppressed")

        val saved = database.worldDao().events(pet.id, 200)
        assertEquals(countBeforeReplay, saved.size)
        assertTrue(saved.none { it.id == "world:${pet.id}:routine-suppressed:0" })
    }

    @Test
    fun evidenceCapCreatesUniqueBoundedEpisodesAndReplayIsIdempotent() = runBlocking {
        val pet = createPet("journal-evidence-cap")
        val before = WorldInitializer(database).ensure(pet, 1_000L)
        val events = (0 until 260).map { index ->
            event("notable_$index", 2_000L + index * 1_000L,
                WorldCoordinate(7 + index % 20, 8 + index / 20), Biome.FOREST)
        }
        val after = before.copy(tick = 300, lastSimulatedAt = 400_000L,
            actor = before.actor.copy(x = 30, y = 30, presence = PresenceMode.WORLD))
        val journal = LivingWorldJournal(database)
        journal.record(before, after, events, transitionId = "evidence-cap")

        val firstEvents = database.worldDao().events(pet.id, 400).sortedBy { it.timestamp }
        val firstEpisodes = database.worldDao().episodes(pet.id, 10).sortedBy { it.startTime }
        assertEquals(260, firstEvents.size)
        assertEquals(listOf(256, 4), firstEpisodes.map { LivingWorldJournal.decodeEvidence(it.evidenceJson).size })
        assertTrue(firstEpisodes.all { LivingWorldJournal.decodeEvidence(it.evidenceJson).size <= 256 })
        assertEquals(2, firstEpisodes.map { it.id }.toSet().size)

        journal.record(before, after, events, transitionId = "evidence-cap")
        val replayEvents = database.worldDao().events(pet.id, 400).sortedBy { it.timestamp }
        val replayEpisodes = database.worldDao().episodes(pet.id, 10).sortedBy { it.startTime }
        assertEquals(firstEvents.map { it.id }, replayEvents.map { it.id })
        assertEquals(firstEpisodes.map { it.id }, replayEpisodes.map { it.id })
        assertEquals(firstEpisodes.map { it.evidenceJson }, replayEpisodes.map { it.evidenceJson })
    }

    @Test
    fun localTimeGapsAndMajorEventsSplitEpisodes() = runBlocking {
        val pet = createPet("journal-boundaries")
        val before = WorldInitializer(database).ensure(pet, 1_000L)
        val after = before.copy(tick = 4, lastSimulatedAt = 2_404_002L,
            actor = before.actor.copy(x = 30, y = 30, presence = PresenceMode.WORLD))
        val events = listOf(
            event("second", 1_202_001L, WorldCoordinate(8, 8), Biome.WETLANDS),
            event("first", 2_000L, WorldCoordinate(7, 8), Biome.FOREST),
            event("third", 2_404_002L, WorldCoordinate(9, 8), Biome.DESERT)
        )
        LivingWorldJournal(database).record(before, after, events, transitionId = "multi-gap")

        val episodes = database.worldDao().episodes(pet.id, 10).sortedBy { it.startTime }
        assertEquals(3, episodes.size)
        assertEquals(listOf(2_000L, 1_202_001L, 2_404_002L), episodes.map { it.startTime })
        assertEquals(3, episodes.map { it.id }.toSet().size)
        val saved = database.worldDao().events(pet.id, 10).sortedBy { it.timestamp }
        assertEquals(
            listOf("world:${pet.id}:multi-gap:1", "world:${pet.id}:multi-gap:0", "world:${pet.id}:multi-gap:2"),
            saved.map { it.id }
        )

        val majorPet = createPet("journal-major-boundary")
        val majorStart = WorldInitializer(database).ensure(majorPet, 1_000L)
        val world = majorStart.copy(tick = 4, lastSimulatedAt = 4_000L,
            actor = majorStart.actor.copy(x = 12, y = 12, presence = PresenceMode.WORLD))
        LivingWorldJournal(database).record(majorStart, world, listOf(
            event("before_major", 2_000L, WorldCoordinate(7, 8), Biome.FOREST),
            event("major_adventure", 3_000L, WorldCoordinate(8, 8), Biome.FOREST, EventImportance.MAJOR),
            event("after_major", 4_000L, WorldCoordinate(9, 8), Biome.FOREST)
        ), transitionId = "major-boundary")
        val majorEpisodes = database.worldDao().episodes(majorPet.id, 10).sortedBy { it.startTime }
        assertEquals(3, majorEpisodes.size)
        assertEquals(listOf("CLOSED", "PENDING", "OPEN"), majorEpisodes.map { it.memoryStatus })

        val home = world.copy(tick = 5, lastSimulatedAt = 5_000L,
            actor = world.actor.copy(presence = PresenceMode.HOME))
        LivingWorldJournal(database).record(world, home, listOf(
            event("returned_home", 5_000L, WorldCoordinate(9, 8), Biome.FOREST)
        ), transitionId = "home-boundary")
        val afterHome = database.worldDao().episodes(majorPet.id, 10).sortedBy { it.startTime }
        assertEquals(listOf("CLOSED", "PENDING", "CLOSED"), afterHome.map { it.memoryStatus })
    }

    @Test
    fun contextlessNpcEventUsesNpcFallbackInsteadOfPetFinalState() = runBlocking {
        val pet = createPet("journal-npc-fallback")
        val before = WorldInitializer(database).ensure(pet, 1_000L)
        val npc = before.npcs.first()
        val after = before.copy(
            tick = before.tick + 1,
            lastSimulatedAt = 9_000L,
            actor = before.actor.copy(x = 220, y = 221, presence = PresenceMode.WORLD),
            npcs = before.npcs.map {
                if (it.id == npc.id) it.copy(
                    x = 42,
                    y = 43,
                    execution = null
                ) else it
            }
        )
        LivingWorldJournal(database).record(before, after, listOf(
            WorldEffectRequest.Event("npc_legacy_event", EventImportance.NOTABLE, npc.id)
        ), transitionId = "npc-fallback")

        val saved = database.worldDao().events(pet.id).single()
        val facts = LivingWorldJournal.decodeFacts(saved.payload)
        assertEquals("42", facts.getValue("x"))
        assertEquals("43", facts.getValue("y"))
        assertEquals((npc.execution?.needs ?: npc.needs).hunger.toString(), facts.getValue("hungerBefore"))
        assertEquals((npc.execution?.goal ?: npc.currentGoal).name, facts.getValue("goal"))
        assertTrue(facts.getValue("x") != after.actor.x.toString())
    }

    private suspend fun createPet(id: String): TamaPet {
        val pet = TamaPet(id = id, name = "Pixel")
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        return pet
    }

    private fun event(
        type: String,
        timestamp: Long,
        coordinate: WorldCoordinate,
        biome: Biome,
        importance: EventImportance = EventImportance.NOTABLE,
        actorId: String = "pet",
        payload: Map<String, String> = emptyMap()
    ) =
        WorldEffectRequest.Event(
            eventType = type,
            importance = importance,
            actorId = actorId,
            payload = payload,
            context = WorldEventContext(
                timestamp = timestamp,
                actorCoordinate = coordinate,
                biome = biome,
                beforeGoal = GoalId.EXPLORE,
                beforeAction = ActionId.WALK,
                needs = NeedsProjection(hunger = 71f, hydration = 63f, energy = 55f)
            )
        )

    private fun eventFacts(event: com.example.llamadroid.tama.world.persistence.TamaWorldEventEntity): List<String> {
        val facts = LivingWorldJournal.decodeFacts(event.payload)
        return listOf(event.eventType, event.timestamp.toString(), facts["x"].orEmpty(),
            facts["y"].orEmpty(), facts["biome"].orEmpty())
    }
}
