package com.example.llamadroid.service

import androidx.room.Room
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AppDatabase
import java.io.File
import java.nio.file.Files
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
class AgentGreenfieldDetectionTest {
    private lateinit var database: AppDatabase
    private lateinit var workspaceRoot: File
    private var conversationId: Long = 0L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        conversationId = runBlocking {
            database.agentChatDao().insertConversation(
                AgentConversationEntity(
                    title = "Greenfield project",
                    workspaceBackend = AgentWorkspaceBackendType.LOCAL_SANDBOX.name
                )
            )
        }
        workspaceRoot = Files.createTempDirectory("agent-greenfield").toFile()
    }

    @After
    fun tearDown() {
        database.close()
        workspaceRoot.deleteRecursively()
    }

    @Test
    fun `empty local workspace marker is durable and idempotent`() = runBlocking {
        File(workspaceRoot, "brain/summary.md").apply {
            parentFile?.mkdirs()
            writeText("runtime metadata")
        }
        File(workspaceRoot, ".adt").mkdirs()

        val marked = AgentDurableContractStore.markGreenfieldIfEmptyLocalWorkspace(
            database = database,
            conversationId = conversationId,
            workspaceRoot = workspaceRoot
        )
        assertTrue(marked.greenfield)
        assertEquals(
            true,
            database.agentWorkflowDao().getProjectContract(conversationId)?.greenfield
        )

        // A later scan must not clear the durable marker, even if runtime
        // configuration is created after the first Direct turn.
        File(workspaceRoot, ".adt/run.json").writeText("{}")

        val repeated = AgentDurableContractStore.markGreenfieldIfEmptyLocalWorkspace(
            database = database,
            conversationId = conversationId,
            workspaceRoot = workspaceRoot
        )
        assertEquals(marked, repeated)
    }

    @Test
    fun `existing user source prevents automatic marker`() = runBlocking {
        File(workspaceRoot, "src/main.py").apply {
            parentFile?.mkdirs()
            writeText("print('existing')")
        }

        val contract = AgentDurableContractStore.markGreenfieldIfEmptyLocalWorkspace(
            database = database,
            conversationId = conversationId,
            workspaceRoot = workspaceRoot
        )

        assertFalse(contract.greenfield)
        assertFalse(
            database.agentWorkflowDao().getProjectContract(conversationId)?.greenfield == true
        )
    }
}
