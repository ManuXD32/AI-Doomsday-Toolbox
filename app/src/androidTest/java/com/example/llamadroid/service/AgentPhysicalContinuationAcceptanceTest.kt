package com.example.llamadroid.service

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in physical-device continuation acceptance.
 *
 * The driver deliberately does not export screenshots, accessibility trees, chat messages, or
 * project files. It opens the requested saved conversation, clicks only the localized Continue
 * action, and observes bounded runtime/file metadata until the repaired project launches.
 */
@RunWith(AndroidJUnit4::class)
class AgentPhysicalContinuationAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun continueRepairsManifestAndLaunchesPreviewWithoutExportingContent() {
        val arguments = InstrumentationRegistry.getArguments()
        val requestedConversationId = arguments.getString("qaConversationId")?.toLongOrNull()
        val requestedProjectName = arguments.getString("qaProjectName")?.trim().orEmpty()
        val requestedProjectFolder = arguments.getString("qaProjectFolder")?.trim().orEmpty()
        val expectMissingManifest = arguments.getString("qaExpectMissingManifest").toBoolean()
        val allowRetry = arguments.getString("qaAllowRetry").toBoolean()
        val allowApproval = arguments.getString("qaAllowApproval").toBoolean()
        val conversation = runBlocking {
            val dao = AppDatabase.getDatabase(context).agentChatDao()
            requestedConversationId?.takeIf { it > 0L }?.let { dao.getConversation(it) }
                ?: dao.getAllConversations().first().firstOrNull {
                    requestedProjectName.isNotBlank() && it.title == requestedProjectName
                }
        }
        assumeTrue(
            "qaConversationId or qaProjectName must select a saved QA conversation",
            conversation != null
        )
        val anchor = requireNotNull(conversation).id
        val projectFolder = requestedProjectFolder.ifBlank { conversation.projectFolder }
        assumeTrue("The selected QA conversation must have an app-private workspace", projectFolder.isNotBlank())
        assertTrue(
            "Physical Direct-Agent acceptance requires Agent Auto mode to be enabled",
            SettingsRepository(context).autoMode.value
        )

        val projectRoot = AgentLocalWorkspaceSupport.rootPathForProject(context, projectFolder)
        val runManifest = File(projectRoot, ".adt/run.json")
        if (expectMissingManifest) {
            assertFalse("The repair fixture must start without a committed run manifest", runManifest.exists())
        }

        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, "agent?conversationId=$anchor")
            }
        )
        instrumentation.waitForIdleSync()

        val continueLabels = mutableSetOf(
            context.getString(R.string.action_continue),
            "Continue",
            "Continuar"
        )
        if (allowRetry) {
            continueLabels += context.getString(R.string.action_retry)
            continueLabels += "Retry"
            continueLabels += "Reintentar"
        }
        if (allowApproval) {
            continueLabels += context.getString(R.string.action_allow)
            continueLabels += "Allow"
            continueLabels += "Permitir"
        }
        val recoveryNode = waitForClickableText(continueLabels, timeoutMillis = 60_000L)
        assertNotNull(
            "The interrupted project must expose the requested explicit recovery action; " +
                metadataOnlyFailureSummary(anchor),
            recoveryNode
        )
        clickNodeRequired(recoveryNode!!)

        waitUntil(300_000L, "Gemma did not commit the repaired run manifest") {
            runManifest.isFile
        }
        val manifest = JSONObject(runManifest.readText(Charsets.UTF_8))
        assertEquals(1, manifest.getInt("version"))
        assertEquals("web", manifest.getString("runtime"))
        assertEquals("index.html", manifest.getString("entrypoint"))
        assertEquals("web", manifest.getString("ui"))

        val coordinator = AgentProotRunCoordinator.get(context)
        waitUntil(
            900_000L,
            "The continued Local Debian project did not launch a preview; " +
                metadataOnlyFailureSummary(anchor)
        ) {
            val hasPreview = coordinator.projectStates.value[anchor]?.previewUrl?.isNotBlank() == true
            val needsDirection = runBlocking {
                AppDatabase.getDatabase(context).agentChatDao().getConversation(anchor)?.resumeState ==
                    AgentService.RESUME_STATE_NEEDS_DIRECTION
            }
            if (!hasPreview && needsDirection && !AgentService.isLoading.value) {
                throw AssertionError(
                    "The continued Direct run paused before launching a preview; " +
                        metadataOnlyFailureSummary(anchor)
                )
            }
            hasPreview
        }

        // Allow queued Verify turns to drain. A stable idle interval distinguishes completion
        // from the brief gaps between serialized continuations.
        var idleSince = 0L
        waitUntil(300_000L, "The continued Direct run did not settle after preview launch") {
            if (AgentService.isLoading.value) {
                idleSince = 0L
                false
            } else {
                if (idleSince == 0L) idleSince = SystemClock.elapsedRealtime()
                SystemClock.elapsedRealtime() - idleSince >= 10_000L
            }
        }
        assertTrue(
            "The Direct run paused in Needs direction after launching the preview",
            AgentService.statusText.value != context.getString(R.string.agent_status_needs_direction)
        )
        assertTrue(
            "A repaired manifest failure remained active after the successful receipt",
            AgentService.lastDirectFailure.value == null
        )
    }

    private fun waitForClickableText(
        acceptedText: Set<String>,
        timeoutMillis: Long
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            findClickableText(root, acceptedText)?.let {
                return it
            }
            // The recovery reason is intentionally bounded and scrollable on a phone. In a
            // verbose locale the full-width Continue action can start just below that viewport.
            // Scroll only app-owned accessibility containers, then reacquire the semantics tree.
            // Compose can report ACTION_SCROLL_FORWARD as handled without moving this
            // bounded recovery viewport. Follow the semantic action with the same physical
            // swipe a user performs so a false-positive accessibility result cannot starve
            // the Continue action below the fold.
            scrollForwardAppContainers(root)
            swipeForwardAppContainer(root)
            SystemClock.sleep(250L)
        }
        return null
    }

    private fun findClickableText(
        node: AccessibilityNodeInfo?,
        acceptedText: Set<String>
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (
            node.packageName?.toString() == context.packageName &&
            node.text?.toString() in acceptedText
        ) {
            var candidate: AccessibilityNodeInfo? = node
            while (candidate != null) {
                if (candidate.isClickable && candidate.isEnabled) return candidate
                candidate = candidate.parent
            }
        }
        for (index in 0 until node.childCount) {
            findClickableText(node.getChild(index), acceptedText)?.let { return it }
        }
        return null
    }

    private fun scrollForwardAppContainers(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (
            node.packageName?.toString() == context.packageName &&
            node.isScrollable &&
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        ) {
            return true
        }
        for (index in 0 until node.childCount) {
            if (scrollForwardAppContainers(node.getChild(index))) return true
        }
        return false
    }

    private fun swipeForwardAppContainer(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.packageName?.toString() == context.packageName && node.isScrollable) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.width() > 0 && bounds.height() >= 160) {
                val x = bounds.exactCenterX()
                val startY = bounds.bottom - bounds.height() * 0.18f
                val endY = bounds.top + bounds.height() * 0.18f
                // Use Android's input service for the swipe. On the physical Samsung QA
                // device Instrumentation.sendPointerSync delivered the individual events but
                // Compose's nested scroll recognizer did not claim the complete gesture.
                instrumentation.uiAutomation.executeShellCommand(
                    "input swipe ${x.toInt()} ${startY.toInt()} ${x.toInt()} ${endY.toInt()} 350"
                ).close()
                SystemClock.sleep(450L)
                return true
            }
        }
        for (index in 0 until node.childCount) {
            if (swipeForwardAppContainer(node.getChild(index))) return true
        }
        return false
    }

    private fun clickNodeRequired(node: AccessibilityNodeInfo) {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val bounds = Rect().also(node::getBoundsInScreen)
        assertFalse("The recovery action has no visible bounds", bounds.isEmpty)
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(
            MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                0
            )
        )
        instrumentation.sendPointerSync(
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                0
            )
        )
    }

    private fun waitUntil(
        timeoutMillis: Long,
        failureMessage: String,
        condition: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(500L)
        }
        assertTrue(failureMessage, condition())
    }

    private fun metadataOnlyFailureSummary(conversationId: Long): String = runBlocking {
        AppDatabase.getDatabase(context).agentChatDao()
            .getRecentProjectEventsSync(conversationId, limit = 12)
            .joinToString(prefix = "recentEvents=[", postfix = "]", separator = ";") { event ->
                listOfNotNull(
                    event.eventType,
                    event.toolName,
                    event.phase,
                    event.status,
                    event.errorClass
                ).joinToString("/")
            }
    }
}
