package com.example.llamadroid.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedLlamaCommandEditorTest {
    @Test fun `default command and quoted custom argument paths are accepted`() {
        assertTrue(validSavedLlamaArguments("", ""))
        assertTrue(validSavedLlamaArguments("--no-warmup --chat-template-file '/models/chat template.jinja'", "{default_args}"))
    }

    @Test fun `unfinished quotes cannot be saved into a launch profile`() {
        assertFalse(validSavedLlamaArguments("--chat-template-file '/models/chat template.jinja", ""))
        assertFalse(validSavedLlamaArguments("", "{default_args} 'unfinished"))
    }
}
