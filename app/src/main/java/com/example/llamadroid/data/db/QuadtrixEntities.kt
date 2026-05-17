package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.example.llamadroid.quadtrix.QuadtrixOptionKeys
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "quadtrix_profiles",
    indices = [Index(value = ["name"], unique = true)]
)
data class QuadtrixProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val datasetPath: String = "",
    val modelFilename: String = "web_model.bin",
    val modelPath: String = "",
    val arch: String = "qwen3",
    val tokenizer: String = "qwen3",
    val qwenTokenizerJsonPath: String = "",
    val batchSize: Int = 1,
    val gradAccumSteps: Int = 20,
    val blockSize: Int = 256,
    val maxIters: Int = 5000,
    val evalInterval: Int = 250,
    val evalIters: Int = 20,
    val logInterval: Int = 1,
    val threads: Int = 4,
    val learningRate: String = "0.0002",
    val gradClip: String = "1.0",
    val optimizer: String = "adamw8",
    val mathBackend: String = "auto",
    val dropout: String = "0.1",
    val trainSplit: String = "0.9",
    val nEmbd: Int = 256,
    val nHead: Int = 4,
    val nKvHead: Int = 0,
    val headDim: Int = 0,
    val intermediateSize: Int = 0,
    val nLayer: Int = 8,
    val ropeTheta: String = "1000000.0",
    val rmsNormEps: String = "0.000001",
    val tieWordEmbeddings: Boolean = true,
    val seed: Int = 1337,
    val checkpointEvery: Int = 500,
    val weightStorage: String = "int8",
    val activationQuantBits: Int = 8,
    val optimizerStateBits: Int = 8,
    val strictQuantizedWeights: Boolean = false,
    val skipInitialEval: Boolean = false,
    val resume: Boolean = false,
    val resumePath: String = "",
    val parquetTextColumn: String = "",
    val parquetInstructionColumn: String = "instruction",
    val parquetInputColumn: String = "input",
    val parquetOutputColumn: String = "output",
    val distMode: String = "none",
    val distRole: String = "coordinator",
    val workerHost: String = "0.0.0.0",
    val workerPort: Int = 9091,
    val workerToken: String = "",
    val distWorkers: String = "",
    val distSyncInterval: Int = 1,
    val distGradientBits: Int = 32,
    val distShards: String = "auto",
    val distRpcTimeoutSec: Int = 900,
    val distReprobeInterval: Int = 5,
    val distCoordinatorCompute: Boolean = true,
    val distCoordinatorOnly: Boolean = false,
    val webHost: String = "127.0.0.1",
    val webPort: Int = 8080,
    val exportGgufPath: String = "",
    val saveGgufAfterTrain: Boolean = false,
    val ggufOuttype: String = "f16",
    val ggufName: String = "",
    val showGgufInModels: Boolean = true,
    val streamProgress: Boolean = false,
    val streamHost: String = "127.0.0.1",
    val streamLanEnabled: Boolean = false,
    val remoteStreamHost: String = "",
    val remoteStreamPort: Int = 9999,
    val remoteStreamToken: String = "",
    val streamPort: Int = 9999,
    val tokenCacheMode: String = "auto",
    val tokenCacheDir: String = "",
    val tokenizationMode: String = "records",
    val tokenizeLogIntervalSec: Int = 5,
    val printSystemInfo: Boolean = false,
    val noGenerateAfterTrain: Boolean = true,
    val enabledOptions: String = QuadtrixOptionKeys.defaultCsv,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "quadtrix_runs",
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["status"]),
        Index(value = ["startedAt"])
    ]
)
data class QuadtrixRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long? = null,
    val profileName: String,
    val status: String,
    val processMode: String,
    val pid: Long? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val latestEtaSeconds: Long? = null,
    val latestIter: Int = 0,
    val maxIter: Int = 0,
    val latestBatchLoss: Double? = null,
    val latestTrainLoss: Double? = null,
    val latestValLoss: Double? = null,
    val latestGradNorm: Double? = null,
    val logFilePath: String = "",
    val modelOutputDir: String = "",
    val errorMessage: String? = null
)

@Entity(
    tableName = "quadtrix_metrics",
    indices = [
        Index(value = ["runId"]),
        Index(value = ["profileName"]),
        Index(value = ["iter"])
    ]
)
data class QuadtrixMetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long? = null,
    val profileName: String,
    val iter: Int,
    val maxIter: Int = 0,
    val batchLoss: Double? = null,
    val trainLoss: Double? = null,
    val valLoss: Double? = null,
    val gradNorm: Double? = null,
    val elapsedSeconds: Long? = null,
    val etaSeconds: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface QuadtrixProfileDao {
    @Query("SELECT * FROM quadtrix_profiles ORDER BY updatedAt DESC")
    fun observeProfiles(): Flow<List<QuadtrixProfileEntity>>

    @Query("SELECT * FROM quadtrix_profiles WHERE id = :id")
    suspend fun getProfile(id: Long): QuadtrixProfileEntity?

    @Query("SELECT * FROM quadtrix_profiles WHERE name = :name LIMIT 1")
    suspend fun getProfileByName(name: String): QuadtrixProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: QuadtrixProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: QuadtrixProfileEntity)

    @Query("DELETE FROM quadtrix_profiles WHERE id = :id")
    suspend fun deleteProfile(id: Long)
}

@Dao
interface QuadtrixRunDao {
    @Query("SELECT * FROM quadtrix_runs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentRuns(limit: Int = 50): Flow<List<QuadtrixRunEntity>>

    @Query("SELECT * FROM quadtrix_runs WHERE id = :id")
    suspend fun getRun(id: Long): QuadtrixRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: QuadtrixRunEntity): Long

    @Update
    suspend fun updateRun(run: QuadtrixRunEntity)
}

@Dao
interface QuadtrixMetricDao {
    @Query("SELECT * FROM quadtrix_metrics WHERE profileName = :profileName ORDER BY iter ASC, createdAt ASC")
    fun observeMetrics(profileName: String): Flow<List<QuadtrixMetricEntity>>

    @Query("SELECT * FROM quadtrix_metrics WHERE runId = :runId ORDER BY iter ASC, createdAt ASC")
    fun observeRunMetrics(runId: Long): Flow<List<QuadtrixMetricEntity>>

    @Query("SELECT * FROM quadtrix_metrics WHERE profileName = :profileName ORDER BY iter ASC, createdAt ASC")
    suspend fun getMetricsForProfile(profileName: String): List<QuadtrixMetricEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetric(metric: QuadtrixMetricEntity): Long

    @Query("DELETE FROM quadtrix_metrics WHERE profileName = :profileName")
    suspend fun clearForProfile(profileName: String)
}
