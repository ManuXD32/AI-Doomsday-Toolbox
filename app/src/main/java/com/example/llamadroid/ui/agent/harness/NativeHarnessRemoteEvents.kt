package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A question item projected from a Gateway user-questions waterfall. */
internal data class NativeHarnessRemoteQuestion(
    val sessionId: String,
    val uiId: String,
    val answerId: String,
    val eventId: String,
    val title: String,
    val prompt: String,
    val detail: String?,
    val options: List<String>,
    val allowsFreeText: Boolean,
    val multiSelect: Boolean,
)

/** An approval request projected from the official approval waterfall. */
internal data class NativeHarnessRemoteApproval(
    val sessionId: String,
    val uiId: String,
    val eventId: String,
    val title: String,
    val summary: String,
    val detail: String?,
)

/** A plan-review question, which is the documented user-questions intent. */
internal data class NativeHarnessRemotePlan(
    val sessionId: String,
    val uiId: String,
    val answerId: String,
    val eventId: String,
    val title: String,
    val summary: String,
    val steps: List<String>,
    val approveLabel: String,
    val rejectLabel: String,
)

/** Wire shape required by the user-questions client for option and custom answers. */
internal fun buildHarnessQuestionAnswer(
    answerId: String,
    selected: List<String>,
    custom: String?
): JsonObject = buildJsonObject {
    put("id", answerId)
    putJsonArray("selected") { selected.forEach { add(JsonPrimitive(it)) } }
    custom?.takeIf(String::isNotBlank)?.let { put("custom", it) }
}

/** Plan-review "Request changes" is a rejected waterfall, not a decline answer. */
internal fun buildHarnessPlanCancellationOutcome(): JsonObject = buildJsonObject {
    put("kind", "rejected")
    put("error", buildJsonObject {
        put("name", "UserQuestionError")
        put("code", "ASK_CANCELLED")
        put("message", "the user cancelled ask_user_question")
    })
}

/**
 * Owns the authenticated `$events` stream and its one-shot waterfall replies.
 * The native controller supplies presentation callbacks; this class keeps
 * event/client identities out of the Compose model and posts only the official
 * `$events/result` envelope.
 */
internal class NativeHarnessRemoteEvents(
    private val scope: CoroutineScope,
    private val clientProvider: () -> HarnessClient?,
    private val onReady: suspend (removedUiIds: List<String>) -> Unit,
    private val onQuestion: suspend (NativeHarnessRemoteQuestion) -> Unit,
    private val onApproval: suspend (NativeHarnessRemoteApproval) -> Unit,
    private val onPlan: suspend (NativeHarnessRemotePlan) -> Unit,
    private val onCancelled: suspend (removedUiIds: List<String>) -> Unit,
    private val onEmit: suspend (event: String, args: JsonArray) -> Unit = { _, _ -> },
    private val onFailure: suspend (Throwable) -> Unit,
) : AutoCloseable {
    private val lock = Mutex()
    private val questions = mutableMapOf<String, QuestionRoute>()
    private val approvals = mutableMapOf<String, ApprovalRoute>()
    private val plans = mutableMapOf<String, PlanRoute>()
    private var streamJob: Job? = null
    private var attachedClient: HarnessClient? = null
    private var clientId: String? = null

    private data class QuestionRoute(
        val clientId: String,
        val eventId: String,
        val questions: List<QuestionAnswer>,
        val answers: MutableMap<String, QuestionValue> = mutableMapOf(),
    )

    private data class QuestionAnswer(
        val uiId: String,
        val answerId: String,
    )

    private data class QuestionValue(
        val selected: List<String>,
        val custom: String?
    )

    private data class ApprovalRoute(val clientId: String, val eventId: String)

    private data class PlanRoute(
        val clientId: String,
        val eventId: String,
        val answerId: String,
        val approveLabel: String,
    )

    /** Replaces the stream when the runtime rotates the authenticated client. */
    fun attach(client: HarnessClient?) {
        if (client === attachedClient) return
        streamJob?.cancel()
        streamJob = null
        attachedClient = client
        clientId = null
        if (client == null) return
        streamJob = scope.launch {
            try {
                client.remoteEvents(HarnessStreamPolicy.Default).collect(::applyFrame)
            } catch (error: Throwable) {
                if (error !is kotlinx.coroutines.CancellationException) onFailure(error)
            }
        }
    }

    /** Reopen the event carrier after a recoverable parser or transport failure. */
    fun restart() {
        val client = attachedClient ?: return
        attachedClient = null
        attach(client)
    }

    suspend fun answerQuestion(
        uiId: String,
        answer: String,
        custom: Boolean = false,
        selected: List<String> = emptyList()
    ): HarnessRpcResult {
        val route = lock.withLock {
            val route = questions.values.firstOrNull { candidate ->
                candidate.questions.any { it.uiId == uiId }
            } ?: return@withLock null
            val item = route.questions.first { it.uiId == uiId }
            route.answers[item.answerId] = if (custom) {
                QuestionValue(selected = selected, custom = answer.trim().takeIf(String::isNotEmpty))
            } else {
                QuestionValue(selected = selected.ifEmpty { listOf(answer) }, custom = null)
            }
            route to (route.answers.size == route.questions.size)
        } ?: return missingEvent("QUESTION_EVENT_NOT_FOUND")
        val (questionRoute, complete) = route
        if (!complete) {
            return HarnessRpcResult.Success(buildJsonObject { put("pending", true) })
        }
        val answers = questionRoute.questions.map { item ->
            val value = questionRoute.answers[item.answerId] ?: QuestionValue(emptyList(), null)
            buildHarnessQuestionAnswer(item.answerId, value.selected, value.custom)
        }
        return settle(
            questionRoute.clientId,
            questionRoute.eventId,
            buildJsonObject {
                put("kind", "result")
                put("value", buildJsonObject { put("answers", JsonArray(answers)) })
            },
            remove = { lock.withLock { removeQuestionRoute(questionRoute.eventId) } }
        )
    }

    suspend fun decideApproval(uiId: String, approved: Boolean): HarnessRpcResult {
        val route = lock.withLock {
            approvals[uiId]
        } ?: return missingEvent("APPROVAL_EVENT_NOT_FOUND")
        val value = if (approved) "allowed-once" else "rejected"
        return settle(
            route.clientId,
            route.eventId,
            buildJsonObject {
                put("kind", "result")
                put("value", value)
            },
            remove = { lock.withLock { approvals.remove(uiId) } }
        )
    }

    suspend fun decidePlan(uiId: String, approved: Boolean): HarnessRpcResult {
        val route = lock.withLock { plans[uiId] }
            ?: return missingEvent("PLAN_EVENT_NOT_FOUND")
        val outcome = if (approved) {
            val selected = route.approveLabel
            buildJsonObject {
                put("kind", "result")
                put("value", buildJsonObject {
                    put("answers", buildJsonArray {
                        add(buildJsonObject {
                            put("id", route.answerId)
                            putJsonArray("selected") { add(JsonPrimitive(selected)) }
                        })
                    })
                })
            }
        } else {
            buildHarnessPlanCancellationOutcome()
        }
        return settle(
            route.clientId,
            route.eventId,
            outcome,
            remove = { lock.withLock { plans.remove(uiId) } }
        )
    }

    override fun close() {
        streamJob?.cancel()
        streamJob = null
        attachedClient = null
        clientId = null
    }

    private suspend fun settle(
        eventClientId: String,
        eventId: String,
        outcome: JsonObject,
        remove: suspend () -> Unit,
    ): HarnessRpcResult {
        val client = clientProvider()
            ?: return missingEvent("HARNESS_UNAVAILABLE")
        val result = client.call(
            "\$events",
            "result",
            buildJsonObject {
                put("clientId", eventClientId)
                put("eventId", eventId)
                put("outcome", outcome)
            },
            HarnessCallPolicy.NoRetry
        )
        if (result is HarnessRpcResult.Success) remove()
        return result
    }

    private fun missingEvent(code: String): HarnessRpcResult = HarnessRpcResult.Failure(
        com.example.llamadroid.harness.client.HarnessRpcError(code, "The Harness interaction is no longer pending")
    )

    private suspend fun applyFrame(frame: JsonElement) {
        when (frame.string("type")) {
            "ready" -> {
                val nextClientId = frame.string("clientId") ?: return
                val removed = lock.withLock {
                    clientId = nextClientId
                    val ids = questions.values.flatMap { it.questions.map(QuestionAnswer::uiId) } +
                        approvals.keys + plans.keys
                    questions.clear()
                    approvals.clear()
                    plans.clear()
                    ids
                }
                onReady(removed)
            }
            "waterfall" -> applyWaterfall(frame)
            "emit" -> {
                val event = frame.string("event") ?: return
                val args = (frame as? JsonObject)?.get("args") as? JsonArray ?: JsonArray(emptyList())
                onEmit(event, args)
            }
            "cancel" -> {
                val eventId = frame.string("eventId") ?: return
                val removed = lock.withLock { removeEvent(eventId) }
                if (removed.isNotEmpty()) onCancelled(removed)
            }
        }
    }

    private suspend fun applyWaterfall(frame: JsonElement) {
        val eventId = frame.string("eventId") ?: return
        // alpha.2's gateway puts the scoped Agent identity on the envelope; the
        // registry guarantees Agent.id == Session.id. Never infer it from the UI.
        val sessionId = frame.string("agentId")?.takeIf { it.isNotBlank() && it.length <= 256 }
            ?: throw IllegalArgumentException("HARNESS_EVENT_SESSION_MISSING")
        val activeClientId = lock.withLock { clientId } ?: return
        val request = frame.objectValue("request") ?: return
        when (frame.string("event")) {
            "approval/request" -> {
                val route = ApprovalRoute(activeClientId, eventId)
                lock.withLock { approvals[eventId] = route }
                onApproval(
                    NativeHarnessRemoteApproval(
                        sessionId = sessionId,
                        uiId = eventId,
                        eventId = eventId,
                        title = request.string("toolName").orEmpty(),
                        summary = request.string("reason").orEmpty(),
                        detail = request.string("callId")
                    )
                )
            }
            "user-questions/request" -> {
                val items = request.objectArray("questions")
                for ((index, item) in items.withIndex()) {
                    val answerId = item.string("id") ?: "question-$index"
                    val uiId = "$eventId:$answerId"
                    val intent = item.objectValue("intent")
                    val options = item.objectArray("options").mapNotNull { it.string("label") }
                    if (intent?.string("kind") == "plan-review") {
                        val approve = intent.string("approve") ?: options.firstOrNull() ?: "Approve"
                        val reject = options.firstOrNull { it != approve } ?: "Reject"
                        lock.withLock {
                            plans[uiId] = PlanRoute(activeClientId, eventId, answerId, approve)
                        }
                        onPlan(
                            NativeHarnessRemotePlan(
                                sessionId = sessionId,
                                uiId = uiId,
                                answerId = answerId,
                                eventId = eventId,
                                title = item.string("header").orEmpty(),
                                summary = item.string("question") ?: item.string("detail").orEmpty(),
                                steps = item.string("detail")?.lineSequence()?.filter(String::isNotBlank)?.toList().orEmpty(),
                                approveLabel = approve,
                                rejectLabel = reject
                            )
                        )
                    } else {
                        val route = QuestionRoute(
                            clientId = activeClientId,
                            eventId = eventId,
                            questions = items.mapIndexed { itemIndex, question ->
                                QuestionAnswer(
                                    uiId = "$eventId:${question.string("id") ?: "question-$itemIndex"}",
                                    answerId = question.string("id") ?: "question-$itemIndex"
                                )
                            }
                        )
                        // All question items in one waterfall share the same answer map.
                        lock.withLock { questions.putIfAbsent(eventId, route) }
                        onQuestion(
                            NativeHarnessRemoteQuestion(
                                sessionId = sessionId,
                                uiId = uiId,
                                answerId = answerId,
                                eventId = eventId,
                                title = item.string("header").orEmpty(),
                                prompt = item.string("question").orEmpty(),
                                detail = item.string("detail"),
                                options = options,
                                allowsFreeText = true,
                                multiSelect = item.boolean("multiSelect").orDefault(false)
                            )
                        )
                    }
                }
            }
        }
    }

    private fun removeEvent(eventId: String): List<String> {
        val ids = questions.values.filter { it.eventId == eventId }
            .flatMap { it.questions.map(QuestionAnswer::uiId) } +
            approvals.filterValues { it.eventId == eventId }.keys +
            plans.filterValues { it.eventId == eventId }.keys
        questions.entries.removeIf { it.value.eventId == eventId }
        approvals.entries.removeIf { it.value.eventId == eventId }
        plans.entries.removeIf { it.value.eventId == eventId }
        return ids
    }

    private fun removeQuestionRoute(eventId: String) {
        questions.entries.removeIf { it.value.eventId == eventId }
    }
}
