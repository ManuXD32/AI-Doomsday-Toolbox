package com.example.llamadroid.tama.transfer

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

/** Stable per-install identity used to recognise "my own pet coming home". */
interface DeviceIdentity {
    fun deviceId(): String
    fun deviceName(): String
}

/** Random id persisted in [prefs]; [nameProvider] supplies the user-visible device name. */
class PrefsDeviceIdentity(
    private val prefs: SharedPreferences,
    private val nameProvider: () -> String
) : DeviceIdentity {
    override fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_DEVICE_ID, id) }
        return id
    }

    override fun deviceName(): String = nameProvider()

    private companion object {
        const val KEY_DEVICE_ID = "tama_transfer_device_id"
    }
}
