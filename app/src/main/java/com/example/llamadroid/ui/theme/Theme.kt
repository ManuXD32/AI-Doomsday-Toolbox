package com.example.llamadroid.ui.theme

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.example.llamadroid.data.AppThemeMode
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = SoftStudioDarkPrimary,
    onPrimary = SoftStudioDarkOnPrimary,
    primaryContainer = SoftStudioDarkPrimaryContainer,
    onPrimaryContainer = SoftStudioDarkOnPrimaryContainer,
    inversePrimary = SoftStudioDarkInversePrimary,
    secondary = SoftStudioDarkSecondary,
    onSecondary = SoftStudioDarkOnSecondary,
    secondaryContainer = SoftStudioDarkSecondaryContainer,
    onSecondaryContainer = SoftStudioDarkOnSecondaryContainer,
    tertiary = SoftStudioDarkTertiary,
    onTertiary = SoftStudioDarkOnTertiary,
    tertiaryContainer = SoftStudioDarkTertiaryContainer,
    onTertiaryContainer = SoftStudioDarkOnTertiaryContainer,
    background = SoftStudioDarkBackground,
    onBackground = SoftStudioDarkOnBackground,
    surface = SoftStudioDarkSurface,
    onSurface = SoftStudioDarkOnSurface,
    surfaceVariant = SoftStudioDarkSurfaceVariant,
    onSurfaceVariant = SoftStudioDarkOnSurfaceVariant,
    inverseSurface = SoftStudioDarkInverseSurface,
    inverseOnSurface = SoftStudioDarkInverseOnSurface,
    surfaceDim = SoftStudioDarkSurfaceDim,
    surfaceBright = SoftStudioDarkSurfaceBright,
    surfaceContainerLowest = SoftStudioDarkSurfaceContainerLowest,
    surfaceContainerLow = SoftStudioDarkSurfaceContainerLow,
    surfaceContainer = SoftStudioDarkSurfaceContainer,
    surfaceContainerHigh = SoftStudioDarkSurfaceContainerHigh,
    surfaceContainerHighest = SoftStudioDarkSurfaceContainerHighest,
    surfaceTint = SoftStudioDarkSurfaceTint,
    outline = SoftStudioDarkOutline,
    outlineVariant = SoftStudioDarkOutlineVariant,
    scrim = SoftStudioDarkScrim
)

private val LightColorScheme = lightColorScheme(
    primary = SoftStudioLightPrimary,
    onPrimary = SoftStudioLightOnPrimary,
    primaryContainer = SoftStudioLightPrimaryContainer,
    onPrimaryContainer = SoftStudioLightOnPrimaryContainer,
    inversePrimary = SoftStudioLightInversePrimary,
    secondary = SoftStudioLightSecondary,
    onSecondary = SoftStudioLightOnSecondary,
    secondaryContainer = SoftStudioLightSecondaryContainer,
    onSecondaryContainer = SoftStudioLightOnSecondaryContainer,
    tertiary = SoftStudioLightTertiary,
    onTertiary = SoftStudioLightOnTertiary,
    tertiaryContainer = SoftStudioLightTertiaryContainer,
    onTertiaryContainer = SoftStudioLightOnTertiaryContainer,
    background = SoftStudioLightBackground,
    onBackground = SoftStudioLightOnBackground,
    surface = SoftStudioLightSurface,
    onSurface = SoftStudioLightOnSurface,
    surfaceVariant = SoftStudioLightSurfaceVariant,
    onSurfaceVariant = SoftStudioLightOnSurfaceVariant,
    inverseSurface = SoftStudioLightInverseSurface,
    inverseOnSurface = SoftStudioLightInverseOnSurface,
    surfaceDim = SoftStudioLightSurfaceDim,
    surfaceBright = SoftStudioLightSurfaceBright,
    surfaceContainerLowest = SoftStudioLightSurfaceContainerLowest,
    surfaceContainerLow = SoftStudioLightSurfaceContainerLow,
    surfaceContainer = SoftStudioLightSurfaceContainer,
    surfaceContainerHigh = SoftStudioLightSurfaceContainerHigh,
    surfaceContainerHighest = SoftStudioLightSurfaceContainerHighest,
    surfaceTint = SoftStudioLightSurfaceTint,
    outline = SoftStudioLightOutline,
    outlineVariant = SoftStudioLightOutlineVariant,
    scrim = SoftStudioLightScrim
)

private val SoftStudioShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun LlamaDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+. The app palette is the
    // default so the Soft Studio identity is stable across devices.
    dynamicColor: Boolean = false,
    themeMode: AppThemeMode? = null,
    content: @Composable () -> Unit
) {
    val resolvedDarkTheme = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM, null -> darkTheme
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (resolvedDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        resolvedDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val activity = LocalActivity.current
    val view = LocalView.current
    if (activity != null && !view.isInEditMode) {
        SideEffect {
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !resolvedDarkTheme
                isAppearanceLightNavigationBars = !resolvedDarkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = SoftStudioShapes,
        content = content
    )
}
