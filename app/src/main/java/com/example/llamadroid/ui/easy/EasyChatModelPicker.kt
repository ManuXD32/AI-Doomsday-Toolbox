package com.example.llamadroid.ui.easy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.easy.EasyModelNames
import com.example.llamadroid.data.repository.EasyLlamaChatCoordinator
import com.example.llamadroid.data.repository.EasyModelSwitch
import com.example.llamadroid.data.repository.isEasyLlamaChatModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Picker of installed Easy chat models for the open chat. Picking one prepares that model's Easy
 * server and reports it through [onSwitched]; the caller rebinds the chat and clears its cache.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EasyChatModelPicker(
    currentFilename: String?,
    busy: Boolean,
    onSwitched: (EasyModelSwitch) -> Unit,
    onDismiss: () -> Unit,
    /** Row id the chat is bound to; marks the matching saved server. Null leaves saved servers unmarked. */
    currentServerId: Long? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coordinator = remember(context) { EasyLlamaChatCoordinator(context) }
    val models by remember(context) {
        AppDatabase.getDatabase(context).modelDao().getModelsByTypes(listOf(ModelType.LLM, ModelType.VISION))
            .map { list -> list.filter(::isEasyLlamaChatModel) }
    }.collectAsState(initial = emptyList())
    val savedServers = rememberEasySavedServers()
    var switchingCard by remember { mutableStateOf<Long?>(null) }
    var switching by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { if (switching == null && switchingCard == null) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()
                .testTag("easy_chat_model_picker"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.easy_chat_switch_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.easy_chat_switch_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) {
                Text(stringResource(R.string.easy_chat_switch_busy), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (failed) {
                Text(stringResource(R.string.easy_chat_switch_failed), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (models.isEmpty()) {
                Text(stringResource(R.string.easy_chat_switch_none), style = MaterialTheme.typography.bodyMedium)
            }
            if (savedServers.isNotEmpty()) {
                Text(stringResource(R.string.easy_chat_saved_servers_section), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                EasySavedServerList(savedServers, currentServerId, !busy && switching == null && switchingCard == null,
                    switchingCard, status) { server ->
                    val cardId = server.cardId ?: return@EasySavedServerList
                    switchingCard = cardId; failed = false; status = null
                    scope.launch {
                        try {
                            val switch = coordinator.prepareSavedServer(cardId) { status = it }
                            currentFilename?.let { runCatching { coordinator.stopEasyModelIfIdle(it) } }
                            onSwitched(switch)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Throwable) {
                            failed = true
                        } finally { switchingCard = null; status = null }
                    }
                }
                Text(stringResource(R.string.easy_chat_models_section), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
            }
            models.forEach { model ->
                val curated = EasyModelNames.curatedTitleRes(model.filename)?.let { stringResource(it) }
                val label = EasyModelNames.friendlyName(model.filename, curated)
                val isCurrent = model.filename == currentFilename
                val enabled = !busy && switching == null && switchingCard == null && !isCurrent
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = enabled) {
                            switching = model.filename; failed = false; status = null
                            scope.launch {
                                try {
                                    val switch = coordinator.prepareEasyModel(model.filename) { status = it }
                                    // Two resident LLMs can OOM a phone: free the previous Easy model. Done before
                                    // onSwitched because that closes the sheet and cancels this scope.
                                    currentFilename?.takeIf { it != model.filename }
                                        ?.let { runCatching { coordinator.stopEasyModelIfIdle(it) } }
                                    onSwitched(switch)
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Throwable) {
                                    failed = true
                                } finally { switching = null; status = null }
                            }
                        }
                        .padding(vertical = 8.dp)
                        .testTag("easy_chat_model_option")
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                        color = if (enabled || isCurrent) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    when {
                        isCurrent -> Text(stringResource(R.string.easy_chat_switch_current),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        switching == model.filename -> {
                            CircularProgressIndicator(Modifier.padding(top = 4.dp), strokeWidth = 2.dp)
                            status?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}
