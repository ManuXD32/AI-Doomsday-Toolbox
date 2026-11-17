package com.example.llamadroid.service.search

import java.security.SecureRandom

/** Lifecycle of the optional in-app SearXNG instance. */
internal enum class SearxngLocalState { NOT_INSTALLED, INSTALLING, STOPPED, STARTING, RUNNING, FAILED }

internal enum class SearxngLocalEvent {
    INSTALL_REQUESTED, INSTALL_OK, INSTALL_FAILED, START_REQUESTED, HEALTHY, START_FAILED, PROCESS_EXITED,
    STOP_REQUESTED, UNINSTALLED, MARKER_PRESENT, MARKER_MISSING
}

/** Pure transition table; unknown (state, event) pairs keep the current state. */
internal object SearxngLocalStateMachine {
    fun next(state: SearxngLocalState, event: SearxngLocalEvent): SearxngLocalState = when (event) {
        SearxngLocalEvent.INSTALL_REQUESTED ->
            if (state == SearxngLocalState.NOT_INSTALLED || state == SearxngLocalState.FAILED) SearxngLocalState.INSTALLING else state
        SearxngLocalEvent.INSTALL_OK -> if (state == SearxngLocalState.INSTALLING) SearxngLocalState.STOPPED else state
        SearxngLocalEvent.INSTALL_FAILED -> if (state == SearxngLocalState.INSTALLING) SearxngLocalState.FAILED else state
        SearxngLocalEvent.START_REQUESTED -> if (state == SearxngLocalState.STOPPED) SearxngLocalState.STARTING else state
        SearxngLocalEvent.HEALTHY -> if (state == SearxngLocalState.STARTING) SearxngLocalState.RUNNING else state
        SearxngLocalEvent.START_FAILED -> if (state == SearxngLocalState.STARTING) SearxngLocalState.FAILED else state
        SearxngLocalEvent.PROCESS_EXITED ->
            if (state == SearxngLocalState.RUNNING || state == SearxngLocalState.STARTING) SearxngLocalState.STOPPED else state
        SearxngLocalEvent.STOP_REQUESTED ->
            if (state == SearxngLocalState.RUNNING || state == SearxngLocalState.STARTING) SearxngLocalState.STOPPED else state
        SearxngLocalEvent.UNINSTALLED -> SearxngLocalState.NOT_INSTALLED
        // Re-sync from the on-disk marker; never interrupts a running operation.
        SearxngLocalEvent.MARKER_PRESENT -> if (state == SearxngLocalState.NOT_INSTALLED || state == SearxngLocalState.FAILED) SearxngLocalState.STOPPED else state
        SearxngLocalEvent.MARKER_MISSING -> if (state == SearxngLocalState.STOPPED) SearxngLocalState.NOT_INSTALLED else state
    }
}

internal object SearxngLocalConfig {
    const val ENVIRONMENT_ID = "searxng-local"
    const val PROJECT_FOLDER = "searxng-local"
    const val GUEST_HOME = "/workspace/searxng"
    const val GUEST_SCRIPT = "$GUEST_HOME/searxng_local.sh"
    const val DEFAULT_PORT = 18888
    const val IDLE_STOP_MS = 10 * 60 * 1000L
    const val START_TIMEOUT_MS = 90_000L
    const val INSTALL_TIMEOUT_MS = 60 * 60 * 1000L
    const val SERVE_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    const val LOG_TAIL_BYTES = 24 * 1024

    fun baseUrl(port: Int) = "http://127.0.0.1:$port"
    fun healthUrl(port: Int) = "${baseUrl(port)}/healthz"

    /** Command line run through `/bin/sh -c` inside PRoot; env values are validated (digits / hex only). */
    fun command(action: String, port: Int, secret: String?): String {
        require(action == "install" || action == "serve") { "Unknown action" }
        require(port in 1024..65535) { "Port is invalid" }
        val parts = mutableListOf("SEARXNG_HOME=$GUEST_HOME", "SEARXNG_PORT=$port")
        if (secret != null) {
            require(secret.matches(Regex("[0-9a-f]{64}"))) { "Secret must be 64 hex characters" }
            parts += "SEARXNG_SECRET=$secret"
        }
        return parts.joinToString(" ") + " sh $GUEST_SCRIPT $action"
    }

    fun newSecret(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Last [maxBytes] of [text] cut on a line boundary, so the log viewer never starts mid-line. */
    fun tail(text: String, maxBytes: Int = LOG_TAIL_BYTES): String {
        if (text.length <= maxBytes) return text
        val cut = text.takeLast(maxBytes)
        val nl = cut.indexOf('\n')
        return if (nl in 0 until cut.length - 1) cut.substring(nl + 1) else cut
    }

    /** True when the idle window since [lastUsedMs] has elapsed. */
    fun isIdle(nowMs: Long, lastUsedMs: Long, idleMs: Long = IDLE_STOP_MS) = nowMs - lastUsedMs >= idleMs
}
