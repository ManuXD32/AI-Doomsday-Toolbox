package com.example.llamadroid.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBackupPolicyTest {
    @Test
    fun `local imported model rows are skipped`() {
        val model = model(
            type = ModelType.LLM,
            repoId = "local-import",
            isDownloaded = false
        )

        assertFalse(ModelBackupPolicy.shouldKeepInPortableBackup(model))
    }

    @Test
    fun `downloaded hugging face model rows are skipped`() {
        val model = model(
            type = ModelType.LLM,
            repoId = "owner/repo",
            isDownloaded = true
        )

        assertFalse(ModelBackupPolicy.shouldKeepInPortableBackup(model))
    }

    @Test
    fun `downloaded rows with local import marker are skipped`() {
        val model = model(
            type = ModelType.LLM,
            repoId = "local-import",
            isDownloaded = true
        )

        assertFalse(ModelBackupPolicy.shouldKeepInPortableBackup(model))
        assertTrue(ModelBackupPolicy.IMPORTED_MODEL_SQL_PREDICATE.contains("1 = 0"))
    }

    @Test
    fun `downloaded and imported whisper model rows are skipped`() {
        val downloaded = model(
            type = ModelType.WHISPER,
            repoId = "ggerganov/whisper.cpp",
            isDownloaded = true
        )
        val imported = model(
            type = ModelType.WHISPER,
            repoId = "local-import",
            isDownloaded = false
        )

        assertFalse(ModelBackupPolicy.shouldKeepInPortableBackup(downloaded))
        assertFalse(ModelBackupPolicy.shouldKeepInPortableBackup(imported))
    }

    @Test
    fun `catalog and custom ONNX model rows are skipped`() {
        val catalog = model(
            type = ModelType.ONNX_IMAGE_GEN,
            repoId = "ShiftHackZ/Local-Diffusion-Models-SDAI-ONXX",
            isDownloaded = true,
            onnxAssetKind = "sdai_catalog_bundle"
        )
        val custom = model(
            type = ModelType.ONNX_IMAGE_GEN,
            repoId = "custom-import/my-bundle",
            isDownloaded = true,
            onnxAssetKind = "custom_import_bundle"
        )

        assertFalse(ModelBackupPolicy.shouldKeepInPortableBackup(catalog))
        assertFalse(ModelBackupPolicy.shouldKeepInPortableBackup(custom))
    }

    @Test
    fun `sql predicate excludes every model row`() {
        assertTrue(ModelBackupPolicy.IMPORTED_MODEL_SQL_PREDICATE.contains("1 = 0"))
    }

    private fun model(
        type: ModelType,
        repoId: String,
        isDownloaded: Boolean,
        onnxAssetKind: String? = null
    ): ModelEntity = ModelEntity(
        filename = "model.bin",
        path = "/models/model.bin",
        sizeBytes = 1L,
        type = type,
        repoId = repoId,
        isDownloaded = isDownloaded,
        onnxAssetKind = onnxAssetKind
    )
}
