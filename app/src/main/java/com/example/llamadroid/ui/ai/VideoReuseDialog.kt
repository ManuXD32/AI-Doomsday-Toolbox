package com.example.llamadroid.ui.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.*
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VideoReuseDialog(
    metadata: GeneratedVideoMetadata,
    navController: NavController,
    onDismiss: () -> Unit,
    onApplied: () -> Unit,
    onReuseLocal: ((com.example.llamadroid.service.VideoReusePayload, Map<String, String>) -> Unit)?,
    localDraftEdited: Boolean?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var localEdited by remember { mutableStateOf(false) }
    var distributedEdited by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<VideoReuseTarget?>(null) }
    var failed by remember { mutableStateOf(false) }
    var publishedHandoff by remember { mutableStateOf<VideoReuseHandoff?>(null) }
    fun dismiss() {
        scope.launch {
            publishedHandoff?.let { handoff -> withContext(Dispatchers.IO) { VideoReuseHandoffStore.complete(context, handoff) } }
            onDismiss()
        }
    }
    LaunchedEffect(metadata.metadataPath) {
        try {
            val edits = withContext(Dispatchers.IO) {
                (localDraftEdited ?: VideoReuseDraftAdapter.hasMeaningfulLocalDraft(SettingsRepository(context).videoGenerationDraft())) to
                    (AppDatabase.getDatabase(context).sdDistributedDao().getMasterSettings()
                        ?.let(VideoReuseDraftAdapter::hasMeaningfulDistributedDraft) ?: false)
            }
            localEdited = edits.first
            distributedEdited = edits.second
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // If draft inspection fails, require confirmation before either replacement.
            localEdited = true
            distributedEdited = true
            failed = true
        }
        ready = true
    }
    fun apply(target: VideoReuseTarget) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                publishedHandoff = withContext(Dispatchers.IO) { VideoReuseHandoffStore.publish(context, metadata.metadataPath, target) }
                if (target == VideoReuseTarget.LOCAL && onReuseLocal != null) {
                    val payload = VideoReusePayload.fromMetadata(metadata)
                    val sources = resolveVideoReuseSources(context, payload)
                    onReuseLocal(payload, sources)
                    onApplied()
                } else {
                    navController.navigate(if (target == VideoReuseTarget.LOCAL) Screen.VideoGen.route else Screen.SdDistributedRunConfig.route) {
                        launchSingleTop = true
                    }
                    onApplied()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                busy = false
                failed = true
            }
        }
    }
    fun choose(target: VideoReuseTarget) {
        if (if (target == VideoReuseTarget.LOCAL) localEdited else distributedEdited) confirmation = target else apply(target)
    }
    WalkthroughAlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(if (confirmation == null) R.string.video_detail_reuse_destination else R.string.video_detail_replace_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (confirmation != null) {
                    Text(stringResource(R.string.video_detail_replace_body))
                } else {
                    Button(onClick = { choose(VideoReuseTarget.LOCAL) }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.video_detail_reuse_local))
                    }
                    OutlinedButton(onClick = { choose(VideoReuseTarget.DISTRIBUTED) }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.video_detail_reuse_distributed))
                    }
                }
                if (!ready || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.video_detail_reuse_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            confirmation?.let { target ->
                TextButton(onClick = { apply(target) }, enabled = !busy) { Text(stringResource(R.string.video_detail_replace)) }
            }
        },
        dismissButton = { TextButton(onClick = { dismiss() }, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } }
    )
}
