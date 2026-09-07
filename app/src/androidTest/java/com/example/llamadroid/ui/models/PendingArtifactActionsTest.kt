package com.example.llamadroid.ui.models

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import com.example.llamadroid.R
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises the actual card without a download service or production database. */
class PendingArtifactActionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun cancellationRemainsReachableWhileTheQueueIsBusy() {
        var cancels = 0
        rule.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                PendingArtifactCard(
                    artifact = PendingModelArtifactEntity(id = "fixture", filename = "nested-model.gguf",
                        stagingPath = "/fixture/model.gguf", status = "STAGED"),
                    busy = true, onInspect = { error("Inspect must stay disabled") },
                    onPromote = { error("An active download is not promotable") },
                    onCancel = { cancels++ }, onRetry = { error("An active download is not retryable") }
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.action_cancel)).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, cancels) }
        rule.onNodeWithText(rule.activity.getString(R.string.action_retry)).assertDoesNotExist()
    }

    @Test fun cancelledArtifactOffersRetryWithoutInspectionOrPromotion() {
        var retries = 0
        rule.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                PendingArtifactCard(
                    artifact = PendingModelArtifactEntity(id = "fixture", filename = "cancelled-model.gguf",
                        stagingPath = "/fixture/model.gguf", status = "CANCELLED"),
                    busy = false, onInspect = { error("Cancelled work must not be inspected") },
                    onPromote = { error("Cancelled work must not be promoted") },
                    onCancel = { error("Already cancelled") }, onRetry = { retries++ }
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.action_retry)).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, retries) }
        rule.onNodeWithText(rule.activity.getString(R.string.model_library_inspect)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.model_library_promote)).assertDoesNotExist()
    }

    @Test fun validatedUnknownArtifactExposesDiscardAction() {
        var discards = 0
        rule.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                PendingArtifactCard(
                    artifact = PendingModelArtifactEntity(
                        id = "fixture-validated",
                        filename = "validated-unknown.gguf",
                        stagingPath = "/fixture/validated-unknown.gguf",
                        status = "VALIDATED"
                    ),
                    busy = false,
                    onInspect = { error("Validated work must not be re-inspected") },
                    onPromote = {},
                    onCancel = { error("Validated work is not active") },
                    onRetry = { error("Validated work is not retryable") },
                    onDiscard = { discards++ }
                )
            }
        }
        rule.onNodeWithText(
            rule.activity.getString(R.string.model_library_discard_artifact)
        ).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, discards) }
    }
}
