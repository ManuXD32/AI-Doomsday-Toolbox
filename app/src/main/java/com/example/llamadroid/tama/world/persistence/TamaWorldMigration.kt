package com.example.llamadroid.tama.world.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema only: deterministic generation runs after migration, never on Room's open thread. */
object TamaWorldMigration : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("hydration", "social", "curiosity").forEach { name ->
            db.execSQL("ALTER TABLE tama_pets ADD COLUMN $name REAL NOT NULL DEFAULT 100")
        }
        db.execSQL("""CREATE TABLE IF NOT EXISTS tama_worlds (
            id TEXT NOT NULL PRIMARY KEY, petId TEXT NOT NULL, seed INTEGER NOT NULL,
            generatorVersion INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL,
            createdAt INTEGER NOT NULL, lastSimulatedAt INTEGER NOT NULL, stateJson TEXT NOT NULL)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tama_worlds_petId ON tama_worlds(petId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS tama_world_actors (
            worldId TEXT NOT NULL PRIMARY KEY, petId TEXT NOT NULL, payload TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS tama_world_chunks (
            worldId TEXT NOT NULL, chunkX INTEGER NOT NULL, chunkY INTEGER NOT NULL,
            payload TEXT NOT NULL, PRIMARY KEY(worldId, chunkX, chunkY))""")
        listOf("structures", "objects").forEach { family ->
            db.execSQL("""CREATE TABLE IF NOT EXISTS tama_world_$family (
                worldId TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(worldId, id))""")
        }
        db.execSQL("""CREATE TABLE IF NOT EXISTS tama_world_npcs (
            worldId TEXT NOT NULL, npcId TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(worldId, npcId))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS tama_world_relationships (
            petId TEXT NOT NULL, npcId TEXT NOT NULL, familiarity REAL NOT NULL,
            friendship REAL NOT NULL, trust REAL NOT NULL, lastInteraction INTEGER NOT NULL,
            sharedEventCount INTEGER NOT NULL, PRIMARY KEY(petId, npcId))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS tama_world_events (
            id TEXT NOT NULL PRIMARY KEY, worldId TEXT NOT NULL, petId TEXT NOT NULL,
            timestamp INTEGER NOT NULL, importance TEXT NOT NULL, actorId TEXT NOT NULL,
            eventType TEXT NOT NULL, payload TEXT NOT NULL, memoryEligible INTEGER NOT NULL, episodeId TEXT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tama_world_events_petId_timestamp ON tama_world_events(petId, timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tama_world_events_episodeId ON tama_world_events(episodeId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS tama_world_episodes (
            id TEXT NOT NULL PRIMARY KEY, worldId TEXT NOT NULL, petId TEXT NOT NULL,
            startTime INTEGER NOT NULL, endTime INTEGER NOT NULL, title TEXT NOT NULL,
            summary TEXT NOT NULL, importance TEXT NOT NULL, memoryStatus TEXT NOT NULL, evidenceJson TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tama_world_episodes_petId_startTime ON tama_world_episodes(petId, startTime)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS tama_world_policies (
            id TEXT NOT NULL PRIMARY KEY, petId TEXT NOT NULL, version INTEGER NOT NULL,
            parentId TEXT, modelHash TEXT NOT NULL, inferenceArtifact TEXT NOT NULL,
            metadataJson TEXT NOT NULL, active INTEGER NOT NULL, createdAt INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tama_world_policies_petId_createdAt ON tama_world_policies(petId, createdAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS tama_world_action_receipts (
            petId TEXT NOT NULL, id TEXT NOT NULL, worldId TEXT NOT NULL, kind TEXT NOT NULL,
            requestJson TEXT NOT NULL, resultJson TEXT, status TEXT NOT NULL,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, completedAt INTEGER, acknowledgedAt INTEGER,
            PRIMARY KEY(petId, id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tama_world_action_receipts_petId_status_updatedAt ON tama_world_action_receipts(petId,status,updatedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tama_world_action_receipts_petId_kind_updatedAt ON tama_world_action_receipts(petId,kind,updatedAt)")
    }
}
