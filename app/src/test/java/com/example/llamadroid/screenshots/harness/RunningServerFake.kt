package com.example.llamadroid.screenshots.harness

import android.content.Context
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaServerSessionOwner
import com.example.llamadroid.service.LlamaServerSessionOwnerStore
import com.example.llamadroid.service.LlamaServerSessionSnapshot
import com.example.llamadroid.service.LlamaServerSessionStateStore
import com.example.llamadroid.service.LlamaServerSessionStatus
import com.example.llamadroid.util.NativeProcessCleanup
import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockkObject

/**
 * A fake "running" llama-server: a tiny loopback HTTP stub answering `/health`, `/props` and
 * `/slots` (pattern of EasyLlamaChatOpeningTest) plus the owner/state session files, with the
 * native liveness probe mocked to true. [stop] must run from `@After` (the harness `unmockkAll`
 * restores [NativeProcessCleanup]).
 */
class RunningServerFake(private val contextSize: Int = 8192, private val preferredPort: Int = 8080) {
    private var server: NanoHTTPD? = null
    private val sessionIds = mutableListOf<String>()
    private var pidCounter = 4100

    /** Starts the stub (idempotent) and returns its loopback port. */
    fun start(): Int {
        server?.let { return it.listeningPort }
        // Prefer the llama-server default port so the cards read "Running on 8080"; fall back to any free port.
        val stub = listOf(preferredPort, 0).firstNotNullOf { port ->
            runCatching {
                object : NanoHTTPD("127.0.0.1", port) {
                    override fun serve(session: IHTTPSession): Response = newFixedLengthResponse(
                        Response.Status.OK, "application/json", when (session.uri) {
                            "/slots" -> """[{"id":0,"n_ctx":$contextSize,"is_processing":false}]"""
                            "/props" -> """{"default_generation_settings":{"n_ctx":$contextSize}}"""
                            else -> """{"status":"ok"}"""
                        }
                    )
                }.also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            }.getOrNull()
        }
        server = stub
        return stub.listeningPort
    }

    /** Marks [sessionId] (for a card: "card:<id>") as a live RUNNING server on [port]. */
    fun markRunning(context: Context, sessionId: String, port: Int, profile: LlamaServerLaunchProfile? = null) {
        mockkObject(NativeProcessCleanup)
        every { NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(any(), any(), any(), any(), any(), any()) } returns true
        val pid = pidCounter++
        LlamaServerSessionOwnerStore(context).write(
            LlamaServerSessionOwner(
                sessionId, pid, 12345L + pid, port,
                profile?.let { LlamaServerLaunchProfile.encodeForPersistence(it) }
            )
        )
        LlamaServerSessionStateStore(context).write(
            LlamaServerSessionSnapshot(sessionId, LlamaServerSessionStatus.RUNNING, port, pid)
        )
        sessionIds += sessionId
    }

    fun stop(context: Context) {
        sessionIds.forEach {
            runCatching { LlamaServerSessionOwnerStore(context).delete(it) }
            runCatching { LlamaServerSessionStateStore(context).delete(it) }
        }
        sessionIds.clear()
        runCatching { server?.stop() }
        server = null
    }
}
