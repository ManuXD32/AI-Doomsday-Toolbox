package com.example.llamadroid.tama.data

import android.content.Context
import androidx.annotation.StringRes
import com.example.llamadroid.R

enum class PetSpeciesLine(
    val id: String,
    @StringRes val displayNameRes: Int,
    val promptLabel: String,
    val promptFlavor: String
) {
    DRAGON(
        id = "dragon",
        displayNameRes = R.string.tama_species_dragon,
        promptLabel = "dragon",
        promptFlavor = """
            • You are a cute little dragon with a brave heart and a tiny hoard mentality
            • You adore shiny treasures, favorite snacks, and dramatic declarations about your greatness
            • Your confidence is real, but it should stay adorable rather than intimidating
        """.trimIndent()
    ),
    UNICORN(
        id = "unicorn",
        displayNameRes = R.string.tama_species_unicorn,
        promptLabel = "unicorn",
        promptFlavor = """
            • You are a gentle unicorn with a hopeful, affectionate, slightly magical vibe
            • You naturally look for wonder, comfort, and little moments of beauty
            • Your kindness should feel warm and sincere, never stiff or preachy
        """.trimIndent()
    ),
    KITSUNE(
        id = "kitsune",
        displayNameRes = R.string.tama_species_kitsune,
        promptLabel = "kitsune",
        promptFlavor = """
            • You are a clever kitsune who is playful, curious, and lightly mischievous
            • You tease with affection, enjoy little tricks, and notice details other pets might miss
            • Your sly side should feel cute and charming rather than cruel
        """.trimIndent()
    );

    companion object {
        fun fromSpeciesId(species: String?, legacyBodyStyle: Int = 0): PetSpeciesLine {
            return entries.firstOrNull { it.id.equals(species?.trim(), ignoreCase = true) }
                ?: when (Math.floorMod(legacyBodyStyle, entries.size)) {
                    1 -> UNICORN
                    2 -> KITSUNE
                    else -> DRAGON
                }
        }
    }
}

enum class PetSpriteState(val assetState: String, val frameCount: Int) {
    IDLE("idle", 2),
    WALK("walk", 4),
    RUN("run", 4),
    SLEEP("sleep", 2),
    EAT("eat", 4),
    CLEAN("clean", 4),
    PLAY("play", 4),
    WORK("work", 4),
    STUDY("study", 4),
    TRAIN("train", 4),
    RELAX("relax", 2),
    TALK("talk", 3),
    HAPPY("happy", 3),
    HURT_TIRED("hurt_tired", 2),
    SIT("sit", 2)
}

val TamaSpriteSupportedActions: Set<String> = setOf(
    "idle",
    "eating",
    "cleaning",
    "playing",
    "sleeping",
    "working",
    "studying",
    "sunbathing",
    "walking",
    "running",
    "relaxing",
    "training",
    "talking",
    "happy",
    "hurt",
    "tired",
    "sitting",
    "poop_cleaning"
)

fun normalizePetSpecies(species: String?, legacyBodyStyle: Int = 0): String {
    return PetSpeciesLine.fromSpeciesId(species, legacyBodyStyle).id
}

fun TamaPet.normalizedSpeciesPet(): TamaPet {
    val normalized = normalizePetSpecies(species, genetics.bodyStyle)
    return if (normalized == species) this else copy(species = normalized)
}

fun speciesDisplayName(context: Context, species: String?, legacyBodyStyle: Int = 0): String {
    val line = PetSpeciesLine.fromSpeciesId(species, legacyBodyStyle)
    return context.getString(line.displayNameRes)
}

fun mapPetActionToSpriteState(action: String?, isSleeping: Boolean): PetSpriteState {
    if (isSleeping) return PetSpriteState.SLEEP
    return when (action?.lowercase()) {
        "walk", "walking" -> PetSpriteState.WALK
        "run", "running" -> PetSpriteState.RUN
        "sleep", "sleeping" -> PetSpriteState.SLEEP
        "eat", "eating", "drink", "drinking" -> PetSpriteState.EAT
        "clean", "cleaning", "wash", "washing", "poop_cleaning" -> PetSpriteState.CLEAN
        "play", "playing" -> PetSpriteState.PLAY
        "work", "working" -> PetSpriteState.WORK
        "study", "studying" -> PetSpriteState.STUDY
        "train", "training" -> PetSpriteState.TRAIN
        "sunbathing", "relax", "relaxing" -> PetSpriteState.RELAX
        "talk", "talking" -> PetSpriteState.TALK
        "happy" -> PetSpriteState.HAPPY
        "hurt", "tired", "hurt_tired" -> PetSpriteState.HURT_TIRED
        "sit", "sitting" -> PetSpriteState.SIT
        else -> PetSpriteState.IDLE
    }
}

fun resolvePetSpriteAssetPath(
    speciesLine: PetSpeciesLine,
    stage: GrowthStage,
    state: PetSpriteState,
    frameIndex: Int
): String {
    val actualState = if (stage == GrowthStage.EGG) PetSpriteState.IDLE else state
    val frame = Math.floorMod(frameIndex, actualState.frameCount)
    return "tama/animations/frames/${speciesLine.id}/${stage.name.lowercase()}/${actualState.assetState}_$frame.png"
}
