package com.example.llamadroid.tama.world.training

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Synthetic checkpoints and telemetry only, outside the pet database and portable history exports. */
internal class TrainingRepository(context: Context, petId: String) {
    private val directory = File(context.noBackupFilesDir,
        "tama-world-training/${UUID.nameUUIDFromBytes(petId.toByteArray())}").apply { mkdirs() }
    val resumeFile: File get() = File(directory, "resume.checkpoint")
    private val indexFile = File(directory, "index.json")
    private val stateFile = File(directory, "state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun index(): List<BrainRuntimeCheckpoint> = runCatching {
        if (indexFile.isFile) json.decodeFromString<List<BrainRuntimeCheckpoint>>(indexFile.readText()) else emptyList()
    }.getOrDefault(emptyList())

    fun loadState(): BrainRuntimePersistence? = runCatching {
        if (stateFile.isFile) {
            json.decodeFromString<BrainRuntimePersistence>(stateFile.readText())
        } else {
            null
        }
    }.getOrNull()

    fun saveState(state: BrainRuntimePersistence) {
        writeAtomic(stateFile, json.encodeToString(state).toByteArray())
    }

    /**
     * Persist a new branch head. [parentCheckpointId] is supplied by the controller from the
     * checkpoint actually restored or most recently continued, rather than inferred from index
     * ordering. It is nullable for a first session and retained even if retention later removes
     * the predecessor's files.
     */
    fun save(
        trainer: PpoTrainer,
        artifact: ByteArray,
        parentCheckpointId: String? = null
    ): BrainRuntimeCheckpoint {
        val id = UUID.randomUUID().toString()
        parentCheckpointId?.let { validatedId(it) }
        val snapshot = trainer.snapshot()
        val checkpoint = BrainRuntimeCheckpoint(
            id = id,
            createdAt = System.currentTimeMillis(),
            episodes = snapshot.metrics.episodes,
            curriculumId = snapshot.curriculum.id,
            modelHash = sha256(artifact),
            profile = trainer.config.profile.name,
            policyVersion = snapshot.candidatePolicyVersion,
            rewardConfiguration = TrainingCheckpointMetadata.rewardConfiguration(trainer.config),
            objectiveSteps = snapshot.metrics.objectiveSteps,
            pathEfficiency = snapshot.metrics.pathEfficiency,
            stuckRate = snapshot.metrics.stuckRate,
            criticalNeedsRate = snapshot.metrics.criticalNeedsRate,
            explorationScore = snapshot.metrics.explorationScore,
            parentCheckpointId = parentCheckpointId
        )
        val previousIndex = index()
        val retainedIndex = (listOf(checkpoint) + previousIndex).take(MAX_CHECKPOINTS)
        trainer.save(checkpointFile(id))
        writeAtomic(artifactFile(id), artifact)
        // The index is the commit point. Only after it is durable may files belonging to entries
        // it retired be removed; a failed index write therefore leaves recoverable files.
        writeAtomic(indexFile, json.encodeToString(retainedIndex).toByteArray())
        val retainedIds = retainedIndex.mapTo(HashSet()) { it.id }
        previousIndex.asSequence()
            .map { it.id }
            .filter { it !in retainedIds }
            .forEach(::deleteRetiredCheckpoint)
        return checkpoint
    }

    /**
     * Attach a completed holdout comparison only to the immutable artifact it evaluated. The
     * policy file is never rewritten; the index update is the atomic metadata commit point.
     */
    fun attachEvaluation(
        checkpointId: String,
        evaluation: BrainCheckpointEvaluation
    ): BrainRuntimeCheckpoint {
        val validId = validatedId(checkpointId)
        require(evaluation.checkpointId == validId) { "evaluation_checkpoint_mismatch" }
        val previousIndex = index()
        val target = previousIndex.firstOrNull { it.id == validId } ?: error("checkpoint_missing")
        val artifact = artifactFile(validId).takeIf { it.isFile }?.readBytes() ?: error("artifact_missing")
        val artifactHash = sha256(artifact)
        require(target.modelHash == artifactHash) { "checkpoint_artifact_changed" }
        require(evaluation.candidateArtifactHash == artifactHash) { "evaluation_artifact_mismatch" }
        val updated = target.copy(evaluation = evaluation)
        val updatedIndex = previousIndex.map { if (it.id == validId) updated else it }
        writeAtomic(indexFile, json.encodeToString(updatedIndex).toByteArray())
        return updated
    }

    /**
     * Return a checkpoint only when its indexed hash and on-disk artifact both match. Supplying
     * [preferredId] intentionally prevents a live candidate evaluation from attaching to an
     * arbitrary older checkpoint that happens to contain identical bytes.
     */
    fun checkpointForArtifact(
        artifactHash: String,
        preferredId: String? = null
    ): BrainRuntimeCheckpoint? {
        val candidates = if (preferredId == null) {
            index()
        } else {
            index().filter { it.id == preferredId }
        }
        return candidates.firstOrNull { checkpoint ->
            checkpoint.modelHash == artifactHash && runCatching {
                sha256(artifactFile(checkpoint.id).readBytes()) == artifactHash
            }.getOrDefault(false)
        }
    }

    fun checkpointFile(id: String): File = File(directory, "${validatedId(id)}.checkpoint")
    fun artifactFile(id: String): File = File(directory, "${validatedId(id)}.policy")

    private fun validatedId(id: String): String {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "Unknown checkpoint" }
        return id
    }

    private fun deleteRetiredCheckpoint(id: String) {
        // Only IDs that came from the committed index reach this method. Keep malformed or
        // unrelated files untouched so retention cannot become a path or broad cleanup tool.
        if (runCatching { UUID.fromString(id) }.isFailure) return
        runCatching { checkpointFile(id).delete() }
        runCatching { artifactFile(id).delete() }
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        private fun writeAtomic(file: File, bytes: ByteArray) {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!temporary.renameTo(file)) {
                check(file.delete() && temporary.renameTo(file)) { "Checkpoint replacement failed" }
            }
        }

        private const val MAX_CHECKPOINTS = 50
    }
}
