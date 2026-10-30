package com.example.llamadroid.service.perf

/** Parses and formats Linux CPU lists such as `0-3,6` (cpuset files, `Cpus_allowed_list`). */
object CpuList {
    /** Returns the sorted, de-duplicated CPU ids; malformed segments are ignored. */
    fun parse(text: String?): List<Int> {
        if (text.isNullOrBlank()) return emptyList()
        val cpus = sortedSetOf<Int>()
        text.trim().split(',').forEach { raw ->
            val part = raw.trim()
            if (part.isEmpty()) return@forEach
            val bounds = part.split('-')
            when (bounds.size) {
                1 -> bounds[0].trim().toIntOrNull()?.takeIf { it >= 0 }?.let(cpus::add)
                2 -> {
                    val from = bounds[0].trim().toIntOrNull()
                    val to = bounds[1].trim().toIntOrNull()
                    if (from != null && to != null && from >= 0 && to >= from && to - from <= MAX_CPU_ID) {
                        for (cpu in from..to) cpus.add(cpu)
                    }
                }
            }
        }
        return cpus.toList()
    }

    /** Compact form of [cpus], for example `[0,1,2,3,6]` becomes `0-3,6`. */
    fun format(cpus: Collection<Int>): String {
        val sorted = cpus.distinct().sorted()
        if (sorted.isEmpty()) return ""
        val out = StringBuilder()
        var start = sorted[0]
        var previous = start
        fun flush() {
            if (out.isNotEmpty()) out.append(',')
            if (start == previous) out.append(start) else out.append(start).append('-').append(previous)
        }
        for (index in 1 until sorted.size) {
            val cpu = sorted[index]
            if (cpu == previous + 1) {
                previous = cpu
            } else {
                flush()
                start = cpu
                previous = cpu
            }
        }
        flush()
        return out.toString()
    }

    /** Extracts the `Cpus_allowed_list` line of a `/proc/<pid>/status` text. */
    fun parseAllowedListFromStatus(statusText: String): String? =
        statusText.lineSequence()
            .firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private const val MAX_CPU_ID = 1023
}
