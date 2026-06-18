package com.example.llamadroid.tama.data

import com.example.llamadroid.R

const val TAMA_TRAINING_EXERCISE_PER_HOUR = TAMA_STUDY_EDUCATION_PER_HOUR
const val TAMA_TRAINING_HAPPINESS_PER_HOUR = 10f

data class TamaTrainingDefinition(
    val id: String,
    val titleRes: Int,
    val requiredExercise: Int,
    val hourlyPay: Long,
    val backgroundAssetPath: String
)

object TamaTrainingCatalog {
    val tiers: List<TamaTrainingDefinition> = listOf(
        TamaTrainingDefinition(
            id = "warmup_bags",
            titleRes = R.string.tama_training_warmup_bags,
            requiredExercise = 0,
            hourlyPay = 8,
            backgroundAssetPath = "tama/backgrounds/boxing_ring_warmup_bags.png"
        ),
        TamaTrainingDefinition(
            id = "footwork_lane",
            titleRes = R.string.tama_training_footwork_lane,
            requiredExercise = 15,
            hourlyPay = 14,
            backgroundAssetPath = "tama/backgrounds/boxing_ring_footwork_lane.png"
        ),
        TamaTrainingDefinition(
            id = "sparring_corner",
            titleRes = R.string.tama_training_sparring_corner,
            requiredExercise = 30,
            hourlyPay = 24,
            backgroundAssetPath = "tama/backgrounds/boxing_ring_sparring_corner.png"
        ),
        TamaTrainingDefinition(
            id = "coach_drills",
            titleRes = R.string.tama_training_coach_drills,
            requiredExercise = 50,
            hourlyPay = 40,
            backgroundAssetPath = "tama/backgrounds/boxing_ring_coach_drills.png"
        ),
        TamaTrainingDefinition(
            id = "champion_spar",
            titleRes = R.string.tama_training_champion_spar,
            requiredExercise = 70,
            hourlyPay = 58,
            backgroundAssetPath = "tama/backgrounds/boxing_ring_champion_spar.png"
        ),
        TamaTrainingDefinition(
            id = "title_ring",
            titleRes = R.string.tama_training_title_ring,
            requiredExercise = 90,
            hourlyPay = 80,
            backgroundAssetPath = "tama/backgrounds/boxing_ring_title_ring.png"
        )
    )

    fun tierById(tierId: String?): TamaTrainingDefinition? =
        tiers.firstOrNull { it.id.equals(tierId, ignoreCase = true) }
}

fun GrowthStage.canTrain(): Boolean = canStudy()
