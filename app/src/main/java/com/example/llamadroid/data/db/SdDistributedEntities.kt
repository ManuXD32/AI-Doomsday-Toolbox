package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "sd_distributed_workers",
    indices = [Index(value = ["host", "port"], unique = true)]
)
data class SdDistributedWorkerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val host: String,
    val port: Int = 50062,
    val deviceName: String = "Media Worker",
    val ramMB: Int = 4096,
    val threads: Int = 4,
    val backendDevice: String = "",
    val isEnabled: Boolean = true,
    val lastSeenAt: Long = 0L,
    val sortOrder: Int = 0
)

@Entity(tableName = "sd_distributed_master_settings")
data class SdDistributedMasterSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = false,
    val placementMode: String = "AUTO_RAM",
    val backendSpec: String = "",
    val paramsBackendSpec: String = "",
    val autoFit: Boolean = false,
    val autoRamScope: String = "DIFFUSION_ONLY",
    val maxVramEnabled: Boolean = false,
    val maxVramSpec: String = "",
    val splitMode: String = "layer",
    val customFlags: String = "",
    val prompt: String = "",
    val negativePrompt: String = "",
    val dimensions: String = "512 x 512",
    val steps: String = "20",
    val cfg: String = "7.0",
    val seed: String = "-1",
    val sampler: String = "euler_a",
    val scheduler: String = "",
    val batchCount: String = "1",
    val clipSkip: String = "0",
    val strength: String = "0.75",
    val frames: String = "8",
    val fps: String = "5",
    val runtimeThreads: String = "-1",
    val mmap: Boolean = false,
    val diffusionFa: Boolean = false,
    val vaeTiling: Boolean = false,
    val vaeTileSize: String = "",
    val vaeTileOverlap: String = "0",
    val flowShift: String = "",
    val quantization: String = "",
    val tensorRules: String = "",
    val loraStrength: String = "1.0",
    val controlStrength: String = "0.9",
    val cacheMode: String = "",
    val cacheOption: String = "",
    val scmMask: String = "",
    val scmPolicy: String = "",
    val masterContributes: Boolean = false,
    val masterDisplayName: String = "This device",
    val masterRamMB: Int = 4096,
    val masterThreads: Int = 4,
    val masterBackendDevice: String = "cpu",
    val masterAllowedModules: String = "diffusion,te,vae,controlnet,upscaler",
    val masterDiffusionSharePercent: String = "",
    val imageWorkflowMode: String = "TXT2IMG",
    val imageModelPath: String = "",
    val imageUpscalerModelPath: String = "",
    val imageInputPath: String = "",
    val imageVaePath: String = "",
    val imageTaePath: String = "",
    val imageClipLPath: String = "",
    val imageClipGPath: String = "",
    val imageT5xxlPath: String = "",
    val imageLlmPath: String = "",
    val imageLlmVisionPath: String = "",
    val imagePhotoMakerPath: String = "",
    val imageControlNetEnabled: Boolean = false,
    val imageControlNetPath: String = "",
    val imageLoraEnabled: Boolean = false,
    val imageLoraPath: String = "",
    val imageLoraApplyMode: String = "",
    val imageCustomFlags: String = "",
    val videoWorkflowMode: String = "TXT2VID",
    val videoModelPath: String = "",
    val videoInputPath: String = "",
    val videoUseVae: Boolean = false,
    val videoVaePath: String = "",
    val videoUseT5xxl: Boolean = false,
    val videoT5xxlPath: String = "",
    val videoCustomFlags: String = "",
    val devicesExpanded: Boolean = true,
    val plannerExpanded: Boolean = true,
    val generationExpanded: Boolean = false,
    val imageExpanded: Boolean = true,
    val videoExpanded: Boolean = false,
    val runtimeExpanded: Boolean = false,
    val adaptersExpanded: Boolean = false,
    val expertExpanded: Boolean = true
)

@Entity(
    tableName = "sd_distributed_templates",
    indices = [Index(value = ["name"], unique = true)]
)
data class SdDistributedTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val workflowType: String = "IMAGE",
    val settingsJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sd_distributed_placements")
data class SdDistributedPlacementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val placementMode: String = "AUTO_FIT",
    val backendSpec: String = "",
    val paramsBackendSpec: String = "",
    val autoFit: Boolean = true,
    val maxVramSpec: String = "",
    val splitMode: String = "layer",
    val customFlags: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sd_distributed_runs",
    indices = [Index(value = ["createdAt"])]
)
data class SdDistributedRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val mode: String,
    val modelName: String,
    val rpcServers: String,
    val backendSpec: String,
    val paramsBackendSpec: String,
    val splitMode: String,
    val autoFit: Boolean,
    val maxVramSpec: String,
    val commandPreview: String,
    val status: String = "READY"
)

@Dao
interface SdDistributedDao {
    @Query("SELECT * FROM sd_distributed_workers ORDER BY deviceName ASC")
    fun observeWorkers(): Flow<List<SdDistributedWorkerEntity>>

    @Query("SELECT * FROM sd_distributed_workers WHERE isEnabled = 1 ORDER BY sortOrder ASC, deviceName ASC")
    fun observeEnabledWorkers(): Flow<List<SdDistributedWorkerEntity>>

    @Query("SELECT * FROM sd_distributed_workers ORDER BY sortOrder ASC, deviceName ASC")
    fun observeWorkersOrdered(): Flow<List<SdDistributedWorkerEntity>>

    @Query("SELECT * FROM sd_distributed_workers WHERE isEnabled = 1 ORDER BY sortOrder ASC, deviceName ASC")
    suspend fun getEnabledWorkersOnce(): List<SdDistributedWorkerEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM sd_distributed_workers")
    suspend fun getMaxWorkerSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorker(worker: SdDistributedWorkerEntity): Long

    @Update
    suspend fun updateWorker(worker: SdDistributedWorkerEntity)

    @Query("DELETE FROM sd_distributed_workers WHERE id = :id")
    suspend fun deleteWorker(id: Long)

    @Query("UPDATE sd_distributed_workers SET isEnabled = :enabled WHERE id = :id")
    suspend fun setWorkerEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE sd_distributed_workers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setWorkerSortOrder(id: Long, sortOrder: Int)

    @Query("SELECT * FROM sd_distributed_master_settings WHERE id = 1")
    fun observeMasterSettings(): Flow<SdDistributedMasterSettingsEntity?>

    @Query("SELECT * FROM sd_distributed_master_settings WHERE id = 1")
    suspend fun getMasterSettings(): SdDistributedMasterSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMasterSettings(settings: SdDistributedMasterSettingsEntity): Long

    @Query("SELECT * FROM sd_distributed_templates ORDER BY updatedAt DESC")
    fun observeTemplates(): Flow<List<SdDistributedTemplateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(template: SdDistributedTemplateEntity): Long

    @Query("DELETE FROM sd_distributed_templates WHERE id = :id")
    suspend fun deleteTemplate(id: Long)

    @Query("SELECT * FROM sd_distributed_placements ORDER BY updatedAt DESC")
    fun observePlacements(): Flow<List<SdDistributedPlacementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlacement(placement: SdDistributedPlacementEntity): Long

    @Query("DELETE FROM sd_distributed_placements WHERE id = :id")
    suspend fun deletePlacement(id: Long)

    @Query("SELECT * FROM sd_distributed_runs ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentRuns(limit: Int = 20): Flow<List<SdDistributedRunEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: SdDistributedRunEntity): Long
}
