package com.example.llamadroid.audio

import androidx.room.Room
import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AudioRuntimeCoordinatorTest {
    private lateinit var database: AppDatabase
    private lateinit var coordinatorContext: Context

    @Before
    fun setUp() {
        runBlocking { AudioRuntimeCoordinator.resetForTests() }
        coordinatorContext = mockk()
        every { coordinatorContext.applicationContext } returns coordinatorContext
        every { coordinatorContext.getString(R.string.audio_runtime_status_interrupted) } returns "interrupted"
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `cancelled queue row cannot be resurrected by a stale claim`() = runBlocking {
        val dao = database.audioDao()
        dao.insertJob(job("cancel-first", AudioJobStatuses.QUEUED))
        assertEquals(1, dao.cancelQueuedJob("cancel-first", "cancelled", 2L))
        assertEquals(0, dao.claimQueuedJob("cancel-first", "preparing", 3L))
        assertEquals(AudioJobStatuses.CANCELLED, dao.getJob("cancel-first")?.status)

        dao.insertJob(job("claim-first", AudioJobStatuses.QUEUED))
        assertEquals(1, dao.claimQueuedJob("claim-first", "preparing", 2L))
        assertEquals(0, dao.cancelQueuedJob("claim-first", "cancelled", 3L))
        assertEquals(1, dao.markCancelling("claim-first", "cancelling", 3L))
        assertEquals(AudioJobStatuses.CANCELLING, dao.getJob("claim-first")?.status)
    }

    @Test
    fun `startup recovery interrupts queued and active jobs once`() = runBlocking {
        val dao = database.audioDao()
        dao.insertJob(job("queued", AudioJobStatuses.QUEUED))
        dao.insertJob(job("running", AudioJobStatuses.RUNNING))

        val first = AudioRuntimeCoordinator.initialize(coordinatorContext, dao)
        val second = AudioRuntimeCoordinator.initialize(coordinatorContext, dao)

        assertEquals(2, first)
        assertEquals(0, second)
        assertEquals(AudioJobStatuses.INTERRUPTED, dao.getJob("queued")?.status)
        assertEquals(AudioJobStatuses.INTERRUPTED, dao.getJob("running")?.status)
        assertTrue(dao.getJob("queued")?.completedAt != null)
    }

    private fun job(id: String, status: String) = AudioGenerationJobEntity(
        id = id,
        adapterId = AudioAdapterIds.LLAMA_CLI,
        family = AudioModelFamilies.QWEN3_TTS,
        modelId = "model",
        modelPath = "/models/model.gguf",
        modelDisplayName = "Model",
        text = "test",
        status = status,
        createdAt = 1L,
        updatedAt = 1L
    )
}
