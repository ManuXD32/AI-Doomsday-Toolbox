package com.example.llamadroid.ui.chat

import com.example.llamadroid.ui.ai.llama.RunningLlamaChatServerUi

private const val MIN_CHAT_SERVER_PORT = 1
private const val MAX_CHAT_SERVER_PORT = 65535

/**
 * Resolves the port used when a Chat destination is first composed.
 *
 * A saved selection represents an explicit choice made in Chat and therefore wins on later
 * recompositions. On a fresh entry, the route override wins over the general setting. Keeping
 * this pure makes it possible to protect the route contract without coupling it to Compose.
 */
internal fun resolveChatServerPort(
    routePortOverride: Int?,
    savedSelectedPort: Int?,
    configuredPort: Int
): Int {
    return (savedSelectedPort ?: routePortOverride ?: configuredPort)
        .coerceIn(MIN_CHAT_SERVER_PORT, MAX_CHAT_SERVER_PORT)
}

/** Returns the live card matching the selected port, without changing the selected port. */
internal fun runningChatServerForPort(
    selectedPort: Int,
    runningServers: List<RunningLlamaChatServerUi>
): RunningLlamaChatServerUi? = runningServers.firstOrNull { it.port == selectedPort }
