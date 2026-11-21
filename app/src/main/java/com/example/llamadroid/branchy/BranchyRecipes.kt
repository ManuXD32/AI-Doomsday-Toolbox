package com.example.llamadroid.branchy

import androidx.annotation.StringRes
import com.example.llamadroid.R

/**
 * One recipe stage. [mix] holds curated dataset ids (see `CuratedDatasets`) with sampling weights that
 * sum to 1. The UI resolves them to Room dataset ids when it creates the run (`RunStage` in the config).
 */
data class RecipeStage(
    @StringRes val nameRes: Int,
    val mix: List<Pair<String, Double>>,
    val tokensTarget: Double,
    val lrScale: Double
)

data class BranchyRecipe(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val targetParams: Double,
    val defaultContext: Int,
    val vocab: Int,
    val stages: List<RecipeStage>
) {
    val totalTokens: Double get() = stages.sumOf { it.tokensTarget }

    /** Every curated dataset id used by any stage, without duplicates. */
    fun datasetIds(): List<String> = stages.flatMap { s -> s.mix.map { it.first } }.distinct()
}

object BranchyRecipes {
    /** Stage 1 (pretrain): general knowledge + code + a little stories. */
    private val pretrainMix = listOf(
        "cosmopedia-100k" to 0.35,
        "wikipedia-en" to 0.30,
        "code-instructions-120k" to 0.25,
        "tinystories" to 0.10
    )

    /** Stage 2 (SFT): chat + tool use + code-instruct + Spanish chat. */
    private val sftMix = listOf(
        "smol-smoltalk" to 0.35,
        "ultrachat-200k" to 0.15,
        "hermes-function-calling" to 0.10,
        "glaive-function-calling-v2" to 0.10,
        "codealpaca-20k" to 0.05,
        "code-instructions-120k" to 0.05,
        "openhermes-2.5-es" to 0.15,
        "alpaca-spanish" to 0.05
    )

    private fun recipe(
        id: String, @StringRes titleRes: Int, @StringRes descRes: Int, params: Double,
        vocab: Int, totalTokens: Double
    ): BranchyRecipe {
        val sftTokens = totalTokens * 0.15
        return BranchyRecipe(
            id = id, titleRes = titleRes, descRes = descRes, targetParams = params,
            defaultContext = 1024, vocab = vocab,
            stages = listOf(
                RecipeStage(R.string.branchy_stage_pretrain, pretrainMix, totalTokens - sftTokens, 1.0),
                RecipeStage(R.string.branchy_stage_sft, sftMix, sftTokens, 0.3)
            )
        )
    }

    val all: List<BranchyRecipe> = listOf(
        recipe(
            "mini_assistant_30m", R.string.branchy_recipe_30m_title, R.string.branchy_recipe_30m_desc,
            params = 30e6, vocab = 8192, totalTokens = 3e8
        ),
        recipe(
            "mini_assistant_120m", R.string.branchy_recipe_120m_title, R.string.branchy_recipe_120m_desc,
            params = 120e6, vocab = 16384, totalTokens = 1.2e9
        )
    )

    fun byId(id: String?): BranchyRecipe? = all.firstOrNull { it.id == id }
}
