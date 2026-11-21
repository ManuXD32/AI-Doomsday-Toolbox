package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.model.SdCuratedBundleCatalog
import com.example.llamadroid.service.SDConfig
import com.example.llamadroid.service.SamplingMethod
import java.util.Locale

/**
 * Recommended image generation numbers for one curated bundle or main file
 * (single source: model cards and stable-diffusion.cpp docs/models). [quickSizes] lists extra
 * canvases (first entry is the default size); every Qwen size is a multiple of 32.
 */
data class SdImageDefaults(
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfg: Float,
    val sampler: SamplingMethod,
    val schedulerCliName: String? = null,
    val flowShift: Float? = null,
    /** Float list or named preset passed as `--sigmas`; blank when unused. */
    val sigmas: String = "",
    val quickSizes: List<Pair<Int, Int>> = listOf(width to height),
    /** False for models trained without a negative prompt (SDXL-Turbo, Turbo distills). */
    val usesNegativePrompt: Boolean = true,
    /** Curated LoRA file id (SdCuratedBundleFile.id) the preset expects at [loraStrength], or null. */
    val loraFileId: String? = null,
    val loraStrength: Float = 1.0f
)

object SdBundleGenerationDefaults {
    const val QWEN_TURBO_SIGMAS = "qwen-image-2.1-turbo"
    const val QWEN_TURBO_BUNDLE_ID = "qwen-image-21-turbo"
    const val SD15_LCM_BUNDLE_ID = "sd15-lcm"
    const val QWEN_TURBO_FILE_ID = "qwen-image-21-turbo-q4"
    const val LCM_LORA_FILE_ID = "lcm-lora-sd15"

    private val SD15 = SdImageDefaults(
        512, 512, 25, 7.0f, SamplingMethod.EULER_A,
        quickSizes = listOf(512 to 512, 512 to 768, 768 to 512)
    )
    private val SD15_LCM = SdImageDefaults(
        512, 512, 4, 1.0f, SamplingMethod.LCM,
        quickSizes = listOf(512 to 512, 512 to 768, 768 to 512),
        loraFileId = LCM_LORA_FILE_ID, loraStrength = 1.0f
    )
    private val SDXL_TURBO = SdImageDefaults(
        512, 512, 4, 1.0f, SamplingMethod.EULER_A,
        quickSizes = listOf(512 to 512, 768 to 768), usesNegativePrompt = false
    )
    private val FLUX_SCHNELL = SdImageDefaults(
        768, 768, 4, 1.0f, SamplingMethod.EULER,
        quickSizes = listOf(768 to 768, 512 to 512, 1024 to 1024)
    )
    private val QWEN_SIZES = listOf(1024 to 1024, 1344 to 768, 768 to 1344, 2048 to 2048)
    private val QWEN_21 = SdImageDefaults(
        1024, 1024, 20, 6.0f, SamplingMethod.EULER, quickSizes = QWEN_SIZES
    )
    private val QWEN_21_TURBO = SdImageDefaults(
        1024, 1024, 8, 1.0f, SamplingMethod.EULER, sigmas = QWEN_TURBO_SIGMAS,
        quickSizes = QWEN_SIZES, usesNegativePrompt = false
    )

    private val byBundleId: Map<String, SdImageDefaults> = mapOf(
        "low-end" to SD15,
        "ipa-starter" to SD15,
        SD15_LCM_BUNDLE_ID to SD15_LCM,
        "midrange" to SDXL_TURBO,
        "high-end" to FLUX_SCHNELL,
        "distributed" to FLUX_SCHNELL,
        "qwen-image-21-q4-vision" to QWEN_21,
        QWEN_TURBO_BUNDLE_ID to QWEN_21_TURBO
    )

    /** Main-file ids differ from bundle ids so a chosen file resolves without knowing the bundle. */
    private val byFileId: Map<String, SdImageDefaults> = mapOf(
        "sd15-q4" to SD15,
        "sdxl-turbo-q4" to SDXL_TURBO,
        "flux-schnell-q4" to FLUX_SCHNELL,
        "flux-schnell-q6" to FLUX_SCHNELL,
        "qwen-image-21-diffusion-q4" to QWEN_21,
        QWEN_TURBO_FILE_ID to QWEN_21_TURBO
    )

    fun forBundle(bundleId: String?): SdImageDefaults? = bundleId?.let(byBundleId::get)

    fun forFileId(fileId: String?): SdImageDefaults? = fileId?.let(byFileId::get)

    /** Defaults of the curated file installed under [main]'s filename, or null for custom imports. */
    fun forMain(main: ModelEntity?): SdImageDefaults? {
        val filename = main?.filename ?: return null
        return forFileId(SdCuratedBundleCatalog.fileForLocalFilename(filename)?.id)
    }
}

/**
 * `--sigmas` value for [main]: its bundle file entry, or a Qwen Image 2.1 file whose name
 * contains "turbo". Blank when the model needs none.
 */
fun sdBundleSigmasFor(main: ModelEntity): String {
    SdBundleGenerationDefaults.forMain(main)?.sigmas?.takeIf { it.isNotBlank() }?.let { return it }
    val isQwen21 = main.sdFamily == "qwen_image" && main.sdVariant?.startsWith("2.1") == true
    return if (isQwen21 && main.filename.lowercase(Locale.US).contains("turbo")) {
        SdBundleGenerationDefaults.QWEN_TURBO_SIGMAS
    } else {
        ""
    }
}

private fun Float.cleanString(): String =
    if (this == toInt().toFloat()) "%.1f".format(Locale.US, this) else toString()

/**
 * Applies bundle defaults to the distributed image fields (size, steps, cfg, sampler,
 * scheduler, flow shift). Call it only when the selected model/bundle changes so later user
 * edits are kept. Sigmas are not stored; they are resolved at launch by [sdBundleSigmasFor].
 */
fun SdDistributedMasterSettingsEntity.withImageDefaults(d: SdImageDefaults): SdDistributedMasterSettingsEntity =
    copy(
        imageWidth = d.width.toString(),
        imageHeight = d.height.toString(),
        imageSteps = d.steps.toString(),
        imageCfg = d.cfg.cleanString(),
        imageSampler = d.sampler.cliName,
        imageScheduler = d.schedulerCliName.orEmpty(),
        imageFlowShift = d.flowShift?.cleanString().orEmpty()
    )

/** Local ImageGen equivalent: returns [config] with the bundle numbers and sigmas applied. */
fun SDConfig.withImageDefaults(d: SdImageDefaults): SDConfig = copy(
    width = d.width,
    height = d.height,
    steps = d.steps,
    cfgScale = d.cfg,
    samplingMethod = d.sampler,
    flowShift = d.flowShift ?: flowShift,
    sigmas = d.sigmas
)
