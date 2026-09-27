package com.example.llamadroid.data.repository

import androidx.room.Room
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.savedCommandFromLaunchProfile
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.ManagedLlamaServerCoordinator
import com.example.llamadroid.service.ManagedLlamaServerException
import com.example.llamadroid.service.LlamaServerSessionStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EasyLlamaChatPersistenceTest {
    private lateinit var database: AppDatabase
    private lateinit var model: File
    private lateinit var coordinator: EasyLlamaChatCoordinator

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        model = File.createTempFile("easy-chat-", ".gguf", context.cacheDir).apply { writeText("test model") }
        coordinator = EasyLlamaChatCoordinator(context, database)
        runBlocking {
            database.modelDao().insertModel(ModelEntity(model.name, model.path, model.length(), ModelType.LLM,
                "test", isDownloaded = true))
        }
    }

    @After fun tearDown() {
        database.close()
        model.delete()
    }

    @Test fun `concurrent creation and cold recreation reuse one profile card and connection`() = runBlocking {
        val targets = (1..8).map { async { coordinator.createOrReuse(model.name) } }.awaitAll()
        assertEquals(1, targets.map { it.card.id }.distinct().size)
        assertEquals(1, targets.map { it.server.id }.distinct().size)
        assertEquals(1, database.savedCommandDao().getCommandsByScope("GENERAL").first().size)
        val restored = EasyLlamaChatCoordinator(RuntimeEnvironment.getApplication(), database).createOrReuse(model.name)
        assertEquals(targets.first(), restored)
        assertTrue(restored.card.port in 49152..65535)
        assertEquals(restored.card.id, restored.server.managedServerCardId)
        assertNull(restored.server.localLaunchProfileJson)
    }

    @Test fun `linked connection follows port changes atomically and missing card requires repair`() = runBlocking {
        val target = coordinator.createOrReuse(model.name)
        database.llamaServerCardDao().updatePort(target.card.id, 56001)
        assertEquals(56001, database.llamaServerDao().getManagedServer(target.card.id)?.port)
        database.llamaServerCardDao().updateCard(target.card.copy(port = 56002))
        assertEquals(56002, database.llamaServerDao().getManagedServer(target.card.id)?.port)
        database.llamaServerCardDao().deleteCardById(target.card.id)
        assertNotNull(database.llamaServerDao().getManagedServer(target.card.id))
        try {
            ManagedLlamaServerCoordinator(RuntimeEnvironment.getApplication(), database).prepare(target.card.id)
            fail("A removed card cannot fall back to global configuration")
        } catch (error: ManagedLlamaServerException) {
            assertEquals("MANAGED_CARD_MISSING", error.code)
        }
    }

    @Test fun `stopped cards are discovered without launching and missing files stay visible`() = runBlocking {
        val target = coordinator.createOrReuse(model.name)
        val managed = ManagedLlamaServerCoordinator(RuntimeEnvironment.getApplication(), database)
        val stopped = managed.catalog().single()
        assertEquals(LlamaServerSessionStatus.STOPPED, stopped.status)
        assertEquals(8192, stopped.contextTokens)
        assertEquals("adt-llama-server", stopped.modelRow().getString("owned_by"))
        model.delete()
        val missing = managed.catalog().single()
        assertEquals(target.card.id, missing.card.id)
        assertEquals("MANAGED_MODEL_MISSING", missing.errorCode)
    }

    @Test fun `easy lifecycle survives preset edits without changing manual cards`() = runBlocking {
        val target = coordinator.createOrReuse(model.name)
        val edited = savedCommandFromLaunchProfile("Edited", LlamaServerLaunchProfile(
            modelPath = model.path, host = "0.0.0.0", contextSize = 4096))
        assertEquals(600, edited.launchProfileForCard(target.card).idleStopSeconds)
        assertEquals("127.0.0.1", edited.launchProfileForCard(target.card).host)
        assertEquals(4096, edited.launchProfileForCard(target.card).contextSize)
        assertNull(edited.launchProfileForCard(target.card.copy(easyModelId = null)).idleStopSeconds)
        assertEquals("0.0.0.0", edited.launchProfileForCard(target.card.copy(easyModelId = null)).host)
    }
}
