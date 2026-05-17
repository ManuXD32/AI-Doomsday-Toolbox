package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.EventType
import com.example.llamadroid.tama.data.TAMA_STUDY_EDUCATION_PER_HOUR
import com.example.llamadroid.tama.data.TAMA_STUDY_MAX_REWARD_MS
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TamaStudyMode
import com.example.llamadroid.tama.data.TamaStudyPhase
import com.example.llamadroid.tama.data.TamaStudyStatus
import com.example.llamadroid.tama.db.TamaDao
import com.example.llamadroid.tama.db.TamaEventEntity
import com.example.llamadroid.tama.db.TamaStudySessionEntity
import com.example.llamadroid.widget.TamaPetWidgetProvider
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.roundToInt

object TamaStudySessionSupport {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class StudySessionResult(
        val pet: TamaPet,
        val session: TamaStudySessionEntity?,
        val message: String,
        val completed: Boolean = false,
        val phaseChanged: Boolean = false
    )

    fun decodeLabelIds(session: TamaStudySessionEntity?): List<String> =
        decodeStringList(session?.labelIdsJson)

    fun decodeLabelNames(session: TamaStudySessionEntity?): List<String> =
        decodeStringList(session?.labelNamesSnapshotJson)

    fun isRestPhase(session: TamaStudySessionEntity?): Boolean {
        val phase = parsePhase(session?.currentPhase)
        return phase == TamaStudyPhase.SHORT_REST || phase == TamaStudyPhase.LONG_REST
    }

    fun activeStudyContextLine(context: Context, session: TamaStudySessionEntity?): String {
        if (session == null || session.status != TamaStudyStatus.ACTIVE.name) {
            return context.getString(R.string.tama_chat_study_context_none)
        }
        val mode = when (session.mode) {
            TamaStudyMode.POMODORO.name -> context.getString(R.string.tama_study_mode_pomodoro)
            else -> context.getString(R.string.tama_study_mode_normal)
        }
        val phase = localizedPhase(context, session.currentPhase)
        val labels = decodeLabelNames(session).joinToString(", ").ifBlank {
            context.getString(R.string.tama_study_no_labels_short)
        }
        return context.getString(R.string.tama_chat_study_context_active, mode, phase, labels)
    }

    fun currentPhaseRemainingMs(session: TamaStudySessionEntity?, now: Long): Long {
        return (session?.phaseEndsAt?.minus(now) ?: 0L).coerceAtLeast(0L)
    }

    fun currentPhaseProgress(session: TamaStudySessionEntity?, now: Long): Float {
        if (session == null) return 0f
        val start = session.phaseStartedAt ?: return 0f
        val end = session.phaseEndsAt ?: return 0f
        val total = (end - start).coerceAtLeast(1L)
        return ((now - start).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    fun localizedPhase(context: Context, phaseName: String?): String = when (parsePhase(phaseName)) {
        TamaStudyPhase.FOCUS -> context.getString(R.string.tama_study_phase_focus)
        TamaStudyPhase.SHORT_REST -> context.getString(R.string.tama_study_phase_short_rest)
        TamaStudyPhase.LONG_REST -> context.getString(R.string.tama_study_phase_long_rest)
    }

    suspend fun advanceActiveSession(
        context: Context,
        dao: TamaDao,
        pet: TamaPet,
        now: Long = System.currentTimeMillis(),
        showNotification: Boolean = false
    ): StudySessionResult {
        val active = dao.getActiveStudySession(pet.id)
            ?: return StudySessionResult(pet, null, "")
        if (active.mode != TamaStudyMode.POMODORO.name) {
            return StudySessionResult(pet, active, "")
        }

        var session = active
        var changed = false
        var completed = false
        var lastPhaseForNotification: TamaStudyPhase? = null

        while (session.status == TamaStudyStatus.ACTIVE.name) {
            val phaseEnd = session.phaseEndsAt ?: break
            if (now < phaseEnd) break
            val phase = parsePhase(session.currentPhase)
            val phaseStart = session.phaseStartedAt ?: phaseEnd
            val phaseDuration = (phaseEnd - phaseStart).coerceAtLeast(0L)
            session = addPhaseDuration(session, phase, phaseDuration)
            changed = true

            if (phase == TamaStudyPhase.FOCUS && session.currentRound >= session.roundsPlanned) {
                completed = true
                session = session.copy(
                    status = TamaStudyStatus.COMPLETED.name,
                    phaseEndsAt = null,
                    completedAt = phaseEnd,
                    lastUpdatedAt = now
                )
                break
            }

            val nextPhase = when (phase) {
                TamaStudyPhase.FOCUS -> {
                    if (session.currentRound % 4 == 0) TamaStudyPhase.LONG_REST else TamaStudyPhase.SHORT_REST
                }
                TamaStudyPhase.SHORT_REST,
                TamaStudyPhase.LONG_REST -> TamaStudyPhase.FOCUS
            }
            val nextRound = if (phase == TamaStudyPhase.FOCUS) {
                session.currentRound
            } else {
                (session.currentRound + 1).coerceAtMost(session.roundsPlanned)
            }
            session = session.copy(
                currentRound = nextRound,
                currentPhase = nextPhase.name,
                phaseStartedAt = phaseEnd,
                phaseEndsAt = phaseEnd + phaseDurationMs(session, nextPhase),
                lastUpdatedAt = now
            )
            lastPhaseForNotification = nextPhase
        }

        if (!changed) return StudySessionResult(pet, active, "")

        val updatedPet = if (completed) {
            val education = calculateEducationGain(session)
            val completedAt = session.completedAt ?: now
            val pausedDurationMs = ((completedAt - (pet.activityStartTime ?: active.startedAt)).coerceAtLeast(0L))
            val finalPet = shiftPoopTimersForStudy(
                pet.copy(
                    currentActivity = ActivityType.NONE,
                    currentWorkJobId = null,
                    activityStartTime = null,
                    educationLevel = pet.educationLevel + education
                ),
                pausedDurationMs
            )
            val finalSession = session.copy(educationAwarded = education, lastUpdatedAt = now)
            dao.saveStudySession(finalSession)
            dao.savePet(PetMapper.toEntity(finalPet))
            TamaPetWidgetProvider.refreshAll(context.applicationContext)
            dao.saveEvent(studyEvent(context, finalPet, finalSession, completedAt, completed = true))
            if (showNotification) {
                UnifiedNotificationManager.showTamaStudyNotification(
                    pet = finalPet,
                    title = context.getString(R.string.tama_notification_study_complete_title, finalPet.name),
                    body = context.getString(R.string.tama_notification_study_complete_body, education.roundToInt())
                )
            }
            return StudySessionResult(
                pet = finalPet,
                session = finalSession,
                message = context.getString(R.string.tama_action_study_result, education.roundToInt()),
                completed = true,
                phaseChanged = true
            )
        } else {
            dao.saveStudySession(session)
            if (showNotification && lastPhaseForNotification != null) {
                UnifiedNotificationManager.showTamaStudyNotification(
                    pet = pet,
                    title = context.getString(R.string.tama_notification_study_phase_title, pet.name),
                    body = context.getString(
                        R.string.tama_notification_study_phase_body,
                        localizedPhase(context, lastPhaseForNotification.name)
                    )
                )
            }
            pet
        }

        return StudySessionResult(updatedPet, session, "", phaseChanged = true)
    }

    suspend fun stopActiveSession(
        context: Context,
        dao: TamaDao,
        pet: TamaPet,
        now: Long = System.currentTimeMillis()
    ): StudySessionResult {
        val advanced = advanceActiveSession(context, dao, pet, now, showNotification = false)
        if (advanced.completed) return advanced
        val active = advanced.session ?: dao.getActiveStudySession(pet.id)
        val session = if (active != null) {
            val partialMs = partialPhaseMs(active, now)
            val withPartial = addPhaseDuration(active, parsePhase(active.currentPhase), partialMs)
            val education = calculateEducationGain(withPartial)
            withPartial.copy(
                status = TamaStudyStatus.STOPPED.name,
                stoppedAt = now,
                phaseEndsAt = null,
                educationAwarded = education,
                lastUpdatedAt = now
            )
        } else {
            legacyNormalSession(pet, now)
        }
        val education = session.educationAwarded
        val pausedDurationMs = ((now - (pet.activityStartTime ?: session.startedAt)).coerceAtLeast(0L))
        val updatedPet = shiftPoopTimersForStudy(
            pet.copy(
                currentActivity = ActivityType.NONE,
                currentWorkJobId = null,
                activityStartTime = null,
                educationLevel = pet.educationLevel + education
            ),
            pausedDurationMs
        )
        dao.saveStudySession(session)
        dao.savePet(PetMapper.toEntity(updatedPet))
        TamaPetWidgetProvider.refreshAll(context.applicationContext)
        dao.saveEvent(studyEvent(context, updatedPet, session, now, completed = false))
        return StudySessionResult(
            pet = updatedPet,
            session = session,
            message = context.getString(R.string.tama_action_study_result, education.roundToInt())
        )
    }

    fun encodeStringList(values: List<String>): String =
        json.encodeToString(values.distinct().filter { it.isNotBlank() })

    private fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    private fun parsePhase(phaseName: String?): TamaStudyPhase =
        runCatching { TamaStudyPhase.valueOf(phaseName ?: TamaStudyPhase.FOCUS.name) }
            .getOrDefault(TamaStudyPhase.FOCUS)

    private fun phaseDurationMs(session: TamaStudySessionEntity, phase: TamaStudyPhase): Long {
        val minutes = when (phase) {
            TamaStudyPhase.FOCUS -> session.focusMinutes
            TamaStudyPhase.SHORT_REST -> session.shortBreakMinutes
            TamaStudyPhase.LONG_REST -> session.longBreakMinutes
        }.coerceAtLeast(1)
        return minutes * 60_000L
    }

    private fun addPhaseDuration(
        session: TamaStudySessionEntity,
        phase: TamaStudyPhase,
        durationMs: Long
    ): TamaStudySessionEntity {
        return if (phase == TamaStudyPhase.FOCUS) {
            session.copy(focusAccumulatedMs = session.focusAccumulatedMs + durationMs)
        } else {
            session.copy(restAccumulatedMs = session.restAccumulatedMs + durationMs)
        }
    }

    private fun partialPhaseMs(session: TamaStudySessionEntity, now: Long): Long {
        val start = session.phaseStartedAt ?: session.startedAt
        val end = session.phaseEndsAt ?: now
        return (now.coerceAtMost(end) - start).coerceAtLeast(0L)
    }

    private fun calculateEducationGain(session: TamaStudySessionEntity): Float {
        val totalMs = (session.focusAccumulatedMs + session.restAccumulatedMs)
            .coerceAtMost(TAMA_STUDY_MAX_REWARD_MS)
        return (totalMs / 3_600_000f) * TAMA_STUDY_EDUCATION_PER_HOUR
    }

    private fun legacyNormalSession(pet: TamaPet, now: Long): TamaStudySessionEntity {
        val startedAt = pet.activityStartTime ?: now
        val duration = (now - startedAt).coerceAtLeast(0L)
        val capped = duration.coerceAtMost(TAMA_STUDY_MAX_REWARD_MS)
        val education = (capped / 3_600_000f) * TAMA_STUDY_EDUCATION_PER_HOUR
        return TamaStudySessionEntity(
            id = UUID.randomUUID().toString(),
            petId = pet.id,
            mode = TamaStudyMode.NORMAL.name,
            status = TamaStudyStatus.STOPPED.name,
            labelIdsJson = "[]",
            labelNamesSnapshotJson = "[]",
            focusMinutes = 0,
            shortBreakMinutes = 0,
            longBreakMinutes = 0,
            roundsPlanned = 0,
            currentRound = 0,
            currentPhase = TamaStudyPhase.FOCUS.name,
            phaseStartedAt = startedAt,
            phaseEndsAt = null,
            focusAccumulatedMs = capped,
            restAccumulatedMs = 0L,
            educationAwarded = education,
            startedAt = startedAt,
            completedAt = null,
            stoppedAt = now,
            lastUpdatedAt = now
        )
    }

    private fun shiftPoopTimersForStudy(pet: TamaPet, pausedDurationMs: Long): TamaPet {
        if (pausedDurationMs <= 0L) return pet
        return pet.copy(
            nextPoopAt = pet.nextPoopAt?.plus(pausedDurationMs),
            poopCreatedAt = pet.poopCreatedAt?.plus(pausedDurationMs),
            lastPoopMiscareAt = pet.lastPoopMiscareAt?.plus(pausedDurationMs)
        )
    }

    private fun studyEvent(
        context: Context,
        pet: TamaPet,
        session: TamaStudySessionEntity,
        timestamp: Long,
        completed: Boolean
    ): TamaEventEntity {
        val labels = decodeLabelNames(session).joinToString(", ").ifBlank {
            context.getString(R.string.tama_study_no_labels_short)
        }
        val details = if (completed) {
            context.getString(
                R.string.tama_event_study_session_completed,
                session.educationAwarded.roundToInt(),
                labels
            )
        } else {
            context.getString(
                R.string.tama_event_study_session_stopped,
                session.educationAwarded.roundToInt(),
                labels
            )
        }
        return TamaEventEntity(
            id = UUID.randomUUID().toString(),
            timestamp = timestamp,
            petId = pet.id,
            eventType = EventType.STUDIED.name,
            details = details,
            locationId = pet.currentLocationId,
            npcId = null,
            statsChangeJson = null
        )
    }
}
