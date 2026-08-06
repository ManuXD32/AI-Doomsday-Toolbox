package com.example.llamadroid.data.model

import android.content.Context
import androidx.annotation.StringRes
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SD_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_TXT2IMG
import com.example.llamadroid.data.db.SD_CAPABILITY_VID_GEN
import com.example.llamadroid.data.db.buildSdCapabilities
import com.example.llamadroid.service.DownloadService
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** One versioned upstream file in a curated Stable Diffusion bundle. */
data class SdCuratedBundleFile(
    val id: String,
    val repoId: String,
    val revision: String = "main",
    val remotePath: String,
    val modelType: ModelType,
    val sizeBytes: Long,
    val sha256: String,
    val licenseLabel: String,
    val sizeIsApproximate: Boolean = false,
    val isVision: Boolean = false,
    val sdCapabilities: String? = null,
    val sdFamily: String? = null,
    val sdVariant: String? = null,
    val sdCompatProfiles: String? = null
) {
    val sourceFilename: String
        get() = remotePath.substringAfterLast('/')

    fun localFilename(bundlePrefix: String): String =
        curatedBundleFilename(bundlePrefix, sourceFilename)

    fun downloadUrl(): String {
        val encodedRevision = revision.urlPathSegment()
        val encodedPath = remotePath
            .split('/')
            .joinToString("/") { segment -> segment.urlPathSegment() }
        return "https://huggingface.co/$repoId/resolve/$encodedRevision/$encodedPath?download=true"
    }
}

data class SdCuratedBundle(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val installPrefix: String,
    val files: List<SdCuratedBundleFile>
) {
    val totalSizeBytes: Long
        get() = files.sumOf { it.sizeBytes }

    val totalSizeIsApproximate: Boolean
        get() = files.any { it.sizeIsApproximate }
}

data class SdCuratedDownloadHandle(
    val progressKey: String,
    val filename: String,
    val type: ModelType
)

private val SD15_Q4 = SdCuratedBundleFile(
    id = "sd15-q4",
    repoId = "gpustack/stable-diffusion-v1-5-GGUF",
    remotePath = "stable-diffusion-v1-5-Q4_0.gguf",
    modelType = ModelType.SD_CHECKPOINT,
    sizeBytes = 1_747_190_784L,
    sha256 = "c2f6e92f9d08d69cc673a1003528ac8199274b3c0eaec88d5fbefe5af67bd42b",
    licenseLabel = "CreativeML Open RAIL-M",
    sdCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG, SD_CAPABILITY_IMG2IMG),
    sdFamily = "checkpoint",
    sdVariant = "sd1"
)

private val SDXL_TURBO_Q4 = SdCuratedBundleFile(
    id = "sdxl-turbo-q4",
    repoId = "gpustack/stable-diffusion-xl-1.0-turbo-GGUF",
    remotePath = "stable-diffusion-xl-1.0-turbo-Q4_0.gguf",
    modelType = ModelType.SD_CHECKPOINT,
    sizeBytes = 3_940_000_000L,
    sha256 = "5282eec23430ca46de87de984521fbf04e3cf78fa4152b05610089bc71d8a535",
    licenseLabel = "Stability AI Community License",
    sizeIsApproximate = true,
    sdCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG, SD_CAPABILITY_IMG2IMG),
    sdFamily = "checkpoint",
    sdVariant = "sdxl"
)

private val IP_ADAPTER_SD15 = SdCuratedBundleFile(
    id = "ip-adapter-sd15",
    repoId = "h94/IP-Adapter",
    remotePath = "models/ip-adapter_sd15.safetensors",
    modelType = ModelType.SD_IP_ADAPTER,
    sizeBytes = 44_642_768L,
    sha256 = "289b45f16d043d0bf542e45831f971dcdaabe18b656f11e86d9dfba7e9ee3369",
    licenseLabel = "Apache-2.0",
    sdCompatProfiles = "checkpoint:sd1"
)

private val CLIP_VISION_SD15 = SdCuratedBundleFile(
    id = "clip-vision-sd15",
    repoId = "h94/IP-Adapter",
    remotePath = "models/image_encoder/model.safetensors",
    modelType = ModelType.SD_CLIP_VISION,
    sizeBytes = 2_528_373_448L,
    sha256 = "6ca9667da1ca9e0b0f75e46bb030f7e011f44f86cbfb8d5a36590fcd7507b030",
    licenseLabel = "Apache-2.0",
    isVision = true,
    sdCompatProfiles = "checkpoint:sd1"
)

private val FLUX_SCHNELL_Q4 = SdCuratedBundleFile(
    id = "flux-schnell-q4",
    repoId = "city96/FLUX.1-schnell-gguf",
    remotePath = "flux1-schnell-Q4_0.gguf",
    modelType = ModelType.SD_DIFFUSION,
    sizeBytes = 6_770_707_360L,
    sha256 = "90a393d3a44bec691c707003f434fdde06064b870bb3c206eb7a4f109b25ff4e",
    licenseLabel = "Apache-2.0",
    sdCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
    sdFamily = "flux_1",
    sdVariant = "schnell"
)

private val FLUX_SCHNELL_Q6 = SdCuratedBundleFile(
    id = "flux-schnell-q6",
    repoId = "city96/FLUX.1-schnell-gguf",
    remotePath = "flux1-schnell-Q6_K.gguf",
    modelType = ModelType.SD_DIFFUSION,
    sizeBytes = 9_834_955_808L,
    sha256 = "a42fd143cec4d7194da281dc8d23a8fe54b16875a13423c042cb545d1da6fa50",
    licenseLabel = "Apache-2.0",
    sdCapabilities = buildSdCapabilities(SD_CAPABILITY_TXT2IMG),
    sdFamily = "flux_1",
    sdVariant = "schnell"
)

private val FLUX_CLIP_L = SdCuratedBundleFile(
    id = "flux-clip-l",
    repoId = "comfyanonymous/flux_text_encoders",
    remotePath = "clip_l.safetensors",
    modelType = ModelType.SD_CLIP_L,
    sizeBytes = 246_144_152L,
    sha256 = "660c6f5b1abae9dc498ac2d21e1347d2abdb0cf6c0c0c8576cd796491d9a6cdd",
    licenseLabel = "Apache-2.0",
    sdCompatProfiles = "flux_1,flux_1:schnell"
)

private val FLUX_T5_Q4 = SdCuratedBundleFile(
    id = "flux-t5-q4",
    repoId = "city96/t5-v1_1-xxl-encoder-gguf",
    remotePath = "t5-v1_1-xxl-encoder-Q4_K_M.gguf",
    modelType = ModelType.SD_T5XXL,
    sizeBytes = 2_896_123_072L,
    sha256 = "6be2b0b7e2de7cf2919340c88cb802a103a997ce46c53131cec91958c1db1af4",
    licenseLabel = "Apache-2.0",
    sdCompatProfiles = "flux_1,flux_1:schnell"
)

private val FLUX_VAE = SdCuratedBundleFile(
    id = "flux-vae",
    repoId = "flux-safetensors/flux-safetensors",
    remotePath = "ae.safetensors",
    modelType = ModelType.SD_VAE,
    sizeBytes = 335_304_388L,
    sha256 = "afc8e28272cd15db3919bacdb6918ce9c1ed22e96cb12c4d5ed0fba823529e38",
    licenseLabel = "Apache-2.0",
    sdCompatProfiles = "flux_1,flux_1:schnell"
)

private val WAN22_Q4 = SdCuratedBundleFile(
    id = "wan22-ti2v-q4",
    repoId = "QuantStack/Wan2.2-TI2V-5B-GGUF",
    remotePath = "Wan2.2-TI2V-5B-Q4_K_S.gguf",
    modelType = ModelType.SD_DIFFUSION,
    sizeBytes = 3_116_380_512L,
    sha256 = "ab4195ecd022e57455672771d8ec14c2589efc9ddd6b96c3578fbb326797bdbb",
    licenseLabel = "Apache-2.0",
    sdCapabilities = buildSdCapabilities(SD_CAPABILITY_VID_GEN)
)

private val WAN22_UMT5_Q4 = SdCuratedBundleFile(
    id = "wan22-umt5-q4",
    repoId = "city96/umt5-xxl-encoder-gguf",
    remotePath = "umt5-xxl-encoder-Q4_K_M.gguf",
    modelType = ModelType.SD_T5XXL,
    sizeBytes = 3_655_145_312L,
    sha256 = "17cf97a5bbbc60a646d6105b832b6f657ce904a8a1ad970e4b59df0c67584a40",
    licenseLabel = "Apache-2.0",
    sdCompatProfiles = "wan2.2"
)

private val WAN22_VAE = SdCuratedBundleFile(
    id = "wan22-vae",
    repoId = "Comfy-Org/Wan_2.2_ComfyUI_Repackaged",
    remotePath = "split_files/vae/wan2.2_vae.safetensors",
    modelType = ModelType.SD_VAE,
    sizeBytes = 1_409_400_960L,
    sha256 = "e40321bd36b9709991dae2530eb4ac303dd168276980d3e9bc4b6e2b75fed156",
    licenseLabel = "Apache-2.0",
    sdCompatProfiles = "wan2.2"
)

private val REALESRGAN_PHOTO_2X = SdCuratedBundleFile(
    id = "realesrgan-photo-2x",
    repoId = "leonelhs/realesrgan",
    remotePath = "RealESRGAN_x2plus.pth",
    modelType = ModelType.SD_UPSCALER,
    sizeBytes = 67_061_725L,
    sha256 = "49fafd45f8fd7aa8d31ab2a22d14d91b536c34494a5cfe31eb5d89c2fa266abb",
    licenseLabel = "MIT",
    sdCompatProfiles = "upscaler"
)

private val REALESRGAN_PHOTO_4X = SdCuratedBundleFile(
    id = "realesrgan-photo-4x",
    repoId = "leonelhs/realesrgan",
    remotePath = "RealESRGAN_x4plus.pth",
    modelType = ModelType.SD_UPSCALER,
    sizeBytes = 67_040_989L,
    sha256 = "4fa0d38905f75ac06eb49a7951b426670021be3018265fd191d2125df9d682f1",
    licenseLabel = "MIT",
    sdCompatProfiles = "upscaler"
)

private val REALESRGAN_ANIME_4X = SdCuratedBundleFile(
    id = "realesrgan-anime-4x",
    repoId = "amd/realesrgan-x4plus-anime-6b",
    remotePath = "RealESRGAN_x4plus_anime_6B.pth",
    modelType = ModelType.SD_UPSCALER,
    sizeBytes = 17_938_799L,
    sha256 = "f872d837d3c90ed2e05227bed711af5671a6fd1c9f7d7e91c911a61f155e99da",
    licenseLabel = "BSD-3-Clause",
    sdCompatProfiles = "upscaler"
)

object SdCuratedBundleCatalog {
    val bundles: List<SdCuratedBundle> = listOf(
        SdCuratedBundle(
            id = "ipa-starter",
            titleRes = R.string.sd_bundle_ipa_title,
            descriptionRes = R.string.sd_bundle_ipa_desc,
            installPrefix = "IPA-Starter",
            files = listOf(SD15_Q4, IP_ADAPTER_SD15, CLIP_VISION_SD15)
        ),
        SdCuratedBundle(
            id = "low-end",
            titleRes = R.string.sd_bundle_low_title,
            descriptionRes = R.string.sd_bundle_low_desc,
            installPrefix = "Low-End",
            files = listOf(SD15_Q4)
        ),
        SdCuratedBundle(
            id = "midrange",
            titleRes = R.string.sd_bundle_medium_title,
            descriptionRes = R.string.sd_bundle_medium_desc,
            installPrefix = "Midrange",
            files = listOf(SDXL_TURBO_Q4)
        ),
        SdCuratedBundle(
            id = "high-end",
            titleRes = R.string.sd_bundle_high_title,
            descriptionRes = R.string.sd_bundle_high_desc,
            installPrefix = "High-End",
            files = listOf(FLUX_SCHNELL_Q4, FLUX_CLIP_L, FLUX_T5_Q4, FLUX_VAE)
        ),
        SdCuratedBundle(
            id = "distributed",
            titleRes = R.string.sd_bundle_distributed_title,
            descriptionRes = R.string.sd_bundle_distributed_desc,
            installPrefix = "Distributed",
            files = listOf(FLUX_SCHNELL_Q6, FLUX_CLIP_L, FLUX_T5_Q4, FLUX_VAE)
        ),
        SdCuratedBundle(
            id = "local-video",
            titleRes = R.string.sd_bundle_video_title,
            descriptionRes = R.string.sd_bundle_video_desc,
            installPrefix = "Local-Video",
            files = listOf(WAN22_Q4, WAN22_UMT5_Q4, WAN22_VAE)
        ),
        SdCuratedBundle(
            id = "photo-upscale-2x",
            titleRes = R.string.sd_bundle_upscale_photo_2x_title,
            descriptionRes = R.string.sd_bundle_upscale_photo_2x_desc,
            installPrefix = "Photo-2x",
            files = listOf(REALESRGAN_PHOTO_2X)
        ),
        SdCuratedBundle(
            id = "photo-upscale-4x",
            titleRes = R.string.sd_bundle_upscale_photo_4x_title,
            descriptionRes = R.string.sd_bundle_upscale_photo_4x_desc,
            installPrefix = "Photo-4x",
            files = listOf(REALESRGAN_PHOTO_4X)
        ),
        SdCuratedBundle(
            id = "anime-upscale-4x",
            titleRes = R.string.sd_bundle_upscale_anime_title,
            descriptionRes = R.string.sd_bundle_upscale_anime_desc,
            installPrefix = "Anime-4x",
            files = listOf(REALESRGAN_ANIME_4X)
        )
    )

    fun byId(id: String): SdCuratedBundle? = bundles.firstOrNull { it.id == id }

    fun fileForLocalFilename(filename: String): SdCuratedBundleFile? =
        bundles.firstNotNullOfOrNull { bundle ->
            bundle.files.firstOrNull { file ->
                file.localFilename(bundle.installPrefix) == filename
            }
        }
}

fun curatedBundleFilename(prefix: String, sourceFilename: String): String {
    val normalizedPrefix = prefix
        .trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '_', '.')
        .ifBlank { "Bundle" }
    val normalizedSource = sourceFilename
        .trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '_', '.')
        .ifBlank { "model.bin" }
    return ModelLibraryManager.canonicalFilename("$normalizedPrefix-$normalizedSource")
}

fun SdCuratedBundleFile.isInstalledForBundle(
    bundle: SdCuratedBundle,
    installedModels: List<com.example.llamadroid.data.db.ModelEntity>
): Boolean {
    val expectedFilename = localFilename(bundle.installPrefix)
    return installedModels.any { model ->
        val installedFile = File(model.path)
        model.type == modelType &&
            model.filename == expectedFilename &&
            installedFile.isFile &&
            (sizeIsApproximate || installedFile.length() == sizeBytes)
    }
}

internal fun verifySdCuratedFilePayload(
    file: SdCuratedBundleFile,
    downloadedFile: File
) {
    require(downloadedFile.isFile) {
        "Curated bundle file is missing: ${downloadedFile.name}"
    }
    if (!file.sizeIsApproximate) {
        require(downloadedFile.length() == file.sizeBytes) {
            "Curated bundle size mismatch for ${downloadedFile.name}: " +
                "expected ${file.sizeBytes}, found ${downloadedFile.length()}"
        }
    }
    val actualSha256 = downloadedFile.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = input.read(buffer)
        while (count >= 0) {
            if (count > 0) digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
    require(actualSha256 == file.sha256.lowercase(Locale.US)) {
        "Curated bundle checksum mismatch for ${downloadedFile.name}"
    }
}

/** Returns true when [localFilename] belongs to a curated bundle and was verified. */
fun verifySdCuratedDownload(localFilename: String, downloadedFile: File): Boolean {
    val file = SdCuratedBundleCatalog.fileForLocalFilename(localFilename) ?: return false
    try {
        verifySdCuratedFilePayload(file, downloadedFile)
    } catch (error: Throwable) {
        downloadedFile.delete()
        throw error
    }
    return true
}

fun startSdCuratedBundleFileDownload(
    context: Context,
    repository: ModelRepository,
    bundle: SdCuratedBundle,
    file: SdCuratedBundleFile
): SdCuratedDownloadHandle {
    val modelDir = repository.getModelDir(file.modelType).apply { mkdirs() }
    val localFilename = ModelLibraryManager.canonicalFilename(
        file.localFilename(bundle.installPrefix)
    )
    val destination = File(modelDir, localFilename)
    val progressKey = buildDownloadTaskId(file.repoId, localFilename, file.modelType)

    DownloadProgressHolder.updateProgress(progressKey, localFilename, 0f)
    PendingDownloadHolder.addPending(
        downloadId = progressKey,
        filename = localFilename,
        repoId = file.repoId,
        progressKey = progressKey,
        type = file.modelType,
        destPath = destination.absolutePath,
        isVision = file.isVision,
        sdCapabilities = file.sdCapabilities,
        sdFamily = file.sdFamily,
        sdVariant = file.sdVariant,
        sdCompatProfiles = file.sdCompatProfiles
    )
    DownloadService.startDownload(
        context = context,
        url = file.downloadUrl(),
        destPath = destination.absolutePath,
        filename = localFilename,
        downloadId = progressKey
    )
    return SdCuratedDownloadHandle(
        progressKey = progressKey,
        filename = localFilename,
        type = file.modelType
    )
}

fun expectedSdCuratedDownloadHandle(
    bundle: SdCuratedBundle,
    file: SdCuratedBundleFile
): SdCuratedDownloadHandle {
    val filename = file.localFilename(bundle.installPrefix)
    return SdCuratedDownloadHandle(
        progressKey = buildDownloadTaskId(file.repoId, filename, file.modelType),
        filename = filename,
        type = file.modelType
    )
}

private fun String.urlPathSegment(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

internal fun ModelType.curatedLabel(): String =
    name.lowercase(Locale.US).replace('_', ' ')
