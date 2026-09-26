package com.example.llamadroid.tama.world.runtime

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TamaPotionCatalog
import com.example.llamadroid.tama.data.TamaPotionDefinition
import com.example.llamadroid.tama.data.TamaPotionKind
import com.example.llamadroid.tama.data.TamaPotionVendor
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.ActionState
import com.example.llamadroid.tama.world.core.NpcRole
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.RelationshipProjection
import com.example.llamadroid.tama.world.core.StructureType
import com.example.llamadroid.tama.world.core.WorldEffectRequest
import com.example.llamadroid.tama.world.core.WorldNpc
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.persistence.TamaWorldRelationshipEntity
import com.example.llamadroid.tama.world.persistence.WorldStateStore
import kotlin.math.ceil

/**
 * Completes the physical hospital action at the Android/game boundary.
 *
 * The core owns the approach, duration, and canonical action request. This
 * adapter owns the catalog-backed treatment and commits one pay/heal/event /
 * relationship transaction. It deliberately does not accept a caller-picked
 * potion: the smallest affordable dose that covers the missing health wins,
 * with the largest affordable dose used when a full treatment is out of reach.
 */
internal object WorldHospitalVisit {
    const val CANONICAL_ACTION = "visitHospital"
    const val DOCTOR_ID_ARGUMENT = "doctorId"
    const val DEFAULT_DOCTOR_ID = "hospital_doctor"

    /** Kept in the canonical effect when an action came from autonomous planning. */
    private const val AUTONOMOUS_ARGUMENT = "__worldAutonomous"
    /** Compatibility with an app-shell caller that already normalized the marker. */
    private const val LEGACY_AUTONOMOUS_ARGUMENT = "autonomous"

    private const val MIN_HEALTH = 100f
    private const val FAMILIARITY_GAIN = 1
    private const val FRIENDSHIP_GAIN = 2
    private const val TRUST_GAIN = 1

    private data class DoctorRelationship(
        val before: WorldState,
        val after: WorldState,
        val row: TamaWorldRelationshipEntity,
        val effect: WorldEffectRequest.RelationshipDelta
    )

    suspend fun complete(
        context: Context,
        database: TamaDatabase,
        engine: TamaGameEngine,
        arguments: Map<String, String>,
        /** Newer than Room when travel and completion happen in one batch. */
        currentState: WorldState? = null
    ): TamaGameEngine.ActionResult = database.withTransaction {
        completeInsideTransaction(context, database, engine, arguments, currentState)
    }

    private suspend fun completeInsideTransaction(
        context: Context,
        database: TamaDatabase,
        engine: TamaGameEngine,
        arguments: Map<String, String>,
        currentState: WorldState?
    ): TamaGameEngine.ActionResult {
        val pet = engine.pet.value ?: return failure(context, R.string.tama_error_no_pet)
        when {
            pet.isSleeping -> return failure(context, R.string.tama_world_hospital_pet_asleep, pet.name)
            pet.stage == GrowthStage.EGG -> return failure(context, R.string.tama_world_hospital_egg)
            pet.cycleFrozen -> return failure(context, R.string.tama_world_hospital_frozen)
            pet.stats.health >= MIN_HEALTH -> return failure(context, R.string.tama_world_hospital_full_health, pet.name)
        }

        val state = currentState?.takeIf { it.petId == pet.id }
            ?: WorldStateStore(database).load(pet.id)
            ?: return failure(context, R.string.tama_world_runtime_error_world_missing)
        val clinic = state.structures.firstOrNull {
            it.id == com.example.llamadroid.tama.world.core.LegacyLocationAliases.HOSPITAL &&
                it.type == StructureType.HOSPITAL
        } ?: return failure(context, R.string.tama_world_hospital_unavailable)
        if (state.actor.actorId != pet.id || state.actor.presence != PresenceMode.INTERIOR ||
            state.actor.structureId != clinic.id
        ) {
            return failure(context, R.string.tama_world_hospital_presence_required)
        }

        val doctorId = arguments[DOCTOR_ID_ARGUMENT]?.trim().takeUnless { it.isNullOrEmpty() }
            ?: DEFAULT_DOCTOR_ID
        val doctor = state.npcs.firstOrNull { it.id == doctorId }
            ?: return failure(context, R.string.tama_world_hospital_doctor_unavailable)
        if (!isAvailableAtClinic(doctor, clinic)) {
            return failure(context, R.string.tama_world_hospital_doctor_unavailable)
        }

        val treatment = selectTreatment(pet)
            ?: return failure(context, R.string.tama_world_hospital_no_affordable_treatment)
        val autonomous = arguments[AUTONOMOUS_ARGUMENT].equals("true", ignoreCase = true) ||
            arguments[LEGACY_AUTONOMOUS_ARGUMENT].equals("true", ignoreCase = true)
        if (autonomous && !state.autonomy.permits(ActionId.BUY, purchaseCost = treatment.price)) {
            return failure(context, R.string.tama_world_hospital_autonomy_denied)
        }

        val relationship = doctorRelationship(database, state, doctor, pet.id)
        val result = engine.applyHospitalTreatmentLocked(
            potionId = treatment.id,
            expectedPrice = treatment.price,
            doctorId = doctor.id,
            friendshipAfter = relationship.row.friendship.toInt()
        )
        if (!result.success) return result

        database.worldDao().saveRelationships(listOf(relationship.row))
        // Keep the doctor's relationship in the richer Room projection.
        // WorldNpcEncounters remains the single delta calculation used by
        // ambient and professional encounters. The controller persists its
        // post-effect world snapshot after this adapter returns, so the Room
        // relationship row is the durable source for the pet projection.
        return result
    }

    private fun isAvailableAtClinic(doctor: WorldNpc, clinic: com.example.llamadroid.tama.world.core.WorldStructure): Boolean {
        val definition = com.example.llamadroid.tama.world.core.WorldNpcCatalog.find(doctor.id)
            ?: return false
        if (definition.role != NpcRole.DOCTOR || doctor.role != NpcRole.DOCTOR ||
            definition.jobStructureId != clinic.id || doctor.jobStructureId != clinic.id
        ) return false
        val execution = doctor.execution ?: return false
        if (execution.presence != PresenceMode.INTERIOR || execution.structureId != clinic.id) return false
        if (execution.actionState in setOf(ActionState.BLOCKED, ActionState.INTERRUPTED)) return false
        if (doctor.scheduleState.lowercase() != "working") return false
        return clinic.contains(execution.coordinate) ||
            execution.coordinate.chebyshevDistanceTo(clinic.entrance) <= 1
    }

    private fun selectTreatment(pet: TamaPet): TamaPotionDefinition? {
        val missing = ceil((MIN_HEALTH - pet.stats.health).coerceAtLeast(1f).toDouble()).toInt()
        val affordable = TamaPotionCatalog.byVendor(TamaPotionVendor.HOSPITAL)
            .filter { it.kind == TamaPotionKind.HEALING && (it.healAmount ?: 0) > 0 && pet.money >= it.price }
        return affordable
            .filter { (it.healAmount ?: 0) >= missing }
            .minWithOrNull(compareBy<TamaPotionDefinition> { it.healAmount ?: Int.MAX_VALUE }.thenBy { it.price })
            ?: affordable.maxWithOrNull(compareBy<TamaPotionDefinition> { it.healAmount ?: 0 }.thenByDescending { it.price })
    }

    private suspend fun doctorRelationship(
        database: TamaDatabase,
        state: WorldState,
        doctor: WorldNpc,
        petId: String
    ): DoctorRelationship {
        val stored = database.worldDao().relationships(petId).firstOrNull { it.npcId == doctor.id }
        val persistedProjection = stored?.let {
            RelationshipProjection(it.familiarity.toInt(), it.friendship.toInt(), it.trust.toInt())
        }
        val prior = persistedProjection ?: doctor.relationships[petId] ?: RelationshipProjection()
        val beforeDoctor = doctor.copy(relationships = doctor.relationships + (petId to prior))
        val before = state.copy(npcs = state.npcs.map { if (it.id == doctor.id) beforeDoctor else it })
        val next = prior.copy(
            familiarity = (prior.familiarity + FAMILIARITY_GAIN).coerceIn(0, 100),
            friendship = (prior.friendship + FRIENDSHIP_GAIN).coerceIn(0, 100),
            trust = (prior.trust + TRUST_GAIN).coerceIn(0, 100)
        )
        val afterDoctor = beforeDoctor.copy(relationships = beforeDoctor.relationships + (petId to next))
        val after = before.copy(npcs = before.npcs.map { if (it.id == doctor.id) afterDoctor else it })
        val effect = WorldNpcEncounters.effects(before, after)
            .filterIsInstance<WorldEffectRequest.RelationshipDelta>()
            .singleOrNull { it.npcId == doctor.id }
            ?: WorldEffectRequest.RelationshipDelta(doctor.id, 0, 0, 0, "hospital_visit")
        val row = (stored ?: TamaWorldRelationshipEntity(petId, doctor.id, prior.familiarity.toFloat(),
            prior.friendship.toFloat(), prior.trust.toFloat(), 0L, 0)).copy(
            familiarity = (stored?.familiarity ?: prior.familiarity.toFloat()) + effect.familiarity,
            friendship = (stored?.friendship ?: prior.friendship.toFloat()) + effect.friendship,
            trust = (stored?.trust ?: prior.trust.toFloat()) + effect.trust,
            lastInteraction = state.lastSimulatedAt,
            sharedEventCount = (stored?.sharedEventCount ?: 0) + 1
        )
        return DoctorRelationship(before, after, row, effect)
    }

    private fun failure(context: Context, resource: Int, vararg values: Any): TamaGameEngine.ActionResult =
        TamaGameEngine.ActionResult(false, context.getString(resource, *values))
}
