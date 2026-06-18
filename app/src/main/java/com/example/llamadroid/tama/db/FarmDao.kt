package com.example.llamadroid.tama.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tama_farm_tiles")
data class FarmTileEntity(
    @PrimaryKey val id: Int, // 0-8
    val petId: String,
    val status: String, // TileStatus enum name
    val cropJson: String?, // PlantedCrop serialized
    val lastWateredTime: Long?
)

@Entity(tableName = "tama_farm_upgrades")
data class FarmUpgradeEntity(
    @PrimaryKey val type: String, // "well", "composter"
    val petId: String,
    val isPurchased: Boolean = false,
    val level: Int = 1,
    val lastProductionTime: Long = System.currentTimeMillis(),
    val storedOutput: Int = 0, // Water or Fertilizer ready to collect
    val extraDataJson: String? = null // For composter slots
)

@Entity(
    tableName = "tama_farm_livestock",
    primaryKeys = ["petId", "type"]
)
data class FarmLivestockEntity(
    val petId: String,
    val type: String, // "barn", "coop"
    val slotsJson: String
)

@Dao
interface FarmDao {
    @Query("SELECT * FROM tama_farm_tiles WHERE petId = :petId ORDER BY id ASC")
    fun observeTiles(petId: String): Flow<List<FarmTileEntity>>

    @Query("SELECT * FROM tama_farm_tiles WHERE petId = :petId")
    suspend fun getTiles(petId: String): List<FarmTileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTile(tile: FarmTileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTiles(tiles: List<FarmTileEntity>)

    @Query("SELECT * FROM tama_farm_upgrades WHERE petId = :petId")
    fun observeUpgrades(petId: String): Flow<List<FarmUpgradeEntity>>

    @Query("SELECT * FROM tama_farm_upgrades WHERE petId = :petId")
    suspend fun getUpgrades(petId: String): List<FarmUpgradeEntity>

    @Query("SELECT * FROM tama_farm_upgrades WHERE petId = :petId AND type = :type")
    suspend fun getUpgrade(petId: String, type: String): FarmUpgradeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUpgrade(upgrade: FarmUpgradeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUpgrades(upgrades: List<FarmUpgradeEntity>)

    @Query("SELECT * FROM tama_farm_livestock WHERE petId = :petId ORDER BY type ASC")
    fun observeLivestock(petId: String): Flow<List<FarmLivestockEntity>>

    @Query("SELECT * FROM tama_farm_livestock WHERE petId = :petId ORDER BY type ASC")
    suspend fun getLivestock(petId: String): List<FarmLivestockEntity>

    @Query("SELECT * FROM tama_farm_livestock WHERE petId = :petId AND type = :type LIMIT 1")
    suspend fun getLivestockByType(petId: String, type: String): FarmLivestockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLivestock(entity: FarmLivestockEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLivestock(entities: List<FarmLivestockEntity>)

    @Transaction
    suspend fun saveTilesAndUpgrades(
        tiles: List<FarmTileEntity>,
        upgrades: List<FarmUpgradeEntity>
    ) {
        if (tiles.isNotEmpty()) {
            saveTiles(tiles)
        }
        if (upgrades.isNotEmpty()) {
            saveUpgrades(upgrades)
        }
    }

    @Query("DELETE FROM tama_farm_tiles WHERE petId = :petId")
    suspend fun clearTilesForPet(petId: String)

    @Query("DELETE FROM tama_farm_upgrades WHERE petId = :petId")
    suspend fun clearUpgradesForPet(petId: String)

    @Query("DELETE FROM tama_farm_livestock WHERE petId = :petId")
    suspend fun clearLivestockForPet(petId: String)
}
