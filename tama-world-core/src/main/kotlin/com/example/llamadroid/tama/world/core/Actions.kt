package com.example.llamadroid.tama.world.core

import kotlinx.serialization.Serializable

@Serializable
enum class ActionId {
    WAIT,
    WALK,
    RUN,
    FOLLOW,
    APPROACH,
    FLEE,
    RETURN_HOME,
    ENTER_STRUCTURE,
    EXIT_STRUCTURE,
    LOOK,
    INSPECT,
    SEARCH_AREA,
    OBSERVE_NPC,
    OBSERVE_OBJECT,
    SIT,
    REST,
    SLEEP,
    WAKE,
    EAT,
    DRINK,
    WASH,
    USE_MEDICINE,
    PICK_UP,
    DROP,
    FORAGE,
    HARVEST_WILD_PLANT,
    CHOP_TREE,
    GATHER_WOOD,
    GATHER_STONE,
    GATHER_HERB,
    TILL_SOIL,
    PLANT,
    WATER,
    POUR_WATER,
    FERTILIZE,
    HARVEST_CROP,
    REMOVE_DEAD_CROP,
    STORE_PRODUCE,
    GREET,
    TALK,
    PLAY_WITH,
    FOLLOW_NPC,
    GIVE_ITEM,
    RECEIVE_ITEM,
    HELP_NPC,
    THANK,
    SAY_GOODBYE,
    BUY,
    SELL,
    TRADE,
    PLAY,
    WANDER,
    USE_ARCADE,
    RELAX,
    EXPLORE,
    INVESTIGATE,
    STUDY,
    WORK,
    TRAIN_BOXING,
    VISIT_HOSPITAL,
    USE_ALCHEMY,
    ENTER_DUNGEON,
    ENTER_ADVENTURE_GATE,
    OPEN,
    CLOSE,
    USE,
    ACTIVATE
}

@Serializable
enum class ActorType {
    PET,
    NPC
}

@Serializable
enum class ActionTargetKind {
    NONE,
    TILE,
    ACTOR,
    OBJECT,
    STRUCTURE,
    ITEM,
    FARM_PLOT
}

@Serializable
enum class ActionPrerequisite {
    NONE,
    WALKABLE_TARGET,
    ADJACENT_TARGET,
    HAS_ITEM,
    HAS_TOOL,
    HAS_MONEY,
    HAS_ENERGY,
    HAS_HEALTH,
    DAYTIME,
    NIGHTTIME,
    AT_HOME,
    AT_STRUCTURE,
    AT_FARM,
    TARGET_AVAILABLE,
    AUTONOMY_PERMISSION
}

@Serializable
enum class ActionAnimation {
    IDLE,
    WALK,
    RUN,
    REST,
    EAT,
    DRINK,
    WASH,
    GATHER,
    FARM,
    SOCIAL,
    WORK,
    STUDY,
    TRAIN,
    USE_STRUCTURE,
    INSPECT
}

@Serializable
enum class ActionLogPolicy {
    NONE,
    COMPLETION,
    START_AND_COMPLETION,
    EVERY_INTERACTION
}

/**
 * Declarative action metadata shared by pet and NPC executors. Durable pet
 * state is intentionally absent; a completed action emits effect requests for
 * the app adapter to commit transactionally.
 */
@Serializable
data class ActionDefinition(
    val id: ActionId,
    val validActorTypes: Set<ActorType> = setOf(ActorType.PET, ActorType.NPC),
    val validTargets: Set<ActionTargetKind> = setOf(ActionTargetKind.NONE),
    val prerequisites: Set<ActionPrerequisite> = setOf(ActionPrerequisite.NONE),
    val toolRequirement: String? = null,
    val inventoryInputs: List<InventoryStack> = emptyList(),
    val requiredItemKind: InventoryKind? = null,
    val durationTicks: Int = 1,
    val energyCost: Float = 0f,
    val statEffects: Map<NeedType, Float> = emptyMap(),
    val worldEffects: List<String> = emptyList(),
    val animation: ActionAnimation = ActionAnimation.IDLE,
    val logPolicy: ActionLogPolicy = ActionLogPolicy.COMPLETION,
    val autonomous: Boolean = true
)

data class ActionValidation(
    val valid: Boolean,
    val reason: String? = null
)

@Serializable
data class ActionTarget(
    val kind: ActionTargetKind,
    val id: String? = null,
    val coordinate: WorldCoordinate? = null,
    val available: Boolean = true,
    /** User-selected crop/tool/options retained through an approach route. */
    val arguments: Map<String, String> = emptyMap(),
    /** Optional generated/custom object type used for typed target checks. */
    val objectType: WorldObjectType? = null
)

data class ActionContext(
    val actorType: ActorType,
    val actorCoordinate: WorldCoordinate,
    val target: ActionTarget? = null,
    val presence: PresenceMode = PresenceMode.WORLD,
    val structureType: StructureType? = null,
    val atFarm: Boolean = false,
    val hasItem: (String) -> Boolean = { false },
    val itemKind: (String) -> InventoryKind? = { null },
    val hasTool: (String) -> Boolean = { false },
    val money: Int = 0,
    val energy: Float = 100f,
    val health: Float = 100f,
    val sleeping: Boolean = false,
    val userOwnedCrop: Boolean = false,
    val autonomy: AutonomyPolicy = AutonomyPolicy(level = AutonomyLevel.FULL),
    /** Current quantity of an item, used for selected stack/transfer checks. */
    val itemQuantity: (String) -> Int = { 0 },
    /** Day/night is supplied by the deterministic world clock. */
    val isDaytime: Boolean = true,
    /** Stable actor identity used for private world-target checks. */
    val actorId: String = "pet",
    /** Current structure identity, when the actor is indoors. */
    val structureId: String? = null
)

object ActionExecutor {
    fun validate(action: ActionId, context: ActionContext): ActionValidation {
        val definition = ActionRegistry[action]
        if (context.actorType !in definition.validActorTypes) return ActionValidation(false, "actor_type_not_allowed")
        val targetKind = context.target?.kind ?: ActionTargetKind.NONE
        if (targetKind !in definition.validTargets) return ActionValidation(false, "target_type_not_allowed")
        if (targetKind == ActionTargetKind.STRUCTURE && context.target?.id.isNullOrBlank()) {
            return ActionValidation(false, "structure_required")
        }
        val selectionFailure = WorldActionSemantics.selectionFailure(action, context)
        if (selectionFailure != null) return ActionValidation(false, selectionFailure)
        WorldActionSemantics.contextualFailure(action, context)?.let { reason ->
            return ActionValidation(false, reason)
        }
        if (targetKind == ActionTargetKind.STRUCTURE &&
            ActionPrerequisite.AT_STRUCTURE in definition.prerequisites &&
            context.presence == PresenceMode.INTERIOR &&
            context.structureId != null && context.structureId != context.target?.id
        ) {
            return ActionValidation(false, "wrong_structure")
        }
        val purchase = WorldActionSemantics.purchaseSelection(context.target)
        definition.prerequisites.forEach { prerequisite ->
            val failure = when (prerequisite) {
                ActionPrerequisite.NONE -> null
                ActionPrerequisite.WALKABLE_TARGET -> if (context.target?.coordinate == null) "walkable_target_required" else null
                ActionPrerequisite.ADJACENT_TARGET -> if (context.target?.coordinate == null || context.actorCoordinate.chebyshevDistanceTo(context.target.coordinate) > 1) "adjacent_target_required" else null
                ActionPrerequisite.HAS_ITEM -> {
                    val requiredItem = definition.inventoryInputs.firstOrNull()?.itemId
                        ?: context.target?.arguments?.get("itemId")
                        ?: WorldActionSemantics.ingredientIds(context.target).firstOrNull()
                        ?: context.target?.id?.takeUnless { action == ActionId.USE_ALCHEMY }
                    if (requiredItem == null) {
                        "item_required"
                    } else {
                        val itemId = requiredItem
                        val typedItemPresent = definition.requiredItemKind != null &&
                            context.itemKind(itemId) == definition.requiredItemKind
                        if ((context.hasItem(itemId) || typedItemPresent) && availableItemQuantity(context, itemId) > 0) null else "item_required:$itemId"
                    }
                }
                ActionPrerequisite.HAS_TOOL -> if (definition.toolRequirement == null || !context.hasTool(definition.toolRequirement)) "tool_required" else null
                ActionPrerequisite.HAS_MONEY -> when {
                    action == ActionId.BUY && purchase == null -> "purchase_selection_required"
                    action == ActionId.BUY && purchase!!.totalCost <= 0 -> "invalid_purchase"
                    action == ActionId.BUY && context.money < purchase!!.totalCost -> "money_required"
                    context.money <= 0 -> "money_required"
                    else -> null
                }
                ActionPrerequisite.HAS_ENERGY -> if (context.energy <= definition.energyCost) "energy_required" else null
                ActionPrerequisite.HAS_HEALTH -> if (context.health <= 0f) "health_required" else null
                ActionPrerequisite.DAYTIME -> if (!context.isDaytime) "daytime_required" else null
                ActionPrerequisite.NIGHTTIME -> if (context.isDaytime) "nighttime_required" else null
                ActionPrerequisite.AT_HOME -> if (context.presence != PresenceMode.HOME) "home_required" else null
                ActionPrerequisite.AT_STRUCTURE -> if (context.presence != PresenceMode.INTERIOR) "structure_required" else null
                ActionPrerequisite.AT_FARM -> if (!context.atFarm) "farm_required" else null
                ActionPrerequisite.TARGET_AVAILABLE -> if (context.target?.available != true) "target_unavailable" else null
                ActionPrerequisite.AUTONOMY_PERMISSION -> if (!context.autonomy.permits(
                    action,
                    purchaseCost = purchase?.totalCost ?: 0,
                    userOwnedCrop = context.userOwnedCrop
                )) "autonomy_forbidden" else null
            }
            if (failure != null) return ActionValidation(false, failure)
        }
        val requiredKind = definition.requiredItemKind
        if (requiredKind != null) {
            val itemId = definition.inventoryInputs.firstOrNull()?.itemId
                ?: context.target?.arguments?.get("itemId")
                ?: WorldActionSemantics.ingredientIds(context.target).firstOrNull()
                ?: context.target?.id
            val itemPresent = itemId != null &&
                (context.hasItem(itemId) || context.itemKind(itemId) == requiredKind) &&
                availableItemQuantity(context, itemId) > 0
            if (!itemPresent) return ActionValidation(false, "item_required_kind")
            if (itemId == null || (context.itemKind(itemId) ?: InventoryKinds.infer(itemId)) != requiredKind) {
                return ActionValidation(false, "${requiredKind.name.lowercase()}_required")
            }
        }
        when (action) {
            ActionId.DRINK -> if (context.target?.kind == ActionTargetKind.ITEM) {
                val itemId = context.target.id
                if (itemId == null || !context.hasItem(itemId) || availableItemQuantity(context, itemId) <= 0 ||
                    (context.itemKind(itemId) ?: InventoryKinds.infer(itemId)) != InventoryKind.WATER
                ) {
                    return ActionValidation(false, "water_required")
                }
            } else if (context.target?.kind == ActionTargetKind.OBJECT && !WorldActionSemantics.isWaterSource(context.target)) {
                return ActionValidation(false, "water_source_required")
            }
            ActionId.WASH -> if (context.target?.kind == ActionTargetKind.OBJECT && !WorldActionSemantics.isWaterSource(context.target)) {
                return ActionValidation(false, "water_source_required")
            }
            else -> Unit
        }
        if (action == ActionId.WAKE && !context.sleeping) return ActionValidation(false, "already_awake")
        val objectFailure = WorldActionSemantics.objectFailure(action, context.target)
        if (objectFailure != null) return ActionValidation(false, objectFailure)
        if (context.target?.kind == ActionTargetKind.STRUCTURE) {
            val allowedStructures = allowedStructureTypes(action)
            val npcAssignedWork = context.actorType == ActorType.NPC && action == ActionId.WORK
            if (allowedStructures != null && context.structureType !in allowedStructures && !npcAssignedWork) {
                return ActionValidation(false, "wrong_structure")
            }
        }
        if (action == ActionId.BUY || action == ActionId.SELL) {
            val vendorTypes = if (action == ActionId.BUY) {
                setOf(
                    StructureType.SHOP,
                    StructureType.MARKET_STALL,
                    StructureType.HOSPITAL,
                    StructureType.ALCHEMIST
                )
            } else {
                setOf(StructureType.SHOP, StructureType.MARKET_STALL)
            }
            if (context.structureType !in vendorTypes) return ActionValidation(false, "wrong_structure")
        }
        return ActionValidation(true)
    }

    private fun availableItemQuantity(context: ActionContext, itemId: String): Int =
        maxOf(context.itemQuantity(itemId), if (context.hasItem(itemId)) 1 else 0)

    private fun allowedStructureTypes(action: ActionId): Set<StructureType>? = when (action) {
        ActionId.WASH -> setOf(StructureType.HOME)
        ActionId.BUY -> setOf(
            StructureType.SHOP,
            StructureType.MARKET_STALL,
            StructureType.HOSPITAL,
            StructureType.ALCHEMIST
        )
        ActionId.SELL -> setOf(StructureType.SHOP, StructureType.MARKET_STALL)
        ActionId.RELAX -> setOf(StructureType.PARK)
        ActionId.USE_ARCADE -> setOf(StructureType.ARCADE)
        ActionId.STUDY -> setOf(StructureType.SCHOOL)
        ActionId.WORK -> setOf(StructureType.WORKPLACE)
        ActionId.TRAIN_BOXING -> setOf(StructureType.BOXING_RING)
        ActionId.VISIT_HOSPITAL -> setOf(StructureType.HOSPITAL)
        ActionId.USE_ALCHEMY -> setOf(StructureType.ALCHEMIST)
        ActionId.ENTER_DUNGEON -> setOf(StructureType.DUNGEON_A, StructureType.DUNGEON_B)
        ActionId.ENTER_ADVENTURE_GATE -> setOf(StructureType.ADVENTURE_GATE)
        ActionId.PLAY -> setOf(StructureType.HOME, StructureType.ARCADE)
        ActionId.STORE_PRODUCE -> setOf(StructureType.FARM, StructureType.FARM_BARN)
        else -> null
    }

    fun effectRequests(action: ActionId, definition: ActionDefinition = ActionRegistry[action]): List<WorldEffectRequest> {
        val reason = "action:${action.name.lowercase()}"
        return buildList {
            definition.statEffects.forEach { (need, delta) ->
                if (delta != 0f) add(WorldEffectRequest.NeedDelta(need, delta, reason))
            }
            if (definition.energyCost > 0f) {
                add(WorldEffectRequest.NeedDelta(NeedType.ENERGY, -definition.energyCost, reason))
            }
        }
    }
}

object ActionRegistry {
    private fun definition(
        id: ActionId,
        targets: Set<ActionTargetKind> = setOf(ActionTargetKind.NONE),
        prerequisites: Set<ActionPrerequisite> = setOf(ActionPrerequisite.NONE),
        duration: Int = 1,
        energy: Float = 0f,
        effects: Map<NeedType, Float> = emptyMap(),
        worldEffects: List<String> = emptyList(),
        animation: ActionAnimation = ActionAnimation.IDLE,
        log: ActionLogPolicy = ActionLogPolicy.COMPLETION,
        actors: Set<ActorType> = setOf(ActorType.PET, ActorType.NPC),
        autonomous: Boolean = true,
        tool: String? = null,
        inputs: List<InventoryStack> = emptyList(),
        itemKind: InventoryKind? = null
    ): ActionDefinition = ActionDefinition(
        id = id,
        validActorTypes = actors,
        validTargets = targets,
        prerequisites = prerequisites,
        toolRequirement = tool,
        inventoryInputs = inputs,
        requiredItemKind = itemKind,
        durationTicks = duration.coerceAtLeast(1),
        energyCost = energy.coerceAtLeast(0f),
        statEffects = effects,
        worldEffects = worldEffects,
        animation = animation,
        logPolicy = log,
        autonomous = autonomous
    )

    /** Complete initial action registry from the living-world specification. */
    val definitions: Map<ActionId, ActionDefinition> = listOf(
        // Movement
        definition(ActionId.WAIT),
        definition(ActionId.WALK, setOf(ActionTargetKind.TILE), setOf(ActionPrerequisite.WALKABLE_TARGET), energy = 0.01f, animation = ActionAnimation.WALK, log = ActionLogPolicy.NONE),
        definition(ActionId.RUN, setOf(ActionTargetKind.TILE), setOf(ActionPrerequisite.WALKABLE_TARGET, ActionPrerequisite.HAS_ENERGY), energy = 0.04f, animation = ActionAnimation.RUN, log = ActionLogPolicy.NONE),
        definition(ActionId.FOLLOW, setOf(ActionTargetKind.ACTOR), setOf(ActionPrerequisite.ADJACENT_TARGET), energy = 0.01f, animation = ActionAnimation.WALK, log = ActionLogPolicy.NONE),
        definition(ActionId.APPROACH, setOf(ActionTargetKind.ACTOR, ActionTargetKind.OBJECT, ActionTargetKind.STRUCTURE), energy = 0.01f, animation = ActionAnimation.WALK, log = ActionLogPolicy.NONE),
        definition(ActionId.FLEE, setOf(ActionTargetKind.TILE), setOf(ActionPrerequisite.WALKABLE_TARGET), energy = 0.03f, animation = ActionAnimation.RUN, log = ActionLogPolicy.NONE),
        definition(ActionId.RETURN_HOME, setOf(ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.WALKABLE_TARGET), energy = 0.01f, animation = ActionAnimation.WALK, log = ActionLogPolicy.COMPLETION),
        definition(ActionId.ENTER_STRUCTURE, setOf(ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.ADJACENT_TARGET), animation = ActionAnimation.USE_STRUCTURE),
        definition(ActionId.EXIT_STRUCTURE, setOf(ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.AT_STRUCTURE), animation = ActionAnimation.USE_STRUCTURE),
        // Observation
        definition(ActionId.LOOK, setOf(ActionTargetKind.NONE, ActionTargetKind.TILE), animation = ActionAnimation.INSPECT, log = ActionLogPolicy.NONE),
        definition(ActionId.INSPECT, setOf(ActionTargetKind.TILE, ActionTargetKind.ACTOR, ActionTargetKind.OBJECT, ActionTargetKind.STRUCTURE), animation = ActionAnimation.INSPECT),
        definition(ActionId.SEARCH_AREA, setOf(ActionTargetKind.TILE), setOf(ActionPrerequisite.WALKABLE_TARGET), duration = 3, animation = ActionAnimation.INSPECT),
        definition(ActionId.OBSERVE_NPC, setOf(ActionTargetKind.ACTOR), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 2, animation = ActionAnimation.INSPECT),
        definition(ActionId.OBSERVE_OBJECT, setOf(ActionTargetKind.OBJECT), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 2, animation = ActionAnimation.INSPECT),
        // Rest
        definition(ActionId.SIT, duration = 3, effects = mapOf(NeedType.ENERGY to 0.5f), animation = ActionAnimation.REST),
        definition(ActionId.REST, duration = 5, effects = mapOf(NeedType.ENERGY to 2f), animation = ActionAnimation.REST),
        // Sleep is a timed canonical activity. Energy recovery belongs to the
        // existing elapsed-sleep implementation; emitting a burst here would
        // let a generic adapter double-apply the gain before it starts sleep.
        definition(ActionId.SLEEP, prerequisites = setOf(ActionPrerequisite.AT_HOME), duration = 10, effects = mapOf(NeedType.HEALTH to 0.5f), animation = ActionAnimation.REST),
        definition(ActionId.WAKE, duration = 1, animation = ActionAnimation.IDLE),
        // Needs
        definition(ActionId.EAT, setOf(ActionTargetKind.ITEM), setOf(ActionPrerequisite.HAS_ITEM), duration = 2, itemKind = InventoryKind.FOOD, effects = mapOf(NeedType.HUNGER to 25f, NeedType.HAPPINESS to 2f), animation = ActionAnimation.EAT),
        definition(ActionId.DRINK, setOf(ActionTargetKind.ITEM, ActionTargetKind.OBJECT), setOf(ActionPrerequisite.TARGET_AVAILABLE), duration = 1, effects = mapOf(NeedType.HYDRATION to 30f), animation = ActionAnimation.DRINK),
        definition(ActionId.WASH, setOf(ActionTargetKind.STRUCTURE, ActionTargetKind.OBJECT), duration = 4, effects = mapOf(NeedType.HYGIENE to 30f, NeedType.HAPPINESS to 1f), animation = ActionAnimation.WASH),
        definition(ActionId.USE_MEDICINE, setOf(ActionTargetKind.ITEM), setOf(ActionPrerequisite.HAS_ITEM, ActionPrerequisite.HAS_HEALTH, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 2, itemKind = InventoryKind.MEDICINE, effects = mapOf(NeedType.HEALTH to 25f), animation = ActionAnimation.USE_STRUCTURE),
        // Resources
        definition(ActionId.PICK_UP, setOf(ActionTargetKind.OBJECT, ActionTargetKind.ITEM), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.TARGET_AVAILABLE), duration = 1, worldEffects = listOf("consume_resource"), animation = ActionAnimation.GATHER),
        definition(ActionId.DROP, setOf(ActionTargetKind.ITEM), setOf(ActionPrerequisite.HAS_ITEM), duration = 1, worldEffects = listOf("drop_item"), animation = ActionAnimation.GATHER),
        definition(ActionId.FORAGE, setOf(ActionTargetKind.OBJECT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.TARGET_AVAILABLE), duration = 3, energy = 0.05f, worldEffects = listOf("consume_resource"), animation = ActionAnimation.GATHER),
        definition(ActionId.HARVEST_WILD_PLANT, setOf(ActionTargetKind.OBJECT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.TARGET_AVAILABLE), duration = 3, energy = 0.04f, worldEffects = listOf("consume_resource"), animation = ActionAnimation.GATHER),
        definition(ActionId.CHOP_TREE, setOf(ActionTargetKind.OBJECT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.TARGET_AVAILABLE, ActionPrerequisite.HAS_TOOL), duration = 8, energy = 0.25f, tool = "axe", worldEffects = listOf("consume_resource"), animation = ActionAnimation.GATHER),
        definition(ActionId.GATHER_WOOD, setOf(ActionTargetKind.OBJECT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.TARGET_AVAILABLE), duration = 4, energy = 0.08f, worldEffects = listOf("consume_resource"), animation = ActionAnimation.GATHER),
        definition(ActionId.GATHER_STONE, setOf(ActionTargetKind.OBJECT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.TARGET_AVAILABLE), duration = 4, energy = 0.08f, worldEffects = listOf("consume_resource"), animation = ActionAnimation.GATHER),
        definition(ActionId.GATHER_HERB, setOf(ActionTargetKind.OBJECT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.TARGET_AVAILABLE), duration = 3, energy = 0.05f, worldEffects = listOf("consume_resource"), animation = ActionAnimation.GATHER),
        // Farming
        definition(ActionId.TILL_SOIL, setOf(ActionTargetKind.FARM_PLOT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.AT_FARM, ActionPrerequisite.HAS_TOOL, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 4, energy = 0.1f, tool = "hoe", worldEffects = listOf("farm:till"), animation = ActionAnimation.FARM),
        definition(ActionId.PLANT, setOf(ActionTargetKind.FARM_PLOT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.AT_FARM, ActionPrerequisite.HAS_ITEM, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 3, energy = 0.06f, inputs = listOf(InventoryStack("seed", 1, InventoryKind.SEED)), itemKind = InventoryKind.SEED, worldEffects = listOf("farm:plant"), animation = ActionAnimation.FARM),
        definition(ActionId.WATER, setOf(ActionTargetKind.FARM_PLOT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.AT_FARM, ActionPrerequisite.HAS_TOOL, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 2, energy = 0.03f, tool = "watering_can", worldEffects = listOf("farm:water"), animation = ActionAnimation.FARM),
        definition(ActionId.POUR_WATER, setOf(ActionTargetKind.FARM_PLOT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.AT_FARM, ActionPrerequisite.HAS_ITEM, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 2, energy = 0.02f, inputs = listOf(InventoryStack("water", 1, InventoryKind.WATER)), itemKind = InventoryKind.WATER, worldEffects = listOf("farm:pour_water"), animation = ActionAnimation.FARM),
        definition(ActionId.FERTILIZE, setOf(ActionTargetKind.FARM_PLOT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.AT_FARM, ActionPrerequisite.HAS_ITEM, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 2, energy = 0.02f, inputs = listOf(InventoryStack("fertilizer", 1, InventoryKind.FERTILIZER)), itemKind = InventoryKind.FERTILIZER, worldEffects = listOf("farm:fertilize"), animation = ActionAnimation.FARM),
        definition(ActionId.HARVEST_CROP, setOf(ActionTargetKind.FARM_PLOT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.AT_FARM, ActionPrerequisite.TARGET_AVAILABLE, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 3, energy = 0.06f, worldEffects = listOf("farm:harvest"), animation = ActionAnimation.FARM),
        definition(ActionId.REMOVE_DEAD_CROP, setOf(ActionTargetKind.FARM_PLOT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.AT_FARM, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 3, energy = 0.05f, worldEffects = listOf("farm:remove_dead"), animation = ActionAnimation.FARM),
        definition(ActionId.STORE_PRODUCE, setOf(ActionTargetKind.FARM_PLOT, ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.AT_FARM, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 3, worldEffects = listOf("farm:store"), animation = ActionAnimation.FARM),
        // Social
        definition(ActionId.GREET, setOf(ActionTargetKind.ACTOR), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 1, effects = mapOf(NeedType.SOCIAL to 3f, NeedType.HAPPINESS to 1f), animation = ActionAnimation.SOCIAL, log = ActionLogPolicy.EVERY_INTERACTION),
        definition(ActionId.TALK, setOf(ActionTargetKind.ACTOR), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 4, effects = mapOf(NeedType.SOCIAL to 12f, NeedType.HAPPINESS to 3f), animation = ActionAnimation.SOCIAL, log = ActionLogPolicy.EVERY_INTERACTION),
        definition(ActionId.PLAY_WITH, setOf(ActionTargetKind.ACTOR), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 6, energy = 0.08f, effects = mapOf(NeedType.SOCIAL to 15f, NeedType.HAPPINESS to 10f), animation = ActionAnimation.SOCIAL, log = ActionLogPolicy.EVERY_INTERACTION),
        definition(ActionId.FOLLOW_NPC, setOf(ActionTargetKind.ACTOR), setOf(ActionPrerequisite.ADJACENT_TARGET), energy = 0.01f, animation = ActionAnimation.WALK, log = ActionLogPolicy.NONE),
        definition(ActionId.GIVE_ITEM, setOf(ActionTargetKind.ACTOR, ActionTargetKind.ITEM), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.HAS_ITEM), duration = 2, effects = mapOf(NeedType.SOCIAL to 5f), animation = ActionAnimation.SOCIAL, log = ActionLogPolicy.EVERY_INTERACTION),
        definition(ActionId.RECEIVE_ITEM, setOf(ActionTargetKind.ACTOR, ActionTargetKind.ITEM), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 2, effects = mapOf(NeedType.HAPPINESS to 5f), animation = ActionAnimation.SOCIAL, log = ActionLogPolicy.EVERY_INTERACTION),
        definition(ActionId.HELP_NPC, setOf(ActionTargetKind.ACTOR, ActionTargetKind.OBJECT), setOf(ActionPrerequisite.ADJACENT_TARGET, ActionPrerequisite.TARGET_AVAILABLE), duration = 5, energy = 0.08f, effects = mapOf(NeedType.SOCIAL to 10f, NeedType.HAPPINESS to 5f), worldEffects = listOf("help_npc"), animation = ActionAnimation.SOCIAL),
        definition(ActionId.THANK, setOf(ActionTargetKind.ACTOR), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 1, effects = mapOf(NeedType.SOCIAL to 2f), animation = ActionAnimation.SOCIAL, log = ActionLogPolicy.EVERY_INTERACTION),
        definition(ActionId.SAY_GOODBYE, setOf(ActionTargetKind.ACTOR), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 1, animation = ActionAnimation.SOCIAL, log = ActionLogPolicy.EVERY_INTERACTION),
        // Economy
        definition(ActionId.BUY, setOf(ActionTargetKind.STRUCTURE, ActionTargetKind.ITEM), setOf(ActionPrerequisite.AT_STRUCTURE, ActionPrerequisite.HAS_MONEY, ActionPrerequisite.DAYTIME, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 2, worldEffects = listOf("buy"), animation = ActionAnimation.USE_STRUCTURE),
        definition(ActionId.SELL, setOf(ActionTargetKind.STRUCTURE, ActionTargetKind.ITEM), setOf(ActionPrerequisite.AT_STRUCTURE, ActionPrerequisite.HAS_ITEM, ActionPrerequisite.DAYTIME, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 2, worldEffects = listOf("sell"), animation = ActionAnimation.USE_STRUCTURE),
        definition(ActionId.TRADE, setOf(ActionTargetKind.ACTOR, ActionTargetKind.ITEM), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 3, worldEffects = listOf("trade"), animation = ActionAnimation.SOCIAL),
        // Recreation
        definition(ActionId.PLAY, setOf(ActionTargetKind.OBJECT, ActionTargetKind.STRUCTURE), duration = 5, energy = 0.05f, effects = mapOf(NeedType.HAPPINESS to 12f, NeedType.SOCIAL to 3f), animation = ActionAnimation.SOCIAL),
        definition(ActionId.WANDER, setOf(ActionTargetKind.TILE), setOf(ActionPrerequisite.WALKABLE_TARGET), energy = 0.01f, effects = mapOf(NeedType.CURIOSITY to 1f), animation = ActionAnimation.WALK, log = ActionLogPolicy.NONE),
        definition(ActionId.USE_ARCADE, setOf(ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.AT_STRUCTURE), duration = 8, energy = 0.04f, effects = mapOf(NeedType.HAPPINESS to 18f, NeedType.SOCIAL to 4f), animation = ActionAnimation.USE_STRUCTURE),
        definition(ActionId.RELAX, setOf(ActionTargetKind.OBJECT, ActionTargetKind.STRUCTURE), duration = 8, effects = mapOf(NeedType.ENERGY to 3f, NeedType.HAPPINESS to 5f), animation = ActionAnimation.REST),
        definition(ActionId.EXPLORE, setOf(ActionTargetKind.TILE), setOf(ActionPrerequisite.WALKABLE_TARGET), energy = 0.02f, effects = mapOf(NeedType.CURIOSITY to 2f), animation = ActionAnimation.WALK, log = ActionLogPolicy.NONE),
        definition(ActionId.INVESTIGATE, setOf(ActionTargetKind.TILE, ActionTargetKind.OBJECT), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 4, effects = mapOf(NeedType.CURIOSITY to 8f), animation = ActionAnimation.INSPECT),
        // Existing ADT activities
        definition(ActionId.STUDY, setOf(ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.AT_STRUCTURE, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 20, energy = 0.12f, effects = mapOf(NeedType.CURIOSITY to 5f), animation = ActionAnimation.STUDY),
        definition(ActionId.WORK, setOf(ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.AT_STRUCTURE, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 20, energy = 0.16f, effects = mapOf(NeedType.HAPPINESS to -1f), animation = ActionAnimation.WORK),
        definition(ActionId.TRAIN_BOXING, setOf(ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.AT_STRUCTURE), duration = 15, energy = 0.25f, effects = mapOf(NeedType.HEALTH to 1f, NeedType.ENERGY to -2f), animation = ActionAnimation.TRAIN),
        // The treatment catalog, payment, and health change are canonical
        // Android effects. The pure action provides the physical visit
        // boundary and emits a canonical visit request at completion.
        definition(ActionId.VISIT_HOSPITAL, setOf(ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.AT_STRUCTURE, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 5, animation = ActionAnimation.USE_STRUCTURE),
        definition(ActionId.USE_ALCHEMY, setOf(ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.AT_STRUCTURE, ActionPrerequisite.HAS_ITEM), duration = 8, energy = 0.08f, worldEffects = listOf("alchemy"), animation = ActionAnimation.USE_STRUCTURE),
        definition(ActionId.ENTER_DUNGEON, setOf(ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.AT_STRUCTURE, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 3, animation = ActionAnimation.USE_STRUCTURE),
        definition(ActionId.ENTER_ADVENTURE_GATE, setOf(ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.AT_STRUCTURE, ActionPrerequisite.AUTONOMY_PERMISSION), duration = 3, animation = ActionAnimation.USE_STRUCTURE),
        // Generic interaction
        definition(ActionId.OPEN, setOf(ActionTargetKind.OBJECT, ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 1, animation = ActionAnimation.USE_STRUCTURE),
        definition(ActionId.CLOSE, setOf(ActionTargetKind.OBJECT, ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 1, animation = ActionAnimation.USE_STRUCTURE),
        definition(ActionId.USE, setOf(ActionTargetKind.OBJECT, ActionTargetKind.STRUCTURE, ActionTargetKind.ITEM), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 2, animation = ActionAnimation.USE_STRUCTURE),
        definition(ActionId.ACTIVATE, setOf(ActionTargetKind.OBJECT, ActionTargetKind.STRUCTURE), setOf(ActionPrerequisite.ADJACENT_TARGET), duration = 2, animation = ActionAnimation.USE_STRUCTURE)
    ).associateBy(ActionDefinition::id)

    val ids: Set<ActionId>
        get() = definitions.keys

    operator fun get(id: ActionId): ActionDefinition = definitions.getValue(id)

    fun contains(id: ActionId): Boolean = definitions.containsKey(id)
}
