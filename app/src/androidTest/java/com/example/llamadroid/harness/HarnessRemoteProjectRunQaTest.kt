package com.example.llamadroid.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentRuntimeSource
import com.example.llamadroid.data.db.HarnessSessionEntity
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Opt-in positive Run/Preview coverage against the disposable Paramiko SSH fixture. */
@RunWith(AndroidJUnit4::class)
class HarnessRemoteProjectRunQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun qaRemotePythonAndStaticPreviewStayScopedAcrossSessions(): Unit = runBlocking {
        assumeTrue("Use the isolated Harness x86_64 QA carrier", BuildConfig.HARNESS_QA_X86)
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass harness_ssh_run_qa=true after starting the disposable fixture",
            args.getString("harness_ssh_run_qa") == "true"
        )
        val host = args.getString("harness_ssh_host") ?: "10.0.2.2"
        assumeTrue("SSH QA must use the emulator host gateway", host == "10.0.2.2")
        val port = args.getString("harness_ssh_port")?.toIntOrNull() ?: 0
        val username = args.getString("harness_ssh_username").orEmpty()
        val password = args.getString("harness_ssh_password").orEmpty()
        assumeTrue("A disposable SSH port is required", port in 1..65_535)
        assumeTrue("A disposable SSH username is required", username.isNotBlank())
        assumeTrue("A disposable SSH password is required", password.isNotBlank())

        val runtime = HarnessAppRuntime.get(context)
        val workspaceId = "ssh-run-qa-" + UUID.randomUUID()
        val projectFolder = "run-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val workspace = HarnessWorkspaceEntity(
            id = workspaceId,
            backend = "REMOTE_SSH",
            projectFolder = projectFolder,
            connectionKey = workspaceId,
            title = "SSH Run QA",
            guestPath = "/workspace/remote/$workspaceId"
        )
        val dao = runtime.database.agentChatDao()
        val conversationA = dao.insertConversation(
            AgentConversationEntity(
                title = "SSH Run QA A",
                projectFolder = projectFolder,
                workspaceBackend = "REMOTE_SSH",
                runtimeSource = AgentRuntimeSource.DEEPSEEK
            )
        )
        val conversationB = dao.insertConversation(
            AgentConversationEntity(
                title = "SSH Run QA B",
                projectFolder = projectFolder,
                workspaceBackend = "REMOTE_SSH",
                runtimeSource = AgentRuntimeSource.DEEPSEEK
            )
        )
        val scopeA = scope(workspace, conversationA, "ssh-run-session-a-$workspaceId", requireNotNull(dao.getConversation(conversationA)))
        val scopeB = scope(workspace, conversationB, "ssh-run-session-b-$workspaceId", requireNotNull(dao.getConversation(conversationB)))
        val credentials = HarnessCredentialStore(context)
        val credentialKey = "adt-ssh/$workspaceId"
        val runner = HarnessRemoteProjectRunner(runtime)
        var previewA: String? = null
        var previewB: String? = null
        var primaryFailure: Throwable? = null
        try {
            credentials.replaceRecord(
                credentialKey,
                null,
                JSONObject().put("kind", "grant").put(
                    "payload",
                    JSONObject().put("host", host).put("port", port)
                        .put("username", username).put("password", password)
                )
            )
            runtime.files.prepareSshWorkspace(workspace)
            runtime.files.invoke(scopeA, "workspace.mkdir", JSONObject().put("path", "${workspace.guestPath}/.adt"))
            runtime.files.writeBytes(
                scopeA,
                "${workspace.guestPath}/index.html",
                "<html><body>remote-run-qa</body></html>".toByteArray()
            )
            val webManifest = JSONObject()
                .put("version", 1).put("runtime", "web").put("entrypoint", "index.html").put("ui", "web")
                .toString()
            runtime.files.writeBytes(scopeA, "${workspace.guestPath}/.adt/run.json", webManifest.toByteArray())
            val webConfig = runner.loadConfig(scopeA)

            val previewAUrl = requireNotNull(runner.run(conversationA, scopeA, webConfig).previewUrl)
            previewA = previewAUrl
            assertEquals("RUNNING", runner.check(conversationA)?.status)
            assertTrue("The first SSH preview did not answer", fetchPreview(previewAUrl).contains("remote-run-qa"))

            val previewBUrl = requireNotNull(runner.run(conversationB, scopeB, webConfig).previewUrl)
            previewB = previewBUrl
            assertTrue("Two sessions must not share the same local tunnel", previewA != previewB)
            assertTrue("The second SSH preview did not answer", fetchPreview(previewBUrl).contains("remote-run-qa"))

            assertEquals("STOPPED", runner.stop(conversationA)?.status)
            awaitPreviewClosed(requireNotNull(previewA))
            assertEquals("RUNNING", runner.check(conversationB)?.status)
            assertTrue("Stopping one session closed the other session's tunnel", fetchPreview(requireNotNull(previewB)).contains("remote-run-qa"))
            assertEquals("STOPPED", runner.stop(conversationB)?.status)
            awaitPreviewClosed(requireNotNull(previewB))

            runtime.files.writeBytes(
                scopeA,
                "${workspace.guestPath}/main.py",
                "import time\nprint('remote-python-qa', flush=True)\ntime.sleep(30)\n".toByteArray()
            )
            val pythonManifest = JSONObject()
                .put("version", 1).put("runtime", "python").put("entrypoint", "main.py").put("ui", "console")
                .toString()
            runtime.files.writeBytes(scopeA, "${workspace.guestPath}/.adt/run.json", pythonManifest.toByteArray())
            val pythonConfig = runner.loadConfig(scopeA)
            assertEquals("RUNNING", runner.run(conversationA, scopeA, pythonConfig).status)
            withTimeout(10_000) {
                while (!runner.check(conversationA)?.logs.orEmpty().contains("remote-python-qa")) delay(100)
            }
            assertEquals("STOPPED", runner.stop(conversationA)?.status)
            assertTrue("The captured SSH cleanup receipt remained after both runs", runtime.files.pendingCleanupSessions().none { it == scopeA.session.harnessSessionId || it == scopeB.session.harnessSessionId })
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            suspend fun attemptCleanup(action: suspend () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
            }
            val cleanupActions = listOf<suspend () -> Unit>(
                { runner.stop(conversationA, force = true) },
                { runner.stop(conversationB, force = true) },
                { assertTrue(runtime.files.retryCleanup(includeActive = true, sessionId = scopeA.session.harnessSessionId).isEmpty()) },
                { assertTrue(runtime.files.retryCleanup(includeActive = true, sessionId = scopeB.session.harnessSessionId).isEmpty()) },
                { if (previewA != null) awaitPreviewClosed(previewA!!) },
                { if (previewB != null) awaitPreviewClosed(previewB!!) }
            )
            for (action in cleanupActions) {
                attemptCleanup(action)
            }
            for (path in listOf("index.html", "main.py", ".adt/run.json")) {
                attemptCleanup {
                    runtime.files.invoke(scopeA, "workspace.delete", JSONObject().put("path", "${workspace.guestPath}/$path"))
                }
            }
            attemptCleanup {
                runtime.files.invoke(scopeA, "workspace.delete", JSONObject().put("path", "${workspace.guestPath}/.adt"))
            }
            if (cleanupFailures.isEmpty()) {
                attemptCleanup {
                    credentials.readRecord(credentialKey)?.let {
                        credentials.replaceRecord(credentialKey, it.getString("revision"), null)
                    }
                }
                attemptCleanup { dao.deleteConversationById(conversationA) }
                attemptCleanup { dao.deleteConversationById(conversationB) }
            }
            cleanupFailures.firstOrNull()?.let { failure ->
                cleanupFailures.drop(1).forEach(failure::addSuppressed)
                primaryFailure?.addSuppressed(failure) ?: throw failure
            }
        }
    }

    private fun scope(
        workspace: HarnessWorkspaceEntity,
        conversationId: Long,
        sessionId: String,
        conversation: AgentConversationEntity
    ) = HarnessSessionScope(
        session = HarnessSessionEntity(sessionId, conversationId, workspace.id),
        workspace = workspace,
        conversation = conversation,
        localRoot = null
    )

    private fun fetchPreview(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        return try {
            assertEquals(200, connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText().take(4_096) }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun awaitPreviewClosed(url: String) {
        withTimeout(5_000) {
            while (true) {
                val closed = runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 300
                        connection.readTimeout = 300
                        connection.connect()
                        false
                    } finally {
                        connection.disconnect()
                    }
                }.getOrDefault(true)
                if (closed) return@withTimeout
                delay(100)
            }
        }
    }
}
