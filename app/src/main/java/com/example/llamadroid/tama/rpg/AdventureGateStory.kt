package com.example.llamadroid.tama.rpg

/**
 * Pure story model and progression for the Adventure Gate (Nexum canon, docs/adventure-story-bible.md).
 *
 * Text lives in the dialog workbook (app/src/main/tama-dialogs/pet_dialogs.xlsx) under the `lumo_*` and
 * `lumo_epilogue:*` key families; this object only knows the key shapes, which scenes are pending and which
 * Journal entries are unlocked. It has no Android dependency so the logic is unit-testable on the JVM.
 *
 * "Seen" scene ids are persisted by the UI layer (AdventureGateStoryStore); unlock state is derived from
 * [AdventureGateWorldProgress], so it needs no database column.
 */
enum class StorySpeaker { LUMO, ASTRA, NARRATOR }

enum class StorySceneKind { INTRO, OUTRO, EPILOGUE }

data class StoryBeat(val speaker: StorySpeaker, val text: String)

data class StoryScene(val kind: StorySceneKind, val worldId: String, val beats: List<StoryBeat>) {
    val id: String get() = AdventureGateStory.sceneId(kind, worldId)
}

/** A scene that should be shown now. */
data class PendingStoryScene(val kind: StorySceneKind, val worldId: String) {
    val id: String get() = AdventureGateStory.sceneId(kind, worldId)
}

/** What the Journal may show for one world, derived from progress. */
data class WorldStoryUnlocks(
    val worldId: String,
    val introUnlocked: Boolean,
    val midBossRevealed: Boolean,
    val finalBossRevealed: Boolean,
    val outroUnlocked: Boolean
) {
    /** Lore fragments unlocked in AdventureLore.worlds[].journalFragments: one per boss cleared (phases 7 and 15). */
    val fragmentCount: Int get() = (if (midBossRevealed) 1 else 0) + (if (finalBossRevealed) 1 else 0)
}

object AdventureGateStory {
    const val FINAL_WORLD_ID = "starfall_citadel"
    const val MID_BOSS_PHASE = 7
    const val FINAL_BOSS_PHASE = 15
    const val IDLE_QUIP_COUNT = 12
    const val DEFEAT_LINE_COUNT = 3
    const val MAX_SCENE_BEATS = 8
    private const val BASELINE_FLAG = "baselined"

    /** Workbook key prefix per world, in campaign order. */
    private val worldPrefixes: LinkedHashMap<String, String> = linkedMapOf(
        "sproutvale_gate" to "sproutvale",
        "ember_toyworks" to "ember",
        "bubbleglass_reef" to "bubbleglass",
        "clockwork_cloudway" to "clockwork",
        "moonmoss_library" to "moonmoss",
        "frostfall_toybox" to "frostfall",
        "starfall_citadel" to "starfall"
    )

    /** Speakers of lumo_epilogue:0..7, in order. The workbook rows record the same speaker in npc_id. */
    val epilogueSpeakers: List<StorySpeaker> = listOf(
        StorySpeaker.NARRATOR, StorySpeaker.NARRATOR, StorySpeaker.ASTRA, StorySpeaker.LUMO,
        StorySpeaker.ASTRA, StorySpeaker.NARRATOR, StorySpeaker.NARRATOR, StorySpeaker.LUMO
    )

    val worldIds: List<String> get() = worldPrefixes.keys.toList()

    fun prefix(worldId: String): String? = worldPrefixes[worldId]

    fun sceneId(kind: StorySceneKind, worldId: String): String = when (kind) {
        StorySceneKind.INTRO -> "intro:$worldId"
        StorySceneKind.OUTRO -> "outro:$worldId"
        StorySceneKind.EPILOGUE -> "epilogue"
    }

    // ---- Workbook keys ----

    fun introKey(worldId: String, index: Int): String? = prefix(worldId)?.let { "lumo_intro:$it:$index" }
    fun outroKey(worldId: String, index: Int): String? = prefix(worldId)?.let { "lumo_outro:$it:$index" }
    fun epilogueKey(index: Int): String = "lumo_epilogue:$index"
    const val POSTGAME_KEY = "lumo_postgame:0"
    fun idleKey(index: Int): String = "lumo_idle:${index.mod(IDLE_QUIP_COUNT)}"
    fun defeatKey(index: Int): String = "lumo_defeat:${index.mod(DEFEAT_LINE_COUNT)}"

    private fun bossPhaseSuffix(phase: Int): String? = when (phase) {
        MID_BOSS_PHASE -> "07"
        FINAL_BOSS_PHASE -> "15"
        else -> null
    }

    /** Lumo's line before a boss fight (phases 7 and 15), or null for ordinary phases. */
    fun beforeBossKey(worldId: String, phase: Int): String? {
        val suffix = bossPhaseSuffix(phase) ?: return null
        return prefix(worldId)?.let { "lumo_boss:$it:$suffix" }
    }

    /** Lumo's line after a boss victory (phases 7 and 15), or null for ordinary phases. */
    fun afterVictoryKey(worldId: String, phase: Int): String? {
        val suffix = bossPhaseSuffix(phase) ?: return null
        return prefix(worldId)?.let { "lumo_victory:$it:$suffix" }
    }

    /** Deterministic idle quip index for the pre-battle bubble of an ordinary phase. */
    fun battleQuipIndex(worldId: String, phase: Int): Int {
        val worldIndex = worldIds.indexOf(worldId).coerceAtLeast(0)
        return (worldIndex * FINAL_BOSS_PHASE + phase).mod(IDLE_QUIP_COUNT)
    }

    /** The pre-battle key: the boss line on phases 7/15, otherwise a deterministic idle quip. */
    fun beforeBattleKey(worldId: String, phase: Int): String =
        beforeBossKey(worldId, phase) ?: idleKey(battleQuipIndex(worldId, phase))

    // ---- Scenes ----

    /**
     * Collects consecutive non-blank texts for [keyAt] starting at index 0 (so the workbook decides how many beats
     * a scene has). Falls back to [fallback] (for example AdventureLore) when the workbook has none.
     */
    fun resolveBeats(
        kind: StorySceneKind,
        worldId: String,
        lookup: (String) -> String?,
        fallback: List<String>
    ): List<StoryBeat> {
        val beats = ArrayList<StoryBeat>()
        for (index in 0 until MAX_SCENE_BEATS) {
            val text = when (kind) {
                StorySceneKind.INTRO -> introKey(worldId, index)
                StorySceneKind.OUTRO -> outroKey(worldId, index)
                StorySceneKind.EPILOGUE -> epilogueKey(index)
            }?.let(lookup)?.trim().orEmpty()
            if (text.isEmpty()) break
            val speaker = if (kind == StorySceneKind.EPILOGUE) {
                epilogueSpeakers.getOrElse(index) { StorySpeaker.NARRATOR }
            } else {
                StorySpeaker.LUMO
            }
            beats += StoryBeat(speaker, text)
        }
        if (beats.isEmpty()) {
            fallback.filter { it.isNotBlank() }.forEach { beats += StoryBeat(StorySpeaker.NARRATOR, it.trim()) }
        }
        return beats
    }

    // ---- Unlocks (derived from progress) ----

    private fun midBossCleared(progress: AdventureGateWorldProgress?): Boolean =
        progress != null && (progress.midBossCleared || progress.highestClearedPhase >= MID_BOSS_PHASE)

    private fun finalBossCleared(progress: AdventureGateWorldProgress?): Boolean =
        progress != null && (progress.finalBossCleared || progress.highestClearedPhase >= FINAL_BOSS_PHASE)

    fun worldUnlocks(worldId: String, progress: AdventureGateWorldProgress?, seen: Set<String>): WorldStoryUnlocks {
        val midDone = midBossCleared(progress)
        val finalDone = finalBossCleared(progress)
        val started = (progress?.highestClearedPhase ?: 0) >= 1
        return WorldStoryUnlocks(
            worldId = worldId,
            introUnlocked = started || midDone || finalDone || sceneId(StorySceneKind.INTRO, worldId) in seen,
            midBossRevealed = midDone,
            finalBossRevealed = finalDone,
            outroUnlocked = finalDone
        )
    }

    /** The epilogue unlocks when the Regent (Starfall phase 15) has been beaten. */
    fun epilogueUnlocked(progressRows: List<AdventureGateWorldProgress>): Boolean =
        finalBossCleared(progressRows.firstOrNull { it.worldId == FINAL_WORLD_ID })

    // ---- Pending scenes ----

    /**
     * Which scene should open now, or null. Outros (campaign order) come first, then the epilogue, then the intro
     * of the world the player is looking at. Everything already in [seen] is skipped.
     */
    fun nextPendingScene(
        selectedWorldId: String?,
        progressRows: List<AdventureGateWorldProgress>,
        seen: Set<String>
    ): PendingStoryScene? {
        val byWorld = progressRows.associateBy { it.worldId }
        for (worldId in worldPrefixes.keys) {
            if (finalBossCleared(byWorld[worldId]) && sceneId(StorySceneKind.OUTRO, worldId) !in seen) {
                return PendingStoryScene(StorySceneKind.OUTRO, worldId)
            }
        }
        if (epilogueUnlocked(progressRows) && sceneId(StorySceneKind.EPILOGUE, FINAL_WORLD_ID) !in seen) {
            return PendingStoryScene(StorySceneKind.EPILOGUE, FINAL_WORLD_ID)
        }
        if (selectedWorldId != null && selectedWorldId in worldPrefixes &&
            sceneId(StorySceneKind.INTRO, selectedWorldId) !in seen
        ) {
            return PendingStoryScene(StorySceneKind.INTRO, selectedWorldId)
        }
        return null
    }

    // ---- Seen-state helpers ----

    /**
     * Players who already progressed before the story shipped must not get every scene replayed. The first time a
     * pet's seen set is read, scenes implied by existing progress are marked as seen (the Journal still lists them).
     */
    fun baselineSeen(progressRows: List<AdventureGateWorldProgress>): Set<String> {
        val seen = LinkedHashSet<String>()
        for (row in progressRows) {
            if (row.worldId !in worldPrefixes) continue
            if (row.highestClearedPhase >= 1 || row.midBossCleared || row.finalBossCleared) {
                seen += sceneId(StorySceneKind.INTRO, row.worldId)
            }
            if (finalBossCleared(row)) seen += sceneId(StorySceneKind.OUTRO, row.worldId)
        }
        if (epilogueUnlocked(progressRows)) seen += sceneId(StorySceneKind.EPILOGUE, FINAL_WORLD_ID)
        return seen
    }

    fun isBaselined(stored: Set<String>): Boolean = BASELINE_FLAG in stored
    fun markBaselined(stored: Set<String>): Set<String> = stored + BASELINE_FLAG
    fun visibleSeen(stored: Set<String>): Set<String> = stored - BASELINE_FLAG
}
