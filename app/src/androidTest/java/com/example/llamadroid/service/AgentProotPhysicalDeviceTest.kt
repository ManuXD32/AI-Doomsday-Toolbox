package com.example.llamadroid.service

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentProotEnvironmentEntity
import com.example.llamadroid.data.db.AgentProotEnvironmentSharingMode
import com.example.llamadroid.data.db.AgentProotEnvironmentStatus
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.proot.AgentProotEnvironmentManager
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.data.proot.DebianAssetPack
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in connected acceptance for an arm64 device installed from an APK set that includes the
 * install-time Debian pack. A plain Gradle debug APK has no pack and skips this test.
 */
@RunWith(AndroidJUnit4::class)
class AgentProotPhysicalDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database by lazy { AppDatabase.getDatabase(context) }
    private val environmentManager by lazy { AgentProotEnvironmentManager(context) }
    private var terminalManager: AgentProotTerminalSessionManager? = null
    private var conversationId: Long? = null
    private val environmentId = "qa_pty_physical"
    private val projectFolder = "qa_pty_workspace"
    private val siblingFolder = "qa_pty_sibling"

    @Before
    fun prepareRows(): Unit = runBlocking {
        assumeTrue("Install-time Debian asset pack is required", DebianAssetPack.isAvailable(context))
        database.agentProotRunDao().deleteForEnvironment(environmentId)
        database.agentProotEnvironmentDao().getById(environmentId)?.let {
            database.agentProotEnvironmentDao().delete(it)
        }
        environmentManager.delete(environmentId)
        AgentLocalWorkspaceSupport.deleteProjectRoot(context, projectFolder)
        AgentLocalWorkspaceSupport.deleteProjectRoot(context, siblingFolder)
        val projectRoot = AgentLocalWorkspaceSupport.rootForProject(context, projectFolder)
        File(projectRoot, "device-test.txt").writeText("workspace-visible", Charsets.UTF_8)
        val siblingRoot = AgentLocalWorkspaceSupport.rootForProject(context, siblingFolder)
        File(siblingRoot, "must-not-be-visible.txt").writeText("sibling-secret", Charsets.UTF_8)
        database.agentProotEnvironmentDao().insert(
            AgentProotEnvironmentEntity(
                id = environmentId,
                displayName = "Physical PTY QA",
                storageKey = environmentId,
                sharingMode = AgentProotEnvironmentSharingMode.ISOLATED,
                status = AgentProotEnvironmentStatus.NOT_INSTALLED
            )
        )
        conversationId = database.agentChatDao().insertConversation(
            AgentConversationEntity(
                title = "Physical PTY QA",
                projectFolder = projectFolder,
                workspaceBackend = AgentWorkspaceBackendType.LOCAL_PROOT.name,
                prootEnvironmentId = environmentId
            )
        )
        terminalManager = AgentProotTerminalSessionManager(context)
    }

    @After
    fun removeOwnedFixture(): Unit = runBlocking {
        terminalManager?.closeAll()
        SystemClock.sleep(500)
        conversationId?.let { database.agentChatDao().deleteConversationById(it) }
        database.agentProotRunDao().deleteForEnvironment(environmentId)
        database.agentProotEnvironmentDao().getById(environmentId)?.let {
            database.agentProotEnvironmentDao().delete(it)
        }
        environmentManager.delete(environmentId)
        AgentLocalWorkspaceSupport.deleteProjectRoot(context, projectFolder)
        AgentLocalWorkspaceSupport.deleteProjectRoot(context, siblingFolder)
        Unit
    }

    @Test
    fun realPtySupportsInteractiveDebianNanoAndIndependentSessions() = runBlocking {
        val manager = requireNotNull(terminalManager)
        val anchor = requireNotNull(conversationId)
        val first = manager.open(anchor, projectFolder).getOrThrow()
        waitForScreen(manager, first.sessionId, "debian:/workspace", 240_000)
        // Commands are normally echoed by the interactive TTY. Suppress that echo in this
        // instrumented fixture so a marker can only be observed after the guest command ran.
        manager.send(first.sessionId, "stty -echo").getOrThrow()
        SystemClock.sleep(300)

        manager.send(
            first.sessionId,
                ". /etc/os-release; printf '__DEBIAN__ %s %s\\n' \"\$ID\" \"\$VERSION_CODENAME\"; " +
                "printf '__TTY__ '; test -t 0 && test -t 1 && test -t 2 && echo yes || echo no; " +
                "tty; tty >/dev/null && echo __TTY_PATH_OK__; " +
                "printf '__UNICODE__ áéíóú ✓\\n'; printf '\\033[31m__COLOR__\\033[0m\\n'; " +
                "git --version >/dev/null && echo __GIT_OK__; " +
                "python3 --version >/dev/null && echo __PYTHON_OK__; " +
                "test -f /workspace/device-test.txt && echo __WORKSPACE_OK__; " +
                "test ! -e /workspace/../$siblingFolder/must-not-be-visible.txt && echo __SIBLING_HIDDEN__; " +
                "test ! -e /data/user/0/${context.packageName}/files && echo __APP_PRIVATE_HIDDEN__"
        ).getOrThrow()
        val identity = waitForScreen(manager, first.sessionId, "__APP_PRIVATE_HIDDEN__", 60_000)
        assertTrue(identity.contains("__DEBIAN__ debian trixie"))
        assertTrue(identity.contains("__TTY__ yes"))
        assertTrue(identity.contains("__TTY_PATH_OK__"))
        assertTrue(identity.contains("__UNICODE__ áéíóú ✓"))
        assertTrue(identity.contains("__COLOR__"))
        assertTrue(identity.contains("__GIT_OK__"))
        assertTrue(identity.contains("__PYTHON_OK__"))
        assertTrue(identity.contains("__WORKSPACE_OK__"))
        assertTrue(identity.contains("__SIBLING_HIDDEN__"))

        instrumentation.runOnMainSync {
            manager.terminalSession(first.sessionId)?.updateSize(100, 32)
        }
        manager.send(
            first.sessionId,
            "if [ \"\$(stty size)\" = '32 100' ]; then echo __RESIZE_OK__; " +
                "else printf '__RESIZE_BAD__ '; stty size; fi; echo __RESIZED__"
        ).getOrThrow()
        val resized = waitForScreen(manager, first.sessionId, "__RESIZED__", 30_000)
        assertTrue("PTY resize did not reach the guest:\n$resized", resized.contains("__RESIZE_OK__"))

        manager.send(first.sessionId, "printf '__BEFORE_CLEAR__\\n'; clear; printf '__AFTER_CLEAR__\\n'")
            .getOrThrow()
        waitForScreen(manager, first.sessionId, "__AFTER_CLEAR__", 30_000)
        val visibleAfterClear = visibleScreen(manager, first.sessionId)
        assertTrue(visibleAfterClear.contains("__AFTER_CLEAR__"))
        assertFalse(visibleAfterClear.contains("__BEFORE_CLEAR__"))

        manager.send(first.sessionId, "sleep 30", appendNewline = true).getOrThrow()
        SystemClock.sleep(700)
        manager.send(first.sessionId, "\u0003", appendNewline = false).getOrThrow()
        manager.send(first.sessionId, "echo __CTRL_C_OK__").getOrThrow()
        waitForScreen(manager, first.sessionId, "__CTRL_C_OK__", 15_000)

        manager.send(first.sessionId, "apt update && echo __APT_UPDATED__").getOrThrow()
        waitForScreen(manager, first.sessionId, "__APT_UPDATED__", 240_000)
        manager.send(first.sessionId, "apt install nano; echo __APT_INSTALL_DONE__").getOrThrow()
        var aptInstallScreen = ""
        waitUntil(180_000) {
            aptInstallScreen = transcript(manager, first.sessionId)
            aptInstallScreen.contains("Do you want to continue?") ||
                aptInstallScreen.contains("__APT_INSTALL_DONE__")
        }
        if (aptInstallScreen.contains("Do you want to continue?")) {
            manager.send(first.sessionId, "y").getOrThrow()
        }
        waitForScreen(manager, first.sessionId, "__APT_INSTALL_DONE__", 180_000)
        manager.send(first.sessionId, "command -v nano >/dev/null && echo __NANO_INSTALLED__")
            .getOrThrow()
        waitForScreen(manager, first.sessionId, "__NANO_INSTALLED__", 30_000)

        manager.send(first.sessionId, "nano /workspace/pty-note.txt").getOrThrow()
        waitForScreen(manager, first.sessionId, "GNU nano", 30_000)
        manager.send(first.sessionId, "saved through real PTY", appendNewline = false).getOrThrow()
        manager.send(first.sessionId, "\u000f", appendNewline = false).getOrThrow()
        SystemClock.sleep(300)
        manager.send(first.sessionId, "\r", appendNewline = false).getOrThrow()
        SystemClock.sleep(500)
        manager.send(first.sessionId, "\u0018", appendNewline = false).getOrThrow()
        manager.send(
            first.sessionId,
            "grep -Fxq 'saved through real PTY' /workspace/pty-note.txt && echo __NANO_FILE_OK__; " +
                "python3 -m venv /workspace/.qa-venv && " +
                "/workspace/.qa-venv/bin/python -m pip --version >/dev/null && echo __PIP_OK__; " +
                "echo __TOOLS_OK__"
        ).getOrThrow()
        val tools = waitForScreen(manager, first.sessionId, "__TOOLS_OK__", 180_000)
        assertTrue(tools.contains("__NANO_FILE_OK__"))
        assertTrue(tools.contains("__PIP_OK__"))

        val second = manager.open(anchor, projectFolder).getOrThrow()
        waitForScreen(manager, second.sessionId, "debian:/workspace", 60_000)
        manager.send(second.sessionId, "echo __SECOND_ALIVE__").getOrThrow()
        waitForScreen(manager, second.sessionId, "__SECOND_ALIVE__", 30_000)
        assertTrue(manager.current(anchor).count { it.isConnected } >= 2)

        manager.close(first.sessionId).getOrThrow()
        manager.send(second.sessionId, "echo __INDEPENDENT__").getOrThrow()
        waitForScreen(manager, second.sessionId, "__INDEPENDENT__", 30_000)
        assertTrue(manager.current(anchor).any { it.sessionId == second.sessionId && it.isConnected })

        manager.send(second.sessionId, "\u0004", appendNewline = false).getOrThrow()
        waitUntil(30_000) { manager.current(anchor).firstOrNull { it.sessionId == second.sessionId }
            ?.isConnected == false }
        assertNotNull(manager.current(anchor).firstOrNull { it.sessionId == second.sessionId })
    }

    private fun waitForScreen(
        manager: AgentProotTerminalSessionManager,
        sessionId: String,
        marker: String,
        timeoutMs: Long
    ): String {
        var latest = ""
        waitUntil(timeoutMs) {
            latest = transcript(manager, sessionId)
            latest.contains(marker)
        }
        return latest
    }

    private fun transcript(manager: AgentProotTerminalSessionManager, sessionId: String): String {
        var text = ""
        instrumentation.runOnMainSync {
            text = manager.terminalSession(sessionId)?.emulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }

    private fun visibleScreen(manager: AgentProotTerminalSessionManager, sessionId: String): String {
        var text = ""
        instrumentation.runOnMainSync {
            text = manager.terminalSession(sessionId)?.emulator?.let { emulator ->
                emulator.getSelectedText(0, 0, emulator.mColumns - 1, emulator.mRows - 1)
            }.orEmpty()
        }
        return text
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms")
    }
}
