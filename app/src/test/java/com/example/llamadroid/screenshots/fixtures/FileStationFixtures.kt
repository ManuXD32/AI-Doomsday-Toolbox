package com.example.llamadroid.screenshots.fixtures

import android.app.Application
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.service.filestation.FileStationRuntime
import com.example.llamadroid.service.filestation.FileStationSettingsStore
import com.example.llamadroid.service.filestation.VolumeConfig

/** File Station running on the LAN with two shared folders and a password; reset in `@After`. */
object FileStationFixtures {
    fun seedRunning(app: Application, l: ShotLocale) {
        val store = FileStationSettingsStore(app)
        store.upsertVolume(
            VolumeConfig(
                id = FileStationSettingsStore.ROOT_ID, label = l.pick("Shared documents", "Documentos compartidos"),
                treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments", isRoot = true
            )
        )
        store.upsertVolume(
            VolumeConfig(
                id = store.newVolumeId(), label = l.pick("Survival Library", "Biblioteca de supervivencia"),
                treeUri = "content://com.android.externalstorage.documents/tree/primary%3ASurvival%20Library", readOnly = true
            )
        )
        store.setPassword("demo-password")
        FileStationRuntime.setLastError(null)
        FileStationRuntime.setUnprotected(false)
        FileStationRuntime.setUrls(listOf("Wi-Fi" to "http://192.168.1.10:8787", "Hotspot" to "http://192.168.43.1:8787"))
        FileStationRuntime.setActiveJobs(1)
        FileStationRuntime.setRunning(true)
    }

    fun reset() {
        FileStationRuntime.reset()
        FileStationRuntime.setLastError(null)
    }
}
