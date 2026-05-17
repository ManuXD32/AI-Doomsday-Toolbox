package com.example.llamadroid.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenRouteTest {
    @Test
    fun `llama chat folder shortcut route includes folder id`() {
        assertEquals("llama_chat_list/folder/42", Screen.LlamaChatList.createFolderRoute(42))
    }

    @Test
    fun `llama chat list base route stays unchanged`() {
        assertEquals("llama_chat_list", Screen.LlamaChatList.route)
    }
}
