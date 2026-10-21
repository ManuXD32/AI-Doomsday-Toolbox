package com.example.llamadroid.ui.filestation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

enum class PasswordDialogMode { SET, CHANGE, REMOVE }

/** Set / change / remove the File Station password. Changing or removing requires the current one. */
@Composable
fun FileStationPasswordDialog(
    mode: PasswordDialogMode,
    verifyCurrent: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSet: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val needsCurrent = mode != PasswordDialogMode.SET
    val needsNew = mode != PasswordDialogMode.REMOVE

    fun submit() {
        if (needsCurrent && !verifyCurrent(current)) { error = R.string.fs_pw_wrong; return }
        if (needsNew) {
            if (first.length < 8) { error = R.string.fs_pw_short; return }
            if (first != second) { error = R.string.fs_pw_mismatch; return }
            onSet(first)
        } else {
            onRemove()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (mode) {
                        PasswordDialogMode.SET -> R.string.fs_password_set
                        PasswordDialogMode.CHANGE -> R.string.fs_password_change
                        PasswordDialogMode.REMOVE -> R.string.fs_password_remove
                    }
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (mode == PasswordDialogMode.REMOVE) Text(stringResource(R.string.fs_pw_remove_message))
                if (needsCurrent) PasswordField(current, R.string.fs_pw_current) { current = it; error = null }
                if (needsNew) {
                    PasswordField(first, R.string.fs_pw_new) { first = it; error = null }
                    PasswordField(second, R.string.fs_pw_confirm) { second = it; error = null }
                }
                error?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit) {
                Text(
                    stringResource(
                        if (mode == PasswordDialogMode.REMOVE) R.string.fs_remove else R.string.fs_save
                    )
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.fs_cancel)) } }
    )
}

@Composable
private fun PasswordField(value: String, label: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
}
