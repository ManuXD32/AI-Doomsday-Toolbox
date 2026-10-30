package com.example.llamadroid.service.ephemeral

import android.content.Context
import android.os.SystemClock
import com.example.llamadroid.data.HttpEndpointUrlSupport
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaServerSessionStateStore
import com.example.llamadroid.service.LlamaServerSessionStatus
import com.example.llamadroid.service.ManagedLlamaServerHttp
import com.example.llamadroid.service.checkLlamaServerPort
import com.example.llamadroid.service.ram.LlamaRamEstimator
import com.example.llamadroid.service.ram.LlamaRamGate
import com.example.llamadroid.service.ram.LlamaRamInputs
import com.example.llamadroid.service.chooseEasyLlamaPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Result of a free-RAM check for a temporary model; [ok] is false when memory is short or low. */
data class RamDecision(
    val ok: Boolean,
    val neededBytes: Long,
    val availBytes: Long,
    val lowMemory: Boolean
)

/**
 * Shared lifecycle for short-lived, low-impact llama-server sessions (chat titles, vision helper).
 * Only one ephemeral server runs at a time process-wide; the session is always stopped through the
 * same launcher owner that started it, even on cancellation.
 */
object EphemeralLlamaServer {
    const val START_TIMEOUT_MS = 120_000L
    const val REQUEST_TIMEOUT_MS = 60_000L
    private const val LOW_IMPACT_CONTEXT_TOKENS = 2048
    private const val STOP_WAIT_MS = 15_000L
    private const val IDLE_STOP_SECONDS = 600

    private val mutex = Mutex()

    /**
     * Starts the keyed session on a free loopback port, waits for /health, runs [block] with the
     * base URL (no trailing slash) and always stops the session. Throws if the server cannot start.
     */
    suspend fun <T> run(
        context: Context,
        sessionId: String,
        profile: LlamaServerLaunchProfile,
        block: suspend (baseUrl: String) -> T
    ): T {
        val app = context.applicationContext
        return mutex.withLock {
            var started = false
            var url: String? = null
            try {
                val port = chooseEasyLlamaPort(emptySet()) { checkLlamaServerPort("127.0.0.1", it).available }
                    ?: error("No free loopback port")
                val endpoint = HttpEndpointUrlSupport.fromHostPort("127.0.0.1", port) ?: error("Invalid endpoint")
                url = endpoint
                val launch = profile.copy(host = "127.0.0.1", serverPort = port)
                val startedAt = System.currentTimeMillis()
                started = true
                withContext(Dispatchers.IO) {
                    LlamaServerLauncher.startSession(app, sessionId, launch, port).getOrThrow()
                    waitHealthy(app, sessionId, endpoint, startedAt)
                }
                block(endpoint)
            } finally {
                if (started) withContext(NonCancellable) { stop(app, sessionId, url) }
            }
        }
    }

    private suspend fun waitHealthy(app: Context, sessionId: String, url: String, startedAt: Long) {
        val states = LlamaServerSessionStateStore(app)
        val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ManagedLlamaServerHttp.healthy(url)) return
            val state = states.readAll().firstOrNull { it.sessionId == sessionId }
            if (state?.status == LlamaServerSessionStatus.ERROR && state.updatedAt >= startedAt) {
                error("Temporary server failed to start")
            }
            delay(1_000)
        }
        error("Temporary server start timed out")
    }

    private suspend fun stop(app: Context, sessionId: String, url: String?) = withContext(Dispatchers.IO) {
        runCatching { LlamaServerLauncher.stopSession(app, sessionId) }
        if (url != null) {
            val deadline = SystemClock.elapsedRealtime() + STOP_WAIT_MS
            while (SystemClock.elapsedRealtime() < deadline && ManagedLlamaServerHttp.healthy(url)) delay(500)
        }
    }

    /** Small-footprint launch profile; [base] (when given) keeps binary/flag choices of an existing server. */
    fun lowImpactProfile(
        base: LlamaServerLaunchProfile?,
        modelPath: String,
        mmprojPath: String? = null,
        contextTokens: Int = LOW_IMPACT_CONTEXT_TOKENS
    ): LlamaServerLaunchProfile {
        val threads = (Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
        val seed = base ?: LlamaServerLaunchProfile()
        return seed.copy(
            modelPath = modelPath,
            mmprojPath = mmprojPath,
            visionEnabled = mmprojPath != null,
            videoEnabled = false,
            host = "127.0.0.1",
            threads = threads,
            threadsBatch = threads,
            batchSize = 256,
            contextSize = contextTokens,
            parallel = 1,
            sleepIdleSeconds = null,
            idleStopSeconds = IDLE_STOP_SECONDS
        )
    }

    /**
     * Pure RAM rule shared with unit tests. Uses the same estimator as the chat admission queue:
     * mmap working set of the model, a small-context KV cache, [extraBytes] (mmproj) and a compute
     * buffer, plus the shared safety margin ([RamDecision.neededBytes] includes it).
     */
    fun decideRam(
        availBytes: Long,
        lowMemory: Boolean,
        modelBytes: Long,
        extraBytes: Long = 0L,
        reclaimableCacheBytes: Long = 0L
    ): RamDecision {
        val estimate = LlamaRamEstimator.estimate(
            LlamaRamInputs(
                modelFileBytes = modelBytes,
                mmprojBytes = extraBytes,
                totalContext = LOW_IMPACT_CONTEXT_TOKENS
            )
        )
        val decision = LlamaRamEstimator.decide(estimate, availBytes, lowMemory, reclaimableCacheBytes)
        val needed = if (modelBytes <= 0L) 0L else decision.requiredBytes
        return RamDecision(decision.ok && modelBytes > 0L, needed, availBytes, lowMemory)
    }

    fun ramDecision(context: Context, modelBytes: Long, extraBytes: Long = 0L): RamDecision {
        val ram = LlamaRamGate(context).snapshot()
        return decideRam(ram.availBytes, ram.lowMemory, modelBytes, extraBytes, ram.reclaimableCacheBytes)
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Blocking-free JSON POST to [baseUrl] + [path]; returns the response body or throws on non-2xx. */
    suspend fun postJson(baseUrl: String, path: String, body: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Request failed: HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }
}
