package com.example.llamadroid.ui.ai.llama

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppAdvancedSection

/** Lives in the manager's outer scroll surface; no separate setup catalog. */
@Composable
internal fun LlamaServerSetupGuide(onOpenSettings: () -> Unit) {
    AppAdvancedSection(title = stringResource(R.string.easy_server_guide_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.easy_server_guide_create))
            Text(stringResource(R.string.easy_server_guide_start))
            Text(stringResource(R.string.easy_server_guide_arguments))
            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.easy_server_open_settings))
            }
        }
    }
}
