package com.example.llamadroid.data.binary

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.llamadroid.BuildConfig
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.util.CpuFeatures
import com.example.llamadroid.util.CustomBinaryFamily
import com.example.llamadroid.util.CustomBinaryPackageManager
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.DeviceAcceleration
import com.example.llamadroid.util.DynamicFeatureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

enum class RequestedLlamaBinary(val preferenceValue: String) {
    AUTO(SettingsRepository.NATIVE_BINARY_AUTO),
    CPU_BASELINE(SettingsRepository.NATIVE_BINARY_CPU_BASELINE),
    CPU_DOTPROD(SettingsRepository.NATIVE_BINARY_CPU_DOTPROD),
    CPU_ARMV9(SettingsRepository.NATIVE_BINARY_CPU_ARMV9),
    CPU_I8MM(SettingsRepository.NATIVE_BINARY_CPU_I8MM),
    OPENCL(SettingsRepository.NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL),
    VULKAN(SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_VULKAN);

    companion object {
        fun fromPreference(value: String?): RequestedLlamaBinary =
            when (SettingsRepository.normalizeLlmNativeBinarySelection(value)) {
                SettingsRepository.NATIVE_BINARY_CPU_BASELINE -> CPU_BASELINE
                SettingsRepository.NATIVE_BINARY_CPU_DOTPROD -> CPU_DOTPROD
                SettingsRepository.NATIVE_BINARY_CPU_ARMV9 -> CPU_ARMV9
                SettingsRepository.NATIVE_BINARY_CPU_I8MM -> CPU_I8MM
                SettingsRepository.NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL -> OPENCL
                else -> AUTO
            }
    }
}

enum class EffectiveLlamaBinary(val tier: String?) {
    CPU_BASELINE("baseline"),
    CPU_DOTPROD("dotprod"),
    CPU_ARMV9("armv9"),
    CPU_I8MM("i8mm"),
    OPENCL(null),
    VULKAN(null)
}

data class NativeCpuCapabilities(
    val arm64: Boolean,
    val fp16: Boolean,
    val dotProd: Boolean,
    val armV9: Boolean,
    val i8mm: Boolean
)

data class BinaryAvailability(
    val installed: Boolean,
    val hardwareCompatible: Boolean,
    val complete: Boolean,
    val abiCompatible: Boolean,
    val quarantined: Boolean = false,
    val unavailableReason: String? = null
) {
    val usable: Boolean
        get() = installed && hardwareCompatible && complete && abiCompatible && !quarantined
}

data class InstalledNativeModules(
    val baseline: BinaryAvailability,
    val dotprod: BinaryAvailability,
    val armv9: BinaryAvailability,
    val i8mm: BinaryAvailability,
    val opencl: BinaryAvailability,
    val vulkan: BinaryAvailability
)

data class BinaryResolution(
    val requested: RequestedLlamaBinary,
    val effective: EffectiveLlamaBinary,
    val fallbackUsed: Boolean,
    val fallbackReason: String?
)

enum class RequestedKvBackend {
    AUTO,
    CPU,
    ACCELERATOR
}

enum class EffectiveKvBackend {
    CPU,
    OPENCL,
    VULKAN
}

/**
 * Repository for managing native binaries with CPU tier support.
 * 
 * Binaries are stored with tier suffixes: libname_baseline.so, libname_dotprod.so, libname_armv9.so
 * At runtime, the best available tier is selected based on CPU features.
 */
class BinaryRepository(private val context: Context) {
    companion object {
        private const val TAG = "BinaryRepository"
        
        // Required files for llama.cpp server (for custom binary upload screen)
        val REQUIRED_FILES = listOf(
            "llama-server" to "libllama_server.so",
            "libllama.so" to "libllama.so",
            "libggml.so" to "libggml.so",
            "libggml-base.so" to "libggml-base.so",
            "libggml-cpu.so" to "libggml-cpu.so",
            "libmtmd.so" to "libmtmd.so"
        )
        
        // Binary names (without lib prefix and tier suffix)
        private val TIERED_BINARIES = listOf(
            "ffmpeg",
            "ffprobe",
            "whisper-cli",
            "llama_server",
            "rpc-server",
            "sd-rpc-server",
            "llama-bench",
            "mtmd",
            "sd",
            "kiwix-serve",
            "kiwix-manage",
            "quadtrix_trainer"
        )
        
        // Preference keys
        private const val PREFS_NAME = "llamadroid_settings"
        private const val KEY_PREFERRED_TIER = "preferred_cpu_tier"
        private const val KEY_I8MM_QUARANTINE_PREFIX = "i8mm_failed"
        const val TIER_BASELINE = "baseline"
        const val TIER_DOTPROD = "dotprod"
        const val TIER_ARMV9 = "armv9"
        const val TIER_I8MM = "i8mm"
        private val CPU_TIER_SUFFIXES = setOf(TIER_BASELINE, TIER_DOTPROD, TIER_ARMV9, TIER_I8MM)

        // Required shared libraries (not tiered, always same version)
        val SHARED_LIBS = listOf(
            "libllama.so",
            "libllama.so.0.so",
            "libggml.so",
            "libggml.so.0.so",
            "libggml-base.so",
            "libggml-base.so.0.so",
            "libggml-cpu.so",
            "libggml-cpu.so.0.so",
            "libwhisper.so.1.so"
        )

        internal fun buildBinarySearchTiers(selectedTier: String, deviceTier: String): List<String> {
            val preferred = tiersForSelectionStatic(selectedTier)
            val deviceFallbacks = tiersForSelectionStatic(deviceTier)
            return (preferred + deviceFallbacks).distinct()
        }

        internal fun exactCpuTierForNativeSelection(selection: String): String? =
            when (selection) {
                SettingsRepository.NATIVE_BINARY_CPU_BASELINE -> TIER_BASELINE
                SettingsRepository.NATIVE_BINARY_CPU_DOTPROD -> TIER_DOTPROD
                SettingsRepository.NATIVE_BINARY_CPU_ARMV9 -> TIER_ARMV9
                SettingsRepository.NATIVE_BINARY_CPU_I8MM -> TIER_I8MM
                else -> null
            }

        private fun tiersForSelectionStatic(tier: String): List<String> = when (tier) {
            TIER_I8MM -> listOf(TIER_I8MM, TIER_ARMV9, TIER_DOTPROD, TIER_BASELINE)
            TIER_ARMV9 -> listOf(TIER_ARMV9, TIER_DOTPROD, TIER_BASELINE)
            TIER_DOTPROD -> listOf(TIER_DOTPROD, TIER_BASELINE)
            else -> listOf(TIER_BASELINE)
        }

        fun resolveAutomaticCpuBinary(
            installed: InstalledNativeModules
        ): EffectiveLlamaBinary {
            if (installed.i8mm.usable) return EffectiveLlamaBinary.CPU_I8MM
            if (installed.armv9.usable) return EffectiveLlamaBinary.CPU_ARMV9
            if (installed.dotprod.usable) return EffectiveLlamaBinary.CPU_DOTPROD
            return EffectiveLlamaBinary.CPU_BASELINE
        }

        fun resolveLlamaBinary(
            requested: RequestedLlamaBinary,
            installed: InstalledNativeModules
        ): BinaryResolution {
            val automatic = resolveAutomaticCpuBinary(installed)
            fun fallback(reason: String?) = BinaryResolution(
                requested = requested,
                effective = automatic,
                fallbackUsed = true,
                fallbackReason = reason
            )

            return when (requested) {
                RequestedLlamaBinary.AUTO -> BinaryResolution(
                    requested = requested,
                    effective = automatic,
                    fallbackUsed = automatic != EffectiveLlamaBinary.CPU_I8MM &&
                        (installed.i8mm.installed || installed.i8mm.hardwareCompatible),
                    fallbackReason = when (automatic) {
                        EffectiveLlamaBinary.CPU_I8MM -> null
                        else -> installed.i8mm.unavailableReason?.takeIf { !installed.i8mm.usable }
                    }
                )
                RequestedLlamaBinary.CPU_I8MM ->
                    if (installed.i8mm.usable) BinaryResolution(requested, EffectiveLlamaBinary.CPU_I8MM, false, null)
                    else fallback(installed.i8mm.unavailableReason ?: "i8mm is unavailable")
                RequestedLlamaBinary.CPU_ARMV9 ->
                    if (installed.armv9.usable) BinaryResolution(requested, EffectiveLlamaBinary.CPU_ARMV9, false, null)
                    else fallback(installed.armv9.unavailableReason ?: "CPU Armv9 binary is unavailable")
                RequestedLlamaBinary.CPU_DOTPROD ->
                    if (installed.dotprod.usable) BinaryResolution(requested, EffectiveLlamaBinary.CPU_DOTPROD, false, null)
                    else fallback(installed.dotprod.unavailableReason ?: "CPU dot-product binary is unavailable")
                RequestedLlamaBinary.CPU_BASELINE ->
                    if (installed.baseline.usable) BinaryResolution(requested, EffectiveLlamaBinary.CPU_BASELINE, false, null)
                    else BinaryResolution(requested, automatic, true, installed.baseline.unavailableReason ?: "CPU baseline binary is unavailable")
                RequestedLlamaBinary.OPENCL ->
                    if (installed.opencl.usable) BinaryResolution(requested, EffectiveLlamaBinary.OPENCL, false, null)
                    else fallback(installed.opencl.unavailableReason ?: "OpenCL binary is unavailable")
                RequestedLlamaBinary.VULKAN ->
                    if (installed.vulkan.usable) BinaryResolution(requested, EffectiveLlamaBinary.VULKAN, false, null)
                    else fallback(installed.vulkan.unavailableReason ?: "Vulkan binary is unavailable")
            }
        }

        fun resolveKvBackend(
            requested: RequestedKvBackend,
            binary: EffectiveLlamaBinary
        ): EffectiveKvBackend {
            val accelerator = when (binary) {
                EffectiveLlamaBinary.VULKAN -> EffectiveKvBackend.VULKAN
                EffectiveLlamaBinary.OPENCL -> EffectiveKvBackend.OPENCL
                else -> null
            }
            return when (requested) {
                RequestedKvBackend.AUTO -> accelerator ?: EffectiveKvBackend.CPU
                RequestedKvBackend.CPU -> EffectiveKvBackend.CPU
                RequestedKvBackend.ACCELERATOR -> accelerator ?: EffectiveKvBackend.CPU
            }
        }

        fun requestedKvBackendFromPreference(value: String?): RequestedKvBackend =
            when (SettingsRepository.normalizeLlamaKvOffloadMode(value)) {
                SettingsRepository.LLAMA_KV_OFFLOAD_ACCELERATOR -> RequestedKvBackend.ACCELERATOR
                SettingsRepository.LLAMA_KV_OFFLOAD_CPU -> RequestedKvBackend.CPU
                else -> RequestedKvBackend.AUTO
            }

        fun kvOffloadPreferenceForEffectiveBackend(backend: EffectiveKvBackend): String =
            when (backend) {
                EffectiveKvBackend.CPU -> SettingsRepository.LLAMA_KV_OFFLOAD_CPU
                EffectiveKvBackend.OPENCL,
                EffectiveKvBackend.VULKAN -> SettingsRepository.LLAMA_KV_OFFLOAD_ACCELERATOR
            }

        internal fun acceleratorLibNames(
            name: String,
            nativeBinarySelection: String = SettingsRepository.NATIVE_BINARY_AUTO
        ): List<String> {
            return when (name) {
                "llama_server" -> when (SettingsRepository.normalizeLlmNativeBinarySelection(nativeBinarySelection)) {
                    SettingsRepository.NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL -> listOf("libllama_server_snapdragon_opencl.so")
                    else -> emptyList()
                }
                "llama-bench" -> when (SettingsRepository.normalizeLlmNativeBinarySelection(nativeBinarySelection)) {
                    SettingsRepository.NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL -> listOf("libllama-bench_snapdragon_opencl.so")
                    else -> emptyList()
                }
                "sd" -> when (SettingsRepository.normalizeStableDiffusionNativeBinarySelection(nativeBinarySelection)) {
                    SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_VULKAN -> listOf("libsd_snapdragon_vulkan.so")
                    SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_OPENCL -> listOf("libsd_snapdragon_opencl.so")
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }
    }
    
    private var cachedTier: String? = null
    private val settingsRepository by lazy { SettingsRepository(context) }
    
    /**
     * Get the current CPU tier (cached).
     */
    fun getTier(): String {
        // Check for user override
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val override = prefs.getString(KEY_PREFERRED_TIER, "auto")
        
        if (override != null && override != "auto") {
            Log.i(TAG, "Using forced CPU tier: $override")
            return override
        }

        if (cachedTier == null) {
            cachedTier = CpuFeatures.getTier()
            Log.i(TAG, "Detected CPU tier: $cachedTier")
        }
        return cachedTier!!
    }

    private fun tiersForSelection(tier: String): List<String> = tiersForSelectionStatic(tier)

    private fun llamaAutomaticTiers(deviceTier: String, name: String): List<String> {
        val fallbackTiers = tiersForSelection(deviceTier)
        if (name !in setOf("llama_server", "llama-bench", "rpc-server", "mtmd")) {
            return fallbackTiers
        }
        val i8mmAvailability = binaryAvailabilityForTier(name, TIER_I8MM)
        return if (i8mmAvailability.usable) {
            (listOf(TIER_I8MM) + fallbackTiers).distinct()
        } else {
            fallbackTiers
        }
    }

    private fun nativeLibraryCandidateDirs(): List<File> {
        val primary = File(context.applicationInfo.nativeLibraryDir)
        val dirs = linkedSetOf<File>()
        dirs += primary

        val packageRoot = primary.parentFile?.parentFile
        if (packageRoot != null && packageRoot.exists()) {
            runCatching {
                packageRoot.walkTopDown()
                    .maxDepth(4)
                    .filter { it.isDirectory }
                    .filter { dir ->
                        dir == primary ||
                            dir.name in setOf("arm64", "arm64-v8a") ||
                            dir.listFiles()?.any { it.extension == "so" } == true
                    }
                    .forEach { dirs += it }
            }
        }

        context.applicationInfo.splitSourceDirs
            ?.mapNotNull { File(it).parentFile }
            ?.forEach { splitParent ->
                listOf(
                    File(splitParent, "lib/${CpuFeatures.getArch()}"),
                    File(splitParent, "lib/arm64"),
                    File(splitParent, "lib/arm64-v8a")
                ).filter { it.exists() }.forEach { dirs += it }
            }

        return dirs.filter { it.exists() }
    }

    private fun findAcceleratorBinaries(acceleratorNames: List<String>): List<File> {
        val results = linkedMapOf<String, File>()

        fun addFromDir(dir: File) {
            for (libName in acceleratorNames) {
                val file = File(dir, libName)
                if (file.exists()) {
                    results.putIfAbsent(file.absolutePath, file)
                }
            }
        }

        nativeLibraryCandidateDirs().forEach(::addFromDir)

        listOf(
            "com.example.llamadroid.feature.llm.snapdragon.opencl",
            "com.example.llamadroid.feature.media.snapdragon.vulkan",
            "com.example.llamadroid.feature.media.snapdragon.opencl"
        ).forEach { pkgName ->
            runCatching {
                val featureContext = context.createPackageContext(pkgName, 0)
                addFromDir(File(featureContext.applicationInfo.nativeLibraryDir))
            }
        }

        listOf(
            "feature_llm_snapdragon_opencl",
            "feature_media_snapdragon_vulkan",
            "feature_media_snapdragon_opencl"
        ).forEach { splitName ->
            val splitDir = File(context.filesDir.parent, "split_$splitName")
            if (splitDir.exists()) {
                listOf(CpuFeatures.getArch(), "arm64", "arm64-v8a")
                    .map { File(splitDir, "lib/$it") }
                    .filter { it.exists() }
                    .forEach(::addFromDir)
            }
        }

        return results.values.toList()
    }

    private fun packageNamesForTier(name: String, tier: String): List<String> = buildList {
        if (name in setOf("llama_server", "llama-bench", "rpc-server", "mtmd", "whisper-cli", "quadtrix_trainer")) {
            add("com.example.llamadroid.feature.llm.$tier")
        }
        if (name in setOf("ffmpeg", "ffprobe", "sd", "sd-rpc-server", "whisper-cli")) {
                add("com.example.llamadroid.feature.media.$tier")
        }
        if (tier != TIER_I8MM) {
            if (name in setOf("kiwix-serve", "kiwix-manage")) {
                add("com.example.llamadroid.feature.kiwix.$tier")
            }
        }
    }.distinct()

    private fun splitNamesForTier(name: String, tier: String): List<String> =
        moduleNamesForTier(name, tier)

    private fun moduleNameForTier(name: String, tier: String): String? =
        moduleNamesForTier(name, tier).firstOrNull()

    private fun moduleNamesForTier(name: String, tier: String): List<String> = buildList {
        if (name in setOf("llama_server", "llama-bench", "rpc-server", "mtmd", "whisper-cli", "quadtrix_trainer")) {
            add("feature_llm_$tier")
        }
        if (name in setOf("ffmpeg", "ffprobe", "sd", "sd-rpc-server", "whisper-cli")) {
                add("feature_media_$tier")
        }
        if (tier != TIER_I8MM) {
            if (name in setOf("kiwix-serve", "kiwix-manage")) {
                add("feature_kiwix_$tier")
            }
        }
    }.distinct()

    private fun isModuleDeliveredForTier(name: String, tier: String): Boolean {
        val module = moduleNameForTier(name, tier) ?: return false
        return DynamicFeatureManager.isModuleInstalled(context, module)
    }

    private fun exactTieredFileName(name: String, tier: String): String = "lib${name}_${tier}.so"

    private fun findExactTieredFile(name: String, tier: String): File? {
        val libName = exactTieredFileName(name, tier)
        nativeLibraryCandidateDirs().forEach { dir ->
            File(dir, libName).takeIf { it.isFile }?.let { return it }
        }

        packageNamesForTier(name, tier).forEach { pkgName ->
            runCatching {
                val featureContext = context.createPackageContext(pkgName, 0)
                File(featureContext.applicationInfo.nativeLibraryDir, libName)
                    .takeIf { it.isFile }
            }.getOrNull()?.let { return it }
        }

        splitNamesForTier(name, tier).forEach { splitName ->
            val splitDir = File(context.filesDir.parent, "split_$splitName")
            listOf(CpuFeatures.getArch(), "arm64", "arm64-v8a")
                .map { File(splitDir, "lib/$it/$libName") }
                .firstOrNull { it.isFile }
                ?.let { return it }
        }

        if (canUseDeployedBinExecutables()) {
            File(context.filesDir, "bin/$libName").takeIf { it.isFile }?.let { return it }
        }

        return null
    }

    fun isTierInstalledForBinary(name: String, tier: String): Boolean =
        isModuleDeliveredForTier(name, tier) || findExactTieredFile(name, tier) != null

    private fun hasLibraryCandidateInDir(dir: File, names: List<String>): Boolean =
        names.any { File(dir, it).isFile }

    private fun isI8mmBinarySetComplete(name: String): Boolean {
        val binaryFile = findExactTieredFile(name, TIER_I8MM) ?: return false
        val sourceDir = binaryFile.parentFile ?: return false

        val requiredExecutables = buildList {
            add(listOf(exactTieredFileName(name, TIER_I8MM)))
            if (name == "llama_server") {
                add(listOf("libmtmd_i8mm.so", "libmtmd.so"))
            }
        }
        if (!requiredExecutables.all { candidates -> hasLibraryCandidateInDir(sourceDir, candidates) }) {
            return false
        }

        val sharedRuntimeFamilies = listOf(
            listOf("libllama_i8mm.so", "libllama.so", "libllama.so.0.so"),
            listOf("libggml_i8mm.so", "libggml.so", "libggml.so.0.so"),
            listOf("libggml-base_i8mm.so", "libggml-base.so", "libggml-base.so.0.so"),
            listOf("libggml-cpu_i8mm.so", "libggml-cpu.so", "libggml-cpu.so.0.so")
        )
        val hasSharedRuntime = sharedRuntimeFamilies.any { candidates ->
            hasLibraryCandidateInDir(sourceDir, candidates)
        }
        return !hasSharedRuntime || sharedRuntimeFamilies.all { candidates ->
            hasLibraryCandidateInDir(sourceDir, candidates)
        }
    }

    private fun isHardwareCompatibleWithTier(tier: String): Boolean =
        when (tier) {
            TIER_I8MM -> runCatching { CpuFeatures.hasI8mm() }.getOrDefault(false)
            TIER_ARMV9 -> runCatching { CpuFeatures.hasArmV9() }.getOrDefault(false)
            TIER_DOTPROD -> runCatching { CpuFeatures.hasDotProd() }.getOrDefault(false)
            else -> true
        }

    private fun binaryAvailabilityForTier(name: String, tier: String): BinaryAvailability {
        val installed = isTierInstalledForBinary(name, tier)
        val hardwareCompatible = isHardwareCompatibleWithTier(tier)
        val abiCompatible = CpuFeatures.getArch().lowercase(Locale.US).contains("arm64")
        val complete = if (tier == TIER_I8MM && name in setOf("llama_server", "llama-bench", "rpc-server", "mtmd")) {
            isI8mmBinarySetComplete(name)
        } else {
            findExactTieredFile(name, tier) != null
        }
        val quarantined = tier == TIER_I8MM && isI8mmQuarantined()
        val reason = when {
            !installed -> if (tier == TIER_I8MM) "i8mm module not delivered in this installation" else "module not delivered in this installation"
            !hardwareCompatible -> if (tier == TIER_I8MM) "i8mm library present but CPU capability absent" else "CPU capability absent for $tier"
            !complete -> if (tier == TIER_I8MM) "i8mm module incomplete or invalid" else "binary module incomplete or invalid"
            !abiCompatible -> "native ABI is not arm64-compatible"
            quarantined -> "i8mm is quarantined after a previous startup failure"
            else -> null
        }
        return BinaryAvailability(
            installed = installed,
            hardwareCompatible = hardwareCompatible,
            complete = complete,
            abiCompatible = abiCompatible,
            quarantined = quarantined,
            unavailableReason = reason
        )
    }

    fun currentNativeCpuCapabilities(): NativeCpuCapabilities =
        NativeCpuCapabilities(
            arm64 = CpuFeatures.getArch().lowercase(Locale.US).contains("arm64"),
            fp16 = true,
            dotProd = runCatching { CpuFeatures.hasDotProd() }.getOrDefault(false),
            armV9 = runCatching { CpuFeatures.hasArmV9() }.getOrDefault(false),
            i8mm = runCatching { CpuFeatures.hasI8mm() }.getOrDefault(false)
        )

    fun installedNativeModulesForLlama(): InstalledNativeModules =
        InstalledNativeModules(
            baseline = binaryAvailabilityForTier("llama_server", TIER_BASELINE),
            dotprod = binaryAvailabilityForTier("llama_server", TIER_DOTPROD),
            armv9 = binaryAvailabilityForTier("llama_server", TIER_ARMV9),
            i8mm = binaryAvailabilityForTier("llama_server", TIER_I8MM),
            opencl = BinaryAvailability(
                installed = findAcceleratorBinaries(listOf("libllama_server_snapdragon_opencl.so")).isNotEmpty() ||
                    DynamicFeatureManager.isModuleInstalled(context, DynamicFeatureManager.MODULE_LLM_SNAPDRAGON_OPENCL),
                hardwareCompatible = DeviceAcceleration.isSnapdragonCompatible(),
                complete = findAcceleratorBinaries(listOf("libllama_server_snapdragon_opencl.so")).isNotEmpty(),
                abiCompatible = CpuFeatures.getArch().lowercase(Locale.US).contains("arm64"),
                unavailableReason = null
            ),
            vulkan = BinaryAvailability(
                installed = false,
                hardwareCompatible = false,
                complete = false,
                abiCompatible = CpuFeatures.getArch().lowercase(Locale.US).contains("arm64"),
                unavailableReason = "Vulkan is not an LLM binary"
            )
        )

    fun llamaCpuTierAvailability(tier: String): BinaryAvailability =
        binaryAvailabilityForTier("llama_server", tier)

    fun resolveCurrentLlamaBinary(requestedSelection: String = settingsRepository.llmNativeBinarySelection.value): BinaryResolution =
        resolveLlamaBinary(
            requested = RequestedLlamaBinary.fromPreference(requestedSelection),
            installed = installedNativeModulesForLlama()
        )

    private fun nativeBuildRevisionKey(): String =
        runCatching {
            context.assets.open("native_build_commits.txt").bufferedReader().use { reader ->
                reader.readText().hashCode().toString(16)
            }
        }.getOrDefault("unknown")

    private fun i8mmQuarantineKey(): String =
        "${KEY_I8MM_QUARANTINE_PREFIX}_${BuildConfig.VERSION_CODE}_${nativeBuildRevisionKey()}"

    fun isI8mmQuarantined(): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(i8mmQuarantineKey(), false)

    fun quarantineI8mmForCurrentVersion(reason: String) {
        val key = i8mmQuarantineKey()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, true)
            .putString("${key}_reason", reason.take(240))
            .apply()
        DebugLog.log("$TAG: Quarantined i8mm for current native revision: $reason")
    }

    /** Lets a user retry after installing a newly verified i8mm module. */
    fun clearI8mmQuarantineForCurrentVersion() {
        val key = i8mmQuarantineKey()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key)
            .remove("${key}_reason")
            .apply()
        DebugLog.log("$TAG: Cleared i8mm quarantine for current native revision")
    }

    private fun logLlamaResolutionIfNeeded(
        name: String,
        requestedSelection: String,
        tiersToTry: List<String>
    ) {
        if (name != "llama_server") return
        val modules = installedNativeModulesForLlama()
        val requested = RequestedLlamaBinary.fromPreference(requestedSelection)
        val resolution = resolveLlamaBinary(requested, modules)
        val cpu = currentNativeCpuCapabilities()
        DebugLog.log(
            "$TAG:\n" +
                "  requestedBinary=${requested.preferenceValue}\n" +
                "  installationSource=${if (BuildConfig.IS_FAT_APK_BUILD) "sideload_or_fat_apk" else "google_play_or_split"}\n" +
                "  installedModules={baseline=${modules.baseline.installed}, dotprod=${modules.dotprod.installed}, armv9=${modules.armv9.installed}, i8mm=${modules.i8mm.installed}, opencl=${modules.opencl.installed}}\n" +
                "  cpuFeatures={arm64=${cpu.arm64}, dotprod=${cpu.dotProd}, armv9=${cpu.armV9}, i8mm=${cpu.i8mm}}\n" +
                "  i8mm={installed=${modules.i8mm.installed}, cpuCompatible=${modules.i8mm.hardwareCompatible}, complete=${modules.i8mm.complete}, quarantined=${modules.i8mm.quarantined}}\n" +
                "  effectiveBinary=${resolution.effective.name.lowercase(Locale.US)}\n" +
                "  fallbackUsed=${resolution.fallbackUsed}\n" +
                "  fallbackReason=${resolution.fallbackReason}\n" +
                "  searchTiers=$tiersToTry"
        )
    }

    private fun canUseDeployedBinExecutables(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * Get path to a tiered binary, with fallback to lower tiers.
     * 
     * @param name Binary name without "lib" prefix (e.g., "ffmpeg", "llama-bench")
     * @return File path to the binary, or null if not found
     */
    /**
     * Get path to a tiered binary, with fallback to lower tiers.
     * 
     * @param name Binary name without "lib" prefix (e.g., "ffmpeg", "llama-bench")
     * @return File path to the binary, or null if not found
     */
    /**
     * Get path to a tiered binary, with fallback to lower tiers.
     * 
     * @param name Binary name without "lib" prefix (e.g., "ffmpeg", "llama-bench")
     * @return File path to the binary, or null if not found
     */
    fun getTieredBinary(name: String): File? {
        nativeBinarySelectionFor(name)?.let { selection ->
            return getSelectedNativeBinary(name, selection)
        }
        return getTieredBinary(name, getTier())
    }

    /**
     * Resolve a binary using a launch-profile selector rather than the current global setting.
     * The selector is intentionally explicit so managed server sessions can remain isolated from
     * changes made in General settings after a profile was saved.
     */
    fun getTieredBinaryForSelection(name: String, nativeBinarySelection: String): File? =
        getSelectedNativeBinary(name, nativeBinarySelection)

    fun getCpuTieredBinary(name: String): File? {
        val deviceTier = getTier()
        val tiersToTry = llamaAutomaticTiers(deviceTier, name)
        return findTieredBinary(name, deviceTier, tiersToTry, allowAccelerator = false)
    }

    fun getAcceleratorBinaries(name: String): List<File> {
        if (!DeviceAcceleration.isSnapdragonCompatible()) return emptyList()
        val acceleratorNames = acceleratorLibNames(
            name,
            nativeBinarySelectionFor(name) ?: SettingsRepository.NATIVE_BINARY_AUTO
        )
        if (acceleratorNames.isEmpty()) return emptyList()
        return findAcceleratorBinaries(acceleratorNames)
    }

    private fun nativeBinarySelectionFor(name: String): String? = when (name) {
        "llama_server",
        "llama-bench" -> settingsRepository.llmNativeBinarySelection.value
        "sd" -> settingsRepository.stableDiffusionNativeBinarySelection.value
        else -> null
    }

    private fun getSelectedNativeBinary(name: String, selection: String): File? {
        val normalizedSelection = when (name) {
            "sd" -> SettingsRepository.normalizeStableDiffusionNativeBinarySelection(selection)
            else -> SettingsRepository.normalizeLlmNativeBinarySelection(selection)
        }
        if (CustomBinaryPackageManager.selectionId(normalizedSelection) != null) {
            val family = if (name == "sd") {
                CustomBinaryFamily.STABLE_DIFFUSION
            } else {
                CustomBinaryFamily.LLM_SERVER
            }
            val customPackage = CustomBinaryPackageManager(context).resolve(normalizedSelection, family)
            if (customPackage != null && name in setOf("llama_server", "sd")) {
                DebugLog.log("$TAG: Using custom ${family.manifestValue} package ${customPackage.id}")
                return customPackage.entrypointFile
            }
            DebugLog.log("$TAG: Custom package selection $normalizedSelection is unavailable for $name; using CPU fallback.")
        }
        val deviceTier = getTier()

        exactCpuTierForNativeSelection(normalizedSelection)?.let { exactTier ->
            val availability = binaryAvailabilityForTier(name, exactTier)
            if (!availability.usable) {
                val fallbackTiers = if (name in setOf("llama_server", "llama-bench")) {
                    llamaAutomaticTiers(deviceTier, name)
                } else {
                    tiersForSelection(deviceTier)
                }
                DebugLog.log(
                    "$TAG: Requested CPU tier $exactTier for $name is unavailable; " +
                        "falling back through $fallbackTiers reason=${availability.unavailableReason}"
                )
                return findTieredBinary(
                    name = name,
                    selectedTier = deviceTier,
                    tiersToTry = fallbackTiers,
                    allowAccelerator = false,
                    nativeBinarySelectionOverride = normalizedSelection
                )
            }
            logLlamaResolutionIfNeeded(name, normalizedSelection, listOf(exactTier))
            return findTieredBinary(
                name = name,
                selectedTier = exactTier,
                tiersToTry = listOf(exactTier),
                allowAccelerator = false,
                nativeBinarySelectionOverride = normalizedSelection
            )
        }

        if (normalizedSelection == SettingsRepository.NATIVE_BINARY_AUTO ||
            normalizedSelection == SettingsRepository.NATIVE_BINARY_CPU_AUTO
        ) {
            val tiersToTry = if (name in setOf("llama_server", "llama-bench")) {
                llamaAutomaticTiers(deviceTier, name)
            } else if (name == "sd" && binaryAvailabilityForTier("sd", TIER_I8MM).usable) {
                (listOf(TIER_I8MM) + tiersForSelection(deviceTier)).distinct()
            } else {
                tiersForSelection(deviceTier)
            }
            logLlamaResolutionIfNeeded(name, normalizedSelection, tiersToTry)
            return findTieredBinary(
                name = name,
                selectedTier = deviceTier,
                tiersToTry = tiersToTry,
                allowAccelerator = false,
                nativeBinarySelectionOverride = normalizedSelection
            )
        }

        return findTieredBinary(
            name = name,
            selectedTier = deviceTier,
            tiersToTry = tiersForSelection(deviceTier),
            allowAccelerator = true,
            nativeBinarySelectionOverride = normalizedSelection
        )
    }

    private fun getTieredBinary(name: String, selectedTier: String): File? {
        val deviceTier = getTier()
        val tiersToTry = buildBinarySearchTiers(selectedTier, deviceTier)
        return findTieredBinary(name, selectedTier, tiersToTry)
    }

    private fun findTieredBinary(
        name: String,
        selectedTier: String,
        tiersToTry: List<String>,
        allowAccelerator: Boolean = true,
        nativeBinarySelectionOverride: String? = null
    ): File? {
        if (!DynamicFeatureManager.isNativeLibsReady(context)) {
            Log.w(TAG, "Native libs modules not fully ready yet; probing available paths for $name anyway")
        }
        
        val nativeLibDirs = nativeLibraryCandidateDirs()
        val nativeBinarySelection = nativeBinarySelectionOverride
            ?: nativeBinarySelectionFor(name)
            ?: SettingsRepository.NATIVE_BINARY_AUTO
        val acceleratorNames = if (allowAccelerator && DeviceAcceleration.isSnapdragonCompatible()) {
            acceleratorLibNames(name, nativeBinarySelection)
        } else {
            emptyList()
        }
        DebugLog.log(
            "$TAG: Resolving $name allowAccelerator=$allowAccelerator nativeBinarySelection=$nativeBinarySelection modules=" +
                "${DynamicFeatureManager.getOptionalAcceleratorModules().associateWith { DynamicFeatureManager.isModuleInstalled(context, it) }} dirs=" +
                nativeLibDirs.joinToString { it.absolutePath }
        )
        
        // Strategy 1: Check installed native lib dirs. Android 10+ restricts W^X, so
        // native payloads must be executed from package/split paths when possible.
        if (acceleratorNames.isNotEmpty()) {
            for (dir in nativeLibDirs) {
                for (libName in acceleratorNames) {
                    val file = File(dir, libName)
                    if (file.exists()) {
                        DebugLog.log("$TAG: Found accelerator $name at ${file.absolutePath}")
                        return file
                    }
                }
            }
        }

        val exactLibName = "lib${name}.so"

        for (dir in nativeLibDirs) {
            for (tryTier in tiersToTry) {
                val libName = "lib${name}_${tryTier}.so"
                val file = File(dir, libName)

                if (file.exists()) {
                    DebugLog.log("$TAG: Found $name at ${file.absolutePath} (tier: $tryTier)")
                    return file
                }
            }
            val exactFile = File(dir, exactLibName)
            if (exactFile.exists()) {
                DebugLog.log("$TAG: Found non-tiered $name at ${exactFile.absolutePath}")
                return exactFile
            }
        }
        
        // Strategy 2: Check Feature Module Contexts (Split APKs)
        // On some devices, splits have their own nativeLibraryDir. We must execute from THERE.
        val featureSearchTiers = tiersToTry
        val featurePackages = buildList {
            if (acceleratorNames.isNotEmpty()) {
                add("com.example.llamadroid.feature.llm.snapdragon.opencl")
                add("com.example.llamadroid.feature.media.snapdragon.vulkan")
                add("com.example.llamadroid.feature.media.snapdragon.opencl")
            }
            featureSearchTiers.forEach { tier ->
                add("com.example.llamadroid.feature.llm.$tier")
                add("com.example.llamadroid.feature.media.$tier")
                add("com.example.llamadroid.feature.kiwix.$tier")
            }
            add("com.example.llamadroid.feature.upscaler")
        }.distinct()

        for (pkgName in featurePackages) {
            try {
                val featureContext = context.createPackageContext(pkgName, 0)
                val featureLibDir = File(featureContext.applicationInfo.nativeLibraryDir)
                
                if (featureLibDir.exists()) {
                    if (acceleratorNames.isNotEmpty()) {
                        for (libName in acceleratorNames) {
                            val sourceFile = File(featureLibDir, libName)
                            if (sourceFile.exists()) {
                                DebugLog.log("$TAG: Found accelerator $name in feature dir at ${sourceFile.absolutePath}")
                                return sourceFile
                            }
                        }
                    }
                    for (tryTier in tiersToTry) {
                        val libName = "lib${name}_${tryTier}.so"
                        val sourceFile = File(featureLibDir, libName)
                        
                        if (sourceFile.exists()) {
                            // EXECUTE DIRECTLY from feature lib dir. Do NOT copy.
                            DebugLog.log("$TAG: Found $name in feature dir at ${sourceFile.absolutePath}")
                            return sourceFile
                        }
                    }
                    val exactFile = File(featureLibDir, exactLibName)
                    if (exactFile.exists()) {
                        DebugLog.log("$TAG: Found non-tiered $name in feature dir at ${exactFile.absolutePath}")
                        return exactFile
                    }
                }
            } catch (e: Exception) {
                // Ignore package not found
            }
        }
        
        // Strategy 3: Check Legacy Split Directories (Backup for older Android versions)
        try {
            val splitDirs = buildList {
                if (acceleratorNames.isNotEmpty()) {
                    add("feature_llm_snapdragon_opencl")
                    add("feature_media_snapdragon_vulkan")
                    add("feature_media_snapdragon_opencl")
                }
                featureSearchTiers.forEach { tier ->
                    add("feature_llm_$tier")
                    add("feature_kiwix_$tier")
                    add("feature_media_$tier")
                }
            }.distinct()

            for (splitName in splitDirs) {
                val splitDir = File(context.filesDir.parent, "split_$splitName")
                if (splitDir.exists()) {
                    val abis = listOf(CpuFeatures.getArch(), "arm64", "arm64-v8a")
                    val searchDirs = abis.map { File(splitDir, "lib/$it") }

                    for (dir in searchDirs) {
                        if (dir.exists()) {
                            if (acceleratorNames.isNotEmpty()) {
                                for (libName in acceleratorNames) {
                                    val file = File(dir, libName)
                                    if (file.exists()) {
                                        DebugLog.log("$TAG: Found accelerator $name in split dir at ${file.absolutePath}")
                                        return file
                                    }
                                }
                            }
                            for (tryTier in tiersToTry) {
                                val libName = "lib${name}_${tryTier}.so"
                                val file = File(dir, libName)
                                if (file.exists()) {
                                    DebugLog.log("$TAG: Found $name in split dir at ${file.absolutePath}")
                                    return file
                                }
                            }
                            val exactFile = File(dir, exactLibName)
                            if (exactFile.exists()) {
                                DebugLog.log("$TAG: Found non-tiered $name in split dir at ${exactFile.absolutePath}")
                                return exactFile
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check split dir", e)
        }
        
        // Strategy 4: Fallback to deployed 'bin' dir only on old Android releases.
        // Android 10+ enforces W^X for app-data executables, so copied payloads in
        // files/bin are useful as diagnostics but not valid process candidates.
        val deployedBinDir = File(context.filesDir, "bin")
        if (acceleratorNames.isNotEmpty() && deployedBinDir.exists()) {
            DebugLog.log("$TAG: Skipping app-data accelerator copies in ${deployedBinDir.absolutePath}; Android cannot execute these reliably.")
        }
        if (!canUseDeployedBinExecutables()) {
            if (deployedBinDir.exists()) {
                DebugLog.log("$TAG: Skipping deployed app-data binaries in ${deployedBinDir.absolutePath} on Android ${Build.VERSION.SDK_INT}.")
            }
            Log.w(TAG, "Binary not found: $name (selected tier: $selectedTier, tried tiers: $tiersToTry)")
            return null
        }
        for (tryTier in tiersToTry) {
            val libName = "lib${name}_${tryTier}.so"
            val file = File(deployedBinDir, libName)
            if (file.exists()) {
                 DebugLog.log("$TAG: Found $name in deployed dir at ${file.absolutePath} (May fail with Permission Denied)")
                return file
            }
        }
        val exactDeployed = File(deployedBinDir, exactLibName)
        if (exactDeployed.exists()) {
            DebugLog.log("$TAG: Found non-tiered $name in deployed dir at ${exactDeployed.absolutePath} (May fail with Permission Denied)")
            return exactDeployed
        }

        Log.w(TAG, "Binary not found: $name (selected tier: $selectedTier, tried tiers: $tiersToTry)")
        return null
    }

    /**
     * Get the llama-server executable (tiered).
     */
    suspend fun getExecutable(nativeBinarySelection: String? = null): File? = withContext(Dispatchers.IO) {
        return@withContext if (nativeBinarySelection == null) {
            getTieredBinary("llama_server")
        } else {
            getTieredBinaryForSelection("llama_server", nativeBinarySelection)
        }
    }

    suspend fun getCpuExecutable(): File? = withContext(Dispatchers.IO) {
        getCpuTieredBinary("llama_server")
    }

    suspend fun getCpuFallbackExecutables(name: String, excludingPath: String): List<File> = withContext(Dispatchers.IO) {
        val deviceTier = getTier()
        tiersForSelection(deviceTier)
            .mapNotNull { tier ->
                findTieredBinary(
                    name = name,
                    selectedTier = tier,
                    tiersToTry = listOf(tier),
                    allowAccelerator = false
                )
            }
            .filter { it.absolutePath != excludingPath }
            .distinctBy { it.absolutePath }
    }

    /**
     * Get the library directory path - needed for LD_LIBRARY_PATH
     */
    fun getLibraryDir(): String {
        val paths = mutableListOf<String>()
        val customManager = CustomBinaryPackageManager(context)
        listOf(
            settingsRepository.llmNativeBinarySelection.value to CustomBinaryFamily.LLM_SERVER,
            settingsRepository.stableDiffusionNativeBinarySelection.value to CustomBinaryFamily.STABLE_DIFFUSION
        ).mapNotNull { (selection, family) ->
            customManager.resolve(selection, family)?.libraryDirectory?.absolutePath
        }.forEach(paths::add)
        val customBinDir = File(context.filesDir, "binaries") // User uploaded
        if (customBinDir.exists() && customBinDir.listFiles()?.isNotEmpty() == true) {
            paths.add(customBinDir.absolutePath)
        }
        
        // Add system/split native lib dirs (preferred for package libraries)
        nativeLibraryCandidateDirs().forEach { paths.add(it.absolutePath) }
        
        // Add centralized asset binaries directory (Fallback)
        val assetBinDir = com.example.llamadroid.util.AssetPackManagerUtil.getBinariesDir(context)
        if (assetBinDir.exists()) {
            paths.add(assetBinDir.absolutePath)
        }

        // Add deployed bin dir (Primary execution env)
        val deployedBinDir = File(context.filesDir, "bin")
        if (deployedBinDir.exists()) {
            paths.add(0, deployedBinDir.absolutePath) // Prepend to prefer it
        }
        
        return paths.joinToString(":")
    }
    
    /**
     * Get ffmpeg binary (tiered).
     */
    fun getFFmpegBinary(): File? = getTieredBinary("ffmpeg")
    
    /**
     * Get ffprobe binary (tiered).
     */
    fun getFFprobeBinary(): File? = getTieredBinary("ffprobe")

    /**
     * Get whisper-cli binary (tiered).
     */
    fun getWhisperCliBinary(): File? = getTieredBinary("whisper-cli")
    
    /**
     * Get llama-server binary (tiered).
     */
    fun getLlamaServerBinary(): File? = getTieredBinary("llama_server")

    /**
     * Get llama.cpp rpc-server binary (tiered).
     */
    fun getRpcServerBinary(): File? = getTieredBinary("rpc-server")

    /**
     * Get stable-diffusion.cpp-compatible rpc-server binary (tiered).
     */
    fun getSdRpcServerBinary(): File? = getTieredBinary("sd-rpc-server")
    
    /**
     * Get mtmd (multimodal) binary (tiered).
     */
    fun getMtmdBinary(): File? = getTieredBinary("mtmd")
    
    /**
     * Get stable-diffusion binary (tiered).
     */
    fun getSdBinary(): File? = getTieredBinary("sd")

    fun getCpuSdBinary(): File? = getCpuTieredBinary("sd")
    
    /**
     * Get llama-bench binary (tiered) for benchmarking.
     */
    fun getLlamaBenchBinary(): File? = getTieredBinary("llama-bench")

    /**
     * Get kiwix-serve binary (for serving ZIM files).
     * Note: kiwix binaries may not be tiered yet - fall back to non-tiered if needed
     */
    fun getKiwixServeBinary(): File? = getTieredBinary("kiwix-serve") 
        ?: File(context.applicationInfo.nativeLibraryDir, "libkiwix-serve.so").takeIf { it.exists() }
    
    /**
     * Get kiwix-manage binary (for managing ZIM libraries).
     */
    fun getKiwixManageBinary(): File? = getTieredBinary("kiwix-manage") 
        ?: File(context.applicationInfo.nativeLibraryDir, "libkiwix-manage.so").takeIf { it.exists() }
    
    /**
     * Gets the llama-embedding executable from the native library directory.
     */
    suspend fun getEmbeddingExecutable(): File? = withContext(Dispatchers.IO) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val embeddingFile = File(nativeLibDir, "libllama_embedding.so")
        
        if (embeddingFile.exists()) {
            return@withContext embeddingFile
        }
        return@withContext null
    }
    
    fun getLocalVersion(): String? {
        val customBinDir = File(context.filesDir, "binaries")
        val customServer = File(customBinDir, "libllama_server.so")
        if (customServer.exists()) return "Custom"
        
        val serverFile = getTieredBinary("llama_server")
        if (serverFile == null) return null
        return if (DeviceAcceleration.isAcceleratorBinary(serverFile)) {
            "Bundled Snapdragon"
        } else {
            "Bundled (${getTier()})"
        }
    }
    
    /**
     * Check if custom binaries are installed
     */
    fun hasCustomBinaries(): Boolean {
        val customBinDir = File(context.filesDir, "binaries")
        val customServer = File(customBinDir, "libllama_server.so")
        return customServer.exists()
    }
    
    /**
     * Check availability of all tiered binaries.
     */
    fun checkBinaries(): Map<String, Boolean> {
        return TIERED_BINARIES.associateWith { name ->
            getTieredBinary(name) != null
        }
    }
    
    /**
     * Log all binary paths for debugging.
     */
    fun logBinaryPaths() {
        Log.i(TAG, "CPU Tier: ${getTier()}")
        Log.i(TAG, "Native lib dir: ${context.applicationInfo.nativeLibraryDir}")
        
        TIERED_BINARIES.forEach { name ->
            val file = getTieredBinary(name)
            if (file != null) {
                Log.i(TAG, "  $name: ${file.absolutePath}")
            } else {
                Log.w(TAG, "  $name: NOT FOUND")
            }
        }
    }
    
    /**
     * Install a custom binary file from a Uri
     */
    suspend fun installBinaryFromUri(uri: Uri, targetName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val customBinDir = File(context.filesDir, "binaries")
            customBinDir.mkdirs()
            
            val destFile = File(customBinDir, targetName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Make executable
            destFile.setExecutable(true, false)
            
            DebugLog.log("$TAG: Installed custom binary: $targetName (${destFile.length()} bytes)")
            return@withContext true
        } catch (e: Exception) {
            DebugLog.log("$TAG: Failed to install $targetName: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Delete all custom binaries
     */
    suspend fun deleteCustomBinaries() = withContext(Dispatchers.IO) {
        val customBinDir = File(context.filesDir, "binaries")
        if (customBinDir.exists()) {
            customBinDir.deleteRecursively()
            DebugLog.log("$TAG: Deleted custom binaries")
        }
    }

        /**
     * EXTRACT only the NEEDED binaries to filesDir/bin.
     * Selects the best tier for the current device and ignores others.
     * Prioritizes Native Library Directory (OS extracted) for reliability.
     */
    suspend fun deployAllBinaries(): Boolean = withContext(Dispatchers.IO) {
        if (!DynamicFeatureManager.isNativeLibsReady(context)) return@withContext false

        val deployedBinDir = File(context.filesDir, "bin")
        if (!deployedBinDir.exists()) deployedBinDir.mkdirs()
        
        val deviceTier = getTier()
        // Tiers preference: Device Tier -> ... -> Baseline
        val tiersToTry = if (runCatching { CpuFeatures.hasI8mm() }.getOrDefault(false) && !isI8mmQuarantined()) {
            (listOf(TIER_I8MM) + tiersForSelection(deviceTier)).distinct()
        } else {
            tiersForSelection(deviceTier)
        }

        Log.i(TAG, "Deploying binaries for tier: $deviceTier (fallback: $tiersToTry)")

        // Track active binaries
        val activeBinaries = mutableSetOf<String>()

        // 1. Try Native Library Dir (OS Extracted) - PRIORITY for useLegacyPackaging=true
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        if (nativeDir.exists()) {
             Log.d(TAG, "Scanning nativeLibraryDir: ${nativeDir.absolutePath}")
             val deployed = scanAndCopy(nativeDir, deployedBinDir, tiersToTry)
             activeBinaries.addAll(deployed)
        }

        // 2. Try Feature Contexts (for split installs)
        val fromFeatures = legacyDeploy(deployedBinDir, tiersToTry)
        activeBinaries.addAll(fromFeatures)

        // 3. Fallback: APK Extraction (If nothing found or partial)
        if (activeBinaries.isEmpty() || !areCriticalBinariesPresent(activeBinaries)) {
            Log.w(TAG, "Binaries missing from native/legacy paths. Attempting APK extraction...")
            val splitDirs = context.applicationInfo.splitSourceDirs
            val apkPaths = (splitDirs ?: emptyArray()).toMutableList()
            if (context.applicationInfo.sourceDir != null) {
                apkPaths.add(context.applicationInfo.sourceDir)
            }
            
            apkPaths.forEach { apkPath ->
                try {
                    val deployed = extractFromApk(File(apkPath), deployedBinDir, tiersToTry)
                    if (deployed.isNotEmpty()) {
                        activeBinaries.addAll(deployed)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract from $apkPath", e)
                }
            }
        }
        
        // 4. Cleanup
        if (activeBinaries.isNotEmpty()) {
            deployedBinDir.listFiles()?.forEach { file ->
                if (file.extension == "so") {
                    if (!activeBinaries.contains(file.name)) {
                         // Check if it's a custom binary? (Usually in 'binaries' dir, not 'bin')
                         // But be safe and delete tiered mismatches.
                        if (file.delete()) {
                            Log.v(TAG, "Deleted unused/stale binary: ${file.name}")
                        }
                    }
                }
            }
        }
        
        Log.i(TAG, "Smart binary deployment complete. Active: $activeBinaries")
        return@withContext activeBinaries.isNotEmpty()
    }
    
    // Check if critical binaries are present
    private fun areCriticalBinariesPresent(binaries: Set<String>): Boolean {
        // Just check for a few key ones to decide if we need fallback
        return binaries.any { it.startsWith("libllama") } &&
               binaries.any { it.startsWith("libffmpeg") }
    }

    private fun scanAndCopy(sourceDir: File, destDir: File, tiers: List<String>): List<String> {
        val deployedFiles = mutableListOf<String>()
        val files = sourceDir.listFiles()?.filter { it.extension == "so" } ?: emptyList()
        if (files.isEmpty()) return emptyList()

        // Group by binary name
        val fileGroups = files.groupBy { file ->
            val filename = file.name
            var key = filename
            if (filename.startsWith("lib") && filename.endsWith(".so")) {
                val bareName = filename.substring(3, filename.length - 3)
                val parts = bareName.split("_")
                if (parts.size > 1) {
                    val potentialTier = parts.last()
                    if (potentialTier in CPU_TIER_SUFFIXES) {
                        key = bareName.substringBeforeLast("_")
                    }
                }
            }
            key
        }

        fileGroups.forEach { (_, groupFiles) ->
            val isTiered = groupFiles.any { f -> 
                val n = f.name
                CPU_TIER_SUFFIXES.any { tier -> n.contains("_$tier.so") }
            }

            val bestFile = if (isTiered) {
                tiers.firstNotNullOfOrNull { tier ->
                    groupFiles.find { it.name.endsWith("_$tier.so") }
                }
            } else {
                groupFiles.firstOrNull()
            } ?: return@forEach

            val destFile = File(destDir, bestFile.name)
            try {
                // Only copy if size differs or missing (simple check)
                // Or if we want to ensure executable bit?
                // Files in nativeLibraryDir are not writable/executable by us usually?
                // We copy to filesDir to make them executable if needed (though shared libs usually don't need +x unless executed directly)
                // Binaries like ffmpeg DO need +x.
                if (!destFile.exists() || destFile.length() != bestFile.length()) {
                    bestFile.copyTo(destFile, overwrite = true)
                    destFile.setExecutable(true)
                    Log.v(TAG, "Copied ${bestFile.name} from ${sourceDir.absolutePath}")
                }
                deployedFiles.add(bestFile.name)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy ${bestFile.name}", e)
            }
        }
        return deployedFiles
    }

    /**
     * Extract binaries from APK and return list of filenames deployed.
     */
    private fun extractFromApk(apkFile: File, destDir: File, tiers: List<String>): List<String> {
        val deployedFiles = mutableListOf<String>()
        val zip = java.util.zip.ZipFile(apkFile)
        try {
            val entries = zip.entries()
            val soEntries = mutableListOf<java.util.zip.ZipEntry>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.endsWith(".so")) {
                    soEntries.add(entry)
                }
            }

            // Group by binary name
            val fileGroups = soEntries.groupBy { entry ->
                val filename = File(entry.name).name 
                var key = filename
                
                if (filename.startsWith("lib") && filename.endsWith(".so")) {
                    val bareName = filename.substring(3, filename.length - 3)
                    val parts = bareName.split("_")
                    if (parts.size > 1) {
                        val potentialTier = parts.last()
                         if (potentialTier in CPU_TIER_SUFFIXES) {
                            key = bareName.substringBeforeLast("_")
                        }
                    }
                }
                key
            }

            fileGroups.forEach { (_, groupEntries) ->
                val isTiered = groupEntries.any { e ->
                    val n = File(e.name).name
                    CPU_TIER_SUFFIXES.any { tier -> n.contains("_$tier.so") }
                }

                val bestEntry = if (isTiered) {
                    tiers.firstNotNullOfOrNull { tier ->
                        groupEntries.find { File(it.name).name.endsWith("_$tier.so") }
                    }
                } else {
                    groupEntries.firstOrNull()
                } ?: return@forEach

                val filename = File(bestEntry.name).name
                val destFile = File(destDir, filename)

                if (!destFile.exists() || destFile.length() != bestEntry.size) {
                    zip.getInputStream(bestEntry).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    destFile.setExecutable(true)
                    Log.v(TAG, "Extracted $filename")
                }
                deployedFiles.add(filename)
            }

        } finally {
            zip.close()
        }
        return deployedFiles
    }

    /**
     * Fallback deploy using PackageContext (if APK extraction fails).
     */
    private fun legacyDeploy(destDir: File, tiers: List<String>): List<String> {
         val deployedFiles = mutableListOf<String>()
         
         val currentTier = getTier()
         val featurePackages = listOf(
            "com.example.llamadroid.feature.llm.$currentTier",
            "com.example.llamadroid.feature.media.$currentTier",
            "com.example.llamadroid.feature.kiwix.$currentTier",
            "com.example.llamadroid.feature.upscaler"
        )
        
        featurePackages.forEach { pkgName ->
             try {
                val featureContext = context.createPackageContext(pkgName, 0)
                val featureLibDir = File(featureContext.applicationInfo.nativeLibraryDir)
                
                if (featureLibDir.exists() && featureLibDir.isDirectory) {
                    val files = featureLibDir.listFiles()?.filter { it.extension == "so" } ?: emptyList()
                    
                    // Grouping Logic (Same as APK)
                    val fileGroups = files.groupBy { file ->
                        val name = file.name
                        var key = name
                        if (name.startsWith("lib") && name.endsWith(".so")) {
                            val bareName = name.substring(3, name.length - 3)
                            val parts = bareName.split("_")
                            if (parts.size > 1) {
                                val potentialTier = parts.last()
                                if (potentialTier in CPU_TIER_SUFFIXES) {
                                    key = bareName.substringBeforeLast("_")
                                }
                            }
                        }
                        key
                    }
                    
                    fileGroups.forEach groupLoop@ { (_, groupFiles) ->
                        val isTiered = groupFiles.any { f ->
                            val n = f.name
                            CPU_TIER_SUFFIXES.any { tier -> n.contains("_$tier.so") }
                        }

                        val bestFile = if (isTiered) {
                            tiers.firstNotNullOfOrNull { tier ->
                                groupFiles.find { it.name.endsWith("_$tier.so") }
                            }
                        } else {
                            groupFiles.firstOrNull()
                        } ?: return@groupLoop

                        val destFile = File(destDir, bestFile.name)
                        try {
                            if (!destFile.exists() || destFile.length() != bestFile.length()) {
                                bestFile.copyTo(destFile, overwrite = true)
                                destFile.setExecutable(true)
                            }
                            deployedFiles.add(bestFile.name)
                        } catch (e: Exception) {
                            Log.e(TAG, "Legacy copy failed for ${bestFile.name}", e)
                        }
                    }
                }
             } catch (e: Exception) {
                 // Ignore
             }
        }
        return deployedFiles
    }

}
