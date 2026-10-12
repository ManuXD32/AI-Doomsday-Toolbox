package com.example.llamadroid.ui.ai.llama

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.repository.LlamaServerCardRepository
import com.example.llamadroid.data.repository.RoomGeneralSavedCommandProvider
import com.example.llamadroid.service.LlamaServerSessionSnapshot
import com.example.llamadroid.service.LlamaServerSessionStateStore
import com.example.llamadroid.service.LlamaServerSessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

data class RunningLlamaChatServerUi(
    val sessionId: String,
    val name: String,
    val port: Int
)

/**
 * Reads the independently managed server sessions used by the AI Hub Chat tile.
 *
 * This is intentionally state-only: the hub owns the tile and picker UI, while the dedicated
 * server screen owns cards and logs.
 */
@Composable
fun rememberRunningLlamaChatServers(): List<RunningLlamaChatServerUi> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val cardRepository = remember(database) {
        LlamaServerCardRepository(
            database.llamaServerCardDao(),
            RoomGeneralSavedCommandProvider(database.savedCommandDao())
        )
    }
    val cards by cardRepository.cards.collectAsState(initial = emptyList())
    val stateStore = remember(context) { LlamaServerSessionStateStore(context) }
    var snapshots by remember { mutableStateOf<Map<String, LlamaServerSessionSnapshot>>(emptyMap()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            snapshots = stateStore.readAll().associateBy { it.sessionId }
            delay(750L)
        }
    }

    val runningServers = remember(snapshots, cards) {
        snapshots.values
            .filter { it.status == LlamaServerSessionStatus.RUNNING && it.port != null }
            .mapNotNull { session ->
                val card = cards.firstOrNull { it.sessionId == session.sessionId }
                    ?: return@mapNotNull null
                RunningLlamaChatServerUi(
                    sessionId = session.sessionId,
                    name = card.name,
                    port = requireNotNull(session.port)
                )
            }
            .distinctBy { it.sessionId }
    }

    return runningServers
}
