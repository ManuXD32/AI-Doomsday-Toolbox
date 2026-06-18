package com.example.llamadroid.service

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.onnx.OnnxTtsRequest
import com.example.llamadroid.onnx.SupertonicTtsPipeline
import com.example.llamadroid.util.AIConstants
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max

data class TimedTranscriptSegment(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class TranslatedTranscriptSegment(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val translatedText: String
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

enum class MediaTranslationOutputMode {
    AUTO,
    DUB_VIDEO,
    AUDIO_ONLY
}

data class MediaTranslationJobSpec(
    val sourcePath: String,
    val sourceName: String,
    val sourceMimeType: String?,
    val whisperModelPath: String,
    val whisperLanguage: String,
    val whisperThreads: Int,
    val targetLanguage: String,
    val ttsModelPath: String,
    val ttsModelName: String,
    val ttsLanguage: String,
    val ttsVoiceName: String?,
    val ttsSteps: Int,
    val outputMode: MediaTranslationOutputMode,
    val replaceOriginalAudio: Boolean,
    val backendSnapshot: RemoteSummarySettingsSnapshot
)

data class SubtitleBurnStyleSpec(
    val fontSize: Int,
    val alignment: Int,
    val marginV: Int,
    val marginL: Int,
    val primaryColorRed: Float,
    val primaryColorGreen: Float,
    val primaryColorBlue: Float,
    val fontName: String
)

data class SubtitleTranslationJobSpec(
    val videoPath: String,
    val videoName: String,
    val sourceSubtitlePath: String?,
    val sourceSubtitleName: String?,
    val whisperModelPath: String?,
    val whisperLanguage: String,
    val whisperThreads: Int,
    val targetLanguage: String,
    val translateSubtitles: Boolean,
    val burnIntoVideo: Boolean,
    val burnStyle: SubtitleBurnStyleSpec,
    val backendSnapshot: RemoteSummarySettingsSnapshot
)

data class MediaTranslationWorkflowState(
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val currentBatchItem: Int = 0,
    val totalBatchItems: Int = 0,
    val toolProgressDetail: String? = null,
    val originalSrtPath: String? = null,
    val translatedSrtPath: String? = null,
    val translatedAudioPath: String? = null,
    val finalOutputPath: String? = null,
    val errorMessage: String? = null,
    val cancelled: Boolean = false,
    val paused: Boolean = false
)

object MediaTranslationWorkflowStateHolder {
    private val _state = MutableStateFlow(MediaTranslationWorkflowState())
    val state: StateFlow<MediaTranslationWorkflowState> = _state

    fun update(transform: (MediaTranslationWorkflowState) -> MediaTranslationWorkflowState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = MediaTranslationWorkflowState()
    }
}

object SrtParser {
    private val timeLineRegex = Regex(
        """(\d{2}):(\d{2}):(\d{2}),(\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2}),(\d{3})"""
    )

    fun parse(raw: String): List<TimedTranscriptSegment> {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isBlank()) return emptyList()
        return normalized.split(Regex("""\n{2,}""")).mapNotNull { block ->
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val timeLineIndex = lines.indexOfFirst { timeLineRegex.containsMatchIn(it) }
            if (timeLineIndex < 0) return@mapNotNull null
            val match = timeLineRegex.find(lines[timeLineIndex]) ?: return@mapNotNull null
            val parsedId = lines.firstOrNull()?.toIntOrNull()
            val text = lines.drop(timeLineIndex + 1).joinToString("\n").trim()
            if (text.isBlank()) return@mapNotNull null
            TimedTranscriptSegment(
                id = parsedId ?: 0,
                startMs = parseTimestamp(match.groupValues, 1),
                endMs = parseTimestamp(match.groupValues, 5),
                text = text
            )
        }.mapIndexed { index, segment ->
            segment.copy(id = if (segment.id > 0) segment.id else index + 1)
        }
    }

    private fun parseTimestamp(values: List<String>, offset: Int): Long {
        val hours = values[offset].toLong()
        val minutes = values[offset + 1].toLong()
        val seconds = values[offset + 2].toLong()
        val millis = values[offset + 3].toLong()
        return (((hours * 60 + minutes) * 60 + seconds) * 1000) + millis
    }
}

object SrtWriter {
    fun write(segments: List<TranslatedTranscriptSegment>): String =
        segments.joinToString("\n\n") { segment ->
            buildString {
                appendLine(segment.id)
                appendLine("${formatTimestamp(segment.startMs)} --> ${formatTimestamp(segment.endMs)}")
                append(segment.translatedText.trim())
            }
        }.trimEnd() + "\n"

    fun writeOriginal(segments: List<TimedTranscriptSegment>): String =
        segments.joinToString("\n\n") { segment ->
            buildString {
                appendLine(segment.id)
                appendLine("${formatTimestamp(segment.startMs)} --> ${formatTimestamp(segment.endMs)}")
                append(segment.text.trim())
            }
        }.trimEnd() + "\n"

    private fun formatTimestamp(valueMs: Long): String {
        val safe = valueMs.coerceAtLeast(0L)
        val millis = safe % 1000
        val totalSeconds = safe / 1000
        val seconds = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val minutes = totalMinutes % 60
        val hours = totalMinutes / 60
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}

object TranslationJsonValidator {
    fun parseAndValidate(raw: String, expected: List<TimedTranscriptSegment>): Result<Map<Int, String>> = runCatching {
        val json = extractJson(raw)
        val array = json.optJSONArray("segments")
            ?: throw IllegalArgumentException("Missing segments array")
        val expectedIds = expected.map { it.id }.toSet()
        val seen = mutableSetOf<Int>()
        val translations = linkedMapOf<Int, String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw IllegalArgumentException("Invalid segment at ${index + 1}")
            val id = item.optInt("id", Int.MIN_VALUE)
            if (id !in expectedIds) throw IllegalArgumentException("Unexpected segment id $id")
            if (!seen.add(id)) throw IllegalArgumentException("Duplicate segment id $id")
            val text = item.optString("translatedText").trim()
            if (text.isBlank()) throw IllegalArgumentException("Empty translation for segment $id")
            translations[id] = text
        }
        val missing = expectedIds - seen
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("Missing segment ids: ${missing.joinToString()}")
        }
        translations
    }

    private fun extractJson(raw: String): JSONObject {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "No JSON object found" }
        return JSONObject(trimmed.substring(start, end + 1))
    }
}

object MediaTranslationAudioTiming {
    fun tempoForDuration(sourceSeconds: Float, targetMs: Long): Float {
        val targetSeconds = targetMs.toFloat() / 1000f
        if (sourceSeconds <= 0f || targetSeconds <= 0f) return 1f
        return (sourceSeconds / targetSeconds).coerceIn(0.5f, 100f)
    }
}

object MediaTranslationWorkflowService {
    private const val RUNTIME_DIR = "media_translation_runtime"
    private const val RECOVERABLE_JOB_FILE = "recoverable_job.json"
    private const val KIND_MEDIA = "media"
    private const val KIND_SUBTITLE = "subtitle"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    private var currentProcess: Process? = null
    private var currentRemoteClient: RemoteSummaryClient? = null
    private var notificationTaskId: Int? = null
    private var appContext: Context? = null
    private var currentRunId: Long = 0L
    @Volatile private var cancelled = false
    @Volatile private var pauseRequested = false

    private data class MediaTranslationRecoverableRuntime(
        val kind: String,
        val mediaSpecs: List<MediaTranslationJobSpec>,
        val subtitleSpecs: List<SubtitleTranslationJobSpec>,
        val doneJobIndexes: List<Int>,
        val pendingJobIndexes: List<Int>,
        val title: String
    ) {
        val totalCount: Int
            get() = when (kind) {
                KIND_SUBTITLE -> subtitleSpecs.size
                else -> mediaSpecs.size
            }

        val completedCount: Int
            get() = doneJobIndexes.distinct().count { it in 0 until totalCount }

        val hasRemainingWork: Boolean
            get() = pendingJobIndexes.any { it in 0 until totalCount }

        fun validateInputs(): String? {
            return when (kind) {
                KIND_SUBTITLE -> pendingJobIndexes.firstNotNullOfOrNull { index ->
                    val spec = subtitleSpecs.getOrNull(index) ?: return@firstNotNullOfOrNull ""
                    val paths = mutableListOf(spec.videoPath)
                    if (spec.sourceSubtitlePath.isNullOrBlank()) {
                        paths += spec.whisperModelPath.orEmpty()
                    } else {
                        paths += spec.sourceSubtitlePath
                    }
                    firstMissingPath(
                        paths
                    )
                }
                else -> pendingJobIndexes.firstNotNullOfOrNull { index ->
                    val spec = mediaSpecs.getOrNull(index) ?: return@firstNotNullOfOrNull ""
                    firstMissingPath(
                        listOf(
                            spec.sourcePath,
                            spec.whisperModelPath,
                            spec.ttsModelPath
                        )
                    )
                }
            }
        }

        private fun firstMissingPath(paths: List<String>): String? =
            paths.firstOrNull { path -> path.isBlank() || !File(path).isFile }
    }

    fun start(context: Context, spec: MediaTranslationJobSpec) {
        startMediaQueue(context, listOf(spec), doneJobIndexes = emptySet(), pendingJobIndexes = listOf(0), replaceExisting = true)
    }

    fun startBatch(context: Context, specs: List<MediaTranslationJobSpec>) {
        if (specs.isEmpty()) return
        startMediaQueue(context, specs, doneJobIndexes = emptySet(), pendingJobIndexes = specs.indices.toList(), replaceExisting = true)
    }

    fun startSubtitleTranslation(context: Context, spec: SubtitleTranslationJobSpec) {
        startSubtitleQueue(context, listOf(spec), doneJobIndexes = emptySet(), pendingJobIndexes = listOf(0), replaceExisting = true)
    }

    fun startSubtitleTranslationBatch(context: Context, specs: List<SubtitleTranslationJobSpec>) {
        if (specs.isEmpty()) return
        startSubtitleQueue(context, specs, doneJobIndexes = emptySet(), pendingJobIndexes = specs.indices.toList(), replaceExisting = true)
    }

    fun requestResume(context: Context) {
        requestResume(context, expectedKind = null)
    }

    fun requestResumeMedia(context: Context) {
        requestResume(context, expectedKind = KIND_MEDIA)
    }

    fun requestResumeSubtitle(context: Context) {
        requestResume(context, expectedKind = KIND_SUBTITLE)
    }

    private fun requestResume(context: Context, expectedKind: String?) {
        if (currentJob?.isActive == true) return
        val state = readRecoverableRuntime(context.applicationContext) ?: return
        if (expectedKind != null && state.kind != expectedKind) return
        if (!state.hasRemainingWork) {
            clearRecoverableRuntime(context.applicationContext)
            return
        }
        val validationError = state.validateInputs()
        if (validationError != null) {
            MediaTranslationWorkflowStateHolder.update {
                it.copy(
                    isRunning = false,
                    status = "",
                    errorMessage = context.getString(R.string.workflow_media_resume_missing_inputs, validationError),
                    cancelled = false,
                    paused = false
                )
            }
            return
        }
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = "media_translation_workflow",
            event = "resume_requested",
            details = "kind=${state.kind} done=${state.doneJobIndexes.size} pending=${state.pendingJobIndexes.size} total=${state.totalCount}"
        )
        when (state.kind) {
            KIND_MEDIA -> startMediaQueue(
                context = context,
                specs = state.mediaSpecs,
                doneJobIndexes = state.doneJobIndexes.toSet(),
                pendingJobIndexes = state.pendingJobIndexes,
                replaceExisting = false
            )
            KIND_SUBTITLE -> startSubtitleQueue(
                context = context,
                specs = state.subtitleSpecs,
                doneJobIndexes = state.doneJobIndexes.toSet(),
                pendingJobIndexes = state.pendingJobIndexes,
                replaceExisting = false
            )
        }
    }

    fun hasRecoverableRuntime(context: Context): Boolean =
        readRecoverableRuntime(context.applicationContext)?.hasRemainingWork == true

    fun hasRecoverableMediaRuntime(context: Context): Boolean =
        readRecoverableRuntime(context.applicationContext)?.let { it.kind == KIND_MEDIA && it.hasRemainingWork } == true

    fun hasRecoverableSubtitleRuntime(context: Context): Boolean =
        readRecoverableRuntime(context.applicationContext)?.let { it.kind == KIND_SUBTITLE && it.hasRemainingWork } == true

    private fun recoverableRuntimeFile(context: Context): File =
        File(context.filesDir, RUNTIME_DIR).apply { mkdirs() }.resolve(RECOVERABLE_JOB_FILE)

    private fun writeRecoverableRuntime(context: Context, runtime: MediaTranslationRecoverableRuntime) {
        runCatching {
            recoverableRuntimeFile(context.applicationContext).writeText(runtime.toJson().toString(2))
        }.onFailure {
            DebugLog.log("[MEDIA-TRANSLATE] Recoverable runtime write failed: ${it.message}")
        }
    }

    private fun readRecoverableRuntime(context: Context): MediaTranslationRecoverableRuntime? {
        val file = recoverableRuntimeFile(context.applicationContext)
        if (!file.isFile) return null
        return runCatching {
            JSONObject(file.readText()).toRecoverableRuntime()
        }.onFailure {
            DebugLog.log("[MEDIA-TRANSLATE] Recoverable runtime read failed: ${it.message}")
        }.getOrNull()
    }

    private fun clearRecoverableRuntime(context: Context) {
        runCatching { recoverableRuntimeFile(context.applicationContext).delete() }
    }

    private fun updateRecoverableQueues(context: Context, doneJobIndexes: List<Int>, pendingJobIndexes: List<Int>) {
        val runtime = readRecoverableRuntime(context.applicationContext) ?: return
        val (done, pending) = sanitizeRecoverableQueues(runtime.totalCount, doneJobIndexes, pendingJobIndexes)
        writeRecoverableRuntime(
            context.applicationContext,
            runtime.copy(doneJobIndexes = done, pendingJobIndexes = pending)
        )
    }

    private fun MediaTranslationRecoverableRuntime.toJson(): JSONObject =
        JSONObject().apply {
            put("kind", kind)
            put("title", title)
            put("completedCount", completedCount)
            put("doneJobIndexes", JSONArray().apply { doneJobIndexes.forEach { put(it) } })
            put("pendingJobIndexes", JSONArray().apply { pendingJobIndexes.forEach { put(it) } })
            put("jobs", JSONArray().apply {
                val names = when (kind) {
                    KIND_SUBTITLE -> subtitleSpecs.map { it.videoName }
                    else -> mediaSpecs.map { it.sourceName }
                }
                names.forEachIndexed { index, name ->
                    put(
                        JSONObject().apply {
                            put("index", index)
                            put("sourceName", name)
                            put("status", if (index in doneJobIndexes) "done" else "pending")
                        }
                    )
                }
            })
            put("updatedAt", System.currentTimeMillis())
            put("mediaSpecs", JSONArray().apply { mediaSpecs.forEach { put(it.toJson()) } })
            put("subtitleSpecs", JSONArray().apply { subtitleSpecs.forEach { put(it.toJson()) } })
        }

    private fun JSONObject.toRecoverableRuntime(): MediaTranslationRecoverableRuntime {
        val kind = optString("kind", KIND_MEDIA)
        val mediaSpecs = optJSONArray("mediaSpecs").toJsonObjectList().map { it.toMediaSpec() }
        val subtitleSpecs = optJSONArray("subtitleSpecs").toJsonObjectList().map { it.toSubtitleSpec() }
        val total = if (kind == KIND_SUBTITLE) subtitleSpecs.size else mediaSpecs.size
        val hasExplicitQueues = has("doneJobIndexes") || has("pendingJobIndexes") || has("jobs")
        val jobObjects = optJSONArray("jobs").toJsonObjectList()
        val doneFromJobs = jobObjects.mapNotNull { job ->
            job.optInt("index", -1).takeIf { it >= 0 && job.optString("status") == "done" }
        }
        val pendingFromJobs = jobObjects.mapNotNull { job ->
            job.optInt("index", -1).takeIf { it >= 0 && job.optString("status") != "done" }
        }
        val legacyCompleted = optInt("completedCount", 0).coerceIn(0, total.coerceAtLeast(0))
        val rawDone = optJSONArray("doneJobIndexes").toIntList().ifEmpty {
            if (hasExplicitQueues) doneFromJobs else (0 until legacyCompleted).toList()
        }
        val rawPending = optJSONArray("pendingJobIndexes").toIntList().ifEmpty {
            if (hasExplicitQueues) pendingFromJobs else (legacyCompleted until total).toList()
        }
        val (doneJobIndexes, pendingJobIndexes) = sanitizeRecoverableQueues(total, rawDone, rawPending)
        return MediaTranslationRecoverableRuntime(
            kind = kind,
            mediaSpecs = mediaSpecs,
            subtitleSpecs = subtitleSpecs,
            doneJobIndexes = doneJobIndexes,
            pendingJobIndexes = pendingJobIndexes,
            title = optString("title").ifBlank {
                if (kind == KIND_SUBTITLE) "Subtitle translation" else "Media translation"
            }
        )
    }

    private fun JSONArray?.toJsonObjectList(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optJSONObject(index) }
    }

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        return (0 until length()).map { index -> optInt(index, -1) }.filter { it >= 0 }
    }

    private fun sanitizeRecoverableQueues(
        total: Int,
        rawDone: List<Int>,
        rawPending: List<Int>
    ): Pair<List<Int>, List<Int>> {
        val validRange = 0 until total
        val done = rawDone.distinct().filter { it in validRange }
        val pending = rawPending.distinct().filter { it in validRange && it !in done }
        return done to pending
    }

    private fun MediaTranslationJobSpec.toJson(): JSONObject =
        JSONObject().apply {
            put("sourcePath", sourcePath)
            put("sourceName", sourceName)
            putNullable("sourceMimeType", sourceMimeType)
            put("whisperModelPath", whisperModelPath)
            put("whisperLanguage", whisperLanguage)
            put("whisperThreads", whisperThreads)
            put("targetLanguage", targetLanguage)
            put("ttsModelPath", ttsModelPath)
            put("ttsModelName", ttsModelName)
            put("ttsLanguage", ttsLanguage)
            putNullable("ttsVoiceName", ttsVoiceName)
            put("ttsSteps", ttsSteps)
            put("outputMode", outputMode.name)
            put("replaceOriginalAudio", replaceOriginalAudio)
            put("backendSnapshot", backendSnapshot.toJson())
        }

    private fun JSONObject.toMediaSpec(): MediaTranslationJobSpec =
        MediaTranslationJobSpec(
            sourcePath = optString("sourcePath"),
            sourceName = optString("sourceName", "media"),
            sourceMimeType = optNullableString("sourceMimeType"),
            whisperModelPath = optString("whisperModelPath"),
            whisperLanguage = optString("whisperLanguage", "auto"),
            whisperThreads = optInt("whisperThreads", 4),
            targetLanguage = optString("targetLanguage"),
            ttsModelPath = optString("ttsModelPath"),
            ttsModelName = optString("ttsModelName"),
            ttsLanguage = optString("ttsLanguage", optString("targetLanguage")),
            ttsVoiceName = optNullableString("ttsVoiceName"),
            ttsSteps = optInt("ttsSteps", 12),
            outputMode = enumValueOrDefault(optString("outputMode"), MediaTranslationOutputMode.AUTO),
            replaceOriginalAudio = optBoolean("replaceOriginalAudio", true),
            backendSnapshot = optJSONObject("backendSnapshot").toRemoteSummarySnapshot()
        )

    private fun SubtitleTranslationJobSpec.toJson(): JSONObject =
        JSONObject().apply {
            put("videoPath", videoPath)
            put("videoName", videoName)
            putNullable("sourceSubtitlePath", sourceSubtitlePath)
            putNullable("sourceSubtitleName", sourceSubtitleName)
            putNullable("whisperModelPath", whisperModelPath)
            put("whisperLanguage", whisperLanguage)
            put("whisperThreads", whisperThreads)
            put("targetLanguage", targetLanguage)
            put("translateSubtitles", translateSubtitles)
            put("burnIntoVideo", burnIntoVideo)
            put("burnStyle", burnStyle.toJson())
            put("backendSnapshot", backendSnapshot.toJson())
        }

    private fun JSONObject.toSubtitleSpec(): SubtitleTranslationJobSpec =
        SubtitleTranslationJobSpec(
            videoPath = optString("videoPath"),
            videoName = optString("videoName", "video"),
            sourceSubtitlePath = optNullableString("sourceSubtitlePath"),
            sourceSubtitleName = optNullableString("sourceSubtitleName"),
            whisperModelPath = optNullableString("whisperModelPath"),
            whisperLanguage = optString("whisperLanguage", "auto"),
            whisperThreads = optInt("whisperThreads", 4),
            targetLanguage = optString("targetLanguage"),
            translateSubtitles = optBoolean("translateSubtitles", true),
            burnIntoVideo = optBoolean("burnIntoVideo", true),
            burnStyle = optJSONObject("burnStyle").toSubtitleBurnStyle(),
            backendSnapshot = optJSONObject("backendSnapshot").toRemoteSummarySnapshot()
        )

    private fun SubtitleBurnStyleSpec.toJson(): JSONObject =
        JSONObject().apply {
            put("fontSize", fontSize)
            put("alignment", alignment)
            put("marginV", marginV)
            put("marginL", marginL)
            put("primaryColorRed", primaryColorRed)
            put("primaryColorGreen", primaryColorGreen)
            put("primaryColorBlue", primaryColorBlue)
            put("fontName", fontName)
        }

    private fun JSONObject?.toSubtitleBurnStyle(): SubtitleBurnStyleSpec =
        SubtitleBurnStyleSpec(
            fontSize = this?.optInt("fontSize", 28) ?: 28,
            alignment = this?.optInt("alignment", 2) ?: 2,
            marginV = this?.optInt("marginV", 24) ?: 24,
            marginL = this?.optInt("marginL", 24) ?: 24,
            primaryColorRed = this?.optDouble("primaryColorRed", 1.0)?.toFloat() ?: 1f,
            primaryColorGreen = this?.optDouble("primaryColorGreen", 1.0)?.toFloat() ?: 1f,
            primaryColorBlue = this?.optDouble("primaryColorBlue", 1.0)?.toFloat() ?: 1f,
            fontName = this?.optString("fontName", "Default") ?: "Default"
        )

    private fun RemoteSummarySettingsSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("backend", backend)
            put("ollamaUrl", ollamaUrl)
            put("llamaServerUrl", llamaServerUrl)
            put("llamaSwapUrl", llamaSwapUrl)
            putNullable("ollamaModel", ollamaModel)
            putNullable("llamaSwapModel", llamaSwapModel)
            put("thinkingEnabled", thinkingEnabled)
            putNullable("llamaServerModelLabel", llamaServerModelLabel)
            put("llamaServerContextTokens", llamaServerContextTokens)
            putNullable("llamaServerContextLabel", llamaServerContextLabel)
            put("chunkContext", chunkContext)
            put("chunkMaxTokens", chunkMaxTokens)
            put("mergeContext", mergeContext)
            put("mergeMaxTokens", mergeMaxTokens)
            put("temperature", temperature.toDouble())
            put("timeoutMinutes", timeoutMinutes)
            put("targetLanguage", targetLanguage)
            putNullable("summaryPrompt", summaryPrompt)
            putNullable("mergePrompt", mergePrompt)
        }

    private fun JSONObject?.toRemoteSummarySnapshot(): RemoteSummarySettingsSnapshot =
        RemoteSummarySettingsSnapshot(
            backend = SettingsRepository.normalizeOllamaOrLlamaBackend(
                this?.optString("backend", SettingsRepository.PDF_BACKEND_OLLAMA)
            ),
            ollamaUrl = this?.optString("ollamaUrl", AIConstants.Urls.OLLAMA_DEFAULT)
                ?: AIConstants.Urls.OLLAMA_DEFAULT,
            llamaServerUrl = this?.optString("llamaServerUrl", SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL)
                ?: SettingsRepository.PDF_LLAMA_SERVER_DEFAULT_URL,
            llamaSwapUrl = this?.optString("llamaSwapUrl", SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL)
                ?: SettingsRepository.PDF_LLAMA_SWAP_DEFAULT_URL,
            ollamaModel = this?.optNullableString("ollamaModel"),
            llamaSwapModel = this?.optNullableString("llamaSwapModel"),
            thinkingEnabled = this?.optBoolean("thinkingEnabled", false) ?: false,
            llamaServerModelLabel = this?.optNullableString("llamaServerModelLabel"),
            llamaServerContextTokens = this?.optInt("llamaServerContextTokens", 0) ?: 0,
            llamaServerContextLabel = this?.optNullableString("llamaServerContextLabel"),
            chunkContext = this?.optInt("chunkContext", 4096) ?: 4096,
            chunkMaxTokens = this?.optInt("chunkMaxTokens", 1024) ?: 1024,
            mergeContext = this?.optInt("mergeContext", 4096) ?: 4096,
            mergeMaxTokens = this?.optInt("mergeMaxTokens", 1024) ?: 1024,
            temperature = this?.optDouble("temperature", 0.2)?.toFloat() ?: 0.2f,
            timeoutMinutes = this?.optInt("timeoutMinutes", 10) ?: 10,
            targetLanguage = this?.optString("targetLanguage", SettingsRepository.DEFAULT_SUMMARY_TARGET_LANGUAGE)
                ?: SettingsRepository.DEFAULT_SUMMARY_TARGET_LANGUAGE,
            summaryPrompt = this?.optNullableString("summaryPrompt"),
            mergePrompt = this?.optNullableString("mergePrompt")
        )

    private fun JSONObject.putNullable(key: String, value: String?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, defaultValue: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(defaultValue)

    private fun startMediaQueue(
        context: Context,
        specs: List<MediaTranslationJobSpec>,
        doneJobIndexes: Set<Int>,
        pendingJobIndexes: List<Int>,
        replaceExisting: Boolean
    ) {
        if (specs.isEmpty()) return
        if (replaceExisting) cancel(context, clearRecoverable = true)
        val (initialDone, initialPending) = sanitizeRecoverableQueues(specs.size, doneJobIndexes.toList(), pendingJobIndexes)
        if (initialPending.isEmpty()) return
        appContext = context.applicationContext
        cancelled = false
        pauseRequested = false
        val runId = nextRunId()
        val title = if (specs.size == 1) {
            context.getString(R.string.workflow_media_translate_notification_title, specs.first().sourceName)
        } else {
            context.getString(R.string.workflow_media_translate_batch_notification_title, specs.size)
        }
        writeRecoverableRuntime(
            context,
            MediaTranslationRecoverableRuntime(
                kind = KIND_MEDIA,
                mediaSpecs = specs,
                subtitleSpecs = emptyList(),
                doneJobIndexes = initialDone,
                pendingJobIndexes = initialPending,
                title = title
            )
        )
        MediaTranslationForegroundService.start(context, title)
        notificationTaskId = UnifiedNotificationManager.startTask(
            UnifiedNotificationManager.TaskType.TRANSCRIPTION,
            title
        )
        RemoteSummaryProtection.acquire(context)
        acquireWakeLock(context)
        MediaTranslationWorkflowStateHolder.update {
            MediaTranslationWorkflowState(
                isRunning = true,
                progress = 0.02f,
                status = if (replaceExisting) {
                    context.getString(R.string.workflow_step_starting)
                } else {
                    context.getString(R.string.workflow_media_resume_running, initialDone.size, specs.size)
                },
                currentBatchItem = (initialDone.size + 1).coerceAtMost(specs.size),
                totalBatchItems = specs.size
            )
        }
        currentJob = scope.launch {
            val outputs = mutableListOf<MediaTranslationOutput>()
            val done = initialDone.toMutableList()
            val pending = initialPending.toMutableList()
            val result = runCatching {
                initialPending.forEach { index ->
                    val item = specs[index]
                    ensureActive()
                    updateRecoverableQueues(context.applicationContext, done, pending)
                    MediaTranslationWorkflowStateHolder.update {
                        it.copy(
                            currentBatchItem = (done.size + 1).coerceAtMost(specs.size),
                            totalBatchItems = specs.size,
                            currentChunk = 0,
                            totalChunks = 0,
                            status = context.getString(R.string.workflow_batch_processing_item, index + 1, specs.size, item.sourceName)
                        )
                    }
                    outputs += runWorkflow(context.applicationContext, item)
                    pending.remove(index)
                    if (index !in done) done += index
                    updateRecoverableQueues(context.applicationContext, done, pending)
                }
                outputs.last()
            }
            result.fold(
                onSuccess = { output ->
                    if (runId == currentRunId) {
                        notificationTaskId?.let {
                            UnifiedNotificationManager.completeTask(
                                it,
                                if (specs.size == 1) context.getString(R.string.workflow_media_translate_complete)
                                else context.getString(R.string.workflow_media_translate_batch_complete, specs.size)
                            )
                        }
                        clearRecoverableRuntime(context.applicationContext)
                        MediaTranslationWorkflowStateHolder.update {
                            it.copy(
                                isRunning = false,
                                progress = 1f,
                                status = if (specs.size == 1) context.getString(R.string.workflow_media_translate_complete)
                                else context.getString(R.string.workflow_media_translate_batch_complete, specs.size),
                                currentBatchItem = specs.size,
                                totalBatchItems = specs.size,
                                originalSrtPath = output.originalSrt.absolutePath,
                                translatedSrtPath = output.translatedSrt.absolutePath,
                                translatedAudioPath = output.translatedAudio.absolutePath,
                                finalOutputPath = output.finalOutput.absolutePath,
                                errorMessage = null,
                                cancelled = false,
                                paused = false
                            )
                        }
                    }
                },
                onFailure = { error -> handleFailure(context, error, runId) }
            )
            cleanup(runId)
        }
    }

    private fun startSubtitleQueue(
        context: Context,
        specs: List<SubtitleTranslationJobSpec>,
        doneJobIndexes: Set<Int>,
        pendingJobIndexes: List<Int>,
        replaceExisting: Boolean
    ) {
        if (specs.isEmpty()) return
        if (replaceExisting) cancel(context, clearRecoverable = true)
        val (initialDone, initialPending) = sanitizeRecoverableQueues(specs.size, doneJobIndexes.toList(), pendingJobIndexes)
        if (initialPending.isEmpty()) return
        appContext = context.applicationContext
        cancelled = false
        pauseRequested = false
        val runId = nextRunId()
        val title = if (specs.size == 1) {
            context.getString(R.string.workflow_subtitle_translate_notification_title, specs.first().videoName)
        } else {
            context.getString(R.string.workflow_subtitle_translate_batch_notification_title, specs.size)
        }
        writeRecoverableRuntime(
            context,
            MediaTranslationRecoverableRuntime(
                kind = KIND_SUBTITLE,
                mediaSpecs = emptyList(),
                subtitleSpecs = specs,
                doneJobIndexes = initialDone,
                pendingJobIndexes = initialPending,
                title = title
            )
        )
        MediaTranslationForegroundService.start(context, title)
        notificationTaskId = UnifiedNotificationManager.startTask(
            UnifiedNotificationManager.TaskType.TRANSCRIPTION,
            title
        )
        RemoteSummaryProtection.acquire(context)
        acquireWakeLock(context)
        MediaTranslationWorkflowStateHolder.update {
            MediaTranslationWorkflowState(
                isRunning = true,
                progress = 0.02f,
                status = if (replaceExisting) {
                    context.getString(R.string.workflow_step_starting)
                } else {
                    context.getString(R.string.workflow_media_resume_running, initialDone.size, specs.size)
                },
                currentBatchItem = (initialDone.size + 1).coerceAtMost(specs.size),
                totalBatchItems = specs.size
            )
        }
        currentJob = scope.launch {
            val outputs = mutableListOf<SubtitleTranslationOutput>()
            val done = initialDone.toMutableList()
            val pending = initialPending.toMutableList()
            val result = runCatching {
                initialPending.forEach { index ->
                    val item = specs[index]
                    ensureActive()
                    updateRecoverableQueues(context.applicationContext, done, pending)
                    MediaTranslationWorkflowStateHolder.update {
                        it.copy(
                            currentBatchItem = (done.size + 1).coerceAtMost(specs.size),
                            totalBatchItems = specs.size,
                            currentChunk = 0,
                            totalChunks = 0,
                            status = context.getString(R.string.workflow_batch_processing_item, index + 1, specs.size, item.videoName)
                        )
                    }
                    outputs += runSubtitleWorkflow(context.applicationContext, item)
                    pending.remove(index)
                    if (index !in done) done += index
                    updateRecoverableQueues(context.applicationContext, done, pending)
                }
                outputs.last()
            }
            result.fold(
                onSuccess = { output ->
                    if (runId == currentRunId) {
                        notificationTaskId?.let {
                            UnifiedNotificationManager.completeTask(
                                it,
                                if (specs.size == 1) context.getString(R.string.workflow_subtitle_translate_complete)
                                else context.getString(R.string.workflow_subtitle_translate_batch_complete, specs.size)
                            )
                        }
                        clearRecoverableRuntime(context.applicationContext)
                        MediaTranslationWorkflowStateHolder.update {
                            it.copy(
                                isRunning = false,
                                progress = 1f,
                                status = if (specs.size == 1) context.getString(R.string.workflow_subtitle_translate_complete)
                                else context.getString(R.string.workflow_subtitle_translate_batch_complete, specs.size),
                                currentBatchItem = specs.size,
                                totalBatchItems = specs.size,
                                originalSrtPath = output.originalSrt.absolutePath,
                                translatedSrtPath = output.translatedSrt.absolutePath,
                                translatedAudioPath = null,
                                finalOutputPath = output.finalOutput.absolutePath,
                                errorMessage = null,
                                cancelled = false,
                                paused = false
                            )
                        }
                    }
                },
                onFailure = { error -> handleFailure(context, error, runId) }
            )
            cleanup(runId)
        }
    }

    private fun handleFailure(context: Context, error: Throwable, runId: Long) {
        if (runId != currentRunId) return
        val wasPaused = error is CancellationException && pauseRequested
        val message = if (error is CancellationException) {
            context.getString(R.string.action_cancelled)
        } else {
            error.message ?: context.getString(R.string.error_generic)
        }
        notificationTaskId?.let {
            if (error is CancellationException) UnifiedNotificationManager.dismissTask(it)
            else UnifiedNotificationManager.failTask(it, message)
        }
        MediaTranslationWorkflowStateHolder.update {
            it.copy(
                isRunning = false,
                progress = 0f,
                status = "",
                errorMessage = if (error is CancellationException) null else message,
                cancelled = error is CancellationException && !wasPaused,
                paused = wasPaused
            )
        }
    }

    fun cancel(context: Context? = null) {
        cancel(context, clearRecoverable = true)
    }

    fun pause(context: Context? = null) {
        cancel(context, clearRecoverable = false)
    }

    private fun cancel(context: Context? = null, clearRecoverable: Boolean) {
        val paused = !clearRecoverable
        cancelled = true
        pauseRequested = paused
        if (clearRecoverable) {
            (context?.applicationContext ?: appContext)?.let(::clearRecoverableRuntime)
        }
        currentRemoteClient?.cancelActiveCall()
        currentProcess?.destroyForcibly()
        currentJob?.cancel(CancellationException(context?.getString(R.string.action_cancelled) ?: "Cancelled"))
        notificationTaskId?.let(UnifiedNotificationManager::dismissTask)
        (context?.applicationContext ?: appContext)?.let(MediaTranslationForegroundService::stop)
        MediaTranslationWorkflowStateHolder.update {
            it.copy(isRunning = false, progress = 0f, status = "", cancelled = !paused, paused = paused)
        }
        cleanup()
    }

    private suspend fun runWorkflow(context: Context, spec: MediaTranslationJobSpec): MediaTranslationOutput = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val startedAt = System.currentTimeMillis()
        var extractionMs = 0L
        var transcriptionMs = 0L
        var translationMs = 0L
        var ttsMs = 0L
        var audioExportMs = 0L
        val workDir = File(context.cacheDir, "media_translation_$timestamp").apply { mkdirs() }
        val outputDir = File(context.filesDir, "workflow_media_translation/$timestamp").apply { mkdirs() }
        try {
            val isVideo = isVideoSpec(spec)
            val normalizedAudio = File(workDir, "source.wav")
            update(context.getString(R.string.workflow_media_translate_extracting_audio), 0.08f)
            var stageStartedAt = System.currentTimeMillis()
            runFfmpeg(context, listOf("-y", "-i", spec.sourcePath, "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1", normalizedAudio.absolutePath))
            extractionMs = System.currentTimeMillis() - stageStartedAt
            ensureActive()

            update(context.getString(R.string.workflow_media_translate_transcribing_srt), 0.18f)
            val whisperOutputBase = File(workDir, "whisper")
            stageStartedAt = System.currentTimeMillis()
            val sourceDurationMs = readMediaDurationMs(normalizedAudio).takeIf { it > 0L } ?: readMediaDurationMs(File(spec.sourcePath))
            runWhisperWithProgress(
                context = context,
                audioFile = normalizedAudio,
                outputBase = whisperOutputBase,
                whisperModelPath = spec.whisperModelPath,
                whisperLanguage = spec.whisperLanguage,
                whisperThreads = spec.whisperThreads,
                mediaDurationMs = sourceDurationMs,
                baseProgress = 0.18f,
                progressSpan = 0.1f
            )
            transcriptionMs = System.currentTimeMillis() - stageStartedAt
            val originalTxt = File("${whisperOutputBase.absolutePath}.txt").takeIf { it.isFile }?.readText().orEmpty()
            val originalSrtRaw = File("${whisperOutputBase.absolutePath}.srt").takeIf { it.isFile }?.readText()
                ?: throw IllegalStateException(context.getString(R.string.workflow_media_translate_error_no_srt))
            val segments = SrtParser.parse(originalSrtRaw)
            require(segments.isNotEmpty()) { context.getString(R.string.workflow_media_translate_error_no_segments) }
            val originalSrt = File(outputDir, "original.srt").apply { writeText(SrtWriter.writeOriginal(segments)) }
            File(outputDir, "original_transcript.txt").writeText(originalTxt.ifBlank { segments.joinToString("\n") { it.text } })
            ensureActive()

            update(context.getString(R.string.workflow_media_translate_translating), 0.3f)
            stageStartedAt = System.currentTimeMillis()
            val translated = translateSegments(
                context = context,
                sourceLanguage = spec.whisperLanguage,
                targetLanguage = spec.targetLanguage,
                backendSnapshot = spec.backendSnapshot,
                segments = segments
            )
            translationMs = System.currentTimeMillis() - stageStartedAt
            val translatedSrt = File(outputDir, "translated.srt").apply { writeText(SrtWriter.write(translated)) }
            ensureActive()

            update(context.getString(R.string.workflow_media_translate_synthesizing), 0.55f)
            stageStartedAt = System.currentTimeMillis()
            val timedTrack = synthesizeTimedAudio(context, spec, translated, workDir)
            ttsMs = System.currentTimeMillis() - stageStartedAt
            val translatedAudio = File(outputDir, "translated_audio.m4a")
            stageStartedAt = System.currentTimeMillis()
            runFfmpeg(context, listOf("-y", "-i", timedTrack.absolutePath, "-c:a", "aac", "-b:a", "192k", translatedAudio.absolutePath))
            audioExportMs = System.currentTimeMillis() - stageStartedAt
            ensureActive()

            update(context.getString(R.string.workflow_media_translate_exporting), 0.9f)
            stageStartedAt = System.currentTimeMillis()
            val finalOutput = if (shouldDubVideo(spec, isVideo)) {
                File(outputDir, "dubbed_${safeBaseName(spec.sourceName)}.mp4").also { output ->
                    val muxArgs = if (spec.replaceOriginalAudio) {
                        listOf(
                            "-y",
                            "-i", spec.sourcePath,
                            "-i", translatedAudio.absolutePath,
                            "-map", "0:v:0",
                            "-map", "1:a:0",
                            "-c:v", "copy",
                            "-c:a", "aac",
                            output.absolutePath
                        )
                    } else {
                        listOf(
                            "-y",
                            "-i", spec.sourcePath,
                            "-i", translatedAudio.absolutePath,
                            "-filter_complex", "[0:a:0][1:a:0]amix=inputs=2:duration=longest:dropout_transition=0[aout]",
                            "-map", "0:v:0",
                            "-map", "[aout]",
                            "-c:v", "copy",
                            "-c:a", "aac",
                            output.absolutePath
                        )
                    }
                    runFfmpeg(context, muxArgs)
                }
            } else {
                translatedAudio
            }
            val mediaBurnMs = System.currentTimeMillis() - stageStartedAt
            writeWorkflowMetadata(
                outputDir,
                JSONObject().apply {
                    put("workflow", "media_dubbing")
                    put("sourceName", spec.sourceName)
                    put("targetLanguage", spec.targetLanguage)
                    put("outputMode", spec.outputMode.name)
                    put("isVideo", isVideo)
                    put("segments", segments.size)
                    put("startedAt", startedAt)
                    put("completedAt", System.currentTimeMillis())
                    put("totalDurationMs", System.currentTimeMillis() - startedAt)
                    put("extractAudioDurationMs", extractionMs)
                    put("transcriptionDurationMs", transcriptionMs)
                    put("translationDurationMs", translationMs)
                    put("ttsDurationMs", ttsMs)
                    put("audioExportDurationMs", audioExportMs)
                    put("muxOrExportDurationMs", mediaBurnMs)
                }
            )
            mirrorOutputs(context, listOf(originalSrt, translatedSrt, translatedAudio, finalOutput).distinct())
            MediaTranslationOutput(originalSrt, translatedSrt, translatedAudio, finalOutput)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun runSubtitleWorkflow(context: Context, spec: SubtitleTranslationJobSpec): SubtitleTranslationOutput = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val startedAt = System.currentTimeMillis()
        var extractionMs = 0L
        var transcriptionMs = 0L
        var translationMs = 0L
        var burnMs = 0L
        val workDir = File(context.cacheDir, "subtitle_translation_$timestamp").apply { mkdirs() }
        val outputDir = File(context.filesDir, "workflow_subtitle_translation/$timestamp").apply { mkdirs() }
        try {
            var stageStartedAt = System.currentTimeMillis()
            val originalSrtRaw = if (!spec.sourceSubtitlePath.isNullOrBlank()) {
                update(context.getString(R.string.workflow_subtitle_translate_reading_srt), 0.12f)
                File(spec.sourceSubtitlePath).readText()
            } else {
                update(context.getString(R.string.workflow_media_translate_extracting_audio), 0.08f)
                val normalizedAudio = File(workDir, "source.wav")
                stageStartedAt = System.currentTimeMillis()
                runFfmpeg(context, listOf("-y", "-i", spec.videoPath, "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1", normalizedAudio.absolutePath))
                extractionMs = System.currentTimeMillis() - stageStartedAt
                ensureActive()

                update(context.getString(R.string.workflow_media_translate_transcribing_srt), 0.18f)
                val whisperOutputBase = File(workDir, "whisper")
                stageStartedAt = System.currentTimeMillis()
                val sourceDurationMs = readMediaDurationMs(normalizedAudio).takeIf { it > 0L } ?: readMediaDurationMs(File(spec.videoPath))
                runWhisperWithProgress(
                    context = context,
                    audioFile = normalizedAudio,
                    outputBase = whisperOutputBase,
                    whisperModelPath = spec.whisperModelPath
                        ?: throw IllegalStateException(context.getString(R.string.workflow_select_whisper)),
                    whisperLanguage = spec.whisperLanguage,
                    whisperThreads = spec.whisperThreads,
                    mediaDurationMs = sourceDurationMs,
                    baseProgress = 0.18f,
                    progressSpan = 0.14f
                )
                transcriptionMs = System.currentTimeMillis() - stageStartedAt
                File("${whisperOutputBase.absolutePath}.srt").takeIf { it.isFile }?.readText()
                    ?: throw IllegalStateException(context.getString(R.string.workflow_media_translate_error_no_srt))
            }
            val segments = SrtParser.parse(originalSrtRaw)
            require(segments.isNotEmpty()) { context.getString(R.string.workflow_media_translate_error_no_segments) }
            val originalSrt = File(outputDir, "original.srt").apply { writeText(SrtWriter.writeOriginal(segments)) }
            ensureActive()

            val translated = if (spec.translateSubtitles) {
                update(context.getString(R.string.workflow_media_translate_translating), 0.36f)
                stageStartedAt = System.currentTimeMillis()
                translateSegments(
                    context = context,
                    sourceLanguage = spec.whisperLanguage,
                    targetLanguage = spec.targetLanguage,
                    backendSnapshot = spec.backendSnapshot,
                    segments = segments
                ).also { translationMs = System.currentTimeMillis() - stageStartedAt }
            } else {
                update(context.getString(R.string.workflow_subtitle_translate_preparing), 0.36f)
                segments.map { segment ->
                    TranslatedTranscriptSegment(
                        id = segment.id,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        originalText = segment.text,
                        translatedText = segment.text
                    )
                }
            }
            val translatedSrt = File(outputDir, "translated.srt").apply { writeText(SrtWriter.write(translated)) }
            ensureActive()

            val finalOutput = if (spec.burnIntoVideo) {
                update(context.getString(R.string.workflow_subtitle_translate_burning), 0.78f)
                stageStartedAt = System.currentTimeMillis()
                burnTranslatedSubtitles(context, spec, translatedSrt, workDir, outputDir)
                    .also { burnMs = System.currentTimeMillis() - stageStartedAt }
            } else {
                update(context.getString(R.string.workflow_media_translate_exporting), 0.9f)
                translatedSrt
            }
            writeWorkflowMetadata(
                outputDir,
                JSONObject().apply {
                    put("workflow", "subtitle_translation")
                    put("sourceName", spec.videoName)
                    put("targetLanguage", spec.targetLanguage)
                    put("translateSubtitles", spec.translateSubtitles)
                    put("burnIntoVideo", spec.burnIntoVideo)
                    put("segments", segments.size)
                    put("startedAt", startedAt)
                    put("completedAt", System.currentTimeMillis())
                    put("totalDurationMs", System.currentTimeMillis() - startedAt)
                    put("extractAudioDurationMs", extractionMs)
                    put("transcriptionDurationMs", transcriptionMs)
                    put("translationDurationMs", translationMs)
                    put("subtitleBurnDurationMs", burnMs)
                }
            )
            mirrorOutputs(context, listOf(originalSrt, translatedSrt, finalOutput).distinct(), "SubtitleTranslation")
            SubtitleTranslationOutput(originalSrt, translatedSrt, finalOutput)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun translateSegments(
        context: Context,
        sourceLanguage: String,
        targetLanguage: String,
        backendSnapshot: RemoteSummarySettingsSnapshot,
        segments: List<TimedTranscriptSegment>
    ): List<TranslatedTranscriptSegment> {
        val client = RemoteSummaryClientFactory.fromSnapshot(context, backendSnapshot)
        currentRemoteClient = client
        val translated = linkedMapOf<Int, String>()
        val batches = segments.chunked(20)
        batches.forEachIndexed { batchIndex, batch ->
            ensureActive()
            MediaTranslationWorkflowStateHolder.update {
                it.copy(
                    currentChunk = batchIndex + 1,
                    totalChunks = batches.size,
                    progress = 0.3f + ((batchIndex.toFloat() / batches.size.toFloat()) * 0.2f)
                )
            }
            val response = requestTranslation(context, client, sourceLanguage, targetLanguage, backendSnapshot, batch, repair = false)
            val parsed = TranslationJsonValidator.parseAndValidate(response.output, batch).getOrElse {
                val repair = requestTranslation(
                    context = context,
                    client = client,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    backendSnapshot = backendSnapshot,
                    batch = batch,
                    repair = true,
                    previousOutput = response.rawOutput,
                    validationError = it.message
                )
                TranslationJsonValidator.parseAndValidate(repair.output, batch).getOrThrow()
            }
            translated.putAll(parsed)
        }
        return segments.map { segment ->
            TranslatedTranscriptSegment(
                id = segment.id,
                startMs = segment.startMs,
                endMs = segment.endMs,
                originalText = segment.text,
                translatedText = translated[segment.id].orEmpty()
            )
        }
    }

    private suspend fun requestTranslation(
        context: Context,
        client: RemoteSummaryClient,
        sourceLanguage: String,
        targetLanguage: String,
        backendSnapshot: RemoteSummarySettingsSnapshot,
        batch: List<TimedTranscriptSegment>,
        repair: Boolean,
        previousOutput: String? = null,
        validationError: String? = null
    ): RemoteSummaryResponse {
        val prompt = if (repair) {
            buildRepairPrompt(sourceLanguage, targetLanguage, batch, previousOutput.orEmpty(), validationError.orEmpty())
        } else {
            buildTranslationPrompt(sourceLanguage, targetLanguage, batch)
        }
        return client.summarize(
            RemoteSummaryRequest(
                systemPrompt = context.getString(R.string.workflow_media_translate_system_prompt),
                userPrompt = prompt,
                contextSize = backendSnapshot.chunkContext,
                maxTokens = backendSnapshot.chunkMaxTokens,
                temperature = backendSnapshot.temperature,
                thinkingEnabled = backendSnapshot.thinkingEnabled
            )
        )
    }

    private fun buildTranslationPrompt(sourceLanguage: String, targetLanguage: String, batch: List<TimedTranscriptSegment>): String {
        val payload = JSONObject().apply {
            put("sourceLanguage", sourceLanguage)
            put("targetLanguage", targetLanguage)
            put("segments", JSONArray().apply {
                batch.forEach { segment ->
                    put(JSONObject().apply {
                        put("id", segment.id)
                        put("startMs", segment.startMs)
                        put("endMs", segment.endMs)
                        put("text", segment.text)
                    })
                }
            })
        }
        return """
            Translate the transcript segments to $targetLanguage.
            Preserve every segment id exactly. Do not add, remove, split, merge, or reorder segments.
            Return JSON only in this exact shape: {"segments":[{"id":1,"translatedText":"..."}]}.

            Input JSON:
            ${payload.toString()}
        """.trimIndent()
    }

    private fun buildRepairPrompt(
        sourceLanguage: String,
        targetLanguage: String,
        batch: List<TimedTranscriptSegment>,
        previousOutput: String,
        validationError: String
    ): String =
        buildTranslationPrompt(sourceLanguage, targetLanguage, batch) + "\n\nPrevious output was invalid: $validationError\nPrevious output:\n$previousOutput"

    private suspend fun synthesizeTimedAudio(
        context: Context,
        spec: MediaTranslationJobSpec,
        segments: List<TranslatedTranscriptSegment>,
        workDir: File
    ): File {
        val pieces = mutableListOf<File>()
        var cursorMs = 0L
        val pipeline = SupertonicTtsPipeline(context)
        segments.forEachIndexed { index, segment ->
            ensureActive()
            MediaTranslationWorkflowStateHolder.update {
                it.copy(
                    currentChunk = index + 1,
                    totalChunks = segments.size,
                    progress = 0.55f + ((index.toFloat() / max(segments.size, 1).toFloat()) * 0.3f)
                )
            }
            if (segment.startMs > cursorMs) {
                pieces += createSilence(context, workDir, segment.startMs - cursorMs, "gap_$index")
            }
            val result = pipeline.generate(
                OnnxTtsRequest(
                    modelPath = spec.ttsModelPath,
                    modelName = spec.ttsModelName,
                    text = segment.translatedText,
                    language = spec.ttsLanguage,
                    voiceName = spec.ttsVoiceName,
                    totalSteps = spec.ttsSteps,
                    speed = 1.0f,
                    sourceName = "segment_${segment.id}"
                )
            )
            val adjusted = File(workDir, "segment_${segment.id}.wav")
            fitSpeechToWindow(context, result.wavFile, adjusted, result.durationSeconds, segment.durationMs)
            pieces += adjusted
            cursorMs = segment.endMs
        }
        val concatList = File(workDir, "concat.txt").apply {
            writeText(pieces.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" })
        }
        val output = File(workDir, "translated_track.wav")
        runFfmpeg(context, listOf("-y", "-f", "concat", "-safe", "0", "-i", concatList.absolutePath, "-c:a", "pcm_s16le", output.absolutePath))
        return output
    }

    private fun fitSpeechToWindow(context: Context, input: File, output: File, sourceSeconds: Float, targetMs: Long) {
        val targetSeconds = (targetMs.coerceAtLeast(250L).toDouble() / 1000.0)
        val tempo = MediaTranslationAudioTiming.tempoForDuration(sourceSeconds, targetMs)
        val filters = mutableListOf<String>()
        if (tempo > 1.01f || tempo < 0.99f) {
            filters += atempoChain(tempo)
        }
        filters += "apad"
        filters += String.format(Locale.US, "atrim=0:%.3f", targetSeconds)
        runFfmpeg(
            context,
            listOf(
                "-y",
                "-i", input.absolutePath,
                "-af", filters.joinToString(","),
                "-ar", "48000",
                "-ac", "2",
                output.absolutePath
            )
        )
    }

    private fun atempoChain(rawTempo: Float): String {
        var tempo = rawTempo.coerceIn(0.5f, 100f)
        val parts = mutableListOf<Float>()
        while (tempo > 2f) {
            parts += 2f
            tempo /= 2f
        }
        while (tempo < 0.5f) {
            parts += 0.5f
            tempo /= 0.5f
        }
        parts += tempo
        return parts.joinToString(",") { String.format(Locale.US, "atempo=%.4f", it) }
    }

    private fun createSilence(context: Context, workDir: File, durationMs: Long, name: String): File {
        val output = File(workDir, "$name.wav")
        val seconds = durationMs.toDouble() / 1000.0
        runFfmpeg(
            context,
            listOf(
                "-y",
                "-f", "lavfi",
                "-i", "anullsrc=r=48000:cl=stereo",
                "-t", String.format(Locale.US, "%.3f", seconds),
                output.absolutePath
            )
        )
        return output
    }

    private fun runWhisperWithProgress(
        context: Context,
        audioFile: File,
        outputBase: File,
        whisperModelPath: String,
        whisperLanguage: String,
        whisperThreads: Int,
        mediaDurationMs: Long,
        baseProgress: Float,
        progressSpan: Float
    ) {
        val repo = BinaryRepository(context)
        val whisper = repo.getWhisperCliBinary() ?: throw IllegalStateException(context.getString(R.string.whisper_error_binary_not_found))
        val args = listOf(
            whisper.absolutePath,
            "-m", whisperModelPath,
            "-f", audioFile.absolutePath,
            "-l", whisperLanguage,
            "-t", whisperThreads.toString(),
            "--no-gpu",
            "-otxt",
            "-osrt",
            "-of", outputBase.absolutePath
        )
        runProcessWithSrtProgress(
            context = context,
            repo = repo,
            args = args,
            srtFile = File("${outputBase.absolutePath}.srt"),
            mediaDurationMs = mediaDurationMs,
            baseProgress = baseProgress,
            progressSpan = progressSpan
        )
    }

    private fun runProcessWithSrtProgress(
        context: Context,
        repo: BinaryRepository,
        args: List<String>,
        srtFile: File,
        mediaDurationMs: Long,
        baseProgress: Float,
        progressSpan: Float
    ) {
        ensureActive()
        DebugLog.log("[MEDIA-TRANSLATE] ${args.joinToString(" ")}")
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        val symlinkDir = File(context.filesDir, "ffmpeg_libs").apply { mkdirs() }
        pb.environment()["LD_LIBRARY_PATH"] = "${symlinkDir.absolutePath}:${repo.getLibraryDir()}"
        pb.environment()["HOME"] = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        val process = pb.start()
        currentProcess = process
        val output = StringBuilder()
        val readerThread = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (output.length < 24_000) {
                            output.appendLine(line)
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        while (process.isAlive) {
            ensureActive()
            updateWhisperFileProgress(context, srtFile, mediaDurationMs, baseProgress, progressSpan)
            Thread.sleep(1_000L)
        }
        val exit = process.waitFor()
        readerThread.join(1_000L)
        currentProcess = null
        updateWhisperFileProgress(context, srtFile, mediaDurationMs, baseProgress, progressSpan)
        if (output.isNotBlank()) DebugLog.log("[MEDIA-TRANSLATE] ${output.lines().takeLast(10).joinToString("\n")}")
        ensureActive()
        require(exit == 0) { "Process failed with exit code $exit" }
    }

    private fun updateWhisperFileProgress(
        context: Context,
        srtFile: File,
        mediaDurationMs: Long,
        baseProgress: Float,
        progressSpan: Float
    ) {
        val latestMs = latestSrtEndTimestampMs(srtFile)
        val fraction = if (mediaDurationMs > 0L && latestMs > 0L) {
            (latestMs.toFloat() / mediaDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val detail = if (mediaDurationMs > 0L && latestMs > 0L) {
            context.getString(
                R.string.workflow_media_translate_transcribing_detail,
                formatDurationMs(latestMs),
                formatDurationMs(mediaDurationMs)
            )
        } else {
            context.getString(R.string.workflow_media_translate_transcribing_srt)
        }
        val progress = (baseProgress + progressSpan * fraction).coerceIn(0f, 1f)
        MediaTranslationWorkflowStateHolder.update {
            it.copy(
                status = context.getString(R.string.workflow_media_translate_transcribing_srt),
                progress = progress,
                toolProgressDetail = detail
            )
        }
        notificationTaskId?.let { UnifiedNotificationManager.updateProgress(it, progress, detail) }
    }

    private fun latestSrtEndTimestampMs(srtFile: File): Long {
        if (!srtFile.isFile) return 0L
        val regex = Regex("""-->\s*(\d{2}):(\d{2}):(\d{2}),(\d{3})""")
        return runCatching {
            regex.findAll(srtFile.readText())
                .map { match ->
                    val (hours, minutes, seconds, millis) = match.destructured
                    ((hours.toLong() * 60L + minutes.toLong()) * 60L + seconds.toLong()) * 1000L + millis.toLong()
                }
                .maxOrNull() ?: 0L
        }.getOrDefault(0L)
    }

    private fun readMediaDurationMs(file: File): Long {
        if (!file.isFile) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun formatDurationMs(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun burnTranslatedSubtitles(
        context: Context,
        spec: SubtitleTranslationJobSpec,
        translatedSrt: File,
        workDir: File,
        outputDir: File
    ): File {
        val assFile = File(workDir, "translated.ass")
        runFfmpeg(context, listOf("-y", "-i", translatedSrt.absolutePath, assFile.absolutePath))
        val fontsCacheDir = File(workDir, "fonts").apply { mkdirs() }
        runCatching {
            val sourceFontFile = File("/system/fonts/DroidSans.ttf")
            if (sourceFontFile.exists()) {
                sourceFontFile.copyTo(File(fontsCacheDir, "DroidSans.ttf"), overwrite = true)
            }
        }.onFailure { DebugLog.log("[MEDIA-TRANSLATE] Font copy failed: ${it.message}") }
        val fontconfigDir = File(workDir, "fontconfig").apply { mkdirs() }
        val fontsConfFile = File(fontconfigDir, "fonts.conf")
        fontsConfFile.writeText(
            """<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
    <dir>${fontsCacheDir.absolutePath}</dir>
    <dir>/system/fonts</dir>
    <cachedir>${fontconfigDir.absolutePath}/cache</cachedir>
    <match target="pattern">
        <edit name="family" mode="append" binding="weak">
            <string>Droid Sans</string>
        </edit>
    </match>
</fontconfig>"""
        )
        File(fontconfigDir, "cache").mkdirs()

        val selectedFont = if (spec.burnStyle.fontName.isBlank() || spec.burnStyle.fontName == "Default") {
            "Droid Sans"
        } else {
            spec.burnStyle.fontName
        }
        runCatching {
            assFile.writeText(assFile.readText().replace("Arial", selectedFont))
        }
        val colorHex = String.format(
            Locale.US,
            "&H00%02X%02X%02X",
            (spec.burnStyle.primaryColorBlue.coerceIn(0f, 1f) * 255).toInt(),
            (spec.burnStyle.primaryColorGreen.coerceIn(0f, 1f) * 255).toInt(),
            (spec.burnStyle.primaryColorRed.coerceIn(0f, 1f) * 255).toInt()
        )
        val forceStyle = "Fontsize=${spec.burnStyle.fontSize},Alignment=${spec.burnStyle.alignment},MarginV=${spec.burnStyle.marginV},MarginL=${spec.burnStyle.marginL},PrimaryColour=$colorHex,FontName=$selectedFont"
        val subtitleFilter = "subtitles=${assFile.absolutePath}:fontsdir=${fontsCacheDir.absolutePath}:force_style='$forceStyle'"
        val output = File(outputDir, "subtitled_${safeBaseName(spec.videoName)}.mp4")
        runFfmpeg(
            context,
            listOf(
                "-y",
                "-i", spec.videoPath,
                "-vf", subtitleFilter,
                "-c:v", "libx264",
                "-preset", "fast",
                "-crf", "23",
                "-c:a", "copy",
                output.absolutePath
            ),
            extraEnvironment = mapOf(
                "FONTCONFIG_PATH" to fontconfigDir.absolutePath,
                "FONTCONFIG_FILE" to fontsConfFile.absolutePath
            )
        )
        return output
    }

    private fun runFfmpeg(context: Context, args: List<String>, extraEnvironment: Map<String, String> = emptyMap()) {
        val repo = BinaryRepository(context)
        val ffmpeg = repo.getFFmpegBinary() ?: throw IllegalStateException("FFmpeg not found")
        runProcess(context, repo, listOf(ffmpeg.absolutePath) + args, extraEnvironment)
    }

    private fun runProcess(
        context: Context,
        repo: BinaryRepository,
        args: List<String>,
        extraEnvironment: Map<String, String> = emptyMap()
    ) {
        ensureActive()
        DebugLog.log("[MEDIA-TRANSLATE] ${args.joinToString(" ")}")
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        val symlinkDir = File(context.filesDir, "ffmpeg_libs").apply { mkdirs() }
        pb.environment()["LD_LIBRARY_PATH"] = "${symlinkDir.absolutePath}:${repo.getLibraryDir()}"
        pb.environment()["HOME"] = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        extraEnvironment.forEach { (key, value) -> pb.environment()[key] = value }
        val process = pb.start()
        currentProcess = process
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        currentProcess = null
        if (output.isNotBlank()) DebugLog.log("[MEDIA-TRANSLATE] ${output.lines().takeLast(10).joinToString("\n")}")
        ensureActive()
        require(exit == 0) { "Process failed with exit code $exit" }
    }

    private fun update(status: String, progress: Float) {
        MediaTranslationWorkflowStateHolder.update {
            it.copy(status = status, progress = progress.coerceIn(0f, 1f), toolProgressDetail = null)
        }
        notificationTaskId?.let { UnifiedNotificationManager.updateProgress(it, progress.coerceIn(0f, 1f), status) }
    }

    private fun writeWorkflowMetadata(outputDir: File, metadata: JSONObject) {
        runCatching {
            File(outputDir, "workflow_metadata.json").writeText(metadata.toString(2))
        }.onFailure { DebugLog.log("[MEDIA-TRANSLATE] Metadata write failed: ${it.message}") }
    }

    private fun mirrorOutputs(context: Context, files: List<File>, folderName: String = "MediaDubbing") {
        val outputFolderUri = SettingsRepository(context).outputFolderUri.value ?: return
        runCatching {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(outputFolderUri)) ?: return
            val dir = root.findFile(folderName) ?: root.createDirectory(folderName) ?: return
            files.filter { it.isFile }.forEach { file ->
                val mimeType = when (file.extension.lowercase(Locale.US)) {
                    "srt" -> "application/x-subrip"
                    "m4a" -> "audio/mp4"
                    "mp4" -> "video/mp4"
                    "txt" -> "text/plain"
                    else -> "application/octet-stream"
                }
                val target = dir.findFile(file.name) ?: dir.createFile(mimeType, file.name) ?: return@forEach
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
            }
        }.onFailure { DebugLog.log("[MEDIA-TRANSLATE] Output mirror failed: ${it.message}") }
    }

    private fun isVideoSpec(spec: MediaTranslationJobSpec): Boolean =
        spec.sourceMimeType?.startsWith("video/") == true ||
            spec.sourcePath.substringAfterLast('.', "").lowercase(Locale.US) in setOf("mp4", "mkv", "mov", "webm", "avi")

    private fun shouldDubVideo(spec: MediaTranslationJobSpec, isVideo: Boolean): Boolean =
        isVideo && spec.outputMode != MediaTranslationOutputMode.AUDIO_ONLY

    private fun safeBaseName(name: String): String =
        name.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "media" }

    private fun ensureActive() {
        if (cancelled) throw CancellationException("Cancelled")
    }

    private fun acquireWakeLock(context: Context) {
        WakeLockManager.acquire(context.applicationContext, "MediaTranslationWorkflowService")
        WakeLockManager.acquireWifiLock(context.applicationContext, "MediaTranslationWorkflowService")
    }

    private fun nextRunId(): Long {
        currentRunId += 1L
        return currentRunId
    }

    private fun cleanup(runId: Long? = null) {
        if (runId != null && runId != currentRunId) return
        appContext?.let(MediaTranslationForegroundService::stop)
        currentRemoteClient = null
        currentProcess = null
        currentJob = null
        notificationTaskId = null
        WakeLockManager.release("MediaTranslationWorkflowService")
        WakeLockManager.releaseWifiLock("MediaTranslationWorkflowService")
        RemoteSummaryProtection.release()
    }

    private data class MediaTranslationOutput(
        val originalSrt: File,
        val translatedSrt: File,
        val translatedAudio: File,
        val finalOutput: File
    )

    private data class SubtitleTranslationOutput(
        val originalSrt: File,
        val translatedSrt: File,
        val finalOutput: File
    )
}
