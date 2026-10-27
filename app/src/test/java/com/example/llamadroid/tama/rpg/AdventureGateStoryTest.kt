package com.example.llamadroid.tama.rpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureGateStoryTest {
    private fun progress(worldId: String, highest: Int = 0, mid: Boolean = false, done: Boolean = false) =
        AdventureGateWorldProgress(
            petId = "pet",
            worldId = worldId,
            highestClearedPhase = highest,
            midBossCleared = mid,
            finalBossCleared = done
        )

    @Test
    fun `workbook keys follow the documented families`() {
        assertEquals("lumo_intro:ember:2", AdventureGateStory.introKey("ember_toyworks", 2))
        assertEquals("lumo_outro:starfall:0", AdventureGateStory.outroKey("starfall_citadel", 0))
        assertEquals("lumo_boss:moonmoss:07", AdventureGateStory.beforeBossKey("moonmoss_library", 7))
        assertEquals("lumo_boss:moonmoss:15", AdventureGateStory.beforeBossKey("moonmoss_library", 15))
        assertNull(AdventureGateStory.beforeBossKey("moonmoss_library", 8))
        assertEquals("lumo_victory:frostfall:15", AdventureGateStory.afterVictoryKey("frostfall_toybox", 15))
        assertNull(AdventureGateStory.afterVictoryKey("frostfall_toybox", 3))
        assertNull(AdventureGateStory.introKey("night_arena", 0))
        assertEquals("lumo_idle:3", AdventureGateStory.idleKey(15))
        assertEquals("lumo_defeat:1", AdventureGateStory.defeatKey(4))
    }

    @Test
    fun `ordinary phases get a deterministic idle quip and boss phases get the boss line`() {
        val key = AdventureGateStory.beforeBattleKey("sproutvale_gate", 3)
        assertEquals(key, AdventureGateStory.beforeBattleKey("sproutvale_gate", 3))
        assertTrue(key.startsWith("lumo_idle:"))
        assertEquals("lumo_boss:sproutvale:07", AdventureGateStory.beforeBattleKey("sproutvale_gate", 7))
        assertEquals("lumo_boss:starfall:15", AdventureGateStory.beforeBattleKey("starfall_citadel", 15))
    }

    @Test
    fun `unlocks are derived from boss progress`() {
        val none = AdventureGateStory.worldUnlocks("ember_toyworks", null, emptySet())
        assertFalse(none.introUnlocked)
        assertEquals(0, none.fragmentCount)
        assertFalse(none.outroUnlocked)

        val started = AdventureGateStory.worldUnlocks("ember_toyworks", progress("ember_toyworks", highest = 3), emptySet())
        assertTrue(started.introUnlocked)
        assertFalse(started.midBossRevealed)
        assertEquals(0, started.fragmentCount)

        val mid = AdventureGateStory.worldUnlocks("ember_toyworks", progress("ember_toyworks", highest = 7, mid = true), emptySet())
        assertTrue(mid.midBossRevealed)
        assertFalse(mid.finalBossRevealed)
        assertEquals(1, mid.fragmentCount)

        val done = AdventureGateStory.worldUnlocks("ember_toyworks", progress("ember_toyworks", highest = 15, mid = true, done = true), emptySet())
        assertTrue(done.finalBossRevealed)
        assertTrue(done.outroUnlocked)
        assertEquals(2, done.fragmentCount)

        // A flag without the phase counter (or the reverse) still counts.
        assertTrue(AdventureGateStory.worldUnlocks("x", progress("x", mid = true), emptySet()).midBossRevealed)
        assertTrue(AdventureGateStory.worldUnlocks("x", progress("x", highest = 15), emptySet()).outroUnlocked)
        // Having watched the intro unlocks its page even before any phase is cleared.
        assertTrue(AdventureGateStory.worldUnlocks("x", null, setOf("intro:x")).introUnlocked)
    }

    @Test
    fun `epilogue unlocks only when the Regent is beaten`() {
        assertFalse(AdventureGateStory.epilogueUnlocked(listOf(progress("starfall_citadel", highest = 14))))
        assertFalse(AdventureGateStory.epilogueUnlocked(listOf(progress("frostfall_toybox", done = true))))
        assertTrue(AdventureGateStory.epilogueUnlocked(listOf(progress("starfall_citadel", done = true))))
        assertTrue(AdventureGateStory.epilogueUnlocked(listOf(progress("starfall_citadel", highest = 15))))
    }

    @Test
    fun `pending scenes follow outro then epilogue then intro and respect seen`() {
        val rows = listOf(
            progress("sproutvale_gate", highest = 15, mid = true, done = true),
            progress("ember_toyworks", highest = 2)
        )
        assertEquals(
            PendingStoryScene(StorySceneKind.OUTRO, "sproutvale_gate"),
            AdventureGateStory.nextPendingScene("ember_toyworks", rows, emptySet())
        )
        assertEquals(
            PendingStoryScene(StorySceneKind.INTRO, "ember_toyworks"),
            AdventureGateStory.nextPendingScene("ember_toyworks", rows, setOf("outro:sproutvale_gate"))
        )
        assertNull(AdventureGateStory.nextPendingScene("ember_toyworks", rows, setOf("outro:sproutvale_gate", "intro:ember_toyworks")))
        assertNull(AdventureGateStory.nextPendingScene("night_arena", rows, setOf("outro:sproutvale_gate")))

        val allDone = AdventureGateStory.worldIds.map { progress(it, highest = 15, mid = true, done = true) }
        val outros = AdventureGateStory.worldIds.map { "outro:$it" }.toSet()
        assertEquals(
            PendingStoryScene(StorySceneKind.EPILOGUE, "starfall_citadel"),
            AdventureGateStory.nextPendingScene("starfall_citadel", allDone, outros)
        )
        assertNull(AdventureGateStory.nextPendingScene("starfall_citadel", allDone, outros + "epilogue" + "intro:starfall_citadel"))
    }

    @Test
    fun `baseline marks existing progress as seen so old saves do not replay scenes`() {
        val rows = listOf(
            progress("sproutvale_gate", highest = 15, mid = true, done = true),
            progress("ember_toyworks", highest = 4),
            progress("bubbleglass_reef")
        )
        val seen = AdventureGateStory.baselineSeen(rows)
        assertEquals(setOf("intro:sproutvale_gate", "outro:sproutvale_gate", "intro:ember_toyworks"), seen)
        assertNull(AdventureGateStory.nextPendingScene("ember_toyworks", rows, seen))
        // The next world has not been entered yet, so its intro still plays on first selection.
        assertEquals(
            PendingStoryScene(StorySceneKind.INTRO, "bubbleglass_reef"),
            AdventureGateStory.nextPendingScene("bubbleglass_reef", rows, seen)
        )
        val stored = AdventureGateStory.markBaselined(seen)
        assertTrue(AdventureGateStory.isBaselined(stored))
        assertEquals(seen, AdventureGateStory.visibleSeen(stored))
    }

    @Test
    fun `scene beats come from consecutive workbook keys and fall back to lore`() {
        val texts = mapOf(
            "lumo_intro:ember:0" to "One",
            "lumo_intro:ember:1" to " Two ",
            "lumo_intro:ember:3" to "Skipped because key 2 is missing"
        )
        val beats = AdventureGateStory.resolveBeats(StorySceneKind.INTRO, "ember_toyworks", { texts[it] }, listOf("fallback"))
        assertEquals(listOf("One", "Two"), beats.map { it.text })
        assertTrue(beats.all { it.speaker == StorySpeaker.LUMO })

        val fallback = AdventureGateStory.resolveBeats(StorySceneKind.OUTRO, "ember_toyworks", { null }, listOf("lore beat", " "))
        assertEquals(listOf("lore beat"), fallback.map { it.text })
        assertEquals(StorySpeaker.NARRATOR, fallback.single().speaker)

        val epilogue = AdventureGateStory.resolveBeats(
            StorySceneKind.EPILOGUE, "starfall_citadel",
            { key -> key.takeIf { it.startsWith("lumo_epilogue:") }?.let { "t" } }, emptyList()
        )
        assertEquals(AdventureGateStory.MAX_SCENE_BEATS, epilogue.size)
        assertEquals(StorySpeaker.ASTRA, epilogue[2].speaker)
        assertEquals(StorySpeaker.LUMO, epilogue[3].speaker)
    }
}
