package com.example.llamadroid.service

import com.example.llamadroid.data.db.AgentTodoEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

internal fun parseQuestionToolCall(toolCall: OllamaService.ToolCall): QuestionSpec {
    val root = parseRawToolArguments(toolCall)
    val questions = root.optJSONArray("questions")
        ?: root.optString("questions_json").takeIf { it.isNotBlank() }?.let(::JSONArray)
        ?: throw IllegalArgumentException("question requires a questions array")
    val parsed = (0 until questions.length()).map { index ->
        val item = questions.optJSONObject(index)
            ?: throw IllegalArgumentException("Question ${index + 1} is not an object")
        val id = item.optString("id").trim().ifBlank { "question_${index + 1}" }
        val prompt = item.optString("prompt").trim()
        require(prompt.isNotBlank()) { "Question ${index + 1} needs a prompt" }
        val optionsJson = item.optJSONArray("options") ?: JSONArray()
        val options = (0 until optionsJson.length()).map { optionIndex ->
            val option = optionsJson.optJSONObject(optionIndex)
                ?: throw IllegalArgumentException("Question option is not an object")
            val label = option.optString("label").trim()
            require(label.isNotBlank()) { "Question options need labels" }
            QuestionOption(
                id = option.optString("id").trim().ifBlank { "option_${optionIndex + 1}" },
                label = label,
                description = option.optString("description").trim().takeIf(String::isNotBlank)
            )
        }
        QuestionItem(
            id = id.take(64),
            header = item.optString("header").trim().ifBlank { "Question" }.take(80),
            prompt = prompt.take(1_000),
            options = options,
            multiple = item.optBoolean("multiple", false),
            allowCustom = item.optBoolean("allow_custom", true)
        )
    }
    return QuestionSpec(parsed)
}

internal fun parseTodoToolCall(
    conversationId: Long,
    toolCall: OllamaService.ToolCall
): List<AgentTodoEntity> {
    val root = parseRawToolArguments(toolCall)
    val todos = root.optJSONArray("todos")
        ?: root.optString("todos_json").takeIf { it.isNotBlank() }?.let(::JSONArray)
        ?: throw IllegalArgumentException("todo_write requires a todos array")
    require(todos.length() <= 100) { "A project may contain at most 100 todos" }
    return (0 until todos.length()).map { index ->
        val item = todos.optJSONObject(index)
            ?: throw IllegalArgumentException("Todo ${index + 1} is not an object")
        val text = item.optString("text").trim()
        require(text.isNotBlank()) { "Todo ${index + 1} needs text" }
        val status = item.optString("status", "PENDING").uppercase(Locale.ROOT)
        require(status in AgentTodoStatus.all) {
            "Unsupported todo status: $status"
        }
        val priority = item.optString("priority", "NORMAL").uppercase(Locale.ROOT)
        require(priority in setOf("LOW", "NORMAL", "HIGH")) {
            "Unsupported todo priority: $priority"
        }
        AgentTodoEntity(
            id = item.optString("id").trim().ifBlank { UUID.randomUUID().toString() }.take(100),
            conversationId = conversationId,
            text = text.take(2_000),
            status = status,
            priority = priority,
            position = index
        )
    }
}

internal fun todoEntitiesJson(todos: List<AgentTodoEntity>): String {
    val actionable = todos.filterNot { it.status == AgentTodoStatus.CANCELLED }
    val completedCount = actionable.count { it.status == AgentTodoStatus.COMPLETED }
    val progressPercent = when {
        actionable.isNotEmpty() -> (completedCount * 100 / actionable.size).coerceIn(0, 100)
        todos.isNotEmpty() -> 100
        else -> 0
    }
    val nextTodo = AgentTodoStatus.actionPriority.firstNotNullOfOrNull { status ->
        todos.firstOrNull { it.status == status }
    }
    return stableJson(
        mapOf(
            "todos" to todos.map { todo ->
                mapOf(
                    "id" to todo.id,
                    "text" to todo.text,
                    "status" to todo.status,
                    "priority" to todo.priority,
                    "position" to todo.position
                )
            },
            "progressPercent" to progressPercent,
            "completedCount" to completedCount,
            "actionableCount" to actionable.size,
            "nextTodo" to nextTodo?.let { todo ->
                mapOf(
                    "id" to todo.id,
                    "text" to todo.text,
                    "status" to todo.status,
                    "priority" to todo.priority,
                    "position" to todo.position
                )
            }
        )
    )
}

private fun parseRawToolArguments(toolCall: OllamaService.ToolCall): JSONObject {
    toolCall.rawArgumentsJson?.takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { JSONObject(raw) }.getOrNull()?.let { return it }
    }
    return JSONObject(toolCall.arguments)
}
