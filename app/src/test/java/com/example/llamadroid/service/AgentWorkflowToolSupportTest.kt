package com.example.llamadroid.service

import com.example.llamadroid.data.db.AgentTodoEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    private fun todo(id: String, text: String, status: String, position: Int) = AgentTodoEntity(
        id = id,
        conversationId = 7L,
        text = text,
        status = status,
        priority = "NORMAL",
        position = position
    )
}
