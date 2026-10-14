package com.example.llamadroid.ui.ai.llama.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatNoticeLogicTest {
    @Test
    fun titleHelperNeedsSwitchAndModel() {
        assertTrue(titleHelperUnset(enabled = false, modelPath = "/m/a.gguf"))
        assertTrue(titleHelperUnset(enabled = true, modelPath = ""))
        assertTrue(titleHelperUnset(enabled = true, modelPath = "  "))
        assertFalse(titleHelperUnset(enabled = true, modelPath = "/m/a.gguf"))
    }

    @Test
    fun visionHelperNeedsSwitchAndServer() {
        assertTrue(visionHelperUnset(enabled = false, serverRowId = 4L))
        assertTrue(visionHelperUnset(enabled = true, serverRowId = 0L))
        assertFalse(visionHelperUnset(enabled = true, serverRowId = 4L))
    }

    @Test
    fun noticeIsNeededForAnyOfTheThreeParts() {
        assertFalse(NewChatNotice(false, false, false).needed)
        assertTrue(NewChatNotice(true, false, false).needed)
        assertTrue(NewChatNotice(false, true, false).needed)
        assertTrue(NewChatNotice(false, false, true).needed)
        assertEquals(true, NewChatNotice(true, false, true).anyHelperUnset)
        assertEquals(false, NewChatNotice(true, false, false).anyHelperUnset)
    }
}
