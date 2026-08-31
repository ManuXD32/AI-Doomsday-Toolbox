package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models")
    fun getAllModels(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE type = :type")
    fun getModelsByType(type: ModelType): Flow<List<ModelEntity>>
    
    @Query("SELECT * FROM models WHERE type IN (:types)")
    fun getModelsByTypes(types: List<ModelType>): Flow<List<ModelEntity>>
    
    @Query("SELECT * FROM models WHERE type IN (:types)")
    suspend fun getModelsByTypesSync(types: List<ModelType>): List<ModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: ModelEntity)

    @Delete
    suspend fun deleteModel(model: ModelEntity)
    
    @Query("SELECT * FROM models WHERE filename = :filename LIMIT 1")
    suspend fun getModelByFilename(filename: String): ModelEntity?

    @Query("SELECT * FROM models WHERE path = :path LIMIT 1")
    suspend fun getModelByPath(path: String): ModelEntity?
    
    @Query("DELETE FROM models WHERE filename = :filename")
    suspend fun deleteByFilename(filename: String)
    
    /**
     * Update the filename of a model (used for renaming).
     * Since filename is the primary key, this requires updating both the entity and the file.
     */
    @Query("UPDATE models SET filename = :newFilename, path = :newPath WHERE filename = :oldFilename")
    suspend fun updateFilename(oldFilename: String, newFilename: String, newPath: String)

    @Query("UPDATE models SET filename = :newFilename, path = :newPath, type = :newType, isVision = :isVision WHERE filename = :oldFilename")
    suspend fun updateMetadata(
        oldFilename: String,
        newFilename: String,
        newPath: String,
        newType: ModelType,
        isVision: Boolean
    )

    @Query("UPDATE models SET isVision = :isVision WHERE filename = :filename")
    suspend fun updateVisionSupport(filename: String, isVision: Boolean)
}
