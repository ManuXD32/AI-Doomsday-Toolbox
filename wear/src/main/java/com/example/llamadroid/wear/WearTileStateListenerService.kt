package com.example.llamadroid.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.serialization.decodeFromString

class WearTileStateListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val cache = WearLocalCache(applicationContext)
        val affectedTiles = linkedSetOf<Class<out AdtBaseTileService>>()
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val item = event.dataItem
            val path = item.uri.path.orEmpty()
            if (!path.startsWith(AdtWearProtocol.PREFIX)) return@forEach
            val payload = runCatching {
                DataMapItem.fromDataItem(item).dataMap.getString(AdtWearProtocol.KEY_JSON)
            }.getOrNull() ?: return@forEach
            runCatching {
                when (path) {
                    AdtWearProtocol.SERVER_STATUS -> {
                        cache.writeServer(AdtWearProtocol.json.decodeFromString<LlamaServerSnapshot>(payload))
                        affectedTiles += AdtControlTileService::class.java
                    }
                    AdtWearProtocol.CHAT_LIST -> {
                        cache.writeChats(AdtWearProtocol.json.decodeFromString<ChatListPage>(payload))
                        affectedTiles += AdtChatTileService::class.java
                    }
                    AdtWearProtocol.ORGANIZER_EVENTS -> {
                        cache.writeOrganizerEvents(AdtWearProtocol.json.decodeFromString<OrganizerEventPage>(payload))
                        affectedTiles += AdtCalendarTileService::class.java
                    }
                    AdtWearProtocol.ORGANIZER_EVENTS_MONTH -> {
                        cache.writeOrganizerMonth(AdtWearProtocol.json.decodeFromString<OrganizerMonthPage>(payload))
                        affectedTiles += AdtCalendarTileService::class.java
                    }
                    AdtWearProtocol.PET_CURRENT -> {
                        cache.writePet(AdtWearProtocol.json.decodeFromString<PetSnapshot>(payload))
                        affectedTiles += AdtPetTileService::class.java
                    }
                    AdtWearProtocol.ACTIVE_TASKS -> {
                        cache.writeActiveTasks(AdtWearProtocol.json.decodeFromString<ActiveTaskSnapshot>(payload))
                        affectedTiles += AdtActiveTaskTileService::class.java
                    }
                    AdtWearProtocol.ORGANIZER_PINNED_NOTE -> {
                        cache.writePinnedNote(AdtWearProtocol.json.decodeFromString<OrganizerPinnedNoteResult>(payload))
                        affectedTiles += AdtPinnedNoteTileService::class.java
                    }
                }
            }
        }
        affectedTiles.forEach { tile ->
            runCatching { androidx.wear.tiles.TileService.getUpdater(applicationContext).requestUpdate(tile) }
        }
    }
}
