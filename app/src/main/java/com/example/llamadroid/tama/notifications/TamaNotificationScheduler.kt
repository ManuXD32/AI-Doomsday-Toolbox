package com.example.llamadroid.tama.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.llamadroid.R
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.example.llamadroid.service.TamaDeepDreamService
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.service.isForegroundServiceStartNotAllowed
import com.example.llamadroid.tama.data.FarmLivestockType
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.PlantedCrop
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TamaStudyMode
import com.example.llamadroid.tama.data.TamaStudyStatus
import com.example.llamadroid.tama.data.hungryLivestockCount
import com.example.llamadroid.tama.data.isLivestockStructureFull
import com.example.llamadroid.tama.data.livestockNeedsFeed
import com.example.llamadroid.tama.data.nextLivestockFeedDueAt
import com.example.llamadroid.tama.data.isPoopGenerationPaused
import com.example.llamadroid.tama.data.storedLivestockOutput
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.db.TamaDeepDreamRunEntity
import com.example.llamadroid.tama.db.TamaStudySessionEntity
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaDailyDreamManager
import com.example.llamadroid.tama.game.TamaDeepDreamRunCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

object TamaNotificationScheduler {
    private const val PREFS_NAME = "tama_notification_state"
    private const val KEY_PREFIX_DELIVERED = "delivered"
    private const val KEY_PREFIX_LAST_SIGNATURE = "last_signature"
    private const val ACTION_PET_NEED = "com.example.llamadroid.tama.notifications.PET_NEED"
    private const val ACTION_CROP_READY = "com.example.llamadroid.tama.notifications.CROP_READY"
    private const val ACTION_POOP_READY = "com.example.llamadroid.tama.notifications.POOP_READY"
    private const val ACTION_POOP_NEGLECT = "com.example.llamadroid.tama.notifications.POOP_NEGLECT"
    private const val ACTION_DEEP_DREAM = "com.example.llamadroid.tama.notifications.DEEP_DREAM"
    private const val ACTION_BARN_FULL = "com.example.llamadroid.tama.notifications.BARN_FULL"
    private const val ACTION_COOP_FULL = "com.example.llamadroid.tama.notifications.COOP_FULL"
    private const val ACTION_BARN_HUNGRY = "com.example.llamadroid.tama.notifications.BARN_HUNGRY"
    private const val ACTION_COOP_HUNGRY = "com.example.llamadroid.tama.notifications.COOP_HUNGRY"
    private const val ACTION_STUDY_PHASE = "com.example.llamadroid.tama.notifications.STUDY_PHASE"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_PET_ID = "pet_id"
    private const val EXTRA_DUE_AT = "due_at"
    private const val EXTRA_SIGNATURE = "signature"
    private const val ALERT_THRESHOLD = 30f
    private const val POOP_INTERVAL_MIN_MS = 50 * 60 * 1000L
    private const val POOP_INTERVAL_MAX_MS = 120 * 60 * 1000L
    private const val POOP_MISCARE_MS = 50 * 60 * 1000L

    internal enum class AlertKind(val alarmAction: String, val requestCodeBase: Int) {
        PET_NEED(ACTION_PET_NEED, 72_100),
        CROP_READY(ACTION_CROP_READY, 72_200),
        POOP_READY(ACTION_POOP_READY, 72_300),
        POOP_NEGLECT(ACTION_POOP_NEGLECT, 72_400),
        DEEP_DREAM(ACTION_DEEP_DREAM, 72_500),
        BARN_FULL(ACTION_BARN_FULL, 72_600),
        COOP_FULL(ACTION_COOP_FULL, 72_700),
        BARN_HUNGRY(ACTION_BARN_HUNGRY, 72_800),
        COOP_HUNGRY(ACTION_COOP_HUNGRY, 72_900),
        STUDY_PHASE(ACTION_STUDY_PHASE, 73_000)
    }

    internal data class ScheduledAlert(
        val kind: AlertKind,
        val petId: String,
        val dueAtMillis: Long,
        val signature: String
    )

    internal data class PetNeedForecast(
        val statKey: String,
        val dueAtMillis: Long,
        val projectedStats: PetStats
    )

    suspend fun scheduleForPet(context: Context, petId: String) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val database = TamaDatabase.getInstance(appContext)
        val petEntity = database.tamaDao().getPet(petId)
        if (petEntity == null) {
            cancelPetAlarms(appContext, petId)
            return@withContext
        }

        val now = System.currentTimeMillis()
        val pet = PetMapper.toDomain(petEntity)
        if (pet.cycleFrozen) {
            cancelPetAlarms(appContext, pet.id)
            return@withContext
        }
        val farmRepository = FarmRepository(database.farmDao(), appContext)
        val farmEngine = FarmEngine(farmRepository)
        farmEngine.updateFarm(pet.id)
        val tiles = farmRepository.getTiles(pet.id)
        val livestock = farmRepository.getLivestock(pet.id)
        val activeStudySession = database.tamaDao().getActiveStudySession(pet.id)
        TamaDeepDreamRunCoordinator.reconcileRunsForPet(
            context = appContext,
            database = database,
            petId = pet.id,
            now = now,
            startArtworkIfNeeded = false
        )
        val baseDeepDreamAlert = computeDeepDreamAlert(pet, now = now)
        val currentDeepDreamRun = baseDeepDreamAlert?.let {
            database.tamaDao().getDeepDreamRunBySignature(pet.id, it.signature)
        }
        if (currentDeepDreamRun?.status == TamaDeepDreamRunCoordinator.STATUS_FAILED ||
            currentDeepDreamRun?.status == TamaDeepDreamRunCoordinator.STATUS_CANCELLED) {
            clearDelivered(appContext, AlertKind.DEEP_DREAM, pet.id)
        }

        scheduleOrCancelAlert(appContext, pet.id, AlertKind.PET_NEED, computePetNeedAlert(pet))
        scheduleOrCancelAlert(appContext, pet.id, AlertKind.CROP_READY, computeCropReadyAlert(pet.id, tiles))
        scheduleOrCancelAlert(appContext, pet.id, AlertKind.POOP_READY, computePoopReadyAlert(pet, now = now))
        scheduleOrCancelAlert(appContext, pet.id, AlertKind.POOP_NEGLECT, computePoopNeglectAlert(pet, now = now))
        scheduleOrCancelAlert(appContext, pet.id, AlertKind.BARN_FULL, computeLivestockFullAlert(pet.id, livestock, FarmLivestockType.BARN, farmRepository, now))
        scheduleOrCancelAlert(appContext, pet.id, AlertKind.COOP_FULL, computeLivestockFullAlert(pet.id, livestock, FarmLivestockType.COOP, farmRepository, now))
        scheduleOrCancelAlert(appContext, pet.id, AlertKind.BARN_HUNGRY, computeLivestockHungryAlert(pet.id, livestock, FarmLivestockType.BARN, farmRepository, now))
        scheduleOrCancelAlert(appContext, pet.id, AlertKind.COOP_HUNGRY, computeLivestockHungryAlert(pet.id, livestock, FarmLivestockType.COOP, farmRepository, now))
        scheduleOrCancelAlert(appContext, pet.id, AlertKind.STUDY_PHASE, computeStudyPhaseAlert(pet.id, activeStudySession, now))
        scheduleOrCancelAlert(
            appContext,
            pet.id,
            AlertKind.DEEP_DREAM,
            computeDeepDreamAlert(pet, currentDeepDreamRun, now = now)
        )
    }

    suspend fun scheduleAll(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val database = TamaDatabase.getInstance(appContext)
        val activePet = database.tamaDao().getActivePet()
        val petIds = database.tamaDao().getAllPetIds()
        petIds.forEach { petId ->
            if (activePet?.id == petId) {
                scheduleForPet(appContext, petId)
            } else {
                cancelPetAlarms(appContext, petId)
            }
        }
    }

    suspend fun retryPendingDeepDreamForActivePet(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val database = TamaDatabase.getInstance(appContext)
        val activePetEntity = database.tamaDao().getActivePet() ?: return@withContext
        val activePet = PetMapper.toDomain(activePetEntity)
        TamaDeepDreamRunCoordinator.reconcileRunsForPet(
            context = appContext,
            database = database,
            petId = activePet.id,
            startArtworkIfNeeded = true
        )
        val waitingRun = TamaDeepDreamRunCoordinator.getWaitingAppOpenRunForPet(database, activePet.id)
            ?: return@withContext
        val alert = computeDeepDreamAlert(activePet, waitingRun)
        if (alert == null || alert.signature != waitingRun.signature) {
            TamaDeepDreamRunCoordinator.markCancelled(
                database = database,
                runId = waitingRun.id,
                errorMessage = appContext.getString(R.string.tama_deep_dream_error_retry_sleep_changed)
            )
            scheduleForPet(appContext, activePet.id)
            return@withContext
        }

        val started = runCatching {
            ContextCompat.startForegroundService(
                appContext,
                TamaDeepDreamService.createIntent(appContext, activePet.id, waitingRun.signature)
            )
        }.onSuccess {
            recordDeepDreamSchedulerBreadcrumb(
                event = "pending_retry_dispatched",
                petId = activePet.id,
                details = "signature=${waitingRun.signature}"
            )
        }.onFailure { error ->
            val event = if (isForegroundServiceStartNotAllowed(error)) {
                "pending_retry_start_denied"
            } else {
                "pending_retry_start_failed"
            }
            recordDeepDreamSchedulerBreadcrumb(
                event = event,
                petId = activePet.id,
                details = "${error.javaClass.simpleName}: ${error.message}"
            )
        }.isSuccess

        if (!started) return@withContext
    }

    fun cancelPetAlarms(context: Context, petId: String) {
        val appContext = context.applicationContext
        AlertKind.entries.forEach { kind ->
            alarmManager(appContext).cancel(buildAlarmPendingIntent(appContext, petId, kind, null))
            clearDelivered(appContext, kind, petId)
            clearLastSignature(appContext, kind, petId)
        }
    }

    fun cancelPoopAlarms(context: Context, petId: String) {
        val appContext = context.applicationContext
        listOf(AlertKind.POOP_READY, AlertKind.POOP_NEGLECT).forEach { kind ->
            alarmManager(appContext).cancel(buildAlarmPendingIntent(appContext, petId, kind, null))
            clearDelivered(appContext, kind, petId)
        }
    }

    internal fun markDelivered(context: Context, kind: AlertKind, petId: String, signature: String) {
        prefs(context).edit {
            putString(deliveredKey(kind, petId), signature)
        }
    }

    internal fun wasDelivered(context: Context, kind: AlertKind, petId: String, signature: String): Boolean {
        return prefs(context).getString(deliveredKey(kind, petId), null) == signature
    }

    internal fun clearDelivered(context: Context, kind: AlertKind, petId: String) {
        prefs(context).edit {
            remove(deliveredKey(kind, petId))
        }
    }

    private fun deliveredKey(kind: AlertKind, petId: String): String {
        return "${KEY_PREFIX_DELIVERED}_${kind.name.lowercase()}_$petId"
    }

    private fun lastSignatureKey(kind: AlertKind, petId: String): String {
        return "${KEY_PREFIX_LAST_SIGNATURE}_${kind.name.lowercase()}_$petId"
    }

    private fun lastSignature(context: Context, kind: AlertKind, petId: String): String? {
        return prefs(context).getString(lastSignatureKey(kind, petId), null)
    }

    private fun setLastSignature(context: Context, kind: AlertKind, petId: String, signature: String) {
        prefs(context).edit {
            putString(lastSignatureKey(kind, petId), signature)
        }
    }

    private fun clearLastSignature(context: Context, kind: AlertKind, petId: String) {
        prefs(context).edit {
            remove(lastSignatureKey(kind, petId))
        }
    }

    private fun scheduleOrCancelAlert(
        context: Context,
        petId: String,
        kind: AlertKind,
        alert: ScheduledAlert?
    ) {
        val alarmManager = alarmManager(context)
        if (alert == null) {
            alarmManager.cancel(buildAlarmPendingIntent(context, petId, kind, null))
            clearDelivered(context, kind, petId)
            clearLastSignature(context, kind, petId)
            return
        }
        val previousSignature = lastSignature(context, alert.kind, alert.petId)
        if (previousSignature != alert.signature) {
            clearDelivered(context, alert.kind, alert.petId)
        }
        setLastSignature(context, alert.kind, alert.petId, alert.signature)

        if (wasDelivered(context, alert.kind, alert.petId, alert.signature)) {
            alarmManager.cancel(buildAlarmPendingIntent(context, petId, kind, null))
            return
        }

        val pendingIntent = buildAlarmPendingIntent(context, alert.petId, alert.kind, alert)
        val triggerAt = max(System.currentTimeMillis() + 1_000L, alert.dueAtMillis)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun buildAlarmPendingIntent(
        context: Context,
        petId: String,
        kind: AlertKind,
        alert: ScheduledAlert?
    ): PendingIntent {
        val intent = Intent(context, TamaNotificationReceiver::class.java).apply {
            action = kind.alarmAction
            putExtra(EXTRA_KIND, kind.name)
            putExtra(EXTRA_PET_ID, petId)
            if (alert != null) {
                putExtra(EXTRA_DUE_AT, alert.dueAtMillis)
                putExtra(EXTRA_SIGNATURE, alert.signature)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            kind.requestCodeBase + petId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal fun alertKindFromIntent(intent: Intent): AlertKind? {
        return intent.getStringExtra(EXTRA_KIND)?.let {
            runCatching { AlertKind.valueOf(it) }.getOrNull()
        }
    }

    internal fun petIdFromIntent(intent: Intent): String? = intent.getStringExtra(EXTRA_PET_ID)

    internal fun signatureFromIntent(intent: Intent): String? = intent.getStringExtra(EXTRA_SIGNATURE)

    internal fun dueAtFromIntent(intent: Intent): Long = intent.getLongExtra(EXTRA_DUE_AT, 0L)

    internal fun petStatKeyFromSignature(signature: String): String? {
        val parts = signature.split(":")
        return if (parts.size >= 3 && parts.firstOrNull() == "pet") parts[1] else null
    }

    internal fun computePetNeedAlert(pet: TamaPet, now: Long = System.currentTimeMillis()): ScheduledAlert? {
        val forecast = computePetNeedForecast(pet, now) ?: return null
        return ScheduledAlert(
            kind = AlertKind.PET_NEED,
            petId = pet.id,
            dueAtMillis = forecast.dueAtMillis,
            signature = "pet:${forecast.statKey}:${forecast.dueAtMillis}"
        )
    }

    internal fun computeCropReadyAlert(petId: String, tiles: List<FarmTile>, now: Long = System.currentTimeMillis()): ScheduledAlert? {
        val dueAt = earliestCropReadyTime(tiles, now) ?: return null
        if (dueAt <= now) return null
        return ScheduledAlert(
            kind = AlertKind.CROP_READY,
            petId = petId,
            dueAtMillis = dueAt,
            signature = "crop:$dueAt"
        )
    }

    internal fun computePoopReadyAlert(pet: TamaPet, now: Long = System.currentTimeMillis()): ScheduledAlert? {
        if (pet.stage == GrowthStage.EGG || isPoopGenerationPaused(pet)) return null
        if (pet.poopCount >= 4) return null
        val dueAt = pet.nextPoopAt ?: return null
        if (dueAt <= now) return null
        return ScheduledAlert(
            kind = AlertKind.POOP_READY,
            petId = pet.id,
            dueAtMillis = dueAt,
            signature = "poop:${dueAt}:ready"
        )
    }

    internal fun computePoopNeglectAlert(pet: TamaPet, now: Long = System.currentTimeMillis()): ScheduledAlert? {
        if (pet.stage == GrowthStage.EGG || isPoopGenerationPaused(pet)) return null
        val poopCreatedAt = pet.poopCreatedAt ?: return null
        if (pet.lastPoopMiscareAt == poopCreatedAt) return null
        val dueAt = poopCreatedAt + POOP_MISCARE_MS
        return ScheduledAlert(
            kind = AlertKind.POOP_NEGLECT,
            petId = pet.id,
            dueAtMillis = dueAt,
            signature = "poop:${poopCreatedAt}:neglect"
        )
    }

    internal fun computeLivestockFullAlert(
        petId: String,
        livestock: List<com.example.llamadroid.tama.db.FarmLivestockEntity>,
        type: FarmLivestockType,
        farmRepository: FarmRepository,
        now: Long = System.currentTimeMillis()
    ): ScheduledAlert? {
        val entity = livestock.firstOrNull { it.type.equals(type.id, ignoreCase = true) } ?: return null
        val slots = farmRepository.decodeLivestockSlots(entity, type)
        val occupiedSlots = slots.filter { it.occupied }
        if (occupiedSlots.isEmpty()) return null
        val isFull = isLivestockStructureFull(type, slots)
        val dueAt = if (isFull) {
            now
        } else {
            if (occupiedSlots.any { it.storedOutput < type.perAnimalStorageCap && livestockNeedsFeed(it, now) }) {
                return null
            }
            occupiedSlots.maxOfOrNull { slot ->
                if (slot.storedOutput >= type.perAnimalStorageCap) {
                    now
                } else {
                    val remainingUnits = (type.perAnimalStorageCap - slot.storedOutput).coerceAtLeast(1)
                    (slot.lastProductionTime ?: now) + (remainingUnits * type.productionIntervalMs)
                }
            } ?: return null
        }
        val signature = if (isFull) {
            "livestock:${type.id}:full:${occupiedSlots.size}:${storedLivestockOutput(slots)}"
        } else {
            "livestock:${type.id}:$dueAt:${occupiedSlots.size}:${storedLivestockOutput(slots)}"
        }
        return ScheduledAlert(
            kind = if (type == FarmLivestockType.BARN) AlertKind.BARN_FULL else AlertKind.COOP_FULL,
            petId = petId,
            dueAtMillis = dueAt,
            signature = signature
        )
    }

    internal fun computeLivestockHungryAlert(
        petId: String,
        livestock: List<com.example.llamadroid.tama.db.FarmLivestockEntity>,
        type: FarmLivestockType,
        farmRepository: FarmRepository,
        now: Long = System.currentTimeMillis()
    ): ScheduledAlert? {
        val entity = livestock.firstOrNull { it.type.equals(type.id, ignoreCase = true) } ?: return null
        val slots = farmRepository.decodeLivestockSlots(entity, type)
        val occupiedIndexes = slots.withIndex().filter { it.value.occupied }
        if (occupiedIndexes.isEmpty()) return null
        val hungryIndexes = occupiedIndexes.filter { livestockNeedsFeed(it.value, now) }.map { it.index }
        val dueAt = if (hungryIndexes.isNotEmpty()) {
            now
        } else {
            nextLivestockFeedDueAt(slots) ?: return null
        }
        val signature = if (hungryIndexes.isNotEmpty()) {
            "livestock:${type.id}:hungry:${hungryIndexes.joinToString("-")}"
        } else {
            "livestock:${type.id}:feed_due:$dueAt"
        }
        return ScheduledAlert(
            kind = if (type == FarmLivestockType.BARN) AlertKind.BARN_HUNGRY else AlertKind.COOP_HUNGRY,
            petId = petId,
            dueAtMillis = dueAt,
            signature = signature
        )
    }

    internal fun randomPoopDelayMs(): Long {
        return kotlin.random.Random.nextLong(POOP_INTERVAL_MIN_MS, POOP_INTERVAL_MAX_MS + 1)
    }

    internal fun computeDeepDreamAlert(
        pet: TamaPet,
        run: TamaDeepDreamRunEntity? = null,
        now: Long = System.currentTimeMillis()
    ): ScheduledAlert? {
        if (!pet.isSleeping || pet.stage == GrowthStage.EGG) return null
        val sleepStart = pet.sleepStartTime ?: return null
        val eligibleDate = TamaDailyDreamManager.eligibleDeepDreamDate(
            pet = pet,
            now = max(now, sleepStart + (4 * 60 * 60 * 1000L))
        ) ?: run {
            val sleepStartCalendar = java.util.Calendar.getInstance().apply { timeInMillis = sleepStart }
            val sleepHour = sleepStartCalendar.get(java.util.Calendar.HOUR_OF_DAY)
            val sleepMinute = sleepStartCalendar.get(java.util.Calendar.MINUTE)
            val inDeepWindow = when {
                sleepHour > 21 && sleepHour < 24 -> true
                sleepHour < 3 -> true
                sleepHour == 21 -> true
                sleepHour == 3 -> sleepMinute == 0
                else -> false
            }
            if (!inDeepWindow) return null
            TamaDailyDreamManager.formatDreamDate(sleepStart)
        }
        val dueAt = sleepStart + (4 * 60 * 60 * 1000L)
        val alert = ScheduledAlert(
            kind = AlertKind.DEEP_DREAM,
            petId = pet.id,
            dueAtMillis = dueAt,
            signature = "deep:$eligibleDate:$dueAt"
        )
        return if (TamaDeepDreamRunCoordinator.shouldSuppressAlert(run, alert.signature, now)) {
            null
        } else {
            alert
        }
    }

    internal fun computeStudyPhaseAlert(
        petId: String,
        session: TamaStudySessionEntity?,
        now: Long = System.currentTimeMillis()
    ): ScheduledAlert? {
        if (session == null) return null
        if (session.status != TamaStudyStatus.ACTIVE.name) return null
        if (session.mode != TamaStudyMode.POMODORO.name) return null
        val dueAt = session.phaseEndsAt ?: return null
        return ScheduledAlert(
            kind = AlertKind.STUDY_PHASE,
            petId = petId,
            dueAtMillis = dueAt.coerceAtLeast(now),
            signature = "study:${session.id}:${session.currentPhase}:$dueAt"
        )
    }

    private fun isPoopGenerationPaused(pet: TamaPet): Boolean {
        return pet.isPoopGenerationPaused()
    }

    internal fun computePetNeedForecast(pet: TamaPet, now: Long = System.currentTimeMillis()): PetNeedForecast? {
        if (pet.stage == GrowthStage.EGG || pet.isSleeping) return null
        val decayPerHour = com.example.llamadroid.tama.game.TamaGameEngine.STAT_DECAY_PER_HOUR.toFloat()
        val decayMultiplier = if (pet.stage == GrowthStage.BABY) 5f else 1f
        val decayPerMs = (decayPerHour * decayMultiplier) / 3_600_000f
        if (decayPerMs <= 0f) return null

        val projectedNow = projectPetStats(pet, now)
        val candidates = listOf(
            "hunger" to projectedNow.hunger,
            "happiness" to projectedNow.happiness,
            "hygiene" to projectedNow.hygiene
        )

        val earliest = candidates
            .mapNotNull { (key, value) ->
                if (value <= ALERT_THRESHOLD) return@mapNotNull null
                val millisUntil = ((value - ALERT_THRESHOLD) / decayPerMs).toLong()
                PetNeedForecast(key, now + millisUntil, projectedNow)
            }
            .minByOrNull { it.dueAtMillis }

        return earliest
    }

    internal fun projectPetStats(pet: TamaPet, now: Long): PetStats {
        val secondsPassed = ((now - pet.lastDecayTime).coerceAtLeast(0L)) / 1000f
        val decayPerHour = com.example.llamadroid.tama.game.TamaGameEngine.STAT_DECAY_PER_HOUR.toFloat()
        val decayMultiplier = if (pet.stage == GrowthStage.BABY) 5f else 1f
        val decayAmount = (secondsPassed / 3600f) * decayPerHour * decayMultiplier
        if (decayAmount <= 0.001f) return pet.stats
        return pet.stats.copy(
            hunger = (pet.stats.hunger - decayAmount).coerceIn(0f, 100f),
            happiness = (pet.stats.happiness - decayAmount).coerceIn(0f, 100f),
            hygiene = (pet.stats.hygiene - decayAmount).coerceIn(0f, 100f),
            energy = (pet.stats.energy - decayAmount).coerceIn(0f, 100f)
        )
    }

    internal fun earliestCropReadyTime(tiles: List<FarmTile>, now: Long = System.currentTimeMillis()): Long? {
        return tiles.mapNotNull { tile ->
            val crop = tile.crop ?: return@mapNotNull null
            cropReadyTime(crop, now)
        }.minOrNull()
    }

    internal fun readyCropCount(tiles: List<FarmTile>, now: Long = System.currentTimeMillis()): Int {
        return tiles.count { tile ->
            val crop = tile.crop ?: return@count false
            !crop.isDecayed && cropReadyTime(crop, now)?.let { now >= it } == true
        }
    }

    private fun cropReadyTime(crop: PlantedCrop, now: Long): Long? {
        if (crop.isDecayed) return null
        if (crop.stage >= 3) return if (now >= crop.lastStageUpdateTime) crop.lastStageUpdateTime else crop.lastStageUpdateTime
        val info = com.example.llamadroid.tama.data.CropDefinitions.CROPS[crop.type] ?: return null
        var stage = crop.stage
        var lastUpdate = crop.lastStageUpdateTime
        while (stage < 3) {
            val stageDuration = info.stageTimes[stage].let { if (crop.isFertilized) it / 2 else it }
            lastUpdate += stageDuration
            stage++
        }
        return lastUpdate
    }

    private fun alarmManager(context: Context): AlarmManager {
        return context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    private fun recordDeepDreamSchedulerBreadcrumb(
        event: String,
        petId: String,
        details: String? = null
    ) {
        runCatching {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "tama_notification_scheduler",
                sessionId = petId,
                mode = "tama_deep_dream",
                event = event,
                details = details
            )
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
