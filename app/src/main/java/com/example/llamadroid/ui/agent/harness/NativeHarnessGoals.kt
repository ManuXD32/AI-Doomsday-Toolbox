package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Keeps the latest goal projection for each live session. */
internal class NativeHarnessGoalStore {
    private val values = mutableMapOf<String, HarnessGoalUi?>()

    fun get(sessionId: String): HarnessGoalUi? = values[sessionId]

    fun applyProjection(sessionId: String, value: JsonElement): HarnessGoalUi? {
        val previous = values[sessionId]
        val next = parseHarnessGoal(value)?.copy(activation = previous?.activation ?: "disarmed")
        values[sessionId] = next
        return next
    }

    fun applyView(sessionId: String, value: JsonElement): HarnessGoalUi? {
        val next = parseHarnessGoal(value)
        values[sessionId] = next
        return next
    }

    fun applyActivation(
        sessionId: String,
        goalId: String?,
        revision: Int?,
        activation: String?
    ): HarnessGoalUi? {
        val current = values[sessionId]
        val next = if (goalId == null) {
            null
        } else if (current != null && current.id == goalId && (revision == null || current.revision == revision)) {
            current.copy(activation = activation ?: "disarmed")
        } else {
            current
        }
        values[sessionId] = next
        return next
    }
}

/** Native mutations for the official seven-method GoalService surface. */
internal class NativeHarnessGoalActions(
    private val clientProvider: suspend () -> HarnessClient?,
    private val selectedSessionProvider: () -> String?,
    private val selectedGoalProvider: () -> HarnessGoalUi?,
    private val updateGoal: suspend (sessionId: String, goal: HarnessGoalUi?) -> Unit,
    private val reportFailure: suspend (code: String, message: String) -> Unit
) {
    suspend fun dispatch(action: NativeHarnessUiAction): Boolean = when (action) {
        is NativeHarnessUiAction.CreateGoal -> {
            create(action)
            true
        }
        is NativeHarnessUiAction.EditGoal -> {
            edit(action)
            true
        }
        NativeHarnessUiAction.PauseGoal -> {
            transition("pause")
            true
        }
        NativeHarnessUiAction.ResumeGoal -> {
            transition("resume")
            true
        }
        NativeHarnessUiAction.CompleteGoal -> {
            transition("complete")
            true
        }
        NativeHarnessUiAction.ClearGoal -> {
            transition("clear")
            true
        }
        else -> false
    }

    suspend fun refresh(): Boolean {
        val sessionId = selectedSessionProvider() ?: return false
        val client = clientProvider() ?: return false
        return when (val result = client.call(
            "goals",
            "get",
            buildJsonObject { put("agentId", sessionId) },
            HarnessCallPolicy.SafeRead
        )) {
            is HarnessRpcResult.Failure -> {
                reportFailure(result.error.code, result.error.message)
                false
            }
            is HarnessRpcResult.Success -> {
                updateGoal(sessionId, parseHarnessGoal(result.value))
                true
            }
        }
    }

    private suspend fun create(action: NativeHarnessUiAction.CreateGoal) {
        val sessionId = selectedSessionProvider() ?: return
        val objective = action.objective.trim()
        if (objective.isBlank()) {
            reportFailure("GOAL_INVALID_OBJECTIVE", "Enter a goal objective before creating it")
            return
        }
        if (action.maxGoalRounds != null && action.maxGoalRounds < 1) {
            reportFailure("GOAL_INVALID_MAX_ROUNDS", "The goal round limit must be positive")
            return
        }
        callGoal(
            method = "create",
            args = buildJsonObject {
                put("agentId", sessionId)
                putJsonObject("request") {
                    put("objective", objective)
                    action.maxGoalRounds?.let { put("maxGoalRounds", it) }
                }
            }
        )
    }

    private suspend fun edit(action: NativeHarnessUiAction.EditGoal) {
        val sessionId = selectedSessionProvider() ?: return
        val goal = selectedGoalProvider() ?: return reportFailure("GOAL_NOT_FOUND", "There is no current Harness goal")
        val objective = action.objective?.trim()?.takeIf(String::isNotBlank)
        val maxRounds = action.maxGoalRounds
        if (objective == null && maxRounds == null) {
            reportFailure("GOAL_INVALID_EDIT", "Change the objective or round limit before saving")
            return
        }
        if (maxRounds != null && maxRounds < 1) {
            reportFailure("GOAL_INVALID_MAX_ROUNDS", "The goal round limit must be positive")
            return
        }
        callGoal(
            method = "edit",
            args = buildJsonObject {
                put("agentId", sessionId)
                putRef(goal)
                putJsonObject("request") {
                    objective?.let { put("objective", it) }
                    maxRounds?.let { put("maxGoalRounds", it) }
                }
            }
        )
    }

    private suspend fun transition(method: String) {
        val sessionId = selectedSessionProvider() ?: return
        val goal = selectedGoalProvider() ?: return reportFailure("GOAL_NOT_FOUND", "There is no current Harness goal")
        callGoal(
            method = method,
            args = buildJsonObject {
                put("agentId", sessionId)
                putRef(goal)
            }
        )
    }

    private suspend fun callGoal(method: String, args: JsonObject) {
        val client = clientProvider() ?: return
        when (val result = client.call("goals", method, args, HarnessCallPolicy.NoRetry)) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> refresh()
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putRef(goal: HarnessGoalUi) {
        putJsonObject("ref") {
            put("id", goal.id)
            put("revision", goal.revision)
        }
    }
}

/** Parse either a durable projection value or a GoalService view. */
internal fun parseHarnessGoal(value: JsonElement): HarnessGoalUi? {
    if (value is JsonNull) return null
    val root = value as? JsonObject ?: return null
    val snapshot = root.objectValue("goal") ?: root
    val id = snapshot.string("id") ?: return null
    val revision = snapshot.int("revision") ?: return null
    val objective = snapshot.string("objective") ?: return null
    val phase = snapshot.string("phase") ?: return null
    val rounds = root.int("roundsStarted") ?: snapshot.int("roundsStarted") ?: 0
    val maxRounds = snapshot.int("maxGoalRounds") ?: root.int("maxGoalRounds") ?: return null
    val blockedReason = snapshot.objectValue("blockedReason")?.string("message")
    return HarnessGoalUi(
        id = id,
        revision = revision,
        objective = objective,
        phase = phase,
        roundsStarted = rounds,
        maxGoalRounds = maxRounds,
        blockedReason = blockedReason,
        activation = root.string("activation") ?: "disarmed"
    )
}
