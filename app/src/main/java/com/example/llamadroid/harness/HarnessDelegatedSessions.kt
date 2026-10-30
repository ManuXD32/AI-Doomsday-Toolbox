package com.example.llamadroid.harness

import java.util.concurrent.ConcurrentHashMap

/**
 * Harness sessions owned by a DeepSeek delegation subagent. `HarnessAppRuntime.onNotice` asks
 * [suppresses] so the routine completion/question notifications for these sessions go to the
 * subagent instead of the user. With [register]'s `suppressApprovals = false` (the "Ask me"
 * policy) approval notices are still shown to the user.
 */
object HarnessDelegatedSessions {
    private val sessions = ConcurrentHashMap<String, Boolean>()

    private fun key(runtimeId: String, sessionId: String) = "$runtimeId\u0000$sessionId"

    fun register(runtimeId: String, sessionId: String, suppressApprovals: Boolean) {
        sessions[key(runtimeId, sessionId)] = suppressApprovals
    }

    fun unregister(runtimeId: String, sessionId: String) {
        sessions.remove(key(runtimeId, sessionId))
    }

    /** True when the notice for [sessionId] must not be published to the user. */
    fun suppresses(runtimeId: String, sessionId: String?, isApproval: Boolean): Boolean {
        if (sessionId == null) return false
        val suppressApprovals = sessions[key(runtimeId, sessionId)] ?: return false
        return !isApproval || suppressApprovals
    }

    internal fun clearForTest() = sessions.clear()
}
