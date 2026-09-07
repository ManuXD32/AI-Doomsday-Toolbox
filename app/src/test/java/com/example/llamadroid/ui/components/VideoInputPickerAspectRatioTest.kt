package com.example.llamadroid.ui.components

import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowStatFs

/** Verifies that a real non-square source is staged byte-for-byte with its original bounds. */
@RunWith(RobolectricTestRunner::class)
class VideoInputPickerAspectRatioTest {
    @Test
    fun `image staging preserves non square dimensions and source bytes`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val stagingRoot = File(context.filesDir, "video_inputs").apply { mkdirs() }
        // StatFs is not populated for the app-private test volume unless the test registers it.
        // Keep the production 32 MiB reserve check active instead of weakening production code.
        ShadowStatFs.registerStats(stagingRoot, 32768, 16384, 16384)
        val source = File(context.cacheDir, "video-picker-source-${System.nanoTime()}.png")
        var staged: File? = null
        try {
            source.writeBytes(Base64.decode(NON_SQUARE_PNG_BASE64, Base64.DEFAULT))

            val imported = importVideoImage(context, Uri.fromFile(source))
            val stagedFile = imported.file
            staged = stagedFile

            assertEquals(3, imported.width)
            assertEquals(2, imported.height)
            assertTrue(stagedFile.isFile)
            assertArrayEquals(source.readBytes(), stagedFile.readBytes())
        } finally {
            staged?.delete()
            source.delete()
            ShadowStatFs.unregisterStats(stagingRoot)
        }
    }

    @Test
    fun `image staging rejects a known low space staging volume`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val stagingRoot = File(context.filesDir, "video_inputs").apply { mkdirs() }
        val source = File(context.cacheDir, "video-picker-low-space-${System.nanoTime()}.png")
        val existingPartFiles = stagingRoot.listFiles().orEmpty()
            .filter { it.name.endsWith(".part") }
            .map { it.name }
        ShadowStatFs.registerStats(stagingRoot, 32768, 4096, 4096)
        try {
            source.writeBytes(Base64.decode(NON_SQUARE_PNG_BASE64, Base64.DEFAULT))

            val error = runCatching {
                importVideoImage(context, Uri.fromFile(source))
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals(
                existingPartFiles,
                stagingRoot.listFiles().orEmpty()
                    .filter { it.name.endsWith(".part") }
                    .map { it.name }
            )
        } finally {
            source.delete()
            ShadowStatFs.unregisterStats(stagingRoot)
        }
    }

    private companion object {
        // A tiny 3x2 RGBA PNG generated for this test; no production or fixture image is used.
        const val NON_SQUARE_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAGElEQVR4nGP4z8Dwn6Hh/38wDWc0/P8PAKaEDXVmyKy4AAAAAElFTkSuQmCC"
    }
}
