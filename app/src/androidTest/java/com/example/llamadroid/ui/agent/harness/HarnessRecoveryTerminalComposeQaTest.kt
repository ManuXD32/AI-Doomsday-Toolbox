package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.R
import com.example.llamadroid.harness.HarnessTerminalScreen
import com.example.llamadroid.harness.HarnessTermuxTransportAdapter
import com.example.llamadroid.service.HarnessRecoveryTerminalPhase
import com.example.llamadroid.service.HarnessRecoveryTerminalSessionManager
import com.example.llamadroid.service.HarnessRecoveryTerminalState
import com.example.llamadroid.service.HarnessRecoveryTerminalStatus
import com.example.llamadroid.service.WorkspaceTerminalUiState
import com.example.llamadroid.ui.agent.AgentProotTerminalViewport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production-screen Compose coverage for the recovery lifecycle fix and both full-screen PTY
 * viewports. The live entry test is opt-in because it starts the API36 x86 recovery rootfs.
 */
@RunWith(AndroidJUnit4::class)
class HarnessRecoveryTerminalComposeQaTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recoveryViewportHidesSecondaryActionsWhenKeyboardOwnsInsets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = HarnessRecoveryTerminalSessionManager.get(context)
        composeRule.setContent {
            MaterialTheme {
                HarnessRecoveryTerminalViewport(
                    padding = PaddingValues(0.dp),
                    state = HarnessRecoveryTerminalState(
                        status = HarnessRecoveryTerminalStatus.CONNECTED,
                        phase = HarnessRecoveryTerminalPhase.CONNECTED,
                        sessionId = "compose-recovery",
                        processId = 42
                    ),
                    manager = manager,
                    host = null,
                    context = context,
                    keyboardVisible = true
                )
            }
        }

        composeRule.onNodeWithTag("harness_recovery_terminal_viewport").assertIsDisplayed()
        composeRule.onNodeWithTag("harness_recovery_terminal_actions").assertDoesNotExist()
        composeRule.onNodeWithTag("harness_recovery_terminal_special_keys").assertIsDisplayed()
    }

    @Test
    fun projectTerminalUsesSharedKeyBarWhenNativeImeIsVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selected = WorkspaceTerminalUiState(
            workspaceRoot = "/workspace",
            sessionId = "compose-project-native",
            displayName = "Debian",
            backend = com.example.llamadroid.service.AgentWorkspaceBackendType.LOCAL_PROOT,
            isConnected = true,
            openedAt = 1L,
            lastActivityAt = 1L,
        )
        val transport = HarnessTermuxTransportAdapter(onWrite = {})
        try {
            composeRule.setContent {
                MaterialTheme {
                    HarnessTerminalScreen(
                        workspaceRoot = selected.workspaceRoot,
                        state = selected,
                        sessions = listOf(selected),
                        transport = transport,
                        onSend = {},
                        onSpecialKey = {},
                        onInterrupt = {},
                        onReconnect = {},
                        onClear = {},
                        onStop = {},
                        onNewSession = {},
                        onSelectSession = {},
                    )
                }
            }
            // AndroidView reports false before the IME is shown; drive the same signal used by
            // the platform callback so this test covers the production keyboard branch without
            // depending on an emulator keyboard implementation.
            composeRule.runOnIdle { transport.setImeVisible(true) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("harness_terminal_controls").assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.harness_terminal_key_shift))
                .assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.agent_proot_terminal_new_session))
                .assertDoesNotExist()
        } finally {
            transport.close()
        }
    }

    @Test
    fun projectViewportKeepsWeightedTerminalAndSpecialKeysReachableWithKeyboard() {
        val selected = WorkspaceTerminalUiState(
            workspaceRoot = "/workspace",
            sessionId = "compose-project",
            displayName = "Debian",
            backend = com.example.llamadroid.service.AgentWorkspaceBackendType.LOCAL_PROOT,
            isConnected = true,
            openedAt = 1L,
            lastActivityAt = 1L
        )
        composeRule.setContent {
            MaterialTheme {
                AgentProotTerminalViewport(
                    innerPadding = PaddingValues(0.dp),
                    keyboardVisible = true,
                    sessions = listOf(selected),
                    selectedSession = selected,
                    isOpening = false,
                    terminalHost = null,
                    screenError = "hidden while keyboard is visible",
                    controlEnabled = false,
                    altEnabled = false,
                    onSelectSession = {},
                    onNewSession = {},
                    onControl = {},
                    onAlt = {},
                    onSequence = {}
                )
            }
        }

        val viewport = composeRule.onNodeWithTag("agent_proot_terminal_viewport")
        viewport.assertIsDisplayed()
        composeRule.onNodeWithTag("agent_proot_terminal_extra_keys").assertIsDisplayed()
        assertTrue(viewport.fetchSemanticsNode().size.height > 0)
    }

    @Test
    fun productionRecoveryEntrySurvivesHostCreationRecomposition() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass recovery_compose_qa=true for live Compose recovery coverage", args.getString("recovery_compose_qa") == "true")
        assumeTrue("Use the explicit x86_64 Harness QA carrier", BuildConfig.HARNESS_QA_X86)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = HarnessRecoveryTerminalSessionManager.get(context)
        runBlocking { manager.close() }
        try {
            composeRule.setContent {
                MaterialTheme {
                    HarnessRecoveryTerminalScreen(
                        onBack = {},
                        manager = manager
                    )
                }
            }
            // Cold carrier extraction and first PRoot launch can exceed the normal UI test
            // window. The manager still has bounded process/guest readiness phases; this outer
            // wait covers only the one-time environment preparation on a fresh emulator.
            composeRule.waitUntil(240_000L) {
                check(manager.state.value.status !in setOf(
                    HarnessRecoveryTerminalStatus.FAILED,
                )) {
                    "Recovery terminal failed during live Compose QA: ${manager.state.value}"
                }
                manager.state.value.status == HarnessRecoveryTerminalStatus.CONNECTED
            }
            composeRule.onNodeWithTag("harness_recovery_terminal_viewport").assertIsDisplayed()

            // The original DisposableEffect(manager, host) closed the session exactly when the
            // host changed from null to non-null. Keep the production route composed long enough
            // to observe that recomposition boundary and assert the PTY remains connected.
            repeat(10) {
                composeRule.waitForIdle()
                composeRule.runOnIdle {
                    assertEquals(HarnessRecoveryTerminalStatus.CONNECTED, manager.state.value.status)
                }
                Thread.sleep(100L)
            }
        } finally {
            runBlocking { manager.close() }
        }
    }
}
