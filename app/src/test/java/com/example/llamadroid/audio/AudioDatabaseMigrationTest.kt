package com.example.llamadroid.audio

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.llamadroid.data.db.Migrations
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Executes the production migration against the complete exported legacy schema. */
@RunWith(RobolectricTestRunner::class)
class AudioDatabaseMigrationTest {
    @Test
    fun `migration preserves installed model and matches fresh audio schema`() {
        val old = schema(113)
        val expected = schema(114)
        open(old).use { migrated ->
            open(expected).use { fresh ->
                val database = migrated.writableDatabase
                val model = old.getJSONArray("entities").let { entities ->
                    (0 until entities.length()).map(entities::getJSONObject)
                        .first { it.getString("tableName") == "models" }
                }
                val values = ContentValues()
                val fields = model.getJSONArray("fields")
                for (index in 0 until fields.length()) {
                    val field = fields.getJSONObject(index)
                    if (!field.getBoolean("notNull")) continue
                    val name = field.getString("columnName")
                    if (field.getString("affinity") == "TEXT") values.put(name, "legacy-$name")
                    else values.put(name, 0)
                }
                database.insert("models", SQLiteDatabase.CONFLICT_ABORT, values)
                Migrations.MIGRATION_113_114.migrate(database)
                val entities = expected.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val table = entities.getJSONObject(index).getString("tableName")
                    assertEquals(table, TableInfo.read(fresh.writableDatabase, table), TableInfo.read(database, table))
                }
                database.query("SELECT filename, audioFamily, audioLanguage, audioComponentRole, audioArtifactIdentity FROM models").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("legacy-filename", cursor.getString(0))
                    for (index in 1..4) assertTrue(cursor.isNull(index))
                    assertEquals(1, cursor.count)
                }
            }
        }
    }

    @Test
    fun `audio library migration is idempotent and creates logical organization tables`() {
        val legacy = schema(114)
        open(legacy).use { helper ->
            val database = helper.writableDatabase
            database.execSQL("PRAGMA foreign_keys = ON")
            Migrations.MIGRATION_114_115.migrate(database)
            // A retry after a process interruption must not duplicate indexes or fail.
            Migrations.MIGRATION_114_115.migrate(database)

            // The exported schema is required: a missing snapshot must not skip validation.
            schema(115).let { expected ->
                open(expected).use { fresh ->
                    val entities = expected.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val table = entities.getJSONObject(index).getString("tableName")
                        assertEquals(table, TableInfo.read(fresh.writableDatabase, table), TableInfo.read(database, table))
                    }
                }
            }

            database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN " +
                    "('audio_library_folders', 'audio_library_items', 'audio_library_preferences', " +
                    "'model_deletion_journal_operations', 'model_deletion_journal_paths')"
            ).use { cursor ->
                val tables = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertTrue(
                    tables.containsAll(
                        setOf(
                            "audio_library_folders",
                            "audio_library_items",
                            "audio_library_preferences",
                            "model_deletion_journal_operations",
                            "model_deletion_journal_paths"
                        )
                    )
                )
            }
            database.query("PRAGMA table_info(audio_library_items)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("originKey", "audioPath", "folderId", "kind")))
            }
            database.query("PRAGMA table_info(model_deletion_journal_operations)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(
                    columns.containsAll(
                        setOf("operationId", "targetKey", "targetKind", "status", "previewJson", "resultJson", "createdAt", "updatedAt")
                    )
                )
            }
            database.query("PRAGMA table_info(model_deletion_journal_paths)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("operationId", "path", "status", "failureCode", "failureMessage", "updatedAt")))
            }
            database.execSQL(
                "INSERT INTO model_deletion_journal_operations " +
                    "(operationId, targetKey, targetKind, status, previewJson, createdAt, updatedAt) " +
                    "VALUES ('op-1', 'model.gguf', 'model', 'IN_PROGRESS', '{}', 1, 1)"
            )
            database.execSQL(
                "INSERT INTO model_deletion_journal_paths " +
                    "(operationId, path, status, updatedAt) VALUES ('op-1', '/data/model.gguf', 'PENDING', 1)"
            )
            database.execSQL(
                "UPDATE model_deletion_journal_paths SET status = 'DELETED', updatedAt = 2 " +
                    "WHERE operationId = 'op-1' AND path = '/data/model.gguf'"
            )
            database.query(
                "SELECT status FROM model_deletion_journal_paths WHERE operationId = 'op-1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("DELETED", cursor.getString(0))
            }
            database.execSQL("DELETE FROM model_deletion_journal_operations WHERE operationId = 'op-1'")
            database.query("SELECT COUNT(*) FROM model_deletion_journal_paths WHERE operationId = 'op-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private fun schema(version: Int): JSONObject {
        val relative = "schemas/com.example.llamadroid.data.db.AppDatabase/$version.json"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private fun schemaOrNull(version: Int): JSONObject? {
        val relative = "schemas/com.example.llamadroid.data.db.AppDatabase/$version.json"
        val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile } ?: return null
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private fun open(schema: JSONObject): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.getJSONArray("indices")
                        for (position in 0 until indices.length()) {
                            db.execSQL(indices.getJSONObject(position).getString("createSql").replace("\${TABLE_NAME}", table))
                        }
                    }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
    )
}
