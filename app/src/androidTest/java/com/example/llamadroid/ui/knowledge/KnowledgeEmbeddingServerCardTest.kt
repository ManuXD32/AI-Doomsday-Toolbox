package com.example.llamadroid.ui.knowledge

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.repository.KnowledgeEmbeddingServerStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeEmbeddingServerCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun missingModelPrerequisiteAndActionsRemainReachableAtLargeText() {
        setCard(
            status = KnowledgeEmbeddingServerStatus(),
            embeddingConfigReady = false
        )

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.responsive_kb_embedding_server_prerequisite)
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.kb_test_embedding)
        ).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.kb_open_logs)
        ).performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun startingStateExposesTheFullStopAction() {
        val stopLabel = composeRule.activity.getString(R.string.kb_stop_embedding_server)

        setCard(
            status = KnowledgeEmbeddingServerStatus(starting = true),
            embeddingConfigReady = true
        )
        composeRule.onNodeWithText(stopLabel)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun runningStateExposesTheFullStopAction() {
        val stopLabel = composeRule.activity.getString(R.string.kb_stop_embedding_server)

        setCard(
            status = KnowledgeEmbeddingServerStatus(running = true),
            embeddingConfigReady = true
        )
        composeRule.onNodeWithText(stopLabel)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun recoverableErrorMessageDoesNotHideRestartOrLogsActions() {
        val errorMessage = "qa-embedding-error"
        setCard(
            status = KnowledgeEmbeddingServerStatus(message = errorMessage),
            embeddingConfigReady = true
        )

        composeRule.onNodeWithText(errorMessage)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.kb_test_embedding)
        ).performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.kb_open_logs)
        ).performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    private fun setCard(
        status: KnowledgeEmbeddingServerStatus,
        embeddingConfigReady: Boolean
    ) {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 2f)
            ) {
                MaterialTheme {
                    LazyColumn(Modifier.size(width = 320.dp, height = 500.dp)) {
                        item {
                            KnowledgeEmbeddingServerCard(
                                status = status,
                                backend = SettingsRepository.KB_EMBED_BACKEND_LOCAL,
                                modelLabel = composeRule.activity.getString(
                                    R.string.kb_embedding_model_none
                                ),
                                embeddingConfigReady = embeddingConfigReady,
                                networkVisible = false,
                                chunkSize = 1_000,
                                embeddingBatchSize = 1_024,
                                embeddingThreads = 4,
                                onStartServer = {},
                                onStopServer = {},
                                onOpenLogs = {}
                            )
                        }
                    }
                }
            }
        }
    }
}
