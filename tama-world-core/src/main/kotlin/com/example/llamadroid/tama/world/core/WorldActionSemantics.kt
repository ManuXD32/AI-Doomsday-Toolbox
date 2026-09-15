package com.example.llamadroid.tama.world.core

/**
 * Pure parsing and target checks shared by action start and completion. The
 * world never invents a selected item, price, or counterparty quantity: a
 * missing or malformed selection is rejected before any effect is emitted.
 */
internal object WorldActionSemantics {
    const val CANONICAL_ACTION_ARGUMENT = "canonicalAction"
    const val AUTONOMOUS_ACTION_ARGUMENT = "__worldAutonomous"
    const val ITEM_ID_ARGUMENT = "itemId"
    const val QUANTITY_ARGUMENT = "quantity"
    const val PRICE_ARGUMENT = "pricePerUnit"
    const val RESULT_ITEM_ARGUMENT = "resultItemId"
    /** Durable marker used by the core's physical follow loop. */
    const val FOLLOW_INTENT_ARGUMENT = "__worldFollow"

    data class PurchaseSelection(
        val itemId: String,
        val quantity: Int,
        val pricePerUnit: Int,
        val totalCost: Int
    )

    data class TransferSelection(
        val itemId: String,
        val quantity: Int
    )

    data class TradeSelection(
        val give: TransferSelection,
        val receive: TransferSelection
    )

    fun canonicalAction(arguments: Map<String, String>): String? =
        arguments[CANONICAL_ACTION_ARGUMENT]?.trim()?.takeIf { it.isNotEmpty() }

    fun selectedItemId(target: ActionTarget?): String? {
        if (target == null) return null
        return target.arguments[ITEM_ID_ARGUMENT]?.trim()?.takeIf { it.isNotEmpty() }
            ?: target.arguments["item"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: target.id?.takeIf { target.kind == ActionTargetKind.ITEM }
    }

    fun quantity(arguments: Map<String, String>, key: String = QUANTITY_ARGUMENT): Int? {
        val raw = arguments[key] ?: return 1
        return raw.trim().toIntOrNull()?.takeIf { it in 1..1_000_000 }
    }

    fun nonNegativeInt(arguments: Map<String, String>, key: String): Int? {
        val raw = arguments[key] ?: return null
        return raw.trim().toIntOrNull()?.takeIf { it in 0..1_000_000_000 }
    }

    fun purchaseSelection(target: ActionTarget?): PurchaseSelection? {
        if (target == null) return null
        val itemId = selectedItemId(target) ?: return null
        val quantity = quantity(target.arguments) ?: return null
        val price = nonNegativeInt(target.arguments, PRICE_ARGUMENT)
            ?: nonNegativeInt(target.arguments, "unitPrice")
            ?: nonNegativeInt(target.arguments, "price")
            ?: return null
        val total = price.toLong() * quantity.toLong()
        if (price <= 0 || total !in 1..Int.MAX_VALUE) return null
        return PurchaseSelection(itemId, quantity, price, total.toInt())
    }

    fun saleSelection(target: ActionTarget?): PurchaseSelection? {
        if (target == null) return null
        val itemId = selectedItemId(target) ?: return null
        val quantity = quantity(target.arguments) ?: return null
        val price = nonNegativeInt(target.arguments, PRICE_ARGUMENT)
            ?: nonNegativeInt(target.arguments, "unitPrice")
            ?: nonNegativeInt(target.arguments, "price")
            ?: return null
        val total = price.toLong() * quantity.toLong()
        if (price <= 0 || total !in 1..Int.MAX_VALUE) return null
        return PurchaseSelection(itemId, quantity, price, total.toInt())
    }

    /** Actions that need an object to be physically within interaction range. */
    fun requiresPhysicalAdjacency(action: ActionId, target: ActionTarget?): Boolean = when {
        action == ActionId.INSPECT && target?.kind in setOf(
            ActionTargetKind.ACTOR,
            ActionTargetKind.OBJECT,
            ActionTargetKind.STRUCTURE
        ) -> true
        target?.kind == ActionTargetKind.OBJECT && action in setOf(
            ActionId.DRINK,
            ActionId.WASH,
            ActionId.PLAY,
            ActionId.RELAX
        ) -> true
        else -> false
    }

    /**
     * Context-sensitive checks which cannot be expressed as one static
     * prerequisite for both item/object and structure targets. These checks
     * run at action start and again at completion, so an actor cannot begin a
     * remote interaction and finish it after the target or presence changes.
     */
    fun contextualFailure(action: ActionId, context: ActionContext): String? {
        val target = context.target ?: return null
        val adjacent = target.coordinate?.let {
            context.actorCoordinate.chebyshevDistanceTo(it) <= 1
        } == true
        if (requiresPhysicalAdjacency(action, target) && !adjacent) {
            return "adjacent_target_required"
        }
        if (target.kind != ActionTargetKind.STRUCTURE) return null
        val sameStructure = context.structureId == null || context.structureId == target.id
        return when (action) {
            ActionId.WASH -> when {
                context.presence != PresenceMode.HOME -> "home_required"
                !sameStructure -> "wrong_structure"
                else -> null
            }
            ActionId.PLAY -> when {
                !sameStructure -> "wrong_structure"
                context.structureType == StructureType.HOME ->
                    if (context.presence != PresenceMode.HOME) "home_required" else null
                context.presence != PresenceMode.INTERIOR -> "structure_required"
                else -> null
            }
            ActionId.RELAX -> when {
                context.presence != PresenceMode.INTERIOR -> "structure_required"
                !sameStructure -> "wrong_structure"
                else -> null
            }
            else -> null
        }
    }

    fun transferSelection(target: ActionTarget?): TransferSelection? {
        if (target == null) return null
        val itemId = selectedItemId(target) ?: return null
        val quantity = quantity(target.arguments) ?: return null
        return TransferSelection(itemId, quantity)
    }

    fun tradeSelection(target: ActionTarget?): TradeSelection? {
        val give = transferSelection(target) ?: return null
        val arguments = target?.arguments ?: return null
        val receiveId = arguments["receiveItemId"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val receiveQuantity = quantity(arguments, "receiveQuantity") ?: return null
        return TradeSelection(give, TransferSelection(receiveId, receiveQuantity))
    }

    /** Supports the compact comma/semicolon form and simple JSON string arrays. */
    fun ingredientIds(target: ActionTarget?): List<String> {
        val arguments = target?.arguments ?: return emptyList()
        val raw = arguments["ingredients"]?.trim().orEmpty()
        val source = if (raw.isNotEmpty()) raw else arguments[ITEM_ID_ARGUMENT].orEmpty()
        val structuredIds = Regex("""[\"']?id[\"']?\s*:\s*[\"']([^\"']+)[\"']""")
            .findAll(source)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (structuredIds.isNotEmpty()) return structuredIds
        return source
            .removePrefix("[")
            .removeSuffix("]")
            .split(',', ';', '|')
            .map { it.trim().trim('"', '\'', '{', '}').substringAfter(":").trim('"', '\'') }
            .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    fun resultItemId(target: ActionTarget?): String? =
        target?.arguments?.get(RESULT_ITEM_ARGUMENT)?.trim()?.takeIf { it.isNotEmpty() }

    fun availableQuantity(context: ActionContext, itemId: String): Int =
        maxOf(context.itemQuantity(itemId), if (context.hasItem(itemId)) 1 else 0)

    fun selectionFailure(action: ActionId, context: ActionContext): String? {
        val target = context.target
        val canonical = canonicalAction(target?.arguments.orEmpty())
        if (canonical != null) {
            val rawPrice = target?.arguments?.let { arguments ->
                listOf(PRICE_ARGUMENT, "unitPrice", "price").asSequence()
                    .mapNotNull(arguments::get)
                    .firstOrNull()
            }
            val explicitPrice = rawPrice?.trim()?.toLongOrNull()
            return when (action) {
                ActionId.BUY -> when {
                    target?.kind !in setOf(ActionTargetKind.ITEM, ActionTargetKind.STRUCTURE) ->
                        "purchase_target_required"
                    explicitPrice != null && explicitPrice <= 0L -> "invalid_purchase"
                    purchaseSelection(target) == null -> "purchase_selection_required"
                    else -> null
                }
                ActionId.SELL -> when {
                    target?.kind !in setOf(ActionTargetKind.ITEM, ActionTargetKind.STRUCTURE) ->
                        "sale_target_required"
                    explicitPrice != null && explicitPrice <= 0L -> "invalid_sale"
                    saleSelection(target) == null -> "sale_selection_required"
                    else -> null
                }
                ActionId.GIVE_ITEM, ActionId.RECEIVE_ITEM -> when {
                    target?.kind != ActionTargetKind.ACTOR -> "counterparty_required"
                    transferSelection(target) == null -> "transfer_selection_required"
                    else -> null
                }
                ActionId.TRADE -> when {
                    target?.kind != ActionTargetKind.ACTOR -> "counterparty_required"
                    // Canonical adapters may use TRADE solely as the physical
                    // adjacency/duration boundary. Their durable request
                    // owns the item selection and validates it again at
                    // completion, so generic barter fields must not be
                    // fabricated here.
                    else -> null
                }
                ActionId.EAT, ActionId.USE_MEDICINE -> when {
                    target?.kind != ActionTargetKind.ITEM -> "item_target_required"
                    selectedItemId(target).isNullOrBlank() -> "item_selection_required"
                    else -> null
                }
                ActionId.USE -> if (target?.id.isNullOrBlank()) "target_required" else null
                ActionId.VISIT_HOSPITAL -> null
                else -> null
            }
        }
        return when (action) {
            ActionId.DROP -> {
                val selection = transferSelection(target)
                when {
                    target?.kind != ActionTargetKind.ITEM -> "item_target_required"
                    selection == null -> "drop_selection_required"
                    availableQuantity(context, selection.itemId) < selection.quantity -> "item_quantity_required:${selection.itemId}"
                    else -> null
                }
            }
            ActionId.BUY -> when {
                target?.kind !in setOf(ActionTargetKind.ITEM, ActionTargetKind.STRUCTURE) -> "purchase_target_required"
                purchaseSelection(target) == null -> "purchase_selection_required"
                else -> null
            }
            ActionId.SELL -> {
                val selection = saleSelection(target)
                when {
                    target?.kind !in setOf(ActionTargetKind.ITEM, ActionTargetKind.STRUCTURE) -> "sale_target_required"
                    selection == null -> "sale_selection_required"
                    availableQuantity(context, selection.itemId) < selection.quantity -> "item_quantity_required:${selection.itemId}"
                    else -> null
                }
            }
            ActionId.GIVE_ITEM, ActionId.RECEIVE_ITEM -> {
                val selection = transferSelection(target)
                when {
                    target?.kind != ActionTargetKind.ACTOR -> "counterparty_required"
                    selection == null -> "transfer_selection_required"
                    action == ActionId.GIVE_ITEM && availableQuantity(context, selection.itemId) < selection.quantity ->
                        "item_quantity_required:${selection.itemId}"
                    else -> null
                }
            }
            ActionId.TRADE -> when {
                target?.kind != ActionTargetKind.ACTOR -> "counterparty_required"
                tradeSelection(target) == null -> "trade_selection_required"
                availableQuantity(context, tradeSelection(target)!!.give.itemId) < tradeSelection(target)!!.give.quantity ->
                    "item_quantity_required:${tradeSelection(target)!!.give.itemId}"
                else -> null
            }
            // The core has no canonical inventory/storage repository. A
            // generic request must not claim success without a durable store
            // transition; the Android adapter may provide a canonical action.
            ActionId.STORE_PRODUCE -> "canonical_action_required"
            // Recipe matching, ingredient ownership, and the fallback
            // rotten-crop result live in the canonical Android adapter. The
            // pure core has no recipe catalog, so a generic request must not
            // be able to choose an arbitrary output item.
            ActionId.USE_ALCHEMY -> "canonical_action_required"
            // Treatment selection, price, and healing are canonical game
            // operations. The generic visit is a physical boundary that
            // delegates to that canonical operation at completion; it must
            // never grant health from the pure core itself.
            ActionId.VISIT_HOSPITAL -> null
            ActionId.OPEN, ActionId.CLOSE, ActionId.ACTIVATE ->
                if (target?.id.isNullOrBlank()) "target_required" else null
            ActionId.USE -> when {
                target?.id.isNullOrBlank() -> "target_required"
                target?.kind == ActionTargetKind.ITEM -> "canonical_action_required"
                else -> null
            }
            else -> null
        }
    }

    fun objectFailure(action: ActionId, target: ActionTarget?): String? {
        if (target == null || target.objectType == null) return null
        val expected = when (action) {
            ActionId.FORAGE -> setOf(WorldObjectType.BERRY_PATCH, WorldObjectType.MUSHROOM)
            ActionId.HARVEST_WILD_PLANT -> setOf(
                WorldObjectType.BERRY_PATCH,
                WorldObjectType.MUSHROOM,
                WorldObjectType.HERB_PATCH,
                WorldObjectType.GLOWING_PLANT,
                WorldObjectType.FLOWER_MEADOW
            )
            ActionId.GATHER_WOOD -> setOf(WorldObjectType.FALLEN_LOG)
            ActionId.CHOP_TREE -> setOf(WorldObjectType.TREE)
            ActionId.GATHER_STONE -> setOf(WorldObjectType.STONE)
            ActionId.GATHER_HERB -> setOf(WorldObjectType.HERB_PATCH, WorldObjectType.GLOWING_PLANT, WorldObjectType.MUSHROOM)
            ActionId.PICK_UP -> setOf(WorldObjectType.DROPPED_ITEM, WorldObjectType.SHELL)
            // Generic recreation accepts explicit furniture/toy-like objects;
            // terrain resources and arbitrary world props are not play seats.
            ActionId.PLAY, ActionId.RELAX -> setOf(WorldObjectType.BENCH, WorldObjectType.CUSTOM)
            else -> emptySet()
        }
        return if (expected.isNotEmpty() && target.objectType !in expected) "wrong_object_type" else null
    }

    fun isWaterSource(target: ActionTarget?): Boolean {
        if (target?.objectType == WorldObjectType.POND) return true
        val normalized = target?.id?.lowercase() ?: return false
        return normalized.startsWith("natural_water_") ||
            normalized.startsWith("generated_pond_") ||
            normalized == "pond" || normalized.startsWith("pond_") || normalized.endsWith("_pond") ||
            normalized == "well" || normalized.startsWith("well_") || normalized.endsWith("_well") ||
            normalized == "spring" || normalized.startsWith("spring_") || normalized.endsWith("_spring") ||
            normalized == "stream" || normalized.startsWith("stream_") || normalized.endsWith("_stream") ||
            normalized == "river" || normalized.startsWith("river_") || normalized.endsWith("_river") ||
            normalized == "lake" || normalized.startsWith("lake_") || normalized.endsWith("_lake")
    }
}
