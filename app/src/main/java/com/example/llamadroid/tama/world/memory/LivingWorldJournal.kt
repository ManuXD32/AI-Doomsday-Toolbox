package com.example.llamadroid.tama.world.memory

import androidx.room.withTransaction
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.EventImportance
import com.example.llamadroid.tama.world.core.GoalId
import com.example.llamadroid.tama.world.core.NeedsProjection
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.StructureType
import com.example.llamadroid.tama.world.core.WorldCoordinate
import com.example.llamadroid.tama.world.core.WorldEffectRequest
import com.example.llamadroid.tama.world.core.WorldGenerator
import com.example.llamadroid.tama.world.core.WorldNpcCatalog
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.persistence.TamaWorldEpisodeEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldEventEntity
import com.example.llamadroid.tama.world.persistence.WorldStateStore
import java.util.Calendar
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * The only writer of adventure events. Called after a living transition commits,
 * within the same Room transaction. Training has no dependency on this Android
 * module, and receives neither this repository nor the living database.
 */
class LivingWorldJournal(private val database: TamaDatabase) {
    private val dao = database.worldDao()
    private val json = Json { ignoreUnknownKeys = true }

    internal suspend fun record(
        before: WorldState,
        after: WorldState,
        effects: List<WorldEffectRequest.Event>,
        transitionId: String = after.tick.toString()
    ) = database.withTransaction {
        require(before.worldId == after.worldId && before.petId == after.petId)
        val meaningful = effects.filter { it.importance != EventImportance.TRACE }
        val returnedHome = before.actor.presence != PresenceMode.HOME && after.actor.presence == PresenceMode.HOME
        val leftHome = before.actor.presence == PresenceMode.HOME && after.actor.presence != PresenceMode.HOME
        if (meaningful.isEmpty()) {
            if (returnedHome || leftHome) latestOpenEpisode(after.petId)?.let { seal(it) }
            return@withTransaction
        }

        // A catch-up result can contain events from many logical ticks. Core
        // stamps each event with its own context; legacy app-created events
        // have no context and intentionally retain the old final-state
        // fallback, resolved against the addressed actor when that actor is
        // an NPC.
        val snapshots = meaningful.mapIndexed { index, event ->
            eventSnapshot(index, event, before, after)
        }
        val eventIds = snapshots.map { eventId(after.worldId, transitionId, it.originalIndex) }
        val existingIds = mutableSetOf<String>()
        for (chunk in eventIds.chunked(EXISTING_ID_QUERY_CHUNK)) {
            existingIds += dao.existingEventIds(chunk)
        }
        // A complete replay is a no-op. This is also what makes a compressed
        // catch-up idempotent when every retained event already exists.
        if (existingIds.size == eventIds.size) return@withTransaction

        val ordered = snapshots
            .filter { eventId(after.worldId, transitionId, it.originalIndex) !in existingIds }
            .sortedWith(compareBy<EventSnapshot> { it.timestamp }.thenBy { it.originalIndex })
        if (ordered.isEmpty()) return@withTransaction

        // Routine compression is deliberately stateful within this batch.
        // Without the in-memory list, a large catch-up can retain every copy
        // of a repeated action because none of its siblings is in Room yet.
        val routineFrom = ordered.minOf { it.timestamp } - ROUTINE_WINDOW_MS
        val routineTo = ordered.maxOf { it.timestamp }
        val recentRoutine = dao.routineEventsBetween(
            after.petId,
            routineFrom,
            routineTo,
            RECENT_ROUTINE_EVENT_LIMIT
        )
            .asSequence()
            .map { RoutineOccurrence(it.timestamp, routineFingerprint(it.actorId, it.eventType, decodeFacts(it.payload))) }
            .toMutableList()
        val accepted = buildList {
            ordered.forEach { snapshot ->
                val event = snapshot.event
                val fingerprint = routineFingerprint(event.actorId, event.eventType, event.payload)
                val repeatedRoutine = snapshot.homeBoundary == HomeBoundary.NONE &&
                    event.importance == EventImportance.ROUTINE && recentRoutine.any { old ->
                        snapshot.timestamp - old.timestamp in 0..ROUTINE_WINDOW_MS && old.fingerprint == fingerprint
                    }
                if (!repeatedRoutine) {
                    add(snapshot)
                    if (event.importance == EventImportance.ROUTINE) {
                        recentRoutine += RoutineOccurrence(snapshot.timestamp, fingerprint)
                    }
                }
            }
        }

        if (accepted.isEmpty()) {
            if (returnedHome || leftHome) latestOpenEpisode(after.petId)?.let { seal(it) }
            return@withTransaction
        }

        var episode = latestOpenEpisode(after.petId)
        if (leftHome) {
            episode?.let { seal(it) }
            episode = null
        }

        splitIntoBatches(accepted).forEach { batch ->
            var remaining = batch.entries
            while (remaining.isNotEmpty()) {
                val canContinue = episode?.let {
                    it.memoryStatus == "OPEN" &&
                        !batch.isMajor &&
                        !batch.startsAtHome &&
                        importanceOf(it.importance) != EventImportance.MAJOR &&
                        decodeEvidence(it.evidenceJson).size < MAX_EVIDENCE_IDS &&
                        canContinueEpisode(it, remaining.first().timestamp)
                } == true

                if (!canContinue) {
                    episode?.let { seal(it) }
                    episode = newEpisode(after.worldId, after.petId, transitionId, remaining.first())
                }

                val current = checkNotNull(episode)
                val existingEvidence = decodeEvidence(current.evidenceJson)
                val capacity = MAX_EVIDENCE_IDS - existingEvidence.size
                if (capacity <= 0) {
                    seal(current)
                    episode = null
                    continue
                }
                val part = remaining.take(capacity)
                val partIds = part.map { eventId(after.worldId, transitionId, it.originalIndex) }
                val entries = part.mapIndexed { partIndex, snapshot ->
                    val event = snapshot.event
                    TamaWorldEventEntity(
                        id = partIds[partIndex], worldId = after.worldId, petId = after.petId,
                        timestamp = snapshot.timestamp, importance = event.importance.name, actorId = event.actorId,
                        eventType = event.eventType,
                        payload = json.encodeToString(event.payload + snapshot.facts),
                        memoryEligible = event.importance >= EventImportance.MEMORABLE, episodeId = current.id
                    )
                }
                dao.saveEvents(entries)
                val evidence = (existingEvidence + partIds).distinct()
                val importance = maxOf(
                    importanceOf(current.importance),
                    part.maxOf { it.event.importance }
                )
                val updated = current.copy(
                    startTime = minOf(current.startTime, part.minOf { it.timestamp }),
                    endTime = maxOf(current.endTime, part.maxOf { it.timestamp }),
                    importance = importance.name,
                    evidenceJson = json.encodeToString(evidence)
                )
                dao.saveEpisodes(listOf(updated))
                episode = updated
                remaining = remaining.drop(part.size)

                // A major event is an explicit episode boundary and becomes a
                // pending memory candidate immediately. Cap splits also close
                // the full episode before the next bounded evidence segment.
                if (batch.isMajor || (batch.closesAtHome && remaining.isEmpty()) || remaining.isNotEmpty()) {
                    seal(updated)
                    episode = null
                }
            }
        }
        if (returnedHome) episode?.let { seal(it) }
    }

    private data class EventSnapshot(
        val originalIndex: Int,
        val event: WorldEffectRequest.Event,
        val timestamp: Long,
        val homeBoundary: HomeBoundary,
        val facts: Map<String, String>
    )

    private data class RoutineOccurrence(val timestamp: Long, val fingerprint: String)

    private enum class HomeBoundary { NONE, ENTER, EXIT }

    private data class EventBatch(
        val entries: List<EventSnapshot>,
        val isMajor: Boolean,
        val startsAtHome: Boolean = false,
        val closesAtHome: Boolean = false
    )

    private fun splitIntoBatches(ordered: List<EventSnapshot>): List<EventBatch> {
        val batches = mutableListOf<EventBatch>()
        var current = mutableListOf<EventSnapshot>()
        var previousTimestamp: Long? = null

        fun flush() {
            if (current.isNotEmpty()) {
                batches += EventBatch(
                    entries = current,
                    isMajor = current.any { it.event.importance == EventImportance.MAJOR },
                    startsAtHome = current.first().homeBoundary == HomeBoundary.EXIT,
                    closesAtHome = current.last().homeBoundary == HomeBoundary.ENTER
                )
                current = mutableListOf()
            }
            previousTimestamp = null
        }

        ordered.forEach { snapshot ->
            if (snapshot.event.importance == EventImportance.MAJOR) {
                flush()
                batches += EventBatch(listOf(snapshot), isMajor = true)
                return@forEach
            }
            if (snapshot.homeBoundary == HomeBoundary.EXIT && current.isNotEmpty()) {
                flush()
            }
            if (current.isNotEmpty() && previousTimestamp != null &&
                snapshot.timestamp - checkNotNull(previousTimestamp) > EPISODE_GAP_MS
            ) {
                flush()
            }
            if (current.size == MAX_EVIDENCE_IDS) flush()
            current += snapshot
            previousTimestamp = snapshot.timestamp
            if (snapshot.homeBoundary == HomeBoundary.ENTER) flush()
        }
        flush()
        return batches
    }

    private fun newEpisode(
        worldId: String,
        petId: String,
        transitionId: String,
        first: EventSnapshot
    ) = TamaWorldEpisodeEntity(
        id = "${worldId}:episode:$transitionId:${first.originalIndex}",
        worldId = worldId,
        petId = petId,
        startTime = first.timestamp,
        endTime = first.timestamp,
        title = first.event.eventType,
        summary = "",
        importance = first.event.importance.name,
        memoryStatus = "OPEN",
        evidenceJson = "[]"
    )

    private suspend fun latestOpenEpisode(petId: String): TamaWorldEpisodeEntity? =
        dao.episodes(petId, MAX_EPISODE_LOOKBACK)
            .asSequence()
            .filter { it.memoryStatus == "OPEN" }
            .maxWithOrNull(compareBy<TamaWorldEpisodeEntity> { it.startTime }.thenBy { it.endTime }.thenBy { it.id })

    private fun eventSnapshot(
        originalIndex: Int,
        event: WorldEffectRequest.Event,
        before: WorldState,
        after: WorldState
    ): EventSnapshot {
        val context = event.context
        val actor = context?.let {
            ActorFacts(it.actorCoordinate, it.beforeGoal, it.beforeAction, it.needs)
        } ?: legacyActorFacts(event.actorId, before, after)
        val coordinate = actor.coordinate
        return EventSnapshot(
            originalIndex = originalIndex,
            event = event,
            timestamp = context?.timestamp ?: after.lastSimulatedAt,
            homeBoundary = homeBoundary(event, before, after),
            facts = mapOf(
                "source" to "LIVING_WORLD",
                "x" to coordinate.x.toString(),
                "y" to coordinate.y.toString(),
                "biome" to (context?.biome?.name ?: WorldGenerator.tileAt(after, coordinate.x, coordinate.y).biome.name),
                "goal" to actor.goal.name,
                "action" to actor.action.name,
                "hungerBefore" to actor.needs.hunger.toString(),
                "hydrationBefore" to actor.needs.hydration.toString(),
                "energyBefore" to actor.needs.energy.toString()
            )
        )
    }

    private data class ActorFacts(
        val coordinate: WorldCoordinate,
        val goal: GoalId,
        val action: ActionId,
        val needs: NeedsProjection
    )

    private fun homeBoundary(
        event: WorldEffectRequest.Event,
        before: WorldState,
        after: WorldState
    ): HomeBoundary {
        if (event.actorId !in setOf(before.actor.actorId, after.actor.actorId, "pet")) return HomeBoundary.NONE
        val home = (before.structures + after.structures)
            .firstOrNull { it.type == StructureType.HOME }
        val structureId = event.payload["structureId"]
        val isHome = event.payload["structureType"] == StructureType.HOME.name ||
            (home != null && structureId == home.id)
        if (!isHome) return HomeBoundary.NONE
        return when (event.eventType.lowercase(Locale.ROOT)) {
            "entered_structure", "entered_structure_action" -> HomeBoundary.ENTER
            "left_structure", "left_structure_action" -> HomeBoundary.EXIT
            else -> HomeBoundary.NONE
        }
    }

    private fun legacyActorFacts(actorId: String, before: WorldState, after: WorldState): ActorFacts {
        val beforeNpc = before.npcs.firstOrNull { it.id == actorId }
        val afterNpc = after.npcs.firstOrNull { it.id == actorId }
        val fallbackNpc = afterNpc ?: beforeNpc
        if (fallbackNpc != null) {
            val beforeExecution = beforeNpc?.execution
            val afterExecution = afterNpc?.execution
            return ActorFacts(
                coordinate = afterExecution?.coordinate ?: fallbackNpc.coordinate,
                goal = beforeExecution?.goal ?: beforeNpc?.currentGoal ?: afterExecution?.goal ?: fallbackNpc.currentGoal,
                action = beforeExecution?.action ?: beforeNpc?.currentAction ?: afterExecution?.action ?: fallbackNpc.currentAction,
                needs = beforeExecution?.needs ?: beforeNpc?.needs ?: afterExecution?.needs ?: fallbackNpc.needs
            )
        }
        // Legacy pet events were created after the transition and therefore
        // retain their historical final-position fallback. Their needs and
        // intent remain the before-transition values for compatibility.
        return ActorFacts(after.actor.coordinate, before.actor.goal, before.actor.action, before.actor.needs)
    }

    private fun canContinueEpisode(
        episode: TamaWorldEpisodeEntity,
        eventStart: Long
    ): Boolean {
        if (eventStart < episode.startTime) return false
        // Internal batches already split adjacent local events across a gap.
        // Continuation therefore compares only the first event with the
        // existing episode. A 30-minute chain of one-minute events remains
        // one episode whether it arrives in one catch-up or thirty commits.
        return eventStart <= episode.endTime || eventStart - episode.endTime <= EPISODE_GAP_MS
    }

    private suspend fun seal(episode: TamaWorldEpisodeEntity) {
        if (episode.memoryStatus != "OPEN") return
        dao.saveEpisodes(listOf(episode.copy(memoryStatus =
            if (importanceOf(episode.importance) == EventImportance.MAJOR) "PENDING" else "CLOSED")))
    }

    /** Bounded facts for chat; no training table, telemetry file or trainer query is reachable here. */
    suspend fun contextFacts(petId: String, requestText: String = "", now: Long = System.currentTimeMillis()): String {
        val request = requestText.lowercase(Locale.ROOT)
        val startOfDay = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val from = when {
            request.contains("today") || request.contains("hoy") -> startOfDay
            request.contains("yesterday") || request.contains("ayer") -> startOfDay - 86_400_000L
            request.contains("week") || request.contains("semana") -> now - 7 * 86_400_000L
            requestText.isBlank() -> now - 7 * 86_400_000L
            else -> 0L
        }
        val to = if (request.contains("yesterday") || request.contains("ayer")) startOfDay - 1 else now
        val state = WorldStateStore(database).load(petId)
        val npcId = WorldNpcCatalog.definitions.firstOrNull {
            request.contains(it.name.lowercase(Locale.ROOT)) || request.contains(it.id.lowercase(Locale.ROOT))
        }?.id
        val events = dao.queryAdventures(petId, from.coerceAtLeast(0), to, npcId, npcId?.let { "%$it%" }, 60)
        val episodes = dao.episodes(petId, 20)
        val relationships = dao.relationships(petId)
        return buildString {
            appendLine("Verified living-world facts (simulation records; any prose is interpretation):")
            appendLine("History window: $from through $to. Up to 60 meaningful records, ordered by importance. Absence from this bounded query is not proof an event never happened.")
            state?.let { world ->
                appendLine("Known places: ${world.knownPlaces.joinToString { "${it.id} (${it.x},${it.y})" }}")
                val biomes = world.explored.asSequence().map { WorldGenerator.tileAt(world, it.x, it.y).biome }.distinct().toList()
                appendLine("Biomes actually explored: ${biomes.joinToString { it.name }}")
            }
            relationships.forEach { appendLine("Relationship ${it.npcId}: friendship=${it.friendship}, trust=${it.trust}, interactions=${it.sharedEventCount}, last=${it.lastInteraction}") }
            episodes.filter { it.memoryStatus == "SAVED" && it.endTime in from..to }.take(5).forEach {
                appendLine("Adventure memory [${it.id}]: ${it.summary.take(1000)} Evidence: ${it.evidenceJson.take(1000)}")
            }
            events.forEach { appendLine("[${it.id}] ${it.timestamp} ${it.actorId} ${it.eventType}: ${it.payload.take(700)}") }
        }.take(24_000)
    }

    companion object {
        private const val EPISODE_GAP_MS = 20 * 60_000L
        private const val ROUTINE_WINDOW_MS = 15 * 60_000L
        private const val MAX_EVIDENCE_IDS = 256
        private const val RECENT_ROUTINE_EVENT_LIMIT = 256
        private const val MAX_EPISODE_LOOKBACK = 250
        private const val EXISTING_ID_QUERY_CHUNK = 500
        private val CONTEXT_KEYS = setOf("source", "x", "y", "biome", "goal", "action", "hungerBefore", "hydrationBefore", "energyBefore")
        private fun eventId(worldId: String, transitionId: String, originalIndex: Int): String =
            "$worldId:$transitionId:$originalIndex"
        private fun routineFingerprint(actorId: String, eventType: String, payload: Map<String, String>): String =
            "$actorId:$eventType:${payload.filterKeys { it !in CONTEXT_KEYS }.toSortedMap()}"
        fun importanceOf(value: String): EventImportance =
            runCatching { EventImportance.valueOf(value) }.getOrDefault(EventImportance.ROUTINE)
        fun decodeFacts(value: String): Map<String, String> =
            runCatching { Json.decodeFromString<Map<String, String>>(value) }.getOrDefault(emptyMap())
        fun decodeEvidence(value: String): List<String> =
            runCatching { Json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
    }
}
