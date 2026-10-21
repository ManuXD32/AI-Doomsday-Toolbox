package com.example.llamadroid.ui.filestation

import androidx.core.net.toUri
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.service.AiServerNetwork
import com.example.llamadroid.service.filestation.FileStationRuntime
import com.example.llamadroid.service.filestation.FileStationSettings
import com.example.llamadroid.service.filestation.FileStationSettingsStore
import com.example.llamadroid.service.filestation.VolumeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PortError { RANGE, BUSY }

/** Screen state holder; every edit persists immediately and signals the running service. */
class FileStationViewModel(application: Application) : AndroidViewModel(application) {
    private val store = FileStationSettingsStore(application)
    val settings: StateFlow<FileStationSettings> = store.settings

    val running = FileStationRuntime.running
    val urls = FileStationRuntime.urls
    val activeJobs = FileStationRuntime.activeJobs
    val lastError = FileStationRuntime.lastError

    private val _portDraft = MutableStateFlow(store.settings.value.port.toString())
    val portDraft: StateFlow<String> = _portDraft.asStateFlow()
    private val _portError = MutableStateFlow<PortError?>(null)
    val portError: StateFlow<PortError?> = _portError.asStateFlow()

    /** Port and LAN flag the running server was started with; null while stopped. */
    private val _applied = MutableStateFlow<Pair<Int, Boolean>?>(null)
    val applied: StateFlow<Pair<Int, Boolean>?> = _applied.asStateFlow()

    private val _ffmpeg = MutableStateFlow(false)
    val ffmpegAvailable: StateFlow<Boolean> = _ffmpeg.asStateFlow()

    init {
        viewModelScope.launch {
            running.collect { isRunning ->
                _applied.value = if (isRunning) store.settings.value.let { it.port to it.lanVisible } else null
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            _ffmpeg.value = runCatching { BinaryRepository(application).getFFmpegBinary() != null }.getOrDefault(false)
        }
    }

    /** Whether port/LAN edits differ from what the running server uses. */
    fun restartNeeded(s: FileStationSettings, applied: Pair<Int, Boolean>?): Boolean =
        applied != null && (applied.first != s.port || applied.second != s.lanVisible)

    fun start() = FileStationRuntime.start(getApplication())
    fun stop() = FileStationRuntime.stop(getApplication())

    private fun changed() = FileStationRuntime.notifySettingsChanged()

    fun setRootFolder(treeUri: String) {
        val current = store.settings.value.rootVolume
        store.upsertVolume(
            VolumeConfig(
                id = current?.id ?: FileStationSettingsStore.ROOT_ID,
                label = if (current != null && current.treeUri == treeUri) current.label else labelFor(treeUri),
                treeUri = treeUri,
                readOnly = current?.readOnly ?: false,
                isRoot = true,
            )
        )
        changed()
    }

    fun setRootReadOnly(value: Boolean) {
        store.settings.value.rootVolume?.let { store.upsertVolume(it.copy(readOnly = value)); changed() }
    }

    fun newExtra(treeUri: String) = VolumeConfig(store.newVolumeId(), labelFor(treeUri), treeUri)

    fun saveVolume(volume: VolumeConfig) { store.upsertVolume(volume); changed() }
    fun removeVolume(id: String) { store.removeVolume(id); changed() }

    fun setLanVisible(value: Boolean) { store.setLanVisible(value); changed() }

    fun onPortDraft(text: String) {
        val digits = text.filter { it.isDigit() }.take(5)
        _portDraft.value = digits
        val port = digits.toIntOrNull()
        if (port == null || port !in FileStationSettingsStore.PORT_RANGE) {
            _portError.value = PortError.RANGE
            return
        }
        _portError.value = null
        val s = store.settings.value
        if (port == s.port) return
        viewModelScope.launch {
            val busy = running.value.not() && !withContext(Dispatchers.IO) {
                AiServerNetwork.canBind(AiServerNetwork.bindHost(s.lanVisible), port)
            }
            if (_portDraft.value.toIntOrNull() != port) return@launch
            if (busy) _portError.value = PortError.BUSY else store.setPort(port)
        }
    }

    fun setPassword(password: String) { store.setPassword(password); changed() }
    fun clearPassword() { store.clearPassword(); changed() }
    fun verifyPassword(password: String): Boolean = store.verifyPassword(password)
    fun signOutEveryone() { store.rotateSecret(); changed() }

    fun setTrashDays(text: String) {
        text.filter { it.isDigit() }.take(4).toIntOrNull()?.let { store.setTrashRetentionDays(it); changed() }
    }

    fun setShowHidden(value: Boolean) { store.setShowHidden(value); changed() }

    private fun labelFor(treeUri: String): String =
        treeUri.toUri().lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() } ?: "/"
}
