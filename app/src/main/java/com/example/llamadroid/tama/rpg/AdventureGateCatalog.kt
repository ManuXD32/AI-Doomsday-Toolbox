package com.example.llamadroid.tama.rpg

import com.example.llamadroid.R

object AdventureGateCatalog {
    const val WORLD_COUNT = 7
    const val PHASES_PER_WORLD = 15
    const val BATTLE_POTION_LIMIT = 4
    const val BATTLE_GUARD_LIMIT = 4
    const val MAX_ENEMIES_PER_WAVE = 4
    const val LOADOUT_ATTACK_LIMIT = 4
    const val LOADOUT_MAGIC_LIMIT = 4
    const val SKELETON_HELPER_SKILL_ID = "skeleton_helper"
    const val ALWAYS_GUARD_SKILL_ID = "guard"
    const val NIGHT_ARENA_WORLD_ID = "night_arena"
    const val BOSS_RELIC_DROP_CHANCE_PERCENT = 20
    const val MYSTERY_RELIC_DROP_CHANCE_PERCENT = 5
    const val MYSTERY_RELIC_ID = "ag_relic_nexum_heart"
    const val CLEANSE_DRAUGHT_ID = "ag_cleanse_draught"

    fun consumesGuardUse(skillId: String): Boolean = skillId == ALWAYS_GUARD_SKILL_ID

    val startingAttackIds = listOf("paw_strike")
    val startingMagicIds = listOf("spark")
    val starterSkillIds = startingAttackIds + startingMagicIds + ALWAYS_GUARD_SKILL_ID
    val strategicSkillIds: Set<String> = setOf(
        "bleeding_swipe",
        "shield_cracker",
        "meteor_bounce",
        "ember_lullaby",
        "tidal_mirror",
        "star_prism",
        "thorn_snare",
        "shadow_mist",
        "dream_mend",
        "mana_shell",
        SKELETON_HELPER_SKILL_ID
    )

    private const val SKILL_STAR_PRICE = 7000

    val statuses: List<AdventureGateStatusDefinition> = listOf(
        AdventureGateStatusDefinition("poison", R.string.adventure_gate_status_poison, R.string.adventure_gate_status_poison_desc, "tama/adventure_gate/status_icons/poison.png"),
        AdventureGateStatusDefinition("burn", R.string.adventure_gate_status_burn, R.string.adventure_gate_status_burn_desc, "tama/adventure_gate/status_icons/burn.png"),
        AdventureGateStatusDefinition("freeze", R.string.adventure_gate_status_freeze, R.string.adventure_gate_status_freeze_desc, "tama/adventure_gate/status_icons/freeze.png"),
        AdventureGateStatusDefinition("paralyze", R.string.adventure_gate_status_paralyze, R.string.adventure_gate_status_paralyze_desc, "tama/adventure_gate/status_icons/paralyze.png"),
        AdventureGateStatusDefinition("bleed", R.string.adventure_gate_status_bleed, R.string.adventure_gate_status_bleed_desc, "tama/adventure_gate/status_icons/bleed.png"),
        AdventureGateStatusDefinition("blind", R.string.adventure_gate_status_blind, R.string.adventure_gate_status_blind_desc, "tama/adventure_gate/status_icons/blind.png"),
        AdventureGateStatusDefinition("slow", R.string.adventure_gate_status_slow, R.string.adventure_gate_status_slow_desc, "tama/adventure_gate/status_icons/slow.png"),
        AdventureGateStatusDefinition("weaken", R.string.adventure_gate_status_weaken, R.string.adventure_gate_status_weaken_desc, "tama/adventure_gate/status_icons/weaken.png"),
        AdventureGateStatusDefinition("brittle", R.string.adventure_gate_status_brittle, R.string.adventure_gate_status_brittle_desc, "tama/adventure_gate/status_icons/brittle.png"),
        AdventureGateStatusDefinition("regen", R.string.adventure_gate_status_regen, R.string.adventure_gate_status_regen_desc, "tama/adventure_gate/status_icons/regen.png"),
        AdventureGateStatusDefinition("ward", R.string.adventure_gate_status_ward, R.string.adventure_gate_status_ward_desc, "tama/adventure_gate/status_icons/ward.png")
    )

    val badStatusIds: Set<String> = setOf(
        "poison",
        "burn",
        "bleed",
        "freeze",
        "paralyze",
        "blind",
        "slow",
        "weaken",
        "brittle"
    )

    val supplies: List<AdventureGateSupplyDefinition> = listOf(
        supply("ag_hp_dew_tiny", AdventureGateSupplyKind.HP, R.string.adventure_gate_item_tiny_hp_dew, 120, 120, 0),
        supply("ag_mana_dew_tiny", AdventureGateSupplyKind.MANA, R.string.adventure_gate_item_tiny_mana_dew, 60, 100, 0),
        supply(CLEANSE_DRAUGHT_ID, AdventureGateSupplyKind.CLEANSE, R.string.adventure_gate_item_cleanse_draught, 0, 600, 0),
        supply("ag_hp_brew_warm", AdventureGateSupplyKind.HP, R.string.adventure_gate_item_warm_hp_brew, 220, 260, 1),
        supply("ag_mana_brew_warm", AdventureGateSupplyKind.MANA, R.string.adventure_gate_item_warm_mana_brew, 112, 230, 1),
        supply("ag_hp_elixir_pearl", AdventureGateSupplyKind.HP, R.string.adventure_gate_item_pearl_hp_elixir, 340, 520, 2),
        supply("ag_mana_elixir_pearl", AdventureGateSupplyKind.MANA, R.string.adventure_gate_item_pearl_mana_elixir, 180, 460, 2),
        supply("ag_hp_flask_clock", AdventureGateSupplyKind.HP, R.string.adventure_gate_item_clock_hp_flask, 500, 900, 3),
        supply("ag_mana_flask_clock", AdventureGateSupplyKind.MANA, R.string.adventure_gate_item_clock_mana_flask, 260, 800, 3),
        supply("ag_hp_draught_moonmoss", AdventureGateSupplyKind.HP, R.string.adventure_gate_item_moonmoss_hp_draught, 680, 1400, 4),
        supply("ag_mana_draught_moonmoss", AdventureGateSupplyKind.MANA, R.string.adventure_gate_item_moonmoss_mana_draught, 360, 1250, 4),
        supply("ag_hp_phial_aurora", AdventureGateSupplyKind.HP, R.string.adventure_gate_item_aurora_hp_phial, 880, 2050, 5),
        supply("ag_mana_phial_aurora", AdventureGateSupplyKind.MANA, R.string.adventure_gate_item_aurora_mana_phial, 480, 1850, 5),
        supply("ag_hp_ambrosia_star", AdventureGateSupplyKind.HP, R.string.adventure_gate_item_star_hp_ambrosia, 1200, 3000, 6),
        supply("ag_mana_ambrosia_star", AdventureGateSupplyKind.MANA, R.string.adventure_gate_item_star_mana_ambrosia, 640, 2700, 6),
        supply("ag_skill_star", AdventureGateSupplyKind.SKILL_POINT, R.string.adventure_gate_item_skill_star, 1, SKILL_STAR_PRICE, 0)
    )

    val recipes: List<AdventureGateRecipeDefinition> = listOf(
        recipe("ag_mana_dew_tiny", "crop_wheat", "crop_rice"),
        recipe("ag_hp_dew_tiny", "crop_rice", "crop_carrot"),
        recipe(CLEANSE_DRAUGHT_ID, "crop_wheat", "crop_carrot", "crop_tomato", "crop_corn"),
        recipe("ag_mana_brew_warm", "crop_wheat", "crop_carrot", "crop_tomato"),
        recipe("ag_hp_brew_warm", "crop_rice", "crop_carrot", "crop_corn"),
        recipe("ag_mana_elixir_pearl", "crop_carrot", "crop_tomato", "crop_corn", "crop_corn"),
        recipe("ag_hp_elixir_pearl", "crop_rice", "crop_tomato", "crop_corn", "crop_strawberry"),
        recipe("ag_mana_flask_clock", "crop_carrot", "crop_tomato", "crop_corn", "crop_strawberry", "crop_strawberry"),
        recipe("ag_hp_flask_clock", "crop_rice", "crop_corn", "crop_corn", "crop_strawberry", "crop_melon"),
        recipe("ag_mana_draught_moonmoss", "crop_tomato", "crop_corn", "crop_strawberry", "crop_melon", "crop_pumpkin"),
        recipe("ag_hp_draught_moonmoss", "crop_carrot", "crop_strawberry", "crop_melon", "crop_melon", "crop_pumpkin"),
        recipe("ag_mana_phial_aurora", "crop_corn", "crop_strawberry", "crop_melon", "crop_melon", "crop_pumpkin", "crop_pumpkin"),
        recipe("ag_hp_phial_aurora", "crop_tomato", "crop_strawberry", "crop_strawberry", "crop_melon", "crop_melon", "crop_pumpkin", "crop_pumpkin"),
        recipe("ag_mana_ambrosia_star", "crop_corn", "crop_strawberry", "crop_strawberry", "crop_melon", "crop_melon", "crop_melon", "crop_pumpkin", "crop_pumpkin", "crop_pumpkin"),
        recipe("ag_hp_ambrosia_star", "crop_corn", "crop_strawberry", "crop_strawberry", "crop_melon", "crop_melon", "crop_melon", "crop_pumpkin", "crop_pumpkin", "crop_pumpkin", "crop_pumpkin")
    )

    val equipment: List<AdventureGateEquipmentDefinition> = listOf(
        equipment("ag_weapon_sprout_baton", AdventureGateEquipmentSlot.WEAPON, R.string.adventure_gate_item_sprout_baton, 700, 0, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(attack = 4, accuracy = 3), elementDamageElement = AdventureGateElement.NATURE, elementDamageBonusPercent = 6)),
        equipment("ag_shield_leaf_shell", AdventureGateEquipmentSlot.SHIELD, R.string.adventure_gate_item_leaf_shell_shield, 850, 0, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(defense = 5), incomingReductionPercent = 3), weaknesses = setOf(AdventureGateElement.FIRE), resistances = setOf(AdventureGateElement.NATURE, AdventureGateElement.WATER)),
        equipment("ag_ring_dewdrop", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_dewdrop_ring, 650, 0, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(maxHp = 12), healingBonusPercent = 5)),
        equipment("ag_weapon_ember_hammer", AdventureGateEquipmentSlot.WEAPON, R.string.adventure_gate_item_ember_toy_hammer, 1350, 1, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(attack = 8), elementDamageElement = AdventureGateElement.FIRE, elementDamageBonusPercent = 8)),
        equipment("ag_shield_brass_button", AdventureGateEquipmentSlot.SHIELD, R.string.adventure_gate_item_brass_button_guard, 1550, 1, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(defense = 9), reflectDamagePercent = 4), weaknesses = setOf(AdventureGateElement.STORM), resistances = setOf(AdventureGateElement.FIRE, AdventureGateElement.SLASH)),
        equipment("ag_ring_warm_gear", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_warm_gear_ring, 1250, 1, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(maxMana = 8), manaRefundOnWeakHit = 1)),
        equipment("ag_weapon_coral_bubble_rod", AdventureGateEquipmentSlot.WEAPON, R.string.adventure_gate_item_coral_bubble_rod, 2300, 2, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(magic = 10), elementDamageElement = AdventureGateElement.WATER, elementDamageBonusPercent = 9)),
        equipment("ag_shield_pearl_shell", AdventureGateEquipmentSlot.SHIELD, R.string.adventure_gate_item_pearl_shell_shield, 2600, 2, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(defense = 13), potionBonusPercent = 5), weaknesses = setOf(AdventureGateElement.STORM), resistances = setOf(AdventureGateElement.WATER, AdventureGateElement.LIGHT)),
        equipment("ag_ring_tide_loop", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_tide_loop, 2200, 2, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(speed = 2, evasion = 4), potionBonusPercent = 8)),
        equipment("ag_weapon_clockwork_clawblade", AdventureGateEquipmentSlot.WEAPON, R.string.adventure_gate_item_clockwork_clawblade, 3400, 3, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(attack = 14, speed = 2, accuracy = 6), elementDamageElement = AdventureGateElement.SLASH, elementDamageBonusPercent = 10)),
        equipment("ag_shield_gyro_plate", AdventureGateEquipmentSlot.SHIELD, R.string.adventure_gate_item_gyro_plate_shield, 3800, 3, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(defense = 17), incomingReductionPercent = 6), weaknesses = setOf(AdventureGateElement.FIRE), resistances = setOf(AdventureGateElement.METAL, AdventureGateElement.STORM)),
        equipment("ag_ring_second_hand", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_second_hand_ring, 3200, 3, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(speed = 5, evasion = 5), forceFirstTurn = true)),
        equipment("ag_weapon_moonmoss_quillblade", AdventureGateEquipmentSlot.WEAPON, R.string.adventure_gate_item_moonmoss_quillblade, 4800, 4, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(magic = 18), elementDamageElement = AdventureGateElement.SHADOW, elementDamageBonusPercent = 10, statusChanceBonusPercent = 5)),
        equipment("ag_shield_bookcover_ward", AdventureGateEquipmentSlot.SHIELD, R.string.adventure_gate_item_bookcover_ward, 5300, 4, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(maxMana = 18, defense = 20), statusImmunityIds = setOf("poison")), weaknesses = setOf(AdventureGateElement.FIRE), resistances = setOf(AdventureGateElement.SHADOW, AdventureGateElement.ARCANE)),
        equipment("ag_ring_bookmark", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_bookmark_ring, 4500, 4, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(maxMana = 16, magic = 6), manaRefundOnWeakHit = 2)),
        equipment("ag_weapon_ribbon_ice_mallet", AdventureGateEquipmentSlot.WEAPON, R.string.adventure_gate_item_ribbon_ice_mallet, 6500, 5, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(attack = 22), elementDamageElement = AdventureGateElement.ICE, elementDamageBonusPercent = 12)),
        equipment("ag_shield_snowbutton_bulwark", AdventureGateEquipmentSlot.SHIELD, R.string.adventure_gate_item_snowbutton_bulwark, 7200, 5, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(maxHp = 40, defense = 25), statusImmunityIds = setOf("freeze")), weaknesses = setOf(AdventureGateElement.FIRE), resistances = setOf(AdventureGateElement.ICE, AdventureGateElement.LIGHT)),
        equipment("ag_ring_aurora", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_aurora_ring, 6100, 5, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(magic = 10, speed = 4, accuracy = 4, evasion = 4), healingBonusPercent = 10)),
        equipment("ag_weapon_starfall_fang", AdventureGateEquipmentSlot.WEAPON, R.string.adventure_gate_item_starfall_fang, 8800, 6, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(attack = 30, magic = 8, accuracy = 8), elementDamageElement = AdventureGateElement.LIGHT, elementDamageBonusPercent = 14)),
        equipment("ag_shield_eclipse_mirror", AdventureGateEquipmentSlot.SHIELD, R.string.adventure_gate_item_eclipse_mirror_shield, 9600, 6, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(defense = 34, evasion = 6), incomingReductionPercent = 10, reflectDamagePercent = 8), weaknesses = setOf(AdventureGateElement.ARCANE), resistances = setOf(AdventureGateElement.LIGHT, AdventureGateElement.SHADOW)),
        equipment("ag_ring_dreamlight_crown", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_dreamlight_crown_ring, 8400, 6, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(maxHp = 55, maxMana = 35, magic = 12), manaRefundOnWeakHit = 3)),
        bossRelic("ag_relic_bramble_crown", AdventureGateEquipmentSlot.SHIELD, R.string.adventure_gate_item_bramble_crown_relic, "sproutvale_gate", 7, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(defense = 11), reflectDamagePercent = 7), weaknesses = setOf(AdventureGateElement.FIRE), resistances = setOf(AdventureGateElement.NATURE, AdventureGateElement.STONE)),
        bossRelic("ag_relic_moth_lantern", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_moth_lantern_relic, "sproutvale_gate", 15, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(magic = 8), healingBonusPercent = 12)),
        bossRelic("ag_relic_foreman_tongs", AdventureGateEquipmentSlot.WEAPON, R.string.adventure_gate_item_foreman_tongs_relic, "ember_toyworks", 7, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(attack = 16), elementDamageElement = AdventureGateElement.FIRE, elementDamageBonusPercent = 16)),
        bossRelic("ag_relic_marionette_core", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_marionette_core_relic, "ember_toyworks", 15, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(maxMana = 22), statusImmunityIds = setOf("burn"))),
        bossRelic("ag_relic_tide_gauntlet", AdventureGateEquipmentSlot.WEAPON, R.string.adventure_gate_item_tide_gauntlet_relic, "bubbleglass_reef", 7, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(attack = 18), elementDamageElement = AdventureGateElement.WATER, elementDamageBonusPercent = 16)),
        bossRelic("ag_relic_leviathan_scale", AdventureGateEquipmentSlot.SHIELD, R.string.adventure_gate_item_leviathan_scale_relic, "bubbleglass_reef", 15, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(defense = 24), incomingReductionPercent = 9), weaknesses = setOf(AdventureGateElement.STORM), resistances = setOf(AdventureGateElement.WATER, AdventureGateElement.ARCANE)),
        bossRelic("ag_relic_roc_spring", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_roc_spring_relic, "clockwork_cloudway", 7, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(speed = 7, evasion = 8), forceFirstTurn = true)),
        bossRelic("ag_relic_chrono_key", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_chrono_key_relic, "clockwork_cloudway", 15, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(maxMana = 30, speed = 5, accuracy = 6, evasion = 4), manaRefundOnWeakHit = 4)),
        bossRelic("ag_relic_basilisk_clip", AdventureGateEquipmentSlot.WEAPON, R.string.adventure_gate_item_basilisk_clip_relic, "moonmoss_library", 7, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(magic = 22, accuracy = 10), elementDamageElement = AdventureGateElement.SHADOW, elementDamageBonusPercent = 16, statusChanceBonusPercent = 10)),
        bossRelic("ag_relic_libram_seal", AdventureGateEquipmentSlot.SHIELD, R.string.adventure_gate_item_libram_seal_relic, "moonmoss_library", 15, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(defense = 28, maxMana = 24), statusImmunityIds = setOf("poison", "paralyze")), weaknesses = setOf(AdventureGateElement.LIGHT), resistances = setOf(AdventureGateElement.ARCANE, AdventureGateElement.SHADOW)),
        bossRelic("ag_relic_nutcracker_star", AdventureGateEquipmentSlot.WEAPON, R.string.adventure_gate_item_nutcracker_star_relic, "frostfall_toybox", 7, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(attack = 28), elementDamageElement = AdventureGateElement.METAL, elementDamageBonusPercent = 18)),
        bossRelic("ag_relic_aurora_ribbon", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_aurora_ribbon_relic, "frostfall_toybox", 15, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(maxHp = 60, magic = 14), healingBonusPercent = 15, statusImmunityIds = setOf("freeze"))),
        bossRelic("ag_relic_eclipse_prism", AdventureGateEquipmentSlot.SHIELD, R.string.adventure_gate_item_eclipse_prism_relic, "starfall_citadel", 7, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(defense = 38, evasion = 8), incomingReductionPercent = 12, reflectDamagePercent = 10), weaknesses = setOf(AdventureGateElement.ARCANE), resistances = setOf(AdventureGateElement.LIGHT, AdventureGateElement.SHADOW)),
        bossRelic("ag_relic_regent_dream_key", AdventureGateEquipmentSlot.RING, R.string.adventure_gate_item_regent_dream_key_relic, "starfall_citadel", 15, AdventureGateEquipmentEffect(statBonus = AdventureGateStatBonus(maxHp = 80, maxMana = 50, magic = 18), reviveOncePercent = 25)),
        mysteryRelic()
    )

    val skills: List<AdventureGateSkillDefinition> = listOf(
        AdventureGateSkillDefinition("paw_strike", R.string.adventure_gate_skill_paw_strike, R.string.adventure_gate_skill_paw_strike_desc, AdventureGateSkillKind.ATTACK, AdventureGateElement.STRIKE, 0, 105, accuracyPercent = 100, unlockLevel = 1, path = AdventureGateSkillTreePath.ASSAULT, tier = 1),
        AdventureGateSkillDefinition("quick_claw", R.string.adventure_gate_skill_quick_claw, R.string.adventure_gate_skill_quick_claw_desc, AdventureGateSkillKind.ATTACK, AdventureGateElement.SLASH, 0, 118, accuracyPercent = 94, cooldownTurns = 1, unlockLevel = 3, path = AdventureGateSkillTreePath.ASSAULT, prerequisiteSkillIds = listOf("paw_strike"), tier = 2),
        AdventureGateSkillDefinition("bleeding_swipe", R.string.adventure_gate_skill_bleeding_swipe, R.string.adventure_gate_skill_bleeding_swipe_desc, AdventureGateSkillKind.ATTACK, AdventureGateElement.SLASH, 0, 124, accuracyPercent = 92, cooldownTurns = 1, unlockLevel = 5, path = AdventureGateSkillTreePath.ASSAULT, prerequisiteSkillIds = listOf("quick_claw"), tier = 3, status = bleedStatus(), statusChancePercent = 65),
        AdventureGateSkillDefinition("guard_break", R.string.adventure_gate_skill_guard_break, R.string.adventure_gate_skill_guard_break_desc, AdventureGateSkillKind.ATTACK, AdventureGateElement.STRIKE, 0, 132, accuracyPercent = 90, cooldownTurns = 2, unlockLevel = 7, path = AdventureGateSkillTreePath.ASSAULT, prerequisiteSkillIds = listOf("bleeding_swipe"), tier = 4),
        AdventureGateSkillDefinition("shield_cracker", R.string.adventure_gate_skill_shield_cracker, R.string.adventure_gate_skill_shield_cracker_desc, AdventureGateSkillKind.ATTACK, AdventureGateElement.STRIKE, 0, 118, accuracyPercent = 93, cooldownTurns = 2, unlockLevel = 9, path = AdventureGateSkillTreePath.ASSAULT, prerequisiteSkillIds = listOf("guard_break"), tier = 5, status = brittleStatus(), statusChancePercent = 75),
        AdventureGateSkillDefinition("twin_pounce", R.string.adventure_gate_skill_twin_pounce, R.string.adventure_gate_skill_twin_pounce_desc, AdventureGateSkillKind.ATTACK, AdventureGateElement.BEAST, 0, 148, accuracyPercent = 88, cooldownTurns = 2, unlockLevel = 13, path = AdventureGateSkillTreePath.ASSAULT, prerequisiteSkillIds = listOf("shield_cracker"), tier = 6),
        AdventureGateSkillDefinition("heroic_bash", R.string.adventure_gate_skill_heroic_bash, R.string.adventure_gate_skill_heroic_bash_desc, AdventureGateSkillKind.ATTACK, AdventureGateElement.METAL, 0, 165, accuracyPercent = 92, cooldownTurns = 3, unlockLevel = 21, path = AdventureGateSkillTreePath.ASSAULT, prerequisiteSkillIds = listOf("twin_pounce"), tier = 7),
        AdventureGateSkillDefinition("meteor_bounce", R.string.adventure_gate_skill_meteor_bounce, R.string.adventure_gate_skill_meteor_bounce_desc, AdventureGateSkillKind.ATTACK, AdventureGateElement.STONE, 0, 178, accuracyPercent = 86, cooldownTurns = 4, unlockLevel = 25, path = AdventureGateSkillTreePath.ASSAULT, prerequisiteSkillIds = listOf("heroic_bash"), tier = 8, status = slowStatus(), statusChancePercent = 35),
        AdventureGateSkillDefinition("star_fang", R.string.adventure_gate_skill_star_fang, R.string.adventure_gate_skill_star_fang_desc, AdventureGateSkillKind.ATTACK, AdventureGateElement.LIGHT, 0, 185, accuracyPercent = 95, cooldownTurns = 3, unlockLevel = 31, path = AdventureGateSkillTreePath.ASSAULT, prerequisiteSkillIds = listOf("meteor_bounce"), tier = 9),
        AdventureGateSkillDefinition("doomsday_bonk", R.string.adventure_gate_skill_doomsday_bonk, R.string.adventure_gate_skill_doomsday_bonk_desc, AdventureGateSkillKind.ATTACK, AdventureGateElement.ARCANE, 0, 220, accuracyPercent = 84, cooldownTurns = 4, unlockLevel = 43, path = AdventureGateSkillTreePath.ASSAULT, prerequisiteSkillIds = listOf("star_fang"), tier = 10),

        AdventureGateSkillDefinition("guard", R.string.adventure_gate_skill_guard, R.string.adventure_gate_skill_guard_desc, AdventureGateSkillKind.GUARD, AdventureGateElement.STRIKE, 0, 0, accuracyPercent = 100, unlockLevel = 1, path = AdventureGateSkillTreePath.SUPPORT, tier = 1, targetMode = AdventureGateTargetMode.SELF),
        AdventureGateSkillDefinition("heal_dew", R.string.adventure_gate_skill_heal_dew, R.string.adventure_gate_skill_heal_dew_desc, AdventureGateSkillKind.HEAL, AdventureGateElement.NATURE, 10, 34, accuracyPercent = 100, unlockLevel = 4, path = AdventureGateSkillTreePath.SUPPORT, prerequisiteSkillIds = listOf("guard"), tier = 2, targetMode = AdventureGateTargetMode.SINGLE_ALLY),
        AdventureGateSkillDefinition("bubble_ward", R.string.adventure_gate_skill_bubble_ward, R.string.adventure_gate_skill_bubble_ward_desc, AdventureGateSkillKind.GUARD, AdventureGateElement.WATER, 4, 0, accuracyPercent = 100, unlockLevel = 10, path = AdventureGateSkillTreePath.SUPPORT, prerequisiteSkillIds = listOf("heal_dew"), tier = 3, targetMode = AdventureGateTargetMode.SELF, status = wardStatus(), statusChancePercent = 100),
        AdventureGateSkillDefinition(SKELETON_HELPER_SKILL_ID, R.string.adventure_gate_skill_skeleton_helper, R.string.adventure_gate_skill_skeleton_helper_desc, AdventureGateSkillKind.SUMMON, AdventureGateElement.SHADOW, 40, 0, accuracyPercent = 100, unlockLevel = 10, path = AdventureGateSkillTreePath.MAGIC, prerequisiteSkillIds = listOf("spark"), tier = 3, targetMode = AdventureGateTargetMode.SELF),
        AdventureGateSkillDefinition("dream_mend", R.string.adventure_gate_skill_dream_mend, R.string.adventure_gate_skill_dream_mend_desc, AdventureGateSkillKind.HEAL, AdventureGateElement.NATURE, 16, 42, accuracyPercent = 100, unlockLevel = 16, path = AdventureGateSkillTreePath.SUPPORT, prerequisiteSkillIds = listOf("bubble_ward"), tier = 4, targetMode = AdventureGateTargetMode.SINGLE_ALLY, status = regenStatus(), statusChancePercent = 100),
        AdventureGateSkillDefinition("mana_shell", R.string.adventure_gate_skill_mana_shell, R.string.adventure_gate_skill_mana_shell_desc, AdventureGateSkillKind.GUARD, AdventureGateElement.ARCANE, 12, 0, accuracyPercent = 100, cooldownTurns = 3, unlockLevel = 23, path = AdventureGateSkillTreePath.SUPPORT, prerequisiteSkillIds = listOf("dream_mend"), tier = 5, targetMode = AdventureGateTargetMode.SELF),

        AdventureGateSkillDefinition("spark", R.string.adventure_gate_skill_spark, R.string.adventure_gate_skill_spark_desc, AdventureGateSkillKind.MAGIC, AdventureGateElement.STORM, 6, 120, accuracyPercent = 96, unlockLevel = 1, path = AdventureGateSkillTreePath.MAGIC, tier = 1, status = paralyzeStatus(), statusChancePercent = 35),
        AdventureGateSkillDefinition("fire_puff", R.string.adventure_gate_skill_fire_puff, R.string.adventure_gate_skill_fire_puff_desc, AdventureGateSkillKind.MAGIC, AdventureGateElement.FIRE, 9, 145, accuracyPercent = 94, unlockLevel = 6, path = AdventureGateSkillTreePath.MAGIC, prerequisiteSkillIds = listOf("spark"), tier = 2, status = burnStatus(), statusChancePercent = 70),
        AdventureGateSkillDefinition("frost_bell", R.string.adventure_gate_skill_frost_bell, R.string.adventure_gate_skill_frost_bell_desc, AdventureGateSkillKind.MAGIC, AdventureGateElement.ICE, 12, 155, accuracyPercent = 92, unlockLevel = 15, path = AdventureGateSkillTreePath.MAGIC, prerequisiteSkillIds = listOf("spark"), tier = 2, status = freezeStatus(), statusChancePercent = 55),
        AdventureGateSkillDefinition("tidal_mirror", R.string.adventure_gate_skill_tidal_mirror, R.string.adventure_gate_skill_tidal_mirror_desc, AdventureGateSkillKind.MAGIC, AdventureGateElement.WATER, 13, 150, accuracyPercent = 94, unlockLevel = 14, path = AdventureGateSkillTreePath.MAGIC, prerequisiteSkillIds = listOf("fire_puff"), tier = 3, status = slowStatus(), statusChancePercent = 70),
        AdventureGateSkillDefinition("moonbeam", R.string.adventure_gate_skill_moonbeam, R.string.adventure_gate_skill_moonbeam_desc, AdventureGateSkillKind.MAGIC, AdventureGateElement.LIGHT, 15, 175, accuracyPercent = 95, unlockLevel = 24, path = AdventureGateSkillTreePath.MAGIC, prerequisiteSkillIds = listOf("tidal_mirror"), tier = 4),
        AdventureGateSkillDefinition("ember_lullaby", R.string.adventure_gate_skill_ember_lullaby, R.string.adventure_gate_skill_ember_lullaby_desc, AdventureGateSkillKind.MAGIC, AdventureGateElement.FIRE, 17, 170, accuracyPercent = 93, unlockLevel = 18, path = AdventureGateSkillTreePath.MAGIC, prerequisiteSkillIds = listOf("tidal_mirror"), tier = 4, status = burnStatus(), statusChancePercent = 75),
        AdventureGateSkillDefinition("star_prism", R.string.adventure_gate_skill_star_prism, R.string.adventure_gate_skill_star_prism_desc, AdventureGateSkillKind.MAGIC, AdventureGateElement.LIGHT, 19, 188, accuracyPercent = 92, unlockLevel = 28, path = AdventureGateSkillTreePath.MAGIC, prerequisiteSkillIds = listOf("moonbeam", "ember_lullaby"), tier = 5, status = blindStatus(), statusChancePercent = 70),
        AdventureGateSkillDefinition("night_ribbon", R.string.adventure_gate_skill_night_ribbon, R.string.adventure_gate_skill_night_ribbon_desc, AdventureGateSkillKind.MAGIC, AdventureGateElement.SHADOW, 18, 195, accuracyPercent = 90, unlockLevel = 34, path = AdventureGateSkillTreePath.HEX, prerequisiteSkillIds = listOf("star_prism", "shadow_mist"), tier = 6, status = poisonStatus(stronger = true), statusChancePercent = 80),
        AdventureGateSkillDefinition("arcane_meteor", R.string.adventure_gate_skill_arcane_meteor, R.string.adventure_gate_skill_arcane_meteor_desc, AdventureGateSkillKind.MAGIC, AdventureGateElement.ARCANE, 25, 240, accuracyPercent = 86, unlockLevel = 45, path = AdventureGateSkillTreePath.MAGIC, prerequisiteSkillIds = listOf("night_ribbon"), tier = 7),

        AdventureGateSkillDefinition("thorn_snare", R.string.adventure_gate_skill_thorn_snare, R.string.adventure_gate_skill_thorn_snare_desc, AdventureGateSkillKind.MAGIC, AdventureGateElement.NATURE, 10, 105, accuracyPercent = 95, unlockLevel = 8, path = AdventureGateSkillTreePath.HEX, prerequisiteSkillIds = listOf("spark"), tier = 2, status = poisonStatus(), statusChancePercent = 80),
        AdventureGateSkillDefinition("shadow_mist", R.string.adventure_gate_skill_shadow_mist, R.string.adventure_gate_skill_shadow_mist_desc, AdventureGateSkillKind.MAGIC, AdventureGateElement.SHADOW, 14, 125, accuracyPercent = 92, unlockLevel = 18, path = AdventureGateSkillTreePath.HEX, prerequisiteSkillIds = listOf("thorn_snare"), tier = 3, status = blindStatus(), statusChancePercent = 65)
    )

    val monsters: List<AdventureGateMonsterDefinition> = listOf(
        monster("dewcap_slime", R.string.adventure_gate_monster_dewcap_slime, AdventureGateElement.WATER, null, setOf(AdventureGateElement.STORM, AdventureGateElement.NATURE), setOf(AdventureGateElement.WATER), 48, 0, 9, 8, 4, 7, 12),
        monster("sprig_paw", R.string.adventure_gate_monster_sprig_paw, AdventureGateElement.NATURE, null, setOf(AdventureGateElement.FIRE, AdventureGateElement.ICE), setOf(AdventureGateElement.NATURE), 56, 0, 11, 7, 5, 9, 14),
        monster("pebble_snail", R.string.adventure_gate_monster_pebble_snail, AdventureGateElement.STONE, null, setOf(AdventureGateElement.WATER, AdventureGateElement.NATURE), setOf(AdventureGateElement.STRIKE), 70, 0, 10, 6, 9, 4, 18),
        monster("bramble_guard", R.string.adventure_gate_monster_bramble_guard, AdventureGateElement.NATURE, AdventureGateElement.STONE, setOf(AdventureGateElement.FIRE, AdventureGateElement.ICE), setOf(AdventureGateElement.NATURE, AdventureGateElement.STRIKE), 180, 10, 18, 12, 15, 8, 55, true),
        monster("verdant_crown_moth", R.string.adventure_gate_monster_verdant_crown_moth, AdventureGateElement.NATURE, AdventureGateElement.ARCANE, setOf(AdventureGateElement.FIRE, AdventureGateElement.SHADOW), setOf(AdventureGateElement.NATURE, AdventureGateElement.LIGHT), 310, 24, 24, 28, 18, 14, 120, true),
        monster("cinder_pup", R.string.adventure_gate_monster_cinder_pup, AdventureGateElement.FIRE, null, setOf(AdventureGateElement.WATER, AdventureGateElement.ICE), setOf(AdventureGateElement.FIRE), 82, 16, 18, 12, 10, 14, 24),
        monster("brass_beetle", R.string.adventure_gate_monster_brass_beetle, AdventureGateElement.METAL, null, setOf(AdventureGateElement.STORM, AdventureGateElement.FIRE), setOf(AdventureGateElement.SLASH, AdventureGateElement.STRIKE), 95, 10, 16, 7, 18, 8, 28),
        monster("smoke_sprite", R.string.adventure_gate_monster_smoke_sprite, AdventureGateElement.FIRE, AdventureGateElement.SHADOW, setOf(AdventureGateElement.WATER, AdventureGateElement.LIGHT), setOf(AdventureGateElement.FIRE, AdventureGateElement.SHADOW), 76, 22, 13, 22, 8, 17, 30),
        monster("furnace_foreman", R.string.adventure_gate_monster_furnace_foreman, AdventureGateElement.FIRE, AdventureGateElement.METAL, setOf(AdventureGateElement.WATER, AdventureGateElement.STORM), setOf(AdventureGateElement.FIRE, AdventureGateElement.SLASH), 280, 20, 31, 18, 24, 14, 95, true),
        monster("molten_marionette", R.string.adventure_gate_monster_molten_marionette, AdventureGateElement.FIRE, AdventureGateElement.ARCANE, setOf(AdventureGateElement.WATER, AdventureGateElement.SHADOW), setOf(AdventureGateElement.FIRE, AdventureGateElement.ARCANE), 430, 30, 34, 38, 28, 18, 190, true),
        monster("bubble_jelly", R.string.adventure_gate_monster_bubble_jelly, AdventureGateElement.WATER, null, setOf(AdventureGateElement.STORM, AdventureGateElement.NATURE), setOf(AdventureGateElement.WATER), 112, 10, 20, 24, 12, 18, 38),
        monster("coral_crab", R.string.adventure_gate_monster_coral_crab, AdventureGateElement.WATER, AdventureGateElement.STONE, setOf(AdventureGateElement.STORM, AdventureGateElement.NATURE), setOf(AdventureGateElement.STRIKE, AdventureGateElement.WATER), 135, 8, 24, 14, 24, 11, 42),
        monster("pearl_wisp", R.string.adventure_gate_monster_pearl_wisp, AdventureGateElement.LIGHT, AdventureGateElement.WATER, setOf(AdventureGateElement.SHADOW, AdventureGateElement.STORM), setOf(AdventureGateElement.LIGHT, AdventureGateElement.WATER), 105, 20, 18, 30, 14, 20, 45),
        monster("tide_knuckle", R.string.adventure_gate_monster_tide_knuckle, AdventureGateElement.WATER, AdventureGateElement.BEAST, setOf(AdventureGateElement.STORM, AdventureGateElement.NATURE), setOf(AdventureGateElement.WATER, AdventureGateElement.STRIKE), 370, 28, 40, 20, 31, 18, 145, true),
        monster("glassfin_leviathan", R.string.adventure_gate_monster_glassfin_leviathan, AdventureGateElement.WATER, AdventureGateElement.ARCANE, setOf(AdventureGateElement.STORM, AdventureGateElement.SHADOW), setOf(AdventureGateElement.WATER, AdventureGateElement.ARCANE), 560, 42, 43, 48, 34, 22, 260, true),
        monster("gear_goblet", R.string.adventure_gate_monster_gear_goblet, AdventureGateElement.METAL, null, setOf(AdventureGateElement.STORM, AdventureGateElement.FIRE), setOf(AdventureGateElement.SLASH), 150, 10, 31, 12, 32, 15, 58),
        monster("cloud_pix", R.string.adventure_gate_monster_cloud_pix, AdventureGateElement.STORM, null, setOf(AdventureGateElement.ICE, AdventureGateElement.SHADOW), setOf(AdventureGateElement.STORM), 128, 26, 25, 38, 18, 28, 62),
        monster("magnet_mite", R.string.adventure_gate_monster_magnet_mite, AdventureGateElement.METAL, AdventureGateElement.STORM, setOf(AdventureGateElement.FIRE, AdventureGateElement.ARCANE), setOf(AdventureGateElement.STORM, AdventureGateElement.SLASH), 142, 22, 28, 30, 28, 24, 66),
        monster("brass_roc", R.string.adventure_gate_monster_brass_roc, AdventureGateElement.STORM, AdventureGateElement.METAL, setOf(AdventureGateElement.ICE, AdventureGateElement.FIRE), setOf(AdventureGateElement.STORM, AdventureGateElement.SLASH), 480, 34, 55, 34, 40, 30, 210, true),
        monster("chrono_chimera", R.string.adventure_gate_monster_chrono_chimera, AdventureGateElement.ARCANE, AdventureGateElement.METAL, setOf(AdventureGateElement.SHADOW, AdventureGateElement.STORM), setOf(AdventureGateElement.ARCANE, AdventureGateElement.STRIKE), 700, 54, 58, 62, 45, 32, 360, true),
        monster("ink_bat", R.string.adventure_gate_monster_ink_bat, AdventureGateElement.SHADOW, null, setOf(AdventureGateElement.LIGHT, AdventureGateElement.FIRE), setOf(AdventureGateElement.SHADOW), 185, 24, 38, 42, 24, 32, 80),
        monster("rune_mouse", R.string.adventure_gate_monster_rune_mouse, AdventureGateElement.ARCANE, null, setOf(AdventureGateElement.SHADOW, AdventureGateElement.STRIKE), setOf(AdventureGateElement.ARCANE), 175, 30, 34, 48, 26, 34, 84),
        monster("shelf_golem", R.string.adventure_gate_monster_shelf_golem, AdventureGateElement.NATURE, AdventureGateElement.STONE, setOf(AdventureGateElement.FIRE, AdventureGateElement.ICE), setOf(AdventureGateElement.NATURE, AdventureGateElement.STRIKE), 230, 12, 42, 20, 45, 16, 90),
        monster("bookmark_basilisk", R.string.adventure_gate_monster_bookmark_basilisk, AdventureGateElement.SHADOW, AdventureGateElement.NATURE, setOf(AdventureGateElement.LIGHT, AdventureGateElement.FIRE), setOf(AdventureGateElement.SHADOW, AdventureGateElement.NATURE), 620, 44, 68, 54, 52, 34, 300, true),
        monster("libram_lich", R.string.adventure_gate_monster_libram_lich, AdventureGateElement.ARCANE, AdventureGateElement.SHADOW, setOf(AdventureGateElement.LIGHT, AdventureGateElement.STRIKE), setOf(AdventureGateElement.ARCANE, AdventureGateElement.SHADOW), 840, 78, 61, 82, 52, 35, 480, true),
        monster("snow_button", R.string.adventure_gate_monster_snow_button, AdventureGateElement.ICE, null, setOf(AdventureGateElement.FIRE, AdventureGateElement.STRIKE), setOf(AdventureGateElement.ICE), 255, 28, 50, 48, 36, 30, 110),
        monster("ribbon_yeti", R.string.adventure_gate_monster_ribbon_yeti, AdventureGateElement.ICE, AdventureGateElement.BEAST, setOf(AdventureGateElement.FIRE, AdventureGateElement.LIGHT), setOf(AdventureGateElement.ICE), 285, 18, 62, 32, 42, 28, 118),
        monster("sugar_golem", R.string.adventure_gate_monster_sugar_golem, AdventureGateElement.ICE, AdventureGateElement.LIGHT, setOf(AdventureGateElement.FIRE, AdventureGateElement.SHADOW), setOf(AdventureGateElement.ICE, AdventureGateElement.LIGHT), 310, 34, 52, 56, 50, 24, 126),
        monster("nutcracker_knight", R.string.adventure_gate_monster_nutcracker_knight, AdventureGateElement.METAL, AdventureGateElement.ICE, setOf(AdventureGateElement.FIRE, AdventureGateElement.STORM), setOf(AdventureGateElement.SLASH, AdventureGateElement.ICE), 780, 40, 82, 46, 66, 32, 420, true),
        monster("aurora_wyrm", R.string.adventure_gate_monster_aurora_wyrm, AdventureGateElement.ICE, AdventureGateElement.ARCANE, setOf(AdventureGateElement.FIRE, AdventureGateElement.SHADOW), setOf(AdventureGateElement.ICE, AdventureGateElement.ARCANE), 1010, 86, 78, 96, 60, 40, 650, true),
        monster("star_imp", R.string.adventure_gate_monster_star_imp, AdventureGateElement.LIGHT, AdventureGateElement.ARCANE, setOf(AdventureGateElement.SHADOW), setOf(AdventureGateElement.LIGHT, AdventureGateElement.ARCANE), 340, 45, 70, 72, 44, 42, 150),
        monster("void_kiteling", R.string.adventure_gate_monster_void_kiteling, AdventureGateElement.SHADOW, null, setOf(AdventureGateElement.LIGHT), setOf(AdventureGateElement.SHADOW), 360, 38, 78, 64, 46, 45, 156),
        monster("prism_sentinel", R.string.adventure_gate_monster_prism_sentinel, AdventureGateElement.LIGHT, AdventureGateElement.METAL, setOf(AdventureGateElement.SHADOW, AdventureGateElement.STORM), setOf(AdventureGateElement.LIGHT, AdventureGateElement.SLASH), 410, 42, 74, 70, 70, 34, 170),
        monster("eclipse_herald", R.string.adventure_gate_monster_eclipse_herald, AdventureGateElement.LIGHT, AdventureGateElement.SHADOW, setOf(AdventureGateElement.ARCANE), setOf(AdventureGateElement.LIGHT, AdventureGateElement.SHADOW), 980, 72, 98, 105, 75, 48, 620, true),
        monster("astra_null_regent", R.string.adventure_gate_monster_astra_null_regent, AdventureGateElement.ARCANE, AdventureGateElement.SHADOW, setOf(AdventureGateElement.ARCANE), setOf(AdventureGateElement.LIGHT, AdventureGateElement.SHADOW), 1400, 110, 112, 128, 85, 52, 1000, true)
    )

    val enemyActions: List<AdventureGateEnemyActionDefinition> = listOf(
        enemyAttack("enemy_strike_tap", R.string.adventure_gate_enemy_action_strike_tap, AdventureGateElement.STRIKE, 96),
        enemyAttack("enemy_slash_nip", R.string.adventure_gate_enemy_action_slash_nip, AdventureGateElement.SLASH, 102),
        enemyAttack("enemy_beast_pounce", R.string.adventure_gate_enemy_action_beast_pounce, AdventureGateElement.BEAST, 108),
        enemyAttack("enemy_stone_bump", R.string.adventure_gate_enemy_action_stone_bump, AdventureGateElement.STONE, 104, status = brittleStatus(), statusChance = 20),
        enemyAttack("enemy_metal_clank", R.string.adventure_gate_enemy_action_metal_clank, AdventureGateElement.METAL, 108),
        enemyMagic("enemy_fire_spark", R.string.adventure_gate_enemy_action_fire_spark, AdventureGateElement.FIRE, 8, 112, status = burnStatus(), statusChance = 35),
        enemyMagic("enemy_water_bubble", R.string.adventure_gate_enemy_action_water_bubble, AdventureGateElement.WATER, 7, 105, status = slowStatus(), statusChance = 25),
        enemyMagic("enemy_ice_chime", R.string.adventure_gate_enemy_action_ice_chime, AdventureGateElement.ICE, 9, 112, status = freezeStatus(), statusChance = 28),
        enemyMagic("enemy_storm_zap", R.string.adventure_gate_enemy_action_storm_zap, AdventureGateElement.STORM, 8, 112, status = paralyzeStatus(), statusChance = 30),
        enemyMagic("enemy_nature_spore", R.string.adventure_gate_enemy_action_nature_spore, AdventureGateElement.NATURE, 7, 104, status = poisonStatus(), statusChance = 35),
        enemyMagic("enemy_light_flash", R.string.adventure_gate_enemy_action_light_flash, AdventureGateElement.LIGHT, 8, 108, status = blindStatus(), statusChance = 30),
        enemyMagic("enemy_shadow_hush", R.string.adventure_gate_enemy_action_shadow_hush, AdventureGateElement.SHADOW, 9, 110, status = blindStatus(), statusChance = 24),
        enemyMagic("enemy_arcane_rune", R.string.adventure_gate_enemy_action_arcane_rune, AdventureGateElement.ARCANE, 10, 118, status = weakenStatus(), statusChance = 30),
        enemySpecial("boss_thorn_bulwark", R.string.adventure_gate_boss_special_thorn_bulwark, AdventureGateElement.NATURE, 16, 128, status = brittleStatus(), secondaryStatus = wardStatus(), cooldown = 3),
        enemySpecial("boss_moon_pollen", R.string.adventure_gate_boss_special_moon_pollen, AdventureGateElement.ARCANE, 18, 132, status = poisonStatus(stronger = true), secondaryStatus = blindStatus(), cooldown = 3),
        enemySpecial("boss_overheat_order", R.string.adventure_gate_boss_special_overheat_order, AdventureGateElement.FIRE, 18, 135, status = burnStatus(stronger = true), secondaryStatus = weakenStatus(), cooldown = 3),
        enemySpecial("boss_string_lock", R.string.adventure_gate_boss_special_string_lock, AdventureGateElement.ARCANE, 20, 138, status = paralyzeStatus(), secondaryStatus = weakenStatus(), cooldown = 4),
        enemySpecial("boss_foam_uppercut", R.string.adventure_gate_boss_special_foam_uppercut, AdventureGateElement.WATER, 18, 136, status = slowStatus(), cooldown = 3),
        enemySpecial("boss_deep_tide_prism", R.string.adventure_gate_boss_special_deep_tide_prism, AdventureGateElement.ARCANE, 22, 140, status = slowStatus(), manaDamage = 8, cooldown = 4),
        enemySpecial("boss_storm_dive", R.string.adventure_gate_boss_special_storm_dive, AdventureGateElement.STORM, 18, 142, status = paralyzeStatus(), cooldown = 3),
        enemySpecial("boss_stolen_tick", R.string.adventure_gate_boss_special_stolen_tick, AdventureGateElement.ARCANE, 22, 136, status = slowStatus(stronger = true), cooldown = 3),
        enemySpecial("boss_page_stare", R.string.adventure_gate_boss_special_page_stare, AdventureGateElement.SHADOW, 20, 136, status = poisonStatus(stronger = true), secondaryStatus = blindStatus(), cooldown = 3),
        enemySpecial("boss_forbidden_index", R.string.adventure_gate_boss_special_forbidden_index, AdventureGateElement.ARCANE, 24, 144, status = weakenStatus(), secondaryStatus = blindStatus(), cooldown = 4),
        enemySpecial("boss_tin_march", R.string.adventure_gate_boss_special_tin_march, AdventureGateElement.METAL, 20, 142, status = brittleStatus(), secondaryStatus = freezeStatus(), cooldown = 3),
        enemySpecial("boss_polar_halo", R.string.adventure_gate_boss_special_polar_halo, AdventureGateElement.ICE, 24, 146, status = freezeStatus(), manaDamage = 10, cooldown = 4),
        enemySpecial("boss_twin_eclipse", R.string.adventure_gate_boss_special_twin_eclipse, AdventureGateElement.LIGHT, 22, 145, status = blindStatus(), secondaryStatus = weakenStatus(), cooldown = 3),
        enemySpecial("boss_ending_lock", R.string.adventure_gate_boss_special_ending_lock, AdventureGateElement.ARCANE, 30, 162, status = weakenStatus(), secondaryStatus = slowStatus(stronger = true), cooldown = 4)
    )

    private val monsterById = monsters.associateBy { it.id }
    private val enemyActionById = enemyActions.associateBy { it.id }

    val worlds: List<AdventureGateWorldDefinition> by lazy {
        listOf(
        world("sproutvale_gate", R.string.adventure_gate_world_sproutvale, R.string.adventure_gate_world_sproutvale_desc, "adventure_gate_sproutvale.png", listOf("dewcap_slime", "sprig_paw", "pebble_snail", "bramble_guard", "verdant_crown_moth")),
        world("ember_toyworks", R.string.adventure_gate_world_ember_toyworks, R.string.adventure_gate_world_ember_toyworks_desc, "adventure_gate_ember_toyworks.png", listOf("cinder_pup", "brass_beetle", "smoke_sprite", "furnace_foreman", "molten_marionette")),
        world("bubbleglass_reef", R.string.adventure_gate_world_bubbleglass_reef, R.string.adventure_gate_world_bubbleglass_reef_desc, "adventure_gate_bubbleglass_reef.png", listOf("bubble_jelly", "coral_crab", "pearl_wisp", "tide_knuckle", "glassfin_leviathan")),
        world("clockwork_cloudway", R.string.adventure_gate_world_clockwork_cloudway, R.string.adventure_gate_world_clockwork_cloudway_desc, "adventure_gate_clockwork_cloudway.png", listOf("gear_goblet", "cloud_pix", "magnet_mite", "brass_roc", "chrono_chimera")),
        world("moonmoss_library", R.string.adventure_gate_world_moonmoss_library, R.string.adventure_gate_world_moonmoss_library_desc, "adventure_gate_moonmoss_library.png", listOf("ink_bat", "rune_mouse", "shelf_golem", "bookmark_basilisk", "libram_lich")),
        world("frostfall_toybox", R.string.adventure_gate_world_frostfall_toybox, R.string.adventure_gate_world_frostfall_toybox_desc, "adventure_gate_frostfall_toybox.png", listOf("snow_button", "ribbon_yeti", "sugar_golem", "nutcracker_knight", "aurora_wyrm")),
        world("starfall_citadel", R.string.adventure_gate_world_starfall_citadel, R.string.adventure_gate_world_starfall_citadel_desc, "adventure_gate_starfall_citadel.png", listOf("star_imp", "void_kiteling", "prism_sentinel", "eclipse_herald", "astra_null_regent"))
        )
    }

    private val worldById by lazy { worlds.associateBy { it.id } }
    private val skillById = skills.associateBy { it.id }
    private val statusById = statuses.associateBy { it.id }
    private val supplyById = supplies.associateBy { it.id }
    private val recipeById = recipes.associateBy { it.id }
    private val recipeBySupplyId = recipes.associateBy { it.supplyId }
    private val equipmentById = equipment.associateBy { it.id }

    fun monster(id: String): AdventureGateMonsterDefinition =
        monsterById.getValue(id)

    fun world(id: String): AdventureGateWorldDefinition =
        worldById.getValue(id)

    fun skill(id: String): AdventureGateSkillDefinition =
        skillById.getValue(id)

    fun status(id: String): AdventureGateStatusDefinition =
        statusById.getValue(id)

    fun enemyAction(id: String): AdventureGateEnemyActionDefinition =
        enemyActionById.getValue(id)

    fun supply(id: String): AdventureGateSupplyDefinition? =
        supplyById[id]

    fun recipe(id: String): AdventureGateRecipeDefinition? =
        recipeById[id]

    fun recipeForSupply(supplyId: String): AdventureGateRecipeDefinition? =
        recipeBySupplyId[supplyId]

    fun recipeIdForSupply(supplyId: String): String = "recipe_$supplyId"

    fun supplyForRecipe(recipeId: String): AdventureGateSupplyDefinition? =
        recipe(recipeId)?.let { supply(it.supplyId) }

    fun equipment(id: String): AdventureGateEquipmentDefinition? =
        equipmentById[id]

    fun isAdventureGateItemId(id: String?): Boolean =
        id != null && (supplyById.containsKey(id) || equipmentById.containsKey(id))

    fun shopEquipment(): List<AdventureGateEquipmentDefinition> =
        equipment.filterNot { it.uniqueDrop }

    fun bossRelics(): List<AdventureGateEquipmentDefinition> =
        equipment.filter { it.uniqueDrop }

    fun bossRelicFor(worldId: String, phaseNumber: Int): AdventureGateEquipmentDefinition? =
        equipment.firstOrNull { it.bossDropWorldId == worldId && it.bossDropPhase == phaseNumber }

    fun loadoutForProfile(profile: AdventureGateProfile): AdventureGateEffectiveLoadout =
        AdventureGateEffectiveLoadout(
            weapon = profile.equippedWeaponId?.let(equipmentById::get)?.takeIf { it.slot == AdventureGateEquipmentSlot.WEAPON },
            shield = profile.equippedShieldId?.let(equipmentById::get)?.takeIf { it.slot == AdventureGateEquipmentSlot.SHIELD },
            ring = profile.equippedRingId?.let(equipmentById::get)?.takeIf { it.slot == AdventureGateEquipmentSlot.RING },
            relic = profile.equippedRelicId?.let(equipmentById::get)?.takeIf { it.slot == AdventureGateEquipmentSlot.RELIC }
        )

    fun effectiveStats(profile: AdventureGateProfile, educationLevel: Float = profile.educationLevel): AdventureGateStats {
        val base = AdventureGateCombatEngine.baseStatsForLevel(profile.level)
        val bonuses = loadoutForProfile(profile).equipment.map { it.effect.statBonus }
        val studyMagicBonus = (educationLevel.coerceAtLeast(0f) / 15f).toInt()
        val studyManaBonus = (educationLevel.coerceAtLeast(0f) / 10f).toInt() * 4
        return AdventureGateStats(
            maxHp = (base.maxHp + bonuses.sumOf { it.maxHp }).coerceAtLeast(1),
            maxMana = (base.maxMana + bonuses.sumOf { it.maxMana } + studyManaBonus).coerceAtLeast(0),
            attack = (base.attack + bonuses.sumOf { it.attack }).coerceAtLeast(1),
            magic = (base.magic + bonuses.sumOf { it.magic } + studyMagicBonus).coerceAtLeast(1),
            defense = (base.defense + bonuses.sumOf { it.defense }).coerceAtLeast(0),
            speed = (base.speed + bonuses.sumOf { it.speed }).coerceAtLeast(1),
            accuracy = (base.accuracy + bonuses.sumOf { it.accuracy }).coerceAtLeast(1),
            evasion = (base.evasion + bonuses.sumOf { it.evasion }).coerceAtLeast(0)
        )
    }

    fun phaseCoinReward(phase: AdventureGatePhaseDefinition, replay: Boolean = false): Int {
        phase.coinRewardOverride?.let { reward ->
            return if (replay) (reward / 10).coerceAtLeast(1) else reward
        }
        val worldIndex = worlds.indexOfFirst { it.id == phase.worldId }.coerceAtLeast(0)
        val extraEnemyCount = phase.waveMonsterIds.sumOf { wave -> (wave.size - 1).coerceAtLeast(0) }
        val bossBonus = when (phase.phaseNumber) {
            7 -> 250
            15 -> 500
            else -> 0
        }
        val baseReward = 50 + worldIndex * 90 + (phase.phaseNumber - 1) * 20 + extraEnemyCount * 25 + bossBonus
        return if (replay) {
            (baseReward / 10).coerceAtLeast(1)
        } else {
            baseReward
        }
    }

    fun phaseRetreatPenalty(phase: AdventureGatePhaseDefinition): Int {
        if (phase.worldId == NIGHT_ARENA_WORLD_ID) return 0
        val worldIndex = worlds.indexOfFirst { it.id == phase.worldId }.coerceAtLeast(0)
        val bossPenalty = when (phase.phaseNumber) {
            7 -> 35
            15 -> 65
            else -> 0
        }
        return (150 + worldIndex * 15 + (phase.phaseNumber - 1) * 5 + bossPenalty).coerceIn(150, 300)
    }

    fun phasePotionRewardChancePercent(phase: AdventureGatePhaseDefinition, replay: Boolean = false): Int {
        phase.potionRewardChanceOverride?.let { chance ->
            return if (replay) chance / 2 else chance
        }
        val baseChance = if (phase.isBoss) {
            30
        } else {
            val worldIndex = worlds.indexOfFirst { it.id == phase.worldId }.coerceAtLeast(0)
            (20 + ((worldIndex + phase.phaseNumber) % 11)).coerceIn(20, 30)
        }
        return if (replay) baseChance / 2 else baseChance
    }

    fun rewardSupplyPoolForWorld(worldIndex: Int): List<AdventureGateSupplyDefinition> =
        supplies.filter {
            it.kind in setOf(AdventureGateSupplyKind.HP, AdventureGateSupplyKind.MANA) &&
                it.unlockWorldIndex <= worldIndex.coerceIn(0, WORLD_COUNT - 1)
        }

    fun skillPointCost(skill: AdventureGateSkillDefinition): Int =
        if (skill.id in starterSkillIds) {
            0
        } else {
            when (skill.unlockLevel) {
                in 1..10 -> 1
                in 11..24 -> 2
                in 25..39 -> 3
                else -> 4
            }
        }

    fun elementIconAssetPath(element: AdventureGateElement): String =
        "tama/adventure_gate/type_icons/${element.name.lowercase()}.png"

    fun skillIconAssetPath(skill: AdventureGateSkillDefinition): String =
        if (skill.id in strategicSkillIds) {
            "tama/adventure_gate/skill_icons/${skill.id}.png"
        } else {
            elementIconAssetPath(skill.element)
        }

    fun effectFrameAssetPath(skillId: String, frameIndex: Int): String =
        "tama/adventure_gate/effects/$skillId/frame_$frameIndex.png"

    fun skillsForProfile(profile: AdventureGateProfile): List<AdventureGateSkillDefinition> {
        val ids = profile.equippedAttackIds + profile.equippedMagicIds
        return ids.mapNotNull(skillById::get)
    }

    fun isEquippableMagicSkill(skill: AdventureGateSkillDefinition): Boolean =
        skill.kind != AdventureGateSkillKind.ATTACK && skill.id != ALWAYS_GUARD_SKILL_ID

    fun learnedAttackIdsForLevel(level: Int): List<String> =
        skills.filter { it.kind == AdventureGateSkillKind.ATTACK && it.unlockLevel <= level }.map { it.id }

    fun learnedMagicIdsForLevel(level: Int): List<String> =
        skills.filter { it.kind != AdventureGateSkillKind.ATTACK && it.unlockLevel <= level }.map { it.id }

    private fun world(
        id: String,
        nameRes: Int,
        descriptionRes: Int,
        iconFilename: String,
        monsterIds: List<String>
    ): AdventureGateWorldDefinition {
        val phases = (1..PHASES_PER_WORLD).map { phase ->
            AdventureGatePhaseDefinition(
                worldId = id,
                phaseNumber = phase,
                waveMonsterIds = waveTemplate(id, monsterIds, phase),
                isBoss = phase == 7 || phase == 15,
                backgroundAssetPath = "tama/adventure_gate/worlds/$id/${backgroundAssetIdForPhase(phase)}.webp",
                storyRes = storyResForPhase(id, phase),
                bossRevealRes = bossRevealResForPhase(id, phase)
            )
        }
        return AdventureGateWorldDefinition(
            id = id,
            nameRes = nameRes,
            descriptionRes = descriptionRes,
            mapIconAssetPath = "tama/adventure_gate/icons/$iconFilename",
            worldMapAssetPath = "tama/adventure_gate/maps/$id.webp",
            phases = phases
        )
    }

    private fun waveTemplate(worldId: String, monsterIds: List<String>, phase: Int): List<List<String>> {
        val worldIndex = worldOrderIndex(worldId)
        val a = monsterIds[0]
        val b = monsterIds[1]
        val c = monsterIds[2]
        val midBoss = monsterIds[3]
        val finalBoss = monsterIds[4]
        val base = when (phase) {
            1 -> listOf(listOf(a))
            2 -> listOf(listOf(b))
            3 -> listOf(listOf(c))
            4 -> listOf(listOf(a, b))
            5 -> listOf(listOf(a, a))
            6 -> listOf(listOf(b, c))
            7 -> listOf(listOf(midBoss))
            8 -> listOf(listOf(a, c))
            9 -> listOf(listOf(b, b))
            10 -> listOf(listOf(a, b, c))
            11 -> listOf(listOf(c, c))
            12 -> listOf(listOf(a, b), listOf(c))
            13 -> listOf(listOf(b, c, c))
            14 -> listOf(listOf(a, b), listOf(a, c), listOf(b, c))
            15 -> listOf(listOf(a, c), listOf(finalBoss))
            else -> listOf(listOf(a))
        }
        return when {
            worldIndex >= 4 && phase !in setOf(1, 7, 15) -> base + listOf(listOf(b, c))
            worldIndex >= 2 && phase in setOf(5, 8, 9, 11, 13) -> base + listOf(listOf(a, c))
            worldIndex >= 1 && phase in setOf(6, 10, 12, 14) -> base + listOf(listOf(b, c))
            else -> base
        }
    }

    private fun worldOrderIndex(worldId: String): Int = when (worldId) {
        "sproutvale_gate" -> 0
        "ember_toyworks" -> 1
        "bubbleglass_reef" -> 2
        "clockwork_cloudway" -> 3
        "moonmoss_library" -> 4
        "frostfall_toybox" -> 5
        "starfall_citadel" -> 6
        else -> 0
    }

    fun backgroundAssetIdForPhase(phase: Int): String = when (phase) {
        in 1..4 -> "background_01"
        in 5..6 -> "background_02"
        7 -> "boss_mid"
        in 8..11 -> "background_03"
        in 12..14 -> "background_04"
        15 -> "boss_final"
        else -> "background_01"
    }

    private fun storyResForPhase(worldId: String, phase: Int): Int {
        val stories = when (worldId) {
            "sproutvale_gate" -> sproutvaleStories
            "ember_toyworks" -> emberToyworksStories
            "bubbleglass_reef" -> bubbleglassStories
            "clockwork_cloudway" -> clockworkStories
            "moonmoss_library" -> moonmossStories
            "frostfall_toybox" -> frostfallStories
            "starfall_citadel" -> starfallStories
            else -> sproutvaleStories
        }
        return stories[(phase - 1).coerceIn(0, PHASES_PER_WORLD - 1)]
    }

    private fun bossRevealResForPhase(worldId: String, phase: Int): Int? = when ("$worldId:$phase") {
        "sproutvale_gate:7" -> R.string.adventure_gate_reveal_sproutvale_07
        "sproutvale_gate:15" -> R.string.adventure_gate_reveal_sproutvale_15
        "ember_toyworks:7" -> R.string.adventure_gate_reveal_ember_07
        "ember_toyworks:15" -> R.string.adventure_gate_reveal_ember_15
        "bubbleglass_reef:7" -> R.string.adventure_gate_reveal_bubbleglass_07
        "bubbleglass_reef:15" -> R.string.adventure_gate_reveal_bubbleglass_15
        "clockwork_cloudway:7" -> R.string.adventure_gate_reveal_clockwork_07
        "clockwork_cloudway:15" -> R.string.adventure_gate_reveal_clockwork_15
        "moonmoss_library:7" -> R.string.adventure_gate_reveal_moonmoss_07
        "moonmoss_library:15" -> R.string.adventure_gate_reveal_moonmoss_15
        "frostfall_toybox:7" -> R.string.adventure_gate_reveal_frostfall_07
        "frostfall_toybox:15" -> R.string.adventure_gate_reveal_frostfall_15
        "starfall_citadel:7" -> R.string.adventure_gate_reveal_starfall_07
        "starfall_citadel:15" -> R.string.adventure_gate_reveal_starfall_15
        else -> null
    }

    private val sproutvaleStories = listOf(
        R.string.adventure_gate_story_sproutvale_01,
        R.string.adventure_gate_story_sproutvale_02,
        R.string.adventure_gate_story_sproutvale_03,
        R.string.adventure_gate_story_sproutvale_04,
        R.string.adventure_gate_story_sproutvale_05,
        R.string.adventure_gate_story_sproutvale_06,
        R.string.adventure_gate_story_sproutvale_07,
        R.string.adventure_gate_story_sproutvale_08,
        R.string.adventure_gate_story_sproutvale_09,
        R.string.adventure_gate_story_sproutvale_10,
        R.string.adventure_gate_story_sproutvale_11,
        R.string.adventure_gate_story_sproutvale_12,
        R.string.adventure_gate_story_sproutvale_13,
        R.string.adventure_gate_story_sproutvale_14,
        R.string.adventure_gate_story_sproutvale_15
    )

    private val emberToyworksStories = listOf(
        R.string.adventure_gate_story_ember_01,
        R.string.adventure_gate_story_ember_02,
        R.string.adventure_gate_story_ember_03,
        R.string.adventure_gate_story_ember_04,
        R.string.adventure_gate_story_ember_05,
        R.string.adventure_gate_story_ember_06,
        R.string.adventure_gate_story_ember_07,
        R.string.adventure_gate_story_ember_08,
        R.string.adventure_gate_story_ember_09,
        R.string.adventure_gate_story_ember_10,
        R.string.adventure_gate_story_ember_11,
        R.string.adventure_gate_story_ember_12,
        R.string.adventure_gate_story_ember_13,
        R.string.adventure_gate_story_ember_14,
        R.string.adventure_gate_story_ember_15
    )

    private val bubbleglassStories = listOf(
        R.string.adventure_gate_story_bubbleglass_01,
        R.string.adventure_gate_story_bubbleglass_02,
        R.string.adventure_gate_story_bubbleglass_03,
        R.string.adventure_gate_story_bubbleglass_04,
        R.string.adventure_gate_story_bubbleglass_05,
        R.string.adventure_gate_story_bubbleglass_06,
        R.string.adventure_gate_story_bubbleglass_07,
        R.string.adventure_gate_story_bubbleglass_08,
        R.string.adventure_gate_story_bubbleglass_09,
        R.string.adventure_gate_story_bubbleglass_10,
        R.string.adventure_gate_story_bubbleglass_11,
        R.string.adventure_gate_story_bubbleglass_12,
        R.string.adventure_gate_story_bubbleglass_13,
        R.string.adventure_gate_story_bubbleglass_14,
        R.string.adventure_gate_story_bubbleglass_15
    )

    private val clockworkStories = listOf(
        R.string.adventure_gate_story_clockwork_01,
        R.string.adventure_gate_story_clockwork_02,
        R.string.adventure_gate_story_clockwork_03,
        R.string.adventure_gate_story_clockwork_04,
        R.string.adventure_gate_story_clockwork_05,
        R.string.adventure_gate_story_clockwork_06,
        R.string.adventure_gate_story_clockwork_07,
        R.string.adventure_gate_story_clockwork_08,
        R.string.adventure_gate_story_clockwork_09,
        R.string.adventure_gate_story_clockwork_10,
        R.string.adventure_gate_story_clockwork_11,
        R.string.adventure_gate_story_clockwork_12,
        R.string.adventure_gate_story_clockwork_13,
        R.string.adventure_gate_story_clockwork_14,
        R.string.adventure_gate_story_clockwork_15
    )

    private val moonmossStories = listOf(
        R.string.adventure_gate_story_moonmoss_01,
        R.string.adventure_gate_story_moonmoss_02,
        R.string.adventure_gate_story_moonmoss_03,
        R.string.adventure_gate_story_moonmoss_04,
        R.string.adventure_gate_story_moonmoss_05,
        R.string.adventure_gate_story_moonmoss_06,
        R.string.adventure_gate_story_moonmoss_07,
        R.string.adventure_gate_story_moonmoss_08,
        R.string.adventure_gate_story_moonmoss_09,
        R.string.adventure_gate_story_moonmoss_10,
        R.string.adventure_gate_story_moonmoss_11,
        R.string.adventure_gate_story_moonmoss_12,
        R.string.adventure_gate_story_moonmoss_13,
        R.string.adventure_gate_story_moonmoss_14,
        R.string.adventure_gate_story_moonmoss_15
    )

    private val frostfallStories = listOf(
        R.string.adventure_gate_story_frostfall_01,
        R.string.adventure_gate_story_frostfall_02,
        R.string.adventure_gate_story_frostfall_03,
        R.string.adventure_gate_story_frostfall_04,
        R.string.adventure_gate_story_frostfall_05,
        R.string.adventure_gate_story_frostfall_06,
        R.string.adventure_gate_story_frostfall_07,
        R.string.adventure_gate_story_frostfall_08,
        R.string.adventure_gate_story_frostfall_09,
        R.string.adventure_gate_story_frostfall_10,
        R.string.adventure_gate_story_frostfall_11,
        R.string.adventure_gate_story_frostfall_12,
        R.string.adventure_gate_story_frostfall_13,
        R.string.adventure_gate_story_frostfall_14,
        R.string.adventure_gate_story_frostfall_15
    )

    private val starfallStories = listOf(
        R.string.adventure_gate_story_starfall_01,
        R.string.adventure_gate_story_starfall_02,
        R.string.adventure_gate_story_starfall_03,
        R.string.adventure_gate_story_starfall_04,
        R.string.adventure_gate_story_starfall_05,
        R.string.adventure_gate_story_starfall_06,
        R.string.adventure_gate_story_starfall_07,
        R.string.adventure_gate_story_starfall_08,
        R.string.adventure_gate_story_starfall_09,
        R.string.adventure_gate_story_starfall_10,
        R.string.adventure_gate_story_starfall_11,
        R.string.adventure_gate_story_starfall_12,
        R.string.adventure_gate_story_starfall_13,
        R.string.adventure_gate_story_starfall_14,
        R.string.adventure_gate_story_starfall_15
    )

    private fun monster(
        id: String,
        nameRes: Int,
        primary: AdventureGateElement,
        secondary: AdventureGateElement?,
        weaknesses: Set<AdventureGateElement>,
        resistances: Set<AdventureGateElement>,
        hp: Int,
        mana: Int,
        attack: Int,
        magic: Int,
        defense: Int,
        speed: Int,
        xpReward: Int,
        isBoss: Boolean = false
    ) = AdventureGateMonsterDefinition(
        id = id,
        nameRes = nameRes,
        primaryElement = primary,
        secondaryElement = secondary,
        weaknesses = weaknesses,
        resistances = resistances,
        stats = AdventureGateStats(hp, mana, attack, magic, defense, speed),
        xpReward = xpReward,
        isBoss = isBoss,
        assetBasePath = "tama/adventure_gate/monsters/$id",
        attackActionIds = defaultAttackActions(primary, secondary).take(2),
        magicActionIds = if (mana > 0) defaultMagicActions(primary, secondary).take(2) else emptyList(),
        specialActionId = bossSpecialForMonster(id)
    )

    private fun bossSpecialForMonster(id: String): String? = when (id) {
        "bramble_guard" -> "boss_thorn_bulwark"
        "verdant_crown_moth" -> "boss_moon_pollen"
        "furnace_foreman" -> "boss_overheat_order"
        "molten_marionette" -> "boss_string_lock"
        "tide_knuckle" -> "boss_foam_uppercut"
        "glassfin_leviathan" -> "boss_deep_tide_prism"
        "brass_roc" -> "boss_storm_dive"
        "chrono_chimera" -> "boss_stolen_tick"
        "bookmark_basilisk" -> "boss_page_stare"
        "libram_lich" -> "boss_forbidden_index"
        "nutcracker_knight" -> "boss_tin_march"
        "aurora_wyrm" -> "boss_polar_halo"
        "eclipse_herald" -> "boss_twin_eclipse"
        "astra_null_regent" -> "boss_ending_lock"
        else -> null
    }

    private fun defaultAttackActions(primary: AdventureGateElement, secondary: AdventureGateElement?): List<String> =
        listOfNotNull(attackActionFor(primary), secondary?.let(::attackActionFor), "enemy_strike_tap").distinct()

    private fun defaultMagicActions(primary: AdventureGateElement, secondary: AdventureGateElement?): List<String> =
        listOfNotNull(magicActionFor(primary), secondary?.let(::magicActionFor)).distinct()

    private fun attackActionFor(element: AdventureGateElement): String = when (element) {
        AdventureGateElement.SLASH -> "enemy_slash_nip"
        AdventureGateElement.STONE -> "enemy_stone_bump"
        AdventureGateElement.METAL -> "enemy_metal_clank"
        AdventureGateElement.BEAST -> "enemy_beast_pounce"
        else -> "enemy_strike_tap"
    }

    private fun magicActionFor(element: AdventureGateElement): String? = when (element) {
        AdventureGateElement.FIRE -> "enemy_fire_spark"
        AdventureGateElement.WATER -> "enemy_water_bubble"
        AdventureGateElement.ICE -> "enemy_ice_chime"
        AdventureGateElement.STORM -> "enemy_storm_zap"
        AdventureGateElement.NATURE -> "enemy_nature_spore"
        AdventureGateElement.LIGHT -> "enemy_light_flash"
        AdventureGateElement.SHADOW -> "enemy_shadow_hush"
        AdventureGateElement.ARCANE -> "enemy_arcane_rune"
        AdventureGateElement.METAL -> "enemy_arcane_rune"
        else -> null
    }

    private fun enemyAttack(
        id: String,
        nameRes: Int,
        element: AdventureGateElement,
        power: Int,
        status: AdventureGateStatusEffect? = null,
        statusChance: Int = if (status != null) 25 else 0
    ) = AdventureGateEnemyActionDefinition(
        id = id,
        nameRes = nameRes,
        kind = AdventureGateSkillKind.ATTACK,
        element = element,
        manaCost = 0,
        power = power,
        accuracyPercent = 90,
        status = status,
        statusChancePercent = statusChance
    )

    private fun enemyMagic(
        id: String,
        nameRes: Int,
        element: AdventureGateElement,
        manaCost: Int,
        power: Int,
        status: AdventureGateStatusEffect? = null,
        statusChance: Int = if (status != null) 35 else 0
    ) = AdventureGateEnemyActionDefinition(
        id = id,
        nameRes = nameRes,
        kind = AdventureGateSkillKind.MAGIC,
        element = element,
        manaCost = manaCost,
        power = power,
        accuracyPercent = 90,
        status = status,
        statusChancePercent = statusChance
    )

    private fun enemySpecial(
        id: String,
        nameRes: Int,
        element: AdventureGateElement,
        manaCost: Int,
        power: Int,
        status: AdventureGateStatusEffect?,
        secondaryStatus: AdventureGateStatusEffect? = null,
        manaDamage: Int = 0,
        cooldown: Int
    ) = AdventureGateEnemyActionDefinition(
        id = id,
        nameRes = nameRes,
        kind = AdventureGateSkillKind.MAGIC,
        element = element,
        manaCost = manaCost,
        power = power,
        accuracyPercent = 95,
        cooldownTurns = cooldown,
        status = status,
        statusChancePercent = 85,
        secondaryStatus = secondaryStatus,
        secondaryStatusChancePercent = if (secondaryStatus != null) 45 else 0,
        manaDamage = manaDamage
    )

    private fun poisonStatus(stronger: Boolean = false) =
        AdventureGateStatusEffect("poison", turnsRemaining = 0, damagePerTurn = if (stronger) 11 else 7)

    private fun burnStatus(stronger: Boolean = false) =
        AdventureGateStatusEffect("burn", turnsRemaining = 0, damagePerTurn = if (stronger) 9 else 5, attackMultiplierPercent = 90)

    private fun bleedStatus() =
        AdventureGateStatusEffect("bleed", turnsRemaining = 0, damagePerTurn = 7, physicalDamageTakenBonusPercent = 10)

    private fun freezeStatus() =
        AdventureGateStatusEffect("freeze", turnsRemaining = 0, skipTurnChancePercent = 35, speedMultiplierPercent = 80)

    private fun paralyzeStatus() =
        AdventureGateStatusEffect("paralyze", turnsRemaining = 0, skipTurnChancePercent = 25, evasionDelta = -8)

    private fun blindStatus() =
        AdventureGateStatusEffect("blind", turnsRemaining = 0, accuracyDelta = -25)

    private fun slowStatus(stronger: Boolean = false) =
        AdventureGateStatusEffect("slow", turnsRemaining = 0, speedMultiplierPercent = if (stronger) 60 else 70)

    private fun weakenStatus() =
        AdventureGateStatusEffect("weaken", turnsRemaining = 0, attackMultiplierPercent = 85, magicMultiplierPercent = 85)

    private fun brittleStatus() =
        AdventureGateStatusEffect("brittle", turnsRemaining = 0, defenseMultiplierPercent = 75, incomingDamageBonusPercent = 10)

    private fun regenStatus() =
        AdventureGateStatusEffect("regen", turnsRemaining = 0, hpRegenPercent = 8)

    private fun wardStatus(stronger: Boolean = false) =
        AdventureGateStatusEffect("ward", turnsRemaining = 0, incomingReductionPercent = if (stronger) 28 else 18, manaRegenFlat = 4)

    private fun supply(
        id: String,
        kind: AdventureGateSupplyKind,
        nameRes: Int,
        amount: Int,
        price: Int,
        unlockWorldIndex: Int
    ) = AdventureGateSupplyDefinition(
        id = id,
        kind = kind,
        nameRes = nameRes,
        amount = amount,
        price = price,
        unlockWorldIndex = unlockWorldIndex,
        assetPath = "tama/adventure_gate/items/$id.png"
    )

    private fun recipe(
        supplyId: String,
        vararg ingredientItemIds: String
    ): AdventureGateRecipeDefinition {
        val supply = supplies.first { it.id == supplyId }
        return AdventureGateRecipeDefinition(
            id = recipeIdForSupply(supplyId),
            supplyId = supplyId,
            price = supply.price * 2,
            unlockWorldIndex = supply.unlockWorldIndex,
            ingredientItemIds = ingredientItemIds.toList()
        )
    }

    private fun equipment(
        id: String,
        slot: AdventureGateEquipmentSlot,
        nameRes: Int,
        price: Int,
        unlockWorldIndex: Int,
        effect: AdventureGateEquipmentEffect,
        weaknesses: Set<AdventureGateElement> = emptySet(),
        resistances: Set<AdventureGateElement> = emptySet()
    ) = AdventureGateEquipmentDefinition(
        id = id,
        slot = slot,
        nameRes = nameRes,
        price = price,
        unlockWorldIndex = unlockWorldIndex,
        assetPath = "tama/adventure_gate/items/$id.png",
        effect = effect,
        petWeaknesses = weaknesses,
        petResistances = resistances
    )

    @Suppress("UNUSED_PARAMETER")
    private fun bossRelic(
        id: String,
        slot: AdventureGateEquipmentSlot,
        nameRes: Int,
        worldId: String,
        phaseNumber: Int,
        effect: AdventureGateEquipmentEffect,
        weaknesses: Set<AdventureGateElement> = emptySet(),
        resistances: Set<AdventureGateElement> = emptySet()
    ) = AdventureGateEquipmentDefinition(
        id = id,
        slot = AdventureGateEquipmentSlot.RELIC,
        nameRes = nameRes,
        price = 0,
        unlockWorldIndex = WORLD_COUNT,
        assetPath = "tama/adventure_gate/items/$id.png",
        effect = effect,
        petWeaknesses = weaknesses,
        petResistances = resistances,
        bossDropWorldId = worldId,
        bossDropPhase = phaseNumber
    )

    private fun mysteryRelic() = AdventureGateEquipmentDefinition(
        id = MYSTERY_RELIC_ID,
        slot = AdventureGateEquipmentSlot.RELIC,
        nameRes = R.string.adventure_gate_item_nexum_heart_relic,
        price = 0,
        unlockWorldIndex = WORLD_COUNT,
        assetPath = "tama/adventure_gate/items/$MYSTERY_RELIC_ID.png",
        effect = AdventureGateEquipmentEffect(
            statBonus = AdventureGateStatBonus(maxHp = 50, maxMana = 50),
            turnHpRegenPercent = 5,
            turnManaRegenPercent = 5
        ),
        mysteryDrop = true
    )
}
