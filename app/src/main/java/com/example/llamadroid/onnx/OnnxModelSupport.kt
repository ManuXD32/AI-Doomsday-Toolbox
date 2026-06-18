package com.example.llamadroid.onnx

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Parcelable
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.ONNX_CAPABILITY_BACKGROUND_REMOVAL
import com.example.llamadroid.data.db.ONNX_CAPABILITY_TTS
import com.example.llamadroid.data.db.ONNX_CAPABILITY_IMG2IMG
import com.example.llamadroid.data.db.ONNX_CAPABILITY_TXT2IMG
import com.example.llamadroid.tama.data.TamaPicGenDefaults
import com.example.llamadroid.data.db.buildOnnxCapabilities
import com.example.llamadroid.data.db.hasOnnxCapability
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

const val ONNX_PIPELINE_FAMILY_SDAI_LOCAL_DIFFUSION = "sdai_local_diffusion"
const val ONNX_PIPELINE_FAMILY_SUPERTONIC_TTS = "supertonic_tts"
const val ONNX_PIPELINE_FAMILY_BACKGROUND_REMOVAL = "background_removal"
const val ONNX_ASSET_KIND_SDAI_CATALOG_BUNDLE = "sdai_catalog_bundle"
const val ONNX_ASSET_KIND_SUPERTONIC_CATALOG_BUNDLE = "supertonic_catalog_bundle"
const val ONNX_ASSET_KIND_BACKGROUND_REMOVAL_FILE = "background_removal_file"
const val ONNX_ASSET_KIND_CUSTOM_IMPORT_BUNDLE = "custom_import_bundle"
const val ONNX_INSTALL_KIND_FILE = "file"
const val ONNX_INSTALL_KIND_ARCHIVE_BUNDLE = "archive_bundle"
const val ONNX_INSTALL_KIND_HF_TREE_BUNDLE = "hf_tree_bundle"
const val ONNX_IMAGE_GEN_DEFAULT_STRENGTH = 0.35f

data class OnnxCatalogFile(
    val relativePath: String,
    val sizeBytes: Long
)

enum class OnnxRuntimeBackend {
    CPU,
    NNAPI
}

enum class OnnxImageGenMode(val capabilityToken: String, val storageToken: String) {
    TXT2IMG(ONNX_CAPABILITY_TXT2IMG, "txt2img"),
    IMG2IMG(ONNX_CAPABILITY_IMG2IMG, "img2img");

    companion object {
        fun fromMetadataValue(value: String?): OnnxImageGenMode? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) ||
                    it.storageToken.equals(value, ignoreCase = true)
            }
        }
    }
}

data class OnnxBackendResolution(
    val resolvedBackend: OnnxRuntimeBackend,
    val warningMessage: String? = null
)

enum class OnnxInstallSource {
    SDAI_CATALOG,
    CUSTOM_IMPORT
}

enum class OnnxCatalogProvider(
    val id: String,
    private val repoOwner: String
) {
    SDAI(
        id = "sdai",
        repoOwner = "ShiftHackZ/Local-Diffusion-Models-SDAI-ONXX"
    ),
    MANUXD32(
        id = "manuxd32",
        repoOwner = "ManuXD32/Local-Diffusion-Models-SDAI-ONXX"
    ),
    SUPERTONIC(
        id = "supertonic",
        repoOwner = ""
    ),
    BACKGROUND_REMOVAL(
        id = "background_removal",
        repoOwner = ""
    );

    fun releaseDownloadUrl(releaseTag: String, assetName: String): String =
        if (this == SUPERTONIC) {
            "https://huggingface.co/Supertone/supertonic-3/tree/main"
        } else {
            "https://github.com/$repoOwner/releases/download/$releaseTag/$assetName"
        }

    companion object {
        fun fromId(value: String?): OnnxCatalogProvider? =
            entries.firstOrNull { it.id.equals(value, ignoreCase = true) }
    }
}

enum class OnnxImportStrategy {
    LINK_IN_PLACE,
    COPY_TO_MANAGED
}

@Parcelize
data class OnnxImageGenConfig(
    val modelPath: String,
    val modelName: String,
    val mode: OnnxImageGenMode = OnnxImageGenMode.TXT2IMG,
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfgScale: Float = 7.5f,
    val seed: Long = -1L,
    val requestedWidth: Int = width,
    val requestedHeight: Int = height,
    val initImagePath: String? = null,
    val strength: Float? = null,
    val backend: OnnxRuntimeBackend = OnnxRuntimeBackend.CPU,
    val runtimeOptions: OnnxRuntimeOptions = OnnxRuntimeOptions(),
    val outputPath: String
) : Parcelable

data class OnnxCatalogEntry(
    val provider: OnnxCatalogProvider,
    val bundleId: String,
    val title: String,
    val assetName: String,
    val releaseTag: String,
    val sourceLabel: String,
    val summary: String,
    val archiveSizeBytes: Long,
    val modelType: ModelType = ModelType.ONNX_IMAGE_GEN,
    val capabilities: String? = buildOnnxCapabilities(ONNX_CAPABILITY_TXT2IMG),
    val assetKind: String = ONNX_ASSET_KIND_SDAI_CATALOG_BUNDLE,
    val pipelineFamily: String = ONNX_PIPELINE_FAMILY_SDAI_LOCAL_DIFFUSION,
    val directDownloadUrl: String? = null,
    val gated: Boolean = false
) {
    val stableId: String
        get() = buildOnnxCatalogStableId(provider, bundleId)

    val repoId: String
        get() = buildOnnxCatalogRepoId(provider, bundleId)

    val downloadUrl: String
        get() = directDownloadUrl ?: provider.releaseDownloadUrl(releaseTag, assetName)
}

data class OnnxBundleValidationResult(
    val isValid: Boolean,
    val missingPaths: List<String>,
    val bundleRoot: File,
    val supportedCapabilities: Set<String>
)

data class OnnxBundlePaths(
    val root: File,
    val textEncoderModel: File,
    val unetModel: File,
    val vaeDecoderModel: File,
    val vaeEncoderModel: File? = null,
    val tokenizerVocab: File,
    val tokenizerMerges: File
)

data class OnnxTtsBundlePaths(
    val root: File,
    val onnxDir: File,
    val durationPredictor: File,
    val textEncoder: File,
    val vectorEstimator: File,
    val vocoder: File,
    val config: File,
    val unicodeIndexer: File,
    val voiceStylesDir: File
)

private val onnxMetadataJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

@Serializable
data class OnnxGeneratedImageMetadata(
    val imagePath: String,
    val modelName: String,
    val mode: String = OnnxImageGenMode.TXT2IMG.name,
    val prompt: String,
    val negativePrompt: String,
    val requestedWidth: Int,
    val requestedHeight: Int,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Float,
    val seed: Long,
    val initImagePath: String? = null,
    val initImageOriginalWidth: Int? = null,
    val initImageOriginalHeight: Int? = null,
    val initImageCanvasWidth: Int? = null,
    val initImageCanvasHeight: Int? = null,
    val initImageFittedWidth: Int? = null,
    val initImageFittedHeight: Int? = null,
    val initImagePaddingLeft: Int? = null,
    val initImagePaddingTop: Int? = null,
    val strength: Float? = null,
    val effectiveSteps: Int? = null,
    val backend: String,
    val resolvedBackendSummary: OnnxRuntimeExecutionSummary? = null,
    val runtimeOptions: OnnxRuntimeOptions = OnnxRuntimeOptions(),
    val createdAtEpochMs: Long,
    val sharedOutputRelativePath: String? = null,
    val sharedMetadataRelativePath: String? = null,
    val warningMessage: String? = null,
    val totalTimeMs: Long? = null
) {
    fun toJsonString(): String = onnxMetadataJson.encodeToString(this)

    companion object {
        fun fromJson(rawJson: String): OnnxGeneratedImageMetadata =
            onnxMetadataJson.decodeFromString(rawJson)
    }
}

object OnnxCatalog {
    private const val SUPERTONIC_REPO_ID = "Supertone/supertonic-3"

    val supertonicRequiredFiles: List<OnnxCatalogFile> = listOf(
        OnnxCatalogFile("onnx/duration_predictor.onnx", 3_700_000L),
        OnnxCatalogFile("onnx/text_encoder.onnx", 36_400_000L),
        OnnxCatalogFile("onnx/tts.json", 8_250L),
        OnnxCatalogFile("onnx/unicode_indexer.json", 278_000L),
        OnnxCatalogFile("onnx/vector_estimator.onnx", 257_000_000L),
        OnnxCatalogFile("onnx/vocoder.onnx", 101_000_000L),
        OnnxCatalogFile("voice_styles/F1.json", 292_000L),
        OnnxCatalogFile("voice_styles/F2.json", 292_000L),
        OnnxCatalogFile("voice_styles/F3.json", 291_000L),
        OnnxCatalogFile("voice_styles/F4.json", 292_000L),
        OnnxCatalogFile("voice_styles/F5.json", 291_000L),
        OnnxCatalogFile("voice_styles/M1.json", 292_000L),
        OnnxCatalogFile("voice_styles/M2.json", 292_000L),
        OnnxCatalogFile("voice_styles/M3.json", 290_000L),
        OnnxCatalogFile("voice_styles/M4.json", 292_000L),
        OnnxCatalogFile("voice_styles/M5.json", 291_000L)
    )

    fun supertonicResolveUrl(relativePath: String): String =
        "https://huggingface.co/$SUPERTONIC_REPO_ID/resolve/main/$relativePath"

    private val sdaiEntries: List<OnnxCatalogEntry> = listOf(
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "chilloutmix", "Chilloutmix", "chilloutmix.zip", "patch-14022024", "TIEMING/Chilloutmix", "Balanced photorealistic SD1.5 bundle for txt2img.", 822990569L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "majicmix", "Majicmix", "majicmix.zip", "patch-14022024", "absolute_reality", "General-purpose realistic blend from the original SDAI list.", 631257048L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "realvision", "Realvision", "realvision.zip", "patch-14022024", "realastic-vision-v20", "Earlier realistic local bundle from SDAI.", 630980022L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "analogMadness", "Analog Madness", "analogMadness.zip", "patch-25022024", "analogMadness", "Film-like analog aesthetic with strong portraits.", 630810497L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "aniflatmix", "Aniflatmix", "aniflatmix.zip", "patch-25022024", "aniflatmix", "Anime-oriented flat shading model.", 632804629L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "beautifulRealistic_v60", "Beautiful Realistic v60", "beautifulRealistic_v60.zip", "patch-25022024", "beautifulRealistic_v60", "Photorealistic model with softer lighting.", 632719708L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "cetusMix", "Cetus Mix", "cetusMix.zip", "patch-25022024", "cetusMix", "Flexible general-purpose local diffusion bundle.", 632442291L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "counterfit-v3.0", "Counterfeit v3.0", "counterfit-v3.0.zip", "patch-25022024", "counterfeit", "Anime-focused bundle from the SDAI catalog.", 633890577L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "counterfit-v3.0-babes", "Counterfeit Babes", "counterfit-v3.0-babes.zip", "patch-25022024", "babes", "Character-focused variant from the Counterfeit family.", 631129063L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "counterfit-v3.0-zovyarpg", "Counterfeit Zovya RPG", "counterfit-v3.0-zovyarpg.zip", "patch-25022024", "zovyarpg", "Stylized fantasy art variant.", 631379674L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "cyberrealistic_v32", "CyberRealistic v32", "cyberrealistic_v32.zip", "patch-25022024", "cyberrealistic_v32", "Sharp photorealistic look with contrasty lighting.", 632532073L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "darkjunglemix", "Dark Jungle Mix", "darkjunglemix.zip", "patch-25022024", "darkjunglemix", "Moodier cinematic bundle from the SDAI release list.", 633269040L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "deliberate", "Deliberate", "deliberate.zip", "patch-25022024", "deliberate", "Versatile SD1.5 checkpoint converted for Local Diffusion.", 633428725L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "dreamlikep_r_2", "Dreamlike Photoreal 2", "dreamlikep_r_2.zip", "patch-25022024", "dreamlikep_r_2", "Dreamlike photoreal rendering bundle.", 633204356L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "dreamshaper", "DreamShaper", "dreamshaper.zip", "patch-25022024", "dreamshaper", "Popular stylized all-rounder from the SDAI catalog.", 633178982L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "epicrealism_pureEvolutionV4", "EpicRealism PureEvolution V4", "epicrealism_pureEvolutionV4.zip", "patch-25022024", "epicrealism_pureEvolutionV4", "High-detail realism bundle from the official list.", 633276464L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "fantasticmix", "Fantastic Mix", "fantasticmix.zip", "patch-25022024", "fantasticmix", "Fantasy-biased blend tuned for local generation.", 631792026L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "ICBINP", "ICBINP", "ICBINP.zip", "patch-25022024", "ICBINP", "SDAI Local Diffusion bundle included in the upstream catalog.", 632539605L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "Jucy666", "Jucy666", "Jucy666.zip", "patch-25022024", "Jucy666", "High-contrast stylized photoreal bundle.", 632043631L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "landscape", "Landscape", "landscape.zip", "patch-25022024", "landscape", "Landscape-focused SD1.5 ORT bundle.", 630875026L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "lyriel", "Lyriel", "lyriel.zip", "patch-25022024", "lyriel", "Painterly fantasy model for local txt2img.", 630836449L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "majicmixRealistic_betterV2V25", "Majicmix Realistic V2V25", "majicmixRealistic_betterV2V25.zip", "patch-25022024", "majicmixRealistic_betterV2V25", "Realistic Majicmix variant from the official SDAI release.", 633600959L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "meinamix", "Meinamix", "meinamix.zip", "patch-25022024", "meinamix", "Anime/illustration bundle from the SDAI catalog.", 633304014L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "meinaunreal", "Meina Unreal", "meinaunreal.zip", "patch-25022024", "meinaunreal", "Stylized Unreal-inspired rendering model.", 633006522L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "mixreal", "Mixreal", "mixreal.zip", "patch-25022024", "mixreal", "Photo-heavy SD1.5 conversion for Local Diffusion.", 631200733L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "NED", "Never-ending Dream", "NED.zip", "patch-25022024", "Never-ending-dream(NED(vae))", "Fantasy/local diffusion bundle from the official repository.", 633683725L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "realexistence", "Real Existence", "realexistence.zip", "patch-25022024", "realexistence", "Realistic portrait bundle for ONNX txt2img.", 631449647L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "realisticfantasy", "Realistic Fantasy", "realisticfantasy.zip", "patch-25022024", "realisticfantasy", "Fantasy scenes with realistic texture rendering.", 631157588L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "sunshinemix", "Sunshine Mix", "sunshinemix.zip", "patch-25022024", "sunshinemix", "Warm general-purpose local diffusion mix.", 631373634L),
        OnnxCatalogEntry(OnnxCatalogProvider.SDAI, "universestable", "Universe Stable", "universestable.zip", "patch-25022024", "universestable", "General-use SD1.5 ORT bundle from SDAI.", 630796061L)
    )

    private val manuEntries: List<OnnxCatalogEntry> = listOf(
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "analogMadness", "Analog Madness", "analogMadness.zip", "vae_encoder", "analogMadness", "Analog Madness bundle with added VAE encoder for img2img.", 650529561L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "aniflatmix", "Aniflatmix", "aniflatmix.zip", "vae_encoder", "aniflatmix", "Anime-oriented flat shading model with img2img-ready VAE encoder.", 652514135L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "beautifulRealistic_v60", "Beautiful Realistic v60", "beautifulRealistic_v60.zip", "vae_encoder", "beautifulRealistic_v60", "Photorealistic bundle with VAE encoder added for img2img.", 652435499L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "cetusMix", "Cetus Mix", "cetusMix.zip", "vae_encoder", "cetusMix", "General-purpose local diffusion bundle updated with img2img support.", 652085952L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "counterfit-v3.0-babes", "Counterfeit Babes", "counterfit-v3.0-babes.zip", "vae_encoder", "babes", "Counterfeit family variant with added VAE encoder support.", 650837338L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "counterfit-v3.0-zovyarpg", "Counterfeit Zovya RPG", "counterfit-v3.0-zovyarpg.zip", "vae_encoder", "zovyarpg", "Stylized fantasy art variant updated for img2img.", 651145606L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "counterfit-v3.0", "Counterfeit v3.0", "counterfit-v3.0.zip", "vae_encoder", "counterfeit", "Anime-focused bundle with VAE encoder added for local img2img.", 653614985L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "cyberrealistic_v32", "CyberRealistic v32", "cyberrealistic_v32.zip", "vae_encoder", "cyberrealistic_v32", "Sharp photorealistic bundle patched with VAE encoder support.", 652246060L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "darkjunglemix", "Dark Jungle Mix", "darkjunglemix.zip", "vae_encoder", "darkjunglemix", "Moodier cinematic bundle with img2img-ready encoder.", 652977943L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "deliberate", "Deliberate", "deliberate.zip", "vae_encoder", "deliberate", "Versatile SD1.5 checkpoint converted for Local Diffusion with VAE encoder support.", 653161679L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "dreamlikep_r_2", "Dreamlike Photoreal 2", "dreamlikep_r_2.zip", "vae_encoder", "dreamlikep_r_2", "Dreamlike photoreal rendering bundle updated for img2img.", 652940449L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "dreamshaper", "DreamShaper", "dreamshaper.zip", "vae_encoder", "dreamshaper", "Popular stylized all-rounder with added VAE encoder for img2img.", 652897195L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "epicrealism_pureEvolutionV4", "EpicRealism PureEvolution V4", "epicrealism_pureEvolutionV4.zip", "vae_encoder", "epicrealism_pureEvolutionV4", "High-detail realism bundle patched for img2img.", 652991822L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "fantasticmix", "Fantastic Mix", "fantasticmix.zip", "vae_encoder", "fantasticmix", "Fantasy-biased blend with img2img-ready VAE encoder.", 651512192L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "ICBINP", "ICBINP", "ICBINP.zip", "vae_encoder", "ICBINP", "Local Diffusion bundle from the ManuXD32 fork with img2img support.", 652273218L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "Jucy666", "Jucy666", "Jucy666.zip", "vae_encoder", "Jucy666", "High-contrast stylized photoreal bundle with VAE encoder added.", 651751474L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "landscape", "Landscape", "landscape.zip", "vae_encoder", "landscape", "Landscape-focused ORT bundle with img2img support added.", 711165464L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "lyriel", "Lyriel", "lyriel.zip", "vae_encoder", "lyriel", "Painterly fantasy model updated for local img2img.", 650566846L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "majicmixRealistic_betterV2V25", "Majicmix Realistic V2V25", "majicmixRealistic_betterV2V25.zip", "vae_encoder", "majicmixRealistic_betterV2V25", "Realistic Majicmix variant with added img2img support.", 653328396L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "meinamix", "Meinamix", "meinamix.zip", "vae_encoder", "meinamix", "Anime/illustration bundle from the forked img2img-ready catalog.", 653004931L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "meinaunreal", "Meina Unreal", "meinaunreal.zip", "vae_encoder", "meinaunreal", "Stylized Unreal-inspired rendering bundle with VAE encoder.", 652729915L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "mixreal", "Mixreal", "mixreal.zip", "vae_encoder", "mixreal", "Photo-heavy Local Diffusion conversion with img2img-ready encoder.", 757838467L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "realexistence", "Real Existence", "realexistence.zip", "vae_encoder", "realexistence", "Realistic portrait bundle patched for img2img.", 651138404L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "realisticfantasy", "Realistic Fantasy", "realisticfantasy.zip", "vae_encoder", "realisticfantasy", "Fantasy scenes bundle with added VAE encoder support.", 650891744L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "realvision", "Realvision", "realvision.zip", "vae_encoder", "realastic-vision-v20", "Earlier realistic local bundle from the fork with img2img support.", 652324928L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "sunshinemix", "Sunshine Mix", "sunshinemix.zip", "vae_encoder", "sunshinemix", "Warm general-purpose local diffusion mix with VAE encoder added.", 651071465L),
        OnnxCatalogEntry(OnnxCatalogProvider.MANUXD32, "universestable", "Universe Stable", "universestable.zip", "vae_encoder", "universestable", "General-use SD1.5 ORT bundle with img2img-ready VAE encoder.", 650520961L)
    )

    private val supertonicEntries: List<OnnxCatalogEntry> = listOf(
        OnnxCatalogEntry(
            provider = OnnxCatalogProvider.SUPERTONIC,
            bundleId = "supertonic-3",
            title = "Supertonic 3",
            assetName = "onnx+voice_styles",
            releaseTag = "main",
            sourceLabel = SUPERTONIC_REPO_ID,
            summary = "Multilingual ONNX text-to-speech bundle with preset voices.",
            archiveSizeBytes = 404_000_000L,
            modelType = ModelType.ONNX_TTS,
            capabilities = buildOnnxCapabilities(ONNX_CAPABILITY_TTS),
            assetKind = ONNX_ASSET_KIND_SUPERTONIC_CATALOG_BUNDLE,
            pipelineFamily = ONNX_PIPELINE_FAMILY_SUPERTONIC_TTS
        )
    )

    private fun bgrEntry(
        bundleId: String,
        title: String,
        repoId: String,
        path: String,
        summary: String,
        sizeBytes: Long,
        gated: Boolean = false
    ): OnnxCatalogEntry = OnnxCatalogEntry(
        provider = OnnxCatalogProvider.BACKGROUND_REMOVAL,
        bundleId = bundleId,
        title = title,
        assetName = path,
        releaseTag = "main",
        sourceLabel = repoId,
        summary = summary,
        archiveSizeBytes = sizeBytes,
        modelType = ModelType.ONNX_BACKGROUND_REMOVAL,
        capabilities = buildOnnxCapabilities(ONNX_CAPABILITY_BACKGROUND_REMOVAL),
        assetKind = ONNX_ASSET_KIND_BACKGROUND_REMOVAL_FILE,
        pipelineFamily = ONNX_PIPELINE_FAMILY_BACKGROUND_REMOVAL,
        directDownloadUrl = "https://huggingface.co/$repoId/resolve/main/$path",
        gated = gated
    )

    private val backgroundRemovalEntries: List<OnnxCatalogEntry> = listOf(
        bgrEntry(
            bundleId = "ben2_fp16",
            title = "BEN2 FP16",
            repoId = "onnx-community/BEN2-ONNX",
            path = "onnx/model_fp16.onnx",
            summary = "MIT-licensed BEN2 background-removal model optimized for ONNX Runtime.",
            sizeBytes = 219_000_000L
        ),
        bgrEntry(
            bundleId = "rmbg_2_0_full",
            title = "RMBG 2.0 Full",
            repoId = "briaai/RMBG-2.0",
            path = "onnx/model.onnx",
            summary = "BRIA RMBG 2.0 full ONNX model. Requires accepted Hugging Face access and a token.",
            sizeBytes = 1_020_000_000L,
            gated = true
        ),
        bgrEntry("rmbg_2_0_fp16", "RMBG 2.0 FP16", "briaai/RMBG-2.0", "onnx/model_fp16.onnx", "BRIA RMBG 2.0 FP16 ONNX model. Requires accepted Hugging Face access and a token.", 514_000_000L, gated = true),
        bgrEntry("rmbg_2_0_bnb4", "RMBG 2.0 BNB4", "briaai/RMBG-2.0", "onnx/model_bnb4.onnx", "BRIA RMBG 2.0 BNB4 ONNX variant. Requires accepted Hugging Face access and a token.", 355_000_000L, gated = true),
        bgrEntry("rmbg_2_0_int8", "RMBG 2.0 INT8", "briaai/RMBG-2.0", "onnx/model_int8.onnx", "BRIA RMBG 2.0 INT8 ONNX variant. Requires accepted Hugging Face access and a token.", 366_000_000L, gated = true),
        bgrEntry("rmbg_2_0_q4", "RMBG 2.0 Q4", "briaai/RMBG-2.0", "onnx/model_q4.onnx", "BRIA RMBG 2.0 Q4 ONNX variant. Requires accepted Hugging Face access and a token.", 367_000_000L, gated = true),
        bgrEntry("rmbg_2_0_q4f16", "RMBG 2.0 Q4F16", "briaai/RMBG-2.0", "onnx/model_q4f16.onnx", "BRIA RMBG 2.0 Q4F16 ONNX variant. Requires accepted Hugging Face access and a token.", 234_000_000L, gated = true),
        bgrEntry("rmbg_2_0_quantized", "RMBG 2.0 Quantized", "briaai/RMBG-2.0", "onnx/model_quantized.onnx", "BRIA RMBG 2.0 quantized ONNX variant. Requires accepted Hugging Face access and a token.", 366_000_000L, gated = true),
        bgrEntry("rmbg_2_0_uint8", "RMBG 2.0 UINT8", "briaai/RMBG-2.0", "onnx/model_uint8.onnx", "BRIA RMBG 2.0 UINT8 ONNX variant. Requires accepted Hugging Face access and a token.", 366_000_000L, gated = true),
        bgrEntry("rmbg_1_4_full", "RMBG 1.4 Full", "briaai/RMBG-1.4", "onnx/model.onnx", "BRIA RMBG 1.4 full ONNX model. Requires accepted Hugging Face access and a token.", 176_000_000L, gated = true),
        bgrEntry("rmbg_1_4_fp16", "RMBG 1.4 FP16", "briaai/RMBG-1.4", "onnx/model_fp16.onnx", "BRIA RMBG 1.4 FP16 ONNX model. Requires accepted Hugging Face access and a token.", 88_200_000L, gated = true),
        bgrEntry("rmbg_1_4_quantized", "RMBG 1.4 Quantized", "briaai/RMBG-1.4", "onnx/model_quantized.onnx", "BRIA RMBG 1.4 quantized ONNX model. Requires accepted Hugging Face access and a token.", 44_400_000L, gated = true),
        bgrEntry("inspyrenet_swinb_full", "InSPyReNet SwinB Full", "OS-Software/InSPyReNet-SwinB-Plus-Ultra-ONNX", "onnx/model.onnx", "MIT-licensed InSPyReNet SwinB Plus Ultra ONNX segmentation model.", 395_000_000L),
        bgrEntry("inspyrenet_swinb_fp16", "InSPyReNet SwinB FP16", "OS-Software/InSPyReNet-SwinB-Plus-Ultra-ONNX", "onnx/model_fp16.onnx", "MIT-licensed InSPyReNet SwinB Plus Ultra FP16 ONNX model.", 199_000_000L)
    )

    val entries: List<OnnxCatalogEntry> = (sdaiEntries + manuEntries + supertonicEntries + backgroundRemovalEntries)
        .sortedWith(compareBy<OnnxCatalogEntry> { it.provider.id }.thenBy { it.title.lowercase(Locale.US) })

    fun entriesFor(provider: OnnxCatalogProvider): List<OnnxCatalogEntry> =
        entries
            .filter { it.provider == provider }
            .sortedBy { it.title.lowercase(Locale.US) }

    fun findByStableId(stableId: String): OnnxCatalogEntry? =
        entries.firstOrNull { it.stableId == stableId }

    fun findByLegacyOrStableId(modelId: String, provider: OnnxCatalogProvider? = null): OnnxCatalogEntry? {
        return entries.firstOrNull { entry ->
            (provider == null || entry.provider == provider) &&
                (entry.stableId == modelId || (entry.provider == OnnxCatalogProvider.SDAI && entry.bundleId == modelId))
        }
    }
}

fun buildOnnxCatalogStableId(provider: OnnxCatalogProvider, bundleId: String): String =
    "${provider.id}__$bundleId"

fun buildOnnxCatalogRepoId(provider: OnnxCatalogProvider, bundleId: String): String =
    "onnx_catalog/${provider.id}/$bundleId"

fun parseOnnxCatalogProvider(repoId: String?): OnnxCatalogProvider? {
    if (repoId.isNullOrBlank()) return null
    val normalized = repoId.trim()
    return when {
        normalized.startsWith("onnx_catalog/") ->
            OnnxCatalogProvider.fromId(normalized.substringAfter("onnx_catalog/").substringBefore('/'))
        normalized.startsWith("sdai_catalog/") -> OnnxCatalogProvider.SDAI
        else -> null
    }
}

fun parseOnnxCatalogBundleId(repoId: String?): String? {
    if (repoId.isNullOrBlank()) return null
    val normalized = repoId.trim()
    return when {
        normalized.startsWith("onnx_catalog/") -> normalized.substringAfterLast('/')
        normalized.startsWith("sdai_catalog/") -> normalized.substringAfterLast('/')
        else -> null
    }
}

fun resolveOnnxCatalogEntry(model: ModelEntity): OnnxCatalogEntry? {
    val provider = parseOnnxCatalogProvider(model.repoId)
    val bundleId = parseOnnxCatalogBundleId(model.repoId)
    if (provider != null && bundleId != null) {
        return OnnxCatalog.entriesFor(provider).firstOrNull { it.bundleId == bundleId }
    }
    return OnnxCatalog.findByLegacyOrStableId(model.filename, provider)
}

object OnnxStorage {
    fun legacyManagedModelsRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "SDAI/model"
    )

    fun managedModelsRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "ADT/onnx"
    )

    fun ensureManagedRootsReady() {
        val adtRoot = managedModelsRoot().parentFile
        listOfNotNull(adtRoot, managedModelsRoot(), stagingRoot()).forEach { dir ->
            dir.mkdirs()
            runCatching {
                File(dir, ".nomedia").apply {
                    if (!exists()) writeText("")
                }
            }
        }
    }

    fun managedBundleDir(bundleId: String): File = File(managedModelsRoot(), bundleId)

    fun stagingRoot(): File = File(managedModelsRoot(), ".staging")

    fun stagingBundleDir(bundleId: String): File = File(stagingRoot(), bundleId)

    fun tempDownloadDir(context: Context): File = File(context.cacheDir, "onnx_downloads")

    fun txt2ImgOutputDir(context: Context): File = File(context.filesDir, "onnx_image_output/txt2img")

    fun buildOutputFile(context: Context, prefix: String = "onnx"): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(txt2ImgOutputDir(context).apply { mkdirs() }, "${prefix}_$timestamp.png")
    }

    fun sharedExportRelativePath(
        mode: OnnxImageGenMode,
        fileName: String
    ): String = "images/onnx/${mode.storageToken}/$fileName"

    fun metadataFileFor(imageFile: File): File = File(
        imageFile.parentFile ?: imageFile.absoluteFile.parentFile,
        "${imageFile.name}.json"
    )

    fun writeMetadata(imageFile: File, metadata: OnnxGeneratedImageMetadata) {
        val sidecarFile = metadataFileFor(imageFile)
        sidecarFile.parentFile?.mkdirs()
        sidecarFile.writeText(metadata.toJsonString())
    }

    fun readMetadata(imageFile: File): OnnxGeneratedImageMetadata? {
        val sidecarFile = metadataFileFor(imageFile)
        if (!sidecarFile.isFile) return null
        return runCatching {
            OnnxGeneratedImageMetadata.fromJson(sidecarFile.readText())
        }.getOrNull()
    }

    fun deleteImageWithMetadata(imageFile: File): Boolean {
        val imageDeleted = !imageFile.exists() || imageFile.delete()
        val metadataFile = metadataFileFor(imageFile)
        if (metadataFile.exists()) {
            metadataFile.delete()
        }
        return imageDeleted
    }
}

object OnnxBundleValidator {
    val requiredRelativePaths = listOf(
        "text_encoder/model.ort",
        "unet/model.ort",
        "vae_decoder/model.ort",
        "tokenizer/vocab.json",
        "tokenizer/merges.txt"
    )
    const val img2imgEncoderRelativePath = "vae_encoder/model.ort"

    fun validateDirectory(bundleRoot: File): OnnxBundleValidationResult {
        val missing = requiredRelativePaths.filterNot { relative ->
            File(bundleRoot, relative).isFile
        }.toMutableList()
        val encoderFile = File(bundleRoot, img2imgEncoderRelativePath)
        val encoderRoot = File(bundleRoot, "vae_encoder")
        val supportsTxt2Img = missing.isEmpty()
        val supportsImg2Img = supportsTxt2Img && encoderFile.isFile
        if (supportsTxt2Img && encoderRoot.exists() && !encoderFile.isFile) {
            missing += img2imgEncoderRelativePath
        }
        val capabilities = buildSet {
            if (supportsTxt2Img) {
                add(ONNX_CAPABILITY_TXT2IMG)
            }
            if (supportsImg2Img) {
                add(ONNX_CAPABILITY_IMG2IMG)
            }
        }
        return OnnxBundleValidationResult(
            isValid = missing.isEmpty(),
            missingPaths = missing,
            bundleRoot = bundleRoot,
            supportedCapabilities = capabilities
        )
    }

    fun requirePaths(
        bundleRoot: File,
        mode: OnnxImageGenMode = OnnxImageGenMode.TXT2IMG
    ): OnnxBundlePaths {
        val result = validateDirectory(bundleRoot)
        require(result.isValid) {
            "Missing SDAI bundle files: ${result.missingPaths.joinToString(", ")}"
        }
        if (mode == OnnxImageGenMode.IMG2IMG) {
            require(result.supportedCapabilities.contains(ONNX_CAPABILITY_IMG2IMG)) {
                "This ONNX bundle does not include vae_encoder/model.ort for img2img"
            }
        }
        return OnnxBundlePaths(
            root = bundleRoot,
            textEncoderModel = File(bundleRoot, "text_encoder/model.ort"),
            unetModel = File(bundleRoot, "unet/model.ort"),
            vaeDecoderModel = File(bundleRoot, "vae_decoder/model.ort"),
            vaeEncoderModel = File(bundleRoot, img2imgEncoderRelativePath).takeIf { it.isFile },
            tokenizerVocab = File(bundleRoot, "tokenizer/vocab.json"),
            tokenizerMerges = File(bundleRoot, "tokenizer/merges.txt")
        )
    }
}

object OnnxTtsBundleValidator {
    val requiredRelativePaths = listOf(
        "onnx/duration_predictor.onnx",
        "onnx/text_encoder.onnx",
        "onnx/vector_estimator.onnx",
        "onnx/vocoder.onnx",
        "onnx/tts.json",
        "onnx/unicode_indexer.json"
    )

    fun validateDirectory(bundleRoot: File): OnnxBundleValidationResult {
        val missing = requiredRelativePaths.filterNot { relative ->
            File(bundleRoot, relative).isFile
        }.toMutableList()
        val voiceStylesDir = File(bundleRoot, "voice_styles")
        val hasVoices = voiceStylesDir.isDirectory &&
            voiceStylesDir.listFiles().orEmpty().any { it.isFile && it.extension.equals("json", ignoreCase = true) }
        if (!hasVoices) {
            missing += "voice_styles/*.json"
        }
        return OnnxBundleValidationResult(
            isValid = missing.isEmpty(),
            missingPaths = missing,
            bundleRoot = bundleRoot,
            supportedCapabilities = if (missing.isEmpty()) setOf(ONNX_CAPABILITY_TTS) else emptySet()
        )
    }

    fun requirePaths(bundleRoot: File): OnnxTtsBundlePaths {
        val result = validateDirectory(bundleRoot)
        require(result.isValid) {
            "Missing Supertonic bundle files: ${result.missingPaths.joinToString(", ")}"
        }
        val onnxDir = File(bundleRoot, "onnx")
        return OnnxTtsBundlePaths(
            root = bundleRoot,
            onnxDir = onnxDir,
            durationPredictor = File(onnxDir, "duration_predictor.onnx"),
            textEncoder = File(onnxDir, "text_encoder.onnx"),
            vectorEstimator = File(onnxDir, "vector_estimator.onnx"),
            vocoder = File(onnxDir, "vocoder.onnx"),
            config = File(onnxDir, "tts.json"),
            unicodeIndexer = File(onnxDir, "unicode_indexer.json"),
            voiceStylesDir = File(bundleRoot, "voice_styles")
        )
    }
}

object OnnxImportSupport {
    fun sanitizeBundleId(rawName: String): String {
        val trimmed = rawName.trim().ifBlank { "onnx_bundle" }
        val sanitized = trimmed
            .replace(Regex("\\.(zip|ort|onnx)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
        return sanitized.ifBlank { "onnx_bundle" }
    }

    fun chooseImportStrategy(
        resolvedPath: String?,
        hasAllFilesAccess: Boolean,
        isPathAccessible: Boolean
    ): OnnxImportStrategy {
        return if (!resolvedPath.isNullOrBlank() && hasAllFilesAccess && isPathAccessible) {
            OnnxImportStrategy.LINK_IN_PLACE
        } else {
            OnnxImportStrategy.COPY_TO_MANAGED
        }
    }

    fun makeUniqueBundleId(baseId: String, existingIds: Collection<String>): String {
        if (baseId !in existingIds) return baseId
        var suffix = 2
        while (true) {
            val candidate = "${baseId}_$suffix"
            if (candidate !in existingIds) return candidate
            suffix += 1
        }
    }

    fun recursiveSize(target: File): Long {
        if (!target.exists()) return 0L
        if (target.isFile) return target.length()
        return target.listFiles().orEmpty().sumOf(::recursiveSize)
    }

    fun deleteRecursively(target: File): Boolean {
        if (!target.exists()) return true
        if (target.isDirectory) {
            target.listFiles().orEmpty().forEach { child ->
                if (!deleteRecursively(child)) return false
            }
        }
        return target.delete()
    }

    fun copyDirectory(sourceDir: File, targetDir: File, onProgress: (Float) -> Unit = {}) {
        require(sourceDir.isDirectory) { "Source must be a directory: ${sourceDir.absolutePath}" }
        val totalBytes = recursiveSize(sourceDir).coerceAtLeast(1L)
        var copiedBytes = 0L

        fun copyRecursively(currentSource: File, currentTarget: File) {
            if (currentSource.isDirectory) {
                currentTarget.mkdirs()
                currentSource.listFiles().orEmpty().forEach { child ->
                    copyRecursively(child, File(currentTarget, child.name))
                }
            } else {
                currentTarget.parentFile?.mkdirs()
                FileInputStream(currentSource).use { input ->
                    FileOutputStream(currentTarget).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read = input.read(buffer)
                        while (read >= 0) {
                            output.write(buffer, 0, read)
                            copiedBytes += read
                            onProgress(copiedBytes.toFloat() / totalBytes.toFloat())
                            read = input.read(buffer)
                        }
                    }
                }
            }
        }

        copyRecursively(sourceDir, targetDir)
        onProgress(1f)
    }

    fun calculateDocumentTreeSize(root: DocumentFile): Long {
        if (root.isFile) return root.length()
        return root.listFiles().sumOf(::calculateDocumentTreeSize)
    }

    fun copyDocumentTreeToDirectory(
        context: Context,
        sourceRoot: DocumentFile,
        targetDir: File,
        onProgress: (Float) -> Unit = {}
    ) {
        val totalBytes = calculateDocumentTreeSize(sourceRoot).coerceAtLeast(1L)
        var copiedBytes = 0L

        fun copyNode(node: DocumentFile, destination: File) {
            if (node.isDirectory) {
                destination.mkdirs()
                node.listFiles().forEach { child ->
                    copyNode(child, File(destination, child.name ?: "item"))
                }
            } else if (node.isFile) {
                destination.parentFile?.mkdirs()
                context.contentResolver.openInputStream(node.uri)?.use { input ->
                    copyStreamToFile(input, destination) { delta ->
                        copiedBytes += delta
                        onProgress(copiedBytes.toFloat() / totalBytes.toFloat())
                    }
                } ?: error("Unable to open ${node.uri}")
            }
        }

        copyNode(sourceRoot, targetDir)
        onProgress(1f)
    }

    fun extractBundleArchive(
        archiveFile: File,
        installDir: File,
        onPhase: (String) -> Unit = {},
        ensureActive: () -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): Long {
        val stagingRoot = installDir.parentFile?.let { File(it, ".staging") } ?: OnnxStorage.stagingRoot()
        val stagingDir = File(stagingRoot, installDir.name)
        deleteRecursively(stagingDir)
        deleteRecursively(installDir)
        stagingDir.mkdirs()

        try {
            onPhase("extracting")
            val totalBytes = archiveFile.length().coerceAtLeast(1L)
            val progressReporter = ThrottledProgressReporter(
                totalBytes = totalBytes,
                phaseSpan = 0.98f,
                onProgress = onProgress
            )

            BufferedInputStream(FileInputStream(archiveFile)).use { bufferedInput ->
                ZipArchiveInputStream(bufferedInput).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        ensureActive()
                        val destination = safeResolve(stagingDir, entry.name)
                        if (entry.isDirectory) {
                            destination.mkdirs()
                        } else {
                            destination.parentFile?.mkdirs()
                            copyStreamToFile(zip, destination) { delta ->
                                ensureActive()
                                progressReporter.onBytesCopied(delta)
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            progressReporter.complete()

            onPhase("validating")
            val actualRoot = locateBundleRoot(stagingDir)
                ?: error("Archive does not contain an SDAI Local Diffusion bundle")
            installDir.parentFile?.mkdirs()
            Files.move(
                actualRoot.toPath(),
                installDir.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            val imageValidation = OnnxBundleValidator.validateDirectory(installDir)
            val ttsValidation = OnnxTtsBundleValidator.validateDirectory(installDir)
            require(imageValidation.isValid || ttsValidation.isValid) {
                val missing = if (ttsValidation.missingPaths.size < imageValidation.missingPaths.size) {
                    ttsValidation.missingPaths
                } else {
                    imageValidation.missingPaths
                }
                "Missing ONNX bundle files after extraction: ${missing.joinToString(", ")}"
            }
            onPhase("completed")
            onProgress(1f)
            return progressReporter.extractedBytes
        } catch (e: Exception) {
            deleteRecursively(installDir)
            throw e
        } finally {
            deleteRecursively(stagingDir)
        }
    }

    private fun locateBundleRoot(stagingDir: File): File? {
        if (OnnxBundleValidator.validateDirectory(stagingDir).isValid ||
            OnnxTtsBundleValidator.validateDirectory(stagingDir).isValid
        ) {
            return stagingDir
        }
        val directChildren = stagingDir.listFiles().orEmpty().filter { it.isDirectory }
        if (directChildren.size == 1 && (
                OnnxBundleValidator.validateDirectory(directChildren.first()).isValid ||
                    OnnxTtsBundleValidator.validateDirectory(directChildren.first()).isValid
                )
        ) {
            return directChildren.first()
        }
        return directChildren.firstOrNull {
            OnnxBundleValidator.validateDirectory(it).isValid ||
                OnnxTtsBundleValidator.validateDirectory(it).isValid
        }
    }

    private fun safeResolve(root: File, entryName: String): File {
        val normalized = File(root, entryName)
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = normalized.canonicalFile
        require(
            canonicalTarget.absolutePath == canonicalRoot.absolutePath ||
                canonicalTarget.absolutePath.startsWith(canonicalRoot.absolutePath + File.separator)
        ) {
            "Blocked unsafe archive entry: $entryName"
        }
        return canonicalTarget
    }

    private fun copyStreamToFile(
        input: InputStream,
        destination: File,
        onChunk: (Long) -> Unit
    ) {
        BufferedOutputStream(FileOutputStream(destination), EXTRACTION_BUFFER_SIZE).use { output ->
            val buffer = ByteArray(EXTRACTION_BUFFER_SIZE)
            var read = input.read(buffer)
            while (read >= 0) {
                output.write(buffer, 0, read)
                onChunk(read.toLong())
                read = input.read(buffer)
            }
            output.flush()
        }
    }

    private class ThrottledProgressReporter(
        private val totalBytes: Long,
        private val phaseSpan: Float,
        private val onProgress: (Float) -> Unit
    ) {
        private var copiedBytes = 0L
        private var lastReportedBytes = 0L
        private var lastReportedAtMs = 0L
        private var lastReportedProgress = 0f

        fun onBytesCopied(delta: Long) {
            copiedBytes += delta
            maybeReport(force = copiedBytes >= totalBytes)
        }

        fun complete() {
            copiedBytes = totalBytes
            maybeReport(force = true)
        }

        val extractedBytes: Long
            get() = copiedBytes

        private fun maybeReport(force: Boolean) {
            val nowMs = System.nanoTime() / 1_000_000L
            val progress = ((copiedBytes.toFloat() / totalBytes.toFloat()) * phaseSpan)
                .coerceIn(0f, phaseSpan)
            val bytesDelta = copiedBytes - lastReportedBytes
            val timeDelta = nowMs - lastReportedAtMs
            val progressDelta = abs(progress - lastReportedProgress)
            if (
                force ||
                bytesDelta >= MIN_PROGRESS_BYTES ||
                timeDelta >= MIN_PROGRESS_INTERVAL_MS ||
                progressDelta >= MIN_PROGRESS_DELTA
            ) {
                lastReportedBytes = copiedBytes
                lastReportedAtMs = nowMs
                lastReportedProgress = progress
                onProgress(progress)
            }
        }
    }

    private const val EXTRACTION_BUFFER_SIZE = 256 * 1024
    private const val MIN_PROGRESS_BYTES = 32L * 1024L * 1024L
    private const val MIN_PROGRESS_INTERVAL_MS = 1000L
    private const val MIN_PROGRESS_DELTA = 0.05f
}

fun resolveOnnxBackend(
    requestedBackend: OnnxRuntimeBackend,
    componentLabel: String,
    nnapiErrorMessage: String? = null
): OnnxBackendResolution {
    return if (requestedBackend == OnnxRuntimeBackend.NNAPI && !nnapiErrorMessage.isNullOrBlank()) {
        OnnxBackendResolution(
            resolvedBackend = OnnxRuntimeBackend.CPU,
            warningMessage = "NNAPI fallback to CPU for $componentLabel: $nnapiErrorMessage"
        )
    } else {
        OnnxBackendResolution(
            resolvedBackend = requestedBackend,
            warningMessage = null
        )
    }
}

fun ModelEntity.isOnnxTxt2ImgBundle(): Boolean {
    return type == ModelType.ONNX_IMAGE_GEN &&
        onnxPipelineFamily == ONNX_PIPELINE_FAMILY_SDAI_LOCAL_DIFFUSION &&
        hasOnnxCapability(ONNX_CAPABILITY_TXT2IMG)
}

fun ModelEntity.isOnnxImg2ImgBundle(): Boolean {
    return isOnnxTxt2ImgBundle() && hasOnnxCapability(ONNX_CAPABILITY_IMG2IMG)
}

fun ModelEntity.isOnnxBackgroundRemovalModel(): Boolean {
    return type == ModelType.ONNX_BACKGROUND_REMOVAL &&
        onnxPipelineFamily == ONNX_PIPELINE_FAMILY_BACKGROUND_REMOVAL &&
        hasOnnxCapability(ONNX_CAPABILITY_BACKGROUND_REMOVAL)
}

fun ModelEntity.isTamaDefaultPicGenModel(): Boolean {
    if (!isOnnxTxt2ImgBundle()) return false
    resolveOnnxCatalogEntry(this)?.let { entry ->
        if (entry.bundleId.equals(TamaPicGenDefaults.DEFAULT_MODEL_BUNDLE_ID, ignoreCase = true)) {
            return true
        }
    }
    return filename.contains(TamaPicGenDefaults.DEFAULT_MODEL_BUNDLE_ID, ignoreCase = true)
}

fun buildOnnxImageGenModelEntity(
    filename: String,
    path: String,
    sizeBytes: Long,
    repoId: String,
    installSource: OnnxInstallSource,
    supportedCapabilities: Set<String>,
    referenceUri: String?,
    referencePath: String?
): ModelEntity {
    return ModelEntity(
        filename = filename,
        path = path,
        sizeBytes = sizeBytes,
        type = ModelType.ONNX_IMAGE_GEN,
        repoId = repoId,
        isDownloaded = true,
        onnxCapabilities = buildOnnxCapabilities(*supportedCapabilities.toTypedArray()),
        onnxAssetKind = when (installSource) {
            OnnxInstallSource.SDAI_CATALOG -> ONNX_ASSET_KIND_SDAI_CATALOG_BUNDLE
            OnnxInstallSource.CUSTOM_IMPORT -> ONNX_ASSET_KIND_CUSTOM_IMPORT_BUNDLE
        },
        onnxPipelineFamily = ONNX_PIPELINE_FAMILY_SDAI_LOCAL_DIFFUSION,
        onnxReferenceUri = referenceUri,
        onnxReferencePath = referencePath
    )
}

fun buildOnnxTxt2ImgModelEntity(
    filename: String,
    path: String,
    sizeBytes: Long,
    repoId: String,
    installSource: OnnxInstallSource,
    referenceUri: String?,
    referencePath: String?
): ModelEntity = buildOnnxImageGenModelEntity(
    filename = filename,
    path = path,
    sizeBytes = sizeBytes,
    repoId = repoId,
    installSource = installSource,
    supportedCapabilities = setOf(ONNX_CAPABILITY_TXT2IMG),
    referenceUri = referenceUri,
    referencePath = referencePath
)
