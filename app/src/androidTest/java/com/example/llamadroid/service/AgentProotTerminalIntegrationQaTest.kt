package com.example.llamadroid.service

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentProotEnvironmentEntity
import com.example.llamadroid.data.db.AgentProotEnvironmentSharingMode
import com.example.llamadroid.data.db.AgentProotEnvironmentStatus
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.proot.AgentProotEnvironmentManager
import com.example.llamadroid.data.proot.DebianAssetPack
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Opt-in API36 x86 coverage for the project terminal manager. This exercises the production
 * conversation/database path, guest-shell receipt, real PTY input, resize, and teardown. It is
 * intentionally separate from the recovery terminal because the project manager owns a Room run
 * row and a workspace mount for each conversation.
 */
@RunWith(AndroidJUnit4::class)
class AgentProotTerminalIntegrationQaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database by lazy { AppDatabase.getDatabase(context) }
    private val environmentManager by lazy { AgentProotEnvironmentManager(context) }
    private val environmentId = "qa_project_pty_${UUID.randomUUID()}"
    private val projectFolder = "qa_project_pty_${UUID.randomUUID().toString().replace("-", "")}"
    private var conversationId: Long? = null
    private var terminalManager: AgentProotTerminalSessionManager? = null

    @Before
    fun prepareRows(): Unit = runBlocking {
        assumeTrue(
            "Pass project_terminal_qa=true for disposable project PTY coverage",
            InstrumentationRegistry.getArguments().getString("project_terminal_qa") == "true"
        )
        assumeTrue("Use the explicit x86_64 Harness QA carrier", BuildConfig.HARNESS_QA_X86)
        assumeTrue(
            "The carrier must expose x86_64 native binaries",
            android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"
        )
        assumeTrue("Install-time Debian asset pack is required", DebianAssetPack.isAvailable(context))

        database.agentProotRunDao().deleteForEnvironment(environmentId)
        database.agentProotEnvironmentDao().getById(environmentId)?.let {
            database.agentProotEnvironmentDao().delete(it)
        }
        environmentManager.delete(environmentId)
        AgentLocalWorkspaceSupport.deleteProjectRoot(context, projectFolder)

        val projectRoot = AgentLocalWorkspaceSupport.rootForProject(context, projectFolder)
        File(projectRoot, "project-terminal-fixture.txt").writeText(
            "project-terminal-visible",
            Charsets.UTF_8
        )
        database.agentProotEnvironmentDao().insert(
            AgentProotEnvironmentEntity(
                id = environmentId,
                displayName = "Project PTY QA",
                storageKey = environmentId,
                imageDigest = requireNotNull(DebianAssetPack.readExpectedRootfsSha256(context)) {
                    "Signed Debian rootfs checksum metadata is required for project PTY QA"
                },
                sharingMode = AgentProotEnvironmentSharingMode.ISOLATED,
                status = AgentProotEnvironmentStatus.NOT_INSTALLED
            )
        )
        conversationId = database.agentChatDao().insertConversation(
            AgentConversationEntity(
                title = "Project PTY QA",
                projectFolder = projectFolder,
                workspaceBackend = AgentWorkspaceBackendType.LOCAL_PROOT.name,
                prootEnvironmentId = environmentId
            )
        )
        terminalManager = AgentProotTerminalSessionManager(context)
    }

    @After
    fun removeOwnedFixture(): Unit = runBlocking {
        val manager = terminalManager
        conversationId?.let { id ->
            manager?.closeForConversation(id)
            database.agentChatDao().deleteConversationById(id)
        }
        database.agentProotRunDao().deleteForEnvironment(environmentId)
        database.agentProotEnvironmentDao().getById(environmentId)?.let {
            database.agentProotEnvironmentDao().delete(it)
        }
        environmentManager.delete(environmentId)
        AgentLocalWorkspaceSupport.deleteProjectRoot(context, projectFolder)
    }

    @Test
    fun qaProjectPtyReachesGuestWritesResizesAcceptsCtrlCAndCloses() = runBlocking {
        val manager = requireNotNull(terminalManager)
        val anchor = requireNotNull(conversationId)
        val opened = withTimeout(240_000L) {
            manager.open(anchor, projectFolder).getOrThrow()
        }
        assertTrue("Project terminal did not report connected", opened.isConnected)

        val sessionId = opened.sessionId
        manager.send(sessionId, "stty -echo").getOrThrow()
        SystemClock.sleep(250)
        manager.send(
            sessionId,
            "printf '__PROJECT_GUEST_READY__ %s\\n' \"\$PWD\"; " +
                "if test -t 0 && test -t 1 && test -t 2; then " +
                "echo __PROJECT_TTY_OK__; else echo __PROJECT_TTY_BAD__; fi; " +
                "grep -Fxq project-terminal-visible /workspace/project-terminal-fixture.txt && " +
                "echo __PROJECT_MOUNT_OK__"
        ).getOrThrow()
        val ready = waitForScreen(manager, sessionId, "__PROJECT_MOUNT_OK__", 60_000L)
        assertTrue("Guest shell did not reach /workspace: $ready", ready.contains("/workspace"))
        assertTrue("Project session did not have a real PTY: $ready", ready.contains("__PROJECT_TTY_OK__"))

        instrumentation.runOnMainSync {
            manager.terminalSession(sessionId)?.updateSize(96, 30)
        }
        manager.send(
            sessionId,
            "if [ \"\$(stty size)\" = '30 96' ]; then echo __PROJECT_RESIZE_OK__; " +
                "else echo __PROJECT_RESIZE_BAD__; fi"
        ).getOrThrow()
        val resized = waitForScreen(manager, sessionId, "__PROJECT_RESIZE_", 30_000L)
        assertTrue("PTY resize did not reach the guest: $resized", resized.contains("__PROJECT_RESIZE_OK__"))

        manager.send(sessionId, "sleep 20").getOrThrow()
        SystemClock.sleep(300)
        manager.send(sessionId, "\u0003", appendNewline = false).getOrThrow()
        manager.send(sessionId, "echo __PROJECT_CTRL_C_OK__").getOrThrow()
        waitForScreen(manager, sessionId, "__PROJECT_CTRL_C_OK__", 30_000L)

        manager.close(sessionId).getOrThrow()
        assertEquals(
            "Project terminal remained connected after close",
            emptyList<WorkspaceTerminalUiState>(),
            manager.current(anchor)
        )
    }

    private fun waitForScreen(
        manager: AgentProotTerminalSessionManager,
        sessionId: String,
        marker: String,
        timeoutMs: Long
    ): String {
        var latest = ""
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = transcript(manager, sessionId)
            if (latest.contains(marker)) return latest
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for $marker; transcript=$latest")
    }

    private fun transcript(manager: AgentProotTerminalSessionManager, sessionId: String): String {
        var text = ""
        instrumentation.runOnMainSync {
            text = manager.terminalSession(sessionId)?.emulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }
}
