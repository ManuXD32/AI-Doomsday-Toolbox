package com.example.llamadroid.service

import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdWorkerAdvertTest {
    private fun attrs(vararg pairs: Pair<String, String>): Map<String, ByteArray?> =
        pairs.associate { it.first to it.second.toByteArray(Charsets.UTF_8) }

    @Test
    fun txtContainsCapacityAndVpnWhenPresent() {
        val txt = SdWorkerAdvert.buildTxt(ramFreeMb = 3072, cores = 8, appVersion = "1.0", vpnIp = "100.64.1.2")
        assertEquals("3072", txt["ram_free_mb"])
        assertEquals("8", txt["cores"])
        assertEquals("1.0", txt["app_ver"])
        assertEquals("100.64.1.2", txt["vpn_ip"])
        assertFalse(SdWorkerAdvert.buildTxt(3072, 8, "1.0", null).containsKey("vpn_ip"))
    }

    @Test
    fun parseReadsTxtAndStripsPrefix() {
        val worker = SdWorkerAdvert.parse(
            "SdWorker-Pixel7", "192.168.1.5", 50062,
            attrs("ram_free_mb" to "3072", "cores" to "8", "app_ver" to "1.0"),
            localHasVpn = false
        )
        assertEquals("Pixel7", worker.name)
        assertEquals("192.168.1.5", worker.host)
        assertEquals(3072, worker.ramMb)
        assertEquals(8, worker.cores)
        assertEquals("192.168.1.5:50062", worker.address)
    }

    @Test
    fun parsePrefersVpnOnlyWhenLocalAlsoHasVpn() {
        val a = attrs("vpn_ip" to "100.64.1.2")
        assertEquals("100.64.1.2", SdWorkerAdvert.parse("SdWorker-A", "192.168.1.5", 1, a, true).host)
        assertEquals("192.168.1.5", SdWorkerAdvert.parse("SdWorker-A", "192.168.1.5", 1, a, false).host)
    }

    @Test
    fun parseWithoutTxtUsesDefaults() {
        val worker = SdWorkerAdvert.parse("SdWorker-A", "10.0.0.2", 50062, null, false)
        assertEquals(null, worker.ramMb)
        val entity = worker.toEntity(sortOrder = 3)
        assertEquals(4096, entity.ramMB)
        assertEquals(4, entity.threads)
        assertEquals(3, entity.sortOrder)
    }

    @Test
    fun discoveredWorkerBecomesSavedAndIsHiddenAfterwards() {
        val found = SdWorkerAdvert.parse(
            "SdWorker-Pixel7", "10.0.0.2", 50062,
            attrs("ram_free_mb" to "2048", "cores" to "8"), false
        )
        val entity = found.toEntity(sortOrder = 1)
        assertEquals("10.0.0.2", entity.host)
        assertEquals(50062, entity.port)
        assertEquals("Pixel7", entity.deviceName)
        assertEquals(2048, entity.ramMB)
        assertEquals(4, entity.threads)
        assertTrue(entity.isEnabled)
        assertEquals(listOf(found), SdWorkerAdvert.unsaved(listOf(found), emptyList()))
        assertTrue(SdWorkerAdvert.unsaved(listOf(found), listOf<SdDistributedWorkerEntity>(entity)).isEmpty())
    }
}
