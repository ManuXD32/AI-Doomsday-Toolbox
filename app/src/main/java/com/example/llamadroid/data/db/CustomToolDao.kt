package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomToolDao {
    
    @Query("SELECT * FROM custom_tools WHERE isEnabled = 1 ORDER BY name ASC")
    fun getEnabledTools(): Flow<List<CustomToolEntity>>

    @Query("SELECT * FROM custom_tools WHERE isEnabled = 1 ORDER BY name ASC")
    suspend fun getEnabledToolsOnce(): List<CustomToolEntity>
    
    @Query("SELECT * FROM custom_tools ORDER BY name ASC")
    fun getAllTools(): Flow<List<CustomToolEntity>>
    
    @Query("SELECT * FROM custom_tools WHERE name = :name")
    suspend fun getToolByName(name: String): CustomToolEntity?
    
    @Query("SELECT * FROM custom_tools WHERE LOWER(name) = LOWER(:name) AND isEnabled = 1")
    suspend fun getToolByNameIgnoreCase(name: String): CustomToolEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTool(tool: CustomToolEntity)
    
    @Update
    suspend fun updateTool(tool: CustomToolEntity)
    
    @Delete
    suspend fun deleteTool(tool: CustomToolEntity)
    
    @Query("DELETE FROM custom_tools WHERE name = :name")
    suspend fun deleteToolByName(name: String)
    
    @Query("UPDATE custom_tools SET isEnabled = :enabled WHERE name = :name")
    suspend fun setToolEnabled(name: String, enabled: Boolean)
    
    @Query("SELECT COUNT(*) FROM custom_tools")
    suspend fun getToolCount(): Int
}
