package com.example.llamadroid.wear

import android.content.Context
import com.example.llamadroid.service.SystemStatsCollectionManager
import com.example.llamadroid.service.SystemStatsRepository
import com.example.llamadroid.util.SystemStatsSnapshot
import java.util.Locale
import kotlin.math.max

/** Builds the deliberately small 15-minute payload used by the watch. */
internal suspend fun buildWearStatsSnapshot(
    context: Context,
    request: StatsRequest
): WearStatsSnapshot {
    val now = System.currentTimeMillis()
    val from = request.sinceEpochMs.coerceAtMost(now).coerceAtLeast(now - 15L * 60L * 1000L)
    val until = request.untilEpochMs.coerceIn(from, now + 60_000L)
    val maxPoints = request.maxPoints.coerceIn(2, 15)
    val repository = SystemStatsRepository(context)
    val stored = repository.getSamples(from, until)
    val samples = if (stored.isEmpty()) listOf(repository.sampleLive(now)) else stored
    val points = downsample(samples, maxPoints)
    val latest = samples.lastOrNull()

    fun format(value: Float?, suffix: String = ""): String =
        value?.let { String.format(Locale.US, "%.1f%s", it, suffix) } ?: "—"

    val summary = linkedMapOf<String, String>()
    latest?.let { snapshot ->
        summary["cpu"] = format(snapshot.cpu.totalUsagePercent, "%")
        summary["ram"] = format(snapshot.memory.usagePercent, "%")
        summary["gpu"] = format(snapshot.gpu.loadPercent, "%")
        summary["battery"] = snapshot.battery.levelPercent?.let { "$it%" } ?: "—"
        summary["temperature"] = format(snapshot.temperatures.mapNotNull { it.celsius }.maxOrNull(), "°C")
        summary["uptime"] = snapshot.uptimeSeconds?.let(::formatDuration) ?: "—"
    }

    val availability = latest?.let { snapshot ->
        linkedMapOf(
            "cpu" to availabilityText(snapshot.cpu.availability),
            "ram" to availabilityText(snapshot.memory.availability),
            "gpu" to availabilityText(snapshot.gpu.availability),
            "battery" to availabilityText(snapshot.battery.availability),
            "temperature" to if (snapshot.temperatures.any { it.availability.available }) "available" else "unavailable",
            "thermal_status" to availabilityText(snapshot.thermalStatusAvailability)
        )
    }.orEmpty()

    return WearStatsSnapshot(
        revisioned = Revisioned(
            revision = now,
            updatedAtEpochMs = now,
            sourceDeviceId = context.packageName
        ),
        enabled = SystemStatsCollectionManager.isEnabled(context),
        sampledAtEpochMs = latest?.timestampEpochMs,
        summary = summary,
        series = listOf(
            WearStatsSeries("cpu", "%", points.map { WearStatsPoint(it.timestampEpochMs, it.cpu.totalUsagePercent) }),
            WearStatsSeries("ram", "%", points.map { WearStatsPoint(it.timestampEpochMs, it.memory.usagePercent) }),
            WearStatsSeries("gpu", "%", points.map { WearStatsPoint(it.timestampEpochMs, it.gpu.loadPercent) }),
            WearStatsSeries("battery", "%", points.map { WearStatsPoint(it.timestampEpochMs, it.battery.levelPercent?.toFloat()) }),
            WearStatsSeries("temperature", "°C", points.map { sample ->
                WearStatsPoint(sample.timestampEpochMs, sample.temperatures.mapNotNull { it.celsius }.maxOrNull())
            })
        ),
        availability = availability
    )
}

private fun downsample(samples: List<SystemStatsSnapshot>, maxPoints: Int): List<SystemStatsSnapshot> {
    if (samples.size <= maxPoints) return samples
    val step = (samples.size - 1).toFloat() / (maxPoints - 1).toFloat()
    return (0 until maxPoints).map { index -> samples[(index * step).toInt().coerceIn(0, samples.lastIndex)] }
}

private fun availabilityText(value: com.example.llamadroid.util.StatsAvailability): String =
    if (value.available) "available:${value.source.orEmpty()}" else "unavailable:${value.reason.orEmpty()}"

private fun formatDuration(seconds: Long): String {
    val safe = max(0L, seconds)
    val hours = safe / 3600L
    val minutes = (safe % 3600L) / 60L
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
