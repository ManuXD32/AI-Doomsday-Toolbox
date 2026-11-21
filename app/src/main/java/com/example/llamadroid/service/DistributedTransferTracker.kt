package com.example.llamadroid.service

import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** One tensor of a GGUF file with its stored size in bytes. */
data class GgufTensorEntry(val name: String, val bytes: Long)

/** Minimal GGUF reader that returns the tensor table (name + stored size), skipping metadata values. */
object GgufTensorTable {
    private const val MAGIC = 0x46554747L // "GGUF" little endian
    private const val MAX_TENSORS = 1_000_000L

    fun read(path: String): List<GgufTensorEntry>? = runCatching {
        val file = File(path)
        val length = file.length()
        BufferedInputStream(FileInputStream(file), 1 shl 16).use { read(it, length) }
    }.getOrNull()

    internal fun read(input: InputStream, fileLength: Long): List<GgufTensorEntry>? {
        val r = Reader(input)
        if (r.u32() != MAGIC) return null
        val version = r.u32()
        if (version !in 2L..3L) return null
        val tensorCount = r.u64()
        val kvCount = r.u64()
        if (tensorCount !in 0..MAX_TENSORS || kvCount !in 0..MAX_TENSORS) return null
        var alignment = 32L
        repeat(kvCount.toInt()) {
            val key = r.string()
            val type = r.u32().toInt()
            if (key == "general.alignment" && type == 4) alignment = r.u32().coerceAtLeast(1L)
            else r.skipValue(type)
        }
        val infos = ArrayList<Pair<String, Long>>(tensorCount.toInt())
        repeat(tensorCount.toInt()) {
            val name = r.string()
            val dims = r.u32()
            repeat(dims.toInt()) { r.u64() }
            r.u32() // ggml type
            infos += name to r.u64()
        }
        val dataStart = (r.pos + alignment - 1) / alignment * alignment
        val sorted = infos.sortedBy { it.second }
        val sizes = HashMap<String, Long>()
        sorted.forEachIndexed { index, (name, offset) ->
            val end = sorted.getOrNull(index + 1)?.second ?: (fileLength - dataStart)
            sizes[name] = (end - offset).coerceAtLeast(0L)
        }
        return infos.map { GgufTensorEntry(it.first, sizes[it.first] ?: 0L) }
    }

    private class Reader(private val input: InputStream) {
        var pos = 0L
            private set
        private val buf = ByteArray(8)

        private fun fill(n: Int) {
            var off = 0
            while (off < n) {
                val c = input.read(buf, off, n - off)
                if (c < 0) throw java.io.EOFException()
                off += c
            }
            pos += n
        }

        fun u32(): Long {
            fill(4)
            return ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        }

        fun u64(): Long {
            fill(8)
            return ByteBuffer.wrap(buf, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        }

        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) {
                    if (input.read() < 0) throw java.io.EOFException()
                    left -= 1
                    pos += 1
                } else {
                    left -= s
                    pos += s
                }
            }
        }

        fun string(): String {
            val len = u64()
            if (len < 0 || len > 1 shl 20) throw java.io.IOException("bad string")
            val bytes = ByteArray(len.toInt())
            var off = 0
            while (off < bytes.size) {
                val c = input.read(bytes, off, bytes.size - off)
                if (c < 0) throw java.io.EOFException()
                off += c
            }
            pos += len
            return String(bytes, Charsets.UTF_8)
        }

        fun skipValue(type: Int) {
            when (type) {
                0, 1, 7 -> skip(1)
                2, 3 -> skip(2)
                4, 5, 6 -> skip(4)
                10, 11, 12 -> skip(8)
                8 -> skip(u64())
                9 -> {
                    val elementType = u32().toInt()
                    val count = u64()
                    val fixed = when (elementType) {
                        0, 1, 7 -> 1L
                        2, 3 -> 2L
                        4, 5, 6 -> 4L
                        10, 11, 12 -> 8L
                        else -> 0L
                    }
                    if (fixed > 0) skip(count * fixed)
                    else for (i in 0 until count) skipValue(elementType)
                }
                else -> throw java.io.IOException("bad gguf value type $type")
            }
        }
    }
}

/** Live transfer state of one helper. */
data class HelperTransfer(
    val address: String,
    val expectedBytes: Long,
    val sentBytes: Long,
    val bytesPerSec: Long = 0L,
    val etaSeconds: Int? = null,
    val active: Boolean = false,
    /** Loading finished with far less data sent than expected: the helper already had the weights. */
    val fromCache: Boolean = false,
    val finished: Boolean = false
) {
    val fraction: Float
        get() = if (finished && fromCache) 1f
        else if (expectedBytes <= 0L) 0f
        else (sentBytes.toDouble() / expectedBytes).toFloat().coerceIn(0f, 1f)
}

object DistributedTransferMath {
    /** Below this share of the expected bytes, a finished helper counts as served from its cache. */
    const val CACHE_HIT_FRACTION = 0.2

    /**
     * Bytes of each offloadable layer, index 0 until [offloadableLayers]. Tensors named `blk.N.*` go to layer N,
     * `token_embd*` stays on the master, every other tensor (output, norms) goes to the last layer.
     */
    fun layerBytes(tensors: List<GgufTensorEntry>, offloadableLayers: Int): LongArray {
        val result = LongArray(offloadableLayers.coerceAtLeast(0))
        if (result.isEmpty()) return result
        for (tensor in tensors) {
            val index = when {
                tensor.name.startsWith("blk.") ->
                    tensor.name.removePrefix("blk.").substringBefore('.').toIntOrNull()
                tensor.name.startsWith("token_embd") -> null
                else -> result.lastIndex
            } ?: continue
            if (index in result.indices) result[index] += tensor.bytes
        }
        return result
    }

    /**
     * Expected bytes per helper address, in device order. Layers are handed out to the allocations in list order
     * (layer 0 first); only worker allocations are reported.
     */
    fun expectedPerHelper(
        layerBytes: LongArray,
        allocations: List<DistributedLayerAllocation>,
        workers: List<DistributedWorkerLaunchSpec>
    ): List<Pair<String, Long>> {
        var next = 0
        val out = LinkedHashMap<String, Long>()
        for (allocation in allocations) {
            val count = allocation.assignedLayers.coerceAtLeast(0)
            var bytes = 0L
            for (i in next until next + count) if (i in layerBytes.indices) bytes += layerBytes[i]
            next += count
            if (allocation.device.kind != DistributedDeviceKind.WORKER) continue
            val address = allocation.device.workerAddress?.takeIf(String::isNotBlank)
                ?: workers.firstOrNull { it.workerId != null && it.workerId == allocation.device.workerId }?.address
                ?: continue
            out[address] = (out[address] ?: 0L) + bytes
        }
        return out.map { it.key to it.value }
    }

    /** Maps cumulative transmitted bytes onto the helpers in order, clamping each to its expected size. */
    fun mapSent(sentTotal: Long, expected: List<Pair<String, Long>>): List<Long> {
        var left = sentTotal.coerceAtLeast(0L)
        return expected.map { (_, size) ->
            val part = minOf(left, size.coerceAtLeast(0L))
            left -= part
            part
        }
    }

    fun isCacheHit(sent: Long, expected: Long): Boolean =
        expected > 0L && sent < expected * CACHE_HIT_FRACTION

    /** "38 s", "2 min 05 s", "1 h 03 min". Null renders as an em dash. */
    fun formatEta(seconds: Int?): String {
        if (seconds == null || seconds < 0) return "—"
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> String.format(Locale.ROOT, "%d min %02d s", seconds / 60, seconds % 60)
            else -> String.format(Locale.ROOT, "%d h %02d min", seconds / 3600, (seconds % 3600) / 60)
        }
    }

    fun formatMegabytes(bytes: Long): String = (bytes / (1024L * 1024L)).toString()

    fun formatRate(bytesPerSec: Long): String =
        String.format(Locale.ROOT, "%.1f", bytesPerSec / (1024.0 * 1024.0))
}

/** Turns TX counter samples into per-helper progress. Pure, driven by the caller's clock. */
class DistributedTransferSampler(private val expected: List<Pair<String, Long>>) {
    private var baseline = -1L
    private var lastTx = 0L
    private var lastAt = 0L
    private var smoothedRate = 0.0
    private var sent = 0L

    fun sample(txBytes: Long, nowMs: Long): List<HelperTransfer> {
        if (baseline < 0) {
            baseline = txBytes
            lastTx = txBytes
            lastAt = nowMs
        }
        val dt = (nowMs - lastAt).coerceAtLeast(1L)
        if (nowMs > lastAt) {
            val instant = (txBytes - lastTx).coerceAtLeast(0L) * 1000.0 / dt
            smoothedRate = if (smoothedRate == 0.0) instant else smoothedRate * 0.7 + instant * 0.3
            lastTx = txBytes
            lastAt = nowMs
        }
        sent = (txBytes - baseline).coerceAtLeast(0L)
        val mapped = DistributedTransferMath.mapSent(sent, expected)
        var cumulative = 0L
        var activeAssigned = false
        return expected.mapIndexed { i, (address, size) ->
            cumulative += size
            val done = size <= 0L || mapped[i] >= size
            val active = !done && !activeAssigned
            if (active) activeAssigned = true
            val rate = smoothedRate.toLong()
            val eta = if (done) 0 else if (rate > 0) ((cumulative - sent) / rate).toInt() else null
            HelperTransfer(address, size, mapped[i], if (active) rate else 0L, eta, active, finished = done)
        }
    }

    /** Final state once the server is ready. */
    fun finish(): List<HelperTransfer> {
        val mapped = DistributedTransferMath.mapSent(sent, expected)
        return expected.mapIndexed { i, (address, size) ->
            HelperTransfer(
                address, size, mapped[i],
                fromCache = DistributedTransferMath.isCacheHit(mapped[i], size),
                finished = true
            )
        }
    }
}

/** Cross-process holder: the runtime process publishes JSON, the app process receives it into [transfers]. */
object DistributedTransferTracker {
    private val gson = Gson()
    private val _transfers = MutableStateFlow<List<HelperTransfer>>(emptyList())
    val transfers: StateFlow<List<HelperTransfer>> = _transfers.asStateFlow()

    fun encode(items: List<HelperTransfer>): String = gson.toJson(items)

    fun receive(value: String) {
        _transfers.value = if (value.isBlank()) emptyList() else
            runCatching { gson.fromJson(value, Array<HelperTransfer>::class.java).toList() }
                .getOrDefault(emptyList())
    }

    fun clear() {
        _transfers.value = emptyList()
    }

    /** Expected bytes per helper for a resolved launch, or empty when the model cannot be read. */
    fun expectedFor(launch: ResolvedDistributedLaunch): List<Pair<String, Long>> = runCatching {
        val profile = launch.profile
        if (profile.mainLayerAllocations.isEmpty()) return@runCatching emptyList()
        val tensors = GgufTensorTable.read(profile.config.modelPath) ?: return@runCatching emptyList()
        val layers = DistributedTransferMath.layerBytes(tensors, profile.offloadableLayers)
        DistributedTransferMath.expectedPerHelper(layers, profile.mainLayerAllocations, profile.workers)
    }.getOrDefault(emptyList())
}
