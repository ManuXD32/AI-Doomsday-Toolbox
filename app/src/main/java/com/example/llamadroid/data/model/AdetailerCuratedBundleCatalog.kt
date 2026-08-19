package com.example.llamadroid.data.model

import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelType

object AdetailerCuratedBundleCatalog {
    val bundles: List<CuratedModelBundle> = listOf(
        CuratedModelBundle(
            id = "adetailer-face-fast",
            titleRes = R.string.adetailer_bundle_face_fast_title,
            descriptionRes = R.string.adetailer_bundle_face_fast_desc,
            defaultPrefix = "ADetailer-Face-Fast",
            capabilityRes = listOf(
                R.string.curated_bundle_capability_face_detection,
                R.string.curated_bundle_capability_fast,
                R.string.curated_bundle_capability_safetensors
            ),
            files = listOf(
                CuratedBundleFile(
                    id = "adetailer-face-yolov8n-sdcpp",
                    repoId = "Bingsu/adetailer",
                    revision = "53cc19de382014514d9d4038601d261a7faa9b7b",
                    remotePath = "face_yolov8n.pt",
                    localFilename = "face_yolov8n-sdcpp.safetensors",
                    type = ModelType.SD_ADETAILER,
                    sizeBytes = 6_033_980L,
                    sha256 = "468497950478c232689b73df512b484c855e620af01e54e44efa606962c512d4",
                    license = "Apache-2.0",
                    strictSize = true,
                    note = "Converted from the pinned face_yolov8n.pt checkpoint with the official stable-diffusion.cpp YOLOv8 converter.",
                    downloadUrlOverride = convertedDetectorUrl("face_yolov8n-sdcpp.safetensors")
                )
            )
        ),
        CuratedModelBundle(
            id = "adetailer-face-quality",
            titleRes = R.string.adetailer_bundle_face_quality_title,
            descriptionRes = R.string.adetailer_bundle_face_quality_desc,
            defaultPrefix = "ADetailer-Face-Quality",
            capabilityRes = listOf(
                R.string.curated_bundle_capability_face_detection,
                R.string.curated_bundle_capability_higher_accuracy,
                R.string.curated_bundle_capability_safetensors
            ),
            files = listOf(
                CuratedBundleFile(
                    id = "adetailer-face-yolov8s-sdcpp",
                    repoId = "Bingsu/adetailer",
                    revision = "53cc19de382014514d9d4038601d261a7faa9b7b",
                    remotePath = "face_yolov8s.pt",
                    localFilename = "face_yolov8s-sdcpp.safetensors",
                    type = ModelType.SD_ADETAILER,
                    sizeBytes = 22_284_028L,
                    sha256 = "235fbb79a4db4f9b4593f74da2ac0c16df43aae264529f5948fb1d9a023ca088",
                    license = "Apache-2.0",
                    strictSize = true,
                    note = "Converted from the pinned face_yolov8s.pt checkpoint with the official stable-diffusion.cpp YOLOv8 converter.",
                    downloadUrlOverride = convertedDetectorUrl("face_yolov8s-sdcpp.safetensors")
                )
            )
        ),
        CuratedModelBundle(
            id = "adetailer-hands-fast",
            titleRes = R.string.adetailer_bundle_hands_title,
            descriptionRes = R.string.adetailer_bundle_hands_desc,
            defaultPrefix = "ADetailer-Hands",
            capabilityRes = listOf(
                R.string.curated_bundle_capability_hand_detection,
                R.string.curated_bundle_capability_fast,
                R.string.curated_bundle_capability_safetensors
            ),
            files = listOf(
                CuratedBundleFile(
                    id = "adetailer-hand-yolov8n-sdcpp",
                    repoId = "Bingsu/adetailer",
                    revision = "53cc19de382014514d9d4038601d261a7faa9b7b",
                    remotePath = "hand_yolov8n.pt",
                    localFilename = "hand_yolov8n-sdcpp.safetensors",
                    type = ModelType.SD_ADETAILER,
                    sizeBytes = 6_033_980L,
                    sha256 = "18632a2f8c486d2914a009e87aefff986e7320b8009638bd09e73b64b81c557c",
                    license = "Apache-2.0",
                    strictSize = true,
                    note = "Converted from the pinned hand_yolov8n.pt checkpoint with the official stable-diffusion.cpp YOLOv8 converter.",
                    downloadUrlOverride = convertedDetectorUrl("hand_yolov8n-sdcpp.safetensors")
                )
            )
        ),
        CuratedModelBundle(
            id = "adetailer-general-objects",
            titleRes = R.string.adetailer_bundle_objects_title,
            descriptionRes = R.string.adetailer_bundle_objects_desc,
            defaultPrefix = "ADetailer-Objects",
            capabilityRes = listOf(
                R.string.curated_bundle_capability_general_objects,
                R.string.curated_bundle_capability_coco,
                R.string.curated_bundle_capability_safetensors
            ),
            files = listOf(
                CuratedBundleFile(
                    id = "adetailer-yolov8n-coco-sdcpp",
                    repoId = "ultralytics/assets",
                    revision = "v8.3.0",
                    remotePath = "yolov8n.pt",
                    localFilename = "yolov8n-coco-sdcpp.safetensors",
                    type = ModelType.SD_ADETAILER,
                    sizeBytes = 6_328_408L,
                    sha256 = "0049ba04d51760f3ea5c20f2f2f4522b09ddb67229f08d193f325f0d220c16d8",
                    license = "AGPL-3.0",
                    strictSize = true,
                    note = "Converted from the official Ultralytics v8.3.0 YOLOv8n COCO detection checkpoint with the stable-diffusion.cpp converter.",
                    downloadUrlOverride = convertedDetectorUrl("yolov8n-coco-sdcpp.safetensors")
                )
            )
        )
    )

    private fun convertedDetectorUrl(filename: String): String =
        "https://raw.githubusercontent.com/ManuXD32/AI-Doomsday-Toolbox/Dev/model-assets/adetailer/$filename"
}
