package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.R

/** A localized guide for one canonical app surface and its route aliases. */
data class FeatureGuide(
    val id: String,
    val titleRes: Int,
    val route: String,
    val routeBases: Set<String>,
    val recipes: List<FeatureRecipe>
)

/** A stable, persisted recipe made of inspectable, recoverable steps. */
data class FeatureRecipe(
    val id: String,
    val titleRes: Int,
    val steps: List<FeatureGuideStep>
)

/** One localized recipe step. [route] is only used for genuine cross-surface navigation. */
data class FeatureGuideStep(
    val id: String,
    val titleRes: Int,
    val bodyRes: Int,
    val previewKey: String,
    val targetId: String? = null,
    val eventId: String? = null,
    val route: String? = null
)

/** Catalog for detailed feature guides, separate from first-run walkthrough lessons. */
object FeatureGuideCatalog {
    const val SESSION_PREFIX = "feature:"

    val guides: List<FeatureGuide> = listOf(
        FeatureGuide(
            id = "home",
            titleRes = R.string.feature_guide_home_title,
            route = "dashboard",
            routeBases = setOf("dashboard", "walkthrough"),
            recipes = listOf(
                FeatureRecipe(
                    id = "home.quickstart",
                    titleRes = R.string.feature_recipe_home_quickstart_title,
                    steps = listOf(
                        FeatureGuideStep(
                            id = "home.quickstart.orient",
                            titleRes = R.string.feature_step_home_quickstart_orient_title,
                            bodyRes = R.string.feature_step_home_quickstart_orient_body,
                            previewKey = "home",
                            targetId = "home.summary",
                            route = null
                        ),
                        FeatureGuideStep(
                            id = "home.quickstart.read",
                            titleRes = R.string.feature_step_home_quickstart_read_title,
                            bodyRes = R.string.feature_step_home_quickstart_read_body,
                            previewKey = "home"
                        ),
                        FeatureGuideStep(
                            id = "home.quickstart.recover",
                            titleRes = R.string.feature_step_home_quickstart_recover_title,
                            bodyRes = R.string.feature_step_home_quickstart_recover_body,
                            previewKey = "home"
                        )
                    )
                )
            )
        ),
        FeatureGuide(
            id = "tools",
            titleRes = R.string.feature_guide_tools_title,
            route = "ai_hub",
            routeBases = setOf("ai_hub"),
            recipes = listOf(
                FeatureRecipe(
                    id = "tools.explore",
                    titleRes = R.string.feature_recipe_tools_explore_title,
                    steps = listOf(
                        FeatureGuideStep(
                            id = "tools.explore.orient",
                            titleRes = R.string.feature_step_tools_explore_orient_title,
                            bodyRes = R.string.feature_step_tools_explore_orient_body,
                            previewKey = "tools",
                            targetId = "tools.search",
                            route = null
                        ),
                        FeatureGuideStep(
                            id = "tools.explore.read",
                            titleRes = R.string.feature_step_tools_explore_read_title,
                            bodyRes = R.string.feature_step_tools_explore_read_body,
                            previewKey = "tools"
                        ),
                        FeatureGuideStep(
                            id = "tools.explore.recover",
                            titleRes = R.string.feature_step_tools_explore_recover_title,
                            bodyRes = R.string.feature_step_tools_explore_recover_body,
                            previewKey = "tools"
                        )
                    )
                )
            )
        )
    ) + FeatureMediaModelGuides.guides + FeatureOtherGuides.guides + FeatureTamaGuides.guides

    /** Resolve a live route, including query strings and parameterized route prefixes. */
    fun forRoute(route: String?): FeatureGuide? {
        val normalized = route?.let(::normalizeRoute) ?: return null
        return guides.firstOrNull { guide ->
            (guide.routeBases + guide.route).any { base ->
                val normalizedBase = normalizeRoute(base)
                normalized == normalizedBase || normalized.startsWith("$normalizedBase/")
            }
        }
    }

    /** Resolve a persisted feature session ID, accepting both prefixed and raw recipe IDs. */
    fun recipe(sessionId: String?): FeatureRecipe? {
        val id = sessionId?.removePrefix(SESSION_PREFIX) ?: return null
        return guides.asSequence().flatMap { it.recipes.asSequence() }.firstOrNull { it.id == id }
    }

    fun sessionId(recipeId: String): String =
        if (recipeId.startsWith(SESSION_PREFIX)) recipeId else SESSION_PREFIX + recipeId

    private fun normalizeRoute(route: String): String =
        route.substringBefore('?').trim('/')
}
