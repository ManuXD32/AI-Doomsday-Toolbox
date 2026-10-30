package com.example.llamadroid.tama.adventure

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureRunTest {
    private val hero = AdventureHero(
        name = "Mochi",
        species = "fox",
        stage = "child",
        personality = "shy",
        personalityNote = "Quiet and reserved",
        mood = "happy",
        condition = listOf("tired"),
        gateLevel = 7,
        gateGear = listOf("iron sword")
    )

    private val schematic = StorySchematic(
        totalStages = 10,
        storyThread = "A lullaby is lost.",
        keyEvents = listOf("Find the music box"),
        possibleEndings = listOf("Warm"),
        tone = "cozy",
        difficulty = "medium"
    )

    // ---- Scene parser ----

    @Test
    fun parsesWellFormedScene() {
        val parsed = AdventureSceneParser.parse(
            """
            SCENE: The fox steps into the crypt.
            It smells of candle wax.
            CHOICES:
            1) Open the music box
            2) Ask Lumo to glow
            3) Hum a lullaby
            STATE: hope=75, items=Moon Key; Lantern, flags=met_ghost
            """.trimIndent()
        )
        assertTrue(parsed.structured)
        assertEquals("The fox steps into the crypt.\nIt smells of candle wax.", parsed.text)
        assertEquals(listOf("Open the music box", "Ask Lumo to glow", "Hum a lullaby"), parsed.choices)
        val state = parsed.state!!
        assertEquals(75, state.hope)
        assertFalse(state.hopeIsDelta)
        assertEquals(listOf("Moon Key", "Lantern"), state.items)
        assertEquals(listOf("met_ghost"), state.flags)
    }

    @Test
    fun parsesSpanishLabelsAndSignedHope() {
        val parsed = AdventureSceneParser.parse(
            "**ESCENA:** Hola, valiente.\n**OPCIONES:**\nA) Uno\nB) Dos\nESTADO: esperanza=+5, objetos=ninguno, banderas=-"
        )
        assertEquals("Hola, valiente.", parsed.text)
        assertEquals(listOf("Uno", "Dos"), parsed.choices)
        val state = parsed.state!!
        assertEquals(5, state.hope)
        assertTrue(state.hopeIsDelta)
        assertEquals(emptyList<String>(), state.items)
        assertEquals(emptyList<String>(), state.flags)
    }

    @Test
    fun partialOutputFallsBackGracefully() {
        val sceneOnly = AdventureSceneParser.parse("SCENE: Only a scene.")
        assertTrue(sceneOnly.structured)
        assertEquals("Only a scene.", sceneOnly.text)
        assertTrue(sceneOnly.choices.isEmpty())
        assertNull(sceneOnly.state)

        val plain = AdventureSceneParser.parse("Just prose without any labels.")
        assertFalse(plain.structured)
        assertEquals("Just prose without any labels.", plain.text)
        assertTrue(plain.choices.isEmpty())

        val noScene = AdventureSceneParser.parse("It was quiet.\nCHOICES:\n- Wait\n- Knock")
        assertEquals("It was quiet.", noScene.text)
        assertEquals(listOf("Wait", "Knock"), noScene.choices)
    }

    @Test
    fun garbageNeverThrows() {
        assertEquals("", AdventureSceneParser.parse("   ").text)
        assertEquals("???", AdventureSceneParser.parse("???").text)
        val badState = AdventureSceneParser.parse("STATE: banana")
        assertNull(badState.state)
        assertNull(AdventureSceneParser.parseState("hope=abc"))
        assertEquals(4, AdventureSceneParser.parse("SCENE: x\nCHOICES:\n1) alpha\n2) bravo\n3) charlie\n4) delta\n5) echo\n6) foxtrot").choices.size)
    }

    // ---- Run state ----

    @Test
    fun runStateClampsAndDeduplicates() {
        val start = AdventureRunState()
        assertEquals(70, start.hope)
        assertEquals(100, start.applying(SceneStateUpdate(hope = 150)).hope)
        assertEquals(0, start.applying(SceneStateUpdate(hope = -200, hopeIsDelta = true)).hope)
        assertTrue(start.applying(SceneStateUpdate(hope = -200, hopeIsDelta = true)).isDepleted)
        assertEquals(75, start.applying(SceneStateUpdate(hope = 5, hopeIsDelta = true)).hope)
        assertEquals(start, start.applying(null))

        val items = (1..20).map { "item $it" } + "ITEM 1"
        val updated = start.applying(SceneStateUpdate(items = items, flags = listOf(" a ", "A", "")))
        assertEquals(AdventureRunState.MAX_ITEMS, updated.items.size)
        assertEquals(listOf("a"), updated.flags)
        // Unreported parts are kept.
        val kept = updated.applying(SceneStateUpdate(hope = 10))
        assertEquals(updated.items, kept.items)
    }

    @Test
    fun runStateAndChronicleSurviveSessionJson() {
        val json = Json { ignoreUnknownKeys = true }
        val page = DreamChroniclePage(
            title = "T", summary = "S", outcome = AdventureOutcome.VICTORY, finalHope = 80, difficulty = "hard",
            coins = 80, grants = listOf(ChronicleGrant("gate_item", "pearl"))
        )
        val withState = schematic.copy(runState = AdventureRunState(40, listOf("Key"), listOf("f")), chronicle = page)
        val decoded = json.decodeFromString<StorySchematic>(json.encodeToString(withState))
        assertEquals(withState, decoded)
        // Sessions saved before this feature have no runState: defaults apply.
        val legacy = json.decodeFromString<StorySchematic>(
            """{"totalStages":9,"storyThread":"x","keyEvents":[],"possibleEndings":[],"tone":"t","difficulty":"easy"}"""
        )
        assertEquals(AdventureRunState.START_HOPE, legacy.runState.hope)
        assertNull(legacy.chronicle)
    }

    // ---- Prompts ----

    @Test
    fun systemPromptCarriesLoreLumoAndLanguage() {
        val es = AdventurePrompts.systemPrompt("Spanish", DungeonType.SHADOW_CRYPTS)
        assertTrue(es.contains("Lumo"))
        assertTrue(es.contains("Bajo la habitación"))
        assertTrue(es.contains("Always write the story in Spanish"))
        val en = AdventurePrompts.systemPrompt("English", DungeonType.CHAOS_REALM)
        assertTrue(en.contains("Under every sleeping pet's room"))
        assertTrue(en.contains("Chaos Realm"))
    }

    @Test
    fun scenePromptsIncludePetStateAndFormat() {
        val header = AdventurePrompts.sceneHeader(
            language = "Spanish",
            dungeon = DungeonType.ARCANE_DEPTHS,
            schematic = schematic,
            hero = hero,
            state = AdventureRunState(),
            dungeonLabel = "Profundidades Luminosas del Mar Dormido"
        )
        assertTrue(header.contains("Name: Mochi"))
        assertTrue(header.contains("Species: fox"))
        assertTrue(header.contains("Personality: shy (quiet and reserved)"))
        assertTrue(header.contains("Condition: tired"))
        assertTrue(header.contains("Adventure Gate level: 7; carries iron sword"))
        assertTrue(header.contains("RUN STATE: hope=70/100; items: none; flags: none"))
        assertTrue(header.contains("LANGUAGE: Spanish"))

        val footer = AdventurePrompts.sceneFooter("Spanish", 3, 10, AdventurePrompts.SceneKind.NORMAL)
        assertTrue(footer.contains("SCENE:"))
        assertTrue(footer.contains("1) <short choice>"))
        assertTrue(footer.contains("STATE: hope="))
        assertTrue(footer.contains("in Spanish"))

        val epilogue = AdventurePrompts.sceneFooter("English", 10, 10, AdventurePrompts.SceneKind.EPILOGUE)
        assertFalse(epilogue.contains("1) <short choice>"))
        assertTrue(epilogue.contains("EPILOGUE"))
        assertTrue(AdventurePrompts.sceneFooter("English", 4, 10, AdventurePrompts.SceneKind.CARRIED_BACK).contains("Nobody dies"))
    }

    @Test
    fun plannerPromptKeepsParserFieldsAndHero() {
        val prompt = AdventurePrompts.plannerPrompt("Spanish", DungeonType.FROSTBOUND_HALLS, hero)
        listOf("TOTAL_STAGES", "STORY_THREAD", "KEY_EVENTS", "POSSIBLE_ENDINGS", "TONE", "DIFFICULTY", "Mochi", "Lumo")
            .forEach { assertTrue(it, prompt.contains(it)) }
        assertTrue(prompt.contains("Salones del Invierno Inacabado"))
        assertTrue(prompt.endsWith("Write everything else in Spanish."))
    }

    @Test
    fun languageResolutionFollowsAppLocaleOnlyForAuto() {
        assertEquals("Spanish", AdventurePrompts.resolveLanguage("Auto", "es"))
        assertEquals("English", AdventurePrompts.resolveLanguage("Auto", "en"))
        assertEquals("English", AdventurePrompts.resolveLanguage("auto", "fr"))
        assertEquals("Spanish", AdventurePrompts.resolveLanguage("", "es-ES"))
        assertEquals("French", AdventurePrompts.resolveLanguage("French", "es"))
        assertEquals("English", AdventurePrompts.resolveLanguage("English", "es"))
        assertEquals("es", AdventurePrompts.loreCode("Spanish"))
        assertEquals("en", AdventurePrompts.loreCode("German"))
        assertEquals(AdventurePrompts.AUTO_LANGUAGE, AdventurePrompts.LANGUAGE_OPTIONS.first())
    }

    @Test
    fun customSystemPromptOverridesButLegacyAndBlankDoNot() {
        val fallback = "default text"
        assertTrue(AdventurePrompts.isDefaultSystemPrompt("", fallback))
        assertTrue(AdventurePrompts.isDefaultSystemPrompt(" default text ", fallback))
        assertTrue(AdventurePrompts.isDefaultSystemPrompt("You are a master storyteller for dark fantasy text adventures.", fallback))
        assertFalse(AdventurePrompts.isDefaultSystemPrompt("You are a pirate.", fallback))
    }

    @Test
    fun chroniclePageParsingAndFallback() {
        val (title, summary) = AdventurePrompts.parseChronicle(
            "TITLE: **The Lantern Lullaby**\nSUMMARY: Mochi and Lumo hummed.\nIt was warm.", "ft", "fs"
        )
        assertEquals("The Lantern Lullaby", title)
        assertEquals("Mochi and Lumo hummed.\nIt was warm.", summary)
        assertEquals("ft" to "fs", AdventurePrompts.parseChronicle("nonsense", "ft", "fs"))
        val fallback = AdventurePrompts.fallbackChronicle("Spanish", hero, "El Bostezo Verde", AdventureOutcome.CARRIED_BACK)
        assertNotNull(fallback.first)
        assertTrue(fallback.second.contains("Mochi"))
    }

    @Test
    fun imagePromptUsesStylePrefixAndStaticNegative() {
        val prompt = AdventurePrompts.imagePrompt(DungeonType.VERDANT_MAW, "a mossy tunnel")
        assertTrue(prompt.startsWith(AdventurePrompts.IMAGE_STYLE_PREFIX))
        assertTrue(prompt.endsWith("a mossy tunnel"))
        assertFalse(prompt.contains("Tone:"))
        assertTrue(AdventurePrompts.IMAGE_NEGATIVE_PROMPT.contains("watermark"))
    }

    // ---- Endings and rewards ----

    @Test
    fun outcomeNeedsFinalSceneAndHope() {
        assertEquals(AdventureOutcome.VICTORY, AdventureRewards.outcomeFor(1, reachedFinalScene = true))
        assertEquals(AdventureOutcome.CARRIED_BACK, AdventureRewards.outcomeFor(0, reachedFinalScene = true))
        assertEquals(AdventureOutcome.CARRIED_BACK, AdventureRewards.outcomeFor(60, reachedFinalScene = false))
    }

    @Test
    fun rewardsScaleWithDifficultyAndHope() {
        val medium = AdventureRewards.compute("medium", 100, AdventureOutcome.VICTORY, DungeonType.ARCANE_DEPTHS)
        assertEquals(60, medium.coins)
        assertEquals(9, medium.xp)
        assertEquals(3, medium.bond)
        assertEquals(listOf("gate_item", "lantern_spark"), medium.grants.map { it.rewardId })

        val hard = AdventureRewards.compute("hard", 50, AdventureOutcome.VICTORY, DungeonType.SHADOW_CRYPTS)
        assertEquals(60, hard.coins)
        assertEquals(9, hard.xp)
        assertEquals(2, hard.bond)
        assertEquals(listOf("memory_fragment"), hard.grants.map { it.rewardId })

        val easy = AdventureRewards.compute("easy", 100, AdventureOutcome.VICTORY, DungeonType.CHAOS_REALM)
        assertEquals(40, easy.coins)
        assertEquals(listOf("lantern_spark"), easy.grants.map { it.rewardId })

        assertEquals(AdventureRewards.Computation.NONE, AdventureRewards.compute("hard", 90, AdventureOutcome.CARRIED_BACK, DungeonType.VOID_SANCTUM))
        assertTrue(medium.coins > AdventureRewards.compute("medium", 20, AdventureOutcome.VICTORY, DungeonType.ARCANE_DEPTHS).coins)
    }
}
