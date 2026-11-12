package com.example.llamadroid.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.service.LlamaReasoning

/** Label for a reasoning mode; [chat] selects the per-chat "Template default" wording instead of "Auto". */
@Composable
internal fun reasoningModeLabel(mode: String, chat: Boolean = false): String = stringResource(
    when (mode) {
        LlamaReasoning.MODE_ON -> R.string.reasoning_mode_on
        LlamaReasoning.MODE_OFF -> R.string.reasoning_mode_off
        else -> if (chat) R.string.chat_thinking_mode_default else R.string.reasoning_mode_auto
    }
)

@Composable
internal fun reasoningEffortLabel(effort: String): String = stringResource(
    when (effort) {
        "minimal" -> R.string.reasoning_effort_minimal
        "low" -> R.string.reasoning_effort_low
        "medium" -> R.string.reasoning_effort_medium
        "high" -> R.string.reasoning_effort_high
        "xhigh" -> R.string.reasoning_effort_xhigh
        "max" -> R.string.reasoning_effort_max
        else -> R.string.reasoning_effort_default
    }
)
