package com.example.llamadroid.service

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Debug
import android.os.Process
import com.example.llamadroid.util.AppVersionCodeCompat
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

internal data class LiteRtGpuProbeResult(
    val ok: Boolean,
    val vendor: String = "",
    val renderer: String = "",
    val version: String = "",
    val eglVendor: String = "",
    val eglVersion: String = "",
    val eglClientApis: String = "",
    val eglExtensionsSummary: String = "",
    val glExtensionsSummary: String = "",
    val glExtensionCount: Int = 0,
    val glError: Int = 0,
    val error: String? = null
)

internal data class LiteRtGpuStartupDiagnostics(
    val probe: LiteRtGpuProbeResult,
    val cacheDirPath: String,
    val cacheDirWritable: Boolean,
    val cacheAvailableBytes: Long,
    val stagedModelPath: String,
    val stagedModelSizeBytes: Long,
    val detailLines: List<String> = emptyList()
) {
    fun toLogLines(): List<String> = buildList {
        add(
            "GPU probe ok=${probe.ok} vendor=${probe.vendor.ifBlank { "-" }} " +
                "renderer=${probe.renderer.ifBlank { "-" }} version=${probe.version.ifBlank { "-" }}"
        )
        add(
            "GPU EGL vendor=${probe.eglVendor.ifBlank { "-" }} " +
                "version=${probe.eglVersion.ifBlank { "-" }} clientApis=${probe.eglClientApis.ifBlank { "-" }}"
        )
        probe.eglExtensionsSummary.takeIf { it.isNotBlank() }?.let { add("GPU EGL extensions $it") }
        probe.glExtensionsSummary.takeIf { it.isNotBlank() }?.let {
            add("GPU GL extensions count=${probe.glExtensionCount} $it")
        }
        if (probe.glError != 0) {
            add("GPU probe glError=0x${probe.glError.toString(16)}")
        }
        probe.error?.takeIf { it.isNotBlank() }?.let { add("GPU probe error=$it") }
        add("GPU staged model path=$stagedModelPath sizeBytes=$stagedModelSizeBytes")
        add("GPU cache dir=$cacheDirPath writable=$cacheDirWritable availableBytes=$cacheAvailableBytes")
        detailLines.forEach { add("GPU diagnostic $it") }
    }

    companion object {
        fun collect(
            context: Context,
            sourceModelPath: File,
            stagedModelPath: File,
            cacheDir: File,
            probe: LiteRtGpuProbeResult = LiteRtGpuProbe.probe()
        ): LiteRtGpuStartupDiagnostics {
            runCatching { cacheDir.mkdirs() }
            return LiteRtGpuStartupDiagnostics(
                probe = probe,
                cacheDirPath = cacheDir.absolutePath,
                cacheDirWritable = cacheDir.isDirectory && cacheDir.canWrite(),
                cacheAvailableBytes = runCatching { cacheDir.usableSpace }.getOrDefault(0L),
                stagedModelPath = stagedModelPath.absolutePath,
                stagedModelSizeBytes = fileOrDirectorySize(stagedModelPath),
                detailLines = buildList {
                    addAll(deviceDiagnosticLines(context))
                    addAll(processDiagnosticLines(context))
                    addAll(memoryDiagnosticLines(context))
                    addAll(nativeLibraryDiagnosticLines(context))
                    addAll(openClDiagnosticLines())
                    addAll(modelDiagnosticLines(sourceModelPath, stagedModelPath))
                    addAll(cacheDiagnosticLines(cacheDir))
                }
            )
        }

        fun collect(
            modelPath: File,
            cacheDir: File,
            probe: LiteRtGpuProbeResult = LiteRtGpuProbe.probe()
        ): LiteRtGpuStartupDiagnostics {
            runCatching { cacheDir.mkdirs() }
            return LiteRtGpuStartupDiagnostics(
                probe = probe,
                cacheDirPath = cacheDir.absolutePath,
                cacheDirWritable = cacheDir.isDirectory && cacheDir.canWrite(),
                cacheAvailableBytes = runCatching { cacheDir.usableSpace }.getOrDefault(0L),
                stagedModelPath = modelPath.absolutePath,
                stagedModelSizeBytes = fileOrDirectorySize(modelPath)
            )
        }
    }
}

internal object LiteRtGpuProbe {
    fun probe(): LiteRtGpuProbeResult {
        return try {
            val context = createCurrentContext()
            try {
                context.probe
            } finally {
                context.close()
            }
        } catch (error: Throwable) {
            LiteRtGpuProbeResult(ok = false, error = error.message ?: error.javaClass.name)
        }
    }

    fun createCurrentContext(): LiteRtGpuCurrentContext {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var context = EGL14.EGL_NO_CONTEXT
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay returned EGL_NO_DISPLAY" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) {
                "eglInitialize failed: ${eglErrorString()}"
            }

            val config = chooseConfig(display)
                ?: error("eglChooseConfig found no pbuffer config")
            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0
            )
            check(surface != EGL14.EGL_NO_SURFACE) {
                "eglCreatePbufferSurface failed: ${eglErrorString()}"
            }

            context = createContext(display, config, 3)
            if (context == EGL14.EGL_NO_CONTEXT) {
                context = createContext(display, config, 2)
            }
            check(context != EGL14.EGL_NO_CONTEXT) {
                "eglCreateContext failed: ${eglErrorString()}"
            }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "eglMakeCurrent failed: ${eglErrorString()}"
            }

            val probe = readProbe(display)
            return LiteRtGpuCurrentContext(display, surface, context, probe)
        } catch (error: Throwable) {
            cleanup(display, surface, context)
            throw error
        }
    }

    private fun readProbe(display: EGLDisplay): LiteRtGpuProbeResult {
        val eglExtensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
        val glExtensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        val vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty()
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
        val glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        val glError = GLES20.glGetError()
        val hasDriverIdentity = vendor.isNotBlank() && renderer.isNotBlank()
        return LiteRtGpuProbeResult(
            ok = hasDriverIdentity,
            vendor = vendor,
            renderer = renderer,
            version = glVersion,
            eglVendor = EGL14.eglQueryString(display, EGL14.EGL_VENDOR).orEmpty(),
            eglVersion = EGL14.eglQueryString(display, EGL14.EGL_VERSION).orEmpty(),
            eglClientApis = EGL14.eglQueryString(display, EGL14.EGL_CLIENT_APIS).orEmpty(),
            eglExtensionsSummary = liteRtGpuExtensionSummary(
                raw = eglExtensions,
                important = LiteRtImportantEglExtensions
            ),
            glExtensionsSummary = liteRtGpuExtensionSummary(
                raw = glExtensions,
                important = LiteRtImportantGlExtensions
            ),
            glExtensionCount = splitExtensions(glExtensions).size,
            glError = glError,
            error = if (hasDriverIdentity) null else "glGetString returned empty vendor or renderer"
        )
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE,
            EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,
            EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,
            8,
            EGL14.EGL_GREEN_SIZE,
            8,
            EGL14.EGL_BLUE_SIZE,
            8,
            EGL14.EGL_ALPHA_SIZE,
            8,
            EGL14.EGL_NONE
        )
        return if (EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            configs[0]
        } else {
            null
        }
    }

    private fun createContext(
        display: EGLDisplay,
        config: EGLConfig,
        clientVersion: Int
    ) = EGL14.eglCreateContext(
        display,
        config,
        EGL14.EGL_NO_CONTEXT,
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion, EGL14.EGL_NONE),
        0
    )

    private fun cleanup(
        display: EGLDisplay,
        surface: EGLSurface,
        context: android.opengl.EGLContext
    ) {
        if (display != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
            if (surface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, surface) }
            runCatching { EGL14.eglTerminate(display) }
        }
    }

    private fun eglErrorString(): String = "0x${EGL14.eglGetError().toString(16)}"

    internal fun release(context: LiteRtGpuCurrentContext) {
        cleanup(context.display, context.surface, context.context)
    }
}

internal data class LiteRtGpuCurrentContext(
    val display: EGLDisplay,
    val surface: EGLSurface,
    val context: android.opengl.EGLContext,
    val probe: LiteRtGpuProbeResult
) : AutoCloseable {
    override fun close() {
        LiteRtGpuProbe.release(this)
    }
}

internal fun liteRtLmEngineCacheDir(
    cacheRoot: File,
    modelId: Long,
    backendLabel: String,
    mtpEnabled: Boolean = false,
    contextTokens: Int? = null,
    cacheVersion: String = LITERT_LM_ENGINE_CACHE_VERSION
): File {
    return liteRtLmEngineCachePath(
        cacheRoot = cacheRoot,
        modelId = modelId,
        backendLabel = backendLabel,
        mtpEnabled = mtpEnabled,
        contextTokens = contextTokens,
        cacheVersion = cacheVersion
    ).apply { mkdirs() }
}

internal fun purgeLiteRtLmEngineCacheDir(
    cacheRoot: File,
    modelId: Long,
    backendLabel: String,
    mtpEnabled: Boolean = false,
    contextTokens: Int? = null,
    cacheVersion: String = LITERT_LM_ENGINE_CACHE_VERSION
): Boolean {
    val cacheDir = liteRtLmEngineCachePath(
        cacheRoot = cacheRoot,
        modelId = modelId,
        backendLabel = backendLabel,
        mtpEnabled = mtpEnabled,
        contextTokens = contextTokens,
        cacheVersion = cacheVersion
    )
    return !cacheDir.exists() || cacheDir.deleteRecursively()
}

private fun liteRtLmEngineCachePath(
    cacheRoot: File,
    modelId: Long,
    backendLabel: String,
    mtpEnabled: Boolean,
    contextTokens: Int?,
    cacheVersion: String
): File {
    val modeLabel = if (mtpEnabled) "${backendLabel}_MTP" else backendLabel
    val contextLabel = "ctx_${contextTokens?.coerceAtLeast(1) ?: "default"}"
    return File(cacheRoot, "litert_lm/$modelId/$modeLabel/$cacheVersion/$contextLabel")
}

internal const val LITERT_LM_ENGINE_CACHE_VERSION = "v2"

internal fun liteRtGpuExtensionSummary(
    raw: String,
    important: List<String> = emptyList()
): String {
    val extensions = splitExtensions(raw)
    if (extensions.isEmpty()) return "count=0"
    val extensionSet = extensions.toSet()
    val present = important.filter { it in extensionSet }
    val missing = important.filterNot { it in extensionSet }
    val sample = extensions.take(24).joinToString(",")
    return buildString {
        append("count=${extensions.size}")
        if (present.isNotEmpty()) append(" present=${present.joinToString(",")}")
        if (missing.isNotEmpty()) append(" missing=${missing.joinToString(",")}")
        append(" sample=${sample.truncateDiagnostic(420)}")
    }
}

private fun deviceDiagnosticLines(context: Context): List<String> = buildList {
    add(
        "device sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} " +
            "incremental=${Build.VERSION.INCREMENTAL.truncateDiagnostic(80)}"
    )
    add(
        "device manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL} " +
            "device=${Build.DEVICE} product=${Build.PRODUCT}"
    )
    add("device board=${Build.BOARD} hardware=${Build.HARDWARE} bootloader=${Build.BOOTLOADER}")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add("device socManufacturer=${Build.SOC_MANUFACTURER} socModel=${Build.SOC_MODEL}")
    }
    add("device abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
    add("device fingerprint=${Build.FINGERPRINT.truncateDiagnostic(220)}")
    add(packageDiagnosticLine(context))
}

private fun processDiagnosticLines(context: Context): List<String> = buildList {
    val appInfo = context.applicationInfo
    val processName = currentProcessName(appInfo.processName)
    val is64Bit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Process.is64Bit().toString()
    } else {
        "unknown"
    }
    add(
        "process pid=${Process.myPid()} uid=${Process.myUid()} name=$processName " +
            "is64Bit=$is64Bit thread=${Thread.currentThread().name}"
    )
    add("process appProcessName=${appInfo.processName} package=${context.packageName}")
    add(
        "paths dataDir=${appInfo.dataDir} nativeLibraryDir=${appInfo.nativeLibraryDir} " +
            "sourceDir=${appInfo.sourceDir.truncateDiagnostic(220)}"
    )
    val splitSourceDirs = appInfo.splitSourceDirs?.joinToString(",").orEmpty()
    if (splitSourceDirs.isNotBlank()) {
        add("paths splitSourceDirs=${splitSourceDirs.truncateDiagnostic(500)}")
    }
    add(
        "paths filesDir=${context.filesDir.absolutePath} cacheDir=${context.cacheDir.absolutePath} " +
            "noBackup=${context.noBackupFilesDir.absolutePath}"
    )
    add("env java.library.path=${System.getProperty("java.library.path").orEmpty().truncateDiagnostic(500)}")
}

private fun memoryDiagnosticLines(context: Context): List<String> = buildList {
    val runtime = Runtime.getRuntime()
    add(
        "memory runtimeFree=${runtime.freeMemory()} runtimeTotal=${runtime.totalMemory()} " +
            "runtimeMax=${runtime.maxMemory()}"
    )
    add(
        "memory nativeHeapAllocated=${Debug.getNativeHeapAllocatedSize()} " +
            "nativeHeapFree=${Debug.getNativeHeapFreeSize()} nativeHeapSize=${Debug.getNativeHeapSize()}"
    )
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val info = ActivityManager.MemoryInfo()
    if (activityManager != null) {
        runCatching { activityManager.getMemoryInfo(info) }
            .onSuccess {
                add(
                    "memory systemAvail=${info.availMem} systemTotal=${info.totalMem} " +
                        "threshold=${info.threshold} lowMemory=${info.lowMemory}"
                )
            }
            .onFailure { add("memory systemInfoError=${it.diagnosticMessage()}") }
    }
}

private fun nativeLibraryDiagnosticLines(context: Context): List<String> = buildList {
    val nativeDir = File(context.applicationInfo.nativeLibraryDir.orEmpty())
    add("nativeLibraryDir ${describeFile(nativeDir)}")
    LiteRtExpectedNativeLibraries.forEach { library ->
        add("nativeLib $library ${describeFile(File(nativeDir, library))}")
    }
    val interesting = runCatching {
        nativeDir.listFiles()
            ?.filter { file ->
                val lower = file.name.lowercase(Locale.US)
                lower.contains("litert") ||
                    lower.contains("opencl") ||
                    lower.contains("vndk") ||
                    lower.contains("cdsprpc") ||
                    lower.contains("qnn") ||
                    lower.contains("gpu")
            }
            ?.sortedBy { it.name }
            .orEmpty()
    }.getOrDefault(emptyList())
    add(
        "nativeLib inventory count=${interesting.size} entries=" +
            interesting.take(48).joinToString(",") { "${it.name}:${runCatching { it.length() }.getOrDefault(0L)}" }
                .truncateDiagnostic(900)
    )
}

private fun openClDiagnosticLines(): List<String> = buildList {
    LiteRtOpenClCandidatePaths.forEach { path ->
        add("openclCandidate ${describeFile(File(path))}")
    }
}

private fun modelDiagnosticLines(sourceModelPath: File, stagedModelPath: File): List<String> = buildList {
    add("model source ${describeFile(sourceModelPath)}")
    add("model sourceCanonical=${canonicalPath(sourceModelPath).truncateDiagnostic(500)}")
    add("model sourceParent ${describeFile(sourceModelPath.parentFile)}")
    add("model staged ${describeFile(stagedModelPath)}")
    add("model stagedCanonical=${canonicalPath(stagedModelPath).truncateDiagnostic(500)}")
    add("model stagedParent ${describeFile(stagedModelPath.parentFile)}")
    add(
        "model paths sameAbsolute=${sourceModelPath.absoluteFile == stagedModelPath.absoluteFile} " +
            "sameCanonical=${canonicalPath(sourceModelPath) == canonicalPath(stagedModelPath)}"
    )
    if (stagedModelPath.isDirectory) {
        add("model stagedDirectory ${directorySummary(stagedModelPath)}")
    } else {
        add("model stagedDigest ${fileEdgeSha256(stagedModelPath)}")
    }
}

private fun cacheDiagnosticLines(cacheDir: File): List<String> = buildList {
    runCatching { cacheDir.mkdirs() }
    add("cache ${describeFile(cacheDir)}")
    add(
        "cache space usable=${runCatching { cacheDir.usableSpace }.getOrDefault(0L)} " +
            "free=${runCatching { cacheDir.freeSpace }.getOrDefault(0L)} " +
            "total=${runCatching { cacheDir.totalSpace }.getOrDefault(0L)}"
    )
    add("cache contents ${directorySummary(cacheDir)}")
    add("cache writeProbe=${cacheWriteProbe(cacheDir)}")
}

private fun packageDiagnosticLine(context: Context): String {
    return runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val versionCode = AppVersionCodeCompat.read(info)
        "package name=${context.packageName} versionName=${info.versionName ?: "-"} versionCode=$versionCode"
    }.getOrElse { "package name=${context.packageName} versionInfoError=${it.diagnosticMessage()}" }
}

private fun currentProcessName(fallback: String?): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching { Application.getProcessName() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    return fallback.orEmpty().ifBlank { "unknown" }
}

private fun describeFile(file: File?): String {
    if (file == null) return "path=- exists=false"
    return buildString {
        append("path=${file.absolutePath.truncateDiagnostic(420)}")
        append(" exists=${runCatching { file.exists() }.getOrDefault(false)}")
        append(" isFile=${runCatching { file.isFile }.getOrDefault(false)}")
        append(" isDirectory=${runCatching { file.isDirectory }.getOrDefault(false)}")
        append(" canRead=${runCatching { file.canRead() }.getOrDefault(false)}")
        append(" canWrite=${runCatching { file.canWrite() }.getOrDefault(false)}")
        append(" canExecute=${runCatching { file.canExecute() }.getOrDefault(false)}")
        append(" length=${runCatching { if (file.isFile) file.length() else fileOrDirectorySize(file) }.getOrDefault(0L)}")
        append(" lastModified=${runCatching { file.lastModified() }.getOrDefault(0L)}")
    }
}

private fun directorySummary(root: File): String {
    if (!root.exists()) return "exists=false"
    if (!root.isDirectory) return "notDirectory=true"
    return runCatching {
        var fileCount = 0
        var directoryCount = 0
        var totalBytes = 0L
        val samples = mutableListOf<String>()
        root.walkTopDown().forEach { file ->
            if (file == root) return@forEach
            if (file.isDirectory) {
                directoryCount += 1
            } else if (file.isFile) {
                fileCount += 1
                totalBytes += file.length()
                if (samples.size < 18) samples += "${file.relativeTo(root).path}:${file.length()}"
            }
        }
        "files=$fileCount dirs=$directoryCount totalBytes=$totalBytes samples=${samples.joinToString(",").truncateDiagnostic(700)}"
    }.getOrElse { "summaryError=${it.diagnosticMessage()}" }
}

private fun cacheWriteProbe(cacheDir: File): String {
    return runCatching {
        if (!cacheDir.isDirectory) return@runCatching "failed:notDirectory"
        val probe = File.createTempFile("litert_gpu_cache_", ".tmp", cacheDir)
        try {
            probe.writeText("ok")
            "ok path=${probe.absolutePath.truncateDiagnostic(240)}"
        } finally {
            runCatching { probe.delete() }
        }
    }.getOrElse { "failed:${it.diagnosticMessage()}" }
}

private fun fileOrDirectorySize(root: File): Long {
    if (!root.exists()) return 0L
    if (root.isFile) return root.length()
    return runCatching {
        root.walkTopDown()
            .filter { it.isFile }
            .sumOf { runCatching { it.length() }.getOrDefault(0L) }
    }.getOrDefault(0L)
}

private fun fileEdgeSha256(file: File): String {
    if (!file.isFile) return "notFile=true"
    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val firstWindow = minOf(LiteRtDigestWindowBytes, size)
            var hashedBytes = 0L
            fun updateWindow(start: Long, length: Long) {
                if (length <= 0L) return
                raf.seek(start)
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0L) {
                    val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    remaining -= read.toLong()
                    hashedBytes += read.toLong()
                }
            }
            updateWindow(0L, firstWindow)
            val remainingAfterFirst = size - firstWindow
            val lastWindow = minOf(LiteRtDigestWindowBytes, remainingAfterFirst)
            updateWindow(size - lastWindow, lastWindow)
            "sha256Edge=${digest.digest().toHex()} bytesHashed=$hashedBytes fileBytes=$size"
        }
    }.getOrElse { "digestError=${it.diagnosticMessage()}" }
}

private fun canonicalPath(file: File): String =
    runCatching { file.canonicalPath }.getOrElse { "canonicalError=${it.diagnosticMessage()}" }

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> String.format(Locale.US, "%02x", byte.toInt() and 0xff) }

private fun Throwable.diagnosticMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.name

private fun String.truncateDiagnostic(maxChars: Int): String {
    if (length <= maxChars) return replace('\n', ' ')
    val head = (maxChars / 2).coerceAtLeast(1)
    val tail = (maxChars - head - 3).coerceAtLeast(1)
    return "${take(head)}...${takeLast(tail)}".replace('\n', ' ')
}

private fun splitExtensions(raw: String): List<String> =
    raw.split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

private const val LiteRtDigestWindowBytes = 1024L * 1024L

private val LiteRtImportantEglExtensions = listOf(
    "EGL_ANDROID_blob_cache",
    "EGL_ANDROID_native_fence_sync",
    "EGL_EXT_create_context_robustness",
    "EGL_KHR_create_context",
    "EGL_KHR_fence_sync",
    "EGL_KHR_image_base",
    "EGL_KHR_no_config_context",
    "EGL_KHR_surfaceless_context"
)

private val LiteRtImportantGlExtensions = listOf(
    "GL_ANDROID_extension_pack_es31a",
    "GL_EXT_buffer_storage",
    "GL_EXT_color_buffer_float",
    "GL_EXT_disjoint_timer_query",
    "GL_EXT_shader_io_blocks",
    "GL_EXT_texture_buffer",
    "GL_KHR_debug",
    "GL_OES_EGL_image",
    "GL_OES_EGL_sync"
)

private val LiteRtExpectedNativeLibraries = listOf(
    "libLiteRt.so",
    "libLiteRtClGlAccelerator.so",
    "liblitertlm_jni.so",
    "libAIDOCL.so",
    "libOpenCL.so",
    "libOpenCL-pixel.so",
    "libOpenCL-car.so",
    "libvndksupport.so",
    "libcdsprpc.so"
)

private val LiteRtOpenClCandidatePaths = listOf(
    "/vendor/lib64/libOpenCL.so",
    "/system/vendor/lib64/libOpenCL.so",
    "/odm/lib64/libOpenCL.so",
    "/product/lib64/libOpenCL.so",
    "/system_ext/lib64/libOpenCL.so",
    "/system/lib64/libOpenCL.so",
    "/vendor/lib/libOpenCL.so",
    "/system/vendor/lib/libOpenCL.so",
    "/odm/lib/libOpenCL.so",
    "/product/lib/libOpenCL.so",
    "/system_ext/lib/libOpenCL.so",
    "/system/lib/libOpenCL.so"
)
