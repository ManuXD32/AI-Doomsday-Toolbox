package com.example.llamadroid.data.model

import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType

/** Explicit video-recognition policy; image capability alone is insufficient. */
data class VideoRecognitionPolicy(
    val preferredVideoFps: Float = 2f,
    val segmentSeconds: Int = 12,
    val maxFrames: Int = 24,
    val recommendedContext: Int = 8192,
    val supportsVideoAudio: Boolean = false,
    val recommended: Boolean = false
)

object VideoRecognitionBundleCatalog {
    private fun file(id: String, repo: String, revision: String, name: String, type: ModelType,
                     bytes: Long, hash: String, isVision: Boolean = false) = CuratedBundleFile(
        id = id, repoId = "ggml-org/$repo", revision = revision,
        remotePath = name, localFilename = name, type = type, sizeBytes = bytes,
        isVision = isVision, sha256 = hash, license = "Apache-2.0", strictSize = true
    )

    val bundles = listOf(
        CuratedModelBundle(
            id = "video-smolvlm2-500m", titleRes = R.string.video_bundle_smol_title,
            descriptionRes = R.string.video_bundle_smol_desc, defaultPrefix = "Video-SmolVLM2-500M",
            capabilityRes = listOf(R.string.video_bundle_capability), videoPolicy = VideoRecognitionPolicy(),
            files = listOf(
                file("video-smol-main", "SmolVLM2-500M-Video-Instruct-GGUF", "ccd7aae53bcb1997355c2f094959e72b3642ce17",
                    "SmolVLM2-500M-Video-Instruct-Q8_0.gguf", ModelType.LLM, 436808704L,
                    "6f67b8036b2469fcd71728702720c6b51aebd759b78137a8120733b4d66438bc", isVision = true),
                file("video-smol-mmproj", "SmolVLM2-500M-Video-Instruct-GGUF", "ccd7aae53bcb1997355c2f094959e72b3642ce17",
                    "mmproj-SmolVLM2-500M-Video-Instruct-f16.gguf", ModelType.VISION_PROJECTOR, 199470624L,
                    "b5dc8ebe7cbeab66a5369693960a52515d7824f13d4063ceca78431f2a6b59b0")
            )
        ),
        CuratedModelBundle(
            id = "video-qwen25vl-3b", titleRes = R.string.video_bundle_qwen3_title,
            descriptionRes = R.string.video_bundle_qwen3_desc, defaultPrefix = "Video-Qwen25VL-3B",
            capabilityRes = listOf(R.string.video_bundle_capability),
            videoPolicy = VideoRecognitionPolicy(recommended = true),
            files = listOf(
                file("video-qwen3-main", "Qwen2.5-VL-3B-Instruct-GGUF", "5037fcf163dd95d1e41d1974465f0898ed108ca2",
                    "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", ModelType.LLM, 1929901056L,
                    "d02fe9b69ad8cadbbd228e387667af66612c44bed29ffc8eb1e7caf9ac486c12", isVision = true),
                file("video-qwen3-mmproj", "Qwen2.5-VL-3B-Instruct-GGUF", "5037fcf163dd95d1e41d1974465f0898ed108ca2",
                    "mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf", ModelType.VISION_PROJECTOR, 1338428128L,
                    "b9160fe9d814d1fadf68395677468534778b39ac33c2e7561b7b218626e60d5e")
            )
        ),
        CuratedModelBundle(
            id = "video-qwen25vl-7b", titleRes = R.string.video_bundle_qwen7_title,
            descriptionRes = R.string.video_bundle_qwen7_desc, defaultPrefix = "Video-Qwen25VL-7B",
            capabilityRes = listOf(R.string.video_bundle_capability), videoPolicy = VideoRecognitionPolicy(),
            files = listOf(
                file("video-qwen7-main", "Qwen2.5-VL-7B-Instruct-GGUF", "508edd0afaa66bb9e9f40587acc2184f02daf1f6",
                    "Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf", ModelType.LLM, 4683072032L,
                    "9258bf05b12686d097ff3b6b18d968ab393649780aa2b3cd67fec43d50554392", isVision = true),
                file("video-qwen7-mmproj", "Qwen2.5-VL-7B-Instruct-GGUF", "508edd0afaa66bb9e9f40587acc2184f02daf1f6",
                    "mmproj-Qwen2.5-VL-7B-Instruct-f16.gguf", ModelType.VISION_PROJECTOR, 1354162912L,
                    "c24a7f5fcfc68286f0a217023b6738e73bea4f11787a43e8238d4bb1b8604cde")
            )
        )
    )

    /** Uses the same exact installed-file contract as the canonical bundle cards. */
    fun installedModels(bundle: CuratedModelBundle, models: List<ModelEntity>): List<ModelEntity> =
        bundle.files.mapNotNull { file ->
            models.firstOrNull { file.matchesVerifiedInstalledModel(file.installedFilename(bundle.defaultPrefix), it) }
        }.takeIf { it.size == bundle.files.size }.orEmpty()

    fun installedBundles(models: List<ModelEntity>): List<CuratedModelBundle> =
        bundles.filter { installedModels(it, models).isNotEmpty() }
}
