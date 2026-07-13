package com.example.llamadroid.tama.notifications

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.service.GenerationDiagnosticsStore
import com.example.llamadroid.service.TamaDeepDreamService
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.tama.data.EventType
import com.example.llamadroid.tama.data.FarmLivestockType
import com.example.llamadroid.tama.data.Mood
import com.example.llamadroid.tama.data.applyMiscarePenalty
import com.example.llamadroid.tama.data.hungryLivestockCount
import com.example.llamadroid.tama.data.isLivestockStructureFull
import com.example.llamadroid.tama.data.isPoopGenerationPaused
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.db.TamaEventEntity
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaDeepDreamRunCoordinator
import com.example.llamadroid.tama.game.TamaStudySessionSupport
import com.example.llamadroid.widget.TamaPetWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TamaNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleAlarm(context.applicationContext, intent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleAlarm(context: Context, intent: Intent) {
        val kind = TamaNotificationScheduler.alertKindFromIntent(intent) ?: return
        val petId = TamaNotificationScheduler.petIdFromIntent(intent) ?: return
        val signature = TamaNotificationScheduler.signatureFromIntent(intent) ?: return
        if (TamaNotificationScheduler.wasDelivered(context, kind, petId, signature)) {
            return
        }

        val database = TamaDatabase.getInstance(context)
        val petEntity = database.tamaDao().getPet(petId) ?: return
        val pet = PetMapper.toDomain(petEntity)
        if (pet.cycleFrozen) {
            TamaNotificationScheduler.cancelPetAlarms(context, pet.id)
            return
        }
        val farmRepository = FarmRepository(database.farmDao(), context)
        val farmEngine = FarmEngine(farmRepository)
        farmEngine.updateFarm(pet.id)

        when (kind) {
            TamaNotificationScheduler.AlertKind.PET_NEED -> {
                val dueAt = TamaNotificationScheduler.dueAtFromIntent(intent)
                val statKey = TamaNotificationScheduler.petStatKeyFromSignature(signature) ?: return
                if (dueAt > System.currentTimeMillis()) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                UnifiedNotificationManager.showTamaPetNeedNotification(
                    pet = pet,
                    statKey = statKey,
                    offsetPx = 0
                )
                TamaNotificationScheduler.markDelivered(context, kind, petId, signature)
                repeat(4) { step ->
                    delay(180L)
                    val offsetPx = if (step % 2 == 0) 10 else -10
                    UnifiedNotificationManager.showTamaPetNeedNotification(
                        pet = pet,
                        statKey = statKey,
                        offsetPx = offsetPx
                    )
                }
            }

            TamaNotificationScheduler.AlertKind.CROP_READY -> {
                val tiles = farmRepository.getTiles(petId)
                val readyCount = TamaNotificationScheduler.readyCropCount(tiles)
                if (readyCount <= 0) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                UnifiedNotificationManager.showTamaCropReadyNotification(pet = pet, readyCount = readyCount)
                TamaNotificationScheduler.markDelivered(context, kind, petId, signature)
            }

            TamaNotificationScheduler.AlertKind.POOP_READY -> {
                val dueAt = TamaNotificationScheduler.dueAtFromIntent(intent)
                val currentPet = latestPet(database, petId) ?: return
                if (currentPet.isPoopGenerationPaused() || currentPet.stage == com.example.llamadroid.tama.data.GrowthStage.EGG) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                if (currentPet.poopCount >= 4) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                if (dueAt > 0L && dueAt > System.currentTimeMillis()) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                val currentDueAt = currentPet.nextPoopAt
                if (currentDueAt == null || currentDueAt != dueAt) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }

                val createdAt = System.currentTimeMillis()
                val poopCount = (currentPet.poopCount + 1).coerceAtMost(4)
                val updatedPet = currentPet.copy(
                    poopCreatedAt = currentPet.poopCreatedAt ?: createdAt,
                    poopCount = poopCount,
                    nextPoopAt = if (poopCount >= 4) null else createdAt + TamaNotificationScheduler.randomPoopDelayMs(),
                    lastPoopMiscareAt = if (currentPet.poopCount == 0) null else currentPet.lastPoopMiscareAt
                )
                database.tamaDao().savePet(PetMapper.toEntity(updatedPet))
                TamaPetWidgetProvider.refreshAll(context.applicationContext)
                database.tamaDao().saveEvent(
                    TamaEventEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        timestamp = createdAt,
                        petId = updatedPet.id,
                        eventType = EventType.POOPED.name,
                        details = context.getString(R.string.tama_event_pooped, updatedPet.name),
                        locationId = null,
                        npcId = null,
                        statsChangeJson = null
                    )
                )
                UnifiedNotificationManager.showTamaPoopNotification(updatedPet)
                TamaNotificationScheduler.markDelivered(context, kind, petId, signature)
            }

            TamaNotificationScheduler.AlertKind.POOP_NEGLECT -> {
                val currentPet = latestPet(database, petId) ?: return
                val poopCreatedAt = currentPet.poopCreatedAt
                if (currentPet.isPoopGenerationPaused() || poopCreatedAt == null || currentPet.poopCount <= 0 || currentPet.lastPoopMiscareAt == poopCreatedAt) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                val dueAt = TamaNotificationScheduler.dueAtFromIntent(intent)
                if (dueAt > System.currentTimeMillis()) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                if (dueAt != poopCreatedAt + 50 * 60 * 1000L) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }

                val updatedPet = currentPet
                    .applyMiscarePenalty()
                    .copy(
                        lastPoopMiscareAt = poopCreatedAt,
                        mood = Mood.ANGRY
                    )
                database.tamaDao().savePet(PetMapper.toEntity(updatedPet))
                TamaPetWidgetProvider.refreshAll(context.applicationContext)
                database.tamaDao().saveEvent(
                    TamaEventEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        petId = updatedPet.id,
                        eventType = EventType.POOP_NEGLECTED.name,
                        details = context.getString(R.string.tama_event_poop_neglected, updatedPet.name),
                        locationId = null,
                        npcId = null,
                        statsChangeJson = null
                    )
                )
                UnifiedNotificationManager.showTamaPoopNeglectNotification(updatedPet)
                TamaNotificationScheduler.markDelivered(context, kind, petId, signature)
            }

            TamaNotificationScheduler.AlertKind.DEEP_DREAM -> {
                val refreshedPet = PetMapper.toDomain(database.tamaDao().getPet(petId) ?: return)
                val currentRun = database.tamaDao().getDeepDreamRunBySignature(petId, signature)
                val refreshedAlert = TamaNotificationScheduler.computeDeepDreamAlert(refreshedPet, currentRun)
                if (refreshedAlert == null) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                if (refreshedAlert.signature != signature) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                val started = runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        TamaDeepDreamService.createIntent(context, petId, signature)
                    )
                }.onFailure { error ->
                    recordDeepDreamStartFailure(context, database, refreshedPet.name, petId, signature, error)
                }.isSuccess
                if (!started) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
            }

            TamaNotificationScheduler.AlertKind.BARN_FULL,
            TamaNotificationScheduler.AlertKind.COOP_FULL -> {
                val type = if (kind == TamaNotificationScheduler.AlertKind.BARN_FULL) {
                    FarmLivestockType.BARN
                } else {
                    FarmLivestockType.COOP
                }
                val refreshedPet = latestPet(database, petId) ?: return
                val livestock = farmRepository.getLivestock(petId, type.id) ?: run {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                val refreshedAlert = TamaNotificationScheduler.computeLivestockFullAlert(
                    petId = petId,
                    livestock = listOf(livestock),
                    type = type,
                    farmRepository = farmRepository
                )
                if (refreshedAlert == null || refreshedAlert.signature != signature) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                val slots = farmRepository.decodeLivestockSlots(livestock, type)
                if (!isLivestockStructureFull(type, slots)) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                UnifiedNotificationManager.showTamaLivestockFullNotification(
                    pet = refreshedPet,
                    type = type
                )
                TamaNotificationScheduler.markDelivered(context, kind, petId, signature)
            }

            TamaNotificationScheduler.AlertKind.BARN_HUNGRY,
            TamaNotificationScheduler.AlertKind.COOP_HUNGRY -> {
                val type = if (kind == TamaNotificationScheduler.AlertKind.BARN_HUNGRY) {
                    FarmLivestockType.BARN
                } else {
                    FarmLivestockType.COOP
                }
                val refreshedPet = latestPet(database, petId) ?: return
                val livestock = farmRepository.getLivestock(petId, type.id) ?: run {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                val refreshedAlert = TamaNotificationScheduler.computeLivestockHungryAlert(
                    petId = petId,
                    livestock = listOf(livestock),
                    type = type,
                    farmRepository = farmRepository
                )
                if (refreshedAlert == null || refreshedAlert.signature != signature) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                val hungryCount = hungryLivestockCount(farmRepository.decodeLivestockSlots(livestock, type))
                if (hungryCount <= 0) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                UnifiedNotificationManager.showTamaLivestockHungryNotification(
                    pet = refreshedPet,
                    type = type,
                    hungryCount = hungryCount
                )
                TamaNotificationScheduler.markDelivered(context, kind, petId, signature)
            }

            TamaNotificationScheduler.AlertKind.STUDY_PHASE -> {
                val dueAt = TamaNotificationScheduler.dueAtFromIntent(intent)
                if (dueAt > System.currentTimeMillis()) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                val refreshedPet = latestPet(database, petId) ?: return
                val activeSession = database.tamaDao().getActiveStudySession(petId)
                val refreshedAlert = TamaNotificationScheduler.computeStudyPhaseAlert(
                    petId = petId,
                    session = activeSession
                )
                if (refreshedAlert == null || refreshedAlert.signature != signature) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                val result = TamaStudySessionSupport.advanceActiveSession(
                    context = context,
                    dao = database.tamaDao(),
                    pet = refreshedPet,
                    now = System.currentTimeMillis(),
                    showNotification = true
                )
                if (!result.completed && !result.phaseChanged) {
                    TamaNotificationScheduler.scheduleForPet(context, petId)
                    return
                }
                TamaNotificationScheduler.markDelivered(context, kind, petId, signature)
            }
        }

        TamaNotificationScheduler.scheduleForPet(context, petId)
    }

    private suspend fun recordDeepDreamStartFailure(
        context: Context,
        database: TamaDatabase,
        petName: String,
        petId: String,
        signature: String,
        error: Throwable
    ) {
        val now = System.currentTimeMillis()
        val dreamDate = signature.split(":").getOrNull(1)
            ?: com.example.llamadroid.tama.game.TamaDailyDreamManager.formatDreamDate(now)
        val existing = database.tamaDao().getDeepDreamRunBySignature(petId, signature)
        val shouldNotify = existing?.status != TamaDeepDreamRunCoordinator.STATUS_WAITING_APP_OPEN
        val claim = TamaDeepDreamRunCoordinator.claimPlanningRun(
            database = database,
            petId = petId,
            signature = signature,
            dreamDate = dreamDate,
            now = now
        )
        TamaDeepDreamRunCoordinator.markWaitingAppOpen(
            database = database,
            runId = claim.run.id,
            errorMessage = error.message ?: context.getString(R.string.error_generic),
            now = now
        )
        if (shouldNotify) {
            UnifiedNotificationManager.showTamaDeepDreamRetryOnOpenNotification(petId, petName)
        }
        val event = when (error) {
            is ForegroundServiceStartNotAllowedException -> "receiver_foreground_start_denied"
            else -> "receiver_foreground_start_failed"
        }
        runCatching {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "tama_notification_receiver",
                sessionId = petId,
                mode = "tama_deep_dream",
                event = event,
                details = "${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }

    private suspend fun latestPet(database: TamaDatabase, petId: String) =
        database.tamaDao().getPet(petId)?.let(PetMapper::toDomain)
}
