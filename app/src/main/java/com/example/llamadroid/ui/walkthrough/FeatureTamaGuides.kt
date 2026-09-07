package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.R

/** Detailed guides for the Tama surfaces and the remote FastSD gallery. */
internal object FeatureTamaGuides {
    val guides: List<FeatureGuide> = listOf(
        FeatureGuide(
            id = "tama",
            titleRes = R.string.feature_guide_tama_title,
            route = "tama",
            routeBases = setOf("tama", "tama_chat", "tama_gallery"),
            recipes = listOf(
                tamaRecipe(
                    id = "tama.room",
                    titleRes = R.string.feature_recipe_tama_room_title,
                    previewKey = "tama_room",
                    route = "tama",
                    targetId = "tama.room",
                    eventId = "tama.room",
                    purpose = R.string.feature_step_tama_room_purpose,
                    result = R.string.feature_step_tama_room_result,
                    recover = R.string.feature_step_tama_room_recover
                ),
                tamaRecipe(
                    id = "tama.care",
                    titleRes = R.string.feature_recipe_tama_care_title,
                    previewKey = "tama_care",
                    route = "tama",
                    targetId = "tama.care",
                    eventId = "tama.care",
                    purpose = R.string.feature_step_tama_care_purpose,
                    result = R.string.feature_step_tama_care_result,
                    recover = R.string.feature_step_tama_care_recover
                ),
                tamaRecipe(
                    id = "tama.chat_gallery",
                    titleRes = R.string.feature_recipe_tama_chat_gallery_title,
                    previewKey = "tama_gallery",
                    route = "tama_chat",
                    targetId = "tama.chat",
                    eventId = "tama.chat",
                    purpose = R.string.feature_step_tama_chat_gallery_purpose,
                    result = R.string.feature_step_tama_chat_gallery_result,
                    recover = R.string.feature_step_tama_chat_gallery_recover
                )
            )
        ),
        FeatureGuide(
            id = "tama_farm",
            titleRes = R.string.feature_guide_tama_farm_title,
            route = "farm",
            routeBases = setOf("farm", "farm_barn", "farm_coop"),
            recipes = listOf(
                tamaRecipe(
                    id = "tama.farm",
                    titleRes = R.string.feature_recipe_tama_farm_title,
                    previewKey = "tama_farm",
                    route = "farm",
                    targetId = "tama.farm",
                    eventId = "tama.farm",
                    purpose = R.string.feature_step_tama_farm_purpose,
                    result = R.string.feature_step_tama_farm_result,
                    recover = R.string.feature_step_tama_farm_recover
                ),
                tamaRecipe(
                    id = "tama.livestock",
                    titleRes = R.string.feature_recipe_tama_livestock_title,
                    previewKey = "tama_farm",
                    route = null,
                    targetId = "tama.livestock.feed",
                    eventId = "tama.livestock.feed",
                    purpose = R.string.feature_step_tama_livestock_purpose,
                    result = R.string.feature_step_tama_livestock_result,
                    recover = R.string.feature_step_tama_livestock_recover
                )
            )
        ),
        FeatureGuide(
            id = "tama_store",
            titleRes = R.string.feature_guide_tama_store_title,
            route = "store",
            routeBases = setOf("store"),
            recipes = listOf(
                tamaRecipe(
                    id = "tama.store",
                    titleRes = R.string.feature_recipe_tama_store_title,
                    previewKey = "tama_shop",
                    route = "store",
                    targetId = "tama.store",
                    eventId = "tama.store",
                    purpose = R.string.feature_step_tama_store_purpose,
                    result = R.string.feature_step_tama_store_result,
                    recover = R.string.feature_step_tama_store_recover
                )
            )
        ),
        FeatureGuide(
            id = "tama_arcade",
            titleRes = R.string.feature_guide_tama_arcade_title,
            route = "arcade",
            routeBases = setOf("arcade"),
            recipes = listOf(
                tamaRecipe(
                    id = "tama.arcade",
                    titleRes = R.string.feature_recipe_tama_arcade_title,
                    previewKey = "tama_arcade",
                    route = "arcade",
                    targetId = "tama.arcade",
                    eventId = "tama.arcade",
                    purpose = R.string.feature_step_tama_arcade_purpose,
                    result = R.string.feature_step_tama_arcade_result,
                    recover = R.string.feature_step_tama_arcade_recover
                )
            )
        ),
        FeatureGuide(
            id = "tama_adventure",
            titleRes = R.string.feature_guide_tama_adventure_title,
            route = "dungeon",
            routeBases = setOf("dungeon", "adventure", "adventure_gate", "night_arena"),
            recipes = listOf(
                tamaRecipe(
                    id = "tama.dungeon",
                    titleRes = R.string.feature_recipe_tama_dungeon_title,
                    previewKey = "tama_adventures",
                    route = "dungeon",
                    targetId = "tama.dungeon",
                    eventId = "tama.dungeon",
                    purpose = R.string.feature_step_tama_dungeon_purpose,
                    result = R.string.feature_step_tama_dungeon_result,
                    recover = R.string.feature_step_tama_dungeon_recover
                ),
                tamaRecipe(
                    id = "tama.adventure",
                    titleRes = R.string.feature_recipe_tama_adventure_title,
                    previewKey = "tama_adventures",
                    route = null,
                    targetId = "tama.adventure",
                    eventId = "tama.adventure",
                    purpose = R.string.feature_step_tama_adventure_purpose,
                    result = R.string.feature_step_tama_adventure_result,
                    recover = R.string.feature_step_tama_adventure_recover
                ),
                tamaRecipe(
                    id = "tama.gate",
                    titleRes = R.string.feature_recipe_tama_gate_title,
                    previewKey = "tama_adventures",
                    route = "adventure_gate",
                    targetId = "tama.gate",
                    eventId = "tama.gate",
                    purpose = R.string.feature_step_tama_gate_purpose,
                    result = R.string.feature_step_tama_gate_result,
                    recover = R.string.feature_step_tama_gate_recover
                ),
                tamaRecipe(
                    id = "tama.arena",
                    titleRes = R.string.feature_recipe_tama_arena_title,
                    previewKey = "tama_adventures",
                    route = "night_arena",
                    targetId = "tama.arena",
                    eventId = "tama.arena",
                    purpose = R.string.feature_step_tama_arena_purpose,
                    result = R.string.feature_step_tama_arena_result,
                    recover = R.string.feature_step_tama_arena_recover
                )
            )
        ),
        FeatureGuide(
            id = "fastsd",
            titleRes = R.string.feature_guide_fastsd_title,
            route = "fastsd_gallery",
            routeBases = setOf("fastsd_gallery"),
            recipes = listOf(
                tamaRecipe(
                    id = "fastsd.gallery",
                    titleRes = R.string.feature_recipe_fastsd_gallery_title,
                    previewKey = "fastsd_gallery",
                    route = "fastsd_gallery",
                    targetId = "fastsd.gallery",
                    eventId = "fastsd.gallery",
                    purpose = R.string.feature_step_fastsd_gallery_purpose,
                    result = R.string.feature_step_fastsd_gallery_result,
                    recover = R.string.feature_step_fastsd_gallery_recover
                )
            )
        )
    )
}

private fun tamaRecipe(
    id: String,
    titleRes: Int,
    previewKey: String,
    route: String?,
    targetId: String,
    eventId: String,
    purpose: Int,
    result: Int,
    recover: Int
) = FeatureRecipe(
    id = id,
    titleRes = titleRes,
    steps = listOf(
        FeatureGuideStep(
            id = "$id.purpose",
            titleRes = R.string.feature_step_purpose_title,
            bodyRes = purpose,
            previewKey = previewKey,
            targetId = targetId,
            eventId = eventId,
            route = route
        ),
        FeatureGuideStep(
            id = "$id.result",
            titleRes = R.string.feature_step_result_title,
            bodyRes = result,
            previewKey = previewKey
        ),
        FeatureGuideStep(
            id = "$id.recover",
            titleRes = R.string.feature_step_recover_title,
            bodyRes = recover,
            previewKey = previewKey
        )
    )
)
