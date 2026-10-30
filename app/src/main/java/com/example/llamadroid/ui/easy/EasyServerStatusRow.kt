package com.example.llamadroid.ui.easy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.repository.LlamaServerRegistry
import com.example.llamadroid.data.repository.UnifiedServerStatus
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** True when [status] means the Easy model's server process is up or coming up. */
internal fun easyServerIsActive(status: UnifiedServerStatus?): Boolean =
    status == UnifiedServerStatus.RUNNING || status == UnifiedServerStatus.STARTING

/**
 * "Running" / "Stopped" for the Easy card [cardId] with a one-tap Stop while it is active. Nothing
 * is shown when the model has no Easy card yet.
 */
@Composable
internal fun EasyServerStatusRow(cardId: Long?, modifier: Modifier = Modifier) {
    if (cardId == null) return
    val context = LocalContext.current
    val registry = remember(context) { LlamaServerRegistry(context) }
    val scope = rememberCoroutineScope()
    val server by remember(registry, cardId) {
        registry.observeServers(includeEasy = true).map { list -> list.firstOrNull { it.cardId == cardId } }
    }.collectAsState(initial = null)
    val current = server ?: return
    val active = easyServerIsActive(current.status)
    Row(
        modifier.fillMaxWidth().testTag("easy_server_status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(10.dp).background(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
        )
        Text(
            stringResource(if (active) R.string.easy_server_running else R.string.easy_server_stopped),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (active) {
            TextButton(
                onClick = { scope.launch { registry.stop(current) } },
                modifier = Modifier.testTag("easy_server_stop")
            ) { Text(stringResource(R.string.easy_server_stop), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
