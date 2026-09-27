package com.example.llamadroid.data.repository

import androidx.room.Room
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.service.*
import com.example.llamadroid.util.NativeProcessCleanup
import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class EasyLlamaChatOpeningTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: AppDatabase
    private lateinit var model: File
    private lateinit var server: NanoHTTPD
    private lateinit var coordinator: EasyLlamaChatCoordinator
    private lateinit var target: EasyLlamaChatTarget
    @Volatile private var healthy = true
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        model = File.createTempFile("opening-", ".gguf", context.cacheDir).apply { writeText("test") }
        database.modelDao().insertModel(ModelEntity(model.name, model.path, model.length(), ModelType.LLM,
            "test", isDownloaded = true))
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = newFixedLengthResponse(
                if (session.uri == "/health" && !healthy) Response.Status.SERVICE_UNAVAILABLE else Response.Status.OK,
                "application/json", when (session.uri) {
                    "/slots" -> """[{"id":0,"n_ctx":4096,"is_processing":false}]"""
                    "/props" -> """{"default_generation_settings":{"n_ctx":4096}}"""
                    else -> "{}"
                })
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        coordinator = EasyLlamaChatCoordinator(context, database)
        target = coordinator.createOrReuse(model.name)
        database.llamaServerCardDao().updatePort(target.card.id, server.listeningPort)
        mockkObject(NativeProcessCleanup)
        mockkObject(LlamaServerLauncher)
        every { NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(any(), any(), any()) } returns true
        every { LlamaServerLauncher.startSession(any(), any(), any(), any(), null, true) } answers {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            val profile = thirdArg<LlamaServerLaunchProfile>()
            LlamaServerSessionOwnerStore(context).write(LlamaServerSessionOwner(target.card.sessionId,
                987, 12345, server.listeningPort, LlamaServerLaunchProfile.encodeForPersistence(profile)))
            LlamaServerSessionStateStore(context).write(LlamaServerSessionSnapshot(target.card.sessionId,
                LlamaServerSessionStatus.RUNNING, server.listeningPort, 987))
            Result.success(Unit)
        }
        // Join an owned startup; the fixture's HTTP listener is not an unrelated port collision.
        LlamaServerSessionStateStore(context).write(LlamaServerSessionSnapshot(target.card.sessionId,
            LlamaServerSessionStatus.STARTING, server.listeningPort))
    }

    @After fun tearDown() {
        release.countDown()
        server.stop()
        LlamaServerSessionOwnerStore(context).delete(target.card.sessionId)
        LlamaServerSessionStateStore(context).delete(target.card.sessionId)
        database.close()
        model.delete()
        unmockkObject(LlamaServerLauncher)
        unmockkObject(NativeProcessCleanup)
    }

    @Test fun `running server completes chat with real HTTP and releases startup leases`() = runBlocking {
        val messages = mutableListOf<String>()
        release.countDown()
        val session = withTimeout(10_000) { coordinator.openChat(target.card.id) { messages += it } }
        val chat = requireNotNull(database.llamaChatDao().getChatById(session.chatId))
        assertEquals(target.server.id, session.serverId)
        assertEquals(4096, chat.contextSize)
        assertFalse(LlamaServerUsageStore(context, target.card.sessionId).read().active)
        assertEquals(context.getString(com.example.llamadroid.R.string.easy_chat_opening_conversation), messages.last())
    }

    @Test fun `overlapping entry points create one conversation`() = runBlocking {
        val first = async(Dispatchers.IO) { coordinator.openChat(target.card.id) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.openChat(target.card.id) }
        delay(100)
        release.countDown()
        assertEquals(withTimeout(10_000) { first.await() }, withTimeout(10_000) { second.await() })
        assertEquals(1, database.llamaChatDao().getAllChats().first().size)
    }

    @Test fun `cancelling connection wait releases the operation and permits retry`() = runBlocking {
        healthy = false
        release.countDown()
        val opening = async(Dispatchers.IO) { coordinator.openChat(target.card.id) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        delay(100)
        opening.cancelAndJoin()
        assertTrue(database.llamaChatDao().getAllChats().first().isEmpty())
        assertFalse(LlamaServerUsageStore(context, target.card.sessionId).read().active)
        healthy = true
        withTimeout(10_000) { coordinator.openChat(target.card.id) }
        assertEquals(1, database.llamaChatDao().getAllChats().first().size)
    }
}
