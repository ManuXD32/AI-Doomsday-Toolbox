package com.example.llamadroid.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.navigation.Screen

@Composable
fun GenerationQueueHeaderAction(navController: NavController) {
    IconButton(onClick = { navController.navigate(Screen.GenerationQueue.route) }) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.generation_queue_title))
    }
}

/** Keeps both actions readable beside each other or stacked on compact phones. */
@Composable
fun GenerationStartAndQueueButtons(
    startLabel: String,
    startEnabled: Boolean,
    addEnabled: Boolean,
    onStart: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.3f
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = startEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(startLabel, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onAdd, enabled = addEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Queue, contentDescription = null)
                    Text(stringResource(R.string.generation_queue_add), fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = startEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(startLabel, maxLines = 2, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onAdd, enabled = addEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Default.Queue, contentDescription = null)
                    Text(stringResource(R.string.generation_queue_add), maxLines = 2,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
