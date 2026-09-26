package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessUiModelTest {
    @Test
    fun transcriptProjectionKeepsTheNewestBoundedWindow() {
        val transcript = (0 until 900).map { index ->
            HarnessTranscriptItem(
                id = "message-$index",
                role = HarnessTranscriptRole.ASSISTANT,
                text = "message-$index"
            )
        }

        val projection = boundedHarnessTranscript(transcript)

        assertEquals(HARNESS_MAX_RENDERED_TRANSCRIPT_ITEMS, projection.size)
        assertEquals("message-300", projection.first().text)
        assertEquals("message-899", projection.last().text)
    }

    @Test
    fun transcriptProjectionKeepsShortHistoryIntact() {
        val transcript = listOf(
            HarnessTranscriptItem("one", HarnessTranscriptRole.USER, "first"),
            HarnessTranscriptItem("two", HarnessTranscriptRole.ASSISTANT, "second")
        )

        assertEquals(transcript, boundedHarnessTranscript(transcript))
    }

    @Test
    fun messagePreviewIsBoundedWithVisibleTruncationMarker() {
        val preview = boundedHarnessMessage("x".repeat(HARNESS_MAX_RENDERED_MESSAGE_CHARACTERS + 10))

        assertEquals(HARNESS_MAX_RENDERED_MESSAGE_CHARACTERS + 1, preview.length)
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun actionContractCarriesNativeControlsWithoutEngineTypes() {
        val actions = listOf<NativeHarnessUiAction>(
            NativeHarnessUiAction.StartRuntime,
            NativeHarnessUiAction.StopRuntime,
            NativeHarnessUiAction.ForceStopRuntime,
            NativeHarnessUiAction.SelectSession("session-1"),
            NativeHarnessUiAction.SelectProvider("litert"),
            NativeHarnessUiAction.UpdateSchemaField("approval_mode", "ask"),
            NativeHarnessUiAction.InstallPlugin("official-tools"),
            NativeHarnessUiAction.SetSubagentConcurrency(2),
            NativeHarnessUiAction.UpdateTrajectorySearch("tool")
        )

        assertEquals(9, actions.size)
        assertEquals("litert", (actions[4] as NativeHarnessUiAction.SelectProvider).providerId)
        assertEquals(
            "approval_mode",
            (actions[5] as NativeHarnessUiAction.UpdateSchemaField).key
        )
    }

    @Test
    fun projectIntegrationsAndCredentialsStayExplicitUiActions() {
        val integration = NativeHarnessUiAction.OpenProjectIntegration
        val credential = NativeHarnessUiAction.SetProviderCredential("deepseek", "secret")

        assertTrue(integration === NativeHarnessUiAction.OpenProjectIntegration)
        assertEquals("deepseek", credential.providerId)
        assertEquals("secret", credential.value)
    }

    @Test
    fun authoredFailureCodesResolveToLocalizedResources() {
        assertEquals(
            R.string.harness_notice_provider_not_declared,
            localizedHarnessNoticeMessage("PROVIDER_NOT_DECLARED")
        )
        assertEquals(
            R.string.harness_notice_provider_discovery_unauthorized,
            localizedHarnessNoticeMessage("PROVIDER_DISCOVERY_UNAUTHORIZED")
        )
        assertEquals(
            R.string.harness_notice_provider_discovery_network,
            localizedHarnessNoticeMessage("PROVIDER_DISCOVERY_NETWORK")
        )
        assertEquals(
            R.string.harness_notice_provider_discovery_unsupported,
            localizedHarnessNoticeMessage("PROVIDER_DISCOVERY_UNSUPPORTED")
        )
        assertEquals(
            R.string.harness_notice_provider_discovery_invalid_response,
            localizedHarnessNoticeMessage("PROVIDER_DISCOVERY_INVALID_RESPONSE")
        )
        assertEquals(
            R.string.harness_notice_provider_discovery_too_large,
            localizedHarnessNoticeMessage("PROVIDER_DISCOVERY_TOO_LARGE")
        )
        assertEquals(
            R.string.harness_notice_provider_discovery_http_failed,
            localizedHarnessNoticeMessage("PROVIDER_DISCOVERY_HTTP_FAILED")
        )
        assertEquals(
            R.string.harness_gateway_operation_failed,
            localizedHarnessNoticeMessage("gateway/bad-request")
        )
        assertEquals(
            R.string.harness_litert_0985_worker_interrupted,
            localizedHarnessNoticeMessage("LITERT_WORKER_CRASHED")
        )
    }

    @Test
    fun separateSessionsMayPointAtTheSameProjectFolder() {
        val state = NativeHarnessUiState(
            sessions = listOf(
                HarnessSessionUiState("session-a", "Plan", "shared", "Local"),
                HarnessSessionUiState("session-b", "Build", "shared", "Local")
            )
        )

        assertEquals(2, state.sessions.size)
        assertEquals(state.sessions[0].projectFolder, state.sessions[1].projectFolder)
        assertTrue(state.sessions.map { it.id }.distinct().size == 2)
    }
}
