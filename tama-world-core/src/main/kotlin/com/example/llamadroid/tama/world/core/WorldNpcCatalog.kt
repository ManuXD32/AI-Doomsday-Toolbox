package com.example.llamadroid.tama.world.core

import kotlinx.serialization.Serializable

@Serializable
data class NpcDefinition(
    val id: String,
    val name: String,
    val role: NpcRole,
    val homeStructureId: String? = null,
    val jobStructureId: String? = null,
    val stationary: Boolean = false,
    val personality: Personality = Personality.CHEERFUL,
    val preferredBiomes: Set<Biome> = emptySet()
)

/**
 * The original ambient and park identities, now represented as deterministic
 * world inhabitants. IDs deliberately match the existing Tama catalogs.
 */
object WorldNpcCatalog {
    val definitions: List<NpcDefinition> = listOf(
        NpcDefinition("farm_farmer", "Patch", NpcRole.FARMER, "fixed_3_1", "fixed_3_1", personality = Personality.CHEERFUL, preferredBiomes = setOf(Biome.MEADOW)),
        NpcDefinition("boxing_coach", "Coach Mira", NpcRole.BOXING_COACH, "fixed_4_1", "fixed_4_1", personality = Personality.BRAVE, preferredBiomes = setOf(Biome.MEADOW, Biome.HIGHLANDS)),
        NpcDefinition("shop_seller", "Mimi", NpcRole.SHOP_SELLER, "fixed_1_0", "fixed_1_0", personality = Personality.CHEERFUL, preferredBiomes = setOf(Biome.MEADOW)),
        NpcDefinition("school_teacher", "Miss Clover", NpcRole.TEACHER, "fixed_1_1", "fixed_1_1", personality = Personality.CURIOUS, preferredBiomes = setOf(Biome.MEADOW, Biome.FOREST)),
        NpcDefinition("hospital_doctor", "Doctor Pip", NpcRole.DOCTOR, "fixed_3_0", "fixed_3_0", personality = Personality.CHEERFUL, preferredBiomes = setOf(Biome.MEADOW, Biome.MYSTIC_GROVE)),
        NpcDefinition("dungeon_adventurer", "Rook", NpcRole.ADVENTURER, "fixed_0_2", "fixed_0_2", personality = Personality.BRAVE, preferredBiomes = setOf(Biome.HIGHLANDS, Biome.MYSTIC_GROVE)),
        NpcDefinition("arcade_host", "Pixel Pop", NpcRole.ARCADE_HOST, "fixed_4_0", "fixed_4_0", stationary = true, personality = Personality.PLAYFUL, preferredBiomes = setOf(Biome.MEADOW)),
        NpcDefinition("alchemist_keeper", "Sage Wisp", NpcRole.ALCHEMIST, "fixed_0_1", "fixed_0_1", personality = Personality.CURIOUS, preferredBiomes = setOf(Biome.MYSTIC_GROVE, Biome.FOREST)),
        NpcDefinition("cloud_bunny", "Cloud Bunny", NpcRole.PARK_RESIDENT, "npc_home_a", "fixed_2_0", personality = Personality.CHEERFUL, preferredBiomes = setOf(Biome.MEADOW)),
        NpcDefinition("puddle_duck", "Puddle Duck", NpcRole.PARK_RESIDENT, "npc_home_b", "fixed_2_0", personality = Personality.PLAYFUL, preferredBiomes = setOf(Biome.WETLANDS)),
        NpcDefinition("mint_fox", "Mint Fox", NpcRole.PARK_RESIDENT, "npc_home_c", "fixed_2_0", personality = Personality.SHY, preferredBiomes = setOf(Biome.FOREST)),
        NpcDefinition("berry_cat", "Berry Cat", NpcRole.PARK_RESIDENT, "npc_home_a", "fixed_2_0", personality = Personality.CHEERFUL, preferredBiomes = setOf(Biome.MEADOW, Biome.FOREST)),
        NpcDefinition("moss_deer", "Moss Deer", NpcRole.PARK_RESIDENT, "npc_home_c", "fixed_2_0", personality = Personality.SHY, preferredBiomes = setOf(Biome.FOREST, Biome.WETLANDS)),
        NpcDefinition("sun_lamb", "Sun Lamb", NpcRole.PARK_RESIDENT, "npc_home_a", "fixed_2_0", personality = Personality.CHEERFUL, preferredBiomes = setOf(Biome.MEADOW)),
        NpcDefinition("acorn_mouse", "Acorn Mouse", NpcRole.PARK_RESIDENT, "npc_home_b", "fixed_2_0", personality = Personality.CURIOUS, preferredBiomes = setOf(Biome.FOREST)),
        NpcDefinition("ribbon_bird", "Ribbon Bird", NpcRole.PARK_RESIDENT, "npc_home_c", "fixed_2_0", personality = Personality.CHEERFUL, preferredBiomes = setOf(Biome.MEADOW, Biome.COAST)),
        NpcDefinition("sprout_frog", "Sprout Frog", NpcRole.PARK_RESIDENT, "npc_home_b", "fixed_2_0", personality = Personality.PLAYFUL, preferredBiomes = setOf(Biome.WETLANDS, Biome.MEADOW)),
        NpcDefinition("paper_pup", "Paper Pup", NpcRole.WORKPLACE_RESIDENT, "fixed_2_1", "fixed_2_1", personality = Personality.CURIOUS, preferredBiomes = setOf(Biome.MEADOW)),
        NpcDefinition("recycler", "The Recycler", NpcRole.RECYCLER, "npc_home_b", "fixed_2_0", personality = Personality.CHEERFUL, preferredBiomes = setOf(Biome.MEADOW, Biome.COAST)),
        NpcDefinition("seller", "Market Seller", NpcRole.MARKET_SELLER, "npc_home_c", "market_stall", personality = Personality.CHEERFUL, preferredBiomes = setOf(Biome.MEADOW))
    )

    private val byId = definitions.associateBy(NpcDefinition::id)

    val ids: Set<String>
        get() = byId.keys

    operator fun get(id: String): NpcDefinition = byId.getValue(id)

    fun find(id: String): NpcDefinition? = byId[id]
}
