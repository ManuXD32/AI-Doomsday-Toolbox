package com.example.llamadroid.service

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtGpuDiagnosticsTest {
    @Test
    fun `GPU cache dir is explicit app cache path`() {
        val cacheRoot = createTempDirectory("litert-cache").toFile()

        val cacheDir = liteRtLmEngineCacheDir(cacheRoot, modelId = 42L, backendLabel = "GPU")

        assertEquals(File(cacheRoot, "litert_lm/42/GPU/v2/ctx_default").absolutePath, cacheDir.absolutePath)
        assertTrue(cacheDir.isDirectory)
    }

    @Test
    fun `CPU cache dir keeps existing app cache shape`() {
        val cacheRoot = createTempDirectory("litert-cache").toFile()

        val cacheDir = liteRtLmEngineCacheDir(cacheRoot, modelId = 42L, backendLabel = "CPU")

        assertEquals(File(cacheRoot, "litert_lm/42/CPU/v2/ctx_default").absolutePath, cacheDir.absolutePath)
        assertTrue(cacheDir.isDirectory)
    }

    @Test
    fun `MTP cache dir is isolated from normal backend cache`() {
        val cacheRoot = createTempDirectory("litert-cache").toFile()

        val cacheDir = liteRtLmEngineCacheDir(
            cacheRoot,
            modelId = 42L,
            backendLabel = "GPU",
            mtpEnabled = true
        )

        assertEquals(File(cacheRoot, "litert_lm/42/GPU_MTP/v2/ctx_default").absolutePath, cacheDir.absolutePath)
        assertTrue(cacheDir.isDirectory)
    }

    @Test
    fun `GPU cache dir is isolated by context and can be purged`() {
        val cacheRoot = createTempDirectory("litert-cache").toFile()
        val cacheDir = liteRtLmEngineCacheDir(
            cacheRoot,
            modelId = 42L,
            backendLabel = "GPU",
            mtpEnabled = false,
            contextTokens = 16000
        )
        File(cacheDir, "compiled.bin").writeText("cached")

        assertEquals(File(cacheRoot, "litert_lm/42/GPU/v2/ctx_16000").absolutePath, cacheDir.absolutePath)
        assertTrue(cacheDir.exists())
        assertTrue(
            purgeLiteRtLmEngineCacheDir(
                cacheRoot,
                modelId = 42L,
                backendLabel = "GPU",
                mtpEnabled = false,
                contextTokens = 16000
            )
        )
        assertTrue(!cacheDir.exists())
    }

    @Test
    fun `GPU diagnostics format fake probe without touching EGL`() {
        val root = createTempDirectory("litert-diagnostics").toFile()
        val model = File(root, "model.litertlm").apply { writeText("fake-model") }
        val cache = File(root, "cache")
        val diagnostics = LiteRtGpuStartupDiagnostics.collect(
            modelPath = model,
            cacheDir = cache,
            probe = LiteRtGpuProbeResult(
                ok = true,
                vendor = "Qualcomm",
                renderer = "Adreno 750",
                version = "OpenGL ES 3.2"
            )
        )

        val lines = diagnostics.toLogLines()

        assertTrue(lines.any { it.contains("GPU probe ok=true") })
        assertTrue(lines.any { it.contains("vendor=Qualcomm") })
        assertTrue(lines.any { it.contains("renderer=Adreno 750") })
        assertTrue(lines.any { it.contains("GPU cache dir=${cache.absolutePath}") })
        assertTrue(lines.any { it.contains("sizeBytes=10") })
    }

    @Test
    fun `GPU extension summary reports key present and missing extensions`() {
        val summary = liteRtGpuExtensionSummary(
            raw = "EGL_KHR_create_context EGL_ANDROID_blob_cache EGL_EXT_other",
            important = listOf(
                "EGL_KHR_create_context",
                "EGL_ANDROID_native_fence_sync"
            )
        )

        assertTrue(summary.contains("count=3"))
        assertTrue(summary.contains("present=EGL_KHR_create_context"))
        assertTrue(summary.contains("missing=EGL_ANDROID_native_fence_sync"))
    }
}
