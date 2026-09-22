package com.example.llamadroid.harness

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.KnowledgeBaseEntity
import com.example.llamadroid.data.db.KnowledgeChunkEntity
import com.example.llamadroid.data.db.KnowledgeSourceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HarnessSessionIntegrationTest {
    @Test fun sharedWorkspaceSessionsStayDistinctAndKnowledgeReadsKeepTheirOwnScope() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val journalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val credentials = HarnessCredentialStore(context)
        val workspaces = HarnessWorkspaceRepository(context, database, credentials)
        val files = HarnessWorkspaceAccess(context, credentials)
        val ownedRoots = mutableListOf<File>()
        try {
            val first = workspaces.createWorkspace("harness-qa-${UUID.randomUUID()}")
            val second = workspaces.createWorkspace("harness-qa-${UUID.randomUUID()}")
            val offline = workspaces.openWorkspace(first.id)
            assertTrue(offline is HarnessOfflineWorkspaceScope)
            assertEquals(null, database.harnessDao().sessionForConversation(offline.conversation.id))
            files.writeBytes(offline, "before-session.txt", byteArrayOf(4, 2))
            assertEquals(offline.conversation.id, workspaces.openWorkspace(first.id).conversation.id)
            val firstSession = workspaces.importSession("test-a", "First", first.guestPath)
            val sharedSession = workspaces.importSession("test-b", "Same files", first.guestPath)
            workspaces.importSession("test-c", "Second", second.guestPath)
            val captured = workspaces.scope("test-a")
            val other = workspaces.scope("test-c")
            ownedRoots += requireNotNull(captured.localRoot)
            ownedRoots += requireNotNull(other.localRoot)
            assertArrayEquals(byteArrayOf(4, 2), files.readBytes(captured, "before-session.txt"))
            assertEquals(firstSession.conversationId, workspaces.openWorkspace(first.id).conversation.id)
            assertEquals(firstSession.workspaceId, sharedSession.workspaceId)
            assertTrue(firstSession.conversationId != sharedSession.conversationId)

            val binary = byteArrayOf(0, 127, -1, 10, -128, 0)
            files.writeBytes(captured, "generated/image.bin", binary)
            files.writeBytes(other, "generated/image.bin", byteArrayOf(9))
            assertArrayEquals(binary, files.readBytes(workspaces.scope("test-b"), "generated/image.bin"))
            assertArrayEquals(byteArrayOf(9), files.readBytes(other, "generated/image.bin"))
            val crossWorkspaceReadRejected = try {
                files.readBytes(captured, second.guestPath + "/generated/image.bin")
                false
            } catch (_: Throwable) {
                true
            }
            assertTrue(crossWorkspaceReadRejected)

            val dao = database.knowledgeBaseDao()
            val baseA = dao.insertKnowledgeBase(KnowledgeBaseEntity(name = "Allowed"))
            val baseB = dao.insertKnowledgeBase(KnowledgeBaseEntity(name = "Other session"))
            val sourceA = dao.insertSource(KnowledgeSourceEntity(knowledgeBaseId = baseA, type = "note", sourceRef = "a", title = "A"))
            val sourceB = dao.insertSource(KnowledgeSourceEntity(knowledgeBaseId = baseB, type = "note", sourceRef = "b", title = "B"))
            dao.insertChunks(listOf(
                KnowledgeChunkEntity(id = 101, knowledgeBaseId = baseA, sourceId = sourceA, chunkIndex = 0, text = "allowed-chunk-marker"),
                KnowledgeChunkEntity(id = 102, knowledgeBaseId = baseB, sourceId = sourceB, chunkIndex = 0, text = "other-chunk-marker")
            ))
            database.agentChatDao().updateConversation(captured.conversation.copy(knowledgeBaseIds = baseA.toString()))
            database.agentChatDao().updateConversation(other.conversation.copy(knowledgeBaseIds = baseB.toString()))
            val operations = HarnessBridgeOperations(context, database, credentials, workspaces, files,
                HarnessLocalModels(context, database), HarnessDiagnostics(database, journalScope)) { _, _, _ -> error("Unexpected execution") }
            val allowed = operations.invoke("knowledge.read", "test-a", JSONObject().put("chunkId", 101)).toString()
            val denied = operations.invoke("knowledge.read", "test-a", JSONObject().put("chunkId", 102)).toString()
            val otherRead = operations.invoke("knowledge.read", "test-c", JSONObject().put("chunkId", 102)).toString()
            assertTrue(allowed.contains("allowed-chunk-marker"))
            assertFalse(denied.contains("other-chunk-marker"))
            assertTrue(otherRead.contains("other-chunk-marker"))
            journalScope.coroutineContext[Job]?.children?.toList()?.joinAll()
            val events = database.agentChatDao().getRecentProjectEventsSync()
            assertTrue(events.isNotEmpty())
            assertTrue(events.all { !it.toString().contains("chunk-marker") })
            // Re-indexing updates presentation, but cannot redirect an existing session to another root.
            workspaces.importSession("test-a", "Renamed in original interface", second.guestPath)
            assertEquals(first.id, workspaces.scope("test-a").workspace.id)
            assertEquals("Renamed in original interface", workspaces.scope("test-a").conversation.title)
        } finally {
            journalScope.coroutineContext[Job]?.cancelAndJoin(); database.close()
            // These are the two random project directories created by this test, never user projects.
            ownedRoots.forEach { it.deleteRecursively() }
        }
    }
}
