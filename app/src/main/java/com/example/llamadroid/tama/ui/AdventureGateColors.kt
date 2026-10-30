package com.example.llamadroid.tama.ui

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Adventure Gate palette subset shared with the colour helpers (mirrors the private vals in AdventureGateScreen).
internal val GateColorPanel = Color(0xFF251B34)
internal val GateColorAccent = Color(0xFFFFC857)
internal val GateColorText = Color(0xFFF5EEFF)
internal val GateColorDark = Color(0xFF14101F)

/** Dialog / panel text buttons: gold on the purple panel (about 10.6:1) instead of the default primary. */
@Composable
internal fun gateTextButtonColors() = ButtonDefaults.textButtonColors(
    contentColor = GateColorAccent,
    disabledContentColor = GateColorText.copy(alpha = 0.6f)
)

/** Checkbox with a light unchecked outline and a gold checked box on dark gate panels. */
@Composable
internal fun gateCheckboxColors(): CheckboxColors = CheckboxDefaults.colors(
    checkedColor = GateColorAccent,
    uncheckedColor = GateColorText.copy(alpha = 0.78f),
    checkmarkColor = GateColorDark,
    disabledCheckedColor = GateColorAccent.copy(alpha = 0.5f),
    disabledUncheckedColor = GateColorText.copy(alpha = 0.5f)
)
