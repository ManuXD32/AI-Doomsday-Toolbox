package com.example.llamadroid.service

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

const val MEDIA_TRANSLATION_WORKFLOW_KIND_MEDIA = "media"
const val MEDIA_TRANSLATION_WORKFLOW_KIND_SUBTITLE = "subtitle"

private const val MEDIA_TRANSLATION_WHISPER_LINKER_OOM_MAX_ATTEMPTS = 4

private class WhisperLinkerOutOfMemoryException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

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
    val backendSnapshot: RemoteSummarySettingsSnapshot,
    val translationContextEnabled: Boolean = true,
    val translationContextLines: Int = 2,
    val skipFailedTranslationLines: Boolean = false
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
    val backendSnapshot: RemoteSummarySettingsSnapshot,
    val translationContextEnabled: Boolean = true,
    val translationContextLines: Int = 2,
    val skipFailedTranslationLines: Boolean = false
)

data class MediaTranslationResumeTranslationOverride(
    val targetLanguage: String,
    val backendSnapshot: RemoteSummarySettingsSnapshot,
    val translationContextEnabled: Boolean,
    val translationContextLines: Int,
    val skipFailedTranslationLines: Boolean
)

internal fun mediaTranslationApplyResumeOverride(
    spec: MediaTranslationJobSpec,
    override: MediaTranslationResumeTranslationOverride
): MediaTranslationJobSpec =
    spec.copy(
        targetLanguage = override.targetLanguage,
        backendSnapshot = override.backendSnapshot,
        translationContextEnabled = override.translationContextEnabled,
        translationContextLines = override.translationContextLines.coerceIn(0, 10),
        skipFailedTranslationLines = override.skipFailedTranslationLines
    )

internal fun mediaTranslationApplyResumeOverride(
    spec: SubtitleTranslationJobSpec,
    override: MediaTranslationResumeTranslationOverride
): SubtitleTranslationJobSpec =
    spec.copy(
        targetLanguage = override.targetLanguage,
        backendSnapshot = override.backendSnapshot,
        translationContextEnabled = override.translationContextEnabled,
        translationContextLines = override.translationContextLines.coerceIn(0, 10),
        skipFailedTranslationLines = override.skipFailedTranslationLines
    )

data class MediaTranslationWorkflowState(
    val workflowKind: String? = null,
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
    val skippedTranslationLineCount: Int = 0,
    val cancelled: Boolean = false,
    val paused: Boolean = false
)

data class MediaTranslationRecoverableInfo(
    val kind: String,
    val title: String,
    val sourceName: String,
    val completedCount: Int,
    val pendingCount: Int,
    val totalCount: Int,
    val currentStage: String?
)

data class MediaTranslationInputCleanupResult(
    val deletedCount: Int,
    val freedBytes: Long
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

internal fun mediaTranslationBuildLineTranslationPrompt(
    sourceLanguage: String,
    targetLanguage: String,
    segments: List<TimedTranscriptSegment>,
    targetIndex: Int,
    includeContext: Boolean,
    contextLines: Int
): String {
    val target = segments.getOrNull(targetIndex) ?: error("Missing target segment")
    val safeContextLines = contextLines.coerceIn(0, 10)
    val contextBefore = if (includeContext && safeContextLines > 0) {
        segments.subList((targetIndex - safeContextLines).coerceAtLeast(0), targetIndex)
    } else {
        emptyList()
    }
    val contextAfter = if (includeContext && safeContextLines > 0) {
        segments.subList(targetIndex + 1, (targetIndex + 1 + safeContextLines).coerceAtMost(segments.size))
    } else {
        emptyList()
    }
    return buildString {
        appendLine("Source language hint: $sourceLanguage")
        appendLine("Target language: $targetLanguage")
        appendLine("Use the CONTEXT to translate the TARGET line.")
        appendLine("Return only the translated TARGET line. Do not include explanations, labels, JSON, or markdown.")
        appendLine()
        appendLine("[TARGET] ${target.text}")
        if (includeContext) {
            appendLine()
            appendLine("[CONTEXT]")
            contextBefore.forEach { appendLine(it.text) }
            appendLine(target.text)
            contextAfter.forEach { appendLine(it.text) }
            appendLine("[/CONTEXT]")
        }
    }.trim()
}

internal fun mediaTranslationBuildIsolatedLineTranslationPrompt(
    sourceLanguage: String,
    targetLanguage: String,
    sourceText: String
): String =
    buildString {
        appendLine("Translate this single subtitle line.")
        appendLine("Source language hint: $sourceLanguage")
        appendLine("Target language: $targetLanguage")
        appendLine()
        appendLine("Rules:")
        appendLine("- Return exactly one non-empty line.")
        appendLine("- Return only the translation, with no label, quote, JSON, markdown, or explanation.")
        appendLine("- If the source is a name, sound effect, fragment, or cannot be translated, copy the source text exactly.")
        appendLine("- Do not return the source text when a natural translation is possible.")
        appendLine()
        appendLine("Source:")
        append(sourceText)
    }.trim()

internal fun mediaTranslationCleanLineTranslation(raw: String): String {
    var text = raw.trim()
    if (text.startsWith("```")) {
        text = text
            .removePrefix("```json")
            .removePrefix("```text")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
    runCatching {
        val json = JSONObject(text)
        listOf("translatedText", "translation", "text", "target").firstNotNullOfOrNull { key ->
            json.optString(key).takeIf { it.isNotBlank() }
        }
    }.getOrNull()?.let { return it.trim() }

    val labelRegex = Regex(
        pattern = """(?i)^\s*(translation|translated text|target|answer|respuesta|traducci[oó]n)\s*[:\-]\s*"""
    )
    text = text.replace(labelRegex, "").trim()
    if (text.startsWith("[TARGET]", ignoreCase = true)) {
        text = text.removePrefix("[TARGET]").trim()
    }
    return text.trim().trim('"', '\'').trim()
}

internal fun mediaTranslationCleanLineTranslationCandidate(raw: String): String? =
    mediaTranslationCleanLineTranslation(raw)
        .takeIf { it.isNotBlank() && !mediaTranslationLooksLikePromptEcho(it) }

internal fun mediaTranslationShouldRetryLineTranslation(raw: String?, error: Throwable? = null): Boolean =
    error != null || raw == null || mediaTranslationCleanLineTranslationCandidate(raw) == null

internal fun mediaTranslationDiagnosticSnippet(value: String?, maxChars: Int = 180): String {
    val clean = value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()
    if (clean.isBlank()) return "<empty>"
    return if (clean.length <= maxChars) clean else clean.take(maxChars).trimEnd() + "..."
}

internal fun mediaTranslationLooksLikePromptEcho(text: String): Boolean {
    val lower = text.lowercase(Locale.US)
    return "[context]" in lower ||
        "[/context]" in lower ||
        "use the context to translate" in lower ||
        "source language hint:" in lower
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
    private const val RECOVERABLE_MEDIA_JOB_FILE = "recoverable_media_job.json"
    private const val RECOVERABLE_SUBTITLE_JOB_FILE = "recoverable_subtitle_job.json"
    private const val KIND_MEDIA = MEDIA_TRANSLATION_WORKFLOW_KIND_MEDIA
    private const val KIND_SUBTITLE = MEDIA_TRANSLATION_WORKFLOW_KIND_SUBTITLE
    private const val CHECKPOINTS_DIR = "checkpoints"
    private const val MAX_LINE_TRANSLATION_ATTEMPTS = 5
    private const val MAX_LINE_TRANSLATION_FALLBACK_ATTEMPTS = 2
    private const val MAX_LINE_TRANSLATION_RECOVERY_ATTEMPTS = 2
    private const val LINE_TRANSLATION_RETRY_DELAY_MS = 700L
    private const val LINE_TRANSLATION_MAX_OUTPUT_TOKENS = 160
    private const val WORKFLOW_INPUTS_DIR = "workflow_media_inputs"
    private const val STAGE_AUDIO_EXTRACTED = "audio_extracted"
    private const val STAGE_TRANSCRIBED = "transcribed"
    private const val STAGE_ORIGINAL_READY = "original_ready"
    private const val STAGE_TRANSLATED = "translated"
    private const val STAGE_FINAL_EXPORTED = "final_exported"
    private const val TRANSCRIPTION_COMPLETE_TOLERANCE_MS = 5_000L
    private const val TRANSCRIPTION_RESUME_BACKUP_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    private var currentProcess: Process? = null
    private var currentRemoteClient: RemoteSummaryClient? = null
    private var notificationTaskId: Int? = null
    private var appContext: Context? = null
    private var currentRunId: Long = 0L
    @Volatile private var currentKind: String? = null
    @Volatile private var cancelled = false
    @Volatile private var pauseRequested = false

    private data class MediaTranslationJobCheckpoint(
        val index: Int,
        val displayName: String,
        val workDirPath: String,
        val outputDirPath: String,
        val completedStages: Set<String> = emptySet(),
        val currentStage: String? = null,
        val originalSrtPath: String? = null,
        val translatedSrtPath: String? = null,
        val finalOutputPath: String? = null,
        val translatedSegmentCount: Int = 0,
        val totalSegmentCount: Int = 0,
        val skippedSegmentIds: Set<Int> = emptySet(),
        val mediaDurationMs: Long = 0L
    ) {
        fun withStage(stage: String): MediaTranslationJobCheckpoint =
            copy(completedStages = completedStages + stage, currentStage = stage)
    }

    private data class MediaTranslationRecoverableRuntime(
        val kind: String,
        val mediaSpecs: List<MediaTranslationJobSpec>,
        val subtitleSpecs: List<SubtitleTranslationJobSpec>,
        val doneJobIndexes: List<Int>,
        val pendingJobIndexes: List<Int>,
        val title: String,
        val checkpoints: List<MediaTranslationJobCheckpoint> = emptyList()
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

        val pendingCount: Int
            get() = pendingJobIndexes.distinct().count { it in 0 until totalCount }

        fun checkpointFor(index: Int): MediaTranslationJobCheckpoint? =
            checkpoints.firstOrNull { it.index == index }

        fun displayNameFor(index: Int): String {
            checkpointFor(index)?.displayName?.takeIf { it.isNotBlank() }?.let { return it }
            return when (kind) {
                KIND_SUBTITLE -> subtitleSpecs.getOrNull(index)?.videoName
                else -> mediaSpecs.getOrNull(index)?.sourceName
            }.orEmpty().ifBlank { title }
        }

        fun resumeInfo(): MediaTranslationRecoverableInfo? {
            if (!hasRemainingWork) return null
            val index = pendingJobIndexes.firstOrNull { it in 0 until totalCount } ?: return null
            val checkpoint = checkpointFor(index)
            return MediaTranslationRecoverableInfo(
                kind = kind,
                title = title,
                sourceName = displayNameFor(index),
                completedCount = completedCount,
                pendingCount = pendingCount,
                totalCount = totalCount,
                currentStage = checkpoint?.currentStage
            )
        }

        fun validateInputs(): String? {
            return when (kind) {
                KIND_SUBTITLE -> pendingJobIndexes.firstNotNullOfOrNull { index ->
                    val spec = subtitleSpecs.getOrNull(index) ?: return@firstNotNullOfOrNull ""
                    val paths = mutableListOf(spec.videoPath)
                    if (spec.sourceSubtitlePath.isNullOrBlank()) {
                        if (spec.whisperModelPath.isNullOrBlank()) {
                            paths += ""
                        }
                    } else {
                        paths += spec.sourceSubtitlePath
                    }
                    firstMissingPath(
                        paths
                    )
                }
                else -> pendingJobIndexes.firstNotNullOfOrNull { index ->
                    val spec = mediaSpecs.getOrNull(index) ?: return@firstNotNullOfOrNull ""
                    val paths = mutableListOf(spec.sourcePath, spec.ttsModelPath)
                    if (spec.whisperModelPath.isBlank()) {
                        paths += ""
                    }
                    firstMissingPath(paths)
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

    fun requestResumeMedia(
        context: Context,
        translationOverride: MediaTranslationResumeTranslationOverride? = null
    ) {
        requestResume(context, expectedKind = KIND_MEDIA, translationOverride = translationOverride)
    }

    fun requestResumeSubtitle(
        context: Context,
        translationOverride: MediaTranslationResumeTranslationOverride? = null
    ) {
        requestResume(context, expectedKind = KIND_SUBTITLE, translationOverride = translationOverride)
    }

    fun discardRecoverableMediaRuntime(context: Context) {
        discardRecoverableRuntime(context, KIND_MEDIA)
    }

    fun discardRecoverableSubtitleRuntime(context: Context) {
        discardRecoverableRuntime(context, KIND_SUBTITLE)
    }

    private fun discardRecoverableRuntime(context: Context, kind: String) {
        val app = context.applicationContext
        if (currentJob?.isActive == true && currentKind == kind) {
            cancel(app, clearRecoverable = true)
        } else {
            clearRecoverableRuntime(app, kind)
            MediaTranslationWorkflowStateHolder.update {
                it.copy(paused = false, cancelled = true, errorMessage = null)
            }
        }
    }

    private fun requestResume(
        context: Context,
        expectedKind: String?,
        translationOverride: MediaTranslationResumeTranslationOverride? = null
    ) {
        if (currentJob?.isActive == true) return
        val loadedState = if (expectedKind == null) {
            listOf(KIND_MEDIA, KIND_SUBTITLE)
                .firstNotNullOfOrNull { kind -> readRecoverableRuntime(context.applicationContext, kind)?.takeIf { it.hasRemainingWork } }
        } else {
            readRecoverableRuntime(context.applicationContext, expectedKind)
        } ?: return
        val state = if (translationOverride != null) {
            loadedState.withTranslationOverride(translationOverride)
        } else {
            loadedState
        }
        if (!state.hasRemainingWork) {
            clearRecoverableRuntime(context.applicationContext, state.kind)
            return
        }
        val validationError = state.validateInputs()
        if (validationError != null) {
            MediaTranslationWorkflowStateHolder.update {
                it.copy(
                    workflowKind = expectedKind,
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

    private fun MediaTranslationRecoverableRuntime.withTranslationOverride(
        override: MediaTranslationResumeTranslationOverride
    ): MediaTranslationRecoverableRuntime =
        when (kind) {
            KIND_MEDIA -> copy(
                mediaSpecs = mediaSpecs.map { mediaTranslationApplyResumeOverride(it, override) }
            )
            KIND_SUBTITLE -> copy(
                subtitleSpecs = subtitleSpecs.map {
                    if (!it.translateSubtitles) {
                        it
                    } else {
                        mediaTranslationApplyResumeOverride(it, override)
                    }
                }
            )
            else -> this
        }

    fun hasRecoverableRuntime(context: Context): Boolean =
        listOf(KIND_MEDIA, KIND_SUBTITLE).any { kind ->
            readRecoverableRuntime(context.applicationContext, kind)?.hasRemainingWork == true
        }

    fun hasRecoverableMediaRuntime(context: Context): Boolean =
        readRecoverableRuntime(context.applicationContext, KIND_MEDIA)?.hasRemainingWork == true

    fun hasRecoverableSubtitleRuntime(context: Context): Boolean =
        readRecoverableRuntime(context.applicationContext, KIND_SUBTITLE)?.hasRemainingWork == true

    fun getRecoverableMediaRuntimeInfo(context: Context): MediaTranslationRecoverableInfo? =
        readRecoverableRuntime(context.applicationContext, KIND_MEDIA)?.resumeInfo()

    fun getRecoverableSubtitleRuntimeInfo(context: Context): MediaTranslationRecoverableInfo? =
        readRecoverableRuntime(context.applicationContext, KIND_SUBTITLE)?.resumeInfo()

    fun workflowImportedInputBytes(context: Context): Long =
        workflowInputDir(context.applicationContext).takeIf { it.isDirectory }?.sumFileBytes().orZero()

    fun clearWorkflowImportedInputs(context: Context): MediaTranslationInputCleanupResult {
        val inputDir = workflowInputDir(context.applicationContext)
        if (!inputDir.isDirectory) return MediaTranslationInputCleanupResult(0, 0L)
        var deletedCount = 0
        var freedBytes = 0L
        inputDir.listFiles().orEmpty().forEach { file ->
            val bytes = file.sumFileBytes()
            if (file.deleteRecursively()) {
                deletedCount += 1
                freedBytes += bytes
            }
        }
        return MediaTranslationInputCleanupResult(deletedCount, freedBytes)
    }

    private fun workflowInputDir(context: Context): File =
        File(context.filesDir, WORKFLOW_INPUTS_DIR).apply { mkdirs() }

    private fun recoverableRuntimeFile(context: Context, kind: String): File {
        val name = when (kind) {
            KIND_SUBTITLE -> RECOVERABLE_SUBTITLE_JOB_FILE
            else -> RECOVERABLE_MEDIA_JOB_FILE
        }
        return File(context.filesDir, RUNTIME_DIR).apply { mkdirs() }.resolve(name)
    }

    private fun legacyRecoverableRuntimeFile(context: Context): File =
        File(context.filesDir, RUNTIME_DIR).apply { mkdirs() }.resolve(RECOVERABLE_JOB_FILE)

    private fun recoverableCheckpointRoot(context: Context): File =
        File(File(context.filesDir, RUNTIME_DIR).apply { mkdirs() }, CHECKPOINTS_DIR).apply { mkdirs() }

    private fun writeRecoverableRuntime(context: Context, runtime: MediaTranslationRecoverableRuntime) {
        runCatching {
            recoverableRuntimeFile(context.applicationContext, runtime.kind).writeText(runtime.toJson().toString(2))
        }.onFailure {
            DebugLog.log("[MEDIA-TRANSLATE] Recoverable runtime write failed: ${it.message}")
        }
    }

    private fun readRecoverableRuntime(context: Context, expectedKind: String? = null): MediaTranslationRecoverableRuntime? {
        val app = context.applicationContext
        val candidateFiles = when (expectedKind) {
            KIND_MEDIA, KIND_SUBTITLE -> listOf(recoverableRuntimeFile(app, expectedKind))
            else -> listOf(recoverableRuntimeFile(app, KIND_MEDIA), recoverableRuntimeFile(app, KIND_SUBTITLE))
        }
        candidateFiles.firstNotNullOfOrNull { file ->
            readRecoverableRuntimeFile(file)?.takeIf { expectedKind == null || it.kind == expectedKind }
        }?.let { return it }
        return readRecoverableRuntimeFile(legacyRecoverableRuntimeFile(app))
            ?.takeIf { expectedKind == null || it.kind == expectedKind }
    }

    private fun readRecoverableRuntimeFile(file: File): MediaTranslationRecoverableRuntime? {
        if (!file.isFile) return null
        return runCatching {
            JSONObject(file.readText()).toRecoverableRuntime()
        }.onFailure {
            DebugLog.log("[MEDIA-TRANSLATE] Recoverable runtime read failed: ${it.message}")
        }.getOrNull()
    }

    private fun clearRecoverableRuntime(
        context: Context,
        kind: String? = null,
        preserveInputPaths: Set<String> = emptySet()
    ) {
        runCatching {
            val app = context.applicationContext
            val kinds = kind?.let(::listOf) ?: listOf(KIND_MEDIA, KIND_SUBTITLE)
            kinds.forEach { runtimeKind ->
                readRecoverableRuntime(app, runtimeKind)?.let { runtime ->
                    deleteRecoverableScratch(app, runtime, preserveInputPaths)
                    recoverableRuntimeFile(app, runtimeKind).delete()
                    if (legacyRecoverableRuntimeFile(app).isFile) {
                        readRecoverableRuntimeFile(legacyRecoverableRuntimeFile(app))
                            ?.takeIf { it.kind == runtimeKind }
                            ?.let { legacyRecoverableRuntimeFile(app).delete() }
                    }
                } ?: recoverableRuntimeFile(app, runtimeKind).delete()
            }
            if (kind == null) legacyRecoverableRuntimeFile(app).delete()
        }
    }

    private fun deleteRecoverableScratch(
        context: Context,
        runtime: MediaTranslationRecoverableRuntime,
        preserveInputPaths: Set<String> = emptySet()
    ) {
        runtime.checkpoints
            .map { File(it.workDirPath) }
            .filter { it.isDirectory }
            .forEach { it.deleteRecursively() }
        deleteRuntimeInputFiles(context.applicationContext, runtime, preserveInputPaths)
    }

    private fun deleteRuntimeInputFiles(
        context: Context,
        runtime: MediaTranslationRecoverableRuntime,
        preserveInputPaths: Set<String> = emptySet()
    ) {
        runtime.inputPaths()
            .filterNot { it in preserveInputPaths }
            .map { File(it) }
            .filter { it.isFile && it.isUnderWorkflowInputDir(context.applicationContext) }
            .forEach { it.delete() }
    }

    private fun MediaTranslationRecoverableRuntime.inputPaths(): List<String> =
        when (kind) {
            KIND_SUBTITLE -> subtitleSpecs.flatMap { spec -> listOfNotNull(spec.videoPath, spec.sourceSubtitlePath) }
            else -> mediaSpecs.map { it.sourcePath }
        }.filter { it.isNotBlank() }

    private fun File.isUnderWorkflowInputDir(context: Context): Boolean =
        runCatching {
            val rootPath = workflowInputDir(context.applicationContext).canonicalFile.toPath()
            canonicalFile.toPath().startsWith(rootPath)
        }.getOrDefault(false)

    private fun File.sumFileBytes(): Long =
        if (isFile) length() else listFiles().orEmpty().sumOf { it.sumFileBytes() }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun updateRecoverableQueues(context: Context, kind: String, doneJobIndexes: List<Int>, pendingJobIndexes: List<Int>) {
        val runtime = readRecoverableRuntime(context.applicationContext, kind) ?: return
        val (done, pending) = sanitizeRecoverableQueues(runtime.totalCount, doneJobIndexes, pendingJobIndexes)
        writeRecoverableRuntime(
            context.applicationContext,
            runtime.copy(doneJobIndexes = done, pendingJobIndexes = pending)
        )
    }

    private fun updateRecoverableCheckpoint(context: Context, kind: String, checkpoint: MediaTranslationJobCheckpoint) {
        val runtime = readRecoverableRuntime(context.applicationContext, kind) ?: return
        val updated = (runtime.checkpoints.filterNot { it.index == checkpoint.index } + checkpoint)
            .sortedBy { it.index }
        writeRecoverableRuntime(context.applicationContext, runtime.copy(checkpoints = updated))
    }

    private fun recoverableCheckpointsForQueueStart(
        context: Context,
        kind: String,
        replaceExisting: Boolean,
        totalCount: Int
    ): List<MediaTranslationJobCheckpoint> {
        if (replaceExisting) return emptyList()
        return readRecoverableRuntime(context.applicationContext, kind)
            ?.checkpoints
            .orEmpty()
            .filter { it.index in 0 until totalCount }
            .sortedBy { it.index }
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
                    val checkpoint = checkpointFor(index)
                    put(
                        JSONObject().apply {
                            put("index", index)
                            put("sourceName", checkpoint?.displayName ?: name)
                            put("status", if (index in doneJobIndexes) "done" else "pending")
                            checkpoint?.currentStage?.let { put("currentStage", it) }
                        }
                    )
                }
            })
            put("checkpoints", JSONArray().apply { checkpoints.forEach { put(it.toJson()) } })
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
        val checkpoints = optJSONArray("checkpoints").toJsonObjectList()
            .mapNotNull { it.toJobCheckpointOrNull(total) }
        return MediaTranslationRecoverableRuntime(
            kind = kind,
            mediaSpecs = mediaSpecs,
            subtitleSpecs = subtitleSpecs,
            doneJobIndexes = doneJobIndexes,
            pendingJobIndexes = pendingJobIndexes,
            title = optString("title").ifBlank {
                if (kind == KIND_SUBTITLE) "Subtitle translation" else "Media translation"
            },
            checkpoints = checkpoints
        )
    }

    private fun MediaTranslationJobCheckpoint.toJson(): JSONObject =
        JSONObject().apply {
            put("index", index)
            put("displayName", displayName)
            put("workDirPath", workDirPath)
            put("outputDirPath", outputDirPath)
            put("completedStages", JSONArray().apply { completedStages.sorted().forEach { put(it) } })
            putNullable("currentStage", currentStage)
            putNullable("originalSrtPath", originalSrtPath)
            putNullable("translatedSrtPath", translatedSrtPath)
            putNullable("finalOutputPath", finalOutputPath)
            put("translatedSegmentCount", translatedSegmentCount)
            put("totalSegmentCount", totalSegmentCount)
            put("skippedSegmentIds", JSONArray().apply { skippedSegmentIds.sorted().forEach { put(it) } })
            put("mediaDurationMs", mediaDurationMs)
        }

    private fun JSONObject.toJobCheckpointOrNull(total: Int): MediaTranslationJobCheckpoint? {
        val index = optInt("index", -1)
        if (index !in 0 until total) return null
        val workDirPath = optString("workDirPath")
        val outputDirPath = optString("outputDirPath")
        if (workDirPath.isBlank() || outputDirPath.isBlank()) return null
        return MediaTranslationJobCheckpoint(
            index = index,
            displayName = optString("displayName").ifBlank { optString("sourceName", "media") },
            workDirPath = workDirPath,
            outputDirPath = outputDirPath,
            completedStages = optJSONArray("completedStages").toStringSet(),
            currentStage = optNullableString("currentStage"),
            originalSrtPath = optNullableString("originalSrtPath"),
            translatedSrtPath = optNullableString("translatedSrtPath"),
            finalOutputPath = optNullableString("finalOutputPath"),
            translatedSegmentCount = optInt("translatedSegmentCount", 0),
            totalSegmentCount = optInt("totalSegmentCount", 0),
            skippedSegmentIds = optJSONArray("skippedSegmentIds").toIntList().toSet(),
            mediaDurationMs = optLong("mediaDurationMs", 0L)
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

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }.toSet()
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
            put("translationContextEnabled", translationContextEnabled)
            put("translationContextLines", translationContextLines)
            put("skipFailedTranslationLines", skipFailedTranslationLines)
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
            backendSnapshot = optJSONObject("backendSnapshot").toRemoteSummarySnapshot(),
            translationContextEnabled = optBoolean("translationContextEnabled", true),
            translationContextLines = optInt("translationContextLines", 2).coerceIn(0, 10),
            skipFailedTranslationLines = optBoolean("skipFailedTranslationLines", false)
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
            put("translationContextEnabled", translationContextEnabled)
            put("translationContextLines", translationContextLines)
            put("skipFailedTranslationLines", skipFailedTranslationLines)
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
            backendSnapshot = optJSONObject("backendSnapshot").toRemoteSummarySnapshot(),
            translationContextEnabled = optBoolean("translationContextEnabled", true),
            translationContextLines = optInt("translationContextLines", 2).coerceIn(0, 10),
            skipFailedTranslationLines = optBoolean("skipFailedTranslationLines", false)
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
        if (replaceExisting) {
            val preserveInputs = specs.map { it.sourcePath }.toSet()
            if (currentJob?.isActive == true && currentKind == KIND_MEDIA) {
                cancel(context, clearRecoverable = false)
            }
            clearRecoverableRuntime(context, KIND_MEDIA, preserveInputPaths = preserveInputs)
        }
        val (initialDone, initialPending) = sanitizeRecoverableQueues(specs.size, doneJobIndexes.toList(), pendingJobIndexes)
        if (initialPending.isEmpty()) return
        appContext = context.applicationContext
        currentKind = KIND_MEDIA
        cancelled = false
        pauseRequested = false
        val runId = nextRunId()
        val title = if (specs.size == 1) {
            context.getString(R.string.workflow_media_translate_notification_title, specs.first().sourceName)
        } else {
            context.getString(R.string.workflow_media_translate_batch_notification_title, specs.size)
        }
        val preservedCheckpoints = recoverableCheckpointsForQueueStart(
            context = context,
            kind = KIND_MEDIA,
            replaceExisting = replaceExisting,
            totalCount = specs.size
        )
        writeRecoverableRuntime(
            context,
            MediaTranslationRecoverableRuntime(
                kind = KIND_MEDIA,
                mediaSpecs = specs,
                subtitleSpecs = emptyList(),
                doneJobIndexes = initialDone,
                pendingJobIndexes = initialPending,
                title = title,
                checkpoints = preservedCheckpoints
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
                workflowKind = KIND_MEDIA,
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
                    updateRecoverableQueues(context.applicationContext, KIND_MEDIA, done, pending)
                    MediaTranslationWorkflowStateHolder.update {
                        it.copy(
                            currentBatchItem = (done.size + 1).coerceAtMost(specs.size),
                            totalBatchItems = specs.size,
                            currentChunk = 0,
                            totalChunks = 0,
                            status = context.getString(R.string.workflow_batch_processing_item, index + 1, specs.size, item.sourceName)
                        )
                    }
                    outputs += runWorkflow(context.applicationContext, item, index)
                    pending.remove(index)
                    if (index !in done) done += index
                    updateRecoverableQueues(context.applicationContext, KIND_MEDIA, done, pending)
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
                        clearRecoverableRuntime(context.applicationContext, KIND_MEDIA)
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
                                skippedTranslationLineCount = output.skippedTranslationLineCount,
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
        if (replaceExisting) {
            val preserveInputs = specs.flatMap { listOfNotNull(it.videoPath, it.sourceSubtitlePath) }.toSet()
            if (currentJob?.isActive == true && currentKind == KIND_SUBTITLE) {
                cancel(context, clearRecoverable = false)
            }
            clearRecoverableRuntime(context, KIND_SUBTITLE, preserveInputPaths = preserveInputs)
        }
        val (initialDone, initialPending) = sanitizeRecoverableQueues(specs.size, doneJobIndexes.toList(), pendingJobIndexes)
        if (initialPending.isEmpty()) return
        appContext = context.applicationContext
        currentKind = KIND_SUBTITLE
        cancelled = false
        pauseRequested = false
        val runId = nextRunId()
        val title = if (specs.size == 1) {
            context.getString(R.string.workflow_subtitle_translate_notification_title, specs.first().videoName)
        } else {
            context.getString(R.string.workflow_subtitle_translate_batch_notification_title, specs.size)
        }
        val preservedCheckpoints = recoverableCheckpointsForQueueStart(
            context = context,
            kind = KIND_SUBTITLE,
            replaceExisting = replaceExisting,
            totalCount = specs.size
        )
        writeRecoverableRuntime(
            context,
            MediaTranslationRecoverableRuntime(
                kind = KIND_SUBTITLE,
                mediaSpecs = emptyList(),
                subtitleSpecs = specs,
                doneJobIndexes = initialDone,
                pendingJobIndexes = initialPending,
                title = title,
                checkpoints = preservedCheckpoints
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
                workflowKind = KIND_SUBTITLE,
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
                    updateRecoverableQueues(context.applicationContext, KIND_SUBTITLE, done, pending)
                    MediaTranslationWorkflowStateHolder.update {
                        it.copy(
                            currentBatchItem = (done.size + 1).coerceAtMost(specs.size),
                            totalBatchItems = specs.size,
                            currentChunk = 0,
                            totalChunks = 0,
                            status = context.getString(R.string.workflow_batch_processing_item, index + 1, specs.size, item.videoName)
                        )
                    }
                    outputs += runSubtitleWorkflow(context.applicationContext, item, index)
                    pending.remove(index)
                    if (index !in done) done += index
                    updateRecoverableQueues(context.applicationContext, KIND_SUBTITLE, done, pending)
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
                        clearRecoverableRuntime(context.applicationContext, KIND_SUBTITLE)
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
                                skippedTranslationLineCount = output.skippedTranslationLineCount,
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
            (context?.applicationContext ?: appContext)?.let { clearRecoverableRuntime(it, currentKind) }
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

    private suspend fun runWorkflow(context: Context, spec: MediaTranslationJobSpec, jobIndex: Int): MediaTranslationOutput = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val startedAt = System.currentTimeMillis()
        var extractionMs = 0L
        var transcriptionMs = 0L
        var translationMs: Long
        var ttsMs: Long
        var audioExportMs: Long
        var checkpoint = ensureJobCheckpoint(
            context = context,
            kind = KIND_MEDIA,
            index = jobIndex,
            displayName = spec.sourceName,
            outputFolderName = "workflow_media_translation",
            timestamp = timestamp
        )
        val workDir = File(checkpoint.workDirPath).apply { mkdirs() }
        val outputDir = File(checkpoint.outputDirPath).apply { mkdirs() }
            val isVideo = isVideoSpec(spec)
            val normalizedAudio = File(workDir, "source.wav")
            update(context.getString(R.string.workflow_media_translate_extracting_audio), 0.08f)
            var stageStartedAt = System.currentTimeMillis()
            if (normalizedAudio.isUsableCheckpointFile()) {
                update(context.getString(R.string.workflow_media_checkpoint_reusing_audio), 0.14f)
                checkpoint = checkpoint.withStage(STAGE_AUDIO_EXTRACTED)
                updateRecoverableCheckpoint(context, KIND_MEDIA, checkpoint)
            } else {
                runFfmpeg(context, listOf("-y", "-i", spec.sourcePath, "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1", normalizedAudio.absolutePath))
                extractionMs = System.currentTimeMillis() - stageStartedAt
                checkpoint = checkpoint.withStage(STAGE_AUDIO_EXTRACTED)
                updateRecoverableCheckpoint(context, KIND_MEDIA, checkpoint)
            }
            ensureActive()

            update(context.getString(R.string.workflow_media_translate_transcribing_srt), 0.18f)
            val whisperOutputBase = File(workDir, "whisper")
            stageStartedAt = System.currentTimeMillis()
            val sourceDurationMs = readMediaDurationMs(normalizedAudio).takeIf { it > 0L } ?: readMediaDurationMs(File(spec.sourcePath))
            ensureWhisperTranscriptCheckpoint(
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
            checkpoint = checkpoint.withStage(STAGE_TRANSCRIBED).copy(mediaDurationMs = sourceDurationMs)
            updateRecoverableCheckpoint(context, KIND_MEDIA, checkpoint)
            val originalTxt = File("${whisperOutputBase.absolutePath}.txt").takeIf { it.isFile }?.readText().orEmpty()
            val originalSrtRaw = File("${whisperOutputBase.absolutePath}.srt").takeIf { it.isFile }?.readText()
                ?: throw IllegalStateException(context.getString(R.string.workflow_media_translate_error_no_srt))
            val segments = SrtParser.parse(originalSrtRaw)
            require(segments.isNotEmpty()) { context.getString(R.string.workflow_media_translate_error_no_segments) }
            val originalSrt = File(outputDir, mediaTranslationOriginalSubtitleFileName(spec.sourceName, timestamp))
                .apply { writeText(SrtWriter.writeOriginal(segments)) }
            File(outputDir, "original_transcript.txt").writeText(originalTxt.ifBlank { segments.joinToString("\n") { it.text } })
            checkpoint = checkpoint.withStage(STAGE_ORIGINAL_READY).copy(
                originalSrtPath = originalSrt.absolutePath,
                totalSegmentCount = segments.size,
                mediaDurationMs = sourceDurationMs
            )
            updateRecoverableCheckpoint(context, KIND_MEDIA, checkpoint)
            ensureActive()

            update(context.getString(R.string.workflow_media_translate_translating), 0.3f)
            stageStartedAt = System.currentTimeMillis()
            val translatedResult = translateSegments(
                context = context,
                sourceLanguage = spec.whisperLanguage,
                targetLanguage = spec.targetLanguage,
                backendSnapshot = spec.backendSnapshot,
                translationContextEnabled = spec.translationContextEnabled,
                translationContextLines = spec.translationContextLines,
                skipFailedTranslationLines = spec.skipFailedTranslationLines,
                segments = segments,
                checkpoint = checkpoint,
                kind = KIND_MEDIA,
                baseProgress = 0.3f,
                progressSpan = 0.2f
            )
            val translated = translatedResult.segments
            checkpoint = translatedResult.checkpoint
            translationMs = System.currentTimeMillis() - stageStartedAt
            val translatedSrt = File(outputDir, mediaTranslationTranslatedSubtitleFileName(spec.sourceName, timestamp))
                .apply { writeText(SrtWriter.write(translated)) }
            checkpoint = checkpoint.withStage(STAGE_TRANSLATED).copy(
                translatedSrtPath = translatedSrt.absolutePath,
                translatedSegmentCount = translated.size,
                totalSegmentCount = segments.size,
                skippedSegmentIds = translatedResult.skippedSegmentIds
            )
            updateRecoverableCheckpoint(context, KIND_MEDIA, checkpoint)
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
            checkpoint = checkpoint.withStage(STAGE_FINAL_EXPORTED).copy(finalOutputPath = finalOutput.absolutePath)
            updateRecoverableCheckpoint(context, KIND_MEDIA, checkpoint)
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
                    put("skippedTranslationLines", translatedResult.skippedSegmentIds.size)
                    put("ttsDurationMs", ttsMs)
                    put("audioExportDurationMs", audioExportMs)
                    put("muxOrExportDurationMs", mediaBurnMs)
                }
            )
            mirrorOutputs(context, listOf(originalSrt, translatedSrt, translatedAudio, finalOutput).distinct())
            MediaTranslationOutput(originalSrt, translatedSrt, translatedAudio, finalOutput, checkpoint.skippedSegmentIds.size)
    }

    private suspend fun runSubtitleWorkflow(context: Context, spec: SubtitleTranslationJobSpec, jobIndex: Int): SubtitleTranslationOutput = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val startedAt = System.currentTimeMillis()
        var extractionMs = 0L
        var transcriptionMs = 0L
        var translationMs = 0L
        var burnMs = 0L
        var checkpoint = ensureJobCheckpoint(
            context = context,
            kind = KIND_SUBTITLE,
            index = jobIndex,
            displayName = spec.videoName,
            outputFolderName = "workflow_subtitle_translation",
            timestamp = timestamp
        )
        val workDir = File(checkpoint.workDirPath).apply { mkdirs() }
        val outputDir = File(checkpoint.outputDirPath).apply { mkdirs() }
            var stageStartedAt = System.currentTimeMillis()
            val originalSrtRaw = if (!spec.sourceSubtitlePath.isNullOrBlank()) {
                update(context.getString(R.string.workflow_subtitle_translate_reading_srt), 0.12f)
                val checkpointedOriginalSrt = File(workDir, "original.srt")
                if (checkpointedOriginalSrt.hasParseableSegments()) {
                    update(context.getString(R.string.workflow_media_checkpoint_reusing_original_srt), 0.22f)
                    checkpoint = checkpoint.withStage(STAGE_ORIGINAL_READY).copy(originalSrtPath = checkpointedOriginalSrt.absolutePath)
                    updateRecoverableCheckpoint(context, KIND_SUBTITLE, checkpoint)
                    checkpointedOriginalSrt.readText()
                } else {
                    File(spec.sourceSubtitlePath).readText().also { raw ->
                        checkpointedOriginalSrt.writeText(raw)
                        checkpoint = checkpoint.withStage(STAGE_ORIGINAL_READY).copy(originalSrtPath = checkpointedOriginalSrt.absolutePath)
                        updateRecoverableCheckpoint(context, KIND_SUBTITLE, checkpoint)
                    }
                }
            } else {
                update(context.getString(R.string.workflow_media_translate_extracting_audio), 0.08f)
                val normalizedAudio = File(workDir, "source.wav")
                stageStartedAt = System.currentTimeMillis()
                if (normalizedAudio.isUsableCheckpointFile()) {
                    update(context.getString(R.string.workflow_media_checkpoint_reusing_audio), 0.14f)
                    checkpoint = checkpoint.withStage(STAGE_AUDIO_EXTRACTED)
                    updateRecoverableCheckpoint(context, KIND_SUBTITLE, checkpoint)
                } else {
                    runFfmpeg(context, listOf("-y", "-i", spec.videoPath, "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1", normalizedAudio.absolutePath))
                    extractionMs = System.currentTimeMillis() - stageStartedAt
                    checkpoint = checkpoint.withStage(STAGE_AUDIO_EXTRACTED)
                    updateRecoverableCheckpoint(context, KIND_SUBTITLE, checkpoint)
                }
                ensureActive()

                update(context.getString(R.string.workflow_media_translate_transcribing_srt), 0.18f)
                val whisperOutputBase = File(workDir, "whisper")
                stageStartedAt = System.currentTimeMillis()
                val sourceDurationMs = readMediaDurationMs(normalizedAudio).takeIf { it > 0L } ?: readMediaDurationMs(File(spec.videoPath))
                ensureWhisperTranscriptCheckpoint(
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
                checkpoint = checkpoint.withStage(STAGE_TRANSCRIBED).copy(mediaDurationMs = sourceDurationMs)
                updateRecoverableCheckpoint(context, KIND_SUBTITLE, checkpoint)
                File("${whisperOutputBase.absolutePath}.srt").takeIf { it.isFile }?.readText()
                    ?: throw IllegalStateException(context.getString(R.string.workflow_media_translate_error_no_srt))
            }
            val segments = SrtParser.parse(originalSrtRaw)
            require(segments.isNotEmpty()) { context.getString(R.string.workflow_media_translate_error_no_segments) }
            val originalSrt = File(outputDir, mediaTranslationOriginalSubtitleFileName(spec.videoName, timestamp))
                .apply { writeText(SrtWriter.writeOriginal(segments)) }
            checkpoint = checkpoint.withStage(STAGE_ORIGINAL_READY).copy(
                originalSrtPath = originalSrt.absolutePath,
                totalSegmentCount = segments.size
            )
            updateRecoverableCheckpoint(context, KIND_SUBTITLE, checkpoint)
            ensureActive()

            val translated = if (spec.translateSubtitles) {
                update(context.getString(R.string.workflow_media_translate_translating), 0.36f)
                stageStartedAt = System.currentTimeMillis()
                val translatedResult = translateSegments(
                    context = context,
                    sourceLanguage = spec.whisperLanguage,
                    targetLanguage = spec.targetLanguage,
                    backendSnapshot = spec.backendSnapshot,
                    translationContextEnabled = spec.translationContextEnabled,
                    translationContextLines = spec.translationContextLines,
                    skipFailedTranslationLines = spec.skipFailedTranslationLines,
                    segments = segments,
                    checkpoint = checkpoint,
                    kind = KIND_SUBTITLE,
                    baseProgress = 0.36f,
                    progressSpan = 0.36f
                )
                checkpoint = translatedResult.checkpoint
                translatedResult.segments.also { translationMs = System.currentTimeMillis() - stageStartedAt }
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
            val translatedSrt = File(outputDir, mediaTranslationTranslatedSubtitleFileName(spec.videoName, timestamp))
                .apply { writeText(SrtWriter.write(translated)) }
            checkpoint = checkpoint.withStage(STAGE_TRANSLATED).copy(
                translatedSrtPath = translatedSrt.absolutePath,
                translatedSegmentCount = translated.size,
                totalSegmentCount = segments.size,
                skippedSegmentIds = if (spec.translateSubtitles) checkpoint.skippedSegmentIds else emptySet()
            )
            updateRecoverableCheckpoint(context, KIND_SUBTITLE, checkpoint)
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
            checkpoint = checkpoint.withStage(STAGE_FINAL_EXPORTED).copy(finalOutputPath = finalOutput.absolutePath)
            updateRecoverableCheckpoint(context, KIND_SUBTITLE, checkpoint)
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
                    put("skippedTranslationLines", checkpoint.skippedSegmentIds.size)
                    put("subtitleBurnDurationMs", burnMs)
                }
            )
            mirrorOutputs(context, listOf(originalSrt, translatedSrt, finalOutput).distinct(), "SubtitleTranslation")
            SubtitleTranslationOutput(originalSrt, translatedSrt, finalOutput, checkpoint.skippedSegmentIds.size)
    }

    private data class TranslationCheckpointLoad(
        val translations: Map<Int, String>,
        val skippedSegmentIds: Set<Int>,
        val wasCorrupt: Boolean
    )

    private data class TranslationCheckpointResult(
        val segments: List<TranslatedTranscriptSegment>,
        val checkpoint: MediaTranslationJobCheckpoint,
        val skippedSegmentIds: Set<Int>
    )

    private fun ensureJobCheckpoint(
        context: Context,
        kind: String,
        index: Int,
        displayName: String,
        outputFolderName: String,
        timestamp: Long
    ): MediaTranslationJobCheckpoint {
        val runtime = readRecoverableRuntime(context.applicationContext, kind)
        runtime?.checkpointFor(index)?.let { checkpoint ->
            File(checkpoint.workDirPath).mkdirs()
            File(checkpoint.outputDirPath).mkdirs()
            return checkpoint
        }
        val safeName = safeBaseName(displayName)
        val workDir = File(recoverableCheckpointRoot(context), "${kind}_${index}_${safeName}_$timestamp").apply { mkdirs() }
        val outputDir = File(context.filesDir, "$outputFolderName/$timestamp").apply { mkdirs() }
        val checkpoint = MediaTranslationJobCheckpoint(
            index = index,
            displayName = displayName.ifBlank { safeName },
            workDirPath = workDir.absolutePath,
            outputDirPath = outputDir.absolutePath
        )
        updateRecoverableCheckpoint(context.applicationContext, kind, checkpoint)
        return checkpoint
    }

    private fun File.isUsableCheckpointFile(): Boolean =
        isFile && length() > 0L

    private fun File.hasParseableSegments(): Boolean =
        isFile && runCatching { SrtParser.parse(readText()).isNotEmpty() }.getOrDefault(false)

    private fun loadTranslatedSegmentsCheckpoint(
        file: File?,
        expectedSegments: List<TimedTranscriptSegment>
    ): TranslationCheckpointLoad {
        if (file == null || !file.isFile) return TranslationCheckpointLoad(emptyMap(), emptySet(), wasCorrupt = false)
        return runCatching {
            val expectedIds = expectedSegments.map { it.id }.toSet()
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("translations") ?: JSONArray()
            val translations = linkedMapOf<Int, String>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optInt("id", Int.MIN_VALUE)
                val text = item.optString("translatedText").trim()
                if (id in expectedIds && text.isNotBlank()) translations[id] = text
            }
            val sanitized = mediaTranslationSanitizeCheckpointTranslations(translations, expectedSegments)
            val skipped = root.optJSONArray("skippedSegmentIds")
                .toIntList()
                .filter { it in sanitized.keys }
                .toSet()
            TranslationCheckpointLoad(
                sanitized,
                skipped,
                wasCorrupt = false
            )
        }.getOrElse {
            DebugLog.log("[MEDIA-TRANSLATE] Translation checkpoint ignored: ${it.message}")
            TranslationCheckpointLoad(emptyMap(), emptySet(), wasCorrupt = true)
        }
    }

    private fun saveTranslatedSegmentsCheckpoint(
        file: File,
        translations: Map<Int, String>,
        skippedSegmentIds: Set<Int>,
        expectedSegments: List<TimedTranscriptSegment>
    ) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                JSONObject().apply {
                    put("totalSegments", expectedSegments.size)
                    put("translatedSegmentCount", translations.size)
                    put("skippedSegmentIds", JSONArray().apply { skippedSegmentIds.sorted().forEach { put(it) } })
                    put("translations", JSONArray().apply {
                        expectedSegments.forEach { segment ->
                            translations[segment.id]?.let { translatedText ->
                                put(
                                    JSONObject().apply {
                                        put("id", segment.id)
                                        put("translatedText", translatedText)
                                    }
                                )
                            }
                        }
                    })
                    put("updatedAt", System.currentTimeMillis())
                }.toString(2)
            )
        }.onFailure {
            DebugLog.log("[MEDIA-TRANSLATE] Translation checkpoint write failed: ${it.message}")
        }
    }

    private fun buildTranslatedSegments(
        segments: List<TimedTranscriptSegment>,
        translations: Map<Int, String>
    ): List<TranslatedTranscriptSegment> =
        segments.map { segment ->
            TranslatedTranscriptSegment(
                id = segment.id,
                startMs = segment.startMs,
                endMs = segment.endMs,
                originalText = segment.text,
                translatedText = translations[segment.id].orEmpty()
            )
        }

    private suspend fun translateSegments(
        context: Context,
        sourceLanguage: String,
        targetLanguage: String,
        backendSnapshot: RemoteSummarySettingsSnapshot,
        translationContextEnabled: Boolean,
        translationContextLines: Int,
        skipFailedTranslationLines: Boolean,
        segments: List<TimedTranscriptSegment>,
        checkpoint: MediaTranslationJobCheckpoint,
        kind: String,
        baseProgress: Float,
        progressSpan: Float
    ): TranslationCheckpointResult {
        val client = RemoteSummaryClientFactory.fromSnapshot(context, backendSnapshot)
        currentRemoteClient = client
        val partialFile = File(checkpoint.workDirPath, "translated_partial.json")
        val partialLoad = loadTranslatedSegmentsCheckpoint(partialFile, segments)
        if (partialLoad.wasCorrupt) {
            update(context.getString(R.string.workflow_media_checkpoint_invalid_restarting_stage), baseProgress)
        }
        val translated = linkedMapOf<Int, String>().apply { putAll(partialLoad.translations) }
        val skippedSegmentIds = partialLoad.skippedSegmentIds.toMutableSet()
        updateTranslationProgress(context, translated.size, segments.size, translated.size + 1, segments.size, baseProgress, progressSpan)
        segments.forEachIndexed { segmentIndex, segment ->
            ensureActive()
            if (translated.containsKey(segment.id)) {
                updateTranslationProgress(context, translated.size, segments.size, segmentIndex + 1, segments.size, baseProgress, progressSpan)
                return@forEachIndexed
            }
            updateTranslationProgress(context, translated.size, segments.size, segmentIndex + 1, segments.size, baseProgress, progressSpan)
            val cleaned = translateLineWithRetries(
                context = context,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                backendSnapshot = backendSnapshot,
                client = client,
                segments = segments,
                targetIndex = segmentIndex,
                translationContextEnabled = translationContextEnabled,
                translationContextLines = translationContextLines,
                skipFailedTranslationLines = skipFailedTranslationLines
            )
            val finalText = cleaned ?: segment.text.also {
                skippedSegmentIds += segment.id
                DebugLog.log("[MEDIA-TRANSLATE] Skipped failed translation line ${segment.id}; original text preserved.")
            }
            translated[segment.id] = finalText
            saveTranslatedSegmentsCheckpoint(partialFile, translated, skippedSegmentIds, segments)
            val checkpointSrt = File(checkpoint.workDirPath, "translated.srt")
            checkpointSrt.writeText(SrtWriter.write(buildTranslatedSegments(segments, translated)))
            val updatedCheckpoint = checkpoint.copy(
                currentStage = STAGE_TRANSLATED,
                translatedSegmentCount = translated.size,
                totalSegmentCount = segments.size,
                skippedSegmentIds = skippedSegmentIds,
                translatedSrtPath = checkpointSrt.absolutePath
            )
            updateRecoverableCheckpoint(context, kind, updatedCheckpoint)
            updateTranslationProgress(context, translated.size, segments.size, segmentIndex + 1, segments.size, baseProgress, progressSpan)
        }
        val outputSegments = buildTranslatedSegments(segments, translated)
        val updatedCheckpoint = checkpoint.copy(
            currentStage = STAGE_TRANSLATED,
            translatedSegmentCount = outputSegments.size,
            totalSegmentCount = segments.size,
            skippedSegmentIds = skippedSegmentIds,
            translatedSrtPath = File(checkpoint.workDirPath, "translated.srt").absolutePath
        )
        return TranslationCheckpointResult(outputSegments, updatedCheckpoint, skippedSegmentIds)
    }

    private suspend fun translateLineWithRetries(
        context: Context,
        sourceLanguage: String,
        targetLanguage: String,
        backendSnapshot: RemoteSummarySettingsSnapshot,
        client: RemoteSummaryClient,
        segments: List<TimedTranscriptSegment>,
        targetIndex: Int,
        translationContextEnabled: Boolean,
        translationContextLines: Int,
        skipFailedTranslationLines: Boolean
    ): String? {
        val segment = segments[targetIndex]
        var previousOutput: String? = null
        var lastReason = context.getString(R.string.workflow_media_translate_error_unusable_answer)
        var lastRawOutput: String? = null
        var lastCleanedOutput: String? = null
        var lastBackendLabel = mediaTranslationBackendLabel(backendSnapshot)
        var attemptsUsed = 0
        suspend fun runAttempt(
            attempt: Int,
            maxAttempts: Int,
            attemptClient: RemoteSummaryClient,
            attemptSnapshot: RemoteSummarySettingsSnapshot,
            strict: Boolean,
            forceNoContext: Boolean,
            isolatedPrompt: Boolean = false
        ): String? {
            ensureActive()
            lastBackendLabel = mediaTranslationBackendLabel(attemptSnapshot)
            if (attempt > 1) {
                val detail = context.getString(
                    R.string.workflow_media_translate_retrying_line,
                    segment.id,
                    attempt,
                    maxAttempts
                )
                MediaTranslationWorkflowStateHolder.update { it.copy(toolProgressDetail = detail) }
                notificationTaskId?.let { UnifiedNotificationManager.updateProgress(it, MediaTranslationWorkflowStateHolder.state.value.progress, detail) }
                delay(LINE_TRANSLATION_RETRY_DELAY_MS * (attempt - 1))
            }
            attemptsUsed += 1
            val response = runCatching {
                requestLineTranslation(
                    context = context,
                    client = attemptClient,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    backendSnapshot = attemptSnapshot,
                    segments = segments,
                    targetIndex = targetIndex,
                    translationContextEnabled = translationContextEnabled && !forceNoContext,
                    translationContextLines = translationContextLines,
                    repair = attempt > 1,
                    previousOutput = previousOutput,
                    strict = strict,
                    forceNoContext = forceNoContext,
                    isolatedPrompt = isolatedPrompt
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                lastReason = error.message ?: context.getString(R.string.error_generic)
                DebugLog.log("[MEDIA-TRANSLATE] Line ${segment.id} translation attempt $attempt failed: $lastReason")
                null
            } ?: return null
            previousOutput = response.rawOutput.ifBlank { response.output }
            lastRawOutput = response.rawOutput
            lastCleanedOutput = response.output
            if (!mediaTranslationShouldRetryLineTranslation(response.output)) {
                return mediaTranslationCleanLineTranslationCandidate(response.output)
            }
            lastReason = context.getString(R.string.workflow_media_translate_error_unusable_answer)
            DebugLog.log(
                "[MEDIA-TRANSLATE] Line ${segment.id} translation attempt $attempt unusable: " +
                    "raw=${mediaTranslationDiagnosticSnippet(response.rawOutput)} " +
                    "cleaned=${mediaTranslationDiagnosticSnippet(response.output)} " +
                    "tokens=${response.completionTokens}"
            )
            return null
        }

        for (attempt in 1..MAX_LINE_TRANSLATION_ATTEMPTS) {
            runAttempt(
                attempt = attempt,
                maxAttempts = MAX_LINE_TRANSLATION_ATTEMPTS,
                attemptClient = client,
                attemptSnapshot = backendSnapshot,
                strict = attempt >= 2,
                forceNoContext = attempt == MAX_LINE_TRANSLATION_ATTEMPTS
            )?.let { return it }
        }
        if (shouldTrySaferLiteRtTranslationFallback(backendSnapshot)) {
            val detail = context.getString(R.string.workflow_media_translate_litert_fallback_line, segment.id)
            MediaTranslationWorkflowStateHolder.update { it.copy(toolProgressDetail = detail) }
            notificationTaskId?.let { UnifiedNotificationManager.updateProgress(it, MediaTranslationWorkflowStateHolder.state.value.progress, detail) }
            DebugLog.log("[MEDIA-TRANSLATE] Line ${segment.id} switching to LiteRT CPU/no-MTP fallback after $attemptsUsed failed attempts.")
            val fallbackSnapshot = backendSnapshot.copy(
                liteRtBackend = LITERT_BACKEND_CPU,
                liteRtMtpEnabled = false,
                temperature = 0f
            )
            val fallbackClient = RemoteSummaryClientFactory.fromSnapshot(context, fallbackSnapshot)
            currentRemoteClient = fallbackClient
            try {
                for (attempt in 1..MAX_LINE_TRANSLATION_FALLBACK_ATTEMPTS) {
                    runAttempt(
                        attempt = attempt,
                        maxAttempts = MAX_LINE_TRANSLATION_FALLBACK_ATTEMPTS,
                        attemptClient = fallbackClient,
                        attemptSnapshot = fallbackSnapshot,
                        strict = true,
                        forceNoContext = true
                    )?.let { return it }
                }
            } finally {
                currentRemoteClient = client
            }
        }
        val recoverySnapshot = mediaTranslationLineRecoverySnapshot(backendSnapshot)
        val recoveryDetail = context.getString(R.string.workflow_media_translate_recovering_line, segment.id)
        MediaTranslationWorkflowStateHolder.update { it.copy(toolProgressDetail = recoveryDetail) }
        notificationTaskId?.let { UnifiedNotificationManager.updateProgress(it, MediaTranslationWorkflowStateHolder.state.value.progress, recoveryDetail) }
        DebugLog.log(
            "[MEDIA-TRANSLATE] Line ${segment.id} starting fresh isolated recovery after $attemptsUsed failed attempts. " +
                "backend=${mediaTranslationBackendLabel(recoverySnapshot)} source=${mediaTranslationDiagnosticSnippet(segment.text)}"
        )
        val recoveryClient = RemoteSummaryClientFactory.fromSnapshot(context, recoverySnapshot)
        currentRemoteClient = recoveryClient
        try {
            for (attempt in 1..MAX_LINE_TRANSLATION_RECOVERY_ATTEMPTS) {
                runAttempt(
                    attempt = attempt,
                    maxAttempts = MAX_LINE_TRANSLATION_RECOVERY_ATTEMPTS,
                    attemptClient = recoveryClient,
                    attemptSnapshot = recoverySnapshot,
                    strict = true,
                    forceNoContext = true,
                    isolatedPrompt = true
                )?.let { return it }
            }
        } finally {
            currentRemoteClient = client
        }
        if (skipFailedTranslationLines) {
            return null
        }
        val detail = context.getString(
            R.string.workflow_media_translate_error_line_failure_detail,
            lastReason,
            mediaTranslationDisplaySnippet(context, lastRawOutput),
            mediaTranslationDisplaySnippet(context, lastCleanedOutput),
            mediaTranslationDisplaySnippet(context, segment.text),
            lastBackendLabel
        )
        throw IllegalStateException(
            context.getString(
                R.string.workflow_media_translate_error_line_failed_after_retries,
                segment.id,
                attemptsUsed,
                detail
            )
        )
    }

    private suspend fun requestLineTranslation(
        context: Context,
        client: RemoteSummaryClient,
        sourceLanguage: String,
        targetLanguage: String,
        backendSnapshot: RemoteSummarySettingsSnapshot,
        segments: List<TimedTranscriptSegment>,
        targetIndex: Int,
        translationContextEnabled: Boolean,
        translationContextLines: Int,
        repair: Boolean,
        previousOutput: String? = null,
        strict: Boolean = false,
        forceNoContext: Boolean = false,
        isolatedPrompt: Boolean = false
    ): RemoteSummaryResponse {
        val prompt = if (isolatedPrompt) {
            mediaTranslationBuildIsolatedLineTranslationPrompt(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                sourceText = segments.getOrNull(targetIndex)?.text.orEmpty()
            )
        } else {
            mediaTranslationBuildLineTranslationPrompt(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                segments = segments,
                targetIndex = targetIndex,
                includeContext = translationContextEnabled && !forceNoContext,
                contextLines = translationContextLines.coerceIn(0, 10)
            )
        }.let { basePrompt ->
            if (!repair && !strict) {
                basePrompt
            } else {
                buildString {
                    appendLine(basePrompt)
                    appendLine()
                    appendLine("Strict retry rules:")
                    appendLine("- Return exactly one non-empty translated line.")
                    appendLine("- Do not return punctuation-only text unless the source line is punctuation-only.")
                    appendLine("- If the line is a name, sound, or fragment, translate it literally or copy it if there is no translation.")
                    appendLine("- Do not include explanations, labels, quotes, JSON, markdown, or the source line.")
                    if (repair) {
                        appendLine()
                        appendLine("The previous answer was not usable because it was empty, punctuation-only, or repeated the prompt.")
                        appendLine("Previous answer:")
                        appendLine(previousOutput.orEmpty())
                    }
                    appendLine()
                    append(if (isolatedPrompt) "Return only the translated line." else "Return only the translated TARGET line.")
                }
            }
        }
        return client.summarize(
            RemoteSummaryRequest(
                systemPrompt = context.getString(R.string.workflow_media_translate_system_prompt),
                userPrompt = prompt,
                contextSize = backendSnapshot.chunkContext,
                maxTokens = minOf(backendSnapshot.chunkMaxTokens.coerceAtLeast(64), LINE_TRANSLATION_MAX_OUTPUT_TOKENS),
                temperature = if (repair || strict) minOf(backendSnapshot.temperature, 0.05f) else minOf(backendSnapshot.temperature, 0.2f),
                thinkingEnabled = backendSnapshot.thinkingEnabled && !repair && !strict && !isolatedPrompt
            )
        )
    }

    private fun shouldTrySaferLiteRtTranslationFallback(snapshot: RemoteSummarySettingsSnapshot): Boolean {
        val backend = SettingsRepository.normalizeOllamaOrLlamaBackend(snapshot.backend)
        if (backend != SettingsRepository.PDF_BACKEND_LITERT || snapshot.liteRtModelId == null) return false
        val normalizedLiteRtBackend = snapshot.liteRtBackend.trim().lowercase(Locale.US)
        return snapshot.liteRtMtpEnabled || normalizedLiteRtBackend != LITERT_BACKEND_CPU
    }

    private fun mediaTranslationLineRecoverySnapshot(snapshot: RemoteSummarySettingsSnapshot): RemoteSummarySettingsSnapshot {
        val backend = SettingsRepository.normalizeOllamaOrLlamaBackend(snapshot.backend)
        val safer = snapshot.copy(
            temperature = 0f,
            thinkingEnabled = false,
            chunkMaxTokens = snapshot.chunkMaxTokens.coerceAtLeast(64)
        )
        return if (backend == SettingsRepository.PDF_BACKEND_LITERT && snapshot.liteRtModelId != null) {
            safer.copy(
                liteRtBackend = LITERT_BACKEND_CPU,
                liteRtMtpEnabled = false
            )
        } else {
            safer
        }
    }

    private fun mediaTranslationBackendLabel(snapshot: RemoteSummarySettingsSnapshot): String {
        val backend = SettingsRepository.normalizeOllamaOrLlamaBackend(snapshot.backend)
        return if (backend == SettingsRepository.PDF_BACKEND_LITERT) {
            "LiteRT(${snapshot.liteRtBackend}, mtp=${snapshot.liteRtMtpEnabled})"
        } else {
            backend
        }
    }

    private fun mediaTranslationDisplaySnippet(context: Context, value: String?): String {
        val snippet = mediaTranslationDiagnosticSnippet(value)
        return if (snippet == "<empty>") {
            context.getString(R.string.workflow_media_translate_error_empty_snippet)
        } else {
            snippet
        }
    }

    @Deprecated("Kept for old tests and repair diagnostics; media workflows now translate one segment per request.")
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

    private suspend fun ensureWhisperTranscriptCheckpoint(
        context: Context,
        audioFile: File,
        outputBase: File,
        whisperModelPath: String,
        whisperLanguage: String,
        whisperThreads: Int,
        mediaDurationMs: Long,
        baseProgress: Float,
        progressSpan: Float
    ): File {
        val checkpointSrt = File("${outputBase.absolutePath}.srt")
        val existingSegments = checkpointSrt.parseSrtSegmentsOrEmpty()
        val latestCheckpointMs = existingSegments.maxOfOrNull { it.endMs } ?: 0L
        if (existingSegments.isNotEmpty() && isTranscriptCheckpointComplete(latestCheckpointMs, mediaDurationMs)) {
            update(context.getString(R.string.workflow_media_checkpoint_reusing_transcript), baseProgress + progressSpan)
            updateWhisperFileProgress(context, checkpointSrt, mediaDurationMs, baseProgress, progressSpan, latestCheckpointMs)
            return checkpointSrt
        }

        if (existingSegments.isNotEmpty() && latestCheckpointMs > 0L) {
            val resumeStartMs = mediaTranslationResumeStartMs(latestCheckpointMs, TRANSCRIPTION_RESUME_BACKUP_MS)
            val preservedSegments = mediaTranslationTrimSegmentsForResume(existingSegments, resumeStartMs)
            val trimmedCount = existingSegments.size - preservedSegments.size
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "media_translation_workflow",
                event = "transcription_resume_tail",
                details = "previousMs=$latestCheckpointMs resumeMs=$resumeStartMs trimmed=$trimmedCount kept=${preservedSegments.size}"
            )
            update(context.getString(R.string.workflow_media_checkpoint_resuming_transcript), baseProgress)
            checkpointSrt.parentFile?.mkdirs()
            checkpointSrt.writeText(SrtWriter.writeOriginal(preservedSegments))
            val tailAudio = File(outputBase.parentFile ?: audioFile.parentFile, "whisper_resume_${resumeStartMs}.wav")
            val tailOutputBase = File(outputBase.parentFile ?: audioFile.parentFile, "whisper_resume_${resumeStartMs}")
            runFfmpeg(
                context,
                listOf(
                    "-y",
                    "-ss", String.format(Locale.US, "%.3f", resumeStartMs / 1000.0),
                    "-i", audioFile.absolutePath,
                    "-acodec", "pcm_s16le",
                    "-ar", "16000",
                    "-ac", "1",
                    tailAudio.absolutePath
                )
            )
            runWhisperWithProgress(
                context = context,
                audioFile = tailAudio,
                outputBase = tailOutputBase,
                whisperModelPath = whisperModelPath,
                whisperLanguage = whisperLanguage,
                whisperThreads = whisperThreads,
                mediaDurationMs = mediaDurationMs,
                baseProgress = baseProgress,
                progressSpan = progressSpan,
                checkpointSrtFile = checkpointSrt,
                existingCheckpointSegments = preservedSegments,
                timestampOffsetMs = resumeStartMs
            )
            val tailSegments = File("${tailOutputBase.absolutePath}.srt")
                .parseSrtSegmentsOrEmpty()
                .map { it.copy(startMs = it.startMs + resumeStartMs, endMs = it.endMs + resumeStartMs) }
            val liveCheckpointSegments = checkpointSrt.parseSrtSegmentsOrEmpty()
            val merged = mergeTranscriptSegments(
                liveCheckpointSegments.ifEmpty { preservedSegments },
                tailSegments
            )
            if (merged.isNotEmpty()) {
                checkpointSrt.parentFile?.mkdirs()
                checkpointSrt.writeText(SrtWriter.writeOriginal(merged))
            }
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "media_translation_workflow",
                event = "transcription_resume_merged",
                details = "tail=${tailSegments.size} merged=${merged.size}"
            )
            return checkpointSrt
        }

        runWhisperWithProgress(
            context = context,
            audioFile = audioFile,
            outputBase = outputBase,
            whisperModelPath = whisperModelPath,
            whisperLanguage = whisperLanguage,
            whisperThreads = whisperThreads,
            mediaDurationMs = mediaDurationMs,
            baseProgress = baseProgress,
            progressSpan = progressSpan
        )
        return checkpointSrt
    }

    private fun File.parseSrtSegmentsOrEmpty(): List<TimedTranscriptSegment> =
        if (!isFile) {
            emptyList()
        } else {
            runCatching { SrtParser.parse(readText()) }
                .getOrElse {
                    DebugLog.log("[MEDIA-TRANSLATE] Transcript checkpoint ignored: ${it.message}")
                    emptyList()
                }
        }

    private fun isTranscriptCheckpointComplete(latestMs: Long, mediaDurationMs: Long): Boolean =
        latestMs > 0L && mediaDurationMs > 0L && latestMs >= (mediaDurationMs - TRANSCRIPTION_COMPLETE_TOLERANCE_MS).coerceAtLeast(0L)

    private fun mergeTranscriptSegments(
        existingSegments: List<TimedTranscriptSegment>,
        newSegments: List<TimedTranscriptSegment>
    ): List<TimedTranscriptSegment> =
        mediaTranslationMergeTranscriptSegments(existingSegments, newSegments)

    private suspend fun runWhisperWithProgress(
        context: Context,
        audioFile: File,
        outputBase: File,
        whisperModelPath: String,
        whisperLanguage: String,
        whisperThreads: Int,
        mediaDurationMs: Long,
        baseProgress: Float,
        progressSpan: Float,
        checkpointSrtFile: File = File("${outputBase.absolutePath}.srt"),
        existingCheckpointSegments: List<TimedTranscriptSegment> = emptyList(),
        timestampOffsetMs: Long = 0L
    ) {
        val repo = BinaryRepository(context)
        val whisper = repo.getWhisperCliBinary() ?: throw IllegalStateException(context.getString(R.string.whisper_error_binary_not_found))
        val resolvedWhisperModelPath = WhisperModelPathResolver.resolve(context, whisperModelPath)
            ?: throw IllegalStateException(context.getString(R.string.whisper_error_no_model))
        val whisperCandidates = whisperExecutableCandidates(repo, whisper)
        var lastLinkerFailure: WhisperLinkerOutOfMemoryException? = null
        whisperCandidates.forEachIndexed { index, whisperCandidate ->
            val args = listOf(
                whisperCandidate.absolutePath,
                "-m", resolvedWhisperModelPath,
                "-f", audioFile.absolutePath,
                "-l", whisperLanguage,
                "-t", whisperThreads.toString(),
                "--no-gpu",
                "-otxt",
                "-osrt",
                "-of", outputBase.absolutePath
            )
            try {
                runProcessWithSrtProgress(
                    context = context,
                    repo = repo,
                    args = args,
                    srtFile = checkpointSrtFile,
                    existingSegments = existingCheckpointSegments,
                    timestampOffsetMs = timestampOffsetMs,
                    mediaDurationMs = mediaDurationMs,
                    baseProgress = baseProgress,
                    progressSpan = progressSpan,
                    retryWhisperLinkerOutOfMemory = true
                )
                return
            } catch (e: WhisperLinkerOutOfMemoryException) {
                lastLinkerFailure = e
                val next = whisperCandidates.getOrNull(index + 1)
                if (next != null) {
                    DebugLog.log(
                        "[MEDIA-TRANSLATE] Whisper native loader failed for ${whisperCandidate.absolutePath}. " +
                            "Trying alternate installed executable ${next.absolutePath}."
                    )
                }
            } catch (e: Exception) {
                if (lastLinkerFailure != null) {
                    DebugLog.log(
                        "[MEDIA-TRANSLATE] Alternate Whisper executable ${whisperCandidate.absolutePath} also failed: ${e.message}"
                    )
                } else {
                    throw e
                }
            }
        }
        throw lastLinkerFailure ?: IllegalStateException(context.getString(R.string.whisper_error_binary_not_found))
    }

    private fun whisperExecutableCandidates(repo: BinaryRepository, selected: File): List<File> {
        val candidates = linkedSetOf<File>()
        candidates += selected
        repo.getLibraryDir().split(File.pathSeparatorChar)
            .map { File(it, selected.name) }
            .filterTo(candidates) { it.exists() && it.isFile }
        return candidates.toList()
    }

    private fun runProcessWithSrtProgress(
        context: Context,
        repo: BinaryRepository,
        args: List<String>,
        srtFile: File,
        existingSegments: List<TimedTranscriptSegment> = emptyList(),
        timestampOffsetMs: Long = 0L,
        mediaDurationMs: Long,
        baseProgress: Float,
        progressSpan: Float,
        retryWhisperLinkerOutOfMemory: Boolean = false
    ) {
        val maxAttempts = if (retryWhisperLinkerOutOfMemory) MEDIA_TRANSLATION_WHISPER_LINKER_OOM_MAX_ATTEMPTS else 1
        val latestOutputTimestampMs = AtomicLong(existingSegments.maxOfOrNull { it.endMs } ?: timestampOffsetMs)
        val partialSegments = mergeTranscriptSegments(existingSegments, emptyList()).toMutableList()
        val partialSegmentsLock = Any()
        var lastRetryOutput = ""

        for (attempt in 1..maxAttempts) {
            ensureActive()
            DebugLog.log("[MEDIA-TRANSLATE] ${args.joinToString(" ")}")
            val output = StringBuilder()
            var process: Process? = null
            var readerThread: Thread? = null
            try {
                val pb = ProcessBuilder(args).redirectErrorStream(true)
                val symlinkDir = File(context.filesDir, "ffmpeg_libs").apply { mkdirs() }
                pb.environment()["LD_LIBRARY_PATH"] = "${symlinkDir.absolutePath}:${repo.getLibraryDir()}"
                pb.environment()["GGML_BACKEND_PATH"] = "/dev/null"
                pb.environment()["HOME"] = context.filesDir.absolutePath
                pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
                pb.directory(context.filesDir)
                process = pb.start()
                currentProcess = process
                readerThread = Thread {
                    runCatching {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                mediaTranslationLatestTranscriptTimestampMs(line)
                                    .takeIf { it > 0L }
                                    ?.let { latestOutputTimestampMs.updateAndGet { current -> max(current, it + timestampOffsetMs) } }
                                mediaTranslationWhisperOutputSegment(line)?.let { segment ->
                                    val timelineSegment = segment.copy(
                                        startMs = segment.startMs + timestampOffsetMs,
                                        endMs = segment.endMs + timestampOffsetMs
                                    )
                                    latestOutputTimestampMs.updateAndGet { current -> max(current, timelineSegment.endMs) }
                                    synchronized(partialSegmentsLock) {
                                        val isDuplicate = partialSegments.any {
                                            it.startMs == timelineSegment.startMs &&
                                                it.endMs == timelineSegment.endMs &&
                                                it.text == timelineSegment.text
                                        }
                                        if (!isDuplicate) {
                                            partialSegments += timelineSegment.copy(id = partialSegments.size + 1)
                                            srtFile.parentFile?.mkdirs()
                                            srtFile.writeText(SrtWriter.writeOriginal(mergeTranscriptSegments(partialSegments, emptyList())))
                                        }
                                    }
                                }
                                if (output.length < 24_000) {
                                    output.appendLine(line)
                                }
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
                while (process.isAlive) {
                    ensureActive()
                    updateWhisperFileProgress(context, srtFile, mediaDurationMs, baseProgress, progressSpan, latestOutputTimestampMs.get())
                    Thread.sleep(1_000L)
                }
                val exit = process.waitFor()
                readerThread.join(1_000L)
                currentProcess = null
                updateWhisperFileProgress(context, srtFile, mediaDurationMs, baseProgress, progressSpan, latestOutputTimestampMs.get())
                val outputText = output.toString()
                if (outputText.isNotBlank()) DebugLog.log("[MEDIA-TRANSLATE] ${outputText.lines().takeLast(10).joinToString("\n")}")
                ensureActive()
                if (exit == 0) return
                lastRetryOutput = outputText
                if (mediaTranslationWhisperLaunchNeedsMemoryRetry(outputText) && attempt < maxAttempts) {
                    retryWhisperAfterLinkerOutOfMemory(attempt, maxAttempts)
                    continue
                }
                if (mediaTranslationWhisperLaunchNeedsMemoryRetry(outputText)) {
                    throw WhisperLinkerOutOfMemoryException(
                        context.getString(
                            R.string.workflow_media_translate_whisper_linker_oom_failed,
                            maxAttempts,
                            mediaTranslationCompactProcessOutput(outputText)
                        )
                    )
                }
                throw IllegalStateException("Process failed with exit code $exit")
            } catch (e: Exception) {
                currentProcess = null
                val processOutput = output.toString()
                val retryable = mediaTranslationWhisperLaunchNeedsMemoryRetry(processOutput, e.message)
                if (retryable && attempt < maxAttempts) {
                    lastRetryOutput = processOutput.ifBlank { e.message.orEmpty() }
                    retryWhisperAfterLinkerOutOfMemory(attempt, maxAttempts)
                    continue
                }
                if (retryable) {
                    throw WhisperLinkerOutOfMemoryException(
                        context.getString(
                            R.string.workflow_media_translate_whisper_linker_oom_failed,
                            maxAttempts,
                            mediaTranslationCompactProcessOutput(lastRetryOutput.ifBlank { processOutput.ifBlank { e.message.orEmpty() } })
                        ),
                        e
                    )
                }
                throw e
            } finally {
                runCatching { readerThread?.join(250L) }
                currentProcess = null
            }
        }
    }

    private fun retryWhisperAfterLinkerOutOfMemory(attempt: Int, maxAttempts: Int) {
        val delayMs = mediaTranslationWhisperLinkerRetryDelayMs(attempt)
        DebugLog.log(
            "[MEDIA-TRANSLATE] Whisper could not start because Android reported native linker out-of-memory. " +
                "Retrying attempt ${attempt + 1}/$maxAttempts in ${delayMs}ms."
        )
        Runtime.getRuntime().gc()
        System.runFinalization()
        Thread.sleep(delayMs)
    }

    private fun updateWhisperFileProgress(
        context: Context,
        srtFile: File,
        mediaDurationMs: Long,
        baseProgress: Float,
        progressSpan: Float,
        latestOutputTimestampMs: Long = 0L
    ) {
        val latestMs = max(latestSrtEndTimestampMs(srtFile), latestOutputTimestampMs)
        val fraction = if (mediaDurationMs > 0L && latestMs > 0L) {
            (latestMs.toFloat() / mediaDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val percent = mediaTranslationProgressPercent(latestMs, mediaDurationMs)
        val detail = if (mediaDurationMs > 0L && latestMs > 0L) {
            context.getString(
                R.string.workflow_media_translate_transcribing_percent,
                percent,
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

    private fun updateTranslationProgress(
        context: Context,
        translatedCount: Int,
        totalCount: Int,
        currentChunk: Int,
        totalChunks: Int,
        baseProgress: Float,
        progressSpan: Float
    ) {
        val percent = mediaTranslationLineProgressPercent(translatedCount, totalCount)
        val fraction = if (totalCount > 0) (translatedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f) else 0f
        val progress = (baseProgress + progressSpan * fraction).coerceIn(0f, 1f)
        val detail = context.getString(
            R.string.workflow_media_translate_translation_percent,
            percent,
            translatedCount.coerceAtLeast(0),
            totalCount.coerceAtLeast(0)
        )
        MediaTranslationWorkflowStateHolder.update {
            it.copy(
                status = context.getString(R.string.workflow_media_translate_translating),
                progress = progress,
                currentChunk = currentChunk.coerceAtLeast(1),
                totalChunks = totalChunks.coerceAtLeast(1),
                toolProgressDetail = detail
            )
        }
        notificationTaskId?.let { UnifiedNotificationManager.updateProgress(it, progress, detail) }
    }

    private fun latestSrtEndTimestampMs(srtFile: File): Long {
        if (!srtFile.isFile) return 0L
        return runCatching {
            mediaTranslationLatestTranscriptTimestampMs(srtFile.readText())
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
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
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
                val target = createUniqueDocumentFile(dir, mimeType, file.name) ?: return@forEach
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
            }
        }.onFailure { DebugLog.log("[MEDIA-TRANSLATE] Output mirror failed: ${it.message}") }
    }

    private fun createUniqueDocumentFile(dir: DocumentFile, mimeType: String, fileName: String): DocumentFile? {
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        var candidate = fileName
        var suffix = 2
        while (dir.findFile(candidate) != null) {
            candidate = buildString {
                append(base)
                append('_')
                append(suffix++)
                extension?.let {
                    append('.')
                    append(it)
                }
            }
        }
        return dir.createFile(mimeType, candidate)
    }

    private fun isVideoSpec(spec: MediaTranslationJobSpec): Boolean =
        spec.sourceMimeType?.startsWith("video/") == true ||
            spec.sourcePath.substringAfterLast('.', "").lowercase(Locale.US) in setOf("mp4", "mkv", "mov", "webm", "avi")

    private fun shouldDubVideo(spec: MediaTranslationJobSpec, isVideo: Boolean): Boolean =
        isVideo && spec.outputMode != MediaTranslationOutputMode.AUDIO_ONLY

    private fun safeBaseName(name: String): String =
        mediaTranslationSafeOutputBaseName(name)

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
        currentKind = null
        notificationTaskId = null
        WakeLockManager.release("MediaTranslationWorkflowService")
        WakeLockManager.releaseWifiLock("MediaTranslationWorkflowService")
        RemoteSummaryProtection.release()
    }

    private data class MediaTranslationOutput(
        val originalSrt: File,
        val translatedSrt: File,
        val translatedAudio: File,
        val finalOutput: File,
        val skippedTranslationLineCount: Int
    )

    private data class SubtitleTranslationOutput(
        val originalSrt: File,
        val translatedSrt: File,
        val finalOutput: File,
        val skippedTranslationLineCount: Int
    )
}

internal fun mediaTranslationProgressPercent(currentMs: Long, totalMs: Long): Int {
    if (currentMs <= 0L || totalMs <= 0L) return 0
    return ((currentMs.toDouble() / totalMs.toDouble()).coerceIn(0.0, 1.0) * 100.0).roundToInt()
}

internal fun mediaTranslationLineProgressPercent(translatedCount: Int, totalCount: Int): Int {
    if (translatedCount <= 0 || totalCount <= 0) return 0
    return ((translatedCount.toDouble() / totalCount.toDouble()).coerceIn(0.0, 1.0) * 100.0).roundToInt()
}

internal fun mediaTranslationWhisperLaunchNeedsMemoryRetry(vararg details: String?): Boolean =
    details
        .filterNotNull()
        .map { it.lowercase(Locale.US) }
        .any { detail ->
            "cannot link executable" in detail &&
                "relro" in detail &&
                "out of memory" in detail
        }

internal fun mediaTranslationWhisperLinkerRetryDelayMs(attempt: Int): Long =
    (1_500L * attempt.coerceAtLeast(1)).coerceAtMost(8_000L)

internal fun mediaTranslationCompactProcessOutput(output: String, maxChars: Int = 900): String {
    val compact = output
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .takeLast(8)
        .joinToString(" ")
        .ifBlank { "<empty>" }
    return if (compact.length <= maxChars) {
        compact
    } else {
        compact.take((maxChars - 3).coerceAtLeast(0)) + "..."
    }
}

internal fun mediaTranslationTranslatedSubtitleFileName(sourceName: String, timestamp: Long): String =
    "translated_${mediaTranslationSafeOutputBaseName(sourceName)}_$timestamp.srt"

internal fun mediaTranslationOriginalSubtitleFileName(sourceName: String, timestamp: Long): String =
    "original_${mediaTranslationSafeOutputBaseName(sourceName)}_$timestamp.srt"

internal fun mediaTranslationSafeOutputBaseName(name: String): String =
    name.substringBeforeLast('.')
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "media" }

internal fun mediaTranslationResumeStartMs(latestCheckpointMs: Long, backupMs: Long = 60_000L): Long =
    (latestCheckpointMs - backupMs).coerceAtLeast(0L)

internal fun mediaTranslationTrimSegmentsForResume(
    segments: List<TimedTranscriptSegment>,
    resumeStartMs: Long
): List<TimedTranscriptSegment> =
    segments
        .filter { it.endMs <= resumeStartMs }
        .mapIndexed { index, segment -> segment.copy(id = index + 1) }

internal fun mediaTranslationMergeTranscriptSegments(
    existingSegments: List<TimedTranscriptSegment>,
    newSegments: List<TimedTranscriptSegment>
): List<TimedTranscriptSegment> {
    val merged = (existingSegments + newSegments)
        .distinctBy { segment -> "${segment.startMs}:${segment.endMs}:${segment.text}" }
        .sortedWith(compareBy<TimedTranscriptSegment> { it.startMs }.thenBy { it.endMs })
    return merged.mapIndexed { index, segment -> segment.copy(id = index + 1) }
}

internal fun mediaTranslationLatestTranscriptTimestampMs(raw: String): Long {
    val regex = Regex("""-->\s*(\d{2}):(\d{2}):(\d{2})[\.,](\d{3})""")
    return regex.findAll(raw)
        .map { match ->
            val (hours, minutes, seconds, millis) = match.destructured
            ((hours.toLong() * 60L + minutes.toLong()) * 60L + seconds.toLong()) * 1000L + millis.toLong()
        }
        .maxOrNull() ?: 0L
}

internal fun mediaTranslationWhisperOutputSegment(line: String): TimedTranscriptSegment? {
    val regex = Regex(
        """^\s*\[(\d{2}):(\d{2}):(\d{2})[\.,](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[\.,](\d{3})]\s*(.+?)\s*$"""
    )
    val match = regex.find(line) ?: return null
    val text = match.groupValues[9].trim()
    if (text.isBlank()) return null
    return TimedTranscriptSegment(
        id = 0,
        startMs = mediaTranslationTimestampFromMatch(match.groupValues, 1),
        endMs = mediaTranslationTimestampFromMatch(match.groupValues, 5),
        text = text
    )
}

private fun mediaTranslationTimestampFromMatch(values: List<String>, offset: Int): Long {
    val hours = values[offset].toLong()
    val minutes = values[offset + 1].toLong()
    val seconds = values[offset + 2].toLong()
    val millis = values[offset + 3].toLong()
    return ((hours * 60L + minutes) * 60L + seconds) * 1000L + millis
}

internal fun mediaTranslationSanitizeCheckpointTranslations(
    translations: Map<Int, String>,
    expectedSegments: List<TimedTranscriptSegment>
): Map<Int, String> {
    val sanitized = linkedMapOf<Int, String>()
    expectedSegments.forEach { segment ->
        translations[segment.id]?.trim()?.takeIf { it.isNotBlank() }?.let { text ->
            sanitized[segment.id] = text
        }
    }
    return sanitized
}
