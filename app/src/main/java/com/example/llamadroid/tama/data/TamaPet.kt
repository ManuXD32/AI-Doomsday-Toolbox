package com.example.llamadroid.tama.data

import java.util.UUID
import java.util.Calendar
import kotlinx.serialization.Serializable
import com.example.llamadroid.tama.data.InventoryItem

/**
 * Core Tama pet data model.
 * Represents a virtual pet with stats, relationships, and genetic traits.
 */
@Serializable
data class TamaPet(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val species: String = PetSpeciesLine.DRAGON.id,
    val birthTimestamp: Long = System.currentTimeMillis(),
    val stageProgressStartTime: Long = birthTimestamp,
    val lastDecayTime: Long = System.currentTimeMillis(),  // For stat decay
    val stage: GrowthStage = GrowthStage.EGG,
    val stats: PetStats = PetStats(),
    val mood: Mood = Mood.HAPPY,
    val personality: Personality = Personality.random(),
    val genetics: GeneticTraits = GeneticTraits.random(),
    val relationships: Map<String, Int> = emptyMap(),
    val ownerBondLevel: Float = 50f,
    val educationLevel: Float = 0f,
    val exerciseLevel: Float = 0f,
    val introspectionLevel: Float = 0f,
    val currentLocationId: String = "home",
    val homeRoomId: String = TamaRoomCatalog.PRINCIPAL_ROOM_ID,
    val leftDecorationId: String? = null,
    val rightDecorationId: String? = null,
    val growthLocked: Boolean = false,
    val growthLockStartedAt: Long? = null,
    val money: Long = 100,
    val inventory: List<InventoryItem> = emptyList(),
    // Activity states (persist when app closed)
    val currentActivity: ActivityType = ActivityType.NONE,
    val currentWorkJobId: String? = null,
    val activityStartTime: Long? = null,
    val isSleeping: Boolean = false,
    val sleepStartTime: Long? = null,
    val lastDailyDreamDate: String? = null,
    val pendingDreamAlbumId: String? = null,
    val currentParkEncounter: TamaParkEncounter? = null,
    val currentAmbientNpc: TamaAmbientNpcState? = null,
    val lastRecyclerEncounterDate: String? = null,
    val nextPoopAt: Long? = null,
    val poopCreatedAt: Long? = null,
    val poopCount: Int = 0,
    val lastPoopMiscareAt: Long? = null,
    val lastSleepWarningTime: Long? = null,
    val overnightAwakeDateKey: String? = null,
    val overnightAwakeAccumulatedMs: Long = 0L,
    val miscareCount: Int = 0,
    val isMad: Boolean = false,
    val discoveredLocationIds: Set<String> = setOf("home")
)

const val TAMA_MISCARE_HEALTH_PENALTY = 20f
const val TAMA_RELAX_INTROSPECTION_PER_HOUR = 10f
const val TAMA_INTROSPECTION_HP_STEP = 10f
const val TAMA_INTROSPECTION_HP_PER_STEP = 3

fun TamaPet.isHealthForcedAngry(): Boolean = stats.health < 50f

fun TamaPet.isEffectivelyMad(): Boolean = isMad || isHealthForcedAngry()

fun TamaPet.isPoopGenerationPaused(): Boolean {
    return isSleeping || when (currentActivity) {
        ActivityType.WORKING,
        ActivityType.STUDYING,
        ActivityType.TRAINING,
        ActivityType.RELAXING -> true
        else -> false
    }
}

fun isSleepStatFreezeWindow(now: Long = System.currentTimeMillis()): Boolean {
    val calendar = Calendar.getInstance().apply { timeInMillis = now }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    return hour >= 22 || hour < 8
}

fun TamaPet.applyMiscarePenalty(happinessPenalty: Float = 15f): TamaPet {
    return copy(
        miscareCount = miscareCount + 1,
        isMad = true,
        stats = stats.copy(
            health = (stats.health - TAMA_MISCARE_HEALTH_PENALTY).coerceIn(0f, 100f),
            happiness = (stats.happiness - happinessPenalty).coerceIn(0f, 100f)
        )
    )
}

/**
 * Activities that persist when app is closed.
 */
@Serializable
enum class ActivityType {
    NONE,
    WORKING,
    STUDYING,
    TRAINING,
    RELAXING  // At park
}

/**
 * Growth stages with explicit per-stage durations.
 * `durationInStageMillis` is the hidden timer that determines when this stage evolves.
 * User-visible pet age is tracked separately through `birthTimestamp`.
 */
@Serializable
enum class GrowthStage(
    val displayName: String,
    val durationInStageMillis: Long?
) {
    EGG("Egg", 60_000L),
    BABY("Baby", 60L * 60L * 1000L),
    CHILD("Child", 48L * 60L * 60L * 1000L),
    TEEN("Teen", 48L * 60L * 60L * 1000L),
    ADULT("Adult", 48L * 60L * 60L * 1000L),
    SENIOR("Senior", null);

    companion object {
        @Deprecated(
            message = "Do not infer live Tama stage from lifetime age. Use persisted stage plus stageProgressStartTime instead.",
            replaceWith = ReplaceWith("error(\"Legacy-only helper\")")
        )
        fun fromAgeHours(ageHours: Float): GrowthStage {
            val totalAgeMillis = (ageHours * 60f * 60f * 1000f).toLong().coerceAtLeast(0L)
            var elapsed = totalAgeMillis
            for (stage in entries) {
                val duration = stage.durationInStageMillis ?: return stage
                if (elapsed < duration) return stage
                elapsed -= duration
            }
            return SENIOR
        }

        fun durationUntilNextStageMillis(stage: GrowthStage): Long? = stage.durationInStageMillis
    }
}

fun GrowthStage.canWork(): Boolean = when (this) {
    GrowthStage.EGG,
    GrowthStage.CHILD,
    GrowthStage.TEEN,
    GrowthStage.ADULT,
    GrowthStage.SENIOR -> true
    GrowthStage.BABY -> false
}

/**
 * Core pet stats (0-100 scale).
 */
@Serializable
data class PetStats(
    val hunger: Float = 100f,       // 100 = full, 0 = starving
    val happiness: Float = 100f,
    val health: Float = 100f,
    val energy: Float = 100f,
    val hygiene: Float = 100f
) {
    fun needsAttention(): Boolean = hunger < 30f || happiness < 30f || health < 30f || hygiene < 30f
    fun critical(): Boolean = hunger < 10f || health < 10f
}


/**
 * Pet mood affects behavior and responses.
 */
@Serializable
enum class Mood(val emoji: String) {
    ECSTATIC("😄"),
    HAPPY("🙂"),
    NEUTRAL("😐"),
    SAD("😢"),
    SLEEPY("🥱"),
    ANGRY("😠"),
    SICK("🤒"),
    SLEEPING("😴")
}

/**
 * Pet personality affects responses and interactions.
 */
@Serializable
enum class Personality(val description: String) {
    CHEERFUL("Always optimistic and friendly"),
    SHY("Quiet and reserved"),
    PLAYFUL("Loves games and adventures"),
    LAZY("Prefers rest and relaxation"),
    CURIOUS("Wants to explore everything"),
    BRAVE("Eager for challenges");
    
    companion object {
        fun random(): Personality = Personality.entries.toTypedArray().random()
    }
}

/**
 * Genetic traits for sprite generation.
 * Each trait is inherited and combined to create unique appearances.
 */
@Serializable
data class GeneticTraits(
    val eyeStyle: Int = 0,      // Index into eyes folder
    val earStyle: Int = 0,
    val mouthStyle: Int = 0,
    val headShape: Int = 0,
    val bodyStyle: Int = 0,
    val armStyle: Int = 0,
    val legStyle: Int = 0,
    val colorTint: Int = 0,     // Grayscale variation
    val accessories: List<Int> = emptyList()
) {
    companion object {
        fun random(): GeneticTraits = GeneticTraits(
            eyeStyle = (0..9).random(),
            earStyle = (0..9).random(),
            mouthStyle = (0..9).random(),
            headShape = (0..5).random(),
            bodyStyle = (0..5).random(),
            armStyle = (0..5).random(),
            legStyle = (0..5).random(),
            colorTint = (0..3).random()
        )
        
        /**
         * Combine two parents' genetics for a child.
         */
        fun inherit(parent1: GeneticTraits, parent2: GeneticTraits): GeneticTraits {
            return GeneticTraits(
                eyeStyle = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.eyeStyle else parent2.eyeStyle,
                earStyle = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.earStyle else parent2.earStyle,
                mouthStyle = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.mouthStyle else parent2.mouthStyle,
                headShape = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.headShape else parent2.headShape,
                bodyStyle = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.bodyStyle else parent2.bodyStyle,
                armStyle = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.armStyle else parent2.armStyle,
                legStyle = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.legStyle else parent2.legStyle,
                colorTint = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.colorTint else parent2.colorTint
            )
        }
    }
}
