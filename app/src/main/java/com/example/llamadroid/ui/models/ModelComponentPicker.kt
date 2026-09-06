package com.example.llamadroid.ui.models

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.model.library.ModelFamily

/** Component hints share the classifier's canonical roles. Inspection still validates the file. */
@Composable
internal fun ModelComponentPicker(
    family: ModelFamily,
    role: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = stringResource(R.string.model_library_custom_role_label)
) {
    var expanded by remember(family) { mutableStateOf(false) }
    val automatic = stringResource(R.string.model_library_component_auto)
    val choices = modelPromotionChoices(family).distinctBy { it.role }.map {
        it.role to stringResource(it.labelRes)
    }
    // Preserve existing custom metadata, including after a family change, until the user chooses.
    val selected = choices.firstOrNull { it.first == role }?.second
        ?: role.takeIf { it.isNotBlank() }?.let {
            stringResource(R.string.model_library_component_saved, it)
        } ?: automatic
    val entries = listOf("" to automatic) + choices +
        if (role.isNotBlank() && choices.none { it.first == role }) listOf(role to selected) else emptyList()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("model_component_picker").semantics { contentDescription = label }
            ) {
                Text(selected, Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                entries.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        modifier = Modifier.testTag("model_component_option_${value.ifBlank { "auto" }}"),
                        onClick = { onSelect(value); expanded = false }
                    )
                }
            }
        }
    }
}
