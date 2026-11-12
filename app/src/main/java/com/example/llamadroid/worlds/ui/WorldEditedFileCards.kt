package com.example.llamadroid.worlds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.llamadroid.worlds.WorldWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One "latest version" card per `.html` workspace file that the reply's tool steps wrote or edited and
 * that is still a valid 3D world. Shows the file's current content, not the content at edit time.
 */
@Composable
fun WorldEditedFileCards(
    stepsJson: String?,
    messageId: Long,
    chatId: Long,
    modifier: Modifier = Modifier
) {
    if (stepsJson.isNullOrBlank() || chatId <= 0) return
    val context = LocalContext.current
    var files by remember(messageId, stepsJson) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(messageId, stepsJson, chatId) {
        files = withContext(Dispatchers.IO) {
            WorldWorkspace.editedHtmlPaths(stepsJson).take(MAX_FILE_CARDS).mapNotNull { path ->
                WorldWorkspace.readWorldFile(context, chatId, path)?.let { path to it }
            }
        }
    }
    if (files.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEachIndexed { index, (path, text) ->
            WorldChatCard(
                content = text,
                messageId = messageId,
                chatId = chatId,
                workspacePath = path,
                sessionKey = -(messageId * 16 + index + 1)
            )
        }
    }
}

private const val MAX_FILE_CARDS = 4
