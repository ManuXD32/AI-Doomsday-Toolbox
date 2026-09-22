package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard

/**
 * Compact chat entry point for the optional goal editor.
 *
 * The full goal form remains available on demand, but it no longer pushes the
 * first transcript content below the fold on every chat session.
 */
@Composable
internal fun HarnessGoalDisclosure(
    goal: HarnessGoalUi?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    AppSectionCard(
        modifier = Modifier.testTag("harness_goal_disclosure"),
        shape = AppChromeDefaults.InnerCardShape
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(AppChromeDefaults.SectionSpacing)
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.harness_goal_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        goal?.objective?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.harness_goal_collapsed_description),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            OutlinedButton(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (expanded) R.string.harness_goal_hide_editor
                        else R.string.harness_goal_open_editor
                    )
                )
            }
        }
    }
}
