package com.example.llamadroid.tama.data

/** Stable bridge from the fixed-map IDs/type aliases used by existing Tama code. */
object LegacyLocationAliases {
    const val HOME = "fixed_0_0"
    const val SHOP = "fixed_1_0"
    const val PARK = "fixed_2_0"
    const val HOSPITAL = "fixed_3_0"
    const val ARCADE = "fixed_4_0"
    const val ALCHEMIST = "fixed_0_1"
    const val SCHOOL = "fixed_1_1"
    const val WORKPLACE = "fixed_2_1"
    const val FARM = "fixed_3_1"
    const val BOXING_RING = "fixed_4_1"
    const val DUNGEON_A = "fixed_0_2"
    const val ADVENTURE_GATE = "fixed_2_2"
    const val DUNGEON_B = "fixed_4_2"

    val allIds: Set<String> = linkedSetOf(
        HOME,
        SHOP,
        PARK,
        HOSPITAL,
        ARCADE,
        ALCHEMIST,
        SCHOOL,
        WORKPLACE,
        FARM,
        BOXING_RING,
        DUNGEON_A,
        ADVENTURE_GATE,
        DUNGEON_B
    )

    private val aliases: Map<String, String> = buildMap {
        put("home", HOME)
        put("house", HOME)
        put("shop", SHOP)
        put("store", SHOP)
        put("park", PARK)
        put("hospital", HOSPITAL)
        put("clinic", HOSPITAL)
        put("arcade", ARCADE)
        put("alchemist", ALCHEMIST)
        put("alchemy", ALCHEMIST)
        put("school", SCHOOL)
        put("workplace", WORKPLACE)
        put("work", WORKPLACE)
        put("farm", FARM)
        put("boxing_ring", BOXING_RING)
        put("boxing ring", BOXING_RING)
        put("gym", BOXING_RING)
        put("dungeon_a", DUNGEON_A)
        put("dungeon_1", DUNGEON_A)
        put("dungeon1", DUNGEON_A)
        put("dungeon_b", DUNGEON_B)
        put("dungeon_2", DUNGEON_B)
        put("dungeon2", DUNGEON_B)
        put("adventure_gate", ADVENTURE_GATE)
        put("adventure gate", ADVENTURE_GATE)
    }

    /**
     * Returns a stable fixed ID. Bare `dungeon` is intentionally unresolved
     * because the old map has two dungeon positions and the caller must choose.
     */
    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value in allIds) return value
        return aliases[value]
    }

    /**
     * Normalizes a persisted location id. Bare legacy `dungeon` used the first
     * fixed dungeon; explicit second-dungeon ids never collapse into it.
     */
    fun normalizeLocation(raw: String?): String? {
        normalize(raw)?.let { return it }
        return if (raw.equals("dungeon", ignoreCase = true)) DUNGEON_A else null
    }
}
