package com.example.llamadroid.data.db

import androidx.room.*

/**
 * Workflow template types
 */
enum class WorkflowType {
    TRANSCRIBE_SUMMARY,
    TXT2IMG_UPSCALE,
    MANGA_TRANSLATION
}

/**
 * Entity for saved workflow templates
 */
@Entity(tableName = "workflow_templates")
data class WorkflowTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val type: WorkflowType,
    val configJson: String,  // JSON serialized config
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * DAO for workflow templates
 */
@Dao
interface WorkflowTemplateDao {
    @Query("SELECT * FROM workflow_templates WHERE type = :type ORDER BY createdAt DESC")
    fun getByType(type: WorkflowType): kotlinx.coroutines.flow.Flow<List<WorkflowTemplateEntity>>
    
    @Query("SELECT * FROM workflow_templates ORDER BY createdAt DESC")
    fun getAll(): kotlinx.coroutines.flow.Flow<List<WorkflowTemplateEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: WorkflowTemplateEntity): Long
    
    @Delete
    suspend fun delete(template: WorkflowTemplateEntity)
    
    @Query("DELETE FROM workflow_templates WHERE id = :id")
    suspend fun deleteById(id: Int)
}
