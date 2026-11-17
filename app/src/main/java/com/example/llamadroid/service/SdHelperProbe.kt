package com.example.llamadroid.service

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

/** What a probe of a helper's heartbeat port learned. */
enum class SdHelperLiveness { HEARTBEAT, HOST_ALIVE, UNREACHABLE }

/**
 * Probes a helper without touching its RPC port: the SD RPC server serves one client at a time, so connecting to
 * it while sd-cli is attached would steal or queue behind the real connection. The helper app answers a banner on
 * `rpcPort + 1` instead.
 */
object SdHelperProbe {
    const val HEARTBEAT_PORT_OFFSET = 1
    const val BANNER = "adt-sd-hb 1"
    const val DEFAULT_TIMEOUT_MS = 2000

    fun heartbeatPort(rpcPort: Int): Int = rpcPort + HEARTBEAT_PORT_OFFSET

    /**
     * Banner read -> [SdHelperLiveness.HEARTBEAT]; connected without the banner, or refused (the host answered with
     * a RST) -> [SdHelperLiveness.HOST_ALIVE]; timeout, no route or unknown host -> [SdHelperLiveness.UNREACHABLE].
     */
    fun probe(host: String, rpcPort: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): SdHelperLiveness =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, heartbeatPort(rpcPort)), timeoutMs)
                socket.soTimeout = timeoutMs
                val line = try {
                    socket.getInputStream().bufferedReader().readLine()
                } catch (_: java.net.SocketTimeoutException) {
                    // The listener accepted but its thread is starved (helper CPU is saturated by the run): the
                    // heartbeat socket still exists, so the helper process is alive.
                    return@use SdHelperLiveness.HEARTBEAT
                } catch (_: java.io.IOException) {
                    null
                }
                if (line?.trim() == BANNER) SdHelperLiveness.HEARTBEAT else SdHelperLiveness.HOST_ALIVE
            }
        } catch (e: ConnectException) {
            if (e.message.orEmpty().contains("refused", ignoreCase = true)) {
                SdHelperLiveness.HOST_ALIVE
            } else {
                SdHelperLiveness.UNREACHABLE
            }
        } catch (_: Exception) {
            SdHelperLiveness.UNREACHABLE
        }
}
