package com.example.llamadroid.service.perf

import java.io.File

/** Relative speed class of one core, derived from `cpuinfo_max_freq`. */
enum class CoreClass { LITTLE, BIG, PRIME }

/** How many allowed CPUs fall into each [CoreClass]. */
data class CoreMix(val prime: Int, val big: Int, val little: Int) {
    val isEmpty: Boolean get() = prime + big + little == 0
}

object CpuTopology {
    /** Max frequency (kHz) per CPU id, read from sysfs; unreadable cores are omitted. */
    fun readMaxFrequencies(cpuRoot: File = File("/sys/devices/system/cpu")): Map<Int, Long> =
        runCatching {
            cpuRoot.listFiles { f -> f.name.matches(CPU_DIR) }.orEmpty().mapNotNull { dir ->
                val id = dir.name.removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
                val khz = runCatching { File(dir, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }
                    .getOrNull() ?: return@mapNotNull null
                id to khz
            }.toMap()
        }.getOrDefault(emptyMap())

    /**
     * One distinct frequency means a symmetric chip (no classes). Two clusters are little+big;
     * three or more are little, big and a prime top cluster.
     */
    fun classify(maxKhz: Map<Int, Long>): Map<Int, CoreClass> {
        val distinct = maxKhz.values.distinct().sorted()
        if (distinct.size < 2) return emptyMap()
        val top = distinct.last()
        val bottom = distinct.first()
        return maxKhz.mapValues { (_, khz) ->
            when {
                khz == bottom -> CoreClass.LITTLE
                khz == top && distinct.size >= 3 -> CoreClass.PRIME
                else -> CoreClass.BIG
            }
        }
    }

    fun mix(allowed: Collection<Int>, classes: Map<Int, CoreClass>): CoreMix {
        if (classes.isEmpty()) return CoreMix(0, 0, 0)
        var prime = 0
        var big = 0
        var little = 0
        allowed.forEach { cpu ->
            when (classes[cpu]) {
                CoreClass.PRIME -> prime++
                CoreClass.BIG -> big++
                CoreClass.LITTLE -> little++
                null -> Unit
            }
        }
        return CoreMix(prime, big, little)
    }

    private val CPU_DIR = Regex("cpu[0-9]+")
}
