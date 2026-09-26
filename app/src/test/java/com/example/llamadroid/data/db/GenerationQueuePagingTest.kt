package com.example.llamadroid.data.db

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GenerationQueuePagingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun openCleanDatabase() {
        AppDatabase.closeInstance()
        context.deleteDatabase("llama_droid_db")
    }

    @After fun closeDatabase() {
        AppDatabase.closeInstance()
        context.deleteDatabase("llama_droid_db")
    }

    @Test fun tenThousandSavedRequestsStayOutOfActiveProjectionAndHistoryLoadsOnePage() = runBlocking {
        val database = AppDatabase.getDatabase(context)
        val sqlite = database.openHelper.writableDatabase
        val payload = "x".repeat(2048)
        sqlite.beginTransaction()
        try {
            val insert = sqlite.compileStatement("""INSERT INTO generation_queue_items
                (id, kind, mode, promptPreview, configJson, sortOrder, status, runId,
                 createdAtMillis, finishedAtMillis, errorMessage)
                VALUES (?, 'IMAGE', 'TXT2IMG', 'preview', ?, ?, ?, 'run-1', ?, ?, ?)""")
            repeat(10_000) { index ->
                insert.clearBindings()
                insert.bindString(1, "item-${index.toString().padStart(5, '0')}")
                insert.bindString(2, payload)
                insert.bindLong(3, index.toLong())
                insert.bindString(4, if (index % 4 == 0) "FAILED" else "SUCCEEDED")
                insert.bindLong(5, index.toLong())
                insert.bindLong(6, 500L) // equal finish times exercise the deterministic tie breaker
                if (index == 0) insert.bindString(7, "media_processing_time_limit")
                else insert.bindNull(7)
                insert.executeInsert()
            }
            sqlite.setTransactionSuccessful()
        } finally { sqlite.endTransaction() }

        val dao = database.generationQueueDao()
        assertTrue(dao.observeQueue().first().isEmpty())
        assertEquals(0, dao.pendingCount())
        val summary = dao.runSummary("run-1")
        assertEquals(10_000, summary.total)
        assertEquals(10_000, summary.finished)
        assertEquals(7_500, summary.succeeded)
        assertEquals(2_500, summary.failed)
        assertEquals(1, summary.hasMediaTimeout)
        assertEquals(payload, dao.getItem("item-00000")?.configJson)

        val source = dao.historyPagingSource()
        val result = source.load(PagingSource.LoadParams.Refresh(null, 50, false))
        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page
        assertEquals(50, page.data.size)
        assertEquals("item-09999", page.data.first().id)
        assertEquals("item-09950", page.data.last().id)

        val plan = sqlite.query("""EXPLAIN QUERY PLAN SELECT id FROM generation_queue_items
            INDEXED BY index_generation_queue_items_finishedAtMillis_createdAtMillis_id
            WHERE status IN ('SUCCEEDED', 'FAILED', 'STOPPED', 'INTERRUPTED')
            ORDER BY finishedAtMillis DESC, createdAtMillis DESC, id DESC LIMIT 50""")
        plan.use { cursor ->
            val details = buildList {
                while (cursor.moveToNext()) add(cursor.getString(3))
            }.joinToString(" ")
            assertTrue(details, details.contains("index_generation_queue_items_finishedAtMillis_createdAtMillis_id"))
            assertFalse(details, details.contains("USE TEMP B-TREE"))
        }
    }

    @Test fun sqlRunSummaryMatchesMixedAndEmptyRunStates() = runBlocking {
        val database = AppDatabase.getDatabase(context)
        val dao = database.generationQueueDao()
        assertEquals(GenerationQueueRunSummary.EMPTY, dao.runSummary("missing"))
        val statuses = listOf("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "STOPPED", "INTERRUPTED")
        statuses.forEachIndexed { index, status ->
            dao.putItem(GenerationQueueItemEntity(
                id = "mixed-$index", kind = "IMAGE", mode = "TXT2IMG", promptPreview = "preview",
                configJson = "{}", sortOrder = index.toLong(), status = status, runId = "mixed",
                createdAtMillis = index.toLong(),
                errorMessage = if (status == "INTERRUPTED") "media_processing_time_limit" else null
            ))
        }
        assertEquals(GenerationQueueRunSummary(6, 4, 1, 2, 1, 1), dao.runSummary("mixed"))
        assertEquals(1, dao.pendingCount())
        assertEquals(2, dao.observeQueue().first().size)
    }
}
