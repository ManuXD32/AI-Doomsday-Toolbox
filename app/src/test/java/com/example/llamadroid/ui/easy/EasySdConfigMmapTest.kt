package com.example.llamadroid.ui.easy

import androidx.test.core.app.ApplicationProvider
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.easy.EasyImageEngine
import com.example.llamadroid.data.easy.EasyImageKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EasySdConfigMmapTest {
    private val main = ModelEntity(
        filename = "sd15.gguf", path = "/tmp/sd15.gguf", sizeBytes = 1L,
        type = ModelType.SD_CHECKPOINT, repoId = "x", sdFamily = "checkpoint", sdVariant = "sd1"
    )
    private val selection = EasyImageSelection(
        "local:sd15.gguf", null, main, EasyImageKind.SD15_SDCPP, EasyImageEngine.SDCPP, emptyMap()
    )

    @Test
    fun checkpointFamilyUsesMmap() {
        val config = buildEasySdConfig(ApplicationProvider.getApplicationContext(), selection, "cat", 512 to 512)
        assertTrue(config.mmap)
        assertFalse(config.vaeTiling)
    }

    @Test
    fun ramGateTilingOverrideIsApplied() {
        val config = buildEasySdConfig(
            ApplicationProvider.getApplicationContext(), selection, "cat", 512 to 512, vaeTiling = true
        )
        assertTrue(config.vaeTiling)
    }
}
