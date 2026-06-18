package com.example.llamadroid.util

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

enum class AccelerationWorkload {
    LLM,
    STABLE_DIFFUSION
}

enum class AccelerationStatus {
    UNSUPPORTED,
    SUPPORTED_NOT_INSTALLED,
    INSTALLING,
    ACTIVE,
    CPU_SELECTED,
    CPU_FALLBACK,
    FAILED
}

data class AcceleratorReadiness(
    val workload: AccelerationWorkload,
    val status: AccelerationStatus,
    val modules: List<String>,
    val activeBinary: File? = null,
    val installProgress: Int? = null,
    val detail: String? = null
)

data class AcceleratorModuleState(
    val moduleName: String,
    val status: AccelerationStatus,
    val progress: Int? = null,
    val errorMessage: String? = null
)

object DeviceAcceleration {
    const val MODULE_LLM_SNAPDRAGON_OPENCL = "feature_llm_snapdragon_opencl"

    val llamaSnapdragonModules = listOf(
        MODULE_LLM_SNAPDRAGON_OPENCL
    )

    val stableDiffusionSnapdragonModules = emptyList<String>()

    private val _activeBinaries = MutableStateFlow<Map<AccelerationWorkload, String>>(emptyMap())
    val activeBinaries = _activeBinaries.asStateFlow()
    private val _runtimeFailures = MutableStateFlow<Map<AccelerationWorkload, String>>(emptyMap())
    val runtimeFailures = _runtimeFailures.asStateFlow()

    fun reportActiveBinary(workload: AccelerationWorkload, file: File?) {
        _activeBinaries.value = _activeBinaries.value.toMutableMap().apply {
            if (file == null) remove(workload) else put(workload, file.absolutePath)
        }
        if (file != null && isAcceleratorBinary(file)) {
            clearRuntimeFailure(workload)
        }
    }

    fun reportRuntimeFailure(workload: AccelerationWorkload, detail: String) {
        _runtimeFailures.value = _runtimeFailures.value.toMutableMap().apply {
            put(workload, detail)
        }
    }

    fun clearRuntimeFailure(workload: AccelerationWorkload) {
        _runtimeFailures.value = _runtimeFailures.value.toMutableMap().apply {
            remove(workload)
        }
    }

    fun isSnapdragonCompatible(): Boolean {
        val values = buildList {
            add(Build.MANUFACTURER)
            add(Build.BRAND)
            add(Build.DEVICE)
            add(Build.BOARD)
            add(Build.HARDWARE)
            add(Build.PRODUCT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Build.SOC_MANUFACTURER)
                add(Build.SOC_MODEL)
            }
        }.joinToString(" ").lowercase(Locale.US)

        return listOf("qualcomm", "snapdragon", "qcom", "sm8", "sm7", "sdm", "msm").any { it in values }
    }

    fun optionalModulesForDevice(): List<String> {
        if (!isSnapdragonCompatible()) return emptyList()
        return (llamaSnapdragonModules + stableDiffusionSnapdragonModules).distinct()
    }

    fun modulesFor(workload: AccelerationWorkload): List<String> = when (workload) {
        AccelerationWorkload.LLM -> llamaSnapdragonModules
        AccelerationWorkload.STABLE_DIFFUSION -> stableDiffusionSnapdragonModules
    }

    fun readiness(
        context: Context,
        workload: AccelerationWorkload,
        activeBinary: File?,
        accelerationMode: String = "auto"
    ): AcceleratorReadiness {
        if (!isSnapdragonCompatible()) {
            return AcceleratorReadiness(workload, AccelerationStatus.UNSUPPORTED, emptyList(), activeBinary)
        }
        val active = activeBinary?.let { isAcceleratorBinary(it) } == true
        if (active) {
            return AcceleratorReadiness(workload, AccelerationStatus.ACTIVE, modulesFor(workload), activeBinary)
        }
        if (accelerationMode.trim().lowercase(Locale.US).replace('_', '-') in setOf("cpu", "cpu-only")) {
            return AcceleratorReadiness(workload, AccelerationStatus.CPU_SELECTED, modulesFor(workload), activeBinary)
        }
        val modules = modulesFor(workload)
        val runtimeFailure = _runtimeFailures.value[workload]
        val installed = modules.any { DynamicFeatureManager.isModuleInstalled(context, it) }
        val states = DynamicFeatureManager.optionalModuleStates.value
        val moduleStates = modules.mapNotNull { states[it] }
        val failed = moduleStates.firstOrNull { it.status == AccelerationStatus.FAILED }
        val resolvedStatus = resolveReadinessStatus(
            isCompatible = true,
            installed = installed,
            active = active,
            moduleStates = moduleStates
        )
        val status = when {
            runtimeFailure != null && activeBinary != null -> AccelerationStatus.CPU_FALLBACK
            runtimeFailure != null -> AccelerationStatus.FAILED
            else -> resolvedStatus
        }
        val progress = moduleStates.mapNotNull { it.progress }.maxOrNull()
        return AcceleratorReadiness(
            workload = workload,
            status = status,
            modules = modules,
            activeBinary = activeBinary,
            installProgress = progress,
            detail = runtimeFailure ?: failed?.errorMessage
        )
    }

    fun isAcceleratorBinary(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        return "snapdragon_opencl" in name || "opencl" in name
    }

    fun acceleratorLibrarySearchDirs(): List<File> =
        listOf(
            "/vendor/lib64",
            "/vendor/lib64/egl",
            "/vendor/lib",
            "/vendor/lib/egl",
            "/system/vendor/lib64",
            "/system/vendor/lib64/egl",
            "/system/vendor/lib",
            "/system/vendor/lib/egl",
            "/odm/lib64",
            "/odm/lib64/egl",
            "/odm/lib",
            "/odm/lib/egl",
            "/product/lib64",
            "/product/lib",
            "/system_ext/lib64",
            "/system_ext/lib",
            "/system/lib64",
            "/system/lib",
            "/vendor/dsp/cdsp",
            "/vendor/lib/rfsa/adsp",
            "/system/lib/rfsa/adsp",
            "/dsp"
        ).map(::File)
            .filter { it.isDirectory }

    internal fun resolveReadinessStatus(
        isCompatible: Boolean,
        installed: Boolean,
        active: Boolean,
        moduleStates: List<AcceleratorModuleState>
    ): AccelerationStatus {
        if (!isCompatible) return AccelerationStatus.UNSUPPORTED
        if (active) return AccelerationStatus.ACTIVE
        if (moduleStates.any { it.status == AccelerationStatus.FAILED }) return AccelerationStatus.FAILED
        if (moduleStates.any { it.status == AccelerationStatus.INSTALLING }) return AccelerationStatus.INSTALLING
        if (installed) return AccelerationStatus.CPU_FALLBACK
        return AccelerationStatus.SUPPORTED_NOT_INSTALLED
    }
}
