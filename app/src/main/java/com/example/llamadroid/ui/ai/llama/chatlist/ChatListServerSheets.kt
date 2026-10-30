package com.example.llamadroid.ui.ai.llama.chatlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavController
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.ui.ai.llama.servers.ServerDetailSheet
import com.example.llamadroid.ui.ai.llama.servers.ServerEditorSheet
import com.example.llamadroid.ui.ai.llama.servers.LowRamStartDialog
import com.example.llamadroid.ui.ai.llama.servers.ServerRegistryViewModel
import com.example.llamadroid.ui.ai.llama.servers.key
import com.example.llamadroid.ui.navigation.Screen

/** Which server sheet (detail or editor) the Chats list currently shows. */
@Stable
class ChatListServerSheetState {
    var detailKey by mutableStateOf<String?>(null)
    var editorOpen by mutableStateOf(false)
    var editingKey by mutableStateOf<String?>(null)
    var initialModel by mutableStateOf<ModelEntity?>(null)

    fun openEditor(editingKey: String? = null, initialModel: ModelEntity? = null) {
        detailKey = null
        this.editingKey = editingKey
        this.initialModel = initialModel
        editorOpen = true
    }

    fun closeEditor() {
        editorOpen = false
        editingKey = null
        initialModel = null
    }
}

/** Hosts [ServerDetailSheet] and [ServerEditorSheet] for the Chats list and strip. */
@Composable
fun ChatListServerSheets(
    state: ChatListServerSheetState,
    viewModel: ServerRegistryViewModel,
    navController: NavController
) {
    val servers by viewModel.servers.collectAsState()
    val editing = state.editingKey?.let { key -> servers.firstOrNull { it.key == key } }

    LowRamStartDialog(viewModel)

    state.detailKey?.let { key ->
        ServerDetailSheet(
            serverKey = key,
            viewModel = viewModel,
            onDismiss = { state.detailKey = null },
            onEdit = { server -> state.openEditor(editingKey = server.key) },
            onOpenChat = { chatId, rowId ->
                state.detailKey = null
                navController.navigate(Screen.LlamaChat.createRoute(chatId, rowId))
            },
            onOpenWebUi = { port ->
                state.detailKey = null
                navController.navigate("chat?port=$port")
            }
        )
    }

    if (state.editorOpen && (state.editingKey == null || editing != null)) {
        ServerEditorSheet(
            viewModel = viewModel,
            editing = editing,
            onDismiss = { state.closeEditor() },
            onManageModels = {
                state.closeEditor()
                navController.navigate(Screen.LLMModels.route)
            },
            initialModel = state.initialModel
        )
    }
}
