package com.example.llamadroid.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle expressed as fractions of the original page width and height. */
data class MangaPixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class MangaNormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun clamped(): MangaNormalizedRect = MangaNormalizedRect(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f),
        bottom = bottom.coerceIn(0f, 1f)
    ).let { rect ->
        MangaNormalizedRect(
            left = min(rect.left, rect.right),
            top = min(rect.top, rect.bottom),
            right = max(rect.left, rect.right),
            bottom = max(rect.top, rect.bottom)
        )
    }

    fun toPixelBounds(width: Int, height: Int): MangaPixelBounds {
        val rect = clamped()
        return MangaPixelBounds(
            (rect.left * width).floorToInt().coerceIn(0, width),
            (rect.top * height).floorToInt().coerceIn(0, height),
            (rect.right * width).ceilToInt().coerceIn(0, width),
            (rect.bottom * height).ceilToInt().coerceIn(0, height)
        )
    }

    fun toPixelRect(width: Int, height: Int): Rect = toPixelBounds(width, height).let { bounds ->
        Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    fun padded(horizontal: Float, vertical: Float): MangaNormalizedRect = MangaNormalizedRect(
        left = left - horizontal,
        top = top - vertical,
        right = right + horizontal,
        bottom = bottom + vertical
    ).clamped()

    companion object {
        fun fromPixelRect(rect: Rect, width: Int, height: Int): MangaNormalizedRect {
            return fromPixelBounds(rect.left, rect.top, rect.right, rect.bottom, width, height)
        }

        fun fromPixelBounds(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            width: Int,
            height: Int
        ): MangaNormalizedRect {
            val safeWidth = width.coerceAtLeast(1).toFloat()
            val safeHeight = height.coerceAtLeast(1).toFloat()
            return MangaNormalizedRect(
                left = left / safeWidth,
                top = top / safeHeight,
                right = right / safeWidth,
                bottom = bottom / safeHeight
            ).clamped()
        }
    }
}

private fun Float.floorToInt(): Int = floor(this).toInt()
private fun Float.ceilToInt(): Int = ceil(this).toInt()

enum class MangaPaintedPageReviewState {
    UNREVIEWED,
    PAINTED,
    NO_TEXT
}

data class MangaPaintedRegionDescriptor(
    val regionId: String,
    val pageIndex: Int,
    val bounds: MangaNormalizedRect,
    val pixelCount: Int = 0,
    val ignoredTiny: Boolean = false
)

data class MangaPaintedPageReview(
    val pageIndex: Int,
    val sourceUri: String,
    val sourcePageIndex: Int,
    val width: Int,
    val height: Int,
    val state: MangaPaintedPageReviewState = MangaPaintedPageReviewState.UNREVIEWED,
    val regions: List<MangaPaintedRegionDescriptor> = emptyList(),
    val tinyMarksIgnored: Int = 0,
    val warning: String? = null
) {
    val isReviewed: Boolean get() = state != MangaPaintedPageReviewState.UNREVIEWED
    val hasRegions: Boolean get() = regions.isNotEmpty()
}

/** Stable reference placed in resumable manifests; it never contains page pixels. */
data class MangaPaintedOcrWorkspaceRef(
    val workspaceId: String,
    val revision: Long = 0L,
    val sourceFingerprint: String? = null
)

data class MangaPaintedOcrPageIndex(
    val pageIndex: Int,
    val sourceUri: String,
    val sourcePageIndex: Int,
    val displayName: String,
    val sourceKind: MangaTranslationSourceKind,
    val sourceFingerprint: String
)

data class MangaPaintedOcrWorkspace(
    val ref: MangaPaintedOcrWorkspaceRef,
    val directory: File,
    val pages: List<MangaPaintedOcrPageIndex>,
    val reviews: List<MangaPaintedPageReview>,
    val createdAt: Long,
    val updatedAt: Long
) {
    val reviewedPages: Int get() = reviews.count { it.isReviewed }
    val totalPages: Int get() = pages.size
    /** A document containing only explicitly skipped pages is a valid no-op. */
    val isReady: Boolean get() = totalPages > 0 && reviewedPages == totalPages
    val currentRef: MangaPaintedOcrWorkspaceRef
        get() = ref.copy(revision = ref.revision, sourceFingerprint = combinedSourceFingerprint(pages))

    fun reviewFor(pageIndex: Int): MangaPaintedPageReview? = reviews.firstOrNull { it.pageIndex == pageIndex }

    companion object {
        private fun combinedSourceFingerprint(pages: List<MangaPaintedOcrPageIndex>): String =
            pages.joinToString("|") { it.sourceFingerprint }
    }
}

data class MangaPaintedRegionAnalysis(
    val regions: List<MangaPaintedRegionDescriptor>,
    val ignoredTinyMarks: Int,
    val warnings: List<String>
)

/**
 * Pure mask analysis used by the editor and the runtime. A value above 2 is
 * considered painted. Components are deliberately 8-connected so diagonal
 * brush strokes form one OCR request.
 */
object MangaPaintedOcrSupport {
    const val PREVIEW_MAX_SIDE = 2048
    const val MIN_COMPONENT_PIXELS = 5
    const val MAX_REGIONS_PER_PAGE = 256
    const val MASK_THRESHOLD = 3

    fun analyzeMask(
        mask: ByteArray,
        width: Int,
        height: Int,
        pageIndex: Int = 0,
        minComponentPixels: Int = MIN_COMPONENT_PIXELS
    ): MangaPaintedRegionAnalysis {
        require(width > 0 && height > 0 && mask.size == width * height)
        val threshold = minComponentPixels.coerceAtLeast(1)
        val painted = BooleanArray(mask.size) { index ->
            (mask[index].toInt() and 0xff) >= MASK_THRESHOLD
        }
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val descriptors = mutableListOf<MangaPaintedRegionDescriptor>()
        var ignoredTiny = 0
        var componentNumber = 0
        for (start in painted.indices) {
            if (!painted[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            var pixels = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                pixels++
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val neighbor = ny * width + nx
                        if (painted[neighbor] && !visited[neighbor]) {
                            visited[neighbor] = true
                            if (tail < queue.size) queue[tail++] = neighbor
                        }
                    }
                }
            }
            componentNumber++
            if (pixels < threshold) {
                ignoredTiny++
                continue
            }
            if (descriptors.size >= MAX_REGIONS_PER_PAGE) continue
            descriptors += MangaPaintedRegionDescriptor(
                regionId = "p${pageIndex + 1}_painted_${descriptors.size + 1}",
                pageIndex = pageIndex,
                bounds = MangaNormalizedRect.fromPixelBounds(
                    minX,
                    minY,
                    (maxX + 1).coerceAtMost(width),
                    (maxY + 1).coerceAtMost(height),
                    width,
                    height
                ),
                pixelCount = pixels
            )
        }
        val warnings = buildList {
            if (ignoredTiny > 0) add("$ignoredTiny tiny painted mark(s) ignored")
            if (componentNumber > MAX_REGIONS_PER_PAGE) add("Too many painted regions; only the first $MAX_REGIONS_PER_PAGE were kept")
            if (descriptors.isEmpty() && componentNumber > 0) add("Paint a larger connected region or mark this page as No text")
        }
        return MangaPaintedRegionAnalysis(descriptors, ignoredTiny, warnings)
    }

    fun paddedRegion(
        region: MangaPaintedRegionDescriptor,
        paddingFraction: Float = 0.012f
    ): MangaNormalizedRect {
        val horizontal = max(paddingFraction, region.bounds.width * 0.08f)
        val vertical = max(paddingFraction, region.bounds.height * 0.10f)
        return region.bounds.padded(horizontal, vertical)
    }

    fun mapBoxFromRegionToPage(
        box: PdfOcrBox,
        regionRect: Rect,
        pageWidth: Int,
        pageHeight: Int,
        cropWidth: Int = regionRect.width().coerceAtLeast(1),
        cropHeight: Int = regionRect.height().coerceAtLeast(1)
    ): PdfOcrBox {
        val width = cropWidth.coerceAtLeast(1)
        val height = cropHeight.coerceAtLeast(1)
        return PdfOcrBox(
            left = (regionRect.left + box.left * regionRect.width() / width).coerceIn(0, pageWidth),
            top = (regionRect.top + box.top * regionRect.height() / height).coerceIn(0, pageHeight),
            right = (regionRect.left + box.right * regionRect.width() / width).coerceIn(0, pageWidth),
            bottom = (regionRect.top + box.bottom * regionRect.height() / height).coerceIn(0, pageHeight)
        )
    }

    fun orderRegions(
        regions: List<MangaPaintedRegionDescriptor>,
        pageHeight: Float,
        direction: MangaReadingDirection
    ): List<MangaPaintedRegionDescriptor> {
        if (regions.size <= 1) return regions
        val medianHeight = regions.map { it.bounds.height }.sorted()[regions.size / 2].coerceAtLeast(0.01f)
        // Bounds are normalized, but retaining the page-height argument keeps this helper
        // compatible with the existing reading-order API and prevents a tolerance larger than
        // the supplied page coordinate space.
        val tolerance = max(0.025f, medianHeight * 0.70f).coerceAtMost(pageHeight.coerceAtLeast(1f))
        val rows = mutableListOf<MutableList<MangaPaintedRegionDescriptor>>()
        val rowCenters = mutableListOf<Float>()
        regions.sortedBy { it.bounds.top }.forEach { region ->
            val center = region.bounds.top + region.bounds.height / 2f
            val row = rowCenters.indices.minByOrNull { index -> kotlin.math.abs(rowCenters[index] - center) }
                ?.takeIf { index -> kotlin.math.abs(rowCenters[index] - center) <= tolerance }
            if (row == null) {
                rows += mutableListOf(region)
                rowCenters += center
            } else {
                rows[row] += region
                rowCenters[row] = rows[row].map { it.bounds.top + it.bounds.height / 2f }.average().toFloat()
            }
        }
        return rows.indices.sortedBy { rowCenters[it] }.flatMap { row ->
            if (direction == MangaReadingDirection.RIGHT_TO_LEFT) {
                rows[row].sortedByDescending { it.bounds.left }
            } else {
                rows[row].sortedBy { it.bounds.left }
            }
        }
    }

    /** Build one OCR bitmap per component and neutralize pixels outside its painted mask. */
    fun createMaskedRegionBitmap(
        source: Bitmap,
        mask: ByteArray,
        maskWidth: Int,
        maskHeight: Int,
        region: MangaPaintedRegionDescriptor,
        paddingFraction: Float = 0.012f,
        neutralColor: Int? = null
    ): Bitmap {
        require(mask.size == maskWidth * maskHeight)
        val sourceRect = paddedRegion(region, paddingFraction).toPixelRect(source.width, source.height)
        val crop = Bitmap.createBitmap(source, sourceRect.left, sourceRect.top, sourceRect.width().coerceAtLeast(1), sourceRect.height().coerceAtLeast(1))
        val output = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(crop.width * crop.height)
        crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
        val sampledNeutral = neutralColor ?: sampleNeutralColor(pixels, crop.width, crop.height)
        val scaleX = maskWidth.toFloat() / source.width.toFloat().coerceAtLeast(1f)
        val scaleY = maskHeight.toFloat() / source.height.toFloat().coerceAtLeast(1f)
        for (y in 0 until crop.height) {
            for (x in 0 until crop.width) {
                val sourceX = sourceRect.left + x
                val sourceY = sourceRect.top + y
                val mx = (sourceX * scaleX).floorToInt().coerceIn(0, maskWidth - 1)
                val my = (sourceY * scaleY).floorToInt().coerceIn(0, maskHeight - 1)
                val painted = (mask[my * maskWidth + mx].toInt() and 0xff) >= MASK_THRESHOLD
                if (!painted) pixels[y * crop.width + x] = sampledNeutral
            }
        }
        output.setPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
        crop.recycle()
        return output
    }

    private fun sampleNeutralColor(pixels: IntArray, width: Int, height: Int): Int {
        if (pixels.isEmpty()) return Color.WHITE
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x != 0 && y != 0 && x != width - 1 && y != height - 1) continue
                val color = pixels[y * width + x]
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                count++
            }
        }
        return Color.rgb((red / count.coerceAtLeast(1)).toInt(), (green / count.coerceAtLeast(1)).toInt(), (blue / count.coerceAtLeast(1)).toInt())
    }

    fun workspaceFingerprint(ref: MangaPaintedOcrWorkspaceRef): String =
        listOf(ref.workspaceId, ref.revision.toString(), ref.sourceFingerprint.orEmpty()).joinToString(":")
}

/**
 * Durable manager for painted OCR drafts. Only bounded preview masks and normalized
 * descriptors are stored; source pages are always reopened from their URI at runtime.
 */
object MangaPaintedOcrWorkspaceManager {
    private const val ROOT = "manga_painted_ocr"
    private const val MANIFEST = "manifest.json"
    private const val PAGE_MASK_PREFIX = "mask_"
    private const val PAGE_MASK_SUFFIX = ".bin"
    private const val VERSION = 1
    private const val MAX_PREVIEW_SIDE = MangaPaintedOcrSupport.PREVIEW_MAX_SIDE
    private const val ABANDONED_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L

    suspend fun create(context: Context, sources: List<MangaTranslationSource>): MangaPaintedOcrWorkspace =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Keep cleanup bounded without running a background scanner. Completed and
            // abandoned workspaces are retained for seven days so a recent job can still be
            // resumed or inspected after navigation.
            sweepAbandoned(context)
            require(sources.isNotEmpty()) {
                context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_select_source)
            }
            val pages = indexSources(context, sources)
            require(pages.isNotEmpty()) {
                context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_no_pages)
            }
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val directory = File(File(context.filesDir, ROOT), id).apply { mkdirs() }
            val workspace = MangaPaintedOcrWorkspace(
                ref = MangaPaintedOcrWorkspaceRef(id, revision = 0L),
                directory = directory,
                pages = pages,
                reviews = pages.map { page ->
                    MangaPaintedPageReview(
                        pageIndex = page.pageIndex,
                        sourceUri = page.sourceUri,
                        sourcePageIndex = page.sourcePageIndex,
                        width = 1,
                        height = 1
                    )
                },
                createdAt = now,
                updatedAt = now
            )
            write(workspace)
            workspace.copy(ref = workspace.currentRef)
        }

    fun load(context: Context, ref: MangaPaintedOcrWorkspaceRef): MangaPaintedOcrWorkspace? =
        runCatching {
            sweepAbandoned(context)
            val directory = workspaceDirectory(context, ref.workspaceId)
            val file = File(directory, MANIFEST)
            if (!file.isFile) return null
            fromJson(org.json.JSONObject(file.readText()), directory)
        }.getOrNull()

    suspend fun savePageMask(
        context: Context,
        ref: MangaPaintedOcrWorkspaceRef,
        pageIndex: Int,
        width: Int,
        height: Int,
        mask: ByteArray
    ): MangaPaintedOcrWorkspace = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val workspace = requireNotNull(load(context, ref)) {
            context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_workspace_unavailable)
        }
        require(pageIndex in workspace.pages.indices) {
            context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_page_unavailable)
        }
        require(width > 0 && height > 0)
        require(width.toLong() * height.toLong() == mask.size.toLong())
        require(width <= MAX_PREVIEW_SIDE && height <= MAX_PREVIEW_SIDE) {
            context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_mask_too_large)
        }
        File(workspace.directory, "$PAGE_MASK_PREFIX${pageIndex}$PAGE_MASK_SUFFIX").writeBytes(mask)
        val analysis = MangaPaintedOcrSupport.analyzeMask(mask, width, height, pageIndex)
        val page = workspace.pages[pageIndex]
        val updatedReview = MangaPaintedPageReview(
            pageIndex = pageIndex,
            sourceUri = page.sourceUri,
            sourcePageIndex = page.sourcePageIndex,
            width = width,
            height = height,
            state = if (analysis.regions.isEmpty()) MangaPaintedPageReviewState.UNREVIEWED else MangaPaintedPageReviewState.PAINTED,
            regions = analysis.regions,
            tinyMarksIgnored = analysis.ignoredTinyMarks,
            warning = analysis.warnings.joinToString("; ").ifBlank { null }
        )
        val now = System.currentTimeMillis()
        val updated = workspace.copy(
            ref = workspace.ref.copy(revision = workspace.ref.revision + 1, sourceFingerprint = workspace.currentRef.sourceFingerprint),
            reviews = workspace.reviews.map { if (it.pageIndex == pageIndex) updatedReview else it },
            updatedAt = now
        )
        write(updated)
        updated
    }

    suspend fun markNoText(
        context: Context,
        ref: MangaPaintedOcrWorkspaceRef,
        pageIndex: Int
    ): MangaPaintedOcrWorkspace = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val workspace = requireNotNull(load(context, ref)) {
            context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_workspace_unavailable)
        }
        val page = workspace.pages.getOrNull(pageIndex) ?: error(
            context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_page_unavailable)
        )
        File(workspace.directory, "$PAGE_MASK_PREFIX${pageIndex}$PAGE_MASK_SUFFIX").delete()
        val updated = workspace.copy(
            ref = workspace.ref.copy(revision = workspace.ref.revision + 1, sourceFingerprint = workspace.currentRef.sourceFingerprint),
            reviews = workspace.reviews.map {
                if (it.pageIndex == pageIndex) MangaPaintedPageReview(
                    pageIndex = pageIndex,
                    sourceUri = page.sourceUri,
                    sourcePageIndex = page.sourcePageIndex,
                    width = it.width,
                    height = it.height,
                    state = MangaPaintedPageReviewState.NO_TEXT
                ) else it
            },
            updatedAt = System.currentTimeMillis()
        )
        write(updated)
        updated
    }

    suspend fun clearPageReview(
        context: Context,
        ref: MangaPaintedOcrWorkspaceRef,
        pageIndex: Int
    ): MangaPaintedOcrWorkspace = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val workspace = requireNotNull(load(context, ref)) {
            context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_workspace_unavailable)
        }
        File(workspace.directory, "$PAGE_MASK_PREFIX${pageIndex}$PAGE_MASK_SUFFIX").delete()
        val updated = workspace.copy(
            ref = workspace.ref.copy(revision = workspace.ref.revision + 1, sourceFingerprint = workspace.currentRef.sourceFingerprint),
            reviews = workspace.reviews.map { if (it.pageIndex == pageIndex) it.copy(state = MangaPaintedPageReviewState.UNREVIEWED, regions = emptyList(), warning = null) else it },
            updatedAt = System.currentTimeMillis()
        )
        write(updated)
        updated
    }

    fun readMask(context: Context, ref: MangaPaintedOcrWorkspaceRef, pageIndex: Int): ByteArray? =
        runCatching {
            val workspace = requireNotNull(load(context, ref))
            val file = File(workspace.directory, "$PAGE_MASK_PREFIX${pageIndex}$PAGE_MASK_SUFFIX")
            file.takeIf { it.isFile && it.length() <= MAX_PREVIEW_SIDE.toLong() * MAX_PREVIEW_SIDE.toLong() }
                ?.readBytes()
        }.getOrNull()

    fun isSourceValid(context: Context, workspace: MangaPaintedOcrWorkspace): Boolean =
        runCatching {
            workspace.pages.all { page ->
                val source = Uri.parse(page.sourceUri)
                currentSourceFingerprint(context, source, page.displayName, page.sourceKind) == page.sourceFingerprint
            }
        }.getOrDefault(false)

    fun invalidateIfSourcesChanged(context: Context, ref: MangaPaintedOcrWorkspaceRef): MangaPaintedOcrWorkspace? {
        val workspace = load(context, ref) ?: return null
        val changedSourceUris = workspace.pages.asSequence()
            .filter { page ->
                currentSourceFingerprint(
                    context,
                    Uri.parse(page.sourceUri),
                    page.displayName,
                    page.sourceKind
                ) != page.sourceFingerprint
            }
            .map { it.sourceUri }
            .toSet()
        if (changedSourceUris.isEmpty()) return workspace
        val updatedPages = workspace.pages.map { page ->
            if (page.sourceUri in changedSourceUris) {
                // A changed source invalidates the corresponding preview mask as well as its
                // normalized descriptors. Leaving mask_N.bin behind would let a later editor
                // reopen stale pixels when the replacement page happens to have the same size.
                File(workspace.directory, "$PAGE_MASK_PREFIX${page.pageIndex}$PAGE_MASK_SUFFIX").delete()
                page.copy(
                    sourceFingerprint = currentSourceFingerprint(
                        context,
                        Uri.parse(page.sourceUri),
                        page.displayName,
                        page.sourceKind
                    )
                )
            } else page
        }
        return workspace.copy(
            ref = workspace.ref.copy(revision = workspace.ref.revision + 1),
            pages = updatedPages,
            reviews = workspace.reviews.map { review ->
                if (review.sourceUri in changedSourceUris) {
                    review.copy(
                        state = MangaPaintedPageReviewState.UNREVIEWED,
                        regions = emptyList(),
                        warning = "Source page changed; review this page again"
                    )
                } else review
            },
            updatedAt = System.currentTimeMillis()
        ).also { updated -> write(updated) }
    }

    fun sweepAbandoned(context: Context, nowMs: Long = System.currentTimeMillis()): Int {
        val root = File(context.filesDir, ROOT)
        var removed = 0
        root.listFiles()?.filter { directory -> directory.isDirectory }.orEmpty().forEach { directory ->
            val manifest = File(directory, MANIFEST)
            val lastTouched = maxOf(directory.lastModified(), manifest.lastModified())
            if (lastTouched > 0L && nowMs - lastTouched > ABANDONED_RETENTION_MS && directory.deleteRecursively()) removed++
        }
        return removed
    }

    fun delete(context: Context, ref: MangaPaintedOcrWorkspaceRef): Boolean =
        runCatching { workspaceDirectory(context, ref.workspaceId).deleteRecursively() }.getOrDefault(false)

    /** Bounded active-page decode. No page image is retained by the workspace manager. */
    suspend fun decodePagePreview(
        context: Context,
        workspace: MangaPaintedOcrWorkspace,
        pageIndex: Int,
        maxSide: Int = MAX_PREVIEW_SIDE
    ): Bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val page = workspace.pages.getOrNull(pageIndex) ?: error(
            context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_page_unavailable)
        )
        return@withContext when (page.sourceKind) {
            MangaTranslationSourceKind.CBZ -> decodeCbzPagePreview(context, page, maxSide)
            MangaTranslationSourceKind.PDF -> decodePdfPagePreview(context, page, maxSide)
        }
    }

    private fun decodeCbzPagePreview(context: Context, page: MangaPaintedOcrPageIndex, maxSide: Int): Bitmap {
        val temporary = File(context.cacheDir, "painted_ocr_${UUID.randomUUID()}.cbz")
        return try {
            context.contentResolver.openInputStream(Uri.parse(page.sourceUri))?.use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            } ?: error(context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_open_comic))
            ZipFile(temporary).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && MangaTranslationSupport.isSafeComicZipEntryName(it.name) && isComicImage(it.name) }
                    .sortedWith(compareBy { PDFTranslationLogic.naturalSortKey(it.name).joinToString("\u0000") })
                    .toList()
                val entry = entries.getOrNull(page.sourcePageIndex) ?: error(
                    context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_page_unavailable)
                )
                val pageFile = File(context.cacheDir, "painted_ocr_${UUID.randomUUID()}.page")
                try {
                    zip.getInputStream(entry).use { input -> FileOutputStream(pageFile).use { output -> input.copyTo(output) } }
                    decodeBoundedFile(context, pageFile, maxSide)
                } finally {
                    pageFile.delete()
                }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun decodePdfPagePreview(context: Context, page: MangaPaintedOcrPageIndex, maxSide: Int): Bitmap {
        val temporary = File(context.cacheDir, "painted_ocr_${UUID.randomUUID()}.pdf")
        return try {
            context.contentResolver.openInputStream(Uri.parse(page.sourceUri))?.use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            } ?: error(context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_open_pdf))
            ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.openPage(page.sourcePageIndex).use { pdfPage ->
                        val scale = (maxSide.toFloat() / max(pdfPage.width, pdfPage.height).toFloat()).coerceAtMost(1f)
                        val width = (pdfPage.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (pdfPage.height * scale).roundToInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            Canvas(bitmap).drawColor(Color.WHITE)
                            pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun decodeBoundedFile(context: Context, file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / (sample * 2) > maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error(context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_decode_page))
        if (max(bitmap.width, bitmap.height) <= maxSide) return bitmap
        val scale = maxSide.toFloat() / max(bitmap.width, bitmap.height).toFloat()
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true).also { bitmap.recycle() }
    }

    private fun indexSources(context: Context, sources: List<MangaTranslationSource>): List<MangaPaintedOcrPageIndex> {
        val pages = mutableListOf<MangaPaintedOcrPageIndex>()
        sources.forEach { source ->
            val kind = MangaTranslationSupport.sourceKindFor(source.displayName, source.mimeType)
                ?: error(context.getString(com.example.llamadroid.R.string.workflow_manga_unsupported_file))
            val count = when (kind) {
                MangaTranslationSourceKind.CBZ -> countCbzPages(context, source.uri)
                MangaTranslationSourceKind.PDF -> countPdfPages(context, source.uri)
            }
            val fingerprint = currentSourceFingerprint(context, source.uri, source.displayName, kind)
            repeat(count) { sourcePageIndex ->
                pages += MangaPaintedOcrPageIndex(
                    pageIndex = pages.size,
                    sourceUri = source.uri.toString(),
                    sourcePageIndex = sourcePageIndex,
                    displayName = source.displayName,
                    sourceKind = kind,
                    sourceFingerprint = fingerprint
                )
            }
        }
        return pages
    }

    private fun countCbzPages(context: Context, uri: Uri): Int {
        val temporary = File(context.cacheDir, "painted_ocr_index_${UUID.randomUUID()}.cbz")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temporary).use { output -> input.copyTo(output) } }
                ?: error(context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_open_comic))
            return ZipFile(temporary).use { zip ->
                zip.entries().asSequence().count { !it.isDirectory && MangaTranslationSupport.isSafeComicZipEntryName(it.name) && isComicImage(it.name) }
            }
        } finally { temporary.delete() }
    }

    private fun countPdfPages(context: Context, uri: Uri): Int {
        val temporary = File(context.cacheDir, "painted_ocr_index_${UUID.randomUUID()}.pdf")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temporary).use { output -> input.copyTo(output) } }
                ?: error(context.getString(com.example.llamadroid.R.string.workflow_manga_painted_error_open_pdf))
            return ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
        } finally { temporary.delete() }
    }

    private fun currentSourceFingerprint(context: Context, uri: Uri, displayName: String, kind: MangaTranslationSourceKind): String {
        val metadata = buildString {
            append(uri)
            append('|').append(displayName)
            append('|').append(kind.name)
            runCatching {
                context.contentResolver.query(uri, arrayOf("_size", "last_modified"), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        append('|').append(cursor.getLongOrNull(0)).append('|').append(cursor.getLongOrNull(1))
                    }
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(metadata.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
        if (index in 0 until columnCount && !isNull(index)) getLong(index) else null

    private fun isComicImage(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") || lower.endsWith(".avif") || lower.endsWith(".heic") || lower.endsWith(".heif") ||
            lower.endsWith(".bmp") || lower.endsWith(".gif")
    }

    private fun workspaceDirectory(context: Context, id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,96}"))) { "Invalid painted OCR workspace id" }
        val root = File(context.filesDir, ROOT).canonicalFile
        val directory = File(root, id).canonicalFile
        require(directory.parentFile == root) { "Painted OCR workspace is outside its root" }
        return directory
    }

    private fun write(workspace: MangaPaintedOcrWorkspace) {
        workspace.directory.mkdirs()
        val file = File(workspace.directory, MANIFEST)
        val temporary = File(workspace.directory, "$MANIFEST.tmp")
        temporary.writeText(toJson(workspace).toString(2))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
        workspace.directory.setLastModified(System.currentTimeMillis())
    }

    private fun toJson(workspace: MangaPaintedOcrWorkspace): org.json.JSONObject = org.json.JSONObject()
        .put("version", VERSION)
        .put("workspaceId", workspace.ref.workspaceId)
        .put("revision", workspace.ref.revision)
        .put("sourceFingerprint", workspace.ref.sourceFingerprint)
        .put("createdAt", workspace.createdAt)
        .put("updatedAt", workspace.updatedAt)
        .put("pages", org.json.JSONArray().apply { workspace.pages.forEach { page ->
            put(org.json.JSONObject()
                .put("pageIndex", page.pageIndex)
                .put("sourceUri", page.sourceUri)
                .put("sourcePageIndex", page.sourcePageIndex)
                .put("displayName", page.displayName)
                .put("sourceKind", page.sourceKind.name)
                .put("sourceFingerprint", page.sourceFingerprint))
        } })
        .put("reviews", org.json.JSONArray().apply { workspace.reviews.forEach { review ->
            put(org.json.JSONObject()
                .put("pageIndex", review.pageIndex)
                .put("sourceUri", review.sourceUri)
                .put("sourcePageIndex", review.sourcePageIndex)
                .put("width", review.width)
                .put("height", review.height)
                .put("state", review.state.name)
                .put("tinyMarksIgnored", review.tinyMarksIgnored)
                .put("warning", review.warning)
                .put("regions", org.json.JSONArray().apply { review.regions.forEach { region ->
                    put(org.json.JSONObject()
                        .put("regionId", region.regionId)
                        .put("pageIndex", region.pageIndex)
                        .put("left", region.bounds.left.toDouble())
                        .put("top", region.bounds.top.toDouble())
                        .put("right", region.bounds.right.toDouble())
                        .put("bottom", region.bounds.bottom.toDouble())
                        .put("pixelCount", region.pixelCount))
                } }))
        } })

    private fun fromJson(json: org.json.JSONObject, directory: File): MangaPaintedOcrWorkspace {
        val pages = buildList {
            val array = json.optJSONArray("pages") ?: org.json.JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val kind = runCatching { MangaTranslationSourceKind.valueOf(item.optString("sourceKind")) }.getOrNull() ?: continue
                add(MangaPaintedOcrPageIndex(
                    pageIndex = item.optInt("pageIndex", index),
                    sourceUri = item.optString("sourceUri"),
                    sourcePageIndex = item.optInt("sourcePageIndex"),
                    displayName = item.optString("displayName"),
                    sourceKind = kind,
                    sourceFingerprint = item.optString("sourceFingerprint")
                ))
            }
        }.sortedBy { it.pageIndex }
        val reviews = pages.map { page ->
            val item = (json.optJSONArray("reviews") ?: org.json.JSONArray()).let { array ->
                (0 until array.length()).asSequence().mapNotNull { array.optJSONObject(it) }.firstOrNull { it.optInt("pageIndex", -1) == page.pageIndex }
            }
            val regions = buildList {
                val array = item?.optJSONArray("regions") ?: org.json.JSONArray()
                for (index in 0 until array.length()) {
                    val region = array.optJSONObject(index) ?: continue
                    add(MangaPaintedRegionDescriptor(
                        regionId = region.optString("regionId", "p${page.pageIndex + 1}_painted_${index + 1}"),
                        pageIndex = page.pageIndex,
                        bounds = MangaNormalizedRect(
                            region.optDouble("left", 0.0).toFloat(),
                            region.optDouble("top", 0.0).toFloat(),
                            region.optDouble("right", 0.0).toFloat(),
                            region.optDouble("bottom", 0.0).toFloat()
                        ).clamped(),
                        pixelCount = region.optInt("pixelCount", 0)
                    ))
                }
            }
            MangaPaintedPageReview(
                pageIndex = page.pageIndex,
                sourceUri = page.sourceUri,
                sourcePageIndex = page.sourcePageIndex,
                width = item?.optInt("width", 1) ?: 1,
                height = item?.optInt("height", 1) ?: 1,
                state = runCatching { MangaPaintedPageReviewState.valueOf(item?.optString("state").orEmpty()) }.getOrDefault(MangaPaintedPageReviewState.UNREVIEWED),
                regions = regions,
                tinyMarksIgnored = item?.optInt("tinyMarksIgnored", 0) ?: 0,
                warning = item?.optString("warning")?.takeIf { it.isNotBlank() }
            )
        }
        val id = json.optString("workspaceId", directory.name)
        return MangaPaintedOcrWorkspace(
            ref = MangaPaintedOcrWorkspaceRef(id, json.optLong("revision", 0L), json.optString("sourceFingerprint").takeIf { it.isNotBlank() }),
            directory = directory,
            pages = pages,
            reviews = reviews,
            createdAt = json.optLong("createdAt", directory.lastModified()),
            updatedAt = json.optLong("updatedAt", directory.lastModified())
        )
    }
}
