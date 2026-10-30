package com.example.llamadroid.ui.easy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.llamadroid.R

/** Easy-mode chat top bar: title, model name and a single options button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EasyChatTopBar(
    title: String,
    modelName: String?,
    onBack: () -> Unit,
    onOptions: () -> Unit,
    /** Tapping the model name opens the model picker. */
    onModelClick: (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = androidx.compose.ui.Modifier.testTag("native_chat_title"))
                if (!modelName.isNullOrBlank()) {
                    Text(
                        modelName + if (onModelClick != null) " \u25BE" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (onModelClick != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (onModelClick != null) {
                            androidx.compose.ui.Modifier.clickable(onClick = onModelClick).testTag("easy_chat_model_name")
                        } else androidx.compose.ui.Modifier
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            IconButton(onClick = onOptions, modifier = androidx.compose.ui.Modifier.testTag("easy_chat_options")) {
                Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.easy_chat_opts_button))
            }
        }
    )
}
