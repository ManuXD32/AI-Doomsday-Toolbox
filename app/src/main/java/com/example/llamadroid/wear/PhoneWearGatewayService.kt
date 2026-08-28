package com.example.llamadroid.wear

import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class PhoneWearGatewayService : WearableListenerService() {
    override fun onCreate() {
        super.onCreate()
        PhoneWearGateway.start(applicationContext)
    }

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray> =
        PhoneWearGateway.handleRequest(applicationContext, nodeId, path, request)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        PhoneWearGateway.handleMessage(applicationContext, messageEvent)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                PhoneWearGateway.handleDataItem(applicationContext, event.dataItem)
            }
        }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        PhoneWearGateway.handleCapabilityChanged(applicationContext, capabilityInfo)
    }
}
