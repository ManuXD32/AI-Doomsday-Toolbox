package com.example.llamadroid.ui.agent.harness

internal suspend fun readHarnessSessionProjection(
    hooks: NativeHarnessWorkspaceHooks,
    sessionId: String
): NativeHarnessSessionProjection? = try {
    hooks.readSessionProjection(sessionId)
} catch (_: Throwable) {
    null
}
