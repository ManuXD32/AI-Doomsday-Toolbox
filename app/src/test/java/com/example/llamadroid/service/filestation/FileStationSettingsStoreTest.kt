package com.example.llamadroid.service.filestation

import android.content.Context
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FileStationSettingsStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(FileStationSettingsStore.PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
        context.getSharedPreferences(FileStationSettingsStore.LEGACY_PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }

    @Test
    fun defaultsAndRoundTrip() {
        val store = FileStationSettingsStore(context)
        val s = store.settings.value
        assertEquals(9111, s.port)
        assertTrue(s.lanVisible)
        assertEquals(30, s.trashRetentionDays)
        assertFalse(s.showHidden)
        assertTrue(s.volumes.isEmpty())

        store.upsertVolume(VolumeConfig("root", "Docs", "content://tree/a", readOnly = false, isRoot = true))
        store.upsertVolume(VolumeConfig("v1", "Media", "content://tree/b", readOnly = true))
        store.setPort(9200); store.setLanVisible(false); store.setTrashRetentionDays(7); store.setShowHidden(true)

        val again = FileStationSettingsStore(context).settings.value
        assertEquals(2, again.volumes.size)
        assertEquals("Docs", again.rootVolume?.label)
        assertTrue(again.extraVolumes.single().readOnly)
        assertEquals(9200, again.port)
        assertFalse(again.lanVisible)
        assertEquals(7, again.trashRetentionDays)
        assertTrue(again.showHidden)

        store.removeVolume("v1")
        assertEquals(1, store.settings.value.volumes.size)
    }

    @Test
    fun onlyOneRootSurvives() {
        val store = FileStationSettingsStore(context)
        store.upsertVolume(VolumeConfig("root", "A", "content://a", isRoot = true))
        store.upsertVolume(VolumeConfig("v2", "B", "content://b", isRoot = true))
        assertEquals("v2", store.settings.value.rootVolume?.id)
        assertEquals(1, store.settings.value.volumes.count { it.isRoot })
    }

    @Test
    fun legacyMigrationRunsOnce() {
        context.getSharedPreferences(FileStationSettingsStore.LEGACY_PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString("file_server_folder_uri", "content://tree/legacy")
            putInt("file_server_port", 9333)
        }
        val first = FileStationSettingsStore(context).settings.value
        assertEquals("content://tree/legacy", first.rootVolume?.treeUri)
        assertEquals(9333, first.port)

        val store = FileStationSettingsStore(context)
        store.removeVolume("root")
        store.setPort(9400)
        context.getSharedPreferences(FileStationSettingsStore.LEGACY_PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString("file_server_folder_uri", "content://tree/other")
        }
        val third = FileStationSettingsStore(context).settings.value
        assertNull(third.rootVolume)
        assertEquals(9400, third.port)
    }

    @Test
    fun passwordSetVerifyClear() {
        val store = FileStationSettingsStore(context)
        assertFalse(store.settings.value.hasPassword)
        assertNull(store.credentials())
        store.setPassword("hunter2hunter2")
        assertTrue(store.settings.value.hasPassword)
        assertNotNull(store.credentials())
        assertTrue(store.verifyPassword("hunter2hunter2"))
        assertFalse(store.verifyPassword("wrong"))
        assertTrue(FileStationSettingsStore(context).verifyPassword("hunter2hunter2"))
        store.clearPassword()
        assertFalse(store.settings.value.hasPassword)
        assertNull(store.credentials())
        assertFalse(store.verifyPassword("hunter2hunter2"))
    }

    @Test
    fun secretRotates() {
        val store = FileStationSettingsStore(context)
        val s0 = store.sessionSecret()
        assertEquals(s0, FileStationSettingsStore(context).sessionSecret())
        store.setPassword("abcdefgh")
        val s1 = store.sessionSecret()
        assertNotEquals(s0, s1)
        assertNotEquals(s1, store.rotateSecret())
        val s2 = store.sessionSecret()
        store.clearPassword()
        assertNotEquals(s2, store.sessionSecret())
    }
}
