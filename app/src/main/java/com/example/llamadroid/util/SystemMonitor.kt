package com.example.llamadroid.util

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import kotlin.math.max

/** Compatibility shape used by existing worker notifications. */
data class SystemStats(
    val cpuUsagePercent: Int,
    val ramUsagePercent: Int,
    val freeRamGb: Float,
    val totalRamGb: Float
)

/**
 * Rootless Android/Linux telemetry collector.
 *
 * It only opens Android APIs and nodes that the application UID can read. No
 * shell, Termux, su, hidden API, or privileged service is used. Firmware may
 * hide individual nodes; those metrics remain unavailable in the snapshot.
 */
class SystemMonitor(private val context: Context) {
    private val appContext = context.applicationContext
    private var previousCpu: Map<String, CpuCounter> = emptyMap()
    private var previousSchedstat: Map<Int, Long> = emptyMap()
    private var previousCpuIdle: Map<Int, Long> = emptyMap()
    private var previousCpuAtNs: Long? = null
    private var previousNetworkIo: Pair<Long, Long>? = null
    private var previousIoAt: Long? = null

    /** Establishes cumulative-counter baselines before the first persisted sample. */
    fun warmUp() {
        readCpu()
        readSwap(System.currentTimeMillis())
    }

    fun observeStats(): Flow<SystemStats> = flow {
        while (true) {
            val snapshot = sample()
            val total = snapshot.memory.totalBytes ?: 0L
            val available = snapshot.memory.availableBytes ?: 0L
            emit(
                SystemStats(
                    cpuUsagePercent = snapshot.cpu.totalUsagePercent?.toInt() ?: 0,
                    ramUsagePercent = snapshot.memory.usagePercent?.toInt() ?: 0,
                    freeRamGb = available / GB.toFloat(),
                    totalRamGb = total / GB.toFloat()
                )
            )
            delay(2_000L)
        }
    }

    fun sample(now: Long = System.currentTimeMillis()): SystemStatsSnapshot {
        val memory = readMemory()
        val thermal = readThermalStatus()
        return SystemStatsSnapshot(
            timestampEpochMs = now,
            device = readDevice(),
            cpu = readCpu(),
            temperatures = readTemperatures(thermal),
            gpu = readGpu(),
            battery = readBattery(),
            memory = memory,
            swap = readSwap(now),
            uptimeSeconds = readUptimeSeconds(),
            thermalStatus = thermal.value,
            thermalStatusAvailability = thermal.availability
        )
    }

    private fun readDevice(): DeviceStats = DeviceStats(
        manufacturer = Build.MANUFACTURER.orUnavailable(),
        model = Build.MODEL.orUnavailable(),
        board = Build.BOARD.orUnavailable(),
        hardware = Build.HARDWARE.orUnavailable(),
        androidVersion = Build.VERSION.RELEASE.orUnavailable(),
        sdkInt = Build.VERSION.SDK_INT,
        cpuAbi = Build.SUPPORTED_ABIS.firstOrNull().orUnavailable()
    )

    private fun readThermalStatus(): ThermalReading {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ThermalReading(
                value = null,
                availability = StatsAvailability(
                    false,
                    source = "PowerManager",
                    reason = "Thermal status requires Android 10 or newer"
                )
            )
        }
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return ThermalReading(
                value = null,
                availability = StatsAvailability(false, "PowerManager", "PowerManager is unavailable")
            )
        return ThermalReading(
            value = power.currentThermalStatus,
            availability = StatsAvailability(true, "PowerManager")
        )
    }

    private fun readCpu(): CpuStats {
        val lines = readLines(File("/proc/stat"), "procfs:/proc/stat")
        val current = linkedMapOf<String, CpuCounter>()
        lines.filter { it.startsWith("cpu") && it.getOrNull(3)?.isDigit() == true }.forEach { line ->
            val fields = line.trim().split(WHITESPACE)
            val name = fields.firstOrNull() ?: return@forEach
            val values = fields.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size >= 4) {
                current[name] = CpuCounter(
                    total = values.sum(),
                    idle = values[3] + (values.getOrNull(4) ?: 0L)
                )
            }
        }
        val totalCounter = parseCpuLine(lines.firstOrNull { it.startsWith("cpu ") })
        val schedstat = readSchedstat()
        val cpuIdle = readCpuIdleResidency(
            current.keys.mapNotNull { it.removePrefix("cpu").toIntOrNull() }.toSet() + schedstat.keys
        )
        val coreIndices = buildSet {
            current.keys.mapNotNullTo(this) { it.removePrefix("cpu").toIntOrNull() }
            schedstat.keys.forEach(::add)
            cpuIdle.keys.forEach(::add)
            discoverCpuIndices().forEach(::add)
        }
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val elapsedNs = previousCpuAtNs?.let { nowNs - it }?.takeIf { it > 0L }
        val elapsedUs = elapsedNs?.div(1_000L)
        val oldCpu = previousCpu
        val oldSchedstat = previousSchedstat
        val oldCpuIdle = previousCpuIdle
        val cores = coreIndices.sorted().map { core ->
            val name = "cpu$core"
            val procUsage = current[name]?.let { usageFor(name, it, oldCpu) }
            val schedUsage = if (procUsage == null && elapsedNs != null) {
                val previousRuntime = oldSchedstat[core]
                val currentRuntime = schedstat[core]
                if (previousRuntime != null && currentRuntime != null) {
                    RootlessStatsParsing.runtimeUsagePercent(previousRuntime, currentRuntime, elapsedNs)
                } else null
            } else null
            val idleUsage = if (procUsage == null && schedUsage == null && elapsedUs != null) {
                val previousIdle = oldCpuIdle[core]
                val currentIdle = cpuIdle[core]
                if (previousIdle != null && currentIdle != null) {
                    RootlessStatsParsing.idleUsagePercent(previousIdle, currentIdle, elapsedUs)
                } else null
            } else null
            val frequency = readCpuFrequency(core)
            CpuCoreStats(
                name = name,
                usagePercent = procUsage ?: schedUsage ?: idleUsage,
                frequencyMHz = frequency.value,
                frequencyAvailability = frequency.availability
            )
        }
        val procTotalUsage = totalCounter?.let { usageFor("cpu", it, oldCpu) }
        val fallbackUsages = cores.mapNotNull { core ->
            if (current[core.name]?.let { usageFor(core.name, it, oldCpu) } == null) core.usagePercent else null
        }
        val fallbackUsage = fallbackUsages.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        previousCpu = buildMap {
            totalCounter?.let { put("cpu", it) }
            putAll(current)
        }
        previousSchedstat = schedstat
        previousCpuIdle = cpuIdle
        previousCpuAtNs = nowNs
        val load = readFirstLine(File("/proc/loadavg"), "procfs:/proc/loadavg")
            ?.trim()?.split(WHITESPACE)?.firstOrNull()?.toFloatOrNull()
        val pressure = readPressure("cpu")
        val source = when {
            procTotalUsage != null -> "procfs:/proc/stat"
            cores.any { current[it.name] == null && it.usagePercent != null && oldSchedstat[it.name.removePrefix("cpu").toIntOrNull()] != null } -> "procfs:/proc/schedstat"
            cores.any { current[it.name] == null && it.usagePercent != null } -> "sysfs:cpuidle"
            current.isNotEmpty() -> "procfs:/proc/stat"
            coreIndices.isNotEmpty() -> "sysfs:/sys/devices/system/cpu"
            else -> "procfs:/proc/stat"
        }
        val hasCpuData = current.isNotEmpty() || schedstat.isNotEmpty() || cpuIdle.isNotEmpty() || coreIndices.isNotEmpty()
        return CpuStats(
            totalUsagePercent = procTotalUsage ?: fallbackUsage,
            cores = cores,
            load1m = load,
            pressureSomePercent = pressure,
            availability = if (hasCpuData) {
                StatsAvailability(true, source, if (procTotalUsage == null) "CPU usage uses a rootless fallback until procfs deltas are available" else null)
            } else {
                StatsAvailability(false, source, "No readable /proc/stat, /proc/schedstat, cpuidle, or CPU topology nodes")
            }
        )
    }

    private fun usageFor(name: String, counter: CpuCounter, previous: Map<String, CpuCounter> = previousCpu): Float? {
        val old = previous[name] ?: return null
        return RootlessStatsParsing.cpuUsagePercent(
            previousTotal = old.total,
            previousIdle = old.idle,
            currentTotal = counter.total,
            currentIdle = counter.idle
        )
    }

    private fun parseCpuLine(line: String?): CpuCounter? {
        val fields = line?.trim()?.split(WHITESPACE)?.drop(1)?.mapNotNull { it.toLongOrNull() } ?: return null
        if (fields.size < 4) return null
        return CpuCounter(fields.sum(), fields[3] + (fields.getOrNull(4) ?: 0L))
    }

    private fun readSchedstat(): Map<Int, Long> = readLines(
        File("/proc/schedstat"),
        "procfs:/proc/schedstat"
    ).mapNotNull { line ->
        val fields = line.trim().split(WHITESPACE)
        if (fields.size < 8) return@mapNotNull null
        val index = fields.firstOrNull()?.removePrefix("cpu")?.toIntOrNull() ?: return@mapNotNull null
        val runtime = fields.getOrNull(7)?.toLongOrNull()?.takeIf { it >= 0L } ?: return@mapNotNull null
        index to runtime
    }.toMap()

    private fun readCpuIdleResidency(candidateCores: Set<Int> = emptySet()): Map<Int, Long> {
        val result = linkedMapOf<Int, Long>()
        (candidateCores + discoverCpuIndices()).forEach { core ->
            val cpuRoot = File("/sys/devices/system/cpu/cpu$core/cpuidle")
            val states = cpuRoot.listFiles()?.filter { it.name.startsWith("state") }.orEmpty()
            val paths = if (states.isNotEmpty()) states.map { File(it, "time") } else {
                (0..31).map { File(cpuRoot, "state$it/time") }
            }
            val values = paths.mapNotNull { readLong(it, "sysfs:${it.path}")?.takeIf { value -> value >= 0L } }
            if (values.isNotEmpty()) result[core] = values.sum()
        }
        return result
    }

    private fun discoverCpuIndices(): Set<Int> {
        val result = linkedSetOf<Int>()
        File("/sys/devices/system/cpu").listFiles().orEmpty().forEach { entry ->
            entry.name.removePrefix("cpu").toIntOrNull()?.let(result::add)
        }
        return result
    }

    private fun readCpuFrequency(core: Int?): FrequencyReading {
        if (core == null) return FrequencyReading(null, StatsAvailability(false, "sysfs:cpufreq", "CPU core id unavailable"))
        val candidates = mutableListOf(
            "/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq",
            "/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_cur_freq",
            "/sys/devices/system/cpu/cpufreq/policy$core/scaling_cur_freq"
        )
        File("/sys/devices/system/cpu/cpufreq").listFiles()
            ?.filter { it.name.startsWith("policy") && policyContainsCore(it, core) }
            ?.forEach { policy ->
                candidates += File(policy, "scaling_cur_freq").path
                candidates += File(policy, "cpuinfo_cur_freq").path
            }
        var malformedSource: String? = null
        candidates.forEach { path ->
            val text = readFirstLine(File(path), "sysfs:$path")?.trim() ?: return@forEach
            val value = text.split(WHITESPACE).firstOrNull()?.toLongOrNull()
            val frequencyMHz = value?.let(RootlessStatsParsing::frequencyMHz)
            if (frequencyMHz != null) {
                return FrequencyReading(
                    frequencyMHz,
                    StatsAvailability(true, "sysfs:$path")
                )
            }
            malformedSource = "sysfs:$path"
        }
        return FrequencyReading(
            null,
            StatsAvailability(
                false,
                malformedSource ?: candidates.joinToString(",") { "sysfs:$it" },
                if (malformedSource != null) "CPU frequency value is malformed" else "CPU frequency nodes are missing or unreadable"
            )
        )
    }

    private fun policyContainsCore(policy: File, core: Int): Boolean {
        if (policy.name.removePrefix("policy").toIntOrNull() == core) return true
        return listOf("related_cpus", "affected_cpus").any { name ->
            val text = readFirstLine(
                File(policy, name),
                "sysfs:${File(policy, name).path}"
            ) ?: return@any false
            text.split(WHITESPACE).any { token ->
                token.toIntOrNull() == core || token.split('-', limit = 2).let { range ->
                    range.size == 2 && range[0].toIntOrNull()?.let { start ->
                        range[1].toIntOrNull()?.let { end -> core in start..end }
                    } == true
                }
            }
        }
    }

    private fun readMemory(): MemoryStats {
        val info = ActivityManager.MemoryInfo()
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        runCatching { manager?.getMemoryInfo(info) }
        val memInfo = readMemInfo()
        val total = if (info.totalMem > 0L) info.totalMem else memInfo["MemTotal"]
        val available = if (info.availMem > 0L) info.availMem else memInfo["MemAvailable"] ?: memInfo["MemFree"]
        val used = if (total != null && available != null) (total - available).coerceAtLeast(0L) else null
        val pressure = readPressure("memory")
        return MemoryStats(
            totalBytes = total,
            availableBytes = available,
            usedBytes = used,
            usagePercent = if (total != null && total > 0 && used != null) used * 100f / total else null,
            cacheBytes = memInfo["Cached"],
            anonymousBytes = (memInfo["AnonPages"] ?: 0L) + (memInfo["AnonHugePages"] ?: 0L),
            slabBytes = memInfo["Slab"],
            pressureSomePercent = pressure,
            availability = if (total != null || available != null) StatsAvailability(true, "android/procfs")
            else StatsAvailability(false, "android/procfs", "Memory APIs and /proc/meminfo unavailable")
        )
    }

    private fun readMemInfo(): Map<String, Long> {
        val path = File("/proc/meminfo")
        return readLines(path, "procfs:/proc/meminfo").mapNotNull { line ->
            val parts = line.split(WHITESPACE)
            val value = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val bytes = if (parts.getOrNull(2) == "kB") value * 1024L else value
            parts.firstOrNull()?.removeSuffix(":") to bytes
        }.filter { it.first != null }.associate { it.first!! to it.second }
    }

    private fun readBattery(): BatteryStats {
        val intent = runCatching {
            appContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val manager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val sysfs = readBatterySysfs()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 } ?: sysfs.level
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 }
        val levelPercent = if (scale != null) level?.let { (it * 100 / scale).coerceIn(0, 100) } else level
        val current = batteryProperty(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.let { RootlessStatsParsing.currentMilliAmpsFromMicroAmps(it.toLong()) }
            ?: sysfs.currentMilliAmps
        val average = batteryProperty(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            ?.let { RootlessStatsParsing.currentMilliAmpsFromMicroAmps(it.toLong()) }
            ?: sysfs.averageCurrentMilliAmps
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }?.div(1000f) ?: sysfs.voltageVolts
        val power = if (current != null && voltage != null) current * voltage / 1000f else null
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.div(10f) ?: sysfs.temperatureCelsius
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.takeIf { it >= 0 }?.let(::batteryStatus) ?: sysfs.status
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)?.takeIf { it >= 0 }?.let(::batteryHealth) ?: sysfs.health
        val source = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.takeIf { intent.hasExtra(BatteryManager.EXTRA_PLUGGED) }?.let(::batterySource) ?: sysfs.source
        // Android does not expose a stable cycle-count property across the API levels
        // supported by the app. Keep this unavailable rather than guessing from charge data.
        val cycles: Int? = sysfs.cycleCount
        val available = levelPercent != null || temp != null || voltage != null || current != null || status != null
        return BatteryStats(
            status = status,
            levelPercent = levelPercent,
            temperatureCelsius = temp,
            currentMilliAmps = current,
            averageCurrentMilliAmps = average,
            voltageVolts = voltage,
            powerWatts = power,
            source = source,
            health = health,
            chemistry = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            cycleCount = cycles,
            availability = if (available) StatsAvailability(
                true,
                listOfNotNull("BatteryManager/ACTION_BATTERY_CHANGED".takeIf { intent != null }, sysfs.sourcePath).joinToString("+")
                    .ifBlank { "BatteryManager" }
            ) else StatsAvailability(false, "BatteryManager/sysfs", "Battery broadcast, properties, and public power-supply nodes unavailable")
        )
    }

    private fun readBatterySysfs(): BatterySysfs {
        val roots = listOf(
            "/sys/class/power_supply/battery",
            "/sys/class/power_supply/BAT0",
            "/sys/class/power_supply/BMS",
            "/sys/class/power_supply/bms"
        ).map(::File)
        val root = roots.firstOrNull { directory ->
            listOf("capacity", "voltage_now", "current_now", "temp", "status")
                .any { name ->
                    val path = File(directory, name)
                    readFirstLine(path, "sysfs:${path.path}") != null
                }
        } ?: return BatterySysfs()
        fun text(name: String): String? = readFirstLine(File(root, name), "sysfs:${File(root, name).path}")?.trim()?.takeIf { it.isNotBlank() }
        fun long(name: String): Long? = text(name)?.toLongOrNull()
        val currentRaw = long("current_now") ?: long("current_avg")
        val tempRaw = long("temp_mC") ?: long("temp") ?: long("temp_c")
        return BatterySysfs(
            level = long("capacity")?.toInt()?.coerceIn(0, 100),
            status = text("status")?.lowercase(Locale.US)?.replace('-', '_'),
            health = text("health")?.lowercase(Locale.US)?.replace('-', '_'),
            source = text("online")?.let { if (it == "1") "external" else "battery" },
            currentMilliAmps = currentRaw?.let(RootlessStatsParsing::currentMilliAmpsFromMicroAmps),
            averageCurrentMilliAmps = long("current_avg")?.let(RootlessStatsParsing::currentMilliAmpsFromMicroAmps),
            voltageVolts = long("voltage_now")?.div(1_000_000f),
            temperatureCelsius = tempRaw?.let {
                when {
                    kotlin.math.abs(it) >= 10_000L -> it / 1000f
                    kotlin.math.abs(it) >= 200L -> it / 10f
                    else -> it.toFloat()
                }
            },
            cycleCount = long("cycle_count")?.toInt(),
            sourcePath = "sysfs:${root.path}"
        )
    }

    private fun batteryProperty(manager: BatteryManager?, property: Int): Int? =
        manager?.getIntProperty(property)?.takeUnless { it == Int.MIN_VALUE }

    private fun batteryStatus(value: Int): String = when (value) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    private fun batteryHealth(value: Int): String = when (value) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }

    private fun batterySource(value: Int): String = when (value) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        else -> "battery"
    }

    private fun readTemperatures(thermal: ThermalReading): List<TemperatureStats> {
        val root = File("/sys/class/thermal")
        val zones = root.listFiles()?.filter { it.name.startsWith("thermal_zone") }.orEmpty()
        val values = zones.map { zone ->
            val typePath = File(zone, "type")
            val tempPath = File(zone, "temp")
            val name = readFirstLine(typePath, "sysfs:${typePath.path}")?.trim()?.takeIf { it.isNotBlank() }
                ?: zone.name
            val raw = readLong(tempPath, "sysfs:${tempPath.path}")
            val celsius = raw?.let(::temperatureToCelsius)
            TemperatureStats(
                name = name,
                celsius = celsius,
                source = "sysfs:${tempPath.path}",
                availability = if (celsius != null) StatsAvailability(true, "sysfs:${tempPath.path}")
                else StatsAvailability(false, "sysfs:${tempPath.path}", "Temperature node unavailable")
            )
        }.toMutableList()
        val hwmonRoot = File("/sys/class/hwmon")
        hwmonRoot.listFiles().orEmpty().forEach { hwmon ->
            hwmon.listFiles().orEmpty()
                .filter { it.name.matches(Regex("temp\\d+_input")) }
                .forEach { input ->
                    val index = input.name.removePrefix("temp").removeSuffix("_input")
                    val label = readFirstLine(File(hwmon, "temp${index}_label"), "sysfs:${File(hwmon, "temp${index}_label").path}")
                        ?.trim()?.takeIf { it.isNotBlank() } ?: "${hwmon.name}:temp$index"
                    val raw = readLong(input, "sysfs:${input.path}")
                    val celsius = raw?.let(::temperatureToCelsius)
                    values += TemperatureStats(
                        name = label,
                        celsius = celsius,
                        source = "sysfs:${input.path}",
                        availability = if (celsius != null) StatsAvailability(true, "sysfs:${input.path}")
                        else StatsAvailability(false, "sysfs:${input.path}", "Hardware-monitor temperature node unavailable")
                    )
                }
        }
        if (values.isNotEmpty()) return values
        return thermal.value?.let {
            listOf(
                TemperatureStats(
                    name = "android_thermal_status",
                    celsius = null,
                    source = "PowerManager",
                    availability = StatsAvailability(true, "PowerManager", "Thermal status=$it; temperature is not exposed")
                )
            )
        }.orEmpty()
    }

    private fun temperatureToCelsius(raw: Long): Float? = RootlessStatsParsing.temperatureCelsius(raw)

    private fun readGpu(): GpuStats {
        val kgslCandidates = linkedSetOf<File>().apply {
            add(File("/sys/class/kgsl/kgsl-3d0"))
            add(File("/sys/class/kgsl/kgsl-3d0/device"))
            add(File("/sys/class/kgsl/kgsl"))
            File("/sys/class/kgsl").listFiles().orEmpty()
                .filter { it.name.contains("3d", ignoreCase = true) || it.name.contains("gpu", ignoreCase = true) }
                .forEach { add(it) }
        }
        kgslCandidates.forEach { kgsl ->
            val roots = listOf(kgsl, File(kgsl, "device"), File(kgsl, "devfreq"))
            val busy = roots.asSequence().mapNotNull { root ->
                val path = File(root, "gpubusy")
                readFirstLine(path, "sysfs:${path.path}")?.trim()?.split(WHITESPACE)?.mapNotNull { it.toLongOrNull() }
            }.firstOrNull()
            val load = busy?.takeIf { it.size >= 2 && it[1] > 0 }?.let { it[0] * 100f / it[1] }
                ?: roots.asSequence().mapNotNull { root ->
                    listOf("gpu_busy_percentage", "gpu_busy_percent", "busy_percent", "load").firstNotNullOfOrNull { name ->
                        val path = File(root, name)
                        readLong(path, "sysfs:${path.path}")?.toFloat()
                    }
                }.firstOrNull()
            val clock = roots.asSequence().mapNotNull { root ->
                listOf("gpuclk", "clock_mhz", "cur_freq", "devfreq/cur_freq").firstNotNullOfOrNull { name ->
                    val path = File(root, name)
                    readLong(path, "sysfs:${path.path}")?.let(::frequencyToMHz)
                }
            }.firstOrNull()
            val maxClock = roots.asSequence().mapNotNull { root ->
                listOf("max_gpuclk", "max_freq", "devfreq/max_freq").firstNotNullOfOrNull { name ->
                    val path = File(root, name)
                    readLong(path, "sysfs:${path.path}")?.let(::frequencyToMHz)
                }
            }.firstOrNull()
            val governor = roots.asSequence().mapNotNull { root ->
                listOf("devfreq/governor", "governor").firstNotNullOfOrNull { name ->
                    val path = File(root, name)
                    readFirstLine(path, "sysfs:${path.path}")?.trim()?.takeIf { it.isNotBlank() }
                }
            }.firstOrNull()
            val temperature = roots.asSequence().mapNotNull { root ->
                listOf("temp", "temperature", "gpu_temp", "devfreq/temp").firstNotNullOfOrNull { name ->
                    val path = File(root, name)
                    readLong(path, "sysfs:${path.path}")?.let(::temperatureToCelsius)
                }
            }.firstOrNull()
            val memory = readKgslMemory(roots)
            val available = load != null || clock != null || maxClock != null || governor != null || temperature != null || memory != null
            if (available) {
                return GpuStats(
                    name = "KGSL/Adreno",
                    loadPercent = load?.coerceIn(0f, 100f),
                    temperatureCelsius = temperature,
                    clockMHz = clock,
                    maxClockMHz = maxClock,
                    memoryBytes = memory,
                    governor = governor,
                    availability = StatsAvailability(true, "sysfs:${kgsl.path}")
                )
            }
        }
        val devfreqRoot = File("/sys/class/devfreq")
        val candidate = devfreqRoot.listFiles()?.firstOrNull { file ->
            val name = (readFirstLine(File(file, "name"), "sysfs:${File(file, "name").path}") ?: file.name).lowercase(Locale.US)
            name.contains("gpu") || name.contains("kgsl") || file.name.contains("gpu", ignoreCase = true)
        }
        if (candidate != null) {
            val cur = readLong(File(candidate, "cur_freq"), "sysfs:${File(candidate, "cur_freq").path}")?.let(::frequencyToMHz)
            val max = readLong(File(candidate, "max_freq"), "sysfs:${File(candidate, "max_freq").path}")?.let(::frequencyToMHz)
            val governor = readFirstLine(File(candidate, "governor"), "sysfs:${File(candidate, "governor").path}")
            val load = listOf("gpu_busy_percent", "busy_percent", "load")
                .firstNotNullOfOrNull { name -> readLong(File(candidate, name), "sysfs:${File(candidate, name).path}") }
                ?.toFloat()?.coerceIn(0f, 100f)
            val memory = readKgslMemory(listOf(candidate))
            val available = cur != null || max != null || governor != null || load != null || memory != null
            return GpuStats(
                name = candidate.name,
                loadPercent = load,
                clockMHz = cur,
                maxClockMHz = max,
                memoryBytes = memory,
                governor = governor,
                availability = if (available) {
                    StatsAvailability(true, "sysfs:${candidate.path}", if (load == null) "Load is not exposed by this devfreq driver" else null)
                } else {
                    StatsAvailability(false, "sysfs:${candidate.path}", "GPU devfreq nodes are present but unreadable")
                }
            )
        }
        return GpuStats()
    }

    private fun readKgslMemory(roots: List<File>): Long? {
        val candidates = roots.flatMap { root ->
            listOf("page_alloc", "coherent", "secure", "mapped", "vmalloc", "gmem_used", "gmem_total", "memory", "mem_usage")
                .map { name -> File(root, name) }
        }
        val pageAllocated = candidates.filter { it.name == "page_alloc" }.mapNotNull { path -> readLong(path, "sysfs:${path.path}") }.firstOrNull()
        val coherent = candidates.filter { it.name == "coherent" }.mapNotNull { path -> readLong(path, "sysfs:${path.path}") }.firstOrNull()
        val fallback = listOf("secure", "mapped", "vmalloc", "gmem_used", "gmem_total", "memory", "mem_usage")
            .asSequence()
            .flatMap { name -> candidates.filter { it.name == name }.asSequence() }
            .mapNotNull { path -> readLong(path, "sysfs:${path.path}") }
            .toList()
        return RootlessStatsParsing.kgslMemoryBytes(pageAllocated, coherent, fallback)
    }

    private fun frequencyToMHz(value: Long): Long? = RootlessStatsParsing.frequencyMHz(value)

    private fun readSwap(now: Long): SwapStats {
        val swapLines = readLines(File("/proc/swaps"), "procfs:/proc/swaps").drop(1)
        var total = 0L
        var used = 0L
        swapLines.forEach { line ->
            val fields = line.trim().split(WHITESPACE)
            total += RootlessStatsParsing.swapBytesFromKilobytes(fields.getOrNull(2)?.toLongOrNull()) ?: 0L
            used += RootlessStatsParsing.swapBytesFromKilobytes(fields.getOrNull(3)?.toLongOrNull()) ?: 0L
        }
        val memInfo = readMemInfo()
        val memTotal = memInfo["SwapTotal"]
        val memFree = memInfo["SwapFree"]
        val resolvedTotal = memTotal ?: total.takeIf { it > 0L }
        val resolvedUsed = if (memTotal != null && memFree != null) {
            (memTotal - memFree).coerceAtLeast(0L)
        } else used.takeIf { it > 0L }
        val vm = readVmStat()
        val io = readSwapIo(vm)
        val rate = previousIoAt?.let { oldAt ->
            val elapsedMs = max(1L, now - oldAt)
            val old = previousNetworkIo
            if (old != null && io != null) {
                Pair(
                    RootlessStatsParsing.ioRatePerSecond(old.first, io.first, elapsedMs),
                    RootlessStatsParsing.ioRatePerSecond(old.second, io.second, elapsedMs)
                )
            } else null
        }
        previousNetworkIo = io
        previousIoAt = now
        val zramDevices = discoverZramDevices().mapNotNull(::readZram)
        val zramDisk = zramDevices.sumNullable { it.diskBytes }
        val zramOriginal = zramDevices.sumNullable { it.originalBytes }
        val zramCompressed = zramDevices.sumNullable { it.compressedBytes }
        val zramMemory = zramDevices.sumNullable { it.memoryBytes }
        val zramLimit = zramDevices.sumNullable { it.memoryLimitBytes }
        val zramMax = zramDevices.sumNullable { it.memoryUsedMaxBytes }
        val available = memTotal != null || memFree != null || swapLines.isNotEmpty() || zramDevices.isNotEmpty()
        return SwapStats(
            totalBytes = resolvedTotal,
            usedBytes = resolvedUsed,
            readBytesPerSecond = rate?.first,
            writtenBytesPerSecond = rate?.second,
            zramDiskBytes = zramDisk,
            zramOriginalBytes = zramOriginal,
            zramCompressedBytes = zramCompressed,
            zramMemoryBytes = zramMemory,
            zramMemoryLimitBytes = zramLimit,
            zramMemoryUsedMaxBytes = zramMax,
            availability = if (available) StatsAvailability(true, "procfs:/proc/swaps/sysfs:/sys/block")
            else StatsAvailability(false, "procfs:/proc/swaps", "Swap and zram are unavailable")
        )
    }

    private fun discoverZramDevices(): List<File> = buildList {
        File("/sys/block").listFiles().orEmpty()
            .filter { it.name.startsWith("zram") }
            .forEach { add(it) }
        (0..15).forEach { index ->
            val device = File("/sys/block/zram$index")
            if (none { it.path == device.path }) add(device)
        }
    }

    private fun readZram(device: File): ZramReading? {
        val mmStatPath = File(device, "mm_stat")
        val parsedMmStat = RootlessStatsParsing.parseZramMmStat(
            readFirstLine(mmStatPath, "sysfs:${mmStatPath.path}")
        )
        val pageSize = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }
            .getOrNull()?.takeIf { it > 0L }
        val legacyMaxPages = readLong(File(device, "max_used_pages"), "sysfs:${File(device, "max_used_pages").path}")
        val reading = if (parsedMmStat != null) {
            ZramReading(
                diskBytes = readLong(File(device, "disksize"), "sysfs:${File(device, "disksize").path}"),
                originalBytes = parsedMmStat.originalBytes,
                compressedBytes = parsedMmStat.compressedBytes,
                memoryBytes = parsedMmStat.memoryBytes,
                memoryLimitBytes = parsedMmStat.memoryLimitBytes,
                memoryUsedMaxBytes = parsedMmStat.memoryUsedMaxBytes
            )
        } else {
            ZramReading(
                diskBytes = readLong(File(device, "disksize"), "sysfs:${File(device, "disksize").path}"),
                originalBytes = readLong(File(device, "orig_data_size"), "sysfs:${File(device, "orig_data_size").path}"),
                compressedBytes = readLong(File(device, "compr_data_size"), "sysfs:${File(device, "compr_data_size").path}"),
                memoryBytes = readLong(File(device, "mem_used_total"), "sysfs:${File(device, "mem_used_total").path}"),
                memoryLimitBytes = readLong(File(device, "mem_limit"), "sysfs:${File(device, "mem_limit").path}"),
                memoryUsedMaxBytes = legacyMaxPages?.let { pages ->
                    pageSize?.let { size -> runCatching { Math.multiplyExact(pages, size) }.getOrNull() }
                }
            )
        }
        return reading.takeIf {
            it.diskBytes != null || it.originalBytes != null || it.compressedBytes != null || it.memoryBytes != null ||
                it.memoryLimitBytes != null || it.memoryUsedMaxBytes != null
        }
    }

    private fun List<ZramReading>.sumNullable(selector: (ZramReading) -> Long?): Long? {
        val values = mapNotNull(selector)
        return values.takeIf { it.isNotEmpty() }?.fold(0L) { total, value ->
            runCatching { Math.addExact(total, value) }.getOrElse { Long.MAX_VALUE }
        }
    }

    private data class ZramReading(
        val diskBytes: Long?,
        val originalBytes: Long?,
        val compressedBytes: Long?,
        val memoryBytes: Long?,
        val memoryLimitBytes: Long?,
        val memoryUsedMaxBytes: Long?
    )

    private fun readVmStat(): Map<String, Long> =
        readLines(File("/proc/vmstat"), "procfs:/proc/vmstat").mapNotNull { line ->
            val fields = line.trim().split(WHITESPACE)
            val value = fields.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            fields.firstOrNull() to value
        }.filter { it.first != null }.associate { it.first!! to it.second }

    private fun readSwapIo(vmStat: Map<String, Long>): Pair<Long, Long>? {
        val pageSize = runCatching {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        }.getOrNull()?.takeIf { it > 0L } ?: return null
        val pagesIn = vmStat["pswpin"] ?: return null
        val pagesOut = vmStat["pswpout"] ?: return null
        val bytesIn = runCatching { Math.multiplyExact(pagesIn, pageSize) }.getOrNull() ?: return null
        val bytesOut = runCatching { Math.multiplyExact(pagesOut, pageSize) }.getOrNull() ?: return null
        return bytesIn to bytesOut
    }

    private fun readPressure(resource: String): Float? =
        readFirstLine(File("/proc/pressure/$resource"), "procfs:/proc/pressure/$resource")
            ?.substringAfter("some", "")?.substringAfter("avg10=", "")?.substringBefore(" ")?.toFloatOrNull()

    private fun readUptimeSeconds(): Long? = runCatching {
        SystemClock.elapsedRealtime().div(1000L)
    }.getOrNull() ?: readFirstLine(File("/proc/uptime"), "procfs:/proc/uptime")
        ?.trim()?.split(WHITESPACE)?.firstOrNull()?.toDoubleOrNull()?.toLong()

    private fun readLines(file: File, @Suppress("UNUSED_PARAMETER") source: String): List<String> =
        runCatching { file.bufferedReader().use { it.readLines() } }.getOrDefault(emptyList())

    private fun readFirstLine(file: File, @Suppress("UNUSED_PARAMETER") source: String): String? =
        runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull()

    private fun readLong(file: File, source: String): Long? =
        RootlessStatsParsing.firstLong(readFirstLine(file, source))

    private data class FrequencyReading(
        val value: Long?,
        val availability: StatsAvailability
    )

    private data class ThermalReading(
        val value: Int?,
        val availability: StatsAvailability
    )

    private data class BatterySysfs(
        val level: Int? = null,
        val status: String? = null,
        val health: String? = null,
        val source: String? = null,
        val currentMilliAmps: Float? = null,
        val averageCurrentMilliAmps: Float? = null,
        val voltageVolts: Float? = null,
        val temperatureCelsius: Float? = null,
        val cycleCount: Int? = null,
        val sourcePath: String? = null
    )

    private data class CpuCounter(val total: Long, val idle: Long)

    companion object {
        private const val GB = 1024L * 1024L * 1024L
        private val WHITESPACE = Regex("\\s+")
    }
}

private fun String?.orUnavailable(): String = this?.trim().orEmpty().ifBlank { "Unavailable" }
