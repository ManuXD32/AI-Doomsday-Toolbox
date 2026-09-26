package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonObject

/** Converts the official control-stream job values into bounded native rows. */
internal fun parseHarnessJob(value: JsonObject, hooks: NativeHarnessJobHooks): HarnessJobUi = HarnessJobUi(
    id = value.string("id") ?: "unknown-job",
    title = value.string("label") ?: value.string("kind") ?: "Job",
    statusLabel = value.string("status") ?: "unknown",
    detail = value.string("detail"),
    canRead = hooks.canRead,
    canKill = hooks.canKill && (value.string("status") == "running" || value.string("status") == "stopping")
)

internal fun parseHarnessJobsForSession(
    jobsBySession: JsonObject,
    sessionId: String,
    hooks: NativeHarnessJobHooks
): List<HarnessJobUi> = jobsBySession[sessionId]
    ?.jsonArrayOrNull()
    ?.mapNotNull { it.jsonObjectOrNull()?.let { value -> parseHarnessJob(value, hooks) } }
    .orEmpty()
