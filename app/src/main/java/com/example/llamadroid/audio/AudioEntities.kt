package com.example.llamadroid.audio

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "audio_voice_profiles",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["family"]),
        Index(value = ["adapterId"])
    ]
)
data class AudioVoiceProfileEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val language: String,
    val originalPath: String,
    val normalizedPath: String? = null,
    val denoisedPath: String? = null,
    val sourceUri: String? = null,
    val adapterId: String? = null,
    val family: String? = null,
    val durationMs: Long = 0L,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    @androidx.room.ColumnInfo(defaultValue = "'{}'") val metadataJson: String = "{}"
) {
    fun toProfile(): AudioVoiceProfile = AudioVoiceProfile(
        id = id,
        name = name,
        language = language,
        originalPath = originalPath,
        normalizedPath = normalizedPath,
        denoisedPath = denoisedPath,
        sourceUri = sourceUri,
        adapterId = adapterId,
        family = family,
        durationMs = durationMs,
        sampleRate = sampleRate,
        channels = channels,
        createdAt = createdAt,
        updatedAt = updatedAt,
        metadataJson = metadataJson
    )

    companion object {
        fun fromProfile(profile: AudioVoiceProfile): AudioVoiceProfileEntity = AudioVoiceProfileEntity(
            id = profile.id,
            name = profile.name,
            language = profile.language,
            originalPath = profile.originalPath,
            normalizedPath = profile.normalizedPath,
            denoisedPath = profile.denoisedPath,
            sourceUri = profile.sourceUri,
            adapterId = profile.adapterId,
            family = profile.family,
            durationMs = profile.durationMs,
            sampleRate = profile.sampleRate,
            channels = profile.channels,
            createdAt = profile.createdAt,
            updatedAt = profile.updatedAt,
            metadataJson = profile.metadataJson
        )
    }
}

@Entity(
    tableName = "audio_generation_jobs",
    indices = [
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
        Index(value = ["updatedAt"]),
        Index(value = ["voiceProfileId"]),
        Index(value = ["modelId"])
    ]
)
data class AudioGenerationJobEntity(
    @androidx.room.PrimaryKey val id: String,
    val adapterId: String,
    val family: String,
    val modelId: String,
    val modelPath: String,
    val companionPath: String? = null,
    val modelDisplayName: String,
    val modelLanguage: String? = null,
    val language: String? = null,
    val voiceStyle: String? = null,
    val voiceProfileId: String? = null,
    val referenceAudioPath: String? = null,
    val text: String? = null,
    val sourceUri: String? = null,
    val sourceName: String? = null,
    val speed: Float = 1.0f,
    val totalSteps: Int = 8,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val seed: Long? = null,
    val maxFrames: Int = 512,
    val runtimeThreads: Int = 4,
    val batchSize: Int = 1,
    val microBatchSize: Int = 1,
    val outputFormat: String = "wav",
    val outputSampleRate: Int = 24_000,
    val chunkSize: Int = 800,
    val normalizeReference: Boolean = true,
    val denoiseReference: Boolean = false,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long? = null,
    val includeMetadata: Boolean = true,
    val status: String = AudioJobStatuses.QUEUED,
    val progress: Float = 0f,
    val stageMessage: String = "",
    val completedChunks: Int = 0,
    val totalChunks: Int = 0,
    val wavPath: String? = null,
    val outputPath: String? = null,
    val metadataPath: String? = null,
    val durationMs: Long = 0L,
    val sampleRate: Int = 0,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "'{}'") val metadataJson: String = "{}"
) {
    fun toRequest(): AudioGenerationRequest = AudioGenerationRequest(
        model = AudioModelDescriptor(
            id = modelId,
            family = family,
            modelPath = modelPath,
            companionPath = companionPath,
            displayName = modelDisplayName,
            adapterId = adapterId,
            language = modelLanguage
        ),
        text = text,
        sourceUri = sourceUri,
        sourceName = sourceName,
        voiceProfileId = voiceProfileId,
        referenceAudioPath = referenceAudioPath,
        language = language,
        voiceStyle = voiceStyle,
        speed = speed,
        totalSteps = totalSteps,
        temperature = temperature,
        topP = topP,
        topK = topK,
        seed = seed,
        maxFrames = maxFrames,
        runtimeThreads = runtimeThreads,
        batchSize = batchSize,
        microBatchSize = microBatchSize,
        outputFormat = outputFormat,
        outputSampleRate = outputSampleRate,
        chunkSize = chunkSize,
        normalizeReference = normalizeReference,
        denoiseReference = denoiseReference,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        includeMetadata = includeMetadata,
        metadataJson = metadataJson
    )

    fun snapshot(): AudioJobSnapshot = AudioJobSnapshot(
        id = id,
        status = status,
        model = AudioModelDescriptor(
            id = modelId,
            family = family,
            modelPath = modelPath,
            companionPath = companionPath,
            displayName = modelDisplayName,
            adapterId = adapterId,
            language = modelLanguage
        ),
        voiceProfileId = voiceProfileId,
        progress = progress,
        stageMessage = stageMessage,
        outputPath = outputPath,
        durationMs = durationMs,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        completedChunks = completedChunks,
        totalChunks = totalChunks
    )
}

@Dao
interface AudioDao {
    @Query("SELECT * FROM audio_voice_profiles ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    fun observeVoiceProfiles(): Flow<List<AudioVoiceProfileEntity>>

    @Query("SELECT * FROM audio_voice_profiles ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    suspend fun getVoiceProfiles(): List<AudioVoiceProfileEntity>

    @Query("SELECT * FROM audio_voice_profiles WHERE id = :id LIMIT 1")
    suspend fun getVoiceProfile(id: String): AudioVoiceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoiceProfile(profile: AudioVoiceProfileEntity)

    @Update
    suspend fun updateVoiceProfile(profile: AudioVoiceProfileEntity)

    @Query("DELETE FROM audio_voice_profiles WHERE id = :id")
    suspend fun deleteVoiceProfile(id: String)

    @Query("SELECT * FROM audio_generation_jobs ORDER BY createdAt DESC")
    fun observeJobs(): Flow<List<AudioGenerationJobEntity>>

    @Query("SELECT * FROM audio_generation_jobs WHERE (:family IS NULL OR family = :family) AND (:speechOnly = 0 OR family NOT IN ('stable_audio_music', 'stable_audio_sfx')) ORDER BY CASE WHEN status IN ('preparing', 'running', 'cancelling') THEN 0 WHEN status = 'queued' THEN 1 ELSE 2 END, updatedAt DESC LIMIT 50")
    fun observeUiJobs(family: String? = null, speechOnly: Boolean = false): Flow<List<AudioGenerationJobEntity>>

    @Query("SELECT * FROM audio_generation_jobs WHERE id = :id LIMIT 1")
    fun observeJob(id: String): Flow<AudioGenerationJobEntity?>

    @Query("SELECT * FROM audio_generation_jobs WHERE status IN ('complete', 'cancelled', 'interrupted', 'error') ORDER BY createdAt DESC")
    fun observeHistory(): Flow<List<AudioGenerationJobEntity>>

    @Query("SELECT * FROM audio_generation_jobs WHERE id = :id LIMIT 1")
    suspend fun getJob(id: String): AudioGenerationJobEntity?

    @Query("SELECT * FROM audio_generation_jobs WHERE status IN ('queued', 'preparing', 'running', 'cancelling') ORDER BY createdAt ASC")
    suspend fun getClaimedJobs(): List<AudioGenerationJobEntity>

    @Query("SELECT * FROM audio_generation_jobs WHERE status = 'queued' ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextQueuedJob(): AudioGenerationJobEntity?

    @Query("UPDATE audio_generation_jobs SET status = 'preparing', progress = 0, stageMessage = :message, startedAt = :startedAt, updatedAt = :startedAt, errorMessage = NULL WHERE id = :id AND status = 'queued'")
    suspend fun claimQueuedJob(id: String, message: String, startedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: AudioGenerationJobEntity)

    @Update
    suspend fun updateJob(job: AudioGenerationJobEntity)

    @Query("DELETE FROM audio_generation_jobs WHERE id = :id")
    suspend fun deleteJob(id: String)

    @Query("UPDATE audio_generation_jobs SET status = 'interrupted', stageMessage = :message, completedAt = :updatedAt, updatedAt = :updatedAt WHERE status IN ('queued', 'running', 'preparing', 'cancelling')")
    suspend fun markRecoverableJobsInterrupted(message: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE audio_generation_jobs SET status = 'queued', stageMessage = :message, errorMessage = NULL, updatedAt = :updatedAt WHERE id = :id AND status IN ('error', 'interrupted', 'cancelled')")
    suspend fun requeueJob(id: String, message: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE audio_generation_jobs SET status = 'cancelling', stageMessage = :message, updatedAt = :updatedAt WHERE id = :id AND status IN ('queued', 'preparing', 'running')")
    suspend fun markCancelling(id: String, message: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE audio_generation_jobs SET status = 'cancelled', stageMessage = :message, updatedAt = :updatedAt, completedAt = :updatedAt WHERE id = :id AND status = 'queued'")
    suspend fun cancelQueuedJob(id: String, message: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM audio_generation_jobs WHERE status IN ('preparing', 'running', 'cancelling') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActiveJob(): AudioGenerationJobEntity?

    @Query("SELECT * FROM audio_generation_jobs WHERE voiceProfileId = :profileId ORDER BY createdAt DESC")
    suspend fun getJobsForVoice(profileId: String): List<AudioGenerationJobEntity>
}
