package com.example.llamadroid.tama.world.presentation

import com.example.llamadroid.tama.world.memory.AdventureMemoryPolicy
import com.example.llamadroid.tama.world.persistence.TamaWorldEpisodeEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldEventEntity
import com.example.llamadroid.tama.world.ui.WorldBiome
import com.example.llamadroid.tama.world.ui.WorldEventImportance
import com.example.llamadroid.tama.world.ui.WorldEventResultUi
import com.example.llamadroid.tama.world.ui.WorldEventSource
import com.example.llamadroid.tama.world.ui.WorldEpisodeUi
import com.example.llamadroid.tama.world.ui.WorldJournalUiState
import com.example.llamadroid.tama.world.ui.WorldMemoryCandidateUi
import com.example.llamadroid.tama.world.ui.WorldMemoryPolicy
import com.example.llamadroid.tama.world.ui.WorldEventUi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Locale-sensitive labels supplied by the app shell when it builds journal state. */
data class WorldJournalLabels(
    val eventTitle: (String) -> String = ::humanize,
    val resultLabel: (String) -> String = ::humanize,
    val locationName: (String) -> String = { it },
    val worldName: String = "Living world",
    val openEpisodeSummary: String = "Episode is still being recorded",
    val timeRange: (Long, Long) -> String = { start, end -> "$start–$end" },
    val time: (Long) -> String = Long::toString,
    /** Localizes built-in episode keys while leaving stored user/LLM titles untouched. */
    val episodeTitle: (String) -> String = { it },
    /** Resolves a persisted actor ID; null keeps opaque IDs out of the product UI. */
    val actorName: (String) -> String? = { null },
    /** Formats a fact value or returns null for internal identifiers/metadata. */
    val resultValue: (String, String) -> String? = { key, value ->
        value.takeUnless { key.endsWith("Id", ignoreCase = true) }
    }
)

/**
 * Projects only persisted living-world rows. Training telemetry cannot enter this
 * adapter because it has no matching Room entities and training-source payloads
 * are filtered before they reach the Compose contract.
 */
fun projectWorldJournal(
    episodes: List<TamaWorldEpisodeEntity>,
    eventsByEpisode: Map<String, List<TamaWorldEventEntity>> = emptyMap(),
    /** All rows currently available to recover evidence links from older snapshots. */
    allEvents: List<TamaWorldEventEntity> = emptyList(),
    policy: AdventureMemoryPolicy = AdventureMemoryPolicy.AUTO_MAJOR,
    labels: WorldJournalLabels = WorldJournalLabels(),
    selectedEpisodeId: String? = null,
    filter: com.example.llamadroid.tama.world.ui.WorldJournalFilter =
        com.example.llamadroid.tama.world.ui.WorldJournalFilter.ALL
): WorldJournalUiState {
    val knownEventsById = (allEvents + eventsByEpisode.values.flatten())
        .groupBy { it.id }
    val projected = episodes
        .asSequence()
        .mapNotNull { episode ->
            val linked = eventsByEpisode[episode.id].orEmpty()
            val evidenced = decodeEvidence(episode.evidenceJson).mapNotNull { evidenceId ->
                knownEventsById[evidenceId]
                    ?.firstOrNull { event ->
                        event.worldId == episode.worldId && event.petId == episode.petId
                    }
            }
            projectEpisode(
                episode = episode,
                events = (linked + evidenced).distinctBy { it.id },
                labels = labels
            )
        }
        .toList()
    val candidates = episodes
        .asSequence()
        .filter { it.memoryStatus in MEMORY_REVIEW_STATUSES }
        .map { episode ->
            WorldMemoryCandidateUi(
                episodeId = episode.id,
                title = labels.episodeTitle(episode.title),
                summary = episode.summary.ifBlank { labels.openEpisodeSummary },
                timeRange = labels.timeRange(episode.startTime, episode.endTime),
                importance = importanceOf(episode.importance),
                memoryStatus = episode.memoryStatus
            )
        }
        .toList()
    return WorldJournalUiState(
        episodes = projected,
        selectedEpisodeId = selectedEpisodeId,
        filter = filter,
        memoryPolicy = policy.toUiPolicy(),
        pendingMemories = candidates
    )
}

fun WorldMemoryPolicy.toDomainPolicy(): AdventureMemoryPolicy = when (this) {
    WorldMemoryPolicy.NEVER -> AdventureMemoryPolicy.NEVER
    WorldMemoryPolicy.ASK -> AdventureMemoryPolicy.ASK
    WorldMemoryPolicy.AUTO_MAJOR -> AdventureMemoryPolicy.AUTO_MAJOR
}

fun AdventureMemoryPolicy.toUiPolicy(): WorldMemoryPolicy = when (this) {
    AdventureMemoryPolicy.NEVER -> WorldMemoryPolicy.NEVER
    AdventureMemoryPolicy.ASK -> WorldMemoryPolicy.ASK
    AdventureMemoryPolicy.AUTO_MAJOR -> WorldMemoryPolicy.AUTO_MAJOR
}

private fun projectEpisode(
    episode: TamaWorldEpisodeEntity,
    events: List<TamaWorldEventEntity>,
    labels: WorldJournalLabels
): WorldEpisodeUi? {
    val livingEvents = events.mapNotNull { event ->
        projectEvent(
            event = event,
            expectedWorldId = episode.worldId,
            expectedPetId = episode.petId,
            labels = labels
        )
    }
    val importance = listOf(
        importanceOf(episode.importance),
        livingEvents.maxOfOrNull { it.importance } ?: WorldEventImportance.TRACE
    ).maxBy { it.ordinal }
    val first = livingEvents.firstOrNull()
    return WorldEpisodeUi(
        id = episode.id,
        title = labels.episodeTitle(episode.title),
        timeRange = labels.timeRange(episode.startTime, episode.endTime),
        locationLabel = first?.locationLabel ?: labels.worldName,
        biome = first?.biome ?: WorldBiome.MEADOW,
        importance = importance,
        summary = episode.summary.ifBlank { labels.openEpisodeSummary },
        events = livingEvents,
        memoryEligible = livingEvents.any { it.memoryEligible },
        source = WorldEventSource.LIVING_WORLD
    )
}

private fun projectEvent(
    event: TamaWorldEventEntity,
    expectedWorldId: String,
    expectedPetId: String,
    labels: WorldJournalLabels
): WorldEventUi? {
    if (event.worldId != expectedWorldId || event.petId != expectedPetId) return null
    val facts = decodeFacts(event.payload)
    if (facts["source"]?.equals(WorldEventSource.LIVING_WORLD.name, ignoreCase = true) == false) {
        return null
    }
    val location = facts["locationId"]?.let(labels.locationName)
        ?: listOfNotNull(facts["x"], facts["y"]).takeIf { it.size == 2 }?.joinToString(", ")
        ?: labels.worldName
    val results = facts
        .filterKeys { it !in CONTEXT_KEYS }
        .toSortedMap()
        .mapNotNull { (key, value) ->
            labels.resultValue(key, value)?.let { WorldEventResultUi(labels.resultLabel(key), it) }
        }
    return WorldEventUi(
        id = event.id,
        timestamp = labels.time(event.timestamp),
        title = labels.eventTitle(event.eventType),
        detail = facts["details"].orEmpty().ifBlank { labels.eventTitle(event.eventType) },
        importance = importanceOf(event.importance),
        source = WorldEventSource.LIVING_WORLD,
        locationLabel = location,
        biome = biomeOf(facts["biome"]),
        actorName = event.actorId.takeIf { it.isNotBlank() }?.let(labels.actorName),
        results = results,
        memoryEligible = event.memoryEligible
    )
}

private fun importanceOf(value: String): WorldEventImportance = runCatching {
    WorldEventImportance.valueOf(value.uppercase())
}.getOrDefault(WorldEventImportance.ROUTINE)

private fun biomeOf(value: String?): WorldBiome = value?.let { raw ->
    WorldBiome.entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
} ?: WorldBiome.MEADOW

private fun decodeFacts(payload: String): Map<String, String> = runCatching {
    JSON.parseToJsonElement(payload).jsonObject.mapNotNull { (key, value) ->
        // Event facts are a scalar presentation contract. Ignore future nested
        // metadata fields individually so one non-scalar value cannot hide the
        // verified quantity/details that the journal can still render.
        (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
    }.toMap()
}.getOrDefault(emptyMap())

private fun decodeEvidence(value: String): List<String> = runCatching {
    JSON.decodeFromString<List<String>>(value)
}.getOrDefault(emptyList())

private fun humanize(value: String): String = value
    .lowercase()
    .split('_')
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

private val MEMORY_REVIEW_STATUSES = setOf("CLOSED", "PENDING", "APPROVED")
private val CONTEXT_KEYS = setOf("source", "x", "y", "biome", "goal", "action", "locationId", "details")
private val JSON = Json { ignoreUnknownKeys = true }
