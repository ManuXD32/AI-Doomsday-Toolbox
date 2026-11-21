package com.example.llamadroid.branchy.infer

import android.content.Context
import androidx.core.content.edit
import com.example.llamadroid.branchy.BranchyCommandBuilder
import com.example.llamadroid.branchy.BranchyEvent
import com.example.llamadroid.branchy.BranchyEventParser
import com.example.llamadroid.branchy.BranchyRunEntity
import com.example.llamadroid.branchy.cluster.BranchyClusterToken
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.repository.LocalLlamaServerRepository
import com.example.llamadroid.modeltools.BranchyExportSupport
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaServerSessionStateStore
import com.example.llamadroid.service.LlamaServerSessionStatus
import com.example.llamadroid.service.ManagedLlamaServerCoordinator
import com.example.llamadroid.service.ProcessController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** One step of the export, push and start pipeline. [key] is `export_main`, `export:<helperId>`, `push:<helperId>` or `server`. */
data class InferStep(val key: String, val helperId: String? = null)

sealed class InferStepState {
    object Pending : InferStepState()
    data class Running(val done: Long = 0L, val total: Long = 0L) : InferStepState()
    /** [reused] = the file from an earlier run was kept. */
    data class Done(val reused: Boolean = false) : InferStepState()
    data class Failed(val message: String) : InferStepState()
}

class BranchyInferException(val code: String, message: String) : Exception(message)

/** What the pipeline needs; [slices] is helper id -> hosted branches. */
data class InferRequest(
    val run: BranchyRunEntity,
    val type: String,
    val helpers: List<InferHelper>,
    val slices: Map<String, List<Int>>,
    val layout: InferLayout
)

/** The server card the chat lists, plus the helper targets it was started with. */
data class InferStarted(val cardId: Long, val sessionId: String, val helperTargets: List<String>)

/**
 * Export `main.gguf` and one slice per helper, push the slices (resumable: the native push resumes by name and
 * hash), then start the app's managed native llama-server card with the Branchy flags. Reuses
 * `LocalLlamaServerRepository` and `ManagedLlamaServerCoordinator`; no parallel launcher.
 */
class BranchyInferPipeline(context: Context) {
    private val context = context.applicationContext

    fun steps(request: InferRequest): List<InferStep> = buildList {
        add(InferStep("export_main"))
        request.helpers.filter { it.id in request.slices }.forEach { add(InferStep("export:${it.id}", it.id)) }
        request.helpers.filter { it.id in request.slices }.forEach { add(InferStep("push:${it.id}", it.id)) }
        add(InferStep("server"))
    }

    suspend fun run(request: InferRequest, onStep: (InferStep, InferStepState) -> Unit): InferStarted =
        withContext(Dispatchers.IO) {
            val exe = BinaryRepository(context).getTieredBinary("branchy_trainer")?.absolutePath
                ?: throw BranchyInferException("no_binary", "binary")
            val runDir = File(request.run.runDir)
            val ckpt = BranchyExportSupport.latestCheckpoint(runDir, request.run.stage)
                ?: throw BranchyInferException("no_checkpoint", "checkpoint")
            val tokenizer = File(runDir, "tokenizer.json")
            val outDir = File(runDir, "infer").apply { mkdirs() }
            val tokenFile = BranchyClusterToken.writeTokenFile(context)
            val active = request.helpers.filter { it.id in request.slices }
            val stepList = steps(request)
            fun step(key: String) = stepList.first { it.key == key }

            val mainFile = File(outDir, BranchyPlacement.mainFileName(request.run.name, ckpt.name, request.type))
            exportOnce(step("export_main"), onStep, mainFile) { tmp ->
                BranchyCommandBuilder.exportGguf(exe, ckpt.absolutePath, tokenizer.absolutePath, tmp, request.type)
            }
            val sliceFiles = active.associate { h ->
                val branches = request.slices.getValue(h.id)
                val file = File(outDir, BranchyPlacement.sliceFileName(request.run.name, ckpt.name, request.type, branches))
                exportOnce(step("export:${h.id}"), onStep, file) { tmp ->
                    BranchyCommandBuilder.exportGguf(exe, ckpt.absolutePath, null, tmp, request.type, branches)
                }
                h.id to file
            }
            for (h in active) {
                val st = step("push:${h.id}")
                onStep(st, InferStepState.Running())
                val file = sliceFiles.getValue(h.id)
                val result = runNative(
                    BranchyCommandBuilder.push(exe, h.pushTarget, tokenFile.absolutePath, file.absolutePath, file.name)
                ) { ev ->
                    if (ev is BranchyEvent.Progress && ev.phase == "push") onStep(st, InferStepState.Running(ev.done, ev.total))
                }
                if (result.exit != 0 || !result.pushed) {
                    val msg = result.error ?: "exit ${result.exit}"
                    onStep(st, InferStepState.Failed(msg))
                    throw BranchyInferException("push_failed", "${h.name}: $msg")
                }
                onStep(st, InferStepState.Done())
            }
            val serverStep = step("server")
            onStep(serverStep, InferStepState.Running())
            try {
                val started = startServer(request, mainFile, active.map { it.inferTarget }, tokenFile)
                onStep(serverStep, InferStepState.Done())
                started
            } catch (e: Exception) {
                onStep(serverStep, InferStepState.Failed(e.message.orEmpty()))
                throw e
            }
        }

    private suspend fun exportOnce(
        step: InferStep,
        onStep: (InferStep, InferStepState) -> Unit,
        out: File,
        argv: (tmp: String) -> List<String>
    ) {
        if (out.isFile && out.length() > 0L) {
            onStep(step, InferStepState.Done(reused = true))
            return
        }
        onStep(step, InferStepState.Running())
        val tmp = File(out.parentFile, out.name + ".part").also { it.delete() }
        val result = runNative(argv(tmp.absolutePath)) { ev ->
            if (ev is BranchyEvent.Progress) onStep(step, InferStepState.Running(ev.done, ev.total))
        }
        if (result.exit != 0 || !tmp.isFile || tmp.length() == 0L) {
            tmp.delete()
            val msg = result.error ?: "exit ${result.exit}"
            onStep(step, InferStepState.Failed(msg))
            throw BranchyInferException("export_failed", msg)
        }
        out.delete()
        if (!tmp.renameTo(out)) {
            onStep(step, InferStepState.Failed("rename"))
            throw BranchyInferException("export_failed", "rename")
        }
        onStep(step, InferStepState.Done())
    }

    private class NativeResult(val exit: Int, val error: String?, val pushed: Boolean)

    private suspend fun runNative(argv: List<String>, onEvent: (BranchyEvent) -> Unit): NativeResult = coroutineScope {
        // stderr is merged into stdout; the parser ignores every line that is not a BRANCHY_EVENT.
        val proc = ProcessBuilder(argv).redirectErrorStream(true).start()
        // Reading blocks and is not cancellable: a watcher kills the process on cancellation, which ends the read.
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                if (proc.isAlive) proc.destroyForcibly()
            }
        }
        var error: String? = null
        var pushed = false
        try {
            withContext(Dispatchers.IO) {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    val ev = BranchyEventParser.parse(line) ?: return@forEachLine
                    if (ev is BranchyEvent.Error) error = ev.message
                    if (ev is BranchyEvent.Unknown && ev.type == "pushed") pushed = true
                    onEvent(ev)
                }
            }
        } finally {
            watcher.cancel()
            if (proc.isAlive) proc.destroyForcibly()
        }
        NativeResult(withContext(Dispatchers.IO) { proc.waitFor() }, error, pushed)
    }

    /**
     * Creates (or updates, keyed by the run id in the `branchy_infer` prefs) one managed server card for the
     * main model with the extra flags in [BranchyInferArgs.serverArgs], then waits until it answers `/health`.
     */
    private suspend fun startServer(
        request: InferRequest,
        mainFile: File,
        helperTargets: List<String>,
        tokenFile: File
    ): InferStarted {
        val settings = SettingsRepository(context)
        val flags = ProcessController().buildCommandString(BranchyInferArgs.serverArgs(helperTargets, tokenFile.absolutePath))
        val base = LlamaServerLaunchProfile.capture(settings).copy(
            modelPath = mainFile.absolutePath,
            mmprojPath = null,
            visionEnabled = false,
            videoEnabled = false,
            speculativeEnabled = false,
            host = "127.0.0.1",
            parallel = 1,
            // The model sleeping would drop and re-create the remote context.
            sleepIdleSeconds = null,
            idleStopSeconds = null,
            contextSize = (request.layout.context ?: 4096).coerceIn(256, 8192),
            customFlags = flags
        )
        val database = AppDatabase.getDatabase(context)
        val repo = LocalLlamaServerRepository(context, database)
        val name = "Branchy - ${request.run.name}".take(60)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "card_${request.run.id}"
        val existing = prefs.getLong(key, 0L).takeIf { it > 0L }?.let { database.llamaServerCardDao().getCard(it) }
        val cardId = if (existing != null) {
            // A running session keeps the old flags: stop it so the new placement takes effect.
            LlamaServerLauncher.stopSession(context, existing.sessionId)
            awaitStopped(existing.sessionId)
            repo.updateLocalServerProfile(existing.id, base, name = name)
            existing.id
        } else {
            repo.createLocalServer(name, base).cardId.also { prefs.edit { putLong(key, it) } }
        }
        val card = database.llamaServerCardDao().getCard(cardId)
            ?: throw BranchyInferException("card_missing", "card")
        ManagedLlamaServerCoordinator(context, database).prepare(cardId)
        return InferStarted(cardId, card.sessionId, helperTargets)
    }

    /** The stop command is asynchronous; wait (up to 15 s) until the session no longer reports RUNNING. */
    private suspend fun awaitStopped(sessionId: String) {
        val states = LlamaServerSessionStateStore(context)
        repeat(30) {
            val running = states.readAll().firstOrNull { it.sessionId == sessionId }?.status == LlamaServerSessionStatus.RUNNING
            if (!running) return
            delay(500)
        }
    }

    companion object {
        private const val PREFS = "branchy_infer"

        /** Median TCP connect time (about one round trip) to [host]:[port] in ms, or null when unreachable. */
        fun measureRttMs(host: String, port: Int, timeoutMs: Int = 1500): Int? {
            val times = (1..3).mapNotNull {
                val t0 = System.nanoTime()
                runCatching { Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) } }
                    .getOrNull()?.let { ((System.nanoTime() - t0) / 1_000_000L).toInt() }
            }.sorted()
            return times.getOrNull(times.size / 2)
        }
    }
}
