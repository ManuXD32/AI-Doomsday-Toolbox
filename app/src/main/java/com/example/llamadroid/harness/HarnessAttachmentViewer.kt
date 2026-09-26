package com.example.llamadroid.harness

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.harness.HarnessWorkspaceAccess.Companion.readBounded
import com.example.llamadroid.harness.runtime.HarnessEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.UUID

data class HarnessDownloadedFile(val file: File, val name: String, val mediaType: String)

suspend fun decodeHarnessAttachment(context: Context, value: JsonObject): HarnessDownloadedFile = withContext(Dispatchers.IO) {
    val attachment = requireNotNull(value["attachment"]?.jsonObject)
    val encoded = requireNotNull(value["data"]?.jsonPrimitive?.content)
    require(encoded.length <= 24 * 1024 * 1024) { "ATTACHMENT_TOO_LARGE" }
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    require(bytes.size <= 16 * 1024 * 1024) { "ATTACHMENT_TOO_LARGE" }
    val type = attachment["mediaType"]?.jsonPrimitive?.content ?: "application/octet-stream"
    val name = attachment["name"]?.jsonPrimitive?.content ?: "attachment.${if (type == "image/png") "png" else "bin"}"
    cacheHarnessDownload(context, bytes, name, type)
}

suspend fun downloadHarnessFile(context: Context, endpoint: HarnessEndpoint, url: String): HarnessDownloadedFile = withContext(Dispatchers.IO) {
    val target = URI(url)
    val origin = URI(endpoint.origin)
    require(target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port && target.userInfo == null) {
        "DOWNLOAD_ORIGIN_REJECTED"
    }
    val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    client.newCall(Request.Builder().url(url).header("Cookie", endpoint.cookieHeader).build()).execute().use { response ->
        check(response.isSuccessful) { "DOWNLOAD_FAILED" }
        val body = requireNotNull(response.body)
        val bytes = body.byteStream().use { it.readBounded(64 * 1024 * 1024) }
        val filename = Regex("filename=\"?([^\";]+)").find(response.header("Content-Disposition").orEmpty())?.groupValues?.get(1)
            ?: target.path.substringAfterLast('/').ifBlank { "download" }
        cacheHarnessDownload(context, bytes, filename, body.contentType()?.toString()?.substringBefore(';') ?: "application/octet-stream")
    }
}

internal fun cacheHarnessDownload(context: Context, bytes: ByteArray, filename: String, mediaType: String): HarnessDownloadedFile {
    val directory = File(context.cacheDir, "harness_downloads").apply { mkdirs() }
    // Only expired temporary copies are removed; workspace files and exported documents are untouched.
    directory.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60_000L }.forEach { it.delete() }
    val safeName = File(filename).name.replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(150).ifBlank { "attachment" }
    val file = File(directory, UUID.randomUUID().toString() + "-" + safeName).apply { writeBytes(bytes) }
    return HarnessDownloadedFile(file, safeName, mediaType)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HarnessAttachmentViewer(download: HarnessDownloadedFile, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember(download.file) { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(download.mediaType)) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> download.file.inputStream().use { it.copyTo(output) } }
            } }.onFailure { failed = true }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.harness_runtime_close)) }
                    TextButton(onClick = { save.launch(download.name) }) { Text(stringResource(R.string.harness_attachment_save)) }
                    TextButton(onClick = {
                        runCatching {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", download.file)
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, download.mediaType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }.onFailure { failed = true }
                    }) { Text(stringResource(R.string.harness_attachment_open)) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(download.name, style = MaterialTheme.typography.titleMedium)
                    if (failed) Text(stringResource(R.string.harness_attachment_failed))
                    if (download.mediaType.startsWith("image/")) AsyncImage(download.file, contentDescription = download.name)
                    else if (download.mediaType.startsWith("text/")) {
                        Text(remember(download.file) { download.file.inputStream().bufferedReader().use { reader ->
                            val chars = CharArray(24_000)
                            val count = reader.read(chars).coerceAtLeast(0)
                            String(chars, 0, count)
                        } }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
