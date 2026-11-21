package com.example.llamadroid.sd

import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.easy.EasyImageKind
import com.example.llamadroid.data.easy.EasyImagePresets
import com.example.llamadroid.data.model.SdCuratedBundleCatalog
import com.example.llamadroid.service.SDConfig
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.buildSdCommandArgs
import com.example.llamadroid.service.sanitizeSdSigmas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdBundleGenerationDefaultsTest {
    private fun qwenConfig(defaults: SdImageDefaults, mode: SDMode = SDMode.TXT2IMG) = SDConfig(
        modelPath = "/m/qwen.gguf",
        prompt = "a cat",
        outputPath = "/o.png",
        mode = mode,
        initImage = if (mode == SDMode.IMG2IMG) "/in.png" else null,
        modelFamily = "qwen_image",
        modelVariant = "2.1",
        llmPath = "/m/llm.gguf",
        llmVisionPath = "/m/mmproj.gguf",
        vaePath = "/m/vae.safetensors"
    ).withImageDefaults(defaults)

    private fun List<String>.valueOf(flag: String): String? = indexOf(flag).takeIf { it >= 0 }?.let { get(it + 1) }

    @Test
    fun turboArgsCarryNamedSigmasEightStepsAndCfgOne() {
        val d = SdBundleGenerationDefaults.forBundle(SdBundleGenerationDefaults.QWEN_TURBO_BUNDLE_ID)!!
        val args = buildSdCommandArgs(qwenConfig(d))
        assertEquals("qwen-image-2.1-turbo", args.valueOf("--sigmas"))
        assertEquals("8", args.valueOf("--steps"))
        assertEquals("1.0", args.valueOf("--cfg-scale"))
        assertEquals("1024", args.valueOf("-W"))
    }

    @Test
    fun baseQwenArgsHaveComponentsAndNoSigmas() {
        val d = SdBundleGenerationDefaults.forBundle("qwen-image-21-q4-vision")!!
        val args = buildSdCommandArgs(qwenConfig(d))
        assertTrue(args.contains("--llm"))
        assertTrue(args.contains("--vae"))
        assertFalse(args.contains("--sigmas"))
        assertEquals("20", args.valueOf("--steps"))
        assertEquals("6.0", args.valueOf("--cfg-scale"))
        val img2img = buildSdCommandArgs(qwenConfig(d, SDMode.IMG2IMG))
        assertTrue(img2img.contains("--llm_vision"))
    }

    @Test
    fun sigmasSanitizerAcceptsFloatListsAndPresetsOnly() {
        assertNull(sanitizeSdSigmas(""))
        assertEquals("1.0,0.5,0", sanitizeSdSigmas(" 1.0, 0.5, 0 "))
        assertEquals("qwen-image-2.1-turbo", sanitizeSdSigmas("qwen-image-2.1-turbo"))
        assertNull(sanitizeSdSigmas("Bad Preset;rm"))
        assertNull(sanitizeSdSigmas("1.0,abc"))
    }

    @Test
    fun distributedSettingsReceiveBundleNumbers() {
        val flux = SdBundleGenerationDefaults.forBundle("high-end")!!
        val s = SdDistributedMasterSettingsEntity().withImageDefaults(flux)
        assertEquals("768", s.imageWidth)
        assertEquals("768", s.imageHeight)
        assertEquals("4", s.imageSteps)
        assertEquals("1.0", s.imageCfg)
        assertEquals("euler", s.imageSampler)
        val sd15 = SdBundleGenerationDefaults.forBundle("low-end")!!
        assertEquals("25", SdDistributedMasterSettingsEntity().withImageDefaults(sd15).imageSteps)
        val lcm = SdBundleGenerationDefaults.forBundle(SdBundleGenerationDefaults.SD15_LCM_BUNDLE_ID)!!
        assertEquals(SamplingMethod.LCM, lcm.sampler)
        assertEquals(SdBundleGenerationDefaults.LCM_LORA_FILE_ID, lcm.loraFileId)
    }

    @Test
    fun everyDefaultsKeyExistsInTheCatalogAndQwenSizesAreMultiplesOf32() {
        val bundleIds = SdCuratedBundleCatalog.bundles.map { it.id }.toSet()
        val fileIds = SdCuratedBundleCatalog.bundles.flatMap { b -> b.files.map { it.id } }.toSet()
        listOf("low-end", "ipa-starter", "midrange", "high-end", "distributed", "qwen-image-21-q4-vision",
            SdBundleGenerationDefaults.QWEN_TURBO_BUNDLE_ID, SdBundleGenerationDefaults.SD15_LCM_BUNDLE_ID)
            .forEach { assertTrue(it, it in bundleIds); assertNotNull(SdBundleGenerationDefaults.forBundle(it)) }
        listOf("sd15-q4", "sdxl-turbo-q4", "flux-schnell-q4", "flux-schnell-q6", "qwen-image-21-diffusion-q4",
            SdBundleGenerationDefaults.QWEN_TURBO_FILE_ID, SdBundleGenerationDefaults.LCM_LORA_FILE_ID)
            .forEach { assertTrue(it, it in fileIds) }
        SdBundleGenerationDefaults.forBundle("qwen-image-21-q4-vision")!!.quickSizes
            .forEach { (w, h) -> assertEquals(0, w % 32); assertEquals(0, h % 32) }
    }

    @Test
    fun easyPresetsReadSharedNumbers() {
        val sd15 = EasyImagePresets.forKind(EasyImageKind.SD15_SDCPP)
        assertEquals(25, sd15.steps)
        assertEquals(7f, sd15.cfgScale, 0f)
        assertEquals(SamplingMethod.EULER_A, sd15.sampler)
        assertEquals(4, EasyImagePresets.forKind(EasyImageKind.SDXL_TURBO).steps)
        assertEquals(SamplingMethod.EULER, EasyImagePresets.forKind(EasyImageKind.FLUX_SCHNELL).sampler)
        assertEquals(20, EasyImagePresets.forKind(EasyImageKind.ONNX_SD15).steps)
    }

    @Test
    fun newBundlesHavePinnedShaSizeAndSharedEntries() {
        val turbo = SdCuratedBundleCatalog.byId("qwen-image-21-turbo")!!
        val main = turbo.files.single { it.componentRole == "diffusion" }
        assertEquals("unsloth/Qwen-Image-2.1-Turbo-GGUF", main.repoId)
        assertEquals("qwen-image-2.1-turbo-Q4_K_M.gguf", main.remotePath)
        assertEquals(4_199_565_120L, main.sizeBytes)
        assertEquals("8940e8b522af886e0c0f5afddf720e82dd223cb9a43580b5f61c4d740452ac20", main.sha256)
        val base = SdCuratedBundleCatalog.byId("qwen-image-21-q4-vision")!!
        assertEquals(
            base.files.filter { it.componentRole != "diffusion" }.map { it.sha256 }.toSet(),
            turbo.files.filter { it.componentRole != "diffusion" }.map { it.sha256 }.toSet()
        )
        val lcm = SdCuratedBundleCatalog.byId("sd15-lcm")!!
        val lora = lcm.files.single { it.id == "lcm-lora-sd15" }
        assertEquals(134_621_556L, lora.sizeBytes)
        assertEquals("8f90d840e075ff588a58e22c6586e2ae9a6f7922996ee6649a7f01072333afe4", lora.sha256)
        assertEquals("checkpoint:sd1", lora.sdCompatProfiles)
        assertEquals("sd15-q4", lcm.files.first().id)
    }

    @Test
    fun wanTi2vDefaultsAre832x480() {
        val d = SdVideoFamilyProfiles.resolve(SdVideoFamily.WAN, "wan2_2_ti2v").defaultValues
        assertEquals(832, d.width)
        assertEquals(480, d.height)
        assertEquals(20, d.steps)
        assertEquals(6.0f, d.cfgScale, 0f)
        assertEquals(3.0f, d.flowShift!!, 0f)
        assertEquals(33, d.frames)
        assertEquals(24, d.fps)
    }
}
