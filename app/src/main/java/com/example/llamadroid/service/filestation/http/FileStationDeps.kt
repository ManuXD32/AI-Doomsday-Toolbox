package com.example.llamadroid.service.filestation.http

import com.example.llamadroid.service.filestation.volume.FileStationVolume
import java.io.File
import java.io.InputStream

/** Everything the HTTP server needs; no Android Context so it runs in JVM tests. */
class FileStationDeps(
    val volumes: VolumeRegistryApi,
    val auth: AuthConfigProvider,
    val assets: AssetSource,
    val jobs: JobRunner,
    val tools: ToolBackends = ToolBackends(),
    val clock: Clock = Clock { System.currentTimeMillis() },
    val logger: FsLogger = FsLogger { _, _, _ -> },
    /** App versionCode; appended as `?v=` to static asset URLs for cache busting. */
    val appVersionCode: Long = 0,
    /** Scratch directory for upload temp files, staging and caches (wiped by the caller as needed). */
    val cacheDir: File,
)

fun interface Clock { fun nowMillis(): Long }

fun interface FsLogger { fun log(level: String, tag: String, message: String) }

/** Volume lookup. Implementations may swap the underlying set live. */
interface VolumeRegistryApi {
    fun list(): List<FileStationVolume>
    /** null when unknown id. */
    fun get(id: String): FileStationVolume?
    /** Throws FsException NOT_FOUND (unknown) or READ_ONLY. */
    fun requireWritable(id: String): FileStationVolume
    /** Retention in days for trash purge (0 = keep until emptied). */
    val trashRetentionDays: Int
    val showHidden: Boolean
}

/** Auth/network config, re-read per request so password changes apply live. */
interface AuthConfigProvider {
    /** null = open mode (no password). */
    fun credentials(): PasswordCredentials?
    /** Per-install HMAC secret for session tokens; rotating it signs everyone out. */
    fun sessionSecret(): ByteArray
    /** True when bound on all interfaces (drives open-mode Host check and warnings). */
    val lanVisible: Boolean
}

/** PBKDF2 material as produced by AiServerAuth; hash/salt are Base64 or hex as the store defines. */
class PasswordCredentials(val salt: String, val hash: String)

/** Static web UI source: AssetManager in the app, a directory in tests. Paths are relative ("index.html"). */
interface AssetSource {
    /** null when missing. Caller closes. */
    fun open(path: String): InputStream?
    /** Length in bytes or -1 if unknown. */
    fun length(path: String): Long
}

enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** Snapshot of a job. [progress] is 0..1 (-1 = indeterminate). [outputs] are "<vol>/<path>" strings. */
data class JobInfo(
    val id: String,
    val kind: String,
    val title: String,
    val state: JobState,
    val progress: Float,
    val message: String?,
    val outputs: List<JobOutput>,
    val errorCode: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    /** Optional tool result as a raw JSON object string (duplicates, usage, sha256). */
    val resultJson: String? = null,
)

data class JobOutput(val vol: String, val path: String)

/** Handed to a running job body. Poll [isCancelled] and call [progress]. */
interface JobContext {
    val isCancelled: Boolean
    fun progress(fraction: Float, message: String? = null)
    fun addOutput(vol: String, path: String)
    fun setResult(json: String)
    /** Throws CancellationException-like FsException(IO) if cancelled. */
    fun checkCancelled()
}

interface JobRunner {
    /** Queues [body]; returns the new job id immediately. */
    fun submit(kind: String, title: String, body: (JobContext) -> Unit): String
    fun cancel(id: String): Boolean
    fun list(): List<JobInfo>
    fun get(id: String): JobInfo?
}

/** Tool backends. A null backend means the feature is unavailable and its endpoints answer 501. */
class ToolBackends(
    val image: ImageBackend? = null,
    val pdf: PdfBackend? = null,
    val doc: DocBackend? = null,
    val media: MediaBackend? = null,
    val qr: QrBackend? = null,
)

/** Backends read inputs and write outputs through plain files in a staging dir (job runner stages volume files). */
interface ImageBackend {
    /** format: jpeg|png|webp. Returns false/throws FsException(UNSUPPORTED) for unreadable input. */
    fun convert(input: File, output: File, format: String, quality: Int, maxWidth: Int?, maxHeight: Int?)
    /** Cached thumbnail (JPEG) with longest side [size]; null if not an image. */
    fun thumbnail(input: File, size: Int): ByteArray?
}

interface PdfBackend {
    fun merge(inputs: List<File>, output: File, ctx: JobContext)
    /** ranges like "1-3,5"; one output per range into [outDir], returns created files. */
    fun split(input: File, ranges: String, outDir: File, ctx: JobContext): List<File>
    fun imagesToPdf(images: List<File>, output: File, ctx: JobContext)
    /** format png|jpeg; returns created files. */
    fun pdfToImages(input: File, outDir: File, format: String, dpi: Int, ctx: JobContext): List<File>
    fun pdfToText(input: File, output: File, ctx: JobContext)
}

interface DocBackend {
    /** kind: md|html|txt → PDF. */
    fun textToPdf(input: File, kind: String, output: File, ctx: JobContext)
    /** docx|odt → "txt" or "html". */
    fun officeToText(input: File, outputFormat: String, output: File)
    fun csvToJson(input: File, output: File)
    fun jsonToCsv(input: File, output: File)
}

interface MediaBackend {
    /** Presets listed by docs/file-station.md. Runs ffmpeg; reports progress via [ctx]. */
    fun convert(input: File, output: File, preset: String, ctx: JobContext)
    fun trim(input: File, output: File, startSec: Double, endSec: Double, ctx: JobContext)
    val available: Boolean
}

interface QrBackend {
    /** PNG bytes for [text]. */
    fun png(text: String, size: Int): ByteArray
}
