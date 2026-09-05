package com.example.llamadroid.service

import com.example.llamadroid.data.db.AgentTodoEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkflowToolSupportTest {
    @Test
    fun todoResultReportsProgressAndNextActionableItem() {
        val todos = listOf(
            todo("done", "Completed setup", "COMPLETED", 0),
            todo("next", "Implement guidance", "IN_PROGRESS", 1),
            todo("later", "Run release checks", "PENDING", 2),
            todo("cancelled", "Obsolete step", "CANCELLED", 3)
        )

        val result = JSONObject(todoEntitiesJson(todos))

        assertEquals(33, result.getInt("progressPercent"))
        assertEquals(1, result.getInt("completedCount"))
        assertEquals(3, result.getInt("actionableCount"))
        assertEquals("next", result.getJSONObject("nextTodo").getString("id"))
    }

    @Test
    fun todoWriteParserAcceptsEveryAdvertisedWorkflowStatus() {
        val statuses = AgentTodoStatus.all.toList()
        val rawArguments = JSONObject().put(
            "todos",
            JSONArray().apply {
                statuses.forEachIndexed { index, status ->
                    put(
                        JSONObject()
                            .put("id", "todo-$index")
                            .put("text", "Step $index")
                            .put("status", status)
                    )
                }
            }
        ).toString()

        val parsed = parseTodoToolCall(
            conversationId = 7L,
            toolCall = OllamaService.ToolCall(
                name = "todo_write",
                arguments = emptyMap(),
                rawArgumentsJson = rawArguments
            )
        )

        assertEquals(statuses, parsed.map { it.status })
    }

    @Test
    fun todoResultSelectsReadyWorkflowItemBeforePendingItem() {
        val result = JSONObject(
            todoEntitiesJson(
                listOf(
                    todo("pending", "Wait for dependency", AgentTodoStatus.PENDING, 0),
                    todo("ready", "Implement page", AgentTodoStatus.READY, 1)
                )
            )
        )

        assertTrue(result.has("nextTodo"))
        assertEquals("ready", result.getJSONObject("nextTodo").getString("id"))
    }

    @Test
    fun todoResultKeepsVerifiedItemActionableUntilCompletion() {
        val result = JSONObject(
            todoEntitiesJson(
                listOf(
                    todo("ready", "Implement another page", AgentTodoStatus.READY, 0),
                    todo("verified", "Verified page", AgentTodoStatus.VERIFIED, 1)
                )
            )
        )

        assertEquals(0, result.getInt("completedCount"))
        assertEquals("verified", result.getJSONObject("nextTodo").getString("id"))
    }

    private fun todo(id: String, text: String, status: String, position: Int) = AgentTodoEntity(
        id = id,
        conversationId = 7L,
        text = text,
        status = status,
        priority = "NORMAL",
        position = position
    )
}
