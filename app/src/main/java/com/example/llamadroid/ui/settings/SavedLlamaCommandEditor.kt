package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.SavedCommand
import com.example.llamadroid.data.db.launchProfile
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.ProcessController
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog

internal fun validSavedLlamaArguments(flags: String, template: String): Boolean = runCatching {
    val parser = ProcessController()
    parser.splitCommandLine(flags)
    parser.splitCommandLine(template)
}.isSuccess

/** The same saved-profile editor is reachable from LLM Settings and managed server cards. */
@Composable
fun SavedLlamaCommandEditor(
    command: SavedCommand,
    onDismiss: () -> Unit,
    onSave: (String, LlamaServerLaunchProfile) -> Unit
) {
    val profile = remember(command.id, command.launchProfileJson) { command.launchProfile() }
    var name by remember(command.id) { mutableStateOf(command.name) }
    var flags by remember(command.id) { mutableStateOf(profile.customFlags.orEmpty()) }
    var template by remember(command.id) { mutableStateOf(profile.commandTemplate.orEmpty()) }
    val valid = remember(flags, template) { validSavedLlamaArguments(flags, template) }
    WalkthroughAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.easy_server_arguments)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.easy_server_arguments_help))
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dist_command_preset_name)) }, singleLine = true)
                OutlinedTextField(flags, { flags = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dist_advanced_custom_flags)) }, minLines = 2, maxLines = 5,
                    isError = !valid,
                    supportingText = { Text(stringResource(if (valid) R.string.easy_server_arguments_example
                        else R.string.easy_server_arguments_invalid)) })
                AppAdvancedSection(title = stringResource(R.string.command_template_title),
                    initiallyExpanded = template.isNotBlank()) {
                    Text(stringResource(R.string.easy_server_template_help))
                    OutlinedTextField(template, { template = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.command_template_label)) }, minLines = 3, maxLines = 6,
                        supportingText = { Text(stringResource(R.string.command_template_placeholders)) })
                }
                Text(stringResource(R.string.easy_server_arguments_restart))
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name.trim(), profile.copy(customFlags = flags.takeIf(String::isNotBlank),
                commandTemplate = template.takeIf(String::isNotBlank))) }, enabled = name.isNotBlank() && valid) {
                Text(stringResource(R.string.dist_save_command))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
