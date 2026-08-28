package com.example.llamadroid.data.binary

import com.example.llamadroid.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class BinaryRepositoryTest {

    @Test
    fun buildBinarySearchTiers_prefersBaselineButFallsBackToInstalledDeviceTier() {
        assertEquals(
            listOf("baseline", "dotprod"),
            BinaryRepository.buildBinarySearchTiers(selectedTier = "baseline", deviceTier = "dotprod")
        )
    }

    @Test
    fun buildBinarySearchTiers_keepsArmv9FallbackOrderWhenBaselineMissing() {
        assertEquals(
            listOf("baseline", "armv9", "dotprod"),
            BinaryRepository.buildBinarySearchTiers(selectedTier = "baseline", deviceTier = "armv9")
        )
    }

    @Test
    fun buildBinarySearchTiers_preservesNormalDeviceTierFallbackChain() {
        assertEquals(
            listOf("armv9", "dotprod", "baseline"),
            BinaryRepository.buildBinarySearchTiers(selectedTier = "armv9", deviceTier = "armv9")
        )
    }

    @Test
    fun buildBinarySearchTiers_keepsI8mmFallbackOrder() {
        assertEquals(
            listOf("i8mm", "armv9", "dotprod", "baseline"),
            BinaryRepository.buildBinarySearchTiers(selectedTier = "i8mm", deviceTier = "armv9")
        )
    }

    @Test
    fun acceleratorLibNames_autoDoesNotMapExperimentalGpuPayloads() {
        assertEquals(
            emptyList<String>(),
            BinaryRepository.acceleratorLibNames("llama_server")
        )
        assertEquals(
            emptyList<String>(),
            BinaryRepository.acceleratorLibNames("llama-bench")
        )
        assertEquals(
            emptyList<String>(),
            BinaryRepository.acceleratorLibNames("sd")
        )
    }

    @Test
    fun acceleratorLibNames_honorsUserAccelerationMode() {
        assertEquals(
            emptyList<String>(),
            BinaryRepository.acceleratorLibNames("llama_server", SettingsRepository.ACCELERATION_CPU)
        )
        assertEquals(
            listOf("libllama_server_snapdragon_opencl.so"),
            BinaryRepository.acceleratorLibNames("llama_server", SettingsRepository.ACCELERATION_GPU)
        )
        assertEquals(
            emptyList<String>(),
            BinaryRepository.acceleratorLibNames("llama_server", SettingsRepository.ACCELERATION_NPU)
        )
        assertEquals(
            listOf("libllama-bench_snapdragon_opencl.so"),
            BinaryRepository.acceleratorLibNames("llama-bench", SettingsRepository.NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL)
        )
        assertEquals(
            emptyList<String>(),
            BinaryRepository.acceleratorLibNames("sd", SettingsRepository.NATIVE_BINARY_CPU_AUTO)
        )
        assertEquals(
            listOf("libsd_snapdragon_vulkan.so"),
            BinaryRepository.acceleratorLibNames("sd", SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_VULKAN)
        )
    }

    @Test
    fun exactCpuTierForNativeSelection_mapsConcreteCpuChoices() {
        assertEquals(
            "baseline",
            BinaryRepository.exactCpuTierForNativeSelection(SettingsRepository.NATIVE_BINARY_CPU_BASELINE)
        )
        assertEquals(
            "dotprod",
            BinaryRepository.exactCpuTierForNativeSelection(SettingsRepository.NATIVE_BINARY_CPU_DOTPROD)
        )
        assertEquals(
            "armv9",
            BinaryRepository.exactCpuTierForNativeSelection(SettingsRepository.NATIVE_BINARY_CPU_ARMV9)
        )
        assertEquals(
            "i8mm",
            BinaryRepository.exactCpuTierForNativeSelection(SettingsRepository.NATIVE_BINARY_CPU_I8MM)
        )
        assertEquals(
            null,
            BinaryRepository.exactCpuTierForNativeSelection(SettingsRepository.NATIVE_BINARY_AUTO)
        )
    }

    @Test
    fun resolveAutomaticCpuBinary_prefersUsableI8mm() {
        val modules = modules(
            i8mm = availability(usable = true),
            armv9 = availability(usable = true),
            dotprod = availability(usable = true),
            baseline = availability(usable = true)
        )

        val resolution = BinaryRepository.resolveLlamaBinary(RequestedLlamaBinary.AUTO, modules)

        assertEquals(EffectiveLlamaBinary.CPU_I8MM, resolution.effective)
        assertEquals(false, resolution.fallbackUsed)
    }

    @Test
    fun resolveExplicitI8mm_fallsBackWhenModuleMissing() {
        val modules = modules(
            i8mm = availability(
                installed = false,
                hardwareCompatible = true,
                complete = false,
                abiCompatible = true,
                reason = "i8mm module not delivered in this installation"
            ),
            dotprod = availability(usable = true),
            baseline = availability(usable = true)
        )

        val resolution = BinaryRepository.resolveLlamaBinary(RequestedLlamaBinary.CPU_I8MM, modules)

        assertEquals(EffectiveLlamaBinary.CPU_DOTPROD, resolution.effective)
        assertEquals(true, resolution.fallbackUsed)
        assertEquals("i8mm module not delivered in this installation", resolution.fallbackReason)
    }

    @Test
    fun resolveAutomaticCpuBinary_skipsQuarantinedI8mm() {
        val modules = modules(
            i8mm = BinaryAvailability(
                installed = true,
                hardwareCompatible = true,
                complete = true,
                abiCompatible = true,
                quarantined = true,
                unavailableReason = "i8mm is quarantined after a previous startup failure"
            ),
            armv9 = availability(usable = true),
            baseline = availability(usable = true)
        )

        val resolution = BinaryRepository.resolveLlamaBinary(RequestedLlamaBinary.AUTO, modules)

        assertEquals(EffectiveLlamaBinary.CPU_ARMV9, resolution.effective)
        assertEquals(true, resolution.fallbackUsed)
    }

    @Test
    fun resolveKvBackend_followsEffectiveBinary() {
        assertEquals(
            EffectiveKvBackend.CPU,
            BinaryRepository.resolveKvBackend(RequestedKvBackend.AUTO, EffectiveLlamaBinary.CPU_I8MM)
        )
        assertEquals(
            EffectiveKvBackend.OPENCL,
            BinaryRepository.resolveKvBackend(RequestedKvBackend.AUTO, EffectiveLlamaBinary.OPENCL)
        )
        assertEquals(
            EffectiveKvBackend.VULKAN,
            BinaryRepository.resolveKvBackend(RequestedKvBackend.ACCELERATOR, EffectiveLlamaBinary.VULKAN)
        )
        assertEquals(
            EffectiveKvBackend.CPU,
            BinaryRepository.resolveKvBackend(RequestedKvBackend.ACCELERATOR, EffectiveLlamaBinary.CPU_DOTPROD)
        )
    }

    private fun availability(
        usable: Boolean = false,
        installed: Boolean = usable,
        hardwareCompatible: Boolean = usable,
        complete: Boolean = usable,
        abiCompatible: Boolean = usable,
        reason: String? = null
    ): BinaryAvailability =
        BinaryAvailability(
            installed = installed,
            hardwareCompatible = hardwareCompatible,
            complete = complete,
            abiCompatible = abiCompatible,
            unavailableReason = reason
        )

    private fun modules(
        baseline: BinaryAvailability = availability(),
        dotprod: BinaryAvailability = availability(),
        armv9: BinaryAvailability = availability(),
        i8mm: BinaryAvailability = availability(),
        opencl: BinaryAvailability = availability(),
        vulkan: BinaryAvailability = availability()
    ): InstalledNativeModules =
        InstalledNativeModules(
            baseline = baseline,
            dotprod = dotprod,
            armv9 = armv9,
            i8mm = i8mm,
            opencl = opencl,
            vulkan = vulkan
        )
}
