package com.example.llamadroid.data.model.library

import androidx.room.Room
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RoomModelDeletionJournalTest {
    private lateinit var database: AppDatabase
    private lateinit var journal: RoomModelDeletionJournal

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        journal = RoomModelDeletionJournal(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `path progress survives recovery query and terminal completion`() = runBlocking {
        val preview = ModelDeletionPreview(
            operationId = "delete-op",
            targetKey = "qwen.gguf",
            targetLabel = "Qwen",
            files = listOf(
                ModelDeletionFile("/models/qwen.gguf", 42L, "primary"),
                ModelDeletionFile("/models/qwen-mmproj.gguf", 7L, "companion")
            )
        )
        assertEquals("delete-op", journal.begin(preview))
        journal.recordPath("delete-op", "/models/qwen.gguf", deleted = true)
        journal.recordPath(
            operationId = "delete-op",
            path = "/models/qwen-mmproj.gguf",
            deleted = false,
            failure = ModelDeletionPathFailure(
                path = "/models/qwen-mmproj.gguf",
                code = ModelLibraryErrorCode.DELETION_RECOVERABLE,
                message = "busy"
            )
        )

        val inProgress = journal.recoverableOperations()
        assertEquals(1, inProgress.size)
        assertEquals(ModelDeletionJournalStatus.IN_PROGRESS, inProgress.single().status)
        assertEquals(
            setOf(ModelDeletionJournalPathStatus.DELETED, ModelDeletionJournalPathStatus.FAILED),
            inProgress.single().paths.map { it.status }.toSet()
        )

        journal.fail(
            ModelDeletionResult(
                operationId = "delete-op",
                targetKey = "qwen.gguf",
                status = ModelDeletionStatus.RECOVERABLE,
                failedPaths = listOf(
                    ModelDeletionPathFailure(
                        path = "/models/qwen-mmproj.gguf",
                        code = ModelLibraryErrorCode.DELETION_RECOVERABLE,
                        message = "busy"
                    )
                )
            )
        )
        val recoverable = journal.operation("delete-op")
        assertEquals(ModelDeletionJournalStatus.RECOVERABLE, recoverable?.status)
        assertEquals(ModelDeletionStatus.RECOVERABLE, recoverable?.result?.status)
        assertTrue(recoverable?.result?.failedPaths?.isNotEmpty() == true)

        journal.complete(
            ModelDeletionResult(
                operationId = "delete-op",
                targetKey = "qwen.gguf",
                status = ModelDeletionStatus.COMPLETED,
                deletedPaths = preview.files.map { it.path }
            )
        )
        assertTrue(journal.recoverableOperations().isEmpty())
        assertEquals(ModelDeletionJournalStatus.COMPLETED, journal.operation("delete-op")?.status)
    }
}
