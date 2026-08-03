package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootlessStatsParsingTest {
    @Test
    fun readableMissingDeniedVendorAndMalformedNodesAreHandledWithoutFallbackValues() {
        assertEquals(450_000L, RootlessStatsParsing.firstLong("450000\n"))
        assertEquals(12L, RootlessStatsParsing.firstLong("12 vendor-extra"))
        assertNull(RootlessStatsParsing.firstLong(null)) // missing or permission denied
        assertNull(RootlessStatsParsing.firstLong("not-a-number"))
        assertNull(RootlessStatsParsing.firstLong(""))
    }

    @Test
    fun cpuDeltaUsesCountersAndRejectsInvalidOrResetSamples() {
        assertEquals(
            50f,
            RootlessStatsParsing.cpuUsagePercent(
                previousTotal = 1_000L,
                previousIdle = 100L,
                currentTotal = 1_100L,
                currentIdle = 150L
            )
        )
        assertNull(RootlessStatsParsing.cpuUsagePercent(100L, 20L, 90L, 30L))
        assertNull(RootlessStatsParsing.cpuUsagePercent(100L, 20L, 110L, 10L))
    }

    @Test
    fun schedulerAndIdleResidencyFallbacksProduceActivityDeltas() {
        assertEquals(
            50f,
            RootlessStatsParsing.runtimeUsagePercent(
                previousRuntimeNs = 1_000_000_000L,
                currentRuntimeNs = 1_500_000_000L,
                elapsedNs = 1_000_000_000L
            )
        )
        assertEquals(
            50f,
            RootlessStatsParsing.idleUsagePercent(
                previousIdleUs = 1_000_000L,
                currentIdleUs = 1_500_000L,
                elapsedUs = 1_000_000L
            )
        )
        assertNull(RootlessStatsParsing.runtimeUsagePercent(10L, 5L, 1_000L))
        assertNull(RootlessStatsParsing.idleUsagePercent(10L, 5L, 1_000L))
    }

    @Test
    fun frequencyAndTemperatureConversionsCoverCommonVendorUnits() {
        assertEquals(1_800L, RootlessStatsParsing.frequencyMHz(1_800_000L))
        assertEquals(1_800L, RootlessStatsParsing.frequencyMHz(1_800L))
        assertNull(RootlessStatsParsing.frequencyMHz(0L))
        assertEquals(42f, RootlessStatsParsing.temperatureCelsius(42_000L))
        assertEquals(42f, RootlessStatsParsing.temperatureCelsius(42_000_000L))
        assertNull(RootlessStatsParsing.temperatureCelsius(300_000_000L))
    }

    @Test
    fun batterySignAndSwapIoRateArePreserved() {
        assertEquals(-61f, RootlessStatsParsing.currentMilliAmpsFromMicroAmps(-61_000L))
        assertEquals(500L, RootlessStatsParsing.ioRatePerSecond(1_000L, 1_500L, 1_000L))
        assertNull(RootlessStatsParsing.ioRatePerSecond(1_500L, 1_000L, 1_000L))
        assertNull(RootlessStatsParsing.ioRatePerSecond(1_000L, 1_500L, 0L))
    }

    @Test
    fun swapAndZramValuesKeepUnitsAndFieldPositions() {
        assertEquals(7_340_032L, RootlessStatsParsing.swapBytesFromKilobytes(7_168L))
        assertNull(RootlessStatsParsing.swapBytesFromKilobytes(-1L))

        val zram = RootlessStatsParsing.parseZramMmStat(
            "1048576 524288 262144 0 524288 3 4 5"
        )
        assertEquals(1_048_576L, zram?.originalBytes)
        assertEquals(524_288L, zram?.compressedBytes)
        assertEquals(262_144L, zram?.memoryBytes)
        assertEquals(0L, zram?.memoryLimitBytes)
        assertEquals(524_288L, zram?.memoryUsedMaxBytes)

        val malformed = RootlessStatsParsing.parseZramMmStat("1048576 malformed 262144")
        assertEquals(1_048_576L, malformed?.originalBytes)
        assertNull(malformed?.compressedBytes)
        assertEquals(262_144L, malformed?.memoryBytes)
        assertNull(RootlessStatsParsing.parseZramMmStat("malformed"))
    }

    @Test
    fun kgslMemoryPrefersResidentCountersAndUsesVendorFallbacks() {
        assertEquals(
            768L,
            RootlessStatsParsing.kgslMemoryBytes(512L, 256L, listOf(9_999L))
        )
        assertEquals(
            4_096L,
            RootlessStatsParsing.kgslMemoryBytes(null, null, listOf(null, 4_096L))
        )
        assertNull(RootlessStatsParsing.kgslMemoryBytes(null, null, listOf(null, -1L)))
    }
}
