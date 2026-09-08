package com.example.llamadroid.ui.audio

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the editable speech draft outside the navigation back stack.
 *
 * The draft can contain a long imported document, so it is kept in an app-private JSON file
 * rather than a SavedStateHandle Bundle. SavedStateHandle remains responsible for the small
 * navigation selections; this store survives a cold process restart without risking transaction
 * size limits.
 */
class AudioWorkspaceDraftStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val atomicFile = AtomicFile(file)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var latestVersion = 0L
    private var latest: AudioWorkspaceDraft? = null
    private val mutableError = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = mutableError.asStateFlow()

    suspend fun load(): AudioWorkspaceDraft? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile && !File("${file.path}.bak").isFile) return@runCatching null
            JSONObject(String(atomicFile.readFully(), Charsets.UTF_8)).toDraft()
        }.getOrNull()
    }

    fun save(draft: AudioWorkspaceDraft) {
        val version: Long
        synchronized(this) {
            latestVersion += 1
            version = latestVersion
            latest = draft
        }
        scope.launch {
            runCatching {
                writeMutex.withLock {
                    if (isLatest(version)) write(draft)
                }
            }.onFailure { error ->
                mutableError.value = error
            }
        }
    }

    fun close() {
        val pending: Pair<Long, AudioWorkspaceDraft>?
        synchronized(this) {
            pending = latest?.let { latestVersion to it }
        }
        if (pending == null) {
            scope.cancel()
            return
        }
        scope.launch {
            runCatching {
                writeMutex.withLock {
                    if (isLatest(pending.first)) write(pending.second)
                }
            }.onFailure { error ->
                mutableError.value = error
            }
        }.invokeOnCompletion { scope.cancel() }
    }

    private fun isLatest(version: Long): Boolean = synchronized(this) { version == latestVersion }

    private fun write(draft: AudioWorkspaceDraft) {
        val stream = atomicFile.startWrite()
        try {
            stream.use { output ->
                output.write(draft.toJson().toString().toByteArray(Charsets.UTF_8))
                output.flush()
                atomicFile.finishWrite(output)
            }
        } catch (error: Throwable) {
            runCatching { atomicFile.failWrite(stream) }
            throw error
        }
    }

    private fun AudioWorkspaceDraft.toJson(): JSONObject = JSONObject().apply {
        put("modelId", modelId)
        put("text", text)
        putNullable("sourceUri", sourceUri)
        putNullable("sourceName", sourceName)
        put("language", language)
        putNullable("voiceProfileId", voiceProfileId)
        putNullable("voiceStyle", voiceStyle)
        putNullable("companionPath", companionPath)
        put("speed", speed)
        put("temperature", temperature)
        put("topP", topP)
        put("topK", topK)
        put("seed", seed)
        put("maxTokens", maxTokens)
        put("totalSteps", totalSteps)
        put("batchSize", batchSize)
        put("microBatchSize", microBatchSize)
        put("outputFormat", outputFormat)
        put("outputSampleRate", outputSampleRate)
        put("normalizeReference", normalizeReference)
        put("denoiseReference", denoiseReference)
        put("trimStartMs", trimStartMs)
        put("trimEndMs", trimEndMs)
        put("runtimeThreads", runtimeThreads)
        put("chunkSize", chunkSize)
        put("componentIds", JSONArray(componentIds))
        put("includeMetadata", includeMetadata)
    }

    private fun JSONObject.toDraft(): AudioWorkspaceDraft = AudioWorkspaceDraft(
        modelId = optString("modelId"),
        text = optString("text"),
        sourceUri = optNullableString("sourceUri"),
        sourceName = optNullableString("sourceName"),
        language = optString("language", "en"),
        voiceProfileId = optNullableString("voiceProfileId"),
        voiceStyle = optNullableString("voiceStyle"),
        companionPath = optNullableString("companionPath"),
        speed = optDouble("speed", 1.0).toFloat(),
        temperature = optDouble("temperature", 0.7).toFloat(),
        topP = optDouble("topP", 0.9).toFloat(),
        topK = optInt("topK", 40),
        seed = optString("seed"),
        maxTokens = optInt("maxTokens", 512),
        totalSteps = optInt("totalSteps", 8),
        batchSize = optInt("batchSize", 1),
        microBatchSize = optInt("microBatchSize", 1),
        outputFormat = optString("outputFormat", "wav"),
        outputSampleRate = optInt("outputSampleRate", 24_000),
        normalizeReference = optBoolean("normalizeReference", true),
        denoiseReference = optBoolean("denoiseReference", false),
        trimStartMs = optInt("trimStartMs", 0),
        trimEndMs = optInt("trimEndMs", 10_000),
        runtimeThreads = optInt("runtimeThreads", 4),
        chunkSize = optInt("chunkSize", 800),
        componentIds = buildList {
            val values = optJSONArray("componentIds") ?: return@buildList
            for (index in 0 until values.length()) values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        },
        includeMetadata = optBoolean("includeMetadata", true)
    )

    private fun JSONObject.putNullable(name: String, value: String?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private companion object {
        const val FILE_NAME = "audio_workspace_draft.json"
    }
}
