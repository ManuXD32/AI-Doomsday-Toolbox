package com.example.llamadroid.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppTaskActionFooter

/** The complete editable plan remains reachable on short phones with the keyboard open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPlanEditorDialog(
    text: String,
    onTextChange: (String) -> Unit,
    saving: Boolean,
    hasChanges: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            topBar = { TopAppBar(
                    actions = { com.example.llamadroid.ui.walkthrough.FeatureGuideAction() },title = { Text(stringResource(R.string.studio_plan_title)) }) },
            bottomBar = {
                AppTaskActionFooter {
                    Button(onClick = onSave, enabled = !saving && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (saving) R.string.studio_plan_saving else R.string.studio_plan_save))
                    }
                    OutlinedButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(stringResource(R.string.studio_plan_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    OutlinedTextField(
                        value = text, onValueChange = onTextChange, enabled = !saving,
                        label = { Text(stringResource(R.string.studio_plan_label)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 480.dp),
                        minLines = 8, maxLines = 20
                    )
                }
                if (hasChanges) item {
                    Text(stringResource(R.string.studio_plan_edited),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
