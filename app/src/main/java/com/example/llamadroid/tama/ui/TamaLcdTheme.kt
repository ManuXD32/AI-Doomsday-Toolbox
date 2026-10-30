package com.example.llamadroid.tama.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Error / warning text on the cream LCD surfaces (>= 4.5:1 on E8E8D0 and D4D4AA). */
internal val TamaErrorText = Color(0xFF9C1C14)

/**
 * Tama "LCD" colour schemes. Every role is set explicitly so Material components placed inside a
 * Tama popup (switches, segmented buttons, chips, dropdown menus, tonal buttons, checkboxes...) get
 * consistent on-colours regardless of the app theme (Soft Studio light/dark or any Pixel variant).
 * All text pairs are >= 4.5:1 and the outline / control roles >= 3:1 (see TamaLcdThemeContrastTest).
 *
 * - [tamaLcdLightScheme]: for the cream popup body (surface E8E8D0).
 * - [tamaLcdDarkScheme]: for the dark footer / title bars (surface 2C2C2C).
 */
internal val tamaLcdLightScheme: ColorScheme = lightColorScheme(
    primary = TamaDark,
    onPrimary = TamaLight,
    primaryContainer = TamaBackground,
    onPrimaryContainer = TamaDark,
    inversePrimary = TamaBackground,
    secondary = TamaAccent,
    onSecondary = TamaLight,
    secondaryContainer = TamaBackground,
    onSecondaryContainer = TamaDark,
    tertiary = TamaMutedText,
    onTertiary = TamaLight,
    tertiaryContainer = TamaBackground,
    onTertiaryContainer = TamaDark,
    background = TamaLight,
    onBackground = TamaDark,
    surface = TamaLight,
    onSurface = TamaDark,
    surfaceVariant = TamaBackground,
    onSurfaceVariant = TamaMutedText,
    surfaceTint = TamaDark,
    inverseSurface = TamaDark,
    inverseOnSurface = TamaLight,
    error = TamaErrorText,
    onError = TamaLight,
    errorContainer = Color(0xFFF2D0C8),
    onErrorContainer = Color(0xFF5C0F0A),
    outline = TamaAccent,
    outlineVariant = Color(0xFF76766A),
    scrim = Color.Black,
    surfaceBright = Color(0xFFF4F4E4),
    surfaceDim = TamaBackground,
    surfaceContainerLowest = Color(0xFFF4F4E4),
    surfaceContainerLow = Color(0xFFEEEEDA),
    surfaceContainer = Color(0xFFE0E0C4),
    surfaceContainerHigh = Color(0xFFD8D8B8),
    surfaceContainerHighest = TamaBackground
)

internal val tamaLcdDarkScheme: ColorScheme = darkColorScheme(
    primary = TamaLight,
    onPrimary = TamaDark,
    primaryContainer = TamaAccent,
    onPrimaryContainer = TamaLight,
    inversePrimary = TamaDark,
    secondary = TamaBackground,
    onSecondary = TamaDark,
    secondaryContainer = Color(0xFF4A4A3C),
    onSecondaryContainer = TamaLight,
    tertiary = Color(0xFFC8C8B0),
    onTertiary = TamaDark,
    tertiaryContainer = Color(0xFF4A4A3C),
    onTertiaryContainer = TamaLight,
    background = TamaDark,
    onBackground = TamaLight,
    surface = TamaDark,
    onSurface = TamaLight,
    surfaceVariant = Color(0xFF3C3C34),
    onSurfaceVariant = TamaBackground,
    surfaceTint = TamaLight,
    inverseSurface = TamaLight,
    inverseOnSurface = TamaDark,
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF5C0F0A),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD4),
    outline = Color(0xFFA8A894),
    outlineVariant = Color(0xFF7A7A6A),
    scrim = Color.Black,
    surfaceBright = Color(0xFF424242),
    surfaceDim = Color(0xFF222222),
    surfaceContainerLowest = Color(0xFF222222),
    surfaceContainerLow = Color(0xFF262626),
    surfaceContainer = Color(0xFF323232),
    surfaceContainerHigh = Color(0xFF3A3A3A),
    surfaceContainerHighest = Color(0xFF424242)
)

/**
 * Re-skins Material components with the Tama LCD palette while keeping the app's shapes and
 * typography (so the Pixel style's stepped shapes / fonts still apply). Popup windows opened
 * inside (dropdown menus, AlertDialogs) inherit the scheme through composition locals.
 */
@Composable
internal fun TamaLcdSurface(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) tamaLcdDarkScheme else tamaLcdLightScheme
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            content()
        }
    }
}

/** Switch colours that keep the thumb visible against the track in both states on LCD surfaces. */
@Composable
internal fun tamaSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = TamaLight,
    checkedTrackColor = TamaDark,
    checkedBorderColor = TamaDark,
    uncheckedThumbColor = TamaAccent,
    uncheckedTrackColor = TamaLight,
    uncheckedBorderColor = TamaAccent
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun tamaOutlinedFieldColors(containerColor: Color = TamaBackground): TextFieldColors {
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = TamaDark,
        unfocusedBorderColor = TamaAccent,
        focusedContainerColor = containerColor,
        unfocusedContainerColor = containerColor,
        cursorColor = TamaDark,
        focusedTextColor = TamaDark,
        unfocusedTextColor = TamaDark,
        focusedLabelColor = TamaDark,
        unfocusedLabelColor = TamaMutedText,
        focusedPlaceholderColor = TamaMutedText,
        unfocusedPlaceholderColor = TamaMutedText,
        focusedTrailingIconColor = TamaDark,
        unfocusedTrailingIconColor = TamaAccent
    )
}

@Composable
internal fun TamaBackendChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = TamaDark, contentColor = TamaLight),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(label, fontWeight = FontWeight.Bold, color = TamaLight)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TamaDark),
            border = BorderStroke(1.dp, TamaAccent),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(label, fontWeight = FontWeight.Bold, color = TamaDark)
        }
    }
}
