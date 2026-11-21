package com.example.llamadroid.worlds

import com.example.llamadroid.service.chat.NativeChatStep
import com.example.llamadroid.service.chat.NativeChatSteps
import com.example.llamadroid.service.chat.NativeChatStepsCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldWorkspaceTest {
    private fun tool(name: String, args: String, status: String = "ok") =
        NativeChatStep(type = "tool", round = 0, toolName = name, arguments = args, status = status)

    @Test
    fun `slug is ascii lowercase and never empty`() {
        assertEquals("elefantes-de-sabana", WorldWorkspace.slugFor("Elefantes de Sabana!"))
        assertEquals("world", WorldWorkspace.slugFor("???"))
    }

    @Test
    fun `edited html paths come from successful edit tools only`() {
        val steps = NativeChatSteps(
            steps = listOf(
                tool("write_workspace_file", """{"path":"worlds/a.html","content":"x"}"""),
                tool("replace_in_workspace_file", """{"path":"/worlds/a.html"}"""),
                tool("write_workspace_file", """{"path":"notes.txt"}"""),
                tool("edit_workspace_lines", """{"path":"worlds/err.html"}""", status = "error"),
                tool("apply_workspace_patch", "{\"patch\":\"*** Update File: worlds/b.html\\n@@\"}"),
                tool("read_workspace_file", """{"path":"worlds/c.html"}""")
            )
        )
        assertEquals(listOf("worlds/a.html", "worlds/b.html"), WorldWorkspace.editedHtmlPaths(NativeChatStepsCodec.encode(steps)))
    }
}
