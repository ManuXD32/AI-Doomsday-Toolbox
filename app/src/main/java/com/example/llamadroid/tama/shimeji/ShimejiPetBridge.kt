package com.example.llamadroid.tama.shimeji

import android.content.Context
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.TamaFoodKind
import com.example.llamadroid.tama.data.TamaFoodOption
import com.example.llamadroid.tama.data.TamaPomodoroSettings
import com.example.llamadroid.tama.data.TamaStudyMode
import com.example.llamadroid.tama.data.TamaStudyPhase
import com.example.llamadroid.tama.data.TamaStudyStatus
import com.example.llamadroid.tama.data.foodOptions
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.transfer.TamaTransferFreeze

/**
 * Hook the game engine must implement so the overlay can credit presence happiness.
 * Implemented by [TamaGameEngine]: adds [delta] happiness (clamped 0..100) to the active pet and
 * saves it; ignored while a pet transfer has the pet away.
 */
interface ShimejiPresenceSink {
    suspend fun applyShimejiPresence(delta: Int)
}

/** [quantity] is -1 for free, unlimited foods. */
data class ShimejiFood(
    val id: String,
    val label: String,
    val quantity: Int,
    val hungerGain: Int,
    val happinessGain: Int,
    val kind: TamaFoodKind = TamaFoodKind.FREE,
    val emoji: String = ""
) {
    val unlimited: Boolean get() = quantity < 0
}

data class ShimejiPetSnapshot(
    val name: String,
    val species: PetSpeciesLine,
    val stage: GrowthStage,
    val isSleeping: Boolean,
    /** True while a pet transfer has the pet away; the overlay hides the pet. */
    val away: Boolean,
    val foods: List<ShimejiFood>,
    val foodOptions: List<TamaFoodOption> = emptyList(),
    val sleepStartTime: Long? = null,
    val currentActivity: ActivityType = ActivityType.NONE,
    val activityStartTime: Long? = null,
    val study: ShimejiStudyInfo? = null,
    val energy: Float = 100f
) {
    val isEgg: Boolean get() = stage == GrowthStage.EGG
}

/**
 * Overlay-side access to the active pet. Builds its own [TamaGameEngine] exactly like
 * `PhoneWearGateway` does (the engine reloads the pet from the DB before every action).
 */
class ShimejiPetBridge(context: Context) {
    private val appContext = context.applicationContext
    private val engine: TamaGameEngine = run {
        val database = TamaDatabase.getInstance(appContext)
        val farmRepository = FarmRepository(database.farmDao(), appContext)
        TamaGameEngine(
            context = appContext,
            dao = database.tamaDao(),
            farmEngine = FarmEngine(farmRepository),
            farmRepository = farmRepository,
            settingsRepo = SettingsRepository(appContext)
        )
    }
    private val transferStateDao = TamaDatabase.getInstance(appContext).transferStateDao()

    suspend fun snapshot(): ShimejiPetSnapshot? {
        TamaTransferFreeze.sync(transferStateDao)
        val pet = engine.loadPet() ?: return null
        val options = foodOptions(pet)
        // Compatibility adapter for ShimejiActionPanel: free foods use quantity -1 (unlimited).
        val foods = options.map { ShimejiFood(it.id, it.label(appContext), it.quantity ?: -1, it.hungerGain, it.happinessGain, it.kind, it.emoji) }
        val study = if (pet.currentActivity == ActivityType.STUDYING) {
            engine.refreshActiveStudySession()?.takeIf { it.status == TamaStudyStatus.ACTIVE.name }?.let {
                ShimejiStudyInfo(
                    pomodoro = it.mode == TamaStudyMode.POMODORO.name,
                    phase = runCatching { TamaStudyPhase.valueOf(it.currentPhase) }.getOrDefault(TamaStudyPhase.FOCUS),
                    startedAt = it.startedAt,
                    phaseEndsAt = it.phaseEndsAt
                )
            }
        } else {
            null
        }
        return ShimejiPetSnapshot(
            name = pet.name,
            species = PetSpeciesLine.fromSpeciesId(pet.species),
            stage = pet.stage,
            isSleeping = pet.isSleeping,
            away = TamaTransferFreeze.frozen,
            foods = foods,
            foodOptions = options,
            sleepStartTime = pet.sleepStartTime,
            currentActivity = pet.currentActivity,
            activityStartTime = pet.activityStartTime,
            study = study,
            energy = pet.stats.energy
        )
    }

    suspend fun feed(): TamaGameEngine.ActionResult = engine.feed()

    suspend fun feedWithFood(food: ShimejiFood): TamaGameEngine.ActionResult =
        engine.feedWithFood(food.id, food.hungerGain, food.happinessGain)

    suspend fun clean(): TamaGameEngine.ActionResult = engine.clean()

    suspend fun goToBed(): TamaGameEngine.ActionResult = engine.goToBed()

    suspend fun wakeUp(): TamaGameEngine.ActionResult = engine.wakeUp()

    /** Normal (open ended) study session without labels. */
    suspend fun startStudy(): TamaGameEngine.ActionResult = engine.startNormalStudySession(emptySet(), emptyList())

    /** Pomodoro study session with the default 25/5/15 x4 settings and no labels. */
    suspend fun startPomodoro(): TamaGameEngine.ActionResult =
        engine.startPomodoroStudySession(emptySet(), emptyList(), TamaPomodoroSettings())

    suspend fun startRelax(): TamaGameEngine.ActionResult = engine.startActivity(ActivityType.RELAXING)

    suspend fun stopActivity(): TamaGameEngine.ActionResult = engine.stopActivity()

    /** Returns true once the engine handled [delta] (it ignores it while the pet is away). */
    suspend fun applyPresence(delta: Int): Boolean {
        engine.applyShimejiPresence(delta)
        return true
    }

    fun close() {
        engine.close()
    }
}
