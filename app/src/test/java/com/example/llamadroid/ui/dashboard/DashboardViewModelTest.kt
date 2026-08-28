package com.example.llamadroid.ui.dashboard

import com.example.llamadroid.service.ServerState
import com.example.llamadroid.ui.chat.llamaChatWebViewUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardViewModelTest {
    @Test
    fun dashboardHealthPortUsesConfiguredPortWhenServerIsNotRunning() {
        assertEquals(8099, dashboardHealthPort(8099, ServerState.Stopped))
    }

    @Test
    fun dashboardHealthPortKeepsRunningServerPort() {
        assertEquals(8101, dashboardHealthPort(8099, ServerState.Running(8101)))
    }

    @Test
    fun healthyDashboardProbeNeverPromotesStoppedRuntimeToRunning() {
        assertEquals(
            ServerState.Stopped,
            dashboardStateAfterHealthProbe(ServerState.Stopped, healthCode = 200)
        )
    }

    @Test
    fun loadingServerCannotBeStartedAgainAndCanBeCancelled() {
        assertFalse(dashboardCanStartServer(ServerState.Loading(0f, "Loading model")))
        assertTrue(dashboardCanStopServer(ServerState.Loading(0f, "Loading model")))
    }

    @Test
    fun failedServerOffersBothRetryAndRecovery() {
        assertTrue(dashboardCanRecoverServer(ServerState.Error("native process interrupted")))
        assertTrue(dashboardCanStartServer(ServerState.Error("native process interrupted")))
        assertFalse(dashboardCanRecoverServer(ServerState.Stopped))
    }

    @Test
    fun chatWebViewUrlUsesConfiguredPort() {
        assertEquals("http://127.0.0.1:8099/", llamaChatWebViewUrl(8099))
    }
}
