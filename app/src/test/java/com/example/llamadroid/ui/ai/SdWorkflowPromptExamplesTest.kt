package com.example.llamadroid.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class SdWorkflowPromptExamplesTest {
    @Test
    fun `unknown detector uses generic inpaint guidance`() {
        assertEquals(
            SdWorkflowPromptContext.INPAINT,
            sdWorkflowPromptContextForDetector("custom-detector.bin")
        )
    }
}
