package com.example.llamadroid.ui.settings.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSettingsSectionTest {
    @Test
    fun routeRoundTripsEverySection() {
        ChatSettingsSection.entries.forEach { section ->
            assertEquals(section, ChatSettingsSection.fromArg(section.arg))
            assertEquals("settings_chat?section=${section.arg}", ChatSettingsSection.route(section))
        }
    }

    @Test
    fun unknownOrMissingArgMeansNoScroll() {
        assertNull(ChatSettingsSection.fromArg(null))
        assertNull(ChatSettingsSection.fromArg(""))
        assertNull(ChatSettingsSection.fromArg("nope"))
        assertEquals("settings_chat", ChatSettingsSection.route())
    }

    @Test
    fun hasEightSectionsWithUniqueArgs() {
        assertEquals(8, ChatSettingsSection.entries.size)
        assertEquals(8, ChatSettingsSection.entries.map { it.arg }.toSet().size)
    }
}
