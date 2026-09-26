package com.example.llamadroid.service

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in, device-side end-to-end acceptance for a fresh Direct Local Debian project.
 *
 * The endpoint, model, project name, and prompt are instrumentation arguments. The test never
 * exports the screen, accessibility tree, chat, project files, or tool output; only assertion
 * metadata can leave the app process through the normal instrumentation result.
 */
@RunWith(AndroidJUnit4::class)
class AgentPhysicalFreshPomodoroAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database = AppDatabase.getDatabase(context)

    @Test
    fun createsFreshLocalDebianProjectAndLaunchesVerifiedPreview() {
        val arguments = InstrumentationRegistry.getArguments()
        val projectName = arguments.getString("qaProjectName")?.trim().orEmpty()
        val prompt = arguments.getString("qaPromptBase64")
            ?.let { encoded ->
                String(
                    Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP),
                    Charsets.UTF_8
                )
            }
            ?.trim()
            .orEmpty()
        val expectedServerUrl = arguments.getString("qaServerUrl")?.trim()?.trimEnd('/').orEmpty()
        val expectedModel = arguments.getString("qaModel")?.trim().orEmpty()
        assumeTrue("qaProjectName is required", projectName.isNotBlank())
        assumeTrue("qaPromptBase64 is required", prompt.isNotBlank())
        assumeTrue("qaServerUrl is required", expectedServerUrl.isNotBlank())
        assumeTrue("qaModel is required", expectedModel.isNotBlank())

        val settings = SettingsRepository(context)
        val dispatch = settings.resolveAgentSettingsForDispatch(role = "ORCHESTRATOR")
        assertTrue("Physical Direct-Agent acceptance requires Agent Auto mode", settings.autoMode.value)
        assertEquals(SettingsRepository.PDF_BACKEND_LLAMA_SWAP, dispatch.backend)
        assertEquals(expectedServerUrl, settings.agentLlamaSwapUrl.value.trim().trimEnd('/'))
        assertEquals(expectedModel, dispatch.model)
        assertEquals(16_384, dispatch.contextSize)
        assertEquals(4_096, dispatch.maxOutputTokens)
        assertFalse(dispatch.thinkingEnabled)

        val startedAt = System.currentTimeMillis()
        openAgentDashboard()
        clickRequired(setOf(context.getString(R.string.agent_new_project_btn), "New Project", "Nuevo Proyecto"))
        val nameField = waitForEditable(timeoutMillis = 30_000L)
        assertNotNull("New-project name field did not appear", nameField)
        assertTrue("Could not enter the QA project name", setText(nameField!!, projectName))
        clickRequired(setOf(context.getString(R.string.agent_proot_backend_label), "Local Debian", "Debian local"))
        clickRequired(setOf(context.getString(R.string.action_create), "Create", "Crear"))

        val conversation = waitForConversation(projectName, startedAt)
        assertEquals(AgentWorkspaceBackendType.LOCAL_PROOT.name, conversation.workspaceBackend)
        assertNotNull("Fresh Local Debian project must own an isolated environment", conversation.prootEnvironmentId)
        reportCheckpoint("project_created")

        val messageField = waitForMessageEditable(timeoutMillis = 60_000L)
        assertNotNull("Agent message editor did not appear", messageField)
        assertTrue("Could not enter the Pomodoro acceptance task", setText(messageField!!, prompt))
        clickRequired(setOf(context.getString(R.string.action_send), "Send", "Enviar"))
        reportCheckpoint("task_dispatched")

        val pendingPlan = waitForPendingPlan(conversation.id)
        assertEquals("AWAITING_APPROVAL", pendingPlan.state)
        reportCheckpoint("plan_ready")
        clickRequired(
            setOf(context.getString(R.string.action_approve), "Approve", "Aprobar"),
            timeoutMillis = 90_000L
        )
        waitUntil(180_000L, "The approved plan did not enter Build; ${metadataSummary(conversation.id)}") {
            runBlocking {
                database.agentWorkflowDao().getPendingPlanById(pendingPlan.id)?.let { durable ->
                    durable.state == "BUILDING" && durable.continuationEnqueued
                } == true
            }
        }
        reportCheckpoint("build_started")

        val coordinator = AgentProotRunCoordinator.get(context)
        var needsDirectionSince = 0L
        waitUntil(
            1_200_000L,
            "Fresh Gemma Local Debian run did not launch a preview; ${metadataSummary(conversation.id)}"
        ) {
            val hasPreview = coordinator.projectStates.value[conversation.id]?.previewUrl?.isNotBlank() == true
            if (!hasPreview) {
                val durableResume = runBlocking {
                    database.agentChatDao().getConversation(conversation.id)?.resumeState
                }
                if (durableResume == AgentService.RESUME_STATE_NEEDS_DIRECTION && !AgentService.isLoading.value) {
                    if (needsDirectionSince == 0L) needsDirectionSince = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - needsDirectionSince >= 3_000L) {
                        fail("Direct Agent paused before preview; ${metadataSummary(conversation.id)}")
                    }
                } else {
                    needsDirectionSince = 0L
                }
            }
            hasPreview
        }
        val previewUrl = requireNotNull(coordinator.projectStates.value[conversation.id]?.previewUrl)
        assertHttpPreview(previewUrl)
        reportCheckpoint("preview_live")

        var idleSince = 0L
        waitUntil(420_000L, "Direct Verify did not settle; ${metadataSummary(conversation.id)}") {
            if (AgentService.isLoading.value) {
                idleSince = 0L
                false
            } else {
                if (idleSince == 0L) idleSince = SystemClock.elapsedRealtime()
                SystemClock.elapsedRealtime() - idleSince >= 10_000L
            }
        }
        reportCheckpoint("verify_idle")
        // The model owns its Verify phase. Exercise the generated UI only after
        // its serialized preview observation/repair continuations have settled.
        assertPomodoroBehaviorInWebView(previewUrl)
        reportCheckpoint("webui_behavior_passed")

        val projectRoot = AgentLocalWorkspaceSupport.rootPathForProject(context, conversation.projectFolder)
        val manifestFile = File(projectRoot, ".adt/run.json")
        assertTrue("The completed project must contain .adt/run.json", manifestFile.isFile)
        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        assertEquals(1, manifest.getInt("version"))
        assertEquals("web", manifest.getString("runtime"))
        assertEquals("index.html", manifest.getString("entrypoint"))
        assertEquals("web", manifest.getString("ui"))

        val messages = runBlocking {
            database.agentChatDao().getMessagesForConversationSync(conversation.id)
        }
        assertEquals("Exactly one plan approval is allowed", 1, messages.count { it.isPlanApproved == true })
        assertEquals(
            "Auto mode must not create non-plan approval cards for run_project or writes",
            0,
            messages.count { it.needsApproval && !it.isPlan }
        )
        assertTrue("Direct acceptance must not delegate", messages.none { it.isDelegation })
        assertTrue("Direct acceptance must not invoke a custom specialist", messages.none { it.customAgentName != null })
        assertTrue(
            "The project exceeded the fifteen-response acceptance budget",
            messages.count { it.role == "assistant" && it.timestamp >= startedAt } <= 15
        )
        assertTrue(
            "The Direct run ended in Needs direction",
            AgentService.statusText.value != context.getString(R.string.agent_status_needs_direction)
        )
        assertTrue("A failure remained active after successful verification", AgentService.lastDirectFailure.value == null)
        reportCheckpoint("acceptance_complete")
    }

    private fun reportCheckpoint(name: String) {
        instrumentation.sendStatus(
            2,
            Bundle().apply { putString("stream", "QA_CHECKPOINT $name\n") }
        )
    }

    private fun openAgentDashboard() {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, "agent")
            }
        )
        instrumentation.waitForIdleSync()
    }

    private fun waitForConversation(projectName: String, startedAt: Long): AgentConversationEntity {
        var found: AgentConversationEntity? = null
        waitUntil(60_000L, "The Local Debian conversation was not created") {
            found = runBlocking {
                database.agentChatDao().getAllConversations().first().firstOrNull {
                    it.title == projectName && it.createdAt >= startedAt
                }
            }
            found != null
        }
        return requireNotNull(found)
    }

    private fun waitForPendingPlan(conversationId: Long) = run {
        var found: com.example.llamadroid.data.db.AgentPendingPlanEntity? = null
        waitUntil(420_000L, "Gemma did not produce one actionable plan; ${metadataSummary(conversationId)}") {
            found = runBlocking { database.agentWorkflowDao().getPendingPlan(conversationId) }
            found?.state == "AWAITING_APPROVAL"
        }
        requireNotNull(found)
    }

    private fun clickRequired(acceptedText: Set<String>, timeoutMillis: Long = 60_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var matchedNode = false
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val node = findNode(instrumentation.uiAutomation.rootInActiveWindow) { candidate ->
                candidate.packageName?.toString() == context.packageName &&
                    candidate.isEnabled &&
                    nodeLabels(candidate).any { it in acceptedText }
            }
            if (node != null) {
                matchedNode = true
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                if (clickable?.isEnabled == true &&
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    instrumentation.waitForIdleSync()
                    return
                }
                // Some Compose icon buttons expose a valid click semantic but replace the
                // semantics owner between lookup and ACTION_CLICK. Exercise the same visible
                // control by coordinates as a device-user fallback before reacquiring it.
                (clickable ?: node).takeIf { it.isEnabled }?.let { target ->
                    val bounds = Rect().also(target::getBoundsInScreen)
                    if (!bounds.isEmpty) {
                        val eventTime = SystemClock.uptimeMillis()
                        instrumentation.sendPointerSync(
                            MotionEvent.obtain(
                                eventTime,
                                eventTime,
                                MotionEvent.ACTION_DOWN,
                                bounds.exactCenterX(),
                                bounds.exactCenterY(),
                                0
                            )
                        )
                        instrumentation.sendPointerSync(
                            MotionEvent.obtain(
                                eventTime,
                                SystemClock.uptimeMillis(),
                                MotionEvent.ACTION_UP,
                                bounds.exactCenterX(),
                                bounds.exactCenterY(),
                                0
                            )
                        )
                        instrumentation.waitForIdleSync()
                        return
                    }
                }
            }
            // Compose may replace the semantics node after text entry or state publication.
            // Reacquire it instead of treating one stale-node rejection as a product failure.
            SystemClock.sleep(250L)
        }
        fail(
            if (matchedNode) {
                "App action stayed stale or disabled: ${acceptedText.joinToString()}"
            } else {
                "Missing app action ${acceptedText.joinToString()}"
            }
        )
    }

    private fun waitForEditable(timeoutMillis: Long): AccessibilityNodeInfo? = waitForNode(timeoutMillis) {
        it.packageName?.toString() == context.packageName &&
            it.isEnabled &&
            it.isEditable &&
            it.className?.toString() == "android.widget.EditText"
    }

    private fun waitForMessageEditable(timeoutMillis: Long): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val windowBounds = Rect().also { root?.getBoundsInScreen(it) }
            val editor = findNode(root) { candidate ->
                if (
                    candidate.packageName?.toString() != context.packageName ||
                    !candidate.isEnabled ||
                    !candidate.isEditable ||
                    candidate.className?.toString() != "android.widget.EditText"
                ) {
                    return@findNode false
                }
                val bounds = Rect().also(candidate::getBoundsInScreen)
                !windowBounds.isEmpty && bounds.centerY() >= windowBounds.top + windowBounds.height() * 3 / 4
            }
            if (editor != null) return editor
            SystemClock.sleep(250L)
        }
        return null
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean =
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
        )

    private fun waitForNode(
        timeoutMillis: Long,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            findNode(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(250L)
        }
        return null
    }

    private fun findNode(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            findNode(node.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun nodeLabels(node: AccessibilityNodeInfo): Sequence<String> = sequenceOf(
        node.text?.toString(),
        node.contentDescription?.toString(),
        node.hintText?.toString()
    ).filterNotNull()

    private fun assertHttpPreview(previewUrl: String) {
        val connection = URL(previewUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        try {
            assertTrue("Preview returned HTTP ${connection.responseCode}", connection.responseCode in 200..399)
        } finally {
            connection.disconnect()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun assertPomodoroBehaviorInWebView(previewUrl: String) {
        val loaded = CountDownLatch(1)
        val webViewRef = AtomicReference<WebView>()
        instrumentation.runOnMainSync {
            webViewRef.set(
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loaded.countDown()
                        }
                    }
                    loadUrl(previewUrl)
                }
            )
        }
        assertTrue("Pomodoro preview did not finish loading", loaded.await(30, TimeUnit.SECONDS))
        val webView = webViewRef.get()
        try {
            assertTrue(
                "Pomodoro controls or duration fields are missing",
                evaluateBoolean(
                    webView,
                    """
                    (() => ['start-btn','pause-btn','reset-btn','work-minutes','break-minutes',
                        'mode-label','countdown'].every(id => document.getElementById(id)))()
                    """.trimIndent()
                )
            )
            val initialCountdown = evaluateString(webView, "document.getElementById('countdown').textContent.trim()")
            evaluateBoolean(webView, "document.getElementById('start-btn').click(); true")
            SystemClock.sleep(2_200L)
            val runningCountdown = evaluateString(webView, "document.getElementById('countdown').textContent.trim()")
            assertNotEquals("Start did not advance the countdown", initialCountdown, runningCountdown)

            evaluateBoolean(webView, "document.getElementById('pause-btn').click(); true")
            val pausedCountdown = evaluateString(webView, "document.getElementById('countdown').textContent.trim()")
            SystemClock.sleep(1_700L)
            assertEquals(
                "Pause did not hold the countdown",
                pausedCountdown,
                evaluateString(webView, "document.getElementById('countdown').textContent.trim()")
            )

            evaluateBoolean(webView, "document.getElementById('reset-btn').click(); true")
            SystemClock.sleep(300L)
            assertEquals(
                "Reset did not restore the configured work duration",
                initialCountdown,
                evaluateString(webView, "document.getElementById('countdown').textContent.trim()")
            )

            val initialMode = evaluateString(webView, "document.getElementById('mode-label').textContent.trim()")
            assertTrue(
                "Could not configure one-minute work/break acceptance durations",
                evaluateBoolean(
                    webView,
                    """
                    (() => {
                      for (const id of ['work-minutes','break-minutes']) {
                        const input = document.getElementById(id);
                        input.value = '1';
                        input.dispatchEvent(new Event('input', { bubbles: true }));
                        input.dispatchEvent(new Event('change', { bubbles: true }));
                      }
                      document.getElementById('reset-btn').click();
                      return true;
                    })()
                    """.trimIndent()
                )
            )
            evaluateBoolean(webView, "document.getElementById('start-btn').click(); true")
            waitUntil(75_000L, "Pomodoro did not transition from work to break") {
                evaluateString(webView, "document.getElementById('mode-label').textContent.trim()") != initialMode
            }
            evaluateBoolean(webView, "document.getElementById('pause-btn').click(); true")
            evaluateBoolean(webView, "document.getElementById('reset-btn').click(); true")
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }

    private fun evaluateBoolean(webView: WebView, script: String): Boolean =
        evaluateJavascript(webView, script) == "true"

    private fun evaluateString(webView: WebView, script: String): String {
        val raw = evaluateJavascript(webView, script)
        return JSONObject("{\"value\":$raw}").optString("value")
    }

    private fun evaluateJavascript(webView: WebView, script: String): String {
        val completed = CountDownLatch(1)
        val result = AtomicReference("null")
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) { value ->
                result.set(value ?: "null")
                completed.countDown()
            }
        }
        assertTrue("WebView JavaScript evaluation timed out", completed.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun waitUntil(timeoutMillis: Long, failureMessage: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(500L)
        }
        assertTrue(failureMessage, condition())
    }

    private fun metadataSummary(conversationId: Long): String = runBlocking {
        database.agentChatDao().getRecentProjectEventsSync(conversationId, limit = 12)
            .joinToString(prefix = "recentEvents=[", postfix = "]", separator = ";") { event ->
                listOfNotNull(event.eventType, event.toolName, event.phase, event.status, event.errorClass)
                    .joinToString("/")
            }
    }
}
