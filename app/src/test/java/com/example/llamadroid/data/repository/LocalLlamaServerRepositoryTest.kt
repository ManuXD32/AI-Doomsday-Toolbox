package com.example.llamadroid.data.repository

import androidx.room.Room
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.service.LlamaServerLaunchProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalLlamaServerRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repo: LocalLlamaServerRepository
    private val profile = LlamaServerLaunchProfile(modelPath = "/m/model.gguf", contextSize = 4096)

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = LocalLlamaServerRepository(context, database)
    }

    @After fun tearDown() { database.close() }

    @Test fun `create makes preset card and linked row`() = runBlocking {
        val ids = repo.createLocalServer("One", profile, port = 51001)
        val card = database.llamaServerCardDao().getCard(ids.cardId)!!
        assertEquals(ids.presetId, card.savedCommandId)
        assertEquals(51001, card.port)
        val row = database.llamaServerDao().getManagedServer(ids.cardId)!!
        assertEquals(ids.serverRowId, row.id)
        assertEquals("model.gguf", row.modelName)
    }

    @Test fun `editing a shared preset copies it and unshared updates in place`() = runBlocking {
        val first = repo.createLocalServer("One", profile, port = 51001)
        val second = repo.createLocalServer("Two", profile, port = 51002)
        // Make both cards share the first preset.
        database.llamaServerCardDao().updateSavedCommand(second.cardId, first.presetId)
        repo.updateLocalServerProfile(second.cardId, profile.copy(contextSize = 2048))
        val moved = database.llamaServerCardDao().getCard(second.cardId)!!
        assertNotEquals(first.presetId, moved.savedCommandId)
        assertEquals(first.presetId, database.llamaServerCardDao().getCard(first.cardId)!!.savedCommandId)
        // Now unshared: in place.
        repo.updateLocalServerProfile(first.cardId, profile.copy(contextSize = 1024), port = 51009)
        assertEquals(first.presetId, database.llamaServerCardDao().getCard(first.cardId)!!.savedCommandId)
        assertEquals(51009, database.llamaServerDao().getManagedServer(first.cardId)!!.port)
    }

    @Test fun `delete removes card row preset and clears chat bindings`() = runBlocking {
        val ids = repo.createLocalServer("One", profile, port = 51001)
        val chatId = database.llamaChatDao().insertChat(LlamaChatEntity(title = "c", serverId = ids.serverRowId))
        repo.deleteLocalServer(ids.cardId)
        assertNull(database.llamaServerCardDao().getCard(ids.cardId))
        assertNull(database.llamaServerDao().getServerById(ids.serverRowId))
        assertNull(database.savedCommandDao().getGeneralCommandById(ids.presetId))
        assertNull(database.llamaChatDao().getChatById(chatId)!!.serverId)
    }

    @Test fun `chat binding helpers`() = runBlocking {
        val dao = database.llamaChatDao()
        val chat = dao.insertChat(LlamaChatEntity(title = "c"))
        assertEquals(1, dao.bindChatServerIfUnbound(chat, 5))
        assertEquals(0, dao.bindChatServerIfUnbound(chat, 6))
        assertEquals(1, dao.getChatsBoundToServer(5).size)
        dao.setPersistKvCache(chat, true)
        assertTrue(dao.getChatById(chat)!!.persistKvCache)
    }
}
