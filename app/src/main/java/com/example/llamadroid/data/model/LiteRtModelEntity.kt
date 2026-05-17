package com.example.llamadroid.data.model

import android.os.Build
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

const val LITERT_BACKEND_AUTO = "auto"
const val LITERT_BACKEND_CPU = "cpu"
const val LITERT_BACKEND_GPU = "gpu"

@Entity(
    tableName = "litert_models",
    indices = [
        Index("repoId"),
        Index("updatedAt")
    ]
)
data class LiteRtModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val path: String,
    val sourceUri: String? = null,
    val repoId: String? = null,
    val filename: String,
    val sizeBytes: Long = 0L,
    val backendPreference: String = LITERT_BACKEND_AUTO,
    val supportsCpu: Boolean = true,
    val supportsGpu: Boolean = true,
    val supportsNpu: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

fun normalizeLiteRtBackend(value: String?): String {
    val normalized = value
        ?.trim()
        ?.lowercase(Locale.US)
        ?.replace('_', '-')
        ?: return LITERT_BACKEND_AUTO
    return when (normalized) {
        LITERT_BACKEND_CPU, "cpu-only" -> LITERT_BACKEND_CPU
        LITERT_BACKEND_GPU, "gpu-only", "opencl", "vulkan" -> LITERT_BACKEND_GPU
        "npu", "npu-only", "qnn", "hexagon", "htp",
        "npu-force", "force-npu", "forced-npu", "npu-forced", "qnn-force" -> LITERT_BACKEND_AUTO
        else -> LITERT_BACKEND_AUTO
    }
}

fun LiteRtModelEntity.isLikelyLiteRtGpuPackage(): Boolean {
    val lower = filename.lowercase(Locale.US)
    return !lower.contains(".qualcomm.") &&
        !lower.contains("_qualcomm_") &&
        !lower.contains(".mediatek.") &&
        !lower.contains("_mediatek_")
}

fun LiteRtModelEntity.defaultLiteRtEngineMaxTokens(): Int? =
    liteRtEngineMaxTokensFromText(
        listOf(displayName, filename, repoId.orEmpty()).joinToString(" ")
    )

fun liteRtEngineMaxTokensFromText(text: String): Int? {
    val lower = text.lowercase(Locale.US)
    Regex("""(?:^|[^a-z0-9])ekv(\d{3,5})(?:[^a-z0-9]|$)""")
        .find(lower)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?.let { return it }

    return when {
        "gemma-4" in lower || "gemma 4" in lower -> 4000
        "gemma-3n" in lower || "gemma 3n" in lower -> 4096
        "gemma3-1b" in lower || "gemma 3 1b" in lower -> 1024
        "mobile-actions" in lower || "functiongemma" in lower -> 1024
        else -> null
    }
}

fun LiteRtModelEntity.liteRtPackageTarget(): String? =
    listOf(filename, repoId.orEmpty())
        .firstNotNullOfOrNull { text -> liteRtPackageTargetFromText(text) }

fun liteRtPackageTargetFromText(text: String): String? =
    Regex("""(?:^|[._-])((?:sm|qcs)\d{4})(?:[._-]|$)""")
        .find(text.lowercase(Locale.US))
        ?.groupValues
        ?.getOrNull(1)

data class LiteRtDeviceTargetInfo(
    val rawLabel: String,
    val normalizedTargets: Set<String>
) {
    val primaryTarget: String?
        get() = normalizedTargets.firstOrNull { it.startsWith("sm") || it.startsWith("qcs") }
}

fun currentLiteRtDeviceTargetInfo(): LiteRtDeviceTargetInfo {
    val rawParts = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Build.SOC_MANUFACTURER)
            add(Build.SOC_MODEL)
        }
        add(Build.HARDWARE)
        add(Build.BOARD)
        add(Build.DEVICE)
        add(Build.PRODUCT)
        add(Build.MODEL)
        add(Build.MANUFACTURER)
        add(Build.BRAND)
    }.filter { it.isNotBlank() }.distinct()
    return liteRtDeviceTargetInfoFromText(rawParts.joinToString(" "))
}

fun liteRtDeviceTargetInfoFromText(text: String): LiteRtDeviceTargetInfo {
    val lower = text.lowercase(Locale.US)
    val targets = linkedSetOf<String>()
    Regex("""(?:^|[^a-z0-9])((?:sm|qcs|mt)\d{4})(?:[^a-z0-9]|$)""")
        .findAll(lower)
        .forEach { match ->
            match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { targets += it }
        }

    liteRtDeviceAliases.forEach { (alias, target) ->
        if (lower.contains(alias)) targets += target
    }

    return LiteRtDeviceTargetInfo(
        rawLabel = text,
        normalizedTargets = targets
    )
}

fun liteRtPackageMatchesDeviceTarget(packageTarget: String?, deviceInfo: String): Boolean {
    val info = liteRtDeviceTargetInfoFromText(deviceInfo)
    return liteRtPackageMatchesDeviceTargets(packageTarget, info.normalizedTargets, deviceInfo)
}

fun liteRtPackageMatchesDeviceTargets(
    packageTarget: String?,
    deviceTargets: Set<String>,
    rawDeviceInfo: String = ""
): Boolean {
    val target = packageTarget?.lowercase(Locale.US) ?: return true
    if (deviceTargets.isEmpty() && rawDeviceInfo.isBlank()) return true
    if (target in deviceTargets) return true
    return rawDeviceInfo.lowercase(Locale.US).contains(target)
}

fun LiteRtModelEntity.isRecommendedForLiteRtDevice(deviceInfo: LiteRtDeviceTargetInfo): Boolean =
    liteRtPackageMatchesDeviceTargets(
        packageTarget = liteRtPackageTarget(),
        deviceTargets = deviceInfo.normalizedTargets,
        rawDeviceInfo = deviceInfo.rawLabel
    )

private val liteRtDeviceAliases = mapOf(
    // Snapdragon 8 Gen 3 devices commonly expose "pineapple" in Build.HARDWARE/BOARD.
    "pineapple" to "sm8650",
    // Snapdragon 8 Gen 2.
    "kalama" to "sm8550",
    // Snapdragon 8 Elite.
    "sun" to "sm8750"
)

fun liteRtPackageTargetDisplay(target: String?): String =
    target?.uppercase(Locale.US).orEmpty()
