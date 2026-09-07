package com.example.llamadroid.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SharedFileHolder
import com.example.llamadroid.data.SharedFileTarget
import com.example.llamadroid.service.GeneratedVideoMetadata
import com.example.llamadroid.service.VideoGenerationService
import com.example.llamadroid.service.VideoGenerationStateHolder
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shared by the video tool, distributed gallery and the all-media index. */
@Composable
fun VideoGalleryDetail(
    metadata: GeneratedVideoMetadata,
    navController: NavController,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
    onReuseLocal: ((com.example.llamadroid.service.VideoReusePayload, Map<String, String>) -> Unit)? = null,
    localDraftEdited: Boolean? = null
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showReuse by remember { mutableStateOf(false) }
    fun reportFailure(error: Throwable) {
        Toast.makeText(context, resources.getString(R.string.video_gen_share_failed, error.localizedMessage.orEmpty()), Toast.LENGTH_LONG).show()
    }
    fun openTool(target: SharedFileTarget, route: String, sourceTag: String? = null) {
        SharedFileHolder.setPendingFile(Uri.fromFile(File(metadata.preferredArtifactPath)),
            videoMimeType(File(metadata.preferredArtifactPath)), target, sourceTag = sourceTag)
        onDismiss()
        navController.navigate(route)
    }
    VideoDetailDialog(
        metadata = metadata,
        onDismiss = onDismiss,
        onShare = {
            runCatching {
                val file = File(metadata.preferredArtifactPath)
                check(file.exists()) { resources.getString(R.string.video_gen_share_failed_missing) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = videoMimeType(file)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, resources.getString(R.string.video_gen_share_chooser)))
            }.onFailure(::reportFailure)
        },
        onInterpolate = { openTool(SharedFileTarget.VIDEO_INTERPOLATION, Screen.VideoInterpolation.route) },
        onUpscale = { openTool(SharedFileTarget.VIDEO_UPSCALER, Screen.VideoUpscaler.route) },
        onInterpolateAndUpscale = { openTool(SharedFileTarget.VIDEO_INTERPOLATION, Screen.Workflows.route, "interpolate_then_upscale") },
        onCopyInfo = {
            runCatching {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                    ClipData.newPlainText(resources.getString(R.string.video_gen_copy_info), buildVideoGenerationInfoText(context, metadata)))
            }.onSuccess {
                Toast.makeText(context, resources.getString(R.string.video_gen_copy_info_success), Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(context, resources.getString(R.string.video_gen_copy_info_failed,
                    error.localizedMessage.orEmpty()), Toast.LENGTH_LONG).show()
            }
        },
        onRetryConversion = {
            runCatching { ContextCompat.startForegroundService(context, VideoGenerationService.createRetryConversionIntent(context, metadata)) }
                .onSuccess { onDismiss() }.onFailure {
                    Toast.makeText(context, resources.getString(R.string.video_output_retry_failed), Toast.LENGTH_LONG).show()
                }
        },
        onDelete = { confirmDelete = true },
        onReuse = { showReuse = true }
    )
    if (confirmDelete) {
        WalkthroughAlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text(stringResource(R.string.video_detail_delete_title)) },
            text = { Text(stringResource(R.string.video_detail_delete_body)) },
            confirmButton = { TextButton(onClick = {
                deleting = true
                scope.launch {
                    val failed = withContext(Dispatchers.IO + NonCancellable) {
                        var failed = false
                        listOf(metadata.exportedAviUri, metadata.exportedMp4Uri, metadata.exportedNativeUri,
                            metadata.exportedMetadataUri, metadata.exportedAudioUri).filterNotNull().distinct().forEach { uri ->
                            runCatching { deleteDocumentUri(context, uri) }.onFailure { failed = true }
                        }
                        // Explicitly confirmed deletion owns this generation's outputs, never inputs or models.
                        listOf(metadata.aviPath, metadata.mp4Path, metadata.nativeOutputPath,
                            metadata.audioSidecarPath, metadata.metadataPath).filterNotNull().filter(String::isNotBlank)
                            .distinct().forEach { path ->
                                runCatching {
                                    val file = File(path)
                                    if (file.exists() && !file.delete()) failed = true
                                }.onFailure { failed = true }
                            }
                        failed
                    }
                    deleting = false
                    confirmDelete = false
                    Toast.makeText(context, resources.getString(if (failed) R.string.video_detail_delete_failed
                        else R.string.video_gen_delete_success), Toast.LENGTH_LONG).show()
                    VideoGenerationStateHolder.txt2vid.removeVideo(metadata)
                    VideoGenerationStateHolder.img2vid.removeVideo(metadata)
                    onDeleted()
                    onDismiss()
                }
            }, enabled = !deleting) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !deleting) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    if (showReuse) {
        VideoReuseDialog(metadata, navController,
            onDismiss = { showReuse = false },
            onApplied = { showReuse = false; onDismiss() },
            onReuseLocal = onReuseLocal,
            localDraftEdited = localDraftEdited)
    }
}
