package com.example.llamadroid.ui.models

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.library.InstalledModelAsset
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual member and URL controls; callbacks only capture a request, never persist user data. */
class ModelSourceAttachmentDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun directoryLinkTargetsTheChosenMemberWithoutChangingTheRuntimeKey() {
        val directory = File(rule.activity.cacheDir, "attachment-dialog-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(directory, "model.onnx").writeText("fixture graph")
            val tokenizer = File(directory, "tokenizer.json").apply { writeText("fixture tokenizer") }
            val model = ModelEntity(filename = "fixture-package", path = directory.path,
                sizeBytes = 28, repoId = "fixture", type = ModelType.ONNX_IMAGE_GEN)
            val asset = InstalledModelAsset.fromModel(model, ModelFamily.ONNX, "image_generation")
            var saved: ModelSourceAttachmentRequest? = null
            rule.setContent {
                LlamaDroidTheme(dynamicColor = false) {
                    ModelSourceAttachmentDialog(asset, emptyList(), emptyList(), {}, { saved = it })
                }
            }
            rule.waitUntil(5_000) { rule.onAllNodesWithText("tokenizer.json").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("tokenizer.json").performScrollTo().performClick()
            rule.onNodeWithText(rule.activity.getString(R.string.model_source_url_label))
                .performScrollTo().performTextInput("https://example.com/tokenizer.json")
            rule.onNodeWithText(rule.activity.getString(R.string.model_source_save_link)).performClick()
            rule.runOnIdle {
                val result = requireNotNull(saved)
                assertEquals(asset.stableId, result.asset.stableId)
                assertEquals(tokenizer.canonicalPath, File(result.asset.path).canonicalPath)
                assertEquals("https://example.com/tokenizer.json", result.newSource!!.url)
                assertEquals("fixture tokenizer", tokenizer.readText())
            }
        } finally {
            directory.deleteRecursively() // Only this test's uniquely named temporary files.
        }
    }

    @Test fun repositoryPageCannotBeSavedAsAFileSource() {
        val model = ModelEntity(filename = "fixture.gguf", path = "/fixture.gguf", sizeBytes = 0,
            repoId = "fixture", type = ModelType.LLM)
        var saves = 0
        rule.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                ModelSourceAttachmentDialog(InstalledModelAsset.fromModel(model, ModelFamily.LLM, "llm"),
                    emptyList(), emptyList(), {}, { saves++ })
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.model_source_url_label))
            .performScrollTo().performTextInput("https://huggingface.co/example/repository")
        rule.onNodeWithText(rule.activity.getString(R.string.model_source_save_link)).performClick()
        rule.runOnIdle { assertEquals(0, saves) }
        rule.onNodeWithText(rule.activity.getString(R.string.model_source_invalid_link)).assertExists()
    }
}
