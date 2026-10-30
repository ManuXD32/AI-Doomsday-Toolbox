package com.example.llamadroid.harness

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessDelegatedImageSettingsTest {
    @Test
    fun chatSettingsWinWhenEnabledWithModel() {
        assertEquals(HarnessImageSource.CHAT, decideHarnessImageSource(true, "model.onnx"))
    }

    @Test
    fun fallsBackToAgentWhenDisabledOrModelMissing() {
        assertEquals(HarnessImageSource.AGENT, decideHarnessImageSource(false, "model.onnx"))
        assertEquals(HarnessImageSource.AGENT, decideHarnessImageSource(true, null))
        assertEquals(HarnessImageSource.AGENT, decideHarnessImageSource(true, "  "))
    }
}
