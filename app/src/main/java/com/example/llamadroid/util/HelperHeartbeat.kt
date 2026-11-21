package com.example.llamadroid.util

import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** What a probe of a helper's heartbeat port learned. */
enum class HelperLiveness { HEARTBEAT, HOST_ALIVE, UNREACHABLE }

/**
 * Optional second heartbeat line: what a helper tells the master about itself. Every field is optional so older
 * or partial helpers still parse.
 */
data class HelperSpecs(
    val name: String? = null,
    val node: String? = null,
    val ramFreeMb: Int? = null,
    val ramTotalMb: Int? = null,
    val cores: Int? = null,
    val threads: Int? = null,
    val gflops: Double? = null,
    val version: String? = null
) {
    /** One-line JSON; unknown fields are left out. */
    fun toJsonLine(): String = buildJsonObject {
        name?.let { put("name", it) }
        node?.let { put("node", it) }
        ramFreeMb?.let { put("ram_free_mb", it) }
        ramTotalMb?.let { put("ram_total_mb", it) }
        cores?.let { put("cores", it) }
        threads?.let { put("threads", it) }
        gflops?.let { put("gflops", it) }
        version?.let { put("version", it) }
    }.toString()

    companion object {
        /** Null when [line] is not a JSON object; wrong-typed or non-positive numbers become null. */
        fun parse(line: String?): HelperSpecs? {
            val text = line?.trim()?.takeIf { it.startsWith("{") } ?: return null
            val obj = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            fun num(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it > 0.0 }
            return HelperSpecs(
                name = str("name"), node = str("node"),
                ramFreeMb = num("ram_free_mb")?.toInt(), ramTotalMb = num("ram_total_mb")?.toInt(),
                cores = num("cores")?.toInt(), threads = num("threads")?.toInt(),
                gflops = num("gflops"), version = str("version")
            )
        }
    }
}

/** Outcome of [HelperHeartbeat.probeDetailed]: liveness, the optional specs line and the connect+banner round trip. */
data class HelperProbeResult(val liveness: HelperLiveness, val specs: HelperSpecs? = null, val rttMs: Long? = null)

/**
 * Shared heartbeat sidecar for single-client helpers (SD RPC worker, Branchy worker): serves [banner] and closes
 * every accepted socket on `rpcPort + 1`, so a master can tell the helper is alive without touching the
 * single-client RPC port.
 */
class HelperHeartbeatServer(
    private val port: Int,
    private val banner: String,
    private val bindHost: String = "0.0.0.0",
    private val threadName: String = "helper-heartbeat",
    private val logTag: String = "HelperHeartbeat",
    /** Called once per accepted socket; a non-null result is sent as a second line (fresh values per probe). */
    private val payloadProvider: (() -> String?)? = null
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    /** Binds and starts serving; returns false (after logging) when the port cannot be bound. */
    fun start(): Boolean {
        val socket = try {
            ServerSocket(port, 50, InetAddress.getByName(bindHost))
        } catch (e: Exception) {
            DebugLog.log("[$logTag] bind failed on $port: ${e.message}")
            return false
        }
        server = socket
        thread = Thread({
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 1000
                        val payload = runCatching { payloadProvider?.invoke() }.getOrNull()
                            ?.replace('\n', ' ')?.replace('\r', ' ')
                        client.getOutputStream().apply {
                            write((banner + "\n").toByteArray(Charsets.US_ASCII))
                            if (payload != null) write((payload + "\n").toByteArray(Charsets.UTF_8))
                            flush()
                        }
                    }
                } catch (_: Exception) {
                    // closed server or a client that vanished; loop condition decides whether to stop
                }
            }
        }, threadName).apply { isDaemon = true; start() }
        return true
    }

    fun close() {
        runCatching { server?.close() }
        server = null
        thread = null
    }
}

/**
 * Probes a helper's heartbeat port (`rpcPort + 1`) without touching its single-client RPC port.
 */
object HelperHeartbeat {
    const val PORT_OFFSET = 1
    const val DEFAULT_TIMEOUT_MS = 2000

    fun heartbeatPort(rpcPort: Int): Int = rpcPort + PORT_OFFSET

    /**
     * Banner read -> [HelperLiveness.HEARTBEAT]; connected without the banner, or refused (the host answered with
     * a RST) -> [HelperLiveness.HOST_ALIVE]; timeout, no route or unknown host -> [HelperLiveness.UNREACHABLE].
     */
    fun probe(host: String, rpcPort: Int, banner: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): HelperLiveness =
        probeDetailed(host, rpcPort, banner, timeoutMs).liveness

    /**
     * Like [probe], and also reads the optional specs line (old helpers send the banner only, which still counts
     * as online) and measures the connect + banner round trip in milliseconds.
     */
    fun probeDetailed(host: String, rpcPort: Int, banner: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): HelperProbeResult =
        try {
            Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(host, heartbeatPort(rpcPort)), timeoutMs)
                socket.soTimeout = timeoutMs
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val line = try {
                    reader.readLine()
                } catch (_: java.net.SocketTimeoutException) {
                    // The listener accepted but its thread is starved (helper CPU is saturated by the run): the
                    // heartbeat socket still exists, so the helper process is alive.
                    return@use HelperProbeResult(HelperLiveness.HEARTBEAT)
                } catch (_: java.io.IOException) {
                    null
                }
                val rtt = (System.nanoTime() - start) / 1_000_000
                if (line?.trim() != banner) return@use HelperProbeResult(HelperLiveness.HOST_ALIVE, null, rtt)
                val specs = try {
                    HelperSpecs.parse(reader.readLine())
                } catch (_: java.io.IOException) {
                    null
                }
                HelperProbeResult(HelperLiveness.HEARTBEAT, specs, rtt)
            }
        } catch (e: ConnectException) {
            if (e.message.orEmpty().contains("refused", ignoreCase = true)) {
                HelperProbeResult(HelperLiveness.HOST_ALIVE)
            } else {
                HelperProbeResult(HelperLiveness.UNREACHABLE)
            }
        } catch (_: Exception) {
            HelperProbeResult(HelperLiveness.UNREACHABLE)
        }
}
