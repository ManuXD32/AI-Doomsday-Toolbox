package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DelegateProjectBinderTest {
    private class FakePort : DelegateHarnessPort {
        val projects = mutableMapOf("ws1" to DelegateHarnessProject("ws1", "Proj", "/p", "h1"))
        val sessions = mutableMapOf("s1" to "ws1") // session -> workspace (Room rows)
        val harnessSessions = mutableSetOf("s1")
        val calls = mutableListOf<String>()
        var adoptError: DelegationHarnessException? = null
        var nextSession = 100

        override suspend fun createProject(title: String): DelegateHarnessProject {
            calls += "createProject:$title"
            val p = DelegateHarnessProject("wsNew", title, "/n", null)
            projects[p.workspaceId] = p
            return p
        }
        override suspend fun openProject(workspaceId: String): DelegateHarnessProject {
            calls += "openProject:$workspaceId"
            return projects[workspaceId] ?: throw DelegationHarnessException("PROJECT_NOT_FOUND", "gone")
        }
        override suspend fun createThread(project: DelegateHarnessProject, title: String, suppressApprovals: Boolean): DelegateHarnessThread {
            calls += "createThread:${project.workspaceId}"
            val id = "s${nextSession++}"
            sessions[id] = project.workspaceId
            harnessSessions += id
            return DelegateHarnessThread(id, 5L, project, title)
        }
        override suspend fun adoptThread(project: DelegateHarnessProject, sessionId: String, suppressApprovals: Boolean): DelegateHarnessThread {
            calls += "adopt:$sessionId"
            adoptError?.let { throw it }
            if (sessions[sessionId] != project.workspaceId) throw DelegationHarnessException("THREAD_NOT_FOUND", "no")
            return DelegateHarnessThread(sessionId, 7L, project, "Old")
        }
        override suspend fun sessionKnown(sessionId: String): Boolean = sessionId in harnessSessions
        override suspend fun selectModel(sessionId: String, binding: HarnessModelBinding) { calls += "model:$sessionId" }
        override suspend fun applyApprovalPolicy(sessionId: String, policy: DelegationApprovalPolicy): DelegateApprovalOutcome {
            calls += "policy:$sessionId"
            return DelegateApprovalOutcome("workspace-write", true, null)
        }
    }

    private class FakeStore(val tasks: List<NativeChatDelegateTaskEntity>) : DelegateBindStore {
        val saves = mutableListOf<Pair<Long, DelegateBindingFields>>()
        override suspend fun chatTasks(chatId: Long) = tasks.filter { it.chatId == chatId }
        override suspend fun saveBinding(taskId: Long, binding: DelegateBindingFields) { saves += taskId to binding }
    }

    private val binding = HarnessModelBinding("p", "m", 1000L, 200L, HarnessRouteKind.MANAGED_CARD)

    private fun task(id: Long, ws: String? = null, session: String? = null, threads: String = "[]", created: Long = id) =
        NativeChatDelegateTaskEntity(id = id, chatId = 1, title = "Task $id", request = "r", runtimeId = "rt",
            workspaceId = ws, harnessSessionId = session, harnessThreadsJson = threads, createdAt = created)

    private fun bind(
        port: FakePort, store: FakeStore, current: NativeChatDelegateTaskEntity,
        options: DelegationTaskOptions = DelegationTaskOptions(), newProject: Boolean = false, freshThread: Boolean = false,
    ) = runBlocking {
        DelegateProjectBinder(port, store).bind(current, options, DelegationApprovalPolicy.SUBAGENT_DECIDES, newProject, freshThread) { binding }
    }

    @Test
    fun reopenSameThreadReselectsModel() {
        val port = FakePort()
        val current = task(2, "ws1", "s1", """[{"id":"s1","title":"Old"}]""")
        val store = FakeStore(listOf(task(1, "ws1", "s1"), current))
        val r = bind(port, store, current)
        assertTrue(r.resumed); assertFalse(r.replaced)
        assertEquals("s1", r.thread.sessionId)
        assertFalse(port.calls.any { it.startsWith("createThread") })
        assertTrue(port.calls.containsAll(listOf("model:s1", "policy:s1")))
        assertEquals(1, r.threadNumber)
        assertTrue(r.describe().contains("keeps its earlier conversation"))
    }

    @Test
    fun restartWithFreshPortStillAdoptsAndAppliesModelAndPolicy() {
        val current = task(2, "ws1", "s1")
        val store = FakeStore(listOf(current))
        val r1 = bind(FakePort(), store, current)
        val port2 = FakePort()
        val r2 = bind(port2, store, current)
        assertEquals(r1.thread.sessionId, r2.thread.sessionId)
        assertEquals(listOf("openProject:ws1", "adopt:s1", "model:s1", "policy:s1"), port2.calls)
    }

    @Test
    fun threadGoneCreatesNewThreadInSameProjectWithNotes() {
        val port = FakePort().also { it.harnessSessions.clear() }
        val current = task(2, "ws1", "s1", """[{"id":"s1","title":"Old"}]""")
        val store = FakeStore(listOf(current))
        val r = bind(port, store, current)
        assertTrue(r.replaced); assertFalse(r.resumed)
        assertEquals("ws1", r.project.workspaceId)
        assertEquals(2, r.threadCount)
        assertEquals(DelegateProjectBinder.SUBAGENT_NOTE, r.subagentNote)
        assertNotNull(r.mainNotice)
        assertEquals(1, store.saves.size)
        assertTrue(port.calls.contains("createThread:ws1"))
    }

    @Test
    fun otherAdoptErrorPropagatesAndNothingSaved() {
        val port = FakePort().also { it.adoptError = DelegationHarnessException("RUNTIME_NOT_RUNNING", "down") }
        val current = task(2, "ws1", "s1")
        val store = FakeStore(listOf(current))
        try { bind(port, store, current); fail("expected exception") }
        catch (e: DelegationHarnessException) { assertEquals("RUNTIME_NOT_RUNNING", e.code) }
        assertTrue(store.saves.isEmpty())
        assertFalse(port.calls.any { it.startsWith("createThread") })
    }

    @Test
    fun projectGoneIsUnavailableAndNothingSaved() {
        val port = FakePort().also { it.projects.clear() }
        val current = task(2, "ws1", "s1")
        val store = FakeStore(listOf(current))
        try { bind(port, store, current); fail("expected exception") }
        catch (e: DelegationHarnessException) { assertEquals("PROJECT_UNAVAILABLE", e.code) }
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun defaultWithChatProjectContinuesItAndItsLastThread() {
        val port = FakePort()
        val current = task(3)
        val store = FakeStore(listOf(task(1, "ws1", "s1"), current))
        val r = bind(port, store, current)
        assertFalse(r.projectCreated)
        assertEquals("ws1", r.project.workspaceId)
        assertEquals("s1", r.thread.sessionId)
        assertFalse(port.calls.any { it.startsWith("createProject") })
    }

    @Test
    fun explicitNewProjectCreatesOne() {
        val port = FakePort()
        val current = task(3)
        val store = FakeStore(listOf(task(1, "ws1", "s1"), current))
        val r = bind(port, store, current, newProject = true)
        assertTrue(r.projectCreated)
        assertEquals("Task 3", r.project.title)
        assertTrue(r.describe().contains("created"))
        assertEquals(1, r.threadCount)
    }

    @Test
    fun chatWithoutProjectCreatesOne() {
        val port = FakePort()
        val current = task(1)
        val r = bind(port, FakeStore(listOf(current)), current)
        assertTrue(r.projectCreated)
    }

    @Test
    fun optionsProjectIdUsesChatsLastThreadInThatProject() {
        val port = FakePort()
        port.projects["ws2"] = DelegateHarnessProject("ws2", "Other", "/o", null)
        port.sessions["s2"] = "ws2"; port.harnessSessions += "s2"
        val current = task(4)
        val store = FakeStore(listOf(task(1, "ws1", "s1"), task(2, "ws2", "s2"), task(3, "ws1", "s1"), current))
        val r = bind(port, store, current, DelegationTaskOptions(projectId = "ws2"))
        assertEquals("ws2", r.project.workspaceId)
        assertEquals("s2", r.thread.sessionId)
    }

    @Test
    fun freshThreadCreatesThreadInExistingProject() {
        val port = FakePort()
        val current = task(2, "ws1", "s1", """[{"id":"s1","title":"Old"}]""")
        val r = bind(port, FakeStore(listOf(current)), current, freshThread = true)
        assertFalse(r.resumed); assertFalse(r.replaced)
        assertEquals("ws1", r.project.workspaceId)
        assertEquals(2, r.threadNumber)
        assertNull(r.subagentNote)
        assertFalse(port.calls.any { it.startsWith("adopt") })
    }

    @Test
    fun singleSaveCarriesAllFields() {
        val port = FakePort()
        val current = task(2, "ws1", "s1", """[{"id":"s1","title":"Old"}]""")
        val store = FakeStore(listOf(current))
        bind(port, store, current)
        assertEquals(1, store.saves.size)
        val (id, f) = store.saves.single()
        assertEquals(2L, id)
        assertEquals("ws1", f.workspaceId)
        assertEquals("s1", f.harnessSessionId)
        assertEquals(7L, f.harnessConversationId)
        assertEquals(1, JSONArray(f.harnessThreadsJson).length())
        assertTrue(f.harnessModelJson.contains("\"model\":\"m\""))
    }
}
