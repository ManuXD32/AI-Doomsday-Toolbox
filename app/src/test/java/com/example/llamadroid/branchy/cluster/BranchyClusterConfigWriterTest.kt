package com.example.llamadroid.branchy.cluster

import com.example.llamadroid.branchy.BranchyPlacement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchyClusterConfigWriterTest {
    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val devices = listOf(
        ClusterDeviceSpec("phone-b", "192.168.1.20", 50210, listOf(3, 1), 12.5),
        ClusterDeviceSpec("phone-c", "192.168.1.21", 50210, listOf(2), threads = 6)
    )

    @Test
    fun `branch parallel config has the native cluster json shape`() {
        val j = parse(BranchyClusterConfigWriter.write("run-1", BranchyClusterMode.BRANCH_PARALLEL, devices))
        assertEquals("run-1", j["cluster_id"]!!.jsonPrimitive.content)
        assertEquals("branch_parallel", j["mode"]!!.jsonPrimitive.content)
        assertEquals("f32", j["wire"]!!.jsonPrimitive.content)
        assertEquals(2, j["pipeline_depth"]!!.jsonPrimitive.int)
        assertEquals(3, j["absorb_microbatches"]!!.jsonPrimitive.int)
        assertFalse(j.containsKey("dp"))
        val devs = j["devices"]!!.jsonArray
        assertEquals(2, devs.size)
        val first = devs[0].jsonObject
        assertEquals("phone-b", first["id"]!!.jsonPrimitive.content)
        assertEquals("192.168.1.20", first["host"]!!.jsonPrimitive.content)
        assertEquals(50210, first["port"]!!.jsonPrimitive.int)
        assertEquals(listOf(1, 3), first["branches"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(12.5, first["gflops"]!!.jsonPrimitive.double, 0.0)
        assertFalse(devs[1].jsonObject.containsKey("gflops"))
        assertFalse(first.containsKey("threads"))
        assertEquals(6, devs[1].jsonObject["threads"]!!.jsonPrimitive.int)
    }

    @Test
    fun `data parallel config carries the dp block`() {
        val j = parse(
            BranchyClusterConfigWriter.write(
                "run-2", BranchyClusterMode.DATA_PARALLEL, devices, wire = "bf16", pipelineDepth = 1,
                dp = ClusterDpSettings(syncEvery = 32, syncSeconds = 5, bits = 4, outerMomentum = 0.9)
            )
        )
        assertEquals("data_parallel", j["mode"]!!.jsonPrimitive.content)
        assertEquals("bf16", j["wire"]!!.jsonPrimitive.content)
        val dp = j["dp"]!!.jsonObject
        assertEquals(32, dp["sync_every"]!!.jsonPrimitive.int)
        assertEquals(5, dp["sync_seconds"]!!.jsonPrimitive.int)
        assertEquals(4, dp["bits"]!!.jsonPrimitive.int)
        assertEquals(0.9, dp["outer_momentum"]!!.jsonPrimitive.double, 0.0)
        assertEquals("auto", dp["step_mode"]!!.jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty device list is rejected`() {
        BranchyClusterConfigWriter.write("x", BranchyClusterMode.BRANCH_PARALLEL, emptyList())
    }

    @Test
    fun `placement maps workers and reports devices without an endpoint`() {
        val placement = listOf(
            BranchyPlacement("local", "coordinator", listOf(0)),
            BranchyPlacement("phone-b", "worker", listOf(2, 1)),
            BranchyPlacement("phone-x", "worker", listOf(3)),
            BranchyPlacement("phone-idle", "worker", emptyList())
        )
        val endpoints = mapOf("phone-b" to ClusterEndpoint("10.0.0.2", 50210, 8.0))
        val (devs, missing) = BranchyClusterConfigWriter.devicesFromPlacement(placement, endpoints)
        assertEquals(listOf("phone-b"), devs.map { it.id })
        assertEquals(listOf(1, 2), devs.single().branches)
        assertEquals(8.0, devs.single().gflops!!, 0.0)
        assertEquals(listOf("phone-x"), missing)
    }

    @Test
    fun `coordinator entry is added only on request`() {
        val placement = listOf(
            BranchyPlacement("local", "coordinator", listOf(0)),
            BranchyPlacement("phone-b", "worker", listOf(1))
        )
        val endpoints = mapOf("phone-b" to ClusterEndpoint("10.0.0.2", 50210))
        val (withCoord, _) = BranchyClusterConfigWriter.devicesFromPlacement(placement, endpoints, includeCoordinator = true)
        assertEquals(listOf("coordinator", "phone-b"), withCoord.map { it.id })
        assertEquals("127.0.0.1", withCoord[0].host)
        val (without, _) = BranchyClusterConfigWriter.devicesFromPlacement(placement, endpoints)
        assertEquals(listOf("phone-b"), without.map { it.id })
    }

    @Test
    fun `ram_mb is written for helpers and as a port-less coordinator entry`() {
        val devices = listOf(
            ClusterDeviceSpec("phone-b", "10.0.0.2", 50210, listOf(1), ramMb = 8192),
            ClusterDeviceSpec("phone-c", "10.0.0.3", 50210, listOf(2))
        )
        val json = parse(BranchyClusterConfigWriter.write("c", BranchyClusterMode.BRANCH_PARALLEL, devices, coordinatorRamMb = 12000))
        val arr = json["devices"]!!.jsonArray.map { it.jsonObject }
        assertEquals(8192, arr[0]["ram_mb"]!!.jsonPrimitive.int)
        assertFalse(arr[1].containsKey("ram_mb"))
        val coord = arr.last()
        assertEquals("coordinator", coord["id"]!!.jsonPrimitive.content)
        assertEquals(12000, coord["ram_mb"]!!.jsonPrimitive.int)
        assertFalse(coord.containsKey("port") || coord.containsKey("branches"))
        val none = parse(BranchyClusterConfigWriter.write("c", BranchyClusterMode.BRANCH_PARALLEL, devices))
        assertEquals(2, none["devices"]!!.jsonArray.size)
    }

    @Test
    fun `cluster id is made native safe`() {
        assertEquals("my-run-1", BranchyClusterConfigWriter.clusterId("My Run 1"))
        assertEquals("cluster", BranchyClusterConfigWriter.clusterId("***"))
        assertTrue(BranchyClusterConfigWriter.clusterId("a".repeat(100)).length <= 40)
    }

    @Test
    fun `json arrays stay sorted and typed`() {
        val text = BranchyClusterConfigWriter.write("c", BranchyClusterMode.BRANCH_PARALLEL, devices)
        val arr: JsonArray = parse(text)["devices"]!!.jsonArray
        assertNull(arr[1].jsonObject["gflops"])
    }

    @Test
    fun `device with an alt host writes the ordered hosts list and keeps host first`() {
        val j = parse(
            BranchyClusterConfigWriter.write(
                "run-3", BranchyClusterMode.BRANCH_PARALLEL,
                listOf(ClusterDeviceSpec("phone-b", "100.64.0.2", 50210, listOf(0), altHost = "192.168.1.20"))
            )
        )
        val dev = j["devices"]!!.jsonArray[0].jsonObject
        assertEquals("100.64.0.2", dev["host"]!!.jsonPrimitive.content)
        assertEquals(listOf("100.64.0.2", "192.168.1.20"), dev["hosts"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
