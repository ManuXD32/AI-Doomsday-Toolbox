package com.example.llamadroid.tama.rpg

import com.example.llamadroid.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdventureGateCatalogTest {
    private fun assetExists(assetPath: String): Boolean =
        listOf(File("src/main/assets"), File("app/src/main/assets")).any { File(it, assetPath).isFile }

    @Test
    fun `catalog has seven worlds with fifteen phases each`() {
        assertEquals(7, AdventureGateCatalog.worlds.size)
        AdventureGateCatalog.worlds.forEach { world ->
            assertEquals(15, world.phases.size)
            assertEquals(2, world.phases.count { it.isBoss })
            assertTrue(world.worldMapAssetPath.endsWith("${world.id}.webp"))
        }
    }

    @Test
    fun `bosses are only at phases seven and fifteen`() {
        AdventureGateCatalog.worlds.forEach { world ->
            val bossPhases = world.phases.filter { it.isBoss }.map { it.phaseNumber }
            assertEquals(listOf(7, 15), bossPhases)
        }
    }

    @Test
    fun `regular phases reuse backgrounds while bosses have special backgrounds`() {
        AdventureGateCatalog.worlds.forEach { world ->
            assertEquals(6, world.phases.map { it.backgroundAssetPath }.toSet().size)
            assertEquals(
                world.phases[0].backgroundAssetPath,
                world.phases[3].backgroundAssetPath
            )
            assertEquals(
                world.phases[4].backgroundAssetPath,
                world.phases[5].backgroundAssetPath
            )
            assertEquals(
                world.phases[7].backgroundAssetPath,
                world.phases[10].backgroundAssetPath
            )
            assertEquals(
                world.phases[11].backgroundAssetPath,
                world.phases[13].backgroundAssetPath
            )
            assertTrue(world.phases[6].backgroundAssetPath.endsWith("boss_mid.webp"))
            assertTrue(world.phases[14].backgroundAssetPath.endsWith("boss_final.webp"))
            assertTrue(world.phases.none { "/phase_" in it.backgroundAssetPath })
        }
    }

    @Test
    fun `catalog has at least thirty five enemies and valid phase references`() {
        assertTrue(AdventureGateCatalog.monsters.size >= 35)
        val monsterIds = AdventureGateCatalog.monsters.map { it.id }.toSet()

        AdventureGateCatalog.worlds.flatMap { it.phases }.forEach { phase ->
            assertTrue(phase.backgroundAssetPath.endsWith(".webp"))
            assertTrue("Missing story for ${phase.worldId} ${phase.phaseNumber}", phase.storyRes != 0)
            if (phase.isBoss) {
                assertTrue("Missing boss reveal for ${phase.worldId} ${phase.phaseNumber}", phase.bossRevealRes != null)
            }
            assertFalse(phase.waveMonsterIds.isEmpty())
            phase.waveMonsterIds.forEach { wave ->
                assertTrue(
                    "Wave ${phase.worldId} ${phase.phaseNumber} has too many visible monsters",
                    wave.size <= AdventureGateCatalog.MAX_ENEMIES_PER_WAVE
                )
            }
            phase.waveMonsterIds.flatten().forEach { monsterId ->
                assertTrue("Missing monster $monsterId", monsterId in monsterIds)
            }
        }
    }

    @Test
    fun `phase bosses reference boss monsters and regular phases do not`() {
        AdventureGateCatalog.worlds.forEach { world ->
            world.phases.forEach { phase ->
                val phaseMonsters = phase.waveMonsterIds.flatten().map(AdventureGateCatalog::monster)
                if (phase.phaseNumber == 7 || phase.phaseNumber == 15) {
                    assertTrue(
                        "Boss phase ${world.id} ${phase.phaseNumber} must contain a boss",
                        phaseMonsters.any { it.isBoss }
                    )
                } else {
                    assertTrue(
                        "Regular phase ${world.id} ${phase.phaseNumber} should not contain bosses",
                        phaseMonsters.none { it.isBoss }
                    )
                }
            }
        }
    }

    @Test
    fun `later worlds add extra wave pressure without exceeding visible enemy cap`() {
        val worldOnePhaseSix = AdventureGateCatalog.world("sproutvale_gate").phases[5]
        val worldTwoPhaseSix = AdventureGateCatalog.world("ember_toyworks").phases[5]
        val worldFivePhaseFive = AdventureGateCatalog.world("moonmoss_library").phases[4]

        assertTrue(worldTwoPhaseSix.waveMonsterIds.size > worldOnePhaseSix.waveMonsterIds.size)
        assertTrue(worldFivePhaseFive.waveMonsterIds.size > worldOnePhaseSix.waveMonsterIds.size)
        AdventureGateCatalog.worlds.flatMap { it.phases }.forEach { phase ->
            phase.waveMonsterIds.forEach { wave ->
                assertTrue(wave.size <= AdventureGateCatalog.MAX_ENEMIES_PER_WAVE)
            }
        }
    }

    @Test
    fun `smoke sprite id keeps compatibility while localized name changes`() {
        assertEquals(R.string.adventure_gate_monster_smoke_sprite, AdventureGateCatalog.monster("smoke_sprite").nameRes)
        assertEquals("Soot Wisp", stringXmlValue("values", "adventure_gate_monster_smoke_sprite"))
        assertEquals("Brizna de Hollín", stringXmlValue("values-es", "adventure_gate_monster_smoke_sprite"))
    }

    @Test
    fun `ember regular enemies have mana and magic actions`() {
        listOf("cinder_pup", "brass_beetle", "smoke_sprite").forEach { monsterId ->
            val monster = AdventureGateCatalog.monster(monsterId)
            assertTrue("$monsterId should have mana in world 2", monster.stats.maxMana > 0)
            assertTrue("$monsterId should have magic actions in world 2", monster.magicActionIds.isNotEmpty())
        }
    }

    @Test
    fun `monsters have names stats elements weaknesses resistances and sprite paths`() {
        val playerElements = AdventureGateCatalog.skills.map { it.element }.toSet()
        AdventureGateCatalog.monsters.forEach { monster ->
            assertTrue("Missing name for ${monster.id}", monster.nameRes != 0)
            assertTrue("${monster.id} should have positive HP", monster.stats.maxHp > 0)
            assertTrue("${monster.id} should have positive attack", monster.stats.attack > 0)
            assertTrue("${monster.id} should have positive magic", monster.stats.magic > 0)
            assertTrue("${monster.id} should have positive defense", monster.stats.defense > 0)
            assertTrue("${monster.id} should have positive speed", monster.stats.speed > 0)
            assertTrue("${monster.id} should reward XP", monster.xpReward > 0)
            assertTrue("${monster.id} needs at least one weakness", monster.weaknesses.isNotEmpty())
            assertTrue("${monster.id} needs at least one resistance", monster.resistances.isNotEmpty())
            assertTrue(
                "${monster.id} should not be both weak and resistant to the same element",
                monster.weaknesses.intersect(monster.resistances).isEmpty()
            )
            assertTrue(
                "${monster.id} must be weak to at least one player attack or magic element",
                monster.weaknesses.any { it in playerElements }
            )
            assertTrue(monster.assetBasePath.endsWith(monster.id))
        }
    }

    private fun stringXmlValue(folder: String, name: String): String {
        val file = listOf(File("src/main/res/$folder/strings.xml"), File("app/src/main/res/$folder/strings.xml"))
            .first(File::isFile)
        val regex = Regex("""<string name="$name"(?:\s+[^>]*)?>(.+?)</string>""")
        return regex.find(file.readText())?.groupValues?.get(1)
            ?: error("Missing string $name in $folder")
    }

    @Test
    fun `skills cover attacks magic mana costs unlocks and asset ids`() {
        val skillIds = AdventureGateCatalog.skills.map { it.id }
        assertEquals(skillIds.size, skillIds.toSet().size)
        assertTrue(AdventureGateCatalog.startingAttackIds.all { it in skillIds })
        assertTrue(AdventureGateCatalog.startingMagicIds.all { it in skillIds })
        assertTrue(AdventureGateCatalog.skills.any { it.kind == AdventureGateSkillKind.MAGIC && it.manaCost > 0 })
        assertTrue(AdventureGateCatalog.skills.any { it.kind == AdventureGateSkillKind.HEAL && it.manaCost > 0 })
        assertTrue(AdventureGateCatalog.skills.any { it.kind == AdventureGateSkillKind.GUARD })
        assertTrue(AdventureGateCatalog.skills.any { it.status?.damagePerTurn ?: 0 > 0 })
        assertTrue(AdventureGateCatalog.skills.any { it.status?.skipTurnChancePercent ?: 0 > 0 })
        AdventureGateCatalog.skills.forEach { skill ->
            assertTrue("Missing skill name for ${skill.id}", skill.nameRes != 0)
            assertTrue("Missing skill description for ${skill.id}", skill.descriptionRes != 0)
            assertTrue("${skill.id} unlock level must be positive", skill.unlockLevel > 0)
            assertTrue("${skill.id} power should be non-negative", skill.power >= 0)
            assertTrue(AdventureGateCatalog.elementIconAssetPath(skill.element).endsWith(".png"))
            if (skill.kind == AdventureGateSkillKind.ATTACK) {
                assertEquals("${skill.id} attack should not cost mana", 0, skill.manaCost)
            } else {
                assertTrue("${skill.id} mana cost should be non-negative", skill.manaCost >= 0)
            }
        }
    }

    @Test
    fun `skills are organized into prerequisite tree paths`() {
        assertEquals(
            AdventureGateSkillTreePath.entries.toSet(),
            AdventureGateCatalog.skills.map { it.path }.toSet()
        )
        val skillsById = AdventureGateCatalog.skills.associateBy { it.id }
        AdventureGateCatalog.skills.forEach { skill ->
            skill.prerequisiteSkillIds.forEach { prerequisite ->
                val prerequisiteSkill = skillsById[prerequisite]
                assertTrue("$skill requires unknown prerequisite $prerequisite", prerequisiteSkill != null)
                assertTrue(
                    "$skill should not require later-level prerequisite $prerequisite",
                    (prerequisiteSkill?.unlockLevel ?: 0) <= skill.unlockLevel
                )
            }
            if (skill.id in AdventureGateCatalog.starterSkillIds) {
                assertTrue(skill.prerequisiteSkillIds.isEmpty())
                assertEquals(0, AdventureGateCatalog.skillPointCost(skill))
            }
        }
        assertFalse("tidal_mirror should come before frost_bell", "frost_bell" in AdventureGateCatalog.skill("tidal_mirror").prerequisiteSkillIds)
        assertEquals(listOf("fire_puff"), AdventureGateCatalog.skill("tidal_mirror").prerequisiteSkillIds)
    }

    @Test
    fun `new strategic skills have generated icons and effect frames`() {
        AdventureGateCatalog.strategicSkillIds.forEach { skillId ->
            val skill = AdventureGateCatalog.skill(skillId)
            assertTrue("Missing generated skill icon for $skillId", assetExists(AdventureGateCatalog.skillIconAssetPath(skill)))
            repeat(3) { frame ->
                assertTrue(
                    "Missing generated effect frame $frame for $skillId",
                    assetExists(AdventureGateCatalog.effectFrameAssetPath(skillId, frame))
                )
            }
        }
    }

    @Test
    fun `enemies have limited action loadouts and bosses have specials`() {
        AdventureGateCatalog.monsters.forEach { monster ->
            assertTrue("${monster.id} has too many attacks", monster.attackActionIds.size <= 2)
            assertTrue("${monster.id} has too many magic actions", monster.magicActionIds.size <= 2)
            monster.attackActionIds.forEach { AdventureGateCatalog.enemyAction(it) }
            monster.magicActionIds.forEach { AdventureGateCatalog.enemyAction(it) }
            if (monster.isBoss) {
                assertTrue("${monster.id} needs a boss special", monster.specialActionId != null)
                monster.specialActionId?.let { AdventureGateCatalog.enemyAction(it) }
            } else {
                assertEquals(null, monster.specialActionId)
            }
        }
    }

    @Test
    fun `status catalog has strategy status icons`() {
        assertEquals(
            setOf("poison", "burn", "freeze", "paralyze", "bleed", "blind", "slow", "weaken", "brittle", "regen", "ward"),
            AdventureGateCatalog.statuses.map { it.id }.toSet()
        )
        AdventureGateCatalog.statuses.forEach { status ->
            assertTrue(status.nameRes != 0)
            assertTrue(status.descriptionRes != 0)
            assertTrue(status.iconAssetPath.endsWith("${status.id}.png"))
            assertTrue("Missing status icon for ${status.id}", assetExists(status.iconAssetPath))
        }
    }

    @Test
    fun `ward skill descriptions match catalog mechanics`() {
        val bubbleWard = AdventureGateCatalog.skill("bubble_ward")
        assertEquals(4, bubbleWard.manaCost)
        assertEquals(100, bubbleWard.statusChancePercent)
        assertEquals("ward", bubbleWard.status?.id)
        assertEquals(18, bubbleWard.status?.incomingReductionPercent)
        assertEquals(4, bubbleWard.status?.manaRegenFlat)

        val manaShell = AdventureGateCatalog.skill("mana_shell")
        assertEquals(null, manaShell.status)

        listOf(
            stringXmlValue("values", "adventure_gate_skill_bubble_ward_desc"),
            stringXmlValue("values-es", "adventure_gate_skill_bubble_ward_desc")
        ).forEach { text ->
            assertTrue(text.contains("2-5"))
            assertTrue(text.contains("18%"))
            assertTrue(text.contains("+4"))
        }
    }

    @Test
    fun `status skills expose visible mechanical effects`() {
        AdventureGateCatalog.skills.filter { it.status != null }.forEach { skill ->
            val status = skill.status ?: error("Missing status for ${skill.id}")
            assertTrue("${skill.id} should have a positive status chance", skill.statusChancePercent > 0)
            assertTrue(
                "${skill.id} status should expose at least one gameplay effect",
                status.damagePerTurn > 0 ||
                    status.skipTurnChancePercent > 0 ||
                    status.attackMultiplierPercent != 100 ||
                    status.magicMultiplierPercent != 100 ||
                    status.defenseMultiplierPercent != 100 ||
                    status.speedMultiplierPercent != 100 ||
                    status.accuracyDelta != 0 ||
                    status.evasionDelta != 0 ||
                    status.incomingDamageBonusPercent != 0 ||
                    status.physicalDamageTakenBonusPercent != 0 ||
                    status.hpRegenPercent > 0 ||
                    status.manaRegenFlat > 0 ||
                    status.incomingReductionPercent > 0
            )
        }
    }

    @Test
    fun `boss reveal cards are expanded in both languages`() {
        val revealNames = listOf(
            "adventure_gate_reveal_sproutvale_07",
            "adventure_gate_reveal_sproutvale_15",
            "adventure_gate_reveal_ember_07",
            "adventure_gate_reveal_ember_15",
            "adventure_gate_reveal_bubbleglass_07",
            "adventure_gate_reveal_bubbleglass_15",
            "adventure_gate_reveal_clockwork_07",
            "adventure_gate_reveal_clockwork_15",
            "adventure_gate_reveal_moonmoss_07",
            "adventure_gate_reveal_moonmoss_15",
            "adventure_gate_reveal_frostfall_07",
            "adventure_gate_reveal_frostfall_15",
            "adventure_gate_reveal_starfall_07",
            "adventure_gate_reveal_starfall_15"
        )
        listOf("values", "values-es").forEach { folder ->
            revealNames.forEach { name ->
                val text = stringXmlValue(folder, name)
                assertTrue("$folder $name should be a longer reveal", text.split(Regex("\\s+")).size >= 28)
            }
        }
    }

    @Test
    fun `replayed phase rewards reduce coins and potion chance`() {
        val firstPhase = AdventureGateCatalog.worlds.first().phases.first()
        assertEquals(50, AdventureGateCatalog.phaseCoinReward(firstPhase))
        assertEquals(5, AdventureGateCatalog.phaseCoinReward(firstPhase, replay = true))
        assertEquals(
            AdventureGateCatalog.phasePotionRewardChancePercent(firstPhase) / 2,
            AdventureGateCatalog.phasePotionRewardChancePercent(firstPhase, replay = true)
        )

        val bossPhase = AdventureGateCatalog.worlds.first().phases.first { it.phaseNumber == 7 }
        assertEquals(30, AdventureGateCatalog.phasePotionRewardChancePercent(bossPhase))
        assertEquals(15, AdventureGateCatalog.phasePotionRewardChancePercent(bossPhase, replay = true))
    }

    @Test
    fun `supplies and equipment have world unlocks unique ids and asset paths`() {
        assertEquals(16, AdventureGateCatalog.supplies.size)
        assertEquals(7, AdventureGateCatalog.supplies.count { it.kind == AdventureGateSupplyKind.HP })
        assertEquals(7, AdventureGateCatalog.supplies.count { it.kind == AdventureGateSupplyKind.MANA })
        assertEquals(1, AdventureGateCatalog.supplies.count { it.kind == AdventureGateSupplyKind.CLEANSE })
        assertEquals(1, AdventureGateCatalog.supplies.count { it.kind == AdventureGateSupplyKind.SKILL_POINT })
        assertEquals((0..6).toSet(), AdventureGateCatalog.supplies.map { it.unlockWorldIndex }.toSet())
        AdventureGateCatalog.supplies.forEach { supply ->
            assertTrue(supply.nameRes != 0)
            if (supply.kind == AdventureGateSupplyKind.CLEANSE) {
                assertEquals(0, supply.amount)
            } else {
                assertTrue(supply.amount > 0)
            }
            assertTrue(supply.price > 0)
            assertTrue(supply.assetPath.endsWith("${supply.id}.png"))
            assertTrue("Missing asset ${supply.assetPath}", assetExists(supply.assetPath))
        }

        assertEquals(21, AdventureGateCatalog.shopEquipment().size)
        assertEquals(15, AdventureGateCatalog.bossRelics().size)
        assertEquals(36, AdventureGateCatalog.equipment.size)
        assertEquals(AdventureGateCatalog.equipment.size, AdventureGateCatalog.equipment.map { it.id }.toSet().size)
        assertEquals(7, AdventureGateCatalog.shopEquipment().count { it.slot == AdventureGateEquipmentSlot.WEAPON })
        assertEquals(7, AdventureGateCatalog.shopEquipment().count { it.slot == AdventureGateEquipmentSlot.SHIELD })
        assertEquals(7, AdventureGateCatalog.shopEquipment().count { it.slot == AdventureGateEquipmentSlot.RING })
        assertEquals(0, AdventureGateCatalog.shopEquipment().count { it.slot == AdventureGateEquipmentSlot.RELIC })
        assertEquals(15, AdventureGateCatalog.bossRelics().count { it.slot == AdventureGateEquipmentSlot.RELIC })
        AdventureGateCatalog.equipment.forEach { equipment ->
            assertTrue(equipment.nameRes != 0)
            assertTrue(equipment.assetPath.endsWith("${equipment.id}.png"))
            if (equipment.uniqueDrop) {
                assertTrue(equipment.price == 0)
                if (!equipment.mysteryDrop) {
                    assertTrue(equipment.bossDropWorldId != null)
                    assertTrue(equipment.bossDropPhase == 7 || equipment.bossDropPhase == 15)
                }
            } else {
                assertTrue(equipment.price > 0)
                assertTrue(equipment.unlockWorldIndex in 0..6)
            }
        }
    }

    @Test
    fun `hp and mana supply recovery follows doubled potion ladder`() {
        assertEquals(
            listOf(120, 220, 340, 500, 680, 880, 1200),
            AdventureGateCatalog.supplies
                .filter { it.kind == AdventureGateSupplyKind.HP }
                .map { it.amount }
        )
        assertEquals(
            listOf(60, 112, 180, 260, 360, 480, 640),
            AdventureGateCatalog.supplies
                .filter { it.kind == AdventureGateSupplyKind.MANA }
                .map { it.amount }
        )
    }

    @Test
    fun `recipe catalog matches supply prices unlocks ingredients and assets`() {
        assertEquals(15, AdventureGateCatalog.recipes.size)
        val recipeIds = AdventureGateCatalog.recipes.map { it.id }
        assertEquals(recipeIds.toSet().size, recipeIds.size)
        val cleanseRecipe = checkNotNull(AdventureGateCatalog.recipeForSupply(AdventureGateCatalog.CLEANSE_DRAUGHT_ID))
        assertEquals(1200, cleanseRecipe.price)
        assertEquals(0, cleanseRecipe.unlockWorldIndex)
        assertEquals(
            mapOf("crop_wheat" to 1, "crop_carrot" to 1, "crop_tomato" to 1, "crop_corn" to 1),
            cleanseRecipe.ingredientCounts
        )

        AdventureGateCatalog.recipes.forEach { recipe ->
            val supply = checkNotNull(AdventureGateCatalog.supply(recipe.supplyId))
            assertEquals("recipe_${supply.id}", recipe.id)
            assertEquals(supply.price * 2, recipe.price)
            assertEquals(supply.unlockWorldIndex, recipe.unlockWorldIndex)
            assertTrue(recipe.ingredientItemIds.isNotEmpty())
            assertTrue(recipe.ingredientItemIds.all { it.startsWith("crop_") })
        }
        assertTrue(assetExists("tama/potions/recipe_scroll.png"))
        assertTrue(assetExists("tama/potions/alchemy_cauldron.png"))
    }

    @Test
    fun `element chart has broad type coverage`() {
        val monsterElements = AdventureGateCatalog.monsters.flatMap {
            listOfNotNull(it.primaryElement, it.secondaryElement)
        }.toSet()
        assertTrue(monsterElements.containsAll(
            setOf(
                AdventureGateElement.FIRE,
                AdventureGateElement.WATER,
                AdventureGateElement.ICE,
                AdventureGateElement.STORM,
                AdventureGateElement.NATURE,
                AdventureGateElement.STONE,
                AdventureGateElement.METAL,
                AdventureGateElement.LIGHT,
                AdventureGateElement.SHADOW,
                AdventureGateElement.ARCANE,
                AdventureGateElement.BEAST
            )
        ))

        val playerSkillElements = AdventureGateCatalog.skills.map { it.element }.toSet()
        assertTrue(playerSkillElements.containsAll(
            setOf(
                AdventureGateElement.STRIKE,
                AdventureGateElement.SLASH,
                AdventureGateElement.FIRE,
                AdventureGateElement.WATER,
                AdventureGateElement.ICE,
                AdventureGateElement.STORM,
                AdventureGateElement.NATURE,
                AdventureGateElement.METAL,
                AdventureGateElement.LIGHT,
                AdventureGateElement.SHADOW,
                AdventureGateElement.ARCANE,
                AdventureGateElement.BEAST
            )
        ))
    }
}
