package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.llamadroid.R
import com.example.llamadroid.sd.SdComponentCandidate
import com.example.llamadroid.sd.SdComponentFit

/**
 * Picker for one SD component role. Never hides installed files: MATCH files first, then an
 * "Other installed files (not checked for this model)" group with a warning colour. The saved path
 * is shown even when it is not in [candidates]. [includedInMain] shows the "not needed" state but
 * stays overridable. [onAddFile] (null = hidden) adds an "Add file" row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentCandidatePicker(
    label: String,
    candidates: List<SdComponentCandidate>,
    selectedPath: String,
    onSelectedPath: (String) -> Unit,
    required: Boolean,
    includedInMain: Boolean,
    onAddFile: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val noneLabel = stringResource(R.string.imagegen_none_builtin)
    val selected = candidates.firstOrNull { it.model.path == selectedPath }
    val selectedName = when {
        selected != null -> selected.model.filename
        selectedPath.isNotBlank() -> selectedPath.substringAfterLast('/')
        includedInMain -> stringResource(R.string.media_picker_included_in_main_short)
        required -> stringResource(R.string.sd_dist_select_model)
        else -> noneLabel
    }
    val matches = candidates.filter { it.fit == SdComponentFit.MATCH }
    val others = candidates.filter { it.fit == SdComponentFit.UNVERIFIED }
    val selectedUnverified = selected?.fit == SdComponentFit.UNVERIFIED
    Column(modifier = modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                singleLine = true,
                isError = required && !includedInMain && selectedPath.isBlank()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (!required || includedInMain) {
                    DropdownMenuItem(
                        text = { Text(noneLabel) },
                        onClick = {
                            onSelectedPath("")
                            expanded = false
                        }
                    )
                }
                matches.forEach { candidate ->
                    CandidateItem(candidate, false) {
                        onSelectedPath(candidate.model.path)
                        expanded = false
                    }
                }
                if (others.isNotEmpty()) {
                    if (matches.isNotEmpty()) HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.media_picker_other_files),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {},
                        enabled = false
                    )
                    others.forEach { candidate ->
                        CandidateItem(candidate, true) {
                            onSelectedPath(candidate.model.path)
                            expanded = false
                        }
                    }
                }
                if (candidates.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.media_picker_no_files), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = {},
                        enabled = false
                    )
                }
                if (onAddFile != null) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.media_picker_add_file), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            expanded = false
                            onAddFile()
                        }
                    )
                }
            }
        }
        val caption = when {
            includedInMain && selectedPath.isBlank() -> stringResource(R.string.media_picker_included_in_main)
            selectedUnverified -> stringResource(R.string.media_picker_unverified_selected)
            else -> null
        }
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = if (selectedUnverified) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CandidateItem(candidate: SdComponentCandidate, warn: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                candidate.model.filename,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        onClick = onClick
    )
}
