package com.example.llamadroid.audio.library

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "audio_library_folders",
    indices = [
        Index(value = ["parentId"]),
        Index(value = ["updatedAt"])
    ]
)
data class AudioLibraryFolderEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "audio_library_items",
    indices = [
        Index(value = ["originKey"], unique = true),
        Index(value = ["folderId"]),
        Index(value = ["createdAt"]),
        Index(value = ["updatedAt"]),
        Index(value = ["title"])
    ]
)
data class AudioLibraryItemEntity(
    @androidx.room.PrimaryKey val id: String,
    val originKey: String,
    val title: String,
    val audioPath: String,
    val metadataPath: String? = null,
    val mimeType: String = "audio/wav",
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
    val folderId: String? = null,
    val source: String = "audio_workspace",
    val status: String = "complete",
    val modelName: String = "",
    val voiceName: String? = null,
    val language: String = "en",
    val kind: String = "speech"
)

@Entity(tableName = "audio_library_preferences")
data class AudioLibraryPreferencesEntity(
    @androidx.room.PrimaryKey val id: String = DEFAULT_ID,
    val query: String = "",
    val sort: String = AudioLibrarySort.NEWEST.name,
    val folderId: String? = null,
    val kind: String? = null,
    val legacyMigrationVersion: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_ID = "default"
    }
}

@Dao
interface AudioLibraryDao {
    @Query(
        """
        SELECT * FROM audio_library_items
        WHERE (:query = '' OR title LIKE '%' || :query || '%' OR modelName LIKE '%' || :query || '%' OR COALESCE(voiceName, '') LIKE '%' || :query || '%')
          AND (:folderId IS NULL OR folderId = :folderId)
          AND (:kind IS NULL OR kind = :kind)
        ORDER BY
          CASE WHEN :sort = 'NAME_ASC' THEN title END COLLATE NOCASE ASC,
          CASE WHEN :sort = 'NAME_DESC' THEN title END COLLATE NOCASE DESC,
          CASE WHEN :sort = 'OLDEST' THEN createdAt END ASC,
          CASE WHEN :sort = 'LONGEST' THEN durationMs END DESC,
          CASE WHEN :sort = 'SHORTEST' THEN durationMs END ASC,
          CASE WHEN :sort = 'SIZE_ASC' THEN sizeBytes END ASC,
          CASE WHEN :sort = 'SIZE_DESC' THEN sizeBytes END DESC,
          createdAt DESC,
          id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun observeItemsPage(
        query: String,
        folderId: String?,
        kind: String?,
        sort: String,
        limit: Int,
        offset: Int
    ): Flow<List<AudioLibraryItemEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM audio_library_items
        WHERE (:query = '' OR title LIKE '%' || :query || '%' OR modelName LIKE '%' || :query || '%' OR COALESCE(voiceName, '') LIKE '%' || :query || '%')
          AND (:folderId IS NULL OR folderId = :folderId)
          AND (:kind IS NULL OR kind = :kind)
        """
    )
    fun observeItemCount(query: String, folderId: String?, kind: String?): Flow<Int>

    @Query(
        """
        SELECT id FROM audio_library_items
        WHERE (:query = '' OR title LIKE '%' || :query || '%' OR modelName LIKE '%' || :query || '%' OR COALESCE(voiceName, '') LIKE '%' || :query || '%')
          AND (:folderId IS NULL OR folderId = :folderId)
          AND (:kind IS NULL OR kind = :kind)
        ORDER BY createdAt DESC, id ASC
        """
    )
    suspend fun matchingItemIds(query: String, folderId: String?, kind: String?): List<String>

    @Query("SELECT * FROM audio_library_items WHERE id = :id LIMIT 1")
    suspend fun getItem(id: String): AudioLibraryItemEntity?

    @Query("SELECT * FROM audio_library_items WHERE id IN (:ids)")
    suspend fun getItems(ids: List<String>): List<AudioLibraryItemEntity>

    @Query("SELECT * FROM audio_library_items WHERE folderId IN (:folderIds)")
    suspend fun getItemsInFolders(folderIds: List<String>): List<AudioLibraryItemEntity>

    @Query("SELECT * FROM audio_library_items ORDER BY createdAt DESC, id ASC")
    fun observeAllItems(): Flow<List<AudioLibraryItemEntity>>

    @Query("SELECT * FROM audio_library_items WHERE originKey IN (:keys)")
    suspend fun getItemsByOriginKeys(keys: List<String>): List<AudioLibraryItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<AudioLibraryItemEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: AudioLibraryItemEntity)

    @Update
    suspend fun updateItem(item: AudioLibraryItemEntity)

    @Query("UPDATE audio_library_items SET folderId = :folderId, updatedAt = :updatedAt WHERE id IN (:ids)")
    suspend fun moveItems(ids: List<String>, folderId: String?, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE audio_library_items SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameItem(id: String, title: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM audio_library_items WHERE id = :id")
    suspend fun deleteItem(id: String): Int

    @Query("SELECT * FROM audio_library_folders ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeFolders(): Flow<List<AudioLibraryFolderEntity>>

    /** Snapshot query used by repository transactions; avoids opening a long-lived Flow. */
    @Query("SELECT * FROM audio_library_folders ORDER BY name COLLATE NOCASE ASC, id ASC")
    suspend fun getFolders(): List<AudioLibraryFolderEntity>

    @Query("SELECT * FROM audio_library_folders WHERE id = :id LIMIT 1")
    suspend fun getFolder(id: String): AudioLibraryFolderEntity?

    @Query("SELECT * FROM audio_library_folders WHERE ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId) ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeChildFolders(parentId: String?): Flow<List<AudioLibraryFolderEntity>>

    @Query("SELECT * FROM audio_library_folders WHERE ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId) AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findFolderByName(parentId: String?, name: String): AudioLibraryFolderEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolder(folder: AudioLibraryFolderEntity)

    @Update
    suspend fun updateFolder(folder: AudioLibraryFolderEntity)

    @Query("DELETE FROM audio_library_folders WHERE id = :id")
    suspend fun deleteFolder(id: String): Int

    @Query("SELECT COUNT(*) FROM audio_library_folders WHERE parentId = :parentId")
    suspend fun countChildFolders(parentId: String): Int

    @Query("SELECT COUNT(*) FROM audio_library_items WHERE folderId = :folderId")
    suspend fun countItemsInFolder(folderId: String): Int

    @Query("SELECT * FROM audio_library_preferences WHERE id = :id LIMIT 1")
    suspend fun getPreferences(id: String = AudioLibraryPreferencesEntity.DEFAULT_ID): AudioLibraryPreferencesEntity?

    @Query("SELECT * FROM audio_library_preferences WHERE id = :id LIMIT 1")
    fun observePreferences(id: String = AudioLibraryPreferencesEntity.DEFAULT_ID): Flow<AudioLibraryPreferencesEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreferences(preferences: AudioLibraryPreferencesEntity)
}
