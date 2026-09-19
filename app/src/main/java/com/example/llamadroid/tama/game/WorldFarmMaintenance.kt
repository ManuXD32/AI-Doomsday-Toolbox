package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler

/** Shared maintenance rules; only an active development adventure requires travel. */
object WorldFarmMaintenance {
    suspend fun request(context: Context, engine: TamaGameEngine, operation: String,
                        arguments: Map<String, String> = emptyMap()): TamaGameEngine.ActionResult {
        if (!engine.isSimulatedWorldActive) {
            return try {
                engine.runClassicTransaction { _, farm ->
                    val result = complete(context, engine, farm, arguments + ("operation" to operation))
                    engine.pet.value?.id?.let { petId ->
                        TamaCommitEffects.deferOrRun("notifications:$petId") {
                            TamaNotificationScheduler.scheduleForPet(context.applicationContext, petId)
                        }
                    }
                    result
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TamaGameEngine.ActionResult(false, context.getString(R.string.tama_world_runtime_action_unavailable))
            }
        }
        val action = if (operation == "harvester_collect") ActionId.STORE_PRODUCE else ActionId.USE
        val result = engine.world.queueAction(action, LegacyLocationAliases.FARM,
            arguments + mapOf("canonicalAction" to "farmMaintenance", "operation" to operation), LegacyLocationAliases.FARM)
        return TamaGameEngine.ActionResult(result.acceptedCommand, context.getString(if (result.acceptedCommand)
            R.string.tama_world_runtime_action_queued else R.string.tama_world_runtime_action_unavailable))
    }

    internal suspend fun complete(context: Context, engine: TamaGameEngine, farm: FarmRepository,
                                  arguments: Map<String, String>): TamaGameEngine.ActionResult {
        val pet = requireNotNull(engine.pet.value)
        val now = System.currentTimeMillis()
        check(!pet.cycleFrozen)
        suspend fun spend(cost: Int) {
            check(cost > 0 && engine.spendMoney(cost.toLong())) { context.getString(R.string.tama_action_not_enough_coins, cost.toLong(), pet.money) }
        }
        suspend fun log(resource: Int, vararg values: Any) = engine.logEvent(pet.id, EventType.OTHER, context.getString(resource, *values))
        fun item(): InventoryItem = pet.inventory.firstOrNull { it.id == arguments["itemId"] && it.quantity > 0 }
            ?: error("item_required")
        val operation = arguments.getValue("operation")
        if (operation.startsWith("planting_")) check(farm.getUpgrade(pet.id, FARM_PLANTING_DRONE_ID)?.isPurchased == true)
        if (operation.startsWith("harvester_")) check(farm.getUpgrade(pet.id, FARM_HARVESTING_DRONE_ID)?.isPurchased == true)
        when (operation) {
            "buy_upgrade" -> {
                val type = arguments.getValue("type")
                val droneId = farmDroneIdForFuelUpgradeId(type)
                when {
                    type == FARMLAND_UPGRADE_ID -> {
                        val level = farm.getUpgrade(pet.id, type)?.takeIf { it.isPurchased }?.level ?: 0
                        spend(farmlandUpgradeCostForLevel(level) ?: error("upgrade_maxed"))
                        check(farm.upgradeFarmland(pet.id, rescheduleNotifications = false))
                    }
                    droneId != null -> {
                        val upgrade = farm.getUpgrade(pet.id, droneId)?.takeIf { it.isPurchased } ?: error("drone_required")
                        if (droneId == FARM_PLANTING_DRONE_ID) {
                            val current = farm.decodePlantingDroneState(upgrade, now)
                            spend(farmDroneFuelUpgradeCostForLevel(current.fuelUpgradeLevel) ?: error("upgrade_maxed"))
                            farm.savePlantingDroneState(pet.id, current.copy(fuelUpgradeLevel = current.fuelUpgradeLevel + 1, lastUpdatedAt = now), rescheduleNotifications = false)
                        } else {
                            val current = farm.decodeHarvesterDroneState(upgrade, now)
                            spend(farmDroneFuelUpgradeCostForLevel(current.fuelUpgradeLevel) ?: error("upgrade_maxed"))
                            farm.saveHarvesterDroneState(pet.id, current.copy(fuelUpgradeLevel = current.fuelUpgradeLevel + 1, lastUpdatedAt = now), rescheduleNotifications = false)
                        }
                    }
                    type in setOf("well", "composter") -> {
                        check(farm.getUpgrade(pet.id, type)?.isPurchased != true)
                        val price = if (type == "well") FARM_WELL_COST else 800
                        spend(price)
                        farm.buyUpgrade(pet.id, type, price, rescheduleNotifications = false)
                    }
                    else -> error("unknown_farm_upgrade")
                }
                log(R.string.event_purchased_upgrade, upgradeName(context, type))
            }
            "buy_drone" -> {
                val type = arguments.getValue("type")
                check(type in setOf(FARM_PLANTING_DRONE_ID, FARM_HARVESTING_DRONE_ID))
                check(farm.getUpgrade(pet.id, type)?.isPurchased != true && pet.inventory.none { it.id == type })
                spend(FARM_DRONE_BUY_PRICE)
                check(engine.grantItem(InventoryItem(type, upgradeName(context, type), ItemType.TOOL)))
                farm.buyUpgrade(pet.id, type, FARM_DRONE_BUY_PRICE, rescheduleNotifications = false)
                log(R.string.event_purchased_upgrade, upgradeName(context, type))
            }
            "buy_livestock" -> {
                val type = livestockType(arguments)
                val occupied = farm.decodeLivestockSlots(farm.getLivestock(pet.id, type.id), type).count { it.occupied }
                check(occupied < type.maxAnimals)
                spend(type.buyPrice)
                check(farm.buyLivestockAnimal(pet.id, type, rescheduleNotifications = false))
                log(if (type == FarmLivestockType.BARN) R.string.tama_event_bought_cow else R.string.tama_event_bought_chicken)
            }
            "collect_livestock" -> {
                val type = livestockType(arguments)
                val count = farm.collectLivestockOutput(pet.id, type, rescheduleNotifications = false)
                check(count > 0)
                val name = context.getString(if (type == FarmLivestockType.BARN) R.string.tama_item_milk_bottle else R.string.tama_item_egg)
                check(engine.grantItem(InventoryItem(type.productInventoryId, name, ItemType.CROP), count))
                log(if (type == FarmLivestockType.BARN) R.string.tama_event_collected_milk else R.string.tama_event_collected_eggs, count)
            }
            "feed_livestock" -> {
                val type = livestockType(arguments)
                val slot = farm.decodeLivestockSlots(farm.getLivestock(pet.id, type.id), type)
                    .getOrNull(arguments.getValue("slot").toInt()) ?: error("animal_missing")
                check(slot.occupied && livestockNeedsFeed(slot, now)) { "animal_not_hungry" }
                val feed = pet.inventory.firstOrNull { it.id == LIVESTOCK_FEED_ITEM_ID && it.quantity > 0 } ?: error("feed_required")
                check(engine.consumeItem(feed, 1))
                check(farm.feedLivestockAnimal(pet.id, type, arguments.getValue("slot").toInt(), rescheduleNotifications = false))
                log(if (type == FarmLivestockType.BARN) R.string.tama_event_fed_cow else R.string.tama_event_fed_chicken)
            }
            "buy_well", "buy_composter" -> {
                val type = if (operation == "buy_well") "well" else "composter"
                check(farm.getUpgrade(pet.id, type)?.isPurchased != true) { "already_purchased" }
                val cost = if (type == "well") FARM_WELL_COST else 800
                spend(cost)
                farm.buyUpgrade(pet.id, type, cost, rescheduleNotifications = false)
                log(if (type == "well") R.string.tama_event_well_upgrade else R.string.tama_event_composter_upgrade)
            }
            "collect_water" -> {
                val count = farm.collectWellTileOutput(pet.id, arguments.getValue("slot").toInt(), now, rescheduleNotifications = false)
                check(count > 0) { "output_not_ready" }
                check(engine.grantItem(InventoryItem("water", context.getString(R.string.tama_item_water), ItemType.MATERIAL), count))
                log(R.string.tama_event_collected_well_water, count)
            }
            "well_capacity" -> {
                val level = farm.getUpgrade(pet.id, "well")?.takeIf { it.isPurchased }?.level ?: error("well_required")
                check(level < FARM_WELL_MAX_LEVEL)
                spend(wellCapacityUpgradeCostForLevel(level))
                check(farm.upgradeWellCapacity(pet.id, rescheduleNotifications = false))
                log(R.string.tama_event_well_capacity_upgrade, level + 1)
            }
            "well_speed" -> {
                val upgrade = farm.getUpgrade(pet.id, "well")?.takeIf { it.isPurchased } ?: error("well_required")
                val level = farm.decodeWellState(upgrade, now).speedLevel
                spend(wellSpeedUpgradeCostForLevel(level) ?: error("upgrade_maxed"))
                check(farm.upgradeWellSpeed(pet.id, rescheduleNotifications = false))
                log(R.string.tama_event_well_speed_upgrade, wellIntervalHoursForSpeedLevel(level + 1))
            }
            "collect_compost" -> {
                val count = farm.collectComposterTileOutput(pet.id, arguments.getValue("slot").toInt(), rescheduleNotifications = false)
                check(count > 0) { "output_not_ready" }
                check(engine.grantItem(InventoryItem("fertilizer", context.getString(R.string.tama_item_fertilizer), ItemType.MATERIAL), count))
                log(R.string.tama_event_collected_fertilizer, count)
            }
            "composter_capacity" -> {
                val level = farm.getUpgrade(pet.id, "composter")?.takeIf { it.isPurchased }?.level ?: error("composter_required")
                check(level < FARM_COMPOSTER_MAX_LEVEL)
                spend(composterCapacityUpgradeCostForLevel(level))
                check(farm.upgradeComposterCapacity(pet.id, rescheduleNotifications = false))
                log(R.string.tama_event_composter_capacity_upgrade, level + 1)
            }
            "compost" -> {
                val input = item()
                check(FarmTradeItemCatalog.isCompostableCropItem(input.id))
                check(engine.consumeItem(input, 1))
                check(farm.addComposterInput(pet.id, input.id, arguments.getValue("slot").toInt(), rescheduleNotifications = false))
                log(R.string.tama_event_started_composting_crop, inventoryItemDisplayName(context, input))
            }
            "planting_settings" -> {
                val requested = Json.decodeFromString<PlantingDroneState>(arguments.getValue("settings"))
                val current = farm.decodePlantingDroneState(farm.getUpgrade(pet.id, FARM_PLANTING_DRONE_ID), now)
                val rank = requested.seeds.mapIndexed { index, stock -> stock.cropId to index }.toMap()
                farm.savePlantingDroneState(pet.id, current.copy(enabled = requested.enabled,
                    seeds = current.seeds.sortedBy { rank[it.cropId] ?: Int.MAX_VALUE }, statusKey = null,
                    lastUpdatedAt = now), rescheduleNotifications = false)
            }
            "harvester_settings" -> {
                val requested = Json.decodeFromString<HarvesterDroneState>(arguments.getValue("settings"))
                val current = farm.decodeHarvesterDroneState(farm.getUpgrade(pet.id, FARM_HARVESTING_DRONE_ID), now)
                farm.saveHarvesterDroneState(pet.id, current.copy(enabled = requested.enabled, mode = requested.mode,
                    cropFilter = requested.cropFilter.filter { it in CropDefinitions.CROPS }.toSet(), statusKey = null,
                    lastUpdatedAt = now), rescheduleNotifications = false)
            }
            "planting_item" -> {
                val input = item()
                val requested = arguments.getValue("quantity").toInt().coerceIn(1, 1_000_000)
                val current = farm.decodePlantingDroneState(farm.getUpgrade(pet.id, FARM_PLANTING_DRONE_ID), now)
                val count = minOf(requested, input.quantity, if (input.id == FARM_FUEL_BUCKET_ID)
                    farmDroneFuelCapacityForUpgradeLevel(current.fuelUpgradeLevel) - current.fuel else requested)
                check(count > 0)
                val updated = when {
                    input.id == FARM_FUEL_BUCKET_ID -> current.copy(fuel = current.fuel + count)
                    input.id == "water" -> current.copy(water = current.water + count)
                    input.id == "fertilizer" -> current.copy(fertilizer = current.fertilizer + count)
                    input.type == ItemType.SEED -> {
                        val cropId = input.id.removePrefix("seed_")
                        check(cropId in CropDefinitions.CROPS)
                        val existing = current.seeds.firstOrNull { it.cropId == cropId }
                        current.copy(seeds = current.seeds.filterNot { it.cropId == cropId } + DroneSeedStock(cropId, (existing?.quantity ?: 0) + count))
                    }
                    else -> error("unsupported_drone_item")
                }
                check(engine.consumeItem(input, count))
                farm.savePlantingDroneState(pet.id, updated.copy(lastUpdatedAt = now), rescheduleNotifications = false)
            }
            "planting_tool" -> {
                val family = arguments.getValue("family")
                check(family in setOf("hoe", "watering_can"))
                val current = farm.decodePlantingDroneState(farm.getUpgrade(pet.id, FARM_PLANTING_DRONE_ID), now)
                val oldTool = if (family == "hoe") current.hoe else current.wateringCan
                val amount = minOf(arguments.getValue("amount").toInt().coerceAtLeast(0),
                    FARM_TOOL_DURABILITY_CAP - (oldTool?.durability ?: 0))
                val transferred = engine.consumeFarmToolDurability(family, amount)
                check(transferred > 0)
                val updatedTool = DroneToolState(family, family, (oldTool?.durability ?: 0) + transferred, FARM_TOOL_DURABILITY_CAP)
                val updated = if (family == "hoe") current.copy(hoe = updatedTool) else current.copy(wateringCan = updatedTool)
                farm.savePlantingDroneState(pet.id, updated.copy(lastUpdatedAt = now), rescheduleNotifications = false)
            }
            "harvester_fuel" -> {
                val input = item()
                check(input.id == FARM_FUEL_BUCKET_ID)
                val current = farm.decodeHarvesterDroneState(farm.getUpgrade(pet.id, FARM_HARVESTING_DRONE_ID), now)
                val count = minOf(arguments.getValue("quantity").toInt().coerceAtLeast(0), input.quantity,
                    farmDroneFuelCapacityForUpgradeLevel(current.fuelUpgradeLevel) - current.fuel)
                check(count > 0 && engine.consumeItem(input, count))
                farm.saveHarvesterDroneState(pet.id, current.copy(fuel = current.fuel + count, lastUpdatedAt = now), rescheduleNotifications = false)
            }
            "harvester_collect" -> check(engine.collectHarvesterDroneStorage()) { "storage_empty" }
            else -> error("unknown_farm_operation")
        }
        return TamaGameEngine.ActionResult(true, context.getString(R.string.wear_tama_action_done))
    }

    private fun livestockType(arguments: Map<String, String>): FarmLivestockType =
        FarmLivestockType.entries.first { it.id == arguments.getValue("type") }

    private fun upgradeName(context: Context, type: String): String = context.getString(when (type) {
        FARMLAND_UPGRADE_ID -> R.string.tama_farm_upgrade_farmland
        "well" -> R.string.tama_farm_upgrade_well
        "composter" -> R.string.tama_farm_upgrade_composter
        FARM_PLANTING_DRONE_ID, FARM_PLANTING_DRONE_FUEL_UPGRADE_ID -> R.string.tama_farm_planting_drone
        FARM_HARVESTING_DRONE_ID, FARM_HARVESTING_DRONE_FUEL_UPGRADE_ID -> R.string.tama_farm_harvesting_drone
        else -> error("unknown_farm_upgrade")
    })
}
