package com.example.llamadroid.service.perf

/** Rolling screen-on / screen-off generation speed of one server. */
data class ServerSpeedStats(
    val screenOnTps: Double?,
    val screenOnSamples: Int,
    val screenOffTps: Double?,
    val screenOffSamples: Int,
    /** CPUs the llama-server process was allowed to use at the last sample of each mode. */
    val screenOnCpus: String?,
    val screenOffCpus: String?
)

/** Pure aggregation behind [LockedScreenSpeedRecorder]; not thread-safe, callers synchronize. */
class LockedScreenSpeedAggregator(private val windowSize: Int = DEFAULT_WINDOW) {
    private class Mode {
        val samples = ArrayDeque<Double>()
        var cpus: String? = null
    }

    private class Entry {
        val on = Mode()
        val off = Mode()
    }

    private val entries = LinkedHashMap<Int, Entry>()

    fun record(key: Int, screenOn: Boolean, tokensPerSecond: Double, allowedCpus: String?) {
        if (!tokensPerSecond.isFinite() || tokensPerSecond <= 0.0) return
        val entry = entries.getOrPut(key) { Entry() }
        val mode = if (screenOn) entry.on else entry.off
        mode.samples.addLast(tokensPerSecond)
        while (mode.samples.size > windowSize) mode.samples.removeFirst()
        if (!allowedCpus.isNullOrBlank()) mode.cpus = allowedCpus
    }

    fun stats(key: Int): ServerSpeedStats? {
        val entry = entries[key] ?: return null
        return ServerSpeedStats(
            screenOnTps = entry.on.samples.takeIf { it.isNotEmpty() }?.average(),
            screenOnSamples = entry.on.samples.size,
            screenOffTps = entry.off.samples.takeIf { it.isNotEmpty() }?.average(),
            screenOffSamples = entry.off.samples.size,
            screenOnCpus = entry.on.cpus,
            screenOffCpus = entry.off.cpus
        )
    }

    fun keys(): Set<Int> = entries.keys.toSet()

    companion object {
        const val DEFAULT_WINDOW = 5
    }
}
