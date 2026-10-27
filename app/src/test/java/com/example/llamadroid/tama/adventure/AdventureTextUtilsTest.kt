package com.example.llamadroid.tama.adventure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureTextUtilsTest {
    @Test
    fun cleanOutputKeepsDialogueQuotes() {
        val text = "\"Stay back,\" whispers the guard. \"The crypt is sealed.\" What do you do?"
        assertEquals(text, AdventureTextUtils.cleanOutput(text))
    }

    @Test
    fun cleanOutputUnwrapsJsonObjectOnly() {
        val json = "{\"story\": \"He said \\\"run\\\".\\nThen silence.\"}"
        assertEquals("He said \"run\".\nThen silence.", AdventureTextUtils.cleanOutput(json))
        val prose = "She wrote \"story\": \"a tale\" on the wall."
        assertEquals(prose, AdventureTextUtils.cleanOutput(prose))
    }

    @Test
    fun cleanOutputStripsSingleWrappingQuotesAndFences() {
        assertEquals("A lone lantern.", AdventureTextUtils.cleanOutput("```text\n\"A lone lantern.\"\n```"))
    }

    @Test
    fun parseSchematicKeepsSentencesAndBullets() {
        val response = """
            **TOTAL_STAGES:** 12
            STORY_THREAD: A pet enters the vault. It finds 3 keys, 2 of them cursed.
            Then it must choose.
            KEY_EVENTS:
            - The door opens - but at a price.
            2. A guardian offers a riddle.
              It never lies.
            • Collapse of the bridge
            POSSIBLE_ENDINGS:
            - Escape with the relic
            - Seal the vault forever
            TONE: Eerie, hopeful
            DIFFICULTY: **Hard**
        """.trimIndent()
        val s = AdventureTextUtils.parseSchematic(response)
        assertEquals(12, s.totalStages)
        assertEquals("A pet enters the vault. It finds 3 keys, 2 of them cursed. Then it must choose.", s.storyThread)
        assertEquals(
            listOf("The door opens - but at a price.", "A guardian offers a riddle. It never lies.", "Collapse of the bridge"),
            s.keyEvents
        )
        assertEquals(listOf("Escape with the relic", "Seal the vault forever"), s.possibleEndings)
        assertEquals("Eerie, hopeful", s.tone)
        assertEquals("hard", s.difficulty)
    }

    @Test
    fun parseSchematicIgnoresStrayNumbersAndClampsStageRange() {
        val noHeader = AdventureTextUtils.parseSchematic("You will face 3 stages of trouble and 40 monsters.")
        assertEquals(AdventureTextUtils.DEFAULT_TOTAL_STAGES, noHeader.totalStages)
        assertEquals(8, AdventureTextUtils.parseSchematic("TOTAL_STAGES: 3").totalStages)
        assertEquals(20, AdventureTextUtils.parseSchematic("TOTAL_STAGES: 30").totalStages)
        assertEquals("medium", noHeader.difficulty)
    }

    @Test
    fun summaryIsCappedKeepingNewestLines() {
        var summary = ""
        repeat(200) { summary = AdventureTextUtils.appendCappedSummary(summary, "Scene $it: something happened here.") }
        assertTrue(summary.length <= AdventureTextUtils.MAX_SUMMARY_CHARS)
        assertTrue(summary.endsWith("Scene 199: something happened here."))
        assertTrue(!summary.contains("Scene 0:"))
    }

    @Test
    fun fitHistoryDropsOldScenesWhenBudgetIsSmall() {
        val scenes = listOf("A".repeat(3000), "B".repeat(3000), "C".repeat(3000))
        val (summary, fitted) = AdventureTextUtils.fitHistory("old summary", scenes, 4000)
        assertEquals("old summary", summary)
        assertTrue(fitted.sumOf { it.length } <= 4000)
        assertTrue(fitted.last().startsWith("C"))
        assertTrue(fitted.none { it.startsWith("A") })
    }

    @Test
    fun historyBudgetShrinksWithSmallContext() {
        val small = AdventureTextUtils.historyBudgetChars(4096, 1100, 3000)
        val large = AdventureTextUtils.historyBudgetChars(16384, 1100, 3000)
        assertTrue(small in 200..6000)
        assertTrue(large > small)
    }

    @Test
    fun sessionSeedIsStableAndNonNegative() {
        val id = "3f1c2d4e-aaaa-bbbb-cccc-1234567890ab"
        assertEquals(AdventureTextUtils.sessionSeed(id), AdventureTextUtils.sessionSeed(id))
        assertTrue(AdventureTextUtils.sessionSeed(id) >= 0L)
    }
}
