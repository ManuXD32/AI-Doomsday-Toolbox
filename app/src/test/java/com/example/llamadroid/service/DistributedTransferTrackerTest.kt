package com.example.llamadroid.service

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributedTransferTrackerTest {
    private val tensors = listOf(
        GgufTensorEntry("token_embd.weight", 500),
        GgufTensorEntry("blk.0.attn_q.weight", 100),
        GgufTensorEntry("blk.0.ffn_down.weight", 20),
        GgufTensorEntry("blk.1.attn_q.weight", 200),
        GgufTensorEntry("blk.2.attn_q.weight", 300),
        GgufTensorEntry("output_norm.weight", 5),
        GgufTensorEntry("output.weight", 50)
    )

    private fun worker(address: String, layers: Int) = DistributedLayerAllocation(
        DistributedDeviceRef(DistributedDeviceKind.WORKER, null, address), 1000, 0.5f, layers
    )

    @Test
    fun layerBytesSkipsEmbeddingAndPutsOutputOnLastLayer() {
        val layers = DistributedTransferMath.layerBytes(tensors, 4)
        assertEquals(listOf(120L, 200L, 300L, 55L), layers.toList())
    }

    @Test
    fun expectedPerHelperFollowsAllocationOrderAndSkipsMaster() {
        val layers = longArrayOf(120, 200, 300, 55)
        val allocations = listOf(
            DistributedLayerAllocation(DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU), 1000, 0.3f, 1),
            worker("10.0.0.2:50052", 2),
            worker("10.0.0.3:50052", 1)
        )
        val expected = DistributedTransferMath.expectedPerHelper(layers, allocations, emptyList())
        assertEquals(listOf("10.0.0.2:50052" to 500L, "10.0.0.3:50052" to 55L), expected)
    }

    @Test
    fun mapSentFillsHelpersInOrderAndClamps() {
        val expected = listOf("a" to 100L, "b" to 50L)
        assertEquals(listOf(40L, 0L), DistributedTransferMath.mapSent(40, expected))
        assertEquals(listOf(100L, 20L), DistributedTransferMath.mapSent(120, expected))
        assertEquals(listOf(100L, 50L), DistributedTransferMath.mapSent(9_999, expected))
    }

    @Test
    fun cacheHitWhenFarLessWasSent() {
        assertTrue(DistributedTransferMath.isCacheHit(10, 1000))
        assertFalse(DistributedTransferMath.isCacheHit(900, 1000))
        assertFalse(DistributedTransferMath.isCacheHit(0, 0))
        val sampler = DistributedTransferSampler(listOf("a" to 1000L, "b" to 1000L))
        sampler.sample(0, 0)
        sampler.sample(1_050, 1_000)
        val final = sampler.finish()
        assertFalse(final[0].fromCache)
        assertTrue(final[1].fromCache)
    }

    @Test
    fun samplerReportsActiveHelperRateAndEta() {
        val mb = 1024L * 1024L
        val sampler = DistributedTransferSampler(listOf("a" to 100 * mb, "b" to 100 * mb))
        sampler.sample(5_000, 0)
        val out = sampler.sample(5_000 + 10 * mb, 1_000)
        assertTrue(out[0].active)
        assertFalse(out[1].active)
        assertEquals(10 * mb, out[0].bytesPerSec)
        assertEquals(9, out[0].etaSeconds)
        assertEquals(19, out[1].etaSeconds)
    }

    @Test
    fun etaFormatting() {
        assertEquals("38 s", DistributedTransferMath.formatEta(38))
        assertEquals("2 min 05 s", DistributedTransferMath.formatEta(125))
        assertEquals("1 h 03 min", DistributedTransferMath.formatEta(3_780))
        assertEquals("—", DistributedTransferMath.formatEta(null))
    }

    @Test
    fun readsTensorSizesFromGgufHeader() {
        val out = ByteArrayOutputStream()
        fun le(n: Int, v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array(), 0, n)
        fun str(s: String) { le(8, s.length.toLong()); out.write(s.toByteArray()) }
        out.write("GGUF".toByteArray()); le(4, 3); le(8, 2); le(8, 1)
        str("general.name"); le(4, 8); str("x")
        // tensor: blk.0.w offset 0, blk.1.w offset 64
        str("blk.0.w"); le(4, 1); le(8, 4); le(4, 0); le(8, 0)
        str("blk.1.w"); le(4, 1); le(8, 4); le(4, 0); le(8, 64)
        val header = out.size()
        val dataStart = (header + 31) / 32 * 32
        val total = dataStart + 64 + 96L
        val table = GgufTensorTable.read(out.toByteArray().inputStream(), total)
        assertNotNull(table)
        assertEquals(listOf(64L, 96L), table!!.map { it.bytes })
    }
}
