package com.example.llamadroid.media

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.example.llamadroid.onnx.OnnxBackgroundRemovalMetadata
import com.example.llamadroid.onnx.OnnxBackgroundRemovalStorage
import com.example.llamadroid.onnx.OnnxGeneratedImageMetadata
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.service.GeneratedVideoMetadata
import com.example.llamadroid.service.SdGeneratedImageMetadata
import java.io.File
import java.util.Locale
import org.json.JSONObject

/** The media kind rendered by a saved-output gallery. */
enum class MediaGalleryType {
    IMAGE,
    VIDEO
}

/** The producer that owns a saved output. */
enum class MediaGallerySource {
    SD,
    FAST_SD,
    ONNX,
    TAMA,
    WORKFLOW,
    IMAGE_PROCESSING,
    VIDEO_PROCESSING,
    UNKNOWN
}

/** Whether an item has a trustworthy local/distributed provenance marker. */
enum class MediaGalleryLocation {
    LOCAL,
    DISTRIBUTED,
    UNKNOWN
}

/** Stable identity used by gallery keys, selection state, and deduplication. */
@JvmInline
value class MediaGalleryIdentity(val value: String)

enum class MediaGalleryTypeFilter {
    ALL,
    IMAGES,
    VIDEOS
}

enum class MediaGallerySourceFilter {
    ALL,
    SD,
    FAST_SD,
    ONNX,
    TAMA,
    WORKFLOW,
    PROCESSING
}

enum class MediaGalleryLocationFilter {
    ALL,
    LOCAL,
    DISTRIBUTED,
    UNKNOWN
}

/**
 * A remote FastSD result can be supplied by the existing SSH gallery without making the index
 * depend on an SSH connection. [path] is the remote path; [previewFile] may point at a cached
 * thumbnail or downloaded copy when one is available.
 */
data class MediaGalleryRemoteItem(
    val identity: MediaGalleryIdentity,
    val name: String,
    val path: String,
    val type: MediaGalleryType = MediaGalleryType.IMAGE,
    val createdAt: Long,
    val mimeType: String = "image/*",
    val location: MediaGalleryLocation = MediaGalleryLocation.UNKNOWN,
    val previewFile: File? = null
)

/** Fixed remote FastSD output location used by the existing SSH gallery. */
const val FAST_SD_RESULTS_PATH = "/root/fastsdcpu/results"

/**
 * A bounded, read-only listing command. The NUL delimiter keeps filenames containing spaces or
 * newlines intact; the gallery never downloads image bytes while building the shared index.
 */
const val FAST_SD_LIST_COMMAND =
    "find '$FAST_SD_RESULTS_PATH' -mindepth 1 -maxdepth 1 -type f " +
        "\\( -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.webp' \\) " +
        "-printf '%T@ %p\\0'"

data class MediaGalleryItem(
    val identity: MediaGalleryIdentity,
    val file: File,
    val type: MediaGalleryType,
    val source: MediaGallerySource,
    val location: MediaGalleryLocation,
    val mimeType: String,
    val createdAt: Long,
    val title: String = "",
    val prompt: String = "",
    val mode: String = "",
    val imageMetadata: SdGeneratedImageMetadata? = null,
    val onnxImageMetadata: OnnxGeneratedImageMetadata? = null,
    val backgroundRemovalMetadata: OnnxBackgroundRemovalMetadata? = null,
    val videoMetadata: GeneratedVideoMetadata? = null,
    val previewFile: File? = file,
    val remotePath: String? = null
) {
    /** Compatibility aliases make the model convenient for older gallery card code. */
    val id: String get() = identity.value
    val kind: MediaGalleryType get() = type
    val origin: MediaGalleryLocation get() = location
}

/** Explicit allowlisted roots for saved user-facing media. */
data class MediaGalleryRoots(
    val sdRoot: File,
    val videoRoot: File,
    val onnxImageRoot: File,
    val tamaRoot: File,
    val workflowRoots: List<File>,
    val backgroundRemovalRoot: File,
    val videoUpscaleRoot: File,
    val videoInterpolationRoot: File,
    val subtitleOutputRoot: File,
    val fastSdRoot: File? = null
) {
    companion object {
        fun fromFilesDir(filesDir: File): MediaGalleryRoots = MediaGalleryRoots(
            sdRoot = File(filesDir, "sd_output"),
            videoRoot = File(filesDir, "video_gen_output"),
            onnxImageRoot = File(filesDir, "onnx_image_output"),
            tamaRoot = File(filesDir, "tama_gallery"),
            workflowRoots = listOf(
                File(filesDir, "workflow_media_translation"),
                File(filesDir, "workflow_subtitle_translation")
            ),
            backgroundRemovalRoot = File(filesDir, "bgr_output"),
            videoUpscaleRoot = File(filesDir, "video_upscale_output"),
            videoInterpolationRoot = File(filesDir, "video_interpolation_output"),
            subtitleOutputRoot = File(filesDir, "subtitle_outputs")
        )
    }
}

/**
 * Shared saved-media index for Library and feature galleries.
 *
 * Scanning is deliberately IO-only and allowlisted. It does not inspect the device media store,
 * bundled assets, caches, workflow inputs, model directories, or runtime checkpoints.
 */
object MediaGalleryIndex {
    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp")
    private val fastSdImageExtensions = setOf("png", "jpg", "jpeg", "webp")
    private val videoExtensions = setOf("mp4", "avi", "webm", "mkv", "mov")
    private val videoArtifactExtensions = videoExtensions + "webp"
    private val workflowMediaExtensions = imageExtensions + videoExtensions

    fun scan(
        context: Context,
        remoteFastSdItems: List<MediaGalleryRemoteItem> = emptyList()
    ): List<MediaGalleryItem> = scan(MediaGalleryRoots.fromFilesDir(context.filesDir), remoteFastSdItems)

    fun scan(
        filesDir: File,
        remoteFastSdItems: List<MediaGalleryRemoteItem> = emptyList()
    ): List<MediaGalleryItem> = scan(MediaGalleryRoots.fromFilesDir(filesDir), remoteFastSdItems)

    fun scan(
        roots: MediaGalleryRoots,
        remoteFastSdItems: List<MediaGalleryRemoteItem> = emptyList()
    ): List<MediaGalleryItem> {
        val result = LinkedHashMap<String, MediaGalleryItem>()

        fun add(item: MediaGalleryItem) {
            if (item.file.isFile || item.remotePath != null) {
                result.putIfAbsent(item.identity.value, item)
            }
        }

        scanSdImages(roots.sdRoot, ::add)
        scanSdWorkflowImages(roots.sdRoot, ::add)
        scanGeneratedVideos(roots.videoRoot, ::add)
        scanOnnxImages(roots.onnxImageRoot, ::add)
        scanTamaImages(roots.tamaRoot, ::add)
        scanWorkflowMedia(roots.workflowRoots, ::add)
        scanBackgroundRemoval(roots.backgroundRemovalRoot, ::add)
        scanVideoRoot(roots.videoUpscaleRoot, MediaGallerySource.VIDEO_PROCESSING, ::add)
        scanVideoRoot(roots.videoInterpolationRoot, MediaGallerySource.VIDEO_PROCESSING, ::add)
        scanVideoRoot(roots.subtitleOutputRoot, MediaGallerySource.VIDEO_PROCESSING, ::add)
        roots.fastSdRoot?.let {
            scanImageRoot(it, MediaGallerySource.FAST_SD, { MediaGalleryLocation.LOCAL }, ::add)
        }

        remoteFastSdItems.forEach { remote ->
            add(
                MediaGalleryItem(
                    identity = remote.identity,
                    // A remote path is metadata only. Keep a harmless name placeholder until the
                    // owning FastSD gallery downloads a preview or share copy explicitly.
                    file = remote.previewFile ?: File(remote.name),
                    type = remote.type,
                    source = MediaGallerySource.FAST_SD,
                    location = remote.location,
                    mimeType = remote.mimeType,
                    createdAt = remote.createdAt,
                    title = remote.name,
                    previewFile = remote.previewFile,
                    remotePath = remote.path
                )
            )
        }

        return result.values
            .sortedWith(compareByDescending<MediaGalleryItem> { it.createdAt }.thenBy { it.identity.value })
    }

    /**
     * Parse [FAST_SD_LIST_COMMAND] output without interpreting remote paths as local files.
     * [endpoint] is part of the identity so equal filenames on two connected hosts remain
     * distinct. The parser is public for deterministic host-side tests and SSH adapters.
     */
    fun parseFastSdListing(
        output: String,
        endpoint: String? = null,
        resultsPath: String = FAST_SD_RESULTS_PATH
    ): List<MediaGalleryRemoteItem> {
        val root = resultsPath.trimEnd('/')
        val prefix = "$root/"
        val endpointKey = endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        return output
            .split('\u0000')
            .mapNotNull { record ->
                val separator = record.indexOf(' ')
                if (separator <= 0) return@mapNotNull null
                val modifiedSeconds = record.substring(0, separator).toDoubleOrNull()
                    ?: return@mapNotNull null
                val remotePath = record.substring(separator + 1)
                if (!remotePath.startsWith(prefix) || remotePath.length <= prefix.length) {
                    return@mapNotNull null
                }
                val name = remotePath.removePrefix(prefix)
                if (name.isBlank() || name.contains('/')) return@mapNotNull null
                val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
                if (extension !in fastSdImageExtensions) return@mapNotNull null
                val mimeType = when (extension) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    else -> "image/png"
                }
                MediaGalleryRemoteItem(
                    identity = MediaGalleryIdentity("fastsd:$endpointKey:$remotePath"),
                    name = name,
                    path = remotePath,
                    createdAt = (modifiedSeconds * 1000.0).toLong().coerceAtLeast(0L),
                    mimeType = mimeType,
                    location = MediaGalleryLocation.UNKNOWN
                )
            }
            .distinctBy { it.identity.value }
            .sortedWith(compareByDescending<MediaGalleryRemoteItem> { it.createdAt }.thenBy { it.identity.value })
    }

    /** Observe allowlisted output roots so a visible gallery refreshes after a completed write. */
    fun observe(context: Context, onChanged: () -> Unit): Observer =
        observe(MediaGalleryRoots.fromFilesDir(context.filesDir), onChanged)

    fun observe(roots: MediaGalleryRoots, onChanged: () -> Unit): Observer =
        Observer(roots, onChanged)

    class Observer internal constructor(
        private val roots: MediaGalleryRoots,
        private val onChanged: () -> Unit
    ) : AutoCloseable {
        private val workerThread = HandlerThread("media-gallery-watch").apply { start() }
        private val workerHandler = Handler(workerThread.looper)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val refreshRunnable = Runnable { if (!closed) onChanged() }
        private val rebuildRunnable = Runnable { if (!closed) rebuildWatchers() }
        private val observers = mutableMapOf<String, FileObserver>()
        private val watchedDirectoryPaths = mutableSetOf<String>()
        private val allowedEntriesByParent: Map<String, Set<String>> by lazy {
            roots.durableRoots()
                .groupBy { root -> canonicalPath(root.parentFile ?: root) }
                .mapValues { (_, children) -> children.map { it.name }.toSet() }
        }
        @Volatile
        private var closed = false

        init {
            workerHandler.post(rebuildRunnable)
        }

        override fun close() {
            closed = true
            mainHandler.removeCallbacks(refreshRunnable)
            workerHandler.removeCallbacks(rebuildRunnable)
            workerHandler.post {
                observers.values.forEach(FileObserver::stopWatching)
                observers.clear()
                watchedDirectoryPaths.clear()
                workerThread.quitSafely()
            }
        }

        private fun rebuildWatchers() {
            if (closed) return
            observers.values.forEach(FileObserver::stopWatching)
            observers.clear()
            watchedDirectoryPaths.clear()
            directoriesToWatch().forEach { directory ->
                val path = canonicalPath(directory)
                watchedDirectoryPaths += path
                val observer = object : FileObserver(path, WATCH_MASK) {
                    override fun onEvent(event: Int, relativePath: String?) {
                        if (closed || event == 0) return
                        workerHandler.post {
                            if (closed || !isRelevant(directory, relativePath)) return@post
                            mainHandler.removeCallbacks(refreshRunnable)
                            mainHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
                            if (isDirectoryChange(directory, relativePath, event)) {
                                workerHandler.removeCallbacks(rebuildRunnable)
                                workerHandler.postDelayed(rebuildRunnable, REFRESH_DEBOUNCE_MS)
                            }
                        }
                    }
                }
                runCatching { observer.startWatching() }
                observers[path] = observer
            }
        }

        private fun directoriesToWatch(): List<File> {
            val rootsToWatch = roots.durableRoots()
            val parentDirectories = rootsToWatch.mapNotNull { it.parentFile }
            return (parentDirectories + rootsToWatch + rootsToWatch.flatMap { root ->
                if (root.isDirectory) root.walkTopDown().filter(File::isDirectory).toList() else emptyList()
            }).filter(File::isDirectory).distinctBy(::canonicalPath)
        }

        private fun isRelevant(directory: File, relativePath: String?): Boolean {
            val allowedNames = allowedEntriesByParent[canonicalPath(directory)] ?: return true
            val topLevelName = relativePath?.substringBefore(File.separatorChar) ?: return false
            return topLevelName in allowedNames
        }

        private fun isDirectoryChange(directory: File, relativePath: String?, event: Int): Boolean {
            val child = relativePath?.let { File(directory, it) } ?: return false
            val childPath = canonicalPath(child)
            val newlyCreatedDirectory = event and CREATE_OR_MOVE_IN_EVENTS != 0 && child.isDirectory
            val removedDirectory = event and REMOVE_EVENTS != 0 && childPath in watchedDirectoryPaths
            return newlyCreatedDirectory || removedDirectory
        }

        private companion object {
            const val REFRESH_DEBOUNCE_MS = 750L
            val WATCH_MASK = FileObserver.CREATE or FileObserver.CLOSE_WRITE or
                FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO
            val CREATE_OR_MOVE_IN_EVENTS = FileObserver.CREATE or FileObserver.MOVED_TO
            val REMOVE_EVENTS = FileObserver.DELETE or FileObserver.MOVED_FROM
        }
    }

    fun filter(
        items: List<MediaGalleryItem>,
        type: MediaGalleryTypeFilter = MediaGalleryTypeFilter.ALL,
        source: MediaGallerySourceFilter = MediaGallerySourceFilter.ALL,
        location: MediaGalleryLocationFilter = MediaGalleryLocationFilter.ALL
    ): List<MediaGalleryItem> = items.asSequence()
        .filter { item ->
            when (type) {
                MediaGalleryTypeFilter.ALL -> true
                MediaGalleryTypeFilter.IMAGES -> item.type == MediaGalleryType.IMAGE
                MediaGalleryTypeFilter.VIDEOS -> item.type == MediaGalleryType.VIDEO
            }
        }
        .filter { item ->
            when (source) {
                MediaGallerySourceFilter.ALL -> true
                MediaGallerySourceFilter.SD -> item.source == MediaGallerySource.SD
                MediaGallerySourceFilter.FAST_SD -> item.source == MediaGallerySource.FAST_SD
                MediaGallerySourceFilter.ONNX -> item.source == MediaGallerySource.ONNX
                MediaGallerySourceFilter.TAMA -> item.source == MediaGallerySource.TAMA
                MediaGallerySourceFilter.WORKFLOW -> item.source == MediaGallerySource.WORKFLOW
                MediaGallerySourceFilter.PROCESSING -> item.source == MediaGallerySource.IMAGE_PROCESSING ||
                    item.source == MediaGallerySource.VIDEO_PROCESSING
            }
        }
        .filter { item ->
            when (location) {
                MediaGalleryLocationFilter.ALL -> true
                MediaGalleryLocationFilter.LOCAL -> item.location == MediaGalleryLocation.LOCAL
                MediaGalleryLocationFilter.DISTRIBUTED -> item.location == MediaGalleryLocation.DISTRIBUTED
                MediaGalleryLocationFilter.UNKNOWN -> item.location == MediaGalleryLocation.UNKNOWN
            }
        }
        .sortedWith(compareByDescending<MediaGalleryItem> { it.createdAt }.thenBy { it.identity.value })
        .toList()

    fun isProvenDistributed(item: MediaGalleryItem): Boolean =
        item.location == MediaGalleryLocation.DISTRIBUTED

    private fun scanSdImages(root: File, add: (MediaGalleryItem) -> Unit) {
        scanImageRoot(
            root = root,
            source = MediaGallerySource.SD,
            locationFor = { file ->
                readSdMetadata(file)?.let {
                    distributedLocation(
                        SdGeneratedImageMetadata.metadataFileForImage(file)
                    )
                }
                    ?: MediaGalleryLocation.UNKNOWN
            },
            add = add,
            relativeFirstSegments = setOf("", "txt2img", "img2img", "adetailer", "upscaled")
        )
    }

    private fun scanSdWorkflowImages(root: File, add: (MediaGalleryItem) -> Unit) {
        val workflowRoot = File(root, "workflow")
        scanImageRoot(
            root = workflowRoot,
            source = MediaGallerySource.WORKFLOW,
            locationFor = { file ->
                readSdMetadata(file)?.let {
                    distributedLocation(
                        SdGeneratedImageMetadata.metadataFileForImage(file)
                    )
                }
                    ?: MediaGalleryLocation.UNKNOWN
            },
            add = add
        )
    }

    private fun scanOnnxImages(root: File, add: (MediaGalleryItem) -> Unit) {
        scanImageRoot(
            root = root,
            source = MediaGallerySource.ONNX,
            locationFor = { MediaGalleryLocation.LOCAL },
            add = add,
            imageMetadataFor = { file -> readOnnxImageMetadata(file) }
        )
    }

    private fun scanTamaImages(root: File, add: (MediaGalleryItem) -> Unit) {
        scanImageRoot(
            root = root,
            source = MediaGallerySource.TAMA,
            locationFor = { MediaGalleryLocation.LOCAL },
            add = add
        )
    }

    private fun scanBackgroundRemoval(root: File, add: (MediaGalleryItem) -> Unit) {
        scanImageRoot(
            root = root,
            source = MediaGallerySource.IMAGE_PROCESSING,
            locationFor = { MediaGalleryLocation.LOCAL },
            add = add,
            include = { file -> !file.nameWithoutExtension.endsWith("_mask", ignoreCase = true) },
            backgroundRemovalMetadataFor = { file -> OnnxBackgroundRemovalStorage.readMetadata(file) }
        )
    }

    private fun scanImageRoot(
        root: File,
        source: MediaGallerySource,
        locationFor: (File) -> MediaGalleryLocation,
        add: (MediaGalleryItem) -> Unit,
        include: (File) -> Boolean = { true },
        imageMetadataFor: (File) -> OnnxGeneratedImageMetadata? = { null },
        backgroundRemovalMetadataFor: (File) -> OnnxBackgroundRemovalMetadata? = { null },
        relativeFirstSegments: Set<String>? = null
    ) {
        if (!root.isDirectory) return
        val rootCanonical = root.canonicalPath
        root.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.extension.lowercase(Locale.US) in imageExtensions &&
                    include(file) &&
                    (relativeFirstSegments == null || relativeFirstSegment(rootCanonical, file) in relativeFirstSegments)
            }
            .forEach { file ->
                val sdMetadata = if (source == MediaGallerySource.SD || source == MediaGallerySource.WORKFLOW) {
                    readSdMetadata(file)
                } else {
                    null
                }
                val onnxMetadata = imageMetadataFor(file)
                val bgrMetadata = backgroundRemovalMetadataFor(file)
                val sidecarIdentity = when {
                    sdMetadata != null -> "sd-meta:${canonicalPath(SdGeneratedImageMetadata.metadataFileForImage(file))}"
                    onnxMetadata != null -> "onnx-meta:${canonicalPath(OnnxStorage.metadataFileFor(file))}"
                    bgrMetadata != null -> "bgr-meta:${canonicalPath(OnnxBackgroundRemovalStorage.metadataFileFor(file))}"
                    else -> "file:${canonicalPath(file)}"
                }
                val metadataPrompt = sdMetadata?.prompt ?: onnxMetadata?.prompt.orEmpty()
                val metadataMode = sdMetadata?.mode ?: onnxMetadata?.mode.orEmpty()
                add(
                    MediaGalleryItem(
                        identity = MediaGalleryIdentity(sidecarIdentity),
                        file = file,
                        type = MediaGalleryType.IMAGE,
                        source = source,
                        location = locationFor(file),
                        mimeType = imageMimeType(file),
                        createdAt = sdMetadata?.createdAt
                            ?.takeIf { it > 0L }
                            ?: onnxMetadata?.createdAtEpochMs?.takeIf { it > 0L }
                            ?: bgrMetadata?.createdAtEpochMs?.takeIf { it > 0L }
                            ?: file.lastModified(),
                        title = metadataPrompt.ifBlank { file.nameWithoutExtension },
                        prompt = metadataPrompt,
                        mode = metadataMode,
                        imageMetadata = sdMetadata,
                        onnxImageMetadata = onnxMetadata,
                        backgroundRemovalMetadata = bgrMetadata
                    )
                )
            }
    }

    private fun scanGeneratedVideos(root: File, add: (MediaGalleryItem) -> Unit) {
        if (!root.isDirectory) return
        val metadataFiles = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .toList()
        val metadataArtifacts = mutableSetOf<String>()
        metadataFiles.forEach { metadataFile ->
            val metadata = GeneratedVideoMetadata.fromFile(metadataFile) ?: return@forEach
            val normalizedMetadata = normalizeVideoMetadata(root, metadataFile, metadata)
            val artifact = listOf(
                normalizedMetadata.preferredArtifactPath,
                normalizedMetadata.mp4Path,
                normalizedMetadata.aviPath,
                normalizedMetadata.nativeOutputPath
            )
                .mapNotNull { path -> resolveArtifact(metadataFile, path) }
                .firstOrNull { file -> file.isFile && isWithin(root, file) }
                ?: return@forEach
            listOf(normalizedMetadata.mp4Path, normalizedMetadata.aviPath, normalizedMetadata.nativeOutputPath)
                .mapNotNull { path -> resolveArtifact(metadataFile, path) }
                .filter { isWithin(root, it) }
                .forEach { metadataArtifacts += canonicalPath(it) }
            add(
                MediaGalleryItem(
                    identity = MediaGalleryIdentity("video-meta:${canonicalPath(metadataFile)}"),
                    file = artifact,
                    type = MediaGalleryType.VIDEO,
                    source = MediaGallerySource.SD,
                    location = distributedLocation(metadataFile),
                    mimeType = videoMimeType(artifact),
                    createdAt = metadata.createdAt.takeIf { it > 0L } ?: artifact.lastModified(),
                    title = metadata.prompt.ifBlank { artifact.nameWithoutExtension },
                    prompt = metadata.prompt,
                    mode = metadata.mode,
                    videoMetadata = normalizedMetadata
                )
            )
        }
        root.walkTopDown()
            .filter { file ->
                    file.isFile &&
                    file.extension.lowercase(Locale.US) in videoArtifactExtensions &&
                    canonicalPath(file) !in metadataArtifacts
            }
            .forEach { file ->
                add(
                    MediaGalleryItem(
                        identity = MediaGalleryIdentity("file:${canonicalPath(file)}"),
                        file = file,
                        type = MediaGalleryType.VIDEO,
                        source = MediaGallerySource.SD,
                        location = MediaGalleryLocation.UNKNOWN,
                        mimeType = videoMimeType(file),
                        createdAt = file.lastModified(),
                        title = file.nameWithoutExtension
                    )
                )
            }
    }
    private fun scanWorkflowMedia(roots: List<File>, add: (MediaGalleryItem) -> Unit) {
        roots.forEach { root ->
            if (!root.isDirectory) return@forEach
            root.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.extension.lowercase(Locale.US) in workflowMediaExtensions &&
                        !file.name.equals("workflow_metadata.json", ignoreCase = true) &&
                        !file.nameWithoutExtension.endsWith("_mask", ignoreCase = true)
                }
                .forEach { file ->
                    val type = mediaTypeFor(file) ?: return@forEach
                    add(
                        MediaGalleryItem(
                            identity = MediaGalleryIdentity("workflow:${canonicalPath(file)}"),
                            file = file,
                            type = type,
                            source = MediaGallerySource.WORKFLOW,
                            location = MediaGalleryLocation.LOCAL,
                            mimeType = mimeTypeFor(file),
                            createdAt = file.lastModified(),
                            title = file.nameWithoutExtension
                        )
                    )
                }
        }
    }

    private fun scanVideoRoot(
        root: File,
        source: MediaGallerySource,
        add: (MediaGalleryItem) -> Unit
    ) {
        if (!root.isDirectory) return
        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.US) in videoExtensions }
            .forEach { file ->
                add(
                    MediaGalleryItem(
                        identity = MediaGalleryIdentity("file:${canonicalPath(file)}"),
                        file = file,
                        type = MediaGalleryType.VIDEO,
                        source = source,
                        location = MediaGalleryLocation.LOCAL,
                        mimeType = videoMimeType(file),
                        createdAt = file.lastModified(),
                        title = file.nameWithoutExtension
                    )
                )
            }
    }

    private fun readSdMetadata(file: File): SdGeneratedImageMetadata? =
        runCatching { SdGeneratedImageMetadata.fromFile(SdGeneratedImageMetadata.metadataFileForImage(file)) }.getOrNull()

    private fun readOnnxImageMetadata(file: File): OnnxGeneratedImageMetadata? =
        runCatching { OnnxStorage.readMetadata(file) }.getOrNull()

    private fun relativeFirstSegment(rootCanonical: String, file: File): String {
        val relative = canonicalPath(file).removePrefix(rootCanonical).trimStart(File.separatorChar)
        return relative.substringBefore(File.separatorChar, missingDelimiterValue = "")
    }

    /**
     * The raw sidecar proves that the distributed flag was explicitly persisted. The metadata
     * readers default a missing flag to false, so treating that parsed default as LOCAL would
     * incorrectly certify old output.
     */
    private fun distributedLocation(metadataFile: File): MediaGalleryLocation {
        val explicitEnabled = runCatching {
            val json = JSONObject(metadataFile.readText())
            when {
                json.opt("distributedEnabled") is Boolean -> json.optBoolean("distributedEnabled")
                json.optJSONObject("distributedRuntime")?.opt("enabled") is Boolean ->
                    json.optJSONObject("distributedRuntime")?.optBoolean("enabled")
                else -> null
            }
        }.getOrNull()
        return when (explicitEnabled) {
            true -> MediaGalleryLocation.DISTRIBUTED
            false -> MediaGalleryLocation.LOCAL
            null -> MediaGalleryLocation.UNKNOWN
        }
    }

    private fun mediaTypeFor(file: File): MediaGalleryType? = when (file.extension.lowercase(Locale.US)) {
        in imageExtensions -> MediaGalleryType.IMAGE
        in videoExtensions -> MediaGalleryType.VIDEO
        else -> null
    }

    private fun mimeTypeFor(file: File): String = when (mediaTypeFor(file)) {
        MediaGalleryType.IMAGE -> imageMimeType(file)
        MediaGalleryType.VIDEO -> videoMimeType(file)
        null -> "application/octet-stream"
    }

    private fun imageMimeType(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/png"
    }

    private fun videoMimeType(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "webp" -> "image/webp"
        "avi" -> "video/x-msvideo"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        else -> "video/mp4"
    }

    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private fun resolveArtifact(metadataFile: File, path: String?): File? {
        if (path.isNullOrBlank()) return null
        val artifact = File(path)
        return if (artifact.isAbsolute) artifact else File(metadataFile.parentFile, path)
    }

    /** Keep output paths usable when a metadata sidecar contains relative paths or was moved. */
    private fun normalizeVideoMetadata(
        root: File,
        metadataFile: File,
        metadata: GeneratedVideoMetadata
    ): GeneratedVideoMetadata = metadata.copy(
        metadataPath = canonicalPath(metadataFile),
        aviPath = normalizeOutputPath(root, metadataFile, metadata.aviPath).orEmpty(),
        mp4Path = normalizeOutputPath(root, metadataFile, metadata.mp4Path).orEmpty(),
        nativeOutputPath = normalizeOutputPath(root, metadataFile, metadata.nativeOutputPath).orEmpty().ifBlank { null }
    )

    private fun normalizeOutputPath(root: File, metadataFile: File, path: String?): String? =
        path?.takeIf(String::isNotBlank)
            ?.let { resolveArtifact(metadataFile, it) }
            ?.takeIf { isWithin(root, it) }
            ?.let(::canonicalPath)

    private fun isWithin(root: File, file: File): Boolean {
        val rootPath = canonicalPath(root).trimEnd(File.separatorChar) + File.separator
        return canonicalPath(file).startsWith(rootPath)
    }

    private fun MediaGalleryRoots.durableRoots(): List<File> = listOf(
        sdRoot,
        videoRoot,
        onnxImageRoot,
        tamaRoot,
        *workflowRoots.toTypedArray(),
        backgroundRemovalRoot,
        videoUpscaleRoot,
        videoInterpolationRoot,
        subtitleOutputRoot,
        fastSdRoot
    ).filterNotNull()
}

fun List<MediaGalleryItem>.filterMediaGallery(
    type: MediaGalleryTypeFilter = MediaGalleryTypeFilter.ALL,
    source: MediaGallerySourceFilter = MediaGallerySourceFilter.ALL,
    location: MediaGalleryLocationFilter = MediaGalleryLocationFilter.ALL
): List<MediaGalleryItem> = MediaGalleryIndex.filter(this, type, source, location)
