package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.buildSdCapabilities
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.service.SdBinaryCapabilities
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdWorkflowGateTest {
    @Test
    fun `selected workflow is blocked when the bound mode or model changes`() {
        val payload = temporaryPayload("checkpoint")
        try {
            val file = checkpointFile(payload)
            val preset = preset(SdWorkflowOperation.PRECISION_INPAINTING, file)
            val model = modelFor(file, preset, payload)

            val matching = evaluateSdWorkflowGate(
                preset = preset,
                installedModels = listOf(model),
                binaryCapabilities = SdBinaryCapabilities(
                    supportedFlags = emptySet(),
                    supportedModes = setOf("img_gen")
                ),
                selection = SdWorkflowSelection(
                    mode = "inpaint",
                    modelPath = payload.absolutePath
                )
            )
            assertFalse(matching.issues.any { it.code == SdWorkflowGateIssue.Code.CONFIGURATION_MISMATCH })

            val changed = evaluateSdWorkflowGate(
                preset = preset,
                installedModels = listOf(model),
                binaryCapabilities = SdBinaryCapabilities(
                    supportedFlags = emptySet(),
                    supportedModes = setOf("img_gen")
                ),
                selection = SdWorkflowSelection(
                    mode = "txt2img",
                    modelPath = "/different/model.gguf"
                )
            )
            assertTrue(changed.issues.any { it.code == SdWorkflowGateIssue.Code.CONFIGURATION_MISMATCH })
        } finally {
            payload.delete()
        }
    }

    @Test
    fun `object workflow requires largest-only settings in the selected config`() {
        val payload = temporaryPayload("checkpoint")
        try {
            val file = checkpointFile(payload)
            val preset = preset(SdWorkflowOperation.OBJECT_EDIT, file).copy(
                objectLargestOnly = true,
                requiredAdvancedArgs = "mask_k_largest=1"
            )
            val model = modelFor(file, preset, payload)
            val result = evaluateSdWorkflowGate(
                preset = preset,
                installedModels = listOf(model),
                selection = SdWorkflowSelection(
                    mode = "adetailer",
                    modelPath = payload.absolutePath,
                    maxDetections = 8,
                    advancedArgs = ""
                )
            )
            assertTrue(result.issues.any { it.code == SdWorkflowGateIssue.Code.CONFIGURATION_MISMATCH })
        } finally {
            payload.delete()
        }
    }

    @Test
    fun `hash verification reads the cache without hashing on the gate call`() {
        val payload = temporaryPayload("checkpoint")
        try {
            val file = checkpointFile(payload)
            val preset = preset(SdWorkflowOperation.PRECISION_INPAINTING, file)
            val model = modelFor(file, preset, payload)
            val before = evaluateSdWorkflowGate(
                preset = preset,
                installedModels = listOf(model),
                verifyHashes = true
            )
            assertTrue(before.issues.any { it.code == SdWorkflowGateIssue.Code.HASH_NOT_VERIFIED })

            verifySdCuratedFilePayloadCached(file, payload)
            val after = evaluateSdWorkflowGate(
                preset = preset,
                installedModels = listOf(model),
                verifyHashes = true
            )
            assertFalse(after.issues.any { it.code == SdWorkflowGateIssue.Code.HASH_NOT_VERIFIED })
        } finally {
            payload.delete()
        }
    }

    @Test
    fun `gate reports unavailable native mode from the probed binary`() {
        val payload = temporaryPayload("checkpoint")
        try {
            val file = checkpointFile(payload)
            val preset = preset(SdWorkflowOperation.PRECISION_INPAINTING, file)
            val result = evaluateSdWorkflowGate(
                preset = preset,
                installedModels = listOf(modelFor(file, preset, payload)),
                binaryCapabilities = SdBinaryCapabilities(emptySet(), supportedModes = setOf("adetailer"))
            )
            assertTrue(result.issues.any { it.code == SdWorkflowGateIssue.Code.BINARY_MODE_UNAVAILABLE })
        } finally {
            payload.delete()
        }
    }

    private fun preset(operation: SdWorkflowOperation, file: SdCuratedBundleFile): SdWorkflowPreset =
        SdWorkflowPreset(
            operation = operation,
            bundle = SdCuratedBundle(
                id = "test-${operation.name.lowercase()}",
                titleRes = com.example.llamadroid.R.string.sd_workflow_preset_label,
                descriptionRes = com.example.llamadroid.R.string.sd_workflow_preset_help,
                installPrefix = "Test",
                files = listOf(file)
            ),
            smokePrompt = "test",
            smokeNegativePrompt = ""
        )

    private fun checkpointFile(payload: File): SdCuratedBundleFile =
        SdCuratedBundleFile(
            id = "test-checkpoint",
            repoId = "test/repo",
            revision = "0123456789abcdef0123456789abcdef01234567",
            remotePath = "checkpoint.gguf",
            modelType = ModelType.SD_CHECKPOINT,
            sizeBytes = payload.length(),
            sha256 = sha256(payload),
            licenseLabel = "Test",
            sizeIsApproximate = true,
            sdCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG, SD_CAPABILITY_IMG2IMG),
            sdFamily = "checkpoint",
            sdVariant = "sd1"
        )

    private fun modelFor(
        file: SdCuratedBundleFile,
        preset: SdWorkflowPreset,
        payload: File
    ): ModelEntity = ModelEntity(
        filename = file.localFilename(preset.bundle.installPrefix),
        path = payload.absolutePath,
        sizeBytes = payload.length(),
        type = file.modelType,
        repoId = file.repoId,
        isDownloaded = true,
        sdCapabilities = file.sdCapabilities,
        sdFamily = file.sdFamily,
        sdVariant = file.sdVariant
    )

    private fun temporaryPayload(text: String): File =
        File.createTempFile("sd-workflow-gate", ".gguf").apply { writeText(text) }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte) }
}
