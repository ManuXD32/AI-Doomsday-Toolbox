package com.example.llamadroid.ui.models

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.llamadroid.R
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real editor controls with isolated values; never registers or modifies a model. */
class ModelPromotionDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun unchangedLiteRtEditorPreservesSavedRuntimeOptions() {
        var result: JSONObject? = null
        var promotedFamily: ModelFamily? = null
        val artifact = PendingModelArtifactEntity(id = "fixture", bundleId = "fixture-bundle",
            filename = "nested-long-model-name.litertlm", stagingPath = "/fixture",
            requestedFamily = "LITERT", detectedFamily = "LITERT")
        rule.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                PromoteArtifactDialog(artifact, false, {}, { family, _, _, json ->
                    promotedFamily = family; result = JSONObject(json)
                }, savedMetadataJson = """{"liteRtBackend":"gpu","supportsVision":true,"supportsAudio":true,"supportsEmbedding":false,"supportsCpu":false,"supportsGpu":true,"supportsNpu":false,"maxContextTokens":8192}""")
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.model_library_promote_action)).performClick()
        rule.runOnIdle {
            assertEquals(ModelFamily.LITERT, promotedFamily)
            val metadata = requireNotNull(result)
            assertEquals("gpu", metadata.getString("liteRtBackend"))
            assertTrue(metadata.getBoolean("supportsVision"))
            assertTrue(metadata.getBoolean("supportsAudio"))
            assertFalse(metadata.getBoolean("supportsEmbedding"))
            assertFalse(metadata.getBoolean("supportsCpu"))
            assertEquals(8192, metadata.getInt("maxContextTokens"))
        }
    }

    @Test fun liteRtUserEditOverridesOnlyTheSelectedOption() {
        var result: JSONObject? = null
        val artifact = PendingModelArtifactEntity(id = "fixture", filename = "model.litertlm",
            stagingPath = "/fixture", detectedFamily = "LITERT")
        rule.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                PromoteArtifactDialog(artifact, false, {}, { _, _, _, json -> result = JSONObject(json) },
                    savedMetadataJson = """{"liteRtBackend":"cpu","supportsVision":true,"supportsAudio":true,"maxContextTokens":4096}""")
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.litert_models_modality_vision))
            .performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.model_library_promote_action)).performClick()
        rule.runOnIdle {
            val metadata = requireNotNull(result)
            assertFalse(metadata.getBoolean("supportsVision"))
            assertTrue(metadata.getBoolean("supportsAudio"))
            assertEquals("cpu", metadata.getString("liteRtBackend"))
            assertEquals(4096, metadata.getInt("maxContextTokens"))
        }
    }
}
