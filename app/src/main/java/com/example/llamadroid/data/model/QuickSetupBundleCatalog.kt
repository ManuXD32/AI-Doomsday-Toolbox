package com.example.llamadroid.data.model

import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelType

/**
 * Cross-family "ADT starter bundle" for new users: a vision + MTP chat model, a small
 * Stable Diffusion checkpoint and a multilingual Whisper model.
 *
 * Every file reuses the pinned coordinates of an existing family catalog entry and installs
 * under the same filename as that canonical bundle (via [CuratedBundleFile.fixedInstallPrefix]),
 * so installed state and download tasks are shared with the LLM, SD and Whisper managers.
 * Drift against those catalogs is guarded by QuickSetupBundleCatalogTest.
 */
object QuickSetupBundleCatalog {
    const val BUNDLE_ID = "adt-starter"

    /** Install prefix of the canonical `gemma4-e2b-complete` bundle. */
    const val GEMMA_PREFIX = "Gemma4-E2B"

    /** Install prefix of the canonical SD `low-end` bundle. */
    const val SD_PREFIX = "Low-End"

    val gemmaMain = CuratedBundleFile(
        id = "starter-gemma4-e2b-main",
        repoId = "unsloth/gemma-4-E2B-it-GGUF",
        revision = "main",
        remotePath = "gemma-4-E2B-it-Q4_K_M.gguf",
        localFilename = "gemma-4-E2B-it-Q4_K_M.gguf",
        type = ModelType.LLM,
        sizeBytes = 3_106_738_272L,
        sha256 = "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8",
        license = "Apache-2.0",
        isVision = true,
        fixedInstallPrefix = GEMMA_PREFIX
    )

    val gemmaMmproj = CuratedBundleFile(
        id = "starter-gemma4-e2b-mmproj",
        repoId = "unsloth/gemma-4-E2B-it-GGUF",
        revision = "main",
        remotePath = "mmproj-BF16.gguf",
        localFilename = "mmproj-gemma-4-E2B-it-BF16.gguf",
        type = ModelType.VISION_PROJECTOR,
        sizeBytes = 986_833_728L,
        sha256 = "a402f10fb5780bf91d03a10cd89061139f522bee2e679b1291bbfdcd71d9547d",
        license = "Apache-2.0",
        fixedInstallPrefix = GEMMA_PREFIX
    )

    val gemmaMtp = CuratedBundleFile(
        id = "starter-gemma4-e2b-mtp",
        repoId = "unsloth/gemma-4-E2B-it-GGUF",
        revision = "main",
        remotePath = "mtp-gemma-4-E2B-it.gguf",
        localFilename = "mtp-gemma-4-E2B-it.gguf",
        type = ModelType.LLM_DRAFT,
        sizeBytes = 97_817_664L,
        sha256 = "9eba819938efccfd6044f8af84e3bbfddc639a2bcf32ebc36420e6a649191919",
        license = "Apache-2.0",
        fixedInstallPrefix = GEMMA_PREFIX
    )

    /** Stable Diffusion 1.5 Q4_0, the smallest curated txt2img checkpoint (self-contained GGUF). */
    val imageModel: CuratedBundleFile = SdCuratedBundleCatalog.smallestCheckpointFile.let { sd ->
        CuratedBundleFile(
            id = "starter-${sd.id}",
            repoId = sd.repoId,
            revision = sd.revision,
            remotePath = sd.remotePath,
            localFilename = sd.sourceFilename,
            type = sd.modelType,
            sizeBytes = sd.sizeBytes,
            sha256 = sd.sha256,
            license = sd.licenseLabel,
            fixedInstallPrefix = SD_PREFIX,
            sdCapabilities = sd.sdCapabilities,
            sdFamily = sd.sdFamily,
            sdVariant = sd.sdVariant,
            sdCompatProfiles = sd.sdCompatProfiles
        )
    }

    /** Multilingual (not `.en`) Whisper small, Q5_1. Same name/source as the Whisper manager. */
    val whisper = CuratedBundleFile(
        id = "starter-whisper-small-q5_1",
        repoId = "ggerganov/whisper.cpp",
        revision = "main",
        remotePath = "ggml-small-q5_1.bin",
        localFilename = "ggml-small-q5_1.bin",
        type = ModelType.WHISPER,
        sizeBytes = 190_085_487L,
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        license = "MIT",
        fixedInstallPrefix = ""
    )

    val bundle = CuratedModelBundle(
        id = BUNDLE_ID,
        titleRes = R.string.quick_setup_bundle_title,
        descriptionRes = R.string.quick_setup_bundle_desc,
        defaultPrefix = "ADT-Starter",
        capabilityRes = listOf(
            R.string.curated_bundle_capability_vision,
            R.string.curated_bundle_capability_mtp,
            R.string.quick_setup_capability_image,
            R.string.quick_setup_capability_speech
        ),
        files = listOf(gemmaMain, gemmaMmproj, gemmaMtp, imageModel, whisper)
    )

    val bundles: List<CuratedModelBundle> = listOf(bundle)
}
