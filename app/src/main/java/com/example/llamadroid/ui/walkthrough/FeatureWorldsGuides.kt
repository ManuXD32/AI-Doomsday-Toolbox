package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.R
import com.example.llamadroid.ui.navigation.Screen

/** Guide for 3D worlds: three.js scenes from chat replies, saved in the Library. */
internal object FeatureWorldsGuides {
    val guides: List<FeatureGuide> = listOf(
        FeatureGuide(
            id = "worlds",
            titleRes = R.string.feature_guide_worlds_title,
            route = Screen.WorldLibrary.route,
            routeBases = setOf(Screen.WorldLibrary.route, "world_viewer"),
            recipes = listOf(
                FeatureRecipe(
                    id = "worlds.create_play",
                    titleRes = R.string.feature_recipe_worlds_create_play_title,
                    steps = listOf(
                        worldsStep("worlds.create_play.orient", R.string.feature_step_purpose_title, R.string.feature_step_worlds_create_play_orient, Screen.WorldLibrary.route),
                        worldsStep("worlds.create_play.read", R.string.feature_step_result_title, R.string.feature_step_worlds_create_play_read),
                        worldsStep("worlds.create_play.recover", R.string.feature_step_recover_title, R.string.feature_step_worlds_create_play_recover)
                    )
                )
            )
        )
    )
}

private fun worldsStep(id: String, titleRes: Int, bodyRes: Int, route: String? = null) =
    FeatureGuideStep(id = id, titleRes = titleRes, bodyRes = bodyRes, previewKey = "tools", route = route)
