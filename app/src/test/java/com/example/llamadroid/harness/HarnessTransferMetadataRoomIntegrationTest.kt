package com.example.llamadroid.harness

import android.content.Context
import android.content.ContextWrapper
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentRuntimeSource
import com.example.llamadroid.data.db.AgentProotEnvironmentEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.HarnessInstallationDataLayout
import com.example.llamadroid.data.db.HarnessInstallationPurpose
import com.example.llamadroid.data.db.HarnessSessionEntity
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Durable Room coverage for scoped Harness transfer and deletion boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HarnessTransferMetadataRoomIntegrationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        val databaseRoot = temporary.newFolder("database")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(databaseRoot, name)
        }
        AppDatabase.closeInstance()
        database = AppDatabase.getDatabase(context)
    }

    @After
    fun tearDown() {
        database.close()
        AppDatabase.closeInstance()
    }

    @Test
    fun `apply scopes identical Harness ids and retry uses the durable receipt`() = runBlocking {
        database.harnessInstallationDao().insert(environment(RUNTIME_A))
        database.harnessInstallationDao().insert(environment(RUNTIME_B))
        val metadata = sourceDocument()

        val first = HarnessTransferMetadata.apply(
            context = context,
            targetId = RUNTIME_A,
            metadata = metadata,
            sessionIdMap = mapOf(SOURCE_SESSION to SHARED_SESSION),
            cwdMap = mapOf(SOURCE_GUEST_PATH to "/workspace/projects/runtime-a"),
            workspaceIdMap = mapOf(SOURCE_WORKSPACE to "workspace-a"),
            harnessWorkspaceIdMap = mapOf(SOURCE_HARNESS_WORKSPACE to SHARED_HARNESS_WORKSPACE),
        )
        val second = HarnessTransferMetadata.apply(
            context = context,
            targetId = RUNTIME_B,
            metadata = metadata.copy(transferId = "transfer-room-b"),
            sessionIdMap = mapOf(SOURCE_SESSION to SHARED_SESSION),
            cwdMap = mapOf(SOURCE_GUEST_PATH to "/workspace/projects/runtime-b"),
            workspaceIdMap = mapOf(SOURCE_WORKSPACE to "workspace-b"),
            harnessWorkspaceIdMap = mapOf(SOURCE_HARNESS_WORKSPACE to SHARED_HARNESS_WORKSPACE),
        )

        assertTrue(first.imported)
        assertTrue(second.imported)
        assertEquals(SHARED_HARNESS_WORKSPACE,
            database.harnessDao(RUNTIME_A).workspace("workspace-a")?.harnessWorkspaceId)
        assertEquals(SHARED_HARNESS_WORKSPACE,
            database.harnessDao(RUNTIME_B).workspace("workspace-b")?.harnessWorkspaceId)
        assertNotNull(database.harnessDao(RUNTIME_A).session(SHARED_SESSION))
        assertNotNull(database.harnessDao(RUNTIME_B).session(SHARED_SESSION))
        assertEquals(1, database.harnessDao(RUNTIME_A).conversations().size)
        assertEquals(1, database.harnessDao(RUNTIME_B).conversations().size)

        val retry = HarnessTransferMetadata.apply(
            context = context,
            targetId = RUNTIME_A,
            metadata = metadata,
            newRuntime = true,
        )
        assertFalse(retry.imported)
        assertEquals(first.workspaceIdMap, retry.workspaceIdMap)
        assertEquals(first.sessionIdMap, retry.sessionIdMap)
        assertEquals(first.conversationIdMap, retry.conversationIdMap)
        assertEquals(1, database.harnessDao(RUNTIME_A).workspaces().size)
        assertEquals(1, database.harnessDao(RUNTIME_A).conversations().size)
        assertEquals(metadata.transferId, database.harnessTransferDao().receipt(metadata.transferId)?.operationId)
    }

    @Test
    fun `runtime deletion is isolated and rejects external environment references`() = runBlocking {
        database.harnessInstallationDao().insert(environment(RUNTIME_A))
        database.harnessInstallationDao().insert(environment(RUNTIME_B))
        val metadata = sourceDocument()
        HarnessTransferMetadata.apply(
            context = context,
            targetId = RUNTIME_A,
            metadata = metadata,
            sessionIdMap = mapOf(SOURCE_SESSION to SHARED_SESSION),
            workspaceIdMap = mapOf(SOURCE_WORKSPACE to "workspace-a"),
            harnessWorkspaceIdMap = mapOf(SOURCE_HARNESS_WORKSPACE to SHARED_HARNESS_WORKSPACE),
        )
        HarnessTransferMetadata.apply(
            context = context,
            targetId = RUNTIME_B,
            metadata = metadata.copy(transferId = "transfer-room-b"),
            sessionIdMap = mapOf(SOURCE_SESSION to SHARED_SESSION),
            workspaceIdMap = mapOf(SOURCE_WORKSPACE to "workspace-b"),
            harnessWorkspaceIdMap = mapOf(SOURCE_HARNESS_WORKSPACE to SHARED_HARNESS_WORKSPACE),
        )

        database.agentChatDao().insertConversationDirect(
            AgentConversationEntity(
                title = "External owner",
                projectFolder = "external",
                workspaceBackend = "LOCAL_PROOT",
                prootEnvironmentId = RUNTIME_B,
                runtimeSource = AgentRuntimeSource.WORKSPACE_ONLY,
                runtimeId = "runtime-external",
            )
        )

        database.harnessInstallationDao().deleteRuntimeRecords(RUNTIME_A)
        assertNull(database.harnessInstallationDao().getById(RUNTIME_A))
        assertTrue(database.harnessDao(RUNTIME_A).workspaces().isEmpty())
        assertTrue(database.harnessDao(RUNTIME_A).conversations().isEmpty())
        assertNotNull(database.harnessInstallationDao().getById(RUNTIME_B))
        assertNotNull(database.harnessDao(RUNTIME_B).workspace("workspace-b"))
        assertNotNull(database.harnessDao(RUNTIME_B).session(SHARED_SESSION))
        assertEquals(1, database.harnessDao(RUNTIME_B).conversations().size)

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { database.harnessInstallationDao().deleteRuntimeRecords(RUNTIME_B) }
        }
        assertEquals("HARNESS_RUNTIME_REFERENCED", failure.message)
        assertNotNull(database.harnessInstallationDao().getById(RUNTIME_B))
        assertNotNull(database.harnessDao(RUNTIME_B).workspace("workspace-b"))
    }

    private fun sourceDocument(): HarnessTransferMetadata.Document {
        val conversation = AgentConversationEntity(
            id = SOURCE_CONVERSATION_ID,
            title = "Imported conversation",
            projectFolder = "shared-project",
            workspaceBackend = "LOCAL_PROOT",
            prootEnvironmentId = SOURCE_RUNTIME,
            runtimeSource = AgentRuntimeSource.DEEPSEEK,
            runtimeId = SOURCE_RUNTIME,
        )
        return HarnessTransferMetadata.Document(
            transferId = "transfer-room-a",
            sourceRuntimeId = SOURCE_RUNTIME,
            workspaces = listOf(
                HarnessWorkspaceEntity(
                    id = SOURCE_WORKSPACE,
                    backend = "LOCAL_PROOT",
                    projectFolder = "shared-project",
                    title = "Shared project",
                    guestPath = SOURCE_GUEST_PATH,
                    harnessWorkspaceId = SOURCE_HARNESS_WORKSPACE,
                    prootEnvironmentId = SOURCE_RUNTIME,
                    runtimeId = SOURCE_RUNTIME,
                )
            ),
            sessions = listOf(
                HarnessSessionEntity(
                    harnessSessionId = SOURCE_SESSION,
                    conversationId = SOURCE_CONVERSATION_ID,
                    workspaceId = SOURCE_WORKSPACE,
                    runtimeId = SOURCE_RUNTIME,
                )
            ),
            conversations = listOf(conversation),
        )
    }

    private fun environment(id: String) = AgentProotEnvironmentEntity(
        id = id,
        displayName = id,
        purpose = HarnessInstallationPurpose.HARNESS,
        dataLayout = HarnessInstallationDataLayout.SCOPED,
    )

    private companion object {
        const val SOURCE_RUNTIME = "source-runtime"
        const val RUNTIME_A = "runtime-a"
        const val RUNTIME_B = "runtime-b"
        const val SOURCE_WORKSPACE = "source-workspace"
        const val SOURCE_HARNESS_WORKSPACE = "same-harness-workspace"
        const val SHARED_HARNESS_WORKSPACE = "same-harness-workspace"
        const val SOURCE_SESSION = "same-session"
        const val SHARED_SESSION = "same-session"
        const val SOURCE_GUEST_PATH = "/workspace/projects/shared-project"
        const val SOURCE_CONVERSATION_ID = 700L
    }
}
