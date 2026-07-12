package com.example.llamadroid.ui.ai.llama

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlamaChatModelsTest {

    @Test
    fun `export payload round trips media paths`() {
        val payload = LlamaChatExportPayload(
            title = "Example",
            systemPrompt = "Be helpful",
            apiParams = """{"temperature":0.7}""",
            messages = listOf(
                LlamaChatSerializedMessage(
                    role = "user",
                    content = "hello",
                    imagePath = "/tmp/image.png",
                    audioPath = "/tmp/audio.m4a"
                ),
                LlamaChatSerializedMessage(
                    role = "assistant",
                    content = "hi"
                )
            )
        )

        val json = Gson().toJson(payload)
        val parsed = Gson().fromJson(json, LlamaChatExportPayload::class.java)

        assertEquals("Example", parsed.title)
        assertEquals("Be helpful", parsed.systemPrompt)
        assertEquals("""{"temperature":0.7}""", parsed.apiParams)
        assertEquals(2, parsed.messages.size)
        assertEquals("/tmp/image.png", parsed.messages[0].imagePath)
        assertEquals("/tmp/audio.m4a", parsed.messages[0].audioPath)
        assertNull(parsed.messages[1].imagePath)
        assertNull(parsed.messages[1].audioPath)
    }

    @Test
    fun `display model name strips storage paths`() {
        assertEquals(
            "qwen2.5-7b-instruct-q4_k_m.gguf",
            displayLlamaModelName("/storage/emulated/0/Android/data/com.example/files/models/qwen2.5-7b-instruct-q4_k_m.gguf")
        )
        assertEquals("llama3.2:latest", displayLlamaModelName("llama3.2:latest"))
    }

}
