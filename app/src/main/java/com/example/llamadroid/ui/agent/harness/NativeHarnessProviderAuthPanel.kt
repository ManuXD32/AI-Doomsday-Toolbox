package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

@Composable
internal fun HarnessProviderAuthPanel(
    config: HarnessProviderConfigUi,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    val auth = config.auth
    if (auth.methods.isEmpty() && !auth.configured && auth.requestId == null && auth.notices.isEmpty() && auth.prompt == null && auth.errorCode == null) {
        return
    }
    Text(
        stringResource(
            if (auth.configured) R.string.harness_provider_auth_configured
            else R.string.harness_provider_auth_missing
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (auth.busy || auth.status == "pending") {
        Text(
            stringResource(R.string.harness_provider_auth_waiting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (auth.notices.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            auth.notices.forEach { notice ->
                Text(notice.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (auth.configured) {
            OutlinedButton(
                onClick = { onAction(NativeHarnessUiAction.LogoutProvider(config.id)) },
                enabled = !auth.busy
            ) {
                Text(stringResource(R.string.harness_provider_auth_logout))
            }
        } else {
            auth.methods.forEach { method ->
                Button(
                    onClick = { onAction(NativeHarnessUiAction.StartProviderLogin(config.id, method.id)) },
                    enabled = !auth.busy
                ) {
                    Text(stringResource(R.string.harness_provider_auth_login, method.label))
                }
            }
        }
    }
    auth.prompt?.let { prompt ->
        HarnessProviderAuthPrompt(config.id, auth.requestId, prompt, onAction)
    }
    auth.requestId?.let { requestId ->
        if (auth.status == "pending") {
            OutlinedButton(
                onClick = { onAction(NativeHarnessUiAction.CancelProviderLogin(config.id, requestId)) },
                enabled = !auth.busy,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(stringResource(R.string.harness_provider_auth_cancel))
            }
        }
    }
}

@Composable
private fun HarnessProviderAuthPrompt(
    providerId: String,
    requestId: String?,
    prompt: HarnessProviderAuthPromptUi,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    val id = requestId ?: return
    var answer by remember(providerId, id, prompt.kind, prompt.message) { mutableStateOf("") }
    LaunchedEffect(providerId, id, prompt.kind, prompt.message) { answer = "" }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(prompt.message, style = MaterialTheme.typography.bodyMedium)
        if (prompt.kind == "select" && prompt.options.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                prompt.options.forEach { option ->
                    OutlinedButton(onClick = { answer = option.id }) {
                        Text(option.label)
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it.take(16_384) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_provider_auth_answer_label)) },
                placeholder = { prompt.placeholder?.let { Text(it) } },
                visualTransformation = if (prompt.kind == "secret") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                singleLine = true
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onAction(NativeHarnessUiAction.AnswerProviderLogin(providerId, id, answer)) },
                enabled = answer.isNotBlank()
            ) {
                Text(stringResource(R.string.harness_provider_auth_submit))
            }
            OutlinedButton(
                onClick = { onAction(NativeHarnessUiAction.AnswerProviderLogin(providerId, id, "", declined = true)) }
            ) {
                Text(stringResource(R.string.harness_provider_auth_decline))
            }
        }
    }
}
