package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/** Shared connection editor used by the retained workspace flow and Harness workspace bindings. */
@Composable
fun SshConnectionFields(
    host: String, port: String, user: String, password: String,
    onHostChange: (String) -> Unit, onPortChange: (String) -> Unit,
    onUserChange: (String) -> Unit, onPasswordChange: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(host, onHostChange, label = { Text(stringResource(R.string.ssh_host_label_short)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(port, onPortChange, label = { Text(stringResource(R.string.ssh_port_label_short)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(user, onUserChange, label = { Text(stringResource(R.string.ssh_user_label_short)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(password, onPasswordChange, label = { Text(stringResource(R.string.ssh_password_label_short)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    }
}
