package com.example.llamadroid.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ModelLibraryMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AppDatabase::class.java.canonicalName), FrameworkSQLiteOpenHelperFactory())

    @Test fun migrateAndPortableRestorePreserveDefinitionsWithoutInstallationClaims() {
        val name = "model-library-migration-test"
        helper.createDatabase(name, 112).apply {
            insertFixture("models", mapOf("filename" to "imported.gguf", "path" to "/old/private/imported.gguf", "type" to "LLM"))
            insertFixture("download_tasks", mapOf("id" to "legacy", "url" to "https://example.com/model.gguf", "modelType" to "LLM", "huggingFaceToken" to "test-secret"))
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 113, true, Migrations.MIGRATION_112_113)
        db.query("SELECT filename FROM models").use { assertTrue(it.moveToFirst()); assertEquals("imported.gguf", it.getString(0)) }
        db.query("SELECT sourceId, stageOnly FROM download_tasks WHERE id='legacy'").use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertEquals(0, it.getInt(1))
        }
        db.insertFixture("model_sources", mapOf("id" to "source", "family" to "LLM", "kind" to "HTTPS", "url" to "https://example.com/model.gguf", "normalizedKey" to "source-key", "verified" to 1,
            "validationStatus" to "verified", "checkedAt" to 1234L))
        db.insertFixture("model_provenance", mapOf("id" to "edge", "sourceId" to "source", "modelKey" to "/old/private/imported.gguf", "localPath" to "/old/private/imported.gguf", "family" to "LLM"))
        db.insertFixture("model_bundles", mapOf("id" to "bundle", "name" to "My setup", "family" to "LLM"))
        db.insertFixture("model_bundle_items", mapOf("id" to "item", "bundleId" to "bundle", "sourceId" to "source", "family" to "LLM", "itemKey" to "main", "required" to 1,
            "modelMetadataJson" to """{"modelType":"LLM","isVision":true,"path":"/old/private/weights","token":"test-secret"}"""))
        db.insertFixture("model_sources", mapOf("id" to "unsafe-source", "family" to "LLM", "kind" to "HTTPS",
            "url" to "https://example.com/model.gguf?token=test-secret", "normalizedKey" to "unsafe-key"))
        db.insertFixture("pending_model_artifacts", mapOf("id" to "pending", "filename" to "fixture.gguf",
            "sourceId" to "source", "stagingPath" to "/old/private/fixture.gguf.part", "status" to "STAGED",
            "validationJson" to """{"privatePath":"/old/private/fixture.gguf"}"""))
        db.execSQL("DELETE FROM models")
        db.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { portable ->
            sanitizePortableModelLibrary(portable)
            portable.rawQuery("SELECT name FROM model_bundles", null).use { assertTrue(it.moveToFirst()); assertEquals("My setup", it.getString(0)) }
            portable.rawQuery("SELECT sourceId FROM model_bundle_items", null).use { assertTrue(it.moveToFirst()); assertEquals("source", it.getString(0)) }
            portable.rawQuery("SELECT url, verified FROM model_sources WHERE id='source'", null).use { assertTrue(it.moveToFirst()); assertEquals("https://example.com/model.gguf", it.getString(0)); assertEquals(0, it.getInt(1)) }
            portable.rawQuery("SELECT validationStatus, checkedAt, lastErrorCode FROM model_sources WHERE id='source'", null).use {
                assertTrue(it.moveToFirst()); assertEquals("needs_check", it.getString(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
            }
            portable.rawQuery("SELECT modelMetadataJson FROM model_bundle_items", null).use {
                assertTrue(it.moveToFirst())
                val metadata = org.json.JSONObject(it.getString(0))
                assertEquals("LLM", metadata.getString("modelType")); assertTrue(metadata.getBoolean("isVision"))
                assertFalse(metadata.has("path")); assertFalse(metadata.has("token"))
            }
            portable.rawQuery("SELECT localPath, modelKey FROM model_provenance", null).use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertEquals("restored:edge", it.getString(1)) }
            portable.rawQuery("SELECT COUNT(*) FROM download_tasks", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            portable.rawQuery("SELECT COUNT(*) FROM pending_model_artifacts", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            portable.rawQuery("SELECT url, normalizedKey FROM model_sources WHERE id='unsafe-source'", null).use {
                assertTrue(it.moveToFirst()); assertEquals("", it.getString(0)); assertEquals("restored:unsafe-source", it.getString(1))
            }
            // Reapplying restore sanitation is idempotent; definitions remain reusable drafts.
            sanitizePortableModelLibrary(portable)
            portable.rawQuery("SELECT COUNT(*) FROM model_bundles", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }

        }
    }

    /** Populate legacy NOT NULL fields without relying on constructor defaults in SQL. */
    private fun SupportSQLiteDatabase.insertFixture(table: String, overrides: Map<String, Any>) {
        val values = linkedMapOf<String, Any?>()
        query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(1)
                values[column] = overrides[column] ?: if (cursor.getInt(3) == 0) null
                    else if (cursor.getString(2).equals("TEXT", true)) "" else 0
            }
        }
        execSQL("INSERT INTO `$table` (${values.keys.joinToString { "`$it`" }}) VALUES (${values.keys.joinToString { "?" }})", values.values.toTypedArray())
    }
}
