package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.ModelEntity
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdWorkflowPresetCatalogTest {
    @Test
    fun `catalog exposes all approved first party workflows with pinned metadata`() {
        assertEquals(
            setOf(
                "workflow-face-fast",
                "workflow-face-quality",
                "workflow-hand-repair",
                "workflow-object-edit",
                "workflow-portrait-detail-pro",
                "workflow-precision-inpainting"
            ),
            SdWorkflowPresetCatalog.presets.map { it.id }.toSet()
        )
        SdWorkflowPresetCatalog.presets.flatMap { it.files }.forEach { file ->
            assertEquals(64, file.sha256.length)
            assertTrue(file.licenseLabel.isNotBlank())
            assertTrue(file.revision.isNotBlank())
        }
    }

    @Test
    fun `object edit explicitly configures largest COCO-only detection`() {
        val preset = SdWorkflowPresetCatalog.byId("workflow-object-edit")!!
        assertTrue(preset.objectLargestOnly)
        assertEquals("mask_k_largest=1", preset.requiredAdvancedArgs)
        val detector = preset.files.first { it.modelType == ModelType.SD_ADETAILER }
        assertEquals("ultralytics/assets", detector.repoId)
        assertEquals("v8.3.0", detector.revision)
        assertEquals("AGPL-3.0", detector.licenseLabel)
        assertEquals(1, preset.defaultMaxDetections)
    }

    @Test
    fun `pure upscale is not represented as a diffusion workflow`() {
        assertFalse(SdWorkflowPresetCatalog.presets.any { preset -> preset.files.any { it.modelType == ModelType.SD_UPSCALER } })
    }

    @Test
    fun `converted detector keeps safetensors installed filename`() {
        val detector = SdWorkflowPresetCatalog.byId("workflow-face-fast")!!.files
            .first { it.modelType == ModelType.SD_ADETAILER }
        assertTrue(detector.localFilename("Workflow-Face-Fast").endsWith("-sdcpp.safetensors"))
        assertEquals("face_yolov8n.pt", detector.sourceFilename)
    }

    @Test
    fun `face quality checkpoint keeps pinned revision payload identity`() {
        val preset = SdWorkflowPresetCatalog.byId("workflow-face-quality")!!
        val checkpoint = preset.files.single { it.modelType == ModelType.SD_CHECKPOINT }

        assertEquals("8978218f370944c135a689ff3347171195ecdeb6", checkpoint.revision)
        assertEquals(2_765_375_264L, checkpoint.sizeBytes)
        assertEquals(
            "d54425f5607a477da26890dd6dba26620d06ae9bcf9f7026f2849bc6e2725af8",
            checkpoint.sha256
        )
        assertFalse(checkpoint.sizeIsApproximate)

        val bundle = requireNotNull(SdCuratedBundleCatalog.byId("workflow-face-quality"))
        assertEquals(
            checkpoint,
            SdCuratedBundleCatalog.fileForLocalFilename(
                checkpoint.localFilename(bundle.installPrefix)
            )
        )
    }

    @Test
    fun `workflow reuses a curated checkpoint with the same pinned sha`() {
        val installedPreset = SdWorkflowPresetCatalog.byId("workflow-face-fast")!!
        val requestedPreset = SdWorkflowPresetCatalog.byId("workflow-hand-repair")!!
        val installedFile = installedPreset.files.first { it.modelType == ModelType.SD_CHECKPOINT }
        val requestedFile = requestedPreset.files.first { it.modelType == ModelType.SD_CHECKPOINT }
        val payload = File.createTempFile("sd-workflow-dedup", ".gguf")
        try {
            RandomAccessFile(payload, "rw").use { it.setLength(installedFile.sizeBytes) }
            val installedModel = ModelEntity(
                filename = installedFile.localFilename(installedPreset.bundle.installPrefix),
                path = payload.absolutePath,
                sizeBytes = payload.length(),
                type = installedFile.modelType,
                repoId = installedFile.repoId,
                isDownloaded = true
            )
            assertEquals(
                installedModel,
                requestedFile.installedSdCuratedModel(requestedPreset.bundle, listOf(installedModel))
            )
        } finally {
            payload.delete()
        }
    }
}
