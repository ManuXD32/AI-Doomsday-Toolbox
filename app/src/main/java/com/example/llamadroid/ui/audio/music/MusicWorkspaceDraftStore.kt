package com.example.llamadroid.ui.audio.music

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** One application-owned state/writer per workspace, shared by catalog shortcuts and creation UI. */
class MusicWorkspaceDraftStore private constructor(context: Context, private val kind: String) {
    init { require(kind in setOf("music", "sfx")) }
    private val file = AtomicFile(File(context.applicationContext.filesDir, "audio_${kind}_draft.json"))
    private val ioMutex = Mutex()
    private val latest = MutableStateFlow<MusicWorkspaceDraft?>(null)
    val draft = latest.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<MusicWorkspaceDraft>(Channel.CONFLATED)
    private val mutableError = MutableStateFlow(false)
    val error = mutableError.asStateFlow()
    private val writer = scope.launch {
        for (draft in pending) ioMutex.withLock {
            var output: java.io.FileOutputStream? = null
            try {
                output = file.startWrite()
                output.write(draft.toJson().toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
                mutableError.value = false
            } catch (cancelled: CancellationException) {
                file.failWrite(output)
                throw cancelled
            } catch (_: Exception) {
                file.failWrite(output)
                mutableError.value = true
            }
        }
    }
    suspend fun load(): MusicWorkspaceDraft {
        latest.value?.let { return it }
        val loaded = withContext(Dispatchers.IO) { ioMutex.withLock {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) MusicWorkspaceDraft(kind)
            else MusicWorkspaceDraft.fromJson(kind, JSONObject(file.openRead().bufferedReader().use { it.readText() }))
        } }
        latest.compareAndSet(null, loaded)
        return latest.value!!
    }
    fun save(draft: MusicWorkspaceDraft) {
        require(draft.kind == kind)
        latest.value = draft
        if (pending.trySend(draft).isFailure) mutableError.value = true
    }
    companion object {
        private val stores = java.util.concurrent.ConcurrentHashMap<String, MusicWorkspaceDraftStore>()
        fun get(context: Context, kind: String): MusicWorkspaceDraftStore =
            stores.computeIfAbsent("${context.applicationContext.filesDir}:$kind") {
                MusicWorkspaceDraftStore(context.applicationContext, kind)
            }
    }
}
