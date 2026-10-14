package com.example.llamadroid.ui.ai.llama.chat

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.harness.HarnessAttachmentViewer
import com.example.llamadroid.harness.HarnessDownloadedFile
import java.io.File
import java.util.Locale

enum class AttachmentKind { IMAGE, TEXT, PDF, ARCHIVE, OTHER }

/** Human-readable size: "950 B", "1.5 KB", "12.4 MB", "1.2 GB". */
fun formatAttachmentSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    return when {
        b < 1024L -> "$b B"
        b < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.1f GB", b / (1024.0 * 1024 * 1024))
    }
}

private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "xz", "bz2", "7z", "rar", "zst")
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "json", "xml", "csv", "log", "kt", "java", "py", "js", "ts", "html", "css", "yaml", "yml", "toml", "sh", "c", "cpp", "h"
)

fun attachmentKindForMime(mime: String, name: String): AttachmentKind {
    val m = mime.trim().lowercase(Locale.ROOT)
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        m.startsWith("image/") -> AttachmentKind.IMAGE
        m == "application/pdf" || ext == "pdf" -> AttachmentKind.PDF
        m.contains("zip") || m.contains("tar") || m.contains("compressed") || m.contains("gzip") ||
            m.contains("7z") || m.contains("rar") || ext in ARCHIVE_EXTENSIONS -> AttachmentKind.ARCHIVE
        m.startsWith("text/") || m == "application/json" || m == "application/xml" || ext in TEXT_EXTENSIONS -> AttachmentKind.TEXT
        else -> AttachmentKind.OTHER
    }
}

private fun kindIcon(kind: AttachmentKind) = when (kind) {
    AttachmentKind.IMAGE -> Icons.Default.Image
    AttachmentKind.TEXT -> Icons.Default.Description
    AttachmentKind.PDF -> Icons.Default.PictureAsPdf
    AttachmentKind.ARCHIVE -> Icons.Default.FolderZip
    AttachmentKind.OTHER -> Icons.Default.AttachFile
}

private fun shareAttachment(context: Context, attachment: LlamaMessageAttachmentEntity) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(attachment.path))
        val send = Intent(Intent.ACTION_SEND)
            .setType(attachment.mimeType.ifBlank { "application/octet-stream" })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, attachment.displayName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Wrapping chips for attachments on a message bubble. Tap opens the viewer; the share icon sends the file. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatAttachmentChips(
    attachments: List<LlamaMessageAttachmentEntity>,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    if (attachments.isEmpty()) return
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<HarnessDownloadedFile?>(null) }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        attachments.forEach { attachment ->
            val file = remember(attachment.path) { File(attachment.path) }
            val exists = remember(attachment.path, attachment.id) { file.isFile }
            AttachmentChip(
                attachment = attachment,
                exists = exists,
                compact = compact,
                onClick = {
                    viewing = HarnessDownloadedFile(file, attachment.displayName, attachment.mimeType.ifBlank { "application/octet-stream" })
                },
                trailing = if (exists) {
                    {
                        IconButton(onClick = { shareAttachment(context, attachment) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.native_chat_attachments_share, attachment.displayName),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else null
            )
        }
    }
    viewing?.let { HarnessAttachmentViewer(download = it, onClose = { viewing = null }) }
}

/** Composer row of pending attachments with remove buttons; scrolls horizontally. */
@Composable
fun PendingAttachmentRow(
    attachments: List<LlamaMessageAttachmentEntity>,
    onRemove: (LlamaMessageAttachmentEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        attachments.forEach { attachment ->
            val exists = remember(attachment.path, attachment.id) { File(attachment.path).isFile }
            AttachmentChip(
                attachment = attachment,
                exists = exists,
                compact = true,
                onClick = null,
                trailing = {
                    IconButton(onClick = { onRemove(attachment) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.native_chat_attachments_remove, attachment.displayName),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: LlamaMessageAttachmentEntity,
    exists: Boolean,
    compact: Boolean,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?
) {
    val kind = remember(attachment.mimeType, attachment.displayName) {
        attachmentKindForMime(attachment.mimeType, attachment.displayName)
    }
    val clickable = exists && onClick != null
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .widthIn(max = if (compact) 200.dp else 260.dp)
            .alpha(if (exists) 1f else 0.5f)
            .let { if (clickable) it.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick!!) else it }
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = if (trailing != null) 0.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (kind == AttachmentKind.IMAGE && exists) {
                AsyncImage(
                    model = File(attachment.path),
                    contentDescription = attachment.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(if (compact) 28.dp else 36.dp).clip(RoundedCornerShape(6.dp))
                )
            } else {
                Icon(kindIcon(kind), contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = attachment.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (exists) formatAttachmentSize(attachment.sizeBytes)
                    else stringResource(R.string.native_chat_attachments_missing),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            trailing?.invoke()
        }
    }
}
