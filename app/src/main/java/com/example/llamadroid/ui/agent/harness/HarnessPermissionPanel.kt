package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard

@Composable
internal fun HarnessPermissionCard(
    state: HarnessPermissionUiState,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    if (!state.available && state.options.isEmpty() && !state.isLoading) return
    var menuOpen by remember { mutableStateOf(false) }
    val selected = state.options.firstOrNull { it.value == state.currentValue }
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        HarnessSectionHeading(title = stringResource(R.string.harness_permission_title), icon = Icons.Default.Lock)
        Text(stringResource(R.string.harness_permission_description), style = MaterialTheme.typography.bodySmall)
        HarnessChoiceField(
            label = stringResource(R.string.harness_permission_label),
            value = selected?.name ?: state.currentValue ?: stringResource(R.string.harness_choose_value),
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it },
            options = state.options.map { it.name },
            onOptionSelected = { name ->
                state.options.firstOrNull { it.name == name }?.let {
                    onAction(NativeHarnessUiAction.SelectPermissionPreset(it.value))
                }
                menuOpen = false
            },
            enabled = state.options.isNotEmpty() && !state.isLoading
        )
        selected?.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.RefreshPermissionCatalog) },
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Text(stringResource(R.string.harness_refresh_permissions))
        }
    }
}
