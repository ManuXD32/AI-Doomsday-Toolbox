package com.example.llamadroid.harness

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.HarnessSessionEntity
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Emulator-only SSH integration. The host launches tools/harness_ssh_qa_server.py with a
 * random /tmp root and passes the password through instrumentation arguments. The test is
 * skipped unless root explicitly opts in, so normal Android test runs never contact SSH.
 */
@RunWith(AndroidJUnit4::class)
class HarnessSshIntegrationQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun qaRemoteWorkspaceAndOwnedProcessCleanup() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass harness_ssh_qa=true after starting the disposable fixture", args.getString("harness_ssh_qa") == "true")
        val host: String = args.getString("harness_ssh_host") ?: "10.0.2.2"
        assumeTrue("SSH QA must use the emulator host gateway", host == "10.0.2.2")
        val port = args.getString("harness_ssh_port")?.toIntOrNull() ?: 0
        val username = args.getString("harness_ssh_username").orEmpty()
        val password = args.getString("harness_ssh_password").orEmpty()
        assumeTrue("A disposable SSH port is required", port in 1..65_535)
        assumeTrue("A disposable SSH username is required", username.isNotBlank())
        assumeTrue("A disposable SSH password is required", password.isNotBlank())

        val workspaceId = "ssh-qa-" + UUID.randomUUID().toString()
        val projectFolder = "qa-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val marker = "ADT_SSH_QA_" + UUID.randomUUID().toString().replace("-", "")
        val workspace = HarnessWorkspaceEntity(
            id = workspaceId,
            backend = "REMOTE_SSH",
            projectFolder = projectFolder,
            connectionKey = workspaceId,
            title = "SSH QA",
            guestPath = "/workspace/$projectFolder"
        )
        val scope = HarnessSessionScope(
            session = HarnessSessionEntity("ssh-qa-session-$workspaceId", 0L, workspaceId),
            workspace = workspace,
            conversation = AgentConversationEntity(
                title = "SSH QA",
                projectFolder = projectFolder,
                workspaceBackend = "REMOTE_SSH"
            ),
            localRoot = null
        )
        val credentials = HarnessCredentialStore(context)
        val credentialKey = "adt-ssh/$workspaceId"
        val files = HarnessWorkspaceAccess(context, credentials)
        val diagnosticScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val diagnostics = HarnessDiagnostics(AppDatabase.getDatabase(context), diagnosticScope)
        val processes = HarnessSshProcesses(files, diagnostics)
        val record = JSONObject().apply {
            put("kind", "grant")
            put("payload", JSONObject().apply {
                put("host", host)
                put("port", port)
                put("username", username)
                put("password", password)
            })
        }
        try {
            credentials.replaceRecord(credentialKey, null, record)
            files.prepareSshWorkspace(workspace)

            val markerPath = "${workspace.guestPath}/qa-marker.txt"
            files.writeBytes(scope, markerPath, marker.toByteArray(Charsets.UTF_8))
            assertEquals(marker, String(files.readBytes(scope, markerPath), Charsets.UTF_8))

            val targetPath = "${workspace.guestPath}/symlink-target.txt"
            val linkPath = "${workspace.guestPath}/symlink-final.txt"
            files.writeBytes(scope, targetPath, "target-$marker".toByteArray(Charsets.UTF_8))
            val linkCreated = files.execute(
                scope,
                "ln -s symlink-target.txt symlink-final.txt",
                timeoutMs = 10_000L
            )
            assertEquals(0, linkCreated.getInt("exitCode"))
            val symlinkWrite = try {
                files.writeBytes(scope, linkPath, "must-not-replace-target".toByteArray(Charsets.UTF_8))
                null
            } catch (failure: Throwable) {
                failure
            }
            assertEquals("WORKSPACE_WRITE_SYMLINK_REJECTED", symlinkWrite?.message)
            assertEquals("target-$marker", String(files.readBytes(scope, targetPath), Charsets.UTF_8))

            val executed = files.execute(
                scope,
                "printf '%s' '$marker'",
                timeoutMs = 10_000L
            )
            assertEquals(0, executed.getInt("exitCode"))
            assertEquals(marker, executed.getString("output"))

            val started = processes.invoke(
                scope,
                "workspace.process.start",
                JSONObject()
                    .put("command", "cat")
                    .put("cwd", ".")
                    .put("pty", true)
                    .put("cols", 80)
                    .put("rows", 24)
            )
            val processId = started.getString("id")
            val resized = processes.invoke(
                scope,
                "workspace.process.resize",
                JSONObject().put("id", processId).put("cols", 100).put("rows", 40)
            )
            assertTrue(resized.getBoolean("resized"))
            val wireData = "WIRE-$marker"
            val written = processes.invoke(
                scope,
                "workspace.process.write",
                JSONObject()
                    .put("id", processId)
                    .put("dataBase64", Base64.encodeToString(wireData.toByteArray(), Base64.NO_WRAP))
            )
            assertEquals(wireData.length, written.getInt("written"))

            var transcript = ""
            withTimeout(10_000L) {
                while (!transcript.contains(wireData)) {
                    val running = processes.invoke(
                        scope,
                        "workspace.process.read",
                        JSONObject().put("id", processId)
                    )
                    transcript += String(
                        Base64.decode(running.getString("stdoutBase64"), Base64.DEFAULT),
                        Charsets.UTF_8
                    )
                    if (running.getString("status") != "running") break
                    delay(50L)
                }
            }
            assertTrue("PTY process did not echo process.write", transcript.contains(wireData))

            // Runtime-wide retry cleanup must leave an active official SSH process alone. The
            // owner receipt remains registered internally, but it is not returned as pending
            // work while HarnessSshProcesses still owns the PTY.
            val cleanupWhileActive = files.retryCleanup()
            assertTrue(
                "retryCleanup touched an active SSH PTY receipt",
                cleanupWhileActive.none { it == scope.session.harnessSessionId }
            )
            val activeState = processes.invoke(
                scope,
                "workspace.process.read",
                JSONObject().put("id", processId)
            )
            assertEquals("running", activeState.getString("status"))

            val ended = processes.invoke(
                scope,
                "workspace.process.endInput",
                JSONObject().put("id", processId)
            )
            assertTrue(ended.getBoolean("ended"))
            var finalState = "running"
            withTimeout(10_000L) {
                while (finalState == "running") {
                    val status = processes.invoke(
                        scope,
                        "workspace.process.read",
                        JSONObject().put("id", processId)
                    )
                    finalState = status.getString("status")
                    if (finalState == "running") delay(50L)
                }
            }
            assertEquals("exited", finalState)

            val closed = processes.invoke(
                scope,
                "workspace.process.close",
                JSONObject().put("id", processId)
            )
            assertTrue(closed.getBoolean("closed"))
            withTimeout(10_000L) {
                while (true) {
                    val pending = files.retryCleanup()
                    if (scope.session.harnessSessionId !in pending) break
                    delay(100L)
                }
                assertTrue(
                    "Owned SSH cleanup receipt remained",
                    scope.session.harnessSessionId !in files.pendingCleanupSessions()
                )
            }

            // A transport disconnect must leave the owner receipt visible until the
            // adapter can reconnect and verify remote cleanup. Closing the already
            // disconnected PTY exercises that recovery path without a fake host fault.
            val recoveryStarted = processes.invoke(
                scope,
                "workspace.process.start",
                JSONObject()
                    .put("command", "cat")
                    .put("cwd", ".")
                    .put("pty", true)
                    .put("cols", 80)
                    .put("rows", 24)
            )
            val recoveryProcessId = recoveryStarted.getString("id")
            assertEquals(
                "running",
                processes.invoke(
                    scope,
                    "workspace.process.read",
                    JSONObject().put("id", recoveryProcessId)
                ).getString("status")
            )
            files.cancelOwnedRequests()
            val closeAfterDisconnect = try {
                processes.invoke(
                    scope,
                    "workspace.process.close",
                    JSONObject().put("id", recoveryProcessId)
                )
                Result.success<Unit>(Unit)
            } catch (failure: Throwable) {
                Result.failure<Unit>(failure)
            }
            assertTrue(
                "Closing a disconnected PTY should retain its cleanup receipt",
                closeAfterDisconnect.isFailure
            )
            assertTrue(
                "Disconnected SSH cleanup receipt was not visible",
                files.pendingCleanupSessions().contains(scope.session.harnessSessionId)
            )
            val recoveredPending = withTimeout(15_000L) {
                var pending = files.retryCleanup().filter { it == scope.session.harnessSessionId }
                while (pending.isNotEmpty() || scope.session.harnessSessionId in files.pendingCleanupSessions()) {
                    delay(100L)
                    pending = files.retryCleanup().filter { it == scope.session.harnessSessionId }
                }
                pending
            }
            assertTrue(
                "Retry did not reconnect and clear the SSH cleanup receipt",
                recoveredPending.isEmpty() &&
                    scope.session.harnessSessionId !in files.pendingCleanupSessions()
            )
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            suspend fun attemptCleanup(action: suspend () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                }
            }
            attemptCleanup { processes.cancelAll() }
            attemptCleanup { files.cancelOwnedRequests() }
            attemptCleanup { files.retryCleanup(includeActive = true) }
            attemptCleanup {
                files.invoke(
                    scope,
                    "workspace.delete",
                    JSONObject().put("path", "${workspace.guestPath}/symlink-final.txt")
                )
            }
            listOf("symlink-target.txt", "qa-marker.txt").forEach { name ->
                attemptCleanup {
                    files.invoke(
                        scope,
                        "workspace.delete",
                        JSONObject().put("path", "${workspace.guestPath}/$name")
                    )
                }
            }
            attemptCleanup {
                credentials.readRecord(credentialKey)?.let {
                    credentials.replaceRecord(credentialKey, it.getString("revision"), null)
                }
            }
            diagnosticScope.cancel()
            cleanupFailures.firstOrNull()?.let { failure ->
                cleanupFailures.drop(1).forEach(failure::addSuppressed)
                throw failure
            }
        }
    }
}
