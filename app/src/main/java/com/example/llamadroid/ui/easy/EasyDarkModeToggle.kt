package com.example.llamadroid.ui.easy

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.AppThemeMode
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.easy.EasyModeSettings

/** Easy mode's moon/sun toggle. It writes only the Easy theme, never the advanced one. */
@Composable
fun EasyDarkModeToggle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember(context) { EasyModeSettings(context) }
    val mode by settings.themeModeFlow.collectAsState()
    ThemeModeToggleButton(mode, { settings.themeMode = it }, modifier.testTag("easy_dark_mode_toggle"))
}

/** Advanced mode's moon/sun toggle (Home tab). It writes only the advanced theme setting. */
@Composable
fun AdvancedDarkModeToggle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember(context) { SettingsRepository(context) }
    val mode by settings.themeMode.collectAsState()
    ThemeModeToggleButton(mode, settings::setThemeMode, modifier.testTag("advanced_dark_mode_toggle"))
}

/** The icon reflects the effective theme, including SYSTEM; a tap picks the opposite explicitly. */
@Composable
private fun ThemeModeToggleButton(mode: AppThemeMode, onSelect: (AppThemeMode) -> Unit, modifier: Modifier = Modifier) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> systemDark
    }
    IconButton(
        onClick = { onSelect(if (dark) AppThemeMode.LIGHT else AppThemeMode.DARK) },
        modifier = modifier
    ) {
        Icon(
            if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
            stringResource(if (dark) R.string.easy_mode_shell_switch_to_light else R.string.easy_mode_shell_switch_to_dark)
        )
    }
}
