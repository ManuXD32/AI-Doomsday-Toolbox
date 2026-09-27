package com.example.llamadroid.service

import androidx.room.Room
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.savedCommandFromLaunchProfile
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.util.NativeProcessCleanup
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class ManagedLlamaServerCoordinatorPrepareTest {
    private val cardId = 7_000_000_000L + abs(System.nanoTime() % 1_000_000L)
    private val sessionId get() = LlamaServerCardEntity.sessionIdForCard(cardId)

    private lateinit var context: android.content.Context
    private lateinit var database: AppDatabase
    private lateinit var modelFile: File
    private lateinit var profile: LlamaServerLaunchProfile

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        modelFile = File(context.cacheDir, "managed-prepare-$cardId.gguf").apply {
            parentFile?.mkdirs()
            writeText("host-test-model")
        }
        profile = LlamaServerLaunchProfile(
            modelPath = modelFile.absolutePath,
            host = "127.0.0.1",
            serverPort = freePort(),
            contextSize = 8192,
            threads = 2
        )

        mockkObject(LlamaServerLauncher)
        mockkObject(NativeProcessCleanup)
        mockkObject(ManagedLlamaServerHttp)
        every {
            NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(any(), any(), any())
        } returns true
    }

    @After
    fun tearDown() {
        LlamaServerSessionStateStore(context).delete(sessionId)
        LlamaServerSessionOwnerStore(context).delete(sessionId)
        File(context.filesDir, "llama_server_usage/$sessionId").deleteRecursively()
        modelFile.delete()
        database.close()
        unmockkObject(ManagedLlamaServerHttp)
        unmockkObject(NativeProcessCleanup)
        unmockkObject(LlamaServerLauncher)
    }

    @Test
    fun `concurrent prepares join one card startup and preserve its launch profile`() = runBlocking {
        insertCard(profile)
        writeSnapshot(LlamaServerSessionStatus.STARTING, profile)
        every { ManagedLlamaServerHttp.healthy(any()) } returns true
        every { ManagedLlamaServerHttp.contextTokens(any(), any()) } returns 4096

        val enteredLauncher = CountDownLatch(1)
        val releaseLauncher = CountDownLatch(1)
        val launchCalls = AtomicInteger(0)
        val launchProfiles = mutableListOf<LlamaServerLaunchProfile>()
        every {
            LlamaServerLauncher.startSession(any(), any(), any(), any(), null, true)
        } answers {
            val launched = thirdArg<LlamaServerLaunchProfile>()
            synchronized(launchProfiles) { launchProfiles += launched }
            if (launchCalls.incrementAndGet() == 1) {
                enteredLauncher.countDown()
                releaseLauncher.await(2, TimeUnit.SECONDS)
            }
            writeOwner(launched)
            writeSnapshot(LlamaServerSessionStatus.RUNNING, launched)
            Result.success(Unit)
        }

        val coordinator = ManagedLlamaServerCoordinator(context, database)
        val first = async(Dispatchers.IO) { coordinator.prepare(cardId) }
        assertTrue(enteredLauncher.await(2, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { coordinator.prepare(cardId) }
        Thread.sleep(50)
        assertFalse(second.isCompleted)

        releaseLauncher.countDown()
        val results = withTimeout(5_000) { awaitAll(first, second) }

        // Both callers may issue the idempotent ensureRunning dispatch; the profile must remain
        // the same while the per-card mutex prevents competing startup decisions.
        assertEquals(2, launchCalls.get())
        assertEquals(1, results.map { it.card.id }.distinct().size)
        assertTrue(launchProfiles.all { it.modelPath == profile.modelPath })
        assertTrue(launchProfiles.all { it.serverPort == profile.serverPort })
        assertTrue(results.all { it.contextTokens == 4096 })
        verify(exactly = 2) {
            LlamaServerLauncher.startSession(any(), any(), any(), any(), null, true)
        }
    }

    @Test
    fun `running without owner fails visibly and does not reassign the running port`() = runBlocking {
        insertCard(profile)
        writeSnapshot(LlamaServerSessionStatus.RUNNING, profile)
        every { ManagedLlamaServerHttp.healthy(any()) } returns true
        every { LlamaServerLauncher.startSession(any(), any(), any(), any(), null, true) } returns Result.success(Unit)
        var clock: Job? = null
        try {
            withTimeout(5_000) {
                ManagedLlamaServerCoordinator(context, database).prepare(cardId) { message ->
                    if (message == context.getString(com.example.llamadroid.R.string.managed_llama_verifying)) {
                        clock = launch {
                            delay(100)
                            ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
                        }
                    }
                }
            }
            error("Missing ownership must not leave the user waiting for three minutes")
        } catch (error: ManagedLlamaServerException) {
            assertEquals("MANAGED_OWNER_UNAVAILABLE", error.code)
        } finally { clock?.cancel() }
        assertEquals(profile.serverPort, database.llamaServerCardDao().getCard(cardId)?.port)
    }

    @Test
    fun `persisted startup error is surfaced without waiting for the readiness deadline`() = runBlocking {
        insertCard(profile)
        writeSnapshot(LlamaServerSessionStatus.STARTING, profile)
        every { ManagedLlamaServerHttp.healthy(any()) } returns false
        every {
            LlamaServerLauncher.startSession(any(), any(), any(), any(), null, true)
        } answers {
            writeSnapshot(
                status = LlamaServerSessionStatus.ERROR,
                launchProfile = thirdArg<LlamaServerLaunchProfile>(),
                error = "native failed"
            )
            Result.success(Unit)
        }

        val failure = try {
            ManagedLlamaServerCoordinator(context, database).prepare(cardId)
            error("prepare should fail when the session publishes ERROR")
        } catch (error: ManagedLlamaServerException) {
            error
        }

        assertEquals("MANAGED_START_FAILED", failure.code)
        verify(exactly = 1) {
            LlamaServerLauncher.startSession(any(), any(), any(), any(), null, true)
        }
    }

    @Test
    fun `startup timeout returns a stable code without waiting three minutes`() = runBlocking {
        insertCard(profile)
        writeSnapshot(LlamaServerSessionStatus.STOPPED, profile)
        every { ManagedLlamaServerHttp.healthy(any()) } returns false
        every {
            LlamaServerLauncher.startSession(any(), any(), any(), any(), null, true)
        } answers {
            ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
            Result.success(Unit)
        }

        val failure = try {
            withTimeout(5_000) {
                ManagedLlamaServerCoordinator(context, database).prepare(cardId)
            }
            error("prepare should fail when readiness never arrives")
        } catch (error: ManagedLlamaServerException) {
            error
        }

        assertEquals("MANAGED_START_TIMEOUT", failure.code)
        verify(exactly = 1) {
            LlamaServerLauncher.startSession(any(), any(), any(), any(), null, true)
        }
    }

    @Test
    fun `a stopped card restarts with the same persisted profile and inherited context`() = runBlocking {
        insertCard(profile)
        every { ManagedLlamaServerHttp.healthy(any()) } returns true
        every { ManagedLlamaServerHttp.contextTokens(any(), any()) } returns 4096
        val launchProfiles = mutableListOf<LlamaServerLaunchProfile>()
        every {
            LlamaServerLauncher.startSession(any(), any(), any(), any(), null, true)
        } answers {
            val launched = thirdArg<LlamaServerLaunchProfile>()
            synchronized(launchProfiles) { launchProfiles += launched }
            writeOwner(launched)
            writeSnapshot(LlamaServerSessionStatus.RUNNING, launched)
            Result.success(Unit)
        }

        val coordinator = ManagedLlamaServerCoordinator(context, database)
        val first = coordinator.prepare(cardId)
        LlamaServerSessionOwnerStore(context).delete(sessionId)
        writeSnapshot(LlamaServerSessionStatus.STOPPED, profile)
        val second = coordinator.prepare(cardId)

        assertEquals(2, launchProfiles.size)
        assertEquals(profile.modelPath, launchProfiles[0].modelPath)
        assertEquals(profile.modelPath, launchProfiles[1].modelPath)
        assertEquals(profile.serverPort, launchProfiles[0].serverPort)
        assertEquals(profile.serverPort, launchProfiles[1].serverPort)
        assertEquals(4096, first.contextTokens)
        assertEquals(4096, second.contextTokens)
        verify(exactly = 2) {
            LlamaServerLauncher.startSession(any(), any(), any(), any(), null, true)
        }
    }

    @Test
    fun `manual card reports a busy port without replacing its configured port`() = runBlocking {
        ServerSocket(0).use { occupied ->
            val busyProfile = profile.copy(serverPort = occupied.localPort)
            insertCard(busyProfile)
            writeSnapshot(LlamaServerSessionStatus.STOPPED, busyProfile)

            val failure = try {
                ManagedLlamaServerCoordinator(context, database).prepare(cardId)
                error("prepare should reject an occupied manual port")
            } catch (error: ManagedLlamaServerException) {
                error
            }

            assertEquals("MANAGED_PORT_BUSY", failure.code)
            verify(exactly = 0) {
                LlamaServerLauncher.startSession(any(), any(), any(), any(), null, true)
            }
            assertEquals(busyProfile.serverPort, database.llamaServerCardDao().getCard(cardId)?.port)
        }
    }

    private suspend fun insertCard(launchProfile: LlamaServerLaunchProfile) {
        database.savedCommandDao().insertCommand(
            savedCommandFromLaunchProfile("Prepare fixture", launchProfile, id = cardId)
        )
        database.llamaServerCardDao().insertCard(
            LlamaServerCardEntity(
                id = cardId,
                name = "Prepare fixture",
                savedCommandId = cardId,
                presetNameSnapshot = "Prepare fixture",
                port = launchProfile.serverPort
            )
        )
    }

    private fun writeOwner(launchProfile: LlamaServerLaunchProfile) {
        LlamaServerSessionOwnerStore(context).write(
            LlamaServerSessionOwner(
                sessionId = sessionId,
                pid = 42,
                processStartTimeTicks = 1L,
                port = launchProfile.serverPort,
                launchProfileJson = LlamaServerLaunchProfile.encodeForPersistence(launchProfile)
            )
        )
    }

    private fun writeSnapshot(
        status: LlamaServerSessionStatus,
        launchProfile: LlamaServerLaunchProfile,
        error: String? = null
    ) {
        LlamaServerSessionStateStore(context).write(
            LlamaServerSessionSnapshot(
                sessionId = sessionId,
                status = status,
                port = launchProfile.serverPort,
                pid = 42,
                error = error,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
