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
    WALK("walk", 2),
    SLEEP("sleep", 1),
    EAT("eat", 2)
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
    "relaxing"
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
        "walking" -> PetSpriteState.WALK
        "sleeping" -> PetSpriteState.SLEEP
        "eating" -> PetSpriteState.EAT
        "idle", "cleaning", "playing", "working", "studying", "training", "sunbathing", "relaxing" -> PetSpriteState.IDLE
        else -> PetSpriteState.IDLE
    }
}

fun resolvePetSpriteAssetPath(
    speciesLine: PetSpeciesLine,
    stage: GrowthStage,
    state: PetSpriteState,
    frameIndex: Int
): String {
    val frame = if (state.frameCount <= 1) {
        0
    } else {
        Math.floorMod(frameIndex, state.frameCount)
    }
    return "tama/pets/${speciesLine.id}/${stage.name.lowercase()}/${state.assetState}_$frame.png"
}
