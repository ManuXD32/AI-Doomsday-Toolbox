package com.example.llamadroid.wear

import android.app.Activity
import android.os.Bundle

open class OpenHomeTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdtTileBridge.launch(this, MainActivity.ROUTE_HOME)
        finish()
    }
}

class OpenChatsTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdtTileBridge.launch(this, MainActivity.ROUTE_CHATS)
        finish()
    }
}

class OpenPinnedChatOneTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPinnedChatAt(0)
    }
}

class OpenPinnedChatTwoTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPinnedChatAt(1)
    }
}

class OpenCalendarTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdtTileBridge.launch(this, MainActivity.ROUTE_CALENDAR)
        finish()
    }
}

class OpenPinnedNoteChooserTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdtTileBridge.launch(this, MainActivity.ROUTE_CALENDAR)
        finish()
    }
}

class OpenPetTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdtTileBridge.launch(this, MainActivity.ROUTE_PET)
        finish()
    }
}

class OpenTasksTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdtTileBridge.launch(this, MainActivity.ROUTE_TASKS)
        finish()
    }
}

class StartServerTileActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdtTileBridge.sendRpc(
            this,
            AdtWearProtocol.SERVER_START,
            ServerCommandRequest.serializer(),
            ServerCommandRequest(AdtTileBridge.meta()),
            ServerCommandResult.serializer()
        ) { result ->
            result?.snapshot?.let { WearLocalCache(this).writeServer(it) }
            AdtTileBridge.refreshTiles(this)
            finish()
        }
    }
}

class StopServerTileActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdtTileBridge.sendRpc(
            this,
            AdtWearProtocol.SERVER_STOP,
            ServerCommandRequest.serializer(),
            ServerCommandRequest(AdtTileBridge.meta()),
            ServerCommandResult.serializer()
        ) { result ->
            result?.snapshot?.let { WearLocalCache(this).writeServer(it) }
            AdtTileBridge.refreshTiles(this)
            finish()
        }
    }
}

class QuickChatTileActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdtTileBridge.sendRpc(
            this,
            AdtWearProtocol.QUICK_CHAT_CREATE,
            QuickChatCreateRequest.serializer(),
            QuickChatCreateRequest(AdtTileBridge.meta()),
            ChatSummary.serializer()
        ) { summary ->
            if (summary != null) {
                AdtTileBridge.launch(this, MainActivity.ROUTE_CHAT_DETAIL, summary.id)
            } else {
                AdtTileBridge.launch(this, MainActivity.ROUTE_CHATS)
            }
            finish()
        }
    }
}

class CancelTaskTileActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val task = AdtTileCache(this).activeTasks()?.tasks.orEmpty().firstOrNull { it.canCancel }
        if (task == null) {
            AdtTileBridge.launch(this, MainActivity.ROUTE_TASKS)
            finish()
            return
        }
        AdtTileBridge.sendRpc(
            this,
            AdtWearProtocol.TASK_CANCEL,
            TaskCommandRequest.serializer(),
            TaskCommandRequest(AdtTileBridge.meta(), task.taskId),
            CommandAckDto.serializer()
        ) {
            AdtTileBridge.refreshTiles(this)
            AdtTileBridge.launch(this, MainActivity.ROUTE_TASKS)
            finish()
        }
    }
}

class PauseTaskTileActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendTaskCommand(AdtWearProtocol.TASK_PAUSE) { it.canPause }
    }
}

class ResumeTaskTileActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendTaskCommand(AdtWearProtocol.TASK_RESUME) { it.canResume }
    }
}

class RefreshTasksTileActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdtTileBridge.sendRpc(
            this,
            AdtWearProtocol.ACTIVE_TASKS,
            PingRequest.serializer(),
            PingRequest(AdtTileBridge.meta()),
            ActiveTaskSnapshot.serializer()
        ) { snapshot ->
            snapshot?.let { WearLocalCache(this).writeActiveTasks(it) }
            AdtTileBridge.refreshTiles(this)
            finish()
        }
    }
}

private fun Activity.openPinnedChatAt(index: Int) {
    val chat = AdtTileCache(this).chats()?.chats.orEmpty().filter { it.pinned }.getOrNull(index)
    if (chat != null) {
        AdtTileBridge.launch(this, MainActivity.ROUTE_CHAT_DETAIL, chat.id)
    } else {
        AdtTileBridge.launch(this, MainActivity.ROUTE_CHATS)
    }
    finish()
}

private fun Activity.sendTaskCommand(path: String, predicate: (ActiveTaskSummary) -> Boolean) {
    val task = AdtTileCache(this).activeTasks()?.tasks.orEmpty().firstOrNull(predicate)
    if (task == null) {
        AdtTileBridge.launch(this, MainActivity.ROUTE_TASKS)
        finish()
        return
    }
    AdtTileBridge.sendRpc(
        this,
        path,
        TaskCommandRequest.serializer(),
        TaskCommandRequest(AdtTileBridge.meta(), task.taskId),
        CommandAckDto.serializer()
    ) {
        AdtTileBridge.refreshTiles(this)
        AdtTileBridge.launch(this, MainActivity.ROUTE_TASKS)
        finish()
    }
}
