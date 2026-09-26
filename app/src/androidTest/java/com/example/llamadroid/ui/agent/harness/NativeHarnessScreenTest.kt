package com.example.llamadroid.ui.agent.harness

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeHarnessScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lifecycleHeaderDispatchesStartStopAndForceStop() {
        var state by mutableStateOf(
            NativeHarnessUiState(
                runtime = HarnessRuntimeUiState(canStart = true, canStop = false, canForceStop = false)
            )
        )
        val actions = mutableListOf<NativeHarnessUiAction>()

        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    NativeHarnessScreen(
                        state = state,
                        onAction = { action ->
                            actions += action
                            state = state.copy(runtime = when (action) {
                                NativeHarnessUiAction.StartRuntime -> HarnessRuntimeUiState(
                                    status = HarnessRuntimeStatus.RUNNING,
                                    canStart = false,
                                    canStop = true,
                                    canForceStop = true
                                )
                                NativeHarnessUiAction.StopRuntime -> HarnessRuntimeUiState(
                                    status = HarnessRuntimeStatus.STOPPING,
                                    canStart = false,
                                    canStop = false,
                                    canForceStop = true
                                )
                                NativeHarnessUiAction.ForceStopRuntime -> HarnessRuntimeUiState(
                                    status = HarnessRuntimeStatus.STOPPED,
                                    canStart = true,
                                    canStop = false,
                                    canForceStop = false
                                )
                                else -> state.runtime
                            })
                        }
                    )
                }
            }
        }

        openSecondaryDestination(R.string.harness_tab_runtime)
        composeRule.onNodeWithTag("harness_runtime_tab").assertIsDisplayed()
        composeRule.onNodeWithTag("harness_start").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("harness_stop").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("harness_force_stop").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    NativeHarnessUiAction.StartRuntime,
                    NativeHarnessUiAction.StopRuntime,
                    NativeHarnessUiAction.ForceStopRuntime
                ),
                actions
            )
        }
    }

    @Test
    fun harnessTerminalTabUsesDedicatedInsetViewportAndBackReturnsConversation() {
        val state = NativeHarnessUiState(
            selectedSessionId = "terminal-session",
            sessions = listOf(
                HarnessSessionUiState(
                    id = "terminal-session",
                    title = "Terminal session",
                    projectFolder = "qa-project",
                    backendLabel = "Local",
                    isSelected = true,
                )
            ),
            workspace = HarnessWorkspaceUiState(projectFolder = "qa-project")
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    NativeHarnessScreen(
                        state = state,
                        onAction = {},
                        terminalContent = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .testTag("qa_harness_terminal_slot")
                            )
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("harness_work_tab_terminal")
            .performScrollTo()
            .performClick()
        val viewport = composeRule.onNodeWithTag("harness_terminal_fullscreen_viewport")
        viewport.assertIsDisplayed()
        composeRule.onNodeWithTag("harness_terminal_fullscreen").assertIsDisplayed()
        composeRule.onNodeWithTag("qa_harness_terminal_slot").assertIsDisplayed()
        composeRule.onNodeWithTag("harness_body").assertDoesNotExist()
        composeRule.onNodeWithTag("harness_session_selector").assertDoesNotExist()
        composeRule.onNodeWithTag("harness_work_tabs").assertDoesNotExist()
        assertTrue(viewport.fetchSemanticsNode().size.height > 0)

        composeRule.onNodeWithContentDescription(defaultContextLabel(R.string.action_back)).performClick()
        composeRule.onNodeWithTag("harness_terminal_fullscreen").assertDoesNotExist()
        composeRule.onNodeWithTag("harness_work_tabs").assertIsDisplayed()
    }

    @Test
    fun runtimeTabKeepsDiagnosticsBoundedAndExposesRefreshAndCopy() {
        val state = NativeHarnessUiState(
            runtime = HarnessRuntimeUiState(
                diagnostics = (0..120).map { index ->
                    HarnessRuntimeDiagnosticUi(
                        id = "event-$index",
                        timestampMs = index.toLong(),
                        eventLabel = "event-$index",
                        stateLabel = "RUNNING"
                    )
                },
                diagnosticsLastUpdatedMs = 1L
            ),
            sessions = listOf(
                HarnessSessionUiState(
                    id = "session-1",
                    title = "QA session",
                    projectFolder = "qa",
                    backendLabel = "Local"
                )
            )
        )
        val actions = mutableListOf<NativeHarnessUiAction>()
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    NativeHarnessScreen(state = state, onAction = { actions += it })
                }
            }
        }

        openSecondaryDestination(R.string.harness_tab_runtime)
        composeRule.onNodeWithTag("harness_runtime_diagnostics")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("harness_session_session-1").assertDoesNotExist()
        composeRule.onNodeWithTag("harness_runtime_diagnostic_event-0").assertDoesNotExist()
        composeRule.onNodeWithTag("harness_runtime_diagnostic_event-120").assertExists()
        composeRule.onNodeWithTag("harness_runtime_diagnostic_event-21").assertExists()
        composeRule.onNodeWithTag("harness_runtime_refresh_diagnostics").performClick()
        composeRule.onNodeWithTag("harness_runtime_copy_diagnostics")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    NativeHarnessUiAction.RefreshRuntimeDiagnostics,
                    NativeHarnessUiAction.CopyRuntimeDiagnostics
                ),
                actions
            )
        }
    }

    @Test
    fun settingsMenuDrillsIntoProviderEditor() {
        val state = NativeHarnessUiState(
            managementLoads = mapOf(
                HarnessManagementArea.SETTINGS to HarnessManagementLoadUi(loaded = true)
            ),
            provider = HarnessProviderUiState(
                providers = listOf(
                    HarnessProviderOption(
                        id = "deepseek-official",
                        name = "DeepSeek",
                        models = listOf("deepseek-flash")
                    ),
                    HarnessProviderOption(id = "custom", name = "Custom")
                ),
                configs = listOf(
                    HarnessProviderConfigUi(
                        id = "deepseek-official",
                        name = "DeepSeek",
                        credentialReference = "deepseek.apiKey",
                        credentialWritable = true
                    )
                )
            )
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    NativeHarnessScreen(state = state, onAction = {})
                }
            }
        }

        openSecondaryDestination(R.string.harness_tab_settings)
        composeRule.onNodeWithTag("harness_settings_section_providers")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("harness_provider_directory").assertIsDisplayed()
        composeRule.onNodeWithTag("harness_provider_entry_deepseek-official")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("harness_provider_editor_deepseek-official").assertIsDisplayed()
        composeRule.onNodeWithTag("harness_provider_credential_deepseek-official")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("harness_provider_advanced_deepseek-official")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun nullProjectSelectionShowsProjectLandingAndCreateAction() {
        val project = HarnessProjectUi(
            id = "project-new",
            title = "New project",
            projectFolder = "new-project",
            backendLabel = "Local",
        )
        val actions = mutableListOf<NativeHarnessUiAction>()
        var createCount = 0
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    NativeHarnessScreen(
                        state = NativeHarnessUiState(
                            sessions = listOf(
                                HarnessSessionUiState(
                                    id = "stale-session",
                                    title = "Old session",
                                    projectFolder = "old-project",
                                    backendLabel = "Local",
                                )
                            )
                        ),
                        onAction = { actions += it },
                        projects = listOf(project),
                        projectNavigationEnabled = true,
                        initialProjectId = null,
                        onCreateProject = { createCount++ },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("harness_projects_landing").assertIsDisplayed()
        composeRule.onNodeWithTag("harness_project_create").performClick()
        composeRule.onNodeWithTag("harness_project_project-new").performClick()
        composeRule.runOnIdle { assertEquals(1, createCount) }
        composeRule.onNodeWithTag("harness_session_stale-session").assertDoesNotExist()
    }

    @Test
    fun loadingProjectListKeepsDeepLinkedProjectUntilRoomEmits() {
        val project = HarnessProjectUi(
            id = "project-loading",
            title = "Loading project",
            projectFolder = "loading-project",
            backendLabel = "Local",
        )
        var projects by mutableStateOf(emptyList<HarnessProjectUi>())
        var projectsLoaded by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    NativeHarnessScreen(
                        state = NativeHarnessUiState(),
                        onAction = {},
                        projects = projects,
                        projectNavigationEnabled = true,
                        projectsLoaded = projectsLoaded,
                        initialProjectId = project.id,
                    )
                }
            }
        }

        composeRule.runOnIdle {
            projects = listOf(project)
            projectsLoaded = true
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("harness_projects_landing").assertDoesNotExist()
    }

    @Test
    fun modelSettingsUsesParentLazyColumnWithoutNestedScrollCrash() {
        val rows = (0..18).joinToString(",") { index ->
            "{\"id\":\"model-$index\",\"name\":\"Model $index\",\"contextWindow\":32768}"
        }.let { "[$it]" }
        val state = NativeHarnessUiState(
            managementLoads = mapOf(
                HarnessManagementArea.SETTINGS to HarnessManagementLoadUi(loaded = true)
            ),
            provider = HarnessProviderUiState(
                providers = listOf(HarnessProviderOption(id = "local", name = "Local")),
                configs = listOf(
                    HarnessProviderConfigUi(
                        id = "local",
                        name = "Local",
                        fields = listOf(
                            HarnessSchemaField(
                                key = "llm-pi-ai.providers.local.models",
                                label = "Models",
                                type = HarnessSchemaFieldType.JSON,
                                value = rows,
                            )
                        ),
                    )
                ),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                    NativeHarnessScreen(state = state, onAction = {})
                }
            }
        }

        openSecondaryDestination(R.string.harness_tab_settings)
        composeRule.onNodeWithTag("harness_settings_section_model")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("harness_model_editor").assertIsDisplayed()
        composeRule.onNodeWithText("Model 18")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun composerKeepsOneNodeWhenViewportChanges() {
        var viewportHeight by mutableStateOf(640.dp)
        var state by mutableStateOf(
            NativeHarnessUiState(
                selectedSessionId = "session-1",
                sessions = listOf(
                    HarnessSessionUiState(
                        id = "session-1",
                        title = "QA session",
                        projectFolder = "qa",
                        backendLabel = "Local",
                        isSelected = true
                    )
                )
            )
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 360.dp, height = viewportHeight)) {
                    NativeHarnessScreen(
                        state = state,
                        onAction = { action ->
                            if (action is NativeHarnessUiAction.UpdateComposer) {
                                state = state.copy(composerText = action.text)
                            }
                        }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("harness_composer")
            .performClick()
            .performTextInput("draft")
        composeRule.runOnIdle { viewportHeight = 420.dp }
        composeRule.onAllNodesWithTag("harness_composer").fetchSemanticsNodes()
            .let { nodes -> assertEquals(1, nodes.size) }
        composeRule.onNodeWithTag("harness_composer").assertTextContains("draft")
    }

    @Test
    fun spanishLargeTextKeepsLifecycleLabelAndConversationScrollableOnSmallViewport() =
        verifyLargeTextOnSmallViewport("es")

    @Test
    fun englishLargeTextKeepsLifecycleLabelAndConversationScrollableOnSmallViewport() =
        verifyLargeTextOnSmallViewport("en")

    private fun verifyLargeTextOnSmallViewport(language: String) {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val localizedContext = localizedContext(baseContext, language)
        val transcript = (0..40).map { index ->
            HarnessTranscriptItem(
                id = "message-$index",
                role = HarnessTranscriptRole.ASSISTANT,
                text = "message-$index"
            )
        }
        val state = NativeHarnessUiState(
            runtime = HarnessRuntimeUiState(canStart = true),
            transcript = transcript
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
                LocalResources provides localizedContext.resources,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)
            ) {
                MaterialTheme {
                    Box(Modifier.size(width = 320.dp, height = 460.dp)) {
                        NativeHarnessScreen(state = state, onAction = {})
                    }
                }
            }
        }

        openSecondaryDestination(localizedContext.getString(R.string.harness_tab_runtime))
        composeRule.onNodeWithTag("harness_runtime_tab").assertIsDisplayed()
        composeRule.onNodeWithText(localizedContext.getString(R.string.harness_start))
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedContext.getString(R.string.harness_stop))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedContext.getString(R.string.harness_force_stop))
            .performScrollTo()
            .assertIsDisplayed()
        // Runtime is a secondary destination and intentionally has no work-tab row. Use the
        // production Back affordance to return to the conversation before checking its timeline.
        composeRule.onNodeWithContentDescription(localizedContext.getString(R.string.action_back))
            .performClick()
        composeRule.onNodeWithText(localizedContext.getString(R.string.harness_tab_conversation))
            .performScrollTo()
            .performClick()
        // The fixed rows are goal, usage, and the transcript title before message-0.
        val transcriptHeaderCount = 3
        composeRule.onNodeWithTag("harness_chat_timeline")
            .assertIsDisplayed()
            .performScrollToIndex(transcriptHeaderCount + transcript.lastIndex)
        composeRule.onNodeWithText("message-40").assertIsDisplayed()
    }

    private fun localizedContext(base: Context, language: String): Context = Configuration(base.resources.configuration)
        .let { configuration ->
            configuration.setLocale(java.util.Locale.forLanguageTag(language))
            base.createConfigurationContext(configuration)
        }

    private fun defaultContextLabel(resourceId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resourceId)

    private fun openSecondaryDestination(resourceId: Int) {
        openSecondaryDestination(defaultContextLabel(resourceId))
    }

    private fun openSecondaryDestination(label: String) {
        composeRule.onNodeWithTag("harness_secondary_menu").performClick()
        composeRule.onNodeWithText(label).performClick()
    }
}
