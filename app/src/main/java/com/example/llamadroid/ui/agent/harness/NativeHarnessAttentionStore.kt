package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcError
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

data class HarnessAttentionEntry(
    val sessionId: String,
    val eventId: String,
    val id: String,
    val question: HarnessQuestionUi? = null,
    val approval: HarnessApprovalUi? = null,
    val plan: HarnessPlanUi? = null,
)

data class HarnessAttentionNotice(val sessionId: String, val key: String, val kind: String)
data class HarnessRemoteEmit(val event: String, val args: JsonArray)

/** One native event carrier for the lifetime of the explicitly started runtime. */
class NativeHarnessAttentionStore(
    private val scope: CoroutineScope,
    private val clientProvider: () -> HarnessClient?,
    private val onNotice: suspend (HarnessAttentionNotice) -> Unit = {},
    private val onResolved: (HarnessAttentionNotice) -> Unit = {},
    private val onFailure: suspend (Throwable) -> Unit = {},
) {
    private val mutableEntries = MutableStateFlow<List<HarnessAttentionEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private val mutableEmits = MutableSharedFlow<HarnessRemoteEmit>(extraBufferCapacity = 64)
    val emits = mutableEmits.asSharedFlow()
    private val running = ConcurrentHashMap<String, String>()
    private val userStops = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private var reconnect: Job? = null
    private var attached: HarnessClient? = null
    private val remote: NativeHarnessRemoteEvents = NativeHarnessRemoteEvents(
        scope, clientProvider,
        onReady = { remove(it, dismiss = false) },
        onQuestion = { value ->
            add(HarnessAttentionEntry(value.sessionId, value.eventId, value.uiId,
                question = HarnessQuestionUi(value.uiId, value.title, value.prompt, value.options,
                    value.allowsFreeText, value.multiSelect)), "question")
        },
        onApproval = { value ->
            add(HarnessAttentionEntry(value.sessionId, value.eventId, value.uiId,
                approval = HarnessApprovalUi(value.uiId, value.title, value.summary, detail = value.detail)), "approval")
        },
        onPlan = { value ->
            add(HarnessAttentionEntry(value.sessionId, value.eventId, value.uiId,
                plan = HarnessPlanUi(value.uiId, value.title, value.summary, value.steps)), "plan")
        },
        onCancelled = { remove(it) },
        onEmit = { event, args -> acceptEmit(event, args) },
        onFailure = { failure ->
            onFailure(failure)
            reconnect?.cancel()
            reconnect = scope.launch {
                delay(2_000)
                if (attached != null && attached === clientProvider()) restart()
            }
        },
    )

    fun attach(client: HarnessClient?) {
        if (attached === client) return
        reconnect?.cancel()
        attached = client
        mutableEntries.value = emptyList()
        running.clear()
        userStops.clear()
        failed.clear()
        remote.attach(client)
    }

    fun restart() = remote.restart()
    fun noteStop(sessionId: String) { userStops.add(sessionId) }
    fun hasPending(): Boolean = entries.value.isNotEmpty()

    suspend fun answer(sessionId: String?, id: String, answer: String, custom: Boolean, selected: List<String>): HarnessRpcResult =
        settle(sessionId, id) { remote.answerQuestion(id, answer, custom, selected) }

    suspend fun approve(sessionId: String?, id: String, approved: Boolean): HarnessRpcResult =
        settle(sessionId, id) { remote.decideApproval(id, approved) }

    suspend fun reviewPlan(sessionId: String?, id: String, approved: Boolean): HarnessRpcResult =
        settle(sessionId, id) { remote.decidePlan(id, approved) }

    private suspend fun settle(sessionId: String?, id: String, action: suspend () -> HarnessRpcResult): HarnessRpcResult {
        val entry = entries.value.firstOrNull { it.id == id && it.sessionId == sessionId }
            ?: return HarnessRpcResult.Failure(HarnessRpcError("INTERACTION_NO_LONGER_PENDING", "The interaction is no longer pending"))
        val result = action()
        if (result is HarnessRpcResult.Success) {
            if (result.value.boolean("pending") == true) remove(listOf(id))
            else remove(entries.value.filter { it.eventId == entry.eventId }.map { it.id })
        }
        return result
    }

    private suspend fun add(entry: HarnessAttentionEntry, kind: String) {
        // Never evict an unanswered item merely because another project asks a question.
        check(entries.value.size < 512 || entries.value.any { it.id == entry.id }) { "HARNESS_ATTENTION_LIMIT" }
        mutableEntries.update { current -> current.filterNot { it.id == entry.id } + entry }
        onNotice(HarnessAttentionNotice(entry.sessionId, entry.id, kind))
    }

    private fun remove(ids: List<String>, dismiss: Boolean = true) {
        val removed = ids.toSet()
        entries.value.filter { dismiss && it.id in removed }.forEach { entry ->
            val kind = when { entry.plan != null -> "plan"; entry.approval != null -> "approval"; else -> "question" }
            onResolved(HarnessAttentionNotice(entry.sessionId, entry.id, kind))
        }
        mutableEntries.update { values -> values.filterNot { it.id in removed } }
    }

    private suspend fun acceptEmit(event: String, args: JsonArray) {
        mutableEmits.emit(HarnessRemoteEmit(event, args))
        val session = (args.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return
        when (event) {
            "api-session/error" -> failed.add(session)
            "api-session/status" -> when ((args.getOrNull(1) as? JsonPrimitive)?.booleanOrNull) {
                true -> if (running.putIfAbsent(session, UUID.randomUUID().toString()) == null) {
                    failed.remove(session)
                    userStops.remove(session)
                }
                false -> {
                    val turn = running.remove(session) ?: return // no historic completion on reconnect
                    val kind = when {
                        failed.remove(session) -> "interrupted"
                        userStops.remove(session) -> "stopped"
                        entries.value.any { it.sessionId == session } -> return
                        else -> "completed"
                    }
                    onNotice(HarnessAttentionNotice(session, "$session:$turn:$kind", kind))
                }
                null -> Unit
            }
        }
    }
}
