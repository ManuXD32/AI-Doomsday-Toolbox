package com.example.llamadroid.util

import kotlinx.serialization.Serializable

/** A rootless probe result. Unreadable vendor nodes are represented explicitly. */
@Serializable
data class StatsAvailability(
    val available: Boolean,
    val source: String? = null,
    val reason: String? = null
)

@Serializable
data class DeviceStats(
    val manufacturer: String,
    val model: String,
    val board: String,
    val hardware: String,
    val androidVersion: String,
    val sdkInt: Int,
    val cpuAbi: String,
    val availability: StatsAvailability = StatsAvailability(true, "android")
)

@Serializable
data class CpuCoreStats(
    val name: String,
    val usagePercent: Float? = null,
    val frequencyMHz: Long? = null,
    val frequencyAvailability: StatsAvailability = StatsAvailability(false, reason = "CPU frequency node unavailable")
)

@Serializable
data class CpuStats(
    val totalUsagePercent: Float? = null,
    val cores: List<CpuCoreStats> = emptyList(),
    val load1m: Float? = null,
    val pressureSomePercent: Float? = null,
    val availability: StatsAvailability = StatsAvailability(false, reason = "CPU counters unavailable")
)

@Serializable
data class TemperatureStats(
    val name: String,
    val celsius: Float? = null,
    val source: String,
    val availability: StatsAvailability
)

@Serializable
data class GpuStats(
    val name: String? = null,
    val loadPercent: Float? = null,
    val temperatureCelsius: Float? = null,
    val clockMHz: Long? = null,
    val maxClockMHz: Long? = null,
    val memoryBytes: Long? = null,
    val governor: String? = null,
    val availability: StatsAvailability = StatsAvailability(false, reason = "GPU counters unavailable")
)

@Serializable
data class BatteryStats(
    val status: String? = null,
    val levelPercent: Int? = null,
    val temperatureCelsius: Float? = null,
    val currentMilliAmps: Float? = null,
    val averageCurrentMilliAmps: Float? = null,
    val voltageVolts: Float? = null,
    val powerWatts: Float? = null,
    val source: String? = null,
    val health: String? = null,
    val chemistry: String? = null,
    val cycleCount: Int? = null,
    val availability: StatsAvailability = StatsAvailability(false, reason = "Battery information unavailable")
)

@Serializable
data class MemoryStats(
    val totalBytes: Long? = null,
    val availableBytes: Long? = null,
    val usedBytes: Long? = null,
    val usagePercent: Float? = null,
    val cacheBytes: Long? = null,
    val anonymousBytes: Long? = null,
    val slabBytes: Long? = null,
    val pressureSomePercent: Float? = null,
    val availability: StatsAvailability = StatsAvailability(false, reason = "Memory information unavailable")
)

@Serializable
data class SwapStats(
    val totalBytes: Long? = null,
    val usedBytes: Long? = null,
    val readBytesPerSecond: Long? = null,
    val writtenBytesPerSecond: Long? = null,
    val zramDiskBytes: Long? = null,
    val zramOriginalBytes: Long? = null,
    val zramCompressedBytes: Long? = null,
    val zramMemoryBytes: Long? = null,
    val zramMemoryLimitBytes: Long? = null,
    val zramMemoryUsedMaxBytes: Long? = null,
    val availability: StatsAvailability = StatsAvailability(false, reason = "Swap information unavailable")
)

@Serializable
data class SystemStatsSnapshot(
    val timestampEpochMs: Long,
    val device: DeviceStats,
    val cpu: CpuStats,
    val temperatures: List<TemperatureStats>,
    val gpu: GpuStats,
    val battery: BatteryStats,
    val memory: MemoryStats,
    val swap: SwapStats,
    val uptimeSeconds: Long? = null,
    val thermalStatus: Int? = null,
    val thermalStatusAvailability: StatsAvailability = StatsAvailability(
        false,
        source = "PowerManager",
        reason = "Thermal status is unavailable on this Android version or device"
    )
)

@Serializable
data class StatsExport(
    val exportedAtEpochMs: Long,
    val fromEpochMs: Long,
    val untilEpochMs: Long,
    val samples: List<SystemStatsSnapshot>
)
