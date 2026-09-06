package com.example.llamadroid.ui.models

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModelComponentPickerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun choosingComponentThenAutomaticStoresCanonicalRoleAndClearsHint() {
        val role = mutableStateOf("")
        rule.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                ModelComponentPicker(ModelFamily.LLM, role.value, { role.value = it })
            }
        }
        rule.onNodeWithTag("model_component_picker").performClick()
        rule.onNodeWithTag("model_component_option_lora").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("lora", role.value) }
        rule.onNodeWithTag("model_component_picker").performClick()
        rule.onNodeWithTag("model_component_option_auto").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("", role.value) }
    }

    @Test fun existingCustomRoleSurvivesFamilyChangesUntilUserPicksSupportedComponent() {
        val family = mutableStateOf(ModelFamily.LLM)
        val role = mutableStateOf("legacy_companion")
        rule.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                ModelComponentPicker(family.value, role.value, { role.value = it })
            }
        }
        rule.runOnIdle { family.value = ModelFamily.SD }
        rule.onNodeWithTag("model_component_picker").assertTextContains("legacy_companion", substring = true)
            .performClick()
        rule.onNodeWithTag("model_component_option_vae").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("vae", role.value) }
    }
}
