package com.example.llamadroid.tama.adventure

import android.content.Context
import androidx.annotation.StringRes
import com.example.llamadroid.R

/**
 * Types of dungeons available for text adventures (Dream Chronicle runs). Names follow docs/adventure-story-bible.md;
 * the enum constants stay stable for saved sessions.
 * Dungeons unlock sequentially (1-6), with Chaos Realm always available.
 */
enum class DungeonType(
    val displayName: String,
    @StringRes val displayNameRes: Int,
    val emoji: String,
    val unlockOrder: Int,  // 0 = always unlocked
    val stylePrompt: String
) {
    SHADOW_CRYPTS(
        displayName = "Hushed Crypts of Forgotten Lullabies",
        displayNameRes = R.string.adventure_dungeon_shadow_crypts,
        emoji = "💀",
        unlockOrder = 1,
        stylePrompt = """
            |Dark necromantic setting. Ancient crypts filled with undead, 
            |cursed artifacts, and whispers of forgotten souls. Gothic horror 
            |atmosphere with moral dilemmas about life and death. The air is 
            |thick with decay and dark magic. Spirits haunt every corner.
        """.trimMargin()
    ),
    
    ARCANE_DEPTHS(
        displayName = "Lantern Deeps of the Sleeping Sea",
        displayNameRes = R.string.adventure_dungeon_arcane_depths,
        emoji = "🔮",
        unlockOrder = 2,
        stylePrompt = """
            |Magical underwater caverns beneath a forgotten sea. Bioluminescent 
            |creatures light paths through drowned wizard towers. Ancient sea-witch 
            |covens guard forbidden knowledge. Mystery and wonder mixed with 
            |dangerous magical experiments gone terribly wrong.
        """.trimMargin()
    ),
    
    INFERNAL_SPIRE(
        displayName = "Spire of Fair Bargains",
        displayNameRes = R.string.adventure_dungeon_infernal_spire,
        emoji = "🔥",
        unlockOrder = 3,
        stylePrompt = """
            |Demonic tower rising from a volcanic hellscape. Devils offer deals 
            |with hidden costs. Soul contracts written in burning blood. Themes 
            |of temptation, power at terrible prices, and redemption through 
            |flames. The heat is oppressive, the shadows hide worse.
        """.trimMargin()
    ),
    
    FROSTBOUND_HALLS(
        displayName = "Halls of the Unfinished Winter",
        displayNameRes = R.string.adventure_dungeon_frostbound_halls,
        emoji = "❄️",
        unlockOrder = 4,
        stylePrompt = """
            |Frozen palace of an ancient ice lich at the edge of the world. 
            |Crystalline corridors hold frozen-in-time victims. Cold that seeps 
            |into the soul itself. Themes of isolation, preservation beyond 
            |death, and bitter grudges that span centuries.
        """.trimMargin()
    ),
    
    VERDANT_MAW(
        displayName = "The Verdant Yawn",
        displayNameRes = R.string.adventure_dungeon_verdant_maw,
        emoji = "🌿",
        unlockOrder = 5,
        stylePrompt = """
            |Living dungeon made of carnivorous plants and sentient fungal 
            |networks. Druidic corruption has turned nature into a predator. 
            |The thin line between growth and consumption blurs. Body horror 
            |meets dark ecology. Everything here is hungry and patient.
        """.trimMargin()
    ),
    
    VOID_SANCTUM(
        displayName = "Sanctum Between the Stars",
        displayNameRes = R.string.adventure_dungeon_void_sanctum,
        emoji = "🌑",
        unlockOrder = 6,
        stylePrompt = """
            |Reality-bending temple to eldritch entities from beyond the stars. 
            |Non-euclidean geometry warps perception. Sanity-testing horrors 
            |lurk in impossible angles. Cosmic indifference as the true terror. 
            |Lovecraftian atmosphere with forbidden knowledge that corrupts.
        """.trimMargin()
    ),
    
    CHAOS_REALM(
        displayName = "Chaos Realm",
        displayNameRes = R.string.adventure_dungeon_chaos_realm,
        emoji = "🎲",
        unlockOrder = 0,  // Always unlocked
        stylePrompt = """
            |You have complete creative freedom. Generate any dark fantasy 
            |setting you imagine. Mix genres, invent new horrors, surprise 
            |the player with the unexpected. Be creative and unpredictable.
            |This world follows no rules but your imagination.
        """.trimMargin()
    );
    
    val isRandom: Boolean get() = this == CHAOS_REALM
    val isAlwaysUnlocked: Boolean get() = unlockOrder == 0
    
    companion object {
        fun getUnlockedDungeons(completedCount: Int): List<DungeonType> {
            return entries.filter { it.isAlwaysUnlocked || it.unlockOrder <= completedCount + 1 }
        }
    }
}

fun DungeonType.localizedName(context: Context): String = context.getString(displayNameRes)
