package com.example.llamadroid.wear

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.tasks.Task

class WearCompanionBridgeService : WearableListenerService() {
    override fun onCreate() {
        super.onCreate()
        WearCompanionBridgeManager.start(applicationContext)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        WearCompanionBridgeManager.handleMessage(applicationContext, messageEvent)
    }

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray> =
        WearCompanionBridgeManager.handleRpcRequest(applicationContext, nodeId, path, request)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                WearCompanionBridgeManager.handleDataItem(applicationContext, event.dataItem)
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        WearCompanionBridgeManager.handleChannel(applicationContext, channel)
    }
}
