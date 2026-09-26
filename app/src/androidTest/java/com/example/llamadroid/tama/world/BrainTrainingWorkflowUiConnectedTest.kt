package com.example.llamadroid.tama.world

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.R
import com.example.llamadroid.tama.world.ui.BrainCheckpointUi
import com.example.llamadroid.tama.world.ui.BrainEvaluationUi
import com.example.llamadroid.tama.world.ui.BrainRunState
import com.example.llamadroid.tama.world.ui.BrainTrainingCallbacks
import com.example.llamadroid.tama.world.ui.BrainTrainingMetricsUi
import com.example.llamadroid.tama.world.ui.BrainTrainingScreen
import com.example.llamadroid.tama.world.ui.BrainTrainingUiState
import com.example.llamadroid.tama.world.ui.SafeAutonomyUi
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrainTrainingWorkflowUiConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun savedCandidateKeepsEvaluateAndDecisionActionsVisible() {
        var saved = 0
        var evaluatedId = ""
        var adoptedId = ""
        var kept = 0
        val candidateId = "candidate-1"
        setContent(
            state = workflowState(candidateId),
            callbacks = callbacks(
                onSave = { saved += 1 },
                onEvaluate = { evaluatedId = it },
                onAdopt = { adoptedId = it },
                onKeep = { kept += 1 }
            )
        )

        scrollLazyToTag("brain_active_policy")
        scrollLazyToTag("brain_candidate_workflow")
        scrollLazyToTag("brain_save_checkpoint")
        compose.onNodeWithTag("brain_save_checkpoint").performClick()
        scrollLazyToTag("brain_evaluate_candidate")
        compose.onNodeWithTag("brain_evaluate_candidate").performClick()
        scrollLazyToTag("brain_evaluation")
        scrollLazyToTag("brain_adopt_candidate")
        compose.onNodeWithTag("brain_adopt_candidate").performClick()
        scrollLazyToTag("brain_keep_current")
        compose.onNodeWithTag("brain_keep_current").performClick()

        assertEquals(1, saved)
        assertEquals(candidateId, evaluatedId)
        assertEquals(candidateId, adoptedId)
        assertEquals(1, kept)
    }

    @Test
    fun optionalDetailsStartCollapsedAndExpandThroughOneLazyOwner() {
        val context = compose.activity
        setContent(state = workflowState(candidateId = null), callbacks = callbacks())

        scrollLazyToTag("brain_training_details")
        compose.onNodeWithText(context.getString(R.string.tama_world_brain_metrics_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.tama_world_brain_workflow_details_title)).performClick()
        scrollLazyToText(context.getString(R.string.tama_world_brain_metrics_title))
    }

    @Test
    fun noCandidateKeepsEvaluateActionDisabled() {
        setContent(state = workflowState(candidateId = null), callbacks = callbacks())
        scrollLazyToTag("brain_evaluate_candidate")
        compose.onNodeWithTag("brain_evaluate_candidate").assertIsNotEnabled()
    }

    @Test
    fun activeEvaluationShowsProgressAndDisablesCandidateActions() {
        setContent(state = workflowState(candidateId = "candidate-1").copy(isEvaluating = true), callbacks = callbacks())

        scrollLazyToTag("brain_evaluating")
        scrollLazyToTag("brain_save_checkpoint")
        compose.onNodeWithTag("brain_save_checkpoint").assertIsNotEnabled()
        scrollLazyToTag("brain_evaluate_candidate")
        compose.onNodeWithTag("brain_evaluate_candidate").assertIsNotEnabled()
    }

    private fun scrollLazyToTag(tag: String) {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .onFirst().performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun scrollLazyToText(text: String) {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .onFirst().performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun setContent(
        state: BrainTrainingUiState,
        callbacks: BrainTrainingCallbacks,
        language: String = "en"
    ) {
        val configuration = compose.activity.resources.configuration.let { base ->
            android.content.res.Configuration(base).apply { setLocale(Locale.forLanguageTag(language)) }
        }
        val context = compose.activity.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources,
                LocalDensity provides Density(context.resources.displayMetrics.density, 1f)
            ) {
                LlamaDroidTheme(dynamicColor = false) {
                    Box(Modifier.size(360.dp, 720.dp)) {
                        BrainTrainingScreen(state = state, callbacks = callbacks)
                    }
                }
            }
        }
    }

    private fun callbacks(
        onSave: () -> Unit = {},
        onEvaluate: (String) -> Unit = {},
        onAdopt: (String) -> Unit = {},
        onKeep: () -> Unit = {}
    ) = BrainTrainingCallbacks(
        onBack = {},
        onStart = {},
        onPause = {},
        onResume = {},
        onSpeedChanged = {},
        onProfileSelected = {},
        onCheckpointSaved = onSave,
        onEvaluate = onEvaluate,
        onAdoptCandidate = onAdopt,
        onKeepCurrent = onKeep,
        onRestoreCheckpoint = {},
        onSafeAutonomyChanged = {},
        onOpenJournal = {}
    )

    private fun workflowState(candidateId: String?): BrainTrainingUiState {
        val checkpoint = candidateId?.let {
            BrainCheckpointUi(
                id = it,
                displayName = "Practice checkpoint",
                modelHash = "sha256",
                trainingEpisodes = 12,
                curriculum = "Level 1",
                rewardConfiguration = "saved rules",
                metricSummary = "episodes=12",
                createdAt = "today",
                isCandidate = true
            )
        }
        return BrainTrainingUiState(
            currentBrainName = "Active brain",
            currentBrainVersion = 2,
            trainingAgeEpisodes = 24,
            adopted = true,
            currentCurriculum = "Level 1",
            currentCurriculumId = 1,
            metrics = BrainTrainingMetricsUi(24, 50f, 1f, 12f, 60f, 5f, 0f, 20f),
            liveEnvironment = null,
            runState = BrainRunState.IDLE,
            speedMultiplier = 1,
            charts = emptyList(),
            checkpoints = listOfNotNull(checkpoint),
            evaluation = candidateId?.let {
                BrainEvaluationUi(
                    candidateCheckpointId = it,
                    candidateName = "candidate-v1",
                    currentSuccessPercent = 40f,
                    candidateSuccessPercent = 55f,
                    currentObjectiveSteps = 12f,
                    candidateObjectiveSteps = 10f,
                    currentStuckPercent = 8f,
                    candidateStuckPercent = 4f,
                    currentNeedFailurePercent = 3f,
                    candidateNeedFailurePercent = 2f,
                    unseenSeedCount = 4,
                    complete = true,
                    candidateWins = true,
                    currentPolicyIsAdopted = true
                )
            },
            safeAutonomy = SafeAutonomyUi()
        )
    }
}
