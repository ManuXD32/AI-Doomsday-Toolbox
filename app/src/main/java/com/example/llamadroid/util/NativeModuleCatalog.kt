package com.example.llamadroid.util

import android.content.Context
import com.example.llamadroid.BuildConfig

/**
 * The single source of truth for native feature payloads.  The binaries are
 * executable ELF files despite their .so package suffix; their dependencies
 * must be contained in this definition or be an Android system library.
 */
enum class NativeEngineFamily { LLM, MEDIA }

enum class NativeBackendKind { CPU, OPENCL, VULKAN }

enum class NativeCpuTier { BASELINE, DOTPROD, ARMV9, I8MM }

enum class NativeModuleDelivery { PLAY_MANAGED, EMBEDDED, SIDELOADED }

data class NativeModuleDefinition(
    val moduleName: String,
    val family: NativeEngineFamily,
    val backend: NativeBackendKind,
    val tier: NativeCpuTier? = null,
    val experimental: Boolean = false,
    val expectedFiles: Set<String>,
    val mayBeAutoProvisioned: Boolean,
    val supportsBenchmark: Boolean = false
) {
    fun isCompatible(): Boolean = when (tier) {
        NativeCpuTier.DOTPROD -> runCatching { CpuFeatures.hasDotProd() }.getOrDefault(false)
        NativeCpuTier.ARMV9 -> runCatching { CpuFeatures.hasArmV9() }.getOrDefault(false)
        NativeCpuTier.I8MM -> runCatching { CpuFeatures.hasI8mm() }.getOrDefault(false)
        NativeCpuTier.BASELINE, null -> true
    } && when (backend) {
        NativeBackendKind.CPU -> true
        // Accelerator executables are currently compiled with dot-product CPU
        // flags, so a Qualcomm name alone is not a sufficient safety check.
        NativeBackendKind.OPENCL,
        NativeBackendKind.VULKAN -> DeviceAcceleration.supportsSnapdragonNativeBinaries()
    }
}

object NativeModuleCatalog {
    private fun llmCpuPayload(tier: String) = setOf(
        "libllama_server_${tier}.so",
        "librpc-server_${tier}.so",
        "libmtmd_${tier}.so",
        "libwhisper-cli_${tier}.so",
        "libllama-bench_${tier}.so",
        "libquadtrix_trainer_${tier}.so"
    )

    private fun mediaCpuPayload(tier: String) = setOf(
        "libsd_${tier}.so",
        "libsd-rpc-server_${tier}.so",
        "libffmpeg_${tier}.so",
        "libffprobe_${tier}.so",
        "libwhisper-cli_${tier}.so"
    )

    val definitions: List<NativeModuleDefinition> = listOf(
        NativeModuleDefinition(
            moduleName = "feature_llm_baseline",
            family = NativeEngineFamily.LLM,
            backend = NativeBackendKind.CPU,
            tier = NativeCpuTier.BASELINE,
            expectedFiles = llmCpuPayload("baseline"),
            mayBeAutoProvisioned = true,
            supportsBenchmark = true
        ),
        NativeModuleDefinition(
            moduleName = "feature_llm_dotprod",
            family = NativeEngineFamily.LLM,
            backend = NativeBackendKind.CPU,
            tier = NativeCpuTier.DOTPROD,
            expectedFiles = llmCpuPayload("dotprod"),
            mayBeAutoProvisioned = true,
            supportsBenchmark = true
        ),
        NativeModuleDefinition(
            moduleName = "feature_llm_armv9",
            family = NativeEngineFamily.LLM,
            backend = NativeBackendKind.CPU,
            tier = NativeCpuTier.ARMV9,
            expectedFiles = llmCpuPayload("armv9"),
            mayBeAutoProvisioned = true,
            supportsBenchmark = true
        ),
        NativeModuleDefinition(
            moduleName = "feature_llm_i8mm",
            family = NativeEngineFamily.LLM,
            backend = NativeBackendKind.CPU,
            tier = NativeCpuTier.I8MM,
            experimental = true,
            expectedFiles = llmCpuPayload("i8mm"),
            mayBeAutoProvisioned = false,
            supportsBenchmark = true
        ),
        NativeModuleDefinition(
            moduleName = "feature_llm_snapdragon_opencl",
            family = NativeEngineFamily.LLM,
            backend = NativeBackendKind.OPENCL,
            experimental = true,
            expectedFiles = setOf("libllama_server_snapdragon_opencl.so", "libllama-bench_snapdragon_opencl.so", "libAIDOCL.so"),
            mayBeAutoProvisioned = false,
            supportsBenchmark = true
        ),
        NativeModuleDefinition(
            moduleName = "feature_media_baseline",
            family = NativeEngineFamily.MEDIA,
            backend = NativeBackendKind.CPU,
            tier = NativeCpuTier.BASELINE,
            expectedFiles = mediaCpuPayload("baseline"),
            mayBeAutoProvisioned = true
        ),
        NativeModuleDefinition(
            moduleName = "feature_media_dotprod",
            family = NativeEngineFamily.MEDIA,
            backend = NativeBackendKind.CPU,
            tier = NativeCpuTier.DOTPROD,
            expectedFiles = mediaCpuPayload("dotprod"),
            mayBeAutoProvisioned = true
        ),
        NativeModuleDefinition(
            moduleName = "feature_media_armv9",
            family = NativeEngineFamily.MEDIA,
            backend = NativeBackendKind.CPU,
            tier = NativeCpuTier.ARMV9,
            expectedFiles = mediaCpuPayload("armv9"),
            mayBeAutoProvisioned = true
        ),
        NativeModuleDefinition(
            moduleName = "feature_media_i8mm",
            family = NativeEngineFamily.MEDIA,
            backend = NativeBackendKind.CPU,
            tier = NativeCpuTier.I8MM,
            experimental = true,
            expectedFiles = setOf("libsd_i8mm.so"),
            mayBeAutoProvisioned = false
        ),
        NativeModuleDefinition(
            moduleName = "feature_media_snapdragon_vulkan",
            family = NativeEngineFamily.MEDIA,
            backend = NativeBackendKind.VULKAN,
            experimental = true,
            expectedFiles = setOf("libsd_snapdragon_vulkan.so"),
            mayBeAutoProvisioned = false
        ),
        NativeModuleDefinition(
            moduleName = "feature_media_snapdragon_opencl",
            family = NativeEngineFamily.MEDIA,
            backend = NativeBackendKind.OPENCL,
            experimental = true,
            expectedFiles = setOf("libsd_snapdragon_opencl.so", "libAIDOCL.so"),
            mayBeAutoProvisioned = false
        )
    )

    fun require(moduleName: String): NativeModuleDefinition =
        definitions.first { it.moduleName == moduleName }

    /** Built-in payloads are statically linked; only imported custom binaries need linker staging. */
    fun isBuiltInStaticPayload(fileName: String): Boolean =
        definitions.any { fileName in it.expectedFiles }

    fun deliveryFor(context: Context): NativeModuleDelivery {
        if (BuildConfig.IS_FAT_APK_BUILD) return NativeModuleDelivery.EMBEDDED
        val installer = runCatching {
            context.packageManager.getInstallerPackageName(context.packageName)
        }.getOrNull()
        return if (installer == "com.android.vending") {
            NativeModuleDelivery.PLAY_MANAGED
        } else {
            NativeModuleDelivery.SIDELOADED
        }
    }
}
