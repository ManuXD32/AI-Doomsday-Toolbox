package com.example.llamadroid.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun `onnx model route can open the catalog while preserving the base route`() {
        assertEquals("onnx_models", Screen.OnnxModels.createRoute())
        assertEquals("onnx_models?tab=catalog", Screen.OnnxModels.createRoute("catalog"))
        assertThrows(IllegalArgumentException::class.java) {
            Screen.OnnxModels.createRoute("unsupported")
        }
    }
}
