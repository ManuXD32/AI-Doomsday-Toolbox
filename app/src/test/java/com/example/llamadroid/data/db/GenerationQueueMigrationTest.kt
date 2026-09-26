package com.example.llamadroid.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GenerationQueueMigrationTest {
    @Test
    fun `migration creates durable queue and control tables`() {
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        ).use { helper ->
            val database = helper.writableDatabase
            GenerationQueueMigration.MIGRATION_123_124.migrate(database)
            database.execSQL("INSERT INTO generation_queue_control " +
                "(id, state, scheduledAtMillis, runId, activeItemId, updatedAtMillis) " +
                "VALUES (1, 'SCHEDULED', 123456, NULL, NULL, 123)")
            database.execSQL("INSERT INTO generation_queue_items " +
                "(id, kind, mode, promptPreview, configJson, sortOrder, status, createdAtMillis) " +
                "VALUES ('item-1', 'IMAGE', 'TXT2IMG', 'prompt', '{}', 1, 'PENDING', 123)")
            GenerationQueueMigration.MIGRATION_124_125.migrate(database)

            database.query("SELECT state, scheduledAtMillis FROM generation_queue_control WHERE id = 1")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("SCHEDULED", cursor.getString(0))
                    assertEquals(123456L, cursor.getLong(1))
                }
            database.query("SELECT id, status FROM generation_queue_items ORDER BY sortOrder")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("item-1", cursor.getString(0))
                    assertEquals("PENDING", cursor.getString(1))
                }
            database.query("SELECT configJson FROM generation_queue_items WHERE id = 'item-1'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("{}", cursor.getString(0))
                }
            database.query("SELECT sql FROM sqlite_master WHERE type = 'index' AND " +
                "name = 'index_generation_queue_items_finishedAtMillis_createdAtMillis_id'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(0).contains("finishedAtMillis DESC, createdAtMillis DESC, id DESC"))
                }
        }
    }
}
