package com.example.llamadroid.ui.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

internal enum class InpaintCanvasTransform {
    FIT,
    CENTER_CROP;

    companion object {
        fun fromStoredValue(value: String?): InpaintCanvasTransform =
            entries.firstOrNull { it.name == value } ?: FIT
    }
}

internal enum class InpaintMaskProvenance {
    DRAWN,
    IMPORTED,
    AUTO_SUBJECT,
    AUTO_BACKGROUND
}

internal data class InpaintWorkspace(
    val id: String,
    val directory: File,
    val sourcePath: String,
    val maskPath: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val transform: InpaintCanvasTransform,
    val provenance: InpaintMaskProvenance
)

/** Owns canonical source/mask files so drafts never overwrite each other in cache. */
internal object InpaintWorkspaceManager {
    private const val ROOT_DIRECTORY = "image_edits"
    private const val SOURCE_FILE = "source.png"
    private const val MASK_FILE = "mask.png"
    private const val ORPHAN_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L

    suspend fun create(
        context: Context,
        sourceUri: Uri,
        canvasWidth: Int,
        canvasHeight: Int,
        transform: InpaintCanvasTransform = InpaintCanvasTransform.FIT
    ): InpaintWorkspace = withContext(Dispatchers.IO) {
        requireValidCanvas(canvasWidth, canvasHeight)
        val source = context.contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
            ?: error("Unable to decode the inpaint source image")
        val id = UUID.randomUUID().toString()
        val directory = File(File(context.filesDir, ROOT_DIRECTORY), id).apply { mkdirs() }
        val sourceFile = File(directory, SOURCE_FILE)
        val maskFile = File(directory, MASK_FILE)
        try {
            val canonical = renderCanonicalSource(source, canvasWidth, canvasHeight, transform)
            writeBitmap(canonical, sourceFile)
            writeMask(InpaintMaskRaster.empty(canvasWidth, canvasHeight), maskFile)
            canonical.recycle()
            InpaintWorkspace(
                id = id,
                directory = directory,
                sourcePath = sourceFile.absolutePath,
                maskPath = maskFile.absolutePath,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                transform = transform,
                provenance = InpaintMaskProvenance.DRAWN
            )
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        } finally {
            source.recycle()
        }
    }

    suspend fun saveMask(
        workspace: InpaintWorkspace,
        raster: InpaintMaskRaster,
        provenance: InpaintMaskProvenance = InpaintMaskProvenance.DRAWN
    ): InpaintWorkspace = withContext(Dispatchers.IO) {
        require(raster.width == workspace.canvasWidth && raster.height == workspace.canvasHeight) {
            "Mask dimensions do not match the inpaint canvas"
        }
        require(!raster.isEmpty()) { "Mask has no editable pixels" }
        val file = File(workspace.maskPath)
        require(file.parentFile?.canonicalFile == workspace.directory.canonicalFile) {
            "Mask path is outside its workspace"
        }
        writeMask(raster, file)
        workspace.directory.setLastModified(System.currentTimeMillis())
        workspace.copy(provenance = provenance)
    }

    suspend fun importMask(
        context: Context,
        workspace: InpaintWorkspace,
        maskUri: Uri
    ): InpaintWorkspace = withContext(Dispatchers.IO) {
        val bitmap = context.contentResolver.openInputStream(maskUri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
            ?: error("Unable to decode the imported mask")
        try {
            require(
                InpaintMaskRaster.compatibleAspectRatio(
                    bitmap.width,
                    bitmap.height,
                    workspace.canvasWidth,
                    workspace.canvasHeight
                )
            ) { "Imported mask aspect ratio does not match the source canvas" }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val grayscale = ByteArray(pixels.size) { index ->
                val color = pixels[index]
                val alpha = color ushr 24 and 0xff
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                val luminance = (red * 299 + green * 587 + blue * 114) / 1000
                min(alpha, luminance).toByte()
            }
            val raster = if (bitmap.width == workspace.canvasWidth && bitmap.height == workspace.canvasHeight) {
                InpaintMaskRaster.fromBytes(workspace.canvasWidth, workspace.canvasHeight, grayscale)
            } else {
                InpaintMaskRaster.resizeNearest(
                    bitmap.width,
                    bitmap.height,
                    grayscale,
                    workspace.canvasWidth,
                    workspace.canvasHeight
                )
            }
            saveMask(workspace, raster, InpaintMaskProvenance.IMPORTED)
        } finally {
            bitmap.recycle()
        }
    }

    fun fromPaths(
        sourcePath: String,
        maskPath: String,
        transform: InpaintCanvasTransform = InpaintCanvasTransform.FIT,
        provenance: InpaintMaskProvenance = InpaintMaskProvenance.DRAWN
    ): InpaintWorkspace? {
        val sourceFile = File(sourcePath)
        val maskFile = File(maskPath)
        if (!sourceFile.isFile || !maskFile.isFile || sourceFile.parentFile != maskFile.parentFile) return null
        val root = sourceFile.parentFile ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return InpaintWorkspace(
            id = root.name,
            directory = root,
            sourcePath = sourceFile.absolutePath,
            maskPath = maskFile.absolutePath,
            canvasWidth = bounds.outWidth,
            canvasHeight = bounds.outHeight,
            transform = transform,
            provenance = provenance
        )
    }

    fun delete(workspace: InpaintWorkspace): Boolean = workspace.directory.deleteRecursively()

    fun sweepOrphans(
        context: Context,
        referencedWorkspaceIds: Set<String>,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val root = File(context.filesDir, ROOT_DIRECTORY)
        val directories = root.listFiles { file -> file.isDirectory }.orEmpty()
        var removed = 0
        directories.forEach { directory ->
            val isReferenced = directory.name in referencedWorkspaceIds
            val isExpired = nowMs - directory.lastModified() > ORPHAN_MAX_AGE_MS
            if (!isReferenced && isExpired && directory.deleteRecursively()) removed++
        }
        return removed
    }

    private fun requireValidCanvas(width: Int, height: Int) {
        require(width in 64..4096 && height in 64..4096 && width % 8 == 0 && height % 8 == 0) {
            "Inpaint canvas must be 64..4096 and divisible by 8"
        }
    }

    private fun renderCanonicalSource(
        source: Bitmap,
        width: Int,
        height: Int,
        transform: InpaintCanvasTransform
    ): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val sourceRect = Rect(0, 0, source.width, source.height)
        val scale = when (transform) {
            InpaintCanvasTransform.FIT -> min(width.toFloat() / source.width, height.toFloat() / source.height)
            InpaintCanvasTransform.CENTER_CROP -> max(width.toFloat() / source.width, height.toFloat() / source.height)
        }
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val destination = RectF(
            (width - drawWidth) / 2f,
            (height - drawHeight) / 2f,
            (width + drawWidth) / 2f,
            (height + drawHeight) / 2f
        )
        canvas.drawBitmap(source, sourceRect, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private fun writeMask(raster: InpaintMaskRaster, target: File) {
        val bitmap = Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(
                raster.toOpaqueGrayscaleArgb(),
                0,
                raster.width,
                0,
                0,
                raster.width,
                raster.height
            )
            writeBitmap(bitmap, target)
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeBitmap(bitmap: Bitmap, target: File) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Failed to write PNG" }
        }
    }
}
