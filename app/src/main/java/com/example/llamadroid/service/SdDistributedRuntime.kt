package com.example.llamadroid.service

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class SdDistributedPlacementMode {
    AUTO_RAM,
    AUTO_FIT,
    COMPONENTS,
    MANUAL
}

enum class SdDistributedSplitMode(val cliName: String) {
    LAYER("layer"),
    ROW("row");

    companion object {
        fun fromCliName(value: String): SdDistributedSplitMode =
            entries.firstOrNull { it.cliName.equals(value, ignoreCase = true) } ?: LAYER
    }
}

enum class SdDistributedAutoRamScope {
    DIFFUSION_ONLY,
    FULL_PIPELINE;

    companion object {
        fun fromStoredValue(value: String?): SdDistributedAutoRamScope =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DIFFUSION_ONLY
    }
}

@Parcelize
data class SdDistributedRuntimeConfig(
    val enabled: Boolean = false,
    val rpcServers: String = "",
    val placementMode: SdDistributedPlacementMode = SdDistributedPlacementMode.AUTO_RAM,
    val backendSpec: String = "",
    val paramsBackendSpec: String = "",
    val autoFit: Boolean = false,
    val maxVramSpec: String = "",
    val splitMode: SdDistributedSplitMode = SdDistributedSplitMode.LAYER,
    val customFlags: String = ""
) : Parcelable {
    val hasRpcServers: Boolean
        get() = rpcServers.trim().isNotEmpty()

    fun normalizedBackendSpec(): String = backendSpec.trim()

    fun normalizedParamsBackendSpec(): String = paramsBackendSpec.trim()

    fun normalizedMaxVramSpec(): String = maxVramSpec.trim()
}

fun appendSdDistributedArgs(
    args: MutableList<String>,
    config: SdDistributedRuntimeConfig,
    binaryCapabilities: SdBinaryCapabilities? = null
) {
    if (!config.enabled) return

    if (config.hasRpcServers) {
        args.addAll(listOf("--rpc-servers", config.rpcServers.trim()))
    }

    if (config.autoFit || config.placementMode == SdDistributedPlacementMode.AUTO_FIT) {
        args.add("--auto-fit")
    } else {
        val backendSpec = config.normalizedBackendSpec()
        if (backendSpec.isNotEmpty()) {
            args.addAll(listOf("--backend", backendSpec))
        }
    }

    val paramsBackendSpec = config.normalizedParamsBackendSpec()
    if (paramsBackendSpec.isNotEmpty()) {
        args.addAll(listOf("--params-backend", paramsBackendSpec))
    }

    val maxVramSpec = config.normalizedMaxVramSpec()
    if (maxVramSpec.isNotEmpty()) {
        args.addAll(listOf("--max-vram", maxVramSpec))
    }

    args.addAll(listOf("--split-mode", SdDistributedSplitMode.LAYER.cliName))

    if (config.customFlags.isNotBlank()) {
        val customArgs = splitShellLikeArgs(config.customFlags)
        validateSdDistributedCustomFlags(customArgs)
        args.addAll(customArgs)
    }

    val requiredFlags = missingSdDistributedFlags(config, binaryCapabilities)
    if (requiredFlags.isNotEmpty()) {
        throw SdUnsupportedFlagsException(requiredFlags)
    }
}

fun buildSdDistributedPreviewArgs(config: SdDistributedRuntimeConfig): List<String> =
    mutableListOf<String>().also { appendSdDistributedArgs(it, config, SdBinaryCapabilities.ALLOW_ALL) }

fun missingSdDistributedFlags(
    config: SdDistributedRuntimeConfig,
    binaryCapabilities: SdBinaryCapabilities?
): List<String> {
    if (!config.enabled ||
        binaryCapabilities == null ||
        binaryCapabilities == SdBinaryCapabilities.ALLOW_ALL
    ) {
        return emptyList()
    }

    val requiredFlags = mutableSetOf<String>()
    if (config.hasRpcServers) requiredFlags += "--rpc-servers"
    if (config.autoFit || config.placementMode == SdDistributedPlacementMode.AUTO_FIT) {
        requiredFlags += "--auto-fit"
    } else if (config.normalizedBackendSpec().isNotEmpty()) {
        requiredFlags += "--backend"
    }
    if (config.normalizedParamsBackendSpec().isNotEmpty()) requiredFlags += "--params-backend"
    if (config.normalizedMaxVramSpec().isNotEmpty()) requiredFlags += "--max-vram"
    requiredFlags += "--split-mode"

    return requiredFlags
        .filterNot { flag -> binaryCapabilities.supports(flag) }
        .sorted()
}

fun validateSdDistributedCustomFlags(customArgs: List<String>) {
    customArgs.forEachIndexed { index, arg ->
        val lower = arg.lowercase()
        val splitModeValue = when {
            lower == "--split-mode" -> customArgs.getOrNull(index + 1)?.lowercase()
            lower.startsWith("--split-mode=") -> lower.substringAfter("=")
            else -> null
        }
        if (splitModeValue == SdDistributedSplitMode.ROW.cliName) {
            throw SdDisallowedDistributedFlagException("--split-mode row")
        }
    }
}

data class SdDistributedPlanningWorker(
    val id: Long = 0L,
    val host: String,
    val port: Int,
    val displayName: String,
    val ramMB: Int,
    val threads: Int,
    val backendDevice: String = "",
    val rpcIndex: Int,
    val isLocalMaster: Boolean = false,
    val allowedModules: Set<String> = SdDistributedModules.defaultModuleSet,
    val manualDiffusionSharePercent: Int? = null
) {
    val rpcName: String = if (isLocalMaster) "cpu" else "RPC$rpcIndex"
    val endpoint: String = "$host:$port"
}

object SdDistributedModules {
    const val DIFFUSION = "diffusion"
    const val TEXT_ENCODER = "te"
    const val VAE = "vae"
    const val CONTROLNET = "controlnet"
    const val UPSCALER = "upscaler"

    val defaultModuleSet: Set<String> = setOf(DIFFUSION, TEXT_ENCODER, VAE, CONTROLNET, UPSCALER)

    fun normalizeCsv(value: String): Set<String> =
        value.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map {
                when (it.lowercase()) {
                    "text_encoder", "text-encoder", "clip", "encoder" -> TEXT_ENCODER
                    "vae_tae", "vae/tae", "tae" -> VAE
                    else -> it.lowercase()
                }
            }
            .filter { it in defaultModuleSet }
            .toSet()
            .ifEmpty { defaultModuleSet }

    fun toCsv(modules: Set<String>): String =
        defaultModuleSet.filter { it in modules }.joinToString(",")
}

data class SdDistributedModuleAssignment(
    val module: String,
    val devices: List<String>,
    val estimatedRamShares: Map<String, Float> = emptyMap(),
    val estimatedLayerShares: Map<String, Int> = emptyMap(),
    val isSplit: Boolean = devices.size > 1
)

data class SdDistributedPlacementPlan(
    val rpcServers: String,
    val backendSpec: String,
    val paramsBackendSpec: String = "",
    val maxVramSpec: String = "",
    val assignments: List<SdDistributedModuleAssignment> = emptyList()
) {
    val isEmpty: Boolean = rpcServers.isBlank() || (backendSpec.isBlank() && maxVramSpec.isBlank())
}

data class SdRamPlannerOptions(
    val autoRamScope: SdDistributedAutoRamScope = SdDistributedAutoRamScope.DIFFUSION_ONLY,
    val useDiskParamsForSplitDiffusion: Boolean = false,
    val diffusionReserveMB: Int = 512,
    val firstRpcOverheadMB: Int = 512
) {
    val assignTextEncoder: Boolean
        get() = autoRamScope == SdDistributedAutoRamScope.FULL_PIPELINE
    val assignVae: Boolean
        get() = autoRamScope == SdDistributedAutoRamScope.FULL_PIPELINE
    val assignUpscaler: Boolean
        get() = autoRamScope == SdDistributedAutoRamScope.FULL_PIPELINE
}

fun buildRamWeightedSdPlacementPlan(
    workers: List<SdDistributedPlanningWorker>,
    options: SdRamPlannerOptions = SdRamPlannerOptions()
): SdDistributedPlacementPlan {
    val orderedWorkers = workers
        .filter { it.host.isNotBlank() && (it.isLocalMaster || it.port > 0) && it.ramMB > 0 }
        .sortedWith(
            compareBy<SdDistributedPlanningWorker> { if (it.isLocalMaster) -1 else 0 }
                .thenByDescending { if (it.isLocalMaster) Int.MAX_VALUE else it.ramMB }
                .thenByDescending { it.threads }
                .thenBy { it.rpcIndex }
                .thenBy { it.displayName.lowercase() }
        )
        .let { sorted ->
            var nextRpcIndex = 0
            sorted.map { worker ->
                if (worker.isLocalMaster) {
                    worker
                } else {
                    worker.copy(rpcIndex = nextRpcIndex++)
                }
            }
        }

    if (orderedWorkers.isEmpty()) return SdDistributedPlacementPlan(rpcServers = "", backendSpec = "")
    val remainingRam = orderedWorkers.associate { it.rpcName to it.ramMB }.toMutableMap()
    val firstRemote = orderedWorkers.firstOrNull { !it.isLocalMaster }
    if (firstRemote != null && options.firstRpcOverheadMB > 0) {
        remainingRam[firstRemote.rpcName] = (remainingRam[firstRemote.rpcName] ?: firstRemote.ramMB)
            .minus(options.firstRpcOverheadMB)
            .coerceAtLeast(1)
    }
    val maxVramSpec = buildSdDistributedMaxVramSpec(orderedWorkers, remainingRam)
    val assignments = mutableListOf<SdDistributedModuleAssignment>()

    fun assignWholeModule(module: String, budgetMB: Int) {
        val target = orderedWorkers
            .filter {
                val remaining = remainingRam[it.rpcName] ?: 0
                remaining >= budgetMB && remaining - budgetMB >= options.diffusionReserveMB
            }
            .filter { module in it.allowedModules }
            .minByOrNull { remainingRam[it.rpcName] ?: Int.MAX_VALUE }
            ?: return
        remainingRam[target.rpcName] = (remainingRam[target.rpcName] ?: 0) - budgetMB
        assignments += SdDistributedModuleAssignment(
            module = module,
            devices = listOf(target.rpcName),
            estimatedRamShares = mapOf(target.rpcName to 1f),
            estimatedLayerShares = mapOf(target.rpcName to 100)
        )
    }

    if (options.assignVae) assignWholeModule(SdDistributedModules.VAE, 1024)
    if (options.assignTextEncoder) assignWholeModule(SdDistributedModules.TEXT_ENCODER, 2048)
    if (options.assignUpscaler) assignWholeModule(SdDistributedModules.UPSCALER, 1024)

    val diffusionWorkers = orderedWorkers
        .filter { SdDistributedModules.DIFFUSION in it.allowedModules }
        .filter { (remainingRam[it.rpcName] ?: 0) >= 512 }
        .ifEmpty { orderedWorkers.filter { SdDistributedModules.DIFFUSION in it.allowedModules } }
        .sortedWith(compareByDescending<SdDistributedPlanningWorker> { remainingRam[it.rpcName] ?: it.ramMB }.thenBy { it.rpcIndex })

    if (diffusionWorkers.isEmpty()) {
        val assignmentByModule = assignments.associateBy { it.module }
        val backendParts = listOfNotNull(
            assignmentByModule[SdDistributedModules.TEXT_ENCODER]?.let { "te=${it.devices.first()}" },
            assignmentByModule[SdDistributedModules.VAE]?.let { "vae=${it.devices.first()}" },
            assignmentByModule[SdDistributedModules.UPSCALER]?.let { "upscaler=${it.devices.first()}" }
        )
        return SdDistributedPlacementPlan(
            rpcServers = orderedWorkers.filterNot { it.isLocalMaster }.joinToString(",") { it.endpoint },
            backendSpec = backendParts.joinToString(","),
            maxVramSpec = maxVramSpec,
            assignments = assignments
        )
    }

    val diffusionWeightTotal = diffusionWorkers.sumOf { (remainingRam[it.rpcName] ?: it.ramMB).coerceAtLeast(1) }
    val manualMasterShare = diffusionWorkers
        .firstOrNull { it.isLocalMaster }
        ?.manualDiffusionSharePercent
        ?.coerceIn(1, 95)

    val estimatedShares = if (manualMasterShare != null) {
        val master = diffusionWorkers.first { it.isLocalMaster }
        val remotes = diffusionWorkers.filterNot { it.isLocalMaster }
        if (remotes.isEmpty()) {
            mapOf(master.rpcName to 1f)
        } else {
            val remoteTotal = remotes.sumOf { (remainingRam[it.rpcName] ?: it.ramMB).coerceAtLeast(1) }.coerceAtLeast(1)
            buildMap {
                put(master.rpcName, manualMasterShare / 100f)
                remotes.forEach { worker ->
                    val remoteWeight = (remainingRam[worker.rpcName] ?: worker.ramMB).coerceAtLeast(1).toFloat() / remoteTotal.toFloat()
                    put(worker.rpcName, remoteWeight * ((100 - manualMasterShare) / 100f))
                }
            }
        }
    } else {
        diffusionWorkers.associate { worker ->
        val share = (remainingRam[worker.rpcName] ?: worker.ramMB).coerceAtLeast(1).toFloat() / diffusionWeightTotal.toFloat()
        worker.rpcName to share
        }
    }
    val estimatedLayers = normalizePercentages(estimatedShares)
    assignments.add(
        0,
        SdDistributedModuleAssignment(
            module = SdDistributedModules.DIFFUSION,
            devices = diffusionWorkers.map { it.rpcName },
            estimatedRamShares = estimatedShares,
            estimatedLayerShares = estimatedLayers
        )
    )

    val assignmentByModule = assignments.associateBy { it.module }
    val backendParts = listOfNotNull(
        assignmentByModule[SdDistributedModules.DIFFUSION]?.let { "diffusion=${it.devices.joinToString("&")}" },
        assignmentByModule[SdDistributedModules.TEXT_ENCODER]?.let { "te=${it.devices.first()}" },
        assignmentByModule[SdDistributedModules.VAE]?.let { "vae=${it.devices.first()}" },
        assignmentByModule[SdDistributedModules.UPSCALER]?.let { "upscaler=${it.devices.first()}" }
    )

    val paramsBackendSpec =
        if (options.useDiskParamsForSplitDiffusion && (assignmentByModule[SdDistributedModules.DIFFUSION]?.isSplit == true)) {
            "diffusion=disk"
        } else {
            ""
        }

    return SdDistributedPlacementPlan(
        rpcServers = orderedWorkers.filterNot { it.isLocalMaster }.joinToString(",") { it.endpoint },
        backendSpec = backendParts.joinToString(","),
        paramsBackendSpec = paramsBackendSpec,
        maxVramSpec = maxVramSpec,
        assignments = assignments
    )
}

private fun buildSdDistributedMaxVramSpec(
    workers: List<SdDistributedPlanningWorker>,
    effectiveRamByDevice: Map<String, Int>
): String =
    workers.joinToString(",") { worker ->
        val effectiveRam = effectiveRamByDevice[worker.rpcName] ?: worker.ramMB
        "${worker.rpcName}=${formatSdMaxVramGiB(effectiveRam)}"
    }

private fun formatSdMaxVramGiB(ramMB: Int): String {
    val gib = ramMB.toFloat() / 1024f
    val roundedTenths = kotlin.math.round(gib * 10f) / 10f
    return if (roundedTenths % 1f == 0f) {
        roundedTenths.toInt().toString()
    } else {
        "%.1f".format(java.util.Locale.US, roundedTenths)
    }
}

private fun normalizePercentages(shares: Map<String, Float>): Map<String, Int> {
    if (shares.isEmpty()) return emptyMap()
    val raw = shares.mapValues { (_, value) -> (value * 100f).toInt().coerceAtLeast(1) }.toMutableMap()
    var delta = 100 - raw.values.sum()
    val keysByShare = shares.entries.sortedByDescending { it.value }.map { it.key }
    var index = 0
    while (delta != 0 && keysByShare.isNotEmpty()) {
        val key = keysByShare[index % keysByShare.size]
        val current = raw[key] ?: 0
        if (delta > 0) {
            raw[key] = current + 1
            delta--
        } else if (current > 1) {
            raw[key] = current - 1
            delta++
        } else {
            index++
        }
        index++
    }
    return raw
}

fun splitShellLikeArgs(value: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaping = false

    value.forEach { ch ->
        when {
            escaping -> {
                current.append(ch)
                escaping = false
            }
            ch == '\\' -> escaping = true
            quote != null && ch == quote -> quote = null
            quote != null -> current.append(ch)
            ch == '\'' || ch == '"' -> quote = ch
            ch.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
            }
            else -> current.append(ch)
        }
    }

    if (current.isNotEmpty()) result += current.toString()
    return result
}
