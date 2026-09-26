package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.TamaWorkCatalog
import com.example.llamadroid.tama.data.TamaTrainingCatalog
import com.example.llamadroid.tama.data.TamaFoodCatalog
import com.example.llamadroid.tama.data.TamaCommerceCatalog
import com.example.llamadroid.tama.data.canWork
import com.example.llamadroid.tama.data.canStudy
import com.example.llamadroid.tama.data.canTrain
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException

/** Shared validation: direct classic controls or physical development-world actions. */
internal object TamaCanonicalActions {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun request(context: Context, engine: TamaGameEngine, action: String,
                        arguments: Map<String, String> = emptyMap()): TamaGameEngine.ActionResult {
        val pet = engine.pet.value ?: return TamaGameEngine.ActionResult(false, context.getString(R.string.tama_error_no_pet))
        fun rejected(resource: Int, vararg values: Any) = TamaGameEngine.ActionResult(false, context.getString(resource, *values))
        if (pet.cycleFrozen && action != "usePotion") return rejected(R.string.tama_cycle_frozen_busy)
        if (pet.isSleeping && action != "wakeUp") return rejected(R.string.tama_sleeping_busy, pet.name)
        if (pet.stage == GrowthStage.EGG && action in setOf("feed", "feedWithFood", "clean", "play")) {
            return rejected(when (action) {
                "clean" -> R.string.tama_action_egg_cannot_bathe
                "play" -> R.string.tama_action_egg_cannot_play
                else -> R.string.tama_action_egg_cannot_eat
            })
        }
        when (action) {
            "wakeUp" -> if (!pet.isSleeping) return rejected(R.string.tama_world_already_awake, pet.name)
            "goToBed" -> {
                if (pet.stage == GrowthStage.EGG) return rejected(R.string.tama_action_egg_cannot_sleep)
                if (pet.stats.energy >= 100) return rejected(R.string.tama_action_not_tired, pet.name)
            }
            "feed" -> if (pet.stats.hunger >= 100) return rejected(R.string.tama_action_food_full, pet.name)
            "clean" -> if (pet.poopCount == 0 && pet.stats.hygiene >= 100) return rejected(R.string.tama_action_already_clean, pet.name)
            "play" -> {
                if (pet.stats.happiness >= 100) return rejected(R.string.tama_action_already_super_happy, pet.name)
                if (pet.stats.energy < 10) return rejected(R.string.tama_action_too_tired_to_play, pet.name)
            }
        }
        if (action in setOf("startWork", "startTraining", "startNormalStudySession", "startPomodoroStudySession", "startActivity")) {
            if (pet.currentActivity != ActivityType.NONE) return rejected(R.string.tama_action_already_busy, pet.name)
            val activity = arguments["activity"]
            if ((action == "startWork" || activity == ActivityType.WORKING.name) && !pet.stage.canWork())
                return rejected(R.string.tama_action_only_teens_work)
            if ((action in setOf("startNormalStudySession", "startPomodoroStudySession") || activity == ActivityType.STUDYING.name) && !pet.stage.canStudy())
                return rejected(R.string.tama_action_only_students_study)
            if ((action == "startTraining" || activity == ActivityType.TRAINING.name) && !pet.stage.canTrain())
                return rejected(R.string.tama_action_only_students_train)
            if (action == "startWork") {
                val job = TamaWorkCatalog.jobById(arguments.getValue("jobId")) ?: return rejected(R.string.tama_work_job_missing)
                if (pet.educationLevel < job.requiredEducation) return rejected(R.string.tama_work_job_locked,
                    job.requiredEducation, context.getString(job.titleRes))
            }
            if (action == "startTraining") {
                val tier = TamaTrainingCatalog.tierById(arguments.getValue("tierId")) ?: return rejected(R.string.tama_training_tier_missing)
                if (pet.exerciseLevel < tier.requiredExercise) return rejected(R.string.tama_training_tier_locked,
                    tier.requiredExercise, context.getString(tier.titleRes))
            }
        }
        val rule = when (action) {
            "goToBed" -> Rule(ActionId.SLEEP, null, LegacyLocationAliases.HOME)
            "wakeUp" -> Rule(ActionId.WAKE, null)
            "stopActivity" -> Rule(ActionId.WAIT, null)
            "startWork" -> Rule(ActionId.WORK, LegacyLocationAliases.WORKPLACE, LegacyLocationAliases.WORKPLACE)
            "startTraining" -> Rule(ActionId.TRAIN_BOXING, LegacyLocationAliases.BOXING_RING, LegacyLocationAliases.BOXING_RING)
            "startNormalStudySession", "startPomodoroStudySession" -> Rule(ActionId.STUDY, LegacyLocationAliases.SCHOOL, LegacyLocationAliases.SCHOOL)
            "startActivity" -> when (ActivityType.valueOf(arguments.getValue("activity"))) {
                ActivityType.WORKING -> Rule(ActionId.WORK, LegacyLocationAliases.WORKPLACE, LegacyLocationAliases.WORKPLACE)
                ActivityType.STUDYING -> Rule(ActionId.STUDY, LegacyLocationAliases.SCHOOL, LegacyLocationAliases.SCHOOL)
                ActivityType.TRAINING -> Rule(ActionId.TRAIN_BOXING, LegacyLocationAliases.BOXING_RING, LegacyLocationAliases.BOXING_RING)
                ActivityType.RELAXING -> Rule(ActionId.RELAX, LegacyLocationAliases.PARK, LegacyLocationAliases.PARK)
                ActivityType.NONE -> Rule(ActionId.WAIT, null)
            }
            "feed" -> Rule(ActionId.EAT, "meal", LegacyLocationAliases.HOME)
            "feedWithFood" -> {
                val foodId = arguments.getValue("foodId")
                val food = TamaFoodCatalog.byId(foodId) ?: return rejected(R.string.tama_world_runtime_action_unavailable)
                if (arguments["hungerGain"]?.toIntOrNull() != food.hungerGain ||
                    arguments["happinessGain"]?.toIntOrNull() != food.happinessGain) {
                    return rejected(R.string.tama_world_runtime_action_unavailable)
                }
                val free = foodId in setOf("lettuce", "candy")
                if (!free && pet.inventory.none { it.id == foodId && it.quantity > 0 && it.type == ItemType.FOOD }) {
                    return TamaGameEngine.ActionResult(false, context.getString(R.string.tama_action_no_item_food, foodId))
                }
                Rule(ActionId.EAT, foodId, if (free) LegacyLocationAliases.HOME else null)
            }
            "clean" -> Rule(ActionId.WASH, LegacyLocationAliases.HOME, LegacyLocationAliases.HOME)
            "play" -> Rule(ActionId.PLAY, LegacyLocationAliases.HOME, LegacyLocationAliases.HOME)
            "buyItem" -> {
                val item = item(arguments)
                if (runCatching { requirePositivePurchase(arguments) }.isFailure) return rejected(R.string.tama_world_runtime_action_unavailable)
                val vendor = arguments["vendorId"] ?: LegacyLocationAliases.SHOP
                val offer = TamaCommerceCatalog.offer(context, item.id, vendor)
                if (offer == null || item.type != offer.item.type || arguments["pricePerUnit"]?.toIntOrNull() != offer.price) {
                    return rejected(R.string.tama_world_runtime_action_unavailable)
                }
                Rule(ActionId.BUY, item.id, vendor)
            }
            "buyLegacyItem" -> {
                val offer = TamaCommerceCatalog.legacyOffer(context, arguments.getValue("itemName"))
                if (offer == null || arguments["price"]?.toIntOrNull() != offer.price) return rejected(R.string.tama_world_runtime_action_unavailable)
                Rule(ActionId.BUY, offer.item.id, LegacyLocationAliases.SHOP)
            }
            "sellItem" -> {
                val quantity = arguments["quantity"]?.toIntOrNull() ?: 0
                val price = arguments["price"]?.toLongOrNull() ?: 0L
                if (quantity <= 0 || price <= 0 || price != TamaCommerceCatalog.sellPrice(item(arguments).id)?.toLong() ||
                    price > (Long.MAX_VALUE - pet.money) / quantity) return rejected(R.string.tama_world_runtime_action_unavailable)
                Rule(ActionId.SELL, item(arguments).id, LegacyLocationAliases.SHOP)
            }
            "sellToParkSeller" -> {
                require(arguments.getValue("quantity").toInt() > 0)
                Rule(ActionId.SELL, item(arguments).id, "market_stall")
            }
            "usePotion" -> Rule(ActionId.USE, arguments.getValue("potionId"))
            "useAdventureGateSupply" -> Rule(ActionId.USE, arguments.getValue("supplyId"))
            "brewAdventureGatePotion" -> Rule(ActionId.USE_ALCHEMY, LegacyLocationAliases.ALCHEMIST, LegacyLocationAliases.ALCHEMIST)
            else -> error("unknown_canonical_action")
        }
        if (!engine.isSimulatedWorldActive) {
            return try {
                engine.runClassicTransaction { _, _ -> complete(context, engine, action, arguments) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                rejected(R.string.tama_world_runtime_action_unavailable)
            }
        }
        val result = engine.world.queueAction(rule.action, rule.targetId,
            arguments + ("canonicalAction" to action), rule.destinationId)
        return TamaGameEngine.ActionResult(result.acceptedCommand,
            context.getString(if (result.acceptedCommand) R.string.tama_world_runtime_action_queued else R.string.tama_world_runtime_action_unavailable),
            if (result.acceptedCommand) rule.action.name.lowercase() else "")
    }

    /** Called inside a serialized Room transaction by either action path. */
    suspend fun complete(context: Context, engine: TamaGameEngine, action: String, arguments: Map<String, String>): TamaGameEngine.ActionResult = when (action) {
        "goToBed" -> engine.goToBedLocked()
        "wakeUp" -> {
            if (engine.pet.value?.isSleeping == true) {
                engine.wakeUpLocked()
                TamaGameEngine.ActionResult(true, "")
            } else TamaGameEngine.ActionResult(false, context.getString(R.string.tama_world_already_awake, engine.pet.value?.name.orEmpty()))
        }
        "stopActivity" -> engine.stopActivityLocked()
        "startWork" -> engine.startWorkLocked(arguments.getValue("jobId"))
        "startTraining" -> engine.startTrainingLocked(arguments.getValue("tierId"))
        "startActivity" -> engine.startActivityLocked(ActivityType.valueOf(arguments.getValue("activity")))
        "startNormalStudySession" -> engine.startNormalStudySessionLocked(
            json.decodeFromString(arguments.getValue("labels")), json.decodeFromString(arguments.getValue("newLabels")))
        "startPomodoroStudySession" -> engine.startPomodoroStudySessionLocked(
            json.decodeFromString(arguments.getValue("labels")), json.decodeFromString(arguments.getValue("newLabels")),
            json.decodeFromString(arguments.getValue("settings")))
        "feed" -> engine.feedLocked()
        "feedWithFood" -> {
            require(arguments.getValue("hungerGain").toInt() in 0..100 && arguments.getValue("happinessGain").toInt() in 0..100)
            engine.feedWithFoodLocked(arguments.getValue("foodId"), arguments.getValue("hungerGain").toInt(),
                arguments.getValue("happinessGain").toInt())
        }
        "clean" -> engine.cleanLocked()
        "play" -> engine.playLocked()
        "buyItem" -> {
            requirePositivePurchase(arguments)
            val selected = item(arguments)
            val vendor = arguments["vendorId"] ?: LegacyLocationAliases.SHOP
            val offer = TamaCommerceCatalog.offer(context, selected.id, vendor) ?: error("unknown_vendor_offer")
            require(offer.price == arguments.getValue("pricePerUnit").toInt() && offer.item.type == selected.type) { "invalid_vendor_offer" }
            if (engine.isSimulatedWorldActive) {
                require(LegacyLocationAliases.normalize(engine.pet.value?.currentLocationId.orEmpty()) == vendor) { "vendor_required" }
            }
            engine.buyItemLocked(offer.item, arguments.getValue("quantity").toInt(), offer.price)
        }
        "buyLegacyItem" -> {
            require(arguments.getValue("price").toInt() > 0)
            engine.buyItemLocked(arguments.getValue("itemName"), arguments.getValue("price").toInt())
        }
        "sellItem" -> {
            require(arguments.getValue("quantity").toInt() > 0 && arguments.getValue("price").toLong() > 0)
            require(TamaCommerceCatalog.sellPrice(item(arguments).id)?.toLong() == arguments.getValue("price").toLong()) { "invalid_sale_price" }
            engine.sellItemLocked(item(arguments), arguments.getValue("quantity").toInt(), arguments.getValue("price").toLong())
        }
        "sellToParkSeller" -> {
            require(arguments.getValue("quantity").toInt() > 0)
            engine.sellToParkSellerLocked(item(arguments), arguments.getValue("quantity").toInt())
        }
        "usePotion" -> engine.usePotionLocked(arguments.getValue("potionId"))
        "useAdventureGateSupply" -> engine.useAdventureGateSupplyLocked(arguments.getValue("supplyId"))
        "brewAdventureGatePotion" -> engine.brewAdventureGatePotionLocked(json.decodeFromString(arguments.getValue("ingredients")))
        else -> error("unknown_canonical_action")
    }

    fun itemArguments(item: InventoryItem): Map<String, String> = mapOf("item" to json.encodeToString(item), "itemId" to item.id)
    private fun item(arguments: Map<String, String>): InventoryItem = json.decodeFromString(arguments.getValue("item"))
    private fun requirePositivePurchase(arguments: Map<String, String>) {
        require(arguments.getValue("quantity").toInt() in 1..1_000_000 && arguments.getValue("pricePerUnit").toInt() > 0) { "invalid_purchase" }
    }
    private data class Rule(val action: ActionId, val targetId: String?, val destinationId: String? = null)
}
