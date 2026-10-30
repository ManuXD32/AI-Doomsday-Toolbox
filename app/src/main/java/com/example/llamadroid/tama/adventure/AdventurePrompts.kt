package com.example.llamadroid.tama.adventure

import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.Personality
import com.example.llamadroid.tama.db.AdventureGateProfileEntity
import com.example.llamadroid.tama.db.TamaPetEntity

/** The player's real pet as the protagonist of an LLM dream. All strings are English labels for the model. */
data class AdventureHero(
    val name: String,
    val species: String,
    val stage: String,
    val personality: String,
    val personalityNote: String,
    val mood: String,
    val condition: List<String> = emptyList(),
    val gateLevel: Int? = null,
    val gateGear: List<String> = emptyList()
)

/** Prompt builders for the Dream Chronicle adventures. Pure (no Android), so they are unit tested. */
object AdventurePrompts {
    /** Stored language setting meaning "follow the app language". */
    const val AUTO_LANGUAGE = SettingsRepository.ADVENTURE_LANGUAGE_AUTO

    /** Language options for the settings dropdown, Auto first. Explicit legacy choices are kept as-is. */
    val LANGUAGE_OPTIONS = listOf(
        AUTO_LANGUAGE, "English", "Spanish", "French", "German", "Italian",
        "Portuguese", "Russian", "Chinese", "Japanese", "Korean"
    )

    /** A stored system prompt containing this text is the pre-rewrite default and is replaced by the new one. */
    const val LEGACY_SYSTEM_PROMPT_MARKER = "master storyteller for dark fantasy text adventures"

    const val IMAGE_STYLE_PREFIX = "retro handheld game background illustration, charming 1990s Tamagotchi-style, " +
        "flat colorful 2d art, bold outlines, cute toy-like shapes, simple readable composition, " +
        "clear open center space, no text, no UI"
    const val IMAGE_NEGATIVE_PROMPT =
        "text, ui, watermark, realistic photo, blurry, cluttered foreground, extra people, gore, scary, horror"

    private const val MAX_CONDITION_NOTES = 3

    fun resolveLanguage(setting: String, appLanguageCode: String): String {
        val value = setting.trim()
        if (value.isEmpty() || value.startsWith("auto", ignoreCase = true)) {
            return if (appLanguageCode.trim().lowercase().startsWith("es")) "Spanish" else "English"
        }
        return value
    }

    /** "es" for Spanish, "en" otherwise: the key the bilingual AdventureLore content understands. */
    fun loreCode(language: String): String {
        val value = language.trim().lowercase()
        return if (value.startsWith("es") || value == "spanish" || value == "castellano") "es" else "en"
    }

    /** True when [stored] should be replaced by the generated default prompt (blank, legacy or the new fallback). */
    fun isDefaultSystemPrompt(stored: String, fallbackDefault: String): Boolean {
        val value = stored.trim()
        return value.isEmpty() || value == fallbackDefault.trim() || value.contains(LEGACY_SYSTEM_PROMPT_MARKER)
    }

    fun heroFrom(pet: TamaPetEntity, gate: AdventureGateProfileEntity?): AdventureHero {
        val personalityEnum = Personality.entries.firstOrNull { it.name.equals(pet.personality, ignoreCase = true) }
        val notes = buildList {
            if (pet.happiness < 30f) add("feeling sad")
            if (pet.energy < 30f) add("tired")
            if (pet.health < 40f) add("a little unwell")
            if (pet.ownerBondLevel >= 75f) add("deeply bonded to its owner")
            if (pet.educationLevel >= 50f) add("well studied")
        }.take(MAX_CONDITION_NOTES)
        val gear = listOfNotNull(
            gate?.equippedWeaponId, gate?.equippedShieldId, gate?.equippedRingId, gate?.equippedRelicId
        ).filter { it.isNotBlank() }.map { it.replace('_', ' ').replace('-', ' ') }
        return AdventureHero(
            name = pet.name,
            species = PetSpeciesLine.fromSpeciesId(pet.species).promptLabel,
            stage = pet.stage.lowercase(),
            personality = pet.personality.lowercase(),
            personalityNote = personalityEnum?.description.orEmpty(),
            mood = pet.mood.lowercase(),
            condition = notes,
            gateLevel = gate?.level,
            gateGear = gear
        )
    }

    fun heroCard(hero: AdventureHero): String = buildString {
        appendLine("HERO (the player's real pet, protagonist of this dream):")
        appendLine("- Name: ${hero.name}")
        appendLine("- Species: ${hero.species}")
        appendLine("- Life stage: ${hero.stage}")
        append("- Personality: ${hero.personality}")
        if (hero.personalityNote.isNotBlank()) append(" (${hero.personalityNote.lowercase()})")
        appendLine()
        appendLine("- Mood: ${hero.mood}")
        if (hero.condition.isNotEmpty()) appendLine("- Condition: ${hero.condition.joinToString(", ")}")
        hero.gateLevel?.let { level ->
            append("- Adventure Gate level: $level")
            if (hero.gateGear.isNotEmpty()) append("; carries ${hero.gateGear.joinToString(", ")}")
            appendLine()
        }
    }.trimEnd()

    fun runStateLine(state: AdventureRunState): String =
        "RUN STATE: hope=${state.hope}/${AdventureRunState.MAX_HOPE}; " +
            "items: ${state.items.joinToString("; ").ifBlank { "none" }}; " +
            "flags: ${state.flags.joinToString("; ").ifBlank { "none" }}"

    /** Default narrator prompt: premise, Lumo's voice and the tone guide, in the story language. */
    fun systemPrompt(language: String, dungeon: DungeonType): String {
        val lore = AdventureLore.loreFor(loreCode(language))
        val companion = lore.companion
        return buildString {
            appendLine("You are the narrator of the Dream Chronicle, the book that ${companion.name} keeps of a pet's dreams.")
            appendLine("Every adventure is a dream the player's own pet walks through as the hero.")
            appendLine()
            appendLine("WORLD:")
            appendLine(lore.premise)
            appendLine()
            appendLine("COMPANION: ${companion.name}")
            appendLine(companion.species)
            appendLine(companion.personality)
            appendLine("How ${companion.name} talks:")
            companion.voiceSamples.take(3).forEach { appendLine("- $it") }
            appendLine("Running jokes (use at most one per scene):")
            companion.runningJokes.take(2).forEach { appendLine("- $it") }
            appendLine()
            appendLine("STYLE GUIDE:")
            appendLine(lore.toneGuide)
            if (dungeon.isRandom) {
                appendLine()
                appendLine("This is the free-form Chaos Realm: invent the setting yourself; the Nexum lore and ${companion.name} are optional seasoning.")
            }
            appendLine()
            append("Always write the story in $language.")
            if (loreCode(language) == "es") {
                appendLine()
                append("Write natural, idiomatic Spanish from Spain in the neutral tú register, avoiding literal calques from English. Use the dialogue dash (—) for speech and never assume the pet's gender.")
            }
        }
    }

    fun plannerPrompt(language: String, dungeon: DungeonType, hero: AdventureHero, gateVisit: String? = null): String {
        val code = loreCode(language)
        val lore = AdventureLore.loreFor(code)
        val title = lore.dungeon(dungeon)?.title ?: dungeon.displayName
        return buildString {
            appendLine("You are a story planner for a cozy, magical dream adventure starring a pet. ${lore.companion.name} is its lantern companion.")
            appendLine()
            appendLine("WORLD:")
            appendLine(lore.premise)
            appendLine()
            appendLine("DREAM SETTING: $title")
            appendLine(AdventureLore.dungeonStylePrompt(dungeon, code).trim())
            appendLine()
            if (!gateVisit.isNullOrBlank()) {
                appendLine(gateVisit.trim())
                appendLine()
            }
            appendLine(heroCard(hero))
            appendLine()
            appendLine("Create a brief structural outline with these exact fields:")
            appendLine("TOTAL_STAGES: number between 8 and 20")
            appendLine("STORY_THREAD: one paragraph with the main plot arc, tailored to the hero's personality")
            appendLine("KEY_EVENTS: 4-5 bullet points")
            appendLine("POSSIBLE_ENDINGS: 2-3 bullet points (all warm; failure means being carried back gently)")
            appendLine("TONE: one short phrase")
            appendLine("DIFFICULTY: easy, medium, or hard")
            appendLine()
            appendLine("Keep it concise and practical. Keep the field names above in English.")
            append("Write everything else in $language.")
        }
    }

    fun sceneHeader(
        language: String,
        dungeon: DungeonType,
        schematic: StorySchematic,
        hero: AdventureHero,
        state: AdventureRunState,
        dungeonLabel: String,
        gateVisit: String? = null
    ): String = buildString {
        val code = loreCode(language)
        appendLine("SETTING: $dungeonLabel")
        appendLine("THEME: ${AdventureLore.dungeonStylePrompt(dungeon, code).trim()}")
        appendLine("LANGUAGE: $language")
        appendLine()
        if (!gateVisit.isNullOrBlank()) {
            appendLine(gateVisit.trim())
            appendLine()
        }
        appendLine(heroCard(hero))
        appendLine()
        appendLine(runStateLine(state))
        appendLine()
        appendLine("HIDDEN STORY GUIDE:")
        appendLine("Main arc: ${schematic.storyThread}")
        appendLine("Key events: ${schematic.keyEvents.joinToString(", ")}")
        appendLine("Possible endings: ${schematic.possibleEndings.joinToString(", ")}")
        appendLine("Tone: ${schematic.tone}")
        appendLine("Difficulty: ${schematic.difficulty}")
        appendLine()
    }

    enum class SceneKind { NORMAL, EPILOGUE, CARRIED_BACK }

    fun sceneFooter(language: String, stageNumber: Int, totalStages: Int, kind: SceneKind): String = buildString {
        val progressPercent = (stageNumber * 100) / totalStages.coerceAtLeast(1)
        when (kind) {
            SceneKind.EPILOGUE -> append("Write scene $stageNumber of $totalStages: the EPILOGUE. Resolve the story warmly, show how the hero grew, and give Lumo a last tender moment.")
            SceneKind.CARRIED_BACK -> append("The hero's hope has run out. Write a gentle ending: Lumo and the dream light carry the hero safely back home. Nobody dies or is hurt; give a soft lesson and a promise of another try.")
            SceneKind.NORMAL -> {
                append("Write scene $stageNumber of $totalStages. ")
                when {
                    progressPercent < 20 -> append("This is the opening stretch: introduce the hero and Lumo, establish wonder and a first small problem.")
                    progressPercent < 50 -> append("Build momentum and reveal a meaningful new twist.")
                    progressPercent < 80 -> append("Escalate the stakes and make the consequences sharper.")
                    else -> append("Prepare the climax and narrow the possible outcomes.")
                }
            }
        }
        appendLine()
        appendLine()
        appendLine("OUTPUT FORMAT. Use exactly these English labels; write all content in $language:")
        appendLine("SCENE: <120-220 words in second person, the hero by name, with sound, smell, texture and light>")
        if (kind == SceneKind.NORMAL) {
            appendLine("CHOICES:")
            appendLine("1) <short choice>")
            appendLine("2) <short choice>")
            appendLine("(2 to 4 clearly different choices, each with a real consequence)")
        } else {
            appendLine("(No CHOICES section: this scene ends the story.)")
        }
        appendLine("STATE: hope=<0-100>, items=<items the hero carries, separated by ; or none>, flags=<short story flags separated by ; or none>")
        appendLine()
        appendLine("Rules:")
        appendLine("- Stay fully in-world; never mention the format, stage counts or hidden notes inside the scene.")
        appendLine("- Hope rises with kindness, courage and clever ideas and falls with recklessness or ignoring friends. At 0 the hero is gently carried back (no death).")
        appendLine("- Keep every item and flag short; keep the previous ones unless they were used up or lost.")
        appendLine("- Keep this scene distinct from the recent scenes.")
        append("- Return only SCENE, ${if (kind == SceneKind.NORMAL) "CHOICES and STATE" else "STATE"}.")
    }

    fun chroniclePrompt(
        language: String,
        hero: AdventureHero,
        dungeonLabel: String,
        outcome: AdventureOutcome,
        state: AdventureRunState,
        storySoFar: String
    ): String = buildString {
        appendLine("Write the Dream Chronicle page for this finished dream.")
        appendLine("Hero: ${hero.name}, a ${hero.personality} ${hero.species}. Companion: Lumo the dream lantern.")
        appendLine("Setting: $dungeonLabel")
        appendLine(
            if (outcome == AdventureOutcome.VICTORY) "Outcome: the hero finished the dream with hope ${state.hope}/100."
            else "Outcome: the hero ran out of hope and was gently carried back home."
        )
        if (state.items.isNotEmpty()) appendLine("Found: ${state.items.joinToString("; ")}")
        if (storySoFar.isNotBlank()) {
            appendLine("Story so far:")
            appendLine(storySoFar.trim())
        }
        appendLine()
        appendLine("Return exactly two lines:")
        appendLine("TITLE: <poetic page title, at most 8 words>")
        appendLine("SUMMARY: <2 or 3 warm sentences in past tense naming the hero and Lumo>")
        append("Keep the labels in English; write the content in $language.")
    }

    /** Parses the TITLE/SUMMARY reply; falls back to the given values when a part is missing. */
    fun parseChronicle(response: String, fallbackTitle: String, fallbackSummary: String): Pair<String, String> {
        val title = Regex("^[\\s*#>_\\-•]*(?:TITLE|T[IÍ]TULO)[\\s*_]*:[\\s*_]*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            .find(response)?.groupValues?.get(1)?.trim()?.trim('*', '_', '"')?.trim()
        val summary = Regex("(?:SUMMARY|RESUMEN)[\\s*_]*:[\\s*_]*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(response)?.groupValues?.get(1)?.trim()?.trim('*', '_')?.trim()
        return (title?.takeIf { it.isNotBlank() }?.take(80) ?: fallbackTitle) to
            (summary?.takeIf { it.isNotBlank() }?.take(600) ?: fallbackSummary)
    }

    /** Deterministic Chronicle page used when the model gives no usable title or summary. */
    fun fallbackChronicle(
        language: String,
        hero: AdventureHero,
        dungeonTitle: String,
        outcome: AdventureOutcome
    ): Pair<String, String> {
        val spanish = loreCode(language) == "es"
        return if (outcome == AdventureOutcome.VICTORY) {
            if (spanish) "Página de $dungeonTitle" to "${hero.name} y Lumo atravesaron $dungeonTitle con valor y terminaron el sueño juntos."
            else "A Page of $dungeonTitle" to "${hero.name} and Lumo crossed $dungeonTitle with courage and finished the dream together."
        } else {
            if (spanish) "El sueño que volvió a casa" to "La esperanza de ${hero.name} se apagó en $dungeonTitle, pero Lumo le mostró con suavidad el camino de vuelta a casa. Siempre hay otra oportunidad."
            else "The Dream That Went Home" to "${hero.name}'s hope ran low in $dungeonTitle, but Lumo carried the way home gently. There is always another try."
        }
    }

    /** Stored fallback ending scene (raw scene format) used when the model cannot write the carried-back scene. */
    fun fallbackCarriedBackScene(language: String, hero: AdventureHero): String =
        if (loreCode(language) == "es") {
            "SCENE: La luz se vuelve muy suave. Lumo, cálido como una vela, te levanta con cuidado, ${hero.name}. " +
                "El camino de vuelta huele a sábanas limpias y a lluvia. —Descansa —susurra Lumo—. El sueño no se va a ninguna parte; " +
                "lo intentamos otra vez cuando quieras. Todo está bien.\nSTATE: hope=0"
        } else {
            "SCENE: The light grows very soft. ${hero.name}, Lumo's candle-warm arms lift you gently. " +
                "The way back smells of clean blankets and rain. \"Rest,\" Lumo whispers. \"The dream is not going anywhere; " +
                "we will try again whenever you like.\" Everything is all right.\nSTATE: hope=0"
        }

    /** Positive image prompt: fixed style prefix + dungeon world prompt (English) + optional scene detail. */
    fun imagePrompt(dungeon: DungeonType, sceneDetail: String?): String {
        val world = AdventureLore.dungeonStylePrompt(dungeon, "en").trim()
            .substringBefore(" Tone:").trim()
        return buildString {
            append(IMAGE_STYLE_PREFIX)
            append(", ")
            append(world)
            sceneDetail?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(", ")
                append(it.take(280))
            }
        }
    }
}
