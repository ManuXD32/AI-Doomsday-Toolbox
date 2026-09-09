package com.example.llamadroid.service

import android.content.Context
import android.os.Build
import android.os.Process
import com.example.llamadroid.data.binary.BinaryRepository
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.Locale
import java.util.UUID

/**
 * Limits shared by native MTMD video input and local frame extraction.
 *
 * The native decoder can accept a whole video URL, but an unbounded video lets a
 * single request allocate an unbounded number of decoded images.  Keep these
 * values in one small policy object so local and remote paths apply the same
 * limits.
 */
data class NativeLlamaVideoPolicy(
    val maxSegmentDurationMs: Long = MAX_SEGMENT_DURATION_MS,
    val maxFramesPerSegment: Int = MAX_FRAMES_PER_SEGMENT,
    val maxFps: Float = MAX_VIDEO_FPS,
    val defaultFps: Float = DEFAULT_VIDEO_FPS
) {
    init {
        require(maxSegmentDurationMs in 1L..MAX_SEGMENT_DURATION_MS) {
            "Video segment duration must be between 1ms and 30s"
        }
        require(maxFramesPerSegment in 1..MAX_FRAMES_PER_SEGMENT) {
            "Video frame limit must be between 1 and 24"
        }
        require(maxFps.isFinite() && maxFps > 0f && maxFps <= MAX_VIDEO_FPS) {
            "Video fps must be finite and no greater than 2"
        }
        require(defaultFps.isFinite() && defaultFps > 0f && defaultFps <= maxFps) {
            "Default video fps must be finite and no greater than the policy cap"
        }
    }

    fun normalizedFps(value: Float): Float =
        if (!value.isFinite()) defaultFps else value.coerceIn(0.1f, maxFps)

    fun normalizedTimestampIntervalMs(value: Int): Int =
        value.coerceIn(MIN_TIMESTAMP_INTERVAL_MS, MAX_TIMESTAMP_INTERVAL_MS)

    companion object {
        const val MAX_SEGMENT_DURATION_MS: Long = 30_000L
        const val MAX_FRAMES_PER_SEGMENT: Int = 24
        const val MAX_VIDEO_SEGMENTS_PER_RUN: Int = 120
        const val MAX_VIDEO_FPS: Float = 2.0f
        const val DEFAULT_VIDEO_FPS: Float = 2.0f
        const val DEFAULT_TIMESTAMP_INTERVAL_MS: Int = 5_000
        const val MIN_TIMESTAMP_INTERVAL_MS: Int = 250
        const val MAX_TIMESTAMP_INTERVAL_MS: Int = 60_000
    }
}

const val MAX_SEGMENT_DURATION_MS: Long = NativeLlamaVideoPolicy.MAX_SEGMENT_DURATION_MS
const val MAX_FRAMES_PER_SEGMENT: Int = NativeLlamaVideoPolicy.MAX_FRAMES_PER_SEGMENT
const val MAX_VIDEO_SEGMENTS_PER_RUN: Int = NativeLlamaVideoPolicy.MAX_VIDEO_SEGMENTS_PER_RUN
const val MAX_VIDEO_FPS: Float = NativeLlamaVideoPolicy.MAX_VIDEO_FPS
const val DEFAULT_VIDEO_FPS: Float = NativeLlamaVideoPolicy.DEFAULT_VIDEO_FPS
const val DEFAULT_TIMESTAMP_INTERVAL_MS: Int = NativeLlamaVideoPolicy.DEFAULT_TIMESTAMP_INTERVAL_MS
const val MAX_VIDEO_FRAME_DIMENSION: Int = 768

/** Session-private paths used by llama-server's MTMD video decoder. */
data class LlamaVideoRuntimeDirectories(
    val root: File,
    val mediaDirectory: File,
    val ffmpegDirectory: File,
    val ffmpeg: File,
    val ffprobe: File
) {
    val mediaPath: String get() = mediaDirectory.absolutePath
    val ffmpegDirPath: String get() = ffmpegDirectory.absolutePath

    /** The caller owns this session directory and may remove it after the child exits. */
    fun cleanup() {
        root.deleteRecursively()
    }
}

/** Cross-process descriptor for the server-private media root. */
data class LlamaVideoRuntimeDescriptor(
    val rootPath: String,
    val mediaPath: String,
    val ffmpegDirPath: String,
    val ffmpegPath: String,
    val ffprobePath: String,
    val ownerPid: Int,
    val serverPort: Int,
    val createdAtMs: Long
)

/**
 * Native llama.cpp video launch and path helpers. Every launch receives a
 * fresh private directory resolved from the current Android context. The
 * active reference below is only a short-lived request-discovery handle; the
 * durable cross-process descriptor is validated before it is used.
 */
object NativeLlamaVideoSupport {
    const val DEFAULT_VIDEO_FPS: Float = NativeLlamaVideoPolicy.DEFAULT_VIDEO_FPS
    const val DEFAULT_TIMESTAMP_INTERVAL_MS: Int = NativeLlamaVideoPolicy.DEFAULT_TIMESTAMP_INTERVAL_MS
    const val MAX_VIDEO_FPS: Float = NativeLlamaVideoPolicy.MAX_VIDEO_FPS
    const val MAX_SEGMENT_DURATION_MS: Long = NativeLlamaVideoPolicy.MAX_SEGMENT_DURATION_MS
    const val MAX_FRAMES_PER_SEGMENT: Int = NativeLlamaVideoPolicy.MAX_FRAMES_PER_SEGMENT
    const val MAX_VIDEO_FRAME_DIMENSION: Int = com.example.llamadroid.service.MAX_VIDEO_FRAME_DIMENSION

    private const val RUNTIME_ROOT = "llama_video_runtime"
    private const val ACTIVE_RUNTIME_DESCRIPTOR = "active_runtime.properties"

    @Volatile
    private var activeRuntime: LlamaVideoRuntimeDirectories? = null

    fun normalizeFps(value: Float): Float =
        NativeLlamaVideoPolicy().normalizedFps(value)

    fun normalizeTimestampIntervalMs(value: Int): Int =
        NativeLlamaVideoPolicy().normalizedTimestampIntervalMs(value)

    fun formatFps(value: Float): String =
        String.format(Locale.US, "%.3f", normalizeFps(value))
            .trimEnd('0')
            .trimEnd('.')
            .ifBlank { "1" }

    /**
     * Build only the native video flags.  Runtime directories are optional here
     * so command previews can still show the typed fps/timestamp policy before a
     * process has been allocated.
     */
    fun commandArgs(config: LlamaConfig): List<String> {
        if (!config.videoEnabled) return emptyList()
        return buildList {
            add("--video-fps")
            add(formatFps(config.videoFps))
            add("--video-timestamp-interval")
            add(normalizeTimestampIntervalMs(config.videoTimestampIntervalMs).toString())
            config.videoFfmpegDir
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    add("--video-ffmpeg-dir")
                    add(it)
                }
            config.mediaPath
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    add("--media-path")
                    add(it)
                }
        }
    }

    /**
     * Publish the currently running server's private media root to request
     * adapters. The reference is process-local and is cleared before the root
     * is removed, so a persisted chat attachment can never be mistaken for a
     * durable runtime file.
     */
    @Synchronized
    fun registerActiveRuntime(runtime: LlamaVideoRuntimeDirectories) {
        activeRuntime = runtime
    }

    /** Return the live server runtime, if its private directories still exist. */
    @Synchronized
    fun activeRuntimeDirectories(): LlamaVideoRuntimeDirectories? {
        val runtime = activeRuntime ?: return null
        return runtime.takeIf {
            it.root.isDirectory && it.mediaDirectory.isDirectory && it.ffmpegDirectory.isDirectory
        }
    }

    /** Convenience for chat/request code which only needs to stage a local file. */
    fun activeMediaDirectory(): File? = activeRuntimeDirectories()?.mediaDirectory

    /**
     * Cross-process form used by the main chat process. LlamaService runs in
     * `:llama_runtime`, so its in-memory reference is not visible here; the
     * descriptor is accepted only while that owner process is still alive and
     * all paths remain under this app's private runtime root.
     */
    @Synchronized
    fun activeRuntimeDirectories(context: Context): LlamaVideoRuntimeDirectories? {
        return activeRuntimeDirectoriesFromDescriptor(context, expectedServerPort = null)
    }

    /** Resolve the runtime only when it belongs to the requested local server port. */
    @Synchronized
    fun activeRuntimeDirectories(
        context: Context,
        serverPort: Int
    ): LlamaVideoRuntimeDirectories? {
        require(serverPort in 1..65_535) { "Server port must be valid" }
        return activeRuntimeDirectoriesFromDescriptor(context, expectedServerPort = serverPort)
    }

    private fun activeRuntimeDirectoriesFromDescriptor(
        context: Context,
        expectedServerPort: Int?
    ): LlamaVideoRuntimeDirectories? {
        activeRuntimeDirectories()?.let { runtime ->
            if (expectedServerPort == null) return runtime
            val localDescriptor = readRuntimeDescriptor(context)
            if (localDescriptor?.serverPort == expectedServerPort &&
                localDescriptor.rootPath == runtime.root.absolutePath
            ) return runtime
            return null
        }
        val descriptor = readRuntimeDescriptor(context) ?: return null
        if (expectedServerPort != null && descriptor.serverPort != expectedServerPort) return null
        if (!isRuntimeOwnerAlive(context, descriptor.ownerPid)) {
            clearRuntimeDescriptor(context, descriptor.rootPath)
            return null
        }
        return descriptor.toRuntimeDirectories(context)
            ?: run {
                clearRuntimeDescriptor(context, descriptor.rootPath)
                null
            }
    }

    fun activeMediaDirectory(context: Context): File? =
        activeRuntimeDirectories(context)?.mediaDirectory

    fun activeMediaDirectory(context: Context, serverPort: Int): File? =
        activeRuntimeDirectories(context, serverPort)?.mediaDirectory

    /** Persist the live root so the main process can stage a request attachment. */
    @Synchronized
    fun registerActiveRuntime(
        context: Context,
        runtime: LlamaVideoRuntimeDirectories,
        serverPort: Int
    ) {
        activeRuntime = runtime
        writeRuntimeDescriptor(
            context = context,
            descriptor = LlamaVideoRuntimeDescriptor(
                rootPath = runtime.root.absolutePath,
                mediaPath = runtime.mediaDirectory.absolutePath,
                ffmpegDirPath = runtime.ffmpegDirectory.absolutePath,
                ffmpegPath = runtime.ffmpeg.absolutePath,
                ffprobePath = runtime.ffprobe.absolutePath,
                ownerPid = Process.myPid(),
                serverPort = serverPort,
                createdAtMs = System.currentTimeMillis()
            )
        )
    }

    @Synchronized
    fun clearActiveRuntime(runtime: LlamaVideoRuntimeDirectories) {
        if (activeRuntime === runtime) activeRuntime = null
    }

    @Synchronized
    fun clearActiveRuntime(context: Context, runtime: LlamaVideoRuntimeDirectories) {
        if (activeRuntime === runtime) activeRuntime = null
        val descriptor = readRuntimeDescriptor(context)
        descriptor?.let {
            if (it.rootPath == runtime.root.absolutePath) {
                clearRuntimeDescriptor(context, it.rootPath)
            }
        }
    }

    /**
     * Resolve the packaged tiered ffmpeg payloads and expose the names expected
     * by MTMD through private symlinks.  The targets remain in the installed
     * native feature directory; no executable is copied into app data.
     */
    fun createRuntimeDirectories(
        context: Context,
        binaryRepository: BinaryRepository,
        sessionId: String = "session_${System.currentTimeMillis()}_${UUID.randomUUID()}"
    ): LlamaVideoRuntimeDirectories {
        val ffmpegTarget = binaryRepository.getFFmpegBinary()
            ?: error("Packaged ffmpeg binary is unavailable")
        val ffprobeTarget = binaryRepository.getFFprobeBinary()
            ?: error("Packaged ffprobe binary is unavailable")
        require(ffmpegTarget.isFile) { "Packaged ffmpeg binary is not a file" }
        require(ffprobeTarget.isFile) { "Packaged ffprobe binary is not a file" }

        val safeSession = sanitizeSessionId(sessionId)
        val root = File(context.filesDir, "$RUNTIME_ROOT/$safeSession")
        val mediaDirectory = File(root, "media")
        val ffmpegDirectory = File(root, "ffmpeg")
        try {
            require(root.mkdirs() || root.isDirectory) { "Unable to create video runtime directory" }
            require(mediaDirectory.mkdirs() || mediaDirectory.isDirectory) {
                "Unable to create private video media directory"
            }
            require(ffmpegDirectory.mkdirs() || ffmpegDirectory.isDirectory) {
                "Unable to create private video ffmpeg directory"
            }
            val ffmpeg = File(ffmpegDirectory, "ffmpeg")
            val ffprobe = File(ffmpegDirectory, "ffprobe")
            createPrivateExecutableSymlink(ffmpegTarget, ffmpeg)
            createPrivateExecutableSymlink(ffprobeTarget, ffprobe)
            return LlamaVideoRuntimeDirectories(
                root = root,
                mediaDirectory = mediaDirectory,
                ffmpegDirectory = ffmpegDirectory,
                ffmpeg = ffmpeg,
                ffprobe = ffprobe
            )
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    /** Exposed for focused tests and for other media runtimes that need the same rule. */
    internal fun createPrivateExecutableSymlink(target: File, link: File) {
        require(target.isFile) { "Symlink target is missing: ${target.absolutePath}" }
        require(target.absolutePath != link.absolutePath) { "Symlink target and link must differ" }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "Private executable symlinks require Android O or newer"
        }
        link.parentFile?.mkdirs()
        Files.deleteIfExists(link.toPath())
        Files.createSymbolicLink(link.toPath(), target.toPath())
        check(Files.isSymbolicLink(link.toPath())) { "FFmpeg runtime link was not created" }
    }

    /**
     * Convert a staged basename into the relative URL accepted by llama.cpp.
     * Path separators and traversal are rejected so the native media root stays
     * a strict private boundary.
     */
    fun relativeFileUrl(fileName: String): String {
        val safe = sanitizeMediaFileName(fileName)
        return "file://$safe"
    }

    fun sanitizeMediaFileName(fileName: String): String {
        val candidate = fileName.trim()
        require(candidate.isNotBlank()) { "Media filename must not be blank" }
        require(candidate != "." && candidate != "..") { "Media filename is invalid" }
        require('/' !in candidate && '\\' !in candidate) { "Media filename must be relative" }
        require(!candidate.contains("..")) { "Media filename may not contain traversal" }
        return candidate.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)
            .ifBlank { "media.bin" }
    }

    private fun sanitizeSessionId(value: String): String =
        value.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
            .ifBlank { "session_${System.currentTimeMillis()}" }

    private fun writeRuntimeDescriptor(
        context: Context,
        descriptor: LlamaVideoRuntimeDescriptor
    ) {
        val target = File(context.filesDir, ACTIVE_RUNTIME_DESCRIPTOR)
        val temporary = File(context.filesDir, "$ACTIVE_RUNTIME_DESCRIPTOR.tmp")
        val values = Properties().apply {
            setProperty("rootPath", descriptor.rootPath)
            setProperty("mediaPath", descriptor.mediaPath)
            setProperty("ffmpegDirPath", descriptor.ffmpegDirPath)
            setProperty("ffmpegPath", descriptor.ffmpegPath)
            setProperty("ffprobePath", descriptor.ffprobePath)
            setProperty("ownerPid", descriptor.ownerPid.toString())
            setProperty("serverPort", descriptor.serverPort.toString())
            setProperty("createdAtMs", descriptor.createdAtMs.toString())
        }
        try {
            FileOutputStream(temporary).use { values.store(it, "llama video runtime") }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse {
                if (!temporary.renameTo(target)) {
                    error("Unable to publish active video runtime descriptor")
                }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun readRuntimeDescriptor(context: Context): LlamaVideoRuntimeDescriptor? {
        val file = File(context.filesDir, ACTIVE_RUNTIME_DESCRIPTOR)
        if (!file.isFile) return null
        val values = Properties()
        return try {
            FileInputStream(file).use(values::load)
            LlamaVideoRuntimeDescriptor(
                rootPath = values.getProperty("rootPath") ?: return null,
                mediaPath = values.getProperty("mediaPath") ?: return null,
                ffmpegDirPath = values.getProperty("ffmpegDirPath") ?: return null,
                ffmpegPath = values.getProperty("ffmpegPath") ?: return null,
                ffprobePath = values.getProperty("ffprobePath") ?: return null,
                ownerPid = values.getProperty("ownerPid")?.toIntOrNull() ?: return null,
                serverPort = values.getProperty("serverPort")?.toIntOrNull() ?: return null,
                createdAtMs = values.getProperty("createdAtMs")?.toLongOrNull() ?: return null
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun clearRuntimeDescriptor(context: Context, rootPath: String?) {
        val file = File(context.filesDir, ACTIVE_RUNTIME_DESCRIPTOR)
        val descriptor = readRuntimeDescriptor(context)
        if (rootPath == null || descriptor?.rootPath == rootPath) {
            file.delete()
            val runtimeRoot = File(context.filesDir, RUNTIME_ROOT).absoluteFile
            val staleRoot = rootPath?.let { File(it) }?.absoluteFile
            if (staleRoot != null && isDescendant(staleRoot, runtimeRoot)) {
                staleRoot.deleteRecursively()
            }
        }
    }

    private fun LlamaVideoRuntimeDescriptor.toRuntimeDirectories(
        context: Context
    ): LlamaVideoRuntimeDirectories? {
        val runtimeRoot = File(context.filesDir, RUNTIME_ROOT).absoluteFile
        val root = File(rootPath).absoluteFile
        val media = File(mediaPath).absoluteFile
        val ffmpegDirectory = File(ffmpegDirPath).absoluteFile
        val ffmpeg = File(ffmpegPath).absoluteFile
        val ffprobe = File(ffprobePath).absoluteFile
        if (!isDescendant(root, runtimeRoot) ||
            !isDescendant(media, root) ||
            !isDescendant(ffmpegDirectory, root) ||
            !isDescendant(ffmpeg, ffmpegDirectory) ||
            !isDescendant(ffprobe, ffmpegDirectory) ||
            !root.isDirectory ||
            !media.isDirectory ||
            !ffmpegDirectory.isDirectory ||
            !Files.isSymbolicLink(ffmpeg.toPath()) ||
            !Files.isSymbolicLink(ffprobe.toPath()) ||
            !ffmpeg.isFile ||
            !ffprobe.isFile
        ) return null
        return LlamaVideoRuntimeDirectories(root, media, ffmpegDirectory, ffmpeg, ffprobe)
    }

    private fun isDescendant(child: File, parent: File): Boolean {
        val parentPath = parent.absolutePath.trimEnd(File.separatorChar) + File.separator
        return child.absolutePath.startsWith(parentPath)
    }

    private fun isRuntimeOwnerAlive(context: Context, ownerPid: Int): Boolean {
        if (ownerPid <= 0) return false
        val cmdline = File("/proc/$ownerPid/cmdline")
        val expected = "${context.packageName}:llama_runtime"
        return runCatching {
            cmdline.readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
                .contains(expected)
        }.getOrDefault(false)
    }
}
