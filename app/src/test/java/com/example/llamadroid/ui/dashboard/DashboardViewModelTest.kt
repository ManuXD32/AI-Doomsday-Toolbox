package com.example.llamadroid.ui.dashboard

import com.example.llamadroid.service.ServerState
import com.example.llamadroid.ui.chat.llamaChatWebViewUrl
import org.junit.Assert.assertEquals
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
    fun chatWebViewUrlUsesConfiguredPort() {
        assertEquals("http://127.0.0.1:8099/", llamaChatWebViewUrl(8099))
    }
}
