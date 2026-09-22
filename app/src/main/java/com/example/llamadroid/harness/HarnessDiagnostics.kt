package com.example.llamadroid.harness

import com.example.llamadroid.data.db.AgentProjectEventEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.harness.runtime.HarnessRuntimeLogEvent
import com.example.llamadroid.harness.runtime.HarnessRuntimeMetadataLogger
import com.example.llamadroid.harness.client.HarnessTransportEvent
import com.example.llamadroid.harness.client.HarnessTransportKind
import com.example.llamadroid.harness.client.HarnessTransportPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** Deliberately accepts only event metadata, never prompt, response, argument or exception text. */
class HarnessDiagnostics(
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val runtimeJournal: HarnessRuntimeJournal? = null,
) : HarnessRuntimeMetadataLogger {
    private val sequence = AtomicInteger()
    private var lastWebBoot: HarnessWebViewBootDiagnostic? = null
    val runtimeEntries: StateFlow<List<HarnessRuntimeDiagnostic>> = runtimeJournal?.entries
        ?: MutableStateFlow<List<HarnessRuntimeDiagnostic>>(emptyList()).asStateFlow()

    fun loadRuntimeJournal() { runtimeJournal?.load() }
    fun clearRuntimeJournal() { runtimeJournal?.clear() }

    /** Invoked by the client's bounded background diagnostic worker. */
    fun transport(event: HarnessTransportEvent) {
        if (event.kind == HarnessTransportKind.RPC && event.phase == HarnessTransportPhase.START) return
        runtimeJournal?.recordConnection(
            event = if (event.kind == HarnessTransportKind.RPC) "rpc" else "websocket",
            transport = if (event.kind == HarnessTransportKind.RPC) "http" else "websocket",
            operation = event.namespace?.let { namespace ->
                listOfNotNull(namespace, event.method?.takeIf(String::isNotEmpty)).joinToString("/")
            },
            outcome = when (event.phase) {
                HarnessTransportPhase.START -> "opening"
                HarnessTransportPhase.SUCCESS -> "success"
                HarnessTransportPhase.FAILURE -> "failure"
                HarnessTransportPhase.OPEN -> "open"
                HarnessTransportPhase.CLOSE -> "closed"
                HarnessTransportPhase.RECONNECT -> "retrying"
            },
            durationMs = event.durationMs, errorCode = event.errorCode,
            httpStatus = event.httpStatus ?: event.webSocketStatus?.takeIf { it in 100..599 },
            closeCode = event.webSocketStatus?.takeIf { it >= 1000 },
        )
    }

    fun webView(outcome: String, code: String? = null, httpStatus: Int? = null) {
        writeSafely { runtimeJournal?.recordConnection("webview", "webview", "interface", outcome,
            errorCode = code, httpStatus = httpStatus) }
    }

    /** Only fixed counts, booleans, and a bounded WebView error class survive the WebView boundary. */
    internal fun webBoot(diagnostic: HarnessWebViewBootDiagnostic) {
        if (diagnostic == lastWebBoot) return
        lastWebBoot = diagnostic
        val counts = mapOf(
            "entries" to diagnostic.graphEntries, "batches" to diagnostic.graphBatches,
            "pending" to diagnostic.pendingRegistrations, "scripts" to diagnostic.pluginScripts,
            "assets" to diagnostic.essentialAssets, "stylesheets" to diagnostic.stylesheetAssets,
            "importsFailed" to diagnostic.moduleImportFailures,
            "mounted" to diagnostic.mountedUi?.let { if (it) 1 else 0 },
            "overlay" to diagnostic.bootOverlay?.let { if (it) 1 else 0 },
            "failedOverlay" to diagnostic.failedOverlay?.let { if (it) 1 else 0 },
            "resourceError" to harnessWebResourceErrorMagnitude(diagnostic.webResourceErrorCode),
        ).mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        writeSafely { runtimeJournal?.recordConnection("webview", "webview", "interface",
            if (diagnostic.outcome == "pending") "waiting" else diagnostic.outcome,
            errorCode = diagnostic.code, httpStatus = diagnostic.httpStatus, webBoot = counts) }
    }

    fun event(sessionId: String?, event: String, status: String, durationMs: Long? = null, errorCode: String? = null) {
        writeSafely {
            if (event in setOf("connection", "parser", "render", "action", "transcript_action", "workspace_open") &&
                (status == "failure" || (event != "action" && status == "recovered"))) {
                runtimeJournal?.recordConnection(
                    event = if (status == "failure") "native_failure" else "native_recovered",
                    transport = "native", operation = event, outcome = status,
                    durationMs = durationMs, errorCode = errorCode,
                )
            }
            val mapping = sessionId?.let { database.harnessDao().session(it) } ?: return@writeSafely
            val workspace = database.harnessDao().workspace(mapping.workspaceId) ?: return@writeSafely
            database.agentChatDao().insertProjectEvent(AgentProjectEventEntity(
                conversationId = mapping.conversationId, projectFolder = workspace.projectFolder,
                sequenceNumber = sequence.incrementAndGet(), category = "HARNESS",
                eventType = token(event), status = token(status), durationMs = durationMs,
                errorClass = errorCode?.let(::harnessDiagnosticErrorClass), summary = token(event)
            ))
            database.agentChatDao().pruneProjectEvents(mapping.conversationId)
        }
    }

    fun eventForConversation(conversationId: Long, event: String, status: String, durationMs: Long? = null, errorCode: String? = null) {
        writeSafely {
            val conversation = database.agentChatDao().getConversation(conversationId) ?: return@writeSafely
            database.agentChatDao().insertProjectEvent(AgentProjectEventEntity(
                conversationId = conversationId, projectFolder = conversation.projectFolder,
                sequenceNumber = sequence.incrementAndGet(), category = "HARNESS",
                eventType = token(event), status = token(status), durationMs = durationMs,
                errorClass = errorCode?.let(::harnessDiagnosticErrorClass), summary = token(event)
            ))
            database.agentChatDao().pruneProjectEvents(conversationId)
        }
    }

    override fun record(event: HarnessRuntimeLogEvent) {
        // A first launch has no mapped conversation yet. Persist it before session fan-out.
        runtimeJournal?.record(event)
        writeSafely {
            database.harnessDao().conversations().filter { it.runtimeSource == "DEEPSEEK" }.forEach { conversation ->
                val session = database.harnessDao().sessionForConversation(conversation.id)
                event(session?.harnessSessionId, event.event, event.state?.name ?: "UNKNOWN", event.durationMs, event.errorCode)
            }
        }
    }

    private fun writeSafely(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                android.util.Log.e("HarnessDiagnostics", "Journal write failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun token(value: String): String = value.take(128).replace(Regex("[^A-Za-z0-9_.:/-]"), "_")
}
