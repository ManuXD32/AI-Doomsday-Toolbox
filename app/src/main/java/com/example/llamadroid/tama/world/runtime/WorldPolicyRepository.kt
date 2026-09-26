package com.example.llamadroid.tama.world.runtime

import androidx.room.withTransaction
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.TamaActionGate
import com.example.llamadroid.tama.world.persistence.TamaPolicyCheckpointEntity
import com.example.llamadroid.tama.world.policy.RecurrentPolicyInferenceArtifact
import com.example.llamadroid.tama.world.training.BASELINE_LIVING_POLICY_ID
import com.example.llamadroid.tama.world.training.LivingPolicyReference
import com.example.llamadroid.tama.world.training.LivingPolicySummary
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Adopted inference weights are the sole crossing from synthetic training into living state. */
class WorldPolicyRepository(private val database: TamaDatabase, private val world: TamaWorldController) {
    suspend fun adopt(bytes: ByteArray, metadata: String): String = TamaActionGate.run {
        validate(bytes)
        val pet = database.tamaDao().getActivePet() ?: error("pet_missing")
        world.flush()
        val dao = database.worldDao()
        val policies = dao.policies(pet.id)
        val previous = policies.firstOrNull { it.active }
        val nextVersion = (policies.maxOfOrNull { it.version } ?: 0) + 1
        val id = UUID.randomUUID().toString()
        database.withTransaction {
            dao.deactivatePolicies(pet.id)
            dao.savePolicies(listOf(TamaPolicyCheckpointEntity(
                id = id,
                petId = pet.id,
                version = nextVersion,
                parentId = previous?.id,
                modelHash = sha256(bytes),
                inferenceArtifact = Base64.getEncoder().encodeToString(bytes),
                metadataJson = metadata,
                active = true,
                createdAt = System.currentTimeMillis()
            )))
        }
        loadIntoWorld(pet.id)
        id
    }

    /** Returns the active living artifact together with its durable ID and numeric version. */
    suspend fun activeReference(): LivingPolicyReference {
        val petId = database.tamaDao().getActivePet()?.id ?: return LivingPolicyReference.baseline()
        val record = database.worldDao().activePolicy(petId) ?: return LivingPolicyReference.baseline()
        val bytes = decodeValidated(record)
        return LivingPolicyReference(
            id = record.id,
            version = record.version,
            modelHash = record.modelHash,
            inferenceArtifact = bytes,
            sourceCheckpointId = sourceCheckpointId(record.metadataJson)
        )
    }

    suspend fun activeArtifact(): ByteArray? = activeReference().inferenceArtifact

    /** Durable adopted-policy history, plus the deterministic baseline entry. */
    suspend fun history(): List<LivingPolicySummary> {
        val petId = database.tamaDao().getActivePet()?.id
            ?: return listOf(LivingPolicySummary.baseline(active = true))
        val records = database.worldDao().policies(petId)
        val activeId = records.firstOrNull { it.active }?.id
        return buildList {
            add(LivingPolicySummary.baseline(active = activeId == null))
            records.forEach { record ->
                add(LivingPolicySummary(
                    id = record.id,
                    version = record.version,
                    parentId = record.parentId,
                    modelHash = record.modelHash,
                    createdAt = record.createdAt,
                    active = record.active,
                    sourceCheckpointId = sourceCheckpointId(record.metadataJson)
                ))
            }
        }
    }

    suspend fun restore(id: String) = TamaActionGate.run {
        val pet = database.tamaDao().getActivePet() ?: error("pet_missing")
        world.flush()
        val dao = database.worldDao()
        if (id == BASELINE_LIVING_POLICY_ID) {
            dao.deactivatePolicies(pet.id)
            world.installNavigation(null, "baseline-v1")
            return@run
        }
        val checkpoint = dao.policies(pet.id).firstOrNull { it.id == id } ?: error("policy_missing")
        val bytes = decodeValidated(checkpoint)
        database.withTransaction {
            dao.deactivatePolicies(pet.id)
            dao.savePolicies(listOf(checkpoint.copy(active = true)))
        }
        // Keep the validation result above explicit: restoration must never install an unchecked
        // payload even though loadIntoWorld validates again at the world boundary.
        require(bytes.isNotEmpty()) { "policy_empty" }
        loadIntoWorld(pet.id)
    }

    internal suspend fun loadIntoWorld(petId: String) {
        val active = database.worldDao().activePolicy(petId)
        val policy = active?.let {
            val bytes = decodeValidated(it)
            LearnedWorldNavigation(RecurrentPolicyInferenceArtifact.fromByteArray(bytes))
        }
        world.installNavigation(policy, active?.id ?: "baseline-v1")
    }

    private fun decodeValidated(record: TamaPolicyCheckpointEntity): ByteArray {
        val bytes = Base64.getDecoder().decode(record.inferenceArtifact)
        validate(bytes)
        require(sha256(bytes) == record.modelHash) { "policy_hash_mismatch" }
        return bytes
    }

    private fun sourceCheckpointId(metadata: String): String? = metadata
        .split(';')
        .firstOrNull { it.startsWith("sourceCheckpoint=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun validate(bytes: ByteArray) {
            require(bytes.size in 1..1_000_000) { "invalid_inference_artifact_size" }
            val policy = RecurrentPolicyInferenceArtifact.fromByteArray(bytes)
            require(policy.inferenceParameterCount < 250_000) { "invalid_policy_architecture" }
        }
    }
}
