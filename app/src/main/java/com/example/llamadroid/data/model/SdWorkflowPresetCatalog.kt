package com.example.llamadroid.data.model

import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.buildSdCapabilities

/** The user-facing operation a first-party diffusion workflow is allowed to launch. */
enum class SdWorkflowOperation {
    FACE_FAST,
    FACE_QUALITY,
    HAND_REPAIR,
    OBJECT_EDIT,
    PORTRAIT_DETAIL_PRO,
    PRECISION_INPAINTING
}

/** A pinned, smoke-gated workflow assembled from first-party model assets. */
data class SdWorkflowPreset(
    val operation: SdWorkflowOperation,
    val bundle: SdCuratedBundle,
    val detectorRole: String? = null,
    val objectLargestOnly: Boolean = false,
    val defaultMaxDetections: Int = 8,
    val requiredAdvancedArgs: String = "",
    val smokePrompt: String,
    val smokeNegativePrompt: String
) {
    val id: String get() = bundle.id
    val files: List<SdCuratedBundleFile> get() = bundle.files
}

private const val ADETAILER_REVISION = "53cc19de382014514d9d4038601d261a7faa9b7b"
/** Official Ultralytics assets release used for the COCO detector source. */
private const val COCO_YOLOV8N_REVISION = "v8.3.0"
private const val CONVERTED_DETECTOR_BASE =
    "https://raw.githubusercontent.com/ManuXD32/AI-Doomsday-Toolbox/Dev/model-assets/adetailer/"

private fun checkpoint(
    id: String,
    repoId: String,
    revision: String,
    remotePath: String,
    sizeBytes: Long,
    sha256: String,
    licenseLabel: String,
    variant: String = "sd1",
    capabilities: String? = buildSdCapabilities(SD_CAPABILITY_TXT2IMG, SD_CAPABILITY_IMG2IMG),
    sizeIsApproximate: Boolean = false
) = SdCuratedBundleFile(
    id = id,
    repoId = repoId,
    revision = revision,
    remotePath = remotePath,
    modelType = ModelType.SD_CHECKPOINT,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    licenseLabel = licenseLabel,
    sizeIsApproximate = sizeIsApproximate,
    sdCapabilities = capabilities,
    sdFamily = "checkpoint",
    sdVariant = variant
)

private fun detector(
    id: String,
    upstreamPath: String,
    localName: String,
    sizeBytes: Long,
    sha256: String,
    licenseLabel: String,
    compatProfiles: String,
    revision: String = ADETAILER_REVISION,
    repoId: String = "Bingsu/adetailer"
) = SdCuratedBundleFile(
    id = id,
    repoId = repoId,
    revision = revision,
    remotePath = upstreamPath,
    modelType = ModelType.SD_ADETAILER,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    licenseLabel = licenseLabel,
    sdCompatProfiles = compatProfiles,
    localFilenameOverride = localName,
    downloadUrlOverride = CONVERTED_DETECTOR_BASE + localName
)

private val SD15_Q4_FACE = checkpoint(
    id = "workflow-sd15-q4-face",
    repoId = "gpustack/stable-diffusion-v1-5-GGUF",
    revision = "fcce3d7f73b52c7e4fa7f00116dbdecb59cc832c",
    remotePath = "stable-diffusion-v1-5-Q4_0.gguf",
    sizeBytes = 1_747_190_784L,
    sha256 = "c2f6e92f9d08d69cc673a1003528ac8199274b3c0eaec88d5fbefe5af67bd42b",
    licenseLabel = "CreativeML Open RAIL-M"
)

private val SDXL_TURBO_Q4_FACE = checkpoint(
    id = "workflow-sdxl-turbo-q4-face",
    repoId = "gpustack/stable-diffusion-xl-1.0-turbo-GGUF",
    revision = "8978218f370944c135a689ff3347171195ecdeb6",
    remotePath = "stable-diffusion-xl-1.0-turbo-Q4_0.gguf",
    // Keep the pinned revision, payload size, and digest from the same HF LFS
    // object. The current-main digest belongs to the separate midrange bundle.
    sizeBytes = 2_765_375_264L,
    sha256 = "d54425f5607a477da26890dd6dba26620d06ae9bcf9f7026f2849bc6e2725af8",
    licenseLabel = "Stability AI Community License",
    variant = "sdxl",
    sizeIsApproximate = false
)

private val FACE_YOLOV8N = detector(
    id = "workflow-face-yolov8n",
    upstreamPath = "face_yolov8n.pt",
    localName = "face_yolov8n-sdcpp.safetensors",
    sizeBytes = 6_033_980L,
    sha256 = "468497950478c232689b73df512b484c855e620af01e54e44efa606962c512d4",
    licenseLabel = "Apache-2.0",
    compatProfiles = "checkpoint:sd1,checkpoint:sdxl"
)

private val FACE_YOLOV8S = detector(
    id = "workflow-face-yolov8s",
    upstreamPath = "face_yolov8s.pt",
    localName = "face_yolov8s-sdcpp.safetensors",
    sizeBytes = 22_284_028L,
    sha256 = "235fbb79a4db4f9b4593f74da2ac0c16df43aae264529f5948fb1d9a023ca088",
    licenseLabel = "Apache-2.0",
    compatProfiles = "checkpoint:sd1,checkpoint:sdxl"
)

private val HAND_YOLOV8N = detector(
    id = "workflow-hand-yolov8n",
    upstreamPath = "hand_yolov8n.pt",
    localName = "hand_yolov8n-sdcpp.safetensors",
    sizeBytes = 6_033_980L,
    sha256 = "18632a2f8c486d2914a009e87aefff986e7320b8009638bd09e73b64b81c557c",
    licenseLabel = "Apache-2.0",
    compatProfiles = "checkpoint:sd1"
)

private val COCO_YOLOV8N = detector(
    id = "workflow-coco-yolov8n",
    upstreamPath = "yolov8n.pt",
    localName = "yolov8n-coco-sdcpp.safetensors",
    sizeBytes = 6_328_408L,
    sha256 = "0049ba04d51760f3ea5c20f2f2f4522b09ddb67229f08d193f325f0d220c16d8",
    licenseLabel = "AGPL-3.0",
    compatProfiles = "checkpoint:sd1",
    revision = COCO_YOLOV8N_REVISION,
    repoId = "ultralytics/assets"
)

private val REALISTIC_VISION_51_FP16 = checkpoint(
    id = "workflow-realistic-vision-51-fp16",
    repoId = "SG161222/Realistic_Vision_V5.1_noVAE",
    revision = "305469d",
    remotePath = "Realistic_Vision_V5.1_fp16-no-ema.safetensors",
    sizeBytes = 2_132_625_894L,
    sha256 = "99a75a901f4fec732056930a89fa34cf360f6f72d75ce5bf333ddf82adf8dd2a",
    licenseLabel = "CreativeML Open RAIL-M"
)

private val OFFICIAL_SD_VAE = SdCuratedBundleFile(
    id = "workflow-official-sd-vae-mse",
    repoId = "stabilityai/sd-vae-ft-mse-original",
    revision = "c1eba256520a04c72a0dedbf5a326209c576dbef",
    remotePath = "vae-ft-mse-840000-ema-pruned.safetensors",
    modelType = ModelType.SD_VAE,
    sizeBytes = 334_641_190L,
    sha256 = "735e4c3a447a3255760d7f86845f09f937809baa529c17370d83e4c3758f3c75",
    licenseLabel = "MIT",
    sdCompatProfiles = "checkpoint:sd1"
)

private val SD15_INPAINTING = checkpoint(
    id = "workflow-sd15-inpainting",
    repoId = "webui/stable-diffusion-inpainting",
    revision = "c510a60",
    remotePath = "sd-v1-5-inpainting.safetensors",
    sizeBytes = 4_265_203_873L,
    sha256 = "0ec8f8585b104417a8c34a9fbcc1e922a70b8c15490ec4553087f01c8cf33673",
    licenseLabel = "CreativeML Open RAIL-M",
    capabilities = buildSdCapabilities(SD_CAPABILITY_IMG2IMG)
)

object SdWorkflowPresetCatalog {
    val presets: List<SdWorkflowPreset> = listOf(
        SdWorkflowPreset(
            operation = SdWorkflowOperation.FACE_FAST,
            bundle = SdCuratedBundle(
                id = "workflow-face-fast",
                titleRes = R.string.sd_workflow_face_fast_title,
                descriptionRes = R.string.sd_workflow_face_fast_desc,
                installPrefix = "Workflow-Face-Fast",
                files = listOf(SD15_Q4_FACE, FACE_YOLOV8N)
            ),
            detectorRole = "face_yolov8n",
            smokePrompt = "portrait, neutral facial expression, natural eyes",
            smokeNegativePrompt = "smile, distorted face, duplicate features"
        ),
        SdWorkflowPreset(
            operation = SdWorkflowOperation.FACE_QUALITY,
            bundle = SdCuratedBundle(
                id = "workflow-face-quality",
                titleRes = R.string.sd_workflow_face_quality_title,
                descriptionRes = R.string.sd_workflow_face_quality_desc,
                installPrefix = "Workflow-Face-Quality",
                files = listOf(SDXL_TURBO_Q4_FACE, FACE_YOLOV8S)
            ),
            detectorRole = "face_yolov8s",
            smokePrompt = "portrait, clear eyes, natural skin texture",
            smokeNegativePrompt = "cross-eyed, blurry eyes, plastic skin"
        ),
        SdWorkflowPreset(
            operation = SdWorkflowOperation.HAND_REPAIR,
            bundle = SdCuratedBundle(
                id = "workflow-hand-repair",
                titleRes = R.string.sd_workflow_hand_repair_title,
                descriptionRes = R.string.sd_workflow_hand_repair_desc,
                installPrefix = "Workflow-Hand-Repair",
                files = listOf(SD15_Q4_FACE, HAND_YOLOV8N)
            ),
            detectorRole = "hand_yolov8n",
            smokePrompt = "person holding a cup, anatomically correct hands",
            smokeNegativePrompt = "extra fingers, fused fingers, malformed hands"
        ),
        SdWorkflowPreset(
            operation = SdWorkflowOperation.OBJECT_EDIT,
            bundle = SdCuratedBundle(
                id = "workflow-object-edit",
                titleRes = R.string.sd_workflow_object_edit_title,
                descriptionRes = R.string.sd_workflow_object_edit_desc,
                installPrefix = "Workflow-Object-Edit",
                files = listOf(SD15_Q4_FACE, COCO_YOLOV8N)
            ),
            detectorRole = "coco_yolov8n",
            objectLargestOnly = true,
            defaultMaxDetections = 1,
            requiredAdvancedArgs = "mask_k_largest=1",
            smokePrompt = "a single red backpack on a wooden table",
            smokeNegativePrompt = "duplicate objects, unwanted crop, broken edges"
        ),
        SdWorkflowPreset(
            operation = SdWorkflowOperation.PORTRAIT_DETAIL_PRO,
            bundle = SdCuratedBundle(
                id = "workflow-portrait-detail-pro",
                titleRes = R.string.sd_workflow_portrait_detail_pro_title,
                descriptionRes = R.string.sd_workflow_portrait_detail_pro_desc,
                installPrefix = "Workflow-Portrait-Detail-Pro",
                files = listOf(REALISTIC_VISION_51_FP16, OFFICIAL_SD_VAE, FACE_YOLOV8S)
            ),
            detectorRole = "face_yolov8s",
            smokePrompt = "professional portrait, natural skin, sharp eyes",
            smokeNegativePrompt = "plastic skin, asymmetrical eyes, overprocessed face"
        ),
        SdWorkflowPreset(
            operation = SdWorkflowOperation.PRECISION_INPAINTING,
            bundle = SdCuratedBundle(
                id = "workflow-precision-inpainting",
                titleRes = R.string.sd_workflow_precision_inpainting_title,
                descriptionRes = R.string.sd_workflow_precision_inpainting_desc,
                installPrefix = "Workflow-Precision-Inpainting",
                files = listOf(SD15_INPAINTING)
            ),
            smokePrompt = "seamless local repair matching the source lighting and texture",
            smokeNegativePrompt = "visible seam, halo, changed composition"
        )
    )

    val bundles: List<SdCuratedBundle> get() = presets.map { it.bundle }

    fun byId(id: String): SdWorkflowPreset? = presets.firstOrNull { it.id == id }

    fun byOperation(operation: SdWorkflowOperation): SdWorkflowPreset =
        presets.first { it.operation == operation }
}
