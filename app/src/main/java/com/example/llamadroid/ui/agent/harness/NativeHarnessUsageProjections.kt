package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Parses the three token-meter projections and whole-session stats together. */
internal fun harnessParseUsageProjections(values: JsonObject): HarnessTokenUsageUi? {
    val tokenUsage = harnessParseTokenUsageProjection(values["tokenUsage"])
    val pressure = harnessParseContextPressureProjection(values["contextPressure"])
    val breakdown = harnessParseContextBreakdownProjection(values["contextBreakdown"])
    val stats = harnessParseSessionStatsProjection(values["sessionStats"])
    if (tokenUsage == null && pressure == null && breakdown == null && stats == null) return null
    return (tokenUsage ?: HarnessTokenUsageUi(inputTokens = 0L, outputTokens = 0L)).copy(
        contextPressure = pressure,
        contextBreakdown = breakdown,
        sessionStats = stats
    )
}

internal fun harnessParseContextPressureProjection(value: JsonElement?): HarnessContextPressureUi? {
    val objectValue = value?.jsonObjectOrNull() ?: return null
    val pressure = objectValue.long("pressureTokens")
    val projected = objectValue.long("projectedTokens")
    val window = objectValue.long("contextWindow")
    if (pressure == null && projected == null && window == null) return null
    return HarnessContextPressureUi(pressure, projected, window)
}

internal fun harnessParseContextBreakdownProjection(value: JsonElement?): HarnessContextBreakdownUi? {
    val objectValue = value?.jsonObjectOrNull() ?: return null
    val system = objectValue.long("systemTokens")
    val tools = objectValue.long("toolsTokens")
    val messages = objectValue.long("messageTokens")
    if (system == null && tools == null && messages == null) return null
    return HarnessContextBreakdownUi(
        systemTokens = system ?: 0L,
        toolsTokens = tools ?: 0L,
        messageTokens = messages ?: 0L
    )
}

internal fun harnessParseSessionStatsProjection(value: JsonElement?): HarnessSessionStatsUi? {
    val objectValue = value?.jsonObjectOrNull() ?: return null
    val values = listOf(
        objectValue.long("turns"),
        objectValue.long("steps"),
        objectValue.long("llmMs"),
        objectValue.long("toolMs"),
        objectValue.long("ttftMs"),
        objectValue.long("ttftSteps"),
        objectValue.long("decodeMs"),
        objectValue.long("decodeTokens")
    )
    if (values.all { it == null }) return null
    return HarnessSessionStatsUi(
        turns = values[0] ?: 0L,
        steps = values[1] ?: 0L,
        llmMs = values[2] ?: 0L,
        toolMs = values[3] ?: 0L,
        ttftMs = values[4] ?: 0L,
        ttftSteps = values[5] ?: 0L,
        decodeMs = values[6] ?: 0L,
        decodeTokens = values[7] ?: 0L
    )
}

internal fun NativeHarnessUiState.withHarnessUsage(
    usage: HarnessTokenUsageUi?
): NativeHarnessUiState = copy(
    tokenUsage = usage,
    contextPressure = usage?.contextPressure,
    contextBreakdown = usage?.contextBreakdown,
    sessionStats = usage?.sessionStats
)

internal fun NativeHarnessUiState.withHarnessProjection(
    key: String?,
    value: JsonElement?
): NativeHarnessUiState = when (key) {
    "tokenUsage" -> copy(
        tokenUsage = harnessParseTokenUsageProjection(value)?.copy(
            contextPressure = contextPressure,
            contextBreakdown = contextBreakdown,
            sessionStats = sessionStats
        )
    )
    "contextPressure" -> copy(contextPressure = harnessParseContextPressureProjection(value))
    "contextBreakdown" -> copy(contextBreakdown = harnessParseContextBreakdownProjection(value))
    "sessionStats" -> copy(sessionStats = harnessParseSessionStatsProjection(value))
    else -> this
}
