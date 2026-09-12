package com.example.llamadroid.ui.audio.music

import android.content.Context
import android.net.Uri
import com.example.llamadroid.R
import com.example.llamadroid.audio.*
import com.example.llamadroid.audio.music.*
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.model.StableAudioModelSupport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID

data class MusicWorkspaceState(
    val draft: MusicWorkspaceDraft,
    val loaded: Boolean = false,
    val choices: List<MusicComponentChoice> = emptyList(),
    val job: AudioGenerationJobEntity? = null,
    val importing: Boolean = false,
    val enqueueing: Boolean = false,
    val error: String? = null,
    val draftError: Boolean = false
) {
    val busy get() = importing || enqueueing || job?.status in setOf(AudioJobStatuses.QUEUED, AudioJobStatuses.PREPARING, AudioJobStatuses.RUNNING, AudioJobStatuses.CANCELLING)
    val canRetry get() = job?.status in setOf(AudioJobStatuses.ERROR, AudioJobStatuses.INTERRUPTED, AudioJobStatuses.CANCELLED)
}

/** Music/SFX share the existing foreground queue; this object only owns UI state. */
class MusicWorkspaceController(context: Context, val kind: String) {
    private val app = context.applicationContext
    private val family = if (kind == "sfx") StableAudio3Ids.FAMILY_SFX else StableAudio3Ids.FAMILY_MUSIC
    private val db = AppDatabase.getDatabase(app)
    private val repository = AudioWorkspaceRepository(app, db)
    private val store = MusicWorkspaceDraftStore.get(app, kind)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(MusicWorkspaceState(MusicWorkspaceDraft(kind)))
    val state = mutableState.asStateFlow()
    private var models: Map<String, ModelEntity> = emptyMap()
    private var selectedJobId: String? = null
    private var importJob: Job? = null

    init {
        scope.launch {
            try {
                store.load()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = app.getString(R.string.audio_music_draft_error)) } }
        }
        scope.launch { store.draft.filterNotNull().collect { draft ->
            mutableState.update { it.copy(draft = draft, loaded = true) }
        } }
        scope.launch { store.error.collect { error -> mutableState.update { it.copy(draftError = error) } } }
        scope.launch {
            db.modelDao().getAllModels().map { rows -> withContext(Dispatchers.IO) {
                val manifest = StableAudio3ManifestLoader.load(app)
                val entry = StableAudio3ModelDoctor.entryFor(
                    manifest,
                    if (kind == "sfx") StableAudio3Kind.SFX else StableAudio3Kind.MUSIC
                )
                rows.filter { row ->
                    val role = StableAudioModelSupport.canonicalRole(row.audioComponentRole)
                    val expected = role?.let { entry.component(it) }
                    expected != null &&
                        StableAudio3ModelDoctor.isCanonicalInstalledModel(
                            if (kind == "sfx") StableAudio3Kind.SFX else StableAudio3Kind.MUSIC,
                            entry,
                            expected,
                            row
                        ) && File(row.path).isFile && File(row.path).canRead()
                }
            } }.catch { error -> if (error is CancellationException) throw error; fail() }.collect { rows ->
                models = rows.associateBy { it.path }
                val choices = rows.mapNotNull { row ->
                    val role = musicComponentRole(row.audioComponentRole) ?: return@mapNotNull null
                    if (role == "dit" && row.audioFamily != family) return@mapNotNull null
                    MusicComponentChoice(row.path, row.filename, role)
                }
                mutableState.update { it.copy(choices = choices) }
            }
        }
        scope.launch {
            try { repository.recoverStaleJobs() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(); return@launch }
            db.audioDao().observeUiJobs(family).catch { error -> if (error is CancellationException) throw error; fail() }
                .collect { jobs ->
                    val selected = selectedJobId?.let { id -> jobs.firstOrNull { it.id == id } }
                        ?: jobs.firstOrNull()
                    mutableState.update { it.copy(job = selected) }
                }
        }
    }

    fun update(draft: MusicWorkspaceDraft) {
        if (!mutableState.value.loaded) return
        mutableState.update { it.copy(draft = draft, error = null) }
        store.save(draft)
    }

    fun useInput(path: String, operation: String) {
        update(state.value.draft.withValue("initAudio", path).withValue("operation", operation))
    }

    fun validationError(): String? {
        if (!state.value.loaded) return app.getString(R.string.audio_music_validating)
        val required = listOf("dit", "textEncoder", "tokenizer", "codecDecoder") +
            if (state.value.draft["operation"] != "generate") listOf("codecEncoder") else emptyList()
        if (required.any { role -> state.value.choices.none { it.role == role && it.path == state.value.draft.components[role] } }) {
            return app.getString(R.string.audio_music_complete_components)
        }
        return try { buildRequest(state.value.draft).validate(requireDigests = true, supportsExtend = true); null }
        catch (_: StableAudio3ValidationException) { app.getString(R.string.audio_music_error_model_stale) }
        catch (_: IllegalArgumentException) { app.getString(R.string.audio_music_invalid_settings) }
        catch (_: IllegalStateException) { app.getString(R.string.audio_music_complete_components) }
    }

    fun generate() {
        if (!state.value.loaded || state.value.busy) return
        mutableState.update { it.copy(enqueueing = true) }
        scope.launch {
            try {
                val draft = state.value.draft
                val request = buildRequest(draft)
                val model = models[request.components.dit.path] ?: error("Selected model is missing")
                val effective = request.validate(supportsExtend = true)
                val metadata = JSONObject().put("stableAudio", effective.toJson()).put("mediaKind", kind)
                selectedJobId = repository.enqueueGeneration(AudioGenerationRequest(
                    model = AudioModelDescriptor(model.filename, family, model.path, displayName = model.filename,
                        adapterId = StableAudio3Ids.ADAPTER_ID),
                    text = effective.prompt, sourceName = effective.prompt.lineSequence().firstOrNull()?.take(80),
                    speed = 1f, seed = effective.seed, runtimeThreads = effective.threads,
                    outputSampleRate = StableAudio3Ids.SAMPLE_RATE, outputFormat = draft["outputFormat"],
                    normalizeReference = false, includeMetadata = draft["includeMetadata"] == "true",
                    metadataJson = metadata.toString()
                ))
                val insertedJob = db.audioDao().getJob(selectedJobId!!)
                mutableState.update { it.copy(error = null, job = insertedJob) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail() }
            finally { mutableState.update { it.copy(enqueueing = false) } }
        }
    }

    private fun buildRequest(draft: MusicWorkspaceDraft): StableAudio3Request {
        val kindValue = if (kind == "sfx") StableAudio3Kind.SFX else StableAudio3Kind.MUSIC
        val manifest = StableAudio3ManifestLoader.load(app)
        val entry = StableAudio3ModelDoctor.entryFor(
            manifest = manifest,
            kind = kindValue,
            ditPrecision = StableAudio3DitPrecision.fromWire(draft["ditPrecision"]),
            decoderPrecision = StableAudio3CodecPrecision.fromWire(draft["decoderPrecision"]),
            encoderPrecision = StableAudio3CodecPrecision.fromWire(draft["encoderPrecision"])
        )
        fun component(role: String): StableAudio3ComponentRef {
            val path = draft.components[role] ?: error("Missing component")
            val model = models[path] ?: error("Missing model")
            val expected = StableAudio3ModelDoctor.canonicalComponent(kindValue, entry, role)
            require(StableAudio3ModelDoctor.isCanonicalInstalledModel(kindValue, entry, expected, model)) {
                "Stable Audio ${role} is stale; re-download the curated component"
            }
            return StableAudio3ComponentRef(
                path = model.path,
                sha256 = expected.sha256,
                sizeBytes = expected.sizeBytes,
                sourceIdentity = "sha256:${expected.sha256}"
            )
        }
        val operation = StableAudio3Operation.fromWire(draft["operation"])
        return StableAudio3Request(
            kind = kindValue,
            operation = operation,
            components = StableAudio3Components(component("tokenizer"), component("textEncoder"), component("dit"),
                component("codecDecoder"), if (operation != StableAudio3Operation.GENERATE) component("codecEncoder") else null),
            prompt = draft["prompt"], negativePrompt = draft["negativePrompt"],
            durationSeconds = draft["durationSeconds"].toDouble(), steps = draft["steps"].toInt(),
            seed = draft["seed"].takeIf { it.isNotBlank() }?.toLong() ?: SecureRandom().nextLong().ushr(1),
            cfg = draft["cfg"].toFloat(), apg = draft["apg"].toFloat(), cfgBatched = draft["cfgBatched"] == "true",
            initAudioPath = draft["initAudio"].takeIf { operation != StableAudio3Operation.GENERATE && it.isNotBlank() },
            initNoiseLevel = if (operation == StableAudio3Operation.GENERATE) 1f else draft["initNoiseLevel"].toFloat(),
            maskStartSeconds = if (operation == StableAudio3Operation.INPAINT) draft["maskStartSeconds"].toDouble() else null,
            maskEndSeconds = if (operation == StableAudio3Operation.INPAINT) draft["maskEndSeconds"].toDouble() else null,
            threads = draft["threads"].toInt(), freeModels = draft["freeModels"] == "true",
            ditPrecision = StableAudio3DitPrecision.fromWire(draft["ditPrecision"]),
            decoderPrecision = StableAudio3CodecPrecision.fromWire(draft["decoderPrecision"]),
            encoderPrecision = StableAudio3CodecPrecision.fromWire(draft["encoderPrecision"]),
            maxRung = draft["maxRung"].takeIf { it.isNotBlank() }?.toInt(),
            loras = draft.loras.map { StableAudio3Lora(it.path, it.strength.toFloat()) },
            outputPath = File(app.cacheDir, "stable_audio_pending.wav").absolutePath
        )
    }

    fun cancel() {
        if (importJob?.isActive == true) { importJob?.cancel(); return }
        val id = selectedJobId ?: state.value.job?.id ?: return
        scope.launch { try { repository.cancelGeneration(id) }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { fail() } }
    }
    fun retry() {
        val id = selectedJobId ?: state.value.job?.id ?: return
        scope.launch { try { if (!repository.retryJob(id)) fail() }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { fail() } }
    }

    fun importFile(uri: Uri, lora: Boolean) {
        if (state.value.busy) return
        importJob = scope.launch {
            mutableState.update { it.copy(importing = true, error = null) }
            try {
                val path = withContext(Dispatchers.IO) {
                    val directory = File(AudioWorkspaceStorage.root(app), "music_inputs/${UUID.randomUUID()}").apply { mkdirs() }
                    try {
                        val original = File(directory, if (lora) "adapter.safetensors" else "original.audio")
                        app.contentResolver.openInputStream(uri)?.use { input -> original.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024); var count = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                count += n; require(count <= 2L * 1024 * 1024 * 1024) { "Input is too large" }
                                output.write(buffer, 0, n)
                            }
                            require(count > 0) { "Input is empty" }
                        } } ?: error("Input is unreadable")
                        if (lora) original.absolutePath else {
                            val ffmpeg = BinaryRepository(app).getFFmpegBinary() ?: error("FFmpeg missing")
                            val converted = File(directory, "input.wav")
                            val result = AudioProcessRunner.run(app, listOf(ffmpeg.absolutePath, "-hide_banner", "-loglevel", "error", "-y",
                                "-i", original.absolutePath, "-vn", "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le", converted.absolutePath))
                            require(result.exitCode == 0)
                            val info = AudioFileInspector.inspect(converted)
                            require(info.durationMs in 1..120_000 && info.sampleRate == 44_100 && info.channels == 2)
                            converted.absolutePath
                        }
                    } catch (error: Throwable) {
                        // Only this invocation's incomplete import is removed; source URI is untouched.
                        // Keeping failed derivatives would orphan files which no draft can reference.
                        directory.deleteRecursively()
                        throw error
                    }
                }
                val draft = state.value.draft
                update(if (lora) draft.copy(loras = draft.loras + MusicLoraDraft(path)) else draft.withValue("initAudio", path))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail() }
            finally { mutableState.update { it.copy(importing = false) } }
        }
    }

    private fun fail() { mutableState.update { it.copy(error = app.getString(R.string.audio_music_error)) } }
    fun close() { scope.cancel() }
}

internal fun musicComponentRole(value: String?): String? =
    com.example.llamadroid.data.model.StableAudioModelSupport.canonicalRole(value)
        ?.takeIf { it in setOf("dit", "textEncoder", "tokenizer", "codecEncoder", "codecDecoder") }
