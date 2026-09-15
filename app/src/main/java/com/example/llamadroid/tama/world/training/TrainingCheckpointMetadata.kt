package com.example.llamadroid.tama.world.training

/**
 * Stable, language-neutral metadata stored beside an app checkpoint index entry. The actual
 * reward values remain owned by the pure environment; this descriptor records the schema and
 * guard inputs needed to interpret a saved run without copying optimizer state into the index.
 */
internal object TrainingCheckpointMetadata {
    private const val REWARD_SCHEMA_VERSION = 1

    fun rewardConfiguration(config: TrainerConfig): String = buildString {
        append("decomposed-v").append(REWARD_SCHEMA_VERSION)
        append(";catalog-v").append(CurriculumCatalog.VERSION)
        append(";curriculum=").append(config.curriculum.id)
        append(";guard.socialCooldownSteps=").append(config.socialCooldownSteps)
        append(";terms=objective,needs,exploration,social,efficiency,-invalid,-danger,-repetition,-stuck")
    }
}
