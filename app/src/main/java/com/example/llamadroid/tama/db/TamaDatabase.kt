package com.example.llamadroid.tama.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.llamadroid.util.DebugLog

/**
 * Room database for Tama virtual pet persistence.
 */
@Database(
    entities = [
        TamaPetEntity::class,
        TamaEventEntity::class,
        TamaQuestEntity::class,
        TamaQuestChecklistItemEntity::class,
        TamaDeepDreamRunEntity::class,
        TamaStudyLabelEntity::class,
        TamaStudySessionEntity::class,
        TamaLocationEntity::class,
        TamaNpcEntity::class,
        TamaSummaryEntity::class,
        TamaChatMessageEntity::class,
        FarmTileEntity::class,
        FarmUpgradeEntity::class,
        FarmLivestockEntity::class,
        AdventureSessionEntity::class,
        AdventureStageEntity::class,
        DungeonProgressEntity::class,
        AdventureGateProfileEntity::class,
        AdventureGateWorldProgressEntity::class,
        AdventureGateBattleStateEntity::class,
        AdventureGateNightArenaRunEntity::class,
        TamaArtworkEntity::class
    ],
    version = 40,
    exportSchema = true
)
abstract class TamaDatabase : RoomDatabase() {
    abstract fun tamaDao(): TamaDao
    abstract fun farmDao(): FarmDao
    
    companion object {
        @Volatile
        private var INSTANCE: TamaDatabase? = null
        
        
        fun getInstance(context: Context): TamaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TamaDatabase::class.java,
                    "tama_database"
                )
                    .addMigrations(*TamaMigrations.ALL_MIGRATIONS)
                    // Only allow destructive migration from early development versions (1-12)
                    // From v13 onwards, proper migrations are required to preserve pet data
                    .fallbackToDestructiveMigrationFrom(*TamaMigrations.DESTRUCTIVE_FALLBACK_VERSIONS)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            DebugLog.log("[TamaDB] TamaDatabase opened, version: ${db.version}")
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
        
        /**
         * Close the database instance. Used before restore to release file locks.
         */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
