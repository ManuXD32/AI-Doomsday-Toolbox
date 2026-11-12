package com.example.llamadroid.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.InetSocketAddress
import java.net.Socket

/** Outcome of one TCP reachability probe. */
data class ProbeResult(
    val reachable: Boolean,
    val ms: Long?,
    /** Simple exception class name when the probe failed, else null. */
    val errorClass: String?
)

/** A runtime failure recognised in llama-server output. */
sealed interface DistributedRuntimeFailure {
    data class Unreachable(val address: String) : DistributedRuntimeFailure
    data class Abort(val lastLine: String?) : DistributedRuntimeFailure
}

/** TCP preflight for RPC helpers, plus the mapping of raw llama-server output to readable text. */
object DistributedConnectivity {
    const val DEFAULT_TIMEOUT_MS = 2500
    private const val EXIT_ABORT = 134

    private val connectFailure = Regex("""Failed to connect to\s+\[?([^\s\]]+?)\]?:(\d{1,5})""", RegexOption.IGNORE_CASE)

    /** Source-file prefixes such as `/build/x/ggml-rpc.cpp:653:` or `ggml-rpc.cpp:653:`. */
    private val sourcePrefix = Regex("""(?:[\w.+\-]*/)*[\w.+\-]+\.(?:cpp|cc|c|h|hpp|cu|cuh|m|mm):\d+:?\s*""")

    /** Blocking TCP connect; call from a background dispatcher. */
    fun probe(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ProbeResult {
        val start = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            ProbeResult(true, (System.nanoTime() - start) / 1_000_000L, null)
        } catch (error: Exception) {
            ProbeResult(false, null, error.javaClass.simpleName)
        }
    }

    fun splitAddress(address: String): Pair<String, Int>? {
        val host = address.substringBeforeLast(':', "").trim()
        val port = address.substringAfterLast(':', "").trim().toIntOrNull()
        return if (host.isNotEmpty() && port != null && port in 1..65535) host to port else null
    }

    /** Probes every `host:port` in parallel. A malformed address counts as unreachable. */
    suspend fun probeAll(
        addresses: List<String>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        probeFn: (String, Int, Int) -> ProbeResult = { host, port, timeout -> probe(host, port, timeout) }
    ): Map<String, ProbeResult> = coroutineScope {
        addresses.distinct().map { address ->
            async(Dispatchers.IO) {
                val parts = splitAddress(address)
                address to if (parts == null) {
                    ProbeResult(false, null, "IllegalArgumentException")
                } else {
                    probeFn(parts.first, parts.second, timeoutMs)
                }
            }
        }.awaitAll().toMap()
    }

    /**
     * Preflight: null when every endpoint answers, else the readable message that blocks the launch.
     */
    suspend fun preflightFailure(
        addresses: List<String>,
        nameFor: (String) -> String,
        line: (name: String, address: String) -> String,
        hint: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        probeFn: (String, Int, Int) -> ProbeResult = { host, port, timeout -> probe(host, port, timeout) }
    ): String? {
        val failed = unreachable(probeAll(addresses, timeoutMs, probeFn))
        return if (failed.isEmpty()) null else unreachableMessage(failed, nameFor, line, hint)
    }

    fun unreachable(results: Map<String, ProbeResult>): List<String> =
        results.filterValues { !it.reachable }.keys.toList()

    /** Inline text for the "Test connection" button. */
    fun formatProbe(result: ProbeResult, reachable: (Long) -> String, notReachable: String): String =
        if (result.reachable) reachable(result.ms ?: 0L) else notReachable

    /** Drops the source-file prefix and any build path from a raw log line. */
    fun sanitizeLine(line: String): String = line.replace(sourcePrefix, "").trim()

    /** `host:port` from a `Failed to connect to host:port` line, or null. */
    fun parseConnectFailure(line: String): String? {
        val match = connectFailure.find(line) ?: return null
        return "${match.groupValues[1]}:${match.groupValues[2]}"
    }

    /** Unreachable-helper message: one line per helper, then the shared hint. */
    fun unreachableMessage(
        addresses: List<String>,
        nameFor: (String) -> String,
        line: (name: String, address: String) -> String,
        hint: String
    ): String = addresses.joinToString("\n") { line(nameFor(it), it) } + "\n" + hint

    /**
     * Classifies the end of a llama-server run. A connect failure in the output wins over the exit
     * code; exit 134 with no known cause becomes [DistributedRuntimeFailure.Abort].
     */
    fun classifyFailure(lines: List<String>, exitCode: Int?): DistributedRuntimeFailure? {
        lines.asReversed().firstNotNullOfOrNull(::parseConnectFailure)?.let {
            return DistributedRuntimeFailure.Unreachable(it)
        }
        if (exitCode == EXIT_ABORT) {
            val last = lines.asReversed().map(::sanitizeLine).firstOrNull { it.isNotBlank() }
            return DistributedRuntimeFailure.Abort(last)
        }
        return null
    }

    /** Readable end-of-run message, or null when the failure is not recognised. */
    fun readableRuntimeError(
        lines: List<String>,
        exitCode: Int?,
        nameFor: (String) -> String,
        unreachableLine: (name: String, address: String) -> String,
        hint: String,
        abort: String,
        abortWithLine: (String) -> String
    ): String? = when (val failure = classifyFailure(lines, exitCode)) {
        is DistributedRuntimeFailure.Unreachable ->
            unreachableMessage(listOf(failure.address), nameFor, unreachableLine, hint)
        is DistributedRuntimeFailure.Abort ->
            failure.lastLine?.let(abortWithLine) ?: abort
        null -> null
    }
}
