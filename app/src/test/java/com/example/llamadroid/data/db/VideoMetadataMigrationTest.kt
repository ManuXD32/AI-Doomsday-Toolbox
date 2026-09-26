package com.example.llamadroid.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.llamadroid.data.model.AudioCuratedBundleCatalog
import com.example.llamadroid.data.model.VideoRecognitionBundleCatalog
import com.example.llamadroid.service.WhisperModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Verifies that the 117 -> 118 repair is catalog-bound and idempotent. */
@RunWith(RobolectricTestRunner::class)
class VideoMetadataMigrationTest {
    @Test
    fun `known media rows are repaired while arbitrary rows remain unchanged`() {
        open().use { helper ->
            val database = helper.writableDatabase
            val videoBundle = VideoRecognitionBundleCatalog.bundles.first()
            val videoFile = videoBundle.files.first { it.type == ModelType.LLM }
            val videoFilename = videoFile.installedFilename(videoBundle.defaultPrefix)
            insertModel(
                database,
                filename = videoFilename,
                type = ModelType.LLM.name,
                repoId = videoFile.repoId,
                sizeBytes = videoFile.sizeBytes,
                isDownloaded = true,
                isVision = false
            )
            insertModel(
                database,
                filename = "arbitrary-video.gguf",
                type = ModelType.LLM.name,
                repoId = "example/arbitrary-model",
                sizeBytes = videoFile.sizeBytes,
                isDownloaded = true,
                isVision = false
            )

            val whisper = WhisperModel.BASE
            insertModel(
                database,
                filename = whisper.filename,
                type = ModelType.LLM.name,
                repoId = "ggerganov/whisper.cpp",
                sizeBytes = 1L,
                isDownloaded = true,
                isVision = true
            )
            insertModel(
                database,
                filename = "arbitrary-${whisper.filename}",
                type = ModelType.LLM.name,
                repoId = "example/arbitrary-model",
                sizeBytes = 1L,
                isDownloaded = true,
                isVision = true
            )

            val speechBundle = AudioCuratedBundleCatalog.bundles.first()
            val speechFile = speechBundle.files.first()
            insertModel(
                database,
                filename = speechFile.installedFilename(speechBundle.defaultPrefix),
                type = ModelType.LLM.name,
                repoId = speechFile.repoId,
                sizeBytes = speechFile.sizeBytes,
                isDownloaded = true,
                isVision = true
            )

            insertServer(database, videoFilename, "llama-server")
            insertServer(database, "arbitrary-model.gguf", "llama-server")

            AgentSleepWakeMigration.MIGRATION_117_118.migrate(database)
            // A second invocation models a retry after an interrupted upgrade.
            AgentSleepWakeMigration.MIGRATION_117_118.migrate(database)

            database.query(
                "SELECT type, isVision FROM models WHERE repoId = ? AND filename = ?",
                arrayOf(videoFile.repoId, videoFilename)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(ModelType.LLM.name, cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
            database.query(
                "SELECT isVision FROM models WHERE repoId = ? AND filename = ?",
                arrayOf("example/arbitrary-model", "arbitrary-video.gguf")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

            database.query(
                "SELECT type, isVision FROM models WHERE repoId = ? AND filename = ?",
                arrayOf("ggerganov/whisper.cpp", whisper.filename)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(ModelType.WHISPER.name, cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            database.query(
                "SELECT type, isVision FROM models WHERE repoId = ? AND filename = ?",
                arrayOf("example/arbitrary-model", "arbitrary-${whisper.filename}")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(ModelType.LLM.name, cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }

            database.query(
                "SELECT type, isVision, audioFamily, audioComponentRole, audioArtifactIdentity " +
                    "FROM models WHERE repoId = ? AND filename = ?",
                arrayOf(speechFile.repoId, speechFile.installedFilename(speechBundle.defaultPrefix))
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(speechFile.type.name, cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(speechFile.audioFamily, cursor.getString(2))
                assertEquals(speechFile.componentRole, cursor.getString(3))
                assertEquals(speechFile.artifactIdentity, cursor.getString(4))
            }

            database.query(
                "SELECT supportsVision, supportsVideo FROM llama_servers WHERE modelName = ?",
                arrayOf(videoFilename)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
            }
            database.query(
                "SELECT supportsVision, supportsVideo FROM llama_servers WHERE modelName = ?",
                arrayOf("arbitrary-model.gguf")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
            }

            database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'agent_sleep_wakes'"
            ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        }
    }

    private fun insertModel(
        database: SupportSQLiteDatabase,
        filename: String,
        type: String,
        repoId: String,
        sizeBytes: Long,
        isDownloaded: Boolean,
        isVision: Boolean
    ) {
        val values = ContentValues().apply {
            put("filename", filename)
            put("path", "/models/$filename")
            put("sizeBytes", sizeBytes)
            put("type", type)
            put("repoId", repoId)
            put("isDownloaded", isDownloaded)
            put("isVision", isVision)
        }
        database.insert("models", SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private fun insertServer(database: SupportSQLiteDatabase, modelName: String, engine: String) {
        val values = ContentValues().apply {
            put("name", modelName)
            put("host", "127.0.0.1")
            put("port", 8080)
            put("engine", engine)
            put("supportsVision", false)
            put("supportsAudio", false)
            put("modelName", modelName)
            put("lastUsed", 0L)
            put("supportsVideo", false)
        }
        database.insert("llama_servers", SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private fun open(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE agent_conversations (id INTEGER PRIMARY KEY NOT NULL)")
                        db.execSQL(
                            "CREATE TABLE models (" +
                                "filename TEXT NOT NULL PRIMARY KEY, path TEXT NOT NULL, sizeBytes INTEGER NOT NULL, " +
                                "type TEXT NOT NULL, repoId TEXT NOT NULL, isDownloaded INTEGER NOT NULL, " +
                                "isVision INTEGER NOT NULL, audioFamily TEXT, audioLanguage TEXT, " +
                                "audioComponentRole TEXT, audioArtifactIdentity TEXT)"
                        )
                        db.execSQL(
                            "CREATE TABLE llama_servers (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                                "host TEXT NOT NULL, port INTEGER NOT NULL, engine TEXT NOT NULL, " +
                                "supportsVision INTEGER NOT NULL, supportsAudio INTEGER NOT NULL, modelName TEXT, " +
                                "lastUsed INTEGER NOT NULL, supportsVideo INTEGER NOT NULL DEFAULT 0)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
}
