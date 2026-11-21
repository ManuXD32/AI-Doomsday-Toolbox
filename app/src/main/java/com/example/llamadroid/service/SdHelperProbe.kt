package com.example.llamadroid.service

import com.example.llamadroid.util.HelperHeartbeat
import com.example.llamadroid.util.HelperLiveness

/** What a probe of a helper's heartbeat port learned. */
enum class SdHelperLiveness { HEARTBEAT, HOST_ALIVE, UNREACHABLE }

/**
 * Probes a helper without touching its RPC port: the SD RPC server serves one client at a time, so connecting to
 * it while sd-cli is attached would steal or queue behind the real connection. The helper app answers a banner on
 * `rpcPort + 1` instead.
 */
object SdHelperProbe {
    const val HEARTBEAT_PORT_OFFSET = HelperHeartbeat.PORT_OFFSET
    const val BANNER = "adt-sd-hb 1"
    const val DEFAULT_TIMEOUT_MS = HelperHeartbeat.DEFAULT_TIMEOUT_MS

    fun heartbeatPort(rpcPort: Int): Int = HelperHeartbeat.heartbeatPort(rpcPort)

    /**
     * Banner read -> [SdHelperLiveness.HEARTBEAT]; connected without the banner, or refused (the host answered with
     * a RST) -> [SdHelperLiveness.HOST_ALIVE]; timeout, no route or unknown host -> [SdHelperLiveness.UNREACHABLE].
     */
    fun probe(host: String, rpcPort: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): SdHelperLiveness =
        when (HelperHeartbeat.probe(host, rpcPort, BANNER, timeoutMs)) {
            HelperLiveness.HEARTBEAT -> SdHelperLiveness.HEARTBEAT
            HelperLiveness.HOST_ALIVE -> SdHelperLiveness.HOST_ALIVE
            HelperLiveness.UNREACHABLE -> SdHelperLiveness.UNREACHABLE
        }
}
