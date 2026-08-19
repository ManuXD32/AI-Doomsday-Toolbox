package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.hasSdCapability
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.service.SdBinaryCapabilities
import com.example.llamadroid.service.smokeCheckSdADetailerDetector
import com.example.llamadroid.sd.SdModelFamily
import com.example.llamadroid.sd.effectiveSdCompatProfiles
import com.example.llamadroid.sd.resolvedSdFamily
import java.io.File

/** A launch blocker surfaced to UI/API instead of silently selecting a fallback model. */
data class SdWorkflowGateIssue(
    val code: Code,
    val detail: String
) {
    enum class Code {
        MISSING_FILE,
        UNREADABLE_FILE,
        SIZE_MISMATCH,
        HASH_NOT_VERIFIED,
        WRONG_MODEL_TYPE,
        WRONG_FAMILY,
        MISSING_CAPABILITY,
        DETECTOR_INCOMPATIBLE,
        BINARY_MODE_UNAVAILABLE,
        LARGEST_ONLY_NOT_CONFIGURED,
        CONFIGURATION_MISMATCH
    }
}

/**
 * The small set of UI choices that define whether a selected workflow preset is
 * still attached to the current image-generation configuration.  Prompt text and
 * normal sampling tweaks intentionally do not belong here; changing a pinned model,
 * detector, VAE, operation mode, or object-selection rule does.
 */
data class SdWorkflowSelection(
    val mode: String,
    val modelPath: String?,
    val detectorPath: String? = null,
    val vaePath: String? = null,
    val maxDetections: Int? = null,
    val advancedArgs: String? = null
)

data class SdWorkflowGateResult(
    val preset: SdWorkflowPreset,
    val issues: List<SdWorkflowGateIssue>
) {
    val ready: Boolean get() = issues.isEmpty()
}

/**
 * Validate that every pinned asset is present and compatible before a workflow is
 * launched.  Callers can set [verifyHashes] for an explicit checksum smoke test;
 * normal launch preflight still rejects missing/changed sizes and never falls
 * back to a different family.
 */
fun evaluateSdWorkflowGate(
    preset: SdWorkflowPreset,
    installedModels: List<ModelEntity>,
    binaryCapabilities: SdBinaryCapabilities? = null,
    verifyHashes: Boolean = false,
    selection: SdWorkflowSelection? = null
): SdWorkflowGateResult {
    val issues = mutableListOf<SdWorkflowGateIssue>()
    val resolved = preset.files.map { file ->
        file to file.installedSdCuratedModel(preset.bundle, installedModels)
    }
    resolved.forEach { (file, model) ->
        if (model == null) {
            issues += SdWorkflowGateIssue(SdWorkflowGateIssue.Code.MISSING_FILE, file.sourceFilename)
            return@forEach
        }
        if (model.type != file.modelType) {
            issues += SdWorkflowGateIssue(SdWorkflowGateIssue.Code.WRONG_MODEL_TYPE, "${file.sourceFilename}:${model.type}")
            return@forEach
        }
        val payload = File(model.path)
        if (!payload.isFile || !payload.canRead()) {
            issues += SdWorkflowGateIssue(SdWorkflowGateIssue.Code.UNREADABLE_FILE, model.path)
            return@forEach
        }
        if (!file.sizeIsApproximate && payload.length() != file.sizeBytes) {
            issues += SdWorkflowGateIssue(SdWorkflowGateIssue.Code.SIZE_MISMATCH, file.sourceFilename)
        }
        if (verifyHashes && !isSdCuratedFilePayloadHashVerified(file, payload)) {
            issues += SdWorkflowGateIssue(SdWorkflowGateIssue.Code.HASH_NOT_VERIFIED, file.sourceFilename)
        }
    }

    val baseEntry = resolved.firstOrNull { it.first.modelType == ModelType.SD_CHECKPOINT || it.first.modelType == ModelType.SD_DIFFUSION }
    val base = baseEntry?.second
    if (base != null) {
        val (family, variant) = base.resolvedSdFamily()
        if (family != SdModelFamily.CHECKPOINT || variant.isNullOrBlank()) {
            issues += SdWorkflowGateIssue(SdWorkflowGateIssue.Code.WRONG_FAMILY, "${family?.storedValue.orEmpty()}:${variant.orEmpty()}")
        }
        val requiredCapability = if (preset.operation == SdWorkflowOperation.PRECISION_INPAINTING) {
            SD_CAPABILITY_IMG2IMG
        } else {
            SD_CAPABILITY_TXT2IMG
        }
        if (!base.hasSdCapability(requiredCapability)) {
            issues += SdWorkflowGateIssue(SdWorkflowGateIssue.Code.MISSING_CAPABILITY, requiredCapability)
        }
    }

    preset.files.filter { it.modelType == ModelType.SD_ADETAILER }.forEach { detector ->
        val model = resolved.firstOrNull { it.first == detector }?.second ?: return@forEach
        val (rawFamily, variant) = base?.resolvedSdFamily() ?: return@forEach
        val family = rawFamily ?: return@forEach
        val token = "${family.storedValue}:${variant.orEmpty()}"
        if (detector.sdCompatProfiles.orEmpty().split(',').map { it.trim() }.none { it == token || it == family.storedValue }) {
            issues += SdWorkflowGateIssue(SdWorkflowGateIssue.Code.DETECTOR_INCOMPATIBLE, detector.sourceFilename)
        }
        if (model.effectiveSdCompatProfiles().isEmpty()) {
            issues += SdWorkflowGateIssue(SdWorkflowGateIssue.Code.DETECTOR_INCOMPATIBLE, model.filename)
        }
        if (!smokeCheckSdADetailerDetector(File(model.path))) {
            issues += SdWorkflowGateIssue(SdWorkflowGateIssue.Code.DETECTOR_INCOMPATIBLE, "smoke:${model.filename}")
        }
    }

    if (preset.objectLargestOnly && preset.requiredAdvancedArgs != "mask_k_largest=1") {
        issues += SdWorkflowGateIssue(SdWorkflowGateIssue.Code.LARGEST_ONLY_NOT_CONFIGURED, preset.id)
    }

    selection?.let { current ->
        val expectedMode = if (preset.operation == SdWorkflowOperation.PRECISION_INPAINTING) {
            "inpaint"
        } else {
            "adetailer"
        }
        val expectedBasePath = base?.path
        val expectedDetectorPath = resolved
            .firstOrNull { it.first.modelType == ModelType.SD_ADETAILER }
            ?.second
            ?.path
        val expectedVaePath = resolved
            .firstOrNull { it.first.modelType == ModelType.SD_VAE }
            ?.second
            ?.path
        val modelMatches = expectedBasePath == null || current.modelPath == expectedBasePath
        val detectorMatches = expectedDetectorPath == null || current.detectorPath == expectedDetectorPath
        val vaeMatches = expectedVaePath == null || current.vaePath == expectedVaePath
        val modeMatches = current.mode.trim().lowercase() == expectedMode
        val isAdetailerWorkflow = preset.operation != SdWorkflowOperation.PRECISION_INPAINTING
        val maxDetectionsMatches = !isAdetailerWorkflow ||
            current.maxDetections == preset.defaultMaxDetections
        val advancedMatches = !isAdetailerWorkflow ||
            normalizeAdvancedArgs(current.advancedArgs) == normalizeAdvancedArgs(preset.requiredAdvancedArgs)
        if (!modeMatches || !modelMatches || !detectorMatches || !vaeMatches ||
            !maxDetectionsMatches || !advancedMatches
        ) {
            issues += SdWorkflowGateIssue(
                SdWorkflowGateIssue.Code.CONFIGURATION_MISMATCH,
                preset.id
            )
        }
    }

    val requiredMode = when (preset.operation) {
        SdWorkflowOperation.PRECISION_INPAINTING -> "img_gen"
        else -> "adetailer"
    }
    if (binaryCapabilities != null && !binaryCapabilities.supportsMode(requiredMode)) {
        issues += SdWorkflowGateIssue(SdWorkflowGateIssue.Code.BINARY_MODE_UNAVAILABLE, requiredMode)
    }
    return SdWorkflowGateResult(preset, issues)
}

private fun normalizeAdvancedArgs(value: String?): String =
    value.orEmpty().split(Regex("\\s+"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" ")
