package com.example.llamadroid.harness

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentRuntimeSource
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.AgentLocalWorkspaceSupport
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HarnessProjectManagementPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var workspaces: HarnessWorkspaceRepository
    private lateinit var management: HarnessProjectManagement

    @Before fun setUp() {
        val directory = temporary.newFolder("app-files")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val credentials = HarnessCredentialStore(context)
        workspaces = HarnessWorkspaceRepository(context, database, credentials)
        management = HarnessProjectManagement(context, database, workspaces, HarnessWorkspaceAccess(context, credentials))
    }

    @After fun tearDown() { database.close() }

    @Test fun renamePreservesSessionTitlesAnchorIdsAndFiles() = runBlocking {
        val project = workspaces.createWorkspace("Demo")
        val alias = project.copy(id = "old-alias", backend = "LOCAL_SANDBOX")
        database.harnessDao().saveWorkspace(alias)
        val session = workspaces.importSession("thread-a", "Keep thread title", project.guestPath, project.id)
        val anchor = database.agentChatDao().insertConversation(AgentConversationEntity(
            title = project.id, projectFolder = project.projectFolder,
            workspaceBackend = project.backend, runtimeSource = AgentRuntimeSource.WORKSPACE_ONLY,
        ))
        val file = AgentLocalWorkspaceSupport.rootForProject(context, project.projectFolder)
            .resolve("keep.txt").also { it.writeText("unchanged") }
        management.rename(project.id, "Renamed")
        assertEquals("Renamed", database.harnessDao().workspace(project.id)?.title)
        assertEquals("Renamed", database.harnessDao().workspace(alias.id)?.title)
        assertEquals("Keep thread title", database.agentChatDao().getConversation(session.conversationId)?.title)
        assertEquals(project.id, database.agentChatDao().getConversation(anchor)?.title)
        assertEquals("unchanged", file.readText())
    }

    @Test fun confirmedRemovalDeletesOnlyTheProjectAndPreventsReimport() = runBlocking {
        val project = workspaces.createWorkspace("Remove")
        workspaces.importSession("removed-thread", "History remains in Harness", project.guestPath, project.id)
        val root = AgentLocalWorkspaceSupport.rootForProject(context, project.projectFolder)
        root.resolve("owned.txt").writeText("owned")
        val other = workspaces.createWorkspace("Keep")
        val keep = AgentLocalWorkspaceSupport.rootForProject(context, other.projectFolder)
            .resolve("keep.txt").also { it.writeText("preserved") }
        Files.createSymbolicLink(root.resolve("linked-other").toPath(), keep.parentFile.toPath())

        management.remove(project.id, deleteFiles = true)

        assertFalse(root.exists())
        assertEquals("preserved", keep.readText())
        assertTrue(management.isSessionRemoved("removed-thread", project.guestPath))
        assertFalse(project.id in management.visibleWorkspaceIds(database.harnessDao().workspaces()))
        assertTrue(other.id in management.visibleWorkspaceIds(database.harnessDao().workspaces()))
        assertTrue(runCatching {
            workspaces.importSession("new-remote-list-entry", "Old", project.guestPath)
        }.isFailure)
        assertFalse(root.exists())
    }

    @Test fun removalWithoutFileConsentKeepsExistingBytes() = runBlocking {
        val project = workspaces.createWorkspace("Keep files")
        val file = AgentLocalWorkspaceSupport.rootForProject(context, project.projectFolder)
            .resolve("keep.txt").also { it.writeText("preserved") }
        management.remove(project.id, deleteFiles = false)
        assertEquals("preserved", file.readText())
        assertTrue(management.isSessionRemoved("unindexed", project.guestPath))
        assertFalse(project.id in management.visibleWorkspaceIds(database.harnessDao().workspaces()))
    }

    @Test fun batchRemovalDeduplicatesAliasesAndLeavesUnselectedProjectFiles() = runBlocking {
        val first = workspaces.createWorkspace("First")
        val firstAlias = first.copy(id = "first-alias", backend = "LOCAL_SANDBOX")
        database.harnessDao().saveWorkspace(firstAlias)
        val second = workspaces.createWorkspace("Second")
        val keep = workspaces.createWorkspace("Keep")
        val firstFile = AgentLocalWorkspaceSupport.rootForProject(context, first.projectFolder)
            .resolve("first.txt").also { it.writeText("first") }
        val secondFile = AgentLocalWorkspaceSupport.rootForProject(context, second.projectFolder)
            .resolve("second.txt").also { it.writeText("second") }
        val keepFile = AgentLocalWorkspaceSupport.rootForProject(context, keep.projectFolder)
            .resolve("keep.txt").also { it.writeText("keep") }

        val previews = management.previewBatch(listOf(first.id, firstAlias.id, second.id))
        assertEquals(2, previews.size)
        assertEquals(setOf(first.id, second.id), previews.map { it.id }.toSet())
        assertEquals(2, management.removeBatch(listOf(first.id, firstAlias.id, second.id), deleteFiles = true).size)

        assertFalse(firstFile.exists())
        assertFalse(secondFile.exists())
        assertEquals("keep", keepFile.readText())
        assertTrue(first.id !in management.visibleWorkspaceIds(database.harnessDao().workspaces()))
        assertTrue(second.id !in management.visibleWorkspaceIds(database.harnessDao().workspaces()))
        assertTrue(keep.id in management.visibleWorkspaceIds(database.harnessDao().workspaces()))
    }
}
