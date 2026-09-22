package com.example.llamadroid.ui.agent.harness

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compact, large-text coverage for the custom-provider form through the production screen.
 * The Settings/provider surface owns the panel, so this exercises the parent settings scroll
 * container that keeps the full form reachable on a compact viewport.
 */
@RunWith(AndroidJUnit4::class)
class NativeHarnessProviderFormQaTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun englishProviderFormStaysReachableAndDispatchesExactRequest() =
        verifyProviderForm("en")

    @Test
    fun spanishProviderFormStaysReachableAndDispatchesExactRequest() =
        verifyProviderForm("es")

    @Test
    fun productionSettingsRouteStartsDraftDiscoveryWithoutFakeModelOrKey() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val actions = mutableListOf<NativeHarnessUiAction>()
        val state = NativeHarnessUiState(
            managementLoads = mapOf(
                HarnessManagementArea.SETTINGS to HarnessManagementLoadUi(loaded = true)
            ),
            provider = HarnessProviderUiState(
                canCreateProvider = true,
                customProviderProtocols = listOf("openai-completions"),
            ),
        )

        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 320.dp, height = 460.dp)) {
                    NativeHarnessScreen(
                        state = state,
                        onAction = { action ->
                            if (action !is NativeHarnessUiAction.LoadManagement) actions += action
                        },
                    )
                }
            }
        }

        openSecondaryDestination(resources.getString(R.string.harness_tab_settings))
        composeRule.onNodeWithTag("harness_settings_section_providers")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("harness_provider_add")
            .performScrollTo()
            .performClick()
        labeledField(resources.getString(R.string.harness_custom_provider_base_url))
            .performScrollTo()
            .performTextInput("http://127.0.0.1:8123/v1")
        composeRule.onNodeWithText(resources.getString(R.string.harness_discover_models))
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            val discovery = actions.single() as NativeHarnessUiAction.DiscoverCustomProviderModels
            assertEquals("http://127.0.0.1:8123/v1", discovery.request.baseUrl)
            assertEquals("", discovery.request.apiKey)
            assertEquals(emptyList<NativeHarnessCustomProviderModel>(), discovery.request.models)
        }
    }

    private fun verifyProviderForm(language: String) {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val localizedContext = localizedContext(baseContext, language)
        val resources = localizedContext.resources
        val actions = mutableListOf<NativeHarnessUiAction>()
        val state = NativeHarnessUiState(
            runtime = HarnessRuntimeUiState(canStart = true),
            provider = HarnessProviderUiState(
                canCreateProvider = true,
                customProviderProtocols = listOf("openai-completions")
            )
        )

        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalResources provides resources,
                LocalConfiguration provides resources.configuration,
                LocalDensity provides Density(baseDensity.density, fontScale = 2f)
            ) {
                MaterialTheme {
                    Box(Modifier.size(width = 320.dp, height = 460.dp)) {
                        NativeHarnessScreen(
                            state = state,
                            onAction = { action ->
                                if (action !is NativeHarnessUiAction.LoadManagement) {
                                    actions += action
                                }
                            }
                        )
                    }
                }
            }
        }

        // Providers are a focused destination; the add-provider form is
        // opened explicitly so the directory never composes every editor.
        openSecondaryDestination(resources.getString(R.string.harness_tab_settings))
        composeRule.onNodeWithTag("harness_settings_section_providers")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("harness_provider_add")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(resources.getString(R.string.harness_custom_provider_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("harness_custom_provider_advanced")
            .performScrollTo()
        composeRule.onNodeWithText(resources.getString(R.string.harness_custom_provider_advanced))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(resources.getString(R.string.harness_custom_provider_api_protocol))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            resources.getString(R.string.harness_custom_provider_api_openai_completions)
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(resources.getString(R.string.harness_custom_provider_add_model))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        labeledField(resources.getString(R.string.harness_custom_provider_route))
            .performScrollTo().performTextInput("qa-provider")
        labeledFields(resources.getString(R.string.harness_custom_provider_display_name))
            .assertCountEquals(2)
        labeledField(resources.getString(R.string.harness_custom_provider_display_name), occurrence = 0)
            .performScrollTo().performTextInput("QA provider")
        labeledField(resources.getString(R.string.harness_custom_provider_base_url))
            .performScrollTo().performTextInput("http://127.0.0.1:8123/v1")
        labeledField(resources.getString(R.string.harness_custom_provider_api_key))
            .performScrollTo().performTextInput("qa-secret")
        labeledField(resources.getString(R.string.harness_custom_provider_model_id))
            .performScrollTo().performTextInput("qa-model")
        labeledField(resources.getString(R.string.harness_custom_provider_model_name))
            .performScrollTo().performTextInput("QA model")
        labeledField(resources.getString(R.string.harness_custom_provider_context_window))
            .performScrollTo().performTextInput("4096")
        labeledField(resources.getString(R.string.harness_custom_provider_max_tokens))
            .performScrollTo().performTextInput("256")

        assertReachable(resources.getString(R.string.harness_custom_provider_route))
        assertReachable(resources.getString(R.string.harness_custom_provider_base_url))
        assertReachable(resources.getString(R.string.harness_custom_provider_api_key))
        assertReachable(resources.getString(R.string.harness_custom_provider_models))
        assertReachable(resources.getString(R.string.harness_custom_provider_model_id))
        assertReachable(resources.getString(R.string.harness_custom_provider_context_window))
        assertReachable(resources.getString(R.string.harness_custom_provider_max_tokens))
        composeRule.onAllNodesWithText(
            resources.getString(R.string.harness_custom_provider_display_name),
            useUnmergedTree = true
        ).assertCountEquals(2)

        composeRule.onNodeWithText(resources.getString(R.string.harness_custom_provider_create))
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        val expected = NativeHarnessCustomProviderRequest(
            route = "qa-provider",
            displayName = "QA provider",
            api = "openai-completions",
            baseUrl = "http://127.0.0.1:8123/v1",
            apiKey = "qa-secret",
            models = listOf(
                NativeHarnessCustomProviderModel(
                    id = "qa-model",
                    name = "QA model",
                    contextWindow = 4096,
                    maxTokens = 256
                )
            )
        )
        composeRule.runOnIdle {
            assertEquals(
                listOf(NativeHarnessUiAction.CreateCustomProvider(expected)),
                actions
            )
        }
    }

    private fun assertReachable(text: String) {
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun openSecondaryDestination(label: String) {
        composeRule.onNodeWithTag("harness_secondary_menu").performClick()
        composeRule.onNodeWithText(label).performClick()
    }

    private fun labeledFields(label: String) =
        composeRule.onAllNodes(
            hasSetTextAction() and hasAnyDescendant(hasText(label)),
            useUnmergedTree = true
        )

    private fun labeledField(label: String, occurrence: Int = 0) =
        labeledFields(label)[occurrence]

    private fun localizedContext(base: Context, language: String): Context =
        Configuration(base.resources.configuration).let { configuration ->
            configuration.setLocale(java.util.Locale.forLanguageTag(language))
            base.createConfigurationContext(configuration)
        }
}
