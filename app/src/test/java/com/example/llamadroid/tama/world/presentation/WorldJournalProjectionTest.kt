package com.example.llamadroid.tama.world.presentation

import com.example.llamadroid.tama.world.persistence.TamaWorldEpisodeEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldJournalProjectionTest {
    @Test
    fun builtInTitlesActorsAndFactIdsUsePresentationLabels() {
        val labels = WorldJournalLabels(
            eventTitle = { raw -> if (raw.equals("ITEM_BOUGHT", ignoreCase = true)) "Bought an item" else "Other event" },
            resultLabel = { key -> key },
            locationName = { "Mimi's shop" },
            worldName = "Living world",
            openEpisodeSummary = "Still recording",
            episodeTitle = { raw -> if (raw.equals("item_bought", ignoreCase = true)) "Bought an item" else raw },
            actorName = { id -> if (id == "pet" || id == "pet-1") "Pip" else null },
            resultValue = { key, value ->
                when (key) {
                    "itemId" -> if (value == "berry") "Wild berries" else null
                    "objectId" -> null
                    else -> value
                }
            }
        )
        val state = projectWorldJournal(
            episodes = listOf(episode(title = "item_bought", memoryStatus = "PENDING")),
            eventsByEpisode = mapOf("episode-1" to listOf(event())) ,
            labels = labels
        )

        val projectedEpisode = state.episodes.single()
        val projectedEvent = projectedEpisode.events.single()
        val resultValues = projectedEvent.results.associate { it.label to it.value }

        assertEquals("Bought an item", projectedEpisode.title)
        assertEquals("Bought an item", state.pendingMemories.single().title)
        assertEquals("Bought an item", projectedEvent.title)
        assertEquals("Pip", projectedEvent.actorName)
        assertEquals("Wild berries", resultValues["itemId"])
        assertEquals("2", resultValues["quantity"])
        assertFalse(resultValues.values.any { it.contains("opaque-object") })
        assertFalse(projectedEvent.results.any { it.label == "objectId" })
    }

    @Test
    fun storedUserAndModelProseRemainUnchangedWhileUnknownActorIdsStayHidden() {
        val prose = "The model noticed a quiet path and saved this exact wording."
        val labels = WorldJournalLabels(
            eventTitle = { "Localized fallback" },
            episodeTitle = { raw -> if (raw == "ITEM_BOUGHT") "Bought an item" else raw },
            actorName = { null },
            resultValue = { key, value -> if (key.endsWith("Id", ignoreCase = true)) null else value }
        )
        val state = projectWorldJournal(
            episodes = listOf(episode(title = "My own adventure", summary = prose)),
            eventsByEpisode = mapOf("episode-1" to listOf(event(eventType = "custom_event", actorId = "internal-actor"))),
            labels = labels
        )

        val projectedEvent = state.episodes.single().events.single()
        assertEquals("My own adventure", state.episodes.single().title)
        assertEquals(prose, state.episodes.single().summary)
        assertEquals(prose, projectedEvent.detail)
        assertNull(projectedEvent.actorName)
    }

    @Test
    fun defaultFactFormatterHidesOpaqueIdentifiers() {
        val labels = WorldJournalLabels()

        assertNull(labels.resultValue("objectId", "opaque-object"))
        assertNull(labels.resultValue("legacyEventId", "legacy-42"))
        assertEquals("7", labels.resultValue("quantity", "7"))
        assertTrue(labels.resultValue("reward", "3") == "3")
    }

    @Test
    fun scalarFactsRemainVisibleWhenPayloadAlsoContainsNestedMetadata() {
        val projected = projectWorldJournal(
            episodes = listOf(episode(title = "scalar-facts")),
            eventsByEpisode = mapOf(
                "episode-1" to listOf(
                    event(
                        payload = """{"source":"LIVING_WORLD","details":"Verified detail","quantity":"2","metadata":{"internal":"hidden"}}"""
                    )
                )
            ),
            labels = WorldJournalLabels(eventTitle = { it }, resultLabel = { it })
        ).episodes.single().events.single()

        assertEquals("Verified detail", projected.detail)
        assertEquals("2", projected.results.single { it.label == "quantity" }.value)
        assertTrue(projected.results.none { it.value == "hidden" })
    }

    @Test
    fun qaEpisodeRowsRenderVerifiedFactsAndUseEvidenceWhenLinksAreStale() {
        val episodeId = "world:adt-world-release-qa:episode:51"
        val events = listOf(
            qaEvent(
                id = "world:adt-world-release-qa:51:0",
                episodeId = episodeId,
                eventType = "left_structure",
                payload = """{"structureId":"fixed_0_0","source":"LIVING_WORLD","x":"121","y":"99","biome":"FOREST","goal":"IDLE","action":"WAIT","hungerBefore":"100.0","hydrationBefore":"100.0","energyBefore":"100.0"}"""
            ),
            qaEvent(
                id = "world:adt-world-release-qa:51:1",
                episodeId = episodeId,
                eventType = "BIOME_DISCOVERED",
                payload = """{"discoveredBiome":"FOREST","source":"LIVING_WORLD","x":"121","y":"99","biome":"FOREST","goal":"IDLE","action":"WAIT","hungerBefore":"100.0","hydrationBefore":"100.0","energyBefore":"100.0"}"""
            ),
            qaEvent(
                id = "world:adt-world-release-qa:51:2",
                episodeId = episodeId,
                eventType = "BIOME_DISCOVERED",
                payload = """{"discoveredBiome":"WETLANDS","source":"LIVING_WORLD","x":"121","y":"99","biome":"FOREST","goal":"IDLE","action":"WAIT","hungerBefore":"100.0","hydrationBefore":"100.0","energyBefore":"100.0"}"""
            ),
            qaEvent(
                id = "world:adt-world-release-qa:106:0",
                episodeId = episodeId,
                eventType = "PLACE_DISCOVERED",
                payload = """{"locationId":"npc_home_a","x":"108","y":"100","source":"LIVING_WORLD","biome":"FOREST","goal":"GATHER_WOOD","action":"GATHER_WOOD","hungerBefore":"99.9875","hydrationBefore":"99.9875","energyBefore":"99.46744"}"""
            ),
            qaEvent(
                id = "world:adt-world-release-qa:525:0",
                episodeId = episodeId,
                eventType = "entered_structure",
                payload = """{"structureId":"fixed_0_0","structureType":"HOME","source":"LIVING_WORLD","x":"122","y":"122","biome":"MEADOW","goal":"RETURN_HOME","action":"WALK","hungerBefore":"99.93057","hydrationBefore":"99.93057","energyBefore":"94.29998"}"""
            )
        )
        val episode = TamaWorldEpisodeEntity(
            id = episodeId,
            worldId = "world:adt-world-release-qa",
            petId = "adt-world-release-qa",
            startTime = 1_789_434_251_089L,
            endTime = 1_789_434_294_483L,
            title = "left_structure",
            summary = "",
            importance = "MEMORABLE",
            memoryStatus = "CLOSED",
            evidenceJson = """["${events[0].id}","${events[1].id}","${events[2].id}","${events[3].id}","${events[4].id}"]"""
        )
        val labels = WorldJournalLabels(
            openEpisodeSummary = "Awaiting summary",
            episodeTitle = { if (it == "left_structure") "Left a place" else it },
            timeRange = { _, _ -> "12:00–12:01" },
            eventTitle = { it },
            time = { "12:00" }
        )

        val direct = projectWorldJournal(
            episodes = listOf(episode),
            eventsByEpisode = events.groupBy { requireNotNull(it.episodeId) },
            allEvents = events,
            labels = labels
        ).episodes.single()
        assertEquals(5, direct.events.size)
        assertEquals("Awaiting summary", direct.summary)

        val staleLinks = events.map { it.copy(episodeId = null) }
        val foreign = events.first().copy(
            id = "world:other-world:51:0",
            worldId = "world:other-world",
            episodeId = null
        )
        val recovered = projectWorldJournal(
            episodes = listOf(
                episode.copy(
                    evidenceJson = """["${events[0].id}","${events[1].id}","${events[2].id}","${events[3].id}","${events[4].id}","${foreign.id}"]"""
                )
            ),
            allEvents = staleLinks + foreign,
            labels = labels
        ).episodes.single()
        assertEquals(5, recovered.events.size)
    }

    private fun episode(title: String, summary: String = "", memoryStatus: String = "CLOSED") =
        TamaWorldEpisodeEntity(
            id = "episode-1",
            worldId = "world-1",
            petId = "pet-1",
            startTime = 10L,
            endTime = 20L,
            title = title,
            summary = summary,
            importance = "NOTABLE",
            memoryStatus = memoryStatus,
            evidenceJson = "[]"
        )

    private fun event(
        eventType: String = "item_bought",
        actorId: String = "pet",
        payload: String = """{"source":"LIVING_WORLD","itemId":"berry","quantity":"2","objectId":"opaque-object","details":"$DETAILS"}"""
    ) = TamaWorldEventEntity(
        id = "event-1",
        worldId = "world-1",
        petId = "pet-1",
        timestamp = 20L,
        importance = "NOTABLE",
        actorId = actorId,
        eventType = eventType,
        payload = payload,
        memoryEligible = true,
        episodeId = "episode-1"
    )

    private fun qaEvent(
        id: String,
        episodeId: String?,
        eventType: String,
        payload: String
    ) = TamaWorldEventEntity(
        id = id,
        worldId = "world:adt-world-release-qa",
        petId = "adt-world-release-qa",
        timestamp = 1_789_434_251_089L,
        importance = if (eventType == "left_structure" || eventType == "entered_structure") "ROUTINE" else "MEMORABLE",
        actorId = "adt-world-release-qa",
        eventType = eventType,
        payload = payload,
        memoryEligible = eventType != "left_structure" && eventType != "entered_structure",
        episodeId = episodeId
    )

    private companion object {
        const val DETAILS = "The model noticed a quiet path and saved this exact wording."
    }
}
